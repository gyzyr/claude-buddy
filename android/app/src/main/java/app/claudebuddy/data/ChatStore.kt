package app.claudebuddy.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private val Context.chatStore by preferencesDataStore("claude_chat")

/**
 * [queued] — написано, пока ПК был офлайн; уйдёт, когда ПК проснётся.
 * [channel] — с кем разговор: "" — мини-Claude, иначе имя проекта на ПК (настоящий Claude Code в его папке).
 */
data class ChatMessage(
    val role: String, val text: String, val time: Long,
    val queued: Boolean = false, val channel: String = "", val error: Boolean = false,
)

/**
 * История чатов (последние 300 сообщений на все каналы), выбранный канал и незавершённые задачи
 * Claude Code (канал → id задачи) — чтобы ответ не потерялся, если закрыть экран, пока Claude работает.
 */
class ChatStore private constructor(private val ctx: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val key = stringPreferencesKey("messages")
    private val kChannel = stringPreferencesKey("channel")
    private val kJobs = stringPreferencesKey("jobs")
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    private val _channel = MutableStateFlow("")
    val channel: StateFlow<String> = _channel.asStateFlow()
    private val _jobs = MutableStateFlow<Map<String, String>>(emptyMap())
    val jobs: StateFlow<Map<String, String>> = _jobs.asStateFlow()

    init {
        scope.launch {
            val p = ctx.chatStore.data.first()
            p[key]?.let { raw ->
                val arr = JSONArray(raw)
                _messages.value = (0 until arr.length()).map { i ->
                    arr.getJSONObject(i).let {
                        ChatMessage(it.optString("role"), it.optString("text"), it.optLong("time"),
                            it.optBoolean("queued"), it.optString("channel"), it.optBoolean("error"))
                    }
                }
            }
            _channel.value = p[kChannel] ?: ""
            p[kJobs]?.let { raw -> val o = JSONObject(raw); _jobs.value = o.keys().asSequence().associateWith { o.getString(it) } }
        }
    }

    fun add(m: ChatMessage) = set(_messages.value + m)

    /** Очередь канала ушла на ПК. */
    fun markSent(channel: String) = set(_messages.value.map { if (it.channel == channel) it.copy(queued = false) else it })

    fun clear(channel: String) = set(_messages.value.filterNot { it.channel == channel })

    fun select(channel: String) {
        _channel.value = channel
        scope.launch { ctx.chatStore.edit { it[kChannel] = channel } }
    }

    fun setJob(channel: String, job: String?) {
        val m = _jobs.value.toMutableMap()
        if (job == null) m.remove(channel) else m[channel] = job
        _jobs.value = m
        scope.launch { ctx.chatStore.edit { it[kJobs] = JSONObject(m as Map<*, *>).toString() } }
    }

    private fun set(list: List<ChatMessage>) {
        val trimmed = list.takeLast(300)
        _messages.value = trimmed
        val arr = JSONArray(trimmed.map {
            JSONObject().put("role", it.role).put("text", it.text).put("time", it.time).put("queued", it.queued)
                .put("channel", it.channel).put("error", it.error)
        })
        scope.launch { ctx.chatStore.edit { it[key] = arr.toString() } }
    }

    companion object {
        @Volatile private var instance: ChatStore? = null
        fun get(ctx: Context) = instance ?: synchronized(this) { instance ?: ChatStore(ctx.applicationContext).also { instance = it } }
    }
}
