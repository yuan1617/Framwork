#
# Copyright (C) 2010 The Android Open Source Project
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
#
LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
    main_drmserver.cpp \
    DrmManager.cpp \
    DrmManagerService.cpp

LOCAL_SHARED_LIBRARIES := \
    libmedia \
    libutils \
    liblog \
    libbinder \
    libdl

ifeq ($(strip $(MTK_DRM_APP)),yes)
  ifeq ($(strip $(MTK_OMADRM_SUPPORT)),yes)
    LOCAL_CFLAGS += -DMTK_OMA_DRM_SUPPORT
    LOCAL_SHARED_LIBRARIES += \
        libdrmmtkutil \
        libdrmmtkwhitelist \
        libstlport
  else ifeq ($(strip $(MTK_MOBILE_MANAGEMENT)),yes)
    LOCAL_CFLAGS += -DMTK_CTA_DRM_SUPPORT
    LOCAL_SHARED_LIBRARIES += \
        libdrmmtkutil \
        libdrmmtkwhitelist \
        libstlport
  endif
else ifeq ($(strip $(MTK_WVDRM_SUPPORT)),yes)
  LOCAL_CFLAGS += -DMTK_WV_DRM_SUPPORT
  LOCAL_SHARED_LIBRARIES += \
      libdrmmtkutil \
      libdrmmtkwhitelist \
      libstlport
endif

LOCAL_STATIC_LIBRARIES := libdrmframeworkcommon

LOCAL_C_INCLUDES := \
    $(TOP)/frameworks/av/include \
    $(TOP)/frameworks/av/drm/libdrmframework/include \
    $(TOP)/frameworks/av/drm/libdrmframework/plugins/common/include

ifeq ($(strip $(MTK_DRM_APP)),yes)
  LOCAL_C_INCLUDES += \
      $(MTK_PATH_SOURCE)/frameworks/av/include \
      external/stlport/stlport \
      bionic
else ifeq ($(strip $(MTK_WVDRM_SUPPORT)),yes)
  LOCAL_C_INCLUDES += \
      $(MTK_PATH_SOURCE)/frameworks/av/include \
      external/stlport/stlport \
      bionic
endif

LOCAL_MODULE:= drmserver

LOCAL_MODULE_TAGS := optional

LOCAL_32_BIT_ONLY := true

include $(BUILD_EXECUTABLE)
