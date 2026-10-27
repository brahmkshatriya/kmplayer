package dev.brahmkshatriya.kmplayer.internal

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import dev.brahmkshatriya.kmplayer.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.net.URI
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** All native calls, including creation, rendering and disposal, share one owner thread. */
internal class JvmNativeKMPlayerBackend private constructor(
    private val config: KMPlayerConfig,
    private val bridge: JvmNativePlayerBridge,
    private val kind: KMBackendKind,
) : JvmKMPlayerBackend {
    @Volatile private var ownerThread: Thread? = null
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "KMPlayer-$kind").apply { isDaemon = true; ownerThread = this }
    }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val closed = AtomicBoolean(false)
    private var handle: Pointer? = null
    private val mutableState = MutableStateFlow(KMPlayerState())
    private val mutableEvents = MutableSharedFlow<KMPlayerEvent>(extraBufferCapacity = 16)
    private val mutableFrames = MutableStateFlow<KMVideoFrame?>(null)
    override val state: StateFlow<KMPlayerState> = mutableState.asStateFlow()
    override val events: SharedFlow<KMPlayerEvent> = mutableEvents.asSharedFlow()
    override val videoFrames: StateFlow<KMVideoFrame?> = mutableFrames.asStateFlow()
    override lateinit var capabilities: KMPlayerCapabilities
        private set
    private var videoEnabled = config.audioOnly == false
    private var analyzer: KMPcmSpectrumAnalyzer? = null
    private var frameMemory: Memory? = null
    private var frameWidth = 0
    private var frameHeight = 0
    private var sourceLoaded = false
    private var pendingRestore: Duration? = null
    private var pendingSeek: Pair<Long, Long>? = null
    private var nextProgressNanos = 0L
    private var nextAudioNanos = 0L
    private val errorBytes = ByteArray(2048)
    private val errorSize = JvmSizeT(errorBytes.size.toLong())
    private val pcm = FloatArray(8192)
    private val sampleRate = IntByReference()

    internal val nativeHandle: Long?
        get() = if (closed.get()) null else runCatching {
            execute { handle?.takeUnless { closed.get() }?.let(bridge::player_native_handle)?.let(Pointer::nativeValue) }
        }.getOrNull()

    private fun initialize(): KMResult<Unit> = action {
        val created = newHandle() ?: return@action failure("native-player-create", nativeError())
        handle = created
        val hls = bridge.player_has_hls(created) != 0
        if (config.requireHls && !hls) return@action failure("hls-unavailable",
            "This $kind installation does not report HLS playback support. Windows N editions may require the Media Feature Pack.")
        capabilities = KMPlayerCapabilities(kind, video = config.audioOnly != true, hls = hls,
            nativeVideoSurface = config.audioOnly != true)
        scope.launch {
            while (isActive && !closed.get()) {
                try {
                    handle?.let { player ->
                        val now = System.nanoTime()
                        if (now >= nextProgressNanos) {
                            pollEvents(player)
                            refreshProgress(player)
                            nextProgressNanos = now + 100_000_000L
                        }
                        if (analyzer != null && now >= nextAudioNanos) {
                            captureAudio(player)
                            nextAudioNanos = now + 25_000_000L
                        }
                        if (videoEnabled && sourceLoaded) captureVideo(player)
                    }
                } catch (error: Exception) { reportError("native-playback", error.message ?: "Native playback failed") }
                delay(if (videoEnabled && sourceLoaded) 16 else if (analyzer != null) 25 else 100)
            }
        }
        KMResult.Success(Unit)
    }

    private fun newHandle(): Pointer? {
        errorBytes.fill(0)
        return bridge.player_create(if (videoEnabled) 0 else 1, errorBytes, errorSize)
    }

    override suspend fun load(source: KMMediaSource, playWhenReady: Boolean): KMResult<Unit> {
        if (closed.get()) return released()
        return withContext(dispatcher) { loadSource(source, playWhenReady) }
    }

    private fun loadSource(source: KMMediaSource, playWhenReady: Boolean): KMResult<Unit> = action {
        val uri = runCatching { URI(source.uri).let { if (it.scheme == null) File(source.uri).toURI().toString() else it.toString() } }
            .getOrElse { return@action failure("invalid-uri", "Invalid media URI: ${it.message}") }
        // A fresh engine gives every source its own callback queue. Late completion/error
        // notifications from the old source cannot be attributed to the new source.
        val next = newHandle() ?: return@action failure("native-player-create", nativeError())
        val previous = mutableState.value
        val configured = configure(next, previous)
        if (configured is KMResult.Failure || bridge.player_load(next, uri, 0) == 0) {
            bridge.player_destroy(next)
            return@action if (configured is KMResult.Failure) configured else failure("native-load", "$kind rejected the media source")
        }
        val old = handle
        handle = next
        old?.let(bridge::player_destroy)
        analyzer?.reset()
        sourceLoaded = false
        pendingRestore = null
        pendingSeek = null
        mutableFrames.value = null
        mutableState.value = KMPlayerState(mediaStatus = KMMediaStatus.Opening, uri = source.uri,
            playWhenReady = playWhenReady, isBuffering = true,
            volume = previous.volume, muted = previous.muted, speed = previous.speed)
        nextProgressNanos = 0
        KMResult.Success(Unit)
    }

    private fun configure(player: Pointer, state: KMPlayerState): KMResult<Unit> {
        if (bridge.player_set_volume(player, state.volume / 100.0) == 0 ||
            bridge.player_set_muted(player, if (state.muted) 1 else 0) == 0) {
            return failure("native-settings", "$kind rejected the volume or mute setting")
        }
        if (analyzer != null && bridge.player_set_audio_analysis(player, 1) == 0) {
            return failure("audio-analysis-unavailable", "Could not restore the native audio sample tap")
        }
        return KMResult.Success(Unit)
    }

    override fun play(): KMResult<Unit> = action {
        val player = handle ?: return@action released()
        val state = mutableState.value
        if (state.uri == null) return@action failure("no-source", "Load a media source before playing")
        if (state.mediaStatus == KMMediaStatus.Ended) {
            if (bridge.player_seek_ms(player, 0) == 0) return@action failure("native-seek", "$kind rejected the seek")
        }
        if (state.mediaStatus == KMMediaStatus.Opening) {
            mutableState.update { it.copy(playWhenReady = true) }
            return@action KMResult.Success(Unit)
        }
        val result = result(bridge.player_play(player), "play")
        if (result is KMResult.Success) mutableState.update { it.copy(playWhenReady = true,
            mediaStatus = KMMediaStatus.Ready, isBuffering = false) }
        result
    }

    override fun pause(): KMResult<Unit> = action {
        val result = result(bridge.player_pause(handle!!), "pause")
        if (result is KMResult.Success) mutableState.update { it.copy(playWhenReady = false, isBuffering = false) }
        result
    }

    override fun stop(): KMResult<Unit> = action {
        val result = result(if (sourceLoaded) bridge.player_stop(handle!!) else bridge.player_pause(handle!!), "stop")
        if (result is KMResult.Success) {
            analyzer?.reset()
            pendingSeek = null
            pendingRestore = if (sourceLoaded) null else Duration.ZERO
            mutableFrames.value = null
            mutableState.update { it.copy(mediaStatus = KMMediaStatus.Idle, position = Duration.ZERO,
                playWhenReady = false, isBuffering = false) }
        }
        result
    }

    override fun seekTo(position: Duration): KMResult<Unit> = action {
        if (mutableState.value.uri == null) return@action failure("no-source", "Load a media source before seeking")
        val target = position.coerceAtLeast(Duration.ZERO).inWholeMilliseconds
        if (!sourceLoaded) {
            pendingRestore = target.milliseconds
            mutableState.update { it.copy(position = target.milliseconds) }
            return@action KMResult.Success(Unit)
        }
        seek(target)
    }

    private fun seek(target: Long): KMResult<Unit> {
        val result = result(bridge.player_seek_ms(handle!!, target), "seek")
        if (result is KMResult.Success) {
            analyzer?.reset()
            pendingSeek = target to System.nanoTime()
            mutableState.update { it.copy(position = target.milliseconds) }
        }
        return result
    }

    override fun setVolume(volume: Double): KMResult<Unit> = action {
        if (!volume.isFinite() || volume !in 0.0..100.0) return@action failure("invalid-volume", "volume must be between 0 and 100")
        val result = result(bridge.player_set_volume(handle!!, volume / 100.0), "volume")
        if (result is KMResult.Success) mutableState.update { it.copy(volume = volume) }
        result
    }

    override fun setMuted(muted: Boolean): KMResult<Unit> = action {
        val result = result(bridge.player_set_muted(handle!!, if (muted) 1 else 0), "mute")
        if (result is KMResult.Success) mutableState.update { it.copy(muted = muted) }
        result
    }

    override fun setSpeed(speed: Double): KMResult<Unit> = action {
        if (!speed.isFinite() || speed <= 0) return@action failure("invalid-speed", "speed must be a positive finite value")
        val result = if (sourceLoaded) result(bridge.player_set_speed(handle!!, speed), "speed") else KMResult.Success(Unit)
        if (result is KMResult.Success) mutableState.update { it.copy(speed = speed) }
        result
    }

    override fun setVideoEnabled(enabled: Boolean): KMResult<Unit> = action {
        if (enabled && config.audioOnly == true) return@action failure("video-disabled", "Video is disabled for this player")
        if (enabled == videoEnabled) return@action KMResult.Success(Unit)
        if (bridge is JvmAvPlayerBridge) {
            val result = result(bridge.player_set_video_enabled(handle!!, if (enabled) 1 else 0), "video-mode")
            if (result is KMResult.Success) {
                videoEnabled = enabled
                if (!enabled) {
                    mutableFrames.value = null
                    mutableState.update { it.copy(videoWidth = null, videoHeight = null) }
                }
                nextProgressNanos = 0
            }
            return@action result
        }
        val snapshot = mutableState.value
        videoEnabled = enabled
        val next = newHandle()
        if (next == null) { videoEnabled = !enabled; return@action failure("native-video-mode", nativeError()) }
        val settings = configure(next, snapshot)
        val uri = snapshot.uri
        if (settings is KMResult.Failure || (uri != null && bridge.player_load(next, uri, 0) == 0)) {
            bridge.player_destroy(next)
            videoEnabled = !enabled
            return@action if (settings is KMResult.Failure) settings else failure("native-video-mode", "Could not restore the source after changing video mode")
        }
        val old = handle
        handle = next
        old?.let(bridge::player_destroy)
        analyzer?.reset()
        pendingRestore = if (uri != null && snapshot.seekable) snapshot.position else null
        if (uri != null) mutableState.update { it.copy(isBuffering = true, videoWidth = null, videoHeight = null) }
        if (!enabled) mutableFrames.value = null
        nextProgressNanos = 0
        KMResult.Success(Unit)
    }

    override fun createAudioAnalyzer(config: KMAudioAnalyzerConfig): KMResult<KMAudioAnalyzer> = action {
        if (analyzer != null) return@action failure("audio-analyzer-active", "Close the existing analyzer first")
        if (bridge.player_set_audio_analysis(handle!!, 1) == 0) return@action failure("audio-analysis-unavailable",
            "Decoded audio analysis is unavailable on this $kind player")
        val created = KMPcmSpectrumAnalyzer(config) {
            if (!closed.get()) execute {
                handle?.let { bridge.player_set_audio_analysis(it, 0) }
                analyzer = null
            }
        }
        analyzer = created
        KMResult.Success(created)
    }

    private fun pollEvents(player: Pointer) {
        repeat(16) {
            errorBytes.fill(0)
            when (bridge.player_poll_event(player, errorBytes, errorSize)) {
                0 -> return
                1 -> {
                    val saved = mutableState.value
                    val firstLoad = !sourceLoaded
                    sourceLoaded = true
                    if (bridge.player_set_speed(player, saved.speed) == 0) {
                        reportError("native-speed", "$kind rejected the playback rate")
                        return@repeat
                    }
                    pendingRestore?.let {
                        val seek = seek(it.inWholeMilliseconds)
                        pendingRestore = null
                        if (seek is KMResult.Failure) {
                            reportError(seek.error.code, seek.error.message)
                            return@repeat
                        }
                    }
                    if (saved.mediaStatus == KMMediaStatus.Idle) return@repeat
                    if (saved.playWhenReady && bridge.player_play(player) == 0) reportError("native-play", "$kind could not start playback")
                    if (mutableState.value.mediaStatus != KMMediaStatus.Error) {
                        mutableState.update { it.copy(mediaStatus = KMMediaStatus.Ready, isBuffering = false, error = null) }
                        if (firstLoad) mutableEvents.tryEmit(KMPlayerEvent.MediaLoaded)
                    }
                }
                2 -> {
                    if (mutableState.value.mediaStatus in setOf(KMMediaStatus.Idle, KMMediaStatus.Error, KMMediaStatus.Ended)) return@repeat
                    refreshProgress(player)
                    mutableState.update { it.copy(mediaStatus = KMMediaStatus.Ended, playWhenReady = false, isBuffering = false) }
                    mutableEvents.tryEmit(KMPlayerEvent.MediaEnded)
                }
                3 -> reportError("native-playback", nativeError())
                4 -> mutableState.update { it.copy(isBuffering = true) }
                5 -> mutableState.update { it.copy(isBuffering = false) }
            }
        }
    }

    private fun refreshProgress(player: Pointer) {
        if (mutableState.value.mediaStatus !in setOf(KMMediaStatus.Ready, KMMediaStatus.Opening)) return
        val position = bridge.player_position_ms(player).coerceAtLeast(0)
        val pending = pendingSeek
        val seeking = pending != null && kotlin.math.abs(position - pending.first) > 500 && System.nanoTime() - pending.second < 5_000_000_000L
        if (!seeking) pendingSeek = null
        mutableState.update { it.copy(
            position = if (seeking || pendingRestore != null) it.position else position.milliseconds,
            duration = bridge.player_duration_ms(player).takeIf { it > 0 }?.milliseconds,
            bufferedPosition = bridge.player_buffered_end_ms(player).takeIf { it >= 0 }?.milliseconds,
            seekable = bridge.player_is_seekable(player) != 0,
            isLive = sourceLoaded && bridge.player_is_live(player) != 0,
            videoWidth = if (videoEnabled) bridge.player_video_width(player).takeIf { it > 0 } else null,
            videoHeight = if (videoEnabled) bridge.player_video_height(player).takeIf { it > 0 } else null,
        ) }
    }

    private fun captureAudio(player: Pointer) {
        val active = analyzer ?: return
        val count = bridge.player_copy_audio_samples(player, pcm, pcm.size, sampleRate)
        if (count in 1..pcm.size && sampleRate.value > 0) active.offerOwned(pcm.copyOf(count), sampleRate.value)
    }

    private fun captureVideo(player: Pointer) {
        val width = mutableState.value.videoWidth ?: return
        val height = mutableState.value.videoHeight ?: return
        val bytes = width.toLong() * height * 4
        if (width <= 0 || height <= 0 || bytes > 256L * 1024 * 1024) return
        if (frameWidth != width || frameHeight != height) {
            frameMemory?.close()
            frameMemory = Memory(bytes)
            frameWidth = width
            frameHeight = height
        }
        val memory = frameMemory ?: return
        if (bridge.player_copy_video_frame(player, memory, width, height, width * 4) != 0) {
            mutableFrames.value = KMVideoFrame(width, height, memory.getByteArray(0, bytes.toInt()))
        }
    }

    private fun reportError(code: String, message: String) {
        if (mutableState.value.mediaStatus == KMMediaStatus.Error) return
        val error = KMPlayerError(code, message)
        mutableState.update { it.copy(mediaStatus = KMMediaStatus.Error, playWhenReady = false, isBuffering = false, error = error) }
        mutableEvents.tryEmit(KMPlayerEvent.PlaybackError(error))
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
        try {
            execute {
                analyzer?.close(); analyzer = null
                handle?.let(bridge::player_destroy); handle = null
                frameMemory?.close(); frameMemory = null
                mutableFrames.value = null
                mutableState.update { it.copy(mediaStatus = KMMediaStatus.Released, playWhenReady = false, isBuffering = false) }
            }
        } finally { dispatcher.close() }
    }

    private fun <T> execute(block: () -> T): T {
        if (Thread.currentThread() === ownerThread) return block()
        try { return executor.submit(Callable(block)).get() }
        catch (error: ExecutionException) { throw error.cause ?: error }
    }

    private fun <T> action(block: () -> KMResult<T>): KMResult<T> {
        if (closed.get()) return released()
        return try { execute { if (closed.get()) released() else block() } }
        catch (error: RejectedExecutionException) { released() }
        catch (error: Exception) { failure("native-player", error.message ?: "$kind operation failed") }
    }

    private fun nativeError(): String = errorBytes.takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8).ifBlank { "$kind operation failed" }
    private fun result(value: Int, operation: String): KMResult<Unit> = if (value != 0) KMResult.Success(Unit) else failure("native-$operation", "$kind rejected $operation")

    companion object {
        internal fun create(config: KMPlayerConfig, kind: KMBackendKind): KMResult<KMPlayerBackend> {
            var backend: JvmNativeKMPlayerBackend? = null
            return try {
                val bridge = JvmNativePlayerLibraries.load(kind)
                backend = JvmNativeKMPlayerBackend(config, bridge, kind)
                when (val initialized = backend.initialize()) {
                    is KMResult.Success -> KMResult.Success(backend)
                    is KMResult.Failure -> { backend.close(); initialized }
                }
            } catch (error: LinkageError) {
                backend?.close()
                failure("native-library-unavailable", "Could not load the $kind bridge: ${error.message}")
            } catch (error: Exception) {
                backend?.close()
                failure("native-player-create", error.message ?: "Could not create $kind player")
            }
        }
    }
}

private fun failure(code: String, message: String): KMResult.Failure = KMResult.Failure(KMPlayerError(code, message))
private fun released(): KMResult.Failure = failure("released", "KMPlayer has been released")
