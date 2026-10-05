@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brahmkshatriya.kmplayer.internal

import cnames.structs.kmplayer_mf_player
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
import dev.brahmkshatriya.kmplayer.mediafoundation.KMPLAYER_MF_EVENT_BUFFERING_ENDED
import dev.brahmkshatriya.kmplayer.mediafoundation.KMPLAYER_MF_EVENT_BUFFERING_STARTED
import dev.brahmkshatriya.kmplayer.mediafoundation.KMPLAYER_MF_EVENT_ENDED
import dev.brahmkshatriya.kmplayer.mediafoundation.KMPLAYER_MF_EVENT_ERROR
import dev.brahmkshatriya.kmplayer.mediafoundation.KMPLAYER_MF_EVENT_NONE
import dev.brahmkshatriya.kmplayer.mediafoundation.KMPLAYER_MF_EVENT_READY
import dev.brahmkshatriya.kmplayer.mediafoundation.kmplayer_mf_player_create
import dev.brahmkshatriya.kmplayer.mediafoundation.kmplayer_mf_player_destroy
import dev.brahmkshatriya.kmplayer.mediafoundation.kmplayer_mf_player_duration_ms
import dev.brahmkshatriya.kmplayer.mediafoundation.kmplayer_mf_player_engine
import dev.brahmkshatriya.kmplayer.mediafoundation.kmplayer_mf_player_has_hls
import dev.brahmkshatriya.kmplayer.mediafoundation.kmplayer_mf_player_is_live
import dev.brahmkshatriya.kmplayer.mediafoundation.kmplayer_mf_player_is_seekable
import dev.brahmkshatriya.kmplayer.mediafoundation.kmplayer_mf_player_load
import dev.brahmkshatriya.kmplayer.mediafoundation.kmplayer_mf_player_pause
import dev.brahmkshatriya.kmplayer.mediafoundation.kmplayer_mf_player_play
import dev.brahmkshatriya.kmplayer.mediafoundation.kmplayer_mf_player_poll_event
import dev.brahmkshatriya.kmplayer.mediafoundation.kmplayer_mf_player_position_ms
import dev.brahmkshatriya.kmplayer.mediafoundation.kmplayer_mf_player_render_bgra
import dev.brahmkshatriya.kmplayer.mediafoundation.kmplayer_mf_player_seek_ms
import dev.brahmkshatriya.kmplayer.mediafoundation.kmplayer_mf_player_set_muted
import dev.brahmkshatriya.kmplayer.mediafoundation.kmplayer_mf_player_set_speed
import dev.brahmkshatriya.kmplayer.mediafoundation.kmplayer_mf_player_set_volume
import dev.brahmkshatriya.kmplayer.mediafoundation.kmplayer_mf_player_stop
import dev.brahmkshatriya.kmplayer.mediafoundation.kmplayer_mf_player_video_height
import dev.brahmkshatriya.kmplayer.mediafoundation.kmplayer_mf_player_video_width
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
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

internal class MediaFoundationKMPlayerBackend(
    private var handle: CPointer<kmplayer_mf_player>,
    hls: Boolean,
    private val videoAllowed: Boolean,
    initialVideoEnabled: Boolean,
) : KMPlayerBackend {
    private val mutableState = MutableStateFlow(KMPlayerState())
    private val mutableEvents = MutableSharedFlow<KMPlayerEvent>(extraBufferCapacity = 16)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var closed = false
    private var videoEnabled: Boolean = initialVideoEnabled
    private var pendingSeekMs: Long? = null
    private var resumeAfterReconfigure: Boolean = false

    override val state: StateFlow<KMPlayerState> = mutableState.asStateFlow()
    override val events: SharedFlow<KMPlayerEvent> = mutableEvents.asSharedFlow()
    override val capabilities: KMPlayerCapabilities = KMPlayerCapabilities(
        backend = KMBackendKind.MediaFoundation,
        video = videoAllowed,
        hls = hls,
        nativeVideoSurface = videoAllowed,
    )

    internal val engineHandle: ULong
        get() = kmplayer_mf_player_engine(handle).toULong()

    internal fun renderBgra(
        pixels: COpaquePointer,
        width: Int,
        height: Int,
        stride: Int,
        scale: KMVideoScale,
    ): Boolean = kmplayer_mf_player_render_bgra(
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
        val ok = kmplayer_mf_player_load(handle, source.uri.cstr.ptr, if (playWhenReady) 1 else 0)
        if (ok == 0) return@memScoped failure("media-foundation-load", "Media Foundation rejected the media source")
        mutableState.value = KMPlayerState(
            mediaStatus = KMMediaStatus.Opening,
            playWhenReady = playWhenReady,
            isBuffering = true,
            uri = source.uri,
        )
        KMResult.Success(Unit)
    }

    override fun play(): KMResult<Unit> = nativeAction("media-foundation-play") {
        kmplayer_mf_player_play(handle)
    }.also { result ->
        if (result is KMResult.Success) mutableState.update { it.copy(playWhenReady = true) }
    }

    override fun pause(): KMResult<Unit> = nativeAction("media-foundation-pause") {
        kmplayer_mf_player_pause(handle)
    }.also { result ->
        if (result is KMResult.Success) mutableState.update { it.copy(playWhenReady = false, isBuffering = false) }
    }

    override fun stop(): KMResult<Unit> = nativeAction("media-foundation-stop") {
        kmplayer_mf_player_stop(handle)
    }.also { result ->
        if (result is KMResult.Success) {
            mutableState.update {
                it.copy(
                    mediaStatus = KMMediaStatus.Idle,
                    playWhenReady = false,
                    isBuffering = false,
                    position = Duration.ZERO,
                )
            }
        }
    }

    override fun seekTo(position: Duration): KMResult<Unit> = nativeAction("media-foundation-seek") {
        kmplayer_mf_player_seek_ms(handle, position.inWholeMilliseconds.coerceAtLeast(0L))
    }

    override fun setVolume(volume: Double): KMResult<Unit> {
        if (!volume.isFinite() || volume !in 0.0..100.0) {
            return failure("invalid-volume", "volume must be between 0 and 100")
        }
        return nativeAction("media-foundation-volume") {
            kmplayer_mf_player_set_volume(handle, volume / 100.0)
        }.also { result ->
            if (result is KMResult.Success) mutableState.update { it.copy(volume = volume) }
        }
    }

    override fun setMuted(muted: Boolean): KMResult<Unit> = nativeAction("media-foundation-mute") {
        kmplayer_mf_player_set_muted(handle, if (muted) 1 else 0)
    }.also { result ->
        if (result is KMResult.Success) mutableState.update { it.copy(muted = muted) }
    }

    override fun setSpeed(speed: Double): KMResult<Unit> {
        if (!speed.isFinite() || speed <= 0.0) {
            return failure("invalid-speed", "speed must be a positive finite value")
        }
        return nativeAction("media-foundation-speed") {
            kmplayer_mf_player_set_speed(handle, speed)
        }.also { result ->
            if (result is KMResult.Success) mutableState.update { it.copy(speed = speed) }
        }
    }

    override fun setVideoEnabled(enabled: Boolean): KMResult<Unit> {
        if (enabled && !videoAllowed) return failure("video-disabled", "Video is disabled for this player.")
        if (videoEnabled == enabled) return KMResult.Success(Unit)

        val previous = handle
        val snapshot = mutableState.value
        val newHandle = createMediaFoundationHandle(audioOnly = !enabled)
            ?: return failure("media-foundation-video-mode", "Unable to recreate Media Foundation player for video mode change")

        videoEnabled = enabled
        handle = newHandle
        kmplayer_mf_player_set_volume(handle, snapshot.volume / 100.0)
        kmplayer_mf_player_set_muted(handle, if (snapshot.muted) 1 else 0)
        kmplayer_mf_player_set_speed(handle, snapshot.speed)

        val uri = snapshot.uri
        if (uri != null) {
            pendingSeekMs = snapshot.position.inWholeMilliseconds.coerceAtLeast(0L)
            resumeAfterReconfigure = snapshot.playWhenReady
            memScoped {
                kmplayer_mf_player_load(handle, uri.cstr.ptr, 0)
            }
            mutableState.update { it.copy(isBuffering = true, videoWidth = null, videoHeight = null) }
        }
        kmplayer_mf_player_destroy(previous)
        return KMResult.Success(Unit)
    }

    override fun close() {
        if (closed) return
        closed = true
        runBlocking { pollingJob.cancelAndJoin() }
        scope.cancel()
        kmplayer_mf_player_destroy(handle)
        mutableState.update {
            it.copy(mediaStatus = KMMediaStatus.Released, playWhenReady = false, isBuffering = false)
        }
    }

    private fun nativeAction(code: String, block: () -> Int): KMResult<Unit> {
        if (closed) return failure("released", "KMPlayer has been released")
        return if (block() != 0) KMResult.Success(Unit) else failure(code, "Media Foundation operation failed")
    }

    private fun refreshProgress() {
        val position = kmplayer_mf_player_position_ms(handle)
        val duration = kmplayer_mf_player_duration_ms(handle)
        val width = kmplayer_mf_player_video_width(handle)
        val height = kmplayer_mf_player_video_height(handle)
        mutableState.update {
            it.copy(
                position = position.coerceAtLeast(0L).milliseconds,
                duration = duration.takeIf { value -> value >= 0L }?.milliseconds,
                seekable = kmplayer_mf_player_is_seekable(handle) != 0,
                isLive = kmplayer_mf_player_is_live(handle) != 0,
                videoWidth = width.takeIf { value -> value > 0 },
                videoHeight = height.takeIf { value -> value > 0 },
            )
        }
    }

    private fun pollEvents() {
        repeat(16) {
            val message = ByteArray(1024)
            val event = message.usePinned { pinned ->
                kmplayer_mf_player_poll_event(handle, pinned.addressOf(0), message.size.toULong())
            }
            if (event == KMPLAYER_MF_EVENT_NONE.toInt()) return

            when (event) {
                KMPLAYER_MF_EVENT_READY.toInt() -> {
                    pendingSeekMs?.let { value ->
                        kmplayer_mf_player_seek_ms(handle, value)
                        pendingSeekMs = null
                    }
                    if (resumeAfterReconfigure) {
                        kmplayer_mf_player_play(handle)
                        resumeAfterReconfigure = false
                    }
                    mutableState.update { it.copy(mediaStatus = KMMediaStatus.Ready, isBuffering = false, error = null) }
                    mutableEvents.tryEmit(KMPlayerEvent.MediaLoaded)
                }
                KMPLAYER_MF_EVENT_ENDED.toInt() -> {
                    mutableState.update {
                        it.copy(mediaStatus = KMMediaStatus.Ended, playWhenReady = false, isBuffering = false)
                    }
                    mutableEvents.tryEmit(KMPlayerEvent.MediaEnded)
                }
                KMPLAYER_MF_EVENT_ERROR.toInt() -> {
                    val text = message.usePinned { it.addressOf(0).toKString() }
                    val error = KMPlayerError(
                        code = "media-foundation",
                        message = text.ifBlank { "Media Foundation playback failed" },
                    )
                    mutableState.update { it.copy(mediaStatus = KMMediaStatus.Error, isBuffering = false, error = error) }
                    mutableEvents.tryEmit(KMPlayerEvent.PlaybackError(error))
                }
                KMPLAYER_MF_EVENT_BUFFERING_STARTED.toInt() -> {
                    mutableState.update { it.copy(isBuffering = true) }
                }
                KMPLAYER_MF_EVENT_BUFFERING_ENDED.toInt() -> {
                    mutableState.update { it.copy(isBuffering = false) }
                }
            }
        }
    }

    private fun failure(code: String, message: String): KMResult.Failure =
        KMResult.Failure(KMPlayerError(code, message))
}

internal fun createMediaFoundationHandle(audioOnly: Boolean): CPointer<kmplayer_mf_player>? {
    val errorBuffer = ByteArray(1024)
    return errorBuffer.usePinned { pinned ->
        kmplayer_mf_player_create(
            if (audioOnly) 1 else 0,
            pinned.addressOf(0),
            errorBuffer.size.toULong(),
        )
    }
}

internal actual fun createPlatformKMPlayerBackend(config: KMPlayerConfig): KMResult<KMPlayerBackend> {
    val initialVideoEnabled = config.audioOnly == false
    val handle = createMediaFoundationHandle(audioOnly = !initialVideoEnabled)
        ?: return KMResult.Failure(
            KMPlayerError(
                code = "media-foundation-create",
                message = "Unable to create IMFMediaEngine. Windows N editions may require the Media Feature Pack.",
            ),
        )
    val hls = kmplayer_mf_player_has_hls(handle) != 0
    if (config.requireHls && !hls) {
        kmplayer_mf_player_destroy(handle)
        return KMResult.Failure(
            KMPlayerError(
                code = "hls-unavailable",
                message = "Windows Media Foundation is present, but this installation does not report HLS playback support.",
            ),
        )
    }
    return KMResult.Success(
        MediaFoundationKMPlayerBackend(
            handle = handle,
            hls = hls,
            videoAllowed = config.audioOnly != true,
            initialVideoEnabled = initialVideoEnabled,
        ),
    )
}
