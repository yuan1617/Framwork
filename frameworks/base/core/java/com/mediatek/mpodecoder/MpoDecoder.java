/* Copyright Statement:
 *
 * This software/firmware and related documentation ("MediaTek Software") are
 * protected under relevant copyright laws. The information contained herein
 * is confidential and proprietary to MediaTek Inc. and/or its licensors.
 * Without the prior written permission of MediaTek inc. and/or its licensors,
 * any reproduction, modification, use or disclosure of MediaTek Software,
 * and information contained herein, in whole or in part, shall be strictly prohibited.
 */
/* MediaTek Inc. (C) 2010. All rights reserved.
 *
 * BY OPENING THIS FILE, RECEIVER HEREBY UNEQUIVOCALLY ACKNOWLEDGES AND AGREES
 * THAT THE SOFTWARE/FIRMWARE AND ITS DOCUMENTATIONS ("MEDIATEK SOFTWARE")
 * RECEIVED FROM MEDIATEK AND/OR ITS REPRESENTATIVES ARE PROVIDED TO RECEIVER ON
 * AN "AS-IS" BASIS ONLY. MEDIATEK EXPRESSLY DISCLAIMS ANY AND ALL WARRANTIES,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE OR NONINFRINGEMENT.
 * NEITHER DOES MEDIATEK PROVIDE ANY WARRANTY WHATSOEVER WITH RESPECT TO THE
 * SOFTWARE OF ANY THIRD PARTY WHICH MAY BE USED BY, INCORPORATED IN, OR
 * SUPPLIED WITH THE MEDIATEK SOFTWARE, AND RECEIVER AGREES TO LOOK ONLY TO SUCH
 * THIRD PARTY FOR ANY WARRANTY CLAIM RELATING THERETO. RECEIVER EXPRESSLY ACKNOWLEDGES
 * THAT IT IS RECEIVER'S SOLE RESPONSIBILITY TO OBTAIN FROM ANY THIRD PARTY ALL PROPER LICENSES
 * CONTAINED IN MEDIATEK SOFTWARE. MEDIATEK SHALL ALSO NOT BE RESPONSIBLE FOR ANY MEDIATEK
 * SOFTWARE RELEASES MADE TO RECEIVER'S SPECIFICATION OR TO CONFORM TO A PARTICULAR
 * STANDARD OR OPEN FORUM. RECEIVER'S SOLE AND EXCLUSIVE REMEDY AND MEDIATEK'S ENTIRE AND
 * CUMULATIVE LIABILITY WITH RESPECT TO THE MEDIATEK SOFTWARE RELEASED HEREUNDER WILL BE,
 * AT MEDIATEK'S OPTION, TO REVISE OR REPLACE THE MEDIATEK SOFTWARE AT ISSUE,
 * OR REFUND ANY SOFTWARE LICENSE FEES OR SERVICE CHARGE PAID BY RECEIVER TO
 * MEDIATEK FOR SUCH MEDIATEK SOFTWARE AT ISSUE.
 *
 * The following software/firmware and/or related documentation ("MediaTek Software")
 * have been modified by MediaTek Inc. All revisions are subject to any receiver's
 * applicable license agreements with MediaTek Inc.
 */

/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.mediatek.mpodecoder;

import java.io.IOException;
import java.io.InputStream;
import android.util.Log;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory.Options;
import android.net.Uri;
import android.content.ContentResolver;

public class MpoDecoder {
    private static final String TAG = "MpoDecoder";
    public static final int TYPE_NONE = 0x000000;
    /**
     * means MPO
     * @hide
     * @internal
     */
    public static final int TYPE_MAV = 0x020003;
    public static final int TYPE_STEREO = 0x020002;
    public static final int TYPE_3DPAN = 0x020001;
    private final int mNativeMpoDecoder;

    private MpoDecoder(int nativeMpoDecoder) {
        if (nativeMpoDecoder == 0) {
            throw new RuntimeException("native mpo decoder creation failed");
        }
        mNativeMpoDecoder = nativeMpoDecoder;
    }

    static {
        System.loadLibrary("mpojni");
    }

    /**
     * get MAV width
     * @hide
     * @internal
     * @return mpo width
     */
    public native int getWidth();

    /**
     * get MAV height
     * @hide
     * @internal
     * @return mpo height
     */
    public native int getHeight();

    /**
     * get MAV total frame count
     * @hide
     * @internal
     * @return mpo total frame count
     */
    public native int getFrameCount();

    /**
     * get MPO type
     * @hide
     * @internal
     * @return mpo type
     */
    public native int getMpoType();

    /**
     * get MPO subType
     * @hide
     * @internal
     * @return mpo subtype
     */
    public native int getMpoSubType();

    /**
     * get bitmap of frameindex
     * @hide
     * @internal
     * @return bitmap of frameindex
     */
    public native Bitmap getFrameBitmap(int frameIndex, Options options);

    /**
     * This method release all the Info stored for MPO. After this method is
     * call, Movie Object should no longer be used. eg. mMpoDecoder.closeMpo();
     * mMpoDecoder = null;
     * @hide
     * @internal
     */
    public native void close();

    /**
     * decode mpo file with file path
     * @hide
     * @internal
     * @return created MpoDecoder
     */
    public static native MpoDecoder decodeFile(String pathName);

    /**
     * decode mpo file with uri
     * @hide
     * @internal
     * @return created MpoDecoder
     */
    public static MpoDecoder decodeUri(ContentResolver cr, Uri mpoUri) {
        Log.i(TAG, "<decodeUri> (mpoUri=" + mpoUri + ") ");
        if (null == mpoUri)
            return null;
        byte[] buffer = getByteBuffer(cr, mpoUri);
        Log.v(TAG, "<decodeUri> buffer=" + buffer);
        if (null == buffer) {
            Log.e(TAG, "<decodeUri> got null buffer from " + mpoUri);
            return null;
        }
        return decodeByteArray(buffer, 0, buffer.length);
    }

    private static byte[] getByteBuffer(ContentResolver cr, Uri uri) {
        Log.i(TAG, "<getByteBuffer> Image Uri:" + uri);
        InputStream mpoStream = null;
        try {
            mpoStream = cr.openInputStream(uri);
            Log.v(TAG, "<getByteBuffer> we want to get stream size..");
            final int BufSize = 4096 * 16;
            byte[] buffer = new byte[BufSize];
            int streamSize = 0;
            int readSize = 0;
            do {
                readSize = mpoStream.read(buffer);
                if (0 < readSize) {
                    streamSize += readSize;
                }
            } while (0 < readSize);
            Log.i(TAG, "<getByteBuffer> streamSize=" + streamSize);
            if (streamSize <= 0) {
                Log.e(TAG, "<getByteBuffer> got invalid stream length of MPO");
                return null;
            }
            // close the open stream
            mpoStream.close();
            Log.v(TAG, "<getByteBuffer> reopen stream");
            // reopen the stream
            mpoStream = cr.openInputStream(uri);
            Log.v(TAG, "<getByteBuffer> allocate bysste");
            // allocate buffer for mpo stream
            buffer = new byte[streamSize + 1];
            Log.v(TAG, "<getByteBuffer> read stream..");
            // read the whole stream to buffer
            readSize = mpoStream.read(buffer);
            // now data is in buffer, stream is no longer used
            mpoStream.close();
            Log.v(TAG, "<getByteBuffer> read whole stream length:" + readSize);
            if (readSize != streamSize) {
                Log.w(TAG, "<getByteBuffer> read length could be wrong?");
            }
            if (readSize < 0) {
                Log.e(TAG, "<getByteBuffer> read whole stream failed");
            }
            return buffer;
        } catch (IOException ex) {
            Log.e(TAG, "<getByteBuffer> Failed to open mpo stream " + uri);
            return null;
        }
    }

    /**
     * decode mpo file with byteArray
     * @hide
     * @internal
     * @return created MpoDecoder
     */
    public static native MpoDecoder decodeByteArray(byte[] data, int offset,
            int length);
}
