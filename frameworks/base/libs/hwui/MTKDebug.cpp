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
#define LOG_TAG "OpenGLRenderer"

#include "MTKDebug.h"
#include <GLES3/gl3.h>

#include <stdlib.h>
#include <utils/Thread.h>
#include <utils/Condition.h>
#include <utils/Mutex.h>
#include <SkCanvas.h>
#include <unistd.h>
#include <SkImageEncoder.h>

#if defined(MTK_ENG_BUILD) // turn on log in eng load

bool g_HWUI_debug_opengl = 1;
bool g_HWUI_debug_extensions = 0;
bool g_HWUI_debug_init = 0;
bool g_HWUI_debug_memory_usage = 0;
bool g_HWUI_debug_cache_flush = 1;
bool g_HWUI_debug_layers_as_regions = 0;
bool g_HWUI_debug_clip_regions = 0;
bool g_HWUI_debug_programs = 0;
bool g_HWUI_debug_layers = 1;
bool g_HWUI_debug_render_buffers = 0;
bool g_HWUI_debug_stencil = 0;
bool g_HWUI_debug_patches = 0;
bool g_HWUI_debug_patches_vertices = 0;
bool g_HWUI_debug_patches_empty_vertices = 0;
bool g_HWUI_debug_paths = 0;
bool g_HWUI_debug_textures = 1;
bool g_HWUI_debug_layer_renderer = 1;
bool g_HWUI_debug_font_renderer = 0;
bool g_HWUI_debug_defer = 0;
bool g_HWUI_debug_display_list = 0;
bool g_HWUI_debug_display_ops_as_events = 1;
bool g_HWUI_debug_detailed_events = 0;
bool g_HWUI_debug_merge_behavior = 0;
bool g_HWUI_debug_shadow = 0;

// debug dump functions
bool g_HWUI_debug_texture_tracker = 0;
bool g_HWUI_debug_duration = 0;
bool g_HWUI_debug_render_thread = 0;
bool g_HWUI_debug_dumpDisplayList = 0;
bool g_HWUI_debug_dumpDraw = 0;
bool g_HWUI_debug_dumpTexture = 0;
bool g_HWUI_debug_dumpAlphaTexture = 0;
bool g_HWUI_debug_dumpLayer = 0;
bool g_HWUI_debug_dumpTextureLayer = 0;
bool g_HWUI_debug_enhancement = 1;

// sync with egl trace
bool g_HWUI_debug_egl_trace = 0;

// ANR threshold
int g_HWUI_debug_anr_ns = 100000000;

#else // keep user/usedebug load log off

bool g_HWUI_debug_opengl = 1;
bool g_HWUI_debug_extensions = 0;
bool g_HWUI_debug_init = 0;
bool g_HWUI_debug_memory_usage = 0;
bool g_HWUI_debug_cache_flush = 1;
bool g_HWUI_debug_layers_as_regions = 0;
bool g_HWUI_debug_clip_regions = 0;
bool g_HWUI_debug_programs = 0;
bool g_HWUI_debug_layers = 0;
bool g_HWUI_debug_render_buffers = 0;
bool g_HWUI_debug_stencil = 0;
bool g_HWUI_debug_patches = 0;
bool g_HWUI_debug_patches_vertices = 0;
bool g_HWUI_debug_patches_empty_vertices = 0;
bool g_HWUI_debug_paths = 0;
bool g_HWUI_debug_textures = 0;
bool g_HWUI_debug_layer_renderer = 0;
bool g_HWUI_debug_font_renderer = 0;
bool g_HWUI_debug_defer = 0;
bool g_HWUI_debug_display_list = 0;
bool g_HWUI_debug_display_ops_as_events = 0;
bool g_HWUI_debug_detailed_events = 0;
bool g_HWUI_debug_merge_behavior = 0;
bool g_HWUI_debug_shadow = 0;

// debug dump functions
bool g_HWUI_debug_texture_tracker = 0;
bool g_HWUI_debug_duration = 0;
bool g_HWUI_debug_render_thread = 0;
bool g_HWUI_debug_dumpDisplayList = 0;
bool g_HWUI_debug_dumpDraw = 0;
bool g_HWUI_debug_dumpTexture = 0;
bool g_HWUI_debug_dumpAlphaTexture = 0;
bool g_HWUI_debug_dumpLayer = 0;
bool g_HWUI_debug_dumpTextureLayer = 0;
bool g_HWUI_debug_enhancement = 1;

// sync with egl trace
bool g_HWUI_debug_egl_trace = 0;

// ANR threshold
int g_HWUI_debug_anr_ns = 50000000;

#endif // defined(MTK_ENG_BUILD)

#if defined(MTK_DEBUG_RENDERER)
namespace android {

#ifdef USE_OPENGL_RENDERER
using namespace uirenderer;
ANDROID_SINGLETON_STATIC_INSTANCE(Dumper);
ANDROID_SINGLETON_STATIC_INSTANCE(TextureTracker);
#endif

namespace uirenderer {

#define TTLOGD(...) \
    if (g_HWUI_debug_texture_tracker) ALOGD(__VA_ARGS__)

///////////////////////////////////////////////////////////////////////////////
// Dumper
///////////////////////////////////////////////////////////////////////////////

class DumpBarrier {
    public:
        DumpBarrier(Condition::WakeUpType type = Condition::WAKE_UP_ALL) : mType(type), mSignaled(false) { }
        ~DumpBarrier() { }

        void signal() {
            Mutex::Autolock l(mLock);
            mSignaled = true;
            mCondition.signal(mType);
        }

        void wait() {
            Mutex::Autolock l(mLock);
            while (!mSignaled) {
                mCondition.wait(mLock);
            }
            mSignaled = false;
        }

    private:
        Condition::WakeUpType mType;
        volatile bool mSignaled;
        mutable Mutex mLock;
        mutable Condition mCondition;
};

class DumpTask {
public:
    const static float TARGET_SIZE = 102480; // 240 * 427
    DumpTask(int w, int h, const char* f, bool c):
        width(w), height(h), size(width * height * 4), compress(c) {
        memcpy(filename, f, 512);
        bitmap.setInfo(SkImageInfo::MakeN32Premul(width, height));
        bitmap.allocPixels();
    }

    DumpTask(const SkBitmap* b, const char* f, bool c):
        width(b->width()), height(b->height()), size(b->getSize()), compress(c) {
        memcpy(filename, f, 512);

        nsecs_t start = systemTime(SYSTEM_TIME_MONOTONIC);
        b->copyTo(&bitmap);
        nsecs_t end = systemTime(SYSTEM_TIME_MONOTONIC);
        ALOGD("copyTo %dx%d, time %lldms", width, height, nanoseconds_to_milliseconds(end - start));
    }

    ~DumpTask() {
    }

    void preProcess() {
        if (bitmap.readyToDraw() && compress) {
            nsecs_t start = systemTime(SYSTEM_TIME_MONOTONIC);
            float ratio = sqrt(TARGET_SIZE / width / height) ;

            if (ratio < 1) {
                int w = (int)(width * ratio + 0.5);;
                int h = (int)(height * ratio + 0.5);
                SkBitmap dst;
                dst.setInfo(SkImageInfo::MakeN32Premul(w, h));
                dst.allocPixels();
                dst.eraseColor(0);

                SkPaint paint;
                paint.setFilterBitmap(true);

                SkCanvas canvas(dst);
                canvas.scale(ratio, ratio);
                canvas.drawBitmap(bitmap, 0.0f, 0.0f, &paint);
                dst.copyTo(&bitmap);
                nsecs_t end = systemTime(SYSTEM_TIME_MONOTONIC);
                ALOGD("scale ratio %f, %dx%d, time %lldms", ratio, bitmap.width(), bitmap.height(), nanoseconds_to_milliseconds(end - start));
            } else {
                ALOGD("scale ratio %f >= 1, %dx%d not needed", ratio, bitmap.width(), bitmap.height());
            }

        }
    }

    void onProcess() {
        nsecs_t start = systemTime(SYSTEM_TIME_MONOTONIC);
        if (!SkImageEncoder::EncodeFile(filename, bitmap, SkImageEncoder::kPNG_Type, 40))
        {
            ALOGE("Failed to encode image %s\n", filename);
            char* lastPeriod = strrchr(filename, '/');
            if (lastPeriod) {
                char file[512];
                // folder /data/HWUI_dump/ will be created by script
                sprintf(file, "/data/HWUI_dump/%s", lastPeriod + 1);
                if (!SkImageEncoder::EncodeFile(file, bitmap, SkImageEncoder::kPNG_Type, 40))
                {
                    ALOGE("Failed to encode image %s\n", file);
                }
            }
        }
        nsecs_t end = systemTime(SYSTEM_TIME_MONOTONIC);
        ALOGD("encodeFile %dx%d, time %lldms", bitmap.width(), bitmap.height(), nanoseconds_to_milliseconds(end - start));
    }

    int width;
    int height;
    size_t size;
    char filename[512];
    SkBitmap bitmap;
    bool compress;
};

class DumperThread: public Thread {
 public:
     DumperThread(const String8 name): mSignal(Condition::WAKE_UP_ONE), mName(name) { }

     bool addTask(DumpTask *task) {
         if (!isRunning()) {
             run(mName.string(), PRIORITY_DEFAULT);
         }

         Mutex::Autolock l(mLock);
         ssize_t index = mTasks.add(task);
         mSignal.signal();

         return index >= 0;
     }
     size_t getTaskCount() const {
         Mutex::Autolock l(mLock);
         return mTasks.size();
     }
     void exit() {
         {
             Mutex::Autolock l(mLock);
             for (size_t i = 0; i < mTasks.size(); i++) {
                 const DumpTask* task = mTasks.itemAt(i);
                 delete task;
             }
             mTasks.clear();
         }
         requestExit();
         mSignal.signal();
     }

 private:
     virtual bool threadLoop() {
         mSignal.wait();
         Vector<DumpTask*> tasks;
         {
             Mutex::Autolock l(mLock);
             tasks = mTasks;
             mTasks.clear();
         }

         for (size_t i = 0; i < tasks.size(); i++) {
             DumpTask* task = tasks.itemAt(i);
             task->onProcess();
             delete task;
         }
         return true;
     }

     // Lock for the list of tasks
     mutable Mutex mLock;
     Vector<DumpTask *> mTasks;

     // Signal used to wake up the thread when a new
     // task is available in the list
     mutable DumpBarrier mSignal;

     const String8 mName;
 };

Dumper::Dumper() : mPid(getpid())
                 , mProcessName(NULL)
                 , mThreadCount(sysconf(_SC_NPROCESSORS_CONF) / 2) {
    // Get the number of available CPUs. This value does not change over time.
    ALOGD("Dumper init %d threads <%p>", mThreadCount, this);
    for (int i = 0; i < mThreadCount; i++) {
        String8 name;
        name.appendFormat("HwuiDumperThread%d", i + 1);
        mThreads.add(new DumperThread(name));
    }

    // Get process name
    FILE *f;
    char processName[256];
    bool success = true;

    f = fopen("/proc/self/cmdline", "r");
    if (!f) {
        ALOGE("Can't get application name");
        success = false;
    } else {
        if (fgets(processName, 256, f) == NULL) {
            ALOGE("fgets failed");
            success = false;
        }
        fclose(f);
    }

    if (success) {
        mProcessName = new char[strlen(processName) + 1];
        memmove(mProcessName, processName, strlen(processName) + 1);
        ALOGD("<%s> is running.", mProcessName);
    }
}

Dumper::~Dumper() {
    for (size_t i = 0; i < mThreads.size(); i++) {
        mThreads[i]->exit();
    }
    if (mProcessName) {
        delete []mProcessName;
        mProcessName = NULL;
    }
}

bool Dumper::addTask(DumpTask *task) {
    task->preProcess();
    if (mThreads.size() > 0) {
        size_t minQueueSize = MAX_BUFFER_SIZE / mThreadCount / task->bitmap.getSize();
        sp<DumperThread> thread;
        for (size_t i = 0; i < mThreads.size(); i++) {
            if (mThreads[i]->getTaskCount() < minQueueSize) {
                thread = mThreads[i];
                minQueueSize = mThreads[i]->getTaskCount();
            }
        }

        if (thread.get() == NULL)
            return false;

        return thread->addTask(task);
    }
    return false;
}

bool Dumper::dumpDisplayList(int width, int height, int frameCount, void* renderer) {
    char file[512];
    sprintf(file, "/data/data/%s/dp_%p_%09d.png", mProcessName, renderer, frameCount);

    ALOGD("%s: %dx%d %s", __FUNCTION__, width, height, file);
    return dumpImage(width, height, file);
}

bool Dumper::dumpDraw(int width, int height, int frameCount, int index, void* renderer, void* drawOp, int sub) {
    char file[512];
    sprintf(file, "/data/data/%s/draw_%p_%09d_%02d_%02d_%p.png", mProcessName, renderer, frameCount, index, sub, drawOp);

    ALOGD("%s: %dx%d %s", __FUNCTION__, width, height, file);
    return dumpImage(width, height, file);
}

bool Dumper::dumpLayer(int width, int height, int fbo, int frameCount, void* renderer, void* layer) {
    char file[512];
    nsecs_t time = systemTime(SYSTEM_TIME_MONOTONIC);
    sprintf(file, "/data/data/%s/layer_%p_%p_%d_%dx%d_%09d_%09u.png",
        mProcessName, renderer, layer, fbo, width, height, frameCount, (unsigned int) time / 1000);

    ALOGD("%s: %dx%d %s", __FUNCTION__, width, height, file);
    return dumpImage(width, height, file);
}

bool Dumper::dumpTexture(int texture, int width, int height, const SkBitmap *bitmap, bool isLayer) {
    char file[512];

    if (isLayer) {
        nsecs_t time = systemTime(SYSTEM_TIME_MONOTONIC);
        sprintf(file, "/data/data/%s/texLayer_%d_%dx%d_%u.png",
            mProcessName, texture, width, height, (unsigned int) time / 1000);
    } else {
        sprintf(file, "/data/data/%s/tex_%d_%dx%d_%p.png",
            mProcessName, texture, width, height, bitmap);
    }

    ALOGD("%s: %dx%d %s", __FUNCTION__, width, height, file);

    DumpTask* task = new DumpTask(bitmap, file, isLayer);
    if (!task->bitmap.readyToDraw()) {
        ALOGE("%s: failed to copy bitmap %p\n", __FUNCTION__, bitmap);
        delete task;
        return false;
    }

    if (addTask(task)) {
        // dumper will help to delete task when task finished
    } else {
        task->onProcess();
        delete task;
    }

    return true;
}

bool Dumper::dumpAlphaTexture(int width, int height, uint8_t *data, const char *prefix, SkBitmap::Config format) {
    static int count = 0;

    char file[512];
    SkBitmap bitmap;
    SkBitmap bitmapCopy;

    sprintf(file, "/data/data/%s/%s_%04d.png", mProcessName, prefix, count++);

    ALOGD("%s: %dx%d %s", __FUNCTION__, width, height, file);
    bitmap.setInfo(SkImageInfo::MakeN32Premul(width, height));
    bitmap.setPixels(data, NULL);

    DumpTask* task = new DumpTask(&bitmap, file, false);

    if (!task->bitmap.readyToDraw()) {
        ALOGE("%s: failed to copy data %p", __FUNCTION__, data);
        delete task;
        return false;
    }

    if (addTask(task)) {
        // dumper will help to delete task when task finished
    } else {
        task->onProcess();
        delete task;
    }

    return true;
}


bool Dumper::dumpImage(int width, int height, const char *filename) {
    DumpTask* task = new DumpTask(width, height, filename, true);
    GLenum error;
    nsecs_t start = systemTime(SYSTEM_TIME_MONOTONIC);
    glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, task->bitmap.getPixels());
    nsecs_t end = systemTime(SYSTEM_TIME_MONOTONIC);
    ALOGD("%s: readpixel %dx%d time %lldms", __FUNCTION__, width, height, nanoseconds_to_milliseconds(end - start));

    if ((error = glGetError()) != GL_NO_ERROR) {
        ALOGE("%s: get GL error 0x%x \n", __FUNCTION__, error);
        delete task;
        return false;
    }

    if (addTask(task)) {
        // dumper will help to delete task when task finished
    } else {
        task->onProcess();
        delete task;
    }

    return true;
}


///////////////////////////////////////////////////////////////////////////////
// TextureTracker
///////////////////////////////////////////////////////////////////////////////

TextureTracker::TextureTracker() {
    TTLOGD("[TT]TextureTracker +");
}

TextureTracker::~TextureTracker() {
    TTLOGD("[TT]TextureTracker -");
}

void TextureTracker::startMark(const char* name) {
    Mutex::Autolock _l(mLock);
    mViews.push(String8(name));
}

void TextureTracker::endMark() {
    Mutex::Autolock _l(mLock);
    mViews.pop();
}

String8 TextureTracker::top() {
    Mutex::Autolock _l(mLock);
    return mViews.top();
}

void TextureTracker::add(int textureId, int w, int h, int format, int type, String8 purpose, const char* comment) {
    Mutex::Autolock _l(mLock);

    if (mViews.size() == 0) {
        if (comment != NULL) {
            ALOGE("[TT]add error %s %d %d %d 0x%x 0x%x %s", comment, textureId, w, h, format, type, purpose.string());
        } else {
            ALOGE("[TT]add error %d %d %d 0x%x 0x%x %s", textureId, w, h, format, type, purpose.string());
        }
        return;
    }
    TextureEntry entry(mViews.top(), textureId, w, h, format, type, purpose);
    mMemoryList.add(entry);

    if (comment != NULL) {
        TTLOGD("[TT]%s %s %d %d %d 0x%x 0x%x => %d %s", comment, entry.mName.string(), textureId, w, h, format, type, entry.mMemory, purpose.string());
    }
}

void TextureTracker::add(String8 name, int textureId, int w, int h, int format, int type, String8 purpose, const char* comment) {
    Mutex::Autolock _l(mLock);
    TextureEntry entry(name, textureId, w, h, format, type, purpose);
    mMemoryList.add(entry);

    if (comment != NULL) {
        TTLOGD("[TT]%s %s %d %d %d 0x%x 0x%x => %d %s", comment, name.string(), textureId, w, h, format, type, entry.mMemory, purpose.string());
    }
}

void TextureTracker::add(const char* name, int textureId, int w, int h, int format, int type, const char* purpose, const char* comment) {
    add(String8(name), textureId, w, h, format, type, String8(purpose), comment);
}

void TextureTracker::remove(int textureId, const char* comment) {
    Mutex::Autolock _l(mLock);
    TextureEntry entry(textureId);
    ssize_t index = mMemoryList.indexOf(entry);

    if (index >= 0) {
        entry = mMemoryList.itemAt(index);
        mMemoryList.removeAt(index);

        TTLOGD("[TT]%s %s %d", comment, entry.mName.string(), textureId);
    } else {
        TTLOGD("[TT]%s already %d", comment, textureId);
    }
}

void TextureTracker::update(int textureId, bool ghost, String8 name) {
    Mutex::Autolock _l(mLock);
    TextureEntry entry(textureId);
    ssize_t index = mMemoryList.indexOf(entry);

    if (index >= 0) {
        TextureEntry& item = mMemoryList.editItemAt(index);
        TTLOGD("[TT]update before %s %d %d %d %d %d\n", item.mName.string(), item.mId, item.mWidth, item.mHeight,
                        item.mMemory, item.mGhost);

        item.mGhost = ghost;

        if (name.isEmpty()) {
            if (!ghost) {
                item.mName = mViews.top();
            }
        } else {
            item.mName = name;
        }

        entry = mMemoryList.itemAt(index);
        TTLOGD("[TT]update after %s %d %d %d %d %d\n", entry.mName.string(), entry.mId, entry.mWidth, entry.mHeight,
                    entry.mMemory, entry.mGhost);
    } else {
        TTLOGD("[TT]update not found %d", textureId);
    }
}

int TextureTracker::estimateMemory(int w, int h, int format, int type) {
    int bytesPerPixel = 0;

    switch (type) {
        case GL_UNSIGNED_BYTE:
            switch (format) {
                case GL_RGBA:
                    bytesPerPixel = 4;
                    break;
                case GL_RGB:
                    bytesPerPixel = 3;
                    break;
                case GL_LUMINANCE_ALPHA:
                    bytesPerPixel = 2;
                    break;
                case GL_ALPHA:
                case GL_LUMINANCE:
                    bytesPerPixel = 1;
                    break;
                default:
                    ALOGE("[TT]estimateMemory Error!! type:0x%x, format:0x%x", type, format);
                    break;
            }
            break;
        case GL_UNSIGNED_SHORT_4_4_4_4: // GL_RGBA format
        case GL_UNSIGNED_SHORT_5_5_5_1: // GL_RGBA format
        case GL_UNSIGNED_SHORT_5_6_5:   // GL_RGB
            bytesPerPixel = 2;
            break;
        case GL_FLOAT:
            switch (format) {
                case GL_RED:
                    bytesPerPixel = 2;
                    break;
                case GL_RGBA:
                    bytesPerPixel = 8;
                    break;
                default:
                    ALOGE("[TT]estimateMemory Error!! type:0x%x, format:0x%x", type, format);
                    break;
            }
            break;
        default:
            ALOGE("[TT]estimateMemory Error!! type:0x%x, format:0x%x", type, format);
            break;
    }

    return w * h * bytesPerPixel;
}

#define GL_CASE(target, x) case x: target = #x; break;
void TextureTracker::dumpMemoryUsage(String8 &log) {
    log.appendFormat("\nTextureTracker:\n");

    int sum = 0;

    SortedList<Sum> list;
    size_t count = mMemoryList.size();
    for (size_t i = 0; i < count; i++) {
        Sum current(mMemoryList.itemAt(i));
        ssize_t index = list.indexOf(current);
        if (index < 0) {
            list.add(current);
        } else {
            Sum& item = list.editItemAt(index);
            item.mSum += mMemoryList.itemAt(i).mMemory;
            item.mItems.add(mMemoryList.itemAt(i));
        }
    }

    Vector<Sum> result;
    result.add(list.itemAt(0));
    size_t sortSize = list.size();
    for (size_t i = 1; i < sortSize; i++) {
        Sum entry = list.itemAt(i);
        size_t index = result.size();
        size_t size = result.size();
        for (size_t j = 0; j < size; j++) {
            Sum e = result.itemAt(j);
            if (entry.mSum > e.mSum) {
                index = j;
                break;
            }
       }
       result.insertAt(entry, index);
    }

    for (size_t i = 0; i < sortSize; i++) {
        const Sum& current = result.itemAt(i);
        String8 tmpString;
        size_t itemCount = current.mItems.size();
        for (size_t j = 0; j < itemCount; j++) {
            const TextureEntry& entry = current.mItems.itemAt(j);

            const char* format = "";
            const char* type = "";

            switch (entry.mFormat) {
                GL_CASE(format, GL_RGBA);
                GL_CASE(format, GL_RGB);
                GL_CASE(format, GL_ALPHA);
                GL_CASE(format, GL_LUMINANCE);
                GL_CASE(format, GL_LUMINANCE_ALPHA);
                default:
                    break;
            }

            switch (entry.mType) {
                GL_CASE(type, GL_UNSIGNED_BYTE);
                GL_CASE(type, GL_UNSIGNED_SHORT_4_4_4_4);
                GL_CASE(type, GL_UNSIGNED_SHORT_5_5_5_1);
                GL_CASE(type, GL_UNSIGNED_SHORT_5_6_5);
                GL_CASE(type, GL_FLOAT);
                default:
                    break;
            }

            tmpString.appendFormat("        %d (%d, %d) (%s, %s) %d <%s> %s\n",
                entry.mId, entry.mWidth, entry.mHeight, format, type, entry.mMemory,
                entry.mPurpose.string(), entry.mGhost ? "g" : "");
        }

        sum += current.mSum;
        log.appendFormat("%s: %d bytes, %.2f KB, %.2f MB\n", current.mName.string(), current.mSum, current.mSum / 1024.0f, current.mSum / 1048576.0f);
        log.append(tmpString);
        log.append("\n");
    }

    log.appendFormat("\nTotal monitored:\n  %d bytes, %.2f KB, %.2f MB\n", sum, sum / 1024.0f, sum / 1048576.0f);
}

}; // namespace uirenderer
}; // namespace android

#endif /* defined(MTK_DEBUG_RENDERER) */
