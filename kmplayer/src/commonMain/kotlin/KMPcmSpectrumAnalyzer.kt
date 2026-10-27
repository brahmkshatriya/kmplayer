package dev.brahmkshatriya.kmplayer.internal

import dev.brahmkshatriya.kmplayer.KMAudioAnalyzer
import dev.brahmkshatriya.kmplayer.KMAudioAnalyzerConfig
import dev.brahmkshatriya.kmplayer.KMAudioFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/** Receives small PCM chunks; FFT work runs on a separate, conflated worker. */
internal class KMPcmSpectrumAnalyzer(
    private val config: KMAudioAnalyzerConfig,
    private val onClose: () -> Unit,
) : KMAudioAnalyzer {
    private val mutableFrames = MutableStateFlow<KMAudioFrame?>(null)
    override val frames: StateFlow<KMAudioFrame?> = mutableFrames
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private data class Chunk(val generation: Int, val sampleRateHz: Int, val samples: FloatArray)
    private val pending = Channel<Chunk>(Channel.CONFLATED)
    @Volatile private var stopped = false
    @Volatile private var generation = 0
    private var observedGeneration = -1
    private var rate = 0
    private val window = FloatArray(config.fftSize)
    private var nextSample = 0
    private var filled = 0
    private var sinceFrame = 0
    private var previous = FloatArray(config.fftSize / 2)

    init {
        scope.launch {
            for (chunk in pending) accept(chunk)
        }
    }

    /** Safe to call from an audio callback. Samples are owned by the caller after this returns. */
    internal fun offer(samples: FloatArray, sampleRateHz: Int) {
        if (!stopped && samples.isNotEmpty() && sampleRateHz > 0) {
            pending.trySend(Chunk(generation, sampleRateHz, samples.copyOf()))
        }
    }

    /** Transfers ownership of a newly allocated chunk; avoid a second audio-thread copy. */
    internal fun offerOwned(samples: FloatArray, sampleRateHz: Int) {
        if (!stopped && samples.isNotEmpty() && sampleRateHz > 0) {
            pending.trySend(Chunk(generation, sampleRateHz, samples))
        }
    }

    internal fun reset() {
        if (!stopped) {
            generation++
            mutableFrames.value = null
        }
    }

    private fun accept(chunk: Chunk) {
        if (stopped || chunk.generation != generation) return
        val sampleRate = chunk.sampleRateHz
        if (rate != sampleRate || observedGeneration != chunk.generation) {
            observedGeneration = chunk.generation
            rate = sampleRate
            nextSample = 0
            filled = 0
            sinceFrame = 0
            previous.fill(0f)
            mutableFrames.value = null
        }
        val hop = (sampleRate / config.updateRateHz).coerceAtLeast(1)
        for (value in chunk.samples) {
            if (stopped || chunk.generation != generation) return
            window[nextSample] = value.coerceIn(-1f, 1f)
            nextSample = (nextSample + 1) % window.size
            if (filled < window.size) filled++
            sinceFrame++
            if (filled == window.size && sinceFrame >= hop) {
                sinceFrame = 0
                val frame = analyzeWindow()
                if (stopped || chunk.generation != generation) return
                mutableFrames.value = frame
            }
        }
    }

    private fun analyzeWindow(): KMAudioFrame {
        val size = window.size
        val real = DoubleArray(size)
        val imaginary = DoubleArray(size)
        var energy = 0.0
        var peak = 0f
        val waveform = if (config.includeWaveform) FloatArray(size) else null
        for (i in 0 until size) {
            val sample = window[(nextSample + i) % size]
            waveform?.set(i, sample)
            energy += sample * sample
            peak = maxOf(peak, abs(sample))
            real[i] = sample * (0.5 - 0.5 * cos(2.0 * PI * i / (size - 1)))
        }
        // In-place radix-2 Cooley-Tukey FFT.
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tmp = real[i]
                real[i] = real[j]
                real[j] = tmp
            }
        }
        var length = 2
        while (length <= size) {
            val angle = -2.0 * PI / length
            val stepReal = cos(angle)
            val stepImaginary = sin(angle)
            for (start in 0 until size step length) {
                var wr = 1.0
                var wi = 0.0
                for (k in 0 until length / 2) {
                    val a = start + k
                    val b = a + length / 2
                    val tr = wr * real[b] - wi * imaginary[b]
                    val ti = wr * imaginary[b] + wi * real[b]
                    real[b] = real[a] - tr
                    imaginary[b] = imaginary[a] - ti
                    real[a] += tr
                    imaginary[a] += ti
                    val nextWr = wr * stepReal - wi * stepImaginary
                    wi = wr * stepImaginary + wi * stepReal
                    wr = nextWr
                }
            }
            length = length shl 1
        }
        val magnitudesDb = FloatArray(size / 2)
        for (bin in magnitudesDb.indices) {
            val magnitude = (2.0 / (size * 0.5)) * sqrt(real[bin] * real[bin] + imaginary[bin] * imaginary[bin])
            val smooth = previous[bin] * config.smoothing + magnitude.toFloat() * (1f - config.smoothing)
            previous[bin] = smooth
            magnitudesDb[bin] = (20.0 * ln(smooth.coerceAtLeast(1e-6f).toDouble()) / ln(10.0))
                .toFloat().coerceIn(-120f, 0f)
        }
        return KMAudioFrame(rate, size, magnitudesDb, waveform,
            sqrt(energy / size).toFloat().coerceIn(0f, 1f), peak)
    }

    override fun close() {
        if (stopped) return
        stopped = true
        pending.close()
        scope.cancel()
        mutableFrames.value = null
        onClose()
    }
}
