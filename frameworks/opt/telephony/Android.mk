# Copyright (C) 2011 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# enable this build only when platform library is available
ifeq ($(TARGET_BUILD_JAVA_SUPPORT_LEVEL),platform)

LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_AIDL_INCLUDES := $(LOCAL_PATH)/src/java
LOCAL_SRC_FILES := $(call all-java-files-under, src/java) \
	$(call all-Iaidl-files-under, src/java) \
	$(call all-logtags-files-under, src/java)

LOCAL_JAVA_LIBRARIES := voip-common ims-common
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE := telephony-common

include $(BUILD_JAVA_LIBRARY)

ifeq ($(strip $(BUILD_MTK_API_DEP)), yes)
# telephony-common API table.
# ============================================================
LOCAL_MODULE := telephony-common-api

LOCAL_JAVA_LIBRARIES += $(LOCAL_STATIC_JAVA_LIBRARIES) okhttp
LOCAL_MODULE_CLASS := JAVA_LIBRARIES

LOCAL_DROIDDOC_OPTIONS:= \
		-api $(TARGET_OUT_COMMON_INTERMEDIATES)/PACKAGING/telephony-common-api.txt \
		-nodocs \
		-hidden

include $(BUILD_DROIDDOC)
endif

# Include subdirectory makefiles
# ============================================================
include $(call all-makefiles-under,$(LOCAL_PATH))

endif # JAVA platform
