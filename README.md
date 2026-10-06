# kmplayer

[![Maven Central](https://img.shields.io/maven-central/v/dev.brahmkshatriya.kmplayer/kmplayer?label=Maven%20Central)](https://central.sonatype.com/artifact/dev.brahmkshatriya.kmplayer/kmplayer)

Kotlin Multiplatform audio and video playback using the platform media stack on Android, Apple platforms, Windows, Linux, JavaScript, and WasmJS.

`kmplayer` provides one Kotlin API for playback and HLS. `kmplayer-compose` adds a Compose Multiplatform video surface.

## Add the dependency

Use the core artifact when you only need playback:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("dev.brahmkshatriya.kmplayer:kmplayer:<version>")
        }
    }
}
```

For Compose Multiplatform video UI, add the Compose artifact instead. It already depends on the core library:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("dev.brahmkshatriya.kmplayer:kmplayer-compose:<version>")
        }
    }
}
```

The public API lives under `dev.brahmkshatriya.kmplayer` and `dev.brahmkshatriya.kmplayer.compose`.

## Play media

```kotlin
import dev.brahmkshatriya.kmplayer.KMPlayer
import dev.brahmkshatriya.kmplayer.getOrThrow
import kotlin.time.Duration.Companion.seconds

val player = KMPlayer.create().getOrThrow()

player.load(
    uri = "https://example.com/master.m3u8",
    playWhenReady = true,
).getOrThrow()

player.pause()
player.seekTo(30.seconds)
player.play()

player.close()
```

`KMPlayer` implements `AutoCloseable`, so non-Compose callers should close it when playback is no longer needed.

## Observe playback

Current playback state is exposed as a `StateFlow`:

```kotlin
player.state.collect { state ->
    println(state.mediaStatus)
    println(state.position)
    println(state.duration)
    println(state.isPlaying)
    println(state.isBuffering)
}
```

Convenience properties are available for common state and controls:

```kotlin
player.volume = 75.0
player.muted = false
player.speed = 1.25

println(player.position)
println(player.duration)
println(player.isPlaying)
println(player.isLive)
```

One-shot events such as media completion and playback errors are available through `player.events`.

## Compose Multiplatform

`rememberKMPlayer()` owns the player lifecycle for the composition. `KMVideoSurface` attaches video only while the composable is present.

```kotlin
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import dev.brahmkshatriya.kmplayer.compose.KMVideoSurface
import dev.brahmkshatriya.kmplayer.compose.rememberKMPlayer
import dev.brahmkshatriya.kmplayer.getOrThrow

@Composable
fun VideoScreen(url: String) {
    val player = rememberKMPlayer()

    LaunchedEffect(player, url) {
        player.load(url, playWhenReady = true).getOrThrow()
    }

    KMVideoSurface(
        player = player,
        modifier = Modifier.fillMaxSize(),
    )
}
```

Video scaling can be selected with `KMVideoScale.Fit`, `Crop`, or `Fill`.

If player creation failures need to be rendered instead of thrown, use `rememberKMPlayerResult()`.

## Audio-only playback

By default, video usage is automatic:

```kotlin
KMPlayer.create(
    KMPlayerConfig(audioOnly = null), // default
)
```

With no registered video surface, the player uses its lean audio-only path. When `KMVideoSurface` enters composition, video is enabled; when the last surface leaves, video is disabled again.

You can override that behavior:

```kotlin
KMPlayerConfig(audioOnly = true)  // always audio-only
KMPlayerConfig(audioOnly = false) // always keep video enabled
```

This changes the actual playback pipeline where the platform allows it. For example, Android disables Media3 video track selection, Windows uses Media Foundation's audio-only mode, and Linux disables GStreamer's video path.

For custom non-Compose video integrations, register the surface lifetime explicitly:

```kotlin
val registration = player.registerVideoSurface().getOrThrow()

try {
    // Attach the platform video output.
} finally {
    registration.close()
}
```

## HLS

HLS support is part of the default `KMPlayer` contract:

```kotlin
KMPlayer.create() // requireHls = true
```

If the current platform/runtime cannot provide HLS, player creation returns a `KMResult.Failure` with `hls-unavailable` rather than creating a partially capable player.

For applications that do not need HLS:

```kotlin
KMPlayer.create(
    KMPlayerConfig(requireHls = false),
)
```

## Supported platforms

| Platform | Playback backend | HLS |
| --- | --- | --- |
| Android | Media3 / ExoPlayer | Media3 HLS |
| iOS | AVFoundation / `AVPlayer` | Native |
| macOS | AVFoundation / `AVPlayer` | Native |
| Windows | Media Foundation / `IMFMediaEngine` | Native, capability checked |
| Linux x64 | GStreamer | GStreamer HLS plugins |
| Linux arm64 | GStreamer | GStreamer HLS plugins |
| JavaScript | `HTMLMediaElement` | Native when available, otherwise `hls.js` |
| WasmJS | `HTMLMediaElement` | Native when available, otherwise `hls.js` |

Android requires API 24 or newer.

## Linux requirements

Linux uses the system GStreamer installation rather than bundling a media engine. The runtime must provide:

- `playbin`
- `hlsdemux2` or `hlsdemux`
- `souphttpsrc` or `curlhttpsrc`

If HLS is not required, construct the player with `requireHls = false`.

## Platform access

The underlying player is available when a platform-specific integration needs it:

```text
Android      player.exoPlayer
iOS/macOS    player.avPlayer
Windows      player.mediaEngineHandle
Linux        player.gStreamerPipelineHandle
JS/WasmJS    player.htmlVideoElement
```

These are borrowed handles/objects owned by `KMPlayer`. Do not use them after the player is closed.

In automatic video mode, Windows and web may replace their backing media object when video is enabled or disabled. Custom video integrations should call `registerVideoSurface()` before reading the native handle and stop using it when the registration is closed.

## Playback result handling

Operations return `KMResult` when the platform can reject an action:

```kotlin
when (val result = player.load(url, playWhenReady = true)) {
    is KMResult.Success -> Unit
    is KMResult.Failure -> {
        println(result.error.code)
        println(result.error.message)
    }
}
```

For applications that prefer exceptions at the call site, use `getOrThrow()`.

## License

Apache License 2.0.
