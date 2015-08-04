/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.mediatek.systemui.qs.tiles;

import android.content.Intent;
import android.content.Context;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.provider.Settings;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.view.View;
import android.view.ViewGroup;
import android.view.View.OnAttachStateChangeListener;

import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.systemui.R;
import com.android.systemui.qs.QSTile;
import com.android.internal.telephony.IccCardConstants;

import com.mediatek.xlog.Xlog;
import com.mediatek.systemui.statusbar.policy.DataConnectionController;
import com.mediatek.systemui.statusbar.util.SIMHelper;


public class DataConnectionTile extends QSTile<QSTile.BooleanState> {
    private static final String TAG = "DataConnectionTile";
    private static final boolean DEBUG = true;
    private final DataConnectionController mController;
    private boolean mListening;
    private int mDataState = R.drawable.ic_qs_mobile_off;
    private boolean mAirPlaneMode = false;
    private IccCardConstants.State mSimState = IccCardConstants.State.UNKNOWN;
    private TelephonyManager mTelephonyManager;
    private boolean mDataEnable = false;
    private long mDefaultDataSim = SubscriptionManager.INVALID_SUB_ID;
    private int mSlotID = SIMHelper.INVALID_SLOT_ID;
    private int mCurrentRadioMode = 3;

    public static final int DATA_DISCONNECT = 0;
    public static final int DATA_CONNECT = 1;
    public static final int AIRPLANE_DATA_CONNECT = 2;
    public static final int DATA_CONNECT_DISABLE = 3;
    public static final int DATA_RADIO_OFF = 4;

    private int mSlotCount = 0;
    public static final int DEFAULT_DATA_SIM_UNSET = 0;
    public static final int DEFAULT_DATA_SIM_SET = 1;

    public DataConnectionTile(Host host) {
        super(host);
        mController = host.getDataConnectionController();
        if (DEBUG) {
            Xlog.d(TAG, "DataConnectionTile");
        }
        mSlotCount = SIMHelper.getSlotCount();
        if (DEBUG) {
            Xlog.d(TAG, "mSlotCount = " + mSlotCount);
        }
    }

    @Override
    protected BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void setListening(boolean listening) {
        if (DEBUG) Xlog.d( TAG, "DataConnectionTile setListening= " +  listening);
        if (mListening == listening) return;
            mListening = listening;
            if (listening) {
                final IntentFilter filter = new IntentFilter();
                filter.addAction(TelephonyIntents.ACTION_ANY_DATA_CONNECTION_STATE_CHANGED);
                filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
                filter.addAction(Intent.ACTION_MSIM_MODE_CHANGED);
                filter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
                mContext.registerReceiver(mReceiver, filter);
                mContext.getContentResolver().registerContentObserver(
                            Settings.Secure.getUriFor(Settings.Global.MOBILE_DATA)
                            , true, mMobileStateForSingleCardChangeObserver);

                /// M:Register for settings change.
                mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(
                Settings.Global.MULTI_SIM_DATA_CALL_SUBSCRIPTION),
                false, mDefaultDataSIMObserver);
            }
    }

    @Override
    protected void handleDestroy() {
        super.handleDestroy();
        if (DEBUG) Xlog.d(TAG, "handle destroy");
        mContext.unregisterReceiver(mReceiver);
        mContext.getContentResolver().unregisterContentObserver(
                    mMobileStateForSingleCardChangeObserver);
    }
    
    @Override
    protected void handleClick() {
        getDefaultDataSlotID();
        if (DEBUG) {
            Xlog.d(TAG, "handleClick sim state " + isDefaultSimSet() +
                    "data enable state=" + mTelephonyManager.getDataEnabled() +
                    " mCurrentRadioMode=" + mCurrentRadioMode);
        }

        if (!hasSimInsert()
            || mCurrentRadioMode == 0
            && isDefaultSimSet() != DEFAULT_DATA_SIM_UNSET
            || mAirPlaneMode
            || !isDefaultDataSimRadioOn()) {
            if (DEBUG) {
                Xlog.d(TAG, "handleClick mAirPlaneMode= " + mAirPlaneMode +
                " mSimState= " + mSimState +
                " mSlotID= " + mSlotID +
                " mDefaultDataSim= " + mDefaultDataSim);
            }
            if (!mAirPlaneMode) {
                mDataState = R.drawable.ic_qs_mobile_off;
            }
            refreshState();
            return;
        }

        if (mTelephonyManager == null)
            mTelephonyManager = TelephonyManager.from(mContext);

        try {
            boolean enabled = mTelephonyManager.getDataEnabled();

            if (DEBUG) Xlog.d( TAG, "handleClick data state= " + enabled );

            mTelephonyManager.setDataEnabled(!enabled);
            if (!enabled) {
                mDataState = R.drawable.ic_qs_mobile_white;
            } else {
                mDataState = R.drawable.ic_qs_mobile_off;
            }
        } catch (NullPointerException e) {
            if (DEBUG) Xlog.d(TAG, "failed get  TelephonyManager exception" + e);
        }

        refreshState();
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.label = mContext.getString(R.string.mobile);
        state.visible = true;
        state.iconId =  mDataState;
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(TelephonyIntents.ACTION_ANY_DATA_CONNECTION_STATE_CHANGED)) {
                if (DEBUG) Xlog.d(TAG, "onReceive ACTION_ANY_DATA_CONNECTION_STATE_CHANGED");
                getDataConnectionState();
            } else if (action.equals(TelephonyIntents.ACTION_SIM_STATE_CHANGED)) {
                if (DEBUG) Xlog.d(TAG, "onReceive ACTION_SIM_STATE_CHANGED");
                updateSimState(intent);
                //getDataConnectionState();
            } else if (action.equals(Intent.ACTION_AIRPLANE_MODE_CHANGED)) {
                mAirPlaneMode = intent.getBooleanExtra("state", false);
                if (DEBUG) Xlog.d(TAG, "onReceive ACTION_AIRPLANE_MODE_CHANGED mAirPlaneMode= " + mAirPlaneMode);
                getDataConnectionState();
            } else if (action.equals(Intent.ACTION_MSIM_MODE_CHANGED)) {
                mCurrentRadioMode = intent.getIntExtra(Intent.EXTRA_MSIM_MODE , 3);
                getDataConnectionState();
                if (DEBUG) {
                    Xlog.d(TAG, "onReceive ACTION_MSIM_MODE_CHANGED mCurrentRadioMode" +
                        mCurrentRadioMode);
                }
            }
            refreshState();
        }
    };

    private int getDataConnectionState() {
        if (DEBUG) Xlog.d( TAG, "getDataConnectionState mSimState= "+ mSimState);

        if (mTelephonyManager == null) {
                mTelephonyManager = TelephonyManager.from(mContext);
            }

        try {
            getDefaultDataSlotID();

            boolean dataEnable = mTelephonyManager.getDataEnabled();
            if (DEBUG) {
                Xlog.d(TAG, "getDataConnectionState dataEnable= " + dataEnable);
            }

            int dataResult = DATA_DISCONNECT;

            if (dataEnable == false /*|| mSimState == IccCardConstants.State.ABSENT*/) {
                dataResult = DATA_DISCONNECT;
            } else if (dataEnable && mAirPlaneMode) {
                dataResult = AIRPLANE_DATA_CONNECT;
            } else if (dataEnable && !mAirPlaneMode /*&& mSimState != IccCardConstants.State.ABSENT*/) {
                dataResult = DATA_CONNECT;
            }

            boolean radiomode = (mCurrentRadioMode & (mSlotID + 1)) == 0;
            Xlog.d(TAG, "getDataConnectionState DATA_RADIO_OFF mCurrentRadioMode= "
                    + mCurrentRadioMode + " mSlotID= " + mSlotID +
                    " (mCurrentRadioMode & (mSlotID + 1)) =" + radiomode);
            if ((dataEnable == true && (mCurrentRadioMode & (mSlotID + 1)) == 0) ||
                    (dataEnable == true && !SIMHelper.isSimInsertedBySlot(mContext, mSlotID)) &&
                    isDefaultSimSet() != DEFAULT_DATA_SIM_UNSET) {
                dataResult = DATA_RADIO_OFF;
            }

            setDataConnectionUI(dataResult);
            if (DEBUG) {
                Xlog.d(TAG, "getDataConnectionState dataResult= " +
                        dataResult + " mSimState= " + mSimState);
            }
            return dataResult;
        } catch (NullPointerException e) {
            if (DEBUG) {
                Xlog.d(TAG, "failed get  TelephonyManager exception" + e);
            }
        }

        return DATA_DISCONNECT;
    }

    private void setDataConnectionUI(int dataState) {
        if (DEBUG) Xlog.d( TAG, "setDataConnectionUI = " + dataState);
        switch(dataState) {
            case DATA_DISCONNECT:
                mDataState = R.drawable.ic_qs_mobile_off;
                break;
            case DATA_RADIO_OFF:
            case DATA_CONNECT_DISABLE:
            case AIRPLANE_DATA_CONNECT:
                mDataState = R.drawable.ic_qs_mobile_off;
                break;
            case DATA_CONNECT:
                mDataState = R.drawable.ic_qs_mobile_white;
                break;
            default :
                mDataState = R.drawable.ic_qs_mobile_off;
                break;
        }
    }

    private final void updateSimState(Intent intent) {
        getDefaultDataSlotID();

        int slotId = intent.getIntExtra(PhoneConstants.SLOT_KEY,
                SIMHelper.INVALID_SLOT_ID);
        if (DEBUG) {
            Xlog.d(TAG, "updateSimState default data mSlotID= " + mSlotID + " slotId= " + slotId);
        }
        if (mSlotID == slotId) {
            String stateExtra = intent.getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE);
            if (IccCardConstants.INTENT_VALUE_ICC_ABSENT.equals(stateExtra)) {
                mSimState = IccCardConstants.State.ABSENT;
            } else if (IccCardConstants.INTENT_VALUE_ICC_READY.equals(stateExtra)) {
                mSimState = IccCardConstants.State.READY;
            } else if (IccCardConstants.INTENT_VALUE_ICC_LOCKED.equals(stateExtra)) {
                final String lockedReason =
                        intent.getStringExtra(IccCardConstants.INTENT_KEY_LOCKED_REASON);
                if (IccCardConstants.INTENT_VALUE_LOCKED_ON_PIN.equals(lockedReason)) {
                    mSimState = IccCardConstants.State.PIN_REQUIRED;
                } else if (IccCardConstants.INTENT_VALUE_LOCKED_ON_PUK.equals(lockedReason)) {
                    mSimState = IccCardConstants.State.PUK_REQUIRED;
                } else {
                    mSimState = IccCardConstants.State.NETWORK_LOCKED;
                }
            } else {
                mSimState = IccCardConstants.State.UNKNOWN;
            }
        }
        if (DEBUG) Xlog.d(TAG, "updateSimState mSimState= " + mSimState);
    }

    private ContentObserver mMobileStateForSingleCardChangeObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {

            if (!SIMHelper.isWifiOnlyDevice()) {
                mDataEnable = Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.MOBILE_DATA, 1) == 1;
                if (DEBUG) {
                    Xlog.d(TAG, "onChange mDataEnable= " + mDataEnable);
                }
            }
        }
    };

    private ContentObserver mDefaultDataSIMObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            mDefaultDataSim = SubscriptionManager.getDefaultDataSubId();
            if (mDefaultDataSim != SubscriptionManager.INVALID_SUB_ID) {
                mSlotID = SubscriptionManager.getSlotId(mDefaultDataSim);
            } else {
                mSlotID = SIMHelper.INVALID_SLOT_ID;
            }
            if (DEBUG) {
                Xlog.d(TAG, "mDefaultDataSIMObserver mDefaultDataSim= " + mDefaultDataSim +
                    " mSlotID=" + mSlotID);
            }
        }
    };

    private void getDefaultDataSlotID() {
        try {
            mDefaultDataSim = SubscriptionManager.getDefaultDataSubId();
            if (mDefaultDataSim != SubscriptionManager.INVALID_SUB_ID) {
                mSlotID = SubscriptionManager.getSlotId(mDefaultDataSim);
            } else {
                mSlotID = SIMHelper.INVALID_SLOT_ID;
            }
        } catch (NullPointerException e) {
            if (DEBUG) {
                Xlog.d(TAG, "failed get  SubscriptionManager exception" + e);
            }
        }
    }

    private boolean hasSimInsert() {
        for (int slot = 0; slot < mSlotCount ; slot++) {
            if (SIMHelper.isSimInsertedBySlot(mContext, slot)) {
                if (DEBUG) {
                    Xlog.d(TAG, "hasSimInsert slot=" + slot);
                }
                return true;
            }
        }
        if (DEBUG) {
            Xlog.d(TAG, "No Sim Insert");
        }
        return false;
    }

    private int isDefaultSimSet() {
        if (hasSimInsert() && (mDefaultDataSim == SubscriptionManager.INVALID_SUB_ID)) {
            if (DEBUG) {
                Xlog.d(TAG, "DefaultSim is unset ");
            }
            return DEFAULT_DATA_SIM_UNSET;
        } else if (hasSimInsert() && (mDefaultDataSim != SubscriptionManager.INVALID_SUB_ID)) {
            if (DEBUG) {
                    Xlog.d(TAG, "DefaultSim is set ");
            }
            return DEFAULT_DATA_SIM_SET;
        } else {
            return -1;
        }
    }

    private boolean isDefaultDataSimRadioOn() {
        if (isDefaultSimSet() == DEFAULT_DATA_SIM_SET) {
            if ((mCurrentRadioMode & (mSlotID + 1)) == 0) {
                if (DEBUG) {
                        Xlog.d(TAG, "DefaultDataSimRadio is Off mSlotID= " + mSlotID);
                }
                return false;
            } else {
                if (DEBUG) {
                    Xlog.d(TAG, "DefaultDataSimRadio is on mSlotID= " + mSlotID);
                }
                return true;
            }
        } else if (isDefaultSimSet() == DEFAULT_DATA_SIM_UNSET) {
            if (DEBUG) {
                Xlog.d(TAG, "isDefaultSimSet() == DEFAULT_DATA_SIM_UNSET");
            }
            return true;
        } else {
            return false;
        }

    }
}
