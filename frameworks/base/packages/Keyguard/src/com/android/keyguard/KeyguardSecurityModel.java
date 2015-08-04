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

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.android.internal.telephony.IccCardConstants;
import com.android.internal.widget.LockPatternUtils;

import com.mediatek.keyguard.AntiTheft.AntiTheftManager ;
import com.mediatek.keyguard.PowerOffAlarm.PowerOffAlarmManager ;

public class KeyguardSecurityModel {

    /**
     * The different types of security available for {@link Mode#UnlockScreen}.
     * @see com.android.internal.policy.impl.LockPatternKeyguardView#getUnlockMode()
     */
     public static enum SecurityMode {
        Invalid, // NULL state
        None, // No security enabled
        Pattern, // Unlock by drawing a pattern.
        Password, // Unlock by entering an alphanumeric password
        PIN, // Strictly numeric password
        Biometric, // Unlock with a biometric key (e.g. finger print or face unlock)
        Account, // Unlock by entering an account's login and password.
        //SimPin, // Unlock by entering a sim pin.
        //SimPuk, // Unlock by entering a sim puk.
        SimPinPukMe1, // Unlock by entering a sim pin/puk/me for sim or gemini sim1.
        SimPinPukMe2, // Unlock by entering a sim pin/puk/me for sim or gemini sim2.
        SimPinPukMe3, // Unlock by entering a sim pin/puk/me for sim or gemini sim3.
        SimPinPukMe4, // Unlock by entering a sim pin/puk/me for sim or gemini sim4.
        AlarmBoot, // add for power-off alarm.
        Voice, // Unlock with voice password
        AntiTheft // Antitheft feature
    }

    private Context mContext;
    private LockPatternUtils mLockPatternUtils;
    private static final String TAG = "KeyguardSecurityModel" ;

    public KeyguardSecurityModel(Context context) {
        mContext = context;
        mLockPatternUtils = new LockPatternUtils(context);
    }

    void setLockPatternUtils(LockPatternUtils utils) {
        mLockPatternUtils = utils;
    }

    /**
     * Returns true if biometric unlock is installed and selected.  If this returns false there is
     * no need to even construct the biometric unlock.
     */
    boolean isBiometricUnlockEnabled() {
        return mLockPatternUtils.usingBiometricWeak()
                && mLockPatternUtils.isBiometricWeakInstalled();
    }

    /**
     * Returns true if a condition is currently suppressing the biometric unlock.  If this returns
     * true there is no need to even construct the biometric unlock.
     */
    private boolean isBiometricUnlockSuppressed() {
        KeyguardUpdateMonitor monitor = KeyguardUpdateMonitor.getInstance(mContext);
        final boolean backupIsTimedOut = monitor.getFailedUnlockAttempts() >=
                LockPatternUtils.FAILED_ATTEMPTS_BEFORE_TIMEOUT;
        return monitor.getMaxBiometricUnlockAttemptsReached() || backupIsTimedOut
                || !monitor.isAlternateUnlockEnabled()
                || monitor.getPhoneState() != TelephonyManager.CALL_STATE_IDLE;
    }

    public SecurityMode getSecurityMode() {
        //Log.d(TAG, "getSecurityMode() is called.") ;

        KeyguardUpdateMonitor updateMonitor = KeyguardUpdateMonitor.getInstance(mContext);
        IccCardConstants.State simState = IccCardConstants.State.UNKNOWN;

        SecurityMode mode = SecurityMode.None;

        //Log.d(TAG, "getSecurityMode() - check SIM Pin/Puk/Me dismissed?") ;
        for (int i = 0; i < updateMonitor.getNumOfSubscription(); i++) {
            long subId = updateMonitor.getSubIdUsingSubIndex(i);
            if (isPinPukOrMeRequiredOfSubId(subId)) {
                //Log.d(TAG, "getSecurityMode() - subId = " + subId + ", subIndex = "
                //+ i + " is pinpukme required!") ;
                if (0 == i) {
                    mode = SecurityMode.SimPinPukMe1;
                }
                else if (1 == i) {
                    mode = SecurityMode.SimPinPukMe2;
                }
                else if (2 == i) {
                    mode = SecurityMode.SimPinPukMe3;
                }
                else if (3 == i) {
                    mode = SecurityMode.SimPinPukMe4;
                }
                break;
            }
        }

        if (mode == SecurityMode.None) {
            if (PowerOffAlarmManager.isAlarmBoot()) { /// M: add for power-off alarm
                mode = SecurityMode.AlarmBoot;
            }
        }

        if (AntiTheftManager.isAntiTheftPriorToSecMode(mode)) {
            Log.d("KeyguardSecurityModel", "should show AntiTheft!") ;
            mode = SecurityMode.AntiTheft;
        }

        if (mode == SecurityMode.None) {
            final int security = mLockPatternUtils.getKeyguardStoredPasswordQuality();
            Log.d(TAG, "getSecurityMode() - PW_Quality = " + security) ;

            switch (security) {
                case DevicePolicyManager.PASSWORD_QUALITY_NUMERIC:
                case DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX:
                    mode = mLockPatternUtils.isLockPasswordEnabled() ?
                            SecurityMode.PIN : SecurityMode.None;
                    break;
                case DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC:
                case DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC:
                case DevicePolicyManager.PASSWORD_QUALITY_COMPLEX:
                    mode = mLockPatternUtils.isLockPasswordEnabled() ?
                            SecurityMode.Password : SecurityMode.None;
                    break;

                case DevicePolicyManager.PASSWORD_QUALITY_SOMETHING:
                case DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED:
                    if (mLockPatternUtils.isLockPatternEnabled()) {
                        mode = mLockPatternUtils.isPermanentlyLocked() ?
                            SecurityMode.Account : SecurityMode.Pattern;
                    }
                    break;

                default:
                    throw new IllegalStateException("Unknown security quality:" + security);
            }
        }

        //Log.d(TAG, "getSecurityMode() - mode = " + mode) ;

        return mode;
    }

    /**
     * Some unlock methods can have an alternate, such as biometric unlocks (e.g. face unlock).
     * This function decides if an alternate unlock is available and returns it. Otherwise,
     * returns @param mode.
     *
     * @param mode the mode we want the alternate for
     * @return alternate or the given mode
     */
    SecurityMode getAlternateFor(SecurityMode mode) {
        if (!isBiometricUnlockSuppressed()
                && (mode == SecurityMode.Password
                        || mode == SecurityMode.PIN
                        || mode == SecurityMode.Pattern)) {
            if (isBiometricUnlockEnabled()) {
                return SecurityMode.Biometric;
            } else if (mLockPatternUtils.usingVoiceWeak()) { ///M add for voice unlock
                return SecurityMode.Voice;
            }
        }
        return mode; // no alternate, return what was given
    }

    /**
     * Some unlock methods can have a backup which gives the user another way to get into
     * the device. This is currently only supported for Biometric and Pattern unlock.
     *
     * @return backup method or current security mode
     */
    SecurityMode getBackupSecurityMode(SecurityMode mode) {
        switch(mode) {
            case Biometric:
            case Voice: ///M: add for voice unlock
                return getSecurityMode();
            case Pattern:
                return SecurityMode.Account;
        }
        return mode; // no backup, return current security mode
    }

    /**
     * M:
     * Returns true if voice unlock is support and selected.  If this returns false there is
     * no need to even construct the voice unlock.
     */
    boolean isVoiceUnlockEnabled() {
        return KeyguardUtils.isVoiceUnlockSupport()
                && mLockPatternUtils.usingVoiceWeak();
    }

    /**
     * M:
     * This function checking if we need to show the SimPin lock view for this sim id.
     *
     * @param subId subscription ID
     * @return subId does requre SIM PIN/PUK/ME unlock
     */
    public boolean isPinPukOrMeRequiredOfSubId(long subId) {
        KeyguardUpdateMonitor updateMonitor = KeyguardUpdateMonitor.getInstance(mContext);
        //int simId = updateMonitor.getSlotIdUsingSubId(subId) ;

        //Log.d(TAG, "isPinPukOrMeRequiredOfSubId() - subId = " + subId) ;

        if (updateMonitor != null) {
            final IccCardConstants.State simState = updateMonitor.getSimStateOfSub(subId);

            Log.d(TAG, "isPinPukOrMeRequiredOfSubId() - simState = " + simState) ;
            return (
                // check PIN required
                (simState == IccCardConstants.State.PIN_REQUIRED
                && !updateMonitor.getPinPukMeDismissFlagOfSub(subId))
                // check PUK required
                || (simState == IccCardConstants.State.PUK_REQUIRED
                && !updateMonitor.getPinPukMeDismissFlagOfSub(subId)
                && updateMonitor.getRetryPukCountOfSub(subId) != 0)
                // check ME required
                || (simState == IccCardConstants.State.NETWORK_LOCKED
                && !updateMonitor.getPinPukMeDismissFlagOfSub(subId)
                && updateMonitor.getSimMeLeftRetryCountOfSub(subId) != 0
                && KeyguardUtils.isMediatekSimMeLockSupport())
                );
        } else {
            return false;
        }
    }

    /**
     * M:
     * This function return the subIndex of input SimPinPukMe mode.
     */
    int getSubIndexFromSecurityMode(SecurityMode mode) {
        int subIndex = 0;

        if (isSimPinPukSecurityMode(mode)) {
            subIndex = mode.ordinal() - SecurityMode.SimPinPukMe1.ordinal();
        }
        return subIndex;
    }

    /**
     * M:
     * This function checking if the input security is SimPinPukMe mode or not.
     */
    boolean isSimPinPukSecurityMode(SecurityMode mode) {
        switch(mode) {
            case SimPinPukMe1:
            case SimPinPukMe2:
            case SimPinPukMe3:
            case SimPinPukMe4:
                return true;
            default:
                return false;
        }
    }
}
