LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_CLANG := true
LOCAL_SDK_VERSION := 8

LOCAL_SRC_FILES:= \
    android_renderscript_RenderScript.cpp

#GMS rename
ifdef BUILD_GMS
ifeq ($(strip $(BUILD_GMS)), yes)
LOCAL_SHARED_LIBRARIES := \
        libRSSupport_old \
        libjnigraphics
else
LOCAL_SHARED_LIBRARIES := \
        libRSSupport \
        libjnigraphics
endif
else
LOCAL_SHARED_LIBRARIES := \
        libRSSupport \
        libjnigraphics
endif

LOCAL_STATIC_LIBRARIES := \
        libcutils

#GMS Rename
ifdef BUILD_GMS
ifeq ($(strip $(BUILD_GMS)), yes)
rs_generated_include_dir := $(call generated-sources-dir-for,SHARED_LIBRARIES,libRSSupport_old,,)
else
rs_generated_include_dir := $(call generated-sources-dir-for,SHARED_LIBRARIES,libRSSupport,,)
endif
else
rs_generated_include_dir := $(call generated-sources-dir-for,SHARED_LIBRARIES,libRSSupport,,)
endif

LOCAL_C_INCLUDES += \
	$(JNI_H_INCLUDE) \
	frameworks/rs \
	$(rs_generated_include_dir)

LOCAL_CFLAGS += -Wno-unused-parameter -U_FORTIFY_SOURCE

LOCAL_ADDITIONAL_DEPENDENCIES := $(addprefix $(rs_generated_include_dir)/,rsgApiFuncDecl.h)

#GMS rename
ifdef BUILD_GMS
ifeq ($(strip $(BUILD_GMS)), yes)
LOCAL_MODULE:= librsjni_old
else
LOCAL_MODULE:= librsjni
endif
else
LOCAL_MODULE:= librsjni
endif

LOCAL_ADDITIONAL_DEPENDENCIES += $(rs_generated_source)
LOCAL_MODULE_TAGS := optional

#GMS rename
ifdef BUILD_GMS
ifeq ($(strip $(BUILD_GMS)), yes)
LOCAL_REQUIRED_MODULES := libRSSupport_old
else
LOCAL_REQUIRED_MODULES := libRSSupport
endif
else
LOCAL_REQUIRED_MODULES := libRSSupport
endif

LOCAL_32_BIT_ONLY := true

include $(BUILD_SHARED_LIBRARY)
