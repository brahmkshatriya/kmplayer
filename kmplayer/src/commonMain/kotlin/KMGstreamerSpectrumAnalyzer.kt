package dev.brahmkshatriya.kmplayer.internal

import dev.brahmkshatriya.kmplayer.KMAudioAnalyzer
import dev.brahmkshatriya.kmplayer.KMAudioAnalyzerConfig
import dev.brahmkshatriya.kmplayer.KMAudioFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.log10
import kotlin.math.pow

/** GStreamer's spectrum plugin produces dB frequency bins directly. */
internal class KMGstreamerSpectrumAnalyzer(
    private val config: KMAudioAnalyzerConfig,
    private val onClose: () -> Unit,
) : KMAudioAnalyzer {
    private val mutableFrames = MutableStateFlow<KMAudioFrame?>(null)
    override val frames: StateFlow<KMAudioFrame?> = mutableFrames
    internal val bandCount: Int get() = config.fftSize / 2
    private val previous = FloatArray(config.fftSize / 2)
    private var closed = false

    internal fun publish(sampleRateHz: Int, rawDb: FloatArray) {
        if (closed || sampleRateHz <= 0 || rawDb.size != previous.size) return
        val db = FloatArray(rawDb.size)
        for (i in rawDb.indices) {
            val amplitude = 10f.pow(rawDb[i].coerceIn(-120f, 0f) / 20f)
            val smoothed = (previous[i] * config.smoothing + amplitude * (1f - config.smoothing))
                .coerceAtLeast(1e-6f)
            previous[i] = smoothed
            db[i] = (20f * log10(smoothed)).coerceIn(-120f, 0f)
        }
        mutableFrames.value = KMAudioFrame(sampleRateHz, config.fftSize, db, null, null, null)
    }

    internal fun clear() {
        mutableFrames.value = null
        previous.fill(0f)
    }

    override fun close() {
        if (closed) return
        closed = true
        clear()
        onClose()
    }
}
