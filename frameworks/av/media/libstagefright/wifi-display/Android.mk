LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

ifeq ($(strip $(MTK_WFD_HDCP_TX_SUPPORT)),yes)
LOCAL_CPPFLAGS += -DWFD_HDCP_TX_SUPPORT -DWFD_HDCP_TX_PAYLOAD_ALIGNMENT
endif

ifeq ($(strip $(MTK_DX_HDCP_SUPPORT)),yes)
LOCAL_CPPFLAGS += -DWFD_HDCP_TX_PAYLOAD_ALIGNMENT
endif

ifeq ($(strip $(MTK_SEC_WFD_VIDEO_PATH_SUPPORT)),yes)
LOCAL_CPPFLAGS += -DSEC_WFD_VIDEO_PATH_SUPPORT
endif

ifeq ($(strip $(TRUSTONIC_TEE_SUPPORT)),yes)
LOCAL_CPPFLAGS += -DTRUSTONIC_TEE_SUPPORT
endif

ifeq ($(strip $(MTK_USE_ANDROID_MM_DEFAULT_CODE)),yes)
LOCAL_SRC_FILES:= \
        MediaSender.cpp                 \
        Parameters.cpp                  \
        rtp/RTPSender.cpp               \
        source/Converter.cpp            \
        source/MediaPuller.cpp          \
        source/PlaybackSession.cpp      \
        source/RepeaterSource.cpp       \
        source/TSPacketizer.cpp         \
        source/WifiDisplaySource.cpp    \
        VideoFormats.cpp                
else

LOCAL_SRC_FILES:= \
        DataPathTrace.cpp               \
        MediaReceiver.cpp               \
        MediaSender.cpp                 \
        Parameters.cpp                  \
        SNTPClient.cpp                  \
        TimeSyncer.cpp                  \
        rtp/RTPAssembler.cpp            \
        rtp/RTPReceiver.cpp             \
        rtp/RTPSender.cpp               \
        source/Converter.cpp            \
        source/MediaPuller.cpp          \
        source/PlaybackSession.cpp      \
        source/RepeaterSource.cpp       \
        source/TSPacketizer.cpp         \
        source/WifiDisplaySource.cpp    \
        VideoFormats.cpp                \
        uibc/UibcMessage.cpp            \
        uibc/UibcCapability.cpp	        \
        uibc/UibcHandler.cpp            \
        uibc/UibcServerHandler.cpp      \
        uibc/UibcClientHandler.cpp      \
        
ifeq ($(strip $(MTK_WFD_SINK_SUPPORT)),yes)
LOCAL_SRC_FILES += \
        sink/DirectRenderer.cpp \
        sink/WifiDisplaySink.cpp

LOCAL_CFLAGS += -DMTK_WFD_SINK_SUPPORT

# For MTK Sink UIBC feature
ifeq ($(strip $(MTK_WFD_SINK_UIBC_SUPPORT)),yes)
LOCAL_CFLAGS += -DMTK_WFD_SINK_UIBC_SUPPORT
endif

endif

#LOCAL_CFLAGS += -DUSE_SINGLE_THREAD_FOR_SENDER
endif



ifeq ($(strip $(MTK_USE_ANDROID_MM_DEFAULT_CODE)),yes)
LOCAL_C_INCLUDES:= \
        $(TOP)/frameworks/av/media/libstagefright \
        $(TOP)/frameworks/native/include/media/openmax \
        $(TOP)/frameworks/av/media/libstagefright/mpeg2ts \
        $(TOP)/frameworks/av/media/libstagefright/wifi-display
else
LOCAL_C_INCLUDES:= \
        $(TOP)/frameworks/av/media/libstagefright \
        $(TOP)/frameworks/native/include/media/openmax \
        $(TOP)/frameworks/av/media/libstagefright/mpeg2ts \
        $(TOP)/frameworks/av/media/libstagefright/wifi-display \
        $(TOP)/frameworks/av/media/libstagefright/wifi-display/uibc \
        $(TOP)/frameworks/native/include/media/hardware \
        $(TOP)/external/expat/lib \
        $(TOP)/external/flac/include \
        $(TOP)/external/tremolo \
        $(MTK_PATH_SOURCE)/frameworks/av/media/libstagefright/include \
        $(TOP)/$(MTK_ROOT)/frameworks/av/media/libstagefright/include/omx_core \
        $(TOP)/external/openssl/include \
        $(TOP)/frameworks/av/media/libstagefright/include/omx_core \
        $(TOP)/frameworks/av/media/libstagefright/include \
        $(TOP)/external/skia/include/images \
        $(TOP)/external/skia/include/core \
        $(TOP)/system/core/include/system \
        $(TOP)/hardware/libhardware_legacy/include/hardware_legacy \
        $(TOP)/frameworks/native/include/input
endif

LOCAL_SHARED_LIBRARIES:= \
        libbinder                       \
        libcutils                       \
        liblog                          \
        libgui                          \
        libmedia                        \
        libstagefright                  \
        libstagefright_foundation       \
        libui                           \
        libutils                        \
        libdl
        
#add for mmprofile code        
ifneq ($(strip $(MTK_USE_ANDROID_MM_DEFAULT_CODE)),yes)
LOCAL_C_INCLUDES += \
        $(TOPDIR)/system/core/include
LOCAL_SHARED_LIBRARIES += \
           libmmprofile
           
#LOCAL_CFLAGS += -DUSE_MMPROFILE
ifeq ($(strip $(MTK_BWC_SUPPORT)),yes)
LOCAL_C_INCLUDES += \
        $(TOP)/$(MTK_PATH_SOURCE)/hardware/bwc/inc
LOCAL_SHARED_LIBRARIES += \
           libbwc

LOCAL_CFLAGS += -DUSE_MTK_BWC
endif 

endif   

ifeq ($(strip $(MTK_SEC_WFD_VIDEO_PATH_SUPPORT)),yes)
LOCAL_SHARED_LIBRARIES += \
           libtz_uree
endif

ifeq ($(strip $(MTK_SEC_WFD_VIDEO_PATH_SUPPORT)),yes)
LOCAL_C_INCLUDES += \
    $(call include-path-for, trustzone) \
    $(call include-path-for, trustzone-uree)
endif

ifneq ($(strip $(MTK_USE_ANDROID_MM_DEFAULT_CODE)),yes)
#ifneq ($(TARGET_BUILD_VARIANT),user)
#For MTB support
LOCAL_SHARED_LIBRARIES += libmtb
LOCAL_C_INCLUDES += $(TOP)/vendor/mediatek/proprietary/external/mtb
LOCAL_CFLAGS += -DMTB_SUPPORT
#endif
endif

#add for UIBC support     
ifneq ($(strip $(MTK_USE_ANDROID_MM_DEFAULT_CODE)),yes)
LOCAL_CFLAGS += -DUIBC_SOURCE_SUPPORT
endif  

LOCAL_MODULE:= libstagefright_wfd
LOCAL_MODULE_TAGS:= optional

include $(BUILD_SHARED_LIBRARY)

################################################################################

ifneq ($(strip $(MTK_USE_ANDROID_MM_DEFAULT_CODE)),yes)

include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
        wfd.cpp

LOCAL_C_INCLUDES:= \
        $(TOP)/frameworks/av/media/libstagefright \
        $(TOP)/frameworks/native/include/media/openmax \
        $(TOP)/frameworks/av/media/libstagefright/mpeg2ts \
        $(TOP)/frameworks/av/media/libstagefright/wifi-display \
        $(TOP)/frameworks/av/media/libstagefright/wifi-display\uibc

LOCAL_SHARED_LIBRARIES:= \
        libbinder                       \
        libgui                          \
        libmedia                        \
        libstagefright                  \
        libstagefright_foundation       \
        libstagefright_wfd              \
        libutils                        \
        liblog                          \

LOCAL_MODULE:= wfd

LOCAL_MODULE_TAGS := debug

include $(BUILD_EXECUTABLE)

endif

################################################################################
#add to build HDCP folder 
include $(call all-makefiles-under,$(LOCAL_PATH))
