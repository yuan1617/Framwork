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

#include <utils/Log.h>

#include "RTSPSource.h"

#include "AnotherPacketSource.h"
#include "MyHandler.h"
#include "SDPLoader.h"
#ifdef MTK_AOSP_ENHANCEMENT
#define LOG_TAG "RTSPSource"
#endif

#include <media/IMediaHTTPService.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MetaData.h>

#ifdef MTK_AOSP_ENHANCEMENT
#include "GenericSource.h"
#include <ASessionDescription.h>
//for bitrate-adaptation
static int kWholeBufSize = 40000000; //40Mbytes
static int kTargetTime = 2000;  //ms
//Redefine to avoid overrided by other headers


// We're going to buffer at least 2 secs worth data on all tracks before
// starting playback (both at startup and after a seek).

//static const int64_t kMinDurationUs =   5000000ll;
static const int64_t kHighWaterMarkUs = 5000000ll;   //5 secs
//static const int64_t kLowWaterMarkUs =  1000000ll;     //1 secs
#ifdef MTB_SUPPORT
#define ATRACE_TAG ATRACE_TAG_MTK_STREAMING
#include <utils/Trace.h>
#endif
#endif

namespace android {

const int64_t kNearEOSTimeoutUs = 2000000ll; // 2 secs

NuPlayer::RTSPSource::RTSPSource(
        const sp<AMessage> &notify,
        const sp<IMediaHTTPService> &httpService,
        const char *url,
        const KeyedVector<String8, String8> *headers,
        bool uidValid,
        uid_t uid,
        bool isSDP)
    : Source(notify),
      mHTTPService(httpService),
      mURL(url),
      mUIDValid(uidValid),
      mUID(uid),
      mFlags(0),
      mIsSDP(isSDP),
      mState(DISCONNECTED),
      mFinalResult(OK),
      mDisconnectReplyID(0),
      mBuffering(true),
      mSeekGeneration(0),
      mEOSTimeoutAudio(0),
      mEOSTimeoutVideo(0) {
      if (headers) {
        mExtraHeaders = *headers;

        ssize_t index =
            mExtraHeaders.indexOfKey(String8("x-hide-urls-from-log"));

        if (index >= 0) {
            mFlags |= kFlagIncognito;

            mExtraHeaders.removeItemsAt(index);
        }
    }
#ifdef MTK_AOSP_ENHANCEMENT
    mRTSPRenderedPositionUs = 0;
	init();
#endif
}

NuPlayer::RTSPSource::~RTSPSource() {
#ifdef MTK_AOSP_ENHANCEMENT
    if (mHandlerLooper != NULL) {
        mHandlerLooper->stop();
    }
#endif
    if (mLooper != NULL) {
        mLooper->unregisterHandler(id());
        mLooper->stop();
    }
}

void NuPlayer::RTSPSource::prepareAsync() {
    if (mLooper == NULL) {
        mLooper = new ALooper;
        mLooper->setName("rtsp");
        mLooper->start();

        mLooper->registerHandler(this);
    }

    CHECK(mHandler == NULL);
    CHECK(mSDPLoader == NULL);

    sp<AMessage> notify = new AMessage(kWhatNotify, id());

    CHECK_EQ(mState, (int)DISCONNECTED);
    mState = CONNECTING;

    if (mIsSDP) {
        mSDPLoader = new SDPLoader(notify,
                (mFlags & kFlagIncognito) ? SDPLoader::kFlagIncognito : 0,
                mHTTPService);

        mSDPLoader->load(
                mURL.c_str(), mExtraHeaders.isEmpty() ? NULL : &mExtraHeaders);
    } else {
        mHandler = new MyHandler(mURL.c_str(), notify, mUIDValid, mUID);
#ifdef MTK_AOSP_ENHANCEMENT
		setHandler();
        // mtk80902: standalone looper for MyHandler
        registerHandlerLooper();
#else
        mLooper->registerHandler(mHandler);
#endif
#ifdef MTK_AOSP_ENHANCEMENT
        if (msdp != NULL) {
            ALOGI("prepareAsync, sdp mURL = %s", mURL.c_str());
            sp<ASessionDescription> sdp = (ASessionDescription*)msdp.get();
            mHandler->loadSDP(sdp);
        } else
#endif
        mHandler->connect();
    }

    sp<AMessage> notifyStart = dupNotify();
    notifyStart->setInt32("what", kWhatBufferingStart);
    notifyStart->post();
}

void NuPlayer::RTSPSource::start() {
#ifdef MTK_AOSP_ENHANCEMENT
    mHandler->resume();
#endif
}

void NuPlayer::RTSPSource::stop() {
    if (mLooper == NULL) {
        return;
    }
    sp<AMessage> msg = new AMessage(kWhatDisconnect, id());

    sp<AMessage> dummy;
    msg->postAndAwaitResponse(&dummy);
}

void NuPlayer::RTSPSource::pause() {
#ifdef MTK_AOSP_ENHANCEMENT
    if (mState == DISCONNECTED) {
        return;
    }
    // mtk80902: why cant pause near the end of streaming??
#else
    int64_t mediaDurationUs = 0;
    getDuration(&mediaDurationUs);
    for (size_t index = 0; index < mTracks.size(); index++) {
        TrackInfo *info = &mTracks.editItemAt(index);
        sp<AnotherPacketSource> source = info->mSource;

        // Check if EOS or ERROR is received
        if (source != NULL && source->isFinished(mediaDurationUs)) {
            return;
        }
    }
#endif
    mHandler->pause();
}

void NuPlayer::RTSPSource::resume() {
#ifdef MTK_AOSP_ENHANCEMENT
    if (mState == DISCONNECTED) {
        return;
    }
#endif
    mHandler->resume();
}

status_t NuPlayer::RTSPSource::feedMoreTSData() {
    return mFinalResult;
}

sp<MetaData> NuPlayer::RTSPSource::getFormatMeta(bool audio) {
    sp<AnotherPacketSource> source = getSource(audio);

    if (source == NULL) {
        return NULL;
    }

#ifdef  MTK_AOSP_ENHANCEMENT 
    //avoid codec consume data so fast which will cause double bufferring 
    sp<MetaData> meta = source->getFormat();
	setMeta(audio, meta);
    return meta;
#else
    return source->getFormat();
#endif
}

bool NuPlayer::RTSPSource::haveSufficientDataOnAllTracks() {
    // We're going to buffer at least 2 secs worth data on all tracks before
    // starting playback (both at startup and after a seek).

    static const int64_t kMinDurationUs = 2000000ll;

    int64_t mediaDurationUs = 0;
    getDuration(&mediaDurationUs);
    if ((mAudioTrack != NULL && mAudioTrack->isFinished(mediaDurationUs))
            || (mVideoTrack != NULL && mVideoTrack->isFinished(mediaDurationUs))) {
#ifdef MTK_AOSP_ENHANCEMENT
        notifyBufRate(mHighWaterMarkUs);
#endif
        return true;
    }

    status_t err;
    int64_t durationUs;
#ifdef MTK_AOSP_ENHANCEMENT
    err == generalBufferedDurationUs(&durationUs);
    if (err == OK ) {
        bool bufferingComplete = (durationUs >= mHighWaterMarkUs);
		notifyBufRate(bufferingComplete ? mHighWaterMarkUs : durationUs);
        return bufferingComplete;
    }
#else
    if (mAudioTrack != NULL
            && (durationUs = mAudioTrack->getBufferedDurationUs(&err))
                    < kMinDurationUs
            && err == OK) {
        ALOGV("audio track doesn't have enough data yet. (%.2f secs buffered)",
              durationUs / 1E6);
        return false;
    }

    if (mVideoTrack != NULL
            && (durationUs = mVideoTrack->getBufferedDurationUs(&err))
                    < kMinDurationUs
            && err == OK) {
        ALOGV("video track doesn't have enough data yet. (%.2f secs buffered)",
              durationUs / 1E6);
        return false;
    }
#endif

    return true;
}

status_t NuPlayer::RTSPSource::dequeueAccessUnit(
        bool audio, sp<ABuffer> *accessUnit) {
    if (mBuffering) {
        if (!haveSufficientDataOnAllTracks()) {
            return -EWOULDBLOCK;
        }

        mBuffering = false;

        sp<AMessage> notify = dupNotify();
        notify->setInt32("what", kWhatBufferingEnd);
        notify->post();
    }

    sp<AnotherPacketSource> source = getSource(audio);

    if (source == NULL) {
#ifdef MTK_AOSP_ENHANCEMENT
        ALOGD("%s source is null!", audio?"audio":"video");
#endif
        return -EWOULDBLOCK;
    }

#ifdef MTK_AOSP_ENHANCEMENT
    // mtk80902: ALPS00447701
    // error occurs while still receiving data
    // mtk80902: ALPS01258456
    // after seek to the end, received accu arrived earlier than EOS
    if (mQuitRightNow) {
        ALOGD("%s RTSPSource Quit Right Now", audio?"audio":"video");
        return ERROR_END_OF_STREAM;
    }
#endif
    status_t finalResult;
    if (!source->hasBufferAvailable(&finalResult)) {
        if (finalResult == OK) {
            int64_t mediaDurationUs = 0;
            getDuration(&mediaDurationUs);
            sp<AnotherPacketSource> otherSource = getSource(!audio);
            status_t otherFinalResult;

            // If other source already signaled EOS, this source should also signal EOS
            if (otherSource != NULL &&
                    !otherSource->hasBufferAvailable(&otherFinalResult) &&
                    otherFinalResult == ERROR_END_OF_STREAM) {
                source->signalEOS(ERROR_END_OF_STREAM);
                return ERROR_END_OF_STREAM;
            }

            // If this source has detected near end, give it some time to retrieve more
            // data before signaling EOS
            if (source->isFinished(mediaDurationUs)) {
                int64_t eosTimeout = audio ? mEOSTimeoutAudio : mEOSTimeoutVideo;
                if (eosTimeout == 0) {
                    setEOSTimeout(audio, ALooper::GetNowUs());
                } else if ((ALooper::GetNowUs() - eosTimeout) > kNearEOSTimeoutUs) {
                    setEOSTimeout(audio, 0);
                    source->signalEOS(ERROR_END_OF_STREAM);
                    return ERROR_END_OF_STREAM;
                }
                return -EWOULDBLOCK;
            }

            if (!(otherSource != NULL && otherSource->isFinished(mediaDurationUs))) {
                // We should not enter buffering mode
                // if any of the sources already have detected EOS.
                mBuffering = true;

                sp<AMessage> notify = dupNotify();
                notify->setInt32("what", kWhatBufferingStart);
                notify->post();
            }

            return -EWOULDBLOCK;
        }
        return finalResult;
    }

    setEOSTimeout(audio, 0);

    return source->dequeueAccessUnit(accessUnit);
}

sp<AnotherPacketSource> NuPlayer::RTSPSource::getSource(bool audio) {
    if (mTSParser != NULL) {
        sp<MediaSource> source = mTSParser->getSource(
                audio ? ATSParser::AUDIO : ATSParser::VIDEO);

        return static_cast<AnotherPacketSource *>(source.get());
    }

    return audio ? mAudioTrack : mVideoTrack;
}

void NuPlayer::RTSPSource::setEOSTimeout(bool audio, int64_t timeout) {
    if (audio) {
        mEOSTimeoutAudio = timeout;
    } else {
        mEOSTimeoutVideo = timeout;
    }
}

status_t NuPlayer::RTSPSource::getDuration(int64_t *durationUs) {
    *durationUs = 0ll;

    int64_t audioDurationUs;
    if (mAudioTrack != NULL && mAudioTrack->getFormat() != NULL
            && mAudioTrack->getFormat()->findInt64(
                kKeyDuration, &audioDurationUs)
            && audioDurationUs > *durationUs) {
        *durationUs = audioDurationUs;
    }

    int64_t videoDurationUs;
    if (mVideoTrack != NULL && mVideoTrack->getFormat() != NULL
            && mVideoTrack->getFormat()->findInt64(
                kKeyDuration, &videoDurationUs)
            && videoDurationUs > *durationUs) {
        *durationUs = videoDurationUs;
    }

    return OK;
}

status_t NuPlayer::RTSPSource::seekTo(int64_t seekTimeUs) {
		sp<AMessage> msg = new AMessage(kWhatPerformSeek, id());
#ifdef MTK_AOSP_ENHANCEMENT
    status_t err = preSeekSync(seekTimeUs);
    if (err != OK) {
        return err; // here would never return EWOULDBLOCK
    }
    //TODO: flush source to avoid still using data before seek
#endif

    msg->setInt32("generation", ++mSeekGeneration);
    msg->setInt64("timeUs", seekTimeUs);
#ifdef MTK_AOSP_ENHANCEMENT
    msg->post();
    return -EWOULDBLOCK;
#else
    msg->post(200000ll);

    return OK;
#endif
}

void NuPlayer::RTSPSource::performSeek(int64_t seekTimeUs) {
    if (mState != CONNECTED) {
#ifdef MTK_AOSP_ENHANCEMENT
        // add notify
        notifyAsyncDone(kWhatSeekDone);
#endif
        return;
    }

    mState = SEEKING;
    mHandler->seek(seekTimeUs);
}

void NuPlayer::RTSPSource::onMessageReceived(const sp<AMessage> &msg) {
    if (msg->what() == kWhatDisconnect) {
        uint32_t replyID;
        CHECK(msg->senderAwaitsResponse(&replyID));

        mDisconnectReplyID = replyID;
        finishDisconnectIfPossible();
        return;
    } else if (msg->what() == kWhatPerformSeek) {
        int32_t generation;
        CHECK(msg->findInt32("generation", &generation));

        if (generation != mSeekGeneration) {
            // obsolete.
#ifdef MTK_AOSP_ENHANCEMENT
            // add notify
            notifyAsyncDone(kWhatSeekDone);
#endif
            return;
        }

#ifdef MTK_AOSP_ENHANCEMENT
        mQuitRightNow = false;
#endif
        int64_t seekTimeUs;
        CHECK(msg->findInt64("timeUs", &seekTimeUs));

        performSeek(seekTimeUs);
        return;
    }
#ifdef MTK_AOSP_ENHANCEMENT
    else if (msg->what() == kWhatStopTrack) {
		onStopTrack(msg);
        return;
    }
#endif
    CHECK_EQ(msg->what(), (int)kWhatNotify);

    int32_t what;
    CHECK(msg->findInt32("what", &what));

    switch (what) {
        case MyHandler::kWhatConnected:
        {
            onConnected();
#ifndef MTK_AOSP_ENHANCEMENT      // why mark? WR by Qian       
            notifyVideoSizeChanged();
#endif            
            uint32_t flags = 0;

            if (mHandler->isSeekable()) {
                flags = FLAG_CAN_PAUSE
                        | FLAG_CAN_SEEK
                        | FLAG_CAN_SEEK_BACKWARD
                        | FLAG_CAN_SEEK_FORWARD;
            }

            notifyFlagsChanged(flags);
            notifyPrepared();
            break;
        }

        case MyHandler::kWhatDisconnected:
        {
            onDisconnected(msg);
            break;
        }

        case MyHandler::kWhatSeekDone:
        {
            mState = CONNECTED;
#ifdef MTK_AOSP_ENHANCEMENT
            status_t err = OK;
            msg->findInt32("result", &err);
            notifyAsyncDone(kWhatSeekDone, err);
#endif
            break;
        }

        case MyHandler::kWhatAccessUnit:
        {
            size_t trackIndex;
            CHECK(msg->findSize("trackIndex", &trackIndex));

            if (mTSParser == NULL) {
                CHECK_LT(trackIndex, mTracks.size());
            } else {
                CHECK_EQ(trackIndex, 0u);
            }

            sp<ABuffer> accessUnit;
            CHECK(msg->findBuffer("accessUnit", &accessUnit));

            int32_t damaged;
            if (accessUnit->meta()->findInt32("damaged", &damaged)
                    && damaged) {
                ALOGI("dropping damaged access unit.");
                break;
            }

            if (mTSParser != NULL) {
                size_t offset = 0;
                status_t err = OK;
                while (offset + 188 <= accessUnit->size()) {
                    err = mTSParser->feedTSPacket(
                            accessUnit->data() + offset, 188);
                    if (err != OK) {
                        break;
                    }

                    offset += 188;
                }

                if (offset < accessUnit->size()) {
                    err = ERROR_MALFORMED;
                }

                if (err != OK) {
                    sp<AnotherPacketSource> source = getSource(false /* audio */);
                    if (source != NULL) {
                        source->signalEOS(err);
                    }

                    source = getSource(true /* audio */);
                    if (source != NULL) {
                        source->signalEOS(err);
                    }
                }
                break;
            }

            TrackInfo *info = &mTracks.editItemAt(trackIndex);

            sp<AnotherPacketSource> source = info->mSource;
            if (source != NULL) {
                uint32_t rtpTime;
                CHECK(accessUnit->meta()->findInt32("rtp-time", (int32_t *)&rtpTime));

                if (!info->mNPTMappingValid) {
                    // This is a live stream, we didn't receive any normal
                    // playtime mapping. We won't map to npt time.
                    source->queueAccessUnit(accessUnit);
                    break;
                }

                int64_t nptUs =
                    ((double)rtpTime - (double)info->mRTPTime)
                        / info->mTimeScale
                        * 1000000ll
                        + info->mNormalPlaytimeUs;

                accessUnit->meta()->setInt64("timeUs", nptUs);

#ifdef MTB_SUPPORT
				if(mAudioTrack == source)
					ATRACE_ONESHOT(ATRACE_ONESHOT_SPECIAL, "recvOneAudioAccessUnit");  
				else
					ATRACE_ONESHOT(ATRACE_ONESHOT_SPECIAL, "recvOneVideoAccessUnit");  
#endif

                source->queueAccessUnit(accessUnit);
            }
            break;
        }

        case MyHandler::kWhatEOS:
        {
            int32_t finalResult;
            CHECK(msg->findInt32("finalResult", &finalResult));
            CHECK_NE(finalResult, (status_t)OK);

#ifdef MTK_AOSP_ENHANCEMENT
            if (finalResult == ERROR_EOS_QUITNOW) {
                mQuitRightNow = true;
                finalResult = ERROR_END_OF_STREAM;
            }
#endif
            if (mTSParser != NULL) {
                sp<AnotherPacketSource> source = getSource(false /* audio */);
                if (source != NULL) {
                    source->signalEOS(finalResult);
                }

                source = getSource(true /* audio */);
                if (source != NULL) {
                    source->signalEOS(finalResult);
                }

                return;
            }

            size_t trackIndex;
            CHECK(msg->findSize("trackIndex", &trackIndex));
#ifdef MTK_AOSP_ENHANCEMENT
            // mtk80902: ALPS00434921
            if (mTracks.size() == 0 || trackIndex >= mTracks.size()) {
                ALOGW("sth wrong that track index: %d while mTrack's size: %d",
                        trackIndex, mTracks.size());
                break;
            }
#else
            CHECK_LT(trackIndex, mTracks.size());
#endif
            TrackInfo *info = &mTracks.editItemAt(trackIndex);
            sp<AnotherPacketSource> source = info->mSource;
            if (source != NULL) {
                source->signalEOS(finalResult);
            }

            break;
        }

        case MyHandler::kWhatSeekDiscontinuity:
        {
            size_t trackIndex;
            CHECK(msg->findSize("trackIndex", &trackIndex));
            CHECK_LT(trackIndex, mTracks.size());

            TrackInfo *info = &mTracks.editItemAt(trackIndex);
            sp<AnotherPacketSource> source = info->mSource;
            if (source != NULL) {
#ifdef MTK_AOSP_ENHANCEMENT
		        source->queueDiscontinuity(ATSParser::DISCONTINUITY_FLUSH_SOURCE_ONLY, NULL,true);
#else
                source->queueDiscontinuity(
                        ATSParser::DISCONTINUITY_SEEK,
                        NULL,
                        true /* discard */);
#endif
            }

            break;
        }

        case MyHandler::kWhatNormalPlayTimeMapping:
        {
            size_t trackIndex;
            CHECK(msg->findSize("trackIndex", &trackIndex));
            CHECK_LT(trackIndex, mTracks.size());

            uint32_t rtpTime;
            CHECK(msg->findInt32("rtpTime", (int32_t *)&rtpTime));

            int64_t nptUs;
            CHECK(msg->findInt64("nptUs", &nptUs));

            TrackInfo *info = &mTracks.editItemAt(trackIndex);
            info->mRTPTime = rtpTime;
            info->mNormalPlaytimeUs = nptUs;
            info->mNPTMappingValid = true;
            ALOGI("Mapping:mRTPTime %d mNormalPlaytimeUs =%lld",rtpTime,nptUs);
            break;
        }

        case SDPLoader::kWhatSDPLoaded:
        {
            onSDPLoaded(msg);
            break;
        }

#ifdef MTK_AOSP_ENHANCEMENT
        case MyHandler::kWhatPreSeekDone:
        {
            completeSyncCall(msg);
            break;
        }
        case MyHandler::kWhatPlayDone:
        {
            status_t err = OK;
            msg->findInt32("result", &err);
            notifyAsyncDone(kWhatPlayDone, err);
            break;
        }
        case MyHandler::kWhatPauseDone:
        {
            status_t err = OK;
            msg->findInt32("result", &err);
            notifyAsyncDone(kWhatPauseDone, err);
            break;
        }
#endif
        default: 
        {
            ALOGD("Unhandled MyHandler notification '%c%c%c%c' .", 
                    what>>24, (char)((what>>16) & 0xff), (char)((what>>8) & 0xff), (char)(what & 0xff));
            TRESPASS();
		}
    }
}

void NuPlayer::RTSPSource::onConnected() {
    ALOGV("onConnected");
    CHECK(mAudioTrack == NULL);
    CHECK(mVideoTrack == NULL);

    size_t numTracks = mHandler->countTracks();
    for (size_t i = 0; i < numTracks; ++i) {
        int32_t timeScale;
        sp<MetaData> format = mHandler->getTrackFormat(i, &timeScale);

        const char *mime;
        CHECK(format->findCString(kKeyMIMEType, &mime));

        if (!strcasecmp(mime, MEDIA_MIMETYPE_CONTAINER_MPEG2TS)) {
            // Very special case for MPEG2 Transport Streams.
            CHECK_EQ(numTracks, 1u);

            mTSParser = new ATSParser;
            return;
        }

        bool isAudio = !strncasecmp(mime, "audio/", 6);
        bool isVideo = !strncasecmp(mime, "video/", 6);

        TrackInfo info;
        info.mTimeScale = timeScale;
        info.mRTPTime = 0;
        info.mNormalPlaytimeUs = 0ll;
        info.mNPTMappingValid = false;

        if ((isAudio && mAudioTrack == NULL)
                || (isVideo && mVideoTrack == NULL)) {
            sp<AnotherPacketSource> source = new AnotherPacketSource(format);
#ifdef MTK_AOSP_ENHANCEMENT
			//for bitrate adaptation, ARTPConnection need get the pointer of AnotherPacketSource
			//to get the buffer queue info during sendRR
			mHandler->setAnotherPacketSource(i,source);

			//set bufferQue size and target time to anotherpacketSource
			//which will be same to the buffer info send to server during setup
			source->setBufQueSize(m_BufQueSize);
			source->setTargetTime(m_TargetTime);
#endif
            if (isAudio) {
                mAudioTrack = source;
            } else {
                mVideoTrack = source;
#ifdef MTK_AOSP_ENHANCEMENT
                mVideoTrack->setScanForIDR(true);
#endif
            }

            info.mSource = source;
        }

        mTracks.push(info);
    }
#ifdef MTK_AOSP_ENHANCEMENT
	prepareMeta();
#endif

    mState = CONNECTED;
}

void NuPlayer::RTSPSource::onSDPLoaded(const sp<AMessage> &msg) {
    status_t err;
    CHECK(msg->findInt32("result", &err));

    mSDPLoader.clear();

    if (mDisconnectReplyID != 0) {
        err = UNKNOWN_ERROR;
    }

    if (err == OK) {
        sp<ASessionDescription> desc;
        sp<RefBase> obj;
        CHECK(msg->findObject("description", &obj));
        desc = static_cast<ASessionDescription *>(obj.get());

        AString rtspUri;
        if (!desc->findAttribute(0, "a=control", &rtspUri)) {
            ALOGE("Unable to find url in SDP");
            err = UNKNOWN_ERROR;
        } else {
            sp<AMessage> notify = new AMessage(kWhatNotify, id());

            mHandler = new MyHandler(rtspUri.c_str(), notify, mUIDValid, mUID);
#ifdef MTK_AOSP_ENHANCEMENT
			setHandler();
			registerHandlerLooper();
#else
            mLooper->registerHandler(mHandler);
#endif

            mHandler->loadSDP(desc);
        }
    }

    if (err != OK) {
        if (mState == CONNECTING) {
            // We're still in the preparation phase, signal that it
            // failed.
            notifyPrepared(err);
        }

        mState = DISCONNECTED;
        mFinalResult = err;

        if (mDisconnectReplyID != 0) {
            finishDisconnectIfPossible();
        }
    }
}

void NuPlayer::RTSPSource::onDisconnected(const sp<AMessage> &msg) {
    if (mState == DISCONNECTED) {
        return;
    }

    status_t err;
    CHECK(msg->findInt32("result", &err));
    CHECK_NE(err, (status_t)OK);

#ifdef MTK_AOSP_ENHANCEMENT
    mHandlerLooper->unregisterHandler(mHandler->id());
#else
    mLooper->unregisterHandler(mHandler->id());
#endif
    mHandler.clear();

    if (mState == CONNECTING) {
        // We're still in the preparation phase, signal that it
        // failed.
        notifyPrepared(err);
    }

    mState = DISCONNECTED;
    mFinalResult = err;

    if (mDisconnectReplyID != 0) {
        finishDisconnectIfPossible();
    }
}

void NuPlayer::RTSPSource::finishDisconnectIfPossible() {
    if (mState != DISCONNECTED) {
        if (mHandler != NULL) {
            mHandler->disconnect();
        } else if (mSDPLoader != NULL) {
            mSDPLoader->cancel();
        }
        return;
    }

    (new AMessage)->postReply(mDisconnectReplyID);
    mDisconnectReplyID = 0;
}

#ifdef MTK_AOSP_ENHANCEMENT
NuPlayer::RTSPSource::RTSPSource(
        const sp<AMessage> &notify,
        const char *url,
        const KeyedVector<String8, String8> *headers,
        bool uidValid,
        uid_t uid,
        bool isSDP)
    : Source(notify),
      mURL(url),
      mUIDValid(uidValid),
      mUID(uid),
      mFlags(0),
      mIsSDP(isSDP),
      mState(DISCONNECTED),
      mFinalResult(OK),
      mDisconnectReplyID(0),
      mBuffering(true),
      mSeekGeneration(0),
      mEOSTimeoutAudio(0),
      mEOSTimeoutVideo(0) {
      if (headers) {
        mExtraHeaders = *headers;

        ssize_t index =
            mExtraHeaders.indexOfKey(String8("x-hide-urls-from-log"));

        if (index >= 0) {
            mFlags |= kFlagIncognito;

            mExtraHeaders.removeItemsAt(index);
        }
    }
#ifdef MTK_AOSP_ENHANCEMENT
	init();
#endif
}

void NuPlayer::RTSPSource::init() {
	ALOGD("init+, RTSP uri headers from AP:\n");
	for (size_t i = 0; i < mExtraHeaders.size(); i ++) {
		ALOGD("\t\t%s: %s", mExtraHeaders.keyAt(i).string(), mExtraHeaders.valueAt(i).string());
	} 

	mHighWaterMarkUs = kHighWaterMarkUs;
	mQuitRightNow = false;
	mSyncCallResult = OK;
	mSyncCallDone = false;
	mLastSeekCompletedTimeUs = -1;

	//for bitrate adaptation
	m_BufQueSize = kWholeBufSize; //Whole Buffer queue size 
	m_TargetTime = kTargetTime;  // target protected time of buffer queue duration for interrupt-free playback 

	//parse rtsp buffering size from headers and remove useless headers
	//porting from AwesomePlayer
	String8 cacheSize;
	if (removeSpecificHeaders(String8("MTK-RTSP-CACHE-SIZE"), &mExtraHeaders, &cacheSize)) {
		mHighWaterMarkUs = atoi(cacheSize.string()) * 1000000ll;
	}
	ALOGI("RTSP cache size = %lldus", mHighWaterMarkUs);
	//removeSpecificHeaders(String8("MTK-HTTP-CACHE-SIZE"), &mExtraHeaders, &cacheSize);
}

void NuPlayer::RTSPSource::registerHandlerLooper() {
	// mtk80902: standalone looper for MyHandler
	if (mHandlerLooper == NULL) {
		mHandlerLooper = new ALooper;
		mHandlerLooper->setName("rtsp_handler");
		mHandlerLooper->start();
	}
	mHandlerLooper->registerHandler(mHandler);
}

void NuPlayer::RTSPSource::setHandler() {
	// mtk80902: ALPS00450314 - min & max port
	// pass into MyHandler
	mHandler->parseHeaders(&mExtraHeaders);

	//for bitrate adaptation
	//because myhandler need this info during setup, but Anotherpacket source will not be created until connect done
	//so myhandler can't get this buffer info from anotherpacketsource just like apacketsource
	//but myhandler should keep the same value with anotherpacketsource, so put the value setting here
	mHandler->setBufQueSize(m_BufQueSize);
	mHandler->setTargetTimeUs(m_TargetTime);
}

void NuPlayer::RTSPSource::setMeta(bool audio, sp<MetaData>& meta) {
	//avoid codec consume data so fast which will cause double bufferring 
	 if (audio) {
            meta->setInt32(kKeyInputBufferNum, 1);
            meta->setInt32(kKeyMaxQueueBuffer, 1);
   }else{
			meta->setInt32(kKeyRTSPSeekMode, 1);
			meta->setInt32(kKeyMaxQueueBuffer, 1);
			meta->setInt32(kKeyInputBufferNum, 4);
	}
}

void NuPlayer::RTSPSource::notifyBufRate(int64_t durationUs) {
	static int kBufferNotifyCounter = 0;

    if (durationUs != mHighWaterMarkUs && ++kBufferNotifyCounter <= 20) {
        return;
    }

    // clear counter
    kBufferNotifyCounter = 0;

	// mtk80902: NuPlayer dequeueAccu loops in 10ms
	// which is too frequent - here we notify every 0.2S
	sp<AMessage> notify = dupNotify();
	notify->setInt32("what", kWhatBufferNotify);
    int32_t rate = 100.0 * (double)durationUs / mHighWaterMarkUs;
	notify->setInt32("bufRate", rate);
	notify->post();
}

status_t NuPlayer::RTSPSource::generalBufferedDurationUs(int64_t *durationUs) {
    int64_t videoTrackUs = 0;
    int64_t audeoTrackUs = 0;
	*durationUs = -1;
    status_t err = OK;
    if (mVideoTrack != NULL) {
       videoTrackUs = mVideoTrack->getBufferedDurationUs(&err);
       if (err == OK) {
           *durationUs = videoTrackUs;
           ALOGV("video track buffered %.2f secs", videoTrackUs / 1E6);
       } else {
           ALOGV("video track buffer status %d", err);
       }
    }

    if (mAudioTrack != NULL) {
        audeoTrackUs = mAudioTrack->getBufferedDurationUs(&err);
        if (err == OK) {
            if (audeoTrackUs < *durationUs || *durationUs == -1) {
                *durationUs = audeoTrackUs;
            }
            ALOGV("audio track buffered %.f secs", audeoTrackUs / 1E6);
        } else {
           ALOGV("audio track buffer status %d", err);
        }
    }
    return err;
}

void NuPlayer::RTSPSource::notifyAsyncDone(uint32_t notif, status_t err) {
    sp<AMessage> notify = dupNotify();
    notify->setInt32("what", notif);
    notify->setInt32("result", err);
    notify->post();
}

status_t NuPlayer::RTSPSource::preSeekSync(int64_t timeUs) {
    Mutex::Autolock autoLock(mLock);
    if (mState != CONNECTED) {
		ALOGW("live streaming or switching tcp or not connected, seek is invalid.");
        return INVALID_OPERATION;
	}

    bool tooEarly =
        mLastSeekCompletedTimeUs >= 0
            && ALooper::GetNowUs() < mLastSeekCompletedTimeUs + 500000ll;
#ifdef MTK_BSP_PACKAGE
    //cancel  ignore seek --do every seek for bsp package
    // because ignore seek and notify seek complete will cause progress return back
    tooEarly = false;
#endif

     if (tooEarly) {
         ALOGD("seek %lld not perform, because tooEarly", timeUs);
		 ALOGW("ignore too frequent seeks");
         return ALREADY_EXISTS;
     }

     prepareSyncCall();
     mHandler->preSeek(timeUs);
     status_t err = finishSyncCall();
     ALOGI("preSeek end err = %d", err);
	 // mtk80902: ALPS00436651
	 if (err == ALREADY_EXISTS)
		 ALOGW("ignore too frequent seeks");
	 else if (err == INVALID_OPERATION)
		 ALOGW("live streaming or switching TCP or not connected, seek is invalid.");
	 
     return err;
}

void NuPlayer::RTSPSource::prepareSyncCall() {
    mSyncCallResult = OK;
    mSyncCallDone = false;
}

status_t NuPlayer::RTSPSource::finishSyncCall() {
    while(mSyncCallDone == false) {
        mCondition.wait(mLock);
    }
    return mSyncCallResult;
}

void NuPlayer::RTSPSource::completeSyncCall(const sp<AMessage>& msg) {
    Mutex::Autolock autoLock(mLock);
    if (!msg->findInt32("result", &mSyncCallResult)) {
        ALOGW("no result found in completeSyncCall");
        mSyncCallResult = OK;
    }
    mSyncCallDone = true;
    mCondition.signal();
}

void  NuPlayer::RTSPSource::setSDP(sp<RefBase>& sdp){
	msdp = sdp;
}

void NuPlayer::RTSPSource::setParams(const sp<MetaData>& meta)
{
    if (mHandler != NULL)
		mHandler->setPacketSourceParams(meta);	
}

bool NuPlayer::RTSPSource::removeSpecificHeaders(const String8 MyKey, KeyedVector<String8, String8> *headers, String8 *pMyHeader) {
	ALOGD("removeSpecificHeaders %s", MyKey.string());
    *pMyHeader = "";
    if (headers != NULL) {
        ssize_t index;
        if ((index = headers->indexOfKey(MyKey)) >= 0) {
            *pMyHeader = headers->valueAt(index);
            headers->removeItemsAt(index);
           	ALOGD("special headers: %s = %s", MyKey.string(), pMyHeader->string());
            return true;
        }
    }
    return false;
}

void NuPlayer::RTSPSource::stopTrack(bool audio) {
    sp<AMessage> msg = new AMessage(kWhatStopTrack, id());
    msg->setInt32("audio", audio);
    msg->post();
}

void NuPlayer::RTSPSource::prepareMeta() {
	// mtk80902: ALPS00448589
	// porting from MtkRTSPController
	if (mMetaData == NULL)
		mMetaData = new MetaData;
	mMetaData->setInt32(kKeyServerTimeout, mHandler->getServerTimeout());
	AString val;
	sp<ASessionDescription> desc = mHandler->getSessionDesc();
	if (desc->findAttribute(0, "s=", &val)) {
		ALOGI("rtsp s=%s ", val.c_str());
		mMetaData->setCString(kKeyTitle, val.c_str());
	}
	if (desc->findAttribute(0, "i=", &val)) {
		ALOGI("rtsp i=%s ", val.c_str());
		mMetaData->setCString(kKeyAuthor, val.c_str());
	}
}
void NuPlayer::RTSPSource::onStopTrack(const sp<AMessage> &msg) {
	int32_t audio;
	if (!msg->findInt32("audio", &audio))
		return;
	if (audio && mAudioTrack != NULL) {
		for (size_t index = 0; index < mTracks.size(); index++) {
			TrackInfo *info = &mTracks.editItemAt(index);
			if (info->mSource == mAudioTrack) {
				info->mSource.clear();
				mAudioTrack.clear();
				break;
			}
		}
	}
	else if (!audio && mVideoTrack != NULL) {
		for (size_t index = 0; index < mTracks.size(); index++) {
			TrackInfo *info = &mTracks.editItemAt(index);
			if (info->mSource == mVideoTrack) {
				info->mSource.clear();
				mVideoTrack.clear();
				break;
			}
		}
	}
}

NuPlayer::Source::DataSourceType NuPlayer::RTSPSource::getDataSourceType() {
    return NuPlayer::Source::SOURCE_Rtsp;
}

int64_t NuPlayer::RTSPSource::getRenderedPositionUs() {
    return mRTSPRenderedPositionUs;
}

void NuPlayer::RTSPSource::setRenderedPositionUs(const int64_t us) {
    mRTSPRenderedPositionUs = us;
}
#endif

}  // namespace android
