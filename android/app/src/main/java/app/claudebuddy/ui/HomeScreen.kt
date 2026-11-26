package app.claudebuddy.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.claudebuddy.data.CalEvent
import app.claudebuddy.data.CalendarRepo
import app.claudebuddy.data.FeedStore
import app.claudebuddy.data.PcClient
import app.claudebuddy.data.PcStatus
import app.claudebuddy.data.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.random.Random

/** Сводка, полученная от мини-Claude, живёт 30 минут, чтобы не тратить лимиты на каждый заход. */
private object SummaryCache { var text: String? = null; var at = 0L }

@Composable
fun HomeScreen(store: FeedStore, pc: PcStatus?, pcOnline: Boolean) {
    val ctx = LocalContext.current
    val today by store.today.collectAsState()
    val settings by store.settings.collectAsState(initial = Settings())
    var events by remember { mutableStateOf<List<CalEvent>>(emptyList()) }
    var calAllowed by remember { mutableStateOf(CalendarRepo.hasPermission(ctx)) }
    var summary by remember { mutableStateOf(SummaryCache.text) }
    var loadingSummary by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val askCalendar = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { calAllowed = it }

    LaunchedEffect(calAllowed) { while (true) { events = CalendarRepo.upcoming(ctx); delay(60_000) } }

    fun loadSummary() {
        loadingSummary = true
        scope.launch {
            val text = PcClient(settings.brainUrl, settings.brainToken).summary(dayContext(today, settings, events))
            if (!text.isNullOrBlank()) { summary = text; SummaryCache.text = text; SummaryCache.at = System.currentTimeMillis() }
            loadingSummary = false
        }
    }
    LaunchedEffect(pcOnline, events) {
        if (pcOnline && !loadingSummary && (SummaryCache.text == null || System.currentTimeMillis() - SummaryCache.at > 30 * 60_000)) loadSummary()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        Text(greeting(), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ink)
        Muted(nowLabel())
        Spacer(Modifier.height(12.dp))

        Room(pc, pcOnline)
        Spacer(Modifier.height(12.dp))

        // ---- Сводка дня ----
        Card {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                CardTitle(tr("Сводка дня", "Today's summary"))
                if (pcOnline) TextButton(onClick = { loadSummary() }, enabled = !loadingSummary) { Text(tr("Обновить", "Refresh"), color = ClaudeOrange) }
            }
            val v = today.totalVideos.toInt()
            Fact("📱", tr("Телефон", "Phone"), tr("$v ${plural(v, "ролик", "ролика", "роликов")} из ${settings.videoLimit} · ${today.totalMinutes} мин в лентах",
                "$v of ${settings.videoLimit} ${countWord(v, "", "", "", "video")} · ${today.totalMinutes} min in feeds"))
            if (pcOnline && pc != null) {
                val projects = pc.projects.take(3).joinToString(", ") { it.name }
                Fact("💻", tr("ПК", "PC"), if (pc.sessionsToday == 0) tr("сегодня без Claude Code", "no Claude Code today") else
                    "${pc.sessionsToday} ${countWord(pc.sessionsToday, "сессия", "сессии", "сессий", "session")} Claude Code · $projects")
            } else Fact("💻", tr("ПК", "PC"), tr("офлайн — данные подтянутся, когда проснётся", "offline — data will sync when it wakes up"))
            Spacer(Modifier.height(8.dp))
            when {
                loadingSummary -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), color = ClaudeOrange, strokeWidth = 2.dp)
                    Muted(tr("  Claude собирает сводку…", "  Claude is writing a summary…"))
                }
                summary != null -> Text(summary!!, color = Ink, fontSize = 15.sp, lineHeight = 21.sp)
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- Расписание ----
        Card {
            CardTitle(tr("Расписание", "Schedule"))
            Spacer(Modifier.height(6.dp))
            if (!calAllowed) {
                Muted(tr("Дай доступ к календарю — покажу события из Outlook и Google.", "Allow calendar access to see your Outlook and Google events."))
                Spacer(Modifier.height(8.dp))
                Button({ askCalendar.launch(Manifest.permission.READ_CALENDAR) }, shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeOrange)) { Text(tr("Разрешить календарь", "Allow calendar")) }
            } else {
                val now = LocalDateTime.now()
                val todayEvents = events.filter { it.begin.toLocalDate() <= LocalDate.now() || (it.allDay && it.begin.toLocalDate() == LocalDate.now()) }
                val tomorrow = events.filter { it.begin.toLocalDate() == LocalDate.now().plusDays(1) }
                val next = todayEvents.firstOrNull { !it.allDay && it.end.isAfter(now) } ?: tomorrow.firstOrNull { !it.allDay }
                if (next != null) NextEvent(next, now)
                if (todayEvents.isEmpty()) Muted(tr("На сегодня больше ничего — свободный день 🌿", "Nothing else today — a free day 🌿"))
                todayEvents.filter { it != next }.forEach { EventRow(it) }
                if (tomorrow.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp)); Muted(tr("Завтра", "Tomorrow"))
                    tomorrow.filter { it != next }.take(5).forEach { EventRow(it) }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..11 -> tr("Доброе утро!", "Good morning!")
    in 12..17 -> tr("Добрый день!", "Good afternoon!")
    in 18..22 -> tr("Добрый вечер!", "Good evening!")
    else -> tr("Доброй ночи!", "Good night!")
}

@Composable
private fun Fact(icon: String, label: String, value: String) =
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text("$icon ", fontSize = 15.sp)
        Text("$label: ", fontWeight = FontWeight.SemiBold, color = Ink, fontSize = 15.sp)
        Text(value, color = Ink.copy(alpha = 0.8f), fontSize = 15.sp)
    }

@Composable
private fun NextEvent(e: CalEvent, now: LocalDateTime) {
    val mins = Duration.between(now, e.begin).toMinutes()
    val whenText = when {
        e.begin.isBefore(now) -> tr("идёт сейчас", "happening now")
        mins < 60 -> tr("через $mins мин", "in $mins min")
        e.begin.toLocalDate() != LocalDate.now() -> tr("завтра в ${e.timeLabel}", "tomorrow at ${e.timeLabel}")
        else -> tr("в ${e.timeLabel}", "at ${e.timeLabel}")
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(ClaudeOrange.copy(alpha = 0.12f)).padding(12.dp)) {
        Text(tr("Дальше · $whenText", "Next · $whenText"), color = ClaudeOrange, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Text(e.title, color = Ink, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Muted("${e.timeLabel}–${e.end.toLocalTime().toString().take(5)} · ${if (e.isOutlook) "Outlook" else e.calendar}")
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun EventRow(e: CalEvent) =
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(e.timeLabel, color = Ink.copy(alpha = 0.6f), fontSize = 14.sp, modifier = Modifier.width(78.dp))
        Text(e.title, color = Ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
        if (e.isOutlook) Text("Outlook", color = Color(0xFF0F6CBD), fontSize = 11.sp)
    }

/**
 * Комната, где живёт Claude. Если ПК онлайн — повторяет мини-Claude: кодит, зовёт «!».
 * Если офлайн — живёт сам: гуляет, отдыхает, ночью спит.
 */
@Composable
private fun Room(pc: PcStatus?, online: Boolean) {
    var activity by remember { mutableStateOf(Activity.IDLE) }
    var facingLeft by remember { mutableStateOf(false) }
    val x = remember { Animatable(0.45f) }
    val mode = when {
        online && pc?.activity == "coding" -> "coding"
        online && pc?.activity == "alert" -> "alert"
        else -> "free"
    }

    LaunchedEffect(mode) {
        when (mode) {
            "coding" -> { activity = Activity.WALK; facingLeft = x.value > 0.3f; x.animateTo(0.3f, tween(1500, easing = LinearEasing)); activity = Activity.CODING }
            "alert" -> { activity = Activity.ALERT }
            else -> while (true) {
                val hour = LocalTime.now().hour
                if (hour >= 23 || hour < 7) { activity = Activity.SLEEP; delay(30_000); continue }
                val target = Random.nextFloat() * 0.8f + 0.1f
                facingLeft = target < x.value
                activity = Activity.WALK
                x.animateTo(target, tween((kotlin.math.abs(target - x.value) * 9000).toInt().coerceAtLeast(600), easing = LinearEasing))
                activity = if (Random.nextInt(6) == 0) Activity.SLEEP else Activity.IDLE
                delay(if (activity == Activity.SLEEP) 6000 else Random.nextLong(1500, 4000))
            }
        }
    }

    val bubble = when (mode) {
        "coding" -> tr("Кодю на ПК", "Coding on the PC") + (pc?.project?.let { " · $it" } ?: "")
        "alert" -> tr("На ПК всё готово${pc?.project?.let { " в «$it»" } ?: ""} — загляни!", "Done on the PC${pc?.project?.let { " in “$it”" } ?: ""} — take a look!")
        else -> if (online) tr("ПК отдыхает, а я тут гуляю", "PC is resting, so I'm taking a walk") else tr("ПК офлайн — гуляю сам по себе", "PC is offline — wandering on my own")
    }

    BoxWithConstraints(
        Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFFFFF7EF), Color(0xFFF6E6D6)))),
    ) {
        // пол
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(46.dp).background(Color(0xFFEBD3BC)))
        // реплика
        Text(
            bubble, color = Ink, fontSize = 14.sp,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
                .clip(RoundedCornerShape(14.dp)).background(Color.White).padding(horizontal = 12.dp, vertical = 6.dp),
        )
        val size = 104.dp
        Box(Modifier.align(Alignment.BottomStart).offset(x = (maxWidth - size) * x.value, y = (-6).dp)) {
            Mascot(if (mode == "alert") Mood.HAPPY else if (activity == Activity.SLEEP) Mood.SLEEPY else Mood.HAPPY,
                size = size, activity = activity, facingLeft = facingLeft, interactive = true)
        }
    }
}
