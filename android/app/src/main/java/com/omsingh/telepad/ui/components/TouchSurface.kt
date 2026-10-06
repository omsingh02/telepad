package com.omsingh.telepad.ui.components

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.omsingh.telepad.R
import com.omsingh.telepad.core.input.GestureConfig
import com.omsingh.telepad.core.input.GestureEngine
import com.omsingh.telepad.core.input.InputEvent
import com.omsingh.telepad.ui.theme.Haptics
import com.omsingh.telepad.ui.theme.PadShape
import com.omsingh.telepad.ui.theme.Spacing
import com.omsingh.telepad.ui.theme.TelepadTheme
import com.omsingh.telepad.ui.theme.rememberReducedMotion
import kotlinx.coroutines.delay
import kotlin.math.min

/**
 * The touchpad: a large soft surface that turns finger movement into mouse input.
 *
 * What the fingers do is decided by [GestureEngine]; this composable feeds it touches and
 * shows what it recognised, so that the pad teaches itself: a ring where a click landed,
 * a label saying "Right click" or "Dragging", a dot under every finger. A strip along the
 * right edge scrolls with one finger, like the edge of a laptop trackpad.
 *
 * It is also operable without touch gestures: it exposes click and scroll as accessibility
 * actions, because a screen reader takes over the very gestures the pad relies on.
 */
@Composable
fun TouchSurface(
    onEvent: (InputEvent) -> Unit,
    config: GestureConfig,
    haptics: Haptics,
    modifier: Modifier = Modifier,
    scrollStrip: Boolean = true,
    hints: Boolean = true,
) {
    val density = LocalDensity.current.density
    val currentOnEvent by rememberUpdatedState(onEvent)

    // Words for what was recognised, resolved here because the engine's callback is not composable.
    val clickLabel = stringResource(R.string.pad_label_click)
    val rightLabel = stringResource(R.string.pad_label_right_click)
    val middleLabel = stringResource(R.string.pad_label_middle_click)
    val dragLabel = stringResource(R.string.pad_label_drag)
    val upLabel = stringResource(R.string.pad_label_scroll_up)
    val downLabel = stringResource(R.string.pad_label_scroll_down)
    val padDescription = stringResource(R.string.pad_description)
    val leftAction = stringResource(R.string.pad_action_left_click)
    val rightAction = stringResource(R.string.pad_action_right_click)
    val stripDescription = stringResource(R.string.pad_scroll_strip)
    val upAction = stringResource(R.string.pad_action_scroll_up)
    val downAction = stringResource(R.string.pad_action_scroll_down)

    var label by remember { mutableStateOf<String?>(null) }
    var labelStamp by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var everTouched by remember { mutableStateOf(false) }
    var rippleAt by remember { mutableStateOf(Offset.Zero) }
    var rippleStamp by remember { mutableIntStateOf(0) }
    var lastTickAt by remember { mutableFloatStateOf(0f) }
    var ticking by remember { mutableStateOf(false) }
    val touches = remember { mutableStateListOf<Offset>() }
    var lastPoint by remember { mutableStateOf(Offset.Zero) }

    fun show(text: String?) {
        label = text
        labelStamp++
    }

    val engine = remember {
        GestureEngine { event ->
            when (event) {
                InputEvent.Click -> {
                    haptics.click(); show(clickLabel); rippleAt = lastPoint; rippleStamp++
                }
                InputEvent.RightClick -> {
                    haptics.heavy(); show(rightLabel); rippleAt = lastPoint; rippleStamp++
                }
                is InputEvent.MouseButton -> if (event.button == InputEvent.Button.MIDDLE && event.pressed) {
                    haptics.click(); show(middleLabel); rippleAt = lastPoint; rippleStamp++
                }
                is InputEvent.DragStart -> {
                    dragging = true; haptics.heavy(); show(dragLabel)
                }
                is InputEvent.DragEnd -> {
                    dragging = false; show(null)
                }
                is InputEvent.Scroll -> {
                    val now = SystemClock.uptimeMillis().toFloat()
                    if (now - lastTickAt > 45f) {
                        haptics.tick(); lastTickAt = now
                    }
                    show(if (event.delta > 0) upLabel else downLabel)
                }
                else -> Unit
            }
            currentOnEvent(event)
        }
    }
    SideEffect { engine.config = config.copy(density = density) }

    // The label fades out on its own, unless a drag is still in progress.
    LaunchedEffect(labelStamp) {
        if (label != null && !dragging) {
            delay(900)
            if (!dragging) label = null
        }
    }

    // Long-press, hold-to-drag and momentum are driven by time, so run a frame loop while needed.
    LaunchedEffect(ticking) {
        while (ticking) {
            androidx.compose.runtime.withFrameMillis { }
            engine.tick(SystemClock.uptimeMillis())
            if (!engine.needsTicks) ticking = false
        }
    }

    val extended = TelepadTheme.extended
    val touched = touches.isNotEmpty()
    val borderColor = if (touched || dragging) MaterialTheme.colorScheme.primary else extended.padOutline

    Surface(
        modifier = modifier,
        shape = PadShape,
        color = extended.pad,
        border = BorderStroke(if (touched) 2.dp else 1.5.dp, borderColor),
    ) {
        Row(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .semantics {
                        contentDescription = padDescription
                        customActions = listOf(
                            CustomAccessibilityAction(leftAction) { currentOnEvent(InputEvent.Click); true },
                            CustomAccessibilityAction(rightAction) { currentOnEvent(InputEvent.RightClick); true },
                        )
                    }
                    .padPointerInput(engine, touches, onDown = { everTouched = true; ticking = true }, onPoint = { lastPoint = it })
                    .padGrid(extended.padGrid),
            ) {
                TouchFeedback(touches, rippleAt, rippleStamp, dragging)

                PadOverlays(showHints = hints && !everTouched, label = label)
            }

            if (scrollStrip) {
                ScrollStrip(
                    description = stripDescription,
                    upAction = upAction,
                    downAction = downAction,
                    onScrollBy = { engine.scrollBy(it) },
                    onScrollStep = { currentOnEvent(InputEvent.Scroll(it)) },
                )
            }
        }
    }
}

/** Reads touches and passes them to the engine; draws nothing. */
private fun Modifier.padPointerInput(
    engine: GestureEngine,
    touches: androidx.compose.runtime.snapshots.SnapshotStateList<Offset>,
    onDown: () -> Unit,
    onPoint: (Offset) -> Unit,
): Modifier = pointerInput(engine) {
    awaitPointerEventScope {
        var previousCount = 0
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val time = event.changes.first().uptimeMillis
                val pressed = event.changes.filter { it.pressed }
                val count = pressed.size

                var cx = 0f
                var cy = 0f
                for (change in pressed) { cx += change.position.x; cy += change.position.y }
                if (count > 0) { cx /= count; cy /= count }
                if (count > 0) onPoint(Offset(cx, cy))

                touches.clear()
                for (change in pressed) touches.add(change.position)

                when {
                    previousCount == 0 && count > 0 -> {
                        onDown()
                        engine.onDown(time, cx, cy)
                        if (count > 1) engine.onPointerDown(time, count, cx, cy)
                    }
                    count > previousCount -> engine.onPointerDown(time, count, cx, cy)
                    count == 0 && previousCount > 0 -> engine.onUp(time)
                    count < previousCount -> engine.onPointerUp(time, count, cx, cy)
                    event.changes.any { it.positionChanged() } -> engine.onMove(time, count, cx, cy)
                }
                previousCount = count
                event.changes.forEach { it.consume() }
            }
        } finally {
            // The scope ended (the screen went away, or the system took the touch): let go of everything.
            touches.clear()
            engine.onCancel()
        }
    }
}

/** A faint dot grid: it gives the surface texture, and makes finger movement easier to see. */
private fun Modifier.padGrid(color: Color): Modifier = drawWithCache {
    val step = 28.dp.toPx()
    val width = size.width
    val height = size.height
    val points = buildList {
        var y = step / 2
        while (y < height) {
            var x = step / 2
            while (x < width) {
                add(Offset(x, y))
                x += step
            }
            y += step
        }
    }
    onDrawBehind {
        drawPoints(points, PointMode.Points, color, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
    }
}

/** A glow under each finger, and a ring that spreads from where a click happened. */
@Composable
private fun TouchFeedback(
    touches: List<Offset>,
    rippleAt: Offset,
    rippleStamp: Int,
    dragging: Boolean,
) {
    val reduced = rememberReducedMotion()
    val progress = remember { Animatable(1f) }
    LaunchedEffect(rippleStamp) {
        if (rippleStamp > 0 && !reduced) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(450))
        }
    }
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxSize()) {
        val glow = 30.dp.toPx()
        for (point in touches) {
            drawCircle(color.copy(alpha = if (dragging) 0.28f else 0.16f), glow, point)
            drawCircle(color.copy(alpha = 0.9f), 6.dp.toPx(), point)
        }
        val p = progress.value
        if (p < 1f) {
            drawCircle(
                color = color.copy(alpha = (1f - p) * 0.6f),
                radius = 16.dp.toPx() + 56.dp.toPx() * p,
                center = rippleAt,
                style = Stroke(width = 3.dp.toPx() * (1f - p) + 1f),
            )
        }
    }
}

/**
 * The scroll strip along the pad's edge: slide one finger up or down to scroll.
 * Notches move with the finger so that it feels like turning a wheel.
 */
@Composable
private fun ScrollStrip(
    description: String,
    upAction: String,
    downAction: String,
    onScrollBy: (Float) -> Unit,
    onScrollStep: (Float) -> Unit,
) {
    var active by remember { mutableStateOf(false) }
    var shift by remember { mutableFloatStateOf(0f) }
    val outline = TelepadTheme.extended.padOutline
    val accent = MaterialTheme.colorScheme.primary
    val color = if (active) accent else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = Modifier
            .width(44.dp)
            .fillMaxHeight()
            .semantics {
                contentDescription = description
                customActions = listOf(
                    CustomAccessibilityAction(upAction) { onScrollStep(1f); true },
                    CustomAccessibilityAction(downAction) { onScrollStep(-1f); true },
                )
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { active = true },
                    onDragEnd = { active = false },
                    onDragCancel = { active = false },
                ) { change, dy ->
                    change.consume()
                    shift += dy
                    onScrollBy(dy)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // The divider between pad and strip.
            drawLine(outline, Offset(0f, 16.dp.toPx()), Offset(0f, size.height - 16.dp.toPx()), strokeWidth = 1.5.dp.toPx())
            // Notches that travel with the finger.
            val spacing = 18.dp.toPx()
            val offset = ((shift % spacing) + spacing) % spacing
            var y = offset
            while (y < size.height) {
                val edge = min(y, size.height - y) / (size.height / 2f)
                drawLine(
                    color = color.copy(alpha = 0.18f + 0.4f * edge.coerceIn(0f, 1f)),
                    start = Offset(size.width / 2 - 7.dp.toPx(), y),
                    end = Offset(size.width / 2 + 7.dp.toPx(), y),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                y += spacing
            }
        }
        Column(Modifier.fillMaxHeight().padding(vertical = Spacing.sm), verticalArrangement = Arrangement.SpaceBetween) {
            Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = null, tint = color)
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = color)
        }
    }
}

/** The first-use hint, and the label naming what the pad just recognised. */
@Composable
private fun PadOverlays(showHints: Boolean, label: String?) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = showHints,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = Spacing.lg)
                    .background(TelepadTheme.extended.pad.copy(alpha = 0.92f), MaterialTheme.shapes.large)
                    .padding(horizontal = Spacing.lg, vertical = Spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Icon(
                    Icons.Rounded.TouchApp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(40.dp),
                )
                Text(
                    stringResource(R.string.pad_hint),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                for (line in listOf(R.string.pad_hint_scroll, R.string.pad_hint_right)) {
                    Text(
                        stringResource(line),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = label != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = Spacing.lg),
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.inverseSurface) {
                Text(
                    text = label.orEmpty(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                )
            }
        }
    }
}
