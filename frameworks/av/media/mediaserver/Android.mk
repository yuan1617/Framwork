LOCAL_PATH:= $(call my-dir)

ifneq ($(BOARD_USE_CUSTOM_MEDIASERVEREXTENSIONS),true)
include $(CLEAR_VARS)
LOCAL_SRC_FILES := register.cpp
LOCAL_MODULE := libregistermsext
LOCAL_MODULE_TAGS := optional
include $(BUILD_STATIC_LIBRARY)
endif

include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	main_mediaserver.cpp 

LOCAL_SHARED_LIBRARIES := \
	libaudioflinger \
	libaudiopolicyservice \
	libcamera_metadata\
	libcameraservice \
	libmedialogservice \
	libcutils \
	libnbaio \
	libmedia \
	libmediaplayerservice \
	libutils \
	liblog \
	libmemorydumper \
	libdl \
	libbinder \
	libsoundtriggerservice


LOCAL_STATIC_LIBRARIES := \
	libregistermsext

LOCAL_C_INCLUDES := \
    $(TOP)/$(MTK_PATH_SOURCE)/frameworks/av/include \
    frameworks/av/media/libmediaplayerservice \
    frameworks/av/services/medialog \
    frameworks/av/services/audioflinger \
    frameworks/av/services/audiopolicy \
    frameworks/av/services/camera/libcameraservice \
    $(call include-path-for, audio-utils) \
    frameworks/av/services/soundtrigger

LOCAL_C_INCLUDES += \
        frameworks/av/include/media \
        $(TOP)/$(MTK_ROOT)/frameworks-ext/av/services/audioflinger \
        $(MTK_PATH_SOURCE)/external/audiodcremoveflt \
        $(MTK_PATH_SOURCE)/external/AudioCompensationFilter \
        $(MTK_PATH_SOURCE)/external/AudioComponentEngine \
        $(MTK_PATH_SOURCE)/platform/common/hardware/audio/aud_drv \
        $(MTK_PATH_SOURCE)/platform/common/hardware/audio/ \
        $(MTK_PATH_SOURCE)/platform/common/hardware/audio/include \
        $(MTK_PATH_PLATFORM)/hardware/audio/aud_drv \
        $(MTK_PATH_PLATFORM)/hardware/audio/aud_drv/include \
        $(MTK_PATH_PLATFORM)/hardware/audio \
        $(MTK_PATH_SOURCE)/external/AudioDCRemoval \
        $(MTK_PATH_SOURCE)/external/blisrc32 \
        $(MTK_PATH_SOURCE)/external/limiter \
        $(MTK_PATH_SOURCE)/external/shifter \
        $(MTK_PATH_SOURCE)/external/bessound_HD \
        $(MTK_PATH_SOURCE)/external/bessound \
        $(LOCAL_MTK_PATH)

LOCAL_C_INCLUDES += \
    $(MTK_PATH_SOURCE)/external/audiocustparam \


ifeq ($(strip $(BOARD_USES_MTK_AUDIO)),true)
LOCAL_CFLAGS += -DMTK_AUDIO
endif



LOCAL_MODULE:= mediaserver
LOCAL_32_BIT_ONLY := true

include $(BUILD_EXECUTABLE)
