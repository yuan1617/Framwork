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

package com.android.systemui.qs.tiles;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import com.android.systemui.R;
import com.android.systemui.qs.QSTile;
import com.android.systemui.qs.QSTileView;
import com.android.systemui.qs.SignalTileView;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.NetworkController.DataUsageInfo;
import com.android.systemui.statusbar.policy.NetworkController.NetworkSignalChangedCallback;
import com.android.internal.telephony.PhoneConstants;

import com.mediatek.xlog.Xlog;

/** Quick settings tile: Cellular **/
public class CellularTile extends QSTile<QSTile.SignalState> {
    private static final Intent CELLULAR_SETTINGS = new Intent().setComponent(new ComponentName(
            "com.android.settings", "com.android.settings.Settings$DataUsageSummaryActivity"));

    private static final String TAG = "CellularTile";
    private static final boolean DBG = true;
    
    private final NetworkController mController;
    private final CellularDetailAdapter mDetailAdapter;

    public CellularTile(Host host) {
        super(host);
        mController = host.getNetworkController();
        mDetailAdapter = new CellularDetailAdapter();
    }

    @Override
    protected SignalState newTileState() {
        return new SignalState();
    }

    @Override
    public DetailAdapter getDetailAdapter() {
        return mDetailAdapter;
    }

    @Override
    public void setListening(boolean listening) {
        if (listening) {
            mController.addNetworkSignalChangedCallback(mCallback);
        } else {
            mController.removeNetworkSignalChangedCallback(mCallback);
        }
    }

    @Override
    public QSTileView createTileView(Context context) {
        return new SignalTileView(context);
    }

    @Override
    protected void handleClick() {
        // SUB SELECT
        final long subId = SubscriptionManager.getDefaultDataSubId();
        Xlog.d(TAG,"DefaultDataSubId = " + subId);

        CELLULAR_SETTINGS.putExtra(PhoneConstants.SUBSCRIPTION_KEY, subId);
        mHost.startSettingsActivity(CELLULAR_SETTINGS);
    }

    @Override
    protected void handleUpdateState(SignalState state, Object arg) {
        state.visible = mController.hasMobileDataFeature();
        if (!state.visible) return;
        final CallbackInfo cb = (CallbackInfo) arg;
        if (cb == null) return;

        final Resources r = mContext.getResources();
        state.iconId = cb.noSim ? R.drawable.ic_qs_no_sim
                : !cb.enabled || cb.airplaneModeEnabled ? R.drawable.ic_qs_signal_disabled
                : cb.mobileSignalIconId > 0 ? cb.mobileSignalIconId
                : R.drawable.ic_qs_signal_no_signal;
        state.isOverlayIconWide = cb.isDataTypeIconWide;
        state.autoMirrorDrawable = !cb.noSim;
        state.overlayIconId = cb.enabled && (cb.dataTypeIconId > 0) && !cb.wifiConnected
                ? cb.dataTypeIconId
                : 0;
        state.isroaming = cb.isroaming;
        state.isConnected = cb.isconnected;
        state.isAirplaneMode = cb.airplaneModeEnabled;
        state.filter = state.iconId != R.drawable.ic_qs_no_sim;
        state.activityIn = cb.enabled && cb.activityIn;
        state.activityOut = cb.enabled && cb.activityOut;

        state.label = cb.enabled
                ? removeTrailingPeriod(cb.enabledDesc)
                : r.getString(R.string.quick_settings_rssi_emergency_only);

        final String signalContentDesc = cb.enabled && (cb.mobileSignalIconId > 0)
                ? cb.signalContentDescription
                : r.getString(R.string.accessibility_no_signal);
        final String dataContentDesc = cb.enabled && (cb.dataTypeIconId > 0) && !cb.wifiEnabled
                ? cb.dataContentDescription
                : r.getString(R.string.accessibility_no_data);

        String noService = mContext.getString(
                com.android.internal.R.string.lockscreen_carrier_default);

        if (cb.enabled &&
                noService.equals(state.label) &&
                TelephonyManager.from(mContext).getNetworkOperator() != null &&
                SubscriptionManager.getDefaultDataSubId() == SubscriptionManager.INVALID_SUB_ID &&
                !cb.noSim) {
            state.label = r.getString(R.string.quick_settings_data_sim_notset);
        }

        state.contentDescription = r.getString(
                R.string.accessibility_quick_settings_mobile,
                signalContentDesc, dataContentDesc,
                state.label);
    }

    // Remove the period from the network name
    public static String removeTrailingPeriod(String string) {
        if (string == null) return null;
        final int length = string.length();
        if (string.endsWith(".")) {
            return string.substring(0, length - 1);
        }
        return string;
    }

    private static final class CallbackInfo {
        boolean enabled;
        boolean wifiEnabled;
        boolean wifiConnected;
        boolean airplaneModeEnabled;
        int mobileSignalIconId;
        String signalContentDescription;
        int dataTypeIconId;
        boolean isroaming;
        boolean isconnected;
        String dataContentDescription;
        boolean activityIn;
        boolean activityOut;
        String enabledDesc;
        boolean noSim;
        boolean isDataTypeIconWide;
        long subId;
    }

    private final NetworkSignalChangedCallback mCallback = new NetworkSignalChangedCallback() {
        private boolean mWifiEnabled;
        private boolean mWifiConnected;
        private boolean mAirplaneModeEnabled;

        @Override
        public void onWifiSignalChanged(boolean enabled, boolean connected, int wifiSignalIconId,
                boolean activityIn, boolean activityOut,
                String wifiSignalContentDescriptionId, String description) {
            mWifiEnabled = enabled;
            mWifiConnected = connected;
        }

        @Override
        public void onMobileDataSignalChanged(long subId,
                boolean enabled,
                int mobileSignalIconId,
                String mobileSignalContentDescriptionId, int dataTypeIconId, boolean isroam, boolean isconnected, boolean isAir,
                boolean activityIn, boolean activityOut,
                String dataTypeContentDescriptionId, String description, boolean noSim,
                boolean isDataTypeIconWide) {
            final CallbackInfo info = new CallbackInfo();  // TODO pool?
            info.enabled = enabled;
            info.wifiEnabled = mWifiEnabled;
            info.wifiConnected = mWifiConnected;
            info.airplaneModeEnabled = isAir;
            info.mobileSignalIconId = mobileSignalIconId;
            info.signalContentDescription = mobileSignalContentDescriptionId;
            info.dataTypeIconId = dataTypeIconId;
            info.isroaming = isroam;
            info.isconnected = isconnected;
            info.dataContentDescription = dataTypeContentDescriptionId;
            info.activityIn = activityIn;
            info.activityOut = activityOut;
            info.enabledDesc = description;
            info.noSim = noSim;
            info.isDataTypeIconWide = isDataTypeIconWide;
            info.subId = subId;
            if (DBG) {
                Xlog.d(TAG, "onMobileDataSignalChanged info.enabled = " + info.enabled +
                    " info.wifiEnabled = " + info.wifiEnabled +
                    " info.wifiConnected = " + info.wifiConnected +
                    " info.airplaneModeEnabled = " + info.airplaneModeEnabled +
                    " info.mobileSignalIconId = " + info.mobileSignalIconId +
                    " info.signalContentDescription = " + info.signalContentDescription +
                    " info.dataTypeIconId = " + info.dataTypeIconId +
                    " info.dataContentDescription = " + info.dataContentDescription + 
                    " info.activityIn = " + info.activityIn +
                    " info.activityOut = " + info.activityOut +
                    " info.enabledDesc = " + info.enabledDesc +
                    " info.noSim = " + info.noSim +
                    " info.isDataTypeIconWide = " + info.isDataTypeIconWide +
                    " info.subId = " + info.subId);
            }
            refreshState(info);
        }

        @Override
        public void onAirplaneModeChanged(boolean enabled) {
            mAirplaneModeEnabled = enabled;
        }

        public void onMobileDataEnabled(boolean enabled) {
            mDetailAdapter.setMobileDataEnabled(enabled);
        }
    };

    private final class CellularDetailAdapter implements DetailAdapter {

        @Override
        public int getTitle() {
            return R.string.quick_settings_cellular_detail_title;
        }

        @Override
        public Boolean getToggleState() {
            return mController.isMobileDataSupported() ? mController.isMobileDataEnabled() : null;
        }

        @Override
        public Intent getSettingsIntent() {
            return CELLULAR_SETTINGS;
        }

        @Override
        public void setToggleState(boolean state) {
            mController.setMobileDataEnabled(state);
        }

        @Override
        public View createDetailView(Context context, View convertView, ViewGroup parent) {
            final DataUsageDetailView v = (DataUsageDetailView) (convertView != null
                    ? convertView
                    : LayoutInflater.from(mContext).inflate(R.layout.data_usage, parent, false));
            final DataUsageInfo info = mController.getDataUsageInfo();
            if (info == null) return v;
            v.bind(info);
            return v;
        }

        public void setMobileDataEnabled(boolean enabled) {
            fireToggleStateChanged(enabled);
        }
    }
}
