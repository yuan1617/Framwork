/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

//#define LOG_NDEBUG 0
#define LOG_TAG "NuPlayer"
#include <utils/Log.h>

#include "NuPlayer.h"

#include "HTTPLiveSource.h"
#include "NuPlayerDecoder.h"
#include "NuPlayerDecoderPassThrough.h"
#include "NuPlayerDriver.h"
#include "NuPlayerRenderer.h"
#include "NuPlayerSource.h"
#include "RTSPSource.h"
#include "StreamingSource.h"
#include "GenericSource.h"
#include "TextDescriptions.h"

#include "ATSParser.h"

#include <media/stagefright/foundation/hexdump.h>
#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MetaData.h>
#include <gui/IGraphicBufferProducer.h>

#include "avc_utils.h"

#include "ESDS.h"
#include <media/stagefright/Utils.h>
#include <utils/CallStack.h> //Callstack


#ifdef MTK_AOSP_ENHANCEMENT
#include <media/stagefright/OMXCodec.h>
#include <cutils/properties.h>
#endif

namespace android {

// TODO optimize buffer size for power consumption
// The offload read buffer size is 32 KB but 24 KB uses less power.
const size_t NuPlayer::kAggregateBufferSizeBytes = 24 * 1024;

struct NuPlayer::Action : public RefBase {
    Action() {}

    virtual void execute(NuPlayer *player) = 0;

private:
    DISALLOW_EVIL_CONSTRUCTORS(Action);
};

struct NuPlayer::SeekAction : public Action {
    SeekAction(int64_t seekTimeUs, bool needNotify)
        : mSeekTimeUs(seekTimeUs),
          mNeedNotify(needNotify) {
    }

    virtual void execute(NuPlayer *player) {
        player->performSeek(mSeekTimeUs, mNeedNotify);
    }

private:
    int64_t mSeekTimeUs;
    bool mNeedNotify;

    DISALLOW_EVIL_CONSTRUCTORS(SeekAction);
};

struct NuPlayer::SetSurfaceAction : public Action {
    SetSurfaceAction(const sp<NativeWindowWrapper> &wrapper)
        : mWrapper(wrapper) {
    }

    virtual void execute(NuPlayer *player) {
        player->performSetSurface(mWrapper);
    }

private:
    sp<NativeWindowWrapper> mWrapper;

    DISALLOW_EVIL_CONSTRUCTORS(SetSurfaceAction);
};

struct NuPlayer::ShutdownDecoderAction : public Action {
    ShutdownDecoderAction(bool audio, bool video)
        : mAudio(audio),
          mVideo(video) {
    }

    virtual void execute(NuPlayer *player) {
        player->performDecoderShutdown(mAudio, mVideo);
    }

private:
    bool mAudio;
    bool mVideo;

    DISALLOW_EVIL_CONSTRUCTORS(ShutdownDecoderAction);
};

struct NuPlayer::PostMessageAction : public Action {
    PostMessageAction(const sp<AMessage> &msg)
        : mMessage(msg) {
    }

    virtual void execute(NuPlayer *) {
        mMessage->post();
    }

private:
    sp<AMessage> mMessage;

    DISALLOW_EVIL_CONSTRUCTORS(PostMessageAction);
};

// Use this if there's no state necessary to save in order to execute
// the action.
struct NuPlayer::SimpleAction : public Action {
    typedef void (NuPlayer::*ActionFunc)();

    SimpleAction(ActionFunc func)
        : mFunc(func) {
    }

    virtual void execute(NuPlayer *player) {
        (player->*mFunc)();
    }

private:
    ActionFunc mFunc;

    DISALLOW_EVIL_CONSTRUCTORS(SimpleAction);
};

////////////////////////////////////////////////////////////////////////////////
#ifdef MTK_AOSP_ENHANCEMENT
static int64_t kRTSPEarlyEndTimeUs = 3000000ll; // 3secs
#endif 

NuPlayer::NuPlayer()
    : mUIDValid(false),
      mSourceFlags(0),
      mVideoIsAVC(false),
      mOffloadAudio(false),
      mAudioDecoderGeneration(0),
      mVideoDecoderGeneration(0),
      mRendererGeneration(0),
      mAudioEOS(false),
      mVideoEOS(false),
      mScanSourcesPending(false),
      mScanSourcesGeneration(0),
      mPollDurationGeneration(0),
      mTimedTextGeneration(0),
      mTimeDiscontinuityPending(false),
      mFlushingAudio(NONE),
      mFlushingVideo(NONE),
      mSkipRenderingAudioUntilMediaTimeUs(-1ll),
      mSkipRenderingVideoUntilMediaTimeUs(-1ll),
      mNumFramesTotal(0ll),
#ifdef MTK_AOSP_ENHANCEMENT
      mVideoDecoder(NULL),
      mAudioDecoder(NULL),
      mRenderer(NULL),
      mFlags(0),
      mPrepare(UNPREPARED),
      mDataSourceType(SOURCE_Default),
      mPlayState(STOPPED),
      mHLSConsumingAudio(HLSConsume_NONE),
      mHLSConsumingVideo(HLSConsume_NONE),
      mStopWhileHLSConsume(false),
      mPauseWhileHLSConsume(false),
      mAudioOnly(false),
      mVideoOnly(false),
      mSeekTimeUs(-1),
      mVideoinfoNotify(false),
      mAudioinfoNotify(false),
#ifdef MTK_CLEARMOTION_SUPPORT
      mEnClearMotion(1),
#endif
#ifdef MTK_SLOW_MOTION_VIDEO_SUPPORT
      mslowmotion_start(0),
      mslowmotion_end(0),
      mslowmotion_speed(1),
#endif      
#endif
      mNumFramesDropped(0ll),
      mVideoScalingMode(NATIVE_WINDOW_SCALING_MODE_SCALE_TO_WINDOW),
      mStarted(false) {
    clearFlushComplete();
#ifdef MTK_AOSP_ENHANCEMENT
    char value[PROPERTY_VALUE_MAX];   // only debug
    if (property_get("nuplayer.debug.disable.track", value, NULL)) {
        mDebugDisableTrackId = atoi(value);
    } else {
        mDebugDisableTrackId = 0;
}
    ALOGI("disable trackId:%d", mDebugDisableTrackId);
    mIsStreamSource = false;
    mNotifyListenerVideodecoderIsNull = false;
#endif
}

NuPlayer::~NuPlayer() {
    ALOGD("~NuPlayer");
}

void NuPlayer::setUID(uid_t uid) {
    mUIDValid = true;
    mUID = uid;
}

void NuPlayer::setDriver(const wp<NuPlayerDriver> &driver) {
    mDriver = driver;
}

void NuPlayer::setDataSourceAsync(const sp<IStreamSource> &source) {
#ifdef MTK_AOSP_ENHANCEMENT
    mIsStreamSource = true;
#endif
    sp<AMessage> msg = new AMessage(kWhatSetDataSource, id());

    sp<AMessage> notify = new AMessage(kWhatSourceNotify, id());

    msg->setObject("source", new StreamingSource(notify, source));
    msg->post();
}

static bool IsHTTPLiveURL(const char *url) {
    if (!strncasecmp("http://", url, 7)
            || !strncasecmp("https://", url, 8)
            || !strncasecmp("file://", url, 7)) {
        size_t len = strlen(url);
        if (len >= 5 && !strcasecmp(".m3u8", &url[len - 5])) {
            return true;
        }

        if (strstr(url,"m3u8")) {
            return true;
        }
    }

    return false;
}

#ifdef MTK_AOSP_ENHANCEMENT

static bool IsHttpURL(const char *url) {
    return (!strncasecmp(url, "http://", 7) || !strncasecmp(url, "https://", 8));
}

static bool IsRtspURL(const char *url) {
    return !strncasecmp(url, "rtsp://", 7);
}

static bool IsRtspSDP(const char *url) {
    size_t len = strlen(url);
    bool isSDP = (len >= 4 && !strcasecmp(".sdp", &url[len - 4])) || strstr(url, ".sdp?");
    return (IsHttpURL(url) && isSDP);
}

#endif
void NuPlayer::setDataSourceAsync(
        const sp<IMediaHTTPService> &httpService,
        const char *url,
        const KeyedVector<String8, String8> *headers) {

    sp<AMessage> msg = new AMessage(kWhatSetDataSource, id());
    size_t len = strlen(url);

    sp<AMessage> notify = new AMessage(kWhatSourceNotify, id());

    sp<Source> source;
    if (IsHTTPLiveURL(url)) {
        source = new HTTPLiveSource(notify, httpService, url, headers);
#ifdef MTK_AOSP_ENHANCEMENT
        mDataSourceType = SOURCE_HttpLive;
#endif
#ifdef MTK_AOSP_ENHANCEMENT
    } else if (IsRtspURL(url) || IsRtspSDP(url)) {
        ALOGI("Is RTSP Streaming");
        source = new RTSPSource(notify, httpService, url, headers, mUIDValid, mUID, IsRtspSDP(url));
#else
    } else if (!strncasecmp(url, "rtsp://", 7)) {
        source = new RTSPSource(
                notify, httpService, url, headers, mUIDValid, mUID);
    } else if ((!strncasecmp(url, "http://", 7)
                || !strncasecmp(url, "https://", 8))
                    && ((len >= 4 && !strcasecmp(".sdp", &url[len - 4]))
                    || strstr(url, ".sdp?"))) {
        source = new RTSPSource(
                notify, httpService, url, headers, mUIDValid, mUID, true);
#endif
    } else {
#ifdef MTK_AOSP_ENHANCEMENT
        if (!strncasecmp(url, "http://", 7)
                || !strncasecmp(url, "https://", 8)) {
            mDataSourceType = SOURCE_Http;
            ALOGI("Is http Streaming");
        }
#endif
        sp<GenericSource> genericSource =
                new GenericSource(notify, mUIDValid, mUID);
        // Don't set FLAG_SECURE on mSourceFlags here for widevine.
        // The correct flags will be updated in Source::kWhatFlagsChanged
        // handler when  GenericSource is prepared.

        status_t err = genericSource->setDataSource(httpService, url, headers);

        if (err == OK) {
            source = genericSource;
        } else {
            ALOGE("Failed to set data source!");
        }
    }
    msg->setObject("source", source);
    msg->post();
}

void NuPlayer::setDataSourceAsync(int fd, int64_t offset, int64_t length) {
    sp<AMessage> msg = new AMessage(kWhatSetDataSource, id());

    sp<AMessage> notify = new AMessage(kWhatSourceNotify, id());

    sp<GenericSource> source =
            new GenericSource(notify, mUIDValid, mUID);

    status_t err = source->setDataSource(fd, offset, length);

    if (err != OK) {
        ALOGE("Failed to set data source!");
        source = NULL;
    }

    msg->setObject("source", source);
#ifdef MTK_AOSP_ENHANCEMENT
    setDataSourceAsync_proCheck(msg, notify); 
#endif  
    msg->post();
}

void NuPlayer::prepareAsync() {
    (new AMessage(kWhatPrepare, id()))->post();
}

void NuPlayer::setVideoSurfaceTextureAsync(
        const sp<IGraphicBufferProducer> &bufferProducer) {
    sp<AMessage> msg = new AMessage(kWhatSetVideoNativeWindow, id());

    if (bufferProducer == NULL) {
        msg->setObject("native-window", NULL);
    } else {
        msg->setObject(
                "native-window",
                new NativeWindowWrapper(
                    new Surface(bufferProducer, true /* controlledByApp */)));
    }

    msg->post();
}

void NuPlayer::setAudioSink(const sp<MediaPlayerBase::AudioSink> &sink) {
    sp<AMessage> msg = new AMessage(kWhatSetAudioSink, id());
    msg->setObject("sink", sink);
    msg->post();
}

void NuPlayer::start() {
    (new AMessage(kWhatStart, id()))->post();
}

void NuPlayer::pause() {
    (new AMessage(kWhatPause, id()))->post();
}

void NuPlayer::resume() {
    (new AMessage(kWhatResume, id()))->post();
}

void NuPlayer::resetAsync() {
    if (mSource != NULL) {
        // During a reset, the data source might be unresponsive already, we need to
        // disconnect explicitly so that reads exit promptly.
        // We can't queue the disconnect request to the looper, as it might be
        // queued behind a stuck read and never gets processed.
        // Doing a disconnect outside the looper to allows the pending reads to exit
        // (either successfully or with error).
        mSource->disconnect();
    }

    (new AMessage(kWhatReset, id()))->post();
}

void NuPlayer::seekToAsync(int64_t seekTimeUs, bool needNotify) {
    sp<AMessage> msg = new AMessage(kWhatSeek, id());
    msg->setInt64("seekTimeUs", seekTimeUs);
    msg->setInt32("needNotify", needNotify);
    msg->post();
}

void NuPlayer::writeTrackInfo(
        Parcel* reply, const sp<AMessage> format) const {
    int32_t trackType;
    CHECK(format->findInt32("type", &trackType));

    AString lang;
    CHECK(format->findString("language", &lang));

    reply->writeInt32(2); // write something non-zero
    reply->writeInt32(trackType);
    reply->writeString16(String16(lang.c_str()));

    if (trackType == MEDIA_TRACK_TYPE_SUBTITLE) {
        AString mime;
        CHECK(format->findString("mime", &mime));

        int32_t isAuto, isDefault, isForced;
        CHECK(format->findInt32("auto", &isAuto));
        CHECK(format->findInt32("default", &isDefault));
        CHECK(format->findInt32("forced", &isForced));

        reply->writeString16(String16(mime.c_str()));
        reply->writeInt32(isAuto);
        reply->writeInt32(isDefault);
        reply->writeInt32(isForced);
    }
}

void NuPlayer::onMessageReceived(const sp<AMessage> &msg) {
    switch (msg->what()) {
        case kWhatSetDataSource:
        {
            ALOGD("kWhatSetDataSource");
 #ifdef MTK_AOSP_ENHANCEMENT
        if(mSource == NULL) {
                int32_t result;
            if(msg->findInt32("result", &result)) {
                ALOGW("kWhatSetDataSource, notify driver result");
                sp<NuPlayerDriver> driver = mDriver.promote();
                driver->notifySetDataSourceCompleted(result);
                break;
            }
        }
#endif
            CHECK(mSource == NULL);

            status_t err = OK;
            sp<RefBase> obj;
            CHECK(msg->findObject("source", &obj));
            if (obj != NULL) {
                mSource = static_cast<Source *>(obj.get());
            } else {
                err = UNKNOWN_ERROR;
            }

            CHECK(mDriver != NULL);
            sp<NuPlayerDriver> driver = mDriver.promote();
            if (driver != NULL) {
                driver->notifySetDataSourceCompleted(err);
            }
            break;
        }

        case kWhatPrepare:
        {
#ifdef MTK_AOSP_ENHANCEMENT
            ALOGD("kWhatPrepare, source type = %d", (int)mDataSourceType);
            if (mPrepare == PREPARING)
                break;
            mPrepare = PREPARING;
            if (mSource == NULL) {
                ALOGW("prepare error: source is not ready");
                finishPrepare(UNKNOWN_ERROR);
                break;
            }
#endif
            mSource->prepareAsync();
            break;
        }

        case kWhatGetTrackInfo:
        {
            uint32_t replyID;
            CHECK(msg->senderAwaitsResponse(&replyID));

            Parcel* reply;
            CHECK(msg->findPointer("reply", (void**)&reply));

            size_t inbandTracks = 0;
            if (mSource != NULL) {
                inbandTracks = mSource->getTrackCount();
            }

            size_t ccTracks = 0;
            if (mCCDecoder != NULL) {
                ccTracks = mCCDecoder->getTrackCount();
            }

            // total track count
            reply->writeInt32(inbandTracks + ccTracks);

            // write inband tracks
            for (size_t i = 0; i < inbandTracks; ++i) {
                writeTrackInfo(reply, mSource->getTrackInfo(i));
            }

            // write CC track
            for (size_t i = 0; i < ccTracks; ++i) {
                writeTrackInfo(reply, mCCDecoder->getTrackInfo(i));
            }

            sp<AMessage> response = new AMessage;
            response->postReply(replyID);
            break;
        }

        case kWhatGetSelectedTrack:
        {
            status_t err = INVALID_OPERATION;
            if (mSource != NULL) {
                err = OK;

                int32_t type32;
                CHECK(msg->findInt32("type", (int32_t*)&type32));
                media_track_type type = (media_track_type)type32;
                ssize_t selectedTrack = mSource->getSelectedTrack(type);

                Parcel* reply;
                CHECK(msg->findPointer("reply", (void**)&reply));
                reply->writeInt32(selectedTrack);
            }

            sp<AMessage> response = new AMessage;
            response->setInt32("err", err);

            uint32_t replyID;
            CHECK(msg->senderAwaitsResponse(&replyID));
            response->postReply(replyID);
            break;
        }
        case kWhatSelectTrack:
        {
            uint32_t replyID;
            CHECK(msg->senderAwaitsResponse(&replyID));

            size_t trackIndex;
            int32_t select;
            CHECK(msg->findSize("trackIndex", &trackIndex));
            CHECK(msg->findInt32("select", &select));

            status_t err = INVALID_OPERATION;

            size_t inbandTracks = 0;
            if (mSource != NULL) {
                inbandTracks = mSource->getTrackCount();
            }
            size_t ccTracks = 0;
            if (mCCDecoder != NULL) {
                ccTracks = mCCDecoder->getTrackCount();
            }

            if (trackIndex < inbandTracks) {
                err = mSource->selectTrack(trackIndex, select);
                if (!select && err == OK) {
                    int32_t type;
                    sp<AMessage> info = mSource->getTrackInfo(trackIndex);
                    if (info != NULL
                            && info->findInt32("type", &type)
                            && type == MEDIA_TRACK_TYPE_TIMEDTEXT) {
                        ++mTimedTextGeneration;
                    }
                    
                } 
#if defined(MTK_AOSP_ENHANCEMENT) && defined(MTK_AUDIO_CHANGE_SUPPORT) 
                    if(mAudioEOS == true){
                        ALOGD("push performDecoderShutdown at kWhatSelectTrack");
                        mDeferredActions.push_back(
                            new ShutdownDecoderAction(
                                true /* audio */, false /* video */));
                        
                        ALOGD("push performScanSources at kWhatSelectTrack");
                        mDeferredActions.push_back(
                            new SimpleAction(&NuPlayer::performScanSources));
                        processDeferredActions();
                    }
#endif
            } else {
                trackIndex -= inbandTracks;

                if (trackIndex < ccTracks) {
                    err = mCCDecoder->selectTrack(trackIndex, select);
                }
            }

            sp<AMessage> response = new AMessage;
            response->setInt32("err", err);

            response->postReply(replyID);
            break;
        }

        case kWhatPollDuration:
        {
            int32_t generation;
            CHECK(msg->findInt32("generation", &generation));

            if (generation != mPollDurationGeneration) {
                // stale
                break;
            }

            int64_t durationUs;
            if (mDriver != NULL && mSource->getDuration(&durationUs) == OK) {
                sp<NuPlayerDriver> driver = mDriver.promote();
                if (driver != NULL) {
                    driver->notifyDuration(durationUs);
                }
            }

            msg->post(1000000ll);  // poll again in a second.
            break;
        }

        case kWhatSetVideoNativeWindow:
        {
            ALOGD("kWhatSetVideoNativeWindow");

            mDeferredActions.push_back(
                    new ShutdownDecoderAction(
                        false /* audio */, true /* video */));

            sp<RefBase> obj;
            CHECK(msg->findObject("native-window", &obj));

            mDeferredActions.push_back(
                    new SetSurfaceAction(
                        static_cast<NativeWindowWrapper *>(obj.get())));

            if (obj != NULL) {
                if (mStarted && mSource->getFormat(false /* audio */) != NULL) {
                    // Issue a seek to refresh the video screen only if started otherwise
                    // the extractor may not yet be started and will assert.
                    // If the video decoder is not set (perhaps audio only in this case)
                    // do not perform a seek as it is not needed.
                    int64_t currentPositionUs = 0;
                    if (getCurrentPosition(&currentPositionUs) == OK) {
                    mDeferredActions.push_back(
                                new SeekAction(currentPositionUs, false /* needNotify */));
                    }
                }

                // If there is a new surface texture, instantiate decoders
                // again if possible.
                mDeferredActions.push_back(
                        new SimpleAction(&NuPlayer::performScanSources));
            }

            processDeferredActions();
            break;
        }

        case kWhatSetAudioSink:
        {
            ALOGD("kWhatSetAudioSink");

            sp<RefBase> obj;
            CHECK(msg->findObject("sink", &obj));

            mAudioSink = static_cast<MediaPlayerBase::AudioSink *>(obj.get());
            ALOGD("\t\taudio sink: %p", mAudioSink.get());
            break;
        }

        case kWhatStart:
        {
            ALOGD("kWhatStart");

            mVideoIsAVC = false;
            mOffloadAudio = false;
            mAudioEOS = false;
            mVideoEOS = false;
            mSkipRenderingAudioUntilMediaTimeUs = -1;
            mSkipRenderingVideoUntilMediaTimeUs = -1;
            mNumFramesTotal = 0;
            mNumFramesDropped = 0;
            mStarted = true;

            /* instantiate decoders now for secure playback */
            if (mSourceFlags & Source::FLAG_SECURE) {
                if (mNativeWindow != NULL) {
                    instantiateDecoder(false, &mVideoDecoder);
                }

                if (mAudioSink != NULL) {
                    instantiateDecoder(true, &mAudioDecoder);
                }
            }
#ifdef MTK_AOSP_ENHANCEMENT
            if (mPlayState == PLAYING) {
                break;
            }
            if (mSource != NULL) {
                mSource->start();
            }
#else
            mSource->start();
#endif

            uint32_t flags = 0;

            if (mSource->isRealTime()) {
                flags |= Renderer::FLAG_REAL_TIME;
            }

            sp<MetaData> audioMeta = mSource->getFormatMeta(true /* audio */);
            audio_stream_type_t streamType = AUDIO_STREAM_MUSIC;
            if (mAudioSink != NULL) {
                streamType = mAudioSink->getAudioStreamType();
            }

            sp<AMessage> videoFormat = mSource->getFormat(false /* audio */);

            mOffloadAudio =
                canOffloadStream(audioMeta, (videoFormat != NULL),
                                 true /* is_streaming */, streamType);
            if (mOffloadAudio) {
                flags |= Renderer::FLAG_OFFLOAD_AUDIO;
            }

            sp<AMessage> notify = new AMessage(kWhatRendererNotify, id());
            ++mRendererGeneration;
            notify->setInt32("generation", mRendererGeneration);
            mRenderer = new Renderer(mAudioSink, notify, flags);

            mRendererLooper = new ALooper;
            mRendererLooper->setName("NuPlayerRenderer");
            mRendererLooper->start(false, false, ANDROID_PRIORITY_AUDIO);
            mRendererLooper->registerHandler(mRenderer);
#ifdef MTK_SLOW_MOTION_VIDEO_SUPPORT
            if(mRenderer != NULL){
                mRenderer->setsmspeed(mslowmotion_speed);
            }
#endif
            sp<MetaData> meta = getFileMeta();
            int32_t rate;
            if (meta != NULL
                    && meta->findInt32(kKeyFrameRate, &rate) && rate > 0) {
                mRenderer->setVideoFrameRate(rate);
            }

            postScanSources();
#ifdef MTK_AOSP_ENHANCEMENT
           if (mDataSourceType == SOURCE_HttpLive){
               mRenderer->setLateVideoToDisplay(false);
           }
            mPlayState = PLAYING;
#endif
            break;
        }

        case kWhatScanSources:
        {
            int32_t generation;
            CHECK(msg->findInt32("generation", &generation));
            if (generation != mScanSourcesGeneration) {
                // Drop obsolete msg.
                break;
            }

            mScanSourcesPending = false;
#ifdef MTK_AOSP_ENHANCEMENT
            if (!mIsStreamSource) {       // StreamSource should use google default, due to 3rd party usage
                scanSource_l(msg);
                if (mVideoDecoder != NULL && mAudioDecoder != NULL && mRenderer != NULL) {
                    ALOGI("has video and audio");
                    uint32_t flag = Renderer::FLAG_HAS_VIDEO_AUDIO;
                    mRenderer->setFlags(flag, true); 
                }
                if (mVideoDecoder == NULL && mAudioDecoder != NULL && mRenderer != NULL) {
                    if (!mNotifyListenerVideodecoderIsNull) {
                        notifyListener(MEDIA_SET_VIDEO_SIZE, 0, 0);
                        mNotifyListenerVideodecoderIsNull = true; 
                    }
                }
                break;
            }
#endif
            ALOGD("scanning sources haveAudio=%d, haveVideo=%d",
                 mAudioDecoder != NULL, mVideoDecoder != NULL);

            bool hadAnySourcesBefore =
                (mAudioDecoder != NULL) || (mVideoDecoder != NULL);

            // initialize video before audio because successful initialization of
            // video may change deep buffer mode of audio.
            if (mNativeWindow != NULL) {
                instantiateDecoder(false, &mVideoDecoder);
            }

            if (mAudioSink != NULL) {
                if (mOffloadAudio) {
                    // open audio sink early under offload mode.
                    sp<AMessage> format = mSource->getFormat(true /*audio*/);
                    openAudioSink(format, true /*offloadOnly*/);
                }
                instantiateDecoder(true, &mAudioDecoder);
            }

            if (!hadAnySourcesBefore
                    && (mAudioDecoder != NULL || mVideoDecoder != NULL)) {
                // This is the first time we've found anything playable.

                if (mSourceFlags & Source::FLAG_DYNAMIC_DURATION) {
                    schedulePollDuration();
                }
            }

            status_t err;
            if ((err = mSource->feedMoreTSData()) != OK) {
                if (mAudioDecoder == NULL && mVideoDecoder == NULL) {
                    // We're not currently decoding anything (no audio or
                    // video tracks found) and we just ran out of input data.

                    if (err == ERROR_END_OF_STREAM) {
                        notifyListener(MEDIA_PLAYBACK_COMPLETE, 0, 0);
                    } else {
                        notifyListener(MEDIA_ERROR, MEDIA_ERROR_UNKNOWN, err);
                    }
                }
                break;
            }

            if ((mAudioDecoder == NULL && mAudioSink != NULL)
                    || (mVideoDecoder == NULL && mNativeWindow != NULL)) {
                msg->post(100000ll);
                mScanSourcesPending = true;
            }
            break;
        }

        case kWhatVideoNotify:
        case kWhatAudioNotify:
        {
            bool audio = (msg->what() == kWhatAudioNotify);

            int32_t currentDecoderGeneration =
                (audio? mAudioDecoderGeneration : mVideoDecoderGeneration);
            int32_t requesterGeneration = currentDecoderGeneration - 1;
            CHECK(msg->findInt32("generation", &requesterGeneration));

            if (requesterGeneration != currentDecoderGeneration) {
                ALOGD("got message from old %s decoder, generation(%d:%d)",
                        audio ? "audio" : "video", requesterGeneration,
                        currentDecoderGeneration);
                sp<AMessage> reply;
                if (!(msg->findMessage("reply", &reply))) {
                    return;
                }

                reply->setInt32("err", INFO_DISCONTINUITY);
                reply->post();
                return;
            }

            int32_t what;
            CHECK(msg->findInt32("what", &what));

            if (what == Decoder::kWhatFillThisBuffer) {
                status_t err = feedDecoderInputData(audio, msg);

                if (err == -EWOULDBLOCK) {
                    if (mSource->feedMoreTSData() == OK) {
                        msg->post(10 * 1000ll);
                    }
#ifdef MTK_AOSP_ENHANCEMENT
                    // for RTSP, feedMoreTSData() may return !OK, and there is no buffer reply, the ACodec will be blocked due to not receive buffer reply when flushing.
                    else {
                        sp<AMessage> reply;
                        err = mSource->feedMoreTSData();
                        CHECK(msg->findMessage("reply", &reply));

                        ALOGW(" %s, cannot feed more ts data when EWOULDBLOCK, reply source err(%d)", audio ? "audio" : "video", err);
                        reply->setInt32("err", err);
                        reply->post();
                    }
#endif                
                }
            } else if (what == Decoder::kWhatEOS) {
                int32_t err;
                CHECK(msg->findInt32("err", &err));

                if (err == ERROR_END_OF_STREAM) {
                    ALOGD("got %s decoder EOS", audio ? "audio" : "video");
#ifdef MTK_AOSP_ENHANCEMENT
                    if (mDataSourceType == SOURCE_HttpLive) {
                        handleForACodecInfoDiscontinuity(audio,err);  
                    }
#endif
                } 
                else {
                    ALOGD("got %s decoder EOS w/ error %d",
                         audio ? "audio" : "video",
                         err);
                }

                mRenderer->queueEOS(audio, err);
            } else if (what == Decoder::kWhatFlushCompleted) {
#ifdef MTK_AOSP_ENHANCEMENT
                ALOGD("decoder %s flush completed", audio ? "audio" : "video");
#else
                ALOGV("decoder %s flush completed", audio ? "audio" : "video");
#endif
                handleFlushComplete(audio, true /* isDecoder */);
                finishFlushIfPossible();
            } else if (what == Decoder::kWhatOutputFormatChanged) {
                sp<AMessage> format;
                CHECK(msg->findMessage("format", &format));

                if (audio) {
                    openAudioSink(format, false /*offloadOnly*/);
                } else {
                    // video
                    sp<AMessage> inputFormat =
                            mSource->getFormat(false /* audio */);

                    updateVideoSize(inputFormat, format);
                }
            } else if (what == Decoder::kWhatShutdownCompleted) {
                ALOGD("%s shutdown completed", audio ? "audio" : "video");
#ifdef MTK_AOSP_ENHANCEMENT
                if(handleForACodecShutdownCompleted(audio))
                    break;
#endif
                if (audio) {
                    mAudioDecoder.clear();
                    ++mAudioDecoderGeneration;

                    CHECK_EQ((int)mFlushingAudio, (int)SHUTTING_DOWN_DECODER);
                    mFlushingAudio = SHUT_DOWN;
                } else {
                    mVideoDecoder.clear();
                    ++mVideoDecoderGeneration;

                    CHECK_EQ((int)mFlushingVideo, (int)SHUTTING_DOWN_DECODER);
                    mFlushingVideo = SHUT_DOWN;
                }
                finishFlushIfPossible();
            } else if (what == Decoder::kWhatError) {
                ALOGE("Received error from %s decoder, aborting playback.",
                     audio ? "audio" : "video");
#ifdef MTK_AOSP_ENHANCEMENT
                if (mDataSourceType == SOURCE_HttpLive){
                    ALOGI("HLS handle for ACodec Error");
                    handleForACodecError(audio,msg);
                    break;
                }
#endif
                status_t err;
                if (!msg->findInt32("err", &err) || err == OK) {
                    err = UNKNOWN_ERROR;
                }

                // Decoder errors can be due to Source (e.g. from streaming),
                // or from decoding corrupted bitstreams, or from other decoder
                // MediaCodec operations (e.g. from an ongoing reset or seek).
                //
                // We try to gracefully shut down the affected decoder if possible,
                // rather than trying to force the shutdown with something
                // similar to performReset(). This method can lead to a hang
                // if MediaCodec functions block after an error, but they should
                // typically return INVALID_OPERATION instead of blocking.

                FlushStatus *flushing = audio ? &mFlushingAudio : &mFlushingVideo;
                ALOGE("received error(%#x) from %s decoder, flushing(%d), now shutting down",
                        err, audio ? "audio" : "video", *flushing);
#ifdef MTK_AOSP_ENHANCEMENT
                if (mRenderer != NULL) {
                    if (mDataSourceType == SOURCE_Local) {
                        uint32_t flag = Renderer::FLAG_HAS_VIDEO_AUDIO;
                        mRenderer->setFlags(flag, false); 
                    } else {
                        mRenderer->queueEOS(audio, err);
                    }
                }
#endif
                switch (*flushing) {
                    case NONE:
                        mDeferredActions.push_back(
                                new ShutdownDecoderAction(audio, !audio /* video */));
                        processDeferredActions();
                        break;
                    case FLUSHING_DECODER:
                        *flushing = FLUSHING_DECODER_SHUTDOWN; // initiate shutdown after flush.
                        break; // Wait for flush to complete.
                    case FLUSHING_DECODER_SHUTDOWN:
                        break; // Wait for flush to complete.
                    case SHUTTING_DOWN_DECODER:
                        break; // Wait for shutdown to complete.
                    case FLUSHED:
                        // Widevine source reads must stop before releasing the video decoder.
                        if (!audio && mSource != NULL && mSourceFlags & Source::FLAG_SECURE) {
                            mSource->stop();
                        }
                        getDecoder(audio)->initiateShutdown(); // In the middle of a seek.
                        *flushing = SHUTTING_DOWN_DECODER;     // Shut down.
                        break;
                    case SHUT_DOWN:
                        finishFlushIfPossible();  // Should not occur.
                        break;                    // Finish anyways.
                }

#ifdef MTK_AOSP_ENHANCEMENT
                if (err == ERROR_BAD_FRAME_SIZE) {
                	notifyListener(MEDIA_ERROR, MEDIA_ERROR_BAD_FILE, 0);
                } else {
                	if(mDataSourceType == SOURCE_Local) { 
                		if (!audio) {
                            mVideoinfoNotify = true;
                			if (mSource->getFormat(true) != NULL) {
                                if (false == mAudioinfoNotify) {
                                    notifyListener(MEDIA_INFO, MEDIA_INFO_HAS_UNSUPPORT_VIDEO, 0);
                                }else {
                                    notifyListener(MEDIA_ERROR, MEDIA_ERROR_TYPE_NOT_SUPPORTED, 0);
                                }
                            }else {
                                notifyListener(MEDIA_ERROR, MEDIA_ERROR_TYPE_NOT_SUPPORTED, 0);
                            }
                		} else {
                            mAudioinfoNotify = true;
                			if (mVideoDecoder != NULL) {
                                if (false == mVideoinfoNotify) {
                                    notifyListener(MEDIA_INFO, MEDIA_INFO_HAS_UNSUPPORT_AUDIO, 0);
                                }else {
                                    notifyListener(MEDIA_ERROR, MEDIA_ERROR_TYPE_NOT_SUPPORTED, 0);
                                }
                            }else {
                                notifyListener(MEDIA_ERROR, MEDIA_ERROR_TYPE_NOT_SUPPORTED, 0);
                            }
                		}
                	} else {
                		notifyListener(MEDIA_INFO, audio ? MEDIA_INFO_HAS_UNSUPPORT_AUDIO : MEDIA_INFO_HAS_UNSUPPORT_VIDEO, 0);
                	}
                }
#else
                notifyListener(MEDIA_ERROR, MEDIA_ERROR_UNKNOWN, err);
#endif

            } else if (what == Decoder::kWhatDrainThisBuffer) {
                renderBuffer(audio, msg);
#ifdef MTK_AOSP_ENHANCEMENT
#if 0
            } else if (what == ACodec::kWhatComponentAllocated) {
                handleForACodecComponentAllocated(msg);
#endif
#endif
            } else {
                ALOGV("Unhandled decoder notification %d '%c%c%c%c'.",
                      what,
                      what >> 24,
                      (what >> 16) & 0xff,
                      (what >> 8) & 0xff,
                      what & 0xff);
            }

            break;
        }

        case kWhatRendererNotify:
        {
            int32_t requesterGeneration = mRendererGeneration - 1;
            CHECK(msg->findInt32("generation", &requesterGeneration));
            if (requesterGeneration != mRendererGeneration) {
                ALOGV("got message from old renderer, generation(%d:%d)",
                        requesterGeneration, mRendererGeneration);
                return;
            }

            int32_t what;
            CHECK(msg->findInt32("what", &what));

            if (what == Renderer::kWhatEOS) {
                int32_t audio;
                CHECK(msg->findInt32("audio", &audio));

                int32_t finalResult;
                CHECK(msg->findInt32("finalResult", &finalResult));
#ifdef MTK_AOSP_ENHANCEMENT
                if(handleForRenderEos(finalResult,audio))
                      break;
#endif
                if (audio) {
                    mAudioEOS = true;
                } else {
                    mVideoEOS = true;
                }

                if (finalResult == ERROR_END_OF_STREAM) {
                    ALOGD("reached %s EOS", audio ? "audio" : "video");
                } else {
                    ALOGE("%s track encountered an error (%d)",
                            audio ? "audio" : "video", finalResult);
#ifdef MTK_AOSP_ENHANCEMENT
                handleForRenderError1(finalResult,audio);
#else
                    notifyListener(
                            MEDIA_ERROR, MEDIA_ERROR_UNKNOWN, finalResult);
#endif
                }

                if ((mAudioEOS || mAudioDecoder == NULL)
                 && (mVideoEOS || mVideoDecoder == NULL)) {
#ifdef MTK_AOSP_ENHANCEMENT
                    if(handleForRenderError2(finalResult,audio))
                        break;
#endif
                    notifyListener(MEDIA_PLAYBACK_COMPLETE, 0, 0);
                }
            } else if (what == Renderer::kWhatFlushComplete) {
                int32_t audio;
                CHECK(msg->findInt32("audio", &audio));
#ifdef MTK_AOSP_ENHANCEMENT
                ALOGD("renderer %s flush completed.", audio ? "audio" : "video");
#else
                ALOGV("renderer %s flush completed.", audio ? "audio" : "video");
#endif
                handleFlushComplete(audio, false /* isDecoder */);
                finishFlushIfPossible();
            } else if (what == Renderer::kWhatVideoRenderingStart) {
                notifyListener(MEDIA_INFO, MEDIA_INFO_RENDERING_START, 0);
            } else if (what == Renderer::kWhatMediaRenderingStart) {
                ALOGV("media rendering started");
                notifyListener(MEDIA_STARTED, 0, 0);
            } else if (what == Renderer::kWhatAudioOffloadTearDown) {
                ALOGD("Tear down audio offload, fall back to s/w path");
                int64_t positionUs;
                CHECK(msg->findInt64("positionUs", &positionUs));
                int32_t reason;
                CHECK(msg->findInt32("reason", &reason));
                closeAudioSink();
                mAudioDecoder.clear();
                ++mAudioDecoderGeneration;
                mRenderer->flush(true /* audio */);
                if (mVideoDecoder != NULL) {
                    mRenderer->flush(false /* audio */);
                }
                mRenderer->signalDisableOffloadAudio();
                mOffloadAudio = false;

                performSeek(positionUs, false /* needNotify */);
                if (reason == Renderer::kDueToError) {
                    instantiateDecoder(true /* audio */, &mAudioDecoder);
                }
            }
            break;
        }
#ifdef MTK_AOSP_ENHANCEMENT
        case kWhatStop:
        {
            // mtk80902: substitute of calling pause in NuPlayerDriver's stop most for the rtsp
            ALOGD("kWhatStop, %d", (int32_t)mPlayState);
            onStop();
            break;
        }
#endif
        case kWhatMoreDataQueued:
        {
            break;
        }

        case kWhatReset:
        {
            ALOGD("kWhatReset");

            mDeferredActions.push_back(
                    new ShutdownDecoderAction(
                        true /* audio */, true /* video */));

            mDeferredActions.push_back(
                    new SimpleAction(&NuPlayer::performReset));

            processDeferredActions();
            break;
        }

        case kWhatSeek:
        {
            int64_t seekTimeUs;
            int32_t needNotify;
            CHECK(msg->findInt64("seekTimeUs", &seekTimeUs));
            CHECK(msg->findInt32("needNotify", &needNotify));

            ALOGD("kWhatSeek seekTimeUs=%lld us, needNotify=%d",
                    seekTimeUs, needNotify);

#ifdef MTK_AOSP_ENHANCEMENT
            //no need to flush decoder because it's already done in performSeek
#else
            mDeferredActions.push_back(
                    new SimpleAction(&NuPlayer::performDecoderFlush));
#endif

            mDeferredActions.push_back(
                    new SeekAction(seekTimeUs, needNotify));

            processDeferredActions();
            break;
        }

        case kWhatPause:
        {
#ifdef MTK_AOSP_ENHANCEMENT
            if(onPause())
                break;
            
            mPlayState = PAUSING;
#endif
            if (mSource != NULL) {
                mSource->pause();
            } else {
                ALOGW("pause called when source is gone or not set");
            }
            if (mRenderer != NULL) {
                mRenderer->pause();
            } else {
                ALOGW("pause called when renderer is gone or not set");
            }
            break;
        }

        case kWhatResume:
        {
            ALOGD("kWhatResume");
#ifdef MTK_AOSP_ENHANCEMENT
            if(onResume())
                break;
            
            mPlayState = PLAYSENDING;
#endif
            if (mSource != NULL) {
                mSource->resume();
            } else {
                ALOGW("resume called when source is gone or not set");
            }
            // |mAudioDecoder| may have been released due to the pause timeout, so re-create it if
            // needed.
            if (audioDecoderStillNeeded() && mAudioDecoder == NULL) {
                instantiateDecoder(true /* audio */, &mAudioDecoder);
            }
            if (mRenderer != NULL) {
                mRenderer->resume();
            } else {
                ALOGW("resume called when renderer is gone or not set");
            }
            break;
        }

        case kWhatSourceNotify:
        {
            onSourceNotify(msg);
            break;
        }

        case kWhatClosedCaptionNotify:
        {
            onClosedCaptionNotify(msg);
            break;
        }

        default:
            TRESPASS();
            break;
    }
}

bool NuPlayer::audioDecoderStillNeeded() {
    // Audio decoder is no longer needed if it's in shut/shutting down status.
    return ((mFlushingAudio != SHUT_DOWN) && (mFlushingAudio != SHUTTING_DOWN_DECODER));
}

void NuPlayer::handleFlushComplete(bool audio, bool isDecoder) {
    // We wait for both the decoder flush and the renderer flush to complete
    // before entering either the FLUSHED or the SHUTTING_DOWN_DECODER state.

    mFlushComplete[audio][isDecoder] = true;
    if (!mFlushComplete[audio][!isDecoder]) {
        return;
    }

    FlushStatus *state = audio ? &mFlushingAudio : &mFlushingVideo;
    switch (*state) {
        case FLUSHING_DECODER:
        {
            *state = FLUSHED;
            break;
        }

        case FLUSHING_DECODER_SHUTDOWN:
        {
            *state = SHUTTING_DOWN_DECODER;

            ALOGV("initiating %s decoder shutdown", audio ? "audio" : "video");
            if (!audio) {
                // Widevine source reads must stop before releasing the video decoder.
                if (mSource != NULL && mSourceFlags & Source::FLAG_SECURE) {
                    mSource->stop();
                }
            }
            getDecoder(audio)->initiateShutdown();
            break;
        }

        default:
            // decoder flush completes only occur in a flushing state.
            LOG_ALWAYS_FATAL_IF(isDecoder, "decoder flush in invalid state %d", *state);
            break;
    }
}

void NuPlayer::finishFlushIfPossible() {
    if (mFlushingAudio != NONE && mFlushingAudio != FLUSHED
            && mFlushingAudio != SHUT_DOWN) {
#ifdef MTK_AOSP_ENHANCEMENT
        ALOGD("not flushed, mFlushingAudio = %d", mFlushingAudio);
#endif
        return;
    }

    if (mFlushingVideo != NONE && mFlushingVideo != FLUSHED
            && mFlushingVideo != SHUT_DOWN) {
#ifdef MTK_AOSP_ENHANCEMENT
        ALOGD("not flushed, mFlushingVideo = %d", mFlushingVideo);
#endif
        return;
    }

    ALOGD("both audio and video are flushed now.");

    mPendingAudioAccessUnit.clear();
    mAggregateBuffer.clear();

    if (mTimeDiscontinuityPending) {
#ifdef MTK_AOSP_ENHANCEMENT
        uint32_t flag = Renderer::FLAG_HAS_VIDEO_AUDIO;
        if (mAudioDecoder != NULL && mFlushingAudio == FLUSHED && 
                mVideoDecoder != NULL && mFlushingVideo == FLUSHED) {
            ALOGI("has video and audio sync queue");
            mRenderer->setFlags(flag, true); 
        } else {
            ALOGI("has video and audio not sync queue");
            mRenderer->setFlags(flag, false); 
        }
#endif
        mRenderer->signalTimeDiscontinuity();
        mTimeDiscontinuityPending = false;
    }
#ifdef MTK_AOSP_ENHANCEMENT
    finishFlushIfPossible_l();
#endif

    if (mAudioDecoder != NULL && mFlushingAudio == FLUSHED) {
        mAudioDecoder->signalResume();
    }

    if (mVideoDecoder != NULL && mFlushingVideo == FLUSHED) {
        mVideoDecoder->signalResume();
    }

    mFlushingAudio = NONE;
    mFlushingVideo = NONE;

    clearFlushComplete();

    processDeferredActions();
}

void NuPlayer::postScanSources() {
    if (mScanSourcesPending) {
        return;
    }

    sp<AMessage> msg = new AMessage(kWhatScanSources, id());
    msg->setInt32("generation", mScanSourcesGeneration);
    msg->post();

    mScanSourcesPending = true;
}

void NuPlayer::openAudioSink(const sp<AMessage> &format, bool offloadOnly) {
    uint32_t flags;
    int64_t durationUs;
    bool hasVideo = (mVideoDecoder != NULL);
    // FIXME: we should handle the case where the video decoder
    // is created after we receive the format change indication.
    // Current code will just make that we select deep buffer
    // with video which should not be a problem as it should
    // not prevent from keeping A/V sync.
    if (hasVideo &&
            mSource->getDuration(&durationUs) == OK &&
            durationUs
                > AUDIO_SINK_MIN_DEEP_BUFFER_DURATION_US) {
        flags = AUDIO_OUTPUT_FLAG_DEEP_BUFFER;
    } else {
        flags = AUDIO_OUTPUT_FLAG_NONE;
    }
#ifdef MTK_AOSP_ENHANCEMENT
#ifdef MTK_DOLBY_DAP_SUPPORT
        // DS Effect is attached only to the Non-Deep Buffered Output
        // And we want all audio to flow through DS Effect.
        // As such, we force both Music and Movie Playbacks to take the Non-Deep Buffered Output
        flags = AUDIO_OUTPUT_FLAG_NONE;
#endif
#endif
    mOffloadAudio = mRenderer->openAudioSink(
            format, offloadOnly, hasVideo, flags);

    if (mOffloadAudio) {
        sp<MetaData> audioMeta =
                mSource->getFormatMeta(true /* audio */);
        sendMetaDataToHal(mAudioSink, audioMeta);
    }
}

void NuPlayer::closeAudioSink() {
    mRenderer->closeAudioSink();
}

status_t NuPlayer::instantiateDecoder(bool audio, sp<Decoder> *decoder) {
    if (*decoder != NULL) {
        ALOGD("%s decoder not NULL!", audio?"audio":"video");
        return OK;
    }

    sp<AMessage> format = mSource->getFormat(audio);

    if (format == NULL) {
        ALOGD("%s format is NULL!", audio?"audio":"video");
        return -EWOULDBLOCK;
    }

#ifdef MTK_AOSP_ENHANCEMENT
    if (mDebugDisableTrackId != 0) {        // only debug
        if (mDebugDisableTrackId == 1 && audio) {
            ALOGI("Only Debug  disable audio");
            return -EWOULDBLOCK;
        } else if (mDebugDisableTrackId == 2 && !audio) {
            ALOGI("Only Debug  disable video");
            return -EWOULDBLOCK;
        }
    }
    if(!audio) {
        setVideoProperties(format);
        ALOGD("instantiate Video decoder.");
    }
    else {
        ALOGD("instantiate Audio decoder.");
    }
#endif
    if (!audio) {
        AString mime;
        CHECK(format->findString("mime", &mime));
        mVideoIsAVC = !strcasecmp(MEDIA_MIMETYPE_VIDEO_AVC, mime.c_str());

        sp<AMessage> ccNotify = new AMessage(kWhatClosedCaptionNotify, id());
        mCCDecoder = new CCDecoder(ccNotify);

        if (mSourceFlags & Source::FLAG_SECURE) {
            format->setInt32("secure", true);
        }
    }

    if (audio) {
        sp<AMessage> notify = new AMessage(kWhatAudioNotify, id());
        ++mAudioDecoderGeneration;
        notify->setInt32("generation", mAudioDecoderGeneration);

        if (mOffloadAudio) {
            *decoder = new DecoderPassThrough(notify);
        } else {
            *decoder = new Decoder(notify);
        }
    } else {
        sp<AMessage> notify = new AMessage(kWhatVideoNotify, id());
        ++mVideoDecoderGeneration;
        notify->setInt32("generation", mVideoDecoderGeneration);

        *decoder = new Decoder(notify, mNativeWindow);
    }
    (*decoder)->init();
#ifdef MTK_SLOW_MOTION_VIDEO_SUPPORT       
    if (decoder != NULL && !(mslowmotion_start == 0 && mslowmotion_end == 0))
    {
        sp<AMessage> msg = new AMessage;
        format->setInt64("slowmotion-start", mslowmotion_start);
        format->setInt64("slowmotion-end", mslowmotion_end);
        format->setInt32("slowmotion-speed", mslowmotion_speed);
        ALOGD("(%d) instantiareDecoder-> set slowmotion start(%lld) ~ end(%lld), speed(%d)", __LINE__, mslowmotion_start, mslowmotion_start, mslowmotion_speed);
    }
#endif  
    (*decoder)->configure(format);

    // allocate buffers to decrypt widevine source buffers
    if (!audio && (mSourceFlags & Source::FLAG_SECURE)) {
        Vector<sp<ABuffer> > inputBufs;
        CHECK_EQ((*decoder)->getInputBuffers(&inputBufs), (status_t)OK);

        Vector<MediaBuffer *> mediaBufs;
        for (size_t i = 0; i < inputBufs.size(); i++) {
            const sp<ABuffer> &buffer = inputBufs[i];
            MediaBuffer *mbuf = new MediaBuffer(buffer->data(), buffer->size());
            mediaBufs.push(mbuf);
        }

        status_t err = mSource->setBuffers(audio, mediaBufs);
        if (err != OK) {
            for (size_t i = 0; i < mediaBufs.size(); ++i) {
                mediaBufs[i]->release();
            }
            mediaBufs.clear();
            ALOGE("Secure source didn't support secure mediaBufs.");
            return err;
        }
    }
    return OK;
}

status_t NuPlayer::feedDecoderInputData(bool audio, const sp<AMessage> &msg) {
    sp<AMessage> reply;
    CHECK(msg->findMessage("reply", &reply));
#ifdef MTK_AOSP_ENHANCEMENT
    if ( (audio && isHLSConsumingState(mHLSConsumingAudio)) || (!audio && isHLSConsumingState(mHLSConsumingVideo))) {
        ALOGD(" %s: HLS consuming, so send ok to decoder.", audio?"audio":"video");
                reply->setInt32("err", OK);
                reply->post();
                return OK;
        }else if (((audio && mFlushingAudio != NONE)
                               || (!audio && mFlushingVideo != NONE)
                               || mSource == NULL) ) {
                reply->setInt32("err", INFO_DISCONTINUITY);
                reply->post();
                return OK;
        }
#else      

    if ((audio && mFlushingAudio != NONE)
            || (!audio && mFlushingVideo != NONE)
            || mSource == NULL) {
        reply->setInt32("err", INFO_DISCONTINUITY);
        reply->post();
        return OK;
    }
#endif    

    sp<ABuffer> accessUnit;

    // Aggregate smaller buffers into a larger buffer.
    // The goal is to reduce power consumption.
    // Note this will not work if the decoder requires one frame per buffer.
    bool doBufferAggregation = (audio && mOffloadAudio);
    bool needMoreData = false;

    bool dropAccessUnit;
    do {
        status_t err;
        // Did we save an accessUnit earlier because of a discontinuity?
        if (audio && (mPendingAudioAccessUnit != NULL)) {
            accessUnit = mPendingAudioAccessUnit;
            mPendingAudioAccessUnit.clear();
            err = mPendingAudioErr;
            ALOGD("feedDecoderInputData() use mPendingAudioAccessUnit err:%d",err);
        } else {
            err = mSource->dequeueAccessUnit(audio, &accessUnit);
        }

        if (err == -EWOULDBLOCK) {
            return err;
        } else if (err != OK) {
            if (err == INFO_DISCONTINUITY) {
                if (doBufferAggregation && (mAggregateBuffer != NULL)) {
                    // We already have some data so save this for later.
                    mPendingAudioErr = err;
                    mPendingAudioAccessUnit = accessUnit;
                    accessUnit.clear();
                    ALOGD("feedDecoderInputData() save discontinuity for later");
                    break;
                }
                int32_t type;
                CHECK(accessUnit->meta()->findInt32("discontinuity", &type));

                bool formatChange =
                    (audio &&
                     (type & ATSParser::DISCONTINUITY_AUDIO_FORMAT))
                    || (!audio &&
                            (type & ATSParser::DISCONTINUITY_VIDEO_FORMAT));

                bool timeChange = (type & ATSParser::DISCONTINUITY_TIME) != 0;

                ALOGI("%s discontinuity (type 0x%x formatChange=%d, time=%d) mFlushingAudio %d && mFlushingVideo %d)",
                     audio ? "audio" : "video", type,formatChange, timeChange,mFlushingAudio , mFlushingVideo);

                if (audio) {
                    mSkipRenderingAudioUntilMediaTimeUs = -1;
                } else {
                    mSkipRenderingVideoUntilMediaTimeUs = -1;
                }

                if (timeChange) {
                    sp<AMessage> extra;
                    if (accessUnit->meta()->findMessage("extra", &extra)
                            && extra != NULL) {
                        int64_t resumeAtMediaTimeUs;
                        if (extra->findInt64(
                                    "resume-at-mediatimeUs", &resumeAtMediaTimeUs)) {
                            ALOGI("suppressing rendering of %s until %lld us",
                                    audio ? "audio" : "video", resumeAtMediaTimeUs);

                            if (audio) {
                                mSkipRenderingAudioUntilMediaTimeUs =
                                    resumeAtMediaTimeUs;
                            } else {
                                mSkipRenderingVideoUntilMediaTimeUs =
                                    resumeAtMediaTimeUs;
                            }
                        }
                    }
                }

                mTimeDiscontinuityPending =
                    mTimeDiscontinuityPending || timeChange;

                bool seamlessFormatChange = false;
                sp<AMessage> newFormat = mSource->getFormat(audio);
                if (formatChange) {
                    seamlessFormatChange =
                        getDecoder(audio)->supportsSeamlessFormatChange(newFormat);
                    // treat seamless format change separately
#ifdef MTK_AOSP_ENHANCEMENT
                    ALOGI("supportsSeamlessFormatChange %d,newFormat=0x%x",seamlessFormatChange,newFormat.get());
#endif 
                    formatChange = !seamlessFormatChange;
                }
                bool shutdownOrFlush = formatChange || timeChange;

                // We want to queue up scan-sources only once per discontinuity.
                // We control this by doing it only if neither audio nor video are
                // flushing or shutting down.  (After handling 1st discontinuity, one
                // of the flushing states will not be NONE.)
                // No need to scan sources if this discontinuity does not result
                // in a flush or shutdown, as the flushing state will stay NONE.
#ifdef MTK_AOSP_ENHANCEMENT                 
                if(mDataSourceType != SOURCE_Local){ 
#endif
                if (mFlushingAudio == NONE && mFlushingVideo == NONE &&
                        shutdownOrFlush) {
                    // And we'll resume scanning sources once we're done
                    // flushing.
#ifdef MTK_AOSP_ENHANCEMENT
                        //if format change : mFlushingAudio, mFlushingVideo will not be set
                        //but the mHLSConsumingAudio and mHLSConsumingVideo will be set
                        if(formatChange){ 
                        
                            if (mHLSConsumingAudio == HLSConsume_NONE && mHLSConsumingVideo == HLSConsume_NONE){
                                ALOGI("mDeferredActions post performScanSource,formatChange");
                                mDeferredActions.push_front(
                                        new SimpleAction(
                                            &NuPlayer::performScanSources));
                            }
                        }else{
                            ALOGI("mDeferredActions post performScanSource");
#endif
                            mDeferredActions.push_front(
                                    new SimpleAction(
                                        &NuPlayer::performScanSources));
                        }
#ifdef MTK_AOSP_ENHANCEMENT 
                    }//  if (mFlushingAudio == NONE && mFlushingVideo == NONE && shutdownOrFlush)
                            
                }//if(mDataSourceType != SOURCE_Local)
#endif
#ifdef MTK_AOSP_ENHANCEMENT                  
                if(mDataSourceType == SOURCE_HttpLive && shutdownOrFlush) { 
                    //only one stream will flushed
					//if we not set the other stream FLUSHED,finishFlushIfPossible will be blocked
                    if(mAudioOnly) {
						mFlushingVideo = FLUSHED;
                    }else if(mVideoOnly) {
						mFlushingAudio = FLUSHED;
                    }
                }
                ALOGI("%s discontinuity - (type 0x%x formatChange=%d, time=%d) mFlushingAudio %d && mFlushingVideo %d)",
                     audio ? "audio" : "video", type,formatChange, timeChange,mFlushingAudio , mFlushingVideo);
#endif

                if (formatChange /* not seamless */) {//we consume data before shutdown code
#ifdef MTK_AOSP_ENHANCEMENT               
#ifdef MTK_AUDIO_CHANGE_SUPPORT
                    if(mDataSourceType == SOURCE_Local){
                        flushDecoder(audio, /* needShutdown = */ true);
                                                mDeferredActions.push_back(
                                                    new SimpleAction(&NuPlayer::performScanSources));

                    }else{  
                        hlsConsumeDecoder(audio);                       
                        if(mDataSourceType == SOURCE_HttpLive)
                            err = ERROR_END_OF_STREAM;
                    }
#else                   
                    hlsConsumeDecoder(audio);
                    if(mDataSourceType == SOURCE_HttpLive)
                        err = ERROR_END_OF_STREAM;

#endif
                } else if (timeChange && !(type == ATSParser::DISCONTINUITY_FORMATCHANGE)) {
#else                   
                    // must change decoder
                    flushDecoder(audio, /* needShutdown = */ true);
                } else if (timeChange) {
#endif                    
                    // need to flush
                    flushDecoder(audio, /* needShutdown = */ false, newFormat);
                    err = OK;
                } else if (seamlessFormatChange) {
                    // reuse existing decoder and don't flush
                    updateDecoderFormatWithoutFlush(audio, newFormat);
                    err = OK;
                } else {
                    // This stream is unaffected by the discontinuity
                    return -EWOULDBLOCK;
                }
            }

            reply->setInt32("err", err);
            reply->post();
            return OK;
        }

        if (!audio) {
            ++mNumFramesTotal;
        }

        dropAccessUnit = false;
        if (!audio
                && !(mSourceFlags & Source::FLAG_SECURE)
                && mRenderer->getVideoLateByUs() > 100000ll
                && mVideoIsAVC
                && !IsAVCReferenceFrame(accessUnit)) {
            ALOGD("drop %lld / %lld", mNumFramesDropped, mNumFramesTotal);
            dropAccessUnit = true;
            ++mNumFramesDropped;
        }

        size_t smallSize = accessUnit->size();
        needMoreData = false;
        if (doBufferAggregation && (mAggregateBuffer == NULL)
                // Don't bother if only room for a few small buffers.
                && (smallSize < (kAggregateBufferSizeBytes / 3))) {
            // Create a larger buffer for combining smaller buffers from the extractor.
            mAggregateBuffer = new ABuffer(kAggregateBufferSizeBytes);
            mAggregateBuffer->setRange(0, 0); // start empty
        }

        if (doBufferAggregation && (mAggregateBuffer != NULL)) {
            int64_t timeUs;
            int64_t dummy;
            bool smallTimestampValid = accessUnit->meta()->findInt64("timeUs", &timeUs);
            bool bigTimestampValid = mAggregateBuffer->meta()->findInt64("timeUs", &dummy);
            // Will the smaller buffer fit?
            size_t bigSize = mAggregateBuffer->size();
            size_t roomLeft = mAggregateBuffer->capacity() - bigSize;
            // Should we save this small buffer for the next big buffer?
            // If the first small buffer did not have a timestamp then save
            // any buffer that does have a timestamp until the next big buffer.
            if ((smallSize > roomLeft)
                || (!bigTimestampValid && (bigSize > 0) && smallTimestampValid)) {
                mPendingAudioErr = err;
                mPendingAudioAccessUnit = accessUnit;
                accessUnit.clear();
            } else {
                // Grab time from first small buffer if available.
                if ((bigSize == 0) && smallTimestampValid) {
                    mAggregateBuffer->meta()->setInt64("timeUs", timeUs);
                }
                // Append small buffer to the bigger buffer.
                memcpy(mAggregateBuffer->base() + bigSize, accessUnit->data(), smallSize);
                bigSize += smallSize;
                mAggregateBuffer->setRange(0, bigSize);

                // Keep looping until we run out of room in the mAggregateBuffer.
                needMoreData = true;

                ALOGV("feedDecoderInputData() smallSize = %zu, bigSize = %zu, capacity = %zu",
                        smallSize, bigSize, mAggregateBuffer->capacity());
            }
        }
    } while (dropAccessUnit || needMoreData);

    // ALOGV("returned a valid buffer of %s data", audio ? "audio" : "video");

#if 0
    int64_t mediaTimeUs;
    CHECK(accessUnit->meta()->findInt64("timeUs", &mediaTimeUs));
    ALOGV("feeding %s input buffer at media time %.2f secs",
         audio ? "audio" : "video",
         mediaTimeUs / 1E6);
#endif

    if (!audio) {
        mCCDecoder->decode(accessUnit);
    }

    if (doBufferAggregation && (mAggregateBuffer != NULL)) {
        ALOGV("feedDecoderInputData() reply with aggregated buffer, %zu",
                mAggregateBuffer->size());
        reply->setBuffer("buffer", mAggregateBuffer);
        mAggregateBuffer.clear();
    } else {
        reply->setBuffer("buffer", accessUnit);
    }

    reply->post();

    return OK;
}

void NuPlayer::renderBuffer(bool audio, const sp<AMessage> &msg) {
    // ALOGV("renderBuffer %s", audio ? "audio" : "video");

    sp<AMessage> reply;
    CHECK(msg->findMessage("reply", &reply));
#ifdef MTK_AOSP_ENHANCEMENT
    if(skipBufferWhileSeeking(audio,msg,reply))
        return;
#endif
    if ((audio && mFlushingAudio != NONE)
            || (!audio && mFlushingVideo != NONE)) {
        // We're currently attempting to flush the decoder, in order
        // to complete this, the decoder wants all its buffers back,
        // so we don't want any output buffers it sent us (from before
        // we initiated the flush) to be stuck in the renderer's queue.

        ALOGD("we're still flushing the %s decoder, sending its output buffer"
             " right back.", audio ? "audio" : "video");

        reply->post();
        return;
    }

    sp<ABuffer> buffer;
    CHECK(msg->findBuffer("buffer", &buffer));

    int64_t mediaTimeUs;
    CHECK(buffer->meta()->findInt64("timeUs", &mediaTimeUs));

    int64_t &skipUntilMediaTimeUs =
        audio
            ? mSkipRenderingAudioUntilMediaTimeUs
            : mSkipRenderingVideoUntilMediaTimeUs;

    if (skipUntilMediaTimeUs >= 0) {

        if (mediaTimeUs < skipUntilMediaTimeUs) {
#ifdef MTK_AOSP_ENHANCEMENT
            ALOGD("dropping %s buffer at time %.2f s as requested(skipUntil %.2f).",
                 audio ? "audio" : "video",
                 mediaTimeUs / 1E6, skipUntilMediaTimeUs / 1E6);
#else            
            ALOGV("dropping %s buffer at time %lld as requested.",
                 audio ? "audio" : "video",
                 mediaTimeUs);
#endif
            reply->post();
            return;
        }

        skipUntilMediaTimeUs = -1;
    }

#ifdef MTK_AOSP_ENHANCEMENT
    ALOGD("queue buffer %s %.2f", audio ? "audio":"\t\tvideo", mediaTimeUs / 1E6);
#endif
    if (!audio && mCCDecoder->isSelected()) {
        mCCDecoder->display(mediaTimeUs);
    }

    mRenderer->queueBuffer(audio, buffer, reply);
}

void NuPlayer::updateVideoSize(
        const sp<AMessage> &inputFormat,
        const sp<AMessage> &outputFormat) {
    if (inputFormat == NULL) {
        ALOGW("Unknown video size, reporting 0x0!");
        notifyListener(MEDIA_SET_VIDEO_SIZE, 0, 0);
        return;
    }

    int32_t displayWidth, displayHeight;
    int32_t cropLeft, cropTop, cropRight, cropBottom;

    if (outputFormat != NULL) {
        int32_t width, height;
        CHECK(outputFormat->findInt32("width", &width));
        CHECK(outputFormat->findInt32("height", &height));

            int32_t cropLeft, cropTop, cropRight, cropBottom;
            CHECK(outputFormat->findRect(
                        "crop",
                        &cropLeft, &cropTop, &cropRight, &cropBottom));

            displayWidth = cropRight - cropLeft + 1;
            displayHeight = cropBottom - cropTop + 1;
            
            ALOGI("Video output format changed to %d x %d "
                 "(crop: %d x %d @ (%d, %d))",
                 width, height,
                 displayWidth,
                 displayHeight,
                 cropLeft, cropTop);
#ifdef MTK_AOSP_ENHANCEMENT
        int32_t WRatio, HRatio;
        if (!outputFormat->findInt32("width-ratio", &WRatio)) {
            WRatio = 1;
        }
        if (!outputFormat->findInt32("height-ratio", &HRatio)) {
            HRatio = 1;
        }
        displayWidth *= WRatio;
        displayHeight *= HRatio;
#endif
    } else {
        CHECK(inputFormat->findInt32("width", &displayWidth));
        CHECK(inputFormat->findInt32("height", &displayHeight));

        ALOGV("Video input format %d x %d", displayWidth, displayHeight);
    }

    // Take into account sample aspect ratio if necessary:
    int32_t sarWidth, sarHeight;
    if (inputFormat->findInt32("sar-width", &sarWidth)
            && inputFormat->findInt32("sar-height", &sarHeight)) {
        ALOGV("Sample aspect ratio %d : %d", sarWidth, sarHeight);

        displayWidth = (displayWidth * sarWidth) / sarHeight;

        ALOGV("display dimensions %d x %d", displayWidth, displayHeight);
    }

    int32_t rotationDegrees;
    if (!inputFormat->findInt32("rotation-degrees", &rotationDegrees)) {
        rotationDegrees = 0;
    }

    if (rotationDegrees == 90 || rotationDegrees == 270) {
        int32_t tmp = displayWidth;
        displayWidth = displayHeight;
        displayHeight = tmp;
    }

    notifyListener(
            MEDIA_SET_VIDEO_SIZE,
            displayWidth,
            displayHeight);
}

void NuPlayer::notifyListener(int msg, int ext1, int ext2, const Parcel *in) {
    if (mDriver == NULL) {
        return;
    }

    sp<NuPlayerDriver> driver = mDriver.promote();

    if (driver == NULL) {
        return;
    }

#ifdef MTK_AOSP_ENHANCEMENT
    reviseNotifyErrorCode(msg,&ext1,&ext2);
#endif
    driver->notifyListener(msg, ext1, ext2, in);
}

void NuPlayer::flushDecoder(
        bool audio, bool needShutdown, const sp<AMessage> &newFormat) {
    ALOGD("[%s] flushDecoder needShutdown=%d",
          audio ? "audio" : "video", needShutdown);
#ifdef MTK_AOSP_ENHANCEMENT
    ALOGD("++flushDecoder[%s], mFlushing %d, %d (%d, %d),needShutdown:%d", audio?"audio":"video",
            mFlushingAudio, mFlushingVideo,
            mAudioDecoder != NULL, mVideoDecoder != NULL, needShutdown);
#endif
    const sp<Decoder> &decoder = getDecoder(audio);
    if (decoder == NULL) {
        ALOGI("flushDecoder %s without decoder present",
             audio ? "audio" : "video");
        return;
    }

    // Make sure we don't continue to scan sources until we finish flushing.
    ++mScanSourcesGeneration;
    mScanSourcesPending = false;

    decoder->signalFlush(newFormat);
#ifdef MTK_AOSP_ENHANCEMENT
    if (mRenderer != NULL)
#endif
    mRenderer->flush(audio);

    FlushStatus newStatus =
        needShutdown ? FLUSHING_DECODER_SHUTDOWN : FLUSHING_DECODER;

    mFlushComplete[audio][false /* isDecoder */] = false;
    mFlushComplete[audio][true /* isDecoder */] = false;
    if (audio) {
        ALOGE_IF(mFlushingAudio != NONE,
                "audio flushDecoder() is called in state %d", mFlushingAudio);
        mFlushingAudio = newStatus;
    } else {
        ALOGE_IF(mFlushingVideo != NONE,
                "video flushDecoder() is called in state %d", mFlushingVideo);
        mFlushingVideo = newStatus;

        if (mCCDecoder != NULL) {
            mCCDecoder->flush();
        }
    }
#ifdef MTK_AOSP_ENHANCEMENT
    ALOGD("--flushDecoder[%s] end, mFlushing %d, %d,needShutdown:%d", audio?"audio":"video", mFlushingAudio, mFlushingVideo,needShutdown);
#endif    
}

void NuPlayer::updateDecoderFormatWithoutFlush(
        bool audio, const sp<AMessage> &format) {
    ALOGV("[%s] updateDecoderFormatWithoutFlush", audio ? "audio" : "video");

    const sp<Decoder> &decoder = getDecoder(audio);
    if (decoder == NULL) {
        ALOGI("updateDecoderFormatWithoutFlush %s without decoder present",
             audio ? "audio" : "video");
        return;
    }

    decoder->signalUpdateFormat(format);
}

void NuPlayer::queueDecoderShutdown(
        bool audio, bool video, const sp<AMessage> &reply) {
    ALOGI("queueDecoderShutdown audio=%d, video=%d", audio, video);

    mDeferredActions.push_back(
            new ShutdownDecoderAction(audio, video));

    mDeferredActions.push_back(
            new SimpleAction(&NuPlayer::performScanSources));

    mDeferredActions.push_back(new PostMessageAction(reply));

    processDeferredActions();
}

status_t NuPlayer::setVideoScalingMode(int32_t mode) {
    mVideoScalingMode = mode;
    if (mNativeWindow != NULL) {
        status_t ret = native_window_set_scaling_mode(
                mNativeWindow->getNativeWindow().get(), mVideoScalingMode);
        if (ret != OK) {
            ALOGE("Failed to set scaling mode (%d): %s",
                -ret, strerror(-ret));
            return ret;
        }
    }
    return OK;
}

status_t NuPlayer::getTrackInfo(Parcel* reply) const {
    sp<AMessage> msg = new AMessage(kWhatGetTrackInfo, id());
    msg->setPointer("reply", reply);

    sp<AMessage> response;
    status_t err = msg->postAndAwaitResponse(&response);
    return err;
}

status_t NuPlayer::getSelectedTrack(int32_t type, Parcel* reply) const {
    sp<AMessage> msg = new AMessage(kWhatGetSelectedTrack, id());
    msg->setPointer("reply", reply);
    msg->setInt32("type", type);

    sp<AMessage> response;
    status_t err = msg->postAndAwaitResponse(&response);
    if (err == OK && response != NULL) {
        CHECK(response->findInt32("err", &err));
    }
    return err;
}
status_t NuPlayer::selectTrack(size_t trackIndex, bool select) {

    ALOGI("[select track] selectTrack: trackIndex = %d and select=%d", trackIndex, select);
    sp<AMessage> msg = new AMessage(kWhatSelectTrack, id());
    msg->setSize("trackIndex", trackIndex);
    msg->setInt32("select", select);

    sp<AMessage> response;
    status_t err = msg->postAndAwaitResponse(&response);

    if (err != OK) {
        return err;
    }
    if (!response->findInt32("err", &err)) {
        err = OK;
    }
    return err;
}


status_t NuPlayer::getCurrentPosition(int64_t *mediaUs) {
    sp<Renderer> renderer = mRenderer;
    if (renderer == NULL) {
        return NO_INIT;
    }
#ifdef MTK_AOSP_ENHANCEMENT
    status_t err =renderer->getCurrentPosition(mediaUs);
    if (mSource->getRenderedPositionUs() != NuPlayer::Source::NOT_USE_RENDEREDPOSITIONUS) {
        if (OK == err && !isSeeking()) {
            mSource->setRenderedPositionUs(*mediaUs);
        }
    }
    return err;
#else
    return renderer->getCurrentPosition(mediaUs);
#endif
}

void NuPlayer::getStats(int64_t *numFramesTotal, int64_t *numFramesDropped) {
    *numFramesTotal = mNumFramesTotal;
    *numFramesDropped = mNumFramesDropped;
}

sp<MetaData> NuPlayer::getFileMeta() {
    return mSource->getFileFormatMeta();
}

void NuPlayer::schedulePollDuration() {
    sp<AMessage> msg = new AMessage(kWhatPollDuration, id());
    msg->setInt32("generation", mPollDurationGeneration);
    msg->post();
}

void NuPlayer::cancelPollDuration() {
    ++mPollDurationGeneration;
}

void NuPlayer::processDeferredActions() {
    while (!mDeferredActions.empty()) {
        // We won't execute any deferred actions until we're no longer in
        // an intermediate state, i.e. one more more decoders are currently
        // flushing or shutting down.

#ifdef MTK_AOSP_ENHANCEMENT
        // mtk80902: SHUT_DOWN also means not flushing
        if ((mFlushingVideo != NONE && mFlushingVideo != SHUT_DOWN) ||
            (mFlushingAudio != NONE && mFlushingAudio != SHUT_DOWN)  ||
            (mHLSConsumingVideo != HLSConsume_NONE) || (mHLSConsumingAudio != HLSConsume_NONE)) 
#else
        if (mFlushingAudio != NONE || mFlushingVideo != NONE) 
#endif
         {   // We're currently flushing, postpone the reset until that's
            // completed.
#ifdef MTK_AOSP_ENHANCEMENT
            ALOGI("postponing action mFlushingAudio=%d, mFlushingVideo=%d,mHLSConsumingVideo=%d,mHLSConsumingAudio=%d",
                  mFlushingAudio, mFlushingVideo,mHLSConsumingVideo,mHLSConsumingAudio);
#else
            ALOGV("postponing action mFlushingAudio=%d, mFlushingVideo=%d",
                  mFlushingAudio, mFlushingVideo);
#endif

            break;
        }

        sp<Action> action = *mDeferredActions.begin();
        mDeferredActions.erase(mDeferredActions.begin());

        action->execute(this);
    }
}

void NuPlayer::performSeek(int64_t seekTimeUs, bool needNotify) {
    ALOGV("performSeek seekTimeUs=%lld us (%.2f secs), needNotify(%d)",
          seekTimeUs,
          seekTimeUs / 1E6,
          needNotify);

    if (mSource == NULL) {
        // This happens when reset occurs right before the loop mode
        // asynchronously seeks to the start of the stream.
        LOG_ALWAYS_FATAL_IF(mAudioDecoder != NULL || mVideoDecoder != NULL,
                "mSource is NULL and decoders not NULL audio(%p) video(%p)",
                mAudioDecoder.get(), mVideoDecoder.get());
        return;
    }
#ifdef MTK_AOSP_ENHANCEMENT
    performSeek_l(seekTimeUs);
#else
    mSource->seekTo(seekTimeUs);
    ++mTimedTextGeneration;

    if (mDriver != NULL) {
        sp<NuPlayerDriver> driver = mDriver.promote();
        if (driver != NULL) {
            if (needNotify) {
                driver->notifySeekComplete();
            }
        }
    }
#endif
    // everything's flushed, continue playback.
}

void NuPlayer::performDecoderFlush() {
    ALOGV("performDecoderFlush");

    if (mAudioDecoder == NULL && mVideoDecoder == NULL) {
        return;
    }

    mTimeDiscontinuityPending = true;

    if (mAudioDecoder != NULL) {
        flushDecoder(true /* audio */, false /* needShutdown */);
    }

    if (mVideoDecoder != NULL) {
        flushDecoder(false /* audio */, false /* needShutdown */);
    }
}

void NuPlayer::performDecoderShutdown(bool audio, bool video) {
    ALOGV("performDecoderShutdown audio=%d, video=%d", audio, video);

    if ((!audio || mAudioDecoder == NULL)
            && (!video || mVideoDecoder == NULL)) {
        return;
    }

    mTimeDiscontinuityPending = true;

    if (audio && mAudioDecoder != NULL) {
        flushDecoder(true /* audio */, true /* needShutdown */);
    }

    if (video && mVideoDecoder != NULL) {
        flushDecoder(false /* audio */, true /* needShutdown */);
    }
}

void NuPlayer::performReset() {
    ALOGD("performReset");

    CHECK(mAudioDecoder == NULL);
    CHECK(mVideoDecoder == NULL);

    cancelPollDuration();

    ++mScanSourcesGeneration;
    mScanSourcesPending = false;

    if (mRendererLooper != NULL) {
        if (mRenderer != NULL) {
            mRendererLooper->unregisterHandler(mRenderer->id());
        }
        mRendererLooper->stop();
        mRendererLooper.clear();
    }
    mRenderer.clear();
    ++mRendererGeneration;

#ifdef MTK_AOSP_ENHANCEMENT
    mPlayState = STOPPED;
#endif    
    if (mSource != NULL) {
        mSource->stop();

        mSource.clear();
    }

    if (mDriver != NULL) {
        sp<NuPlayerDriver> driver = mDriver.promote();
        if (driver != NULL) {
            driver->notifyResetComplete();
        }
    }

    mStarted = false;
}

void NuPlayer::performScanSources() {
    ALOGD("performScanSources");

    if (!mStarted) {
        return;
    }

    if (mAudioDecoder == NULL || mVideoDecoder == NULL) {
        postScanSources();
    }
}

void NuPlayer::performSetSurface(const sp<NativeWindowWrapper> &wrapper) {
    ALOGD("performSetSurface");

    mNativeWindow = wrapper;

    // XXX - ignore error from setVideoScalingMode for now
#ifdef MTK_AOSP_ENHANCEMENT
    if (mNativeWindow != NULL && mNativeWindow->getNativeWindow().get() != NULL)         
#endif
    setVideoScalingMode(mVideoScalingMode);

    if (mDriver != NULL) {
        sp<NuPlayerDriver> driver = mDriver.promote();
        if (driver != NULL) {
            driver->notifySetSurfaceComplete();
        }
    }
}

void NuPlayer::onSourceNotify(const sp<AMessage> &msg) {
    int32_t what;
    CHECK(msg->findInt32("what", &what));

    switch (what) {
        case Source::kWhatPrepared:
        {
            if (mSource == NULL) {
                // This is a stale notification from a source that was
                // asynchronously preparing when the client called reset().
                // We handled the reset, the source is gone.
                break;
            }

            int32_t err;
            CHECK(msg->findInt32("err", &err));

#ifdef MTK_AOSP_ENHANCEMENT
            onSourcePrepard(err);
#else            
            sp<NuPlayerDriver> driver = mDriver.promote();
            if (driver != NULL) {
                // notify duration first, so that it's definitely set when
                // the app received the "prepare complete" callback.
                int64_t durationUs;
                if (mSource->getDuration(&durationUs) == OK) {
                    driver->notifyDuration(durationUs);
                }
                driver->notifyPrepareCompleted(err);
            }
#endif
            break;
        }

        case Source::kWhatFlagsChanged:
        {
            uint32_t flags;
            CHECK(msg->findInt32("flags", (int32_t *)&flags));

            sp<NuPlayerDriver> driver = mDriver.promote();
            if (driver != NULL) {
                driver->notifyFlagsChanged(flags);
            }

            if ((mSourceFlags & Source::FLAG_DYNAMIC_DURATION)
                    && (!(flags & Source::FLAG_DYNAMIC_DURATION))) {
                cancelPollDuration();
            } else if (!(mSourceFlags & Source::FLAG_DYNAMIC_DURATION)
                    && (flags & Source::FLAG_DYNAMIC_DURATION)
                    && (mAudioDecoder != NULL || mVideoDecoder != NULL)) {
                schedulePollDuration();
            }

            mSourceFlags = flags;
            break;
        }

        case Source::kWhatVideoSizeChanged:
        {
            sp<AMessage> format;
            CHECK(msg->findMessage("format", &format));

            updateVideoSize(format);
            break;
        }

        case Source::kWhatBufferingUpdate:
        {
            int32_t percentage;
            CHECK(msg->findInt32("percentage", &percentage));

            notifyListener(MEDIA_BUFFERING_UPDATE, percentage, 0);
            break;
        }

#ifdef MTK_AOSP_ENHANCEMENT
        case Source::kWhatDurationUpdate:
        {
            int64_t durationUs;
            if (mDataSourceType != SOURCE_Local)
            {
                //only handle local playback
                break;
            }
            CHECK(msg->findInt64("durationUs", &durationUs));
            sp<NuPlayerDriver> driver = mDriver.promote();
            if (driver != NULL) {
                // notify duration
               driver->notifyUpdateDuration(durationUs);
            }
            break;
        }
#endif

        case Source::kWhatBufferingStart:
        {
            notifyListener(MEDIA_INFO, MEDIA_INFO_BUFFERING_START, 0);
            break;
        }
#ifdef MTK_AOSP_ENHANCEMENT
        case Source::kWhatSourceError:
        {
            int32_t err;
            CHECK(msg->findInt32("err", &err));

            notifyListener(MEDIA_ERROR, err, 0);
            ALOGI("Source err");
            break;
        }
#endif
        case Source::kWhatBufferingEnd:
        {
            notifyListener(MEDIA_INFO, MEDIA_INFO_BUFFERING_END, 0);
            break;
        }

        case Source::kWhatSubtitleData:
        {
            sp<ABuffer> buffer;
            CHECK(msg->findBuffer("buffer", &buffer));

            sendSubtitleData(buffer, 0 /* baseIndex */);
            break;
        }

        case Source::kWhatTimedTextData:
        {
            int32_t generation;
            if (msg->findInt32("generation", &generation)
                    && generation != mTimedTextGeneration) {
                break;
            }

            sp<ABuffer> buffer;
            CHECK(msg->findBuffer("buffer", &buffer));

            sp<NuPlayerDriver> driver = mDriver.promote();
            if (driver == NULL) {
                break;
            }

            int posMs;
            int64_t timeUs, posUs;
            driver->getCurrentPosition(&posMs);
            posUs = posMs * 1000;
            CHECK(buffer->meta()->findInt64("timeUs", &timeUs));

            if (posUs < timeUs) {
                if (!msg->findInt32("generation", &generation)) {
                    msg->setInt32("generation", mTimedTextGeneration);
                }
                msg->post(timeUs - posUs);
            } else {
                sendTimedTextData(buffer);
            }
            break;
        }
#ifdef MTK_AOSP_ENHANCEMENT
#ifdef MTK_SUBTITLE_SUPPORT
        case Source::kWhatTimedTextData2:
        {
            ALOGD("func:%s L=%d ",__func__,__LINE__);
            int32_t generation;
            if (msg->findInt32("generation", &generation)
                    && generation != mTimedTextGeneration) {
                break;
            }

            sp<RefBase> obj;
            msg->findObject("subtitle", &obj);

            sp<ParcelEvent> parcelEvent;
            parcelEvent = static_cast<ParcelEvent*>(obj.get());
                
            sp<NuPlayerDriver> driver = mDriver.promote();
            if (driver == NULL) {
                break;
            }

            int posMs;
            int64_t timeUs, posUs;
            driver->getCurrentPosition(&posMs);
            posUs = posMs * 1000;
            CHECK((msg->findInt64("timeUs", &timeUs)));

            if (posUs < timeUs) {
                if (!msg->findInt32("generation", &generation)) {
                    msg->setInt32("generation", mTimedTextGeneration);
                }
                msg->post(timeUs - posUs);
            } else {
                if ((parcelEvent->parcel.dataSize() > 0)) {
                    notifyListener(MEDIA_TIMED_TEXT, 0, 0, &parcelEvent->parcel);
                } else {  // send an empty timed text
                    notifyListener(MEDIA_TIMED_TEXT, 0, 0);
                }
            }
            break;
        }    
#endif
#endif


        case Source::kWhatQueueDecoderShutdown:
        {
            int32_t audio, video;
            CHECK(msg->findInt32("audio", &audio));
            CHECK(msg->findInt32("video", &video));

            sp<AMessage> reply;
            CHECK(msg->findMessage("reply", &reply));

            queueDecoderShutdown(audio, video, reply);
            break;
        }

        case Source::kWhatDrmNoLicense:
        {
            notifyListener(MEDIA_ERROR, MEDIA_ERROR_UNKNOWN, ERROR_DRM_NO_LICENSE);
            break;
        }
#ifdef MTK_AOSP_ENHANCEMENT
        case Source::kWhatBufferNotify: 
        case Source::kWhatSeekDone:
        case NuPlayer::Source::kWhatPlayDone:
        case NuPlayer::Source::kWhatPauseDone:
        case NuPlayer::Source::kWhatPicture:// orange compliance      
             onSourceNotify_l(msg);
        break;
#endif
        default:
            TRESPASS();
    }
}

void NuPlayer::onClosedCaptionNotify(const sp<AMessage> &msg) {
    int32_t what;
    CHECK(msg->findInt32("what", &what));

    switch (what) {
        case NuPlayer::CCDecoder::kWhatClosedCaptionData:
        {
            sp<ABuffer> buffer;
            CHECK(msg->findBuffer("buffer", &buffer));

            size_t inbandTracks = 0;
            if (mSource != NULL) {
                inbandTracks = mSource->getTrackCount();
            }

            sendSubtitleData(buffer, inbandTracks);
            break;
        }

        case NuPlayer::CCDecoder::kWhatTrackAdded:
        {
            notifyListener(MEDIA_INFO, MEDIA_INFO_METADATA_UPDATE, 0);

            break;
        }

        default:
            TRESPASS();
    }


}

void NuPlayer::sendSubtitleData(const sp<ABuffer> &buffer, int32_t baseIndex) {
    int32_t trackIndex;
    int64_t timeUs, durationUs;
    CHECK(buffer->meta()->findInt32("trackIndex", &trackIndex));
    CHECK(buffer->meta()->findInt64("timeUs", &timeUs));
    CHECK(buffer->meta()->findInt64("durationUs", &durationUs));

    Parcel in;
    in.writeInt32(trackIndex + baseIndex);
    in.writeInt64(timeUs);
    in.writeInt64(durationUs);
    in.writeInt32(buffer->size());
    in.writeInt32(buffer->size());
    in.write(buffer->data(), buffer->size());

    notifyListener(MEDIA_SUBTITLE_DATA, 0, 0, &in);
}

void NuPlayer::sendTimedTextData(const sp<ABuffer> &buffer) {
    const void *data;
    size_t size = 0;
    int64_t timeUs;
    int32_t flag = TextDescriptions::LOCAL_DESCRIPTIONS;

    AString mime;
    CHECK(buffer->meta()->findString("mime", &mime));
    CHECK(strcasecmp(mime.c_str(), MEDIA_MIMETYPE_TEXT_3GPP) == 0);

    data = buffer->data();
    size = buffer->size();

    Parcel parcel;
    if (size > 0) {
        CHECK(buffer->meta()->findInt64("timeUs", &timeUs));
        flag |= TextDescriptions::IN_BAND_TEXT_3GPP;
        TextDescriptions::getParcelOfDescriptions(
                (const uint8_t *)data, size, flag, timeUs / 1000, &parcel);
    }

    if ((parcel.dataSize() > 0)) {
        notifyListener(MEDIA_TIMED_TEXT, 0, 0, &parcel);
    } else {  // send an empty timed text
        notifyListener(MEDIA_TIMED_TEXT, 0, 0);
    }
}
////////////////////////////////////////////////////////////////////////////////

sp<AMessage> NuPlayer::Source::getFormat(bool audio) {
    sp<MetaData> meta = getFormatMeta(audio);

    if (meta == NULL) {
        return NULL;
    }

    sp<AMessage> msg = new AMessage;

    if(convertMetaDataToMessage(meta, &msg) == OK) {
        return msg;
    }
    return NULL;
}

void NuPlayer::Source::notifyFlagsChanged(uint32_t flags) {
    sp<AMessage> notify = dupNotify();
    notify->setInt32("what", kWhatFlagsChanged);
    notify->setInt32("flags", flags);
    notify->post();
}

void NuPlayer::Source::notifyVideoSizeChanged(const sp<AMessage> &format) {
    sp<AMessage> notify = dupNotify();
    notify->setInt32("what", kWhatVideoSizeChanged);
    notify->setMessage("format", format);
    notify->post();
}

void NuPlayer::Source::notifyPrepared(status_t err) {
    sp<AMessage> notify = dupNotify();
    notify->setInt32("what", kWhatPrepared);
    notify->setInt32("err", err);
    notify->post();
}

void NuPlayer::Source::onMessageReceived(const sp<AMessage> & /* msg */) {
    TRESPASS();
}
#ifdef MTK_AOSP_ENHANCEMENT
// static
bool NuPlayer::isHLSConsumingState(HLSConsumeStatus state) {
    switch (state) {
        case HLSConsume_AWAITING_DECODER_EOS:
        case HLSConsume_AWAITING_RENDER_EOS:
        case HLSConsume_AWAITING_DECODER_SHUTDOWN:
        case HLSConsume_DONE:
            return true;
        default:
            return false;
    }
}

void NuPlayer::setDataSourceAsync_proCheck(sp<AMessage> &msg, sp<AMessage> &notify) {

    mDataSourceType = SOURCE_Local; 
    sp<RefBase> obj;
       CHECK(msg->findObject("source", &obj));
    sp<Source> source = static_cast<Source *>(obj.get());               

    status_t err = source->initCheck();
    if(err != OK){
        notifyListener(MEDIA_ERROR, MEDIA_ERROR_UNKNOWN, err);
        ALOGW("setDataSource source init check fail err=%d",err);
        source = NULL;
        msg->setObject("source", source);
        msg->setInt32("result", err);
        msg->post();
    }
    return;
}
bool NuPlayer::tyrToChangeDataSourceForLocalSdp() {

    sp<AMessage> format = mSource->getFormat(false);
    
    if(format.get()){
        AString newUrl;
        sp<RefBase> sdp;
        if(format->findString("rtsp-uri", &newUrl) &&
            format->findObject("rtsp-sdp", &sdp)) {
            //is sdp--need re-setDataSource
                mSource.clear();
               sp<AMessage> notify = new AMessage(kWhatSourceNotify, id());
            mSource = new RTSPSource(notify,newUrl.c_str(), NULL, mUIDValid, mUID);
            static_cast<RTSPSource *>(mSource.get())->setSDP(sdp);
            ALOGI("replace local sourceto be RTSPSource");
            return true;
        }
    }   
    return false;
}

void NuPlayer::stop() {
    (new AMessage(kWhatStop, id()))->post();
}

void NuPlayer::onStop() {
    if (mPlayState == PAUSING || mPlayState == PAUSED)
        return;
    if ((mDataSourceType == SOURCE_HttpLive) && (isHLSConsumingState(mHLSConsumingAudio) || isHLSConsumingState(mHLSConsumingVideo))) {
        mStopWhileHLSConsume = true;
        return;
    }
    
    mPlayState = PAUSED;
    CHECK(mRenderer != NULL);
    mRenderer->pause();
}

bool NuPlayer::onPause() {
    bool pausing = false;
    ALOGD("kWhatPause, %d", (int32_t)mPlayState);
    if (mPlayState == STOPPED || mPlayState == PAUSED/* || mPlayState == PLAYSENDING*/) {
        if (!(mDataSourceType == SOURCE_Local || mDataSourceType == SOURCE_Http)){ 
            notifyListener(MEDIA_PAUSE_COMPLETE, INVALID_OPERATION, 0);
        }
        pausing = true;
    }
    if (mPlayState == PAUSING) {
        if (!(mDataSourceType == SOURCE_Local || mDataSourceType == SOURCE_Http)) { 
            notifyListener(MEDIA_PAUSE_COMPLETE, ALREADY_EXISTS, 0);
        }
        pausing = true;
    }
            
    if ((mDataSourceType == SOURCE_HttpLive) && (isHLSConsumingState(mHLSConsumingAudio) || isHLSConsumingState(mHLSConsumingVideo))) {
        mPauseWhileHLSConsume = true;
        pausing = true;
        mPlayState = PAUSING;
        ALOGI("pause while hls consume");
    }
    return pausing;
}

bool NuPlayer::onResume() {
    bool resuming = false;
    ALOGD("kWhatResume, %d", (int32_t)mPlayState);
    
    if ((mDataSourceType == SOURCE_HttpLive) && mPauseWhileHLSConsume) {
        mPauseWhileHLSConsume = false;
        mPlayState = PLAYING;
        notifyListener(MEDIA_PAUSE_COMPLETE, 0, 0);
        notifyListener(MEDIA_PLAY_COMPLETE, 0, 0);
        resuming = true;
    }
    if (mPlayState == PLAYING/* || mPlayState == PAUSING*/) {
        if (!(mDataSourceType == SOURCE_Local || mDataSourceType == SOURCE_Http)) { 
            notifyListener(MEDIA_PLAY_COMPLETE, INVALID_OPERATION, 0);
        }
        resuming = true;
    }
    if (mPlayState == PLAYSENDING) {
        if (!(mDataSourceType == SOURCE_Local || mDataSourceType == SOURCE_Http)) { 
            notifyListener(MEDIA_PLAY_COMPLETE, ALREADY_EXISTS, 0);
        }
        resuming = true;
    }
   
    // mtk80902: ALPS00451531 - bad server. response error
    // if received PLAY without range when playing
    // if play before seek complete then dont send PLAY cmd
    // just set PLAYING state
    if (isSeeking()) {
        ALOGI("onResume notify play complete");
        if (!(mDataSourceType == SOURCE_Local || mDataSourceType == SOURCE_Http)) { 
            notifyListener(MEDIA_PLAY_COMPLETE, OK, 0);
        }
        mPlayState = PLAYING;
        resuming = true;
    }
    return resuming;
}

void NuPlayer::handleForACodecInfoDiscontinuity(bool audio,int32_t err) {
    ALOGD("got %s decoder discontinuity, (consume status: %d, %d)",
            audio ? "audio" : "video", mHLSConsumingAudio, mHLSConsumingVideo);
    if (audio) {
        if (mHLSConsumingAudio == HLSConsume_AWAITING_DECODER_EOS) {
            mHLSConsumingAudio = HLSConsume_AWAITING_RENDER_EOS;
        }
    } else {
        if (mHLSConsumingVideo == HLSConsume_AWAITING_DECODER_EOS) {
            mHLSConsumingVideo = HLSConsume_AWAITING_RENDER_EOS;
        }
    }
}

bool NuPlayer::handleForACodecShutdownCompleted(bool audio) {
    ALOGD("got %s decoder shutdown completed, (consume status: %d, %d)",
            audio ? "audio" : "video", mHLSConsumingAudio, mHLSConsumingVideo);
    bool bConsuming = false;
    if (audio && mHLSConsumingAudio == HLSConsume_AWAITING_DECODER_SHUTDOWN) {
        bConsuming = true;
        mHLSConsumingAudio = HLSConsume_DONE;
        mAudioDecoder.clear();
    }
    if (!audio && mHLSConsumingVideo == HLSConsume_AWAITING_DECODER_SHUTDOWN) {
        bConsuming = true;
        mHLSConsumingVideo = HLSConsume_DONE;
        mVideoDecoder.clear();
    }
    if (bConsuming) {
        finishHLSConsumeIfPossible();
    }
    
    return bConsuming;
}

void NuPlayer::handleForACodecError(bool audio,const sp<AMessage> &codecRequest) {
   
    if (!(IsFlushingState(audio ? mFlushingAudio : mFlushingVideo)) && !(isHLSConsumingState(audio ? mHLSConsumingAudio : mHLSConsumingVideo))) {
        ALOGE("Received error from %s decoder.",audio ? "audio" : "video");
            int32_t err;                        
            CHECK(codecRequest->findInt32("err", &err));
         notifyListener(MEDIA_ERROR, MEDIA_ERROR_UNKNOWN, err);
    } else {
        ALOGD("Ignore error from %s decoder when flushing", audio ? "audio" : "video");
    }
}

void NuPlayer::handleForACodecComponentAllocated(const sp<AMessage> &codecRequest) {
    int32_t quirks;
    CHECK(codecRequest->findInt32("quirks", &quirks));
    // mtk80902: must tell APacketSource quirks settings thru this way..
    ALOGD("Component Alloc: quirks (%u)", quirks);
    sp<MetaData> params = new MetaData;
    if (quirks & OMXCodec::kWantsNALFragments) {
        params->setInt32(kKeyWantsNALFragments, true);
        if (mSource != NULL)
            mSource->setParams(params);
    }
}

bool NuPlayer::handleForRenderEos(int32_t finalResult,bool audio) {
    bool consuming = false;
    if (mDataSourceType == SOURCE_HttpLive) {
        if (((audio && isHLSConsumingState(mHLSConsumingAudio)) || (!audio && isHLSConsumingState(mHLSConsumingVideo)))) {
            if (audio) {
                if (mAudioDecoder != NULL) {
                    if(mHLSConsumingAudio == HLSConsume_AWAITING_RENDER_EOS) {
                        mHLSConsumingAudio = HLSConsume_AWAITING_DECODER_SHUTDOWN;
                        mAudioDecoder->initiateShutdown();
                    }
                } else {
                    mHLSConsumingAudio = HLSConsume_DONE;
                }
            } else {
                if (mVideoDecoder != NULL) {
                    if(mHLSConsumingVideo == HLSConsume_AWAITING_RENDER_EOS) {
                        mHLSConsumingVideo = HLSConsume_AWAITING_DECODER_SHUTDOWN;
                        mVideoDecoder->initiateShutdown();
                    } 
                } else {
                    mHLSConsumingVideo = HLSConsume_DONE;
                }
            }
            ALOGD("finish consume %s render", audio ? "audio" : "video");
            finishHLSConsumeIfPossible();
            consuming = true;
        }
    }
    return consuming;
}

void NuPlayer::handleForRenderError1(int32_t finalResult,int32_t audio) {

#ifdef MTK_AOSP_ENHANCEMENT
    if (mSource != NULL) {
        mSource->stopTrack(audio);
    }
#endif
    
    if (finalResult == ERROR_BAD_FRAME_SIZE) {
        return;
    }
    // mtk80902: ALPS00436989
    if (audio) {
        notifyListener(MEDIA_INFO, MEDIA_INFO_HAS_UNSUPPORT_AUDIO, finalResult);
    } else {
        notifyListener(MEDIA_INFO, MEDIA_INFO_HAS_UNSUPPORT_VIDEO, finalResult);
    }
}

bool NuPlayer::handleForRenderError2(int32_t finalResult,int32_t audio) {
    bool err = false;
    // mtk80902: for ALPS00557536 - dont trigger AP's retry if it's codec's error?
    int64_t RTSPRenderedPositionUs = mSource->getRenderedPositionUs();
    if (RTSPRenderedPositionUs != NuPlayer::Source::NOT_USE_RENDEREDPOSITIONUS
        && (finalResult == ERROR_END_OF_STREAM || finalResult == ERROR_CANNOT_CONNECT)) {
        //mtk08585: need to update RTSPRenderedPositionUs ourselves before notify MEDIA_ERROR_CANNOT_CONNECT_TO_SERVER
        //not only when NuPlayer::getCurrentPosition() called by AP
        if (mRenderer != NULL) {
            int64_t mediaUs = 0;
            status_t getPosErr = mRenderer->getCurrentPosition(&mediaUs);
            if (OK == getPosErr && !isSeeking()) {
                mSource->setRenderedPositionUs(mediaUs);
            }
        }

        // get new value
        RTSPRenderedPositionUs = mSource->getRenderedPositionUs();

        int64_t durationUs;
        if (mSource != NULL && mSource->getDuration(&durationUs) == OK &&
            (durationUs == 0 || durationUs - RTSPRenderedPositionUs > kRTSPEarlyEndTimeUs)) {
            ALOGE("RTSP play end before duration %lld, current pos %lld", durationUs, RTSPRenderedPositionUs);
            notifyListener(MEDIA_ERROR, MEDIA_ERROR_CANNOT_CONNECT_TO_SERVER, 0);
            err = true;
        }
    }
    return err;
}

#ifdef MTK_CLEARMOTION_SUPPORT
void NuPlayer::enableClearMotion(int32_t enable) {
    mEnClearMotion = enable;
}
#endif
bool NuPlayer::flushAfterSeekIfNecessary() {
    bool bWaitingFlush = false;
    bool bNeedShutdown = false;
    if(mDataSourceType == SOURCE_HttpLive) {
      if(isHLSConsumingState(mHLSConsumingAudio) || isHLSConsumingState(mHLSConsumingVideo)) {
          //terminate consume while seeking
          mHLSConsumingVideo = HLSConsume_NONE;
          mHLSConsumingAudio = HLSConsume_NONE;
      }
    }
    
    if (mAudioDecoder == NULL) {
            ALOGD("audio is not there, reset the flush flag");
            mFlushingAudio = NONE;
        } else if ( mFlushingAudio == NONE)  {
            flushDecoder(true /* audio */, bNeedShutdown);
            bWaitingFlush = true;
        } else {
            //TODO: if there is many discontinuity, flush still is needed
            ALOGD("audio is already being flushed");
    }

    if (mVideoDecoder == NULL) {
            ALOGD("video is not there, reset the flush flag");
            mFlushingVideo = NONE;
        } else if (mFlushingVideo == NONE ) {
            flushDecoder(false /* video */, bNeedShutdown);
            bWaitingFlush = true;
        } else {
            //TODO: if there is many discontinuity, flush still is needed
            ALOGD("video is already being flushed");
    }

    return bWaitingFlush;
}

void NuPlayer::finishSeek() {
#ifdef MTK_AOSP_ENHANCEMENT
        if (isRTSPSource()) {
            int64_t durationUs;
            mSource->getDuration(&durationUs);

            if (mSkipRenderingAudioUntilMediaTimeUs != -1 && mSkipRenderingVideoUntilMediaTimeUs != -1) {
                mSkipRenderingAudioUntilMediaTimeUs = -1;
                mSkipRenderingVideoUntilMediaTimeUs = -1;
            }

            mSkipRenderingAudioUntilMediaTimeUs = mSeekTimeUs;
            mSkipRenderingVideoUntilMediaTimeUs = mSeekTimeUs;
        }
#endif
    if (mRenderer != NULL) {  //resume render
        if (mPlayState == PLAYING)
            mRenderer->resume();
    }
    if (mDriver != NULL) {
        sp<NuPlayerDriver> driver = mDriver.promote();
        if (driver != NULL) {
            driver->notifySeekComplete();
            ALOGI("seek(%.2f)  completed", mSeekTimeUs / 1E6);
        }
    }
    mSeekTimeUs = -1;    
}

sp<MetaData> NuPlayer::getMetaData() const {
    return mSource->getMetaData();
}

#ifdef MTK_SLOW_MOTION_VIDEO_SUPPORT
sp<MetaData> NuPlayer::getFormatMeta(bool audio)const {
    if(mSource != NULL){
        return mSource->getFormatMeta(audio);
    }else{
        return NULL;
    }
}
#endif

bool NuPlayer::onScanSources() {
    ALOGE("onScanSources");
    bool needScanAgain = false;
    bool hadAnySourcesBefore =
        (mAudioDecoder != NULL) || (mVideoDecoder != NULL);

    // mtk80902: ALPS01413054
    // get format first, then init decoder
    if (mDataSourceType == SOURCE_HttpLive) {
        needScanAgain = (mSource->allTracksPresent() != OK);
        if(needScanAgain)
            return true;
    }    
    // mtk80902: for rtsp, if instantiateDecoder return EWOULDBLK
    // it means no track. no need to try again.
    status_t videoFmt = OK, audioFmt = OK;
    if (mNativeWindow != NULL) {
#ifdef MTK_CLEARMOTION_SUPPORT
        if (mEnClearMotion) {
            sp<ANativeWindow> window = mNativeWindow->getNativeWindow();    
            if (window != NULL) {
                window->setSwapInterval(window.get(), 1);        
            }
        }       
#endif
        videoFmt = instantiateDecoder(false, &mVideoDecoder);
    }

    if (mAudioSink != NULL) {
        audioFmt = instantiateDecoder(true, &mAudioDecoder);
    }

    if (!hadAnySourcesBefore
            && (mAudioDecoder != NULL || mVideoDecoder != NULL)) {
        // This is the first time we've found anything playable.

        if (mSourceFlags & Source::FLAG_DYNAMIC_DURATION) {
            schedulePollDuration();
        }
    }

    if (isRTSPSource()) {
        ALOGD("audio sink: %p, audio decoder: %p, native window: %p, video decoder: %p",
        mAudioSink.get(), mAudioDecoder.get(), mNativeWindow.get(), mVideoDecoder.get());
        needScanAgain = ((mAudioSink != NULL) && (mAudioDecoder == NULL))
            || ((mNativeWindow != NULL) && (mVideoDecoder == NULL));
    } else if (mDataSourceType == SOURCE_HttpLive) {
        ALOGD("audio sink: %p, audio decoder: %p, native window: %p, video decoder: %p",
        mAudioSink.get(), mAudioDecoder.get(), mNativeWindow.get(), mVideoDecoder.get());
        /*
        needScanAgain = ((mAudioSink != NULL) && (mAudioDecoder == NULL))
                      || ((mNativeWindow != NULL) && (mVideoDecoder == NULL));
        */
    } else {
        ALOGD("Local audio sink: %p, audio decoder: %p, native window: %p, video decoder: %p",
                mAudioSink.get(), mAudioDecoder.get(), mNativeWindow.get(), mVideoDecoder.get());
        needScanAgain = ((mAudioSink != NULL) && (mAudioDecoder == NULL))
            || ((mNativeWindow != NULL) && (mVideoDecoder == NULL));
    }
    
    status_t err;
    if ((err = mSource->feedMoreTSData()) != OK) {
        if (mAudioDecoder == NULL && mVideoDecoder == NULL) {
            // We're not currently decoding anything (no audio or
            // video tracks found) and we just ran out of input data.

            if (err == ERROR_END_OF_STREAM) {
                notifyListener(MEDIA_PLAYBACK_COMPLETE, 0, 0);
            } else {
                notifyListener(MEDIA_ERROR, MEDIA_ERROR_UNKNOWN, err);
            }
        }
        return false;
    } 

    return needScanAgain;
}

void NuPlayer::scanSource_l(const sp<AMessage> &msg) {
    bool needScanAgain = onScanSources();
    //TODO: to handle audio only file, finisPrepare should be sent
    if (needScanAgain) {     //scanning source is not completed, continue
        msg->post(100000ll);
        mScanSourcesPending = true;
    } else {
        if(SOURCE_HttpLive == mDataSourceType) {//decoder may not shutdown after audio/video->audio only stream,can RTSP use the format way?!
            sp<AMessage> audioFormat = mSource->getFormat(true);
            sp<AMessage> videoFormat = mSource->getFormat(false);
            mAudioOnly = videoFormat == NULL;
            mVideoOnly = audioFormat == NULL;
            ALOGD("scanning sources done! Audio only=%d, Video only=%d",mAudioOnly,mVideoOnly);
            if (mAudioOnly) {
                notifyListener(MEDIA_SET_VIDEO_SIZE, 0,0);
            }
            
            if ((videoFormat == NULL) && (audioFormat == NULL)) {
                ALOGD("notify error to AP when there is no audio and video!");
                notifyListener(MEDIA_ERROR, MEDIA_ERROR_UNKNOWN, 0);
            }
        } else {
            if (mVideoDecoder == NULL) {
                notifyListener(MEDIA_SET_VIDEO_SIZE, 0,0);
            }
            
            if ((mVideoDecoder == NULL) && (mAudioDecoder == NULL)) {
                ALOGD("notify error to AP when there is no audio and video!");
                notifyListener(MEDIA_ERROR, MEDIA_ERROR_UNKNOWN, 0);
            }
        }
    }
}

void NuPlayer::finishPrepare(int err /*= OK*/) {
    mPrepare = (err == OK)?PREPARED:UNPREPARED;
    if (mDriver == NULL)
        return;
    sp<NuPlayerDriver> driver = mDriver.promote();
    if (driver != NULL) {
        int64_t durationUs;
        if (mSource != NULL && mSource->getDuration(&durationUs) == OK) {
            driver->notifyDuration(durationUs);
        }        
        driver->notifyPrepareCompleted(err);
        //if (isRTSPSource() && err == OK) {
        //    notifyListener(MEDIA_INFO, MEDIA_INFO_CHECK_LIVE_STREAMING_COMPLETE, 0);
        //}
        ALOGD("complete prepare %s", (err == OK)?"success":"fail");
    }
}

void NuPlayer::finishHLSConsumeIfPossible() {
    ALOGD("finishHLSConsumeIfPossible (%d, %d)",mAudioDecoder == NULL, mVideoDecoder == NULL);

    if ((mAudioDecoder != NULL) && (mHLSConsumingAudio != HLSConsume_DONE && mHLSConsumingAudio != HLSConsume_NONE)) {
        return;
    }
    if ((mVideoDecoder != NULL)&& (mHLSConsumingVideo != HLSConsume_DONE && mHLSConsumingVideo != HLSConsume_NONE)) {
        return;
    }

    if (mTimeDiscontinuityPending) {
        if (mRenderer != NULL) {
            mRenderer->signalTimeDiscontinuity();
        }
        mTimeDiscontinuityPending = false;
    }

    mHLSConsumingVideo = HLSConsume_NONE;
    mHLSConsumingAudio = HLSConsume_NONE;

    ALOGD("both audio and video are consumed now. (%d, %d)", mAudioDecoder != NULL,mVideoDecoder != NULL);

    if (mPauseWhileHLSConsume || mStopWhileHLSConsume) {
      ALOGD("Resume pause/stop after hlsConsumedecoder");
      CHECK(mRenderer != NULL);
      mRenderer->pause();

      if (mPauseWhileHLSConsume) {
         mSource->pause();
         mPauseWhileHLSConsume = false;
      }
      if (mStopWhileHLSConsume) {
         mStopWhileHLSConsume = false;
      }
    }

    processDeferredActions();
}

void NuPlayer::finishFlushIfPossible_l() {
    Mutex::Autolock autoLock(mLock);
    if (isSeeking()) {
           finishSeek();
    }
}

void NuPlayer::setVideoProperties(sp<AMessage> &format) {
#ifdef MTK_CLEARMOTION_SUPPORT
    format->setInt32("use-clearmotion-mode", mEnClearMotion);
    ALOGD("mEnClearMotion(%d).", mEnClearMotion);
#endif
}

bool NuPlayer::skipBufferWhileSeeking(bool audio,const sp<AMessage> &msg,sp<AMessage> &reply) {
    Mutex::Autolock autoLock(mLock);
    bool seeking = isSeeking();
    if (seeking) {
        sp<ABuffer> buffer0;
        CHECK(msg->findBuffer("buffer", &buffer0));
        int64_t mediaTimeUs;
        CHECK(buffer0->meta()->findInt64("timeUs", &mediaTimeUs));
        ALOGD("seeking, %s buffer (%.2f) drop", audio ? "audio" : "video", mediaTimeUs / 1E6);
        reply->post();
    }
    return seeking;
}

void NuPlayer::reviseNotifyErrorCode(int msg,int *ext1,int *ext2) {
    if ((mDataSourceType == SOURCE_Http) && (msg == MEDIA_ERROR || msg == MEDIA_PLAY_COMPLETE ||
        *ext1 == MEDIA_INFO_HAS_UNSUPPORT_AUDIO || *ext1 == MEDIA_INFO_HAS_UNSUPPORT_VIDEO)) {
        status_t cache_stat = mSource->getFinalStatus();
        bool bCacheSuccess = (cache_stat == OK || cache_stat == ERROR_END_OF_STREAM);

        if (!bCacheSuccess) {
            ALOGI(" http error");
            if (cache_stat == -ECANCELED) {
                ALOGD("this error triggered by user's stopping, would not report");
                return;
            } else if (cache_stat == ERROR_FORBIDDEN) {
                *ext1 = MEDIA_ERROR_INVALID_CONNECTION;//httpstatus = 403
            } else if (cache_stat == ERROR_POOR_INTERLACE) {
                *ext1 = MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK;
            } else {
                *ext1 = MEDIA_ERROR_CANNOT_CONNECT_TO_SERVER;
            }
            *ext2 = cache_stat;
            ALOGE("report 'cannot connect' to app, cache_stat = %d", cache_stat);
            if (MEDIA_PLAY_COMPLETE == msg) {
                ALOGD("Http Error and end of stream");
                msg = MEDIA_ERROR;
            }
        }
    }
 
    //try to report a more meaningful error
    if (msg == MEDIA_ERROR && *ext1 == MEDIA_ERROR_UNKNOWN) {
        switch(*ext2) {
            case ERROR_MALFORMED:
                *ext1 = MEDIA_ERROR_BAD_FILE;
                break;
            case ERROR_CANNOT_CONNECT:
                *ext1 = MEDIA_ERROR_CANNOT_CONNECT_TO_SERVER;
                break;
            case ERROR_UNSUPPORTED:
                *ext1 = MEDIA_ERROR_TYPE_NOT_SUPPORTED;
                break;
            case ERROR_FORBIDDEN:
                *ext1 = MEDIA_ERROR_INVALID_CONNECTION;
                break;
            default:
                break;
        }
    }
}

void NuPlayer::hlsConsumeDecoder(bool audio) {
    ALOGD("++hlsConsumeDecoder[%s], mConsuming (%d, %d)", audio?"audio":"video",
            mHLSConsumingAudio, mHLSConsumingVideo);
    // Make sure we don't continue to scan sources until we finish flushing.
    ++mScanSourcesGeneration;
    mScanSourcesPending = false;

    if (audio) {
        CHECK(mHLSConsumingAudio == HLSConsume_NONE);
        mHLSConsumingAudio = HLSConsume_AWAITING_DECODER_EOS;
    } else {
        CHECK(mHLSConsumingVideo == HLSConsume_NONE);
        mHLSConsumingVideo = HLSConsume_AWAITING_DECODER_EOS;
    }

    ALOGD("--hlsConsumeDecoder[%s], mConsuming (%d, %d)", audio?"audio":"video",
            mHLSConsumingAudio, mHLSConsumingVideo);
}

void NuPlayer::performSeek_l(int64_t seekTimeUs) {
    
    CHECK(seekTimeUs != -1);
    Mutex::Autolock autoLock(mLock); 
    mSeekTimeUs = seekTimeUs;   //seek complete later
    if (mSource->getRenderedPositionUs() != NuPlayer::Source::NOT_USE_RENDEREDPOSITIONUS) {
        mSource->setRenderedPositionUs(seekTimeUs);   // mtk80902: for kRTSPEarlyEndTimeUs vs seek to end
    }
    if (mRenderer != NULL) {
        if (mPlayState == PLAYING) {
            mRenderer->pause();
        }
    }
    
#ifdef MTK_AOSP_ENHANCEMENT
    mAudioEOS = false;
    mVideoEOS = false;
    ALOGI("reset EOS flag");
    if (mVideoDecoder != NULL) {
        sp<AMessage> msg = new AMessage;
        msg->setInt64("seekTimeUs", seekTimeUs);
        
        mVideoDecoder->setParameters(msg);
    }
#endif
    
    status_t err = mSource->seekTo(seekTimeUs);
    if (err == -EWOULDBLOCK) {  // finish seek when receive Source::kWhatSeekDone
        mTimeDiscontinuityPending = true;
        ALOGD("seek async, waiting Source seek done");
    } else if (err == OK) {
        if (flushAfterSeekIfNecessary()) {
            mTimeDiscontinuityPending = true;
        } else {
            finishSeek();
        }
    } else {
        ALOGE("seek error %d", (int)err);
        // add notify seek complete
        finishSeek();
    }
}

void NuPlayer::onSourcePrepard(int32_t err) {
    //if file is rtsp local sdp file, check file uses GenericSource, here check source, need to change to RTSPSource
    if(mDataSourceType == SOURCE_Local && tyrToChangeDataSourceForLocalSdp()){
        mPrepare = UNPREPARED;
        ALOGI("to do prepare again");
        prepareAsync();
        return;
   }


    if (mPrepare == PREPARED) //TODO: this would would happen when MyHandler disconnect
        return; 
    if (err != OK) {
        finishPrepare(err);
        return;
    } else if (mSource == NULL) {  // ALPS00779817
        ALOGW("prepare error: source is not ready");
        finishPrepare(UNKNOWN_ERROR);
        return;
    } 
    // if data source is streamingsource or local, the scan will be started in kWhatStart
    finishPrepare();
}

void NuPlayer::onSourceNotify_l(const sp<AMessage> &msg) {
    int32_t what;
    CHECK(msg->findInt32("what", &what));
    if(what == Source::kWhatBufferNotify) {
        int32_t rate;
        CHECK(msg->findInt32("bufRate", &rate));
      if(rate % 10 == 0){
         ALOGD("mFlags %d; mPlayState %d, buffering rate %d",mFlags, mPlayState, rate);
      }
        if (mPlayState == PLAYING ) {
            notifyListener(MEDIA_BUFFERING_UPDATE, rate, 0);
        }
    } 
    else if(what == Source::kWhatSeekDone) {
        if (!flushAfterSeekIfNecessary() && isSeeking()) {
            // restore default
            // result = ok means this seek has discontinuty
            // and should be completed by flush, otherwise
            // it's interrupted before send play, and should
            // be done here.
            finishSeek();
        } 
    }
    else if( what == NuPlayer::Source::kWhatPlayDone) {
        int32_t ret;
        CHECK(msg->findInt32("result", &ret));
        ALOGI("play done with result %d.", ret);
        notifyListener(MEDIA_PLAY_COMPLETE, ret, 0);
        if (ret == OK)
            mPlayState = PLAYING;
        // mtk80902: ALPS00439792
        // special case: pause -> seek -> resume ->
        //  seek complete -> resume complete
        // in this case render cant resume in SeekDone
        if (mRenderer != NULL)
            mRenderer->resume();
    }
    else if(what == NuPlayer::Source::kWhatPauseDone) {
        int32_t ret;
        CHECK(msg->findInt32("result", &ret));
        notifyListener(MEDIA_PAUSE_COMPLETE, ret, 0);
        // ALPS00567579 - an extra pause done?
        if (mPlayState != PAUSING) {
            ALOGW("what's up? an extra pause done?");
            return;
        }
        if (ret == OK){
              mPlayState = PAUSED;
     }
    }
    else if(what == NuPlayer::Source::kWhatPicture) {
        // audio-only stream containing picture for display
        ALOGI("Notify picture existence");
        notifyListener(MEDIA_INFO, MEDIA_INFO_METADATA_UPDATE, 0);
    }
}

#ifdef MTK_DRM_APP
void NuPlayer::getDRMClientProc(const Parcel *request) {
    if ((mDataSourceType == SOURCE_Local || mDataSourceType == SOURCE_Http) && mSource != NULL) {
        (static_cast<GenericSource *>(mSource.get()))->getDRMClientProc(request);
    }
}
#endif
#ifdef MTK_SLOW_MOTION_VIDEO_SUPPORT
status_t NuPlayer::setsmspeed(int32_t speed){
    mslowmotion_speed = speed;
    if(mVideoDecoder != NULL){
        sp<AMessage> msg = new AMessage;
        msg->setInt32("slowmotion-speed", speed);
        mVideoDecoder->setParameters(msg);      
    }
    if(mRenderer != NULL){
        return mRenderer->setsmspeed(speed);
    }else{
        ALOGW("mRenderer = NULL");
        return NO_INIT;
    }
}
status_t NuPlayer::setslowmotionsection(int64_t slowmotion_start,int64_t slowmotion_end){
    mslowmotion_start = slowmotion_start;
    mslowmotion_end = slowmotion_end;
    if(mVideoDecoder != NULL){
        sp<AMessage> msg = new AMessage;
        msg->setInt64("slowmotion-start", slowmotion_start);
        msg->setInt64("slowmotion-end", slowmotion_end);
        msg->setInt32("slowmotion-speed", mslowmotion_speed);   
        return mVideoDecoder->setParameters(msg);
    }else {
        ALOGW("mVideoDecoder = NULL");
        return NO_INIT;
    }
}
#endif
// static
bool NuPlayer::IsFlushingState(FlushStatus state) {
    switch (state) {
        case FLUSHING_DECODER:
            return true;
            
        case FLUSHING_DECODER_SHUTDOWN:
        case SHUTTING_DOWN_DECODER:
            return true;

        default:
            return false;
    }
}
#endif

#ifdef MTK_AOSP_ENHANCEMENT
bool NuPlayer::isSeeking() {
    return (mSeekTimeUs != -1);
}

bool NuPlayer::isRTSPSource() {
    return NuPlayer::Source::SOURCE_Rtsp == mSource->getDataSourceType();
}

#endif

}  // namespace android
