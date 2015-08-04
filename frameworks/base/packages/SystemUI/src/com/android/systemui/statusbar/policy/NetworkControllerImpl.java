/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wimax.WimaxManagerConstants;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Messenger;
import android.os.SystemClock;
import android.provider.Settings;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.telephony.SubscriptionManager;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.cdma.EriInfo;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.util.AsyncChannel;
import com.android.systemui.DemoMode;
import com.android.systemui.R;
import com.android.systemui.statusbar.phone.StatusBarHeaderView;

import com.mediatek.systemui.ext.DataType;
import com.mediatek.systemui.ext.NetworkType;
import com.mediatek.systemui.ext.PluginFactory;
import com.mediatek.systemui.ext.IconIdWrapper;
import com.mediatek.systemui.statusbar.util.SIMHelper;
import com.mediatek.xlog.Xlog;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Platform implementation of the network controller. **/
/// M: [SystemUI] Support "dual SIM"
public class NetworkControllerImpl extends BroadcastReceiver
        implements NetworkController, DemoMode {
    // debug
    static final String TAG = "StatusBar.NetworkController";
    static final boolean DEBUG = true; //false; /// M: Enable Log for debug
    static final boolean CHATTY = false; // additional diagnostics, but not logspew
    /// M: config for show the ! icon or not
    static final boolean mShowNormalIcon = true;

    int mSlotCount = 0;

    // telephony
    boolean mHspaDataDistinguishable;
    final TelephonyManager mPhone;
    boolean[] mDataConnected;
    IccCardConstants.State[] mSimState;
    int mPhoneState = TelephonyManager.CALL_STATE_IDLE;
    int[] mDataNetType;
    int[] mDataState;
    int[] mDataActivity;
    ServiceState[] mServiceState;
    SignalStrength[] mSignalStrength;
    PhoneStateListener[] mPhoneStateListener;
    //int[][] mDataIconList; ///IconIdWrapper
    String[] mNetworkName;
    String mNetworkNameDefault;
    String mNetworkNameSeparator;
    //int[] mPhoneSignalIconId; ///IconIdWrapper
    int[] mQSPhoneSignalIconId;
    int[] mDataDirectionIconId; // data + data direction on phones
    //int[] mDataSignalIconId; ///IconIdWrapper
    //int[] mDataTypeIconId; ///IconIdWrapper
    int[] mQSDataTypeIconId;
    int[] mQSDataTypeIconIdR;
    int mAirplaneIconId;
    /// M: [ALPS01798819] record the last icon id and refresh it
    int mLastAirplaneIconId = -1;
    boolean mDataActive;
    boolean mNoSim;
    int mLastSignalLevel;
    boolean mShowPhoneRSSIForData = false;
    boolean mShowAtLeastThreeGees = false;
    boolean mAlwaysShowCdmaRssi = false;

    String[] mContentDescriptionPhoneSignal;
    String mContentDescriptionWifi;
    String mContentDescriptionWimax;
    String mContentDescriptionCombinedSignal;
    String[] mContentDescriptionDataType;
    NetworkType[] mNetworkType;

    // wifi
    final WifiManager mWifiManager;
    AsyncChannel mWifiChannel;
    boolean mWifiEnabled, mWifiConnected;
    int mWifiRssi, mWifiLevel;
    String mWifiSsid;
    //int mWifiIconId = 0; ///IconIdWrapper
    int mQSWifiIconId = 0;
    int mWifiActivity = WifiManager.DATA_ACTIVITY_NONE;

    // bluetooth
    private boolean mBluetoothTethered = false;
    private int mBluetoothTetherIconId =
        com.android.internal.R.drawable.stat_sys_tether_bluetooth;

    //wimax
    private boolean mWimaxSupported = false;
    private boolean mIsWimaxEnabled = false;
    private boolean mWimaxConnected = false;
    private boolean mWimaxIdle = false;
    private int mWimaxIconId = 0;
    private int mWimaxSignal = 0;
    private int mWimaxState = 0;
    private int mWimaxExtraState = 0;

    // data connectivity (regardless of state, can we access the internet?)
    // state of inet connection - 0 not connected, 100 connected
    private boolean mConnected = false;
    private int mConnectedNetworkType = ConnectivityManager.TYPE_NONE;
    private String mConnectedNetworkTypeName;
    private int mLastConnectedNetworkType = ConnectivityManager.TYPE_NONE;

    private int mInetCondition = 0;
    private int mLastInetCondition = 0;
    private static final int INET_CONDITION_THRESHOLD = 50;

    private boolean mAirplaneMode = false;
    private boolean mLastAirplaneMode = true;

    private Locale mLocale = null;
    private Locale mLastLocale = null;

    // our ui
    Context mContext;
    ArrayList<TextView> mCombinedLabelViews = new ArrayList<TextView>();
    ArrayList<TextView> mMobileLabelViews = new ArrayList<TextView>();
    ArrayList<TextView> mWifiLabelViews = new ArrayList<TextView>();
    ArrayList<StatusBarHeaderView> mEmergencyViews = new ArrayList<>();
    ArrayList<SignalCluster> mSignalClusters = new ArrayList<SignalCluster>();
    ArrayList<NetworkSignalChangedCallback> mSignalsChangedCallbacks =
            new ArrayList<NetworkSignalChangedCallback>();
    //int[] mLastPhoneSignalIconId;
    int[] mLastDataDirectionIconId;
    int mLastWifiIconId = -1;
    int mLastWimaxIconId = -1;
    int mLastCombinedSignalIconId = -1;
    int[] mLastDataTypeIconId;
    String mLastCombinedLabel = "";

    private boolean mHasMobileDataFeature;

    boolean mDataAndWifiStacked = false;
    private boolean[] mIsRoaming;
    /// M: Support "SystemUI - VoLTE icon". @{
    private int mIMSState[];
    private boolean mVoLTEState = false;
    /// M: Support "SystemUI - VoLTE icon". @}
    /// M: Support "Default SIM Indicator"
    boolean[] mSimIndicatorFlag;
    int[] mSimIndicatorResId;
    /// M: IconIdWrapper.
    IconIdWrapper[] mDataSignalIconId;
    IconIdWrapper[] mDataTypeIconId;
    IconIdWrapper[] mDataTypeIconIdR;
    IconIdWrapper[][] mDataIconList;
    IconIdWrapper[][] mPhoneSignalIconId;
    IconIdWrapper mWifiIconId = new IconIdWrapper();
    DataType tempDataTypeL; //Small

    /// M: PhoneSignal
    int[][] mLastPhoneSignalIconId;
    int mPhoneSignalIconIdNum = 2;
    /// M: MobileActivityIcon
    IconIdWrapper[] mMobileActivityIconId;
    int[] mLastMobileActivityIconId;
    int mLastcombinedActivityIconId = -1;
    /// M: Support "IPO".
    private static final String ACTION_BOOT_IPO = "android.intent.action.ACTION_PREBOOT_IPO";

    public interface SignalCluster {
        /// M: IconIdWrapper.
        void setWifiIndicators(boolean visible, IconIdWrapper strengthIcon,
                String contentDescription);
        void setMobileDataIndicators(int slotId, boolean visible,
                IconIdWrapper [] strengthIcon, IconIdWrapper activityIcon, IconIdWrapper typeIcon,
                String contentDescription, String typeContentDescription, boolean roaming,
                boolean isTypeIconWide);
        void setIsAirplaneMode(boolean is, int airplaneIcon);
        void setNetworkType(int slotId, NetworkType mType);
        /// M: Support "SystemUI - VoLTE icon".
        void setVoLTE(boolean mShow);
        /// M: Support "Default SIM Indicator"
        void setShowSimIndicator(int slotId, boolean showSimIndicator,int resId);
        void apply();
    }

    private final WifiAccessPointController mAccessPoints;
    private final MobileDataController mMobileDataController;

    /**
     * Construct this controller object and register for updates.
     */
    public NetworkControllerImpl(Context context) {
        mContext = context;
        final Resources res = context.getResources();

        ConnectivityManager cm = (ConnectivityManager)mContext.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        mHasMobileDataFeature = cm.isNetworkSupported(ConnectivityManager.TYPE_MOBILE);

        mShowPhoneRSSIForData = res.getBoolean(R.bool.config_showPhoneRSSIForData);
        mShowAtLeastThreeGees = res.getBoolean(R.bool.config_showMin3G);
        mAlwaysShowCdmaRssi = res.getBoolean(
                com.android.internal.R.bool.config_alwaysUseCdmaRssi);

        // set up the default wifi icon, used when no radios have ever appeared
        updateWifiIcons();
        updateWimaxIcons();

        // telephony
        mPhone = (TelephonyManager)context.getSystemService(Context.TELEPHONY_SERVICE);
        mHspaDataDistinguishable = mContext.getResources().getBoolean(
                R.bool.config_hspa_data_distinguishable);

        /// M: Support "Operator plugin - HspaDistinguishable". @{
//        mHspaDataDistinguishable = PluginFactory.getStatusBarPlugin(mContext)
//                .customizeHspaDistinguishable(mHspaDataDistinguishable);
        /// M: Support "Operator plugin - HspaDistinguishable". @}

        mNetworkNameSeparator = mContext.getString(R.string.status_bar_network_name_separator);
        mNetworkNameDefault = mContext.getString(
                com.android.internal.R.string.lockscreen_carrier_default);

        mSlotCount = SIMHelper.getSlotCount();
        mSignalStrength = new SignalStrength[mSlotCount];
        mServiceState = new ServiceState[mSlotCount];
        mDataNetType = new int[mSlotCount];
        mDataState = new int[mSlotCount];
        mDataConnected = new boolean[mSlotCount];
        mSimState = new IccCardConstants.State[mSlotCount];
        mDataDirectionIconId = new int[mSlotCount];
        mDataActivity = new int[mSlotCount];
        mContentDescriptionPhoneSignal = new String[mSlotCount];
        //mDataSignalIconId = new int[mSlotCount];
        mContentDescriptionDataType = new String[mSlotCount];
        //mDataIconList = new int[mSlotCount][TelephonyIcons.DATA_G.length];
        mNetworkName = new String[mSlotCount];
        //mPhoneSignalIconId = new int[mSlotCount];
        mQSPhoneSignalIconId = new int[mSlotCount];
        //mDataTypeIconId = new int[mSlotCount];
        mQSDataTypeIconId = new int[mSlotCount];
        mQSDataTypeIconIdR = new int[mSlotCount];
        //mLastPhoneSignalIconId = new int[mSlotCount];
        mLastDataDirectionIconId = new int[mSlotCount];
        mLastDataTypeIconId = new int[mSlotCount];
        mPhoneStateListener = new PhoneStateListener[mSlotCount];
        mNetworkType = new NetworkType[mSlotCount];
        mIsRoaming = new boolean[mSlotCount];
        /// M: Support "SystemUI - VoLTE icon".
        mIMSState = new int[mSlotCount];
        /// M: Support "Default SIM Indicator"
        mSimIndicatorFlag = new boolean[mSlotCount];
        mSimIndicatorResId = new int[mSlotCount];
        /// M: IconIdWrapper.
        mDataSignalIconId = new IconIdWrapper[mSlotCount];
        mDataTypeIconId = new IconIdWrapper[mSlotCount];
        mDataTypeIconIdR = new IconIdWrapper[mSlotCount];
        mDataIconList = new IconIdWrapper[mSlotCount][TelephonyIcons.DATA_G.length];
        mPhoneSignalIconId = new IconIdWrapper[mSlotCount][mPhoneSignalIconIdNum];
        mLastPhoneSignalIconId = new int[mSlotCount][mPhoneSignalIconIdNum];
        /// M: MobileActivityIcon
        mMobileActivityIconId = new IconIdWrapper[mSlotCount];
        mLastMobileActivityIconId = new int[mSlotCount];
        for (int i = 0 ; i < mSlotCount ; i++) {
            mDataNetType[i] = TelephonyManager.NETWORK_TYPE_UNKNOWN;
            mDataState[i] = TelephonyManager.DATA_DISCONNECTED;
            mSimState[i] = IccCardConstants.State.READY;
            //mDataIconList[i] = TelephonyIcons.DATA_G[0];
            mDataActivity[i] = TelephonyManager.DATA_ACTIVITY_NONE;
            mNetworkName[i] = mNetworkNameDefault;
            //mLastPhoneSignalIconId[i] = -1;
            mLastDataDirectionIconId[i] = -1;
            mLastDataTypeIconId[i] = -1;
            /// M: Support "SystemUI - VoLTE icon".
            mIMSState[i] = 0;
            /// M:MobileActivityIcon
            mLastMobileActivityIconId[i] = -1;
            /// M: IconIdWrapper.
            mMobileActivityIconId[i] = new IconIdWrapper();
            mDataTypeIconId[i] = new IconIdWrapper(0);
            mDataTypeIconIdR[i] = new IconIdWrapper(0);
            mDataSignalIconId[i] = new IconIdWrapper(0);
            for (int j = 0; j < TelephonyIcons.DATA_G.length ; j++) {
                mDataIconList[i][j] = new IconIdWrapper(0);
                mDataIconList[i][j].setResources(null);
                mDataIconList[i][j].setIconId(TelephonyIcons.DATA_G[mInetCondition][0]);
            }
            for (int j = 0; j < mPhoneSignalIconIdNum ; j++) {
                mPhoneSignalIconId[i][j] = new IconIdWrapper(0);
                mLastPhoneSignalIconId[i][j] = -1;
            }
        }
        /// M: config for show the ! icon or not . @{
        TelephonyIcons.initTelephonyIcon();
        WifiIcons.initWifiIcon();
        /// M: config for show the ! icon or not . @}


        // wifi
        mWifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        Handler handler = new WifiHandler();
        mWifiChannel = new AsyncChannel();
        Messenger wifiMessenger = mWifiManager.getWifiServiceMessenger();
        if (wifiMessenger != null) {
            mWifiChannel.connect(mContext, handler, wifiMessenger);
        }

        // broadcasts
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.RSSI_CHANGED_ACTION);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        filter.addAction(TelephonyIntents.SPN_STRINGS_UPDATED_ACTION);
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION_IMMEDIATE);
        filter.addAction(ConnectivityManager.INET_CONDITION_ACTION);
        filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
        filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        /// M: Support "SystemUI - VoLTE icon".
        filter.addAction(TelephonyIntents.ACTION_IMS_STATE_CHANGED);
        mWimaxSupported = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_wimaxEnabled);
        if(mWimaxSupported) {
            filter.addAction(WimaxManagerConstants.WIMAX_NETWORK_STATE_CHANGED_ACTION);
            filter.addAction(WimaxManagerConstants.SIGNAL_LEVEL_CHANGED_ACTION);
            filter.addAction(WimaxManagerConstants.NET_4G_STATE_CHANGED_ACTION);
        }
        filter.addAction(TelephonyIntents.ACTION_SUBINFO_CONTENT_CHANGE);
        filter.addAction(TelephonyIntents.ACTION_SUBINFO_RECORD_UPDATED);
        /// M: Support "IPO". @{
        filter.addAction(ACTION_BOOT_IPO);
        filter.addAction("android.intent.action.ACTION_SHUTDOWN_IPO");
        /// M: Support "IPO". @}
        context.registerReceiver(this, filter);

        // AIRPLANE_MODE_CHANGED is sent at boot; we've probably already missed it
        updateAirplaneMode();

        mLastLocale = mContext.getResources().getConfiguration().locale;
        mAccessPoints = new WifiAccessPointController(mContext);
        mMobileDataController = new MobileDataController(mContext);
        mMobileDataController.setCallback(new MobileDataController.Callback() {
            @Override
            public void onMobileDataEnabled(boolean enabled) {
                notifyMobileDataEnabled(enabled);
            }
        });
    }

     private void registerPhoneStateListener() {
        for (int i = 0 ; i < mSlotCount ; i++) {
            final long subId = SIMHelper.getFirstSubInSlot(i);
            if (subId >= 0) {
                mPhoneStateListener[i] = getPhoneStateListener(subId, i);
                mPhone.listen(mPhoneStateListener[i],
                    PhoneStateListener.LISTEN_SERVICE_STATE
                  | PhoneStateListener.LISTEN_SIGNAL_STRENGTHS
                  | PhoneStateListener.LISTEN_CALL_STATE
                  | PhoneStateListener.LISTEN_DATA_CONNECTION_STATE
                  | PhoneStateListener.LISTEN_DATA_ACTIVITY);
                Xlog.d(TAG, "Register PhoneStateListener");
            } else {
                mPhoneStateListener[i] = null;
            }
        }
    }

    private void unregisterPhoneStateListener() {
        for (int i = 0 ; i < mSlotCount ; i++) {
            if (mPhoneStateListener[i] != null) {
                mPhone.listen(mPhoneStateListener[i], PhoneStateListener.LISTEN_NONE);
            }
        }
    }

    private void notifyMobileDataEnabled(boolean enabled) {
        for (NetworkSignalChangedCallback cb : mSignalsChangedCallbacks) {
            cb.onMobileDataEnabled(enabled);
        }
    }

    public boolean hasMobileDataFeature() {
        return mHasMobileDataFeature;
    }

    public boolean hasVoiceCallingFeature() {
        return mPhone.getPhoneType() != TelephonyManager.PHONE_TYPE_NONE;
    }

    public boolean isEmergencyOnly() {
        return (mServiceState[SIMHelper.SLOT_INDEX_DEFAULT] != null
            && mServiceState[SIMHelper.SLOT_INDEX_DEFAULT].isEmergencyOnly());
    }

    public boolean isEmergencyOnly(int slotId) {
        return (mServiceState[slotId] != null
            && mServiceState[slotId].isEmergencyOnly());
    }

    public void addCombinedLabelView(TextView v) {
        mCombinedLabelViews.add(v);
    }

    public void addMobileLabelView(TextView v) {
        mMobileLabelViews.add(v);
    }

    public void addWifiLabelView(TextView v) {
        mWifiLabelViews.add(v);
    }

    public void addEmergencyLabelView(StatusBarHeaderView v) {
        mEmergencyViews.add(v);
    }

    public void addSignalCluster(SignalCluster cluster) {
        mSignalClusters.add(cluster);
        refreshSignalCluster(cluster);
    }

    public void addNetworkSignalChangedCallback(NetworkSignalChangedCallback cb) {
        mSignalsChangedCallbacks.add(cb);
        notifySignalsChangedCallbacks(cb);
    }

    public void removeNetworkSignalChangedCallback(NetworkSignalChangedCallback cb) {
        mSignalsChangedCallbacks.remove(cb);
    }

    @Override
    public void addAccessPointCallback(AccessPointCallback callback) {
        mAccessPoints.addCallback(callback);
    }

    @Override
    public void removeAccessPointCallback(AccessPointCallback callback) {
        mAccessPoints.removeCallback(callback);
    }

    @Override
    public void scanForAccessPoints() {
        mAccessPoints.scan();
    }

    @Override
    public void connect(AccessPoint ap) {
        mAccessPoints.connect(ap);
    }

    @Override
    public void setWifiEnabled(final boolean enabled) {
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... args) {
                // Disable tethering if enabling Wifi
                final int wifiApState = mWifiManager.getWifiApState();
                if (enabled && ((wifiApState == WifiManager.WIFI_AP_STATE_ENABLING) ||
                               (wifiApState == WifiManager.WIFI_AP_STATE_ENABLED))) {
                    mWifiManager.setWifiApEnabled(null, false);
                }

                mWifiManager.setWifiEnabled(enabled);
                return null;
            }
        }.execute();
    }

    @Override
    public DataUsageInfo getDataUsageInfo() {
        final DataUsageInfo info =  mMobileDataController.getDataUsageInfo();
        if (info == null)
            return null;
        // SUB SELECT
        final long subId = SubscriptionManager.getDefaultDataSubId();
        final int slotId = SubscriptionManager.getSlotId(subId);
        info.carrier = mNetworkName[slotId];
        return info;
    }

    @Override
    public boolean isMobileDataSupported() {
        return mMobileDataController.isMobileDataSupported();
    }

    @Override
    public boolean isMobileDataEnabled() {
        return mMobileDataController.isMobileDataEnabled();
    }

    @Override
    public void setMobileDataEnabled(boolean enabled) {
        mMobileDataController.setMobileDataEnabled(enabled);
    }

    private boolean isTypeIconWide(int iconId) {
        return TelephonyIcons.ICON_LTE == iconId || TelephonyIcons.ICON_1X == iconId
                || TelephonyIcons.ICON_3G == iconId || TelephonyIcons.ICON_4G == iconId;
    }

    private boolean isQsTypeIconWide(int iconId) {
        return TelephonyIcons.QS_ICON_LTE == iconId || TelephonyIcons.QS_ICON_1X == iconId
                || TelephonyIcons.QS_ICON_3G == iconId || TelephonyIcons.QS_ICON_4G == iconId;
    }

    public void refreshSignalCluster(SignalCluster cluster) {
        if (mDemoMode) return;
        cluster.setWifiIndicators(
                // only show wifi in the cluster if connected or if wifi-only
                mWifiEnabled && (mWifiConnected || !mHasMobileDataFeature),
                mWifiIconId,
                mContentDescriptionWifi);

        if (mIsWimaxEnabled && mWimaxConnected) {
            // wimax is special
            for (int i = 0; i < mSlotCount ; i++) {
                cluster.setMobileDataIndicators(
                    i,
                    true,
                    //mAlwaysShowCdmaRssi ? mPhoneSignalIconId[i] : mWimaxIconId,
                    mAlwaysShowCdmaRssi ? mPhoneSignalIconId[i] :
                        new IconIdWrapper[]{new IconIdWrapper(mWimaxIconId), new IconIdWrapper()},
                    mMobileActivityIconId[i],
                    mDataTypeIconId[i],
                    mContentDescriptionWimax,
                    mContentDescriptionDataType[i],
                    mDataTypeIconId[i].getIconId() == TelephonyIcons.ROAMING_ICON,
                    false /* isTypeIconWide */ );
            }
        } else {
            // normal mobile data
            for (int i = 0; i < mSlotCount ; i++) {
                cluster.setMobileDataIndicators(
                    i,
                    mHasMobileDataFeature,
                    //mShowPhoneRSSIForData ? mPhoneSignalIconId[i] : mDataSignalIconId[i],
                    mPhoneSignalIconId[i],
                    mMobileActivityIconId[i],
                    mDataTypeIconId[i],
                    mContentDescriptionPhoneSignal[i],
                    mContentDescriptionDataType[i],
                    mDataTypeIconId[i].getIconId() == TelephonyIcons.ROAMING_ICON,
                    isTypeIconWide(mDataTypeIconId[i].getIconId()));
            }
        }
        cluster.setIsAirplaneMode(mAirplaneMode, mAirplaneIconId);
    }

    void notifySignalsChangedCallbacks(NetworkSignalChangedCallback cb) {
        // SUB SELECT
        final long subId = SubscriptionManager.getDefaultDataSubId();
        final int slotId = SubscriptionManager.getSlotId(subId);
        // only show wifi in the cluster if connected or if wifi-only
        boolean wifiEnabled = mWifiEnabled && (mWifiConnected || !mHasMobileDataFeature);
        String wifiDesc = wifiEnabled ?
                mWifiSsid : null;
        boolean wifiIn = wifiEnabled && mWifiSsid != null
                && (mWifiActivity == WifiManager.DATA_ACTIVITY_INOUT
                || mWifiActivity == WifiManager.DATA_ACTIVITY_IN);
        boolean wifiOut = wifiEnabled && mWifiSsid != null
                && (mWifiActivity == WifiManager.DATA_ACTIVITY_INOUT
                || mWifiActivity == WifiManager.DATA_ACTIVITY_OUT);
        cb.onWifiSignalChanged(mWifiEnabled, mWifiConnected, mQSWifiIconId, wifiIn, wifiOut,
                mContentDescriptionWifi, wifiDesc);

        if (!isValidSlotId(slotId)) {
            cb.onMobileDataSignalChanged(subId, mHasMobileDataFeature, 0,
                    null, 0, false, false,mAirplaneMode, false, false, null, mNetworkNameDefault, mNoSim, false);
        } else {
            boolean mobileIn = mDataConnected[slotId]
                && (mDataActivity[slotId] == TelephonyManager.DATA_ACTIVITY_INOUT
                    || mDataActivity[slotId] == TelephonyManager.DATA_ACTIVITY_IN);
            boolean mobileOut = mDataConnected[slotId]
                && (mDataActivity[slotId] == TelephonyManager.DATA_ACTIVITY_INOUT
                    || mDataActivity[slotId] == TelephonyManager.DATA_ACTIVITY_OUT);
					Log.e("xdd","case 1 mobileIn =" + mobileIn );
					Log.e("xdd","case 1 mobileOut =" + mobileOut );
            if (isEmergencyOnly(slotId)) {
				Log.e("xdd","case 1");
                cb.onMobileDataSignalChanged(subId, false, mQSPhoneSignalIconId[slotId],
                        mContentDescriptionPhoneSignal[slotId], mQSDataTypeIconId[slotId], false, false, mAirplaneMode,mobileIn,
                        mobileOut, mContentDescriptionDataType[slotId], null, mNoSim, isQsTypeIconWide(mQSDataTypeIconId[slotId]));
            } else {
				Log.e("xdd","case 2");
                if (mIsWimaxEnabled && mWimaxConnected) {
					Log.e("xdd","case 2 1");
                    // Wimax is special
                    cb.onMobileDataSignalChanged(subId, true, mQSPhoneSignalIconId[slotId],
                            mContentDescriptionPhoneSignal[slotId], mQSDataTypeIconId[slotId], false, false, mAirplaneMode,
                            mobileIn, mobileOut, mContentDescriptionDataType[slotId],
                            mNetworkName[slotId], mNoSim, isQsTypeIconWide(mQSDataTypeIconId[slotId]));
                    
                } else {
					Log.e("xdd","case 2 2");
                    // Normal mobile data
                    cb.onMobileDataSignalChanged(subId, mHasMobileDataFeature,
                            mQSPhoneSignalIconId[slotId], mContentDescriptionPhoneSignal[slotId],
                            isRoaming(slotId) ? mQSDataTypeIconIdR[slotId] : mQSDataTypeIconId[slotId], 
                            		isRoaming(slotId), isDataConnected(slotId), mAirplaneMode, mobileIn, mobileOut,
                            mContentDescriptionDataType[slotId], mNetworkName[slotId], mNoSim, isQsTypeIconWide(mQSDataTypeIconId[slotId]));
                }
            }
        }
        cb.onAirplaneModeChanged(mAirplaneMode);
    }

    public void setStackedMode(boolean stacked) {
        mDataAndWifiStacked = true;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        if (DEBUG) Log.d(TAG, "onReceive, intent action = " + action);
        if (action.equals(WifiManager.RSSI_CHANGED_ACTION)
                || action.equals(WifiManager.WIFI_STATE_CHANGED_ACTION)
                || action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
            updateWifiState(intent);
            refreshViews();
        } else if (action.equals(TelephonyIntents.ACTION_SIM_STATE_CHANGED)) {
            int slotId = intent.getIntExtra(PhoneConstants.SLOT_KEY,
                SIMHelper.INVALID_SLOT_ID);
            if (isValidSlotId(slotId)) {
                updateTelephonySignalStrength();
                updateDataNetType(slotId);
                updateSimState(slotId, intent);
                updateDataIcon(slotId);
                refreshViews(slotId);
            }
        } else if (action.equals(TelephonyIntents.SPN_STRINGS_UPDATED_ACTION)) {
            long subId = intent.getLongExtra(PhoneConstants.SUBSCRIPTION_KEY,
                SubscriptionManager.INVALID_SUB_ID);
            int slotId = SubscriptionManager.getSlotId(subId);
            if (isValidSlotId(slotId)) {
                updateNetworkName(slotId,
                        intent.getBooleanExtra(TelephonyIntents.EXTRA_SHOW_SPN, false),
                        intent.getStringExtra(TelephonyIntents.EXTRA_SPN),
                        intent.getBooleanExtra(TelephonyIntents.EXTRA_SHOW_PLMN, false),
                        intent.getStringExtra(TelephonyIntents.EXTRA_PLMN));
                refreshViews(slotId);
            }
        } else if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION_IMMEDIATE) ||
                 action.equals(ConnectivityManager.INET_CONDITION_ACTION)) {
            updateConnectivity(intent);
            refreshViews();
        } else if (action.equals(Intent.ACTION_CONFIGURATION_CHANGED)) {
            refreshLocale();
            refreshViews();
        } else if (action.equals(Intent.ACTION_AIRPLANE_MODE_CHANGED)) {
            refreshLocale();
            /// M: [ALPS01794897] Get the airplane mode from intent to avoid DB delay.
            mAirplaneMode = intent.getBooleanExtra("state", false);
            //updateAirplaneMode();
            updateTelephonySignalStrength();
            refreshViews();
        } else if (action.equals(WimaxManagerConstants.NET_4G_STATE_CHANGED_ACTION) ||
                action.equals(WimaxManagerConstants.SIGNAL_LEVEL_CHANGED_ACTION) ||
                action.equals(WimaxManagerConstants.WIMAX_NETWORK_STATE_CHANGED_ACTION)) {
            updateWimaxState(intent);
            refreshViews();
        } else if (action.equals(TelephonyIntents.ACTION_SUBINFO_CONTENT_CHANGE)) {
            SIMHelper.updateSIMInfos(context);
            updateDataNetType();
            updateTelephonySignalStrength();
            refreshViews();
        } else if (action.equals(TelephonyIntents.ACTION_SUBINFO_RECORD_UPDATED)) {
            unregisterPhoneStateListener();
            SIMHelper.updateSIMInfos(context);
            registerPhoneStateListener();
            updateDataNetType();
            updateTelephonySignalStrength();
            refreshViews();
        }
        /// M: Support "SystemUI - VoLTE icon". @{
        else if (action.equals(TelephonyIntents.ACTION_IMS_STATE_CHANGED)) {
            // TelephonyIntents.ACTION_IMS_STATE_CHANGED = "android.intent.action.IMS_SERVICE_STATE"
            // TelephonyIntents.EXTRA_IMS_REG_STATE_KEY = "regState"
            int reg = intent.getIntExtra(TelephonyIntents.EXTRA_IMS_REG_STATE_KEY , -1);
            int simID = intent.getIntExtra(PhoneConstants.SLOT_KEY, -1);
            Xlog.d(TAG, "onReceive from TelephonyIntents.ACTION_IMS_STATE_CHANGED: reg=" + reg + "SimID=" + simID);
            if (reg == -1 || simID == -1) {
                return;
            }
            mIMSState[simID] = reg;
            updateVoLTE();
        }
        /// M: Support "SystemUI - VoLTE icon". @}
        /// M: Support "IPO". @{
        else if (action.equals(ACTION_BOOT_IPO)) {
            refreshLocale();
            updateAirplaneMode();
            refreshViews();
        } else if (action.equals("android.intent.action.ACTION_SHUTDOWN_IPO")) {
        }
        /// M: Support "IPO". @}
    }


    // ===== Telephony ==============================================================

    private PhoneStateListener getPhoneStateListener(long subId, final int slotId) {
        return new PhoneStateListener(subId) {
            @Override
            public void onSignalStrengthsChanged(SignalStrength signalStrength) {
                if (DEBUG) {
                    Log.d(TAG, "PhoneStateListener:onSignalStrengthsChanged, slot "
                        + slotId + " before.");
                    Log.d(TAG, "onSignalStrengthsChanged signalStrength=" + signalStrength +
                        ((signalStrength == null) ? "" : (" level=" + signalStrength.getLevel())));
                }

                if (isValidSlotId(slotId)) {
                    mSignalStrength[slotId] = signalStrength;
                    updateTelephonySignalStrength(slotId);
                    refreshViews(slotId);
                }

                if (DEBUG) {
                    Log.d(TAG, "PhoneStateListener:onSignalStrengthsChanged, slot "
                        + slotId + " after.");
                }
            }

            @Override
            public void onServiceStateChanged(ServiceState state) {
                if (DEBUG) {
                    Log.d(TAG, "PhoneStateListener:onServiceStateChanged, slot "
                        + slotId + " before.");
                    Log.d(TAG, "PhoneStateListener:onServiceStateChanged voiceState="
                        + state.getVoiceRegState() + " dataState=" + state.getDataRegState());
                    Log.d(TAG, "PhoneStateListener:onServiceStateChanged slot "
                        + slotId
                        + ", voiceNWType: " + state.getVoiceNetworkType()
                        + ", dataNWType: " + state.getDataNetworkType());
                }

                if (isValidSlotId(slotId)) {
                    mServiceState[slotId] = state;
                    updateTelephonySignalStrength(slotId);
                    updateDataNetType(slotId);
                    updateDataIcon(slotId);
                    refreshViews(slotId);
                    /// M: Support "SystemUI - VoLTE icon".
                    updateVoLTE();
                }

                if (DEBUG) {
                    Log.d(TAG, "PhoneStateListener:onServiceStateChanged, slot "
                        + slotId + " after.");
                }
            }

            @Override
            public void onCallStateChanged(int state, String incomingNumber) {
                if (DEBUG) {
                    Log.d(TAG, "PhoneStateListener:onCallStateChanged, slot "
                        + slotId + " before.");
                    Log.d(TAG, "PhoneStateListener:onCallStateChanged, state=" + state);
                }

                if (isValidSlotId(slotId)) {
                    // In cdma, if a voice call is made, RSSI should switch to 1x.
                    if (isCdma(slotId)) {
                        updateTelephonySignalStrength(slotId);
                        refreshViews(slotId);
                    }
                }

                if (DEBUG) {
                    Log.d(TAG, "PhoneStateListener:onCallStateChanged, slot "
                        + slotId + " after.");
                }
            }

            @Override
            public void onDataConnectionStateChanged(int state, int networkType) {
                if (DEBUG) {
                    Log.d(TAG, "PhoneStateListener:onDataConnectionStateChanged, slot "
                        + slotId + " before.");
                    Log.d(TAG, "PhoneStateListener:onDataConnectionStateChanged, state="
                        + state + " type=" + networkType);
                }

                if (isValidSlotId(slotId)) {
                    mDataState[slotId] = state;
                    mDataNetType[slotId] = networkType;
                    updateDataNetType(slotId);
                    updateDataIcon(slotId);
                    refreshViews(slotId);
                    updateVoLTE();
                }

                if (DEBUG) {
                    Log.d(TAG, "PhoneStateListener:onDataConnectionStateChanged, slot "
                        + slotId + " after.");
                }
            }

            @Override
            public void onDataActivity(int direction) {
                if (DEBUG) {
                    Log.d(TAG, "PhoneStateListener:onDataActivity, slot "
                        + slotId + " before.");
                    Log.d(TAG, "PhoneStateListener:onDataActivity, direction=" + direction);
                }

                if (isValidSlotId(slotId)) {
                    mDataActivity[slotId] = direction;
                    updateDataIcon(slotId);
                    refreshViews(slotId);
                }

                if (DEBUG) {
                    Log.d(TAG, "PhoneStateListener:onDataActivity, slot "
                        + slotId + " after.");
                }
            }
        };
    }

    private final void updateSimState(int slotId, Intent intent) {
        IccCardConstants.State tempSimState = null;

        String stateExtra = intent.getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE);
        if (IccCardConstants.INTENT_VALUE_ICC_ABSENT.equals(stateExtra)) {
            tempSimState = IccCardConstants.State.ABSENT;
        } else if (IccCardConstants.INTENT_VALUE_ICC_READY.equals(stateExtra)) {
            tempSimState = IccCardConstants.State.READY;
        } else if (IccCardConstants.INTENT_VALUE_ICC_LOCKED.equals(stateExtra)) {
            final String lockedReason
                    = intent.getStringExtra(IccCardConstants.INTENT_KEY_LOCKED_REASON);
            if (IccCardConstants.INTENT_VALUE_LOCKED_ON_PIN.equals(lockedReason)) {
                tempSimState = IccCardConstants.State.PIN_REQUIRED;
            } else if (IccCardConstants.INTENT_VALUE_LOCKED_ON_PUK.equals(lockedReason)) {
                tempSimState = IccCardConstants.State.PUK_REQUIRED;
            } else {
                tempSimState = IccCardConstants.State.NETWORK_LOCKED;
            }
        } else {
            tempSimState = IccCardConstants.State.UNKNOWN;
        }

        if (tempSimState != null) {
            mSimState[slotId] = tempSimState;
        }
    }

    private boolean isCdma(int slotId) {
        SignalStrength tempSignalStrength = mSignalStrength[slotId];

        return (tempSignalStrength != null) && !tempSignalStrength.isGsm();
    }

    private boolean hasService(int slotId) {
        return SIMHelper.hasService(mServiceState[slotId]);
    }

    private void updateAirplaneMode() {
        mAirplaneMode = (Settings.Global.getInt(mContext.getContentResolver(),
            Settings.Global.AIRPLANE_MODE_ON, 0) == 1);
    }

    private void refreshLocale() {
        mLocale = mContext.getResources().getConfiguration().locale;
    }

    private final void updateTelephonySignalStrength() {
        for (int i = 0 ; i < mSlotCount ; i++) {
            updateTelephonySignalStrength(i);
        }
    }

    private final void updateTelephonySignalStrength(int slotId) {
        /// M: IconIdWrapper.
        IconIdWrapper tempPhoneSignalIconId[] = {new IconIdWrapper(), new IconIdWrapper()};
        IconIdWrapper tempDataSignalIconId = new IconIdWrapper();

        if (DEBUG) {
            Log.d(TAG, "updateTelephonySignalStrength: hasService=" + hasService(slotId)
                    + " ss=" + mSignalStrength);
        }

        /// M: null signal state
        if (!SIMHelper.isSimInsertedBySlot(mContext, slotId)) {
            Xlog.d(TAG, "updateTelephonySignalStrength(" + slotId + "), is null signal.");

            tempPhoneSignalIconId[0].setResources(null);
            tempPhoneSignalIconId[0].setIconId(R.drawable.stat_sys_signal_null);
            PluginFactory.getStatusBarPlugin(mContext).customizeSignalStrengthNullIcon(slotId,
                    tempPhoneSignalIconId[0]);
            Xlog.d(TAG, "updateTelephonySignalStrength(" + slotId + "), null signal");
        }

        if (!hasService(slotId)) {
            if (CHATTY) Log.d(TAG, "updateTelephonySignalStrength: !hasService()");
            /// M: IconIdWrapper.
            tempPhoneSignalIconId[0].setResources(null);
            tempPhoneSignalIconId[0].setIconId(R.drawable.stat_sys_signal_null);
            PluginFactory.getStatusBarPlugin(mContext).customizeSignalStrengthNullIcon(slotId,
                    tempPhoneSignalIconId[0]);
            tempDataSignalIconId.setResources(null);
            tempDataSignalIconId.setIconId(R.drawable.stat_sys_signal_null);
            mQSPhoneSignalIconId[slotId] = R.drawable.ic_qs_signal_no_signal;
        } else {
            if (mSignalStrength[slotId] == null) {
                if (CHATTY) Log.d(TAG, "updateTelephonySignalStrength: mSignalStrength == null");
                /// M: IconIdWrapper.
                tempPhoneSignalIconId[0].setResources(null);
                tempPhoneSignalIconId[0].setIconId(R.drawable.stat_sys_signal_null);
                tempDataSignalIconId.setResources(null);
                tempDataSignalIconId.setIconId(R.drawable.stat_sys_signal_null);

                mQSPhoneSignalIconId[slotId] = R.drawable.ic_qs_signal_no_signal;
                mContentDescriptionPhoneSignal[slotId] = mContext.getString(
                        AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0]);
            } else {
                int iconLevel;
                int[] iconList;
                if (isCdma(slotId) && mAlwaysShowCdmaRssi) {
                    mLastSignalLevel = iconLevel = mSignalStrength[slotId].getCdmaLevel();
                    if (DEBUG) Log.d(TAG, "mAlwaysShowCdmaRssi=" + mAlwaysShowCdmaRssi
                            + " set to cdmaLevel=" + mSignalStrength[slotId].getCdmaLevel()
                            + " instead of level=" + mSignalStrength[slotId].getLevel());
                } else {
                    mLastSignalLevel = iconLevel = mSignalStrength[slotId].getLevel();
                }

                if (isRoaming(slotId)) {
                    iconList = TelephonyIcons.TELEPHONY_SIGNAL_STRENGTH_ROAMING[mInetCondition];
                } else {
                    iconList = TelephonyIcons.TELEPHONY_SIGNAL_STRENGTH[mInetCondition];
                }

                /// M: IconIdWrapper.
                tempPhoneSignalIconId[0].setResources(null);
                tempPhoneSignalIconId[0].setIconId(iconList[iconLevel]);
                tempDataSignalIconId = tempPhoneSignalIconId[0].clone();
                //mPhoneSignalIconId[slotId] = iconList[iconLevel];
                mQSPhoneSignalIconId[slotId] =
                        TelephonyIcons.QS_TELEPHONY_SIGNAL_STRENGTH[mInetCondition][iconLevel];
                mContentDescriptionPhoneSignal[slotId] = mContext.getString(
                        AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[iconLevel]);
                //mDataSignalIconId[slotId] = mPhoneSignalIconId[slotId];
                if (DEBUG) Log.d(TAG, "updateTelephonySignalStrength: iconLevel=" + iconLevel);

                /// M: Support "CMCC Performance test on SystemUI Statusbar". @{
                Xlog.i(TAG, "[CMCC Performance test][SystemUI][Statusbar] show signal end ["
                        + SystemClock.elapsedRealtime() + "]");
                /// M: Support "CMCC Performance test on SystemUI Statusbar". @}
            }
        }

        mDataSignalIconId[slotId] = tempDataSignalIconId.clone();
        mPhoneSignalIconId[slotId][0] = tempPhoneSignalIconId[0].clone();
        mPhoneSignalIconId[slotId][1] = tempPhoneSignalIconId[1].clone();
    }

    private int inetConditionForNetwork(int networkType) {
        return (mInetCondition == 1 && mConnectedNetworkType == networkType) ? 1 : 0;
    }

    private final void updateDataNetType() {
        for (int i = 0; i < mSlotCount; i++) {
            updateDataNetType(i);
        }
    }

    /// M: Support "Service Network Type on Statusbar". @{
    private final int getNWTypeByPriority(int cs, int ps) {
        /// By Network Class.
        if (TelephonyManager.getNetworkClass(cs) > TelephonyManager.getNetworkClass(ps)) {
            return cs;
        } else {
            return ps;
        }
    }
    /// M: Support "Service Network Type on Statusbar". @}

    private final void updateDataNetType(int slotId) {
        int inetCondition = 0;
        boolean showDataTypeIcon = true;
        mQSDataTypeIconId[slotId] = 0;
        mQSDataTypeIconIdR[slotId] = 0;
        NetworkType tempNetworkType = NetworkType.Type_G; //Big
        DataType tempDataType; //Small
        int tempDataNetType; //Big - switch

        /// M: Support "Service Network Type on Statusbar". @{
        /// M: [ALPS01799427] If Data state is invalid, set from service state's data type.
        int networkTypeData = mDataNetType[slotId]; //Big - Data
        if (mDataState[slotId] == TelephonyManager.DATA_UNKNOWN && mServiceState[slotId] != null) {
            Xlog.d(TAG, "updateDataNetType(" + slotId + "), mDataState[slotId]==-1 : "
                + mDataNetType[slotId] + " / " + mServiceState[slotId].getDataNetworkType());
            networkTypeData = mServiceState[slotId].getDataNetworkType();
        }
        if (mServiceState[slotId] != null) {
            tempDataNetType = getNWTypeByPriority(mServiceState[slotId].getVoiceNetworkType(),
                                                  networkTypeData);
        } else {
            tempDataNetType = networkTypeData;
        }
        /// M: Support "Service Network Type on Statusbar". @}

        Xlog.d(TAG, "updateDataNetType(" + slotId + "), DataNetType=" + mDataNetType[slotId] + " / " + tempDataNetType);
        if (isRoaming(slotId)) {
            mIsRoaming[slotId] = true;
        } else {
            mIsRoaming[slotId] = false;
        }

        if (mIsWimaxEnabled && mWimaxConnected) {
            // wimax is a special 4g network not handled by telephony
            inetCondition = inetConditionForNetwork(ConnectivityManager.TYPE_WIMAX);
            tempDataTypeL = tempDataType = DataType.Type_4G;
            //mDataIconList[slotId] = TelephonyIcons.DATA_4G[inetCondition];
            //mDataTypeIconId[slotId] = R.drawable.stat_sys_data_fully_connected_4g;
            mQSDataTypeIconId[slotId] = TelephonyIcons.QS_DATA_4G[inetCondition];
            mQSDataTypeIconIdR[slotId] = TelephonyIcons.QS_DATA_4G[inetCondition];
            mContentDescriptionDataType[slotId] = mContext.getString(
                    R.string.accessibility_data_connection_4g);
        } else {
            inetCondition = inetConditionForNetwork(ConnectivityManager.TYPE_MOBILE);
            showDataTypeIcon = (inetCondition > 0);
            switch (mDataNetType[slotId]) {
                case TelephonyManager.NETWORK_TYPE_UNKNOWN:
                    if (!mShowAtLeastThreeGees) {
                    	tempDataTypeL = tempDataType = DataType.Type_G;
                        
                        //mDataIconList[slotId] = TelephonyIcons.DATA_G[inetCondition];
                        mContentDescriptionDataType[slotId] = mContext.getString(
                                R.string.accessibility_data_connection_gprs);
                        break;
                    } else {
                        // fall through
                    }
                case TelephonyManager.NETWORK_TYPE_EDGE:
                    if (!mShowAtLeastThreeGees) {
                    	tempDataTypeL = tempDataType = DataType.Type_E;
                        //mDataIconList[slotId] = TelephonyIcons.DATA_E[inetCondition];
                        //mDataTypeIconId[slotId] = showDataTypeIcon ?
                        //    R.drawable.stat_sys_data_fully_connected_e : 0;
                        mQSDataTypeIconId[slotId] = TelephonyIcons.QS_DATA_E[inetCondition];
                        mQSDataTypeIconIdR[slotId] = TelephonyIcons.QS_DATA_E[inetCondition];
                        mContentDescriptionDataType[slotId] = mContext.getString(
                                R.string.accessibility_data_connection_edge);
                        break;
                    } else {
                        // fall through
                    }
                case TelephonyManager.NETWORK_TYPE_UMTS:
                    tempDataType = PluginFactory.getStatusBarPlugin(mContext).customizeDataType(
                          mIsRoaming[slotId], TelephonyManager.NETWORK_TYPE_UMTS, DataType.Type_3G);
                    tempDataTypeL = PluginFactory.getStatusBarPlugin(mContext).customizeDataType(
                            false, TelephonyManager.NETWORK_TYPE_UMTS, DataType.Type_3G);
                    //mDataIconList[slotId] = TelephonyIcons.DATA_3G[inetCondition];
                    //mDataTypeIconId[slotId] = showDataTypeIcon ?
                    //    R.drawable.stat_sys_data_fully_connected_3g : 0;
                    mQSDataTypeIconId[slotId] = TelephonyIcons.QS_DATA_3G[inetCondition];
                    mQSDataTypeIconIdR[slotId] = TelephonyIcons.QS_DATA_3G[inetCondition];

                    mContentDescriptionDataType[slotId] = mContext.getString(
                            R.string.accessibility_data_connection_3g);
                    break;
                case TelephonyManager.NETWORK_TYPE_HSDPA:
                case TelephonyManager.NETWORK_TYPE_HSUPA:
                case TelephonyManager.NETWORK_TYPE_HSPA:
                case TelephonyManager.NETWORK_TYPE_HSPAP:
                    if (mHspaDataDistinguishable) {
                    	tempDataTypeL = tempDataType = DataType.Type_H_PLUS;

                        /// M: Support "Operator plugin - Data icon". @{
                        if (tempDataNetType == TelephonyManager.NETWORK_TYPE_HSDPA
                                || tempDataNetType == TelephonyManager.NETWORK_TYPE_HSUPA
                                || tempDataNetType == TelephonyManager.NETWORK_TYPE_HSPA) {
                        	tempDataTypeL = tempDataType = DataType.Type_H;
                        }
                        /// M: Support "Operator plugin - Data icon". @}

                        //mDataIconList[slotId] = TelephonyIcons.DATA_H[inetCondition];
                        //mDataTypeIconId[slotId] = showDataTypeIcon ?
                        //    R.drawable.stat_sys_data_fully_connected_h : 0;
                        mQSDataTypeIconId[slotId] = TelephonyIcons.QS_DATA_H[inetCondition];
                        mQSDataTypeIconIdR[slotId] = TelephonyIcons.QS_DATA_H[inetCondition];
                        mContentDescriptionDataType[slotId] = mContext.getString(
                                R.string.accessibility_data_connection_3_5g);
                    } else {
                        tempDataType = PluginFactory.getStatusBarPlugin(mContext).customizeDataType(
                            mIsRoaming[slotId], tempDataNetType, DataType.Type_3G);
                        tempDataTypeL = PluginFactory.getStatusBarPlugin(mContext).customizeDataType(
                                false, tempDataNetType, DataType.Type_3G);
                        //mDataIconList[slotId] = TelephonyIcons.DATA_3G[inetCondition];
                        //mDataTypeIconId[slotId] = showDataTypeIcon ?
                        //    R.drawable.stat_sys_data_fully_connected_3g : 0;
                        mQSDataTypeIconId[slotId] = TelephonyIcons.QS_DATA_3G[inetCondition];
                        mQSDataTypeIconIdR[slotId] = TelephonyIcons.QS_DATA_3G[inetCondition];
                        mContentDescriptionDataType[slotId] = mContext.getString(
                                R.string.accessibility_data_connection_3g);
                    }
                    break;
                case TelephonyManager.NETWORK_TYPE_CDMA:
                	tempDataTypeL = tempDataType = DataType.Type_1X;
                    if (!mShowAtLeastThreeGees) {
                        // display 1xRTT for IS95A/B
                        //mDataIconList[slotId] = TelephonyIcons.DATA_1X[inetCondition];
                        //mDataTypeIconId[slotId] = showDataTypeIcon ?
                        //    R.drawable.stat_sys_data_fully_connected_1x : 0;
                        mQSDataTypeIconId[slotId] = TelephonyIcons.QS_DATA_1X[inetCondition];
                        mQSDataTypeIconIdR[slotId] = TelephonyIcons.QS_DATA_1X[inetCondition];
                        mContentDescriptionDataType[slotId] = mContext.getString(
                                R.string.accessibility_data_connection_cdma);
                        break;
                    } else {
                        // fall through
                    }
                case TelephonyManager.NETWORK_TYPE_1xRTT:
                	tempDataTypeL = tempDataType = DataType.Type_1X;
                    if (!mShowAtLeastThreeGees) {
                        //mDataIconList[slotId] = TelephonyIcons.DATA_1X[inetCondition];
                        //mDataTypeIconId[slotId] = showDataTypeIcon ?
                        //    R.drawable.stat_sys_data_fully_connected_1x : 0;
                        mQSDataTypeIconId[slotId] = TelephonyIcons.QS_DATA_1X[inetCondition];
                        mQSDataTypeIconIdR[slotId] = TelephonyIcons.QS_DATA_1X[inetCondition];
                        mContentDescriptionDataType[slotId] = mContext.getString(
                                R.string.accessibility_data_connection_cdma);
                        break;
                    } else {
                        // fall through
                    }
                case TelephonyManager.NETWORK_TYPE_EVDO_0: //fall through
                case TelephonyManager.NETWORK_TYPE_EVDO_A:
                case TelephonyManager.NETWORK_TYPE_EVDO_B:
                case TelephonyManager.NETWORK_TYPE_EHRPD:
                	tempDataTypeL = tempDataType = DataType.Type_3G;
                    //mDataIconList[slotId] = TelephonyIcons.DATA_3G[inetCondition];
                    //mDataTypeIconId[slotId] = showDataTypeIcon ?
                    //    R.drawable.stat_sys_data_fully_connected_3g : 0;
                    mQSDataTypeIconId[slotId] = TelephonyIcons.QS_DATA_3G[inetCondition];
                    mQSDataTypeIconIdR[slotId] = TelephonyIcons.QS_DATA_3G[inetCondition];

                    mContentDescriptionDataType[slotId] = mContext.getString(
                            R.string.accessibility_data_connection_3g);
                    break;
                case TelephonyManager.NETWORK_TYPE_LTE:
                	tempDataTypeL = tempDataType = DataType.Type_4G;
                    boolean show4GforLTE
                            = mContext.getResources().getBoolean(R.bool.config_show4GForLTE);
                    if (show4GforLTE) {
                        //mDataIconList[slotId] = TelephonyIcons.DATA_4G[inetCondition];
                        //mDataTypeIconId[slotId] = showDataTypeIcon ?
                        //    R.drawable.stat_sys_data_fully_connected_4g : 0;
                        mQSDataTypeIconId[slotId] = TelephonyIcons.QS_DATA_4G[inetCondition];
                        mQSDataTypeIconIdR[slotId] = TelephonyIcons.QS_DATA_4G[inetCondition];
                        mContentDescriptionDataType[slotId] = mContext.getString(
                                R.string.accessibility_data_connection_4g);
                    } else {
                        //mDataIconList[slotId] = TelephonyIcons.DATA_LTE[inetCondition];
                        //mDataTypeIconId[slotId] = showDataTypeIcon ?
                        //    TelephonyIcons.ICON_LTE : 0;
                        mQSDataTypeIconId[slotId] = TelephonyIcons.QS_DATA_LTE[inetCondition];
                        mQSDataTypeIconIdR[slotId] = TelephonyIcons.QS_DATA_LTE[inetCondition];

                        mContentDescriptionDataType[slotId] = mContext.getString(
                                R.string.accessibility_data_connection_lte);
                    }
                    break;
                default:
                    if (!mShowAtLeastThreeGees) {
                    	tempDataTypeL = tempDataType = DataType.Type_G;
                        //mDataIconList[slotId] = TelephonyIcons.DATA_G[inetCondition];
                        //mDataTypeIconId[slotId] = showDataTypeIcon ?
                        //    R.drawable.stat_sys_data_fully_connected_g : 0;
                        mQSDataTypeIconId[slotId] = TelephonyIcons.QS_DATA_G[inetCondition];
                        mQSDataTypeIconIdR[slotId] = TelephonyIcons.QS_DATA_G[inetCondition];

                        mContentDescriptionDataType[slotId] = mContext.getString(
                                R.string.accessibility_data_connection_gprs);
                    } else {
                    	tempDataTypeL = tempDataType = DataType.Type_3G;
                        //mDataIconList[slotId] = TelephonyIcons.DATA_3G[inetCondition];
                        //mDataTypeIconId[slotId] = showDataTypeIcon ?
                        //    R.drawable.stat_sys_data_fully_connected_3g : 0;
                        mQSDataTypeIconId[slotId] = TelephonyIcons.QS_DATA_3G[inetCondition];
                        mQSDataTypeIconIdR[slotId] = TelephonyIcons.QS_DATA_3G[inetCondition];

                        mContentDescriptionDataType[slotId] = mContext.getString(
                                R.string.accessibility_data_connection_3g);
                    }
                    break;
            }

            /// M: Support "Service Network Type on Statusbar". @{
            switch (tempDataNetType) {
                case TelephonyManager.NETWORK_TYPE_UNKNOWN:
                    if (!mShowAtLeastThreeGees) {
                        tempNetworkType = NetworkType.Type_G;
                        break;
                    } else {
                        // fall through
                    }
                case TelephonyManager.NETWORK_TYPE_EDGE:
                    if (!mShowAtLeastThreeGees) {
                        tempNetworkType = NetworkType.Type_E;
                        break;
                    } else {
                        // fall through
                    }
                case TelephonyManager.NETWORK_TYPE_UMTS:
                    tempNetworkType = PluginFactory.getStatusBarPlugin(mContext).customizeNetworkType(
                          mIsRoaming[slotId], TelephonyManager.NETWORK_TYPE_UMTS, NetworkType.Type_3G);
                    break;
                case TelephonyManager.NETWORK_TYPE_HSDPA:
                case TelephonyManager.NETWORK_TYPE_HSUPA:
                case TelephonyManager.NETWORK_TYPE_HSPA:
                case TelephonyManager.NETWORK_TYPE_HSPAP:
                    tempNetworkType = NetworkType.Type_3G;
                    if (mHspaDataDistinguishable) {
                    } else {
                        tempNetworkType = PluginFactory.getStatusBarPlugin(mContext).customizeNetworkType(
                            mIsRoaming[slotId], tempDataNetType, NetworkType.Type_3G);
                    }
                    break;
                case TelephonyManager.NETWORK_TYPE_CDMA:
                    tempNetworkType = NetworkType.Type_1X;
                    if (!mShowAtLeastThreeGees) {
                        break;
                    } else {
                        // fall through
                    }
                case TelephonyManager.NETWORK_TYPE_1xRTT:
                    tempNetworkType = NetworkType.Type_1X;
                    if (!mShowAtLeastThreeGees) {
                        break;
                    } else {
                        // fall through
                    }
                case TelephonyManager.NETWORK_TYPE_EVDO_0: //fall through
                case TelephonyManager.NETWORK_TYPE_EVDO_A:
                case TelephonyManager.NETWORK_TYPE_EVDO_B:
                case TelephonyManager.NETWORK_TYPE_EHRPD:
                    tempNetworkType = NetworkType.Type_1X3G;
                    break;
                case TelephonyManager.NETWORK_TYPE_LTE:
                    tempNetworkType = NetworkType.Type_4G;
                    break;
                default:
                    if (!mShowAtLeastThreeGees) {
                        tempNetworkType = NetworkType.Type_G;
                    } else {
                        tempNetworkType = NetworkType.Type_3G;
                    }
                    break;
            }
            /// M: Support "Service Network Type on Statusbar". @}
        }
        if (isRoaming(slotId)) {
            //mDataTypeIconId[slotId] = TelephonyIcons.ROAMING_ICON;
            //mQSDataTypeIconId[slotId] = TelephonyIcons.QS_DATA_R[mInetCondition];
            /// M:[ALPS01785746] Show R no matter what network status is.
            showDataTypeIcon = true;
        }
        if (!hasService(slotId)) {
            tempNetworkType = null;
        }

        /// M: config for show the ! icon or not . @{
        if (mShowNormalIcon) {
            showDataTypeIcon = true;
        }
        /// M: config for show the ! icon or not . @}

        /// M: For operator plugin's DataTypeIcon . @{
        IconIdWrapper[] tempDataIconList = {new IconIdWrapper(), new IconIdWrapper(), new IconIdWrapper(), new IconIdWrapper()};
        IconIdWrapper[] tempDataIconListR = {new IconIdWrapper(), new IconIdWrapper(), new IconIdWrapper(), new IconIdWrapper()};
        
        IconIdWrapper tempDataTypeIconId = new IconIdWrapper();
        IconIdWrapper tempDataTypeIconIdR = new IconIdWrapper();
        int [][] iconList = TelephonyIcons.getDataTypeIconListGemini(mIsRoaming[slotId], tempDataType);
        int [][] iconListR = TelephonyIcons.getDataTypeIconListGemini(false, tempDataTypeL);

        for (int i = 0; i < tempDataIconList.length; i++) {
            tempDataIconList[i].setResources(null);
            tempDataIconList[i].setIconId(iconList[inetCondition][i]);
        }
        
        for (int i = 0; i < tempDataIconListR.length; i++) {
            tempDataIconListR[i].setResources(null);
            tempDataIconListR[i].setIconId(iconListR[inetCondition][i]);
        }

        PluginFactory.getStatusBarPlugin(mContext).customizeDataTypeIcon(tempDataIconList[slotId],
                mIsRoaming[slotId], tempDataType);
        PluginFactory.getStatusBarPlugin(mContext).customizeDataTypeIcon(tempDataIconListR[slotId],
                false, tempDataTypeL);

        tempDataTypeIconId.setResources(showDataTypeIcon ?
                                tempDataIconList[slotId].getResources() : null);
        tempDataTypeIconId.setIconId(showDataTypeIcon ?
                                tempDataIconList[slotId].getIconId() : 0);
        
        tempDataTypeIconIdR.setResources(showDataTypeIcon ?
                tempDataIconListR[slotId].getResources() : null);
        tempDataTypeIconIdR.setIconId(showDataTypeIcon ?
                tempDataIconListR[slotId].getIconId() : 0);

        /// M:[ALPS01811472] small data type id need to be set to null if it is unknown type
        if ((((mDataNetType[slotId] == TelephonyManager.NETWORK_TYPE_UNKNOWN
            || !SIMHelper.isSimInsertedBySlot(mContext, slotId)) && !mShowAtLeastThreeGees)
            || mDataState[slotId] != TelephonyManager.DATA_CONNECTED)
            && mIsRoaming[slotId] == false) {
                tempDataTypeIconId.setResources(null);
                tempDataTypeIconId.setIconId(0);
            Xlog.d(TAG, "Set Data Type Icon ID to null");
        }
        /// M: For operator plugin's DataTypeIcon . @}

        mNetworkType[slotId] = tempNetworkType;
        mDataTypeIconId[slotId] = tempDataTypeIconId.clone();
        //mDataTypeIconIdR[slotId] =  tempDataTypeIconIdR.clone();
        mDataIconList[slotId] = tempDataIconList;

        Xlog.d(TAG, "updateDataNetType(" + slotId + ")"
            + ", mNetworkType=" + tempNetworkType
            + ", tempDataType= " + tempDataType
            + ", mDataTypeIconId= " + Integer.toHexString(mDataTypeIconId[slotId].getIconId())
            + ", mIsRoaming= " + mIsRoaming[slotId]
            + ", inetCondition= " + inetCondition
            + ", showDataTypeIcon= " + showDataTypeIcon + ".");
    }

    boolean isCdmaEri(int slotId) {
        ServiceState tempServiceState = mServiceState[slotId];

        if (tempServiceState != null) {
            final int iconIndex = tempServiceState.getCdmaEriIconIndex();
            if (iconIndex != EriInfo.ROAMING_INDICATOR_OFF) {
                final int iconMode = tempServiceState.getCdmaEriIconMode();
                if (iconMode == EriInfo.ROAMING_ICON_MODE_NORMAL
                        || iconMode == EriInfo.ROAMING_ICON_MODE_FLASH) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isRoaming(int slotId) {
        if (isCdma(slotId)) {
            return true;//isCdmaEri(slotId);
        } else {
            return true;//mServiceState[slotId] != null && mServiceState[slotId].getRoaming();
        }
    }

    private final void updateDataIcon() {
        for (int i = 0; i < mSlotCount ; i++) {
            updateDataIcon(i);
        }
    }

    private final void updateDataIcon(int slotId) {
        int iconId;
        boolean visible = true;

        Xlog.d(TAG, "updateDataIcon(" + slotId + "), SimState=" + mSimState[slotId] + ", DataState=" + mDataState[slotId] +
                ", DataActivity=" + mDataActivity[slotId] + ", NetworkType=" + mNetworkType[slotId]);
        if (!isCdma(slotId)) {
			Log.e("xdd","if !isCdma(slotId)");
            // GSM case, we have to check also the sim state
            if (mSimState[slotId] == IccCardConstants.State.READY ||
                    mSimState[slotId] == IccCardConstants.State.UNKNOWN) {
                mNoSim = false;
                if (hasService(slotId) && mDataState[slotId] == TelephonyManager.DATA_CONNECTED) {
                    switch (mDataActivity[slotId]) {
                        case TelephonyManager.DATA_ACTIVITY_IN:
                            iconId = mDataIconList[slotId][1].getIconId();
                            break;
                        case TelephonyManager.DATA_ACTIVITY_OUT:
                            iconId = mDataIconList[slotId][2].getIconId();
                            break;
                        case TelephonyManager.DATA_ACTIVITY_INOUT:
                            iconId = mDataIconList[slotId][3].getIconId();
                            break;
                        default:
                            iconId = mDataIconList[slotId][0].getIconId();
                            break;
                    }
                    mDataDirectionIconId[slotId] = iconId;
                } else {
                    iconId = 0;
                    visible = false;
                }
            } else {
                iconId = 0;
                mNoSim = true;
                visible = false; // no SIM? no data
            }
        } else {
            // CDMA case, mDataActivity can be also DATA_ACTIVITY_DORMANT
            if (hasService(slotId) && mDataState[slotId] == TelephonyManager.DATA_CONNECTED) {
                switch (mDataActivity[slotId]) {
                    case TelephonyManager.DATA_ACTIVITY_IN:
                        iconId = mDataIconList[slotId][1].getIconId();
                        break;
                    case TelephonyManager.DATA_ACTIVITY_OUT:
                        iconId = mDataIconList[slotId][2].getIconId();
                        break;
                    case TelephonyManager.DATA_ACTIVITY_INOUT:
                        iconId = mDataIconList[slotId][3].getIconId();
                        break;
                    case TelephonyManager.DATA_ACTIVITY_DORMANT:
                    default:
                        iconId = mDataIconList[slotId][0].getIconId();
                        break;
                }
            } else {
                iconId = 0;
                visible = false;
            }
        }

        mDataDirectionIconId[slotId] = iconId;
        mDataConnected[slotId] = visible;
        Xlog.d(TAG, "updateDataIcon(" + slotId + "), iconId=" + iconId + ", visible=" + visible);
    }


    void updateNetworkName(int slotId, boolean showSpn, String spn, boolean showPlmn, String plmn) {
        if (false) {
            Log.d("CarrierLabel", "updateNetworkName(" + slotId + "), showSpn=" + showSpn + " spn="
                    + spn + " showPlmn=" + showPlmn + " plmn=" + plmn);
        }
        StringBuilder str = new StringBuilder();
        boolean something = false;
        if ("Lava".equals(android.plugin.Features.JAVA_FEATURE_CUSTOMER)) {
            if (showSpn && spn != null) {
                str.append(spn);
                something = true;
            } else if (showPlmn && plmn != null) {
                str.append(plmn);
                something = true;
            }
        } else {
            if (showPlmn && plmn != null) {
                str.append(plmn);
                something = true;
            }

            if (showSpn && spn != null) {
                if (!something) {
                    str.append(spn);
                    something = true;
                } else {
                    if (!plmn.equals(spn)) {
                        str.append(mNetworkNameSeparator);
                        str.append(spn);
                    }
                }
            }
        }
        
        if (something) {
            mNetworkName[slotId] = str.toString();
        } else {
            mNetworkName[slotId] = mNetworkNameDefault;
        }

        if (DEBUG) Log.d(TAG, "updateNetworkName(" + slotId + "), mNetworkName="
                + mNetworkName[slotId]);
    }

    // ===== Wifi ===================================================================

    class WifiHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED:
                    if (msg.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                        mWifiChannel.sendMessage(Message.obtain(this,
                                AsyncChannel.CMD_CHANNEL_FULL_CONNECTION));
                    } else {
                        Log.e(TAG, "Failed to connect to wifi");
                    }
                    break;
                case WifiManager.DATA_ACTIVITY_NOTIFICATION:
                    if (msg.arg1 != mWifiActivity) {
                        mWifiActivity = msg.arg1;
                        /// M: Wifi activity icon
                        updateWifiActivityIcons();
                        refreshViews();
                    }
                    break;
                default:
                    //Ignore
                    break;
            }
        }
    }

    private void updateWifiState(Intent intent) {
        final String action = intent.getAction();
        if (action.equals(WifiManager.WIFI_STATE_CHANGED_ACTION)) {
            mWifiEnabled = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                    WifiManager.WIFI_STATE_UNKNOWN) == WifiManager.WIFI_STATE_ENABLED;

        } else if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
            final NetworkInfo networkInfo = (NetworkInfo)
                    intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
            boolean wasConnected = mWifiConnected;
            mWifiConnected = networkInfo != null && networkInfo.isConnected();
            // If Connected grab the signal strength and ssid
            if (mWifiConnected) {
                // try getting it out of the intent first
                WifiInfo info = (WifiInfo) intent.getParcelableExtra(WifiManager.EXTRA_WIFI_INFO);
                if (info == null) {
                    info = mWifiManager.getConnectionInfo();
                }
                if (info != null) {
                    mWifiSsid = huntForSsid(info);
                } else {
                    mWifiSsid = null;
                }
            } else if (!mWifiConnected) {
                mWifiSsid = null;
            }
        } else if (action.equals(WifiManager.RSSI_CHANGED_ACTION)) {
            mWifiRssi = intent.getIntExtra(WifiManager.EXTRA_NEW_RSSI, -200);
            mWifiLevel = WifiManager.calculateSignalLevel(
                    mWifiRssi, WifiIcons.WIFI_LEVEL_COUNT);
        }

        Xlog.d(TAG, "updateWifiState: mWifiLevel = " + mWifiLevel
                + "  mWifiRssi=" + mWifiRssi + " mWifiConnected is " + mWifiConnected);
        updateWifiIcons();
    }

    private void updateWifiIcons() {
        int inetCondition = inetConditionForNetwork(ConnectivityManager.TYPE_WIFI);
        if (mWifiConnected) {
            //mWifiIconId = WifiIcons.WIFI_SIGNAL_STRENGTH[inetCondition][mWifiLevel];
            /// M: config for show the ! icon or not . @{
            boolean showWifiActivityIcon = false;
            if (mShowNormalIcon || inetCondition == 1) {
                showWifiActivityIcon = true;
            }
            /// M: config for show the ! icon or not . @}
            /// M: Wifi activity icon @{
            Xlog.d(TAG, "updateWifiIcons: WifiConnected : inetCondition = " + inetCondition
                + "  mWifiLevel=" + mWifiLevel + " mWifiActivity =" + mWifiActivity);
            if (showWifiActivityIcon && mWifiSsid != null) { // normal case
                updateWifiActivityIcons();
            } else {
                mWifiIconId.setResources(null);
                mWifiIconId.setIconId(WifiIcons.WIFI_SIGNAL_STRENGTH[inetCondition][mWifiLevel]);
            }
            /// M: Wifi activity icon @}
            mQSWifiIconId = WifiIcons.QS_WIFI_SIGNAL_STRENGTH[inetCondition][mWifiLevel];
            mContentDescriptionWifi = mContext.getString(
                    AccessibilityContentDescriptions.WIFI_CONNECTION_STRENGTH[mWifiLevel]);
        } else {
            if (mDataAndWifiStacked) {
                //mWifiIconId = 0;
                mWifiIconId.setResources(null);
                mWifiIconId.setIconId(0);
                mQSWifiIconId = 0;
            } else {
                //mWifiIconId = mWifiEnabled ? R.drawable.stat_sys_wifi_signal_null : 0;
                mWifiIconId.setResources(null);
                mWifiIconId.setIconId(mWifiEnabled ? R.drawable.stat_sys_wifi_signal_null : 0);
                mQSWifiIconId = mWifiEnabled ? R.drawable.ic_qs_wifi_no_network : 0;
            }
            mContentDescriptionWifi = mContext.getString(R.string.accessibility_no_wifi);
        }
    }

    /// M: Wifi activity icon @{
    private void updateWifiActivityIcons() {
        if (mWifiConnected && mWifiSsid != null) {
            Xlog.d(TAG, "updateWifiActivityIcons: WifiConnected: "
                + "  mWifiLevel=" + mWifiLevel + " mWifiActivity =" + mWifiActivity);
            int wifiInoutIconId = 0;
            if (mWifiActivity >= WifiManager.DATA_ACTIVITY_NONE &&
                mWifiActivity <= WifiManager.DATA_ACTIVITY_INOUT) {
                wifiInoutIconId =
                    WifiIcons.WIFI_SIGNAL_STRENGTH_INOUT[mWifiLevel][mWifiActivity];
            } else {
                wifiInoutIconId = WifiIcons.WIFI_SIGNAL_STRENGTH_INOUT[mWifiLevel][0];
            }
            mWifiIconId.setResources(null);
            mWifiIconId.setIconId(wifiInoutIconId);
        }
    }
    /// M: Wifi activity icon @}

    private String huntForSsid(WifiInfo info) {
        String ssid = info.getSSID();
        if (ssid != null) {
            return ssid;
        }
        // OK, it's not in the connectionInfo; we have to go hunting for it
        List<WifiConfiguration> networks = mWifiManager.getConfiguredNetworks();
        for (WifiConfiguration net : networks) {
            if (net.networkId == info.getNetworkId()) {
                return net.SSID;
            }
        }
        return null;
    }


    // ===== Wimax ===================================================================
    private final void updateWimaxState(Intent intent) {
        final String action = intent.getAction();
        boolean wasConnected = mWimaxConnected;
        if (action.equals(WimaxManagerConstants.NET_4G_STATE_CHANGED_ACTION)) {
            int wimaxStatus = intent.getIntExtra(WimaxManagerConstants.EXTRA_4G_STATE,
                    WimaxManagerConstants.NET_4G_STATE_UNKNOWN);
            mIsWimaxEnabled = (wimaxStatus ==
                    WimaxManagerConstants.NET_4G_STATE_ENABLED);
        } else if (action.equals(WimaxManagerConstants.SIGNAL_LEVEL_CHANGED_ACTION)) {
            mWimaxSignal = intent.getIntExtra(WimaxManagerConstants.EXTRA_NEW_SIGNAL_LEVEL, 0);
        } else if (action.equals(WimaxManagerConstants.WIMAX_NETWORK_STATE_CHANGED_ACTION)) {
            mWimaxState = intent.getIntExtra(WimaxManagerConstants.EXTRA_WIMAX_STATE,
                    WimaxManagerConstants.NET_4G_STATE_UNKNOWN);
            mWimaxExtraState = intent.getIntExtra(
                    WimaxManagerConstants.EXTRA_WIMAX_STATE_DETAIL,
                    WimaxManagerConstants.NET_4G_STATE_UNKNOWN);
            mWimaxConnected = (mWimaxState ==
                    WimaxManagerConstants.WIMAX_STATE_CONNECTED);
            mWimaxIdle = (mWimaxExtraState == WimaxManagerConstants.WIMAX_IDLE);
        }
        updateDataNetType();
        updateWimaxIcons();
    }

    private void updateWimaxIcons() {
        if (mIsWimaxEnabled) {
            if (mWimaxConnected) {
                int inetCondition = inetConditionForNetwork(ConnectivityManager.TYPE_WIMAX);
                if (mWimaxIdle)
                    mWimaxIconId = WimaxIcons.WIMAX_IDLE;
                else
                    mWimaxIconId = WimaxIcons.WIMAX_SIGNAL_STRENGTH[inetCondition][mWimaxSignal];
                mContentDescriptionWimax = mContext.getString(
                        AccessibilityContentDescriptions.WIMAX_CONNECTION_STRENGTH[mWimaxSignal]);
            } else {
                mWimaxIconId = WimaxIcons.WIMAX_DISCONNECTED;
                mContentDescriptionWimax = mContext.getString(R.string.accessibility_no_wimax);
            }
        } else {
            mWimaxIconId = 0;
        }
    }

    // ===== Full or limited Internet connectivity ==================================

    private void updateConnectivity(Intent intent) {
        if (CHATTY) {
            Log.d(TAG, "updateConnectivity: intent=" + intent);
        }

        final ConnectivityManager connManager = (ConnectivityManager) mContext
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        final NetworkInfo info = connManager.getActiveNetworkInfo();

        // Are we connected at all, by any interface?
        mConnected = info != null && info.isConnected();
        if (mConnected) {
            mConnectedNetworkType = info.getType();
            mConnectedNetworkTypeName = info.getTypeName();
        } else {
            mConnectedNetworkType = ConnectivityManager.TYPE_NONE;
            mConnectedNetworkTypeName = null;
        }

        int connectionStatus = intent.getIntExtra(ConnectivityManager.EXTRA_INET_CONDITION, 0);

        if (true) {
            Log.d(TAG, "updateConnectivity: networkInfo=" + info);
            Log.d(TAG, "updateConnectivity: connectionStatus=" + connectionStatus);
        }

        mInetCondition = (connectionStatus > INET_CONDITION_THRESHOLD ? 1 : 0);

        if (info != null && info.getType() == ConnectivityManager.TYPE_BLUETOOTH) {
            mBluetoothTethered = info.isConnected();
        } else {
            mBluetoothTethered = false;
        }

        // We want to update all the icons, all at once, for any condition change
        updateDataNetType();
        updateWimaxIcons();
        updateDataIcon();
        updateTelephonySignalStrength();
        updateWifiIcons();
    }


    // ===== Update the views =======================================================

    void refreshViews() {
        for (int i = 0 ; i < mSlotCount ; i++) {
            refreshViews(i);
        }
    }

    void refreshViews(int slotId) {
        Context context = mContext;

        IconIdWrapper combinedSignalIconId = new IconIdWrapper();
        IconIdWrapper combinedActivityIconId = new IconIdWrapper();
        String combinedLabel = "";
        String wifiLabel = "";
        String mobileLabel = "";
        int N;
        final boolean emergencyOnly = isEmergencyOnly();

        boolean tempDataConnected;
        NetworkType tempNetworkType;
        String tempNetworkName;
        ServiceState tempServiceState;
        SignalStrength tempSignalStrength;
        IconIdWrapper tempDataSignalIconId = new IconIdWrapper();
        IconIdWrapper tempPhoneSignalIconId[] = { new IconIdWrapper(), new IconIdWrapper() };
        int tempDataActivity;
        String tempContentDescriptionPhoneSignal = "";
        String tempContentDescriptionDataType = "";
        String tempContentDescriptionCombinedSignal = "";

        tempSignalStrength = mSignalStrength[slotId];
        tempServiceState = mServiceState[slotId];
        tempDataConnected = mDataConnected[slotId];
        tempDataActivity = mDataActivity[slotId];
        tempNetworkType = mNetworkType[slotId];
        tempDataSignalIconId = mDataSignalIconId[slotId].clone();
        tempContentDescriptionPhoneSignal = mContentDescriptionPhoneSignal[slotId];
        tempContentDescriptionDataType = mContentDescriptionDataType[slotId];
        tempPhoneSignalIconId[0] = mPhoneSignalIconId[slotId][0].clone();
        tempPhoneSignalIconId[1] = mPhoneSignalIconId[slotId][1].clone();
        tempNetworkName = mNetworkName[slotId];

        if (!mHasMobileDataFeature) {
            tempDataSignalIconId.setResources(null);
            tempDataSignalIconId.setIconId(0);
            tempPhoneSignalIconId[0].setResources(null);
            tempPhoneSignalIconId[0].setIconId(0);
            tempPhoneSignalIconId[1].setResources(null);
            tempPhoneSignalIconId[1].setIconId(0);
            mobileLabel = "";
        } else {
            // We want to show the carrier name if in service and either:
            //   - We are connected to mobile data, or
            //   - We are not connected to mobile data, as long as the *reason* packets are not
            //     being routed over that link is that we have better connectivity via wifi.
            // If data is disconnected for some other reason but wifi (or ethernet/bluetooth)
            // is connected, we show nothing.
            // Otherwise (nothing connected) we show "No internet connection".

            if (mDataConnected[slotId]) {
                mobileLabel = tempNetworkName;
            } else if (mConnected || emergencyOnly) {
                if (hasService(slotId) || emergencyOnly) {
                    // The isEmergencyOnly test covers the case of a phone with no SIM
                    mobileLabel = tempNetworkName;
                } else {
                    // Tablets, basically
                    mobileLabel = "";
                }
            } else {
                mobileLabel
                    = context.getString(R.string.status_bar_settings_signal_meter_disconnected);
            }

            Xlog.d(TAG, "refreshViews(" + slotId + "), DataConnected=" + tempDataConnected);

            if (tempDataConnected) {
                combinedSignalIconId = tempDataSignalIconId.clone();
                IconIdWrapper tempMobileActivityIconId = new IconIdWrapper();
                //xd note ++++
                tempMobileActivityIconId.setResources(null);
                switch (tempDataActivity) { /// need change in out color
                   case TelephonyManager.DATA_ACTIVITY_IN:
                       tempMobileActivityIconId.setIconId(R.drawable.stat_sys_signal_in);
                       break;
                   case TelephonyManager.DATA_ACTIVITY_OUT:
                       tempMobileActivityIconId.setIconId(R.drawable.stat_sys_signal_out);
                       break;
                   case TelephonyManager.DATA_ACTIVITY_INOUT:
                       tempMobileActivityIconId.setIconId(R.drawable.stat_sys_signal_inout);
                       break;
                   default:
                       tempMobileActivityIconId.setIconId(0);
                       break;
                }
                PluginFactory.getStatusBarPlugin(mContext).customizeDataActivityIcon(
                        tempMobileActivityIconId, tempDataActivity);

                combinedLabel = mobileLabel;
                combinedActivityIconId = tempMobileActivityIconId.clone();
                combinedSignalIconId = tempDataSignalIconId.clone(); // set by updateDataIcon()
                tempContentDescriptionCombinedSignal = tempContentDescriptionDataType;
                mMobileActivityIconId[slotId] = tempMobileActivityIconId.clone();

            } else {
                combinedActivityIconId.setResources(null);
                combinedActivityIconId.setIconId(0);
                mMobileActivityIconId[slotId].setResources(null);
                mMobileActivityIconId[slotId].setIconId(0);
            }
        }

        if (mWifiConnected) {
            if (mWifiSsid == null) {
                wifiLabel = context.getString(R.string.status_bar_settings_signal_meter_wifi_nossid);
            } else {
                wifiLabel = mWifiSsid;
                if (DEBUG) {
                    wifiLabel += "xxxxXXXXxxxxXXXX";
                }
            }
            combinedLabel = wifiLabel;
            combinedSignalIconId.setResources(mWifiIconId.getResources());
            combinedSignalIconId.setIconId(mWifiIconId.getIconId()); // set by updateWifiIcons()
            tempContentDescriptionCombinedSignal = mContentDescriptionWifi;
        } else {
            if (mHasMobileDataFeature) {
                wifiLabel = "";
            } else {
                wifiLabel = context.getString(R.string.status_bar_settings_signal_meter_disconnected);
            }
        }

        if (mBluetoothTethered) {
            combinedLabel = mContext.getString(R.string.bluetooth_tethered);
            combinedSignalIconId.setResources(null);
            combinedSignalIconId.setIconId(mBluetoothTetherIconId);
            tempContentDescriptionCombinedSignal = mContext.getString(
                    R.string.accessibility_bluetooth_tether);
        }

        final boolean ethernetConnected = (mConnectedNetworkType == ConnectivityManager.TYPE_ETHERNET);
        if (ethernetConnected) {
            // TODO: icons and strings for Ethernet connectivity
            combinedLabel = mConnectedNetworkTypeName;
        }

        if (mAirplaneMode &&
                (tempServiceState == null || (!hasService(slotId) && !tempServiceState.isEmergencyOnly()))) {
            // Only display the flight-mode icon if not in "emergency calls only" mode.
            Xlog.d(TAG, "refreshViews(" + slotId + ")" + "Only display the flight-mode icon if not in emergency calls only mode.");
            mAirplaneIconId = TelephonyIcons.FLIGHT_MODE_ICON;

            // look again; your radios are now airplanes
            tempContentDescriptionPhoneSignal = mContext.getString(R.string.accessibility_airplane_mode);
            if (SIMHelper.isSimInsertedBySlot(mContext, slotId)) {
                mDataSignalIconId[slotId].setResources(null);
                mDataSignalIconId[slotId].setIconId(R.drawable.stat_sys_signal_null);
                tempDataSignalIconId = mDataSignalIconId[slotId].clone();
                mPhoneSignalIconId[slotId][0].setResources(null);
                mPhoneSignalIconId[slotId][0].setIconId(R.drawable.stat_sys_signal_null);
                mDataTypeIconId[slotId].setResources(null);
                mDataTypeIconId[slotId].setIconId(0);
            }

            // combined values from connected wifi take precedence over airplane mode
            if (mWifiConnected) {
                // Suppress "No internet connection." from mobile if wifi connected.
                mobileLabel = "";
            } else {
                if (mHasMobileDataFeature) {
                    // let the mobile icon show "No internet connection."
                    wifiLabel = "";
                } else {
                    wifiLabel = context.getString(R.string.status_bar_settings_signal_meter_disconnected);
                    combinedLabel = wifiLabel;
                }
                tempContentDescriptionCombinedSignal = tempContentDescriptionPhoneSignal;
                combinedSignalIconId = tempDataSignalIconId.clone();
            }

        } else if (!tempDataConnected && !mWifiConnected && !mBluetoothTethered
            && !mWimaxConnected && !ethernetConnected) {
            // pretty much totally disconnected
            Xlog.d(TAG, "refreshViews(" + slotId + ")" + "pretty much totally disconnected");

            combinedLabel = context.getString(R.string.status_bar_settings_signal_meter_disconnected);
            // On devices without mobile radios, we want to show the wifi icon
            if (mHasMobileDataFeature) {
                combinedSignalIconId = tempDataSignalIconId.clone();
            } else {
                combinedSignalIconId.setResources(null);
                combinedSignalIconId.setIconId(mWifiIconId.getIconId());
            }
            tempContentDescriptionCombinedSignal = mHasMobileDataFeature
                   ? tempContentDescriptionDataType : mContentDescriptionWifi;

            int inetCondition = inetConditionForNetwork(ConnectivityManager.TYPE_MOBILE);
            if (isRoaming(slotId)) {
                mIsRoaming[slotId] = true;
                mDataTypeIconId[slotId].setIconId(TelephonyIcons.ROAMING_ICON);
                //mQSDataTypeIconId[slotId] = TelephonyIcons.QS_DATA_R[mInetCondition];
            } else {
				Log.e(TAG,"else ~~~~~~~~~~~~~~~~~~~mIsRoaming = false");
                mIsRoaming[slotId] = false;
                mDataTypeIconId[slotId].setIconId(0);
                mQSDataTypeIconId[slotId] = 0;
            }

            Xlog.d(TAG, "refreshViews(" + slotId + "), mDataTypeIconId=" + Integer.toHexString(mDataTypeIconId[slotId].getIconId()));
            mDataTypeIconId[slotId].setResources(null);
        }

        if (mDemoMode) {
            mQSWifiIconId = mDemoWifiLevel < 0 ? R.drawable.ic_qs_wifi_no_network
                    : WifiIcons.QS_WIFI_SIGNAL_STRENGTH[mDemoInetCondition][mDemoWifiLevel];
            mQSPhoneSignalIconId[slotId] = mDemoMobileLevel < 0 ? R.drawable.ic_qs_signal_no_signal :
                    TelephonyIcons.QS_TELEPHONY_SIGNAL_STRENGTH[mDemoInetCondition][mDemoMobileLevel];
            mQSDataTypeIconId[slotId] = mDemoQSDataTypeIconId;
        }

        int tempDataDirectionIconId;
        IconIdWrapper tempDataTypeIconId = new IconIdWrapper();
        IconIdWrapper tempMobileActivityIconId = new IconIdWrapper();
        tempDataDirectionIconId = mDataDirectionIconId[slotId];
        tempPhoneSignalIconId[0] = mPhoneSignalIconId[slotId][0].clone();
        tempPhoneSignalIconId[1] = mPhoneSignalIconId[slotId][1].clone();
        tempDataTypeIconId = mDataTypeIconId[slotId].clone();
        tempMobileActivityIconId = mMobileActivityIconId[slotId].clone();

        if (DEBUG) {
            Log.d(TAG, "refreshViews connected={"
                    + (mWifiConnected?" wifi":"")
                    + (tempDataConnected ? " data" : "")
                    + " } level="
                    + ((tempSignalStrength == null) ? "??" : Integer.toString(tempSignalStrength.getLevel()))
                    + " combinedSignalIconId=0x"
                    + Integer.toHexString(combinedSignalIconId.getIconId())
                    + "/" + getResourceName(combinedSignalIconId.getIconId())
                    + " combinedActivityIconId=0x" + Integer.toHexString(combinedActivityIconId.getIconId())
                    + " mobileLabel=" + mobileLabel
                    + " wifiLabel=" + wifiLabel
                    + " emergencyOnly=" + emergencyOnly
                    + " combinedLabel=" + combinedLabel
                    + " mAirplaneMode=" + mAirplaneMode
                    + " mDataActivity=" + tempDataActivity
                    + " mPhoneSignalIconId=0x" + Integer.toHexString(tempPhoneSignalIconId[0].getIconId())
                    + " mPhoneSignalIconId2=0x" + Integer.toHexString(tempPhoneSignalIconId[1].getIconId())
                    + " mDataDirectionIconId=0x" + Integer.toHexString(tempDataDirectionIconId)
                    + " mDataSignalIconId=0x" + Integer.toHexString(tempDataSignalIconId.getIconId())
                    + " mDataTypeIconId=0x" + Integer.toHexString(tempDataTypeIconId.getIconId())
                    + " mWifiIconId=0x" + Integer.toHexString(mWifiIconId.getIconId())
                    + " mQSPhoneSignalIconId=0x" + Integer.toHexString(mQSPhoneSignalIconId[slotId])
                    + " mQSDataTypeIconId=0x" + Integer.toHexString(mQSDataTypeIconId[slotId])
                    + " mQSWifiIconId=0x" + Integer.toHexString(mQSWifiIconId)
                    + " mBluetoothTetherIconId=0x" + Integer.toHexString(mBluetoothTetherIconId));
        }

        int tempLastPhoneSignalIconId[];
        int tempLastDataTypeIconId;
        int tempLastMobileActivityIconId;

        tempLastPhoneSignalIconId = mLastPhoneSignalIconId[slotId];
        tempLastDataTypeIconId = mLastDataTypeIconId[slotId];
        tempLastMobileActivityIconId = mLastMobileActivityIconId[slotId];

        // update QS
        for (NetworkSignalChangedCallback cb : mSignalsChangedCallbacks) {
            notifySignalsChangedCallbacks(cb);
        }

        if (tempLastPhoneSignalIconId[0]    != tempPhoneSignalIconId[0].getIconId()
         || tempLastPhoneSignalIconId[1]    != tempPhoneSignalIconId[1].getIconId()
         || mLastcombinedActivityIconId     != combinedActivityIconId.getIconId()
         || mLastWifiIconId                 != mWifiIconId.getIconId()
         || mLastInetCondition              != mInetCondition
         || mLastWimaxIconId                != mWimaxIconId
         || tempLastDataTypeIconId          != tempDataTypeIconId.getIconId()
         || tempLastMobileActivityIconId    != tempMobileActivityIconId.getIconId()
         || mLastAirplaneMode               != mAirplaneMode
         || mLastAirplaneIconId             != mAirplaneIconId
         || mLastLocale                     != mLocale
         || mLastConnectedNetworkType       != mConnectedNetworkType) {
            Xlog.d(TAG, "refreshViews(" + slotId + "), set parameters to signal cluster view.");
            // NB: the mLast*s will be updated later
            for (SignalCluster cluster : mSignalClusters) {
                cluster.setWifiIndicators(
                        // only show wifi in the cluster if connected or if wifi-only
                        mWifiEnabled && (mWifiConnected || !mHasMobileDataFeature),
                        mWifiIconId,
                        mContentDescriptionWifi);
                cluster.setMobileDataIndicators(
                        slotId,
                        mHasMobileDataFeature,
                        tempPhoneSignalIconId,
                        tempMobileActivityIconId,
                        tempDataTypeIconId,
                        tempContentDescriptionPhoneSignal,
                        tempContentDescriptionDataType,
                        mIsRoaming[slotId],
                        isTypeIconWide(tempDataTypeIconId.getIconId()));
                cluster.setIsAirplaneMode(mAirplaneMode, mAirplaneIconId);
            }
        }

        /// M: Apply for signal cluster change
        for (SignalCluster cluster : mSignalClusters) {
            /// M: Support "Service Network Type on Statusbar".
            cluster.setNetworkType(slotId, mNetworkType[slotId]);
            /// M: Support "Default SIM Indicator"
            cluster.setShowSimIndicator(slotId, mSimIndicatorFlag[slotId], mSimIndicatorResId[slotId]);
            cluster.apply();
        }

        if (mLastAirplaneMode != mAirplaneMode) {
            mLastAirplaneMode = mAirplaneMode;
        }
        /// M: [ALPS01798819] record the last icon id and refresh it
        if (mLastAirplaneIconId != mAirplaneIconId) {
            mLastAirplaneIconId = mAirplaneIconId;
        }

        if (mLastLocale != mLocale) {
            mLastLocale = mLocale;
        }

        // the phone icon on phones
        if (tempLastPhoneSignalIconId[0] != tempPhoneSignalIconId[0].getIconId() ||
            tempLastPhoneSignalIconId[1] != tempPhoneSignalIconId[1].getIconId()) {
            mLastPhoneSignalIconId[slotId][0] = tempPhoneSignalIconId[0].getIconId();
            mLastPhoneSignalIconId[slotId][1] = tempPhoneSignalIconId[1].getIconId();
        }

        // the data icon on phones
        if (mLastDataDirectionIconId[slotId] != tempDataDirectionIconId) {
            mLastDataDirectionIconId[slotId] = tempDataDirectionIconId;
        }

        // the wifi icon on phones
        if (mLastWifiIconId != mWifiIconId.getIconId()) {
            mLastWifiIconId = mWifiIconId.getIconId();
        }

        if (mLastInetCondition != mInetCondition) {
            mLastInetCondition = mInetCondition;
        }

        if (mLastConnectedNetworkType != mConnectedNetworkType) {
            mLastConnectedNetworkType = mConnectedNetworkType;
        }

        // the wimax icon on phones
        if (mLastWimaxIconId != mWimaxIconId) {
            mLastWimaxIconId = mWimaxIconId;
        }
        // the combined data signal icon
        if (mLastCombinedSignalIconId != combinedSignalIconId.getIconId()) {
            mLastCombinedSignalIconId = combinedSignalIconId.getIconId();
        }

        // the data network type overlay
        if (tempLastDataTypeIconId != tempDataTypeIconId.getIconId()) {
            mLastDataTypeIconId[slotId] = tempDataTypeIconId.getIconId();
        }
        if ((tempLastMobileActivityIconId != tempMobileActivityIconId.getIconId())) {
            mLastMobileActivityIconId[slotId] = tempMobileActivityIconId.getIconId();
        }

        // the data direction overlay
        if (mLastcombinedActivityIconId != combinedActivityIconId.getIconId()) {
            if (DEBUG) {
                Xlog.d(TAG, "changing data overlay icon id to " + combinedActivityIconId.getIconId());
            }
            mLastcombinedActivityIconId = combinedActivityIconId.getIconId();
        }

        // the combinedLabel in the notification panel
        if (!mLastCombinedLabel.equals(combinedLabel)) {
            mLastCombinedLabel = combinedLabel;
            N = mCombinedLabelViews.size();
            for (int i=0; i<N; i++) {
                TextView v = mCombinedLabelViews.get(i);
                if (v != null) { ///M: when wifi only no valid v, it is null.
                    v.setText(combinedLabel);
                }
            }
        }

        // wifi label
        N = mWifiLabelViews.size();
        for (int i=0; i<N; i++) {
            TextView v = mWifiLabelViews.get(i);
            v.setText(wifiLabel);
            if ("".equals(wifiLabel)) {
                v.setVisibility(View.GONE);
            } else {
                v.setVisibility(View.VISIBLE);
            }
        }

        // mobile label
        N = mMobileLabelViews.size();
        for (int i=0; i<N; i++) {
            TextView v = mMobileLabelViews.get(i);
            v.setText(mobileLabel);
            if ("".equals(mobileLabel)) {
                v.setVisibility(View.GONE);
            } else {
                v.setVisibility(View.VISIBLE);
            }
        }

        // e-call label
        N = mEmergencyViews.size();
        for (int i=0; i<N; i++) {
            StatusBarHeaderView v = mEmergencyViews.get(i);
            v.setShowEmergencyCallsOnly(emergencyOnly);
        }
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("NetworkController state:");
        pw.println(String.format("  %s network type %d (%s)",
                mConnected?"CONNECTED":"DISCONNECTED",
                mConnectedNetworkType, mConnectedNetworkTypeName));
        pw.println("  - telephony ------");

        for (int i = 0 ; i < mSlotCount ; i++) {
            pw.println(String.format("====== slotId: %d ======", i));
            pw.print("  hasVoiceCallingFeature()=");
            pw.println(hasVoiceCallingFeature());
            pw.print("  hasService()=");
            pw.println(hasService(i));
            pw.print("  mHspaDataDistinguishable=");
            pw.println(mHspaDataDistinguishable);
            pw.print("  mDataConnected=");
            pw.println(mDataConnected[i]);
            pw.print("  mSimState=");
            pw.println(mSimState[i]);
            pw.print("  mPhoneState=");
            pw.println(mPhoneState);
            pw.print("  mDataState=");
            pw.println(mDataState[i]);
            pw.print("  mDataActivity=");
            pw.println(mDataActivity[i]);
            pw.print("  mDataNetType=");
            pw.print(mDataNetType[i]);
            pw.print("/");
            pw.println(TelephonyManager.getNetworkTypeName(mDataNetType[i]));
            pw.print("  mServiceState=");
            pw.println(mServiceState[i]);
            pw.print("  mSignalStrength=");
            pw.println(mSignalStrength[i]);
            pw.print("  mLastSignalLevel=");
            pw.println(mLastSignalLevel);
            pw.print("  mNetworkName=");
            pw.println(mNetworkName[i]);
            pw.print("  mNetworkNameDefault=");
            pw.println(mNetworkNameDefault);
            pw.print("  mNetworkNameSeparator=");
            pw.println(mNetworkNameSeparator.replace("\n", "\\n"));
            pw.print("  mPhoneSignalIconId=0x");
            pw.print(Integer.toHexString(mPhoneSignalIconId[i][0].getIconId()));
            pw.print("/");
            pw.print("  mQSPhoneSignalIconId=0x");
            pw.print(Integer.toHexString(mQSPhoneSignalIconId[i]));
            pw.print("/");
            pw.println(getResourceName(mPhoneSignalIconId[i][0].getIconId()));
            pw.print("  mDataDirectionIconId=");
            pw.print(Integer.toHexString(mDataDirectionIconId[i]));
            pw.print("/");
            pw.println(getResourceName(mDataDirectionIconId[i]));
            pw.print("  mDataSignalIconId=");
            pw.print(Integer.toHexString(mDataSignalIconId[i].getIconId()));
            pw.print("/");
            pw.println(getResourceName(mDataSignalIconId[i].getIconId()));
            pw.print("  mLastDataDirectionIconId=0x");
            pw.print(Integer.toHexString(mLastDataDirectionIconId[i]));
            pw.print("/");
            pw.println(getResourceName(mLastDataDirectionIconId[i]));
            pw.print("  mDataTypeIconId=");
            pw.print(Integer.toHexString(mDataTypeIconId[i].getIconId()));
            pw.print("/");
            pw.println(getResourceName(mDataTypeIconId[i].getIconId()));
            pw.print("  mLastDataTypeIconId=0x");
            pw.print(Integer.toHexString(mLastDataTypeIconId[i]));
            pw.print("/");
            pw.println(getResourceName(mLastDataTypeIconId[i]));
            pw.print("  mQSDataTypeIconId=");
            pw.print(Integer.toHexString(mQSDataTypeIconId[i]));
            pw.print("/");
            pw.println(getResourceName(mQSDataTypeIconId[i]));

            pw.println("  - icons ------");
            pw.print("  mLastPhoneSignalIconId[0]=0x");
            pw.print(Integer.toHexString(mLastPhoneSignalIconId[i][0]));
            pw.print("/");
            pw.println(getResourceName(mLastPhoneSignalIconId[i][0]));
            pw.print("  mLastPhoneSignalIconId[1]=0x");
            pw.print(Integer.toHexString(mLastPhoneSignalIconId[i][1]));
            pw.print("/");
            pw.println(getResourceName(mLastPhoneSignalIconId[i][1]));
        }

        pw.println("  - wifi ------");
        pw.print("  mWifiEnabled=");
        pw.println(mWifiEnabled);
        pw.print("  mWifiConnected=");
        pw.println(mWifiConnected);
        pw.print("  mWifiRssi=");
        pw.println(mWifiRssi);
        pw.print("  mWifiLevel=");
        pw.println(mWifiLevel);
        pw.print("  mWifiSsid=");
        pw.println(mWifiSsid);
        pw.println(String.format("  mWifiIconId=0x%08x/%s",
                    mWifiIconId.getIconId(), getResourceName(mWifiIconId.getIconId())));
        pw.println(String.format("  mQSWifiIconId=0x%08x/%s",
                    mQSWifiIconId, getResourceName(mQSWifiIconId)));
        pw.print("  mWifiActivity=");
        pw.println(mWifiActivity);

        if (mWimaxSupported) {
            pw.println("  - wimax ------");
            pw.print("  mIsWimaxEnabled="); pw.println(mIsWimaxEnabled);
            pw.print("  mWimaxConnected="); pw.println(mWimaxConnected);
            pw.print("  mWimaxIdle="); pw.println(mWimaxIdle);
            pw.println(String.format("  mWimaxIconId=0x%08x/%s",
                        mWimaxIconId, getResourceName(mWimaxIconId)));
            pw.println(String.format("  mWimaxSignal=%d", mWimaxSignal));
            pw.println(String.format("  mWimaxState=%d", mWimaxState));
            pw.println(String.format("  mWimaxExtraState=%d", mWimaxExtraState));
        }

        pw.println("  - Bluetooth ----");
        pw.print("  mBtReverseTethered=");
        pw.println(mBluetoothTethered);

        pw.println("  - connectivity ------");
        pw.print("  mInetCondition=");
        pw.println(mInetCondition);

        pw.print("  mLastWifiIconId=0x");
        pw.print(Integer.toHexString(mLastWifiIconId));
        pw.print("/");
        pw.println(getResourceName(mLastWifiIconId));
        pw.print("  mLastCombinedSignalIconId=0x");
        pw.print(Integer.toHexString(mLastCombinedSignalIconId));
        pw.print("/");
        pw.println(getResourceName(mLastCombinedSignalIconId));
        pw.print("  mLastCombinedLabel=");
        pw.print(mLastCombinedLabel);
        pw.println("");
    }

    private String getResourceName(int resId) {
        if (resId != 0) {
            final Resources res = mContext.getResources();
            try {
                return res.getResourceName(resId);
            } catch (android.content.res.Resources.NotFoundException ex) {
                return "(unknown)";
            }
        } else {
            return "(null)";
        }
    }

    private boolean mDemoMode;
    private int mDemoInetCondition;
    private int mDemoWifiLevel;
    private int mDemoDataTypeIconId;
    private int mDemoQSDataTypeIconId;
    private int mDemoMobileLevel;

    @Override
    public void dispatchDemoCommand(String command, Bundle args) {
        if (!mDemoMode && command.equals(COMMAND_ENTER)) {
            mDemoMode = true;
            mDemoWifiLevel = mWifiLevel;
            mDemoInetCondition = mInetCondition;
            mDemoDataTypeIconId = mDataTypeIconId[0].getIconId();
            mDemoQSDataTypeIconId = mQSDataTypeIconId[0];
            mDemoMobileLevel = mLastSignalLevel;
        } else if (mDemoMode && command.equals(COMMAND_EXIT)) {
            mDemoMode = false;
            for (SignalCluster cluster : mSignalClusters) {
                refreshSignalCluster(cluster);
            }
            refreshViews();
        } else if (mDemoMode && command.equals(COMMAND_NETWORK)) {
            String airplane = args.getString("airplane");
            if (airplane != null) {
                boolean show = airplane.equals("show");
                for (SignalCluster cluster : mSignalClusters) {
                    cluster.setIsAirplaneMode(show, TelephonyIcons.FLIGHT_MODE_ICON);
                }
            }
            String fully = args.getString("fully");
            if (fully != null) {
                mDemoInetCondition = Boolean.parseBoolean(fully) ? 1 : 0;
            }
            String wifi = args.getString("wifi");
            if (wifi != null) {
                boolean show = wifi.equals("show");
                String level = args.getString("level");
                if (level != null) {
                    mDemoWifiLevel = level.equals("null") ? -1
                            : Math.min(Integer.parseInt(level), WifiIcons.WIFI_LEVEL_COUNT - 1);
                }
                int iconId = mDemoWifiLevel < 0 ? R.drawable.stat_sys_wifi_signal_null
                        : WifiIcons.WIFI_SIGNAL_STRENGTH[mDemoInetCondition][mDemoWifiLevel];
                for (SignalCluster cluster : mSignalClusters) {
                    cluster.setWifiIndicators(
                            show,
                            new IconIdWrapper(iconId),
                            "Demo");
                }
                refreshViews();
            }
            String mobile = args.getString("mobile");
            if (mobile != null) {
                boolean show = mobile.equals("show");
                String datatype = args.getString("datatype");
                if (datatype != null) {
                    mDemoDataTypeIconId =
                            datatype.equals("1x") ? TelephonyIcons.ICON_1X :
                            datatype.equals("3g") ? TelephonyIcons.ICON_3G :
                            datatype.equals("4g") ? TelephonyIcons.ICON_4G :
                            datatype.equals("e") ? R.drawable.stat_sys_data_fully_connected_e :
                            datatype.equals("g") ? R.drawable.stat_sys_data_fully_connected_g :
                            datatype.equals("h") ? R.drawable.stat_sys_data_fully_connected_h :
                            datatype.equals("lte") ? TelephonyIcons.ICON_LTE :
                            datatype.equals("roam") ? TelephonyIcons.ROAMING_ICON :
                            0;
                    mDemoQSDataTypeIconId =
                            datatype.equals("1x") ? TelephonyIcons.QS_ICON_1X :
                            datatype.equals("3g") ? TelephonyIcons.QS_ICON_3G :
                            datatype.equals("4g") ? TelephonyIcons.QS_ICON_4G :
                            datatype.equals("e") ? R.drawable.ic_qs_signal_e :
                            datatype.equals("g") ? R.drawable.ic_qs_signal_g :
                            datatype.equals("h") ? R.drawable.ic_qs_signal_h :
                            datatype.equals("lte") ? TelephonyIcons.QS_ICON_LTE :
                            datatype.equals("roam") ? R.drawable.ic_qs_signal_r :
                            0;
                }
                int[][] icons = TelephonyIcons.TELEPHONY_SIGNAL_STRENGTH;
                String level = args.getString("level");
                if (level != null) {
                    mDemoMobileLevel = level.equals("null") ? -1
                            : Math.min(Integer.parseInt(level), icons[0].length - 1);
                }
                int iconId = mDemoMobileLevel < 0 ? R.drawable.stat_sys_signal_null :
                        icons[mDemoInetCondition][mDemoMobileLevel];
                for (SignalCluster cluster : mSignalClusters) {
                     for (int i = 0; i < mSlotCount ; i++) {
                        cluster.setMobileDataIndicators(
                                i,
                                show,
                                new IconIdWrapper[]{new IconIdWrapper(iconId)},
                                new IconIdWrapper(),
                                new IconIdWrapper(mDemoDataTypeIconId),
                                "Demo",
                                "Demo",
                                mDemoDataTypeIconId == TelephonyIcons.ROAMING_ICON,
                                isTypeIconWide(mDemoDataTypeIconId));
                    }
                }
            }
        }
    }

    private boolean isValidSlotId(int slotId) {
        return (0 <= slotId) && (slotId < mSlotCount);
    }

    /// M: Support "SystemUI - VoLTE icon". @{
    private void updateVoLTE()
    {
        for (int i = 0; i < mSlotCount; i++) {
            Xlog.d(TAG, "updateVoLTE: simID=" + i + ",DataNetType=" + mDataNetType[i] +
                   ",DataState=" + mDataState[i] +
                   ",IMSStatus=" + mIMSState[i]);
            // VoLTE on : 4G + IMS reg
            if (mDataNetType[i] == TelephonyManager.NETWORK_TYPE_LTE &&
                mDataState[i] == TelephonyManager.DATA_CONNECTED &&
                mIMSState[i] == 1) {
                // previous VoLTE = off, then redraw it
                if (mVoLTEState == false) {
                    mVoLTEState = true;
                    for (SignalCluster cluster : mSignalClusters) {
                        cluster.setVoLTE(true);
                    }
                }
                return;
            }
        }

        // VoLTE off case
        if (mVoLTEState == true) {
            mVoLTEState = false;
            for (SignalCluster cluster : mSignalClusters) {
                cluster.setVoLTE(false);
            }
        }
    }
    /// M: Support "SystemUI - VoLTE icon". @}

    /// M: Support "Default SIM Indicator". @{
    public void showSimIndicator(int slotId) {
        //set SimIndicatorFlag and refreshViews.
        Xlog.d(TAG,"showSimIndicator slotId is " + slotId);
        if( isValidSlotId(slotId) ) {
            mSimIndicatorResId[slotId] = R.drawable.stat_sys_default_sim_indicator;
            mSimIndicatorFlag[slotId] = true;
            updateTelephonySignalStrength(slotId);
            updateDataNetType(slotId);
            updateDataIcon(slotId);
            refreshViews(slotId);
        }
    }

    public void hideSimIndicator(int slotId) {
        //reset SimIndicatorFlag and refreshViews.
        Xlog.d(TAG,"hideSimIndicator slotId is " + slotId);
        if( isValidSlotId(slotId) ) {
            mSimIndicatorFlag[slotId] = false;
            updateTelephonySignalStrength(slotId);
            updateDataNetType(slotId);
            updateDataIcon(slotId);
            refreshViews(slotId);
        }
    }
    /// M: Support "Default SIM Indicator". }@

    /// M: Support "SystemUI - Data Connect in front of signal_cluster_combo". @{
    /**
     * Whether data is connected.
     *
     * @param slotId The slot index.
     * @return true If data is connected.
     */
    public final boolean isDataConnected(int slotId) {
        Xlog.d(TAG, "isDataConnected() slotId is " + slotId
                + ", mDataConnected = " + mDataConnected[slotId]);
        return mDataConnected[slotId];
    }

    
    /**
     * Whether Sim service is available.
     *
     * @param slotId The slot index.
     * @return true If has sim service.
     */
    public final boolean hasSimService(int slotId) {
        return hasService(slotId);
    }
    /// M: Support "SystemUI - Data Connect in front of signal_cluster_combo". @}

}
