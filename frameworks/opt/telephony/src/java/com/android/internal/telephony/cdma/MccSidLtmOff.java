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

package com.android.internal.telephony.cdma;

import java.util.List;

import android.util.Log;

/**
 * {@hide}
 */
public class MccSidLtmOff {
    private static final String LOG_TAG = "MccSidLtmOff";
    private static final boolean DBG = true;

    public int Mcc;
    public int Sid;
    public int LtmOffMin;
    public int LtmOffMax;

    public static final int LTM_OFF_INVALID = 100; 

    public MccSidLtmOff() {
        Mcc = -1;
        Sid = -1;
        LtmOffMin = LTM_OFF_INVALID;
        LtmOffMax = LTM_OFF_INVALID;
    }
    
    public MccSidLtmOff (int mcc, int sid, int ltmOffMin, int ltmOffMax) {
        Mcc = mcc;
        Sid = sid;
        LtmOffMin = ltmOffMin;
        LtmOffMax = ltmOffMax;
    }

    /**
     * Copy constructors
     *
     * @param s Source SignalStrength
     *
     * @hide
     */
    public MccSidLtmOff (MccSidLtmOff t) {
        copyFrom(t);
    }

    /**
     * @hide
     */
    protected void copyFrom(MccSidLtmOff t) {
        Mcc = t.Mcc;
        Sid = t.Sid;
        LtmOffMin = t.LtmOffMin;
        LtmOffMax = t.LtmOffMax;
    }

    public int getMcc(){
        return Mcc;
    }

    public int getSid(){
        return Sid;
    }

    public int getLtmOffMin(){
        return LtmOffMin;
    }

    public int getLtmOffMax(){
        return LtmOffMax;
    }
}
