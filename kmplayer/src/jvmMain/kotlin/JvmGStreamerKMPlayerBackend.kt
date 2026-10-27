package dev.brahmkshatriya.kmplayer.internal

import dev.brahmkshatriya.kmplayer.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.freedesktop.gstreamer.*
import org.freedesktop.gstreamer.elements.AppSink
import org.freedesktop.gstreamer.elements.PlayBin
import org.freedesktop.gstreamer.elements.PlayFlags
import org.freedesktop.gstreamer.event.SeekFlags
import org.freedesktop.gstreamer.event.SeekType
import org.freedesktop.gstreamer.message.*
import org.freedesktop.gstreamer.query.SeekingQuery
import org.freedesktop.gstreamer.lowlevel.GstAPI.GErrorStruct
import org.freedesktop.gstreamer.lowlevel.GstMessageAPI.GSTMESSAGE_API
import org.freedesktop.gstreamer.lowlevel.GlibAPI.GLIB_API
import com.sun.jna.ptr.PointerByReference
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Native calls are serialized; streaming callbacks only copy data and enqueue events. */
internal class JvmGStreamerKMPlayerBackend(
    internal val pipeline: PlayBin,
    private val sink: AppSink,
    private val spectrum: Element?,
    hls: Boolean,
    private val videoAllowed: Boolean,
) : JvmKMPlayerBackend {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableState = MutableStateFlow(KMPlayerState())
    private val mutableEvents = MutableSharedFlow<KMPlayerEvent>(extraBufferCapacity = 16)
    private val mutableFrames = MutableStateFlow<KMVideoFrame?>(null)
    override val videoFrames: StateFlow<KMVideoFrame?> = mutableFrames.asStateFlow()
    override val state: StateFlow<KMPlayerState> = mutableState.asStateFlow()
    override val events: SharedFlow<KMPlayerEvent> = mutableEvents.asSharedFlow()
    override val capabilities: KMPlayerCapabilities = KMPlayerCapabilities(
        backend = KMBackendKind.GStreamer, hls = hls, video = videoAllowed,
        nativeVideoSurface = videoAllowed,
    )
    @Volatile private var closed = false
    @Volatile private var acceptingEvents = false
    @Volatile private var generation = 0L
    @Volatile private var videoEnabled = false
    private var analyzer: KMGstreamerSpectrumAnalyzer? = null
    private var seekPending = false
    private val pendingEvents = Channel<Pair<Long, () -> Unit>>(Channel.UNLIMITED)
    private val bus = pipeline.bus
    private val samples = AppSink.NEW_SAMPLE { receiveFrame(it, false) }
    private val preroll = AppSink.NEW_PREROLL { receiveFrame(it, true) }

    init {
        sink.connect(samples)
        sink.connect(preroll)
        // Capture the generation on the posting thread, before another source can be loaded.
        // Never change pipeline state here: this can be a GStreamer streaming thread.
        bus.setSyncHandler { message ->
            try {
                if (acceptingEvents && !closed) {
                    val epoch = generation
                    decodeMessage(message)?.let { pendingEvents.trySend(epoch to it) }
                }
            } finally {
                message.dispose()
            }
            BusSyncReply.DROP
        }
        scope.launch {
            for ((epoch, event) in pendingEvents) synchronized(lock) {
                if (!closed && acceptingEvents && epoch == generation) event()
            }
        }
        scope.launch {
            while (isActive) {
                synchronized(lock) {
                    if (!closed && acceptingEvents) refreshProgress()
                }
                delay(100)
            }
        }
    }

    private fun decodeMessage(message: Message): (() -> Unit)? = when (message.type) {
        MessageType.ERROR -> {
            // Free the raw pointer: the bindings' GErrorStruct overload triggers
            // JNA's automatic structure read after g_error_free has freed it.
            val nativeError = PointerByReference()
            GSTMESSAGE_API.gst_message_parse_error(message, nativeError, null as PointerByReference?)
            val text = nativeError.value?.let { pointer ->
                try { GErrorStruct(pointer).getMessage() } finally { GLIB_API.g_error_free(pointer) }
            } ?: "GStreamer playback failed"
            val error = KMPlayerError("gstreamer", text)
            ({ if (mutableState.value.mediaStatus != KMMediaStatus.Error) {
                mutableState.update { it.copy(mediaStatus = KMMediaStatus.Error, isBuffering = false, playWhenReady = false, error = error) }
                mutableEvents.tryEmit(KMPlayerEvent.PlaybackError(error))
            } })
        }
        MessageType.EOS -> {
            { refreshProgress()
                mutableState.update { it.copy(mediaStatus = KMMediaStatus.Ended, playWhenReady = false, isBuffering = false) }
                mutableEvents.tryEmit(KMPlayerEvent.MediaEnded) }
        }
        MessageType.ASYNC_DONE -> ({ seekPending = false; ready() })
        MessageType.STATE_CHANGED -> {
            val changed = message as StateChangedMessage
            if (changed.source == pipeline && changed.newState in setOf(State.PAUSED, State.PLAYING)) ({ ready() }) else null
        }
        MessageType.BUFFERING -> {
            val percent = (message as BufferingMessage).percent
            { val buffering = percent < 100
                mutableState.update { it.copy(isBuffering = buffering) }
                if (mutableState.value.playWhenReady && !mutableState.value.isLive) {
                    pipeline.setState(if (buffering) State.PAUSED else State.PLAYING)
                }
            }
        }
        MessageType.TAG -> {
            val title = (message as TagMessage).tagList.use { tags ->
                if (tags.getValueCount("title") > 0) tags.getString("title", 0) else null
            }
            if (title == null) null else ({ mutableState.update { it.copy(title = title) } })
        }
        MessageType.ELEMENT -> {
            val structure = message.structure
            if (structure?.name != "spectrum") null else {
                val magnitudes = structure.getValues(Float::class.javaObjectType, "magnitude").toFloatArray()
                val rate = spectrum?.getStaticPad("sink")?.use { pad ->
                    pad.currentCaps?.use { it.getStructure(0).getInteger("rate") }
                } ?: 0
                { analyzer?.publish(rate, magnitudes) }
            }
        }
        else -> null
    }

    private fun ready() {
        if (mutableState.value.mediaStatus == KMMediaStatus.Opening) {
            mutableState.update { it.copy(mediaStatus = KMMediaStatus.Ready, isBuffering = false, error = null) }
            mutableEvents.tryEmit(KMPlayerEvent.MediaLoaded)
        }
        refreshProgress()
    }

    override suspend fun load(source: KMMediaSource, playWhenReady: Boolean): KMResult<Unit> = action("gstreamer-load") {
        val uri = runCatching { URI(source.uri).let { if (it.scheme == null) File(source.uri).toURI() else it } }
            .getOrElse { return@action failure("invalid-uri", "Invalid media URI: ${it.message}") }
        acceptingEvents = false
        generation++
        pipeline.stop()
        lastReadyStatus = KMMediaStatus.Idle
        seekPending = false
        mutableFrames.value = null
        analyzer?.clear()
        val previous = mutableState.value
        mutableState.value = KMPlayerState(
            mediaStatus = KMMediaStatus.Opening, uri = source.uri, playWhenReady = playWhenReady,
            isBuffering = true, volume = previous.volume, muted = previous.muted, speed = previous.speed,
        )
        pipeline.setURI(uri)
        acceptingEvents = true
        checkState(pipeline.setState(if (playWhenReady) State.PLAYING else State.PAUSED))
    }

    override fun play(): KMResult<Unit> = action("gstreamer-play") {
        val current = mutableState.value
        if (current.uri == null) return@action failure("no-source", "Load a media source before playing")
        if (current.mediaStatus == KMMediaStatus.Ended) {
            val seek = seek(Duration.ZERO)
            if (seek is KMResult.Failure) return@action seek
            mutableState.update { it.copy(mediaStatus = KMMediaStatus.Ready) }
        } else if (current.mediaStatus in setOf(KMMediaStatus.Idle, KMMediaStatus.Error)) {
            generation++
            acceptingEvents = true
            lastReadyStatus = KMMediaStatus.Idle
            mutableState.update { it.copy(mediaStatus = KMMediaStatus.Opening, error = null) }
        }
        val result = checkState(pipeline.play())
        if (result is KMResult.Success) mutableState.update { it.copy(playWhenReady = true) }
        result
    }

    override fun pause(): KMResult<Unit> = action("gstreamer-pause") {
        val result = checkState(pipeline.pause())
        if (result is KMResult.Success) mutableState.update { it.copy(playWhenReady = false, isBuffering = false) }
        result
    }

    override fun stop(): KMResult<Unit> = action("gstreamer-stop") {
        acceptingEvents = false
        generation++
        val result = checkState(pipeline.stop())
        analyzer?.clear()
        mutableFrames.value = null
        mutableState.update { it.copy(mediaStatus = KMMediaStatus.Idle, position = Duration.ZERO, playWhenReady = false, isBuffering = false) }
        result
    }

    override fun seekTo(position: Duration): KMResult<Unit> = action("gstreamer-seek") { seek(position) }

    private fun seek(position: Duration, speed: Double = mutableState.value.speed): KMResult<Unit> {
        val target = position.coerceAtLeast(Duration.ZERO)
        if (!pipeline.seek(speed, Format.TIME, setOf(SeekFlags.FLUSH, SeekFlags.ACCURATE), SeekType.SET,
                target.inWholeNanoseconds, SeekType.NONE, -1L)) return failure("gstreamer-seek", "GStreamer rejected the seek or playback rate")
        seekPending = true
        analyzer?.clear()
        mutableState.update { it.copy(position = target, speed = speed) }
        return KMResult.Success(Unit)
    }

    override fun setVolume(volume: Double): KMResult<Unit> = action("gstreamer-volume") {
        if (!volume.isFinite() || volume !in 0.0..100.0) return@action failure("invalid-volume", "volume must be between 0 and 100")
        pipeline.setVolume(volume / 100.0)
        mutableState.update { it.copy(volume = volume) }
        KMResult.Success(Unit)
    }

    override fun setMuted(muted: Boolean): KMResult<Unit> = action("gstreamer-mute") {
        pipeline.set("mute", muted)
        mutableState.update { it.copy(muted = muted) }
        KMResult.Success(Unit)
    }

    override fun setSpeed(speed: Double): KMResult<Unit> = action("gstreamer-speed") {
        if (!speed.isFinite() || speed <= 0) return@action failure("invalid-speed", "speed must be a positive finite value")
        if (mutableState.value.mediaStatus in setOf(KMMediaStatus.Idle, KMMediaStatus.Opening)) {
            mutableState.update { it.copy(speed = speed) }
            KMResult.Success(Unit)
        } else seek(pipeline.queryPosition(TimeUnit.MILLISECONDS).coerceAtLeast(0).milliseconds, speed)
    }

    override fun setVideoEnabled(enabled: Boolean): KMResult<Unit> = action("gstreamer-video-mode") {
        if (enabled && !videoAllowed) return@action failure("video-disabled", "Video is disabled for this player")
        val flags = pipeline.flags.toMutableSet()
        // A video-only source still needs a sink when its surface is detached.
        // Removing its only selected stream causes a not-linked pipeline error.
        val videoOnly = acceptingEvents && (pipeline.get("n-audio") as? Number)?.toInt() == 0 &&
            ((pipeline.get("n-video") as? Number)?.toInt() ?: 0) > 0
        if (enabled || videoOnly) flags.add(PlayFlags.VIDEO) else flags.remove(PlayFlags.VIDEO)
        flags.remove(PlayFlags.TEXT)
        pipeline.flags = flags
        videoEnabled = enabled
        if (!enabled) mutableFrames.value = null
        KMResult.Success(Unit)
    }

    override fun createAudioAnalyzer(config: KMAudioAnalyzerConfig): KMResult<KMAudioAnalyzer> = synchronized(lock) {
        if (closed) return failure("released", "KMPlayer has been released")
        val filter = spectrum ?: return failure("audio-analysis-unavailable", "GStreamer's spectrum plugin is not installed")
        if (analyzer != null) return failure("audio-analyzer-active", "Close the existing analyzer first")
        if (config.includeWaveform) return failure("audio-analysis-unavailable", "GStreamer's spectrum filter does not expose waveform samples")
        filter.set("bands", config.fftSize / 2)
        filter.set("interval", 1_000_000_000L / config.updateRateHz)
        val created = KMGstreamerSpectrumAnalyzer(config) {
            synchronized(lock) {
                if (!closed) filter.set("post-messages", false)
                analyzer = null
            }
        }
        analyzer = created
        filter.set("post-messages", true)
        KMResult.Success(created)
    }

    private fun receiveFrame(sink: AppSink, preroll: Boolean): FlowReturn {
        val epoch = generation
        val sample = (if (preroll) sink.pullPreroll() else sink.pullSample()) ?: return FlowReturn.OK
        sample.use {
            if (closed || !acceptingEvents || !videoEnabled) return FlowReturn.OK
            val structure = sample.caps.getStructure(0)
            val width = structure.getInteger("width")
            val height = structure.getInteger("height")
            val buffer = sample.buffer ?: return FlowReturn.OK
            buffer.use {
                val bytes = buffer.map(false) ?: return FlowReturn.OK
                try {
                    val size = width.toLong() * height * 4
                    if (size <= 0 || size > Int.MAX_VALUE || bytes.remaining() < size) return FlowReturn.OK
                    val frame = KMVideoFrame(width, height, ByteArray(size.toInt()).also(bytes::get))
                    if (!closed && acceptingEvents && videoEnabled && epoch == generation) mutableFrames.value = frame
                } finally { buffer.unmap() }
            }
        }
        return FlowReturn.OK
    }

    private fun refreshProgress() {
        val current = mutableState.value
        if (current.mediaStatus !in setOf(KMMediaStatus.Ready, KMMediaStatus.Opening)) return
        val position = pipeline.queryPosition(TimeUnit.MILLISECONDS)
        val duration = pipeline.queryDuration(TimeUnit.MILLISECONDS)
        val seekable = SeekingQuery(Format.TIME).use { pipeline.query(it) && it.isSeekable }
        val frame = if (videoEnabled) mutableFrames.value else null
        mutableState.update { it.copy(
            position = if (seekPending) it.position else position.coerceAtLeast(0).milliseconds,
            duration = duration.takeIf { it > 0 }?.milliseconds,
            seekable = seekable, videoWidth = frame?.width, videoHeight = frame?.height,
            isLive = duration < 0 && current.mediaStatus == KMMediaStatus.Ready,
        ) }
        if (current.mediaStatus == KMMediaStatus.Ready && current.speed != 1.0 && current.mediaStatus != lastReadyStatus) {
            seek(position.coerceAtLeast(0).milliseconds)
        }
        lastReadyStatus = current.mediaStatus
    }
    private var lastReadyStatus = KMMediaStatus.Idle

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            acceptingEvents = false
            generation++
            scope.cancel()
            pendingEvents.close()
            analyzer?.close()
            pipeline.stop()
            bus.clearSyncHandler()
            sink.disconnect(samples)
            sink.disconnect(preroll)
            bus.dispose()
            pipeline.dispose()
            sink.dispose()
            spectrum?.dispose()
            mutableFrames.value = null
            mutableState.update { it.copy(mediaStatus = KMMediaStatus.Released, playWhenReady = false, isBuffering = false) }
        }
    }

    private inline fun action(code: String, block: () -> KMResult<Unit>): KMResult<Unit> = synchronized(lock) {
        if (closed) return failure("released", "KMPlayer has been released")
        try { block() } catch (error: Exception) { failure(code, error.message ?: "GStreamer operation failed") }
    }

    private fun checkState(result: StateChangeReturn): KMResult<Unit> =
        if (result == StateChangeReturn.FAILURE) failure("gstreamer-state", "GStreamer rejected the state change") else KMResult.Success(Unit)
}

private val gstInitLock = Any()

internal fun createGStreamerKMPlayerBackend(config: KMPlayerConfig): KMResult<KMPlayerBackend> {
    var pipeline: PlayBin? = null
    var sink: AppSink? = null
    var spectrum: Element? = null
    fun cleanup() {
        runCatching { pipeline?.stop() }
        runCatching { pipeline?.dispose() }
        runCatching { sink?.dispose() }
        runCatching { spectrum?.dispose() }
    }
    try {
        synchronized(gstInitLock) { if (!Gst.isInitialized()) Gst.init("KMPlayer") }
        fun available(name: String): Boolean = ElementFactory.find(name)?.use { true } ?: false
        val hls = (available("hlsdemux2") || available("hlsdemux")) &&
            (available("souphttpsrc") || available("curlhttpsrc"))
        if (config.requireHls && !hls) return failure("hls-unavailable", "Install GStreamer HLS and HTTP source plugins, or set requireHls = false")
        pipeline = PlayBin("kmplayer")
        sink = AppSink("kmplayer-video").apply {
            Caps.fromString("video/x-raw,format=BGRA,pixel-aspect-ratio=1/1").use { caps = it }
            set("emit-signals", true)
            set("sync", true)
            set("max-buffers", 2)
            set("drop", true)
            set("enable-last-sample", false)
        }
        pipeline.setVideoSink(sink)
        if (available("spectrum")) {
            spectrum = ElementFactory.make("spectrum", "kmplayer-spectrum").apply {
                set("post-messages", false)
                set("threshold", -120)
            }
            pipeline.set("audio-filter", spectrum)
        }
        val backend = JvmGStreamerKMPlayerBackend(pipeline, sink, spectrum, hls, config.audioOnly != true)
        backend.setVideoEnabled(config.audioOnly == false)
        return KMResult.Success(backend)
    } catch (error: LinkageError) {
        cleanup()
        return failure("gstreamer-unavailable", "Install GStreamer and make its native libraries available to the JVM: ${error.message}")
    } catch (error: Exception) {
        cleanup()
        return failure("gstreamer-init", error.message ?: "Unable to initialize GStreamer")
    }
}

private fun failure(code: String, message: String): KMResult.Failure = KMResult.Failure(KMPlayerError(code, message))
