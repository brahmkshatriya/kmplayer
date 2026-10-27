@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.brahmkshatriya.kmplayer.internal

import dev.brahmkshatriya.kmplayer.KMAudioAnalyzerConfig
import dev.brahmkshatriya.kmplayer.KMAudioFrame
import dev.brahmkshatriya.kmplayer.KMPlayerError
import dev.brahmkshatriya.kmplayer.KMResult
import org.w3c.dom.HTMLMediaElement
import kotlin.math.abs
import kotlin.math.sqrt

private external interface WasmAnalyzerHandle : JsAny {
    fun update()
    fun magnitude(index: Int): Double
    fun sample(index: Int): Double
    fun sampleRate(): Int
    fun close()
}

private fun openHandle(media: HTMLMediaElement, fftSize: Int, smoothing: Double): WasmAnalyzerHandle? = js("""(() => {
    if (!globalThis.AudioContext && !globalThis.webkitAudioContext) return null;
    const cache = globalThis.__kmplayerAudioSourceCache ||
        (globalThis.__kmplayerAudioSourceCache = new WeakMap());
    let record = cache.get(media);
    if (!record) {
        const context = new (globalThis.AudioContext || globalThis.webkitAudioContext)();
        const source = context.createMediaElementSource(media);
        source.connect(context.destination);
        record = { context, source };
        cache.set(media, record);
    }
    const { context, source } = record;
    const analyser = context.createAnalyser();
    analyser.fftSize = fftSize;
    analyser.smoothingTimeConstant = smoothing;
    source.connect(analyser);
    context.resume().catch(() => {});
    const spectrum = new Float32Array(fftSize / 2);
    const waveform = new Float32Array(fftSize);
    return {
        update() { analyser.getFloatFrequencyData(spectrum); analyser.getFloatTimeDomainData(waveform); },
        magnitude(i) { return spectrum[i]; },
        sample(i) { return waveform[i]; },
        sampleRate() { return context.sampleRate; },
        close() { source.disconnect(analyser); analyser.disconnect(); }
    };
})()""")

internal actual fun releaseWebAudioMedia(media: HTMLMediaElement) {
    closeWebAudioMedia(media)
}

private fun closeWebAudioMedia(media: HTMLMediaElement): Unit = js("""(() => {
    const cache = globalThis.__kmplayerAudioSourceCache;
    const record = cache && cache.get(media);
    if (!record) return;
    record.source.disconnect();
    record.context.close();
    cache.delete(media);
})()""")

internal actual fun resumeWebAudioMedia(media: HTMLMediaElement) {
    resumeWasmAudioMedia(media)
}

private fun resumeWasmAudioMedia(media: HTMLMediaElement): Unit = js("""(() => {
    const cache = globalThis.__kmplayerAudioSourceCache;
    const record = cache && cache.get(media);
    if (record) record.context.resume().catch(() => {});
})()""")

internal actual fun openWebSpectrumReader(
    media: HTMLMediaElement,
    config: KMAudioAnalyzerConfig,
): KMResult<WebSpectrumReader> = try {
    val handle = openHandle(media, config.fftSize, config.smoothing.toDouble())
        ?: return KMResult.Failure(KMPlayerError("audio-analysis-unavailable", "Web Audio is unavailable"))
    KMResult.Success(object : WebSpectrumReader {
        private var closed = false
        override fun snapshot(includeWaveform: Boolean): KMAudioFrame {
            handle.update()
            val db = FloatArray(config.fftSize / 2) { i ->
                handle.magnitude(i).toFloat().takeIf { it.isFinite() }?.coerceIn(-120f, 0f) ?: -120f
            }
            val samples = if (includeWaveform) FloatArray(config.fftSize) else null
            var energy = 0.0
            var peak = 0f
            for (i in 0 until config.fftSize) {
                val value = handle.sample(i).toFloat()
                samples?.set(i, value)
                energy += value * value
                peak = maxOf(peak, abs(value))
            }
            return KMAudioFrame(handle.sampleRate(), config.fftSize, db, samples,
                sqrt(energy / config.fftSize).toFloat().coerceIn(0f, 1f), peak.coerceIn(0f, 1f))
        }

        override fun close() {
            if (closed) return
            closed = true
            handle.close()
        }
    })
} catch (error: Throwable) {
    KMResult.Failure(KMPlayerError("audio-analysis-unavailable", error.message ?: "Web Audio is unavailable", error))
}
