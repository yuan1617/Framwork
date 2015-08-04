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

#ifndef MTK_HWUI_DEBUG_H
#define MTK_HWUI_DEBUG_H

#include <stdio.h>
#include <SkBitmap.h>

#include <utils/Trace.h>
#include <utils/Singleton.h>
#include "utils/SortedList.h"
#include <utils/Vector.h>
#include <utils/String8.h>

#include <cutils/properties.h>

#include "DisplayListLogBuffer.h"

// Turn on to check for OpenGL errors on each frame
extern bool g_HWUI_debug_opengl;
// Turn on to display informations about the GPU
extern bool g_HWUI_debug_extensions;
// Turn on to enable initialization information
extern bool g_HWUI_debug_init;
// Turn on to enable memory usage summary on each frame
extern bool g_HWUI_debug_memory_usage;
// Turn on to enable debugging of cache flushes
extern bool g_HWUI_debug_cache_flush;
// Turn on to enable layers debugging when rendered as regions
extern bool g_HWUI_debug_layers_as_regions;
// Turn on to enable debugging when the clip is not a rect
extern bool g_HWUI_debug_clip_regions;
// Turn on to display debug info about vertex/fragment shaders
extern bool g_HWUI_debug_programs;
// Turn on to display info about layers
extern bool g_HWUI_debug_layers;
// Turn on to display info about render buffers
extern bool g_HWUI_debug_render_buffers;
// Turn on to make stencil operations easier to debug
// (writes 255 instead of 1 in the buffer, forces 8 bit stencil)
extern bool g_HWUI_debug_stencil;
// Turn on to display debug info about 9patch objects
extern bool g_HWUI_debug_patches;
// Turn on to display vertex and tex coords data about 9patch objects
// This flag requires DEBUG_PATCHES to be turned on
extern bool g_HWUI_debug_patches_vertices;
// Turn on to display vertex and tex coords data used by empty quads
// in 9patch objects
// This flag requires DEBUG_PATCHES to be turned on
extern bool g_HWUI_debug_patches_empty_vertices;
// Turn on to display debug info about shapes
extern bool g_HWUI_debug_paths;
// Turn on to display debug info about textures
extern bool g_HWUI_debug_textures;
// Turn on to display debug info about the layer renderer
extern bool g_HWUI_debug_layer_renderer;
// Turn on to log draw operation batching and deferral information
extern bool g_HWUI_debug_defer;
// Turn on to enable additional debugging in the font renderers
extern bool g_HWUI_debug_font_renderer;
// Turn on to dump display list state
extern bool g_HWUI_debug_display_list;
// Turn on to insert an event marker for each display list op
extern bool g_HWUI_debug_display_ops_as_events;
// Turn on to insert detailed event markers
extern bool g_HWUI_debug_detailed_events;
// Turn on to highlight drawing batches and merged batches with different colors
extern bool g_HWUI_debug_merge_behavior;
// Turn on to enable debugging shadow
extern bool g_HWUI_debug_shadow;

// debug dump functions
extern bool g_HWUI_debug_texture_tracker;
extern bool g_HWUI_debug_duration;
extern bool g_HWUI_debug_render_thread;
extern bool g_HWUI_debug_dumpDisplayList;
extern bool g_HWUI_debug_dumpDraw;
extern bool g_HWUI_debug_dumpTexture;
extern bool g_HWUI_debug_dumpAlphaTexture;
extern bool g_HWUI_debug_dumpLayer;
extern bool g_HWUI_debug_dumpTextureLayer;
extern bool g_HWUI_debug_enhancement;

// sync with egl trace
extern bool g_HWUI_debug_egl_trace;

// ANR threshold
extern int g_HWUI_debug_anr_ns;

#if defined(MTK_DEBUG_RENDERER) // eng/userdebug build enable debugging mechanism

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Dumper
///////////////////////////////////////////////////////////////////////////////
class DumpBarrier;
class DumpTask;
class DumperThread;

class Dumper: public Singleton<Dumper> {
public:
    const static float MAX_BUFFER_SIZE = 64 * 1048576; // max 64MB for all threads
    int mPid;
    char* mProcessName;

    Dumper();
    ~Dumper();
    friend class Singleton<Dumper>;

public:
    bool addTask(DumpTask *task);
    bool dumpDisplayList(int width, int height, int frameCount, void* renderer);
    bool dumpDraw(int width, int height, int frameCount, int index, void* renderer, void* drawOp, int sub = 0);
    bool dumpLayer(int width, int height, int fbo, int frameCount, void* renderer, void* layer);
    bool dumpTexture(int texture, int width, int height, const SkBitmap *bitmap, bool isLayer);
    bool dumpAlphaTexture(int width, int height, uint8_t *data, const char *prefix, SkBitmap::Config format);

private:
    bool dumpImage(int width, int height, const char *filename);
    int mThreadCount;
    Vector<sp<DumperThread> > mThreads;
};

///////////////////////////////////////////////////////////////////////////////
// TextureTracker
///////////////////////////////////////////////////////////////////////////////

class TextureTracker: public Singleton<TextureTracker> {
    TextureTracker();
    ~TextureTracker();

    friend class Singleton<TextureTracker>;

public:
    void startMark(const char* name);
    void endMark();
    String8 top();
    void add(int textureId, int w, int h, int format, int type, String8 name, const char* comment = NULL);
    void add(String8 name, int textureId, int w, int h, int format, int type, String8 purpose, const char* comment = NULL);
    void add(const char* name, int textureId, int w, int h, int format, int type, const char* purpose, const char* comment = NULL);
    void remove(int textureId, const char* comment = NULL);
    void update(int textureId, bool ghost, String8 name = String8());

    void dumpMemoryUsage(String8 &log);

    static int estimateMemory(int w, int h, int format, int type);

private:
    struct TextureEntry {
        TextureEntry():
            mName(String8("none")), mId(0), mWidth(0), mHeight(0), mFormat(0), mType(0), mMemory(0), mGhost(true), mPurpose(String8("none")) {
        }

        TextureEntry(String8 name, int id, int w, int h, int format, int type, String8 purpose):
            mName(name), mId(id), mWidth(w), mHeight(h), mFormat(format), mType(type), mGhost(false), mPurpose(purpose) {
            mMemory = TextureTracker::estimateMemory(w, h, format, type);
        }

        TextureEntry(int id):
            mName(String8("none")), mId(id), mWidth(0), mHeight(0), mFormat(0), mType(0),mMemory(0), mGhost(true), mPurpose(String8("none")) {
        }

        ~TextureEntry() {
        }

        bool operator<(const TextureEntry& rhs) const {
            return mId < rhs.mId;
        }

        bool operator==(const TextureEntry& rhs) const {
            return mId == rhs.mId;
        }

        String8 mName;
        int mId;
        int mWidth;
        int mHeight;
        int mFormat;
        int mType;
        int mMemory;
        bool mGhost;
        String8 mPurpose;
    };

    struct Sum {
        Sum(): mName(String8("none")), mSum(0) {
        }

        Sum(TextureEntry entry) : mName(entry.mName), mSum(entry.mMemory) {
            mItems.add(entry);
        }

        ~Sum() {
        }

        bool operator<(const Sum& rhs) const {
            return mName < rhs.mName;
        }

        bool operator==(const Sum& rhs) const {
            return mName == rhs.mName;
        }

        String8 mName;
        int mSum;
        Vector<TextureEntry> mItems;
    };

private:
    Vector<String8> mViews;
    SortedList<TextureEntry> mMemoryList;
    mutable Mutex mLock;
};

static void setDebugLog() {
    bool* pDebugArray[] = {
        &g_HWUI_debug_opengl,
        &g_HWUI_debug_extensions,
        &g_HWUI_debug_init,
        &g_HWUI_debug_memory_usage,
        &g_HWUI_debug_cache_flush,
        &g_HWUI_debug_layers_as_regions,
        &g_HWUI_debug_clip_regions,
        &g_HWUI_debug_programs,
        &g_HWUI_debug_layers,
        &g_HWUI_debug_render_buffers,
        &g_HWUI_debug_stencil,
        &g_HWUI_debug_patches,
        &g_HWUI_debug_patches_vertices,
        &g_HWUI_debug_patches_empty_vertices,
        &g_HWUI_debug_paths,
        &g_HWUI_debug_textures,
        &g_HWUI_debug_layer_renderer,
        &g_HWUI_debug_font_renderer,
        &g_HWUI_debug_defer,
        &g_HWUI_debug_display_list,
        &g_HWUI_debug_display_ops_as_events,
        &g_HWUI_debug_detailed_events,
        &g_HWUI_debug_merge_behavior,
        &g_HWUI_debug_shadow,
        &g_HWUI_debug_texture_tracker,
        &g_HWUI_debug_duration,
        &g_HWUI_debug_render_thread,
        &g_HWUI_debug_dumpDisplayList,
        &g_HWUI_debug_dumpDraw,
        &g_HWUI_debug_dumpTexture,
        &g_HWUI_debug_dumpAlphaTexture,
        &g_HWUI_debug_dumpLayer,
        &g_HWUI_debug_dumpTextureLayer,
        &g_HWUI_debug_enhancement,
        &g_HWUI_debug_egl_trace
    };
    const char* properties[] = {
        "debug.hwui.log.opengl",
        "debug.hwui.log.ext",
        "debug.hwui.log.init",
        "debug.hwui.log.mem",
        "debug.hwui.log.cache_flush",
        "debug.hwui.log.layersAsRegions",
        "debug.hwui.log.clip_regions",
        "debug.hwui.log.programs",
        "debug.hwui.log.layers",
        "debug.hwui.log.render_buffers",
        "debug.hwui.log.stencil",
        "debug.hwui.log.patches",
        "debug.hwui.log.patches_vtx",
        "debug.hwui.log.patchesEmptyVtx",
        "debug.hwui.log.paths",
        "debug.hwui.log.tex",
        "debug.hwui.log.layer_renderer",
        "debug.hwui.log.font_renderer",
        "debug.hwui.log.defer",
        "debug.hwui.log.displaylist",
        "debug.hwui.log.display_events",
        "debug.hwui.log.detailed_events",
        "debug.hwui.log.merge_behavior",
        "debug.hwui.log.shadow",

        "debug.hwui.log.texture_tracker",    // log gl textures' life
        "debug.hwui.log.duration",           // sync with DisplayListLogBuffer
        "debug.hwui.log.render_thread",      // log tasks in render thread
        "debug.hwui.dump.displaylist",       // dump rendering result per frame
        "debug.hwui.dump.draw",              // dump rendering result per draw operation
        "debug.hwui.dump.tex",               // dump texture returned from textureCache
        "debug.hwui.dump.fonttex",           // dump texture for fonts, aka g_HWUI_debug_dumpAlphaTexture
        "debug.hwui.dump.layer",             // dump layer, the result of fbo
        "debug.hwui.dump.texture_layer",     // dump texturelayer, copy layer to bitmap
        "debug.hwui.enhancement",            // mtk enhancements
        "debug.egl.trace"                    // sync with DevelopmentSettings
    };
    char value[PROPERTY_VALUE_MAX];
    char valueId[PROPERTY_VALUE_MAX];
    char valueName[PROPERTY_VALUE_MAX];
    int size = int(sizeof(pDebugArray) / sizeof(pDebugArray[0]));

    char propertyId[] = "debug.hwui.process.id";
    char propertyName[] = "debug.hwui.process.name";

    bool enabled = true;
    int pid = Dumper::getInstance().mPid;
    char* pname = Dumper::getInstance().mProcessName;

    property_get(propertyId, valueId, "0");
    property_get(propertyName, valueName, "0");

    if (strcmp(valueId, "0") != 0 || strcmp(valueName, "0") != 0) {
        if (atoi(valueId) != pid && strcmp(valueName, pname) != 0) {
            // target process's pid is not matched
            enabled = false;
            ALOGD("%s=%s, current=%d, %s=%s, current=%s",
                propertyId, valueId, pid, propertyName, valueName, pname);
        }
    }

    if (enabled) {
        for (int i = 0; i < size; i++) {
            property_get(properties[i], value, "");
            if (value[0] != '\0') {
                ALOGD("<%s> setHwuiLog: %s=%s", pname, properties[i], value);
                //must check "1" because egl_trace property is systrace/error/1
                *pDebugArray[i] = (strcmp(value, "1") == 0) ? 1 : 0;
            }
        }

        property_get("debug.hwui.anr.ns", value, "");
        if (value[0] != '\0') {
            g_HWUI_debug_anr_ns = atoi(value);
            ALOGD("<%s> setHwuiAnrNs: %s", pname, value);
        }
    }
}

/**
 * Dump helper
 */
#define DUMP_DISPLAY_LIST(...) \
    if (g_HWUI_debug_dumpDisplayList) Dumper::getInstance().dumpDisplayList(__VA_ARGS__)

#define DUMP_DRAW(...) \
    if (g_HWUI_debug_dumpDraw) Dumper::getInstance().dumpDraw(__VA_ARGS__)

#define DUMP_TEXTURE(...) \
    if (g_HWUI_debug_dumpTexture) Dumper::getInstance().dumpTexture(__VA_ARGS__, false)

#define DUMP_ALPHA_TEXTURE(...) \
    if (g_HWUI_debug_dumpAlphaTexture) Dumper::getInstance().dumpAlphaTexture(__VA_ARGS__)

#define DUMP_LAYER(...) \
    if (g_HWUI_debug_dumpLayer) Dumper::getInstance().dumpLayer(__VA_ARGS__)

#define DUMP_TEXTURE_LAYER(...) \
    if (g_HWUI_debug_dumpTextureLayer) Dumper::getInstance().dumpTexture(__VA_ARGS__, true)

/**
 * Texture tracker helper
 */
#define TT_DUMP_MEMORY_USAGE(...) TextureTracker::getInstance().dumpMemoryUsage(__VA_ARGS__)
#define TT_ADD(...) TextureTracker::getInstance().add(__VA_ARGS__)
#define TT_UPDATE(...) TextureTracker::getInstance().update(__VA_ARGS__)
#define TT_REMOVE(...) TextureTracker::getInstance().remove(__VA_ARGS__)
#define TT_START_MARK(...) TextureTracker::getInstance().startMark(__VA_ARGS__)
#define TT_END_MARK(...) TextureTracker::getInstance().endMark(__VA_ARGS__)
#define TT_TOP(...) TextureTracker::getInstance().top(__VA_ARGS__)

/**
 * Systrace helper
 */
// overview
#define ATRACE_BEGIN_L1(...) atrace_begin(ATRACE_TAG_VIEW, __VA_ARGS__)
#define ATRACE_END_L1() atrace_end(ATRACE_TAG_VIEW)
#define ATRACE_NAME_L1(...) android::ScopedTrace ___tracer(ATRACE_TAG_VIEW, __VA_ARGS__)
#define ATRACE_CALL_L1() ATRACE_NAME_L1(__FUNCTION__)

// detail
#define ATRACE_BEGIN_L2(...) atrace_begin(ATRACE_TAG_HWUI, __VA_ARGS__)
#define ATRACE_END_L2() atrace_end(ATRACE_TAG_HWUI)
#define ATRACE_NAME_L2(...) android::ScopedTrace ___tracer(ATRACE_TAG_HWUI, __VA_ARGS__)
#define ATRACE_CALL_L2() ATRACE_NAME_L2(__FUNCTION__)

/**
 * performance log helper
 */
#define TIME_LOG_BASIC(NAME, COMMAND, DURATION) {                                        \
        nsecs_t start = systemTime(SYSTEM_TIME_MONOTONIC);                               \
        COMMAND;                                                                         \
        nsecs_t end = systemTime(SYSTEM_TIME_MONOTONIC);                                 \
        DURATION = end - start;                                                          \
        if (DURATION > g_HWUI_debug_anr_ns)                                              \
            ALOGD("[ANR Warning] %s %dms", NAME, nanoseconds_to_milliseconds(DURATION)); \
        DisplayListLogBuffer::getInstance().writeCommand(0, NAME, DURATION);             \
    }

#define TIME_LOG_SYSTRACE(NAME, COMMAND, DURATION, SYSTRACE_NAME) { \
        ATRACE_BEGIN_L2(SYSTRACE_NAME);                             \
        TIME_LOG_BASIC(NAME, COMMAND, DURATION);                    \
        ATRACE_END_L2();                                            \
    }

#define TIME_LOG(NAME, COMMAND) {                         \
        nsecs_t duration;                                 \
        TIME_LOG_SYSTRACE(NAME, COMMAND, duration, NAME); \
    }

}; // namespace uirenderer
}; // namespace android

#else // disabled in user load

/**
 * Dump helper
 */
#define DUMP_DISPLAY_LIST(...)
#define DUMP_DRAW(...)
#define DUMP_TEXTURE(...)
#define DUMP_ALPHA_TEXTURE(...)
#define DUMP_LAYER(...)
#define DUMP_TEXTURE_LAYER(...)

/**
 * Texture tracker helper
 */
#define TT_DUMP_MEMORY_USAGE(...)
#define TT_ADD(...)
#define TT_UPDATE(...)
#define TT_REMOVE(...)
#define TT_START_MARK(...)
#define TT_END_MARK(...)
#define TT_TOP(...) String8()

/**
 * Systrace helper
 */
// overview
#define ATRACE_BEGIN_L1(...)
#define ATRACE_END_L1()
#define ATRACE_NAME_L1(...)
#define ATRACE_CALL_L1()

// detail
#define ATRACE_BEGIN_L2(...)
#define ATRACE_END_L2()
#define ATRACE_NAME_L2(...)
#define ATRACE_CALL_L2()

/**
 * performance log helper
 */
#define TIME_LOG_BASIC(NAME, COMMAND, DURATION) COMMAND
#define TIME_LOG_SYSTRACE(NAME, COMMAND, DURATION, SYSTRACE_NAME) COMMAND
#define TIME_LOG(NAME, COMMAND) COMMAND

static void setDebugLog() {
// do nothing in user load
};

#endif /* defined(MTK_DEBUG_RENDERER) */
#endif /* MTK_HWUI_DEBUG_H */
