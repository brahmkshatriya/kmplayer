@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

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
import kotlinx.browser.document
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.w3c.dom.CanPlayTypeResult
import org.w3c.dom.EMPTY
import org.w3c.dom.HTMLMediaElement
import org.w3c.dom.HTMLVideoElement
import org.w3c.dom.events.Event
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal class WebKMPlayerBackend internal constructor(
    internal var media: HTMLMediaElement,
    private val videoAllowed: Boolean,
    initialVideoEnabled: Boolean,
) : KMPlayerBackend {
    private val mutableState = MutableStateFlow(KMPlayerState())
    private val mutableEvents = MutableSharedFlow<KMPlayerEvent>(extraBufferCapacity = 16)
    private val listeners = mutableListOf<Pair<String, (Event) -> Unit>>()
    private var hls: HlsJs? = null
    private var currentSource: KMMediaSource? = null
    private var videoEnabled: Boolean = initialVideoEnabled
    private var pendingSeekSeconds: Double? = null
    private var closed = false

    override val state: StateFlow<KMPlayerState> = mutableState.asStateFlow()
    override val events: SharedFlow<KMPlayerEvent> = mutableEvents.asSharedFlow()
    override val capabilities: KMPlayerCapabilities = KMPlayerCapabilities(
        backend = KMBackendKind.HtmlMedia,
        video = videoAllowed,
        hls = nativeHlsSupported() || createHlsJs()?.also { it.destroy() } != null,
        nativeVideoSurface = videoAllowed,
    )

    init {
        configureMediaElement()
    }

    override suspend fun load(source: KMMediaSource, playWhenReady: Boolean): KMResult<Unit> = actionResult {
        currentSource = source
        loadIntoMedia(source, playWhenReady)
    }

    override fun play(): KMResult<Unit> = action {
        media.play()
        mutableState.update { it.copy(playWhenReady = true) }
    }

    override fun pause(): KMResult<Unit> = action {
        media.pause()
        mutableState.update { it.copy(playWhenReady = false) }
    }

    override fun stop(): KMResult<Unit> = action {
        media.pause()
        if (media.seekable.length > 0) media.currentTime = 0.0
        mutableState.update { it.copy(mediaStatus = KMMediaStatus.Idle, playWhenReady = false) }
    }

    override fun seekTo(position: Duration): KMResult<Unit> = action {
        media.currentTime = position.inWholeMilliseconds.coerceAtLeast(0L).toDouble() / 1000.0
        refreshState()
    }

    override fun setVolume(volume: Double): KMResult<Unit> {
        if (!volume.isFinite() || volume !in 0.0..100.0) return fail("invalid-volume", "volume must be between 0 and 100")
        return action {
            media.volume = volume / 100.0
            mutableState.update { it.copy(volume = volume) }
        }
    }

    override fun setMuted(muted: Boolean): KMResult<Unit> = action {
        media.muted = muted
        mutableState.update { it.copy(muted = muted) }
    }

    override fun setSpeed(speed: Double): KMResult<Unit> {
        if (!speed.isFinite() || speed <= 0.0) return fail("invalid-speed", "speed must be a positive finite value")
        return action {
            media.playbackRate = speed
            mutableState.update { it.copy(speed = speed) }
        }
    }

    override fun setVideoEnabled(enabled: Boolean): KMResult<Unit> = actionResult {
        if (enabled && !videoAllowed) return@actionResult fail("video-disabled", "Video is disabled for this player.")
        if (videoEnabled == enabled) return@actionResult KMResult.Success(Unit)

        val old = media
        val source = currentSource
        val wasPlaying = mutableState.value.playWhenReady
        val oldPosition = old.currentTime.takeIf { it.isFinite() && it >= 0.0 }
        val volume = old.volume
        val muted = old.muted
        val speed = old.playbackRate

        removeListeners(old)
        hls?.destroy()
        hls = null
        old.pause()
        old.removeAttribute("src")
        old.load()

        videoEnabled = enabled
        media = createMediaElement(enabled) ?: return@actionResult fail("html-media", "Unable to create HTMLMediaElement")
        media.volume = volume
        media.muted = muted
        media.playbackRate = speed
        pendingSeekSeconds = oldPosition
        configureMediaElement()
        if (source != null) loadIntoMedia(source, wasPlaying) else KMResult.Success(Unit)
    }

    override fun close() {
        if (closed) return
        closed = true
        removeListeners(media)
        hls?.destroy()
        hls = null
        media.pause()
        media.removeAttribute("src")
        media.load()
        mutableState.update { it.copy(mediaStatus = KMMediaStatus.Released) }
    }

    private fun configureMediaElement() {
        media.preload = "metadata"
        listen("loadstart") { mutableState.update { it.copy(mediaStatus = KMMediaStatus.Opening, isBuffering = true, error = null) } }
        listen("loadedmetadata") {
            pendingSeekSeconds?.let { seconds ->
                if (media.seekable.length > 0 || media.duration.isFinite()) media.currentTime = seconds
                pendingSeekSeconds = null
            }
            refreshState()
        }
        listen("canplay") {
            refreshState()
            mutableState.update { it.copy(mediaStatus = KMMediaStatus.Ready, isBuffering = false) }
            mutableEvents.tryEmit(KMPlayerEvent.MediaLoaded)
        }
        listen("waiting") { mutableState.update { it.copy(isBuffering = true) } }
        listen("playing") { mutableState.update { it.copy(mediaStatus = KMMediaStatus.Ready, isBuffering = false, playWhenReady = true) } }
        listen("pause") { if (!media.ended) mutableState.update { it.copy(playWhenReady = false) } }
        listen("ended") {
            mutableState.update { it.copy(mediaStatus = KMMediaStatus.Ended, playWhenReady = false, isBuffering = false) }
            mutableEvents.tryEmit(KMPlayerEvent.MediaEnded)
        }
        listen("durationchange") { refreshState() }
        listen("progress") { refreshState() }
        listen("timeupdate") { refreshState() }
        listen("error") {
            val mediaError = media.error
            val error = KMPlayerError(
                code = "html-media-${mediaError?.code ?: 0}",
                message = "Browser media playback failed (media error ${mediaError?.code ?: 0})",
            )
            mutableState.update { it.copy(mediaStatus = KMMediaStatus.Error, isBuffering = false, error = error) }
            mutableEvents.tryEmit(KMPlayerEvent.PlaybackError(error))
        }
    }

    private fun loadIntoMedia(source: KMMediaSource, playWhenReady: Boolean): KMResult<Unit> {
        hls?.destroy()
        hls = null
        media.pause()
        media.removeAttribute("src")
        mutableState.update {
            it.copy(
                mediaStatus = KMMediaStatus.Opening,
                playWhenReady = playWhenReady,
                isBuffering = true,
                uri = source.uri,
                error = null,
            )
        }
        if (source.isHls && !nativeHlsSupported()) {
            val controller = createHlsJs()
                ?: return fail("hls-unavailable", "This browser has neither native HLS nor MediaSource support for hls.js")
            hls = controller
            controller.attachMedia(media)
            controller.loadSource(source.uri)
        } else {
            media.src = source.uri
            media.load()
        }
        if (playWhenReady) media.play()
        return KMResult.Success(Unit)
    }

    private fun nativeHlsSupported(): Boolean =
        media.canPlayType("application/vnd.apple.mpegurl") != CanPlayTypeResult.EMPTY ||
            media.canPlayType("application/x-mpegURL") != CanPlayTypeResult.EMPTY

    private fun refreshState() {
        if (closed) return
        val duration = media.duration.takeIf { it.isFinite() && it >= 0.0 }
        val video = media as? HTMLVideoElement
        mutableState.update {
            it.copy(
                position = (media.currentTime.coerceAtLeast(0.0) * 1000.0).roundToLong().milliseconds,
                duration = duration?.let { seconds -> (seconds * 1000.0).roundToLong().milliseconds },
                volume = media.volume * 100.0,
                muted = media.muted,
                speed = media.playbackRate,
                seekable = media.seekable.length > 0,
                videoWidth = video?.videoWidth?.takeIf { width -> width > 0 },
                videoHeight = video?.videoHeight?.takeIf { height -> height > 0 },
                isLive = media.duration.isInfinite(),
            )
        }
    }

    private fun listen(type: String, block: (Event) -> Unit) {
        val listener: (Event) -> Unit = { event -> block(event) }
        media.addEventListener(type, listener)
        listeners += type to listener
    }

    private fun removeListeners(element: HTMLMediaElement) {
        listeners.forEach { (type, listener) -> element.removeEventListener(type, listener) }
        listeners.clear()
    }

    private inline fun actionResult(block: () -> KMResult<Unit>): KMResult<Unit> {
        if (closed) return fail("released", "KMPlayer has been released")
        return try { block() } catch (error: Throwable) {
            fail("html-media", error.message ?: "Browser media operation failed", error)
        }
    }

    private inline fun action(block: () -> Unit): KMResult<Unit> {
        if (closed) return fail("released", "KMPlayer has been released")
        return try {
            block()
            KMResult.Success(Unit)
        } catch (error: Throwable) {
            fail("html-media", error.message ?: "Browser media operation failed", error)
        }
    }

    private fun fail(code: String, message: String, cause: Throwable? = null): KMResult.Failure =
        KMResult.Failure(KMPlayerError(code, message, cause))
}

private fun createMediaElement(video: Boolean): HTMLMediaElement? =
    document.createElement(if (video) "video" else "audio") as? HTMLMediaElement

internal actual fun createPlatformKMPlayerBackend(config: KMPlayerConfig): KMResult<KMPlayerBackend> {
    val videoEnabled = config.audioOnly == false
    val media = createMediaElement(videoEnabled)
        ?: return KMResult.Failure(KMPlayerError("html-media", "Unable to create HTMLMediaElement"))
    return KMResult.Success(
        WebKMPlayerBackend(
            media = media,
            videoAllowed = config.audioOnly != true,
            initialVideoEnabled = videoEnabled,
        ),
    )
}
