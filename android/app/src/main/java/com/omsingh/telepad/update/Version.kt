package com.omsingh.telepad.update

/**
 * A release number as the tags carry it (`v2.0.0`, `v2.0.0-alpha.4`) and which of two is later: semantic
 * versioning, written out because the one rule that matters here is small. `2.0.0-alpha.4` comes after
 * `2.0.0-alpha.3` and before `2.0.0`. The desktop app has the same rules (and the same tests).
 */
class Version private constructor(
    val major: Long,
    val minor: Long,
    val patch: Long,
    private val pre: List<Part>,
) : Comparable<Version> {

    /** One dot-separated part after the hyphen: a number, or a word. */
    private sealed interface Part : Comparable<Part> {
        data class Number(val value: Long) : Part
        data class Word(val text: String) : Part

        override fun compareTo(other: Part): Int = when {
            this is Number && other is Number -> value.compareTo(other.value)
            this is Word && other is Word -> text.compareTo(other.text)
            // Numbers come before words: alpha.1 is before alpha.beta.
            this is Number -> -1
            else -> 1
        }
    }

    val isPrerelease: Boolean get() = pre.isNotEmpty()

    /**
     * Whether a person on this version should be offered [newer]: it has to be later, and only someone
     * already on a pre-release is offered another pre-release.
     */
    fun isOffered(newer: Version): Boolean = newer > this && (!newer.isPrerelease || isPrerelease)

    override fun compareTo(other: Version): Int {
        val core = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
        if (core != 0) return core
        return when {
            pre.isEmpty() && other.pre.isEmpty() -> 0
            // A release comes after all of its pre-releases.
            pre.isEmpty() -> 1
            other.pre.isEmpty() -> -1
            else -> comparePre(pre, other.pre)
        }
    }

    private fun comparePre(a: List<Part>, b: List<Part>): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val c = a[i].compareTo(b[i])
            if (c != 0) return c
        }
        return a.size.compareTo(b.size)
    }

    override fun equals(other: Any?): Boolean = other is Version && compareTo(other) == 0
    override fun hashCode(): Int = listOf(major, minor, patch, pre).hashCode()

    override fun toString(): String = buildString {
        append(major).append('.').append(minor).append('.').append(patch)
        pre.forEachIndexed { i, part ->
            append(if (i == 0) '-' else '.')
            append(
                when (part) {
                    is Part.Number -> part.value.toString()
                    is Part.Word -> part.text
                },
            )
        }
    }

    companion object {
        /** The version in [text] (a leading `v` and a `+build` part are allowed), or `null` if it is not one. */
        fun parse(text: String): Version? {
            val withoutV = text.removePrefix("v")
            val noBuild = withoutV.substringBefore('+')
            val core = noBuild.substringBefore('-')
            val preText = if ('-' in noBuild) noBuild.substringAfter('-') else null

            val numbers = core.split('.')
            if (numbers.size != 3) return null
            val (major, minor, patch) = numbers.map { it.toDigitsOrNull() ?: return null }

            val pre = if (preText == null) {
                emptyList()
            } else {
                preText.split('.').map { part ->
                    if (part.isEmpty() || !part.all { it.isAsciiLetterOrDigit() || it == '-' }) return null
                    part.toDigitsOrNull()?.let { Part.Number(it) } ?: Part.Word(part)
                }
            }
            return Version(major, minor, patch, pre)
        }

        private fun String.toDigitsOrNull(): Long? =
            if (isNotEmpty() && all { it in '0'..'9' }) toLongOrNull() else null

        private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
    }
}
