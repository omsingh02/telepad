package com.omsingh.telepad

import android.app.Application
import com.omsingh.telepad.service.TelepadConnectionService

/**
 * Application class. Eagerly creates the foreground service notification
 * channel so it exists before any connect() call.
 */
class TelepadApp : Application() {

    companion object {
        @Volatile
        lateinit var instance: TelepadApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        TelepadConnectionService.ensureNotificationChannel(this)
    }
}
