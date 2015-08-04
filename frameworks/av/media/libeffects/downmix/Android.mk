LOCAL_PATH:= $(call my-dir)

# Multichannel downmix effect library
include $(CLEAR_VARS)

ifeq ($(strip $(BOARD_USES_MTK_AUDIO)),true)
  ifeq ($(strip $(MTK_BESSURROUND_SUPPORT)),yes)
    LOCAL_CFLAGS += -DMTK_HD_AUDIO_ARCHITECTURE
  endif
endif

LOCAL_SRC_FILES:= \
	EffectDownmix.c

LOCAL_SHARED_LIBRARIES := \
	libcutils liblog

LOCAL_MODULE:= libdownmix

LOCAL_MODULE_TAGS := optional

LOCAL_MODULE_RELATIVE_PATH := soundfx

LOCAL_C_INCLUDES := \
	$(call include-path-for, audio-effects) \
	$(call include-path-for, audio-utils)

LOCAL_CFLAGS += -fvisibility=hidden

include $(BUILD_SHARED_LIBRARY)
