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
#define LOG_TAG "NuPlayerDriver"
#include <inttypes.h>
#include <utils/Log.h>

#include "NuPlayerDriver.h"

#include "NuPlayer.h"
#include "NuPlayerSource.h"

#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/ALooper.h>
#include <media/stagefright/foundation/AUtils.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/Utils.h>

#ifdef MTK_AOSP_ENHANCEMENT
// for ALPS00595180
#include <media/stagefright/MediaErrors.h>
#endif
namespace android {

NuPlayerDriver::NuPlayerDriver()
    : mState(STATE_IDLE),
      mIsAsyncPrepare(false),
      mAsyncResult(UNKNOWN_ERROR),
      mSetSurfaceInProgress(false),
      mDurationUs(-1),
      mPositionUs(-1),
      mSeekInProgress(false),
      mLooper(new ALooper),
      mPlayerFlags(0),
      mAtEOS(false),
      mLooping(false),
      mAutoLoop(false),
      mStartupSeekTimeUs(-1) {
		ALOGV("NuPlayerDriver(%p)", this);
    mLooper->setName("NuPlayerDriver Looper");
    mLooper->start(
            false, /* runOnCallingThread */
            true,  /* canCallJava */
            PRIORITY_AUDIO);

    mPlayer = new NuPlayer;
    mLooper->registerHandler(mPlayer);

    mPlayer->setDriver(this);
    
#ifdef MTK_AOSP_ENHANCEMENT
      mSeekTimeUs = -1;
#ifdef MTK_SLOW_MOTION_VIDEO_SUPPORT
	  mSpeed      = 1;
#endif
#endif
}

NuPlayerDriver::~NuPlayerDriver() {
    ALOGV("~NuPlayerDriver(%p)", this);
    mLooper->stop();
#ifdef MTK_AOSP_ENHANCEMENT
    mLooper->unregisterHandler(mPlayer->id());
#endif
}

status_t NuPlayerDriver::initCheck() {
    return OK;
}

status_t NuPlayerDriver::setUID(uid_t uid) {
    mPlayer->setUID(uid);

    return OK;
}

status_t NuPlayerDriver::setDataSource(
        const sp<IMediaHTTPService> &httpService,
        const char *url,
        const KeyedVector<String8, String8> *headers) {
    ALOGV("setDataSource(%p) url(%s)", this, uriDebugString(url, false).c_str());
    Mutex::Autolock autoLock(mLock);

    if (mState != STATE_IDLE) {
        return INVALID_OPERATION;
    }

    mState = STATE_SET_DATASOURCE_PENDING;

    mPlayer->setDataSourceAsync(httpService, url, headers);

    while (mState == STATE_SET_DATASOURCE_PENDING) {
        mCondition.wait(mLock);
    }

    return mAsyncResult;
}

status_t NuPlayerDriver::setDataSource(int fd, int64_t offset, int64_t length) {
    ALOGV("setDataSource(%p) file(%d)", this, fd);
    Mutex::Autolock autoLock(mLock);

    if (mState != STATE_IDLE) {
        return INVALID_OPERATION;
    }

    mState = STATE_SET_DATASOURCE_PENDING;

    mPlayer->setDataSourceAsync(fd, offset, length);

    while (mState == STATE_SET_DATASOURCE_PENDING) {
        mCondition.wait(mLock);
    }

    return mAsyncResult;
}

status_t NuPlayerDriver::setDataSource(const sp<IStreamSource> &source) {
    ALOGV("setDataSource(%p) stream source", this);
    Mutex::Autolock autoLock(mLock);

    if (mState != STATE_IDLE) {
        return INVALID_OPERATION;
    }

    mState = STATE_SET_DATASOURCE_PENDING;

    mPlayer->setDataSourceAsync(source);

    while (mState == STATE_SET_DATASOURCE_PENDING) {
        mCondition.wait(mLock);
    }

    return mAsyncResult;
}

status_t NuPlayerDriver::setVideoSurfaceTexture(
        const sp<IGraphicBufferProducer> &bufferProducer) {
    ALOGV("setVideoSurfaceTexture(%p)", this);
    Mutex::Autolock autoLock(mLock);

    if (mSetSurfaceInProgress) {
        return INVALID_OPERATION;
    }

    switch (mState) {
        case STATE_SET_DATASOURCE_PENDING:
        case STATE_RESET_IN_PROGRESS:
            return INVALID_OPERATION;

        default:
            break;
    }

    mSetSurfaceInProgress = true;

    mPlayer->setVideoSurfaceTextureAsync(bufferProducer);

    while (mSetSurfaceInProgress) {
        mCondition.wait(mLock);
    }

    return OK;
}

status_t NuPlayerDriver::prepare() {
    ALOGV("prepare(%p)", this);
    Mutex::Autolock autoLock(mLock);
    return prepare_l();
}

status_t NuPlayerDriver::prepare_l() {
    switch (mState) {
        case STATE_UNPREPARED:
            mState = STATE_PREPARING;

            // Make sure we're not posting any notifications, success or
            // failure information is only communicated through our result
            // code.
            mIsAsyncPrepare = false;
            mPlayer->prepareAsync();
            while (mState == STATE_PREPARING) {
                mCondition.wait(mLock);
            }
            return (mState == STATE_PREPARED) ? OK : UNKNOWN_ERROR;
        case STATE_STOPPED:
            // this is really just paused. handle as seek to start
            mAtEOS = false;
            mState = STATE_STOPPED_AND_PREPARING;
            mIsAsyncPrepare = false;
            mPlayer->seekToAsync(0, true /* needNotify */);
            while (mState == STATE_STOPPED_AND_PREPARING) {
                mCondition.wait(mLock);
            }
            return (mState == STATE_STOPPED_AND_PREPARED) ? OK : UNKNOWN_ERROR;
        default:
            return INVALID_OPERATION;
    };
}

status_t NuPlayerDriver::prepareAsync() {
    ALOGV("prepareAsync(%p)", this);
    Mutex::Autolock autoLock(mLock);

    switch (mState) {
        case STATE_UNPREPARED:
            mState = STATE_PREPARING;
            mIsAsyncPrepare = true;
            mPlayer->prepareAsync();
            return OK;
        case STATE_STOPPED:
            // this is really just paused. handle as seek to start
            mAtEOS = false;
            mState = STATE_STOPPED_AND_PREPARING;
            mIsAsyncPrepare = true;
            mPlayer->seekToAsync(0, true /* needNotify */);
            return OK;
        default:
            return INVALID_OPERATION;
    };
}

status_t NuPlayerDriver::start() {
     ALOGI("start, mState = %d", (int)mState);

    Mutex::Autolock autoLock(mLock);

    switch (mState) {
        case STATE_UNPREPARED:
        {
            status_t err = prepare_l();

            if (err != OK) {
                return err;
            }

            CHECK_EQ(mState, STATE_PREPARED);

            // fall through
        }
#ifdef MTK_AOSP_ENHANCEMENT
        case STATE_PREPARING:   //the start will serialized after prepare
#endif
        case STATE_PREPARED:
        {
            mAtEOS = false;
            mPlayer->start();

            if (mStartupSeekTimeUs >= 0) {
                if (mStartupSeekTimeUs > 0) {
                    mPlayer->seekToAsync(mStartupSeekTimeUs);
                }

                mStartupSeekTimeUs = -1;
#ifdef MTK_AOSP_ENHANCEMENT
                mSeekTimeUs = -1;
#endif
            }
            break;
        }

        case STATE_RUNNING:
        {
            if (mAtEOS) {
                mPlayer->seekToAsync(0);
                mAtEOS = false;
                mPositionUs = -1;
            }
            break;
        }

        case STATE_PAUSED:
        case STATE_STOPPED_AND_PREPARED:
        {
            if (mAtEOS) {
                mPlayer->seekToAsync(0);
                mAtEOS = false;
                mPlayer->resume();
                mPositionUs = -1;
            } else {
                mPlayer->resume();
	          }
            break;
        }

        default:
            return INVALID_OPERATION;
    }

    mState = STATE_RUNNING;

    return OK;
}

status_t NuPlayerDriver::stop() {
    ALOGD("stop(%p)", this);
    Mutex::Autolock autoLock(mLock);

    switch (mState) {
        case STATE_RUNNING:
            mPlayer->pause();
            // fall through

        case STATE_PAUSED:
            mState = STATE_STOPPED;
            notifyListener_l(MEDIA_STOPPED);
            break;

        case STATE_PREPARED:
        case STATE_STOPPED:
        case STATE_STOPPED_AND_PREPARING:
        case STATE_STOPPED_AND_PREPARED:
            mState = STATE_STOPPED;
            break;

        default:
            return INVALID_OPERATION;
    }

    return OK;
}

status_t NuPlayerDriver::pause() {
    Mutex::Autolock autoLock(mLock);

    switch (mState) {
        case STATE_PAUSED:
        case STATE_PREPARED:
            return OK;

        case STATE_RUNNING:
            mState = STATE_PAUSED;
            notifyListener_l(MEDIA_PAUSED);
            mPlayer->pause();
            break;

        default:
            return INVALID_OPERATION;
    }

    return OK;
}

bool NuPlayerDriver::isPlaying() {
    return mState == STATE_RUNNING && !mAtEOS;
}

status_t NuPlayerDriver::seekTo(int msec) {
    ALOGD("seekTo(%p) %d ms", this, msec);
    Mutex::Autolock autoLock(mLock);

    int64_t seekTimeUs = msec * 1000ll;
#ifdef MTK_AOSP_ENHANCEMENT 
    //it's live streaming, assume it's ok to seek, because some 3rd party don't get info of live
    if (mDurationUs <= 0 ) {
        notifySeekComplete_l();
        ALOGE("cannot seek without duration, assume to seek complete");
        return OK;
    }
    ALOGI("seekTo(%d ms) mState = %d", msec, (int)mState);
#endif 

    switch (mState) {
        case STATE_PREPARED:
        {
            mStartupSeekTimeUs = seekTimeUs;
            // pretend that the seek completed. It will actually happen when starting playback.
            // TODO: actually perform the seek here, so the player is ready to go at the new
            // location
            notifySeekComplete_l();
            break;
        }

        case STATE_RUNNING:
        case STATE_PAUSED:
        {
            mAtEOS = false;
            mSeekInProgress = true;
            // seeks can take a while, so we essentially paused
            notifyListener_l(MEDIA_PAUSED);
            mPlayer->seekToAsync(seekTimeUs, true /* needNotify */);
            break;
        }

        default:
            return INVALID_OPERATION;
    }

    mPositionUs = seekTimeUs;
#ifdef MTK_AOSP_ENHANCEMENT
    mSeekTimeUs = seekTimeUs;
#endif
    return OK;
}

status_t NuPlayerDriver::getCurrentPosition(int *msec) {
    int64_t tempUs = 0;
    status_t ret = mPlayer->getCurrentPosition(&tempUs);

    Mutex::Autolock autoLock(mLock);
    // We need to check mSeekInProgress here because mPlayer->seekToAsync is an async call, which
    // means getCurrentPosition can be called before seek is completed. Iow, renderer may return a
    // position value that's different the seek to position.
    if (ret != OK || mSeekInProgress) {
        tempUs = (mPositionUs <= 0) ? 0 : mPositionUs;
    } else {
        mPositionUs = tempUs;
    }
    *msec = (int)divRound(tempUs, (int64_t)(1000));
    return OK;
}

status_t NuPlayerDriver::getDuration(int *msec) {
    Mutex::Autolock autoLock(mLock);
#ifdef MTK_AOSP_ENHANCEMENT
	if (mDurationUs/1000 > 0x7fffffff) {
		ALOGI("Duration(%llxms) > 0x7fffffff ms, reset it to %xms", mDurationUs/1000,  0x7fffffff);
		mDurationUs = 0x7fffffffLL*1000;
	}
    else if(mDurationUs < 0) {
        *msec = 0;
        return OK;
    }
#endif

    if (mDurationUs < 0) {
        return UNKNOWN_ERROR;
    }

    *msec = (mDurationUs + 500ll) / 1000;

    return OK;
}

status_t NuPlayerDriver::reset() {
    ALOGD("reset(%p)", this);
    Mutex::Autolock autoLock(mLock);
    ALOGD("reset when mState = %d", mState);

    switch (mState) {
        case STATE_IDLE:
            return OK;

        case STATE_SET_DATASOURCE_PENDING:
        case STATE_RESET_IN_PROGRESS:
            return INVALID_OPERATION;

        case STATE_PREPARING:
        {
            CHECK(mIsAsyncPrepare);

            notifyListener_l(MEDIA_PREPARED);
            break;
        }

        default:
            break;
    }

    if (mState != STATE_STOPPED) {
        notifyListener_l(MEDIA_STOPPED);
    }

    mState = STATE_RESET_IN_PROGRESS;
    mPlayer->resetAsync();

    while (mState == STATE_RESET_IN_PROGRESS) {
        mCondition.wait(mLock);
    }

    mDurationUs = -1;
    mPositionUs = -1;
    mStartupSeekTimeUs = -1;
    mLooping = false;
#ifdef MTK_AOSP_ENHANCEMENT
    mSeekTimeUs = -1;
#endif

    return OK;
}

status_t NuPlayerDriver::setLooping(int loop) {
    mLooping = loop != 0;
    return OK;
}

player_type NuPlayerDriver::playerType() {
    return NU_PLAYER;
}

status_t NuPlayerDriver::invoke(const Parcel &request, Parcel *reply) {
    if (reply == NULL) {
        ALOGE("reply is a NULL pointer");
        return BAD_VALUE;
    }

    int32_t methodId;
    status_t ret = request.readInt32(&methodId);
    if (ret != OK) {
        ALOGE("Failed to retrieve the requested method to invoke");
        return ret;
    }

    switch (methodId) {
        case INVOKE_ID_SET_VIDEO_SCALING_MODE:
        {
            int mode = request.readInt32();
            return mPlayer->setVideoScalingMode(mode);
        }

        case INVOKE_ID_GET_TRACK_INFO:
        {
            return mPlayer->getTrackInfo(reply);
        }

        case INVOKE_ID_SELECT_TRACK:
        {
            int trackIndex = request.readInt32();
            return mPlayer->selectTrack(trackIndex, true /* select */);
        }

        case INVOKE_ID_UNSELECT_TRACK:
        {
            int trackIndex = request.readInt32();
            return mPlayer->selectTrack(trackIndex, false /* select */);
        }

        case INVOKE_ID_GET_SELECTED_TRACK:
        {
            int32_t type = request.readInt32();
            return mPlayer->getSelectedTrack(type, reply);
        }

        default:
        {
            return INVALID_OPERATION;
        }
    }
}

void NuPlayerDriver::setAudioSink(const sp<AudioSink> &audioSink) {
    mPlayer->setAudioSink(audioSink);
    mAudioSink = audioSink;
}

#ifndef MTK_AOSP_ENHANCEMENT
status_t NuPlayerDriver::setParameter(
        int /* key */, const Parcel & /* request */) {
#else
status_t NuPlayerDriver::setParameter(
        int  key , const Parcel &request) {
#endif
#ifdef MTK_AOSP_ENHANCEMENT  
#ifdef MTK_CLEARMOTION_SUPPORT
    if(key == KEY_PARAMETER_CLEARMOTION_DISABLE) {
        int32_t disClearMotion;
        request.readInt32(&disClearMotion);
        ALOGI("setParameter enClearMotion %d",disClearMotion);
        if(disClearMotion)
            mPlayer->enableClearMotion(0);
        else
            mPlayer->enableClearMotion(1);				
        return OK;
    }
#endif
#ifdef MTK_SLOW_MOTION_VIDEO_SUPPORT		
	if(key == KEY_PARAMETER_SlowMotion_Speed_value){
		int SMSpeed = 1;
		request.readInt32(&SMSpeed);
		ALOGD("slowmotion set SMSpeed = %d",SMSpeed);
/*
    	if (isPlaying()) {
			mPositionUs += (ALooper::GetNowUs()- mNotifyTimeRealUs)/SMSpeed;			
        	mNotifyTimeRealUs = ALooper::GetNowUs();
    	}	
*/
		mSpeed = SMSpeed;
		return setsmspeed(SMSpeed);
	}
#endif
#ifdef MTK_SLOW_MOTION_VIDEO_SUPPORT
	if(key == KEY_PARAMETER_SlowMotion_Speed_Section){
		int64_t slowmotion_start =0;
		int64_t slowmotion_end = 0;
        String8 mSlowmotionsection(request.readString16());
		sscanf(mSlowmotionsection.string(),"%dx%d",&slowmotion_start,&slowmotion_end);			
        ALOGD("mSlowmotionsection.string(%s), slowmotion_start = %lld,slowmotion_end = %lld", mSlowmotionsection.string(), slowmotion_start, slowmotion_end);
        return setslowmotionsection(slowmotion_start,slowmotion_end);	
	}
#endif
#ifdef MTK_DRM_APP
if(key == KEY_PARAMETER_DRM_CLIENT_PROC) {
	mPlayer->getDRMClientProc(&request);
}
#endif
#endif
	return INVALID_OPERATION;
}


#ifdef MTK_SLOW_MOTION_VIDEO_SUPPORT
status_t NuPlayerDriver::getParameter(int  key , Parcel *  reply ) {
	ALOGD("NuPlayerDriver::getParameter");
	if(key == KEY_PARAMETER_SlowMotion_Speed_value){
		int32_t SpeedValue = 0;
		sp<MetaData> meta = NULL;
		if(mPlayer != NULL){
			meta = mPlayer->getFormatMeta(false);
			if(meta!= NULL){
				meta->findInt32(kKeySlowMotionSpeedValue, &SpeedValue);
			}
		}
		ALOGD("getparameter = %d",SpeedValue);
		if(SpeedValue > 0){
#ifdef MTK_16X_SLOWMOTION_VIDEO_SUPPORT	
			reply->writeString16(String16("16,4,2,1"));
#else
			reply->writeString16(String16("4,2,1"));
#endif
		}
		else {
			reply->writeString16(String16("0"));
		}
		return OK;
	}
    return INVALID_OPERATION;
}
#else
status_t NuPlayerDriver::getParameter(int /* key */, Parcel * /* reply */) {
    return INVALID_OPERATION;
}
#endif

status_t NuPlayerDriver::getMetadata(
        const media::Metadata::Filter& /* ids */, Parcel *records) {
    Mutex::Autolock autoLock(mLock);

    using media::Metadata;

    Metadata meta(records);

#ifdef MTK_AOSP_ENHANCEMENT
    setMetadata(meta);
#endif
    meta.appendBool(
            Metadata::kPauseAvailable,
            mPlayerFlags & NuPlayer::Source::FLAG_CAN_PAUSE);

    meta.appendBool(
            Metadata::kSeekBackwardAvailable,
            mPlayerFlags & NuPlayer::Source::FLAG_CAN_SEEK_BACKWARD);

    meta.appendBool(
            Metadata::kSeekForwardAvailable,
            mPlayerFlags & NuPlayer::Source::FLAG_CAN_SEEK_FORWARD);

    meta.appendBool(
            Metadata::kSeekAvailable,
            mPlayerFlags & NuPlayer::Source::FLAG_CAN_SEEK);

    return OK;
}

void NuPlayerDriver::notifyResetComplete() {
    ALOGD("notifyResetComplete(%p)", this);
    Mutex::Autolock autoLock(mLock);

    CHECK_EQ(mState, STATE_RESET_IN_PROGRESS);
    mState = STATE_IDLE;
    mCondition.broadcast();
}

void NuPlayerDriver::notifySetSurfaceComplete() {
    ALOGV("notifySetSurfaceComplete(%p)", this);
    Mutex::Autolock autoLock(mLock);

    CHECK(mSetSurfaceInProgress);
    mSetSurfaceInProgress = false;

    mCondition.broadcast();
}

void NuPlayerDriver::notifyDuration(int64_t durationUs) {
    Mutex::Autolock autoLock(mLock);
    mDurationUs = durationUs;
}

void NuPlayerDriver::notifySeekComplete() {
    ALOGV("notifySeekComplete(%p)", this);
    Mutex::Autolock autoLock(mLock);
    mSeekInProgress = false;
    notifySeekComplete_l();
#ifdef MTK_AOSP_ENHANCEMENT    
    mSeekTimeUs = -1;
#endif    
}

void NuPlayerDriver::notifySeekComplete_l() {
    bool wasSeeking = true;
    if (mState == STATE_STOPPED_AND_PREPARING) {
        wasSeeking = false;
        mState = STATE_STOPPED_AND_PREPARED;
        mCondition.broadcast();
        if (!mIsAsyncPrepare) {
            // if we are preparing synchronously, no need to notify listener
            return;
        }
    } else if (mState == STATE_STOPPED) {
        // no need to notify listener
        return;
    }
    notifyListener_l(wasSeeking ? MEDIA_SEEK_COMPLETE : MEDIA_PREPARED);
}

status_t NuPlayerDriver::dump(
        int fd, const Vector<String16> & /* args */) const {
    int64_t numFramesTotal;
    int64_t numFramesDropped;
    mPlayer->getStats(&numFramesTotal, &numFramesDropped);

    FILE *out = fdopen(dup(fd), "w");

    fprintf(out, " NuPlayer\n");
    fprintf(out, "  numFramesTotal(%" PRId64 "), numFramesDropped(%" PRId64 "), "
                 "percentageDropped(%.2f)\n",
                 numFramesTotal,
                 numFramesDropped,
                 numFramesTotal == 0
                    ? 0.0 : (double)numFramesDropped / numFramesTotal);

    fclose(out);
    out = NULL;

    return OK;
}

void NuPlayerDriver::notifyListener(
        int msg, int ext1, int ext2, const Parcel *in) {
    Mutex::Autolock autoLock(mLock);
    notifyListener_l(msg, ext1, ext2, in);
}

void NuPlayerDriver::notifyListener_l(
        int msg, int ext1, int ext2, const Parcel *in) {
    switch (msg) {
        case MEDIA_PLAYBACK_COMPLETE:
        {
            if (mState != STATE_RESET_IN_PROGRESS) {
                if (mLooping || (mAutoLoop
                        && (mAudioSink == NULL || mAudioSink->realtime()))) {
                    mPlayer->seekToAsync(0);
                    break;
                }

                mPlayer->pause();
                mState = STATE_PAUSED;
            }
            // fall through
        }

        case MEDIA_ERROR:
        {
            mAtEOS = true;
            break;
        }

        default:
            break;
    }

    mLock.unlock();
    sendEvent(msg, ext1, ext2, in);
    mLock.lock();
}

void NuPlayerDriver::notifySetDataSourceCompleted(status_t err) {
    Mutex::Autolock autoLock(mLock);

    CHECK_EQ(mState, STATE_SET_DATASOURCE_PENDING);

    mAsyncResult = err;
    mState = (err == OK) ? STATE_UNPREPARED : STATE_IDLE;
#ifdef MTK_AOSP_ENHANCEMENT
	ALOGD("after notifySetDataSourceCompleted mState=%d", mState);
#endif
    mCondition.broadcast();
}

void NuPlayerDriver::notifyPrepareCompleted(status_t err) {
    Mutex::Autolock autoLock(mLock);

    if (mState != STATE_PREPARING) {
        // We were preparing asynchronously when the client called
        // reset(), we sent a premature "prepared" notification and
        // then initiated the reset. This notification is stale.
        CHECK(mState == STATE_RESET_IN_PROGRESS || mState == STATE_IDLE);
        return;
    }

    CHECK_EQ(mState, STATE_PREPARING);

    mAsyncResult = err;

    if (err == OK) {
        // update state before notifying client, so that if client calls back into NuPlayerDriver
        // in response, NuPlayerDriver has the right state
        mState = STATE_PREPARED;
        if (mIsAsyncPrepare) {
            notifyListener_l(MEDIA_PREPARED);
        }
    } else {
        mState = STATE_UNPREPARED;
        if (mIsAsyncPrepare) {
#ifdef MTK_AOSP_ENHANCEMENT
			int ext1;
			reviseNotifyErrorCode(err, ext1);
			notifyListener_l(MEDIA_ERROR, ext1, err);
#else
            notifyListener_l(MEDIA_ERROR, MEDIA_ERROR_UNKNOWN, err);
#endif
        }
    }
#ifndef MTK_AOSP_ENHANCEMENT
//ringtone SetLooping for vorbis ringtone, don't need to do it again
    sp<MetaData> meta = mPlayer->getFileMeta();
    int32_t loop;
    if (meta != NULL
            && meta->findInt32(kKeyAutoLoop, &loop) && loop != 0) {
        mAutoLoop = true;
    }
#endif
    mCondition.broadcast();
}

void NuPlayerDriver::notifyFlagsChanged(uint32_t flags) {
    Mutex::Autolock autoLock(mLock);

    mPlayerFlags = flags;
}

#ifdef MTK_AOSP_ENHANCEMENT
void NuPlayerDriver::notifyUpdateDuration(int64_t durationUs) {
	Mutex::Autolock autoLock(mLock);

	ALOGD("The duration updated, durationUs: %lld -> %lld", mDurationUs, durationUs);	
    mDurationUs = durationUs;
    notifyListener_l(MEDIA_DURATION_UPDATE, mDurationUs/1000, 0);
}

status_t NuPlayerDriver::stop_l() {
    Mutex::Autolock autoLock(mLock);

    switch (mState) {
        case STATE_PAUSED:
        case STATE_PREPARED:
            return OK;

        case STATE_RUNNING:
            mPlayer->stop();
            break;

        default:
            return INVALID_OPERATION;
    }

    mState = STATE_PAUSED;
    return OK;
}

status_t NuPlayerDriver::setMetadata(media::Metadata &meta) {
    // mtk80902: try android default's kXXXAvailable

    // mtk80902: ALPS00448589
    // porting from Stagefright
    
    using media::Metadata;
    sp<MetaData> player_meta = mPlayer->getMetaData();
    if (player_meta != NULL) {
        int timeout = 0;
        if (player_meta->findInt32(kKeyServerTimeout, &timeout) && timeout > 0) {
            meta.appendInt32(Metadata::kServerTimeout, timeout);
        }

        const char *val;
        if (player_meta->findCString(kKeyTitle, &val)) {
            ALOGI("meta title %s ", val);
            meta.appendString(Metadata::kTitle, val);
        }
        if (player_meta->findCString(kKeyAuthor, &val)) {
            ALOGI("meta author %s ", val);
            meta.appendString(Metadata::kAuthor, val);
        }
        if(player_meta->findCString(kKeyAlbumArtMIME, &val))
        {
            meta.appendString(Metadata::kMimeType, val);
            ALOGI("meta kKeyAlbumArtMIME %s ", val);
        }

        uint32_t type;
        size_t dataSize;
        const void *data;
        if(player_meta->findData(kKeyAlbumArt, &type, &data, &dataSize))
        {
            const char *val2 = (const char *)data;
            meta.appendByteArray(Metadata::kAlbumArt, val2, dataSize);
            ALOGI("meta kKeyAlbumArt 0x%X0x%X0x%X0x%X, Size(%d)", val2[0], val2[1], val2[2], val2[3], dataSize);
        }
    }
    return OK;
}

void NuPlayerDriver::reviseNotifyErrorCode(status_t err, int& ext1) {
	ext1 = MEDIA_ERROR_UNKNOWN;
    switch(err) {
        case ERROR_MALFORMED:   // -1007
            ext1 = MEDIA_ERROR_BAD_FILE;
            break;
        case ERROR_CANNOT_CONNECT:  // -1003
            ext1 = MEDIA_ERROR_CANNOT_CONNECT_TO_SERVER;
            break;
        case ERROR_UNSUPPORTED: // -1010
            ext1 = MEDIA_ERROR_TYPE_NOT_SUPPORTED;
            break;
        case ERROR_FORBIDDEN:   // -1100
            ext1 = MEDIA_ERROR_INVALID_CONNECTION;
            break;
        default:
            break;
    }
}

#ifdef MTK_SLOW_MOTION_VIDEO_SUPPORT	
status_t NuPlayerDriver::setsmspeed(int32_t smspeed){
	if(mPlayer != NULL){
		 return mPlayer->setsmspeed(smspeed);
	}else{
		ALOGW("mPlayer == NULL");
		return NO_INIT;
	}
}
status_t NuPlayerDriver::setslowmotionsection(int64_t slowmotion_start,int64_t slowmotion_end){
	if(mPlayer != NULL){
		 return mPlayer->setslowmotionsection(slowmotion_start*1000, slowmotion_end*1000); // ms->us
	}else{
		ALOGW("mPlayer == NULL");
		return NO_INIT;
	}
}
#endif



#endif

}  // namespace android
