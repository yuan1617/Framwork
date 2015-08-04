LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_C_INCLUDES := \
  $(TOP)/$(MTK_PATH_SOURCE)/hardware/include \
	$(TOP)/$(MTK_PATH_SOURCE)/hardware/gralloc_extra/include

LOCAL_SRC_FILES:= \
	surface.cpp

LOCAL_SHARED_LIBRARIES := \
	libcutils \
	libutils \
	libbinder \
    libui \
    libgui \
  libhardware
    
LOCAL_STATIC_LIBRARIES := \
  libgralloc_extra

LOCAL_MODULE:= test-surface

LOCAL_MODULE_TAGS := tests

include $(BUILD_EXECUTABLE)
