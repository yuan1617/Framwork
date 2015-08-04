/*
 * Copyright (C) 2012 The Android Open Source Project
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
#define LOG_TAG "GenericSource"

#include "GenericSource.h"

#include "AnotherPacketSource.h"

#include <media/IMediaHTTPService.h>
#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/DataSource.h>
#include <media/stagefright/FileSource.h>
#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaExtractor.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/Utils.h>
#include "../../libstagefright/include/DRMExtractor.h"
#include "../../libstagefright/include/NuCachedSource2.h"
#include "../../libstagefright/include/WVMExtractor.h"
#include "../../libstagefright/include/HTTPBase.h"
#ifdef MTK_AOSP_ENHANCEMENT
#include <ASessionDescription.h>
#ifdef MTK_DRM_APP
#include <drm/DrmMtkUtil.h>
#include <drm/DrmMtkDef.h>
#endif
#endif

namespace android {
#ifdef MTK_AOSP_ENHANCEMENT
static int64_t kLowWaterMarkUs = 2000000ll;  // 2secs
static int64_t kHighWaterMarkUs = 5000000ll;  // 5secs
static const size_t kLowWaterMarkBytes = 40000;
static const size_t kHighWaterMarkBytes = 200000;
#endif

NuPlayer::GenericSource::GenericSource(
        const sp<AMessage> &notify,
        bool uidValid,
        uid_t uid)
    : Source(notify),
      mFetchSubtitleDataGeneration(0),
      mFetchTimedTextDataGeneration(0),
      mDurationUs(0ll),
      mAudioIsVorbis(false),
      mIsWidevine(false),
      mUIDValid(uidValid),
      mUID(uid),
      mDrmManagerClient(NULL),
      mMetaDataSize(-1ll),
      mBitrate(-1ll),
      mPollBufferingGeneration(0),
      mPendingReadBufferTypes(0) {
    resetDataSource();
    DataSource::RegisterDefaultSniffers();
#ifdef MTK_AOSP_ENHANCEMENT
    mInitCheck = OK;
    mSeekTimeUs = -1;
    mCacheUnderRun = false;
    mPreparing = false;               // for http Streaming
    mPrepareSource = false;            // for http Streaming
    mSDPFormatMeta = new MetaData;
    mIsRequiresSecureBuffer= false;
    mCacheErrorNotify = true;
	mMaxUpdateDurationTimes = 0;
#endif
}

void NuPlayer::GenericSource::resetDataSource() {
    mAudioTimeUs = 0;
    mVideoTimeUs = 0;
    mHTTPService.clear();
    mHttpSource.clear();
    mUri.clear();
    mUriHeaders.clear();
    mFd = -1;
    mOffset = 0;
    mLength = 0;
    setDrmPlaybackStatusIfNeeded(Playback::STOP, 0);
    mDecryptHandle = NULL;
    mDrmManagerClient = NULL;
    mStarted = false;
    mStopRead = true;
}

status_t NuPlayer::GenericSource::setDataSource(
        const sp<IMediaHTTPService> &httpService,
        const char *url,
        const KeyedVector<String8, String8> *headers) {
    resetDataSource();

    mHTTPService = httpService;
    mUri = url;

    if (headers) {
        mUriHeaders = *headers;
    }

    // delay data source creation to prepareAsync() to avoid blocking
    // the calling thread in setDataSource for any significant time.
    return OK;
}

status_t NuPlayer::GenericSource::setDataSource(
        int fd, int64_t offset, int64_t length) {
    resetDataSource();

#ifdef MTK_AOSP_ENHANCEMENT
		mInitCheck = OK;
#endif
    mFd = dup(fd);
    mOffset = offset;
    mLength = length;

    // delay data source creation to prepareAsync() to avoid blocking
    // the calling thread in setDataSource for any significant time.
    return OK;
}

sp<MetaData> NuPlayer::GenericSource::getFileFormatMeta() const {
    return mFileMeta;
}

status_t NuPlayer::GenericSource::initFromDataSource() {
    sp<MediaExtractor> extractor;

    CHECK(mDataSource != NULL);

    if (mIsWidevine) {
        String8 mimeType;
        float confidence;
        sp<AMessage> dummy;
        bool success;

        success = SniffWVM(mDataSource, &mimeType, &confidence, &dummy);
        if (!success
                || strcasecmp(
                    mimeType.string(), MEDIA_MIMETYPE_CONTAINER_WVM)) {
            ALOGE("unsupported widevine mime: %s", mimeType.string());
            return UNKNOWN_ERROR;
        }

        mWVMExtractor = new WVMExtractor(mDataSource);
        mWVMExtractor->setAdaptiveStreamingMode(true);
        if (mUIDValid) {
            mWVMExtractor->setUID(mUID);
        }
        extractor = mWVMExtractor;
    } else {
        extractor = MediaExtractor::Create(mDataSource,
                mSniffedMIME.empty() ? NULL: mSniffedMIME.c_str());
    }

    if (extractor == NULL) {
#ifdef MTK_AOSP_ENHANCEMENT
           ALOGE("initFromDataSource,can't create extractor!");
           mInitCheck = ERROR_UNSUPPORTED;
           if (mCachedSource != NULL) {
               status_t cache_stat = mCachedSource->getRealFinalStatus();
               bool bCacheSuccess = (cache_stat == OK || cache_stat == ERROR_END_OF_STREAM);
               if (!bCacheSuccess) {
                   return ERROR_CANNOT_CONNECT;
               }
           }
#endif 	   	
        return UNKNOWN_ERROR;
    }

#if defined (MTK_AOSP_ENHANCEMENT) && defined (MTK_DRM_APP)
    bool drmFlag = extractor->getDrmFlag();
    int32_t isDrm = 0;
    if (drmFlag == true) {
        checkDrmStatus(mDataSource);
    }
    if (mMetaData != NULL) {
		if (mMetaData->findInt32(kKeyIsDRM, &isDrm)) {
			ALOGD("mMetaData->findInt32(kKeyIsDRM, &isDrm) scuess, isDrm is %d", isDrm);
		} else {
		    ALOGD("mMetaData->findInt32(kKeyIsDRM, &isDrm) fail, then set is %d", drmFlag);
			mMetaData->setInt32(kKeyIsDRM, drmFlag);
		}
    }
#else
    if (extractor->getDrmFlag()) {
        checkDrmStatus(mDataSource);
    }
#endif

#ifdef MTK_AOSP_ENHANCEMENT   
	status_t err = initFromDataSource_checkLocalSdp(extractor)  ;
	if(err == OK) return OK;
	if(err == ERROR_UNSUPPORTED) return err;
	//else, not sdp, should continue to do the following work 
#endif
    mFileMeta = extractor->getMetaData();
    if (mFileMeta != NULL) {
        int64_t duration;
        if (mFileMeta->findInt64(kKeyDuration, &duration)) {
            mDurationUs = duration;
        }
#ifdef MTK_AOSP_ENHANCEMENT
		const char *formatMime;
		if (mFileMeta->findCString(kKeyMIMEType, &formatMime)) {
		    if (!strcasecmp(formatMime, MEDIA_MIMETYPE_CONTAINER_AVI)) {
			    extractor->finishParsing(); // avi create seektable
		    }
		}
       // mFileMeta = fileMeta;
#endif
    }

    int32_t totalBitrate = 0;

    size_t numtracks = extractor->countTracks();
    if (numtracks == 0) {
        return UNKNOWN_ERROR;
    }

    for (size_t i = 0; i < numtracks; ++i) {
        sp<MediaSource> track = extractor->getTrack(i);

        sp<MetaData> meta = extractor->getTrackMetaData(i);

        const char *mime;
        CHECK(meta->findCString(kKeyMIMEType, &mime));

        // Do the string compare immediately with "mime",
        // we can't assume "mime" would stay valid after another
        // extractor operation, some extractors might modify meta
        // during getTrack() and make it invalid.
        if (!strncasecmp(mime, "audio/", 6)) {
            if (mAudioTrack.mSource == NULL) {
                mAudioTrack.mIndex = i;
                mAudioTrack.mSource = track;
                mAudioTrack.mPackets =
                    new AnotherPacketSource(mAudioTrack.mSource->getFormat());
#ifdef MTK_AOSP_ENHANCEMENT
                mAudioTrack.isEOS = false;
#endif

                if (!strcasecmp(mime, MEDIA_MIMETYPE_AUDIO_VORBIS)) {
                    mAudioIsVorbis = true;
                } else {
                    mAudioIsVorbis = false;
                }
            }
        } else if (!strncasecmp(mime, "video/", 6)) {
            if (mVideoTrack.mSource == NULL) {
                mVideoTrack.mIndex = i;
                mVideoTrack.mSource = track;
                mVideoTrack.mPackets =
                    new AnotherPacketSource(mVideoTrack.mSource->getFormat());

#ifdef MTK_AOSP_ENHANCEMENT
                mVideoTrack.isEOS = false;
#endif
                // check if the source requires secure buffers
                int32_t secure;
                if (meta->findInt32(kKeyRequiresSecureBuffers, &secure)
                        && secure) {
                    mIsWidevine = true;
#ifdef MTK_AOSP_ENHANCEMENT					
			 mIsRequiresSecureBuffer= true;
#endif					
                    if (mUIDValid) {
                        extractor->setUID(mUID);
                    }
                }
            }
        }

        if (track != NULL) {
            mSources.push(track);
            int64_t durationUs;
            if (meta->findInt64(kKeyDuration, &durationUs)) {
                if (durationUs > mDurationUs) {
                    mDurationUs = durationUs;
                }
            }

            int32_t bitrate;
            if (totalBitrate >= 0 && meta->findInt32(kKeyBitRate, &bitrate)) {
                totalBitrate += bitrate;
            } else {
                totalBitrate = -1;
            }
        }
    }

    mBitrate = totalBitrate;

#ifdef MTK_AOSP_ENHANCEMENT
	if (mVideoTrack.mSource == NULL && mAudioTrack.mSource == NULL) {
		// report unsupport video to MediaPlayerService
		return ERROR_UNSUPPORTED;
	}
#endif
    return OK;
}

void NuPlayer::GenericSource::checkDrmStatus(const sp<DataSource>& dataSource) {
    dataSource->getDrmInfo(mDecryptHandle, &mDrmManagerClient);
    if (mDecryptHandle != NULL) {
        CHECK(mDrmManagerClient);
        if (RightsStatus::RIGHTS_VALID != mDecryptHandle->status) {
            sp<AMessage> msg = dupNotify();
            msg->setInt32("what", kWhatDrmNoLicense);
            msg->post();
        }
    }
}

int64_t NuPlayer::GenericSource::getLastReadPosition() {
    if (mAudioTrack.mSource != NULL) {
        return mAudioTimeUs;
    } else if (mVideoTrack.mSource != NULL) {
        return mVideoTimeUs;
    } else {
        return 0;
    }
}

status_t NuPlayer::GenericSource::setBuffers(
        bool audio, Vector<MediaBuffer *> &buffers) {
    if (mIsWidevine && !audio) {
        return mVideoTrack.mSource->setBuffers(buffers);
    }
    return INVALID_OPERATION;
}

NuPlayer::GenericSource::~GenericSource() {
    if (mLooper != NULL) {
        mLooper->unregisterHandler(id());
        mLooper->stop();
    }
}

void NuPlayer::GenericSource::prepareAsync() {
    if (mLooper == NULL) {
        mLooper = new ALooper;
        mLooper->setName("generic");
        mLooper->start();

        mLooper->registerHandler(this);
    }

    sp<AMessage> msg = new AMessage(kWhatPrepareAsync, id());
    msg->post();
}

void NuPlayer::GenericSource::onPrepareAsync() {
    // delayed data source creation
    if (mDataSource == NULL) {
        if (!mUri.empty()) {
            const char* uri = mUri.c_str();
            mIsWidevine = !strncasecmp(uri, "widevine://", 11);

            if (!strncasecmp("http://", uri, 7)
                    || !strncasecmp("https://", uri, 8)
                    || mIsWidevine) {
                mHttpSource = DataSource::CreateMediaHTTP(mHTTPService);
                if (mHttpSource == NULL) {
                    ALOGE("Failed to create http source!");
                    notifyPreparedAndCleanup(UNKNOWN_ERROR);
                    return;
                }
            }

            mDataSource = DataSource::CreateFromURI(
                   mHTTPService, uri, &mUriHeaders, &mContentType,
                   static_cast<HTTPBase *>(mHttpSource.get()));
        } else {
            // set to false first, if the extractor
            // comes back as secure, set it to true then.
            mIsWidevine = false;

            mDataSource = new FileSource(mFd, mOffset, mLength);
        }

        if (mDataSource == NULL) {
            ALOGE("Failed to create data source!");
            notifyPreparedAndCleanup(UNKNOWN_ERROR);
            return;
        }

        if (mDataSource->flags() & DataSource::kIsCachingDataSource) {
            mCachedSource = static_cast<NuCachedSource2 *>(mDataSource.get());
        }

        if (mIsWidevine || mCachedSource != NULL) {
#ifdef MTK_AOSP_ENHANCEMENT
            if (mCachedSource != NULL) {
                ALOGI("set prepare source");
                mPrepareSource = true;
            }
#endif
            schedulePollBuffering();
        }
    }

    // check initial caching status
    status_t err = prefillCacheIfNecessary();
    if (err != OK) {
        if (err == -EAGAIN) {
            (new AMessage(kWhatPrepareAsync, id()))->post(200000);
        } else {
            ALOGE("Failed to prefill data cache!");
            notifyPreparedAndCleanup(UNKNOWN_ERROR);
        }
        return;
    }

    // init extrator from data source
    err = initFromDataSource();

    if (err != OK) {
        ALOGE("Failed to init from data source!");
        notifyPreparedAndCleanup(err);
        return;
    }

    if (mVideoTrack.mSource != NULL) {
        sp<MetaData> meta = doGetFormatMeta(false /* audio */);
        sp<AMessage> msg = new AMessage;
        err = convertMetaDataToMessage(meta, &msg);
        if(err != OK) {
            notifyPreparedAndCleanup(err);
            return;
        }
        notifyVideoSizeChanged(msg);
    }

#ifdef MTK_AOSP_ENHANCEMENT
#ifdef MTK_DRM_APP
	// OMA DRM v1 implementation: consume rights.
	mIsCurrentComplete = false;
	if (mDecryptHandle != NULL) {
		ALOGD("NuPlayer, consumeRights @prepare_l()");
		// in some cases, the mFileSource may be NULL (E.g. play audio directly in File Manager)
		// We don't know, but we assume it's a OMA DRM v1 case (DecryptApiType::CONTAINER_BASED)
		if (((mDataSource->flags() & OMADrmFlag) != 0)
			|| (DecryptApiType::CONTAINER_BASED == mDecryptHandle->decryptApiType)) {
			if (!DrmMtkUtil::isTrustedVideoClient(mDrmValue)) {
				mDrmManagerClient->consumeRights(mDecryptHandle, 0x01, false);
			}
		}
	}
#endif
#endif // #ifdef MTK_AOSP_ENHANCEMENT
    notifyFlagsChanged(
#ifndef MTK_AOSP_ENHANCEMENT     
            (mIsWidevine ? FLAG_SECURE : 0) 
#else
	      (mIsWidevine &&  mIsRequiresSecureBuffer ? FLAG_SECURE : 0)
#endif
            | FLAG_CAN_PAUSE
            | FLAG_CAN_SEEK_BACKWARD
            | FLAG_CAN_SEEK_FORWARD
            | FLAG_CAN_SEEK);

#ifdef MTK_AOSP_ENHANCEMENT
    if (mCachedSource != NULL) {
        // asf file, should read from begin. Or else, the cache is in the end of file.
        const char *mime;			
        if (mFileMeta != NULL && mFileMeta->findCString(kKeyMIMEType, &mime) &&
                !strcasecmp(MEDIA_MIMETYPE_VIDEO_WMV, mime)) {
            ALOGI("ASF Streaming change cache to the begining of file");
            int8_t data[1];
            mCachedSource->readAt(0, (void *)data, 1);
        }
        mPreparing = true;
        mPrepareSource = false;
        ALOGI("set preparing here");
        return;
    }
#endif
    notifyPrepared();

}

void NuPlayer::GenericSource::notifyPreparedAndCleanup(status_t err) {
    if (err != OK) {
        mMetaDataSize = -1ll;
        mContentType = "";
        mSniffedMIME = "";
        mDataSource.clear();
        mCachedSource.clear();
        mHttpSource.clear();

        cancelPollBuffering();
    }
    notifyPrepared(err);
}

status_t NuPlayer::GenericSource::prefillCacheIfNecessary() {
    CHECK(mDataSource != NULL);

    if (mCachedSource == NULL) {
        // no prefill if the data source is not cached
        return OK;
    }

    // We're not doing this for streams that appear to be audio-only
    // streams to ensure that even low bandwidth streams start
    // playing back fairly instantly.
    if (!strncasecmp(mContentType.string(), "audio/", 6)) {
        return OK;
    }

    // We're going to prefill the cache before trying to instantiate
    // the extractor below, as the latter is an operation that otherwise
    // could block on the datasource for a significant amount of time.
    // During that time we'd be unable to abort the preparation phase
    // without this prefill.

    // Initially make sure we have at least 192 KB for the sniff
    // to complete without blocking.
    static const size_t kMinBytesForSniffing = 192 * 1024;
    static const size_t kDefaultMetaSize = 200000;

    status_t finalStatus;

    size_t cachedDataRemaining =
            mCachedSource->approxDataRemaining(&finalStatus);

    if (finalStatus != OK || (mMetaDataSize >= 0
            && (off64_t)cachedDataRemaining >= mMetaDataSize)) {
        ALOGV("stop caching, status %d, "
                "metaDataSize %lld, cachedDataRemaining %zu",
                finalStatus, mMetaDataSize, cachedDataRemaining);
        return OK;
    }

    ALOGV("now cached %zu bytes of data", cachedDataRemaining);

    if (mMetaDataSize < 0
            && cachedDataRemaining >= kMinBytesForSniffing) {
        String8 tmp;
        float confidence;
        sp<AMessage> meta;
        if (!mCachedSource->sniff(&tmp, &confidence, &meta)) {
            return UNKNOWN_ERROR;
        }

        // We successfully identified the file's extractor to
        // be, remember this mime type so we don't have to
        // sniff it again when we call MediaExtractor::Create()
        mSniffedMIME = tmp.string();

        if (meta == NULL
                || !meta->findInt64("meta-data-size",
                        reinterpret_cast<int64_t*>(&mMetaDataSize))) {
            mMetaDataSize = kDefaultMetaSize;
        }
#ifdef MTK_AOSP_ENHANCEMENT
        // maybe mMetaDataSize is the whole file, if moov box in the end of file,
        if(mMetaDataSize > kDefaultMetaSize){
            mMetaDataSize = kDefaultMetaSize;
        }
#endif

        if (mMetaDataSize < 0ll) {
            ALOGE("invalid metaDataSize = %lld bytes", mMetaDataSize);
            return UNKNOWN_ERROR;
        }
    }

    return -EAGAIN;
}

void NuPlayer::GenericSource::start() {
    ALOGI("start");
#ifdef MTK_AOSP_ENHANCEMENT
    // http Streaming check buffering status, and notify 701 if cache low
    if (mCachedSource != NULL) {
        onPollBuffering2();
        if (mCacheUnderRun) {
            return;
        }
    }
#endif

    mStopRead = false;
    if (mAudioTrack.mSource != NULL) {
#ifndef MTK_AOSP_ENHANCEMENT
        CHECK_EQ(mAudioTrack.mSource->start(), (status_t)OK);
#else
        status_t err = mAudioTrack.mSource->start();
        if (err != OK) {
            ALOGE("mSource audio start fail");
            sp<AMessage> msg = dupNotify();
            msg->setInt32("what", kWhatSourceError);
            msg->setInt32("err", err);
            msg->post();
            return;
        }
#endif

        postReadBuffer(MEDIA_TRACK_TYPE_AUDIO);
    }

    if (mVideoTrack.mSource != NULL) {
        CHECK_EQ(mVideoTrack.mSource->start(), (status_t)OK);

        postReadBuffer(MEDIA_TRACK_TYPE_VIDEO);
    }

    setDrmPlaybackStatusIfNeeded(Playback::START, getLastReadPosition() / 1000);
    mStarted = true;
#ifdef MTK_AOSP_ENHANCEMENT
    if (mPreparing) {
        ALOGW("Waring: is parpring, but to start"); 
        // mPreparing for http buffering, should set to false.  
        mPreparing = false;        
    }
#ifdef MTK_DRM_APP
	// OMA DRM v1 implementation, when the playback is done and position comes to 0, consume rights.
	if (mIsCurrentComplete) { // single recursive mode
		ALOGD("NuPlayer, consumeRights @play_l()");
		// in some cases, the mFileSource may be NULL (E.g. play audio directly in File Manager)
		// We don't know, but we assume it's a OMA DRM v1 case (DecryptApiType::CONTAINER_BASED)
		if (((mDataSource->flags() & OMADrmFlag) != 0) 
			|| (DecryptApiType::CONTAINER_BASED == mDecryptHandle->decryptApiType)) {
			if (!DrmMtkUtil::isTrustedVideoClient(mDrmValue)) {
				mDrmManagerClient->consumeRights(mDecryptHandle, 0x10, false);
			}
		}
		mIsCurrentComplete = false;
	}
#endif
#endif // #ifdef MTK_AOSP_ENHANCEMENT
#ifdef MTK_AOSP_ENHANCEMENT
	scheduleUpdateDuration();
#endif
}

void NuPlayer::GenericSource::stop() {
    // nothing to do, just account for DRM playback status
    setDrmPlaybackStatusIfNeeded(Playback::STOP, 0);
    mStarted = false;
    if (mIsWidevine) {
        // For a widevine source we need to prevent any further reads.
        sp<AMessage> msg = new AMessage(kWhatStopWidevine, id());
        sp<AMessage> response;
        (void) msg->postAndAwaitResponse(&response);
    }
#ifdef MTK_AOSP_ENHANCEMENT
#ifdef MTK_DRM_APP
	mIsCurrentComplete = true;
#endif
#endif
}

void NuPlayer::GenericSource::pause() {
    // nothing to do, just account for DRM playback status
    setDrmPlaybackStatusIfNeeded(Playback::PAUSE, 0);
    mStarted = false;
}

void NuPlayer::GenericSource::resume() {
    // nothing to do, just account for DRM playback status
    setDrmPlaybackStatusIfNeeded(Playback::START, getLastReadPosition() / 1000);
    mStarted = true;
}

void NuPlayer::GenericSource::disconnect() {
    if (mDataSource != NULL) {
        // disconnect data source
        if (mDataSource->flags() & DataSource::kIsCachingDataSource) {
            static_cast<NuCachedSource2 *>(mDataSource.get())->disconnect();
        }
    } else if (mHttpSource != NULL) {
        static_cast<HTTPBase *>(mHttpSource.get())->disconnect();
    }
}

void NuPlayer::GenericSource::setDrmPlaybackStatusIfNeeded(int playbackStatus, int64_t position) {
    if (mDecryptHandle != NULL) {
        mDrmManagerClient->setPlaybackStatus(mDecryptHandle, playbackStatus, position);
    }
    mSubtitleTrack.mPackets = new AnotherPacketSource(NULL);
    mTimedTextTrack.mPackets = new AnotherPacketSource(NULL);
}

status_t NuPlayer::GenericSource::feedMoreTSData() {
    return OK;
}

void NuPlayer::GenericSource::schedulePollBuffering() {
    sp<AMessage> msg = new AMessage(kWhatPollBuffering, id());
    msg->setInt32("generation", mPollBufferingGeneration);
    msg->post(1000000ll);
}

void NuPlayer::GenericSource::cancelPollBuffering() {
    ++mPollBufferingGeneration;
}

void NuPlayer::GenericSource::notifyBufferingUpdate(int percentage) {
    sp<AMessage> msg = dupNotify();
    msg->setInt32("what", kWhatBufferingUpdate);
    msg->setInt32("percentage", percentage);
    msg->post();
}

void NuPlayer::GenericSource::onPollBuffering() {
#ifdef MTK_AOSP_ENHANCEMENT
    if (mCachedSource != NULL) {
        return onPollBuffering2();
    }
#endif
    status_t finalStatus = UNKNOWN_ERROR;
    int64_t cachedDurationUs = 0ll;

    if (mCachedSource != NULL) {
        size_t cachedDataRemaining =
                mCachedSource->approxDataRemaining(&finalStatus);

        if (finalStatus == OK) {
            off64_t size;
            int64_t bitrate = 0ll;
            if (mDurationUs > 0 && mCachedSource->getSize(&size) == OK) {
                bitrate = size * 8000000ll / mDurationUs;
            } else if (mBitrate > 0) {
                bitrate = mBitrate;
            }
            if (bitrate > 0) {
                cachedDurationUs = cachedDataRemaining * 8000000ll / bitrate;
            }
        }
    } else if (mWVMExtractor != NULL) {
        cachedDurationUs
            = mWVMExtractor->getCachedDurationUs(&finalStatus);
    }

    if (finalStatus == ERROR_END_OF_STREAM) {
        notifyBufferingUpdate(100);
        cancelPollBuffering();
        return;
    } else if (cachedDurationUs > 0ll && mDurationUs > 0ll) {
        int percentage = 100.0 * cachedDurationUs / mDurationUs;
        if (percentage > 100) {
            percentage = 100;
        }

        notifyBufferingUpdate(percentage);
    }

    schedulePollBuffering();
}

#ifdef MTK_AOSP_ENHANCEMENT
void NuPlayer::GenericSource::scheduleUpdateDuration() {

	if (mMaxUpdateDurationTimes > 10)
	{
		return;
	}
    sp<AMessage> msg = new AMessage(kWhatUpdateDuration, id());
    msg->post(200*1000ll);
	mMaxUpdateDurationTimes++;
}

void NuPlayer::GenericSource::notifyDurationUpdate(int64_t durationUs) {

	sp<AMessage> msg = dupNotify();
    msg->setInt32("what", kWhatDurationUpdate);
    msg->setInt64("durationUs", durationUs);
    msg->post();
}

void NuPlayer::GenericSource::onUpdateDuration() {

    int64_t duration;
	Track *track;
	if (mAudioTrack.mSource != NULL && mVideoTrack.mSource == NULL) {
		// only handle audio duration is not correct
		track = &mAudioTrack;
	} else {
		return;
	}
	sp<MetaData> meta = track->mSource->getFormat();
	if (meta->findInt64(kKeyDuration, &duration)) {
		if (mDurationUs != duration && mDurationUs > 0)
		{
        	mDurationUs = duration;
			notifyDurationUpdate(mDurationUs);
			return;
		}
    }
	scheduleUpdateDuration();
}
#endif

void NuPlayer::GenericSource::onMessageReceived(const sp<AMessage> &msg) {
    switch (msg->what()) {
      case kWhatPrepareAsync:
      {
          onPrepareAsync();
          break;
      }
      case kWhatFetchSubtitleData:
      {
          fetchTextData(kWhatSendSubtitleData, MEDIA_TRACK_TYPE_SUBTITLE,
                  mFetchSubtitleDataGeneration, mSubtitleTrack.mPackets, msg);
          break;
      }

      case kWhatFetchTimedTextData:
      {
          fetchTextData(kWhatSendTimedTextData, MEDIA_TRACK_TYPE_TIMEDTEXT,
                  mFetchTimedTextDataGeneration, mTimedTextTrack.mPackets, msg);
          break;
      }

      case kWhatSendSubtitleData:
      {
          sendTextData(kWhatSubtitleData, MEDIA_TRACK_TYPE_SUBTITLE,
                  mFetchSubtitleDataGeneration, mSubtitleTrack.mPackets, msg);
          break;
      }

      case kWhatSendTimedTextData:
      {
#if defined(MTK_AOSP_ENHANCEMENT) && defined(MTK_SUBTITLE_SUPPORT)  
          sendTextData2(kWhatTimedTextData2, MEDIA_TRACK_TYPE_TIMEDTEXT,
                  mFetchTimedTextDataGeneration, mTimedTextTrack.mPackets, msg);
#else
          sendTextData(kWhatTimedTextData, MEDIA_TRACK_TYPE_TIMEDTEXT,
                  mFetchTimedTextDataGeneration, mTimedTextTrack.mPackets, msg);
#endif
          break;
      }

      case kWhatChangeAVSource:
      {
	  	 
          int32_t trackIndex;
          CHECK(msg->findInt32("trackIndex", &trackIndex));
          const sp<MediaSource> source = mSources.itemAt(trackIndex);
          ALOGE("[select track]SelectTrack index:%d",trackIndex);
          Track* track;
          const char *mime;
          media_track_type trackType, counterpartType;
          sp<MetaData> meta = source->getFormat();
          meta->findCString(kKeyMIMEType, &mime);
          if (!strncasecmp(mime, "audio/", 6)) {
              track = &mAudioTrack;
              trackType = MEDIA_TRACK_TYPE_AUDIO;
              counterpartType = MEDIA_TRACK_TYPE_VIDEO;;
          } else {
              CHECK(!strncasecmp(mime, "video/", 6));
              track = &mVideoTrack;
              trackType = MEDIA_TRACK_TYPE_VIDEO;
              counterpartType = MEDIA_TRACK_TYPE_AUDIO;;
          }


          if (track->mSource != NULL) {
              track->mSource->stop();
          }
          track->mSource = source;  //mSource  is  track
          track->mSource->start();
          track->mIndex = trackIndex;

          status_t avail;
          if (!track->mPackets->hasBufferAvailable(&avail)) {
              // sync from other source
              #if defined(MTK_AOSP_ENHANCEMENT) && defined(MTK_AUDIO_CHANGE_SUPPORT) 
              ALOGD("this track has no avialable data");
              #else
              TRESPASS();
              ALOGD("this track has no avialable data");
              break;
              #endif
          }

#if defined(MTK_AOSP_ENHANCEMENT) && defined(MTK_AUDIO_CHANGE_SUPPORT) 
          int64_t timeUs = mAudioTimeUs;
          int64_t actualTimeUs;    
          const bool formatChange = true;
          sp<AMessage> latestMeta = track->mPackets->getLatestEnqueuedMeta();
          if(latestMeta != NULL){
              latestMeta->findInt64("timeUs", &timeUs);
          }
          int64_t latestDequeueTimeUs = -1;
          int64_t videoTimeUs = mVideoTimeUs;
          int64_t seekTimeUs = 0;
          sp<AMessage> latestDequeueMeta = track->mPackets->getLatestDequeuedMeta();
          if(latestDequeueMeta != NULL){
              latestDequeueMeta->findInt64("timeUs", &latestDequeueTimeUs);
          }
          seekTimeUs = (videoTimeUs != 0 ? videoTimeUs:(latestDequeueTimeUs != -1 ? latestDequeueTimeUs : timeUs));
          readBuffer(trackType, seekTimeUs, &actualTimeUs, formatChange); //do seek audio
          readBuffer(counterpartType, -1, NULL, formatChange);
          ALOGE("[select track]seektime:%lld,timeUs %lld, latestDequeueTimeUs %lld,videoTime:%lld,actualTimeUs %lld,counterpartType:%d",seekTimeUs, timeUs, latestDequeueTimeUs,videoTimeUs,actualTimeUs,counterpartType);
		  break;
#else
          int64_t timeUs, actualTimeUs;    
          const bool formatChange = true;
          sp<AMessage> latestMeta = track->mPackets->getLatestEnqueuedMeta();
          CHECK(latestMeta != NULL && latestMeta->findInt64("timeUs", &timeUs));
          readBuffer(trackType, timeUs, &actualTimeUs, formatChange); //do seek audio
          readBuffer(counterpartType, -1, NULL, formatChange);
          ALOGE("[select track]timeUs %lld actualTimeUs %lld,counterpartType:%d ", timeUs, actualTimeUs,counterpartType);
#endif    break;
      }
      case kWhatPollBuffering:
      {
          int32_t generation;
          CHECK(msg->findInt32("generation", &generation));
          if (generation == mPollBufferingGeneration) {
              onPollBuffering();
          }
          break;
      }

#ifdef MTK_AOSP_ENHANCEMENT
	  case kWhatUpdateDuration:
	  {
	  	  onUpdateDuration();
		  break;
	  }
#endif

      case kWhatGetFormat:
      {
          onGetFormatMeta(msg);
          break;
      }

      case kWhatGetSelectedTrack:
      {
          onGetSelectedTrack(msg);
          break;
      }

      case kWhatSelectTrack:
      {
          onSelectTrack(msg);
          break;
      }

      case kWhatSeek:
      {
          onSeek(msg);
          break;
      }

      case kWhatReadBuffer:
      {
          onReadBuffer(msg);
          break;
      }

      case kWhatStopWidevine:
      {
          // mStopRead is only used for Widevine to prevent the video source
          // from being read while the associated video decoder is shutting down.
          mStopRead = true;
          if (mVideoTrack.mSource != NULL) {
              mVideoTrack.mPackets->clear();
          }
          sp<AMessage> response = new AMessage;
          uint32_t replyID;
          CHECK(msg->senderAwaitsResponse(&replyID));
          response->postReply(replyID);
          break;
      }
      default:
          Source::onMessageReceived(msg);
          break;
    }
}

void NuPlayer::GenericSource::fetchTextData(
        uint32_t sendWhat,
        media_track_type type,
        int32_t curGen,
        sp<AnotherPacketSource> packets,
        sp<AMessage> msg) {
    int32_t msgGeneration;
    CHECK(msg->findInt32("generation", &msgGeneration));
    if (msgGeneration != curGen) {
        // stale
        return;
    }

    int32_t avail;
    if (packets->hasBufferAvailable(&avail)) {
        return;
    }

    int64_t timeUs;
    CHECK(msg->findInt64("timeUs", &timeUs));

    int64_t subTimeUs;
    readBuffer(type, timeUs, &subTimeUs);

    int64_t delayUs = subTimeUs - timeUs;
    if (msg->what() == kWhatFetchSubtitleData) {
        const int64_t oneSecUs = 1000000ll;
        delayUs -= oneSecUs;
    }
    sp<AMessage> msg2 = new AMessage(sendWhat, id());
    msg2->setInt32("generation", msgGeneration);
    msg2->post(delayUs < 0 ? 0 : delayUs);
}

void NuPlayer::GenericSource::sendTextData(
        uint32_t what,
        media_track_type type,
        int32_t curGen,
        sp<AnotherPacketSource> packets,
        sp<AMessage> msg) {
    int32_t msgGeneration;
    CHECK(msg->findInt32("generation", &msgGeneration));
    if (msgGeneration != curGen) {
        // stale
        return;
    }

    int64_t subTimeUs;
    if (packets->nextBufferTime(&subTimeUs) != OK) {
        return;
    }

    int64_t nextSubTimeUs;
    readBuffer(type, -1, &nextSubTimeUs);

    sp<ABuffer> buffer;
    status_t dequeueStatus = packets->dequeueAccessUnit(&buffer);
    if (dequeueStatus == OK) {
        sp<AMessage> notify = dupNotify();
        notify->setInt32("what", what);
        notify->setBuffer("buffer", buffer);
        notify->post();

        const int64_t delayUs = nextSubTimeUs - subTimeUs;
        msg->post(delayUs < 0 ? 0 : delayUs);
    }
}

#ifdef MTK_AOSP_ENHANCEMENT
#ifdef MTK_SUBTITLE_SUPPORT
void NuPlayer::GenericSource::sendTextData2(
        uint32_t what,
        media_track_type type,
        int32_t curGen,
        sp<AnotherPacketSource> packets,
        sp<AMessage> msg) {
        ALOGD("func:%s L=%d ",__func__,__LINE__);
    int32_t msgGeneration;
    CHECK(msg->findInt32("generation", &msgGeneration));
    if (msgGeneration != curGen) {
        // stale
        return;
    }

    if (mTimedTextSource == NULL) {
        return;
    }
    int64_t subTimeUs;
    if (packets->nextBufferTime(&subTimeUs) != OK) {
        return;
    }

    int64_t nextSubTimeUs;
    readBuffer(type, -1, &nextSubTimeUs);

    sp<ABuffer> buffer;
    status_t dequeueStatus = packets->dequeueAccessUnit(&buffer);
    if (dequeueStatus == OK) {
        sp<AMessage> notify = dupNotify();
        sp<ParcelEvent>   parcelEvent = new ParcelEvent();
        notify->setInt32("what", what);
        //notify->setBuffer("buffer", buffer);
        mTimedTextSource->parse(buffer->data(),
                                buffer->size(),
                                subTimeUs,
                                -1,     /*fix me,this is a output parameter*/
                                &(parcelEvent->parcel));
        notify->setObject("subtitle", parcelEvent);
        notify->setInt64("timeUs", subTimeUs);
        notify->post();

        const int64_t delayUs = nextSubTimeUs - subTimeUs;
        msg->post(delayUs < 0 ? 0 : delayUs);
    }
}
#endif
#endif 


sp<MetaData> NuPlayer::GenericSource::getFormatMeta(bool audio) {
    sp<AMessage> msg = new AMessage(kWhatGetFormat, id());
    msg->setInt32("audio", audio);

    sp<AMessage> response;
    void *format;
    status_t err = msg->postAndAwaitResponse(&response);
    if (err == OK && response != NULL) {
        CHECK(response->findPointer("format", &format));
#ifdef MTK_AOSP_ENHANCEMENT
        if (mCachedSource != NULL && format != NULL) {
            MetaData *meta = (MetaData *)format;
            if (!audio) {
                ALOGI("video set max queue buffer 1");
                meta->setInt32(kKeyMaxQueueBuffer, 1);
            } else {
                const char *mime = NULL;			
                if (mFileMeta != NULL && mFileMeta->findCString(kKeyMIMEType, &mime) && !strcasecmp(mime, "audio/x-wav")) { 
                    ALOGI("audio x-wav max queueBuffer 2");
                    meta->setInt32(kKeyInputBufferNum, 4);
                    meta->setInt32(kKeyMaxQueueBuffer, 2);
                }
            }
        }
        if (mDecryptHandle != NULL && format != NULL) {
            MetaData *meta = (MetaData *)format;
            meta->setInt32(kKeyIsProtectDrm, 1);
        }
	if( mIsWidevine && !mIsRequiresSecureBuffer && format != NULL){				
		
		MetaData *meta = (MetaData *)format;
		meta->setInt32(kKeyIsProtectDrm, 1);
       }
		
#endif
        return (MetaData *)format;
    } else {
        return NULL;
    }
}

void NuPlayer::GenericSource::onGetFormatMeta(sp<AMessage> msg) const {
    int32_t audio;
    CHECK(msg->findInt32("audio", &audio));

    sp<AMessage> response = new AMessage;
    sp<MetaData> format = doGetFormatMeta(audio);
    response->setPointer("format", format.get());

    uint32_t replyID;
    CHECK(msg->senderAwaitsResponse(&replyID));
    response->postReply(replyID);
}

sp<MetaData> NuPlayer::GenericSource::doGetFormatMeta(bool audio) const {
    sp<MediaSource> source = audio ? mAudioTrack.mSource : mVideoTrack.mSource;
#ifdef MTK_AOSP_ENHANCEMENT
	ALOGI("GenericSource::getFormatMeta %d",audio);
	if(mRtspUri.string() && mSessionDesc.get()){
		//if is sdp
		mSDPFormatMeta->setCString(kKeyMIMEType,MEDIA_MIMETYPE_APPLICATION_SDP);
		mSDPFormatMeta->setCString(kKeyUri,mRtspUri.string());
		mSDPFormatMeta->setPointer(kKeySDP,mSessionDesc.get());

		ALOGI("GenericSource::getFormatMeta sdp meta");

		return mSDPFormatMeta;
	}
#endif
    if (source == NULL) {
        return NULL;
    }

    return source->getFormat();
}

status_t NuPlayer::GenericSource::dequeueAccessUnit(
        bool audio, sp<ABuffer> *accessUnit) {
#ifdef MTK_AOSP_ENHANCEMENT
    // http Streaming check buffering status
    if (mCachedSource != NULL && mCacheUnderRun) {
        return -EWOULDBLOCK;
    }
#endif
    Track *track = audio ? &mAudioTrack : &mVideoTrack;

    if (track->mSource == NULL) {
        return -EWOULDBLOCK;
    }

    if (mIsWidevine && !audio) {
        // try to read a buffer as we may not have been able to the last time
        postReadBuffer(MEDIA_TRACK_TYPE_VIDEO);
    }

    status_t finalResult;
    if (!track->mPackets->hasBufferAvailable(&finalResult)) {
        return (finalResult == OK ? -EWOULDBLOCK : finalResult);
    }

    status_t result = track->mPackets->dequeueAccessUnit(accessUnit);

    if (!track->mPackets->hasBufferAvailable(&finalResult)) {
        postReadBuffer(audio? MEDIA_TRACK_TYPE_AUDIO : MEDIA_TRACK_TYPE_VIDEO);
    }

    if (result != OK) {
        if (mSubtitleTrack.mSource != NULL) {
            mSubtitleTrack.mPackets->clear();
            mFetchSubtitleDataGeneration++;
        }
        if (mTimedTextTrack.mSource != NULL) {
            mTimedTextTrack.mPackets->clear();
            mFetchTimedTextDataGeneration++;
        }
        return result;
    }

    int64_t timeUs;
    status_t eosResult; // ignored
    CHECK((*accessUnit)->meta()->findInt64("timeUs", &timeUs));
	
	ALOGD("dequeueAccessUnit audio:%d time:%lld",audio,timeUs);

    if (mSubtitleTrack.mSource != NULL
            && !mSubtitleTrack.mPackets->hasBufferAvailable(&eosResult)) {
        sp<AMessage> msg = new AMessage(kWhatFetchSubtitleData, id());
        msg->setInt64("timeUs", timeUs);
        msg->setInt32("generation", mFetchSubtitleDataGeneration);
        msg->post();
    }

    if (mTimedTextTrack.mSource != NULL
            && !mTimedTextTrack.mPackets->hasBufferAvailable(&eosResult)) {
        sp<AMessage> msg = new AMessage(kWhatFetchTimedTextData, id());
        msg->setInt64("timeUs", timeUs);
        msg->setInt32("generation", mFetchTimedTextDataGeneration);
        msg->post();
    }

    return result;
}

status_t NuPlayer::GenericSource::getDuration(int64_t *durationUs) {
    *durationUs = mDurationUs;
    return OK;
}

size_t NuPlayer::GenericSource::getTrackCount() const {
	ALOGD("getTrackCount :%d",mSources.size());
    return mSources.size();
}

sp<AMessage> NuPlayer::GenericSource::getTrackInfo(size_t trackIndex) const {
    size_t trackCount = mSources.size();
    if (trackIndex >= trackCount) {
        return NULL;
    }

    sp<AMessage> format = new AMessage();
    sp<MetaData> meta = mSources.itemAt(trackIndex)->getFormat();

    const char *mime;
    CHECK(meta->findCString(kKeyMIMEType, &mime));

    int32_t trackType;
    if (!strncasecmp(mime, "video/", 6)) {
        trackType = MEDIA_TRACK_TYPE_VIDEO;
    } else if (!strncasecmp(mime, "audio/", 6)) {
        trackType = MEDIA_TRACK_TYPE_AUDIO;
#if defined(MTK_AOSP_ENHANCEMENT) && defined(MTK_SUBTITLE_SUPPORT)
    } else if (!strncasecmp(mime, "text/", 5)) {
        trackType = MEDIA_TRACK_TYPE_TIMEDTEXT;    
#else
    } else if (!strcasecmp(mime, MEDIA_MIMETYPE_TEXT_3GPP)) {
        trackType = MEDIA_TRACK_TYPE_TIMEDTEXT;
#endif
    } else {
        trackType = MEDIA_TRACK_TYPE_UNKNOWN;
    }
    format->setInt32("type", trackType);

    const char *lang;
    if (!meta->findCString(kKeyMediaLanguage, &lang)) {
        lang = "und";
    }
    format->setString("language", lang);

    if (trackType == MEDIA_TRACK_TYPE_SUBTITLE) {
        format->setString("mime", mime);

        int32_t isAutoselect = 1, isDefault = 0, isForced = 0;
        meta->findInt32(kKeyTrackIsAutoselect, &isAutoselect);
        meta->findInt32(kKeyTrackIsDefault, &isDefault);
        meta->findInt32(kKeyTrackIsForced, &isForced);

        format->setInt32("auto", !!isAutoselect);
        format->setInt32("default", !!isDefault);
        format->setInt32("forced", !!isForced);
    }

    return format;
}

ssize_t NuPlayer::GenericSource::getSelectedTrack(media_track_type type) const {
    sp<AMessage> msg = new AMessage(kWhatGetSelectedTrack, id());
    msg->setInt32("type", type);

    sp<AMessage> response;
    int32_t index;
    status_t err = msg->postAndAwaitResponse(&response);
    if (err == OK && response != NULL) {
        CHECK(response->findInt32("index", &index));
        return index;
    } else {
        return -1;
    }
}

void NuPlayer::GenericSource::onGetSelectedTrack(sp<AMessage> msg) const {
    int32_t tmpType;
    CHECK(msg->findInt32("type", &tmpType));
    media_track_type type = (media_track_type)tmpType;

    sp<AMessage> response = new AMessage;
    ssize_t index = doGetSelectedTrack(type);
    response->setInt32("index", index);

    uint32_t replyID;
    CHECK(msg->senderAwaitsResponse(&replyID));
    response->postReply(replyID);
}

ssize_t NuPlayer::GenericSource::doGetSelectedTrack(media_track_type type) const {
    const Track *track = NULL;
    switch (type) {
    case MEDIA_TRACK_TYPE_VIDEO:
        track = &mVideoTrack;
        break;
    case MEDIA_TRACK_TYPE_AUDIO:
        track = &mAudioTrack;
        break;
    case MEDIA_TRACK_TYPE_TIMEDTEXT:
        track = &mTimedTextTrack;
        break;
    case MEDIA_TRACK_TYPE_SUBTITLE:
        track = &mSubtitleTrack;
        break;
    default:
        break;
    }

    if (track != NULL && track->mSource != NULL) {
        return track->mIndex;
    }

    return -1;
}

status_t NuPlayer::GenericSource::selectTrack(size_t trackIndex, bool select) {
    ALOGE("[select track]%s track: %zu", select ? "select" : "deselect", trackIndex);
    sp<AMessage> msg = new AMessage(kWhatSelectTrack, id());
    msg->setInt32("trackIndex", trackIndex);
    msg->setInt32("select", select);

    sp<AMessage> response;
    status_t err = msg->postAndAwaitResponse(&response);
    if (err == OK && response != NULL) {
        CHECK(response->findInt32("err", &err));
    }

    return err;
}

void NuPlayer::GenericSource::onSelectTrack(sp<AMessage> msg) {
    int32_t trackIndex, select;
    CHECK(msg->findInt32("trackIndex", &trackIndex));
    CHECK(msg->findInt32("select", &select));

    sp<AMessage> response = new AMessage;
    status_t err = doSelectTrack(trackIndex, select);
    response->setInt32("err", err);

    uint32_t replyID;
    CHECK(msg->senderAwaitsResponse(&replyID));
    response->postReply(replyID);
}

status_t NuPlayer::GenericSource::doSelectTrack(size_t trackIndex, bool select) {
    if (trackIndex >= mSources.size()) {
		ALOGE("[select track]trackIndex:%d > %d",trackIndex,mSources.size());
        return BAD_INDEX;
    }

    if (!select) {
        Track* track = NULL;
        if (mSubtitleTrack.mSource != NULL && trackIndex == mSubtitleTrack.mIndex) {
            track = &mSubtitleTrack;
            mFetchSubtitleDataGeneration++;
        } else if (mTimedTextTrack.mSource != NULL && trackIndex == mTimedTextTrack.mIndex) {
            track = &mTimedTextTrack;
            mFetchTimedTextDataGeneration++;
#ifdef MTK_AOSP_ENHANCEMENT
#ifdef MTK_SUBTITLE_SUPPORT
            if(mTimedTextSource != NULL) {
                mTimedTextSource->stop();
                mTimedTextSource.clear();
            }
#endif
#endif
        }
        if (track == NULL) {
			
			ALOGE("[select track]track == NULL");
            return INVALID_OPERATION;
        }
        track->mSource->stop();
        track->mSource = NULL;
        track->mPackets->clear();
        return OK;
    }

    const sp<MediaSource> source = mSources.itemAt(trackIndex);
    sp<MetaData> meta = source->getFormat();
    const char *mime;
    CHECK(meta->findCString(kKeyMIMEType, &mime));
    if (!strncasecmp(mime, "text/", 5)) {
#if defined(MTK_AOSP_ENHANCEMENT) && defined(MTK_SUBTITLE_SUPPORT)        
        bool isSubtitle = !((0 == strcasecmp(mime, MEDIA_MIMETYPE_TEXT_3GPP))
                         || (0 ==  strcasecmp(mime, MEDIA_MIMETYPE_TEXT_ASS))
                         || (0 ==  strcasecmp(mime, MEDIA_MIMETYPE_TEXT_SSA))
                         || (0 ==  strcasecmp(mime, MEDIA_MIMETYPE_TEXT_VOBSUB))
                         || (0 ==  strcasecmp(mime, MEDIA_MIMETYPE_TEXT_DVB)));
        ALOGD("func:%s isSubtitle=%d ",__func__,isSubtitle);
#else
        bool isSubtitle = strcasecmp(mime, MEDIA_MIMETYPE_TEXT_3GPP);
#endif
        Track *track = isSubtitle ? &mSubtitleTrack : &mTimedTextTrack;
        if (track->mSource != NULL && track->mIndex == trackIndex) {
            return OK;
        }
        track->mIndex = trackIndex;
        if (track->mSource != NULL) {
            track->mSource->stop();
        }
        track->mSource = mSources.itemAt(trackIndex);
#if defined(MTK_AOSP_ENHANCEMENT) && defined(MTK_SUBTITLE_SUPPORT)  
        if (!isSubtitle) {
            mTimedTextSource = TimedTextSource::CreateTimedTextSource(track->mSource);
            mTimedTextSource->start();
        }
        else {
            track->mSource->start();
        }
#else
        track->mSource->start();
#endif

        if (track->mPackets == NULL) {
            track->mPackets = new AnotherPacketSource(track->mSource->getFormat());
        } else {
            track->mPackets->clear();
            track->mPackets->setFormat(track->mSource->getFormat());

        }

        if (isSubtitle) {
            mFetchSubtitleDataGeneration++;
        } else {
            mFetchTimedTextDataGeneration++;
        }

        return OK;
    } else if (!strncasecmp(mime, "audio/", 6) || !strncasecmp(mime, "video/", 6)) {
        bool audio = !strncasecmp(mime, "audio/", 6);
        Track *track = audio ? &mAudioTrack : &mVideoTrack;
        if (track->mSource != NULL && track->mIndex == trackIndex) {
            return OK;
        }

        sp<AMessage> msg = new AMessage(kWhatChangeAVSource, id());
        msg->setInt32("trackIndex", trackIndex);
        msg->post();
        return OK;
    }

    return INVALID_OPERATION;
}

status_t NuPlayer::GenericSource::seekTo(int64_t seekTimeUs) {
    sp<AMessage> msg = new AMessage(kWhatSeek, id());
    msg->setInt64("seekTimeUs", seekTimeUs);

    sp<AMessage> response;
    status_t err = msg->postAndAwaitResponse(&response);
    if (err == OK && response != NULL) {
        CHECK(response->findInt32("err", &err));
    }

    return err;
}

void NuPlayer::GenericSource::onSeek(sp<AMessage> msg) {
    int64_t seekTimeUs;
    CHECK(msg->findInt64("seekTimeUs", &seekTimeUs));

    sp<AMessage> response = new AMessage;
    status_t err = doSeek(seekTimeUs);
    response->setInt32("err", err);

    uint32_t replyID;
    CHECK(msg->senderAwaitsResponse(&replyID));
    response->postReply(replyID);
}

status_t NuPlayer::GenericSource::doSeek(int64_t seekTimeUs) {
    // If the Widevine source is stopped, do not attempt to read any
    // more buffers.
    if (mStopRead) {
        return INVALID_OPERATION;
    }
#ifdef MTK_AOSP_ENHANCEMENT
    mSeekTimeUs = seekTimeUs;
#endif
    if (mVideoTrack.mSource != NULL) {
        int64_t actualTimeUs;
        readBuffer(MEDIA_TRACK_TYPE_VIDEO, seekTimeUs, &actualTimeUs);

        seekTimeUs = actualTimeUs;
    }

    if (mAudioTrack.mSource != NULL) {
        readBuffer(MEDIA_TRACK_TYPE_AUDIO, seekTimeUs);
    }

    setDrmPlaybackStatusIfNeeded(Playback::START, seekTimeUs / 1000);
    if (!mStarted) {
        setDrmPlaybackStatusIfNeeded(Playback::PAUSE, 0);
    }
    return OK;
}

sp<ABuffer> NuPlayer::GenericSource::mediaBufferToABuffer(
        MediaBuffer* mb,
        media_track_type trackType,
        int64_t *actualTimeUs) {
    bool audio = trackType == MEDIA_TRACK_TYPE_AUDIO;
    size_t outLength = mb->range_length();

    if (audio && mAudioIsVorbis) {
        outLength += sizeof(int32_t);
    }

    sp<ABuffer> ab;
#ifdef MTK_AOSP_ENHANCEMENT
    if (mIsWidevine && mIsRequiresSecureBuffer && !audio) 
#else	
    if (mIsWidevine && !audio) 
#endif
{
        // data is already provided in the buffer
        ab = new ABuffer(NULL, mb->range_length());
        mb->add_ref();
        ab->setMediaBufferBase(mb);
    } else {
        ab = new ABuffer(outLength);
        memcpy(ab->data(),
               (const uint8_t *)mb->data() + mb->range_offset(),
               mb->range_length());
    }

    if (audio && mAudioIsVorbis) {
        int32_t numPageSamples;
        if (!mb->meta_data()->findInt32(kKeyValidSamples, &numPageSamples)) {
            numPageSamples = -1;
        }

        uint8_t* abEnd = ab->data() + mb->range_length();
        memcpy(abEnd, &numPageSamples, sizeof(numPageSamples));
    }

    sp<AMessage> meta = ab->meta();

    int64_t timeUs;
    CHECK(mb->meta_data()->findInt64(kKeyTime, &timeUs));
    meta->setInt64("timeUs", timeUs);

    if (trackType == MEDIA_TRACK_TYPE_TIMEDTEXT) {
        const char *mime;
        CHECK(mTimedTextTrack.mSource != NULL
                && mTimedTextTrack.mSource->getFormat()->findCString(kKeyMIMEType, &mime));
        meta->setString("mime", mime);
    }

    int64_t durationUs;
    if (mb->meta_data()->findInt64(kKeyDuration, &durationUs)) {
        meta->setInt64("durationUs", durationUs);
    }

    if (trackType == MEDIA_TRACK_TYPE_SUBTITLE) {
        meta->setInt32("trackIndex", mSubtitleTrack.mIndex);
    }
     // add for debug
	if(audio){
		meta->setInt32("audio",1);
	}else{
		meta->setInt32("audio",0);
	}

    if (actualTimeUs) {
        *actualTimeUs = timeUs;
    }

    mb->release();
    mb = NULL;

    return ab;
}

void NuPlayer::GenericSource::postReadBuffer(media_track_type trackType) {
    Mutex::Autolock _l(mReadBufferLock);

    if ((mPendingReadBufferTypes & (1 << trackType)) == 0) {
        mPendingReadBufferTypes |= (1 << trackType);
        sp<AMessage> msg = new AMessage(kWhatReadBuffer, id());
        msg->setInt32("trackType", trackType);
        msg->post();
    }
}

void NuPlayer::GenericSource::onReadBuffer(sp<AMessage> msg) {
    int32_t tmpType;
    CHECK(msg->findInt32("trackType", &tmpType));
    media_track_type trackType = (media_track_type)tmpType;
    {
        // only protect the variable change, as readBuffer may
        // take considerable time.  This may result in one extra
        // read being processed, but that is benign.
        Mutex::Autolock _l(mReadBufferLock);
        mPendingReadBufferTypes &= ~(1 << trackType);
    }
    readBuffer(trackType);
}

void NuPlayer::GenericSource::readBuffer(
        media_track_type trackType, int64_t seekTimeUs, int64_t *actualTimeUs, bool formatChange) {
    // Do not read data if Widevine source is stopped
    if (mStopRead) {
        return;
    }
    Track *track;
    size_t maxBuffers = 1;
    switch (trackType) {
        case MEDIA_TRACK_TYPE_VIDEO:
            track = &mVideoTrack;
            if (mIsWidevine) {
                maxBuffers = 2;
            }
            break;
        case MEDIA_TRACK_TYPE_AUDIO:
            track = &mAudioTrack;
            if (mIsWidevine) {
                maxBuffers = 8;
            } else {
                maxBuffers = 64;
            }
            break;
        case MEDIA_TRACK_TYPE_SUBTITLE:
            track = &mSubtitleTrack;
            break;
        case MEDIA_TRACK_TYPE_TIMEDTEXT:
            track = &mTimedTextTrack;
            break;
        default:
            TRESPASS();
    }

    if (track->mSource == NULL) {
        return;
    }

    if (actualTimeUs) {
        *actualTimeUs = seekTimeUs;
    }

    MediaSource::ReadOptions options;

    bool seeking = false;

    if (seekTimeUs >= 0) {
        options.setSeekTo(seekTimeUs, MediaSource::ReadOptions::SEEK_PREVIOUS_SYNC);
        seeking = true;
#ifdef MTK_AOSP_ENHANCEMENT
        if (track->isEOS) {
            ALOGI("reset EOS false");
            track->isEOS = false;
        }
#endif
    }
#ifdef MTK_AOSP_ENHANCEMENT
    if (track->isEOS) {
        return;
    }
#endif
    if (mIsWidevine && trackType != MEDIA_TRACK_TYPE_AUDIO) {
        options.setNonBlocking();
    }

    for (size_t numBuffers = 0; numBuffers < maxBuffers; ) {
        MediaBuffer *mbuf;
        status_t err = track->mSource->read(&mbuf, &options);

        options.clearSeekTo();

        if (err == OK) {
            int64_t timeUs;
            CHECK(mbuf->meta_data()->findInt64(kKeyTime, &timeUs));
            if (trackType == MEDIA_TRACK_TYPE_AUDIO) {
                mAudioTimeUs = timeUs;
            } else if (trackType == MEDIA_TRACK_TYPE_VIDEO) {
                mVideoTimeUs = timeUs;
            }

            // formatChange && seeking: track whose source is changed during selection
            // formatChange && !seeking: track whose source is not changed during selection
            // !formatChange: normal seek
            if ((seeking || formatChange)
                    && (trackType == MEDIA_TRACK_TYPE_AUDIO
                    || trackType == MEDIA_TRACK_TYPE_VIDEO)) {
                ATSParser::DiscontinuityType type = formatChange
                        ? (seeking
                                ? ATSParser::DISCONTINUITY_FORMATCHANGE
                                : ATSParser::DISCONTINUITY_NONE)
                        : ATSParser::DISCONTINUITY_SEEK;
#ifdef MTK_AOSP_ENHANCEMENT
                sp<AMessage> extra = NULL;
                if (mHTTPService == NULL && seeking) {
                    extra = new AMessage;
                    extra->setInt64("resume-at-mediatimeUs", mSeekTimeUs);
                    ALOGI("set preRoll time:%lld", mSeekTimeUs);
                }
                track->mPackets->queueDiscontinuity( type, extra, true /* discard */);
#else
                track->mPackets->queueDiscontinuity( type, NULL, true /* discard */);
#endif
            }
			ALOGV("queueAccessUnit type:%d,time:%lld",trackType,timeUs);
            sp<ABuffer> buffer = mediaBufferToABuffer(mbuf, trackType, actualTimeUs);
            track->mPackets->queueAccessUnit(buffer);
            formatChange = false;
            seeking = false;
            ++numBuffers;
        } else if (err == WOULD_BLOCK) {
            break;
        } else if (err == INFO_FORMAT_CHANGED) {
#if 0
            track->mPackets->queueDiscontinuity(
                    ATSParser::DISCONTINUITY_FORMATCHANGE,
                    NULL,
                    false /* discard */);
#endif
        } else {
#ifdef MTK_AOSP_ENHANCEMENT 
            if (seeking) {
				sp<AMessage> extra = NULL;
				extra = new AMessage;
				extra->setInt64("resume-at-mediatimeUs", mSeekTimeUs);
				ALOGI("seek to EOS, discard packets buffers and set preRoll time:%lld", mSeekTimeUs);
				track->mPackets->queueDiscontinuity(ATSParser::DISCONTINUITY_SEEK, extra, true /* discard */);
            }
#endif

            track->mPackets->signalEOS(err);

#ifdef MTK_AOSP_ENHANCEMENT
            track->isEOS = true;
#endif
	            break;
        }
    }
}

#ifdef MTK_AOSP_ENHANCEMENT
#ifdef MTK_DRM_APP
void NuPlayer::GenericSource::getDRMClientProc(const Parcel *request) {
	mDrmValue = request->readString8();
}
#endif

status_t NuPlayer::GenericSource::initCheck() const{
       ALOGI("GenericSource::initCheck");
	return mInitCheck;
}
status_t NuPlayer::GenericSource::getFinalStatus() const{
    status_t cache_stat = OK;
    if (mCachedSource != NULL) 
        cache_stat = mCachedSource->getRealFinalStatus();
    ALOGI("GenericSource::getFinalStatus");
    return cache_stat;
}
status_t NuPlayer::GenericSource::initFromDataSource_checkLocalSdp(const sp<MediaExtractor> extractor) {
    void *sdp = NULL;
    if (extractor->getMetaData().get()!= NULL && extractor->getMetaData()->findPointer(kKeySDP, &sdp)) {
    	  
    	 sp<ASessionDescription> pSessionDesc;
        pSessionDesc = (ASessionDescription*)sdp;
        ALOGI("initFromDataSource,is application/sdp");
        if (!pSessionDesc->isValid()){
		ALOGE("initFromDataSource,sdp file is not valid!");
		pSessionDesc.clear();
		mInitCheck = ERROR_UNSUPPORTED;
		return ERROR_UNSUPPORTED;  //notify not supported sdp
        }
        if (pSessionDesc->countTracks() == 1u){
		ALOGE("initFromDataSource,sdp file contain only root description");
		pSessionDesc.clear();
		mInitCheck = ERROR_UNSUPPORTED;
		return ERROR_UNSUPPORTED;
        }
        status_t err = pSessionDesc->getSessionUrl(mRtspUri);
        if (err != OK){
		ALOGE("initFromDataSource,can't get new url from sdp!!!");
		pSessionDesc.clear();
		mRtspUri.clear();
		mInitCheck = ERROR_UNSUPPORTED;
		return ERROR_UNSUPPORTED;
        }
	mSessionDesc = pSessionDesc;
	mInitCheck = OK;
	return  OK;
    }
    return NO_INIT;//not sdp
}

bool NuPlayer::GenericSource::getCachedDuration(int64_t *durationUs, bool *eos) {
    int64_t bitrate;

    if (mCachedSource != NULL && getBitrate(&bitrate) && (bitrate > 0)) {
        status_t finalStatus;
        size_t cachedDataRemaining = mCachedSource->approxDataRemaining(&finalStatus);
        *durationUs = cachedDataRemaining * 8000000ll / bitrate;
        *eos = (finalStatus != OK);
        return true;
    }

    return false;
}

bool NuPlayer::GenericSource::getBitrate(int64_t *bitrate) {
    off64_t size;
    if (mDurationUs > 0 && mCachedSource != NULL
            && mCachedSource->getSize(&size) == OK) {
        *bitrate = size * 8000000ll / mDurationUs;  // in bits/sec
        return true;
    }

    if (mBitrate >= 0) {
        *bitrate = mBitrate;
        return true;
    }

    *bitrate = 0;

    return false;
}

void NuPlayer::GenericSource::onPollBuffering2() {
    int64_t bitrate = 0ll;
    getBitrate(&bitrate);

    status_t finalStatus;
    size_t cachedDataRemaining = mCachedSource->approxDataRemaining(&finalStatus);
    bool eos = (finalStatus != OK);


    // prepare Source should not notify error here, it maybe case cts testPlayMp3Stream1Ssl fail 
    // prepare error would notify in NuPlayerDriver
    if (eos && !mPrepareSource && (finalStatus != ERROR_END_OF_STREAM) && mCacheErrorNotify) {
        ALOGD("Notify once, onBufferingUpdateCachedSource_l, finalStatus=%d", finalStatus);
        mCacheErrorNotify = false;
        sp<AMessage> msg = dupNotify();
        msg->setInt32("what", kWhatSourceError);
        msg->setInt32("err", finalStatus);
        msg->post();
    }

    //update percent
    if (eos) {
        if (finalStatus == ERROR_END_OF_STREAM) {
            notifyBufferingUpdate(100);
        }
        if (mPreparing) {
            ALOGI("prepared when eos");
            notifyPrepared();
            mPreparing = false;
        }
        if (mCacheUnderRun && bitrate == 0) {
            mCacheUnderRun = false;
            ALOGI("cache eos");
            mCacheUnderRun = false;
            sp<AMessage> notifyStart = dupNotify();
            notifyStart->setInt32("what", kWhatBufferingEnd);
            notifyStart->post();
        }
    } else {
        if (bitrate > 0) {
            size_t cachedSize = mCachedSource->cachedSize();
            int64_t cachedDurationUs = cachedSize * 8000000ll / bitrate;

            int percentage = 100.0 * (double)cachedDurationUs / mDurationUs;
            if (percentage > 100) {
                percentage = 100;
            }

            notifyBufferingUpdate(percentage);
        } else {
            // We don't know the bitrate of the stream, use absolute size
            // limits to maintain the cache.

            if (mStarted && !mCacheUnderRun && !eos
                    && (cachedDataRemaining < kLowWaterMarkBytes)) {
                ALOGI("cache is running low (< %d) , pausing.",
                        kLowWaterMarkBytes);
                mCacheUnderRun = true;
                //ensureCacheIsFetching_l();

                sp<AMessage> notifyStart = dupNotify();
                notifyStart->setInt32("what", kWhatBufferingStart);
                notifyStart->post();
            } else if (eos || cachedDataRemaining > kHighWaterMarkBytes) {
                if (mCacheUnderRun) {
                    ALOGI("cache has filled up (> %d), resuming.",
                            kHighWaterMarkBytes);
                    mCacheUnderRun = false;
                    sp<AMessage> notifyStart = dupNotify();
                    notifyStart->setInt32("what", kWhatBufferingEnd);
                    notifyStart->post();
                } else if (mPreparing) {
                    ALOGI("prepared when bitrate not available");
                    notifyPrepared();
                    mPreparing = false;
                }
            }
        }
    }

    int64_t cachedDurationUs;
    if (getCachedDuration(&cachedDurationUs, &eos)) {
        ALOGV("cachedDurationUs = %.2f secs, eos=%d",
                cachedDurationUs / 1E6, eos);

        int64_t highWaterMarkUs = kHighWaterMarkUs;
        if (bitrate > 0) {
            CHECK(mCachedSource.get() != NULL);
            int64_t nMaxCacheDuration = mCachedSource->getMaxCacheSize() * 8000000ll / bitrate;
            if (nMaxCacheDuration < highWaterMarkUs) {
                //ALOGV("highwatermark = %lld, cache maxduration = %lld", highWaterMarkUs, nMaxCacheDuration);
                highWaterMarkUs = nMaxCacheDuration;
            }
        }

        if (mStarted && !mCacheUnderRun && !eos
                && (cachedDurationUs < kLowWaterMarkUs)) {
            ALOGI("cache is running low (%.2f secs), pausing.",
                    cachedDurationUs / 1E6);
            //ensureCacheIsFetching_l();
            mCacheUnderRun = true;
            sp<AMessage> notifyStart = dupNotify();
            notifyStart->setInt32("what", kWhatBufferingStart);
            notifyStart->post();
        } else if (eos || cachedDurationUs > highWaterMarkUs) {
            if (mCacheUnderRun) {
                ALOGI("cache has filled up (%.2f secs), resuming.",
                        cachedDurationUs / 1E6);
                mCacheUnderRun = false;

                sp<AMessage> notifyStart = dupNotify();
                notifyStart->setInt32("what", kWhatBufferingEnd);
                notifyStart->post();
            } else if (mPreparing) {
                ALOGI("prepared notify");
                notifyPrepared();
                mPreparing = false;
            }
        }
    }
    schedulePollBuffering();
}
#endif
}  // namespace android
