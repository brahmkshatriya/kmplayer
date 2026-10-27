package dev.brahmkshatriya.kmplayer.internal

import dev.brahmkshatriya.kmplayer.KMAudioAnalyzer
import dev.brahmkshatriya.kmplayer.KMAudioAnalyzerConfig
import dev.brahmkshatriya.kmplayer.KMAudioFrame
import dev.brahmkshatriya.kmplayer.KMPlayerError
import dev.brahmkshatriya.kmplayer.KMResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLMediaElement

/** Platform interop abstracts AudioContext/AnalyserNode differences in JS and Wasm. */
internal interface WebSpectrumReader : AutoCloseable {
    fun snapshot(includeWaveform: Boolean): KMAudioFrame?
    override fun close()
}

internal expect fun openWebSpectrumReader(
    media: HTMLMediaElement,
    config: KMAudioAnalyzerConfig,
): KMResult<WebSpectrumReader>

/** Disconnect and close the persistent Web Audio routing when the media element is retired. */
internal expect fun releaseWebAudioMedia(media: HTMLMediaElement)

/** Resume a previously analyzed media element inside a user-initiated play action. */
internal expect fun resumeWebAudioMedia(media: HTMLMediaElement)

internal class WebAudioAnalyzer(
    private val config: KMAudioAnalyzerConfig,
    private val onClose: () -> Unit,
    private var reader: WebSpectrumReader,
) : KMAudioAnalyzer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableFrames = MutableStateFlow<KMAudioFrame?>(null)
    override val frames: StateFlow<KMAudioFrame?> = mutableFrames
    private var closed = false

    init {
        scope.launch {
            while (isActive && !closed) {
                mutableFrames.value = reader.snapshot(config.includeWaveform)
                delay((1000L / config.updateRateHz).coerceAtLeast(1L))
            }
        }
    }

    /** Reattach when kmplayer replaces its HTMLMediaElement for a video surface. */
    fun reattach(media: HTMLMediaElement) {
        reader.close()
        mutableFrames.value = null
        when (val reopened = openWebSpectrumReader(media, config)) {
            is KMResult.Success -> reader = reopened.value
            is KMResult.Failure -> close()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        reader.close()
        mutableFrames.value = null
        onClose()
    }
}
