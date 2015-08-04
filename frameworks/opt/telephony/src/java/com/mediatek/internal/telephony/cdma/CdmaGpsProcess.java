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

package com.mediatek.internal.telephony.cdma;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;

import android.util.Log;

import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.cdma.CDMAPhone;

/**
 * @hide
 */
public class CdmaGpsProcess extends Handler {
    static final String LOG_TAG = "CDMA_GPS";

    /* indicator for gps wap service status */
    private static final int GPS_WAP_SERVICE_STATUS_NO_OPT = 0;
    private static final int GPS_WAP_SERVICE_STATUS_STARTING = 1;
    private static final int GPS_WAP_SERVICE_STATUS_STOPPING = 2;

    /* added by wsong, for gps mpc ip & port address setting notify */
    public static final String INTENT_VIA_GPS_MPC_SETTING_NOTIFY =
        "com.android.internal.telephony.via-gps-mpc-setting-notify";
    public static final String INTENT_VIA_GPS_MPC_SETTING_NOTIFY_IP_EXTRA =
        "via-gps-mpc-setting-ip";
    public static final String INTENT_VIA_GPS_MPC_SETTING_NOTIFY_PORT_EXTRA =
        "via-gps-mpc-setting-port";

    /* added by wsong, for gps mpc ip & port address setting result notify */
    public static final String INTENT_VIA_GPS_MPC_SETTING_RESULT_NOTIFY =
        "com.android.internal.telephony.via-gps-mpc-setting-result-notify";
    public static final String INTENT_VIA_GPS_MPC_SETTING_RESULT_NOTIFY_EXTRA =
        "via-gps-mpc-setting-result";

    /* added by wsong, for gps fix result notify */
    public static final String INTENT_VIA_GPS_FIX_RESULT_NOTIFY =
        "com.android.internal.telephony.via-gps-fix-result-notify";
    public static final String INTENT_VIA_GPS_FIX_RESULT_NOTIFY_EXTRA =
        "via-gps-fix-result";

    /*for agps test*/
    public static final String INTENT_VIA_SIMU_REQUEST =
        "com.android.internal.telephony.via-simu-request";
    public static final String EXTRAL_VIA_SIMU_REQUEST_PARAM =
        "com.android.internal.telephony.via-simu-request-param";

    private static final int EVENT_GPS_APPLY_WAP_SRV             = 1;
    private static final int EVENT_GPS_MPC_SET_COMPLETE          = 2;

    /* VIA GPS event */
    private static final int REQUEST_DATA_CONNECTION = 0;
    private static final int CLOSE_DATA_CONNECTION   = 1;
    private static final int GPS_START               = 2;
    private static final int GPS_FIX_RESULT          = 3;
    private static final int GPS_STOP                = 4;

    /* Instance Variables */
    private Context mContext;
    private CommandsInterface mCM;
    private CDMAPhone mPhone;

    private int mSimId;

    /* Connectivity Manager instance */
    private ConnectivityManager mConnectivityManager;

    /* indicator for data connection status */
    private int mDataCallState;
    private static final int DATACALL_DISCONNECTED = 0; /*disconnected*/
    private static final int DATACALL_CONNECTED = 1; /*connected*/
    private static final int DATACALL_WIFI = 2; /*wifi connected*/
    private static final int DATACALL_OTHER = 3; /*other datacall connected*/

    /* indicator for the gps wap data service status */
    private int mGpsWapSrvStatus;

    /* indicator for the state of gps wap service is connected */
    private boolean mGpsWapConnConnected;

    /*indicator for gps service waiting for Datacall Connectted*/
    private boolean mWaitforDataConnecting;

    /**
     * @hide
     *
     * @param context context instance
     * @param phone The phone instance
     * @param ci The command interface
     * @param simId Sim id used to do data connection
     */
    public CdmaGpsProcess(Context context, CDMAPhone phone, CommandsInterface ci, int simId) {
        mContext = context;
        mPhone = phone;
        mCM = ci;
        mSimId = simId;
        mConnectivityManager = (ConnectivityManager) context.getSystemService(
                                Context.CONNECTIVITY_SERVICE);
    }

    /**
     * @hide
     *
     * @param context context instance
     * @param phone The phone instance
     * @param ci The command interface
     */
    public CdmaGpsProcess(Context context, CDMAPhone phone, CommandsInterface ci) {
        this(context, phone, ci, 0/*PhoneConstants.GEMINI_SIM_2*/);
    }

    /**
     * @hide
     */
    public void start() {
        mDataCallState = DATACALL_DISCONNECTED;
        mGpsWapSrvStatus = GPS_WAP_SERVICE_STATUS_NO_OPT;
        mGpsWapConnConnected = false;

        mCM.registerForViaGpsEvent(this, EVENT_GPS_APPLY_WAP_SRV, null);
        /* Register for GPS WAP service */
        IntentFilter intentFilter = new IntentFilter();
        //intentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        intentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION_IMMEDIATE);
        intentFilter.addAction(INTENT_VIA_GPS_MPC_SETTING_NOTIFY);
        intentFilter.addAction(INTENT_VIA_SIMU_REQUEST); /*simulate test action*/
        mContext.registerReceiver(mBroadcastReceiver, intentFilter);
    }

    /**
     * @hide
     */
    public void stop() {
        mCM.unregisterForViaGpsEvent(this);
        requestAGPSTcpConnected(0, null);
        mContext.unregisterReceiver(mBroadcastReceiver);
    }

    @Override
    public void handleMessage(Message msg) {
        AsyncResult ar;

        switch(msg.what) {
            case EVENT_GPS_APPLY_WAP_SRV: {
                ar = (AsyncResult) msg.obj;
                int[] data = (int[]) (ar.result);
                viaGpsEventHandler(data);
            }
            break;

            case EVENT_GPS_MPC_SET_COMPLETE: {
                ar = (AsyncResult) msg.obj;
                boolean success = (ar.exception == null) ? true : false;
                onGpsMpcSetComplete(success);
            }
            break;

            default: {
                super.handleMessage(msg);
            }
        }
    }

    private void viaGpsEventHandler(int[] data) {
        int event = data[0];
        int gpsStatus = data[1];

        switch (event) {
        case REQUEST_DATA_CONNECTION:
            Log.d(LOG_TAG, "[VIA] GPS Request data connection");
            startGpsWapService();
            break;
        case CLOSE_DATA_CONNECTION:
            stopGpsWapService();
            break;
        case GPS_START:
            break;
        case GPS_FIX_RESULT:
            onFixResultHandler(gpsStatus);
            break;
        case GPS_STOP:
        default:
            break;
        }
    }

    private void onFixResultHandler(int gpsStatus) {
        Log.d(LOG_TAG, "[VIA] onFixResultHandler, gpsStatus = " + gpsStatus);
        Intent intent = new Intent(INTENT_VIA_GPS_FIX_RESULT_NOTIFY);
        intent.putExtra(INTENT_VIA_GPS_FIX_RESULT_NOTIFY_EXTRA, gpsStatus);
        mContext.sendBroadcast(intent);
    }
    private void stopGpsWapService() {
        Log.d(LOG_TAG, "[VIA] stopGpsWapService()");
        mGpsWapSrvStatus = GPS_WAP_SERVICE_STATUS_STOPPING;
     //   if (SystemProperties.get("ro.mtk_gemini_support").equals("1")) {
     //       mConnectivityManager.stopUsingNetworkFeatureGemini(
     //             ConnectivityManager.TYPE_MOBILE, Phone.FEATURE_ENABLE_MMS, mSimId);
     //   }else{
            mConnectivityManager.stopUsingNetworkFeature(ConnectivityManager.TYPE_MOBILE,
                    Phone.FEATURE_ENABLE_MMS);
     //   }
    }
    private PhoneConstants.DataState getDataConnectState() {
        PhoneConstants.DataState defaultState = mPhone.getDataConnectionState(
            PhoneConstants.APN_TYPE_DEFAULT);
        Log.d(LOG_TAG, "[VIA] DataCall Connection apn default state = " + defaultState);
        PhoneConstants.DataState  mmsState = mPhone.getDataConnectionState(
            PhoneConstants.APN_TYPE_MMS);
        Log.d(LOG_TAG, "[VIA] DataCall Connection apn MMS state = " + mmsState);
        PhoneConstants.DataState  suplState = mPhone.getDataConnectionState(
            PhoneConstants.APN_TYPE_SUPL);
        Log.d(LOG_TAG, "[VIA] DataCall Connection apn supl state = " + suplState);
        if (defaultState == PhoneConstants.DataState.CONNECTING ||
                mmsState == PhoneConstants.DataState.CONNECTING ||
                suplState == PhoneConstants.DataState.CONNECTING) {
             return PhoneConstants.DataState.CONNECTING;
        } else if (defaultState == PhoneConstants.DataState.CONNECTED ||
                mmsState == PhoneConstants.DataState.CONNECTED ||
                suplState == PhoneConstants.DataState.CONNECTED) {
           return PhoneConstants.DataState.CONNECTED;
        } else {
           return PhoneConstants.DataState.DISCONNECTED;
        }
    }
    private void startGpsWapService() {
        Log.d(LOG_TAG, "[VIA] startGpsWapService" +
            ", mDataCallState = " + mDataCallState);
        PhoneConstants.DataState dataState = getDataConnectState();
        if (PhoneConstants.DataState.CONNECTING == dataState) {
            Log.d(LOG_TAG, "[VIA] data call is connecting, wait for connected");
            mWaitforDataConnecting = true;
            return;
        }
        // not ctwap data connection has been setuped, exchange the active apn to ctwap
        if (PhoneConstants.DataState.DISCONNECTED == dataState) {
            requestAGPSTcpConnected(mDataCallState, null);
        } else {
            int result;
          //  if (SystemProperties.get("ro.mtk_gemini_support").equals("1")) {
          //      result = mConnectivityManager.startUsingNetworkFeatureGemini(
          //                 ConnectivityManager.TYPE_MOBILE, Phone.FEATURE_ENABLE_MMS, mSimId);
          //  }else{
                result = mConnectivityManager.startUsingNetworkFeature(
                             ConnectivityManager.TYPE_MOBILE, Phone.FEATURE_ENABLE_MMS);
          //  }
            Log.d(LOG_TAG, "[VIA] startGpsWapService() startUsingNetworkFeature(MMS)=" + result);
            if (result == PhoneConstants.APN_ALREADY_ACTIVE) {
                requestAGPSTcpConnected(1, null);
            } else if (result == PhoneConstants.APN_REQUEST_STARTED) {
                mGpsWapSrvStatus = GPS_WAP_SERVICE_STATUS_STARTING;
            } else {
                requestAGPSTcpConnected(0, null);
            }
        }
    }
    private void requestAGPSTcpConnected(int state, Message msg) {
       Log.d(LOG_TAG, "[VIA] requestAGPSTcpConnected(" + state + ")");
       mCM.requestAGPSTcpConnected(state, null);
    }
    private void onGpsMpcSetComplete(boolean success) {
        Log.d(LOG_TAG, "[VIA] onGpsMpcSetComplete, success = " + success);
        Intent intent = new Intent(INTENT_VIA_GPS_MPC_SETTING_RESULT_NOTIFY);
        intent.putExtra(INTENT_VIA_GPS_MPC_SETTING_RESULT_NOTIFY_EXTRA, success);
        mContext.sendBroadcast(intent);
    }

    // Receiver to get message of CONNECTED, DISCONNECTED, FAILED, or NOTREADY
    // about data connection
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            //Log.d(LOG_TAG, "[VIA] onReceive() action="+action);
            if (ConnectivityManager.CONNECTIVITY_ACTION.equals(action)
                || ConnectivityManager.CONNECTIVITY_ACTION_IMMEDIATE.equals(action)) {
               Log.d(LOG_TAG, "[VIA] ConnectivityManager::" + action);
               NetworkInfo info = (NetworkInfo) intent.getExtra(
                                  ConnectivityManager.EXTRA_NETWORK_INFO);
               NetworkInfo.DetailedState detailedState = info.getDetailedState();
               Log.d(LOG_TAG, "[VIA] onDataConnectionStateChanged" +
                        ", state = " + detailedState +
                        ", type = " + info.getType());
               Log.d(LOG_TAG, "[VIA] onDataConnectionStateChanged" +
                        ", mGpsWapSrvStatus = " + mGpsWapSrvStatus);
               Log.d(LOG_TAG, "[VIA] onDataConnectionStateChanged" +
                    ", mGpsWapConnConnected = " + mGpsWapConnConnected +
                        ", mWaitforDataConnecting =" + mWaitforDataConnecting);
              if (detailedState == NetworkInfo.DetailedState.CONNECTED) {
                 //get connected type
                 if (info.getType() == ConnectivityManager.TYPE_WIFI) {
                    mDataCallState = DATACALL_WIFI;
                } else {
                    boolean isCdmaDataCall = true;
                  //  if (SystemProperties.get("ro.mtk_gemini_support").equals("1")) {
                  //      isCdmaDataCall = intent.getIntExtra(
                  //             ConnectivityManager.EXTRA_SIM_ID,mSimId)==mSimId;
                  //  }
                    Log.d(LOG_TAG,
                         "[VIA] onDataConnectionStateChanged Network Type is Cdma Data Call?" +
                          isCdmaDataCall);
                    if (isCdmaDataCall) {
                        mDataCallState = DATACALL_CONNECTED;
                    } else {
                        mDataCallState = DATACALL_OTHER;
                    }
                    Log.d(LOG_TAG, "[VIA] onDataConnectionStateChanged" +
                        ", mDataCallState = " + mDataCallState);
                }
                //continue the last action
                if (mGpsWapSrvStatus == GPS_WAP_SERVICE_STATUS_STARTING) {
                    mGpsWapSrvStatus = GPS_WAP_SERVICE_STATUS_NO_OPT;
                    mGpsWapConnConnected = true;
                    startGpsWapService();
                } else if (mWaitforDataConnecting) {
                    mWaitforDataConnecting = false;
                    startGpsWapService();
                }
              } else if (detailedState == NetworkInfo.DetailedState.DISCONNECTED) {
                mDataCallState = DATACALL_DISCONNECTED;
                if (mGpsWapSrvStatus == GPS_WAP_SERVICE_STATUS_STOPPING &&
                   mGpsWapConnConnected) {
                    requestAGPSTcpConnected(0, null);
                    mGpsWapSrvStatus = GPS_WAP_SERVICE_STATUS_NO_OPT;
                    mGpsWapConnConnected = false;
                }
              }
            } else if (INTENT_VIA_GPS_MPC_SETTING_NOTIFY.equals(action)) {
                String ip = intent.getStringExtra(INTENT_VIA_GPS_MPC_SETTING_NOTIFY_IP_EXTRA);
                String port = intent.getStringExtra(INTENT_VIA_GPS_MPC_SETTING_NOTIFY_PORT_EXTRA);

                Log.d(LOG_TAG, "[VIA] INTENT_VIA_GPS_MPC_SETTING_NOTIFY IP = " +
                      ip + ", PORT = " + port);
                mCM.requestAGPSSetMpcIpPort(ip, port, obtainMessage(EVENT_GPS_MPC_SET_COMPLETE));
            } else if (INTENT_VIA_SIMU_REQUEST.equals(action)) {
                int[] data = {-1, 0};
                data[0] = intent.getIntExtra(EXTRAL_VIA_SIMU_REQUEST_PARAM, -1);
                Log.d(LOG_TAG, "[VIA] INTENT_VIA_SIMU_REQUEST =" + data[0]);
                viaGpsEventHandler(data);
            }
        }
    };
}
