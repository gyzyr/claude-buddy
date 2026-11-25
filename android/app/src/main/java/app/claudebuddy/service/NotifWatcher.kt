package app.claudebuddy.service

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import app.claudebuddy.data.FeedStore
import app.claudebuddy.data.PcClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Видит уведомления мессенджеров и почты и передаёт их мини-Claude на ПК —
 * там Claude решает, важно ли (кто-то ждёт ответа), и показывает облачко над головой.
 * Остальные приложения не читаются. Если ПК офлайн — последние сообщения ждут в очереди.
 */
class NotifWatcher : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recent = LinkedHashMap<String, Long>() // защита от дублей (одно сообщение приходит несколько раз)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val appName = WATCH[sbn.packageName] ?: return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return  // «3 новых сообщения» — сводка, пропускаем
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return

        val now = System.currentTimeMillis()
        val key = "${sbn.packageName}|$title|$text"
        synchronized(recent) {
            recent.entries.removeIf { now - it.value > 15 * 60_000 }
            if (recent.containsKey(key)) return
            recent[key] = now
        }
        val item = JSONObject()
            .put("app", appName).put("pkg", sbn.packageName)
            .put("title", title.take(80)).put("text", text.take(300))
            .put("conversation", ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.take(80) ?: "")
            .put("isGroup", ex.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false))
            .put("category", n.category ?: "")
            .put("time", now)
        enqueue(this, item)
        scope.launch { flush(this@NotifWatcher) }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        /** Какие приложения слушаем: мессенджеры и почта. */
        val WATCH = mapOf(
            "com.whatsapp" to "WhatsApp", "com.whatsapp.w4b" to "WhatsApp Business",
            "org.telegram.messenger" to "Telegram", "org.telegram.messenger.web" to "Telegram", "org.thunderdog.challegram" to "Telegram X",
            "com.instagram.android" to "Instagram", "com.facebook.orca" to "Messenger",
            "com.discord" to "Discord", "com.viber.voip" to "Viber", "org.thoughtcrime.securesms" to "Signal",
            "com.samsung.android.messaging" to "SMS", "com.google.android.apps.messaging" to "SMS",
            "com.Slack" to "Slack", "com.microsoft.teams" to "Teams",
            "com.google.android.gm" to "Gmail", "com.microsoft.office.outlook" to "Outlook",
            "com.samsung.android.email.provider" to "Почта",
        )

        private val queue = ArrayDeque<JSONObject>()

        private fun enqueue(ctx: Context, item: JSONObject) = synchronized(queue) {
            queue.addLast(item)
            while (queue.size > 20) queue.removeFirst()
        }

        /** Отправить очередь на ПК (зовётся и из таймера службы лент, когда ПК снова на связи). */
        suspend fun flush(ctx: Context) {
            val items = synchronized(queue) { queue.toList() }
            if (items.isEmpty()) return
            val s = FeedStore.get(ctx).settings.first()
            val pc = PcClient(s.brainUrl, s.brainToken)
            for (item in items) {
                if (System.currentTimeMillis() - item.optLong("time") > 6 * 3600_000) { synchronized(queue) { queue.remove(item) }; continue }
                val sent = runCatching { pc.notify(item) }.isSuccess
                if (!sent) return // ПК недоступен — попробуем позже
                synchronized(queue) { queue.remove(item) }
            }
        }

        fun enabled(ctx: Context) = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    }
}
