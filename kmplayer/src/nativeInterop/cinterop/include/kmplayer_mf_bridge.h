#ifndef KMPLAYER_MF_BRIDGE_H
#define KMPLAYER_MF_BRIDGE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct kmplayer_mf_player kmplayer_mf_player;

enum kmplayer_mf_event {
    KMPLAYER_MF_EVENT_NONE = 0,
    KMPLAYER_MF_EVENT_READY = 1,
    KMPLAYER_MF_EVENT_ENDED = 2,
    KMPLAYER_MF_EVENT_ERROR = 3,
    KMPLAYER_MF_EVENT_BUFFERING_STARTED = 4,
    KMPLAYER_MF_EVENT_BUFFERING_ENDED = 5
};

kmplayer_mf_player *kmplayer_mf_player_create(int audio_only, char *error_message, size_t error_message_size);
void kmplayer_mf_player_destroy(kmplayer_mf_player *player);

int kmplayer_mf_player_load(kmplayer_mf_player *player, const char *uri_utf8, int play_when_ready);
int kmplayer_mf_player_play(kmplayer_mf_player *player);
int kmplayer_mf_player_pause(kmplayer_mf_player *player);
int kmplayer_mf_player_stop(kmplayer_mf_player *player);
int kmplayer_mf_player_seek_ms(kmplayer_mf_player *player, int64_t position_ms);
int kmplayer_mf_player_set_volume(kmplayer_mf_player *player, double volume_0_to_1);
int kmplayer_mf_player_set_muted(kmplayer_mf_player *player, int muted);
int kmplayer_mf_player_set_speed(kmplayer_mf_player *player, double speed);

int64_t kmplayer_mf_player_position_ms(kmplayer_mf_player *player);
int64_t kmplayer_mf_player_duration_ms(kmplayer_mf_player *player);
int kmplayer_mf_player_has_hls(kmplayer_mf_player *player);
int kmplayer_mf_player_is_seekable(kmplayer_mf_player *player);
int kmplayer_mf_player_is_live(kmplayer_mf_player *player);
int kmplayer_mf_player_video_width(kmplayer_mf_player *player);
int kmplayer_mf_player_video_height(kmplayer_mf_player *player);
uintptr_t kmplayer_mf_player_engine(kmplayer_mf_player *player);
int kmplayer_mf_player_render_bgra(
    kmplayer_mf_player *player,
    void *pixels,
    int width,
    int height,
    int stride,
    int scale_mode
);

int kmplayer_mf_player_poll_event(
    kmplayer_mf_player *player,
    char *message,
    size_t message_size
);

#ifdef __cplusplus
}
#endif

#endif
