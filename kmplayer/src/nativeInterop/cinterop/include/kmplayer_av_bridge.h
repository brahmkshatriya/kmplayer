#ifndef KMPLAYER_AV_BRIDGE_H
#define KMPLAYER_AV_BRIDGE_H
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
typedef void kmplayer_av_player;
int kmplayer_av_player_bridge_version(void);
kmplayer_av_player *kmplayer_av_player_create(int audio_only, char *error, size_t size);
void kmplayer_av_player_destroy(kmplayer_av_player *player);
int kmplayer_av_player_load(kmplayer_av_player *player, const char *uri, int play_when_ready);
int kmplayer_av_player_play(kmplayer_av_player *player);
int kmplayer_av_player_pause(kmplayer_av_player *player);
int kmplayer_av_player_stop(kmplayer_av_player *player);
int kmplayer_av_player_seek_ms(kmplayer_av_player *player, int64_t position);
int kmplayer_av_player_set_volume(kmplayer_av_player *player, double volume);
int kmplayer_av_player_set_muted(kmplayer_av_player *player, int muted);
int kmplayer_av_player_set_speed(kmplayer_av_player *player, double speed);
int kmplayer_av_player_set_video_enabled(kmplayer_av_player *player, int enabled);
int kmplayer_av_player_set_audio_analysis(kmplayer_av_player *player, int enabled);
int kmplayer_av_player_copy_audio_samples(kmplayer_av_player *player, float *samples, int capacity, int *rate);
int64_t kmplayer_av_player_position_ms(kmplayer_av_player *player);
int64_t kmplayer_av_player_duration_ms(kmplayer_av_player *player);
int64_t kmplayer_av_player_buffered_end_ms(kmplayer_av_player *player);
int kmplayer_av_player_has_hls(kmplayer_av_player *player);
int kmplayer_av_player_is_seekable(kmplayer_av_player *player);
int kmplayer_av_player_is_live(kmplayer_av_player *player);
int kmplayer_av_player_video_width(kmplayer_av_player *player);
int kmplayer_av_player_video_height(kmplayer_av_player *player);
uintptr_t kmplayer_av_player_native_handle(kmplayer_av_player *player);
int kmplayer_av_player_copy_video_frame(kmplayer_av_player *player, void *pixels, int width, int height, int stride);
int kmplayer_av_player_poll_event(kmplayer_av_player *player, char *message, size_t size);
#ifdef __cplusplus
}
#endif
#endif
