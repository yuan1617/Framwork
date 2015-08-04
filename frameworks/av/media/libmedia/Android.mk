LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

ifeq ($(MTK_AUDIO),yes)
LOCAL_CFLAGS += -DMTK_AUDIO
LOCAL_C_INCLUDES+= \
   $(TOP)/$(MTK_PATH_SOURCE)/platform/common/hardware/audio/include
endif

LOCAL_SRC_FILES:= \
    AudioParameter.cpp
LOCAL_MODULE:= libmedia_helper
LOCAL_MODULE_TAGS := optional

include $(BUILD_STATIC_LIBRARY)

include $(CLEAR_VARS)



ifeq ($(strip $(BOARD_USES_MTK_AUDIO)),true)
  LOCAL_CFLAGS += -DMTK_HDMI_MULTI_CHANNEL_SUPPORT
else
  LOCAL_CFLAGS += -DGENERIC_AUDIO
endif

# For MTK Sink feature
ifeq ($(strip $(MTK_WFD_SINK_SUPPORT)),yes)
LOCAL_CFLAGS += -DMTK_WFD_SINK_SUPPORT

# For MTK Sink UIBC feature
ifeq ($(strip $(MTK_WFD_SINK_UIBC_SUPPORT)),yes)
LOCAL_CFLAGS += -DMTK_WFD_SINK_UIBC_SUPPORT
endif
endif

ifeq ($(MTK_AUDIO),yes)
  LOCAL_CFLAGS += -DMTK_AUDIO
endif

ifeq ($(strip $(HAVE_AACENCODE_FEATURE)),yes)
    LOCAL_CFLAGS += -DHAVE_AACENCODE_FEATURE
endif

ifeq ($(strip $(MTK_AUDIO_HD_REC_SUPPORT)), yes)
	LOCAL_CFLAGS += -DMTK_AUDIO_HD_REC_SUPPORT
endif

LOCAL_SRC_FILES:= \
    AudioTrack.cpp \
    AudioTrackShared.cpp \
    IAudioFlinger.cpp \
    IAudioFlingerClient.cpp \
    IAudioTrack.cpp \
    IAudioRecord.cpp \
    ICrypto.cpp \
    IDrm.cpp \
    IDrmClient.cpp \
    IHDCP.cpp \
    AudioRecord.cpp \
    AudioSystem.cpp \
    mediaplayer.cpp \
    IMediaCodecList.cpp \
    IMediaHTTPConnection.cpp \
    IMediaHTTPService.cpp \
    IMediaLogService.cpp \
    IMediaPlayerService.cpp \
    IMediaPlayerClient.cpp \
    IMediaRecorderClient.cpp \
    IMediaPlayer.cpp \
    IMediaRecorder.cpp \
    IRemoteDisplay.cpp \
    IRemoteDisplayClient.cpp \
    IStreamSource.cpp \
    MediaCodecInfo.cpp \
    Metadata.cpp \
    mediarecorder.cpp \
    IMediaMetadataRetriever.cpp \
    mediametadataretriever.cpp \
    ToneGenerator.cpp \
    JetPlayer.cpp \
    IOMX.cpp \
    IAudioPolicyService.cpp \
    IAudioPolicyServiceClient.cpp \
    MediaScanner.cpp \
    MediaScannerClient.cpp \
    CharacterEncodingDetector.cpp \
    IMediaDeathNotifier.cpp \
    MediaProfiles.cpp \
    IEffect.cpp \
    IEffectClient.cpp \
    AudioEffect.cpp \
    Visualizer.cpp \
    MemoryLeakTrackUtil.cpp \
    SoundPool.cpp \
    SoundPoolThread.cpp \
    StringArray.cpp

LOCAL_SRC_FILES += ../libnbaio/roundup.c

ifneq ($(strip $(MTK_USE_ANDROID_MM_DEFAULT_CODE)),yes)
LOCAL_SRC_FILES += \
    AudioPCMxWay.cpp \
    ATVCtrl.cpp \
    IATVCtrlClient.cpp \
    IATVCtrlService.cpp \
    AudioTrackCenter.cpp
endif


LOCAL_SHARED_LIBRARIES := \
        libui liblog libcutils libutils libbinder libsonivox libicuuc libicui18n libexpat \
        libcamera_client libstagefright_foundation \
        libgui libdl libaudioutils libnbaio

LOCAL_STATIC_LIBRARIES += \
        libmedia_helper
ifneq ($(strip $(MTK_USE_ANDROID_MM_DEFAULT_CODE)),yes)

LOCAL_SHARED_LIBRARIES += \
        libvcodecdrv
endif

LOCAL_STATIC_LIBRARIES += libinstantssq

LOCAL_WHOLE_STATIC_LIBRARIES := libmedia_helper

LOCAL_MODULE:= libmedia

LOCAL_C_INCLUDES := \
    $(TOP)/frameworks/native/include/media/openmax  \
    $(TOP)/frameworks/av/include/media/ \
    $(TOP)/frameworks/av/media/libstagefright   \
    $(TOP)/external/icu/icu4c/source/common \
    $(TOP)/external/icu/icu4c/source/i18n   \
    $(call include-path-for, audio-effects) \
    $(call include-path-for, audio-utils)

ifneq ($(strip $(MTK_USE_ANDROID_MM_DEFAULT_CODE)),yes)
LOCAL_C_INCLUDES+= \
	 $(TOP)/$(MTK_PATH_PLATFORM)/hardware/vcodec/inc \
	 $(TOP)/$(MTK_ROOT)/external/mhal/src/core/drv/inc \
	 $(TOP)/$(MTK_ROOT)/frameworks/av/include
endif

ifeq ($(MTK_AUDIO),yes)
LOCAL_C_INCLUDES+= \
   $(TOP)/$(MTK_PATH_SOURCE)/platform/common/hardware/audio/include
endif

include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)

# for <cutils/atomic-inline.h>
LOCAL_CFLAGS += -DANDROID_SMP=$(if $(findstring true,$(TARGET_CPU_SMP)),1,0)
LOCAL_SRC_FILES += SingleStateQueue.cpp
LOCAL_CFLAGS += -DSINGLE_STATE_QUEUE_INSTANTIATIONS='"SingleStateQueueInstantiations.cpp"'

LOCAL_MODULE := libinstantssq
LOCAL_MODULE_TAGS := optional

include $(BUILD_STATIC_LIBRARY)
