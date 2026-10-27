package dev.brahmkshatriya.kmplayer

import dev.brahmkshatriya.kmplayer.internal.KMPcmSpectrumAnalyzer
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KMAudioAnalyzerTest {
    @Test
    fun configRequiresPowerOfTwoAndValidRate() {
        assertTrue(KMAudioAnalyzerConfig().isValid())
        assertFalse(KMAudioAnalyzerConfig(fftSize = 500).isValid())
        assertFalse(KMAudioAnalyzerConfig(updateRateHz = 0).isValid())
        assertFalse(KMAudioAnalyzerConfig(smoothing = 1f).isValid())
    }

    @Test
    fun fftFindsToneAtExpectedFrequency() = runTest {
        val analyzer = KMPcmSpectrumAnalyzer(
            KMAudioAnalyzerConfig(fftSize = 1024, updateRateHz = 30, smoothing = 0f, includeWaveform = true),
        ) {}
        try {
            val samples = FloatArray(2048) { i ->
                (0.8 * sin(2 * PI * 128.0 * i / 8192.0)).toFloat()
            }
            analyzer.offer(samples, 8192)
            val frame = withContext(Dispatchers.Default) {
                withTimeout(4000) { analyzer.frames.filterNotNull().first() }
            }
            assertEquals(8f, frame.frequencyResolutionHz)
            assertTrue(frame.magnitudesDb[16] > -5f)
            assertTrue(frame.magnitudesDb[16] > frame.magnitudesDb[40] + 40f)
            assertTrue(frame.bandEnergy(120f..136f) > 0.2f)
            assertTrue(frame.bandEnergy(2000f..3000f) < 0.05f)
            assertEquals(1024, frame.waveform?.size)
        } finally {
            analyzer.close()
        }
    }
}
