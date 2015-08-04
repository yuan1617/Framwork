LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=                 \
        MatroskaExtractor.cpp

LOCAL_C_INCLUDES:= \
        $(TOP)/external/libvpx/libwebm \
 $(TOP)/frameworks/native/include/media/openmax \

LOCAL_CFLAGS += -Wno-multichar -Werror

######################## MTK_USE_ANDROID_MM_DEFAULT_CODE ######################
ifeq ($(strip $(MTK_USE_ANDROID_MM_DEFAULT_CODE)),yes)

else

LOCAL_C_INCLUDES += \
$(TOP)/$(MTK_ROOT)/frameworks/av/media/libstagefright/include/omx_core

endif  # MTK_USE_ANDROID_MM_DEFAULT_CODE
######################## MTK_USE_ANDROID_MM_DEFAULT_CODE ######################


LOCAL_MODULE:= libstagefright_matroska

include $(BUILD_STATIC_LIBRARY)
