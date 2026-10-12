package lt.sklk.tv

import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import coil.dispose
import coil.load

/** Right-hand side of the list: everything known about the focused row. */
class DetailsPanel(root: View) {
    private val context = root.context
    private val image: ImageView = root.findViewById(R.id.details_image)
    private val title: TextView = root.findViewById(R.id.details_title)
    private val meta: TextView = root.findViewById(R.id.details_meta)
    private val progressText: TextView = root.findViewById(R.id.details_progress_text)
    private val progress: ProgressBar = root.findViewById(R.id.details_progress)
    private val body: TextView = root.findViewById(R.id.details_body)
    private val hint: TextView = root.findViewById(R.id.details_hint)

    init {
        image.clipToOutline = true
    }

    fun bind(row: Row?) {
        when (row) {
            is Row.Item -> bind(row.entry)
            is Row.Continue -> bindContinue(row.entry)
            is Row.Server -> {
                clear()
                title.text = context.getString(R.string.server)
                set(meta, row.url)
                set(body, context.getString(R.string.server_about))
            }
            else -> clear()
        }
    }

    private fun bind(e: Entry) {
        setImage(e.thumb)
        title.text = e.displayTitle
        val plot = e.plot?.lines()?.filter { it.isNotBlank() }.orEmpty()
        if (e.isFile) {
            set(meta, listOfNotNull(
                e.durationSec.takeIf { it > 0 }?.let(Format::duration),
                e.sizeBytes.takeIf { it > 0 }?.let(Format::size),
            ).joinToString("  ·  "))
            // the size is already in the meta line; show the real file name since the title is prettified
            val lines = plot.filterNot { it.startsWith("Size ") }
            set(body, (listOf(e.label).filter { it != e.displayTitle } + lines).joinToString("\n"))
        } else {
            set(meta, plot.firstOrNull())
            set(body, plot.drop(1).joinToString("\n"))
        }
        setProgress(e)
        hint.visibility = if (e.isFile || e.menu.isNotEmpty()) View.VISIBLE else View.INVISIBLE
    }

    private fun bindContinue(e: Entry) {
        setImage(e.thumb)
        title.text = e.displayTitle
        set(meta, e.folderTitle)
        set(body, null)
        if (e.resumeMs > 0) {
            setProgress(e)
        } else {
            setProgress(null)
            set(progressText, context.getString(R.string.up_next))
        }
        hint.visibility = View.VISIBLE
    }

    private fun clear() {
        setImage(null)
        title.text = null
        set(meta, null)
        set(body, null)
        setProgress(null)
        hint.visibility = View.INVISIBLE
    }

    /** Files: where it stopped, or watched; folders: how many episodes are watched. */
    private fun setProgress(e: Entry?) {
        val text = when {
            e == null -> null
            e.isFolder && e.watchedCount > 0 && !e.isWatched -> context.getString(R.string.watched_count, e.watchedCount, e.totalCount)
            !e.isFolder && e.resumeMs > 0 -> context.getString(R.string.stopped_at, Format.time(e.resumeMs), Format.time(e.resumeTotalMs))
            e.isWatched -> context.getString(R.string.watched)
            else -> null
        }
        set(progressText, text)
        val fraction = if (text != null) e?.progress ?: 0f else 0f
        progress.visibility = if (fraction > 0f) View.VISIBLE else View.GONE
        progress.progress = (fraction * progress.max).toInt()
    }

    // posters aren't in the api yet; any item with a `thumb` gets one here
    private fun setImage(url: String?) {
        image.dispose()
        if (url == null) {
            image.setImageDrawable(null)
            image.visibility = View.GONE
        } else {
            image.visibility = View.VISIBLE
            image.load(url) { crossfade(true) }
        }
    }

    private fun set(view: TextView, text: String?) {
        view.text = text
        view.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }
}
