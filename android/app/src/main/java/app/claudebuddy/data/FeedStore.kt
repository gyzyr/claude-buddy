package app.claudebuddy.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalDate

private val Context.dataStore by preferencesDataStore("claude_buddy")

data class Settings(
    val videoLimit: Int = 50,
    val limitMinutes: Int = 30,
    val brainUrl: String = "http://127.0.0.1:8787",
    val brainToken: String = "",
    /** Язык интерфейса и ответов Claude: "ru" или "en". */
    val language: String = "ru",
)

/** Сегодняшние счётчики: ролики (Reels/Shorts/TikTok) и секунды (остальные ленты) по приложениям. */
data class Today(val videos: Map<String, Long> = emptyMap(), val seconds: Map<String, Long> = emptyMap()) {
    val totalVideos get() = videos.values.sum()
    val totalMinutes get() = (seconds.values.sum() / 60).toInt()
}

data class DayStat(val date: LocalDate, val videos: Long, val minutes: Int)

/**
 * Хранилище: настройки, счётчики по дням и приложениям, причины, разрешения.
 * Счётчики живут в памяти ([today]) и сбрасываются на диск раз в несколько секунд.
 */
class FeedStore private constructor(private val ctx: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val kVideoLimit = intPreferencesKey("video_limit")
    private val kLimit = intPreferencesKey("limit_minutes")
    private val kBrainUrl = stringPreferencesKey("brain_url")
    private val kBrainToken = stringPreferencesKey("brain_token")
    private val kAllowedUntil = longPreferencesKey("allowed_until")
    private val kAllowedVideos = longPreferencesKey("allowed_videos_until")
    private fun kSec(date: LocalDate, pkg: String) = longPreferencesKey("sec_${date}_$pkg")
    private fun kVid(date: LocalDate, pkg: String) = longPreferencesKey("vid_${date}_$pkg")
    private fun kReasons(date: LocalDate) = stringPreferencesKey("reasons_$date")
    private val kLanguage = stringPreferencesKey("language")

    val settings = ctx.dataStore.data.map { p ->
        Settings(
            videoLimit = p[kVideoLimit] ?: 50,
            limitMinutes = p[kLimit] ?: 30,
            brainUrl = p[kBrainUrl] ?: "http://127.0.0.1:8787",
            brainToken = p[kBrainToken] ?: "",
            language = p[kLanguage] ?: "ru",
        ).also { app.claudebuddy.ui.Lang.code = it.language } // язык видят служба, виджет и экраны
    }

    fun setLanguage(code: String) {
        app.claudebuddy.ui.Lang.code = code
        scope.launch { ctx.dataStore.edit { it[kLanguage] = code }; app.claudebuddy.widget.WidgetUpdater.poke(ctx) }
    }

    private val _today = MutableStateFlow(Today())
    val today: StateFlow<Today> = _today.asStateFlow()
    private var todayDate = LocalDate.now()
    private val dirtySec = mutableSetOf<String>()
    private val dirtyVid = mutableSetOf<String>()

    /** Разрешение от Claude: до какого времени (ms) и до какого числа роликов за день. */
    private var allowedUntil = 0L
    private var allowedVideosUntil = 0L

    private val loaded = kotlinx.coroutines.CompletableDeferred<Unit>()

    init {
        scope.launch { try { importBrainConfig(); loadToday() } finally { loaded.complete(Unit) } }
    }

    /**
     * Адрес ПК и токен кладёт ПК по кабелю (adb run-as) в files/brain.json — другие приложения туда писать не могут.
     * Забираем в настройки и удаляем файл.
     */
    private suspend fun importBrainConfig() {
        val f = java.io.File(ctx.filesDir, "brain.json")
        if (!f.exists()) return
        runCatching {
            val j = org.json.JSONObject(f.readText())
            ctx.dataStore.edit { p ->
                j.optString("url").takeIf { it.startsWith("http") }?.let { p[kBrainUrl] = it.trim().trimEnd('/') }
                j.optString("token").takeIf { it.isNotBlank() }?.let { p[kBrainToken] = it.trim() }
            }
        }
        f.delete()
    }

    /** Перечитать brain.json без перезапуска приложения. */
    fun reimportBrainConfig() { scope.launch { importBrainConfig() } }

    /** Дождаться, пока сегодняшние счётчики прочитаются с диска (нужно виджету при холодном старте). */
    suspend fun awaitLoaded() = loaded.await()

    private suspend fun loadToday() {
        val p = ctx.dataStore.data.first()
        app.claudebuddy.ui.Lang.code = p[kLanguage] ?: "ru"
        todayDate = LocalDate.now()
        _today.value = Today(readDay(p, "vid", todayDate), readDay(p, "sec", todayDate))
        allowedUntil = p[kAllowedUntil] ?: 0L
        allowedVideosUntil = p[kAllowedVideos] ?: 0L
    }

    private fun readDay(p: Preferences, kind: String, date: LocalDate): Map<String, Long> {
        val prefix = "${kind}_${date}_"
        return p.asMap().mapNotNull { (k, v) ->
            if (k.name.startsWith(prefix) && v is Long) k.name.removePrefix(prefix) to v else null
        }.toMap()
    }

    suspend fun lastDays(days: Int): List<DayStat> {
        val p = ctx.dataStore.data.first()
        val now = LocalDate.now()
        return (days - 1 downTo 0).map { back ->
            val d = now.minusDays(back.toLong())
            if (d == todayDate) DayStat(d, _today.value.totalVideos, _today.value.totalMinutes)
            else DayStat(d, readDay(p, "vid", d).values.sum(), (readDay(p, "sec", d).values.sum() / 60).toInt())
        }
    }

    @Synchronized
    private fun rollDay() {
        val now = LocalDate.now()
        if (now != todayDate) { flush(); todayDate = now; _today.value = Today(); allowedVideosUntil = 0 }
    }

    @Synchronized
    fun addSecond(pkg: String) {
        rollDay()
        val t = _today.value
        _today.value = t.copy(seconds = t.seconds + (pkg to ((t.seconds[pkg] ?: 0L) + 1)))
        dirtySec += pkg
    }

    @Synchronized
    fun addVideo(pkg: String) {
        rollDay()
        val t = _today.value
        _today.value = t.copy(videos = t.videos + (pkg to ((t.videos[pkg] ?: 0L) + 1)))
        dirtyVid += pkg
    }

    /** Сброс счётчиков лент за сегодня (по просьбе пользователя — из чата с Claude). */
    @Synchronized
    fun resetToday() {
        val date = todayDate
        val t = _today.value
        _today.value = Today()
        dirtySec.clear(); dirtyVid.clear()
        allowedVideosUntil = 0; allowedUntil = 0
        scope.launch {
            ctx.dataStore.edit { p ->
                (t.seconds.keys).forEach { p.remove(kSec(date, it)) }
                (t.videos.keys).forEach { p.remove(kVid(date, it)) }
                p.remove(kAllowedUntil); p.remove(kAllowedVideos)
            }
        }
    }

    @Synchronized
    fun flush() {
        if (dirtySec.isEmpty() && dirtyVid.isEmpty()) return
        val t = _today.value
        val sec = dirtySec.associateWith { t.seconds[it] ?: 0L }
        val vid = dirtyVid.associateWith { t.videos[it] ?: 0L }
        val date = todayDate
        dirtySec.clear(); dirtyVid.clear()
        scope.launch {
            ctx.dataStore.edit { p ->
                sec.forEach { (pkg, s) -> p[kSec(date, pkg)] = s }
                vid.forEach { (pkg, v) -> p[kVid(date, pkg)] = v }
            }
        }
    }

    /** Claude разрешил: [minutes] для лент по времени и столько же «роликов» (≈ ролик в минуту ×2) для видео-лент. */
    fun allowFor(minutes: Int) {
        allowedUntil = System.currentTimeMillis() + minutes * 60_000L
        allowedVideosUntil = _today.value.totalVideos + minutes * 2L
        val (u, v) = allowedUntil to allowedVideosUntil
        scope.launch { ctx.dataStore.edit { it[kAllowedUntil] = u; it[kAllowedVideos] = v } }
    }

    fun isAllowedByTime() = System.currentTimeMillis() < allowedUntil
    fun isAllowedByVideos() = _today.value.totalVideos < allowedVideosUntil

    suspend fun todayReasons(): List<String> =
        ctx.dataStore.data.first()[kReasons(LocalDate.now())]?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()

    fun addReason(reason: String) {
        val clean = reason.replace("\n", " ").trim().take(300)
        scope.launch {
            ctx.dataStore.edit { p ->
                val key = kReasons(LocalDate.now())
                p[key] = ((p[key] ?: "") + "\n" + clean).trim()
            }
        }
    }

    fun updateSettings(s: Settings) {
        scope.launch {
            ctx.dataStore.edit {
                it[kVideoLimit] = s.videoLimit.coerceIn(1, 2000)
                it[kLimit] = s.limitMinutes.coerceIn(1, 600)
                it[kBrainUrl] = s.brainUrl.trim().trimEnd('/')
                it[kBrainToken] = s.brainToken.trim()
            }
        }
    }

    companion object {
        @Volatile private var instance: FeedStore? = null
        fun get(ctx: Context): FeedStore =
            instance ?: synchronized(this) { instance ?: FeedStore(ctx.applicationContext).also { instance = it } }
    }
}
