/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.keyguard;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.media.AudioManager ;
import android.os.SystemProperties;
import android.provider.Settings;
import android.telephony.SubInfoRecord;
import android.telephony.SubscriptionManager;
import android.util.Log;
import android.view.inputmethod.InputMethodManager;


/**
 * Utilities for Keyguard.
 */
public class KeyguardUtils {
    private static final String TAG = "KeyguardUtils";
    private static final boolean DEBUG = KeyguardConstants.DEBUG;
    private KeyguardUpdateMonitor mUpdateMonitor;

    /**
     * Constructor.
     * @param context the context
     */
    public KeyguardUtils(Context context) {
        mUpdateMonitor = KeyguardUpdateMonitor.getInstance(context);
    }

    /**
     * Return Operator name of related subId.
     * @param subId id of subscription
     * @param context the context
     * @return operator name.
     */
    public String getOptrNameUsingSubId(long subId, Context context) {
        SubInfoRecord info = SubscriptionManager.getSubInfoForSubscriber(subId);
        if (null == info) {
           if (DEBUG) {
            Log.d(TAG, "getOptrNameUsingSubId, return null");
           }
        } else {
           if (DEBUG) {
            Log.d(TAG, "getOptrNameUsingSubId mDisplayName=" + info.displayName);
           }
           return info.displayName;
        }
        return null;
    }

    /**
     * Return Operator drawable of related subId.
     * @param subId id of subscription
     * @param context the context
     * @return operator related drawable.
     */
    public Drawable getOptrDrawableUsingSubId(long subId, Context context) {
        Drawable bgDrawable = null;
        SubInfoRecord info = SubscriptionManager.getSubInfoForSubscriber(subId);
        if (null == info) {
            if (DEBUG) {
                Log.d(TAG, "getOptrDrawableUsingSubId, return null");
            }
        } else {
            if (info.simIconRes[0] > 0) {
                bgDrawable = context.getResources().getDrawable(info.simIconRes[0]);
            }
        }
        return bgDrawable;
    }


    /********************************************************
     ** Mediatek add begin.
     ********************************************************/
    private static final boolean mIsOwnerSdcardOnlySupport =
        SystemProperties.get("ro.mtk_owner_sdcard_support").equals("1");
    private static final boolean mIsVoiceUnlockSupport =
        SystemProperties.get("ro.mtk_voice_unlock_support").equals("1");
    private static final boolean mIsPrivacyProtectionLockSupport =
        SystemProperties.get("ro.mtk_privacy_protection_lock").equals("1");
    private static final boolean mIsMediatekSimMeLockSupport =
        SystemProperties.get("ro.sim_me_lock_mode", "0").equals("0");

    public static final boolean isOwnerSdcardOnlySupport() {
        return mIsOwnerSdcardOnlySupport;
    }

    public static final boolean isVoiceUnlockSupport() {
        return mIsVoiceUnlockSupport;
    }

    public static final boolean isPrivacyProtectionLockSupport() {
        return mIsPrivacyProtectionLockSupport ;
    }

    private static final String MTK_VOW_SUPPORT_State = "MTK_VOW_SUPPORT" ;
    private static final String MTK_VOW_SUPPORT_On = "MTK_VOW_SUPPORT=true" ;
    private static final String MTK_VOW_SUPPORT_Off = "MTK_VOW_SUPPORT=false" ;
    /**
     * Return VOW Support or not.
     * @param context the context
     * @return support or not
     */
    public static final boolean isVoiceWakeupSupport(Context context) {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE) ;
        if (am == null) {
            Log.d(TAG, "isVoiceWakeupSupport() - get AUDIO_SERVICE fails, return false.") ;
            return false ;
        }

        String val = am.getParameters(MTK_VOW_SUPPORT_State) ;
        return val != null && val.equalsIgnoreCase(MTK_VOW_SUPPORT_On) ;
    }

    public static final boolean isMediatekSimMeLockSupport() {
        return mIsMediatekSimMeLockSupport;
    }

    public static final boolean isTablet() {
        return ("tablet".equals(SystemProperties.get("ro.build.characteristics")));
    }

    /**
     * M : fix ALPS01564588. Refresh IME Stauts to hide ALT-BACK key correctly.
     * @param context the context
     */
    public static void requestImeStatusRefresh(Context context) {
        InputMethodManager imm =
            ((InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE));
        if (imm != null) {
            if (DEBUG) {
                Log.d(TAG, "call imm.requestImeStatusRefresh()");
            }
            imm.refreshImeWindowVisibility() ;
        }
    }

    /**
     * Return AirPlane mode is on or not.
     * @param context the context
     * @return airplane mode is on or not
     */
    public static boolean isAirplaneModeOn(Context context) {
        boolean airplaneModeOn = Settings.Global.getInt(context.getContentResolver(),
                                                        Settings.Global.AIRPLANE_MODE_ON,
                                                        0) != 0;
        Log.d(TAG, "isAirplaneModeOn() = " + airplaneModeOn) ;
        return airplaneModeOn ;
    }

    /**
     * if return true, it means that Modem will turn off after entering AirPlane mode.
     * @return support or not
     */
    public static boolean isFlightModePowerOffMd() {
        boolean powerOffMd = SystemProperties.get("ro.mtk_flight_mode_power_off_md").equals("1") ;
        Log.d(TAG, "powerOffMd = " + powerOffMd) ;
        return powerOffMd ;
    }
}
