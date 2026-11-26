package app.claudebuddy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.claudebuddy.data.DayStat
import app.claudebuddy.data.FeedApps
import app.claudebuddy.data.FeedStore
import app.claudebuddy.data.Settings
import java.time.format.TextStyle
import java.util.Locale

private val Red = Color(0xFFC0453A)

/** Вкладка «Ленты»: сегодня, неделя, лимиты. */
@Composable
fun FeedScreen(store: FeedStore) {
    val today by store.today.collectAsState()
    val settings by store.settings.collectAsState(initial = Settings())
    var week by remember { mutableStateOf<List<DayStat>>(emptyList()) }
    LaunchedEffect(today) { week = store.lastDays(7) }
    val videos = today.totalVideos.toInt()
    val limit = settings.videoLimit

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Mascot(if (videos >= limit) Mood.WORRIED else Mood.HAPPY, size = 110.dp, interactive = true)
        Spacer(Modifier.height(8.dp))

        Card {
            Muted(tr("Сегодня пролистано", "Scrolled today"))
            Text(tr("$videos из $limit ${plural(limit, "ролика", "роликов", "роликов")}", "$videos of $limit videos"), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.height(8.dp))
            Bar(videos / limit.toFloat())
            Spacer(Modifier.height(12.dp))
            FeedApps.all.distinctBy { it.title }.forEach { app ->
                val pkgs = FeedApps.all.filter { it.title == app.title }.map { it.pkg }
                val v = pkgs.sumOf { today.videos[it] ?: 0L }
                val sec = pkgs.sumOf { today.seconds[it] ?: 0L }
                if (v == 0L && sec == 0L) return@forEach
                val value = if (app.countVideos) "$v ${countWord(v.toInt(), "ролик", "ролика", "роликов", "video")} · ${sec / 60} ${tr("мин", "min")}"
                            else "${sec / 60} ${tr("мин", "min")} ${sec % 60} ${tr("с", "s")}"
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(app.title, color = Ink); Text(value, color = Ink.copy(alpha = 0.7f))
                }
            }
            if (today.videos.isEmpty() && today.seconds.isEmpty()) Muted(tr("Пока ни одного — так держать 🌿", "None yet — keep it up 🌿"))
        }
        Spacer(Modifier.height(12.dp))

        Card {
            Muted(tr("Неделя, роликов в день", "This week, videos per day"))
            Spacer(Modifier.height(8.dp))
            WeekChart(week, limit)
        }
        Spacer(Modifier.height(12.dp))

        LimitsCard(settings) { store.updateSettings(it) }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Bar(fraction: Float) {
    Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Cream)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(5.dp))
            .background(if (fraction >= 1f) Red else ClaudeOrange))
    }
}

@Composable
private fun WeekChart(week: List<DayStat>, limit: Int) {
    val max = maxOf(limit, week.maxOfOrNull { it.videos.toInt() } ?: 0).coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
        week.forEach { day ->
            val v = day.videos.toInt()
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$v", fontSize = 11.sp, color = Ink.copy(alpha = 0.6f))
                Box(Modifier.width(22.dp).height((80f * v / max).coerceAtLeast(2f).dp).clip(RoundedCornerShape(6.dp))
                    .background(if (v > limit) Red else ClaudeOrange))
                Text(day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Lang.locale), fontSize = 11.sp, color = Ink)
            }
        }
    }
}

@Composable
private fun LimitsCard(settings: Settings, save: (Settings) -> Unit) {
    var videoLimit by remember(settings) { mutableStateOf(settings.videoLimit.toString()) }
    var limit by remember(settings) { mutableStateOf(settings.limitMinutes.toString()) }
    Card {
        CardTitle(tr("Лимиты", "Limits"))
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(videoLimit, { videoLimit = it.filter(Char::isDigit).take(4) }, Modifier.fillMaxWidth(),
            label = { Text(tr("Роликов в день (Reels, Shorts, TikTok)", "Videos per day (Reels, Shorts, TikTok)")) })
        OutlinedTextField(limit, { limit = it.filter(Char::isDigit).take(3) }, Modifier.fillMaxWidth(),
            label = { Text(tr("Минут в день (X, Facebook)", "Minutes per day (X, Facebook)")) })
        Spacer(Modifier.height(8.dp))
        Button({ save(settings.copy(videoLimit = videoLimit.toIntOrNull() ?: 50, limitMinutes = limit.toIntOrNull() ?: 30)) },
            shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = ClaudeOrange)) { Text(tr("Сохранить", "Save")) }
    }
}
