package app.claudebuddy.service

import app.claudebuddy.ui.tr
import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.claudebuddy.brain.Verdict
import app.claudebuddy.data.FeedStore
import app.claudebuddy.ui.Activity
import app.claudebuddy.ui.ClaudeOrange
import app.claudebuddy.ui.Cream
import app.claudebuddy.ui.Ink
import app.claudebuddy.ui.Mascot
import app.claudebuddy.ui.Mood
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Lifecycle для ComposeView вне Activity. */
private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    fun start() { saved.performRestore(null); registry.currentState = Lifecycle.State.RESUMED }
    fun stop() { registry.currentState = Lifecycle.State.DESTROYED }
}

/** Окно поверх всего (accessibility overlay — отдельное разрешение не нужно). */
private class OverlayWindow(private val service: AccessibilityService, private val lp: () -> WindowManager.LayoutParams) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null
    val isShowing get() = view != null

    fun show(content: @Composable () -> Unit) {
        if (view != null) return
        val o = OverlayOwner().also { it.start() }
        val v = ComposeView(service).apply {
            setViewTreeLifecycleOwner(o); setViewTreeSavedStateRegistryOwner(o); setContent(content)
        }
        runCatching { wm.addView(v, lp()) }.onSuccess { view = v; owner = o }.onFailure { o.stop() }
    }

    fun hide() {
        view?.let { runCatching { wm.removeView(it) } }
        owner?.stop(); view = null; owner = null
    }
}

/** Маленький значок во время листания: Claude + счётчик роликов (как в Inku). Не мешает касаниям. */
class FloatingBubble(service: AccessibilityService, private val store: FeedStore) {
    private val window = OverlayWindow(service) {
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 24; y = 260 }
    }
    val isShowing get() = window.isShowing

    fun show(countVideos: Boolean) = window.show {
        val today by store.today.collectAsState()
        val settings by store.settings.collectAsState(initial = app.claudebuddy.data.Settings())
        val (value, limit) = if (countVideos) today.totalVideos.toInt() to settings.videoLimit else today.totalMinutes to settings.limitMinutes
        val near = value >= limit * 0.8
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.55f))
                .border(1.dp, if (near) ClaudeOrange else Color.White.copy(alpha = 0.25f), RoundedCornerShape(50))
                .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Mascot(if (near) Mood.WORRIED else Mood.HAPPY, size = 34.dp)
            Text("$value/$limit${if (countVideos) "" else " " + tr("мин", "min")}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    fun hide() = window.hide()
}

/** Барьер после лимита: фон темнеет, появляется Claude. Выбор: выйти или остаться (с причиной). */
class BarrierOverlay(
    service: AccessibilityService,
    private val judge: suspend (reason: String) -> Verdict,
    private val onAllow: (minutes: Int) -> Unit,
    private val onExit: () -> Unit,
) {
    private val window = OverlayWindow(service) {
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            @Suppress("DEPRECATION")
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
    }
    val isShowing get() = window.isShowing

    fun show(appTitle: String, summary: String) = window.show {
        BarrierContent(appTitle, summary, judge,
            allow = { m -> dismiss(); onAllow(m) },
            exit = { dismiss(); onExit() })
    }

    fun dismiss() = window.hide()
}

private sealed interface Stage {
    data object Choice : Stage
    data object Reason : Stage
    data object Thinking : Stage
    data class Answer(val verdict: Verdict) : Stage
}

@Composable
private fun BarrierContent(
    appTitle: String,
    summary: String,
    judge: suspend (String) -> Verdict,
    allow: (Int) -> Unit,
    exit: () -> Unit,
) {
    var stage by remember { mutableStateOf<Stage>(Stage.Choice) }
    var reason by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.62f))
            // касания по затемнению не проходят в ленту
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            val (mood, act) = when (val s = stage) {
                Stage.Choice, Stage.Reason -> Mood.WORRIED to Activity.IDLE
                Stage.Thinking -> Mood.THINKING to Activity.IDLE
                is Stage.Answer -> if (s.verdict is Verdict.Deny) Mood.WORRIED to Activity.IDLE else Mood.HAPPY to Activity.ALERT
            }
            Mascot(mood, size = 130.dp, activity = act)
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Cream).padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (val s = stage) {
                    Stage.Choice -> {
                        Title(tr("Лимит на сегодня закончился", "That's your limit for today"))
                        Body(tr("Уже $summary. Может, на сегодня хватит $appTitle?", "Already $summary. Maybe that's enough $appTitle for today?"))
                        Spacer(Modifier.height(18.dp))
                        Primary(tr("Выйти", "Leave"), onClick = exit)
                        Spacer(Modifier.height(8.dp))
                        Secondary(tr("Остаться — объясню зачем", "Stay — I'll explain why")) { stage = Stage.Reason }
                    }
                    Stage.Reason -> {
                        Title(tr("Зачем тебе сейчас $appTitle?", "Why do you need $appTitle right now?"))
                        Body(tr("Расскажи честно — если причина хорошая, я пущу ненадолго.", "Be honest — if it's a good reason, I'll let you in for a bit."))
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = reason, onValueChange = { reason = it.take(300) },
                            modifier = Modifier.fillMaxWidth(), placeholder = { Text(tr("Например: друг прислал видео, надо ответить", "e.g. a friend sent me a video, I need to reply")) },
                            shape = RoundedCornerShape(16.dp), minLines = 2,
                        )
                        Spacer(Modifier.height(12.dp))
                        Primary(tr("Объяснить Claude", "Explain to Claude"), enabled = reason.isNotBlank()) {
                            stage = Stage.Thinking
                            scope.launch { stage = Stage.Answer(judge(reason)) }
                        }
                        TextButton(onClick = exit) { Text(tr("Передумал, выхожу", "Never mind, I'm leaving"), color = Ink) }
                    }
                    Stage.Thinking -> {
                        Title(tr("Думаю…", "Thinking…"))
                        Spacer(Modifier.height(12.dp))
                        CircularProgressIndicator(color = ClaudeOrange)
                    }
                    is Stage.Answer -> when (val v = s.verdict) {
                        is Verdict.Allow -> {
                            Body(v.message)
                            Spacer(Modifier.height(16.dp))
                            Primary(tr("Спасибо! (${v.minutes} мин)", "Thanks! (${v.minutes} min)")) { allow(v.minutes) }
                        }
                        is Verdict.Deny -> {
                            Body(v.message)
                            Spacer(Modifier.height(16.dp))
                            Primary(tr("Хорошо, выхожу", "OK, I'm leaving"), onClick = exit)
                            TextButton(onClick = { reason = ""; stage = Stage.Reason }) { Text(tr("Объяснить по-другому", "Explain differently"), color = Ink) }
                        }
                        is Verdict.Wait -> {
                            var left by remember { mutableIntStateOf(v.seconds) }
                            LaunchedEffect(Unit) { while (left > 0) { delay(1000); left-- } }
                            Body(v.message)
                            Spacer(Modifier.height(16.dp))
                            if (left > 0) Title("$left") else Primary(tr("Открыть на ${v.minutes} мин", "Open for ${v.minutes} min")) { allow(v.minutes) }
                            TextButton(onClick = exit) { Text(tr("Передумал, выхожу", "Never mind, I'm leaving"), color = Ink) }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun Title(text: String) =
    Text(text, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = Ink, textAlign = TextAlign.Center)

@Composable private fun Body(text: String) =
    Text(text, fontSize = 16.sp, color = Ink.copy(alpha = 0.85f), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))

@Composable private fun Primary(text: String, enabled: Boolean = true, onClick: () -> Unit) =
    Button(
        onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(50.dp),
        shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = ClaudeOrange),
    ) { Text(text, fontSize = 16.sp) }

@Composable private fun Secondary(text: String, onClick: () -> Unit) =
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp)) {
        Text(text, fontSize = 15.sp, color = Ink)
    }
