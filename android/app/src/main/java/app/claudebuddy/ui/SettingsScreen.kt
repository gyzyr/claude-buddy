package app.claudebuddy.ui

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.claudebuddy.data.CalendarRepo
import app.claudebuddy.data.FeedStore
import app.claudebuddy.data.PcClient
import app.claudebuddy.data.Settings
import app.claudebuddy.service.FeedWatcherService
import app.claudebuddy.service.NotifWatcher
import kotlinx.coroutines.launch

fun Context.accessibilityOn(): Boolean {
    val enabled = AndroidSettings.Secure.getString(contentResolver, AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    val me = ComponentName(this, FeedWatcherService::class.java).flattenToString()
    return enabled.split(':').any { it.equals(me, ignoreCase = true) }
}

fun Context.usageAccessOn(): Boolean {
    val ops = getSystemService(AppOpsManager::class.java)
    return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
}

/** Вкладка «Настройки»: язык, доступы и связь с ПК. */
@Composable
fun SettingsScreen(store: FeedStore, pcOnline: Boolean) {
    val ctx = LocalContext.current
    val settings by store.settings.collectAsState(initial = Settings(language = Lang.code))
    var a11y by remember { mutableStateOf(ctx.accessibilityOn()) }
    var usage by remember { mutableStateOf(ctx.usageAccessOn()) }
    var cal by remember { mutableStateOf(CalendarRepo.hasPermission(ctx)) }
    var notif by remember { mutableStateOf(NotifWatcher.enabled(ctx)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        a11y = ctx.accessibilityOn(); usage = ctx.usageAccessOn(); cal = CalendarRepo.hasPermission(ctx); notif = NotifWatcher.enabled(ctx)
    }

    var url by remember(settings) { mutableStateOf(settings.brainUrl) }
    var token by remember(settings) { mutableStateOf(settings.brainToken) }
    var ping by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Card {
            CardTitle(tr("Язык", "Language"))
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LangChip("Русский", "ru", settings.language) { store.setLanguage(it) }
                LangChip("English", "en", settings.language) { store.setLanguage(it) }
            }
            Muted(tr("Claude тоже будет отвечать на этом языке.", "Claude will reply in this language too."), Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(12.dp))

        Card {
            CardTitle(tr("Доступы", "Permissions"))
            Spacer(Modifier.height(6.dp))
            Access(tr("Слежение за лентами", "Feed tracking"), a11y) { ctx.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) }
            Access(tr("История использования", "Usage access"), usage) { ctx.startActivity(Intent(AndroidSettings.ACTION_USAGE_ACCESS_SETTINGS)) }
            Access(tr("Сообщения и почта → облачко на ПК", "Messages & mail → bubble on PC"), notif) {
                ctx.startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            Access(tr("Календарь (Outlook, Google)", "Calendar (Outlook, Google)"), cal) {
                ctx.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${ctx.packageName}")))
            }
        }
        Spacer(Modifier.height(12.dp))

        Card {
            CardTitle(tr("Мини-Claude на ПК", "Mini-Claude on PC"))
            Muted(if (pcOnline) tr("Сейчас на связи ✅", "Connected ✅") else tr("Сейчас офлайн", "Offline"))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text(tr("Адрес", "Address")) }, singleLine = true)
            OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text(tr("Токен (необязательно)", "Token (optional)")) }, singleLine = true)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ store.updateSettings(settings.copy(brainUrl = url, brainToken = token)) }, shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeOrange)) { Text(tr("Сохранить", "Save")) }
                OutlinedButton({
                    ping = tr("Проверяю…", "Checking…")
                    scope.launch { ping = if (PcClient(url, token).status() != null) tr("ПК на связи ✅", "PC is online ✅") else tr("ПК не отвечает", "PC is not responding") }
                }, shape = RoundedCornerShape(14.dp)) { Text(tr("Проверить", "Test"), color = Ink) }
            }
            if (ping.isNotEmpty()) Muted(ping, Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(6.dp))
            Muted(tr("Через Tailscale: http://<IP ПК в Tailscale>:8787. Запасной путь по кабелю (127.0.0.1:8787) работает сам.",
                "Via Tailscale: http://<PC Tailscale IP>:8787. The USB fallback (127.0.0.1:8787) works automatically."))
        }
    }
}

@Composable
private fun LangChip(label: String, code: String, current: String, pick: (String) -> Unit) {
    val sel = code == current
    Text(label,
        Modifier.clip(RoundedCornerShape(50)).background(if (sel) ClaudeOrange else Color.White)
            .border(1.dp, ClaudeOrange.copy(alpha = if (sel) 0f else 0.4f), RoundedCornerShape(50))
            .clickable { pick(code) }.padding(horizontal = 18.dp, vertical = 9.dp),
        color = if (sel) Color.White else Ink, fontSize = 15.sp)
}

@Composable
private fun Access(title: String, on: Boolean, open: () -> Unit) =
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text((if (on) "✅ " else "⚠️ ") + title, color = Ink, modifier = Modifier.weight(1f))
        if (!on) OutlinedButton(onClick = open, shape = RoundedCornerShape(12.dp)) { Text(tr("Включить", "Enable"), color = Ink) }
    }
