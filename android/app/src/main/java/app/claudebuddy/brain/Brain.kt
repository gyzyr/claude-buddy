package app.claudebuddy.brain

import app.claudebuddy.ui.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class JudgeRequest(
    val app: String,
    val reason: String,
    val videosToday: Int,
    val videoLimit: Int,
    val minutesToday: Int,
    val limitMinutes: Int,
    val localTime: String,
    val recentReasons: List<String>,
)

sealed interface Verdict {
    data class Allow(val minutes: Int, val message: String) : Verdict
    data class Deny(val message: String) : Verdict
    /** Мозг недоступен: подождать [seconds] и пустить на [minutes]. */
    data class Wait(val seconds: Int, val minutes: Int, val message: String) : Verdict
}

/** «Мозг» — сменный модуль: ПК (Claude Code под подпиской), позже API или офлайн. */
interface Brain {
    suspend fun judge(req: JudgeRequest): Verdict
}

/** Офлайн-правила: когда ПК выключен — пауза и короткий допуск. */
object OfflineBrain : Brain {
    override suspend fun judge(req: JudgeRequest) = Verdict.Wait(
        seconds = 30, minutes = 5,
        message = tr("Мой компьютер сейчас спит, так что без разговоров: подождём 30 секунд. Если за это время передумаешь — я только рад 🙂",
            "My computer is asleep, so no debate: let's wait 30 seconds. If you change your mind meanwhile, even better 🙂"),
    )
}

/** Мини-Claude на ПК: POST {url}/judge. */
class PcBrain(private val baseUrl: String, private val token: String) : Brain {
    override suspend fun judge(req: JudgeRequest): Verdict = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("app", req.app).put("reason", req.reason)
            .put("videosToday", req.videosToday).put("videoLimit", req.videoLimit)
            .put("minutesToday", req.minutesToday).put("limitMinutes", req.limitMinutes)
            .put("localTime", req.localTime).put("recentReasons", JSONArray(req.recentReasons))
            .put("lang", app.claudebuddy.ui.Lang.code)
        val conn = (URL("$baseUrl/judge").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 3_000
            readTimeout = 45_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (token.isNotEmpty()) setRequestProperty("X-Token", token)
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            val j = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).readText())
            val msg = j.optString("message")
            if (j.optBoolean("allow")) Verdict.Allow(j.optInt("minutes", 5), msg) else Verdict.Deny(msg)
        } finally {
            conn.disconnect()
        }
    }
}

/** Сначала ПК, при любой ошибке — офлайн-правила. */
class RoutingBrain(private val pc: PcBrain) : Brain {
    override suspend fun judge(req: JudgeRequest): Verdict =
        runCatching { pc.judge(req) }.getOrElse { OfflineBrain.judge(req) }
}
