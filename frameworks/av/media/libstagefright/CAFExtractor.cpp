/*
 * Copyright (C) 2009 The Android Open Source Project
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
#define LOG_TAG "CAFExtractor"
#include <utils/Log.h>
#include <cutils/xlog.h>

#include "include/CAFExtractor.h"

#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/DataSource.h>
#include <media/stagefright/MediaBufferGroup.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/Utils.h>
#include <utils/String8.h>


#include "TableOfContentThread.h"
#include "include/AwesomePlayer.h"

namespace android 
{
class CAFSource : public MediaSource, public TableOfContentThread
{
public:
    CAFSource(
        const sp<DataSource> &source, 
        const sp<MetaData> &meta, 
        off_t pakt_pos, 
        off_t data_pos,
        CAFAudioFormat       desc_info,
        CAFPacketTableHeader pakt_info,
        CAFSpecificConfig    kuki_info);

	virtual status_t getNextFramePos(off_t *curPos, off_t *pNextPos, int64_t *frameTsUs);
	virtual status_t  sendDurationUpdateEvent(int64_t duration);
    virtual status_t start(MetaData *params = NULL);
    virtual status_t stop();

    virtual sp<MetaData> getFormat();

    virtual status_t read(
            MediaBuffer **buffer, const ReadOptions *options = NULL);

protected:
    virtual ~CAFSource();

private:
    sp<DataSource> mDataSource;
    sp<MetaData>   mMeta;
	AwesomePlayer *mObserver;

    int64_t mSeekFrame;
    off_t mOffset;
	off_t mPaktOffset;
    int64_t mCurrentTimeUs;
    bool mStarted;
    MediaBufferGroup *mGroup;

    uint32_t mBytesPerPacket;
    uint32_t mFramesPerPacket;
	
    //kuki
    uint32_t mSampleRate;
	uint32_t mNumChannels;
    //pakt
    int64_t mNumberPackets;
    int64_t mNumberValidFrames;
    int32_t mPrimingFrames;
    int32_t mRemainderFrames;

	uint32_t mMaxFramesPerPacket;

    CAFSource(const CAFSource &);
    CAFSource &operator=(const CAFSource &);
};

////////////////////////////////////////////////////////////////////////////////

static bool isValidChunk(char c)
{
	if (c >= 'a' && c <= 'z')
	{
		return true;
	} else if (c == ' ' || c == '.') {
		return true;
	} else {
		return false;
	}
}

static bool getCafFormatInfo(
		const sp<DataSource> &source, off64_t offset, CAFAudioFormat *desc_info, CAFPacketTableHeader *pakt_info, 
		CAFSpecificConfig *kuki_info, off64_t *data_pos, off64_t *pakt_pos, off64_t *kuki_pos)
{
    uint32_t chunkType = 0;
	int64_t  chunkSize = 0;
    uint8_t  readBuffer[36] = {0};
	uint32_t CookieId = 0;

	int      numChunk = 0;
	int64_t  extraSize = 0;

    while (1)
    {
    	if (source->readAt(offset, readBuffer, 12) != 12) {
            ALOGD("No more chunk type found");
            break;
        }
		offset += 12;
        chunkType = U32_AT(readBuffer);
		chunkSize = (int64_t)U64_AT(readBuffer + 4);
		char c1, c2, c3, c4;
		c1 = (chunkType>>24)&0xff;
		c2 = (chunkType>>16)&0xff;
		c3 = (chunkType>>8) &0xff;
		c4 = (chunkType>>0) &0xff;
		ALOGD("@offset: %lld Parsing chunk_type: %c%c%c%c chunk_size: %lld", 
                 offset-12, c1, c2, c3, c4,chunkSize);
		if (!isValidChunk(c1) || !isValidChunk(c2) ||
			!isValidChunk(c3) || !isValidChunk(c4))
		{
			ALOGE(" Invalid bitstream.");
            return false;
		}
		if (numChunk > 20)
		{
			ALOGE(" Can not find required Chunks.");
            return false;
		}
        switch (chunkType)
        {
            case FOURCC('d','e','s','c'):
				if (chunkSize != 32)
            	{
                	ALOGE("  alac's desc chunkSize must be 32");
                	return false;
            	}
                if (source->readAt(offset, readBuffer, 32) != 32) {
            		ALOGE("Parsing Desc Chunk Error.");
            		return false;
        		}
				desc_info->nFormatID         = U32_AT(readBuffer + 8);
				desc_info->nFormatFlags      = U32_AT(readBuffer + 12);
    			desc_info->nBytesPerPacket   = U32_AT(readBuffer + 16);
    			desc_info->nFramesPerPacket  = U32_AT(readBuffer + 20);
    			desc_info->nChannelsPerFrame = U32_AT(readBuffer + 24);
    			desc_info->nBitsPerChannel   = U32_AT(readBuffer + 28);
				offset += chunkSize;
                break;
			case FOURCC('p','a','k','t'):
				if (source->readAt(offset, readBuffer, 24) != 24) {
            		ALOGE("Parsing Pakt Chunk Error.");
            		return false;
        		}
				pakt_info->nNumberPackets     = (int64_t)U64_AT(readBuffer);
    			pakt_info->nNumberValidFrames = (int64_t)U64_AT(readBuffer + 8);
    			pakt_info->nPrimingFrames     = (int32_t)U32_AT(readBuffer + 16);
    			pakt_info->nRemainderFrames   = (int32_t)U32_AT(readBuffer + 20);
				
				*pakt_pos = offset + 24;
				offset += chunkSize;
				break;
			case FOURCC('d','a','t','a'):
				*data_pos  = offset + 4;
				offset += chunkSize;
				break;
			case FOURCC('k','u','k','i'):
				extraSize = chunkSize;
				*kuki_pos = offset;
				while (extraSize > 24)
				{
					if (source->readAt(*kuki_pos, readBuffer, 8) != 8) {
            			ALOGE("Parsing Kuki Chunk Error.");
            			return false;
        			}
					CookieId = U32_AT(readBuffer + 4);
					if (CookieId == FOURCC('f','r','m','a') ||
						CookieId == FOURCC('a','l','a','c'))
					{
						*kuki_pos += 12;
						extraSize -= 12;
					}
					else
					{
						break;
					}
				}
				
				if (extraSize < 24)
				{
    				ALOGE("  get alac's kuki chunk_data error");
					return false;
				}
	
				if (source->readAt(*kuki_pos, readBuffer, 24) != 24)
    			{
    				ALOGE("  get alac's kuki chunk_data error");
					return false;
				}
				kuki_info->frameLength       = U32_AT(readBuffer);
				kuki_info->compatibleVersion = readBuffer[4];
				kuki_info->bitDepth          = readBuffer[5];
				kuki_info->pb                = readBuffer[6];
				kuki_info->mb                = readBuffer[7];
				kuki_info->kb                = readBuffer[8];
				kuki_info->numChannels       = readBuffer[9];
				kuki_info->maxRun            = U16_AT(readBuffer + 10);
				kuki_info->maxFrameBytes     = U32_AT(readBuffer + 12);
				kuki_info->avgBitRate        = U32_AT(readBuffer + 16);
				kuki_info->sampleRate        = U32_AT(readBuffer + 20);

				offset += chunkSize;
				break;
            default:
				ALOGD("Skip Parsing");
				offset += chunkSize;
                break;
        }
		numChunk++;
    }
    return true;
}

CAFExtractor::CAFExtractor(
	const sp<DataSource> &source, const sp<AMessage> &_meta)
    : mDataSource(source),
      mInitCheck(NO_INIT),
      mPaktStartPos(0),      
      mDataStartPos(0) 
{
	bool success = true;
	int64_t pos;
    mMeta = new MetaData;
	sp<AMessage> meta = _meta;
	
    ALOGD("CAFExtractor+");
	if (meta == NULL) {
        String8 mimeType;
        float confidence;

        if (!SniffCAF(mDataSource, &mimeType, &confidence, &meta)) {
            return;
        }
    }

	int64_t offset;
    CHECK(meta->findInt64("offset", &offset));

	off64_t cookiePos;
	uint8_t specficCAFCodecData[24];
	if (!getCafFormatInfo(source, offset, &mCAFDescChunkInfo, &mCAFPaktChunkInfo,
		&mCAFKukiChunkInfo, &mDataStartPos, &mPaktStartPos, &cookiePos))
    {
        return;
    }

	ALOGD("  mFormatID:          0x%x",       mCAFDescChunkInfo.nFormatID);	
	ALOGD("  mFormatFlags:       0x%x",       mCAFDescChunkInfo.nFormatFlags);
	ALOGD("  mChannelsPerFrame:  %u",         mCAFDescChunkInfo.nChannelsPerFrame);
	ALOGD("  mBytesPerPacket:    %u",         mCAFDescChunkInfo.nBytesPerPacket);
	ALOGD("  mFramesPerPacket:   %u",         mCAFDescChunkInfo.nFramesPerPacket);
	ALOGD("  mBitsPerChannel:    %u",         mCAFDescChunkInfo.nBitsPerChannel);
	
	ALOGD("  mNumberPackets:     %lld",       mCAFPaktChunkInfo.nNumberPackets);
	ALOGD("  mNumberValidFrames: %lld",       mCAFPaktChunkInfo.nNumberValidFrames);
	ALOGD("  mPrimingFrames:     %d",         mCAFPaktChunkInfo.nPrimingFrames);
	ALOGD("  mRemainderFrames:   %d",         mCAFPaktChunkInfo.nRemainderFrames);

	ALOGD("  Cookie Info frameLength:       %u",     mCAFKukiChunkInfo.frameLength);
	ALOGD("  Cookie Info compatibleVersion: %u",     mCAFKukiChunkInfo.compatibleVersion);
	ALOGD("  Cookie Info bitDepth:          %u",     mCAFKukiChunkInfo.bitDepth);
	ALOGD("  Cookie Info pb:                %u",     mCAFKukiChunkInfo.pb);
	ALOGD("  Cookie Info mb:                %u",     mCAFKukiChunkInfo.mb);
	ALOGD("  Cookie Info kb:                %u",     mCAFKukiChunkInfo.kb);
	ALOGD("  Cookie Info numChannels:       %u",     mCAFKukiChunkInfo.numChannels);
	ALOGD("  Cookie Info maxRun:            %u",     mCAFKukiChunkInfo.maxRun);
	ALOGD("  Cookie Info maxFrameBytes:     %u",     mCAFKukiChunkInfo.maxFrameBytes);
	ALOGD("  Cookie Info avgBitRate:        %u",     mCAFKukiChunkInfo.avgBitRate);
	ALOGD("  Cookie Info sampleRate:        %u",     mCAFKukiChunkInfo.sampleRate);
	
	ALOGD("  mPaktStartPos: %lld", mPaktStartPos);
	ALOGD("  mDataStartPos: %lld", mDataStartPos);

	if (mCAFDescChunkInfo.nFormatID == FOURCC('a','l','a','c'))
    {
    	mMeta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_AUDIO_ALAC);             
    } else {
    	ALOGE("  unsupport mFormatID");
		return;
	}

	if (mCAFDescChunkInfo.nBytesPerPacket != 0 && 
		mCAFDescChunkInfo.nFramesPerPacket != 0)
	{
    	ALOGE("  unsupport");
		return;
	}

	if (mDataSource->readAt(cookiePos, specficCAFCodecData, sizeof(specficCAFCodecData)) != sizeof(specficCAFCodecData))
    {
    	ALOGE("  get alac's kuki chunk_data error");
		return;
	}

	if (mCAFKukiChunkInfo.numChannels == 0 ||
		mCAFKukiChunkInfo.sampleRate == 0)
	{
    	ALOGE("  Invalid bitstream, numChannel and Samplerate can not be zero.");
		return;
	}
	
	mMeta->setData(kKeyALACC, 0,      specficCAFCodecData, sizeof(specficCAFCodecData));
	mMeta->setInt32(kKeyNumSamples,   mCAFDescChunkInfo.nFramesPerPacket);
	mMeta->setInt32(kKeyChannelCount, mCAFKukiChunkInfo.numChannels);
	mMeta->setInt32(kKeySampleRate,   mCAFKukiChunkInfo.sampleRate);
	mMeta->setInt32(kKeyBitWidth,     mCAFKukiChunkInfo.bitDepth);
	mMeta->setInt64(kKeyDuration,     
		(mCAFPaktChunkInfo.nNumberValidFrames * 1000000L / mCAFKukiChunkInfo.sampleRate));

    ALOGD("CAFExtractor-");
    mInitCheck = OK;
}

CAFExtractor::~CAFExtractor() {
}

sp<MetaData> CAFExtractor::getMetaData() {
    //TODO
    sp<MetaData> meta = new MetaData;

    if (mInitCheck != OK) {
        return meta;
    }

    if (mCAFDescChunkInfo.nFormatID == FOURCC('a','l','a','c'))
    {        
        meta->setCString(kKeyMIMEType, "audio/alac");
    }

    return meta;
}

size_t CAFExtractor::countTracks() {
    return mInitCheck == OK ? 1 : 0;
}

sp<MediaSource> CAFExtractor::getTrack(size_t index) {
    if (mInitCheck != OK || index != 0) {
        return NULL;
    }

    return new CAFSource(mDataSource, mMeta, mPaktStartPos, mDataStartPos,
		mCAFDescChunkInfo, mCAFPaktChunkInfo, mCAFKukiChunkInfo);
}

sp<MetaData> CAFExtractor::getTrackMetaData(size_t index, uint32_t flags) {
    if (mInitCheck != OK || index != 0) {
        return NULL;
    }

    return mMeta;
}

////////////////////////////////////////////////////////////////////////////////

CAFSource::CAFSource(
        const sp<DataSource> &source, 
        const sp<MetaData> &meta, 
        off_t pakt_pos, 
        off_t data_pos,
        CAFAudioFormat       desc_info,
        CAFPacketTableHeader pakt_info,
        CAFSpecificConfig    kuki_info)
    : mDataSource(source),
      mMeta(meta),
      mPaktOffset(pakt_pos),
      mOffset(data_pos),
      mSeekFrame(0),
      mCurrentTimeUs(0),
      mStarted(false),
      mGroup(NULL) {

	void *pAwe = NULL;
    meta->findPointer(kKeyDataSourceObserver, &pAwe);
    mObserver = (AwesomePlayer *) pAwe;

	mFramesPerPacket    = desc_info.nFramesPerPacket;
	mBytesPerPacket     = desc_info.nBytesPerPacket;
	mNumberPackets      = pakt_info.nNumberPackets;
	mNumberValidFrames  = pakt_info.nNumberValidFrames;
	mPrimingFrames      = pakt_info.nPrimingFrames;
	mRemainderFrames    = pakt_info.nRemainderFrames;

	mSampleRate         = kuki_info.sampleRate;
	mNumChannels        = kuki_info.numChannels;
	mMaxFramesPerPacket = kALACDefaultFrameSize;

	//TableOfContent
	isCAFFormat   = true;
	mFirstPaktPos = pakt_pos;
	mSeekPaktPos  = pakt_pos;
}

CAFSource::~CAFSource() {
    if (mStarted) {
        stop();
    }
}

status_t CAFSource::getNextFramePos(off_t *pCurpos, off_t *pNextPos, int64_t *frameTsUs)
{
	if (mBytesPerPacket != 0 && mFramesPerPacket == 0)
	{
		uint8_t data = 0;
		size_t numSamples = 0;
 		off_t paktPos = mCurPaktPos;
		ALOGV("CurPos = %d, CurPaktPos = %d", *pCurpos, mCurPaktPos);

		if (mDataSource->readAt(*pCurpos, &data, 1) != 1)
		{
			ALOGD("ALAC: end of stream!!");
			return ERROR_END_OF_STREAM;
		}
		while (1)
		{
			if (mDataSource->readAt(paktPos, &data, 1) != 1)
			{
				ALOGE("Get pakt chunk data error");
				return ERROR_IO;
			}
			paktPos++;

			if (data < 0x80)
			{
				numSamples = (numSamples << 7) | data;
				break;
 			} else {
 				numSamples = (numSamples << 7) | (data & 0x7f);
			}
		}
		*frameTsUs = (float)numSamples / mSampleRate * 1000000L;
		*pNextPos = *pCurpos + mBytesPerPacket;
		mNextPaktPos = paktPos;
		ALOGV("frameSize = %d, NextPos = %d, frameUs = %lld, CurPaktPos = %d, NextPaktPos = %d", 
			mBytesPerPacket, *pNextPos, *frameTsUs, mCurPaktPos, mNextPaktPos);
	}
	else if (mBytesPerPacket == 0 && mFramesPerPacket != 0)
	{
		uint8_t data = 0;
		size_t frameSize = 0;
 		off_t paktPos = mCurPaktPos;
		ALOGV("CurPos = %d, CurPaktPos = %d", *pCurpos, mCurPaktPos);

		if (mDataSource->readAt(*pCurpos, &data, 1) != 1)
		{
			ALOGD("ALAC: end of stream!!");
			return ERROR_END_OF_STREAM;
		}
		while (1)
		{
			if (mDataSource->readAt(paktPos, &data, 1) != 1)
			{
				ALOGE("Get pakt chunk data error");
				return ERROR_IO;
			}
			paktPos++;

			if (data < 0x80)
			{
				frameSize = (frameSize << 7) | data;
				break;
 			} else {
 				frameSize = (frameSize << 7) | (data & 0x7f);
			}
		}
		*frameTsUs = (float)mFramesPerPacket / mSampleRate * 1000000L;
		*pNextPos = *pCurpos + frameSize;
		mNextPaktPos = paktPos;
		ALOGV("frameSize = %d, NextPos = %d, frameUs = %lld, CurPaktPos = %d, NextPaktPos = %d", 
			frameSize, *pNextPos, *frameTsUs, mCurPaktPos, mNextPaktPos);
	}
	else if (mBytesPerPacket == 0 && mFramesPerPacket == 0)
	{
		uint8_t data = 0;
		size_t frameSize = 0, numSamples = 0;
 		off_t paktPos = mCurPaktPos;
		ALOGV("CurPos = %d, CurPaktPos = %d", *pCurpos, mCurPaktPos);

		if (mDataSource->readAt(*pCurpos, &data, 1) != 1)
		{
			ALOGD("ALAC: end of stream!!");
			return ERROR_END_OF_STREAM;
		}
		//packet size
		while (1)
		{
			if (mDataSource->readAt(paktPos, &data, 1) != 1)
			{
				ALOGE("Get pakt chunk data error");
				return ERROR_IO;
			}
			paktPos++;

			if (data < 0x80)
			{
				frameSize = (frameSize << 7) | data;
				break;
 			} else {
 				frameSize = (frameSize << 7) | (data & 0x7f);
			}
		}
		//number of frames(samples)
		while (1)
		{
			if (mDataSource->readAt(paktPos, &data, 1) != 1)
			{
				ALOGE("Get pakt chunk data error");
				return ERROR_IO;
			}
			paktPos++;

			if (data < 0x80)
			{
				numSamples = (numSamples << 7) | data;
				break;
 			} else {
 				numSamples = (numSamples << 7) | (data & 0x7f);
			}
		}
		*frameTsUs = (float)numSamples / mSampleRate * 1000000L;
		*pNextPos = *pCurpos + frameSize;
		mNextPaktPos = paktPos;
		ALOGV("frameSize = %d, NextPos = %d, frameUs = %lld, CurPaktPos = %d, NextPaktPos = %d", 
			frameSize, *pNextPos, *frameTsUs, mCurPaktPos, mNextPaktPos);
	}
	return OK;
}


status_t CAFSource::sendDurationUpdateEvent(int64_t duration)
{
    if (mObserver)
    {
        ALOGD("ALAC Seek Table duration %lld", duration);
        mObserver->postDurationUpdateEvent(duration);
    }

    return OK;
}

status_t CAFSource::start(MetaData *params) {
    CHECK(!mStarted);

    //TODO
    startTOCThread(mOffset);
    mGroup = new MediaBufferGroup;
	if (mFramesPerPacket != 0)
	{
		mGroup->add_buffer(new MediaBuffer(mFramesPerPacket * mNumChannels * 4));
	}
	else
	{
		mGroup->add_buffer(new MediaBuffer(mMaxFramesPerPacket * mNumChannels * 2));
	}
    mStarted = true;

    return OK;
}

status_t CAFSource::stop() {
    CHECK(mStarted);

    //TODO
    stopTOCThread();
    delete mGroup;
    mGroup = NULL;

    mStarted = false;
    return OK;
}

sp<MetaData> CAFSource::getFormat() {
    return mMeta;
}

status_t CAFSource::read(
        MediaBuffer **out, const ReadOptions *options) {
    *out = NULL;

    int64_t seekFrame = 0;
    int64_t seekTimeUs = 0;
    size_t frameSize = 0, numFrames = 0;
    ReadOptions::SeekMode mode;
    if (options != NULL && options->getSeekTo(&seekTimeUs, &mode)) {
        status_t status = getFramePos(seekTimeUs, &mCurrentTimeUs, &mOffset, false);
		if (status != OK)
		{
			return status;
		}
		mPaktOffset = mSeekPaktPos;
        ALOGD("seek = true, mCurrentTimeUs = %lld, mOffset = %d, mPaktOffset = %d", mCurrentTimeUs, mOffset, mPaktOffset);

    }

	if (mPaktOffset < 0)
	{
		return ERROR_OUT_OF_RANGE;
	}

	if (mBytesPerPacket != 0 && mFramesPerPacket == 0)
	{
		uint8_t data = 0;
		numFrames = 0;
		while (1)
		{
			if (mDataSource->readAt(mPaktOffset, &data, 1) != 1)
			{
				ALOGE("Get pakt chunk data error");
				return ERROR_IO;
			}
			mPaktOffset++;

			if (data < 0x80)
			{
				numFrames = (numFrames << 7) | data;
				break;
 			} else {
 				numFrames = (numFrames << 7) | (data & 0x7f);
			}
		}
		frameSize = mBytesPerPacket;
		mCurrentTimeUs += (float)numFrames / mSampleRate * 1000000L;
	}
	else if (mBytesPerPacket == 0 && mFramesPerPacket != 0)
	{
		uint8_t data = 0;
		frameSize = 0;
		while (1)
		{
			if (mDataSource->readAt(mPaktOffset, &data, 1) != 1)
			{
				ALOGE("Get pakt chunk data error");
				return ERROR_IO;
			}
			mPaktOffset++;

			if (data < 0x80)
			{
				frameSize = (frameSize << 7) | data;
				break;
 			} else {
 				frameSize = (frameSize << 7) | (data & 0x7f);
			}
		}
		
		mCurrentTimeUs += (float)mFramesPerPacket / mSampleRate * 1000000L;
		ALOGV("  frameSize = %d", frameSize);
	}
	else if (mBytesPerPacket == 0 && mFramesPerPacket == 0)
	{
		uint8_t data = 0;
		frameSize = 0;
		numFrames = 0;
		//parse frame size
		while (1)
		{
			if (mDataSource->readAt(mPaktOffset, &data, 1) != 1)
			{
				ALOGE("Get pakt chunk data error");
				return ERROR_IO;
			}
			mPaktOffset++;

			if (data < 0x80)
			{
				frameSize = (frameSize << 7) | data;
				break;
 			} else {
 				frameSize = (frameSize << 7) | (data & 0x7f);
			}
		}
		//parse number of frames
		while (1)
		{
			if (mDataSource->readAt(mPaktOffset, &data, 1) != 1)
			{
				ALOGE("Get pakt chunk data error");
				return ERROR_IO;
			}
			mPaktOffset++;

			if (data < 0x80)
			{
				numFrames = (numFrames << 7) | data;
				break;
 			} else {
 				numFrames = (numFrames << 7) | (data & 0x7f);
			}
		}
		mCurrentTimeUs += (float)numFrames / mSampleRate * 1000000L;
	}
    
	ALOGV("  mCurrentTimeUs = %lld, mFramesPerPacket = %d, mSampleRate = %d", mCurrentTimeUs, mFramesPerPacket, mSampleRate);

	if (mOffset < 0)
	{
		return ERROR_OUT_OF_RANGE;
	}
	
    MediaBuffer *buffer;
    status_t err = mGroup->acquire_buffer(&buffer);
    if (err != OK) {
        ALOGE("  acquire_buffer error");
        return err;
    }

    ssize_t n = mDataSource->readAt(mOffset, buffer->data(), frameSize);

	if (n <= 0)
	{
        buffer->release();
        buffer = NULL;
        return ERROR_END_OF_STREAM;
	} else if (n != (ssize_t)frameSize) {
        buffer->release();
        buffer = NULL;
        ALOGE("  ERROR_IO");
        return ERROR_IO;
    }

    buffer->set_range(0, frameSize);
    buffer->meta_data()->setInt64(kKeyTime, mCurrentTimeUs);
    buffer->meta_data()->setInt32(kKeyIsSyncFrame, 1);
	
    mOffset += frameSize; 

    *out = buffer;

    return OK;
}

////////////////////////////////////////////////////////////////////////////////

bool SniffCAF(
        const sp<DataSource> &source, String8 *mimeType, 
         float *confidence, sp<AMessage> *meta) {
    off64_t pos = 0;
    unsigned char header[8];

	uint16_t nFileVersion;
	uint16_t nFileFlags;

    if (source->readAt(pos, header, sizeof(header)) != sizeof(header)
		|| memcmp("caff", header, 4)) 
	{
        return false;
    }

	pos += 8;
	nFileVersion = *(uint16_t *)(header + 4);
	nFileFlags   = *(uint16_t *)(header + 6);
	ALOGV("SniffCAF:FileVersion = %d, FileFlags = %d", nFileVersion, nFileFlags);
	
	*meta = new AMessage;
	(*meta)->setInt64("offset", pos);
	
    *mimeType = MEDIA_MIMETYPE_AUDIO_ALAC;
    *confidence = 0.5;
	ALOGD("SniffCAF success.");
	
    return true;
}

}  // namespace android
