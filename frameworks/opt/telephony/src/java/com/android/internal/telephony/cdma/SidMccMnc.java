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
public class SidMccMnc {
    private static final String LOG_TAG = "SidMccMnc";
    private static final boolean DBG = true;

    public int Sid;
    public int MccMnc;

    public SidMccMnc() {
        Sid = -1;
        MccMnc = -1;
    }
    
    public SidMccMnc (int mSid, int mMccMnc) {
        Sid = mSid;
        MccMnc = mMccMnc;
    }

    /**
     * Copy constructors
     *
     * @param s Source SignalStrength
     *
     * @hide
     */
    public SidMccMnc (SidMccMnc t) {
        copyFrom(t);
    }

    /**
     * @hide
     */
    protected void copyFrom(SidMccMnc t) {
        Sid = t.Sid;
        MccMnc = t.MccMnc;
    }

    public int getSid(){
        return Sid;
    }

    public int getMccMnc(){
        return MccMnc;
    }

    public String toString() {
        return ("Sid =" +Sid 
              + ", MccMnc = " + MccMnc
              );
    }

}

