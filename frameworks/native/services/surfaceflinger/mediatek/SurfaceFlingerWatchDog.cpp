/* Copyright Statement:
 *
 * This software/firmware and related documentation ("MediaTek Software") are
 * protected under relevant copyright laws. The information contained herein is
 * confidential and proprietary to MediaTek Inc. and/or its licensors. Without
 * the prior written permission of MediaTek inc. and/or its licensors, any
 * reproduction, modification, use or disclosure of MediaTek Software, and
 * information contained herein, in whole or in part, shall be strictly
 * prohibited.
 *
 * MediaTek Inc. (C) 2010. All rights reserved.
 *
 * BY OPENING THIS FILE, RECEIVER HEREBY UNEQUIVOCALLY ACKNOWLEDGES AND AGREES
 * THAT THE SOFTWARE/FIRMWARE AND ITS DOCUMENTATIONS ("MEDIATEK SOFTWARE")
 * RECEIVED FROM MEDIATEK AND/OR ITS REPRESENTATIVES ARE PROVIDED TO RECEIVER
 * ON AN "AS-IS" BASIS ONLY. MEDIATEK EXPRESSLY DISCLAIMS ANY AND ALL
 * WARRANTIES, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE OR
 * NONINFRINGEMENT. NEITHER DOES MEDIATEK PROVIDE ANY WARRANTY WHATSOEVER WITH
 * RESPECT TO THE SOFTWARE OF ANY THIRD PARTY WHICH MAY BE USED BY,
 * INCORPORATED IN, OR SUPPLIED WITH THE MEDIATEK SOFTWARE, AND RECEIVER AGREES
 * TO LOOK ONLY TO SUCH THIRD PARTY FOR ANY WARRANTY CLAIM RELATING THERETO.
 * RECEIVER EXPRESSLY ACKNOWLEDGES THAT IT IS RECEIVER'S SOLE RESPONSIBILITY TO
 * OBTAIN FROM ANY THIRD PARTY ALL PROPER LICENSES CONTAINED IN MEDIATEK
 * SOFTWARE. MEDIATEK SHALL ALSO NOT BE RESPONSIBLE FOR ANY MEDIATEK SOFTWARE
 * RELEASES MADE TO RECEIVER'S SPECIFICATION OR TO CONFORM TO A PARTICULAR
 * STANDARD OR OPEN FORUM. RECEIVER'S SOLE AND EXCLUSIVE REMEDY AND MEDIATEK'S
 * ENTIRE AND CUMULATIVE LIABILITY WITH RESPECT TO THE MEDIATEK SOFTWARE
 * RELEASED HEREUNDER WILL BE, AT MEDIATEK'S OPTION, TO REVISE OR REPLACE THE
 * MEDIATEK SOFTWARE AT ISSUE, OR REFUND ANY SOFTWARE LICENSE FEES OR SERVICE
 * CHARGE PAID BY RECEIVER TO MEDIATEK FOR SUCH MEDIATEK SOFTWARE AT ISSUE.
 *
 * The following software/firmware and/or related documentation ("MediaTek
 * Software") have been modified by MediaTek Inc. All revisions are subject to
 * any receiver's applicable license agreements with MediaTek Inc.
 */

/*
 * Copyright (C) 2011 The Android Open Source Project
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

#include <inttypes.h>

#include <cutils/xlog.h>
#include <cutils/properties.h>
#include "cutils/debugger.h"

#include "SurfaceFlingerWatchDog.h"
#include "aee.h"

namespace android {

ANDROID_SINGLETON_STATIC_INSTANCE(SFWatchDog);

#define SW_WATCHDOG_TIMER       1000
#define SW_WATCHDOG_INIT_THRESHOLD   800L
#define SW_WATCHDOG_RTTCOUNT    10
#define RTT_FOLDER_PATH         "/data/anr/SF_RTT/"
#define RTT_FILE_NAME           "rtt_dump"
#define RTT_DUMP                (RTT_FOLDER_PATH RTT_FILE_NAME)

Mutex SFWatchDog::sLock(Mutex::PRIVATE);
sp<SFWatchDog> SFWatchDog::sInstance;

SFWatchDog::SFWatchDog() : Thread(false) {
    XLOGI("[%s]", __func__);
}

SFWatchDog::~SFWatchDog() {
    XLOGI("[%s]", __func__);

    mNodeList.clear();
}

void SFWatchDog::onFirstRef() {
    XLOGI("[%s]", __func__);
    mUpdateCount = 0;
    run("SFWatchDog", PRIORITY_BACKGROUND);
}

status_t SFWatchDog::readyToRun() {
    XLOGI("[%s]", __func__);

    mThreshold = SW_WATCHDOG_INIT_THRESHOLD;
    mTimer = SW_WATCHDOG_TIMER;
    mShowLog = false;

    char cmds[256];

    // create rtt folder
    snprintf(cmds, sizeof(cmds), "mkdir %s", RTT_FOLDER_PATH);
    system(cmds);

    return NO_ERROR;
}

// AED Exported Functions
static int aee_ioctl_wdt_kick(int value)
{
    int ret=0;
    int fd = open(AE_WDT_POWERKEY_DEVICE_PATH, O_RDONLY);
    if (fd < 0)	{
        XLOGD("[SF-WD] ERROR: open %s failed.\n", AE_WDT_DEVICE_PATH);
        return 1;
    } else {
        if (ioctl(fd, AEEIOCTL_WDT_KICK_POWERKEY, (int)value) != 0) {
            XLOGD("[SF-WD] ERROR: aee wdt kick powerkey ioctl failed.\n");
            close (fd);
            return 1;
        }
    }
    close (fd);
    return ret;
}

sp<SFWatchDog> SFWatchDog::getInstance() {
    Mutex::Autolock _l(sLock);
    static sp<SFWatchDog> instance = sInstance;
    if (instance.get() == 0) {
        instance = new SFWatchDog();
        sInstance = instance;
    }
    return instance;
}

bool SFWatchDog::threadLoop() {
    XLOGV("[%s]", __func__);

    {
        Mutex::Autolock _l(mScreenLock);
    }

    nsecs_t stopTime = 1;
    if (isSFThreadHang(&stopTime)) {
        char cmds[256];
        static uint32_t rtt_ct = SW_WATCHDOG_RTTCOUNT;
        if (rtt_ct > 0) {
            rtt_ct --;
        } else {
            XLOGD("[SF-WD] swap rtt dump file");

            // swap rtt dump file
            snprintf(cmds, sizeof(cmds), "mv %s.txt %s_1.txt", RTT_DUMP, RTT_DUMP);
            system(cmds);

            rtt_ct = SW_WATCHDOG_RTTCOUNT;
        }

        // append SurfaceFlinger rtt information to rtt file
        char filename[100];
        snprintf(filename, sizeof(filename), "%s.txt", RTT_DUMP);
        int fd = open(filename, O_CREAT | O_WRONLY | O_NOFOLLOW, 0666);  /* -rw-rw-rw- */
        if (fd < 0) {
            ALOGE("Can't open %s: %s\n", filename, strerror(errno));
            return true;
        }

        if (lseek(fd, 0, SEEK_END) < 0) {
            fprintf(stderr, "lseek: %s\n", strerror(errno));
        } else {
            dump_backtrace_to_file(getpid(), fd);
        }

        close(fd);

        XLOGD("[SF-WD] dump rtt file: %s.txt", RTT_DUMP);
        XLOGW("[SF-WD] ============================================");
    } else {
        stopTime = 1;
    }

    getProperty();

    char value[PROPERTY_VALUE_MAX];
    snprintf(value, sizeof(value), "%" PRId64 " ", stopTime);
    if (stopTime < 0 || stopTime >= 2147483247) {
        volatile nsecs_t tmpStopTime = stopTime;
        XLOGD("[SF-WD] tmpStopTime=(%" PRId64 ", %" PRId64 ")", tmpStopTime, stopTime);
        abort();
    }

    uint32_t ret = property_set("service.sf.status", value);
    if (mUpdateCount) {
        if (mShowLog)
            XLOGV("[SF-WD] mUpdateCount: %d", mUpdateCount);
#if 0
        aee_ioctl_wdt_kick(WDT_SETBY_SF);
#endif
        mUpdateCount = 0;
    }
    //else {
    //	XLOGV("[SF-WD] mUpdateCount not update!!!!!: %d", mUpdateCount);
    //	aee_ioctl_wdt_kick(WDT_SETBY_SF_NEED_NOT_UPDATE);
    //}
    usleep(mTimer * 1000);
    return true;
}

uint32_t SFWatchDog::registerNodeName(const char* name) {
    uint32_t index = 0;

    for (uint32_t i = 0; i < mNodeList.size(); i++) {
        if (name == mNodeList[i]->mName) {
            XLOGI("register an already registered name: %s (%s)", name, mNodeList[i]->mName.string());
            return i;
        }
    }

    index = mNodeList.size();
    sp<NodeElement> node = new NodeElement();
    node->mName = name;
    node->mStartTransactionTime = 0;
    mNodeList.add(node);

    XLOGI("[%s] name=%s, index=%d", __func__, name, index);

    return index;
}

bool SFWatchDog::isSFThreadHang(nsecs_t* pStopTime) {
    Mutex::Autolock _l(mLock);

    const nsecs_t now = systemTime();
    if (mShowLog) {
        XLOGI("[SF-WD] Threshold: %" PRId64 " ns NodeList size: %d", mThreshold * 1000 * 1000, mNodeList.size());
        for (uint32_t i = 0; i < mNodeList.size(); i++) {
            XLOGI("[SF-WD]   [%s] last transaction: %" PRId64 ", now: %" PRId64 ".",
                    mNodeList[i]->mName.string(),
                    mNodeList[i]->mStartTransactionTime, now);
        }
    }

    *pStopTime = INT64_MAX;
    for (uint32_t i = 0; i < mNodeList.size(); i++) {
        if ((mNodeList[i]->mStartTransactionTime != 0 || mThreshold == 0) &&
            mThreshold * 1000 * 1000 < now - mNodeList[i]->mStartTransactionTime) {
            // we could set mThreshold as zero for debugging
            *pStopTime = *pStopTime < now - mNodeList[i]->mStartTransactionTime ?
                *pStopTime : now - mNodeList[i]->mStartTransactionTime;
        }
    }

    if (*pStopTime != INT64_MAX) { // maybe hang
        XLOGW("[SF-WD] ============================================");
        XLOGW("[SF-WD] detect SF maybe hang, Threshold: %" PRId64 " ns StopTime: %" PRId64 " ns, List(size: %d):", mThreshold * 1000 * 1000,
                *pStopTime, mNodeList.size());
        for (uint32_t i = 0; i < mNodeList.size(); i++) {
            if (mNodeList[i]->mStartTransactionTime == 0) {
                XLOGI("[SF-WD]   [%s] wait event", mNodeList[i]->mName.string());
            } else {
                XLOGW("[SF-WD]   [%s] stopTime= %" PRId64 "ns", mNodeList[i]->mName.string(), now - mNodeList[i]->mStartTransactionTime);
            }
        }

        if (mThreshold == 0) {
            *pStopTime = 1;
        }
        return true;
    } else {
        *pStopTime = 1;
        return false;
    }
}

void SFWatchDog::markStartTransactionTime(uint32_t index) {
    Mutex::Autolock _l(mLock);

    if (index >= mNodeList.size()) {
        XLOGE("[unmarkStartTransactionTime] index=%d > Node list size=%d", index, mNodeList.size());
        return;
    }

    mNodeList[index]->mStartTransactionTime = systemTime();
    mUpdateCount ++;
    if (mShowLog)
        XLOGV("[%s] name=%s, index=%d, time = %" PRId64 "", __func__, mNodeList[index]->mName.string(), index, mNodeList[index]->mStartTransactionTime);
}

void SFWatchDog::unmarkStartTransactionTime(uint32_t index) {
    Mutex::Autolock _l(mLock);

    if (index >= mNodeList.size()) {
        XLOGE("[unmarkStartTransactionTime] index=%d > Node list size=%d", index, mNodeList.size());
        return;
    }

    mNodeList[index]->mStartTransactionTime = 0;

    if (mShowLog)
        XLOGV("[%s] name=%s, index=%d, time = %" PRId64 "", __func__, mNodeList[index]->mName.string(), index, mNodeList[index]->mStartTransactionTime);
}

void SFWatchDog::screenReleased() {
    mScreenLock.lock();

    if (mShowLog)
        XLOGD("[SF-WD] screen release");
}

void SFWatchDog::screenAcquired() {
    mScreenLock.unlock();

    if (mShowLog)
        XLOGD("[SF-WD] acquire");
}

void SFWatchDog::getProperty() {
    char value[PROPERTY_VALUE_MAX];

    if (property_get("debug.sf.wdthreshold", value, NULL) > 0) {
        nsecs_t threshold = static_cast<nsecs_t>(atoi(value));
        if (threshold != mThreshold) {
            XLOGD("SF watch dog change threshold from %" PRId64 " --> %" PRId64 ".", mThreshold, threshold);
            mThreshold = threshold;
        }
    }

    if (property_get("debug.sf.wdtimer", value, NULL) > 0) {
        int timer = atoi(value);
        if (timer != (int)mTimer) {
            XLOGD("SF watch dog change timer from %" PRId64 " --> %" PRId64 ".", mTimer, timer);
            mTimer = timer;
        }
    }

    property_get("debug.sf.wdlog", value, "0");
    mShowLog = atoi(value);
}

void SFWatchDog::setThreshold(const nsecs_t& time) {
    Mutex::Autolock _l(mLock);
    XLOGD("SF watch dog change threshold from %" PRId64 " --> %" PRId64 ".", mThreshold, time);
    mThreshold = time;
}

nsecs_t SFWatchDog::getThreshold() {
    Mutex::Autolock _l(mLock);
    return mThreshold;
}
}; // namespace android
