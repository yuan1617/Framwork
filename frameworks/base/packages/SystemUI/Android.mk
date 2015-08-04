LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := $(call all-java-files-under, src) \
    src/com/android/systemui/EventLogTags.logtags

LOCAL_STATIC_JAVA_LIBRARIES := Keyguard
LOCAL_STATIC_JAVA_LIBRARIES += com.mediatek.systemui.ext
LOCAL_JAVA_LIBRARIES := telephony-common
LOCAL_JAVA_LIBRARIES += mediatek-framework

LOCAL_PACKAGE_NAME := SystemUI
LOCAL_MULTILIB := 32
LOCAL_CERTIFICATE := platform
LOCAL_PRIVILEGED_MODULE := true

LOCAL_PROGUARD_FLAG_FILES := proguard.flags

LOCAL_RESOURCE_DIR := \
    frameworks/base/packages/Keyguard/res \
    $(LOCAL_PATH)/res
LOCAL_AAPT_FLAGS := --auto-add-overlay --extra-packages com.android.keyguard
LOCAL_AAPT_FLAGS += --extra-packages com.mediatek.keyguard.ext

include $(BUILD_PACKAGE)

include $(call all-makefiles-under,$(LOCAL_PATH))
