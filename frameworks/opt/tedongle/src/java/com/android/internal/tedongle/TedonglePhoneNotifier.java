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

package com.android.internal.tedongle;

//import android.net.LinkCapabilities;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.LinkProperties;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.tedongle.CellInfo;
import android.tedongle.ServiceState;
import android.tedongle.TelephonyManager;
import android.tedongle.VoLteServiceState;
import android.tedongle.DataConnectionRealTimeInfo;
import android.util.Log;
import android.os.AsyncResult;
import android.os.Process; // NFC SEEK
import android.os.UserHandle;
import android.tedongle.SignalStrength;
import android.os.Registrant;
import android.os.RegistrantList;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;


import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import com.android.internal.tedongle.ITedongle;



//import com.android.internal.tedongle.ITelephonyRegistry;

import java.util.List;

/**
 * broadcast intents
 */
public class TedonglePhoneNotifier implements PhoneNotifier {

    static final String LOG_TAG = "3GD-GSM";
    private static final boolean DBG = true;

    private Context mContext;
    
    private ITedongle mTedongleService;

    private BroadcastReceiver mDataConnChangeReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            if (TelephonyIntents.ACTION_ANY_DATA_CONNECTION_STATE_CHANGED_MOBILE.equals(intent.getAction())) {
                log("Received ACTION_ANY_DATA_CONNECTION_STATE_CHANGED_MOBILE");
                intent.setAction(TelephonyIntents.ACTION_ANY_DATA_CONNECTION_STATE_CHANGED);
                mContext.sendStickyBroadcast(intent);
            }
        }        
    };


    /*package*/
    TedonglePhoneNotifier(Context mct) {

        mContext = mct;
 
    }

    

    public void notifyPhoneState(Phone sender) {

    }

    public void notifyServiceState(Phone sender) {
       
    }

    public void notifyVoLteServiceStateChanged(Phone sender, VoLteServiceState lteState) {

    }

    public void notifyDataConnectionRealTimeInfo(Phone sender, DataConnectionRealTimeInfo dcRtInfo) {

    }

    public void notifyPreciseDataConnectionFailed(Phone sender, String reason, String apnType,
            String apn, String failCause) {

    }

    public void notifyDisconnectCause(int cause, int preciseCause) {
        
    }

    public void notifyPreciseCallState(Phone sender) {

    }

    public void notifySignalStrength(Phone sender) {

        mTedongleService = ITedongle.Stub.asInterface(ServiceManager.getService("tedongleservice"));
        if (mTedongleService != null) {
            try {
                Log.d(LOG_TAG, "tedonglePhoneNotifier->notifySignalStrength!");
                mTedongleService.NotifySignalStrength(sender.getSignalStrength());
            } catch (RemoteException ex) {
                //something wrong
            }
        }

    }

    private void broadcastSignalStrengthChanged(SignalStrength signalStrength) {

        Intent intent = new Intent(TelephonyIntents.ACTION_SIGNAL_STRENGTH_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        Bundle data = new Bundle();
        signalStrength.fillInNotifierBundle(data);
        intent.putExtras(data);
        mContext.sendStickyBroadcast(intent);
    }
    public void notifyMessageWaitingChanged(Phone sender) {

    }

    public void notifyCallForwardingChanged(Phone sender) {
        
    }

    public void notifyDataActivity(Phone sender) {
        
            //mRegistry.notifyDataActivity(convertDataActivityState(sender.getDataActivityState()));
        
            // system process is dead
        
    }

    public void notifyDataConnection(Phone sender, String reason, String apnType,
            PhoneConstants.DataState state) {
        if (apnType.equals("default")) {
            doNotifyDataConnection(sender, reason, apnType, state);
        }
    }

    private void doNotifyDataConnection(Phone sender, String reason, String apnType,
            PhoneConstants.DataState state) {
        // TODO
        // use apnType as the key to which connection we're talking about.
        // pass apnType back up to fetch particular for this one.
        TelephonyManager telephony = TelephonyManager.getDefault();
        LinkProperties linkProperties = null;
        NetworkCapabilities linkCapabilities = null;
        boolean roaming = false;

        if (state == PhoneConstants.DataState.CONNECTED) {
            linkProperties = sender.getLinkProperties(apnType);
            linkCapabilities = sender.getNetworkCapabilities(apnType);
        }
        ServiceState ss = sender.getServiceState();
        if (ss != null) roaming = ss.getRoaming();

        //int networkType = TelephonyManager.NETWORK_TYPE_UNKNOWN;
        //networkType = ((telephony!=null) ? telephony.getNetworkType() : TelephonyManager.NETWORK_TYPE_UNKNOWN);

        broadcastDataConnectionStateChanged(convertDataState(state),
                                            sender.isDataConnectivityPossible(apnType),
                                            reason,
                                            sender.getActiveApnHost(apnType),
                                            apnType,
                                            linkProperties,
                                            linkCapabilities,
                                            roaming);
        

    }

    private void broadcastDataConnectionStateChanged(int state,
            boolean isDataConnectivityPossible,
            String reason, String apn, String apnType, LinkProperties linkProperties,
            NetworkCapabilities linkCapabilities, boolean roaming) {
        // Note: not reporting to the battery stats service here, because the
        // status bar takes care of that after taking into account all of the
        // required info.
        Intent intent = new Intent(TelephonyIntents.ACTION_ANY_DATA_CONNECTION_STATE_CHANGED_MOBILE);
        intent.putExtra(PhoneConstants.STATE_KEY, TedonglePhoneNotifier.convertDataState(state).toString());
        if (!isDataConnectivityPossible) {
            intent.putExtra(PhoneConstants.NETWORK_UNAVAILABLE_KEY, true);
        }
        if (reason != null) {
            intent.putExtra(PhoneConstants.STATE_CHANGE_REASON_KEY, reason);
        }
        if (linkProperties != null) {
            intent.putExtra(PhoneConstants.DATA_LINK_PROPERTIES_KEY, linkProperties);
            String iface = linkProperties.getInterfaceName();
            if (iface != null) {
                intent.putExtra(PhoneConstants.DATA_IFACE_NAME_KEY, iface);
            }
        }
        if (linkCapabilities != null) {
            intent.putExtra(PhoneConstants.DATA_NETWORK_CAPABILITIES_KEY, linkCapabilities);
        }
        if (roaming) intent.putExtra(PhoneConstants.DATA_NETWORK_ROAMING_KEY, true);

        intent.putExtra(PhoneConstants.DATA_APN_KEY, apn);
        intent.putExtra(PhoneConstants.DATA_APN_TYPE_KEY, apnType);


        // To make sure MobileDataStateTracker can receive this intent firstly,
        // using oredered intent ACTION_ANY_DATA_CONNECTION_STATE_CHANGED_MOBILE
        // redirect original intent. -->
        mContext.sendOrderedBroadcast(intent, null, mDataConnChangeReceiver , null, 0, null, null);
        // <--
    }

    public void notifyDataConnectionFailed(Phone sender, String reason, String apnType) {

        broadcastDataConnectionFailed(reason, apnType);
    
       
    }

    private void broadcastDataConnectionFailed(String reason, String apnType) {
        
        Intent intent = new Intent(TelephonyIntents.ACTION_DATA_CONNECTION_FAILED);
        intent.putExtra(PhoneConstants.FAILURE_REASON_KEY, reason);
        intent.putExtra(PhoneConstants.DATA_APN_TYPE_KEY, apnType);
        mContext.sendStickyBroadcast(intent);
        
    }

    public void notifyCellLocation(Phone sender) {
        
    }

    public void notifyCellInfo(Phone sender, List<CellInfo> cellInfo) {
       
    }

    public void notifyOtaspChanged(Phone sender, int otaspMode) {
        
    }

    private void log(String s) {
        Log.d(LOG_TAG, "[TedonglePhoneNotifier] " + s);
    }

    /**
     * Convert the {@link PhoneConstants.State} enum into the TelephonyManager.CALL_STATE_*
     * constants for the public API.
     */
    public static int convertCallState(PhoneConstants.State state) {
        switch (state) {
            case RINGING:
                return TelephonyManager.CALL_STATE_RINGING;
            case OFFHOOK:
                return TelephonyManager.CALL_STATE_OFFHOOK;
            default:
                return TelephonyManager.CALL_STATE_IDLE;
        }
    }

    /**
     * Convert the TelephonyManager.CALL_STATE_* constants into the
     * {@link PhoneConstants.State} enum for the public API.
     */
    public static PhoneConstants.State convertCallState(int state) {
        switch (state) {
            case TelephonyManager.CALL_STATE_RINGING:
                return PhoneConstants.State.RINGING;
            case TelephonyManager.CALL_STATE_OFFHOOK:
                return PhoneConstants.State.OFFHOOK;
            default:
                return PhoneConstants.State.IDLE;
        }
    }


    /**
     * Convert the {@link DataState} enum into the TelephonyManager.DATA_* constants
     * for the public API.
     */
    public static int convertDataState(PhoneConstants.DataState state) {
        switch (state) {
            case CONNECTING:
                return TelephonyManager.DATA_CONNECTING;
            case CONNECTED:
                return TelephonyManager.DATA_CONNECTED;
            case SUSPENDED:
                return TelephonyManager.DATA_SUSPENDED;
            default:
                return TelephonyManager.DATA_DISCONNECTED;
        }
    }

    /**
     * Convert the TelephonyManager.DATA_* constants into {@link DataState} enum
     * for the public API.
     */
    public static PhoneConstants.DataState convertDataState(int state) {
        switch (state) {
            case TelephonyManager.DATA_CONNECTING:
                return PhoneConstants.DataState.CONNECTING;
            case TelephonyManager.DATA_CONNECTED:
                return PhoneConstants.DataState.CONNECTED;
            case TelephonyManager.DATA_SUSPENDED:
                return PhoneConstants.DataState.SUSPENDED;
            default:
                return PhoneConstants.DataState.DISCONNECTED;
        }
    }

    /**
     * Convert the {@link DataState} enum into the TelephonyManager.DATA_* constants
     * for the public API.
     */
    public static int convertDataActivityState(Phone.DataActivityState state) {
        switch (state) {
            case DATAIN:
                return TelephonyManager.DATA_ACTIVITY_IN;
            case DATAOUT:
                return TelephonyManager.DATA_ACTIVITY_OUT;
            case DATAINANDOUT:
                return TelephonyManager.DATA_ACTIVITY_INOUT;
            case DORMANT:
                return TelephonyManager.DATA_ACTIVITY_DORMANT;
            default:
                return TelephonyManager.DATA_ACTIVITY_NONE;
        }
    }

    /**
     * Convert the TelephonyManager.DATA_* constants into the {@link DataState} enum
     * for the public API.
     */
    public static Phone.DataActivityState convertDataActivityState(int state) {
        switch (state) {
            case TelephonyManager.DATA_ACTIVITY_IN:
                return Phone.DataActivityState.DATAIN;
            case TelephonyManager.DATA_ACTIVITY_OUT:
                return Phone.DataActivityState.DATAOUT;
            case TelephonyManager.DATA_ACTIVITY_INOUT:
                return Phone.DataActivityState.DATAINANDOUT;
            case TelephonyManager.DATA_ACTIVITY_DORMANT:
                return Phone.DataActivityState.DORMANT;
            default:
                return Phone.DataActivityState.NONE;
        }
    }

    public interface IDataStateChangedCallback {
        void onDataStateChanged(long subId, String state, String reason, String apnName,
            String apnType, boolean unavailable);
    }
}
