/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.mediatek.internal.telephony.cdma;

/**
 * The class represents sid, mccmnc states.
 * @hide
 */
public class SidMccMnc {
    public int mSid;
    public int mMccMnc;

    /**
     * Default constructor.
     */
    public SidMccMnc() {
        mSid = -1;
        mMccMnc = -1;
    }

    /**
     * Constructor.
     * @param sid SID value
     * @param mccMnc MCCMNC value
     */
    public SidMccMnc(int sid, int mccMnc) {
        mSid = sid;
        mMccMnc = mccMnc;
    }

    /**
     * Copy constructor.
     * @param t SidMccMnc object.
     */
    public SidMccMnc(SidMccMnc t) {
        copyFrom(t);
    }

    /**
     * @hide
     */
    protected void copyFrom(SidMccMnc t) {
        mSid = t.mSid;
        mMccMnc = t.mMccMnc;
    }

    public int getSid() {
        return mSid;
    }

    public int getMccMnc() {
        return mMccMnc;
    }

    @Override
    public String toString() {
        return ("Sid =" + mSid + ", MccMnc = " + mMccMnc);
    }
}