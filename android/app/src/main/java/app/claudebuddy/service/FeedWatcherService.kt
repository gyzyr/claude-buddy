package app.claudebuddy.service

import app.claudebuddy.ui.tr
import app.claudebuddy.widget.WidgetUpdater
import android.accessibilityservice.AccessibilityService
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.claudebuddy.brain.JudgeRequest
import app.claudebuddy.brain.PcBrain
import app.claudebuddy.brain.RoutingBrain
import app.claudebuddy.brain.Verdict
import app.claudebuddy.data.FeedApp
import app.claudebuddy.data.FeedApps
import app.claudebuddy.data.FeedStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Следит, когда пользователь в бесконечной ленте. В видео-лентах (Reels, Shorts, TikTok) считает
 * пролистанные ролики, в остальных — секунды. После лимита показывает барьер.
 * Содержимое экрана не сохраняется и никуда не уходит — смотрим только resource-id и индексы прокрутки.
 */
class FeedWatcherService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var store: FeedStore
    private lateinit var barrier: BarrierOverlay

    private var currentApp: FeedApp? = null
    private var inFeed = false
    private var lastScan = 0L

    // счёт роликов
    private var lastIndex = -1          // индекс текущего ролика в пейджере
    private var indexMode = false       // пейджер сообщает индексы → считаем по ним
    private var lastVideoAt = 0L        // для запасного режима (по жестам прокрутки)
    private val seenIndices = mutableSetOf<Int>() // уже посчитанные ролики в текущем заходе в ленту
    private var pending: Pair<kotlinx.coroutines.Job, String>? = null // ролик, ожидающий проверки на рекламу
    private var dumpCounter = 0

    // присланный рилс (открыт из личных сообщений): его можно смотреть и после лимита
    private var lastScreenWasDirect = false
    private var sharedMode = false

    private lateinit var bubble: FloatingBubble
    private lateinit var briefing: DailyBriefing

    override fun onServiceConnected() {
        store = FeedStore.get(this)
        bubble = FloatingBubble(this, store)
        briefing = DailyBriefing(this, store)
        barrier = BarrierOverlay(
            this,
            judge = ::judge,
            onAllow = { store.allowFor(it) },
            onExit = ::leaveFeed,
        )
        scope.launch { tickLoop() }
        Log.i(TAG, "connected")
    }

    /**
     * «Выйти»: в приложениях, где лента — только раздел (Instagram, YouTube, VK), уходим назад из Reels/Shorts
     * и остаёмся в приложении. Там, где лента — всё приложение (TikTok), — на главный экран.
     */
    private fun leaveFeed() {
        val app = currentApp
        if (app == null || app.feedMarkers.isEmpty()) { performGlobalAction(GLOBAL_ACTION_HOME); return }
        scope.launch {
            repeat(3) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                delay(500)
                val stillIn = hasMarker(rootInActiveWindow, app.feedMarkers)
                if (!stillIn) { setInFeed(false, app); return@launch }
            }
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return // наш собственный оверлей
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // системные окна (клавиатура, шторка) не меняют текущее приложение
            if (pkg.startsWith("com.android.systemui") || pkg.contains("inputmethod") || pkg.contains("honeyboard")) return
            val app = FeedApps.find(pkg)
            if (app?.pkg != currentApp?.pkg) { indexMode = false }
            currentApp = app
            if (app == null) setInFeed(false, null)
        }
        val app = currentApp ?: return
        if (app.pkg != pkg) return

        val now = System.currentTimeMillis()
        if (now - lastScan >= 1000 || event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastScan = now
            val root = rootInActiveWindow
            // Сторис в Instagram — не лента, даже если внутри сторис рилс. Рилс, открытый из сторис или из лички, —
            // «присланный»: его можно посмотреть и после лимита, а дальше по ленте — уже считается.
            val story = app.pkg == "com.instagram.android" && hasMarker(root, STORY_MARKERS)
            val feed = !story && (app.feedMarkers.isEmpty() || hasMarker(root, app.feedMarkers))
            if (!feed && app.pkg == "com.instagram.android") lastScreenWasDirect = story || hasMarker(root, DIRECT_MARKERS)
            setInFeed(feed, app)
        }

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED && inFeed && app.countVideos) onScrolled(app, event, now)
    }

    private fun setInFeed(value: Boolean, app: FeedApp?) {
        if (value == inFeed) return
        inFeed = value
        lastIndex = -1
        seenIndices.clear()
        sharedMode = value && lastScreenWasDirect
        Log.d(TAG, "${app?.title ?: "—"}: inFeed=$value${if (sharedMode) " (присланный рилс из личных сообщений)" else ""}")
        if (value && app != null) bubble.show(app.countVideos) else bubble.hide()
        // первый ролик при входе в ленту тоже считается — кроме присланного в личке
        if (value && app != null && app.countVideos && !sharedMode) countVideo(app, "вход в ленту")
    }

    private fun onScrolled(app: FeedApp, e: AccessibilityEvent, now: Long) {
        val id = e.source?.viewIdResourceName
        if (BuildConfigDebug) Log.v(TAG, "scroll ${e.className} from=${e.fromIndex} to=${e.toIndex} count=${e.itemCount} dy=${e.scrollDeltaY} id=$id")
        // Учитываем только прокрутку самой ленты, а не комментариев, поиска или «поделиться».
        if (app.feedMarkers.isNotEmpty() && (id == null || app.feedMarkers.none { id.contains(it, ignoreCase = true) })) return
        val from = e.fromIndex
        if (from >= 0 && e.toIndex == from && e.itemCount > 1) {
            // Пейджер остановился на одном ролике (Instagram): считаем только ещё не виденные индексы,
            // чтобы листание назад-вперёд не накручивало счёт.
            indexMode = true
            if (lastIndex == -1) seenIndices.clear()
            if (seenIndices.add(from) && lastIndex != -1) {
                // пролистал дальше присланного рилса — это уже обычная лента
                if (sharedMode) { sharedMode = false; Log.d(TAG, "присланный рилс досмотрен, дальше — лента") }
                countVideo(app, "индекс $lastIndex→$from")
            }
            lastIndex = from
        } else if (!indexMode && e.scrollDeltaY >= MIN_SWIPE_PX && now - lastVideoAt > 600) {
            sharedMode = false
            // Запасной режим (YouTube Shorts, TikTok): один крупный сдвиг вперёд = один ролик.
            // Мелкие «доводки» анимации (dy в единицы-десятки px) и листание назад не считаем.
            countVideo(app, "свайп dy=${e.scrollDeltaY}")
        }
    }

    /**
     * Кандидат в ролики. Решение откладываем на [CLASSIFY_DELAY_MS], чтобы экран успел дорисоваться,
     * и проверяем, не реклама ли это. Если пользователь пролистал раньше — проверяем сразу.
     */
    private fun countVideo(app: FeedApp, why: String) {
        lastVideoAt = System.currentTimeMillis()
        pending?.let { (job, prevWhy) -> if (job.isActive) { job.cancel(); classifyAndCount(app, "$prevWhy, быстро пролистан") } }
        val job = scope.launch { delay(CLASSIFY_DELAY_MS); classifyAndCount(app, why) }
        pending = job to why
    }

    private fun classifyAndCount(app: FeedApp, why: String) {
        val nodes = visibleNodes(rootInActiveWindow)
        val n = ++dumpCounter
        if (BuildConfigDebug) nodes.take(90).forEach { Log.v(DUMP_TAG, "#$n ${it.id}|${it.cls}|${it.text}") }
        val ad = nodes.firstOrNull { node -> AD_LABELS.any { node.text.equals(it, ignoreCase = true) || node.text.startsWith("$it ·", ignoreCase = true) } }
        if (ad != null) {
            Log.d(TAG, "${app.title}: пропуск #$n — реклама («${ad.text}»)")
            return
        }
        store.addVideo(app.pkg)
        WidgetUpdater.poke(this)
        Log.d(TAG, "${app.title}: +1 ролик #$n ($why), сегодня ${store.today.value.totalVideos}")
        scope.launch { checkBarrier(app) }
    }

    private data class Node(val id: String, val cls: String, val text: String)

    /** Видимые элементы экрана: короткий id, класс и текст/описание (обрезаны). Только для проверки на месте. */
    private fun visibleNodes(root: AccessibilityNodeInfo?): List<Node> {
        root ?: return emptyList()
        val out = mutableListOf<Node>()
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (queue.isNotEmpty() && visited < 800) {
            val x = queue.removeFirst(); visited++
            if (x.isVisibleToUser) {
                val id = x.viewIdResourceName?.substringAfter(":id/") ?: ""
                val text = (x.text ?: x.contentDescription)?.toString()?.replace("\n", " ")?.trim()?.take(50) ?: ""
                if (id.isNotEmpty() || text.isNotEmpty()) out += Node(id, x.className?.toString()?.substringAfterLast('.') ?: "", text)
            }
            for (i in 0 until x.childCount) x.getChild(i)?.let(queue::add)
        }
        return out
    }

    private fun hasMarker(root: AccessibilityNodeInfo?, markers: List<String>): Boolean {
        root ?: return false
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (queue.isNotEmpty() && visited < 600) {
            val n = queue.removeFirst(); visited++
            val id = n.viewIdResourceName
            if (id != null && n.isVisibleToUser && markers.any { id.contains(it, ignoreCase = true) }) return true
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
        }
        return false
    }

    private suspend fun checkBarrier(app: FeedApp) {
        if (barrier.isShowing) return
        val s = store.settings.first()
        val t = store.today.value
        val over = if (app.countVideos) t.totalVideos >= s.videoLimit && !store.isAllowedByVideos()
                   else t.totalMinutes >= s.limitMinutes && !store.isAllowedByTime()
        if (over) barrier.show(app.title, summary(app, s.videoLimit, s.limitMinutes))
    }

    private fun summary(app: FeedApp, videoLimit: Int, minLimit: Int): String {
        val t = store.today.value
        return if (app.countVideos) tr("${t.totalVideos} роликов сегодня (лимит $videoLimit)", "${t.totalVideos} videos today (limit $videoLimit)")
               else tr("${t.totalMinutes} мин в лентах сегодня (лимит $minLimit)", "${t.totalMinutes} min in feeds today (limit $minLimit)")
    }

    private suspend fun tickLoop() {
        val power = getSystemService(PowerManager::class.java)
        var sinceFlush = 0
        while (scope.isActive) {
            delay(1000)
            val app = currentApp
            if (app != null && inFeed && power.isInteractive && !barrier.isShowing) {
                store.addSecond(app.pkg) // время копим везде — пригодится для статистики
                if (!app.countVideos) checkBarrier(app)
            }
            if (++sinceFlush >= 10) {
                store.flush(); sinceFlush = 0
                scope.launch(Dispatchers.IO) { runCatching { briefing.onTick() } }
            }
        }
    }

    private suspend fun judge(reason: String): Verdict {
        val s = store.settings.first()
        val t = store.today.value
        val req = JudgeRequest(
            app = currentApp?.title ?: tr("лента", "feed"),
            reason = reason,
            videosToday = t.totalVideos.toInt(),
            videoLimit = s.videoLimit,
            minutesToday = t.totalMinutes,
            limitMinutes = s.limitMinutes,
            localTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm, EEEE", Locale.forLanguageTag("ru"))),
            recentReasons = store.todayReasons(),
        )
        store.addReason(reason)
        return RoutingBrain(PcBrain(s.brainUrl, s.brainToken)).judge(req)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (::store.isInitialized) store.flush()
        if (::barrier.isInitialized) barrier.dismiss()
        if (::bubble.isInitialized) bubble.hide()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val TAG = "ClaudeFeed"
        /** Подробный лог прокрутки — для подстройки счёта роликов. */
        const val BuildConfigDebug = true
        /** Минимальный сдвиг ленты вперёд, который считается переходом к следующему ролику. */
        const val MIN_SWIPE_PX = 300
        const val CLASSIFY_DELAY_MS = 700L
        const val DUMP_TAG = "ClaudeDump"
        /** Экран переписки в Instagram — признаки уточняются по логам. */
        /** Экран сторис в Instagram (внутри он называется reel_viewer — это НЕ Reels). */
        val STORY_MARKERS = listOf("reel_viewer", "story_viewer", "reel_viewer_media", "reel_viewer_header")
        val DIRECT_MARKERS = listOf("direct_thread", "thread_message", "message_list", "row_thread", "direct_composer")
        /** Метки рекламы (точное совпадение текста элемента или «метка · …»). Уточняются по логам. */
        val AD_LABELS = listOf("Реклама", "Спонсорский контент", "Спонсорская публикация", "Sponsored", "Ad", "Рекламное объявление")
    }
}
