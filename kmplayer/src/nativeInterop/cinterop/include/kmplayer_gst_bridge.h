#ifndef KMPLAYER_GST_BRIDGE_H
#define KMPLAYER_GST_BRIDGE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct kmplayer_gst_player kmplayer_gst_player;

enum kmplayer_gst_event {
    KMPLAYER_GST_EVENT_NONE = 0,
    KMPLAYER_GST_EVENT_READY = 1,
    KMPLAYER_GST_EVENT_ENDED = 2,
    KMPLAYER_GST_EVENT_ERROR = 3,
    KMPLAYER_GST_EVENT_BUFFERING = 4,
    KMPLAYER_GST_EVENT_TITLE = 5
};

int kmplayer_gst_init(char *error_message, size_t error_message_size);
int kmplayer_gst_has_hls(void);

kmplayer_gst_player *kmplayer_gst_player_create(int audio_only, char *error_message, size_t error_message_size);
void kmplayer_gst_player_destroy(kmplayer_gst_player *player);

int kmplayer_gst_player_load(kmplayer_gst_player *player, const char *uri, int play_when_ready);
int kmplayer_gst_player_play(kmplayer_gst_player *player);
int kmplayer_gst_player_pause(kmplayer_gst_player *player);
int kmplayer_gst_player_stop(kmplayer_gst_player *player);
int kmplayer_gst_player_seek_ms(kmplayer_gst_player *player, int64_t position_ms);
int kmplayer_gst_player_set_volume(kmplayer_gst_player *player, double volume_0_to_1);
int kmplayer_gst_player_set_muted(kmplayer_gst_player *player, int muted);
int kmplayer_gst_player_set_speed(kmplayer_gst_player *player, double speed);
int kmplayer_gst_player_set_video_enabled(kmplayer_gst_player *player, int enabled);

int64_t kmplayer_gst_player_position_ms(kmplayer_gst_player *player);
int64_t kmplayer_gst_player_duration_ms(kmplayer_gst_player *player);
int kmplayer_gst_player_is_seekable(kmplayer_gst_player *player);
uintptr_t kmplayer_gst_player_pipeline(kmplayer_gst_player *player);
int kmplayer_gst_player_render_bgra(
    kmplayer_gst_player *player,
    void *pixels,
    int width,
    int height,
    int stride,
    int scale_mode
);

int kmplayer_gst_player_poll_event(
    kmplayer_gst_player *player,
    char *message,
    size_t message_size,
    int *value
);

#ifdef __cplusplus
}
#endif

#endif
