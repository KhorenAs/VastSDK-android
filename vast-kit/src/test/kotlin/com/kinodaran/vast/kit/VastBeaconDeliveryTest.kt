package com.kinodaran.vast.kit

import com.kinodaran.vast.core.VastBeacon
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A tracking request is the only record that an ad was shown. These check that
 * one is not lost when it fails — the case that used to happen most, and
 * silently.
 */
class VastBeaconDeliveryTest {

    private lateinit var directory: File
    private lateinit var queue: VastBeaconQueue
    private lateinit var server: Server

    @BeforeTest
    fun setUp() {
        // A file of this test's own: the shared queue belongs to the app, and a
        // test must not inherit or destroy what the last run left there.
        directory = Files.createTempDirectory("vast-queue").toFile()
        queue = VastBeaconQueue(File(directory, "pending.tsv"))
        server = Server()
    }

    @AfterTest
    fun tearDown() {
        server.close()
        directory.deleteRecursively()
    }

    // MARK: - The queue

    @Test
    fun anUndeliveredBeaconIsKept() = runBlocking {
        queue.enqueue(listOf(url("impression")))
        assertEquals(1, queue.count())
    }

    /** Taken rather than copied. Draining and keeping would send every beacon again on every flush. */
    @Test
    fun drainingEmptiesTheQueue() = runBlocking {
        queue.enqueue(listOf(url("a"), url("b")))

        assertEquals(2, queue.drain().size)
        assertEquals(0, queue.count(), "a drained beacon would be sent twice")
    }

    /** The whole point: a beacon queued by one launch is still there for the next. */
    @Test
    fun theQueueSurvivesTheProcessThatWroteIt() = runBlocking {
        val file = File(directory, "shared.tsv")
        VastBeaconQueue(file).enqueue(listOf(url("impression")))

        // A different instance reading the same file is as close as a test gets to a relaunch.
        assertEquals(listOf(url("impression")), VastBeaconQueue(file).drain())
    }

    @Test
    fun theQueueIsCappedOldestOutFirst() = runBlocking {
        queue.enqueue((0 until VastBeaconQueue.CAPACITY + 20).map { url("beacon$it") })

        val drained = queue.drain()
        assertEquals(VastBeaconQueue.CAPACITY, drained.size)
        assertEquals(url("beacon${VastBeaconQueue.CAPACITY + 19}"), drained.last(), "the newest beacons are the ones worth keeping")
    }

    /** A report about yesterday is worth less to an ad server than a clean count. */
    @Test
    fun aBeaconOlderThanTheMaximumAgeIsDropped() = runBlocking {
        var now = 1_000_000_000L
        val aging = VastBeaconQueue(File(directory, "aging.tsv")) { now }
        aging.enqueue(listOf(url("old")))
        now += VastBeaconQueue.MAX_AGE_MILLIS + 1
        aging.enqueue(listOf(url("new")))

        assertEquals(listOf(url("new")), aging.drain())
    }

    // MARK: - The transport

    @Test
    fun aBeaconThatFailsIsQueuedRatherThanLost() = runBlocking {
        server.status = 503
        VastHttpTransport(queue, maxAttempts = 1).fire(listOf(beacon(server.url("impression"))))

        assertEquals(1, queue.count(), "the impression that paid for the break was dropped")
    }

    @Test
    fun anUnreachableHostIsQueuedToo() = runBlocking {
        val closed = server.url("impression")
        server.close()
        VastHttpTransport(queue, maxAttempts = 1).fire(listOf(beacon(closed)))

        assertEquals(1, queue.count())
    }

    @Test
    fun aBeaconThatLandsIsNotQueued() = runBlocking {
        VastHttpTransport(queue, maxAttempts = 1).fire(listOf(beacon(server.url("impression"))))

        assertEquals(listOf("/impression"), server.requested)
        assertEquals(0, queue.count())
    }

    /** A 4xx is the server's answer about a URI it wrote, not a delivery failure. */
    @Test
    fun aClientErrorIsNotRetriedOrQueued() = runBlocking {
        server.status = 404
        VastHttpTransport(queue, maxAttempts = 2).fire(listOf(beacon(server.url("impression"))))

        assertEquals(listOf("/impression"), server.requested, "asked once, not argued with")
        assertEquals(0, queue.count())
    }

    @Test
    fun aServerErrorIsRetried() = runBlocking {
        server.status = 500
        VastHttpTransport(queue, maxAttempts = 2).fire(listOf(beacon(server.url("impression"))))

        assertEquals(2, server.requested.size)
    }

    /** Retries ride along with the next flush, so they happen when the network is known to be working. */
    @Test
    fun theNextFlushCarriesTheEarlierFailure() = runBlocking {
        server.status = 503
        VastHttpTransport(queue, maxAttempts = 1).fire(listOf(beacon(server.url("impression"))))
        assertEquals(1, queue.count())

        server.status = 200
        server.requested.clear()
        VastHttpTransport(queue, maxAttempts = 1).fire(listOf(beacon(server.url("start"))))

        assertEquals(setOf("/impression", "/start"), server.requested.toSet(), "the earlier failure did not ride along")
        assertEquals(0, queue.count())
    }

    @Test
    fun aMalformedUrlIsDroppedRatherThanCarriedForever() = runBlocking {
        VastHttpTransport(queue, maxAttempts = 1).fire(listOf(beacon("not a url")))
        assertEquals(0, queue.count())
    }

    // MARK: - The loader

    @Test
    fun aLatin1ResponseIsReadRatherThanDiscarded() {
        val latin1 = byteArrayOf('<'.code.toByte(), 'é'.code.toByte(), '>'.code.toByte())
        assertEquals("<é>", VastHttpLoader.decode(latin1))
        assertEquals("<é>", VastHttpLoader.decode("<é>".toByteArray(Charsets.UTF_8)))
    }

    // MARK: - Fixtures

    private fun url(path: String) = "https://ads.test/$path"

    private fun beacon(url: String) = VastBeacon(VastBeacon.Kind.Impression, url, "a")

    /** A real HTTP server on a loopback port, so "the beacon failed" is a setting rather than a wait. */
    private class Server : AutoCloseable {
        @Volatile var status = 200
        val requested: MutableList<String> = Collections.synchronizedList(mutableListOf())
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                requested += exchange.requestURI.path
                exchange.sendResponseHeaders(status, -1)
                exchange.close()
            }
            start()
        }

        fun url(path: String) = "http://127.0.0.1:${server.address.port}/$path"

        override fun close() = server.stop(0)
    }
}
