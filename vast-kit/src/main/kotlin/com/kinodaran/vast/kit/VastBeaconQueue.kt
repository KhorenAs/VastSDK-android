package com.kinodaran.vast.kit

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Beacons that did not land, kept until they do.
 *
 * A tracking request is the only record that an ad was shown, and a failed one
 * used to be lost the moment it failed — most often in the case that happens
 * most: a request in flight when the viewer leaves the app. That is not a
 * rounding error in somebody's report; it is the impression that paid for the
 * break.
 *
 * Retrying, including across launches, is safe by design rather than by hope: a
 * VAST tracking URI carries its own event identity, so a repeat is de-duplicated
 * by the ad server instead of double-counted. `<Impression>` is explicit about
 * at-least-once delivery for exactly this reason.
 *
 * Two limits keep the file from becoming a graveyard. A beacon older than
 * [MAX_AGE_MILLIS] is dropped, because a report about yesterday is worth less to
 * an ad server than a clean count; and the queue holds [CAPACITY] entries,
 * oldest first out, so a device that spends a week offline does not accumulate a
 * megabyte of URLs it will never send.
 */
public class VastBeaconQueue internal constructor(
    private val file: File?,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private class Entry(val recordedMillis: Long, val url: String)

    private val mutex = Mutex()
    private var pending: List<Entry>? = null

    /** Keeps beacons that failed, so the next flush can try again. */
    public suspend fun enqueue(urls: List<String>): Unit = mutex.withLock {
        if (urls.isEmpty()) return@withLock
        val entries = load()
        val recorded = now()
        urls.mapTo(entries) { Entry(recorded, it) }
        val kept = trimmed(entries)
        pending = kept
        save(kept)
        VastLog.notice("holding ${kept.size} beacon(s) for retry")
    }

    /**
     * Takes everything worth retrying, and forgets it.
     *
     * Taken rather than copied: whoever drains the queue owns delivery from that
     * point, and re-queues what fails again. Holding on as well would send every
     * beacon twice on every flush for as long as one of them kept failing.
     */
    public suspend fun drain(): List<String> = mutex.withLock {
        val entries = trimmed(load())
        if (entries.isEmpty()) return@withLock emptyList()
        pending = emptyList()
        save(emptyList())
        entries.map { it.url }
    }

    public suspend fun count(): Int = mutex.withLock { trimmed(load()).size }

    // MARK: - Storage

    private fun load(): MutableList<Entry> {
        pending?.let { return it.toMutableList() }
        val entries = mutableListOf<Entry>()
        runCatching {
            file?.takeIf { it.exists() }?.forEachLine { line ->
                val tab = line.indexOf('\t')
                val recorded = if (tab > 0) line.substring(0, tab).toLongOrNull() else null
                if (recorded != null) entries += Entry(recorded, line.substring(tab + 1))
            }
        }
        pending = entries.toList()
        return entries
    }

    /**
     * One beacon per line, `recorded<TAB>url`. URLs have no raw tabs or newlines —
     * those are encoded before a URL is stored — so the format needs no escaping
     * and no JSON library, which is what lets this run in a plain JVM test.
     *
     * Written to a temporary file and renamed over the old one, so a process
     * killed mid-write leaves the previous queue rather than half of a new one.
     * A failed write is not worth interrupting anything for: the beacons are
     * still in memory, and this run will still try to send them.
     */
    private fun save(entries: List<Entry>) {
        val target = file ?: return
        runCatching {
            target.parentFile?.mkdirs()
            val temporary = File(target.parentFile, "${target.name}.tmp")
            temporary.writeText(entries.joinToString("") { "${it.recordedMillis}\t${it.url}\n" })
            if (!temporary.renameTo(target)) {
                target.delete()
                temporary.renameTo(target)
            }
        }.onFailure { VastLog.notice("could not hold beacons for retry: ${it.message}") }
    }

    private fun trimmed(entries: MutableList<Entry>): MutableList<Entry> {
        val cutoff = now() - MAX_AGE_MILLIS
        val fresh = entries.filterTo(mutableListOf()) { it.recordedMillis > cutoff }
        return if (fresh.size <= CAPACITY) fresh else fresh.subList(fresh.size - CAPACITY, fresh.size).toMutableList()
    }

    public companion object {
        /** Older than this and a beacon is dropped rather than sent. */
        public const val MAX_AGE_MILLIS: Long = 6 * 60 * 60 * 1000L

        /** Most beacons kept at once. */
        public const val CAPACITY: Int = 500

        @Volatile
        private var shared: VastBeaconQueue? = null

        /**
         * The process-wide queue, so retries survive the session that queued them —
         * and so a host that builds a transport per break does not build a queue
         * per break.
         *
         * In `noBackupFilesDir`: the system never backs it up, so a queued URL —
         * which carries whatever the host supplied through the macros, an
         * advertising identifier and a consent string among them — does not travel
         * to a cloud backup. App-private storage is encrypted at rest by the
         * platform on every device that ships with Android 10 or later.
         */
        public fun shared(context: Context): VastBeaconQueue = shared ?: synchronized(this) {
            shared ?: VastBeaconQueue(File(context.applicationContext.noBackupFilesDir, "vast-pending-beacons.tsv")).also { shared = it }
        }
    }
}
