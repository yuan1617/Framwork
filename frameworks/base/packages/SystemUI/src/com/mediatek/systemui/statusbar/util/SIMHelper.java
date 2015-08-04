/*
* Copyright (C) 2011-2014 MediaTek Inc.
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

package com.mediatek.systemui.statusbar.util;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.provider.Settings;
import android.net.ConnectivityManager;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;
import android.telephony.SubInfoRecord;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.provider.Settings;

import com.android.internal.telephony.ITelephony;

import com.mediatek.xlog.Xlog;

import java.util.Comparator;
import java.util.Collections;
import java.util.List;

/**
 * M: [SystemUI] Support "dual SIM" and "Notification toolbar".
 */
public class SIMHelper {

    private static final String TAG = "SIMHelper";
    private static final boolean DEBUG = false;

    private static List<SubInfoRecord> sSimInfos;

    public static final int SLOT_INDEX_DEFAULT = 0;
    public static final int SLOT_INDEX_1 = 1;
    public static final int SLOT_INDEX_2 = 2;
    public static final int SLOT_INDEX_3 = 3;
    public static final int INVALID_SLOT_ID = -1000;

    public static Context mContext;

    private SIMHelper() {
    }

    /// M: [SystemUI] Support Smartbook Feature. @{
    public static boolean isSmartBookPluggedIn(Context mContext) {
        if (isMtkSmartBookSupport()) {
            DisplayManager mDisplayManager = (DisplayManager) mContext.getSystemService(Context.DISPLAY_SERVICE);
            return mDisplayManager.isSmartBookPluggedIn();
        } else {
            return false;
        }
    }
    private static boolean bMtkSmartbookSupport = SystemProperties.get("ro.mtk_smartbook_support").equals("1");
    public static final boolean isMtkSmartBookSupport() {
        return bMtkSmartbookSupport;
    }
    /// @}

    /**
     * Get the default SIM id of the assigned business.
     *
     * @param context
     * @param businessType
     * @return The default SIM id, or -1 if it is not defined.
     */
    public static long getDefaultSIM(Context context, String businessType) {
        return Settings.System.getLong(context.getContentResolver(), businessType, -1);
    }

    public static void setDefaultSIM(Context context, String businessType, long simId) {
        Settings.System.putLong(context.getContentResolver(), businessType, simId);
    }

    public static List<SubInfoRecord> getSIMInfoList(Context context) {
        if (sSimInfos == null || sSimInfos.size() == 0) {
            sSimInfos = getSortedSIMInfoList(context);
        }
        return sSimInfos;
    }
    private static List<SubInfoRecord> getSortedSIMInfoList(Context context) {
        if (sSimInfos == null || sSimInfos.size() == 0) {
            Xlog.d(TAG, "getSortedSIMInfoList, SubInfoRecord is null");
            return null;
        }

        List<SubInfoRecord> simInfoList = sSimInfos;
        Collections.sort(sSimInfos, new Comparator<SubInfoRecord>() {
            @Override
            public int compare(SubInfoRecord a, SubInfoRecord b) {
                if (a.slotId < b.slotId) {
                    return -1;
                } else if (a.slotId > b.slotId) {
                    return 1;
                } else {
                    return 0;
                }
            }
        });
        return simInfoList;
    }

    /// M: [SystemUI] Support "dual SIM" @{
    public static SubInfoRecord getSubInfoById(Context context, long subId) {
        if (sSimInfos == null || sSimInfos.size() == 0) {
            Xlog.d(TAG, "getSubInfoById, SubInfoRecord is null");
            return null;
        }

        for (SubInfoRecord info : sSimInfos) {
            if (info.subId == subId) {
                return info;
            }
        }
        return null;
    }

    public static SubInfoRecord getSubInfoBySlot(Context context, int soltId) {
        if (sSimInfos == null || sSimInfos.size() == 0) {
            Xlog.d(TAG, "getSubInfoBySlot, SubInfoRecord is null");
            return null;
        }

        for (SubInfoRecord info : sSimInfos) {
            if (info.slotId == soltId) {
                return info;
            }
        }
        return null;
    }

    public static void updateSIMInfos(Context context) {
        sSimInfos = SubscriptionManager.getActiveSubInfoList();
    }

    public static long getFirstSubInSlot(int slotId) {
        long[] subIds = SubscriptionManager.getSubId(slotId);
        if (subIds != null && subIds.length > 0) {
            return subIds[0];
        }
        Log.d(TAG, "Cannot get first sub in slot: " + slotId);
        return SubscriptionManager.INVALID_SUB_ID;
    }

    public static boolean hasService(ServiceState ss) {
        if (ss != null) {
            // Consider the device to be in service if either voice or data service is available.
            // Some SIM cards are marketed as data-only and do not support voice service, and on
            // these SIM cards, we want to show signal bars for data service as well as the "no
            // service" or "emergency calls only" text that indicates that voice is not available.
            switch (ss.getVoiceRegState()) {
                case ServiceState.STATE_POWER_OFF:
                    return false;
                case ServiceState.STATE_OUT_OF_SERVICE:
                case ServiceState.STATE_EMERGENCY_ONLY:
                    return ss.getDataRegState() == ServiceState.STATE_IN_SERVICE;
                default:
                    return true;
            }
        } else {
            return false;
        }
    }

    public static int getSlotCount() {
        //FIXME: the slot count may not always be equal to phone count
        return TelephonyManager.getDefault().getPhoneCount();
    }

    public static boolean isSimInsertedBySlot(Context context, int slotId) {
        if (sSimInfos != null) {
            if (slotId <= getSlotCount() - 1) {
                SubInfoRecord info = getSubInfoBySlot(context, slotId);
                if (info != null)
                    return true;
                else
                    return false;
            } else {
                return false; // default return false
            }
        } else {
            Xlog.d(TAG, "isSimInsertedBySlot, SubInfoRecord is null");
            return false;
        }
    }
    /// @}

    /// M: add HotKnot in quicksetting @{
    private static boolean bMtkHotKnotSupport =
            SystemProperties.get("ro.mtk_hotknot_support").equals("1");
    public static final boolean isMtkHotKnotSupport() {
        Xlog.d(TAG, "isMtkHotKnotSupport, bMtkHotKnotSupport = " + bMtkHotKnotSupport);
        return bMtkHotKnotSupport;
    }
    /// add HotKnot in quicksetting @}

    /// M: add AudioProfile in quicksetting @{
    private static boolean bMtkAudioProfilesSupport =
            SystemProperties.get("ro.mtk_audio_profiles").equals("1");

    public static final boolean isMtkAudioProfilesSupport() {
        return bMtkAudioProfilesSupport;
    }
    /// add AudioProfile in quicksetting @}

    /// M: add DataConnection in quicksetting @{
    private static boolean bMtkGemini3GSwitchSupport =
            SystemProperties.get("ro.mtk_gemini_3g_switch").equals("1");

    public static final boolean isMtkGemini3GSwitchSupport() {
        return bMtkGemini3GSwitchSupport;
    }

    public static void setContext(Context context) {
        mContext = context;
    }
    
    public static boolean isWifiOnlyDevice() {
        ConnectivityManager cm =
                (ConnectivityManager)mContext.getSystemService(mContext.CONNECTIVITY_SERVICE);
        return  !(cm.isNetworkSupported(ConnectivityManager.TYPE_MOBILE));
    }

    private static boolean bMtkGeminiSupport =
            SystemProperties.get("ro.mtk_gemini_support").equals("1");
    public static final boolean isMtkGeminiSupport() {
        return bMtkGeminiSupport;
    }
    /// M: add DataConnection in quicksetting @}

    /**
     * Get the radio state of default data sim.
     *
     * @param subId default data sim subid
     * @return The radio state of default data sim
     */
    public static boolean isRadioOn(long subId) {
        ITelephony telephony = ITelephony.Stub.asInterface(ServiceManager
                .getService(Context.TELEPHONY_SERVICE));
        try {
            if (telephony != null) {
                return telephony.isRadioOnForSubscriber(subId);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "mTelephony exception");
        }
        return false;
    }
}
