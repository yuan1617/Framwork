LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=                 \
        TextDescriptions.cpp      \
        TimedTextDriver.cpp       \
        TimedText3GPPSource.cpp \
        TimedTextSource.cpp       \
        TimedTextSRTSource.cpp    \
        TimedTextPlayer.cpp    \
        TimedTextVOBSUBSource.cpp    \
        TimedTextASSSource.cpp    \
        TimedTextVOBSubtitleParser.cpp    \
        TimedTextUtil.cpp    \
        MagicString.cpp    \
        FileCacheManager.cpp  \
        StructTime.cpp    \
        

LOCAL_CFLAGS += -Wno-multichar


LOCAL_C_INCLUDES:= \
        $(TOP)/frameworks/av/include/media/stagefright/timedtext \
        $(TOP)/frameworks/av/media/libstagefright \
        $(TOP)/frameworks/av/include/media/stagefright

######################## MTK_USE_ANDROID_MM_DEFAULT_CODE ######################
ifneq ($(strip $(MTK_USE_ANDROID_MM_DEFAULT_CODE)),yes)        
LOCAL_MTK_PATH:=../../../../../vendor/mediatek/proprietary/frameworks/av/media/libstagefright/timedtext/

ifeq ($(strip $(MTK_SUBTITLE_SUPPORT)),yes)
LOCAL_C_INCLUDES += $(TOP)/vendor/mediatek/proprietary/frameworks/av/media/libstagefright/timedtext/
LOCAL_SRC_FILES += \
        $(LOCAL_MTK_PATH)DvbPage.cpp               \
        $(LOCAL_MTK_PATH)DVbPageMgr.cpp            \
        $(LOCAL_MTK_PATH)DvbClut.cpp               \
        $(LOCAL_MTK_PATH)DvbClutMgr.cpp            \
        $(LOCAL_MTK_PATH)DvbObject.cpp             \
        $(LOCAL_MTK_PATH)DvbObjectMgr.cpp          \
        $(LOCAL_MTK_PATH)DvbRegion.cpp             \
        $(LOCAL_MTK_PATH)DvbRegionMgr.cpp          \
        $(LOCAL_MTK_PATH)DvbDds.cpp                \
        $(LOCAL_MTK_PATH)DVBDdsMgr.cpp             \
        $(LOCAL_MTK_PATH)dvbparser.cpp             \
        $(LOCAL_MTK_PATH)TimedTextDVBSource.cpp	  
       
endif

endif    # MTK_USE_ANDROID_MM_DEFAULT_CODE
######################## MTK_USE_ANDROID_MM_DEFAULT_CODE ######################
LOCAL_MODULE:= libstagefright_timedtext

include $(BUILD_STATIC_LIBRARY)
