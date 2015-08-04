LOCAL_PATH:= $(call my-dir)

#
# libmediaplayerservice
#

include $(CLEAR_VARS)

LOCAL_SRC_FILES:=               \
    ActivityManager.cpp         \
    Crypto.cpp                  \
    Drm.cpp                     \
    HDCP.cpp                    \
    MediaPlayerFactory.cpp      \
    MediaPlayerService.cpp      \
    MediaRecorderClient.cpp     \
    MetadataRetrieverClient.cpp \
    MidiFile.cpp                \
    MidiMetadataRetriever.cpp   \
    RemoteDisplay.cpp           \
    SharedLibrary.cpp           \
    StagefrightPlayer.cpp       \
    StagefrightRecorder.cpp     \
    TestPlayerStub.cpp          \
    VideoFrameScheduler.cpp     \

LOCAL_SHARED_LIBRARIES :=       \
    libbinder                   \
    libcamera_client            \
    libcrypto                   \
    libcutils                   \
    libdrmframework             \
    liblog                      \
    libdl                       \
    libgui                      \
    libmedia                    \
    libsonivox                  \
    libstagefright              \
    libstagefright_foundation   \
    libstagefright_httplive     \
    libstagefright_omx          \
    libstagefright_wfd          \
    libutils                    \
    libcutils                   \
    libvorbisidec               \

ifeq ($(strip $(MTK_BSP_PACKAGE)),no)
LOCAL_SHARED_LIBRARIES += libcustom_prop

endif

ifeq ($(strip $(MTK_DX_HDCP_SUPPORT)),yes)
LOCAL_SHARED_LIBRARIES += libstagefright_hdcp
endif

LOCAL_STATIC_LIBRARIES :=       \
    libstagefright_nuplayer     \
    libstagefright_rtsp         \

LOCAL_C_INCLUDES :=                                                 \
    $(TOP)/frameworks/av/media/libstagefright/include               \
    $(TOP)/frameworks/av/media/libstagefright/rtsp                  \
    $(TOP)/frameworks/av/media/libstagefright/webm                  \
    $(TOP)/frameworks/native/include/media/openmax                  \
    $(TOP)/external/tremolo/Tremolo                                 \
    $(TOP)/frameworks/native/include

# For MTK Sink feature
ifeq ($(strip $(MTK_WFD_SINK_SUPPORT)),yes)       		
LOCAL_CFLAGS += -DMTK_WFD_SINK_SUPPORT

# For MTK Sink UIBC feature
ifeq ($(strip $(MTK_WFD_SINK_UIBC_SUPPORT)),yes)       		
LOCAL_CFLAGS += -DMTK_WFD_SINK_UIBC_SUPPORT
endif
endif

ifeq ($(strip $(MTK_USE_ANDROID_MM_DEFAULT_CODE)),yes)
LOCAL_C_INCLUDES+= \
	$(TOP)/frameworks/av/media/libstagefright/wifi-display
else
LOCAL_C_INCLUDES += $(TOP)/$(MTK_ROOT)/kernel/include/linux/vcodec \

LOCAL_SHARED_LIBRARIES += \
        libvcodecdrv
LOCAL_C_INCLUDES+= \
 		$(TOP)/frameworks/av/media/libstagefright/wifi-display \
  	$(TOP)/$(MTK_PATH_SOURCE)/frameworks/av/media/libs \
  	$(TOP)/$(MTK_PATH_SOURCE)/frameworks/av/include


#LOCAL_STATIC_LIBRARIES += libwriter_mtk

LOCAL_SRC_FILES+= \
  NotifySender.cpp

LOCAL_CFLAGS += -DNOTIFYSENDER_ENABLE

ifeq ($(strip $(MTK_DRM_APP)),yes)
    LOCAL_CFLAGS += -DMTK_DRM_APP
    LOCAL_C_INCLUDES += \
        $(TOP)/vendor/mediatek/proprietary/frameworks/av/include \
        external/stlport/stlport \
        bionic
		LOCAL_SHARED_LIBRARIES += \
        libdrmmtkutil
endif

ifeq ($(HAVE_ADPCMENCODE_FEATURE),yes)
  LOCAL_CFLAGS += -DHAVE_ADPCMENCODE_FEATURE
endif
ifneq ($(strip $(HAVE_MATV_FEATURE))_$(strip $(MTK_FM_SUPPORT)), no_no)
  LOCAL_SHARED_LIBRARIES += libmtkplayer
ifeq ($(strip $(BOARD_USES_MTK_AUDIO)),true)  
  LOCAL_C_INCLUDES+= \
    $(TOP)/frameworks/av/include
endif
endif

ifeq ($(HAVE_MATV_FEATURE),yes)
  LOCAL_CFLAGS += -DMTK_MATV_ENABLE
endif	
ifeq ($(MTK_FM_SUPPORT),yes)
  LOCAL_CFLAGS += -DMTK_FM_ENABLE
endif

ifeq ($(strip $(MTK_SLOW_MOTION_VIDEO_SUPPORT)),yes)	
LOCAL_C_INCLUDES +=  $(TOP)/$(MTK_PATH_SOURCE)/frameworks/av/media/libstagefright/include
endif

endif

LOCAL_MODULE:= libmediaplayerservice

LOCAL_32_BIT_ONLY := true

include $(BUILD_SHARED_LIBRARY)

include $(call all-makefiles-under,$(LOCAL_PATH))
