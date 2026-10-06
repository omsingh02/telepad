package com.omsingh.telepad.core.trust

/**
 * A [TrustStore] that is created the first time it is used.
 *
 * The real store is backed by the Android Keystore, and asking the Keystore for the first time
 * can take a noticeable moment. Everything that touches the store does so off the main thread,
 * but creating it up front (as the object that owns it is created, on the main thread) would
 * put that moment on the main thread anyway. This defers it to the first real use.
 */
class LazyTrustStore(create: () -> TrustStore) : TrustStore {
    private val store: TrustStore by lazy(create)

    override fun all() = store.all()
    override fun put(device: PairedDevice) = store.put(device)
    override fun remove(publicKey: String) = store.remove(publicKey)
    override fun clear() = store.clear()
    override fun localStaticPrivateKey() = store.localStaticPrivateKey()
    override val needsMigration: Boolean get() = store.needsMigration
    override fun migrateLegacy(favorites: List<LegacyTrust.Favorite>, nowMs: Long) = store.migrateLegacy(favorites, nowMs)
    override fun resetLocalIdentity() = store.resetLocalIdentity()
}
