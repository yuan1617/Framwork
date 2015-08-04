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
 * MediaTek Inc. (C) 2013. All rights reserved.
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

#include <utils/RefBase.h>

#include <GLES/gl.h>

#include <gui/Surface.h>
#include "RenderEngine/RenderEngine.h"

#include "SurfaceFlinger.h"
#include "DisplayDevice.h"

// ----------------------------------------------------------------------------
using namespace android;
// ----------------------------------------------------------------------------


void DisplayDevice::drawDebugLine() const {
    // line color:
    //   red for primary
    //   green for external
    //   blue for virtual
    uint32_t color;
    switch (mType) {
        case DisplayDevice::DISPLAY_PRIMARY:
            color = 0xFF0000FF;
            break;
        case DisplayDevice::DISPLAY_EXTERNAL:
            color = 0xFF00FF00;
            break;
        case DisplayDevice::DISPLAY_VIRTUAL:
            color = 0xFFFF0000;
            break;
        default:
            color = 0xFFFFFFFF;
    }
    mFlinger->getRenderEngine().drawDebugLine(this, color);
}

void DisplayDevice::correctSizeByHwOrientation(uint32_t &w, uint32_t &h) const {
    if (mHwOrientation & DisplayState::eOrientationSwapMask) {
        uint32_t tmp = w;
        w = h;
        h = tmp;
    }
}

void DisplayDevice::correctRotationByHwOrientation(Transform::orientation_flags &rotation) const {
    if (mHwOrientation != DisplayState::eOrientationDefault) {
        // convert hw orientation into flag presentation
        // here inverse transform needed
        uint8_t hw_rot_90  = 0x00;
        uint8_t hw_flip_hv = 0x00;
        switch (mHwOrientation) {
            case DisplayState::eOrientation90:
                hw_rot_90 = Transform::ROT_90;
                hw_flip_hv = Transform::ROT_180;
                break;
            case DisplayState::eOrientation180:
                hw_flip_hv = Transform::ROT_180;
                break;
            case DisplayState::eOrientation270:
                hw_rot_90  = Transform::ROT_90;
                break;
        }

        // transform flags operation
        // 1) flip H V if both have ROT_90 flag
        // 2) XOR these flags
        uint8_t rotation_rot_90  = rotation & Transform::ROT_90;
        uint8_t rotation_flip_hv = rotation & Transform::ROT_180;
        if (rotation_rot_90 & hw_rot_90) {
            rotation_flip_hv = (~rotation_flip_hv) & Transform::ROT_180;
        }
        rotation = static_cast<Transform::orientation_flags>
                   ((rotation_rot_90 ^ hw_rot_90) | (rotation_flip_hv ^ hw_flip_hv));
    }
}

void DisplayDevice::correctCropByHwOrientation(Rect& crop) const {
    if (mHwOrientation != DisplayState::eOrientationDefault) {
        Transform tr;
        uint32_t flags = 0x00;
        switch (mHwOrientation) {
            case DisplayState::eOrientation90:
                flags = Transform::ROT_90;
                break;
            case DisplayState::eOrientation180:
                flags = Transform::ROT_180;
                break;
            case DisplayState::eOrientation270:
                flags = Transform::ROT_270;
                break;
        }
        tr.set(flags, mDisplayWidth, mDisplayHeight);
        crop = tr.transform(crop);
    }
}

bool DisplayDevice::mustRecompose() const {
    return mMustRecompose || SurfaceFlinger::sPropertiesState.mDebugSkipComp;
}