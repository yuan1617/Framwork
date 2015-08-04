LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
    AudioPolicyService.cpp \
    AudioPolicyEffects.cpp

ifeq ($(USE_LEGACY_AUDIO_POLICY), 1)
LOCAL_SRC_FILES += \
    AudioPolicyInterfaceImplLegacy.cpp \
    AudioPolicyClientImplLegacy.cpp

    LOCAL_CFLAGS += -DUSE_LEGACY_AUDIO_POLICY
else
LOCAL_SRC_FILES += \
    AudioPolicyInterfaceImpl.cpp \
    AudioPolicyClientImpl.cpp
endif

LOCAL_C_INCLUDES := \
    $(TOPDIR)frameworks/av/services/audioflinger \
    $(call include-path-for, audio-effects) \
    $(call include-path-for, audio-utils)

LOCAL_SHARED_LIBRARIES := \
    libcutils \
    libutils \
    liblog \
    libbinder \
    libmedia \
    libhardware \
    libhardware_legacy

ifneq ($(USE_LEGACY_AUDIO_POLICY), 1)
LOCAL_SHARED_LIBRARIES += \
    libaudiopolicymanager
endif

LOCAL_STATIC_LIBRARIES := \
    libmedia_helper \
    libserviceutility

ifeq ($(MTK_AUDIO),yes)
LOCAL_CFLAGS += -DMTK_AUDIO
LOCAL_SRC_FILES += \
    AudioHeadsetDetect.cpp
ifeq ($(strip $(TARGET_BUILD_VARIANT)),eng)
  LOCAL_CFLAGS += -DCONFIG_MT_ENG_BUILD
endif
endif

LOCAL_C_INCLUDES += \
    $(TOPDIR)/frameworks/av/include \
    $(MTK_PATH_PLATFORM)/hardware/audio/include \
    $(MTK_PATH_SOURCE)/platform/common/hardware/audio/include \
    $(MTK_PATH_SOURCE)/external/nvram/libnvram \
    $(MTK_PATH_SOURCE)/external/AudioCompensationFilter \
    $(MTK_PATH_SOURCE)/external/AudioComponentEngine \
    $(MTK_PATH_SOURCE)/external/HeadphoneCompensationFilter \
    $(MTK_PATH_SOURCE)/external/audiocustparam \
    $(MTK_PATH_SOURCE)/frameworks/av/include/media \
    $(MTK_PATH_SOURCE)/frameworks/av/include \
    $(TOP)/frameworks/av/include/media \
    $(MTK_PATH_CUSTOM)/custom \
    $(MTK_PATH_CUSTOM)/custom/audio \
    $(MTK_PATH_CUSTOM)/hal/audioflinger/audio

LOCAL_MODULE:= libaudiopolicyservice
#LOCAL_MULTILIB := 32
LOCAL_CFLAGS += -fvisibility=hidden

include $(BUILD_SHARED_LIBRARY)


ifneq ($(USE_LEGACY_AUDIO_POLICY), 1)

include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
    AudioPolicyManager.cpp

LOCAL_SHARED_LIBRARIES := \
    libcutils \
    libutils \
    liblog \
    libsoundtrigger

LOCAL_STATIC_LIBRARIES := \
    libmedia_helper
    
ifeq ($(MTK_AUDIO),yes)
LOCAL_CFLAGS += -DMTK_AUDIO
endif

ifeq ($(strip $(MTK_HIGH_RESOLUTION_AUDIO_SUPPORT)),yes)
    LOCAL_CFLAGS += -DMTK_HD_AUDIO_ARCHITECTURE
endif

ifeq ($(MTK_SUPPORT_TC1_TUNNING),yes)
  LOCAL_CFLAGS += -DMTK_AUDIO_GAIN_TABLE
endif

ifeq ($(DISABLE_EARPIECE),yes)
    LOCAL_CFLAGS += -DDISABLE_EARPIECE
endif

ifeq ($(MTK_SMARTBOOK_SUPPORT),yes)
    LOCAL_CFLAGS += -DHDMI_VOLUME_ADJUST_SUPPORT
endif

LOCAL_SHARED_LIBRARIES += \
    libmedia \
    libaudiocustparam

LOCAL_C_INCLUDES += \
    $(TOPDIR)/frameworks/av/include \
    $(MTK_PATH_PLATFORM)/hardware/audio/include \
    $(MTK_PATH_SOURCE)/platform/common/hardware/audio/include \
    $(MTK_PATH_SOURCE)/external/nvram/libnvram \
    $(MTK_PATH_SOURCE)/external/AudioCompensationFilter \
    $(MTK_PATH_SOURCE)/external/AudioComponentEngine \
    $(MTK_PATH_SOURCE)/external/HeadphoneCompensationFilter \
    $(MTK_PATH_SOURCE)/external/audiocustparam \
    $(MTK_PATH_SOURCE)/frameworks/av/include/media \
    $(MTK_PATH_SOURCE)/frameworks/av/include \
    $(TOP)/frameworks/av/include/media \
    $(MTK_PATH_CUSTOM)/custom \
    $(MTK_PATH_CUSTOM)/custom/audio \
    $(MTK_PATH_CUSTOM)/hal/audioflinger/audio

ifeq ($(HAVE_AEE_FEATURE),yes)
    LOCAL_SHARED_LIBRARIES += libaed
    LOCAL_C_INCLUDES += \
    $(MTK_PATH_SOURCE)/external/aee/binary/inc
    LOCAL_CFLAGS += -DHAVE_AEE_FEATURE
endif

ifneq (,$(filter yes, $(MTK_AUDIO_DDPLUS_SUPPORT) $(MTK_DOLBY_DAP_SUPPORT)))
    LOCAL_C_INCLUDES += $(MTK_PATH_SOURCE)/external/ds1_utility
    LOCAL_STATIC_LIBRARIES += ds1_utility
    LOCAL_SHARED_LIBRARIES += libbinder
endif

ifeq ($(MTK_DOLBY_DAP_SUPPORT), yes)
        LOCAL_CFLAGS += -DDOLBY_DAP_OPENSLES
        LOCAL_CFLAGS += -DDOLBY_DAP_OPENSLES_MOVE_EFFECT
        LOCAL_C_INCLUDES += $(TOP)/vendor/dolby/ds1/libds/include/
endif

ifeq ($(strip $(MTK_LOSSLESS_BT_SUPPORT)),yes)
    LOCAL_CFLAGS += -DMTK_LOSSLESS_BT_SUPPORT
endif

LOCAL_MODULE:= libaudiopolicymanagerdefault

include $(BUILD_SHARED_LIBRARY)

ifneq ($(USE_CUSTOM_AUDIO_POLICY), 1)

include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
    AudioPolicyFactory.cpp

LOCAL_SHARED_LIBRARIES := \
    libaudiopolicymanagerdefault
    
LOCAL_C_INCLUDES := \
    ./$(TOPDIR)/$(MTK_ROOT)/frameworks-ext/av \
    $(TOPDIR)/frameworks/av/include \
    $(MTK_PATH_PLATFORM)/hardware/audio/include \
    $(MTK_PATH_SOURCE)/platform/common/hardware/audio/include \
    $(MTK_PATH_SOURCE)/external/nvram/libnvram \
    $(MTK_PATH_SOURCE)/external/AudioCompensationFilter \
    $(MTK_PATH_SOURCE)/external/AudioComponentEngine \
    $(MTK_PATH_SOURCE)/external/HeadphoneCompensationFilter \
    $(MTK_PATH_SOURCE)/external/audiocustparam \
    $(MTK_PATH_SOURCE)/frameworks/av/include/media \
    $(MTK_PATH_SOURCE)/frameworks/av/include \
    $(TOP)/frameworks/av/include/media \
    $(MTK_PATH_CUSTOM)/custom \
    $(MTK_PATH_CUSTOM)/custom/audio \
    $(MTK_PATH_CUSTOM)/hal/audioflinger/audio

ifeq ($(MTK_AUDIO),yes)
LOCAL_CFLAGS += -DMTK_AUDIO
endif

ifeq ($(strip $(MTK_LOSSLESS_BT_SUPPORT)),yes)
    LOCAL_CFLAGS += -DMTK_LOSSLESS_BT_SUPPORT
endif

LOCAL_MODULE:= libaudiopolicymanager
#LOCAL_MULTILIB := 32

include $(BUILD_SHARED_LIBRARY)

endif
endif
