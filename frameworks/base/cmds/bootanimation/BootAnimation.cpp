/*
 * Copyright (C) 2007 The Android Open Source Project
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

#define LOG_NDEBUG 0
#define LOG_TAG "BootAnimation"

#include <stdint.h>
#include <sys/types.h>
#include <math.h>
#include <fcntl.h>
#include <utils/misc.h>
#include <signal.h>

#include <cutils/properties.h>

#include <androidfw/AssetManager.h>
#include <binder/IPCThreadState.h>
#include <utils/Atomic.h>
#include <utils/Errors.h>
#include <utils/Log.h>

#include <ui/PixelFormat.h>
#include <ui/Rect.h>
#include <ui/Region.h>
#include <ui/DisplayInfo.h>

#include <gui/ISurfaceComposer.h>
#include <gui/Surface.h>
#include <gui/SurfaceComposerClient.h>

#include <SkBitmap.h>
#include <SkStream.h>
#include <SkImageDecoder.h>

#include <GLES/gl.h>
#include <GLES/glext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <ETC1/etc1.h>
#include <EGL/eglext.h>

#include <media/IMediaHTTPService.h>
#include <media/mediaplayer.h>
#include <media/MediaPlayerInterface.h>

#include <system/audio.h>
#include "BootAnimation.h"
#include "AudioPlayer.h"

#define PATH_COUNT 3
#ifdef MTK_TER_SERVICE
   
#include <binder/IServiceManager.h>
#include "ITerService.h"
#define REGIONAL_BOOTANIM_FILE_NAME "persist.bootanim.logopath"
#define REGIONAL_BOOTANIM_GET_MNC   "persist.bootanim.mnc"


#define SYSTEM_REGIONALPHONE_DB     "/system/etc/regionalphone/regionalphone.db"
#define CUSTOM_REGIONALPHONE_DB     "/custom/etc/regionalphone/regionalphone.db"

enum MNC
{
    MNC_VODAFONE     = 46692,
    MNC_CHNIAUNICOM1 = 46001,
    MNC_CHNIAUNICOM2 = 46009,
    MNC_COUNT        = 2
};

static const char* mResourcePath[MNC_COUNT][PATH_COUNT] =
{"/system/media/bootanimation1.zip", "/custom/media/bootanimation1.zip", "/data/local/bootanimation1.zip", /*  0  */
 "/system/media/bootanimation2.zip", "/custom/media/bootanimation2.zip", "/data/local/bootanimation2.zip", /*  1  */
};

#endif

#define OEM_BOOTANIMATION_FILE "/oem/media/bootanimation.zip"
#define SYSTEM_BOOTANIMATION_FILE "/system/media/bootanimation.zip"
#define CUSTOM_BOOTANIMATION_FILE "/custom/media/bootanimation.zip"
#define USER_BOOTANIMATION_FILE   "/data/local/bootanimation.zip"
#define SYSTEM_ENCRYPTED_BOOTANIMATION_FILE "/system/media/bootanimation-encrypted.zip"
#define EXIT_PROP_NAME "service.bootanim.exit"

#define SYSTEM_SHUTANIMATION_FILE "/system/media/shutanimation.zip"
#define CUSTOM_SHUTANIMATION_FILE "/custom/media/shutanimation.zip"
#define USER_SHUTANIMATION_FILE   "/data/local/shutanimation.zip"

static char* mAudioPath[2][PATH_COUNT] =
{"/system/media/bootaudio.mp3", "/custom/media/bootaudio.mp3", "/data/local/bootaudio.mp3", /*  bootaudio path  */
 "/system/media/shutaudio.mp3", "/custom/media/shutaudio.mp3", "/data/local/shutaudio.mp3", /*  shutaudio path  */
};

extern "C" int clock_nanosleep(clockid_t clock_id, int flags,
                           const struct timespec *request,
                           struct timespec *remain);

namespace android {

static const int ANIM_ENTRY_NAME_MAX = 256;
static const bool deal_with_encryption = true;

// ---------------------------------------------------------------------------

BootAnimation::BootAnimation() : Thread(false), mZip(NULL)
{
    mSession = new SurfaceComposerClient();
}

BootAnimation::BootAnimation(bool bSetBootOrShutDown, bool bSetPlayMP3,bool bSetRotated) : Thread(false), mZip(NULL)
{
	XLOGD("[BootAnimation %s %s %d]",__FILE__,__FUNCTION__,__LINE__);

	mSession = new SurfaceComposerClient();
	bBootOrShutDown = bSetBootOrShutDown;
	bShutRotate = bSetRotated;
	bPlayMP3 = bSetPlayMP3;
        mProgram = 0;
        bMTKMovie = false;
	XLOGD("[BootAnimation %s %d]bBootOrShutDown=%d,bPlayMP3=%d,bShutRotate=%d",__FUNCTION__,__LINE__,bBootOrShutDown,bPlayMP3,bShutRotate);
}
BootAnimation::~BootAnimation() {
    if (mZip != NULL) {
        delete mZip;
    }

    if (mProgram) {
        XLOGD("mProgram: %d", mProgram);
        glDeleteProgram(mProgram);
    }
}

void BootAnimation::onFirstRef() {
    status_t err = mSession->linkToComposerDeath(this);
    ALOGE_IF(err, "linkToComposerDeath failed (%s) ", strerror(-err));
    if (err == NO_ERROR) {
        run("BootAnimation", PRIORITY_DISPLAY);
    }
}

sp<SurfaceComposerClient> BootAnimation::session() const {
    return mSession;
}


void BootAnimation::binderDied(const wp<IBinder>&)
{
    // woah, surfaceflinger died!
    XLOGD("SurfaceFlinger died, exiting...");

    // calling requestExit() is not enough here because the Surface code
    // might be blocked on a condition variable that will never be updated.
    kill( getpid(), SIGKILL );
    requestExit();
    if (mAudioPlayer != NULL) {
        mAudioPlayer->requestExit();
    }
}

status_t BootAnimation::initTexture(Texture* texture, AssetManager& assets,
        const char* name) {
    Asset* asset = assets.open(name, Asset::ACCESS_BUFFER);
    if (!asset)
        return NO_INIT;
    SkBitmap bitmap;
    SkImageDecoder::DecodeMemory(asset->getBuffer(false), asset->getLength(),
            &bitmap, kUnknown_SkColorType, SkImageDecoder::kDecodePixels_Mode);
    asset->close();
    delete asset;

    // ensure we can call getPixels(). No need to call unlock, since the
    // bitmap will go out of scope when we return from this method.
    bitmap.lockPixels();

    const int w = bitmap.width();
    const int h = bitmap.height();
    const void* p = bitmap.getPixels();

    GLint crop[4] = { 0, h, w, -h };
    texture->w = w;
    texture->h = h;

    glGenTextures(1, &texture->name);
    glBindTexture(GL_TEXTURE_2D, texture->name);

    switch (bitmap.colorType()) {
        case kAlpha_8_SkColorType:
            glTexImage2D(GL_TEXTURE_2D, 0, GL_ALPHA, w, h, 0, GL_ALPHA,
                    GL_UNSIGNED_BYTE, p);
            break;
        case kARGB_4444_SkColorType:
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA,
                    GL_UNSIGNED_SHORT_4_4_4_4, p);
            break;
        case kN32_SkColorType:
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA,
                    GL_UNSIGNED_BYTE, p);
            break;
        case kRGB_565_SkColorType:
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, w, h, 0, GL_RGB,
                    GL_UNSIGNED_SHORT_5_6_5, p);
            break;
        default:
            break;
    }

    glTexParameteriv(GL_TEXTURE_2D, GL_TEXTURE_CROP_RECT_OES, crop);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
    return NO_ERROR;
}

status_t BootAnimation::initTexture(const Animation::Frame& frame)
{
    //StopWatch watch("blah");

    SkBitmap bitmap;
    SkMemoryStream  stream(frame.map->getDataPtr(), frame.map->getDataLength());
    SkImageDecoder* codec = SkImageDecoder::Factory(&stream);
    if (codec) {
        codec->setDitherImage(false);
        codec->decode(&stream, &bitmap,
                kN32_SkColorType,
                SkImageDecoder::kDecodePixels_Mode);
        delete codec;
    }

    // FileMap memory is never released until application exit.
    // Release it now as the texture is already loaded and the memory used for
    // the packed resource can be released.
    frame.map->release();

    // ensure we can call getPixels(). No need to call unlock, since the
    // bitmap will go out of scope when we return from this method.
    bitmap.lockPixels();

    const int w = bitmap.width();
    const int h = bitmap.height();
    const void* p = bitmap.getPixels();

    GLint crop[4] = { 0, h, w, -h };
    int tw = 1 << (31 - __builtin_clz(w));
    int th = 1 << (31 - __builtin_clz(h));
    if (tw < w) tw <<= 1;
    if (th < h) th <<= 1;

    switch (bitmap.colorType()) {
        case kN32_SkColorType:
            if (tw != w || th != h) {
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, tw, th, 0, GL_RGBA,
                        GL_UNSIGNED_BYTE, 0);
                glTexSubImage2D(GL_TEXTURE_2D, 0,
                        0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, p);
            } else {
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, tw, th, 0, GL_RGBA,
                        GL_UNSIGNED_BYTE, p);
            }
            break;

        case kRGB_565_SkColorType:
            if (tw != w || th != h) {
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, tw, th, 0, GL_RGB,
                        GL_UNSIGNED_SHORT_5_6_5, 0);
                glTexSubImage2D(GL_TEXTURE_2D, 0,
                        0, 0, w, h, GL_RGB, GL_UNSIGNED_SHORT_5_6_5, p);
            } else {
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, tw, th, 0, GL_RGB,
                        GL_UNSIGNED_SHORT_5_6_5, p);
            }
            break;
        default:
            break;
    }

    glTexParameteriv(GL_TEXTURE_2D, GL_TEXTURE_CROP_RECT_OES, crop);

    return NO_ERROR;
}

status_t BootAnimation::readyToRun() {
    if (bBootOrShutDown) {
        initBootanimationZip();
    } else {
        initShutanimationZip();
    }
    mAssets.addDefaultAssets();
    if (mZip != NULL) {
        ZipEntryRO desc = mZip->findEntryByName("desc.txt");
        int method;
        mZip->getEntryInfo(desc, &method, NULL, NULL, NULL, NULL, NULL);
        mZip->releaseEntry(desc);
        if (method == ZipFileRO::kCompressStored) {
            bMTKMovie = false;
        } else {
            bMTKMovie = true;
        }
    }
    sp<IBinder> dtoken(SurfaceComposerClient::getBuiltInDisplay(
            ISurfaceComposer::eDisplayIdMain));
    DisplayInfo dinfo;
    status_t status = SurfaceComposerClient::getDisplayInfo(dtoken, &dinfo);
    if (status)
        return -1;

    // create the native surface
    sp<SurfaceControl> control = session()->createSurface(String8("BootAnimation"),
            dinfo.w, dinfo.h, PIXEL_FORMAT_RGB_565);

    SurfaceComposerClient::openGlobalTransaction();
    control->setLayer(0x2000010);
    SurfaceComposerClient::closeGlobalTransaction();

    sp<Surface> s = control->getSurface();

    // initialize opengl and egl
    EGLint w, h, dummy;
    EGLint numConfigs;
    EGLConfig config;
    EGLSurface surface;
    EGLContext context;

    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);

    XLOGD("initialize opengl and egl");
    EGLBoolean eglret = eglInitialize(display, 0, 0);
    if (eglret == EGL_FALSE) {
        XLOGE("eglInitialize(display, 0, 0) return EGL_FALSE");
    }
    if (!bMTKMovie) {
        const EGLint attribs[] = {
                EGL_RED_SIZE,   8,
                EGL_GREEN_SIZE, 8,
                EGL_BLUE_SIZE,  8,
                EGL_DEPTH_SIZE, 0,
                EGL_NONE
        };
        eglChooseConfig(display, attribs, &config, 1, &numConfigs);
        context = eglCreateContext(display, config, NULL, NULL);
     } else {
        const EGLint attribs[] = {
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL_RED_SIZE,   5,
            EGL_GREEN_SIZE, 6,
            EGL_BLUE_SIZE,  5,
            EGL_DEPTH_SIZE, 16,
            EGL_NONE
        };
        eglChooseConfig(display, attribs, &config, 1, &numConfigs); 
        int attrib_list[] = {EGL_CONTEXT_CLIENT_VERSION, 2,
                       EGL_NONE, EGL_NONE}; 
        context = eglCreateContext(display, config, EGL_NO_CONTEXT, attrib_list);
                       
        
    }

    surface = eglCreateWindowSurface(display, config, s.get(), NULL);
    eglret = eglQuerySurface(display, surface, EGL_WIDTH, &w);
    if (eglret == EGL_FALSE) {
        XLOGE("eglQuerySurface(display, surface, EGL_WIDTH, &w) return EGL_FALSE");
    }
    eglret = eglQuerySurface(display, surface, EGL_HEIGHT, &h);
    if (eglret == EGL_FALSE) {
        XLOGE("eglQuerySurface(display, surface, EGL_HEIGHT, &h) return EGL_FALSE");
    }

    if (eglMakeCurrent(display, surface, surface, context) == EGL_FALSE) {
        XLOGE("eglMakeCurrent(display, surface, surface, context) return EGL_FALSE");
        return NO_INIT;
    }

    mDisplay = display;
    mContext = context;
    mSurface = surface;
    mWidth = w;
    mHeight = h;
    mFlingerSurfaceControl = control;
    mFlingerSurface = s;

    return NO_ERROR;
}

bool BootAnimation::threadLoop()
{
    bool r;
    // We have no bootanimation file, so we use the stock android logo
    // animation.
    sp<MediaPlayer> mediaplayer;
    char* resourcePath = initAudioPath();
    status_t mediastatus = NO_ERROR;
    if (resourcePath != NULL) {
        XLOGD("sound file path: %s", resourcePath);
        mediaplayer = new MediaPlayer();
        mediastatus = mediaplayer->setDataSource(NULL, resourcePath, NULL);
        if (mediastatus == NO_ERROR) {
            mediaplayer->setAudioStreamType(AUDIO_STREAM_BOOT);
            mediastatus = mediaplayer->prepare();
        }
        if (mediastatus == NO_ERROR) {		    
            mediastatus = mediaplayer->start();
        }
    }
    if (mZip == NULL) {
        r = android();
    } else {
        if (!bMTKMovie) {
            XLOGD("threadLoop() movie()");
            r = movie();
        } else {
            XLOGD("threadLoop() MTKmovie()");
            r = MTKmovie();
        }
    }
    if (resourcePath != NULL) {
        if (mediastatus == NO_ERROR) {		    
            mediaplayer->stop();
            mediaplayer->disconnect();
            mediaplayer.clear();
        }
    }
    eglMakeCurrent(mDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroyContext(mDisplay, mContext);
    eglDestroySurface(mDisplay, mSurface);
    mFlingerSurface.clear();
    mFlingerSurfaceControl.clear();
    eglTerminate(mDisplay);
    IPCThreadState::self()->stopProcess();
    return r;
}

bool BootAnimation::android()
{
    initTexture(&mAndroid[0], mAssets, "images/android-logo-mask.png");
    initTexture(&mAndroid[1], mAssets, "images/android-logo-shine.png");

    // clear screen
    glShadeModel(GL_FLAT);
    glDisable(GL_DITHER);
    glDisable(GL_SCISSOR_TEST);
    glClearColor(0,0,0,1);
    glClear(GL_COLOR_BUFFER_BIT);
    eglSwapBuffers(mDisplay, mSurface);

    glEnable(GL_TEXTURE_2D);
    glTexEnvx(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_REPLACE);

    const GLint xc = (mWidth  - mAndroid[0].w) / 2;
    const GLint yc = (mHeight - mAndroid[0].h) / 2;
// const Rect updateRect(xc, yc, xc + mAndroid[0].w, yc + mAndroid[0].h);  //MR1 ADDED

    int x = xc, y = yc;
    int w = mAndroid[0].w, h = mAndroid[0].h;
    if (x < 0) {
        w += x;
        x  = 0;
    }
    if (y < 0) {
        h += y;
        y  = 0;
    }
    if (w > mWidth) {
        w = mWidth;
    }
    if (h > mHeight) {
        h = mHeight;
    }
	XLOGD("[BootAnimation %s %d]x=%d,y=%d,w=%d,h=%d",__FUNCTION__,__LINE__,x,y,w,h);

    const Rect updateRect(x, y, x+w, y+h);

    glScissor(updateRect.left, mHeight - updateRect.bottom, updateRect.width(),
            updateRect.height());

    // Blend state
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    glTexEnvx(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_REPLACE);

    const nsecs_t startTime = systemTime();
    do {
        nsecs_t now = systemTime();
        double time = now - startTime;
        float t = 4.0f * float(time / us2ns(16667)) / mAndroid[1].w;
        GLint offset = (1 - (t - floorf(t))) * mAndroid[1].w;
        GLint x = xc - offset;

        glDisable(GL_SCISSOR_TEST);
        glClear(GL_COLOR_BUFFER_BIT);

        glEnable(GL_SCISSOR_TEST);
        glDisable(GL_BLEND);
        glBindTexture(GL_TEXTURE_2D, mAndroid[1].name);
        glDrawTexiOES(x,                 yc, 0, mAndroid[1].w, mAndroid[1].h);
        glDrawTexiOES(x + mAndroid[1].w, yc, 0, mAndroid[1].w, mAndroid[1].h);

        glEnable(GL_BLEND);
        glBindTexture(GL_TEXTURE_2D, mAndroid[0].name);
        glDrawTexiOES(xc, yc, 0, mAndroid[0].w, mAndroid[0].h);

        EGLBoolean res = eglSwapBuffers(mDisplay, mSurface);
        if (res == EGL_FALSE)
            break;

        // 12fps: don't animate too fast to preserve CPU
        const nsecs_t sleepTime = 83333 - ns2us(systemTime() - now);
        if (sleepTime > 0)
            usleep(sleepTime);

        checkExit();
    } while (!exitPending());

    glDeleteTextures(1, &mAndroid[0].name);
    glDeleteTextures(1, &mAndroid[1].name);
    return false;
}


void BootAnimation::checkExit() {
    // Allow surface flinger to gracefully request shutdown
    char value[PROPERTY_VALUE_MAX];
    property_get(EXIT_PROP_NAME, value, "0");
    int exitnow = atoi(value);
    if (exitnow) {
        requestExit();
        if (mAudioPlayer != NULL) {
            mAudioPlayer->requestExit();
        }
    }
}

// Parse a color represented as an HTML-style 'RRGGBB' string: each pair of
// characters in str is a hex number in [0, 255], which are converted to
// floating point values in the range [0.0, 1.0] and placed in the
// corresponding elements of color.
//
// If the input string isn't valid, parseColor returns false and color is
// left unchanged.
static bool parseColor(const char str[7], float color[3]) {
    float tmpColor[3];
    for (int i = 0; i < 3; i++) {
        int val = 0;
        for (int j = 0; j < 2; j++) {
            val *= 16;
            char c = str[2*i + j];
            if      (c >= '0' && c <= '9') val += c - '0';
            else if (c >= 'A' && c <= 'F') val += (c - 'A') + 10;
            else if (c >= 'a' && c <= 'f') val += (c - 'a') + 10;
            else                           return false;
        }
        tmpColor[i] = static_cast<float>(val) / 255.0f;
    }
    memcpy(color, tmpColor, sizeof(tmpColor));
    return true;
}

bool BootAnimation::readFile(const char* name, String8& outString)
{
    ZipEntryRO entry = mZip->findEntryByName(name);
    ALOGE_IF(!entry, "couldn't find %s", name);
    if (!entry) {
        return false;
    }

    FileMap* entryMap = mZip->createEntryFileMap(entry);
    mZip->releaseEntry(entry);
    ALOGE_IF(!entryMap, "entryMap is null");
    if (!entryMap) {
        return false;
    }

    outString.setTo((char const*)entryMap->getDataPtr(), entryMap->getDataLength());
    entryMap->release();
    return true;
}

bool BootAnimation::movie()
{
    String8 desString;

    if (!readFile("desc.txt", desString)) {
        return false;
    }
    char const* s = desString.string();

    // Create and initialize an AudioPlayer if we have an audio_conf.txt file
    String8 audioConf;
    if (readFile("audio_conf.txt", audioConf)) {
        mAudioPlayer = new AudioPlayer;
        if (!mAudioPlayer->init(audioConf.string())) {
            ALOGE("mAudioPlayer.init failed");
            mAudioPlayer = NULL;
        }
    }

    Animation animation;

    // Parse the description file
    for (;;) {
        const char* endl = strstr(s, "\n");
        if (!endl) break;
        String8 line(s, endl - s);
        const char* l = line.string();
        int fps, width, height, count, pause;
        char path[ANIM_ENTRY_NAME_MAX];
        char color[7] = "000000"; // default to black if unspecified

        char pathType;
        if (sscanf(l, "%d %d %d", &width, &height, &fps) == 3) {
            // ALOGD("> w=%d, h=%d, fps=%d", width, height, fps);
            animation.width = width;
            animation.height = height;
            animation.fps = fps;
        }
        else if (sscanf(l, " %c %d %d %s #%6s", &pathType, &count, &pause, path, color) >= 4) {
            // ALOGD("> type=%c, count=%d, pause=%d, path=%s, color=%s", pathType, count, pause, path, color);
            Animation::Part part;
            part.playUntilComplete = pathType == 'c';
            part.count = count;
            part.pause = pause;
            part.path = path;
            part.audioFile = NULL;
            if (!parseColor(color, part.backgroundColor)) {
                ALOGE("> invalid color '#%s'", color);
                part.backgroundColor[0] = 0.0f;
                part.backgroundColor[1] = 0.0f;
                part.backgroundColor[2] = 0.0f;
            }
            animation.parts.add(part);
        }

        s = ++endl;
    }

    // read all the data structures
    const size_t pcount = animation.parts.size();
    void *cookie = NULL;
    if (!mZip->startIteration(&cookie)) {
        return false;
    }

    ZipEntryRO entry;
    char name[ANIM_ENTRY_NAME_MAX];
    while ((entry = mZip->nextEntry(cookie)) != NULL) {
        const int foundEntryName = mZip->getEntryFileName(entry, name, ANIM_ENTRY_NAME_MAX);
        if (foundEntryName > ANIM_ENTRY_NAME_MAX || foundEntryName == -1) {
            ALOGE("Error fetching entry file name");
            continue;
        }

        const String8 entryName(name);
        const String8 path(entryName.getPathDir());
        const String8 leaf(entryName.getPathLeaf());
        if (leaf.size() > 0) {
            for (size_t j=0 ; j<pcount ; j++) {
                if (path == animation.parts[j].path) {
                    int method;
                    // supports only stored png files
                    if (mZip->getEntryInfo(entry, &method, NULL, NULL, NULL, NULL, NULL)) {
                        if (method == ZipFileRO::kCompressStored) {
                            FileMap* map = mZip->createEntryFileMap(entry);
                            if (map) {
                                Animation::Part& part(animation.parts.editItemAt(j));
                                if (leaf == "audio.wav") {
                                    // a part may have at most one audio file
                                    part.audioFile = map;
                                } else {
                                    Animation::Frame frame;
                                    frame.name = leaf;
                                    frame.map = map;
                                    part.frames.add(frame);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    mZip->endIteration(cookie);

    // clear screen
    glShadeModel(GL_FLAT);
    glDisable(GL_DITHER);
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_BLEND);
    glClearColor(0,0,0,1);
    glClear(GL_COLOR_BUFFER_BIT);

    eglSwapBuffers(mDisplay, mSurface);

    glBindTexture(GL_TEXTURE_2D, 0);
    glEnable(GL_TEXTURE_2D);
    glTexEnvx(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_REPLACE);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

    const int xc = (mWidth - animation.width) / 2;
    const int yc = ((mHeight - animation.height) / 2);
    nsecs_t lastFrame = systemTime();
    nsecs_t frameDuration = s2ns(1) / animation.fps;

    Region clearReg(Rect(mWidth, mHeight));
    clearReg.subtractSelf(Rect(xc, yc, xc+animation.width, yc+animation.height));

    for (size_t i=0 ; i<pcount ; i++) {
        const Animation::Part& part(animation.parts[i]);
        const size_t fcount = part.frames.size();
        glBindTexture(GL_TEXTURE_2D, 0);

        for (int r=0 ; !part.count || r<part.count ; r++) {
            // Exit any non playuntil complete parts immediately
            if(exitPending() && !part.playUntilComplete)
                break;

            // only play audio file the first time we animate the part
            if (r == 0 && mAudioPlayer != NULL && part.audioFile) {
                mAudioPlayer->playFile(part.audioFile);
            }

            glClearColor(
                    part.backgroundColor[0],
                    part.backgroundColor[1],
                    part.backgroundColor[2],
                    1.0f);

            for (size_t j=0 ; j<fcount && (!exitPending() || part.playUntilComplete) ; j++) {
                const Animation::Frame& frame(part.frames[j]);
                nsecs_t lastFrame = systemTime();

                if (r > 0) {
                    glBindTexture(GL_TEXTURE_2D, frame.tid);
                } else {
                    if (part.count != 1) {
                        glGenTextures(1, &frame.tid);
                        glBindTexture(GL_TEXTURE_2D, frame.tid);
                        glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
                        glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                    }
                    initTexture(frame);
                }

                if (!clearReg.isEmpty()) {
                    Region::const_iterator head(clearReg.begin());
                    Region::const_iterator tail(clearReg.end());
                    glEnable(GL_SCISSOR_TEST);
                    while (head != tail) {
                        const Rect& r(*head++);
                        glScissor(r.left, mHeight - r.bottom,
                                r.width(), r.height());
                        glClear(GL_COLOR_BUFFER_BIT);
                    }
                    glDisable(GL_SCISSOR_TEST);
                }
                glDrawTexiOES(xc, yc, 0, animation.width, animation.height);
                eglSwapBuffers(mDisplay, mSurface);

                nsecs_t now = systemTime();
                nsecs_t delay = frameDuration - (now - lastFrame);
                XLOGD("%lld, %lld", ns2ms(now - lastFrame), ns2ms(delay));
                lastFrame = now;

                if (delay > 0) {
                    struct timespec spec;
                    spec.tv_sec  = (now + delay) / 1000000000;
                    spec.tv_nsec = (now + delay) % 1000000000;
                    int err;
                    do {
                        err = clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &spec, NULL);
                    } while (err<0 && errno == EINTR);
                }

                checkExit();
            }

            usleep(part.pause * ns2us(frameDuration));

            // For infinite parts, we've now played them at least once, so perhaps exit
            if(exitPending() && !part.count)
                break;
        }

        // free the textures for this part
        if (part.count != 1) {
            for (size_t j=0 ; j<fcount ; j++) {
                const Animation::Frame& frame(part.frames[j]);
                glDeleteTextures(1, &frame.tid);
            }
        }
    }

    return false;
}

// ---------------------------------------------------------------------------
char* BootAnimation::initAudioPath() {
    if (!bPlayMP3) {
        XLOGD("initAudioPath: DON'T PLAY AUDIO!");
        return NULL;
    }

    char crypto_state[PROPERTY_VALUE_MAX];
    char type[PROPERTY_VALUE_MAX];
    char status[PROPERTY_VALUE_MAX];
    char volume[PROPERTY_VALUE_MAX];

    property_get("ro.crypto.state", crypto_state, "-1");
    property_get("vold.encryption.type", type, "-1");
    XLOGD("[BootAnimation %s %d]ro.crypto.state=%s, vold.encryption.type=%s",
          __FUNCTION__,__LINE__, crypto_state, type);
    while (strcmp(type, "-1") == 0 && 
           strcmp(crypto_state, "unencrypted") != 0 && 
           deal_with_encryption == true){
        usleep(100000);
        property_get("ro.crypto.state", crypto_state, "-1");
        property_get("vold.encryption.type", type, "-1");
    }

    while (strcmp(type, "default") == 0 && deal_with_encryption == true){
        property_get("vold.decrypt", status, "-1");
        if (strcmp(status, "trigger_restart_framework") != 0){
            usleep(100000);
            XLOGD("[BootAnimation %s %d]Decrypt status=%s",__FUNCTION__,__LINE__,status);
            continue;
        }
        property_get("persist.sys.mute.state", volume, "-1");
        int nVolume = -1;
        nVolume = atoi(volume);
        XLOGD("[BootAnimation %s %d]nVolume=%d",__FUNCTION__,__LINE__,nVolume); 
        if(nVolume == 0 || nVolume == 1){
            XLOGD("initAudioPath: DON'T PLAY AUDIO!");
            return NULL;
        }
        break;
    }

    int index = 0;
    if (bBootOrShutDown) {
        index = 0;
    } else {
        index = 1;
    }

    for (int i = 0; i < PATH_COUNT; i++) {
        if (access(mAudioPath[index][i], F_OK) == 0) {
            XLOGD("initAudioPath: audio path = %s", mAudioPath[index][i]);
            return mAudioPath[index][i];
        }
    }
    return NULL;
}
void BootAnimation::initBootanimationZip() {
    ZipFileRO* zipFile = NULL;
#ifdef MTK_TER_SERVICE
    if ((access(CUSTOM_REGIONALPHONE_DB, F_OK) == 0)||(access(SYSTEM_REGIONALPHONE_DB, F_OK) == 0)) {
        XLOGD("regionalphone.db check OK");
        char BootanimFileName[PROPERTY_VALUE_MAX];
        // use property to set resource zip
        if (property_get(REGIONAL_BOOTANIM_FILE_NAME, BootanimFileName, NULL) <= 0) {
            XLOGD("[BootAnimation %s %d]need get the bootanimation zip path for regional phone",__FUNCTION__,__LINE__);

            // get the terservice for regional phone
            sp<ITerService> terService = 0;
            const String16 serviceName("terservice");
            sp<IBinder> service = defaultServiceManager()->checkService(serviceName);
            if(service != NULL) {
                status_t terService_err = getService(serviceName,&terService);
                if (terService_err == NO_ERROR && terService->isEarlyReadServiceEnabled()) {
                    while(true) {
                        if(!terService->isEarlyDataReady()) {
                            usleep(100000);
                            continue;
                        }
                        if (property_get(REGIONAL_BOOTANIM_FILE_NAME, BootanimFileName, NULL) > 0) {
                            XLOGD("[BootAnimation %s %d]use the bootanimation zip path = %s",__FUNCTION__,__LINE__, BootanimFileName);
                            if ((access(BootanimFileName, R_OK) == 0) && 
                                ((zipFile = ZipFileRO::open(BootanimFileName)) != NULL)) {
                                mZip = zipFile;
                            }
                            break;
                        } else {
                            String8 mncStr("");
                            status_t mnc_err = terService->getSimMccMnc(&mncStr);
                            XLOGD("[BootAnimation %s %d]mnc_err= %d",__FUNCTION__,__LINE__,mnc_err);
                            if (mnc_err == NO_ERROR) {
                                XLOGD("[BootAnimation %s %d]mncStr= %d",__FUNCTION__,__LINE__,atoi(mncStr));
                                property_set(REGIONAL_BOOTANIM_GET_MNC, mncStr);
                                int index = -1;
                                switch (atoi(mncStr)) {
                                case MNC_VODAFONE:
                                    index = 0;
                                    break;
                                case MNC_CHNIAUNICOM1:
                                case MNC_CHNIAUNICOM2:
                                    index = 1;
                                    break;
                                default :
                                    XLOGD("[BootAnimation %s %d]get mnc invalid: not 46692 or 46001, quit get mnc",__FUNCTION__,__LINE__);
                                    break;
                                }
                                if (index >= 0) {
                                    for (int i = 0; i < PATH_COUNT; i++) {
                                        if ((access(mResourcePath[index][i], F_OK) == 0) && 
                                            ((zipFile = ZipFileRO::open(mResourcePath[index][i])) != NULL)) {
                                            mZip = zipFile;
                                            property_set(REGIONAL_BOOTANIM_FILE_NAME, mResourcePath[index][i]);
                                            XLOGD("[BootAnimation %s %d]logopath= %s", __FUNCTION__, __LINE__, mResourcePath[index][i]);
                                            break;
                                        }
                                    } 
                                }
                            } else {
                                XLOGD("[BootAnimation %s %d]get mnc error, quit get mnc",__FUNCTION__,__LINE__);
                            }
                            break;
                        }
                    }
                }
            }
        } else {
            XLOGD("[BootAnimation %s %d]use the bootanimation zip path = %s",__FUNCTION__,__LINE__, BootanimFileName);
            if ((access(BootanimFileName, R_OK) == 0) && 
                ((zipFile = ZipFileRO::open(BootanimFileName)) != NULL)) {
                mZip = zipFile;
            }
        }

    }
#endif
    if (zipFile == NULL) {
        if (((access(SYSTEM_BOOTANIMATION_FILE, R_OK) == 0) &&
            ((zipFile = ZipFileRO::open(SYSTEM_BOOTANIMATION_FILE)) != NULL)) ||

            ((access(CUSTOM_BOOTANIMATION_FILE, R_OK) == 0) &&
            ((zipFile = ZipFileRO::open(CUSTOM_BOOTANIMATION_FILE)) != NULL)) ||

            ((access(USER_BOOTANIMATION_FILE, R_OK) == 0) &&
            ((zipFile = ZipFileRO::open(USER_BOOTANIMATION_FILE)) != NULL))) {
            mZip = zipFile;
        }
    }
}

void BootAnimation::initShutanimationZip() {
    ZipFileRO* zipFile = NULL;
    if (((access(SYSTEM_SHUTANIMATION_FILE, R_OK) == 0) &&
            ((zipFile = ZipFileRO::open(SYSTEM_SHUTANIMATION_FILE)) != NULL)) ||

            ((access(CUSTOM_SHUTANIMATION_FILE, R_OK) == 0) &&
            ((zipFile = ZipFileRO::open(CUSTOM_SHUTANIMATION_FILE)) != NULL)) ||

            ((access(USER_SHUTANIMATION_FILE, R_OK) == 0) &&
            ((zipFile = ZipFileRO::open(USER_SHUTANIMATION_FILE)) != NULL))) {
            mZip = zipFile;
    }
}

status_t BootAnimation::initTexture(const char* EntryName)
{
    /* Calculate number of Mipmap levels. */
    XLOGD("[BootAnimation %s %d] ",__FUNCTION__,__LINE__);
    ZipEntryRO entry = mZip->findEntryByName(EntryName);
    ALOGE_IF(!entry, "couldn't find EntryName = %s", EntryName);
    if (!entry) {
        return -1;
    }
    size_t actualLen;

    mZip->getEntryInfo(entry, NULL, &actualLen, NULL, NULL, NULL, NULL);
    XLOGD("[BootAnimation %s %d]   actualLen = %d ",__FUNCTION__,__LINE__, actualLen);
    char* buffer = (char*) malloc(actualLen);
    if (buffer == NULL) {
        XLOGD("[BootAnimation %s %d]  malloc failed",__FUNCTION__,__LINE__);
        mZip->releaseEntry(entry);
        return NO_MEMORY;
    }
    if (!mZip->uncompressEntry(entry, buffer, actualLen)) {
       XLOGD("[BootAnimation %s %d]  uncompressEntry failed",__FUNCTION__,__LINE__);
       mZip->releaseEntry(entry);
       return -1;
    }
    mZip->releaseEntry(entry);
    int width = etc1_pkm_get_width((unsigned char *)buffer);
    int height = etc1_pkm_get_height((unsigned char *)buffer);

    int Size = etc1_get_encoded_data_size(width, height);

    /* Load base Mipmap level into level 0 of texture.
     * Skip the 16 byte header of the PKM file before passing the data to OpenGL ES.
     * Data size (taken in number of bytes) of the texture is:
     *      Number of pixels = padded width * padded height.
     *      The number of pixels is divided by two as there are 4 bits per pixel in ETC (half a byte)
     */
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glCompressedTexImage2D(GL_TEXTURE_2D, 0, GL_ETC1_RGB8_OES, width, height, 0, Size, buffer + 16);
    XLOGD("[BootAnimation %s %d]  texture width = %d, height =%d",__FUNCTION__,__LINE__, width, height);

    free(buffer);
    return NO_ERROR;
}

// ---------------------------------------------------------------------------

GLuint BootAnimation::buildShader(const char* source, GLenum shaderType)
{
    GLuint shaderHandle = glCreateShader(shaderType);

    if (shaderHandle)
    {
        glShaderSource(shaderHandle, 1, &source, 0);
        glCompileShader(shaderHandle);

        GLint compiled = 0;
        glGetShaderiv(shaderHandle, GL_COMPILE_STATUS, &compiled);
        if (!compiled)
        {
            GLint infoLen = 0;
            glGetShaderiv(shaderHandle, GL_INFO_LOG_LENGTH, &infoLen);
            if (infoLen)
            {
                char* buf = (char*) malloc(infoLen);
                if (buf)
                {
                    glGetShaderInfoLog(shaderHandle, infoLen, NULL, buf);
                    XLOGD("error::Could not compile shader %d:\n%s\n", shaderType, buf);
                    free(buf);
                }
                glDeleteShader(shaderHandle);
                shaderHandle = 0;
            }
        }

    }

    return shaderHandle;
}  

GLuint BootAnimation::buildProgram (const char* vertexShaderSource,
        const char* fragmentShaderSource)
{
    GLuint vertexShader = buildShader(vertexShaderSource, GL_VERTEX_SHADER);
    GLuint fragmentShader = buildShader(fragmentShaderSource, GL_FRAGMENT_SHADER);
    GLuint programHandle = glCreateProgram();

    if (programHandle)
    {
        glAttachShader(programHandle, vertexShader);
        glAttachShader(programHandle, fragmentShader);
        glLinkProgram(programHandle);

        GLint linkStatus = GL_FALSE;
        glGetProgramiv(programHandle, GL_LINK_STATUS, &linkStatus);
        if (linkStatus != GL_TRUE) {
            GLint bufLength = 0;
            glGetProgramiv(programHandle, GL_INFO_LOG_LENGTH, &bufLength);
            if (bufLength) {
                char* buf = (char*) malloc(bufLength);
                if (buf) {
                    glGetProgramInfoLog(programHandle, bufLength, NULL, buf);
                    XLOGD("error::Could not link program:\n%s\n", buf);
                    free(buf);
                }
            }
            glDeleteProgram(programHandle);
            programHandle = 0;
        }

    }
    return programHandle;
}

 void BootAnimation::initShader() {

    const char* VERTEX_SHADER =
        "attribute vec4 a_position;\n"
        "attribute vec2 a_texCoord;\n"
        "varying vec2 v_texCoord;\n"
        "void main()\n"
        "{\n"
            "gl_Position = a_position;\n"
            "v_texCoord = a_texCoord;\n"
        "}\n";

    const char* FRAG_SHADER =
        "precision mediump float;\n"
        "varying vec2 v_texCoord;\n"
        "uniform sampler2D u_samplerTexture;\n"
        "void main()\n"
        "{\n"
            "gl_FragColor = texture2D(u_samplerTexture, v_texCoord);\n"
        "}\n";

    mProgram = buildProgram(VERTEX_SHADER, FRAG_SHADER);
    mAttribPosition = glGetAttribLocation(mProgram, "a_position");
    mAttribTexCoord = glGetAttribLocation(mProgram, "a_texCoord");
    mUniformTexture = glGetUniformLocation(mProgram, "u_samplerTexture");
    glUseProgram (mProgram);
}

bool BootAnimation::MTKmovie()
{
    ZipEntryRO desc = mZip->findEntryByName("desc.txt");
    ALOGE_IF(!desc, "couldn't find desc.txt");
    if (!desc) {
        return false;
    }
    size_t uncomplen = 0;
    char* tmp = NULL;
    mZip->getEntryInfo(desc, NULL, &uncomplen, NULL, NULL, NULL, NULL);
    XLOGD("[BootAnimation %s %d]   uncomplen = %d ",__FUNCTION__,__LINE__, uncomplen);
    tmp = (char*) malloc(uncomplen);
    mZip->uncompressEntry(desc, tmp, uncomplen);
    String8 desString((char const*)tmp, uncomplen);
    free(tmp);
    mZip->releaseEntry(desc);
    char const* s = desString.string();

    Animation animation;

    // Parse the description file
    for (;;) {
        const char* endl = strstr(s, "\n");
        if (!endl) break;
        String8 line(s, endl - s);
        const char* l = line.string();
        int fps, width, height, count, pause;
        char path[ANIM_ENTRY_NAME_MAX];
        char color[7] = "000000"; // default to black if unspecified
        char pathType;
        if (sscanf(l, "%d %d %d", &width, &height, &fps) == 3) {
            XLOGD("> w=%d, h=%d, fps=%d", width, height, fps); // add log
            animation.width = width;
            animation.height = height;
            animation.fps = fps;
        }
        else if (sscanf(l, " %c %d %d %s", &pathType, &count, &pause, path) == 4) {
            XLOGD("> type=%c, count=%d, pause=%d, path=%s", pathType, count, pause, path); // add log
            Animation::Part part;
            part.playUntilComplete = pathType == 'c';
            part.count = count;
            part.pause = pause;
            part.path = path;
            animation.parts.add(part);
            if (!parseColor(color, part.backgroundColor)) {
                XLOGD("> invalid color '#%s'", color);
                part.backgroundColor[0] = 0.0f;
                part.backgroundColor[1] = 0.0f;
                part.backgroundColor[2] = 0.0f;
            }
        }

        s = ++endl;
    }

    // read all the data structures
    const size_t pcount = animation.parts.size();
    void *cookie = NULL;
    if (!mZip->startIteration(&cookie)) {
        return false;
    }

    ZipEntryRO entry;
    char name[ANIM_ENTRY_NAME_MAX];
    while ((entry = mZip->nextEntry(cookie)) != NULL) {
        const int foundEntryName = mZip->getEntryFileName(entry, name, ANIM_ENTRY_NAME_MAX);
        if (foundEntryName > ANIM_ENTRY_NAME_MAX || foundEntryName == -1) {
            ALOGE("Error fetching entry file name");
            continue;
        }

        const String8 entryName(name);
        const String8 path(entryName.getPathDir());
        const String8 leaf(entryName.getPathLeaf());
        if (leaf.size() > 0) {
            for (size_t j=0 ; j<pcount ; j++) {
                if (path == animation.parts[j].path) {
                    Animation::Frame frame;
                    frame.name = leaf;
                            // frame.map = map;
                    frame.fullPath = entryName;
                    Animation::Part& part(animation.parts.editItemAt(j));
                    part.frames.add(frame);
                }
            }
        }
    }

    mZip->endIteration(cookie);

    initShader();

    glViewport((mWidth - animation.width) >> 1, (mHeight - animation.height) >> 1,
            animation.width, animation.height);
    // clear screen
    glDisable(GL_DITHER);
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_BLEND);
    glClear(GL_COLOR_BUFFER_BIT);

    eglSwapBuffers(mDisplay, mSurface);

    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, 0);
    glUniform1i(mUniformTexture, 0);

    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

    nsecs_t lastFrame = systemTime();
    nsecs_t frameDuration = s2ns(1) / animation.fps;
    XLOGD("[BootAnimation %s %d]lastFrame=%lld,frameDuration=%lld,ms=%lld",__FUNCTION__,__LINE__,lastFrame,frameDuration,ns2ms(systemTime()));

    for (size_t i=0 ; i<pcount ; i++) {
        const Animation::Part& part(animation.parts[i]);
        const size_t fcount = part.frames.size();
        XLOGD("[BootAnimation %s %d]i=%d,i<pcount=%d,r<part.count=%d,j<fcount=%d",__FUNCTION__,__LINE__,i,pcount,part.count,fcount);
        glBindTexture(GL_TEXTURE_2D, 0);

        for (int r=0 ; !part.count || r<part.count ; r++) {
            // Exit any non playuntil complete parts immediately
            if(exitPending() && !part.playUntilComplete) {
                XLOGD("[BootAnimation %s %d]part.playUntilComplete=%d", __FUNCTION__, __LINE__ ,part.playUntilComplete);
                break;
             }
            glClearColor(
                    part.backgroundColor[0],
                    part.backgroundColor[1],
                    part.backgroundColor[2],
                    1.0f);
            for (size_t j=0 ; j<fcount && (!exitPending() || part.playUntilComplete) ; j++) {
                const Animation::Frame& frame(part.frames[j]);
                nsecs_t lastFrame = systemTime();
                XLOGD("[BootAnimation %s %d]i=%d,r=%d,j=%d,lastFrame=%lld(%lld ms),file=%s",__FUNCTION__,__LINE__,i,r,j,lastFrame,ns2ms(lastFrame),frame.name.string());
                if (r > 0) {
                    glBindTexture(GL_TEXTURE_2D, frame.tid);
                } else {
                    if (part.count != 1) {
                        glGenTextures(1, &frame.tid);
                        glBindTexture(GL_TEXTURE_2D, frame.tid);
                        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
                        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                    }
                    initTexture(frame.fullPath.string());
                }

                static GLfloat quadVertex[] = { -1.0f,  1.0f, 0.0f,  // Position 0
                                                0.0f,  0.0f,        // TexCoord 0
                                                -1.0f, -1.0f, 0.0f,  // Position 1                           
                                                0.0f,  1.0f,        // TexCoord 1                           
                                                1.0f, -1.0f, 0.0f,  // Position 2                            
                                                1.0f,  1.0f,        // TexCoord 2                            
                                                1.0f,  1.0f, 0.0f,  // Position 3                            
                                                1.0f,  0.0f         // TexCoord 3                         
                                              };

                static GLushort quadIndex[] = { 0, 1, 2, 0, 2, 3 };
                glVertexAttribPointer(mAttribPosition, 
                                     3, GL_FLOAT, 
                                     false, 5*sizeof(GL_FLOAT), quadVertex);
                           
                glVertexAttribPointer(mAttribTexCoord, 
                                     2, GL_FLOAT,
                                     false, 5*sizeof(GL_FLOAT), &quadVertex[3]);
                glEnableVertexAttribArray(mAttribPosition);        
                glEnableVertexAttribArray(mAttribTexCoord);

                glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_SHORT, quadIndex);
                eglSwapBuffers(mDisplay, mSurface);

                nsecs_t now = systemTime();
                nsecs_t delay = frameDuration - (now - lastFrame);
                XLOGD("[BootAnimation %s %d]%lld,delay=%lld",__FUNCTION__,__LINE__,ns2ms(now - lastFrame), ns2ms(delay));
                lastFrame = now;

                if (delay > 0) {
                    struct timespec spec;
                    spec.tv_sec  = (now + delay) / 1000000000;
                    spec.tv_nsec = (now + delay) % 1000000000;
                    int err;
                    do {
                        err = clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &spec, NULL);
                    } while (err<0 && errno == EINTR);
                }

                checkExit();
            }

            usleep(part.pause * ns2us(frameDuration));

            // For infinite parts, we've now played them at least once, so perhaps exit
            if(exitPending() && !part.count) {
                XLOGD("[BootAnimation %s %d]break,exitPending()=%d,part.count=%d",__FUNCTION__,__LINE__,exitPending(),part.count);
                break;
            }
        }

        // free the textures for this part
        if (part.count != 1) {
            for (size_t j=0 ; j<fcount ; j++) {
                const Animation::Frame& frame(part.frames[j]);
                glDeleteTextures(1, &frame.tid);
                XLOGD("[BootAnimation %s %d]del,part.count=%d,j=%d,fcount=%d",__FUNCTION__,__LINE__,part.count,j,fcount);
            }
        }
    }
    XLOGD("[BootAnimation %s %d]end",__FUNCTION__,__LINE__);
    return false;
}

}
; // namespace android
