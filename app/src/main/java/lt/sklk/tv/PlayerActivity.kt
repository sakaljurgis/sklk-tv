package lt.sklk.tv

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Pair
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.addCallback
import androidx.annotation.OptIn
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/**
 * Fullscreen ExoPlayer for a playlist of api streams. Progress goes to the server (see [Progress]):
 * every few seconds while playing, and on pause, stop, skipping and the end.
 *
 * Remote: OK toggles pause, Left/Right seek (hold to go faster) while the controls are hidden;
 * with the controls shown, the D-pad moves between buttons as usual and Back hides them.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : FragmentActivity() {

    /**
     * [key] identifies the item for progress reports (the api path of the file); [resumeMs] is where to
     * pick it up when the playlist moves on to it (the first item starts at the position it's given).
     */
    data class Item(val url: String, val title: String, val key: String, val resumeMs: Long = 0)

    private lateinit var playerView: PlayerView
    private lateinit var titleView: TextView
    private lateinit var infoView: TextView

    private lateinit var items: List<Item>
    private var player: ExoPlayer? = null
    private var startIndex = 0
    private var startPositionMs = 0L
    private var playWhenReady = true

    private val handler = Handler(Looper.getMainLooper())
    private val consumedKeyUps = mutableSetOf<Int>()

    // last known state of the current item, so it can still be reported after a playlist transition
    private var trackedKey: String? = null
    private var trackedPositionMs = 0L
    private var trackedDurationMs = 0L
    private var ticks = 0

    private val tracker = object : Runnable {
        override fun run() {
            track()
            if (++ticks % REPORT_EVERY_S == 0 && player?.isPlaying == true) reportTracked(Progress.Event.TICK)
            handler.postDelayed(this, 1_000)
        }
    }
    private val hideInfo = Runnable { infoView.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        playerView = findViewById(R.id.player_view)
        titleView = findViewById(R.id.title)
        infoView = findViewById(R.id.info)

        val urls = intent.getStringArrayListExtra(EXTRA_URLS).orEmpty()
        val titles = intent.getStringArrayListExtra(EXTRA_TITLES).orEmpty()
        val keys = intent.getStringArrayListExtra(EXTRA_KEYS).orEmpty()
        val resumes = intent.getLongArrayExtra(EXTRA_RESUMES) ?: LongArray(0)
        items = urls.indices.map {
            Item(urls[it], titles.getOrElse(it) { "" }, keys.getOrElse(it) { urls[it] }, resumes.getOrElse(it) { 0 })
        }
        if (items.isEmpty()) {
            finish()
            return
        }

        if (savedInstanceState != null) {
            startIndex = savedInstanceState.getInt(STATE_INDEX)
            startPositionMs = savedInstanceState.getLong(STATE_POSITION)
            playWhenReady = savedInstanceState.getBoolean(STATE_PLAY_WHEN_READY, true)
        } else {
            startIndex = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(items.indices)
            startPositionMs = intent.getLongExtra(EXTRA_START_POSITION, 0)
            if (startPositionMs > 0) showInfo(getString(R.string.resuming_from, Format.time(startPositionMs)), 4_000)
        }

        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { titleView.visibility = it })
        playerView.setErrorMessageProvider { e: PlaybackException ->
            Pair.create(0, getString(R.string.playback_error, e.errorCodeName, e.cause?.message ?: e.message.orEmpty()))
        }

        onBackPressedDispatcher.addCallback(this) {
            if (playerView.isControllerFullyVisible) playerView.hideController() else finish()
        }
    }

    override fun onStart() {
        super.onStart()
        initializePlayer()
    }

    override fun onPause() {
        super.onPause()
        // now, not in onStop: the list behind resumes before that and reloads, it should get this
        track()
        reportTracked(Progress.Event.STOP)
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        player?.let {
            startIndex = it.currentMediaItemIndex
            startPositionMs = it.currentPosition
            playWhenReady = it.playWhenReady
        }
        outState.putInt(STATE_INDEX, startIndex)
        outState.putLong(STATE_POSITION, startPositionMs)
        outState.putBoolean(STATE_PLAY_WHEN_READY, playWhenReady)
    }

    private fun initializePlayer() {
        val renderersFactory = DefaultRenderersFactory(this)
            // prefer the TV's own decoders, fall back to FFmpeg for audio formats it can't handle
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)

        val p = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(httpFactory))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                /* handleAudioFocus= */ true,
            )
            .setSeekBackIncrementMs(SEEK_STEP_MS)
            .setSeekForwardIncrementMs(SEEK_STEP_MS)
            .build()

        val mediaItems = items.map {
            MediaItem.Builder()
                .setUri(it.url)
                .setMediaId(it.key)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(it.title).build())
                .build()
        }
        p.addListener(listener)
        p.setMediaItems(mediaItems, startIndex, startPositionMs)
        p.playWhenReady = playWhenReady
        p.prepare()

        playerView.player = p
        player = p
        titleView.text = items[startIndex].title
        trackedKey = items[startIndex].key
        handler.postDelayed(tracker, 1_000)
    }

    private fun releasePlayer() {
        handler.removeCallbacks(tracker)
        val p = player ?: return
        track()
        startIndex = p.currentMediaItemIndex
        startPositionMs = p.currentPosition
        playWhenReady = p.playWhenReady
        p.removeListener(listener)
        p.release()
        player = null
        playerView.player = null
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // the previous item either ran to the end (auto-advance) or the user skipped away from it
            trackedKey?.let { prev ->
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) Progress.report(prev, trackedDurationMs, trackedDurationMs, Progress.Event.END)
                else if (prev != mediaItem?.mediaId) reportTracked(Progress.Event.STOP)
            }
            trackedKey = mediaItem?.mediaId
            trackedPositionMs = 0
            trackedDurationMs = 0
            titleView.text = mediaItem?.mediaMetadata?.title
            val item = items.firstOrNull { it.key == mediaItem?.mediaId } ?: return
            // the server's "up next" until there's a position
            Progress.report(item.key, 0, 0, Progress.Event.START)
            // moved on to an episode started before (maybe on another device): pick it up there
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED && item.resumeMs >= Progress.MIN_RESUME_MS) {
                player?.seekTo(item.resumeMs)
                showInfo(getString(R.string.resuming_from, Format.time(item.resumeMs)), 4_000)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                trackedKey?.let {
                    val duration = player?.duration?.takeIf { d -> d != C.TIME_UNSET } ?: trackedDurationMs
                    Progress.report(it, duration, duration, Progress.Event.END)
                }
                trackedKey = null
                finish()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // paused by the user (not buffering): report the exact spot, to pick it up elsewhere
            val p = player ?: return
            if (!isPlaying && !p.playWhenReady && p.playbackState == Player.STATE_READY) {
                track()
                reportTracked(Progress.Event.PAUSE)
            }
        }
    }

    private fun track() {
        val p = player ?: return
        if (p.currentMediaItem?.mediaId != trackedKey) return
        trackedPositionMs = p.currentPosition
        if (p.duration != C.TIME_UNSET) trackedDurationMs = p.duration
    }

    private fun reportTracked(event: Progress.Event) {
        trackedKey?.let { Progress.report(it, trackedPositionMs, trackedDurationMs, event) }
    }

    // lint wrongly attributes super.dispatchKeyEvent to androidx.core's restricted override
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        // swallow the key-up of anything handled on key-down, so the controller doesn't act on it too
        if (event.action == KeyEvent.ACTION_UP && consumedKeyUps.remove(code)) return true

        val p = player
        if (p != null && event.action == KeyEvent.ACTION_DOWN && !playerView.isControllerFullyVisible) {
            val handled = when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> seekBy(-seekStep(event)).let { true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> seekBy(seekStep(event)).let { true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (event.repeatCount == 0) {
                        if (p.isPlaying) {
                            p.pause()
                            playerView.showController()
                        } else {
                            p.play()
                        }
                    }
                    true
                }
                else -> false
            }
            if (handled) {
                consumedKeyUps += code
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // holding the button accelerates: 10s, then 30s, then 1min steps
    private fun seekStep(event: KeyEvent): Long = when {
        event.repeatCount > 20 -> 60_000
        event.repeatCount > 5 -> 30_000
        else -> SEEK_STEP_MS
    }

    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        if (!p.isCurrentMediaItemSeekable) return
        val duration = p.duration
        var target = (p.currentPosition + deltaMs).coerceAtLeast(0)
        if (duration != C.TIME_UNSET) target = target.coerceAtMost(duration - 1_000)
        p.seekTo(target)
        val arrow = if (deltaMs < 0) "⏪" else "⏩"
        val total = if (duration != C.TIME_UNSET) " / ${Format.time(duration)}" else ""
        showInfo("$arrow  ${Format.time(target)}$total", 1_500)
    }

    private fun showInfo(text: String, durationMs: Long) {
        infoView.text = text
        infoView.visibility = View.VISIBLE
        handler.removeCallbacks(hideInfo)
        handler.postDelayed(hideInfo, durationMs)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URLS = "urls"
        private const val EXTRA_TITLES = "titles"
        private const val EXTRA_KEYS = "keys"
        private const val EXTRA_RESUMES = "resumes"
        private const val EXTRA_INDEX = "index"
        private const val EXTRA_START_POSITION = "start_position"
        private const val STATE_INDEX = "state_index"
        private const val STATE_POSITION = "state_position"
        private const val STATE_PLAY_WHEN_READY = "state_play_when_ready"
        private const val SEEK_STEP_MS = 10_000L
        private const val REPORT_EVERY_S = 15

        /** Plays [items] from [startIndex], at [startPositionMs]. */
        fun intent(context: Context, items: List<Item>, startIndex: Int, startPositionMs: Long) =
            Intent(context, PlayerActivity::class.java)
                .putStringArrayListExtra(EXTRA_URLS, ArrayList(items.map { it.url }))
                .putStringArrayListExtra(EXTRA_TITLES, ArrayList(items.map { it.title }))
                .putStringArrayListExtra(EXTRA_KEYS, ArrayList(items.map { it.key }))
                .putExtra(EXTRA_RESUMES, items.map { it.resumeMs }.toLongArray())
                .putExtra(EXTRA_INDEX, startIndex)
                .putExtra(EXTRA_START_POSITION, startPositionMs)
    }
}
