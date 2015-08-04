/*
 * Copyright (C) 2011 The Android Open Source Project
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
#define LOG_TAG "ESExtractor"
#include <utils/Log.h>
#include "ESExtractor.h"


#include <media/stagefright/foundation/ABitReader.h>
#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/foundation/hexdump.h>
#include <media/stagefright/DataSource.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/Utils.h>
#include <utils/String8.h>

#include "mpeg2ts/AnotherPacketSource.h"
#include "mpeg2ts/ATSParser.h"
#include "include/avc_utils.h"
#include <sys/time.h>

#include "include/hevc_utils.h"

#define ES_SNIFF_LENGTH 256
#define ES_CHUNK_SIZE   16384

#define ES_ONE_TRACK       1
#define ES_NO_TRACK        0

#define GETFORMATDONE     0x1010
#define GETAUDONE         0x2020

namespace android {

struct ESExtractor::Track : public MediaSource {
    Track(ESExtractor *extractor, unsigned stream_type);

    virtual status_t start(MetaData *params);
    virtual status_t stop();
    virtual sp<MetaData> getFormat();

    virtual status_t read(
            MediaBuffer **buffer, const ReadOptions *options);
    
    sp<MetaData> getQueueFormat();
    sp<AnotherPacketSource> getSource();
    void setSource(sp<MetaData> meta);

    status_t dequeueAccessUnit(sp<ABuffer> &mAccessUnit);
    
    status_t dequeueAccessUnitMPEGVideo(sp<ABuffer> &mAccessUnit);

    status_t dequeueAccessUnitHEVC(sp<ABuffer> &mAccessUnit);

protected:
    virtual ~Track();

private:
    friend struct ESExtractor;

    ESExtractor *mExtractor;

    unsigned mStreamType;
    sp<AnotherPacketSource> mSource;
    sp<MetaData> mQueueFormat;
    bool seeking;
    bool mSeekable;

    bool isVideo();
    bool isAudio();
    void signalDiscontinuity(const bool bKeepFormat = false);

    DISALLOW_EVIL_CONSTRUCTORS(Track);
};

struct ESExtractor::WrappedTrack : public MediaSource {
    WrappedTrack(const sp<ESExtractor> &extractor, const sp<Track> &track);

    virtual status_t start(MetaData *params);
    virtual status_t stop();
    virtual sp<MetaData> getFormat();

    virtual status_t read(
            MediaBuffer **buffer, const ReadOptions *options);

protected:
    virtual ~WrappedTrack();

private:
    sp<ESExtractor> mWExtractor;
    sp<ESExtractor::Track> mWTrack;

    DISALLOW_EVIL_CONSTRUCTORS(WrappedTrack);
};

////////////////////////////////////////////////////////////////////////////////
struct NALPosition {
    size_t nalOffset;
    size_t nalSize;
};

static uint32_t U24_AT(const uint8_t *ptr) {
    return ptr[0] << 16 | ptr[1] << 8 | ptr[2];
}

static void EncodeSize14(uint8_t **_ptr, size_t size) {
    CHECK_LE(size, 0x3fff);

    uint8_t *ptr = *_ptr;

    *ptr++ = 0x80 | (size >> 7);
    *ptr++ = size & 0x7f;

    *_ptr = ptr;
}

static sp<ABuffer> MakeMPEGVideoESDS(const sp<ABuffer> &csd) {
    sp<ABuffer> esds = new ABuffer(csd->size() + 25);

    uint8_t *ptr = esds->data();
    *ptr++ = 0x03;
    EncodeSize14(&ptr, 22 + csd->size());

    *ptr++ = 0x00;  // ES_ID
    *ptr++ = 0x00;

    *ptr++ = 0x00;  // streamDependenceFlag, URL_Flag, OCRstreamFlag

    *ptr++ = 0x04;
    EncodeSize14(&ptr, 16 + csd->size());

    *ptr++ = 0x40;  // Audio ISO/IEC 14496-3

    for (size_t i = 0; i < 12; ++i) {
        *ptr++ = 0x00;
    }

    *ptr++ = 0x05;
    EncodeSize14(&ptr, csd->size());

    memcpy(ptr, csd->data(), csd->size());

    return esds;
}
status_t getNextNALUnit4(
        const uint8_t **_data, size_t *_size,
        const uint8_t **nalStart, size_t *nalSize,
        bool startCodeFollows) {

    const uint8_t *data = *_data;
    size_t size = *_size;

    *nalStart = NULL;
    *nalSize = 0;

    if (size == 0) {
        return -EAGAIN;
    }

    // Skip any number of leading 0x00.

    size_t offset = 0;
    while (offset < size && data[offset] == 0x00) {
        ++offset;
    }

    if (offset == size) {
        return -EAGAIN;
    }

    // A valid startcode consists of at least two 0x00 bytes followed by 0x01.

    if (offset < 2 || data[offset] != 0x01) {
        return ERROR_MALFORMED;
    }

    ++offset;

    size_t startOffset = offset;

    for (;;) {
        while (offset < size && data[offset] != 0x01) {
            ++offset;
        }

        if (offset == size) {
            if (startCodeFollows) {
                offset = size + 2;
                break;
            }

            return -EAGAIN;
        }

        if (data[offset - 1] == 0x00 && data[offset - 2] == 0x00) {
            break;
        }

        ++offset;
    }
    size_t endOffset;
    
    if ((offset >= 3) && data[offset - 1] == 0x00 && data[offset - 2] == 0x00 && data[offset - 3] == 0x00) {
        endOffset = offset - 3;
    }
    else {
        endOffset = offset - 2;
    }
    
    /*
    while (endOffset > startOffset + 1 && data[endOffset - 1] == 0x00) {
        --endOffset;
    }
    */
    
    *nalStart = &data[startOffset];
    *nalSize = endOffset - startOffset;


    if (offset + 2 < size) {
        *_data = &data[offset - 2];
        *_size = size - offset + 2;
    } else {
        *_data = NULL;
        *_size = 0;
    }
    
    ALOGV("nalSize:%d,startOffset:%d,endOffset:%d,offset:%d",*nalSize,startOffset,endOffset,offset);
    return OK;
}

////////////////////////////////////////////////////////////////////////////////

ESExtractor::ESExtractor(const sp<DataSource> &source)
    : mDataSource(source),
      mOffset(0),
      mFinalResult(OK),
      mBuffer(new ABuffer(0)),
      mScanning(true),
      mSeeking(false),
      mFileSize(0),
      mhasVTrack(false),
      mhasATrack(false),
      mNeedDequeuePES(true),
	  bisPlayable(true) {
      init();
      signalDiscontinuity(true);
      //Init Offset
      mOffset = 0;
      mScanning = false;
}

ESExtractor::~ESExtractor() {
}

size_t ESExtractor::countTracks() {
    if (mTrack == NULL) {
        return ES_NO_TRACK;
    }

    return ES_ONE_TRACK;
}


sp<MediaSource> ESExtractor::getTrack(size_t index) {
    if (mTrack == NULL) {
        return NULL;
    }
    
    return new WrappedTrack(this, mTrack);
}

sp<MetaData> ESExtractor::getTrackMetaData(size_t index, uint32_t flags) {
    if (mTrack == NULL) {
        return NULL;
    }

    return mTrack->getFormat();
}

sp<MetaData> ESExtractor::getMetaData() {
    sp<MetaData> meta_mp;
    int32_t      bitrate;
    
    mDataSource->getSize(&mFileSize);
    sp<MetaData> meta = new MetaData;
    meta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_ELEMENT_STREAM);
    if ((mTrack != NULL) && ( (meta_mp = mTrack->getQueueFormat()) != NULL)) {
        
        if(meta_mp->findInt32(kKeyBitRate, &bitrate)) {
            meta->setInt32(kKeyBitRate, bitrate);
        }
    }
    return meta;
}


uint32_t ESExtractor::flags() const {

    return CAN_PAUSE;
}

status_t ESExtractor::feedMore() {
    Mutex::Autolock autoLock(mLock);
    static const size_t kChunkSize = ES_CHUNK_SIZE;
    for (;;) {
        status_t err = dequeueES();

        if (err == -EAGAIN && mFinalResult == OK) {
            
            memmove(mBuffer->base(), mBuffer->data(), mBuffer->size());
            mBuffer->setRange(0, mBuffer->size());
            
            ALOGD("mBuffer->size():%d,kChunkSize:%d,mBuffer->capacity():%d",mBuffer->size(),kChunkSize,mBuffer->capacity());
            if (mBuffer->size() + kChunkSize > mBuffer->capacity()) {
                size_t neededSize = mBuffer->size() + kChunkSize;
                neededSize = (neededSize + 65535) & ~65535;
                size_t newCapacity = (mBuffer->capacity()==0) ? kChunkSize : neededSize;
                ALOGD("Capacity %d->%d\n", mBuffer->capacity(), newCapacity);
                sp<ABuffer> newBuffer = new ABuffer(newCapacity);
                memcpy(newBuffer->data(), mBuffer->data(), mBuffer->size());
                newBuffer->setRange(0, mBuffer->size());
                mBuffer = newBuffer;
            }

            ssize_t n = mDataSource->readAt(
                    mOffset, mBuffer->data() + mBuffer->size(), kChunkSize);

            if (n < (ssize_t)kChunkSize) {
                mFinalResult = (n < 0) ? (status_t)n : ERROR_END_OF_STREAM;
                return mFinalResult;
            }

            mBuffer->setRange(mBuffer->offset(), mBuffer->size() + n);
            mOffset += n;
            ALOGD("Read success,mBuffer->size()%d",mBuffer->size());
        }
        else if (err != OK) {
            mFinalResult = err;
            return err;
        } 
        else {
            return OK;
        }
    }
}

status_t ESExtractor::dequeueES() {

    unsigned streamType;
    uint8_t pprevStartCode = 0xff;
    uint8_t prevStartCode = 0xff;
    uint8_t currentStartCode = 0xff;

    if (mBuffer->size() < 4) {
        ALOGD("dequeueES:mBuffer->size() < 4");
        return -EAGAIN;
    }

    if (mTrack == NULL && mScanning) {
        const uint8_t *data = mBuffer->data();
        size_t size = mBuffer->size();
        size_t offset = 0;
        for (;;) {
            if (offset + 3 >= size) {
                return ERROR_MALFORMED;
            }
            if (U24_AT(data + offset) != 0x000001) {
                ++offset;
                continue;
            }
            pprevStartCode = prevStartCode;
            prevStartCode = currentStartCode;
            currentStartCode = data[offset+3];
            if (0xb3 == prevStartCode) {//MPEG1/2
                if (0xb5 == currentStartCode) {
                    streamType = ATSParser::STREAMTYPE_MPEG2_VIDEO;
                    mTrack = new Track(this, streamType);
                    ALOGD("streamType:STREAMTYPE_MPEG2_VIDEO");
                    return OK;
                }
                else {
                    streamType = ATSParser::STREAMTYPE_MPEG1_VIDEO;
                    mTrack = new Track(this, streamType);
                    ALOGD("streamType:STREAMTYPE_MPEG1_VIDEO");
                    return OK;
                }
            }
            else if ((0x44 == currentStartCode) && (0x42 == prevStartCode) && (0x40 == pprevStartCode)) {
                streamType = ATSParser::STREAMTYPE_HEVC;
                mTrack = new Track(this, streamType);
                ALOGD("streamType:STREAMTYPE_HEVC");
                return OK;
            }
            offset++;
        }
    }
    else if (mTrack != NULL) {
        
        if (!mTrack->mExtractor->getDequeueState()){
            return OK;
        }

        sp<ABuffer> accessUnit;
        status_t err = mTrack->dequeueAccessUnit(accessUnit);
        switch (err) {
            case GETAUDONE:
                ALOGD("dequeueES:dequeueAccessUnit return GETAUDONE");
                if (mTrack->getSource() == NULL) {
                    ALOGV("dequeueES:mTrack->mSource is NULL");
                    sp<MetaData> meta = mTrack->getQueueFormat();
                    if (meta != NULL) {
                        ALOGV("dequeueES:Got The Queue Format");
                        mTrack->setSource(meta);
                        ALOGV("dequeueES:set The mSource,queue this AU");
                        mTrack->getSource()->queueAccessUnit(accessUnit);
                    }
                } 
                else if (mTrack->getQueueFormat() != NULL) {
                    ALOGV("dequeueES:mTrack->mSource is not NULL and mTrack->getQueueFormat() is not NULL");
                    mTrack->getSource()->queueAccessUnit(accessUnit);
                }
                return OK;
            case -EAGAIN:
                ALOGD("dequeueES:dequeueAccessUnit return -EAGAIN");
                return -EAGAIN;
            case GETFORMATDONE:
                ALOGD("dequeueES:dequeueAccessUnit return GETFORMATDONE");
                return OK;
            case ERROR_MALFORMED:
                return ERROR_MALFORMED;
        }
        
    }
    
    return ERROR_MALFORMED;
}



void ESExtractor::setDequeueState(bool needDequeuePES) {
	mNeedDequeuePES = needDequeuePES;
}

bool ESExtractor::getDequeueState() {    //For Seek
	return mNeedDequeuePES;
}

void ESExtractor::init() {
    bool haveAudio = false;
    bool haveVideo = false;
    int numPacketsParsed = 0;
    ALOGD("*****************init in*************** \n");

    mOffset = 0;
    while (feedMore() == OK) {

        if (++numPacketsParsed > 10) {
            break;
        }
        
        if (mTrack == NULL) {
            continue;
        }
        
        if (mTrack->getFormat() == NULL) {
            continue;
        }

        if (mTrack->isVideo() &&(mTrack->getFormat() != NULL)) {
            haveVideo = true;
        }
        
        if (mTrack->isAudio() &&(mTrack->getFormat() != NULL)) {
            haveAudio = true;
        }

        if (haveAudio == 1 || haveVideo == 1) {
    		ALOGD("bisplayable is true");
    		bisPlayable = true;
            break;
	    }

    }

    mFinalResult = OK;
    mBuffer->setRange(0, 0);
    ALOGD("************ init out *****************\n");
}

bool ESExtractor::getSeeking() {
    return mSeeking;
}

void ESExtractor::signalDiscontinuity(const bool bKeepFormat) {
    if (mBuffer != NULL) {
        mBuffer.clear();
        mBuffer = new ABuffer(0);
    }
    if (mTrack != NULL)
        mTrack->signalDiscontinuity(bKeepFormat);
}

////////////////////////////////////////////////////////////////////////////////

ESExtractor::Track::Track(ESExtractor *extractor, unsigned stream_type)
    : mExtractor(extractor),
      mStreamType(stream_type),
      seeking(false),
      mSeekable(false){
}

ESExtractor::Track::~Track() {

}

status_t ESExtractor::Track::start(MetaData *params) {
    if (mSource == NULL) {
        return NO_INIT;
    }

    return mSource->start(params);
}

status_t ESExtractor::Track::stop() {
    if (mSource == NULL) {
        return NO_INIT;
    }

    return mSource->stop();
}

sp<MetaData> ESExtractor::Track::getFormat() {
    if (mSource == NULL) {
        return NULL;
    }

    return mSource->getFormat();
}

sp<MetaData> ESExtractor::Track::getQueueFormat() {
    return mQueueFormat;
}



status_t ESExtractor::Track::read(
        MediaBuffer **buffer, const ReadOptions *options) {

    if (mSource == NULL) {
        return NO_INIT;
    }

    status_t err = OK;
    status_t finalResult = OK;
    while (!mSource->hasBufferAvailable(&finalResult)) {
        ALOGD("mSource has no Buffer Available,finalResult:%d",finalResult);
        if (finalResult != OK) {
            ALOGD("read:ERROR_END_OF_STREAM this=%p",this );
            mExtractor->setDequeueState(true);
            mSource->clear(true);
            return ERROR_END_OF_STREAM;
        }
    	err = mExtractor->feedMore();
        if (err != OK) {
            ALOGD("read:signalEOS this=%p",this );
            mSource->signalEOS(err);
        }
    }
    return mSource->read(buffer, options);

}

sp<AnotherPacketSource> ESExtractor::Track::getSource() {
    if (mSource == NULL) {
        return NULL;
    }
    return mSource;
}
void ESExtractor::Track::setSource(sp<MetaData> meta) {
    mSource = new AnotherPacketSource(meta);
}


bool ESExtractor::Track::isVideo() {

    switch (mStreamType) {
        case ATSParser::STREAMTYPE_H264:
        case ATSParser::STREAMTYPE_MPEG1_VIDEO:
        case ATSParser::STREAMTYPE_MPEG2_VIDEO:
        case ATSParser::STREAMTYPE_MPEG4_VIDEO:
        case ATSParser::STREAMTYPE_HEVC:
            return true;

        default:
            return false;
    }
}

bool ESExtractor::Track::isAudio() {

    switch (mStreamType) {
        case ATSParser::STREAMTYPE_MPEG1_AUDIO:
        case ATSParser::STREAMTYPE_MPEG2_AUDIO:
        case ATSParser::STREAMTYPE_MPEG2_AUDIO_ADTS:
        case ATSParser::STREAMTYPE_AUDIO_PSLPCM:
            return true;

        default:
            return false;
    }
}

void ESExtractor::Track::signalDiscontinuity(const bool bKeepFormat){

    if (!mExtractor->getDequeueState()) {

      return;
    
    }

    if (mSource.get()) {

      mSource->clear(bKeepFormat);
    
    }
    else {

      ALOGE("[error]this stream has no source\n");
    
    }

    return;
}

status_t ESExtractor::Track::dequeueAccessUnit(sp<ABuffer> &mAccessUnit){

    switch (mStreamType) {
        case ATSParser::STREAMTYPE_H264:
            break;

        case ATSParser::STREAMTYPE_MPEG1_VIDEO:

        case ATSParser::STREAMTYPE_MPEG2_VIDEO:
            ALOGV("dequeueAccessUnit:dequeueAccessUnitMPEGVideo");
            return dequeueAccessUnitMPEGVideo(mAccessUnit);

        case ATSParser::STREAMTYPE_MPEG4_VIDEO:
            break;
        case ATSParser::STREAMTYPE_HEVC:
            return dequeueAccessUnitHEVC(mAccessUnit);
    }
    return ERROR_MALFORMED;
}

status_t ESExtractor::Track::dequeueAccessUnitMPEGVideo(sp<ABuffer> &mAccessUnit) {
    const uint8_t *data = mExtractor->mBuffer->data();
    size_t size = mExtractor->mBuffer->size();
    bool sawPictureStart = false;
    int pprevStartCode = -1;
    int prevStartCode = -1;
    int currentStartCode = -1;

    size_t offset = 0;
    size_t lastGOPOff = -1;

    while (offset + 3 < size) {
        if (U24_AT(data + offset) != 0x000001) {
            ++offset;
            continue;
        }
        pprevStartCode = prevStartCode;
        prevStartCode = currentStartCode;
        currentStartCode = data[offset + 3];
        ALOGV("pprevStartCode:0x%x,prevStartCode:0x%x,currentStartCode:0x%x,offset:%d",pprevStartCode,prevStartCode,currentStartCode,offset);

        if (currentStartCode == 0xb3 && mQueueFormat == NULL) {
            memmove(mExtractor->mBuffer->data(), mExtractor->mBuffer->data() + offset, size - offset);
            size -= offset;
            offset = 0;
            mExtractor->mBuffer->setRange(0, size);
        }

        if ((prevStartCode == 0xb3 && currentStartCode != 0xb5)
                || (pprevStartCode == 0xb3 && prevStartCode == 0xb5)) {
            // seqHeader without/with extension

            if (mQueueFormat == NULL) {
                CHECK_GE(size, 7u);

                unsigned width =
                    (data[4] << 4) | data[5] >> 4;

                unsigned height =
                    ((data[5] & 0x0f) << 8) | data[6];
                
                mQueueFormat = new MetaData;
                mQueueFormat->setCString(kKeyMIMEType, MEDIA_MIMETYPE_VIDEO_MPEG2);
                mQueueFormat->setInt32(kKeyWidth, (int32_t)width);
                mQueueFormat->setInt32(kKeyHeight, (int32_t)height);

                ALOGI("found MPEG2 video codec config (%d x %d)", width, height);

                sp<ABuffer> csd = new ABuffer(offset);
                memcpy(csd->data(), data, offset);

                memmove(mExtractor->mBuffer->data(),
                        mExtractor->mBuffer->data() + offset,
                        mExtractor->mBuffer->size() - offset);

                mExtractor->mBuffer->setRange(0, mExtractor->mBuffer->size() - offset);
                size -= offset;
                offset = 0;

                sp<ABuffer> esds = MakeMPEGVideoESDS(csd);
                mQueueFormat->setData(kKeyESDS, kTypeESDS, esds->data(), esds->size());
                ALOGV("dequeueAccessUnitMPEGVideo:get mQueueFormat,return GETFORMATDONE");
                return GETFORMATDONE;
            }
        }

        if (mQueueFormat != NULL && (currentStartCode == 0x00 || (sawPictureStart && currentStartCode == 0xB7))) { //ALPS00473447
            // Picture start
            ALOGV("dequeueAccessUnitMPEGVideo:Picture start");
            if (!sawPictureStart) {
                sawPictureStart = true;
            } else {
                mAccessUnit = new ABuffer(offset);
                memcpy(mAccessUnit->data(), data, offset);

                memmove(mExtractor->mBuffer->data(),
                        mExtractor->mBuffer->data() + offset,
                        mExtractor->mBuffer->size() - offset);

                mExtractor->mBuffer->setRange(0, mExtractor->mBuffer->size() - offset);

                offset = 0;
                mAccessUnit->meta()->setInt32("invt", (int32_t)true);
                mAccessUnit->meta()->setInt64("timeUs", 0);
                ALOGV("dequeueAccessUnitMPEGVideo:return OPCONTINUE");
                return GETAUDONE;
            }
        }
        ++offset;
    }
    ALOGV("dequeueAccessUnitMPEGVideo:not enought for an AU,return -EAGAIN");
    return -EAGAIN;
}


status_t ESExtractor::Track::dequeueAccessUnitHEVC(sp<ABuffer> &mAccessUnit) {
    const uint8_t *data = mExtractor->mBuffer->data();
    size_t size = mExtractor->mBuffer->size();
    ALOGV("dequeueAccessUnitHEVC Start,size:%d",size);

	Vector<NALPosition> nals;
	size_t totalSize = 0;

	status_t err;
	const uint8_t *nalStart;
	size_t nalSize;
	bool foundSlice = false;
	size_t preVCLIndex = -1;
    unsigned preSliceType = 0, curSliceType = 0;

	while ((err = getNextNALUnit4(&data, &size, &nalStart, &nalSize)) == OK) {

        CHECK_GT(nalSize, 0u);

		unsigned sliceType = (nalStart[0] & 0x7E)>>1;

        preSliceType = curSliceType;
        curSliceType = sliceType;

		bool flush = false;
        
        if (sliceType == 35) {
             /*delimiter starting an AU*/
            if (foundSlice && (preVCLIndex != -1)) {
 		        flush = true;
            }
            foundSlice = true;
        } 
        else if ((sliceType >= 0 && sliceType <= 3) || (sliceType >= 16 && sliceType <= 21)) {
            //slice_segment_layer_rbsp()
            /*first_slice_segment_in_pic_flag*/
            unsigned firstSlice = (nalStart[2] & 0x80)>>7;
			
            if (firstSlice) {//firstSlice indicates an new AU
				if (foundSlice && (preVCLIndex != -1)) {
				    flush = true;
				}
				foundSlice = true;
            }
        }

		if (flush) {
			// The access unit will contain all nal units up to, but excluding
			// the current one, separated by 0x00 0x00 0x00 0x01 startcodes.

			ALOGV("[%s]flush sliceType = %u preVCLIndex:%d nals.size():%d",
				__FUNCTION__, sliceType, preVCLIndex, nals.size());
			
			size_t dstOffset = 0;
            for (size_t i = 0; i < preVCLIndex; ++i) {
                const NALPosition &pos = nals.itemAt(i);
                dstOffset += pos.nalSize + 4;
            }

			mAccessUnit = new ABuffer(dstOffset);
	        ALOGV("totalSize:%d, auSize:%d",totalSize,dstOffset);

			dstOffset = 0;
			for (size_t i = 0; i < preVCLIndex; ++i) {
				const NALPosition &pos = nals.itemAt(i);
				ALOGV("[hevc]pos:%d size:%d", pos.nalOffset, pos.nalSize);
				memcpy(mAccessUnit->data() + dstOffset, "\x00\x00\x00\x01", 4);
                memcpy(mAccessUnit->data() + dstOffset + 4,
						mExtractor->mBuffer->data() + pos.nalOffset,
						pos.nalSize);    
				dstOffset += pos.nalSize + 4;
			}
	
			const NALPosition &pos = nals.itemAt(preVCLIndex - 1);
			size_t nextScan = pos.nalOffset + pos.nalSize;
    	    ALOGV("nalOffset:%d, nalSize:%d, nextScan:%d",pos.nalOffset,pos.nalSize,nextScan);

			memmove(mExtractor->mBuffer->data(),
					mExtractor->mBuffer->data() + nextScan,
					mExtractor->mBuffer->size() - nextScan);
	
			mExtractor->mBuffer->setRange(0, mExtractor->mBuffer->size() - nextScan);

            mAccessUnit->meta()->setInt64("timeUs", (int64_t)0);
            mAccessUnit->meta()->setInt32("invt", (int32_t)true);

			/*
			if (mQueueFormat == NULL) {
			  	mQueueFormat = MakeHEVCMetaData(mAccessUnit);
		    }
		    */

            ALOGV("dequeueAccessUnitHEVC:return GETAUDONE");
            return GETAUDONE;
		}

		NALPosition pos;
		pos.nalOffset = nalStart - mExtractor->mBuffer->data();
		pos.nalSize = nalSize;
	
		nals.push_back(pos);
		totalSize += nalSize;

		if (0 <= sliceType && sliceType <= 31) { /*VCL unit*/
			preVCLIndex = nals.size();//position of preVCLunit
		}
		ALOGV("nals add sliceType:%u, nals.size:%u", sliceType, nals.size());

        if (32 == curSliceType) {

            CHECK_EQ(nals.size(),1u);
            
            const NALPosition &pos = nals.itemAt(nals.size() - 1);
            
            mAccessUnit = new ABuffer(pos.nalSize + 4); 
            ALOGV("[hevc]VPS pos:%d size:%d", pos.nalOffset, pos.nalSize);
            memcpy(mAccessUnit->data(), "\x00\x00\x00\x01", 4);
            memcpy(mAccessUnit->data() + 4,
                    mExtractor->mBuffer->data() + pos.nalOffset,
                    pos.nalSize);
            
            mAccessUnit->meta()->setInt64("timeUs", (int64_t)0);
            mAccessUnit->meta()->setInt32("invt", (int32_t)true);
            
            size_t nextScan = pos.nalOffset + pos.nalSize;
            memmove(mExtractor->mBuffer->data(),
                    mExtractor->mBuffer->data() + nextScan,
                    mExtractor->mBuffer->size() - nextScan);

			mExtractor->mBuffer->setRange(0, mExtractor->mBuffer->size() - nextScan);

            return GETAUDONE;
        }

        else if (34 == curSliceType && 33 == preSliceType) {

            CHECK_EQ(nals.size(),2u);
            
            size_t auSize = 8 + totalSize;
            mAccessUnit = new ABuffer(auSize); 

            size_t dstOffset = 0;
            for (size_t i = 0; i < nals.size(); ++i) {
				const NALPosition &pos = nals.itemAt(i);
				ALOGV("[hevc]SPS/PPS pos:%d size:%d", pos.nalOffset, pos.nalSize);
				memcpy(mAccessUnit->data() + dstOffset, "\x00\x00\x00\x01", 4);
                memcpy(mAccessUnit->data() + dstOffset + 4,
						mExtractor->mBuffer->data() + pos.nalOffset,
						pos.nalSize);    
				dstOffset += pos.nalSize + 4;
            }

            mAccessUnit->meta()->setInt64("timeUs", (int64_t)0);
            mAccessUnit->meta()->setInt32("invt", (int32_t)true);

            const NALPosition &pos = nals.itemAt(nals.size() - 1);
            size_t nextScan = pos.nalOffset + pos.nalSize;
			memmove(mExtractor->mBuffer->data(),
					mExtractor->mBuffer->data() + nextScan,
					mExtractor->mBuffer->size() - nextScan);

			mExtractor->mBuffer->setRange(0, mExtractor->mBuffer->size() - nextScan);
            
			if (mQueueFormat == NULL) {
			  	mQueueFormat = MakeHEVCMetaData(mAccessUnit);
		    }

            return GETAUDONE;
        }

	}
    
    CHECK_EQ(err, (status_t)-EAGAIN);
    if (err == -EAGAIN) {
        ALOGV("dequeueAccessUnitHEVC:return -EAGAIN");
        return -EAGAIN;
    }
    
    return ERROR_MALFORMED;

}


////////////////////////////////////////////////////////////////////////////////

ESExtractor::WrappedTrack::WrappedTrack(
        const sp<ESExtractor> &extractor, const sp<Track> &track)
    : mWExtractor(extractor),
      mWTrack(track) {
}

ESExtractor::WrappedTrack::~WrappedTrack() {
}

status_t ESExtractor::WrappedTrack::start(MetaData *params) {
    return mWTrack->start(params);
}

status_t ESExtractor::WrappedTrack::stop() {
    return mWTrack->stop();
}

sp<MetaData> ESExtractor::WrappedTrack::getFormat() {
    return mWTrack->getFormat();
}

status_t ESExtractor::WrappedTrack::read(
        MediaBuffer **buffer, const ReadOptions *options) {
    return mWTrack->read(buffer, options);
}
////////////////////////////////////////////////////////////////////////////////

bool SniffES(
        const sp<DataSource> &source, String8 *mimeType, float *confidence,
        sp<AMessage> *) {

    uint32_t SniffLength = ES_SNIFF_LENGTH;
    sp<ABuffer> SniffBuffer = new ABuffer(SniffLength);
    ALOGD("+SniffES in");

    int32_t length = source->readAt(0, SniffBuffer->data(), SniffLength);
    if (length < 0) {
        ALOGD("SniffES:Read file failed");
        return false;
    }

    const uint8_t *data = SniffBuffer->data();
    size_t size = SniffBuffer->size();
    size_t offset = 0;
    bool MatchPoint = 0;
    while (offset + 3 < size) {
        if (memcmp(&data[offset], "\x00\x00\x01", 3)){//MPEG1 MPEG2 MPEG4 H.264 H.265 VC1 Video
            ++offset;
            continue;
        }
        MatchPoint = 1;
        break;
    }

    if (MatchPoint != 0) {
        *confidence = 0.01f;
        mimeType->setTo(MEDIA_MIMETYPE_ELEMENT_STREAM);
        return true;
    }
    return false;
}

}  // namespace android

