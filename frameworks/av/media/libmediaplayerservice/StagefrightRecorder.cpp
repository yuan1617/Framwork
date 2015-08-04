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

#define LOG_TAG "StagefrightRecorder"
#include <inttypes.h>
#include <utils/Log.h>
#include <cutils/xlog.h>

#ifdef MTK_AOSP_ENHANCEMENT
#undef ALOGV
#define ALOGV ALOGD
#define MM_LOGD(fmt, arg...)       XLOGD("[%s] "fmt, __FUNCTION__, ##arg)
#define MM_LOGE(fmt, arg...)       XLOGE("[%s] "fmt, __FUNCTION__, ##arg)

#include <media/stagefright/PCMWriter.h>
#include <media/stagefright/OggWriter.h>
// for MCI buffer 
#include "venc_drv_if_public.h" 

#ifdef HAVE_ADPCMENCODE_FEATURE
#include <media/stagefright/ADPCMWriter.h>
#endif
#ifdef MTK_SLOW_MOTION_VIDEO_SUPPORT
#include <MtkBSSource.h>
#include <omx_core/OMX_Component.h>
#include <cutils/properties.h>

#endif
#ifdef HAVE_AEE_FEATURE	
#include "aee.h"
#endif

#else
#define MM_LOGD(fmt, arg...)
#define MM_LOGE(fmt, arg...)
#endif

#include "WebmWriter.h"
#include "StagefrightRecorder.h"

#include <binder/IPCThreadState.h>
#include <binder/IServiceManager.h>
#include <media/IMediaPlayerService.h>
#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/foundation/ALooper.h>
#include <media/stagefright/ACodec.h>
#include <media/stagefright/AudioSource.h>
#include <media/stagefright/AMRWriter.h>
#include <media/stagefright/AACWriter.h>
#include <media/stagefright/CameraSource.h>
#include <media/stagefright/CameraSourceTimeLapse.h>
#include <media/stagefright/MPEG2TSWriter.h>
#include <media/stagefright/MPEG4Writer.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/MediaCodecSource.h>
#include <media/stagefright/OMXClient.h>
#include <media/stagefright/OMXCodec.h>
#include <media/MediaProfiles.h>
#include <camera/ICamera.h>
#include <camera/CameraParameters.h>

#include <utils/Errors.h>
#include <sys/types.h>
#include <ctype.h>
#include <unistd.h>

#include <system/audio.h>

#include "ARTPWriter.h"

namespace android {

// To collect the encoder usage for the battery app
static void addBatteryData(uint32_t params) {
    sp<IBinder> binder =
        defaultServiceManager()->getService(String16("media.player"));
    sp<IMediaPlayerService> service = interface_cast<IMediaPlayerService>(binder);
    CHECK(service.get() != NULL);

    service->addBatteryData(params);
}


StagefrightRecorder::StagefrightRecorder()
    : mWriter(NULL),
      mOutputFd(-1),
      mAudioSource(AUDIO_SOURCE_CNT),
      mVideoSource(VIDEO_SOURCE_LIST_END),
      mCaptureTimeLapse(false),
      mStarted(false) {

    ALOGV("Constructor");
#ifdef MTK_AOSP_ENHANCEMENT
	preInit();
#endif
    reset();
}

StagefrightRecorder::~StagefrightRecorder() {
    ALOGV("Destructor");

    stop();

    if (mLooper != NULL) {
        mLooper->stop();
    }

MM_LOGD("-");
}

status_t StagefrightRecorder::init() {
    ALOGV("init");

    mLooper = new ALooper;
    mLooper->setName("recorder_looper");
    mLooper->start();

    return OK;
}

// The client side of mediaserver asks it to creat a SurfaceMediaSource
// and return a interface reference. The client side will use that
// while encoding GL Frames
sp<IGraphicBufferProducer> StagefrightRecorder::querySurfaceMediaSource() const {
    ALOGV("Get SurfaceMediaSource");
    return mGraphicBufferProducer;
}

status_t StagefrightRecorder::setAudioSource(audio_source_t as) {
    ALOGV("setAudioSource: %d", as);
    if (as < AUDIO_SOURCE_DEFAULT ||
        as >= AUDIO_SOURCE_CNT) {
        ALOGE("Invalid audio source: %d", as);
        return BAD_VALUE;
    }

    if (as == AUDIO_SOURCE_DEFAULT) {
        mAudioSource = AUDIO_SOURCE_MIC;
    } else {
        mAudioSource = as;
    }

    return OK;
}

status_t StagefrightRecorder::setVideoSource(video_source vs) {
    ALOGV("setVideoSource: %d", vs);
    if (vs < VIDEO_SOURCE_DEFAULT ||
        vs >= VIDEO_SOURCE_LIST_END) {
        ALOGE("Invalid video source: %d", vs);
        return BAD_VALUE;
    }

    if (vs == VIDEO_SOURCE_DEFAULT) {
        mVideoSource = VIDEO_SOURCE_CAMERA;
    } else {
        mVideoSource = vs;
    }

    return OK;
}

status_t StagefrightRecorder::setOutputFormat(output_format of) {
    ALOGV("setOutputFormat: %d", of);
    if (of < OUTPUT_FORMAT_DEFAULT ||
        of >= OUTPUT_FORMAT_LIST_END) {
        ALOGE("Invalid output format: %d", of);
        return BAD_VALUE;
    }

    if (of == OUTPUT_FORMAT_DEFAULT) {
        mOutputFormat = OUTPUT_FORMAT_THREE_GPP;
    } else {
        mOutputFormat = of;
    }

    return OK;
}

status_t StagefrightRecorder::setAudioEncoder(audio_encoder ae) {
    ALOGV("setAudioEncoder: %d", ae);
    if (ae < AUDIO_ENCODER_DEFAULT ||
        ae >= AUDIO_ENCODER_LIST_END) {
        ALOGE("Invalid audio encoder: %d", ae);
        return BAD_VALUE;
    }

    if (ae == AUDIO_ENCODER_DEFAULT) {
        mAudioEncoder = AUDIO_ENCODER_AMR_NB;
    } else {
        mAudioEncoder = ae;
    }

    return OK;
}

status_t StagefrightRecorder::setVideoEncoder(video_encoder ve) {
    ALOGV("setVideoEncoder: %d", ve);
    if (ve < VIDEO_ENCODER_DEFAULT ||
        ve >= VIDEO_ENCODER_LIST_END) {
        ALOGE("Invalid video encoder: %d", ve);
        return BAD_VALUE;
    }


    mVideoEncoder = ve;

#ifdef MTK_AOSP_ENHANCEMENT
#ifdef MTK_VIDEO_HEVC_SUPPORT
	if (ve == VIDEO_ENCODER_CTS_DUMMY) {
		mVideoEncoder = VIDEO_ENCODER_MPEG_4_SP;
	}
#endif
#endif


    return OK;
}

status_t StagefrightRecorder::setVideoSize(int width, int height) {
    ALOGV("setVideoSize: %dx%d", width, height);
    if (width <= 0 || height <= 0) {
        ALOGE("Invalid video size: %dx%d", width, height);
        return BAD_VALUE;
    }

    // Additional check on the dimension will be performed later
    mVideoWidth = width;
    mVideoHeight = height;

    return OK;
}

status_t StagefrightRecorder::setVideoFrameRate(int frames_per_second) {
    ALOGV("setVideoFrameRate: %d", frames_per_second);
    if ((frames_per_second <= 0 && frames_per_second != -1) ||
#ifdef MTK_AOSP_ENHANCEMENT
        frames_per_second > 240) {
#else    
        frames_per_second > 120) {
#endif
        ALOGE("Invalid video frame rate: %d", frames_per_second);
        return BAD_VALUE;
    }

    // Additional check on the frame rate will be performed later
    mFrameRate = frames_per_second;

    return OK;
}

status_t StagefrightRecorder::setCamera(const sp<ICamera> &camera,
                                        const sp<ICameraRecordingProxy> &proxy) {
    ALOGV("setCamera");
    if (camera == 0) {
        ALOGE("camera is NULL");
        return BAD_VALUE;
    }
    if (proxy == 0) {
        ALOGE("camera proxy is NULL");
        return BAD_VALUE;
    }

    mCamera = camera;
    mCameraProxy = proxy;
    return OK;
}

status_t StagefrightRecorder::setPreviewSurface(const sp<IGraphicBufferProducer> &surface) {
    ALOGV("setPreviewSurface: %p", surface.get());
    mPreviewSurface = surface;

    return OK;
}

status_t StagefrightRecorder::setOutputFile(const char * /* path */) {
    ALOGE("setOutputFile(const char*) must not be called");
    // We don't actually support this at all, as the media_server process
    // no longer has permissions to create files.

    return -EPERM;
}

status_t StagefrightRecorder::setOutputFile(int fd, int64_t offset, int64_t length) {
    ALOGV("setOutputFile: %d, %lld, %lld", fd, offset, length);
    // These don't make any sense, do they?
    CHECK_EQ(offset, 0ll);
    CHECK_EQ(length, 0ll);

    if (fd < 0) {
        ALOGE("Invalid file descriptor: %d", fd);
        return -EBADF;
    }

    if (mOutputFd >= 0) {
        ::close(mOutputFd);
    }
    mOutputFd = dup(fd);

    return OK;
}

// Attempt to parse an int64 literal optionally surrounded by whitespace,
// returns true on success, false otherwise.
static bool safe_strtoi64(const char *s, int64_t *val) {
    char *end;

    // It is lame, but according to man page, we have to set errno to 0
    // before calling strtoll().
    errno = 0;
    *val = strtoll(s, &end, 10);

    if (end == s || errno == ERANGE) {
        return false;
    }

    // Skip trailing whitespace
    while (isspace(*end)) {
        ++end;
    }

    // For a successful return, the string must contain nothing but a valid
    // int64 literal optionally surrounded by whitespace.

    return *end == '\0';
}

// Return true if the value is in [0, 0x007FFFFFFF]
static bool safe_strtoi32(const char *s, int32_t *val) {
    int64_t temp;
    if (safe_strtoi64(s, &temp)) {
        if (temp >= 0 && temp <= 0x007FFFFFFF) {
            *val = static_cast<int32_t>(temp);
            return true;
        }
    }
    return false;
}

// Trim both leading and trailing whitespace from the given string.
static void TrimString(String8 *s) {
    size_t num_bytes = s->bytes();
    const char *data = s->string();

    size_t leading_space = 0;
    while (leading_space < num_bytes && isspace(data[leading_space])) {
        ++leading_space;
    }

    size_t i = num_bytes;
    while (i > leading_space && isspace(data[i - 1])) {
        --i;
    }

    s->setTo(String8(&data[leading_space], i - leading_space));
}

status_t StagefrightRecorder::setParamAudioSamplingRate(int32_t sampleRate) {
    ALOGV("setParamAudioSamplingRate: %d", sampleRate);
    if (sampleRate <= 0) {
        ALOGE("Invalid audio sampling rate: %d", sampleRate);
        return BAD_VALUE;
    }

    // Additional check on the sample rate will be performed later.
    mSampleRate = sampleRate;
    return OK;
}

status_t StagefrightRecorder::setParamAudioNumberOfChannels(int32_t channels) {
    ALOGV("setParamAudioNumberOfChannels: %d", channels);
    if (channels <= 0 || channels >= 3) {
        ALOGE("Invalid number of audio channels: %d", channels);
        return BAD_VALUE;
    }

    // Additional check on the number of channels will be performed later.
    mAudioChannels = channels;
    return OK;
}

status_t StagefrightRecorder::setParamAudioEncodingBitRate(int32_t bitRate) {
    ALOGV("setParamAudioEncodingBitRate: %d", bitRate);
    if (bitRate <= 0) {
        ALOGE("Invalid audio encoding bit rate: %d", bitRate);
        return BAD_VALUE;
    }

    // The target bit rate may not be exactly the same as the requested.
    // It depends on many factors, such as rate control, and the bit rate
    // range that a specific encoder supports. The mismatch between the
    // the target and requested bit rate will NOT be treated as an error.
    mAudioBitRate = bitRate;
    return OK;
}

status_t StagefrightRecorder::setParamVideoEncodingBitRate(int32_t bitRate) {
    ALOGV("setParamVideoEncodingBitRate: %d", bitRate);
    if (bitRate <= 0) {
        ALOGE("Invalid video encoding bit rate: %d", bitRate);
        return BAD_VALUE;
    }

    // The target bit rate may not be exactly the same as the requested.
    // It depends on many factors, such as rate control, and the bit rate
    // range that a specific encoder supports. The mismatch between the
    // the target and requested bit rate will NOT be treated as an error.
    mVideoBitRate = bitRate;
    return OK;
}

// Always rotate clockwise, and only support 0, 90, 180 and 270 for now.
status_t StagefrightRecorder::setParamVideoRotation(int32_t degrees) {
    ALOGV("setParamVideoRotation: %d", degrees);
    if (degrees < 0 || degrees % 90 != 0) {
        ALOGE("Unsupported video rotation angle: %d", degrees);
        return BAD_VALUE;
    }
    mRotationDegrees = degrees % 360;
    return OK;
}

status_t StagefrightRecorder::setParamMaxFileDurationUs(int64_t timeUs) {
    ALOGV("setParamMaxFileDurationUs: %lld us", timeUs);

    // This is meant for backward compatibility for MediaRecorder.java
    if (timeUs <= 0) {
        ALOGW("Max file duration is not positive: %lld us. Disabling duration limit.", timeUs);
        timeUs = 0; // Disable the duration limit for zero or negative values.
    } else if (timeUs <= 100000LL) {  // XXX: 100 milli-seconds
        ALOGE("Max file duration is too short: %lld us", timeUs);
        return BAD_VALUE;
    }

    if (timeUs <= 15 * 1000000LL) {
        ALOGW("Target duration (%lld us) too short to be respected", timeUs);
    }
    mMaxFileDurationUs = timeUs;
    return OK;
}

status_t StagefrightRecorder::setParamMaxFileSizeBytes(int64_t bytes) {
    ALOGV("setParamMaxFileSizeBytes: %lld bytes", bytes);

    // This is meant for backward compatibility for MediaRecorder.java
    if (bytes <= 0) {
        ALOGW("Max file size is not positive: %lld bytes. "
             "Disabling file size limit.", bytes);
        bytes = 0; // Disable the file size limit for zero or negative values.
    } else if (bytes <= 1024) {  // XXX: 1 kB
        ALOGE("Max file size is too small: %lld bytes", bytes);
        return BAD_VALUE;
    }

    if (bytes <= 100 * 1024) {
        ALOGW("Target file size (%lld bytes) is too small to be respected", bytes);
    }

    mMaxFileSizeBytes = bytes;
    return OK;
}

status_t StagefrightRecorder::setParamInterleaveDuration(int32_t durationUs) {
    ALOGV("setParamInterleaveDuration: %d", durationUs);
    if (durationUs <= 500000) {           //  500 ms
        // If interleave duration is too small, it is very inefficient to do
        // interleaving since the metadata overhead will count for a significant
        // portion of the saved contents
        ALOGE("Audio/video interleave duration is too small: %d us", durationUs);
        return BAD_VALUE;
    } else if (durationUs >= 10000000) {  // 10 seconds
        // If interleaving duration is too large, it can cause the recording
        // session to use too much memory since we have to save the output
        // data before we write them out
        ALOGE("Audio/video interleave duration is too large: %d us", durationUs);
        return BAD_VALUE;
    }
    mInterleaveDurationUs = durationUs;
    return OK;
}

// If seconds <  0, only the first frame is I frame, and rest are all P frames
// If seconds == 0, all frames are encoded as I frames. No P frames
// If seconds >  0, it is the time spacing (seconds) between 2 neighboring I frames
status_t StagefrightRecorder::setParamVideoIFramesInterval(int32_t seconds) {
    ALOGV("setParamVideoIFramesInterval: %d seconds", seconds);
    mIFramesIntervalSec = seconds;
    return OK;
}

status_t StagefrightRecorder::setParam64BitFileOffset(bool use64Bit) {
    ALOGV("setParam64BitFileOffset: %s",
        use64Bit? "use 64 bit file offset": "use 32 bit file offset");
    mUse64BitFileOffset = use64Bit;
    return OK;
}

status_t StagefrightRecorder::setParamVideoCameraId(int32_t cameraId) {
    ALOGV("setParamVideoCameraId: %d", cameraId);
    if (cameraId < 0) {
        return BAD_VALUE;
    }
    mCameraId = cameraId;
    return OK;
}

status_t StagefrightRecorder::setParamTrackTimeStatus(int64_t timeDurationUs) {
    ALOGV("setParamTrackTimeStatus: %lld", timeDurationUs);
    if (timeDurationUs < 20000) {  // Infeasible if shorter than 20 ms?
        ALOGE("Tracking time duration too short: %lld us", timeDurationUs);
        return BAD_VALUE;
    }
    mTrackEveryTimeDurationUs = timeDurationUs;
    return OK;
}

status_t StagefrightRecorder::setParamVideoEncoderProfile(int32_t profile) {
    ALOGV("setParamVideoEncoderProfile: %d", profile);

    // Additional check will be done later when we load the encoder.
    // For now, we are accepting values defined in OpenMAX IL.
    mVideoEncoderProfile = profile;
    return OK;
}

status_t StagefrightRecorder::setParamVideoEncoderLevel(int32_t level) {
    ALOGV("setParamVideoEncoderLevel: %d", level);

    // Additional check will be done later when we load the encoder.
    // For now, we are accepting values defined in OpenMAX IL.
    mVideoEncoderLevel = level;
    return OK;
}

status_t StagefrightRecorder::setParamMovieTimeScale(int32_t timeScale) {
    ALOGV("setParamMovieTimeScale: %d", timeScale);

    // The range is set to be the same as the audio's time scale range
    // since audio's time scale has a wider range.
    if (timeScale < 600 || timeScale > 96000) {
        ALOGE("Time scale (%d) for movie is out of range [600, 96000]", timeScale);
        return BAD_VALUE;
    }
    mMovieTimeScale = timeScale;
    return OK;
}

status_t StagefrightRecorder::setParamVideoTimeScale(int32_t timeScale) {
    ALOGV("setParamVideoTimeScale: %d", timeScale);

    // 60000 is chosen to make sure that each video frame from a 60-fps
    // video has 1000 ticks.
    if (timeScale < 600 || timeScale > 60000) {
        ALOGE("Time scale (%d) for video is out of range [600, 60000]", timeScale);
        return BAD_VALUE;
    }
    mVideoTimeScale = timeScale;
    return OK;
}

status_t StagefrightRecorder::setParamAudioTimeScale(int32_t timeScale) {
    ALOGV("setParamAudioTimeScale: %d", timeScale);

    // 96000 Hz is the highest sampling rate support in AAC.
    if (timeScale < 600 || timeScale > 96000) {
        ALOGE("Time scale (%d) for audio is out of range [600, 96000]", timeScale);
        return BAD_VALUE;
    }
    mAudioTimeScale = timeScale;
    return OK;
}

status_t StagefrightRecorder::setParamTimeLapseEnable(int32_t timeLapseEnable) {
    ALOGV("setParamTimeLapseEnable: %d", timeLapseEnable);

    if(timeLapseEnable == 0) {
        mCaptureTimeLapse = false;
    } else if (timeLapseEnable == 1) {
        mCaptureTimeLapse = true;
    } else {
        return BAD_VALUE;
    }
    return OK;
}

status_t StagefrightRecorder::setParamTimeBetweenTimeLapseFrameCapture(int64_t timeUs) {
    ALOGV("setParamTimeBetweenTimeLapseFrameCapture: %lld us", timeUs);

    // Not allowing time more than a day
    if (timeUs <= 0 || timeUs > 86400*1E6) {
        ALOGE("Time between time lapse frame capture (%lld) is out of range [0, 1 Day]", timeUs);
        return BAD_VALUE;
    }

    mTimeBetweenTimeLapseFrameCaptureUs = timeUs;
    return OK;
}

status_t StagefrightRecorder::setParamGeoDataLongitude(
    int64_t longitudex10000) {

    if (longitudex10000 > 1800000 || longitudex10000 < -1800000) {
        return BAD_VALUE;
    }
    mLongitudex10000 = longitudex10000;
    return OK;
}

status_t StagefrightRecorder::setParamGeoDataLatitude(
    int64_t latitudex10000) {

    if (latitudex10000 > 900000 || latitudex10000 < -900000) {
        return BAD_VALUE;
    }
    mLatitudex10000 = latitudex10000;
    return OK;
}

status_t StagefrightRecorder::setParameter(
        const String8 &key, const String8 &value) {
    ALOGV("setParameter: key (%s) => value (%s)", key.string(), value.string());
    if (key == "max-duration") {
        int64_t max_duration_ms;
        if (safe_strtoi64(value.string(), &max_duration_ms)) {
            return setParamMaxFileDurationUs(1000LL * max_duration_ms);
        }
    } else if (key == "max-filesize") {
        int64_t max_filesize_bytes;
        if (safe_strtoi64(value.string(), &max_filesize_bytes)) {
            return setParamMaxFileSizeBytes(max_filesize_bytes);
        }
    } else if (key == "interleave-duration-us") {
        int32_t durationUs;
        if (safe_strtoi32(value.string(), &durationUs)) {
            return setParamInterleaveDuration(durationUs);
        }
    } else if (key == "param-movie-time-scale") {
        int32_t timeScale;
        if (safe_strtoi32(value.string(), &timeScale)) {
            return setParamMovieTimeScale(timeScale);
        }
    } else if (key == "param-use-64bit-offset") {
        int32_t use64BitOffset;
        if (safe_strtoi32(value.string(), &use64BitOffset)) {
            return setParam64BitFileOffset(use64BitOffset != 0);
        }
    } else if (key == "param-geotag-longitude") {
        int64_t longitudex10000;
        if (safe_strtoi64(value.string(), &longitudex10000)) {
            return setParamGeoDataLongitude(longitudex10000);
        }
    } else if (key == "param-geotag-latitude") {
        int64_t latitudex10000;
        if (safe_strtoi64(value.string(), &latitudex10000)) {
            return setParamGeoDataLatitude(latitudex10000);
        }
    } else if (key == "param-track-time-status") {
        int64_t timeDurationUs;
        if (safe_strtoi64(value.string(), &timeDurationUs)) {
            return setParamTrackTimeStatus(timeDurationUs);
        }
    } else if (key == "audio-param-sampling-rate") {
        int32_t sampling_rate;
        if (safe_strtoi32(value.string(), &sampling_rate)) {
            return setParamAudioSamplingRate(sampling_rate);
        }
    } else if (key == "audio-param-number-of-channels") {
        int32_t number_of_channels;
        if (safe_strtoi32(value.string(), &number_of_channels)) {
            return setParamAudioNumberOfChannels(number_of_channels);
        }
    } else if (key == "audio-param-encoding-bitrate") {
        int32_t audio_bitrate;
        if (safe_strtoi32(value.string(), &audio_bitrate)) {
            return setParamAudioEncodingBitRate(audio_bitrate);
        }
    } else if (key == "audio-param-time-scale") {
        int32_t timeScale;
        if (safe_strtoi32(value.string(), &timeScale)) {
            return setParamAudioTimeScale(timeScale);
        }
    } else if (key == "video-param-encoding-bitrate") {
        int32_t video_bitrate;
        if (safe_strtoi32(value.string(), &video_bitrate)) {
            return setParamVideoEncodingBitRate(video_bitrate);
        }
    } else if (key == "video-param-rotation-angle-degrees") {
        int32_t degrees;
        if (safe_strtoi32(value.string(), &degrees)) {
#ifdef MTK_AUDIO_HD_REC_SUPPORT
            mParams += "LRChannelSwitch=";
            if (degrees == 0)
                mParams += "1;";
            else
                mParams += "0;";
#endif

            return setParamVideoRotation(degrees);
        }
    } else if (key == "video-param-i-frames-interval") {
        int32_t seconds;
        if (safe_strtoi32(value.string(), &seconds)) {
            return setParamVideoIFramesInterval(seconds);
        }
    } else if (key == "video-param-encoder-profile") {
        int32_t profile;
        if (safe_strtoi32(value.string(), &profile)) {
            return setParamVideoEncoderProfile(profile);
        }
    } else if (key == "video-param-encoder-level") {
        int32_t level;
        if (safe_strtoi32(value.string(), &level)) {
            return setParamVideoEncoderLevel(level);
        }
    } else if (key == "video-param-camera-id") {
        int32_t cameraId;
        if (safe_strtoi32(value.string(), &cameraId)) {
            return setParamVideoCameraId(cameraId);
        }
    } else if (key == "video-param-time-scale") {
        int32_t timeScale;
        if (safe_strtoi32(value.string(), &timeScale)) {
            return setParamVideoTimeScale(timeScale);
        }
    } else if (key == "time-lapse-enable") {
        int32_t timeLapseEnable;
        if (safe_strtoi32(value.string(), &timeLapseEnable)) {
            return setParamTimeLapseEnable(timeLapseEnable);
        }
    } else if (key == "time-between-time-lapse-frame-capture") {
        int64_t timeBetweenTimeLapseFrameCaptureUs;
        if (safe_strtoi64(value.string(), &timeBetweenTimeLapseFrameCaptureUs)) {
            return setParamTimeBetweenTimeLapseFrameCapture(
                    timeBetweenTimeLapseFrameCaptureUs);
        }
    } else {
#ifdef MTK_AOSP_ENHANCEMENT
    	return setParameterEx(key, value);
#endif
        ALOGE("setParameter: failed to find key %s", key.string());
    }
    return BAD_VALUE;
}

status_t StagefrightRecorder::setParameters(const String8 &params) {
    ALOGV("setParameters: %s", params.string());
    const char *cparams = params.string();
    const char *key_start = cparams;
    for (;;) {
        const char *equal_pos = strchr(key_start, '=');
        if (equal_pos == NULL) {
            ALOGE("Parameters %s miss a value", cparams);
            return BAD_VALUE;
        }
        String8 key(key_start, equal_pos - key_start);
        TrimString(&key);
        if (key.length() == 0) {
            ALOGE("Parameters %s contains an empty key", cparams);
            return BAD_VALUE;
        }
        const char *value_start = equal_pos + 1;
        const char *semicolon_pos = strchr(value_start, ';');
        String8 value;
        if (semicolon_pos == NULL) {
            value.setTo(value_start);
        } else {
            value.setTo(value_start, semicolon_pos - value_start);
        }
        if (setParameter(key, value) != OK) {
            return BAD_VALUE;
        }
        if (semicolon_pos == NULL) {
            break;  // Reaches the end
        }
        key_start = semicolon_pos + 1;
    }
    return OK;
}

status_t StagefrightRecorder::setListener(const sp<IMediaRecorderClient> &listener) {
    mListener = listener;

    return OK;
}

status_t StagefrightRecorder::setClientName(const String16& clientName) {
    mClientName = clientName;

    return OK;
}

status_t StagefrightRecorder::prepareInternal() {
    ALOGV("prepare");
    if (mOutputFd < 0) {
        ALOGE("Output file descriptor is invalid");
        return INVALID_OPERATION;
    }

#ifdef MTK_AOSP_ENHANCEMENT
	MM_LOGD("+");
	if (mPaused) {
		return resume();
	}
#endif
    // Get UID here for permission checking
    mClientUid = IPCThreadState::self()->getCallingUid();
	
    status_t status = OK;

    switch (mOutputFormat) {
        case OUTPUT_FORMAT_DEFAULT:
        case OUTPUT_FORMAT_THREE_GPP:
        case OUTPUT_FORMAT_MPEG_4:
        case OUTPUT_FORMAT_WEBM:
            status = setupMPEG4orWEBMRecording();
            break;

        case OUTPUT_FORMAT_AMR_NB:
        case OUTPUT_FORMAT_AMR_WB:
            status = setupAMRRecording();
            break;

        case OUTPUT_FORMAT_AAC_ADIF:
        case OUTPUT_FORMAT_AAC_ADTS:
            status = setupAACRecording();
            break;

        case OUTPUT_FORMAT_RTP_AVP:
            status = setupRTPRecording();
            break;

        case OUTPUT_FORMAT_MPEG2TS:
            status = setupMPEG2TSRecording();
            break;
#ifdef MTK_AOSP_ENHANCEMENT
		case OUTPUT_FORMAT_WAV:
		case OUTPUT_FORMAT_OGG:
			status = OK;
			break;
#endif
        default:
            ALOGE("Unsupported output file format: %d", mOutputFormat);
            status = UNKNOWN_ERROR;
            break;
    }

    return status;
}

status_t StagefrightRecorder::prepare() {
    if (mVideoSource == VIDEO_SOURCE_SURFACE) {
        return prepareInternal();
    }
    return OK;
}

status_t StagefrightRecorder::start() {
    ALOGV("start");
    if (mOutputFd < 0) {
        ALOGE("Output file descriptor is invalid");
        return INVALID_OPERATION;
    }

    status_t status = OK;

    if (mVideoSource != VIDEO_SOURCE_SURFACE) {
        status = prepareInternal();
        if (status != OK) {
            return status;
        }
    }

    if (mWriter == NULL) {
#ifdef MTK_AOSP_ENHANCEMENT
		if (mOutputFormat != OUTPUT_FORMAT_WAV && mOutputFormat != OUTPUT_FORMAT_OGG) {
#endif
        ALOGE("File writer is not avaialble");
        return UNKNOWN_ERROR;
#ifdef MTK_AOSP_ENHANCEMENT
		}
#endif
    }

    switch (mOutputFormat) {
        case OUTPUT_FORMAT_DEFAULT:
        case OUTPUT_FORMAT_THREE_GPP:
        case OUTPUT_FORMAT_MPEG_4:
        case OUTPUT_FORMAT_WEBM:
        {
            bool isMPEG4 = true;
            if (mOutputFormat == OUTPUT_FORMAT_WEBM) {
                isMPEG4 = false;
            }
            sp<MetaData> meta = new MetaData;
            setupMPEG4orWEBMMetaData(&meta);
            status = mWriter->start(meta.get());
            break;
        }
 
#ifdef MTK_AOSP_ENHANCEMENT
        case OUTPUT_FORMAT_AMR_NB:
        case OUTPUT_FORMAT_AMR_WB:
        case OUTPUT_FORMAT_AAC_ADIF:
        case OUTPUT_FORMAT_AAC_ADTS:
        case OUTPUT_FORMAT_MPEG2TS:
        {
            status = mWriter->start();
            break;
        }
        case OUTPUT_FORMAT_RTP_AVP:
        {  
            sp<MetaData> meta = new MetaData;
    if (mRTPTarget.length() > 0) {
        meta->setCString(kKeyRTPTarget, mRTPTarget.string());
    }       
            status = mWriter->start(meta.get());
            break;
        }
        case OUTPUT_FORMAT_WAV:
#ifdef HAVE_ADPCMENCODE_FEATURE
			if(AUDIO_ENCODER_MS_ADPCM == mAudioEncoder || AUDIO_ENCODER_DVI_IMA_ADPCM == mAudioEncoder)
				status = startADPCMRecording();
			else
#endif
	        status = startPCMRecording();
			break;
        case OUTPUT_FORMAT_OGG:		
	        status = startOGGRecording();
			break;
         
#else
        case OUTPUT_FORMAT_AMR_NB:
        case OUTPUT_FORMAT_AMR_WB:
        case OUTPUT_FORMAT_AAC_ADIF:
        case OUTPUT_FORMAT_AAC_ADTS:
        case OUTPUT_FORMAT_RTP_AVP:
        case OUTPUT_FORMAT_MPEG2TS:
        {

            status = mWriter->start();
            break;
        }
#endif

        default:
        {
            ALOGE("Unsupported output file format: %d", mOutputFormat);
            status = UNKNOWN_ERROR;
            break;
        }
    }

    if (status != OK) {
        mWriter.clear();
        mWriter = NULL;
    }

    if ((status == OK) && (!mStarted)) {
        mStarted = true;

        uint32_t params = IMediaPlayerService::kBatteryDataCodecStarted;
        if (mAudioSource != AUDIO_SOURCE_CNT) {
            params |= IMediaPlayerService::kBatteryDataTrackAudio;
        }
        if (mVideoSource != VIDEO_SOURCE_LIST_END) {
            params |= IMediaPlayerService::kBatteryDataTrackVideo;
        }

        addBatteryData(params);
    }
	MM_LOGD("- status=%d",status);
    return status;
}

sp<MediaSource> StagefrightRecorder::createAudioSource() {
#ifdef MTK_AOSP_ENHANCEMENT
	//MTK80721 HDRecord 2011-12-23
	//#ifdef MTK_AUDIO_HD_REC_SUPPORT
    if ((mAudioEncoder == AUDIO_ENCODER_AAC || mAudioEncoder == AUDIO_ENCODER_HE_AAC || mAudioEncoder == AUDIO_ENCODER_AAC_ELD) && mSampleRate < 16000)
    {
        ALOGD("encode profile tuning:encode:%d,samplerate:%d,min smplerate=16K",mAudioEncoder, mSampleRate);
        mSampleRate = 16000;
    }
#endif
    sp<AudioSource> audioSource =
        new AudioSource(
                mAudioSource,
                mSampleRate,
#ifdef MTK_AOSP_ENHANCEMENT
				mParams,
#endif
                mAudioChannels);

    status_t err = audioSource->initCheck();

    if (err != OK) {
        ALOGE("audio source is not initialized");
        return NULL;
    }

    sp<AMessage> format = new AMessage;
    const char *mime;
    switch (mAudioEncoder) {
        case AUDIO_ENCODER_AMR_NB:
        case AUDIO_ENCODER_DEFAULT:
            format->setString("mime", MEDIA_MIMETYPE_AUDIO_AMR_NB);
            break;
        case AUDIO_ENCODER_AMR_WB:
            format->setString("mime", MEDIA_MIMETYPE_AUDIO_AMR_WB);
            break;
        case AUDIO_ENCODER_AAC:
            format->setString("mime", MEDIA_MIMETYPE_AUDIO_AAC);
            format->setInt32("aac-profile", OMX_AUDIO_AACObjectLC);
            break;
#ifdef MTK_AOSP_ENHANCEMENT			
		case AUDIO_ENCODER_VORBIS:
            format->setString("mime", MEDIA_MIMETYPE_AUDIO_VORBIS);
	    	break;
#ifdef HAVE_ADPCMENCODE_FEATURE
		case AUDIO_ENCODER_MS_ADPCM:
            format->setString("mime", MEDIA_MIMETYPE_AUDIO_MS_ADPCM);
			break;
		case AUDIO_ENCODER_DVI_IMA_ADPCM:
            format->setString("mime", MEDIA_MIMETYPE_AUDIO_DVI_IMA_ADPCM);
			break;
#endif		
#endif
        case AUDIO_ENCODER_HE_AAC:
            format->setString("mime", MEDIA_MIMETYPE_AUDIO_AAC);
            format->setInt32("aac-profile", OMX_AUDIO_AACObjectHE);
            break;
        case AUDIO_ENCODER_AAC_ELD:
            format->setString("mime", MEDIA_MIMETYPE_AUDIO_AAC);
            format->setInt32("aac-profile", OMX_AUDIO_AACObjectELD);
            break;

        default:
            ALOGE("Unknown audio encoder: %d", mAudioEncoder);
            return NULL;
    }

    int32_t maxInputSize;
    CHECK(audioSource->getFormat()->findInt32(
                kKeyMaxInputSize, &maxInputSize));

    format->setInt32("max-input-size", maxInputSize);
    format->setInt32("channel-count", mAudioChannels);
    format->setInt32("sample-rate", mSampleRate);
    format->setInt32("bitrate", mAudioBitRate);

    MM_LOGD("mAudioBitRate=%d",mAudioBitRate);

    if (mAudioTimeScale > 0) {
        format->setInt32("time-scale", mAudioTimeScale);
    }

    sp<MediaSource> audioEncoder =
            MediaCodecSource::Create(mLooper, format, audioSource);
    mAudioSourceNode = audioSource;

    if (audioEncoder == NULL) {
        ALOGE("Failed to create audio encoder");
    }

    return audioEncoder;
}

status_t StagefrightRecorder::setupAACRecording() {
    // FIXME:
    // Add support for OUTPUT_FORMAT_AAC_ADIF
    CHECK_EQ(mOutputFormat, OUTPUT_FORMAT_AAC_ADTS);

    CHECK(mAudioEncoder == AUDIO_ENCODER_AAC ||
          mAudioEncoder == AUDIO_ENCODER_HE_AAC ||
          mAudioEncoder == AUDIO_ENCODER_AAC_ELD);
    CHECK(mAudioSource != AUDIO_SOURCE_CNT);

    mWriter = new AACWriter(mOutputFd);
    return setupRawAudioRecording();
}

status_t StagefrightRecorder::setupAMRRecording() {
    CHECK(mOutputFormat == OUTPUT_FORMAT_AMR_NB ||
          mOutputFormat == OUTPUT_FORMAT_AMR_WB);

    if (mOutputFormat == OUTPUT_FORMAT_AMR_NB) {
        if (mAudioEncoder != AUDIO_ENCODER_DEFAULT &&
            mAudioEncoder != AUDIO_ENCODER_AMR_NB) {
            ALOGE("Invalid encoder %d used for AMRNB recording",
                    mAudioEncoder);
            return BAD_VALUE;
        }
    } else {  // mOutputFormat must be OUTPUT_FORMAT_AMR_WB
        if (mAudioEncoder != AUDIO_ENCODER_AMR_WB) {
            ALOGE("Invlaid encoder %d used for AMRWB recording",
                    mAudioEncoder);
            return BAD_VALUE;
        }
    }

    mWriter = new AMRWriter(mOutputFd);
    return setupRawAudioRecording();
}

status_t StagefrightRecorder::setupRawAudioRecording() {
    if (mAudioSource >= AUDIO_SOURCE_CNT) {
        ALOGE("Invalid audio source: %d", mAudioSource);
        return BAD_VALUE;
    }

    status_t status = BAD_VALUE;
    if (OK != (status = checkAudioEncoderCapabilities())) {
        return status;
    }

    sp<MediaSource> audioEncoder = createAudioSource();
    if (audioEncoder == NULL) {
        return UNKNOWN_ERROR;
    }

    CHECK(mWriter != 0);
    mWriter->addSource(audioEncoder);

    if (mMaxFileDurationUs != 0) {
        mWriter->setMaxFileDuration(mMaxFileDurationUs);
    }
    if (mMaxFileSizeBytes != 0) {
        mWriter->setMaxFileSize(mMaxFileSizeBytes);
    }
    mWriter->setListener(mListener);

    return OK;
}

status_t StagefrightRecorder::setupRTPRecording() {
    CHECK_EQ(mOutputFormat, OUTPUT_FORMAT_RTP_AVP);

    if ((mAudioSource != AUDIO_SOURCE_CNT
                && mVideoSource != VIDEO_SOURCE_LIST_END)
            || (mAudioSource == AUDIO_SOURCE_CNT
                && mVideoSource == VIDEO_SOURCE_LIST_END)) {
        // Must have exactly one source.
        return BAD_VALUE;
    }

    if (mOutputFd < 0) {
        return BAD_VALUE;
    }

    sp<MediaSource> source;

    if (mAudioSource != AUDIO_SOURCE_CNT) {
        source = createAudioSource();
    } else {
        setDefaultVideoEncoderIfNecessary();

        sp<MediaSource> mediaSource;
        status_t err = setupMediaSource(&mediaSource);
        if (err != OK) {
            return err;
        }

        err = setupVideoEncoder(mediaSource, &source);
        if (err != OK) {
            return err;
        }
    }

    mWriter = new ARTPWriter(mOutputFd);
    mWriter->addSource(source);
    mWriter->setListener(mListener);

    return OK;
}

status_t StagefrightRecorder::setupMPEG2TSRecording() {
    CHECK_EQ(mOutputFormat, OUTPUT_FORMAT_MPEG2TS);

    sp<MediaWriter> writer = new MPEG2TSWriter(mOutputFd);

    if (mAudioSource != AUDIO_SOURCE_CNT) {
        if (mAudioEncoder != AUDIO_ENCODER_AAC &&
            mAudioEncoder != AUDIO_ENCODER_HE_AAC &&
            mAudioEncoder != AUDIO_ENCODER_AAC_ELD) {
            return ERROR_UNSUPPORTED;
        }

        status_t err = setupAudioEncoder(writer);

        if (err != OK) {
            return err;
        }
    }

    if (mVideoSource < VIDEO_SOURCE_LIST_END) {
        if (mVideoEncoder != VIDEO_ENCODER_H264) {
            ALOGE("MPEG2TS recording only supports H.264 encoding!");
            return ERROR_UNSUPPORTED;
        }

        sp<MediaSource> mediaSource;
        status_t err = setupMediaSource(&mediaSource);
        if (err != OK) {
            return err;
        }

        sp<MediaSource> encoder;
        err = setupVideoEncoder(mediaSource, &encoder);

        if (err != OK) {
            return err;
        }

        writer->addSource(encoder);
    }

    if (mMaxFileDurationUs != 0) {
        writer->setMaxFileDuration(mMaxFileDurationUs);
    }

    if (mMaxFileSizeBytes != 0) {
        writer->setMaxFileSize(mMaxFileSizeBytes);
    }

    mWriter = writer;

    return OK;
}

void StagefrightRecorder::clipVideoFrameRate() {
    ALOGV("clipVideoFrameRate: encoder %d", mVideoEncoder);
    if (mFrameRate == -1) {
        mFrameRate = mEncoderProfiles->getCamcorderProfileParamByName(
                "vid.fps", mCameraId, CAMCORDER_QUALITY_LOW);
        ALOGW("Using default video fps %d", mFrameRate);
    }

    int minFrameRate = mEncoderProfiles->getVideoEncoderParamByName(
                        "enc.vid.fps.min", mVideoEncoder);
    int maxFrameRate = mEncoderProfiles->getVideoEncoderParamByName(
                        "enc.vid.fps.max", mVideoEncoder);
    if (mFrameRate < minFrameRate && minFrameRate != -1) {
        ALOGW("Intended video encoding frame rate (%d fps) is too small"
             " and will be set to (%d fps)", mFrameRate, minFrameRate);
        mFrameRate = minFrameRate;
    } else if (mFrameRate > maxFrameRate && maxFrameRate != -1) {
        ALOGW("Intended video encoding frame rate (%d fps) is too large"
             " and will be set to (%d fps)", mFrameRate, maxFrameRate);
        mFrameRate = maxFrameRate;
    }
    MM_LOGD("mFrameRate = %d, minFrameRate = %d, maxFrameRate = %d", mFrameRate, minFrameRate, maxFrameRate);
}

void StagefrightRecorder::clipVideoBitRate() {
    ALOGV("clipVideoBitRate: encoder %d", mVideoEncoder);
    int minBitRate = mEncoderProfiles->getVideoEncoderParamByName(
                        "enc.vid.bps.min", mVideoEncoder);
    int maxBitRate = mEncoderProfiles->getVideoEncoderParamByName(
                        "enc.vid.bps.max", mVideoEncoder);
    if (mVideoBitRate < minBitRate && minBitRate != -1) {
        ALOGW("Intended video encoding bit rate (%d bps) is too small"
             " and will be set to (%d bps)", mVideoBitRate, minBitRate);
        mVideoBitRate = minBitRate;
    } else if (mVideoBitRate > maxBitRate && maxBitRate != -1) {
        ALOGW("Intended video encoding bit rate (%d bps) is too large"
             " and will be set to (%d bps)", mVideoBitRate, maxBitRate);
        mVideoBitRate = maxBitRate;
    }
    MM_LOGD("mVideoBitRate = %d, minBitRate = %d, maxBitRate = %d", mVideoBitRate, minBitRate, maxBitRate);
}

void StagefrightRecorder::clipVideoFrameWidth() {
    ALOGV("clipVideoFrameWidth: encoder %d", mVideoEncoder);
    int minFrameWidth = mEncoderProfiles->getVideoEncoderParamByName(
                        "enc.vid.width.min", mVideoEncoder);
    int maxFrameWidth = mEncoderProfiles->getVideoEncoderParamByName(
                        "enc.vid.width.max", mVideoEncoder);
    if (mVideoWidth < minFrameWidth && minFrameWidth != -1) {
        ALOGW("Intended video encoding frame width (%d) is too small"
             " and will be set to (%d)", mVideoWidth, minFrameWidth);
        mVideoWidth = minFrameWidth;
    } else if (mVideoWidth > maxFrameWidth && maxFrameWidth != -1) {
        ALOGW("Intended video encoding frame width (%d) is too large"
             " and will be set to (%d)", mVideoWidth, maxFrameWidth);
        mVideoWidth = maxFrameWidth;
    }
    MM_LOGD("mVideoWidth = %d, minFrameWidth = %d, maxFrameWidth = %d", mVideoWidth, minFrameWidth, maxFrameWidth);
}

status_t StagefrightRecorder::checkVideoEncoderCapabilities(
        bool *supportsCameraSourceMetaDataMode) {
    /* hardware codecs must support camera source meta data mode */
    Vector<CodecCapabilities> codecs;
    OMXClient client;
    CHECK_EQ(client.connect(), (status_t)OK);
    QueryCodecs(
            client.interface(),
            (mVideoEncoder == VIDEO_ENCODER_H263 ? MEDIA_MIMETYPE_VIDEO_H263 :
             mVideoEncoder == VIDEO_ENCODER_MPEG_4_SP ? MEDIA_MIMETYPE_VIDEO_MPEG4 :
             mVideoEncoder == VIDEO_ENCODER_VP8 ? MEDIA_MIMETYPE_VIDEO_VP8 :
		#if defined(MTK_AOSP_ENHANCEMENT) && defined(MTK_VIDEO_HEVC_SUPPORT)
 			mVideoEncoder == VIDEO_ENCODER_H264 ? MEDIA_MIMETYPE_VIDEO_AVC : 
 			mVideoEncoder == VIDEO_ENCODER_HEVC ? MEDIA_MIMETYPE_VIDEO_HEVC : ""),
 		#else
 			mVideoEncoder == VIDEO_ENCODER_H264 ? MEDIA_MIMETYPE_VIDEO_AVC : ""),
 		#endif
            false /* decoder */, true /* hwCodec */, &codecs);
    *supportsCameraSourceMetaDataMode = codecs.size() > 0;
    ALOGV("encoder %s camera source meta-data mode",
            *supportsCameraSourceMetaDataMode ? "supports" : "DOES NOT SUPPORT");
#ifdef MTK_AOSP_ENHANCEMENT
	checkVideoEncoderCapabilitiesEx();
#endif

    if (!mCaptureTimeLapse) {
        // Dont clip for time lapse capture as encoder will have enough
        // time to encode because of slow capture rate of time lapse.
        clipVideoBitRate();
        clipVideoFrameRate();
        clipVideoFrameWidth();
        clipVideoFrameHeight();
        setDefaultProfileIfNecessary();
    }
    return OK;
}

// Set to use AVC baseline profile if the encoding parameters matches
// CAMCORDER_QUALITY_LOW profile; this is for the sake of MMS service.
void StagefrightRecorder::setDefaultProfileIfNecessary() {
    ALOGV("setDefaultProfileIfNecessary");

    camcorder_quality quality = CAMCORDER_QUALITY_LOW;

    int64_t durationUs   = mEncoderProfiles->getCamcorderProfileParamByName(
                                "duration", mCameraId, quality) * 1000000LL;

    int fileFormat       = mEncoderProfiles->getCamcorderProfileParamByName(
                                "file.format", mCameraId, quality);

    int videoCodec       = mEncoderProfiles->getCamcorderProfileParamByName(
                                "vid.codec", mCameraId, quality);

    int videoBitRate     = mEncoderProfiles->getCamcorderProfileParamByName(
                                "vid.bps", mCameraId, quality);

    int videoFrameRate   = mEncoderProfiles->getCamcorderProfileParamByName(
                                "vid.fps", mCameraId, quality);

    int videoFrameWidth  = mEncoderProfiles->getCamcorderProfileParamByName(
                                "vid.width", mCameraId, quality);

    int videoFrameHeight = mEncoderProfiles->getCamcorderProfileParamByName(
                                "vid.height", mCameraId, quality);

    int audioCodec       = mEncoderProfiles->getCamcorderProfileParamByName(
                                "aud.codec", mCameraId, quality);

    int audioBitRate     = mEncoderProfiles->getCamcorderProfileParamByName(
                                "aud.bps", mCameraId, quality);

    int audioSampleRate  = mEncoderProfiles->getCamcorderProfileParamByName(
                                "aud.hz", mCameraId, quality);

    int audioChannels    = mEncoderProfiles->getCamcorderProfileParamByName(
                                "aud.ch", mCameraId, quality);

    if (durationUs == mMaxFileDurationUs &&
        fileFormat == mOutputFormat &&
        videoCodec == mVideoEncoder &&
        videoBitRate == mVideoBitRate &&
        videoFrameRate == mFrameRate &&
        videoFrameWidth == mVideoWidth &&
        videoFrameHeight == mVideoHeight &&
        audioCodec == mAudioEncoder &&
        audioBitRate == mAudioBitRate &&
        audioSampleRate == mSampleRate &&
        audioChannels == mAudioChannels) {
        if (videoCodec == VIDEO_ENCODER_H264) {
            ALOGI("Force to use AVC baseline profile");
            setParamVideoEncoderProfile(OMX_VIDEO_AVCProfileBaseline);
            // set 0 for invalid levels - this will be rejected by the
            // codec if it cannot handle it during configure
            setParamVideoEncoderLevel(ACodec::getAVCLevelFor(
                    videoFrameWidth, videoFrameHeight, videoFrameRate, videoBitRate));
        }
    }
}

void StagefrightRecorder::setDefaultVideoEncoderIfNecessary() {
    if (mVideoEncoder == VIDEO_ENCODER_DEFAULT) {
        if (mOutputFormat == OUTPUT_FORMAT_WEBM) {
            // default to VP8 for WEBM recording
            mVideoEncoder = VIDEO_ENCODER_VP8;
        } else {
            // pick the default encoder for CAMCORDER_QUALITY_LOW
            int videoCodec = mEncoderProfiles->getCamcorderProfileParamByName(
                    "vid.codec", mCameraId, CAMCORDER_QUALITY_LOW);

            if (videoCodec > VIDEO_ENCODER_DEFAULT &&
                videoCodec < VIDEO_ENCODER_LIST_END) {
                mVideoEncoder = (video_encoder)videoCodec;
            } else {
                // default to H.264 if camcorder profile not available
                mVideoEncoder = VIDEO_ENCODER_H264;
            }
        }
    }
}

status_t StagefrightRecorder::checkAudioEncoderCapabilities() {
    clipAudioBitRate();
    clipAudioSampleRate();
    clipNumberOfAudioChannels();
    return OK;
}

void StagefrightRecorder::clipAudioBitRate() {
    ALOGV("clipAudioBitRate: encoder %d", mAudioEncoder);

    int minAudioBitRate =
            mEncoderProfiles->getAudioEncoderParamByName(
                "enc.aud.bps.min", mAudioEncoder);
    if (minAudioBitRate != -1 && mAudioBitRate < minAudioBitRate) {
        ALOGW("Intended audio encoding bit rate (%d) is too small"
            " and will be set to (%d)", mAudioBitRate, minAudioBitRate);
        mAudioBitRate = minAudioBitRate;
    }

    int maxAudioBitRate =
            mEncoderProfiles->getAudioEncoderParamByName(
                "enc.aud.bps.max", mAudioEncoder);
    if (maxAudioBitRate != -1 && mAudioBitRate > maxAudioBitRate) {
        ALOGW("Intended audio encoding bit rate (%d) is too large"
            " and will be set to (%d)", mAudioBitRate, maxAudioBitRate);
        mAudioBitRate = maxAudioBitRate;
    }
}

void StagefrightRecorder::clipAudioSampleRate() {
    ALOGV("clipAudioSampleRate: encoder %d", mAudioEncoder);

    int minSampleRate =
            mEncoderProfiles->getAudioEncoderParamByName(
                "enc.aud.hz.min", mAudioEncoder);
    if (minSampleRate != -1 && mSampleRate < minSampleRate) {
        ALOGW("Intended audio sample rate (%d) is too small"
            " and will be set to (%d)", mSampleRate, minSampleRate);
        mSampleRate = minSampleRate;
    }

    int maxSampleRate =
            mEncoderProfiles->getAudioEncoderParamByName(
                "enc.aud.hz.max", mAudioEncoder);
    if (maxSampleRate != -1 && mSampleRate > maxSampleRate) {
        ALOGW("Intended audio sample rate (%d) is too large"
            " and will be set to (%d)", mSampleRate, maxSampleRate);
        mSampleRate = maxSampleRate;
    }
}

void StagefrightRecorder::clipNumberOfAudioChannels() {
    ALOGV("clipNumberOfAudioChannels: encoder %d", mAudioEncoder);

    int minChannels =
            mEncoderProfiles->getAudioEncoderParamByName(
                "enc.aud.ch.min", mAudioEncoder);
    if (minChannels != -1 && mAudioChannels < minChannels) {
        ALOGW("Intended number of audio channels (%d) is too small"
            " and will be set to (%d)", mAudioChannels, minChannels);
        mAudioChannels = minChannels;
    }

    int maxChannels =
            mEncoderProfiles->getAudioEncoderParamByName(
                "enc.aud.ch.max", mAudioEncoder);
    if (maxChannels != -1 && mAudioChannels > maxChannels) {
        ALOGW("Intended number of audio channels (%d) is too large"
            " and will be set to (%d)", mAudioChannels, maxChannels);
        mAudioChannels = maxChannels;
    }
}

void StagefrightRecorder::clipVideoFrameHeight() {
    ALOGV("clipVideoFrameHeight: encoder %d", mVideoEncoder);
    int minFrameHeight = mEncoderProfiles->getVideoEncoderParamByName(
                        "enc.vid.height.min", mVideoEncoder);
    int maxFrameHeight = mEncoderProfiles->getVideoEncoderParamByName(
                        "enc.vid.height.max", mVideoEncoder);
    if (minFrameHeight != -1 && mVideoHeight < minFrameHeight) {
        ALOGW("Intended video encoding frame height (%d) is too small"
             " and will be set to (%d)", mVideoHeight, minFrameHeight);
        mVideoHeight = minFrameHeight;
    } else if (maxFrameHeight != -1 && mVideoHeight > maxFrameHeight) {
        ALOGW("Intended video encoding frame height (%d) is too large"
             " and will be set to (%d)", mVideoHeight, maxFrameHeight);
        mVideoHeight = maxFrameHeight;
    }
    MM_LOGD("mVideoHeight = %d, minFrameHeight = %d, maxFrameHeight = %d", mVideoHeight, minFrameHeight, maxFrameHeight);
}

// Set up the appropriate MediaSource depending on the chosen option
status_t StagefrightRecorder::setupMediaSource(
                      sp<MediaSource> *mediaSource) {
	MM_LOGD("mVideoSource=%d", mVideoSource);
    if (mVideoSource == VIDEO_SOURCE_DEFAULT
            || mVideoSource == VIDEO_SOURCE_CAMERA) {
        sp<CameraSource> cameraSource;
        status_t err = setupCameraSource(&cameraSource);
        if (err != OK) {
            return err;
        }
        *mediaSource = cameraSource;
    } else if (mVideoSource == VIDEO_SOURCE_SURFACE) {
        *mediaSource = NULL;
    } else {
        return INVALID_OPERATION;
    }
    return OK;
}

status_t StagefrightRecorder::setupCameraSource(
        sp<CameraSource> *cameraSource) {
	MM_LOGD("");
	
	#if defined(MTK_AOSP_ENHANCEMENT) && defined(MTK_SLOW_MOTION_VIDEO_SUPPORT)&& defined(MTK_VIDEO_HEVC_SUPPORT)
		char avcorhevc[PROPERTY_VALUE_MAX];
		int64_t avcorhevcvalue;//default 0 avc, if !0 hevc
		property_get("vr.slowmotion.avcorhevc", avcorhevc, "0");
		avcorhevcvalue = atol(avcorhevc);
		ALOGD("vr.slowmotion.avcorhevc=%" PRId64 "", avcorhevcvalue);

		if(avcorhevcvalue&&mVideoWidth==1280&&mVideoHeight==720&&mFrameRate==120&&mVideoEncoder==VIDEO_ENCODER_H264){
			mVideoHeight = 736;
			mVideoEncoder = VIDEO_ENCODER_HEVC;//(android::video_encoder)102;
		}
		ALOGV("mVideoWidth=%d, mVideoHeight=%d, mVideoEncoder=%d"
	             , mVideoWidth, mVideoHeight, mVideoEncoder);
	#endif

    status_t err = OK;
    bool encoderSupportsCameraSourceMetaDataMode;
    if ((err = checkVideoEncoderCapabilities(
                &encoderSupportsCameraSourceMetaDataMode)) != OK) {
        return err;
    }
    Size videoSize;
    videoSize.width = mVideoWidth;
    videoSize.height = mVideoHeight;
	
#ifdef MTK_AOSP_ENHANCEMENT//for CTS vr test with parameter of 1920*1080 which is not 16 align
	checkVideoEncoderBufferLimit(videoSize.width, videoSize.height);
#endif
    if (mCaptureTimeLapse) {
        if (mTimeBetweenTimeLapseFrameCaptureUs < 0) {
            ALOGE("Invalid mTimeBetweenTimeLapseFrameCaptureUs value: %lld",
                mTimeBetweenTimeLapseFrameCaptureUs);
            return BAD_VALUE;
        }

        mCameraSourceTimeLapse = CameraSourceTimeLapse::CreateFromCamera(
                mCamera, mCameraProxy, mCameraId, mClientName, mClientUid,
                videoSize, mFrameRate, mPreviewSurface,
                mTimeBetweenTimeLapseFrameCaptureUs,
                encoderSupportsCameraSourceMetaDataMode);
        *cameraSource = mCameraSourceTimeLapse;
    } else {
        *cameraSource = CameraSource::CreateFromCamera(
                mCamera, mCameraProxy, mCameraId, mClientName, mClientUid,
                videoSize, mFrameRate,
                mPreviewSurface, encoderSupportsCameraSourceMetaDataMode);
    }
    mCamera.clear();
    mCameraProxy.clear();
    if (*cameraSource == NULL) {
        return UNKNOWN_ERROR;
    }

    if ((*cameraSource)->initCheck() != OK) {
        (*cameraSource).clear();
        *cameraSource = NULL;
        return NO_INIT;
    }

    // When frame rate is not set, the actual frame rate will be set to
    // the current frame rate being used.
    if (mFrameRate == -1) {
        int32_t frameRate = 0;
        CHECK ((*cameraSource)->getFormat()->findInt32(
                    kKeyFrameRate, &frameRate));
        ALOGI("Frame rate is not explicitly set. Use the current frame "
             "rate (%d fps)", frameRate);
        mFrameRate = frameRate;
    }

    CHECK(mFrameRate != -1);

    mIsMetaDataStoredInVideoBuffers =
        (*cameraSource)->isMetaDataStoredInVideoBuffers();

    return OK;
}

status_t StagefrightRecorder::setupVideoEncoder(
        sp<MediaSource> cameraSource,
        sp<MediaSource> *source) {
    source->clear();

    sp<AMessage> format = new AMessage();

    switch (mVideoEncoder) {
        case VIDEO_ENCODER_H263:
            format->setString("mime", MEDIA_MIMETYPE_VIDEO_H263);
            break;

        case VIDEO_ENCODER_MPEG_4_SP:
            format->setString("mime", MEDIA_MIMETYPE_VIDEO_MPEG4);
            break;

        case VIDEO_ENCODER_H264:
            format->setString("mime", MEDIA_MIMETYPE_VIDEO_AVC);
            break;
            
        case VIDEO_ENCODER_VP8:
            format->setString("mime", MEDIA_MIMETYPE_VIDEO_VP8);
            break;

#if defined(MTK_AOSP_ENHANCEMENT) && defined(MTK_VIDEO_HEVC_SUPPORT)
        case VIDEO_ENCODER_HEVC:
        		format->setString("mime", MEDIA_MIMETYPE_VIDEO_HEVC);
            break;
#endif

        default:
            CHECK(!"Should not be here, unsupported video encoding.");
            break;
    }
    
    if (cameraSource != NULL) {
        sp<MetaData> meta = cameraSource->getFormat();

        int32_t width, height, stride, sliceHeight, colorFormat;
        CHECK(meta->findInt32(kKeyWidth, &width));
        CHECK(meta->findInt32(kKeyHeight, &height));
        CHECK(meta->findInt32(kKeyStride, &stride));
        CHECK(meta->findInt32(kKeySliceHeight, &sliceHeight));
        CHECK(meta->findInt32(kKeyColorFormat, &colorFormat));

#ifdef MTK_AOSP_ENHANCEMENT
         //Need Refine: discuss with codec owner,delete the workaround for codec
	//tell codec the real width and height ap want to record
        ALOGI("( width = %d,height =%d ) from cameraSource",width,height);
        ALOGI("( mVideoWidth = %d,mVideoWidth =%d ) from AP",mVideoWidth,mVideoHeight);
				width  = mVideoWidth;
        height = mVideoHeight;
#endif
        format->setInt32("width", width);
        format->setInt32("height", height);
        format->setInt32("stride", stride);
        format->setInt32("slice-height", sliceHeight);
        format->setInt32("color-format", colorFormat);
        
        MM_LOGD("cameraSource != NULL,Real Camera Recording:width=%d, height=%d,\n stride=%d,slice-height=%d,\n color-format=%d,",\
		     width, height,stride, sliceHeight, colorFormat);
    } else {
        format->setInt32("width", mVideoWidth);
        format->setInt32("height", mVideoHeight);
        format->setInt32("stride", mVideoWidth);
        format->setInt32("slice-height", mVideoWidth);
        format->setInt32("color-format", OMX_COLOR_FormatAndroidOpaque);
        
        // set up time lapse/slow motion for surface source
        if (mCaptureTimeLapse) {
            if (mTimeBetweenTimeLapseFrameCaptureUs <= 0) {
                ALOGE("Invalid mTimeBetweenTimeLapseFrameCaptureUs value: %lld",
                    mTimeBetweenTimeLapseFrameCaptureUs);
                return BAD_VALUE;
            }
            format->setInt64("time-lapse",
                    mTimeBetweenTimeLapseFrameCaptureUs);
        }
        MM_LOGD("cameraSource == NULL:width=%d, height=%d,\n stride=%d,slice-height=%d,\n color-format=%d,",\
		     mVideoWidth, mVideoHeight,mVideoWidth, mVideoWidth, OMX_COLOR_FormatAndroidOpaque);
    }

    format->setInt32("bitrate", mVideoBitRate);
    format->setInt32("frame-rate", mFrameRate);
    format->setInt32("i-frame-interval", mIFramesIntervalSec);

	MM_LOGD("bitrate=%d, frame-rate=%d,\n i-frame-interval=%d,",\
		     mVideoBitRate, mFrameRate, mIFramesIntervalSec);
	MM_LOGD("time-scale=%d, profile=%d, level=%d",\
		     mVideoTimeScale, mVideoEncoderProfile, mVideoEncoderLevel);

    if (mVideoTimeScale > 0) {
        format->setInt32("time-scale", mVideoTimeScale);
    }
    if (mVideoEncoderProfile != -1) {
        format->setInt32("profile", mVideoEncoderProfile);
    }
    if (mVideoEncoderLevel != -1) {
        format->setInt32("level", mVideoEncoderLevel);
    }

    uint32_t flags = 0;
    if (mIsMetaDataStoredInVideoBuffers) {
        flags |= MediaCodecSource::FLAG_USE_METADATA_INPUT;
    }

    if (cameraSource == NULL) {
        flags |= MediaCodecSource::FLAG_USE_SURFACE_INPUT;
    }

#ifdef MTK_AOSP_ENHANCEMENT
	if (mSlowMotionSpeedValue > 0) {
		ALOGD("enable non ref p for solw motion");
        format->setInt32("enc-nonRefP", true);
	}
	
	if (mLivePhotoTagValue > 0) {
		//inform live phot mode to SMI through encoder
		ALOGD("set scenario kLivePhotoEffect to encoder for livephoto");
		format->setInt32("livephoto",1);
	}
#endif

sp<MediaCodecSource> encoder =
            MediaCodecSource::Create(mLooper, format, cameraSource, flags);
	
    if (encoder == NULL) {
        ALOGE("Failed to create video encoder");
        // When the encoder fails to be created, we need
        // release the camera source due to the camera's lock
        // and unlock mechanism.
        if (cameraSource != NULL) {
            cameraSource->stop();
        }
        return UNKNOWN_ERROR;
    }

    if (cameraSource == NULL) {
        mGraphicBufferProducer = encoder->getGraphicBufferProducer();
    }

    *source = encoder;
#ifdef MTK_AOSP_ENHANCEMENT
	mVideoEncSource = encoder;

	ALOGD("setupVideoEncoder done --");
#endif 
    return OK;
}

status_t StagefrightRecorder::setupAudioEncoder(const sp<MediaWriter>& writer) {
	MM_LOGD("+ mAudioEncoder=%d",mAudioEncoder);
    status_t status = BAD_VALUE;
    if (OK != (status = checkAudioEncoderCapabilities())) {
        return status;
    }

    switch(mAudioEncoder) {
        case AUDIO_ENCODER_AMR_NB:
        case AUDIO_ENCODER_AMR_WB:
        case AUDIO_ENCODER_AAC:
        case AUDIO_ENCODER_HE_AAC:
        case AUDIO_ENCODER_AAC_ELD:
            break;

        default:
            ALOGE("Unsupported audio encoder: %d", mAudioEncoder);
            return UNKNOWN_ERROR;
    }

    sp<MediaSource> audioEncoder = createAudioSource();
    if (audioEncoder == NULL) {
        return UNKNOWN_ERROR;
    }

    writer->addSource(audioEncoder);
	MM_LOGD("-");
    return OK;
}


status_t StagefrightRecorder::setupMPEG4orWEBMRecording() {

    MM_LOGD("+");

    mWriter.clear();
    mTotalBitRate = 0;

    status_t err = OK;
    sp<MediaWriter> writer;
    if (mOutputFormat == OUTPUT_FORMAT_WEBM) {
        writer = new WebmWriter(mOutputFd);
    } else {
        writer = new MPEG4Writer(mOutputFd);
    }

    if (mVideoSource < VIDEO_SOURCE_LIST_END) {
        setDefaultVideoEncoderIfNecessary();

        sp<MediaSource> mediaSource;
        err = setupMediaSource(&mediaSource);
        if (err != OK) {
			MM_LOGE("setupMediaSource Fail err=%d",err);
            return err;
        }
		

        sp<MediaSource> encoder;
		
#if defined(MTK_AOSP_ENHANCEMENT) && defined(MTK_SLOW_MOTION_VIDEO_SUPPORT)
		int32_t  colorFormat = 0;// OMX_COLOR_FormatUnused;
		if(mediaSource != NULL){
			sp<MetaData> meta = mediaSource->getFormat();			
			CHECK(meta->findInt32(kKeyColorFormat, &colorFormat));
		}
		char path[PROPERTY_VALUE_MAX];
		int64_t pathvalue;//default 0 path2, if !0 path1(MtkBSSource)
		property_get("vr.slowmotion.dl.path", path, "0");
		pathvalue = atol(path);
		ALOGD("vr.slowmotion.dl.path=%" PRId64 "", pathvalue);
		
		if(pathvalue&&colorFormat == OMX_MTK_COLOR_FormatBitStream){
			ALOGI("Create MtkBSSource for hw direct link");		
			setupMtkBSSource(mediaSource,&encoder);
		}
		else{
			err = setupVideoEncoder(mediaSource, &encoder);
			if (err != OK) {
				MM_LOGE("setupVideoEncoder Fail err=%d",err);
	            return err;
        	}

		}

#else 
        err = setupVideoEncoder(mediaSource, &encoder);
        if (err != OK) {
			MM_LOGE("setupVideoEncoder Fail err=%d",err);
            return err;
        }
#endif

#ifdef MTK_AOSP_ENHANCEMENT
	if(mEnableLivePhoto) {
	 	mpLivePhotoSource = new LivePhotoSource(encoder);
	    encoder = mpLivePhotoSource;
	}
#endif
		writer->addSource(encoder);
        mTotalBitRate += mVideoBitRate;
    }

    if (mOutputFormat != OUTPUT_FORMAT_WEBM) {
        // Audio source is added at the end if it exists.
        // This help make sure that the "recoding" sound is suppressed for
        // camcorder applications in the recorded files.
        // TODO Audio source is currently unsupported for webm output; vorbis encoder needed.
        if (!mCaptureTimeLapse && (mAudioSource != AUDIO_SOURCE_CNT)) {
            err = setupAudioEncoder(writer);
            if (err != OK) return err;
            mTotalBitRate += mAudioBitRate;
        }

        if (mInterleaveDurationUs > 0) {
            reinterpret_cast<MPEG4Writer *>(writer.get())->
                setInterleaveDuration(mInterleaveDurationUs);
        }
        if (mLongitudex10000 > -3600000 && mLatitudex10000 > -3600000) {
            reinterpret_cast<MPEG4Writer *>(writer.get())->
                setGeoData(mLatitudex10000, mLongitudex10000);
        }
    }
    if (mMaxFileDurationUs != 0) {
        writer->setMaxFileDuration(mMaxFileDurationUs);
    }
    if (mMaxFileSizeBytes != 0) {
        writer->setMaxFileSize(mMaxFileSizeBytes);
    }
    if (mVideoSource == VIDEO_SOURCE_DEFAULT
            || mVideoSource == VIDEO_SOURCE_CAMERA) {
        mStartTimeOffsetMs = mEncoderProfiles->getStartTimeOffsetMs(mCameraId);
    } else if (mVideoSource == VIDEO_SOURCE_SURFACE) {
        // surface source doesn't need large initial delay
        mStartTimeOffsetMs = 200;
    }
    if (mStartTimeOffsetMs > 0) {
        writer->setStartTimeOffsetMs(mStartTimeOffsetMs);
    }

    writer->setListener(mListener);
    mWriter = writer;

    MM_LOGD("-");

    return OK;
}

void StagefrightRecorder::setupMPEG4orWEBMMetaData(sp<MetaData> *meta) {
    int64_t startTimeUs = systemTime() / 1000;
    (*meta)->setInt64(kKeyTime, startTimeUs);
    (*meta)->setInt32(kKeyFileType, mOutputFormat);
    (*meta)->setInt32(kKeyBitRate, mTotalBitRate);
    if (mMovieTimeScale > 0) {
        (*meta)->setInt32(kKeyTimeScale, mMovieTimeScale);
    }
    if (mOutputFormat != OUTPUT_FORMAT_WEBM) {
        (*meta)->setInt32(kKey64BitFileOffset, mUse64BitFileOffset);
        if (mTrackEveryTimeDurationUs > 0) {
            (*meta)->setInt64(kKeyTrackTimeStatus, mTrackEveryTimeDurationUs);
        }
        if (mRotationDegrees != 0) {
            (*meta)->setInt32(kKeyRotation, mRotationDegrees);
        }
    }
#ifdef MTK_AOSP_ENHANCEMENT
	setupMPEG4MetaDataEx(meta);
#endif
}

status_t StagefrightRecorder::pause() {
    ALOGV("pause");
    if (mWriter == NULL) {
        return UNKNOWN_ERROR;
    }
#ifdef MTK_AOSP_ENHANCEMENT
	if (mCaptureTimeLapse && mCameraSourceTimeLapse != NULL) {//force pick for quick return in pause
		mCameraSourceTimeLapse->setForcePick(true, 1);
	}
#endif
    mWriter->pause();

#ifdef MTK_AOSP_ENHANCEMENT
	if (mCaptureTimeLapse && mCameraSourceTimeLapse != NULL) {//cancel force pick
		mCameraSourceTimeLapse->setForcePick(false, 0);
	}

	//For save power
	//pause Video encoding through pause puller of MediaCodecSource
	//TODO: pause audio encoding
	if(mVideoEncSource != NULL){
		mVideoEncSource->pause();
	}
	
	mPaused = true;
	return OK;//codecs still run.Keep the recorder state started
#endif

	
    if (mStarted) {
        mStarted = false;

        uint32_t params = 0;
        if (mAudioSource != AUDIO_SOURCE_CNT) {
            params |= IMediaPlayerService::kBatteryDataTrackAudio;
        }
        if (mVideoSource != VIDEO_SOURCE_LIST_END) {
            params |= IMediaPlayerService::kBatteryDataTrackVideo;
        }

        addBatteryData(params);
    }


    return OK;
}

status_t StagefrightRecorder::stop() {
    ALOGV("stop");
    status_t err = OK;

    if (mCaptureTimeLapse && mCameraSourceTimeLapse != NULL) {
        mCameraSourceTimeLapse->startQuickReadReturns();
        mCameraSourceTimeLapse = NULL;
    }
#ifdef MTK_AOSP_ENHANCEMENT

	if( mpLivePhotoSource != NULL) {
		mpLivePhotoSource->stop();
		mpLivePhotoSource.clear();
	}
#endif
    if (mWriter != NULL) {
        err = mWriter->stop();
        mWriter.clear();
#ifdef MTK_AOSP_ENHANCEMENT
		//2012/04/12 for QQ-HD sound recording bug
        if (mAudioSourceNode != NULL)
        {
            mAudioSourceNode.clear();
        }
#endif
    }

    mGraphicBufferProducer.clear();

    if (mOutputFd >= 0) {
        ::close(mOutputFd);
        mOutputFd = -1;
    }

    if (mStarted) {
        mStarted = false;

        uint32_t params = 0;
        if (mAudioSource != AUDIO_SOURCE_CNT) {
            params |= IMediaPlayerService::kBatteryDataTrackAudio;
        }
        if (mVideoSource != VIDEO_SOURCE_LIST_END) {
            params |= IMediaPlayerService::kBatteryDataTrackVideo;
        }

        addBatteryData(params);
    }
#ifdef MTK_AOSP_ENHANCEMENT
	ALOGD("stop done");
#endif
    return err;
}

status_t StagefrightRecorder::close() {
    ALOGV("close");
    stop();

    return OK;
}

status_t StagefrightRecorder::reset() {
    ALOGV("reset");
    stop();

    // No audio or video source by default
    mAudioSource = AUDIO_SOURCE_CNT;
    mVideoSource = VIDEO_SOURCE_LIST_END;

    // Default parameters
    mOutputFormat  = OUTPUT_FORMAT_THREE_GPP;
    mAudioEncoder  = AUDIO_ENCODER_AMR_NB;

#ifdef MTK_AOSP_ENHANCEMENT    //In order to pass CTS test case for preview size: 320 x 240
    mVideoEncoder  = VIDEO_ENCODER_MPEG_4_SP;
#else
    mVideoEncoder  = VIDEO_ENCODER_DEFAULT;
#endif  //#ifdef MTK_AOSP_ENHANCEMENT

    mVideoWidth    = 176;
    mVideoHeight   = 144;
    mFrameRate     = -1;
    mVideoBitRate  = 192000;
    mSampleRate    = 8000;
    mAudioChannels = 1;
    mAudioBitRate  = 12200;
    mInterleaveDurationUs = 0;
    mIFramesIntervalSec = 1;
    mAudioSourceNode = 0;
    mUse64BitFileOffset = false;
    mMovieTimeScale  = -1;
    mAudioTimeScale  = -1;
    mVideoTimeScale  = -1;
    mCameraId        = 0;
    mStartTimeOffsetMs = -1;
    mVideoEncoderProfile = -1;
    mVideoEncoderLevel   = -1;
    mMaxFileDurationUs = 0;
    mMaxFileSizeBytes = 0;
    mTrackEveryTimeDurationUs = 0;
    mCaptureTimeLapse = false;
    mTimeBetweenTimeLapseFrameCaptureUs = -1;
    mCameraSourceTimeLapse = NULL;
    mIsMetaDataStoredInVideoBuffers = false;
    mEncoderProfiles = MediaProfiles::getInstance();
    mRotationDegrees = 0;
    mLatitudex10000 = -3600000;
    mLongitudex10000 = -3600000;
    mTotalBitRate = 0;

    mOutputFd = -1;

#ifdef MTK_AOSP_ENHANCEMENT
	resetEx();
#endif // #ifdef MTK_AOSP_ENHANCEMENT
    return OK;
}

status_t StagefrightRecorder::getMaxAmplitude(int *max) {
    ALOGV("getMaxAmplitude");

    if (max == NULL) {
        ALOGE("Null pointer argument");
        return BAD_VALUE;
    }

    if (mAudioSourceNode != 0) {
        *max = mAudioSourceNode->getMaxAmplitude();
    } else {
        *max = 0;
    }

    return OK;
}

status_t StagefrightRecorder::dump(
        int fd, const Vector<String16>& args) const {
    ALOGV("dump");
    const size_t SIZE = 256;
    char buffer[SIZE];
    String8 result;
    if (mWriter != 0) {
        mWriter->dump(fd, args);
    } else {
        snprintf(buffer, SIZE, "   No file writer\n");
        result.append(buffer);
    }
    snprintf(buffer, SIZE, "   Recorder: %p\n", this);
    snprintf(buffer, SIZE, "   Output file (fd %d):\n", mOutputFd);
    result.append(buffer);
    snprintf(buffer, SIZE, "     File format: %d\n", mOutputFormat);
    result.append(buffer);
    snprintf(buffer, SIZE, "     Max file size (bytes): %" PRId64 "\n", mMaxFileSizeBytes);
    result.append(buffer);
    snprintf(buffer, SIZE, "     Max file duration (us): %" PRId64 "\n", mMaxFileDurationUs);
    result.append(buffer);
    snprintf(buffer, SIZE, "     File offset length (bits): %d\n", mUse64BitFileOffset? 64: 32);
    result.append(buffer);
    snprintf(buffer, SIZE, "     Interleave duration (us): %d\n", mInterleaveDurationUs);
    result.append(buffer);
    snprintf(buffer, SIZE, "     Progress notification: %" PRId64 " us\n", mTrackEveryTimeDurationUs);
    result.append(buffer);
    snprintf(buffer, SIZE, "   Audio\n");
    result.append(buffer);
    snprintf(buffer, SIZE, "     Source: %d\n", mAudioSource);
    result.append(buffer);
    snprintf(buffer, SIZE, "     Encoder: %d\n", mAudioEncoder);
    result.append(buffer);
    snprintf(buffer, SIZE, "     Bit rate (bps): %d\n", mAudioBitRate);
    result.append(buffer);
    snprintf(buffer, SIZE, "     Sampling rate (hz): %d\n", mSampleRate);
    result.append(buffer);
    snprintf(buffer, SIZE, "     Number of channels: %d\n", mAudioChannels);
    result.append(buffer);
    snprintf(buffer, SIZE, "     Max amplitude: %d\n", mAudioSourceNode == 0? 0: mAudioSourceNode->getMaxAmplitude());
    result.append(buffer);
    snprintf(buffer, SIZE, "   Video\n");
    result.append(buffer);
    snprintf(buffer, SIZE, "     Source: %d\n", mVideoSource);
    result.append(buffer);
    snprintf(buffer, SIZE, "     Camera Id: %d\n", mCameraId);
    result.append(buffer);
    snprintf(buffer, SIZE, "     Start time offset (ms): %d\n", mStartTimeOffsetMs);
    result.append(buffer);
    snprintf(buffer, SIZE, "     Encoder: %d\n", mVideoEncoder);
    result.append(buffer);
    snprintf(buffer, SIZE, "     Encoder profile: %d\n", mVideoEncoderProfile);
    result.append(buffer);
    snprintf(buffer, SIZE, "     Encoder level: %d\n", mVideoEncoderLevel);
    result.append(buffer);
    snprintf(buffer, SIZE, "     I frames interval (s): %d\n", mIFramesIntervalSec);
    result.append(buffer);
    snprintf(buffer, SIZE, "     Frame size (pixels): %dx%d\n", mVideoWidth, mVideoHeight);
    result.append(buffer);
    snprintf(buffer, SIZE, "     Frame rate (fps): %d\n", mFrameRate);
    result.append(buffer);
    snprintf(buffer, SIZE, "     Bit rate (bps): %d\n", mVideoBitRate);
    result.append(buffer);
    ::write(fd, result.string(), result.size());
    return OK;
}

#ifdef MTK_AOSP_ENHANCEMENT

void StagefrightRecorder::preInit() {
	mpLivePhotoSource = NULL;
}

status_t StagefrightRecorder::setParameterEx(const String8 &key, const String8 &value) {
	//MTK80721	2012-11-26 Preprocess Effect+
	if (key == "audio-param-preprocesseffect") 
	{
		ALOGD(" key = %s,value=%s",key.string(),value.string());
		mParams += "PREPROCESS_EFFECT=";
		mParams += value;
		mParams += ";";
		return OK;
	}
#ifdef MTK_AUDIO_HD_REC_SUPPORT
	//Add by MTK80721 HDRecord 2011-12-23
	else if (key == "audio-param-hdrecvoicemode")
	{
		mParams += "HDREC_SET_VOICE_MODE=";
			mParams += value;
			mParams += ";";
		return OK;
	}
	else if (key == "audio-param-hdrecvideomode")
	{
		mParams += "HDREC_SET_VIDEO_MODE=";
			mParams += value;
			mParams += ";";
		return OK;
	}
#endif
	else if (key == "rtp-target-addresses") {
		ALOGD(" key =  rtp-target-addresses");
		if (mOutputFormat != OUTPUT_FORMAT_RTP_AVP) {
			ALOGE("Bad parameter!!! %s for non-rtp writer %d", value.string(), mOutputFormat);
			return BAD_VALUE;
		}
		ALOGD("set rtp-target-addresses = %s success!!!", value.string());

		mRTPTarget.setTo(value.string());
		return OK;
	} else if(key == "media-param-pause") {
		int64_t isPause = 0;
		ALOGD(" key =  media-param-pause \n");
		if (safe_strtoi64(value.string(), &isPause) && (1 == isPause)) {
			if (pause() != OK) {
				ALOGD("Pause return error");
				return UNKNOWN_ERROR;
			}
			else
				return OK;
		}
		else
		{
			ALOGE("Bad parameter!!! isPause = %lld", isPause);
			return BAD_VALUE;
		} 
	} else if (key == "media-param-tag-artist") {
		ALOGD(" key = media-param-tag-artist");
		ALOGD(" set media-param-tag-artist = %s success!!!", value.string());

		mArtistTag.setTo(value.string());
		return OK;
	} else if (key == "media-param-tag-album") {
		ALOGD(" key = media-param-tag-album");
		ALOGD(" set media-param-tag-album = %s success!!!", value.string());

		mAlbumTag.setTo(value.string());
		return OK;
	} else if (key == "media-param-livephoto") {
		ALOGD(" key = media-param-livephoto");
		int32_t isLivePhoto = 0;
		if (safe_strtoi32(value.string(), &isLivePhoto) && (1 == isLivePhoto)) {
			mEnableLivePhoto = true;
			return OK;
		}
		else
		{
			ALOGE("Bad parameter!!! isLivePhoto = %d", isLivePhoto);
			return BAD_VALUE;
		} 
	}
	else if (key == "media-param-tag-livephoto") {
			ALOGD("media-param-tag-livephoto");
		if (safe_strtoi32(value.string(), &mLivePhotoTagValue)) {
			ALOGD(" set media-param-tag-livephoto = %d success!!!", mLivePhotoTagValue);
			return OK;
		}
		else
		{
			ALOGE(" set media-param-tag-livephoto failed!!!");			
			return BAD_VALUE;
		}
	}
	else if (key == "media-param-capture-livephoto") {
		ALOGD("media-param-capture-livephoto");
		int32_t isCapture = 0;
		if (safe_strtoi32(value.string(), &isCapture) && (1 == isCapture)) {
			if (mpLivePhotoSource == NULL){
				ALOGE("mpLivePhotoSource is NULL");
				return UNKNOWN_ERROR;
			}
			return mpLivePhotoSource->startLivePhoto();
		}
		else
		{
			ALOGE("Bad parameter!!! isCapture = %d", isCapture);
			return BAD_VALUE;
		}
#ifdef MTK_SLOW_MOTION_VIDEO_SUPPORT		 
	} else if (key == "media-param-slowmotion") {
		ALOGD("media-param-slowmotion");
		if (safe_strtoi32(value.string(), &mSlowMotionSpeedValue)) {
			ALOGD(" set media-param-slowmotion = %d success!!!", mSlowMotionSpeedValue);
			return OK;
		}
		else
		{
			ALOGE(" set media-param-slowmotion failed!!!"); 		
			return BAD_VALUE;
		}
#endif 	
    } else {
	    ALOGE("setParameterEx: failed to find key %s", key.string());
    }

    return BAD_VALUE;
}

status_t StagefrightRecorder::resume(){
	ALOGD("resume +");
	if (mCaptureTimeLapse && mCameraSourceTimeLapse != NULL) {//force pick for quick return in resume
		mCameraSourceTimeLapse->setForcePick(true, 2);
	}
	if(mVideoEncSource != NULL){
		ALOGD("resume encoder +");
		//resume video encoding
		mVideoEncSource->start();
		ALOGD("resume encoder -");
	}	
	//should check mWriter.get() first
	ALOGD("resume writer +");
	mWriter->start();
	ALOGD("resume writer -");

	if (mCaptureTimeLapse && mCameraSourceTimeLapse != NULL) {//cancel force pick
		mCameraSourceTimeLapse->setForcePick(false, 0);
	}	
	mPaused = false;
	ALOGD("resume -");
	return OK;
}

#ifdef HAVE_ADPCMENCODE_FEATURE
status_t StagefrightRecorder::startADPCMRecording()
{
	ALOGD("startADPCMRecording +");
	CHECK(mOutputFormat == OUTPUT_FORMAT_WAV);

	if(AUDIO_ENCODER_MS_ADPCM != mAudioEncoder && AUDIO_ENCODER_DVI_IMA_ADPCM != mAudioEncoder)
	{
		SXLOGE("mAudioEncoder is not supported !!!");
		return BAD_VALUE;
	}

	if(mSampleRate < 8000 || mSampleRate > 48000)
	{
		ALOGE("mSampleRate is not supported !!!");
		return BAD_VALUE;
	}
	if(mAudioChannels < 1 || mAudioChannels > 2)
	{
		ALOGE("mAudioChannels is not supported !!!");
		return BAD_VALUE;
	}	
	if (mAudioSource >= AUDIO_SOURCE_CNT)
	{
        ALOGE("Invalid audio source: %d", mAudioSource);
        return BAD_VALUE;
    }

	sp<MediaSource> audioEncoder = createAudioSource();
	if(audioEncoder == NULL)
	{
		ALOGE("create Audio Source Failed !!!");
		return UNKNOWN_ERROR;
	}

	mWriter = new ADPCMWriter(dup(mOutputFd));
    mWriter->addSource(audioEncoder);

    if (mMaxFileDurationUs != 0) {
        mWriter->setMaxFileDuration(mMaxFileDurationUs);
    }
    if (mMaxFileSizeBytes != 0) {
        mWriter->setMaxFileSize(mMaxFileSizeBytes);
    }
    mWriter->setListener(mListener);
    status_t err = mWriter->start();
	
    ALOGD("startADPCMRecording -");
    return err;
}
#endif

status_t StagefrightRecorder::startPCMRecording() {
	CHECK(mOutputFormat == OUTPUT_FORMAT_WAV);
   
    if (mOutputFormat == OUTPUT_FORMAT_WAV) {
        if (mAudioEncoder != AUDIO_ENCODER_PCM) {
            ALOGE("Invalid encoder %d used for PCM recording",mAudioEncoder);
            return BAD_VALUE;
        }
        //AUDIO_SOURCE_MIC 8K,16K;AUDIO_SOURCE_I2S:8K~48K
        if (mSampleRate < 8000 || mSampleRate > 48000) {
            ALOGE("Invalid sampling rate %d used for PCM recording",mSampleRate);
            return BAD_VALUE;
        }
    } 
    if (mAudioChannels != 1 && mAudioChannels != 2) {
        ALOGE("Invalid number of audio channels %d used for PCM recording",
                mAudioChannels);
        return BAD_VALUE;
    }

    if (mAudioSource >= AUDIO_SOURCE_CNT) {
        ALOGE("Invalid audio source: %d", mAudioSource);
        return BAD_VALUE;
    }
    ALOGD("StagefrightRecorder::startPCMRecording");

    mAudioSourceNode = new AudioSource(mAudioSource,mSampleRate,mAudioChannels);
	sp<MediaSource> pcmSource = mAudioSourceNode;

    if (pcmSource == NULL) {
        return UNKNOWN_ERROR;
    }

    mWriter = new PCMWriter(dup(mOutputFd));
    mWriter->addSource(pcmSource);
    if (mMaxFileDurationUs != 0) {
        mWriter->setMaxFileDuration(mMaxFileDurationUs);
    }
    if (mMaxFileSizeBytes != 0) {
        mWriter->setMaxFileSize(mMaxFileSizeBytes);
    }
    mWriter->setListener(mListener);
    status_t err = mWriter->start();
    return err;
}

status_t StagefrightRecorder::startOGGRecording()
{
    CHECK(mOutputFormat == OUTPUT_FORMAT_OGG);

    if (mAudioEncoder != AUDIO_ENCODER_VORBIS) 
    {
            ALOGE("Invalid encoder %d used for OGG recording", mAudioEncoder);
            return BAD_VALUE;
    }
    if (mSampleRate < 8000 || mSampleRate > 48000) 
    {
            ALOGE("Invalid sampling rate %d used for OGG vorbis recording",mSampleRate);
            return BAD_VALUE;
    }
   
    if (mAudioChannels != 1 && mAudioChannels != 2) 
    {
        ALOGE("Invalid number of audio channels %d used for ogg recording",mAudioChannels);
        return BAD_VALUE;
    }

    if (mAudioSource >= AUDIO_SOURCE_CNT) 
    {
        ALOGE("Invalid audio source: %d", mAudioSource);
        return BAD_VALUE;
    }

    sp<MediaSource> audioEncoder = createAudioSource();

    if (audioEncoder == NULL) {
        return UNKNOWN_ERROR;
    }

    mWriter = new OggWriter(dup(mOutputFd));
    mWriter->addSource(audioEncoder);

    if (mMaxFileDurationUs != 0) {
        mWriter->setMaxFileDuration(mMaxFileDurationUs);
    }
    if (mMaxFileSizeBytes != 0) {
        mWriter->setMaxFileSize(mMaxFileSizeBytes);
    }
    mWriter->setListener(mListener);
    status_t err = mWriter->start();
    return err;

}

void StagefrightRecorder::setupMPEG4MetaDataEx(sp<MetaData> *meta) {

	if (mArtistTag.length() > 0) {
		(*meta)->setCString(kKeyArtist, mArtistTag.string());
	}
	if (mAlbumTag.length() > 0) {
		(*meta)->setCString(kKeyAlbum, mAlbumTag.string());
	}
	if (mVideoSource == VIDEO_SOURCE_DEFAULT
		|| mVideoSource == VIDEO_SOURCE_CAMERA) {
		(*meta)->setInt32(kKeyVideoEncoder, mVideoEncoder);
		(*meta)->setInt32(kKeyFrameRate, mFrameRate);
		(*meta)->setInt32(kKeyWidth, mVideoWidth);
		(*meta)->setInt32(kKeyHeight, mVideoHeight);
		(*meta)->setInt32(kKeyVideoBitRate, mVideoBitRate);

		bool bEnableVQForMem = false;
#ifdef MTK_USES_VR_DYNAMIC_QUALITY_MECHANISM
		bEnableVQForMem = true;
#endif
		if (mCaptureTimeLapse) {//don't enable this feature when timelapse encoding
			bEnableVQForMem = false;
		}

#ifdef MTK_SLOW_MOTION_VIDEO_SUPPORT	
		if (mSlowMotionSpeedValue > 0) {
			ALOGD("set slow motion speed value %d", mSlowMotionSpeedValue);
			(*meta)->setInt32(kKeySlowMotionSpeedValue, mSlowMotionSpeedValue);
			
			ALOGD("disable video quality adjust");
			bEnableVQForMem = false;

			if(mIsDirectLink) {
				ALOGD("camera output bitstream, use direct link");
				(*meta)->setInt32(kKeyIsDirectLink, mIsDirectLink);
			}
		}
#endif

		if(bEnableVQForMem)
			(*meta)->setInt32(kKeyVQForMem, 1);

	}
	if (mLivePhotoTagValue > 0) {
		ALOGD("set livephoto tag %d", mLivePhotoTagValue);
		(*meta)->setInt32(kKeyIsLivePhoto, mLivePhotoTagValue);
	}
}

void StagefrightRecorder::resetEx(){
    mRTPTarget.setTo("");
	mPaused = false;
	mArtistTag.setTo("");
	mAlbumTag.setTo("");

	//for CTS VR parameter of 1920*1080 which is not 16 align 
	mEnableLivePhoto = false;
	mLivePhotoTagValue = -1;
	mSlowMotionSpeedValue = -1;
	mIsDirectLink = false;
}

void StagefrightRecorder::checkVideoEncoderCapabilitiesEx(){
	//-->Check video codec valid
	//Since our camera will send the preview size frame to encoder 
	//and this parameter have been set by AP in startPreview stage,
	//it is useless to clip video size here.
	//We can only switch codec type to avoid recorder error.
	if(VIDEO_ENCODER_H263 == mVideoEncoder) {//h263 encode only support these
		if (((mVideoWidth == 128) && (mVideoHeight == 96)) ||
			((mVideoWidth == 176) && (mVideoHeight == 144)) ||
			((mVideoWidth == 352) && (mVideoHeight == 288)) ||
			((mVideoWidth == 704) && (mVideoHeight == 576)) ||
			((mVideoWidth == 1408) && (mVideoHeight == 1152))) {
			ALOGD("h263 size is OK, %dx%d", mVideoWidth, mVideoHeight);
		}
		else {
			mVideoEncoder = VIDEO_ENCODER_MPEG_4_SP;
			ALOGW("Unsupport h263 size, switch to MPEG4, %dx%d", mVideoWidth, mVideoHeight);
		}
	}

	if (VIDEO_ENCODER_H264 == mVideoEncoder)
	{
		int32_t minWidth = mEncoderProfiles->getVideoEncoderParamByName(
							"enc.vid.width.min", mVideoEncoder);
		int32_t maxWidth = mEncoderProfiles->getVideoEncoderParamByName(
							"enc.vid.width.max", mVideoEncoder);
		
		int32_t minHeight = mEncoderProfiles->getVideoEncoderParamByName(
							"enc.vid.height.min", mVideoEncoder);
		int32_t maxHeight = mEncoderProfiles->getVideoEncoderParamByName(
							"enc.vid.height.max", mVideoEncoder);

		if ((mVideoWidth <= maxWidth) && (mVideoHeight <= maxHeight)
			&& (mVideoWidth > minWidth) && (mVideoHeight > minHeight)) {
			ALOGD("H264 size is ok, %dx%d", mVideoWidth, mVideoHeight);
		}
		else {
			mVideoEncoder = VIDEO_ENCODER_MPEG_4_SP;
			ALOGW("Unsupport h264 size, switch to MPEG4, %dx%d", mVideoWidth, mVideoHeight);
		}
	}

#ifdef MTK_VIDEO_HEVC_SUPPORT
	if (VIDEO_ENCODER_HEVC == mVideoEncoder)
	{
		int32_t minWidth = mEncoderProfiles->getVideoEncoderParamByName(
							"enc.vid.width.min", mVideoEncoder);
		int32_t maxWidth = mEncoderProfiles->getVideoEncoderParamByName(
							"enc.vid.width.max", mVideoEncoder);
		
		int32_t minHeight = mEncoderProfiles->getVideoEncoderParamByName(
							"enc.vid.height.min", mVideoEncoder);
		int32_t maxHeight = mEncoderProfiles->getVideoEncoderParamByName(
							"enc.vid.height.max", mVideoEncoder);

		if ((mVideoWidth <= maxWidth) && (mVideoHeight <= maxHeight)
			&& (mVideoWidth > minWidth) && (mVideoHeight > minHeight)) {
			ALOGD("HEVC size is ok, %dx%d", mVideoWidth, mVideoHeight);
		}
		else {
			mVideoEncoder = VIDEO_ENCODER_MPEG_4_SP;
			ALOGW("Unsupport hevc size, switch to MPEG4, %dx%d", mVideoWidth, mVideoHeight);
		}
	}
#endif

}

void StagefrightRecorder::checkVideoEncoderBufferLimit(int& width, int& height) {
	//add for CTS test-- which set parameter 1920*1080 not 16 align, which will cause codec KE
	VENC_DRV_QUERY_INPUT_BUF_LIMIT tInputBuflimit;
	
	switch(mVideoEncoder){
			case VIDEO_ENCODER_H263:
				tInputBuflimit.eVideoFormat = VENC_DRV_VIDEO_FORMAT_H263;
				break;
			case VIDEO_ENCODER_H264:
				tInputBuflimit.eVideoFormat = VENC_DRV_VIDEO_FORMAT_H264;
				break;
#ifdef MTK_VIDEO_HEVC_SUPPORT
			case VIDEO_ENCODER_HEVC:
				tInputBuflimit.eVideoFormat = VENC_DRV_VIDEO_FORMAT_HEVC;
				break;	  
#endif
			case VIDEO_ENCODER_MPEG_4_SP:
				tInputBuflimit.eVideoFormat = VENC_DRV_VIDEO_FORMAT_MPEG4;
				break;
			default:
				ALOGW("unsupport codec %d",mVideoEncoder);
				tInputBuflimit.eVideoFormat = VENC_DRV_VIDEO_FORMAT_H264;
				break;
		}
	tInputBuflimit.u4Width= mVideoWidth; //input
	tInputBuflimit.u4Height = mVideoHeight; //input
	tInputBuflimit.u4Stride = mVideoWidth; //output--buffer width limitation
	tInputBuflimit.u4SliceHeight = mVideoHeight;//output--buffer height limiatation
	// tInputBuflimit.eScenario = VENC_DRV_SCENARIO_CAMERA_REC_SLOW_MOTION;
	if(VENC_DRV_MRESULT_OK != eVEncDrvQueryCapability(VENC_DRV_QUERY_TYPE_INPUT_BUF_LIMIT,(void*)&tInputBuflimit,NULL)){
		ALOGE("Query codec Buffer limitation fail!!");
	}
	width = tInputBuflimit.u4Stride;
	height = tInputBuflimit.u4SliceHeight;
	
	ALOGI("checkVideoEncoderBufferLimit, width =%d, height=%d", width, height);
}

#ifdef MTK_SLOW_MOTION_VIDEO_SUPPORT

status_t StagefrightRecorder::setupMtkBSSource(
        sp<MediaSource> cameraSource,
        sp<MediaSource> *source) {
    source->clear();

    sp<MetaData> enc_meta = new MetaData;
    enc_meta->setInt32(kKeyBitRate, mVideoBitRate);
    enc_meta->setInt32(kKeyFrameRate, mFrameRate);
	MM_LOGD("+,mVideoEncoder=%d,enc_meta kKeyBitRate=%d,kKeyFrameRate=%d",mVideoEncoder,mVideoBitRate,mFrameRate);

    switch (mVideoEncoder) {
        case VIDEO_ENCODER_H263:
            enc_meta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_VIDEO_H263);
            break;

        case VIDEO_ENCODER_MPEG_4_SP:
            enc_meta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_VIDEO_MPEG4);
            break;

        case VIDEO_ENCODER_H264:
            enc_meta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_VIDEO_AVC);
            break;

#ifdef MTK_VIDEO_HEVC_SUPPORT
        case VIDEO_ENCODER_HEVC:
            enc_meta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_VIDEO_HEVC);
            break;
#endif
        default:
            CHECK(!"Should not be here, unsupported video encoding.");
            break;
    }

    sp<MetaData> meta = cameraSource->getFormat();

    int32_t width, height, stride, sliceHeight, colorFormat;
    CHECK(meta->findInt32(kKeyWidth, &width));
    CHECK(meta->findInt32(kKeyHeight, &height));
    CHECK(meta->findInt32(kKeyStride, &stride));
    CHECK(meta->findInt32(kKeySliceHeight, &sliceHeight));
    CHECK(meta->findInt32(kKeyColorFormat, &colorFormat));
	
	//tell codec the real width and height ap want to record
	width  = mVideoWidth;
    height = mVideoHeight;

    enc_meta->setInt32(kKeyWidth, width);
    enc_meta->setInt32(kKeyHeight, height);
    enc_meta->setInt32(kKeyIFramesInterval, mIFramesIntervalSec);
    enc_meta->setInt32(kKeyStride, stride);
    enc_meta->setInt32(kKeySliceHeight, sliceHeight);
    enc_meta->setInt32(kKeyColorFormat, colorFormat);

	MM_LOGD("kKeyWidth=%d, kKeyHeight=%d,\n kKeyIFramesInterval=%d,\n kKeyStride=%d,kKeySliceHeight=%d,\n kKeyColorFormat=%d,",\
		     mVideoWidth, mVideoHeight, mIFramesIntervalSec, stride, sliceHeight, colorFormat);
	MM_LOGD("kKeyTimeScale=%d, kKeyVideoProfile=%d, kKeyVideoLevel=%d",\
		     mVideoTimeScale, mVideoEncoderProfile, mVideoEncoderLevel);

    if (mVideoTimeScale > 0) {
        enc_meta->setInt32(kKeyTimeScale, mVideoTimeScale);
    }
    if (mVideoEncoderProfile != -1) {
        enc_meta->setInt32(kKeyVideoProfile, mVideoEncoderProfile);
    }
    if (mVideoEncoderLevel != -1) {
        enc_meta->setInt32(kKeyVideoLevel, mVideoEncoderLevel);
    }

	if (mSlowMotionSpeedValue > 0) {
		ALOGD("enable non ref p for solw motion");
		enc_meta->setInt32(kKeyEnableNonRefP, true);
	}

	sp<MediaSource> mtkBSSource = NULL;

    ALOGI("Create MtkBSSource for hw direct link");
	mtkBSSource = MtkBSSource::Create(cameraSource, enc_meta);
	mIsDirectLink = true;
	
    if (mtkBSSource == NULL) {
        ALOGW("Failed to create the MtkBSSource");
        // When the encoder fails to be created, we need
        // release the camera source due to the camera's lock
        // and unlock mechanism.
        cameraSource->stop();
        return UNKNOWN_ERROR;
    }

    *source = mtkBSSource;

    return OK;
}
#endif
#endif
}  // namespace android
