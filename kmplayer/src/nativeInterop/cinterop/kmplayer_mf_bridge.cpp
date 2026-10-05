#include "kmplayer_mf_bridge.h"
#include "kmplayer_mfmediaengine_abi.h"

#include <d3d11.h>
#include <mfapi.h>
#include <objbase.h>
#include <oleauto.h>

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <new>
#include <string>

static const GUID KM_CLSID_MFMediaEngineClassFactory =
    {0xb44392da, 0x499b, 0x446b, {0xa4, 0xcb, 0x00, 0x5f, 0xea, 0xd0, 0xe6, 0xd5}};
static const GUID KM_IID_IMFMediaEngineClassFactory =
    {0x4d645ace, 0x26aa, 0x4688, {0x9b, 0xe1, 0xdf, 0x35, 0x16, 0x99, 0x0b, 0x93}};
static const GUID KM_IID_IMFMediaEngineNotify =
    {0xfee7c112, 0xe776, 0x42b5, {0x9b, 0xbf, 0x00, 0x48, 0x52, 0x4e, 0x2b, 0xd5}};
static const GUID KM_MF_MEDIA_ENGINE_CALLBACK =
    {0xc60381b8, 0x83a4, 0x41f8, {0xa3, 0xd0, 0xde, 0x05, 0x07, 0x68, 0x49, 0xa9}};
static const GUID KM_MF_MEDIA_ENGINE_DXGI_MANAGER =
    {0x065702da, 0x1094, 0x486d, {0x86, 0x17, 0xee, 0x7c, 0xc4, 0xee, 0x46, 0x48}};
static const DWORD KM_MF_MEDIA_ENGINE_AUDIOONLY = 0x1;

enum {
    KM_PENDING_READY = 1 << 0,
    KM_PENDING_ENDED = 1 << 1,
    KM_PENDING_ERROR = 1 << 2,
    KM_PENDING_BUFFERING_STARTED = 1 << 3,
    KM_PENDING_BUFFERING_ENDED = 1 << 4,
};

struct kmplayer_mf_player;

class KMMediaEngineNotify final : public KMIMFMediaEngineNotify {
public:
    explicit KMMediaEngineNotify(kmplayer_mf_player *owner) : owner_(owner) {}

    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID iid, void **result) override;
    ULONG STDMETHODCALLTYPE AddRef() override { return static_cast<ULONG>(InterlockedIncrement(&ref_count_)); }
    ULONG STDMETHODCALLTYPE Release() override {
        const LONG remaining = InterlockedDecrement(&ref_count_);
        if (remaining == 0) delete this;
        return static_cast<ULONG>(remaining);
    }
    HRESULT STDMETHODCALLTYPE EventNotify(DWORD event, DWORD_PTR param1, DWORD param2) override;

    void detach() { owner_ = nullptr; }

private:
    volatile LONG ref_count_ = 1;
    kmplayer_mf_player *owner_;
};

struct kmplayer_mf_player {
    KMIMFMediaEngine *engine = nullptr;
    KMMediaEngineNotify *notify = nullptr;
    ID3D11Device *d3d_device = nullptr;
    ID3D11DeviceContext *d3d_context = nullptr;
    IMFDXGIDeviceManager *dxgi_manager = nullptr;
    UINT dxgi_reset_token = 0;
    ID3D11Texture2D *render_texture = nullptr;
    ID3D11Texture2D *staging_texture = nullptr;
    int texture_width = 0;
    int texture_height = 0;
    volatile LONG pending = 0;
    volatile LONG last_error = 0;
    bool mf_started = false;
    CO_MTA_USAGE_COOKIE mta_cookie = nullptr;
};

static void km_copy_message(char *destination, size_t size, const char *source) {
    if (!destination || size == 0) return;
    if (!source) source = "";
    std::snprintf(destination, size, "%s", source);
}

static void km_copy_hresult(char *destination, size_t size, const char *prefix, HRESULT hr) {
    if (!destination || size == 0) return;
    std::snprintf(destination, size, "%s (HRESULT 0x%08lx)", prefix, static_cast<unsigned long>(hr));
}

static std::wstring km_utf8_to_wide(const char *value) {
    if (!value || !*value) return std::wstring();
    const int count = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, value, -1, nullptr, 0);
    if (count <= 0) return std::wstring();
    std::wstring result(static_cast<size_t>(count), L'\0');
    if (MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, value, -1, result.data(), count) <= 0) {
        return std::wstring();
    }
    result.resize(static_cast<size_t>(count - 1));
    return result;
}

static void km_queue(kmplayer_mf_player *player, LONG flag) {
    if (player) InterlockedOr(&player->pending, flag);
}

static void km_release_video_textures(kmplayer_mf_player *player) {
    if (!player) return;
    if (player->staging_texture) {
        player->staging_texture->Release();
        player->staging_texture = nullptr;
    }
    if (player->render_texture) {
        player->render_texture->Release();
        player->render_texture = nullptr;
    }
    player->texture_width = 0;
    player->texture_height = 0;
}

static HRESULT km_create_video_textures(kmplayer_mf_player *player, int width, int height) {
    if (!player || !player->d3d_device || width <= 0 || height <= 0) return E_INVALIDARG;
    if (player->render_texture && player->staging_texture &&
        player->texture_width == width && player->texture_height == height) {
        return S_OK;
    }

    km_release_video_textures(player);

    D3D11_TEXTURE2D_DESC render_desc = {};
    render_desc.Width = static_cast<UINT>(width);
    render_desc.Height = static_cast<UINT>(height);
    render_desc.MipLevels = 1;
    render_desc.ArraySize = 1;
    render_desc.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
    render_desc.SampleDesc.Count = 1;
    render_desc.Usage = D3D11_USAGE_DEFAULT;
    render_desc.BindFlags = D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE;

    HRESULT hr = player->d3d_device->CreateTexture2D(&render_desc, nullptr, &player->render_texture);
    if (FAILED(hr)) return hr;

    D3D11_TEXTURE2D_DESC staging_desc = render_desc;
    staging_desc.Usage = D3D11_USAGE_STAGING;
    staging_desc.BindFlags = 0;
    staging_desc.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
    hr = player->d3d_device->CreateTexture2D(&staging_desc, nullptr, &player->staging_texture);
    if (FAILED(hr)) {
        km_release_video_textures(player);
        return hr;
    }

    player->texture_width = width;
    player->texture_height = height;
    return S_OK;
}

HRESULT STDMETHODCALLTYPE KMMediaEngineNotify::QueryInterface(REFIID iid, void **result) {
    if (!result) return E_POINTER;
    if (IsEqualIID(iid, IID_IUnknown) || IsEqualIID(iid, KM_IID_IMFMediaEngineNotify)) {
        *result = static_cast<KMIMFMediaEngineNotify *>(this);
        AddRef();
        return S_OK;
    }
    *result = nullptr;
    return E_NOINTERFACE;
}

HRESULT STDMETHODCALLTYPE KMMediaEngineNotify::EventNotify(DWORD event, DWORD_PTR param1, DWORD param2) {
    kmplayer_mf_player *player = owner_;
    if (!player) return S_OK;

    switch (event) {
        case KM_MF_MEDIA_ENGINE_EVENT_LOADEDMETADATA:
        case KM_MF_MEDIA_ENGINE_EVENT_LOADEDDATA:
        case KM_MF_MEDIA_ENGINE_EVENT_CANPLAY:
        case KM_MF_MEDIA_ENGINE_EVENT_CANPLAYTHROUGH:
        case KM_MF_MEDIA_ENGINE_EVENT_PLAYING:
            km_queue(player, KM_PENDING_READY | KM_PENDING_BUFFERING_ENDED);
            break;
        case KM_MF_MEDIA_ENGINE_EVENT_WAITING:
        case KM_MF_MEDIA_ENGINE_EVENT_STALLED:
        case KM_MF_MEDIA_ENGINE_EVENT_BUFFERINGSTARTED:
            km_queue(player, KM_PENDING_BUFFERING_STARTED);
            break;
        case KM_MF_MEDIA_ENGINE_EVENT_BUFFERINGENDED:
            km_queue(player, KM_PENDING_BUFFERING_ENDED);
            break;
        case KM_MF_MEDIA_ENGINE_EVENT_ENDED:
            km_queue(player, KM_PENDING_ENDED);
            break;
        case KM_MF_MEDIA_ENGINE_EVENT_ERROR:
            InterlockedExchange(&player->last_error, static_cast<LONG>(param2 ? param2 : param1));
            km_queue(player, KM_PENDING_ERROR);
            break;
        default:
            break;
    }
    return S_OK;
}

kmplayer_mf_player *kmplayer_mf_player_create(int audio_only, char *error_message, size_t error_message_size) {
    kmplayer_mf_player *player = new (std::nothrow) kmplayer_mf_player();
    if (!player) {
        km_copy_message(error_message, error_message_size, "out of memory");
        return nullptr;
    }

    HRESULT hr = CoIncrementMTAUsage(&player->mta_cookie);
    if (FAILED(hr)) {
        km_copy_hresult(error_message, error_message_size, "CoIncrementMTAUsage failed", hr);
        delete player;
        return nullptr;
    }

    hr = MFStartup(MF_VERSION, MFSTARTUP_FULL);
    if (FAILED(hr)) {
        km_copy_hresult(error_message, error_message_size, "MFStartup failed", hr);
        CoDecrementMTAUsage(player->mta_cookie);
        player->mta_cookie = nullptr;
        delete player;
        return nullptr;
    }
    player->mf_started = true;

    player->notify = new (std::nothrow) KMMediaEngineNotify(player);
    if (!player->notify) {
        km_copy_message(error_message, error_message_size, "out of memory");
        kmplayer_mf_player_destroy(player);
        return nullptr;
    }

    KMIMFMediaEngineClassFactory *factory = nullptr;
    hr = CoCreateInstance(
        KM_CLSID_MFMediaEngineClassFactory,
        nullptr,
        CLSCTX_INPROC_SERVER,
        KM_IID_IMFMediaEngineClassFactory,
        reinterpret_cast<void **>(&factory)
    );
    if (FAILED(hr) || !factory) {
        km_copy_hresult(error_message, error_message_size, "Media Foundation Media Engine is unavailable", hr);
        kmplayer_mf_player_destroy(player);
        return nullptr;
    }

    IMFAttributes *attributes = nullptr;
    hr = MFCreateAttributes(&attributes, audio_only ? 1 : 2);
    if (SUCCEEDED(hr)) {
        hr = attributes->SetUnknown(KM_MF_MEDIA_ENGINE_CALLBACK, player->notify);
    }
    if (SUCCEEDED(hr) && !audio_only) {
        D3D_FEATURE_LEVEL feature_level = D3D_FEATURE_LEVEL_11_0;
        hr = D3D11CreateDevice(
            nullptr,
            D3D_DRIVER_TYPE_HARDWARE,
            nullptr,
            D3D11_CREATE_DEVICE_BGRA_SUPPORT,
            nullptr,
            0,
            D3D11_SDK_VERSION,
            &player->d3d_device,
            &feature_level,
            &player->d3d_context
        );
        if (FAILED(hr)) {
            hr = D3D11CreateDevice(
                nullptr,
                D3D_DRIVER_TYPE_WARP,
                nullptr,
                D3D11_CREATE_DEVICE_BGRA_SUPPORT,
                nullptr,
                0,
                D3D11_SDK_VERSION,
                &player->d3d_device,
                &feature_level,
                &player->d3d_context
            );
        }
    }
    if (SUCCEEDED(hr) && !audio_only) {
        hr = MFCreateDXGIDeviceManager(&player->dxgi_reset_token, &player->dxgi_manager);
    }
    if (SUCCEEDED(hr) && !audio_only) {
        hr = player->dxgi_manager->ResetDevice(player->d3d_device, player->dxgi_reset_token);
    }
    if (SUCCEEDED(hr) && !audio_only) {
        hr = attributes->SetUnknown(KM_MF_MEDIA_ENGINE_DXGI_MANAGER, player->dxgi_manager);
    }
    if (SUCCEEDED(hr)) {
        hr = factory->CreateInstance(audio_only ? KM_MF_MEDIA_ENGINE_AUDIOONLY : 0u, attributes, &player->engine);
    }

    if (attributes) attributes->Release();
    factory->Release();

    if (FAILED(hr) || !player->engine) {
        km_copy_hresult(error_message, error_message_size, "Unable to create IMFMediaEngine", hr);
        kmplayer_mf_player_destroy(player);
        return nullptr;
    }

    return player;
}

void kmplayer_mf_player_destroy(kmplayer_mf_player *player) {
    if (!player) return;
    if (player->engine) {
        player->engine->Pause();
        player->engine->Shutdown();
        player->engine->Release();
        player->engine = nullptr;
    }
    if (player->notify) {
        player->notify->detach();
        player->notify->Release();
        player->notify = nullptr;
    }
    km_release_video_textures(player);
    if (player->dxgi_manager) {
        player->dxgi_manager->Release();
        player->dxgi_manager = nullptr;
    }
    if (player->d3d_context) {
        player->d3d_context->Release();
        player->d3d_context = nullptr;
    }
    if (player->d3d_device) {
        player->d3d_device->Release();
        player->d3d_device = nullptr;
    }
    if (player->mf_started) {
        MFShutdown();
        player->mf_started = false;
    }
    if (player->mta_cookie) {
        CoDecrementMTAUsage(player->mta_cookie);
        player->mta_cookie = nullptr;
    }
    delete player;
}

int kmplayer_mf_player_load(kmplayer_mf_player *player, const char *uri_utf8, int play_when_ready) {
    if (!player || !player->engine || !uri_utf8) return 0;
    const std::wstring uri = km_utf8_to_wide(uri_utf8);
    if (uri.empty()) return 0;

    InterlockedExchange(&player->pending, 0);
    InterlockedExchange(&player->last_error, 0);
    player->engine->Pause();
    player->engine->SetAutoPlay(play_when_ready ? TRUE : FALSE);

    BSTR source = SysAllocStringLen(uri.data(), static_cast<UINT>(uri.size()));
    if (!source) return 0;
    HRESULT hr = player->engine->SetSource(source);
    SysFreeString(source);
    if (FAILED(hr)) return 0;
    hr = player->engine->Load();
    return SUCCEEDED(hr) ? 1 : 0;
}

int kmplayer_mf_player_play(kmplayer_mf_player *player) {
    return player && player->engine && SUCCEEDED(player->engine->Play()) ? 1 : 0;
}

int kmplayer_mf_player_pause(kmplayer_mf_player *player) {
    return player && player->engine && SUCCEEDED(player->engine->Pause()) ? 1 : 0;
}

int kmplayer_mf_player_stop(kmplayer_mf_player *player) {
    if (!player || !player->engine) return 0;
    HRESULT hr = player->engine->Pause();
    if (FAILED(hr)) return 0;
    hr = player->engine->SetCurrentTime(0.0);
    return SUCCEEDED(hr) ? 1 : 0;
}

int kmplayer_mf_player_seek_ms(kmplayer_mf_player *player, int64_t position_ms) {
    if (!player || !player->engine) return 0;
    if (position_ms < 0) position_ms = 0;
    return SUCCEEDED(player->engine->SetCurrentTime(static_cast<double>(position_ms) / 1000.0)) ? 1 : 0;
}

int kmplayer_mf_player_set_volume(kmplayer_mf_player *player, double volume_0_to_1) {
    if (!player || !player->engine || !std::isfinite(volume_0_to_1)) return 0;
    if (volume_0_to_1 < 0.0) volume_0_to_1 = 0.0;
    if (volume_0_to_1 > 1.0) volume_0_to_1 = 1.0;
    return SUCCEEDED(player->engine->SetVolume(volume_0_to_1)) ? 1 : 0;
}

int kmplayer_mf_player_set_muted(kmplayer_mf_player *player, int muted) {
    return player && player->engine && SUCCEEDED(player->engine->SetMuted(muted ? TRUE : FALSE)) ? 1 : 0;
}

int kmplayer_mf_player_set_speed(kmplayer_mf_player *player, double speed) {
    if (!player || !player->engine || !std::isfinite(speed) || speed <= 0.0) return 0;
    return SUCCEEDED(player->engine->SetPlaybackRate(speed)) ? 1 : 0;
}

int64_t kmplayer_mf_player_position_ms(kmplayer_mf_player *player) {
    if (!player || !player->engine) return -1;
    const double value = player->engine->GetCurrentTime();
    if (!std::isfinite(value) || value < 0.0) return -1;
    return static_cast<int64_t>(value * 1000.0);
}

int64_t kmplayer_mf_player_duration_ms(kmplayer_mf_player *player) {
    if (!player || !player->engine) return -1;
    const double value = player->engine->GetDuration();
    if (!std::isfinite(value) || value <= 0.0) return -1;
    return static_cast<int64_t>(value * 1000.0);
}

int kmplayer_mf_player_has_hls(kmplayer_mf_player *player) {
    if (!player || !player->engine) return 0;
    const wchar_t *types[] = {
        L"application/vnd.apple.mpegurl",
        L"application/x-mpegURL",
    };
    for (const wchar_t *type : types) {
        BSTR mime = SysAllocString(type);
        if (!mime) continue;
        KM_MF_MEDIA_ENGINE_CANPLAY answer = KM_MF_MEDIA_ENGINE_CANPLAY_NOT_SUPPORTED;
        const HRESULT hr = player->engine->CanPlayType(mime, &answer);
        SysFreeString(mime);
        if (SUCCEEDED(hr) && answer != KM_MF_MEDIA_ENGINE_CANPLAY_NOT_SUPPORTED) return 1;
    }
    return 0;
}

int kmplayer_mf_player_is_seekable(kmplayer_mf_player *player) {
    if (!player || !player->engine) return 0;
    KMIMFMediaTimeRange *range = nullptr;
    const HRESULT hr = player->engine->GetSeekable(&range);
    if (FAILED(hr) || !range) return 0;
    const int result = range->GetLength() > 0 ? 1 : 0;
    range->Release();
    return result;
}

int kmplayer_mf_player_is_live(kmplayer_mf_player *player) {
    return kmplayer_mf_player_duration_ms(player) < 0 ? 1 : 0;
}

int kmplayer_mf_player_video_width(kmplayer_mf_player *player) {
    if (!player || !player->engine) return 0;
    DWORD width = 0, height = 0;
    if (FAILED(player->engine->GetNativeVideoSize(&width, &height))) return 0;
    return static_cast<int>(width);
}

int kmplayer_mf_player_video_height(kmplayer_mf_player *player) {
    if (!player || !player->engine) return 0;
    DWORD width = 0, height = 0;
    if (FAILED(player->engine->GetNativeVideoSize(&width, &height))) return 0;
    return static_cast<int>(height);
}

uintptr_t kmplayer_mf_player_engine(kmplayer_mf_player *player) {
    return player && player->engine ? reinterpret_cast<uintptr_t>(player->engine) : 0;
}

int kmplayer_mf_player_render_bgra(
    kmplayer_mf_player *player,
    void *pixels_void,
    int width,
    int height,
    int stride,
    int scale_mode
) {
    if (!player || !player->engine || !player->d3d_device || !player->d3d_context ||
        !pixels_void || width <= 0 || height <= 0 || stride < width * 4) {
        return 0;
    }

    unsigned char *pixels = static_cast<unsigned char *>(pixels_void);
    for (int y = 0; y < height; ++y) {
        unsigned char *row = pixels + static_cast<size_t>(y) * static_cast<size_t>(stride);
        for (int x = 0; x < width; ++x) {
            row[static_cast<size_t>(x) * 4u + 0u] = 0;
            row[static_cast<size_t>(x) * 4u + 1u] = 0;
            row[static_cast<size_t>(x) * 4u + 2u] = 0;
            row[static_cast<size_t>(x) * 4u + 3u] = 255;
        }
    }

    if (FAILED(km_create_video_textures(player, width, height))) return 0;

    DWORD video_width = 0;
    DWORD video_height = 0;
    if (FAILED(player->engine->GetNativeVideoSize(&video_width, &video_height)) ||
        video_width == 0 || video_height == 0) {
        return 1;
    }

    MFVideoNormalizedRect source = {0.0f, 0.0f, 1.0f, 1.0f};
    RECT destination = {0, 0, width, height};
    const double source_aspect = static_cast<double>(video_width) / static_cast<double>(video_height);
    const double target_aspect = static_cast<double>(width) / static_cast<double>(height);

    if (scale_mode == 0) {
        if (target_aspect > source_aspect) {
            const int fitted_width = static_cast<int>(height * source_aspect + 0.5);
            destination.left = (width - fitted_width) / 2;
            destination.right = destination.left + fitted_width;
        } else {
            const int fitted_height = static_cast<int>(width / source_aspect + 0.5);
            destination.top = (height - fitted_height) / 2;
            destination.bottom = destination.top + fitted_height;
        }
    } else if (scale_mode == 1) {
        if (target_aspect > source_aspect) {
            const double visible = source_aspect / target_aspect;
            source.top = static_cast<float>((1.0 - visible) * 0.5);
            source.bottom = static_cast<float>(1.0 - source.top);
        } else {
            const double visible = target_aspect / source_aspect;
            source.left = static_cast<float>((1.0 - visible) * 0.5);
            source.right = static_cast<float>(1.0 - source.left);
        }
    }

    MFARGB background = {0, 0, 0, 255};
    const HRESULT transfer = player->engine->TransferVideoFrame(
        player->render_texture,
        &source,
        &destination,
        &background
    );
    if (FAILED(transfer)) return 1;

    player->d3d_context->CopyResource(player->staging_texture, player->render_texture);
    D3D11_MAPPED_SUBRESOURCE mapped = {};
    const HRESULT mapped_result = player->d3d_context->Map(
        player->staging_texture,
        0,
        D3D11_MAP_READ,
        0,
        &mapped
    );
    if (FAILED(mapped_result)) return 0;

    const size_t bytes_per_row = static_cast<size_t>(width) * 4u;
    for (int y = 0; y < height; ++y) {
        std::memcpy(
            pixels + static_cast<size_t>(y) * static_cast<size_t>(stride),
            static_cast<const unsigned char *>(mapped.pData) + static_cast<size_t>(y) * mapped.RowPitch,
            bytes_per_row
        );
    }
    player->d3d_context->Unmap(player->staging_texture, 0);
    return 1;
}

static int km_take_pending(kmplayer_mf_player *player, LONG flag) {
    for (;;) {
        const LONG current = InterlockedCompareExchange(&player->pending, 0, 0);
        if ((current & flag) == 0) return 0;
        const LONG next = current & ~flag;
        if (InterlockedCompareExchange(&player->pending, next, current) == current) return 1;
    }
}

int kmplayer_mf_player_poll_event(
    kmplayer_mf_player *player,
    char *message,
    size_t message_size
) {
    if (!player) return KMPLAYER_MF_EVENT_NONE;
    if (km_take_pending(player, KM_PENDING_ERROR)) {
        const LONG error = InterlockedCompareExchange(&player->last_error, 0, 0);
        if (message && message_size > 0) {
            std::snprintf(message, message_size, "Media Foundation playback error (%ld)", error);
        }
        return KMPLAYER_MF_EVENT_ERROR;
    }
    if (km_take_pending(player, KM_PENDING_ENDED)) return KMPLAYER_MF_EVENT_ENDED;
    if (km_take_pending(player, KM_PENDING_READY)) return KMPLAYER_MF_EVENT_READY;
    if (km_take_pending(player, KM_PENDING_BUFFERING_STARTED)) return KMPLAYER_MF_EVENT_BUFFERING_STARTED;
    if (km_take_pending(player, KM_PENDING_BUFFERING_ENDED)) return KMPLAYER_MF_EVENT_BUFFERING_ENDED;
    return KMPLAYER_MF_EVENT_NONE;
}
