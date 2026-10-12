package lt.sklk.tv

import android.app.AlertDialog
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.text.inSpans
import androidx.fragment.app.Fragment
import androidx.leanback.widget.OnChildViewHolderSelectedListener
import androidx.leanback.widget.VerticalGridView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * One api folder as a list with a details panel. Loads `/api?path=<path>[&search=]` itself.
 * The home screen (empty path) also shows the server's "Continue watching" on top, and the server
 * setting at the bottom (asked for right away if there's none).
 */
class BrowseFragment : Fragment(R.layout.fragment_browse) {

    private val path by lazy { requireArguments().getString(ARG_PATH).orEmpty() }
    private val search by lazy { requireArguments().getString(ARG_SEARCH) }
    private val crumbs by lazy { requireArguments().getStringArrayList(ARG_CRUMBS).orEmpty() }
    private val isHome get() = path.isEmpty() && search == null

    private val adapter = RowAdapter(::onRowClicked, ::onRowMenu)
    private var entries: List<Entry>? = null
    private var continueEntries: List<Entry> = emptyList()
    private var error: String? = null
    private var loadJob: Job? = null
    private var loadedAt = 0L
    private var busyCount = 0

    private var selectedId: String? = null
    private var selectedIndex = 0

    private var list: VerticalGridView? = null
    private var details: DetailsPanel? = null
    private var loading: View? = null
    private var count: TextView? = null
    private val showLoading = Runnable { loading?.visibility = View.VISIBLE }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loading = view.findViewById(R.id.loading)
        count = view.findViewById(R.id.count)
        details = DetailsPanel(view.findViewById(R.id.details))
        view.findViewById<TextView>(R.id.breadcrumb).text = breadcrumb()

        list = view.findViewById<VerticalGridView>(R.id.list).also {
            it.adapter = adapter
            it.itemAnimator = null
            it.setOnChildViewHolderSelectedListener(object : OnChildViewHolderSelectedListener() {
                override fun onChildViewHolderSelected(parent: RecyclerView, child: RecyclerView.ViewHolder?, position: Int, subposition: Int) {
                    val row = adapter.rows.getOrNull(position)
                    if (row != null) {
                        selectedId = row.id
                        selectedIndex = position
                    }
                    details?.bind(row)
                }
            })
        }

        render()
        if (entries == null && error == null) load(silent = false)
    }

    override fun onResume() {
        super.onResume()
        // back from the player or a subfolder: watch state and "Continue watching" may have changed
        // (here or on another device), and the folder itself too (deleted files, torrent status)
        if (entries != null && SystemClock.elapsedRealtime() - loadedAt > REFRESH_AFTER_MS) load(silent = true)
    }

    override fun onDestroyView() {
        loading?.removeCallbacks(showLoading)
        list = null
        details = null
        loading = null
        count = null
        super.onDestroyView()
    }

    // ---- loading

    /** [silent]: refresh in the background keeping what's shown; errors are ignored. */
    private fun load(silent: Boolean) {
        loadJob?.cancel()
        if (Settings.serverUrl == null) {
            render()
            if (isHome && !silent) askServer()
            return
        }
        loadJob = lifecycleScope.launch {
            if (!silent) busy(true)
            try {
                // progress the player just reported should be in what's loaded
                Progress.sync()
                val continuing = if (isHome) async { loadContinueWatching() } else null
                var response = Api.query(path, search)
                // the api may ask to re-query the current folder; the web ui does the same
                if (response.forceUpdatePath != null) response = Api.query(path, search)
                val resume = continuing?.await().orEmpty()
                loadedAt = SystemClock.elapsedRealtime()
                when {
                    response.listing != null -> {
                        response.message?.let(::toast)
                        if (!silent || response.listing.items != entries || resume != continueEntries) {
                            entries = response.listing.items
                            continueEntries = resume
                            error = null
                            render()
                        }
                    }
                    response.play != null -> {
                        startPlayer(listOf(PlayerActivity.Item(Api.resolveUrl(response.play), crumbs.lastOrNull().orEmpty(), path)), 0, 0)
                        leave(response.message)
                    }
                    else -> leave(response.message ?: getString(R.string.nothing_here))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!silent || entries == null) {
                    error = getString(R.string.load_failed, e.message ?: e.javaClass.simpleName)
                    render()
                }
            } finally {
                if (!silent) busy(false)
            }
        }
    }

    /** Home only; if it fails, the section is just left out. */
    private suspend fun loadContinueWatching(): List<Entry> = try {
        Api.query(Api.CONTINUE_PATH).listing?.items.orEmpty()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }

    /** For responses that aren't a listing (e.g. "scan started"): show the message and go back up. */
    private fun leave(message: String?) {
        message?.let(::toast)
        val fm = parentFragmentManager
        if (!isHome && fm.findFragmentById(R.id.container) === this) fm.popBackStack()
    }

    private fun render() {
        val list = list ?: return
        val rows = buildRows()
        adapter.submit(rows)

        val index = rows.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 }
            ?: selectedIndex.coerceAtMost(rows.lastIndex).takeIf { it >= 0 && rows[it] !is Row.Header }
            ?: rows.indexOfFirst { it !is Row.Header }.coerceAtLeast(0)
        list.selectedPosition = index
        details?.bind(rows.getOrNull(index))
        if (!list.hasFocus()) list.requestFocus()

        count?.text = entries?.takeIf { !isHome }?.let { resources.getQuantityString(R.plurals.items_count, it.size, it.size) }
    }

    private fun buildRows(): List<Row> {
        val rows = mutableListOf<Row>()
        if (isHome && continueEntries.isNotEmpty()) {
            rows += Row.Header(getString(R.string.continue_watching))
            continueEntries.mapTo(rows, Row::Continue)
            rows += Row.Header(getString(R.string.library))
        }
        when {
            error != null -> rows += Row.Info(error!!, retry = true)
            // home shows the Continue watching folder inline, above
            entries != null -> entries!!.filterNot { isHome && it.path == Api.CONTINUE_PATH }.mapTo(rows, Row::Item)
        }
        if (rows.isEmpty() && entries != null) rows += Row.Info(getString(R.string.nothing_here), retry = false)
        // once there's something above it, or it'd take the focus
        if (isHome && (entries != null || error != null || Settings.serverUrl == null)) {
            rows += Row.Header(getString(R.string.settings))
            rows += Row.Server(Settings.serverUrl)
        }
        return rows
    }

    private fun breadcrumb(): CharSequence {
        if (crumbs.isEmpty()) return getString(R.string.app_name)
        val dim = ForegroundColorSpan(requireContext().getColor(R.color.text_secondary))
        return SpannableStringBuilder().apply {
            inSpans(dim) { append(crumbs.dropLast(1).joinToString("") { "$it  ›  " }) }
            append(crumbs.last())
        }
    }

    // ---- clicks

    private fun onRowClicked(row: Row) {
        when (row) {
            is Row.Header -> Unit
            is Row.Info -> if (row.retry) {
                error = null
                render()
                load(silent = false)
            }
            is Row.Continue -> playContinue(row.entry, fromStart = false)
            is Row.Server -> askServer()
            is Row.Item -> {
                val e = row.entry
                when {
                    e.isFolder -> openFolder(e.path, e.label)
                    e.isPlayable && e.needsResolve -> resolveAndPlay(e)
                    e.isPlayable -> playFromHere(e, entries.orEmpty())
                    e.isSearch -> askSearch(e)
                }
            }
        }
    }

    private fun openFolder(path: String, title: String, search: String? = null, crumbs: List<String> = this.crumbs + title) {
        (activity as? MainActivity)?.openFolder(BrowseFragment.newInstance(path, crumbs, search))
    }

    /** Plays [entry] followed by the rest of its folder, so episodes continue automatically. */
    private fun playFromHere(entry: Entry, siblings: List<Entry>, fromStart: Boolean = false) {
        val playlist = siblings.filter { it.isFile && !it.needsResolve }.ifEmpty { listOf(entry) }
        val start = playlist.indexOf(entry).coerceAtLeast(0)
        lifecycleScope.launch {
            val positionMs = if (fromStart) 0L else resumePositionMs(playlist[start])
            startPlayer(playlist.map { PlayerActivity.Item(Api.resolveUrl(it.path), it.displayTitle, it.path, it.resumeMs) }, start, positionMs)
        }
    }

    /**
     * Where to start [entry]: its resume point, asked for again since it may have moved on another
     * device after the list loaded (the listed one if the server can't say).
     */
    private suspend fun resumePositionMs(entry: Entry): Long {
        val fileId = Api.fileId(entry.path) ?: return 0L
        busy(true)
        val fresh = try {
            Progress.sync()
            Api.state(fileId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } finally {
            busy(false)
        }
        return (fresh?.positionMs ?: entry.resumeMs).takeIf { it >= Progress.MIN_RESUME_MS } ?: 0L
    }

    /** Items with action "query" first ask the api for their stream url. */
    private fun resolveAndPlay(entry: Entry) {
        lifecycleScope.launch {
            busy(true)
            try {
                val response = Api.query(entry.path)
                response.message?.let(::toast)
                response.play?.let {
                    startPlayer(listOf(PlayerActivity.Item(Api.resolveUrl(it), entry.displayTitle, entry.path)), 0, 0)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(getString(R.string.load_failed, e.message ?: e.javaClass.simpleName))
            } finally {
                busy(false)
            }
        }
    }

    /** Re-lists the file's folder so playback continues with the following episodes; falls back to the file alone. */
    private fun playContinue(entry: Entry, fromStart: Boolean) {
        val folder = entry.folder ?: return playFromHere(entry, listOf(entry), fromStart)
        lifecycleScope.launch {
            busy(true)
            val items = try {
                Api.query(folder).listing?.items.orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            } finally {
                busy(false)
            }
            val listed = items.firstOrNull { it.path == entry.path }
            if (listed == null) playFromHere(entry, listOf(entry), fromStart) else playFromHere(listed, items, fromStart)
        }
    }

    private fun startPlayer(items: List<PlayerActivity.Item>, startIndex: Int, startPositionMs: Long) {
        if (items.isEmpty()) return
        startActivity(PlayerActivity.intent(requireContext(), items, startIndex, startPositionMs))
    }

    private fun askSearch(entry: Entry) {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setText(entry.searchFor.orEmpty())
            selectAll()
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(entry.label)
            .setView(input)
            .setPositiveButton(R.string.search, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        val submit = {
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) {
                dialog.dismiss()
                openFolder(entry.path, "${entry.label}: $text", search = text)
            }
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) submit()
            true
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { submit() }
            input.requestFocus()
        }
        dialog.show()
    }

    /** The server's address; a new one reloads everything from it. */
    private fun askServer() {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_DONE
            hint = "https://media.example.com"
            setText(Settings.serverUrl ?: "https://")
            setSelection(text.length)
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.server)
            .setMessage(R.string.server_about)
            .setView(input)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        val submit = {
            val url = Settings.normalize(input.text.toString())
            if (url == null) {
                toast(getString(R.string.server_invalid, input.text))
            } else {
                dialog.dismiss()
                if (url != Settings.serverUrl) {
                    Settings.saveServerUrl(url)
                    // they'd be for the other server's files
                    Progress.clearPending()
                    entries = null
                    continueEntries = emptyList()
                    error = null
                }
                load(silent = false)
            }
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) submit()
            true
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { submit() }
            input.requestFocus()
        }
        dialog.show()
    }

    // ---- "..." menu

    private fun onRowMenu(row: Row) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        val title: String
        when (row) {
            is Row.Continue -> {
                val e = row.entry
                title = e.displayTitle
                if (e.resumeMs >= Progress.MIN_RESUME_MS) actions += getString(R.string.menu_play_from_start) to { playContinue(e, fromStart = true) }
                e.folder?.let { folder ->
                    val folderTitle = e.folderTitle ?: folder
                    actions += getString(R.string.menu_open_folder) to { openFolder(folder, folderTitle, crumbs = listOf(folderTitle)) }
                }
                // hide from Continue watching, mark watched/unwatched
                e.menu.forEach { action -> actions += action.name to { runServerAction(e, action) } }
            }
            is Row.Item -> {
                val e = row.entry
                title = e.displayTitle
                if (e.isFile && !e.needsResolve && e.resumeMs >= Progress.MIN_RESUME_MS) actions += getString(R.string.menu_play_from_start) to {
                    playFromHere(e, entries.orEmpty(), fromStart = true)
                }
                // mark watched/unwatched (a file, or everything in a folder), delete file, ...
                e.menu.forEach { action -> actions += action.name to { runServerAction(e, action) } }
            }
            else -> return
        }
        if (actions.isEmpty()) {
            toast(getString(R.string.no_options))
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    /** Server actions from the api's contextMenus; anything delete-like is confirmed first, with Cancel focused. */
    private fun runServerAction(entry: Entry, action: MenuAction) {
        if (!action.isDestructive) return executeServerAction(entry, action)
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.confirm_title, action.name))
            .setMessage(getString(R.string.confirm_message, entry.label))
            .setPositiveButton(action.name) { _, _ -> executeServerAction(entry, action) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus() }
        dialog.show()
    }

    private fun executeServerAction(entry: Entry, action: MenuAction) {
        lifecycleScope.launch {
            busy(true)
            try {
                // e.g. "Mark unwatched" right after stopping: the stop report must not land after it
                Progress.sync()
                val response = Api.query(action.path)
                toast(response.message ?: getString(R.string.action_done, action.name))
                when {
                    response.play != null -> startPlayer(listOf(PlayerActivity.Item(Api.resolveUrl(response.play), entry.displayTitle, entry.path)), 0, 0)
                    // like the web ui, a returned list replaces what's shown (e.g. the folder without the deleted file)
                    response.listing != null && response.forceUpdatePath == null -> {
                        entries = response.listing.items
                        render()
                    }
                    response.refreshContainer -> load(silent = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(getString(R.string.action_failed, action.name, e.message ?: e.javaClass.simpleName))
            } finally {
                busy(false)
            }
        }
    }

    // ---- helpers

    /** Spinner for anything that makes the user wait; delayed so quick responses don't flash it. */
    private fun busy(on: Boolean) {
        busyCount = (busyCount + if (on) 1 else -1).coerceAtLeast(0)
        val loading = loading ?: return
        loading.removeCallbacks(showLoading)
        if (busyCount > 0) loading.postDelayed(showLoading, 300) else loading.visibility = View.GONE
    }

    private fun toast(text: String) {
        context?.let { Toast.makeText(it, text, Toast.LENGTH_LONG).show() }
    }

    companion object {
        private const val ARG_PATH = "path"
        private const val ARG_SEARCH = "search"
        private const val ARG_CRUMBS = "crumbs"
        private const val REFRESH_AFTER_MS = 5_000L

        /** [crumbs]: folder titles from home down to this one, for the breadcrumb. */
        fun newInstance(path: String, crumbs: List<String>, search: String? = null) = BrowseFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_PATH, path)
                putString(ARG_SEARCH, search)
                putStringArrayList(ARG_CRUMBS, ArrayList(crumbs))
            }
        }
    }
}
