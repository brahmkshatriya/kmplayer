#import "kmplayer_av_bridge.h"
#import <AVFoundation/AVFoundation.h>
#import <CoreImage/CoreImage.h>
#import <CoreVideo/CoreVideo.h>
#import <Foundation/Foundation.h>
#import <dispatch/dispatch.h>
#include <math.h>
#include <float.h>
#include <limits.h>
#include <stdio.h>
#include <string.h>

// Event values deliberately match the Media Foundation bridge ABI.
enum { KM_AV_READY = 1, KM_AV_ENDED = 2, KM_AV_ERROR = 3,
    KM_AV_BUFFERING_STARTED = 4, KM_AV_BUFFERING_ENDED = 5 };
static char KMItemStatusContext, KMItemTracksContext, KMTimeControlContext;

@interface KMAVPlayer : NSObject
@property(nonatomic, strong) dispatch_queue_t queue;
@property(nonatomic, strong) AVPlayer *player;
@property(nonatomic, strong) AVPlayerItem *item;
@property(nonatomic, strong) AVPlayerItemVideoOutput *output;
@property(nonatomic, strong) CIContext *imageContext;
@property(nonatomic, strong) NSMutableArray<NSDictionary *> *events;
@property(nonatomic, strong) NSMutableArray *notifications;
@property(nonatomic) BOOL audioOnly;
@property(nonatomic) BOOL closed;
@property(nonatomic) BOOL playRequested;
@property(nonatomic) BOOL ready;
@property(nonatomic) double speed;
@property(nonatomic) int64_t pendingSeek;
@property(nonatomic) CGAffineTransform videoTransform;
@property(nonatomic) CVPixelBufferRef renderBuffer;
- (void)removeItem;
- (void)applyVideoPolicy;
- (void)push:(int)event message:(NSString *)message;
- (void)seek:(int64_t)position;
@end

@implementation KMAVPlayer
- (instancetype)init {
    if ((self = [super init])) {
        _queue = dispatch_queue_create("dev.brahmkshatriya.kmplayer.avplayer", DISPATCH_QUEUE_SERIAL);
        _events = [NSMutableArray array];
        _notifications = [NSMutableArray array];
        _speed = 1.0;
        _pendingSeek = -1;
        _videoTransform = CGAffineTransformIdentity;
        _player = [AVPlayer new];
        [_player addObserver:self forKeyPath:@"timeControlStatus" options:NSKeyValueObservingOptionNew context:&KMTimeControlContext];
    }
    return self;
}
- (void)push:(int)event message:(NSString *)message {
    if (_closed) return;
    // Native engines may post the same readiness/error more than once. Bound the
    // queue, retaining only the first error, and never discard terminal events.
    if (event == KM_AV_READY && _ready) return;
    if (event == KM_AV_READY) _ready = YES;
    if (event == KM_AV_ERROR) for (NSDictionary *entry in _events) if ([entry[@"code"] intValue] == KM_AV_ERROR) return;
    if (_events.count >= 64 && event >= KM_AV_BUFFERING_STARTED) return;
    [_events addObject:@{@"code": @(event), @"message": message ?: @""}];
}
- (void)applyVideoPolicy {
    for (AVPlayerItemTrack *track in _item.tracks) {
        if ([track.assetTrack.mediaType isEqualToString:AVMediaTypeVideo]) track.enabled = !_audioOnly;
    }
}
- (void)removeItem {
    for (id observer in _notifications) [[NSNotificationCenter defaultCenter] removeObserver:observer];
    [_notifications removeAllObjects];
    if (_item) {
        [_item removeObserver:self forKeyPath:@"status" context:&KMItemStatusContext];
        [_item removeObserver:self forKeyPath:@"tracks" context:&KMItemTracksContext];
        if (_output) [_item removeOutput:_output];
    }
    _output = nil;
    [_player replaceCurrentItemWithPlayerItem:nil];
    _item = nil;
    _ready = NO;
    _pendingSeek = -1;
    if (_renderBuffer) { CVPixelBufferRelease(_renderBuffer); _renderBuffer = NULL; }
}
- (void)observeValueForKeyPath:(NSString *)keyPath ofObject:(id)object change:(NSDictionary *)change context:(void *)context {
    if (context != &KMItemStatusContext && context != &KMItemTracksContext && context != &KMTimeControlContext) {
        [super observeValueForKeyPath:keyPath ofObject:object change:change context:context]; return;
    }
    __weak KMAVPlayer *weakSelf = self;
    dispatch_async(_queue, ^{
        KMAVPlayer *self = weakSelf;
        if (!self || self.closed) return;
        if (context == &KMTimeControlContext) {
            if (self.item && self.ready) [self push:self.player.timeControlStatus == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
                ? KM_AV_BUFFERING_STARTED : KM_AV_BUFFERING_ENDED message:nil];
            return;
        }
        if (object != self.item) return;
        [self applyVideoPolicy];
        if (self.item.status == AVPlayerItemStatusFailed) {
            [self push:KM_AV_ERROR message:self.item.error.localizedDescription ?: @"AVPlayer could not open this source"];
        } else if (self.item.status == AVPlayerItemStatusReadyToPlay && !self.ready) {
            AVAssetTrack *video = [[self.item.asset tracksWithMediaType:AVMediaTypeVideo] firstObject];
            self.videoTransform = video ? video.preferredTransform : CGAffineTransformIdentity;
            [self push:KM_AV_READY message:nil];
            if (self.playRequested) [self.player playImmediatelyAtRate:(float)self.speed];
        }
    });
}
- (void)seek:(int64_t)position {
    if (position < 0) position = 0;
    self.pendingSeek = position;
    AVPlayerItem *item = _item;
    __weak KMAVPlayer *weakSelf = self;
    [_player seekToTime:CMTimeMake(position, 1000) toleranceBefore:kCMTimeZero toleranceAfter:kCMTimeZero completionHandler:^(BOOL finished) {
        KMAVPlayer *owner = weakSelf;
        if (!owner) return;
        dispatch_async(owner.queue, ^{
            KMAVPlayer *self = weakSelf;
            if (!self || self.closed || self.item != item) return;
            self.pendingSeek = -1;
            if (finished && self.playRequested) [self.player playImmediatelyAtRate:(float)self.speed];
        });
    }];
}
@end

// The JVM owns this retained object and serializes API entry. AVFoundation state
// and asynchronous notifications are additionally confined to its native queue.
static KMAVPlayer *km_av(kmplayer_av_player *handle) { return (__bridge KMAVPlayer *)handle; }
static int64_t km_time_ms(CMTime time) {
    double seconds = CMTimeGetSeconds(time);
    return isfinite(seconds) && seconds >= 0.0 ? (int64_t)(seconds * 1000.0) : -1;
}
#define KM_READ(type, fallback, ...) \
    if (!handle) return fallback; \
    KMAVPlayer *owner = km_av(handle); __block type result = fallback; \
    dispatch_sync(owner.queue, ^{ @autoreleasepool { if (!owner.closed) { \
        @try { __VA_ARGS__ } @catch (NSException *exception) { [owner push:KM_AV_ERROR message:exception.reason]; } \
    } } }); return result

int kmplayer_av_player_bridge_version(void) { return 1; }
kmplayer_av_player *kmplayer_av_player_create(int audio_only, char *error, size_t size) {
    @autoreleasepool {
        @try {
            KMAVPlayer *owner = [KMAVPlayer new];
            owner.audioOnly = audio_only != 0;
            if (!owner.player) { if (error && size) snprintf(error, size, "Unable to create AVPlayer"); return NULL; }
            return (kmplayer_av_player *)CFBridgingRetain(owner);
        } @catch (NSException *exception) {
            if (error && size) snprintf(error, size, "%s", exception.reason.UTF8String ?: "Unable to create AVPlayer");
            return NULL;
        }
    }
}
void kmplayer_av_player_destroy(kmplayer_av_player *handle) {
    if (!handle) return;
    @autoreleasepool {
        KMAVPlayer *owner = CFBridgingRelease(handle);
        dispatch_sync(owner.queue, ^{ @autoreleasepool {
            owner.closed = YES;
            [owner.player pause];
            [owner removeItem];
            [owner.player removeObserver:owner forKeyPath:@"timeControlStatus" context:&KMTimeControlContext];
            [owner.events removeAllObjects];
        } });
    }
}
int kmplayer_av_player_load(kmplayer_av_player *handle, const char *uri, int play_when_ready) {
    KM_READ(int, 0,
        NSString *text = uri ? [NSString stringWithUTF8String:uri] : nil;
        NSURL *url = text ? [NSURL URLWithString:text] : nil;
        if (url && url.scheme) {
            [owner.player pause];
            [owner removeItem];
            [owner.events removeAllObjects];
            owner.playRequested = play_when_ready != 0;
            AVPlayerItem *item = [AVPlayerItem playerItemWithURL:url];
            owner.item = item;
            if (!owner.audioOnly) {
                owner.output = [[AVPlayerItemVideoOutput alloc] initWithPixelBufferAttributes:
                    @{(id)kCVPixelBufferPixelFormatTypeKey: @(kCVPixelFormatType_32BGRA)}];
                owner.output.suppressesPlayerRendering = YES;
                [item addOutput:owner.output];
            }
            [item addObserver:owner forKeyPath:@"status" options:NSKeyValueObservingOptionInitial | NSKeyValueObservingOptionNew context:&KMItemStatusContext];
            [item addObserver:owner forKeyPath:@"tracks" options:NSKeyValueObservingOptionNew context:&KMItemTracksContext];
            __weak KMAVPlayer *weakOwner = owner;
            NSNotificationCenter *center = [NSNotificationCenter defaultCenter];
            id ended = [center addObserverForName:AVPlayerItemDidPlayToEndTimeNotification object:item queue:nil usingBlock:^(NSNotification *notification) {
                KMAVPlayer *strongOwner = weakOwner;
                if (!strongOwner) return;
                dispatch_async(strongOwner.queue, ^{ KMAVPlayer *self = weakOwner;
                    if (self && !self.closed && self.item == item) { self.playRequested = NO; [self push:KM_AV_ENDED message:nil]; }
                });
            }];
            id failed = [center addObserverForName:AVPlayerItemFailedToPlayToEndTimeNotification object:item queue:nil usingBlock:^(NSNotification *notification) {
                NSError *error = notification.userInfo[AVPlayerItemFailedToPlayToEndTimeErrorKey];
                KMAVPlayer *strongOwner = weakOwner;
                if (!strongOwner) return;
                dispatch_async(strongOwner.queue, ^{ KMAVPlayer *self = weakOwner;
                    if (self && !self.closed && self.item == item) [self push:KM_AV_ERROR message:error.localizedDescription ?: @"AVPlayer playback failed"];
                });
            }];
            [owner.notifications addObjectsFromArray:@[ended, failed]];
            [owner.player replaceCurrentItemWithPlayerItem:item];
            result = 1;
        }
    );
}
int kmplayer_av_player_play(kmplayer_av_player *handle) {
    KM_READ(int, 0, owner.playRequested = YES; if (owner.ready) [owner.player playImmediatelyAtRate:(float)owner.speed]; result = 1;);
}
int kmplayer_av_player_pause(kmplayer_av_player *handle) {
    KM_READ(int, 0, owner.playRequested = NO; [owner.player pause]; result = 1;);
}
int kmplayer_av_player_stop(kmplayer_av_player *handle) {
    KM_READ(int, 0, owner.playRequested = NO; [owner.player pause]; [owner seek:0]; result = 1;);
}
int kmplayer_av_player_seek_ms(kmplayer_av_player *handle, int64_t position) {
    KM_READ(int, 0, [owner seek:position]; result = 1;);
}
int kmplayer_av_player_set_volume(kmplayer_av_player *handle, double volume) {
    if (!isfinite(volume) || volume < 0.0 || volume > 1.0) return 0;
    KM_READ(int, 0, owner.player.volume = (float)volume; result = 1;);
}
int kmplayer_av_player_set_muted(kmplayer_av_player *handle, int muted) {
    KM_READ(int, 0, owner.player.muted = muted != 0; result = 1;);
}
int kmplayer_av_player_set_speed(kmplayer_av_player *handle, double speed) {
    if (!isfinite(speed) || speed <= 0.0 || speed > FLT_MAX) return 0;
    KM_READ(int, 0, owner.speed = speed; if (owner.playRequested && owner.ready) owner.player.rate = (float)speed; result = 1;);
}
int kmplayer_av_player_set_video_enabled(kmplayer_av_player *handle, int enabled) {
    KM_READ(int, 0,
        owner.audioOnly = enabled == 0;
        if (owner.item) {
            if (enabled && !owner.output) {
                owner.output = [[AVPlayerItemVideoOutput alloc] initWithPixelBufferAttributes:
                    @{(id)kCVPixelBufferPixelFormatTypeKey: @(kCVPixelFormatType_32BGRA)}];
                owner.output.suppressesPlayerRendering = YES;
                [owner.item addOutput:owner.output];
            } else if (!enabled && owner.output) {
                [owner.item removeOutput:owner.output];
                owner.output = nil;
            }
            [owner applyVideoPolicy];
        }
        result = 1;
    );
}
// AVFoundation audio analysis is not implemented by the native Kotlin backend either.
int kmplayer_av_player_set_audio_analysis(kmplayer_av_player *handle, int enabled) { return 0; }
int kmplayer_av_player_copy_audio_samples(kmplayer_av_player *handle, float *samples, int capacity, int *rate) { return 0; }
int64_t kmplayer_av_player_position_ms(kmplayer_av_player *handle) {
    KM_READ(int64_t, -1, result = owner.pendingSeek >= 0 ? owner.pendingSeek : km_time_ms(owner.player.currentTime););
}
int64_t kmplayer_av_player_duration_ms(kmplayer_av_player *handle) {
    KM_READ(int64_t, -1, if (owner.item) result = km_time_ms(owner.item.duration););
}
int64_t kmplayer_av_player_buffered_end_ms(kmplayer_av_player *handle) {
    KM_READ(int64_t, -1,
        CMTime position = owner.player.currentTime;
        for (NSValue *value in owner.item.loadedTimeRanges) {
            CMTimeRange range = value.CMTimeRangeValue;
            if (CMTimeRangeContainsTime(range, position)) { result = km_time_ms(CMTimeRangeGetEnd(range)); break; }
        }
    );
}
int kmplayer_av_player_has_hls(kmplayer_av_player *handle) { return handle != NULL; }
int kmplayer_av_player_is_seekable(kmplayer_av_player *handle) {
    KM_READ(int, 0, result = owner.item.seekableTimeRanges.count > 0;);
}
int kmplayer_av_player_is_live(kmplayer_av_player *handle) {
    KM_READ(int, 0, result = owner.ready && km_time_ms(owner.item.duration) < 0;);
}
int kmplayer_av_player_video_width(kmplayer_av_player *handle) {
    KM_READ(int, 0, if (!owner.audioOnly) result = (int)llround(owner.item.presentationSize.width););
}
int kmplayer_av_player_video_height(kmplayer_av_player *handle) {
    KM_READ(int, 0, if (!owner.audioOnly) result = (int)llround(owner.item.presentationSize.height););
}
uintptr_t kmplayer_av_player_native_handle(kmplayer_av_player *handle) {
    KM_READ(uintptr_t, 0, result = (uintptr_t)(__bridge void *)owner.player;);
}
int kmplayer_av_player_copy_video_frame(kmplayer_av_player *handle, void *pixels, int width, int height, int stride) {
    if (!pixels || width <= 0 || height <= 0 || width > INT_MAX / 4 || stride < width * 4) return 0;
    KM_READ(int, 0,
        CMTime time = owner.player.currentTime;
        if (owner.output && [owner.output hasNewPixelBufferForItemTime:time]) {
            CVPixelBufferRef buffer = [owner.output copyPixelBufferForItemTime:time itemTimeForDisplay:NULL];
            if (buffer) {
                CVPixelBufferRef rendered = buffer;
                if (!CGAffineTransformIsIdentity(owner.videoTransform) || CVPixelBufferGetWidth(buffer) != (size_t)width || CVPixelBufferGetHeight(buffer) != (size_t)height) {
                    CIImage *image = [[CIImage imageWithCVPixelBuffer:buffer] imageByApplyingTransform:owner.videoTransform];
                    CGRect bounds = image.extent;
                    if (bounds.size.width > 0 && bounds.size.height > 0) {
                        image = [image imageByApplyingTransform:CGAffineTransformMakeTranslation(-bounds.origin.x, -bounds.origin.y)];
                        image = [image imageByApplyingTransform:CGAffineTransformMakeScale(width / bounds.size.width, height / bounds.size.height)];
                        if (owner.renderBuffer && (CVPixelBufferGetWidth(owner.renderBuffer) != (size_t)width || CVPixelBufferGetHeight(owner.renderBuffer) != (size_t)height)) {
                            CVPixelBufferRelease(owner.renderBuffer); owner.renderBuffer = NULL;
                        }
                        if (!owner.renderBuffer) {
                            CVPixelBufferRef target = NULL;
                            if (CVPixelBufferCreate(kCFAllocatorDefault, width, height, kCVPixelFormatType_32BGRA, NULL, &target) == kCVReturnSuccess) owner.renderBuffer = target;
                        }
                        if (!owner.imageContext) owner.imageContext = [CIContext contextWithOptions:nil];
                        if (owner.renderBuffer) {
                            [owner.imageContext render:image toCVPixelBuffer:owner.renderBuffer];
                            rendered = owner.renderBuffer;
                        } else rendered = NULL;
                    } else rendered = NULL;
                }
                if (rendered && CVPixelBufferLockBaseAddress(rendered, kCVPixelBufferLock_ReadOnly) == kCVReturnSuccess) {
                    const unsigned char *base = CVPixelBufferGetBaseAddress(rendered);
                    size_t rowBytes = CVPixelBufferGetBytesPerRow(rendered);
                    if (base && rowBytes >= (size_t)width * 4 && CVPixelBufferGetHeight(rendered) >= (size_t)height) {
                        for (int y = 0; y < height; ++y) memcpy((unsigned char *)pixels + (size_t)y * stride, base + (size_t)y * rowBytes, (size_t)width * 4);
                        result = 1;
                    }
                    CVPixelBufferUnlockBaseAddress(rendered, kCVPixelBufferLock_ReadOnly);
                }
                CVPixelBufferRelease(buffer);
            }
        }
    );
}
int kmplayer_av_player_poll_event(kmplayer_av_player *handle, char *message, size_t size) {
    KM_READ(int, 0,
        if (owner.events.count) {
            NSDictionary *event = owner.events.firstObject;
            result = [event[@"code"] intValue];
            if (message && size) snprintf(message, size, "%s", [event[@"message"] UTF8String]);
            [owner.events removeObjectAtIndex:0];
        }
    );
}
