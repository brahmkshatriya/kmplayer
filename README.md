# KMPlayer

[![Maven Central](https://img.shields.io/maven-central/v/dev.brahmkshatriya.kmplayer/kmplayer?label=Maven%20Central)](https://central.sonatype.com/artifact/dev.brahmkshatriya.kmplayer/kmplayer)
[![Publish](https://github.com/brahmkshatriya/kmplayer/actions/workflows/publish.yml/badge.svg)](https://github.com/brahmkshatriya/kmplayer/actions/workflows/publish.yml)

Kotlin Multiplatform audio and video playback using each operating system's media stack. One API for Android, iOS, macOS, Windows, Linux, JVM, JavaScript, and WasmJS.

- Playback controls, seeking, buffering state, and completion/error events through Kotlin flows.
- HLS playback, checked when the player is created.
- Optional Compose video surfaces with `Fit`, `Crop`, and `Fill` scaling.
- Persistent caching for finite HTTP(S) media, enabled by default.
- Optional audio spectrum analysis and access to the underlying platform player.

Your app owns playlists, queue transitions, and the background playback lifecycle.

[Setup](#setup) · [Compose](#compose-video) · [Playback](#playback-and-state) · [Platforms](#platform-support) · [Caching](#persistent-media-caching) · [Audio analysis](#audio-spectrum-analysis) · [Building](#building-from-source) · [Publishing](#publishing)

## Setup

Add `mavenCentral()` and `google()` to your project's dependency repositories. Replace `<version>` with the release shown in the Maven Central badge.

For playback without Compose:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("dev.brahmkshatriya.kmplayer:kmplayer:<version>")
        }
    }
}
```

For Compose video, use `kmplayer-compose`; it includes the core library:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("dev.brahmkshatriya.kmplayer:kmplayer-compose:<version>")
        }
    }
}
```

The packages are `dev.brahmkshatriya.kmplayer` and `dev.brahmkshatriya.kmplayer.compose`.

On Android (API 24+), initialization happens automatically through the library's manifest provider. If your app removes that provider, call `KMPlayerAndroid.initialize(applicationContext)` before creating a player. Network playback also needs this permission in your app's manifest:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

Linux needs an installed [GStreamer runtime](#linux). Browser playback follows the browser's autoplay policy; start audible playback from a user interaction when required.

## Compose video

```kotlin
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import dev.brahmkshatriya.kmplayer.KMVideoScale
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
        scale = KMVideoScale.Fit,
    )
}
```

`rememberKMPlayer()` closes its player when it leaves composition. Use `rememberKMPlayerResult()` to handle creation failures in your UI. A `KMVideoSurface` attached to an externally owned player manages only the surface lifetime; the caller still closes the player.

`Fit` preserves the whole image with letterboxing, `Crop` fills the surface by cropping, and `Fill` stretches to its bounds.

## Playback and state

Create one player for the lifetime of your screen or playback service. `load()` is suspending; call it from your owner's coroutine scope:

```kotlin
import dev.brahmkshatriya.kmplayer.KMPlayer
import dev.brahmkshatriya.kmplayer.getOrThrow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

val player = KMPlayer.create().getOrThrow()

// scope is the CoroutineScope owned by your screen or service.
scope.launch {
    player.load(
        uri = "https://example.com/master.m3u8",
        playWhenReady = true,
    ).getOrThrow()
}

// Call these from your playback controls:
player.pause().getOrThrow()
player.seekTo(30.seconds).getOrThrow()
player.play().getOrThrow()
player.volume = 75.0 // 0..100
player.muted = false
player.speed = 1.25
```

Call `player.close()` in the owner's cleanup. It releases playback, caching work, and any audio analyzer.

`player.state` is a `StateFlow<KMPlayerState>` containing status, position, nullable duration, buffered position, buffering/playback flags, seekability, live status, video dimensions, and the latest error:

```kotlin
scope.launch {
    player.state.collect { state ->
        println("${state.position} / ${state.duration}; playing=${state.isPlaying}")
    }
}
```

Convenience properties include `player.position`, `duration`, `isPlaying`, `isBuffering`, `isLive`, and `seekable`. `player.events` is a `SharedFlow<KMPlayerEvent>` for `MediaLoaded`, `MediaEnded`, and `PlaybackError` events. Collect it to advance your queue or display asynchronous errors.

### Error handling

Creation and playback operations return `KMResult`. Use `getOrThrow()` when exceptions suit your app, or handle failures explicitly:

```kotlin
import dev.brahmkshatriya.kmplayer.KMResult

// Inside a coroutine:
when (val result = player.load(url, playWhenReady = true)) {
    is KMResult.Success -> Unit
    is KMResult.Failure -> {
        println("${result.error.code}: ${result.error.message}")
    }
}
```

A successful `load()` accepts the request; later decoding or network failures can arrive through `PlaybackError` and `state.error`. Property assignments such as `player.volume = 75.0` throw if rejected; `setVolume()`, `setMuted()`, and `setSpeed()` return `KMResult` instead.

### HLS and video policy

`KMPlayerConfig(requireHls = true)` is the default. Creation returns `hls-unavailable` if the runtime lacks HLS support. For applications that only play other formats, use `requireHls = false`. Sources ending in `.m3u8` or using an HLS MIME type are detected automatically; `KMMediaSourceType.Hls` handles URLs without that information.

Video is automatic by default: it is enabled while at least one KMPlayer video surface is attached. Configure `audioOnly = true` for audio playback, or `audioOnly = false` to keep video enabled without a managed surface:

```kotlin
import dev.brahmkshatriya.kmplayer.KMPlayerConfig

val player = KMPlayer.create(
    KMPlayerConfig(audioOnly = true, requireHls = false),
).getOrThrow()
```

For a custom platform surface, keep a registration alive while its video output is attached:

```kotlin
val registration = player.registerVideoSurface().getOrThrow()
try {
    // Attach and use your platform video output here.
} finally {
    registration.close()
}
```

## Platform support

Both artifacts declare all 12 Kotlin targets below. Supported formats and codecs depend on the installed platform runtime.

| Kotlin target | Host / architecture | Playback backend | HLS |
| --- | --- | --- | --- |
| `android` | Android API 24+ | Media3 / ExoPlayer | Media3 HLS |
| `iosArm64` | iOS device, arm64 | AVFoundation / AVPlayer | Native |
| `iosSimulatorArm64` | iOS simulator, Apple Silicon | AVFoundation / AVPlayer | Native |
| `iosX64` | iOS simulator, Intel | AVFoundation / AVPlayer | Native |
| `macosX64`, `macosArm64` | macOS, Intel / Apple Silicon | AVFoundation / AVPlayer | Native |
| `mingwX64` | Windows x64 | Media Foundation / IMFMediaEngine | Runtime capability check |
| `linuxX64`, `linuxArm64` | Linux x64 / arm64 | GStreamer | Installed plugins |
| `jvm` | Java 17+ on Linux, Windows x64, or macOS 11+ x64 / arm64 | OS-selected backend; see below | Backend-dependent |
| `js`, `wasmJs` | Browser | HTMLMediaElement | Native or bundled `hls.js` fallback |

### JVM desktop

The JVM artifact selects **Media Foundation on Windows**, **AVPlayer on macOS**, and **GStreamer on Linux**. Use the same `KMPlayer.create()` and `KMVideoSurface` APIs on all three systems.

Release JARs bundle the Windows x64 DLL and macOS x64/arm64 dylibs. They are extracted and loaded automatically. Windows N editions may need the Media Feature Pack; HLS availability is checked against the installed Media Foundation runtime.

For an application-bundled or signed bridge, set `-Dkmplayer.native.library.path=/absolute/path/to/bridge` before creating a player. This lets a hardened macOS application load a bridge signed with its own identity.

### Linux

Install GStreamer for the process architecture, with `playbin`, `appsink`, video conversion, the decoders you need, and an audio output plugin. HLS additionally needs `hlsdemux2` or `hlsdemux`, plus `souphttpsrc` or `curlhttpsrc`. Audio analysis needs the `spectrum` plugin.

For Debian/Ubuntu, a typical runtime installation is:

```bash
sudo apt-get install gstreamer1.0-tools gstreamer1.0-plugins-base \
    gstreamer1.0-plugins-good gstreamer1.0-plugins-bad gstreamer1.0-libav
```

Native executables must link against the target's GStreamer libraries. For a nonstandard installation, configure the executable's library search paths. Linux JVM uses the system library loader.

### Apple and browser notes

Intel iOS simulators use Compose 1.10.3 / AndroidX Runtime 1.10.5, which still publish `iosX64` artifacts. Intel simulator apps need a compatible Compose dependency line. Device and Apple Silicon simulator artifacts use the current configured Compose version; the core artifact has no Compose dependency.

Browser media must satisfy CORS rules where applicable. Persistent browser caching requires Cache Storage support, normally in a secure context, and remains subject to browser quota and eviction.

## Persistent media caching

Finite HTTP(S) media is cached automatically. On a miss, playback uses the original URL while a background download fills the cache. A later load can use the complete cached file, including offline playback. `state.uri` continues to expose the requested URL. Cache failures allow normal streaming, and an unusable cached file falls back to the original URL.

Defaults are **256 MiB total** and **48 MiB per file**:

```kotlin
import dev.brahmkshatriya.kmplayer.KMCacheConfig
import dev.brahmkshatriya.kmplayer.KMPlayerConfig

val config = KMPlayerConfig(
    cache = KMCacheConfig(
        maxSizeBytes = 512L * 1024 * 1024,
        maxTrackBytes = 128L * 1024 * 1024,
        cacheName = "audio",
    ),
)

// Disable persistent caching with KMPlayerConfig(cache = null).
```

`baseDirectory` optionally selects a filesystem parent directory. Otherwise, KMPlayer uses the application's cache area on Android/iOS and the user cache directory on desktop. `cacheName` identifies its subdirectory. On web, these settings select a logical Cache Storage namespace; the browser controls its physical location.

HLS playlists/segments are excluded. Offline availability requires a completed download and compatible media; DRM, CORS restrictions, oversized files, and browser eviction can prevent caching.

## Audio spectrum analysis

Create an optional analyzer on the player producing the audio:

```kotlin
import dev.brahmkshatriya.kmplayer.KMAudioAnalyzerConfig
import kotlinx.coroutines.flow.filterNotNull

val result = player.createAudioAnalyzer(
    KMAudioAnalyzerConfig(fftSize = 2048, updateRateHz = 30, smoothing = 0.8f),
)
when (result) {
    is KMResult.Success -> {
        val analyzer = result.value
        scope.launch {
            try {
                analyzer.frames.filterNotNull().collect { frame ->
                    println("Bass: ${frame.bandEnergy(40f..160f)}")
                }
            } finally {
                analyzer.close()
            }
        }
    }
    is KMResult.Failure -> println(result.error.message)
}
```

Frames contain `magnitudesDb` in dBFS (-120..0), the sample rate, and bin spacing. `includeWaveform = true` requests mono waveform samples; RMS and peak are nullable when the backend only supplies a spectrum. Treat frame arrays as read-only. Frames start empty and are emitted from decoded audio. One analyzer may be active per player; closing the player closes its analyzer.

| Platform | Analysis source | Limits |
| --- | --- | --- |
| Android | Media3 decoded PCM tee; FFT off the audio thread | Audio offload disabled during analysis |
| Linux native / JVM | GStreamer `spectrum` | Plugin required; waveform, RMS, and peak unavailable |
| Windows native / JVM | Optional Media Foundation PCM effect | Protected streams or unsupported formats may bypass the effect |
| iOS / macOS native / JVM | AVFoundation tap not implemented | Returns `audio-analysis-unavailable` |
| JavaScript / WasmJS | Web Audio `AnalyserNode` | Web Audio support and authorized cross-origin media required |

`fftSize` must be a power of two from 256 to 8192, `updateRateHz` must be 1..60, and `smoothing` must be in [0, 1). Invalid configurations and unsupported analysis features return `KMResult.Failure`.

## Platform access

Use these extension properties for platform-specific integration:

| Platform | Access |
| --- | --- |
| Android | `player.exoPlayer` |
| iOS / macOS native | `player.avPlayer` |
| Windows native | `player.mediaEngineHandle` |
| Linux native | `player.gStreamerPipelineHandle` |
| Linux JVM | `player.gStreamerPlayBin`, `player.videoFrames` |
| Windows JVM | `player.mediaEngineHandle`, `player.videoFrames` |
| macOS JVM | `player.avPlayerHandle`, `player.videoFrames` |
| JavaScript / WasmJS | `player.htmlVideoElement` |

Handles and objects are borrowed and owned by KMPlayer. Keep their lifetime within the player's lifetime. Automatic video changes can replace the Windows or web media object; register a custom surface before reading its handle and release your references when the registration closes.

## Building from source

Use JDK 21+ and the checked-in Gradle wrapper. Android builds need the SDK versions in [gradle.properties](gradle.properties). Apple builds need macOS and Xcode; Windows native builds need the LLVM toolchain. Linux native builds need a C compiler, `pkg-config`, and GStreamer core/app/video development packages.

```bash
# Core, Compose JVM, and existing common unit tests:
./gradlew :kmplayer:jvmTest :kmplayer-compose:compileKotlinJvm

# Publish JVM artifacts to Maven Local:
./gradlew :kmplayer:publishJvmPublicationToMavenLocal \
    :kmplayer-compose:publishJvmPublicationToMavenLocal
```

Build Linux publications directly after installing a C toolchain, `pkg-config`, and the GStreamer development packages. For Debian/Ubuntu:

```bash
sudo apt-get install build-essential pkg-config \
    libgstreamer1.0-dev libgstreamer-plugins-base1.0-dev

./gradlew \
    :kmplayer:publishLinuxX64PublicationToReleaseShardRepository \
    :kmplayer-compose:publishLinuxX64PublicationToReleaseShardRepository
```

The release workflow runs Gradle directly on its hosted runners. Kotlin/Native Linux ARM64 publications are cross-compiled from an x64 host. The workflow builds the GStreamer C archive on an ARM64 runner first and passes it to the x64 publication job.

`KMPLAYER_LINUX_ARM64_CC`, `KMPLAYER_LINUX_ARM64_CFLAGS`, and `KMPLAYER_LINUX_ARM64_AR` select a cross-compilation SDK. The standard Konan ARM64 GCC toolchain is detected when already installed. Alternatively, set `KMPLAYER_LINUX_ARM64_PREBUILT_ARCHIVE` to an existing archive.

### Building a complete JVM JAR

Local builds compile host bridges automatically. Windows JVM bridge builds require MinGW-w64 `g++`. A distributable JVM JAR needs three bridges, built on their respective hosts:

```bash
# Windows x64:
./gradlew :kmplayer:buildJvmNativeBridgeWindowsX64

# macOS with Xcode (both architectures):
./gradlew :kmplayer:buildJvmNativeBridgeMacosX64 :kmplayer:buildJvmNativeBridgeMacosArm64
```

Combine the generated folders from `kmplayer/build/generated/jvmNative/native/` into one directory:

```text
jvm-native/
├── windows-x86_64/kmplayer_mf.dll
├── macos-x86_64/libkmplayer_av.dylib
└── macos-aarch64/libkmplayer_av.dylib
```

Then package with validation enabled:

```bash
KMPLAYER_JVM_NATIVE_DIR=/absolute/path/to/jvm-native \
    ./gradlew -Pkmplayer.requireAllJvmNatives=true :kmplayer:jvmJar
```

That flag rejects missing bridges. Local Linux builds can also cross-build the Windows DLL when the Konan MinGW toolchain is installed. macOS bridges require an Apple SDK. The release workflow assembles all three automatically.

## Publishing

[The publish workflow](.github/workflows/publish.yml) follows the WebView KMP release process:

1. Start it manually with a release version, or push a version tag such as `v0.1.0`. A leading `v` is removed from tags; snapshot versions are rejected.
2. Build the Linux ARM64 C bridge and Windows/macOS JVM bridges on suitable hosts.
3. Publish core and Compose artifacts for all 12 targets into separate Maven repository shards, with a separate job for KMP root metadata. All builds run directly on hosted runners.
4. Merge the shards, verify all 26 publications and the JVM's bundled native bridges, and reject snapshot metadata.
5. Validate Maven metadata and companion artifacts, sign the files, and upload **one Maven Central deployment**. Wait for Central to report it published.

Configure these repository Actions secrets:

| Secret | Contents |
| --- | --- |
| `GRADLE_PROPERTIES_CONTENT` | Maven Central token credentials and signing properties shown below |
| `GPG_SECRET_KEY_RING_BASE64` | Base64-encoded exported GPG secret key ring |

```properties
mavenCentralUsername=<central-token-username>
mavenCentralPassword=<central-token-password>
signing.keyId=<signing-key-id>
signing.password=<signing-key-passphrase>
```

The workflow writes `signing.secretKeyRingFile` itself. Platform builds run without publishing credentials; only the final publish job restores them. Failed upload bundles are retained as workflow artifacts for five days.

To inspect unsigned artifacts locally, use the `publish…PublicationToReleaseShardRepository` tasks. They write to `build/maven-release-shard` without uploading. [prepare-maven-central-bundle.py](scripts/prepare-maven-central-bundle.py) validates and stages that repository. [publish-maven-central-bundle.sh](scripts/publish-maven-central-bundle.sh) signs and uploads the resulting bundle; `CENTRAL_BUNDLE_ONLY=true` stops after creating the signed ZIP.

## License

[Apache License 2.0](LICENSE).
