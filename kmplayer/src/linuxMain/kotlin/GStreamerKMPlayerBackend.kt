@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brahmkshatriya.kmplayer.internal

import cnames.structs.kmplayer_gst_player
import dev.brahmkshatriya.kmplayer.KMBackendKind
import dev.brahmkshatriya.kmplayer.KMMediaSource
import dev.brahmkshatriya.kmplayer.KMMediaStatus
import dev.brahmkshatriya.kmplayer.KMPlayerCapabilities
import dev.brahmkshatriya.kmplayer.KMPlayerConfig
import dev.brahmkshatriya.kmplayer.KMPlayerError
import dev.brahmkshatriya.kmplayer.KMPlayerEvent
import dev.brahmkshatriya.kmplayer.KMPlayerState
import dev.brahmkshatriya.kmplayer.KMResult
import dev.brahmkshatriya.kmplayer.KMVideoScale
import dev.brahmkshatriya.kmplayer.gstreamer.KMPLAYER_GST_EVENT_BUFFERING
import dev.brahmkshatriya.kmplayer.gstreamer.KMPLAYER_GST_EVENT_ENDED
import dev.brahmkshatriya.kmplayer.gstreamer.KMPLAYER_GST_EVENT_ERROR
import dev.brahmkshatriya.kmplayer.gstreamer.KMPLAYER_GST_EVENT_NONE
import dev.brahmkshatriya.kmplayer.gstreamer.KMPLAYER_GST_EVENT_READY
import dev.brahmkshatriya.kmplayer.gstreamer.KMPLAYER_GST_EVENT_TITLE
import dev.brahmkshatriya.kmplayer.gstreamer.kmplayer_gst_has_hls
import dev.brahmkshatriya.kmplayer.gstreamer.kmplayer_gst_init
import dev.brahmkshatriya.kmplayer.gstreamer.kmplayer_gst_player_create
import dev.brahmkshatriya.kmplayer.gstreamer.kmplayer_gst_player_destroy
import dev.brahmkshatriya.kmplayer.gstreamer.kmplayer_gst_player_duration_ms
import dev.brahmkshatriya.kmplayer.gstreamer.kmplayer_gst_player_is_seekable
import dev.brahmkshatriya.kmplayer.gstreamer.kmplayer_gst_player_load
import dev.brahmkshatriya.kmplayer.gstreamer.kmplayer_gst_player_pause
import dev.brahmkshatriya.kmplayer.gstreamer.kmplayer_gst_player_pipeline
import dev.brahmkshatriya.kmplayer.gstreamer.kmplayer_gst_player_play
import dev.brahmkshatriya.kmplayer.gstreamer.kmplayer_gst_player_poll_event
import dev.brahmkshatriya.kmplayer.gstreamer.kmplayer_gst_player_position_ms
import dev.brahmkshatriya.kmplayer.gstreamer.kmplayer_gst_player_render_bgra
import dev.brahmkshatriya.kmplayer.gstreamer.kmplayer_gst_player_seek_ms
import dev.brahmkshatriya.kmplayer.gstreamer.kmplayer_gst_player_set_muted
import dev.brahmkshatriya.kmplayer.gstreamer.kmplayer_gst_player_set_speed
import dev.brahmkshatriya.kmplayer.gstreamer.kmplayer_gst_player_set_video_enabled
import dev.brahmkshatriya.kmplayer.gstreamer.kmplayer_gst_player_set_volume
import dev.brahmkshatriya.kmplayer.gstreamer.kmplayer_gst_player_stop
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal class GStreamerKMPlayerBackend(
    private val handle: CPointer<kmplayer_gst_player>,
    hls: Boolean,
    private val videoAllowed: Boolean,
) : KMPlayerBackend {
    private val mutableState = MutableStateFlow(KMPlayerState())
    private val mutableEvents = MutableSharedFlow<KMPlayerEvent>(extraBufferCapacity = 16)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var closed = false

    override val state: StateFlow<KMPlayerState> = mutableState.asStateFlow()
    override val events: SharedFlow<KMPlayerEvent> = mutableEvents.asSharedFlow()
    override val capabilities: KMPlayerCapabilities = KMPlayerCapabilities(
        backend = KMBackendKind.GStreamer,
        video = videoAllowed,
        hls = hls,
        nativeVideoSurface = videoAllowed,
    )

    internal val pipelineHandle: ULong
        get() = kmplayer_gst_player_pipeline(handle).toULong()

    internal fun renderBgra(
        pixels: COpaquePointer,
        width: Int,
        height: Int,
        stride: Int,
        scale: KMVideoScale,
    ): Boolean = kmplayer_gst_player_render_bgra(
        handle,
        pixels,
        width,
        height,
        stride,
        scale.ordinal,
    ) != 0

    private val pollingJob: Job = scope.launch {
        while (isActive && !closed) {
            pollEvents()
            refreshProgress()
            delay(100)
        }
    }

    override suspend fun load(source: KMMediaSource, playWhenReady: Boolean): KMResult<Unit> = memScoped {
        if (closed) return@memScoped failure("released", "KMPlayer has been released")
        val ok = kmplayer_gst_player_load(handle, source.uri.cstr.getPointer(this), if (playWhenReady) 1 else 0)
        if (ok == 0) return@memScoped failure("gstreamer-load", "GStreamer rejected the media source")
        mutableState.value = KMPlayerState(
            mediaStatus = KMMediaStatus.Opening,
            playWhenReady = playWhenReady,
            isBuffering = true,
            uri = source.uri,
        )
        KMResult.Success(Unit)
    }

    override fun play(): KMResult<Unit> = nativeAction("gstreamer-play") {
        kmplayer_gst_player_play(handle)
    }.also { result ->
        if (result is KMResult.Success) mutableState.update { it.copy(playWhenReady = true) }
    }

    override fun pause(): KMResult<Unit> = nativeAction("gstreamer-pause") {
        kmplayer_gst_player_pause(handle)
    }.also { result ->
        if (result is KMResult.Success) mutableState.update { it.copy(playWhenReady = false) }
    }

    override fun stop(): KMResult<Unit> = nativeAction("gstreamer-stop") {
        kmplayer_gst_player_stop(handle)
    }.also { result ->
        if (result is KMResult.Success) {
            mutableState.update { it.copy(mediaStatus = KMMediaStatus.Idle, playWhenReady = false, isBuffering = false) }
        }
    }

    override fun seekTo(position: Duration): KMResult<Unit> = nativeAction("gstreamer-seek") {
        kmplayer_gst_player_seek_ms(handle, position.inWholeMilliseconds.coerceAtLeast(0L))
    }

    override fun setVolume(volume: Double): KMResult<Unit> {
        if (!volume.isFinite() || volume !in 0.0..100.0) return failure("invalid-volume", "volume must be between 0 and 100")
        return nativeAction("gstreamer-volume") {
            kmplayer_gst_player_set_volume(handle, volume / 100.0)
        }.also { result ->
            if (result is KMResult.Success) mutableState.update { it.copy(volume = volume) }
        }
    }

    override fun setMuted(muted: Boolean): KMResult<Unit> = nativeAction("gstreamer-mute") {
        kmplayer_gst_player_set_muted(handle, if (muted) 1 else 0)
    }.also { result ->
        if (result is KMResult.Success) mutableState.update { it.copy(muted = muted) }
    }

    override fun setSpeed(speed: Double): KMResult<Unit> {
        if (!speed.isFinite() || speed <= 0.0) return failure("invalid-speed", "speed must be a positive finite value")
        return nativeAction("gstreamer-speed") {
            kmplayer_gst_player_set_speed(handle, speed)
        }.also { result ->
            if (result is KMResult.Success) mutableState.update { it.copy(speed = speed) }
        }
    }

    override fun setVideoEnabled(enabled: Boolean): KMResult<Unit> {
        if (enabled && !videoAllowed) return failure("video-disabled", "Video is disabled for this player.")
        return nativeAction("gstreamer-video-mode") {
            kmplayer_gst_player_set_video_enabled(handle, if (enabled) 1 else 0)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runBlocking { pollingJob.cancelAndJoin() }
        scope.cancel()
        kmplayer_gst_player_destroy(handle)
        mutableState.update { it.copy(mediaStatus = KMMediaStatus.Released, playWhenReady = false, isBuffering = false) }
    }

    private fun nativeAction(code: String, block: () -> Int): KMResult<Unit> {
        if (closed) return failure("released", "KMPlayer has been released")
        return if (block() != 0) KMResult.Success(Unit) else failure(code, "GStreamer operation failed")
    }

    private fun refreshProgress() {
        val position = kmplayer_gst_player_position_ms(handle)
        val duration = kmplayer_gst_player_duration_ms(handle)
        val seekable = kmplayer_gst_player_is_seekable(handle) != 0
        mutableState.update {
            it.copy(
                position = position.coerceAtLeast(0L).milliseconds,
                duration = duration.takeIf { value -> value >= 0L }?.milliseconds,
                seekable = seekable,
                isLive = duration < 0L && it.mediaStatus == KMMediaStatus.Ready,
            )
        }
    }

    private fun pollEvents() {
        repeat(16) {
            val message = ByteArray(2048)
            val event = memScoped {
                val value = alloc<IntVar>()
                message.usePinned { pinned ->
                    val eventCode = kmplayer_gst_player_poll_event(
                        handle,
                        pinned.addressOf(0),
                        message.size.toULong(),
                        value.ptr,
                    )
                    eventCode to value.value
                }
            }
            if (event.first == KMPLAYER_GST_EVENT_NONE.toInt()) return

            when (event.first) {
                KMPLAYER_GST_EVENT_READY.toInt() -> {
                    mutableState.update { it.copy(mediaStatus = KMMediaStatus.Ready, isBuffering = false, error = null) }
                    mutableEvents.tryEmit(KMPlayerEvent.MediaLoaded)
                }
                KMPLAYER_GST_EVENT_ENDED.toInt() -> {
                    mutableState.update { it.copy(mediaStatus = KMMediaStatus.Ended, playWhenReady = false, isBuffering = false) }
                    mutableEvents.tryEmit(KMPlayerEvent.MediaEnded)
                }
                KMPLAYER_GST_EVENT_ERROR.toInt() -> {
                    val text = message.usePinned { it.addressOf(0).toKString() }
                    val error = KMPlayerError("gstreamer", text.ifBlank { "GStreamer playback failed" })
                    mutableState.update { it.copy(mediaStatus = KMMediaStatus.Error, isBuffering = false, error = error) }
                    mutableEvents.tryEmit(KMPlayerEvent.PlaybackError(error))
                }
                KMPLAYER_GST_EVENT_BUFFERING.toInt() -> {
                    mutableState.update { it.copy(isBuffering = event.second < 100) }
                }
                KMPLAYER_GST_EVENT_TITLE.toInt() -> {
                    val title = message.usePinned { it.addressOf(0).toKString() }
                    mutableState.update { it.copy(title = title.takeIf(String::isNotBlank)) }
                }
            }
        }
    }

    private fun failure(code: String, message: String): KMResult.Failure =
        KMResult.Failure(KMPlayerError(code, message))
}

internal actual fun createPlatformKMPlayerBackend(config: KMPlayerConfig): KMResult<KMPlayerBackend> {
    val errorBuffer = ByteArray(2048)
    val initialized = errorBuffer.usePinned { pinned ->
        kmplayer_gst_init(pinned.addressOf(0), errorBuffer.size.toULong())
    }
    if (initialized == 0) {
        val message = errorBuffer.usePinned { it.addressOf(0).toKString() }
        return KMResult.Failure(KMPlayerError("gstreamer-init", message.ifBlank { "Unable to initialize GStreamer" }))
    }

    val hls = kmplayer_gst_has_hls() != 0
    if (config.requireHls && !hls) {
        return KMResult.Failure(
            KMPlayerError(
                "hls-unavailable",
                "GStreamer is installed, but HLS requires playbin, hlsdemux/hlsdemux2, and an HTTP source plugin.",
            ),
        )
    }

    errorBuffer.fill(0)
    val handle = errorBuffer.usePinned { pinned ->
        kmplayer_gst_player_create(
            if (config.audioOnly == false) 0 else 1,
            pinned.addressOf(0),
            errorBuffer.size.toULong(),
        )
    } ?: run {
        val message = errorBuffer.usePinned { it.addressOf(0).toKString() }
        return KMResult.Failure(KMPlayerError("gstreamer-create", message.ifBlank { "Unable to create GStreamer playbin" }))
    }

    return KMResult.Success(GStreamerKMPlayerBackend(handle, hls, config.audioOnly != true))
}
