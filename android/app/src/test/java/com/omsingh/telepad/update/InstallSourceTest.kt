package com.omsingh.telepad.update

import org.junit.Assert.assertEquals
import org.junit.Test

class InstallSourceTest {
    @Test fun `a copy installed from a file or by nobody we know updates itself`() {
        assertEquals(InstallSource.Direct, InstallSource.of(null))
        assertEquals(InstallSource.Direct, InstallSource.of("com.google.android.packageinstaller"))
        assertEquals(InstallSource.Direct, InstallSource.of("com.android.shell"))
        assertEquals(InstallSource.Direct, InstallSource.of("com.example.somefilemanager"))
    }

    @Test fun `a copy that a store or an update manager installed is theirs to update`() {
        assertEquals(InstallSource.Store("Google Play"), InstallSource.of("com.android.vending"))
        assertEquals(InstallSource.Store("F-Droid"), InstallSource.of("org.fdroid.fdroid"))
        assertEquals(InstallSource.Store("Obtainium"), InstallSource.of("dev.imranr.obtainium"))
        assertEquals(InstallSource.Store("Droid-ify"), InstallSource.of("com.looker.droidify"))
    }
}
