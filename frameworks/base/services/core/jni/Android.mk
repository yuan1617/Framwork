# This file is included by the top level services directory to collect source
# files
LOCAL_REL_DIR := core/jni

LOCAL_CFLAGS += -Wno-unused-parameter

ifeq ($(USE_OPENGL_RENDERER),true)
    LOCAL_CFLAGS += -DUSE_OPENGL_RENDERER
endif

LOCAL_SRC_FILES += \
    $(LOCAL_REL_DIR)/com_android_server_AlarmManagerService.cpp \
    $(LOCAL_REL_DIR)/com_android_server_am_BatteryStatsService.cpp \
    $(LOCAL_REL_DIR)/com_android_server_AssetAtlasService.cpp \
    $(LOCAL_REL_DIR)/com_android_server_connectivity_Vpn.cpp \
    $(LOCAL_REL_DIR)/com_android_server_ConsumerIrService.cpp \
    $(LOCAL_REL_DIR)/com_android_server_hdmi_HdmiCecController.cpp \
    $(LOCAL_REL_DIR)/com_android_server_input_InputApplicationHandle.cpp \
    $(LOCAL_REL_DIR)/com_android_server_input_InputManagerService.cpp \
    $(LOCAL_REL_DIR)/com_android_server_input_InputWindowHandle.cpp \
    $(LOCAL_REL_DIR)/com_android_server_lights_LightsService.cpp \
    $(LOCAL_REL_DIR)/com_android_server_location_GpsLocationProvider.cpp \
    $(LOCAL_REL_DIR)/com_android_server_location_FlpHardwareProvider.cpp \
    $(LOCAL_REL_DIR)/com_android_server_power_PowerManagerService.cpp \
    $(LOCAL_REL_DIR)/com_android_server_SerialService.cpp \
    $(LOCAL_REL_DIR)/com_android_server_SystemServer.cpp \
    $(LOCAL_REL_DIR)/com_android_server_tv_TvInputHal.cpp \
    $(LOCAL_REL_DIR)/com_android_server_UsbDeviceManager.cpp \
    $(LOCAL_REL_DIR)/com_android_server_UsbHostManager.cpp \
    $(LOCAL_REL_DIR)/com_android_server_VibratorService.cpp \
    $(LOCAL_REL_DIR)/com_android_server_PersistentDataBlockService.cpp \
    $(LOCAL_REL_DIR)/com_android_server_PerfService.cpp \
    $(LOCAL_REL_DIR)/com_mediatek_perfservice_PerfServiceManager.cpp \
    $(LOCAL_REL_DIR)/com_mediatek_hdmi_MtkHdmiManagerService.cpp \
    $(LOCAL_REL_DIR)/com_android_server_display_DisplayPowerController.cpp \
    $(LOCAL_REL_DIR)/onload.cpp

ifneq (yes,$(MTK_BSP_PACKAGE))
LOCAL_SRC_FILES += \
	$(LOCAL_REL_DIR)/com_android_internal_app_ShutdownManager.cpp
endif

include external/stlport/libstlport.mk

LOCAL_C_INCLUDES += \
    $(JNI_H_INCLUDE) \
    frameworks/base/services \
    frameworks/base/libs \
    frameworks/base/libs/hwui \
    frameworks/base/core/jni \
    frameworks/native/services \
    libcore/include \
    libcore/include/libsuspend \
	$(call include-path-for, libhardware)/hardware \
	$(call include-path-for, libhardware_legacy)/hardware_legacy \

LOCAL_SHARED_LIBRARIES += \
    libandroid_runtime \
    libandroidfw \
    libbinder \
    libcutils \
    liblog \
    libhardware \
    libhardware_legacy \
    libnativehelper \
    libutils \
    libui \
    libinput \
    libinputflinger \
    libinputservice \
    libsensorservice \
    libskia \
    libgui \
    libusbhost \
    libsuspend \
    libdl \
    libEGL \
    libGLESv2 \
    libnetutils \
	libmedia

# Add for HWUI program binaries support
ifeq ($(USE_OPENGL_RENDERER),true)
    LOCAL_SHARED_LIBRARIES += libhwui
endif

ifeq ($(strip $(BOARD_USES_MTK_AUDIO)),true)
  LOCAL_CFLAGS += -DMTK_VIBSPK_OPTION_SUPPORT
  
   LOCAL_SHARED_LIBRARIES += \
        libmtkplayer
   LOCAL_C_INCLUDES += $(TOP)/$(MTK_PATH_SOURCE)/frameworks/av/media/libs
endif

ifeq ($(MTK_VIBSPK_SUPPORT),yes)
  LOCAL_CFLAGS += -DMTK_VIBSPK_SUPPORT
endif

ifeq ($(MTK_PERFSERVICE_SUPPORT),yes)
  LOCAL_CFLAGS += -DMTK_PERFSERVICE_SUPPORT
endif

# Add for MTK HDMI
ifeq ($(MTK_DRM_KEY_MNG_SUPPORT), yes)
LOCAL_SHARED_LIBRARIES += libcutils libnetutils libc
LOCAL_SHARED_LIBRARIES += libtz_uree
LOCAL_STATIC_LIBRARIES += liburee_meta_drmkeyinstall
endif

ifeq ($(MTK_DRM_KEY_MNG_SUPPORT), yes)
LOCAL_C_INCLUDES +=  $(MTK_PATH_SOURCE)/mediatek/trustzone/mtk/include/tz_cross/
endif
# Add for MTK HDMI end

ifeq ($(MTK_AAL_SUPPORT),yes)
    LOCAL_C_INCLUDES += \
        $(MTK_PATH_PLATFORM)/hardware/aal/inc

    LOCAL_SHARED_LIBRARIES += \
        libaal
endif

ifeq ($(strip $(MTK_SENSOR_HUB_SUPPORT)),yes)
    LOCAL_C_INCLUDES += \
        $(MTK_PATH_SOURCE)/frameworks/native/services \
        $(MTK_PATH_SOURCE)/frameworks/native/include  \
        $(MTK_PATH_SOURCE)/hardware/sensorhub

    LOCAL_SHARED_LIBRARIES += \
        libsensorhubservice

    LOCAL_CFLAGS += -DMTK_SENSOR_HUB_SUPPORT
endif

