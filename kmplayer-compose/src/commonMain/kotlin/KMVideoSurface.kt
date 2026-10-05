package dev.brahmkshatriya.kmplayer.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.brahmkshatriya.kmplayer.KMPlayer
import dev.brahmkshatriya.kmplayer.KMVideoScale
import dev.brahmkshatriya.kmplayer.getOrThrow

/**
 * Displays video from [player] using the platform's native media rendering path.
 * The player remains owned by the caller and is not closed when this composable leaves composition.
 */
@Composable
public fun KMVideoSurface(
    player: KMPlayer,
    modifier: Modifier = Modifier,
    scale: KMVideoScale = KMVideoScale.Fit,
) {
    if (!player.capabilities.video) return
    val registration = remember(player) { player.registerVideoSurface().getOrThrow() }
    DisposableEffect(registration) {
        onDispose { registration.close() }
    }
    PlatformKMVideoSurface(player, modifier, scale)
}

@Composable
internal expect fun PlatformKMVideoSurface(
    player: KMPlayer,
    modifier: Modifier,
    scale: KMVideoScale,
)
