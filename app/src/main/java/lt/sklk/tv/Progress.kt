package lt.sklk.tv

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Sends playback progress to the server (`POST /api/state/<file id>`), which keeps watched state and
 * resume points for every client (Kodi too) and hands them back with the listings.
 *
 * Reports go out one at a time, in order. One that can't be sent is kept (the latest per file) and
 * resent later with the time it happened; the server ignores it if something newer came in meanwhile.
 */
object Progress {
    /** Resume points closer to the start than this aren't worth resuming from. */
    const val MIN_RESUME_MS = 30_000L

    enum class Event { START, TICK, PAUSE, STOP, END }

    private const val CLIENT = "sklk-tv"

    private lateinit var pending: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sending = Mutex()
    private val jobs = mutableSetOf<Job>()
    @Volatile private var resending = false

    fun init(context: Context) {
        pending = context.getSharedPreferences("progress_pending", Context.MODE_PRIVATE)
        importLocalHistory(context)
        resendPending()
    }

    /** [key] is the file's api path; streams without a server file (e.g. LRT) aren't tracked. */
    fun report(key: String, positionMs: Long, durationMs: Long, event: Event) {
        val fileId = Api.fileId(key) ?: return
        val report = JSONObject()
            .put("position", positionMs / 1000.0)
            .put("event", event.name.lowercase())
            .put("client", CLIENT)
        if (durationMs > 0) report.put("duration", durationMs / 1000.0)
        val at = System.currentTimeMillis()
        launch { send(fileId, report, at) }
    }

    /** Reports kept for a server that's no longer the one in [Settings] are meaningless. */
    fun clearPending() = pending.edit { clear() }

    /** Waits (a little) for reports still on their way, so that what's loaded next includes them. */
    suspend fun sync(timeoutMs: Long = 3_000) {
        resendPending()
        val running = synchronized(jobs) { jobs.toList() }
        withTimeoutOrNull(timeoutMs) { running.joinAll() }
    }

    private fun launch(block: suspend () -> Unit) {
        val job = scope.launch(start = CoroutineStart.LAZY) { block() }
        synchronized(jobs) { jobs += job }
        job.invokeOnCompletion { synchronized(jobs) { jobs -= job } }
        job.start()
    }

    /** [at]: when it happened; a resent report already carries it. */
    private suspend fun send(fileId: Int, report: JSONObject, at: Long) {
        val key = fileId.toString()
        sending.withLock {
            try {
                // a null answer is a rejection (e.g. the file was deleted): nothing to retry either
                Api.report(fileId, report)
                val keptAt = pending.getString(key, null)?.let { JSONObject(it).optLong("at") }
                if (keptAt != null && keptAt <= at) pending.edit { remove(key) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // offline, or the server is down: keep it to send later
                pending.edit { putString(key, JSONObject(report.toString()).put("at", at).toString()) }
            }
        }
    }

    /** Sends what was kept from earlier failures, unless that's under way already. */
    private fun resendPending() {
        if (resending) return
        val kept = pending.all.mapNotNull { (key, raw) ->
            val fileId = key.toIntOrNull() ?: return@mapNotNull null
            val report = (raw as? String)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return@mapNotNull null
            fileId to report
        }
        if (kept.isEmpty()) return
        resending = true
        launch {
            try {
                for ((fileId, report) in kept) send(fileId, report, report.optLong("at"))
            } finally {
                resending = false
            }
        }
    }

    /**
     * v0.2 kept progress on the TV only (by api path, `{pos, dur, watched, updated}` in ms, or v0.1's
     * `"pos:dur"`). Hand it to the server once, as late reports: whatever it knows that's newer wins.
     */
    private fun importLocalHistory(context: Context) {
        val old = context.getSharedPreferences("progress", Context.MODE_PRIVATE)
        val records = old.all
        if (records.isEmpty()) return
        pending.edit {
            for ((path, raw) in records) {
                val fileId = Api.fileId(path) ?: continue
                if (pending.contains(fileId.toString())) continue
                val report = (raw as? String)?.let(::legacyReport) ?: continue
                putString(fileId.toString(), report.toString())
            }
        }
        old.edit { clear() }
    }

    private fun legacyReport(raw: String): JSONObject? {
        val pos: Long
        val dur: Long
        var watched = false
        var updated = 0L
        if (raw.startsWith("{")) {
            val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            pos = o.optLong("pos")
            dur = o.optLong("dur")
            watched = o.optBoolean("watched")
            updated = o.optLong("updated")
        } else {
            val parts = raw.split(':')
            pos = parts[0].toLongOrNull() ?: return null
            dur = parts.getOrNull(1)?.toLongOrNull() ?: 0
        }
        val event = when {
            watched -> Event.END
            pos >= 10_000 -> Event.STOP
            else -> Event.START // started only: makes it "up next"
        }
        return JSONObject()
            .put("position", (if (watched) dur else pos) / 1000.0)
            .put("event", event.name.lowercase())
            .put("client", CLIENT)
            .put("at", updated.takeIf { it > 0 } ?: 1L)
            .apply { if (dur > 0) put("duration", dur / 1000.0) }
    }
}
