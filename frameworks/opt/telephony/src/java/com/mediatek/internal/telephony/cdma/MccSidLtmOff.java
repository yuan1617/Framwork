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
 * This class represents mcc, sid, min ltm off, max ltm off states.
 * @hide
 */
public class MccSidLtmOff {
    public int mMcc;
    public int mSid;
    public int mLtmOffMin;
    public int mLtmOffMax;

    public static final int LTM_OFF_INVALID = 100;

    /**
     * Default constructor.
     */
    public MccSidLtmOff() {
        mMcc = -1;
        mSid = -1;
        mLtmOffMin = LTM_OFF_INVALID;
        mLtmOffMax = LTM_OFF_INVALID;
    }

    /**
     * Constructor.
     * @param mcc MCC
     * @param sid SID
     * @param ltmOffMin min LTM off
     * @param ltmOffMax max LTM off
     */
    public MccSidLtmOff(int mcc, int sid, int ltmOffMin, int ltmOffMax) {
        mMcc = mcc;
        mSid = sid;
        mLtmOffMin = ltmOffMin;
        mLtmOffMax = ltmOffMax;
    }

    /**
     * Copy constructor.
     * @param t the MccSidLtmOff object
     */
    public MccSidLtmOff(MccSidLtmOff t) {
        copyFrom(t);
    }

    /**
     * @hide
     */
    protected void copyFrom(MccSidLtmOff t) {
        mMcc = t.mMcc;
        mSid = t.mSid;
        mLtmOffMin = t.mLtmOffMin;
        mLtmOffMax = t.mLtmOffMax;
    }

    public int getMcc() {
        return mMcc;
    }

    public int getSid() {
        return mSid;
    }

    public int getLtmOffMin() {
        return mLtmOffMin;
    }

    public int getLtmOffMax() {
        return mLtmOffMax;
    }
}
