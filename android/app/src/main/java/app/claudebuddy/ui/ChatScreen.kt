package app.claudebuddy.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import app.claudebuddy.data.CalendarRepo
import app.claudebuddy.data.ChatMessage
import app.claudebuddy.data.ChatStore
import app.claudebuddy.data.FeedStore
import app.claudebuddy.data.PcClient
import app.claudebuddy.data.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Чат. Два вида собеседника (каналы сверху):
 *  • «Мини-Claude» — лёгкий помощник про день, ленты и расписание;
 *  • проект с ПК — настоящий Claude Code в папке проекта (его CLAUDE.md, память, ваши разрешения).
 * ПК офлайн → сообщение ждёт в очереди и уйдёт, когда ПК проснётся; срочное можно открыть в приложении Claude.
 */
@Composable
fun ChatScreen(store: FeedStore, chat: ChatStore, pcOnline: Boolean) {
    val ctx = LocalContext.current
    val all by chat.messages.collectAsState()
    val channel by chat.channel.collectAsState()
    val jobs by chat.jobs.collectAsState()
    val settings by store.settings.collectAsState(initial = Settings())
    val today by store.today.collectAsState()
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var projects by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val client = PcClient(settings.brainUrl, settings.brainToken)

    val messages = all.filter { it.channel == channel }
    val working = jobs[channel] != null
    val busy = sending || working

    // мини-Claude: отвечает сразу
    suspend fun askMini() {
        sending = true
        runCatching {
            val events = CalendarRepo.upcoming(ctx)
            client.chat(chat.messages.value.filter { it.channel == "" }, dayContext(today, settings, events))
        }.onSuccess { reply ->
            chat.markSent("")
            chat.add(ChatMessage("assistant", reply.ifBlank { "…" }, System.currentTimeMillis()))
        }
        sending = false
    }

    // Claude Code в проекте: ставим задачу, ответ заберём опросом
    suspend fun askCode(project: String) {
        val mine = chat.messages.value.filter { it.channel == project }
        val queued = mine.filter { it.queued && it.role == "user" }
        if (queued.isEmpty()) return
        val fresh = mine.none { it.role == "assistant" } // история очищена → новый разговор
        sending = true
        runCatching { client.codeStart(project, queued.joinToString(separator = System.lineSeparator().repeat(2)) { it.text }, fresh) }
            .onSuccess { id -> chat.markSent(project); chat.setJob(project, id) }
            .onFailure { e ->
                if (e !is java.io.IOException) { // ПК ответил ошибкой — показываем; сеть — ждём в очереди
                    chat.markSent(project)
                    chat.add(ChatMessage("assistant", tr("Не получилось: ", "Failed: ") + e.message, System.currentTimeMillis(), channel = project, error = true))
                }
            }
        sending = false
    }

    suspend fun send(ch: String) = if (ch == "") askMini() else askCode(ch)

    // список проектов с ПК
    LaunchedEffect(pcOnline, settings.brainUrl) { if (pcOnline) client.projects()?.let { projects = it } }

    // ждём ответы Claude Code (по всем каналам), пока открыт экран
    LaunchedEffect(jobs.isNotEmpty(), settings.brainUrl) {
        while (chat.jobs.value.isNotEmpty()) {
            for ((ch, id) in chat.jobs.value) {
                runCatching { client.codeJob(id) }.onSuccess { res ->
                    if (res != null) {
                        chat.setJob(ch, null)
                        chat.add(ChatMessage("assistant", res.second, System.currentTimeMillis(), channel = ch, error = !res.first))
                    }
                }
            }
            delay(3_000)
        }
    }

    // ПК проснулся, а в очереди что-то есть — отправляем
    LaunchedEffect(pcOnline, channel, messages.any { it.queued }) {
        if (pcOnline && !busy && messages.any { it.queued }) send(channel)
    }
    LaunchedEffect(channel, messages.size, busy) { if (messages.isNotEmpty()) list.animateScrollToItem(messages.size - 1 + if (busy) 1 else 0) }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Mascot(if (busy) Mood.THINKING else Mood.HAPPY, size = 48.dp, activity = if (busy) Activity.CODING else Activity.IDLE)
            Column(Modifier.padding(start = 8.dp).weight(1f)) {
                CardTitle(if (channel == "") "Claude" else "Claude Code")
                Muted(when {
                    !pcOnline -> tr("ПК офлайн — отвечу, когда проснётся", "PC offline — I'll reply when it wakes up")
                    channel == "" -> tr("на связи через ПК", "connected via PC")
                    working -> tr("работает в «$channel»…", "working in “$channel”…")
                    else -> tr("в проекте «$channel»", "in project “$channel”")
                })
            }
            if (messages.isNotEmpty() && !working)
                TextButton(onClick = { chat.clear(channel) }) { Text(if (channel == "") tr("Очистить", "Clear") else tr("Новый разговор", "New chat"), color = Ink.copy(alpha = 0.6f)) }
        }

        // с кем говорим
        val channels = (listOf("") + projects + listOfNotNull(channel.takeIf { it.isNotEmpty() && it !in projects })).distinct()
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            channels.forEach { ch ->
                val sel = ch == channel
                Text(
                    (if (ch == "") tr("🙂 Мини-Claude", "🙂 Mini-Claude") else "💻 $ch") + if (jobs[ch] != null) " ⏳" else "",
                    Modifier.clip(RoundedCornerShape(50))
                        .background(if (sel) ClaudeOrange else Color.White)
                        .border(1.dp, ClaudeOrange.copy(alpha = if (sel) 0f else 0.35f), RoundedCornerShape(50))
                        .clickable { chat.select(ch) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    color = if (sel) Color.White else Ink, fontSize = 14.sp,
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (messages.isEmpty()) item {
                Muted(
                    if (channel == "") tr("Спроси что угодно: что у меня сегодня, сколько я скроллил, что было на ПК, помоги спланировать вечер…",
                        "Ask anything: what's on today, how much I scrolled, what happened on the PC, help me plan the evening…")
                    else tr("Это Claude Code в папке «$channel» — как в терминале на ПК: знает проект и может работать с ним. " +
                        "Действия, которые требуют подтверждения, придётся подтвердить с ПК.",
                        "This is Claude Code in the “$channel” folder — like the terminal on your PC: it knows the project and can work on it. " +
                        "Actions that need approval have to be approved on the PC."),
                    Modifier.padding(16.dp))
            }
            items(messages) { m -> Bubble(m) }
            if (busy) item {
                Muted(if (channel == "") tr("Claude думает…", "Claude is thinking…") else tr("Claude Code работает… можно закрыть приложение — ответ появится здесь.", "Claude Code is working… you can close the app — the reply will appear here."), Modifier.padding(8.dp))
            }
        }

        if (!pcOnline && messages.any { it.queued }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(16.dp))
                .background(ClaudeOrange.copy(alpha = 0.1f)).padding(12.dp)) {
                Text(tr("Сообщение в очереди: ПК офлайн. Если срочно — продолжи в приложении Claude, текст скопирую.", "Message queued: the PC is offline. If it's urgent, continue in the Claude app — I'll copy the text."), color = Ink, fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { openInClaudeApp(ctx, messages.lastOrNull { it.role == "user" }?.text ?: "") },
                    shape = RoundedCornerShape(12.dp)) { Text(tr("Открыть в приложении Claude", "Open in the Claude app"), color = Ink) }
            }
        }

        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f),
                placeholder = { Text(if (channel == "") tr("Написать Claude…", "Message Claude…") else tr("Задача для «$channel»…", "Task for “$channel”…")) },
                shape = RoundedCornerShape(18.dp), maxLines = 6)
            Spacer(Modifier.padding(4.dp))
            Button(
                onClick = {
                    val text = input.trim(); input = ""
                    val ch = channel
                    chat.add(ChatMessage("user", text, System.currentTimeMillis(), queued = true, channel = ch))
                    if (pcOnline) scope.launch { send(ch) }
                },
                enabled = input.isNotBlank() && !busy, shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ClaudeOrange),
            ) { Text("➤") }
        }
    }
}

@Composable
private fun Bubble(m: ChatMessage) {
    val mine = m.role == "user"
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(18.dp))
                .background(when { mine -> ClaudeOrange; m.error -> Color(0xFFFBE3DC); else -> Color.White })
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            SelectionContainer { Text(m.text, color = if (mine) Color.White else Ink, fontSize = 15.sp, lineHeight = 21.sp) }
            if (m.queued) Text(tr("в очереди", "queued"), color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
        }
    }
}

/** Копирует текст и открывает официальное приложение Claude (та же подписка). */
private fun openInClaudeApp(ctx: Context, text: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Claude", text))
    val intent = ctx.packageManager.getLaunchIntentForPackage("com.anthropic.claude")
    if (intent != null) {
        Toast.makeText(ctx, tr("Текст скопирован — вставь его в чат", "Text copied — paste it into the chat"), Toast.LENGTH_LONG).show()
        ctx.startActivity(intent)
    } else Toast.makeText(ctx, tr("Приложение Claude не найдено", "Claude app not found"), Toast.LENGTH_SHORT).show()
}
