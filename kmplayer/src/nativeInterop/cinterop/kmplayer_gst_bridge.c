#include "kmplayer_gst_bridge.h"

#include <gst/app/gstappsink.h>
#include <gst/gst.h>
#include <gst/video/video.h>
#include <math.h>
#include <string.h>
#include <stdlib.h>

struct kmplayer_gst_player {
    GstElement *playbin;
    GstBus *bus;
    GstAppSink *video_sink;
    GstSample *last_sample;
    double speed;
};

static void kmplayer_copy_message(char *destination, size_t size, const char *source) {
    if (!destination || size == 0) return;
    if (!source) source = "";
    g_strlcpy(destination, source, size);
}

static int kmplayer_has_element(const char *name) {
    GstElementFactory *factory = gst_element_factory_find(name);
    if (!factory) return 0;
    gst_object_unref(factory);
    return 1;
}

int kmplayer_gst_init(char *error_message, size_t error_message_size) {
    GError *error = NULL;
    if (gst_is_initialized()) return 1;
    if (!gst_init_check(NULL, NULL, &error)) {
        kmplayer_copy_message(error_message, error_message_size, error ? error->message : "gst_init_check failed");
        if (error) g_error_free(error);
        return 0;
    }
    return 1;
}

int kmplayer_gst_has_hls(void) {
    const int demux = kmplayer_has_element("hlsdemux2") || kmplayer_has_element("hlsdemux");
    const int http = kmplayer_has_element("souphttpsrc") || kmplayer_has_element("curlhttpsrc");
    return demux && http && kmplayer_has_element("playbin");
}

kmplayer_gst_player *kmplayer_gst_player_create(int audio_only, char *error_message, size_t error_message_size) {
    if (!kmplayer_gst_init(error_message, error_message_size)) return NULL;

    kmplayer_gst_player *player = (kmplayer_gst_player *)calloc(1, sizeof(kmplayer_gst_player));
    if (!player) {
        kmplayer_copy_message(error_message, error_message_size, "out of memory");
        return NULL;
    }

    player->playbin = gst_element_factory_make("playbin", NULL);
    if (!player->playbin) {
        kmplayer_copy_message(error_message, error_message_size, "GStreamer playbin is unavailable");
        free(player);
        return NULL;
    }
    player->bus = gst_element_get_bus(player->playbin);

    if (audio_only) {
        guint flags = 0;
        g_object_get(G_OBJECT(player->playbin), "flags", &flags, NULL);
        flags &= ~1u; /* GstPlayFlags::VIDEO */
        g_object_set(G_OBJECT(player->playbin), "flags", flags, NULL);
        player->speed = 1.0;
        return player;
    }

    GstElement *sink = gst_element_factory_make("appsink", NULL);
    if (!sink) {
        kmplayer_copy_message(error_message, error_message_size, "GStreamer appsink is unavailable");
        kmplayer_gst_player_destroy(player);
        return NULL;
    }
    GstCaps *caps = gst_caps_from_string("video/x-raw,format=BGRA");
    g_object_set(
        G_OBJECT(sink),
        "caps", caps,
        "emit-signals", FALSE,
        "sync", TRUE,
        "max-buffers", 2,
        "drop", TRUE,
        NULL
    );
    gst_caps_unref(caps);
    g_object_set(G_OBJECT(player->playbin), "video-sink", sink, NULL);
    player->video_sink = GST_APP_SINK(sink);
    player->speed = 1.0;
    return player;
}

void kmplayer_gst_player_destroy(kmplayer_gst_player *player) {
    if (!player) return;
    gst_element_set_state(player->playbin, GST_STATE_NULL);
    if (player->last_sample) gst_sample_unref(player->last_sample);
    if (player->bus) gst_object_unref(player->bus);
    if (player->playbin) gst_object_unref(player->playbin);
    free(player);
}

int kmplayer_gst_player_load(kmplayer_gst_player *player, const char *uri, int play_when_ready) {
    if (!player || !uri) return 0;
    gst_element_set_state(player->playbin, GST_STATE_NULL);
    if (player->last_sample) {
        gst_sample_unref(player->last_sample);
        player->last_sample = NULL;
    }
    g_object_set(G_OBJECT(player->playbin), "uri", uri, NULL);
    player->speed = 1.0;
    GstStateChangeReturn result = gst_element_set_state(
        player->playbin,
        play_when_ready ? GST_STATE_PLAYING : GST_STATE_PAUSED
    );
    return result != GST_STATE_CHANGE_FAILURE;
}

int kmplayer_gst_player_play(kmplayer_gst_player *player) {
    if (!player) return 0;
    return gst_element_set_state(player->playbin, GST_STATE_PLAYING) != GST_STATE_CHANGE_FAILURE;
}

int kmplayer_gst_player_pause(kmplayer_gst_player *player) {
    if (!player) return 0;
    return gst_element_set_state(player->playbin, GST_STATE_PAUSED) != GST_STATE_CHANGE_FAILURE;
}

int kmplayer_gst_player_stop(kmplayer_gst_player *player) {
    if (!player) return 0;
    return gst_element_set_state(player->playbin, GST_STATE_READY) != GST_STATE_CHANGE_FAILURE;
}

int kmplayer_gst_player_seek_ms(kmplayer_gst_player *player, int64_t position_ms) {
    if (!player) return 0;
    if (position_ms < 0) position_ms = 0;
    return gst_element_seek_simple(
        player->playbin,
        GST_FORMAT_TIME,
        GST_SEEK_FLAG_FLUSH | GST_SEEK_FLAG_KEY_UNIT,
        (gint64)position_ms * GST_MSECOND
    );
}

int kmplayer_gst_player_set_volume(kmplayer_gst_player *player, double volume_0_to_1) {
    if (!player) return 0;
    g_object_set(G_OBJECT(player->playbin), "volume", volume_0_to_1, NULL);
    return 1;
}

int kmplayer_gst_player_set_muted(kmplayer_gst_player *player, int muted) {
    if (!player) return 0;
    g_object_set(G_OBJECT(player->playbin), "mute", muted ? TRUE : FALSE, NULL);
    return 1;
}

int kmplayer_gst_player_set_speed(kmplayer_gst_player *player, double speed) {
    if (!player || speed <= 0.0) return 0;

    gint64 position = GST_CLOCK_TIME_NONE;
    if (!gst_element_query_position(player->playbin, GST_FORMAT_TIME, &position)) return 0;

    GstEvent *event = gst_event_new_seek(
        speed,
        GST_FORMAT_TIME,
        GST_SEEK_FLAG_FLUSH | GST_SEEK_FLAG_ACCURATE,
        GST_SEEK_TYPE_SET,
        position,
        GST_SEEK_TYPE_NONE,
        GST_CLOCK_TIME_NONE
    );
    if (!gst_element_send_event(player->playbin, event)) return 0;
    player->speed = speed;
    return 1;
}

int kmplayer_gst_player_set_video_enabled(kmplayer_gst_player *player, int enabled) {
    if (!player) return 0;
    guint flags = 0;
    g_object_get(G_OBJECT(player->playbin), "flags", &flags, NULL);

    if (enabled) {
        if (!player->video_sink) {
            GstElement *sink = gst_element_factory_make("appsink", NULL);
            if (!sink) return 0;
            GstCaps *caps = gst_caps_from_string("video/x-raw,format=BGRA");
            g_object_set(
                G_OBJECT(sink),
                "caps", caps,
                "emit-signals", FALSE,
                "sync", TRUE,
                "max-buffers", 2,
                "drop", TRUE,
                NULL
            );
            gst_caps_unref(caps);
            g_object_set(G_OBJECT(player->playbin), "video-sink", sink, NULL);
            player->video_sink = GST_APP_SINK(sink);
        }
        flags |= 1u;
        g_object_set(G_OBJECT(player->playbin), "flags", flags, NULL);
    } else {
        flags &= ~1u;
        g_object_set(G_OBJECT(player->playbin), "flags", flags, NULL);
        if (player->last_sample) {
            gst_sample_unref(player->last_sample);
            player->last_sample = NULL;
        }
        if (player->video_sink) {
            g_object_set(G_OBJECT(player->playbin), "video-sink", NULL, NULL);
            player->video_sink = NULL;
        }
    }
    return 1;
}

int64_t kmplayer_gst_player_position_ms(kmplayer_gst_player *player) {
    if (!player) return -1;
    gint64 position = GST_CLOCK_TIME_NONE;
    if (!gst_element_query_position(player->playbin, GST_FORMAT_TIME, &position) || position < 0) return -1;
    return (int64_t)(position / GST_MSECOND);
}

int64_t kmplayer_gst_player_duration_ms(kmplayer_gst_player *player) {
    if (!player) return -1;
    gint64 duration = GST_CLOCK_TIME_NONE;
    if (!gst_element_query_duration(player->playbin, GST_FORMAT_TIME, &duration) || duration < 0) return -1;
    return (int64_t)(duration / GST_MSECOND);
}

int kmplayer_gst_player_is_seekable(kmplayer_gst_player *player) {
    if (!player) return 0;
    GstQuery *query = gst_query_new_seeking(GST_FORMAT_TIME);
    if (!gst_element_query(player->playbin, query)) {
        gst_query_unref(query);
        return 0;
    }
    gboolean seekable = FALSE;
    gst_query_parse_seeking(query, NULL, &seekable, NULL, NULL);
    gst_query_unref(query);
    return seekable ? 1 : 0;
}

uintptr_t kmplayer_gst_player_pipeline(kmplayer_gst_player *player) {
    return player ? (uintptr_t)player->playbin : (uintptr_t)0;
}

static void kmplayer_clear_bgra(unsigned char *pixels, int width, int height, int stride) {
    if (!pixels || width <= 0 || height <= 0 || stride < width * 4) return;
    for (int y = 0; y < height; ++y) {
        unsigned char *row = pixels + (size_t)y * (size_t)stride;
        for (int x = 0; x < width; ++x) {
            row[(size_t)x * 4u + 0u] = 0;
            row[(size_t)x * 4u + 1u] = 0;
            row[(size_t)x * 4u + 2u] = 0;
            row[(size_t)x * 4u + 3u] = 255;
        }
    }
}

int kmplayer_gst_player_render_bgra(
    kmplayer_gst_player *player,
    void *pixels_void,
    int width,
    int height,
    int stride,
    int scale_mode
) {
    if (!player || !player->video_sink || !pixels_void || width <= 0 || height <= 0 || stride < width * 4) {
        return 0;
    }

    unsigned char *destination = (unsigned char *)pixels_void;
    kmplayer_clear_bgra(destination, width, height, stride);

    GstSample *sample = NULL;
    for (;;) {
        GstSample *next = gst_app_sink_try_pull_sample(player->video_sink, 0);
        if (!next) break;
        if (sample) gst_sample_unref(sample);
        sample = next;
    }
    if (sample) {
        if (player->last_sample) gst_sample_unref(player->last_sample);
        player->last_sample = sample;
    }
    if (!player->last_sample) return 1;

    GstCaps *caps = gst_sample_get_caps(player->last_sample);
    GstBuffer *buffer = gst_sample_get_buffer(player->last_sample);
    if (!caps || !buffer) return 0;

    GstVideoInfo info;
    if (!gst_video_info_from_caps(&info, caps)) return 0;
    GstVideoFrame frame;
    if (!gst_video_frame_map(&frame, &info, buffer, GST_MAP_READ)) return 0;

    const int src_width = GST_VIDEO_FRAME_WIDTH(&frame);
    const int src_height = GST_VIDEO_FRAME_HEIGHT(&frame);
    const int src_stride = GST_VIDEO_FRAME_PLANE_STRIDE(&frame, 0);
    const unsigned char *source = GST_VIDEO_FRAME_PLANE_DATA(&frame, 0);
    if (src_width <= 0 || src_height <= 0 || !source) {
        gst_video_frame_unmap(&frame);
        return 0;
    }

    if (scale_mode == 2) {
        for (int y = 0; y < height; ++y) {
            const int sy = (int)((long long)y * src_height / height);
            unsigned char *dst_row = destination + (size_t)y * (size_t)stride;
            const unsigned char *src_row = source + (size_t)sy * (size_t)src_stride;
            for (int x = 0; x < width; ++x) {
                const int sx = (int)((long long)x * src_width / width);
                memcpy(dst_row + (size_t)x * 4u, src_row + (size_t)sx * 4u, 4u);
            }
        }
    } else {
        const double sx_scale = (double)width / (double)src_width;
        const double sy_scale = (double)height / (double)src_height;
        const double scale = scale_mode == 1 ? fmax(sx_scale, sy_scale) : fmin(sx_scale, sy_scale);
        const double scaled_width = (double)src_width * scale;
        const double scaled_height = (double)src_height * scale;
        const double offset_x = ((double)width - scaled_width) * 0.5;
        const double offset_y = ((double)height - scaled_height) * 0.5;

        for (int y = 0; y < height; ++y) {
            const double source_y = ((double)y - offset_y) / scale;
            if (source_y < 0.0 || source_y >= src_height) continue;
            const int sy = (int)source_y;
            unsigned char *dst_row = destination + (size_t)y * (size_t)stride;
            const unsigned char *src_row = source + (size_t)sy * (size_t)src_stride;
            for (int x = 0; x < width; ++x) {
                const double source_x = ((double)x - offset_x) / scale;
                if (source_x < 0.0 || source_x >= src_width) continue;
                const int sx = (int)source_x;
                memcpy(dst_row + (size_t)x * 4u, src_row + (size_t)sx * 4u, 4u);
            }
        }
    }

    gst_video_frame_unmap(&frame);
    return 1;
}

int kmplayer_gst_player_poll_event(
    kmplayer_gst_player *player,
    char *message,
    size_t message_size,
    int *value
) {
    if (!player || !player->bus) return KMPLAYER_GST_EVENT_NONE;
    if (value) *value = 0;

    GstMessage *event = gst_bus_pop_filtered(
        player->bus,
        GST_MESSAGE_ERROR |
        GST_MESSAGE_EOS |
        GST_MESSAGE_ASYNC_DONE |
        GST_MESSAGE_BUFFERING |
        GST_MESSAGE_TAG
    );
    if (!event) return KMPLAYER_GST_EVENT_NONE;

    int result = KMPLAYER_GST_EVENT_NONE;
    switch (GST_MESSAGE_TYPE(event)) {
        case GST_MESSAGE_ERROR: {
            GError *error = NULL;
            gchar *debug = NULL;
            gst_message_parse_error(event, &error, &debug);
            kmplayer_copy_message(message, message_size, error ? error->message : "GStreamer playback error");
            if (error) g_error_free(error);
            g_free(debug);
            result = KMPLAYER_GST_EVENT_ERROR;
            break;
        }
        case GST_MESSAGE_EOS:
            result = KMPLAYER_GST_EVENT_ENDED;
            break;
        case GST_MESSAGE_ASYNC_DONE:
            result = KMPLAYER_GST_EVENT_READY;
            break;
        case GST_MESSAGE_BUFFERING: {
            gint percent = 0;
            gst_message_parse_buffering(event, &percent);
            if (value) *value = percent;
            result = KMPLAYER_GST_EVENT_BUFFERING;
            break;
        }
        case GST_MESSAGE_TAG: {
            GstTagList *tags = NULL;
            gst_message_parse_tag(event, &tags);
            gchar *title = NULL;
            if (tags && gst_tag_list_get_string(tags, GST_TAG_TITLE, &title)) {
                kmplayer_copy_message(message, message_size, title);
                result = KMPLAYER_GST_EVENT_TITLE;
            }
            g_free(title);
            if (tags) gst_tag_list_unref(tags);
            break;
        }
        default:
            break;
    }

    gst_message_unref(event);
    return result;
}
