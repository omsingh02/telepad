package com.omsingh.telepad.update

import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Why something could not be fetched, in a form the screen turns into words. */
sealed class FetchFailure(message: String) : Exception(message) {
    /** There is no connection to GitHub (no network, or it timed out). */
    class Unreachable : FetchFailure("unreachable")

    /** GitHub is limiting how often it will answer. */
    class RateLimited : FetchFailure("rate limited")

    /** GitHub answered with something other than the file. */
    class Status(val code: Int) : FetchFailure("status $code")

    /** The file is larger than any Telepad download should be. */
    class TooLarge : FetchFailure("too large")

    /** The connection broke, or the address was not one Telepad fetches from. */
    class Interrupted(why: String) : FetchFailure(why)
}

/** What the updater needs from the network. Real in the app ([HttpsUpdateClient]); a script in the tests. */
interface UpdateClient {
    /** The text at [url], which is at most [limit] bytes long. */
    @Throws(FetchFailure::class)
    fun getText(url: String, limit: Long): String

    /**
     * Writes the file at [url] to [sink], reporting `(done, total)` as it comes; `total` is `null` when the
     * server did not say. The file is at most [limit] bytes long.
     */
    @Throws(FetchFailure::class)
    fun download(url: String, limit: Long, sink: OutputStream, progress: (Long, Long?) -> Unit)
}

/**
 * GitHub, over HTTPS, and nothing else: an address that is not this project's releases is refused before any
 * connection is made, and a redirect has to stay on HTTPS.
 */
class HttpsUpdateClient(private val userAgent: String) : UpdateClient {

    override fun getText(url: String, limit: Long): String {
        val out = java.io.ByteArrayOutputStream()
        download(url, limit, out) { _, _ -> }
        return out.toString(Charsets.UTF_8.name())
    }

    override fun download(url: String, limit: Long, sink: OutputStream, progress: (Long, Long?) -> Unit) {
        if (!Releases.owns(url)) throw FetchFailure.Interrupted("not an address Telepad fetches from: $url")
        val connection = open(url)
        try {
            when (val code = connection.responseCode) {
                200 -> Unit
                403, 429 -> throw FetchFailure.RateLimited()
                else -> throw FetchFailure.Status(code)
            }
            val total = connection.contentLengthLong.takeIf { it >= 0 }
            if (total != null && total > limit) throw FetchFailure.TooLarge()
            copy(connection.inputStream, sink, limit, total, progress)
        } catch (failure: FetchFailure) {
            throw failure
        } catch (e: FileNotFoundException) {
            throw FetchFailure.Status(404)
        } catch (e: UnknownHostException) {
            throw FetchFailure.Unreachable()
        } catch (e: SocketTimeoutException) {
            throw FetchFailure.Unreachable()
        } catch (e: SSLException) {
            throw FetchFailure.Interrupted("the connection to GitHub could not be made secure")
        } catch (e: IOException) {
            throw if (e.message?.contains("Unable to resolve host") == true || e.message?.contains("ENETUNREACH") == true) {
                FetchFailure.Unreachable()
            } else {
                FetchFailure.Interrupted(e.message ?: "the connection broke")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        // GitHub sends release files to another of its hosts; both ends have to be HTTPS.
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", userAgent)
        connection.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream")
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        return connection
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000

        /** Copies [input] to [sink], stopping with [FetchFailure.TooLarge] at more than [limit] bytes. */
        internal fun copy(
            input: InputStream,
            sink: OutputStream,
            limit: Long,
            total: Long?,
            progress: (Long, Long?) -> Unit,
        ) {
            val buffer = ByteArray(64 * 1024)
            var done = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                done += n
                if (done > limit) throw FetchFailure.TooLarge()
                sink.write(buffer, 0, n)
                progress(done, total)
            }
            if (total != null && done != total) {
                throw FetchFailure.Interrupted("the connection closed before the whole file arrived")
            }
        }
    }
}
