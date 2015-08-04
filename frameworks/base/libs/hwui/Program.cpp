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

#define LOG_TAG "OpenGLRenderer"

#include <utils/Trace.h>

#include "Program.h"
#include "Vertex.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Base program
///////////////////////////////////////////////////////////////////////////////

Program::Program(const ProgramDescription& description, const char* vertex, const char* fragment) {
    ATRACE_NAME_L2("Program by building");
    description.log("Program by building");
    mInitialized = false;
    mHasColorUniform = false;
    mHasSampler = false;
    mUse = false;

    // No need to cache compiled shaders, rely instead on Android's
    // persistent shaders cache
    mVertexShader = buildShader(vertex, GL_VERTEX_SHADER);
    if (mVertexShader) {
        mFragmentShader = buildShader(fragment, GL_FRAGMENT_SHADER);
        if (mFragmentShader) {
            mProgramId = glCreateProgram();

            glAttachShader(mProgramId, mVertexShader);
            glAttachShader(mProgramId, mFragmentShader);

            position = bindAttrib("position", kBindingPosition);
            if (description.hasTexture || description.hasExternalTexture) {
                texCoords = bindAttrib("texCoords", kBindingTexCoords);
            } else {
                texCoords = -1;
            }

            ATRACE_BEGIN("linkProgram");
            glLinkProgram(mProgramId);
            ATRACE_END();

            GLint status;
            glGetProgramiv(mProgramId, GL_LINK_STATUS, &status);
            if (status != GL_TRUE) {
                GLint infoLen = 0;
                glGetProgramiv(mProgramId, GL_INFO_LOG_LENGTH, &infoLen);
                if (infoLen > 1) {
                    GLchar log[infoLen];
                    glGetProgramInfoLog(mProgramId, infoLen, 0, &log[0]);
                    ALOGE("%s", log);
                }
                LOG_ALWAYS_FATAL("Error while linking shaders");
            } else {
                mInitialized = true;
            }
        } else {
            glDeleteShader(mVertexShader);
        }
    }

    if (mInitialized) {
        transform = addUniform("transform");
        projection = addUniform("projection");
    }
}


Program::Program(const ProgramDescription& description, void* binary, GLint length, GLenum format) {
    ATRACE_NAME_L2("Program by binary");
    description.log("Program by binary");
    uint64_t start = systemTime(SYSTEM_TIME_MONOTONIC);
    programid key = description.key();

    mInitialized = false;
    mHasColorUniform = false;
    mHasSampler = false;
    mUse = false;
    mVertexShader = 0;
    mFragmentShader = 0;

    //
    //  Load the binary into the program object -- no need to link!
    //
    mProgramId = glCreateProgram();
    ATRACE_BEGIN_L1("glProgramBinaryOES");
    glProgramBinaryOES(mProgramId, format, binary, length);
    ATRACE_END_L1();

    GLint success;
    glGetProgramiv(mProgramId, GL_LINK_STATUS, &success);
    if (success) {

        uint64_t end = systemTime(SYSTEM_TIME_MONOTONIC);
        PROGRAM_LOGD("createProgram 0x%.8x%.8x, binary %p, length %d, format %d within %dns", uint32_t(key >> 32),
            uint32_t(key & 0xffffffff), binary, length, format, (int) ((end - start) / 1000));

        mInitialized = true;
        position = addAttrib("position");
        if (description.hasTexture || description.hasExternalTexture) {
            texCoords = addAttrib("texCoords");
        } else {
            texCoords = -1;
        }

        transform = addUniform("transform");
        projection = addUniform("projection");
    } else {
        PROGRAM_LOGD("createProgram 0x%.8x%.8x by binary but failed", uint32_t(key >> 32), uint32_t(key & 0xffffffff));

        glDeleteProgram(mProgramId);
        mProgramId = 0;
    }
}

Program::~Program() {
    if (mInitialized) {
        if (mVertexShader != 0 && mFragmentShader != 0) {
            // This would ideally happen after linking the program
            // but Tegra drivers, especially when perfhud is enabled,
            // sometimes crash if we do so
            glDetachShader(mProgramId, mVertexShader);
            glDetachShader(mProgramId, mFragmentShader);

            glDeleteShader(mVertexShader);
            glDeleteShader(mFragmentShader);
        }

        glDeleteProgram(mProgramId);
    }
}

int Program::addAttrib(const char* name) {
    int slot = glGetAttribLocation(mProgramId, name);
    mAttributes.add(name, slot);
    return slot;
}

int Program::bindAttrib(const char* name, ShaderBindings bindingSlot) {
    glBindAttribLocation(mProgramId, bindingSlot, name);
    mAttributes.add(name, bindingSlot);
    return bindingSlot;
}

int Program::getAttrib(const char* name) {
    ssize_t index = mAttributes.indexOfKey(name);
    if (index >= 0) {
        return mAttributes.valueAt(index);
    }
    return addAttrib(name);
}

int Program::addUniform(const char* name) {
    int slot = glGetUniformLocation(mProgramId, name);
    mUniforms.add(name, slot);
    return slot;
}

int Program::getUniform(const char* name) {
    ssize_t index = mUniforms.indexOfKey(name);
    if (index >= 0) {
        return mUniforms.valueAt(index);
    }
    return addUniform(name);
}

GLuint Program::buildShader(const char* source, GLenum type) {
    ATRACE_CALL();

    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, 0);
    ATRACE_BEGIN_L2("glCompileShader");
    glCompileShader(shader);
    ATRACE_END_L2();

    GLint status;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &status);
    if (status != GL_TRUE) {
        // Some drivers return wrong values for GL_INFO_LOG_LENGTH
        // use a fixed size instead
        GLchar log[512];
        glGetShaderInfoLog(shader, sizeof(log), 0, &log[0]);
        LOG_ALWAYS_FATAL("Error while compiling shader: %s", log);
        return 0;
    }

    return shader;
}

void Program::set(const mat4& projectionMatrix, const mat4& modelViewMatrix,
        const mat4& transformMatrix, bool offset) {
    if (projectionMatrix != mProjection || offset != mOffset) {
        if (CC_LIKELY(!offset)) {
            glUniformMatrix4fv(projection, 1, GL_FALSE, &projectionMatrix.data[0]);
        } else {
            mat4 p(projectionMatrix);
            // offset screenspace xy by an amount that compensates for typical precision
            // issues in GPU hardware that tends to paint hor/vert lines in pixels shifted
            // up and to the left.
            // This offset value is based on an assumption that some hardware may use as
            // little as 12.4 precision, so we offset by slightly more than 1/16.
            p.translate(Vertex::GeometryFudgeFactor(), Vertex::GeometryFudgeFactor());
            glUniformMatrix4fv(projection, 1, GL_FALSE, &p.data[0]);
        }
        mProjection = projectionMatrix;
        mOffset = offset;
    }

    mat4 t(transformMatrix);
    t.multiply(modelViewMatrix);
    glUniformMatrix4fv(transform, 1, GL_FALSE, &t.data[0]);
}

void Program::setColor(const float r, const float g, const float b, const float a) {
    if (!mHasColorUniform) {
        mColorUniform = getUniform("color");
        mHasColorUniform = true;
    }
    glUniform4f(mColorUniform, r, g, b, a);
}

void Program::use() {
    glUseProgram(mProgramId);
    if (texCoords >= 0 && !mHasSampler) {
        glUniform1i(getUniform("baseSampler"), 0);
        mHasSampler = true;
    }
    mUse = true;
}

void Program::remove() {
    mUse = false;
}

}; // namespace uirenderer
}; // namespace android
