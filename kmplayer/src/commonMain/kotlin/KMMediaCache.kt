package dev.brahmkshatriya.kmplayer.internal

import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import dev.brahmkshatriya.kmplayer.KMCacheConfig
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import kotlin.time.Clock

/** A best-effort, bounded, persistent cache. Cache failures never block streaming. */
internal interface KMMediaCache {
    suspend fun cachedUri(sourceUrl: String): String?
    suspend fun store(sourceUrl: String)
    suspend fun invalidate(sourceUrl: String)
    fun close()
}

internal expect fun createPlatformMediaCache(config: KMCacheConfig): KMMediaCache

/** Only the parent directory is platform-specific; the cache name is configured by callers. */
internal fun KMCacheConfig.diskDirectory(defaultBaseDirectory: String): String =
    ((baseDirectory ?: defaultBaseDirectory).toPath() / cacheName).toString()

/** Browser Cache Storage has namespaces, not user-selectable filesystem paths. */
internal fun KMCacheConfig.browserCacheName(): String =
    "kmplayer-${baseDirectory?.let { "$it/" } ?: ""}$cacheName-v1"

/** Android and Kotlin/Native use the same on-disk layout and eviction algorithm. */
internal class DiskKMMediaCache(
    cacheDirectory: String,
    private val files: FileSystem,
    private val config: KMCacheConfig,
) : KMMediaCache {
    private val directory = cacheDirectory.toPath()
    private val mutex = Mutex()
    private val downloading = mutableSetOf<String>()
    private val httpDelegate = lazy { HttpClient { expectSuccess = false } }
    private val http get() = httpDelegate.value

    private fun entry(url: String): Path {
        val extension = url.substringBefore('?').substringBefore('#')
            .substringAfterLast('.', "").lowercase()
            .takeIf { it in setOf("mp3", "m4a", "aac", "mp4", "ogg", "opus", "flac", "wav", "webm", "mkv", "mov", "avi", "m4v") }
            ?: "mp3"
        return directory / "${url.encodeUtf8().sha256().hex()}.$extension"
    }

    private fun touch(path: Path) {
        val sink = files.sink(("$path.used").toPath()).buffer()
        try { sink.writeUtf8(Clock.System.now().toEpochMilliseconds().toString()) }
        finally { sink.close() }
    }

    override suspend fun cachedUri(sourceUrl: String): String? = withContext(Dispatchers.Default) {
        if (!sourceUrl.startsWith("https://") && !sourceUrl.startsWith("http://")) return@withContext null
        runCatching {
            mutex.withLock {
                val path = entry(sourceUrl)
                if ((files.metadataOrNull(path)?.size ?: 0L) <= 0L) return@withLock null
                touch(path)
                // Native playback backends accept file:// URIs for completed songs.
                val nativePath = path.toString().replace('\\', '/')
                // Windows drive paths need file:///C:/..., not file://C:/...
                val uriPath = if (nativePath.startsWith('/')) nativePath else "/$nativePath"
                "file://${uriPath.replace("%", "%25").replace(" ", "%20").replace("#", "%23")}"
            }
        }.getOrNull()
    }

    override suspend fun invalidate(sourceUrl: String) = withContext(Dispatchers.Default) {
        runCatching {
            mutex.withLock {
                val path = entry(sourceUrl)
                files.delete(path, mustExist = false)
                files.delete((path.toString() + ".used").toPath(), mustExist = false)
            }
        }
        Unit
    }

    override suspend fun store(sourceUrl: String) {
        if (!sourceUrl.startsWith("https://") && !sourceUrl.startsWith("http://")) return
        val dest = entry(sourceUrl)
        val claim = mutex.withLock {
            if (dest.toString() in downloading || (files.metadataOrNull(dest)?.size ?: 0L) > 0L) false
            else { downloading += dest.toString(); true }
        }
        if (!claim) return
        try {
            withContext(Dispatchers.Default) {
                files.createDirectories(directory)
                val part = ("$dest.part").toPath()
                try {
                    http.prepareGet(sourceUrl).execute { response ->
                        if (!response.status.isSuccess()) return@execute
                        val length = response.headers["Content-Length"]?.toLongOrNull()
                        if (length != null && length > config.maxTrackBytes) return@execute
                        val type = response.headers["Content-Type"].orEmpty().lowercase()
                        if (type.startsWith("text/") || type.contains("json") ||
                            type.contains("mpegurl") || type.contains("dash+xml")) return@execute
                        var total = 0L
                        val channel = response.bodyAsChannel()
                        val sink = files.sink(part).buffer()
                        try {
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val count = channel.readAvailable(buffer, 0, buffer.size)
                                if (count < 0) break
                                if (count == 0) continue
                                total += count
                                if (total > config.maxTrackBytes) return@execute
                                sink.write(buffer, 0, count)
                            }
                        } finally { sink.close() }
                        if (total == 0L || (length != null && total != length)) return@execute
                        mutex.withLock {
                            files.atomicMove(part, dest)
                            touch(dest)
                            evict(except = dest)
                        }
                    }
                } finally {
                    if (files.exists(part)) files.delete(part)
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // Unavailable network, unsupported format or full storage: stream normally.
        } finally {
            mutex.withLock { downloading.remove(dest.toString()) }
        }
    }

    /** Sort by last usage, leaving a recently completed track intact. */
    private fun evict(except: Path) {
        val entries = files.list(directory).filter { path ->
            path != except && !path.name.endsWith(".used") && !path.name.endsWith(".part") &&
                (files.metadataOrNull(path)?.isRegularFile == true)
        }
        var total = (files.metadataOrNull(except)?.size ?: 0L) + entries.sumOf {
            files.metadataOrNull(it)?.size ?: 0L
        }
        if (total <= config.maxSizeBytes) return
        for (path in entries.sortedBy { path ->
            files.metadataOrNull(("$path.used").toPath())?.lastModifiedAtMillis
                ?: files.metadataOrNull(path)?.lastModifiedAtMillis ?: 0L
        }) {
            if (total <= config.maxSizeBytes) break
            total -= files.metadataOrNull(path)?.size ?: 0L
            files.delete(path)
            files.delete(("$path.used").toPath(), mustExist = false)
        }
    }

    override fun close() {
        // The player cancels cache downloads before closing their HTTP client.
        if (httpDelegate.isInitialized()) http.close()
    }
}
