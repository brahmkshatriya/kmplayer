#ifndef KMPLAYER_MF_BRIDGE_H
#define KMPLAYER_MF_BRIDGE_H

#include <stddef.h>
#include <stdint.h>

#if defined(_WIN32) && defined(KMPLAYER_MF_SHARED)
#define KMPLAYER_MF_API __declspec(dllexport)
#else
#define KMPLAYER_MF_API
#endif

#ifdef __cplusplus
extern "C" {
#endif

typedef struct kmplayer_mf_player kmplayer_mf_player;
KMPLAYER_MF_API int kmplayer_mf_player_bridge_version(void);

enum kmplayer_mf_event {
    KMPLAYER_MF_EVENT_NONE = 0,
    KMPLAYER_MF_EVENT_READY = 1,
    KMPLAYER_MF_EVENT_ENDED = 2,
    KMPLAYER_MF_EVENT_ERROR = 3,
    KMPLAYER_MF_EVENT_BUFFERING_STARTED = 4,
    KMPLAYER_MF_EVENT_BUFFERING_ENDED = 5
};

KMPLAYER_MF_API kmplayer_mf_player *kmplayer_mf_player_create(int audio_only, char *error_message, size_t error_message_size);
KMPLAYER_MF_API void kmplayer_mf_player_destroy(kmplayer_mf_player *player);

KMPLAYER_MF_API int kmplayer_mf_player_load(kmplayer_mf_player *player, const char *uri_utf8, int play_when_ready);
KMPLAYER_MF_API int kmplayer_mf_player_play(kmplayer_mf_player *player);
KMPLAYER_MF_API int kmplayer_mf_player_pause(kmplayer_mf_player *player);
KMPLAYER_MF_API int kmplayer_mf_player_stop(kmplayer_mf_player *player);
KMPLAYER_MF_API int kmplayer_mf_player_seek_ms(kmplayer_mf_player *player, int64_t position_ms);
KMPLAYER_MF_API int kmplayer_mf_player_set_volume(kmplayer_mf_player *player, double volume_0_to_1);
KMPLAYER_MF_API int kmplayer_mf_player_set_muted(kmplayer_mf_player *player, int muted);
KMPLAYER_MF_API int kmplayer_mf_player_set_speed(kmplayer_mf_player *player, double speed);
KMPLAYER_MF_API int kmplayer_mf_player_set_audio_analysis(kmplayer_mf_player *player, int enabled);
KMPLAYER_MF_API int kmplayer_mf_player_copy_audio_samples(kmplayer_mf_player *player,
    float *samples, int capacity, int *sample_rate_hz);

KMPLAYER_MF_API int64_t kmplayer_mf_player_position_ms(kmplayer_mf_player *player);
KMPLAYER_MF_API int64_t kmplayer_mf_player_duration_ms(kmplayer_mf_player *player);
KMPLAYER_MF_API int64_t kmplayer_mf_player_buffered_end_ms(kmplayer_mf_player *player);
KMPLAYER_MF_API int kmplayer_mf_player_has_hls(kmplayer_mf_player *player);
KMPLAYER_MF_API int kmplayer_mf_player_is_seekable(kmplayer_mf_player *player);
KMPLAYER_MF_API int kmplayer_mf_player_is_live(kmplayer_mf_player *player);
KMPLAYER_MF_API int kmplayer_mf_player_video_width(kmplayer_mf_player *player);
KMPLAYER_MF_API int kmplayer_mf_player_video_height(kmplayer_mf_player *player);
KMPLAYER_MF_API uintptr_t kmplayer_mf_player_engine(kmplayer_mf_player *player);
/* Returns a new decoded frame only; retains the last displayed JVM frame while paused. */
KMPLAYER_MF_API int kmplayer_mf_player_copy_video_frame(kmplayer_mf_player *player, void *pixels,
    int width, int height, int stride);
KMPLAYER_MF_API int kmplayer_mf_player_render_bgra(
    kmplayer_mf_player *player,
    void *pixels,
    int width,
    int height,
    int stride,
    int scale_mode
);

KMPLAYER_MF_API int kmplayer_mf_player_poll_event(
    kmplayer_mf_player *player,
    char *message,
    size_t message_size
);

#ifdef __cplusplus
}
#endif

#endif
