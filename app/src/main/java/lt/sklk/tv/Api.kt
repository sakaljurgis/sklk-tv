package lt.sklk.tv

import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Client for the media server's kodi-style JSON API (kodi-api-server, also behind its web UI at
 * `/if/` and the Kodi add-on); the server's address is in [Settings].
 *
 * Every screen is `GET /api?path=<path>[&search=<text>]`, answering with either a
 * folder listing (`updateList` + `items`), a stream to play (`play`), or a message.
 * Items may carry `contextMenus` (mark watched, delete file, ...): actions that are run by
 * querying their path, exactly like the web ui's "..." dropdown.
 * Watch state lives on the server, shared with Kodi: listings carry it, players report to
 * `/api/state/<file id>` (see [Progress]).
 */
object Api {
    /** In-progress files and next episodes, each with the folder it's in. */
    const val CONTINUE_PATH = "all/continue"

    private val FILE_ID = Regex("all/play/(\\d+)")

    private val endpoint get() = (Settings.serverUrl ?: throw IOException("No server set")) + "/api"

    suspend fun query(path: String, search: String? = null): Response = withContext(Dispatchers.IO) {
        val uri = endpoint.toUri().buildUpon().apply {
            if (path.isNotEmpty()) appendQueryParameter("path", path)
            if (search != null) appendQueryParameter("search", search)
        }.build()
        parse(request(uri.toString()))
    }

    /** A file's watch state as the server has it now; null if the server doesn't know the file. */
    suspend fun state(fileId: Int): FileState? = withContext(Dispatchers.IO) {
        FileState.parse(request("$endpoint/state/$fileId", timeoutMs = 3_000))
    }

    /** A player's progress report; answers the updated state, or null if the server rejected it (e.g. the file is gone). */
    suspend fun report(fileId: Int, report: JSONObject): FileState? = withContext(Dispatchers.IO) {
        FileState.parse(request("$endpoint/state/$fileId", body = report, timeoutMs = 10_000))
    }

    /** The server's id of the file a playable path streams, e.g. 103 for `/api?path=all/play/103`. */
    fun fileId(path: String): Int? = FILE_ID.find(path)?.groupValues?.get(1)?.toIntOrNull()

    /** Play paths are either absolute urls or server-relative (`/api?path=all/play/103`). */
    fun resolveUrl(path: String): String {
        val origin = Settings.serverUrl.orEmpty()
        return when {
            path.startsWith("http://") || path.startsWith("https://") -> path
            path.startsWith("/") -> origin + path
            else -> "$origin/$path"
        }
    }

    /** GET, or POST of [body] as JSON. */
    private fun request(url: String, body: JSONObject? = null, timeoutMs: Int = 30_000): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = minOf(timeoutMs, 15_000)
        conn.readTimeout = timeoutMs
        try {
            if (body != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (code !in 200..299) {
                throw IOException("HTTP $code" + (json?.optString("message")?.let { ": $it" } ?: ""))
            }
            return json ?: throw IOException("Invalid response")
        } finally {
            conn.disconnect()
        }
    }

    // same precedence as the web interface's processApiResult()
    private fun parse(json: JSONObject): Response {
        val forceUpdate = json.optJSONObject("forceUpdate")?.optJSONObject("urlParams")?.optString("path")
        val message = json.optStringOrNull("notification") ?: json.optStringOrNull("msgBoxOK")
        val play = json.optStringOrNull("play")

        var listing: Listing? = null
        val items = json.optJSONArray("items")
        if (json.optBoolean("updateList") && items != null) {
            val entries = (0 until items.length()).mapNotNull { i -> items.optJSONObject(i)?.let(::parseEntry) }
            listing = Listing(
                title = json.optStringOrNull("category"),
                items = if (json.optBoolean("nosort")) entries else entries.sortedWith(Entry.byLabel),
            )
        }
        // like the web ui: a notification refreshes the current folder unless told otherwise
        val refresh = forceUpdate != null || json.optBoolean("refreshContainer", true)
        return Response(listing, play, message, forceUpdate, refresh)
    }

    private fun parseEntry(o: JSONObject): Entry {
        val resume = o.optJSONObject("resume")
        return Entry(
            label = o.optString("label"),
            path = o.optString("path"),
            isFolder = o.optBoolean("isFolder"),
            isPlayable = o.optBoolean("IsPlayable") || o.optBoolean("isPlayable") || o.optString("mediatype") == "video",
            action = o.optStringOrNull("action"),
            plot = o.optStringOrNull("plot"),
            thumb = o.optStringOrNull("thumb"),
            searchFor = o.optStringOrNull("searchFor"),
            sizeBytes = o.optLong("size"),
            durationSec = o.optLong("duration"),
            menu = o.optJSONArray("contextMenus")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val m = arr.optJSONObject(i) ?: return@mapNotNull null
                    val path = m.optJSONObject("urlParams")?.optStringOrNull("path") ?: m.optStringOrNull("path")
                    path?.let { MenuAction(m.optString("name", it), it) }
                }
            }.orEmpty(),
            playCount = o.optInt("playcount"),
            resumeSec = resume?.optDouble("position", 0.0) ?: 0.0,
            resumeTotalSec = resume?.optDouble("total", 0.0) ?: 0.0,
            watchedCount = o.optInt("watchedCount"),
            totalCount = o.optInt("totalCount"),
            folder = o.optStringOrNull("folder"),
            folderTitle = o.optStringOrNull("folderTitle"),
        )
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null
}

data class Response(
    val listing: Listing?,
    val play: String?,
    val message: String?,
    val forceUpdatePath: String?,
    val refreshContainer: Boolean,
)

data class Listing(val title: String?, val items: List<Entry>)

/** A file's watch state from `/api/state/<file id>`. */
data class FileState(val positionMs: Long, val durationMs: Long, val watched: Boolean) {
    companion object {
        /** Errors come back as a `notification` (with HTTP 200), not as a state. */
        fun parse(json: JSONObject): FileState? = if (!json.has("fileId")) null else FileState(
            positionMs = (json.optDouble("position", 0.0) * 1000).toLong(),
            durationMs = (json.optDouble("duration", 0.0) * 1000).toLong(),
            watched = json.optBoolean("watched"),
        )
    }
}

/** A server-side "..." menu action; run by querying [path]. */
data class MenuAction(val name: String, val path: String) {
    /** Actions that can't be undone get a confirmation dialog first. */
    val isDestructive get() = DESTRUCTIVE.containsMatchIn(name) || DESTRUCTIVE.containsMatchIn(path)

    private companion object {
        val DESTRUCTIVE = Regex("delete|remove|trin|šalin", RegexOption.IGNORE_CASE)
    }
}

data class Entry(
    val label: String,
    val path: String,
    val isFolder: Boolean = false,
    val isPlayable: Boolean = false,
    val action: String? = null,
    val plot: String? = null,
    val thumb: String? = null,
    val searchFor: String? = null,
    val sizeBytes: Long = 0,
    val durationSec: Long = 0,
    val menu: List<MenuAction> = emptyList(),
    /** Files: times watched to the end and the resume point, as the server keeps them for every client. */
    val playCount: Int = 0,
    val resumeSec: Double = 0.0,
    val resumeTotalSec: Double = 0.0,
    /** Folders: watched episodes out of [totalCount]. */
    val watchedCount: Int = 0,
    val totalCount: Int = 0,
    /** Files listed outside their folder (Continue watching): the folder they're in. */
    val folder: String? = null,
    val folderTitle: String? = null,
) {
    val isFile get() = isPlayable && !isFolder

    val isSearch get() = !isFolder && action == "search"

    /** Playable items with action "query" must first be resolved through the api to get a stream url. */
    val needsResolve get() = action == "query"

    /** Files: watched at least once; folders: every episode is. */
    val isWatched get() = if (isFolder) totalCount > 0 && watchedCount >= totalCount else playCount > 0

    val resumeMs get() = (resumeSec * 1000).toLong()

    val resumeTotalMs get() = ((resumeTotalSec.takeIf { it > 0 } ?: durationSec.toDouble()) * 1000).toLong()

    /** For a progress bar: how far in the resume point is, or the watched share of a partly watched folder; 0 = none. */
    val progress: Float
        get() = when {
            isFolder -> if (totalCount > 0 && watchedCount < totalCount) watchedCount.toFloat() / totalCount else 0f
            resumeMs > 0 && resumeTotalMs > 0 -> (resumeMs.toFloat() / resumeTotalMs).coerceIn(0f, 1f)
            else -> 0f
        }

    /** File names like `Reacher.S01E07.mkv` read better as `Reacher S01E07`. */
    val displayTitle: String
        get() = if (isFile) {
            label.replace(Regex("\\.(mkv|mp4|avi|m4v|mov|wmv|ts|webm)$", RegexOption.IGNORE_CASE), "")
                .replace(Regex("[._]+"), " ")
                .trim()
        } else {
            label
        }

    companion object {
        /** Case-insensitive natural order ignoring a leading "The", so `Episode 2` sorts before `Episode 10`. */
        val byLabel: Comparator<Entry> = Comparator { a, b -> naturalCompare(sortKey(a.label), sortKey(b.label)) }

        private fun sortKey(label: String) = label.lowercase().removePrefix("the ").trim()

        private val chunk = Regex("\\d+|\\D+")

        private fun naturalCompare(a: String, b: String): Int {
            val ca = chunk.findAll(a).map { it.value }.toList()
            val cb = chunk.findAll(b).map { it.value }.toList()
            for (i in 0 until minOf(ca.size, cb.size)) {
                val x = ca[i]
                val y = cb[i]
                val cmp = if (x[0].isDigit() && y[0].isDigit()) {
                    x.trimStart('0').length.compareTo(y.trimStart('0').length)
                        .takeIf { it != 0 } ?: x.trimStart('0').compareTo(y.trimStart('0'))
                } else {
                    x.compareTo(y)
                }
                if (cmp != 0) return cmp
            }
            return ca.size.compareTo(cb.size)
        }
    }
}
