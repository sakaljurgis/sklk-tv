package lt.sklk.tv

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.core.net.toUri

/**
 * The media server's address, like the Kodi add-on's setting (e.g. `https://media.example.com`).
 * Set on the home screen; a build can come with one preset via `sklk.serverUrl` in local.properties.
 */
object Settings {
    private const val KEY_SERVER_URL = "server_url"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    val serverUrl: String?
        get() = prefs.getString(KEY_SERVER_URL, null) ?: BuildConfig.DEFAULT_SERVER_URL.takeIf { it.isNotEmpty() }

    fun saveServerUrl(url: String) = prefs.edit { putString(KEY_SERVER_URL, url) }

    /** Takes what one might type: no scheme, a trailing slash or `/api`. Null if it isn't an address. */
    fun normalize(input: String): String? {
        var url = input.trim().trimEnd('/')
        if (url.isEmpty()) return null
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        url = url.removeSuffix("/api").trimEnd('/')
        return url.takeIf { !it.toUri().host.isNullOrEmpty() }
    }
}
