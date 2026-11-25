package app.claudebuddy.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class Mood { HAPPY, WORRIED, THINKING, SLEEPY }

/** Что маскот делает: повторяет мини-Claude на ПК или живёт сам по себе. */
enum class Activity { IDLE, WALK, CODING, SLEEP, ALERT }

val ClaudeOrange = Color(0xFFD97757)
val Cream = Color(0xFFFAF3EC)
val Ink = Color(0xFF2B1B14)

/**
 * Маскот Claude: оранжевый блок-фигурка с объёмом (верх, бок, рёбра), квадратные глаза с фаской,
 * руки-бруски и две пары ножек. Ходит, печатает, спит, удивляется.
 */
@Composable
fun Mascot(
    mood: Mood,
    modifier: Modifier = Modifier,
    size: Dp = 120.dp,
    activity: Activity = Activity.IDLE,
    facingLeft: Boolean = false,
) {
    val t = rememberInfiniteTransition(label = "mascot")
    val bob by t.animateFloat(0f, 1f, infiniteRepeatable(tween(if (activity == Activity.CODING) 260 else 1400), RepeatMode.Reverse), label = "bob")
    val step by t.animateFloat(0f, 1f, infiniteRepeatable(tween(420, easing = LinearEasing)), label = "step")
    val blink by t.animateFloat(0f, 1f, infiniteRepeatable(tween(3400)), label = "blink")
    val zz by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "zz")

    Canvas(modifier.size(size)) {
        val u = this.size.width / 100f // работаем в сетке 100×100
        // рисунок без отражения повёрнут влево (виден правый бок); вправо — отражаем, чтобы не пятился
        scale(if (facingLeft) 1f else -1f, 1f) {
            drawMascot(u, mood, activity, bob, step, blink, zz)
        }
    }
}

// Цвета блока, как у фигурки: передняя грань, верх светлее, бок темнее, глаза с фаской.
private val Front = Color(0xFFDE7A57)
private val FrontLo = Color(0xFFCC6646)
private val FrontHi = Color(0xFFE88563)
private val Top = Color(0xFFF09A78)
private val Side = Color(0xFFB4573A)
private val SideLo = Color(0xFF9A4630)
private val Eye = Color(0xFF26262B)
private val EyeHi = Color(0xFF55555E)
private val EyeLo = Color(0xFF141417)

private fun DrawScope.drawMascot(u: Float, mood: Mood, activity: Activity, bob: Float, step: Float, blink: Float, zz: Float) {
    fun p(x: Float, y: Float) = Offset(x * u, y * u)
    fun r(c: Color, x: Float, y: Float, w: Float, h: Float) = drawRect(c, p(x, y), Size(w * u, h * u))
    fun poly(c: Color, vararg pts: Float) = drawPath(Path().apply {
        moveTo(pts[0] * u, pts[1] * u)
        var i = 2; while (i < pts.size) { lineTo(pts[i] * u, pts[i + 1] * u); i += 2 }
        close()
    }, c)
    // брусок-конечность с объёмом: светлая кромка сверху, тёмная снизу
    fun limb(x: Float, y: Float, w: Float, h: Float) {
        r(Front, x, y, w, h); r(Color.White.copy(alpha = 0.16f), x, y, w, 1.4f); r(Color.Black.copy(alpha = 0.18f), x, y + h - 1.6f, w, 1.6f)
    }
    // рука, повёрнутая вокруг плеча; dir = -1 левая, 1 правая; ang — подъём в градусах
    fun arm(px: Float, py: Float, dir: Float, ang: Float, len: Float = 11f) {
        rotate(-dir * ang, pivot = p(px, py)) {
            val x0 = if (dir < 0) px - len else px
            limb(x0, py - 4f, len, 8f)
        }
    }

    val walking = activity == Activity.WALK
    val alert = activity == Activity.ALERT
    val coding = activity == Activity.CODING
    val dy = when (activity) {
        Activity.WALK -> if (step < 0.5f) -1.5f else 0f
        Activity.ALERT -> if (step < 0.5f) -2.5f else 0f
        Activity.SLEEP -> bob * 1f
        else -> -bob * 1.2f
    }

    // тень
    drawOval(Color.Black.copy(alpha = 0.14f), p(17f, 80f), Size(72f * u, 6f * u))

    // ножки: две пары у краёв; в ходьбе шагают по очереди
    listOf(24f, 33f, 60f, 69f).forEachIndexed { i, x ->
        val up = if (walking && ((if (i % 2 == 0) step else (step + 0.5f) % 1f) < 0.5f)) 2.5f else 0f
        limb(x, 66f + dy - up, 7f, 16f)
        r(SideLo, x + 7f, 66f + dy - up, 1.5f, 16f)
    }

    // руки: в тревоге машут обеими над головой, в ходьбе качаются, за работой «печатают»
    val wave = if (step < 0.5f) 105f else 60f
    val angL = when { alert -> wave; walking -> if (step < 0.5f) 8f else -6f; coding -> -18f + (if (bob > 0.5f) 6f else 0f); else -> 0f }
    val angR = when { alert -> wave; walking -> if (step < 0.5f) -6f else 8f; coding -> -18f + (if (bob > 0.5f) 0f else 6f); else -> 0f }
    val armLen = if (alert) 15f else 11f
    arm(if (alert) 89f else 86f, 45f + dy, 1f, angR, armLen) // правая — за боком

    // корпус: верх, бок, передняя грань
    poly(Top, 20f, 30f + dy, 80f, 30f + dy, 86f, 24f + dy, 26f, 24f + dy)
    drawPath(Path().apply {
        moveTo(80f * u, (30f + dy) * u); lineTo(86f * u, (24f + dy) * u)
        lineTo(86f * u, (60f + dy) * u); lineTo(80f * u, (66f + dy) * u); close()
    }, Brush.verticalGradient(listOf(Side, SideLo), startY = 24f * u, endY = 66f * u))
    drawRect(
        Brush.verticalGradient(listOf(FrontHi, Front, FrontLo), startY = (30f + dy) * u, endY = (66f + dy) * u),
        p(20f, 30f + dy), Size(60f * u, 36f * u),
    )
    r(Color.White.copy(alpha = 0.22f), 20f, 30f + dy, 60f, 1.2f)
    r(Color.Black.copy(alpha = 0.12f), 20f, 64.6f + dy, 60f, 1.4f)

    arm(if (alert) 17f else 20f, 45f + dy, -1f, angL, armLen) // левая — спереди

    // глаза: квадратные с фаской
    val eyeTop = 38f + dy + if (mood == Mood.THINKING) -3f else 0f
    val closed = mood == Mood.SLEEPY || activity == Activity.SLEEP || blink > 0.965f
    val look = if (coding) 2f else if (walking) -2.5f else -1f // глаза — в сторону, куда повёрнут
    listOf(33f, 57f).forEach { ex0 ->
        val ex = ex0 + look
        when {
            closed -> r(Eye, ex, eyeTop + 4f, 9f, 2.4f)
            mood == Mood.HAPPY && !coding && !alert -> {
                // радостные глаза-«домики»
                drawPath(Path().apply {
                    moveTo(ex * u, (eyeTop + 8f) * u); lineTo((ex + 4.5f) * u, (eyeTop + 2f) * u)
                    lineTo((ex + 9f) * u, (eyeTop + 8f) * u); lineTo((ex + 6.6f) * u, (eyeTop + 8f) * u)
                    lineTo((ex + 4.5f) * u, (eyeTop + 5.2f) * u); lineTo((ex + 2.4f) * u, (eyeTop + 8f) * u); close()
                }, Eye)
            }
            else -> {
                val top = if (mood == Mood.WORRIED) eyeTop + 2f else eyeTop
                r(EyeLo, ex, top, 9f, 9f); r(Eye, ex, top, 8f, 8f)
                r(EyeHi, ex, top, 8f, 1.3f); r(EyeHi, ex, top, 1.3f, 8f)
                r(Color.White.copy(alpha = 0.35f), ex + 2f, top + 2f, 2f, 2f)
            }
        }
    }

    // ноутбук задней крышкой к нам; клавиатура уходит вглубь — вправо-вверх, к нему
    if (coding) {
        poly(Color(0xFF7A7A84), 23f, 63f + dy, 67f, 63f + dy, 75f, 55f + dy, 31f, 55f + dy)
        poly(Color(0xFF55555E), 67f, 63f + dy, 75f, 55f + dy, 75f, 57.5f + dy, 67f, 65.5f + dy)
        r(Color(0xFF5E5E68), 23f, 63f + dy, 44f, 2.5f)
        r(Color(0xFF34343B), 25f, 48f + dy, 40f, 15f)
        r(Color(0xFF4A4A53), 25f, 48f + dy, 40f, 1.4f)
        r(Color(0xFF26262B), 25f, 61.6f + dy, 40f, 1.4f)
        r(if (bob > 0.5f) Color.White else Color(0xFFE6E6EC), 43f, 53f + dy, 4f, 4f)
    }

    // «!» — нужно внимание
    if (alert) {
        val by = if (step < 0.5f) -1.5f else 0f
        val red = Color(0xFFC0392B)
        r(red, 47.5f, 2f + by, 7f, 12f); r(red, 47.5f, 16f + by, 7f, 5f)
        r(Color.White.copy(alpha = 0.3f), 47.5f, 2f + by, 2f, 12f)
    }

    // Z-z-z во сне
    if (activity == Activity.SLEEP) {
        val zy = 18f - zz * 14f
        val a = (1f - zz).coerceIn(0f, 1f)
        drawZ(p(84f + zz * 6f, zy), 6f * u, Ink.copy(alpha = a))
        drawZ(p(74f + zz * 4f, zy + 8f), 4f * u, Ink.copy(alpha = a * 0.7f))
    }
}

private fun DrawScope.drawZ(at: Offset, s: Float, c: Color) {
    val w = s * 0.18f
    drawLine(c, at, Offset(at.x + s, at.y), w)
    drawLine(c, Offset(at.x + s, at.y), Offset(at.x, at.y + s), w)
    drawLine(c, Offset(at.x, at.y + s), Offset(at.x + s, at.y + s), w)
}
