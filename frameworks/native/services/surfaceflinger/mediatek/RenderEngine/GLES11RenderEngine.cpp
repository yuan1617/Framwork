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

#include <GLES/gl.h>
#include <GLES/glext.h>

#include <utils/String8.h>

#include <cutils/xlog.h>
#include <cutils/compiler.h>

#include "RenderEngine/GLES11RenderEngine.h"
#include "RenderEngine/Mesh.h"
#include "RenderEngine/Texture.h"

// ---------------------------------------------------------------------------
namespace android {
// ---------------------------------------------------------------------------

void GLES11RenderEngine::setupLayerProtectImage() {
    Mutex::Autolock l(mProtectImageLock);

    if (-1U == createAndBindProtectImageTexLocked()) {
        XLOGE("Failed to bind DRM image texture");
        return;
    }

    // setup texture
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameterx(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);

    glMatrixMode(GL_TEXTURE);
    glLoadIdentity();
    glMatrixMode(GL_MODELVIEW);

    glDisable(GL_TEXTURE_EXTERNAL_OES);
    glEnable(GL_TEXTURE_2D);
}

// ---------------------------------------------------------------------------
}; // namespace android
// ---------------------------------------------------------------------------

#if defined(__gl2_h_)
#error "don't include gl2/gl2.h in this file"
#endif
