package com.swordfish.lemuroid.app.shared.covers

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.leanback.widget.ImageCardView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.swordfish.lemuroid.lib.library.db.entity.Game
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Plays the preview video of the currently focused game card.
 *
 * The preview can either render into an externally provided [PlayerView] (e.g. the one inside
 * the 天马G-style detail panel on the TV games screen) or, when no target is attached, fall back
 * to a floating overlay added to the card's fragment root.
 *
 * Only one player instance is kept for the whole app. Playback starts only after the focus
 * rests on a card for [FOCUS_DELAY_MS], so scrolling through the grid does not start and stop
 * the decoder continuously.
 */
object VideoPreviewController {

    private const val FOCUS_DELAY_MS = 450L

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var externalPlayerView: PlayerView? = null
    private var currentGameId: Int? = null
    private var currentCard: ImageCardView? = null
    private var pendingPlay: Runnable? = null
    private var resolveJob: Job? = null

    /** Fades the overlay in only once the first video frame has been rendered, so the user
     *  never sees a black shutter or the previous clip's last frame flash over the cover.
     *  Alpha is used instead of visibility: hiding the surface would stop it from ever
     *  producing a first frame. */
    private val playerListener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            if (currentCard != null) playerView?.alpha = 1f
        }
    }

    /**
     * Binds an externally owned [PlayerView] as the preview render target (e.g. the video
     * view inside the TV detail panel). When set, no floating overlay is created.
     */
    fun attachPlayerView(playerView: PlayerView) {
        externalPlayerView = playerView
        playerView.alpha = 0f
        playerView.useController = false
        playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        player?.let { playerView.player = it }
    }

    fun detachPlayerView() {
        externalPlayerView?.player = null
        externalPlayerView = null
    }

    fun onCardFocused(cardView: ImageCardView, game: Game) {
        cancelPending()
        if (currentGameId != game.id) {
            stopAndHide()
        }
        currentGameId = game.id
        currentCard = cardView

        val play = Runnable { play(cardView, game) }
        pendingPlay = play
        handler.postDelayed(play, FOCUS_DELAY_MS)
    }

    fun onCardUnfocused(game: Game) {
        if (currentGameId != game.id) return
        cancelPending()
        currentGameId = null
        currentCard = null
        stopAndHide()
    }

    /** Stops playback without releasing the player, e.g. when leaving the grid screen. */
    fun stopPreview() {
        cancelPending()
        currentGameId = null
        currentCard = null
        stopAndHide()
    }

    /** Hides the preview when a card is recycled while it was the one showing a video. */
    fun onCardDetached(cardView: ImageCardView) {
        if (currentCard !== cardView) return
        cancelPending()
        currentGameId = null
        currentCard = null
        stopAndHide()
    }

    private fun cancelPending() {
        pendingPlay?.let { handler.removeCallbacks(it) }
        pendingPlay = null
        resolveJob?.cancel()
        resolveJob = null
    }

    private fun play(cardView: ImageCardView, game: Game) {
        val context = cardView.context.applicationContext
        resolveJob = scope.launch {
            val source = VideoPreviewResolver.resolve(context, game)
            if (source == null) return@launch

            withContext(Dispatchers.Main) {
                if (currentGameId != game.id || currentCard !== cardView) return@withContext
                showAndPlay(cardView, source)
            }
        }
    }

    private fun showAndPlay(cardView: ImageCardView, source: VideoSource) {
        val view = getOrCreatePlayerView(cardView)
        view.alpha = 0f
        view.bringToFront()

        val exoPlayer = ensurePlayer(cardView.context)
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(source.headers)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)

        // DefaultDataSource resolves content:// (SAF), file:// (local) and http(s):// (WebDAV),
        // so the preview works with the 天马G media folder in every storage mode.
        val mediaSource = ProgressiveMediaSource.Factory(
            DefaultDataSource.Factory(cardView.context, httpDataSourceFactory)
        ).createMediaSource(MediaItem.fromUri(source.uri))

        exoPlayer.setMediaSource(mediaSource)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    /** Returns the attached external PlayerView, or creates a floating one as a fallback. */
    private fun getOrCreatePlayerView(cardView: ImageCardView): PlayerView {
        externalPlayerView?.let {
            playerView = it
            return it
        }
        return createFloatingPlayerView(cardView)
    }

    /** Fallback: a floating overlay anchored to the fragment root when no panel is attached. */
    private fun createFloatingPlayerView(cardView: ImageCardView): PlayerView {
        val host = findOverlayHost(cardView)
        playerView?.let { existing ->
            if (host != null && existing.parent === host) return existing
            (existing.parent as? ViewGroup)?.removeView(existing)
        }

        val density = cardView.resources.displayMetrics.density
        val panelSize = (300 * density).toInt()
        val marginEnd = (24 * density).toInt()

        val view = PlayerView(cardView.context).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            alpha = 0f
            isFocusable = false
            isClickable = false
            layoutParams = FrameLayout.LayoutParams(panelSize, panelSize).apply {
                gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
                this.marginEnd = marginEnd
            }
        }
        androidx.core.view.ViewCompat.setTranslationZ(view, 8f)

        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) = release()
        })

        host?.addView(view)
        playerView = view
        player?.let { view.player = it }
        return view
    }

    private fun findOverlayHost(anchor: View): ViewGroup? {
        var parent: ViewGroup? = anchor.parent as? ViewGroup
        while (parent != null) {
            if (parent is androidx.leanback.widget.BrowseFrameLayout) return parent
            parent = parent.parent as? ViewGroup
        }
        return (anchor.context as? android.app.Activity)?.findViewById(android.R.id.content)
    }

    private fun ensurePlayer(context: Context): ExoPlayer {
        player?.let { return it }

        val exoPlayer = ExoPlayer.Builder(context.applicationContext).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            // Crop the video to fill the whole panel surface. Combined with RESIZE_MODE_FIT on
            // the PlayerView this makes the preview cover the entire panel without
            // letterboxing and without aspect-ratio distortion.
            videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = false
            addListener(playerListener)
        }
        player = exoPlayer
        playerView?.player = exoPlayer
        return exoPlayer
    }

    private fun stopAndHide() {
        playerView?.alpha = 0f
        runCatching {
            player?.stop()
            player?.clearMediaItems()
        }
    }

    private fun release() {
        cancelPending()
        currentGameId = null
        currentCard = null

        runCatching {
            player?.removeListener(playerListener)
            player?.release()
        }
        player = null

        val view = playerView
        playerView = null
        if (externalPlayerView == null) {
            (view?.parent as? ViewGroup)?.removeView(view)
        }
    }
}
