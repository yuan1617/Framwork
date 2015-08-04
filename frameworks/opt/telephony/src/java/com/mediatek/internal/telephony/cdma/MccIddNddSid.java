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
 * This class represents mcc, cc, min sid, max sid, idd, ndd states.
 * @hide
 */
public class MccIddNddSid {
    public int mMcc;
    public String mCc;
    public int mSidMin;
    public int mSidMax;
    public String mIdd;
    public String mNdd;

    /**
     * Default constructor.
     */
    public MccIddNddSid() {
        mMcc = -1;
        mCc = null;
        mSidMin = -1;
        mSidMax = -1;
        mIdd = null;
        mNdd = null;
    }

    /**
     * Constructor.
     * @param mcc MCC
     * @param cc CC
     * @param sidmin min SID
     * @param sidmax max SID
     * @param idd IDD
     * @param ndd NDD
     */
    public MccIddNddSid(int mcc, String cc, int sidmin, int sidmax, String idd,
            String ndd) {
        mMcc = mcc;
        mCc = cc;
        mSidMin = sidmin;
        mSidMax = sidmax;
        mIdd = idd;
        mNdd = ndd;
    }

    /**
     * Copy constructor.
     * @param t the MccIddNddSid object.
     */
    public MccIddNddSid(MccIddNddSid t) {
        copyFrom(t);
    }

    /**
     * @hide
     */
    protected void copyFrom(MccIddNddSid t) {
        mMcc = t.mMcc;
        mCc = t.mCc;
        mSidMin = t.mSidMin;
        mSidMax = t.mSidMax;
        mIdd = t.mIdd;
        mNdd = t.mNdd;
    }

    public int getMcc() {
        return mMcc;
    }

    public String getCc() {
        return mCc;
    }

    public int getSidMin() {
        return mSidMin;
    }

    public int getSidMax() {
        return mSidMax;
    }

    public String getIdd() {
        return mIdd;
    }

    public String getNdd() {
        return mNdd;
    }

    @Override
    public String toString() {
        return ("Mcc =" + mMcc + ", Cc = " + mCc + ", SidMin = " + mSidMin
                + ", SidMax = " + mSidMax + ", Idd = " + mIdd + ", Ndd = " + mNdd);
    }
}
