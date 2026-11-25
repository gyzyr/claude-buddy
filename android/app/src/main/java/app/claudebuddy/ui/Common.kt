package app.claudebuddy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.claudebuddy.data.FeedStore
import app.claudebuddy.data.Settings
import app.claudebuddy.data.Today
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color.White).padding(16.dp), content = content)

@Composable
fun CardTitle(text: String) = Text(text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Ink)

@Composable
fun Muted(text: String, modifier: Modifier = Modifier) = Text(text, color = Ink.copy(alpha = 0.65f), fontSize = 14.sp, modifier = modifier)

fun plural(n: Int, one: String, few: String, many: String): String {
    val m10 = n % 10; val m100 = n % 100
    return when {
        m10 == 1 && m100 != 11 -> one
        m10 in 2..4 && m100 !in 12..14 -> few
        else -> many
    }
}

fun nowLabel(): String = LocalDateTime.now().format(DateTimeFormatter.ofPattern(if (Lang.en) "HH:mm, EEEE, MMMM d" else "HH:mm, EEEE d MMMM", Lang.locale))

/** Контекст дня для мини-Claude: статистика телефона и расписание. */
fun dayContext(today: Today, s: Settings, events: List<app.claudebuddy.data.CalEvent>): JSONObject =
    JSONObject()
        .put("localTime", nowLabel())
        .put("lang", Lang.code)
        .put("videosToday", today.totalVideos).put("videoLimit", s.videoLimit)
        .put("minutesToday", today.totalMinutes)
        .put("events", JSONArray(events.take(12).map {
            val day = if (it.begin.toLocalDate() == java.time.LocalDate.now()) "" else tr("завтра ", "tomorrow ")
            JSONObject().put("time", day + it.timeLabel).put("title", it.title)
        }))

suspend fun FeedStore.currentSettings(): Settings = settings.first()
