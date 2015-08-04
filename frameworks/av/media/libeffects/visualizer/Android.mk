LOCAL_PATH:= $(call my-dir)

# Visualizer library
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	EffectVisualizer.cpp

LOCAL_CFLAGS+= -O2 -fvisibility=hidden

LOCAL_SHARED_LIBRARIES := \
	libcutils \
	liblog \
	libdl

LOCAL_MODULE_RELATIVE_PATH := soundfx
LOCAL_MODULE:= libvisualizer

LOCAL_C_INCLUDES := \
	$(call include-path-for, audio-effects)
	
ifeq ($(strip $(BOARD_USES_MTK_AUDIO)),true)
 ifeq ($(strip $(MTK_HIGH_RESOLUTION_AUDIO_SUPPORT)),true)
#  LOCAL_CFLAGS += -DMTK_HD_AUDIO_ARCHITECTURE
 endif
 
#ifeq ($(strip $(MTK_BESLOUDNESS_SUPPORT)),yes)
    LOCAL_CFLAGS += -DMTK_AUDIOMIXER_ENABLE_DRC
#endif

LOCAL_C_INCLUDES += \
    $(TOP)/mediatek/frameworks-ext/av/services/audioflinger \
    $(TOP)/frameworks/av/services/audioflinger \
    $(MTK_PATH_SOURCE)/external/AudioCompensationFilter \
    $(MTK_PATH_SOURCE)/external/AudioComponentEngine \
    $(MTK_PATH_SOURCE)/external/nvram/libnvram \
    $(MTK_PATH_SOURCE)/external/bessound_HD \
    $(MTK_PATH_SOURCE)/external/limiter \
    $(MTK_PATH_SOURCE)/external/shifter \
    $(MTK_PATH_SOURCE)/external/bessurround_mtk/inc    
endif

include $(BUILD_SHARED_LIBRARY)
