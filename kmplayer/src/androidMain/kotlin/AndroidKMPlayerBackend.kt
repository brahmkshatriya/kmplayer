package dev.brahmkshatriya.kmplayer.internal

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import dev.brahmkshatriya.kmplayer.KMBackendKind
import dev.brahmkshatriya.kmplayer.KMMediaSource
import dev.brahmkshatriya.kmplayer.KMMediaStatus
import dev.brahmkshatriya.kmplayer.KMPlayerAndroid
import dev.brahmkshatriya.kmplayer.KMPlayerCapabilities
import dev.brahmkshatriya.kmplayer.KMPlayerConfig
import dev.brahmkshatriya.kmplayer.KMPlayerError
import dev.brahmkshatriya.kmplayer.KMPlayerEvent
import dev.brahmkshatriya.kmplayer.KMPlayerState
import dev.brahmkshatriya.kmplayer.KMResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal class AndroidKMPlayerBackend internal constructor(
    internal val player: ExoPlayer,
    private val videoAllowed: Boolean,
    initialVideoEnabled: Boolean,
) : KMPlayerBackend {
    private val handler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(KMPlayerState())
    private val mutableEvents = MutableSharedFlow<KMPlayerEvent>(extraBufferCapacity = 16)
    private var closed = false
    private var requestedVolume = 100.0
    private var muted = false

    override val state: StateFlow<KMPlayerState> = mutableState.asStateFlow()
    override val events: SharedFlow<KMPlayerEvent> = mutableEvents.asSharedFlow()
    override val capabilities: KMPlayerCapabilities = KMPlayerCapabilities(
        backend = KMBackendKind.Media3,
        video = videoAllowed,
        hls = true,
        nativeVideoSurface = videoAllowed,
    )
    private var videoEnabled: Boolean = initialVideoEnabled

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> mutableState.update {
                    it.copy(
                        mediaStatus = if (it.mediaStatus == KMMediaStatus.Idle) KMMediaStatus.Opening else it.mediaStatus,
                        isBuffering = true,
                    )
                }
                Player.STATE_READY -> {
                    mutableState.update {
                        it.copy(
                            mediaStatus = KMMediaStatus.Ready,
                            isBuffering = false,
                            seekable = player.isCurrentMediaItemSeekable,
                            isLive = player.isCurrentMediaItemLive,
                        )
                    }
                    mutableEvents.tryEmit(KMPlayerEvent.MediaLoaded)
                }
                Player.STATE_ENDED -> {
                    mutableState.update { it.copy(mediaStatus = KMMediaStatus.Ended, isBuffering = false) }
                    mutableEvents.tryEmit(KMPlayerEvent.MediaEnded)
                }
                Player.STATE_IDLE -> if (!closed) {
                    mutableState.update { it.copy(mediaStatus = KMMediaStatus.Idle, isBuffering = false) }
                }
            }
        }

        override fun onIsLoadingChanged(isLoading: Boolean) {
            mutableState.update { it.copy(isBuffering = isLoading && player.playbackState != Player.STATE_READY) }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            mutableState.update { it.copy(playWhenReady = playWhenReady) }
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            mutableState.update { it.copy(title = mediaMetadata.title?.toString()) }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            mutableState.update {
                it.copy(
                    videoWidth = videoSize.width.takeIf { width -> width > 0 },
                    videoHeight = videoSize.height.takeIf { height -> height > 0 },
                )
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val kmError = KMPlayerError(
                code = "media3-${error.errorCode}",
                message = error.message ?: "Media3 playback failed",
                cause = error,
            )
            mutableState.update { it.copy(mediaStatus = KMMediaStatus.Error, isBuffering = false, error = kmError) }
            mutableEvents.tryEmit(KMPlayerEvent.PlaybackError(kmError))
        }
    }

    private val progressTicker = object : Runnable {
        override fun run() {
            if (closed) return
            val durationMs = player.duration.takeIf { it != C.TIME_UNSET && it >= 0L }
            mutableState.update {
                it.copy(
                    position = player.currentPosition.coerceAtLeast(0L).milliseconds,
                    duration = durationMs?.milliseconds,
                    seekable = player.isCurrentMediaItemSeekable,
                    isLive = player.isCurrentMediaItemLive,
                )
            }
            handler.postDelayed(this, 250L)
        }
    }

    init {
        player.addListener(listener)
        applyVideoMode(videoEnabled)
        handler.post(progressTicker)
    }

    override suspend fun load(source: KMMediaSource, playWhenReady: Boolean): KMResult<Unit> = action {
        val itemBuilder = MediaItem.Builder().setUri(source.uri)
        val mime = source.mimeType ?: if (source.isHls) MimeTypes.APPLICATION_M3U8 else null
        mime?.let(itemBuilder::setMimeType)
        player.setMediaItem(itemBuilder.build())
        player.playWhenReady = playWhenReady
        mutableState.update {
            it.copy(
                mediaStatus = KMMediaStatus.Opening,
                playWhenReady = playWhenReady,
                uri = source.uri,
                error = null,
            )
        }
        player.prepare()
    }

    override fun play(): KMResult<Unit> = action {
        player.play()
        mutableState.update { it.copy(playWhenReady = true) }
    }

    override fun pause(): KMResult<Unit> = action {
        player.pause()
        mutableState.update { it.copy(playWhenReady = false) }
    }

    override fun stop(): KMResult<Unit> = action {
        player.stop()
        mutableState.update { it.copy(mediaStatus = KMMediaStatus.Idle, playWhenReady = false) }
    }

    override fun seekTo(position: Duration): KMResult<Unit> = action {
        player.seekTo(position.inWholeMilliseconds.coerceAtLeast(0L))
    }

    override fun setVolume(volume: Double): KMResult<Unit> {
        if (!volume.isFinite() || volume !in 0.0..100.0) {
            return KMResult.Failure(KMPlayerError("invalid-volume", "volume must be between 0 and 100"))
        }
        requestedVolume = volume
        return action {
            player.volume = if (muted) 0f else (requestedVolume / 100.0).toFloat()
            mutableState.update { it.copy(volume = requestedVolume) }
        }
    }

    override fun setMuted(muted: Boolean): KMResult<Unit> {
        this.muted = muted
        return action {
            player.volume = if (muted) 0f else (requestedVolume / 100.0).toFloat()
            mutableState.update { it.copy(muted = muted) }
        }
    }

    override fun setSpeed(speed: Double): KMResult<Unit> {
        if (!speed.isFinite() || speed <= 0.0) {
            return KMResult.Failure(KMPlayerError("invalid-speed", "speed must be a positive finite value"))
        }
        return action {
            player.setPlaybackSpeed(speed.toFloat())
            mutableState.update { it.copy(speed = speed) }
        }
    }

    override fun setVideoEnabled(enabled: Boolean): KMResult<Unit> {
        if (enabled && !videoAllowed) {
            return KMResult.Failure(KMPlayerError("video-disabled", "Video is disabled for this player."))
        }
        videoEnabled = enabled
        return action { applyVideoMode(enabled) }
    }

    private fun applyVideoMode(enabled: Boolean) {
        val offloadPreferences = TrackSelectionParameters.AudioOffloadPreferences.Builder()
            .setAudioOffloadMode(
                if (enabled) {
                    TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
                } else {
                    TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
                },
            )
            .build()
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled)
            .setAudioOffloadPreferences(offloadPreferences)
            .build()
        if (!enabled) player.clearVideoSurface()
    }

    override fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacks(progressTicker)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            player.removeListener(listener)
            player.release()
        } else {
            handler.post {
                player.removeListener(listener)
                player.release()
            }
        }
        mutableState.update { it.copy(mediaStatus = KMMediaStatus.Released) }
    }

    private fun action(block: () -> Unit): KMResult<Unit> {
        if (closed) return KMResult.Failure(KMPlayerError("released", "KMPlayer has been released"))
        return try {
            if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
            KMResult.Success(Unit)
        } catch (error: Throwable) {
            KMResult.Failure(KMPlayerError("media3", error.message ?: "Media3 operation failed", error))
        }
    }
}

internal actual fun createPlatformKMPlayerBackend(config: KMPlayerConfig): KMResult<KMPlayerBackend> {
    val context = KMPlayerAndroid.applicationContext
        ?: return KMResult.Failure(
            KMPlayerError(
                code = "android-context-unavailable",
                message = "Android context is not initialized. Keep KMPlayerInitProvider enabled or call KMPlayerAndroid.initialize(context).",
            ),
        )

    return try {
        val player = if (Looper.myLooper() == Looper.getMainLooper()) {
            ExoPlayer.Builder(context).setLooper(Looper.getMainLooper()).build()
        } else {
            var created: ExoPlayer? = null
            var failure: Throwable? = null
            val latch = java.util.concurrent.CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                try {
                    created = ExoPlayer.Builder(context).setLooper(Looper.getMainLooper()).build()
                } catch (error: Throwable) {
                    failure = error
                } finally {
                    latch.countDown()
                }
            }
            latch.await()
            failure?.let { throw it }
            checkNotNull(created)
        }
        KMResult.Success(
            AndroidKMPlayerBackend(
                player = player,
                videoAllowed = config.audioOnly != true,
                initialVideoEnabled = config.audioOnly == false,
            ),
        )
    } catch (error: Throwable) {
        KMResult.Failure(KMPlayerError("media3-init", error.message ?: "Unable to create Media3 player", error))
    }
}
