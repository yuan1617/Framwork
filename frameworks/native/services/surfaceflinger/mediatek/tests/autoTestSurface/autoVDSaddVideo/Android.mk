LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_C_INCLUDES := \
	vendor/mediatek/proprietary/hardware/gralloc_extra/include \
	vendor/mediatek/proprietary/hardware/include \

LOCAL_SRC_FILES:= \
	autoVDSaddVideo.cpp

LOCAL_SHARED_LIBRARIES := \
	libcutils \
	libutils \
	libbinder \
    libui \
    libgui \
	libgralloc_extra

LOCAL_MODULE:= autoVDSaddVideoUnitTest

LOCAL_MODULE_TAGS := tests

include $(BUILD_EXECUTABLE)
