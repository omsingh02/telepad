package com.omsingh.telepad.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.omsingh.telepad.ui.theme.rememberReducedMotion

/** Which scene [HeroIllustration] draws. */
enum class Scene {
    /** A phone talking to a PC. */
    PHONE_AND_PC,

    /** Both on one Wi-Fi network. */
    SAME_NETWORK,

    /** A fingerprint being checked between the two. */
    VERIFY,
}

/**
 * Simple line-and-tone drawings for empty states and onboarding, drawn from the theme's
 * own colours so they follow the accent and dark mode. They carry no information a
 * screen reader needs, so they are hidden from it.
 */
@Composable
fun HeroIllustration(scene: Scene, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val container = MaterialTheme.colorScheme.primaryContainer
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
    val body = MaterialTheme.colorScheme.surfaceContainerHighest
    val outline = MaterialTheme.colorScheme.outline
    val surface = MaterialTheme.colorScheme.surface
    val reduced = rememberReducedMotion()

    // With animations switched off nothing may run forever: the picture simply holds still.
    val t = if (reduced) {
        0.5f
    } else {
        val transition = rememberInfiniteTransition(label = "illustration")
        val phase by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
            label = "phase",
        )
        phase
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clearAndSetSemantics { },
    ) {
        val w = size.width
        val h = size.height
        val phone = Offset(w * 0.12f, h * 0.18f) to Size(w * 0.17f, h * 0.64f)
        val laptopScreen = Offset(w * 0.45f, h * 0.2f) to Size(w * 0.42f, h * 0.44f)

        when (scene) {
            Scene.PHONE_AND_PC -> {
                drawLink(phone.first.x + phone.second.width, h * 0.5f, laptopScreen.first.x, h * 0.42f, primary, t)
            }
            Scene.SAME_NETWORK -> {
                drawWifiArcs(Offset(w * 0.5f, h * 0.2f), h * 0.2f, primary, t)
                drawLine(outline, Offset(phone.first.x + phone.second.width / 2, h * 0.2f), Offset(w * 0.5f, h * 0.2f), 3f, StrokeCap.Round)
                drawLine(outline, Offset(w * 0.5f, h * 0.2f), Offset(laptopScreen.first.x + laptopScreen.second.width / 2, h * 0.2f), 3f, StrokeCap.Round)
            }
            Scene.VERIFY -> {
                drawLink(phone.first.x + phone.second.width, h * 0.5f, laptopScreen.first.x, h * 0.42f, outline, 0.5f)
                drawShield(Offset(w * 0.5f, h * 0.46f), h * 0.2f, container, onContainer)
            }
        }

        drawPhone(phone.first, phone.second, body, outline, container, primary, surface, touch = scene != Scene.SAME_NETWORK, t = t)
        drawLaptop(laptopScreen.first, laptopScreen.second, body, outline, container, primary)
    }
}

private fun DrawScope.drawPhone(
    topLeft: Offset, size: Size, body: Color, outline: Color, pad: Color, accent: Color, surface: Color,
    touch: Boolean, t: Float,
) {
    drawRoundRect(body, topLeft, size, CornerRadius(size.width * 0.22f))
    drawRoundRect(outline, topLeft, size, CornerRadius(size.width * 0.22f), style = Stroke(3f))
    // The pad on the screen.
    val inset = size.width * 0.12f
    val padTopLeft = Offset(topLeft.x + inset, topLeft.y + size.height * 0.12f)
    val padSize = Size(size.width - 2 * inset, size.height * 0.62f)
    drawRoundRect(pad, padTopLeft, padSize, CornerRadius(size.width * 0.12f))
    // Two "buttons" under it.
    val buttonY = padTopLeft.y + padSize.height + size.height * 0.05f
    val buttonW = (padSize.width - inset / 2) / 2
    drawRoundRect(surface, Offset(padTopLeft.x, buttonY), Size(buttonW, size.height * 0.07f), CornerRadius(6f))
    drawRoundRect(surface, Offset(padTopLeft.x + buttonW + inset / 2, buttonY), Size(buttonW, size.height * 0.07f), CornerRadius(6f))
    if (touch) {
        // A fingertip drifting across the pad.
        val cx = padTopLeft.x + padSize.width * (0.3f + 0.4f * t)
        val cy = padTopLeft.y + padSize.height * (0.65f - 0.3f * t)
        drawCircle(accent.copy(alpha = 0.25f), padSize.width * 0.22f, Offset(cx, cy))
        drawCircle(accent, padSize.width * 0.09f, Offset(cx, cy))
    }
}

private fun DrawScope.drawLaptop(
    topLeft: Offset, size: Size, body: Color, outline: Color, screen: Color, accent: Color,
) {
    drawRoundRect(body, topLeft, size, CornerRadius(14f))
    drawRoundRect(outline, topLeft, size, CornerRadius(14f), style = Stroke(3f))
    val inset = 10f
    drawRoundRect(screen, Offset(topLeft.x + inset, topLeft.y + inset), Size(size.width - 2 * inset, size.height - 2 * inset), CornerRadius(8f))
    // A pointer on the screen.
    val tip = Offset(topLeft.x + size.width * 0.42f, topLeft.y + size.height * 0.3f)
    val pointer = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(tip.x, tip.y + size.height * 0.34f)
        lineTo(tip.x + size.width * 0.07f, tip.y + size.height * 0.26f)
        lineTo(tip.x + size.width * 0.13f, tip.y + size.height * 0.4f)
        lineTo(tip.x + size.width * 0.17f, tip.y + size.height * 0.37f)
        lineTo(tip.x + size.width * 0.11f, tip.y + size.height * 0.23f)
        lineTo(tip.x + size.width * 0.2f, tip.y + size.height * 0.23f)
        close()
    }
    drawPath(pointer, accent)
    // The base.
    val baseTop = topLeft.y + size.height + 6f
    drawRoundRect(outline, Offset(topLeft.x - size.width * 0.12f, baseTop), Size(size.width * 1.24f, 10f), CornerRadius(5f))
}

private fun DrawScope.drawLink(x1: Float, y1: Float, x2: Float, y2: Float, color: Color, t: Float) {
    val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 14f), -t * 56f)
    drawLine(color, Offset(x1 + 12f, y1), Offset(x2 - 12f, y2), strokeWidth = 5f, cap = StrokeCap.Round, pathEffect = dash)
}

private fun DrawScope.drawWifiArcs(center: Offset, radius: Float, color: Color, t: Float) {
    for (i in 0..2) {
        val r = radius * (0.5f + i * 0.5f)
        val alpha = (0.35f + 0.65f * (1f - ((t + i * 0.25f) % 1f))).coerceIn(0.2f, 1f)
        drawArc(
            color = color.copy(alpha = alpha),
            startAngle = 225f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(center.x - r, center.y - r + radius * 0.5f),
            size = Size(2 * r, 2 * r),
            style = Stroke(6f, cap = StrokeCap.Round),
        )
    }
    drawCircle(color, 7f, Offset(center.x, center.y + radius * 0.5f))
}

private fun DrawScope.drawShield(center: Offset, radius: Float, fill: Color, mark: Color) {
    val path = Path().apply {
        moveTo(center.x, center.y - radius)
        lineTo(center.x + radius * 0.85f, center.y - radius * 0.6f)
        lineTo(center.x + radius * 0.75f, center.y + radius * 0.25f)
        quadraticTo(center.x + radius * 0.5f, center.y + radius * 0.8f, center.x, center.y + radius)
        quadraticTo(center.x - radius * 0.5f, center.y + radius * 0.8f, center.x - radius * 0.75f, center.y + radius * 0.25f)
        lineTo(center.x - radius * 0.85f, center.y - radius * 0.6f)
        close()
    }
    drawPath(path, fill)
    val check = Path().apply {
        moveTo(center.x - radius * 0.35f, center.y + radius * 0.02f)
        lineTo(center.x - radius * 0.08f, center.y + radius * 0.3f)
        lineTo(center.x + radius * 0.4f, center.y - radius * 0.25f)
    }
    drawPath(check, mark, style = Stroke(radius * 0.18f, cap = StrokeCap.Round))
}
