package dev.brahmkshatriya.kmplayer.internal

import dev.brahmkshatriya.kmplayer.KMCacheConfig

internal actual fun createPlatformMediaCache(config: KMCacheConfig): KMMediaCache = BrowserKMMediaCache(config)

internal actual fun browserCachedMedia(cacheName: String, sourceUrl: String, done: (String?) -> Unit): Unit = js("""(function() {
    if (!('caches' in globalThis)) { done(null); return; }
    caches.open(cacheName).then(function(cache) {
        return cache.match(sourceUrl).then(function(saved) {
            if (!saved || !saved.ok) return null;
            return saved.blob().then(function(blob) {
                if (!blob.size) return null;
                return cache.delete(sourceUrl).then(function() {
                    return cache.put(sourceUrl, new Response(blob, {headers: {'Content-Type': blob.type || 'application/octet-stream'}}));
                }).then(function() { return URL.createObjectURL(blob); });
            });
        });
    }).then(done).catch(function() { done(null); });
})()""")

internal actual fun browserStoreMedia(cacheName: String, sourceUrl: String, maxBytes: Double, maxTrackBytes: Double, done: () -> Unit): Unit = js("""(function() {
    if (!('caches' in globalThis) || !/^https?:\/\//i.test(sourceUrl)) { done(); return; }
    caches.open(cacheName).then(function(cache) {
        return cache.match(sourceUrl).then(function(saved) {
            if (saved) return;
            return fetch(sourceUrl, {mode:'cors'}).then(function(response) {
                if (!response.ok || response.type === 'opaque' ||
                    Number(response.headers.get('Content-Length')) > maxTrackBytes) return;
                const type = (response.headers.get('Content-Type') || '').toLowerCase();
                if (type.startsWith('text/') || type.includes('json')) return;
                return response.blob().then(function(blob) {
                    if (!blob.size || blob.size > maxTrackBytes) return;
                    return cache.put(sourceUrl, new Response(blob, {headers: {'Content-Type': blob.type || 'application/octet-stream'}}))
                      .then(function() { return cache.keys(); })
                      .then(function(keys) {
                          return Promise.all(keys.map(function(key) {
                              return cache.match(key).then(function(entry) {
                                  return {key:key, bytes:entry ? Number(entry.headers.get('Content-Length')) || 0 : 0,
                                      response:entry};
                              });
                          }));
                      })
                      .then(function(entries) {
                          return Promise.all(entries.map(function(e) {
                              return e.response ? e.response.blob().then(function(b) {e.bytes=b.size;return e;}) : e;
                          }));
                      })
                      .then(function(entries) {
                          let total=entries.reduce(function(n,e){return n+e.bytes;},0);
                          let chain=Promise.resolve();
                          entries.forEach(function(e) {
                              if (total>maxBytes && e.key.url !== sourceUrl) {
                                  total-=e.bytes;
                                  chain=chain.then(function(){return cache.delete(e.key);});
                              }
                          });
                          return chain;
                      });
                });
            });
        });
    }).catch(function(){}).finally(done);
})()""")

internal actual fun browserInvalidateMedia(cacheName: String, sourceUrl: String, done: () -> Unit): Unit = js("""(function() {
    if (!('caches' in globalThis)) {done();return;}
    caches.open(cacheName).then(function(c){return c.delete(sourceUrl);})
        .catch(function(){}).finally(done);
})()""")

internal actual fun browserReleaseMedia(uri: String): Unit = js("URL.revokeObjectURL(uri)")
