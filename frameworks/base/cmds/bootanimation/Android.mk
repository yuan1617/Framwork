LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)


LOCAL_SRC_FILES:= \
	bootanimation_main.cpp \
	AudioPlayer.cpp \
	BootAnimation.cpp

LOCAL_CFLAGS += -DGL_GLEXT_PROTOTYPES -DEGL_EGLEXT_PROTOTYPES

LOCAL_C_INCLUDES += external/tinyalsa/include

LOCAL_SHARED_LIBRARIES := \
	libcutils \
	liblog \
	libandroidfw \
	libutils \
	libbinder \
        libui \
	libskia \
        libEGL \
        libGLESv2 \
        libETC1 \
        libGLESv1_CM \
        libgui \
        libmedia \
        libtinyalsa

#add for regional phone
ifeq ($(MTK_TER_SERVICE),yes)
LOCAL_SHARED_LIBRARIES += libterservice
LOCAL_C_INCLUDES += $(MTK_PATH_SOURCE)/hardware/terservice/include/
endif
LOCAL_C_INCLUDES += $(TOP)/$(MTK_ROOT)/frameworks-ext/native/include
LOCAL_C_INCLUDES += external/skia/include
LOCAL_MODULE:= bootanimation

ifdef TARGET_32_BIT_SURFACEFLINGER
LOCAL_32_BIT_ONLY := true
endif

include $(BUILD_EXECUTABLE)
