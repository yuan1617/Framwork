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
#define LOG_TAG "test-surface"

#include "graphics_mtk_defs.h"
#include "gralloc_mtk_defs.h"

#include <cutils/memory.h>

#include <utils/Log.h>

#include <binder/IPCThreadState.h>
#include <binder/ProcessState.h>
#include <binder/IServiceManager.h>

#include <ui/GraphicBuffer.h>
#include <gui/Surface.h>
#include <gui/ISurfaceComposer.h>
#include <gui/SurfaceComposerClient.h>
#include <cutils/properties.h>

#include <ui/DisplayInfo.h>
#include <ui/GraphicBufferExtra.h>
#include <ui/gralloc_extra.h>

using namespace android;

struct FRAME {
    char name[128];     // file name
    uint32_t w;         // width
    uint32_t s;         // stride
    uint32_t h;         // height
    uint32_t fmt;       // format
    uint32_t api;       // api connection type
    uint32_t usageEx;
};

FRAME test_frames[] = {
    //{"/data/LGE.yv12",                  400,  416,  240, HAL_PIXEL_FORMAT_YV12,       NATIVE_WINDOW_API_MEDIA,   GRALLOC_EXTRA_BIT_TYPE_VIDEO},
    {"/data/BigShips_1280x720_1.i420", 1280, 1280,  720, HAL_PIXEL_FORMAT_I420,       NATIVE_WINDOW_API_MEDIA,   GRALLOC_EXTRA_BIT_TYPE_VIDEO},
    {"/data/football_qvga_1.i420",      320,  320,  240, HAL_PIXEL_FORMAT_I420,       NATIVE_WINDOW_API_MEDIA,   GRALLOC_EXTRA_BIT_TYPE_VIDEO},
    {"/data/indoor_slow_1.i420",        848,  848,  480, HAL_PIXEL_FORMAT_I420,       NATIVE_WINDOW_API_MEDIA,   GRALLOC_EXTRA_BIT_TYPE_VIDEO},
    {"/data/Kimono1_1920x1088_1.i420", 1920, 1920, 1088, HAL_PIXEL_FORMAT_I420,       NATIVE_WINDOW_API_MEDIA,   GRALLOC_EXTRA_BIT_TYPE_VIDEO},
    {"/data/mobile_qcif_1.i420",        176,  176,  144, HAL_PIXEL_FORMAT_I420,       NATIVE_WINDOW_API_MEDIA,   GRALLOC_EXTRA_BIT_TYPE_VIDEO},
    {"/data/newyork_640x368_1.i420",    640,  640,  368, HAL_PIXEL_FORMAT_I420,       NATIVE_WINDOW_API_MEDIA,   GRALLOC_EXTRA_BIT_TYPE_VIDEO},
    {"/data/out_176_144.i420",          176,  176,  144, HAL_PIXEL_FORMAT_I420,       NATIVE_WINDOW_API_MEDIA,   GRALLOC_EXTRA_BIT_TYPE_VIDEO},
    {"/data/school_640x480_1.i420",     640,  640,  480, HAL_PIXEL_FORMAT_I420,       NATIVE_WINDOW_API_MEDIA,   GRALLOC_EXTRA_BIT_TYPE_VIDEO},
    {"/data/prv03.i420",                640,  640,  480, HAL_PIXEL_FORMAT_I420,       NATIVE_WINDOW_API_CAMERA,  GRALLOC_EXTRA_BIT_TYPE_CAMERA},
    // because the MTKYUB raw data does not contain padding bits,
    // keep the stride be the same value as width.
    //{"/data/ibmbw_720x480_mtk.yuv",     720,  720,  480, HAL_PIXEL_FORMAT_NV12_BLK,   NATIVE_WINDOW_API_MEDIA,   GRALLOC_EXTRA_BIT_TYPE_VIDEO},
};

const static int TEST_FRAMES = sizeof(test_frames) / sizeof(struct FRAME);

//
// use FRAME data to dispay with an ANativeWindow
// as we postBuffer before
//
status_t showTestFrame(ANativeWindow *w, const FRAME &f, bool isSecure, bool isShow, int frameCnt, int delayMs) {
    char value[PROPERTY_VALUE_MAX];

    // set api connection type as register
    native_window_api_connect(w, 1);

    // set buffer size
    native_window_set_buffers_dimensions(w, f.s, f.h);

    // set format
    native_window_set_buffers_format(w, f.fmt);

    // set usage software write-able and hardware texture bind-able
    int usage = GRALLOC_USAGE_SW_WRITE_OFTEN | GRALLOC_USAGE_HW_TEXTURE;
    if (isSecure)
        usage |= GRALLOC_USAGE_SECURE;

    native_window_set_usage(w, usage);

    // set scaling to match window display size
    native_window_set_scaling_mode(w, NATIVE_WINDOW_SCALING_MODE_SCALE_TO_WINDOW);

    // set buffer rotation
    property_get("debug.sftest.orientation", value, "0");
    int orientation = atoi(value);
    switch (orientation)
    {
        case 1:
            native_window_set_buffers_transform(w, HAL_TRANSFORM_ROT_90);
            ALOGD("rot 90");
            break;

        case 2:
            native_window_set_buffers_transform(w, HAL_TRANSFORM_ROT_180);
            ALOGD("rot 180");
            break;

        case 3:
            native_window_set_buffers_transform(w, HAL_TRANSFORM_ROT_270);
            ALOGD("rot 270");
            break;

        case 4:
            native_window_set_buffers_transform(w, HAL_TRANSFORM_FLIP_H);
            ALOGD("flip H");
            break;

        case 5:
            native_window_set_buffers_transform(w, HAL_TRANSFORM_FLIP_V);
            ALOGD("flip V");
            break;

        case 6:
            native_window_set_buffers_transform(w, HAL_TRANSFORM_ROT_90 | HAL_TRANSFORM_FLIP_H);
            ALOGD("rot 90 + flip H");
            break;

        case 7:
            native_window_set_buffers_transform(w, HAL_TRANSFORM_ROT_90 | HAL_TRANSFORM_FLIP_V);
            ALOGD("rot 90 + flip V");
            break;

        default:
            ALOGD("rot 0 and no flip");
    }

    // set buffer count
    native_window_set_buffer_count(w, 10);

    for (int i = 0; i < frameCnt; i++) {
        printf("\n\t(rnd: %d) [sec: %d] [show: %d] ", i, isSecure, isShow);

        ANativeWindowBuffer *buf;
        sp<GraphicBuffer>   gb;
        void                *ptr;
        const Rect          rect(f.w, f.h);

        int err;
        int fenceFd = -1;

        err = w->dequeueBuffer(w, &buf, &fenceFd);                     // dequeue to get buffer handle
        if (err != 0) {
            printf("dequeue error :%d\n", err);
            continue;
        }
        sp<Fence> fence1(new Fence(fenceFd));
        fence1->wait(Fence::TIMEOUT_NEVER);
        if (err) {
            ALOGE("%s", strerror(-err));
        }

        // set api type
        GraphicBufferExtra::get().setBufParameter(buf->handle, GRALLOC_EXTRA_MASK_TYPE | GRALLOC_EXTRA_MASK_DIRTY, f.usageEx | GRALLOC_EXTRA_BIT_DIRTY);

        {
            int len = f.s * f.h * 3 / 2;
            gb = new GraphicBuffer(buf, false);
            gb->lock(GRALLOC_USAGE_SW_WRITE_OFTEN | GRALLOC_USAGE_HW_TEXTURE, rect, &ptr);

            printf("ptr: %p // ", ptr);
            if (ptr != NULL) {
                FILE *file = fopen(f.name, "rb");   // read file into buffer
                if (file != NULL) {
                    fread(ptr, len, 1, file);
                    fclose(file);
                } else {
                    printf("open file %s failed", f.name);
                }
                printf("data [%08x] [%08x] ", *(uint32_t*)ptr, *(uint32_t*)((uintptr_t)ptr + len - 4));
            }
            gb->unlock();
        }

        err = w->queueBuffer(w, buf, -1);                                    // queue to display
        sp<Fence> fence2(new Fence(fenceFd));
        fence2->wait(Fence::TIMEOUT_NEVER);
        if(err) {
            ALOGE("%s", strerror(-err));
        }

        property_get("debug.sftest.sleep", value, "1000");
        int delay = atoi(value);
        usleep(delay * delayMs);
    }
    // disconnect as unregister
    native_window_api_disconnect(w, 1);

    return NO_ERROR;
}


status_t main(int argc, char** argv) {

    // ----------------------------------------------------------------------------- input param --
    int mode_s1 = -1;
    int mode_s2 = -1;
    int rnd = 1000;
    bool isIteratively  = false;
    int frameCnt = 1;
    int delayMs = 1000;
    if (argc >= 3) {
        sscanf(argv[1], "%d", &mode_s1);
        sscanf(argv[2], "%d", &mode_s2);
        if (argc >= 4) sscanf(argv[3], "%d", &rnd);
        if (argc >= 5) sscanf(argv[4], "%d", &frameCnt);
        if (argc >= 6) sscanf(argv[5], "%d", &delayMs);
    }

    if(mode_s1<0 && mode_s2<0) {
        printf("\t    test-svpUt   [top: 0/Nm, 1/Sec, -1/off]   [bot: 0/Nm, 1/Sec, -1/off]\n");
        printf("\tex: test-svpUt    0                            0                        -- (Nm  vs Nm)x100\n");
        printf("\tex: test-svpUt    1                            1                        -- (Nm vs Sec)x1000\n");
        return NO_ERROR;
    }

    if (mode_s1>=0 && mode_s2>=0) {
        isIteratively = true;
    }
    // -------------------------------------------------------------------------------- m4u init --
    printf("\n--------------------------------\n");
    FILE* fp = fopen("/sys/kernel/debug/m4u/debug", "w+");
    if(NULL != fp) {
        fprintf(fp,"50");
        printf("  m4u init done\n");
        fclose(fp);
    } else {
        printf("  m4u init failed\n");
        return NO_ERROR;
    }
    printf("--------------------------------\n\n");
    // ------------------------------------------------------------------ set up the thread-pool --
    sp<ProcessState> proc(ProcessState::self());
    ProcessState::self()->startThreadPool();

    // create a client to surfaceflinger
    sp<SurfaceComposerClient> client = new SurfaceComposerClient();
    DisplayInfo dinfo;
    sp<IBinder> display = SurfaceComposerClient::getBuiltInDisplay(
            ISurfaceComposer::eDisplayIdMain);
    SurfaceComposerClient::getDisplayInfo(display, &dinfo);
    uint32_t dispw = dinfo.w;
    uint32_t disph = dinfo.h;

    ALOGD("display (w,h):(%d,%d)", dispw, disph);
    //--------------------------------------------------------------------------------- Surface1 --
   String8 surfaceName1("test-svpUt s1");
    if (mode_s1 == 1) surfaceName1.append(" (sec)");

    sp<SurfaceControl> surfaceControl1 = client->createSurface(
        surfaceName1, dispw - 100, disph / 2 - 100, PIXEL_FORMAT_RGB_565);

    sp<Surface> surface1 = surfaceControl1->getSurface();
    ANativeWindow* window1 = surface1.get();

    SurfaceComposerClient::openGlobalTransaction();
    surfaceControl1->setPosition(50, 50);
    surfaceControl1->setLayer(100000);
    SurfaceComposerClient::closeGlobalTransaction();
    //--------------------------------------------------------------------------------- Surface2 --
    String8 surfaceName2("test-svpUt s2");
    if (mode_s2 == 1) surfaceName2.append(" (sec)");

    sp<SurfaceControl> surfaceControl2 = client->createSurface(
        surfaceName2, dispw - 100, disph / 2 - 100, PIXEL_FORMAT_RGB_565);

    sp<Surface> surface2 = surfaceControl2->getSurface();
    ANativeWindow* window2 = surface2.get();

    SurfaceComposerClient::openGlobalTransaction();
    surfaceControl2->setPosition(50, disph / 2 + 50);
    surfaceControl2->setLayer(200000);
    SurfaceComposerClient::closeGlobalTransaction();
    //---------------------------------------------------------------------------------------------
    bool isShow1 = true;
    bool isShow2 = true;
    for(int i = 0; i < rnd; i++) {
        int k = i % TEST_FRAMES;
        printf("[test] %d / %d / %d [f: %d] [t: %d mspf]... ", k, i, rnd, frameCnt, delayMs);

        if (mode_s1>=0)
            showTestFrame(window1, test_frames[k], mode_s1!=0, isShow1, frameCnt, delayMs);
        if (mode_s2>=0)
            showTestFrame(window2, test_frames[k], mode_s2!=0, isShow2, frameCnt, delayMs);

        printf("done\n");
        if ((k + 1) == TEST_FRAMES) {
            if (isIteratively) {
                SurfaceComposerClient::openGlobalTransaction();
                int session = (i / TEST_FRAMES) % 3;
                if (session == 0) {
                    surfaceControl2->hide();
                    isShow2 = false;
                } else if (session == 1) {
                    surfaceControl2->show();
                    surfaceControl1->hide();
                    isShow2 = true;
                    isShow1 = false;
                } else {
                    surfaceControl1->show();
                    isShow1 = true;
                }
                SurfaceComposerClient::closeGlobalTransaction();
            }
            printf("\n... loop again ...\n");
        }
    }

    printf("test complete. CTRL+C to finish.\n");
    IPCThreadState::self()->joinThreadPool();
    return NO_ERROR;
}
