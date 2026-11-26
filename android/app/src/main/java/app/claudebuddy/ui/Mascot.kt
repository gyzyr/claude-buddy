package app.claudebuddy.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sign
import kotlin.math.sin
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

/** Реакция на касание — поверх настроения, на пару секунд. */
enum class Reaction { NONE, HAPPY, SURPRISED, DIZZY, ANNOYED }

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
    /** Можно трогать: тык — вздрагивает и подпрыгивает, потереть пальцем — гладишь, швырнуть — кружится голова. */
    interactive: Boolean = false,
) {
    var reaction by remember { mutableStateOf(Reaction.NONE) }
    var reactionKey by remember { mutableIntStateOf(0) }
    var reactionMs by remember { mutableIntStateOf(0) }
    val hop = remember { Animatable(0f) }
    val pull = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val taps = remember { ArrayDeque<Long>() }
    fun react(r: Reaction, ms: Int) { reaction = r; reactionMs = ms; reactionKey++ }
    LaunchedEffect(reactionKey) { if (reaction != Reaction.NONE) { delay(reactionMs.toLong()); reaction = Reaction.NONE } }
    fun jump() = scope.launch { hop.animateTo(1f, tween(130)); hop.animateTo(0f, spring(dampingRatio = 0.35f, stiffness = 400f)) }

    val touch = if (!interactive) Modifier else Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val start = down.position
            var last = start
            var moved = false
            var dir = 0
            var reversals = 0
            val vt = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
            while (true) {
                val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                if (!ch.pressed) break
                vt.addPosition(ch.uptimeMillis, ch.position)
                val d = ch.position - last
                last = ch.position
                val total = ch.position - start
                if (!moved && total.getDistance() > viewConfiguration.touchSlop) moved = true
                if (moved) {
                    ch.consume()
                    val sd = sign(d.x).toInt()
                    if (abs(d.x) > 2f && sd != 0) {
                        if (dir != 0 && sd != dir) {
                            reversals++
                            if (reversals >= 3) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        dir = sd
                    }
                    // гладишь — жмурится, и пока гладишь — не перестаёт
                    if (reversals >= 3 && (reaction != Reaction.HAPPY || d.getDistance() > 2f)) react(Reaction.HAPPY, 2500)
                    scope.launch { pull.snapTo(total * 0.35f) } // тянется за пальцем, но упирается
                }
            }
            if (!moved) {
                val now = System.currentTimeMillis()
                while (taps.isNotEmpty() && now - taps.first() > 2500) taps.removeFirst()
                taps.addLast(now)
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                if (taps.size >= 3) { taps.clear(); react(Reaction.ANNOYED, 3000) } // затыкали — обиделся
                else { react(Reaction.SURPRISED, 900); jump() }
            } else {
                val v = vt.calculateVelocity()
                if (reversals < 3 && hypot(v.x, v.y) > 2500f) {
                    react(Reaction.DIZZY, 3000); haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                scope.launch { pull.animateTo(Offset.Zero, spring(dampingRatio = 0.3f, stiffness = 260f)) } // пружинит обратно
            }
        }
    }

    val t = rememberInfiniteTransition(label = "mascot")
    val bob by t.animateFloat(0f, 1f, infiniteRepeatable(tween(if (activity == Activity.CODING) 260 else 1400), RepeatMode.Reverse), label = "bob")
    val step by t.animateFloat(0f, 1f, infiniteRepeatable(tween(420, easing = LinearEasing)), label = "step")
    val blink by t.animateFloat(0f, 1f, infiniteRepeatable(tween(3400)), label = "blink")
    val zz by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "zz")

    val wobble by t.animateFloat(-1f, 1f, infiniteRepeatable(tween(180), RepeatMode.Reverse), label = "wobble")
    Canvas(modifier.size(size).then(touch).graphicsLayer {
        translationX = pull.value.x
        translationY = pull.value.y - hop.value * this.size.height * 0.2f
        rotationZ = when (reaction) { Reaction.DIZZY -> wobble * 6f; Reaction.ANNOYED -> wobble * 1.5f; else -> 0f }
    }) {
        val u = this.size.width / 100f // работаем в сетке 100×100
        // рисунок без отражения повёрнут влево (виден правый бок); вправо — отражаем, чтобы не пятился
        scale(if (facingLeft) 1f else -1f, 1f) {
            drawMascot(u, mood, activity, bob, step, blink, zz, reaction)
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

private fun DrawScope.drawMascot(u: Float, mood: Mood, activity: Activity, bob: Float, step: Float, blink: Float, zz: Float, reaction: Reaction = Reaction.NONE) {
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
            reaction == Reaction.DIZZY -> { // × × — голова кружится
                drawLine(Eye, p(ex, eyeTop), p(ex + 9f, eyeTop + 9f), 2.2f * u)
                drawLine(Eye, p(ex + 9f, eyeTop), p(ex, eyeTop + 9f), 2.2f * u)
            }
            reaction == Reaction.SURPRISED -> { // широко открытые глаза
                r(EyeLo, ex - 1f, eyeTop - 2f, 11f, 12f); r(Eye, ex - 1f, eyeTop - 2f, 10f, 11f)
                r(Color.White.copy(alpha = 0.45f), ex + 1.5f, eyeTop, 2.5f, 2.5f)
            }
            reaction == Reaction.ANNOYED -> { // прищур и сведённые брови
                r(EyeLo, ex, eyeTop, 9f, 9f); r(Eye, ex, eyeTop, 8f, 8f)
                r(Front, ex - 1f, eyeTop - 1f, 11f, 4.5f)
                val inner = if (ex0 == 33f) ex + 9f else ex
                poly(Eye, ex - 1f, eyeTop - 2f, ex + 10f, eyeTop - 2f, inner, eyeTop + 1.5f)
            }
            closed && reaction != Reaction.HAPPY -> r(Eye, ex, eyeTop + 4f, 9f, 2.4f)
            reaction == Reaction.HAPPY || (mood == Mood.HAPPY && !coding && !alert) -> {
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

    when (reaction) {
        Reaction.HAPPY -> listOf(20f to 0f, 78f to 0.5f).forEach { (hx, off) -> // сердечки поднимаются
            val k = (zz * 2f + off) % 1f
            val hy = 22f - k * 18f
            val c = Color(0xFFE8577A).copy(alpha = 1f - k)
            drawCircle(c, 2.6f * u, p(hx - 2f, hy)); drawCircle(c, 2.6f * u, p(hx + 2f, hy))
            poly(c, hx - 4.6f, hy + 0.8f, hx + 4.6f, hy + 0.8f, hx, hy + 6f)
        }
        Reaction.DIZZY -> repeat(3) { i -> // звёздочки кружатся над головой
            val a = (step + i / 3f) * 2f * Math.PI.toFloat()
            val sx = 50f + cos(a) * 20f
            val sy = 14f + sin(a) * 5f
            poly(Color(0xFFF5C542), sx, sy - 4f, sx + 1.3f, sy - 1.3f, sx + 4f, sy, sx + 1.3f, sy + 1.3f,
                sx, sy + 4f, sx - 1.3f, sy + 1.3f, sx - 4f, sy, sx - 1.3f, sy - 1.3f)
        }
        Reaction.ANNOYED -> { // значок злости
            val c = Color(0xFFD63B2F)
            listOf(Triple(0f, 0f, 0f), Triple(7f, 0f, 90f), Triple(0f, 7f, 270f), Triple(7f, 7f, 180f)).forEach { (qx, qy, a0) ->
                drawArc(c, a0, 90f, false, p(77f + qx, 9f + qy), Size(6f * u, 6f * u), style = Stroke(2f * u))
            }
        }
        Reaction.SURPRISED -> { // капелька
            val c = Color(0xFF7FC4F0)
            poly(c, 82f, 14f, 79f, 21f, 85f, 21f); drawCircle(c, 3f * u, p(82f, 21f))
        }
        Reaction.NONE -> {}
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
