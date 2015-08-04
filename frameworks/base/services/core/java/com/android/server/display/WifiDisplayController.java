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

package com.android.server.display;

import com.android.internal.util.DumpUtils;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.hardware.display.WifiDisplay;
import android.hardware.display.WifiDisplaySessionInfo;
import android.hardware.display.WifiDisplayStatus;
import android.media.RemoteDisplay;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.WifiP2pWfdInfo;
import android.net.wifi.p2p.WifiP2pManager.ActionListener;
import android.net.wifi.p2p.WifiP2pManager.Channel;
import android.net.wifi.p2p.WifiP2pManager.GroupInfoListener;
import android.net.wifi.p2p.WifiP2pManager.PeerListListener;
import android.os.Handler;
import android.provider.Settings;
import android.util.Slog;
import android.view.Surface;

import java.io.PrintWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import libcore.util.Objects;

///M:@{
import android.app.AlertDialog;
import android.app.StatusBarManager;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;

import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.res.Resources;
import android.hardware.input.InputManager;
import android.media.AudioManager;
import android.media.AudioManager.OnAudioFocusChangeListener;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.WifiLock;
import android.net.wifi.p2p.link.WifiP2pLinkInfo;
import android.net.wifi.p2p.WifiP2pManager.WifiP2pLinkInfoListener;
import android.os.RemoteException;
import android.os.IBinder;
import android.os.ServiceManager;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import android.widget.CheckBox;
import android.widget.ProgressBar;
import android.widget.Toast;
import android.widget.TextView;

import java.util.regex.Pattern;
import java.util.regex.Matcher;

import com.android.internal.R;
import com.android.internal.view.IInputMethodManager;
import com.mediatek.hdmi.HdmiDef;
import com.mediatek.hdmi.IMtkHdmiManager;
import android.app.Notification;
import android.app.Notification.Builder;
import android.app.NotificationManager;

///@}


/**
 * Manages all of the various asynchronous interactions with the {@link WifiP2pManager}
 * on behalf of {@link WifiDisplayAdapter}.
 * <p>
 * This code is isolated from {@link WifiDisplayAdapter} so that we can avoid
 * accidentally introducing any deadlocks due to the display manager calling
 * outside of itself while holding its lock.  It's also way easier to write this
 * asynchronous code if we can assume that it is single-threaded.
 * </p><p>
 * The controller must be instantiated on the handler thread.
 * </p>
 */
final class WifiDisplayController implements DumpUtils.Dump {
    private static final String TAG = "WifiDisplayController";
    private static boolean DEBUG = true;

    private static final int DEFAULT_CONTROL_PORT = 7236;
    private static final int MAX_THROUGHPUT = 50;
    private static final int CONNECTION_TIMEOUT_SECONDS = 60;
    /// M: Modify for speed up rtsp setup
    private static final int RTSP_TIMEOUT_SECONDS = 15 + CONNECTION_TIMEOUT_SECONDS;
    private static final int RTSP_TIMEOUT_SECONDS_CERT_MODE = 120;
    private static final int RTSP_SINK_TIMEOUT_SECONDS = 10;


    // We repeatedly issue calls to discover peers every so often for a few reasons.
    // 1. The initial request may fail and need to retried.
    // 2. Discovery will self-abort after any group is initiated, which may not necessarily
    //    be what we want to have happen.
    // 3. Discovery will self-timeout after 2 minutes, whereas we want discovery to
    //    be occur for as long as a client is requesting it be.
    // 4. We don't seem to get updated results for displays we've already found until
    //    we ask to discover again, particularly for the isSessionAvailable() property.
    private static final int DISCOVER_PEERS_INTERVAL_MILLIS = 10000;

    private static final int CONNECT_MAX_RETRIES = 3;
    private static final int CONNECT_RETRY_DELAY_MILLIS = 500;

    private final Context mContext;
    private final Handler mHandler;
    private final Listener mListener;

    private final WifiP2pManager mWifiP2pManager;
    private final Channel mWifiP2pChannel;

    private boolean mWifiP2pEnabled;
    private boolean mWfdEnabled;
    private boolean mWfdEnabling;
    private NetworkInfo mNetworkInfo;

    private final ArrayList<WifiP2pDevice> mAvailableWifiDisplayPeers =
            new ArrayList<WifiP2pDevice>();

    // True if Wifi display is enabled by the user.
    private boolean mWifiDisplayOnSetting;

    // True if a scan was requested independent of whether one is actually in progress.
    private boolean mScanRequested;

    // True if there is a call to discoverPeers in progress.
    private boolean mDiscoverPeersInProgress;

    // The device to which we want to connect, or null if we want to be disconnected.
    private WifiP2pDevice mDesiredDevice;

    // The device to which we are currently connecting, or null if we have already connected
    // or are not trying to connect.
    private WifiP2pDevice mConnectingDevice;

    // The device from which we are currently disconnecting.
    private WifiP2pDevice mDisconnectingDevice;

    // The device to which we were previously trying to connect and are now canceling.
    private WifiP2pDevice mCancelingDevice;

    // The device to which we are currently connected, which means we have an active P2P group.
    private WifiP2pDevice mConnectedDevice;

    // The group info obtained after connecting.
    private WifiP2pGroup mConnectedDeviceGroupInfo;

    // Number of connection retries remaining.
    private int mConnectionRetriesLeft;

    // The remote display that is listening on the connection.
    // Created after the Wifi P2P network is connected.
    private RemoteDisplay mRemoteDisplay;

    // The remote display interface.
    private String mRemoteDisplayInterface;

    // True if RTSP has connected.
    private boolean mRemoteDisplayConnected;

    // The information we have most recently told WifiDisplayAdapter about.
    private WifiDisplay mAdvertisedDisplay;
    private Surface mAdvertisedDisplaySurface;
    private int mAdvertisedDisplayWidth;
    private int mAdvertisedDisplayHeight;
    private int mAdvertisedDisplayFlags;

    // Certification
    private boolean mWifiDisplayCertMode;
    private int mWifiDisplayWpsConfig = WpsInfo.INVALID;

    private WifiP2pDevice mThisDevice;

    ///M:@{
    private int mBackupShowTouchVal;
    private boolean mFast_NeedFastRtsp;
    private String mFast_DesiredMac;
    //private int mBackupScreenOffTimeout;
    private boolean mIsNeedRotate;
    private boolean mIsConnected_OtherP2p;
    private boolean mIsConnecting_P2p_Rtsp;
    private static final int CONNECT_MIN_RETRIES = 0;

    // for HDMI/WFD exclude
    private final static int WFDCONTROLLER_WFD_UPDATE = 0;
    private final static int WFDCONTROLLER_HDMI_UPDATE = 2;

    // for HDMI/WFD exclude
    private IMtkHdmiManager mHdmiManager;

    // for Wfd stat file
    private final static String WFDCONTROLLER_WFD_STAT_FILE = "/proc/wmt_tm/wfd_stat";
    private final static int WFDCONTROLLER_WFD_STAT_DISCONNECT = 0;
    private final static int WFDCONTROLLER_WFD_STAT_STANDBY = 1;
    private final static int WFDCONTROLLER_WFD_STAT_STREAMING = 2;

    // for WFD connected/disconnected
    public static final String WFD_CONNECTION = "com.mediatek.wfd.connection";
    private boolean mIsWFDConnected;

    // for ClearMotion
    public static final String WFD_CLEARMOTION_DIMMED = "com.mediatek.clearmotion.DIMMED_UPDATE";

    // for DRM content on media player
    public static final String DRM_CONTENT_MEDIAPLAYER = "com.mediatek.mediaplayer.DRM_PLAY";
    private boolean mDRMContent_Mediaplayer;
    private int mPlayerID_Mediaplayer;
    // ALPS01031660: switch audio path when wfd is in connecting
    private boolean mRTSPConnecting;

    // for wifi link info & Latency info
    private final static int WFDCONTROLLER_LINK_INFO_PERIOD_MILLIS = 2 * 1000;
    private final static int WFDCONTROLLER_LATENCY_INFO_FIRST_MILLIS = 100;
    private final static int WFDCONTROLLER_LATENCY_INFO_PERIOD_MILLIS = 3 * 1000;
    private final static int WFDCONTROLLER_WIFI_APP_SCAN_PERIOD_MILLIS = 100;
    private final static int WFDCONTROLLER_LATENCY_INFO_DELAY_MILLIS = 2 * 1000;

    // for quality enhancement
    private final static int WFDCONTROLLER_SCORE_THRESHOLD1 = 100;
    private final static int WFDCONTROLLER_SCORE_THRESHOLD2 = 80;
    private final static int WFDCONTROLLER_SCORE_THRESHOLD3 = 30;
    private final static int WFDCONTROLLER_SCORE_THRESHOLD4 = 10;

    // Initialize in config.xml
    private int WFDCONTROLLER_DISPLAY_TOAST_TIME;
    private int WFDCONTROLLER_DISPLAY_NOTIFICATION_TIME;
    private int WFDCONTROLLER_DISPLAY_RESOLUTION;
    private int WFDCONTROLLER_DISPLAY_POWER_SAVING_OPTION;
    private int WFDCONTROLLER_DISPLAY_POWER_SAVING_DELAY;
    private int WFDCONTROLLER_DISPLAY_SECURE_OPTION;

    private boolean WFDCONTROLLER_SQC_INFO_ON = false;
    private boolean WFDCONTROLLER_QE_ON = true;

    private boolean mAutoChannelSelection = false;
    private int mLatencyProfiling = 2;  // 0: disable(in EM), 1: enable(in EM), 2: disable(not in EM), 3: enable(not in EM)
    private int mResolution;
    private int mPrevResolution;
    private boolean mReconnectForResolutionChange = false;
    private boolean mAutoEnableWifi;
    private int mWifiP2pChannelId = -1;
    private boolean mWifiApConnected = false;
    private int mWifiApFreq = 0;
    private int mWifiNetworkId = -1;
    private String mWifiApSsid = null;

    View mLatencyPanelView = null;
    TextView mTextView = null;

    private final static int WFDCONTROLLER_AVERATE_SCORE_COUNT = 4;
    private final static int WFDCONTROLLER_INVALID_VALUE = -1;
    private int[] mScore = new int[WFDCONTROLLER_AVERATE_SCORE_COUNT];
    private int mScoreIndex = 0;

    private int mScoreLevel = 0;
    private int mLevel = 0;
    private int mWifiScore = 0;
    private int mWifiRate = 0;
    private int mRSSI = 0;
    private final NotificationManager mNotificationManager;
    private boolean mNotiTimerStarted;
    private boolean mToastTimerStarted;

    // ALPS00677009: for reconnect
    private WifiP2pDevice mReConnectDevice;
    private static final int RECONNECT_RETRY_DELAY_MILLIS = 1000;
    private int mReConnection_Timeout_Remain_Seconds;
    private boolean mReConnecting;

    // ALPS00759126: keep wifi enabled when WFD connected
    private WifiLock mWifiLock;
    private WifiManager mWifiManager;
    private boolean mStopWifiScan = false;

    // ALPS00772074: do disconnect for every power-off case
    private final static String WFDCONTROLLER_PRE_SHUTDOWN = "android.intent.action.ACTION_PRE_SHUTDOWN";

    // ALPS00812236: dismiss all dialogs when wifi p2p/wfd is disabled
    private final static int WFD_WIFIP2P_EXCLUDED_DIALOG = 1;
    private final static int WFD_HDMI_EXCLUDED_DIALOG_WFD_UPDATE = 2;
    private final static int WFD_HDMI_EXCLUDED_DIALOG_HDMI_UPDATE = 3;
    private final static int WFD_RECONNECT_DIALOG = 4;
    private final static int WFD_CHANGE_RESOLUTION_DIALOG = 5;
    private final static int WFD_SOUND_PATH_DIALOG = 6;
    private final static int WFD_WAIT_CONNECT_DIALOG = 7;
    private final static int WFD_CONFIRM_CONNECT_DIALOG = 8;
    private final static int WFD_BUILD_CONNECT_DIALOG = 9;
    private AlertDialog mWifiDirectExcludeDialog;
    private AlertDialog mHDMIExcludeDialog_WfdUpdate;
    private AlertDialog mHDMIExcludeDialog_HDMIUpdate;
    private AlertDialog mReConnecteDialog;
    private AlertDialog mChangeResolutionDialog;
    private AlertDialog mSoundPathDialog;
    private AlertDialog mWaitConnectDialog;
    private AlertDialog mConfirmConnectDialog;
    private AlertDialog mBuildConnectDialog;
    private boolean mUserDecided;

    // ALPS00834020: do scan after disconnect
    private boolean mLastTimeConnected;

    private boolean mWifiPowerSaving = true;

    WifiP2pWfdInfo mWfdInfo;

    // M:@{ Channel Conflict UX Support
    public static final String WFD_CHANNEL_CONFLICT_OCCURS = "com.mediatek.wifi.p2p.OP.channel";
    public static final String WFD_SINK_CHANNEL_CONFLICT_OCCURS = "com.mediatek.wifi.p2p.freq.conflict";
    private int mP2pOperFreq = 0;
    private int mNetworkId = -1;
    private boolean mDisplayApToast;
    private ChannelConflictState mChannelConflictState;

    enum ChannelConflictState {
        STATE_IDLE,
        STATE_AP_DISCONNECTING,
        STATE_WFD_CONNECTING,
        STATE_AP_CONNECTING
    }

    enum ChannelConflictEvt {
        EVT_AP_DISCONNECTED,
        EVT_AP_CONNECTED,
        EVT_WFD_P2P_DISCONNECTED,
        EVT_WFD_P2P_CONNECTED
    }
    ///@}

    ///M:@{ WFD Sink Support
    private boolean mSinkEnabled = false;

    enum SinkState {
        SINK_STATE_IDLE,
        SINK_STATE_WAITING_P2P_CONNECTION,
        SINK_STATE_WIFI_P2P_CONNECTED,
        SINK_STATE_WAITING_RTSP,
        SINK_STATE_RTSP_CONNECTED,
    }

    private SinkState mSinkState;
    private String mSinkDeviceName;
    private String mSinkMacAddress;
    private String mSinkIpAddress;
    private int mSinkPort;
    StatusBarManager mStatusBarManager;
    private Surface mSinkSurface;
    private AudioManager mAudioManager;

    public static final String WFD_SINK_GC_REQUEST_CONNECT = "com.mediatek.wifi.p2p.GO.GCrequest.connect";

    private static final int WFD_SINK_IP_RETRY_FIRST_DELAY = 300;
    private static final int WFD_SINK_IP_RETRY_DELAY_MILLIS = 1000;
    private static final int WFD_SINK_IP_RETRY_COUNT = 50;
    private int mSinkIpRetryCount;

    private IInputMethodManager mInputMethodManager;
    private PowerManager.WakeLock mWakeLock;
    private PowerManager.WakeLock mWakeLockSink;
    ///@}


    public WifiDisplayController(Context context, Handler handler, Listener listener) {
        mContext = context;
        mHandler = handler;
        mListener = listener;

        mWifiP2pManager = (WifiP2pManager)context.getSystemService(Context.WIFI_P2P_SERVICE);
        mWifiP2pChannel = mWifiP2pManager.initialize(context, handler.getLooper(), null);


        ///M:@{
        //get WiFi lock
        getWifiLock();

        mWfdInfo = new WifiP2pWfdInfo();

        mHdmiManager = IMtkHdmiManager.Stub.asInterface(ServiceManager
                .getService(Context.HDMI_SERVICE));
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        ///@}

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);

        ///M:@{
        intentFilter.addAction(DRM_CONTENT_MEDIAPLAYER);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION);
        intentFilter.addAction(WFDCONTROLLER_PRE_SHUTDOWN);
        intentFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        intentFilter.addAction(WFD_SINK_GC_REQUEST_CONNECT);
        intentFilter.addAction(WFD_CHANNEL_CONFLICT_OCCURS);
        intentFilter.addAction(WFD_SINK_CHANNEL_CONFLICT_OCCURS);
        ///@}
        context.registerReceiver(mWifiP2pReceiver, intentFilter, null, mHandler);

        ContentObserver settingsObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                if (!selfChange) {
                    updateSettings();
                }
            }
        };

        final ContentResolver resolver = mContext.getContentResolver();
        resolver.registerContentObserver(Settings.Global.getUriFor(
                Settings.Global.WIFI_DISPLAY_ON), false, settingsObserver);
        resolver.registerContentObserver(Settings.Global.getUriFor(
                Settings.Global.WIFI_DISPLAY_CERTIFICATION_ON), false, settingsObserver);
        resolver.registerContentObserver(Settings.Global.getUriFor(
                Settings.Global.WIFI_DISPLAY_WPS_CONFIG), false, settingsObserver);
        updateSettings();

        ///M:@{

        //observe HDMI
        ContentObserver settingsObserverHDMI = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                updateSettingsHDMI();
            }
        };

        resolver.registerContentObserver(Settings.System.getUriFor(
                Settings.System.HDMI_ENABLE_STATUS), false, settingsObserverHDMI);

        DisplayMetrics dm = new DisplayMetrics();
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(dm);
        Slog.i(TAG, "RealMetrics, Width = " + dm.widthPixels + ", Height = " + dm.heightPixels);
        if (dm.widthPixels < dm.heightPixels) {
            mIsNeedRotate = true;
        }

        // Register EM observer
        registerEMObserver(dm.widthPixels, dm.heightPixels);

        mNotificationManager = (NotificationManager) context.getSystemService(
                Context.NOTIFICATION_SERVICE);

        //reset all Setting config
        actionAtDisconnected(null);
        updateWfdStatFile(WFDCONTROLLER_WFD_STAT_DISCONNECT);
        //mWifiP2pManager.setWfdSessionMode(mWifiP2pChannel, WifiP2pWfdInfo.DISCONNECTED, null);

        //For WFD UIBC Source Latin Characters
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK, "UIBC Source");

        //For WFD Sink
        mStatusBarManager = (StatusBarManager) context.getSystemService(Context.STATUS_BAR_SERVICE);
        mWakeLockSink = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK, "WFD Sink");

        ///@}

    }

    private void updateSettings() {
        final ContentResolver resolver = mContext.getContentResolver();
        mWifiDisplayOnSetting = Settings.Global.getInt(resolver,
                Settings.Global.WIFI_DISPLAY_ON, 0) != 0;
        mWifiDisplayCertMode = Settings.Global.getInt(resolver,
                Settings.Global.WIFI_DISPLAY_CERTIFICATION_ON, 0) != 0;

        mWifiDisplayWpsConfig = WpsInfo.INVALID;
        if (mWifiDisplayCertMode) {
            mWifiDisplayWpsConfig = Settings.Global.getInt(resolver,
                  Settings.Global.WIFI_DISPLAY_WPS_CONFIG, WpsInfo.INVALID);
        }

        ///M:@{
        loadDebugLevel();
        boolean HDMIOn = false;
        if (SystemProperties.get("ro.mtk_hdmi_support").equals("1")) {
            HDMIOn = Settings.System.getInt(resolver,
                Settings.System.HDMI_ENABLE_STATUS, 1) != 0;
        }
        if (true == mWifiDisplayOnSetting && true == HDMIOn) {
            dialogWfdHdmiConflict(WFDCONTROLLER_WFD_UPDATE);

        } else {

            enableWifiDisplay();
        }
        ///@}
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.println("mWifiDisplayOnSetting=" + mWifiDisplayOnSetting);
        pw.println("mWifiP2pEnabled=" + mWifiP2pEnabled);
        pw.println("mWfdEnabled=" + mWfdEnabled);
        pw.println("mWfdEnabling=" + mWfdEnabling);
        pw.println("mNetworkInfo=" + mNetworkInfo);
        pw.println("mScanRequested=" + mScanRequested);
        pw.println("mDiscoverPeersInProgress=" + mDiscoverPeersInProgress);
        pw.println("mDesiredDevice=" + describeWifiP2pDevice(mDesiredDevice));
        pw.println("mConnectingDisplay=" + describeWifiP2pDevice(mConnectingDevice));
        pw.println("mDisconnectingDisplay=" + describeWifiP2pDevice(mDisconnectingDevice));
        pw.println("mCancelingDisplay=" + describeWifiP2pDevice(mCancelingDevice));
        pw.println("mConnectedDevice=" + describeWifiP2pDevice(mConnectedDevice));
        pw.println("mConnectionRetriesLeft=" + mConnectionRetriesLeft);
        pw.println("mRemoteDisplay=" + mRemoteDisplay);
        pw.println("mRemoteDisplayInterface=" + mRemoteDisplayInterface);
        pw.println("mRemoteDisplayConnected=" + mRemoteDisplayConnected);
        pw.println("mAdvertisedDisplay=" + mAdvertisedDisplay);
        pw.println("mAdvertisedDisplaySurface=" + mAdvertisedDisplaySurface);
        pw.println("mAdvertisedDisplayWidth=" + mAdvertisedDisplayWidth);
        pw.println("mAdvertisedDisplayHeight=" + mAdvertisedDisplayHeight);
        pw.println("mAdvertisedDisplayFlags=" + mAdvertisedDisplayFlags);
        ///M:@{
        pw.println("mBackupShowTouchVal=" + mBackupShowTouchVal);
        pw.println("mFast_NeedFastRtsp=" + mFast_NeedFastRtsp);
        pw.println("mFast_DesiredMac=" + mFast_DesiredMac);
        pw.println("mIsNeedRotate=" + mIsNeedRotate);
        pw.println("mIsConnected_OtherP2p=" + mIsConnected_OtherP2p);
        pw.println("mIsConnecting_P2p_Rtsp=" + mIsConnecting_P2p_Rtsp);
        pw.println("mIsWFDConnected=" + mIsWFDConnected);
        pw.println("mDRMContent_Mediaplayer=" + mDRMContent_Mediaplayer);
        pw.println("mPlayerID_Mediaplayer=" + mPlayerID_Mediaplayer);
        ///@}
        pw.println("mAvailableWifiDisplayPeers: size=" + mAvailableWifiDisplayPeers.size());
        for (WifiP2pDevice device : mAvailableWifiDisplayPeers) {
            pw.println("  " + describeWifiP2pDevice(device));
        }
    }

    public void requestStartScan() {
        Slog.i(TAG, Thread.currentThread().getStackTrace()[2].getMethodName() + ",mSinkEnabled:" + mSinkEnabled);

        ///M:@{ WFD Sink Support
        if (SystemProperties.get("ro.mtk_wfd_sink_support").equals("1")  && mSinkEnabled) {
            return;
        }

        if (!mScanRequested) {

            mScanRequested = true;
            updateScanState();
        }
    }

    public void requestStopScan() {
        if (mScanRequested) {
            mScanRequested = false;
            updateScanState();
        }

    }

    public void requestConnect(String address) {
        Slog.i(TAG, Thread.currentThread().getStackTrace()[2].getMethodName() + ", address = " + address);

        ///M:@{
        resetReconnectVariable();

        if (mIsConnected_OtherP2p) {
            showDialog(WFD_WIFIP2P_EXCLUDED_DIALOG);

        } else {
            if (DEBUG) {
                Slog.d(TAG, "mAvailableWifiDisplayPeers dump:");
            }

            for (WifiP2pDevice device : mAvailableWifiDisplayPeers) {
                if (DEBUG) {
                    Slog.d(TAG, "\t" + describeWifiP2pDevice(device));
                }
                if (device.deviceAddress.equals(address)) {
                    connect(device);
                }
            }

        }
        ///@}
    }

    public void requestPause() {
        if (mRemoteDisplay != null) {
            mRemoteDisplay.pause();
        }
    }

    public void requestResume() {
        if (mRemoteDisplay != null) {
            mRemoteDisplay.resume();
        }
    }

    public void requestDisconnect() {
        Slog.i(TAG, Thread.currentThread().getStackTrace()[2].getMethodName());

        disconnect();

        ///M:@{
        resetReconnectVariable();
    }

    private void updateWfdEnableState() {
        Slog.i(TAG, "updateWfdEnableState(), mWifiDisplayOnSetting:" + mWifiDisplayOnSetting + ", mWifiP2pEnabled:" + mWifiP2pEnabled);
        if (mWifiDisplayOnSetting && mWifiP2pEnabled) {

            ///M:@{ Sink support, Reset as source mode
            mSinkEnabled = false;
            ///@}

            // WFD should be enabled.
            if (!mWfdEnabled && !mWfdEnabling) {
                mWfdEnabling = true;

                updateWfdInfo(true);

                ///M:@{ To reduce ping time for HDCP locality check
                if (SystemProperties.get("ro.mtk_wfd_hdcp_tx_support").equals("1") ||
                    SystemProperties.get("ro.mtk_dx_hdcp_support").equals("1")) {
                    updateWifiPowerSavingMode(false);
                }
                ///@}

            }
        } else {

            updateWfdInfo(false);

            ///M:@{ To reduce ping time for HDCP locality check
            if (SystemProperties.get("ro.mtk_wfd_hdcp_tx_support").equals("1") ||
                SystemProperties.get("ro.mtk_dx_hdcp_support").equals("1")) {
                updateWifiPowerSavingMode(true);
            }
            ///@}

            // WFD should be disabled.
            mWfdEnabling = false;
            mWfdEnabled = false;
            reportFeatureState();
            updateScanState();
            disconnect();
            ///M:@{

            // ALPS00812236: dismiss all dialogs when wfd is disabled
            dismissDialog();

            ///@}
        }
    }

    ///M:@{
    private void resetWfdInfo() {
        mWfdInfo.setWfdEnabled(false);
        mWfdInfo.setDeviceType(WifiP2pWfdInfo.WFD_SOURCE);
        mWfdInfo.setSessionAvailable(false);
        mWfdInfo.setUibcSupported(false);
        mWfdInfo.setContentProtected(false);
    }

    private void updateWfdInfo(boolean enable)
    {
        Slog.i(TAG, "updateWfdInfo(), enable:" + enable + ",mWfdEnabling:" + mWfdEnabling);

        ///M:@{
        //WifiP2pWfdInfo wfdInfo = new WifiP2pWfdInfo();
        resetWfdInfo();

        if (!enable) {

            mWfdInfo.setWfdEnabled(false);
            mWifiP2pManager.setWFDInfo(mWifiP2pChannel, mWfdInfo, new ActionListener() {
                    @Override
                    public void onSuccess() {
                        if (DEBUG) {
                            Slog.d(TAG, "Successfully set WFD info.");
                        }
                    }

                    @Override
                    public void onFailure(int reason) {
                        if (DEBUG) {
                            Slog.d(TAG, "Failed to set WFD info with reason " + reason + ".");
                        }
                    }
             });
        } else {

            mWfdInfo.setWfdEnabled(true);

            ///M:@{ WFD Sink Support
            if (SystemProperties.get("ro.mtk_wfd_sink_support").equals("1")  && mSinkEnabled) {
                mWfdInfo.setDeviceType(WifiP2pWfdInfo.PRIMARY_SINK);
            } else {
                mWfdInfo.setDeviceType(WifiP2pWfdInfo.WFD_SOURCE);
            }

            Slog.i(TAG, "Set session available as true");
            mWfdInfo.setSessionAvailable(true);
            mWfdInfo.setControlPort(DEFAULT_CONTROL_PORT);
            mWfdInfo.setMaxThroughput(MAX_THROUGHPUT);

            //M: ALPS00799275: UIBC Support bit
            if (SystemProperties.get("ro.mtk_wfd_sink_support").equals("1")  && mSinkEnabled) {
                if (SystemProperties.get("ro.mtk_wfd_sink_uibc_support").equals("1")) {
                    mWfdInfo.setUibcSupported(true);
                } else {
                    mWfdInfo.setUibcSupported(false);
                }
            } else {
                mWfdInfo.setUibcSupported(true);
            }

            //M: ALPS00799275: CP Support bit
            if (SystemProperties.get("ro.mtk_wfd_hdcp_tx_support").equals("1") ||
                SystemProperties.get("ro.mtk_dx_hdcp_support").equals("1")) {
                mWfdInfo.setContentProtected(true);
            }

            Slog.i(TAG, "HDCP Tx support? " + (SystemProperties.get("ro.mtk_wfd_hdcp_tx_support").equals("1") || SystemProperties.get("ro.mtk_dx_hdcp_support").equals("1")) + ", our wfd info: " + mWfdInfo);

            if (mWfdEnabling) {

                mWifiP2pManager.setWFDInfo(mWifiP2pChannel, mWfdInfo, new ActionListener() {
                    @Override
                    public void onSuccess() {

                        Slog.d(TAG, "Successfully set WFD info.");

                        if (mWfdEnabling) {
                            mWfdEnabling = false;
                            mWfdEnabled = true;
                            reportFeatureState();

                            ///M:@{
                            if (SystemProperties.get("ro.mtk_wfd_support").equals("1") &&
                                mAutoEnableWifi == true) {

                                mAutoEnableWifi = false;
                                Slog.d(TAG, "scan after enable wifi automatically.");
                            }
                            ///@}

                            updateScanState();
                        }
                    }

                    @Override
                    public void onFailure(int reason) {

                        Slog.d(TAG, "Failed to set WFD info with reason " + reason + ".");

                        mWfdEnabling = false;
                    }
                });

            } else {


                mWifiP2pManager.setWFDInfo(mWifiP2pChannel, mWfdInfo, null);
            }

        }


    }

    private void reportFeatureState() {
        final int featureState = computeFeatureState();
        Slog.d(TAG, "reportFeatureState(), featureState = " + featureState);
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                Slog.d(TAG, "callback onFeatureStateChanged(): featureState = " + featureState);
                mListener.onFeatureStateChanged(featureState);
            }
        });
    }

    private int computeFeatureState() {
        if (!mWifiP2pEnabled) {
            if (SystemProperties.get("ro.mtk_wfd_support").equals("1")) {
                if (mWifiDisplayOnSetting) {
                    Slog.d(TAG, "Wifi p2p is disabled, update WIFI_DISPLAY_ON as false.");

                    Settings.Global.putInt(
                        mContext.getContentResolver(), Settings.Global.WIFI_DISPLAY_ON, 0);
                    mWifiDisplayOnSetting = false;
                }
            }
            else {
                return WifiDisplayStatus.FEATURE_STATE_DISABLED;
            }
        }
        return mWifiDisplayOnSetting ? WifiDisplayStatus.FEATURE_STATE_ON :
                WifiDisplayStatus.FEATURE_STATE_OFF;
    }

    private void updateScanState() {
        Slog.i(TAG, "updateScanState(), mSinkEnabled:" + mSinkEnabled);

        ///M:@{ WFD Sink Support
        if (SystemProperties.get("ro.mtk_wfd_sink_support").equals("1")  && mSinkEnabled) {
            return;
        }

        if (mScanRequested && mWfdEnabled && mDesiredDevice == null) {
            if (!mDiscoverPeersInProgress) {

                Slog.i(TAG, "Starting Wifi display scan.");
                mDiscoverPeersInProgress = true;
                handleScanStarted();
                tryDiscoverPeers();
            }
        } else {
            if (mDiscoverPeersInProgress) {
                // Cancel automatic retry right away.
                mHandler.removeCallbacks(mDiscoverPeers);

                // Defer actually stopping discovery if we have a connection attempt in progress.
                // The wifi display connection attempt often fails if we are not in discovery
                // mode.  So we allow discovery to continue until we give up trying to connect.
                if (mDesiredDevice == null || mDesiredDevice == mConnectedDevice) {
                    Slog.i(TAG, "Stopping Wifi display scan.");
                    mDiscoverPeersInProgress = false;
                    stopPeerDiscovery();
                    handleScanFinished();
                }
            }
        }
    }

    private void tryDiscoverPeers() {
        Slog.d(TAG, "tryDiscoverPeers()");
        mWifiP2pManager.discoverPeers(mWifiP2pChannel, new ActionListener() {
            @Override
            public void onSuccess() {
                if (DEBUG) {
                    Slog.d(TAG, "Discover peers succeeded.  Requesting peers now.");
                }

                if (mDiscoverPeersInProgress) {
                    requestPeers();
                }
            }

            @Override
            public void onFailure(int reason) {
                if (DEBUG) {
                    Slog.d(TAG, "Discover peers failed with reason " + reason + ".");
                }

                // Ignore the error.
                // We will retry automatically in a little bit.
            }
        });

        // Retry discover peers periodically until stopped.
        mHandler.postDelayed(mDiscoverPeers, DISCOVER_PEERS_INTERVAL_MILLIS);
    }

    private void stopPeerDiscovery() {
        mWifiP2pManager.stopPeerDiscovery(mWifiP2pChannel, new ActionListener() {
            @Override
            public void onSuccess() {
                if (DEBUG) {
                    Slog.d(TAG, "Stop peer discovery succeeded.");
                }
            }

            @Override
            public void onFailure(int reason) {
                if (DEBUG) {
                    Slog.d(TAG, "Stop peer discovery failed with reason " + reason + ".");
                }
            }
        });
    }

    private void requestPeers() {
        mWifiP2pManager.requestPeers(mWifiP2pChannel, new PeerListListener() {
            @Override
            public void onPeersAvailable(WifiP2pDeviceList peers) {

                ///M:@{ WFD Sink Support
                if (SystemProperties.get("ro.mtk_wfd_sink_support").equals("1")  && mSinkEnabled) {
                    return;
                }

                if (DEBUG) {
                    Slog.d(TAG, "Received list of peers. mDiscoverPeersInProgress=" +
                        mDiscoverPeersInProgress);
                }                
                      
                mAvailableWifiDisplayPeers.clear();
                for (WifiP2pDevice device : peers.getDeviceList()) {
                    if (DEBUG) {
                        Slog.d(TAG, "  " + describeWifiP2pDevice(device));
                    }
                    ///M:@{
                    if (null != mConnectedDevice &&
                        mConnectedDevice.deviceAddress.equals(device.deviceAddress)) {
                        mAvailableWifiDisplayPeers.add(device);
                    } else
                    ///@}
                    if (isWifiDisplay(device)) {
                        mAvailableWifiDisplayPeers.add(device);
                    }
                }
                ///M:@{ Sync devices list with MediaRouter to avoid disconnect
                //if (mDiscoverPeersInProgress) {
                    handleScanResults();
                //}
                ///@}
            }
        });
    }

    private void handleScanStarted() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                Slog.d(TAG, "callback onScanStarted()");
                mListener.onScanStarted();
            }
        });
    }

    private void handleScanResults() {
        final int count = mAvailableWifiDisplayPeers.size();
        final WifiDisplay[] displays = WifiDisplay.CREATOR.newArray(count);
        for (int i = 0; i < count; i++) {
            WifiP2pDevice device = mAvailableWifiDisplayPeers.get(i);
            displays[i] = createWifiDisplay(device);
            updateDesiredDevice(device);
        }

        mHandler.post(new Runnable() {
            @Override
            public void run() {
                Slog.d(TAG, "callback onScanResults(), count = " + count);
                if (DEBUG) {
                    for (int i = 0; i < count; i++) {
                        Slog.d(TAG, "\t" + displays[i].getDeviceName() + ": " + displays[i].getDeviceAddress());
                    }
                }
                mListener.onScanResults(displays);
            }
        });
    }


    private void handleScanFinished() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mListener.onScanFinished();
            }
        });
    }

    private void updateDesiredDevice(WifiP2pDevice device) {
        // Handle the case where the device to which we are connecting or connected
        // may have been renamed or reported different properties in the latest scan.
        final String address = device.deviceAddress;
        if (mDesiredDevice != null && mDesiredDevice.deviceAddress.equals(address)) {
            if (DEBUG) {
                Slog.d(TAG, "updateDesiredDevice: new information "
                        + describeWifiP2pDevice(device));
            }
            mDesiredDevice.update(device);
            if (mAdvertisedDisplay != null
                    && mAdvertisedDisplay.getDeviceAddress().equals(address)) {
                readvertiseDisplay(createWifiDisplay(mDesiredDevice));
            }
        }
    }

    private void connect(final WifiP2pDevice device) {
        Slog.i(TAG, "connect: device name = " + device.deviceName);

        if (mDesiredDevice != null
                && !mDesiredDevice.deviceAddress.equals(device.deviceAddress)) {
            if (DEBUG) {
                Slog.d(TAG, "connect: nothing to do, already connecting to "
                        + describeWifiP2pDevice(mDesiredDevice));    ///Modified by MTK
            }
            return;
        }

        ///M:@{ ALPS00792267: avoid to connect the same dongle rapidly*/
        if (mDesiredDevice != null
                && mDesiredDevice.deviceAddress.equals(device.deviceAddress)) {
            if (DEBUG) {
                Slog.d(TAG, "connect: connecting to the same dongle already "
                        + describeWifiP2pDevice(mDesiredDevice));
            }
            return;
        }
        ///@}

        if (mConnectedDevice != null
                && !mConnectedDevice.deviceAddress.equals(device.deviceAddress)
                && mDesiredDevice == null) {
            if (DEBUG) {
                Slog.d(TAG, "connect: nothing to do, already connected to "
                        + describeWifiP2pDevice(device) + " and not part way through "
                        + "connecting to a different device.");
            }
            return;
        }

        if (!mWfdEnabled) {
            Slog.i(TAG, "Ignoring request to connect to Wifi display because the "
                    +" feature is currently disabled: " + device.deviceName);
            return;
        }

        mDesiredDevice = device;
        mConnectionRetriesLeft = CONNECT_MIN_RETRIES;   ///Modify by MTK
        updateConnection();
    }

    private void disconnect() {
        Slog.i(TAG, "disconnect, mRemoteDisplayInterface = " + mRemoteDisplayInterface);

        ///M:@{ WFD Sink Support
        if (SystemProperties.get("ro.mtk_wfd_sink_support").equals("1")  && mSinkEnabled) {
            disconnectWfdSink();
            return;
        }


        mDesiredDevice = null;
        ///M:@{
        updateWfdStatFile(WFDCONTROLLER_WFD_STAT_DISCONNECT);
        if (null != mConnectedDevice) {
            mReConnectDevice = mConnectedDevice;
        }
        ///@}
        updateConnection();
    }

    private void retryConnection() {
        // Cheap hack.  Make a new instance of the device object so that we
        // can distinguish it from the previous connection attempt.
        // This will cause us to tear everything down before we try again.
        mDesiredDevice = new WifiP2pDevice(mDesiredDevice);
        updateConnection();
    }

    /**
     * This function is called repeatedly after each asynchronous operation
     * until all preconditions for the connection have been satisfied and the
     * connection is established (or not).
     */
    private void updateConnection() {
        // Step 0. Stop scans if necessary to prevent interference while connected.
        // Resume scans later when no longer attempting to connect.
        updateScanState();

        // Step 1. Before we try to connect to a new device, tell the system we
        // have disconnected from the old one.
        ///M:@{
        //if (mRemoteDisplay != null && mConnectedDevice != mDesiredDevice) {
        if ((mRemoteDisplay != null && mConnectedDevice != mDesiredDevice) ||
             (true == mIsConnecting_P2p_Rtsp)) {
            String localInterface = (null != mRemoteDisplayInterface) ? mRemoteDisplayInterface : "localhost";
            String localDeviceName = (null != mConnectedDevice) ? mConnectedDevice.deviceName :
                                        ((null != mConnectingDevice) ? mConnectingDevice.deviceName : "N/A");
            Slog.i(TAG, "Stopped listening for RTSP connection on " + localInterface
                        + " from Wifi display : " + localDeviceName);

            mIsConnected_OtherP2p = false;
            mIsConnecting_P2p_Rtsp = false;
            Slog.i(TAG, "\tbefore dispose() ---> ");
            mListener.onDisplayDisconnecting();
            mRemoteDisplay.dispose();
            Slog.i(TAG, "\t<--- after dispose()");
            ///@}
            mRemoteDisplay = null;
            mRemoteDisplayInterface = null;
            mRemoteDisplayConnected = false;
            mHandler.removeCallbacks(mRtspTimeout);

            mWifiP2pManager.setMiracastMode(WifiP2pManager.MIRACAST_DISABLED);
            unadvertiseDisplay();

            // continue to next step
        }

        // Step 2. Before we try to connect to a new device, disconnect from the old one.
        if (mDisconnectingDevice != null) {
            return; // wait for asynchronous callback
        }
        if (mConnectedDevice != null && mConnectedDevice != mDesiredDevice) {
            Slog.i(TAG, "Disconnecting from Wifi display: " + mConnectedDevice.deviceName);
            mDisconnectingDevice = mConnectedDevice;
            mConnectedDevice = null;
            mConnectedDeviceGroupInfo = null;

            unadvertiseDisplay();

            final WifiP2pDevice oldDevice = mDisconnectingDevice;
            mWifiP2pManager.removeGroup(mWifiP2pChannel, new ActionListener() {
                @Override
                public void onSuccess() {
                    Slog.i(TAG, "Disconnected from Wifi display: " + oldDevice.deviceName);
                    next();
                }

                @Override
                public void onFailure(int reason) {
                    Slog.i(TAG, "Failed to disconnect from Wifi display: "
                            + oldDevice.deviceName + ", reason=" + reason);
                    next();
                }

                private void next() {
                    if (mDisconnectingDevice == oldDevice) {
                        mDisconnectingDevice = null;
                        ///M:@{
                        if (null != mRemoteDisplay) {
                            mIsConnecting_P2p_Rtsp = true;
                        }
                        ///@}
                        updateConnection();
                    }
                }
            });
            return; // wait for asynchronous callback
        }

        // Step 3. Before we try to connect to a new device, stop trying to connect
        // to the old one.
        if (mCancelingDevice != null) {
            return; // wait for asynchronous callback
        }
        if (mConnectingDevice != null && mConnectingDevice != mDesiredDevice) {
            Slog.i(TAG, "Canceling connection to Wifi display: " + mConnectingDevice.deviceName);
            mCancelingDevice = mConnectingDevice;
            mConnectingDevice = null;

            unadvertiseDisplay();
            mHandler.removeCallbacks(mConnectionTimeout);

            final WifiP2pDevice oldDevice = mCancelingDevice;
            mWifiP2pManager.cancelConnect(mWifiP2pChannel, new ActionListener() {
                @Override
                public void onSuccess() {
                    Slog.i(TAG, "Canceled connection to Wifi display: " + oldDevice.deviceName);
                    next();
                }

                @Override
                public void onFailure(int reason) {
                    Slog.i(TAG, "Failed to cancel connection to Wifi display: "
                            + oldDevice.deviceName + ", reason=" + reason
                    ///M:@{ ALPS00530805
                    //It may Group been formed but there isn't enough time to notify WFD fwk, and WFD fwk CancelConnect() first.
                            + ". Do removeGroup()");
                    mWifiP2pManager.removeGroup(mWifiP2pChannel, null);
                    ///@}
                    next();
                }

                private void next() {
                    if (mCancelingDevice == oldDevice) {
                        mCancelingDevice = null;
                        ///M:@{
                        if (null != mRemoteDisplay) {
                            mIsConnecting_P2p_Rtsp = true;
                        }
                        ///@}
                        updateConnection();
                    }
                }
            });
            return; // wait for asynchronous callback
        }

        // Step 4. If we wanted to disconnect, or we're updating after starting an
        // autonomous GO, then mission accomplished.
        if (mDesiredDevice == null) {
            if (mWifiDisplayCertMode) {
                mListener.onDisplaySessionInfo(getSessionInfo(mConnectedDeviceGroupInfo, 0));
            }
            unadvertiseDisplay();
            return; // done
        }

        // Step 5. Try to connect.
        if (mConnectedDevice == null && mConnectingDevice == null) {
            Slog.i(TAG, "Connecting to Wifi display: " + mDesiredDevice.deviceName);

            mConnectingDevice = mDesiredDevice;
            WifiP2pConfig config = new WifiP2pConfig();
            WpsInfo wps = new WpsInfo();
            if (mWifiDisplayWpsConfig != WpsInfo.INVALID) {
                wps.setup = mWifiDisplayWpsConfig;
            } else if (mConnectingDevice.wpsPbcSupported()) {
                wps.setup = WpsInfo.PBC;
            } else if (mConnectingDevice.wpsDisplaySupported()) {
                // We do keypad if peer does display
                wps.setup = WpsInfo.KEYPAD;
            } else if (mConnectingDevice.wpsKeypadSupported()) {
                // We do display if peer does keypad
                wps.setup = WpsInfo.DISPLAY;
            } else {
                // Default use PBC
                wps.setup = WpsInfo.PBC;
            }
            config.wps = wps;
            config.deviceAddress = mConnectingDevice.deviceAddress;

            // Helps with STA & P2P concurrency
            String goIntent = SystemProperties.get(
                "wfd.source.go_intent",
                String.valueOf(WifiP2pConfig.MIN_GROUP_OWNER_INTENT));
            config.groupOwnerIntent = Integer.valueOf(goIntent);

            Slog.i(TAG, "Source go_intent:" + config.groupOwnerIntent);

            WifiDisplay display = createWifiDisplay(mConnectingDevice);
            advertiseDisplay(display, null, 0, 0, 0);

            /// M: @{
            setAutoChannelSelection();

            // Channel Conflict UX Support
            enterCCState(ChannelConflictState.STATE_IDLE);
            
            // ALPS01593529: no p2p_invite in wfd source case @{
            mWifiP2pManager.setMiracastMode(WifiP2pManager.MIRACAST_SOURCE);

            // Prevent wifi scan to improve performance
            stopWifiScan(true);
            ///@}

            final WifiP2pDevice newDevice = mDesiredDevice;
            mWifiP2pManager.connect(mWifiP2pChannel, config, new ActionListener() {
                @Override
                public void onSuccess() {
                    // The connection may not yet be established.  We still need to wait
                    // for WIFI_P2P_CONNECTION_CHANGED_ACTION.  However, we might never
                    // get that broadcast, so we register a timeout.
                    Slog.i(TAG, "Initiated connection to Wifi display: " + newDevice.deviceName);

                    mHandler.postDelayed(mConnectionTimeout, CONNECTION_TIMEOUT_SECONDS * 1000);
                }

                @Override
                public void onFailure(int reason) {
                    if (mConnectingDevice == newDevice) {
                        Slog.i(TAG, "Failed to initiate connection to Wifi display: "
                                + newDevice.deviceName + ", reason=" + reason);
                        mConnectingDevice = null;
                        handleConnectionFailure(false);
                    }
                }
            });
            /*
            /// M: Modify for speed up rtsp setup @{
            if (true == mDRMContent_Mediaplayer) {
                setRemoteSubmixOn(false);
            } else {
                setRemoteSubmixOn(true);
            }
            */
            ///M: ALPS01593529: no p2p_invite in wfd source case @{
            //mWifiP2pManager.setMiracastMode(WifiP2pManager.MIRACAST_SOURCE);
            ///@}
            // ALPS01031660
            mRTSPConnecting = true;


            final WifiP2pDevice oldDevice = mConnectingDevice;
            final int port = getPortNumber(mConnectingDevice);
            final String iface = "127.0.0.1" + ":" + port;
            mRemoteDisplayInterface = iface;

            //mWifiP2pManager.setWfdSessionMode(mWifiP2pChannel, WifiP2pWfdInfo.SETUP, null);  ///Add by MTK
            Slog.i(TAG, "Listening for RTSP connection on " + iface
                    + " from Wifi display: " + mConnectingDevice.deviceName
                    + " , Speed-Up rtsp setup, DRM Content isPlaying = " + mDRMContent_Mediaplayer);

            mRemoteDisplay = RemoteDisplay.listen(iface, new RemoteDisplay.Listener() {
                @Override
                public void onDisplayConnected(Surface surface,
                        int width, int height, int flags, int session)  {
                    /// M: rtsp connected callback faster than wifi broadcast @{
                    if (null != mConnectingDevice) {
                        mConnectedDevice = mConnectingDevice;
                    }
                    if (mConnectedDevice != oldDevice || mRemoteDisplayConnected) {
                        if (DEBUG) {
                            Slog.e(TAG, "!!RTSP connected condition GOT Trobule:" +
                                "\nmConnectedDevice: " + mConnectedDevice +
                                "\noldDevice: " + oldDevice +
                                "\nmRemoteDisplayConnected: " + mRemoteDisplayConnected);
                        }
                    }
                    // M: ApusOne WifiP2pDevice will change some value(wpsConfigMethodsSupported, status) when connected!
                    if (null != mConnectedDevice && null != oldDevice &&
                        mConnectedDevice.deviceAddress.equals(oldDevice.deviceAddress) &&
                        !mRemoteDisplayConnected) {
                    //if (mConnectedDevice == oldDevice && !mRemoteDisplayConnected) {
                    ///@}
                        Slog.i(TAG, "Opened RTSP connection with Wifi display: "
                                + mConnectedDevice.deviceName);
                        mRemoteDisplayConnected = true;
                        mHandler.removeCallbacks(mRtspTimeout);

                        if (mWifiDisplayCertMode) {
                            mListener.onDisplaySessionInfo(
                                    getSessionInfo(mConnectedDeviceGroupInfo, session));
                        }

                        /// M: @{
                        //mWifiP2pManager.setWfdSessionMode(mWifiP2pChannel, WifiP2pWfdInfo.PLAY, null);   ///Add by MTK


                        ///@}
                        updateWfdStatFile(WFDCONTROLLER_WFD_STAT_STREAMING);  ///Add by MTK
                        final WifiDisplay display = createWifiDisplay(mConnectedDevice);
                        advertiseDisplay(display, surface, width, height, flags);
                    }
                    //ALPS01031660
                    mRTSPConnecting = false;
                }

                @Override
                public void onDisplayDisconnected() {
                    if (mConnectedDevice == oldDevice) {
                        Slog.i(TAG, "Closed RTSP connection with Wifi display: "
                                + mConnectedDevice.deviceName);
                        mHandler.removeCallbacks(mRtspTimeout);
                        disconnect();
                        /// M: @{
                        //mWifiP2pManager.setWfdSessionMode(mWifiP2pChannel, WifiP2pWfdInfo.DISCONNECTED, null);


                        ///@}
                    }
                    // ALPS01031660
                    mRTSPConnecting = false;
                }

                @Override
                public void onDisplayError(int error) {
                    if (mConnectedDevice == oldDevice) {
                        Slog.i(TAG, "Lost RTSP connection with Wifi display due to error "
                                + error + ": " + mConnectedDevice.deviceName);
                        mHandler.removeCallbacks(mRtspTimeout);
                        handleConnectionFailure(false);
                    }
                    // ALPS01031660
                    mRTSPConnecting = false;
                }

                @Override
                public void onDisplayKeyEvent(int uniCode, int flags) {
                    Slog.d(TAG, "onDisplayKeyEvent:uniCode=" + uniCode);
                    if (mInputMethodManager != null) {
                        try {
                            if (mWakeLock != null) {
                                mWakeLock.acquire();
                            }
                            mInputMethodManager.sendCharacterToCurClient(uniCode);
                            if (mWakeLock != null) {
                                mWakeLock.release();
                            }
                        } catch (android.os.RemoteException e) {
                            e.printStackTrace();
                        }
                    }
                }

                @Override
                public void onDisplayGenericMsgEvent(int event) {
                    Slog.d(TAG, "onDisplayGenericMsgEvent: " + event);
                }

            }, mHandler);

            // Use extended timeout value for certification, as some tests require user inputs
            int rtspTimeout = mWifiDisplayCertMode ?
                    RTSP_TIMEOUT_SECONDS_CERT_MODE : RTSP_TIMEOUT_SECONDS;

            mHandler.postDelayed(mRtspTimeout, rtspTimeout * 1000);
            ///@}
            return; // wait for asynchronous callback
        }

        // Step 6. Listen for incoming RTSP connection.
        if (mConnectedDevice != null && mRemoteDisplay == null) {
            Inet4Address addr = getInterfaceAddress(mConnectedDeviceGroupInfo);
            if (addr == null) {
                Slog.i(TAG, "Failed to get local interface address for communicating "
                        + "with Wifi display: " + mConnectedDevice.deviceName);
                handleConnectionFailure(false);
                return; // done
            }
            /// M: Modify for speed up rtsp setup @{
            /* move to Step 5.
            mWifiP2pManager.setMiracastMode(WifiP2pManager.MIRACAST_SOURCE);

            final WifiP2pDevice oldDevice = mConnectedDevice;
            final int port = getPortNumber(mConnectedDevice);
            final String iface = addr.getHostAddress() + ":" + port;
            mRemoteDisplayInterface = iface;

            Slog.i(TAG, "Listening for RTSP connection on " + iface
                    + " from Wifi display: " + mConnectedDevice.deviceName);

            mRemoteDisplay = RemoteDisplay.listen(iface, new RemoteDisplay.Listener() {
                @Override
                public void onDisplayConnected(Surface surface,
                        int width, int height, int flags, int session) {
                    if (mConnectedDevice == oldDevice && !mRemoteDisplayConnected) {
                        Slog.i(TAG, "Opened RTSP connection with Wifi display: "
                                + mConnectedDevice.deviceName);
                        mRemoteDisplayConnected = true;
                        mHandler.removeCallbacks(mRtspTimeout);

                        if (mWifiDisplayCertMode) {
                            mListener.onDisplaySessionInfo(
                                    getSessionInfo(mConnectedDeviceGroupInfo, session));
                        }
                        updateWfdStatFile(WFDCONTROLLER_WFD_STAT_STREAMING);  ///Add by MTK
                        final WifiDisplay display = createWifiDisplay(mConnectedDevice);
                        advertiseDisplay(display, surface, width, height, flags);
                    }
                }

                @Override
                public void onDisplayDisconnected() {
                    if (mConnectedDevice == oldDevice) {
                        Slog.i(TAG, "Closed RTSP connection with Wifi display: "
                                + mConnectedDevice.deviceName);
                        mHandler.removeCallbacks(mRtspTimeout);
                        disconnect();
                    }
                }

                @Override
                public void onDisplayError(int error) {
                    if (mConnectedDevice == oldDevice) {
                        Slog.i(TAG, "Lost RTSP connection with Wifi display due to error "
                                + error + ": " + mConnectedDevice.deviceName);
                        mHandler.removeCallbacks(mRtspTimeout);
                        handleConnectionFailure(false);
                    }
                }
                ///M:@{

                @Override
                public void onDisplayGenericMsgEvent(int event){
                    Slog.d(TAG, "onDisplayGenericMsgEvent: " + event);

                }
                ///@}
            }, mHandler);

            // Use extended timeout value for certification, as some tests require user inputs
            int rtspTimeout = mWifiDisplayCertMode ?
                    RTSP_TIMEOUT_SECONDS_CERT_MODE : RTSP_TIMEOUT_SECONDS;

            mHandler.postDelayed(mRtspTimeout, rtspTimeout * 1000);
            */
        }
    }

    private WifiDisplaySessionInfo getSessionInfo(WifiP2pGroup info, int session) {
        if (info == null) {
            return null;
        }
        Inet4Address addr = getInterfaceAddress(info);
        WifiDisplaySessionInfo sessionInfo = new WifiDisplaySessionInfo(
                !info.getOwner().deviceAddress.equals(mThisDevice.deviceAddress),
                session,
                info.getOwner().deviceAddress + " " + info.getNetworkName(),
                info.getPassphrase(),
                (addr != null) ? addr.getHostAddress() : "");
        if (DEBUG) {
            Slog.d(TAG, sessionInfo.toString());
        }
        return sessionInfo;
    }

    private void handleStateChanged(boolean enabled) {

        mWifiP2pEnabled = enabled;
        updateWfdEnableState();
        /// M: ALPS00812236: dismiss all dialogs when wifi p2p is disabled @{
        if (false == enabled) {
            dismissDialog();
        }
        ///@}
    }

    private void handlePeersChanged() {
        // Even if wfd is disabled, it is best to get the latest set of peers to
        // keep in sync with the p2p framework
        requestPeers();
    }

    /// M: reconnect when wifi p2p disconnect and reason is frequency conflict @{
    //private void handleConnectionChanged(NetworkInfo networkInfo) {
    private void handleConnectionChanged(NetworkInfo networkInfo, int reason) {
    ///@}
        Slog.i(TAG, "handleConnectionChanged(), mWfdEnabled:" + mWfdEnabled);
        mNetworkInfo = networkInfo;
        if (mWfdEnabled && networkInfo.isConnected()) {
            if (mDesiredDevice != null || mWifiDisplayCertMode) {
                mWifiP2pManager.requestGroupInfo(mWifiP2pChannel, new GroupInfoListener() {
                    @Override
                    public void onGroupInfoAvailable(WifiP2pGroup info) {
                        if (DEBUG) {
                            Slog.d(TAG, "Received group info: " + describeWifiP2pGroup(info));
                        }

                        if (mConnectingDevice != null && !info.contains(mConnectingDevice)) {
                            Slog.i(TAG, "Aborting connection to Wifi display because "
                                    + "the current P2P group does not contain the device "
                                    + "we expected to find: " + mConnectingDevice.deviceName
                                    + ", group info was: " + describeWifiP2pGroup(info));
                            handleConnectionFailure(false);
                            return;
                        }

                        if (mDesiredDevice != null && !info.contains(mDesiredDevice)) {
                            Slog.i(TAG, "Aborting connection to Wifi display because "
                                + "the current P2P group does not contain the device "
                                + "we desired to find: " + mDesiredDevice.deviceName
                                + ", group info was: " + describeWifiP2pGroup(info));
                            disconnect();
                            return;
                        }

                        if (mWifiDisplayCertMode) {
                            boolean owner = info.getOwner().deviceAddress
                                    .equals(mThisDevice.deviceAddress);
                            if (owner && info.getClientList().isEmpty()) {
                                // this is the case when we started Autonomous GO,
                                // and no client has connected, save group info
                                // and updateConnection()
                                mConnectingDevice = mDesiredDevice = null;
                                mConnectedDeviceGroupInfo = info;
                                updateConnection();
                            } else if (mConnectingDevice == null && mDesiredDevice == null) {
                                // this is the case when we received an incoming connection
                                // from the sink, update both mConnectingDevice and mDesiredDevice
                                // then proceed to updateConnection() below
                                mConnectingDevice = mDesiredDevice = owner ?
                                        info.getClientList().iterator().next() : info.getOwner();
                            }
                        }

                        if (mConnectingDevice != null && mConnectingDevice == mDesiredDevice) {
                            Slog.i(TAG, "Connected to Wifi display: "
                                    + mConnectingDevice.deviceName);

                            mHandler.removeCallbacks(mConnectionTimeout);
                            mConnectedDeviceGroupInfo = info;
                            mConnectedDevice = mConnectingDevice;
                            mConnectingDevice = null;
                            updateWfdStatFile(WFDCONTROLLER_WFD_STAT_STANDBY);  ///Add by MTK
                            updateConnection();
                            
                            // M:@{ Channel Conflict UX Support
                            handleChannelConflictProcedure(ChannelConflictEvt.EVT_WFD_P2P_CONNECTED); 
                        }
                    }
                });
            }
        } else {
            mConnectedDeviceGroupInfo = null;

            // Disconnect if we lost the network while connecting or connected to a display.
            if (mConnectingDevice != null || mConnectedDevice != null) {
                disconnect();
            }

            if (mWfdEnabled) {
                requestPeers();
                if (true == mLastTimeConnected) {

                    if (mReconnectForResolutionChange) {
                        Slog.i(TAG, "requestStartScan() for resolution change.");
                        requestStartScan();
                        // check scan result per RECONNECT_RETRY_DELAY_MILLIS ms
                        mReConnection_Timeout_Remain_Seconds = CONNECTION_TIMEOUT_SECONDS;
                        mHandler.postDelayed(mReConnect, RECONNECT_RETRY_DELAY_MILLIS);
                    }
                }
                ///@}
            }
            mReconnectForResolutionChange = false;

            /// M: frequency conflict is NO_COMMON_CHANNEL==7  @{
            if (7 == reason && null != mReConnectDevice) {
                Slog.i(TAG, "reconnect procedure start, ReConnectDevice = " + mReConnectDevice);
                dialogReconnect();
            }
            ///@}

            // M:@{ Channel Conflict UX Support
            handleChannelConflictProcedure(ChannelConflictEvt.EVT_WFD_P2P_DISCONNECTED);

        }

        ///M: other Wifi P2p trigger connection @{
        if (null == mDesiredDevice) {
            mIsConnected_OtherP2p = networkInfo.isConnected();
            if (true == mIsConnected_OtherP2p) {
                Slog.w(TAG, "Wifi P2p connection is connected but it does not wifidisplay trigger");
                resetReconnectVariable();
            }
        }
        ///@}
    }

    private final Runnable mDiscoverPeers = new Runnable() {
        @Override
        public void run() {
            Slog.d(TAG, "mDiscoverPeers, run()");
            tryDiscoverPeers();
        }
    };

    private final Runnable mConnectionTimeout = new Runnable() {
        @Override
        public void run() {
            if (mConnectingDevice != null && mConnectingDevice == mDesiredDevice) {
                Slog.i(TAG, "Timed out waiting for Wifi display connection after "
                        + CONNECTION_TIMEOUT_SECONDS + " seconds: "
                        + mConnectingDevice.deviceName);
                handleConnectionFailure(true);
            }
        }
    };

    private final Runnable mRtspTimeout = new Runnable() {
        @Override
        public void run() {
            if (mConnectedDevice != null
                    && mRemoteDisplay != null && !mRemoteDisplayConnected) {
                Slog.i(TAG, "Timed out waiting for Wifi display RTSP connection after "
                        + RTSP_TIMEOUT_SECONDS + " seconds: "
                        + mConnectedDevice.deviceName);
                handleConnectionFailure(true);
            }
        }
    };

    private void handleConnectionFailure(boolean timeoutOccurred) {
        Slog.i(TAG, "Wifi display connection failed!");

        if (mDesiredDevice != null) {
            if (mConnectionRetriesLeft > 0) {
                final WifiP2pDevice oldDevice = mDesiredDevice;
                mHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (mDesiredDevice == oldDevice && mConnectionRetriesLeft > 0) {
                            mConnectionRetriesLeft -= 1;
                            Slog.i(TAG, "Retrying Wifi display connection.  Retries left: "
                                    + mConnectionRetriesLeft);
                            retryConnection();
                        }
                    }
                }, timeoutOccurred ? 0 : CONNECT_RETRY_DELAY_MILLIS);
            } else {
                disconnect();
            }
        }
    }

    private void advertiseDisplay(final WifiDisplay display,
            final Surface surface, final int width, final int height, final int flags) {
        if (DEBUG) {
            Slog.d(TAG, "advertiseDisplay(): ----->"
                + "\n\tdisplay: " + display
                + "\n\tsurface: " + surface
                + "\n\twidth: " + width
                + "\n\theight: " + height
                + "\n\tflags: " + flags
                );
        }
        if (!Objects.equal(mAdvertisedDisplay, display)
                || mAdvertisedDisplaySurface != surface
                || mAdvertisedDisplayWidth != width
                || mAdvertisedDisplayHeight != height
                || mAdvertisedDisplayFlags != flags
                ) {   ///Added by MTK
            final WifiDisplay oldDisplay = mAdvertisedDisplay;
            final Surface oldSurface = mAdvertisedDisplaySurface;

            mAdvertisedDisplay = display;
            mAdvertisedDisplaySurface = surface;
            mAdvertisedDisplayWidth = width;
            mAdvertisedDisplayHeight = height;
            mAdvertisedDisplayFlags = flags;

            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (DEBUG) {
                        Slog.d(TAG, "oldSurface = " + oldSurface + ", surface = " + surface +
                            ", oldDisplay = " + oldDisplay + ", display = " + display);
                    }

                    if (oldSurface != null && surface != oldSurface) {
                        Slog.d(TAG, "callback onDisplayDisconnected()");
                        mListener.onDisplayDisconnected();
                        actionAtDisconnected(oldDisplay);  ///Add by MTK
                    } else if (oldDisplay != null && !oldDisplay.hasSameAddress(display)) {
                        Slog.d(TAG, "callback onDisplayConnectionFailed()");
                        mListener.onDisplayConnectionFailed();
                        actionAtConnectionFailed();  ///Add by MTK
                    }

                    if (display != null) {
                        if (!display.hasSameAddress(oldDisplay)) {
                            Slog.d(TAG, "callback onDisplayConnecting(): display = " + display);
                            mListener.onDisplayConnecting(display);
                            actionAtConnecting();  ///Add by MTK
                        } else if (!display.equals(oldDisplay)) {
                            // The address is the same but some other property such as the
                            // name must have changed.
                            mListener.onDisplayChanged(display);
                        }
                        if (surface != null && surface != oldSurface) {

                            updateIfHdcp(flags);  ///Add by MTK

                            Slog.d(TAG, "callback onDisplayConnected()"
                                + ": display = " + display
                                + ", surface = " + surface
                                + ", width = " + width
                                + ", height = " + height
                                + ", flags = " + flags);
                            mListener.onDisplayConnected(display, surface, width, height, flags);
                            actionAtConnected(display, flags);  ///Add by MTK
                        }
                    }
                }
            });
        }
        ///M:@{
        else {
            if (DEBUG) {
                Slog.d(TAG, "advertiseDisplay() : no need update!");
            }
        }
        ///@}
    }

    private void unadvertiseDisplay() {
        advertiseDisplay(null, null, 0, 0, 0);
    }

    private void readvertiseDisplay(WifiDisplay display) {
        advertiseDisplay(display, mAdvertisedDisplaySurface,
                mAdvertisedDisplayWidth, mAdvertisedDisplayHeight,
                mAdvertisedDisplayFlags);
    }

    private static Inet4Address getInterfaceAddress(WifiP2pGroup info) {
        NetworkInterface iface;
        try {
            iface = NetworkInterface.getByName(info.getInterface());
        } catch (SocketException ex) {
            Slog.w(TAG, "Could not obtain address of network interface "
                    + info.getInterface(), ex);
            return null;
        }

        Enumeration<InetAddress> addrs = iface.getInetAddresses();
        while (addrs.hasMoreElements()) {
            InetAddress addr = addrs.nextElement();
            if (addr instanceof Inet4Address) {
                return (Inet4Address)addr;
            }
        }

        Slog.w(TAG, "Could not obtain address of network interface "
                + info.getInterface() + " because it had no IPv4 addresses.");
        return null;
    }

    private static int getPortNumber(WifiP2pDevice device) {
        if (device.deviceName.startsWith("DIRECT-")
                && device.deviceName.endsWith("Broadcom")) {
            // These dongles ignore the port we broadcast in our WFD IE.
            return 8554;
        }
        return DEFAULT_CONTROL_PORT;
    }

    private static boolean isWifiDisplay(WifiP2pDevice device) {
        return device.wfdInfo != null
                && device.wfdInfo.isWfdEnabled()
                && device.wfdInfo.isSessionAvailable()  ///Add by MTK
                && isPrimarySinkDeviceType(device.wfdInfo.getDeviceType());
    }

    private static boolean isPrimarySinkDeviceType(int deviceType) {
        return deviceType == WifiP2pWfdInfo.PRIMARY_SINK
                || deviceType == WifiP2pWfdInfo.SOURCE_OR_PRIMARY_SINK;
    }

    private static String describeWifiP2pDevice(WifiP2pDevice device) {
        return device != null ? device.toString().replace('\n', ',') : "null";
    }

    private static String describeWifiP2pGroup(WifiP2pGroup group) {
        return group != null ? group.toString().replace('\n', ',') : "null";
    }

    private static WifiDisplay createWifiDisplay(WifiP2pDevice device) {
        return new WifiDisplay(device.deviceAddress, device.deviceName, null,
                true, device.wfdInfo.isSessionAvailable(), false);
    }

    private final BroadcastReceiver mWifiP2pReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action.equals(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)) {
                // This broadcast is sticky so we'll always get the initial Wifi P2P state
                // on startup.
                boolean enabled = (intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE,
                        WifiP2pManager.WIFI_P2P_STATE_DISABLED)) ==
                        WifiP2pManager.WIFI_P2P_STATE_ENABLED;

                Slog.d(TAG, "Received WIFI_P2P_STATE_CHANGED_ACTION: enabled=" + enabled);


                handleStateChanged(enabled);
            } else if (action.equals(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)) {
                if (DEBUG) {
                    Slog.d(TAG, "Received WIFI_P2P_PEERS_CHANGED_ACTION.");
                }

                handlePeersChanged();
            } else if (action.equals(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)) {
                NetworkInfo networkInfo = (NetworkInfo)intent.getParcelableExtra(
                        WifiP2pManager.EXTRA_NETWORK_INFO);
                ///M: ALPS00677009: receive p2p broadcast to know the group removed reason @{
                int reason = intent.getIntExtra("reason=", -1);
                if (DEBUG) {
                    Slog.d(TAG, "Received WIFI_P2P_CONNECTION_CHANGED_ACTION: networkInfo="
                        + networkInfo + ", reason = " + reason);
                } else {
                    Slog.d(TAG, "Received WIFI_P2P_CONNECTION_CHANGED_ACTION: isConnected? "
                        + networkInfo.isConnected() + ", reason = " + reason);
                }

                // Update channel ID
                updateWifiP2pChannelId(networkInfo.isConnected(), intent);

                ///M:@{ WFD Sink Support
                if (SystemProperties.get("ro.mtk_wfd_sink_support").equals("1")  && mSinkEnabled) {
                    if (reason != -2) {
                        handleSinkP2PConnection(networkInfo);
                    }
                    return;
                }

                //handleConnectionChanged(networkInfo);
                handleConnectionChanged(networkInfo, reason);
                // ALPS00834020: record disconnect last time
                mLastTimeConnected = networkInfo.isConnected();                
                
                ///@}
            } else if (action.equals(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)) {
                mThisDevice = (WifiP2pDevice) intent.getParcelableExtra(
                        WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
                if (DEBUG) {
                    Slog.d(TAG, "Received WIFI_P2P_THIS_DEVICE_CHANGED_ACTION: mThisDevice= "
                            + mThisDevice);
                }
            }
            ///M:@{
            else if (action.equals(DRM_CONTENT_MEDIAPLAYER)) {
                int playerID = 0;
                mDRMContent_Mediaplayer = intent.getBooleanExtra("isPlaying", false);
                playerID = intent.getIntExtra("playerId", 0);
                Slog.i(TAG, "Received DRM_CONTENT_MEDIAPLAYER: isPlaying = " + mDRMContent_Mediaplayer
                        + ", player = " + playerID
                        + ", isConnected = " + mIsWFDConnected
                        + ", isConnecting = " + mRTSPConnecting);

                if (true == mIsWFDConnected ||
                        true == mRTSPConnecting) {   // ALPS01031660
                    if (true == mDRMContent_Mediaplayer) {
                        //setRemoteSubmixOn(false);
                        mPlayerID_Mediaplayer = playerID;
                    } else {
                        if (mPlayerID_Mediaplayer == playerID) {
                            //setRemoteSubmixOn(true);
                        } else {
                            Slog.w(TAG, "player ID doesn't match last time: " + mPlayerID_Mediaplayer);
                        }
                    }
                }
            }
            /*M: ALPS00645645: add protection if scan result isn't replied from supplicant*/
            else if (action.equals(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)) {
                int discoveryState = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE,
                    WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED);
                if (DEBUG) {
                    Slog.d(TAG, "Received WIFI_P2P_DISCOVERY_CHANGED_ACTION: discoveryState="
                        + discoveryState);
                }
                if (discoveryState == WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED) {
                    handleScanFinished();
                }
            }
            /*M: ALPS00772074: do disconnect for every power-off case*/
            else if (action.equals(WFDCONTROLLER_PRE_SHUTDOWN)) {
                Slog.i(TAG, "Received " + WFDCONTROLLER_PRE_SHUTDOWN
                    + ", do disconnect anyway");

                // non-blocking API first
                if (null != mWifiP2pManager) {
                    mWifiP2pManager.removeGroup(mWifiP2pChannel, null);
                }
                // blocking API after
                if (null != mRemoteDisplay) {
                    mRemoteDisplay.dispose();
                }
            }
            /*M: ALPS01012422: can't scan any dongles when wifi ap is connected */
            else if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
                NetworkInfo info = (NetworkInfo) intent.getParcelableExtra(
                        WifiManager.EXTRA_NETWORK_INFO);

                boolean connected = info.isConnected(); 
                if (connected == mWifiApConnected) {
                    return;
                }
                
                mWifiApConnected = connected;

                if (mWifiApConnected) {
                    WifiInfo conInfo = mWifiManager.getConnectionInfo();
                    if (conInfo != null) {
                        mWifiApSsid = conInfo.getSSID();   
                        mWifiApFreq = conInfo.getFrequency();
                        mWifiNetworkId = conInfo.getNetworkId();
                    }                    
                } else {
                    mWifiApSsid = null;
                    mWifiApFreq = 0;
                    mWifiNetworkId = -1;
                }
                Slog.i(TAG, "Received NETWORK_STATE_CHANGED,con:" + mWifiApConnected + ",SSID:" + mWifiApSsid + ",Freq:" + mWifiApFreq + ",netId:" + mWifiNetworkId);

                ///M:@{ WFD Sink Support
                if (SystemProperties.get("ro.mtk_wfd_sink_support").equals("1")  && mSinkEnabled) {
                    setSinkMiracastMode();
                }
        
                // M:@{ Channel Conflict UX Support
                handleChannelConflictProcedure( 
                    mWifiApConnected ? 
                    ChannelConflictEvt.EVT_AP_CONNECTED : 
                    ChannelConflictEvt.EVT_AP_DISCONNECTED);
            }
            else if (action.equals(WFD_SINK_GC_REQUEST_CONNECT)) {
                mSinkDeviceName = intent.getStringExtra("deviceName");

                Slog.i(TAG, "Received WFD_SINK_GC_REQUEST_CONNECT, mSinkDeviceName:" + mSinkDeviceName);

                if (!isSinkState(SinkState.SINK_STATE_WAITING_P2P_CONNECTION)) {
                    Slog.d(TAG, "State is wrong. Decline directly !!");
                    mWifiP2pManager.setGCInviteResult(mWifiP2pChannel, false, 0, null);
                    return;
                }

                if (mSinkDeviceName != null) {
                    // Show progress dialog
                    showDialog(WFD_CONFIRM_CONNECT_DIALOG);
                }
            }
            // M:@{ Channel Conflict UX Support
            else if (action.equals(WFD_CHANNEL_CONFLICT_OCCURS)){
                mP2pOperFreq = intent.getIntExtra("p2pOperFreq", -1);

                Slog.i(TAG, "Received WFD_CHANNEL_CONFLICT_OCCURS, p2pOperFreq:" + mP2pOperFreq);
                if (mP2pOperFreq != -1) {
                    startChannelConflictProcedure();
                }
            }
            else if (action.equals(WFD_SINK_CHANNEL_CONFLICT_OCCURS)){
                Slog.i(TAG, "Received WFD_SINK_CHANNEL_CONFLICT_OCCURS, mSinkEnabled:" + mSinkEnabled + ", apConnected:" + mWifiApConnected);
                
                ///M:@{ WFD Sink Support
                if (SystemProperties.get("ro.mtk_wfd_sink_support").equals("1")  && mSinkEnabled) {
                    if (mWifiApConnected){

                        //WiFi P2P FW will disconnect the current AP
                        notifyApDisconnected();
                    }
                }                
            }
            ///@}
        }
    };

    /**
     * Called on the handler thread when displays are connected or disconnected.
     */
    public interface Listener {
        void onFeatureStateChanged(int featureState);

        void onScanStarted();
        void onScanResults(WifiDisplay[] availableDisplays);
        void onScanFinished();

        void onDisplayConnecting(WifiDisplay display);
        void onDisplayConnectionFailed();
        void onDisplayChanged(WifiDisplay display);
        void onDisplayConnected(WifiDisplay display,
                Surface surface, int width, int height, int flags);
        void onDisplaySessionInfo(WifiDisplaySessionInfo sessionInfo);
        void onDisplayDisconnected();
        void onDisplayDisconnecting();  ///Add by MTK
    }

    ///Add by MTK @{
    private void sendKeyEvent(int keyCode, int isDown) {
        long now = SystemClock.uptimeMillis();
        if (isDown == 1) {
            injectKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, translateAsciiToKeyCode(keyCode), 0, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD));
        } else {
            injectKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, translateAsciiToKeyCode(keyCode), 0, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD));
        }
    }

    private void sendTap(float x, float y) {
        long now = SystemClock.uptimeMillis();
        injectPointerEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0));
        injectPointerEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, x, y, 0));
    }

    private void injectKeyEvent(KeyEvent event) {
        Slog.d(TAG, "InjectKeyEvent: " + event);
        InputManager.getInstance().injectInputEvent(event,
            InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH);
    }

    private void injectPointerEvent(MotionEvent event) {
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        Slog.d("Input", "InjectPointerEvent: " + event);
        InputManager.getInstance().injectInputEvent(event,
            InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH);
    }

    //Support for UIBC mechanism
    private int translateSpecialCode(int ascii) {
        int newKeyCode = 0;
        switch(ascii) {
        case 8: //Backspace
            newKeyCode = KeyEvent.KEYCODE_DEL;
          break;
        case 13:
            newKeyCode = KeyEvent.KEYCODE_ENTER;
            break;
        case 16:
            newKeyCode = KeyEvent.KEYCODE_SHIFT_LEFT;
            break;
        case 20:
            newKeyCode = KeyEvent.KEYCODE_CAPS_LOCK;
            break;
        case 32: //space
            newKeyCode = KeyEvent.KEYCODE_SPACE;
            break;
        case 12: //Enter
            newKeyCode = KeyEvent.KEYCODE_ENTER;
            break;
        case 190: //.
            newKeyCode = KeyEvent.KEYCODE_PERIOD;
            break;
        case 188: //,
            newKeyCode = KeyEvent.KEYCODE_COMMA;
            break;
        case 191:  //'/'
            newKeyCode = KeyEvent.KEYCODE_SLASH;
            break;
        case 220: //'\'
            newKeyCode = KeyEvent.KEYCODE_BACKSLASH;
            break;
        case 34: //page down
            newKeyCode = KeyEvent.KEYCODE_PAGE_UP;
            break;
        case 33: //page up
            newKeyCode = KeyEvent.KEYCODE_PAGE_DOWN;
            break;
        case 37: // up
            newKeyCode = KeyEvent.KEYCODE_DPAD_UP;
            break;
        case 38: //down
            newKeyCode = KeyEvent.KEYCODE_DPAD_DOWN;
            break;
        case 39: //Right
            newKeyCode = KeyEvent.KEYCODE_DPAD_RIGHT;
            break;
        case 40: //Left
            newKeyCode = KeyEvent.KEYCODE_DPAD_LEFT;
            break;
        case 27: //Esc
            newKeyCode = KeyEvent.KEYCODE_ESCAPE;
            break;
        case 219: //[
            newKeyCode = KeyEvent.KEYCODE_LEFT_BRACKET;
            break;
        case 221: //]
            newKeyCode = KeyEvent.KEYCODE_RIGHT_BRACKET;
            break;
        case 192:
            newKeyCode = KeyEvent.KEYCODE_GRAVE;
            break;
        case 189:
            newKeyCode = KeyEvent.KEYCODE_MINUS;
            break;
        case 187:
            newKeyCode = KeyEvent.KEYCODE_EQUALS;
            break;
        case 186:
            newKeyCode = KeyEvent.KEYCODE_SEMICOLON;
            break;
        case 222:
            newKeyCode = KeyEvent.KEYCODE_APOSTROPHE;
            break;
        }

        return newKeyCode;
    }

    private  int translateAsciiToKeyCode(int ascii) {
        if (ascii >= 48 && ascii <= 57) {  // 0~9
            return (ascii - 41);
        } else if (ascii >= 65 && ascii <= 90) { //A~Z
            return (ascii - 36);
        } else {
            int newKeyCode = translateSpecialCode(ascii);
            if (newKeyCode > 0) {
                Slog.d(TAG, "special code: " + ascii + ":" + newKeyCode);
                return newKeyCode;
            }
            Slog.d(TAG, "translateAsciiToKeyCode: ascii is not supported" + ascii);
        }
        return 0;
    }

    private void getWifiLock() {
        if (null == mWifiManager) {
            mWifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        }
        if (null == mWifiLock && null != mWifiManager) {
            mWifiLock = mWifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL , "WFD_WifiLock");
        }
    }

    private void updateIfHdcp(int flags) {
        boolean secure = (flags & RemoteDisplay.DISPLAY_FLAG_SECURE) != 0;
        if (secure) {
            SystemProperties.set("media.wfd.hdcp", "1");
        }
        else {
            SystemProperties.set("media.wfd.hdcp", "0");
        }
    }

    private void stopWifiScan(boolean ifStop) {
        if (mStopWifiScan != ifStop) {
            Slog.i(TAG, "stopWifiScan()," + ifStop);
            mWifiManager.stopReconnectAndScan(ifStop, 0);
            mStopWifiScan = ifStop;
        }
    }

    private void actionAtConnected(WifiDisplay display, int flags) {
        Slog.i(TAG, Thread.currentThread().getStackTrace()[2].getMethodName());

        /* Keep Google MR1 original behavior
        if (DEBUG) {
            Slog.d(TAG, Thread.currentThread().getStackTrace()[2].getMethodName() + ", mIsNeedRotate = " + mIsNeedRotate);
        }
        if (true == mIsNeedRotate) {
            Settings.System.putInt(mContext.getContentResolver(), Settings.System.USER_ROTATION, Surface.ROTATION_90);
        }
        mBackupShowTouchVal = Settings.System.getInt(mContext.getContentResolver(), Settings.System.SHOW_TOUCHES, 0);
        Settings.System.putInt(mContext.getContentResolver(), Settings.System.SHOW_TOUCHES, 1);
        Settings.System.putInt(mContext.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, WindowManagerPolicy.USER_ROTATION_FREE);
        //mBackupScreenOffTimeout = Settings.System.getInt(mContext.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, 0);
        //Settings.System.putInt(mContext.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, 30*60*1000);  // 30minutes
        */
        mIsWFDConnected = true;

        Intent intent = new Intent(WFD_CONNECTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra("connected", 1);
        if (null != display) {
            intent.putExtra("device_address", display.getDeviceAddress());
            intent.putExtra("device_name", display.getDeviceName());
            intent.putExtra("device_alias", display.getDeviceAlias());
        } else {
            Slog.e(TAG, Thread.currentThread().getStackTrace()[2].getMethodName() + ", null display");
            intent.putExtra("device_address", "00:00:00:00:00:00");
            intent.putExtra("device_name", "wifidisplay dongle");
            intent.putExtra("device_alias", "wifidisplay dongle");
        }

        // for HDCP
        boolean secure = (flags & RemoteDisplay.DISPLAY_FLAG_SECURE) != 0;
        if (secure) {
            intent.putExtra("secure", 1);
        }
        else {
            intent.putExtra("secure", 0);
        }
        Slog.i(TAG, "secure:" + secure);

        // for UIBC
        int usingUIBC = mRemoteDisplay.getWfdParam(8);

        // bit0: touch, bit1: mouse, bit2: keyboard
        if ((usingUIBC & (1 << 0)) != 0 ||
            (usingUIBC & (1 << 1)) != 0) {
            intent.putExtra("uibc_touch_mouse", 1);
        }
        else {
            intent.putExtra("uibc_touch_mouse", 0);
        }

        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);

        if (true == mReConnecting) {
            resetReconnectVariable();
        }

        getWifiLock();
        if (null != mWifiManager && null != mWifiLock) {
            if (!mWifiLock.isHeld()) {
                if (DEBUG) {
                    Slog.i(TAG, "acquire wifilock");
                }
                mWifiLock.acquire();
            } else {
                Slog.e(TAG, "WFD connected, and WifiLock is Held!");
            }
        } else {
            Slog.e(TAG, "actionAtConnected(): mWifiManager: " + mWifiManager
                + ", mWifiLock: " + mWifiLock);
        }

        if (WFDCONTROLLER_QE_ON) {
            // For Quality Enhancement
            mHandler.postDelayed(mWifiLinkInfo, WFDCONTROLLER_LINK_INFO_PERIOD_MILLIS);   ///Add by MTK
            resetSignalParam();
        }


        if (SystemProperties.get("ro.mtk_wfd_support").equals("1")) {
            // BT & WFD concurrency
            boolean show = SystemProperties.getInt("af.policy.r_submix_prio_adjust", 0) == 0;
            if (show) {
                checkA2dpStatus();
            }

            // Show WFD capability
            updateChosenCapability(usingUIBC);

            IBinder b = ServiceManager.getService(Context.INPUT_METHOD_SERVICE);
            mInputMethodManager = IInputMethodManager.Stub.asInterface(b);

            // Start profiling timer
            if (mLatencyProfiling == 3) {

                mHandler.postDelayed(
                    mDelayProfiling,
                    WFDCONTROLLER_LATENCY_INFO_DELAY_MILLIS);
            }
        }

        //Notify clear motion
        notifyClearMotion(true);


        // check if Wifi AP (11G) affects WFD quality
        if (mWifiApConnected) {
            checkIfWifiApIs11G();
        }        
    }

    private void actionAtDisconnected(WifiDisplay display) {
        Slog.i(TAG, Thread.currentThread().getStackTrace()[2].getMethodName());

        /* Keep Google MR1 original behavior
        if (DEBUG) {
            Slog.d(TAG, Thread.currentThread().getStackTrace()[2].getMethodName() + ", mIsNeedRotate = " + mIsNeedRotate);
        }
        if (true == mIsNeedRotate) {
            Settings.System.putInt(mContext.getContentResolver(), Settings.System.USER_ROTATION, Surface.ROTATION_0);
        }
        Settings.System.putInt(mContext.getContentResolver(), Settings.System.SHOW_TOUCHES, mBackupShowTouchVal);
        Settings.System.putInt(mContext.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, WindowManagerPolicy.USER_ROTATION_LOCKED);
        //Settings.System.putInt(mContext.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, mBackupScreenOffTimeout);
        */
        mIsWFDConnected = false;

        Intent intent = new Intent(WFD_CONNECTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra("connected", 0);
        if (null != display) {
            intent.putExtra("device_address", display.getDeviceAddress());
            intent.putExtra("device_name", display.getDeviceName());
            intent.putExtra("device_alias", display.getDeviceAlias());
        } else {
            Slog.e(TAG, Thread.currentThread().getStackTrace()[2].getMethodName() + ", null display");
            intent.putExtra("device_address", "00:00:00:00:00:00");
            intent.putExtra("device_name", "wifidisplay dongle");
            intent.putExtra("device_alias", "wifidisplay dongle");
        }
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);

        if (true == mReConnecting) {
            Toast.makeText(mContext, com.mediatek.internal.R.string.wifi_display_disconnected
                , Toast.LENGTH_SHORT).show();
            resetReconnectVariable();
        }

        getWifiLock();
        if (null != mWifiManager && null != mWifiLock) {
            if (mWifiLock.isHeld()) {
                if (DEBUG) {
                    Slog.i(TAG, "release wifilock");
                }
                mWifiLock.release();
            } else {
                Slog.e(TAG, "WFD disconnected, and WifiLock isn't Held!");
            }
        } else {
            Slog.e(TAG, "actionAtDisconnected(): mWifiManager: " + mWifiManager
                + ", mWifiLock: " + mWifiLock);
        }

        if (WFDCONTROLLER_QE_ON) {
            mHandler.removeCallbacks(mWifiLinkInfo);
        }
        clearNotify();


        if (SystemProperties.get("ro.mtk_wfd_support").equals("1")) {

            // Show WFD capability
            updateChosenCapability(0);

            //stop profiling status
            stopProfilingInfo();
        }


        //Notify clear motion
        notifyClearMotion(false);

        // Resume wifi scan
        stopWifiScan(false);
    }

    private void actionAtConnecting() {
        Slog.i(TAG, Thread.currentThread().getStackTrace()[2].getMethodName());

        //if (null != mReConnectDevice) {
        //    Slog.i(TAG, "set mReConnecting as true");
        //    mReConnecting = true;
        //}
    }

    private void actionAtConnectionFailed() {
        Slog.i(TAG, Thread.currentThread().getStackTrace()[2].getMethodName());

        if (true == mReConnecting) {
            Toast.makeText(mContext, com.mediatek.internal.R.string.wifi_display_disconnected
                , Toast.LENGTH_SHORT).show();
            resetReconnectVariable();
        }
        // Resume wifi scan
        stopWifiScan(false);
    }

    private int loadWfdWpsSetup() {
        String wfdWpsSetup = SystemProperties.get("wlan.wfd.wps.setup", "1");
        if (DEBUG) {
            Slog.d(TAG, Thread.currentThread().getStackTrace()[2].getMethodName() + ", wfdWpsSetup = " + wfdWpsSetup);
        }
        switch (Integer.valueOf(wfdWpsSetup)) {
            case 0:
                return WpsInfo.KEYPAD;
            case 1:
                return WpsInfo.PBC;
            default:
                return WpsInfo.PBC;
        }
    }

    private void loadDebugLevel() {
        String debugLevel = SystemProperties.get("wlan.wfd.controller.debug", "0");
        if (DEBUG) {
            Slog.d(TAG, Thread.currentThread().getStackTrace()[2].getMethodName() + ", debugLevel = " + debugLevel);
        }
        switch (Integer.valueOf(debugLevel)) {
            case 0:
                DEBUG = false;
                break;
            case 1:
                DEBUG = true;
                break;
            default:
                DEBUG = false;
                break;
        }
    }

    private void enableWifiDisplay() {

        mHandler.removeCallbacks(mEnableWifiDelay);

        // Enable wifi
        if (SystemProperties.get("ro.mtk_wfd_support").equals("1") &&
            mWifiDisplayOnSetting && !mWifiP2pEnabled) {

            long delay = Settings.Global.getLong(mContext.getContentResolver(),
                Settings.Global.WIFI_REENABLE_DELAY_MS, 500);

            Slog.d(TAG, "Enable wifi with delay:" + delay);
            mHandler.postDelayed(mEnableWifiDelay, delay);

            //show toast
            Toast.makeText(mContext, com.mediatek.internal.R.string.wifi_display_wfd_and_wifi_are_turned_on
                , Toast.LENGTH_SHORT).show();
        }
        else {
            mAutoEnableWifi = false;
            updateWfdEnableState();
        }
    }


    private void updateSettingsHDMI() {
        final ContentResolver resolver = mContext.getContentResolver();

	boolean HDMIOn = false;
	if (SystemProperties.get("ro.mtk_hdmi_support").equals("1")) {
	    HDMIOn = Settings.System.getInt(resolver,Settings.System.HDMI_ENABLE_STATUS, 0) != 0;
	}


        if (true == HDMIOn && true == mWifiDisplayOnSetting) {
            if (WifiDisplayStatus.FEATURE_STATE_ON == computeFeatureState()) {
                dialogWfdHdmiConflict(WFDCONTROLLER_HDMI_UPDATE);

            } else {
                if (DEBUG) {
                    Slog.d(TAG, "HDMI on and WFD feature state isn't on --> turn off WifiDisplay directly");
                }
                Settings.Global.putInt(mContext.getContentResolver(), Settings.Global.WIFI_DISPLAY_ON, 0);
            }

        }
    }

    private void updateWfdStatFile(int wfd_stat) {
        /* disable it due to native module is not ready
        if (DEBUG) {
            Slog.d(TAG, Thread.currentThread().getStackTrace()[2].getMethodName() + ", wfd_stat = " + wfd_stat);
        }

        try {
            FileOutputStream fbp = new FileOutputStream(WFDCONTROLLER_WFD_STAT_FILE);
            fbp.write(wfd_stat);
            fbp.flush();
            fbp.close();
        } catch (FileNotFoundException e) {
            if (DEBUG) {
                Slog.e(TAG, "Failed to find " + WFDCONTROLLER_WFD_STAT_FILE);
            }
        } catch (java.io.IOException e) {
            if (DEBUG) {
                Slog.e(TAG, "Failed to open " + WFDCONTROLLER_WFD_STAT_FILE);
            }
        }
        */
    }

    private void dialogWfdHdmiConflict(int which) {
        if (DEBUG) {
            Slog.d(TAG, Thread.currentThread().getStackTrace()[2].getMethodName()
                + ", which = " + which);
        }

        if (WFDCONTROLLER_WFD_UPDATE == which) {
            showDialog(WFD_HDMI_EXCLUDED_DIALOG_WFD_UPDATE);

        } else if (WFDCONTROLLER_HDMI_UPDATE == which) {
            showDialog(WFD_HDMI_EXCLUDED_DIALOG_HDMI_UPDATE);

        }

    }

    private static final Pattern wfdLinkInfoPattern = Pattern.compile(
            "sta_addr=((?:[0-9a-f]{2}:){5}[0-9a-f]{2}|any)\n" +
            "link_score=(.*)\n" +
            "per=(.*)\n" +
            "rssi=(.*)\n" +
            "phy=(.*)\n" +
            "rate=(.*)\n" +
            "total_cnt=(.*)\n" +
            "threshold_cnt=(.*)\n" +
            "fail_cnt=(.*)\n" +
            "timeout_cnt=(.*)\n" +
            "apt=(.*)\n" +
            "aat=(.*)\n" +
            "TC_buf_full_cnt=(.*)\n" +
            "TC_sta_que_len=(.*)\n" +
            "TC_avg_que_len=(.*)\n" +
            "TC_cur_que_len=(.*)\n" +
            "flag=(.*)\n" +
            "reserved0=(.*)\n" +
            "reserved1=(.*)");

    private final Runnable mWifiLinkInfo = new Runnable() {
        @Override
        public void run() {
            if (null == mConnectedDevice) {
                Slog.e(TAG, Thread.currentThread().getStackTrace()[2].getMethodName() + ": ConnectedDevice is null");

            } else if (null == mRemoteDisplay) {
                Slog.e(TAG, Thread.currentThread().getStackTrace()[2].getMethodName() + ": RemoteDisplay is null");

            } else {
                mWifiP2pManager.requestWifiP2pLinkInfo(mWifiP2pChannel, mConnectedDevice.deviceAddress, new WifiP2pLinkInfoListener() {
                    @Override
                    public void onLinkInfoAvailable(WifiP2pLinkInfo status) {
                        //if (DEBUG) {
                        //    Slog.i(TAG, Thread.currentThread().getStackTrace()[2].getMethodName() + ", linkInfo: \n" + status.linkInfo);
                        //}

                        if (null != status && null != status.linkInfo) {
                            Matcher match;
                            match = wfdLinkInfoPattern.matcher(status.linkInfo);
                            if (match.find()) {
                                mWifiScore = parseDec(match.group(2));
                                mRSSI = parseFloat(match.group(4));
                                mWifiRate = parseFloat(match.group(6));

                                // Update signal level
                                updateSignalLevel();

                            } else {
                                Slog.e(TAG, "wfdLinkInfoPattern Malformed Pattern, not match String ");
                            }

                        } else {
                            Slog.e(TAG, "onLinkInfoAvailable() parameter is null!");
                        }

                    }

                });

                mHandler.postDelayed(mWifiLinkInfo, WFDCONTROLLER_LINK_INFO_PERIOD_MILLIS);
            }

        }
    };

    private int parseDec(String decString) {
        int num = 0;

        try {
            num = Integer.parseInt(decString);
        } catch (NumberFormatException e) {
            Slog.e(TAG, "Failed to parse dec string " + decString);
        }
        return num;
    }

    private int parseFloat(String floatString) {
        int num = 0;

        try {
            num = (int) Float.parseFloat(floatString);
        } catch (NumberFormatException e) {
            Slog.e(TAG, "Failed to parse float string " + floatString);
        }
        return num;
    }


    private void updateSignalLevel() {

        // Update average score
        int avarageScore = getAverageScore();

        // Update score level
        updateScoreLevel(avarageScore);

        String message = "W:" + avarageScore + ",L:" + mLevel;

        // Update signal level
        if (mScoreLevel >= 6) {

            //Set level to encoder
            if (mRemoteDisplay != null) {
                if (DEBUG) {
                    Slog.d(TAG, "setWfdLevel():+2");
                }

                mRemoteDisplay.setWfdLevel(2);
                mLevel += 2;
            }

            mScoreLevel = 0;
        }
        else if (mScoreLevel >= 4) {

            //Set level to encoder
            if (mRemoteDisplay != null) {
                if (DEBUG) {
                    Slog.d(TAG, "setWfdLevel():+1");
                }
                mRemoteDisplay.setWfdLevel(1);
                mLevel += 1;
            }

            mScoreLevel = 0;

        }
        else if (mScoreLevel <= -6) {

            //Set level to encoder
            if (mRemoteDisplay != null) {
                if (DEBUG) {
                    Slog.d(TAG, "setWfdLevel():-2");
                }
                mRemoteDisplay.setWfdLevel(-2);
                mLevel -= 2;
            }
            mScoreLevel = 0;
        }
        else if (mScoreLevel <= -4) {

            //Set level to encoder
            if (mRemoteDisplay != null) {
                if (DEBUG) {
                    Slog.d(TAG, "setWfdLevel():-1");
                }
                mRemoteDisplay.setWfdLevel(-1);
                mLevel -= 1;
            }
            mScoreLevel = 0;
        }

        // for log
        if (mLevel > 0)
            mLevel = 0;
        if (mLevel < -5)
            mLevel = -5;

        message += ">" + mLevel;

        // Handle level change
        handleLevelChange();

        if (null != mRemoteDisplay) {
            int expectedBitRate = mRemoteDisplay.getWfdParam(0);
            int currentBitRate = mRemoteDisplay.getWfdParam(1);
            int fluencyRate = mRemoteDisplay.getWfdParam(2);

            message += ",ER:" + expectedBitRate;
            message += ",CR:" + currentBitRate;

            if (fluencyRate != WFDCONTROLLER_INVALID_VALUE) {
                message += ",FR:" + fluencyRate;
            }

            if (WFDCONTROLLER_SQC_INFO_ON) {
                Toast.makeText(mContext, message, Toast.LENGTH_SHORT).show();
            }

            // print log
            //if (DEBUG) {
            Slog.d(TAG, message);
            //}

        } else {
            Slog.e(TAG, "mRemoteDisplay is null");

        }

    }

    private int getAverageScore() {

        mScore[mScoreIndex % WFDCONTROLLER_AVERATE_SCORE_COUNT] = mWifiScore;
        mScoreIndex ++;

        int count = 0;
        int sum = 0;
        for (int i = 0; i < WFDCONTROLLER_AVERATE_SCORE_COUNT; i++) {
            if (mScore[i] != WFDCONTROLLER_INVALID_VALUE) {
                sum += mScore[i];
                count ++;
            }
        }
        return sum / count;
    }
    private void updateScoreLevel(int score) {
        if (score >= WFDCONTROLLER_SCORE_THRESHOLD1) {
            if (mScoreLevel < 0) {
                mScoreLevel = 0;
            }
            mScoreLevel += 6;
        }
        else if (score >= WFDCONTROLLER_SCORE_THRESHOLD2) {
            if (mScoreLevel < 0) {
                mScoreLevel = 0;
            }
            mScoreLevel += 2;
        }
        else if (score >= WFDCONTROLLER_SCORE_THRESHOLD3) {
            if (mScoreLevel > 0) {
                mScoreLevel = 0;
            }
            mScoreLevel -= 2;
        }
        else if (score >= WFDCONTROLLER_SCORE_THRESHOLD4) {
            if (mScoreLevel > 0) {
                mScoreLevel = 0;
            }
            mScoreLevel -= 3;
        }
        else {
            if (mScoreLevel > 0) {
                mScoreLevel = 0;
            }
            mScoreLevel -= 6;
        }
    }

    private void resetSignalParam() {
        mLevel = 0;
        mScoreLevel = 0;

        mScoreIndex = 0;
        for (int i = 0; i < WFDCONTROLLER_AVERATE_SCORE_COUNT; i++) {
            mScore[i] = WFDCONTROLLER_INVALID_VALUE;
        }

        mNotiTimerStarted = false;
        mToastTimerStarted = false;

    }

    private void registerEMObserver(int widthPixels, int heightPixels) {

        // Init parameter
        WFDCONTROLLER_DISPLAY_TOAST_TIME = mContext.getResources().getInteger(com.mediatek.internal.R.integer.wfd_display_toast_time);
        WFDCONTROLLER_DISPLAY_NOTIFICATION_TIME = mContext.getResources().getInteger(com.mediatek.internal.R.integer.wfd_display_notification_time);
        WFDCONTROLLER_DISPLAY_RESOLUTION = mContext.getResources().getInteger(com.mediatek.internal.R.integer.wfd_display_default_resolution);
        WFDCONTROLLER_DISPLAY_POWER_SAVING_OPTION = mContext.getResources().getInteger(com.mediatek.internal.R.integer.wfd_display_power_saving_option);
        WFDCONTROLLER_DISPLAY_POWER_SAVING_DELAY = mContext.getResources().getInteger(com.mediatek.internal.R.integer.wfd_display_power_saving_delay);
        WFDCONTROLLER_DISPLAY_SECURE_OPTION = mContext.getResources().getInteger(com.mediatek.internal.R.integer.wfd_display_secure_option);

        Slog.d(TAG, "registerEMObserver(), tt:" + WFDCONTROLLER_DISPLAY_TOAST_TIME +
                                         ",nt:" + WFDCONTROLLER_DISPLAY_NOTIFICATION_TIME +
                                         ",res:" + WFDCONTROLLER_DISPLAY_RESOLUTION +
                                         ",ps:" + WFDCONTROLLER_DISPLAY_POWER_SAVING_OPTION +
                                         ",psd:" + WFDCONTROLLER_DISPLAY_POWER_SAVING_DELAY +
                                         ",so:" + WFDCONTROLLER_DISPLAY_SECURE_OPTION);

        // Init parameter
        Settings.Global.putInt(
            mContext.getContentResolver(), Settings.Global.WIFI_DISPLAY_DISPLAY_TOAST_TIME, WFDCONTROLLER_DISPLAY_TOAST_TIME);
        Settings.Global.putInt(
            mContext.getContentResolver(), Settings.Global.WIFI_DISPLAY_DISPLAY_NOTIFICATION_TIME, WFDCONTROLLER_DISPLAY_NOTIFICATION_TIME);
        Settings.Global.putInt(
            mContext.getContentResolver(), Settings.Global.WIFI_DISPLAY_SQC_INFO_ON, WFDCONTROLLER_SQC_INFO_ON ? 1 : 0);
        Settings.Global.putInt(
            mContext.getContentResolver(), Settings.Global.WIFI_DISPLAY_QE_ON, WFDCONTROLLER_QE_ON ? 1 : 0);

        if (SystemProperties.get("ro.mtk_wfd_support").equals("1")) {

            int r;
            r = Settings.Global.getInt(
                    mContext.getContentResolver(), Settings.Global.WIFI_DISPLAY_RESOLUTION, -1);

            // boot up for the first time
            if (r == -1) {
                if (WFDCONTROLLER_DISPLAY_RESOLUTION >= 0 &&
                    WFDCONTROLLER_DISPLAY_RESOLUTION <= 3) {
                    mPrevResolution = mResolution = WFDCONTROLLER_DISPLAY_RESOLUTION;
                } else {
                    // initialize resolution and frame rate
                    if (widthPixels >= 1080 && heightPixels >= 1920) {
                        mPrevResolution = mResolution = 2;  // 2: 1080p,30fps (Menu is enabled)
                    } else {
                        mPrevResolution = mResolution = 0;  // 0: 720p,30fps  (Menu is disabled)
                    }
                }
            }
            else {
                if (r >= 0 && r <= 3) {
                    // use the previous selection
                    mPrevResolution = mResolution = r;
                } else {
                    mPrevResolution = mResolution = 0; // 0: 720p,30fps  (Menu is disabled)
                }
            }

            int resolutionIndex = getResolutionIndex(mResolution);
            Slog.i(TAG, "mResolution:" + mResolution + ", resolutionIndex: " + resolutionIndex);

            SystemProperties.set("media.wfd.video-format", String.valueOf(resolutionIndex));
        }

        Settings.Global.putInt(
            mContext.getContentResolver(), Settings.Global.WIFI_DISPLAY_AUTO_CHANNEL_SELECTION, mAutoChannelSelection ? 1 : 0);
        Settings.Global.putInt(
            mContext.getContentResolver(), Settings.Global.WIFI_DISPLAY_RESOLUTION, mResolution);
        Settings.Global.putInt(
            mContext.getContentResolver(), Settings.Global.WIFI_DISPLAY_POWER_SAVING_OPTION, WFDCONTROLLER_DISPLAY_POWER_SAVING_OPTION);
        Settings.Global.putInt(
            mContext.getContentResolver(), Settings.Global.WIFI_DISPLAY_POWER_SAVING_DELAY, WFDCONTROLLER_DISPLAY_POWER_SAVING_DELAY);

        Settings.Global.putInt(
            mContext.getContentResolver(), Settings.Global.WIFI_DISPLAY_LATENCY_PROFILING, mLatencyProfiling);
        Settings.Global.putString(
            mContext.getContentResolver(), Settings.Global.WIFI_DISPLAY_CHOSEN_CAPABILITY, "");
        resetLatencyInfo();
        initSecureOption();

        // Register observer
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.WIFI_DISPLAY_DISPLAY_TOAST_TIME), false, mObserver);
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.WIFI_DISPLAY_DISPLAY_NOTIFICATION_TIME), false, mObserver);
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.WIFI_DISPLAY_SQC_INFO_ON), false, mObserver);
        mContext.getContentResolver().registerContentObserver(
                        Settings.Global.getUriFor(Settings.Global.WIFI_DISPLAY_QE_ON), false, mObserver);

        if (SystemProperties.get("ro.mtk_wfd_support").equals("1")) {
            mContext.getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.WIFI_DISPLAY_AUTO_CHANNEL_SELECTION), false, mObserver);
            mContext.getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.WIFI_DISPLAY_RESOLUTION), false, mObserver);
            mContext.getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.WIFI_DISPLAY_LATENCY_PROFILING), false, mObserver);
            mContext.getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.WIFI_DISPLAY_SECURITY_OPTION), false, mObserver);

        }
    }

    private final ContentObserver mObserver = new ContentObserver(new Handler()) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {

                if (selfChange) {
                    return;
                }

                WFDCONTROLLER_DISPLAY_TOAST_TIME = Settings.Global.getInt(
                    mContext.getContentResolver(), Settings.Global.WIFI_DISPLAY_DISPLAY_TOAST_TIME, 20);

                WFDCONTROLLER_DISPLAY_NOTIFICATION_TIME = Settings.Global.getInt(
                    mContext.getContentResolver(), Settings.Global.WIFI_DISPLAY_DISPLAY_NOTIFICATION_TIME, 120);

                WFDCONTROLLER_SQC_INFO_ON = Settings.Global.getInt(
                    mContext.getContentResolver(), Settings.Global.WIFI_DISPLAY_SQC_INFO_ON, 0) != 0;

                WFDCONTROLLER_QE_ON = Settings.Global.getInt(
                    mContext.getContentResolver(), Settings.Global.WIFI_DISPLAY_QE_ON, 0) != 0;

                mAutoChannelSelection = Settings.Global.getInt(
                    mContext.getContentResolver(), Settings.Global.WIFI_DISPLAY_AUTO_CHANNEL_SELECTION, 0) != 0;


                Slog.d(TAG, "onChange(), t_time:" + WFDCONTROLLER_DISPLAY_TOAST_TIME +
                                         ",n_time:" + WFDCONTROLLER_DISPLAY_NOTIFICATION_TIME +
                                         ",sqc:" + WFDCONTROLLER_SQC_INFO_ON +
                                         ",qe:" + WFDCONTROLLER_QE_ON +
                                         ",autoChannel:" + mAutoChannelSelection);

                if (SystemProperties.get("ro.mtk_wfd_support").equals("1")) {

                    handleResolutionChange();
                    handleLatencyProfilingChange();
                    handleSecureOptionChange();
                }

            }
    };

    private void initSecureOption() {
        Settings.Global.putInt(
            mContext.getContentResolver(),
            Settings.Global.WIFI_DISPLAY_SECURITY_OPTION,
            WFDCONTROLLER_DISPLAY_SECURE_OPTION);

        //set system property
        SystemProperties.set("wlan.wfd.security.image", String.valueOf(WFDCONTROLLER_DISPLAY_SECURE_OPTION));
    }

    private void handleSecureOptionChange() {

        int secureOption = Settings.Global.getInt(
                mContext.getContentResolver(), Settings.Global.WIFI_DISPLAY_SECURITY_OPTION, 1);

        if (secureOption == WFDCONTROLLER_DISPLAY_SECURE_OPTION) {
            return;
        }

        Slog.i(TAG, "handleSecureOptionChange:" + secureOption + "->" + WFDCONTROLLER_DISPLAY_SECURE_OPTION);
        WFDCONTROLLER_DISPLAY_SECURE_OPTION = secureOption;

        //set system property
        SystemProperties.set("ro.sf.security.image", String.valueOf(WFDCONTROLLER_DISPLAY_SECURE_OPTION));
    }

    private int getResolutionIndex(int settingValue) {
        switch (settingValue) {
            case 0:
            case 3:
                return 5; // 720p/30fps
            case 1:
            case 2:
                return 7;  // 1080p/30fps
            default:
                return 5;  // 720p/30fps
        }
    }

    private void handleResolutionChange() {
        int r;
        boolean doNotRemind;
        r = Settings.Global.getInt(
                    mContext.getContentResolver(), Settings.Global.WIFI_DISPLAY_RESOLUTION, 0);

        if (r == mResolution) {
            return;
        }
        else {
            mPrevResolution = mResolution;
            mResolution = r;

            Slog.d(TAG, "handleResolutionChange(), resolution:" + mPrevResolution + "->" + mResolution);
        }

        int idxModified = getResolutionIndex(mResolution);
        int idxOriginal = getResolutionIndex(mPrevResolution);

        if (idxModified == idxOriginal) {
            return;
        }

        doNotRemind = Settings.Global.getInt(
                            mContext.getContentResolver(), Settings.Global.WIFI_DISPLAY_RESOLUTION_DONOT_REMIND, 0) != 0;


        Slog.d(TAG, "index:" + idxOriginal + "->" + idxModified + ", doNotRemind:" + doNotRemind);

        SystemProperties.set("media.wfd.video-format", String.valueOf(idxModified));


        // check if need to reconnect
        if (mConnectedDevice != null || mConnectingDevice != null) {
            if (doNotRemind) {
                Slog.d(TAG, "-- reconnect for resolution change --");

                // reconnect again
                disconnect();
                mReconnectForResolutionChange = true;

            } else {
                showDialog(WFD_CHANGE_RESOLUTION_DIALOG);
            }
        }

    }

    private void revertResolutionChange() {

        Slog.d(TAG, "revertResolutionChange(), resolution:" + mResolution + "->" + mPrevResolution);

        int idxModified = getResolutionIndex(mResolution);
        int idxOriginal = getResolutionIndex(mPrevResolution);


        Slog.d(TAG, "index:" + idxModified + "->" + idxOriginal);

        SystemProperties.set("media.wfd.video-format", String.valueOf(idxOriginal));

        mResolution = mPrevResolution;

        Settings.Global.putInt(
            mContext.getContentResolver(), Settings.Global.WIFI_DISPLAY_RESOLUTION, mResolution);
    }

    private void handleLatencyProfilingChange() {

        int value = Settings.Global.getInt(
            mContext.getContentResolver(), Settings.Global.WIFI_DISPLAY_LATENCY_PROFILING, 2);

        if (value == mLatencyProfiling) {
            return;
        }

        Slog.d(TAG, "handleLatencyProfilingChange(), connected:" + mIsWFDConnected + ",value:" + mLatencyProfiling + "->" + value);
        mLatencyProfiling = value;

        if (mLatencyProfiling != 3) {
            mHandler.removeCallbacks(mDelayProfiling);
        }

        // In EM or show latency panel
        if ((mLatencyProfiling == 0 ||
             mLatencyProfiling == 1 ||
             mLatencyProfiling == 3) && mIsWFDConnected) {

            startProfilingInfo();
        } else {
            stopProfilingInfo();
        }

    }

    private void showLatencyPanel() {
        Slog.i(TAG, Thread.currentThread().getStackTrace()[2].getMethodName());

        LayoutInflater adbInflater = LayoutInflater.from(mContext);
        mLatencyPanelView = adbInflater.inflate(com.mediatek.internal.R.layout.textpanel, null);

        // text view
        mTextView = (TextView) mLatencyPanelView.findViewById(com.mediatek.internal.R.id.bodyText);
        mTextView.setTextColor(0xffffffff);
        mTextView.setText(
            "AP:\n" +
            "S:\n" +
            "R:\n" +
            "AL:\n");

        // layout param
        WindowManager.LayoutParams layoutParams;
        layoutParams = new WindowManager.LayoutParams();
        layoutParams.type = WindowManager.LayoutParams.TYPE_TOP_MOST; //ok

        layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;

        layoutParams.width = WindowManager.LayoutParams.WRAP_CONTENT;
        layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
        layoutParams.gravity = Gravity.LEFT | Gravity.TOP;
        layoutParams.alpha = 0.7f;

        // add view to window manager
        WindowManager windowManager = (WindowManager)
            mContext.getSystemService(Context.WINDOW_SERVICE);

        windowManager.addView(mLatencyPanelView, layoutParams);
    }

    private void hideLatencyPanel() {
        Slog.i(TAG, Thread.currentThread().getStackTrace()[2].getMethodName());

        if (mLatencyPanelView != null) {
            // remove view to window manager
            WindowManager windowManager = (WindowManager)
                mContext.getSystemService(Context.WINDOW_SERVICE);

            windowManager.removeView(mLatencyPanelView);
            mLatencyPanelView = null;
        }

        mTextView = null;
    }

    private void checkA2dpStatus() {

        // Get the default adapter
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();

        if (!adapter.isEnabled()) {
            Slog.d(TAG, "checkA2dpStatus(), BT is not enabled");
            return;
        }

        int value;

        value = Settings.Global.getInt(
                        mContext.getContentResolver(), Settings.Global.WIFI_DISPLAY_SOUND_PATH_DONOT_REMIND, -1);


        Slog.d(TAG, "checkA2dpStatus(), value:" + value);

        // don't need to remind
        if (value == 1) {
            return;
        }

        BluetoothProfile.ServiceListener profileListener = new BluetoothProfile.ServiceListener() {

            public void onServiceConnected(int profile, BluetoothProfile proxy) {

                BluetoothA2dp a2dp = (BluetoothA2dp) proxy;
                List<BluetoothDevice> deviceList = a2dp.getConnectedDevices();

                boolean empty = deviceList.isEmpty();
                Slog.d(TAG, "BluetoothProfile listener is connected, empty:" + empty);

                // A2DP is connected
                if (!empty) {
                    showDialog(WFD_SOUND_PATH_DIALOG);
                }
            }

            public void onServiceDisconnected(int profile) {
                // Do nothing, but need declare, otherwise there is build error.
            }
        };



        // Establish connection to the proxy.
        adapter.getProfileProxy(mContext, profileListener, BluetoothProfile.A2DP);
    }

    private void setAutoChannelSelection() {

        Slog.d(TAG, "setAutoChannelSelection(), auto:" + mAutoChannelSelection);

        if (mAutoChannelSelection) {
            mWifiP2pManager.setP2pAutoChannel(mWifiP2pChannel, true, null);
        } else {
            mWifiP2pManager.setP2pAutoChannel(mWifiP2pChannel, false, null);
        }
    }

    private void updateChosenCapability(int usingUIBC) {

        String capability = "";

        if (mIsWFDConnected) {
            // Audio capability
            int usingPCMAudio = mRemoteDisplay.getWfdParam(3);
            if (usingPCMAudio == 1) {
                capability += "LPCM(2 ch),";
            }
            else {
                capability += "AAC(2 ch),";
            }

            // Video capability
            int isCBPOnly = mRemoteDisplay.getWfdParam(4);
            if (isCBPOnly == 1) {
                capability += "H.264(CBP level 3.1),";
            }
            else {
                capability += "H.264(CHP level 4.1),";
            }

            // Resolution and frame rate
            int resolutionIndex = getResolutionIndex(mResolution);
            if (resolutionIndex == 5) { // 720p/30fps
                capability += "1280x720 30p,";
            } else if (resolutionIndex == 6) {  // 720p/60fps
                capability += "1280x720 60p,";
            } else if (resolutionIndex == 7) { // 1080p/30fps
                capability += "1920x1080 30p,";
            } else {
                capability += "640x480 60p,";
            }

            // HDCP
            int usingHDCP = mRemoteDisplay.getWfdParam(7);
            if (usingHDCP == 1) {
                capability += "with HDCP,";
            }
            else {
                capability += "without HDCP,";
            }

            if (usingUIBC != 0) {
                capability += "with UIBC";
            }
            else {
                capability += "without UIBC";
            }

        }

        Slog.d(TAG, "updateChosenCapability(), connected:" + mIsWFDConnected + ", capability:" + capability);


        Settings.Global.putString(
            mContext.getContentResolver(),
            Settings.Global.WIFI_DISPLAY_CHOSEN_CAPABILITY, capability);

    }

    private void startProfilingInfo() {

        if (mLatencyProfiling == 3) {
            showLatencyPanel();
        } else {
            hideLatencyPanel();
        }
        // Remove callback first
        mHandler.removeCallbacks(mLatencyInfo);
        mHandler.removeCallbacks(mScanWifiAp);

        mHandler.postDelayed(mLatencyInfo, WFDCONTROLLER_LATENCY_INFO_FIRST_MILLIS);
        mHandler.postDelayed(mScanWifiAp, WFDCONTROLLER_WIFI_APP_SCAN_PERIOD_MILLIS);
    }

    private void stopProfilingInfo() {

        // Hide latency panel
        hideLatencyPanel();

        // Stop profiling
        mHandler.removeCallbacks(mLatencyInfo);
        mHandler.removeCallbacks(mScanWifiAp);
        mHandler.removeCallbacks(mDelayProfiling);

        // Reset string
        resetLatencyInfo();

    }

    private void resetLatencyInfo() {

        Settings.Global.putString(
            mContext.getContentResolver(), Settings.Global.WIFI_DISPLAY_WIFI_INFO, "0,0,0,0");
        Settings.Global.putString(
            mContext.getContentResolver(), Settings.Global.WIFI_DISPLAY_WFD_LATENCY, "0,0,0");
    }

    private int getWifiApNum() {

        int count = 0;
        final List<ScanResult> results = mWifiManager.getScanResults();
        ArrayList<String> SSIDList = new ArrayList<String>();

        if (results != null) {
            for (ScanResult result : results) {
                // Ignore hidden and ad-hoc networks.
                if (result.SSID == null || result.SSID.length() == 0 ||
                    result.capabilities.contains("[IBSS]")) {
                    continue;
                }

                if (getFreqId(result.frequency) == mWifiP2pChannelId) {

                    boolean duplicate = false;

                    for (String ssid : SSIDList) {
                        if (ssid.equals(result.SSID)) {
                            duplicate = true;
                            break;
                        }
                    }

                    if (!duplicate) {
                        if (DEBUG) {
                            Slog.d(TAG, "AP SSID: " + result.SSID);
                        }
                        SSIDList.add(result.SSID);
                        count ++;
                    }
                }
            }
        }

        return count;
    }

    private void updateWifiP2pChannelId(boolean connected, Intent intent) {

        if (mWfdEnabled && connected && (mDesiredDevice != null || mSinkEnabled)) {
            WifiP2pGroup wifiP2pGroup = (WifiP2pGroup) intent.getParcelableExtra(
                                WifiP2pManager.EXTRA_WIFI_P2P_GROUP);     

            int freq = wifiP2pGroup.getFrequency();
        
            mWifiP2pChannelId = getFreqId(freq);

            Slog.d(TAG, "updateWifiP2pChannelId(), freq:" + freq +", id:" + mWifiP2pChannelId);
        
        }
        else {
            mWifiP2pChannelId = -1;

            Slog.d(TAG, "updateWifiP2pChannelId(), id:" + mWifiP2pChannelId);
        
        }

        
    }

    private int getFreqId(int frequency) {
        switch (frequency) {
            //2.4G
            case 2412:
                return 1;
            case 2417:
                return 2;
            case 2422:
                return 3;
            case 2427:
                return 4;
            case 2432:
                return 5;
            case 2437:
                return 6;
            case 2442:
                return 7;
            case 2447:
                return 8;
            case 2452:
                return 9;
            case 2457:
                return 10;
            case 2462:
                return 11;
            case 2467:
                return 12;
            case 2472:
                return 13;
            case 2484:
                return 14;
            //5G
            case 5180:
                return 36;
            case 5190:
                return 38;
            case 5200:
                return 40;
            case 5210:
                return 42;
            case 5220:
                return 44;
            case 5230:
                return 46;
            case 5240:
                return 48;
            case 5260:
                return 52;
            case 5280:
                return 56;
            case 5300:
                return 60;
            case 5320:
                return 64;
            case 5500:
                return 100;
            case 5520:
                return 104;
            case 5540:
                return 108;
            case 5560:
                return 112;
            case 5580:
                return 116;
            case 5600:
                return 120;
            case 5620:
                return 124;
            case 5640:
                return 128;
            case 5660:
                return 132;
            case 5680:
                return 136;
            case 5700:
                return 140;
            case 5745:
                return 149;
            case 5765:
                return 153;
            case 5785:
                return 157;
            case 5805:
                return 161;
            case 5825:
                return 165;
            default:
                return 0;
        }
    }

    private final Runnable mLatencyInfo = new Runnable() {
        @Override
        public void run() {
            if (null == mConnectedDevice) {
                Slog.e(TAG, Thread.currentThread().getStackTrace()[2].getMethodName() + ": ConnectedDevice is null");
                return;
            } else if (null == mRemoteDisplay) {
                Slog.e(TAG, Thread.currentThread().getStackTrace()[2].getMethodName() + ": RemoteDisplay is null");
                return;
            } else if (!(mLatencyProfiling == 0 || mLatencyProfiling == 1 || mLatencyProfiling == 3)) {
                Slog.e(TAG, Thread.currentThread().getStackTrace()[2].getMethodName() + ": mLatencyProfiling:" + mLatencyProfiling);
                return;
            }

            // Wifi Info string: "%d,%d,%d,%d, %d" means (channdlID, AP num, Score, Data rate, RSSI)
            int wifiApNum = getWifiApNum();

            String WifiInfo = mWifiP2pChannelId + "," + wifiApNum + "," +
                              mWifiScore + "," + mWifiRate + "," + mRSSI;
            Slog.d(TAG, "WifiInfo:" + WifiInfo);

            // Wifi latency string: "%d,%d,%d" means (avg latency, sink fps, timestamp)
            int avgLatency = mRemoteDisplay.getWfdParam(5);
            int sinkFps = mRemoteDisplay.getWfdParam(6);

            String WFDLatency = avgLatency + ",0,0";
            Slog.d(TAG, "WFDLatency:" + WFDLatency);

            if (mLatencyProfiling == 0 || mLatencyProfiling == 1) {

                Settings.Global.putString(
                                mContext.getContentResolver(), Settings.Global.WIFI_DISPLAY_WIFI_INFO, WifiInfo);

                Settings.Global.putString(
                    mContext.getContentResolver(), Settings.Global.WIFI_DISPLAY_WFD_LATENCY, WFDLatency);
            }
            else if (mLatencyProfiling == 3) {

                mTextView.setText(
                    "AP:" + wifiApNum + "\n" +
                    "S:" + mWifiScore + "\n" +
                    "R:" + mWifiRate + "\n" +
                    "RS:" + mRSSI + "\n" +
                    "AL:" + avgLatency + "\n" +
                    "SF:" + sinkFps + "\n");
            }

            mHandler.postDelayed(mLatencyInfo, WFDCONTROLLER_LATENCY_INFO_PERIOD_MILLIS);

        }
    };

    private final Runnable mScanWifiAp = new Runnable() {
        @Override
        public void run() {
            if (null == mConnectedDevice) {
                Slog.e(TAG, Thread.currentThread().getStackTrace()[2].getMethodName() + ": ConnectedDevice is null");
                return;
            } else if (null == mRemoteDisplay) {
                Slog.e(TAG, Thread.currentThread().getStackTrace()[2].getMethodName() + ": RemoteDisplay is null");
                return;
            } else if (!(mLatencyProfiling == 0 || mLatencyProfiling == 1 || mLatencyProfiling == 3)) {
                Slog.e(TAG, Thread.currentThread().getStackTrace()[2].getMethodName() + ": mLatencyProfiling:" + mLatencyProfiling);
                return;
            }

            Slog.d(TAG, "call mWifiManager.startScan()");
            mWifiManager.startScan();
       }
    };

    private final Runnable mDelayProfiling = new Runnable() {
        @Override
        public void run() {
            if (mLatencyProfiling == 3 && mIsWFDConnected) {
                startProfilingInfo();
            }
        }
    };

    private void handleLevelChange() {

        if (mLevel < 0) {

            // toast
            if (!mToastTimerStarted) {

                mHandler.postDelayed(
                    mDisplayToast,
                    WFDCONTROLLER_DISPLAY_TOAST_TIME * 1000);
                mToastTimerStarted = true;
            }

            // notification
            if (!mNotiTimerStarted) {

                mHandler.postDelayed(
                    mDisplayNotification,
                    WFDCONTROLLER_DISPLAY_NOTIFICATION_TIME * 1000);
                mNotiTimerStarted = true;
            }

        }
        else {

            clearNotify();
        }

    }

    private void clearNotify() {
        // toast
        if (mToastTimerStarted) {
            mHandler.removeCallbacks(mDisplayToast);
            mToastTimerStarted = false;
        }

        // notification
        if (mNotiTimerStarted) {

            mHandler.removeCallbacks(mDisplayNotification);
            mNotiTimerStarted = false;
        }

        // Cancel the old notification if there is one.
        mNotificationManager.cancelAsUser(null,
            com.mediatek.internal.R.string.wifi_display_unstable_connection, UserHandle.ALL);
    }

    private final Runnable mDisplayToast = new Runnable() {
        @Override
        public void run() {
            Slog.d(TAG, "mDisplayToast run()" + mLevel);
            Resources mResource = Resources.getSystem();

            if (mLevel != 0) {
                Toast.makeText(
                    mContext,
                    mResource.getString(com.mediatek.internal.R.string.wifi_display_connection_is_not_steady),
                    Toast.LENGTH_SHORT).show();
            }

            mToastTimerStarted = false;
        }
    };

    private final Runnable mDisplayNotification = new Runnable() {
        @Override
        public void run() {
            Slog.d(TAG, "mDisplayNotification run()" + mLevel);
            if (mLevel != 0) {
                showNotification(
                    com.mediatek.internal.R.string.wifi_display_unstable_connection,
                    com.mediatek.internal.R.string.wifi_display_unstable_suggestion);
            }

            mNotiTimerStarted = false;
        }
    };

    private void showNotification(int titleId, int contentId) {

        Slog.d(TAG, "showNotification(), titleId:" + titleId);

        // Cancel the old notification if there is one.
        mNotificationManager.cancelAsUser(
                null,     //TAG
                titleId,  //ID
                UserHandle.ALL);

        // Post the notification.
        Resources mResource = Resources.getSystem();

        Builder builder = new Notification.Builder(mContext)
                .setContentTitle(mResource.getString(titleId))
                .setContentText(mResource.getString(contentId))
                .setSmallIcon(com.mediatek.internal.R.drawable.ic_notify_wifidisplay_blink)
                .setAutoCancel(true);

        Notification notification = new Notification.BigTextStyle(builder)
                .bigText(mResource.getString(contentId))
                .build();
        mNotificationManager.notifyAsUser(
                    null,     //Tag
                    titleId,  //ID
                    notification, UserHandle.ALL);

    }

    private void dialogReconnect() {
        showDialog(WFD_RECONNECT_DIALOG);

    }

    private final Runnable mReConnect = new Runnable() {
        @Override
        public void run() {
            Slog.d(TAG, "mReConnect, run()");

            if (null == mReConnectDevice) {
                Slog.w(TAG, "no reconnect device");
                return;
            }

            boolean empty = true;
            for (WifiP2pDevice device : mAvailableWifiDisplayPeers) {
                if (DEBUG) {
                    Slog.d(TAG, "\t" + describeWifiP2pDevice(device));
                }
                empty = false;

                if (device.deviceAddress.equals(mReConnectDevice.deviceAddress)) {
                    Slog.i(TAG, "connect() in mReConnect. Set mReConnecting as true");
                    mReConnecting = true;
                    connect(device);
                    return;
                }
            }

            mReConnection_Timeout_Remain_Seconds = mReConnection_Timeout_Remain_Seconds -
                (RECONNECT_RETRY_DELAY_MILLIS / 1000);
            if (mReConnection_Timeout_Remain_Seconds > 0) {
                // check scan result per RECONNECT_RETRY_DELAY_MILLIS ms
                mHandler.postDelayed(mReConnect, RECONNECT_RETRY_DELAY_MILLIS);

                if (empty && mReConnection_Timeout_Remain_Seconds % 2 == 0) {
                    Slog.i(TAG, "requestStartScan() in mReConnect, mReConnection_Timeout_Remain_Seconds:" + mReConnection_Timeout_Remain_Seconds);
                    requestStartScan();
                }
            } else {
                Slog.e(TAG, "reconnect timeout!");
                Toast.makeText(mContext, com.mediatek.internal.R.string.wifi_display_disconnected
                    , Toast.LENGTH_SHORT).show();
                resetReconnectVariable();
                return;
            }
        }
    };

    private void resetReconnectVariable() {
        Slog.i(TAG, Thread.currentThread().getStackTrace()[2].getMethodName());

        mReConnectDevice = null;
        mReConnection_Timeout_Remain_Seconds = 0;
        mReConnecting = false;
        mHandler.removeCallbacks(mReConnect);
    }

    private void chooseNo_WifiDirectExcludeDialog() {
        if (SystemProperties.get("ro.mtk_wfd_sink_support").equals("1")  && mSinkEnabled) {
            Slog.d(TAG, "[sink] callback onDisplayConnectionFailed()");
            mListener.onDisplayConnectionFailed();
        } else {
            /*M: ALPS00758891: notify apk wfd connection isn't connected*/
            actionAtDisconnected(null);
        }

    }

    private void chooseNo_HDMIExcludeDialog_WfdUpdate() {
        Settings.Global.putInt(mContext.getContentResolver(), Settings.Global.WIFI_DISPLAY_ON, 0);
        updateWfdEnableState();

    }

    private void turnOffHdmi() {
        if (null != mHdmiManager) {
            try {
                mHdmiManager.enableHdmi(false);
            } catch (RemoteException e) {
                Slog.d(TAG, "hdmi manager RemoteException: " + e.getMessage());
            }
        }
    }

    private void prepareDialog(int dialogID) {
        Resources mResource = Resources.getSystem();

        if (WFD_WIFIP2P_EXCLUDED_DIALOG == dialogID) {
            // wifi direct excluded dialog
            mWifiDirectExcludeDialog = new AlertDialog.Builder(mContext)
                .setMessage(mResource.getString(com.mediatek.internal.R.string.wifi_display_wifi_p2p_disconnect_wfd_connect))
                .setPositiveButton(mResource.getString(R.string.dlg_ok), new OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {

                            Slog.d(TAG, "[Exclude Dialog] disconnect previous Wi-Fi P2p connection");

                            //Reset parameter
                            mIsConnected_OtherP2p = false;

                            mWifiP2pManager.removeGroup(mWifiP2pChannel, new ActionListener() {
                                @Override
                                public void onSuccess() {
                                    Slog.i(TAG, "Disconnected from previous Wi-Fi P2p device, succeess");
                                }

                                @Override
                                public void onFailure(int reason) {
                                    Slog.i(TAG, "Disconnected from previous Wi-Fi P2p device, failure = " + reason);
                                }
                            });

                            chooseNo_WifiDirectExcludeDialog();

                            mUserDecided = true;
                        }
                    })
                .setNegativeButton(mResource.getString(R.string.decline), new OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {

                            Slog.d(TAG, "[Exclude Dialog] keep previous Wi-Fi P2p connection");

                            chooseNo_WifiDirectExcludeDialog();
                            mUserDecided = true;
                        }
                    })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        @Override
                        public void onCancel(DialogInterface arg0) {

                            Slog.d(TAG, "[Exclude Dialog] onCancel(): keep previous Wi-Fi P2p connection");

                            chooseNo_WifiDirectExcludeDialog();
                            mUserDecided = true;
                        }
                    })
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                        @Override
                        public void onDismiss(DialogInterface arg0) {

                            Slog.d(TAG, "[Exclude Dialog] onDismiss()");

                            if (false == mUserDecided) {
                                chooseNo_WifiDirectExcludeDialog();
                            }
                        }
                    })
                .create();
            popupDialog(mWifiDirectExcludeDialog);

        } else if (WFD_HDMI_EXCLUDED_DIALOG_WFD_UPDATE == dialogID) {

            String messageString = reviseHDMIString(mResource.getString(com.mediatek.internal.R.string.wifi_display_bt_hdmi_off_wfd_on));

            // HDMI excluded dialog, WFD update
            mHDMIExcludeDialog_WfdUpdate = new AlertDialog.Builder(mContext)
                .setMessage(messageString)
                .setPositiveButton(mResource.getString(R.string.dlg_ok), new OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (DEBUG) {
                                Slog.d(TAG, "WifiDisplay on, user turn off HDMI");
                            }

                            turnOffHdmi();

                            enableWifiDisplay();

                            mUserDecided = true;
                        }
                    })
                .setNegativeButton(mResource.getString(R.string.decline), new OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (DEBUG) {
                                Slog.d(TAG, "WifiDisplay on, user DON'T turn off HDMI -> turn off WifiDisplay");
                            }
                            chooseNo_HDMIExcludeDialog_WfdUpdate();
                            mUserDecided = true;
                        }
                    })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        @Override
                        public void onCancel(DialogInterface arg0) {
                            if (DEBUG) {
                                Slog.d(TAG, "onCancel(): WifiDisplay on, user DON'T turn off HDMI -> turn off WifiDisplay");
                            }
                            chooseNo_HDMIExcludeDialog_WfdUpdate();
                            mUserDecided = true;
                        }
                    })
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                        @Override
                        public void onDismiss(DialogInterface arg0) {
                            if (DEBUG) {
                                Slog.d(TAG, "onDismiss()");
                            }
                            if (false == mUserDecided) {
                                chooseNo_HDMIExcludeDialog_WfdUpdate();
                            }
                        }
                    })
                .create();
            popupDialog(mHDMIExcludeDialog_WfdUpdate);

        } else if (WFD_HDMI_EXCLUDED_DIALOG_HDMI_UPDATE == dialogID) {
            String messageString = reviseHDMIString(mResource.getString(com.mediatek.internal.R.string.wifi_display_wfd_off_hdmi_on));

            // HDMI excluded dialog, HDMI update
            mHDMIExcludeDialog_HDMIUpdate = new AlertDialog.Builder(mContext)
                .setMessage(messageString)
                .setPositiveButton(mResource.getString(R.string.dlg_ok), new OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (DEBUG) {
                                Slog.d(TAG, "HDMI on, turn off WifiDisplay");
                            }
                            Settings.Global.putInt(mContext.getContentResolver(), Settings.Global.WIFI_DISPLAY_ON, 0);
                            mUserDecided = true;
                        }
                    })
                .setNegativeButton(mResource.getString(R.string.decline), new OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (DEBUG) {
                                Slog.d(TAG, "HDMI on, user DON'T turn off WifiDisplay -> turn off HDMI");
                            }
                            turnOffHdmi();
                            mUserDecided = true;
                        }
                    })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        @Override
                        public void onCancel(DialogInterface arg0) {
                            if (DEBUG) {
                                Slog.d(TAG, "onCancel(): HDMI on, user DON'T turn off WifiDisplay -> turn off HDMI");
                            }
                            turnOffHdmi();
                            mUserDecided = true;
                        }
                    })
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                        @Override
                        public void onDismiss(DialogInterface arg0) {
                            if (DEBUG) {
                                Slog.d(TAG, "onDismiss()");
                            }
                            if (false == mUserDecided) {
                                turnOffHdmi();
                            }
                        }
                    })
                .create();
            popupDialog(mHDMIExcludeDialog_HDMIUpdate);

        } else if (WFD_RECONNECT_DIALOG == dialogID) {
            // re-connect dialog
            mReConnecteDialog = new AlertDialog.Builder(mContext)
                .setTitle(com.mediatek.internal.R.string.wifi_display_reconnect)
                .setMessage(com.mediatek.internal.R.string.wifi_display_disconnect_then_reconnect)
                .setPositiveButton(mResource.getString(R.string.dlg_ok), new OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (DEBUG) {
                                Slog.d(TAG, "user want to reconnect");
                            }

                            //scan first
                            requestStartScan();
                            // check scan result per RECONNECT_RETRY_DELAY_MILLIS ms
                            mReConnection_Timeout_Remain_Seconds = CONNECTION_TIMEOUT_SECONDS;
                            mHandler.postDelayed(mReConnect, RECONNECT_RETRY_DELAY_MILLIS);
                        }
                    })
                .setNegativeButton(mResource.getString(R.string.decline), new OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (DEBUG) {
                                Slog.d(TAG, "user want nothing");
                            }
                        }
                    })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        @Override
                        public void onCancel(DialogInterface arg0) {
                            if (DEBUG) {
                                Slog.d(TAG, "user want nothing");
                            }
                        }
                    })
                .create();
            popupDialog(mReConnecteDialog);

        } else if (WFD_CHANGE_RESOLUTION_DIALOG == dialogID) {

            // check box layout
            LayoutInflater adbInflater = LayoutInflater.from(mContext);
            View checkboxLayout = adbInflater.inflate(com.mediatek.internal.R.layout.checkbox, null);
            final CheckBox checkbox = (CheckBox) checkboxLayout.findViewById(com.mediatek.internal.R.id.skip);
            checkbox.setText(com.mediatek.internal.R.string.wifi_display_do_not_remind_again);

            // change resolution dialog
            mChangeResolutionDialog = new AlertDialog.Builder(mContext)
                .setView(checkboxLayout)
                .setMessage(com.mediatek.internal.R.string.wifi_display_change_resolution_reminder)
                .setPositiveButton(mResource.getString(R.string.dlg_ok), new OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {

                            boolean checked = checkbox.isChecked();
                            Slog.d(TAG, "[Change resolution]: ok. checked:" + checked);

                            // update settings
                            if (checked) {

                                Settings.Global.putInt(
                                    mContext.getContentResolver(),
                                    Settings.Global.WIFI_DISPLAY_RESOLUTION_DONOT_REMIND,
                                    1);

                            } else {
                                Settings.Global.putInt(
                                    mContext.getContentResolver(),
                                    Settings.Global.WIFI_DISPLAY_RESOLUTION_DONOT_REMIND,
                                    0);
                            }

                            // check again if need to reconnect
                            if (mConnectedDevice != null || mConnectingDevice != null) {

                                Slog.d(TAG, "-- reconnect for resolution change --");
                                /// reconnect again
                                disconnect();
                                mReconnectForResolutionChange = true;
                            }
                        }
                    })
                .setNegativeButton(mResource.getString(R.string.cancel), new OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {

                            Slog.d(TAG, "[Change resolution]: cancel");


                            //revert resolution
                            revertResolutionChange();
                        }
                    })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        @Override
                        public void onCancel(DialogInterface arg0) {

                            Slog.d(TAG, "[Change resolution]: doesn't choose");


                            //revert resolution
                            revertResolutionChange();
                        }
                    })
                .create();
            popupDialog(mChangeResolutionDialog);

        } else if (WFD_SOUND_PATH_DIALOG == dialogID) {

            // check box layout
            LayoutInflater adbInflater = LayoutInflater.from(mContext);
            View checkboxLayout = adbInflater.inflate(com.mediatek.internal.R.layout.checkbox, null);
            final CheckBox checkbox = (CheckBox) checkboxLayout.findViewById(com.mediatek.internal.R.id.skip);
            checkbox.setText(com.mediatek.internal.R.string.wifi_display_do_not_remind_again);

            // default is enable
            int value = Settings.Global.getInt(
                            mContext.getContentResolver(), Settings.Global.WIFI_DISPLAY_SOUND_PATH_DONOT_REMIND, -1);
            if (value == -1) {
                checkbox.setChecked(true);
            }

            // sound path dialog
            mSoundPathDialog = new AlertDialog.Builder(mContext)
                .setView(checkboxLayout)
                .setMessage(com.mediatek.internal.R.string.wifi_display_sound_path_reminder)
                .setPositiveButton(mResource.getString(R.string.dlg_ok), new OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {

                            boolean checked = checkbox.isChecked();
                            Slog.d(TAG, "[Sound path reminder]: ok. checked:" + checked);

                            // update settings
                            if (checked) {
                                Settings.Global.putInt(
                                    mContext.getContentResolver(),
                                    Settings.Global.WIFI_DISPLAY_SOUND_PATH_DONOT_REMIND,
                                    1);

                            } else {
                                Settings.Global.putInt(
                                    mContext.getContentResolver(),
                                    Settings.Global.WIFI_DISPLAY_SOUND_PATH_DONOT_REMIND,
                                    0);
                            }
                        }
                    })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        @Override
                        public void onCancel(DialogInterface arg0) {
                            Slog.d(TAG, "[Sound path reminder]: cancel");
                        }
                    })
                .create();
            popupDialog(mSoundPathDialog);

        } else if (WFD_WAIT_CONNECT_DIALOG == dialogID) {

            // progress layout
            LayoutInflater adbInflater = LayoutInflater.from(mContext);
            View progressLayout = adbInflater.inflate(com.mediatek.internal.R.layout.progress_dialog, null);
            final ProgressBar progressBar = (ProgressBar) progressLayout.findViewById(com.mediatek.internal.R.id.progress);
            progressBar.setIndeterminate(true);
            final TextView progressText = (TextView) progressLayout.findViewById(com.mediatek.internal.R.id.progress_text);
            progressText.setText(com.mediatek.internal.R.string.wifi_display_wait_connection);

            // wait connection dialog
            mWaitConnectDialog = new AlertDialog.Builder(mContext)
                .setView(progressLayout)
                .setNegativeButton(mResource.getString(R.string.cancel), new OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {

                            Slog.d(TAG, "[Wait connection]: cancel");

                            //disconnect wifi display
                            disconnectWfdSink();
                        }
                    })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        @Override
                        public void onCancel(DialogInterface arg0) {
                            Slog.d(TAG, "[Wait connection]: no choice");

                            //disconnect wifi display
                            disconnectWfdSink();
                        }
                    })
                .create();
            popupDialog(mWaitConnectDialog);

        } else if (WFD_CONFIRM_CONNECT_DIALOG == dialogID) {

            dismissDialogDetail(mWaitConnectDialog);

            String message = mSinkDeviceName +
                             " " +
                             mResource.getString(com.mediatek.internal.R.string.wifi_display_confirm_connection);

            // confirm connect dialog
            mConfirmConnectDialog = new AlertDialog.Builder(mContext)
                .setMessage(message)
                .setPositiveButton(mResource.getString(R.string.accept), new OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {

                            Slog.d(TAG, "[GC confirm connection]: accept");

                            String goIntent = SystemProperties.get(
                                "wfd.sink.go_intent",
                                String.valueOf(WifiP2pConfig.MAX_GROUP_OWNER_INTENT - 1));
                            int value = Integer.valueOf(goIntent);

                            Slog.i(TAG, "Sink go_intent:" + value);
                            mWifiP2pManager.setGCInviteResult(mWifiP2pChannel, true, value, null);

                            // Show progress dialog
                            showDialog(WFD_BUILD_CONNECT_DIALOG);
                        }
                    })
                .setNegativeButton(mResource.getString(R.string.decline), new OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {

                            Slog.d(TAG, "[GC confirm connection]: declines");
                            mWifiP2pManager.setGCInviteResult(mWifiP2pChannel, false, 0, null);

                            //disconnect wifi display
                            disconnectWfdSink();
                        }
                    })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        @Override
                        public void onCancel(DialogInterface arg0) {

                            Slog.d(TAG, "[Confirm connection]: cancel");

                            //disconnect wifi display
                            disconnectWfdSink();
                        }
                    })
                .create();
            popupDialog(mConfirmConnectDialog);
        } else if (WFD_BUILD_CONNECT_DIALOG == dialogID) {

            // progress layout
            LayoutInflater adbInflater = LayoutInflater.from(mContext);
            View progressLayout = adbInflater.inflate(com.mediatek.internal.R.layout.progress_dialog, null);
            final ProgressBar progressBar = (ProgressBar) progressLayout.findViewById(com.mediatek.internal.R.id.progress);
            progressBar.setIndeterminate(true);
            final TextView progressText = (TextView) progressLayout.findViewById(com.mediatek.internal.R.id.progress_text);
            progressText.setText(com.mediatek.internal.R.string.wifi_display_build_connection);

            // build connection dialog
            mBuildConnectDialog = new AlertDialog.Builder(mContext)
                .setView(progressLayout)
                .setNegativeButton(mResource.getString(R.string.cancel), new OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {

                            Slog.d(TAG, "[Build connection]: cancel");

                            //disconnect wifi display
                            disconnectWfdSink();
                        }
                    })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        @Override
                        public void onCancel(DialogInterface arg0) {
                            Slog.d(TAG, "[Build connection]: no choice");

                            //disconnect wifi display
                            disconnectWfdSink();
                        }
                    })
                .create();
            popupDialog(mBuildConnectDialog);

        }

    }

    private void popupDialog(AlertDialog dialog) {
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        dialog.getWindow().getAttributes().privateFlags |=
                WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
        dialog.show();

    }

    private void showDialog(int dialogID) {
        mUserDecided = false;
        prepareDialog(dialogID);

    }

    private void dismissDialog() {
        dismissDialogDetail(mWifiDirectExcludeDialog);
        dismissDialogDetail(mHDMIExcludeDialog_WfdUpdate);
        dismissDialogDetail(mHDMIExcludeDialog_HDMIUpdate);
        dismissDialogDetail(mReConnecteDialog);

    }

    private void dismissDialogDetail(AlertDialog dialog) {
        if (null != dialog && dialog.isShowing()) {
            dialog.dismiss();
        }

    }

    private void notifyClearMotion(boolean connected) {
        if (SystemProperties.get("ro.mtk_clearmotion_support").equals("1")) {

            // Set system property
            SystemProperties.set(
                "sys.display.clearMotion.dimmed",
                connected ? "1" : "0");

            // Send broadcast
            Intent intent = new Intent(WFD_CLEARMOTION_DIMMED);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);

            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        }
    }

    private void updateWifiPowerSavingMode(boolean enable) {
        if (mWifiPowerSaving != enable) {
            mWifiPowerSaving = enable;
            Slog.d(TAG, "setPowerSavingMode():" + mWifiPowerSaving);
            mWifiManager.setPowerSavingMode(enable);
        }
    }

    private void checkIfWifiApIs11G() {
        Slog.d(TAG, "checkIfWifiApIs11G()");

        String wifiStatus = mWifiManager.getWifiStatus();
        if (null == wifiStatus) {
            Slog.d(TAG, "getWifiStatus() return null.");
            return;
        }

        if (DEBUG) {
            Slog.d(TAG, "getWifiStatus() return: " + wifiStatus);
        }

        String[] tokens = wifiStatus.split("\n");

        for (String token : tokens) {
            if (token.startsWith("group_cipher=")) {
                String[] nameValue = token.split("=");
                String cipher = nameValueAssign(nameValue);
                if (cipher == null) {
                    Slog.e(TAG, "cipher is null.");
                    break;
                }

                Slog.d(TAG, "cipher is " + cipher);

                if (cipher.contains("TKIP") || cipher.contains("WEP")) {
                    // Display toast
                    Toast.makeText(
                        mContext,
                        com.mediatek.internal.R.string.wifi_display_wifi_ap_affects_quality,
                        Toast.LENGTH_SHORT).show();
                }
                break;
            }
        }

    }

    private String nameValueAssign(String[] nameValue) {
        if (null == nameValue || 2 != nameValue.length) {
            return null;
        } else {
            return nameValue[1];
        }
    }

    private String reviseHDMIString(String input) {
        /* 0: HDMI
         * 1: MHL (Connected to smartbook)
         * 2: MHL (Connected to TV or No connection)
         */
        try {
            if (mHdmiManager != null
                    && (mHdmiManager.getDisplayType() == HdmiDef.DISPLAY_TYPE_MHL || mHdmiManager
                            .getDisplayType() == HdmiDef.DISPLAY_TYPE_SMB)) {

                return input.replaceAll("HDMI", "MHL");
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "HdmiManager.getDisplayType() RemoteException");
        }

        return input;
    }

    ///M:@{ WFD Sink Support
    public boolean getIfSinkEnabled() {
        Slog.i(TAG, Thread.currentThread().getStackTrace()[2].getMethodName() + ",enable = " + mSinkEnabled);

        return mSinkEnabled;
    }

    public void requestEnableSink(boolean enable) {
        Slog.i(TAG, Thread.currentThread().getStackTrace()[2].getMethodName() + ",enable = " + enable + ",Connected = " + mIsWFDConnected + ", option = " + SystemProperties.get("ro.mtk_wfd_sink_support").equals("1")  + ", WfdEnabled = " + mWfdEnabled);

        if (SystemProperties.get("ro.mtk_wfd_sink_support").equals("1")) {
            if (mSinkEnabled == enable || mIsWFDConnected) {
                return;
            }

            // Dialog displays when requestWaitConnection() is invoked
            if (enable && mIsConnected_OtherP2p) {
                Slog.i(TAG, "OtherP2P is connected! Only set variable. Ignore !");
                mSinkEnabled = enable;
                enterSinkState(SinkState.SINK_STATE_IDLE);
                return;
            }

            // stop or resume wifi scan
            stopWifiScan(enable);

            // sink: stop source action: scan
            if (enable == true) {
                requestStopScan();
            }

            mSinkEnabled = enable;

            // update WFD device type to WIFI P2P FW
            updateWfdInfo(true);

            //source
            if (mSinkEnabled == false) {
                requestStartScan();
            }
            // sink
            else {
                enterSinkState(SinkState.SINK_STATE_IDLE);
            }
        }
    }

    public void requestWaitConnection(Surface surface) {
        Slog.i(TAG, Thread.currentThread().getStackTrace()[2].getMethodName() + ", mSinkState:" + mSinkState);

        if (!isSinkState(SinkState.SINK_STATE_IDLE)) {
            Slog.i(TAG, "State is wrong! Ignore the request !");
            return;
        }

        if (mIsConnected_OtherP2p) {
            Slog.i(TAG, "OtherP2P is connected! Show dialog!");

            notifyDisplayConnecting();
            showDialog(WFD_WIFIP2P_EXCLUDED_DIALOG);
            return;
        }

        mSinkSurface = surface;
        mIsWFDConnected = false;

        mWifiP2pManager.discoverPeers(mWifiP2pChannel, new ActionListener() {
            @Override
            public void onSuccess() {
                Slog.d(TAG, "[sink] succeed for infinite p2p listen<-->search state");

                // Show progress dialog
                showDialog(WFD_WAIT_CONNECT_DIALOG);
            }

            @Override
            public void onFailure(int reason) {
                Slog.e(TAG, "[sink] failed for infinite p2p listen<-->search state");

                enterSinkState(SinkState.SINK_STATE_IDLE);
                Slog.d(TAG, "[sink] callback onDisplayConnectionFailed()");
                mListener.onDisplayConnectionFailed();
            }
        });

        setSinkMiracastMode();
                
        enterSinkState(SinkState.SINK_STATE_WAITING_P2P_CONNECTION);

        mHandler.post(new Runnable() {
            @Override
            public void run() {

                notifyDisplayConnecting();
            }
        });
    }

    public void requestSuspendDisplay(boolean suspend, Surface surface) {
        Slog.i(TAG, Thread.currentThread().getStackTrace()[2].getMethodName() + ",suspend = " + suspend);
        mSinkSurface = surface;

        if (isSinkState(SinkState.SINK_STATE_RTSP_CONNECTED)) {
            if (mRemoteDisplay != null) {
                mRemoteDisplay.suspendDisplay(suspend, surface);
            }

            blockNotificationList(!suspend);
        } else {
            //
            Slog.i(TAG, "State is wrong !!!, SinkState:" + mSinkState);
        }

    }

    ///M:@{ WFD UIBC Support
    public void sendUibcInputEvent(String input) {
        if (SystemProperties.get("ro.mtk_wfd_sink_uibc_support").equals("1")) {
            if (mRemoteDisplay != null) {
                mRemoteDisplay.sendUibcEvent(input);
            }
        }
    }
    ///@}

    private synchronized void disconnectWfdSink() {
        Slog.i(TAG, Thread.currentThread().getStackTrace()[2].getMethodName() + ", SinkState = " + mSinkState);

        if (isSinkState(SinkState.SINK_STATE_WAITING_P2P_CONNECTION) ||
            isSinkState(SinkState.SINK_STATE_WIFI_P2P_CONNECTED)) {

            mHandler.removeCallbacks(mGetSinkIpAddr);

            stopPeerDiscovery();
            Slog.i(TAG, "Disconnected from WFD sink (P2P).");

            enterSinkState(SinkState.SINK_STATE_IDLE);

            Slog.d(TAG, "[Sink] callback onDisplayDisconnected()");
            mListener.onDisplayDisconnected();

            updateIfSinkConnected(false);


            mWifiP2pManager.setMiracastMode(WifiP2pManager.MIRACAST_DISABLED);

        } else if (isSinkState(SinkState.SINK_STATE_WAITING_RTSP) ||
                   isSinkState(SinkState.SINK_STATE_RTSP_CONNECTED)) {

            if (mRemoteDisplay != null) {

                Slog.i(TAG, "before dispose()");
                mRemoteDisplay.dispose();
                Slog.i(TAG, "after dispose()");
            }

            mHandler.removeCallbacks(mRtspSinkTimeout);

            enterSinkState(SinkState.SINK_STATE_WIFI_P2P_CONNECTED);
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    disconnectWfdSink();
                }
            });

        }

        //Reset parameter
        mRemoteDisplay = null;
        mSinkDeviceName = null;
        mSinkMacAddress = null;
        mSinkPort = 0;
        mSinkIpAddress = null;
        mSinkSurface = null;

        // Dismiss dialog
        dismissDialogDetail(mWaitConnectDialog);
        dismissDialogDetail(mConfirmConnectDialog);
        dismissDialogDetail(mBuildConnectDialog);
    }

    private void handleSinkP2PConnection(NetworkInfo networkInfo) {
        Slog.i(TAG, "handleSinkP2PConnection(), sinkState:" + mSinkState);

        if (null != mWifiP2pManager && networkInfo.isConnected()) {

            // Do state check first
            if (!isSinkState(SinkState.SINK_STATE_WAITING_P2P_CONNECTION)) {
                return;
            }

            mWifiP2pManager.requestGroupInfo(mWifiP2pChannel, new WifiP2pManager.GroupInfoListener() {

                public void onGroupInfoAvailable(WifiP2pGroup group) {
                    Slog.i(TAG, "onGroupInfoAvailable(), mSinkState:" + mSinkState);

                    //Get valid WFD client in the group
                    boolean found = false;

                    // Check if the group owner is me
                    if (group.getOwner().deviceAddress.equals(mThisDevice.deviceAddress)) {
                        Slog.i(TAG, "group owner is my self !");

                        for (WifiP2pDevice c : group.getClientList()) {

                            Slog.i(TAG, "Client device:" + c);

                            if (isWifiDisplaySource(c) && mSinkDeviceName.equals(c.deviceName)) {

                                mSinkMacAddress = c.deviceAddress;
                                mSinkPort = c.wfdInfo.getControlPort();
                                Slog.i(TAG, "Found ! Sink name:" + mSinkDeviceName + ",mac address:" + mSinkMacAddress + ",port:" + mSinkPort);

                                found = true;
                                break;
                            }
                        }

                    } else {
                        Slog.i(TAG, "group owner is not my self ! So I am GC.");
                        mSinkMacAddress = group.getOwner().deviceAddress;
                        mSinkPort = group.getOwner().wfdInfo.getControlPort();

                        Slog.i(TAG, "Sink name:" + mSinkDeviceName + ",mac address:" + mSinkMacAddress + ",port:" + mSinkPort);
                        found = true;

                    }

                    if (found) {
                        mSinkIpRetryCount = WFD_SINK_IP_RETRY_COUNT;
                        enterSinkState(SinkState.SINK_STATE_WIFI_P2P_CONNECTED);
                        mHandler.postDelayed(mGetSinkIpAddr, WFD_SINK_IP_RETRY_FIRST_DELAY);
                    }
                }
            });
        } else {

            // Do state check first
            if (isSinkState(SinkState.SINK_STATE_WAITING_P2P_CONNECTION) ||
                isSinkState(SinkState.SINK_STATE_WIFI_P2P_CONNECTED)) {

                // Something wrong on GO group. So remove group.
                disconnectWfdSink();
            }
        }
    }

    private boolean isWifiDisplaySource(WifiP2pDevice device) {
        boolean result = device.wfdInfo != null
                && device.wfdInfo.isWfdEnabled()
                && device.wfdInfo.isSessionAvailable()  ///May have IOT issue
                && isSourceDeviceType(device.wfdInfo.getDeviceType());  ///May have IOT issue

        if (!result) {
            Slog.e(TAG, "This is not WFD source device !!!!!!");
        }
        return result;
    }

    private void notifyDisplayConnecting() {
        //TODO: define multi-language string
        WifiDisplay display = new WifiDisplay(
            "Temp address", "WiFi Display Device", null,
        true, true, false);

        Slog.d(TAG, "[sink] callback onDisplayConnecting()");
        mListener.onDisplayConnecting(display);
    }

    private boolean isSourceDeviceType(int deviceType) {
        return deviceType == WifiP2pWfdInfo.WFD_SOURCE
                || deviceType == WifiP2pWfdInfo.SOURCE_OR_PRIMARY_SINK;
    }

    private final Runnable mGetSinkIpAddr = new Runnable() {
        @Override
        public void run() {

            Slog.d(TAG, "mGetSinkIpAddr run(), count:" + mSinkIpRetryCount);

            if (!isSinkState(SinkState.SINK_STATE_WIFI_P2P_CONNECTED)) {
                Slog.d(TAG, "mSinkState:(" + mSinkState + ") is wrong !");
                return;
            }

            mSinkIpAddress = mWifiP2pManager.getPeerIpAddress(mSinkMacAddress);

            if (mSinkIpAddress == null) {
                if (mSinkIpRetryCount > 0) {
                    mSinkIpRetryCount --;
                    mHandler.postDelayed(mGetSinkIpAddr, WFD_SINK_IP_RETRY_DELAY_MILLIS);
                } else {
                    // ToDo: Check why fail
                    Slog.d(TAG, "mGetSinkIpAddr FAIL !!!!!!");
                }
            } else {

                //Add port number
                mSinkIpAddress += ":" + mSinkPort;

                Slog.i(TAG, "sink Ip address = " + mSinkIpAddress);

                //connect RTSP
                connectRtsp();
            }
        }
    };

    private void connectRtsp() {
        Slog.d(TAG, "connectRtsp(), mSinkState:" + mSinkState);

        mRemoteDisplay = RemoteDisplay.connect(mSinkIpAddress, mSinkSurface, new RemoteDisplay.Listener() {
                @Override
                public void onDisplayConnected(Surface surface,
                        int width, int height, int flags, int session)  {

                    Slog.i(TAG, "Opened RTSP connection!");

                    dismissDialogDetail(mBuildConnectDialog);

                    enterSinkState(SinkState.SINK_STATE_RTSP_CONNECTED);
                    mHandler.removeCallbacks(mRtspSinkTimeout);

                    //TODO: define multi-language string
                    WifiDisplay display = new WifiDisplay(
                        mSinkMacAddress, mSinkDeviceName, null, true, true, false);

                    Slog.d(TAG, "[sink] callback onDisplayConnected(), addr:" + mSinkMacAddress +
                           ", name:" + mSinkDeviceName);

                    updateIfSinkConnected(true);
                    mListener.onDisplayConnected(display, null, 0, 0, 0);
                }

                @Override
                public void onDisplayDisconnected() {
                    Slog.i(TAG, "Closed RTSP connection! mSinkState:" + mSinkState);

                    if (!isSinkState(SinkState.SINK_STATE_WAITING_RTSP) &&
                        !isSinkState(SinkState.SINK_STATE_RTSP_CONNECTED)) {
                        return;
                    }

                    dismissDialogDetail(mBuildConnectDialog);
                    mHandler.removeCallbacks(mRtspSinkTimeout);

                    disconnectWfdSink();
                }

                @Override
                public void onDisplayError(int error) {
                    Slog.i(TAG, "Lost RTSP connection! mSinkState:" + mSinkState);

                    if (!isSinkState(SinkState.SINK_STATE_WAITING_RTSP) &&
                        !isSinkState(SinkState.SINK_STATE_RTSP_CONNECTED)) {
                        return;
                    }

                    dismissDialogDetail(mBuildConnectDialog);
                    mHandler.removeCallbacks(mRtspSinkTimeout);

                    disconnectWfdSink();
                }

                @Override
                public void onDisplayKeyEvent(int keyCode, int flags) {
                    Slog.d(TAG, "onDisplayKeyEvent:");
                }

                @Override
                public void onDisplayGenericMsgEvent(int event){

                }

            }, mHandler);

        enterSinkState(SinkState.SINK_STATE_WAITING_RTSP);

        // Use extended timeout value for certification, as some tests require user inputs
        int rtspTimeout = mWifiDisplayCertMode ?
                RTSP_TIMEOUT_SECONDS_CERT_MODE : RTSP_SINK_TIMEOUT_SECONDS;

        mHandler.postDelayed(mRtspSinkTimeout, rtspTimeout * 1000);

    }

    private final Runnable mRtspSinkTimeout = new Runnable() {
        @Override
        public void run() {
            Slog.d(TAG, "mRtspSinkTimeout, run()");
            disconnectWfdSink();
        }
    };

    private void blockNotificationList(boolean block) {
        Slog.i(TAG, "blockNotificationList(), block:" + block);
        if (block) {
            mStatusBarManager.disable(StatusBarManager.DISABLE_EXPAND);
        } else {
            mStatusBarManager.disable(0);
        }
    }

    private void enterSinkState(SinkState state) {
        Slog.i(TAG, "enterSinkState()," + mSinkState + "->" + state);
        mSinkState = state;
    }

    private boolean isSinkState(SinkState state) {
        return  (mSinkState == state) ? true : false;
    }

    private void updateIfSinkConnected(boolean connected) {
        if (mIsWFDConnected == connected) {
            return;
        }
        mIsWFDConnected = connected;

        // No sink notification list when connected
        blockNotificationList(connected);

        // Prevent other source from searching to this sink device
        Slog.i(TAG, "Set session available as " + !connected);
        mWfdInfo.setSessionAvailable(!connected);
        mWifiP2pManager.setWFDInfo(mWifiP2pChannel, mWfdInfo, null);


        // Make backlight to turn on always when connected
        if (mWakeLockSink != null) {
            if (connected) {
                mWakeLockSink.acquire();
            } else {
                mWakeLockSink.release();
            }
        }        

        // Pause other sound in sink phone when connected
        getAudioFocus(connected);
    }

    private void getAudioFocus(boolean grab) {
        if (grab) {
            int ret = mAudioManager.requestAudioFocus(
                        mAudioFocusListener,
                        AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN);
            if (ret == AudioManager.AUDIOFOCUS_REQUEST_FAILED) {
                Slog.e(TAG, "requestAudioFocus() FAIL !!!");
            }
        } else {
            mAudioManager.abandonAudioFocus(mAudioFocusListener);
        }
    }

    private void setSinkMiracastMode(){
        Slog.i(TAG, "setSinkMiracastMode(), freq:" + mWifiApFreq);
        if (mWifiApConnected) {
            mWifiP2pManager.setMiracastMode(WifiP2pManager.MIRACAST_SINK, mWifiApFreq);         
        } else {
            mWifiP2pManager.setMiracastMode(WifiP2pManager.MIRACAST_SINK);
        }
    }

    private void notifyApDisconnected(){
        Slog.e(TAG, "notifyApDisconnected()");

        // Display Toast
        Resources r = Resources.getSystem();
        Toast.makeText(
            mContext, 
            r.getString(com.mediatek.internal.R.string.wifi_display_wifi_network_disconnected, mWifiApSsid), 
            Toast.LENGTH_SHORT).show();

        // Display Notification
        showNotification(
            com.mediatek.internal.R.string.wifi_display_channel_confliction,
            com.mediatek.internal.R.string.wifi_display_wifi_network_cannot_coexist);
    }

    // Do nothing now
    private OnAudioFocusChangeListener mAudioFocusListener = new OnAudioFocusChangeListener() {
        public void onAudioFocusChange(int focusChange) {
            Slog.e(TAG, "onAudioFocusChange(), focus:" + focusChange);
            switch (focusChange) {
                case AudioManager.AUDIOFOCUS_LOSS:
                    break;
                case AudioManager.AUDIOFOCUS_GAIN:
                    break;
                default:
                    break;
            }
        }
    };

    private final Runnable mEnableWifiDelay = new Runnable() {
        @Override
        public void run() {

            Slog.d(TAG, "Enable wifi automatically.");
            mAutoEnableWifi = true;

            int wifiApState = mWifiManager.getWifiApState();
            if ((wifiApState == WifiManager.WIFI_AP_STATE_ENABLING) ||
                (wifiApState == WifiManager.WIFI_AP_STATE_ENABLED)) {
                mWifiManager.setWifiApEnabled(null, false);
            }
            mWifiManager.setWifiEnabled(true);


        }
    };

    // M:@{ Channel Conflict UX Support
    private void startChannelConflictProcedure() {
        Slog.i(TAG, "startChannelConflictProcedure(), mChannelConflictState:" + mChannelConflictState + ",mWifiApConnected:" + mWifiApConnected);
        
        if (!isCCState(ChannelConflictState.STATE_IDLE)){
            //CC TODO: Handle state is not idle
            Slog.i(TAG, "State is wrong !!");
            return;
        }

        if (!mWifiApConnected) {
            Slog.i(TAG, "No WiFi AP Connected. Wrong !!");
            return;
        }

        if (wifiApHasSameFreq()) {

            // Store the current AP for reconnect           
            mNetworkId = mWifiNetworkId;
            Slog.i(TAG, "Same Network Id:" + mNetworkId);
            mDisplayApToast = false;
            
            // Disconnect AP
            mWifiManager.disconnect();
            enterCCState(ChannelConflictState.STATE_AP_DISCONNECTING);
            
        } else {
            mNetworkId = getSameFreqNetworkId();
            
            if (mNetworkId == -1) {
                // Display dialog
                mWifiP2pManager.setFreqConflictExResult(mWifiP2pChannel, false, null);
            } else {
                mDisplayApToast = true;
                
                // Disconnect AP
                mWifiManager.disconnect();
                enterCCState(ChannelConflictState.STATE_AP_DISCONNECTING);
            }                
        }           
    }

    private void handleChannelConflictProcedure(ChannelConflictEvt event){

        if (isCCState(null) || isCCState(ChannelConflictState.STATE_IDLE)){
            return;
        }
        Slog.i(TAG, "handleChannelConflictProcedure(), evt:" + event + ", ccState:" + mChannelConflictState);
        
        if (isCCState(ChannelConflictState.STATE_AP_DISCONNECTING)){
            if (event == ChannelConflictEvt.EVT_AP_DISCONNECTED) {
                // Connect WFD
                mWifiP2pManager.setFreqConflictExResult(mWifiP2pChannel, true, null);
                enterCCState(ChannelConflictState.STATE_WFD_CONNECTING);
            } else {
                //CC TODO: Error handling
                mWifiP2pManager.setFreqConflictExResult(mWifiP2pChannel, false, null);
                enterCCState(ChannelConflictState.STATE_IDLE);
            }
        } else if (isCCState(ChannelConflictState.STATE_WFD_CONNECTING)) {
            if (event == ChannelConflictEvt.EVT_WFD_P2P_CONNECTED){
                // Same SSID
                Slog.i(TAG, "connect AP, mNetworkId:" + mNetworkId);        
                               
                mWifiManager.connect(mNetworkId, null);
                
                enterCCState(ChannelConflictState.STATE_AP_CONNECTING);
                
            } else {
                //CC TODO: Error handling
                enterCCState(ChannelConflictState.STATE_IDLE);
            }  
        } else if (isCCState(ChannelConflictState.STATE_AP_CONNECTING)) {
            if (event == ChannelConflictEvt.EVT_AP_CONNECTED) {

                if (mDisplayApToast) {              
                    // Display Toast
                    Resources r = Resources.getSystem();
                    Toast.makeText(
                        mContext, 
                        r.getString(com.mediatek.internal.R.string.wifi_display_connected_to_wifi_network, mWifiApSsid), 
                        Toast.LENGTH_SHORT).show();
                }
                
                enterCCState(ChannelConflictState.STATE_IDLE);
            } else {
                //CC TODO: Error handling
                enterCCState(ChannelConflictState.STATE_IDLE);
            }  
        }
    }

    private boolean wifiApHasSameFreq() { 
        Slog.i(TAG, "wifiApHasSameFreq()");
        
        if (mWifiApSsid == null || mWifiApSsid.length() < 2) {
            Slog.e(TAG, "mWifiApSsid is invalid !!");
            return false;
        }

        // Remove (")
        String apSsid = mWifiApSsid.substring(1, mWifiApSsid.length() - 1);       
        
        final List<ScanResult> results = mWifiManager.getScanResults();
       
        boolean found = false;
        if (results != null) {
            for (ScanResult result : results) {
                Slog.i(TAG, "SSID:" + result.SSID + 
                            ",Freq:" + result.frequency +
                            ",Level:" + result.level +
                            ",BSSID:" + result.BSSID);
                // Ignore hidden and ad-hoc networks.
                if (result.SSID == null || result.SSID.length() == 0 ||
                    result.capabilities.contains("[IBSS]")) {
                    continue;
                }

                // Check if the AP has the same frequency as WFD
                if (result.SSID.equals(apSsid) && result.frequency == mP2pOperFreq) {
                    found = true;                   
                    break;
                }                
            }
        }
        Slog.i(TAG, "AP SSID:" + apSsid + ", sameFreq:" + found);
        return found;
    }
    
    private int getSameFreqNetworkId() { 
        Slog.i(TAG, "getSameFreqNetworkId()");
        final List<WifiConfiguration> everConnecteds = mWifiManager.getConfiguredNetworks();
        final List<ScanResult> results = mWifiManager.getScanResults();

        if (results == null || everConnecteds == null){
            Slog.i(TAG, "results:" + results +
                         ",everConnecteds:" + everConnecteds); 
            return -1;
        }
        
        int maxRssi = -128;  // min RSSI: -127 minus 1
        int selectedNetworkId = -1;
        for (WifiConfiguration everConnected : everConnecteds) {
            String trim = everConnected.SSID.substring(1, everConnected.SSID.length() - 1);
            
            Slog.i(TAG, "SSID:" + trim + ",NetId:" + everConnected.networkId);
            
            for (ScanResult result : results) {
                // Ignore hidden and ad-hoc networks.
                if (result.SSID == null || result.SSID.length() == 0 ||
                    result.capabilities.contains("[IBSS]")) {
                    continue;
                }   

                // Store the AP with same freq as dongle & max rssi
                if (trim.equals(result.SSID) &&
                    result.frequency == mP2pOperFreq && result.level > maxRssi){          

                    selectedNetworkId = everConnected.networkId;
                    maxRssi = result.level;
                    break;
                }           
            }
        }

        Slog.i(TAG, "Selected Network Id:" + selectedNetworkId);
        return selectedNetworkId;
    }

    private void enterCCState(ChannelConflictState state){
        Slog.i(TAG, "enterCCState()," + mChannelConflictState + "->" + state);
        mChannelConflictState = state;
    }

    private boolean isCCState(ChannelConflictState state){
        return  (mChannelConflictState == state) ? true : false;
    }

    
    ///@}
    
    /* [google mechanism] how to get connection state? from WifiDisplaySettings.java
        -SettingProvider: WIFI_DISPLAY_ON
        -Broadcast ACTION_WIFI_DISPLAY_STATUS_CHANGED
        -WifiDisplayStatus.getActiveDisplayState(): connect state
        -WifiDisplayStatus.getFeatureState(): feature state
    */
    ///@}
}
