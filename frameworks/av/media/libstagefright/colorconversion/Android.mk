LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

ifneq ($(strip $(MTK_USE_ANDROID_MM_DEFAULT_CODE)),yes)
ifeq ($(strip $(MTK_DP_FRAMEWORK)),yes)
LOCAL_CFLAGS += -DMTK_USEDPFRMWK
else
LOCAL_CFLAGS += -DMTK_MHAL
endif
endif

LOCAL_SRC_FILES:=                     \
        ColorConverter.cpp            \
        SoftwareRenderer.cpp

LOCAL_C_INCLUDES := \
    $(TOP)/vendor/mediatek/proprietary/frameworks/av/media/libstagefright/include/omx_core \
    $(TOP)/frameworks/native/include/media/openmax \
    $(TOP)/hardware/msm7k \
	$(TOP)/vendor/mediatek/proprietary/hardware/dpframework/inc \
	$(TOP)/vendor/mediatek/proprietary/external/mhal/inc

LOCAL_MODULE:= libstagefright_color_conversion

include $(BUILD_STATIC_LIBRARY)
