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
#define LOG_TAG "MPEG4Writer"

#include <arpa/inet.h>
#include <fcntl.h>
#include <inttypes.h>
#include <pthread.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include <utils/Log.h>

#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/MPEG4Writer.h>
#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/Utils.h>
#include <media/mediarecorder.h>
#include <cutils/properties.h>

#include "include/ESDS.h"

#define WARN_UNLESS(condition, message, ...) \
( (CONDITION(condition)) ? false : ({ \
    ALOGW("Condition %s failed "  message, #condition, ##__VA_ARGS__); \
    true; \
}))

#ifdef MTK_AOSP_ENHANCEMENT
#include <linux/rtpm_prio.h>
#include <media/stagefright/CameraSource.h>
#include <VideoQualityController.h>
#include <MPEG4FileCacheWriter.h>
#include <limits.h>
#include <media/stagefright/MediaCodecSource.h>

#define MPEG4WRITER_USE_XLOG
#ifdef MPEG4WRITER_USE_XLOG
#include <cutils/xlog.h>
#undef ALOGE
#undef ALOGW
#undef ALOGI
#undef ALOGD
#undef ALOGV
#define ALOGE XLOGE
#define ALOGW XLOGW
#define ALOGI XLOGI
#define ALOGD XLOGD
#define ALOGV XLOGV
#endif

#ifdef MTB_SUPPORT
#define ATRACE_TAG ATRACE_TAG_MTK_VR
#include <utils/Trace.h>
#endif
#endif


namespace android {

static const int64_t kMinStreamableFileSizeInBytes = 5 * 1024 * 1024;
static const int64_t kMax32BitFileSize = 0x00ffffffffLL; // 2^32-1 : max FAT32
                                                         // filesystem file size
                                                         // used by most SD cards
static const uint8_t kNalUnitTypeSeqParamSet = 0x07;
static const uint8_t kNalUnitTypePicParamSet = 0x08;
static const int64_t kInitialDelayTimeUs     = 700000LL;

#ifdef MTK_AOSP_ENHANCEMENT
// SEI
static const uint8_t kNalUnitTypeSEI = 0x06;
// HEVC NAL Type
//#ifdef MTK_VIDEO_HEVC_SUPPORT
static const uint8_t kNalUnitTypeVdoParamSet_HEVC = 0x20; // 0x33
static const uint8_t kNalUnitTypeSeqParamSet_HEVC = 0x21; // 0x33;
static const uint8_t kNalUnitTypePicParamSet_HEVC = 0x22; // 0x34;
//#endif

#define EXT_MIN_FREE_MEM_THRESHOLD 		100*1024*1024LL
#define LARGE_BITRATE_THRESHOLD 		30000000  // 30Mbits

#define TRACK_STTS_TABLE_BUFFER_SIZE	1024*1024
#define TRACK_STSZ_TABLE_BUFFER_SIZE	1024*1024

#define TRACK_SKIPNOTIFY_RATIO 			3

//added by hai.li @2010-12-25 to check file size limit accurately
#define META_DATA_HEADER_RESERVE_BYTES 	150
#define TRACK_HEADER_RESERVE_BYTES 		500
//added by hai.li @2010-12-25 to check file size limit accurately

static const int64_t kMax32BitDuration = 0x007fffffffLL;
#endif

class MPEG4Writer::Track {
public:
    Track(MPEG4Writer *owner, const sp<MediaSource> &source, size_t trackId);

    ~Track();

    status_t start(MetaData *params);
    status_t stop();
    status_t pause();
    bool reachedEOS();

    int64_t getDurationUs() const;
    int64_t getEstimatedTrackSizeBytes() const;
    void writeTrackHeader(bool use32BitOffset = true);
    void bufferChunk(int64_t timestampUs);
    bool isAvc() const { return mIsAvc; }
    bool isAudio() const { return mIsAudio; }
    bool isMPEG4() const { return mIsMPEG4; }
    void addChunkOffset(off64_t offset);
    int32_t getTrackId() const { return mTrackId; }
    status_t dump(int fd, const Vector<String16>& args) const;

private:
    enum {
        kMaxCttsOffsetTimeUs = 1000000LL,  // 1 second
        kSampleArraySize = 1000,
    };

    // A helper class to handle faster write box with table entries
    template<class TYPE>
    struct ListTableEntries {
        ListTableEntries(uint32_t elementCapacity, uint32_t entryCapacity)
            : mElementCapacity(elementCapacity),
            mEntryCapacity(entryCapacity),
            mTotalNumTableEntries(0),
            mNumValuesInCurrEntry(0),
#ifdef MTK_AOSP_ENHANCEMENT
			mTempFileSize(0),
			mTempFile(NULL),
			mTempFileName(),
#endif
            mCurrTableEntriesElement(NULL) {
            CHECK_GT(mElementCapacity, 0);
            CHECK_GT(mEntryCapacity, 0);
        }

        // Free the allocated memory.
        ~ListTableEntries() {
            while (!mTableEntryList.empty()) {
                typename List<TYPE *>::iterator it = mTableEntryList.begin();
                delete[] (*it);
                mTableEntryList.erase(it);
#ifdef MTK_AOSP_ENHANCEMENT
				if (mTempFile != NULL) {
					releaseTempFile();
				}
#endif
            }
        }

        // Replace the value at the given position by the given value.
        // There must be an existing value at the given position.
        // @arg value must be in network byte order
        // @arg pos location the value must be in.
        void set(const TYPE& value, uint32_t pos) {
            CHECK_LT(pos, mTotalNumTableEntries * mEntryCapacity);
#ifdef MTK_AOSP_ENHANCEMENT
			if (mTempFile != NULL) {
				return setEx(value, pos);
			}
#endif

            typename List<TYPE *>::iterator it = mTableEntryList.begin();
            uint32_t iterations = (pos / (mElementCapacity * mEntryCapacity));
            while (it != mTableEntryList.end() && iterations > 0) {
                ++it;
                --iterations;
            }
            CHECK(it != mTableEntryList.end());
            CHECK_EQ(iterations, 0);

            (*it)[(pos % (mElementCapacity * mEntryCapacity))] = value;
        }

        // Get the value at the given position by the given value.
        // @arg value the retrieved value at the position in network byte order.
        // @arg pos location the value must be in.
        // @return true if a value is found.
        bool get(TYPE& value, uint32_t pos) const {
            if (pos >= mTotalNumTableEntries * mEntryCapacity) {
                return false;
            }
#ifdef MTK_AOSP_ENHANCEMENT
			if (mTempFile != NULL) {
				return getEx(value, pos);
			}
#endif

            typename List<TYPE *>::iterator it = mTableEntryList.begin();
            uint32_t iterations = (pos / (mElementCapacity * mEntryCapacity));
            while (it != mTableEntryList.end() && iterations > 0) {
                ++it;
                --iterations;
            }
            CHECK(it != mTableEntryList.end());
            CHECK_EQ(iterations, 0);

            value = (*it)[(pos % (mElementCapacity * mEntryCapacity))];
            return true;
        }

        // Store a single value.
        // @arg value must be in network byte order.
        void add(const TYPE& value) {
            CHECK_LT(mNumValuesInCurrEntry, mElementCapacity);
            uint32_t nEntries = mTotalNumTableEntries % mElementCapacity;
            uint32_t nValues  = mNumValuesInCurrEntry % mEntryCapacity;
            if (nEntries == 0 && nValues == 0) {
#ifdef MTK_AOSP_ENHANCEMENT
				if (needNewCurrTable()) {
#endif
                mCurrTableEntriesElement = new TYPE[mEntryCapacity * mElementCapacity];
                CHECK(mCurrTableEntriesElement != NULL);
                mTableEntryList.push_back(mCurrTableEntriesElement);
#ifdef MTK_AOSP_ENHANCEMENT
				}
#endif
            }

            uint32_t pos = nEntries * mEntryCapacity + nValues;
            mCurrTableEntriesElement[pos] = value;

            ++mNumValuesInCurrEntry;
            if ((mNumValuesInCurrEntry % mEntryCapacity) == 0) {
                ++mTotalNumTableEntries;
                mNumValuesInCurrEntry = 0;
            }
        }

        // Write out the table entries:
        // 1. the number of entries goes first
        // 2. followed by the values in the table enties in order
        // @arg writer the writer to actual write to the storage
        void write(MPEG4Writer *writer) const {
            CHECK_EQ(mNumValuesInCurrEntry % mEntryCapacity, 0);
            uint32_t nEntries = mTotalNumTableEntries;
            writer->writeInt32(nEntries);
#ifdef MTK_AOSP_ENHANCEMENT
			if (mTempFile != NULL) {
				writeTempFile(writer, nEntries);
			}
#endif
            for (typename List<TYPE *>::iterator it = mTableEntryList.begin();
                it != mTableEntryList.end(); ++it) {
                CHECK_GT(nEntries, 0);
                if (nEntries >= mElementCapacity) {
#ifdef MTK_AOSP_ENHANCEMENT
					if (mTempFile != NULL)
						ALOGD("[%s] is use temp file, should not come here", mTempFileName.string());
#endif
                    writer->write(*it, sizeof(TYPE) * mEntryCapacity, mElementCapacity);
                    nEntries -= mElementCapacity;
                } else {
                    writer->write(*it, sizeof(TYPE) * mEntryCapacity, nEntries);
                    break;
                }
            }
        }

        // Return the number of entries in the table.
        uint32_t count() const { return mTotalNumTableEntries; }

#ifdef MTK_AOSP_ENHANCEMENT
		void popTopTableEntry(){
			if((mTotalNumTableEntries <= 0) || mTableEntryList.empty()){
				ALOGE("mTotalNumTableEntries=%d,mTableEntryList.size=%d", mTotalNumTableEntries,mTableEntryList.size());
				return;
			}
			mTotalNumTableEntries--;

			if((0 == (mTotalNumTableEntries % mElementCapacity))) {

				if (mTempFile != NULL && mTempFileSize>0) {
					CHECK_GE(mTempFileSize, sizeof(TYPE)*mEntryCapacity*mElementCapacity);
					fseeko(mTempFile, mTempFileSize-sizeof(TYPE)*mEntryCapacity*mElementCapacity, SEEK_SET);
					ALOGD("[%s] popTopTableEntry fread+, mTempFileSize=%d", mTempFileName.string(), mTempFileSize);
					int ret = fread(mCurrTableEntriesElement, sizeof(TYPE)*mEntryCapacity, mElementCapacity, mTempFile);
					ALOGD("[%s] popTopTableEntry fread-, read size=%d, ret=%d", mTempFileName.string(), sizeof(TYPE)*mEntryCapacity*mElementCapacity, ret);
					mTempFileSize -= sizeof(TYPE)*mEntryCapacity*mElementCapacity;
				}
				else if (!mTableEntryList.empty()) {
					//delete the top node of mTableEntryList and free it's memory
					delete[] (*(--mTableEntryList.end()));
	                mTableEntryList.erase(--mTableEntryList.end());

					//update mCurrTableEntriesElement to equal the mTableEntryList end-1 node
					mCurrTableEntriesElement = *(--mTableEntryList.end());
				}
			}
		}

		uint32_t getEntryCapacity(){
			return mEntryCapacity;
		}

		void setTempFileName(const char* tempFileName) {
			if (tempFileName != NULL) {
				//mTempFile = fopen(tempFileName, "w+b");
				mTempFileName.setTo(tempFileName);
				ALOGD("set temp file name [%s]", mTempFileName.string());
				mTempFileSize = 0;
			}
		}

		void setEx(const TYPE& value, uint32_t pos) {
			if( pos < mTempFileSize/sizeof(TYPE)) {
				fseeko(mTempFile, pos*sizeof(TYPE), SEEK_SET);
				int ret = fwrite(&value, sizeof(TYPE), 1, mTempFile);
				ALOGD("[%s] set value(%x) in file, ret=%d", mTempFileName.string(), value, ret);
				fseeko(mTempFile, 0, SEEK_END);
			}
			else {
				mCurrTableEntriesElement[(pos % (mElementCapacity * mEntryCapacity))] = value;
				ALOGD("[%s] set value(%x) in mCurrTableEntriesElement", mTempFileName.string(), value);
			}
		}	

		bool getEx(TYPE& value, uint32_t pos) const {
			if( pos < mTempFileSize/sizeof(TYPE)) {
				fseeko(mTempFile, pos*sizeof(TYPE), SEEK_SET);
				int ret = fread(&value, sizeof(TYPE), 1, mTempFile);
				ALOGD("[%s] get value(%x) in file, ret=%d", mTempFileName.string(), value, ret);
				fseeko(mTempFile, 0, SEEK_END);
			}
			else {
				value = mCurrTableEntriesElement[(pos % (mElementCapacity * mEntryCapacity))];
				ALOGD("[%s] get value(%x) in mCurrTableEntriesElement", mTempFileName.string(), value);
			}
			return true;
		}
		
		bool needNewCurrTable() {
			if (mCurrTableEntriesElement != NULL && mTempFile == NULL && mTempFileName != "") {
				ALOGD("[%s] fopen+ ", mTempFileName.string());
				mTempFile = fopen(mTempFileName, "w+b");
				ALOGD("[%s] fopen- mTempFile = %p", mTempFileName.string(), mTempFile);
			}
			if (mCurrTableEntriesElement != NULL && mTempFile != NULL) {
				int ret = fwrite(mCurrTableEntriesElement, sizeof(TYPE)*mEntryCapacity, mElementCapacity, mTempFile);
				mTempFileSize += sizeof(TYPE)*mEntryCapacity*mElementCapacity;
				ALOGD("[%s] fwrite ret=%d", mTempFileName.string(), ret);
				return false;
			}
			return true;
		}
		
		void writeTempFile(MPEG4Writer *writer, uint32_t &nEntries) const {
			size_t tempFileSize = mTempFileSize;
			fseeko(mTempFile, 0, SEEK_SET);
			void* tempBuf = malloc(sizeof(TYPE)*mEntryCapacity*mElementCapacity);
			while (tempFileSize != 0)
			{
				ALOGD("[%s] fread+, tempFileSize=%d", mTempFileName.string(), tempFileSize);
				int ret = fread(tempBuf, sizeof(TYPE)*mEntryCapacity*mElementCapacity, 1, mTempFile);
				ALOGD("[%s] fread-, ret=%d", mTempFileName.string(), ret);
				writer->write(tempBuf, sizeof(TYPE)*mEntryCapacity*mElementCapacity);
				tempFileSize -= sizeof(TYPE)*mEntryCapacity*mElementCapacity;
				nEntries -= mElementCapacity;
			}
			free(tempBuf);
		}

		void releaseTempFile() {
			fclose(mTempFile);
			mTempFile = NULL;
			remove(mTempFileName.string());
		}
			
#endif

    private:
        uint32_t         mElementCapacity;  // # entries in an element
        uint32_t         mEntryCapacity;    // # of values in each entry
        uint32_t         mTotalNumTableEntries;
        uint32_t         mNumValuesInCurrEntry;  // up to mEntryCapacity
#ifdef MTK_AOSP_ENHANCEMENT
		size_t 			 mTempFileSize;
		FILE*			 mTempFile;
		String8 		 mTempFileName;
#endif
        TYPE             *mCurrTableEntriesElement;
        mutable List<TYPE *>     mTableEntryList;

        DISALLOW_EVIL_CONSTRUCTORS(ListTableEntries);
    };



    MPEG4Writer *mOwner;
    sp<MetaData> mMeta;
    sp<MediaSource> mSource;
    volatile bool mDone;
    volatile bool mPaused;
    volatile bool mResumed;
    volatile bool mStarted;
    bool mIsAvc;
    bool mIsAudio;
    bool mIsMPEG4;
    int32_t mTrackId;
    int64_t mTrackDurationUs;
    int64_t mMaxChunkDurationUs;

    int64_t mEstimatedTrackSizeBytes;
    int64_t mMdatSizeBytes;
    int32_t mTimeScale;

    pthread_t mThread;


    List<MediaBuffer *> mChunkSamples;

    bool                mSamplesHaveSameSize;
    ListTableEntries<uint32_t> *mStszTableEntries;

    ListTableEntries<uint32_t> *mStcoTableEntries;
    ListTableEntries<off64_t> *mCo64TableEntries;
    ListTableEntries<uint32_t> *mStscTableEntries;
    ListTableEntries<uint32_t> *mStssTableEntries;
    ListTableEntries<uint32_t> *mSttsTableEntries;
    ListTableEntries<uint32_t> *mCttsTableEntries;

    int64_t mMinCttsOffsetTimeUs;
    int64_t mMaxCttsOffsetTimeUs;

    // Sequence parameter set or picture parameter set
    struct AVCParamSet {
        AVCParamSet(uint16_t length, const uint8_t *data)
            : mLength(length), mData(data) {}

        uint16_t mLength;
        const uint8_t *mData;
    };
    List<AVCParamSet> mSeqParamSets;
    List<AVCParamSet> mPicParamSets;
    uint8_t mProfileIdc;
    uint8_t mProfileCompatible;
    uint8_t mLevelIdc;

    void *mCodecSpecificData;
    size_t mCodecSpecificDataSize;
    bool mGotAllCodecSpecificData;
    bool mTrackingProgressStatus;

    bool mReachedEOS;
    int64_t mStartTimestampUs;
    int64_t mStartTimeRealUs;
    int64_t mFirstSampleTimeRealUs;
    int64_t mPreviousTrackTimeUs;
    int64_t mTrackEveryTimeDurationUs;

    // Update the audio track's drift information.
    void updateDriftTime(const sp<MetaData>& meta);

    int32_t getStartTimeOffsetScaledTime() const;

    static void *ThreadWrapper(void *me);
    status_t threadEntry();

    const uint8_t *parseParamSet(
        const uint8_t *data, size_t length, int type, size_t *paramSetLen);

    status_t makeAVCCodecSpecificData(const uint8_t *data, size_t size);
    status_t copyAVCCodecSpecificData(const uint8_t *data, size_t size);
    status_t parseAVCCodecSpecificData(const uint8_t *data, size_t size);

    // Track authoring progress status
    void trackProgressStatus(int64_t timeUs, status_t err = OK);
    void initTrackingProgressStatus(MetaData *params);

    void getCodecSpecificDataFromInputFormatIfPossible();

    // Determine the track time scale
    // If it is an audio track, try to use the sampling rate as
    // the time scale; however, if user chooses the overwrite
    // value, the user-supplied time scale will be used.
    void setTimeScale();

    // Simple validation on the codec specific data
    status_t checkCodecSpecificData() const;
    int32_t mRotation;

    void updateTrackSizeEstimate();
    void addOneStscTableEntry(size_t chunkId, size_t sampleId);
    void addOneStssTableEntry(size_t sampleId);

    // Duration is time scale based
    void addOneSttsTableEntry(size_t sampleCount, int32_t timescaledDur);
    void addOneCttsTableEntry(size_t sampleCount, int32_t timescaledDur);

    bool isTrackMalFormed() const;
    void sendTrackSummary(bool hasMultipleTracks);

    // Write the boxes
    void writeStcoBox(bool use32BitOffset);
    void writeStscBox();
    void writeStszBox();
    void writeStssBox();
    void writeSttsBox();
    void writeCttsBox();
    void writeD263Box();
    void writePaspBox();
    void writeAvccBox();
    void writeUrlBox();
    void writeDrefBox();
    void writeDinfBox();
    void writeDamrBox();
    void writeMdhdBox(uint32_t now);
    void writeSmhdBox();
    void writeVmhdBox();
    void writeHdlrBox();
    void writeTkhdBox(uint32_t now);
    void writeMp4aEsdsBox();
    void writeMp4vEsdsBox();
    void writeAudioFourCCBox();
    void writeVideoFourCCBox();
    void writeStblBox(bool use32BitOffset);

    Track(const Track &);
    Track &operator=(const Track &);

#ifdef MTK_AOSP_ENHANCEMENT
public:
	bool 		isTrackExited();
	int32_t 	getMultiSliceBSInfo(){return mMultiSliceBS;} //for support multi-slice case

private:
	void 		init();
	void 		resume();
	void   		initStart(MetaData *params);
	void   		pauseEx();
	void   		waitTrackThreadExit();
	void 		signalResumed(bool hasMultipleTracks);
	status_t 	parseAVCCodecSpecificDataByNALSize(const uint8_t *data, size_t size);
	void 		syncMoovStartTime();
	void 		signalTrackThreadExit();
	void 		atraceDataRead();
	void    	getFirstPauseTimeUs(MediaBuffer *buffer, int64_t& firstPauseFrameTimeUs );
	bool 		hasCodecInfo();
	void        getMultiSliceBS(MediaBuffer *buffer);
	void  		checkVideoHeader(MediaBuffer *buffer);
	bool 		needStripStartcode(); 
	bool 		waitNewFrameForResume(MediaBuffer *buffer, int64_t firstPauseFrameTimeUs);
	bool 		isSEIData();
	MediaBuffer* getSEIData(MediaBuffer *buffer);
	void 		writeUdtaBox();
	void 		writeLvpoBox();
#ifdef WRITER_ENABLE_EDTS_BOX
	void 		writeEdtsBox();
#endif
	bool 		needDropAudioFrame(int64_t timestampUs);

private:
    int64_t 	mPauseTimeUs;
	int64_t 	mTrackStartTimeOffsetUs;  // for first video frame timestamp > first audio frame timestamp
	Mutex 		mLock;
	Condition 	mThreadExitCondition;
	bool 		mThreadExit;
	size_t 		mAVCSEIDataSize;
	void* 		mAVCSEIData;
	bool 		mPausedFirstFrame;
	Condition 	mPauseCondition;
	int32_t 	mLivePhotoTagValue;
	int32_t 	mMultiSliceBS;//indicate multi-slice Stream
	int32_t 	mTrackBitRate;

#ifdef SD_FULL_PROTECT
public:

	void		addWritedChunkNum();
	void		decWritedChunkNum();
	size_t		getWritedChunkNum() { return mWritedChunkNum; }
	int64_t 	getEstimatedTrackHeaderSizeBytes();
	void		updateTrackHeader();
private:
	size_t 		mWritedChunkNum;
#endif

#ifdef MTK_SLOW_MOTION_VIDEO_SUPPORT
	int32_t 	mSlowMotionSpeedValue;
	bool 		mDirectLink;
	int32_t 	mNonRefPFreq;
	void 		writeSmsvBox();
#endif

//#ifdef MTK_VIDEO_HEVC_SUPPORT
public:
	bool 		isHevc() const { return mIsHevc; }
private:
	// members for hevc
	bool 		mIsHevc;
	List<AVCParamSet> mVdoParamSets;
	// funs for hevc
	const uint8_t* 	parseHEVCParamSet(
		const uint8_t *data, size_t length, int type, size_t *paramSetLen); 
	status_t 	copyHEVCCodecSpecificData(const uint8_t *data, size_t size);
	status_t 	parseHEVCCodecSpecificData(const uint8_t *data, size_t size);
	status_t 	parseHEVCCodecSpecificDataByNALSize(const uint8_t *data, size_t size);
	status_t 	makeHEVCCodecSpecificData(const uint8_t *data, size_t size);
	void 		writeHvccBox();
//#endif
#endif
};

MPEG4Writer::MPEG4Writer(const char *filename)
    : mFd(-1),
      mInitCheck(NO_INIT),
      mIsRealTimeRecording(true),
      mUse4ByteNalLength(true),
      mUse32BitOffset(true),
      mIsFileSizeLimitExplicitlyRequested(false),
      mPaused(false),
      mStarted(false),
      mWriterThreadStarted(false),
      mOffset(0),
      mMdatOffset(0),
      mEstimatedMoovBoxSize(0),
      mInterleaveDurationUs(1000000),
      mLatitudex10000(0),
      mLongitudex10000(0),
      mAreGeoTagsAvailable(false),
      mStartTimeOffsetMs(-1) {

    mFd = open(filename, O_CREAT | O_LARGEFILE | O_TRUNC | O_RDWR, S_IRUSR | S_IWUSR);
    if (mFd >= 0) {
        mInitCheck = OK;
    }
#ifdef MTK_AOSP_ENHANCEMENT
	if(mFd < 0)
	   ALOGW("MPEG4Writer Constructor file open fail:%s (%d)",strerror(errno),errno);
	ALOGD("MPEG4Writer Constructor filename=%s",filename);
	init();
#endif

}

MPEG4Writer::MPEG4Writer(int fd)
    : mFd(dup(fd)),
      mInitCheck(mFd < 0? NO_INIT: OK),
      mIsRealTimeRecording(true),
      mUse4ByteNalLength(true),
      mUse32BitOffset(true),
      mIsFileSizeLimitExplicitlyRequested(false),
      mPaused(false),
      mStarted(false),
      mWriterThreadStarted(false),
      mOffset(0),
      mMdatOffset(0),
      mEstimatedMoovBoxSize(0),
      mInterleaveDurationUs(1000000),
      mLatitudex10000(0),
      mLongitudex10000(0),
      mAreGeoTagsAvailable(false),
      mStartTimeOffsetMs(-1) {
#ifdef MTK_AOSP_ENHANCEMENT
    ALOGD("MPEG4Writer Constructor mFd=%d",mFd);
	init();
#endif
}

MPEG4Writer::~MPEG4Writer() {
#ifdef MTK_AOSP_ENHANCEMENT
    ALOGD("~MPEG4Writer");
#endif
    reset();

    while (!mTracks.empty()) {
        List<Track *>::iterator it = mTracks.begin();
        delete *it;
        (*it) = NULL;
        mTracks.erase(it);
    }
    mTracks.clear();
#ifdef MTK_AOSP_ENHANCEMENT
	releaseEx();
#endif
}

status_t MPEG4Writer::dump(
        int fd, const Vector<String16>& args) {
    const size_t SIZE = 256;
    char buffer[SIZE];
    String8 result;
    snprintf(buffer, SIZE, "   MPEG4Writer %p\n", this);
    result.append(buffer);
    snprintf(buffer, SIZE, "     mStarted: %s\n", mStarted? "true": "false");
    result.append(buffer);
    ::write(fd, result.string(), result.size());
    for (List<Track *>::iterator it = mTracks.begin();
         it != mTracks.end(); ++it) {
        (*it)->dump(fd, args);
    }
    return OK;
}

status_t MPEG4Writer::Track::dump(
        int fd, const Vector<String16>& /* args */) const {
    const size_t SIZE = 256;
    char buffer[SIZE];
    String8 result;
    snprintf(buffer, SIZE, "     %s track\n", mIsAudio? "Audio": "Video");
    result.append(buffer);
    snprintf(buffer, SIZE, "       reached EOS: %s\n",
            mReachedEOS? "true": "false");
    result.append(buffer);
    snprintf(buffer, SIZE, "       frames encoded : %d\n", mStszTableEntries->count());
    result.append(buffer);
    snprintf(buffer, SIZE, "       duration encoded : %" PRId64 " us\n", mTrackDurationUs);
    result.append(buffer);
    ::write(fd, result.string(), result.size());
    return OK;
}

status_t MPEG4Writer::addSource(const sp<MediaSource> &source) {
    Mutex::Autolock l(mLock);
    if (mStarted) {
        ALOGE("Attempt to add source AFTER recording is started");
        return UNKNOWN_ERROR;
    }

    // At most 2 tracks can be supported.
    if (mTracks.size() >= 2) {
        ALOGE("Too many tracks (%zu) to add", mTracks.size());
        return ERROR_UNSUPPORTED;
    }

    CHECK(source.get() != NULL);

    // A track of type other than video or audio is not supported.
    const char *mime;
    source->getFormat()->findCString(kKeyMIMEType, &mime);
    bool isAudio = !strncasecmp(mime, "audio/", 6);
    bool isVideo = !strncasecmp(mime, "video/", 6);
    if (!isAudio && !isVideo) {
        ALOGE("Track (%s) other than video or audio is not supported",
            mime);
        return ERROR_UNSUPPORTED;
    }

    // At this point, we know the track to be added is either
    // video or audio. Thus, we only need to check whether it
    // is an audio track or not (if it is not, then it must be
    // a video track).

    // No more than one video or one audio track is supported.
    for (List<Track*>::iterator it = mTracks.begin();
         it != mTracks.end(); ++it) {
        if ((*it)->isAudio() == isAudio) {
            ALOGE("%s track already exists", isAudio? "Audio": "Video");
            return ERROR_UNSUPPORTED;
        }
    }

    // This is the first track of either audio or video.
    // Go ahead to add the track.
    Track *track = new Track(this, source, 1 + mTracks.size());
    mTracks.push_back(track);
#ifdef MTK_AOSP_ENHANCEMENT
	if(!track->isAudio()) {
		mVideoQualityController = new VideoQualityController(this, source);
		CHECK(mVideoQualityController != NULL);
	}
#endif

    return OK;
}

status_t MPEG4Writer::startTracks(MetaData *params) {
    if (mTracks.empty()) {
        ALOGE("No source added");
        return INVALID_OPERATION;
    }

    for (List<Track *>::iterator it = mTracks.begin();
         it != mTracks.end(); ++it) {
        status_t err = (*it)->start(params);

        if (err != OK) {
            for (List<Track *>::iterator it2 = mTracks.begin();
                 it2 != it; ++it2) {
                (*it2)->stop();
            }

            return err;
        }
    }
    return OK;
}

int64_t MPEG4Writer::estimateMoovBoxSize(int32_t bitRate) {
    // This implementation is highly experimental/heurisitic.
    //
    // Statistical analysis shows that metadata usually accounts
    // for a small portion of the total file size, usually < 0.6%.

    // The default MIN_MOOV_BOX_SIZE is set to 0.6% x 1MB / 2,
    // where 1MB is the common file size limit for MMS application.
    // The default MAX _MOOV_BOX_SIZE value is based on about 3
    // minute video recording with a bit rate about 3 Mbps, because
    // statistics also show that most of the video captured are going
    // to be less than 3 minutes.

    // If the estimation is wrong, we will pay the price of wasting
    // some reserved space. This should not happen so often statistically.
    static const int32_t factor = mUse32BitOffset? 1: 2;
    static const int64_t MIN_MOOV_BOX_SIZE = 3 * 1024;  // 3 KB
    static const int64_t MAX_MOOV_BOX_SIZE = (180 * 3000000 * 6LL / 8000);
    int64_t size = MIN_MOOV_BOX_SIZE;

    // Max file size limit is set
    if (mMaxFileSizeLimitBytes != 0 && mIsFileSizeLimitExplicitlyRequested) {
        size = mMaxFileSizeLimitBytes * 6 / 1000;
    }

    // Max file duration limit is set
    if (mMaxFileDurationLimitUs != 0) {
        if (bitRate > 0) {
            int64_t size2 =
                ((mMaxFileDurationLimitUs * bitRate * 6) / 1000 / 8000000);
            if (mMaxFileSizeLimitBytes != 0 && mIsFileSizeLimitExplicitlyRequested) {
                // When both file size and duration limits are set,
                // we use the smaller limit of the two.
                if (size > size2) {
                    size = size2;
                }
            } else {
                // Only max file duration limit is set
                size = size2;
            }
        }
    }

    if (size < MIN_MOOV_BOX_SIZE) {
        size = MIN_MOOV_BOX_SIZE;
    }

    // Any long duration recording will be probably end up with
    // non-streamable mp4 file.
    if (size > MAX_MOOV_BOX_SIZE) {
        size = MAX_MOOV_BOX_SIZE;
    }

    ALOGI("limits: %" PRId64 "/%" PRId64 " bytes/us, bit rate: %d bps and the"
         " estimated moov size %" PRId64 " bytes",
         mMaxFileSizeLimitBytes, mMaxFileDurationLimitUs, bitRate, size);
    return factor * size;
}

status_t MPEG4Writer::start(MetaData *param) {
#ifdef MTK_AOSP_ENHANCEMENT
	ALOGD("start ++");
#endif
    if (mInitCheck != OK) {
        return UNKNOWN_ERROR;
    }

    /*
     * Check mMaxFileSizeLimitBytes at the beginning
     * since mMaxFileSizeLimitBytes may be implicitly
     * changed later for 32-bit file offset even if
     * user does not ask to set it explicitly.
     */
    if (mMaxFileSizeLimitBytes != 0) {
        mIsFileSizeLimitExplicitlyRequested = true;
    }

    int32_t use64BitOffset;
    if (param &&
        param->findInt32(kKey64BitFileOffset, &use64BitOffset) &&
        use64BitOffset) {
        mUse32BitOffset = false;
    }

    if (mUse32BitOffset) {
        // Implicit 32 bit file size limit
        if (mMaxFileSizeLimitBytes == 0) {
            mMaxFileSizeLimitBytes = kMax32BitFileSize;
        }

        // If file size is set to be larger than the 32 bit file
        // size limit, treat it as an error.
        if (mMaxFileSizeLimitBytes > kMax32BitFileSize) {
            ALOGW("32-bit file size limit (%" PRId64 " bytes) too big. "
                 "It is changed to %" PRId64 " bytes",
                mMaxFileSizeLimitBytes, kMax32BitFileSize);
            mMaxFileSizeLimitBytes = kMax32BitFileSize;
        }
    }

    int32_t use2ByteNalLength;
    if (param &&
        param->findInt32(kKey2ByteNalLength, &use2ByteNalLength) &&
        use2ByteNalLength) {
        mUse4ByteNalLength = false;
    }

    int32_t isRealTimeRecording;
    if (param && param->findInt32(kKeyRealTimeRecording, &isRealTimeRecording)) {
        mIsRealTimeRecording = isRealTimeRecording;
    }
	
#ifdef MTK_AOSP_ENHANCEMENT
	if (!(mStarted && mPaused))
#endif
    mStartTimestampUs = -1;

    if (mStarted) {
        if (mPaused) {
            mPaused = false;
#ifdef MTK_AOSP_ENHANCEMENT
			return resume(param);
#else
            return startTracks(param);
#endif
        }
        return OK;
    }

#ifdef MTK_AOSP_ENHANCEMENT
	initStart(param); 
#endif

    if (!param ||
        !param->findInt32(kKeyTimeScale, &mTimeScale)) {
        mTimeScale = 1000;
    }
    CHECK_GT(mTimeScale, 0);
    ALOGV("movie time scale: %d", mTimeScale);

    /*
     * When the requested file size limit is small, the priority
     * is to meet the file size limit requirement, rather than
     * to make the file streamable. mStreamableFile does not tell
     * whether the actual recorded file is streamable or not.
     */
    mStreamableFile =
        (mMaxFileSizeLimitBytes != 0 &&
         mMaxFileSizeLimitBytes >= kMinStreamableFileSizeInBytes);

#ifdef MTK_AOSP_ENHANCEMENT
	mStreamableFile = false; //use mStreamableFile  as the streamable file optional, usually make it false
#endif

    /*
     * mWriteMoovBoxToMemory is true if the amount of data in moov box is
     * smaller than the reserved free space at the beginning of a file, AND
     * when the content of moov box is constructed. Note that video/audio
     * frame data is always written to the file but not in the memory.
     *
     * Before stop()/reset() is called, mWriteMoovBoxToMemory is always
     * false. When reset() is called at the end of a recording session,
     * Moov box needs to be constructed.
     *
     * 1) Right before a moov box is constructed, mWriteMoovBoxToMemory
     * to set to mStreamableFile so that if
     * the file is intended to be streamable, it is set to true;
     * otherwise, it is set to false. When the value is set to false,
     * all the content of the moov box is written immediately to
     * the end of the file. When the value is set to true, all the
     * content of the moov box is written to an in-memory cache,
     * mMoovBoxBuffer, util the following condition happens. Note
     * that the size of the in-memory cache is the same as the
     * reserved free space at the beginning of the file.
     *
     * 2) While the data of the moov box is written to an in-memory
     * cache, the data size is checked against the reserved space.
     * If the data size surpasses the reserved space, subsequent moov
     * data could no longer be hold in the in-memory cache. This also
     * indicates that the reserved space was too small. At this point,
     * _all_ moov data must be written to the end of the file.
     * mWriteMoovBoxToMemory must be set to false to direct the write
     * to the file.
     *
     * 3) If the data size in moov box is smaller than the reserved
     * space after moov box is completely constructed, the in-memory
     * cache copy of the moov box is written to the reserved free
     * space. Thus, immediately after the moov is completedly
     * constructed, mWriteMoovBoxToMemory is always set to false.
     */
    mWriteMoovBoxToMemory = false;
    mMoovBoxBuffer = NULL;
    mMoovBoxBufferOffset = 0;

    writeFtypBox(param);
    mFreeBoxOffset = mOffset;

    if (mEstimatedMoovBoxSize == 0) {
        int32_t bitRate = -1;
        if (param) {
            param->findInt32(kKeyBitRate, &bitRate);
        }
        mEstimatedMoovBoxSize = estimateMoovBoxSize(bitRate);
    }
    CHECK_GE(mEstimatedMoovBoxSize, 8);
    if (mStreamableFile) {
        // Reserve a 'free' box only for streamable file
#if defined(MTK_AOSP_ENHANCEMENT) && defined(USE_FILE_CACHE)
		mCacheWriter->seek(mFreeBoxOffset, SEEK_SET);
#else
        lseek64(mFd, mFreeBoxOffset, SEEK_SET);
#endif
        writeInt32(mEstimatedMoovBoxSize);
        write("free", 4);
        mMdatOffset = mFreeBoxOffset + mEstimatedMoovBoxSize;
    } else {
        mMdatOffset = mOffset;
    }

    mOffset = mMdatOffset;
#if defined(MTK_AOSP_ENHANCEMENT) && defined(USE_FILE_CACHE)
	mCacheWriter->seek(mMdatOffset, SEEK_SET);
#else
    lseek64(mFd, mMdatOffset, SEEK_SET);
#endif
    if (mUse32BitOffset) {
        write("????mdat", 8);
    } else {
        write("\x00\x00\x00\x01mdat????????", 16);
    }

    status_t err = startWriterThread();
    if (err != OK) {
        return err;
    }

    err = startTracks(param);
    if (err != OK) {
        return err;
    }
    mStarted = true;
#ifdef MTK_AOSP_ENHANCEMENT
	ALOGD("start --");
#endif
    return OK;
}

bool MPEG4Writer::use32BitFileOffset() const {
    return mUse32BitOffset;
}

status_t MPEG4Writer::pause() {
    if (mInitCheck != OK) {
        return OK;
    }
    mPaused = true;
#ifdef MTK_AOSP_ENHANCEMENT
	mResumed = false;
	mPausedDurationUs = 0x7FFFFFFFFFFFFFFFLL;
	ALOGD("pause+,Pause TimeUs=%" PRId64 "", systemTime() / 1000);
#endif
    status_t err = OK;
    for (List<Track *>::iterator it = mTracks.begin();
         it != mTracks.end(); ++it) {
        status_t status = (*it)->pause();
        if (status != OK) {
            err = status;
        }
    }
#ifdef MTK_AOSP_ENHANCEMENT
	ALOGD("pause-, Paused TimeUs=%" PRId64 "", systemTime()/1000);
#endif
    return err;
}

void MPEG4Writer::stopWriterThread() {
    ALOGD("Stopping writer thread");
    if (!mWriterThreadStarted) {
        return;
    }

    {
        Mutex::Autolock autolock(mLock);

        mDone = true;
        mChunkReadyCondition.signal();
    }

#ifdef MTK_AOSP_ENHANCEMENT
	waitWriterThreadExit();
#endif

    void *dummy;
    pthread_join(mThread, &dummy);
    mWriterThreadStarted = false;
    ALOGD("Writer thread stopped");
}

/*
 * MP4 file standard defines a composition matrix:
 * | a  b  u |
 * | c  d  v |
 * | x  y  w |
 *
 * the element in the matrix is stored in the following
 * order: {a, b, u, c, d, v, x, y, w},
 * where a, b, c, d, x, and y is in 16.16 format, while
 * u, v and w is in 2.30 format.
 */
void MPEG4Writer::writeCompositionMatrix(int degrees) {
    ALOGV("writeCompositionMatrix");
    uint32_t a = 0x00010000;
    uint32_t b = 0;
    uint32_t c = 0;
    uint32_t d = 0x00010000;
    switch (degrees) {
        case 0:
            break;
        case 90:
            a = 0;
            b = 0x00010000;
            c = 0xFFFF0000;
            d = 0;
            break;
        case 180:
            a = 0xFFFF0000;
            d = 0xFFFF0000;
            break;
        case 270:
            a = 0;
            b = 0xFFFF0000;
            c = 0x00010000;
            d = 0;
            break;
        default:
            CHECK(!"Should never reach this unknown rotation");
            break;
    }

    writeInt32(a);           // a
    writeInt32(b);           // b
    writeInt32(0);           // u
    writeInt32(c);           // c
    writeInt32(d);           // d
    writeInt32(0);           // v
    writeInt32(0);           // x
    writeInt32(0);           // y
    writeInt32(0x40000000);  // w
}

void MPEG4Writer::release() {
#if defined(MTK_AOSP_ENHANCEMENT) && defined(USE_FILE_CACHE)
	ALOGD("release, closing mFd=%d",mFd);
	mCacheWriter->close();
#else
    close(mFd);
#endif

    mFd = -1;
    mInitCheck = NO_INIT;
    mStarted = false;
}

status_t MPEG4Writer::reset() {
#ifdef MTK_AOSP_ENHANCEMENT
	ALOGD("reset ++");
#endif
    if (mInitCheck != OK) {
        return OK;
    } else {
        if (!mWriterThreadStarted ||
            !mStarted) {
            if (mWriterThreadStarted) {
                stopWriterThread();
            }
            release();
            return OK;
        }
    }

    status_t err = OK;
    int64_t maxDurationUs = 0;
    int64_t minDurationUs = 0x7fffffffffffffffLL;
    for (List<Track *>::iterator it = mTracks.begin();
         it != mTracks.end(); ++it) {
        status_t status = (*it)->stop();
        if (err == OK && status != OK) {
            err = status;
        }

        int64_t durationUs = (*it)->getDurationUs();
        if (durationUs > maxDurationUs) {
            maxDurationUs = durationUs;
        }
        if (durationUs < minDurationUs) {
            minDurationUs = durationUs;
        }
    }

#ifdef MTK_AOSP_ENHANCEMENT
	mMaxDuration = maxDurationUs;
	if (ERROR_UNSUPPORTED_VIDEO == err) {
		ALOGW("err = ERROR_UNSUPPORTED_VIDEO, Bypass");
		err = OK;
	}
#endif

    if (mTracks.size() > 1) {
        ALOGD("Duration from tracks range is [%" PRId64 ", %" PRId64 "] us",
            minDurationUs, maxDurationUs);
    }

    stopWriterThread();

    // Do not write out movie header on error.
    if (err != OK) {
#ifdef MTK_AOSP_ENHANCEMENT
        ALOGE("MPEG4Writer::reset: !!!!!!ERROR during Track reset, do not write out moov, err=%d", err);
#endif
        release();
        return err;
    }
#ifdef MTK_AOSP_ENHANCEMENT
	writeMetaData();
#else
    // Fix up the size of the 'mdat' chunk.
    if (mUse32BitOffset) {
        lseek64(mFd, mMdatOffset, SEEK_SET);
        uint32_t size = htonl(static_cast<uint32_t>(mOffset - mMdatOffset));
        ::write(mFd, &size, 4);
    } else {
        lseek64(mFd, mMdatOffset + 8, SEEK_SET);
        uint64_t size = mOffset - mMdatOffset;
        size = hton64(size);
        ::write(mFd, &size, 8);
    }
    lseek64(mFd, mOffset, SEEK_SET);

    // Construct moov box now
    mMoovBoxBufferOffset = 0;
    mWriteMoovBoxToMemory = mStreamableFile;
    if (mWriteMoovBoxToMemory) {
        // There is no need to allocate in-memory cache
        // for moov box if the file is not streamable.

        mMoovBoxBuffer = (uint8_t *) malloc(mEstimatedMoovBoxSize);
        CHECK(mMoovBoxBuffer != NULL);
    }
    writeMoovBox(maxDurationUs);

    // mWriteMoovBoxToMemory could be set to false in
    // MPEG4Writer::write() method
    if (mWriteMoovBoxToMemory) {
        mWriteMoovBoxToMemory = false;
        // Content of the moov box is saved in the cache, and the in-memory
        // moov box needs to be written to the file in a single shot.

        CHECK_LE(mMoovBoxBufferOffset + 8, mEstimatedMoovBoxSize);

        // Moov box
        lseek64(mFd, mFreeBoxOffset, SEEK_SET);
        mOffset = mFreeBoxOffset;
        write(mMoovBoxBuffer, 1, mMoovBoxBufferOffset);

        // Free box
        lseek64(mFd, mOffset, SEEK_SET);
        writeInt32(mEstimatedMoovBoxSize - mMoovBoxBufferOffset);
        write("free", 4);
    } else {
        ALOGI("The mp4 file will not be streamable.");
    }

    // Free in-memory cache for moov box
    if (mMoovBoxBuffer != NULL) {
        free(mMoovBoxBuffer);
        mMoovBoxBuffer = NULL;
        mMoovBoxBufferOffset = 0;
    }

    CHECK(mBoxes.empty());
#endif

    release();
#ifdef MTK_AOSP_ENHANCEMENT
	ALOGD("reset --");
#endif
    return err;
}

uint32_t MPEG4Writer::getMpeg4Time() {
    time_t now = time(NULL);
    // MP4 file uses time counting seconds since midnight, Jan. 1, 1904
    // while time function returns Unix epoch values which starts
    // at 1970-01-01. Lets add the number of seconds between them
    uint32_t mpeg4Time = now + (66 * 365 + 17) * (24 * 60 * 60);
    return mpeg4Time;
}

void MPEG4Writer::writeMvhdBox(int64_t durationUs) {
    uint32_t now = getMpeg4Time();
    beginBox("mvhd");
#ifdef MTK_AOSP_ENHANCEMENT
	if(isEnable64BitDuration(durationUs)) { //eanble 64 bit for duration,creation and modification time
		writeInt32(0x1000000); 		//version = 1, flag = 0
		writeInt64((int64_t)now);  // creation time in 64bit
	    writeInt64((int64_t)now);  // modification time in 64bit
	    writeInt32(mTimeScale);    // mvhd timescale
		int64_t duration = (durationUs * mTimeScale + 5E5) / 1E6;
	    writeInt64(duration); 
	} else {
#endif
	writeInt32(0);			   // version=0, flags=0
	writeInt32(now);		   // creation time
	writeInt32(now);		   // modification time
	writeInt32(mTimeScale);    // mvhd timescale
	int32_t duration = (durationUs * mTimeScale + 5E5) / 1E6;
	writeInt32(duration);
#ifdef MTK_AOSP_ENHANCEMENT
	}
#endif
    writeInt32(0x10000);       // rate: 1.0
    writeInt16(0x100);         // volume
    writeInt16(0);             // reserved
    writeInt32(0);             // reserved
    writeInt32(0);             // reserved
    writeCompositionMatrix(0); // matrix
    writeInt32(0);             // predefined
    writeInt32(0);             // predefined
    writeInt32(0);             // predefined
    writeInt32(0);             // predefined
    writeInt32(0);             // predefined
    writeInt32(0);             // predefined
    writeInt32(mTracks.size() + 1);  // nextTrackID
    endBox();  // mvhd
}

void MPEG4Writer::writeMoovBox(int64_t durationUs) {
    beginBox("moov");
    writeMvhdBox(durationUs);
#ifdef MTK_AOSP_ENHANCEMENT
    ALOGD("writeMoovBox ++");
	if (mAreGeoTagsAvailable ||
		mArtistTag.length() > 0 ||
		mAlbumTag.length() > 0) {
#else
    if (mAreGeoTagsAvailable) {
#endif
        writeUdtaBox();
    }
    int32_t id = 1;
    for (List<Track *>::iterator it = mTracks.begin();
        it != mTracks.end(); ++it, ++id) {
        (*it)->writeTrackHeader(mUse32BitOffset);
    }
    endBox();  // moov
#ifdef MTK_AOSP_ENHANCEMENT
	ALOGD("writeMoovBox --");
#endif
}

void MPEG4Writer::writeFtypBox(MetaData *param) {
    beginBox("ftyp");

    int32_t fileType;
    if (param && param->findInt32(kKeyFileType, &fileType) &&
        fileType != OUTPUT_FORMAT_MPEG_4) {
        writeFourcc("3gp4");
        writeInt32(0);
        writeFourcc("isom");
        writeFourcc("3gp4");
    } else {
        writeFourcc("mp42");
        writeInt32(0);
        writeFourcc("isom");
        writeFourcc("mp42");
    }

    endBox();
}

static bool isTestModeEnabled() {
#if (PROPERTY_VALUE_MAX < 5)
#error "PROPERTY_VALUE_MAX must be at least 5"
#endif

    // Test mode is enabled only if rw.media.record.test system
    // property is enabled.
    char value[PROPERTY_VALUE_MAX];
    if (property_get("rw.media.record.test", value, NULL) &&
        (!strcasecmp(value, "true") || !strcasecmp(value, "1"))) {
        return true;
    }
    return false;
}

void MPEG4Writer::sendSessionSummary() {
    // Send session summary only if test mode is enabled
    if (!isTestModeEnabled()) {
        return;
    }

    for (List<ChunkInfo>::iterator it = mChunkInfos.begin();
         it != mChunkInfos.end(); ++it) {
        int trackNum = it->mTrack->getTrackId() << 28;
        notify(MEDIA_RECORDER_TRACK_EVENT_INFO,
                trackNum | MEDIA_RECORDER_TRACK_INTER_CHUNK_TIME_MS,
                it->mMaxInterChunkDurUs);
    }
}

status_t MPEG4Writer::setInterleaveDuration(uint32_t durationUs) {
    mInterleaveDurationUs = durationUs;
    return OK;
}

void MPEG4Writer::lock() {
    mLock.lock();
}

void MPEG4Writer::unlock() {
    mLock.unlock();
}

off64_t MPEG4Writer::addSample_l(MediaBuffer *buffer) {
    off64_t old_offset = mOffset;
#if defined(MTK_AOSP_ENHANCEMENT) && defined(USE_FILE_CACHE)
	mCacheWriter->write((const uint8_t *)buffer->data() + buffer->range_offset(),
       1, buffer->range_length());
#else
    ::write(mFd,
          (const uint8_t *)buffer->data() + buffer->range_offset(),
          buffer->range_length());
#endif

    mOffset += buffer->range_length();

    return old_offset;
}

static void StripStartcode(MediaBuffer *buffer) {
    if (buffer->range_length() < 4) {
        return;
    }

    const uint8_t *ptr =
        (const uint8_t *)buffer->data() + buffer->range_offset();

    if (!memcmp(ptr, "\x00\x00\x00\x01", 4)) {
        buffer->set_range(
                buffer->range_offset() + 4, buffer->range_length() - 4);
    }

#if defined(MTK_AOSP_ENHANCEMENT) //&& defined(MTK_VIDEO_HEVC_SUPPORT)
	else if (!memcmp(ptr, "\x00\x00\x01", 3)) {
		ALOGV("StripStartcode 00 00 01 for HEVC directlink");
		buffer->set_range(buffer->range_offset() + 3, buffer->range_length() - 3);
	}
#endif
}

off64_t MPEG4Writer::addLengthPrefixedSample_l(MediaBuffer *buffer) {
    off64_t old_offset = mOffset;

    size_t length = buffer->range_length();

#if defined(MTK_AOSP_ENHANCEMENT) && defined(USE_FILE_CACHE)
	int32_t hasSEIbuffer = 0;
	if (buffer->meta_data()->findInt32(kKeyHasSEIBuffer, &hasSEIbuffer) && (hasSEIbuffer != 0))
		return writeSEIbuffer(buffer);
	
	if (mUse4ByteNalLength) {
		uint8_t x = length >> 24;
		mCacheWriter->write(&x, 1, 1);
		x = (length >> 16) & 0xff;
		mCacheWriter->write(&x, 1, 1);
		x = (length >> 8) & 0xff;
		mCacheWriter->write(&x, 1, 1);
		x = length & 0xff;
		mCacheWriter->write(&x, 1, 1);

		mCacheWriter->write((const uint8_t *)buffer->data() + buffer->range_offset(),
				1, length);
		mOffset += length + 4;
	} else {
        CHECK_LT(length, 65536);

		uint8_t x = length >> 8;
		mCacheWriter->write(&x, 1, 1);
		x = length & 0xff;
		mCacheWriter->write(&x, 1, 1);
		mCacheWriter->write((const uint8_t *)buffer->data() + buffer->range_offset(),
				1, length);
		mOffset += length + 2;
	}
#else
    if (mUse4ByteNalLength) {
        uint8_t x = length >> 24;
        ::write(mFd, &x, 1);
        x = (length >> 16) & 0xff;
        ::write(mFd, &x, 1);
        x = (length >> 8) & 0xff;
        ::write(mFd, &x, 1);
        x = length & 0xff;
        ::write(mFd, &x, 1);

        ::write(mFd,
              (const uint8_t *)buffer->data() + buffer->range_offset(),
              length);

        mOffset += length + 4;
    } else {
        CHECK_LT(length, 65536);

        uint8_t x = length >> 8;
        ::write(mFd, &x, 1);
        x = length & 0xff;
        ::write(mFd, &x, 1);
        ::write(mFd, (const uint8_t *)buffer->data() + buffer->range_offset(), length);
        mOffset += length + 2;
    }
#endif

    return old_offset;
}

size_t MPEG4Writer::write(
        const void *ptr, size_t size, size_t nmemb) {

    const size_t bytes = size * nmemb;
    if (mWriteMoovBoxToMemory) {
        off64_t moovBoxSize = 8 + mMoovBoxBufferOffset + bytes;
        if (moovBoxSize > mEstimatedMoovBoxSize) {
            // The reserved moov box at the beginning of the file
            // is not big enough. Moov box should be written to
            // the end of the file from now on, but not to the
            // in-memory cache.

            // We write partial moov box that is in the memory to
            // the file first.
            for (List<off64_t>::iterator it = mBoxes.begin();
                 it != mBoxes.end(); ++it) {
                (*it) += mOffset;
            }
#if defined(MTK_AOSP_ENHANCEMENT) && defined(USE_FILE_CACHE)
			mCacheWriter->seek(mOffset,SEEK_SET);
			mCacheWriter->write(mMoovBoxBuffer, 1, mMoovBoxBufferOffset);
			mCacheWriter->write(ptr, size, nmemb);
#else
            lseek64(mFd, mOffset, SEEK_SET);
            ::write(mFd, mMoovBoxBuffer, mMoovBoxBufferOffset);
            ::write(mFd, ptr, bytes);
#endif
            mOffset += (bytes + mMoovBoxBufferOffset);

            // All subsequent moov box content will be written
            // to the end of the file.
            mWriteMoovBoxToMemory = false;
        } else {
            memcpy(mMoovBoxBuffer + mMoovBoxBufferOffset, ptr, bytes);
            mMoovBoxBufferOffset += bytes;
        }
    } else {
#if defined(MTK_AOSP_ENHANCEMENT) && defined(USE_FILE_CACHE)
		mCacheWriter->write(ptr, size, nmemb);
#else
        ::write(mFd, ptr, size * nmemb);
#endif
        mOffset += bytes;
    }
    return bytes;
}

void MPEG4Writer::beginBox(const char *fourcc) {
    CHECK_EQ(strlen(fourcc), 4);

    mBoxes.push_back(mWriteMoovBoxToMemory?
            mMoovBoxBufferOffset: mOffset);

    writeInt32(0);
    writeFourcc(fourcc);
}

void MPEG4Writer::endBox() {
    CHECK(!mBoxes.empty());

    off64_t offset = *--mBoxes.end();
    mBoxes.erase(--mBoxes.end());

    if (mWriteMoovBoxToMemory) {
       int32_t x = htonl(mMoovBoxBufferOffset - offset);
       memcpy(mMoovBoxBuffer + offset, &x, 4);
    } else {
#if defined(MTK_AOSP_ENHANCEMENT) && defined(USE_FILE_CACHE)
		mCacheWriter->seek(offset, SEEK_SET);
#else
        lseek64(mFd, offset, SEEK_SET);
#endif
        writeInt32(mOffset - offset);
        mOffset -= 4;
#if defined(MTK_AOSP_ENHANCEMENT) && defined(USE_FILE_CACHE)
		mCacheWriter->seek(mOffset, SEEK_SET);
#else
        lseek64(mFd, mOffset, SEEK_SET);
#endif
    }
}

void MPEG4Writer::writeInt8(int8_t x) {
    write(&x, 1, 1);
}

void MPEG4Writer::writeInt16(int16_t x) {
    x = htons(x);
    write(&x, 1, 2);
}

void MPEG4Writer::writeInt32(int32_t x) {
    x = htonl(x);
    write(&x, 1, 4);
}

void MPEG4Writer::writeInt64(int64_t x) {
    x = hton64(x);
    write(&x, 1, 8);
}

void MPEG4Writer::writeCString(const char *s) {
    size_t n = strlen(s);
    write(s, 1, n + 1);
}

void MPEG4Writer::writeFourcc(const char *s) {
    CHECK_EQ(strlen(s), 4);
    write(s, 1, 4);
}


// Written in +/-DD.DDDD format
void MPEG4Writer::writeLatitude(int degreex10000) {
    bool isNegative = (degreex10000 < 0);
    char sign = isNegative? '-': '+';

    // Handle the whole part
    char str[9];
    int wholePart = degreex10000 / 10000;
    if (wholePart == 0) {
        snprintf(str, 5, "%c%.2d.", sign, wholePart);
    } else {
        snprintf(str, 5, "%+.2d.", wholePart);
    }

    // Handle the fractional part
    int fractionalPart = degreex10000 - (wholePart * 10000);
    if (fractionalPart < 0) {
        fractionalPart = -fractionalPart;
    }
    snprintf(&str[4], 5, "%.4d", fractionalPart);

    // Do not write the null terminator
    write(str, 1, 8);
}

// Written in +/- DDD.DDDD format
void MPEG4Writer::writeLongitude(int degreex10000) {
    bool isNegative = (degreex10000 < 0);
    char sign = isNegative? '-': '+';

    // Handle the whole part
    char str[10];
    int wholePart = degreex10000 / 10000;
    if (wholePart == 0) {
        snprintf(str, 6, "%c%.3d.", sign, wholePart);
    } else {
        snprintf(str, 6, "%+.3d.", wholePart);
    }

    // Handle the fractional part
    int fractionalPart = degreex10000 - (wholePart * 10000);
    if (fractionalPart < 0) {
        fractionalPart = -fractionalPart;
    }
    snprintf(&str[5], 5, "%.4d", fractionalPart);

    // Do not write the null terminator
    write(str, 1, 9);
}

/*
 * Geodata is stored according to ISO-6709 standard.
 * latitudex10000 is latitude in degrees times 10000, and
 * longitudex10000 is longitude in degrees times 10000.
 * The range for the latitude is in [-90, +90], and
 * The range for the longitude is in [-180, +180]
 */
status_t MPEG4Writer::setGeoData(int latitudex10000, int longitudex10000) {
    // Is latitude or longitude out of range?
    if (latitudex10000 < -900000 || latitudex10000 > 900000 ||
        longitudex10000 < -1800000 || longitudex10000 > 1800000) {
        return BAD_VALUE;
    }

    mLatitudex10000 = latitudex10000;
    mLongitudex10000 = longitudex10000;
    mAreGeoTagsAvailable = true;
    return OK;
}

void MPEG4Writer::write(const void *data, size_t size) {
    write(data, 1, size);
}

bool MPEG4Writer::isFileStreamable() const {
    return mStreamableFile;
}

bool MPEG4Writer::exceedsFileSizeLimit() {
    // No limit
    if (mMaxFileSizeLimitBytes == 0) {
        return false;
    }

    int64_t nTotalBytesEstimate = static_cast<int64_t>(mEstimatedMoovBoxSize);
#ifdef MTK_AOSP_ENHANCEMENT
	nTotalBytesEstimate += META_DATA_HEADER_RESERVE_BYTES;//added by hai.li @2010-12-25 to check file size limit accurately
#endif
    for (List<Track *>::iterator it = mTracks.begin();
         it != mTracks.end(); ++it) {
        nTotalBytesEstimate += (*it)->getEstimatedTrackSizeBytes();
    }

#ifndef MTK_AOSP_ENHANCEMENT
    if (!mStreamableFile) {
        // Add 1024 bytes as error tolerance
        return nTotalBytesEstimate + 1024 >= mMaxFileSizeLimitBytes;
    }
#endif
    // Be conservative in the estimate: do not exceed 95% of
    // the target file limit. For small target file size limit, though,
    // this will not help.
#ifdef MTK_AOSP_ENHANCEMENT//Do not left too much space when mMaxFileSizeLimitBytes is large
	notifyEstimateSize(nTotalBytesEstimate);
    if (mMaxFileSizeLimitBytes >= 2*1024*1024)
        return (nTotalBytesEstimate >= (mMaxFileSizeLimitBytes - 100*1024));
    else
#endif //MTK_AOSP_ENHANCEMENT
    return (nTotalBytesEstimate >= (95 * mMaxFileSizeLimitBytes) / 100);
}

bool MPEG4Writer::exceedsFileDurationLimit() {
    // No limit
    if (mMaxFileDurationLimitUs == 0) {
        return false;
    }

    for (List<Track *>::iterator it = mTracks.begin();
         it != mTracks.end(); ++it) {
        if ((*it)->getDurationUs() >= mMaxFileDurationLimitUs) {
#ifdef MTK_AOSP_ENHANCEMENT
		  	ALOGI("%s track duration =%" PRId64 " >=mMaxFileDuration(%" PRId64 ")", (*it)->isAudio()? "Audio" :"Video", (*it)->getDurationUs(),mMaxFileDurationLimitUs);
#endif
            return true;
        }
    }
    return false;
}

bool MPEG4Writer::reachedEOS() {
    bool allDone = true;
    for (List<Track *>::iterator it = mTracks.begin();
         it != mTracks.end(); ++it) {
        if (!(*it)->reachedEOS()) {
            allDone = false;
            break;
        }
    }

    return allDone;
}

void MPEG4Writer::setStartTimestampUs(int64_t timeUs) {
    ALOGI("setStartTimestampUs: %" PRId64, timeUs);
    CHECK_GE(timeUs, 0ll);
    Mutex::Autolock autoLock(mLock);
    if (mStartTimestampUs < 0 || mStartTimestampUs > timeUs) {
        mStartTimestampUs = timeUs;
        ALOGI("Earliest track starting time: %" PRId64, mStartTimestampUs);
    }
}

int64_t MPEG4Writer::getStartTimestampUs() {
    Mutex::Autolock autoLock(mLock);
    return mStartTimestampUs;
}

size_t MPEG4Writer::numTracks() {
    Mutex::Autolock autolock(mLock);
    return mTracks.size();
}

////////////////////////////////////////////////////////////////////////////////

MPEG4Writer::Track::Track(
        MPEG4Writer *owner, const sp<MediaSource> &source, size_t trackId)
    : mOwner(owner),
      mMeta(source->getFormat()),
      mSource(source),
      mDone(false),
      mPaused(false),
      mResumed(false),
      mStarted(false),
      mTrackId(trackId),
      mTrackDurationUs(0),
      mEstimatedTrackSizeBytes(0),
      mSamplesHaveSameSize(true),
#ifdef MTK_AOSP_ENHANCEMENT
      mStszTableEntries(new ListTableEntries<uint32_t>(TRACK_STSZ_TABLE_BUFFER_SIZE/4, 1)),
#else
      mStszTableEntries(new ListTableEntries<uint32_t>(1000, 1)),
#endif
      mStcoTableEntries(new ListTableEntries<uint32_t>(1000, 1)),
      mCo64TableEntries(new ListTableEntries<off64_t>(1000, 1)),
      mStscTableEntries(new ListTableEntries<uint32_t>(1000, 3)),
      mStssTableEntries(new ListTableEntries<uint32_t>(1000, 1)),
#ifdef MTK_AOSP_ENHANCEMENT
      mSttsTableEntries(new ListTableEntries<uint32_t>(TRACK_STTS_TABLE_BUFFER_SIZE/4/2, 2)),
#else
      mSttsTableEntries(new ListTableEntries<uint32_t>(1000, 2)),
#endif
      mCttsTableEntries(new ListTableEntries<uint32_t>(1000, 2)),
      mCodecSpecificData(NULL),
      mCodecSpecificDataSize(0),
      mGotAllCodecSpecificData(false),
      mReachedEOS(false),
      mRotation(0) {
    getCodecSpecificDataFromInputFormatIfPossible();

    const char *mime;
    mMeta->findCString(kKeyMIMEType, &mime);
    mIsAvc = !strcasecmp(mime, MEDIA_MIMETYPE_VIDEO_AVC);
#if defined(MTK_AOSP_ENHANCEMENT) //&& defined(MTK_VIDEO_HEVC_SUPPORT)
	mIsHevc = !strcasecmp(mime, MEDIA_MIMETYPE_VIDEO_HEVC);
#endif
    mIsAudio = !strncasecmp(mime, "audio/", 6);
    mIsMPEG4 = !strcasecmp(mime, MEDIA_MIMETYPE_VIDEO_MPEG4) ||
               !strcasecmp(mime, MEDIA_MIMETYPE_AUDIO_AAC);

    setTimeScale();
#ifdef MTK_AOSP_ENHANCEMENT
	init();
#endif

}

void MPEG4Writer::Track::updateTrackSizeEstimate() {

    uint32_t stcoBoxCount = (mOwner->use32BitFileOffset()
                            ? mStcoTableEntries->count()
                            : mCo64TableEntries->count());
    int64_t stcoBoxSizeBytes = stcoBoxCount * 4;

    int64_t stszBoxSizeBytes = mSamplesHaveSameSize? 4: (mStszTableEntries->count() * 4);

    mEstimatedTrackSizeBytes = mMdatSizeBytes;  // media data size
    if (!mOwner->isFileStreamable()) {
        // Reserved free space is not large enough to hold
        // all meta data and thus wasted.
        mEstimatedTrackSizeBytes += mStscTableEntries->count() * 12 +  // stsc box size
                                    mStssTableEntries->count() * 4 +   // stss box size
                                    mSttsTableEntries->count() * 8 +   // stts box size
                                    mCttsTableEntries->count() * 8 +   // ctts box size
                                    stcoBoxSizeBytes +           // stco box size
                                    stszBoxSizeBytes;            // stsz box size
#ifdef MTK_AOSP_ENHANCEMENT
        mEstimatedTrackSizeBytes += TRACK_HEADER_RESERVE_BYTES;   //added by hai.li @2010-12-25 to check file size limit accurately
#endif
    }
}

void MPEG4Writer::Track::addOneStscTableEntry(
        size_t chunkId, size_t sampleId) {

        mStscTableEntries->add(htonl(chunkId));
        mStscTableEntries->add(htonl(sampleId));
        mStscTableEntries->add(htonl(1));
}

void MPEG4Writer::Track::addOneStssTableEntry(size_t sampleId) {
    mStssTableEntries->add(htonl(sampleId));
}

void MPEG4Writer::Track::addOneSttsTableEntry(
        size_t sampleCount, int32_t duration) {

    if (duration == 0) {
        ALOGW("0-duration samples found: %zu", sampleCount);
    }
    mSttsTableEntries->add(htonl(sampleCount));
    mSttsTableEntries->add(htonl(duration));
}

void MPEG4Writer::Track::addOneCttsTableEntry(
        size_t sampleCount, int32_t duration) {

    if (mIsAudio) {
        return;
    }
    mCttsTableEntries->add(htonl(sampleCount));
    mCttsTableEntries->add(htonl(duration));
}

void MPEG4Writer::Track::addChunkOffset(off64_t offset) {
    if (mOwner->use32BitFileOffset()) {
        uint32_t value = offset;
        mStcoTableEntries->add(htonl(value));
    } else {
        mCo64TableEntries->add(hton64(offset));
    }
}

void MPEG4Writer::Track::setTimeScale() {
    ALOGV("setTimeScale");
    // Default time scale
    mTimeScale = 90000;

    if (mIsAudio) {
        // Use the sampling rate as the default time scale for audio track.
        int32_t sampleRate;
        bool success = mMeta->findInt32(kKeySampleRate, &sampleRate);
        CHECK(success);
        mTimeScale = sampleRate;
    }

    // If someone would like to overwrite the timescale, use user-supplied value.
    int32_t timeScale;
    if (mMeta->findInt32(kKeyTimeScale, &timeScale)) {
        mTimeScale = timeScale;
    }

    CHECK_GT(mTimeScale, 0);
}

void MPEG4Writer::Track::getCodecSpecificDataFromInputFormatIfPossible() {
    const char *mime;
    CHECK(mMeta->findCString(kKeyMIMEType, &mime));

    if (!strcasecmp(mime, MEDIA_MIMETYPE_VIDEO_AVC)) {
        uint32_t type;
        const void *data;
        size_t size;
        if (mMeta->findData(kKeyAVCC, &type, &data, &size)) {
            mCodecSpecificData = malloc(size);
            mCodecSpecificDataSize = size;
            memcpy(mCodecSpecificData, data, size);
            mGotAllCodecSpecificData = true;
        }
    } else if (!strcasecmp(mime, MEDIA_MIMETYPE_VIDEO_MPEG4)
            || !strcasecmp(mime, MEDIA_MIMETYPE_AUDIO_AAC)) {
        uint32_t type;
        const void *data;
        size_t size;
        if (mMeta->findData(kKeyESDS, &type, &data, &size)) {
            ESDS esds(data, size);
            if (esds.getCodecSpecificInfo(&data, &size) == OK) {
                mCodecSpecificData = malloc(size);
                mCodecSpecificDataSize = size;
                memcpy(mCodecSpecificData, data, size);
                mGotAllCodecSpecificData = true;
            }
        }
    }
#if defined(MTK_AOSP_ENHANCEMENT) //&& defined(MTK_VIDEO_HEVC_SUPPORT)
	else if(!strcasecmp(mime, MEDIA_MIMETYPE_VIDEO_HEVC)) {
        uint32_t type;
        const void *data;
        size_t size;
        if (mMeta->findData(kKeyHVCC, &type, &data, &size)) {
            mCodecSpecificData = malloc(size);
            mCodecSpecificDataSize = size;
            memcpy(mCodecSpecificData, data, size);
            mGotAllCodecSpecificData = true;
        }
    }
#endif

}

MPEG4Writer::Track::~Track() {
#ifdef MTK_AOSP_ENHANCEMENT
	ALOGD("%s ~Track",mIsAudio? "audio": "video");
#endif
    stop();

    delete mStszTableEntries;
    delete mStcoTableEntries;
    delete mCo64TableEntries;
    delete mStscTableEntries;
    delete mSttsTableEntries;
    delete mStssTableEntries;
    delete mCttsTableEntries;

    mStszTableEntries = NULL;
    mStcoTableEntries = NULL;
    mCo64TableEntries = NULL;
    mStscTableEntries = NULL;
    mSttsTableEntries = NULL;
    mStssTableEntries = NULL;
    mCttsTableEntries = NULL;

    if (mCodecSpecificData != NULL) {
        free(mCodecSpecificData);
        mCodecSpecificData = NULL;
    }
#ifdef MTK_AOSP_ENHANCEMENT
	if (mAVCSEIData != NULL) {
		free(mAVCSEIData);
		mAVCSEIData = NULL;
	}
	ALOGD("%s ~Track done",mIsAudio? "audio": "video");
#endif
}

void MPEG4Writer::Track::initTrackingProgressStatus(MetaData *params) {
    ALOGV("initTrackingProgressStatus");
    mPreviousTrackTimeUs = -1;
    mTrackingProgressStatus = false;
    mTrackEveryTimeDurationUs = 0;
    {
        int64_t timeUs;
        if (params && params->findInt64(kKeyTrackTimeStatus, &timeUs)) {
            ALOGV("Receive request to track progress status for every %" PRId64 " us", timeUs);
            mTrackEveryTimeDurationUs = timeUs;
            mTrackingProgressStatus = true;
        }
    }
}

// static
void *MPEG4Writer::ThreadWrapper(void *me) {
#ifdef MTK_AOSP_ENHANCEMENT
	ALOGD("MPEG4Writer::ThreadWrapper writer thread: %p",me);
#else
    ALOGV("ThreadWrapper: %p", me);
#endif
    MPEG4Writer *writer = static_cast<MPEG4Writer *>(me);
    writer->threadFunc();
    return NULL;
}

void MPEG4Writer::bufferChunk(const Chunk& chunk) {
    ALOGV("bufferChunk: %p", chunk.mTrack);
    Mutex::Autolock autolock(mLock);
    CHECK_EQ(mDone, false);

    for (List<ChunkInfo>::iterator it = mChunkInfos.begin();
         it != mChunkInfos.end(); ++it) {

        if (chunk.mTrack == it->mTrack) {  // Found owner
            it->mChunks.push_back(chunk);
#ifdef MTK_AOSP_ENHANCEMENT
			checkBufferedMem(chunk, it->mTrack->isAudio());
#endif
            mChunkReadyCondition.signal();
            return;
        }
    }

    CHECK(!"Received a chunk for a unknown track");
}

void MPEG4Writer::writeChunkToFile(Chunk* chunk) {
    ALOGV("writeChunkToFile: %" PRId64 " from %s track",
        chunk->mTimeStampUs, chunk->mTrack->isAudio()? "audio": "video");

    int32_t isFirstSample = true;
#if defined(MTK_AOSP_ENHANCEMENT) && defined(SD_FULL_PROTECT)
	if (mIsSDFull) 
		return;
	uint32_t chunkSize = 0;
#endif
    while (!chunk->mSamples.empty()) {
        List<MediaBuffer *>::iterator it = chunk->mSamples.begin();

#ifdef MTK_AOSP_ENHANCEMENT //for support multi-Slice case
		off64_t offset = isLengthPrefixed(chunk)
#else
        off64_t offset = chunk->mTrack->isAvc()
#endif
                                ? addLengthPrefixedSample_l(*it)
                                : addSample_l(*it);

        if (isFirstSample) {
            chunk->mTrack->addChunkOffset(offset);
            isFirstSample = false;
        }
#if defined(MTK_AOSP_ENHANCEMENT) && defined(SD_FULL_PROTECT)
		if (mIsSDFull)
			break;
		chunkSize += (*it)->range_length();
#endif

        (*it)->release();
        (*it) = NULL;
        chunk->mSamples.erase(it);
    }

#ifdef MTK_AOSP_ENHANCEMENT //in the case of sd card is full
	eraseChunkSamples(chunk);
#endif
    chunk->mSamples.clear();
#if defined(MTK_AOSP_ENHANCEMENT) && defined(SD_FULL_PROTECT)
	addWritedChunk(chunk, chunkSize);
#endif

}

void MPEG4Writer::writeAllChunks() {
    ALOGV("writeAllChunks");
    size_t outstandingChunks = 0;
    Chunk chunk;
    while (findChunkToWrite(&chunk)) {
        writeChunkToFile(&chunk);
        ++outstandingChunks;
    }

    sendSessionSummary();

    mChunkInfos.clear();
    ALOGD("%zu chunks are written in the last batch", outstandingChunks);
}

bool MPEG4Writer::findChunkToWrite(Chunk *chunk) {
    ALOGV("findChunkToWrite");

    int64_t minTimestampUs = 0x7FFFFFFFFFFFFFFFLL;
    Track *track = NULL;
    for (List<ChunkInfo>::iterator it = mChunkInfos.begin();
         it != mChunkInfos.end(); ++it) {
        if (!it->mChunks.empty()) {
            List<Chunk>::iterator chunkIt = it->mChunks.begin();
            if (chunkIt->mTimeStampUs < minTimestampUs) {
                minTimestampUs = chunkIt->mTimeStampUs;
                track = it->mTrack;
            }
        }
    }

    if (track == NULL) {
        ALOGV("Nothing to be written after all");
        return false;
    }

    if (mIsFirstChunk) {
        mIsFirstChunk = false;
    }

    for (List<ChunkInfo>::iterator it = mChunkInfos.begin();
         it != mChunkInfos.end(); ++it) {
        if (it->mTrack == track) {
            *chunk = *(it->mChunks.begin());
            it->mChunks.erase(it->mChunks.begin());
            CHECK_EQ(chunk->mTrack, track);
#ifdef MTK_AOSP_ENHANCEMENT
			mBufferedDataSize -= chunk->mDataSize;
			ALOGD("- mBufferedDataSize(%" PRId64 "), chunk->mDataSize(%" PRId64 ")", mBufferedDataSize, chunk->mDataSize);
#endif

            int64_t interChunkTimeUs =
                chunk->mTimeStampUs - it->mPrevChunkTimestampUs;
            if (interChunkTimeUs > it->mPrevChunkTimestampUs) {
                it->mMaxInterChunkDurUs = interChunkTimeUs;
            }

            return true;
        }
    }

    return false;
}

void MPEG4Writer::threadFunc() {
    ALOGV("threadFunc");

    prctl(PR_SET_NAME, (unsigned long)"MPEG4Writer", 0, 0, 0);

#ifdef MTK_AOSP_ENHANCEMENT
    androidSetThreadPriority(0, ANDROID_PRIORITY_DISPLAY); // -4, to have favorable IO priority
#endif
    Mutex::Autolock autoLock(mLock);
    while (!mDone) {
        Chunk chunk;
        bool chunkFound = false;

        while (!mDone && !(chunkFound = findChunkToWrite(&chunk))) {
            mChunkReadyCondition.wait(mLock);
        }

        // In real time recording mode, write without holding the lock in order
        // to reduce the blocking time for media track threads.
        // Otherwise, hold the lock until the existing chunks get written to the
        // file.
        if (chunkFound) {
            if (mIsRealTimeRecording) {
                mLock.unlock();
            }
            writeChunkToFile(&chunk);
            if (mIsRealTimeRecording) {
                mLock.lock();
            }
        }
    }

    writeAllChunks();
#ifdef MTK_AOSP_ENHANCEMENT
	mWriterThreadExit = true;
	mWriterThreadExitCondition.signal();
	ALOGD("writer thread exit");
#endif
}

status_t MPEG4Writer::startWriterThread() {
#ifdef MTK_AOSP_ENHANCEMENT
	ALOGD("startWriterThread");
#else
    ALOGV("startWriterThread");
#endif

    mDone = false;
    mIsFirstChunk = true;
    mDriftTimeUs = 0;
    for (List<Track *>::iterator it = mTracks.begin();
         it != mTracks.end(); ++it) {
        ChunkInfo info;
        info.mTrack = *it;
        info.mPrevChunkTimestampUs = 0;
        info.mMaxInterChunkDurUs = 0;
        mChunkInfos.push_back(info);
    }

#ifdef MTK_AOSP_ENHANCEMENT
	mWriterThreadExit = false;
#endif

    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_JOINABLE);
    pthread_create(&mThread, &attr, ThreadWrapper, this);
    pthread_attr_destroy(&attr);
    mWriterThreadStarted = true;
#ifdef MTK_AOSP_ENHANCEMENT
	ALOGD("startWriterThread OK");
#endif
    return OK;
}


status_t MPEG4Writer::Track::start(MetaData *params) {
#ifdef MTK_AOSP_ENHANCEMENT
	ALOGD("%s Track  start",mIsAudio? "audio": "video");
#endif
    if (!mDone && mPaused) {
        mPaused = false;
        mResumed = true;
#ifdef MTK_AOSP_ENHANCEMENT//
		resume();
#endif
        return OK;
    }

    int64_t startTimeUs;
    if (params == NULL || !params->findInt64(kKeyTime, &startTimeUs)) {
        startTimeUs = 0;
    }
    mStartTimeRealUs = startTimeUs;
#ifdef MTK_AOSP_ENHANCEMENT
	ALOGD("%s Track  start,mStartTimeRealUs=%" PRId64 "",mIsAudio? "audio": "video",mStartTimeRealUs);
#endif

    int32_t rotationDegrees;
    if (!mIsAudio && params && params->findInt32(kKeyRotation, &rotationDegrees)) {
        mRotation = rotationDegrees;
    }

#ifdef MTK_AOSP_ENHANCEMENT
	initStart(params);
#endif
    initTrackingProgressStatus(params);

    sp<MetaData> meta = new MetaData;
    if (mOwner->isRealTimeRecording() && mOwner->numTracks() > 1) {
        /*
         * This extra delay of accepting incoming audio/video signals
         * helps to align a/v start time at the beginning of a recording
         * session, and it also helps eliminate the "recording" sound for
         * camcorder applications.
         *
         * If client does not set the start time offset, we fall back to
         * use the default initial delay value.
         */
        int64_t startTimeOffsetUs = mOwner->getStartTimeOffsetMs() * 1000LL;
        if (startTimeOffsetUs < 0) {  // Start time offset was not set
            startTimeOffsetUs = kInitialDelayTimeUs;
        }
#ifdef MTK_AOSP_ENHANCEMENT
		int32_t videoFrameRate;
		if(mIsAudio && params && params->findInt32(kKeyFrameRate, &videoFrameRate))
		{ 
			ALOGD("find video frame rate(%d) for audio track, audio mTrackStartTimeOffsetUs = %dus", videoFrameRate, 1000000/videoFrameRate);
			mTrackStartTimeOffsetUs = 1000000/videoFrameRate;
		}
#endif
        startTimeUs += startTimeOffsetUs;
        ALOGI("Start time offset: %" PRId64 " us", startTimeOffsetUs);
    }

    meta->setInt64(kKeyTime, startTimeUs);

#ifdef MTK_AOSP_ENHANCEMENT
	ALOGD("Start %s MediaSource", mIsAudio? "Audio": "Video");
#endif
    status_t err = mSource->start(meta.get());
#ifdef MTK_AOSP_ENHANCEMENT
	ALOGD("Start %s MediaSource finish, err=%d", mIsAudio? "Audio": "Video", err);
#endif
if (err != OK) {
        mDone = mReachedEOS = true;
        return err;
    }

    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_JOINABLE);

    mDone = false;
    mStarted = true;
    mTrackDurationUs = 0;
    mReachedEOS = false;
    mEstimatedTrackSizeBytes = 0;
    mMdatSizeBytes = 0;
#ifdef MTK_AOSP_ENHANCEMENT
	mThreadExit = false;
#endif
    mMaxChunkDurationUs = 0;

    pthread_create(&mThread, &attr, ThreadWrapper, this);
    pthread_attr_destroy(&attr);

#ifdef MTK_AOSP_ENHANCEMENT
	ALOGD("%s Track track thread start OK--",mIsAudio? "audio": "video");
#endif
    return OK;
}

status_t MPEG4Writer::Track::pause() {
#ifdef MTK_AOSP_ENHANCEMENT
	if(mPaused) {
		ALOGD("%s Track in paused state, return directly",mIsAudio? "audio": "video");
		return OK;
	}
#endif
    mPaused = true;
#ifdef MTK_AOSP_ENHANCEMENT
	pauseEx();
#endif
    return OK;
}

status_t MPEG4Writer::Track::stop() {
    ALOGD("%s track stopping", mIsAudio? "Audio": "Video");
    if (!mStarted) {
        ALOGE("Stop() called but track is not started");
        return ERROR_END_OF_STREAM;
    }

    if (mDone) {
        return OK;
    }
    mDone = true;
	
	ALOGD("%s track source stopping", mIsAudio? "Audio": "Video");
	mSource->stop();
	ALOGD("%s track source stopped", mIsAudio? "Audio": "Video");
	
#ifdef MTK_AOSP_ENHANCEMENT
	if (!mIsAudio) {
		ALOGD("Notify camera release");
		mOwner->notify(MEDIA_RECORDER_EVENT_INFO, MEDIA_RECORDER_INFO_CAMERA_RELEASE, 0);
	}
#endif
	
	void *dummy;
	
#ifdef MTK_AOSP_ENHANCEMENT
	waitTrackThreadExit(); //Need Refine: ptherad_join also will wait thread exit,whether duplicated?
#endif
	
	pthread_join(mThread, &dummy);
    status_t err = static_cast<status_t>(reinterpret_cast<uintptr_t>(dummy));

    ALOGD("%s track stopped", mIsAudio? "Audio": "Video");
    return err;
}

bool MPEG4Writer::Track::reachedEOS() {
    return mReachedEOS;
}

// static
void *MPEG4Writer::Track::ThreadWrapper(void *me) {
    Track *track = static_cast<Track *>(me);

    status_t err = track->threadEntry();
    return (void *)(uintptr_t)err;
}

static void getNalUnitType(uint8_t byte, uint8_t* type) {
    ALOGV("getNalUnitType: %d", byte);

    // nal_unit_type: 5-bit unsigned integer
    *type = (byte & 0x1F);
}

static const uint8_t *findNextStartCode(
        const uint8_t *data, size_t length) {

    ALOGV("findNextStartCode: %p %zu", data, length);

    size_t bytesLeft = length;
    while (bytesLeft > 4  &&
            memcmp("\x00\x00\x00\x01", &data[length - bytesLeft], 4)) {
        --bytesLeft;
    }
    if (bytesLeft <= 4) {
        bytesLeft = 0; // Last parameter set
    }
    return &data[length - bytesLeft];
}

const uint8_t *MPEG4Writer::Track::parseParamSet(
        const uint8_t *data, size_t length, int type, size_t *paramSetLen) {

    ALOGV("parseParamSet");
    CHECK(type == kNalUnitTypeSeqParamSet ||
          type == kNalUnitTypePicParamSet);

    const uint8_t *nextStartCode = findNextStartCode(data, length);
    *paramSetLen = nextStartCode - data;
    if (*paramSetLen == 0) {
        ALOGE("Param set is malformed, since its length is 0");
        return NULL;
    }

    AVCParamSet paramSet(*paramSetLen, data);
    if (type == kNalUnitTypeSeqParamSet) {
        if (*paramSetLen < 4) {
            ALOGE("Seq parameter set malformed");
            return NULL;
        }
        if (mSeqParamSets.empty()) {
            mProfileIdc = data[1];
            mProfileCompatible = data[2];
            mLevelIdc = data[3];
        } else {
            if (mProfileIdc != data[1] ||
                mProfileCompatible != data[2] ||
                mLevelIdc != data[3]) {
                ALOGE("Inconsistent profile/level found in seq parameter sets");
                return NULL;
            }
        }
        mSeqParamSets.push_back(paramSet);
    } else {
        mPicParamSets.push_back(paramSet);
    }
    return nextStartCode;
}

status_t MPEG4Writer::Track::copyAVCCodecSpecificData(
        const uint8_t *data, size_t size) {
#ifdef MTK_AOSP_ENHANCEMENT
	ALOGD("copyAVCCodecSpecificData size=%d",size);
#else
    ALOGV("copyAVCCodecSpecificData");
#endif

    // 2 bytes for each of the parameter set length field
    // plus the 7 bytes for the header
    if (size < 4 + 7) {
        ALOGE("Codec specific data length too short: %zu", size);
        return ERROR_MALFORMED;
    }

    mCodecSpecificDataSize = size;
    mCodecSpecificData = malloc(size);
    memcpy(mCodecSpecificData, data, size);
    return OK;
}

status_t MPEG4Writer::Track::parseAVCCodecSpecificData(
        const uint8_t *data, size_t size) {

#ifdef MTK_AOSP_ENHANCEMENT
	ALOGD("parseAVCCodecSpecificData size=%d",size);
#else
    ALOGV("parseAVCCodecSpecificData");
#endif
    // Data starts with a start code.
    // SPS and PPS are separated with start codes.
    // Also, SPS must come before PPS
    uint8_t type = kNalUnitTypeSeqParamSet;
    bool gotSps = false;
    bool gotPps = false;
    const uint8_t *tmp = data;
    const uint8_t *nextStartCode = data;
    size_t bytesLeft = size;
    size_t paramSetLen = 0;
    mCodecSpecificDataSize = 0;
    while (bytesLeft > 4 && !memcmp("\x00\x00\x00\x01", tmp, 4)) {
        getNalUnitType(*(tmp + 4), &type);
        if (type == kNalUnitTypeSeqParamSet) {
            if (gotPps) {
                ALOGE("SPS must come before PPS");
                return ERROR_MALFORMED;
            }
            if (!gotSps) {
                gotSps = true;
            }
            nextStartCode = parseParamSet(tmp + 4, bytesLeft - 4, type, &paramSetLen);
        } else if (type == kNalUnitTypePicParamSet) {
            if (!gotSps) {
                ALOGE("SPS must come before PPS");
                return ERROR_MALFORMED;
            }
            if (!gotPps) {
                gotPps = true;
            }
            nextStartCode = parseParamSet(tmp + 4, bytesLeft - 4, type, &paramSetLen);
#ifdef MTK_AOSP_ENHANCEMENT//for  SEI support
		} else if (type == kNalUnitTypeSEI) {
			nextStartCode = findNextStartCode(tmp + 4, bytesLeft - 4);
			if (nextStartCode == NULL) {
				return ERROR_MALFORMED;
			}
			mAVCSEIDataSize = nextStartCode - tmp - 4;
			mAVCSEIData = malloc(mAVCSEIDataSize);
			memcpy(mAVCSEIData, tmp + 4, mAVCSEIDataSize);
			ALOGD("mAVCSEIData=0x%8.8x, mAVCSEIDataSize=%d", *(uint32_t*)mAVCSEIData, mAVCSEIDataSize);
			bytesLeft -= nextStartCode - tmp;
			tmp = nextStartCode;
			continue;
#endif
        } else {
            ALOGE("Only SPS and PPS Nal units are expected");
            return ERROR_MALFORMED;
        }

        if (nextStartCode == NULL) {
#ifdef MTK_AOSP_ENHANCEMENT
            ALOGE("nextStartCode == NULL");
#endif
            return ERROR_MALFORMED;
        }

        // Move on to find the next parameter set
        bytesLeft -= nextStartCode - tmp;
        tmp = nextStartCode;
        mCodecSpecificDataSize += (2 + paramSetLen);
    }

    {
        // Check on the number of seq parameter sets
        size_t nSeqParamSets = mSeqParamSets.size();
        if (nSeqParamSets == 0) {
            ALOGE("Cound not find sequence parameter set");
            return ERROR_MALFORMED;
        }

        if (nSeqParamSets > 0x1F) {
            ALOGE("Too many seq parameter sets (%zu) found", nSeqParamSets);
            return ERROR_MALFORMED;
        }
    }

    {
        // Check on the number of pic parameter sets
        size_t nPicParamSets = mPicParamSets.size();
        if (nPicParamSets == 0) {
            ALOGE("Cound not find picture parameter set");
            return ERROR_MALFORMED;
        }
        if (nPicParamSets > 0xFF) {
            ALOGE("Too many pic parameter sets (%zd) found", nPicParamSets);
            return ERROR_MALFORMED;
        }
    }
// FIXME:
// Add chromat_format_idc, bit depth values, etc for AVC/h264 high profile and above
// and remove #if 0
#if 0
    {
        // Check on the profiles
        // These profiles requires additional parameter set extensions
        if (mProfileIdc == 100 || mProfileIdc == 110 ||
            mProfileIdc == 122 || mProfileIdc == 144) {
            ALOGE("Sorry, no support for profile_idc: %d!", mProfileIdc);
            return BAD_VALUE;
        }
    }
#endif
    return OK;
}

status_t MPEG4Writer::Track::makeAVCCodecSpecificData(
        const uint8_t *data, size_t size) {
#ifdef MTK_AOSP_ENHANCEMENT
	ALOGD("makeAVCCodecSpecificData,size=%d",size);
#endif

    if (mCodecSpecificData != NULL) {
        ALOGE("Already have codec specific data");
        return ERROR_MALFORMED;
    }

    if (size < 4) {
        ALOGE("Codec specific data length too short: %zu", size);
        return ERROR_MALFORMED;
    }
#ifdef MTK_AOSP_ENHANCEMENT //for multi-Slice case
	ALOGI("makeAVCCodecSpecificData,mMultiSliceBS=%d",mMultiSliceBS);
	if(!mMultiSliceBS) {
#endif
    // Data is in the form of AVCCodecSpecificData
    if (memcmp("\x00\x00\x00\x01", data, 4)) {
        return copyAVCCodecSpecificData(data, size);
    }

    if (parseAVCCodecSpecificData(data, size) != OK) {
        return ERROR_MALFORMED;
    }
#ifdef MTK_AOSP_ENHANCEMENT //for multi-Slice case
	} else {
		ALOGI("H.264 before parseAVCCodecSpecificDataByNALSize");
		if(parseAVCCodecSpecificDataByNALSize(data,size)!= OK){
			ALOGE("parseAVCCodecSpecificDataByNALSize Failed");
			return ERROR_MALFORMED;
		}
	}
#endif

    // ISO 14496-15: AVC file format
    mCodecSpecificDataSize += 7;  // 7 more bytes in the header
    mCodecSpecificData = malloc(mCodecSpecificDataSize);
    uint8_t *header = (uint8_t *)mCodecSpecificData;
    header[0] = 1;                     // version
    header[1] = mProfileIdc;           // profile indication
    header[2] = mProfileCompatible;    // profile compatibility
    header[3] = mLevelIdc;
#ifdef MTK_AOSP_ENHANCEMENT
	ALOGD("makeAVCCodecSpecificData,mProfileIdc=%d,mProfileCompatible=%d,mLevelIdc=%d",mProfileIdc,mProfileCompatible,mLevelIdc);
#endif

    // 6-bit '111111' followed by 2-bit to lengthSizeMinuusOne
    if (mOwner->useNalLengthFour()) {
        header[4] = 0xfc | 3;  // length size == 4 bytes
    } else {
        header[4] = 0xfc | 1;  // length size == 2 bytes
    }

    // 3-bit '111' followed by 5-bit numSequenceParameterSets
    int nSequenceParamSets = mSeqParamSets.size();
    header[5] = 0xe0 | nSequenceParamSets;
    header += 6;
    for (List<AVCParamSet>::iterator it = mSeqParamSets.begin();
         it != mSeqParamSets.end(); ++it) {
        // 16-bit sequence parameter set length
        uint16_t seqParamSetLength = it->mLength;
        header[0] = seqParamSetLength >> 8;
        header[1] = seqParamSetLength & 0xff;

        // SPS NAL unit (sequence parameter length bytes)
        memcpy(&header[2], it->mData, seqParamSetLength);
        header += (2 + seqParamSetLength);
    }

    // 8-bit nPictureParameterSets
    int nPictureParamSets = mPicParamSets.size();
    header[0] = nPictureParamSets;
    header += 1;
    for (List<AVCParamSet>::iterator it = mPicParamSets.begin();
         it != mPicParamSets.end(); ++it) {
        // 16-bit picture parameter set length
        uint16_t picParamSetLength = it->mLength;
        header[0] = picParamSetLength >> 8;
        header[1] = picParamSetLength & 0xff;

        // PPS Nal unit (picture parameter set length bytes)
        memcpy(&header[2], it->mData, picParamSetLength);
        header += (2 + picParamSetLength);
    }

    return OK;
}

/*
 * Updates the drift time from the audio track so that
 * the video track can get the updated drift time information
 * from the file writer. The fluctuation of the drift time of the audio
 * encoding path is smoothed out with a simple filter by giving a larger
 * weight to more recently drift time. The filter coefficients, 0.5 and 0.5,
 * are heuristically determined.
 */
void MPEG4Writer::Track::updateDriftTime(const sp<MetaData>& meta) {
    int64_t driftTimeUs = 0;
    if (meta->findInt64(kKeyDriftTime, &driftTimeUs)) {
        int64_t prevDriftTimeUs = mOwner->getDriftTimeUs();
        int64_t timeUs = (driftTimeUs + prevDriftTimeUs) >> 1;
        mOwner->setDriftTimeUs(timeUs);
    }
}

status_t MPEG4Writer::Track::threadEntry() {
    int32_t count = 0;
    const int64_t interleaveDurationUs = mOwner->interleaveDuration();
    const bool hasMultipleTracks = (mOwner->numTracks() > 1);
    int64_t chunkTimestampUs = 0;
    int32_t nChunks = 0;
    int32_t nZeroLengthFrames = 0;
    int64_t lastTimestampUs = 0;      // Previous sample time stamp
    int64_t lastDurationUs = 0;       // Between the previous two samples
    int64_t currDurationTicks = 0;    // Timescale based ticks
    int64_t lastDurationTicks = 0;    // Timescale based ticks
    int32_t sampleCount = 1;          // Sample count in the current stts table entry
    uint32_t previousSampleSize = 0;  // Size of the previous sample
    int64_t previousPausedDurationUs = 0;
    int64_t timestampUs = 0;
    int64_t cttsOffsetTimeUs = 0;
    int64_t currCttsOffsetTimeTicks = 0;   // Timescale based ticks
    int64_t lastCttsOffsetTimeTicks = -1;  // Timescale based ticks
    int32_t cttsSampleCount = 0;           // Sample count in the current ctts table entry
    uint32_t lastSamplesPerChunk = 0;
#ifdef MTK_AOSP_ENHANCEMENT
	int64_t firstPauseFrameTimeUs = 0;
#endif

    if (mIsAudio) {
        prctl(PR_SET_NAME, (unsigned long)"AudioTrackEncoding", 0, 0, 0);
    } else {
        prctl(PR_SET_NAME, (unsigned long)"VideoTrackEncoding", 0, 0, 0);
    }

    if (mOwner->isRealTimeRecording()) {
        androidSetThreadPriority(0, ANDROID_PRIORITY_AUDIO);
    }

    sp<MetaData> meta_data;

    status_t err = OK;
    MediaBuffer *buffer;
    const char *trackName = mIsAudio ? "Audio" : "Video";

#ifdef MTK_AOSP_ENHANCEMENT
ALOGD("%s mStszTableEntries->count()=%d, mDone=%d", mIsAudio?"Audio":"Video", mStszTableEntries->count(), mDone);
while (!mDone && (err = mSource->read(&buffer)) == OK) {//added mStszTableEntries->count() condition by hai.li @2010-12-25 to make sure recording one frame

		atraceDataRead();
#else
while (!mDone && (err = mSource->read(&buffer)) == OK) {
#endif

        if (buffer->range_length() == 0) {
            buffer->release();
            buffer = NULL;
            ++nZeroLengthFrames;
            continue;
        }

        // If the codec specific data has not been received yet, delay pause.
        // After the codec specific data is received, discard what we received
        // when the track is to be paused.
#ifndef MTK_AOSP_ENHANCEMENT
        if (mPaused && !mResumed) {
#else
		if ((mPaused && !mResumed) && (mStszTableEntries->count() > 0)) {//Do pause after the first frame received
			getFirstPauseTimeUs(buffer, firstPauseFrameTimeUs);
#endif
            buffer->release();
            buffer = NULL;
            continue;
        }

        ++count;

        int32_t isCodecConfig;
        if (buffer->meta_data()->findInt32(kKeyIsCodecConfig, &isCodecConfig)
#ifdef MTK_AOSP_ENHANCEMENT
		    && hasCodecInfo()
#endif
			&& isCodecConfig) {

            CHECK(!mGotAllCodecSpecificData);

            if (mIsAvc) {
#ifdef MTK_AOSP_ENHANCEMENT //for support multi-Slice case
 				getMultiSliceBS(buffer);
#endif
                status_t err = makeAVCCodecSpecificData(
                        (const uint8_t *)buffer->data()
                            + buffer->range_offset(),
                        buffer->range_length());
                CHECK_EQ((status_t)OK, err);
            } else if (mIsMPEG4) {
                mCodecSpecificDataSize = buffer->range_length();
                mCodecSpecificData = malloc(mCodecSpecificDataSize);
                memcpy(mCodecSpecificData,
                        (const uint8_t *)buffer->data()
                            + buffer->range_offset(),
                       buffer->range_length());
#ifdef MTK_AOSP_ENHANCEMENT
				ALOGD("%s MPEG4 codec info size=%d", mIsAudio?"Audio":"Video", mCodecSpecificDataSize);
 				if(!mIsAudio && (*(uint32_t*)mCodecSpecificData != 0xB0010000)) {
					ALOGW("Maybe Wrong MPEG-4 VOS Header: 0x%8.8x", *(uint32_t*)mCodecSpecificData);
				}
#endif
            }
#if defined(MTK_AOSP_ENHANCEMENT) //&& defined(MTK_VIDEO_HEVC_SUPPORT)
			else if (mIsHevc) { 
 				getMultiSliceBS(buffer);
				status_t err = makeHEVCCodecSpecificData(
						(const uint8_t *)buffer->data()
							+ buffer->range_offset(),
						buffer->range_length());
 				CHECK_EQ((status_t)OK, err);
			}
#endif	

            buffer->release();
            buffer = NULL;

            mGotAllCodecSpecificData = true;
            continue;
        }

#ifdef MTK_AOSP_ENHANCEMENT
		checkVideoHeader(buffer);
		if(waitNewFrameForResume(buffer, firstPauseFrameTimeUs)) {
			buffer->release();
			buffer = NULL;
			continue;
		}
#endif


// Make a deep copy of the MediaBuffer and Metadata and release
// the original as soon as we can
#ifdef MTK_AOSP_ENHANCEMENT
		MediaBuffer *copy;
		if (isSEIData()) { //for SEI support
			copy = getSEIData(buffer);
		}
		else {
			copy = new MediaBuffer(buffer->range_length());
			memcpy(copy->data(), (uint8_t *)buffer->data() + buffer->range_offset(),
					buffer->range_length());
			copy->set_range(0, buffer->range_length());
		}
#else
		MediaBuffer *copy = new MediaBuffer(buffer->range_length());
		memcpy(copy->data(), (uint8_t *)buffer->data() + buffer->range_offset(),
				buffer->range_length());
        copy->set_range(0, buffer->range_length());
#endif
		
        meta_data = new MetaData(*buffer->meta_data().get());
        buffer->release();
        buffer = NULL;

#ifdef MTK_AOSP_ENHANCEMENT
		if (needStripStartcode()) StripStartcode(copy);
#else
        if (mIsAvc) StripStartcode(copy);
#endif

		size_t sampleSize = copy->range_length();

#ifdef MTK_AOSP_ENHANCEMENT
		if (needStripStartcode()) {
#else
        if (mIsAvc) {
#endif
            if (mOwner->useNalLengthFour()) {
                sampleSize += 4;
            } else {
                sampleSize += 2;
            }
        }

        // Max file size or duration handling
        mMdatSizeBytes += sampleSize;
        updateTrackSizeEstimate();

#ifdef MTK_AOSP_ENHANCEMENT
		if (mOwner->exceedsFileSizeLimit() && (mStszTableEntries->count() != 0)){
#else
        if (mOwner->exceedsFileSizeLimit()) {
#endif
            mOwner->notify(MEDIA_RECORDER_EVENT_INFO, MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED, 0);

#ifdef MTK_AOSP_ENHANCEMENT//We should signal resumed when we reached max file size during resuming
			ALOGD("Notify App for Max file size reached!");
			signalResumed(hasMultipleTracks);
            copy->release();
            copy = NULL;
 			mPauseCondition.signal();
#endif
            break;
        }
        if (mOwner->exceedsFileDurationLimit()) {
            mOwner->notify(MEDIA_RECORDER_EVENT_INFO, MEDIA_RECORDER_INFO_MAX_DURATION_REACHED, 0);
#ifdef MTK_AOSP_ENHANCEMENT//We should signal resumed when we reached max duration during resuming
            ALOGD("Notify App for Max Duration reached!");
			signalResumed(hasMultipleTracks);
            copy->release();
            copy = NULL;
			mPauseCondition.signal();
#endif
            break;
        }

        int32_t isSync = false;
        meta_data->findInt32(kKeyIsSyncFrame, &isSync);
        CHECK(meta_data->findInt64(kKeyTime, &timestampUs));
		
#ifdef MTK_AOSP_ENHANCEMENT
		if(mIsAudio && hasMultipleTracks && needDropAudioFrame(timestampUs)) {
			ALOGD("Drop audio frame whose timestamp(%" PRId64 "us) may > the first video frame's", timestampUs);
			copy->release();
			copy = NULL;
			continue;
		}
#endif

////////////////////////////////////////////////////////////////////////////////

        if (mStszTableEntries->count() == 0) {

            mFirstSampleTimeRealUs = systemTime() / 1000;
            mStartTimestampUs = timestampUs;
            mOwner->setStartTimestampUs(mStartTimestampUs);
            previousPausedDurationUs = mStartTimestampUs;
#ifdef MTK_AOSP_ENHANCEMENT
			ALOGD("%s mStartTimestampUs=%" PRId64 "us", mIsAudio?"Audio":"Video", mStartTimestampUs);
			if (!mIsAudio) {
				mOwner->setVideoStartTimeUs(mStartTimestampUs);
				mOwner->notify(MEDIA_RECORDER_EVENT_INFO, MEDIA_RECORDER_INFO_START_TIMER, 0);
			}
#endif
        }

        if (mResumed) {
#ifdef MTK_AOSP_ENHANCEMENT//lastDurationUs is not exectly the same as the last frame duration
			if (hasMultipleTracks) {//Audio and Video tracks should have equal paused duration
				previousPausedDurationUs += mOwner->getPausedDuration();
			} else {//This is only for one track case
				previousPausedDurationUs += timestampUs - firstPauseFrameTimeUs;
			}
			ALOGD("%s resume time is %" PRId64 ", previousPausedDurationUs=%" PRId64 "", mIsAudio?"Audio":"Video", timestampUs, previousPausedDurationUs);
			signalResumed(hasMultipleTracks);
#else
            int64_t durExcludingEarlierPausesUs = timestampUs - previousPausedDurationUs;
            if (WARN_UNLESS(durExcludingEarlierPausesUs >= 0ll, "for %s track", trackName)) {
                copy->release();
                return ERROR_MALFORMED;
            }

            int64_t pausedDurationUs = durExcludingEarlierPausesUs - mTrackDurationUs;
            if (WARN_UNLESS(pausedDurationUs >= lastDurationUs, "for %s track", trackName)) {
                copy->release();
                return ERROR_MALFORMED;
            }

            previousPausedDurationUs += pausedDurationUs - lastDurationUs;
            mResumed = false;
#endif
        }

        timestampUs -= previousPausedDurationUs;
        if (WARN_UNLESS(timestampUs >= 0ll, "for %s track", trackName)) {
            copy->release();
            return ERROR_MALFORMED;
        }

#if defined(MTK_AOSP_ENHANCEMENT) && defined(MTK_SLOW_MOTION_VIDEO_SUPPORT)
        if(mSlowMotionSpeedValue > 0)
            timestampUs *= mSlowMotionSpeedValue;
#endif
        if (!mIsAudio) {
            /*
             * Composition time: timestampUs
             * Decoding time: decodingTimeUs
             * Composition time offset = composition time - decoding time
             */
            int64_t decodingTimeUs;
#ifdef MTK_AOSP_ENHANCEMENT
		  if(meta_data->findInt64(kKeyDecodingTime, &decodingTimeUs)){
#else
            CHECK(meta_data->findInt64(kKeyDecodingTime, &decodingTimeUs));
#endif
            decodingTimeUs -= previousPausedDurationUs;
#if defined(MTK_AOSP_ENHANCEMENT) && defined(MTK_SLOW_MOTION_VIDEO_SUPPORT)
            if(mSlowMotionSpeedValue > 0)
              decodingTimeUs *= mSlowMotionSpeedValue;
#endif
            cttsOffsetTimeUs =
                    timestampUs + kMaxCttsOffsetTimeUs - decodingTimeUs;
            if (WARN_UNLESS(cttsOffsetTimeUs >= 0ll, "for %s track", trackName)) {
                copy->release();
                return ERROR_MALFORMED;
            }

            timestampUs = decodingTimeUs;
            ALOGV("decoding time: %" PRId64 " and ctts offset time: %" PRId64,
                timestampUs, cttsOffsetTimeUs);

            // Update ctts box table if necessary
            currCttsOffsetTimeTicks =
                    (cttsOffsetTimeUs * mTimeScale + 500000LL) / 1000000LL;
 if (WARN_UNLESS(currCttsOffsetTimeTicks <= 0x0FFFFFFFFLL, "for %s track", trackName)) {
                copy->release();
                return ERROR_MALFORMED;
            }

#ifdef MTK_AOSP_ENHANCEMENT
		  }
#endif

            if (mStszTableEntries->count() == 0) {

                // Force the first ctts table entry to have one single entry
                // so that we can do adjustment for the initial track start
                // time offset easily in writeCttsBox().
                lastCttsOffsetTimeTicks = currCttsOffsetTimeTicks;
                addOneCttsTableEntry(1, currCttsOffsetTimeTicks);
                cttsSampleCount = 0;      // No sample in ctts box is pending
            } else {
                if (currCttsOffsetTimeTicks != lastCttsOffsetTimeTicks) {
                    addOneCttsTableEntry(cttsSampleCount, lastCttsOffsetTimeTicks);
                    lastCttsOffsetTimeTicks = currCttsOffsetTimeTicks;
                    cttsSampleCount = 1;  // One sample in ctts box is pending
                } else {
                    ++cttsSampleCount;
                }
            }

            // Update ctts time offset range

            if (mStszTableEntries->count() == 0) {

                mMinCttsOffsetTimeUs = currCttsOffsetTimeTicks;
                mMaxCttsOffsetTimeUs = currCttsOffsetTimeTicks;
            } else {
                if (currCttsOffsetTimeTicks > mMaxCttsOffsetTimeUs) {
                    mMaxCttsOffsetTimeUs = currCttsOffsetTimeTicks;
                } else if (currCttsOffsetTimeTicks < mMinCttsOffsetTimeUs) {
                    mMinCttsOffsetTimeUs = currCttsOffsetTimeTicks;
                }
            }

        }

#ifndef MTK_AOSP_ENHANCEMENT
        if (mOwner->isRealTimeRecording()) {
            if (mIsAudio) {
                updateDriftTime(meta_data);
            }
        }
#endif

        if (WARN_UNLESS(timestampUs >= 0ll, "for %s track", trackName)) {
            copy->release();
            return ERROR_MALFORMED;
        }

        ALOGV("%s media time stamp: %" PRId64 " and previous paused duration %" PRId64,
                trackName, timestampUs, previousPausedDurationUs);
        if (timestampUs > mTrackDurationUs) {
            mTrackDurationUs = timestampUs;
        }
#ifdef MTK_AOSP_ENHANCEMENT //for some audio track one frame will last too long
		if(!hasMultipleTracks && mIsAudio) { //for Audio Record CTS test
			int64_t bufferDuration = 0;
			//int64_t realDuration;
			if(meta_data->findInt64(kKeyDuration, &bufferDuration)){
				//realDuration = mTrackDurationUs + bufferDuration;
				mTrackDurationUs += bufferDuration;
			}
			if (mOwner->exceedsFileDurationLimit()) {
	            mOwner->notify(MEDIA_RECORDER_EVENT_INFO, MEDIA_RECORDER_INFO_MAX_DURATION_REACHED, 0);
				ALOGD("Notify App for Max Duration reached! timestampUs=%" PRId64 ",bufferDuration=%" PRId64 "",\
					 timestampUs,bufferDuration);
				signalResumed(hasMultipleTracks);
                copy->release();
                copy = NULL;
				break;
			}
		}
#endif

        // We need to use the time scale based ticks, rather than the
        // timestamp itself to determine whether we have to use a new
        // stts entry, since we may have rounding errors.
        // The calculation is intended to reduce the accumulated
        // rounding errors.
        currDurationTicks =
            ((timestampUs * mTimeScale + 500000LL) / 1000000LL -
                (lastTimestampUs * mTimeScale + 500000LL) / 1000000LL);
        if (currDurationTicks < 0ll) {
            ALOGE("timestampUs %" PRId64 " < lastTimestampUs %" PRId64 " for %s track",
                timestampUs, lastTimestampUs, trackName);
            copy->release();

#ifndef MTK_AOSP_ENHANCEMENT //did not set mThreadExit =true before return, may cause stop ANR
            return UNKNOWN_ERROR;
#endif

        }
#ifdef MTK_AOSP_ENHANCEMENT
		CHECK_GE(currDurationTicks, 0ll); //add check like 4.1 to easyly find root cause
#endif

        // if the duration is different for this sample, see if it is close enough to the previous
        // duration that we can fudge it and use the same value, to avoid filling the stts table
        // with lots of near-identical entries.
        // "close enough" here means that the current duration needs to be adjusted by less
        // than 0.1 milliseconds
        if (lastDurationTicks && (currDurationTicks != lastDurationTicks)) {
            int64_t deltaUs = ((lastDurationTicks - currDurationTicks) * 1000000LL
                    + (mTimeScale / 2)) / mTimeScale;
            if (deltaUs > -100 && deltaUs < 100) {
                // use previous ticks, and adjust timestamp as if it was actually that number
                // of ticks
                currDurationTicks = lastDurationTicks;
                timestampUs += deltaUs;
            }
        }

        mStszTableEntries->add(htonl(sampleSize));
        if (mStszTableEntries->count() > 2) {

            // Force the first sample to have its own stts entry so that
            // we can adjust its value later to maintain the A/V sync.
            if (mStszTableEntries->count() == 3 || currDurationTicks != lastDurationTicks) {
#ifdef MTK_AOSP_ENHANCEMENT
                ALOGV("%s lastDurationUs: %" PRId64 " us, currDurationTicks: %" PRId64 " us",
                        mIsAudio? "Audio": "Video", lastDurationUs, currDurationTicks);
#endif
                addOneSttsTableEntry(sampleCount, lastDurationTicks);
                sampleCount = 1;
            } else {
                ++sampleCount;
            }

        }
        if (mSamplesHaveSameSize) {

            if (mStszTableEntries->count() >= 2 && previousSampleSize != sampleSize) {

                mSamplesHaveSameSize = false;
            }
            previousSampleSize = sampleSize;
        }
        ALOGV("%s timestampUs/lastTimestampUs: %" PRId64 "/%" PRId64,
                trackName, timestampUs, lastTimestampUs);
        lastDurationUs = timestampUs - lastTimestampUs;
        lastDurationTicks = currDurationTicks;
        lastTimestampUs = timestampUs;

        if (isSync != 0) {

            addOneStssTableEntry(mStszTableEntries->count());

        }

        if (mTrackingProgressStatus) {
            if (mPreviousTrackTimeUs <= 0) {
                mPreviousTrackTimeUs = mStartTimestampUs;
            }
            trackProgressStatus(timestampUs);
        }

#ifndef MTK_AOSP_ENHANCEMENT
        if (!hasMultipleTracks) {
			off64_t offset = mIsAvc? mOwner->addLengthPrefixedSample_l(copy)
                                 : mOwner->addSample_l(copy);

            uint32_t count = (mOwner->use32BitFileOffset()
                        ? mStcoTableEntries->count()
                        : mCo64TableEntries->count());

            if (count == 0) {
                addChunkOffset(offset);
            }
            copy->release();
            copy = NULL;
            continue;
        }
#endif

        mChunkSamples.push_back(copy);
        if (interleaveDurationUs == 0) {
            addOneStscTableEntry(++nChunks, 1);
            bufferChunk(timestampUs);
        } else {
            if (chunkTimestampUs == 0) {
                chunkTimestampUs = timestampUs;
            } else {
                int64_t chunkDurationUs = timestampUs - chunkTimestampUs;
                if (chunkDurationUs > interleaveDurationUs) {
                    if (chunkDurationUs > mMaxChunkDurationUs) {
                        mMaxChunkDurationUs = chunkDurationUs;
                    }
                    ++nChunks;
                    if (nChunks == 1 ||  // First chunk
                        lastSamplesPerChunk != mChunkSamples.size()) {
                        lastSamplesPerChunk = mChunkSamples.size();
                        addOneStscTableEntry(nChunks, lastSamplesPerChunk);
                    }
                    bufferChunk(timestampUs);
                    chunkTimestampUs = timestampUs;
                }
            }
        }

    }

    if (isTrackMalFormed()) {
        err = ERROR_MALFORMED;
    }

    mOwner->trackProgressStatus(mTrackId, -1, err);

    // Last chunk
#ifdef MTK_AOSP_ENHANCEMENT
    if (!mChunkSamples.empty()) {
        addOneStscTableEntry(++nChunks, mChunkSamples.size());
        bufferChunk(timestampUs);
    }
#else
    if (!hasMultipleTracks) {
        addOneStscTableEntry(1, mStszTableEntries->count());
    } else if (!mChunkSamples.empty()) {
        addOneStscTableEntry(++nChunks, mChunkSamples.size());
        bufferChunk(timestampUs);
    }
#endif

    // We don't really know how long the last frame lasts, since
    // there is no frame time after it, just repeat the previous
    // frame's duration.
	if (mStszTableEntries->count() == 1) {
#ifdef MTK_AOSP_ENHANCEMENT //Do not set duration to 0 even if there is only one frame in this track
		ALOGW("Only one frame in %s track, Set scaled duration to 1", mIsAudio? "audio": "video");
		if (mTimeScale >= 1000000LL) {
			lastDurationUs = 1;
		} else {
			lastDurationUs = (1000000LL + (mTimeScale >> 1)) / mTimeScale;
		}
		lastDurationTicks = (lastDurationUs * mTimeScale + 5E5) / 1E6;
#else
        lastDurationUs = 0;  // A single sample's duration
        lastDurationTicks = 0;
#endif
    } else {
        ++sampleCount;  // Count for the last sample
    }

    if (mStszTableEntries->count() <= 2) {

        addOneSttsTableEntry(1, lastDurationTicks);
        if (sampleCount - 1 > 0) {
            addOneSttsTableEntry(sampleCount - 1, lastDurationTicks);
        }
    } else {
        addOneSttsTableEntry(sampleCount, lastDurationTicks);
    }

    // The last ctts box may not have been written yet, and this
    // is to make sure that we write out the last ctts box.
    if (currCttsOffsetTimeTicks == lastCttsOffsetTimeTicks) {
        if (cttsSampleCount > 0) {
            addOneCttsTableEntry(cttsSampleCount, lastCttsOffsetTimeTicks);
        }
    }

#ifdef MTK_AOSP_ENHANCEMENT
	if(!hasMultipleTracks && mIsAudio) { //for Audio Record CTS test
		int64_t bufferDuration = 0;
		if(!(meta_data.get()) || !(meta_data->findInt64(kKeyDuration, &bufferDuration))) {
			 mTrackDurationUs += lastDurationUs;
		}
	}
	else
#endif
    mTrackDurationUs += lastDurationUs;

    mReachedEOS = true;

    sendTrackSummary(hasMultipleTracks);

    ALOGI("Received total/0-length (%d/%d) buffers and encoded %d frames. - %s",
            count, nZeroLengthFrames, mStszTableEntries->count(), trackName);
    if (mIsAudio) {
        ALOGI("Audio track drift time: %" PRId64 " us", mOwner->getDriftTimeUs());
    }

    if (err == ERROR_END_OF_STREAM) {
#ifdef MTK_AOSP_ENHANCEMENT
		signalTrackThreadExit();
#endif
        return OK;
    }
#ifdef MTK_AOSP_ENHANCEMENT
	signalTrackThreadExit();
#endif
    return err;
}

bool MPEG4Writer::Track::isTrackMalFormed() const {

    if (mStszTableEntries->count() == 0) {                      // no samples written

        ALOGE("The number of recorded samples is 0");
        return true;
    }

    if (!mIsAudio && mStssTableEntries->count() == 0) {  // no sync frames for video
        ALOGE("There are no sync frames for video track");
#ifdef MTK_AOSP_ENHANCEMENT
	mOwner->notify(MEDIA_RECORDER_EVENT_INFO, 0x7FFF, 0); //for VT Recording
#else
	return true;
#endif
    }

    if (OK != checkCodecSpecificData()) {         // no codec specific data
        return true;
    }

    return false;
}

void MPEG4Writer::Track::sendTrackSummary(bool hasMultipleTracks) {

    // Send track summary only if test mode is enabled.
    if (!isTestModeEnabled()) {
        return;
    }

    int trackNum = (mTrackId << 28);

    mOwner->notify(MEDIA_RECORDER_TRACK_EVENT_INFO,
                    trackNum | MEDIA_RECORDER_TRACK_INFO_TYPE,
                    mIsAudio? 0: 1);

    mOwner->notify(MEDIA_RECORDER_TRACK_EVENT_INFO,
                    trackNum | MEDIA_RECORDER_TRACK_INFO_DURATION_MS,
                    mTrackDurationUs / 1000);

    mOwner->notify(MEDIA_RECORDER_TRACK_EVENT_INFO,
                    trackNum | MEDIA_RECORDER_TRACK_INFO_ENCODED_FRAMES,

                    mStszTableEntries->count());


    {
        // The system delay time excluding the requested initial delay that
        // is used to eliminate the recording sound.
        int64_t startTimeOffsetUs = mOwner->getStartTimeOffsetMs() * 1000LL;
        if (startTimeOffsetUs < 0) {  // Start time offset was not set
            startTimeOffsetUs = kInitialDelayTimeUs;
        }
        int64_t initialDelayUs =
            mFirstSampleTimeRealUs - mStartTimeRealUs - startTimeOffsetUs;

        mOwner->notify(MEDIA_RECORDER_TRACK_EVENT_INFO,
                    trackNum | MEDIA_RECORDER_TRACK_INFO_INITIAL_DELAY_MS,
                    (initialDelayUs) / 1000);
    }

    mOwner->notify(MEDIA_RECORDER_TRACK_EVENT_INFO,
                    trackNum | MEDIA_RECORDER_TRACK_INFO_DATA_KBYTES,
                    mMdatSizeBytes / 1024);

    if (hasMultipleTracks) {
        mOwner->notify(MEDIA_RECORDER_TRACK_EVENT_INFO,
                    trackNum | MEDIA_RECORDER_TRACK_INFO_MAX_CHUNK_DUR_MS,
                    mMaxChunkDurationUs / 1000);

        int64_t moovStartTimeUs = mOwner->getStartTimestampUs();
        if (mStartTimestampUs != moovStartTimeUs) {
            int64_t startTimeOffsetUs = mStartTimestampUs - moovStartTimeUs;
            mOwner->notify(MEDIA_RECORDER_TRACK_EVENT_INFO,
                    trackNum | MEDIA_RECORDER_TRACK_INFO_START_OFFSET_MS,
                    startTimeOffsetUs / 1000);
        }
    }
}

void MPEG4Writer::Track::trackProgressStatus(int64_t timeUs, status_t err) {
    ALOGV("trackProgressStatus: %" PRId64 " us", timeUs);

    if (mTrackEveryTimeDurationUs > 0 &&
        timeUs - mPreviousTrackTimeUs >= mTrackEveryTimeDurationUs) {
        ALOGV("Fire time tracking progress status at %" PRId64 " us", timeUs);
        mOwner->trackProgressStatus(mTrackId, timeUs - mPreviousTrackTimeUs, err);
        mPreviousTrackTimeUs = timeUs;
    }
}

void MPEG4Writer::trackProgressStatus(
        size_t trackId, int64_t timeUs, status_t err) {
    Mutex::Autolock lock(mLock);
    int32_t trackNum = (trackId << 28);

    // Error notification
    // Do not consider ERROR_END_OF_STREAM an error
    if (err != OK && err != ERROR_END_OF_STREAM) {
        notify(MEDIA_RECORDER_TRACK_EVENT_ERROR,
               trackNum | MEDIA_RECORDER_TRACK_ERROR_GENERAL,
               err);
#ifdef MTK_AOSP_ENHANCEMENT
        ALOGW("notify track err = %d", err);
#endif
        return;
    }

    if (timeUs == -1) {
        // Send completion notification
        notify(MEDIA_RECORDER_TRACK_EVENT_INFO,
               trackNum | MEDIA_RECORDER_TRACK_INFO_COMPLETION_STATUS,
               err);
    } else {
        // Send progress status
        notify(MEDIA_RECORDER_TRACK_EVENT_INFO,
               trackNum | MEDIA_RECORDER_TRACK_INFO_PROGRESS_IN_TIME,
               timeUs / 1000);
    }
}

void MPEG4Writer::setDriftTimeUs(int64_t driftTimeUs) {
    ALOGV("setDriftTimeUs: %" PRId64 " us", driftTimeUs);
    Mutex::Autolock autolock(mLock);
    mDriftTimeUs = driftTimeUs;
}

int64_t MPEG4Writer::getDriftTimeUs() {
    ALOGV("getDriftTimeUs: %" PRId64 " us", mDriftTimeUs);
    Mutex::Autolock autolock(mLock);
    return mDriftTimeUs;
}

bool MPEG4Writer::isRealTimeRecording() const {
    return mIsRealTimeRecording;
}

bool MPEG4Writer::useNalLengthFour() {
    return mUse4ByteNalLength;
}

void MPEG4Writer::Track::bufferChunk(int64_t timestampUs) {
    ALOGV("bufferChunk");
#ifdef MTK_AOSP_ENHANCEMENT
	ALOGD("%s bufferChunk", mIsAudio?"A":"V");
#endif

    Chunk chunk(this, timestampUs, mChunkSamples);
    mOwner->bufferChunk(chunk);
    mChunkSamples.clear();
}

int64_t MPEG4Writer::Track::getDurationUs() const {
    return mTrackDurationUs;
}

int64_t MPEG4Writer::Track::getEstimatedTrackSizeBytes() const {
    return mEstimatedTrackSizeBytes;
}

status_t MPEG4Writer::Track::checkCodecSpecificData() const {
    const char *mime;
    CHECK(mMeta->findCString(kKeyMIMEType, &mime));
    if (!strcasecmp(MEDIA_MIMETYPE_AUDIO_AAC, mime) ||
        !strcasecmp(MEDIA_MIMETYPE_VIDEO_MPEG4, mime) ||
        !strcasecmp(MEDIA_MIMETYPE_VIDEO_AVC, mime)
#if defined(MTK_AOSP_ENHANCEMENT) //&& defined(MTK_VIDEO_HEVC_SUPPORT)
        || !strcasecmp(MEDIA_MIMETYPE_VIDEO_HEVC, mime)
#endif
		) {
        if (!mCodecSpecificData ||
            mCodecSpecificDataSize <= 0) {
            ALOGE("Missing codec specific data");
            return ERROR_MALFORMED;
        }
    } else {
        if (mCodecSpecificData ||
            mCodecSpecificDataSize > 0) {
            ALOGE("Unexepected codec specific data found");
            return ERROR_MALFORMED;
        }
    }
    return OK;
}

void MPEG4Writer::Track::writeTrackHeader(bool use32BitOffset) {

#ifdef MTK_AOSP_ENHANCEMENT
	ALOGD("%s track writeTrackHeader ++, time scale: %d",
        mIsAudio? "Audio": "Video", mTimeScale);
#else
    ALOGV("%s track time scale: %d",
        mIsAudio? "Audio": "Video", mTimeScale);
#endif

    uint32_t now = getMpeg4Time();
    mOwner->beginBox("trak");
#ifdef MTK_AOSP_ENHANCEMENT
		if (!mIsAudio) {
			writeUdtaBox();
		}
#endif
        writeTkhdBox(now);
#if defined(MTK_AOSP_ENHANCEMENT) && defined(WRITER_ENABLE_EDTS_BOX)  // write 'edts' box to record video/audio start time offset
		if(mOwner->isEnableEdtsBoxForTimeOffset()){
			ALOGI("enable edts box to record start time offset");
			writeEdtsBox();
		}
#endif
        mOwner->beginBox("mdia");
            writeMdhdBox(now);
            writeHdlrBox();
            mOwner->beginBox("minf");
                if (mIsAudio) {
                    writeSmhdBox();
                } else {
                    writeVmhdBox();
                }
                writeDinfBox();
                writeStblBox(use32BitOffset);
            mOwner->endBox();  // minf
        mOwner->endBox();  // mdia
    mOwner->endBox();  // trak
#ifdef MTK_AOSP_ENHANCEMENT
	ALOGD("%s track writeTrackHeader --",mIsAudio? "Audio": "Video");
#endif

}
void MPEG4Writer::Track::writeStblBox(bool use32BitOffset) {
    mOwner->beginBox("stbl");
    mOwner->beginBox("stsd");
    mOwner->writeInt32(0);               // version=0, flags=0
    mOwner->writeInt32(1);               // entry count
    if (mIsAudio) {
        writeAudioFourCCBox();
    } else {
        writeVideoFourCCBox();
    }
    mOwner->endBox();  // stsd
    writeSttsBox();
    writeCttsBox();
    if (!mIsAudio) {
        writeStssBox();
    }
    writeStszBox();
    writeStscBox();
    writeStcoBox(use32BitOffset);
    mOwner->endBox();  // stbl
}

void MPEG4Writer::Track::writeVideoFourCCBox() {
    const char *mime;
    bool success = mMeta->findCString(kKeyMIMEType, &mime);
    CHECK(success);
    if (!strcasecmp(MEDIA_MIMETYPE_VIDEO_MPEG4, mime)) {
        mOwner->beginBox("mp4v");
    } else if (!strcasecmp(MEDIA_MIMETYPE_VIDEO_H263, mime)) {
        mOwner->beginBox("s263");
    } else if (!strcasecmp(MEDIA_MIMETYPE_VIDEO_AVC, mime)) {
        mOwner->beginBox("avc1");
#if defined(MTK_AOSP_ENHANCEMENT) //&& defined(MTK_VIDEO_HEVC_SUPPORT)
    } else if (!strcasecmp(MEDIA_MIMETYPE_VIDEO_HEVC, mime)) {
        mOwner->beginBox("hvc1");
#endif
    } else {
        ALOGE("Unknown mime type '%s'.", mime);
        CHECK(!"should not be here, unknown mime type.");
    }

    mOwner->writeInt32(0);           // reserved
    mOwner->writeInt16(0);           // reserved
    mOwner->writeInt16(1);           // data ref index
    mOwner->writeInt16(0);           // predefined
    mOwner->writeInt16(0);           // reserved
    mOwner->writeInt32(0);           // predefined
    mOwner->writeInt32(0);           // predefined
    mOwner->writeInt32(0);           // predefined

    int32_t width, height;
    success = mMeta->findInt32(kKeyWidth, &width);
    success = success && mMeta->findInt32(kKeyHeight, &height);
    CHECK(success);

    mOwner->writeInt16(width);
    mOwner->writeInt16(height);
    mOwner->writeInt32(0x480000);    // horiz resolution
    mOwner->writeInt32(0x480000);    // vert resolution
    mOwner->writeInt32(0);           // reserved
    mOwner->writeInt16(1);           // frame count
    mOwner->writeInt8(0);            // compressor string length
    mOwner->write("                               ", 31);
    mOwner->writeInt16(0x18);        // depth
    mOwner->writeInt16(-1);          // predefined

#ifdef MTK_AOSP_ENHANCEMENT
	if (mCodecSpecificDataSize > 104) {
		ALOGW("MPEG4 codec info size large, %d", mCodecSpecificDataSize);
	}
#else
    CHECK_LT(23 + mCodecSpecificDataSize, 128);
#endif

    if (!strcasecmp(MEDIA_MIMETYPE_VIDEO_MPEG4, mime)) {
        writeMp4vEsdsBox();
    } else if (!strcasecmp(MEDIA_MIMETYPE_VIDEO_H263, mime)) {
        writeD263Box();
    } else if (!strcasecmp(MEDIA_MIMETYPE_VIDEO_AVC, mime)) {
        writeAvccBox();
#if defined(MTK_AOSP_ENHANCEMENT) //&& defined(MTK_VIDEO_HEVC_SUPPORT)
	} else if (!strcasecmp(MEDIA_MIMETYPE_VIDEO_HEVC, mime)) {
		writeHvccBox();
#endif
    }

    writePaspBox();
    mOwner->endBox();  // mp4v, s263 or avc1
}

void MPEG4Writer::Track::writeAudioFourCCBox() {
    const char *mime;
    bool success = mMeta->findCString(kKeyMIMEType, &mime);
    CHECK(success);
    const char *fourcc = NULL;
    if (!strcasecmp(MEDIA_MIMETYPE_AUDIO_AMR_NB, mime)) {
        fourcc = "samr";
    } else if (!strcasecmp(MEDIA_MIMETYPE_AUDIO_AMR_WB, mime)) {
        fourcc = "sawb";
    } else if (!strcasecmp(MEDIA_MIMETYPE_AUDIO_AAC, mime)) {
        fourcc = "mp4a";
    } else {
        ALOGE("Unknown mime type '%s'.", mime);
        CHECK(!"should not be here, unknown mime type.");
    }

    mOwner->beginBox(fourcc);        // audio format
    mOwner->writeInt32(0);           // reserved
    mOwner->writeInt16(0);           // reserved
    mOwner->writeInt16(0x1);         // data ref index
    mOwner->writeInt32(0);           // reserved
    mOwner->writeInt32(0);           // reserved
    int32_t nChannels;
    CHECK_EQ(true, mMeta->findInt32(kKeyChannelCount, &nChannels));
    mOwner->writeInt16(nChannels);   // channel count
    mOwner->writeInt16(16);          // sample size
    mOwner->writeInt16(0);           // predefined
    mOwner->writeInt16(0);           // reserved

    int32_t samplerate;
    success = mMeta->findInt32(kKeySampleRate, &samplerate);
    CHECK(success);
    mOwner->writeInt32(samplerate << 16);
    if (!strcasecmp(MEDIA_MIMETYPE_AUDIO_AAC, mime)) {
        writeMp4aEsdsBox();
    } else if (!strcasecmp(MEDIA_MIMETYPE_AUDIO_AMR_NB, mime) ||
               !strcasecmp(MEDIA_MIMETYPE_AUDIO_AMR_WB, mime)) {
        writeDamrBox();
    }
    mOwner->endBox();
}

void MPEG4Writer::Track::writeMp4aEsdsBox() {
    mOwner->beginBox("esds");
    CHECK(mCodecSpecificData);
    CHECK_GT(mCodecSpecificDataSize, 0);

    // Make sure all sizes encode to a single byte.
    CHECK_LT(mCodecSpecificDataSize + 23, 128);

    mOwner->writeInt32(0);     // version=0, flags=0
    mOwner->writeInt8(0x03);   // ES_DescrTag
    mOwner->writeInt8(23 + mCodecSpecificDataSize);
    mOwner->writeInt16(0x0000);// ES_ID
    mOwner->writeInt8(0x00);

    mOwner->writeInt8(0x04);   // DecoderConfigDescrTag
    mOwner->writeInt8(15 + mCodecSpecificDataSize);
    mOwner->writeInt8(0x40);   // objectTypeIndication ISO/IEC 14492-2
    mOwner->writeInt8(0x15);   // streamType AudioStream

    mOwner->writeInt16(0x03);  // XXX
    mOwner->writeInt8(0x00);   // buffer size 24-bit
#ifdef MTK_AOSP_ENHANCEMENT
	mOwner->writeInt32(mTrackBitRate); // max bit rate
    mOwner->writeInt32(mTrackBitRate); // avg bit rate
#else
    mOwner->writeInt32(96000); // max bit rate
    mOwner->writeInt32(96000); // avg bit rate
#endif

    mOwner->writeInt8(0x05);   // DecoderSpecificInfoTag
    mOwner->writeInt8(mCodecSpecificDataSize);
    mOwner->write(mCodecSpecificData, mCodecSpecificDataSize);

    static const uint8_t kData2[] = {
        0x06,  // SLConfigDescriptorTag
        0x01,
        0x02
    };
    mOwner->write(kData2, sizeof(kData2));

    mOwner->endBox();  // esds
}

void MPEG4Writer::Track::writeMp4vEsdsBox() {
    CHECK(mCodecSpecificData);
    CHECK_GT(mCodecSpecificDataSize, 0);
    mOwner->beginBox("esds");

    mOwner->writeInt32(0);    // version=0, flags=0

    mOwner->writeInt8(0x03);  // ES_DescrTag
    mOwner->writeInt8(23 + mCodecSpecificDataSize);
    mOwner->writeInt16(0x0000);  // ES_ID
    mOwner->writeInt8(0x1f);

    mOwner->writeInt8(0x04);  // DecoderConfigDescrTag
    mOwner->writeInt8(15 + mCodecSpecificDataSize);
    mOwner->writeInt8(0x20);  // objectTypeIndication ISO/IEC 14492-2
    mOwner->writeInt8(0x11);  // streamType VisualStream

    static const uint8_t kData[] = {
        0x01, 0x77, 0x00,
        0x00, 0x03, 0xe8, 0x00,
        0x00, 0x03, 0xe8, 0x00
    };
    mOwner->write(kData, sizeof(kData));

    mOwner->writeInt8(0x05);  // DecoderSpecificInfoTag

    mOwner->writeInt8(mCodecSpecificDataSize);
    mOwner->write(mCodecSpecificData, mCodecSpecificDataSize);

    static const uint8_t kData2[] = {
        0x06,  // SLConfigDescriptorTag
        0x01,
        0x02
    };
    mOwner->write(kData2, sizeof(kData2));

    mOwner->endBox();  // esds
}

void MPEG4Writer::Track::writeTkhdBox(uint32_t now) {
    mOwner->beginBox("tkhd");

#ifdef MTK_AOSP_ENHANCEMENT
	syncMoovStartTime();

	int32_t mvhdTimeScale = mOwner->getTimeScale();
	int64_t tkhdDurationInTimescale = (mTrackDurationUs * mvhdTimeScale +5E5) / 1E6 ;
	ALOGI("writeTkhdBox,tkhdDurationInTimescale(movieTimeScale)=0x%x(%" PRId64 ")",tkhdDurationInTimescale,tkhdDurationInTimescale);
	uint8_t version = 0;
	if(tkhdDurationInTimescale > kMax32BitDuration){
		ALOGI("writeTkhdBox,tkhdDurationInTimescale(movieTimeScale) is large than 0x7FFFFFFF,change to use 64 bit for duration");
		version = 1;
	}
	if(version == 1){
		// Flags = 7 to indicate that the track is enabled, and
	    // part of the presentation
	    mOwner->writeInt32(0x1000007);          // version=1, flags=7
	    mOwner->writeInt64((int64_t)now);       // creation time in 64 bit
	    mOwner->writeInt64((int64_t)now);       // modification time in 64bit
	    mOwner->writeInt32(mTrackId);      		// track id starts with 1
	    mOwner->writeInt32(0);             		// reserved
	    mOwner->writeInt64(tkhdDurationInTimescale);  // in mvhd timescale
	}else{
		// Flags = 7 to indicate that the track is enabled, and
	    // part of the presentation
	    mOwner->writeInt32(0x07);          // version=0, flags=7
	    mOwner->writeInt32(now);           // creation time
	    mOwner->writeInt32(now);           // modification time
	    mOwner->writeInt32(mTrackId);      // track id starts with 1
	    mOwner->writeInt32(0);             // reserved
	    mOwner->writeInt32((int32_t)tkhdDurationInTimescale);  // in mvhd timescale
	}

#else
    // Flags = 7 to indicate that the track is enabled, and
    // part of the presentation
    mOwner->writeInt32(0x07);          // version=0, flags=7
    mOwner->writeInt32(now);           // creation time
    mOwner->writeInt32(now);           // modification time
    mOwner->writeInt32(mTrackId);      // track id starts with 1
    mOwner->writeInt32(0);             // reserved
    int64_t trakDurationUs = getDurationUs();
    int32_t mvhdTimeScale = mOwner->getTimeScale();
    int32_t tkhdDuration =
        (trakDurationUs * mvhdTimeScale + 5E5) / 1E6;
    mOwner->writeInt32(tkhdDuration);  // in mvhd timescale
#endif
    mOwner->writeInt32(0);             // reserved
    mOwner->writeInt32(0);             // reserved
    mOwner->writeInt16(0);             // layer
    mOwner->writeInt16(0);             // alternate group
    mOwner->writeInt16(mIsAudio ? 0x100 : 0);  // volume
    mOwner->writeInt16(0);             // reserved

    mOwner->writeCompositionMatrix(mRotation);       // matrix

    if (mIsAudio) {
        mOwner->writeInt32(0);
        mOwner->writeInt32(0);
    } else {
        int32_t width, height;
        bool success = mMeta->findInt32(kKeyWidth, &width);
        success = success && mMeta->findInt32(kKeyHeight, &height);
        CHECK(success);

        mOwner->writeInt32(width << 16);   // 32-bit fixed-point value
        mOwner->writeInt32(height << 16);  // 32-bit fixed-point value
    }
    mOwner->endBox();  // tkhd
}

void MPEG4Writer::Track::writeVmhdBox() {
    mOwner->beginBox("vmhd");
    mOwner->writeInt32(0x01);        // version=0, flags=1
    mOwner->writeInt16(0);           // graphics mode
    mOwner->writeInt16(0);           // opcolor
    mOwner->writeInt16(0);
    mOwner->writeInt16(0);
    mOwner->endBox();
}

void MPEG4Writer::Track::writeSmhdBox() {
    mOwner->beginBox("smhd");
    mOwner->writeInt32(0);           // version=0, flags=0
    mOwner->writeInt16(0);           // balance
    mOwner->writeInt16(0);           // reserved
    mOwner->endBox();
}

void MPEG4Writer::Track::writeHdlrBox() {
    mOwner->beginBox("hdlr");
    mOwner->writeInt32(0);             // version=0, flags=0
    mOwner->writeInt32(0);             // component type: should be mhlr
    mOwner->writeFourcc(mIsAudio ? "soun" : "vide");  // component subtype
    mOwner->writeInt32(0);             // reserved
    mOwner->writeInt32(0);             // reserved
    mOwner->writeInt32(0);             // reserved
    // Removing "r" for the name string just makes the string 4 byte aligned
    mOwner->writeCString(mIsAudio ? "SoundHandle": "VideoHandle");  // name
    mOwner->endBox();
}

void MPEG4Writer::Track::writeMdhdBox(uint32_t now) {
    int64_t trakDurationUs = getDurationUs();
    mOwner->beginBox("mdhd");
#ifdef MTK_AOSP_ENHANCEMENT
	uint8_t version = 0;
	int64_t trackDurationInTimeScale = (trakDurationUs * mTimeScale + 5E5) / 1E6;
	ALOGI("writeMdhdBox,trackDurationInTimeScale(mediaTimeScale)=0x%x(%" PRId64 ")",trackDurationInTimeScale,trackDurationInTimeScale);
	if(trackDurationInTimeScale > kMax32BitDuration){
		ALOGI("writeMdhdBox,trackDurationInTimeScale(mediaTimeScale) is large than 0x7FFFFFFF,change to use 64 bit for duration");
		version = 1;
	}
	if(version == 1){ //eanble 64 bit for duration,creation and modification time
		mOwner->writeInt32(0x1000000); 		//version = 1, flag = 0
		mOwner->writeInt64(now);           // creation time in 64bit
	    mOwner->writeInt64(now);           // modification time in 64bit
	    mOwner->writeInt32(mTimeScale);    // media timescale
	    mOwner->writeInt64(trackDurationInTimeScale);  // use media timescale
	}else{
		mOwner->writeInt32(0);				// version=0, flags=0
		mOwner->writeInt32(now);           // creation time
	    mOwner->writeInt32(now);           // modification time
	    mOwner->writeInt32(mTimeScale);    // media timescale
	    int32_t mdhdDuration = (int32_t)trackDurationInTimeScale;
	    mOwner->writeInt32(mdhdDuration);  // use media timescale
	}
#else
    mOwner->writeInt32(0);             // version=0, flags=0
    mOwner->writeInt32(now);           // creation time
    mOwner->writeInt32(now);           // modification time
    mOwner->writeInt32(mTimeScale);    // media timescale
    int32_t mdhdDuration = (trakDurationUs * mTimeScale + 5E5) / 1E6;
    mOwner->writeInt32(mdhdDuration);  // use media timescale
#endif
    // Language follows the three letter standard ISO-639-2/T
    // 'e', 'n', 'g' for "English", for instance.
    // Each character is packed as the difference between its ASCII value and 0x60.
    // For "English", these are 00101, 01110, 00111.
    // XXX: Where is the padding bit located: 0x15C7?
    mOwner->writeInt16(0);             // language code
    mOwner->writeInt16(0);             // predefined
    mOwner->endBox();
}

void MPEG4Writer::Track::writeDamrBox() {
    // 3gpp2 Spec AMRSampleEntry fields
    mOwner->beginBox("damr");
    mOwner->writeCString("   ");  // vendor: 4 bytes
    mOwner->writeInt8(0);         // decoder version
    mOwner->writeInt16(0x83FF);   // mode set: all enabled
    mOwner->writeInt8(0);         // mode change period
    mOwner->writeInt8(1);         // frames per sample
    mOwner->endBox();
}

void MPEG4Writer::Track::writeUrlBox() {
    // The table index here refers to the sample description index
    // in the sample table entries.
    mOwner->beginBox("url ");
    mOwner->writeInt32(1);  // version=0, flags=1 (self-contained)
    mOwner->endBox();  // url
}

void MPEG4Writer::Track::writeDrefBox() {
    mOwner->beginBox("dref");
    mOwner->writeInt32(0);  // version=0, flags=0
    mOwner->writeInt32(1);  // entry count (either url or urn)
    writeUrlBox();
    mOwner->endBox();  // dref
}

void MPEG4Writer::Track::writeDinfBox() {
    mOwner->beginBox("dinf");
    writeDrefBox();
    mOwner->endBox();  // dinf
}

void MPEG4Writer::Track::writeAvccBox() {
    CHECK(mCodecSpecificData);
    CHECK_GE(mCodecSpecificDataSize, 5);

    // Patch avcc's lengthSize field to match the number
    // of bytes we use to indicate the size of a nal unit.
    uint8_t *ptr = (uint8_t *)mCodecSpecificData;
    ptr[4] = (ptr[4] & 0xfc) | (mOwner->useNalLengthFour() ? 3 : 1);
#ifdef MTK_AOSP_ENHANCEMENT
	if(mMultiSliceBS)
	 ptr[4] = (ptr[4] & 0xfc) | 3;  //for multi-NAL case must be 4 bytes
#endif
    mOwner->beginBox("avcC");
    mOwner->write(mCodecSpecificData, mCodecSpecificDataSize);
    mOwner->endBox();  // avcC
}

void MPEG4Writer::Track::writeD263Box() {
    mOwner->beginBox("d263");
    mOwner->writeInt32(0);  // vendor
    mOwner->writeInt8(0);   // decoder version
    mOwner->writeInt8(10);  // level: 10
    mOwner->writeInt8(0);   // profile: 0
    mOwner->endBox();  // d263
}

// This is useful if the pixel is not square
void MPEG4Writer::Track::writePaspBox() {
    mOwner->beginBox("pasp");
    mOwner->writeInt32(1 << 16);  // hspacing
    mOwner->writeInt32(1 << 16);  // vspacing
    mOwner->endBox();  // pasp
}

int32_t MPEG4Writer::Track::getStartTimeOffsetScaledTime() const {
    int64_t trackStartTimeOffsetUs = 0;
    int64_t moovStartTimeUs = mOwner->getStartTimestampUs();
    if (mStartTimestampUs != moovStartTimeUs) {
        CHECK_GT(mStartTimestampUs, moovStartTimeUs);
        trackStartTimeOffsetUs = mStartTimestampUs - moovStartTimeUs;
    }
#ifdef MTK_AOSP_ENHANCEMENT
	ALOGD("getStartTimeOffsetScaledTime(%" PRId64 "us) for %s track", trackStartTimeOffsetUs, isAudio()? "Audio": "Video");
#endif
    return (trackStartTimeOffsetUs *  mTimeScale + 500000LL) / 1000000LL;
}

void MPEG4Writer::Track::writeSttsBox() {
    mOwner->beginBox("stts");
    mOwner->writeInt32(0);  // version=0, flags=0
    uint32_t duration;
    CHECK(mSttsTableEntries->get(duration, 1));
    duration = htonl(duration);  // Back to host byte order

#if defined(MTK_AOSP_ENHANCEMENT) && defined(WRITER_ENABLE_EDTS_BOX)
	if(!mOwner->isEnableEdtsBoxForTimeOffset())
#endif
    mSttsTableEntries->set(htonl(duration + getStartTimeOffsetScaledTime()), 1);

    mSttsTableEntries->write(mOwner);

    mOwner->endBox();  // stts
}

void MPEG4Writer::Track::writeCttsBox() {
    if (mIsAudio) {  // ctts is not for audio
        return;
    }

    // There is no B frame at all
    if (mMinCttsOffsetTimeUs == mMaxCttsOffsetTimeUs) {
        return;
    }

    // Do not write ctts box when there is no need to have it.
    if (mCttsTableEntries->count() == 0) {
        return;
    }

    ALOGV("ctts box has %d entries with range [%" PRId64 ", %" PRId64 "]",
            mCttsTableEntries->count(), mMinCttsOffsetTimeUs, mMaxCttsOffsetTimeUs);

    mOwner->beginBox("ctts");
    mOwner->writeInt32(0);  // version=0, flags=0
    uint32_t duration;
    CHECK(mCttsTableEntries->get(duration, 1));
    duration = htonl(duration);  // Back host byte order
    mCttsTableEntries->set(htonl(duration + getStartTimeOffsetScaledTime() - mMinCttsOffsetTimeUs), 1);
    mCttsTableEntries->write(mOwner);
    mOwner->endBox();  // ctts
}

void MPEG4Writer::Track::writeStssBox() {
    mOwner->beginBox("stss");
    mOwner->writeInt32(0);  // version=0, flags=0
    mStssTableEntries->write(mOwner);
    mOwner->endBox();  // stss
}

void MPEG4Writer::Track::writeStszBox() {
    mOwner->beginBox("stsz");
    mOwner->writeInt32(0);  // version=0, flags=0

#ifdef MTK_AOSP_ENHANCEMENT
	ALOGD("writeStszBox for %s track", isAudio()? "Audio": "Video");
	uint32_t sampleSize;
    if (mSamplesHaveSameSize && mStszTableEntries->get(sampleSize, 0)) {
		ALOGD("get same sample size %x for %s track", sampleSize, isAudio()? "Audio": "Video");
		mOwner->writeInt32(htonl(sampleSize));//if samples have the same size, we write any sample's size
		mOwner->writeInt32(mStszTableEntries->count());
    } else {
#endif
    mOwner->writeInt32(0);
	mStszTableEntries->write(mOwner);
#ifdef MTK_AOSP_ENHANCEMENT
	}
#endif
    mOwner->endBox();  // stsz
}

void MPEG4Writer::Track::writeStscBox() {
    mOwner->beginBox("stsc");
    mOwner->writeInt32(0);  // version=0, flags=0
    mStscTableEntries->write(mOwner);
    mOwner->endBox();  // stsc
}

void MPEG4Writer::Track::writeStcoBox(bool use32BitOffset) {
    mOwner->beginBox(use32BitOffset? "stco": "co64");
    mOwner->writeInt32(0);  // version=0, flags=0
    if (use32BitOffset) {
        mStcoTableEntries->write(mOwner);
    } else {
        mCo64TableEntries->write(mOwner);
    }
    mOwner->endBox();  // stco or co64
}

void MPEG4Writer::writeUdtaBox() {
    beginBox("udta");
#ifdef MTK_AOSP_ENHANCEMENT
	if (mAreGeoTagsAvailable) {
    	writeGeoDataBox();
	}
	if (mArtistTag.length() > 0 || mAlbumTag.length() > 0) {
		writeArtAlbBox();
	}
#else
	writeGeoDataBox();
#endif
    endBox();
}

/*
 * Geodata is stored according to ISO-6709 standard.
 */
void MPEG4Writer::writeGeoDataBox() {
    beginBox("\xA9xyz");
    /*
     * For historical reasons, any user data start
     * with "\0xA9", must be followed by its assoicated
     * language code.
     * 0x0012: text string length
     * 0x15c7: lang (locale) code: en
     */
    writeInt32(0x001215c7);
    writeLatitude(mLatitudex10000);
    writeLongitude(mLongitudex10000);
    writeInt8(0x2F);
    endBox();
}

#ifdef MTK_AOSP_ENHANCEMENT
static void dumpHex(const uint8_t *data, size_t length) {
	ALOGD("dumpHex, length = %d", length);
	for(uint32_t i=0; i<length; i++)	
		ALOGD("dumpHex: %2c", data[i]);
}

int64_t MPEG4Writer::getMaxDurationUs() {
    int64_t _TrackDurationUs = 0;
    for (List<Track *>::iterator it = mTracks.begin() ; it != mTracks.end() ; ++it) {
        if ((*it)->getDurationUs() >= _TrackDurationUs) {
            _TrackDurationUs = (*it)->getDurationUs();
        }
    }
    return _TrackDurationUs;
}

void MPEG4Writer::writeMetaData() {
	ALOGD("writeMetaData ++");
	// Fix up the size of the 'mdat' chunk.
#ifdef USE_FILE_CACHE
#ifdef SD_FULL_PROTECT
	if (mIsSDFull)
		processSDFull();
#endif
		ALOGD("mMdatOffset=%d, mOffset=%d", (int32_t)mMdatOffset, (int32_t)mOffset);
    if (mUse32BitOffset) {
			mCacheWriter->seek(mMdatOffset, SEEK_SET);
			int32_t size = htonl(static_cast<int32_t>(mOffset - mMdatOffset));
			mCacheWriter->write(&size, 1, 4);
		} else {
			mCacheWriter->seek(mMdatOffset + 8, SEEK_SET);
			int64_t size = mOffset - mMdatOffset;
			size = hton64(size);
			mCacheWriter->write(&size, 1, 8);
		}
		mCacheWriter->seek(mOffset, SEEK_SET);
#else
	if (mUse32BitOffset) {
        lseek64(mFd, mMdatOffset, SEEK_SET);
        int32_t size = htonl(static_cast<int32_t>(mOffset - mMdatOffset));
        ::write(mFd, &size, 4);
    } else {
        lseek64(mFd, mMdatOffset + 8, SEEK_SET);
        int64_t size = mOffset - mMdatOffset;
        size = hton64(size);
        ::write(mFd, &size, 8);
    }
    lseek64(mFd, mOffset, SEEK_SET);
#endif

   // const off64_t moovOffset = mOffset;
		// Construct moov box now
	    mMoovBoxBufferOffset = 0;
		mWriteMoovBoxToMemory = mStreamableFile;
	    if (mWriteMoovBoxToMemory) {
	        // There is no need to allocate in-memory cache
	        // for moov box if the file is not streamable.

	        mMoovBoxBuffer = (uint8_t *) malloc(mEstimatedMoovBoxSize);
	        CHECK(mMoovBoxBuffer != NULL);
	    }
    writeMoovBox(mMaxDuration);

		// mWriteMoovBoxToMemory could be set to false in
    	// MPEG4Writer::write() method
	if (mWriteMoovBoxToMemory) {
		mWriteMoovBoxToMemory = false;
        // Content of the moov box is saved in the cache, and the in-memory
        // moov box needs to be written to the file in a single shot.

		CHECK_LE(mMoovBoxBufferOffset + 8, mEstimatedMoovBoxSize);

		// Moov box
#ifdef USE_FILE_CACHE
		mCacheWriter->seek(mFreeBoxOffset, SEEK_SET);
#else
        lseek64(mFd, mFreeBoxOffset, SEEK_SET);
#endif
		mOffset = mFreeBoxOffset;
        write(mMoovBoxBuffer, 1, mMoovBoxBufferOffset);

			// Free box
#ifdef USE_FILE_CACHE
		mCacheWriter->seek(mOffset, SEEK_SET);
#else
        lseek64(mFd, mOffset, SEEK_SET);
#endif
		writeInt32(mEstimatedMoovBoxSize - mMoovBoxBufferOffset);
		write("free", 4);
	} else {
		ALOGI("The mp4 file will not be streamable.");
	}
		// Free in-memory cache for moov box
    if (mMoovBoxBuffer != NULL) {
		free(mMoovBoxBuffer);
		mMoovBoxBuffer = NULL;
		mMoovBoxBufferOffset = 0;
	}

	CHECK(mBoxes.empty());
	ALOGD("writeMetaData --");
}

int64_t MPEG4Writer::getPausedDuration() {
	return mPausedDurationUs;
}
void MPEG4Writer::setPausedDuration(int64_t paudedDurationUs) {
	mPausedDurationUs = paudedDurationUs;
}

void MPEG4Writer::signalResumed() {
	Mutex::Autolock autolock(mLock);
	mResumed = true;
	mResumedCondition.signal();
	ALOGD("Resume complete");
}

#ifdef SD_FULL_PROTECT
void MPEG4Writer::processSDFull() {
	int64_t metaEstimatedSize = META_DATA_HEADER_RESERVE_BYTES;
	int64_t removeChunkSize = 0;
	mOffset = mMdatOffset + (mUse32BitOffset?8 : 16);
	for (List<Track *>::iterator it = mTracks.begin();
		 it != mTracks.end(); ++it) {
		metaEstimatedSize += (*it)->getEstimatedTrackHeaderSizeBytes();
	}

	ALOGD("metaEstimatedSize=%" PRId64 "", metaEstimatedSize);
	for (List<WritedChunk*>::iterator it = mWritedChunks.end();
		 it != mWritedChunks.begin(); ) {
		 --it;
		if (removeChunkSize < metaEstimatedSize + mWriterCacheSize){
			removeChunkSize += (*it)->mSize;
			ALOGD("removeChunkSize=%" PRId64 "", removeChunkSize);
			((*it)->mTrack)->decWritedChunkNum();
			//mWritedChunks.erase(it);
		}
		else
			mOffset += (*it)->mSize;
	}

	for (List<Track *>::iterator it = mTracks.begin();
	  it != mTracks.end(); ++it) {
	 (*it)->updateTrackHeader();
	}
	mIsSDFull = false;
	mSDHasFull = true;
}

void MPEG4Writer::finishHandleSDFull()
{
	if (!mSDHasFull)
	{
		return;
	}
	off_t size;

    off64_t filesize = lseek64(mFd, 0, SEEK_END);
	size = filesize - mOffset;
	lseek64(mFd, mOffset, SEEK_SET);
	ALOGD("left size=%ld", size);
	size = ((size >> 24) & 0xff) | ((size >> 8) & 0xff00) | ((size << 8) & 0xff0000) | ((size << 24) & 0xff000000);//little endian
	::write(mFd, &size, 4);
	uint32_t freebox = 0x65657266;//"free" little endian
	::write(mFd, &freebox, 4);
}
#endif

void MPEG4Writer::init() {
	mResumed = true;
	mPausedDurationUs = 0x7FFFFFFFFFFFFFFFLL;
	mVideoStartTimeUs = -1;
	mWriterThreadExit = true;
	mArtistTag.setTo("");
	mAlbumTag.setTo("");
	
	mLowMemoryProtectThreshold = LOW_MEM_PROTECT_THRESHOLD;
	char param[PROPERTY_VALUE_MAX];
	int64_t value;
	property_get("vr.low.memory.protect.threshold", param, "-1");
	value = atol(param);
	ALOGD("value=%" PRId64 "", value);
	if(value > 0)
	{
	  mLowMemoryProtectThreshold = value;
	}
	ALOGD("@@[RECORD_PROPERTY]low.memory.protect.threshold = %" PRId64 "", mLowMemoryProtectThreshold);
	
	mBufferedDataSize = 0;
	mNotifyCounter = 0;
	mVideoQualityController = NULL;

	mMemInfoFd = -1;

#ifdef USE_FILE_CACHE
	mWriterCacheSize = DEFAULT_FILE_CACHE_SIZE;
	char param1[PROPERTY_VALUE_MAX];
	int32_t value1;
	property_get("vr.writer.cache.size", param1, "-1");
	value1 = atoi(param1);
	ALOGD("value1=%d", value1);
	if((value1 > 0) && (value1 < LOW_MEM_PROTECT_THRESHOLD))
	{
		mWriterCacheSize = value1;
	}
	ALOGD("@@[RECORD_PROPERTY]writer.cache.size = %d", mWriterCacheSize);
	mCacheWriter = new MPEG4FileCacheWriter(mFd, mWriterCacheSize);
	CHECK(mCacheWriter != NULL);
	mCacheWriter->setOwner(this);
#endif
	
#ifdef SD_FULL_PROTECT
		mIsSDFull = false;
		mSDHasFull = false;
#endif
	
#ifdef WRITER_ENABLE_EDTS_BOX
		mEnableEdtsBoxForTimeOffset = true;
#endif
	
}

void MPEG4Writer::releaseEx() { 
	
#ifdef USE_FILE_CACHE
	delete mCacheWriter;
#endif

#ifdef SD_FULL_PROTECT

	ALOGD("mWritedChunks.size=%d", mWritedChunks.size());
	while (!mWritedChunks.empty()) {
		List<WritedChunk*>::iterator it = mWritedChunks.begin();
		delete *it;
		(*it) = NULL;
		mWritedChunks.erase(it);
	}
	mWritedChunks.clear();
	ALOGD("~MPEG4Writer done");

#endif
	if(NULL != mVideoQualityController) {
		delete mVideoQualityController;
		mVideoQualityController = NULL;
	}

	if (mMemInfoFd >= 0) {
		ALOGW("close /proc/meminfo\n");
		close(mMemInfoFd);
	}
}

status_t MPEG4Writer::resume(MetaData *param) {
	ALOGD("Resume TimeUs=%" PRId64 "", systemTime()/1000);
	//check whether track thread has exited
	//if thread has exited, return directly
	for (List<Track *>::iterator it = mTracks.begin();
		it != mTracks.end(); ++it) {
			if((*it)->isTrackExited()){
				ALOGW("%s recording Track thread has exited",(*it)->isAudio()? "Audio":"Video");
				mResumed = true;
				return OK;
			}
	}
	
	status_t err = startTracks(param);
	if (OK == err)
	{
		Mutex::Autolock autolock(mLock);
		if (!mResumed) {
			ALOGD("wait resume complete");
			mResumedCondition.wait(mLock);
		}
		//mResumed = false;
	}
	return err;
}

void MPEG4Writer::initStart(MetaData *param) {
	if(NULL != mVideoQualityController)
		mVideoQualityController->init(param);

	const char *artist, *album;
	if (param &&
		param->findCString(kKeyArtist, &artist)) {
		mArtistTag.setTo(artist);
	}
	if (param &&
		param->findCString(kKeyAlbum, &album)) {
		mAlbumTag.setTo(album);
	}

	
#ifdef CHECK_LOW_MEM_BY_MEM_FREE
	useMemFreeCheckLM = true;
	ALOGD("useMemFreeCheckLM = true");
#else
	useMemFreeCheckLM = false;
	ALOGD("useMemFreeCheckLM = false");
#endif
	char param1[PROPERTY_VALUE_MAX];
	int64_t value;
	property_get("vr.check.low.memory.by.mem.free", param1, "-1");
	value = atol(param1);
	ALOGD("initStart, vr.check.low.memory.by.mem.free=%" PRId64 "", value);
	if(value > 0)
	{
		useMemFreeCheckLM = true;
		ALOGD("@@[RECORD_PROPERTY]vr.check.low.memory.by.mem.free = %d", useMemFreeCheckLM);
	}

	if(useMemFreeCheckLM) {
		int32_t videoFPS = 0;
		int32_t videoWidth = 0;
		int32_t videoHeight = 0;
		param->findInt32(kKeyFrameRate, &videoFPS);
		param->findInt32(kKeyWidth,&videoWidth);
		param->findInt32(kKeyHeight,&videoHeight);

		if(videoFPS > 30 || videoWidth > 1920) {
			mMemInfoFd = open("/proc/meminfo", O_RDONLY);
			if (mMemInfoFd < 0) {
				useMemFreeCheckLM = false;
				ALOGW("Unable to open /proc/meminfo: %s\n", strerror(errno));
			}
			
			mMinFreeMem = getMinFreeMem();
		}
		else {
			ALOGW("Disable low memory check by memfree due to profile not reach, videoFPS(%d), videoWidth(%d),  videoHeight(%d)\n", videoFPS, videoWidth, videoHeight);
			useMemFreeCheckLM = false;
		}
	}
}
void MPEG4Writer::waitWriterThreadExit() {
	ALOGD("Wait writer thread exit +");
	Mutex::Autolock autolock(mLock);
	if (!mWriterThreadExit) {
		ALOGD("Real wait writer thread exit");
		mWriterThreadExitCondition.wait(mLock);
	}
	ALOGD("Wait writer thread exit -");
}

bool MPEG4Writer::isEnable64BitDuration(int64_t durationUs) {
	int64_t movieDuratinoInTimeScale = (durationUs * mTimeScale + 5E5) / 1E6;
	ALOGI("writeMvhdBox,movieDuratinoInTimeScale=0x%x(%" PRId64 "))",movieDuratinoInTimeScale,movieDuratinoInTimeScale);
	if(movieDuratinoInTimeScale > kMax32BitDuration){
		ALOGI("writeMvhdBox,movieDuratinoInTimeScale is large than 0x7FFFFFFF,change to use 64 bit for duration");
		return true;
	}
	return false;
}

off64_t MPEG4Writer::writeSEIbuffer(MediaBuffer *buffer) {
    off64_t old_offset = mOffset;
    size_t length = buffer->range_length();
	mCacheWriter->write((const uint8_t *)buffer->data() + buffer->range_offset(),
		1, length);
	mOffset += length;
	return old_offset;
}

void MPEG4Writer::notifyEstimateSize(int64_t nTotalBytesEstimate) {
    if( 0 == (mNotifyCounter%TRACK_SKIPNOTIFY_RATIO) )
    {
        ALOGV("notify nTotalBytesEstimate %" PRId64 ", %d", nTotalBytesEstimate, mNotifyCounter);
        //add notify file size to app for mms
        notify(MEDIA_RECORDER_EVENT_INFO,MEDIA_RECORDER_INFO_RECORDING_SIZE,(int)nTotalBytesEstimate);
    }
    mNotifyCounter++;
}

void MPEG4Writer::checkBufferedMem(const Chunk& chunk, bool isAudio) {
	mBufferedDataSize += chunk.mDataSize;
	ALOGD("+ mBufferedDataSize(%" PRId64 "), chunk->mDataSize(%" PRId64 ")", mBufferedDataSize, chunk.mDataSize);
	if(useMemFreeCheckLM) {
		if(isNearLowMemory()) {
			ALOGW("near low memory threshold, auto stop to avoid low memory issue", mBufferedDataSize, mLowMemoryProtectThreshold);
			notify(MEDIA_RECORDER_EVENT_INFO, MEDIA_RECORDER_INFO_WRITE_SLOW, 0);
		}
	}
	else {
		if ( mBufferedDataSize > mLowMemoryProtectThreshold) {
			ALOGW("buffered data size %" PRId64 " > %" PRId64 ", auto stop to avoid low memory issue", mBufferedDataSize, mLowMemoryProtectThreshold);
			notify(MEDIA_RECORDER_EVENT_INFO, MEDIA_RECORDER_INFO_WRITE_SLOW, 0);
		}
		
	}

	if (!isAudio && NULL != mVideoQualityController) {
		mVideoQualityController->adjustQualityIfNeed(mBufferedDataSize);
	}
}

bool MPEG4Writer::isLengthPrefixed(Chunk* chunk) {
//#ifdef MTK_VIDEO_HEVC_SUPPORT
	return (chunk->mTrack->isAvc()|| chunk->mTrack->isHevc())&& !(chunk->mTrack->getMultiSliceBSInfo());
//#else
	//return chunk->mTrack->isAvc() && !(chunk->mTrack->getMultiSliceBSInfo());
//#endif
}

void MPEG4Writer::eraseChunkSamples(Chunk* chunk) {
	while (!chunk->mSamples.empty()) {
		List<MediaBuffer *>::iterator it = chunk->mSamples.begin();
		(*it)->release();
		(*it) = NULL;
		chunk->mSamples.erase(it);
	}
}
void MPEG4Writer::addWritedChunk(Chunk* chunk, int32_t chunkSize) {
	WritedChunk *writedchunk = new WritedChunk(chunk->mTrack, chunkSize);
	mWritedChunks.push_back(writedchunk);
	chunk->mTrack->addWritedChunkNum();
}

void MPEG4Writer::writeArtAlbBox() {
	beginBox("meta");
	writeInt32(0);//flag and version
	beginBox("ilst");
	if (mArtistTag.length() > 0) {
		const char artistbox[] = {'\xA9', 'A', 'R', 'T', '\0'};//"\xA9ART" will be recognized as '\xA9A', 'R', 'T'
		beginBox(artistbox);
		beginBox("data");
		writeInt32(0);//flag and version
		writeInt32(0);//4 byte null space
		writeCString(mArtistTag.string());
		endBox();//data
		endBox();//0xa9ART
	}

	if (mAlbumTag.length() > 0) {
		const char albumbox[] = {'\xA9', 'a', 'l', 'b', '\0'};
		beginBox(albumbox);
		beginBox("data");
		writeInt32(0);//flag and version
		writeInt32(0);//4 byte null space
		writeCString(mAlbumTag.string());
		endBox();//data
		endBox();//0xa9alb
	}
	endBox();//ilst
	endBox();//meta
}

long MPEG4Writer::getMinFreeMem() {
	int fd = open("/sys/module/lowmemorykiller/parameters/minfree", O_RDONLY);
	if (fd < 0) {
		ALOGW("Unable to open /sys/module/lowmemorykiller/parameters/minfree: %s\n", strerror(errno));
		return -1;
	}
	char buffer[1024];
	int len = ::read(fd, buffer, sizeof(buffer)-1);
	if (len < 0) {
		ALOGW("Empty /sys/module/lowmemorykiller/parameters/minfree");
		return -1;
	}
	buffer[len] = 0;
	close(fd);

	char* ptr = buffer;
	long minFreeMax = 0;
	long minFree[6];
	for(int i=0; i<6; i++) {
		while(*ptr && (*ptr<'1' || *ptr>'9')) ptr++;
		if(!(*ptr)) break;
		minFree[i] = atoll(ptr);
		ALOGD("got minFree \t%ld", minFree[i]);
		if (minFree[i] > minFreeMax)
			minFreeMax = minFree[i];
		while(*ptr && (*ptr>='0' && *ptr<='9')) ptr++;
	}

	if (minFreeMax <= 0)
		return -1;

	ALOGD("PAGESIZE(%ld), minFreeMax(%ld), minFreeMem(%ld)", PAGESIZE, minFreeMax, minFreeMax*PAGESIZE);
	return minFreeMax*PAGESIZE;
}


bool MPEG4Writer::isNearLowMemory() {
	if(mMinFreeMem <= 0) {
		ALOGE("mMinFreeMem(%ld) <= 0, use 70M low memory threshold", mMinFreeMem);
		return mBufferedDataSize > mLowMemoryProtectThreshold;
	}
	
	char buffer[1024];
	int numFound = 0;

	if (mMemInfoFd < 0) {
		ALOGE("Unable to open /proc/meminfo: %s, use 70M low memory threshold\n", strerror(errno));
		return mBufferedDataSize > mLowMemoryProtectThreshold;
	}

	lseek64(mMemInfoFd, 0, SEEK_SET);

	int len = ::read(mMemInfoFd, buffer, sizeof(buffer)-1);

	if (len < 0) {
		ALOGW("Empty /proc/meminfo,  use 70M low memory threshold");
		return mBufferedDataSize > mLowMemoryProtectThreshold;
	}
	
	buffer[len] = 0;
	long memFree = 0;
	long memCached = 0;
	
    char* ptr = strstr(buffer, "MemFree:");
	if (NULL != ptr) {
		ptr += 8;
		while (*ptr == ' ') ptr++;
		memFree = atoll(ptr);
		ALOGI("got MemFree: %ldKB", memFree);
	}

    ptr = strstr(buffer, "Cached:");
	if (NULL != ptr) {
		ptr += 8;
		while (*ptr == ' ') ptr++;
		memCached = atoll(ptr);
		ALOGI("got memCached: %ldKB", memCached);
	}

	if(memFree<=0 && memCached<=0) {
		ALOGW("got memFree/memCached failed,  use 70M low memory threshold");
		return mBufferedDataSize > mLowMemoryProtectThreshold;
	}
	
	if(memFree*1024 < mMinFreeMem+EXT_MIN_FREE_MEM_THRESHOLD
		&& memCached*1024 < mMinFreeMem+EXT_MIN_FREE_MEM_THRESHOLD) {
		ALOGI("MemFree(%ldByte) < mMinFreeMem(%ldByte)+EXT_MIN_FREE_MEM_THRESHOLD(%ldByte), near low memory", 
			memFree*1024, mMinFreeMem, EXT_MIN_FREE_MEM_THRESHOLD);
		return true;
	}
	else
		return false;
}

void MPEG4Writer::setVideoStartTimeUs(int64_t timeUs) {
	mVideoStartTimeUs = timeUs;
}
int64_t MPEG4Writer::getVideoStartTimeUs() {
	return mVideoStartTimeUs;
}

#ifdef SD_FULL_PROTECT
void MPEG4Writer::Track::addWritedChunkNum() {
	mWritedChunkNum++;
}

void MPEG4Writer::Track::decWritedChunkNum() {
	mWritedChunkNum--;
}


int64_t MPEG4Writer::Track::getEstimatedTrackHeaderSizeBytes() {
	updateTrackSizeEstimate();
	return mEstimatedTrackSizeBytes - mMdatSizeBytes;
}

void MPEG4Writer::Track::updateTrackHeader() {
	ALOGD("%s mStcoTableEntries=%d,mCo64TableEntries=%d, mWritedChunkNum=%d", mIsAudio?"Audio":"Video", mStcoTableEntries->count(),mCo64TableEntries->count(), mWritedChunkNum);

	uint32_t iStscTableEntries = mStscTableEntries->count();
	uint32_t iStscEntryCapacity = mStscTableEntries->getEntryCapacity();
	ALOGD("updateTrackHeader,stsc size=%d, entry capacity=%d", iStscTableEntries,iStscEntryCapacity);

	//update Stsc Table mStscTableEntries
	bool err = false;
	uint32_t iFirstChunkNum;
	for(int i = iStscTableEntries; i > 0; i--){
		err = mStscTableEntries->get(iFirstChunkNum,(i - 1) * iStscEntryCapacity);
		iFirstChunkNum = ntohl(iFirstChunkNum); //go back to host byte order
		ALOGD("(%d-1)*iStscEntryCapacity=%d,iFirstChunkNum=%d",i,(i - 1) * iStscEntryCapacity,iFirstChunkNum);
		if(!err){
			ALOGE("mStscTableEntries->get() fail,(i - 1) * iStscEntryCapacity=%d",(i - 1) * iStscEntryCapacity);
			break;
		 }

		if(iFirstChunkNum > mWritedChunkNum){
			mStscTableEntries->popTopTableEntry();
			ALOGD("updateTrackHeader,Delete one table entry");
		}
		else{
			ALOGI("updateTrackHeader,remain Stsc entry num = %d(%d)",i,mStscTableEntries->count());
				break;
	}
	}
	//update Stco Table mStcoTableEntries
	if(mOwner->use32BitFileOffset()){
		for (size_t chunkNum = mStcoTableEntries->count(); chunkNum > mWritedChunkNum; chunkNum--) {
			mStcoTableEntries->popTopTableEntry();
			ALOGD("updateTrackHeader,delete mStcoTableEntries table chunkNum=%d", chunkNum);
		}
		ALOGI("updateTrackHeader,remain Stco entry num = (%d)",mStcoTableEntries->count());
	}
	else{//update Stco Table mCo64TableEntries
		for (size_t chunkNum = mCo64TableEntries->count(); chunkNum > mWritedChunkNum; chunkNum--) {
			mCo64TableEntries->popTopTableEntry();
			ALOGD("updateTrackHeader,delete mCo64TableEntries table chunkNum=%d", chunkNum);
		}
		ALOGI("updateTrackHeader,remain Stco entry num = (%d)",mCo64TableEntries->count());
	}

	
	//got total sample count
	iStscTableEntries = mStscTableEntries->count();
	iStscEntryCapacity = mStscTableEntries->getEntryCapacity();
	ALOGD("after update, iStscTableEntries=%d, iStscEntryCapacity=%d", iStscTableEntries, iStscEntryCapacity);
	uint32_t iPostFirstChunkNum = mWritedChunkNum+1;
	uint32_t iCurFirstChunkNum = 0;
	uint32_t iTotalSampleCnt = 0;
	uint32_t iSampleCnt = 0;
	for(int i = iStscTableEntries-1; i >= 0; i--) {
		err = mStscTableEntries->get(iCurFirstChunkNum, i* iStscEntryCapacity);
		iCurFirstChunkNum = ntohl(iCurFirstChunkNum); //go back to host byte order
		
		err = mStscTableEntries->get(iSampleCnt, i* iStscEntryCapacity+1);
		iSampleCnt = ntohl(iSampleCnt); //go back to host byte order

		ALOGD("iSampleCnt=%d, iPostFirstChunkNum=%d, iCurFirstChunkNum=%d", iSampleCnt, iPostFirstChunkNum, iCurFirstChunkNum);
		iTotalSampleCnt += iSampleCnt*(iPostFirstChunkNum - iCurFirstChunkNum);
		ALOGD("iTotalSampleCnt=%d", iTotalSampleCnt);
		iPostFirstChunkNum = iCurFirstChunkNum;
		ALOGD("iPostFirstChunkNum=%d", iPostFirstChunkNum);
	}

	//update Stss Table mStssTableEntries
	uint32_t iStssTableEntries = mStssTableEntries->count();
	uint32_t iStssEntryCapacity = mStssTableEntries->getEntryCapacity();
	uint32_t iSampleId = 0;
	for (int i = iStssTableEntries; i > 0; i--) {
		err = mStssTableEntries->get(iSampleId,(i - 1) * iStssEntryCapacity);
		iSampleId = ntohl(iSampleId); //go back to host byte order
		
		if(iSampleId <= iTotalSampleCnt)
			break;

		mStssTableEntries->popTopTableEntry();
		ALOGD("updateTrackHeader,delete mStssTableEntries table sample number=%d, iSampleId=%d", i, iSampleId);
	}

	//update track duration
	uint32_t iSttsTableEntries = mSttsTableEntries->count();
	uint32_t iSttsEntryCapacity = mSttsTableEntries->getEntryCapacity();
	uint32_t iSttsFrameCnt = 0;

	uint32_t index;
	for(index = 0; index < iSttsTableEntries; index++) {
		err = mSttsTableEntries->get(iSampleCnt, index* iSttsEntryCapacity);
		iSampleCnt = ntohl(iSampleCnt); //go back to host byte order
		iSttsFrameCnt += iSampleCnt;
		ALOGD("index = %d, iSampleCnt=%d, iSttsFrameCnt=%d", index, iSampleCnt, iSttsFrameCnt);
		if(iSttsFrameCnt >= iTotalSampleCnt)
			break;
	}


	if((iSttsFrameCnt > iTotalSampleCnt) && (iSampleCnt > iSttsFrameCnt-iTotalSampleCnt)) {
		iSampleCnt -= (iSttsFrameCnt-iTotalSampleCnt);
		mSttsTableEntries->set(htonl(iSampleCnt), index*iSttsEntryCapacity);
		ALOGD("reset the sample count: index(%d), iSampleCnt(%d)", index, iSampleCnt);
	}

	//uint32_t iSampleCnt = 0;
	uint32_t iDurationTimeScale = 0;
		
	for(int i = iSttsTableEntries-1; i > index; i--) {
		err = mSttsTableEntries->get(iSampleCnt, i* iSttsEntryCapacity);
		iSampleCnt = ntohl(iSampleCnt); //go back to host byte order
		
		err = mSttsTableEntries->get(iDurationTimeScale, i* iSttsEntryCapacity+1);
		iDurationTimeScale = ntohl(iDurationTimeScale); //go back to host byte order
		int64_t iDurationUs = iDurationTimeScale*1000000LL/mTimeScale ;
		
		ALOGD("iSampleCnt=%d, iDurationTimeScale=%d, iDurationUs=%" PRId64 "", iSampleCnt, iDurationTimeScale, iDurationUs);
		ALOGD("+mTrackDurationUs = %" PRId64 "", mTrackDurationUs);
		mTrackDurationUs -= iSampleCnt*iDurationUs;
		ALOGD("-mTrackDurationUs = %" PRId64 "", mTrackDurationUs);
	}

}

#endif

bool MPEG4Writer::Track::isTrackExited(){

	Mutex::Autolock autolock(mLock);
	return mThreadExit;
}

void MPEG4Writer::Track::signalResumed(bool hasMultipleTracks) {
	if (mResumed) {
		if (hasMultipleTracks) {
			if (!mIsAudio) {
				mOwner->signalResumed();//Both audio and video resumed;
			}
		} else {//This is only for one track case
			mOwner->signalResumed();
		}
		ALOGD("%s resumed",mIsAudio? "audio": "video");
		mResumed = false;
	}
}

void MPEG4Writer::Track::init() {
#ifdef SD_FULL_PROTECT
	mWritedChunkNum = 0;
#endif
	mThreadExit = true;
	mAVCSEIData = NULL;
	mAVCSEIDataSize = 0;

	char sttsFileName[100];
	sprintf(sttsFileName, "//sdcard//stts_%s.dat", mIsAudio?"A":"V");
	mSttsTableEntries->setTempFileName(sttsFileName);
	char stszFileName[100];
	sprintf(stszFileName, "//sdcard//stsz_%s.dat", mIsAudio?"A":"V");
	mStszTableEntries->setTempFileName(stszFileName);

	mPausedFirstFrame = false;
	mMultiSliceBS = false; //for support multi-Slice case

	mLivePhotoTagValue = -1;
#ifdef MTK_SLOW_MOTION_VIDEO_SUPPORT
	int32_t slowMotionSpeedValue;
	if (!mIsAudio && mMeta->findInt32(kKeySlowMotionSpeedValue, &slowMotionSpeedValue)) {
		ALOGD("get slow motion speed value %d", slowMotionSpeedValue);
		mSlowMotionSpeedValue = slowMotionSpeedValue;
	}
	else
		mSlowMotionSpeedValue = -1;

	mDirectLink = false;

	int32_t nonRefPFreq;
	if (!mIsAudio && mMeta->findInt32(kKeyNonRefPFreq, &nonRefPFreq)) {
		ALOGD("get non reference P freq %x", nonRefPFreq);
		mNonRefPFreq = nonRefPFreq;
	}
	else
		mNonRefPFreq = 0;
#endif
	mTrackBitRate = mIsAudio? 12200 : 192000;
	if(mMeta->findInt32(kKeyBitRate,&mTrackBitRate)){
		ALOGI("Track Bitrate set =%d",mTrackBitRate);
	}
	mPauseTimeUs = -1;
	mTrackStartTimeOffsetUs = 0;
	mStartTimestampUs = -1;
}

void MPEG4Writer::Track::resume() {
#ifdef MTK_SLOW_MOTION_VIDEO_SUPPORT
	if (!mIsAudio && mDirectLink) {
		ALOGD("property_set Send force I cmd");
		property_set("dl.vr.force.iframe", "1");
	}
#endif
}

void MPEG4Writer::Track::initStart(MetaData *params) {
	int32_t livePhotoTagValue;
	if (!mIsAudio && params && params->findInt32(kKeyIsLivePhoto, &livePhotoTagValue)) {
		ALOGD("get livephoto tag %d", livePhotoTagValue);
		mLivePhotoTagValue = livePhotoTagValue;
	}
#ifdef MTK_SLOW_MOTION_VIDEO_SUPPORT
	int32_t slowMotionSpeedValue;
	if (!mIsAudio && params && params->findInt32(kKeySlowMotionSpeedValue, &slowMotionSpeedValue)) {
		ALOGD("get slow motion speed value %d", slowMotionSpeedValue);
		mSlowMotionSpeedValue = slowMotionSpeedValue;
	}
	
	int32_t directLink;
	if (params && params->findInt32(kKeyIsDirectLink, &directLink)) {
		ALOGD("slow motion is direct link: %d", directLink);
		mDirectLink = directLink;
	}
	
#endif
}
void MPEG4Writer::Track::pauseEx() {
	mPausedFirstFrame = true;
	mPauseTimeUs = systemTime() / 1000;
	ALOGD("%s  pause TimeUs=%" PRId64 "", mIsAudio? "audio": "video", mPauseTimeUs);

	Mutex::Autolock autolock(mLock);
	if (mPausedFirstFrame && !mThreadExit) {
		ALOGD("%s wait pause complete",mIsAudio? "audio": "video");
		mPauseCondition.wait(mLock);
	}
}

void MPEG4Writer::Track::waitTrackThreadExit() {
	ALOGD("%s wait track thread exit +", mIsAudio?"Audio":"Video");
	Mutex::Autolock autolock(mLock);
	if (!mThreadExit) {
		ALOGD("%s wait track thread to stop", mIsAudio?"Audio":"Video");
		mThreadExitCondition.wait(mLock);
	}
	ALOGD("%s wait track thread exit -", mIsAudio?"Audio":"Video");
}

void MPEG4Writer::Track::writeUdtaBox() {
	bool isWriteUdtaBox = false;

	if (!mIsAudio &&
		mLivePhotoTagValue > 0) {
		if (!isWriteUdtaBox) {
			isWriteUdtaBox = true;
			mOwner->beginBox("udta");
		}
		writeLvpoBox();
	}
#ifdef MTK_SLOW_MOTION_VIDEO_SUPPORT
	if (!mIsAudio &&
		mSlowMotionSpeedValue > 0) {
		if (!isWriteUdtaBox) {
			isWriteUdtaBox = true;
			mOwner->beginBox("udta");
		}
		writeSmsvBox();
	}
#endif

	if (isWriteUdtaBox) {
		mOwner->endBox();
	}
}

void MPEG4Writer::Track::writeLvpoBox() {
	ALOGD("write livephoto box, mLivePhotoTagValue = %d", mLivePhotoTagValue);
	mOwner->beginBox("lvpo");
	mOwner->writeInt32(0); //version=0, flag=0
	mOwner->writeInt16(0x15C7); //1bit padding + 15bit language code based on ISO-639-2/T: eng
	mOwner->writeCString("MTK-live-photo:");
	mOwner->writeInt32(mLivePhotoTagValue); //version=0, flag=0
	mOwner->endBox();
}
#ifdef MTK_SLOW_MOTION_VIDEO_SUPPORT
void MPEG4Writer::Track::writeSmsvBox() {
	ALOGD("write slowmotion box, mSlowMotionSpeedValue = %d", mSlowMotionSpeedValue);
	mOwner->beginBox("smsv");
	mOwner->writeInt32(0); //version=0, flag=0
	mOwner->writeInt16(0x15C7); //1bit padding + 15bit language code based on ISO-639-2/T: eng
	mOwner->writeCString("MTK-slow-motion:");
	mOwner->writeInt32(mSlowMotionSpeedValue); 
	mOwner->writeCString("non-ref-p-freq:");
	mOwner->writeInt32(mNonRefPFreq);
	mOwner->endBox();
}
#endif

status_t MPEG4Writer::Track::parseAVCCodecSpecificDataByNALSize(
        const uint8_t *data, size_t size) {

    ALOGI("parseAVCCodecSpecificDataByNALSize");
    // Data not starts with a start code.
    // SPS and PPS are not separated with start codes.
    //NALsize+SPS+NALsize+PPS+NALsize+SEI
    // Also, SPS must come before PPS
    uint8_t type = kNalUnitTypeSeqParamSet;
    bool gotSps = false;
    bool gotPps = false;
    const uint8_t *tmp = data;
    size_t bytesLeft = size;
    mCodecSpecificDataSize = 0;
    while (bytesLeft > 4) {
		size_t nalSize = tmp[0] << 24 | tmp[1] << 16 | tmp[2] << 8 | tmp[3];
		ALOGI("parseAVCCodecSpecificDataByNALSize,nal size =%d",nalSize);
		if(nalSize < 4){
			ALOGE("Seq parameter set malformed");
		    return ERROR_MALFORMED;
		}
		if(bytesLeft < (nalSize +4)){
			ALOGE("Codec Config data error:bytesLeft=%d < nalSize=%d",bytesLeft,nalSize);
			return ERROR_MALFORMED;
		}

        getNalUnitType(*(tmp + 4), &type);
        if (type == kNalUnitTypeSeqParamSet) {
            if (gotPps) {
                ALOGE("SPS must come before PPS");
                return ERROR_MALFORMED;
            }
            if (!gotSps) {
                gotSps = true;
            }

			//test
			 AVCParamSet paramSet(nalSize, tmp + 4);

			if (mSeqParamSets.empty()) {
		            mProfileIdc = tmp[5];
		            mProfileCompatible = tmp[6];
		            mLevelIdc = tmp[7];
		     } else {
		            if (mProfileIdc != tmp[5] ||
		                mProfileCompatible != tmp[6] ||
		                mLevelIdc != tmp[7]) {
		                ALOGE("Inconsistent profile/level found in seq parameter sets");
		                return ERROR_MALFORMED;
		            }
		     }
		     mSeqParamSets.push_back(paramSet);

        } else if (type == kNalUnitTypePicParamSet) {
            if (!gotSps) {
                ALOGE("SPS must come before PPS");
                return ERROR_MALFORMED;
            }
            if (!gotPps) {
                gotPps = true;
            }

			 AVCParamSet paramSet(nalSize, tmp + 4 );
			 mPicParamSets.push_back(paramSet);
        }
		//for  SEI support
		else if (type == kNalUnitTypeSEI) {

			mAVCSEIDataSize = nalSize + 4; //nalsize(4bytes)+sei
			mAVCSEIData = malloc(mAVCSEIDataSize);
			memcpy(mAVCSEIData, tmp, mAVCSEIDataSize); //will copy nalsize+sei
			ALOGD("mAVCSEIData=0x%8.8x, mAVCSEIDataSize=%d", *(uint32_t*)mAVCSEIData, mAVCSEIDataSize);
			bytesLeft -= (nalSize + 4);
			tmp += (nalSize + 4);
			continue;
		}
		else {
            ALOGE("Only SPS and PPS and SEI Nal units are expected");
            return ERROR_MALFORMED;
        }

        // Move on to find the next parameter set
        bytesLeft -= (nalSize + 4);
      	tmp += (nalSize + 4);
        mCodecSpecificDataSize += (2 + nalSize);
    }

    {
        // Check on the number of seq parameter sets
        size_t nSeqParamSets = mSeqParamSets.size();
        if (nSeqParamSets == 0) {
            ALOGE("Cound not find sequence parameter set");
            return ERROR_MALFORMED;
        }

        if (nSeqParamSets > 0x1F) {
            ALOGE("Too many seq parameter sets (%d) found", nSeqParamSets);
            return ERROR_MALFORMED;
        }
    }

    {
        // Check on the number of pic parameter sets
        size_t nPicParamSets = mPicParamSets.size();
        if (nPicParamSets == 0) {
            ALOGE("Cound not find picture parameter set");
            return ERROR_MALFORMED;
        }
        if (nPicParamSets > 0xFF) {
            ALOGE("Too many pic parameter sets (%d) found", nPicParamSets);
            return ERROR_MALFORMED;
        }
    }

    return OK;
}

void MPEG4Writer::Track::syncMoovStartTime() {
#ifdef WRITER_ENABLE_EDTS_BOX
	if(mOwner->isEnableEdtsBoxForTimeOffset())
		return;
#endif

	int64_t trackStartTimeOffsetUs = 0;
	int64_t moovStartTimeUs = mOwner->getStartTimestampUs();
	if (mStartTimestampUs != moovStartTimeUs) {
		ALOGD("%s mStartTimestampUs = %" PRId64 ", moovStartTimeUs%" PRId64 "", mIsAudio?"Audio":"Video" ,mStartTimestampUs, moovStartTimeUs);
		CHECK(mStartTimestampUs >= moovStartTimeUs);
		trackStartTimeOffsetUs = mStartTimestampUs - moovStartTimeUs;
	}
	mTrackDurationUs += trackStartTimeOffsetUs;
}

void MPEG4Writer::Track::signalTrackThreadExit() {
	Mutex::Autolock autolock(mLock);
	mThreadExitCondition.signal();
	mThreadExit = true;
}

void MPEG4Writer::Track::atraceDataRead() {
#ifdef MTB_SUPPORT
	if(mIsAudio)
		ATRACE_ONESHOT(ATRACE_ONESHOT_SPECIAL, "audioTrackReadData");   
	else
		ATRACE_ONESHOT(ATRACE_ONESHOT_SPECIAL, "videoTrackReadData");   
#endif
}

void MPEG4Writer::Track::getFirstPauseTimeUs(MediaBuffer *buffer, int64_t& firstPauseFrameTimeUs) {
	Mutex::Autolock autolock(mLock);
	if (mPausedFirstFrame) {
		buffer->meta_data()->findInt64(kKeyTime, &firstPauseFrameTimeUs);
		mPausedFirstFrame = false;
		ALOGD("%s first pause time stamp = %" PRId64 "", mIsAudio?"Audio":"Video" ,firstPauseFrameTimeUs);
		mPauseCondition.signal();
	}
}

bool MPEG4Writer::Track::hasCodecInfo() {
//#ifdef MTK_VIDEO_HEVC_SUPPORT
	return (mIsAvc || mIsMPEG4 || mIsHevc);  //only AVC/MPEG4/AAC/HEVC has codec info
//#else
	//return (mIsAvc || mIsMPEG4);
//#endif
}

void MPEG4Writer::Track::getMultiSliceBS(MediaBuffer *buffer) {
	int32_t iMultiSliceBS = false;
	if(buffer->meta_data()->findInt32(KKeyMultiSliceBS,&iMultiSliceBS)
		&& iMultiSliceBS){
		ALOGI("multi Slice Bitstream %d",iMultiSliceBS);
		mMultiSliceBS = iMultiSliceBS;
	}
}

void MPEG4Writer::Track::checkVideoHeader(MediaBuffer *buffer) {
	if (mIsMPEG4 && !mIsAudio && //MPEG4
		(*(uint32_t*)((uint8_t *)buffer->data() + buffer->range_offset())) != 0xB6010000) {
		ALOGW("Maybe Wrong MPEG-4 Bitstream Header: 0x%8.8x",
			*(uint32_t*)((uint8_t *)buffer->data() + buffer->range_offset()));
	}

	if (mIsMPEG4 && !mIsAudio && //MPEG4
		((*((uint8_t *)buffer->data() + buffer->range_offset() + 4) & 0xC0) >> 6) > 0x1) {
		ALOGW("Maybe Wrong MPEG-4 Frame Type %x", *((uint8_t *)buffer->data() + buffer->range_offset() + 4));
	}

	if (!mIsAudio && !mIsMPEG4 && !mIsAvc && //h263
//#ifdef MTK_VIDEO_HEVC_SUPPORT
         !mIsHevc &&
//#endif
		(*(uint16_t*)((uint8_t *)buffer->data() + buffer->range_offset()) != 0x0) &&
		(((*((uint8_t *)buffer->data() + buffer->range_offset() + 2)) >> 2) != 0x20)) {
		ALOGW("Maybe Wrong H263 Header 0x%8.8x", *(uint32_t*)((uint8_t *)buffer->data() + buffer->range_offset()));
	}
}

bool MPEG4Writer::Track::needStripStartcode() {
//#ifdef MTK_VIDEO_HEVC_SUPPORT
	return ((!isSEIData()) && (mIsAvc||mIsHevc) && !mMultiSliceBS);
//#else
	//return ((!isSEIData()) && mIsAvc && !mMultiSliceBS);	
//#endif
}

bool MPEG4Writer::Track::waitNewFrameForResume(MediaBuffer *buffer, int64_t firstPauseFrameTimeUs) {
	if(mResumed) {
		int64_t resumeTimeStampUs;
		CHECK((buffer->meta_data()->findInt64(kKeyTime, &resumeTimeStampUs)));

		if(resumeTimeStampUs < mPauseTimeUs-mStartTimeRealUs) {
			if( resumeTimeStampUs+1000000 > mPauseTimeUs-mStartTimeRealUs ) {
				ALOGI("wait new frame come, resumeTimeStampUs(%" PRId64 "us) < mPauseTimeUs(%" PRId64 "us)-mStartTimeRealUs(%" PRId64 "us)", resumeTimeStampUs, mPauseTimeUs, mStartTimeRealUs);
				return true;
			}
			else {
				ALOGD("it is camera time lapse, resumeTimeStampUs(%" PRId64 "us), mPauseTimeUs(%" PRId64 "us), mStartTimeRealUs(%" PRId64 "us)", resumeTimeStampUs, mPauseTimeUs, mStartTimeRealUs);
			}
		}

		if(mIsAudio) {
			mOwner->setPausedDuration(resumeTimeStampUs-firstPauseFrameTimeUs);
		}
		else {
			int32_t isSync = false;
			buffer->meta_data()->findInt32(kKeyIsSyncFrame, &isSync);
			if(!isSync ||
			   ((mOwner->numTracks() > 1) && (resumeTimeStampUs-firstPauseFrameTimeUs < mOwner->getPausedDuration())) ) {

#ifdef MTK_SLOW_MOTION_VIDEO_SUPPORT
				if (mDirectLink) {
					ALOGD("property_set Send force I cmd");
					property_set("dl.vr.force.iframe", "1");
				}else
#endif				
				if (reinterpret_cast<MediaCodecSource *>(mSource.get())->requestIDRFrame() != OK)
						ALOGW("Send force I cmd fail");
						
				ALOGI("wait new frame come for IFrame or for video pause duration > audio pause duration");
				return true;
			}
		}
	}
	return false;
}

bool MPEG4Writer::Track::isSEIData() {
//#ifdef MTK_VIDEO_HEVC_SUPPORT
	return ((mIsAvc || mIsHevc) && (mStszTableEntries->count() == 0) && (mAVCSEIData != NULL));
//#else
	//return (mIsAvc && (mStszTableEntries->count() == 0) && (mAVCSEIData != NULL));
//#endif
}

MediaBuffer* MPEG4Writer::Track::getSEIData(MediaBuffer *buffer) {
	MediaBuffer *copy;
	size_t sampleSize = 0;
	if(mMultiSliceBS){
		size_t len = buffer->range_length();
		ALOGI("is AVC/HEVC,sampleSize=%d",sampleSize);
		sampleSize += len + mAVCSEIDataSize;
		ALOGI("is AVC/HEVC,sampleSize=%d,len=%d,mAVCSEIDataSize=%d",sampleSize,len,mAVCSEIDataSize);
		copy = new MediaBuffer(sampleSize);

		memcpy((uint8_t *)copy->data(), mAVCSEIData, mAVCSEIDataSize);
		memcpy((uint8_t *)copy->data() + mAVCSEIDataSize, (uint8_t *)buffer->data() + buffer->range_offset(), len);

	}
	else{
		StripStartcode(buffer);
		size_t len = buffer->range_length();
		sampleSize = mOwner->useNalLengthFour()?8:4; //two Nals
		sampleSize += len + mAVCSEIDataSize;
		copy = new MediaBuffer(sampleSize);
		if (mOwner->useNalLengthFour())
		{
			uint32_t NALsize = ((mAVCSEIDataSize >> 24) & 0xff) | ((mAVCSEIDataSize >> 8) & 0xff00) | ((mAVCSEIDataSize << 8) & 0xff0000) | ((mAVCSEIDataSize << 24) & 0xff000000);
			memcpy(copy->data(), &NALsize, 4);
			memcpy((uint8_t *)copy->data() + 4, mAVCSEIData, mAVCSEIDataSize);
			NALsize = ((len >> 24) & 0xff) | ((len >> 8) & 0xff00) | ((len << 8) & 0xff0000) | ((len << 24) & 0xff000000);
			memcpy((uint8_t *)copy->data() + 4 + mAVCSEIDataSize, &NALsize, 4);
			memcpy((uint8_t *)copy->data() + 8 + mAVCSEIDataSize, (uint8_t *)buffer->data() + buffer->range_offset(), len);
		}
		else
		{
			CHECK(mAVCSEIDataSize < 65536);
			uint16_t NALsize = ((mAVCSEIDataSize >> 8) & 0xff) | ((mAVCSEIDataSize << 8) & 0xff00);
			memcpy(copy->data(), &NALsize, 2);
			memcpy((uint8_t *)copy->data() + 2, mAVCSEIData, mAVCSEIDataSize);
			NALsize = ((len >> 8) & 0xff) | ((len << 8) & 0xff00);
			memcpy((uint8_t *)copy->data() + 2 + mAVCSEIDataSize, &NALsize, 2);
			memcpy((uint8_t *)copy->data() + 4 + mAVCSEIDataSize, (uint8_t *)buffer->data() + buffer->range_offset(), len);
		}
	}
	
	copy->meta_data()->setInt32(kKeyHasSEIBuffer, true);
	return copy;
}

bool MPEG4Writer::Track::needDropAudioFrame(int64_t timestampUs) {
	if(timestampUs < mTrackStartTimeOffsetUs) {
		return true;
	}
#ifdef MTK_SLOW_MOTION_VIDEO_SUPPORT
	int64_t videoStartTimeUs = mOwner->getVideoStartTimeUs();
	if(mDirectLink 
		&& (videoStartTimeUs < 0 || timestampUs < videoStartTimeUs)) {
		return true;
	}
#endif
	return false;
}

//#ifdef MTK_VIDEO_HEVC_SUPPORT

static void getHEVCNalUnitType(uint8_t byte, uint8_t* type) {
    // nal_unit_type: 6-bit unsigned integer
    *type = (byte & 0x7E) >> 1;
	
	ALOGD("getHEVCNalUnitType: byte %d, type %d", byte, *type);
}

const uint8_t *MPEG4Writer::Track::parseHEVCParamSet(
        const uint8_t *data, size_t length, int type, size_t *paramSetLen) {
	  ALOGD("parseHEVCParamSet");
    CHECK(type == kNalUnitTypeSeqParamSet_HEVC ||
          type == kNalUnitTypePicParamSet_HEVC ||
          type == kNalUnitTypeVdoParamSet_HEVC);

    const uint8_t *nextStartCode = findNextStartCode(data, length);
    *paramSetLen = nextStartCode - data;
    if (*paramSetLen == 0) {
        ALOGE("Param set is malformed, since its length is 0");
        return NULL;
    }

    AVCParamSet paramSet(*paramSetLen, data);
    if (type == kNalUnitTypeSeqParamSet_HEVC) {
        if (*paramSetLen < 4) {
            ALOGE("Seq parameter set malformed");
            return NULL;
        }
		
		/*
		//set fixed profile,level value
			mProfileIdc = 0x4D;
			mProfileCompatible = 0xC0;
			mLevelIdc = 0x1F;
		*/
        if (mSeqParamSets.empty()) {
            mProfileIdc = data[1];
            mProfileCompatible = data[2];
            mLevelIdc = data[3];
        } else {
            if (mProfileIdc != data[1] ||
                mProfileCompatible != data[2] ||
                mLevelIdc != data[3]) {
                ALOGE("Inconsistent profile/level found in seq parameter sets");
                //return NULL;
            }
        
    	}
        mSeqParamSets.push_back(paramSet);
    } else if(type == kNalUnitTypePicParamSet_HEVC){
        mPicParamSets.push_back(paramSet);
    }else {
		mVdoParamSets.push_back(paramSet);
    }
    return nextStartCode;
}

status_t MPEG4Writer::Track::copyHEVCCodecSpecificData(
        const uint8_t *data, size_t size) {
	ALOGD("copyHEVCCodecSpecificData size=%d",size);

    // 2 bytes for each of the parameter set length field
    // plus the 7 bytes for the header
    if (size < 4 + 23) {
        ALOGE("Codec specific data length too short: %d", size);
        return ERROR_MALFORMED;
    }

    mCodecSpecificDataSize = size;
    mCodecSpecificData = malloc(size);
    memcpy(mCodecSpecificData, data, size);
    return OK;
}


status_t MPEG4Writer::Track::parseHEVCCodecSpecificData(
	const uint8_t *data, size_t size) {
	ALOGD("parseHEVCCodecSpecificData size=%d",size);

	// Data starts with a start code.
	// SPS and PPS are separated with start codes.
	// Also, SPS must come before PPS
	uint8_t type = kNalUnitTypeSeqParamSet_HEVC;
	bool gotSps = false;
	bool gotPps = false;
	const uint8_t *tmp = data;
	const uint8_t *nextStartCode = data;
	size_t bytesLeft = size;
	size_t paramSetLen = 0;
	mCodecSpecificDataSize = 0;
	while (bytesLeft > 4 && !memcmp("\x00\x00\x00\x01", tmp, 4)) {
		getHEVCNalUnitType(*(tmp + 4), &type);
		ALOGI("HEVC CodecConfigInfo,NAL type=%d",type);

		if (type == kNalUnitTypeSeqParamSet_HEVC) {
			if (gotPps) {
				ALOGE("SPS must come before PPS");
				return ERROR_MALFORMED;
			}
			if (!gotSps) {
				gotSps = true;
			}
			nextStartCode = parseHEVCParamSet(tmp + 4, bytesLeft - 4, type, &paramSetLen);
		} else if (type == kNalUnitTypePicParamSet_HEVC) {
			if (!gotSps) {
				ALOGE("SPS must come before PPS");
				return ERROR_MALFORMED;
			}
			if (!gotPps) {
				gotPps = true;
			}
			nextStartCode = parseHEVCParamSet(tmp + 4, bytesLeft - 4, type, &paramSetLen);
		} else if (type == kNalUnitTypeVdoParamSet_HEVC) {
			nextStartCode = parseHEVCParamSet(tmp + 4, bytesLeft - 4, type, &paramSetLen);
		} else {
			//only parse pps,sps  at present,other nal will be ignored
			nextStartCode = findNextStartCode(tmp + 4, bytesLeft - 4);
			if (nextStartCode == NULL) {
				return ERROR_MALFORMED;
			}	
			bytesLeft -= nextStartCode - tmp;
			tmp = nextStartCode;
			continue;
		}

		if (nextStartCode == NULL) {
			return ERROR_MALFORMED;
		}

		// Move on to find the next parameter set
		bytesLeft -= nextStartCode - tmp;
		tmp = nextStartCode;
		mCodecSpecificDataSize += (2 + paramSetLen);
	}

	{
		// Check on the number of seq parameter sets
		size_t nSeqParamSets = mSeqParamSets.size();
		if (nSeqParamSets == 0) {
			ALOGE("Cound not find sequence parameter set");
			return ERROR_MALFORMED;
		}

		if (nSeqParamSets > 0x1F) {
			ALOGE("Too many seq parameter sets (%d) found", nSeqParamSets);
			return ERROR_MALFORMED;
		}
	}

	{
		// Check on the number of pic parameter sets
		size_t nPicParamSets = mPicParamSets.size();
		if (nPicParamSets == 0) {
			ALOGE("Cound not find picture parameter set");
			return ERROR_MALFORMED;
		}
		if (nPicParamSets > 0xFF) {
			ALOGE("Too many pic parameter sets (%d) found", nPicParamSets);
			return ERROR_MALFORMED;
		}
	}

	{
		// Check on the number of pic parameter sets
		size_t nVdoParamSets = mVdoParamSets.size();
		if (nVdoParamSets == 0) {
			ALOGW("Cound not find video parameter set");
		}
		if (nVdoParamSets > 0xFF) {
			ALOGE("Too many video parameter sets (%d) found", nVdoParamSets);
			return ERROR_MALFORMED;
		}
	}
	
	return OK;
}

status_t MPEG4Writer::Track::parseHEVCCodecSpecificDataByNALSize(
        const uint8_t *data, size_t size) {

    ALOGI("parseHEVCCodecSpecificDataByNALSize");
    // Data not starts with a start code.
    // SPS and PPS are not separated with start codes.
    //NALsize+SPS+NALsize+PPS+NALsize+SEI
    // Also, SPS must come before PPS
    uint8_t type = kNalUnitTypeSeqParamSet_HEVC;
    bool gotSps = false;
    bool gotPps = false;
    const uint8_t *tmp = data;
    size_t bytesLeft = size;
    mCodecSpecificDataSize = 0;
    while (bytesLeft > 4) {
		size_t nalSize = tmp[0] << 24 | tmp[1] << 16 | tmp[2] << 8 | tmp[3];
		ALOGI("parseHEVCCodecSpecificDataByNALSize,nal size =%d",nalSize);
		if(nalSize < 4){
			ALOGE("Seq parameter set malformed");
		    return ERROR_MALFORMED;
		}
		if(bytesLeft < (nalSize +4)){
			ALOGE("Codec Config data error:bytesLeft=%d < nalSize=%d",bytesLeft,nalSize);
			return ERROR_MALFORMED;
		}
		
        getNalUnitType(*(tmp + 4), &type);
        if (type == kNalUnitTypeSeqParamSet_HEVC) {
            if (gotPps) {
                ALOGE("SPS must come before PPS");
                return ERROR_MALFORMED;
            }
            if (!gotSps) {
                gotSps = true;
            }

			AVCParamSet paramSet(nalSize, tmp + 4);
			if (mSeqParamSets.empty()) {
		            mProfileIdc = tmp[5];
		            mProfileCompatible = tmp[6];
		            mLevelIdc = tmp[7];
		     } else {
		            if (mProfileIdc != tmp[5] ||
		                mProfileCompatible != tmp[6] ||
		                mLevelIdc != tmp[7]) {
		                ALOGE("Inconsistent profile/level found in seq parameter sets");
		                //return ERROR_MALFORMED;
		            }
		     }
		     mSeqParamSets.push_back(paramSet);
				
        } else if (type == kNalUnitTypePicParamSet_HEVC) {
            if (!gotSps) {
                ALOGE("SPS must come before PPS");
                return ERROR_MALFORMED;
            }
            if (!gotPps) {
                gotPps = true;
            }

			 AVCParamSet paramSet(nalSize, tmp + 4 );
			 mPicParamSets.push_back(paramSet);
        } 
		else if (type == kNalUnitTypeVdoParamSet_HEVC) {
			 AVCParamSet paramSet(nalSize, tmp + 4 );
			 mVdoParamSets.push_back(paramSet);
	    } 
		//for  SEI support
		else if (type == kNalUnitTypeSEI) { 
			
			mAVCSEIDataSize = nalSize + 4; //nalsize(4bytes)+sei
			mAVCSEIData = malloc(mAVCSEIDataSize);
			memcpy(mAVCSEIData, tmp, mAVCSEIDataSize);
			ALOGD("mAVCSEIData=0x%8.8x, mAVCSEIDataSize=%d", *(uint32_t*)mAVCSEIData, mAVCSEIDataSize);
			bytesLeft -= (nalSize + 4);
			tmp += (nalSize + 4);
			continue;
		}
		else {
            ALOGE("Only SPS and PPS and VPS and SEI Nal units are expected");
            return ERROR_MALFORMED;
        }

        // Move on to find the next parameter set
        bytesLeft -= (nalSize + 4);
      	tmp += (nalSize + 4);
        mCodecSpecificDataSize += (2 + nalSize);
    }

    {
        // Check on the number of seq parameter sets
        size_t nSeqParamSets = mSeqParamSets.size();
        if (nSeqParamSets == 0) {
            ALOGE("Cound not find sequence parameter set");
            return ERROR_MALFORMED;
        }

        if (nSeqParamSets > 0x1F) {
            ALOGE("Too many seq parameter sets (%d) found", nSeqParamSets);
            return ERROR_MALFORMED;
        }
    }

    {
        // Check on the number of pic parameter sets
        size_t nPicParamSets = mPicParamSets.size();
        if (nPicParamSets == 0) {
            ALOGE("Cound not find picture parameter set");
            return ERROR_MALFORMED;
        }
        if (nPicParamSets > 0xFF) {
            ALOGE("Too many pic parameter sets (%d) found", nPicParamSets);
            return ERROR_MALFORMED;
        }
    }
	
    {
        // Check on the number of pic parameter sets
        size_t nVdoParamSets = mVdoParamSets.size();
        ALOGD("vdo parameter sets (%d)", nVdoParamSets);
    }

    return OK;
}



status_t MPEG4Writer::Track::makeHEVCCodecSpecificData(
        const uint8_t *data, size_t size) {
        
	ALOGD("makeHEVCCodecSpecificData,size=%d",size);

	dumpHex(data, size);
    if (mCodecSpecificData != NULL) {
        ALOGE("Already have codec specific data");
        return ERROR_MALFORMED;
    }

    if (size < 4) {  // maybe need reconfirm
        ALOGE("Codec specific data length too short: %d", size);
        return ERROR_MALFORMED;
    }
	
	if(!mMultiSliceBS){
	    // Data is in the form of HEVCCodecSpecificData
		ALOGI("makeHEVCCodecSpecificData, is not multi slice bitstream");
		
	    if (memcmp("\x00\x00\x00\x01", data, 4)) {
	        ALOGD("No start code HEVC header, copy directly");
	        return copyHEVCCodecSpecificData(data, size);
	    }

	    if (parseHEVCCodecSpecificData(data, size) != OK) {
			ALOGE("parseHEVCCodecSpecificData error");
	        return ERROR_MALFORMED;
	    }
	}
	else{ // if multi-NAL case ,nalsize+nal+nalsize+nal,  parse sps,pps,sei by nal size
		ALOGI("H.265 before parseHEVCCodecSpecificDataByNALSize");
		if(parseHEVCCodecSpecificDataByNALSize(data,size)!= OK){
			ALOGE("parseHEVCCodecSpecificDataByNALSize Failed");
			return ERROR_MALFORMED;
		}
	}

    // ISO 14496-15: HEVC file format 
    mCodecSpecificDataSize += 23;  // 23 more bytes in the header
    uint8_t numOfArrays = 0;
    if(mVdoParamSets.size() > 0) {
		mCodecSpecificDataSize += 3; 
		numOfArrays++;
    }
    if(mSeqParamSets.size() > 0){
		mCodecSpecificDataSize += 3; 
		numOfArrays++;
    }
	if(mPicParamSets.size() > 0){
		mCodecSpecificDataSize += 3; 
		numOfArrays++;
    } 
    mCodecSpecificData = malloc(mCodecSpecificDataSize);
    uint8_t *header = (uint8_t *)mCodecSpecificData;

	ALOGD("makeHEVCCodecSpecificData,mProfileIdc=%x,mProfileCompatible=%x,mLevelIdc=%x", mProfileIdc, mProfileCompatible, mLevelIdc);

	// version
    header[0] = 0x01;
	
	// profile space: 00,  tier flag:0,  profile indication
    header[1] = 0x18 & 0x00;
	
    // profile compatibility
    header[2] = 0x80;
    header[3] = 0x00;
	header[4] = 0x00;
	header[5] = 0x00;

    // constranint indicator
    header[6] = 0x00;
    header[7] = 0x00;
    header[8] = 0x00;
	header[9] = 0x00;
    header[10] = 0x00;
    header[11] = 0x00;
	
    // level indication
    //header[12] = mLevelIdc;
    header[12] = 0x00;

    // min spatial segmentation idc
    header[13] = 0xf0 | 0x00;
    header[14] = 0x00;

    // parallelism type
    header[15] = 0xfc | 0x00;
	
    // chroma format
    header[16] = 0xfc | 0x01;

    // bit depth luma minus
    header[17] = 0xf8 | 0x00;

    // bit depth chroma minus
    header[18] = 0xf8 | 0x00;

    // avg frame rate
    header[19] = 0x00;
    header[20] = 0x00;

	// constant frame rate
    header[21] = 0x00 | 0x00; // 2bit
	// num temporal layer
    header[21] |= 0x08;  // 3bit
	// temporal id nesting
    header[21] |= 0x04;  // 1bit
	// temporal id nesting
    // lengthSizeMinuusOne
    if (mOwner->useNalLengthFour()) {
        header[21] |= 0x03;  // length size == 4 bytes
    } else {
        header[21] |= 0x01;  // length size == 2 bytes
    }

	// num of arrays
	header[22] = numOfArrays;

	uint32_t i=23;
	
    if(mVdoParamSets.size() > 0) {
		// array completeness
		header[i] = 0x00 | 0x80;

		// nal type
		header[i++] |= kNalUnitTypeVdoParamSet_HEVC;

		
		header[i++] = (mVdoParamSets.size()) >> 8;
		header[i++] = (mVdoParamSets.size()) & 0xff;
		
		for (List<AVCParamSet>::iterator it = mVdoParamSets.begin();
			 it != mVdoParamSets.end(); ++it) {
			// 16-bit sequence parameter set length
			uint16_t vdoParamSetLength = it->mLength;
			header[i++] = vdoParamSetLength >> 8;
			header[i++] = vdoParamSetLength & 0xff;
		
			// SPS NAL unit (sequence parameter length bytes)
			memcpy(&header[i], it->mData, vdoParamSetLength);
			header += vdoParamSetLength;
		}
    }

	
    if(mSeqParamSets.size() > 0) {
		// array completeness
		header[i] = 0x00 | 0x80;

		// nal type
		header[i++] |= kNalUnitTypeSeqParamSet_HEVC;

		
		header[i++] = (mSeqParamSets.size()) >> 8;
		header[i++] = (mSeqParamSets.size()) & 0xff;
		
		for (List<AVCParamSet>::iterator it = mSeqParamSets.begin();
			 it != mSeqParamSets.end(); ++it) {
			// 16-bit sequence parameter set length
			uint16_t seqParamSetLength = it->mLength;
			header[i++] = seqParamSetLength >> 8;
			header[i++] = seqParamSetLength & 0xff;
		
			// SPS NAL unit (sequence parameter length bytes)
			memcpy(&header[i], it->mData, seqParamSetLength);
			header += seqParamSetLength;
		}
    }

	
    if(mPicParamSets.size() > 0) {
		// array completeness
		header[i] = 0x00 | 0x80;

		// nal type
		header[i++] |= kNalUnitTypePicParamSet_HEVC;

		
		header[i++] = (mPicParamSets.size()) >> 8;
		header[i++] = (mPicParamSets.size()) & 0xff;
		
		for (List<AVCParamSet>::iterator it = mPicParamSets.begin();
			 it != mPicParamSets.end(); ++it) {
			// 16-bit sequence parameter set length
			uint16_t picParamSetLength = it->mLength;
			header[i++] = picParamSetLength >> 8;
			header[i++] = picParamSetLength & 0xff;
		
			// SPS NAL unit (sequence parameter length bytes)
			memcpy(&header[i], it->mData, picParamSetLength);
			header += picParamSetLength;
		}
    }

	
	ALOGD("HEVCCodecSpecificData");
	dumpHex((uint8_t *)mCodecSpecificData, mCodecSpecificDataSize);
	
    return OK;
}
void MPEG4Writer::Track::writeHvccBox() {
    CHECK(mCodecSpecificData);
    CHECK_GE(mCodecSpecificDataSize, 23);

    // Patch hvcc's lengthSize field to match the number
    // of bytes we use to indicate the size of a nal unit.
    uint8_t *ptr = (uint8_t *)mCodecSpecificData;
    ptr[21] = (ptr[21] & 0xfc) | ((mOwner->useNalLengthFour()||mMultiSliceBS) ? 3 : 1);
    mOwner->beginBox("hvcC");
    mOwner->write(mCodecSpecificData, mCodecSpecificDataSize);
    mOwner->endBox();  // hvcC
}

//#endif

#ifdef WRITER_ENABLE_EDTS_BOX
void MPEG4Writer::Track::writeEdtsBox(){
	ALOGD("%s track writeEdtsBox ++", mIsAudio? "Audio": "Video");
	int64_t moovStartTimeUs = mOwner->getStartTimestampUs();
	int32_t mvhdTimeScale = mOwner->getTimeScale();
	int64_t trakDurationUs = getDurationUs();
	if (mStartTimestampUs != moovStartTimeUs) {
		mOwner->beginBox("edts");
		  mOwner->beginBox("elst");
		    mOwner->writeInt32(0);           // version=0, flags=0: 32-bit time
		    mOwner->writeInt32(2);           // never ends with an empty list


		    // First elst entry: specify the starting time offset
		    int64_t offsetUs = mStartTimestampUs - moovStartTimeUs;
		    ALOGD("OffsetUs: %" PRId64 "", offsetUs);
		    int32_t seg = (offsetUs * mvhdTimeScale + 5E5) / 1E6;

		    mOwner->writeInt32(seg);         // in mvhd timecale
		    mOwner->writeInt32(-1);          // starting time offset
		    mOwner->writeInt32(1 << 16);     // rate = 1.0

		    // Second elst entry: specify the track duration
		    seg = (trakDurationUs * mvhdTimeScale + 5E5) / 1E6;
		    mOwner->writeInt32(seg);         // in mvhd timescale
		    mOwner->writeInt32(0);
		    mOwner->writeInt32(1 << 16);
		  mOwner->endBox();
		mOwner->endBox();
	}
	ALOGD("%s track writeEdtsBox --", mIsAudio? "Audio": "Video");
}
#endif


#endif//
}  // namespace android
