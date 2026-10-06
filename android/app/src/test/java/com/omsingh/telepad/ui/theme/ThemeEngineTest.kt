package com.omsingh.telepad.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeEngineTest {

    /** The five accents the app offers (they are also what older installs have stored). */
    private val seeds = mapOf(
        "cyan" to 0xFF0EA5E9.toInt(),
        "purple" to 0xFF8B5CF6.toInt(),
        "green" to 0xFF10B981.toInt(),
        "orange" to 0xFFF97316.toInt(),
        "red" to 0xFFEF4444.toInt(),
    )

    private fun everyScheme(): List<Triple<String, Boolean, Int>> =
        seeds.flatMap { (name, seed) -> listOf(Triple(name, false, seed), Triple(name, true, seed)) }

    private fun label(name: String, dark: Boolean) = "$name/${if (dark) "dark" else "light"}"

    @Test
    fun `every text and icon pair is readable in every accent and both modes`() {
        val failures = mutableListOf<String>()
        for ((name, dark, seed) in everyScheme()) {
            val roles = ThemeEngine.scheme(seed, dark)
            val extended = ThemeEngine.extended(seed, dark)
            val requirements = roles.textPairs() + extended.textPairs(roles.surface)
            for (r in requirements) {
                if (r.actual < r.minimum) {
                    failures += "${label(name, dark)} ${r.name}: %.2f < %.1f".format(r.actual, r.minimum)
                }
            }
        }
        assertTrue("Contrast failures:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `body text reaches AAA on the main surface`() {
        for ((name, dark, seed) in everyScheme()) {
            val roles = ThemeEngine.scheme(seed, dark)
            val ratio = ColorMath.contrast(roles.onSurface, roles.surface)
            assertTrue("${label(name, dark)}: $ratio", ratio >= 7.0)
        }
    }

    @Test
    fun `the primary colour keeps the hue of the seed`() {
        for ((name, seed) in seeds) {
            val seedHue = ColorMath.toOklch(seed).h
            for (dark in listOf(false, true)) {
                val roles = ThemeEngine.scheme(seed, dark)
                for ((role, color) in listOf("primary" to roles.primary, "primaryContainer" to roles.primaryContainer)) {
                    val hue = ColorMath.toOklch(color).h
                    assertTrue(
                        "${label(name, dark)} $role has hue $hue, seed has $seedHue",
                        ColorMath.hueDistance(seedHue, hue) <= 10.0,
                    )
                }
            }
        }
    }

    @Test
    fun `surfaces are quiet neutrals so content stays the focus`() {
        for ((name, dark, seed) in everyScheme()) {
            val roles = ThemeEngine.scheme(seed, dark)
            val surfaces = listOf(
                roles.background, roles.surface, roles.surfaceDim, roles.surfaceBright,
                roles.surfaceContainerLowest, roles.surfaceContainerLow, roles.surfaceContainer,
                roles.surfaceContainerHigh, roles.surfaceContainerHighest, roles.inverseSurface,
            )
            for (color in surfaces) {
                val chroma = ColorMath.toOklch(color).c
                assertTrue("${label(name, dark)}: ${ColorMath.argbToHex(color)} has chroma $chroma", chroma < 0.03)
            }
        }
    }

    @Test
    fun `surface containers step up in lightness in the right direction`() {
        for ((name, dark, seed) in everyScheme()) {
            val r = ThemeEngine.scheme(seed, dark)
            val steps = listOf(
                r.surfaceContainerLowest, r.surfaceContainerLow, r.surfaceContainer,
                r.surfaceContainerHigh, r.surfaceContainerHighest,
            ).map { ColorMath.luminance(it) }
            // Dark themes get lighter as a surface is raised; light themes get darker.
            val ordered = if (dark) steps.zipWithNext().all { (a, b) -> b > a } else steps.zipWithNext().all { (a, b) -> b < a }
            assertTrue("${label(name, dark)}: $steps", ordered)
        }
    }

    @Test
    fun `light schemes are light and dark schemes are dark`() {
        for ((name, seed) in seeds) {
            val light = ThemeEngine.scheme(seed, dark = false)
            val dark = ThemeEngine.scheme(seed, dark = true)
            assertTrue("$name light surface", ColorMath.luminance(light.surface) > 0.8)
            assertTrue("$name dark surface", ColorMath.luminance(dark.surface) < 0.02)
            assertTrue("$name light on-surface", ColorMath.luminance(light.onSurface) < 0.05)
            assertTrue("$name dark on-surface", ColorMath.luminance(dark.onSurface) > 0.5)
        }
    }

    @Test
    fun `different accents give clearly different primaries`() {
        val primaries = seeds.values.map { ThemeEngine.scheme(it, dark = false).primary }
        assertEquals(primaries.size, primaries.toSet().size)
        for (i in primaries.indices) for (j in i + 1 until primaries.size) {
            val a = ColorMath.toOklch(primaries[i])
            val b = ColorMath.toOklch(primaries[j])
            // Orange and red sit close on the hue wheel, so allow a smaller gap there.
            assertTrue("primaries $i and $j look alike", ColorMath.hueDistance(a.h, b.h) > 15.0)
        }
    }

    @Test
    fun `an unusual seed still yields a legible scheme`() {
        // Near-grey, near-white and near-black seeds must not break contrast or crash.
        val odd = listOf(0xFF808080.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFFFF00.toInt(), 0xFF0000FF.toInt())
        for (seed in odd) for (dark in listOf(false, true)) {
            val roles = ThemeEngine.scheme(seed, dark)
            val failures = (roles.textPairs() + ThemeEngine.extended(seed, dark).textPairs(roles.surface))
                .filter { it.actual < it.minimum }
            assertTrue("seed ${ColorMath.argbToHex(seed)} dark=$dark: ${failures.map { it.name }}", failures.isEmpty())
        }
    }

    @Test
    fun `status colours do not depend on the accent`() {
        val base = ThemeEngine.extended(seeds.getValue("cyan"), dark = false)
        for ((name, seed) in seeds) {
            val other = ThemeEngine.extended(seed, dark = false)
            assertEquals("$name success", base.success, other.success)
            assertEquals("$name warning", base.warning, other.warning)
        }
        val success = ColorMath.toOklch(base.success).h
        val warning = ColorMath.toOklch(base.warning).h
        assertTrue("success should be green, hue $success", success in 125.0..175.0)
        assertTrue("warning should be amber, hue $warning", warning in 60.0..110.0)
    }

    @Test
    fun `the error colour is red`() {
        for ((name, dark, seed) in everyScheme()) {
            val hue = ColorMath.toOklch(ThemeEngine.scheme(seed, dark).error).h
            assertTrue("${label(name, dark)} error hue $hue", ColorMath.hueDistance(hue, 29.0) < 12.0)
        }
    }

    @Test
    fun `every role is fully opaque`() {
        for ((_, dark, seed) in everyScheme()) {
            val roles = ThemeEngine.scheme(seed, dark)
            val all = roles.textPairs().flatMap { listOf(it.foreground, it.background) } +
                listOf(roles.scrim, roles.surfaceTint, roles.inversePrimary, roles.outlineVariant)
            assertTrue(all.all { (it ushr 24) == 0xFF })
        }
    }

    @Test
    fun `generation is deterministic`() {
        for ((_, dark, seed) in everyScheme()) {
            assertEquals(ThemeEngine.scheme(seed, dark), ThemeEngine.scheme(seed, dark))
            assertEquals(ThemeEngine.extended(seed, dark), ThemeEngine.extended(seed, dark))
        }
    }

    @Test
    fun `light and dark schemes are different`() {
        for ((_, seed) in seeds) {
            assertNotEquals(ThemeEngine.scheme(seed, false).primary, ThemeEngine.scheme(seed, true).primary)
            assertNotEquals(ThemeEngine.scheme(seed, false).surface, ThemeEngine.scheme(seed, true).surface)
        }
    }

    @Test
    fun `the touch pad stands apart from the screen it sits on`() {
        for ((name, dark, seed) in everyScheme()) {
            val roles = ThemeEngine.scheme(seed, dark)
            val extended = ThemeEngine.extended(seed, dark)
            assertNotEquals("${label(name, dark)} pad fill", roles.surface, extended.pad)
            val grid = ColorMath.contrast(extended.padGrid, extended.pad)
            assertTrue("${label(name, dark)} grid is invisible ($grid)", grid >= 1.15)
            assertTrue("${label(name, dark)} grid should stay quieter than the border",
                grid < ColorMath.contrast(extended.padOutline, extended.pad))
        }
    }
}
