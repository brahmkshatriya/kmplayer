/*
 * In-process, passthrough Media Foundation audio transform.
 *
 * The media engine inserts this as an optional audio effect before loading
 * a source. Playback samples are passed through unchanged. When enabled,
 * a bounded copy of decoded mono PCM is exposed to the Kotlin FFT worker.
 *
 * Included by kmplayer_mf_bridge.cpp after Media Foundation headers.
 */
class KMAudioTap final : public IMFTransform {
public:
    KMAudioTap() { InitializeCriticalSection(&lock_); }
    ~KMAudioTap() {
        if (pending_) pending_->Release();
        if (input_type_) input_type_->Release();
        if (output_type_) output_type_->Release();
        DeleteCriticalSection(&lock_);
    }

    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID iid, void **object) override {
        if (!object) return E_POINTER;
        *object = nullptr;
        if (!IsEqualIID(iid, IID_IUnknown) && !IsEqualIID(iid, KM_IID_IMFTransform))
            return E_NOINTERFACE;
        *object = static_cast<IMFTransform *>(this);
        AddRef();
        return S_OK;
    }
    ULONG STDMETHODCALLTYPE AddRef() override {
        return static_cast<ULONG>(InterlockedIncrement(&refs_));
    }
    ULONG STDMETHODCALLTYPE Release() override {
        const LONG remaining = InterlockedDecrement(&refs_);
        if (!remaining) delete this;
        return static_cast<ULONG>(remaining);
    }

    void enable(bool enabled) {
        EnterCriticalSection(&lock_);
        enabled_ = enabled;
        read_ = write_ = 0;
        LeaveCriticalSection(&lock_);
    }

    int copy(float *destination, int capacity, int *sample_rate_hz) {
        if (!destination || capacity <= 0) return 0;
        EnterCriticalSection(&lock_);
        const unsigned int available = write_ - read_;
        const unsigned int count = std::min<unsigned int>(available, static_cast<unsigned int>(capacity));
        for (unsigned int i = 0; i < count; ++i)
            destination[i] = samples_[(read_ + i) % kCapacity];
        read_ += count;
        if (sample_rate_hz) *sample_rate_hz = enabled_ ? static_cast<int>(sample_rate_) : 0;
        LeaveCriticalSection(&lock_);
        return static_cast<int>(count);
    }

    HRESULT STDMETHODCALLTYPE GetStreamLimits(DWORD *in_min, DWORD *in_max, DWORD *out_min, DWORD *out_max) override {
        if (!in_min || !in_max || !out_min || !out_max) return E_POINTER;
        *in_min = *in_max = *out_min = *out_max = 1;
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE GetStreamCount(DWORD *inputs, DWORD *outputs) override {
        if (!inputs || !outputs) return E_POINTER;
        *inputs = *outputs = 1;
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE GetStreamIDs(DWORD, DWORD *, DWORD, DWORD *) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE GetInputStreamInfo(DWORD id, MFT_INPUT_STREAM_INFO *info) override {
        if (id != 0) return MF_E_INVALIDSTREAMNUMBER;
        if (!info) return E_POINTER;
        *info = {};
        info->dwFlags = MFT_INPUT_STREAM_WHOLE_SAMPLES | MFT_INPUT_STREAM_SINGLE_SAMPLE_PER_BUFFER;
        info->cbSize = 1;
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE GetOutputStreamInfo(DWORD id, MFT_OUTPUT_STREAM_INFO *info) override {
        if (id != 0) return MF_E_INVALIDSTREAMNUMBER;
        if (!info) return E_POINTER;
        *info = {};
        info->dwFlags = MFT_OUTPUT_STREAM_WHOLE_SAMPLES |
            MFT_OUTPUT_STREAM_SINGLE_SAMPLE_PER_BUFFER | MFT_OUTPUT_STREAM_PROVIDES_SAMPLES;
        info->cbSize = 0;
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE GetAttributes(IMFAttributes **attrs) override {
        if (!attrs) return E_POINTER;
        return MFCreateAttributes(attrs, 1);
    }
    HRESULT STDMETHODCALLTYPE GetInputStreamAttributes(DWORD, IMFAttributes **) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE GetOutputStreamAttributes(DWORD, IMFAttributes **) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE DeleteInputStream(DWORD) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE AddInputStreams(DWORD, DWORD *) override { return E_NOTIMPL; }

    HRESULT STDMETHODCALLTYPE GetInputAvailableType(DWORD id, DWORD index, IMFMediaType **type) override {
        if (id != 0) return MF_E_INVALIDSTREAMNUMBER;
        if (!type) return E_POINTER;
        *type = nullptr;
        if (output_type_) {
            if (index != 0) return MF_E_NO_MORE_TYPES;
            return clone_type(output_type_, type);
        }
        // Advertise common PCM formats while accepting arbitrary compatible
        // concrete sample rates and channel layouts in SetInputType.
        if (index >= 16) return MF_E_NO_MORE_TYPES;
        const UINT32 rate = (index / 4) == 0 ? 48000u :
            (index / 4) == 1 ? 44100u :
            (index / 4) == 2 ? 96000u : 32000u;
        const UINT32 channels = (index % 4) < 2 ? 2u : 1u;
        const bool floating = (index % 2) == 0;
        const UINT32 bytes_per_sample = floating ? 4u : 2u;
        const UINT32 block_align = channels * bytes_per_sample;
        HRESULT hr = MFCreateMediaType(type);
        if (FAILED(hr)) return hr;
        hr = (*type)->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Audio);
        if (SUCCEEDED(hr)) hr = (*type)->SetGUID(MF_MT_SUBTYPE,
            floating ? MFAudioFormat_Float : MFAudioFormat_PCM);
        if (SUCCEEDED(hr)) hr = (*type)->SetUINT32(MF_MT_AUDIO_NUM_CHANNELS, channels);
        if (SUCCEEDED(hr)) hr = (*type)->SetUINT32(MF_MT_AUDIO_SAMPLES_PER_SECOND, rate);
        if (SUCCEEDED(hr)) hr = (*type)->SetUINT32(MF_MT_AUDIO_BITS_PER_SAMPLE, bytes_per_sample * 8);
        if (SUCCEEDED(hr)) hr = (*type)->SetUINT32(MF_MT_AUDIO_BLOCK_ALIGNMENT, block_align);
        if (SUCCEEDED(hr)) hr = (*type)->SetUINT32(MF_MT_AUDIO_AVG_BYTES_PER_SECOND, rate * block_align);
        if (SUCCEEDED(hr)) hr = (*type)->SetUINT32(MF_MT_ALL_SAMPLES_INDEPENDENT, TRUE);
        if (SUCCEEDED(hr)) hr = (*type)->SetUINT32(MF_MT_FIXED_SIZE_SAMPLES, TRUE);
        if (FAILED(hr)) { (*type)->Release(); *type = nullptr; }
        return hr;
    }
    HRESULT STDMETHODCALLTYPE GetOutputAvailableType(DWORD id, DWORD index, IMFMediaType **type) override {
        if (id != 0) return MF_E_INVALIDSTREAMNUMBER;
        if (!type) return E_POINTER;
        *type = nullptr;
        if (index != 0) return MF_E_NO_MORE_TYPES;
        if (!input_type_) return MF_E_TRANSFORM_TYPE_NOT_SET;
        return clone_type(input_type_, type);
    }
    HRESULT STDMETHODCALLTYPE SetInputType(DWORD id, IMFMediaType *type, DWORD flags) override {
        if (id != 0) return MF_E_INVALIDSTREAMNUMBER;
        if (!type) {
            if (flags & MFT_SET_TYPE_TEST_ONLY) return E_INVALIDARG;
            if (pending_) return MF_E_TRANSFORM_CANNOT_CHANGE_MEDIATYPE_WHILE_PROCESSING;
            replace(input_type_, nullptr);
            replace(output_type_, nullptr);
            return S_OK;
        }
        if (!is_supported(type)) return MF_E_INVALIDMEDIATYPE;
        if (output_type_ && !equivalent(type, output_type_)) return MF_E_INVALIDMEDIATYPE;
        if (!(flags & MFT_SET_TYPE_TEST_ONLY)) {
            if (pending_) return MF_E_TRANSFORM_CANNOT_CHANGE_MEDIATYPE_WHILE_PROCESSING;
            replace(input_type_, type);
            update_format(type);
        }
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE SetOutputType(DWORD id, IMFMediaType *type, DWORD flags) override {
        if (id != 0) return MF_E_INVALIDSTREAMNUMBER;
        if (!type) {
            if (!(flags & MFT_SET_TYPE_TEST_ONLY)) replace(output_type_, nullptr);
            return S_OK;
        }
        if (!is_supported(type) || (input_type_ && !equivalent(input_type_, type)))
            return MF_E_INVALIDMEDIATYPE;
        if (!(flags & MFT_SET_TYPE_TEST_ONLY)) replace(output_type_, type);
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE GetInputCurrentType(DWORD id, IMFMediaType **type) override {
        return current_type(id, input_type_, type);
    }
    HRESULT STDMETHODCALLTYPE GetOutputCurrentType(DWORD id, IMFMediaType **type) override {
        return current_type(id, output_type_, type);
    }
    HRESULT STDMETHODCALLTYPE GetInputStatus(DWORD id, DWORD *flags) override {
        if (id != 0) return MF_E_INVALIDSTREAMNUMBER;
        if (!flags) return E_POINTER;
        *flags = pending_ ? 0 : MFT_INPUT_STATUS_ACCEPT_DATA;
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE GetOutputStatus(DWORD *flags) override {
        if (!flags) return E_POINTER;
        *flags = pending_ ? MFT_OUTPUT_STATUS_SAMPLE_READY : 0;
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE SetOutputBounds(LONGLONG, LONGLONG) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE ProcessEvent(DWORD, IMFMediaEvent *) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE ProcessMessage(MFT_MESSAGE_TYPE message, ULONG_PTR) override {
        if (message == MFT_MESSAGE_COMMAND_FLUSH || message == MFT_MESSAGE_NOTIFY_END_STREAMING) {
            if (pending_) { pending_->Release(); pending_ = nullptr; }
        }
        if (message == MFT_MESSAGE_COMMAND_FLUSH) {
            EnterCriticalSection(&lock_);
            read_ = write_ = 0;
            LeaveCriticalSection(&lock_);
        }
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE ProcessInput(DWORD id, IMFSample *sample, DWORD flags) override {
        if (id != 0) return MF_E_INVALIDSTREAMNUMBER;
        if (!sample || flags != 0) return E_INVALIDARG;
        if (!input_type_ || !output_type_) return MF_E_TRANSFORM_TYPE_NOT_SET;
        if (pending_) return MF_E_NOTACCEPTING;
        capture(sample);
        sample->AddRef();
        pending_ = sample;
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE ProcessOutput(DWORD flags, DWORD count,
        MFT_OUTPUT_DATA_BUFFER *output, DWORD *status) override {
        if (flags != 0 || count != 1 || !output || !status) return E_INVALIDARG;
        *status = 0;
        if (!pending_) return MF_E_TRANSFORM_NEED_MORE_INPUT;
        output[0].pSample = pending_;
        output[0].dwStatus = 0;
        output[0].pEvents = nullptr;
        pending_ = nullptr;
        return S_OK;
    }

private:
    static constexpr unsigned int kCapacity = 65536;
    volatile LONG refs_ = 1;
    IMFMediaType *input_type_ = nullptr;
    IMFMediaType *output_type_ = nullptr;
    IMFSample *pending_ = nullptr;
    CRITICAL_SECTION lock_;
    bool enabled_ = false;
    float samples_[kCapacity] = {};
    unsigned int read_ = 0;
    unsigned int write_ = 0;
    UINT32 sample_rate_ = 0;
    UINT32 channels_ = 0;
    UINT32 bits_ = 0;
    bool floating_ = false;

    static void replace(IMFMediaType *&slot, IMFMediaType *value) {
        if (value) value->AddRef();
        if (slot) slot->Release();
        slot = value;
    }
    static HRESULT clone_type(IMFMediaType *original, IMFMediaType **clone) {
        HRESULT hr = MFCreateMediaType(clone);
        if (FAILED(hr)) return hr;
        hr = original->CopyAllItems(*clone);
        if (FAILED(hr)) { (*clone)->Release(); *clone = nullptr; }
        return hr;
    }
    static bool is_supported(IMFMediaType *type) {
        GUID major = {}, subtype = {};
        if (FAILED(type->GetGUID(MF_MT_MAJOR_TYPE, &major)) || !IsEqualGUID(major, MFMediaType_Audio) ||
            FAILED(type->GetGUID(MF_MT_SUBTYPE, &subtype))) return false;
        return IsEqualGUID(subtype, MFAudioFormat_PCM) || IsEqualGUID(subtype, MFAudioFormat_Float);
    }
    static bool equivalent(IMFMediaType *a, IMFMediaType *b) {
        GUID as = {}, bs = {};
        if (FAILED(a->GetGUID(MF_MT_SUBTYPE, &as)) ||
            FAILED(b->GetGUID(MF_MT_SUBTYPE, &bs)) || !IsEqualGUID(as, bs)) return false;
        const GUID keys[] = { MF_MT_AUDIO_NUM_CHANNELS, MF_MT_AUDIO_SAMPLES_PER_SECOND,
            MF_MT_AUDIO_BITS_PER_SAMPLE };
        for (const GUID &key : keys) {
            UINT32 av = 0, bv = 0;
            if (SUCCEEDED(a->GetUINT32(key, &av)) && SUCCEEDED(b->GetUINT32(key, &bv)) && av != bv)
                return false;
        }
        return true;
    }
    static HRESULT current_type(DWORD id, IMFMediaType *stored, IMFMediaType **type) {
        if (id != 0) return MF_E_INVALIDSTREAMNUMBER;
        if (!type) return E_POINTER;
        *type = nullptr;
        if (!stored) return MF_E_TRANSFORM_TYPE_NOT_SET;
        stored->AddRef();
        *type = stored;
        return S_OK;
    }
    void update_format(IMFMediaType *type) {
        UINT32 channels = 0, rate = 0, bits = 0;
        GUID subtype = {};
        type->GetUINT32(MF_MT_AUDIO_NUM_CHANNELS, &channels);
        type->GetUINT32(MF_MT_AUDIO_SAMPLES_PER_SECOND, &rate);
        type->GetUINT32(MF_MT_AUDIO_BITS_PER_SAMPLE, &bits);
        type->GetGUID(MF_MT_SUBTYPE, &subtype);
        EnterCriticalSection(&lock_);
        channels_ = channels;
        sample_rate_ = rate;
        bits_ = bits;
        floating_ = IsEqualGUID(subtype, MFAudioFormat_Float);
        read_ = write_ = 0;
        LeaveCriticalSection(&lock_);
    }
    void capture(IMFSample *sample) {
        EnterCriticalSection(&lock_);
        const bool active = enabled_ && channels_ > 0 && channels_ <= 8 && sample_rate_ > 0 &&
            ((floating_ && bits_ == 32) || (!floating_ && bits_ == 16));
        LeaveCriticalSection(&lock_);
        if (!active) return;
        IMFMediaBuffer *buffer = nullptr;
        if (FAILED(sample->ConvertToContiguousBuffer(&buffer)) || !buffer) return;
        BYTE *bytes = nullptr;
        DWORD length = 0;
        if (SUCCEEDED(buffer->Lock(&bytes, nullptr, &length))) {
            EnterCriticalSection(&lock_);
            if (enabled_) {
                const DWORD sample_bytes = bits_ / 8;
                const DWORD frames = length / (sample_bytes * channels_);
                for (DWORD i = 0; i < frames; ++i) {
                    float mono = 0.0f;
                    for (DWORD channel = 0; channel < channels_; ++channel) {
                        const BYTE *p = bytes + (i * channels_ + channel) * sample_bytes;
                        if (floating_) {
                            float value = 0.0f;
                            std::memcpy(&value, p, sizeof(value));
                            mono += std::isfinite(value) ? value : 0.0f;
                        } else {
                            int16_t value = 0;
                            std::memcpy(&value, p, sizeof(value));
                            mono += static_cast<float>(value) / 32768.0f;
                        }
                    }
                    samples_[write_ % kCapacity] = std::max(-1.0f,
                        std::min(1.0f, mono / static_cast<float>(channels_)));
                    ++write_;
                    if (write_ - read_ > kCapacity) read_ = write_ - kCapacity;
                }
            }
            LeaveCriticalSection(&lock_);
            buffer->Unlock();
        }
        buffer->Release();
    }
};
