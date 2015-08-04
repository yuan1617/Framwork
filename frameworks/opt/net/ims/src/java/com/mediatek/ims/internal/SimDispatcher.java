package com.mediatek.ims.internal;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;

import android.telephony.Rlog;
import android.telephony.TelephonyManager;

import com.mediatek.ims.ImsAdapter;
import com.mediatek.ims.ImsAdapter.VaSocketIO;
import com.mediatek.ims.ImsAdapter.VaEvent;
import com.mediatek.ims.ImsEventDispatcher;
import com.mediatek.ims.VaConstants;

import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.IPhoneSubInfo;

import com.mediatek.internal.telephony.ITelephonyEx;

/**
 * {@hide}
 *
 * Implement a SIM dispatcher to handle the request from indicated message.
 *
 */
public class SimDispatcher extends Handler implements ImsEventDispatcher.VaEventDispatcher {
    private static final String TAG = "[SimDispatcher]";

    private static final Object mLock = new Object();

    private static final int READ_USIM_COMMAND_IMSI = 0;
    private static final int READ_USIM_COMMAND_PSISMSC = 1;
    private static final int READ_USIM_COMMAND_SMSP = 2;

    private static final int READ_ISIM_COMMAND_IMPI = 0;
    private static final int READ_ISIM_COMMAND_IMPU = 1;
    private static final int READ_ISIM_COMMAND_DOMAIN_NAME = 2;
    private static final int READ_ISIM_COMMAND_PSISMSC = 3;
    private static final int READ_ISIM_SERVICE_TABLE = 4;

    private static final int READ_USIM_COMMAND_DATA_LENGTH = 256;
    private static final int READ_ISIM_COMMAND_DATA_LENGTH = 256;
    private static final int READ_ISIM_COMMAND_DATA_NUM = 5;
    private static final int USIM_AUTH_IMS_AKA_COMMAND_RES_LENGTH = 256;

    private static final int EVENT_DO_SIM_AKA_AUTH = 100;

    private Context mContext;
    private VaSocketIO mSocket;
    private String mSimState = "";
    private String mIsimState = "";

    /**
     * SimDispatcher constructor.
     *
     * @param context the indicated context
     * @param io socket IO
     *
     */
    public SimDispatcher(Context context, VaSocketIO io) {
        mContext = context;
        mSocket = io;

        log("SimDispatcher()");

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        intentFilter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED_MULTI_APPLICATION);
        mContext.registerReceiver(mReceiver, intentFilter);
    }

    /**
     * enableRequest API - currently, do nothing.
     *
     */
    public void enableRequest() {
        log("enableRequest()");
    }

    /**
     * disableRequest API - currently, do nothing.
     *
     */
    public void disableRequest() {
        log("disableRequest()");
    }

    @Override
    public void handleMessage(Message msg) {
        synchronized (mLock) {
            AsyncResult ar = (AsyncResult) msg.obj;
            switch (msg.what) {
                default:
                    log(" Unknown Event " + msg.what);
            }
        }
    }

    private final BroadcastReceiver mReceiver = new  BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            log("[BroadcastReceiver]+");
            String action = intent.getAction();
            int slotId;
            int simType = 0;
            int eventId = 0;
            boolean needToNotify = false;

            if (action.equals(TelephonyIntents.ACTION_SIM_STATE_CHANGED)) {
                String simState = intent.getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE);
                log(" sim State: " + simState);
                mSimState = simState;
                if (simState.equals(IccCardConstants.INTENT_VALUE_ICC_LOADED)) {
                    eventId = VaConstants.MSG_ID_NOTIFY_SIM_READY;
                    simType = 1;
                    needToNotify = true;
                } else if (simState.equals(IccCardConstants.INTENT_VALUE_ICC_ABSENT)) {
                    eventId = VaConstants.MSG_ID_NOTIFY_SIM_ERROR;
                    simType = 0;
                    needToNotify = true;
                }
            } else if (action.equals(TelephonyIntents.ACTION_SIM_STATE_CHANGED_MULTI_APPLICATION)) {
                String isimState = intent.getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE);
                log(" isim State: " + isimState);
                mIsimState = isimState;
                if (mIsimState.equals(IccCardConstants.INTENT_VALUE_ICC_LOADED)) {
                    eventId = VaConstants.MSG_ID_NOTIFY_SIM_READY;
                    simType = 2;
                    needToNotify = true;
                } else if (mIsimState.equals(IccCardConstants.INTENT_VALUE_ICC_ABSENT)) {
                    eventId = VaConstants.MSG_ID_NOTIFY_SIM_READY;
                    simType = 3;
                    needToNotify = true;
                }
            }

            log("eventId:" + eventId + ", simType:" + simType + ", needToNotify:" + needToNotify);

            if (needToNotify) {
                ImsAdapter.VaEvent event = new ImsAdapter.VaEvent(eventId);

                //SIM state
                event.putByte(simType);

                //Session ID
                event.putByte(0);

                //Pad
                event.putByte(0);
                event.putByte(0);

                log("Notify VA for USIM ready.");

                // send the event to va
                mSocket.writeEvent(event);
            }

            log("[BroadcastReceiver]-");
        }
    };

    /**
     * vaEventCallback API - handle request from socket interface.
     *
     * @param event event object from Imsadpater
     *
     */
    public void vaEventCallback(VaEvent event) {
        try {
            int requestId;
            int len;
            String data;
            int transactionId;
            int type;

            requestId = event.getRequestID();
            log("requestId = " + requestId);

            switch (requestId) {
                case VaConstants.MSG_ID_REQUEST_DO_AKA_AUTHENTICATION:
                    int randLen = 0;
                    int autnLen = 0;
                    int family = 0;
                    String strRand = "";
                    String strAuth = "";
                    /*--- Step1. parse request data --- */
                    transactionId = event.getByte();
                    randLen = event.getByte();
                    autnLen = event.getByte();
                    // isIsimPrefer: 0 for USIM prefer, 1 for ISIM prefer
                    // UiccController.APP_FAM_3GPP =  1; //SIM/USIM
                    // UiccController.APP_FAM_3GPP2 = 2; //RUIM/CSIM
                    // UiccController.APP_FAM_IMS   = 3; //ISIM
                    family = ((event.getByte() == 1) ? 3 : 1);

                    log("transaction_id: " + transactionId);
                    log("Rand Len =  " + randLen + "Autn Len = " + autnLen);

                    byte byteRand[] = event.getBytes(32);
                    byte byteAutn[] = event.getBytes(32);

                    log("SIM auth:RAND = " + byteRand + " ,AUTN = " + byteAutn);

                    /*--- Step2. do authentication ---*/
                    // Do authentication by ITelephony interface
                    // Return data: payload + sw1 + sw2 (need to notify user)
                    // FIXME: ANR issue?
                    byte[] response = null;

                    try {
                        response = getITelephonyEx().simAkaAuthentication(
                                0, family, byteRand, byteAutn);
                    } catch (RemoteException ex) {
                        ex.printStackTrace();
                    } catch (NullPointerException ex) {
                        // This could happen before phone restarts due to crashing
                        ex.printStackTrace();
                    }

                    /*--- Step3. send response data ---*/
                    VaEvent resEvent = new ImsAdapter.VaEvent(
                            VaConstants.MSG_ID_RESPONSE_DO_AKA_AUTHENTICATION);

                    //transaction_id
                    resEvent.putByte((byte) transactionId);
                    log("Trans ID = " + transactionId);


                    //isSuccess
                    resEvent.putByte((response == null) ? 0 : 1);

                    //Pad[2]
                    resEvent.putByte(0);
                    resEvent.putByte(0);

                    //Data
                    byte resData[] = new byte[USIM_AUTH_IMS_AKA_COMMAND_RES_LENGTH];
                    if (response != null) {
                        System.arraycopy(response, 0, resData, 0, response.length);
                        resEvent.putBytes(resData);
                        //log("Result = " + IccUtils.bytesToHexString(response.payload))
                    } else {
                        resEvent.putBytes(resData);
                    }

                    mSocket.writeEvent(resEvent);

                    log("MSG_ID_RESPONSE_DO_AKA_AUTHENTICATION response is " + resData);
                    break;

                case VaConstants.MSG_ID_REQUEST_READ_USIM_FILE:
                    transactionId = event.getByte();
                    type = event.getByte();

                    log("readUsimData, type = " + type);
                    readUsimData(type, transactionId);

                    break;

                case VaConstants.MSG_ID_REQUEST_READ_ISIM_FILE:
                    transactionId = event.getByte();
                    type = event.getByte();

                    log("readIsimData, type = " + type);
                    readIsimData(type, transactionId);

                    break;

                case VaConstants.MSG_ID_REQUEST_QUERY_SIM_STATUS:
                    VaEvent responseEvent =
                            new ImsAdapter.VaEvent(VaConstants.MSG_ID_RESPONSE_QUERY_SIM_STATUS);
                    transactionId = event.getByte();
                    log("transaction_id: " + transactionId);

                    //transaction_id
                    responseEvent.putByte(transactionId);

                    log("mSimState: " + mSimState);
                    //USIM Type
                    if (mSimState.equals(IccCardConstants.INTENT_VALUE_ICC_LOADED)) {
                        responseEvent.putByte(1);
                    } else {
                        responseEvent.putByte(0);
                    }

                    //ISIM Type
                    if (mIsimState.equals(IccCardConstants.INTENT_VALUE_ICC_LOADED)) {
                        responseEvent.putByte(2);
                    } else {
                        responseEvent.putByte(3);
                    }

                    //Session ID
                    responseEvent.putByte(0);

                    //Pad
                    responseEvent.putByte(0);

                    // send the event to va
                    mSocket.writeEvent(responseEvent);

                    break;
                default:
                    log("Unknown request: " + requestId);
                    break;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void readUsimData(int type, int transactionId) {

        VaEvent event = new ImsAdapter.VaEvent(VaConstants.MSG_ID_RESPONSE_READ_USIM_FILE);
        byte resData[] = new byte[READ_USIM_COMMAND_DATA_LENGTH];
        log("transaction_id = " + transactionId);

        switch (type) {
            case READ_USIM_COMMAND_IMSI:
                String imsi;
                int mncLength = 0;
                imsi = TelephonyManager.getDefault().getSubscriberId();
                log("imsi = " + imsi);

                try {
                    mncLength = getSubscriberInfo().getMncLength();
                } catch (RemoteException ex) {
                    ex.printStackTrace();
                } catch (NullPointerException ex) {
                    // This could happen before phone restarts due to crashing
                    ex.printStackTrace();
                }

                log("MNC length = " + mncLength);

                if (imsi == null || mncLength <= 0 || mncLength > 15) {
                    log("readUsimDataFail: ID = " + transactionId + ", type = " + type);
                    readUsimDataFail(type, transactionId);
                    return;
                }

                //transaction_id
                event.putByte(transactionId);

                //is_success
                event.putByte(1);

                //data_type
                event.putByte(type);

                //num_of_data
                event.putByte(1);

                //mncLength
                event.putByte((byte) mncLength);

                //Pad
                event.putByte(0);
                event.putByte(0);
                event.putByte(0);

                //sim_usim_data
                //len
                log("imsi.length = " + imsi.length());
                event.putInt(imsi.length());
                //data
                event.putString(imsi, READ_USIM_COMMAND_DATA_LENGTH);

                break;
            case READ_USIM_COMMAND_PSISMSC:
                byte efPsismsc[] = null;

                try {
                    efPsismsc = getSubscriberInfo().getUsimPsismsc();
                } catch (RemoteException ex) {
                    ex.printStackTrace();
                } catch (NullPointerException ex) {
                    // This could happen before phone restarts due to crashing
                    ex.printStackTrace();
                }

                if (efPsismsc == null) {
                    log("readUsimDataFail: ID = " + transactionId + ", type = " + type);
                    readUsimDataFail(type, transactionId);
                    return;
                }

                for (int i = 0; i < efPsismsc.length ; i++) {
                    log("Result = " + efPsismsc[i]);
                }

                //transaction_id
                event.putByte(transactionId);

                //is_success
                event.putByte(1);

                //data_type
                event.putByte(type);

                //num_of_data
                event.putByte(1);

                //mncLength
                event.putByte(0);

                //Pad
                event.putByte(0);
                event.putByte(0);
                event.putByte(0);

                //sim_usim_data
                //len
                int psismscLen = ((efPsismsc.length > READ_USIM_COMMAND_DATA_LENGTH)
                        ? READ_USIM_COMMAND_DATA_LENGTH : efPsismsc.length);
                log("efPsismsc.length = " + efPsismsc.length
                        + ", max len = " + READ_USIM_COMMAND_DATA_LENGTH);
                event.putInt(psismscLen);
                //data
                System.arraycopy(efPsismsc, 0, resData, 0, psismscLen);
                event.putBytes(resData);

                break;
            case READ_USIM_COMMAND_SMSP:
                byte efSmsp[] = null;

                try {
                    efSmsp = getSubscriberInfo().getUsimSmsp();
                } catch (RemoteException ex) {
                    ex.printStackTrace();
                } catch (NullPointerException ex) {
                    // This could happen before phone restarts due to crashing
                    ex.printStackTrace();
                }

                if (efSmsp == null) {
                    log("readUsimDataFail: ID = " + transactionId + ", type = " + type);
                    readUsimDataFail(type, transactionId);
                    return;
                }

                for (int i = 0; i < efSmsp.length ; i++) {
                    log("Result = " + efSmsp[i]);
                }

                //transaction_id
                event.putByte(transactionId);

                //is_success
                event.putByte(1);

                //data_type
                event.putByte(type);

                //num_of_data
                event.putByte(1);

                //mncLength
                event.putByte(0);

                //Pad
                event.putByte(0);
                event.putByte(0);
                event.putByte(0);

                //sim_usim_data
                //len
                int smspLen = ((efSmsp.length > READ_USIM_COMMAND_DATA_LENGTH)
                        ? READ_USIM_COMMAND_DATA_LENGTH : efSmsp.length);
                log("efSmsp.length = " + efSmsp.length
                        + ", max len = " + READ_USIM_COMMAND_DATA_LENGTH);
                event.putInt(smspLen);
                //data
                System.arraycopy(efSmsp, 0, resData, 0, smspLen);
                event.putBytes(resData);
                break;
            default:
                log("readUsimData unknown type = " + type);
                break;
            }

        // send the event to va
        mSocket.writeEvent(event);
    }

    private void readUsimDataFail(int type, int transactionId) {
        VaEvent event = new ImsAdapter.VaEvent(VaConstants.MSG_ID_RESPONSE_READ_USIM_FILE);
        byte pad[] = new byte[READ_USIM_COMMAND_DATA_LENGTH];
        event.putByte(transactionId);

        //is_success
        event.putByte(0);

        //data_type
        event.putByte(type);

        //num_of_data
        event.putByte(0);

        //mncLength
        event.putByte(0);

        //Pad
        event.putByte(0);
        event.putByte(0);
        event.putByte(0);

        //sim_usim_data
        //len
        event.putInt(0);
        //data
        event.putBytes(pad);

        // send the event to va
        mSocket.writeEvent(event);
    }


    private void readIsimData(int type, int transactionId) {
        VaEvent event = new ImsAdapter.VaEvent(VaConstants.MSG_ID_RESPONSE_READ_ISIM_FILE);
        byte resData[] = new byte[READ_ISIM_COMMAND_DATA_LENGTH];
        log("readIsimData type = " + type + ", transactionId =" + transactionId);

        switch (type) {
            case READ_ISIM_COMMAND_IMPI:
                String impi = TelephonyManager.getDefault().getIsimImpi();
                log("impi = " + impi);

                if (impi == null) {
                    log("readIsimData: type = " + type);
                    readIsimDataFail(type, transactionId);
                    return;
                }

                //transaction_id
                event.putByte(transactionId);

                //is_success
                event.putByte(1);

                //data_type
                event.putByte(type);

                //num_of_data
                event.putByte(1);

                //ims_isim_data
                //len
                log("impi.length = " + impi.length());
                event.putInt(((impi.length() > READ_ISIM_COMMAND_DATA_LENGTH)
                        ? READ_ISIM_COMMAND_DATA_LENGTH : impi.length()));
                //data
                event.putString(impi, READ_ISIM_COMMAND_DATA_LENGTH);

                for (int i = 0; i < (READ_ISIM_COMMAND_DATA_NUM - 1); i++) {
                    //ims_isim_data[n], Pad
                    //len
                    event.putInt(0);
                    //data
                    event.putBytes(new byte[READ_ISIM_COMMAND_DATA_LENGTH]);
                }

                break;

            case READ_ISIM_COMMAND_IMPU:
                String[] impu = TelephonyManager.getDefault().getIsimImpu();

                log("impu = " + impu);

                if (impu == null) {
                    log("readIsimData: type = " + type);
                    readIsimDataFail(type, transactionId);
                    return;
                }

                //transaction_id
                event.putByte(transactionId);

                //is_success
                event.putByte(1);

                //data_type
                event.putByte(type);

                //num_of_data
                event.putByte(((impu.length > 5) ? 5 : impu.length));

                //ims_isim_data
                for (int i = 0; i < READ_ISIM_COMMAND_DATA_NUM; i++) {
                    if (i < impu.length && impu[i] != null) {
                        //len
                        log("impu[" + i + "].length = " + impu[i].length() + ", " + impu[i]);
                        event.putInt(((impu[i].length() > READ_ISIM_COMMAND_DATA_LENGTH)
                                ? READ_ISIM_COMMAND_DATA_LENGTH : impu[i].length()));
                        //data
                        event.putString(impu[i], READ_ISIM_COMMAND_DATA_LENGTH);
                    } else {
                        //len
                        event.putInt(0);
                        //data
                        event.putBytes(new byte[READ_ISIM_COMMAND_DATA_LENGTH]);
                    }
                }

                break;

            case READ_ISIM_COMMAND_DOMAIN_NAME:
                String domain = TelephonyManager.getDefault().getIsimDomain();
                log("domain = " + domain);

                if (domain == null) {
                    log("readIsimData: type = " + type);
                    readIsimDataFail(type, transactionId);
                    return;
                }

                //transaction_id
                event.putByte(transactionId);

                //is_success
                event.putByte(1);

                //data_type
                event.putByte(type);

                //num_of_data
                event.putByte(1);

                //ims_isim_data
                //len
                log("domain.length = " + domain.length());
                event.putInt(((domain.length() > READ_ISIM_COMMAND_DATA_LENGTH)
                        ? READ_ISIM_COMMAND_DATA_LENGTH : domain.length()));
                //data
                event.putString(domain, READ_ISIM_COMMAND_DATA_LENGTH);

                for (int i = 0; i < (READ_ISIM_COMMAND_DATA_NUM - 1); i++) {
                    //ims_isim_data[n], Pad
                    //len
                    event.putInt(0);
                    //data
                    event.putBytes(new byte[READ_ISIM_COMMAND_DATA_LENGTH]);
                }

                break;

            case READ_ISIM_COMMAND_PSISMSC:
                byte efPsismsc[] = null;

                try {
                    efPsismsc = getSubscriberInfo().getIsimPsismsc();
                } catch (RemoteException ex) {
                    ex.printStackTrace();
                } catch (NullPointerException ex) {
                    // This could happen before phone restarts due to crashing
                    ex.printStackTrace();
                }

                if (efPsismsc == null) {
                    log("readUsimDataFail: type = " + type);
                    readIsimDataFail(type, transactionId);
                    return;
                }

                for (int i = 0; i < efPsismsc.length ; i++) {
                    log("Result = " + efPsismsc[i]);
                }

                //transaction_id
                event.putByte(transactionId);

                //is_success
                event.putByte(1);

                //data_type
                event.putByte(type);

                //num_of_data
                event.putByte(1);

                //ims_isim_data
                //len
                int psismscLen = ((efPsismsc.length > READ_ISIM_COMMAND_DATA_LENGTH)
                        ? READ_ISIM_COMMAND_DATA_LENGTH : efPsismsc.length);
                log("efPsismsc.length = " + efPsismsc.length
                        + ", max len = " + READ_ISIM_COMMAND_DATA_LENGTH);
                event.putInt(psismscLen);
                //data
                System.arraycopy(efPsismsc, 0, resData, 0, psismscLen);
                event.putBytes(resData);

                for (int i = 0; i < (READ_ISIM_COMMAND_DATA_NUM - 1); i++) {
                    //ims_isim_data[n], Pad
                    //len
                    event.putInt(0);
                    //data
                    event.putBytes(new byte[READ_ISIM_COMMAND_DATA_LENGTH]);
                }

                break;
            case READ_ISIM_SERVICE_TABLE:
                String ist = TelephonyManager.getDefault().getIsimIst();
                log("ist = " + ist);

                if (ist == null) {
                    log("readIsimData: type = " + type);
                    readIsimDataFail(type, transactionId);
                    return;
                }

                byte istBytes[] = hexStringToBytes(ist);

                //transaction_id
                event.putByte(transactionId);

                //is_success
                event.putByte(1);

                //data_type
                event.putByte(type);

                //num_of_data
                event.putByte(1);

                //ims_isim_data
                //len
                int istLen = ((istBytes.length > READ_ISIM_COMMAND_DATA_LENGTH)
                        ? READ_ISIM_COMMAND_DATA_LENGTH : istBytes.length);
                log( "istBytes.length = " + istBytes.length
                        + ", max len = " + READ_ISIM_COMMAND_DATA_LENGTH);
                event.putInt(istLen);
                //data
                System.arraycopy(istBytes, 0, resData, 0, istLen);
                event.putBytes(resData);

                for (int i = 0; i < (READ_ISIM_COMMAND_DATA_NUM - 1); i++) {
                    //ims_isim_data[n], Pad
                    //len
                    event.putInt(0);
                    //data
                    event.putBytes(new byte[READ_ISIM_COMMAND_DATA_LENGTH]);
                }

                break;
            default:
                log("readIsimData unknown type = " + type);
                break;
            }

        // send the event to va
        mSocket.writeEvent(event);
    }

    private void readIsimDataFail(int type, int transactionId) {
        VaEvent event = new ImsAdapter.VaEvent(VaConstants.MSG_ID_RESPONSE_READ_ISIM_FILE);
        byte pad[] = new byte[READ_ISIM_COMMAND_DATA_LENGTH];

        //transaction_id
        event.putByte(transactionId);

        //is_success
        event.putByte(0);

        //data_type
        event.putByte(type);

        //num_of_data
        event.putByte(0);

        //ims_isim_data
        for (int i = 0; i < READ_ISIM_COMMAND_DATA_NUM; i++) {
            //len
            event.putInt(0);
            //data
            event.putBytes(pad);
        }

        // send the event to va
        mSocket.writeEvent(event);
    }


    protected void log(String s) {
        Rlog.d(TAG, s);
    }

    private ITelephonyEx getITelephonyEx() {
        return ITelephonyEx.Stub.asInterface(
                ServiceManager.getService(Context.TELEPHONY_SERVICE_EX));
    }

    private IPhoneSubInfo getSubscriberInfo() {
        // get it each time because that process crashes a lot
        return IPhoneSubInfo.Stub.asInterface(ServiceManager.getService("iphonesubinfo"));
    }

    private int hexCharToInt(char c) {
        if (c >= '0' && c <= '9') {
            return (c - '0');
        }
        if (c >= 'A' && c <= 'F') {
            return (c - 'A' + 10);
        }
        if (c >= 'a' && c <= 'f') {
            return (c - 'a' + 10);
        }

        throw new RuntimeException ("invalid hex char '" + c + "'");
    }

    /**
     * Converts a hex String to a byte array.
     *
     * @param s A string of hexadecimal characters, must be an even number of
     *          chars long
     *
     * @return byte array representation
     *
     * @throws RuntimeException on invalid format
     */
    private byte[] hexStringToBytes(String s) {
        byte[] ret;

        if (s == null) {
            return null;
        }

        int sz = s.length();

        ret = new byte[sz/2];

        for (int i=0 ; i <sz ; i+=2) {
            ret[i/2] = (byte) ((hexCharToInt(s.charAt(i)) << 4)
                                | hexCharToInt(s.charAt(i+1)));
        }

        return ret;
    }
}
