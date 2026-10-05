#ifndef KMPLAYER_MFMEDIAENGINE_ABI_H
#define KMPLAYER_MFMEDIAENGINE_ABI_H

/*
 * Minimal IMFMediaEngine ABI used by kmplayer's private Windows bridge.
 * The Kotlin/Native MinGW sysroot currently ships Media Foundation but omits
 * mfmediaengine.h, so keeping this narrow interface here avoids making that
 * SDK-header omission part of kmplayer's public API.
 */

#include <windows.h>
#include <ole2.h>
#include <mfobjects.h>
#include <evr.h>

enum KM_MF_MEDIA_ENGINE_ERR {
    KM_MF_MEDIA_ENGINE_ERR_NOERROR = 0,
    KM_MF_MEDIA_ENGINE_ERR_ABORTED = 1,
    KM_MF_MEDIA_ENGINE_ERR_NETWORK = 2,
    KM_MF_MEDIA_ENGINE_ERR_DECODE = 3,
    KM_MF_MEDIA_ENGINE_ERR_SRC_NOT_SUPPORTED = 4,
    KM_MF_MEDIA_ENGINE_ERR_ENCRYPTED = 5,
};

enum KM_MF_MEDIA_ENGINE_PRELOAD {
    KM_MF_MEDIA_ENGINE_PRELOAD_MISSING = 0,
    KM_MF_MEDIA_ENGINE_PRELOAD_EMPTY = 1,
    KM_MF_MEDIA_ENGINE_PRELOAD_NONE = 2,
    KM_MF_MEDIA_ENGINE_PRELOAD_METADATA = 3,
    KM_MF_MEDIA_ENGINE_PRELOAD_AUTOMATIC = 4,
};

enum KM_MF_MEDIA_ENGINE_CANPLAY {
    KM_MF_MEDIA_ENGINE_CANPLAY_NOT_SUPPORTED = 0,
    KM_MF_MEDIA_ENGINE_CANPLAY_MAYBE = 1,
    KM_MF_MEDIA_ENGINE_CANPLAY_PROBABLY = 2,
};

enum KM_MF_MEDIA_ENGINE_EVENT {
    KM_MF_MEDIA_ENGINE_EVENT_LOADSTART = 1,
    KM_MF_MEDIA_ENGINE_EVENT_PROGRESS = 2,
    KM_MF_MEDIA_ENGINE_EVENT_SUSPEND = 3,
    KM_MF_MEDIA_ENGINE_EVENT_ABORT = 4,
    KM_MF_MEDIA_ENGINE_EVENT_ERROR = 5,
    KM_MF_MEDIA_ENGINE_EVENT_EMPTIED = 6,
    KM_MF_MEDIA_ENGINE_EVENT_STALLED = 7,
    KM_MF_MEDIA_ENGINE_EVENT_PLAY = 8,
    KM_MF_MEDIA_ENGINE_EVENT_PAUSE = 9,
    KM_MF_MEDIA_ENGINE_EVENT_LOADEDMETADATA = 10,
    KM_MF_MEDIA_ENGINE_EVENT_LOADEDDATA = 11,
    KM_MF_MEDIA_ENGINE_EVENT_WAITING = 12,
    KM_MF_MEDIA_ENGINE_EVENT_PLAYING = 13,
    KM_MF_MEDIA_ENGINE_EVENT_CANPLAY = 14,
    KM_MF_MEDIA_ENGINE_EVENT_CANPLAYTHROUGH = 15,
    KM_MF_MEDIA_ENGINE_EVENT_SEEKING = 16,
    KM_MF_MEDIA_ENGINE_EVENT_SEEKED = 17,
    KM_MF_MEDIA_ENGINE_EVENT_TIMEUPDATE = 18,
    KM_MF_MEDIA_ENGINE_EVENT_ENDED = 19,
    KM_MF_MEDIA_ENGINE_EVENT_RATECHANGE = 20,
    KM_MF_MEDIA_ENGINE_EVENT_DURATIONCHANGE = 21,
    KM_MF_MEDIA_ENGINE_EVENT_VOLUMECHANGE = 22,
    KM_MF_MEDIA_ENGINE_EVENT_FORMATCHANGE = 1000,
    KM_MF_MEDIA_ENGINE_EVENT_BUFFERINGSTARTED = 1005,
    KM_MF_MEDIA_ENGINE_EVENT_BUFFERINGENDED = 1006,
};

struct KMIMFMediaError;
struct KMIMFMediaEngineSrcElements;

struct KMIMFMediaTimeRange : public IUnknown {
    virtual DWORD STDMETHODCALLTYPE GetLength() = 0;
    virtual HRESULT STDMETHODCALLTYPE GetStart(DWORD index, double *start) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetEnd(DWORD index, double *end) = 0;
    virtual BOOL STDMETHODCALLTYPE ContainsTime(double time) = 0;
    virtual HRESULT STDMETHODCALLTYPE AddRange(double start, double end) = 0;
    virtual HRESULT STDMETHODCALLTYPE Clear() = 0;
};

struct KMIMFMediaEngine : public IUnknown {
    virtual HRESULT STDMETHODCALLTYPE GetError(KMIMFMediaError **error) = 0;
    virtual HRESULT STDMETHODCALLTYPE SetErrorCode(KM_MF_MEDIA_ENGINE_ERR error) = 0;
    virtual HRESULT STDMETHODCALLTYPE SetSourceElements(KMIMFMediaEngineSrcElements *elements) = 0;
    virtual HRESULT STDMETHODCALLTYPE SetSource(BSTR url) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetCurrentSource(BSTR *url) = 0;
    virtual USHORT STDMETHODCALLTYPE GetNetworkState() = 0;
    virtual KM_MF_MEDIA_ENGINE_PRELOAD STDMETHODCALLTYPE GetPreload() = 0;
    virtual HRESULT STDMETHODCALLTYPE SetPreload(KM_MF_MEDIA_ENGINE_PRELOAD preload) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetBuffered(KMIMFMediaTimeRange **buffered) = 0;
    virtual HRESULT STDMETHODCALLTYPE Load() = 0;
    virtual HRESULT STDMETHODCALLTYPE CanPlayType(BSTR type, KM_MF_MEDIA_ENGINE_CANPLAY *answer) = 0;
    virtual USHORT STDMETHODCALLTYPE GetReadyState() = 0;
    virtual BOOL STDMETHODCALLTYPE IsSeeking() = 0;
    virtual double STDMETHODCALLTYPE GetCurrentTime() = 0;
    virtual HRESULT STDMETHODCALLTYPE SetCurrentTime(double time) = 0;
    virtual double STDMETHODCALLTYPE GetStartTime() = 0;
    virtual double STDMETHODCALLTYPE GetDuration() = 0;
    virtual BOOL STDMETHODCALLTYPE IsPaused() = 0;
    virtual double STDMETHODCALLTYPE GetDefaultPlaybackRate() = 0;
    virtual HRESULT STDMETHODCALLTYPE SetDefaultPlaybackRate(double rate) = 0;
    virtual double STDMETHODCALLTYPE GetPlaybackRate() = 0;
    virtual HRESULT STDMETHODCALLTYPE SetPlaybackRate(double rate) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetPlayed(KMIMFMediaTimeRange **played) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetSeekable(KMIMFMediaTimeRange **seekable) = 0;
    virtual BOOL STDMETHODCALLTYPE IsEnded() = 0;
    virtual BOOL STDMETHODCALLTYPE GetAutoPlay() = 0;
    virtual HRESULT STDMETHODCALLTYPE SetAutoPlay(BOOL autoplay) = 0;
    virtual BOOL STDMETHODCALLTYPE GetLoop() = 0;
    virtual HRESULT STDMETHODCALLTYPE SetLoop(BOOL loop) = 0;
    virtual HRESULT STDMETHODCALLTYPE Play() = 0;
    virtual HRESULT STDMETHODCALLTYPE Pause() = 0;
    virtual BOOL STDMETHODCALLTYPE GetMuted() = 0;
    virtual HRESULT STDMETHODCALLTYPE SetMuted(BOOL muted) = 0;
    virtual double STDMETHODCALLTYPE GetVolume() = 0;
    virtual HRESULT STDMETHODCALLTYPE SetVolume(double volume) = 0;
    virtual BOOL STDMETHODCALLTYPE HasVideo() = 0;
    virtual BOOL STDMETHODCALLTYPE HasAudio() = 0;
    virtual HRESULT STDMETHODCALLTYPE GetNativeVideoSize(DWORD *cx, DWORD *cy) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetVideoAspectRatio(DWORD *cx, DWORD *cy) = 0;
    virtual HRESULT STDMETHODCALLTYPE Shutdown() = 0;
    virtual HRESULT STDMETHODCALLTYPE TransferVideoFrame(
        IUnknown *surface,
        const MFVideoNormalizedRect *src,
        const RECT *dst,
        const MFARGB *color
    ) = 0;
    virtual HRESULT STDMETHODCALLTYPE OnVideoStreamTick(LONGLONG *time) = 0;
};

struct KMIMFMediaEngineNotify : public IUnknown {
    virtual HRESULT STDMETHODCALLTYPE EventNotify(DWORD event, DWORD_PTR param1, DWORD param2) = 0;
};

struct KMIMFMediaEngineClassFactory : public IUnknown {
    virtual HRESULT STDMETHODCALLTYPE CreateInstance(
        DWORD flags,
        IMFAttributes *attributes,
        KMIMFMediaEngine **engine
    ) = 0;
    virtual HRESULT STDMETHODCALLTYPE CreateTimeRange(KMIMFMediaTimeRange **range) = 0;
    virtual HRESULT STDMETHODCALLTYPE CreateError(KMIMFMediaError **error) = 0;
};

#endif
