package com.omsingh.telepad.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.io.IOException

/**
 * Puts a downloaded, checked APK in place of this app. Android itself checks that the new file is signed with
 * the same key as the installed app, so a file from anywhere else is refused whatever it claims to be.
 */
interface ApkInstaller {
    /** Whether the person has let this app install other apps' files (Android asks for that per app). */
    fun mayInstall(): Boolean

    /** Hands [apk] to the system installer. The result arrives through [InstallResults]. */
    @Throws(IOException::class)
    fun install(apk: File)
}

/** What the system installer said about the update. */
sealed interface InstallResult {
    data object Success : InstallResult
    data class Failure(val message: String?) : InstallResult
}

/** Where [InstallResultReceiver] reports to; the update screen listens. */
object InstallResults {
    private val results = MutableSharedFlow<InstallResult>(extraBufferCapacity = 4)
    val flow: SharedFlow<InstallResult> = results.asSharedFlow()
    internal fun report(result: InstallResult) {
        results.tryEmit(result)
    }
}

/** The installer built into Android (`PackageInstaller`). */
class SystemApkInstaller(private val context: Context) : ApkInstaller {

    override fun mayInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    override fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            // Android 12 and later can update an app without a question when the same installer made the
            // last install; otherwise it asks, and the receiver shows what it asks.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("telepad.apk", 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val intent = Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName)
                // The system adds to the intent what it has to say, so it has to be a mutable one.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val result = PendingIntent.getBroadcast(context, id, intent, flags)
                session.commit(result.intentSender)
            }
        } catch (e: IOException) {
            runCatching { installer.abandonSession(id) }
            throw e
        } catch (e: RuntimeException) {
            runCatching { installer.abandonSession(id) }
            throw IOException(e.message ?: "the system installer refused the file", e)
        }
    }
}

/** Receives the system installer's answer: shows its question if it has one, and reports how it ended. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val question = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (question != null) {
                    question.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(question) }
                        .onFailure { InstallResults.report(InstallResult.Failure(it.message)) }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> InstallResults.report(InstallResult.Success)
            else -> InstallResults.report(
                InstallResult.Failure(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)),
            )
        }
    }
}
