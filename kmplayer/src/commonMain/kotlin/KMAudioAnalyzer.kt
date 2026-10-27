package dev.brahmkshatriya.kmplayer

import kotlinx.coroutines.flow.StateFlow
import kotlin.math.pow

/** Settings for optional, real-time analysis of the audio produced by a KMPlayer. */
public data class KMAudioAnalyzerConfig(
    /** Number of PCM samples per FFT window; a power of two between 256 and 8192. */
    val fftSize: Int = 2048,
    /** Maximum number of spectrum updates per second. */
    val updateRateHz: Int = 30,
    /** Exponential smoothing of linear magnitudes: 0 = none, approaching 1 = slower. */
    val smoothing: Float = 0.8f,
    /** Include a snapshot of the most recent mono PCM samples in each frame. */
    val includeWaveform: Boolean = false,
)

/** Snapshot of one FFT window. Arrays are owned by this frame; consumers should treat them as read-only. */
public class KMAudioFrame(
    public val sampleRateHz: Int,
    public val fftSize: Int,
    /** Linear frequency bins, expressed in dBFS (0 = full-scale sinusoid, -120 = silence). */
    public val magnitudesDb: FloatArray,
    /** Mono PCM values, nominally between -1 and +1. Null unless requested. */
    public val waveform: FloatArray?,
    /** Root-mean-square level of the window, between 0 and 1, if available. */
    public val rms: Float?,
    /** Maximum absolute sample level of the window, between 0 and 1, if available. */
    public val peak: Float?,
) {
    public val frequencyResolutionHz: Float get() = sampleRateHz.toFloat() / fftSize

    /** Average linear amplitude for a frequency range; 0 = silence, 1 = full scale. */
    public fun bandEnergy(frequenciesHz: ClosedFloatingPointRange<Float>): Float {
        if (magnitudesDb.isEmpty() || sampleRateHz <= 0) return 0f
        val resolution = frequencyResolutionHz
        if (frequenciesHz.endInclusive < frequenciesHz.start ||
            frequenciesHz.start >= sampleRateHz / 2f || frequenciesHz.endInclusive < 0f) return 0f
        val start = kotlin.math.ceil((frequenciesHz.start / resolution).toDouble()).toInt()
            .coerceIn(0, magnitudesDb.lastIndex)
        val end = kotlin.math.floor((frequenciesHz.endInclusive / resolution).toDouble()).toInt()
            .coerceIn(0, magnitudesDb.lastIndex)
        if (start > end) return 0f
        var sum = 0.0
        for (bin in start..end) sum += 10.0.pow(magnitudesDb[bin] / 20.0)
        return (sum / (end - start + 1)).toFloat().coerceIn(0f, 1f)
    }
}

/** A single active analysis session belonging to a KMPlayer. Close it to release capture resources. */
public interface KMAudioAnalyzer : AutoCloseable {
    /** Latest completed frame. Initially null; no synthetic frames are emitted. */
    public val frames: StateFlow<KMAudioFrame?>
    override fun close()
}

internal fun KMAudioAnalyzerConfig.isValid(): Boolean =
    fftSize in 256..8192 && (fftSize and (fftSize - 1)) == 0 &&
        updateRateHz in 1..60 && smoothing.isFinite() && smoothing >= 0f && smoothing < 1f
