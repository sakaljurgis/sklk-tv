package lt.sklk.tv

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** One line in a [BrowseFragment] list. */
sealed interface Row {
    /** Stable identity, used to keep the selection across reloads. */
    val id: String

    data class Header(val text: String) : Row {
        override val id get() = "header:$text"
    }

    data class Item(val entry: Entry) : Row {
        override val id get() = "item:${entry.path}"
    }

    /** A file from the server's Continue watching, with the folder it's in. */
    data class Continue(val entry: Entry) : Row {
        override val id get() = "continue:${entry.path}"
    }

    /** Home: the server's address, set on click. */
    data class Server(val url: String?) : Row {
        override val id get() = "server"
    }

    /** An empty-folder notice or a load error that retries on click. */
    data class Info(val text: String, val retry: Boolean) : Row {
        override val id get() = "info"
    }
}

/** Rows for a leanback VerticalGridView: OK opens, hold OK or the Menu key opens the "..." menu. */
class RowAdapter(
    private val onClick: (Row) -> Unit,
    private val onMenu: (Row) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var rows: List<Row> = emptyList()
        private set

    private val ids = HashMap<String, Long>()

    init {
        setHasStableIds(true)
    }

    fun submit(newRows: List<Row>) {
        rows = newRows
        // stable ids let RecyclerView keep focus on the same row through a full rebind
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    override fun getItemCount() = rows.size

    override fun getItemId(position: Int) = ids.getOrPut(rows[position].id) { ids.size.toLong() }

    override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) TYPE_HEADER else TYPE_ROW

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        if (viewType == TYPE_HEADER) {
            return HeaderHolder(inflater.inflate(R.layout.row_header, parent, false))
        }
        val holder = RowHolder(inflater.inflate(R.layout.row_item, parent, false))
        val v = holder.itemView
        val rowAt = { holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let(rows::getOrNull) }
        v.setOnClickListener { rowAt()?.let(onClick) }
        v.setOnLongClickListener { rowAt()?.let(onMenu); true }
        v.setOnKeyListener { _, keyCode, event ->
            if (keyCode != KeyEvent.KEYCODE_MENU) return@setOnKeyListener false
            if (event.action == KeyEvent.ACTION_UP) rowAt()?.let(onMenu)
            true
        }
        // long file names scroll while focused
        v.setOnFocusChangeListener { _, hasFocus -> holder.title.isSelected = hasFocus }
        return holder
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderHolder).text.text = row.text
            is Row.Item -> (holder as RowHolder).bind(row.entry)
            is Row.Continue -> (holder as RowHolder).bindContinue(row.entry)
            is Row.Info -> (holder as RowHolder).bind(row)
            is Row.Server -> (holder as RowHolder).bind(row)
        }
    }

    private class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.text)
    }

    private class RowHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.icon)
        val title: TextView = view.findViewById(R.id.title)
        val meta: TextView = view.findViewById(R.id.meta)
        val check: ImageView = view.findViewById(R.id.check)
        val progress: ProgressBar = view.findViewById(R.id.progress)

        fun bind(e: Entry) {
            icon.setImageResource(
                when {
                    e.isFolder -> R.drawable.ic_folder
                    e.isPlayable -> R.drawable.ic_play
                    e.isSearch -> R.drawable.ic_search
                    else -> R.drawable.ic_folder
                }
            )
            title.text = e.displayTitle
            // files: running time; folders: "26 files", torrents: "Size 4.46 GB"
            setMeta(if (e.isFile && e.durationSec > 0) Format.duration(e.durationSec) else e.plot?.lineSequence()?.first())
            // files: watched / resume point; shows and seasons: all watched / how much of it
            setStatus(e.isWatched, e.progress)
            title.isSelected = itemView.hasFocus()
        }

        fun bindContinue(e: Entry) {
            icon.setImageResource(R.drawable.ic_play)
            title.text = e.displayTitle
            val left = e.resumeTotalMs - e.resumeMs
            setMeta(
                if (e.resumeMs > 0 && left > 0) itemView.context.getString(R.string.time_left, Format.duration(left / 1000))
                else e.folderTitle
            )
            setStatus(false, e.progress)
            title.isSelected = itemView.hasFocus()
        }

        fun bind(info: Row.Info) {
            icon.setImageResource(R.drawable.ic_error)
            title.text = info.text
            setMeta(null)
            setStatus(false, 0f)
        }

        fun bind(server: Row.Server) {
            icon.setImageResource(R.drawable.ic_settings)
            title.text = itemView.context.getString(R.string.server)
            setMeta(server.url ?: itemView.context.getString(R.string.server_not_set))
            setStatus(false, 0f)
        }

        private fun setMeta(text: String?) {
            meta.text = text
            meta.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

        private fun setStatus(watched: Boolean, fraction: Float) {
            check.visibility = if (watched) View.VISIBLE else View.GONE
            val showBar = fraction > 0.01f
            progress.visibility = if (showBar) View.VISIBLE else View.GONE
            if (showBar) progress.progress = (fraction * progress.max).toInt()
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ROW = 1
    }
}
