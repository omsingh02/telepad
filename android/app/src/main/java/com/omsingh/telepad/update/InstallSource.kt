package com.omsingh.telepad.update

import android.content.Context
import android.os.Build

/**
 * Where this copy of the app came from, which decides whether it updates itself. A copy that an app store
 * installed is that store's to update (two updaters fighting over one app helps nobody); a copy installed from a
 * downloaded file, a file manager or `adb` has nobody else looking after it.
 */
sealed interface InstallSource {
    /** Installed from a file: the app can update itself. */
    data object Direct : InstallSource

    /** Installed by a store or an update manager, which is named to the person. */
    data class Store(val name: String) : InstallSource

    companion object {
        /** The stores and update managers that look after the apps they install, by package name. */
        private val STORES = mapOf(
            "com.android.vending" to "Google Play",
            "org.fdroid.fdroid" to "F-Droid",
            "org.fdroid.basic" to "F-Droid",
            "com.looker.droidify" to "Droid-ify",
            "com.machiav3lli.fdroid" to "Neo Store",
            "dev.imranr.obtainium" to "Obtainium",
            "com.aurora.store" to "Aurora Store",
            "com.amazon.venezia" to "Amazon Appstore",
            "com.sec.android.app.samsungapps" to "Galaxy Store",
            "com.huawei.appmarket" to "AppGallery",
        )

        /** The source for an app installed by [installer] (its package name), or by nobody we know. */
        fun of(installer: String?): InstallSource =
            STORES[installer]?.let { Store(it) } ?: Direct

        /** Asks the system who installed this app. */
        fun detect(context: Context): InstallSource {
            val installer = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getInstallerPackageName(context.packageName)
                }
            }.getOrNull()
            return of(installer)
        }
    }
}
