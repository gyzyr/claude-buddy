package app.claudebuddy.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import app.claudebuddy.R
import app.claudebuddy.data.CalEvent
import app.claudebuddy.data.CalendarRepo
import app.claudebuddy.data.FeedStore
import app.claudebuddy.data.PcClient
import app.claudebuddy.data.PcStatus
import app.claudebuddy.ui.MainActivity
import app.claudebuddy.ui.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Виджет на домашний экран: сколько роликов сегодня (из лимита), следующее событие
 * и «!», когда мини-Claude на ПК закончил работу и ждёт вас (или лимит лент исчерпан).
 *
 * Обновляется из службы FeedWatcherService (при новом ролике и раз в минуту),
 * а системой — раз в 30 минут (updatePeriodMillis).
 */
class ClaudeWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { WidgetUpdater.refresh(ctx, fetchPc = true) } finally { pending.finish() }
        }
    }
}

object WidgetUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var lastPc: PcStatus? = null
    @Volatile private var lastEvent: CalEvent? = null
    @Volatile private var eventsAt = 0L

    private fun ids(ctx: Context): IntArray =
        AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, ClaudeWidget::class.java))

    /** Быстрое обновление без сети (например, после нового ролика). */
    fun poke(ctx: Context) {
        if (ids(ctx).isEmpty()) return
        scope.launch { refresh(ctx, fetchPc = false) }
    }

    suspend fun refresh(ctx: Context, fetchPc: Boolean) {
        val ids = ids(ctx)
        if (ids.isEmpty()) return
        val store = FeedStore.get(ctx)
        store.awaitLoaded()
        val s = store.settings.first()
        if (fetchPc) lastPc = PcClient(s.brainUrl, s.brainToken).status()
        val now = System.currentTimeMillis()
        if (fetchPc || now - eventsAt > 5 * 60_000) {
            eventsAt = now
            lastEvent = CalendarRepo.upcoming(ctx).firstOrNull()
        }
        val views = build(ctx, store.today.value.totalVideos, s.videoLimit, lastEvent, lastPc,
            calendarAllowed = CalendarRepo.hasPermission(ctx))
        AppWidgetManager.getInstance(ctx).updateAppWidget(ids, views)
    }

    private fun build(
        ctx: Context, videos: Long, limit: Int, event: CalEvent?, pc: PcStatus?, calendarAllowed: Boolean,
    ): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_claude)

        // ролики
        v.setTextViewText(R.id.w_count, "$videos")
        v.setTextViewText(R.id.w_limit, tr("/ $limit роликов", "/ $limit videos"))
        v.setProgressBar(R.id.w_progress, limit, videos.coerceAtMost(limit.toLong()).toInt(), false)

        // следующее событие
        v.setTextViewText(R.id.w_event, when {
            !calendarAllowed -> tr("📅 Откройте приложение, чтобы видеть календарь", "📅 Open the app to see your calendar")
            event == null -> tr("📅 Дальше ничего нет — свободно", "📅 Nothing next — you're free")
            else -> {
                val day = if (event.begin.toLocalDate() == LocalDate.now().plusDays(1)) tr("завтра ", "tomorrow ") else ""
                "📅 $day${event.timeLabel} · ${event.title}"
            }
        })

        // зачем вы нужны Claude
        val need = when {
            pc?.activity == "alert" -> tr("Claude закончил${pc.project?.let { " в «$it»" } ?: ""} — глянь", "Claude is done${pc.project?.let { " in “$it”" } ?: ""} — take a look")
            videos >= limit -> tr("Лимит роликов на сегодня исчерпан", "Daily video limit reached")
            else -> null
        }
        v.setViewVisibility(R.id.w_alert, if (need != null) View.VISIBLE else View.GONE)
        // при тревоге Claude поднимает руки, над головой «!»
        v.setImageViewResource(R.id.w_mascot, if (need != null) R.drawable.ic_mascot_alert else R.drawable.ic_mascot)
        if (need != null) v.setTextViewText(R.id.w_alert, "❗ $need")

        // статус ПК под маскотом
        v.setTextViewText(R.id.w_pc, when (pc?.activity) {
            null -> tr("ПК офлайн", "PC offline")
            "coding" -> tr("кодит", "coding")
            "alert" -> tr("ждёт", "waiting")
            else -> tr("отдыхает", "resting")
        })

        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE)
        v.setOnClickPendingIntent(R.id.w_root, open)
        return v
    }
}
