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

    /** A file written before retries were counted has two fields a line; it has to read the same. */
    @Test
    fun aRetryCountIsAThirdFieldOnTheLineAndBothShapesReadBack() = runBlocking {
        val recorded = 1_700_000_000_000L
        val file = File(directory, "format.tsv")
        val writer = VastBeaconQueue(file) { recorded }
        writer.requeue(listOf(VastBeaconQueue.Entry(recorded, url("a"), retries = 2)))
        writer.enqueue(listOf(url("b")))

        assertEquals("$recorded\t${url("a")}\t2\n$recorded\t${url("b")}\n", file.readText())
        assertEquals(
            listOf(VastBeaconQueue.Entry(recorded, url("a"), retries = 2), VastBeaconQueue.Entry(recorded, url("b"))),
            VastBeaconQueue(file) { recorded }.drainEntries(),
        )
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

    /**
     * A retry that failed again used to be queued as new, so a beacon to a
     * tracker that never answers was never older than its latest failure: it
     * stayed for as long as the app kept sending beacons, with whatever consent
     * string its URL carried.
     */
    @Test
    fun aBeaconThatKeepsFailingAgesOutFromWhenItWasFirstRecorded() = runBlocking {
        var now = 1_000_000_000L
        val aging = VastBeaconQueue(File(directory, "aging.tsv")) { now }
        server.failing += "/blocked"
        val transport = VastHttpTransport(aging, maxAttempts = 1)

        transport.fire(listOf(beacon(server.url("blocked"))))
        now += VastBeaconQueue.MAX_AGE_MILLIS - 60_000
        transport.fire(listOf(beacon(server.url("start"))))
        now += 120_000

        assertEquals(2, server.requested.count { it == "/blocked" }, "the failure was not retried at all")
        assertEquals(emptyList(), aging.drain(), "a URL that may carry a consent string outlived MAX_AGE_MILLIS")
    }

    @Test
    fun aMalformedUrlIsDroppedRatherThanCarriedForever() = runBlocking {
        VastHttpTransport(queue, maxAttempts = 1).fire(listOf(beacon("not a url")))
        assertEquals(0, queue.count())
    }

    // MARK: - The retry ration

    /**
     * Found by hand in a browser, in the web port, and the same here: with a
     * tracker that never answers, every new beacon sent the whole backlog again,
     * twice, so one ad produced dozens of requests per tracker. An ad blocker or
     * a DNS filter makes a tracker like that of every tracker it knows.
     */
    @Test
    fun theBacklogIsResentAtMostOncePerIntervalHoweverManyBeaconsFire() = runBlocking {
        var now = 1_000_000_000L
        val rationed = VastBeaconQueue(File(directory, "rationed.tsv")) { now }
        server.status = 503
        val transport = VastHttpTransport(rationed, maxAttempts = 2)

        val events = listOf("impression", "start", "firstQuartile", "midpoint", "thirdQuartile", "complete")
        for (event in events) {
            transport.fire(listOf(beacon(server.url(event))))
            now += 1_000
        }

        assertEquals(3, server.requested.count { it == "/impression" }, "its own two attempts, then one retry within the interval")
        assertEquals(13, server.requested.size, "six beacons, two attempts each, and one retry batch")
        assertEquals(6, rationed.count())
    }

    @Test
    fun aQueuedBeaconGetsOneAttemptPerBatchWhereAFirstSendGetsTwo() = runBlocking {
        server.status = 503
        queue.enqueue(listOf(server.url("old")))

        VastHttpTransport(queue, maxAttempts = 2).fire(listOf(beacon(server.url("new"))))

        assertEquals(listOf("/new", "/new", "/old"), server.requested.sorted())
    }

    @Test
    fun aRetryWaitsForTheIntervalToPass() = runBlocking {
        var now = 1_000_000_000L
        val rationed = VastBeaconQueue(File(directory, "rationed.tsv")) { now }
        server.status = 503
        rationed.enqueue(listOf(server.url("impression")))
        val transport = VastHttpTransport(rationed, maxAttempts = 2)

        transport.flushPending()
        transport.flushPending()
        assertEquals(1, server.requested.size, "once per interval")

        now += VastBeaconQueue.RETRY_INTERVAL_MILLIS
        transport.flushPending()
        assertEquals(2, server.requested.size)
    }

    /** The ration is measured on the wall clock, which can be set back; that must not stop retries until it catches up. */
    @Test
    fun aClockSetBackDoesNotHoldRetriesBack() = runBlocking {
        var now = 1_000_000_000L
        val rationed = VastBeaconQueue(File(directory, "rationed.tsv")) { now }
        server.status = 503
        rationed.enqueue(listOf(server.url("impression")))
        val transport = VastHttpTransport(rationed, maxAttempts = 2)

        transport.flushPending()
        now -= 60 * 60 * 1000L
        transport.flushPending()

        assertEquals(2, server.requested.size)
    }

    @Test
    fun aRetryBatchIsCappedOldestFirstAndTheRestStaysQueued() = runBlocking {
        var now = 1_000_000_000L
        val rationed = VastBeaconQueue(File(directory, "rationed.tsv")) { now }
        repeat(VastBeaconQueue.RETRY_BATCH + 10) {
            rationed.enqueue(listOf(server.url("b$it")))
            now += 1
        }

        VastHttpTransport(rationed, maxAttempts = 1).flushPending()

        assertEquals((0 until VastBeaconQueue.RETRY_BATCH).map { "/b$it" }.toSet(), server.requested.toSet())
        assertEquals(VastBeaconQueue.RETRY_BATCH, server.requested.size)
        assertEquals(10, rationed.count())
    }

    @Test
    fun aBeaconIsLetGoAfterMaxRetriesFailedBatchesWellBeforeItWouldAgeOut() = runBlocking {
        var now = 1_000_000_000L
        val rationed = VastBeaconQueue(File(directory, "rationed.tsv")) { now }
        server.status = 503
        rationed.enqueue(listOf(server.url("blocked")))
        val transport = VastHttpTransport(rationed, maxAttempts = 2)

        repeat(VastBeaconQueue.MAX_RETRIES + 2) {
            transport.flushPending()
            now += VastBeaconQueue.RETRY_INTERVAL_MILLIS
        }

        assertEquals(VastBeaconQueue.MAX_RETRIES, server.requested.size)
        assertEquals(0, rationed.count())
    }

    /** A host builds a session per break, and the session a transport: the ration has to be the process's, not theirs. */
    @Test
    fun everyTransportInTheProcessSharesOneRation() = runBlocking {
        server.status = 503
        queue.enqueue(listOf(server.url("old")))

        repeat(5) { VastHttpTransport(queue, maxAttempts = 2).flushPending() }

        assertEquals(listOf("/old"), server.requested.toList())
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

        /** Paths that answer 503 whatever [status] says: a tracker that is down while the rest are not. */
        val failing: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        val requested: MutableList<String> = Collections.synchronizedList(mutableListOf())
        private val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val path = exchange.requestURI.path
                requested += path
                exchange.sendResponseHeaders(if (path in failing) 503 else status, -1)
                exchange.close()
            }
            start()
        }

        fun url(path: String) = "http://127.0.0.1:${httpServer.address.port}/$path"

        override fun close() = httpServer.stop(0)
    }
}
