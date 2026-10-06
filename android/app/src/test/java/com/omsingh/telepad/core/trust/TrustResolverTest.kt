package com.omsingh.telepad.core.trust

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustResolverTest {

    private val desk = PairedDevice(publicKey = "KEY-DESK", name = "Desk PC", host = "192.168.1.20", port = 5000)
    private val laptop = PairedDevice(publicKey = "KEY-LAPTOP", name = "Laptop", host = "192.168.1.31", port = 5000)
    private val paired = listOf(desk, laptop)

    @Test
    fun `a paired key connects without asking`() {
        val decision = TrustResolver.resolve("KEY-DESK", "192.168.1.20", "Desk PC", paired)
        assertEquals(TrustDecision.Connect(desk), decision)
    }

    @Test
    fun `a paired PC that moved to a new address is still trusted`() {
        val decision = TrustResolver.resolve("KEY-DESK", "192.168.1.77", "Desk PC", paired)
        assertEquals(TrustDecision.Connect(desk), decision)
    }

    @Test
    fun `a paired PC is trusted even if it was renamed`() {
        val decision = TrustResolver.resolve("KEY-DESK", "192.168.1.20", "Office", paired)
        assertEquals(TrustDecision.Connect(desk), decision)
    }

    @Test
    fun `an unknown key at an unknown address is a new PC to verify`() {
        val decision = TrustResolver.resolve("KEY-NEW", "192.168.1.99", "Guest PC", paired)
        assertEquals(TrustDecision.Verify("KEY-NEW"), decision)
    }

    @Test
    fun `with nothing paired every PC is new`() {
        assertEquals(TrustDecision.Verify("K"), TrustResolver.resolve("K", "10.0.0.2", "PC", emptyList()))
    }

    @Test
    fun `a different key at the same address and name warns that the identity changed`() {
        val decision = TrustResolver.resolve("KEY-OTHER", "192.168.1.20", "Desk PC", paired)
        assertEquals(TrustDecision.KeyChanged(desk, "KEY-OTHER"), decision)
    }

    @Test
    fun `name and address comparisons ignore case and padding`() {
        val decision = TrustResolver.resolve("KEY-OTHER", "192.168.1.20", "  desk pc ", paired)
        assertTrue(decision is TrustDecision.KeyChanged)

        val byName = listOf(desk.copy(host = "Desk.local"))
        val hostCase = TrustResolver.resolve("KEY-OTHER", "DESK.LOCAL", "Desk PC", byName)
        assertTrue(hostCase is TrustDecision.KeyChanged)
    }

    @Test
    fun `a different key at a known address under another name is just a new PC`() {
        // The old PC is off and the router gave its address to a visitor's laptop.
        val decision = TrustResolver.resolve("KEY-VISITOR", "192.168.1.20", "Visitor Laptop", paired)
        assertEquals(TrustDecision.Verify("KEY-VISITOR"), decision)
    }

    @Test
    fun `an address entered by hand has no name so any key change at a known address warns`() {
        val decision = TrustResolver.resolve("KEY-OTHER", "192.168.1.20", null, paired)
        assertEquals(TrustDecision.KeyChanged(desk, "KEY-OTHER"), decision)
    }

    @Test
    fun `the paired key wins over an address clash`() {
        // Laptop's key presented from the Desk's old address: it is the laptop, which moved.
        val decision = TrustResolver.resolve("KEY-LAPTOP", "192.168.1.20", "Laptop", paired)
        assertEquals(TrustDecision.Connect(laptop), decision)
    }

    @Test
    fun `the first matching device is reported when several share an address`() {
        val a = desk.copy(publicKey = "KEY-A", name = "A")
        val b = desk.copy(publicKey = "KEY-B", name = "B")
        val decision = TrustResolver.resolve("KEY-X", "192.168.1.20", "B", listOf(a, b))
        assertEquals(TrustDecision.KeyChanged(b, "KEY-X"), decision)
    }

    // ── Store ────────────────────────────────────────────────────────────

    @Test
    fun `putting a device with an existing key replaces it`() {
        val store = InMemoryPairedDeviceStore(listOf(desk))
        store.put(desk.copy(host = "192.168.1.55", lastConnectedMs = 42))
        assertEquals(1, store.all().size)
        assertEquals("192.168.1.55", store.all().single().host)
    }

    @Test
    fun `devices can be removed and cleared`() {
        val store = InMemoryPairedDeviceStore(paired)
        store.remove("KEY-DESK")
        assertEquals(listOf(laptop), store.all())
        store.clear()
        assertTrue(store.all().isEmpty())
    }

    // ── Migration from the address-keyed format ──────────────────────────

    @Test
    fun `old trust entries become paired devices using the favourites for names`() {
        val migrated = LegacyTrust.migrate(
            trustedByHost = mapOf("192.168.1.20" to "KEY-DESK", "192.168.1.31" to "KEY-LAPTOP"),
            favorites = listOf(LegacyTrust.Favorite("Desk PC", "192.168.1.20", 5001, lastConnectedMs = 900)),
            nowMs = 1_000,
        )
        assertEquals(2, migrated.size)
        val deskMigrated = migrated.first { it.publicKey == "KEY-DESK" }
        assertEquals("Desk PC", deskMigrated.name)
        assertEquals(5001, deskMigrated.port)
        assertEquals(900, deskMigrated.lastConnectedMs)
        val laptopMigrated = migrated.first { it.publicKey == "KEY-LAPTOP" }
        assertEquals("a PC with no favourite is named by its address", "192.168.1.31", laptopMigrated.name)
        assertEquals(5000, laptopMigrated.port)
        assertEquals(1_000, laptopMigrated.pairedAtMs)
    }

    @Test
    fun `one key trusted under two addresses migrates to one device`() {
        val migrated = LegacyTrust.migrate(
            trustedByHost = linkedMapOf("10.0.0.5" to "KEY", "10.0.0.6" to "KEY"),
            favorites = emptyList(),
        )
        assertEquals(1, migrated.size)
    }

    @Test
    fun `blank keys are dropped and favourites without trust are not invented into devices`() {
        val migrated = LegacyTrust.migrate(
            trustedByHost = mapOf("10.0.0.5" to ""),
            favorites = listOf(LegacyTrust.Favorite("Ghost", "10.0.0.9", 5000, 1)),
        )
        assertTrue(migrated.isEmpty())
    }

    @Test
    fun `host matching for favourites ignores case`() {
        val migrated = LegacyTrust.migrate(
            trustedByHost = mapOf("desk.local" to "K"),
            favorites = listOf(LegacyTrust.Favorite("Desk", "Desk.Local", 5000, 5)),
        )
        assertEquals("Desk", migrated.single().name)
    }
}
