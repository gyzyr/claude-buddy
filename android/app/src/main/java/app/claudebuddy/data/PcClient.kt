package app.claudebuddy.data

import app.claudebuddy.ui.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class PcProject(val name: String, val sessions: Int)

/** Что делает мини-Claude на ПК. [activity]: coding / alert / idle. */
data class PcStatus(
    val activity: String,
    val project: String?,
    val lastActiveMinutesAgo: Int?,
    val sessionsToday: Int,
    val projects: List<PcProject>,
)

/** HTTP-клиент мини-Claude на ПК (через USB-проброс или Tailscale). */
class PcClient(private val baseUrl: String, private val token: String) {

    /**
     * Сначала адрес из настроек (Tailscale — работает везде), потом кабель (adb reverse на 127.0.0.1).
     * Последний сработавший адрес пробуем первым, чтобы не ждать таймаут каждый раз.
     */
    private suspend fun request(path: String, body: JSONObject? = null, readTimeout: Int = 10_000): JSONObject {
        val urls = listOfNotNull(lastGood, baseUrl.trimEnd('/'), LOCAL).distinct()
        var last: Exception? = null
        for (u in urls) {
            try { return requestAt(u, path, body, readTimeout).also { lastGood = u } }
            catch (e: java.io.IOException) {
                // не достучались — пробуем следующий адрес; медленный ответ уже дошедшего ПК не повторяем
                val noConnect = e is java.net.ConnectException || e is java.net.NoRouteToHostException ||
                    e is java.net.UnknownHostException ||
                    (e is java.net.SocketTimeoutException && e.message?.contains("connect", ignoreCase = true) == true)
                if (!noConnect) throw e
                last = e
                if (lastGood == u) lastGood = null
            }
        }
        throw last ?: java.io.IOException("ПК недоступен")
    }

    private suspend fun requestAt(base: String, path: String, body: JSONObject?, readTimeout: Int): JSONObject =
        withContext(Dispatchers.IO) {
            val c = (URL("$base$path").openConnection() as HttpURLConnection).apply {
                connectTimeout = 2_500
                this.readTimeout = readTimeout
                if (token.isNotBlank()) setRequestProperty("X-Token", token.trim())
                if (body != null) {
                    requestMethod = "POST"; doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            try {
                if (body != null) c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                if (c.responseCode != 200) error("HTTP ${c.responseCode}")
                JSONObject(c.inputStream.bufferedReader(Charsets.UTF_8).readText())
            } finally { c.disconnect() }
        }

    suspend fun status(): PcStatus? = runCatching {
        val j = request("/status")
        val today = j.optJSONObject("today") ?: JSONObject()
        val arr = today.optJSONArray("projects") ?: JSONArray()
        PcStatus(
            activity = j.optString("activity", "idle"),
            project = j.optString("project").takeIf { it.isNotBlank() && it != "null" },
            lastActiveMinutesAgo = if (j.isNull("lastActiveMinutesAgo")) null else j.optInt("lastActiveMinutesAgo"),
            sessionsToday = today.optInt("sessions"),
            projects = (0 until arr.length()).map { i -> arr.getJSONObject(i).let { PcProject(it.optString("name"), it.optInt("sessions")) } },
        )
    }.getOrNull()

    suspend fun summary(context: JSONObject): String? =
        runCatching { request("/summary", context, readTimeout = 60_000).optString("text") }.getOrNull()

    /** Снимок дня с телефона — чтобы мини-Claude на ПК знал про ленты и расписание. */
    suspend fun pushPhone(context: JSONObject) { request("/phone", context) }

    suspend fun chat(messages: List<ChatMessage>, context: JSONObject): String =
        request("/chat", JSONObject()
            .put("messages", JSONArray(messages.map { JSONObject().put("role", it.role).put("text", it.text) }))
            .put("context", context), readTimeout = 120_000).optString("reply")

    /** Команды для телефона от Claude на ПК (например, «сбросить счётчик лент»). Забранные — удаляются на ПК. */
    suspend fun commands(): List<String> = runCatching {
        val arr = request("/phone/commands").optJSONArray("commands") ?: JSONArray()
        (0 until arr.length()).map { arr.getString(it) }
    }.getOrDefault(emptyList())

    /** Новое сообщение из мессенджера/почты — ПК решит, важно ли, и покажет облачко над мини-Claude. */
    suspend fun notify(item: JSONObject) { request("/notify", item) }

    /** Проекты на ПК, где можно поговорить с Claude Code. */
    suspend fun projects(): List<String>? = runCatching {
        val arr = request("/projects").optJSONArray("projects") ?: JSONArray()
        (0 until arr.length()).map { arr.getJSONObject(it).optString("name") }.filter { it.isNotBlank() }
    }.getOrNull()

    /** Отдать сообщение Claude Code в проекте; возвращает id задачи — ответ забирать через [codeJob]. */
    suspend fun codeStart(project: String, text: String, newSession: Boolean = false): String =
        request("/code", JSONObject().put("project", project).put("text", text).put("newSession", newSession).put("lang", app.claudebuddy.ui.Lang.code)).getString("job")

    /** Статус задачи: null — ещё работает; иначе (успех?, текст). Задача пропала — ошибка. */
    suspend fun codeJob(id: String): Pair<Boolean, String>? {
        val j = try { request("/code/$id") } catch (e: IllegalStateException) {
            if (e.message?.contains("404") == true) return false to tr("Задача потерялась — ПК перезапускался. Отправь ещё раз.", "The task was lost — the PC restarted. Please send it again.")
            throw e
        }
        return when (j.optString("status")) {
            "running" -> null
            "done" -> true to j.optString("reply")
            else -> false to j.optString("reply", "ошибка")
        }
    }

    companion object {
        const val LOCAL = "http://127.0.0.1:8787"
        @Volatile private var lastGood: String? = null
    }
}
