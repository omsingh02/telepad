package com.omsingh.telepad.update

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.OutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateManagerTest {
    private val base = "https://github.com/omsingh02/telepad/releases/download/v2.0.0-alpha.4"
    private val apkName = "telepad-android-v2.0.0-alpha.4.apk"
    private val body = "an apk, pretend".toByteArray()
    private lateinit var folder: File

    @Before fun setUp() {
        folder = File(System.getProperty("java.io.tmpdir"), "telepad-update-test-${System.nanoTime()}")
        folder.mkdirs()
    }

    @After fun tearDown() {
        folder.deleteRecursively()
    }

    private fun sha(bytes: ByteArray) = Checksums.sha256(bytes.inputStream())

    private fun feed(vararg tags: String): String {
        val entries = tags.joinToString("") { tag ->
            """<entry><link rel="alternate" type="text/html" href="https://github.com/omsingh02/telepad/releases/tag/$tag"/><content type="html">&lt;p&gt;notes&lt;/p&gt;</content></entry>"""
        }
        return """<?xml version="1.0"?><feed xmlns="http://www.w3.org/2005/Atom"><title>Release notes</title>$entries</feed>"""
    }

    /** A network that answers from a table; a value of `null` means the connection failed. */
    private class FakeClient(val answers: MutableMap<String, Any>) : UpdateClient {
        val asked = mutableListOf<String>()
        override fun getText(url: String, limit: Long): String {
            asked += url
            return when (val answer = answers[url] ?: FetchFailure.Status(404)) {
                is FetchFailure -> throw answer
                is ByteArray -> String(answer)
                else -> answer.toString()
            }
        }
        override fun download(url: String, limit: Long, sink: OutputStream, progress: (Long, Long?) -> Unit) {
            asked += url
            when (val answer = answers[url] ?: FetchFailure.Status(404)) {
                is FetchFailure -> throw answer
                is ByteArray -> {
                    if (answer.size > limit) throw FetchFailure.TooLarge()
                    sink.write(answer)
                    progress(answer.size.toLong(), answer.size.toLong())
                }
                else -> error("unexpected $answer")
            }
        }
    }

    private class FakeInstaller(var allowed: Boolean = true, var failWith: IOException? = null) : ApkInstaller {
        val installed = mutableListOf<ByteArray>()
        override fun mayInstall() = allowed
        override fun install(apk: File) {
            failWith?.let { throw it }
            installed += apk.readBytes()
        }
    }

    private class Memory(var value: Long = 0) : LongStore {
        override fun get() = value
        override fun set(value: Long) { this.value = value }
    }

    private fun TestScope.manager(
        client: FakeClient,
        installer: FakeInstaller = FakeInstaller(),
        current: String = "2.0.0-alpha.3",
        source: InstallSource = InstallSource.Direct,
        installsItself: Boolean = true,
        memory: Memory = Memory(),
        now: Long = 1_000_000_000L,
    ): UpdateManager {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return UpdateManager(
            current = Version.parse(current)!!,
            client = client,
            installer = installer,
            source = source,
            folder = File(folder, "updates"),
            scope = backgroundScope,
            io = dispatcher,
            clock = { now },
            lastChecked = memory,
            installsItself = installsItself,
        )
    }

    private fun network(file: ByteArray = body, sums: String? = "${sha(body)}  $apkName\n") = FakeClient(
        mutableMapOf<String, Any>(
            Releases.LIST_URL to feed("v2.0.0-alpha.4"),
            "$base/$apkName" to file,
        ).apply { if (sums != null) put("$base/SHA256SUMS", sums) },
    )

    @Test fun `it starts knowing nothing and finds the newer release with its file`() = runTest(UnconfinedTestDispatcher()) {
        val m = manager(network())
        assertEquals(UpdateState.Unknown, m.state.value)
        m.check()
        val found = m.state.value as UpdateState.Available
        assertEquals("v2.0.0-alpha.4", found.release.tag)
        assertEquals(apkName, found.apk?.name)
        assertNotNull(found.sums)
    }

    @Test fun `being up to date and being offline are different answers`() = runTest(UnconfinedTestDispatcher()) {
        val same = manager(network(), current = "2.0.0-alpha.4")
        same.check()
        assertEquals(UpdateState.UpToDate, same.state.value)

        val offline = manager(FakeClient(mutableMapOf(Releases.LIST_URL to FetchFailure.Unreachable())))
        offline.check()
        assertEquals(UpdateState.CheckFailed(Problem.Offline), offline.state.value)

        val limited = manager(FakeClient(mutableMapOf(Releases.LIST_URL to FetchFailure.RateLimited())))
        limited.check()
        assertEquals(UpdateState.CheckFailed(Problem.RateLimited), limited.state.value)

        val html = manager(FakeClient(mutableMapOf(Releases.LIST_URL to "<html>nope</html>")))
        html.check()
        assertTrue(html.state.value is UpdateState.CheckFailed)
    }

    @Test fun `installing downloads checks and hands the file to the system installer`() = runTest(UnconfinedTestDispatcher()) {
        val installer = FakeInstaller()
        val m = manager(network(), installer)
        m.check()
        m.install()
        assertTrue(m.state.value.toString(), m.state.value is UpdateState.Installing)
        assertEquals(1, installer.installed.size)
        assertEquals(body.toList(), installer.installed.single().toList())
    }

    @Test fun `a download that does not match its checksum is never installed`() = runTest(UnconfinedTestDispatcher()) {
        val installer = FakeInstaller()
        val m = manager(network(file = "an apk, PRETEND".toByteArray()), installer)
        m.check()
        m.install()
        val failed = m.state.value as UpdateState.InstallFailed
        assertEquals(Problem.ChecksumMismatch, failed.problem)
        assertTrue(installer.installed.isEmpty())
        assertFalse("the bad file is gone", File(folder, "updates/$apkName").exists())
    }

    @Test fun `if the checksum list stops naming the file by the time of the install nothing is downloaded`() = runTest(UnconfinedTestDispatcher()) {
        val client = network()
        val m = manager(client)
        m.check()
        client.answers["$base/SHA256SUMS"] = "${sha(body)}  something-else.apk\n"
        m.install()
        assertEquals(Problem.ChecksumMismatch, (m.state.value as UpdateState.InstallFailed).problem)
        assertFalse(client.asked.contains("$base/$apkName"))
    }

    @Test fun `a release whose checksums do not name the file is not installed from`() = runTest(UnconfinedTestDispatcher()) {
        val installer = FakeInstaller()
        val m = manager(network(sums = "${sha(body)}  telepad-android-v2.0.0-alpha.4-other.apk\n"), installer)
        m.check()
        assertEquals(null, (m.state.value as UpdateState.Available).apk)
        m.install()
        assertEquals(Problem.NoFile, (m.state.value as UpdateState.InstallFailed).problem)
        assertTrue(installer.installed.isEmpty())
    }

    @Test fun `a release with no checksum list at all is told, not installed from`() = runTest(UnconfinedTestDispatcher()) {
        val installer = FakeInstaller()
        val m = manager(network(sums = null), installer)
        m.check()
        val found = m.state.value as UpdateState.Available
        assertEquals(null, found.apk)
        m.install()
        assertEquals(Problem.NoFile, (m.state.value as UpdateState.InstallFailed).problem)
        assertTrue(installer.installed.isEmpty())
    }

    @Test fun `the person is asked to allow installs first and then it goes on`() = runTest(UnconfinedTestDispatcher()) {
        val installer = FakeInstaller(allowed = false)
        val m = manager(network(), installer)
        m.check()
        m.install()
        assertTrue(m.state.value is UpdateState.NeedsPermission)
        assertTrue(installer.installed.isEmpty())

        installer.allowed = true
        m.install()
        assertTrue(m.state.value is UpdateState.Installing)
        assertEquals(1, installer.installed.size)
    }

    @Test fun `a failure of the system installer is reported and can be tried again`() = runTest(UnconfinedTestDispatcher()) {
        val installer = FakeInstaller(failWith = IOException("no space left"))
        val m = manager(network(), installer)
        m.check()
        m.install()
        assertEquals(Problem.Other("no space left"), (m.state.value as UpdateState.InstallFailed).problem)
        installer.failWith = null
        m.install()
        assertTrue(m.state.value is UpdateState.Installing)
    }

    @Test fun `a copy that a store installed only looks`() = runTest(UnconfinedTestDispatcher()) {
        val installer = FakeInstaller()
        val m = manager(network(), installer, source = InstallSource.Store("F-Droid"))
        assertFalse(m.canInstall)
        m.check()
        assertTrue(m.state.value is UpdateState.Available)
        m.install()
        assertTrue("still just available", m.state.value is UpdateState.Available)
        assertTrue(installer.installed.isEmpty())
    }

    @Test fun `a debug build only looks`() = runTest(UnconfinedTestDispatcher()) {
        val m = manager(network(), installsItself = false)
        assertFalse(m.canInstall)
        m.check()
        m.install()
        assertTrue(m.state.value is UpdateState.Available)
    }

    @Test fun `it looks by itself only when it has not looked in the last day`() = runTest(UnconfinedTestDispatcher()) {
        val client = network()
        val memory = Memory(value = 1_000_000_000L - 60_000)   // a minute ago
        val m = manager(client, memory = memory)
        m.checkIfDue()
        assertEquals(UpdateState.Unknown, m.state.value)
        assertTrue(client.asked.isEmpty())

        memory.value = 1_000_000_000L - 25 * 60 * 60 * 1000     // 25 hours ago
        m.checkIfDue()
        assertTrue(m.state.value is UpdateState.Available)
        assertEquals("and it remembers when", 1_000_000_000L, memory.value)
    }

    @Test fun `a second look while an install is under way does not start over`() = runTest(UnconfinedTestDispatcher()) {
        val client = network()
        val m = manager(client)
        m.check()
        m.install()
        assertTrue(m.state.value is UpdateState.Installing)
        val lists = client.asked.count { it == Releases.LIST_URL }

        m.check()
        assertEquals("no new list was fetched", lists, client.asked.count { it == Releases.LIST_URL })
        assertTrue(m.state.value is UpdateState.Installing)
    }
}
