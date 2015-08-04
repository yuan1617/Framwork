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

package com.mediatek.internal.telephony.cdma;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Plus Code HPCD table.
 * @hide
 */
public class PlusCodeHpcdTable {

    private static PlusCodeHpcdTable sInstance;

    static final Object sInstSync = new Object();

    static final String LOG_TAG = "CDMA-PlusCodeHpcdTable";
    private static final boolean DBG = true;

    static final int PARAM_FOR_OFFSET = 2;

    private static final MccIddNddSid[] MccIddNddSidMap = TelephonyPlusCode.MCC_IDD_NDD_SID_MAP;

    private static final MccSidLtmOff[] MccSidLtmOffMap = TelephonyPlusCode.MCC_SID_LTM_OFF_MAP;

    /**
     * @return the single instance.
     */
    public static PlusCodeHpcdTable getInstance() {
        synchronized (sInstSync) {
            if (sInstance == null) {
                sInstance = new PlusCodeHpcdTable();
            }
        }
        return sInstance;
    }

    private PlusCodeHpcdTable() {
        // Do nothing.
    }

    /**
     * Get CC from MccIddNddSidMap by mcc value.
     * @param sMcc the MCC
     * @return CC
     */
    public static MccIddNddSid getCcFromTableByMcc(String sMcc) {
        Log.d(LOG_TAG, " getCcFromTableByMcc mcc = " + sMcc);
        if (sMcc == null || sMcc.length() == 0) {
            Log.d(LOG_TAG, "[getCcFromTableByMcc] please check the param ");
            return null;
        }

        int mcc;
        try {
            mcc = Integer.parseInt(sMcc);
        } catch (NumberFormatException e) {
            Log.e(LOG_TAG, Log.getStackTraceString(e));
            return null;
        }
        int size = MccIddNddSidMap.length;

        MccIddNddSid mccIddNddSid = null;

        /*int high = size - 1, low = 0, guess;
        while (high - low > 1) {
            guess = (high + low) / 2;
            mccIddNddSid = (MccIddNddSid) MccIddNddSidMap[guess];

            int temMcc = mccIddNddSid.getMcc();
            if (temMcc < mcc) {
                low = guess;
            } else {
                high = guess;
            }
        }*/

        Log.d(LOG_TAG, " getCcFromTableByMcc size = " + size);
        int find = -1;
        for (int i = 0; i < size; i++) {
            mccIddNddSid = (MccIddNddSid) MccIddNddSidMap[i];
            int tempMcc = mccIddNddSid.getMcc();
            Log.d(LOG_TAG, " getCcFromTableByMcc tempMcc = " + tempMcc);
            if (tempMcc == mcc) {
                find = i;
                break;
            }
        }

        Log.d(LOG_TAG, " getCcFromTableByMcc find = " + find);
        if (find > -1 && find < size) {
            mccIddNddSid = (MccIddNddSid) MccIddNddSidMap[find];
            Log.d(LOG_TAG, "Now find Mcc = " + mccIddNddSid.mMcc + ", Mcc = "
                    + mccIddNddSid.mCc + ", SidMin = " + mccIddNddSid.mSidMin
                    + ", SidMax = " + mccIddNddSid.mSidMax + ", Idd = "
                    + mccIddNddSid.mIdd + ", Ndd = " + mccIddNddSid.mNdd);
            return mccIddNddSid;
        } else {
            Log.d(LOG_TAG, "can't find one that match the Mcc");
            return null;
        }
    }

    /**
     * Get MCC from conflicts table by sid. If Conlicts, there was more than one value,
     * so add into list. If not, there was only one value in the list.
     * @param sSid the SID
     * @return MCC
     */
    public static ArrayList<String> getMccFromConflictTableBySid(String sSid) {
        Log.d(LOG_TAG, " [getMccFromConflictTableBySid] sid = " + sSid);
        if (sSid == null || sSid.length() == 0 || sSid.length() > 5) {
            Log.d(LOG_TAG, "[getMccFromConflictTableBySid] please check the param ");
            return null;
        }

        int sid;
        try {
            sid = Integer.parseInt(sSid);
        } catch (NumberFormatException e) {
            Log.e(LOG_TAG, Log.getStackTraceString(e));
            return null;
        }

        if (sid < 0) {
            return null;
        }

        ArrayList<String> mccArrays = new ArrayList<String>();
        MccSidLtmOff mccSidLtmOff = null;
        int mccSidMapSize = MccSidLtmOffMap.length;
        Log.d(LOG_TAG, " [getMccFromConflictTableBySid] mccSidMapSize = " + mccSidMapSize);
        for (int i = 0; i < mccSidMapSize; i++) {
            mccSidLtmOff = (MccSidLtmOff) MccSidLtmOffMap[i];
            if (mccSidLtmOff != null && mccSidLtmOff.mSid == sid) {
                mccArrays.add(Integer.toString(mccSidLtmOff.mMcc));
                Log.d(LOG_TAG, "mccSidLtmOff  Mcc = " + mccSidLtmOff.mMcc
                        + ", Sid = " + mccSidLtmOff.mSid + ", LtmOffMin = "
                        + mccSidLtmOff.mLtmOffMin + ", LtmOffMax = "
                        + mccSidLtmOff.mLtmOffMax);
            }
        }

        return mccArrays;
    }

    /**
     * Get CC from MccIddNddSidMap by sid.
     * @param sSid the SID.
     * @return the CC.
     */
    public static MccIddNddSid getCcFromMINSTableBySid(String sSid) {
        Log.d(LOG_TAG, " [getCcFromMINSTableBySid] sid = " + sSid);
        if (sSid == null || sSid.length() == 0 || sSid.length() > 5) {
            Log.d(LOG_TAG, "[getCcFromMINSTableBySid] please check the param ");
            return null;
        }

        int sid;
        try {
            sid = Integer.parseInt(sSid);
        } catch (NumberFormatException e) {
            Log.e(LOG_TAG, Log.getStackTraceString(e));
            return null;
        }
        if (sid < 0) {
            return null;
        }

        MccIddNddSid mccIddNddSid = null;
        MccIddNddSid findMccIddNddSid = null;

        int size = MccIddNddSidMap.length;
        for (int i = 0; i < size; i++) {
            mccIddNddSid = (MccIddNddSid) MccIddNddSidMap[i];
            if (sid <= mccIddNddSid.mSidMax && sid >= mccIddNddSid.mSidMin) {
                findMccIddNddSid = mccIddNddSid;
                break;
            }
        }

        if (DBG) {
            Log.d(LOG_TAG, " getCcFromMINSTableBySidAndLtm findMccIddNddSid = " + findMccIddNddSid);
        }
        return findMccIddNddSid;

    }

    /**
     * Get CC from MccIddNddSidMap by ltm_off.
     * @param mccArray the MCC array.
     * @param ltmOff the LTM off.
     * @return the CC
     */
    public String getCcFromMINSTableByLTM(List<String> mccArray, String ltmOff) {
        Log.d(LOG_TAG, " getCcFromMINSTableByLTM sLtm_off = " + ltmOff);
        if (ltmOff == null || ltmOff.length() == 0 || mccArray == null || mccArray.size() == 0) {
            Log.d(LOG_TAG, "[getCcFromMINSTableByLTM] please check the param ");
            return null;
        }

        String findMcc = null;

        int ltmoff;
        try {
            ltmoff = Integer.parseInt(ltmOff);
        } catch (NumberFormatException e) {
            Log.e(LOG_TAG, Log.getStackTraceString(e));
            return null;
        }

        Log.d(LOG_TAG, "[getCcFromMINSTableByLTM]  ltm_off =  " + ltmoff);

        int findOutMccSize = mccArray.size();
        if (findOutMccSize > 1 && MccSidLtmOffMap != null) {
            int mccSidMapSize = MccSidLtmOffMap.length;
            if (DBG) {
                Log.d(LOG_TAG, " Conflict FindOutMccSize = " + findOutMccSize);
            }

            MccSidLtmOff mccSidLtmOff = null;
            int mcc = -1;
            for (int i = 0; i < findOutMccSize; i++) {
                try {
                    mcc = Integer.parseInt(mccArray.get(i));
                } catch (NumberFormatException e) {
                    Log.e(LOG_TAG, Log.getStackTraceString(e));
                    return null;
                }

                Log.d(LOG_TAG, " Conflict mcc = " + mcc + ",index = " + i);
                for (int j = 0; j < mccSidMapSize; j++) {
                    mccSidLtmOff = (MccSidLtmOff) MccSidLtmOffMap[j];
                    if (mccSidLtmOff.mMcc == mcc) {

                        int max = (mccSidLtmOff.mLtmOffMax) * PARAM_FOR_OFFSET;
                        int min = (mccSidLtmOff.mLtmOffMin) * PARAM_FOR_OFFSET;

                        Log.d(LOG_TAG, "mccSidLtmOff LtmOffMin = "
                                + mccSidLtmOff.mLtmOffMin + ", LtmOffMax = "
                                + mccSidLtmOff.mLtmOffMax);
                        if (ltmoff <= max && ltmoff >= min) {
                            findMcc = mccArray.get(i);
                            break;
                        }
                    }
                }
            }
        } else {
            findMcc = mccArray.get(0);
        }

        Log.d(LOG_TAG, "find one that match the ltm_off mcc = " + findMcc);
        return findMcc;
    }

    /**
     * Get mcc from conflict table by sid ltm off.
     * @param sSid the SID
     * @param sLtmOff the LTM off
     * @return MCC
     */
    public static String getMccFromConflictTableBySidLtmOff(String sSid, String sLtmOff) {
        Log.d(LOG_TAG, " [getMccFromConflictTableBySidLtmOff] sSid = " + sSid
                + ", sLtm_off = " + sLtmOff);
        if (sSid == null || sSid.length() == 0 || sSid.length() > 5
                || sLtmOff == null || sLtmOff.length() == 0) {
            Log.d(LOG_TAG, "[getMccFromConflictTableBySidLtmOff] please check the param ");
            return null;
        }

        int sid;
        try {
            sid = Integer.parseInt(sSid);
        } catch (NumberFormatException e) {
            Log.e(LOG_TAG, Log.getStackTraceString(e));
            return null;
        }
        if (sid < 0) {
            return null;
        }

        int ltmoff;
        try {
            ltmoff = Integer.parseInt(sLtmOff);
        } catch (NumberFormatException e) {
            Log.e(LOG_TAG, Log.getStackTraceString(e));
            return null;
        }

        Log.d(LOG_TAG, " [getMccFromConflictTableBySidLtmOff] sid = " + sid);

        int mccSidMapSize = MccSidLtmOffMap.length;
        Log.d(LOG_TAG, " [getMccFromConflictTableBySidLtmOff] mccSidMapSize = " + mccSidMapSize);

        MccSidLtmOff mccSidLtmOff = null;
        for (int i = 0; i < mccSidMapSize; i++) {
            mccSidLtmOff = MccSidLtmOffMap[i];

            int max = (mccSidLtmOff.mLtmOffMax) * PARAM_FOR_OFFSET;
            int min = (mccSidLtmOff.mLtmOffMin) * PARAM_FOR_OFFSET;

            Log.d(LOG_TAG,
                    "[getMccFromConflictTableBySidLtmOff] mccSidLtmOff.Sid = "
                            + mccSidLtmOff.mSid + ", sid = " + sid
                            + ", ltm_off = " + ltmoff + ", max = " + max
                            + ", min = " + min);

            if (mccSidLtmOff != null && mccSidLtmOff.mSid == sid
                    && (ltmoff <= max && ltmoff >= min)) {
                String mccStr = Integer.toString(mccSidLtmOff.mMcc);
                Log.d(LOG_TAG, "[getMccFromConflictTableBySidLtmOff] Mcc = " + mccStr);
                return mccStr;
            }
        }

        return null;
    }

    /**
     * Get mcc from MINS table by sid.
     * @param sSid the SID
     * @return MCC
     */
    public static String getMccFromMINSTableBySid(String sSid) {
        Log.d(LOG_TAG, " [getMccFromMINSTableBySid] sid = " + sSid);
        if (sSid == null || sSid.length() == 0 || sSid.length() > 5) {
            Log.d(LOG_TAG, "[getMccFromMINSTableBySid] please check the param ");
            return null;
        }

        int sid;
        try {
            sid = Integer.parseInt(sSid);
        } catch (NumberFormatException e) {
            Log.e(LOG_TAG, Log.getStackTraceString(e));
            return null;
        }
        if (sid < 0) {
            return null;
        }

        MccIddNddSid mccIddNddSid = null;

        int size = MccIddNddSidMap.length;
        Log.d(LOG_TAG, " [getMccFromMINSTableBySid] size = " + size);

        for (int i = 0; i < size; i++) {
            mccIddNddSid = MccIddNddSidMap[i];

            Log.d(LOG_TAG, " [getMccFromMINSTableBySid] sid = " + sid
                    + ", mccIddNddSid.SidMin = " + mccIddNddSid.mSidMin
                    + ", mccIddNddSid.SidMax = " + mccIddNddSid.mSidMax);

            if (sid >= mccIddNddSid.mSidMin && sid <= mccIddNddSid.mSidMax) {
                String mccStr = Integer.toString(mccIddNddSid.mMcc);
                Log.d(LOG_TAG, "[queryMccFromConflictTableBySid] Mcc = " + mccStr);
                return mccStr;
            }

        }

        return null;
    }

    /**
     * Get mccmnc from SidMccMncList by sid.
     * @param sSid the SID
     * @return MCCMNC
     */
    public static String getMccMncFromSidMccMncListBySid(String sSid) {
        Log.d(LOG_TAG, " [getMccMncFromSidMccMncListBySid] sid = " + sSid);
        if (sSid == null || sSid.length() == 0 || sSid.length() > 5) {
            Log.d(LOG_TAG, "[getMccMncFromSidMccMncListBySid] please check the param ");
            return null;
        }

        int sid;
        try {
            sid = Integer.parseInt(sSid);
        } catch (NumberFormatException e) {
            Log.e(LOG_TAG, Log.getStackTraceString(e));
            return null;
        }
        if (sid < 0) {
            return null;
        }

        List<SidMccMnc> mSidMccMncList = TelephonyPlusCode.getSidMccMncList();
        SidMccMnc mSidMccMnc = null;
        int left = 0;
        int right = mSidMccMncList.size() - 1;
        int mid;
        int mccMnc = 0;

        while (left <= right) {
            mid = (left + right) / 2;
            mSidMccMnc = mSidMccMncList.get(mid);
            if (sid < mSidMccMnc.mSid) {
                right = mid - 1;
            } else if (sid > mSidMccMnc.mSid) {
                left = mid + 1;
            } else {
                mccMnc = mSidMccMnc.mMccMnc;
                break;
            }
        }

        if (mccMnc != 0) {
            String mccMncStr = Integer.toString(mccMnc);
            Log.d(LOG_TAG, "[getMccMncFromSidMccMncListBySid] MccMncStr = " + mccMncStr);
            return mccMncStr;
        } else {
            return null;
        }
    }
}
