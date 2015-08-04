/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.server.wifi.p2p;

import android.app.AlertDialog;
import android.app.Notification;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.DhcpResults;
import android.net.DhcpStateMachine;
import android.net.InterfaceConfiguration;
import android.net.LinkAddress;
import android.net.NetworkInfo;
import android.net.NetworkUtils;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.IWifiP2pManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pGroupList;
import android.net.wifi.p2p.WifiP2pGroupList.GroupDeleteListener;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.WifiP2pProvDiscEvent;
import android.net.wifi.p2p.WifiP2pWfdInfo;
import android.net.wifi.p2p.nsd.WifiP2pServiceInfo;
import android.net.wifi.p2p.nsd.WifiP2pServiceRequest;
import android.net.wifi.p2p.nsd.WifiP2pServiceResponse;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;

import com.android.internal.R;
import com.android.internal.util.AsyncChannel;
import com.android.internal.util.Protocol;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.server.wifi.WifiMonitor;
import com.android.server.wifi.WifiNative;
import com.android.server.wifi.WifiStateMachine;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

///M:@{
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.fastconnect.WifiP2pFastConnectInfo;
import android.net.wifi.p2p.link.WifiP2pLinkInfo;
import android.net.StaticIpConfiguration;
import android.os.SystemProperties;
import android.util.Log;
import android.widget.Toast;


import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.FileInputStream;
///@}

/**
 * WifiP2pService includes a state machine to perform Wi-Fi p2p operations. Applications
 * communicate with this service to issue device discovery and connectivity requests
 * through the WifiP2pManager interface. The state machine communicates with the wifi
 * driver through wpa_supplicant and handles the event responses through WifiMonitor.
 *
 * Note that the term Wifi when used without a p2p suffix refers to the client mode
 * of Wifi operation
 * @hide
 */
public final class WifiP2pServiceImpl extends IWifiP2pManager.Stub {
    private static final String TAG = "WifiP2pService";
    private static final boolean DBG = true;  ///Modify by MTK
    private static final String NETWORKTYPE = "WIFI_P2P";

    private Context mContext;
    private String mInterface;
    private Notification mNotification;

    INetworkManagementService mNwService;
    private DhcpStateMachine mDhcpStateMachine;

    private P2pStateMachine mP2pStateMachine;
    private AsyncChannel mReplyChannel = new AsyncChannel();
    private AsyncChannel mWifiChannel;

    private static final Boolean JOIN_GROUP = true;
    private static final Boolean FORM_GROUP = false;

    private static final Boolean RELOAD = true;
    private static final Boolean NO_RELOAD = false;

    /* Two minutes comes from the wpa_supplicant setting */
    private static final int GROUP_CREATING_WAIT_TIME_MS = 120 * 1000;
    private static int mGroupCreatingTimeoutIndex = 0;

    private static final int DISABLE_P2P_WAIT_TIME_MS = 5 * 1000;
    private static int mDisableP2pTimeoutIndex = 0;

    /* Set a two minute discover timeout to avoid STA scans from being blocked */
    private static final int DISCOVER_TIMEOUT_S = 120;

    /* Idle time after a peer is gone when the group is torn down */
    private static final int GROUP_IDLE_TIME_S = 10;

    private static final int BASE = Protocol.BASE_WIFI_P2P_SERVICE;

    /* Delayed message to timeout group creation */
    public static final int GROUP_CREATING_TIMED_OUT        =   BASE + 1;

    /* User accepted a peer request */
    private static final int PEER_CONNECTION_USER_ACCEPT    =   BASE + 2;
    /* User rejected a peer request */
    private static final int PEER_CONNECTION_USER_REJECT    =   BASE + 3;
    /* User wants to disconnect wifi in favour of p2p */
    private static final int DROP_WIFI_USER_ACCEPT          =   BASE + 4;
    /* User wants to keep his wifi connection and drop p2p */
    private static final int DROP_WIFI_USER_REJECT          =   BASE + 5;
    /* Delayed message to timeout p2p disable */
    public static final int DISABLE_P2P_TIMED_OUT           =   BASE + 6;
    /*M: wifi p2p fast connect*/
    public static final int FAST_CONNECT_FIND_GO_TIMED_OUT  =   BASE + 9;
    /*M: ALPS01467393: disableState need stop WifiMonitor*/
    private static final int STOP_P2P_MONITOR_TIMED_OUT     =   BASE + 10;


    /* Commands to the WifiStateMachine */
    public static final int P2P_CONNECTION_CHANGED          =   BASE + 11;

    /* These commands are used to temporarily disconnect wifi when we detect
     * a frequency conflict which would make it impossible to have with p2p
     * and wifi active at the same time.
     *
     * If the user chooses to disable wifi temporarily, we keep wifi disconnected
     * until the p2p connection is done and terminated at which point we will
     * bring back wifi up
     *
     * DISCONNECT_WIFI_REQUEST
     *      msg.arg1 = 1 enables temporary disconnect and 0 disables it.
     */
    public static final int DISCONNECT_WIFI_REQUEST         =   BASE + 12;
    public static final int DISCONNECT_WIFI_RESPONSE        =   BASE + 13;

    public static final int SET_MIRACAST_MODE               =   BASE + 14;

    // During dhcp (and perhaps other times) we can't afford to drop packets
    // but Discovery will switch our channel enough we will.
    //   msg.arg1 = ENABLED for blocking, DISABLED for resumed.
    //   msg.arg2 = msg to send when blocked
    //   msg.obj  = StateMachine to send to when blocked
    public static final int BLOCK_DISCOVERY                 =   BASE + 15;

    // set country code
    public static final int SET_COUNTRY_CODE                =   BASE + 16;

    /** M: NFC Float II @{ */
    private static final int M_CMD_NFC_TAG_READ             =   BASE + 20;
    private static final int M_CMD_NFC_HANDOVER_READ        =   BASE + 21;
    /** @} */

    public static final int ENABLED                         = 1;
    public static final int DISABLED                        = 0;

    private final boolean mP2pSupported;

    private WifiP2pDevice mThisDevice = new WifiP2pDevice();

    /* When a group has been explicitly created by an app, we persist the group
     * even after all clients have been disconnected until an explicit remove
     * is invoked */
    private boolean mAutonomousGroup;

    /* Invitation to join an existing p2p group */
    private boolean mJoinExistingGroup;

    /* Track whether we are in p2p discovery. This is used to avoid sending duplicate
     * broadcasts
     */
    private boolean mDiscoveryStarted;
    /* Track whether servcice/peer discovery is blocked in favor of other wifi actions
     * (notably dhcp)
     */
    private boolean mDiscoveryBlocked;

    // Supplicant doesn't like setting the same country code multiple times (it may drop
    // current connected network), so we save the country code here to avoid redundency
    private String mLastSetCountryCode;

    /*
     * remember if we were in a scan when it had to be stopped
     */
    private boolean mDiscoveryPostponed = false;

    private NetworkInfo mNetworkInfo;

    private boolean mTemporarilyDisconnectedWifi = false;

    /* The transaction Id of service discovery request */
    private byte mServiceTransactionId = 0;

    /* Service discovery request ID of wpa_supplicant.
     * null means it's not set yet. */
    private String mServiceDiscReqId;

    /* clients(application) information list. */
    private HashMap<Messenger, ClientInfo> mClientInfoList = new HashMap<Messenger, ClientInfo>();

    /* Is chosen as a unique address to avoid conflict with
       the ranges defined in Tethering.java */
    private static final String SERVER_ADDRESS = "192.168.49.1";

    ///M: variables @{
    /*M: wifi direct fast connect, default time out value is 30s*/
    private static final int FAST_CONNECT_FIND_GO_WAIT_TIME_MS = 30 * 1000;
    private static int mFastConnectFindGoTimeOutIndex = 0;

    /*M: fast connect scan time interval is 3s*/
    private static final int FAST_SCAN_INTERVAL_TIME_MS = 3 * 1000;

    /*M: for fast connect scan mechanism */
    private static final int FAST_DISCOVER_TIMEOUT_S = 123;

    /*M: Set 25s for ALPS00450978, because scan block to
     * feel some peers has diappeared */
    private static final int CONNECTED_DISCOVER_TIMEOUT_S = 25;

    /*M: Power Saving Command*/
    public static final int P2P_ACTIVE  = 0;
    //When traffic is large will not ajust active/PS
    public static final int P2P_MAX_PS  = 1;
    //When traffic is large ajust active/PS automatically
    public static final int P2P_FAST_PS = 2;

    /*M: add to Enable wifi/wifi p2p */
    private WifiManager mWifiManager;

    /*M: NFC hand over wifi direct start*/
    private WifiP2pFastConnectInfo mFastConnectInfo =  new WifiP2pFastConnectInfo();
    private boolean mGcFastConnectEnaled = false;
    private boolean mGoFastConnectEnaled = false;
    private boolean mFoundGo = false;
    private boolean mFoundGoTimeOut = false;
    private boolean mRestartFastConnectAsGo = false;
    private boolean mRestartFastConnectAsGc = false;
    /*NFC hand over wifi direct end*/

    /*M: add for ALPS00489161.The reason is when group created successfully,
    and UI do not know in time still send cancel connect command.
    This case GroupcreatedState should remove the group. But when Inviting 3-device
    to join a group and 3-device do not response.At this time GO or GC click cancel means
    the Group has created entirely, so use the default solution to process the cancel command*/
    private boolean mGroupCreatedEntirely = false;

    /*M: ALPS00677009: broadcast the group removed reason*/
    private P2pStatus mGroupRemoveReason = P2pStatus.UNKNOWN;

    /*M: ALPS01000113: sync. Beam+ state*/
    private boolean mBeamPlusStart = false;

    /* M: ALPS01593529: no p2p_invite in wfd source case */
    private int mMiracastMode = WifiP2pManager.MIRACAST_DISABLED;

    /*M: ALPS01303168: wfd sink */
    private int mDeviceCapa;
    // for getPeerIpAddress()
    private static final String DHCP_INFO_FILE = "/data/misc/dhcp/dnsmasq.p2p0.leases";

    /*M: ALPS01467393: disableState need stop WifiMonitor */
    private static final int STOP_P2P_MONITOR_WAIT_TIME_MS = 5 * 1000;
    private static int mStopP2pMonitorTimeoutIndex = 0;

    /** M: NFC Float II @{ */
    private static final String SUBMASK = "255.255.255.0";
    private static final String[] GC_IP_RANGE = {"192.168.49.2", "192.168.49.3", "192.168.49.4", "192.168.49.5",
        "192.168.49.6", "192.168.49.7", "192.168.49.8", "192.168.49.9", "192.168.49.10", "192.168.49.11"};
    private static final int CLEAR_TMP_GO_REQUEST = 1;
    private static final int CLEAR_LISTEN_FLAG_REQUEST = 2;
    private static final String UNKNOWN_COMMAND = "UNKNOWN COMMAND";
    private static final long CLEAR_OPERATION_MS = 2 * 60 * 1000;
    private String mTmpGcIp;
    private String mTmpGoIp;
    private String mTmpGoAddress;
    private HashMap<String, String> mClientIp = new HashMap<String, String>();
    private PendingIntent mClearTmpGoIntent;
    private AlarmManager mAlarmManager;
    private boolean mWaitingForHrToken = false;
    private boolean mListening = false;

    private static final boolean mMtkWpsp2pnfcSupport  = SystemProperties.get("ro.mtk_wifiwpsp2p_nfc_support").equals("1");

    //intent: send broadcast
    private static final String MTK_NFC_WFA_P2P_HR_ACTION = "mtk.nfc.wfa.p2p.HR_ACTION";
    private static final String MTK_NFC_WFA_TAG_WRITE_ACTION = "mtk.nfc.wfa.tag.WRITE_ACTION";  //The device want to write Tag which includes WFA static handover info
    private static final String MTK_NFC_WFA_CFG_TAG_WRITE_ACTION = "mtk.nfc.wfa.cfg.tag.WRITE_ACTION";  //The device want to write Configuration Tag when it acts as GO

    private static final String MTK_WPS_NFC_TESTBED_P2P_AUTOGO_AS_SEL_ACTION = "mtk.wps.nfc.testbed.p2pgo.as.sel";  //Wi-Fi Direct test item:6.1.18
    private static final String MTK_WPS_NFC_TESTBED_EXTRA_PASSWORD = "mtk.wps.nfc.testbed.extra.password";

    //intent: recv broadcast
    private static final String ACTION_CLEAR_TMP_GO = "com.android.server.WifiManager.action.CLEAR_TMP_GO";
    private static final String MTK_NFC_WFA_P2P_HR_RECEIVE_ACTION = "mtk.nfc.wfa.p2p.HR_RECEIVE_ACTION";
    private static final String MTK_NFC_WFA_P2P_EXTRA_HR_P2P_DEV_INFO = "mtk.nfc.wfa.p2p.extra.HR_P2P_DEV_INFO";  //byte array

    private static final String MTK_NFC_WFA_P2P_HS_RECEIVE_ACTION = "mtk.nfc.wfa.p2p.HS_RECEIVE_ACTION";
    private static final String MTK_NFC_WFA_P2P_EXTRA_HS_P2P_DEV_INFO = "mtk.nfc.wfa.p2p.extra.HS_P2P_DEV_INFO";  //byte array

    private static final String MTK_NFC_WFA_TAG_RECEIVE_ACTION = "mtk.nfc.wfa.tag.RECEIVE_ACTION";  //The device read Tag with WFA static handover info.
    private static final String MTK_NFC_WFA_TAG_EXTRA_DEV_INFO = "mtk.nfc.wfa.tag.extra.DEV_INFO";  //byte array,same as HS_P2P_DEV_INFO
    /** @} */

    /** M: enhance frequency conflict @{ */
    private int p2pOperFreq = -1;
    ///@}

    /**
     * Error code definition.
     * see the Table.8 in the WiFi Direct specification for the detail.
     */
    public static enum P2pStatus {
        /* Success. */
        SUCCESS,

        /* The target device is currently unavailable. */
        INFORMATION_IS_CURRENTLY_UNAVAILABLE,

        /* Protocol error. */
        INCOMPATIBLE_PARAMETERS,

        /* The target device reached the limit of the number of the connectable device.
         * For example, device limit or group limit is set. */
        LIMIT_REACHED,

        /* Protocol error. */
        INVALID_PARAMETER,

        /* Unable to accommodate request. */
        UNABLE_TO_ACCOMMODATE_REQUEST,

        /* Previous protocol error, or disruptive behavior. */
        PREVIOUS_PROTOCOL_ERROR,

        /* There is no common channels the both devices can use. */
        NO_COMMON_CHANNEL,

        /* Unknown p2p group. For example, Device A tries to invoke the previous persistent group,
         *  but device B has removed the specified credential already. */
        UNKNOWN_P2P_GROUP,

        /* Both p2p devices indicated an intent of 15 in group owner negotiation. */
        BOTH_GO_INTENT_15,

        /* Incompatible provisioning method. */
        INCOMPATIBLE_PROVISIONING_METHOD,

        /* Rejected by user */
        REJECTED_BY_USER,

        ///M: expand reason code  @{
        MTK_EXPAND_01,
        MTK_EXPAND_02,
        ///@}

        /* Unknown error */
        UNKNOWN;

        public static P2pStatus valueOf(int error) {
            switch(error) {
            case 0 :
                return SUCCESS;
            case 1:
                return INFORMATION_IS_CURRENTLY_UNAVAILABLE;
            case 2:
                return INCOMPATIBLE_PARAMETERS;
            case 3:
                return LIMIT_REACHED;
            case 4:
                return INVALID_PARAMETER;
            case 5:
                return UNABLE_TO_ACCOMMODATE_REQUEST;
            case 6:
                return PREVIOUS_PROTOCOL_ERROR;
            case 7:
                return NO_COMMON_CHANNEL;
            case 8:
                return UNKNOWN_P2P_GROUP;
            case 9:
                return BOTH_GO_INTENT_15;
            case 10:
                return INCOMPATIBLE_PROVISIONING_METHOD;
            case 11:
                return REJECTED_BY_USER;
            ///M: expand reason code  @{
            case 12:
                return MTK_EXPAND_01;
            case 13:
                return MTK_EXPAND_02;
            ///@}
            default:
                return UNKNOWN;
            }
        }
    }

    /**
     * Handles client connections
     */
    private class ClientHandler extends Handler {

        ClientHandler(android.os.Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
              case WifiP2pManager.SET_DEVICE_NAME:
              case WifiP2pManager.SET_WFD_INFO:
              case WifiP2pManager.DISCOVER_PEERS:
              case WifiP2pManager.STOP_DISCOVERY:
              case WifiP2pManager.CONNECT:
              case WifiP2pManager.CANCEL_CONNECT:
              case WifiP2pManager.CREATE_GROUP:
              case WifiP2pManager.REMOVE_GROUP:
              case WifiP2pManager.START_LISTEN:
              case WifiP2pManager.STOP_LISTEN:
              case WifiP2pManager.SET_CHANNEL:
              case WifiP2pManager.START_WPS:
              case WifiP2pManager.ADD_LOCAL_SERVICE:
              case WifiP2pManager.REMOVE_LOCAL_SERVICE:
              case WifiP2pManager.CLEAR_LOCAL_SERVICES:
              case WifiP2pManager.DISCOVER_SERVICES:
              case WifiP2pManager.ADD_SERVICE_REQUEST:
              case WifiP2pManager.REMOVE_SERVICE_REQUEST:
              case WifiP2pManager.CLEAR_SERVICE_REQUESTS:
              case WifiP2pManager.REQUEST_PEERS:
              case WifiP2pManager.REQUEST_CONNECTION_INFO:
              case WifiP2pManager.REQUEST_GROUP_INFO:
              case WifiP2pManager.DELETE_PERSISTENT_GROUP:
              case WifiP2pManager.REQUEST_PERSISTENT_GROUP_INFO:
              ///M: MTK added on feature  @{
              case WifiP2pManager.GET_HANDOVER_REQUEST:  //Google feature
              case WifiP2pManager.GET_HANDOVER_SELECT:  //Google feature
              case WifiP2pManager.INITIATOR_REPORT_NFC_HANDOVER:  //Google feature
              case WifiP2pManager.RESPONDER_REPORT_NFC_HANDOVER:  //Google feature
              case WifiP2pManager.REQUEST_LINK_INFO:
              case WifiP2pManager.SET_AUTO_CHANNEL_SELECT:
              case WifiP2pManager.START_FAST_CONNECT_AS_GC:
              case WifiP2pManager.GET_NFC_REQUEST_TOKEN:
              case WifiP2pManager.GET_NFC_SELECT_TOKEN:
              case WifiP2pManager.GET_NFC_CONFIG_TOKEN:
              case WifiP2pManager.GET_NFC_WPS_CONFIG_TOKEN:
              case WifiP2pManager.PEER_CONNECTION_USER_ACCEPT_FROM_OUTER:
              case WifiP2pManager.PEER_CONNECTION_USER_REJECT_FROM_OUTER:
              case WifiP2pManager.FREQ_CONFLICT_EX_RESULT:
              ///@}
                mP2pStateMachine.sendMessage(Message.obtain(msg));
                break;
              default:
                Slog.d(TAG, "ClientHandler.handleMessage ignoring msg=" + msg);
                break;
            }
        }
    }
    private ClientHandler mClientHandler;

    public WifiP2pServiceImpl(Context context) {
        mContext = context;

        //STOPSHIP: get this from native side
        mInterface = "p2p0";
        mNetworkInfo = new NetworkInfo(ConnectivityManager.TYPE_WIFI_P2P, 0, NETWORKTYPE, "");

        mP2pSupported = mContext.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_WIFI_DIRECT);

        mThisDevice.primaryDeviceType = mContext.getResources().getString(
                com.android.internal.R.string.config_wifi_p2p_device_type);

        HandlerThread wifiP2pThread = new HandlerThread("WifiP2pService");
        wifiP2pThread.start();
        mClientHandler = new ClientHandler(wifiP2pThread.getLooper());

        mP2pStateMachine = new P2pStateMachine(TAG, wifiP2pThread.getLooper(), mP2pSupported);
        mP2pStateMachine.start();
    }

    public void connectivityServiceReady() {
        IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
        mNwService = INetworkManagementService.Stub.asInterface(b);
    }

    private void enforceAccessPermission() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.ACCESS_WIFI_STATE,
                "WifiP2pService");
    }

    private void enforceChangePermission() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.CHANGE_WIFI_STATE,
                "WifiP2pService");
    }

    private void enforceConnectivityInternalPermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CONNECTIVITY_INTERNAL,
                "WifiP2pService");
    }

    private int checkConnectivityInternalPermission() {
        return mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.CONNECTIVITY_INTERNAL);
    }

    private int checkLocationHardwarePermission() {
        return mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.LOCATION_HARDWARE);
    }

    private void enforceConnectivityInternalOrLocationHardwarePermission() {
        if (checkConnectivityInternalPermission() != PackageManager.PERMISSION_GRANTED
                && checkLocationHardwarePermission() != PackageManager.PERMISSION_GRANTED) {
            enforceConnectivityInternalPermission();
        }
    }

    /**
     * Get a reference to handler. This is used by a client to establish
     * an AsyncChannel communication with WifiP2pService
     */
    public Messenger getMessenger() {
        enforceAccessPermission();
        enforceChangePermission();
        return new Messenger(mClientHandler);
    }

    /**
     * Get a reference to handler. This is used by a WifiStateMachine to establish
     * an AsyncChannel communication with P2pStateMachine
     * @hide
     */
    public Messenger getP2pStateMachineMessenger() {
        enforceConnectivityInternalOrLocationHardwarePermission();
        enforceAccessPermission();
        enforceChangePermission();
        return new Messenger(mP2pStateMachine.getHandler());
    }

    /** This is used to provide information to drivers to optimize performance depending
     * on the current mode of operation.
     * 0 - disabled
     * 1 - source operation
     * 2 - sink operation
     *
     * As an example, the driver could reduce the channel dwell time during scanning
     * when acting as a source or sink to minimize impact on miracast.
     */
    public void setMiracastMode(int mode) {
        enforceConnectivityInternalPermission();
        mP2pStateMachine.sendMessage(SET_MIRACAST_MODE, mode);
    }

    ///M: wfd sink MCC mechanism  @{
    public void setMiracastModeEx(int mode, int freq) {
        enforceConnectivityInternalPermission();
        mP2pStateMachine.sendMessage(SET_MIRACAST_MODE, mode, freq);
    }
    ///@}

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump WifiP2pService from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }
        mP2pStateMachine.dump(fd, pw, args);
        pw.println("mAutonomousGroup " + mAutonomousGroup);
        pw.println("mJoinExistingGroup " + mJoinExistingGroup);
        pw.println("mDiscoveryStarted " + mDiscoveryStarted);
        pw.println("mNetworkInfo " + mNetworkInfo);
        pw.println("mTemporarilyDisconnectedWifi " + mTemporarilyDisconnectedWifi);
        pw.println("mServiceDiscReqId " + mServiceDiscReqId);
        pw.println();
    }

    ///M:@{
    /**
     * M: set in fast connect info's device address to framework
     * return the crendential and mac address of it self
     * @hide
     */
    public WifiP2pFastConnectInfo fastConnectAsGo(WifiP2pFastConnectInfo info) {
        try {
            Log.d(TAG, "Fast Connect As GO in fastConnectIno: " + info);
            WifiP2pFastConnectInfo returnConnectInfo = new WifiP2pFastConnectInfo(WifiNative.getCredential());

            //M: ALPS01467371: make no use of WifiNative.getP2pDeviceAddress()
            //return connect info contain the device address of GO
            returnConnectInfo.deviceAddress = mThisDevice.deviceAddress;  //GO mac

            // get GC address, GO can avoid other device connect to it.
            mFastConnectInfo = new WifiP2pFastConnectInfo(returnConnectInfo);
            mFastConnectInfo.deviceAddress = info.deviceAddress;  //GC mac
            mP2pStateMachine.sendMessage(WifiP2pManager.START_FAST_CONNECT_AS_GO);

            Log.d(TAG, "Fast Connect As GO return fastConnectIno: " + returnConnectInfo);
            Log.d(TAG, "Fast Connect As GO Self fastConnectInfo: " + mFastConnectInfo);
            return returnConnectInfo;
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "fastConnectAsGo() IllegalArgumentException: " + e);
            return null;
        }
    }

    /*M: get this device's Device Address*/
    public String getMacAddress() {
        Log.e(TAG, "getMacAddress(): " + mThisDevice.deviceAddress);
        return mThisDevice.deviceAddress;
    }

    /*M: get peer device's IP*/
    public String getPeerIpAddress(String peerMacAddress) {
        return mP2pStateMachine.getPeerIpAddress(peerMacAddress);
    }
    ///@}

    /**
     * Handles interaction with WifiStateMachine
     */
    private class P2pStateMachine extends StateMachine {

        private DefaultState mDefaultState = new DefaultState();
        private P2pNotSupportedState mP2pNotSupportedState = new P2pNotSupportedState();
        private P2pDisablingState mP2pDisablingState = new P2pDisablingState();
        private P2pDisabledState mP2pDisabledState = new P2pDisabledState();
        private P2pEnablingState mP2pEnablingState = new P2pEnablingState();
        private P2pEnabledState mP2pEnabledState = new P2pEnabledState();
        // Inactive is when p2p is enabled with no connectivity
        private InactiveState mInactiveState = new InactiveState();
        private GroupCreatingState mGroupCreatingState = new GroupCreatingState();
        private UserAuthorizingInviteRequestState mUserAuthorizingInviteRequestState
                = new UserAuthorizingInviteRequestState();
        private UserAuthorizingNegotiationRequestState mUserAuthorizingNegotiationRequestState
                = new UserAuthorizingNegotiationRequestState();
        private ProvisionDiscoveryState mProvisionDiscoveryState = new ProvisionDiscoveryState();
        private GroupNegotiationState mGroupNegotiationState = new GroupNegotiationState();
        private FrequencyConflictState mFrequencyConflictState =new FrequencyConflictState();

        private GroupCreatedState mGroupCreatedState = new GroupCreatedState();
        private UserAuthorizingJoinState mUserAuthorizingJoinState = new UserAuthorizingJoinState();
        private OngoingGroupRemovalState mOngoingGroupRemovalState = new OngoingGroupRemovalState();

        private WifiNative mWifiNative = new WifiNative(mInterface);
        private WifiMonitor mWifiMonitor = new WifiMonitor(this, mWifiNative);

        private final WifiP2pDeviceList mPeers = new WifiP2pDeviceList();
        /* During a connection, supplicant can tell us that a device was lost. From a supplicant's
         * perspective, the discovery stops during connection and it purges device since it does
         * not get latest updates about the device without being in discovery state.
         *
         * From the framework perspective, the device is still there since we are connecting or
         * connected to it. so we keep these devices in a separate list, so that they are removed
         * when connection is cancelled or lost
         */
        private final WifiP2pDeviceList mPeersLostDuringConnection = new WifiP2pDeviceList();
        private final WifiP2pGroupList mGroups = new WifiP2pGroupList(null,
                new GroupDeleteListener() {
            @Override
            public void onDeleteGroup(int netId) {
                if (DBG) logd("called onDeleteGroup() netId=" + netId);
                mWifiNative.removeNetwork(netId);
                mWifiNative.saveConfig();
                sendP2pPersistentGroupsChangedBroadcast();
            }
        });
        private final WifiP2pInfo mWifiP2pInfo = new WifiP2pInfo();
        private WifiP2pGroup mGroup;

        // Saved WifiP2pConfig for an ongoing peer connection. This will never be null.
        // The deviceAddress will be an empty string when the device is inactive
        // or if it is connected without any ongoing join request
        private WifiP2pConfig mSavedPeerConfig = new WifiP2pConfig();

        // Saved WifiP2pGroup from invitation request
        private WifiP2pGroup mSavedP2pGroup;

        P2pStateMachine(String name, Looper looper, boolean p2pSupported) {
            super(name, looper);

            addState(mDefaultState);
                addState(mP2pNotSupportedState, mDefaultState);
                addState(mP2pDisablingState, mDefaultState);
                addState(mP2pDisabledState, mDefaultState);
                addState(mP2pEnablingState, mDefaultState);
                addState(mP2pEnabledState, mDefaultState);
                    addState(mInactiveState, mP2pEnabledState);
                    addState(mGroupCreatingState, mP2pEnabledState);
                        addState(mUserAuthorizingInviteRequestState, mGroupCreatingState);
                        addState(mUserAuthorizingNegotiationRequestState, mGroupCreatingState);
                        addState(mProvisionDiscoveryState, mGroupCreatingState);
                        addState(mGroupNegotiationState, mGroupCreatingState);
                        addState(mFrequencyConflictState, mGroupCreatingState);
                    addState(mGroupCreatedState, mP2pEnabledState);
                        addState(mUserAuthorizingJoinState, mGroupCreatedState);
                        addState(mOngoingGroupRemovalState, mGroupCreatedState);

            if (p2pSupported) {
                setInitialState(mP2pDisabledState);
            } else {
                setInitialState(mP2pNotSupportedState);
            }

            /** M: NFC Float II @{ */
            if (mMtkWpsp2pnfcSupport) {
                mAlarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
                mClearTmpGoIntent = PendingIntent.getBroadcast(mContext, CLEAR_TMP_GO_REQUEST, new Intent(ACTION_CLEAR_TMP_GO), 0);
                IntentFilter filter = new IntentFilter();
                filter.addAction(WifiP2pServiceImpl.MTK_NFC_WFA_P2P_HR_RECEIVE_ACTION);
                filter.addAction(WifiP2pServiceImpl.MTK_NFC_WFA_P2P_HS_RECEIVE_ACTION);
                filter.addAction(WifiP2pServiceImpl.MTK_NFC_WFA_TAG_RECEIVE_ACTION);
                mContext.registerReceiver(
                    new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context context, Intent intent) {
                            String action = intent.getAction();
                            logd("Received: " + action);
                            if (action.equals(WifiP2pServiceImpl.MTK_NFC_WFA_P2P_HR_RECEIVE_ACTION)) {
                                byte[] token = intent.getByteArrayExtra(WifiP2pServiceImpl.MTK_NFC_WFA_P2P_EXTRA_HR_P2P_DEV_INFO);
                                if (token != null) {
                                    sendMessage(obtainMessage(M_CMD_NFC_HANDOVER_READ, 1, -1, bytesToHexString(token)));
                                } else {
                                    loge("No HR token!");
                                }
                            } else if (action.equals(WifiP2pServiceImpl.MTK_NFC_WFA_P2P_HS_RECEIVE_ACTION)) {
                                byte[] token = intent.getByteArrayExtra(WifiP2pServiceImpl.MTK_NFC_WFA_P2P_EXTRA_HS_P2P_DEV_INFO);
                                if (token != null) {
                                    sendMessage(obtainMessage(M_CMD_NFC_HANDOVER_READ, 0, -1, bytesToHexString(token)));
                                } else {
                                    loge("No HS token!");
                                }
                            } else if (action.equals(WifiP2pServiceImpl.MTK_NFC_WFA_TAG_RECEIVE_ACTION)) {
                                byte[] token = intent.getByteArrayExtra(WifiP2pServiceImpl.MTK_NFC_WFA_TAG_EXTRA_DEV_INFO);
                                if (token != null) {
                                    sendMessage(obtainMessage(M_CMD_NFC_TAG_READ, bytesToHexString(token)));
                                } else {
                                    loge("No device info!");
                                }
                            } else if (action.equals(ACTION_CLEAR_TMP_GO)) {
                                mTmpGoAddress = null;
                            }
                        }
                    },
                    filter);
            }
            /** @} */

            setLogRecSize(50);
            setLogOnlyTransitions(true);
        }

    class DefaultState extends State {
        @Override
        public boolean processMessage(Message message) {
            if (DBG) logd(getName() + message.toString());
            switch (message.what) {
                case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED:
                    if (message.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                        if (DBG) logd("Full connection with WifiStateMachine established");
                        mWifiChannel = (AsyncChannel) message.obj;
                    } else {
                        loge("Full connection failure, error = " + message.arg1);
                        mWifiChannel = null;
                    }
                    break;

                case AsyncChannel.CMD_CHANNEL_DISCONNECTED:
                    if (message.arg1 == AsyncChannel.STATUS_SEND_UNSUCCESSFUL) {
                        loge("Send failed, client connection lost");
                    } else {
                        loge("Client connection lost with reason: " + message.arg1);
                    }
                    mWifiChannel = null;
                    break;

                case AsyncChannel.CMD_CHANNEL_FULL_CONNECTION:
                    AsyncChannel ac = new AsyncChannel();
                    ac.connect(mContext, getHandler(), message.replyTo);
                    break;
                case BLOCK_DISCOVERY:
                    mDiscoveryBlocked = (message.arg1 == ENABLED ? true : false);
                    // always reset this - we went to a state that doesn't support discovery so
                    // it would have stopped regardless
                    mDiscoveryPostponed = false;
                    if (mDiscoveryBlocked) {
                        try {
                            StateMachine m = (StateMachine)message.obj;
                            m.sendMessage(message.arg2);
                        } catch (Exception e) {
                            loge("unable to send BLOCK_DISCOVERY response: " + e);
                        }
                    }
                    break;
                case WifiP2pManager.DISCOVER_PEERS:
                    replyToMessage(message, WifiP2pManager.DISCOVER_PEERS_FAILED,
                            WifiP2pManager.BUSY);
                    break;
                case WifiP2pManager.STOP_DISCOVERY:
                    replyToMessage(message, WifiP2pManager.STOP_DISCOVERY_FAILED,
                            WifiP2pManager.BUSY);
                    break;
                case WifiP2pManager.DISCOVER_SERVICES:
                    replyToMessage(message, WifiP2pManager.DISCOVER_SERVICES_FAILED,
                            WifiP2pManager.BUSY);
                    break;
                case WifiP2pManager.CONNECT:
                    replyToMessage(message, WifiP2pManager.CONNECT_FAILED,
                            WifiP2pManager.BUSY);
                    break;
                case WifiP2pManager.CANCEL_CONNECT:
                    replyToMessage(message, WifiP2pManager.CANCEL_CONNECT_FAILED,
                            WifiP2pManager.BUSY);
                    break;
                case WifiP2pManager.CREATE_GROUP:
                    replyToMessage(message, WifiP2pManager.CREATE_GROUP_FAILED,
                            WifiP2pManager.BUSY);
                    break;
                case WifiP2pManager.REMOVE_GROUP:
                    replyToMessage(message, WifiP2pManager.REMOVE_GROUP_FAILED,
                            WifiP2pManager.BUSY);
                    break;
                case WifiP2pManager.ADD_LOCAL_SERVICE:
                    replyToMessage(message, WifiP2pManager.ADD_LOCAL_SERVICE_FAILED,
                            WifiP2pManager.BUSY);
                    break;
                case WifiP2pManager.REMOVE_LOCAL_SERVICE:
                    replyToMessage(message, WifiP2pManager.REMOVE_LOCAL_SERVICE_FAILED,
                            WifiP2pManager.BUSY);
                    break;
                case WifiP2pManager.CLEAR_LOCAL_SERVICES:
                    replyToMessage(message, WifiP2pManager.CLEAR_LOCAL_SERVICES_FAILED,
                            WifiP2pManager.BUSY);
                    break;
                case WifiP2pManager.ADD_SERVICE_REQUEST:
                    replyToMessage(message, WifiP2pManager.ADD_SERVICE_REQUEST_FAILED,
                            WifiP2pManager.BUSY);
                    break;
                case WifiP2pManager.REMOVE_SERVICE_REQUEST:
                    replyToMessage(message,
                            WifiP2pManager.REMOVE_SERVICE_REQUEST_FAILED,
                            WifiP2pManager.BUSY);
                    break;
                case WifiP2pManager.CLEAR_SERVICE_REQUESTS:
                    replyToMessage(message,
                            WifiP2pManager.CLEAR_SERVICE_REQUESTS_FAILED,
                            WifiP2pManager.BUSY);
                    break;
                case WifiP2pManager.SET_DEVICE_NAME:
                    replyToMessage(message, WifiP2pManager.SET_DEVICE_NAME_FAILED,
                            WifiP2pManager.BUSY);
                    break;
                case WifiP2pManager.DELETE_PERSISTENT_GROUP:
                    replyToMessage(message, WifiP2pManager.DELETE_PERSISTENT_GROUP,
                            WifiP2pManager.BUSY);
                    break;
                case WifiP2pManager.SET_WFD_INFO:
                    replyToMessage(message, WifiP2pManager.SET_WFD_INFO_FAILED,
                            WifiP2pManager.BUSY);
                    break;
                case WifiP2pManager.REQUEST_PEERS:
                    replyToMessage(message, WifiP2pManager.RESPONSE_PEERS,
                            new WifiP2pDeviceList(mPeers));
                    break;
                case WifiP2pManager.REQUEST_CONNECTION_INFO:
                    replyToMessage(message, WifiP2pManager.RESPONSE_CONNECTION_INFO,
                            new WifiP2pInfo(mWifiP2pInfo));
                    break;
                case WifiP2pManager.REQUEST_GROUP_INFO:
                    replyToMessage(message, WifiP2pManager.RESPONSE_GROUP_INFO,
                            mGroup != null ? new WifiP2pGroup(mGroup) : null);
                    break;
                case WifiP2pManager.REQUEST_PERSISTENT_GROUP_INFO:
                    replyToMessage(message, WifiP2pManager.RESPONSE_PERSISTENT_GROUP_INFO,
                            new WifiP2pGroupList(mGroups, null));
                    break;
                case WifiP2pManager.START_WPS:
                    replyToMessage(message, WifiP2pManager.START_WPS_FAILED,
                        WifiP2pManager.BUSY);
                    break;
                case WifiP2pManager.GET_HANDOVER_REQUEST:
                case WifiP2pManager.GET_HANDOVER_SELECT:
                    replyToMessage(message, WifiP2pManager.RESPONSE_GET_HANDOVER_MESSAGE, null);
                    break;
                case WifiP2pManager.INITIATOR_REPORT_NFC_HANDOVER:
                case WifiP2pManager.RESPONDER_REPORT_NFC_HANDOVER:
                    replyToMessage(message, WifiP2pManager.REPORT_NFC_HANDOVER_FAILED,
                            WifiP2pManager.BUSY);
                    break;
                    // Ignore
                case WifiMonitor.P2P_INVITATION_RESULT_EVENT:
                case WifiMonitor.SCAN_RESULTS_EVENT:
                case WifiMonitor.SUP_CONNECTION_EVENT:
                case WifiMonitor.SUP_DISCONNECTION_EVENT:
                case WifiMonitor.NETWORK_CONNECTION_EVENT:
                case WifiMonitor.NETWORK_DISCONNECTION_EVENT:
                case WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT:
                case WifiMonitor.AUTHENTICATION_FAILURE_EVENT:
                case WifiMonitor.WPS_SUCCESS_EVENT:
                case WifiMonitor.WPS_FAIL_EVENT:
                case WifiMonitor.WPS_OVERLAP_EVENT:
                case WifiMonitor.WPS_TIMEOUT_EVENT:
                case WifiMonitor.P2P_GROUP_REMOVED_EVENT:
                case WifiMonitor.P2P_DEVICE_FOUND_EVENT:
                case WifiMonitor.P2P_DEVICE_LOST_EVENT:
                case WifiMonitor.P2P_FIND_STOPPED_EVENT:
                case WifiMonitor.P2P_SERV_DISC_RESP_EVENT:
                case PEER_CONNECTION_USER_ACCEPT:
                case PEER_CONNECTION_USER_REJECT:
                case DISCONNECT_WIFI_RESPONSE:
                case DROP_WIFI_USER_ACCEPT:
                case DROP_WIFI_USER_REJECT:
                case GROUP_CREATING_TIMED_OUT:
                case DISABLE_P2P_TIMED_OUT:
                case DhcpStateMachine.CMD_PRE_DHCP_ACTION:
                case DhcpStateMachine.CMD_POST_DHCP_ACTION:
                case DhcpStateMachine.CMD_ON_QUIT:
                case WifiMonitor.P2P_PROV_DISC_FAILURE_EVENT:
                case SET_MIRACAST_MODE:
                case WifiP2pManager.START_LISTEN:
                case WifiP2pManager.STOP_LISTEN:
                case WifiP2pManager.SET_CHANNEL:
                case SET_COUNTRY_CODE:
                    break;
                case WifiStateMachine.CMD_ENABLE_P2P:
                    // Enable is lazy and has no response
                    break;
                case WifiStateMachine.CMD_DISABLE_P2P_REQ:
                    // If we end up handling in default, p2p is not enabled
                    mWifiChannel.sendMessage(WifiStateMachine.CMD_DISABLE_P2P_RSP);
                    break;
                    /* unexpected group created, remove */
                case WifiMonitor.P2P_GROUP_STARTED_EVENT:
                    mGroup = (WifiP2pGroup) message.obj;
                    loge("Unexpected group creation, remove " + mGroup);
                    mWifiNative.p2pGroupRemove(mGroup.getInterface());
                    break;
                // A group formation failure is always followed by
                // a group removed event. Flushing things at group formation
                // failure causes supplicant issues. Ignore right now.
                case WifiMonitor.P2P_GROUP_FORMATION_FAILURE_EVENT:
                    break;
                /** M: NFC Float II @{ */
                case WifiP2pManager.GET_NFC_REQUEST_TOKEN:
                    replyToMessage(message, WifiP2pManager.GET_NFC_REQUEST_TOKEN_FAILED,
                            WifiP2pManager.BUSY);
                    break;
                case WifiP2pManager.GET_NFC_SELECT_TOKEN:
                    replyToMessage(message, WifiP2pManager.GET_NFC_SELECT_TOKEN_FAILED,
                            WifiP2pManager.BUSY);
                    break;
                case WifiP2pManager.GET_NFC_CONFIG_TOKEN:
                    replyToMessage(message, WifiP2pManager.GET_NFC_CONFIG_TOKEN_FAILED,
                            WifiP2pManager.BUSY);
                    break;
                case WifiP2pManager.GET_NFC_WPS_CONFIG_TOKEN:
                    replyToMessage(message, WifiP2pManager.GET_NFC_WPS_CONFIG_TOKEN_FAILED,
                            WifiP2pManager.BUSY);
                    break;
                case WifiStateMachine.M_CMD_CLEAE_HR_WAIT_FLAG:
                    mWaitingForHrToken = false;
                    break;
                /** @} */
                ///M: wfd sink @{
                case WifiP2pManager.PEER_CONNECTION_USER_ACCEPT_FROM_OUTER:
                    break;
                case WifiP2pManager.PEER_CONNECTION_USER_REJECT_FROM_OUTER:
                    break;
                ///@}
                default:
                    loge("Unhandled message " + message);
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class P2pNotSupportedState extends State {
        @Override
        public boolean processMessage(Message message) {
            switch (message.what) {
               case WifiP2pManager.DISCOVER_PEERS:
                    replyToMessage(message, WifiP2pManager.DISCOVER_PEERS_FAILED,
                            WifiP2pManager.P2P_UNSUPPORTED);
                    break;
                case WifiP2pManager.STOP_DISCOVERY:
                    replyToMessage(message, WifiP2pManager.STOP_DISCOVERY_FAILED,
                            WifiP2pManager.P2P_UNSUPPORTED);
                    break;
                case WifiP2pManager.DISCOVER_SERVICES:
                    replyToMessage(message, WifiP2pManager.DISCOVER_SERVICES_FAILED,
                            WifiP2pManager.P2P_UNSUPPORTED);
                    break;
                case WifiP2pManager.CONNECT:
                    replyToMessage(message, WifiP2pManager.CONNECT_FAILED,
                            WifiP2pManager.P2P_UNSUPPORTED);
                    break;
                case WifiP2pManager.CANCEL_CONNECT:
                    replyToMessage(message, WifiP2pManager.CANCEL_CONNECT_FAILED,
                            WifiP2pManager.P2P_UNSUPPORTED);
                    break;
               case WifiP2pManager.CREATE_GROUP:
                    replyToMessage(message, WifiP2pManager.CREATE_GROUP_FAILED,
                            WifiP2pManager.P2P_UNSUPPORTED);
                    break;
                case WifiP2pManager.REMOVE_GROUP:
                    replyToMessage(message, WifiP2pManager.REMOVE_GROUP_FAILED,
                            WifiP2pManager.P2P_UNSUPPORTED);
                    break;
                case WifiP2pManager.ADD_LOCAL_SERVICE:
                    replyToMessage(message, WifiP2pManager.ADD_LOCAL_SERVICE_FAILED,
                            WifiP2pManager.P2P_UNSUPPORTED);
                    break;
                case WifiP2pManager.REMOVE_LOCAL_SERVICE:
                    replyToMessage(message, WifiP2pManager.REMOVE_LOCAL_SERVICE_FAILED,
                            WifiP2pManager.P2P_UNSUPPORTED);
                    break;
                case WifiP2pManager.CLEAR_LOCAL_SERVICES:
                    replyToMessage(message, WifiP2pManager.CLEAR_LOCAL_SERVICES_FAILED,
                            WifiP2pManager.P2P_UNSUPPORTED);
                    break;
                case WifiP2pManager.ADD_SERVICE_REQUEST:
                    replyToMessage(message, WifiP2pManager.ADD_SERVICE_REQUEST_FAILED,
                            WifiP2pManager.P2P_UNSUPPORTED);
                    break;
                case WifiP2pManager.REMOVE_SERVICE_REQUEST:
                    replyToMessage(message,
                            WifiP2pManager.REMOVE_SERVICE_REQUEST_FAILED,
                            WifiP2pManager.P2P_UNSUPPORTED);
                    break;
                case WifiP2pManager.CLEAR_SERVICE_REQUESTS:
                    replyToMessage(message,
                            WifiP2pManager.CLEAR_SERVICE_REQUESTS_FAILED,
                            WifiP2pManager.P2P_UNSUPPORTED);
                    break;
                case WifiP2pManager.SET_DEVICE_NAME:
                    replyToMessage(message, WifiP2pManager.SET_DEVICE_NAME_FAILED,
                            WifiP2pManager.P2P_UNSUPPORTED);
                    break;
                case WifiP2pManager.DELETE_PERSISTENT_GROUP:
                    replyToMessage(message, WifiP2pManager.DELETE_PERSISTENT_GROUP,
                            WifiP2pManager.P2P_UNSUPPORTED);
                    break;
                case WifiP2pManager.SET_WFD_INFO:
                    replyToMessage(message, WifiP2pManager.SET_WFD_INFO_FAILED,
                            WifiP2pManager.P2P_UNSUPPORTED);
                    break;
                case WifiP2pManager.START_WPS:
                    replyToMessage(message, WifiP2pManager.START_WPS_FAILED,
                            WifiP2pManager.P2P_UNSUPPORTED);
                    break;
                case WifiP2pManager.START_LISTEN:
                    replyToMessage(message, WifiP2pManager.START_LISTEN_FAILED,
                            WifiP2pManager.P2P_UNSUPPORTED);
                    break;
                case WifiP2pManager.STOP_LISTEN:
                    replyToMessage(message, WifiP2pManager.STOP_LISTEN_FAILED,
                            WifiP2pManager.P2P_UNSUPPORTED);
                    break;
                /** M: NFC Float II @{ */
                case WifiP2pManager.GET_NFC_REQUEST_TOKEN:
                    replyToMessage(message, WifiP2pManager.GET_NFC_REQUEST_TOKEN_FAILED,
                            WifiP2pManager.P2P_UNSUPPORTED);
                    break;
                case WifiP2pManager.GET_NFC_SELECT_TOKEN:
                    replyToMessage(message, WifiP2pManager.GET_NFC_SELECT_TOKEN_FAILED,
                            WifiP2pManager.P2P_UNSUPPORTED);
                    break;
                case WifiP2pManager.GET_NFC_CONFIG_TOKEN:
                    replyToMessage(message, WifiP2pManager.GET_NFC_CONFIG_TOKEN_FAILED,
                            WifiP2pManager.P2P_UNSUPPORTED);
                    break;
                case WifiP2pManager.GET_NFC_WPS_CONFIG_TOKEN:
                    replyToMessage(message, WifiP2pManager.GET_NFC_WPS_CONFIG_TOKEN_FAILED,
                            WifiP2pManager.P2P_UNSUPPORTED);
                    break;
                /** @} */
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class P2pDisablingState extends State {
        @Override
        public void enter() {
            if (DBG) logd(getName());
            sendMessageDelayed(obtainMessage(DISABLE_P2P_TIMED_OUT,
                    ++mDisableP2pTimeoutIndex, 0), DISABLE_P2P_WAIT_TIME_MS);
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) logd(getName() + message.toString());
            switch (message.what) {
                case WifiMonitor.SUP_DISCONNECTION_EVENT:
                    if (DBG) logd("p2p socket connection lost");
                    transitionTo(mP2pDisabledState);
                    break;
                case WifiStateMachine.CMD_ENABLE_P2P:
                case WifiStateMachine.CMD_DISABLE_P2P_REQ:
                case WifiP2pManager.START_FAST_CONNECT_AS_GO:  ///Add by MTK
                case WifiP2pManager.START_FAST_CONNECT_AS_GC:  ///Add by MTK
                    deferMessage(message);
                    break;
                case DISABLE_P2P_TIMED_OUT:
                    if (mDisableP2pTimeoutIndex == message.arg1) {
                        loge("P2p disable timed out");
                        transitionTo(mP2pDisabledState);
                    }
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        @Override
        public void exit() {
            mWifiChannel.sendMessage(WifiStateMachine.CMD_DISABLE_P2P_RSP);
        }
    }

    class P2pDisabledState extends State {
       @Override
        public void enter() {
            if (DBG) logd(getName());
            ///M: ALPS01289841: device will do fast GC/GO immediately after wifi enabled
            ///    when wifi disable->enable without group form or form failed @{
            mGoFastConnectEnaled = false;
            mGcFastConnectEnaled = false;
            ///@}
            ///M: ALPS01467393: P2pDisableState need stop WifiMonitor @{
            if (mWifiMonitor.isMonitoring()) {
                sendMessageDelayed(obtainMessage(STOP_P2P_MONITOR_TIMED_OUT,
                        ++mStopP2pMonitorTimeoutIndex, 0), STOP_P2P_MONITOR_WAIT_TIME_MS);
            }
            ///@}
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) logd(getName() + message.toString());
            switch (message.what) {
                case WifiStateMachine.CMD_ENABLE_P2P:
                    try {
                        mNwService.setInterfaceUp(mInterface);
                        ///M: ALPS01443292: enable/disable IPv6 on demand  @{
                        mNwService.disableIpv6(mInterface);
                        ///@}
                    } catch (RemoteException re) {
                        loge("Unable to change interface settings: " + re);
                    } catch (IllegalStateException ie) {
                        loge("Unable to change interface settings: " + ie);
                    }
                    mWifiMonitor.startMonitoring();
                    transitionTo(mP2pEnablingState);
                    break;
                ///M:@{
                case WifiP2pManager.START_FAST_CONNECT_AS_GO:
                    setWifiOn_WifiAPOff();
                    mGoFastConnectEnaled = true;
                    break;
                case WifiP2pManager.START_FAST_CONNECT_AS_GC:
                    setWifiOn_WifiAPOff();
                    mFastConnectInfo = (WifiP2pFastConnectInfo) message.obj;
                    logd("Fast Connect As GC mFastConnectInfo = " + mFastConnectInfo);
                    mGcFastConnectEnaled = true;
                    break;
                //M: ALPS01467393: P2pDisableState need stop WifiMonitor
                case STOP_P2P_MONITOR_TIMED_OUT:
                    if (mStopP2pMonitorTimeoutIndex == message.arg1 &&
                            mWifiMonitor.isMonitoring()) {
                        mWifiMonitor.stopMonitoring();
                        mWifiNative.stopDriver();  ///Add by MTK
                    }
                    break;
                ///@}
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class P2pEnablingState extends State {
        @Override
        public void enter() {
            if (DBG) logd(getName());
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) logd(getName() + message.toString());
            switch (message.what) {
                case WifiMonitor.SUP_CONNECTION_EVENT:
                    if (DBG) logd("P2p socket connection successful");
                    mWifiNative.startDriver();  ///Add by MTK
                    transitionTo(mInactiveState);
                    break;
                case WifiMonitor.SUP_DISCONNECTION_EVENT:
                    loge("P2p socket connection failed");
                    transitionTo(mP2pDisabledState);
                    break;
                case WifiStateMachine.CMD_ENABLE_P2P:
                case WifiStateMachine.CMD_DISABLE_P2P_REQ:
                case WifiP2pManager.START_FAST_CONNECT_AS_GO:  ///Add by MTK
                case WifiP2pManager.START_FAST_CONNECT_AS_GC:  ///Add by MTK
                    deferMessage(message);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class P2pEnabledState extends State {
        @Override
        public void enter() {
            if (DBG) logd(getName());
            sendP2pStateChangedBroadcast(true);
            mNetworkInfo.setIsAvailable(true);
            sendP2pConnectionChangedBroadcast();
            initializeP2pSettings();
            ///M: NFC hand over wifi direct @{
            if (mGoFastConnectEnaled) setFastConnectInfo(WifiP2pManager.FAST_CONNECT_AS_GO);
            if (mGcFastConnectEnaled) setFastConnectInfo(WifiP2pManager.FAST_CONNECT_AS_GC);
            ///@}

            /** M: NFC Float II @{ */
            if (mMtkWpsp2pnfcSupport) {
                Settings.System.putInt(mContext.getContentResolver(), "autonomous_go", 0);
                synchronized (mClientIp) {
                    mClientIp.clear();
                }
                mTmpGcIp = null;
                mTmpGoIp = null;
                mWaitingForHrToken = false;
            }
            /** @} */
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) logd(getName() + message.toString());
            switch (message.what) {
                case WifiMonitor.SUP_DISCONNECTION_EVENT:
                    loge("Unexpected loss of p2p socket connection");
                    transitionTo(mP2pDisabledState);
                    break;
                case WifiStateMachine.CMD_ENABLE_P2P:
                    //Nothing to do
                    break;
                case WifiStateMachine.CMD_DISABLE_P2P_REQ:
                    if (mPeers.clear()) {
                        sendPeersChangedBroadcast();
                    }
                    if (mGroups.clear()) sendP2pPersistentGroupsChangedBroadcast();

                    mWifiMonitor.stopMonitoring();
                    mWifiNative.stopDriver();  ///Add by MTK
                    transitionTo(mP2pDisablingState);
                    break;
                case WifiP2pManager.SET_DEVICE_NAME:
                {
                    WifiP2pDevice d = (WifiP2pDevice) message.obj;
                    if (d != null && setAndPersistDeviceName(d.deviceName)) {
                        if (DBG) logd("set device name " + d.deviceName);
                        replyToMessage(message, WifiP2pManager.SET_DEVICE_NAME_SUCCEEDED);
                    } else {
                        replyToMessage(message, WifiP2pManager.SET_DEVICE_NAME_FAILED,
                                WifiP2pManager.ERROR);
                    }
                    break;
                }
                case WifiP2pManager.SET_WFD_INFO:
                {
                    WifiP2pWfdInfo d = (WifiP2pWfdInfo) message.obj;
                    if (d != null && setWfdInfo(d)) {
                        replyToMessage(message, WifiP2pManager.SET_WFD_INFO_SUCCEEDED);
                    } else {
                        replyToMessage(message, WifiP2pManager.SET_WFD_INFO_FAILED,
                                WifiP2pManager.ERROR);
                    }
                    break;
                }
                case BLOCK_DISCOVERY:
                    boolean blocked = (message.arg1 == ENABLED ? true : false);
                    if (mDiscoveryBlocked == blocked) break;
                    mDiscoveryBlocked = blocked;
                    if (blocked && mDiscoveryStarted) {
                        mWifiNative.p2pStopFind();
                        mDiscoveryPostponed = true;
                    }
                    if (!blocked && mDiscoveryPostponed) {
                        mDiscoveryPostponed = false;
                        mWifiNative.p2pFind(DISCOVER_TIMEOUT_S);
                    }
                    if (blocked) {
                        try {
                            StateMachine m = (StateMachine)message.obj;
                            m.sendMessage(message.arg2);
                        } catch (Exception e) {
                            loge("unable to send BLOCK_DISCOVERY response: " + e);
                        }
                    }
                    break;
                case WifiP2pManager.DISCOVER_PEERS:
                    if (mDiscoveryBlocked) {
                        replyToMessage(message, WifiP2pManager.DISCOVER_PEERS_FAILED,
                                WifiP2pManager.BUSY);
                        break;
                    }
                    // do not send service discovery request while normal find operation.
                    clearSupplicantServiceRequest();

                    ///M: wfd sink  @{
                    boolean retP2pFind = false;
                    if (isDeviceBeWfdSink()) {
                        //copy from HE dongle
                        p2pConfigWfdSink();
                        retP2pFind = mWifiNative.p2pFind();
                    } else {
                        retP2pFind = mWifiNative.p2pFind(DISCOVER_TIMEOUT_S);
                    } 
                    if (true == retP2pFind) {
                    ///@}
                        replyToMessage(message, WifiP2pManager.DISCOVER_PEERS_SUCCEEDED);
                        sendP2pDiscoveryChangedBroadcast(true);
                    } else {
                        replyToMessage(message, WifiP2pManager.DISCOVER_PEERS_FAILED,
                                WifiP2pManager.ERROR);
                    }
                    break;
                case WifiMonitor.P2P_FIND_STOPPED_EVENT:
                    sendP2pDiscoveryChangedBroadcast(false);
                    break;
                case WifiP2pManager.STOP_DISCOVERY:
                    if (mWifiNative.p2pStopFind()) {
                        replyToMessage(message, WifiP2pManager.STOP_DISCOVERY_SUCCEEDED);
                    } else {
                        replyToMessage(message, WifiP2pManager.STOP_DISCOVERY_FAILED,
                                WifiP2pManager.ERROR);
                    }
                    ///M: wfd sink, restore configuration  @{
                    if (isDeviceBeWfdSink())    p2pUnconfigWfdSink();
                    ///@}
                    break;
                case WifiP2pManager.DISCOVER_SERVICES:
                    if (mDiscoveryBlocked) {
                        replyToMessage(message, WifiP2pManager.DISCOVER_SERVICES_FAILED,
                                WifiP2pManager.BUSY);
                        break;
                    }
                    if (DBG) logd(getName() + " discover services");
                    if (!updateSupplicantServiceRequest()) {
                        replyToMessage(message, WifiP2pManager.DISCOVER_SERVICES_FAILED,
                                WifiP2pManager.NO_SERVICE_REQUESTS);
                        break;
                    }
                    if (mWifiNative.p2pFind(DISCOVER_TIMEOUT_S)) {
                        replyToMessage(message, WifiP2pManager.DISCOVER_SERVICES_SUCCEEDED);
                    } else {
                        replyToMessage(message, WifiP2pManager.DISCOVER_SERVICES_FAILED,
                                WifiP2pManager.ERROR);
                    }
                    break;
                case WifiMonitor.P2P_DEVICE_FOUND_EVENT:
                    WifiP2pDevice device = (WifiP2pDevice) message.obj;
                    if (mThisDevice.deviceAddress.equals(device.deviceAddress)) break;
                    mPeers.updateSupplicantDetails(device);
                    sendPeersChangedBroadcast();
                    ///M: wifi direct fast connect, found peer before timing out @{
                    logd("P2P_DEVICE_FOUND_EVENT, Fast connect GC enableState:" + mGcFastConnectEnaled
                            + " findTimeout:" + mFoundGoTimeOut + " FoundGo:" + mFoundGo);

                    if (mGcFastConnectEnaled && !mFoundGoTimeOut && !mFoundGo
                            &&  mFastConnectInfo.deviceAddress.equals(device.deviceAddress)) {
                        sendMessage(WifiP2pManager.FAST_CONNECT_AS_GC);
                        logd("Found Fast connect peer:" + mFastConnectInfo.deviceAddress);
                        mFoundGo = true;
                    }
                    ///@}

                    /** M: NFC Float II @{ */
                    if (mMtkWpsp2pnfcSupport) {
                        if (mTmpGoAddress != null && mTmpGoAddress.equals(device.deviceAddress)) {
                            logd("Found the GO device which we known from NFC:" + device.deviceAddress);
                            WifiP2pConfig goConfig = new WifiP2pConfig();
                            goConfig.deviceAddress = device.deviceAddress;
                            goConfig.wps.setup = WpsInfo.PBC;
                            sendMessage(WifiP2pManager.CONNECT, goConfig);
                            mTmpGoAddress = null;
                        }
                    }
                    /** @} */
                    break;
                case WifiMonitor.P2P_DEVICE_LOST_EVENT:
                    device = (WifiP2pDevice) message.obj;
                    // Gets current details for the one removed
                    device = mPeers.remove(device.deviceAddress);
                    if (device != null) {
                        sendPeersChangedBroadcast();
                    }
                    break;
                case WifiP2pManager.ADD_LOCAL_SERVICE:
                    if (DBG) logd(getName() + " add service");
                    WifiP2pServiceInfo servInfo = (WifiP2pServiceInfo)message.obj;
                    if (addLocalService(message.replyTo, servInfo)) {
                        replyToMessage(message, WifiP2pManager.ADD_LOCAL_SERVICE_SUCCEEDED);
                    } else {
                        replyToMessage(message, WifiP2pManager.ADD_LOCAL_SERVICE_FAILED);
                    }
                    break;
                case WifiP2pManager.REMOVE_LOCAL_SERVICE:
                    if (DBG) logd(getName() + " remove service");
                    servInfo = (WifiP2pServiceInfo)message.obj;
                    removeLocalService(message.replyTo, servInfo);
                    replyToMessage(message, WifiP2pManager.REMOVE_LOCAL_SERVICE_SUCCEEDED);
                    break;
                case WifiP2pManager.CLEAR_LOCAL_SERVICES:
                    if (DBG) logd(getName() + " clear service");
                    clearLocalServices(message.replyTo);
                    replyToMessage(message, WifiP2pManager.CLEAR_LOCAL_SERVICES_SUCCEEDED);
                    break;
                case WifiP2pManager.ADD_SERVICE_REQUEST:
                    if (DBG) logd(getName() + " add service request");
                    if (!addServiceRequest(message.replyTo, (WifiP2pServiceRequest)message.obj)) {
                        replyToMessage(message, WifiP2pManager.ADD_SERVICE_REQUEST_FAILED);
                        break;
                    }
                    replyToMessage(message, WifiP2pManager.ADD_SERVICE_REQUEST_SUCCEEDED);
                    break;
                case WifiP2pManager.REMOVE_SERVICE_REQUEST:
                    if (DBG) logd(getName() + " remove service request");
                    removeServiceRequest(message.replyTo, (WifiP2pServiceRequest)message.obj);
                    replyToMessage(message, WifiP2pManager.REMOVE_SERVICE_REQUEST_SUCCEEDED);
                    break;
                case WifiP2pManager.CLEAR_SERVICE_REQUESTS:
                    if (DBG) logd(getName() + " clear service request");
                    clearServiceRequests(message.replyTo);
                    replyToMessage(message, WifiP2pManager.CLEAR_SERVICE_REQUESTS_SUCCEEDED);
                    break;
                case WifiMonitor.P2P_SERV_DISC_RESP_EVENT:
                    if (DBG) logd(getName() + " receive service response");
                    List<WifiP2pServiceResponse> sdRespList =
                        (List<WifiP2pServiceResponse>) message.obj;
                    for (WifiP2pServiceResponse resp : sdRespList) {
                        WifiP2pDevice dev =
                            mPeers.get(resp.getSrcDevice().deviceAddress);
                        resp.setSrcDevice(dev);
                        sendServiceResponse(resp);
                    }
                    break;
                case WifiP2pManager.DELETE_PERSISTENT_GROUP:
                    if (DBG) logd(getName() + " delete persistent group");
                    mGroups.remove(message.arg1);
                    replyToMessage(message, WifiP2pManager.DELETE_PERSISTENT_GROUP_SUCCEEDED);
                    break;
                case SET_MIRACAST_MODE:
                    ///M: wfd sink MCC mechanism  @{
                    if (0 != message.arg2) {
                        mWifiNative.setMiracastMode(message.arg1, message.arg2);
                    } else
                    ///@}
                    mWifiNative.setMiracastMode(message.arg1);

                    ///M: ALPS01593529: no p2p_invite in wfd source case @{
                    mMiracastMode = (int)message.arg1;
                    ///@}
                    break;
                case WifiP2pManager.START_LISTEN:
                    if (DBG) logd(getName() + " start listen mode");
                    mWifiNative.p2pFlush();
                    if (mWifiNative.p2pExtListen(true, 500, 500)) {
                        replyToMessage(message, WifiP2pManager.START_LISTEN_SUCCEEDED);
                    } else {
                        replyToMessage(message, WifiP2pManager.START_LISTEN_FAILED);
                    }
                    break;
                case WifiP2pManager.STOP_LISTEN:
                    if (DBG) logd(getName() + " stop listen mode");
                    if (mWifiNative.p2pExtListen(false, 0, 0)) {
                        replyToMessage(message, WifiP2pManager.STOP_LISTEN_SUCCEEDED);
                    } else {
                        replyToMessage(message, WifiP2pManager.STOP_LISTEN_FAILED);
                    }
                    mWifiNative.p2pFlush();
                    break;
                case WifiP2pManager.SET_CHANNEL:
                    Bundle p2pChannels = (Bundle) message.obj;
                    int lc = p2pChannels.getInt("lc", 0);
                    int oc = p2pChannels.getInt("oc", 0);
                    if (DBG) logd(getName() + " set listen and operating channel");
                    if (mWifiNative.p2pSetChannel(lc, oc)) {
                        replyToMessage(message, WifiP2pManager.SET_CHANNEL_SUCCEEDED);
                    } else {
                        replyToMessage(message, WifiP2pManager.SET_CHANNEL_FAILED);
                    }
                    break;
                case SET_COUNTRY_CODE:
                    String countryCode = (String) message.obj;
                    countryCode = countryCode.toUpperCase(Locale.ROOT);
                    if (mLastSetCountryCode == null ||
                            countryCode.equals(mLastSetCountryCode) == false) {
                        if (mWifiNative.setCountryCode(countryCode)) {
                            mLastSetCountryCode = countryCode;
                        }
                    }
                    break;
                case WifiP2pManager.GET_HANDOVER_REQUEST:
                    Bundle requestBundle = new Bundle();
                    requestBundle.putString(WifiP2pManager.EXTRA_HANDOVER_MESSAGE,
                            mWifiNative.getNfcHandoverRequest());
                    replyToMessage(message, WifiP2pManager.RESPONSE_GET_HANDOVER_MESSAGE,
                            requestBundle);
                    break;
                case WifiP2pManager.GET_HANDOVER_SELECT:
                    Bundle selectBundle = new Bundle();
                    selectBundle.putString(WifiP2pManager.EXTRA_HANDOVER_MESSAGE,
                            mWifiNative.getNfcHandoverSelect());
                    replyToMessage(message, WifiP2pManager.RESPONSE_GET_HANDOVER_MESSAGE,
                            selectBundle);
                    break;
                ///M:@{
                case WifiP2pManager.FAST_DISCOVER_PEERS:
                    logd("FAST_DISCOVER_PEERS, Fast connect GC enableState:" + mGcFastConnectEnaled
                           + " findTimeout:" + mFoundGoTimeOut + " FoundGo:" + mFoundGo);

                    if (mGcFastConnectEnaled && !mFoundGoTimeOut && !mFoundGo) {
                        sendMessageDelayed(WifiP2pManager.FAST_DISCOVER_PEERS, FAST_SCAN_INTERVAL_TIME_MS);
                        clearSupplicantServiceRequest();
                        if (mWifiNative.p2pFind(FAST_DISCOVER_TIMEOUT_S)) {
                            logd(getName() + " Fast connect scan OK");
                            sendP2pDiscoveryChangedBroadcast(true);
                        }
                    }
                    break;
                case WifiP2pManager.REQUEST_LINK_INFO:
                    WifiP2pLinkInfo info = (WifiP2pLinkInfo) message.obj;
                    info.linkInfo = mWifiNative.p2pLinkStatics(info.interfaceAddress);
                    logd("Wifi P2p link info is " + info.toString());
                    replyToMessage(message, WifiP2pManager.RESPONSE_LINK_INFO, new WifiP2pLinkInfo(info));
                    break;
                case WifiP2pManager.SET_AUTO_CHANNEL_SELECT:
                    int enable = message.arg1;
                    mWifiNative.p2pAutoChannel(enable);
                    replyToMessage(message, WifiP2pManager.SET_AUTO_CHANNEL_SELECT_SUCCEEDED);
                    break;
                /** M: NFC Float II @{ */
                case WifiP2pManager.GET_NFC_REQUEST_TOKEN:      //OOB Device Discovery
                    if (!mMtkWpsp2pnfcSupport) {
                        replyToMessage(message, WifiP2pManager.GET_NFC_REQUEST_TOKEN_FAILED);
                        break;
                    }
                    String token = mWifiNative.getNfcHandoverRequest();
                    logd("GET_NFC_REQUEST_TOKEN, token:" + token);
                    if (!TextUtils.isEmpty(token) && !token.equals(UNKNOWN_COMMAND)) {
                        sendNfcRequestTokenBroadcast(token);
                        replyToMessage(message, WifiP2pManager.GET_NFC_REQUEST_TOKEN_SUCCEEDED);
                    } else {
                        logd("Failed to get nfc select token!");
                        replyToMessage(message, WifiP2pManager.GET_NFC_REQUEST_TOKEN_FAILED);
                    }
                    break;
                case WifiP2pManager.GET_NFC_SELECT_TOKEN:       //Write P2P Tag
                    if (!mMtkWpsp2pnfcSupport) {
                        replyToMessage(message, WifiP2pManager.GET_NFC_SELECT_TOKEN_FAILED);
                        break;
                    }
                    token = mWifiNative.getNfcHandoverSelect();
                    logd("GET_NFC_SELECT_TOKEN, token:" + token);
                    if (!TextUtils.isEmpty(token) && !token.equals(UNKNOWN_COMMAND)) {
                        sendNfcSelectTokenBroadcast(token);
                        replyToMessage(message, WifiP2pManager.GET_NFC_SELECT_TOKEN_SUCCEEDED);
                    } else {
                        logd("Failed to get nfc select token!");
                        replyToMessage(message, WifiP2pManager.GET_NFC_SELECT_TOKEN_FAILED);
                    }
                    break;
                case M_CMD_NFC_TAG_READ:
                    if (!mMtkWpsp2pnfcSupport) {
                        break;
                    }
                    mWifiNative.wpsNfcTagRead((String) message.obj);
                    break;
                case WifiMonitor.P2P_GO_IP_ALLOCATE_EVENT:
                    if (!mMtkWpsp2pnfcSupport) {
                        break;
                    }
                    mTmpGcIp = null;
                    synchronized (mClientIp) {
                        for (String ip : GC_IP_RANGE) {
                            if (!mClientIp.containsValue(ip)) {
                                mTmpGcIp = ip;
                                break;
                            }
                        }
                    }
                    logd("P2P_GO_IP_ALLOCATE_EVENT, mTmpGcIp:" + mTmpGcIp);
                    if (mTmpGcIp != null) {
                        mWifiNative.p2pSetAllocateIp(mTmpGcIp, SUBMASK, SERVER_ADDRESS);
                    } else {
                        loge("No more IP for GC!");
                    }
                    break;
                case WifiMonitor.P2P_GC_IP_GET_EVENT:
                    if (!mMtkWpsp2pnfcSupport) {
                        break;
                    }
                    mTmpGcIp = null;
                    mTmpGoIp = null;
                    String gcIp = (String) message.obj;
                    logd("P2P_GC_IP_GET_EVENT, gcIp:" + gcIp);
                    if (!TextUtils.isEmpty(gcIp)) {
                        String[] tokens = gcIp.split(" ");
                        if (tokens.length == 4) {
                            String[] nameValue = tokens[1].split("=");
                            if (nameValue.length == 2) {
                                mTmpGcIp = nameValue[1];
                            }
                            nameValue = tokens[3].split("=");
                            if (nameValue.length == 2) {
                                mTmpGoIp = nameValue[1];
                            }
                        }
                    } else {
                        loge("Failed to get GC IP!");
                    }
                    break;
                case WifiStateMachine.M_CMD_HR_RECEIVED:
                    if (!mMtkWpsp2pnfcSupport) {
                        break;
                    }
                    logd("M_CMD_HR_RECEIVED, mWaitingForHrToken:" + mWaitingForHrToken);
                    if (mWaitingForHrToken) {
                        mWifiNative.nfcRxHandoverToken((String) message.obj, true);
                        mWaitingForHrToken = false;
                    }
                    break;
                ///@}
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        @Override
        public void exit() {
            sendP2pDiscoveryChangedBroadcast(false);
            sendP2pStateChangedBroadcast(false);
            mNetworkInfo.setIsAvailable(false);

            mLastSetCountryCode = null;

            /** M: NFC Float II @{ */
            if (mMtkWpsp2pnfcSupport) {
                mTmpGoAddress = null;
            }
            /** @} */
        }
    }

    class InactiveState extends State {
        @Override
        public void enter() {
            if (DBG) logd(getName());
            ///M: it will cause 2nd connect of channel conflict failed, Google issue @{
            //mSavedPeerConfig.invalidate();
            ///@}
            /** M: NFC Float II @{ */
            if (mMtkWpsp2pnfcSupport) {
                 synchronized (mClientIp) {
                    logd("mClientIp clear");
                    mClientIp.clear();
                }
            }
            /** @} */
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) logd(getName() + message.toString());
            switch (message.what) {
                case WifiP2pManager.CONNECT:
                    if (DBG) logd(getName() + " sending connect:" + (WifiP2pConfig) message.obj);
                    WifiP2pConfig config = (WifiP2pConfig) message.obj;
                    if (isConfigInvalid(config)) {
                        loge("Dropping connect requeset " + config);
                        replyToMessage(message, WifiP2pManager.CONNECT_FAILED);
                        break;
                    }

                    mAutonomousGroup = false;
                    ///M: it will cause provision failed @{
                    //mWifiNative.p2pStopFind();
                    ///@}

                    ////M: ALPS01593529: no p2p_invite in wfd source case @{
                    ////logd("CONNECT: mMiracastMode=" + mMiracastMode);
                    ////if (WifiP2pManager.MIRACAST_SOURCE!=mMiracastMode && reinvokePersistentGroup(config)) {
                    ////@}
                    if (reinvokePersistentGroup(config)) {
                        transitionTo(mGroupNegotiationState);
                    } else {
                        transitionTo(mProvisionDiscoveryState);
                    }
                    mSavedPeerConfig = config;
                    mPeers.updateStatus(mSavedPeerConfig.deviceAddress, WifiP2pDevice.INVITED);
                    sendPeersChangedBroadcast();
                    replyToMessage(message, WifiP2pManager.CONNECT_SUCCEEDED);
                    break;
                case WifiP2pManager.STOP_DISCOVERY:
                    if (mWifiNative.p2pStopFind()) {
                        // When discovery stops in inactive state, flush to clear
                        // state peer data
                        mWifiNative.p2pFlush();
                        mServiceDiscReqId = null;
                        replyToMessage(message, WifiP2pManager.STOP_DISCOVERY_SUCCEEDED);
                    } else {
                        replyToMessage(message, WifiP2pManager.STOP_DISCOVERY_FAILED,
                                WifiP2pManager.ERROR);
                    }
                    break;
                case WifiMonitor.P2P_GO_NEGOTIATION_REQUEST_EVENT:
                    config = (WifiP2pConfig) message.obj;
                    if (isConfigInvalid(config)) {
                        loge("Dropping GO neg request " + config);
                        break;
                    }
                    mSavedPeerConfig = config;
                    mAutonomousGroup = false;
                    mJoinExistingGroup = false;
                    transitionTo(mUserAuthorizingNegotiationRequestState);
                    break;
                case WifiMonitor.P2P_INVITATION_RECEIVED_EVENT:
                    WifiP2pGroup group = (WifiP2pGroup) message.obj;
                    WifiP2pDevice owner = group.getOwner();

                    if (owner == null) {
                        loge("Ignored invitation from null owner");
                        break;
                    }

                    config = new WifiP2pConfig();
                    config.deviceAddress = group.getOwner().deviceAddress;

                    if (isConfigInvalid(config)) {
                        loge("Dropping invitation request " + config);
                        break;
                    }
                    mSavedPeerConfig = config;

                    //Check if we have the owner in peer list and use appropriate
                    //wps method. Default is to use PBC.
                    if ((owner = mPeers.get(owner.deviceAddress)) != null) {
                        if (owner.wpsPbcSupported()) {
                            mSavedPeerConfig.wps.setup = WpsInfo.PBC;
                        } else if (owner.wpsKeypadSupported()) {
                            mSavedPeerConfig.wps.setup = WpsInfo.KEYPAD;
                        } else if (owner.wpsDisplaySupported()) {
                            mSavedPeerConfig.wps.setup = WpsInfo.DISPLAY;
                        }
                    }

                    mAutonomousGroup = false;
                    mJoinExistingGroup = true;
                    transitionTo(mUserAuthorizingInviteRequestState);
                    break;
                case WifiMonitor.P2P_PROV_DISC_PBC_REQ_EVENT:
                case WifiMonitor.P2P_PROV_DISC_ENTER_PIN_EVENT:
                case WifiMonitor.P2P_PROV_DISC_SHOW_PIN_EVENT:
                    //We let the supplicant handle the provision discovery response
                    //and wait instead for the GO_NEGOTIATION_REQUEST_EVENT.
                    //Handling provision discovery and issuing a p2p_connect before
                    //group negotiation comes through causes issues
                    
                    ///M: wfd sink, support show PIN passively  @{
                    if (WifiMonitor.P2P_PROV_DISC_SHOW_PIN_EVENT==message.what && 
                            isDeviceBeWfdSink()) {
                        logd("[wfd sink] show PIN passively");

                        WifiP2pProvDiscEvent provDisc = (WifiP2pProvDiscEvent) message.obj;
                        config = new WifiP2pConfig();

                        config.deviceAddress = provDisc.device.deviceAddress;
                        if (isConfigInvalid(config)) {
                            loge("Dropping invitation show PIN request " + config);
                            break;
                        }
                        mSavedPeerConfig = config;
                        mSavedPeerConfig.wps.setup = WpsInfo.DISPLAY;
                        mSavedPeerConfig.wps.pin = provDisc.pin;

                        //p2pConnectWithPinDisplay(mSavedPeerConfig);
                        //notifyInvitationSent(provDisc.pin, mSavedPeerConfig.deviceAddress);
                        //transitionTo(mGroupNegotiationState);
                        transitionTo(mUserAuthorizingNegotiationRequestState);
                    }
                    ///@}
                    break;
                case WifiP2pManager.CREATE_GROUP:
                    mAutonomousGroup = true;
                    int netId = message.arg1;
                    boolean ret = false;
                    if (netId == WifiP2pGroup.PERSISTENT_NET_ID) {
                        // check if the go persistent group is present.
                        netId = mGroups.getNetworkId(mThisDevice.deviceAddress);
                        if (netId != -1) {
                            ret = mWifiNative.p2pGroupAdd(netId);
                        } else {
                            ret = mWifiNative.p2pGroupAdd(true);
                        }
                    } else {
                        ret = mWifiNative.p2pGroupAdd(false);
                    }

                    if (ret) {
                        replyToMessage(message, WifiP2pManager.CREATE_GROUP_SUCCEEDED);
                        transitionTo(mGroupNegotiationState);
                    } else {
                        replyToMessage(message, WifiP2pManager.CREATE_GROUP_FAILED,
                                WifiP2pManager.ERROR);
                        // remain at this state.
                    }
                    break;
                case WifiMonitor.P2P_GROUP_STARTED_EVENT:
                    mGroup = (WifiP2pGroup) message.obj;
                    if (DBG) logd(getName() + " group started");

                    // We hit this scenario when a persistent group is reinvoked
                    if (mGroup.getNetworkId() == WifiP2pGroup.PERSISTENT_NET_ID) {
                        mAutonomousGroup = false;
                        deferMessage(message);
                        transitionTo(mGroupNegotiationState);
                    } else {
                        loge("Unexpected group creation, remove " + mGroup);
                        mWifiNative.p2pGroupRemove(mGroup.getInterface());
                    }
                    break;
                ///M: wfd sink, refactor Google code. Let event handled at P2pEnabledState  @{
                /*
                case WifiP2pManager.START_LISTEN:
                    if (DBG) logd(getName() + " start listen mode");
                    mWifiNative.p2pFlush();
                    if (mWifiNative.p2pExtListen(true, 500, 500)) {
                        replyToMessage(message, WifiP2pManager.START_LISTEN_SUCCEEDED);
                    } else {
                        replyToMessage(message, WifiP2pManager.START_LISTEN_FAILED);
                    }
                    break;
                case WifiP2pManager.STOP_LISTEN:
                    if (DBG) logd(getName() + " stop listen mode");
                    if (mWifiNative.p2pExtListen(false, 0, 0)) {
                        replyToMessage(message, WifiP2pManager.STOP_LISTEN_SUCCEEDED);
                    } else {
                        replyToMessage(message, WifiP2pManager.STOP_LISTEN_FAILED);
                    }
                    mWifiNative.p2pFlush();
                    break;
                */
                case WifiP2pManager.SET_CHANNEL:
                    Bundle p2pChannels = (Bundle) message.obj;
                    int lc = p2pChannels.getInt("lc", 0);
                    int oc = p2pChannels.getInt("oc", 0);
                    if (DBG) logd(getName() + " set listen and operating channel");
                    if (mWifiNative.p2pSetChannel(lc, oc)) {
                        replyToMessage(message, WifiP2pManager.SET_CHANNEL_SUCCEEDED);
                    } else {
                        replyToMessage(message, WifiP2pManager.SET_CHANNEL_FAILED);
                    }
                    break;
                case WifiP2pManager.INITIATOR_REPORT_NFC_HANDOVER:
                    String handoverSelect = null;

                    if (message.obj != null) {
                        handoverSelect = ((Bundle) message.obj)
                                .getString(WifiP2pManager.EXTRA_HANDOVER_MESSAGE);
                    }

                    if (handoverSelect != null
                            && mWifiNative.initiatorReportNfcHandover(handoverSelect)) {
                        replyToMessage(message, WifiP2pManager.REPORT_NFC_HANDOVER_SUCCEEDED);
                        transitionTo(mGroupCreatingState);
                    } else {
                        replyToMessage(message, WifiP2pManager.REPORT_NFC_HANDOVER_FAILED);
                    }
                    break;
                case WifiP2pManager.RESPONDER_REPORT_NFC_HANDOVER:
                    String handoverRequest = null;

                    if (message.obj != null) {
                        handoverRequest = ((Bundle) message.obj)
                                .getString(WifiP2pManager.EXTRA_HANDOVER_MESSAGE);
                    }

                    if (handoverRequest != null
                            && mWifiNative.responderReportNfcHandover(handoverRequest)) {
                        replyToMessage(message, WifiP2pManager.REPORT_NFC_HANDOVER_SUCCEEDED);
                        transitionTo(mGroupCreatingState);
                    } else {
                        replyToMessage(message, WifiP2pManager.REPORT_NFC_HANDOVER_FAILED);
                    }
                    break;
                ///M:@{
                case WifiP2pManager.START_FAST_CONNECT_AS_GO:
                    setFastConnectInfo(WifiP2pManager.FAST_CONNECT_AS_GO);
                    break;
                case WifiP2pManager.START_FAST_CONNECT_AS_GC:
                    mFastConnectInfo = (WifiP2pFastConnectInfo) message.obj;
                    mGcFastConnectEnaled = true;
                    setFastConnectInfo(WifiP2pManager.FAST_CONNECT_AS_GC);
                    /*M: ALPS01126713: to avoid doing FAST_CONNECT_FIND_GO_TIMED_OUT between START_FAST_CONNECT_AS_GO and FAST_CONNECT_AS_GC*/
                    mFastConnectFindGoTimeOutIndex = mFastConnectFindGoTimeOutIndex + 1;
                    break;
                case WifiP2pManager.FAST_CONNECT_AS_GO:
                    ///M: ALPS01000113: sync. Beam+ state @{
                    if (true == mBeamPlusStart) {
                        mWifiNative.p2pBeamPlusGO(1); //1: reserve start
                    }
                    ///@}
                    mAutonomousGroup = false;
                    mWifiNative.p2pGroupAdd(mFastConnectInfo.networkId);
                    transitionTo(mGroupNegotiationState);
                    break;
                case WifiP2pManager.FAST_CONNECT_AS_GC:
                    //mFoundGo is true means FAST_CONNECT_AS_GC is from P2P_DEVICE_FOUND_EVENT
                    logd("FAST_CONNECT_AS_GC, mFoundGo=" + mFoundGo
                            + " inMpeers=" + mPeers.containsPeer(mFastConnectInfo.deviceAddress));

                    /*M: ALPS00804938: mFoundGo is true but mPeers not contain fastConnect GO, then it needed scan again!*/
                    /*M: ALPS00918679: mFoundGo is false but mPeers contain fastConnect GO, then it still needed scan again!*/
                    if (!mFoundGo || !(mPeers.containsPeer(mFastConnectInfo.deviceAddress))) {
                        mFoundGoTimeOut = false;
                        mFoundGo = false;    ///M: ALPS00931786: need re-trigger WifiP2pManager.FAST_CONNECT_AS_GC when scan is done
                        sendMessageDelayed(WifiP2pManager.FAST_DISCOVER_PEERS, FAST_SCAN_INTERVAL_TIME_MS);
                        sendMessageDelayed(obtainMessage(FAST_CONNECT_FIND_GO_TIMED_OUT,
                                ++ mFastConnectFindGoTimeOutIndex, 0), FAST_CONNECT_FIND_GO_WAIT_TIME_MS);
                        logd("Send FAST_CONNECT_FIND_GO_TIMED_OUT message");
                        break;
                    }
                    int gc2 = mWifiNative.getGroupCapability(mFastConnectInfo.deviceAddress);
                    mPeers.updateGroupCapability(mFastConnectInfo.deviceAddress, gc2);
                    mSavedPeerConfig = new WifiP2pConfig();
                    mSavedPeerConfig.deviceAddress = mFastConnectInfo.deviceAddress;
                    mSavedPeerConfig.netId = mFastConnectInfo.networkId;
                    mWifiNative.p2pStopFind();

                    /*M: ALPS00804938: no matter how the desired fastConnect GO is GO/not GO, INVITE can work!*/
                    if (DBG) logd("Fast connect join Group, is peer GO? " + mPeers.isGroupOwner(mFastConnectInfo.deviceAddress));
                    mWifiNative.p2pReinvoke(mFastConnectInfo.networkId, mFastConnectInfo.deviceAddress);
                    transitionTo(mGroupNegotiationState);
                    break;
                case FAST_CONNECT_FIND_GO_TIMED_OUT:
                    /*M: this message means fast connect failed because of find GO too long,
                     *  but still not found*/
                    if (mFastConnectFindGoTimeOutIndex == message.arg1) {
                        if (DBG) logd("FAST CONNECT FIND GO timed out");
                        handleGroupCreationFailure();
                        mFoundGoTimeOut = true;
                        mFoundGo = false;
                    }
                    break;
                /** M: NFC Float II @{ */
                case M_CMD_NFC_HANDOVER_READ:
                    //M: same flow as WifiP2pManager.INITIATOR_REPORT_NFC_HANDOVER:
                    if (!mMtkWpsp2pnfcSupport) {
                        break;
                    }
                    if (message.arg1 == 1) { /// handover request
                        logd("handover request => initiator");
                        if (message.obj != null && mWifiNative.responderReportNfcHandover((String) message.obj)) {
                            transitionTo(mGroupCreatingState);
                        }

                    } else if (message.arg1 == 0) {  //handoverslelct
                        logd("handover request => responder");
                        if (message.obj != null && mWifiNative.initiatorReportNfcHandover((String) message.obj)) {
                            transitionTo(mGroupCreatingState);
                        }
                    }
                    break;
                case WifiMonitor.P2P_NFC_GO_INVITED_EVENT:
                    if (!mMtkWpsp2pnfcSupport) {
                        break;
                    }
                    String goInvitedStr = (String) message.obj;
                    logd(getName() + "P2P_NFC_GO_INVITED_EVENT, goInvitedStr:" + goInvitedStr);
                    String[] tokens = goInvitedStr.split(" ");
                    if (tokens.length == 2) {
                        String[] nameValue = tokens[1].split("=");
                        if (nameValue.length == 2) {
                            String deviceAddress = nameValue[1];
                            if (mWifiNative.p2pNfcConnectWithOob(deviceAddress, true)) {
                                transitionTo(mGroupNegotiationState);
                            }
                        }
                    }
                    break;
                /** @} */
                ///@}
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class GroupCreatingState extends State {
        @Override
        public void enter() {
            if (DBG) logd(getName());
            sendMessageDelayed(obtainMessage(GROUP_CREATING_TIMED_OUT,
                    ++mGroupCreatingTimeoutIndex, 0), GROUP_CREATING_WAIT_TIME_MS);
            ///M: ALPS01212893: for poor link, wifi p2p start Tx all traffic @{
            sendP2pTxBroadcast(true);
            ///@}

            /** M: NFC Float II @{ */
            if (mMtkWpsp2pnfcSupport) {
                 synchronized (mClientIp) {
                    logd("mClientIp clear");
                    mClientIp.clear();
                }
            }
            /** @} */

            /** M: enhance frequency conflict @{ */
            p2pOperFreq = -1;
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) logd(getName() + message.toString());
            boolean ret = HANDLED;
            switch (message.what) {
               case GROUP_CREATING_TIMED_OUT:
                    if (mGroupCreatingTimeoutIndex == message.arg1) {
                        if (DBG) logd("Group negotiation timed out");
                        handleGroupCreationFailure();
                        transitionTo(mInactiveState);
                    }
                    break;
                case WifiMonitor.P2P_DEVICE_LOST_EVENT:
                    WifiP2pDevice device = (WifiP2pDevice) message.obj;
                    if (!mSavedPeerConfig.deviceAddress.equals(device.deviceAddress)) {
                        if (DBG) {
                            logd("mSavedPeerConfig " + mSavedPeerConfig.deviceAddress +
                                "device " + device.deviceAddress);
                        }
                        // Do the regular device lost handling
                        ret = NOT_HANDLED;
                        break;
                    }
                    // Do nothing
                    if (DBG) logd("Add device to lost list " + device);
                    mPeersLostDuringConnection.updateSupplicantDetails(device);
                    break;
                case WifiP2pManager.DISCOVER_PEERS:
                    /* Discovery will break negotiation */
                    replyToMessage(message, WifiP2pManager.DISCOVER_PEERS_FAILED,
                            WifiP2pManager.BUSY);
                    break;
                case WifiP2pManager.CANCEL_CONNECT:
                    //Do a supplicant p2p_cancel which only cancels an ongoing
                    //group negotiation. This will fail for a pending provision
                    //discovery or for a pending user action, but at the framework
                    //level, we always treat cancel as succeeded and enter
                    //an inactive state
                    ///M: enhance cancelConnect() flow  @{
                    boolean success = false;
                    if (mWifiNative.p2pCancelConnect()) {
                        success = true;
                    } else if (mWifiNative.p2pGroupRemove(mInterface)) {
                        success = true;
                    }
                    handleGroupCreationFailure();
                    transitionTo(mInactiveState);
                    if (success) {
                        replyToMessage(message, WifiP2pManager.CANCEL_CONNECT_SUCCEEDED);
                    } else {
                        replyToMessage(message, WifiP2pManager.CANCEL_CONNECT_FAILED);
                    }
                    ///@}
                    break;
                case WifiMonitor.P2P_GO_NEGOTIATION_SUCCESS_EVENT:
                    // We hit this scenario when NFC handover is invoked.
                    mAutonomousGroup = false;
                    transitionTo(mGroupNegotiationState);
                    break;
                ///M:@{
                case WifiP2pManager.START_FAST_CONNECT_AS_GO:
                    sendMessage(WifiP2pManager.CANCEL_CONNECT);
                    //will turn to inactiveState, let inactiveState to process this message
                    mRestartFastConnectAsGo = true;
                    mGoFastConnectEnaled = true;
                    deferMessage(message);
                    break;
                case WifiP2pManager.START_FAST_CONNECT_AS_GC:
                    sendMessage(WifiP2pManager.CANCEL_CONNECT);
                    //will turn to inactiveState, let inactiveState to process this message
                    mRestartFastConnectAsGc = true;
                    mGcFastConnectEnaled = true;
                    deferMessage(message);
                    break;
                case WifiMonitor.P2P_DEVICE_FOUND_EVENT:
                    WifiP2pDevice peerDevice = (WifiP2pDevice) message.obj;
                    if (mThisDevice.deviceAddress.equals(peerDevice.deviceAddress)) break;
                    if (mSavedPeerConfig != null &&
                            mSavedPeerConfig.deviceAddress.equals(peerDevice.deviceAddress)) {
                        peerDevice.status = WifiP2pDevice.INVITED;
                    }
                    mPeers.update(peerDevice);
                    sendPeersChangedBroadcast();
                    break;
                //ALPS01497387
                case BLOCK_DISCOVERY:
                    if (DBG) logd("defer BLOCK_DISCOVERY@GroupCreatingState");
                    deferMessage(message);
                    break;
                ///@}
                default:
                    ret = NOT_HANDLED;
            }
            return ret;
        }
    }

    class UserAuthorizingNegotiationRequestState extends State {
        @Override
        public void enter() {
            if (DBG) logd(getName());
            ///M: wfd sink: dialog handling move to wfd framework  @{
            if (isDeviceBeWfdSink()) {
                sendP2pGOandGCRequestConnectBroadcast();
            } else
            ///@}
            notifyInvitationReceived();
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) logd(getName() + message.toString());
            boolean ret = HANDLED;
            switch (message.what) {
                case PEER_CONNECTION_USER_ACCEPT:
                ///M: wfd sink @{
                case WifiP2pManager.PEER_CONNECTION_USER_ACCEPT_FROM_OUTER:
                    if (isDeviceBeWfdSink()) {
                        mSavedPeerConfig.groupOwnerIntent = (int) message.arg1;
                        mSavedPeerConfig.netId = WifiP2pGroup.TEMPORARY_NET_ID;
                        ///M: wfd sink, support show PIN passively  @{
                        if (WpsInfo.DISPLAY == mSavedPeerConfig.wps.setup) {
                            notifyInvitationSent(mSavedPeerConfig.wps.pin, mSavedPeerConfig.deviceAddress);
                        }
                        ///@}
                    }
                    if (DBG) logd("User accept negotiation: mSavedPeerConfig = " + mSavedPeerConfig);
                ///@}
                    mWifiNative.p2pStopFind();
                    p2pConnectWithPinDisplay(mSavedPeerConfig);
                    mPeers.updateStatus(mSavedPeerConfig.deviceAddress, WifiP2pDevice.INVITED);
                    sendPeersChangedBroadcast();
                    transitionTo(mGroupNegotiationState);
                   break;
                case PEER_CONNECTION_USER_REJECT:
                ///M: wfd sink @{
                case WifiP2pManager.PEER_CONNECTION_USER_REJECT_FROM_OUTER:
                ///@}
                    if (DBG) logd("User rejected negotiation " + mSavedPeerConfig);
                    transitionTo(mInactiveState);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return ret;
        }

        @Override
        public void exit() {
            //TODO: dismiss dialog if not already done
        }
    }

    class UserAuthorizingInviteRequestState extends State {
        @Override
        public void enter() {
            if (DBG) logd(getName());
            notifyInvitationReceived();
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) logd(getName() + message.toString());
            boolean ret = HANDLED;
            switch (message.what) {
                case PEER_CONNECTION_USER_ACCEPT:
                ///M: wfd sink @{
                case WifiP2pManager.PEER_CONNECTION_USER_ACCEPT_FROM_OUTER:
                ///@}
                    mWifiNative.p2pStopFind();
                    if (!reinvokePersistentGroup(mSavedPeerConfig)) {
                        // Do negotiation when persistence fails
                        p2pConnectWithPinDisplay(mSavedPeerConfig);
                    }
                    mPeers.updateStatus(mSavedPeerConfig.deviceAddress, WifiP2pDevice.INVITED);
                    sendPeersChangedBroadcast();
                    transitionTo(mGroupNegotiationState);
                    break;
                case PEER_CONNECTION_USER_REJECT:
                ///M: wfd sink @{
                case WifiP2pManager.PEER_CONNECTION_USER_REJECT_FROM_OUTER:
                ///@}
                    if (DBG) logd("User rejected invitation " + mSavedPeerConfig);
                    transitionTo(mInactiveState);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return ret;
        }

        @Override
        public void exit() {
            //TODO: dismiss dialog if not already done
        }
    }

    class ProvisionDiscoveryState extends State {
        @Override
        public void enter() {
            if (DBG) logd(getName());
            mWifiNative.p2pProvisionDiscovery(mSavedPeerConfig);
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) logd(getName() + message.toString());
            WifiP2pProvDiscEvent provDisc;
            WifiP2pDevice device;
            switch (message.what) {
                case WifiMonitor.P2P_PROV_DISC_PBC_RSP_EVENT:
                    provDisc = (WifiP2pProvDiscEvent) message.obj;
                    device = provDisc.device;
                    if (!device.deviceAddress.equals(mSavedPeerConfig.deviceAddress)) break;

                    if (mSavedPeerConfig.wps.setup == WpsInfo.PBC) {
                        if (DBG) logd("Found a match " + mSavedPeerConfig);
                        ///M: do stop scan after provision @{
                        mWifiNative.p2pStopFind();
                        ///@}
                        p2pConnectWithPinDisplay(mSavedPeerConfig);
                        transitionTo(mGroupNegotiationState);
                    }
                    break;
                case WifiMonitor.P2P_PROV_DISC_ENTER_PIN_EVENT:
                    provDisc = (WifiP2pProvDiscEvent) message.obj;
                    device = provDisc.device;
                    if (!device.deviceAddress.equals(mSavedPeerConfig.deviceAddress)) break;

                    if (mSavedPeerConfig.wps.setup == WpsInfo.KEYPAD) {
                        if (DBG) logd("Found a match " + mSavedPeerConfig);
                        /* we already have the pin */
                        if (!TextUtils.isEmpty(mSavedPeerConfig.wps.pin)) {
                            ///M: do stop scan after provision @{
                            mWifiNative.p2pStopFind();
                            ///@}
                            p2pConnectWithPinDisplay(mSavedPeerConfig);
                            transitionTo(mGroupNegotiationState);
                        } else {
                            mJoinExistingGroup = false;
                            transitionTo(mUserAuthorizingNegotiationRequestState);
                        }
                    }
                    break;
                case WifiMonitor.P2P_PROV_DISC_SHOW_PIN_EVENT:
                    provDisc = (WifiP2pProvDiscEvent) message.obj;
                    device = provDisc.device;
                    if (!device.deviceAddress.equals(mSavedPeerConfig.deviceAddress)) break;

                    if (mSavedPeerConfig.wps.setup == WpsInfo.DISPLAY) {
                        if (DBG) logd("Found a match " + mSavedPeerConfig);
                        mSavedPeerConfig.wps.pin = provDisc.pin;
                        ///M: do stop scan after provision @{
                        mWifiNative.p2pStopFind();
                        ///@}
                        p2pConnectWithPinDisplay(mSavedPeerConfig);
                        notifyInvitationSent(provDisc.pin, device.deviceAddress);
                        transitionTo(mGroupNegotiationState);
                    }
                    break;
                case WifiMonitor.P2P_PROV_DISC_FAILURE_EVENT:
                    loge("provision discovery failed");
                    handleGroupCreationFailure();
                    transitionTo(mInactiveState);
                    break;
                ///M:@{
                case WifiP2pManager.CANCEL_CONNECT:
                    //Do a supplicant p2p_cancel which only cancels an ongoing
                    //group negotiation. This will fail for a pending provision
                    //discovery or for a pending user action, so try Remove group
                    boolean success = mWifiNative.p2pGroupRemove(mInterface);
                    handleGroupCreationFailure();
                    transitionTo(mInactiveState);
                    replyToMessage(message, success == true
                        ? WifiP2pManager.CANCEL_CONNECT_SUCCEEDED : WifiP2pManager.CANCEL_CONNECT_FAILED);
                    break;
                ///@}
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class GroupNegotiationState extends State {
        @Override
        public void enter() {
            if (DBG) logd(getName());
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) logd(getName() + message.toString());
            switch (message.what) {
                // We ignore these right now, since we get a GROUP_STARTED notification
                // afterwards
                case WifiMonitor.P2P_GO_NEGOTIATION_SUCCESS_EVENT:
                case WifiMonitor.P2P_GROUP_FORMATION_SUCCESS_EVENT:
                    if (DBG) logd(getName() + " go success");
                    break;
                case WifiMonitor.P2P_GROUP_STARTED_EVENT:
                    mGroup = (WifiP2pGroup) message.obj;
                    if (DBG) logd(getName() + " group started");

                    ///M: ALPS01443292: enable/disable IPv6 on demand  @{
                    try {
                        mNwService.enableIpv6(mInterface);
                    } catch (RemoteException re) {
                        loge(getName() + " RemoteException: " + re);
                    } catch (IllegalStateException ie) {
                        loge(getName() + " IllegalStateException: " + ie);
                    }

                    ///@}
                    if (mGroup.getNetworkId() == WifiP2pGroup.PERSISTENT_NET_ID) {
                        /*
                         * update cache information and set network id to mGroup.
                         */
                        updatePersistentNetworks(NO_RELOAD);
                        String devAddr = mGroup.getOwner().deviceAddress;
                        mGroup.setNetworkId(mGroups.getNetworkId(devAddr,
                                mGroup.getNetworkName()));

                        ////M: ALPS01593529: no p2p_invite in wfd source case, don't keep persistent group @{
                        ////if ( WifiP2pManager.MIRACAST_SOURCE == mMiracastMode ) {
                        ////    logd("wfd source case: Network ID = " + mGroup.getNetworkId());
                        ////    mGroups.remove(mGroup.getNetworkId());
                        ////}
                        ///@}
                    }

                    if (mGroup.isGroupOwner()) {
                        /* Setting an idle time out on GO causes issues with certain scenarios
                         * on clients where it can be off-channel for longer and with the power
                         * save modes used.
                         *
                         * TODO: Verify multi-channel scenarios and supplicant behavior are
                         * better before adding a time out in future
                         */
                        //Set group idle timeout of 10 sec, to avoid GO beaconing incase of any
                        //failure during 4-way Handshake.
                        if (!mAutonomousGroup) {
                            mWifiNative.setP2pGroupIdle(mGroup.getInterface(), GROUP_IDLE_TIME_S);
                        }
                        startDhcpServer(mGroup.getInterface());
                    } else {
                        ///M: fast connect @{
                        if (mGcFastConnectEnaled) {
                            String gcIp = mFastConnectInfo.gcIpAddress;
                            String intf = mGroup.getInterface();
                            try {
                                InterfaceConfiguration ifcg = mNwService.getInterfaceConfig(intf);
                                ifcg.setLinkAddress(new LinkAddress(NetworkUtils.numericToInetAddress(gcIp), 24));
                                ifcg.setInterfaceUp();
                                mNwService.setInterfaceConfig(intf, ifcg);
                                //do addInterfaceToLocalNetwork()
                                StaticIpConfiguration staticIpConfiguration = new StaticIpConfiguration();
                                staticIpConfiguration.ipAddress = new LinkAddress(NetworkUtils.numericToInetAddress(mFastConnectInfo.gcIpAddress), 24);
                                logd("fast connect feature, do addInterfaceToLocalNetwork() for Android L version");
                                mNwService.addInterfaceToLocalNetwork(intf,
                                                                      staticIpConfiguration.getRoutes(intf));
                            }  catch (RemoteException re) {
                                loge("fast connect feature, Error configuring interface " + intf + ", :" + re);
                            }
                        } else {
                            /** M: NFC Float II @{ */
                            logd("mTmpGcIp:" + mTmpGcIp + ", mTmpGoIp:" + mTmpGoIp);
                            if (mMtkWpsp2pnfcSupport && mTmpGcIp != null && mTmpGoIp != null) {
                                try {
                                    InterfaceConfiguration ifcg = mNwService.getInterfaceConfig(mGroup.getInterface());
                                    ifcg.setLinkAddress(new LinkAddress(NetworkUtils.numericToInetAddress(mTmpGcIp), 24));
                                    ifcg.setInterfaceUp();
                                    mNwService.setInterfaceConfig(mGroup.getInterface(), ifcg);
                                    //do addInterfaceToLocalNetwork()
                                    StaticIpConfiguration staticIpConfiguration = new StaticIpConfiguration();
                                    staticIpConfiguration.ipAddress = new LinkAddress(NetworkUtils.numericToInetAddress(mTmpGcIp), 24);
                                    logd("NFC Float II feature, do addInterfaceToLocalNetwork() for Android L version");
                                    mNwService.addInterfaceToLocalNetwork(mGroup.getInterface(),
                                                                          staticIpConfiguration.getRoutes(mGroup.getInterface()));
                                } catch (Exception e) {
                                    loge("NFC Float II feature, Error configuring interface " + mGroup.getInterface() + ", :" + e);
                                }
                            } else {
                            /** @} */
                                mWifiNative.setP2pGroupIdle(mGroup.getInterface(), GROUP_IDLE_TIME_S);
                                mDhcpStateMachine = DhcpStateMachine.makeDhcpStateMachine(mContext,
                                        P2pStateMachine.this, mGroup.getInterface());
                                // TODO: We should use DHCP state machine PRE message like WifiStateMachine
                                mDhcpStateMachine.sendMessage(DhcpStateMachine.CMD_START_DHCP);
                            }
                        }
                        ///@}
                        ///M: use MTK power saving command @{
                        //mWifiNative.setP2pPowerSave(mGroup.getInterface(), false);
                        mWifiNative.setP2pPowerSaveMtk(mGroup.getInterface(), P2P_FAST_PS);
                        ///@}
                        WifiP2pDevice groupOwner = mGroup.getOwner();
                        WifiP2pDevice peer = mPeers.get(groupOwner.deviceAddress);
                        if (peer != null) {
                            // update group owner details with peer details found at discovery
                            groupOwner.updateSupplicantDetails(peer);
                            mPeers.updateStatus(groupOwner.deviceAddress, WifiP2pDevice.CONNECTED);
                            sendPeersChangedBroadcast();
                        } else {
                            // A supplicant bug can lead to reporting an invalid
                            // group owner address (all zeroes) at times. Avoid a
                            // crash, but continue group creation since it is not
                            // essential.
                            logw("Unknown group owner " + groupOwner);
                        }
                    }
                    transitionTo(mGroupCreatedState);
                    break;
                case WifiMonitor.P2P_GO_NEGOTIATION_FAILURE_EVENT:
                    /** M: enhance frequency conflict @{ */
                    if (0 != message.arg1)    p2pOperFreq = message.arg1;
                    P2pStatus status = (P2pStatus) message.obj;
                    loge("go negotiation failed, status = " + status + "\tp2pOperFreq = " + p2pOperFreq);
                    if (status == P2pStatus.NO_COMMON_CHANNEL) {
                        transitionTo(mFrequencyConflictState);
                        break;
                    }
                    /* continue with group removal handling */
                case WifiMonitor.P2P_GROUP_REMOVED_EVENT:
                    if (DBG) logd(getName() + " go failure");
                    handleGroupCreationFailure();
                    transitionTo(mInactiveState);
                    break;
                // A group formation failure is always followed by
                // a group removed event. Flushing things at group formation
                // failure causes supplicant issues. Ignore right now.
                case WifiMonitor.P2P_GROUP_FORMATION_FAILURE_EVENT:
                    /** M: enhance frequency conflict @{ */
                    if (0 != message.arg1)    p2pOperFreq = message.arg1;
                    status = (P2pStatus) message.obj;
                    loge("group formation failed, status = " + status + "\tp2pOperFreq = " + p2pOperFreq);
                    if (status == P2pStatus.NO_COMMON_CHANNEL) {
                        transitionTo(mFrequencyConflictState);
                        break;
                    }
                    break;
                case WifiMonitor.P2P_INVITATION_RESULT_EVENT:
                    status = (P2pStatus)message.obj;
                    if (status == P2pStatus.SUCCESS) {
                        // invocation was succeeded.
                        // wait P2P_GROUP_STARTED_EVENT.
                        break;
                    }
                    loge("Invitation result " + status);
                    if (status == P2pStatus.UNKNOWN_P2P_GROUP) {
                        // target device has already removed the credential.
                        // So, remove this credential accordingly.
                        int netId = mSavedPeerConfig.netId;
                        if (netId >= 0) {
                            if (DBG) logd("Remove unknown client from the list");
                            removeClientFromList(netId, mSavedPeerConfig.deviceAddress, true);
                        }

                        // Reinvocation has failed, try group negotiation
                        mSavedPeerConfig.netId = WifiP2pGroup.PERSISTENT_NET_ID;
                        p2pConnectWithPinDisplay(mSavedPeerConfig);
                    } else if (status == P2pStatus.INFORMATION_IS_CURRENTLY_UNAVAILABLE) {

                        // Devices setting persistent_reconnect to 0 in wpa_supplicant
                        // always defer the invocation request and return
                        // "information is currently unable" error.
                        // So, try another way to connect for interoperability.
                        mSavedPeerConfig.netId = WifiP2pGroup.PERSISTENT_NET_ID;
                        p2pConnectWithPinDisplay(mSavedPeerConfig);
                    } else if (status == P2pStatus.NO_COMMON_CHANNEL) {
                        /** M: enhance frequency conflict @{ */
                        if (0 != message.arg1)    p2pOperFreq = message.arg1;
                        logd("Invitation p2pOperFreq = " + p2pOperFreq);
                        transitionTo(mFrequencyConflictState);
                    } else {
                        handleGroupCreationFailure();
                        transitionTo(mInactiveState);
                    }
                    break;
                ///M:@{
                case WifiMonitor.WPS_OVERLAP_EVENT:
                    Toast.makeText(mContext, com.mediatek.internal.R.string.wifi_wps_failed_overlap
                        , Toast.LENGTH_SHORT).show();
                    break;
                ///@}
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class FrequencyConflictState extends State {
        private AlertDialog mFrequencyConflictDialog;
        @Override
        public void enter() {
            if (DBG) logd(getName());

            /** M: enhance frequency conflict @{ */
            if (!SystemProperties.get("ro.mtk_wifi_mcc_support").equals("1") &&
                    1 == mMiracastMode) {
                sendP2pOPChannelBroadcast();
                return;
            } else if (2 == mMiracastMode) {
                logd("[wfd sink] channel conflict, disconnect legacy wifi automatically");
                sendP2pFreqConflictBroadcast();
                sendMessage(DROP_WIFI_USER_ACCEPT);
                return;
            }
            /** @} */
            notifyFrequencyConflict();
        }

        private void notifyFrequencyConflict() {
            logd("Notify frequency conflict");
            Resources r = Resources.getSystem();

            AlertDialog dialog = new AlertDialog.Builder(mContext)
                .setMessage(r.getString(R.string.wifi_p2p_frequency_conflict_message,
                        getDeviceName(mSavedPeerConfig.deviceAddress)))
                .setPositiveButton(r.getString(R.string.dlg_ok), new OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            sendMessage(DROP_WIFI_USER_ACCEPT);
                        }
                    })
                .setNegativeButton(r.getString(R.string.decline), new OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            sendMessage(DROP_WIFI_USER_REJECT);
                        }
                    })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        @Override
                        public void onCancel(DialogInterface arg0) {
                            sendMessage(DROP_WIFI_USER_REJECT);
                        }
                    })
                .create();

            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
            dialog.show();
            mFrequencyConflictDialog = dialog;
        }

        /** M: enhance frequency conflict @{ */
        private void notifyFrequencyConflictEx() {
            logd("Notify frequency conflict enhancement! p2pOperFreq = " + p2pOperFreq);
            Resources r = Resources.getSystem();
            String localFreq = "";
            
            if (p2pOperFreq > 0) {
                if (p2pOperFreq < 5000) {
                    localFreq = "2.4G band-" + new String("" + p2pOperFreq) + " MHz";
                } else {
                    localFreq = "5G band-" + new String("" + p2pOperFreq) + " MHz";
                }
            } else {
                loge(getName() + " in-valid OP channel: " + p2pOperFreq);
            }

            AlertDialog dialog = new AlertDialog.Builder(mContext)
                .setMessage(r.getString(com.mediatek.internal.R.string.wifi_p2p_frequency_conflict_message,
                        getDeviceName(mSavedPeerConfig.deviceAddress),
                        localFreq))
                .setPositiveButton(r.getString(R.string.dlg_ok), new OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            sendMessage(DROP_WIFI_USER_ACCEPT);
                        }
                    })
                .setNegativeButton(r.getString(R.string.decline), new OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            sendMessage(DROP_WIFI_USER_REJECT);
                        }
                    })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        @Override
                        public void onCancel(DialogInterface arg0) {
                            sendMessage(DROP_WIFI_USER_REJECT);
                        }
                    })
                .create();

            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
            dialog.show();
            mFrequencyConflictDialog = dialog;
        }
        /** @} */

        @Override
        public boolean processMessage(Message message) {
            if (DBG) logd(getName() + message.toString());
            switch (message.what) {
                case WifiMonitor.P2P_GO_NEGOTIATION_SUCCESS_EVENT:
                case WifiMonitor.P2P_GROUP_FORMATION_SUCCESS_EVENT:
                    loge(getName() + "group sucess during freq conflict!");
                    break;
                case WifiMonitor.P2P_GROUP_STARTED_EVENT:
                    loge(getName() + "group started after freq conflict, handle anyway");
                    deferMessage(message);
                    transitionTo(mGroupNegotiationState);
                    break;
                case WifiMonitor.P2P_GO_NEGOTIATION_FAILURE_EVENT:
                case WifiMonitor.P2P_GROUP_REMOVED_EVENT:
                case WifiMonitor.P2P_GROUP_FORMATION_FAILURE_EVENT:
                    // Ignore failures since we retry again
                    break;
                case DROP_WIFI_USER_REJECT:
                    ///M: ALPS01472489: notify user decline channel conflict  @{
                    mGroupRemoveReason = P2pStatus.MTK_EXPAND_02;
                    ///@}
                    // User rejected dropping wifi in favour of p2p
                    handleGroupCreationFailure();
                    transitionTo(mInactiveState);
                    break;
                case DROP_WIFI_USER_ACCEPT:
                    // User accepted dropping wifi in favour of p2p
                    mWifiChannel.sendMessage(WifiP2pServiceImpl.DISCONNECT_WIFI_REQUEST, 1);
                    mTemporarilyDisconnectedWifi = true;
                    break;
                case DISCONNECT_WIFI_RESPONSE:
                    // Got a response from wifistatemachine, retry p2p
                    if (DBG) logd(getName() + "Wifi disconnected, retry p2p");
                    transitionTo(mInactiveState);
                    ///M:@{
                    if (mGcFastConnectEnaled) {
                        sendMessage(WifiP2pManager.FAST_CONNECT_AS_GC);
                    } else {
                        sendMessage(WifiP2pManager.CONNECT, mSavedPeerConfig);
                    }
                    ///@}
                    break;
                /** M: enhance frequency conflict @{ */
                case WifiP2pManager.FREQ_CONFLICT_EX_RESULT:
                    int accept = (int)message.arg1;
                    if (DBG) logd(getName() + " frequency confliect enhancement decision: " + accept +
                        ", and p2pOperFreq = " + p2pOperFreq);
                    if (1 == accept) {
                        transitionTo(mInactiveState);
                        mSavedPeerConfig.setPreferOperFreq(p2pOperFreq);
                        sendMessage(WifiP2pManager.CONNECT, mSavedPeerConfig);
                    } else {
                        notifyFrequencyConflictEx();
                    }
                    break;
                /** @} */
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        public void exit() {
            if (mFrequencyConflictDialog != null) mFrequencyConflictDialog.dismiss();
        }
    }

    class GroupCreatedState extends State {
        @Override
        public void enter() {
            if (DBG) logd(getName());
            // Once connected, peer config details are invalid
            mSavedPeerConfig.invalidate();
            mNetworkInfo.setDetailedState(NetworkInfo.DetailedState.CONNECTED, null, null);

            updateThisDevice(WifiP2pDevice.CONNECTED);

            //DHCP server has already been started if I am a group owner
            if (mGroup.isGroupOwner()) {
                setWifiP2pInfoOnGroupFormation(NetworkUtils.numericToInetAddress(SERVER_ADDRESS));
            ///M:@{
            } else if (mGcFastConnectEnaled) {
                setWifiP2pInfoOnGroupFormation(NetworkUtils.numericToInetAddress(SERVER_ADDRESS));
                sendP2pConnectionChangedBroadcast();
            } else {
                ///M: wfd sink@GC, stop listening to avoid packet lost  @{
                if (isDeviceBeWfdSink_InSession()) {
                    if (DBG) logd(getName() + " [wfd sink] stop scan@GC, to avoid packet lost");
                    mWifiNative.p2pStopFind();
                }
                ///@}
                /** M: NFC Float II @{ */
                if (mMtkWpsp2pnfcSupport) {
                    if (mTmpGoIp != null) {
                        logd("Set GO IP!");
                        setWifiP2pInfoOnGroupFormation(NetworkUtils.numericToInetAddress(mTmpGoIp));
                        sendP2pConnectionChangedBroadcast();
                        //Turn on power save on client
                        mWifiNative.setP2pPowerSave(mGroup.getInterface(), true);
                    }
                }
                /** @} */
            }
            ///@}

            // In case of a negotiation group, connection changed is sent
            // after a client joins. For autonomous, send now
            if (mAutonomousGroup) {
                sendP2pConnectionChangedBroadcast();
            }
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) logd(getName() + message.toString());
            switch (message.what) {
                case WifiMonitor.AP_STA_CONNECTED_EVENT:
                    WifiP2pDevice device = (WifiP2pDevice) message.obj;
                    String deviceAddress = device.deviceAddress;
                    // Clear timeout that was set when group was started.
                    mWifiNative.setP2pGroupIdle(mGroup.getInterface(), 0);
                    if (deviceAddress != null) {
                        if (mPeers.get(deviceAddress) != null) {
                            mGroup.addClient(mPeers.get(deviceAddress));
                        } else {
                            ///M: ALPS00712601 + ALPS00741190: GC connected but not in GO UI @{
                            //mGroup.addClient(deviceAddress);
                            device = p2pGoGetSta(device, deviceAddress);
                            mGroup.addClient(device);
                            mPeers.update(device);
                            ///@}
                        }
                        mPeers.updateStatus(deviceAddress, WifiP2pDevice.CONNECTED);
                        if (DBG) logd(getName() + " ap sta connected, mTmpGcIp:" + mTmpGcIp);
                        sendPeersChangedBroadcast();
                        ///M: ALPS01000113: sync. Beam+ state @{
                        if (true == mBeamPlusStart) {
                            mWifiNative.p2pBeamPlusGO(0); //0: reserve end
                        }
                        ///@}
                        ///M: wfd sink@GO, stop listening to avoid packet lost  @{
                        if (isDeviceBeWfdSink_InSession()) {
                            if (DBG) logd(getName() + " [wfd sink] stop scan@GO, to avoid packet lost");
                            mWifiNative.p2pStopFind();
                        }
                        ///@}

                        /** M: NFC Float II @{ */
                        if (mMtkWpsp2pnfcSupport) {
                            logd("mTmpGcIp=" + mTmpGcIp + " mPeers:" + mPeers);
                            if (mTmpGcIp != null) {
                                synchronized (mClientIp) {
                                    mClientIp.put(deviceAddress, mTmpGcIp);
                                    logd("mClientIp=" + mClientIp);
                                }
                            }
                        }
                        /** @} */
                    } else {
                        loge("Connect on null device address, ignore");
                    }
                    sendP2pConnectionChangedBroadcast();
                    break;
                case WifiMonitor.AP_STA_DISCONNECTED_EVENT:
                    device = (WifiP2pDevice) message.obj;
                    deviceAddress = device.deviceAddress;
                    if (deviceAddress != null) {
                        mPeers.updateStatus(deviceAddress, WifiP2pDevice.AVAILABLE);
                        if (mGroup.removeClient(deviceAddress)) {
                            if (DBG) logd("Removed client " + deviceAddress);
                            if (!mAutonomousGroup && mGroup.isClientListEmpty()) {
                                logd("Client list empty, remove non-persistent p2p group");
                                mWifiNative.p2pGroupRemove(mGroup.getInterface());
                                // We end up sending connection changed broadcast
                                // when this happens at exit()
                            } else {
                                // Notify when a client disconnects from group
                                sendP2pConnectionChangedBroadcast();
                            }
                        } else {
                            if (DBG) logd("Failed to remove client " + deviceAddress);
                            for (WifiP2pDevice c : mGroup.getClientList()) {
                                if (DBG) logd("client " + c.deviceAddress);
                            }
                        }
                        sendPeersChangedBroadcast();
                        if (DBG) logd(getName() + " ap sta disconnected");

                        /** M: NFC Float II @{ */
                        if (mMtkWpsp2pnfcSupport) {
                            synchronized (mClientIp) {
                                mClientIp.remove(deviceAddress);
                            }
                        }
                        /** @} */
                    } else {
                        loge("Disconnect on unknown device: " + device);
                    }
                    break;
                case DhcpStateMachine.CMD_POST_DHCP_ACTION:
                    DhcpResults dhcpResults = (DhcpResults) message.obj;
                    if (message.arg1 == DhcpStateMachine.DHCP_SUCCESS &&
                            dhcpResults != null) {
                        if (DBG) logd("DhcpResults: " + dhcpResults);
                        setWifiP2pInfoOnGroupFormation(dhcpResults.serverAddress);
                        sendP2pConnectionChangedBroadcast();
                        //Turn on power save on client
                        ///M: GC power saving had doing at GroupNegotiationState P2P_GROUP_STARTED_EVENT @{
                        //mWifiNative.setP2pPowerSave(mGroup.getInterface(), true);
                        ///@}
                        try {
                            String iface = mGroup.getInterface();
                            mNwService.addInterfaceToLocalNetwork(iface,
                                                                  dhcpResults.getRoutes(iface));
                        } catch (RemoteException e) {
                            loge("Failed to add iface to local network " + e);
                        } catch (IllegalStateException ie) {
                            loge("Failed to add iface to local network: IllegalStateException=" + ie);
                        }
                        ///M: for getPeerIpAddress(), remove "/" prefix  @{
                        if (null != dhcpResults.serverAddress &&
                                dhcpResults.serverAddress.toString().startsWith("/")) {
                            mGroup.getOwner().deviceIP = dhcpResults.serverAddress.toString().substring(1);
                        } else {
                            mGroup.getOwner().deviceIP = "" + dhcpResults.serverAddress;
                        }
                        ///@}
                    } else {
                        loge("DHCP failed");
                        mWifiNative.p2pGroupRemove(mGroup.getInterface());
                    }
                    break;
                case WifiP2pManager.REMOVE_GROUP:
                    if (DBG) logd(getName() + " remove group");
                    if (mWifiNative.p2pGroupRemove(mGroup.getInterface())) {
                        transitionTo(mOngoingGroupRemovalState);
                        replyToMessage(message, WifiP2pManager.REMOVE_GROUP_SUCCEEDED);
                    } else {
                        handleGroupRemoved();
                        transitionTo(mInactiveState);
                        replyToMessage(message, WifiP2pManager.REMOVE_GROUP_FAILED,
                                WifiP2pManager.ERROR);
                    }
                    break;
                /* We do not listen to NETWORK_DISCONNECTION_EVENT for group removal
                 * handling since supplicant actually tries to reconnect after a temporary
                 * disconnect until group idle time out. Eventually, a group removal event
                 * will come when group has been removed.
                 *
                 * When there are connectivity issues during temporary disconnect, the application
                 * will also just remove the group.
                 *
                 * Treating network disconnection as group removal causes race conditions since
                 * supplicant would still maintain the group at that stage.
                 */
                case WifiMonitor.P2P_GROUP_REMOVED_EVENT:
                    ///M:@{
                    /** M: enhance frequency conflict @{ */
                    if (0 != message.arg1)    p2pOperFreq = message.arg1;
                    mGroupRemoveReason = (P2pStatus) message.obj;
                    if (DBG) logd(getName() + " group removed, reason: " + mGroupRemoveReason +
                        ", p2pOperFreq: " + p2pOperFreq);
                    ///@}
                    handleGroupRemoved();
                    transitionTo(mInactiveState);
                    break;
                case WifiMonitor.P2P_DEVICE_LOST_EVENT:
                    device = (WifiP2pDevice) message.obj;
                    //Device loss for a connected device indicates it is not in discovery any more
                    if (mGroup.contains(device)) {
                        if (DBG) logd("Add device to lost list " + device);
                        mPeersLostDuringConnection.updateSupplicantDetails(device);
                        return HANDLED;
                    }
                    // Do the regular device lost handling
                    return NOT_HANDLED;
                case WifiStateMachine.CMD_DISABLE_P2P_REQ:
                    sendMessage(WifiP2pManager.REMOVE_GROUP);
                    deferMessage(message);
                    break;
                    // This allows any client to join the GO during the
                    // WPS window
                case WifiP2pManager.START_WPS:
                    WpsInfo wps = (WpsInfo) message.obj;
                    if (wps == null) {
                        replyToMessage(message, WifiP2pManager.START_WPS_FAILED);
                        break;
                    }
                    boolean ret = true;
                    if (wps.setup == WpsInfo.PBC) {
                        ret = mWifiNative.startWpsPbc(mGroup.getInterface(), null);
                    } else {
                        if (wps.pin == null) {
                            String pin = mWifiNative.startWpsPinDisplay(mGroup.getInterface());
                            try {
                                Integer.parseInt(pin);
                                notifyInvitationSent(pin, "any");
                            } catch (NumberFormatException ignore) {
                                ret = false;
                            }
                        } else {
                            ret = mWifiNative.startWpsPinKeypad(mGroup.getInterface(),
                                    wps.pin);
                        }
                    }
                    replyToMessage(message, ret ? WifiP2pManager.START_WPS_SUCCEEDED :
                            WifiP2pManager.START_WPS_FAILED);
                    break;
                case WifiP2pManager.CONNECT:
                    ///M: add for ALPS00489161  @{
                    mGroupCreatedEntirely = true;
                    ///@}
                    WifiP2pConfig config = (WifiP2pConfig) message.obj;
                    if (isConfigInvalid(config)) {
                        loge("Dropping connect requeset " + config);
                        replyToMessage(message, WifiP2pManager.CONNECT_FAILED);
                        break;
                    }
                    logd("Inviting device : " + config.deviceAddress);
                    mSavedPeerConfig = config;
                    if (mWifiNative.p2pInvite(mGroup, config.deviceAddress)) {
                        mPeers.updateStatus(config.deviceAddress, WifiP2pDevice.INVITED);
                        sendPeersChangedBroadcast();
                        replyToMessage(message, WifiP2pManager.CONNECT_SUCCEEDED);
                    } else {
                        replyToMessage(message, WifiP2pManager.CONNECT_FAILED,
                                WifiP2pManager.ERROR);
                    }
                    // TODO: figure out updating the status to declined when invitation is rejected
                    break;
                case WifiMonitor.P2P_INVITATION_RESULT_EVENT:
                    P2pStatus status = (P2pStatus)message.obj;
                    logd("===> INVITATION RESULT EVENT : " + status +
                        ",\tis GO ? : " + mGroup.getOwner().deviceAddress.equals(mThisDevice.deviceAddress));
                    ///M: ALPS00609781: remove 3rd phone on gc UI when 3rd is invited @{
                    boolean inviteDone = false;
                    if (status == P2pStatus.SUCCESS) {
                        // invocation was succeeded.
                        //break;
                        inviteDone = true;
                    }
                    loge("Invitation result " + status +
                        ",\tis GO ? : " + mGroup.getOwner().deviceAddress.equals(mThisDevice.deviceAddress));
                    if (status == P2pStatus.UNKNOWN_P2P_GROUP) {
                        // target device has already removed the credential.
                        // So, remove this credential accordingly.
                        int netId = mGroup.getNetworkId();
                        if (netId >= 0) {
                            if (DBG) logd("Remove unknown client from the list");
                            if (!removeClientFromList(netId,
                                    mSavedPeerConfig.deviceAddress, false)) {
                                // not found the client on the list
                                loge("Already removed the client, ignore");
                                break;
                            }
                            // try invitation.
                            sendMessage(WifiP2pManager.CONNECT, mSavedPeerConfig);
                        }
                    } else {
                        inviteDone = true;
                    }

                    if (true == inviteDone &&
                        !mGroup.getOwner().deviceAddress.equals(mThisDevice.deviceAddress)) {
                        if (mPeers.remove(mPeers.get(mSavedPeerConfig.deviceAddress))) sendPeersChangedBroadcast();
                    }
                    ///@}
                    break;
                case WifiMonitor.P2P_PROV_DISC_PBC_REQ_EVENT:
                case WifiMonitor.P2P_PROV_DISC_ENTER_PIN_EVENT:
                case WifiMonitor.P2P_PROV_DISC_SHOW_PIN_EVENT:
                    ///M: add for ALPS00489161  @{
                    mGroupCreatedEntirely = true;
                    ///@}
                    WifiP2pProvDiscEvent provDisc = (WifiP2pProvDiscEvent) message.obj;
                    mSavedPeerConfig = new WifiP2pConfig();
                    mSavedPeerConfig.deviceAddress = provDisc.device.deviceAddress;
                    if (message.what == WifiMonitor.P2P_PROV_DISC_ENTER_PIN_EVENT) {
                        mSavedPeerConfig.wps.setup = WpsInfo.KEYPAD;
                    } else if (message.what == WifiMonitor.P2P_PROV_DISC_SHOW_PIN_EVENT) {
                        mSavedPeerConfig.wps.setup = WpsInfo.DISPLAY;
                        mSavedPeerConfig.wps.pin = provDisc.pin;
                    } else {
                        mSavedPeerConfig.wps.setup = WpsInfo.PBC;
                    }
                    transitionTo(mUserAuthorizingJoinState);
                    break;
                case WifiMonitor.P2P_GROUP_STARTED_EVENT:
                    loge("Duplicate group creation event notice, ignore");
                    break;
                ///M:@{
                case WifiMonitor.P2P_PEER_DISCONNECT_EVENT:
                    /*M: ALPS00790492: handle P2P_PEER_DISCONNECT_EVENT frequency conflict error, reason code is 99*/
                    int IEEE802Dot11_ReasonCode = -1;
                    if (null != message.obj) {
                        try {
                            IEEE802Dot11_ReasonCode = Integer.valueOf((String) message.obj);
                            if (99 == IEEE802Dot11_ReasonCode) {
                                mGroupRemoveReason = P2pStatus.NO_COMMON_CHANNEL;
                            }
                        } catch (NumberFormatException e) {
                            loge("Error! Format unexpected");
                        }
                    }
                    /** M: enhance frequency conflict @{ */
                    if (0 != message.arg1)    p2pOperFreq = message.arg1;
                    /* only GC will received this event */
                    if (DBG) loge(getName() + " I'm GC and has been disconnected by GO. IEEE 802.11 reason code: " + IEEE802Dot11_ReasonCode +
                        ", p2pOperFreq: " + p2pOperFreq);
                    mWifiNative.p2pGroupRemove(mGroup.getInterface());
                    handleGroupRemoved();
                    transitionTo(mInactiveState);
                    break;
                case WifiP2pManager.START_FAST_CONNECT_AS_GO:
                    sendMessage(WifiP2pManager.REMOVE_GROUP);
                    //will turn to inactiveState, let inactiveState to process this message
                    mGoFastConnectEnaled = true;
                    mRestartFastConnectAsGo = true;
                    deferMessage(message);
                    break;
                case WifiP2pManager.START_FAST_CONNECT_AS_GC:
                    sendMessage(WifiP2pManager.REMOVE_GROUP);
                    //will turn to inactiveState, let inactiveState to process this message
                    mGcFastConnectEnaled = true;
                    mRestartFastConnectAsGc = true;
                    deferMessage(message);
                    break;
                case WifiMonitor.P2P_DEVICE_FOUND_EVENT:
                    WifiP2pDevice peerDevice = (WifiP2pDevice) message.obj;
                    if (mThisDevice.deviceAddress.equals(peerDevice.deviceAddress)) break;
                    if (mGroup.contains(peerDevice)) {
                        peerDevice.status = WifiP2pDevice.CONNECTED;
                    }
                    mPeers.update(peerDevice);
                    sendPeersChangedBroadcast();
                    break;
                case WifiMonitor.SUP_DISCONNECTION_EVENT:
                    if (DBG) loge("Supplicant close unexpected, send fake Group Remove event");
                    sendMessage(WifiMonitor.P2P_GROUP_REMOVED_EVENT);
                    deferMessage(message);
                    break;
                case WifiP2pManager.DISCOVER_PEERS:
                    // do not send service discovery request while normal find operation.
                    clearSupplicantServiceRequest();
                    if (mWifiNative.p2pFind(CONNECTED_DISCOVER_TIMEOUT_S)) {
                        replyToMessage(message, WifiP2pManager.DISCOVER_PEERS_SUCCEEDED);
                        sendP2pDiscoveryChangedBroadcast(true);
                    } else {
                        replyToMessage(message, WifiP2pManager.DISCOVER_PEERS_FAILED,
                                WifiP2pManager.ERROR);
                    }
                    break;
                case WifiP2pManager.CANCEL_CONNECT:
                    /*M: when group created successfully,
                    and UI do not know in time still send cancel connect command.
                    This case GroupcreatedState should remove the group. But when Inviting 3-device
                    to join a group and 3-device do not response.At this time GO or GC click cancel means
                    the Group has created entirely, so use the default solution to process the cancel command*/
                    if (mGroupCreatedEntirely) {
                        return NOT_HANDLED;
                    }

                    if (DBG) logd(getName() + " cancel connect, try to remove group");
                    boolean success = mWifiNative.p2pGroupRemove(mGroup.getInterface());
                    handleGroupRemoved();
                    transitionTo(mInactiveState);
                    replyToMessage(message, success == true
                        ? WifiP2pManager.CANCEL_CONNECT_SUCCEEDED : WifiP2pManager.CANCEL_CONNECT_FAILED);
                    break;
                /** M: NFC Float II @{ */
                case WifiP2pManager.GET_NFC_CONFIG_TOKEN:
                    if (!mMtkWpsp2pnfcSupport) {
                        replyToMessage(message, WifiP2pManager.GET_NFC_CONFIG_TOKEN_FAILED);
                        break;
                    }
                    String token = mWifiNative.getNfcConfigToken();
                    logd("GET_NFC_CONFIG_TOKEN, token:" + token);
                    if (!TextUtils.isEmpty(token) && !token.equals(UNKNOWN_COMMAND)) {
                        sendNfcConfigTokenBroadcast(token);
                        replyToMessage(message, WifiP2pManager.GET_NFC_CONFIG_TOKEN_SUCCEEDED);
                    } else {
                        logd("Failed to get nfc config token!");
                        replyToMessage(message, WifiP2pManager.GET_NFC_CONFIG_TOKEN_FAILED);
                    }
                    break;
                case WifiP2pManager.GET_NFC_WPS_CONFIG_TOKEN:
                    if (!mMtkWpsp2pnfcSupport) {
                        replyToMessage(message, WifiP2pManager.GET_NFC_WPS_CONFIG_TOKEN_FAILED);
                        break;
                    }
                    token = mWifiNative.getNfcHandoverToken(false);
                    logd("GET_NFC_WPS_CONFIG_TOKEN, token:" + token);
                    if (!TextUtils.isEmpty(token) && !token.equals(UNKNOWN_COMMAND)) {
                        mWaitingForHrToken = true;
                        mWifiChannel.sendMessage(WifiStateMachine.M_CMD_CLEAE_HR_WAIT_FLAG);
                        sendNfcWpsConfigTokenBroadcast(token);
                        replyToMessage(message, WifiP2pManager.GET_NFC_WPS_CONFIG_TOKEN_SUCCEEDED);
                    } else {
                        logd("Failed to get nfc wps config token!");
                        replyToMessage(message, WifiP2pManager.GET_NFC_WPS_CONFIG_TOKEN_FAILED);
                    }
                    break;
                ///@}
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        public void exit() {
            updateThisDevice(WifiP2pDevice.AVAILABLE);
            resetWifiP2pInfo();
            mNetworkInfo.setDetailedState(NetworkInfo.DetailedState.DISCONNECTED, null, null);
            ///M: wfd sink, label this broadcast due to 2nd GC connection comming  @{
            if (null != mGroup) {
                logd("[wfd sink/source] " +
                    " {1} isGroupOwner: " + mGroup.isGroupOwner() +
                    " {2} getClientAmount: " + mGroup.getClientAmount() +
                    " {3} isGroupRemoved(): " + isGroupRemoved());
            }
            // case 1: wfd sink && go & gc amount=1
            if (isDeviceBeWfdSink_InSession()) {
                //sendP2pConnectionChangedBroadcast(P2pStatus.MTK_EXPAND_01);
                logd("[wfd sink/source] don't bother wfd framework, case 1");

            // case 2: wfd source && group still formed
            } else if (isDeviceBeWfdSource_InSession()) {
                //sendP2pConnectionChangedBroadcast(P2pStatus.MTK_EXPAND_01);
                logd("[wfd sink/source] don't bother wfd framework, case 2");

            } else
            ///@}
            /*M: ALPS00677009: broadcast the group removed reason*/
            sendP2pConnectionChangedBroadcast(mGroupRemoveReason);

            /** M: NFC Float II @{ */
            if (mMtkWpsp2pnfcSupport) {
                mTmpGcIp = null;
                mTmpGoIp = null;
            }
            /** @} */
        }
    }

    class UserAuthorizingJoinState extends State {
        @Override
        public void enter() {
            if (DBG) logd(getName());
            ///M: for wfd sink, reject 2nd gc  @{
            if (isDeviceBeWfdSink()) {
                sendMessage(PEER_CONNECTION_USER_REJECT);
            } else
            ///@}
            notifyInvitationReceived();
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) logd(getName() + message.toString());
            switch (message.what) {
                case WifiMonitor.P2P_PROV_DISC_PBC_REQ_EVENT:
                case WifiMonitor.P2P_PROV_DISC_ENTER_PIN_EVENT:
                case WifiMonitor.P2P_PROV_DISC_SHOW_PIN_EVENT:
                    //Ignore more client requests
                    break;
                case PEER_CONNECTION_USER_ACCEPT:
                ///M: wfd sink @{
                case WifiP2pManager.PEER_CONNECTION_USER_ACCEPT_FROM_OUTER:
                ///@}
                    //Stop discovery to avoid failure due to channel switch
                    mWifiNative.p2pStopFind();
                    if (mSavedPeerConfig.wps.setup == WpsInfo.PBC) {
                        mWifiNative.startWpsPbc(mGroup.getInterface(), null);
                    } else {
                        mWifiNative.startWpsPinKeypad(mGroup.getInterface(),
                                mSavedPeerConfig.wps.pin);
                    }
                    transitionTo(mGroupCreatedState);
                    break;
                case PEER_CONNECTION_USER_REJECT:
                ///M: wfd sink @{
                case WifiP2pManager.PEER_CONNECTION_USER_REJECT_FROM_OUTER:
                ///@}
                    if (DBG) logd("User rejected incoming request");
                    transitionTo(mGroupCreatedState);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        @Override
        public void exit() {
            //TODO: dismiss dialog if not already done
        }
    }

    class OngoingGroupRemovalState extends State {
        @Override
        public void enter() {
            if (DBG) logd(getName());
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) logd(getName() + message.toString());
            switch (message.what) {
                // Group removal ongoing. Multiple calls
                // end up removing persisted network. Do nothing.
                case WifiP2pManager.REMOVE_GROUP:
                    replyToMessage(message, WifiP2pManager.REMOVE_GROUP_SUCCEEDED);
                    break;
                // Parent state will transition out of this state
                // when removal is complete
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        super.dump(fd, pw, args);
        pw.println("mWifiP2pInfo " + mWifiP2pInfo);
        pw.println("mGroup " + mGroup);
        pw.println("mSavedPeerConfig " + mSavedPeerConfig);
        pw.println("mSavedP2pGroup " + mSavedP2pGroup);
        pw.println();
    }

    private void sendP2pStateChangedBroadcast(boolean enabled) {
        final Intent intent = new Intent(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        if (enabled) {
            intent.putExtra(WifiP2pManager.EXTRA_WIFI_STATE,
                    WifiP2pManager.WIFI_P2P_STATE_ENABLED);
        } else {
            intent.putExtra(WifiP2pManager.EXTRA_WIFI_STATE,
                    WifiP2pManager.WIFI_P2P_STATE_DISABLED);
        }
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void sendP2pDiscoveryChangedBroadcast(boolean started) {
        if (mDiscoveryStarted == started) return;
        mDiscoveryStarted = started;

        if (DBG) logd("discovery change broadcast " + started);

        final Intent intent = new Intent(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, started ?
                WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED :
                WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void sendThisDeviceChangedBroadcast() {
        final Intent intent = new Intent(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, new WifiP2pDevice(mThisDevice));
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void sendPeersChangedBroadcast() {
        final Intent intent = new Intent(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intent.putExtra(WifiP2pManager.EXTRA_P2P_DEVICE_LIST, new WifiP2pDeviceList(mPeers));
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        /*M: ALPS00541624, sticky broadcast to avoid apk miss peer information */
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void sendP2pConnectionChangedBroadcast() {
        if (DBG) logd("sending p2p connection changed broadcast, mGroup: " + mGroup);
        Intent intent = new Intent(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                | Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.putExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, new WifiP2pInfo(mWifiP2pInfo));
        intent.putExtra(WifiP2pManager.EXTRA_NETWORK_INFO, new NetworkInfo(mNetworkInfo));
        intent.putExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP, new WifiP2pGroup(mGroup));
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
        mWifiChannel.sendMessage(WifiP2pServiceImpl.P2P_CONNECTION_CHANGED,
                new NetworkInfo(mNetworkInfo));
    }

    private void sendP2pPersistentGroupsChangedBroadcast() {
        if (DBG) logd("sending p2p persistent groups changed broadcast");
        Intent intent = new Intent(WifiP2pManager.WIFI_P2P_PERSISTENT_GROUPS_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void startDhcpServer(String intf) {
        InterfaceConfiguration ifcg = null;
        try {
            ifcg = mNwService.getInterfaceConfig(intf);
            ifcg.setLinkAddress(new LinkAddress(NetworkUtils.numericToInetAddress(
                        SERVER_ADDRESS), 24));
            ifcg.setInterfaceUp();
            mNwService.setInterfaceConfig(intf, ifcg);
            /* This starts the dnsmasq server */
            ConnectivityManager cm = (ConnectivityManager) mContext.getSystemService(
                    Context.CONNECTIVITY_SERVICE);
            /* Is chosen as a unique range to avoid conflict with
             * the range defined in Tethering.java
             * M: Changed due to fast connect's static IP, GC's static IP is 192.168.49.2
             * M: NFC Float II, change dhcp range from 12 to 254
            private static final String[] DHCP_RANGE = {"192.168.49.12", "192.168.49.254"};
              [TBD], overwrite the range */
            String[] tetheringDhcpRanges = cm.getTetheredDhcpRanges();
            if (mNwService.isTetheringStarted()) {
                if (DBG) logd("Stop existing tethering and restart it");
                mNwService.stopTethering();
            }
            mNwService.tetherInterface(intf);
            mNwService.startTethering(tetheringDhcpRanges);
        } catch (Exception e) {
            loge("Error configuring interface " + intf + ", :" + e);
            return;
        }

        logd("Started Dhcp server on " + intf);
   }

    private void stopDhcpServer(String intf) {
        try {
            mNwService.untetherInterface(intf);
            for (String temp : mNwService.listTetheredInterfaces()) {
                logd("List all interfaces " + temp);
                if (temp.compareTo(intf) != 0) {
                    logd("Found other tethering interfaces, so keep tethering alive");
                    return;
                }
            }
            mNwService.stopTethering();
        } catch (Exception e) {
            loge("Error stopping Dhcp server" + e);
            return;
        } finally {
            logd("Stopped Dhcp server");
        }
    }

    private void notifyP2pEnableFailure() {
        Resources r = Resources.getSystem();
        AlertDialog dialog = new AlertDialog.Builder(mContext)
            .setTitle(r.getString(R.string.wifi_p2p_dialog_title))
            .setMessage(r.getString(R.string.wifi_p2p_failed_message))
            .setPositiveButton(r.getString(R.string.ok), null)
            .create();
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        dialog.show();
    }

    private void addRowToDialog(ViewGroup group, int stringId, String value) {
        Resources r = Resources.getSystem();
        View row = LayoutInflater.from(mContext).inflate(R.layout.wifi_p2p_dialog_row,
                group, false);
        ((TextView) row.findViewById(R.id.name)).setText(r.getString(stringId));
        ((TextView) row.findViewById(R.id.value)).setText(value);
        group.addView(row);
    }

    private void notifyInvitationSent(String pin, String peerAddress) {
        Resources r = Resources.getSystem();

        final View textEntryView = LayoutInflater.from(mContext)
                .inflate(R.layout.wifi_p2p_dialog, null);

        ViewGroup group = (ViewGroup) textEntryView.findViewById(R.id.info);
        addRowToDialog(group, R.string.wifi_p2p_to_message, getDeviceName(peerAddress));
        addRowToDialog(group, R.string.wifi_p2p_show_pin_message, pin);

        AlertDialog dialog = new AlertDialog.Builder(mContext)
            .setTitle(r.getString(R.string.wifi_p2p_invitation_sent_title))
            .setView(textEntryView)
            .setPositiveButton(r.getString(R.string.ok), null)
            .create();
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        dialog.show();
    }

    private void notifyInvitationReceived() {
        Resources r = Resources.getSystem();
        final WpsInfo wps = mSavedPeerConfig.wps;
        final View textEntryView = LayoutInflater.from(mContext)
                .inflate(R.layout.wifi_p2p_dialog, null);

        ViewGroup group = (ViewGroup) textEntryView.findViewById(R.id.info);
        addRowToDialog(group, R.string.wifi_p2p_from_message, getDeviceName(
                mSavedPeerConfig.deviceAddress));

        final EditText pin = (EditText) textEntryView.findViewById(R.id.wifi_p2p_wps_pin);

        AlertDialog dialog = new AlertDialog.Builder(mContext)
            .setTitle(r.getString(R.string.wifi_p2p_invitation_to_connect_title))
            .setView(textEntryView)
            .setPositiveButton(r.getString(R.string.accept), new OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            if (wps.setup == WpsInfo.KEYPAD) {
                                mSavedPeerConfig.wps.pin = pin.getText().toString();
                            }
                            if (DBG) logd(getName() + " accept invitation " + mSavedPeerConfig);
                            sendMessage(PEER_CONNECTION_USER_ACCEPT);
                        }
                    })
            .setNegativeButton(r.getString(R.string.decline), new OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (DBG) logd(getName() + " ignore connect");
                            sendMessage(PEER_CONNECTION_USER_REJECT);
                        }
                    })
            .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        @Override
                        public void onCancel(DialogInterface arg0) {
                            if (DBG) logd(getName() + " ignore connect");
                            sendMessage(PEER_CONNECTION_USER_REJECT);
                        }
                    })
            .create();

        //make the enter pin area or the display pin area visible
        switch (wps.setup) {
            case WpsInfo.KEYPAD:
                if (DBG) logd("Enter pin section visible");
                textEntryView.findViewById(R.id.enter_pin_section).setVisibility(View.VISIBLE);
                break;
            case WpsInfo.DISPLAY:
                if (DBG) logd("Shown pin section visible");
                addRowToDialog(group, R.string.wifi_p2p_show_pin_message, wps.pin);
                break;
            default:
                break;
        }

        if ((r.getConfiguration().uiMode & Configuration.UI_MODE_TYPE_APPLIANCE) ==
                Configuration.UI_MODE_TYPE_APPLIANCE) {
            // For appliance devices, add a key listener which accepts.
            dialog.setOnKeyListener(new DialogInterface.OnKeyListener() {

                @Override
                public boolean onKey(DialogInterface dialog, int keyCode, KeyEvent event) {
                    // TODO: make the actual key come from a config value.
                    if (keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
                        sendMessage(PEER_CONNECTION_USER_ACCEPT);
                        dialog.dismiss();
                        return true;
                    }
                    return false;
                }
            });
            // TODO: add timeout for this dialog.
            // TODO: update UI in appliance mode to tell user what to do.
        }

        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        dialog.show();
    }

    /**
     * Synchronize the persistent group list between
     * wpa_supplicant and mGroups.
     */
    private void updatePersistentNetworks(boolean reload) {
        String listStr = mWifiNative.listNetworks();
        if (listStr == null) return;

        boolean isSaveRequired = false;
        String[] lines = listStr.split("\n");
        if (lines == null) return;

        if (reload) mGroups.clear();

        // Skip the first line, which is a header
        for (int i = 1; i < lines.length; i++) {
            String[] result = lines[i].split("\t");
            if (result == null || result.length < 4) {
                continue;
            }
            // network-id | ssid | bssid | flags
            int netId = -1;
            String ssid = result[1];
            String bssid = result[2];
            String flags = result[3];
            try {
                netId = Integer.parseInt(result[0]);
            } catch(NumberFormatException e) {
                e.printStackTrace();
                continue;
            }

            if (flags.indexOf("[CURRENT]") != -1) {
                continue;
            }
            if (flags.indexOf("[P2P-PERSISTENT]") == -1) {
                /*
                 * The unused profile is sometimes remained when the p2p group formation is failed.
                 * So, we clean up the p2p group here.
                 */
                if (DBG) logd("clean up the unused persistent group. netId=" + netId);
                mWifiNative.removeNetwork(netId);
                isSaveRequired = true;
                continue;
            }

            if (mGroups.contains(netId)) {
                continue;
            }

            WifiP2pGroup group = new WifiP2pGroup();
            group.setNetworkId(netId);
            group.setNetworkName(ssid);
            String mode = mWifiNative.getNetworkVariable(netId, "mode");
            if (mode != null && mode.equals("3")) {
                group.setIsGroupOwner(true);
            }
            if (bssid.equalsIgnoreCase(mThisDevice.deviceAddress)) {
                group.setOwner(mThisDevice);
            } else {
                WifiP2pDevice device = new WifiP2pDevice();
                device.deviceAddress = bssid;
                group.setOwner(device);
            }
            mGroups.add(group);
            isSaveRequired = true;
        }

        if (reload || isSaveRequired) {
            mWifiNative.saveConfig();
            sendP2pPersistentGroupsChangedBroadcast();
        }
    }

    /**
     * A config is valid if it has a peer address that has already been
     * discovered
     * @return true if it is invalid, false otherwise
     */
    private boolean isConfigInvalid(WifiP2pConfig config) {
        if (config == null) return true;
        if (TextUtils.isEmpty(config.deviceAddress)) return true;
        if (mPeers.get(config.deviceAddress) == null) return true;
        return false;
    }

    /* TODO: The supplicant does not provide group capability changes as an event.
     * Having it pushed as an event would avoid polling for this information right
     * before a connection
     */
    private WifiP2pDevice fetchCurrentDeviceDetails(WifiP2pConfig config) {
        /* Fetch & update group capability from supplicant on the device */
        int gc = mWifiNative.getGroupCapability(config.deviceAddress);
        mPeers.updateGroupCapability(config.deviceAddress, gc);
        return mPeers.get(config.deviceAddress);
    }

    /**
     * Start a p2p group negotiation and display pin if necessary
     * @param config for the peer
     */
    private void p2pConnectWithPinDisplay(WifiP2pConfig config) {
        WifiP2pDevice dev = fetchCurrentDeviceDetails(config);

        String pin = mWifiNative.p2pConnect(config, dev.isGroupOwner());
        try {
            Integer.parseInt(pin);
            notifyInvitationSent(pin, config.deviceAddress);
        } catch (NumberFormatException ignore) {
            // do nothing if p2pConnect did not return a pin
        }
    }

    /**
     * Reinvoke a persistent group.
     *
     * @param config for the peer
     * @return true on success, false on failure
     */
    private boolean reinvokePersistentGroup(WifiP2pConfig config) {
        WifiP2pDevice dev = fetchCurrentDeviceDetails(config);

        boolean join = dev.isGroupOwner();
        String ssid = mWifiNative.p2pGetSsid(dev.deviceAddress);
        if (DBG) logd("target ssid is " + ssid + " join:" + join);

        if (join && dev.isGroupLimit()) {
            if (DBG) logd("target device reaches group limit.");

            // if the target group has reached the limit,
            // try group formation.
            join = false;
        } else if (join) {
            int netId = mGroups.getNetworkId(dev.deviceAddress, ssid);
            if (netId >= 0) {
                ///M: ALPS00605482+ALPS00657537: can't use p2pGroupAdd when peer is GO and had ever formed @{
                // Skip WPS and start 4way handshake immediately.
                //if (!mWifiNative.p2pGroupAdd(netId)) {
                if (!mWifiNative.p2pReinvoke(netId, dev.deviceAddress)) {
                    return false;
                }
                ///@}
                return true;
            }
        }

        if (!join && dev.isDeviceLimit()) {
            loge("target device reaches the device limit.");
            return false;
        }

        if (!join && dev.isInvitationCapable()) {
            int netId = WifiP2pGroup.PERSISTENT_NET_ID;
            if (config.netId >= 0) {
                if (config.deviceAddress.equals(mGroups.getOwnerAddr(config.netId))) {
                    netId = config.netId;
                }
            } else {
                netId = mGroups.getNetworkId(dev.deviceAddress);
            }
            if (netId < 0) {
                netId = getNetworkIdFromClientList(dev.deviceAddress);
            }
            if (DBG) logd("netId related with " + dev.deviceAddress + " = " + netId);
            if (netId >= 0) {
                // Invoke the persistent group.
                if (mWifiNative.p2pReinvoke(netId, dev.deviceAddress)) {
                    // Save network id. It'll be used when an invitation result event is received.
                    config.netId = netId;
                    return true;
                } else {
                    loge("p2pReinvoke() failed, update networks");
                    updatePersistentNetworks(RELOAD);
                    return false;
                }
            }
        }

        return false;
    }

    /**
     * Return the network id of the group owner profile which has the p2p client with
     * the specified device address in it's client list.
     * If more than one persistent group of the same address is present in its client
     * lists, return the first one.
     *
     * @param deviceAddress p2p device address.
     * @return the network id. if not found, return -1.
     */
    private int getNetworkIdFromClientList(String deviceAddress) {
        if (deviceAddress == null) return -1;

        Collection<WifiP2pGroup> groups = mGroups.getGroupList();
        for (WifiP2pGroup group : groups) {
            int netId = group.getNetworkId();
            String[] p2pClientList = getClientList(netId);
            if (p2pClientList == null) continue;
            for (String client : p2pClientList) {
                if (deviceAddress.equalsIgnoreCase(client)) {
                    return netId;
                }
            }
        }
        return -1;
    }

    /**
     * Return p2p client list associated with the specified network id.
     * @param netId network id.
     * @return p2p client list. if not found, return null.
     */
    private String[] getClientList(int netId) {
        String p2pClients = mWifiNative.getNetworkVariable(netId, "p2p_client_list");
        if (p2pClients == null) {
            return null;
        }
        return p2pClients.split(" ");
    }

    /**
     * Remove the specified p2p client from the specified profile.
     * @param netId network id of the profile.
     * @param addr p2p client address to be removed.
     * @param isRemovable if true, remove the specified profile if its client list becomes empty.
     * @return whether removing the specified p2p client is successful or not.
     */
    private boolean removeClientFromList(int netId, String addr, boolean isRemovable) {
        StringBuilder modifiedClientList =  new StringBuilder();
        String[] currentClientList = getClientList(netId);
        boolean isClientRemoved = false;
        if (currentClientList != null) {
            for (String client : currentClientList) {
                if (!client.equalsIgnoreCase(addr)) {
                    modifiedClientList.append(" ");
                    modifiedClientList.append(client);
                } else {
                    isClientRemoved = true;
                }
            }
        }
        if (modifiedClientList.length() == 0 && isRemovable) {
            // the client list is empty. so remove it.
            if (DBG) logd("Remove unknown network");
            mGroups.remove(netId);
            return true;
        }

        if (!isClientRemoved) {
            // specified p2p client is not found. already removed.
            return false;
        }

        if (DBG) logd("Modified client list: " + modifiedClientList);
        if (modifiedClientList.length() == 0) {
            modifiedClientList.append("\"\"");
        }
        mWifiNative.setNetworkVariable(netId,
                "p2p_client_list", modifiedClientList.toString());
        mWifiNative.saveConfig();
        return true;
    }

    private void setWifiP2pInfoOnGroupFormation(InetAddress serverInetAddress) {
        mWifiP2pInfo.groupFormed = true;
        mWifiP2pInfo.isGroupOwner = mGroup.isGroupOwner();
        mWifiP2pInfo.groupOwnerAddress = serverInetAddress;
    }

    // M: return if it is fast connect GC or not
    private boolean resetWifiP2pInfo() {
        ///M: clear fast connect group
        if (mFastConnectInfo != null && mFastConnectInfo.networkId >= 0) {
            logd("clear netId=" + mFastConnectInfo.networkId);
            mGroups.remove(mFastConnectInfo.networkId);
            ///M: ALPS01423828: need reset networkId, use -3 as default value  @{
            //public static final int TEMPORARY_NET_ID = -1;
            //public static final int PERSISTENT_NET_ID = -2;
            mFastConnectInfo.networkId = -3;
            ///@}
        }
        ///M: ALPS01443292: enable/disable IPv6 on demand  @{
        try {
            mNwService.disableIpv6(mInterface);
        } catch (RemoteException re) {
            loge("resetWifiP2pInfo() disableIpv6 RemoteException: " + re);
        } catch (IllegalStateException ie) {
            loge("resetWifiP2pInfo() disableIpv6 IllegalStateException: " + ie);
        }
        ///@}
        mWifiP2pInfo.groupFormed = false;
        mWifiP2pInfo.isGroupOwner = false;
        mWifiP2pInfo.groupOwnerAddress = null;
        ///M: ALPS01212893: for poor link, wifi p2p stop Tx all traffic @{
        sendP2pTxBroadcast(false);
        ///@}
        ///M: NFC fast connect @{
        return setFastConnectInfoOnGroupTermination();
        ///@}
    }

    private String getDeviceName(String deviceAddress) {
        WifiP2pDevice d = mPeers.get(deviceAddress);
        if (d != null) {
                return d.deviceName;
        }
        //Treat the address as name if there is no match
        return deviceAddress;
    }

    private String getPersistedDeviceName() {
        String deviceName = Settings.Global.getString(mContext.getContentResolver(),
                Settings.Global.WIFI_P2P_DEVICE_NAME);
        if (deviceName == null) {
            /* We use the 4 digits of the ANDROID_ID to have a friendly
             * default that has low likelihood of collision with a peer */
            String id = Settings.Secure.getString(mContext.getContentResolver(),
                    Settings.Secure.ANDROID_ID);
            return "Android_" + id.substring(0,4);
			//return "X1 Grand";
        }
        return deviceName;
    }

    private boolean setAndPersistDeviceName(String devName) {
        if (devName == null) return false;

        if (!mWifiNative.setDeviceName(devName)) {
            loge("Failed to set device name " + devName);
            return false;
        }

        mThisDevice.deviceName = devName;
        mWifiNative.setP2pSsidPostfix("-" + mThisDevice.deviceName);

        Settings.Global.putString(mContext.getContentResolver(),
                Settings.Global.WIFI_P2P_DEVICE_NAME, devName);
        sendThisDeviceChangedBroadcast();
        return true;
    }

    private boolean setWfdInfo(WifiP2pWfdInfo wfdInfo) {
        boolean success;

        if (!wfdInfo.isWfdEnabled()) {
            success = mWifiNative.setWfdEnable(false);
        } else {
            success =
                mWifiNative.setWfdEnable(true)
                && mWifiNative.setWfdDeviceInfo(wfdInfo.getDeviceInfoHex());
        }

        if (!success) {
            loge("Failed to set wfd properties, Device Info part");
            return false;
        }

        //M: ALPS01255052: UIBC in WFD IE
        if (0 != wfdInfo.getExtendedCapability()) {
            success = mWifiNative.setWfdExtCapability(wfdInfo.getExtCapaHex());
        }

        if (!success) {
            loge("Failed to set wfd properties, Extended Capability part");
            return false;
        }

        mThisDevice.wfdInfo = wfdInfo;
        sendThisDeviceChangedBroadcast();
        return true;
    }

    private void initializeP2pSettings() {
        mWifiNative.setPersistentReconnect(true);
        mThisDevice.deviceName = getPersistedDeviceName();
        mWifiNative.setDeviceName(mThisDevice.deviceName);
        // DIRECT-XY-DEVICENAME (XY is randomly generated)
        mWifiNative.setP2pSsidPostfix("-" + mThisDevice.deviceName);
        mWifiNative.setDeviceType(mThisDevice.primaryDeviceType);
        // Supplicant defaults to using virtual display with display
        // which refers to a remote display. Use physical_display
        mWifiNative.setConfigMethods("virtual_push_button physical_display keypad");
        // STA has higher priority over P2P
        mWifiNative.setConcurrencyPriority("sta");

        if (DBG) logd("old DeviceAddress: " + mThisDevice.deviceAddress);
        mThisDevice.deviceAddress = mWifiNative.p2pGetDeviceAddress();
        if (DBG) logd("new DeviceAddress: " + mThisDevice.deviceAddress);
        updateThisDevice(WifiP2pDevice.AVAILABLE);

        mClientInfoList.clear();
        mWifiNative.p2pFlush();
        mWifiNative.p2pServiceFlush();
        mServiceTransactionId = 0;
        mServiceDiscReqId = null;

        String countryCode = Settings.Global.getString(mContext.getContentResolver(),
                Settings.Global.WIFI_COUNTRY_CODE);
        if (countryCode != null && !countryCode.isEmpty()) {
            mP2pStateMachine.sendMessage(SET_COUNTRY_CODE, countryCode);
        }

        updatePersistentNetworks(RELOAD);
    }

    private void updateThisDevice(int status) {
        mThisDevice.status = status;
        sendThisDeviceChangedBroadcast();
    }

    private void handleGroupCreationFailure() {
        /*M: ALPS00918679: fast connect GC mode won't trigger normal scan */
        boolean isFastConnGC = resetWifiP2pInfo();
        mNetworkInfo.setDetailedState(NetworkInfo.DetailedState.FAILED, null, null);

        ///M: ALPS01472489: notify user decline channel conflict  @{
        if (P2pStatus.UNKNOWN == mGroupRemoveReason) {
            sendP2pConnectionChangedBroadcast();
        } else {
            sendP2pConnectionChangedBroadcast(mGroupRemoveReason);
        }
        ///@}

        // Remove only the peer we failed to connect to so that other devices discovered
        // that have not timed out still remain in list for connection
        boolean peersChanged = mPeers.remove(mPeersLostDuringConnection);

        ///M: fix peer hadn't been searched but group formed failure. JB MR2 will false alarm! @{
        if (mSavedPeerConfig.deviceAddress != null &&
                mPeers.containsPeer(mSavedPeerConfig.deviceAddress)) {
            if (mPeers.remove(mSavedPeerConfig.deviceAddress) != null) {
                if (DBG) logd("peer device was removed from mPeers!");
                peersChanged = true;
            }
        }
        ///@}

        if (peersChanged) {
            sendPeersChangedBroadcast();
        }

        mPeersLostDuringConnection.clear();
        mServiceDiscReqId = null;

        /*M: ALPS01807734: wfd sink won't trigger normal scan, let scan request from user */
        /*M: ALPS00918679: fast connect GC mode won't trigger normal scan */
        if (!isDeviceBeWfdSink() && false==isFastConnGC) {
            sendMessage(WifiP2pManager.DISCOVER_PEERS);
        }

        /*M: ALPS01000415: case #17-7,#18-1 -> Wifi AP not reconnect problem */
        if (mTemporarilyDisconnectedWifi) {
            mWifiChannel.sendMessage(WifiP2pServiceImpl.DISCONNECT_WIFI_REQUEST, 0);
            mTemporarilyDisconnectedWifi = false;
        }

        /** M: NFC Float II @{ */
        if (mMtkWpsp2pnfcSupport) {
            mTmpGcIp = null;
            mTmpGoIp = null;
        }
        /** @} */
    }

    private void handleGroupRemoved() {
        ///M: fix UI show peer device MAC when UI receive connect request between p2p_flush() and P2P_DEVICE_FOUND, refer to CL1946680  @{
        Collection <WifiP2pDevice> devices = mGroup.getClientList();
        boolean changed = false;
        for (WifiP2pDevice d : mPeers.getDeviceList()) {
            if (devices.contains(d) || mGroup.getOwner().equals(d)) {
                d.status = WifiP2pDevice.AVAILABLE;
                changed = true;
            }
        }
        ///@}

        if (mGroup.isGroupOwner()) {
            stopDhcpServer(mGroup.getInterface());
            ///M: remove dhcp info. file  @{
            boolean b;
            File dhcpFile = new File(DHCP_INFO_FILE);
            if (DBG) logd("DHCP file exists=" + dhcpFile.exists());
            if (dhcpFile.exists()) {
                b = dhcpFile.delete();
                if (b) logd("Delete p2p0 dhcp info file OK!");
            }
            ///@}
        } else {
            ///M: add judge for fast connect @{
            if (mDhcpStateMachine != null) {
                if (DBG) logd("stop DHCP client");
                mDhcpStateMachine.sendMessage(DhcpStateMachine.CMD_STOP_DHCP);
                ///M: quikly stop dhcp without delay @{
                if (!NetworkUtils.stopDhcp(mGroup.getInterface())) {
                    loge("Failed to stop dhcp on " + mGroup.getInterface());
                } else {
                    logd("Stop dhcp successfully!");
                }
                ///@}                
                mDhcpStateMachine.doQuit();
                mDhcpStateMachine = null;
                try {
                    mNwService.removeInterfaceFromLocalNetwork(mGroup.getInterface());
                } catch (RemoteException e) {
                    loge("Failed to remove iface from local network: RemoteException=" + e);
                } catch (IllegalStateException ie) {
                    loge("Failed to remove iface from local network: IllegalStateException=" + ie);
                }
            } else {
                logd("mDhcpStateMachine is null. No dhcp client!");
            }
            ///@}
        }

        try {
            mNwService.clearInterfaceAddresses(mGroup.getInterface());
        } catch (Exception e) {
            loge("Failed to clear addresses " + e);
        }
        NetworkUtils.resetConnections(mGroup.getInterface(), NetworkUtils.RESET_ALL_ADDRESSES);

        // Clear any timeout that was set. This is essential for devices
        // that reuse the main p2p interface for a created group.
        mWifiNative.setP2pGroupIdle(mGroup.getInterface(), 0);

        ///M: fix UI show peer device MAC when UI receive connect request between p2p_flush() and P2P_DEVICE_FOUND, refer to CL1946680  @{
        mGroup = null;
        mWifiNative.p2pFlush();
        if (mPeers.remove(mPeersLostDuringConnection)) changed = true;
        mPeersLostDuringConnection.clear();
        mServiceDiscReqId = null;
        if (changed) sendPeersChangedBroadcast();
        ///@}

        if (mTemporarilyDisconnectedWifi) {
            mWifiChannel.sendMessage(WifiP2pServiceImpl.DISCONNECT_WIFI_REQUEST, 0);
            mTemporarilyDisconnectedWifi = false;
        }

        ///M: add for ALPS00489161  @{
        mGroupCreatedEntirely = false;
        ///@}
   }

    //State machine initiated requests can have replyTo set to null indicating
    //there are no recipients, we ignore those reply actions
    private void replyToMessage(Message msg, int what) {
        if (msg.replyTo == null) return;
        Message dstMsg = obtainMessage(msg);
        dstMsg.what = what;
        mReplyChannel.replyToMessage(msg, dstMsg);
    }

    private void replyToMessage(Message msg, int what, int arg1) {
        if (msg.replyTo == null) return;
        Message dstMsg = obtainMessage(msg);
        dstMsg.what = what;
        dstMsg.arg1 = arg1;
        mReplyChannel.replyToMessage(msg, dstMsg);
    }

    private void replyToMessage(Message msg, int what, Object obj) {
        if (msg.replyTo == null) return;
        Message dstMsg = obtainMessage(msg);
        dstMsg.what = what;
        dstMsg.obj = obj;
        mReplyChannel.replyToMessage(msg, dstMsg);
    }

    /* arg2 on the source message has a hash code that needs to be retained in replies
     * see WifiP2pManager for details */
    private Message obtainMessage(Message srcMsg) {
        Message msg = Message.obtain();
        msg.arg2 = srcMsg.arg2;
        return msg;
    }

    @Override
    protected void logd(String s) {
        ///M: @{
        //Slog.d(TAG, s);
        Log.d(TAG, s);
        ///@}
    }

    @Override
    protected void loge(String s) {
        ///M: @{
        //Slog.e(TAG, s);
        Log.e(TAG, s);
        ///@}
    }

    /**
     * Update service discovery request to wpa_supplicant.
     */
    private boolean updateSupplicantServiceRequest() {
        clearSupplicantServiceRequest();

        StringBuffer sb = new StringBuffer();
        for (ClientInfo c: mClientInfoList.values()) {
            int key;
            WifiP2pServiceRequest req;
            for (int i=0; i < c.mReqList.size(); i++) {
                req = c.mReqList.valueAt(i);
                if (req != null) {
                    sb.append(req.getSupplicantQuery());
                }
            }
        }

        if (sb.length() == 0) {
            return false;
        }

        mServiceDiscReqId = mWifiNative.p2pServDiscReq("00:00:00:00:00:00", sb.toString());
        if (mServiceDiscReqId == null) {
            return false;
        }
        return true;
    }

    /**
     * Clear service discovery request in wpa_supplicant
     */
    private void clearSupplicantServiceRequest() {
        if (mServiceDiscReqId == null) return;

        mWifiNative.p2pServDiscCancelReq(mServiceDiscReqId);
        mServiceDiscReqId = null;
    }

    /* TODO: We could track individual service adds separately and avoid
     * having to do update all service requests on every new request
     */
    private boolean addServiceRequest(Messenger m, WifiP2pServiceRequest req) {
        clearClientDeadChannels();
        ClientInfo clientInfo = getClientInfo(m, true);
        if (clientInfo == null) {
            return false;
        }

        ++mServiceTransactionId;
        //The Wi-Fi p2p spec says transaction id should be non-zero
        if (mServiceTransactionId == 0) ++mServiceTransactionId;
        req.setTransactionId(mServiceTransactionId);
        clientInfo.mReqList.put(mServiceTransactionId, req);

        if (mServiceDiscReqId == null) {
            return true;
        }

        return updateSupplicantServiceRequest();
    }

    private void removeServiceRequest(Messenger m, WifiP2pServiceRequest req) {
        ClientInfo clientInfo = getClientInfo(m, false);
        if (clientInfo == null) {
            return;
        }

        //Application does not have transaction id information
        //go through stored requests to remove
        boolean removed = false;
        for (int i=0; i<clientInfo.mReqList.size(); i++) {
            if (req.equals(clientInfo.mReqList.valueAt(i))) {
                removed = true;
                clientInfo.mReqList.removeAt(i);
                break;
            }
        }

        if (!removed) return;

        if (clientInfo.mReqList.size() == 0 && clientInfo.mServList.size() == 0) {
            if (DBG) logd("remove client information from framework");
            mClientInfoList.remove(clientInfo.mMessenger);
        }

        if (mServiceDiscReqId == null) {
            return;
        }

        updateSupplicantServiceRequest();
    }

    private void clearServiceRequests(Messenger m) {

        ClientInfo clientInfo = getClientInfo(m, false);
        if (clientInfo == null) {
            return;
        }

        if (clientInfo.mReqList.size() == 0) {
            return;
        }

        clientInfo.mReqList.clear();

        if (clientInfo.mServList.size() == 0) {
            if (DBG) logd("remove channel information from framework");
            mClientInfoList.remove(clientInfo.mMessenger);
        }

        if (mServiceDiscReqId == null) {
            return;
        }

        updateSupplicantServiceRequest();
    }

    private boolean addLocalService(Messenger m, WifiP2pServiceInfo servInfo) {
        clearClientDeadChannels();
        ClientInfo clientInfo = getClientInfo(m, true);
        if (clientInfo == null) {
            return false;
        }

        if (!clientInfo.mServList.add(servInfo)) {
            return false;
        }

        if (!mWifiNative.p2pServiceAdd(servInfo)) {
            clientInfo.mServList.remove(servInfo);
            return false;
        }

        return true;
    }

    private void removeLocalService(Messenger m, WifiP2pServiceInfo servInfo) {
        ClientInfo clientInfo = getClientInfo(m, false);
        if (clientInfo == null) {
            return;
        }

        mWifiNative.p2pServiceDel(servInfo);

        clientInfo.mServList.remove(servInfo);
        if (clientInfo.mReqList.size() == 0 && clientInfo.mServList.size() == 0) {
            if (DBG) logd("remove client information from framework");
            mClientInfoList.remove(clientInfo.mMessenger);
        }
    }

    private void clearLocalServices(Messenger m) {
        ClientInfo clientInfo = getClientInfo(m, false);
        if (clientInfo == null) {
            return;
        }

        for (WifiP2pServiceInfo servInfo: clientInfo.mServList) {
            mWifiNative.p2pServiceDel(servInfo);
        }

        clientInfo.mServList.clear();
        if (clientInfo.mReqList.size() == 0) {
            if (DBG) logd("remove client information from framework");
            mClientInfoList.remove(clientInfo.mMessenger);
        }
    }

    private void clearClientInfo(Messenger m) {
        clearLocalServices(m);
        clearServiceRequests(m);
    }

    /**
     * Send the service response to the WifiP2pManager.Channel.
     *
     * @param resp
     */
    private void sendServiceResponse(WifiP2pServiceResponse resp) {
        for (ClientInfo c : mClientInfoList.values()) {
            WifiP2pServiceRequest req = c.mReqList.get(resp.getTransactionId());
            if (req != null) {
                Message msg = Message.obtain();
                msg.what = WifiP2pManager.RESPONSE_SERVICE;
                msg.arg1 = 0;
                msg.arg2 = 0;
                msg.obj = resp;
                try {
                    c.mMessenger.send(msg);
                } catch (RemoteException e) {
                    if (DBG) logd("detect dead channel");
                    clearClientInfo(c.mMessenger);
                    return;
                }
            }
        }
    }

    /**
     * We dont get notifications of clients that have gone away.
     * We detect this actively when services are added and throw
     * them away.
     *
     * TODO: This can be done better with full async channels.
     */
    private void clearClientDeadChannels() {
        ArrayList<Messenger> deadClients = new ArrayList<Messenger>();

        for (ClientInfo c : mClientInfoList.values()) {
            Message msg = Message.obtain();
            msg.what = WifiP2pManager.PING;
            msg.arg1 = 0;
            msg.arg2 = 0;
            msg.obj = null;
            try {
                c.mMessenger.send(msg);
            } catch (RemoteException e) {
                if (DBG) logd("detect dead channel");
                deadClients.add(c.mMessenger);
            }
        }

        for (Messenger m : deadClients) {
            clearClientInfo(m);
        }
    }

    /**
     * Return the specified ClientInfo.
     * @param m Messenger
     * @param createIfNotExist if true and the specified channel info does not exist,
     * create new client info.
     * @return the specified ClientInfo.
     */
    private ClientInfo getClientInfo(Messenger m, boolean createIfNotExist) {
        ClientInfo clientInfo = mClientInfoList.get(m);

        if (clientInfo == null && createIfNotExist) {
            if (DBG) logd("add a new client");
            clientInfo = new ClientInfo(m);
            mClientInfoList.put(m, clientInfo);
        }

        return clientInfo;
    }

    ///M:@{
    /*M: ALPS00677009: broadcast the group removed reason*/
    private void sendP2pConnectionChangedBroadcast(P2pStatus reason) {
        if (DBG) logd("sending p2p connection changed broadcast, reason = " + reason + 
            ", mGroup: " + mGroup +
            ", p2pOperFreq: " + p2pOperFreq);
        Intent intent = new Intent(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                | Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.putExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, new WifiP2pInfo(mWifiP2pInfo));
        intent.putExtra(WifiP2pManager.EXTRA_NETWORK_INFO, new NetworkInfo(mNetworkInfo));
        intent.putExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP, new WifiP2pGroup(mGroup));
        intent.putExtra("p2pOperFreq", p2pOperFreq);

        if (P2pStatus.NO_COMMON_CHANNEL == reason) {
            intent.putExtra("reason=", 7);
        } else if (P2pStatus.MTK_EXPAND_02 == reason) {
            if (DBG) logd("channel conflict, user decline, broadcast with reason=-3");
            intent.putExtra("reason=", -3);
        } else if (P2pStatus.MTK_EXPAND_01 == reason) {
            if (DBG) logd("[wfd sink/source] broadcast with reason=-2");
            intent.putExtra("reason=", -2);
        } else {
            intent.putExtra("reason=", -1);
        }

        mGroupRemoveReason = P2pStatus.UNKNOWN;
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
        mWifiChannel.sendMessage(WifiP2pServiceImpl.P2P_CONNECTION_CHANGED,
                new NetworkInfo(mNetworkInfo));
    }

    //ALPS01212893: for poor link: wifi p2p Tx broadcast
    private void sendP2pTxBroadcast(boolean bStart) {
        if (DBG) logd("sending p2p Tx broadcast: " + bStart);
        Intent intent = new Intent("com.mediatek.wifi.p2p.Tx");
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                | Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.putExtra("start", bStart);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    //M: wfd sink support
    private void sendP2pGOandGCRequestConnectBroadcast() {
        if (DBG) logd("sendP2pGOandGCRequestConnectBroadcast");
        Intent intent = new Intent("com.mediatek.wifi.p2p.GO.GCrequest.connect");
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                | Intent.FLAG_RECEIVER_REPLACE_PENDING);

        WifiP2pDevice dev = mPeers.get(mSavedPeerConfig.deviceAddress);
        if (null != dev && null != dev.deviceName) {
            intent.putExtra("deviceName", dev.deviceName);
        } else {
            intent.putExtra("deviceName", "wifidisplay source");
        }
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    //M: enhance frequency conflict
    private void sendP2pOPChannelBroadcast() {
        if (DBG) logd("sendP2pOPChannelBroadcast: OperFreq = " + p2pOperFreq);
        Intent intent = new Intent("com.mediatek.wifi.p2p.OP.channel");
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                | Intent.FLAG_RECEIVER_REPLACE_PENDING);
        
        intent.putExtra("p2pOperFreq", p2pOperFreq);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    //M: frequency conflict notify
    private void sendP2pFreqConflictBroadcast() {
        if (DBG) logd("sendP2pFreqConflictBroadcast");
        Intent intent = new Intent("com.mediatek.wifi.p2p.freq.conflict");
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                | Intent.FLAG_RECEIVER_REPLACE_PENDING);
        
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    // return if it is fast connect GC or not
    private boolean setFastConnectInfoOnGroupTermination() {
        ///M: ALPS01000113: to notify native layer Beam+ is working  @{
        if (true == mBeamPlusStart) {
            mBeamPlusStart = false;
            mWifiNative.p2pBeamPlus(0); //0: stop
        }
        ///@}
        //wifi direct fast connect group terminate
        if (mRestartFastConnectAsGc) {
            mGoFastConnectEnaled = false;
            mRestartFastConnectAsGc = false;
            return true;

        } else if (mRestartFastConnectAsGo) {
            mGcFastConnectEnaled = false;
            mFoundGo = false;
            mFoundGoTimeOut = false;
            mRestartFastConnectAsGo = false;
            return false;

        } else {
            mGcFastConnectEnaled = false;
            mGoFastConnectEnaled = false;
            mFoundGo = false;
            mFoundGoTimeOut = false;
            mRestartFastConnectAsGc = false;
            mRestartFastConnectAsGo = false;
            return false;

        }
    }

    public void setFastConnectInfo(int role) {
        ///M: ALPS01000113: to notify native layer Beam+ is working  @{
        mBeamPlusStart = true;
        mWifiNative.p2pBeamPlus(1); //1: start
        ///@}
        int id = mWifiNative.addNetwork();
        mFastConnectInfo.networkId = id;
        mWifiNative.setNetworkVariable(id, "ssid", "\"" + mFastConnectInfo.ssid + "\"");
        mWifiNative.setNetworkVariable(id, "key_mgmt", "WPA-PSK");
        mWifiNative.setNetworkVariable(id, "psk", "\"" + mFastConnectInfo.psk + "\"");
        mWifiNative.setNetworkVariable(id, "proto", "RSN");
        mWifiNative.setNetworkVariable(id, "pairwise", "CCMP");
        mWifiNative.setNetworkVariable(id, "auth_alg", "OPEN");
        mWifiNative.setNetworkVariable(id, "disabled", "2");

        if (WifiP2pManager.FAST_CONNECT_AS_GO == role) {
            mWifiNative.setNetworkVariable(id, "mode", "3");
            mWifiNative.p2pSetBssid(id, mThisDevice.deviceAddress);
            sendMessage(WifiP2pManager.DISCOVER_PEERS);
        } else {
            mWifiNative.setNetworkVariable(id, "mode", "0");
            mWifiNative.p2pSetBssid(id, mFastConnectInfo.deviceAddress);
            //mWifiNative.setNetworkVariable(id, "bssid", mFastConnectInfo.deviceAddress);
            ///M: ALPS01480286: to revomve fast scan twice when GC can't find GO  @{
            //sendMessage(WifiP2pManager.FAST_DISCOVER_PEERS);
            clearSupplicantServiceRequest();
            if (mWifiNative.p2pFind(FAST_DISCOVER_TIMEOUT_S)) {
                sendP2pDiscoveryChangedBroadcast(true);
            }
            ///@}
        }
        logd("setFastConnectInfo(): role = " + role + "\n FastConnectInfo = " + mFastConnectInfo);
        sendMessage(role);
    }

    private WifiP2pDevice p2pGoGetSta(WifiP2pDevice p2pDev, String p2pMAC) {
        if (null == p2pMAC || null == p2pDev) {
            loge("gc or gc mac is null");
            return null;
        }

        p2pDev.deviceAddress = p2pMAC;
        String p2pSta = mWifiNative.p2pGoGetSta(p2pMAC);
        if (null == p2pSta)    return p2pDev;
        logd("p2pGoGetSta() return: " + p2pSta);

        String[] tokens = p2pSta.split("\n");
        for (String token : tokens) {
            if (token.startsWith("p2p_device_name=")) {
                String[] nameValue = token.split("=");
                p2pDev.deviceName = nameValueAssign(nameValue, p2pDev.deviceName);
            } else if (token.startsWith("p2p_primary_device_type=")) {
                String[] nameValue = token.split("=");
                p2pDev.primaryDeviceType = nameValueAssign(nameValue, p2pDev.primaryDeviceType);
            } else if (token.startsWith("p2p_group_capab=")) {
                String[] nameValue = token.split("=");
                p2pDev.groupCapability = nameValueAssign(nameValue, p2pDev.groupCapability);
            } else if (token.startsWith("p2p_dev_capab=")) {
                String[] nameValue = token.split("=");
                p2pDev.deviceCapability = nameValueAssign(nameValue, p2pDev.deviceCapability);
            }  else if (token.startsWith("p2p_config_methods=")) {
                String[] nameValue = token.split("=");
                p2pDev.wpsConfigMethodsSupported = nameValueAssign(nameValue, p2pDev.wpsConfigMethodsSupported);
            }
        } //for

        return p2pDev;
    }

    private String nameValueAssign(String[] nameValue, String string) {
        if (null == nameValue || 2 != nameValue.length) {
            return null;
        } else {
            return nameValue[1];
        }
    }

    private int nameValueAssign(String[] nameValue, int integer) {
        if (null == nameValue || 2 != nameValue.length) {
            return 0;
        } else {
            if (null != nameValue[1]) {
                return WifiP2pDevice.parseHex(nameValue[1]);
            } else {
                return 0;
            }
        }
    }

    private int nameValueAssign(String[] nameValue, int integer, int base) {
        if (null == nameValue || 2 != nameValue.length) {
            return 0;
        } else {
            if (null != nameValue[1] && 0 != base) {
                return Integer.parseInt(nameValue[1], base);
            } else {
                return 0;
            }
        }
    }

    private void setWifiOn_WifiAPOff() {
        if (null == mWifiManager) {
            mWifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        }

        int wifiApState = mWifiManager.getWifiApState();
        if ((wifiApState == WifiManager.WIFI_AP_STATE_ENABLING) ||
                (wifiApState == WifiManager.WIFI_AP_STATE_ENABLED)) {
            mWifiManager.setWifiApEnabled(null, false);
        }

        logd("call WifiManager.stopReconnectAndScan() and WifiManager.setWifiEnabled()");
        mWifiManager.stopReconnectAndScan(true, 0);
        mWifiManager.setWifiEnabled(true);
    }

    /* get peer IP address */
    public String getPeerIpAddress(String deviceAddress) {
        logd("getPeerIpAddress deviceAddress=" + deviceAddress);
        if (deviceAddress == null) {
            return null;
        }

        if (null == mGroup) {
            loge("mGroup is null!");
            return null;
        }

        //logd("getPeerIpAddress mThisDevice.isGroupOwner()=" + mThisDevice.isGroupOwner());
        if (!mGroup.isGroupOwner()) {
            if (mGroup.getOwner().deviceAddress != null &&
                deviceAddress.equals(mGroup.getOwner().deviceAddress)) {
                logd("getPeerIpAddress goIpAddress=" + mGroup.getOwner().deviceIP);
                return mGroup.getOwner().deviceIP;
            } else {
                loge("getPeerIpAddress goIpAddress=null");
                return null;
            }
        }

        /** DHCP_INFO_FILE
         * Example:
         *  1393274309 02:08:22:8c:8f:0c 192.168.49.67 android-64e4f3ae3c5378aa *
         */
        FileInputStream fileStream = null;
        try {
            fileStream =  new FileInputStream(DHCP_INFO_FILE);
            DataInputStream in = new DataInputStream(fileStream);
            BufferedReader br = new BufferedReader(new InputStreamReader(in));
            String str = br.readLine();
            String lastOneIP = null;

            //logd("getClientIp Read Message = " + str);
            while ((str != null) && (str.length() != 0)) {
                //logd("getClientIp read a line ok str = " + str);
                String[] fields = str.split(" ");
                //for (String s : fields) {
                    //logd("getClientIp fields = " + s);
                //}
                if (fields.length > 3) {
                    //logd("getClientIp get IP address = " + fields[2]);
                    str = fields[2];
                } else {
                    str = null;
                }

                if (null != str && null != fields[1] &&
                    fields[1].indexOf(deviceAddress) != -1) {
                    logd("getClientIp mac matched, get IP address = " + str);
                    return str;
                } else {
                    lastOneIP = str;
                }
                str = br.readLine();
            } //while()

            //for interface MAC and device MAC is difference case
            loge("getClientIp interface MAC and device MAC is difference, get IP address = " + lastOneIP);
            return lastOneIP;

        } catch (IOException e) {
            loge("Exception: " + e);

        } finally {
            if (fileStream != null) {
                try {
                    fileStream.close();
                    //logd("getClientIp close file OK!");
                } catch (IOException e) {
                    loge("close file met IOException: " + e);
                }
            }

        }

        loge("getClientIp found nothing");
        return null;
    }

    private boolean isGroupRemoved() {
        boolean removed = true;

        for (WifiP2pDevice d : mPeers.getDeviceList()) {
            if (!mThisDevice.deviceAddress.equals(d.deviceAddress) &&
                WifiP2pDevice.CONNECTED == d.status) {
                removed = false;
            }
        }
        if (DBG) logd("isGroupRemoved(): " + removed);
        return removed;
    }

    private void resetWifiP2pConn() {
        //ALPS01807734: restore state for both Created and Creating case
        if (null != mGroup) {
            mWifiNative.p2pGroupRemove(mInterface);

        } else if (getHandler().hasMessages(GROUP_CREATING_TIMED_OUT)) {
            //mWifiNative.p2pCancelConnect();
            sendMessage(WifiP2pManager.CANCEL_CONNECT);

        }

    }

    private void p2pConfigWfdSink() {
        resetWifiP2pConn();

        mWifiNative.setDeviceType("8-0050F204-2");
        //ALPS01511867: disable DEVICE_CAPAB_INVITATION_PROCEDURE
        String result = mWifiNative.p2pGetDeviceCapa();
        if (result.startsWith("p2p_dev_capa=")) {
            String[] nameValue = result.split("=");
            mDeviceCapa = nameValueAssign(nameValue, mDeviceCapa, 10);
        } else {
            mDeviceCapa = -1;
        }
        if (DBG) logd("[wfd sink] p2pConfigWfdSink() ori deviceCapa = " + mDeviceCapa);

        if (0 < mDeviceCapa) {
            String DeviceCapa_local = (Integer.valueOf(mDeviceCapa) & 0xDF) + "";
            mWifiNative.p2pSetDeviceCapa(DeviceCapa_local);
            if (DBG) logd("[wfd sink] p2pConfigWfdSink() after: " + mWifiNative.p2pGetDeviceCapa());
        }
    }

    private void p2pUnconfigWfdSink() {
        resetWifiP2pConn();

        mWifiNative.setDeviceType(mThisDevice.primaryDeviceType);
        if (0 < mDeviceCapa) {
            mWifiNative.p2pSetDeviceCapa(mDeviceCapa + "");
            if (DBG) logd("[wfd sink] p2pUnconfigWfdSink(): " + mWifiNative.p2pGetDeviceCapa());
        }
    }

    private boolean isDeviceBeWfdSink() {
        if ( SystemProperties.get("ro.mtk_wfd_sink_support").equals("1") ) {
            return null != mThisDevice.wfdInfo &&
                    ((WifiP2pWfdInfo.PRIMARY_SINK == mThisDevice.wfdInfo.getDeviceType()) ||
                    (WifiP2pWfdInfo.SOURCE_OR_PRIMARY_SINK == mThisDevice.wfdInfo.getDeviceType()));
        } else {
            if (DBG) loge("[wfd sink] feature wasn't enabled!");
            return false;
        }

    }

    private boolean isDeviceBeWfdSink_InSession() {
        boolean basicCondition = isDeviceBeWfdSink() && (null!=mGroup);

        if (!basicCondition)    return false;

        //wfd sink GC case
        if (!mGroup.isGroupOwner()) {
            return true;
        //wfd sink GO case
        } else {
            if (1==mGroup.getClientAmount())    return true;
        }
        return false;
    }

    private boolean isDeviceBeWfdSource_InSession() {
        return null != mThisDevice.wfdInfo &&
                ((WifiP2pWfdInfo.WFD_SOURCE == mThisDevice.wfdInfo.getDeviceType()) ||
                (WifiP2pWfdInfo.SOURCE_OR_PRIMARY_SINK == mThisDevice.wfdInfo.getDeviceType())) &&
                false == isGroupRemoved();
    }
    } // end of class P2pStateMachine
    ///@}

    /**
     * Information about a particular client and we track the service discovery requests
     * and the local services registered by the client.
     */
    private class ClientInfo {

        /*
         * A reference to WifiP2pManager.Channel handler.
         * The response of this request is notified to WifiP2pManager.Channel handler
         */
        private Messenger mMessenger;

        /*
         * A service discovery request list.
         */
        private SparseArray<WifiP2pServiceRequest> mReqList;

        /*
         * A local service information list.
         */
        private List<WifiP2pServiceInfo> mServList;

        private ClientInfo(Messenger m) {
            mMessenger = m;
            mReqList = new SparseArray();
            mServList = new ArrayList<WifiP2pServiceInfo>();
        }
    }

    /** M: NFC Float II @{ */
    /**
     * Convert hex string to byte[]
     * @param hexString the hex string
     * @return byte[]
     */
    private byte[] hexStringToBytes(String hexString) {
        if (hexString == null || hexString.equals("")) {
            return null;
        }
        int length = hexString.length() / 2;
        byte[] d = new byte[length];
        for (int i = 0; i < length; i++) {
            d[i] = (byte) Integer.parseInt(hexString.substring(2 * i, 2 * i + 2), 16);
        }
        return d;
    }

    private String bytesToHexString(byte[] src) {
        StringBuilder stringBuilder = new StringBuilder("");
        if (src == null || src.length <= 0) {
            return null;
        }
        for (int i = 0; i < src.length; i++) {
            int v = src[i] & 0xFF;
            String hv = Integer.toHexString(v);
            if (hv.length() < 2) {
                stringBuilder.append(0);
            }
            stringBuilder.append(hv);
        }
        return stringBuilder.toString().toUpperCase();
    }

    private void sendNfcRequestTokenBroadcast(String token) {
        Intent intent = new Intent(WifiP2pServiceImpl.MTK_NFC_WFA_P2P_HR_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiP2pServiceImpl.MTK_NFC_WFA_P2P_EXTRA_HR_P2P_DEV_INFO, hexStringToBytes(token));
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void sendNfcSelectTokenBroadcast(String token) {
        Intent intent = new Intent(WifiP2pServiceImpl.MTK_NFC_WFA_TAG_WRITE_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiP2pServiceImpl.MTK_NFC_WFA_TAG_EXTRA_DEV_INFO, hexStringToBytes(token));
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void sendNfcConfigTokenBroadcast(String token) {
        Intent intent = new Intent(WifiP2pServiceImpl.MTK_NFC_WFA_CFG_TAG_WRITE_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiP2pServiceImpl.MTK_NFC_WFA_TAG_EXTRA_DEV_INFO, hexStringToBytes(token));
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void sendNfcWpsConfigTokenBroadcast(String token) {
        Intent intent = new Intent(WifiP2pServiceImpl.MTK_WPS_NFC_TESTBED_P2P_AUTOGO_AS_SEL_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiP2pServiceImpl.MTK_WPS_NFC_TESTBED_EXTRA_PASSWORD, hexStringToBytes(token));
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    public byte[] getP2pHandoverSelectToken() {
        enforceAccessPermission();
        if (!mMtkWpsp2pnfcSupport) {
            return null;
        }
        String token = mP2pStateMachine.mWifiNative.getNfcHandoverSelect();
        Slog.d(TAG, "getP2pHandoverSelectToken, token:" + token);
        if (!TextUtils.isEmpty(token) && !token.equals(UNKNOWN_COMMAND)) {
            return hexStringToBytes(token);
        } else {
            return null;
        }
    }


    /** @} */
}
