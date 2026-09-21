package dev.arachne.atak

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Debug-build timeline of the join and admission path, for race and latency
 * analysis. Release builds compile every call to a no-op (BuildConfig.DEBUG).
 *
 * Marks carry the device monotonic clock (elapsedRealtimeNanos) and the
 * thread name, so a host tool can merge several devices onto one timeline.
 * Details are short state words and counts. Never pass keys, names,
 * invitations or payloads. */
internal object ArachneTrace {
    private const val CAPACITY = 16_384
    // Fields, not one JSON string per event: names are literals and thread
    // names are shared, so a full ring holds little more than the details.
    // A full ring of JSON strings held about 2 MB. Allocated on the first
    // debug mark only, never in release builds.
    private object Ring {
        val seq = LongArray(CAPACITY) { -1 }
        val time = LongArray(CAPACITY)
        val thread = arrayOfNulls<String>(CAPACITY)
        val name = arrayOfNulls<String>(CAPACITY)
        val detail = arrayOfNulls<String>(CAPACITY)
    }
    private val next = AtomicLong(0)
    private val counters = ConcurrentHashMap<String, AtomicLong>()
    private class Timing { var count = 0L; var totalNs = 0L; var maxNs = 0L; var waitNs = 0L; var maxWaitNs = 0L }
    private val timings = ConcurrentHashMap<String, Timing>()

    fun mark(name: String, detail: String = "") {
        if (!BuildConfig.DEBUG) return
        val now = SystemClock.elapsedRealtimeNanos()
        val seq = next.getAndIncrement()
        val slot = (seq % CAPACITY).toInt()
        synchronized(Ring) {
            Ring.seq[slot] = seq; Ring.time[slot] = now
            Ring.thread[slot] = Thread.currentThread().name; Ring.name[slot] = name; Ring.detail[slot] = detail
        }
        count(name)
        if (android.os.Build.VERSION.SDK_INT >= 29 && android.os.Trace.isEnabled()) {
            // Zero-length section: shows as a tick on the Perfetto thread track.
            android.os.Trace.beginSection("Arachne!$name".take(127)); android.os.Trace.endSection()
        }
    }

    fun count(name: String) {
        if (!BuildConfig.DEBUG) return
        counters.getOrPut(name) { AtomicLong() }.incrementAndGet()
    }

    /** [waitNs] is queue time before the call started; [runNs] is the call. */
    fun timed(op: String, waitNs: Long, runNs: Long) {
        if (!BuildConfig.DEBUG) return
        val timing = timings.getOrPut(op) { Timing() }
        synchronized(timing) {
            timing.count++; timing.totalNs += runNs; timing.maxNs = maxOf(timing.maxNs, runNs)
            timing.waitNs += waitNs; timing.maxWaitNs = maxOf(timing.maxWaitNs, waitNs)
        }
    }

    /** Events with seq >= [since], oldest first, at most [limit]. `lost` counts
     * events that the ring overwrote before this read. */
    fun snapshot(since: Long, limit: Int): JSONObject {
        val end = next.get()
        val first = maxOf(since, end - CAPACITY, 0)
        val events = JSONArray()
        var seq = first
        while (seq < end && events.length() < limit) {
            val slot = (seq % CAPACITY).toInt()
            synchronized(Ring) {
                if (Ring.seq[slot] == seq) events.put(JSONObject().put("seq", seq).put("t_ns", Ring.time[slot])
                    .put("thread", Ring.thread[slot]).put("name", Ring.name[slot]).put("detail", Ring.detail[slot]))
            }
            seq++
        }
        return JSONObject().put("now_ns", SystemClock.elapsedRealtimeNanos()).put("next", seq).put("end", end)
            .put("lost", maxOf(0, first - since)).put("events", events)
    }

    fun stats(): JSONObject {
        val counts = JSONObject()
        counters.toSortedMap().forEach { (name, value) -> counts.put(name, value.get()) }
        val ops = JSONObject()
        timings.toSortedMap().forEach { (op, timing) ->
            synchronized(timing) {
                ops.put(op, JSONObject().put("count", timing.count)
                    .put("mean_us", if (timing.count == 0L) 0 else timing.totalNs / timing.count / 1000)
                    .put("max_us", timing.maxNs / 1000)
                    .put("mean_wait_us", if (timing.count == 0L) 0 else timing.waitNs / timing.count / 1000)
                    .put("max_wait_us", timing.maxWaitNs / 1000))
            }
        }
        return JSONObject().put("counters", counts).put("ops", ops)
    }

    /** Clears counters and timings. Returns the first seq of the new window;
     * pass it as `since` so events from before the reset are not read. */
    fun reset(): Long {
        counters.clear(); timings.clear()
        val first = next.get()
        mark("trace_reset")
        return first
    }
}
