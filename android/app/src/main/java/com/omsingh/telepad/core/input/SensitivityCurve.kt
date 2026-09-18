package com.omsingh.telepad.core.input

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign

/**
 * Configurable pointer-acceleration curves.
 *
 * Maps a raw per-event touch delta (in screen px) into a scaled mouse delta
 * suitable for sending to the host. The non-linear curves emulate the feel
 * of each major desktop OS so users can match what they're used to.
 *
 * @param baseSensitivity Linear multiplier applied last. 1.0 = unit gain.
 * @param curve Which acceleration shape to apply.
 *
 * **Thread safety:** Not thread-safe. Owned by a single [TouchpadProcessor]
 * which serializes all access on the input handler thread.
 */
class SensitivityCurve(
    var baseSensitivity: Float = 1.6f,
    var curve: AccelCurve = AccelCurve.MACOS
) {

    enum class AccelCurve {
        /** Direct linear scaling. output = input * sensitivity. */
        LINEAR,

        /** macOS-like: gentle quadratic ramp. Smooth, predictable. */
        MACOS,

        /** Windows-like: piecewise with a faster mid-range. Snappy. */
        WINDOWS,

        /** No acceleration; pure 1:1 mapping ignoring sensitivity slider. */
        FLAT,
    }

    /**
     * Apply the curve to a single-axis delta.
     *
     * @param rawDelta Raw pixel delta from the touch hardware.
     * @return Scaled delta to be sent as a relative mouse move.
     */
    fun apply(rawDelta: Float): Float {
        if (rawDelta == 0f) return 0f
        val magnitude = abs(rawDelta)
        val direction = sign(rawDelta)

        val accelerated = when (curve) {
            AccelCurve.LINEAR -> magnitude * baseSensitivity

            AccelCurve.MACOS -> {
                // macOS-style: output = (input ^ 1.4) * gain.
                // Below the threshold we stay close to linear so precise clicks
                // don't drift; above it, the curve takes over and flicks fly.
                val threshold = 2f
                if (magnitude < threshold) {
                    magnitude * baseSensitivity
                } else {
                    val excess = magnitude - threshold
                    (threshold + excess.pow(1.4f)) * baseSensitivity
                }
            }

            AccelCurve.WINDOWS -> {
                // Windows EPP-style: piecewise linear with three segments,
                // matching the default Mouse Properties → Pointer Options
                // "Enhance pointer precision" curve closely enough that
                // mouse-feel transferred from Windows is preserved.
                val slow = 4f
                val fast = 12f
                val v = when {
                    magnitude < slow -> magnitude * 0.7f
                    magnitude < fast -> {
                        val t = (magnitude - slow) / (fast - slow)
                        slow * 0.7f + t * (fast * 1.4f - slow * 0.7f)
                    }
                    else -> fast * 1.4f + (magnitude - fast) * 1.8f
                }
                v * baseSensitivity
            }

            AccelCurve.FLAT -> magnitude   // sensitivity slider ignored
        }

        return accelerated * direction
    }

    /** Apply curve to both axes. Returns `(scaledDx, scaledDy)`. */
    fun apply(dx: Float, dy: Float): Pair<Float, Float> =
        apply(dx) to apply(dy)
}
