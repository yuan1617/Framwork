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

package com.android.internal.telephony.dataconnection;

import android.app.PendingIntent;
import android.content.Context;
import android.net.NetworkConfig;
import android.telephony.Rlog;

import com.android.internal.R;
import com.android.internal.telephony.DctConstants;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

//VoLTE
import com.mediatek.internal.telephony.DefaultBearerConfig;

/**
 * Maintain the Apn context
 */
public class ApnContext {

    public final String LOG_TAG;

    protected static final boolean DBG = false;

    private final Context mContext;

    private final String mApnType;

    private DctConstants.State mState;

    private ArrayList<ApnSetting> mWaitingApns = null;

    public final int priority;

    /** A zero indicates that all waiting APNs had a permanent error */
    private AtomicInteger mWaitingApnsPermanentFailureCountDown;

    private ApnSetting mApnSetting;

    DcAsyncChannel mDcAc;

    String mReason;

    PendingIntent mReconnectAlarmIntent;

    /**
     * user/app requested connection on this APN
     */
    AtomicBoolean mDataEnabled;

    private final Object mRefCountLock = new Object();
    private int mRefCount = 0;

    /**
     * carrier requirements met
     */
    AtomicBoolean mDependencyMet;

    private final DcTrackerBase mDcTracker;

    /**
      * for VoLte Default Bearer used
      */
    DefaultBearerConfig mDefaultBearerConfig;

    /**
      * To decrease the time of unused apn type.
      */
    private boolean mNeedNotify;

    public ApnContext(Context context, String apnType, String logTag, NetworkConfig config,
            DcTrackerBase tracker) {
        mContext = context;
        mApnType = apnType;
        mState = DctConstants.State.IDLE;
        setReason(Phone.REASON_DATA_ENABLED);
        mDataEnabled = new AtomicBoolean(false);
        mDependencyMet = new AtomicBoolean(config.dependencyMet);
        mWaitingApnsPermanentFailureCountDown = new AtomicInteger(0);
        priority = config.priority;
        LOG_TAG = logTag;
        mDcTracker = tracker;
        
        // VoLTE
        mDefaultBearerConfig = new DefaultBearerConfig();
        mNeedNotify = needNotifyType(apnType);
    }

    public String getApnType() {
        return mApnType;
    }

    public synchronized DcAsyncChannel getDcAc() {
        return mDcAc;
    }

    public synchronized void setDataConnectionAc(DcAsyncChannel dcac) {
        if (DBG) {
            log("setDataConnectionAc: old dcac=" + mDcAc + " new dcac=" + dcac
                    + " this=" + this);
        }
        mDcAc = dcac;
    }

    public synchronized PendingIntent getReconnectIntent() {
        return mReconnectAlarmIntent;
    }

    public synchronized void setReconnectIntent(PendingIntent intent) {
        mReconnectAlarmIntent = intent;
    }

    public synchronized ApnSetting getApnSetting() {
        log("getApnSetting: apnSetting=" + mApnSetting);
        return mApnSetting;
    }

    public synchronized void setApnSetting(ApnSetting apnSetting) {
        log("setApnSetting: apnSetting=" + apnSetting);
        mApnSetting = apnSetting;
    }

    public synchronized void setWaitingApns(ArrayList<ApnSetting> waitingApns) {
        mWaitingApns = waitingApns;
        mWaitingApnsPermanentFailureCountDown.set(mWaitingApns.size());
    }

    public int getWaitingApnsPermFailCount() {
        return mWaitingApnsPermanentFailureCountDown.get();
    }

    public void decWaitingApnsPermFailCount() {
        mWaitingApnsPermanentFailureCountDown.decrementAndGet();
    }

    public synchronized ApnSetting getNextWaitingApn() {
        ArrayList<ApnSetting> list = mWaitingApns;
        ApnSetting apn = null;

        if (list != null) {
            if (!list.isEmpty()) {
                apn = list.get(0);
            }
        }
        return apn;
    }

    public synchronized void removeWaitingApn(ApnSetting apn) {
        if (mWaitingApns != null) {
            mWaitingApns.remove(apn);
        }
    }

    public synchronized ArrayList<ApnSetting> getWaitingApns() {
        return mWaitingApns;
    }

    public synchronized void setState(DctConstants.State s) {
        if (DBG) {
            log("setState: " + s + ", previous state:" + mState);
        }

        mState = s;

        if (mState == DctConstants.State.FAILED) {
            if (mWaitingApns != null) {
                mWaitingApns.clear(); // when teardown the connection and set to IDLE
            }
        }
    }

    public synchronized DctConstants.State getState() {
        return mState;
    }

    public boolean isDisconnected() {
        DctConstants.State currentState = getState();
        return ((currentState == DctConstants.State.IDLE) ||
                    currentState == DctConstants.State.FAILED);
    }

    public synchronized void setReason(String reason) {
        if (DBG) {
            log("set reason as " + reason + ",current state " + mState);
        }
        mReason = reason;
    }

    public synchronized String getReason() {
        return mReason;
    }

    public boolean isReady() {
        return mDataEnabled.get() && mDependencyMet.get();
    }

    public boolean isConnectable() {
        return isReady() && ((mState == DctConstants.State.IDLE)
                                || (mState == DctConstants.State.SCANNING)
                                || (mState == DctConstants.State.RETRYING)
                                || (mState == DctConstants.State.FAILED));
    }

    public boolean isConnectedOrConnecting() {
        return isReady() && ((mState == DctConstants.State.CONNECTED)
                                || (mState == DctConstants.State.CONNECTING)
                                || (mState == DctConstants.State.SCANNING)
                                || (mState == DctConstants.State.RETRYING));
    }

    public void setEnabled(boolean enabled) {
        if (DBG) {
            log("set enabled as " + enabled + ", current state is " + mDataEnabled.get());
        }
        mDataEnabled.set(enabled);
        mNeedNotify = true;
    }

    public boolean isEnabled() {
        return mDataEnabled.get();
    }

    public void setDependencyMet(boolean met) {
        if (DBG) {
            log("set mDependencyMet as " + met + " current state is " + mDependencyMet.get());
        }
        mDependencyMet.set(met);
    }

    public boolean getDependencyMet() {
       return mDependencyMet.get();
    }

    public boolean isProvisioningApn() {
        String provisioningApn = mContext.getResources()
                .getString(R.string.mobile_provisioning_apn);
        if ((mApnSetting != null) && (mApnSetting.apn != null)) {
            return (mApnSetting.apn.equals(provisioningApn));
        } else {
            return false;
        }
    }

    public void incRefCount() {
        synchronized (mRefCountLock) {
            if (mRefCount++ == 0) {
                mDcTracker.setEnabled(mDcTracker.apnTypeToId(mApnType), true);
            }
        }
    }

    public void decRefCount() {
        synchronized (mRefCountLock) {
            if (mRefCount-- == 1) {
                mDcTracker.setEnabled(mDcTracker.apnTypeToId(mApnType), false);
            }
        }
    }

    //VoLTE [start]
    public DefaultBearerConfig getDefaultBearerConfig() {
        return mDefaultBearerConfig;
    }

    public void setDefaultBearerConfig(DefaultBearerConfig defaultBearerConfig) {
        mDefaultBearerConfig.copyFrom(defaultBearerConfig);
    }

    public boolean isDefaultBearerConfigValid() {
        return (0 == mDefaultBearerConfig.mIsValid) ? false : true;
    }

    public void resetDefaultBearerConfig() {
        mDefaultBearerConfig.reset();
    }
    //VoLTE [end]

    //MTK Start: Check need notify or not
    private boolean needNotifyType(String apnTypes) {
        if (apnTypes.equals(PhoneConstants.APN_TYPE_DM)
                || apnTypes.equals(PhoneConstants.APN_TYPE_WAP)
                || apnTypes.equals(PhoneConstants.APN_TYPE_NET)
                || apnTypes.equals(PhoneConstants.APN_TYPE_CMMAIL)
                || apnTypes.equals(PhoneConstants.APN_TYPE_TETHERING)
                || apnTypes.equals(PhoneConstants.APN_TYPE_RCSE)) {
            return false;
        }
        return true;
    }

    public boolean isNeedNotify() {
        if (DBG) {
            log("Current apn tpye:" + mApnType + " isNeedNotify" + mNeedNotify);
        }
        return mNeedNotify;
    }
    //MTK End: Check need notify or not

    @Override
    public synchronized String toString() {
        // We don't print mDataConnection because its recursive.
        return "{mApnType=" + mApnType + " mState=" + getState() + " mWaitingApns={" + mWaitingApns +
                "} mWaitingApnsPermanentFailureCountDown=" + mWaitingApnsPermanentFailureCountDown +
                " mApnSetting={" + mApnSetting + "} mReason=" + mReason +
                " mDataEnabled=" + mDataEnabled + " mDependencyMet=" + mDependencyMet + "}";
    }

    protected void log(String s) {
        Rlog.d(LOG_TAG, "[ApnContext:" + mApnType + "] " + s);
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("ApnContext: " + this.toString());
    }
}
