@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.brahmkshatriya.kmplayer.internal

import dev.brahmkshatriya.kmplayer.KMCacheConfig

internal actual fun createPlatformMediaCache(config: KMCacheConfig): KMMediaCache = BrowserKMMediaCache(config)

internal actual fun browserCachedMedia(cacheName: String, sourceUrl: String, done: (String?) -> Unit): Unit = js("""(() => {
    (async () => {
        if (!('caches' in globalThis)) return done(null);
        const cache = await caches.open(cacheName);
        const saved = await cache.match(sourceUrl);
        if (!saved || !saved.ok) return done(null);
        const blob = await saved.blob();
        if (!blob.size) return done(null);
        await cache.delete(sourceUrl);
        await cache.put(sourceUrl, new Response(blob, { headers: { 'Content-Type': blob.type || 'application/octet-stream' } }));
        done(URL.createObjectURL(blob));
    })().catch(() => done(null));
})()""")

internal actual fun browserStoreMedia(cacheName: String, sourceUrl: String, maxBytes: Double, maxTrackBytes: Double, done: () -> Unit): Unit = js("""(() => {
    (async () => {
        if (!('caches' in globalThis) || !/^https?:\/\//i.test(sourceUrl)) return;
        const cache = await caches.open(cacheName);
        if (await cache.match(sourceUrl)) return;
        const response = await fetch(sourceUrl, { mode: 'cors' });
        if (!response.ok || response.type === 'opaque') return;
        const size = Number(response.headers.get('Content-Length'));
        if (size > maxTrackBytes) return;
        const type = (response.headers.get('Content-Type') || '').toLowerCase();
        if (type.startsWith('text/') || type.includes('json')) return;
        const blob = await response.blob();
        if (!blob.size || blob.size > maxTrackBytes) return;
        await cache.put(sourceUrl, new Response(blob, { headers: { 'Content-Type': blob.type || 'application/octet-stream' } }));
        const entries = []; let total = 0;
        for (const key of await cache.keys()) {
            const saved = await cache.match(key);
            const bytes = saved ? (await saved.blob()).size : 0;
            entries.push({ key, bytes }); total += bytes;
        }
        for (const entry of entries) {
            if (total <= maxBytes) break;
            if (entry.key.url !== sourceUrl) { await cache.delete(entry.key); total -= entry.bytes; }
        }
    })().catch(() => {}).finally(done);
})()""")

internal actual fun browserInvalidateMedia(cacheName: String, sourceUrl: String, done: () -> Unit): Unit = js("""(() => {
    (async () => { if ('caches' in globalThis) await (await caches.open(cacheName)).delete(sourceUrl); })()
        .catch(() => {}).finally(done);
})()""")

internal actual fun browserReleaseMedia(uri: String): Unit = js("URL.revokeObjectURL(uri)")
