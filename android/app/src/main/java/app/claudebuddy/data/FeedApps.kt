package app.claudebuddy.data

/**
 * Приложения с бесконечными лентами.
 * [feedMarkers] — подстроки resource-id экранов ленты (Reels, Shorts). Пустой список = считаем всё приложение.
 * [countVideos] — считать пролистанные ролики (true) или минуты (false).
 * Маркеры подбираются по `uiautomator dump` на реальном телефоне и могут меняться с обновлениями приложений.
 */
data class FeedApp(
    val pkg: String,
    val title: String,
    val feedMarkers: List<String> = emptyList(),
    val countVideos: Boolean = false,
)

object FeedApps {
    val all = listOf(
        FeedApp("com.instagram.android", "Instagram Reels", listOf("clips_viewer", "clips_video"), countVideos = true),
        FeedApp("com.google.android.youtube", "YouTube Shorts", listOf("reel_player", "reel_recycler", "reel_watch", "shorts"), countVideos = true),
        FeedApp("com.zhiliaoapp.musically", "TikTok", countVideos = true),
        FeedApp("com.ss.android.ugc.trill", "TikTok", countVideos = true),
        FeedApp("com.vkontakte.android", "VK Клипы", listOf("clips"), countVideos = true),
        FeedApp("com.facebook.katana", "Facebook"),
        FeedApp("com.twitter.android", "X"),
    )
    private val byPkg = all.associateBy { it.pkg }
    fun find(pkg: String?): FeedApp? = pkg?.let { byPkg[it] }
}
