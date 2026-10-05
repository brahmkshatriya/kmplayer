@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brahmkshatriya.kmplayer.internal

import dev.brahmkshatriya.kmplayer.KMBackendKind
import dev.brahmkshatriya.kmplayer.KMMediaSource
import dev.brahmkshatriya.kmplayer.KMMediaStatus
import dev.brahmkshatriya.kmplayer.KMPlayerCapabilities
import dev.brahmkshatriya.kmplayer.KMPlayerConfig
import dev.brahmkshatriya.kmplayer.KMPlayerError
import dev.brahmkshatriya.kmplayer.KMPlayerEvent
import dev.brahmkshatriya.kmplayer.KMPlayerState
import dev.brahmkshatriya.kmplayer.KMResult
import kotlinx.cinterop.useContents
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import platform.AVFoundation.*
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.removeObserver
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal class AvFoundationKMPlayerBackend internal constructor(
    internal val player: AVPlayer = AVPlayer(),
    private val videoAllowed: Boolean,
    initialVideoEnabled: Boolean,
) : KMPlayerBackend {
    private val mutableState = MutableStateFlow(KMPlayerState())
    private val mutableEvents = MutableSharedFlow<KMPlayerEvent>(extraBufferCapacity = 16)
    private var currentItem: AVPlayerItem? = null
    private var timeObserverToken: Any? = null
    private var endObserver: Any? = null
    private var failedObserver: Any? = null
    private var stalledObserver: Any? = null
    private var requestedVolume = 100.0
    private var muted = false
    private var speed = 1.0
    private var closed = false

    override val state: StateFlow<KMPlayerState> = mutableState.asStateFlow()
    override val events: SharedFlow<KMPlayerEvent> = mutableEvents.asSharedFlow()
    override val capabilities: KMPlayerCapabilities = KMPlayerCapabilities(
        backend = KMBackendKind.AvFoundation,
        video = videoAllowed,
        hls = true,
        nativeVideoSurface = videoAllowed,
    )
    private var videoEnabled: Boolean = initialVideoEnabled

    override suspend fun load(source: KMMediaSource, playWhenReady: Boolean): KMResult<Unit> {
        if (closed) return failure("released", "KMPlayer has been released")
        val url = NSURL(string = source.uri)
            ?: return failure("invalid-uri", "AVFoundation could not parse the media URI")

        cleanupObservers()
        val asset = AVURLAsset.URLAssetWithURL(url, null)
        val item = AVPlayerItem(asset)
        currentItem = item
        player.replaceCurrentItemWithPlayerItem(item)
        applyVideoMode(item)
        player.volume = if (muted) 0f else (requestedVolume / 100.0).toFloat()
        setupObservers(item)

        mutableState.value = KMPlayerState(
            mediaStatus = KMMediaStatus.Ready,
            playWhenReady = playWhenReady,
            isBuffering = playWhenReady,
            uri = source.uri,
            title = url.lastPathComponent,
            volume = requestedVolume,
            muted = muted,
            speed = speed,
        )
        mutableEvents.tryEmit(KMPlayerEvent.MediaLoaded)

        if (playWhenReady) player.playImmediatelyAtRate(speed.toFloat()) else player.pause()
        return KMResult.Success(Unit)
    }

    override fun play(): KMResult<Unit> = action {
        player.playImmediatelyAtRate(speed.toFloat())
        mutableState.update { it.copy(playWhenReady = true) }
    }

    override fun pause(): KMResult<Unit> = action {
        player.pause()
        mutableState.update { it.copy(playWhenReady = false, isBuffering = false) }
    }

    override fun stop(): KMResult<Unit> = action {
        player.pause()
        player.seekToTime(CMTimeMakeWithSeconds(0.0, 600))
        mutableState.update {
            it.copy(
                mediaStatus = KMMediaStatus.Idle,
                playWhenReady = false,
                isBuffering = false,
                position = Duration.ZERO,
            )
        }
    }

    override fun seekTo(position: Duration): KMResult<Unit> = action {
        player.seekToTime(CMTimeMakeWithSeconds(position.inWholeMilliseconds.coerceAtLeast(0L) / 1000.0, 600))
    }

    override fun setVolume(volume: Double): KMResult<Unit> {
        if (!volume.isFinite() || volume !in 0.0..100.0) {
            return failure("invalid-volume", "volume must be between 0 and 100")
        }
        requestedVolume = volume
        return action {
            player.volume = if (muted) 0f else (volume / 100.0).toFloat()
            mutableState.update { it.copy(volume = volume) }
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
            return failure("invalid-speed", "speed must be a positive finite value")
        }
        this.speed = speed
        return action {
            if (mutableState.value.playWhenReady) player.rate = speed.toFloat()
            mutableState.update { it.copy(speed = speed) }
        }
    }

    override fun setVideoEnabled(enabled: Boolean): KMResult<Unit> {
        if (enabled && !videoAllowed) return failure("video-disabled", "Video is disabled for this player.")
        videoEnabled = enabled
        currentItem?.let(::applyVideoMode)
        if (!enabled) mutableState.update { it.copy(videoWidth = null, videoHeight = null) }
        return KMResult.Success(Unit)
    }

    private fun applyVideoMode(item: AVPlayerItem) {
        item.tracks.forEach { rawTrack ->
            val track = rawTrack as? AVPlayerItemTrack ?: return@forEach
            val assetTrack = track.assetTrack
            if (assetTrack?.mediaType == AVMediaTypeVideo) track.enabled = videoEnabled
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        player.pause()
        cleanupObservers()
        player.replaceCurrentItemWithPlayerItem(null)
        currentItem = null
        mutableState.update {
            it.copy(mediaStatus = KMMediaStatus.Released, playWhenReady = false, isBuffering = false)
        }
    }

    private fun setupObservers(item: AVPlayerItem) {
        timeObserverToken = player.addPeriodicTimeObserverForInterval(
            interval = CMTimeMakeWithSeconds(0.25, 600),
            queue = null,
        ) { _ ->
            if (item.status == AVPlayerItemStatusReadyToPlay) {
                applyVideoMode(item)
                updateTiming(item)
                mutableState.update {
                    it.copy(
                        mediaStatus = KMMediaStatus.Ready,
                        isBuffering = player.timeControlStatus == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate,
                        seekable = true,
                        error = null,
                    )
                }
            }
        }

        endObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = AVPlayerItemDidPlayToEndTimeNotification,
            `object` = item,
            queue = NSOperationQueue.mainQueue,
        ) { _ ->
            mutableState.update {
                it.copy(mediaStatus = KMMediaStatus.Ended, playWhenReady = false, isBuffering = false)
            }
            mutableEvents.tryEmit(KMPlayerEvent.MediaEnded)
        }

        failedObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = AVPlayerItemFailedToPlayToEndTimeNotification,
            `object` = item,
            queue = NSOperationQueue.mainQueue,
        ) { _ ->
            val error = KMPlayerError("avfoundation", "AVFoundation playback failed")
            mutableState.update { it.copy(mediaStatus = KMMediaStatus.Error, isBuffering = false, error = error) }
            mutableEvents.tryEmit(KMPlayerEvent.PlaybackError(error))
        }

        stalledObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = AVPlayerItemPlaybackStalledNotification,
            `object` = item,
            queue = NSOperationQueue.mainQueue,
        ) { _ ->
            mutableState.update { it.copy(isBuffering = true) }
        }
    }

    private fun updateTiming(item: AVPlayerItem) {
        val positionSeconds = CMTimeGetSeconds(item.currentTime())
        val durationSeconds = CMTimeGetSeconds(item.duration)
        val position = positionSeconds.takeIf { it.isFinite() && it >= 0.0 }
        val duration = durationSeconds.takeIf { it.isFinite() && it > 0.0 }
        var width: Int? = null
        var height: Int? = null
        if (videoEnabled) {
            item.presentationSize.useContents {
                width = this.width.toInt().takeIf { it > 0 }
                height = this.height.toInt().takeIf { it > 0 }
            }
        }
        mutableState.update {
            it.copy(
                position = ((position ?: 0.0) * 1000.0).roundToLong().milliseconds,
                duration = duration?.let { value -> (value * 1000.0).roundToLong().milliseconds },
                videoWidth = width,
                videoHeight = height,
                isLive = duration == null && item.status == AVPlayerItemStatusReadyToPlay,
            )
        }
    }

    private fun cleanupObservers() {
        timeObserverToken?.let(player::removeTimeObserver)
        timeObserverToken = null

        endObserver?.let(NSNotificationCenter.defaultCenter::removeObserver)
        endObserver = null
        failedObserver?.let(NSNotificationCenter.defaultCenter::removeObserver)
        failedObserver = null
        stalledObserver?.let(NSNotificationCenter.defaultCenter::removeObserver)
        stalledObserver = null
    }

    private inline fun action(block: () -> Unit): KMResult<Unit> {
        if (closed) return failure("released", "KMPlayer has been released")
        return try {
            block()
            KMResult.Success(Unit)
        } catch (error: Throwable) {
            KMResult.Failure(KMPlayerError("avfoundation", error.message ?: "AVFoundation operation failed", error))
        }
    }

    private fun failure(code: String, message: String): KMResult.Failure =
        KMResult.Failure(KMPlayerError(code, message))
}

internal actual fun createPlatformKMPlayerBackend(config: KMPlayerConfig): KMResult<KMPlayerBackend> =
    KMResult.Success(
        AvFoundationKMPlayerBackend(
            videoAllowed = config.audioOnly != true,
            initialVideoEnabled = config.audioOnly == false,
        ),
    )
