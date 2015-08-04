/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.app.ActivityManagerNative;
import android.app.AlarmManager;
import android.app.IUserSwitchObserver;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.app.trust.TrustManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.os.BatteryManager;
import static android.os.BatteryManager.BATTERY_STATUS_FULL;
import static android.os.BatteryManager.BATTERY_STATUS_UNKNOWN;
import static android.os.BatteryManager.BATTERY_HEALTH_UNKNOWN;
import static android.os.BatteryManager.EXTRA_STATUS;
import static android.os.BatteryManager.EXTRA_PLUGGED;
import static android.os.BatteryManager.EXTRA_LEVEL;
import static android.os.BatteryManager.EXTRA_HEALTH;

import android.media.AudioManager;
import android.media.IRemoteControlDisplay;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IRemoteCallback;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;

import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;

import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyIntents;

import android.service.fingerprint.FingerprintManager;
import android.service.fingerprint.FingerprintManagerReceiver;
import android.service.fingerprint.FingerprintUtils;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.util.SparseBooleanArray;

import com.google.android.collect.Lists;
import com.mediatek.internal.telephony.ITelephonyEx;
//import com.mediatek.common.dm.DmAgent;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;

//import com.mediatek.CellConnService.CellConnMgr;
//import com.mediatek.common.telephony.ITelephonyEx;


/**
 * Watches for updates that may be interesting to the keyguard, and provides
 * the up to date information as well as a registration for callbacks that care
 * to be updated.
 *
 * Note: under time crunch, this has been extended to include some stuff that
 * doesn't really belong here.  see {@link #handleBatteryUpdate} where it shutdowns
 * the device, and {@link #getFailedUnlockAttempts()}, {@link #reportFailedAttempt()}
 * and {@link #clearFailedUnlockAttempts()}.  Maybe we should rename this 'KeyguardContext'...
 */
public class KeyguardUpdateMonitor implements TrustManager.TrustListener {

    private static final String TAG = "KeyguardUpdateMonitor";
    private static final boolean DEBUG = KeyguardConstants.DEBUG;
    private static final boolean DEBUG_SIM_STATES = DEBUG || false;
    private static final int FAILED_BIOMETRIC_UNLOCK_ATTEMPTS_BEFORE_BACKUP = 3;
    /// M: support multiple battery number
    private static final int KEYGUARD_BATTERY_NUMBER = 2;
    /// M: Change the threshold to 16 for mediatek device
    private static final int LOW_BATTERY_THRESHOLD = 16;

    private static final String ACTION_FACE_UNLOCK_STARTED
            = "com.android.facelock.FACE_UNLOCK_STARTED";
    private static final String ACTION_FACE_UNLOCK_STOPPED
            = "com.android.facelock.FACE_UNLOCK_STOPPED";

    // Callback messages
    private static final int MSG_TIME_UPDATE = 301;
    private static final int MSG_BATTERY_UPDATE = 302;
    private static final int MSG_CARRIER_INFO_UPDATE = 303;
    private static final int MSG_SIM_STATE_CHANGE = 304;
    private static final int MSG_RINGER_MODE_CHANGED = 305;
    private static final int MSG_PHONE_STATE_CHANGED = 306;
    private static final int MSG_CLOCK_VISIBILITY_CHANGED = 307;
    private static final int MSG_DEVICE_PROVISIONED = 308;
    private static final int MSG_DPM_STATE_CHANGED = 309;
    private static final int MSG_USER_SWITCHING = 310;
    private static final int MSG_USER_REMOVED = 311;
    private static final int MSG_KEYGUARD_VISIBILITY_CHANGED = 312;
    private static final int MSG_BOOT_COMPLETED = 313;
    private static final int MSG_USER_SWITCH_COMPLETE = 314;
    private static final int MSG_SET_CURRENT_CLIENT_ID = 315;
    private static final int MSG_SET_PLAYBACK_STATE = 316;
    private static final int MSG_USER_INFO_CHANGED = 317;
    private static final int MSG_REPORT_EMERGENCY_CALL_ACTION = 318;
    private static final int MSG_SCREEN_TURNED_ON = 319;
    private static final int MSG_SCREEN_TURNED_OFF = 320;
    private static final int MSG_KEYGUARD_BOUNCER_CHANGED = 322;
    private static final int MSG_FINGERPRINT_PROCESSED = 323;
    private static final int MSG_FINGERPRINT_ACQUIRED = 324;
    private static final int MSG_FACE_UNLOCK_STATE_CHANGED = 325;
    private static final int MSG_SUBINFO_RECORD_UPDATE = 326;
    private static final int MSG_SUBINFO_CONTENT_CHANGE = 327;

    private static KeyguardUpdateMonitor sInstance;

    private final Context mContext;

    // Telephony state
    private HashMap<Long, IccCardConstants.State> mSimStateOfSub =
            new HashMap<Long, IccCardConstants.State>();
    private HashMap<Long, Integer> mSlotIdOfSub = new HashMap<Long, Integer>();
    private HashMap<Long, CharSequence> mTelephonyPlmn = new HashMap<Long, CharSequence>();
    private HashMap<Long, CharSequence> mTelephonySpn = new HashMap<Long, CharSequence>();
    private long mStandbySubId[];
    private int mRingMode;

    // Phone state is set as OFFHOOK if one subscription is in OFFHOOK state.
    private int mPhoneState;
    private boolean mKeyguardIsVisible;
    private boolean mBouncer;
    private boolean mBootCompleted;

    // Device provisioning state
    private boolean mDeviceProvisioned;

    // Password attempts
    private int mFailedAttempts = 0;
    private int mFailedBiometricUnlockAttempts = 0;

    private boolean mAlternateUnlockEnabled;

    private boolean mClockVisible;

    private final ArrayList<WeakReference<KeyguardUpdateMonitorCallback>>
            mCallbacks = Lists.newArrayList();
    private ContentObserver mDeviceProvisionedObserver;

    private boolean mSwitchingUser;

    private boolean mScreenOn;

    private int mSubCount = 0;

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_TIME_UPDATE:
                    handleTimeUpdate();
                    break;
                case MSG_BATTERY_UPDATE:
                    handleBatteryUpdate((BatteryStatus) msg.obj);
                    break;
                case MSG_CARRIER_INFO_UPDATE:
                    handleCarrierInfoUpdate((Long) msg.obj);
                    break;
                case MSG_SIM_STATE_CHANGE:
                    handleSimStateChange((SimArgs) msg.obj);
                    break;
                case MSG_RINGER_MODE_CHANGED:
                    handleRingerModeChange(msg.arg1);
                    break;
                case MSG_PHONE_STATE_CHANGED:
                    handlePhoneStateChanged();
                    break;
                case MSG_CLOCK_VISIBILITY_CHANGED:
                    handleClockVisibilityChanged();
                    break;
                case MSG_DEVICE_PROVISIONED:
                    handleDeviceProvisioned();
                    break;
                case MSG_DPM_STATE_CHANGED:
                    handleDevicePolicyManagerStateChanged();
                    break;
                case MSG_USER_SWITCHING:
                    handleUserSwitching(msg.arg1, (IRemoteCallback) msg.obj);
                    break;
                case MSG_USER_SWITCH_COMPLETE:
                    handleUserSwitchComplete(msg.arg1);
                    break;
                case MSG_USER_REMOVED:
                    handleUserRemoved(msg.arg1);
                    break;
                case MSG_KEYGUARD_VISIBILITY_CHANGED:
                    handleKeyguardVisibilityChanged(msg.arg1);
                    break;
                case MSG_KEYGUARD_BOUNCER_CHANGED:
                    handleKeyguardBouncerChanged(msg.arg1);
                    break;
                case MSG_BOOT_COMPLETED:
                    handleBootCompleted();
                    break;
                case MSG_USER_INFO_CHANGED:
                    handleUserInfoChanged(msg.arg1);
                    break;
                case MSG_REPORT_EMERGENCY_CALL_ACTION:
                    handleReportEmergencyCallAction();
                    break;
                case MSG_SCREEN_TURNED_OFF:
                    handleScreenTurnedOff(msg.arg1);
                    break;
                case MSG_SCREEN_TURNED_ON:
                    handleScreenTurnedOn();
                    break;
                case MSG_FINGERPRINT_ACQUIRED:
                    handleFingerprintAcquired(msg.arg1);
                    break;
                case MSG_FINGERPRINT_PROCESSED:
                    handleFingerprintProcessed(msg.arg1);
                    break;
                case MSG_FACE_UNLOCK_STATE_CHANGED:
                    handleFaceUnlockStateChanged(msg.arg1 != 0, msg.arg2);
                    break;
                case MSG_SUBINFO_RECORD_UPDATE:
                    handleSubInfoRecordUpdate();
                    break;
                case MSG_SUBINFO_CONTENT_CHANGE:
                    handleSubInfoContentChange((SubInfoContent) msg.obj);
                    break;
                ///M: support SMB dock status change
                case MSG_DOCK_STATUS_UPDATE:
                    if (DEBUG) Log.d(TAG, "MSG_DOCK_STATUS_UPDATE, msg.arg1=" + msg.arg1);
                    handleDockStatusUpdate(msg.arg1);
                    break;
                case MSG_AIRPLANE_MODE_UPDATE:
                    if (DEBUG) {
                        Log.d(TAG, "MSG_AIRPLANE_MODE_UPDATE, msg.obj=" + (Boolean)msg.obj);
                    }
                    handleAirPlaneModeUpdate((Boolean)msg.obj) ;
                    break;
            }
        }
    };

    private SparseBooleanArray mUserHasTrust = new SparseBooleanArray();
    private SparseBooleanArray mUserTrustIsManaged = new SparseBooleanArray();
    private SparseBooleanArray mUserFingerprintRecognized = new SparseBooleanArray();
    private SparseBooleanArray mUserFaceUnlockRunning = new SparseBooleanArray();

    @Override
    public void onTrustChanged(boolean enabled, int userId, boolean initiatedByUser) {
        if (DEBUG) Log.d(TAG, "onTrustChanged(enabled = " + " , userId = " + userId + ")") ;

        mUserHasTrust.put(userId, enabled);

        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onTrustChanged(userId);
                if (enabled && initiatedByUser) {
                    cb.onTrustInitiatedByUser(userId);
                }
            }
        }
    }

    @Override
    public void onTrustManagedChanged(boolean managed, int userId) {
        mUserTrustIsManaged.put(userId, managed);

        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onTrustManagedChanged(userId);
            }
        }
    }

    private void onFingerprintRecognized(int userId) {
        mUserFingerprintRecognized.put(userId, true);
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onFingerprintRecognized(userId);
            }
        }
    }

    private void handleFingerprintProcessed(int fingerprintId) {
        if (fingerprintId == 0) return; // not a valid fingerprint

        final int userId;
        try {
            userId = ActivityManagerNative.getDefault().getCurrentUser().id;
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to get current user id: ", e);
            return;
        }
        if (isFingerprintDisabled(userId)) {
            Log.d(TAG, "Fingerprint disabled by DPM for userId: " + userId);
            return;
        }
        final ContentResolver res = mContext.getContentResolver();
        final int ids[] = FingerprintUtils.getFingerprintIdsForUser(res, userId);
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] == fingerprintId) {
                onFingerprintRecognized(userId);
            }
        }
    }

    private void handleFingerprintAcquired(int info) {
        if (DEBUG) Log.d(TAG, "handleFingerprintAcquired() is called.") ;
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onFingerprintAcquired(info);
            }
        }
    }

    private void handleFaceUnlockStateChanged(boolean running, int userId) {
        mUserFaceUnlockRunning.put(userId, running);
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onFaceUnlockStateChanged(running, userId);
            }
        }
    }

    public boolean isFaceUnlockRunning(int userId) {
        return mUserFaceUnlockRunning.get(userId);
    }

    private boolean isTrustDisabled(int userId) {
        final DevicePolicyManager dpm =
                (DevicePolicyManager) mContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm != null) {
                // TODO once UI is finalized
                final boolean disabledByGlobalActions = false;
                final boolean disabledBySettings = false;

                // Don't allow trust agent if device is secured with a SIM PIN. This is here
                // mainly because there's no other way to prompt the user to enter their SIM PIN
                // once they get past the keyguard screen.
                final boolean disabledBySimPin = isSimPinSecure();

                final boolean disabledByDpm = (dpm.getKeyguardDisabledFeatures(null, userId)
                        & DevicePolicyManager.KEYGUARD_DISABLE_TRUST_AGENTS) != 0;
                return disabledByDpm || disabledByGlobalActions || disabledBySettings
                        || disabledBySimPin;
        }
        return false;
    }

    private boolean isFingerprintDisabled(int userId) {
        final DevicePolicyManager dpm =
                (DevicePolicyManager) mContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
        return dpm != null && (dpm.getKeyguardDisabledFeatures(null, userId)
                    & DevicePolicyManager.KEYGUARD_DISABLE_FINGERPRINT) != 0;
    }

    public boolean getUserHasTrust(int userId) {
        return !isTrustDisabled(userId) && mUserHasTrust.get(userId)
                || mUserFingerprintRecognized.get(userId);
    }

    public boolean getUserTrustIsManaged(int userId) {
        return mUserTrustIsManaged.get(userId) && !isTrustDisabled(userId);
    }

    static class DisplayClientState {
        public int clientGeneration;
        public boolean clearing;
        public PendingIntent intent;
        public int playbackState;
        public long playbackEventTime;
    }

    private DisplayClientState mDisplayClientState = new DisplayClientState();

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {

        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (DEBUG) Log.d(TAG, "received broadcast " + action);

            if (Intent.ACTION_TIME_TICK.equals(action)
                    || Intent.ACTION_TIME_CHANGED.equals(action)
                    || Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
                mHandler.sendEmptyMessage(MSG_TIME_UPDATE);
            } else if (TelephonyIntents.SPN_STRINGS_UPDATED_ACTION.equals(action)) {
                long subId = intent.getLongExtra(PhoneConstants.SUBSCRIPTION_KEY,
                        SubscriptionManager.INVALID_SUB_ID);

                mTelephonyPlmn.put(subId, getTelephonyPlmnFrom(intent));
                mTelephonySpn.put(subId, getTelephonySpnFrom(intent));
                mTelephonyCsgId.put(subId, getTelephonyCsgIdFrom(intent)) ;
                mTelephonyHnbName.put(subId, getTelephonyHnbNameFrom(intent));
                if (DEBUG) Log.d(TAG, "SPN_STRINGS_UPDATED_ACTION, update subId=" + subId
                        + ", plmn=" + mTelephonyPlmn.get(subId)
                        + ", spn=" + mTelephonySpn.get(subId)
                        + ", csgId=" + mTelephonyCsgId.get(subId)
                        + ", hnbName=" + mTelephonyHnbName.get(subId));
                mHandler.sendMessage(mHandler.obtainMessage(MSG_CARRIER_INFO_UPDATE, subId));
            } else if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                int status = intent.getIntExtra(EXTRA_STATUS, BATTERY_STATUS_UNKNOWN);
                int plugged = intent.getIntExtra(EXTRA_PLUGGED, 0);
                int level = intent.getIntExtra(EXTRA_LEVEL, 0);
                int health = intent.getIntExtra(EXTRA_HEALTH, BATTERY_HEALTH_UNKNOWN);
                Message msg = mHandler.obtainMessage(
                        MSG_BATTERY_UPDATE, new BatteryStatus(0, status, level, plugged, health));
                mHandler.sendMessage(msg);

                boolean b2ndBattPresent = intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT_SMARTBOOK, false);

                if (mDocktoDesk && b2ndBattPresent) {
                    status = intent.getIntExtra(BatteryManager.EXTRA_STATUS_SMARTBOOK, BATTERY_STATUS_UNKNOWN);
                    plugged = BatteryManager.BATTERY_PLUGGED_AC;
                    level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL_SMARTBOOK, 0);
                    health = BATTERY_HEALTH_UNKNOWN;

                    if (DEBUG) Log.d(TAG, "batt2 is present status=" + status + " level=" + level);

                    msg = mHandler.obtainMessage(
                            MSG_BATTERY_UPDATE, new BatteryStatus(1, status, level, plugged, health));
                    mHandler.sendMessage(msg);
                }
            } else if (TelephonyIntents.ACTION_SIM_STATE_CHANGED.equals(action)
                    || TelephonyIntents.ACTION_UNLOCK_SIM_LOCK.equals(action)) {
                String stateExtra = intent.getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE);
                SimArgs simArgs = SimArgs.fromIntent(intent);
                if (DEBUG_SIM_STATES) {
                    Log.v(TAG, "action=" + action + ", state=" + stateExtra
                        + ", slotId=" + simArgs.slotId + ", subId=" + simArgs.subId
                        + ", simArgs.simState = " + simArgs.simState);
                }

                if (TelephonyIntents.ACTION_UNLOCK_SIM_LOCK.equals(action)) {
                    /// M: set sim state as UNKNOWN state to trigger SIM lock view again.
                    mSimStateOfSub.put(simArgs.subId, IccCardConstants.State.UNKNOWN);
                }

                proceedToHandleSimStateChanged(simArgs) ;
            } else if (AudioManager.RINGER_MODE_CHANGED_ACTION.equals(action)) {
                mHandler.sendMessage(mHandler.obtainMessage(MSG_RINGER_MODE_CHANGED,
                        intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, -1), 0));
            } else if (TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(action)) {
                String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
                mHandler.sendMessage(mHandler.obtainMessage(MSG_PHONE_STATE_CHANGED, state));
            } else if (Intent.ACTION_USER_REMOVED.equals(action)) {
                mHandler.sendMessage(mHandler.obtainMessage(MSG_USER_REMOVED,
                       intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0), 0));
            } else if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
                dispatchBootCompleted();
            } else if (TelephonyIntents.ACTION_SUBINFO_RECORD_UPDATED.equals(action)) {
                if (DEBUG) Log.d(TAG, "received ACTION_SUBINFO_RECORD_UPDATED");
                mHandler.sendEmptyMessage(MSG_SUBINFO_RECORD_UPDATE);
            } else if (TelephonyIntents.ACTION_SUBINFO_CONTENT_CHANGE.equals(action)) {
                long subId = intent.getLongExtra(SubscriptionManager._ID,
                        SubscriptionManager.INVALID_SUB_ID);
                String column = intent.getStringExtra(TelephonyIntents.EXTRA_COLUMN_NAME);
                String sValue = intent.getStringExtra(TelephonyIntents.EXTRA_STRING_CONTENT);
                int iValue = intent.getIntExtra(TelephonyIntents.EXTRA_INT_CONTENT, 0);
                if (DEBUG) Log.d(TAG, "received SUBINFO_CONTENT_CHANGE" + " subid=" + subId
                        + " colum=" + column + " sValue=" + sValue + " iValue=" + iValue);
                final Message msg = mHandler.obtainMessage(
                        MSG_SUBINFO_CONTENT_CHANGE, new SubInfoContent(subId, column, sValue, iValue));
                mHandler.sendMessage(msg);
            }
            /// M: Docking to SmartBook state changed
            else if (ACTION_SMARTBOOK_PLUG.equals(action)) {
                mDocktoDesk = intent.getBooleanExtra(EXTRA_SMARTBOOK_PLUG_STATE, false);
                int plugState = mDocktoDesk ? 1 : 0;
                if (DEBUG) {
                    Log.d(TAG, "mDocktoDesk=" + mDocktoDesk + " plugState = " + plugState);
                }

                mHandler.sendMessage(mHandler.obtainMessage(MSG_DOCK_STATUS_UPDATE, plugState, 0));
             } else if (Intent.ACTION_AIRPLANE_MODE_CHANGED.equals(action)) {
                ///M: fix ALPS01821063, we should assume that extra value may not exist.
                ///   Although the extra value of AIRPLANE_MODE_CHANGED intent should exist in fact.
                boolean state = intent.getBooleanExtra("state", false);
                Log.d(TAG, "Receive ACTION_AIRPLANE_MODE_CHANGED, state = " + state);
                Message msg = new Message() ;
                msg.what = MSG_AIRPLANE_MODE_UPDATE ;
                msg.obj = new Boolean(state) ;
                mHandler.sendMessage(msg);                
            }
        }
    };

    private void proceedToHandleSimStateChanged(SimArgs simArgs) {
        if ((IccCardConstants.State.NETWORK_LOCKED == simArgs.simState) &&
            KeyguardUtils.isMediatekSimMeLockSupport()) {
            //if (KeyguardUtils.isMediatekSimMeLockSupport()) {
            /// M: to create new thread to query SIM ME lock status
            /// after finish query, send MSG_SIM_STATE_CHANGE message
            new simMeStatusQueryThread(simArgs).start();
        } else {
            mHandler.sendMessage(mHandler.obtainMessage(MSG_SIM_STATE_CHANGE, simArgs));
        }
    }

    private final BroadcastReceiver mBroadcastAllReceiver = new BroadcastReceiver() {

        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED.equals(action)) {
                mHandler.sendEmptyMessage(MSG_TIME_UPDATE);
            } else if (Intent.ACTION_USER_INFO_CHANGED.equals(action)) {
                mHandler.sendMessage(mHandler.obtainMessage(MSG_USER_INFO_CHANGED,
                        intent.getIntExtra(Intent.EXTRA_USER_HANDLE, getSendingUserId()), 0));
            } else if (ACTION_FACE_UNLOCK_STARTED.equals(action)) {
                mHandler.sendMessage(mHandler.obtainMessage(MSG_FACE_UNLOCK_STATE_CHANGED, 1,
                        getSendingUserId()));
            } else if (ACTION_FACE_UNLOCK_STOPPED.equals(action)) {
                mHandler.sendMessage(mHandler.obtainMessage(MSG_FACE_UNLOCK_STATE_CHANGED, 0,
                        getSendingUserId()));
            } else if (DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED
                    .equals(action)) {
                mHandler.sendEmptyMessage(MSG_DPM_STATE_CHANGED);
            }
        }
    };
    private FingerprintManagerReceiver mFingerprintManagerReceiver =
            new FingerprintManagerReceiver() {
        @Override
        public void onProcessed(int fingerprintId) {
            mHandler.obtainMessage(MSG_FINGERPRINT_PROCESSED, fingerprintId, 0).sendToTarget();
        };

        @Override
        public void onAcquired(int info) {
            mHandler.obtainMessage(MSG_FINGERPRINT_ACQUIRED, info, 0).sendToTarget();
        }

        @Override
        public void onError(int error) {
            if (DEBUG) Log.w(TAG, "FingerprintManager reported error: " + error);
        }
    };

    /**
     * When we receive a
     * {@link com.android.internal.telephony.TelephonyIntents#ACTION_SIM_STATE_CHANGED} broadcast,
     * and then pass a result via our handler to {@link KeyguardUpdateMonitor#handleSimStateChange},
     * we need a single object to pass to the handler.  This class helps decode
     * the intent and provide a {@link SimCard.State} result.
     */
    private static class SimArgs {
        public final IccCardConstants.State simState;
        int slotId = 0;
        long subId = 0;
        int simMECategory = 0;

        SimArgs(IccCardConstants.State state, int slotId, long subId) {
            this.simState = state;
            this.slotId = slotId;
            this.subId = subId;
        }

        SimArgs(IccCardConstants.State state, int slotId, long subId, int meCategory) {
           this.simState = state;
           this.slotId = slotId;
           this.subId = subId ;
           this.simMECategory = meCategory;
        }

        static SimArgs fromIntent(Intent intent) {
            IccCardConstants.State state;
            int meCategory = 0;
            String stateExtra = intent.getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE);
            int slotId = intent.getIntExtra(PhoneConstants.SLOT_KEY, 0);
            long subId = intent.getLongExtra(PhoneConstants.SUBSCRIPTION_KEY,
                    SubscriptionManager.INVALID_SUB_ID);

            if (IccCardConstants.INTENT_VALUE_ICC_ABSENT.equals(stateExtra)) {
                final String absentReason = intent
                    .getStringExtra(IccCardConstants.INTENT_KEY_LOCKED_REASON);

                if (IccCardConstants.INTENT_VALUE_ABSENT_ON_PERM_DISABLED.equals(
                        absentReason)) {
                    state = IccCardConstants.State.PERM_DISABLED;
                }
                else {
                    state = IccCardConstants.State.ABSENT;
                }
            } else if (IccCardConstants.INTENT_VALUE_ICC_READY.equals(stateExtra)) {
                state = IccCardConstants.State.READY;

            } else if (IccCardConstants.INTENT_VALUE_ICC_LOCKED.equals(stateExtra)) {
                final String lockedReason = intent
                        .getStringExtra(IccCardConstants.INTENT_KEY_LOCKED_REASON);
                Log.d(TAG, "INTENT_VALUE_ICC_LOCKED, lockedReason=" + lockedReason);

                if (IccCardConstants.INTENT_VALUE_LOCKED_ON_PIN.equals(lockedReason)) {
                    state = IccCardConstants.State.PIN_REQUIRED;
                }
                else if (IccCardConstants.INTENT_VALUE_LOCKED_ON_PUK.equals(lockedReason)) {
                    state = IccCardConstants.State.PUK_REQUIRED;
                }
                else if (IccCardConstants.INTENT_VALUE_LOCKED_NETWORK.equals(lockedReason)) {
                    meCategory = 0;
                    state = IccCardConstants.State.NETWORK_LOCKED;
                }
                else if (IccCardConstants.INTENT_VALUE_LOCKED_NETWORK_SUBSET.equals(lockedReason)) {
                    meCategory = 1;
                    state = IccCardConstants.State.NETWORK_LOCKED;
                }
                else if (IccCardConstants.INTENT_VALUE_LOCKED_SERVICE_PROVIDER.equals(lockedReason)) {
                    meCategory = 2;
                    state = IccCardConstants.State.NETWORK_LOCKED;
                }
                else if (IccCardConstants.INTENT_VALUE_LOCKED_CORPORATE.equals(lockedReason)) {
                    meCategory = 3;
                    state = IccCardConstants.State.NETWORK_LOCKED;
                }
                else if (IccCardConstants.INTENT_VALUE_LOCKED_SIM.equals(lockedReason)) {
                    meCategory = 4;
                    state = IccCardConstants.State.NETWORK_LOCKED;
                } else {
                    state = IccCardConstants.State.UNKNOWN;
                }
            }
            else if (IccCardConstants.INTENT_VALUE_LOCKED_NETWORK.equals(stateExtra)) {
                state = IccCardConstants.State.NETWORK_LOCKED;

            } else if (//IccCardConstants.INTENT_VALUE_ICC_LOADED.equals(stateExtra) ||
                        IccCardConstants.INTENT_VALUE_ICC_IMSI.equals(stateExtra)) {
                // This is required because telephony doesn't return to "READY" after
                // these state transitions. See bug 7197471.
                state = IccCardConstants.State.READY;
            }
            else if (IccCardConstants.INTENT_VALUE_ICC_NOT_READY.equals(stateExtra)) {
                state = IccCardConstants.State.NOT_READY;
            }
            else {
                state = IccCardConstants.State.UNKNOWN;
            }

            return new SimArgs(state, slotId, subId, meCategory);
        }

        public String toString() {
            return simState.toString();
        }
    }

    public static class BatteryStatus {
        public final int index;
        public final int status;
        public final int level;
        public final int plugged;
        public final int health;
        public BatteryStatus(int index, int status, int level, int plugged, int health) {
            this.index = index;
            this.status = status;
            this.level = level;
            this.plugged = plugged;
            this.health = health;
        }

        /**
         * Determine whether the device is plugged in (USB, power, or wireless).
         * @return true if the device is plugged in.
         */
        boolean isPluggedIn() {
            return plugged == BatteryManager.BATTERY_PLUGGED_AC
                    || plugged == BatteryManager.BATTERY_PLUGGED_USB
                    || plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS;
        }

        /**
         * Whether or not the device is charged. Note that some devices never return 100% for
         * battery level, so this allows either battery level or status to determine if the
         * battery is charged.
         * @return true if the device is charged
         */
        public boolean isCharged() {
            return status == BATTERY_STATUS_FULL || level >= 100;
        }

        /**
         * Whether battery is low and needs to be charged.
         * @return true if battery is low
         */
        public boolean isBatteryLow() {
            return level < LOW_BATTERY_THRESHOLD;
        }

    }

    /* package */ static class SubInfoContent {
        public final long subInfoId;
        public final String column;
        public final String sValue;
        public final int iValue;
        public SubInfoContent(long subInfoId, String column, String sValue, int iValue) {
            this.subInfoId = subInfoId;
            this.column = column;
            this.sValue = sValue;
            this.iValue = iValue;
        }
    }

    public static KeyguardUpdateMonitor getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new KeyguardUpdateMonitor(context);
        }
        return sInstance;
    }

    protected void handleScreenTurnedOn() {
        final int count = mCallbacks.size();
        for (int i = 0; i < count; i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onScreenTurnedOn();
            }
        }
    }

    protected void handleScreenTurnedOff(int arg1) {
        clearFingerprintRecognized();
        final int count = mCallbacks.size();
        for (int i = 0; i < count; i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onScreenTurnedOff(arg1);
            }
        }
    }

    protected void handleSubInfoRecordUpdate() {
        if (DEBUG) Log.d(TAG, "handleSubInfoRecordUpdate");
        long[] activeSubIds = SubscriptionManager.getActiveSubIdList();
        for (long activeSubId : activeSubIds) {
            if (activeSubId <= 0) {
                Log.e(TAG, "handleSubInfoRecordUpdate: invalid activeSubId = " + activeSubId);
                continue;
            }

            int phoneId = SubscriptionManager.getPhoneId(activeSubId);
            if (phoneId < 0 || phoneId >= getNumOfSubscription()) {
                Log.e(TAG, "handleSubInfoRecordUpdate: invalid phonId = " + phoneId);
                continue;
            }

            long origSubId = mStandbySubId[phoneId];
            mStandbySubId[phoneId] = activeSubId;
            if (DEBUG) Log.d(TAG, "handleSubInfoRecordUpdate mStandbySubId[" +
                    phoneId + "]=" + activeSubId);

            if (origSubId < 0) {
                mSimStateOfSub.put(activeSubId, mSimStateOfSub.get(origSubId));
                mSlotIdOfSub.put(activeSubId, mSlotIdOfSub.get(origSubId));
                mTelephonyPlmn.put(activeSubId, mTelephonyPlmn.get(origSubId));
                mTelephonySpn.put(activeSubId, mTelephonySpn.get(origSubId));
                mSimMeCategory.put(activeSubId, mSimMeCategory.get(origSubId));
                mSimMeLeftRetryCount.put(activeSubId, mSimMeLeftRetryCount.get(origSubId));

                final int count = mCallbacks.size();
                for (int i = 0; i < count; i++) {
                    KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
                    if (cb != null) {
                        cb.onSubIdUpdated(origSubId, activeSubId);
                    }
                }
            }
        }
    }

    protected void handleSubInfoContentChange(SubInfoContent content) {
        final int count = mCallbacks.size();
        for (int i = 0; i < count; i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onSubInfoContentChanged(content.subInfoId, content.column,
                    content.sValue, content.iValue);
            }
        }
    }

    /**
     * IMPORTANT: Must be called from UI thread.
     */
    public void dispatchSetBackground(Bitmap bmp) {
        if (DEBUG) Log.d(TAG, "dispatchSetBackground");
        final int count = mCallbacks.size();
        for (int i = 0; i < count; i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onSetBackground(bmp);
            }
        }
    }

    private void handleUserInfoChanged(int userId) {
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onUserInfoChanged(userId);
            }
        }
    }

    private KeyguardUpdateMonitor(Context context) {
        mContext = context;
        mDeviceProvisioned = isDeviceProvisionedInSettingsDb();

        if (DEBUG) Log.d(TAG, "mDeviceProvisioned is:" + mDeviceProvisioned);

        // Since device can't be un-provisioned, we only need to register a content observer
        // to update mDeviceProvisioned when we are...
        if (!mDeviceProvisioned) {
            watchForDeviceProvisioning();
        }

        /// M: support multiple batteries
        mBatteryStatus = new BatteryStatus[KEYGUARD_BATTERY_NUMBER];
        for (int i = 0; i < KEYGUARD_BATTERY_NUMBER; i++) {
            mBatteryStatus[i] = new BatteryStatus(i, BATTERY_STATUS_UNKNOWN, 100, 0, 0);
        }

        initMembers() ;

        // Watch for interesting updates
        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_TIME_TICK);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        filter.addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
        filter.addAction(TelephonyIntents.SPN_STRINGS_UPDATED_ACTION);
        filter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
        filter.addAction(Intent.ACTION_USER_REMOVED);
        filter.addAction(TelephonyIntents.ACTION_SUBINFO_RECORD_UPDATED);
        filter.addAction(TelephonyIntents.ACTION_SUBINFO_CONTENT_CHANGE);

        /// M: SMB dock state change
        filter.addAction(ACTION_SMARTBOOK_PLUG);

        /// M: SIM lock unlock request after dismiss
        filter.addAction(TelephonyIntents.ACTION_UNLOCK_SIM_LOCK);

        /// M: [ALPS01761127] Added for power-off modem feature + airplane mode
        filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);

        context.registerReceiver(mBroadcastReceiver, filter);

        final IntentFilter bootCompleteFilter = new IntentFilter();
        bootCompleteFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        bootCompleteFilter.addAction(Intent.ACTION_BOOT_COMPLETED);
        context.registerReceiver(mBroadcastReceiver, bootCompleteFilter);

        final IntentFilter allUserFilter = new IntentFilter();
        allUserFilter.addAction(Intent.ACTION_USER_INFO_CHANGED);
        allUserFilter.addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED);
        allUserFilter.addAction(ACTION_FACE_UNLOCK_STARTED);
        allUserFilter.addAction(ACTION_FACE_UNLOCK_STOPPED);
        allUserFilter.addAction(DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED);
        context.registerReceiverAsUser(mBroadcastAllReceiver, UserHandle.ALL, allUserFilter,
                null, null);

        try {
            ActivityManagerNative.getDefault().registerUserSwitchObserver(
                    new IUserSwitchObserver.Stub() {
                        @Override
                        public void onUserSwitching(int newUserId, IRemoteCallback reply) {
                            mHandler.sendMessage(mHandler.obtainMessage(MSG_USER_SWITCHING,
                                    newUserId, 0, reply));
                            mSwitchingUser = true;
                        }
                        @Override
                        public void onUserSwitchComplete(int newUserId) throws RemoteException {
                            mHandler.sendMessage(mHandler.obtainMessage(MSG_USER_SWITCH_COMPLETE,
                                    newUserId, 0));
                            mSwitchingUser = false;
                        }
                    });
        } catch (RemoteException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        TrustManager trustManager = (TrustManager) context.getSystemService(Context.TRUST_SERVICE);
        trustManager.registerTrustListener(this);

        FingerprintManager fpm;
        fpm = (FingerprintManager) context.getSystemService(Context.FINGERPRINT_SERVICE);
        fpm.startListening(mFingerprintManagerReceiver);
    }

    private boolean isDeviceProvisionedInSettingsDb() {
        return Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.DEVICE_PROVISIONED, 0) != 0;
    }

    private void watchForDeviceProvisioning() {
        mDeviceProvisionedObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                super.onChange(selfChange);
                mDeviceProvisioned = isDeviceProvisionedInSettingsDb();
                if (mDeviceProvisioned) {
                    mHandler.sendEmptyMessage(MSG_DEVICE_PROVISIONED);
                }
                if (DEBUG) Log.d(TAG, "DEVICE_PROVISIONED state = " + mDeviceProvisioned);
            }
        };

        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.DEVICE_PROVISIONED),
                false, mDeviceProvisionedObserver);

        // prevent a race condition between where we check the flag and where we register the
        // observer by grabbing the value once again...
        boolean provisioned = isDeviceProvisionedInSettingsDb();
        if (provisioned != mDeviceProvisioned) {
            mDeviceProvisioned = provisioned;
            if (mDeviceProvisioned) {
                mHandler.sendEmptyMessage(MSG_DEVICE_PROVISIONED);
            }
        }
    }

    /**
     * Handle {@link #MSG_DPM_STATE_CHANGED}
     */
    protected void handleDevicePolicyManagerStateChanged() {
        for (int i = mCallbacks.size() - 1; i >= 0; i--) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onDevicePolicyManagerStateChanged();
            }
        }
    }

    /**
     * Handle {@link #MSG_USER_SWITCHING}
     */
    protected void handleUserSwitching(int userId, IRemoteCallback reply) {
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onUserSwitching(userId);
            }
        }
        try {
            reply.sendResult(null);
        } catch (RemoteException e) {
        }
    }

    /**
     * Handle {@link #MSG_USER_SWITCH_COMPLETE}
     */
    protected void handleUserSwitchComplete(int userId) {
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onUserSwitchComplete(userId);
            }
        }
    }

    /**
     * This is exposed since {@link Intent#ACTION_BOOT_COMPLETED} is not sticky. If
     * keyguard crashes sometime after boot, then it will never receive this
     * broadcast and hence not handle the event. This method is ultimately called by
     * PhoneWindowManager in this case.
     */
    public void dispatchBootCompleted() {
        mHandler.sendEmptyMessage(MSG_BOOT_COMPLETED);
    }

    /**
     * Handle {@link #MSG_BOOT_COMPLETED}
     */
    protected void handleBootCompleted() {
        if (mBootCompleted) return;
        mBootCompleted = true;
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onBootCompleted();
            }
        }
    }

    /**
     * We need to store this state in the KeyguardUpdateMonitor since this class will not be
     * destroyed.
     */
    public boolean hasBootCompleted() {
        return mBootCompleted;
    }

    /**
     * Handle {@link #MSG_USER_REMOVED}
     */
    protected void handleUserRemoved(int userId) {
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onUserRemoved(userId);
            }
        }
    }

    /**
     * Handle {@link #MSG_DEVICE_PROVISIONED}
     */
    protected void handleDeviceProvisioned() {
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onDeviceProvisioned();
            }
        }
        if (mDeviceProvisionedObserver != null) {
            // We don't need the observer anymore...
            mContext.getContentResolver().unregisterContentObserver(mDeviceProvisionedObserver);
            mDeviceProvisionedObserver = null;
        }
    }

    /**
     * Handle {@link #MSG_PHONE_STATE_CHANGED}
     *  Set mPhoneState as OFFHOOK if one subscription is in OFFHOOK state.
     *  Otherwise, set as RINGING state if one subscription is in RINGING state.
     *  Set as IDLE if all subscriptions are in IDLE state.
     */
    protected void handlePhoneStateChanged() {
        if (DEBUG) Log.d(TAG, "handlePhoneStateChanged");
        mPhoneState = TelephonyManager.CALL_STATE_IDLE;
        for (int i = 0; i < getNumOfSubscription(); i++) {
            int callState = TelephonyManager.getDefault().getCallState(mStandbySubId[i]);
            if (callState == TelephonyManager.CALL_STATE_OFFHOOK) {
                mPhoneState = callState;
            } else if (callState == TelephonyManager.CALL_STATE_RINGING
                    && mPhoneState == TelephonyManager.CALL_STATE_IDLE) {
                mPhoneState = callState;
            }
        }

        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onPhoneStateChanged(mPhoneState);
            }
        }
    }

    /**
     * Handle {@link #MSG_RINGER_MODE_CHANGED}
     */
    protected void handleRingerModeChange(int mode) {
        if (DEBUG) Log.d(TAG, "handleRingerModeChange(" + mode + ")");
        mRingMode = mode;
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onRingerModeChanged(mode);
            }
        }
    }

    /**
     * Handle {@link #MSG_TIME_UPDATE}
     */
    private void handleTimeUpdate() {
        if (DEBUG) Log.d(TAG, "handleTimeUpdate");
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onTimeChanged();
            }
        }
    }

    /**
     * Handle {@link #MSG_BATTERY_UPDATE}
     */
    private void handleBatteryUpdate(BatteryStatus status) {
        final int idx = status.index;
        final boolean batteryUpdateInteresting = isBatteryUpdateInteresting(mBatteryStatus[idx], status);
        if (DEBUG) Log.d(TAG, "handleBatteryUpdate index=" + idx + " updateInteresting=" + batteryUpdateInteresting);
        mBatteryStatus[idx] = status;
        if (batteryUpdateInteresting) {
            for (int i = 0; i < mCallbacks.size(); i++) {
                KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
                if (cb != null) {
                    cb.onRefreshBatteryInfo(status);
                }
            }
        }
    }

    /**
     * Handle {@link #MSG_CARRIER_INFO_UPDATE}
     */
    private void handleCarrierInfoUpdate(long subId) {
        if (DEBUG) Log.d(TAG, "handleCarrierInfoUpdate: plmn = " + mTelephonyPlmn
            + ", spn = " + mTelephonySpn + ", subId = " + subId);

        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onRefreshCarrierInfo(subId, mTelephonyPlmn.get(subId), mTelephonySpn.get(subId));
            }
        }
    }

    /**
     * Handle {@link #MSG_SIM_STATE_CHANGE}
     */
    private void handleSimStateChange(SimArgs simArgs) {
        final IccCardConstants.State state = simArgs.simState;

        if (DEBUG) {
            Log.d(TAG, "handleSimStateChange: intentValue = " + simArgs + " "
                    + "state resolved to " + state.toString() + " subId=" + simArgs.subId);
        }

        IccCardConstants.State tempState;
        tempState = mSimStateOfSub.get(simArgs.subId);
        if (state != IccCardConstants.State.UNKNOWN) {
            Log.d(TAG, "handleSimStateChange: Force to update status.") ;

            ///M: fix ALPS01806988.
            /// Because phone process died, sim state NOT_READY is sent again,
            /// subId also becomes dummyId again.
            /// So we need to notify all related modules that sub id is changed.
            boolean subIdHasUpdated = false ;
            long oriSubId = SubscriptionManager.INVALID_SUB_ID;
            long newSubId = SubscriptionManager.INVALID_SUB_ID;

            mSimStateOfSub.put(simArgs.subId, state);
            mSlotIdOfSub.put(simArgs.subId, simArgs.slotId);
            int phoneId = (state == IccCardConstants.State.ABSENT) ?
                    getSubIndexUsingSubId(simArgs.subId) :
                    SubscriptionManager.getPhoneId(simArgs.subId);
            if (DEBUG) Log.d(TAG, "handleSimStateChange phoneId = " + phoneId) ;

            if (phoneId >= 0 && phoneId < getNumOfSubscription()) {
                if (state == IccCardConstants.State.ABSENT) {
                    long dummySubId = -1 - phoneId ;
                    if (DEBUG) Log.d(TAG, "handleSimStateChange: update subId of mStandbySubId["
                            + phoneId + "] from " + mStandbySubId[phoneId] + " to " + dummySubId);

                    oriSubId = mStandbySubId[phoneId];
                    newSubId = dummySubId ;
                    subIdHasUpdated = true ;

                    mStandbySubId[phoneId] = dummySubId;
                    mSimStateOfSub.put(dummySubId, state);
                    mSlotIdOfSub.put(dummySubId, simArgs.slotId);
                } else {
                    if (mStandbySubId[phoneId] != simArgs.subId) {
                        oriSubId = mStandbySubId[phoneId] ;
                        newSubId = simArgs.subId;
                        subIdHasUpdated = true ;

                        mStandbySubId[phoneId] = simArgs.subId;
                    }
                }

                if (subIdHasUpdated) {
                    Log.d(TAG, "handleSimStateChange: subIdHasUpdated is true. oriSubId = " +
                               oriSubId + ", newSubId = " + newSubId) ;
                    final int count = mCallbacks.size();
                    for (int i = 0; i < count; i++) {
                        KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
                        if (cb != null) {
                            cb.onSubIdUpdated(oriSubId, newSubId);
                        }
                    }
                }
            } else {
                Log.d(TAG, "handleSimStateChange: get inavlid phoneId=" + phoneId + " subId=" + simArgs.subId);
                return;
            }

            for (int i = 0; i < mCallbacks.size(); i++) {
                KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
                if (cb != null) {
                    cb.onSimStateChangedUsingSubId(mStandbySubId[phoneId], state);
                }
            }
        }
    }

    /**
     * Handle {@link #MSG_CLOCK_VISIBILITY_CHANGED}
     */
    private void handleClockVisibilityChanged() {
        if (DEBUG) Log.d(TAG, "handleClockVisibilityChanged()");
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onClockVisibilityChanged();
            }
        }
    }

    /**
     * Handle {@link #MSG_KEYGUARD_VISIBILITY_CHANGED}
     */
    private void handleKeyguardVisibilityChanged(int showing) {
        if (DEBUG) Log.d(TAG, "handleKeyguardVisibilityChanged(" + showing + ")");
        boolean isShowing = (showing == 1);
        mKeyguardIsVisible = isShowing;
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onKeyguardVisibilityChangedRaw(isShowing);
            }
        }
    }

    /**
     * Handle {@link #MSG_KEYGUARD_BOUNCER_CHANGED}
     * @see #sendKeyguardBouncerChanged(boolean)
     */
    private void handleKeyguardBouncerChanged(int bouncer) {
        if (DEBUG) Log.d(TAG, "handleKeyguardBouncerChanged(" + bouncer + ")");
        boolean isBouncer = (bouncer == 1);
        mBouncer = isBouncer;
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onKeyguardBouncerChanged(isBouncer);
            }
        }
    }

    /**
     * Handle {@link #MSG_REPORT_EMERGENCY_CALL_ACTION}
     */
    private void handleReportEmergencyCallAction() {
        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onEmergencyCallAction();
            }
        }
    }

    public boolean isKeyguardVisible() {
        return mKeyguardIsVisible;
    }

    /**
     * @return if the keyguard is currently in bouncer mode.
     */
    public boolean isKeyguardBouncer() {
        return mBouncer;
    }

    public boolean isSwitchingUser() {
        return mSwitchingUser;
    }

    private static boolean isBatteryUpdateInteresting(BatteryStatus old, BatteryStatus current) {
        final boolean nowPluggedIn = current.isPluggedIn();
        final boolean wasPluggedIn = old.isPluggedIn();
        final boolean stateChangedWhilePluggedIn =
            wasPluggedIn == true && nowPluggedIn == true
            && (old.status != current.status);

        // change in plug state is always interesting
        if (wasPluggedIn != nowPluggedIn || stateChangedWhilePluggedIn) {
            return true;
        }

        // change in battery level while plugged in
        /// M: We remove "nowPluggedIn" condition here.
        /// To fix the issue that if HW give up a low battery level(below threshold)
        /// and then a high battery level(above threshold) while device is not pluggin,
        /// then Keyguard may never be able be show
        /// charging text on screen when pluggin
        if (old.level != current.level) {
            return true;
        }

        // change where battery needs charging
        if (!nowPluggedIn && current.isBatteryLow() && current.level != old.level) {
            return true;
        }
        return false;
    }

    /**
     * @param intent The intent with action {@link TelephonyIntents#SPN_STRINGS_UPDATED_ACTION}
     * @return The string to use for the plmn, or null if it should not be shown.
     */
    private CharSequence getTelephonyPlmnFrom(Intent intent) {
        if (intent.getBooleanExtra(TelephonyIntents.EXTRA_SHOW_PLMN, false)) {
            final String plmn = intent.getStringExtra(TelephonyIntents.EXTRA_PLMN);
            return (plmn != null) ? plmn : getDefaultPlmn();
        }
        return null;
    }

    /**
     * @return The default plmn (no service)
     */
    public CharSequence getDefaultPlmn() {
        return mContext.getResources().getText(R.string.keyguard_carrier_default);
    }

    /**
     * @param intent The intent with action {@link Telephony.Intents#SPN_STRINGS_UPDATED_ACTION}
     * @return The string to use for the plmn, or null if it should not be shown.
     */
    private CharSequence getTelephonySpnFrom(Intent intent) {
        if (intent.getBooleanExtra(TelephonyIntents.EXTRA_SHOW_SPN, false)) {
            final String spn = intent.getStringExtra(TelephonyIntents.EXTRA_SPN);
            if (spn != null) {
                return spn;
            }
        }
        return null;
    }

    /**
     * Remove the given observer's callback.
     *
     * @param callback The callback to remove
     */
    public void removeCallback(KeyguardUpdateMonitorCallback callback) {
        if (DEBUG) Log.v(TAG, "*** unregister callback for " + callback);
        for (int i = mCallbacks.size() - 1; i >= 0; i--) {
            if (mCallbacks.get(i).get() == callback) {
                mCallbacks.remove(i);
            }
        }
    }

    /**
     * Register to receive notifications about general keyguard information
     * (see {@link InfoCallback}.
     * @param callback The callback to register
     */
    public void registerCallback(KeyguardUpdateMonitorCallback callback) {
        if (DEBUG) Log.v(TAG, "*** register callback for " + callback);
        // Prevent adding duplicate callbacks
        for (int i = 0; i < mCallbacks.size(); i++) {
            if (mCallbacks.get(i).get() == callback) {
                if (DEBUG) Log.e(TAG, "Object tried to add another callback",
                        new Exception("Called by"));
                return;
            }
        }
        mCallbacks.add(new WeakReference<KeyguardUpdateMonitorCallback>(callback));
        removeCallback(null); // remove unused references
        sendUpdates(callback);

        ///M: in order to improve performance, add a flag to fliter redundant visibility change callbacks
        mNewClientRegUpdateMonitor = true;
    }

    private void sendUpdates(KeyguardUpdateMonitorCallback callback) {
        // Notify listener of the current state
        for (int i = 0; i < KEYGUARD_BATTERY_NUMBER; i++) {
            callback.onRefreshBatteryInfo(mBatteryStatus[i]);
        }
        callback.onTimeChanged();
        callback.onRingerModeChanged(mRingMode);
        callback.onPhoneStateChanged(mPhoneState);
        callback.onClockVisibilityChanged();
        for (long subId: mStandbySubId) {
            callback.onRefreshCarrierInfo(subId, mTelephonyPlmn.get(subId), mTelephonySpn.get(subId));
            callback.onSimStateChangedUsingSubId(subId, mSimStateOfSub.get(subId));
        }
    }

    public void sendKeyguardVisibilityChanged(boolean showing) {
        ///M: in order to improve performance we skip callbacks if no new client registered
        if (mNewClientRegUpdateMonitor || showing != mShowing) {
            if (DEBUG) Log.d(TAG, "sendKeyguardVisibilityChanged(" + showing + ")");
            Message message = mHandler.obtainMessage(MSG_KEYGUARD_VISIBILITY_CHANGED);
            message.arg1 = showing ? 1 : 0;
            message.sendToTarget();
            mNewClientRegUpdateMonitor = false;
            mShowing = showing;
        }
    }

    /**
     * @see #handleKeyguardBouncerChanged(int)
     */
    public void sendKeyguardBouncerChanged(boolean showingBouncer) {
        if (DEBUG) Log.d(TAG, "sendKeyguardBouncerChanged(" + showingBouncer + ")");
        Message message = mHandler.obtainMessage(MSG_KEYGUARD_BOUNCER_CHANGED);
        message.arg1 = showingBouncer ? 1 : 0;
        message.sendToTarget();
    }

    public void reportClockVisible(boolean visible) {
        mClockVisible = visible;
        mHandler.obtainMessage(MSG_CLOCK_VISIBILITY_CHANGED).sendToTarget();
    }

    public IccCardConstants.State getSimStateOfSub(long subId) {
        return mSimStateOfSub.get(subId);
    }

    /**
     * Report that the user successfully entered the SIM PIN or PUK/SIM PIN so we
     * have the information earlier than waiting for the intent
     * broadcast from the telephony code.
     *
     * NOTE: Because handleSimStateChange() invokes callbacks immediately without going
     * through mHandler, this *must* be called from the UI thread.
     */
    public void reportSimUnlocked(long subId) {
        int slotIndex = getSlotIdUsingSubId(subId);
        handleSimStateChange(new SimArgs(IccCardConstants.State.READY, slotIndex, subId));
    }

    /**
     * Report that the emergency call button has been pressed and the emergency dialer is
     * about to be displayed.
     *
     * @param bypassHandler runs immediately.
     *
     * NOTE: Must be called from UI thread if bypassHandler == true.
     */
    public void reportEmergencyCallAction(boolean bypassHandler) {
        if (!bypassHandler) {
            mHandler.obtainMessage(MSG_REPORT_EMERGENCY_CALL_ACTION).sendToTarget();
        } else {
            handleReportEmergencyCallAction();
        }
    }

    public CharSequence getTelephonyPlmn(long subId) {
        return mTelephonyPlmn.get(subId);
    }

    public CharSequence getTelephonySpn(long subId) {
        return mTelephonySpn.get(subId);
    }

    /**
     * @return Whether the device is provisioned (whether they have gone through
     *   the setup wizard)
     */
    public boolean isDeviceProvisioned() {
        return mDeviceProvisioned;
    }

    public int getFailedUnlockAttempts() {
        return mFailedAttempts;
    }

    public void clearFailedUnlockAttempts() {
        mFailedAttempts = 0;
        mFailedBiometricUnlockAttempts = 0;
    }

    public void clearFingerprintRecognized() {
        mUserFingerprintRecognized.clear();
    }

    public void reportFailedUnlockAttempt() {
        mFailedAttempts++;
    }

    public boolean isClockVisible() {
        return mClockVisible;
    }

    public int getPhoneState() {
        return mPhoneState;
    }

    public void reportFailedBiometricUnlockAttempt() {
        mFailedBiometricUnlockAttempts++;
    }

    public boolean getMaxBiometricUnlockAttemptsReached() {
        return mFailedBiometricUnlockAttempts >= FAILED_BIOMETRIC_UNLOCK_ATTEMPTS_BEFORE_BACKUP;
    }

    public boolean isAlternateUnlockEnabled() {
        return mAlternateUnlockEnabled;
    }

    public void setAlternateUnlockEnabled(boolean enabled) {
        Log.d(TAG, "setAlternateUnlockEnabled(enabled = " + enabled + ")") ;

        if (isDocktoDesk() && enabled) {
            ///M: Ignore alternate unlock enable request when in docked state
        } else {
            mAlternateUnlockEnabled = enabled;
        }
    }

    public boolean isSimLocked() {
        boolean bSimLocked = false;
        for (long subId: mStandbySubId) {
            if (isSimLocked(mSimStateOfSub.get(subId))) {
                bSimLocked = true;
                break;
            }
        }
        return bSimLocked;
    }

    public static boolean isSimLocked(IccCardConstants.State state) {
        return state == IccCardConstants.State.PIN_REQUIRED
        || state == IccCardConstants.State.PUK_REQUIRED
        || (state == IccCardConstants.State.NETWORK_LOCKED &&
            KeyguardUtils.isMediatekSimMeLockSupport())
        || state == IccCardConstants.State.PERM_DISABLED;
    }

    public boolean isSimPinSecure() {
        boolean isSecure = false;
        for (long subId: mStandbySubId) {
            if (isSimPinSecure(subId)) {
                isSecure = true;
                break;
            }
        }
        return isSecure;
    }

    /**
       * Check if the subscription is in SIM pin lock state and wait user to unlock.
       * @param subId the sub id of subscription.
       * @return Returns true if the subscription is in SIM pin lock state and not yet dismissed.
       **/
    public boolean isSimPinSecure(long subId) {
        IccCardConstants.State state = mSimStateOfSub.get(subId);
        final IccCardConstants.State simState = state;
        return ((simState == IccCardConstants.State.PIN_REQUIRED
                || simState == IccCardConstants.State.PUK_REQUIRED
                || (simState == IccCardConstants.State.NETWORK_LOCKED) &&
                    KeyguardUtils.isMediatekSimMeLockSupport())
                && !getPinPukMeDismissFlagOfSub(subId));
    }

    public DisplayClientState getCachedDisplayClientState() {
        return mDisplayClientState;
    }

    // TODO: use these callbacks elsewhere in place of the existing notifyScreen*()
    // (KeyguardViewMediator, KeyguardHostView)
    public void dispatchScreenTurnedOn() {
        synchronized (this) {
            mScreenOn = true;
        }
        mHandler.sendEmptyMessage(MSG_SCREEN_TURNED_ON);
    }

    public void dispatchScreenTurndOff(int why) {
        synchronized(this) {
            mScreenOn = false;
        }
        mHandler.sendMessage(mHandler.obtainMessage(MSG_SCREEN_TURNED_OFF, why, 0));
    }

    public boolean isScreenOn() {
        return mScreenOn;
    }

    ////////MTK telephony
    public long getSimPinLockSubId() {
        long currentSimPinSubId = SubscriptionManager.INVALID_SUB_ID;
        for (long subId: mStandbySubId) {
            if (DEBUG) Log.d(TAG, "getSimPinLockSubId, subId=" + subId
                    + " mSimStateOfSub.get(subId)=" + mSimStateOfSub.get(subId));
            if (mSimStateOfSub.get(subId) == IccCardConstants.State.PIN_REQUIRED
                && !getPinPukMeDismissFlagOfSub(subId)) {
                currentSimPinSubId = subId;
                break;
            }
        }
        return currentSimPinSubId;
    }

    public long getSimPukLockSubId() {
        long currentSimPukSubId = SubscriptionManager.INVALID_SUB_ID;
        for (long subId: mStandbySubId) {
            if (DEBUG) Log.d(TAG, "getSimPukLockSubId, subId=" + subId
                    + " mSimStateOfSub.get(subId)=" + mSimStateOfSub.get(subId));
            if (mSimStateOfSub.get(subId) == IccCardConstants.State.PUK_REQUIRED
                && !getPinPukMeDismissFlagOfSub(subId)
                && getRetryPukCountOfSub(subId) != 0) {
                currentSimPukSubId = subId;
                break;
            }
        }
        return currentSimPukSubId;
    }

    /// default use phone count as subscription count, currently support to 4 subscriptions
    public int getNumOfSubscription() {
        if (mSubCount == 0) {
            mSubCount = TelephonyManager.getDefault().getPhoneCount(); //hw can support in theory
            mSubCount = ((mSubCount > 4) ? 4 : mSubCount); //4  // 4 --> in fact our ui layout max support 4. maybe update in future
        }
        return mSubCount;
    }

    public boolean isValidSlotId(int slotId) {
        return (0 <= slotId);
    }

    public int getSlotIdUsingSubId(long subId) {
        if (mSlotIdOfSub.get(subId) != null) {
            return mSlotIdOfSub.get(subId).intValue();
        } else {
            return -1;
        }
    }

    public long getSubIdUsingSubIndex(int index) {
        long subId = SubscriptionManager.INVALID_SUB_ID;
        if (index >= 0 && index < mSubCount) {
            subId = mStandbySubId[index];
        } else {
            if (DEBUG) {
                Log.w(TAG, "getSubIdUsingSubIndex return INVALID_SUB_ID, index=" + index);
            }
        }
        return subId;
    }

    public int getSubIndexUsingSubId(long subId) {
        int subIndex = -1;
        for (int i = 0; i < getNumOfSubscription(); i++) {
            if (subId == mStandbySubId[i]) {
                subIndex = i;
                break;
            }
        }
        if (subIndex < 0) {
            if (DEBUG) {
                Log.w(TAG, "getSubIndexUsingSubId return -1, subId=" + subId);
            }
        }
        return subIndex;
    }

    /********************************************************
     ** Mediatek add begin
     ********************************************************/

    /// M: init members
    private void initMembers() {

        if (DEBUG) {
            Log.d(TAG, "NumOfSubscription=" + getNumOfSubscription());
        }
        // Take a guess at initial SIM state, battery status and PLMN until we get an update
        mStandbySubId = new long[getNumOfSubscription()];

        // Take a guess at initial SIM state, battery status and PLMN until we get an update
        for (int i = 0; i < getNumOfSubscription(); i++) {
            long dummySubId = -1 - i;
            mStandbySubId[i] = dummySubId;
            mSlotIdOfSub.put(dummySubId, i);
            mSimStateOfSub.put(dummySubId, IccCardConstants.State.UNKNOWN);
            mTelephonyPlmn.put(dummySubId, getDefaultPlmn());
            mTelephonyCsgId.put(dummySubId, "") ;
            mTelephonyHnbName.put(dummySubId, "");

            //ME lock Related
            mSimMeCategory.put(dummySubId, 0) ;
            mSimMeLeftRetryCount.put(dummySubId, 5) ;
        }
    }

    /// 1. Smartbootk
    /// 2. Dock
    /// 3. Incoming Indicator for Keyguard Rotation
    /// 4. Misc info
    /// 5. Telephony

    /// ---- Smartbook Info begins ----
    /// M:[SmartBook]Add SmartBook intent @{
    //Sticky broadcast of the current SMARTBOOK plug state.
    public final static String ACTION_SMARTBOOK_PLUG = "android.intent.action.SMARTBOOK_PLUG";

    //Extra in {@link #ACTION_SMARTBOOK_PLUG} indicating the state: true if
    //plug in to SMARTBOOK, false if not.
    public final static String EXTRA_SMARTBOOK_PLUG_STATE = "state";
    /// ---- Smartbook Info ends ----

    /// ---- Dock Info begins ----
    /// M: dock status update message
    private static final int MSG_DOCK_STATUS_UPDATE = 1014;
    /// M: support multiple batteries
    private BatteryStatus mBatteryStatus[];
    private boolean mDocktoDesk = false;

    private void handleDockStatusUpdate(int dockState) {
        for (int i = 0; i < mCallbacks.size(); i++) {
           KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
           if (cb != null) {
               cb.onDockStatusUpdate(dockState);
           }
        }
    }

    ///M: get is dock status
    public boolean isDocktoDesk() {
        return mDocktoDesk;
    }
    /// ---- Dock Info ends ----

    /// ---- Telephony Info begins ----
    /**
     ** M: Used to verify the lock type
     */
    public enum SimLockType {
        SIM_LOCK_PIN,
        SIM_LOCK_PUK,
        SIM_LOCK_ME
    }

    ///M: in order to improve performance, add a flag to fliter redundant visibility change callbacks
    private boolean mNewClientRegUpdateMonitor = false;
    private boolean mShowing = true;

    /// M: SIM ME lock related info
    private HashMap<Long, Integer> mSimMeCategory = new HashMap<Long, Integer>(); //current unlocking category of each SIM card.
    private HashMap<Long, Integer> mSimMeLeftRetryCount = new HashMap<Long, Integer>(); //current left retry count of current ME lock category.
    private static final String QUERY_SIMME_LOCK_RESULT = "com.mediatek.phone.QUERY_SIMME_LOCK_RESULT";
    private static final String SIMME_LOCK_LEFT_COUNT = "com.mediatek.phone.SIMME_LOCK_LEFT_COUNT";

    ///M: Dismiss flags
    private static final int PIN_PUK_ME_RESET = 0x0000;
    private static final int PIN_PUK_ME_DISMISSED = 0x0001;


    /// M: Flag used to indicate weather sim1 or sim2 card's pin/puk is dismissed by user.
    private int mPinPukMeDismissFlag = PIN_PUK_ME_RESET;

    private HashMap<Long, CharSequence> mTelephonyHnbName = new HashMap<Long, CharSequence>();
    private HashMap<Long, CharSequence> mTelephonyCsgId = new HashMap<Long, CharSequence>();

    /**
     ** M: Used to set specified sim card's pin or puk dismiss flag
     *
     * @param subId the id of the subscription to set dismiss flag
     * @param dismiss true to dismiss this flag, false to clear
     */
    public void setPinPukMeDismissFlagOfSub(long subId, boolean dismiss) {
        int subIndex = getSubIndexUsingSubId(subId);

        Log.d(TAG, "setPinPukMeDismissFlagOfSub() - subIndex = " + subIndex) ;
        
        if (subIndex < 0) {
            return;
        }

        //Log.i(TAG, "setPINDismiss,  idx="+subIndex+" subId = " + subId
        //        + ", dismiss=" + dismiss + ", mPinPukMeDismissFlag=" + mPinPukMeDismissFlag);

        int flag2Dismiss = PIN_PUK_ME_RESET;

        flag2Dismiss = PIN_PUK_ME_DISMISSED << subIndex;

        if (dismiss) {
            mPinPukMeDismissFlag |= flag2Dismiss;
        } else {
            mPinPukMeDismissFlag &= ~flag2Dismiss;
        }
    }

    /**
     ** M: Used to get specified sim card's pin or puk dismiss flag
     *
     * @param subId the id of the subscrpition to set dismiss flag
     * @return Returns false if dismiss flag is set.
     */
    public boolean getPinPukMeDismissFlagOfSub(long subId) {
        int subIndex = getSubIndexUsingSubId(subId) ;
        int flag2Check = PIN_PUK_ME_RESET;
        boolean result = false;

        flag2Check = PIN_PUK_ME_DISMISSED << subIndex;
        result = (mPinPukMeDismissFlag & flag2Check) == flag2Check ? true : false;
        //Log.d(TAG, "getPINDismissFlag, idx=" + subIndex + ", subId = " + subId
        //        + ", mPinPukMeDismissFlag=" + mPinPukMeDismissFlag+ " result=" + result);

        return result;
    }

    /**
     *  M:Get the remaining puk count of the sim card with the simId.
     * @param subId the subscription ID
     * @return Return  the PUK retry count
     */
    public int getRetryPukCountOfSub(final long subId) {
        int GET_SIM_RETRY_EMPTY = -1; ///M: The default value of the remaining puk count
        int subIndex = getSubIndexUsingSubId(subId) ;

        if (subIndex == 3) {
            return SystemProperties.getInt("gsm.sim.retry.puk1.4", GET_SIM_RETRY_EMPTY);
        } else if (subIndex == 2) {
            return SystemProperties.getInt("gsm.sim.retry.puk1.3", GET_SIM_RETRY_EMPTY);
        } else if (subIndex == 1) {
            return SystemProperties.getInt("gsm.sim.retry.puk1.2", GET_SIM_RETRY_EMPTY);
        } else {
            return SystemProperties.getInt("gsm.sim.retry.puk1", GET_SIM_RETRY_EMPTY);
        }
    }

    /**
     * M: Start a thread to query SIM ME status.
     */
    private class simMeStatusQueryThread extends Thread {
        SimArgs simArgs;

        simMeStatusQueryThread(SimArgs simArgs) {
            this.simArgs = simArgs;
        }

        @Override
        public void run() {
            try {
                mSimMeCategory.put(simArgs.subId, simArgs.simMECategory);
                Log.d(TAG, "queryNetworkLock, " + "subId =" + simArgs.subId + ", simMECategory ="
                        + simArgs.simMECategory);

                if (simArgs.simMECategory < 0 || simArgs.simMECategory > 5) {
                    return;
                }

                Bundle bundle = ITelephonyEx.Stub.asInterface(ServiceManager.getService("phoneEx"))
                        .queryNetworkLock(simArgs.subId, simArgs.simMECategory);
                boolean query_result = bundle.getBoolean(QUERY_SIMME_LOCK_RESULT, false);

                Log.d(TAG, "queryNetworkLock, " + "query_result =" + query_result);

                if (query_result) {
                    mSimMeLeftRetryCount.put(simArgs.subId, bundle.getInt(SIMME_LOCK_LEFT_COUNT, 5));
                } else {
                    Log.e(TAG, "queryIccNetworkLock result fail");
                }
                mHandler.sendMessage(mHandler.obtainMessage(MSG_SIM_STATE_CHANGE, simArgs));
            } catch (Exception e) {
                Log.e(TAG, "queryIccNetworkLock got exception: " + e.getMessage());
            }
        }
    }

    public int getSimMeCategoryOfSub(long subId) {
        return mSimMeCategory.get(subId);
    }

    public int getSimMeLeftRetryCountOfSub(long subId) {
        return mSimMeLeftRetryCount.get(subId);
    }
    public void minusSimMeLeftRetryCountOfSub(long subId) {
        int simMeRetryCount = mSimMeLeftRetryCount.get(subId) ;
        if (simMeRetryCount > 0) {
            mSimMeLeftRetryCount.put(subId, simMeRetryCount - 1);
        }
    }

    /** M: LTE CSG feature
     * @param intent The intent with action {@link Telephony.Intents#SPN_STRINGS_UPDATED_ACTION}
     * @return The string to use for the HNB name, or null if it should not be shown.
     */
    private CharSequence getTelephonyHnbNameFrom(Intent intent) {
        final String hnbName = intent.getStringExtra(TelephonyIntents.EXTRA_HNB_NAME);
        return hnbName;
    }

    /** M: LTE CSG feature
     * @param intent The intent with action {@link Telephony.Intents#SPN_STRINGS_UPDATED_ACTION}
     * @return The string to use for the CSG id, or null if it should not be shown.
     */
    private CharSequence getTelephonyCsgIdFrom(Intent intent) {
        final String csgId = intent.getStringExtra(TelephonyIntents.EXTRA_CSG_ID);
        return csgId;
    }

    /// M: CSG support
    public CharSequence getTelephonyHnbNameOfSubId(long subId) {
        return mTelephonyHnbName.get(subId);
    }

    /// M: CSG support
    public CharSequence getTelephonyCsgIdOfSubId(long subId) {
        return mTelephonyCsgId.get(subId);
    }

    /**
     * Handle {@link #MSG_AIRPLANE_MODE_UPDATE}
     */
    private static final int MSG_AIRPLANE_MODE_UPDATE = 1015;
    private void handleAirPlaneModeUpdate(boolean airPlaneModeEnabled) {
        ///M: [ALPS01761127]
        ///   After AirPlane on, the sim state will keep as "PIN_REQUIRED".
        ///   After AirPlane off, if PowerOffModem is true,
        ///   Modem will send "NOT_READY" and "PIN_REQUIRED" after .
        ///   So we do not need to send PIN_REQUIRED here.
        if (airPlaneModeEnabled == false && !KeyguardUtils.isFlightModePowerOffMd()) {
            if (DEBUG) {
                Log.d(TAG, "Force to send sim pin/puk/me lock again if needed.");
            }
            for (int i = 0; i < getNumOfSubscription(); i++) {
                long subId = mStandbySubId[i] ;
                if (DEBUG) {
                    Log.d(TAG, "subId = " + subId + " state=" + mSimStateOfSub.get(subId));
                }
                switch (mSimStateOfSub.get(subId)) {
                    case PIN_REQUIRED:
                    case PUK_REQUIRED:
                    case NETWORK_LOCKED:
                        /// 1. keep the original state
                        IccCardConstants.State oriState = mSimStateOfSub.get(subId);
                        /// 2. reset state of subid
                        mSimStateOfSub.put(subId, IccCardConstants.State.UNKNOWN);
                        /// 3. create the SimArgs
                        int meCategory = 0 ;
                        if (mSimMeCategory.get(subId) != null) {
                            meCategory = mSimMeCategory.get(subId) ;
                        }
                        SimArgs simArgs = new SimArgs(oriState,
                                                mSlotIdOfSub.get(subId),
                                                subId,
                                                meCategory);
                        if (DEBUG) {
                            Log.v(TAG, "SimArgs state=" + simArgs.simState
                                + ", slotId=" + simArgs.slotId + ", subId=" + simArgs.subId
                                + ", simArgs.simMECategory = " + simArgs.simMECategory);
                        }
                        proceedToHandleSimStateChanged(simArgs) ;

                        break ;
                    default:
                        break;
                } //end switch
            } //end for
        } else if (airPlaneModeEnabled == true) {
            ///M: fix ALPS01831621
            ///   we supress all PIN/PUK/ME locks when receiving Flight-Mode turned on.
            Log.d(TAG, "Air mode is on, supress all SIM PIN/PUK/ME Lock views.") ;
            for (int i = 0; i < getNumOfSubscription(); i++) {
                long subId = getSubIdUsingSubIndex(i);
                setPinPukMeDismissFlagOfSub(subId, true) ;
            }
        }

        for (int i = 0; i < mCallbacks.size(); i++) {
            KeyguardUpdateMonitorCallback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onAirPlaneModeChanged(airPlaneModeEnabled);
            }
        }
    }

    /// ---- Telephony Info ends ----

}
