package dev.brahmkshatriya.kmplayer.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import dev.brahmkshatriya.kmplayer.KMPlayer
import dev.brahmkshatriya.kmplayer.KMPlayerConfig
import dev.brahmkshatriya.kmplayer.KMResult
import dev.brahmkshatriya.kmplayer.getOrThrow

/** Creates and remembers a [KMPlayer], closing it automatically when it leaves composition. */
@Composable
public fun rememberKMPlayer(config: KMPlayerConfig = KMPlayerConfig()): KMPlayer {
    val player = remember(config) { KMPlayer.create(config).getOrThrow() }
    DisposableEffect(player) {
        onDispose { player.close() }
    }
    return player
}

/** Result-based variant of [rememberKMPlayer] for applications that want to render creation errors. */
@Composable
public fun rememberKMPlayerResult(config: KMPlayerConfig = KMPlayerConfig()): KMResult<KMPlayer> {
    val result = remember(config) { KMPlayer.create(config) }
    val player = (result as? KMResult.Success)?.value
    DisposableEffect(player) {
        onDispose { player?.close() }
    }
    return result
}
