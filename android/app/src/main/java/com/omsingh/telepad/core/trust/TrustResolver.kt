package com.omsingh.telepad.core.trust

/** What the app must do before it may connect to a PC. */
sealed interface TrustDecision {

    /** The PC is one this phone has paired with: connect without asking. */
    data class Connect(val device: PairedDevice) : TrustDecision

    /**
     * A PC never seen before. The user must compare its fingerprint with the one the
     * PC shows, and only then is it trusted.
     */
    data class Verify(val publicKey: String) : TrustDecision

    /**
     * Something answers at the address of a paired PC, under the same name, but with
     * a different key. Either Telepad was reinstalled there, or someone is pretending
     * to be that PC. The user must be warned and must decide.
     */
    data class KeyChanged(val expected: PairedDevice, val presentedKey: String) : TrustDecision
}

/**
 * Trust on first use, decided purely from what is already paired.
 *
 * The rules, in order:
 *
 *  1. A key that is already paired is trusted, wherever it now lives. (The PC just
 *     got a new IP address.)
 *  2. A new key at the address *and under the name* of a paired PC is a changed
 *     identity: the user is warned.
 *  3. Anything else is a new PC to verify. Notably a new key at a known address under
 *     a *different* name is not a warning: that is another computer that was handed
 *     the same address, which is ordinary on a home network.
 *
 * Nothing is remembered here. The caller saves a device only after the user has
 * confirmed it *and* the encrypted handshake has actually succeeded, so a failed
 * or cancelled attempt leaves no trace.
 */
object TrustResolver {

    fun resolve(
        presentedKey: String,
        host: String,
        name: String?,
        paired: List<PairedDevice>,
    ): TrustDecision {
        paired.firstOrNull { it.publicKey == presentedKey }?.let { return TrustDecision.Connect(it) }

        val sameAddress = paired.filter { it.host.equals(host, ignoreCase = true) }
        val impersonated = sameAddress.firstOrNull { name == null || sameName(it.name, name) }
        if (impersonated != null) return TrustDecision.KeyChanged(impersonated, presentedKey)

        return TrustDecision.Verify(presentedKey)
    }

    private fun sameName(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = true)
}
