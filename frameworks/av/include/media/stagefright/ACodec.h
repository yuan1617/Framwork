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

#ifndef A_CODEC_H_

#define A_CODEC_H_

#include <stdint.h>
#include <android/native_window.h>
#include <media/IOMX.h>
#include <media/stagefright/foundation/AHierarchicalStateMachine.h>
#include <media/stagefright/CodecBase.h>
#include <media/stagefright/SkipCutBuffer.h>
#include <OMX_Audio.h>

#define TRACK_BUFFER_TIMING     0

#ifdef MTK_AOSP_ENHANCEMENT
#define VIDEO_M4U_MAX_BUFFER 100
#define APPLY_CHECKING_FLUSH_COMPLETED 1
#define APPLY_CHECKING_FBDEOS_COMPLETED 0
#define APPLY_CHECKING_COMPLETED_INIT_MASK 0
#define APPLY_CHECKING_COMPLETED_EOS_MASK 1
#define APPLY_CHECKING_COMPLETED_FLUSH_MASK 2

#include <media/stagefright/OMXCodec.h>
#endif //MTK_AOSP_ENHANCEMENT

namespace android {

struct ABuffer;
struct MemoryDealer;
struct DescribeColorFormatParams;

struct ACodec : public AHierarchicalStateMachine, public CodecBase {
    ACodec();

    virtual void setNotificationMessage(const sp<AMessage> &msg);

    void initiateSetup(const sp<AMessage> &msg);

    virtual void initiateAllocateComponent(const sp<AMessage> &msg);
    virtual void initiateConfigureComponent(const sp<AMessage> &msg);
    virtual void initiateCreateInputSurface();
    virtual void initiateStart();
    virtual void initiateShutdown(bool keepComponentAllocated = false);

    virtual void signalFlush();
    virtual void signalResume();

    virtual void signalSetParameters(const sp<AMessage> &msg);
    virtual void signalEndOfInputStream();
    virtual void signalRequestIDRFrame();

    // AHierarchicalStateMachine implements the message handling
    virtual void onMessageReceived(const sp<AMessage> &msg) {
        handleMessage(msg);
    }

#ifdef MTK_AOSP_ENHANCEMENT
#if APPLY_CHECKING_FLUSH_COMPLETED
        void signalVDecFlushDoneCheck(int delayTime, int mTimeOut);
        status_t setVDecCheckFlushDone(int delayTime, int mTimeOut);   //should be private
#endif //APPLY_CHECKING_FLUSH_COMPLETED
#endif//MTK_AOSP_ENHANCEMENT

    struct PortDescription : public CodecBase::PortDescription {
        size_t countBuffers();
        IOMX::buffer_id bufferIDAt(size_t index) const;
        sp<ABuffer> bufferAt(size_t index) const;

    private:
        friend struct ACodec;

        Vector<IOMX::buffer_id> mBufferIDs;
        Vector<sp<ABuffer> > mBuffers;

        PortDescription();
        void addBuffer(IOMX::buffer_id id, const sp<ABuffer> &buffer);

        DISALLOW_EVIL_CONSTRUCTORS(PortDescription);
    };

    static bool isFlexibleColorFormat(
            const sp<IOMX> &omx, IOMX::node_id node,
            uint32_t colorFormat, OMX_U32 *flexibleEquivalent);

    // Returns 0 if configuration is not supported.  NOTE: this is treated by
    // some OMX components as auto level, and by others as invalid level.
    static int /* OMX_VIDEO_AVCLEVELTYPE */ getAVCLevelFor(
            int width, int height, int rate, int bitrate,
            OMX_VIDEO_AVCPROFILETYPE profile = OMX_VIDEO_AVCProfileBaseline);

protected:
    virtual ~ACodec();

private:
    struct BaseState;
    struct UninitializedState;
    struct LoadedState;
    struct LoadedToIdleState;
    struct IdleToExecutingState;
    struct ExecutingState;
    struct OutputPortSettingsChangedState;
    struct ExecutingToIdleState;
    struct IdleToLoadedState;
    struct FlushingState;
    struct DeathNotifier;

    enum {
        kWhatSetup                   = 'setu',
        kWhatOMXMessage              = 'omx ',
        kWhatInputBufferFilled       = 'inpF',
        kWhatOutputBufferDrained     = 'outD',
        kWhatShutdown                = 'shut',
        kWhatFlush                   = 'flus',
        kWhatResume                  = 'resm',
        kWhatDrainDeferredMessages   = 'drai',
        kWhatAllocateComponent       = 'allo',
        kWhatConfigureComponent      = 'conf',
        kWhatCreateInputSurface      = 'cisf',
        kWhatSignalEndOfInputStream  = 'eois',
        kWhatStart                   = 'star',
        kWhatRequestIDRFrame         = 'ridr',
        kWhatSetParameters           = 'setP',
        kWhatSubmitOutputMetaDataBufferIfEOS = 'subm',
#ifdef MTK_AOSP_ENHANCEMENT
#if APPLY_CHECKING_FLUSH_COMPLETED
        kWhatMtkVdecCheckFlushDone   = 'MVdF',
#endif //APPLY_CHECKING_FLUSH_COMPLETED
#ifdef APPLY_CHECKING_FBDEOS_COMPLETED
        kWhatMtkVdecCheckFBDEOSDone   = 'MVnD',
#endif //APPLY_CHECKING_FBDEOS_COMPLETED
#endif//MTK_AOSP_ENHANCEMENT
        kWhatOMXDied                 = 'OMXd',
        kWhatReleaseCodecInstance    = 'relC',
    };

    enum {
        kPortIndexInput  = 0,
        kPortIndexOutput = 1
    };

    enum {
        kFlagIsSecure                                 = 1,
        kFlagPushBlankBuffersToNativeWindowOnShutdown = 2,
#ifdef MTK_AOSP_ENHANCEMENT
        kFlagIsProtect                                = 4,
#endif // MTK_AOSP_ENHANCEMENT
    };

    struct BufferInfo {
        enum Status {
            OWNED_BY_US,
            OWNED_BY_COMPONENT,
            OWNED_BY_UPSTREAM,
            OWNED_BY_DOWNSTREAM,
            OWNED_BY_NATIVE_WINDOW,
#ifdef MTK_AOSP_ENHANCEMENT
            OWNED_BY_UNEXPECTED
#endif //MTK_AOSP_ENHANCEMENT
        };

        IOMX::buffer_id mBufferID;
        Status mStatus;
        unsigned mDequeuedAt;

        sp<ABuffer> mData;
        sp<GraphicBuffer> mGraphicBuffer;
    };

#if TRACK_BUFFER_TIMING
    struct BufferStats {
        int64_t mEmptyBufferTimeUs;
        int64_t mFillBufferDoneTimeUs;
    };

    KeyedVector<int64_t, BufferStats> mBufferStats;
#endif

    sp<AMessage> mNotify;

    sp<UninitializedState> mUninitializedState;
    sp<LoadedState> mLoadedState;
    sp<LoadedToIdleState> mLoadedToIdleState;
    sp<IdleToExecutingState> mIdleToExecutingState;
    sp<ExecutingState> mExecutingState;
    sp<OutputPortSettingsChangedState> mOutputPortSettingsChangedState;
    sp<ExecutingToIdleState> mExecutingToIdleState;
    sp<IdleToLoadedState> mIdleToLoadedState;
    sp<FlushingState> mFlushingState;
    sp<SkipCutBuffer> mSkipCutBuffer;

    AString mComponentName;
    uint32_t mFlags;
    uint32_t mQuirks;
    sp<IOMX> mOMX;
    IOMX::node_id mNode;
    sp<MemoryDealer> mDealer[2];

    sp<ANativeWindow> mNativeWindow;
    sp<AMessage> mInputFormat;
    sp<AMessage> mOutputFormat;

    Vector<BufferInfo> mBuffers[2];

    bool mPortEOS[2];
    status_t mInputEOSResult;

    List<sp<AMessage> > mDeferredQueue;

    bool mSentFormat;
    bool mIsEncoder;
    bool mUseMetadataOnEncoderOutput;
    bool mShutdownInProgress;
    bool mExplicitShutdown;

    // If "mKeepComponentAllocated" we only transition back to Loaded state
    // and do not release the component instance.
    bool mKeepComponentAllocated;

    int32_t mEncoderDelay;
    int32_t mEncoderPadding;
    int32_t mRotationDegrees;

    bool mChannelMaskPresent;
    int32_t mChannelMask;
    unsigned mDequeueCounter;
    bool mStoreMetaDataInOutputBuffers;
    int32_t mMetaDataBuffersToSubmit;
    size_t mNumUndequeuedBuffers;

    int64_t mRepeatFrameDelayUs;

    int64_t mMaxPtsGapUs;

    int64_t mTimePerFrameUs;
    int64_t mTimePerCaptureUs;

    bool mCreateInputBuffersSuspended;

    bool mTunneled;

#ifdef MTK_AOSP_ENHANCEMENT
    bool mSupportsPartialFrames;
    bool mIsVideoDecoder;
    bool mIsVideoEncoder;
    sp<ABuffer> mLeftOverBuffer;
    int32_t mMaxQueueBufferNum;
    FILE* mDumpFile;
    bool mIsDumpFile;
    int32_t mVideoAspectRatioWidth;
    int32_t mVideoAspectRatioHeight;
    FILE* mDumpRawFile;
    bool mIsDumpRawFile;
    size_t mAlignedSize;
    void*  mM4UBufferHandle;
    bool mIsDumpProflingFile;

    int32_t mIsVideoEncoderInputSurface;

#if APPLY_CHECKING_FLUSH_COMPLETED
    uint32_t mTotalTimeDuringCheckFlush;
    int32_t mPortsFlushComplete;
#endif //APPLY_CHECKING_FLUSH_COMPLETED

    bool mOMXLivesLocally;

    bool mIsProfileFPS;
    bool mIsProfileNWqueueBuffer;
    static bool mIsProfileBufferActivity;
    bool mIsVideo;
    uint32_t mFrameCount;
    int64_t mFirstPostBufferTime;
    int64_t mLastPostBufferTime;

    bool mIsSlowmotion;
    int64_t mAnchorTimeRealUs;
#endif //#ifndef MTK_AOSP_ENHANCEMENT

    status_t setCyclicIntraMacroblockRefresh(const sp<AMessage> &msg, int32_t mode);
    status_t allocateBuffersOnPort(OMX_U32 portIndex);
    status_t freeBuffersOnPort(OMX_U32 portIndex);
    status_t freeBuffer(OMX_U32 portIndex, size_t i);

    status_t configureOutputBuffersFromNativeWindow(
            OMX_U32 *nBufferCount, OMX_U32 *nBufferSize,
            OMX_U32 *nMinUndequeuedBuffers);
    status_t allocateOutputMetaDataBuffers();
    status_t submitOutputMetaDataBuffer();
    void signalSubmitOutputMetaDataBufferIfEOS_workaround();
    status_t allocateOutputBuffersFromNativeWindow();
    status_t cancelBufferToNativeWindow(BufferInfo *info);
    status_t freeOutputBuffersNotOwnedByComponent();
    BufferInfo *dequeueBufferFromNativeWindow();

    BufferInfo *findBufferByID(
            uint32_t portIndex, IOMX::buffer_id bufferID,
            ssize_t *index = NULL);

    status_t setComponentRole(bool isEncoder, const char *mime);
    status_t configureCodec(const char *mime, const sp<AMessage> &msg);

    status_t configureTunneledVideoPlayback(int32_t audioHwSync,
            const sp<ANativeWindow> &nativeWindow);

    status_t setVideoPortFormatType(
            OMX_U32 portIndex,
            OMX_VIDEO_CODINGTYPE compressionFormat,
            OMX_COLOR_FORMATTYPE colorFormat);

    status_t setSupportedOutputFormat();

    status_t setupVideoDecoder(
            const char *mime, const sp<AMessage> &msg);

    status_t setupVideoEncoder(
            const char *mime, const sp<AMessage> &msg);

    status_t setVideoFormatOnPort(
            OMX_U32 portIndex,
            int32_t width, int32_t height,
            OMX_VIDEO_CODINGTYPE compressionFormat);

    typedef struct drcParams {
        int32_t drcCut;
        int32_t drcBoost;
        int32_t heavyCompression;
        int32_t targetRefLevel;
        int32_t encodedTargetLevel;
    } drcParams_t;

    status_t setupAACCodec(
            bool encoder,
            int32_t numChannels, int32_t sampleRate, int32_t bitRate,
            int32_t aacProfile, bool isADTS, int32_t sbrMode,
            int32_t maxOutputChannelCount, const drcParams_t& drc,
            int32_t pcmLimiterEnable);

    status_t setupAC3Codec(bool encoder, int32_t numChannels, int32_t sampleRate);

    status_t selectAudioPortFormat(
            OMX_U32 portIndex, OMX_AUDIO_CODINGTYPE desiredFormat);

    status_t setupAMRCodec(bool encoder, bool isWAMR, int32_t bitRate);
    status_t setupG711Codec(bool encoder, int32_t numChannels);

    status_t setupFlacCodec(
            bool encoder, int32_t numChannels, int32_t sampleRate, int32_t compressionLevel);

    status_t setupRawAudioFormat(
            OMX_U32 portIndex, int32_t sampleRate, int32_t numChannels);

    status_t setMinBufferSize(OMX_U32 portIndex, size_t size);

    status_t setupMPEG4EncoderParameters(const sp<AMessage> &msg);
    status_t setupH263EncoderParameters(const sp<AMessage> &msg);
    status_t setupAVCEncoderParameters(const sp<AMessage> &msg);
    status_t setupHEVCEncoderParameters(const sp<AMessage> &msg);
    status_t setupVPXEncoderParameters(const sp<AMessage> &msg);

    status_t verifySupportForProfileAndLevel(int32_t profile, int32_t level);

    status_t configureBitrate(
            int32_t bitrate, OMX_VIDEO_CONTROLRATETYPE bitrateMode);

    status_t setupErrorCorrectionParameters();

    status_t initNativeWindow();

    status_t pushBlankBuffersToNativeWindow();

    // Returns true iff all buffers on the given port have status
    // OWNED_BY_US or OWNED_BY_NATIVE_WINDOW.
    bool allYourBuffersAreBelongToUs(OMX_U32 portIndex);

    bool allYourBuffersAreBelongToUs();

    void waitUntilAllPossibleNativeWindowBuffersAreReturnedToUs();

    size_t countBuffersOwnedByComponent(OMX_U32 portIndex) const;
    size_t countBuffersOwnedByNativeWindow() const;

    void deferMessage(const sp<AMessage> &msg);
    void processDeferredMessages();

    void sendFormatChange(const sp<AMessage> &reply);
    status_t getPortFormat(OMX_U32 portIndex, sp<AMessage> &notify);

    void signalError(
            OMX_ERRORTYPE error = OMX_ErrorUndefined,
            status_t internalError = UNKNOWN_ERROR);

    static bool describeDefaultColorFormat(DescribeColorFormatParams &describeParams);
    static bool describeColorFormat(
        const sp<IOMX> &omx, IOMX::node_id node,
        DescribeColorFormatParams &describeParams);

    status_t requestIDRFrame();
    status_t setParameters(const sp<AMessage> &params);

#ifdef MTK_AOSP_ENHANCEMENT
	status_t setupWMACodec(bool encoder, int32_t numChannels, int32_t sampleRate);
	status_t setupADPCMCodec(const char *mime, const sp<AMessage> &msg);
	status_t setupAlacCodec(const char *mime, const sp<AMessage> &msg);

    bool LogAllYourBuffersAreBelongToUs(OMX_U32 portIndex);
    bool LogAllYourBuffersAreBelongToUs();

	status_t setMTKParameters(const sp<AMessage> &params);
	int32_t profileAndQueueBuffer2NativeWindow(struct ANativeWindow* window,
	    struct ANativeWindowBuffer* buffer, int fenceFd);
	int profileAndDequeueNativeWindow(ANativeWindow *anw, struct ANativeWindowBuffer** anb);
	status_t setSlowMotionSpeed(unsigned int u4SlowMotionSpeed);
    status_t setSlowMotionSection(int64_t i64StartTime, int64_t i64EndTime);
    void configureInputDump();
    void configureOutputDump();
    void dumpInput(sp<ABuffer> buffer);
    void dumpOutputOnOMXFBD(BufferInfo *info, size_t rangeLength);
    void dumpOutputOnOutputBufferDrained(BufferInfo *info);
#endif

    // Send EOS on input stream.
    void onSignalEndOfInputStream();

    DISALLOW_EVIL_CONSTRUCTORS(ACodec);
};

}  // namespace android

#endif  // A_CODEC_H_
