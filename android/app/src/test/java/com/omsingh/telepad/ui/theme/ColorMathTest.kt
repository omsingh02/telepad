package com.omsingh.telepad.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ColorMathTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    private fun channelDelta(a: Int, b: Int): Int =
        maxOf(
            abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)),
            abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)),
            abs((a and 0xFF) - (b and 0xFF)),
        )

    // ── WCAG ────────────────────────────────────────────────────────────

    @Test
    fun `black on white is the maximum contrast of 21`() {
        assertEquals(21.0, ColorMath.contrast(black, white), 1e-9)
    }

    @Test
    fun `contrast is symmetric and a colour has no contrast with itself`() {
        val a = 0xFF336699.toInt()
        val b = 0xFFFFCC00.toInt()
        assertEquals(ColorMath.contrast(a, b), ColorMath.contrast(b, a), 1e-12)
        assertEquals(1.0, ColorMath.contrast(a, a), 1e-12)
    }

    @Test
    fun `matches the published WCAG figure for 767676 on white`() {
        // #767676 is the lightest grey that still passes AA on white; the reference value is 4.54.
        assertEquals(4.54, ColorMath.contrast(0xFF767676.toInt(), white), 0.01)
    }

    @Test
    fun `luminance of the primaries uses the sRGB weights`() {
        assertEquals(0.2126, ColorMath.luminance(0xFFFF0000.toInt()), 1e-4)
        assertEquals(0.7152, ColorMath.luminance(0xFF00FF00.toInt()), 1e-4)
        assertEquals(0.0722, ColorMath.luminance(0xFF0000FF.toInt()), 1e-4)
    }

    // ── Oklab ───────────────────────────────────────────────────────────

    @Test
    fun `oklch of the sRGB primaries matches the reference values`() {
        // Reference values from Björn Ottosson's Oklab article.
        val red = ColorMath.toOklch(0xFFFF0000.toInt())
        assertEquals(0.628, red.l, 0.002); assertEquals(0.2577, red.c, 0.002); assertEquals(29.2, red.h, 0.3)

        val green = ColorMath.toOklch(0xFF00FF00.toInt())
        assertEquals(0.8664, green.l, 0.002); assertEquals(0.2948, green.c, 0.002); assertEquals(142.5, green.h, 0.3)

        val blue = ColorMath.toOklch(0xFF0000FF.toInt())
        assertEquals(0.4520, blue.l, 0.002); assertEquals(0.3132, blue.c, 0.002); assertEquals(264.1, blue.h, 0.3)
    }

    @Test
    fun `greys have no chroma and white is full lightness`() {
        assertEquals(1.0, ColorMath.toOklch(white).l, 1e-3)
        assertEquals(0.0, ColorMath.toOklch(black).l, 1e-6)
        assertEquals(0.0, ColorMath.toOklch(0xFF808080.toInt()).c, 1e-3)
    }

    @Test
    fun `colours survive a round trip through oklch`() {
        val samples = intArrayOf(
            0xFF0EA5E9.toInt(), 0xFF8B5CF6.toInt(), 0xFF10B981.toInt(), 0xFFF97316.toInt(), 0xFFEF4444.toInt(),
            0xFF123456.toInt(), 0xFFFEDCBA.toInt(), 0xFF808080.toInt(), 0xFF010203.toInt(), 0xFFFAFAFA.toInt(),
        )
        for (argb in samples) {
            val lch = ColorMath.toOklch(argb)
            val back = ColorMath.fromOklch(lch.l, lch.c, lch.h)
            assertTrue(
                "${ColorMath.argbToHex(argb)} came back as ${ColorMath.argbToHex(back)}",
                channelDelta(argb, back) <= 2,
            )
        }
    }

    @Test
    fun `results are always opaque`() {
        assertEquals(0xFF, ColorMath.fromOklch(0.5, 0.1, 120.0) ushr 24)
        assertEquals(0xFF, ColorMath.colorOfTone(120.0, 0.1, 50.0) ushr 24)
        assertEquals(0xFF000000.toInt(), ColorMath.fromOklch(0.0, 0.1, 10.0))
        assertEquals(0xFFFFFFFF.toInt(), ColorMath.fromOklch(1.0, 0.1, 10.0))
    }

    @Test
    fun `colours too vivid for sRGB are pulled into gamut without changing hue or lightness`() {
        val wanted = Oklch(l = 0.60, c = 0.40, h = 142.0)
        val fitted = ColorMath.toOklch(ColorMath.fromOklch(wanted.l, wanted.c, wanted.h))
        assertTrue("chroma should have been reduced, was ${fitted.c}", fitted.c < wanted.c)
        assertEquals(wanted.l, fitted.l, 0.01)
        assertTrue("hue drifted to ${fitted.h}", ColorMath.hueDistance(wanted.h, fitted.h) < 3.0)
    }

    // ── Tones ───────────────────────────────────────────────────────────

    @Test
    fun `tone 0 and 100 are black and white and 50 is a mid grey`() {
        assertEquals(0.0, ColorMath.luminanceOfTone(0.0), 1e-12)
        assertEquals(1.0, ColorMath.luminanceOfTone(100.0), 1e-12)
        // CIE L* 50 is about 18.4% luminance, the classic "middle grey".
        assertEquals(0.184, ColorMath.luminanceOfTone(50.0), 0.001)
    }

    @Test
    fun `a colour of a given tone has the luminance of that tone whatever its hue`() {
        for (hue in listOf(0.0, 29.0, 85.0, 150.0, 237.0, 295.0, 340.0)) {
            for (tone in listOf(6, 10, 20, 30, 40, 50, 60, 80, 90, 95, 98)) {
                val argb = ColorMath.colorOfTone(hue, 0.15, tone.toDouble())
                val expected = ColorMath.luminanceOfTone(tone.toDouble())
                val actual = ColorMath.luminance(argb)
                // 8-bit channels cannot hit the target exactly, so allow rounding error.
                val tolerance = 0.0015 + expected * 0.03
                assertTrue(
                    "hue $hue tone $tone ${ColorMath.argbToHex(argb)}: luminance $actual, wanted $expected",
                    abs(actual - expected) <= tolerance,
                )
            }
        }
    }

    @Test
    fun `tones of one hue get monotonically lighter`() {
        for (hue in listOf(29.0, 85.0, 150.0, 237.0, 295.0)) {
            var previous = -1.0
            for (tone in 0..100 step 5) {
                val luminance = ColorMath.luminance(ColorMath.colorOfTone(hue, 0.14, tone.toDouble()))
                assertTrue("hue $hue: tone $tone is darker than the one before", luminance >= previous)
                previous = luminance
            }
        }
    }

    @Test
    fun `colours forty tones apart always pass the AA text threshold`() {
        // The property the whole palette relies on, checked across the hue circle.
        for (hue in 0 until 360 step 15) {
            val dark = ColorMath.colorOfTone(hue.toDouble(), 0.2, 40.0)
            val light = ColorMath.colorOfTone(((hue + 120) % 360).toDouble(), 0.2, 98.0)
            assertTrue("hue $hue: ${ColorMath.contrast(dark, light)}", ColorMath.contrast(dark, light) >= 4.5)
        }
    }

    @Test
    fun `hue distance wraps around the circle`() {
        assertEquals(20.0, ColorMath.hueDistance(350.0, 10.0), 1e-9)
        assertEquals(180.0, ColorMath.hueDistance(0.0, 180.0), 1e-9)
        assertEquals(0.0, ColorMath.hueDistance(90.0, 90.0), 1e-9)
    }

    @Test
    fun `hex strings are upper case six digits without alpha`() {
        assertEquals("#0EA5E9", ColorMath.argbToHex(0xFF0EA5E9.toInt()))
        assertEquals("#000000", ColorMath.argbToHex(0xFF000000.toInt()))
    }
}
