package com.kinodaran.vast.kit

import com.kinodaran.vast.core.VastBeacon
import com.kinodaran.vast.core.VastResourceLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Default transport: a plain `GET` per beacon, concurrently, with one retry —
 * and what fails is kept rather than lost.
 *
 * At-least-once delivery is safe by design: a VAST tracking URI carries its own
 * event identity, so a repeat is de-duplicated by the ad server rather than
 * double-counted. Anything that fails every attempt goes to [VastBeaconQueue],
 * which persists it and hands it back on a later flush, including after the app
 * has been relaunched — rationed, so a tracker that never answers costs one
 * request per beacon every [VastBeaconQueue.RETRY_INTERVAL_MILLIS] at most, not
 * the whole backlog again with every new beacon.
 *
 * Built on `HttpURLConnection` so the SDK brings no HTTP client of its own into
 * a host that has already chosen one; a host that wants its OkHttp stack used
 * supplies its own [VastBeaconTransport].
 *
 * A failure still never interrupts playback. It is simply no longer forgotten.
 */
public class VastHttpTransport(
    /** `null` restores the old behaviour of dropping what fails, which is only reasonable where something else is counting. */
    private val queue: VastBeaconQueue?,
    maxAttempts: Int = 2,
    /** Per request, in milliseconds. A beacon that has not landed in a few seconds is not going to. */
    private val timeoutMillis: Int = REQUEST_TIMEOUT_MILLIS,
) : VastBeaconTransport {

    private val maxAttempts = maxAttempts.coerceAtLeast(1)

    override suspend fun fire(beacons: List<VastBeacon>) {
        if (beacons.isEmpty()) return
        // Earlier failures ride along with current traffic, so a retry costs no
        // timer of its own and happens when the network is known to work — as
        // much as the queue's ration allows, which is one batch per interval for
        // the whole process, not the whole backlog with every beacon.
        val retries = queue?.takeForRetry().orEmpty()
        deliver(retries.map { Outgoing(it.url, it) } + beacons.map { Outgoing(it.url, null) })
    }

    /** Sends the next retry batch, with no new beacon to carry it — at session start, say. */
    public suspend fun flushPending() {
        val retries = queue?.takeForRetry().orEmpty()
        if (retries.isNotEmpty()) deliver(retries.map { Outgoing(it.url, it) })
    }

    /**
     * What fails goes back to the queue: a first send as new, a retry as the
     * entry it was taken as, with one more retry counted — so a beacon that keeps
     * failing still ages out from when it was first recorded, not from its
     * latest failure, and is let go after [VastBeaconQueue.MAX_RETRIES].
     */
    @Suppress("InjectDispatcher") // HttpURLConnection blocks; that belongs on I/O, whatever the caller runs on.
    private suspend fun deliver(outgoing: List<Outgoing>) {
        val undelivered = withContext(Dispatchers.IO) {
            coroutineScope {
                outgoing.map { beacon ->
                    // A first send gets its immediate retry; a queued one has had
                    // that already, and one attempt per batch is what keeps a dead
                    // tracker's backlog from doubling every flush.
                    val attempts = if (beacon.queued == null) maxAttempts else 1
                    async { if (send(beacon.url, attempts)) null else beacon }
                }.awaitAll().filterNotNull()
            }
        }
        if (undelivered.isEmpty()) return
        VastLog.warning("${undelivered.size} beacon(s) undelivered")
        val queue = queue ?: return
        queue.enqueue(undelivered.filter { it.queued == null }.map { it.url })
        queue.requeue(undelivered.mapNotNull { it.queued?.let { entry -> entry.copy(retries = entry.retries + 1) } })
    }

    /** One request to make: `queued` is the entry a retry was taken as, and `null` on a first send. */
    private class Outgoing(val url: String, val queued: VastBeaconQueue.Entry?)

    /** @return whether the beacon landed — or can never land, which is the same for the queue. */
    private fun send(url: String, attempts: Int): Boolean {
        repeat(attempts) {
            val status = try {
                get(url)
            } catch (_: MalformedURLException) {
                // Not a URL anyone could fetch, now or later: queueing it would only
                // carry it from flush to flush until it aged out.
                return true
            } catch (_: IllegalArgumentException) {
                return true
            } catch (_: IOException) {
                return@repeat
            }
            // A 4xx is the server's answer, not a delivery failure: retrying it
            // forever would be the SDK arguing with an ad server about a URI the ad
            // server wrote. A 5xx is the server failing, and worth another try.
            if (status !in 500..599) return true
        }
        return false
    }

    private fun get(url: String): Int {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.useCaches = false
            connection.instanceFollowRedirects = true
            val status = connection.responseCode
            // Drained so the connection can be reused; the body itself means nothing.
            runCatching {
                val body = if (status < HttpURLConnection.HTTP_BAD_REQUEST) connection.inputStream else connection.errorStream
                body?.use { it.readBytes() }
            }
            return status
        } finally {
            connection.disconnect()
        }
    }

    public companion object {
        public const val REQUEST_TIMEOUT_MILLIS: Int = 5_000
    }
}

/** Default loader for tags and Wrapper redirection. */
public class VastHttpLoader : VastResourceLoader {

    @Suppress("InjectDispatcher") // As in VastHttpTransport.
    override suspend fun loadVast(url: String, timeoutSeconds: Double): String = withContext(Dispatchers.IO) {
        val timeout = (timeoutSeconds * 1000).toInt().coerceAtLeast(1)
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = timeout
            connection.readTimeout = timeout
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/xml, text/xml;q=0.9, */*;q=0.8")
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("VAST request answered $status")
            decode(connection.inputStream.use { it.readBytes() })
        } finally {
            connection.disconnect()
        }
    }

    internal companion object {
        /**
         * Ad servers are inconsistent about declaring an encoding; UTF-8 first, then
         * Latin-1, which never fails and is better than discarding the ad.
         */
        fun decode(bytes: ByteArray): String = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charsets.ISO_8859_1)
        }
    }
}
