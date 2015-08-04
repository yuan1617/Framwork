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
public class MccIddNddSid {
    private static final String LOG_TAG = "MccIddNddSid";
    private static final boolean DBG = false;

    public int Mcc;
    public String Cc;
    public int SidMin;
    public int SidMax;
    public String Idd;
    public String Ndd;

    public MccIddNddSid() {
        Mcc = -1;
        Cc = null;
        SidMin = -1;
        SidMax = -1;
        Idd = null;
        Ndd = null;
    }

    public MccIddNddSid (int mcc, String cc, int sidmin, int sidmax, String idd, String ndd) {
        Mcc = mcc;
        Cc = cc;
        SidMin = sidmin;
        SidMax = sidmax;
        Idd = idd;
        Ndd = ndd;
    }

    /**
     * Copy constructors
     *
     * @param s Source SignalStrength
     *
     * @hide
     */
    public MccIddNddSid (MccIddNddSid t) {
        copyFrom(t);
    }

    /**
     * @hide
     */
    protected void copyFrom(MccIddNddSid t) {
        Mcc = t.Mcc;
        Cc = t.Cc;
        SidMin = t.SidMin;
        SidMax = t.SidMax;
        Idd = t.Idd;
        Ndd = t.Ndd;
    }

    public int getMcc(){
        return Mcc;
    }

    public String getCc(){
        return Cc;
    }

    public int getSidMin(){
        return SidMin;
    }

    public int getSidMax(){
        return SidMax;
    }

    public String getIdd(){
        return Idd;
    }

    public String getNdd(){
        return Ndd;
    }

    public String toString() {
        return ("Mcc =" +Mcc 
              + ", Cc = " + Cc
              + ", SidMin = " + SidMin
              + ", SidMax = " + SidMax
              + ", Idd = " + Idd
              + ", Ndd = " + Ndd);
    }

   }
