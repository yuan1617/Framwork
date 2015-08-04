LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=                 \
    AAtomizer.cpp                 \
    ABitReader.cpp                \
    ABuffer.cpp                   \
    AHandler.cpp                  \
    AHierarchicalStateMachine.cpp \
    ALooper.cpp                   \
    ALooperRoster.cpp             \
    AMessage.cpp                  \
    ANetworkSession.cpp           \
    AString.cpp                   \
    ParsedMessage.cpp             \
    base64.cpp                    \
    hexdump.cpp

LOCAL_C_INCLUDES:= \
    frameworks/av/include/media/stagefright/foundation \
    frameworks/av/media/libstagefright/wifi-display/uibc \

LOCAL_SHARED_LIBRARIES := \
        libbinder         \
        libutils          \
        liblog						\
        libcutils

LOCAL_CFLAGS += -Wno-multichar -Werror

ifneq ($(TARGET_BUILD_VARIANT),user)
#For MTB support
LOCAL_SHARED_LIBRARIES += libmtb
LOCAL_C_INCLUDES += $(TOP)/vendor/mediatek/proprietary/external/mtb
LOCAL_CFLAGS += -DMTB_SUPPORT
endif

LOCAL_MODULE:= libstagefright_foundation



include $(BUILD_SHARED_LIBRARY)
