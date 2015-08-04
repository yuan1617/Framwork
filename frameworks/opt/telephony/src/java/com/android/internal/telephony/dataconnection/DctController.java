/*
 * Copyright (C) 2014 MediaTek Inc.
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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Registrant;
import android.os.RegistrantList;
import android.os.SystemProperties;
import android.provider.Settings;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkFactory;
import android.net.NetworkRequest;
import android.util.SparseArray;
import android.telephony.Rlog;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.PhoneProxy;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.util.AsyncChannel;
import com.android.internal.telephony.SubscriptionController;

import java.util.HashMap;
import java.util.Iterator;

/** M: start */
import com.mediatek.internal.telephony.dataconnection.DataSubSelector;
/** M: end */

public class DctController extends Handler {
    private static final String LOG_TAG = "DctController";
    private static final boolean DBG = true;

    private static final int EVENT_PROCESS_REQUESTS = 100;
    private static final int EVENT_EXECUTE_REQUEST = 101;
    private static final int EVENT_EXECUTE_ALL_REQUESTS = 102;
    private static final int EVENT_RELEASE_REQUEST = 103;
    private static final int EVENT_RELEASE_ALL_REQUESTS = 104;

    //MTK START
    private static final int EVENT_TRANSIT_TO_ATTACHING = 200;

    private static final int EVENT_DATA_ATTACHED = 500;
    private static final int EVENT_DATA_DETACHED = 600;
    //M
    private static final int EVENT_SET_DATA_ALLOWED = 700;
    private static final int EVENT_RESTORE_PENDING = 800;

    /** M: start */
    static final String PROPERTY_RIL_DATA_ICCID = "persist.radio.data.iccid";
    private String[] PROPERTY_ICCID_SIM = {
        "ril.iccid.sim1",
        "ril.iccid.sim2",
        "ril.iccid.sim3",
        "ril.iccid.sim4",
    };
    static final String PROPERTY_DATA_ALLOW_SIM = "ril.data.allow";
    static final String ACTION_SHUTDOWN_IPO = "android.intent.action.ACTION_SHUTDOWN_IPO";
    /** M: end */

    protected static DctController sDctController;

    protected int mPhoneNum;
    protected PhoneProxy[] mPhones;
    protected DcSwitchState[] mDcSwitchState;
    protected DcSwitchAsyncChannel[] mDcSwitchAsyncChannel;
    protected Handler[] mDcSwitchStateHandler;
    protected HashMap<Integer, RequestInfo> mRequestInfos = new HashMap<Integer, RequestInfo>();
    protected Context mContext;

    /** Used to send us NetworkRequests from ConnectivityService.  Remeber it so we can
     * unregister on dispose. */
    protected Messenger[] mNetworkFactoryMessenger;
    protected NetworkFactory[] mNetworkFactory;
    protected NetworkCapabilities[] mNetworkFilter;

    protected RegistrantList mNotifyDataSwitchInfo = new RegistrantList();
    protected SubscriptionController mSubController = SubscriptionController.getInstance();

    /** M: setup default data sub */
    protected DataSubSelector mDataSubSelector;

    /** M: allow data service or not, check setDataAllowed */
    private static boolean mDataAllowed = true;
    protected RegistrantList mDcSwitchStateChange = new RegistrantList();
    private Runnable mDataNotAllowedTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            logd("disable data service timeout and enable data service again");
            setDataAllowed(SubscriptionManager.DEFAULT_SUB_ID, true, 0);
        }
    };

    private ContentObserver mObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            logd("Settings change, selfChange=" + selfChange);
            onSettingsChange();
        }
    };

    private Handler mRspHandler = new Handler() {
        public void handleMessage(Message msg) {
            AsyncResult ar;
            if (msg.what >= EVENT_RESTORE_PENDING) {
                logd("EVENT_SIM" + (msg.what - EVENT_RESTORE_PENDING + 1) + "_RESTORE.");
                restorePendingRequest(msg.what - EVENT_RESTORE_PENDING);

            } else if (msg.what >= EVENT_SET_DATA_ALLOWED) {
                logd("EVENT_PHONE" + (msg.what - EVENT_SET_DATA_ALLOWED + 1) + "_SET_DATA_ALLOWED");
                transitToAttachingState(msg.what - EVENT_SET_DATA_ALLOWED);

            } else if (msg.what >= EVENT_DATA_DETACHED) {
                logd("EVENT_PHONE" + (msg.what - EVENT_DATA_DETACHED + 1) + "_DATA_DETACH.");
                mDcSwitchAsyncChannel[msg.what - EVENT_DATA_DETACHED].notifyDataDetached();

            } else if (msg.what >= EVENT_DATA_ATTACHED) {
                logd("EVENT_PHONE" + (msg.what - EVENT_DATA_ATTACHED + 1) + "_DATA_ATTACH.");
                mDcSwitchAsyncChannel[msg.what - EVENT_DATA_ATTACHED].notifyDataAttached();
            }
        }
    };

    public static DctController getInstance() {
       if (sDctController == null) {
        throw new RuntimeException(
            "DctController.getInstance can't be called before makeDCTController()");
        }
       return sDctController;
    }

    public static DctController makeDctController(PhoneProxy[] phones) {
        if (sDctController == null) {
            sDctController = new DctController(phones);
        }
        return sDctController;
    }

    protected DctController(PhoneProxy[] phones) {
        if (phones == null || phones.length == 0) {
            if (phones == null) {
                loge("DctController(phones): UNEXPECTED phones=null, ignore");
            } else {
                loge("DctController(phones): UNEXPECTED phones.length=0, ignore");
            }
            return;
        }
        mPhoneNum = phones.length;
        mPhones = phones;

        mDcSwitchState = new DcSwitchState[mPhoneNum];
        mDcSwitchAsyncChannel = new DcSwitchAsyncChannel[mPhoneNum];
        mDcSwitchStateHandler = new Handler[mPhoneNum];
        mNetworkFactoryMessenger = new Messenger[mPhoneNum];
        mNetworkFactory = new NetworkFactory[mPhoneNum];
        mNetworkFilter = new NetworkCapabilities[mPhoneNum];

        for (int i = 0; i < mPhoneNum; ++i) {
            int phoneId = i;
            mDcSwitchState[i] = new DcSwitchState(mPhones[i], "DcSwitchState-" + phoneId, phoneId);
            mDcSwitchState[i].start();
            mDcSwitchAsyncChannel[i] = new DcSwitchAsyncChannel(mDcSwitchState[i], phoneId);
            mDcSwitchStateHandler[i] = new Handler();

            int status = mDcSwitchAsyncChannel[i].fullyConnectSync(mPhones[i].getContext(),
                mDcSwitchStateHandler[i], mDcSwitchState[i].getHandler());

            if (status == AsyncChannel.STATUS_SUCCESSFUL) {
                logd("DctController(phones): Connect success: " + i);
            } else {
                loge("DctController(phones): Could not connect to " + i);
            }

            // Register for radio state change
            PhoneBase phoneBase = (PhoneBase)((PhoneProxy)mPhones[i]).getActivePhone();

            phoneBase.getServiceStateTracker().registerForDataConnectionAttached(mRspHandler,
                   EVENT_DATA_ATTACHED + i, null);
            phoneBase.getServiceStateTracker().registerForDataConnectionDetached(mRspHandler,
                   EVENT_DATA_DETACHED + i, null);

            ConnectivityManager cm = (ConnectivityManager) mPhones[i].getContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);

            mNetworkFilter[i] = new NetworkCapabilities();
            mNetworkFilter[i].addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR);
            mNetworkFilter[i].addCapability(NetworkCapabilities.NET_CAPABILITY_MMS);
            mNetworkFilter[i].addCapability(NetworkCapabilities.NET_CAPABILITY_SUPL);
            mNetworkFilter[i].addCapability(NetworkCapabilities.NET_CAPABILITY_DUN);
            mNetworkFilter[i].addCapability(NetworkCapabilities.NET_CAPABILITY_FOTA);
            mNetworkFilter[i].addCapability(NetworkCapabilities.NET_CAPABILITY_IMS);
            mNetworkFilter[i].addCapability(NetworkCapabilities.NET_CAPABILITY_CBS);
            mNetworkFilter[i].addCapability(NetworkCapabilities.NET_CAPABILITY_IA);
            mNetworkFilter[i].addCapability(NetworkCapabilities.NET_CAPABILITY_RCS);
            mNetworkFilter[i].addCapability(NetworkCapabilities.NET_CAPABILITY_XCAP);
            mNetworkFilter[i].addCapability(NetworkCapabilities.NET_CAPABILITY_EIMS);
            mNetworkFilter[i].addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED);
            mNetworkFilter[i].addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);

            /** M: start */
            mNetworkFilter[i].addCapability(NetworkCapabilities.NET_CAPABILITY_DM);
            mNetworkFilter[i].addCapability(NetworkCapabilities.NET_CAPABILITY_WAP);
            mNetworkFilter[i].addCapability(NetworkCapabilities.NET_CAPABILITY_NET);
            mNetworkFilter[i].addCapability(NetworkCapabilities.NET_CAPABILITY_CMMAIL);
            mNetworkFilter[i].addCapability(NetworkCapabilities.NET_CAPABILITY_TETHERING);
            mNetworkFilter[i].addCapability(NetworkCapabilities.NET_CAPABILITY_RCSE);
            /** M: end */

            mNetworkFactory[i] = new TelephonyNetworkFactory(this.getLooper(),
                    mPhones[i].getContext(), "TelephonyNetworkFactory", phoneBase,
                    mNetworkFilter[i]);
            mNetworkFactory[i].setScoreFilter(50);
            mNetworkFactoryMessenger[i] = new Messenger(mNetworkFactory[i]);
            cm.registerNetworkFactory(mNetworkFactoryMessenger[i], "Telephony");

            //M: Register for Combine attach
            phoneBase.mCi.registerSetDataAllowed(mRspHandler, EVENT_SET_DATA_ALLOWED + i, null);
            phoneBase.mCi.registerForSimPlugOut(mRspHandler, EVENT_RESTORE_PENDING + i, null);
            phoneBase.mCi.registerForNotAvailable(mRspHandler, EVENT_RESTORE_PENDING + i, null);
        }

        mContext = mPhones[0].getContext();

        IntentFilter filter = new IntentFilter();
        filter.addAction(TelephonyIntents.ACTION_SUBINFO_RECORD_UPDATED);
        filter.addAction(ACTION_SHUTDOWN_IPO);
        mContext.registerReceiver(mIntentReceiver, filter);

        //Register for settings change.
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(
                Settings.Global.MULTI_SIM_DATA_CALL_SUBSCRIPTION),
                false, mObserver);

        /** M: start */
        mDataSubSelector = new DataSubSelector(mContext, mPhoneNum);

        //Since the enter of attaching state may get instance of DctController,
        //we need make sure dctController has already been created.
        String attachPhone = "";
        attachPhone = SystemProperties.get(PROPERTY_DATA_ALLOW_SIM, "");
        logd(" attachPhone: " + attachPhone);
        if (attachPhone != null && !attachPhone.equals("")) {
            int phoneId = Integer.parseInt(attachPhone);
            if (phoneId >= 0 && phoneId < mPhoneNum) {
                logd("Set phone" + phoneId + " to attaching state");
                sendMessage(obtainMessage(EVENT_TRANSIT_TO_ATTACHING, phoneId, 0));
            }
        }
        /** M: end */
    }

    public void dispose() {
        logd("DctController.dispose");
        for (int i = 0; i < mPhoneNum; ++i) {
            ConnectivityManager cm = (ConnectivityManager) mPhones[i].getContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
            cm.unregisterNetworkFactory(mNetworkFactoryMessenger[i]);
            mNetworkFactoryMessenger[i] = null;

            // M: Register for Combine attach
            PhoneBase phoneBase = (PhoneBase) ((PhoneProxy) mPhones[i]).getActivePhone();
            phoneBase.mCi.unregisterSetDataAllowed(mRspHandler);
            phoneBase.mCi.unregisterForSimPlugOut(mRspHandler);
            phoneBase.mCi.unregisterForNotAvailable(mRspHandler);
        }

        mContext.getContentResolver().unregisterContentObserver(mObserver);
    }


    @Override
    public void handleMessage(Message msg) {
        logd("handleMessage msg=" + msg);
        switch (msg.what) {
            case EVENT_PROCESS_REQUESTS:
                onProcessRequest();
                break;
            case EVENT_EXECUTE_REQUEST:
                onExecuteRequest((RequestInfo) msg.obj);
                break;
            case EVENT_EXECUTE_ALL_REQUESTS:
                onExecuteAllRequests((int) msg.arg1);
                break;
            case EVENT_RELEASE_REQUEST:
                onReleaseRequest((RequestInfo) msg.obj);
                break;
            case EVENT_RELEASE_ALL_REQUESTS:
                onReleaseAllRequests((int) msg.arg1);
                break;
            case EVENT_TRANSIT_TO_ATTACHING:
                int phoneId = (int) msg.arg1;
                logd("EVENT_TRANSIT_TO_ATTACHING: phone" + phoneId);
                transitToAttachingState(phoneId);
                break;
            default:
                loge("Un-handled message [" + msg.what + "]");
        }
    }

    protected int requestNetwork(NetworkRequest request, int priority) {
        logd("requestNetwork request=" + request
                + ", priority=" + priority);

        RequestInfo requestInfo = new RequestInfo(request, priority);
        mRequestInfos.put(request.requestId, requestInfo);
        processRequests();

        return PhoneConstants.APN_REQUEST_STARTED;
    }

    protected int releaseNetwork(NetworkRequest request) {
        RequestInfo requestInfo = mRequestInfos.get(request.requestId);
        logd("releaseNetwork request=" + request + ", requestInfo=" + requestInfo);

        mRequestInfos.remove(request.requestId);
        releaseRequest(requestInfo);
        processRequests();
        return PhoneConstants.APN_REQUEST_STARTED;
    }

    void processRequests() {
        logd("processRequests");
        sendMessage(obtainMessage(EVENT_PROCESS_REQUESTS));
    }

    void executeRequest(RequestInfo request) {
        logd("executeRequest, request= " + request);
        sendMessage(obtainMessage(EVENT_EXECUTE_REQUEST, request));
    }

    void executeAllRequests(int phoneId) {
        logd("executeAllRequests, phone:" + phoneId);
        sendMessage(obtainMessage(EVENT_EXECUTE_ALL_REQUESTS, phoneId, 0));
    }

    void releaseRequest(RequestInfo request) {
        logd("releaseRequest, request= " + request);
        sendMessage(obtainMessage(EVENT_RELEASE_REQUEST, request));
    }

    void releaseAllRequests(int phoneId) {
        logd("releaseAllRequests, phone:" + phoneId);
        sendMessage(obtainMessage(EVENT_RELEASE_ALL_REQUESTS, phoneId, 0));
    }

    /** M: try to do re-attach
     *  Disconnect all data connections and do detach if current state is ATTACHING or ATTACHED
     *  Once detach is done, all requests would be processed again when entering IDLE state
     *  That means re-attach will be triggered
     */
    void disconnectAll() {
        int activePhoneId = -1;
        for (int i = 0; i < mDcSwitchState.length; i++) {
            if (!mDcSwitchAsyncChannel[i].isIdleSync()) {
                activePhoneId = i;
                break;
            }
        }

        if (activePhoneId >= 0) {
            logd("disconnectAll, active phone:" + activePhoneId);
            mDcSwitchAsyncChannel[activePhoneId].disconnectAllSync();
        } else {
            logd("disconnectAll but no active phone, process requests");
        }
    }

    protected void onProcessRequest() {
        //process all requests
        //1. Check all requests and find subscription of the top priority
        //   request
        //2. Is current data allowed on the selected subscription
        //2-1. If yes, execute all the requests of the sub
        //2-2. If no, set data not allow on the current PS subscription
        //2-2-1. Set data allow on the selected subscription

        int phoneId = getTopPriorityRequestPhoneId();
        int activePhoneId = -1;

        for (int i = 0; i < mDcSwitchState.length; i++) {
            if (!mDcSwitchAsyncChannel[i].isIdleSync()) {
                activePhoneId = i;
                break;
            }
        }

        logd("onProcessRequest phoneId=" + phoneId + ", activePhoneId=" + activePhoneId);

        /** M: handle data not allowed that all state should be set to IDLD */
        if (mDataAllowed) {
            if (activePhoneId == -1 || activePhoneId == phoneId) {
                Iterator<Integer> iterator = mRequestInfos.keySet().iterator();

                if (activePhoneId == -1 && !iterator.hasNext()) {
                    logd("No active phone, set phone" + phoneId + " to attaching state");
                    transitToAttachingState(phoneId);
                }

                while (iterator.hasNext()) {
                    RequestInfo requestInfo = mRequestInfos.get(iterator.next());
                    if (getRequestPhoneId(requestInfo.request) == phoneId
                            && !requestInfo.executed) {
                        mDcSwitchAsyncChannel[phoneId].connectSync(requestInfo);
                    }
                }
            } else {
                mDcSwitchAsyncChannel[activePhoneId].disconnectAllSync();
            }
        } else {
            if (activePhoneId != -1) {
                logd("onProcessRequest data is not allowed");
                mDcSwitchAsyncChannel[activePhoneId].disconnectAllSync();
            } else {
                logd("onProcessRequest data is not allowed and already in IDLE state");
            }
        }
    }

    protected void onExecuteRequest(RequestInfo requestInfo) {
        logd("onExecuteRequest request=" + requestInfo);
        if (!requestInfo.executed) {
            requestInfo.executed = true;
            String apn = apnForNetworkRequest(requestInfo.request);
            int phoneId = getRequestPhoneId(requestInfo.request);
            PhoneBase phoneBase = (PhoneBase) ((PhoneProxy) mPhones[phoneId]).getActivePhone();
            DcTrackerBase dcTracker = phoneBase.mDcTracker;
            dcTracker.incApnRefCount(apn);
        }
    }

    protected void onExecuteAllRequests(int phoneId) {
        logd("onExecuteAllRequests phoneId=" + phoneId);
        Iterator<Integer> iterator = mRequestInfos.keySet().iterator();
        while (iterator.hasNext()) {
            RequestInfo requestInfo = mRequestInfos.get(iterator.next());
            if (getRequestPhoneId(requestInfo.request) == phoneId) {
                onExecuteRequest(requestInfo);
            }
        }
    }

    protected void onReleaseRequest(RequestInfo requestInfo) {
        logd("onReleaseRequest request=" + requestInfo);
        if (requestInfo.executed) {
            String apn = apnForNetworkRequest(requestInfo.request);
            int phoneId = getRequestPhoneId(requestInfo.request);
            PhoneBase phoneBase = (PhoneBase) ((PhoneProxy) mPhones[phoneId]).getActivePhone();
            DcTrackerBase dcTracker = phoneBase.mDcTracker;
            dcTracker.decApnRefCount(apn);
            requestInfo.executed = false;
        }
    }

    protected void onReleaseAllRequests(int phoneId) {
        RequestInfo currRequest = null;
        logd("onReleaseAllRequests phoneId=" + phoneId);
        Iterator<Integer> iterator = mRequestInfos.keySet().iterator();
        while (iterator.hasNext()) {
            RequestInfo requestInfo = mRequestInfos.get(iterator.next());
            if (getRequestPhoneId(requestInfo.request) == phoneId) {
                onReleaseRequest(requestInfo);
            }
        }
    }

    protected void onSettingsChange() {
        long dataSubId = SubscriptionManager.INVALID_SUB_ID;
        //Sub Selection
        dataSubId = mSubController.getDefaultDataSubId();

        /** M: Set data ICCID for combination attach */
        int dataPhoneId = SubscriptionManager.getPhoneId(dataSubId);
        String defaultIccid = "";
        if (dataPhoneId >= 0) {
            if (dataPhoneId >= PROPERTY_ICCID_SIM.length) {
                loge("onSettingsChange, phoneId out of boundary:" + dataPhoneId);
            } else {
                defaultIccid = SystemProperties.get(PROPERTY_ICCID_SIM[dataPhoneId]);
                logd("onSettingsChange, Iccid = " + defaultIccid + ", dataPhoneId:" + dataPhoneId);
            }
        } else {
            logd("onSettingsChange, default data unset");
        }
        SystemProperties.set(PROPERTY_RIL_DATA_ICCID, defaultIccid);

        int activePhoneId = getActiveDataPhoneId();

        logd("onSettingsChange, data sub: " + dataSubId + ", active phone id: " + activePhoneId);
        if (activePhoneId == SubscriptionManager.INVALID_PHONE_ID) {
            logd("onSettingsChange no active phone");
            // Some request maybe pending due to invalid settings
            // Try to handle pending request when settings changed
            for (int i = 0; i < mPhoneNum; ++i) {
                ((DctController.TelephonyNetworkFactory) mNetworkFactory[i]).evalPendingRequest();
            }
            return;
        }

        long[] subIds = SubscriptionManager.getSubId(activePhoneId);
        if (subIds != null && subIds[0] != dataSubId) {
            Iterator<Integer> iterator = mRequestInfos.keySet().iterator();
            while (iterator.hasNext()) {
                RequestInfo requestInfo = mRequestInfos.get(iterator.next());
                String specifier = requestInfo.request.networkCapabilities.getNetworkSpecifier();
                if (specifier == null || specifier.equals("")) {
                    if (requestInfo.executed) {
                        String apn = apnForNetworkRequest(requestInfo.request);
                        logd("[setDataSubId] activePhoneId:" + activePhoneId + ", subId =" +
                                dataSubId);
                        PhoneBase phoneBase =
                                (PhoneBase) ((PhoneProxy) mPhones[activePhoneId]).getActivePhone();
                        DcTrackerBase dcTracker = phoneBase.mDcTracker;
                        dcTracker.decApnRefCount(apn);
                        requestInfo.executed = false;
                    }
                }
            }
        }

        // Some request maybe pending due to invalid settings
        // Try to handle pending request when settings changed
        for (int i = 0; i < mPhoneNum; ++i) {
            ((DctController.TelephonyNetworkFactory) mNetworkFactory[i]).evalPendingRequest();
        }

        processRequests();
    }

    private int getTopPriorityRequestPhoneId() {
        RequestInfo retRequestInfo = null;
        int phoneId = 0;
        int priority = -1;

        //TODO: Handle SIM Switch
        for (int i = 0; i < mPhoneNum; i++) {
            Iterator<Integer> iterator = mRequestInfos.keySet().iterator();
            while (iterator.hasNext()) {
                RequestInfo requestInfo = mRequestInfos.get(iterator.next());
                logd("selectExecPhone requestInfo = " + requestInfo);
                if (getRequestPhoneId(requestInfo.request) == i &&
                        priority < requestInfo.priority) {
                    priority = requestInfo.priority;
                    retRequestInfo = requestInfo;
                }
            }
        }

        if (retRequestInfo != null) {
            phoneId = getRequestPhoneId(retRequestInfo.request);
        } else {
            String curr3GSim = SystemProperties.get("persist.radio.simswitch", "");
            logd("current 3G Sim = " + curr3GSim);
            if (curr3GSim != null && !curr3GSim.equals("")) {
                phoneId = Integer.parseInt(curr3GSim) - 1;
            }
        }

        logd("getTopPriorityRequestPhoneId = " + phoneId
                + ", priority = " + priority);

        return phoneId;
    }

    private boolean isValidPhoneId(int phoneId) {
        return phoneId >= 0 && phoneId <= mPhoneNum;
    }

    private void onSubInfoReady() {
        logd("onSubInfoReady handle pending requset");
        for (int i = 0; i < mPhoneNum; ++i) {
            mNetworkFilter[i].setNetworkSpecifier(String.valueOf(mPhones[i].getSubId()));
            ((DctController.TelephonyNetworkFactory) mNetworkFactory[i]).evalPendingRequest();
        }
    }

    private String apnForNetworkRequest(NetworkRequest nr) {
        NetworkCapabilities nc = nr.networkCapabilities;
        // for now, ignore the bandwidth stuff
        if (nc.getTransportTypes().length > 0 &&
                nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == false) {
            return null;
        }

        // in the near term just do 1-1 matches.
        // TODO - actually try to match the set of capabilities
        int type = -1;
        String name = null;

        boolean error = false;
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_DEFAULT;
            type = ConnectivityManager.TYPE_MOBILE;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_MMS)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_MMS;
            type = ConnectivityManager.TYPE_MOBILE_MMS;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_SUPL)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_SUPL;
            type = ConnectivityManager.TYPE_MOBILE_SUPL;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_DUN)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_DUN;
            type = ConnectivityManager.TYPE_MOBILE_DUN;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_FOTA)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_FOTA;
            type = ConnectivityManager.TYPE_MOBILE_FOTA;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_IMS)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_IMS;
            type = ConnectivityManager.TYPE_MOBILE_IMS;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_CBS)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_CBS;
            type = ConnectivityManager.TYPE_MOBILE_CBS;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_IA)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_IA;
            type = ConnectivityManager.TYPE_MOBILE_IA;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_RCS)) {
            if (name != null) error = true;
            name = null;
            loge("RCS APN type not yet supported");
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_XCAP)) {
            if (name != null) error = true;
            name = null;
            loge("XCAP APN type not yet supported");
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_EIMS)) {
            if (name != null) error = true;
            name = null;
            loge("EIMS APN type not yet supported");
        }

        /** M: start */
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_DM)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_DM;
            type = ConnectivityManager.TYPE_MOBILE_DM;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_WAP)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_WAP;
            type = ConnectivityManager.TYPE_MOBILE_WAP;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NET)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_NET;
            type = ConnectivityManager.TYPE_MOBILE_NET;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_CMMAIL)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_CMMAIL;
            type = ConnectivityManager.TYPE_MOBILE_CMMAIL;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_TETHERING)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_TETHERING;
            type = ConnectivityManager.TYPE_MOBILE_TETHERING;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_RCSE)) {
            if (name != null) error = true;
            name = PhoneConstants.APN_TYPE_RCSE;
            type = ConnectivityManager.TYPE_MOBILE_RCSE;
        }
        /** M: end */

        if (error) {
            loge("Multiple apn types specified in request - result is unspecified!");
        }
        if (type == -1 || name == null) {
            loge("Unsupported NetworkRequest in Telephony: " + nr);
            return null;
        }
        return name;
    }

    protected int getRequestPhoneId(NetworkRequest networkRequest) {
        String specifier = networkRequest.networkCapabilities.getNetworkSpecifier();
        if (specifier == null || specifier.equals("")) {
            // sub selection, data always go with SIM1
            //return PhoneConstants.SIM_ID_1;
            return SubscriptionManager.getPhoneId(SubscriptionManager.getDefaultDataSubId());
        } else {
            int phoneId = SubscriptionManager.getPhoneId(Long.parseLong(specifier))
                    - PhoneConstants.SIM_ID_1;
            return phoneId;
        }
    }

    protected BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            logd("onReceive: action=" + action);
            if (action.equals(TelephonyIntents.ACTION_SUBINFO_RECORD_UPDATED)) {
                onSubInfoReady();
            } else if (action.equals(ACTION_SHUTDOWN_IPO)) {
                logd("IPO Shutdown, clear PROPERTY_DATA_ALLOW_SIM");
                SystemProperties.set(PROPERTY_DATA_ALLOW_SIM, "");
            }
        }
    };

    protected static void logv(String s) {
        if (DBG) Rlog.v(LOG_TAG, "[DctController] " + s);
    }

    protected void logd(String s) {
        if (DBG) Rlog.d(LOG_TAG, "[DctController] " + s);
    }

    protected static void logw(String s) {
        if (DBG) Rlog.w(LOG_TAG, "[DctController] " + s);
    }

    protected static void loge(String s) {
        if (DBG) Rlog.e(LOG_TAG, "[DctController] " + s);
    }

    private class TelephonyNetworkFactory extends NetworkFactory {
        private final SparseArray<NetworkRequest> mPendingReq = new SparseArray<NetworkRequest>();
        private Phone mPhone;

        public TelephonyNetworkFactory(Looper l, Context c, String TAG, Phone phone,
                NetworkCapabilities nc) {
            super(l, c, TAG, nc);
            mPhone = phone;
            log("NetworkCapabilities: " + nc);
        }

        @Override
        protected void needNetworkFor(NetworkRequest networkRequest, int score) {
            // figure out the apn type and enable it
            log("Cellular needs Network for " + networkRequest);

            if (mPhone.getSubId() == -1 || mPhone.getSubId() == -2) {
                log("Sub Info has not been ready, pending request.");
                mPendingReq.put(networkRequest.requestId, networkRequest);
                return;
            }

            if (getRequestPhoneId(networkRequest) == mPhone.getPhoneId()) {
                DcTrackerBase dcTracker = ((PhoneBase) mPhone).mDcTracker;
                String apn = apnForNetworkRequest(networkRequest);
                if (dcTracker.isApnSupported(apn)) {
                    requestNetwork(networkRequest, dcTracker.getApnPriority(apn));
                } else {
                    log("Unsupported APN");
                }
            } else {
                log("Request not send, put to pending");
                mPendingReq.put(networkRequest.requestId, networkRequest);
            }
        }

        @Override
        protected void releaseNetworkFor(NetworkRequest networkRequest) {
            log("Cellular releasing Network for " + networkRequest);

            if (mPendingReq.get(networkRequest.requestId) != null ) {
                log("Sub Info has not been ready, remove request.");
                mPendingReq.remove(networkRequest.requestId);
                return;
            }

            if (getRequestPhoneId(networkRequest) == mPhone.getPhoneId()) {
                DcTrackerBase dcTracker = ((PhoneBase) mPhone).mDcTracker;
                String apn = apnForNetworkRequest(networkRequest);
                if (dcTracker.isApnSupported(apn)) {
                    releaseNetwork(networkRequest);
                } else {
                    log("Unsupported APN");
                }

            } else {
                log("Request not release");
            }
        }

        @Override
        protected void log(String s) {
            if (DBG) Rlog.d(LOG_TAG, "[Sub " + mPhone.getSubId() + "]" + s);
        }

        public void addPendingRequest(NetworkRequest networkRequest) {
            log("addPendingRequest, request:" + networkRequest);
            mPendingReq.put(networkRequest.requestId, networkRequest);
        }

        public void evalPendingRequest() {
            log("evalPendingRequest, pending request size is " + mPendingReq.size());
            int key = 0;
            for (int i = 0; i < mPendingReq.size(); i++) {
                key = mPendingReq.keyAt(i);
                NetworkRequest request = mPendingReq.get(key);
                log("evalPendingRequest: request = " + request);

                mPendingReq.remove(request.requestId);
                needNetworkFor(request, 0);
            }
        }
    }

    /** M: get acive data phone Id */
    public int getActiveDataPhoneId() {
        int activePhoneId = SubscriptionManager.INVALID_PHONE_ID;
        for (int i = 0; i < mDcSwitchState.length; i++) {
            if (!mDcSwitchAsyncChannel[i].isIdleSync()) {
                activePhoneId = i;
                break;
            }
        }
        return activePhoneId;
    }

    /** M: allow data service or not and can set a max timeout for setting data not allowed */
    public void setDataAllowed(long subId, boolean allowed, long timeout) {
        logd("setDataAllowed subId=" + subId + ", allowed=" + allowed + ", timeout=" + timeout);
        mDataAllowed = allowed;
        if (mDataAllowed) {
            mRspHandler.removeCallbacks(mDataNotAllowedTimeoutRunnable);
        }

        processRequests();

        if (!mDataAllowed && timeout > 0) {
            logd("start not allow data timer and timeout=" + timeout);
            mRspHandler.postDelayed(mDataNotAllowedTimeoutRunnable, timeout);
        }
    }

    public void registerForDcSwitchStateChange(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mDcSwitchStateChange.add(r);
    }

    public void unregisterForDcSwitchStateChange(Handler h) {
        mDcSwitchStateChange.remove(h);
    }

    public void notifyDcSwitchStateChange(String state) {
        mDcSwitchStateChange.notifyRegistrants(new AsyncResult(null, state, null));
    }

    /** M: transit to attaching state. */
    private void transitToAttachingState(int targetPhoneId)  {
        int topPriorityPhoneId = getTopPriorityRequestPhoneId();
        int activePhoneId = -1;
        if (topPriorityPhoneId == targetPhoneId) {
            for (int i = 0; i < mDcSwitchState.length; i++) {
                if (!mDcSwitchAsyncChannel[i].isIdleSync()) {
                    activePhoneId = i;
                    break;
                }
            }
            if (activePhoneId != -1 && activePhoneId != targetPhoneId) {
                logd("transitToAttachingState: disconnect other phone");
                mDcSwitchAsyncChannel[activePhoneId].disconnectAllSync();
            } else {
                logd("transitToAttachingState: connect");
                mDcSwitchAsyncChannel[targetPhoneId].connectSync(null);
            }
        } else {
            logd("transitToAttachingState: disconnect target phone");
            mDcSwitchAsyncChannel[targetPhoneId].connectSync(null);
            mDcSwitchAsyncChannel[targetPhoneId].disconnectAllSync();
        }
    }

    private void restorePendingRequest(int phoneId) {
        Iterator<Integer> iterator = mRequestInfos.keySet().iterator();
        while (iterator.hasNext()) {
            RequestInfo requestInfo = mRequestInfos.get(iterator.next());
            logd("restorePendingRequest requestInfo = " + requestInfo);
            if (getRequestPhoneId(requestInfo.request) == phoneId) {
                ((DctController.TelephonyNetworkFactory) mNetworkFactory[phoneId])
                        .addPendingRequest(requestInfo.request);
                onReleaseRequest(requestInfo);
                iterator.remove();
            }
        }
    }

    class RequestInfo {
        boolean executed;
        NetworkRequest request;
        int priority;

        public RequestInfo(NetworkRequest request, int priority) {
            this.request = request;
            this.priority = priority;
        }

        @Override
        public String toString() {
            return "[ request=" + request + ", executed=" + executed +
                ", priority=" + priority + "]";
        }
    }
}
