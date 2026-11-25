package app.claudebuddy.service

import app.claudebuddy.ui.tr
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import app.claudebuddy.R
import app.claudebuddy.data.CalendarRepo
import app.claudebuddy.data.FeedStore
import app.claudebuddy.data.PcClient
import app.claudebuddy.ui.MainActivity
import app.claudebuddy.ui.dayContext
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime

/**
 * Фоновые дела, которые служба делает по таймеру:
 *  • раз в 2 минуты отправляет мини-Claude на ПК снимок дня (ролики, время, расписание),
 *    чтобы на ПК можно было спросить «сколько я сегодня скроллил?»;
 *  • в [BRIEFING_HOUR]:00 присылает утренний брифинг уведомлением (от Claude, а без ПК — простой список).
 */
class DailyBriefing(private val ctx: Context, private val store: FeedStore) {
    private val prefs = ctx.getSharedPreferences("briefing", Context.MODE_PRIVATE)
    private var lastSnapshot = 0L
    private var lastWidget = 0L

    suspend fun onTick() {
        val now = System.currentTimeMillis()
        store.reimportBrainConfig() // ПК мог положить новый адрес/токен
        NotifWatcher.flush(ctx)     // сообщения, пришедшие, пока ПК был офлайн
        runCommands()
        if (now - lastSnapshot > 120_000) { lastSnapshot = now; pushSnapshot() }
        // виджет: статус ПК и календарь раз в минуту, пока экран включён
        val screenOn = ctx.getSystemService(android.os.PowerManager::class.java).isInteractive
        if (screenOn && now - lastWidget > 60_000) {
            lastWidget = now
            runCatching { app.claudebuddy.widget.WidgetUpdater.refresh(ctx, fetchPc = true) }
        }
        val today = LocalDate.now().toString()
        if (LocalTime.now().hour >= BRIEFING_HOUR && LocalTime.now().hour < 13 && prefs.getString("sent", "") != today) {
            prefs.edit().putString("sent", today).apply()
            sendBriefing()
        }
    }

    /** Что попросили сделать через Claude на ПК или в чате. */
    private suspend fun runCommands() {
        val s = store.settings.first()
        for (cmd in PcClient(s.brainUrl, s.brainToken).commands()) when (cmd) {
            "resetToday" -> {
                store.resetToday()
                app.claudebuddy.widget.WidgetUpdater.poke(ctx)
                lastSnapshot = 0 // сразу отправить ПК новые цифры
            }
        }
    }

    private suspend fun context(): JSONObject {
        val s = store.settings.first()
        return dayContext(store.today.value, s, CalendarRepo.upcoming(ctx))
    }

    private suspend fun pushSnapshot() {
        val s = store.settings.first()
        runCatching { PcClient(s.brainUrl, s.brainToken).pushPhone(context()) }
    }

    private suspend fun sendBriefing() {
        val s = store.settings.first()
        val events = CalendarRepo.upcoming(ctx).filter { it.begin.toLocalDate() == LocalDate.now() }
        val text = PcClient(s.brainUrl, s.brainToken).summary(context())
            ?: buildString {
                append(if (events.isEmpty()) tr("Сегодня в календаре пусто — свободный день.", "Your calendar is empty today — a free day.") else tr("Сегодня: ", "Today: "))
                append(events.take(4).joinToString(", ") { "${it.timeLabel} ${it.title}" })
                append(tr(". Лимит лент — ${s.videoLimit} роликов. Хорошего дня!", ". Feed limit: ${s.videoLimit} videos. Have a great day!"))
            }
        notify(tr("Доброе утро! ☀️", "Good morning! ☀️"), text)
    }

    private fun notify(title: String, text: String) {
        if (ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, tr("Брифинги Claude", "Claude briefings"), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(1, NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_mascot)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build())
    }

    companion object {
        const val CHANNEL = "briefing"
        const val BRIEFING_HOUR = 9
    }
}
