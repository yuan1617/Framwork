/*
 * Copyright (C) 2013 The Android Open Source Project
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

#define LOG_TAG "OpenGLRenderer"

#include "AssetAtlas.h"
#include "Caches.h"

#include <GLES2/gl2ext.h>

/// M: used for program binary
#include <cutils/ashmem.h>
#include <sys/mman.h>

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Lifecycle
///////////////////////////////////////////////////////////////////////////////

void AssetAtlas::init(sp<GraphicBuffer> buffer, int64_t* map, int count) {
    if (mImage) {
        return;
    }

    mImage = new Image(buffer);

    if (mImage->getTexture()) {
        Caches& caches = Caches::getInstance();

        mTexture = new Texture(caches);
        mTexture->id = mImage->getTexture();
        mTexture->width = buffer->getWidth();
        mTexture->height = buffer->getHeight();

        createEntries(caches, map, count);
    } else {
        ALOGW("Could not create atlas image");

        delete mImage;
        mImage = NULL;
        mTexture = NULL;
    }

    mGenerationId++;
}

void AssetAtlas::initProgramAtlas(int fd, int length, int64_t* programMap, int programCount) {
    if (mProgramBinaries) {
        return;
    }

    int64_t result = (int64_t)mmap(NULL, length, PROT_READ, MAP_SHARED, fd, 0);
    if (result == (int64_t)MAP_FAILED) {
        ALOGW("Failed to mmap program binaries. Error: %d.", errno);
        return;
    } else {
        mProgramBinaries = (void*) result;
    }

    if (mProgramBinaries && programMap) {
        /// M: program binary entries
        for (int i = 0; i < programCount; ) {
            programid key = static_cast<programid>(programMap[i++]);
            void* binary = reinterpret_cast<void*>(reinterpret_cast<int64_t>(mProgramBinaries) + programMap[i++]);
            GLint length = static_cast<GLint>(programMap[i++]);
            GLenum format = static_cast<GLenum>(programMap[i++]);
            ProgramEntry* entry = new ProgramEntry(key, binary, length, format);
            mProgramEntries.add(entry->programKey, entry);
        }

        mProgramLength = length;
        mProgramMap = programMap;
    } else {
        mProgramBinaries = NULL;
    }
}

void AssetAtlas::destroyProgramAtlas() {
    if (mProgramBinaries) {
        /// M: unmap address if using program binaries is enabled
        int64_t result = munmap(mProgramBinaries, mProgramLength);
        if (result < 0) ALOGW("Failed to munmap program binaries.");

        delete mProgramMap;
        mProgramBinaries = NULL;
        mProgramLength = 0;
        mProgramMap = NULL;

        /// M: Clear program entries
        for (size_t i = 0; i < mProgramEntries.size(); i++) {
            delete mProgramEntries.valueAt(i);
        }
        mProgramEntries.clear();
    }
}

void AssetAtlas::terminate() {
    if (mImage) {
        delete mImage;
        mImage = NULL;

        delete mTexture;
        mTexture = NULL;

        for (size_t i = 0; i < mEntries.size(); i++) {
            delete mEntries.valueAt(i);
        }
        mEntries.clear();
    }
}

///////////////////////////////////////////////////////////////////////////////
// Entries
///////////////////////////////////////////////////////////////////////////////

AssetAtlas::Entry* AssetAtlas::getEntry(const SkBitmap* bitmap) const {
    ssize_t index = mEntries.indexOfKey(bitmap);
    return index >= 0 ? mEntries.valueAt(index) : NULL;
}

Texture* AssetAtlas::getEntryTexture(const SkBitmap* bitmap) const {
    ssize_t index = mEntries.indexOfKey(bitmap);
    return index >= 0 ? mEntries.valueAt(index)->texture : NULL;
}

/// M: get the entry of each program binary
AssetAtlas::ProgramEntry* AssetAtlas::getProgramEntry(programid key) {
    ssize_t index = mProgramEntries.indexOfKey(key);
    return index >= 0 ? mProgramEntries.valueAt(index) : NULL;
}

/**
 * Delegates changes to wrapping and filtering to the base atlas texture
 * instead of applying the changes to the virtual textures.
 */
struct DelegateTexture: public Texture {
    DelegateTexture(Caches& caches, Texture* delegate): Texture(caches), mDelegate(delegate) { }

    virtual void setWrapST(GLenum wrapS, GLenum wrapT, bool bindTexture = false,
            bool force = false, GLenum renderTarget = GL_TEXTURE_2D) {
        mDelegate->setWrapST(wrapS, wrapT, bindTexture, force, renderTarget);
    }

    virtual void setFilterMinMag(GLenum min, GLenum mag, bool bindTexture = false,
            bool force = false, GLenum renderTarget = GL_TEXTURE_2D) {
        mDelegate->setFilterMinMag(min, mag, bindTexture, force, renderTarget);
    }

private:
    Texture* const mDelegate;
}; // struct DelegateTexture

/**
 * TODO: This method does not take the rotation flag into account
 */
void AssetAtlas::createEntries(Caches& caches, int64_t* map, int count) {
    const float width = float(mTexture->width);
    const float height = float(mTexture->height);

    for (int i = 0; i < count; ) {
        SkBitmap* bitmap = reinterpret_cast<SkBitmap*>(map[i++]);
        // NOTE: We're converting from 64 bit signed values to 32 bit
        // signed values. This is guaranteed to be safe because the "x"
        // and "y" coordinate values are guaranteed to be representable
        // with 32 bits. The array is 64 bits wide so that it can carry
        // pointers on 64 bit architectures.
        const int x = static_cast<int>(map[i++]);
        const int y = static_cast<int>(map[i++]);
        bool rotated = map[i++] > 0;

        // Bitmaps should never be null, we're just extra paranoid
        if (!bitmap) continue;

        const UvMapper mapper(
                x / width, (x + bitmap->width()) / width,
                y / height, (y + bitmap->height()) / height);

        Texture* texture = new DelegateTexture(caches, mTexture);
        texture->id = mTexture->id;
        texture->blend = !bitmap->isOpaque();
        texture->width = bitmap->width();
        texture->height = bitmap->height();

        Entry* entry = new Entry(bitmap, x, y, rotated, texture, mapper, *this);
        texture->uvMapper = &entry->uvMapper;

        mEntries.add(entry->bitmap, entry);
    }
}

}; // namespace uirenderer
}; // namespace android
