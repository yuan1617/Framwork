/* Copyright Statement:
 *
 * This software/firmware and related documentation ("MediaTek Software") are
 * protected under relevant copyright laws. The information contained herein
 * is confidential and proprietary to MediaTek Inc. and/or its licensors.
 * Without the prior written permission of MediaTek inc. and/or its licensors,
 * any reproduction, modification, use or disclosure of MediaTek Software,
 * and information contained herein, in whole or in part, shall be strictly prohibited.
 *
 * MediaTek Inc. (C) 2010. All rights reserved.
 *
 * BY OPENING THIS FILE, RECEIVER HEREBY UNEQUIVOCALLY ACKNOWLEDGES AND AGREES
 * THAT THE SOFTWARE/FIRMWARE AND ITS DOCUMENTATIONS ("MEDIATEK SOFTWARE")
 * RECEIVED FROM MEDIATEK AND/OR ITS REPRESENTATIVES ARE PROVIDED TO RECEIVER ON
 * AN "AS-IS" BASIS ONLY. MEDIATEK EXPRESSLY DISCLAIMS ANY AND ALL WARRANTIES,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE OR NONINFRINGEMENT.
 * NEITHER DOES MEDIATEK PROVIDE ANY WARRANTY WHATSOEVER WITH RESPECT TO THE
 * SOFTWARE OF ANY THIRD PARTY WHICH MAY BE USED BY, INCORPORATED IN, OR
 * SUPPLIED WITH THE MEDIATEK SOFTWARE, AND RECEIVER AGREES TO LOOK ONLY TO SUCH
 * THIRD PARTY FOR ANY WARRANTY CLAIM RELATING THERETO. RECEIVER EXPRESSLY ACKNOWLEDGES
 * THAT IT IS RECEIVER'S SOLE RESPONSIBILITY TO OBTAIN FROM ANY THIRD PARTY ALL PROPER LICENSES
 * CONTAINED IN MEDIATEK SOFTWARE. MEDIATEK SHALL ALSO NOT BE RESPONSIBLE FOR ANY MEDIATEK
 * SOFTWARE RELEASES MADE TO RECEIVER'S SPECIFICATION OR TO CONFORM TO A PARTICULAR
 * STANDARD OR OPEN FORUM. RECEIVER'S SOLE AND EXCLUSIVE REMEDY AND MEDIATEK'S ENTIRE AND
 * CUMULATIVE LIABILITY WITH RESPECT TO THE MEDIATEK SOFTWARE RELEASED HEREUNDER WILL BE,
 * AT MEDIATEK'S OPTION, TO REVISE OR REPLACE THE MEDIATEK SOFTWARE AT ISSUE,
 * OR REFUND ANY SOFTWARE LICENSE FEES OR SERVICE CHARGE PAID BY RECEIVER TO
 * MEDIATEK FOR SUCH MEDIATEK SOFTWARE AT ISSUE.
 *
 * The following software/firmware and/or related documentation ("MediaTek Software")
 * have been modified by MediaTek Inc. All revisions are subject to any receiver's
 * applicable license agreements with MediaTek Inc.
 */

/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// for INT64_MAX 
#undef __STRICT_ANSI__
#define __STDINT_LIMITS
#define __STDC_LIMIT_MACROS
#include <stdint.h>
#include "MtkSDPExtractor.h"
#include "ASessionDescription.h"
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/DataSource.h>

namespace android {

MtkSDPExtractor::MtkSDPExtractor(const sp<DataSource> &source)
    :mMetaData(new MetaData), mSessionDesc(new ASessionDescription)
{
    off64_t fileSize;
    if (source->getSize(&fileSize) != OK) {
        fileSize = 4096 * 2;
        ALOGW("no lenth of SDP, try max of %lld", fileSize);
    }

    void* data = malloc(fileSize);
    if (data != NULL) {
        ssize_t n = source->readAt(0, data, fileSize);
        if (n > 0) {
            if (n != fileSize) {
                ALOGW("data read may be incomplete %d vs %lld", (int)n, fileSize);
            }
            mSessionDesc->setTo(data, n);
        }
        free(data);
    } else {
        ALOGW("out of memory in MtkSDPExtractor");
    }

    mMetaData->setCString(kKeyMIMEType, MEDIA_MIMETYPE_APPLICATION_SDP);
    mMetaData->setPointer(kKeySDP, mSessionDesc.get());
}

size_t MtkSDPExtractor::countTracks() {
    return 0;
}

sp<MediaSource> MtkSDPExtractor::getTrack(size_t index) {
    if(index == 0) ALOGI("WR");
    return NULL;
}

sp<MetaData> MtkSDPExtractor::getTrackMetaData(size_t index, uint32_t flags) {
	  if(index == 0 || flags == 0) ALOGI("WR");
    return NULL;
}

sp<MetaData> MtkSDPExtractor::getMetaData() {
    return mMetaData;
}

bool SniffSDP(
        const sp<DataSource> &source, String8 *mimeType, float *confidence,
        sp<AMessage> *meta) {
     if(meta == NULL) ALOGI("WR");
    const int testLen = 7;
    uint8_t line[testLen];
    ssize_t n = source->readAt(0, line, testLen);
    if (n < testLen)
        return false;

    const char* nline = "v=0\no=";
    const char* rnline = "v=0\r\no=";

    if (!memcmp(line, nline, sizeof(nline) - 1) ||
            !memcmp(line, rnline, sizeof(rnline) - 1)) {
        *mimeType = MEDIA_MIMETYPE_APPLICATION_SDP;
        *confidence = 0.5;
        return true;
    }

    return false;
}

}  // namespace android
