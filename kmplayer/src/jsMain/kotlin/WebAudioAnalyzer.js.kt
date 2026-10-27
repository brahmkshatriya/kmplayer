package dev.brahmkshatriya.kmplayer.internal

import dev.brahmkshatriya.kmplayer.KMAudioAnalyzerConfig
import dev.brahmkshatriya.kmplayer.KMAudioFrame
import dev.brahmkshatriya.kmplayer.KMPlayerError
import dev.brahmkshatriya.kmplayer.KMResult
import org.khronos.webgl.Float32Array
import org.w3c.dom.HTMLMediaElement
import kotlin.math.abs
import kotlin.math.sqrt

// A media element may only be passed to createMediaElementSource once.
// Keep its output route alive across analyzer sessions; retire it with the player.
private val audioSources: dynamic = js("new WeakMap()")

internal actual fun openWebSpectrumReader(
    media: HTMLMediaElement,
    config: KMAudioAnalyzerConfig,
): KMResult<WebSpectrumReader> = try {
    val previous = audioSources.get(media)
    val context: dynamic = if (previous != null) previous.context
        else js("new (window.AudioContext || window.webkitAudioContext)()")
    val source = if (previous != null) previous.source else context.createMediaElementSource(media)
    if (previous == null) {
        source.connect(context.destination)
        val record: dynamic = js("({})")
        record.context = context
        record.source = source
        audioSources.set(media, record)
    }
    val analyser = context.createAnalyser()
    analyser.fftSize = config.fftSize
    analyser.smoothingTimeConstant = config.smoothing
    source.connect(analyser)
    context.resume()
    val rate = (context.sampleRate as Number).toInt()
    val spectrum = Float32Array(config.fftSize / 2)
    val wave = Float32Array(config.fftSize)
    KMResult.Success(object : WebSpectrumReader {
        private var closed = false
        override fun snapshot(includeWaveform: Boolean): KMAudioFrame {
            analyser.getFloatFrequencyData(spectrum)
            analyser.getFloatTimeDomainData(wave)
            val db = FloatArray(spectrum.length) { i ->
                (spectrum.asDynamic()[i] as Number).toFloat()
                    .takeIf { it.isFinite() }?.coerceIn(-120f, 0f) ?: -120f
            }
            val samples = if (includeWaveform) FloatArray(wave.length) else null
            var energy = 0.0
            var peak = 0f
            for (i in 0 until wave.length) {
                val value = (wave.asDynamic()[i] as Number).toFloat()
                samples?.set(i, value)
                energy += value * value
                peak = maxOf(peak, abs(value))
            }
            return KMAudioFrame(rate, config.fftSize, db, samples,
                sqrt(energy / wave.length).toFloat().coerceIn(0f, 1f), peak.coerceIn(0f, 1f))
        }

        override fun close() {
            if (closed) return
            closed = true
            source.disconnect(analyser)
            analyser.disconnect()
        }
    })
} catch (error: Throwable) {
    KMResult.Failure(KMPlayerError("audio-analysis-unavailable", error.message ?: "Web Audio is unavailable", error))
}

internal actual fun releaseWebAudioMedia(media: HTMLMediaElement) {
    val record = audioSources.get(media) ?: return
    record.source.disconnect()
    record.context.close()
    audioSources.delete(media)
}

internal actual fun resumeWebAudioMedia(media: HTMLMediaElement) {
    val record = audioSources.get(media) ?: return
    record.context.resume()
}
