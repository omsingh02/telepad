package com.omsingh.telepad.ui.theme

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** A colour in Oklab's polar form: perceptual lightness, chroma and hue (degrees). */
internal data class Oklch(val l: Double, val c: Double, val h: Double)

/**
 * Colour science for the theme engine: sRGB, Oklab/OKLCH, WCAG luminance.
 *
 * Oklab (Björn Ottosson, 2020) is a perceptually even colour space, so changing
 * only the hue of a colour keeps it looking equally light and equally vivid.
 * That is what lets a single seed colour grow into a whole family of accents.
 *
 * Everything here is plain `Double` math with colours as `0xAARRGGBB` ints, so it
 * runs in ordinary unit tests with no Android or Compose on the classpath.
 */
internal object ColorMath {

    // ── sRGB <-> linear light ───────────────────────────────────────────

    private fun toLinear(channel8: Int): Double {
        val c = channel8 / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun toSrgb8(linear: Double): Int {
        val c = linear.coerceIn(0.0, 1.0)
        val encoded = if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1.0 / 2.4) - 0.055
        return (encoded * 255.0).roundToInt().coerceIn(0, 255)
    }

    private fun pack(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    // ── Oklab ───────────────────────────────────────────────────────────

    fun toOklch(argb: Int): Oklch {
        val r = toLinear((argb shr 16) and 0xFF)
        val g = toLinear((argb shr 8) and 0xFF)
        val b = toLinear(argb and 0xFF)

        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)

        val okL = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s
        val okA = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s
        val okB = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s

        val chroma = hypot(okA, okB)
        val hue = if (chroma < 1e-6) 0.0 else Math.toDegrees(atan2(okB, okA)).let { if (it < 0) it + 360.0 else it }
        return Oklch(okL, chroma, hue)
    }

    /** Linear-light RGB of an OKLCH colour; components may fall outside 0..1 (out of gamut). */
    private fun toLinearRgb(l: Double, c: Double, hueDeg: Double): DoubleArray {
        val hue = Math.toRadians(hueDeg)
        val a = c * cos(hue)
        val b = c * sin(hue)

        val lp = (l + 0.3963377774 * a + 0.2158037573 * b).let { it * it * it }
        val mp = (l - 0.1055613458 * a - 0.0638541728 * b).let { it * it * it }
        val sp = (l - 0.0894841775 * a - 1.2914855480 * b).let { it * it * it }

        return doubleArrayOf(
            4.0767416621 * lp - 3.3077115913 * mp + 0.2309699292 * sp,
            -1.2684380046 * lp + 2.6097574011 * mp - 0.3413193965 * sp,
            -0.0041960863 * lp - 0.7034186147 * mp + 1.7076147010 * sp,
        )
    }

    private fun inGamut(rgb: DoubleArray): Boolean = rgb.all { it >= -GAMUT_EPSILON && it <= 1.0 + GAMUT_EPSILON }

    /**
     * The colour at the given OKLCH coordinates, or, when that is too vivid for
     * sRGB, the most saturated colour of the same lightness and hue that fits.
     */
    fun fromOklch(l: Double, chroma: Double, hueDeg: Double): Int {
        if (l <= 0.0) return pack(0, 0, 0)
        if (l >= 1.0) return pack(255, 255, 255)

        var rgb = toLinearRgb(l, chroma, hueDeg)
        if (!inGamut(rgb)) {
            var low = 0.0
            var high = chroma
            repeat(24) {
                val mid = (low + high) / 2
                if (inGamut(toLinearRgb(l, mid, hueDeg))) low = mid else high = mid
            }
            rgb = toLinearRgb(l, low, hueDeg)
        }
        return pack(toSrgb8(rgb[0]), toSrgb8(rgb[1]), toSrgb8(rgb[2]))
    }

    // ── Luminance, contrast ─────────────────────────────────────────────

    /** WCAG relative luminance, 0 (black) to 1 (white). */
    fun luminance(argb: Int): Double =
        0.2126 * toLinear((argb shr 16) and 0xFF) +
            0.7152 * toLinear((argb shr 8) and 0xFF) +
            0.0722 * toLinear(argb and 0xFF)

    /** WCAG contrast ratio, 1 (identical) to 21 (black on white). */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    // ── Tones ───────────────────────────────────────────────────────────

    /** Relative luminance of a grey with CIE lightness [tone] (0 to 100). */
    fun luminanceOfTone(tone: Double): Double {
        val t = tone.coerceIn(0.0, 100.0)
        val ft = (t + 16.0) / 116.0
        val cubed = ft * ft * ft
        return if (cubed > CIE_EPSILON) cubed else (116.0 * ft - 16.0) / CIE_KAPPA
    }

    /**
     * The colour of the given [hue] and (up to) [chroma] whose relative luminance
     * matches CIE lightness [tone]. Tone is what Material 3 builds its contrast
     * guarantees on: two colours whose tones differ by 40 always have enough
     * contrast, whatever their hue.
     */
    fun colorOfTone(hueDeg: Double, chroma: Double, tone: Double): Int {
        if (tone <= 0.0) return pack(0, 0, 0)
        if (tone >= 100.0) return pack(255, 255, 255)

        val target = luminanceOfTone(tone)
        var low = 0.0
        var high = 1.0
        repeat(40) {
            val mid = (low + high) / 2
            if (luminance(fromOklch(mid, chroma, hueDeg)) < target) low = mid else high = mid
        }
        return fromOklch((low + high) / 2, chroma, hueDeg)
    }

    fun argbToHex(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

    fun hueDistance(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }

    private const val GAMUT_EPSILON = 0.0008
    private const val CIE_EPSILON = 216.0 / 24389.0
    private const val CIE_KAPPA = 24389.0 / 27.0
}
