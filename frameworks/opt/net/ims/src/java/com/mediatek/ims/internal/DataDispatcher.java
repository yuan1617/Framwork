package com.mediatek.ims.internal;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import com.mediatek.ims.ImsAdapter;
import com.mediatek.ims.ImsAdapter.VaSocketIO;
import com.mediatek.ims.ImsAdapter.VaEvent;
import com.mediatek.ims.ImsEventDispatcher;
import com.mediatek.ims.VaConstants;
import com.mediatek.xlog.Xlog;


import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.UnknownHostException;

import android.net.ConnectivityManager;
import android.net.DhcpResults;
import android.net.LinkProperties;
import android.net.LinkAddress;
import android.net.NetworkUtils;

import android.os.Looper;
import android.os.Handler;
import android.os.Message;
import android.os.SystemProperties;
import android.os.AsyncResult;
import android.os.ServiceManager;
import android.os.INetworkManagementService;
import android.os.RemoteException;

//import android.net.MobileDataStateTracker;
import android.net.NetworkInfo;
import android.telephony.TelephonyManager;

import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyIntents;

//import com.android.internal.telephony.dataconnection.DcFailCause;
import com.android.internal.telephony.DctConstants;

//import com.android.internal.telephony.uicc.IccRecords;
//import com.android.internal.telephony.uicc.UiccController;

import com.mediatek.internal.telephony.DedicateBearerProperties;
import com.mediatek.internal.telephony.DefaultBearerConfig;
import com.mediatek.internal.telephony.PcscfInfo;
import com.mediatek.internal.telephony.QosStatus;
import com.mediatek.internal.telephony.TftStatus;
import com.mediatek.internal.telephony.PacketFilterInfo;
import com.mediatek.internal.telephony.PcscfAddr;
           
import com.android.ims.mo.ImsLboPcscf;
//import com.android.ims.IImsManagerService;
//import com.mediatek.common.ims.ImsConstants;
import com.android.ims.ImsConfig;


import android.net.ConnectivityManager.NetworkCallback;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import static android.net.ConnectivityManager.TYPE_NONE;
import com.mediatek.internal.telephony.ITelephonyEx;
                      


public class DataDispatcher implements ImsEventDispatcher.VaEventDispatcher {
    private static final String TAG = DataDispatcherUtil.TAG;
    private static final boolean DUMP_TRANSACTION = true;
    private static final boolean DBG = true;

    // TODO: working around for testing, need to change back to IMS later
    private static String IMS_APN = PhoneConstants.APN_TYPE_IMS;
    private static String EMERGENCY_APN = PhoneConstants.APN_TYPE_EMERGENCY;

    private static String FEATURE_ENABLE_IMS = "enableIMS";
    private static String FEATURE_ENABLE_EMERGENCY = "enableEmergency";

    public static final String REASON_BEARER_ACTIVATION = "activation";
    public static final String REASON_BEARER_DEACTIVATION = "deactivation";
    public static final String REASON_BEARER_MODIFICATION = "modification";
    public static final String REASON_BEARER_ABORT = "abort";

    private static final int IMC_CONCATENATED_MSG_TYPE_NONE = 0;
    private static final int IMC_CONCATENATED_MSG_TYPE_ACTIVATION = 1;
    private static final int IMC_CONCATENATED_MSG_TYPE_MODIFICATION = 2;

    public static final String PROPERTY_MANUAL_PCSCF_ADDRESS = "ril.pcscf.addr";
    public static final String PROPERTY_MANUAL_PCSCF_PORT = "ril.pcscf.port";

    private static final int MSG_ON_DEDICATE_CONNECTION_STATE_CHANGED = 4000;
    private static final int MSG_PCSCF_DISCOVERY_PCO_DONE = 5000;
    private static final int MSG_ON_DEFAULT_BEARER_CONNECTION_CHANGED = 6000;
    private static final int MSG_ON_DEFAULT_BEARER_CONNECTION_FAILED = 6100;
    private static final int MSG_ON_NOTIFY_GLOBAL_IP_ADDR = 7000;

    private static final int PDP_ADDR_TYPE_NONE = 0x0;
    private static final int PDP_ADDR_TYPE_IPV4 = 0x21;
    private static final int PDP_ADDR_TYPE_IPV6 = 0x57;
    private static final int PDP_ADDR_TYPE_IPV4v6 = 0x8D;
    private static final int PDP_ADDR_TYPE_NULL = 0x03;

    private static final int PDP_ADDR_MASK_NONE   = 0x00;
    private static final int PDP_ADDR_MASK_IPV4   = 0x01;
    private static final int PDP_ADDR_MASK_IPV6   = 0x02;
    private static final int PDP_ADDR_MASK_IPV4v6 = 0x03;

    private static final int SIZE_DEFAULT_BEARER_RESPONSE = 38000;
    private static final int SIZE_NOTIFY_DEDICATE_BEARER_ACTIVATED = 20480;

    private static final int FAILCAUSE_NONE = 0;
    private static final int FAILCAUSE_UNKNOWN = 65536;

    private static DataDispatcher mInstance;
    private static HashMap<Integer, String> mImsNetworkInterface = new HashMap<Integer, String>();;

    private boolean mIsEnable;
    private Context mContext;
    private VaSocketIO mSocket;

    //private Phone mPhone;
    private HashMap<Integer, TransactionParam> mTransactions = new HashMap<Integer, TransactionParam>();
    private DataDispatcherUtil mDataDispatcherUtil;

    private PcscfDiscoveryDchpThread mPcscfDiscoveryDchpThread;
    private static final int INVALID_CID = -1;
    private int mEmergencyCid = INVALID_CID;
    private ArrayList<Integer> mDeactivateCidArray = new ArrayList <Integer> ();

    DataDispatcherNetworkRequest [] mDataNetworkRequests;
    private static final int[] APN_CAP_LIST = new int[] {NetworkCapabilities.NET_CAPABILITY_IMS,
        NetworkCapabilities.NET_CAPABILITY_EIMS};

    private boolean mIsBroadcastReceiverRegistered;
    private static final int IMS_PDN = 0;
    private static final int IMS_EMERGENCY_PDN = 1;

    // DHCP
    static private final int IP_DHCP_NONE = 0;
    static private final int IP_DHCP_V4 = 1;
    static private final int IP_DHCP_V6 = 2;

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

            if (TelephonyIntents.ACTION_ANY_DEDICATE_DATA_CONNECTION_STATE_CHANGED.equals(action)){
                log("onReceive, intent action is " +
                    TelephonyIntents.ACTION_ANY_DEDICATE_DATA_CONNECTION_STATE_CHANGED);
                mHandler.sendMessage(mHandler.obtainMessage(MSG_ON_DEDICATE_CONNECTION_STATE_CHANGED, intent));
            } else if (TelephonyIntents.ACTION_ANY_DATA_CONNECTION_STATE_CHANGED.equals(action)) {
                String apnType = intent.getStringExtra(PhoneConstants.DATA_APN_TYPE_KEY);
                int imsChanged = intent.getIntExtra(PhoneConstants.DATA_IMS_CHANGED_KEY, 0);
                if (isMsgAllowed(apnType, imsChanged)) {
                    log("onReceive, apnType: " + apnType + " intent action is " +
                        TelephonyIntents.ACTION_ANY_DATA_CONNECTION_STATE_CHANGED);
                    mHandler.sendMessage(mHandler.obtainMessage(MSG_ON_DEFAULT_BEARER_CONNECTION_CHANGED, intent));
                }
            } else if (TelephonyIntents.ACTION_DATA_CONNECTION_FAILED.equals(action)) {
                String apnType = intent.getStringExtra(PhoneConstants.DATA_APN_TYPE_KEY);
                if (isApnIMSorEmergency(apnType)) {
                    log("onReceive, apnType: " + apnType + " intent action is " +
                        TelephonyIntents.ACTION_DATA_CONNECTION_FAILED);
                    mHandler.sendMessage(mHandler.obtainMessage(MSG_ON_DEFAULT_BEARER_CONNECTION_FAILED, intent));
                }
            } else if (TelephonyIntents.ACTION_NOTIFY_GLOBAL_IP_ADDR.equals(action)) {
                log("onReceive, intent action is " +
                    TelephonyIntents.ACTION_NOTIFY_GLOBAL_IP_ADDR);
                mHandler.sendMessage(mHandler.obtainMessage(MSG_ON_NOTIFY_GLOBAL_IP_ADDR, intent));
            } else if (TelephonyIntents.ACTION_NOTIFY_IMS_DEACTIVATED_CIDS.equals(action)) {
                int [] cidArray = intent.getIntArrayExtra(TelephonyIntents.EXTRA_IMS_DEACTIVATED_CIDS);
                log("onReceive, intent action is " +
                    TelephonyIntents.ACTION_NOTIFY_IMS_DEACTIVATED_CIDS);
                setDeactivateCidArray(cidArray);
            } else {
                log("unhandled action!!");
            }
        }
    };

    private Handler mHandler;
    private Thread mHandlerThread = new Thread() {
        @Override
        public void run() {
            Looper.prepare();
            mHandler = new Handler() { //create handler here
                @Override
                synchronized public void handleMessage(Message msg) {
                    if (!mIsEnable) {
                        loge("receives message [" + msg.what + "] but DataDispatcher is not enabled, ignore");
                        return;
                    }

                    if (msg.obj instanceof VaEvent) {
                        VaEvent event = (VaEvent)msg.obj;
                        log("receives request [" + msg.what + ", " + event.getDataLen() + "]");
                        switch(msg.what) {
                            case VaConstants.MSG_ID_REQUEST_DEDICATE_BEARER_ACTIVATATION:
                                handleDedicateBearerActivationRequest(event);
                                break;
                            case VaConstants.MSG_ID_REQUEST_BEARER_DEACTIVATION:
                                handleBearerDeactivationRequest(event);
                                break;
                            case VaConstants.MSG_ID_REQUEST_BEARER_MODIFICATION:
                                handleDedicateBearerModificationRequest(event);
                                break;
                            case VaConstants.MSG_ID_REQUEST_PCSCF_DISCOVERY:
                                handlePcscfDiscoveryRequest(event);
                                break;
                            case VaConstants.MSG_ID_REQUEST_BEARER_ACTIVATION:
                                handleDefaultBearerActivationRequest(event);
                                break;
                            default:
                                log("receives unhandled message [" + msg.what + "]");
                        }
                    } else {
                        log("receives request [" + msg.what + "]");
                        switch (msg.what) {
                            case MSG_ON_DEDICATE_CONNECTION_STATE_CHANGED: {
                                Intent intent = (Intent)msg.obj;
                                int ddcId = intent.getIntExtra("DdcId", -1);
                                DedicateBearerProperties property = (DedicateBearerProperties)intent.getExtra(PhoneConstants.DATA_LINK_PROPERTIES_KEY);
                                DctConstants.State state = (DctConstants.State)intent.getExtra(PhoneConstants.STATE_KEY);
                                
                                int nfailCause = intent.getIntExtra("cause", 0);
                                String reason = intent.getStringExtra(PhoneConstants.STATE_CHANGE_REASON_KEY);
                                int nPhoneId = intent.getIntExtra(PhoneConstants.PHONE_KEY, -1);

                                onDedicateDataConnectionStateChanged(ddcId, state, property, nfailCause, reason);
                                break;
                            }
                            case MSG_PCSCF_DISCOVERY_PCO_DONE: {
                                AsyncResult ar = (AsyncResult)msg.obj;
                                int transactionId = msg.arg1;
                                if (ar.exception == null) {
                                    if (ar.result == null) {
                                        loge("receives MSG_PCSCF_DISCOVERY_PCO_DONE but no PcscfInfo");
                                        rejectPcscfDiscovery(transactionId, 1);
                                    } else {
                                        responsePcscfDiscovery(transactionId, (PcscfInfo)ar.result);
                                    }
                                } else {
                                    loge("receives MSG_PCSCF_DISCOVERY_PCO_DONE but exception [" + ar.exception + "]");
                                    rejectPcscfDiscovery(transactionId, 1);
                                }
                                removeTransaction(transactionId);
                                break;
                            }
                            case MSG_ON_DEFAULT_BEARER_CONNECTION_CHANGED: {
                                Intent intent = (Intent)msg.obj;
                                String apnType = intent.getStringExtra(PhoneConstants.DATA_APN_TYPE_KEY);
                                onDefaultBearerDataConnStateChanged(intent, apnType);
                                break;
                            }
                            case MSG_ON_DEFAULT_BEARER_CONNECTION_FAILED: {
                                Intent intent = (Intent)msg.obj;
                                String apnType = intent.getStringExtra(PhoneConstants.DATA_APN_TYPE_KEY);
                                onDefaultBearerDataConnFail(intent, apnType);
                                break;
                            }
                            case MSG_ON_NOTIFY_GLOBAL_IP_ADDR: {
                                Intent intent = (Intent)msg.obj;
                                String apnType = intent.
                                    getStringExtra(PhoneConstants.DATA_APN_TYPE_KEY);
                                String intfName = intent.
                                    getStringExtra(PhoneConstants.DATA_IFACE_NAME_KEY);
                                InetAddress inetAddr = (InetAddress) intent.
                                    getExtra(TelephonyIntents.EXTRA_GLOBAL_IP_ADDR_KEY);
                                NetworkInfo.State state = getImsOrEmergencyNetworkInfoState(
                                                                        apnType);
                                //state = NetworkInfo.State.CONNECTED; // for testing
                                if (NetworkInfo.State.CONNECTED == state) {
                                    if (DBG) {
                                        String ipType = "unknown type ip";
                                        if (inetAddr instanceof Inet4Address) {
                                            ipType = "IPV4";
                                        } else if (inetAddr instanceof Inet6Address) {
                                            ipType = "IPV6";
                                        }

                                        log("Get Global" + ipType + " address");
                                    }

                                    onNotifyGlobalIpAddr(inetAddr, apnType, intfName);
                                } else {
                                    log("no notify ip to va, due to state not connected!! state ("
                                        + state + ")");
                                }
                                break;
                            }
                            default:
                                loge("receives unhandled message [" + msg.what + "]");
                        }
                    }
                }
            };
            Looper.loop();
        }
    };

    public DataDispatcher(Context context, VaSocketIO IO) {
        log("DataDispatcher created and use apn type [" + IMS_APN + "] as IMS APN");
        mContext = context;
        mSocket = IO;
        mDataDispatcherUtil = new DataDispatcherUtil();
        mInstance = this;
        mHandlerThread.start();

        //createNetworkRequest(APN_CAP_LIST.length);
    }

    public static DataDispatcher getInstance() {
        return mInstance;
    }

    public void enableRequest(){
        synchronized(mHandler) {
            log("receive enableRequest");
            mIsEnable = true;

            if (!mIsBroadcastReceiverRegistered) {
                IntentFilter filter = new IntentFilter();
                filter.addAction(TelephonyIntents.ACTION_ANY_DEDICATE_DATA_CONNECTION_STATE_CHANGED);
                filter.addAction(TelephonyIntents.ACTION_ANY_DATA_CONNECTION_STATE_CHANGED);
                filter.addAction(TelephonyIntents.ACTION_DATA_CONNECTION_FAILED);
                filter.addAction(TelephonyIntents.ACTION_NOTIFY_GLOBAL_IP_ADDR);
                filter.addAction(TelephonyIntents.ACTION_NOTIFY_IMS_DEACTIVATED_CIDS);
                mContext.registerReceiver(mBroadcastReceiver, filter);
                mIsBroadcastReceiverRegistered = true;
            }
        }
    }

    public void disableRequest(){
        synchronized(mHandler) {
            log("receive disableRequest");
            mIsEnable = false;

            if (mIsBroadcastReceiverRegistered) {
                mContext.unregisterReceiver(mBroadcastReceiver);
                mIsBroadcastReceiverRegistered = false;
            }

            synchronized (mImsNetworkInterface) {
                log("disableRequest to clear interface and cid map");
                mImsNetworkInterface.clear();
            }

            synchronized (mTransactions) {
                log("disableRequest to clear transactions");
                mTransactions.clear();
            }

            if (mPcscfDiscoveryDchpThread != null) {
                log("disableRequest to interrupt dhcp thread");
                mPcscfDiscoveryDchpThread.interrupt();
                mPcscfDiscoveryDchpThread = null;
            }

            // TODO: need to use new interface insteading of legcy one for L Migration [start]
            getConnectivityManager().stopUsingNetworkFeature(ConnectivityManager.TYPE_MOBILE, FEATURE_ENABLE_IMS);
            getConnectivityManager().stopUsingNetworkFeature(ConnectivityManager.TYPE_MOBILE, FEATURE_ENABLE_EMERGENCY);
            // TODO: need to use new interface insteading of legcy one for L Migration [end]

        }
    }

    public void vaEventCallback(VaEvent event) {
        //relay to main thread to keep rceiver and callback handler is working under the same thread
        mHandler.sendMessage(mHandler.obtainMessage(event.getRequestID(), event));
    }

    public void setSocket(VaSocketIO socket) {
        //this method is used for testing
        //we could set a dummy socket used to verify the response
        mSocket = socket;
    }

    private void sendVaEvent(VaEvent event) {
        log("DataDispatcher send event [" + event.getRequestID()+ ", " + event.getDataLen() + "]");
        mSocket.writeEvent(event);
    }

    private void setEmergencyCid(int cid) {
        mEmergencyCid = cid;
        log("set mEmergencyCid to: " + mEmergencyCid);
    }

    private void handleDefaultBearerActivationRequest(VaEvent event) {
        // imcf_uint8                               transaction_id
        // imcf_uint8                               pad[3]
        // imc_eps_qos_struct                  ue_defined_eps_qos
        // imc_emergency_ind_enum       emergency_indidation
        // imc_pcscf_discovery_enum       pcscf_discovery_flag
        // imcf_uint8                               signaling_flag
        // imcf_uint8                               pad2[1]
        int result = -1;
        int isValid = 1;
        int isEmergencyInd = 0; //0 is general, 1 is emergency
        int nNetworkType = ConnectivityManager.TYPE_MOBILE_IMS;
        String userFeature = FEATURE_ENABLE_IMS;
        DataDispatcherUtil.DefaultPdnActInd defaultPdnActInd = mDataDispatcherUtil.extractDefaultPdnActInd(event);
        TransactionParam param = new TransactionParam(defaultPdnActInd.transactionId, event.getRequestID());
        putTransaction(param);

        log("handleDefaultBearerActivationRequest");

        // TODO: Need to convert emergency_ind here
        switch (defaultPdnActInd.emergency_ind) {
            case 1: //general
                break;
            case 2: // is emergency
                isEmergencyInd = 1;
                nNetworkType = ConnectivityManager.TYPE_MOBILE_EMERGENCY;
                userFeature = FEATURE_ENABLE_EMERGENCY;
                param.isEmergency = true;
                break;
            default: //invalid or error
                rejectDefaultBearerDataConnActivation(param, FAILCAUSE_UNKNOWN);
                return;
        };

        

        DefaultBearerConfig defaultBearerConfig = new DefaultBearerConfig(isValid, defaultPdnActInd.qosStatus, isEmergencyInd,
                                                 defaultPdnActInd.pcscf_discovery,defaultPdnActInd.signalingFlag);
        setDefaultBearerConfig(networkTypeToApnType(nNetworkType), defaultBearerConfig);

        result = getConnectivityManager().startUsingNetworkFeature(ConnectivityManager.TYPE_MOBILE, userFeature);
        if (result == PhoneConstants.APN_REQUEST_STARTED) { // handle trasaction
            log("handleDefaultBearerActivationRequest APN request started");
        } else if (result == PhoneConstants.APN_ALREADY_ACTIVE) {
            log("handleDefaultBearerActivationRequest APN already active");
            if (nNetworkType == ConnectivityManager.TYPE_MOBILE_IMS) {
                responseDefaultBearerDataConnActivated(param, PhoneConstants.APN_TYPE_IMS,
                        getLinkProperties(PhoneConstants.APN_TYPE_IMS));
            } else {
                responseDefaultBearerDataConnActivated(param, PhoneConstants.APN_TYPE_EMERGENCY,
                        getLinkProperties(PhoneConstants.APN_TYPE_EMERGENCY));
            }
        } else { //response rejection
            log("handleDefaultBearerActivationRequest startUsingNetworkFeature failed!!, result: " + result);
            rejectDefaultBearerDataConnActivation(param, FAILCAUSE_UNKNOWN);
        }
    }

    private void rejectDefaultBearerDataConnActivation(TransactionParam param, int failCause) {
        if (hasTransaction(param.transactionId)) {
            //imcf_uint8 transaction_id
            //imc_ps_cause_enum ps_cause
            //imcf_uint8 pad [2]
            String userFeature = (param.isEmergency) ? FEATURE_ENABLE_EMERGENCY : FEATURE_ENABLE_IMS;
            getConnectivityManager().stopUsingNetworkFeature(ConnectivityManager.TYPE_MOBILE, userFeature);
            
            //prevent receiving disconnect event after receiving IMCB retrying request
            delayForSeconds(5000);

            removeTransaction(param.transactionId);

            sendVaEvent(makeRejectDefaultBearerEvent(param, failCause));
        } else {
            loge("rejectDefaultBearerDataConnActivation but transactionId does not existed, ignore");
        }
    }

    private void handleDefaultBearerDeactivationRequest(int requestId, DataDispatcherUtil.PdnDeactInd pdnDeactInd) {
        int result = -1;
        String networkFeature = FEATURE_ENABLE_IMS;
        int networkType = ConnectivityManager.TYPE_MOBILE_IMS;
        boolean bIsEmergency = false;

        if (pdnDeactInd.isCidValid) {
            log("handleDefaultBearerDeactivationRequest [" + pdnDeactInd.transactionId + "] deactivate cid=" + pdnDeactInd.cid
                + ", networkFeature: " + networkFeature);
            bIsEmergency = ( mEmergencyCid == pdnDeactInd.cid && mEmergencyCid != -1)? true: false;
        } else {
            log("handleDedicateBearerDeactivationRequest [" + pdnDeactInd.transactionId + "] abort transactionId=" + pdnDeactInd.transactionId +
                (pdnDeactInd.isCidValid ? (", cid=" + pdnDeactInd.cid) : (", abortTransactionId=" + pdnDeactInd.abortTransactionId)));

            TransactionParam abortParam = getTransaction(pdnDeactInd.abortTransactionId);
            if (abortParam == null)
                loge("handleDefaultBearerDeactivationRequest to do abort but no transaction is found");
            else
                bIsEmergency = abortParam.isEmergency;
        }

        if (bIsEmergency) {
            log("handleDefaultBearerDeactivationRequest the bearer is emergency bearer");
            networkType = ConnectivityManager.TYPE_MOBILE_EMERGENCY;
            networkFeature = FEATURE_ENABLE_EMERGENCY;
        }
        result = getConnectivityManager().stopUsingNetworkFeature(ConnectivityManager.TYPE_MOBILE, networkFeature);

        TransactionParam param = new TransactionParam(pdnDeactInd.transactionId, requestId);
        param.isEmergency = bIsEmergency;
        putTransaction(param);

        if (result >= 0) {
            if (pdnDeactInd.isCidValid)
                param.cid = pdnDeactInd.cid;
            else
                param.cid = 0; //set cid to 0 for abort transaction

            try {
                NetworkInfo networkInfo = getConnectivityManager().getNetworkInfo(networkType);
                NetworkInfo.State currState = networkInfo.getState();
                log("networkinfo: " + networkInfo);
                if (currState == NetworkInfo.State.DISCONNECTED ||
                    currState == NetworkInfo.State.CONNECTING) {
                    if (!pdnDeactInd.isCidValid) {
                        Integer[] transactionKeyArray = getTransactionKeyArray();
                        for (Integer transactionId : transactionKeyArray) {
                            TransactionParam actParam = getTransaction(transactionId);
                            if (VaConstants.MSG_ID_REQUEST_BEARER_ACTIVATION
                                == actParam.requestId) {
                                loge("handleDefaultBearerDeactivationRequest abort activation "
                                    + "request");
                                rejectDefaultBearerDataConnActivation(actParam,
                                                            FAILCAUSE_UNKNOWN);
                            }
                        }
                    }
                    loge("handleDefaultBearerDeactivationRequest and bearer is already " +
                        "deactivated");
                    responseDefaultBearerDataConnDeactivated(param);
                }
            } catch (NullPointerException ex) {
                loge("networkInfo is null");
                ex.printStackTrace();
            }

            if (bIsEmergency)
                setEmergencyCid(INVALID_CID);
        } else {
            //response rejection
            rejectDefaultBearerDataConnDeactivation(param, 1);
        }

    }

    private void rejectDefaultBearerDataConnDeactivation(TransactionParam param, int failCause) {
        if (hasTransaction(param.transactionId)) {
            //imcf_uint8 transaction_id
            //imc_ps_cause_enum ps_cause
            //imcf_uint8 pad [2]

            removeTransaction(param.transactionId);

            sendVaEvent(makeRejectDefaultBearerEvent(param, failCause));
        } else {
            loge("rejectDefaultBearerDataConnDeactivation but transactionId does not existed, ignore");
        }
    }

    private ImsAdapter.VaEvent makeRejectDefaultBearerEvent(TransactionParam param, int failCause) {
        //imcf_uint8 transaction_id
        //imc_ps_cause_enum ps_cause
        //imcf_uint8 pad [2]

        ImsAdapter.VaEvent event = new ImsAdapter.VaEvent(VaConstants.MSG_ID_REJECT_BEARER_ACTIVATION);
        log("rejectDefaultBearerDataConnActivation param" + param + ", failCause=" + failCause);

        event.putByte(param.transactionId); //transaction id;
        event.putByte(failCause);           //cause
        event.putBytes(new byte[2]);        //padding

        return event;
    }

    private void handleDedicateBearerActivationRequest(VaEvent event) {
        //imcf_uint8                                      transaction_id
        //imcf_uint8                                      primary_context_id
        //imc_im_cn_signaling_flag_enum   signaling_flag (value: 0/1)
        //imcf_uint8                                      pad [1]
        //imc_eps_qos_struct                      ue_defined_eps_qos
        //imc_tft_info_struct                         ue_defined_tft

        int transactionId = event.getByte();
        int primaryCid = event.getByte();
        boolean signalingFlag = event.getByte() > 0;
        event.getByte(); //padding
        QosStatus qosStatus = DataDispatcherUtil.readQos(event);
        TftStatus tftStatus = DataDispatcherUtil.readTft(event);
        log("handleDedicateBearerActivationRequest [" + transactionId + "] primaryCid=" + primaryCid + ", signalingFlag=" + signalingFlag + ", Qos" + qosStatus + ", Tft" + tftStatus);

        int ddcId = enableDedicateBearer(IMS_APN, signalingFlag, qosStatus, tftStatus);
        TransactionParam param = new TransactionParam(transactionId, event.getRequestID());
        param.ddcId = ddcId; //store ddcId for abort if necessary
        putTransaction(param);

        if (ddcId < 0) {
            loge("handleDedicateBearerActivationRequest [" + transactionId + "] but no ddcId is assigned");
            rejectDedicateDataConnectionActivation(param, FAILCAUSE_UNKNOWN, null);
        }
    }

    private void handleBearerDeactivationRequest(VaEvent event) {
        //imcf_uint8    transaction_id
        //imcf_uint8    abort_activate_transaction_id
        //imcf_uint8    context_id_is_valid
        //imcf_uint8    context_id
        DataDispatcherUtil.PdnDeactInd pdnDeactInd = mDataDispatcherUtil.extractPdnDeactInd(event);
        if (isDedicateBearer(pdnDeactInd.cid)) { 
            handleDedicateBearerDeactivationRequest(event.getRequestID(), pdnDeactInd);
        } else if (!pdnDeactInd.isCidValid) {
            TransactionParam param = getTransaction(pdnDeactInd.abortTransactionId);
            if (param != null) {
                if (param.requestId == VaConstants.MSG_ID_REQUEST_BEARER_ACTIVATION) {
                    log("handleBearerDeactivationRequest to default bearer activation");
                    handleDefaultBearerDeactivationRequest(event.getRequestID(), pdnDeactInd);
                } else {
                    log("handleBearerDeactivationRequest to abort dedicate bearer activation");
                    handleDedicateBearerDeactivationRequest(event.getRequestID(), pdnDeactInd);
                }
            } else {
                loge("handleBearerDeactivationRequest to abort bearer activation but no transaction found (reject request anyway)");
                rejectDataBearerDeactivation(pdnDeactInd.transactionId, 1);
            }
        } else {
            handleDefaultBearerDeactivationRequest(event.getRequestID(), pdnDeactInd);
        }
    }

    private void handleDedicateBearerDeactivationRequest(int requestId, DataDispatcherUtil.PdnDeactInd pdnDeactInd) {
        int ddcId = -1;
        log("handleDedicateBearerDeactivationRequest PdnDeactInd" + pdnDeactInd);
        TransactionParam param = new TransactionParam(pdnDeactInd.transactionId, requestId);
        if (pdnDeactInd.isCidValid) {
            ddcId = disableDedicateBearer(REASON_BEARER_DEACTIVATION, pdnDeactInd.cid);
        } else {
            //try to get ddcId from transactions
            TransactionParam transaction = getTransaction(pdnDeactInd.abortTransactionId);
            if (transaction == null) {
                loge("handleDedicateBearerDeactivationRequest do abort but no transaction found with transactionId=" + pdnDeactInd.abortTransactionId);
                //since ddcId is -1, this request will be rejected
            } else {
                log("handleDedicateBearerDeactivationRequest do abort with ddcId=" + transaction.ddcId);
                ddcId = abortEnableDedicateBearer(REASON_BEARER_ABORT, transaction.ddcId);
            }
        }

        putTransaction(param);

        if (ddcId >= 0) {
            if (pdnDeactInd.isCidValid)
                param.cid = pdnDeactInd.cid;
            else
                param.cid = -1;

            param.ddcId = ddcId;
        } else {
            log("handleDedicateBearerDeactivationRequest but no corresponding ddcId is found " + pdnDeactInd);
            rejectDataConnectionDeactivation(pdnDeactInd.transactionId, 1);
        }
    }

    private void handleDedicateBearerModificationRequest(VaEvent event) {
        //imcf_uint8 transation_id
        //imcf_uint8 context_id
        //imcf_uint8 qos_mod
        //imcf_uint8 pad [1]
        //imc_eps_qos_struct ue_defined_eps_qos
        //imcf_uint8 tft_mod
        //imcf_uint8 pad2 [3]
        //imc_tft_info_struc ue_defined_tft
        int transactionId = event.getByte();
        int cid = event.getByte();
        boolean isQosModify = event.getByte() == 1;
        event.getByte(); //padding
        QosStatus qosStatus = DataDispatcherUtil.readQos(event);
        boolean isTftModify = event.getByte() == 1;
        event.getBytes(3); //padding
        TftStatus tftStatus = DataDispatcherUtil.readTft(event);

        log("handleDedicateBearerModificationRequest [" + transactionId + ", " + cid + "] " +
            (isQosModify ? "Qos" + qosStatus : "") + (isTftModify ? " Tft" + tftStatus : ""));

        int ddcId = modifyDedicateBearer(cid, isQosModify ? qosStatus : null,
                                    isTftModify ? tftStatus : null);
        TransactionParam param = new TransactionParam(transactionId, event.getRequestID());
        param.cid = cid;
        param.ddcId = ddcId;

        putTransaction(param);

        if (ddcId < 0) {
            rejectDedicateDataConnectionModification(param, FAILCAUSE_UNKNOWN, null);
        }
    }

    private void handlePcscfDiscoveryRequest(VaEvent event) {
        //imcf_uint8 transaction_id
        //imcf_uint8 context_id
        //imcf_uint8 pad [2]
        //char nw_if_name [IMC_MAXIMUM_NW_IF_NAME_STRING_SIZE]
        //imc_pcscf_acquire_method_enum pcscf_aqcuire_method
        int transactionId = event.getByte();
        int cid = event.getByte();
        event.getBytes(2); //padding

        byte[] interfaceBytes = event.getBytes(DataDispatcherUtil.IMC_MAXIMUM_NW_IF_NAME_STRING_SIZE);
        String interfaceName = null;
        try {
            interfaceName = new String(interfaceBytes, "US-ASCII");
            interfaceName = interfaceName.trim();
        } catch (java.io.UnsupportedEncodingException e) {
            e.printStackTrace();
        } catch (NullPointerException e) {
            e.printStackTrace();
        }

        int method = event.getByte();

        log("handlePcscfDiscoveryRequest [" + transactionId + ", " + cid + ", " + interfaceName + ", " + method + "]");
        TransactionParam param = new TransactionParam(transactionId, event.getRequestID());
        param.cid = cid;
        putTransaction(param);

        switch (method) {
            case PcscfInfo.IMC_PCSCF_ACQUIRE_BY_NONE:
                //invalid acquire method
                rejectPcscfDiscovery(transactionId, 1);
                break;
            case PcscfInfo.IMC_PCSCF_ACQUIRE_BY_SIM:
                handlePcscfDiscoveryRequestByISim(transactionId, event);
                break;
            case PcscfInfo.IMC_PCSCF_ACQUIRE_BY_MO:
                handlePcscfDiscoveryRequestByMo(transactionId, event);
                break;
            case PcscfInfo.IMC_PCSCF_ACQUIRE_BY_PCO:
                handlePcscfDiscoveryRequestByPco(transactionId, cid, event);
                break;
            case PcscfInfo.IMC_PCSCF_ACQUIRE_BY_DHCPv4:
            case PcscfInfo.IMC_PCSCF_ACQUIRE_BY_DHCPv6:
                handlePcscfDiscoveryRequestByDhcp(transactionId, interfaceName, method, event);
                break;
            case PcscfInfo.IMC_PCSCF_ACQUIRE_BY_MANUAL:
                handlePcscfDiscoveryRequestByManual(transactionId, event);
                break;
            default:
                loge("handlePcscfDiscoveryRequest receive unknown method [" + method + "]");
        }
    }

    private void handlePcscfDiscoveryRequestByPco(int transactionId, int cid, VaEvent event) {
        log("handlePcscfDiscoveryRequestByPco [" + transactionId + ", " + cid + "]");
        int result = pcscfDiscovery(IMS_APN, cid,
                    mHandler.obtainMessage(MSG_PCSCF_DISCOVERY_PCO_DONE, transactionId, cid));
        if (result < 0) {
            loge("handlePcscfDiscoveryRequestByPco failed [" + result + "]");
            rejectPcscfDiscovery(transactionId, 1);
        }
    }

    private void handlePcscfDiscoveryRequestByDhcp(int transactionId, String interfaceName, int method, VaEvent event) {
        log("handlePcscfDiscoveryRequestByDhcp [" + transactionId + ", " + interfaceName + "]");
        PcscfDiscoveryDchpThread thread = new PcscfDiscoveryDchpThread(transactionId, interfaceName, event,
            method == PcscfInfo.IMC_PCSCF_ACQUIRE_BY_DHCPv4 ? PcscfDiscoveryDchpThread.ACTION_GET_V4 : PcscfDiscoveryDchpThread.ACTION_GET_V6);
        thread.start();
    }

    private void handlePcscfDiscoveryRequestByISim(int transactionId, VaEvent event) {
        String [] pcscf = TelephonyManager.getDefault().getIsimPcscf();
        if (pcscf == null || pcscf.length == 0) {
            loge("handlePcscfDiscoveryRequestByISim but no P-CSCF found");
            rejectPcscfDiscovery(transactionId, 1);
        } else {
             PcscfInfo pcscfInfo = new PcscfInfo(PcscfInfo.IMC_PCSCF_ACQUIRE_BY_SIM, pcscf); //port is not specified
             responsePcscfDiscovery(transactionId, pcscfInfo);
        }
    }

    private void handlePcscfDiscoveryRequestByMo(int transactionId, VaEvent event) {
        // TODO: Add this code later [start]
        /*
        IImsManagerService service = mDataDispatcherUtil.getImsService();
        if (service == null) {
            loge("handlePcscfDiscoveryRequestByMo but cannot get ImsManager for MO");
            rejectPcscfDiscovery(transactionId, 1);
        } else {
            PcscfInfo pcscfInfo = new PcscfInfo();
            pcscfInfo.source = PcscfInfo.IMC_PCSCF_ACQUIRE_BY_MO;

            try {
                String moPcscf = service.readImsMoString(ImsConfig.IMS_MO_PCSCF);
                if (moPcscf == null || moPcscf.length() == 0) {
                    log("handlePcscfDiscoveryRequestByMo and no MO P-CSCF is found (continue check LBO P-CSCF");
                } else {
                    log("handlePcscfDiscoveryRequestByMo and MO P-CSCF is found [" + moPcscf + "]");
                    pcscfInfo.add(moPcscf, 0); //port is not specified
                }

                ImsLboPcscf[] imsLboPcscfArray = service.readImsLboPcscfMo();
                if (imsLboPcscfArray == null) {
                    log("handlePcscfDiscoveryRequestByMo and no LBO P-CSCF is found");
                } else {
                    for(ImsLboPcscf imsLboPcscf : imsLboPcscfArray) {
                        String lboPcscf = imsLboPcscf.getLboPcscfAddress();
                        if (lboPcscf != null && lboPcscf.length() > 0) {
                            log("handlePcscfDiscoveryRequestByMo and LBO P-CSCF is found [" + lboPcscf +  "]");
                            pcscfInfo.add(lboPcscf, 0); //port is not specified
                        }
                    }
                }
            } catch (RemoteException e) {
                e.printStackTrace();
            }

            if (pcscfInfo.getPcscfAddressCount() == 0) {
                loge("handlePcscfDiscoveryRequestByMo but no any P-CSCF is found");
                rejectPcscfDiscovery(transactionId, 1);
            } else {
                responsePcscfDiscovery(transactionId, pcscfInfo);
            }
            
        }
        */
        // TODO: add this code later [end]
    }

    private void handlePcscfDiscoveryRequestByManual(int transactionId, VaEvent event) {
        String pcscf = SystemProperties.get(PROPERTY_MANUAL_PCSCF_ADDRESS);
        if (pcscf == null || pcscf.length() == 0) {
            loge("handlePcscfDiscoveryRequest (manual) invalid P-CSCF system property");
            rejectPcscfDiscovery(transactionId, 1);
        } else {
            int port = SystemProperties.getInt(PROPERTY_MANUAL_PCSCF_PORT, 0);
            log("handlePcscfDiscoveryRequest (manual) P-CSCF system property [address=" + pcscf + ", port=" + port + "]");

            PcscfInfo pcscfInfo = new PcscfInfo();
            pcscfInfo.source = PcscfInfo.IMC_PCSCF_ACQUIRE_BY_MANUAL;
            pcscfInfo.add(pcscf, port);

            responsePcscfDiscovery(transactionId, pcscfInfo);
        }
    }

    // TODO: Default bearer [start]
    private void onDefaultBearerDataConnFail(Intent intent, String apnType) {
        log("onDefaultBearerDataConnFail apnType=" + apnType);

        boolean hasTransaction = false;
        String reason = intent.getStringExtra(PhoneConstants.STATE_CHANGE_REASON_KEY);
        int nPhoneId = intent.getIntExtra(PhoneConstants.PHONE_KEY, -1);
        
        synchronized (mTransactions) {
            Integer[] transactionKeyArray = getTransactionKeyArray();
            for (Integer transactionId : transactionKeyArray) {
                TransactionParam param = getTransaction(transactionId);
                if (VaConstants.MSG_ID_REQUEST_BEARER_ACTIVATION == param.requestId) {
                    hasTransaction = true;
                    int nfailCause = getLastDataConnectionFailCause(apnType);
                    rejectDefaultBearerDataConnActivation(param, nfailCause);
                }
            }
        }

        if (!hasTransaction) {
            //no matched transaction
            loge("onDefaultBearerDataConnFail but no transaction found");
        }
    }

    private void onDefaultBearerDataConnStateChanged(Intent intent, String apnType) {
        PhoneConstants.DataState state = Enum.valueOf(PhoneConstants.DataState.class, intent.getStringExtra(PhoneConstants.STATE_KEY));
        String reason = intent.getStringExtra(PhoneConstants.STATE_CHANGE_REASON_KEY);
        int nPhoneId = intent.getIntExtra(PhoneConstants.PHONE_KEY, -1);
        boolean hasTransaction = false;
        
        log("onDefaultBearerDataConnStateChanged, state: " + state + ", reason: " + reason + ", apnType: " + apnType);

        synchronized (mTransactions) {
            Integer[] transactionKeyArray = getTransactionKeyArray();
            for (Integer transactionId : transactionKeyArray) {
                TransactionParam param = getTransaction(transactionId);
                if ((IMS_APN.equals(apnType) && param.isEmergency != true) ||
                    (EMERGENCY_APN.equals(apnType) && param.isEmergency == true)) {
                    switch (param.requestId) {
                    case VaConstants.MSG_ID_REQUEST_BEARER_ACTIVATION:
                        // Do something here
                        if (state == PhoneConstants.DataState.CONNECTED) {
                            hasTransaction = true;
                            LinkProperties lp = intent.getParcelableExtra(PhoneConstants.DATA_LINK_PROPERTIES_KEY);
                            responseDefaultBearerDataConnActivated(param, apnType, lp);
                            // need to get IMS address here
                            getIMSGlobalIpAddr(apnType, lp);
                        } else if (state == PhoneConstants.DataState.DISCONNECTED) {
                            rejectDefaultBearerDataConnActivation(param, FAILCAUSE_UNKNOWN);
                        }
                        break;
                    case VaConstants.MSG_ID_REQUEST_BEARER_DEACTIVATION:
                        if (state == PhoneConstants.DataState.DISCONNECTED) {
                            hasTransaction = true;
                            int [] cidArray = getDeactivateCidArray(apnType);
                            if(null != cidArray && cidArray[0] == param.cid) { //response succees
                                responseDefaultBearerDataConnDeactivated(param);

                                if (mPcscfDiscoveryDchpThread != null) {
                                    log("IMS PDN is deactivated and to interrupt P-CSCF discovery thread");
                                    mPcscfDiscoveryDchpThread.interrupt();
                                    mPcscfDiscoveryDchpThread = null;
                                }
                            } else if (null == cidArray){
                                responseDefaultBearerDataConnDeactivated(param);
                            }
                            
                            if (param.isEmergency) { 
                                setEmergencyCid(INVALID_CID);
                            }
                        }
                        break;
                    default:
                        log("onDefaultBearerDataConnStateChanged received unhandled state change event [" + transactionId + " " + param.requestId + "]");
                    }
                }
            }
        }

        log("onDefaultBearerDataConnStateChanged hasTrasaction: " + hasTransaction);
        if (!hasTransaction) {
            //no matched transactions, need to notify Va the state is changed
            switch (state) {
                case DISCONNECTED:
                    {
                        int [] cidArray = getDeactivateCidArray(apnType);
                        int nfailCause = getLastDataConnectionFailCause(apnType);
                        boolean bStopPcscfThread = true;
                        if(null != cidArray) {
                            log("deactivate cid size: " + cidArray.length);
                            for(int i = 0; i < cidArray.length; i++) {
                                notifyDefaultBearerDataConnDeactivated(cidArray[i], nfailCause);
                            }
                            // TODO: need to add this code back for L migration [start] 
                        } else if (/*DcFailCause.LOST_CONNECTION.toString()*/"LOST_CONNECTION".equals(reason)) {
                            // TODO: need to add this code back for L migration [end]
                            int size = mDeactivateCidArray.size();
                            log("deactivate cid(s): " + mDeactivateCidArray + ", size: " + size);
                            for(int i = 0; i < size; i++) {
                                notifyDefaultBearerDataConnDeactivated(mDeactivateCidArray.get(i),
                                                            nfailCause);
                            }
                        } else {
                            loge("can't get any cids, no response deactivated default bearer!!");
                            bStopPcscfThread = false;
                        }

                        if (bStopPcscfThread && mPcscfDiscoveryDchpThread != null) {
                            log("IMS PDN is deactivated and to interrupt P-CSCF discovery thread");
                            mPcscfDiscoveryDchpThread.interrupt();
                            mPcscfDiscoveryDchpThread = null;
                        }
                        clearDeactivateCidArray();
                        if (EMERGENCY_APN.equals(apnType)) {
                            setEmergencyCid(INVALID_CID);
                        }
                    }
                    break;
                case CONNECTED:
                    log("Connected but currently no notify");
                    break;
                default:
                    ;
            }
        }
    }

    private void responseDefaultBearerDataConnActivated(TransactionParam param, String apnType, LinkProperties lp) {
        if (hasTransaction(param.transactionId)) {
            boolean bResponse = true;
            ImsAdapter.VaEvent event = new ImsAdapter.VaEvent(VaConstants.MSG_ID_RESPONSE_BEARER_ACTIVATION, SIZE_DEFAULT_BEARER_RESPONSE);
            int pdnCnt = 0;
            DedicateBearerProperties defaultBearerProp, defaultBearerPropEmpty;
            int ipMask = PDP_ADDR_MASK_NONE;

            log("responseDefaultBearerDataConnActivated " /*+ param + ", " + property*/);
            //imcf_uint8 transaction_id;
            //imcf_uint8 count;
            //imcf_uint8 pad[2];
            //imc_pdn_context_struct  contexts [2]

            // (member of imc_pdn_context_struct)
            //imc_pdp_addr_type_enum pdp_addr_type
            //imcf_uint8 pad2[3]
            //imc_single_concatenated_msg_struct  main_context;
            //imcf_uint8 num_of_concatenated_contexts;
            //imcf_uint8 pad2[3];
            //imc_single_concatenated_msg_struct concatenated_context[IMC_MAX_CONCATENATED_NUM];

            event.putByte(param.transactionId);
            //Check address type here
            int pdp_addr_type = PDP_ADDR_TYPE_NONE;
            if(null != lp) {
                for(LinkAddress linkAddr : lp.getLinkAddresses()) {
                    InetAddress addr = linkAddr.getAddress();
                    if (addr instanceof Inet6Address) {
                        log("ipv6 type");
                        ipMask |= PDP_ADDR_MASK_IPV6;
                    } else if (addr instanceof Inet4Address) {
                        log("ipv4 type");
                        ipMask |= PDP_ADDR_MASK_IPV4;
                    } else {
                        loge("invalid address type");
                        ipMask |= PDP_ADDR_MASK_IPV4;
                    }
                }
            } else {
                loge("Error: get null link properties");
            }

            switch (ipMask) {
                case PDP_ADDR_MASK_IPV4v6:
                    pdp_addr_type = PDP_ADDR_TYPE_IPV4v6;
                    break;
                case PDP_ADDR_MASK_IPV6:
                    pdp_addr_type = PDP_ADDR_TYPE_IPV6;
                    break;
                case PDP_ADDR_MASK_IPV4:
                    pdp_addr_type = PDP_ADDR_TYPE_IPV4;
                case PDP_ADDR_MASK_NONE:
                    // skip // error ??? (shouldn't be this)
                default:
                    // using default ipv4 (shouldn't be this)
                    break;
            };

            //imc_pdn_context_struct here
            defaultBearerProp = getDefaultBearerProperties(apnType);
            log("responseDefaultBearerDataConnActivated: " + defaultBearerProp);
            defaultBearerPropEmpty = new DedicateBearerProperties();
            DedicateBearerProperties [] pdnContextsForVa = {defaultBearerPropEmpty, defaultBearerPropEmpty};
            int [] msgType = {IMC_CONCATENATED_MSG_TYPE_NONE, IMC_CONCATENATED_MSG_TYPE_NONE};
            if (null == defaultBearerProp) {
                //error happnening
                pdnContextsForVa[0] = defaultBearerPropEmpty;
                bResponse = false;
                loge("error happenening , default breaer should not be null");
            } else {
                // TODO: Check defaultBearer data valid or not
                if(defaultBearerProp.interfaceId == -1 && defaultBearerProp.cid == -1) {
                    log("invalid defaultBearerProp, interface id(" + defaultBearerProp.interfaceId +
                        "), cid(" + defaultBearerProp.cid + ")");

                    bResponse = false; // need to reject to VA and let VA retry
                }

                pdnCnt++;
                for(int i = 0; i < defaultBearerProp.concatenateBearers.size(); i++) {
                    DedicateBearerProperties bearerProp = defaultBearerProp.concatenateBearers.get(i);
                    if (defaultBearerProp.defaultCid == bearerProp.defaultCid) {
                        continue;
                    }
                    pdnCnt++;
                }

                //imc_pdn_count 
                event.putByte(pdnCnt);
                event.putBytes(new byte[2]);    //padding
                if(DataDispatcherUtil.DBG) DataDispatcherUtil.dumpPdnAckRsp(event);
                
                // write main_context
                msgType[0] = IMC_CONCATENATED_MSG_TYPE_ACTIVATION;
                pdnContextsForVa[0] = defaultBearerProp;
            }

            if (bResponse == true) {
                for(int i = 0; i < pdnContextsForVa.length; i++) {
                    DataDispatcherUtil.writeAllBearersProperties(event, msgType[i], pdp_addr_type, pdnContextsForVa[i]);
                }

                synchronized (mImsNetworkInterface) {
                    mImsNetworkInterface.put(defaultBearerProp.cid, lp.getInterfaceName());
                    log("Update IMS network interface name: " + mImsNetworkInterface);
                }

                removeTransaction(param.transactionId);

                sendVaEvent(event);
            } else {
                // TODO: need to add this code back for L migration            
                rejectDefaultBearerDataConnActivation(param, FAILCAUSE_UNKNOWN);
            }

            if (param.isEmergency) {
                if (bResponse) {
                    setEmergencyCid(defaultBearerProp.cid);
                } else {
                    setEmergencyCid(INVALID_CID);
                }
            }
        } else {
            loge("responseDefaultBearerDataConnActivated but transactionId does not existed, ignore");
        }
    }

    private void responseDefaultBearerDataConnDeactivated(TransactionParam param) {
        log("responseDefaultBearerDataConnDeactivated");
        synchronized (mImsNetworkInterface) {
            mImsNetworkInterface.remove(param.cid);
        }
        responseDataConnectionDeactivated(param);
    }

    private void notifyDefaultBearerDataConnDeactivated(int cid, int cause) {
        log("notifyDefaultBearerDataConnDeactivated");
        synchronized (mImsNetworkInterface) {
            mImsNetworkInterface.remove(cid);
        }
        notifyDataConnectionDeactivated(cid, cause);
    }

    // TODO: Default bearer [end]

    private void onDedicateDataConnectionStateChanged(int ddcId, DctConstants.State state, DedicateBearerProperties property, int nfailCause/*DcFailCause failCause*/, String reason) {
        log("onDedicateDataConnectionStateChanged ddcId=" + ddcId + ", state=" + state + ", failCause=" + nfailCause + ", reason=" + reason + ", properties=" + property);
        boolean hasTransaction = false;

        synchronized (mTransactions) {
            Integer[] transactionKeyArray = getTransactionKeyArray();
            for (Integer transactionId : transactionKeyArray) {
                TransactionParam param = getTransaction(transactionId);
                if (param.ddcId == ddcId) {
                    hasTransaction = true;
                    switch (param.requestId) {
                        case VaConstants.MSG_ID_REQUEST_DEDICATE_BEARER_ACTIVATATION:
                            if (state == DctConstants.State.CONNECTED) {
                                if (nfailCause == 0 /*DcFailCause.NONE*/) {
                                    //response succees
                                    responseDedicateDataConnectionActivated(param, property);
                                } else {
                                    //response reject but have concatenated bearers
                                    rejectDedicateDataConnectionActivation(param, nfailCause, property);
                                }
                            } else if (state == DctConstants.State.FAILED || state == DctConstants.State.IDLE) {
                                //reject due to the state is not connected
                                rejectDedicateDataConnectionActivation(param, nfailCause, property);
                            }
                            break;
                        case VaConstants.MSG_ID_REQUEST_BEARER_DEACTIVATION:
                            if (param.cid == property.cid) {
                                if (REASON_BEARER_ABORT.equals(reason)) {
                                    //the transaction is to do abort, use failcause to know if the abort is success or not
                                    if (nfailCause == 0 /*DcFailCause.NONE*/) {
                                        log("onDedicateDataConnectionStateChanged to response abort success");
                                        responseDataConnectionDeactivated(param);
                                    } else {
                                        log("onDedicateDataConnectionStateChanged to response abort fail failcause=" + nfailCause);
                                        rejectDataConnectionDeactivation(transactionId, nfailCause);
                                    }
                                } else {
                                    if (state == DctConstants.State.IDLE) {
                                        //response succees
                                        responseDataConnectionDeactivated(param);
                                    } else {
                                        //reject due to the state is not idle
                                        rejectDataConnectionDeactivation(transactionId, nfailCause);
                                    }
                                }
                            } else {
                                if (property.cid == -1) {
                                    //since the property is already invalid and ril response success
                                    //it means that the bearer is already deactivated (AT+GCACT response 4105)
                                    log("onDedicateDataConnectionStateChanged ddcId is equaled but cid is already deactivated (MSG_ID_REQUEST_BEARER_DEACTIVATION)");
                                    responseDataConnectionDeactivated(param);
                                } else {
                                    //error case, ddcId is equaled but cid is not equaled
                                    loge("onDedicateDataConnectionStateChanged ddcId is equaled but cid is not equaled (MSG_ID_REQUEST_BEARER_DEACTIVATION)");
                                }
                            }
                            break;
                        case VaConstants.MSG_ID_REQUEST_BEARER_MODIFICATION:
                            if (param.cid == property.cid) {
                                if (state == DctConstants.State.CONNECTED) {
                                    //response succees
                                    responseDedicateDataConnectionModified(param, property);
                                } else {
                                    //reject due to the state is not connected
                                    rejectDedicateDataConnectionModification(param,
                                                                FAILCAUSE_UNKNOWN, property);
                                }
                            } else {
                                //error case, ddcId is equaled but cid is not equaled
                                loge("onDedicateDataConnectionStateChanged ddcId is equaled but cid is not equaled (MSG_ID_REQUEST_BEARER_MODIFICATION)");
                            }
                            break;
                        default:
                            log("onDedicateDataConnectionStateChanged received unhandled state change event [" + transactionId + " " + param.requestId + "]");
                    }
                }
            }
        }

        if (!hasTransaction) {
            //no matched transactions, need to notify Va the state is changed
            switch (state) {
                case IDLE:
                    notifyDataConnectionDeactivated(property.cid, nfailCause);
                    break;
                case FAILED:
                    loge("onDedicateDataConnectionStateChanged no matched transaction but receive state FAIL");
                    break;
                case CONNECTED:
                    //if (DedicateDataConnection.REASON_BEARER_MODIFICATION.equals(reason).equals(reason))
                    if (REASON_BEARER_MODIFICATION.equals(reason))
                        notifyDedicateDataConnectionModified(property);
                    else
                        notifyDedicateDataConnectionActivated(property);
                    break;
                default:
                    loge("onDedicateDataConnectionStateChanged not matched to any case");
            }
        }
    }

    private void responseDedicateDataConnectionActivated(TransactionParam param, DedicateBearerProperties property) {
        log("responseDedicateDataConnectionActivated " + param + ", " + property);
        responseDedicateDataConnection(param, property, IMC_CONCATENATED_MSG_TYPE_ACTIVATION);
    }

    private void responseDedicateDataConnection(TransactionParam param, DedicateBearerProperties property, int type) {
        if (hasTransaction(param.transactionId)) {
            ImsAdapter.VaEvent event = null;
            if (type == IMC_CONCATENATED_MSG_TYPE_ACTIVATION)
                event = new ImsAdapter.VaEvent(VaConstants.MSG_ID_RESPONSE_DEDICATE_BEARER_ACTIVATION);
            else
                event = new ImsAdapter.VaEvent(VaConstants.MSG_ID_RESPONSE_BEARER_MODIFICATION);

            log("responseDedicateDataConnection type=" + type + ", param" + param + ", property" + property);
            int concatenateBearersSize = property.concatenateBearers.size();
            //imcf_uint8 transaction_id;
            //imc_ps_cause_enum ps_cause;
            //imcf_uint8 pad[2];
            //imc_single_concatenated_msg_struct  main_context;
            //imcf_uint8 num_of_concatenated_contexts;
            //imcf_uint8 pad2[3];
            //imc_single_concatenated_msg_struct concatenated_context[IMC_MAX_CONCATENATED_NUM];
            event.putByte(param.transactionId);
            event.putByte(0);
            event.putBytes(new byte[2]); //padding
            DataDispatcherUtil.writeDedicateBearer(event, type, property);
            event.putByte(concatenateBearersSize); // write concatenated number
            event.putBytes(new byte[3]); // padding
            for (int i = 0; i < DataDispatcherUtil.IMC_MAX_CONCATENATED_NUM; i++) { // write concatenated contexts
                if (i < concatenateBearersSize) {
                    DataDispatcherUtil.writeDedicateBearer(event, type, property.concatenateBearers.get(i));
                } else {
                    DataDispatcherUtil.writeDedicateBearer(event, type, new DedicateBearerProperties());
                }
            }

            removeTransaction(param.transactionId);

            sendVaEvent(event);
        } else {
            loge("responseDedicateDataConnection but transactionId does not existed, ignore");
        }
    }

    private void notifyDedicateDataConnectionActivated(DedicateBearerProperties property) {
        log("notifyDedicateDataConnectionActivated property" + property);
        notifyDedicateDataConnection(property, IMC_CONCATENATED_MSG_TYPE_ACTIVATION);
    }

    private void notifyDedicateDataConnection(DedicateBearerProperties property, int type) {
        synchronized (mImsNetworkInterface) {
            if (mImsNetworkInterface.get(property.defaultCid) == null) {
                loge("notifyDedicateDataConnection but default bearer does not existed, type=" + type + ", " + property);
                return;
            }
        }

        ImsAdapter.VaEvent event = null;
        if (type == IMC_CONCATENATED_MSG_TYPE_ACTIVATION)
            event = new ImsAdapter.VaEvent(VaConstants.MSG_ID_NOTIFY_DEDICATE_BEARER_ACTIVATED);
        else
            event = new ImsAdapter.VaEvent(VaConstants.MSG_ID_NOTIFY_BEARER_MODIFIED);

        log("notifyDedicateDataConnection type=" + type + ", property" + property);
        //imc_ps_cause_enum ps_cause
        //imcf_uint8 pad [3]
        //imc_single_concatenated_msg_struct main_context
        //imcf_uint8 num_of_concatenated_contexts
        //imcf_uint8 pad2 [3]
        //imc_single_concatenated_msg_struct concatenated_context [IMC_MAX_CONCATENATED_NUM]
        event.putByte(0);
        event.putBytes(new byte[3]); //padding
        DataDispatcherUtil.writeDedicateBearer(event, type, property);
        event.putBytes(new byte[3]); //padding
        for (int i=0; i<DataDispatcherUtil.IMC_MAX_CONCATENATED_NUM; i++) {
            if (i<property.concatenateBearers.size()) {
                DataDispatcherUtil.writeDedicateBearer(event, type, property.concatenateBearers.get(i));
            } else {
                DataDispatcherUtil.writeDedicateBearer(event, type, new DedicateBearerProperties());
            }
        }

        log("DataDispatcher send event [" + event.getRequestID()+ ", " + event.getDataLen() + "]");
        mSocket.writeEvent(event);
    }

    private void rejectDedicateDataConnectionActivation(TransactionParam param, int failCause, DedicateBearerProperties property) {
        log("rejectDedicateBearerActivation param" + param + ", failCause=" + failCause + ", property" + property);
        rejectDedicateDataConnection(param, failCause, property, IMC_CONCATENATED_MSG_TYPE_ACTIVATION);
    }

    private void rejectDedicateDataConnection(TransactionParam param, int failCause, DedicateBearerProperties property, int type) {
        if (hasTransaction(param.transactionId)) {
            ImsAdapter.VaEvent event = null;
            if (type == IMC_CONCATENATED_MSG_TYPE_ACTIVATION)
                event = new ImsAdapter.VaEvent(VaConstants.MSG_ID_REJECT_DEDICATE_BEARER_ACTIVATION);
            else
                event = new ImsAdapter.VaEvent(VaConstants.MSG_ID_REJECT_BEARER_MODIFICATION);

            log("rejectDedicateDataConnection type=" + type + ", param" + param + ", failCause=" + failCause + "property" + property);
            //imcf_uint8 transaction_id
            //imc_ps_cause_enum ps_cause
            //imcf_uint8 num_of_concatenated_contexts
            //imcf_uint8 pad [1]
            //imc_single_concatenated_msg_struct concatenated_context [IMC_MAX_CONCATENATED_NUM]

            event.putByte(param.transactionId);
            event.putByte(failCause); //cause
            if (property == null) {
                event.putByte(0); //concatenated number
            } else {
                event.putByte(property.concatenateBearers.size()+1);
                //add one since the property itself and its concatenated bearer should all be counted
            }

            event.putByte(0); //padding

            if (property == null)
                DataDispatcherUtil.writeDedicateBearer(event, type, new DedicateBearerProperties());
            else
                DataDispatcherUtil.writeDedicateBearer(event, type, property); //write property itself

            for (int i=0; i<DataDispatcherUtil.IMC_MAX_CONCATENATED_NUM - 1; i++) {
                if (property == null || i >= property.concatenateBearers.size())
                    DataDispatcherUtil.writeDedicateBearer(event, type, new DedicateBearerProperties());
                else
                    DataDispatcherUtil.writeDedicateBearer(event, type, property.concatenateBearers.get(i)); //write its concatenate bearers
            }

            removeTransaction(param.transactionId);

            log("DataDispatcher send event [" + event.getRequestID()+ ", " + event.getDataLen() + "]");
            mSocket.writeEvent(event);
        } else {
            loge("rejectDedicateDataConnection but transactionId does not existed, ignore");
        }
    }

    private void responseDataConnectionDeactivated(TransactionParam param) {
        if (hasTransaction(param.transactionId)) {
            ImsAdapter.VaEvent event = new ImsAdapter.VaEvent(VaConstants.MSG_ID_RESPONSE_BEARER_DEACTIVATION);
            log("responseDataConnectionDeactivated param" + param);
            //imcf_uint8 transaction_id
            //imcf_uint8 context_id
            //imcf_uint8 pad [2]
            event.putByte(param.transactionId);
            event.putByte(param.cid);
            event.putBytes(new byte[2]); //padding

            removeTransaction(param.transactionId);

            sendVaEvent(event);
        } else {
            loge("responseDataConnectionDeactivated but transactionId does not existed, ignore");
        }
    }

    private void notifyDataConnectionDeactivated(int cid, int cause) {
        ImsAdapter.VaEvent event = new ImsAdapter.VaEvent(VaConstants.MSG_ID_NOTIFY_BEARER_DEACTIVATED);
        log("notifyDedicateDataConnectionDeactivated cid=" + cid + ", cause=" + cause);
        //imcf_uint8  context_id
        //imc_ps_cause_enum   ps_cause
        //imcf_uint8  pad [2]
        event.putByte(cid);
        event.putByte(cause);
        event.putBytes(new byte[2]); //padding

        sendVaEvent(event);
        
        //reject pcscf discorvery if default bearer is deactivated
        synchronized (mTransactions) {
            Integer[] transactionKeyArray = getTransactionKeyArray();
            for (Integer transactionId : transactionKeyArray) {
                TransactionParam param = getTransaction(transactionId);
                if (param.requestId == VaConstants.MSG_ID_REQUEST_PCSCF_DISCOVERY &&
                    param.cid == cid) {
                    rejectPcscfDiscovery(transactionId, 1);
                }
            }
        }
    }

    private void rejectDataBearerDeactivation(int transactionId, int cause) {
        ImsAdapter.VaEvent event = new ImsAdapter.VaEvent(VaConstants.MSG_ID_REJECT_BEARER_DEACTIVATION);
        log("rejectDataBearerDeactivation transactionId=" + transactionId + ", cause=" + cause);
        //imcf_uint8 transaction_id
        //imc_ps_cause_enum ps_cause
        //imcf_uint8 pad[2]
        event.putByte(transactionId);
        event.putByte(cause);
        event.putBytes(new byte[2]); //padding

        removeTransaction(transactionId);
        sendVaEvent(event);
    }

    private void rejectDataConnectionDeactivation(int transactionId, int cause) {
        if (hasTransaction(transactionId)) {
            log("rejectBearerDeactivation transactionId=" + transactionId + ", cause=" + cause);
            rejectDataBearerDeactivation(transactionId, cause);
        } else {
            loge("rejectDataConnectionDeactivation but transactionId does not existed, ignore");
        }
    }

    private void responseDedicateDataConnectionModified(TransactionParam param, DedicateBearerProperties property) {
        log("responseDedicateDataConnectionModified param" + param + ", property" + property);
        //typedef imsa_imcb_dedicated_bearer_act_ack_rsp_struct imsa_imcb_modify_ack_rsp_struct;
        responseDedicateDataConnection(param, property, IMC_CONCATENATED_MSG_TYPE_MODIFICATION);
    }

    private void notifyDedicateDataConnectionModified(DedicateBearerProperties property) {
        log("notifyDedicateDataConnectionModified [" + property + "]");
        //typedef imsa_imcb_dedicated_bearer_act_notify_req_struct imsa_imcb_modify_notify_req_struct;
        notifyDedicateDataConnection(property, IMC_CONCATENATED_MSG_TYPE_MODIFICATION);
    }

    private void rejectDedicateDataConnectionModification(TransactionParam param, int failCause, DedicateBearerProperties property) {
        log("rejectDedicateDataConnectionModification param=" + param + ", failCause=" + failCause + ", property=" + property);
        //typedef imsa_imcb_dedicated_bearer_act_rej_rsp_struct imsa_imcb_modify_rej_rsp_struct;
        rejectDedicateDataConnection(param, failCause, property, IMC_CONCATENATED_MSG_TYPE_MODIFICATION);
    }

    private void responsePcscfDiscovery(int transactionId, PcscfInfo pcscfInfo) {
        if (hasTransaction(transactionId)) {
            ImsAdapter.VaEvent event = new ImsAdapter.VaEvent(VaConstants.MSG_ID_RESPONSE_PCSCF_DISCOVERY);
            log("responsePcscfDiscovery transactionId=" + transactionId + ", Pcscf" + pcscfInfo);
            //imcf_uint8 transaction_id
            //imc_pcscf_acquire_method_enum pcscf_aqcuire_method
            //imcf_uint8 pad [2]
            //imc_pcscf_list_struct pcscf_list
            event.putByte(transactionId);
            event.putByte(pcscfInfo.source);
            event.putBytes(new byte[2]); //padding
            DataDispatcherUtil.writePcscf(event, pcscfInfo);

            removeTransaction(transactionId);
            sendVaEvent(event);
        } else {
            loge("responsePcscfDiscovery but transactionId does not existed, ignore");
        }
    }

    private void rejectPcscfDiscovery(int transactionId,int failCause) {
        if (hasTransaction(transactionId)) {
            ImsAdapter.VaEvent event = new ImsAdapter.VaEvent(VaConstants.MSG_ID_REJECT_PCSCF_DISCOVERY);
            log("rejectPcscfDiscovery transactionId=" + transactionId + ", failCause=" + failCause);
            //imcf_uint8 transaction_id
            //imc_ps_cause_enum ps_caus
            //imcf_uint8 pad [2]
            event.putByte(transactionId);
            event.putByte(failCause);
            event.putBytes(new byte[2]); //padding

            removeTransaction(transactionId);

            sendVaEvent(event);
        } else {
            loge("rejectPcscfDiscovery but transactionId does not existed, ignore");
        }
    }

    private void onNotifyGlobalIpAddr(InetAddress inetIpAddr, String apnType, String intfName) {
        int ipAddrType;
        int cid;
        int msgId = -1;
        byte [] ipAddrByteArray = null;
        InetAddress inetAddr = inetIpAddr;

        if (false == isApnIMSorEmergency(apnType)) {
            loge("onNotifyGlobalIpAddr invalid apnType: " + apnType);
            return;
        }

        if (intfName.isEmpty() == true) {
            loge("onNotifyGlobalIpAddr interface name is empty");
            return;
        }

        if (inetAddr instanceof Inet6Address) {
            msgId = VaConstants.MSG_ID_NOTIFY_IPV6_GLOBAL_ADDR;
        } else if (inetAddr instanceof Inet4Address) {
            log("onNotifyGlobalIpAddr IPAddress Type ipV4");
            msgId = VaConstants.MSG_ID_NOTIFY_IPV4_GLOBAL_ADDR;
        } else {
            loge("onNotifyGlobalIpAddr unknown IPAddress Type (using IPV4)");
            // TODO: temp using ipv4 here
            msgId = VaConstants.MSG_ID_NOTIFY_IPV4_GLOBAL_ADDR;
            //return;
        }

        // get cid first
        DedicateBearerProperties defaultBearerProperties = getDefaultBearerProperties(apnType);
        if (defaultBearerProperties == null) {
            loge("onNotifyGlobalIpAddr default bearer properties is null, can't get cid");
            return;
        }

        cid = defaultBearerProperties.defaultCid;

        // convert address to byte array
        ipAddrByteArray = inetAddr.getAddress();
        log("onNotifyGlobalIpAddr intfName: " + intfName + ", cid: " + cid + ", byte addr length: " + ipAddrByteArray.length);

        if (ipAddrByteArray == null) {
            loge("onNotifyGlobalIpAddr invalid ipAddrByteArray (null)");
            return;
        }

        synchronized (mImsNetworkInterface) {
            if (mImsNetworkInterface.get(cid) == null) {
                loge("onNotifyGlobalIpAddr invalid CID [" + cid + "]");
                return;
            }
        }

        try {
            Network network = getConnectivityManager().getNetworkForType(
                                             convertImsOrEmergencyNetworkType(apnType));
            ImsAdapter.VaEvent event = mDataDispatcherUtil.composeGlobalIPAddrVaEvent(msgId, cid, network.netId,
                                                                ipAddrByteArray, intfName);
        sendVaEvent(event);
        } catch (NullPointerException e) {
            loge("null pointer exception!!");
            e.printStackTrace();
        }
    }

    protected boolean hasTransaction(int transactionId) {
        synchronized (mTransactions) {
            return (mTransactions.get(transactionId) != null);
        }
    }

    protected void putTransaction(TransactionParam param) {
        synchronized (mTransactions) {
            mTransactions.put(param.transactionId, param);
            if (DUMP_TRANSACTION) dumpTransactions();
        }
    }

    protected void removeTransaction(int transactionId) {
        synchronized (mTransactions) {
            mTransactions.remove(transactionId);
            if (DUMP_TRANSACTION) dumpTransactions();
        }
    }

    protected TransactionParam getTransaction(int transactionId) {
        synchronized (mTransactions) {
            return mTransactions.get(transactionId);
        }
    }

    protected Integer[] getTransactionKeyArray() {
        synchronized (mTransactions) {
            Object[] array = mTransactions.keySet().toArray();
            if (array == null) {
                return new Integer[0];
            } else {
                Integer[] intArray = new Integer[array.length];
                for (int i=0; i<array.length; i++)
                    intArray[i] = (Integer)array[i];
                return intArray;
            }
        }
    }

    protected void dumpTransactions() {
       if (mTransactions.size() > 0) {
           log("====Start dump [transactions]====");
           for (TransactionParam param : mTransactions.values())
               log("dump transactions" + param);
           log("====End dump [transactions]====");
       } else {
           log("====dump [transactions] but empty====");
       }
    }

    private static void log(String text) {
        Xlog.d(TAG, "[dedicate] DataDispatcher " + text);
    }

    private static void loge(String text) {
        Xlog.e(TAG, "[dedicate] DataDispatcher " + text);
    }

    private class TransactionParam {
        public int transactionId;
        public int requestId;
        public int cid = -1;
        public int ddcId = -1;
        public boolean isEmergency = false;

        public TransactionParam() {
        }

        public TransactionParam(int tid, int reqId) {
          transactionId = tid;
          requestId = reqId;
        }

        @Override
        public String toString() {
          return "[transactionId=" + transactionId + ", request=" + requestId + ", cid=" + cid + ", ddcid=" + ddcId + "]";
        }
    }

    private class PcscfDiscoveryDchpThread extends Thread {
        private static final int ACTION_GET_V4 = 1;
        private static final int ACTION_GET_V6 = 2;
        private static final int ACTION_CLEAR = 3;
        private final String[] SERVICE_TYPE_ARRAY = {"SIP+D2T", "SIPS+D2T", "SIP+D2U"};

        private int mTransactionId;
        private String mInterfaceName;
        private VaEvent mEvent;
        private int mAction;

        public PcscfDiscoveryDchpThread(int transactionId, String interfaceName, VaEvent event, int action) {
            mTransactionId = transactionId;
            mInterfaceName = interfaceName;
            mEvent = event;
            mAction = action;
        }

        public String getInterfaceName() {
            return mInterfaceName;
        }

        @Override
        public void interrupt() {
            clearSipInfo();
            super.interrupt();
        }

        private void getSipInfo() {
            /* ==In INetworkManagementService==
             *  String[] getSipInfo(String interfaceName, String service, Sting protocol)
             * @param interfaceName input
             * @param service input (service type is "SIP+D2U", "SIP+D2T", "SIPS+D2T", for UDP/TCP/TSL service
             * @param protocol type ("v4" or "v6")
             * @return result_array output, String[0] = hostname, String[1] = port
             * @hide
             */

            if (!NetworkUtils.doSipDhcpRequest(mInterfaceName)) {
                loge("PCSCF discovery doSipDhcpRequest response fail [interface=" + mInterfaceName + "]");
                rejectPcscfDiscovery(mTransactionId, 1);
                return;
            }

            PcscfInfo pcscfInfo = null;

            for (String serviceType : SERVICE_TYPE_ARRAY) {
                String[] pcscfHost = null;
                byte[] pcscfByteArray = null;
                String pcscf = null;
                int port = 0;
                try {
                    INetworkManagementService netd = INetworkManagementService.
                        Stub.asInterface(ServiceManager.getService(
                                        Context.NETWORKMANAGEMENT_SERVICE));
                    if (ACTION_GET_V4 == mAction)
                        pcscfHost = netd.getSipInfo(mInterfaceName, serviceType, "v4");
                    else
                        pcscfHost = netd.getSipInfo(mInterfaceName, serviceType, "v6");    

                } catch (RemoteException e) {
                    e.printStackTrace();
                } catch (IllegalStateException e) {
                    e.printStackTrace();
                } catch (IllegalArgumentException e) {
                    e.printStackTrace();
                } catch (NullPointerException e) {
                    e.printStackTrace();
                }

                if (isInterrupted()) {
                    loge("reject PCSCF discovery DHCP due to the dhcp thread is interrupted before DNS query [" + serviceType + "]");
                    rejectPcscfDiscovery(mTransactionId, 1);
                    return;
                } else if (pcscfHost != null) {
                    try {
                        log("PCSCF discovery DHCP result [host=" + pcscfHost[0] + ", port=" + pcscfHost[1] + "]");
                        InetAddress inetAddress = InetAddress.getByName(pcscfHost[0]);
                        pcscfByteArray = inetAddress.getAddress();
                        port = Integer.parseInt(pcscfHost[1]);
                    } catch (UnknownHostException e) {
                        e.printStackTrace();
                    }
                } else {
                    loge("PCSCF discovery DHCP but no SIP response [" + serviceType + "]");
                }

                if (isInterrupted()) {
                    loge("reject PCSCF discovery DHCP due to the dhcp thread is interrupted after DNS query [" + serviceType + "]");
                    rejectPcscfDiscovery(mTransactionId, 1);
                    return;
                } else if (pcscfByteArray != null && pcscfByteArray.length > 0) {
                    StringBuffer buf = new StringBuffer(pcscfByteArray.length);
                    for (int i=0; i<pcscfByteArray.length; i++) {
                        if (i == 0)
                            buf.append((int)pcscfByteArray[i]);
                        else
                            buf.append("." + (int)pcscfByteArray[i]);
                    }
                    pcscf = buf.toString();

                    if (pcscfInfo == null) {
                        //here we try to create the response when first time we need to add a P-CSCF address
                        pcscfInfo = new PcscfInfo();
                    }
                    loge("PCSCF discovery DHCP get server address [" + pcscf + ", port=" + port + ", serviceTYpe=" + serviceType + "]");
                    pcscfInfo.add(pcscf, port);
                } else {
                    loge("PCSCF discovery DHCP but empty SIP host [" + serviceType + "]");
                }
            }

            if (pcscfInfo != null)
                responsePcscfDiscovery(mTransactionId, pcscfInfo);
            else
                rejectPcscfDiscovery(mTransactionId, 1);
        }

        private void clearSipInfo() {
            /* ==In INetworkManagementService==
             * void clearSipInfo(String interfaceName)
             * @hide
             * @param interfaceName input
             */

            try {
                INetworkManagementService netd = INetworkManagementService.Stub.asInterface(
                                   ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE));
                netd.clearSipInfo(mInterfaceName); 
            } catch (RemoteException e) {
                e.printStackTrace();
            } catch (IllegalStateException e) {
                e.printStackTrace();
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
            } catch (NullPointerException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void run() {
            //make sure there is only one thread running
            synchronized (PcscfDiscoveryDchpThread.class) {
                if (mAction == ACTION_GET_V4 || mAction == ACTION_GET_V6) {
                    log("PCSCF discovery DHCP thread started [threadid=" + getId() + ", " + (mAction == ACTION_GET_V4 ? "ACTION_GET_V4]" : "ACTION_GET_V6]"));
                    getSipInfo();
                } else {
                    log("PCSCF discovery DHCP thread started [threadid=" + getId() + ", CLEAR]");
                    clearSipInfo();
                }

                log("PCSCF discovery DHCP thread finished [threadid=" + getId() + "]");
            }
        }
    }

    private void delayForSeconds(int seconds) {
        try {
            Thread.sleep(seconds);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private boolean isApnIMSorEmergency(String apnType) {
        return (IMS_APN.equals(apnType) || EMERGENCY_APN.equals(apnType)) ? true : false;
    }

    private boolean isMsgAllowed(String apnType, int changed) {
        return (isApnIMSorEmergency(apnType)&& changed == 1) ? true : false;
    }

    private void clearDeactivateCidArray() {
        log("clearDeactivateCidArray, " + mDeactivateCidArray);
        mDeactivateCidArray.clear();
    }
    private void setDeactivateCidArray(int [] cidArray) {
        clearDeactivateCidArray();
        if (cidArray != null) {
            for(int i = 0; i < cidArray.length; i ++) {
                mDeactivateCidArray.add(cidArray[i]);
            }
        }   
        log("setDeactivateCidArray, size: " + mDeactivateCidArray.size() + ", cid(s): " +
            mDeactivateCidArray);
    }

    // VOLTE
   /**
     * .This function will check input cid number is dedicate bearer or not.
     * <p>
     * @param cid indicate which cid to check
     * <p>
     * @return true or false for indicating is dedicate bearer or not
     *
     */
    public boolean isDedicateBearer(int cid) {
        boolean bRet = false;
        try {
            bRet = getITelephonyEx().isDedicateBearer(cid);
        } catch (RemoteException ex) {
            ex.printStackTrace();
        } catch (NullPointerException ex) {
            ex.printStackTrace();
        }
        return bRet;
    }

    /**
    * This function will disable Dedicate bearer.
    * @param reason for indicating what reason for disabling dedicate bearer
    * @param ddcid for indicating which dedicate beare cide need to be disable
    * @return int return ddcid of disable dedicated bearer
    *            -1: some thing wrong
    */
    public int disableDedicateBearer(String reason, int ddcid) {
        int nddcid = -1;
        try {
            nddcid = getITelephonyEx().disableDedicateBearer(reason, ddcid);
        } catch (RemoteException ex) {
            ex.printStackTrace();
        } catch (NullPointerException ex) {
            ex.printStackTrace();
        }
        return nddcid;
    }

    /**
    * This function will enable Dedicate bearer.
    * <p>
    * @param apnType input apnType for enable dedicate bearer
    * @param signalingFlag boolean value for indicating signaling or not
    * @param qosStatus input qosStatus info
    * @param tftStatus input tftStatus info
    * @return int return ddcid of enable dedicated bearer
    *            -1: some thing wrong
    */
    public int enableDedicateBearer(String apnType, boolean signalingFlag,
                            QosStatus qosStatus, TftStatus tftStatus) {
        int ddcid = -1;
        try {
            ddcid = getITelephonyEx().enableDedicateBearer(apnType, signalingFlag,
                                            qosStatus, tftStatus);
        } catch (RemoteException ex) {
            ex.printStackTrace();
        } catch (NullPointerException ex) {
            ex.printStackTrace();
        }
        return ddcid;
    }

    /**
    * This function will abort Dedicate bearer.
    * @param reason for indicating what reason for abort enable dedicate bearer
    * @param ddcid for indicating which dedicate beare cide need to be abort
    * @return int return ddcid of abort dedicated bearer
    *            -1: some thing wrong
    */
    public int abortEnableDedicateBearer(String reason, int ddcid) {
        int nddcid = -1;
        try {
            nddcid = getITelephonyEx().abortEnableDedicateBearer(reason, ddcid);
        } catch (RemoteException ex) {
            ex.printStackTrace();
        } catch (NullPointerException ex) {
            ex.printStackTrace();
        }
        return nddcid;
    }

    /**
     * This function will modify Dedicate bearer.
     *
     * @param cid for indicating which dedicate cid to modify
     * @param qosStatus input qosStatus for modify
     * @param tftStatus input tftStatus for modify
     * @return int: return ddcid of modify dedicated bearer
     *            -1: some thing wrong
     */

    public int modifyDedicateBearer(int cid, QosStatus qosStatus, TftStatus tftStatus) {
        int nddcid = -1;
        try {
            nddcid = getITelephonyEx().modifyDedicateBearer(cid, qosStatus, tftStatus);
        } catch (RemoteException ex) {
            ex.printStackTrace();
        } catch (NullPointerException ex) {
            ex.printStackTrace();
        }
        return nddcid;
    }

    /**
     * This function will set Default Bearer Config for apnContext.
     *
     * @param apnType for indicating which apnType to set default bearer config
     * @param defaultBearerConfig config of default bearer config to be set
     * @return int: return success or not
     *            0: set default bearer config successfully
     */
    public int setDefaultBearerConfig(String apnType, DefaultBearerConfig defaultBearerConfig) {
        int ret = -1;
        try {
            ret = getITelephonyEx().setDefaultBearerConfig(apnType, defaultBearerConfig);
        } catch (RemoteException ex) {
            log("### remote ex ###");
            ex.printStackTrace();
        } catch (NullPointerException ex) {
            log("### null pointer ex ###");
            ex.printStackTrace();
        }
        log("### ret: " + ret);
        return ret;
    }

    /**
     * This function will get Default Bearer properties for apn type.
     *
     * @param apnType input apn type for get the mapping default bearer properties
     * @return DedicateBearerProperties return the default beare properties for input apn type
     *                             return null if something wrong
     *
     */
    public DedicateBearerProperties getDefaultBearerProperties(String apnType) {
        DedicateBearerProperties defaultBearerProp = null;
        try {
            defaultBearerProp = getITelephonyEx().getDefaultBearerProperties(apnType);
        } catch (RemoteException ex) {
            log("### remote ex ###");
            ex.printStackTrace();
        } catch (NullPointerException ex) {
            log("### null pointer ex ###");
            ex.printStackTrace();
        }
        log("### defaultBearerProp: " + defaultBearerProp);
        return defaultBearerProp;
    }

    /**


    /**
     * This function will get DcFailCause with int format.
     *
     * @param apnType for geting which last error of apnType
     * @return int: return int failCause value
     */
    public int getLastDataConnectionFailCause(String apnType) {
        int nErrCode = FAILCAUSE_NONE;
        try {
            nErrCode = getITelephonyEx().getLastDataConnectionFailCause(apnType);
        } catch (RemoteException ex) {
            ex.printStackTrace();
        } catch (NullPointerException ex) {
            ex.printStackTrace();
        }
        return nErrCode;
    }

    /**
     * This function will get deactivate cids.
     *
     * @param apnType for getting which apnType deactivate cid array
     * @return int []: int array about cids which is(are) deactivated
     */
    public int [] getDeactivateCidArray(String apnType) {
        int [] cidArray = null;
        try {
            cidArray = getITelephonyEx().getDeactivateCidArray(apnType);
        } catch (RemoteException ex) {
            ex.printStackTrace();
        } catch (NullPointerException ex) {
            ex.printStackTrace();
        }
        return cidArray;
    }

    /**
     * This function will get link properties of input apn type.
     *
     * @param apnType input apn type for geting link properties
     * @return LinkProperties: return correspondent link properties with input apn type
     */
    public LinkProperties getLinkProperties(String apnType) {
        LinkProperties lp = null;
        try {
            lp = getITelephonyEx().getLinkProperties(apnType);
        } catch (RemoteException ex) {
            ex.printStackTrace();
        } catch (NullPointerException ex) {
            ex.printStackTrace();
        }
        return lp;
    }

    /**
     * This function will do pcscf Discovery.
     *
     * @param apnType input apn type for geting pcscf
     * @param cid input cid
     * @param onComplete for response event while pcscf discovery done
     * @return int: return 0: OK, -1: failed
     */
    public int pcscfDiscovery(String apnType, int cid, Message onComplete) {
        int result = -1;
        try {
            result = getITelephonyEx().pcscfDiscovery(apnType, cid, onComplete);
        } catch (RemoteException ex) {
            ex.printStackTrace();
        } catch (NullPointerException ex) {
            ex.printStackTrace();
        }
        return result;
    }

    private ITelephonyEx getITelephonyEx() {
        return ITelephonyEx.Stub.asInterface(
                ServiceManager.getService(Context.TELEPHONY_SERVICE_EX));
    }

    private ConnectivityManager getConnectivityManager() {
        return (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    private NetworkInfo getImsOrEmergencyNetworkInfo(String apnType) {
        NetworkInfo networkInfo = null; //return null if not ims/emergency apn
        int networkType = convertImsOrEmergencyNetworkType(apnType);
        if (networkType == ConnectivityManager.TYPE_NONE) {
            loge("not ims or emergency apn, apn is " + apnType);
            return networkInfo;
        }

        try {
            getConnectivityManager().getNetworkInfo(networkType);
        } catch (NullPointerException e) {
            e.printStackTrace();
        }

        return networkInfo;
    }
    private NetworkInfo.State getImsOrEmergencyNetworkInfoState(String apnType) {
        NetworkInfo.State state = NetworkInfo.State.UNKNOWN;
        try {
            state = getImsOrEmergencyNetworkInfo(apnType).getState();
        } catch (NullPointerException e) {
            e.printStackTrace();
        }

        return state;
    }
    private NetworkInfo.DetailedState getImsOrEmergencyNetworkInfoDetailState(String apnType) {
        NetworkInfo.DetailedState state = NetworkInfo.DetailedState.IDLE;
        try {
            state = getImsOrEmergencyNetworkInfo(apnType).getDetailedState();
        } catch (NullPointerException e) {
            e.printStackTrace();
        }

        return state;
    }


    // L ver network request related API
    private void createNetworkRequest(int count) {
        mDataNetworkRequests = new DataDispatcherNetworkRequest[count];

        for (int i = 0; i < count; i++) {
            NetworkCapabilities netCap = new NetworkCapabilities();
            netCap.addCapability(APN_CAP_LIST[i]);
            NetworkRequest netRequest = new NetworkRequest(netCap, TYPE_NONE, i);
            mDataNetworkRequests[i] = new DataDispatcherNetworkRequest();
            getConnectivityManager().registerNetworkCallback(netRequest, mDataNetworkRequests[i].networkCallback);
            mDataNetworkRequests[i].networkCapabilities = netCap;
            mDataNetworkRequests[i].networkRequest = netRequest;
        }
    }
    
    private static class DataDispatcherNetworkRequest {
        Network currentNetwork;
        NetworkRequest networkRequest;
        NetworkCapabilities networkCapabilities;

        NetworkCallback networkCallback = new NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                currentNetwork = network;
                log("onAvailable:" + network);
            }
            @Override
            public void onLost(Network network) {
                if (network.equals(currentNetwork)) {
                    currentNetwork = null;
                }
                log("onLost:" + network);
            }
            @Override
            public void onLosing(Network network, int maxMsToLive) {
                if (network.equals(currentNetwork)) {
                    // TODO:
                }
                log("onLosing:" + network);
            }    
        };
    }

    private int convertImsOrEmergencyNetworkType(String apnType) {
        int networkType = ConnectivityManager.TYPE_NONE;
        if (PhoneConstants.APN_TYPE_IMS.equals(apnType)) {
            networkType = ConnectivityManager.TYPE_MOBILE_IMS;
        } else if (PhoneConstants.APN_TYPE_EMERGENCY.equals(apnType)) {
            networkType = ConnectivityManager.TYPE_MOBILE_EMERGENCY;
        } else {
            log("only convert ims/emergency");
        }
        return networkType;
    }

    private static String networkTypeToApnType(int netType) {
        switch(netType) {
            case ConnectivityManager.TYPE_MOBILE:
                return PhoneConstants.APN_TYPE_DEFAULT;  // TODO - use just one of these
            case ConnectivityManager.TYPE_MOBILE_MMS:
                return PhoneConstants.APN_TYPE_MMS;
            case ConnectivityManager.TYPE_MOBILE_SUPL:
                return PhoneConstants.APN_TYPE_SUPL;
            case ConnectivityManager.TYPE_MOBILE_DUN:
                return PhoneConstants.APN_TYPE_DUN;
            case ConnectivityManager.TYPE_MOBILE_HIPRI:
                return PhoneConstants.APN_TYPE_HIPRI;
            case ConnectivityManager.TYPE_MOBILE_FOTA:
                return PhoneConstants.APN_TYPE_FOTA;
            case ConnectivityManager.TYPE_MOBILE_IMS:
                return PhoneConstants.APN_TYPE_IMS;
            case ConnectivityManager.TYPE_MOBILE_CBS:
                return PhoneConstants.APN_TYPE_CBS;
            case ConnectivityManager.TYPE_MOBILE_IA:
                return PhoneConstants.APN_TYPE_IA;
            case ConnectivityManager.TYPE_MOBILE_EMERGENCY:
                return PhoneConstants.APN_TYPE_EMERGENCY;
            default:
                loge("Error mapping networkType " + netType + " to apnType.");
                return null;
        }
    }

    // TODO: VoLTE get global ip address and DHCP
    // Query DHCP
    private void getIMSGlobalIpAddr(String apnType, LinkProperties lp) {
        // TODO: [Notice] maximum 2 address ?? 1 ipv4 and 1 ipv6??
        int cnt = 0;
        for (InetAddress inetAddress : lp.getAddresses()) {
            if (inetAddress instanceof Inet6Address) {
                log("getIMSGlobalIpAddr, ip is IpV6");
                getGlobalIpV6Addr(apnType, lp);
            } else if (inetAddress instanceof Inet4Address) {
                log("getIMSGlobalIpAddr, ip is IpV4");
                if (inetAddress.isAnyLocalAddress() == true) {
                    log("getIMSGlobalIpAddr, Using dhcp");
                    DhcpThread dhcpThread = new DhcpThread(apnType, lp, IP_DHCP_V4);
                    dhcpThread.start();
                } else {
                    log("getIMSGlobalIpAddr, send to Handler");
                    sendGlobalIPAddrToVa(inetAddress, apnType, lp);
                }
            } else {
                loge("getIMSGlobalIpAddr, ip is unknown type, use IpV4 temporary");
                sendGlobalIPAddrToVa(inetAddress, apnType, lp);
            }

            cnt++;
        }
        log("getIMSGlobalIpAddr, ip cnt: " + cnt);
    }

    private void sendGlobalIPAddrToVa(InetAddress inetAddress, String apnType, LinkProperties lp) {
        Intent intent = new Intent(TelephonyIntents.ACTION_NOTIFY_GLOBAL_IP_ADDR);
        intent.putExtra(TelephonyIntents.EXTRA_GLOBAL_IP_ADDR_KEY, inetAddress);
        intent.putExtra(PhoneConstants.DATA_APN_TYPE_KEY, apnType);
        intent.putExtra(PhoneConstants.DATA_IFACE_NAME_KEY, lp.getInterfaceName());

        mContext.sendBroadcast(intent);
    }


    private static final int[] RA_POLLING_TIMER = {1, 1, 1, 2, 3, 4, 5, 6, 7}; //30 seconds
    private static final String RESULT_RA_FAIL = "RaFail";

    private String getRaResultAddress(String prefix, LinkProperties lp) {
        String address = null;
        for (InetAddress inetAddress : lp.getAddresses()) {
            if (inetAddress instanceof Inet6Address) {
                //only IPv6 need to get RA prefix
                try {
                    byte[] ipBytes = inetAddress.getAddress();
                    byte[] prefixBytes = InetAddress.getByName(prefix).getAddress();
                    for (int j=0; j<8; j++) {
                        //replace first 64 bits if IP address with the prefix
                        ipBytes[j] = prefixBytes[j];
                    }

                    address = InetAddress.getByAddress(ipBytes).getHostAddress();
                    log("getRaResultAddress get address [" + address + "]");
                    break;
                } catch (UnknownHostException e) {
                    e.printStackTrace();
                }
            }
        }
        return address;
    }

    private String getRaGlobalIpAddress(NetworkInfo networkInfo, LinkProperties lp) {
        String address = RESULT_RA_FAIL;
        if (lp == null) {
            loge("getRaGlobalIpAddress but no LinkProperties");
            return address;
        }

        String interfaceName = lp.getInterfaceName();
        if (interfaceName == null) {
            loge("getRaGlobalIpAddress but interface name");
            return address;
        }

        for (int i=0, length=RA_POLLING_TIMER.length; i<length; i++) {
            NetworkInfo.DetailedState state = networkInfo.getDetailedState();
            if (state != NetworkInfo.DetailedState.CONNECTED) {
                loge("getRaGlobalIpAddress but data state is not connected [" + state + "]");
                break;
            }

            // TODO: need to add code for L about using NetLinkTracker [start]
            String prefix = SystemProperties.get("net.ipv6." + interfaceName + ".prefix", "");
            if (prefix != null && prefix.length() > 0) {
                //some network did not set o-bit but have prefix information in RA,
                //so check prefix first
                log("getRaGlobalIpAddress get prefix [" + prefix + "]");
                return getRaResultAddress(prefix, lp);
            } else {
                int raResult = NetworkUtils.getRaFlags(interfaceName);
                // //0: No RA result
                //1: check system properties "net.ipv6.ccemniX.prefix" for IP prefix
                //2: need to do DHCPv6 for IP address
                //4: receive RA but no M or O flag, handle as case "1"
                //negative: error
                log("getRaGlobalIpAddress get raResult [" + raResult + "]");

                if (raResult == 1 || raResult == 4) {
                    prefix = SystemProperties.get("net.ipv6." + interfaceName + ".prefix", "");
                    log("getRaGlobalIpAddress get prefix after RA result [" + prefix + "]");
                    return getRaResultAddress(prefix, lp);
                } else if (raResult == 2) {
                    //return null to trigger DHCP
                    log("getRaGlobalIpAddress need to do DHCP, return null");
                    return null;
                } else {
                    //not 1 or 2, keep polling
                    log("getRaGlobalIpAddress keep polling [" + raResult + "]");
                }
            }

            synchronized (this) {
                try {
                    log("getRaGlobalIpAddress no RA result fould, wait for " + i + " seconds");
                    wait(RA_POLLING_TIMER[i] * 1000);
                } catch (InterruptedException e){
                    e.printStackTrace();
                }
            }
        }
        return address;
    }

    private void getGlobalIpV6Addr(String apnType, LinkProperties lp) {
        GlobalIpV6AddrQueryThread queryThread = new GlobalIpV6AddrQueryThread(apnType, lp);
        queryThread.start();
    }

    private class GlobalIpV6AddrQueryThread extends Thread {
        String mApnType;
        LinkProperties mLp;
        NetworkInfo mNetworkInfo = null;

        public GlobalIpV6AddrQueryThread(String apnType, LinkProperties lp) {
            mApnType = apnType;
            mLp = lp;
            mNetworkInfo = getImsOrEmergencyNetworkInfo(apnType);
        }

        @Override
        public void run() {
             synchronized (GlobalIpV6AddrQueryThread.class) {
                // only in IMS
                NetworkInfo.DetailedState state = NetworkInfo.DetailedState.IDLE ;
                if (null != mNetworkInfo && NetworkInfo.DetailedState.CONNECTED ==
                        (state = mNetworkInfo.getDetailedState())) {
                    String address = getRaGlobalIpAddress(mNetworkInfo, mLp);
                    //Checking the string address.. then broadcast to self
                    if (address == null) {
                        if (NetworkInfo.DetailedState.CONNECTED ==
                            mNetworkInfo.getDetailedState()) {
                            DhcpThread dhcpThread = new DhcpThread(mApnType, mLp, IP_DHCP_V6);
                            dhcpThread.start();
                        }
                    } else if (RESULT_RA_FAIL.equals(address)){ // Get RA address failed
                        loge("get ra address failed, no broadcast the address back!!");
                    } else { // broadcast InetAddress back, get RA address ok
                        try {
                            InetAddress inetAddr = Inet6Address.getByName(address);
                            sendGlobalIPAddrToVa(inetAddr, mApnType, mLp);
                        } catch (UnknownHostException ex) {
                            loge("Inet6Address getByName error");
                            ex.printStackTrace();
                        }
                    }
                } else {
                    loge("apn type(" + mApnType + ") is not IMS, state(" + state
                         + "), leave GlobalIpV6AddrQueryThread!!");
                }
             }
        }
    }

    private class DhcpThread extends Thread {
        String mApnType;
        LinkProperties mLp;
        int mIpType;
        String mIntfName;
        DhcpResults mDhcpResult;

        public DhcpThread(String apnType, LinkProperties lp, int ipType) {
            mApnType = apnType;
            mLp = lp;
            mIpType = ipType;
            mIntfName = lp.getInterfaceName();
        }

        private boolean stopDhcp() {
            boolean bRet = false;
            log("[DhcpThread] stopDhcp");

            switch (mIpType) {
            case IP_DHCP_V4:
                bRet = NetworkUtils.stopDhcp(mIntfName);
                break;

            case IP_DHCP_V6:
                bRet = NetworkUtils.stopDhcpv6(mIntfName);
                break;

            default:
                loge("[DhcpThread] unknown ip type: " + mIpType + " for stopDhcp!!");
                break;
            };

            return bRet;
        }

        private DhcpResults startDhcp() {
            boolean bRet = false;
            DhcpResults dhcpResult = new DhcpResults();

            log("[DhcpThread] startDhcp, ipType: " + mIpType);
            switch (mIpType) {
            case IP_DHCP_V4:
                bRet = NetworkUtils.runDhcp(mIntfName, dhcpResult);
                break;

            case IP_DHCP_V6:
                bRet = NetworkUtils.runDhcpv6(mIntfName, dhcpResult);
                break;

            default:
                loge("[DhcpThread] unknown ip type: " + mIpType + " for startDhcp!!");
                break;
            };

            if(false == bRet) {
                loge("[DhcpThread] startDhcp failed!!");
                dhcpResult = null;
            }

            return dhcpResult;
        }

        @Override
        public void run() {
            log ("[DhcpThread] start, apnType: " + mApnType);
            if(isApnIMSorEmergency(mApnType) && NetworkInfo.DetailedState.CONNECTED !=
                                                getImsOrEmergencyNetworkInfoDetailState(mApnType)) {
                // Stop DHCP first
                if(false == stopDhcp()) {
                    log("[DhcpThread] stopDhcp failed!!");
                }

                // TODO:  if interrupted thread here???? need to handle ???
                // Start DHCP
                mDhcpResult = startDhcp();
                LinkProperties dhcpLp = mDhcpResult.toLinkProperties(mIntfName);
                if (mDhcpResult != null && dhcpLp != null) {
                    Collection<LinkAddress> addresses = dhcpLp.getLinkAddresses();
                    if (addresses != null && addresses.size() > 0) {
                        Object[] lp = addresses.toArray();
                        InetAddress inetAddr = ((LinkAddress)lp[0]).getAddress();
                        sendGlobalIPAddrToVa(inetAddr, mApnType, dhcpLp);
                    }
                }
            } else {
                loge("[DhcpThread] apn type is not IMS/Emergency, leave DhcpThread!!");
            }
        }
    }
}
