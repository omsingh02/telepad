package com.omsingh.telepad.update

import android.app.Application
import android.content.Context
import com.omsingh.telepad.BuildConfig
import com.omsingh.telepad.update.Releases.Asset
import com.omsingh.telepad.update.Releases.Release
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/** Why looking for, fetching or installing an update did not work, as the screen puts it in words. */
sealed interface Problem {
    /** No connection to GitHub. */
    data object Offline : Problem

    /** GitHub is limiting how often it answers. */
    data object RateLimited : Problem

    /** The release has no file for this app. */
    data object NoFile : Problem

    /** The download did not match its published checksum, and was thrown away. */
    data object ChecksumMismatch : Problem

    /** Anything else, with what the system said. */
    data class Other(val message: String?) : Problem
}

/** What is known about updates, and what is going on. */
sealed interface UpdateState {
    /** Nobody has looked yet. */
    data object Unknown : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState

    /** There is a newer release. [apk] and [sums] are the file and the checksums that vouch for it, if it has them. */
    data class Available(val release: Release, val apk: Asset?, val sums: Asset?) : UpdateState
    data class Downloading(val release: Release, val done: Long, val total: Long?) : UpdateState

    /** The person has to allow this app to install other apps' files (Android asks once per app) before it can go on. */
    data class NeedsPermission(val release: Release, val apk: Asset?, val sums: Asset?) : UpdateState

    /** The file is with the system installer, which may be asking the person a question. */
    data class Installing(val release: Release) : UpdateState
    data class CheckFailed(val problem: Problem) : UpdateState
    data class InstallFailed(val release: Release, val apk: Asset?, val sums: Asset?, val problem: Problem) : UpdateState
}

/** A number that outlives the app, such as when it last looked. */
interface LongStore {
    fun get(): Long
    fun set(value: Long)
}

/**
 * Looks for a newer release, and installs it when the person asks. It talks to GitHub only in [check] and
 * [install], and installs nothing whose SHA-256 differs from the one published with the release.
 *
 * A copy installed by a store, or a debug build, only ever looks: the store updates its own apps.
 */
class UpdateManager(
    private val current: Version,
    private val client: UpdateClient,
    private val installer: ApkInstaller,
    val source: InstallSource,
    private val folder: File,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long,
    private val lastChecked: LongStore,
    private val installsItself: Boolean = true,
) {
    private val mutableState = MutableStateFlow<UpdateState>(UpdateState.Unknown)
    val state: StateFlow<UpdateState> = mutableState.asStateFlow()

    /** Whether this copy can install an update itself, and so whether the screen offers the button. */
    val canInstall: Boolean get() = installsItself && source is InstallSource.Direct

    init {
        // The system installer's answer, if it ends in a failure that this app is around to report.
        scope.launch {
            InstallResults.flow.collect { result ->
                val now = mutableState.value
                if (now is UpdateState.Installing && result is InstallResult.Failure) {
                    mutableState.value = UpdateState.InstallFailed(now.release, null, null, Problem.Other(result.message))
                }
            }
        }
    }

    private fun busy(state: UpdateState) =
        state is UpdateState.Checking || state is UpdateState.Downloading || state is UpdateState.Installing

    /** Looks for a newer release now, if nothing else is going on. */
    fun check() {
        val before = mutableState.value
        if (busy(before) || !mutableState.compareAndSet(before, UpdateState.Checking)) return
        scope.launch(io) { checkNow() }
    }

    /** Looks, if it has not looked in the last day. */
    fun checkIfDue() {
        if (clock() - lastChecked.get() >= DAY_MS) check()
    }

    /** Asks GitHub and waits for the answer. */
    internal suspend fun checkNow() {
        mutableState.value = UpdateState.Checking
        lastChecked.set(clock())
        mutableState.value = try {
            val list = Releases.parse(client.getText(Releases.LIST_URL, LIST_LIMIT))
            val release = list?.let { Releases.newestFor(it, current) }
            when {
                list == null -> UpdateState.CheckFailed(Problem.Other(null))
                release == null -> UpdateState.UpToDate
                else -> available(release)
            }
        } catch (failure: FetchFailure) {
            UpdateState.CheckFailed(failure.toProblem())
        }
    }

    /**
     * The release with its file, if the release's own checksum list names one: the list is what vouches for a file,
     * so a file it does not name is not one to install.
     */
    private fun available(release: Release): UpdateState.Available {
        val sums = release.sums
        val listed = try {
            Checksums.parse(client.getText(sums.url, SUMS_LIMIT))
        } catch (failure: FetchFailure.Status) {
            // A release whose files are not up yet, or that has no checksums: nothing to install from.
            if (failure.code == 404) null else throw failure
        }
        val name = Releases.apkName(release.version)
        val apk = release.asset(name)?.takeIf { listed?.get(name) != null }
        return UpdateState.Available(release, apk, sums.takeIf { apk != null })
    }

    /** Downloads, checks and installs the update that was found. */
    fun install() {
        val before = mutableState.value
        val (release, apk, sums) = when (before) {
            is UpdateState.Available -> Triple(before.release, before.apk, before.sums)
            is UpdateState.NeedsPermission -> Triple(before.release, before.apk, before.sums)
            is UpdateState.InstallFailed -> Triple(before.release, before.apk, before.sums)
            else -> return
        }
        if (!canInstall) return
        if (apk == null || sums == null) {
            mutableState.value = UpdateState.InstallFailed(release, apk, sums, Problem.NoFile)
            return
        }
        if (!installer.mayInstall()) {
            mutableState.value = UpdateState.NeedsPermission(release, apk, sums)
            return
        }
        if (!mutableState.compareAndSet(before, UpdateState.Downloading(release, 0, apk.size.takeIf { it > 0 }))) return
        scope.launch(io) { installNow(release, apk, sums) }
    }

    internal suspend fun installNow(release: Release, apk: Asset, sums: Asset) {
        fun fail(problem: Problem) {
            mutableState.value = UpdateState.InstallFailed(release, apk, sums, problem)
        }
        try {
            val listed = Checksums.parse(client.getText(sums.url, SUMS_LIMIT))
            val expected = listed[apk.name] ?: return fail(Problem.ChecksumMismatch)

            folder.deleteRecursively()
            if (!folder.mkdirs() && !folder.isDirectory) return fail(Problem.Other("cannot make $folder"))
            val file = File(folder, apk.name)
            val limit = if (apk.size > 0) apk.size.coerceAtMost(FILE_LIMIT) else FILE_LIMIT
            file.outputStream().use { out ->
                client.download(apk.url, limit, out) { done, total ->
                    mutableState.value = UpdateState.Downloading(release, done, total)
                }
            }
            if (file.inputStream().use(Checksums::sha256) != expected) {
                file.delete()
                return fail(Problem.ChecksumMismatch)
            }
            mutableState.value = UpdateState.Installing(release)
            installer.install(file)
        } catch (failure: FetchFailure) {
            fail(failure.toProblem())
        } catch (e: IOException) {
            fail(Problem.Other(e.message))
        }
    }

    private fun FetchFailure.toProblem(): Problem = when (this) {
        is FetchFailure.Unreachable -> Problem.Offline
        is FetchFailure.RateLimited -> Problem.RateLimited
        is FetchFailure.Status -> Problem.Other("GitHub answered $code")
        is FetchFailure.TooLarge -> Problem.Other("the file is larger than expected")
        is FetchFailure.Interrupted -> Problem.Other(message)
    }

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val LIST_LIMIT = 4L * 1024 * 1024
        private const val SUMS_LIMIT = 256L * 1024
        private const val FILE_LIMIT = 200L * 1024 * 1024

        @Volatile private var instance: UpdateManager? = null

        /** The one manager for the app, made on first use with the real network, installer and storage. */
        fun getInstance(application: Application): UpdateManager = instance ?: synchronized(this) {
            instance ?: create(application).also { instance = it }
        }

        private fun create(application: Application): UpdateManager {
            val prefs = application.getSharedPreferences("telepad_updates", Context.MODE_PRIVATE)
            return UpdateManager(
                current = Version.parse(BuildConfig.VERSION_NAME.removeSuffix("-debug")) ?: Version.parse("0.0.0")!!,
                client = HttpsUpdateClient("Telepad-Android/${BuildConfig.VERSION_NAME}"),
                installer = SystemApkInstaller(application),
                source = InstallSource.detect(application),
                folder = File(application.cacheDir, "updates"),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                io = Dispatchers.IO,
                clock = System::currentTimeMillis,
                lastChecked = object : LongStore {
                    override fun get() = prefs.getLong("last_checked", 0L)
                    override fun set(value: Long) = prefs.edit().putLong("last_checked", value).apply()
                },
                // A debug build is not signed with the release key, so Android would refuse the update anyway.
                installsItself = !BuildConfig.DEBUG,
            )
        }
    }
}
