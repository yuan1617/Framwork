LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

include frameworks/av/media/libstagefright/codecs/common/Config.mk

ifneq ($(strip $(MTK_USE_ANDROID_MM_DEFAULT_CODE)),yes)
$(call make-private-dependency,\
  $(BOARD_CONFIG_DIR)/configs/StageFright.mk \
)
endif
ifeq ($(MTK_OGM_PLAYBACK_SUPPORT),yes)
LOCAL_CFLAGS += -DMTK_OGM_PLAYBACK_SUPPORT
endif
ifeq ($(strip $(MTK_VIDEO_VP8ENC_SUPPORT)),yes)
LOCAL_CFLAGS += -DMTK_VIDEO_VP8ENC_SUPPORT
endif

LOCAL_SRC_FILES:=                         \
        ACodec.cpp                        \
        AACExtractor.cpp                  \
        AACWriter.cpp                     \
        AMRExtractor.cpp                  \
        AMRWriter.cpp                     \
        AudioPlayer.cpp                   \
        AudioSource.cpp                   \
        AwesomePlayer.cpp                 \
        CameraSource.cpp                  \
        CameraSourceTimeLapse.cpp         \
        ClockEstimator.cpp                \
        CodecBase.cpp                     \
        DataSource.cpp                    \
        DataURISource.cpp                 \
        DRMExtractor.cpp                  \
        ESDS.cpp                          \
        FileSource.cpp                    \
        HTTPBase.cpp                      \
        JPEGSource.cpp                    \
        MP3Extractor.cpp                  \
        MPEG2TSWriter.cpp                 \
        MPEG4Extractor.cpp                \
        MPEG4Writer.cpp                   \
        MediaAdapter.cpp                  \
        MediaBuffer.cpp                   \
        MediaBufferGroup.cpp              \
        MediaCodec.cpp                    \
        MediaCodecList.cpp                \
        MediaCodecSource.cpp              \
        MediaDefs.cpp                     \
        MediaExtractor.cpp                \
        http/MediaHTTP.cpp                \
        MediaMuxer.cpp                    \
        MediaSource.cpp                   \
        MetaData.cpp                      \
        NuCachedSource2.cpp               \
        NuMediaExtractor.cpp              \
        OMXClient.cpp                     \
        OMXCodec.cpp                      \
        OggExtractor.cpp                  \
        SampleIterator.cpp                \
        SampleTable.cpp                   \
        SkipCutBuffer.cpp                 \
        StagefrightMediaScanner.cpp       \
        StagefrightMetadataRetriever.cpp  \
        SurfaceMediaSource.cpp            \
        ThrottledSource.cpp               \
        TimeSource.cpp                    \
        TimedEventQueue.cpp               \
        Utils.cpp                         \
        VBRISeeker.cpp                    \
        WAVExtractor.cpp                  \
        WVMExtractor.cpp                  \
        XINGSeeker.cpp                    \
        avc_utils.cpp                     \

ifeq ($(strip $(MTK_USE_ANDROID_MM_DEFAULT_CODE)),yes)
LOCAL_SRC_FILES += \
        FLACExtractor.cpp
endif  # MTK_USE_ANDROID_MM_DEFAULT_CODE

LOCAL_C_INCLUDES:= \
        $(TOP)/frameworks/av/include/media/ \
        $(TOP)/frameworks/av/include/media/stagefright/timedtext \
        $(TOP)/frameworks/native/include/media/hardware \
        $(TOP)/external/tremolo \
        $(TOP)/external/openssl/include \
        $(TOP)/external/libvpx/libwebm \
        $(TOP)/system/netd/include \
        $(TOP)/external/icu/icu4c/source/common \
        $(TOP)/external/icu/icu4c/source/i18n \

ifeq ($(strip $(MTK_USE_ANDROID_MM_DEFAULT_CODE)),yes)
LOCAL_C_INCLUDES +=  \
        $(TOP)/external/flac/include \
        $(TOP)/frameworks/native/include/media/openmax
endif # MTK_USE_ANDROID_MM_DEFAULT_CODE
LOCAL_SHARED_LIBRARIES := \
        libbinder \
        libcamera_client \
        libcutils \
        libdl \
        libdrmframework \
        libexpat \
        libgui \
        libicui18n \
        libicuuc \
        liblog \
        libmedia \
        libnetd_client \
        libopus \
        libsonivox \
        libssl \
        libstagefright_omx \
        libstagefright_yuv \
        libsync \
        libui \
        libutils \
        libvorbisidec \
        libz \
        libpowermanager

LOCAL_STATIC_LIBRARIES := \
        libstagefright_color_conversion \
        libstagefright_aacenc \
        libstagefright_matroska \
        libstagefright_webm \
        libstagefright_timedtext \
        libvpx \
        libwebm \
        libstagefright_mpeg2ts \
        libstagefright_id3 \
        libmedia_helper

ifeq ($(strip $(MTK_USE_ANDROID_MM_DEFAULT_CODE)),yes)
LOCAL_STATIC_LIBRARIES += libFLAC
endif  # MTK_USE_ANDROID_MM_DEFAULT_CODE

LOCAL_SHARED_LIBRARIES += \
        libstagefright_enc_common \
        libstagefright_avc_common \
        libstagefright_foundation \
        libdl

LOCAL_CFLAGS += -Wno-multichar

######################## MTK_USE_ANDROID_MM_DEFAULT_CODE ######################
ifneq ($(strip $(MTK_USE_ANDROID_MM_DEFAULT_CODE)),yes)

LOCAL_C_INCLUDES +=  \
        $(TOP)/$(MTK_ROOT)/frameworks/av/media/libstagefright/include/omx_core  \
        $(TOP)/vendor/mediatek/proprietary/hardware/include

#for rtsp local sdp
LOCAL_C_INCLUDES += $(TOP)/frameworks/av/media/libstagefright/rtsp
LOCAL_SRC_FILES += \
			MtkSDPExtractor.cpp
LOCAL_STATIC_LIBRARIES += libstagefright_rtsp



LOCAL_SRC_FILES += \
    	OggWriter.cpp   \
        PCMWriter.cpp

#LOCAL_STATIC_LIBRARIES += libwriter_mtk

LOCAL_SRC_FILES += \
	TableOfContentThread.cpp \
    FileSourceProxy.cpp

LOCAL_SRC_FILES += \
	MtkAACExtractor.cpp \
	MMReadIOThread.cpp
            
ifeq ($(strip $(MTK_LOSSLESS_BT_SUPPORT)),yes)
    LOCAL_CFLAGS += -DMTK_LOSSLESS_BT_SUPPORT
endif

LOCAL_SRC_FILES += hevc_utils.cpp

LOCAL_SRC_FILES += LivePhotoSource.cpp          \
                   MPEG4FileCacheWriter.cpp     \
                   VideoQualityController.cpp   \
                   MtkBSSource.cpp

LOCAL_SHARED_LIBRARIES += \
libvcodecdrv

LOCAL_SRC_FILES += MtkFLVExtractor.cpp

LOCAL_SHARED_LIBRARIES += \
	libhardware \
	libskia \
	libgralloc_extra

ifeq ($(MTK_AUDIO_APE_SUPPORT),yes)
LOCAL_CFLAGS += -DMTK_AUDIO_APE_SUPPORT

LOCAL_SRC_FILES += \
        APEExtractor.cpp \
        apetag.cpp

endif
ifeq ($(MTK_AUDIO_ALAC_SUPPORT),yes)
LOCAL_CFLAGS += -DMTK_AUDIO_ALAC_SUPPORT

LOCAL_SRC_FILES += \
        CAFExtractor.cpp
endif

ifeq ($(strip $(MTK_BSP_PACKAGE)),no)
 	LOCAL_SRC_FILES += MtkFLACExtractor.cpp
    LOCAL_C_INCLUDES += $(TOP)/external/flac/include
    LOCAL_STATIC_LIBRARIES += libFLAC
else
    ifeq ($(TARGET_ARCH), arm)
    	LOCAL_SRC_FILES += MtkFLACExtractor.cpp
        LOCAL_C_INCLUDES += $(TOP)/$(MTK_ROOT)/external/flacdec/include
        LOCAL_STATIC_LIBRARIES += libflacdec_mtk
    else
    	LOCAL_SRC_FILES += FLACExtractor.cpp
        LOCAL_C_INCLUDES += $(TOP)/external/flac/include
        LOCAL_STATIC_LIBRARIES += libFLAC
    endif
endif

LOCAL_C_INCLUDES += \
        $(MTK_PATH_SOURCE)/frameworks/av/media/libstagefright/include \
        $(MTK_PATH_SOURCE)/frameworks/av/include \
        $(TOP)/frameworks/av/media/libstagefright/include \
        $(MTK_PATH_SOURCE)/kernel/include \
        $(TOP)/external/skia/include/images \
        $(TOP)/external/skia/include/core \
        $(TOP)/frameworks/native/include/media/editor \
        $(TOP)/$(MTK_ROOT)/hardware/dpframework/inc \
        $(TOP)/frameworks/av/include \
        $(TOP)/$(MTK_ROOT)/frameworks-ext/native/include


ifneq ($(strip $(MTK_USE_ANDROID_MM_DEFAULT_CODE)),yes)
ifeq ($(strip $(MTK_DP_FRAMEWORK)),yes)
LOCAL_SHARED_LIBRARIES += \
    libdpframework
endif
endif
ifeq ($(strip $(MTK_BSP_PACKAGE)),no)
LOCAL_SHARED_LIBRARIES += \
        libcustom_prop
endif
LOCAL_SRC_FILES += NuCachedWrapperSource.cpp
ifeq ($(strip $(HAVE_ADPCMENCODE_FEATURE)),yes)
    LOCAL_CFLAGS += -DHAVE_ADPCMENCODE_FEATURE
    LOCAL_SRC_FILES += \
        ADPCMWriter.cpp
endif

ifeq ($(strip $(MTK_AVI_PLAYBACK_SUPPORT)), yes)
	LOCAL_CFLAGS += -DMTK_AVI_PLAYBACK_SUPPORT
	LOCAL_SRC_FILES += MtkAVIExtractor.cpp
endif

ifeq ($(MTK_OGM_PLAYBACK_SUPPORT),yes)
LOCAL_CFLAGS += -DMTK_OGM_PLAYBACK_SUPPORT
LOCAL_SRC_FILES += \
        OgmExtractor.cpp
endif

ifeq ($(strip $(MTK_WMV_PLAYBACK_SUPPORT)), yes)
        LOCAL_SRC_FILES += ASFExtractor.cpp
        LOCAL_C_INCLUDES += $(TOP)/frameworks/av/media/libstagefright/libasf/inc
        LOCAL_STATIC_LIBRARIES += libasf
endif

ifeq ($(strip $(HAVE_XLOG_FEATURE)),yes)
	LOCAL_CFLAGS += -DMTK_STAGEFRIGHT_USE_XLOG
endif

ifeq ($(strip $(MTK_DRM_APP)),yes)
    LOCAL_CFLAGS += -DMTK_DRM_APP
    LOCAL_C_INCLUDES += \
        $(TOP)/$(MTK_ROOT)/frameworks/av/include \
        external/stlport/stlport \
        bionic
    LOCAL_SHARED_LIBRARIES += \
        libdrmmtkutil
endif

#ifeq ($(strip $(MTK_USES_VR_DYNAMIC_QUALITY_MECHANISM)),yes)
LOCAL_CFLAGS += -DMTK_USES_VR_DYNAMIC_QUALITY_MECHANISM
LOCAL_C_INCLUDES += \
			$(TOP)/$(MTK_PATH_CUSTOM)/native/vr
#endif
LOCAL_C_INCLUDES += \
	$(TOP)/external/aac/libAACdec/include \
	$(TOP)/external/aac/libPCMutils/include \
	$(TOP)/external/aac/libFDK/include \
	$(TOP)/external/aac/libMpegTPDec/include \
	$(TOP)/external/aac/libSBRdec/include \
	$(TOP)/external/aac/libSYS/include

LOCAL_STATIC_LIBRARIES += libFraunhoferAAC
LOCAL_CFLAGS += -DUSE_FRAUNHOFER_AAC

#MediaRecord CameraSource  OMXCodec
LOCAL_SHARED_LIBRARIES += libcamera_client_mtk
ifeq ($(HAVE_AEE_FEATURE),yes)
LOCAL_SHARED_LIBRARIES += libaed
LOCAL_C_INCLUDES += $(MTK_ROOT)/external/aee/binary/inc
LOCAL_CFLAGS += -DHAVE_AEE_FEATURE
endif

MTK_CUSTOM_UASTRING_FROM_PROPERTY := yes
ifeq ($(strip $(MTK_BSP_PACKAGE)),yes)   # BSP would not have CUSTOM_UASTRING, confirm with yong.ding
	MTK_CUSTOM_UASTRING_FROM_PROPERTY := no
endif
ifeq ($(strip $(MTK_CUSTOM_UASTRING_FROM_PROPERTY)), yes)
LOCAL_CFLAGS += -DCUSTOM_UASTRING_FROM_PROPERTY
LOCAL_C_INCLUDES += $(MTK_PATH_SOURCE)/frameworks/base/custom/inc
LOCAL_SHARED_LIBRARIES += libcustom_prop
endif

LOCAL_CFLAGS += -DMTK_ELEMENT_STREAM_SUPPORT
LOCAL_SRC_FILES += ESExtractor.cpp

endif    # MTK_USE_ANDROID_MM_DEFAULT_CODE

######################## MTK_USE_ANDROID_MM_DEFAULT_CODE ######################

ifneq ($(strip $(MTK_EMULATOR_SUPPORT)),yes)
LOCAL_SHARED_LIBRARIES += libstagefright_memutil
endif
ifeq ($(strip $(MTK_VIDEO_HEVC_SUPPORT)),yes)
LOCAL_CFLAGS += -DMTK_VIDEO_HEVC_SUPPORT
endif

#MTK format
LOCAL_C_INCLUDES += $(TOP)/vendor/mediatek/proprietary/hardware/include


LOCAL_MODULE:= libstagefright

LOCAL_MODULE_TAGS := optional

include $(BUILD_SHARED_LIBRARY)

include $(call all-makefiles-under,$(LOCAL_PATH))
