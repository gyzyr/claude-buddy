package app.claudebuddy.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.claudebuddy.data.ChatStore
import app.claudebuddy.data.FeedStore
import app.claudebuddy.data.PcClient
import app.claudebuddy.data.PcStatus
import app.claudebuddy.data.Settings
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = FeedStore.get(this)
        val chat = ChatStore.get(this)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = ClaudeOrange, background = Cream, surface = Cream)) {
                App(store, chat)
            }
        }
    }
}

private enum class Tab(private val ru: String, private val en: String, val icon: String) {
    HOME("Главная", "Home", "🏠"), FEEDS("Ленты", "Feeds", "📱"), CLAUDE("Claude", "Claude", ""), SETTINGS("Настройки", "Settings", "⚙️");
    val label get() = tr(ru, en)
}

@Composable
private fun App(store: FeedStore, chat: ChatStore) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val settings by store.settings.collectAsState(initial = Settings(language = Lang.code))
    var pc by remember { mutableStateOf<PcStatus?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current

    // разрешение на уведомления — для утреннего брифинга
    val askNotif = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            askNotif.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    // Раз в 10 секунд спрашиваем мини-Claude на ПК, чем он занят
    LaunchedEffect(settings.brainUrl, settings.brainToken) {
        while (true) {
            pc = PcClient(settings.brainUrl, settings.brainToken).status()
            delay(10_000)
        }
    }
    val online = pc != null

    // смена языка — пересобираем весь интерфейс с новыми текстами
    androidx.compose.runtime.key(settings.language) {
    Column(Modifier.fillMaxSize().background(Cream).safeDrawingPadding()) {
        Box(Modifier.weight(1f)) {
            when (Tab.entries[tab]) {
                Tab.HOME -> HomeScreen(store, pc, online)
                Tab.FEEDS -> FeedScreen(store)
                Tab.CLAUDE -> ChatScreen(store, chat, online)
                Tab.SETTINGS -> SettingsScreen(store, online)
            }
        }
        NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
            Tab.entries.forEachIndexed { i, t ->
                NavigationBarItem(
                    selected = tab == i,
                    onClick = { tab = i },
                    icon = { TabIcon(t, selected = tab == i) },
                    label = { Text(t.label, fontSize = 12.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedTextColor = ClaudeOrange, indicatorColor = ClaudeOrange.copy(alpha = 0.15f),
                        unselectedTextColor = Ink.copy(alpha = 0.6f),
                    ),
                )
            }
        }
    }
    }
}

@Composable
private fun TabIcon(t: Tab, selected: Boolean) =
    if (t == Tab.CLAUDE) Mascot(if (selected) Mood.HAPPY else Mood.SLEEPY, size = 30.dp)
    else Text(t.icon, fontSize = 20.sp)
