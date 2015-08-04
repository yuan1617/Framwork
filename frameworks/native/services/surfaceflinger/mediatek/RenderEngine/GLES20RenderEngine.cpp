/*
 * Copyright 2013 The Android Open Source Project
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

#define ATRACE_TAG ATRACE_TAG_GRAPHICS

#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#include <utils/String8.h>
#include <utils/Trace.h>

#include <cutils/xlog.h>
#include <cutils/compiler.h>

#include "RenderEngine/GLES20RenderEngine.h"
#include "RenderEngine/Program.h"
#include "RenderEngine/ProgramCache.h"
#include "RenderEngine/Description.h"
#include "RenderEngine/Mesh.h"
#include "RenderEngine/Texture.h"

// ---------------------------------------------------------------------------
namespace android {
// ---------------------------------------------------------------------------

void GLES20RenderEngine::setupLayerProtectImage() {
    Mutex::Autolock l(mProtectImageLock);

    if (-1U == createAndBindProtectImageTexLocked()) {
        XLOGE("Failed to bind DRM image texture");
        return;
    }

    // setup texture
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);

    Texture texture(Texture::TEXTURE_2D, mProtectImageTexName);
    texture.setDimensions(mProtectImageWidth, mProtectImageHeight);
    mState.setTexture(texture);
}

// ---------------------------------------------------------------------------
}; // namespace android
// ---------------------------------------------------------------------------

#if defined(__gl_h_)
#error "don't include gl/gl.h in this file"
#endif
