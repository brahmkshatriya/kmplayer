package dev.brahmkshatriya.kmplayer.internal

import com.sun.jna.FunctionMapper
import com.sun.jna.IntegerType
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import dev.brahmkshatriya.kmplayer.KMBackendKind
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/** size_t is 64 bits on Windows x64 too, where C long is only 32 bits. */
internal class JvmSizeT(value: Long = 0) : IntegerType(Native.SIZE_T_SIZE, value, true) {
    override fun toByte(): Byte = toLong().toByte()
    override fun toShort(): Short = toLong().toShort()
}

internal interface JvmNativePlayerBridge : Library {
    fun player_bridge_version(): Int
    fun player_create(audioOnly: Int, error: ByteArray, size: JvmSizeT): Pointer?
    fun player_destroy(player: Pointer)
    fun player_load(player: Pointer, uri: String, playWhenReady: Int): Int
    fun player_play(player: Pointer): Int
    fun player_pause(player: Pointer): Int
    fun player_stop(player: Pointer): Int
    fun player_seek_ms(player: Pointer, position: Long): Int
    fun player_set_volume(player: Pointer, volume: Double): Int
    fun player_set_muted(player: Pointer, muted: Int): Int
    fun player_set_speed(player: Pointer, speed: Double): Int
    fun player_set_audio_analysis(player: Pointer, enabled: Int): Int
    fun player_copy_audio_samples(player: Pointer, samples: FloatArray, capacity: Int, rate: IntByReference): Int
    fun player_position_ms(player: Pointer): Long
    fun player_duration_ms(player: Pointer): Long
    fun player_buffered_end_ms(player: Pointer): Long
    fun player_has_hls(player: Pointer): Int
    fun player_is_seekable(player: Pointer): Int
    fun player_is_live(player: Pointer): Int
    fun player_video_width(player: Pointer): Int
    fun player_video_height(player: Pointer): Int
    fun player_native_handle(player: Pointer): Pointer?
    fun player_copy_video_frame(player: Pointer, pixels: Pointer, width: Int, height: Int, stride: Int): Int
    fun player_poll_event(player: Pointer, message: ByteArray, size: JvmSizeT): Int
}

internal interface JvmAvPlayerBridge : JvmNativePlayerBridge {
    fun player_set_video_enabled(player: Pointer, enabled: Int): Int
}

internal object JvmNativePlayerLibraries {
    private val libraries = mutableMapOf<KMBackendKind, JvmNativePlayerBridge>()

    @Synchronized fun load(kind: KMBackendKind): JvmNativePlayerBridge = libraries.getOrPut(kind) {
        val windows = kind == KMBackendKind.MediaFoundation
        val arch = when (val value = System.getProperty("os.arch").lowercase(Locale.ROOT)) {
            "amd64", "x86_64", "x64" -> "x86_64"
            "aarch64", "arm64" -> "aarch64"
            else -> error("Unsupported JVM architecture: $value")
        }
        val os = if (windows) "windows" else "macos"
        val file = if (windows) "kmplayer_mf.dll" else "libkmplayer_av.dylib"
        val override = System.getProperty("kmplayer.native.library.path")?.takeIf(String::isNotBlank)
        val path = override?.let { Path.of(it).toAbsolutePath().toString() } ?: run {
            val resource = "/native/$os-$arch/$file"
            val stream = checkNotNull(javaClass.getResourceAsStream(resource)) {
                "Missing KMPlayer native bridge $resource. Use a complete JVM publication, or set kmplayer.native.library.path to an application-bundled bridge."
            }
            val extracted = Files.createTempFile("kmplayer-", if (windows) ".dll" else ".dylib")
            extracted.toFile().deleteOnExit()
            stream.use { input -> Files.newOutputStream(extracted).use(input::copyTo) }
            extracted.toAbsolutePath().toString()
        }
        val mapper = FunctionMapper { _, method ->
            if (windows && method.name == "player_native_handle") "kmplayer_mf_player_engine"
            else "kmplayer_${if (windows) "mf" else "av"}_${method.name}"
        }
        val type = if (windows) JvmNativePlayerBridge::class.java else JvmAvPlayerBridge::class.java
        val options = mapOf(
            Library.OPTION_FUNCTION_MAPPER to mapper,
            Library.OPTION_STRING_ENCODING to "UTF-8",
        )
        // Resolve the complete ABI before exposing a player; a stale/application
        // supplied bridge should fail at creation rather than on a later control.
        val native = NativeLibrary.getInstance(path, options)
        type.methods.filter { it.name.startsWith("player_") }.forEach { method ->
            native.getFunction(mapper.getFunctionName(native, method))
        }
        Native.load(path, type, options).also { check(it.player_bridge_version() == 1) { "Unsupported KMPlayer native bridge ABI" } }
    }
}
