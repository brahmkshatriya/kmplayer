package dev.brahmkshatriya.kmplayer.internal

import dev.brahmkshatriya.kmplayer.KMBackendKind
import dev.brahmkshatriya.kmplayer.KMPlayerConfig
import dev.brahmkshatriya.kmplayer.KMPlayerError
import dev.brahmkshatriya.kmplayer.KMResult
import dev.brahmkshatriya.kmplayer.KMVideoFrame
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

internal interface JvmKMPlayerBackend : KMPlayerBackend {
    val videoFrames: StateFlow<KMVideoFrame?>
}

internal actual fun createPlatformKMPlayerBackend(config: KMPlayerConfig): KMResult<KMPlayerBackend> {
    val os = System.getProperty("os.name").lowercase(Locale.ROOT)
    return when {
        os.contains("mac") || os.contains("darwin") -> JvmNativeKMPlayerBackend.create(config, KMBackendKind.AvFoundation)
        os.contains("win") -> JvmNativeKMPlayerBackend.create(config, KMBackendKind.MediaFoundation)
        os.contains("linux") -> createGStreamerKMPlayerBackend(config)
        else -> KMResult.Failure(KMPlayerError("unsupported-platform", "KMPlayer does not support JVM playback on $os"))
    }
}
