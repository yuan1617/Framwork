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

package com.android.internal.telephony;

import static com.android.internal.telephony.RILConstants.*;
import static android.telephony.TelephonyManager.NETWORK_TYPE_UNKNOWN;
import static android.telephony.TelephonyManager.NETWORK_TYPE_EDGE;
import static android.telephony.TelephonyManager.NETWORK_TYPE_GPRS;
import static android.telephony.TelephonyManager.NETWORK_TYPE_UMTS;
import static android.telephony.TelephonyManager.NETWORK_TYPE_HSDPA;
import static android.telephony.TelephonyManager.NETWORK_TYPE_HSUPA;
import static android.telephony.TelephonyManager.NETWORK_TYPE_HSPA;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.DisplayManager;
import android.net.ConnectivityManager;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.os.PowerManager.WakeLock;
import android.provider.Settings.SettingNotFoundException;
import android.telephony.CellInfo;
import android.telephony.NeighboringCellInfo;
import android.telephony.PhoneNumberUtils;
import android.telephony.PhoneRatFamily;
import android.telephony.Rlog;
import android.telephony.SignalStrength;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.SparseArray;
import android.view.Display;

import com.android.internal.telephony.gsm.SmsBroadcastConfigInfo;
import com.android.internal.telephony.gsm.SuppServiceNotification;
import com.android.internal.telephony.uicc.IccCardApplicationStatus;
import com.android.internal.telephony.uicc.IccCardStatus;
import com.android.internal.telephony.uicc.IccIoResult;
import com.android.internal.telephony.uicc.IccRefreshResponse;
import com.android.internal.telephony.uicc.IccUtils;
import com.android.internal.telephony.cdma.CdmaCallWaitingNotification;
import com.android.internal.telephony.cdma.CdmaInformationRecords;
import com.android.internal.telephony.cdma.CdmaSmsBroadcastConfigInfo;
import com.android.internal.telephony.dataconnection.DcFailCause;
import com.android.internal.telephony.dataconnection.DataCallResponse;
import com.android.internal.telephony.dataconnection.DataProfile;
import com.android.internal.telephony.TelephonyDevController;
import com.android.internal.telephony.HardwareConfig;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Random;

/* M: call control part start */
import java.util.Vector;

import com.android.internal.telephony.gsm.SuppCrssNotification;

import android.os.IBinder;
import android.os.Binder;
import android.content.pm.PackageManager;
import android.os.ServiceManager;
import com.mediatek.common.mom.IMobileManagerService;
import com.mediatek.common.mom.MobileManagerUtils;
import com.mediatek.common.mom.SubPermissions;
/* M: call control part end */

import com.mediatek.common.telephony.gsm.PBEntry;
import com.mediatek.common.telephony.gsm.PBMemStorage;

// MTK-START, SMS part
import android.telephony.SmsParameters;
import com.mediatek.internal.telephony.IccSmsStorageStatus;
import com.mediatek.internal.telephony.CellBroadcastConfigInfo;
// MTK-END, SMS part

import com.android.internal.telephony.uicc.SpnOverride;
import com.mediatek.internal.telephony.FemtoCellInfo;
import com.mediatek.internal.telephony.NetworkInfoWithAcT;
import java.io.UnsupportedEncodingException;
import com.android.internal.telephony.uicc.UiccController;
import com.android.internal.telephony.uicc.SIMRecords;
import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;

//VoLTE
import com.mediatek.internal.telephony.QosStatus;
import com.mediatek.internal.telephony.TftStatus;
import com.mediatek.internal.telephony.DedicateDataCallState;
import com.mediatek.internal.telephony.PacketFilterInfo;
import com.mediatek.internal.telephony.TftAuthToken;
import com.mediatek.internal.telephony.PcscfInfo;
import com.mediatek.internal.telephony.DefaultBearerConfig;

import com.mediatek.common.MPlugin;
import com.mediatek.common.telephony.IServiceStateExt;

/**
 * {@hide}
 */
class RILRequest {
    static final String LOG_TAG = "RilRequest";

    //***** Class Variables
    static Random sRandom = new Random();
    static AtomicInteger sNextSerial = new AtomicInteger(0);
    private static Object sPoolSync = new Object();
    private static RILRequest sPool = null;
    private static int sPoolSize = 0;
    private static final int MAX_POOL_SIZE = 4;
    private Context mContext;

    //***** Instance Variables
    int mSerial;
    int mRequest;
    Message mResult;
    Parcel mParcel;
    RILRequest mNext;

    /**
     * Retrieves a new RILRequest instance from the pool.
     *
     * @param request RIL_REQUEST_*
     * @param result sent when operation completes
     * @return a RILRequest instance from the pool.
     */
    static RILRequest obtain(int request, Message result) {
        RILRequest rr = null;

        synchronized(sPoolSync) {
            if (sPool != null) {
                rr = sPool;
                sPool = rr.mNext;
                rr.mNext = null;
                sPoolSize--;
            }
        }

        if (rr == null) {
            rr = new RILRequest();
        }

        rr.mSerial = sNextSerial.getAndIncrement();

        rr.mRequest = request;
        rr.mResult = result;
        rr.mParcel = Parcel.obtain();

        if (result != null && result.getTarget() == null) {
            throw new NullPointerException("Message target must not be null");
        }

        // first elements in any RIL Parcel
        rr.mParcel.writeInt(request);
        rr.mParcel.writeInt(rr.mSerial);

        return rr;
    }

    /**
     * Returns a RILRequest instance to the pool.
     *
     * Note: This should only be called once per use.
     */
    void release() {
        synchronized (sPoolSync) {
            if (sPoolSize < MAX_POOL_SIZE) {
                mNext = sPool;
                sPool = this;
                sPoolSize++;
                mResult = null;
            }
        }
    }

    private RILRequest() {
    }

    static void
    resetSerial() {
        // use a random so that on recovery we probably don't mix old requests
        // with new.
        sNextSerial.set(sRandom.nextInt());
    }

    String
    serialString() {
        //Cheesy way to do %04d
        StringBuilder sb = new StringBuilder(8);
        String sn;

        long adjustedSerial = (((long)mSerial) - Integer.MIN_VALUE)%10000;

        sn = Long.toString(adjustedSerial);

        //sb.append("J[");
        sb.append('[');
        for (int i = 0, s = sn.length() ; i < 4 - s; i++) {
            sb.append('0');
        }

        sb.append(sn);
        sb.append(']');
        return sb.toString();
    }

    void
    onError(int error, Object ret) {
        CommandException ex;

        ex = CommandException.fromRilErrno(error);

        if (RIL.RILJ_LOGD) Rlog.d(LOG_TAG, serialString() + "< "
            + RIL.requestToString(mRequest)
            + " error: " + ex + " ret=" + RIL.retToString(mRequest, ret));

        if (mResult != null) {
            AsyncResult.forMessage(mResult, ret, ex);
            mResult.sendToTarget();
        }

        if (mParcel != null) {
            mParcel.recycle();
            mParcel = null;
        }
    }
}


/**
 * RIL implementation of the CommandsInterface.
 *
 * {@hide}
 */
public final class RIL extends BaseCommands implements CommandsInterface {
    static final String RILJ_LOG_TAG = "RILJ";
    static final boolean RILJ_LOGD = true;
    static final boolean RILJ_LOGV = false; // STOPSHIP if true

    /**
     * Wake lock timeout should be longer than the longest timeout in
     * the vendor ril.
     */
    private static final int DEFAULT_WAKE_LOCK_TIMEOUT = 60000;

    //***** Instance Variables

    LocalSocket mSocket;
    HandlerThread mSenderThread;
    RILSender mSender;
    Thread mReceiverThread;
    RILReceiver mReceiver;
    Display mDefaultDisplay;
    int mDefaultDisplayState = Display.STATE_UNKNOWN;
    WakeLock mWakeLock;
    final int mWakeLockTimeout;
    // The number of wakelock requests currently active.  Don't release the lock
    // until dec'd to 0
    int mWakeLockCount;

    SparseArray<RILRequest> mRequestList = new SparseArray<RILRequest>();

    Object     mLastNITZTimeInfo;

    // When we are testing emergency calls
    AtomicBoolean mTestingEmergencyCall = new AtomicBoolean(false);

    private Integer mInstanceId;

    //***** Events

    static final int EVENT_SEND                 = 1;
    static final int EVENT_WAKE_LOCK_TIMEOUT    = 2;

    //***** Constants

    // match with constant in ril.cpp
    static final int RIL_MAX_COMMAND_BYTES = (20 * 1024);
    static final int RESPONSE_SOLICITED = 0;
    static final int RESPONSE_UNSOLICITED = 1;

    static final String[] SOCKET_NAME_RIL = {"rild", "rild2", "rild3"};

    static final int SOCKET_OPEN_RETRY_MILLIS = 4 * 1000;

    // The number of the required config values for broadcast SMS stored in the C struct
    // RIL_CDMA_BroadcastServiceInfo
    private static final int CDMA_BSI_NO_OF_INTS_STRUCT = 3;

    private static final int CDMA_BROADCAST_SMS_NO_OF_SERVICE_CATEGORIES = 31;

    private final DisplayManager.DisplayListener mDisplayListener =
            new DisplayManager.DisplayListener() {
                  @Override
                  public void onDisplayAdded(int displayId) { }

                  @Override
                  public void onDisplayRemoved(int displayId) { }

                  @Override
                  public void onDisplayChanged(int displayId) {
                      if (displayId == Display.DEFAULT_DISPLAY) {
                          updateScreenState();
                      }
                  }
    };

    /* ALPS00799783: for restore previous preferred network type when set type fail */
    private int mPreviousPreferredType = -1;

    private IServiceStateExt mServiceStateExt;

    //MTK-START [mtk04070][111121][ALPS00093395]MTK added
    //save the status of screen
    private boolean isScreenOn = true;

    /// M: C2K RILD socket name definition
    static final String C2K_SOCKET_NAME_RIL = "rild-via";

    /* M: call control part start */
    /* DTMF request will be ignored when duplicated sending */
    private class dtmfQueueHandler {

        public dtmfQueueHandler() {
            mDtmfStatus = DTMF_STATUS_STOP;
        }

        public void start() {
            mDtmfStatus = DTMF_STATUS_START;
        }

        public void stop() {
            mDtmfStatus = DTMF_STATUS_STOP;
        }

        public boolean isStart() {
            return (mDtmfStatus == DTMF_STATUS_START);
        }

        public void add(RILRequest o) {
            mDtmfQueue.addElement(o);
        }

        public void remove(RILRequest o) {
            mDtmfQueue.remove(o);
        }

        public void remove(int idx) {
            mDtmfQueue.removeElementAt(idx);
        }

        public RILRequest get() {
            return (RILRequest) mDtmfQueue.get(0);
        }

        public int size() {
            return mDtmfQueue.size();
        }

        public void setPendingRequest(RILRequest r) {
            mPendingCHLDRequest = r;
        }

        public RILRequest getPendingRequest() {
            return mPendingCHLDRequest;
        }

        public void setSendChldRequest() {
            mIsSendChldRequest = true;
        }

        public void resetSendChldRequest() {
            mIsSendChldRequest = false;
        }

        public boolean hasSendChldRequest() {
            riljLog("mIsSendChldRequest = " + mIsSendChldRequest);
            return mIsSendChldRequest;
        }

        public final int MAXIMUM_DTMF_REQUEST = 32;
        private final boolean DTMF_STATUS_START = true;
        private final boolean DTMF_STATUS_STOP = false;

        private boolean mDtmfStatus = DTMF_STATUS_STOP;
        private Vector mDtmfQueue = new Vector(MAXIMUM_DTMF_REQUEST);

        private RILRequest mPendingCHLDRequest = null;
        private boolean mIsSendChldRequest = false;
    }

    private dtmfQueueHandler mDtmfReqQueue = new dtmfQueueHandler();
    /* M: call control part end */

    BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
                //[ALPS01664312]-Srart
                if (!isScreenOn) {
                    sendScreenState(true);
                }
                //[ALPS01664312]-End
            } else if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                sendScreenState(false);
            } else if (intent.getAction().equals("com.mtk.TEST_TRM")) {
                int mode = intent.getIntExtra("mode", 2);
                Rlog.d(RILJ_LOG_TAG, "RIL received com.mtk.TEST_TRM, mode = " + mode);
                if (mode == 2 && mInstanceId == 0) {
                    setTrm(mode, null);
                }
            } else {
                Rlog.w(RILJ_LOG_TAG, "RIL received unexpected Intent: " + intent.getAction());
            }
        }/* end of onReceive */
    };

    class RILSender extends Handler implements Runnable {
        public RILSender(Looper looper) {
            super(looper);
        }

        // Only allocated once
        byte[] dataLength = new byte[4];

        //***** Runnable implementation
        @Override
        public void
        run() {
            //setup if needed
        }


        //***** Handler implementation
        @Override public void
        handleMessage(Message msg) {
            RILRequest rr = (RILRequest)(msg.obj);
            RILRequest req = null;

            switch (msg.what) {
                case EVENT_SEND:
                    try {
                        LocalSocket s;

                        s = mSocket;

                        if (s == null) {
                            rr.onError(RADIO_NOT_AVAILABLE, null);
                            rr.release();
                            decrementWakeLock();
                            return;
                        }

                        synchronized (mRequestList) {
                            mRequestList.append(rr.mSerial, rr);
                        }

                        byte[] data;

                        data = rr.mParcel.marshall();
                        rr.mParcel.recycle();
                        rr.mParcel = null;

                        if (data.length > RIL_MAX_COMMAND_BYTES) {
                            throw new RuntimeException(
                                    "Parcel larger than max bytes allowed! "
                                                          + data.length);
                        }

                        // parcel length in big endian
                        dataLength[0] = dataLength[1] = 0;
                        dataLength[2] = (byte)((data.length >> 8) & 0xff);
                        dataLength[3] = (byte)((data.length) & 0xff);

                        //Rlog.v(RILJ_LOG_TAG, "writing packet: " + data.length + " bytes");

                        s.getOutputStream().write(dataLength);
                        s.getOutputStream().write(data);
                    } catch (IOException ex) {
                        Rlog.e(RILJ_LOG_TAG, "IOException", ex);
                        req = findAndRemoveRequestFromList(rr.mSerial);
                        // make sure this request has not already been handled,
                        // eg, if RILReceiver cleared the list.
                        if (req != null) {
                            rr.onError(RADIO_NOT_AVAILABLE, null);
                            rr.release();
                            decrementWakeLock();
                        }
                    } catch (RuntimeException exc) {
                        Rlog.e(RILJ_LOG_TAG, "Uncaught exception ", exc);
                        req = findAndRemoveRequestFromList(rr.mSerial);
                        // make sure this request has not already been handled,
                        // eg, if RILReceiver cleared the list.
                        if (req != null) {
                            rr.onError(GENERIC_FAILURE, null);
                            rr.release();
                            decrementWakeLock();
                        }
                    }

                    break;

                case EVENT_WAKE_LOCK_TIMEOUT:
                    // Haven't heard back from the last request.  Assume we're
                    // not getting a response and  release the wake lock.

                    // The timer of WAKE_LOCK_TIMEOUT is reset with each
                    // new send request. So when WAKE_LOCK_TIMEOUT occurs
                    // all requests in mRequestList already waited at
                    // least DEFAULT_WAKE_LOCK_TIMEOUT but no response.
                    //
                    // Note: Keep mRequestList so that delayed response
                    // can still be handled when response finally comes.

                    synchronized (mRequestList) {
                        if (clearWakeLock()) {
                            if (RILJ_LOGD) {
                                int count = mRequestList.size();
                                Rlog.d(RILJ_LOG_TAG, "WAKE_LOCK_TIMEOUT " +
                                        " mRequestList=" + count);
                                for (int i = 0; i < count; i++) {
                                    rr = mRequestList.valueAt(i);
                                    Rlog.d(RILJ_LOG_TAG, i + ": [" + rr.mSerial + "] "
                                            + requestToString(rr.mRequest));
                                }
                            }
                        }
                    }
                    break;
            }
        }
    }

    /**
     * Reads in a single RIL message off the wire. A RIL message consists
     * of a 4-byte little-endian length and a subsequent series of bytes.
     * The final message (length header omitted) is read into
     * <code>buffer</code> and the length of the final message (less header)
     * is returned. A return value of -1 indicates end-of-stream.
     *
     * @param is non-null; Stream to read from
     * @param buffer Buffer to fill in. Must be as large as maximum
     * message size, or an ArrayOutOfBounds exception will be thrown.
     * @return Length of message less header, or -1 on end of stream.
     * @throws IOException
     */
    private static int readRilMessage(InputStream is, byte[] buffer)
            throws IOException {
        int countRead;
        int offset;
        int remaining;
        int messageLength;

        // First, read in the length of the message
        offset = 0;
        remaining = 4;
        do {
            countRead = is.read(buffer, offset, remaining);

            if (countRead < 0 ) {
                Rlog.e(RILJ_LOG_TAG, "Hit EOS reading message length");
                return -1;
            }

            offset += countRead;
            remaining -= countRead;
        } while (remaining > 0);

        messageLength = ((buffer[0] & 0xff) << 24)
                | ((buffer[1] & 0xff) << 16)
                | ((buffer[2] & 0xff) << 8)
                | (buffer[3] & 0xff);

        // Then, re-use the buffer and read in the message itself
        offset = 0;
        remaining = messageLength;
        do {
            countRead = is.read(buffer, offset, remaining);

            if (countRead < 0 ) {
                Rlog.e(RILJ_LOG_TAG, "Hit EOS reading message.  messageLength=" + messageLength
                        + " remaining=" + remaining);
                return -1;
            }

            offset += countRead;
            remaining -= countRead;
        } while (remaining > 0);

        return messageLength;
    }

    class RILReceiver implements Runnable {
        byte[] buffer;

        RILReceiver() {
            buffer = new byte[RIL_MAX_COMMAND_BYTES];
        }

        @Override
        public void
        run() {
            int retryCount = 0;
            String rilSocket = "rild";

            try {for (;;) {
                LocalSocket s = null;
                LocalSocketAddress l;

                if (mInstanceId == null || mInstanceId == 0 ) {
                    rilSocket = SOCKET_NAME_RIL[0];
                } else {
                    rilSocket = SOCKET_NAME_RIL[mInstanceId];
                }

                /* M: C2K start */
                int phoneType = TelephonyManager.getPhoneType(mPreferredNetworkType);
                if (phoneType == PhoneConstants.PHONE_TYPE_CDMA) {
                    rilSocket = C2K_SOCKET_NAME_RIL;
                }
                /* M: C2K end */

                try {
                    s = new LocalSocket();
                    l = new LocalSocketAddress(rilSocket,
                            LocalSocketAddress.Namespace.RESERVED);
                    s.connect(l);
                } catch (IOException ex){
                    try {
                        if (s != null) {
                            s.close();
                        }
                    } catch (IOException ex2) {
                        //ignore failure to close after failure to connect
                    }

                    // don't print an error message after the the first time
                    // or after the 8th time

                    if (retryCount == 8) {
                        Rlog.e (RILJ_LOG_TAG,
                            "Couldn't find '" + rilSocket
                            + "' socket after " + retryCount
                            + " times, continuing to retry silently");
                    } else if (retryCount > 0 && retryCount < 8) {
                        Rlog.i (RILJ_LOG_TAG,
                            "Couldn't find '" + rilSocket
                            + "' socket; retrying after timeout");
                    }

                    try {
                        Thread.sleep(SOCKET_OPEN_RETRY_MILLIS);
                    } catch (InterruptedException er) {
                    }

                    retryCount++;
                    continue;
                }

                retryCount = 0;

                mSocket = s;
                Rlog.i(RILJ_LOG_TAG, "Connected to '" + rilSocket + "' socket");

                int length = 0;
                try {
                    InputStream is = mSocket.getInputStream();

                    for (;;) {
                        Parcel p;

                        length = readRilMessage(is, buffer);

                        if (length < 0) {
                            // End-of-stream reached
                            break;
                        }

                        p = Parcel.obtain();
                        p.unmarshall(buffer, 0, length);
                        p.setDataPosition(0);

                        //Rlog.v(RILJ_LOG_TAG, "Read packet: " + length + " bytes");

                        processResponse(p);
                        p.recycle();
                    }
                } catch (java.io.IOException ex) {
                    Rlog.i(RILJ_LOG_TAG, "'" + rilSocket + "' socket closed",
                          ex);
                } catch (Throwable tr) {
                    Rlog.e(RILJ_LOG_TAG, "Uncaught exception read length=" + length +
                        "Exception:" + tr.toString());
                }

                Rlog.i(RILJ_LOG_TAG, "Disconnected from '" + rilSocket
                      + "' socket");

                setRadioState (RadioState.RADIO_UNAVAILABLE);

                try {
                    mSocket.close();
                } catch (IOException ex) {
                }

                mSocket = null;
                RILRequest.resetSerial();

                // Clear request list on close
                clearRequestList(RADIO_NOT_AVAILABLE, false);
            }} catch (Throwable tr) {
                Rlog.e(RILJ_LOG_TAG,"Uncaught exception", tr);
            }

            /* We're disconnected so we don't know the ril version */
            notifyRegistrantsRilConnectionChanged(-1);
        }
    }



    //***** Constructors

    public RIL(Context context, int preferredNetworkType, int cdmaSubscription) {
        this(context, preferredNetworkType, cdmaSubscription, null);
    }

    public RIL(Context context, int preferredNetworkType,
            int cdmaSubscription, Integer instanceId) {
        super(context);
        if (RILJ_LOGD) {
            riljLog("RIL(context, preferredNetworkType=" + preferredNetworkType +
                    " cdmaSubscription=" + cdmaSubscription + ")");
        }

        mContext = context;
        mCdmaSubscription  = cdmaSubscription;
        mPreferredNetworkType = preferredNetworkType;
        mPhoneType = RILConstants.NO_PHONE;
        mInstanceId = instanceId;

        PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, RILJ_LOG_TAG);
        mWakeLock.setReferenceCounted(false);
        mWakeLockTimeout = SystemProperties.getInt(TelephonyProperties.PROPERTY_WAKE_LOCK_TIMEOUT,
                DEFAULT_WAKE_LOCK_TIMEOUT);
        mWakeLockCount = 0;

        mSenderThread = new HandlerThread("RILSender");
        mSenderThread.start();

        Looper looper = mSenderThread.getLooper();
        mSender = new RILSender(looper);

        ConnectivityManager cm = (ConnectivityManager)context.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        if (cm.isNetworkSupported(ConnectivityManager.TYPE_MOBILE) == false) {
            riljLog("Not starting RILReceiver: wifi-only");
        } else {
            riljLog("Starting RILReceiver");
            mReceiver = new RILReceiver();
            mReceiverThread = new Thread(mReceiver, "RILReceiver");
            mReceiverThread.start();

            DisplayManager dm = (DisplayManager)context.getSystemService(
                    Context.DISPLAY_SERVICE);
            mDefaultDisplay = dm.getDisplay(Display.DEFAULT_DISPLAY);
            dm.registerDisplayListener(mDisplayListener, null);

            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction("com.mtk.TEST_TRM");
            context.registerReceiver(mIntentReceiver, filter);
        }

        if (!SystemProperties.get("ro.mtk_bsp_package").equals("1")) {
            try {
                mServiceStateExt = MPlugin.createInstance(
                        IServiceStateExt.class.getName(), context);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        TelephonyDevController tdc = TelephonyDevController.getInstance();
        tdc.registerRIL(this);
    }

    //***** CommandsInterface implementation

    @Override
    public void getVoiceRadioTechnology(Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_VOICE_RADIO_TECH, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }


    public void getImsRegistrationState(Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_IMS_REGISTRATION_STATE, result);

        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        }
        send(rr);
    }

    @Override public void
    setOnNITZTime(Handler h, int what, Object obj) {
        super.setOnNITZTime(h, what, obj);

        // Send the last NITZ time if we have it
        if (mLastNITZTimeInfo != null) {
            mNITZTimeRegistrant
                .notifyRegistrant(
                    new AsyncResult (null, mLastNITZTimeInfo, null));
            mLastNITZTimeInfo = null;
        }
    }

    @Override
    public void
    getIccCardStatus(Message result) {
        //Note: This RIL request has not been renamed to ICC,
        //       but this request is also valid for SIM and RUIM
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_GET_SIM_STATUS, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    public void setUiccSubscription(int slotId, int appIndex, int subId,
            int subStatus, Message result) {
        //Note: This RIL request is also valid for SIM and RUIM (ICC card)
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_UICC_SUBSCRIPTION, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                + " slot: " + slotId + " appIndex: " + appIndex
                + " subId: " + subId + " subStatus: " + subStatus);

        rr.mParcel.writeInt(slotId);
        rr.mParcel.writeInt(appIndex);
        rr.mParcel.writeInt(subId);
        rr.mParcel.writeInt(subStatus);

        send(rr);
    }

    // FIXME This API should take an AID and slot ID
    public void setDataAllowed(boolean allowed, Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_ALLOW_DATA, result);
        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(allowed ? 1 : 0);
        send(rr);
    }

    @Override public void
    supplyIccPin(String pin, Message result) {
        supplyIccPinForApp(pin, null, result);
    }

    @Override public void
    supplyIccPinForApp(String pin, String aid, Message result) {
        //Note: This RIL request has not been renamed to ICC,
        //       but this request is also valid for SIM and RUIM
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_ENTER_SIM_PIN, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        rr.mParcel.writeInt(2);
        rr.mParcel.writeString(pin);
        rr.mParcel.writeString(aid);

        send(rr);
    }

    @Override public void
    supplyIccPuk(String puk, String newPin, Message result) {
        supplyIccPukForApp(puk, newPin, null, result);
    }

    @Override public void
    supplyIccPukForApp(String puk, String newPin, String aid, Message result) {
        //Note: This RIL request has not been renamed to ICC,
        //       but this request is also valid for SIM and RUIM
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_ENTER_SIM_PUK, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        rr.mParcel.writeInt(3);
        rr.mParcel.writeString(puk);
        rr.mParcel.writeString(newPin);
        rr.mParcel.writeString(aid);

        send(rr);
    }

    @Override public void
    supplyIccPin2(String pin, Message result) {
        supplyIccPin2ForApp(pin, null, result);
    }

    @Override public void
    supplyIccPin2ForApp(String pin, String aid, Message result) {
        //Note: This RIL request has not been renamed to ICC,
        //       but this request is also valid for SIM and RUIM
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_ENTER_SIM_PIN2, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        rr.mParcel.writeInt(2);
        rr.mParcel.writeString(pin);
        rr.mParcel.writeString(aid);

        send(rr);
    }

    @Override public void
    supplyIccPuk2(String puk2, String newPin2, Message result) {
        supplyIccPuk2ForApp(puk2, newPin2, null, result);
    }

    @Override public void
    supplyIccPuk2ForApp(String puk, String newPin2, String aid, Message result) {
        //Note: This RIL request has not been renamed to ICC,
        //       but this request is also valid for SIM and RUIM
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_ENTER_SIM_PUK2, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        rr.mParcel.writeInt(3);
        rr.mParcel.writeString(puk);
        rr.mParcel.writeString(newPin2);
        rr.mParcel.writeString(aid);

        send(rr);
    }

    @Override public void
    changeIccPin(String oldPin, String newPin, Message result) {
        changeIccPinForApp(oldPin, newPin, null, result);
    }

    @Override public void
    changeIccPinForApp(String oldPin, String newPin, String aid, Message result) {
        //Note: This RIL request has not been renamed to ICC,
        //       but this request is also valid for SIM and RUIM
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_CHANGE_SIM_PIN, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        rr.mParcel.writeInt(3);
        rr.mParcel.writeString(oldPin);
        rr.mParcel.writeString(newPin);
        rr.mParcel.writeString(aid);

        send(rr);
    }

    @Override public void
    changeIccPin2(String oldPin2, String newPin2, Message result) {
        changeIccPin2ForApp(oldPin2, newPin2, null, result);
    }

    @Override public void
    changeIccPin2ForApp(String oldPin2, String newPin2, String aid, Message result) {
        //Note: This RIL request has not been renamed to ICC,
        //       but this request is also valid for SIM and RUIM
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_CHANGE_SIM_PIN2, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        rr.mParcel.writeInt(3);
        rr.mParcel.writeString(oldPin2);
        rr.mParcel.writeString(newPin2);
        rr.mParcel.writeString(aid);

        send(rr);
    }

    @Override
    public void
    changeBarringPassword(String facility, String oldPwd, String newPwd, Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_CHANGE_BARRING_PASSWORD, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        rr.mParcel.writeInt(3);
        rr.mParcel.writeString(facility);
        rr.mParcel.writeString(oldPwd);
        rr.mParcel.writeString(newPwd);

        send(rr);
    }

    @Override
    public void
    supplyNetworkDepersonalization(String netpin, Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_ENTER_NETWORK_DEPERSONALIZATION, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        rr.mParcel.writeInt(1);
        rr.mParcel.writeString(netpin);

        send(rr);
    }

    @Override
    public void
    getCurrentCalls (Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_GET_CURRENT_CALLS, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    @Override
    @Deprecated public void
    getPDPContextList(Message result) {
        getDataCallList(result);
    }

    @Override
    public void
    getDataCallList(Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_DATA_CALL_LIST, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    @Override
    public void
    dial (String address, int clirMode, Message result) {
        dial(address, clirMode, null, result);
    }

    @Override
    public void
    dial(String address, int clirMode, UUSInfo uusInfo, Message result) {
        if (!PhoneNumberUtils.isUriNumber(address)) {
           RILRequest rr = RILRequest.obtain(RIL_REQUEST_DIAL, result);

           rr.mParcel.writeString(address);
           rr.mParcel.writeInt(clirMode);

           if (uusInfo == null) {
              rr.mParcel.writeInt(0); // UUS information is absent
           } else {
              rr.mParcel.writeInt(1); // UUS information is present
              rr.mParcel.writeInt(uusInfo.getType());
              rr.mParcel.writeInt(uusInfo.getDcs());
              rr.mParcel.writeByteArray(uusInfo.getUserData());
           }

           if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

           send(rr);
        } else {
           RILRequest rr = RILRequest.obtain(RIL_REQUEST_DIAL_WITH_SIP_URI, result);

           rr.mParcel.writeString(address);
           if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
           send(rr);
        }
    }

    @Override
    public void
    getIMSI(Message result) {
        getIMSIForApp(null, result);
    }

    @Override
    public void
    getIMSIForApp(String aid, Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_GET_IMSI, result);

        rr.mParcel.writeInt(1);
        rr.mParcel.writeString(aid);

        if (RILJ_LOGD) riljLog(rr.serialString() +
                              "> getIMSI: " + requestToString(rr.mRequest)
                              + " aid: " + aid);

        send(rr);
    }

    @Override
    public void
    getIMEI(Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_GET_IMEI, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    @Override
    public void
    getIMEISV(Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_GET_IMEISV, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }


    @Override
    public void
    hangupConnection (int gsmIndex, Message result) {
        if (RILJ_LOGD) riljLog("hangupConnection: gsmIndex=" + gsmIndex);

        RILRequest rr = RILRequest.obtain(RIL_REQUEST_HANGUP, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + " " +
                gsmIndex);

        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(gsmIndex);

        send(rr);
    }

    @Override
    public void
    hangupWaitingOrBackground (Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_HANGUP_WAITING_OR_BACKGROUND,
                                        result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    @Override
    public void
    hangupForegroundResumeBackground (Message result) {
        RILRequest rr
                = RILRequest.obtain(
                        RIL_REQUEST_HANGUP_FOREGROUND_RESUME_BACKGROUND,
                                        result);
        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    @Override
    public void
    switchWaitingOrHoldingAndActive (Message result) {
        RILRequest rr
                = RILRequest.obtain(
                        RIL_REQUEST_SWITCH_WAITING_OR_HOLDING_AND_ACTIVE,
                                        result);
        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        /* M: call control part start */
        handleChldRelatedRequest(rr);
        /* M: call control part end */
    }

    @Override
    public void
    conference (Message result) {

        /* M: call control part start */
        if (MobileManagerUtils.isSupported()) {
            if (!checkMoMSSubPermission(SubPermissions.MAKE_CONFERENCE_CALL)) {
                return;
            }
        }
        /* M: call control part end */

        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_CONFERENCE, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        /* M: call control part start */
        handleChldRelatedRequest(rr);
        /* M: call control part end */
    }


    @Override
    public void setPreferredVoicePrivacy(boolean enable, Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_CDMA_SET_PREFERRED_VOICE_PRIVACY_MODE,
                result);

        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(enable ? 1:0);

        send(rr);
    }

    @Override
    public void getPreferredVoicePrivacy(Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_CDMA_QUERY_PREFERRED_VOICE_PRIVACY_MODE,
                result);
        send(rr);
    }

    @Override
    public void
    separateConnection (int gsmIndex, Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_SEPARATE_CONNECTION, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                            + " " + gsmIndex);

        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(gsmIndex);

        /* M: call control part start */
        handleChldRelatedRequest(rr);
        /* M: call control part end */
    }

    @Override
    public void
    acceptCall (Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_ANSWER, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    @Override
    public void
    rejectCall (Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_UDUB, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    @Override
    public void
    explicitCallTransfer (Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_EXPLICIT_CALL_TRANSFER, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        /* M: call control part start */
        handleChldRelatedRequest(rr);
        /* M: call control part end */
    }

    @Override
    public void
    getLastCallFailCause (Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_LAST_CALL_FAIL_CAUSE, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    /**
     * @deprecated
     */
    @Deprecated
    @Override
    public void
    getLastPdpFailCause (Message result) {
        getLastDataCallFailCause (result);
    }

    /**
     * The preferred new alternative to getLastPdpFailCause
     */
    @Override
    public void
    getLastDataCallFailCause (Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_LAST_DATA_CALL_FAIL_CAUSE, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    @Override
    public void
    setMute (boolean enableMute, Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_SET_MUTE, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                            + " " + enableMute);

        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(enableMute ? 1 : 0);

        send(rr);
    }

    @Override
    public void
    getMute (Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_GET_MUTE, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    @Override
    public void
    getSignalStrength (Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_SIGNAL_STRENGTH, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    @Override
    public void
    getVoiceRegistrationState (Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_VOICE_REGISTRATION_STATE, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    @Override
    public void
    getDataRegistrationState (Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_DATA_REGISTRATION_STATE, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    @Override
    public void
    getOperator(Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_OPERATOR, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    @Override
    public void
    getHardwareConfig (Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_GET_HARDWARE_CONFIG, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    @Override
    public void
    sendDtmf(char c, Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_DTMF, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        rr.mParcel.writeString(Character.toString(c));

        send(rr);
    }

    @Override
    public void
    startDtmf(char c, Message result) {
        /* M: call control part start */
        /* DTMF request will be ignored when the count of requests reaches 32 */
        synchronized (mDtmfReqQueue) {
            if (!mDtmfReqQueue.hasSendChldRequest() && mDtmfReqQueue.size() < mDtmfReqQueue.MAXIMUM_DTMF_REQUEST) {
                if (!mDtmfReqQueue.isStart()) {
                    RILRequest rr = RILRequest.obtain(RIL_REQUEST_DTMF_START, result);

                    rr.mParcel.writeString(Character.toString(c));
                    mDtmfReqQueue.start();
                    mDtmfReqQueue.add(rr);
                    if (mDtmfReqQueue.size() == 1) {
                        riljLog("send start dtmf");
                        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
                        send(rr);
                    }
                } else {
                    riljLog("DTMF status conflict, want to start DTMF when status is " + mDtmfReqQueue.isStart());
                }
            }
        }
        /* M: call control part end */
    }
    @Override
    public void
    stopDtmf(Message result) {
        /* M: call control part start */
        /* DTMF request will be ignored when the count of requests reaches 32 */
        synchronized (mDtmfReqQueue) {
            if (!mDtmfReqQueue.hasSendChldRequest() && mDtmfReqQueue.size() < mDtmfReqQueue.MAXIMUM_DTMF_REQUEST) {
                if (mDtmfReqQueue.isStart()) {
                    RILRequest rr = RILRequest.obtain(RIL_REQUEST_DTMF_STOP, result);

                    mDtmfReqQueue.stop();
                    mDtmfReqQueue.add(rr);
                    if (mDtmfReqQueue.size() == 1) {
                        riljLog("send stop dtmf");
                        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
                        send(rr);
                    }
                } else {
                    riljLog("DTMF status conflict, want to start DTMF when status is " + mDtmfReqQueue.isStart());
                }
            }
        }
        /* M: call control part end */
    }
    @Override
    public void
    sendBurstDtmf(String dtmfString, int on, int off, Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_CDMA_BURST_DTMF, result);

        rr.mParcel.writeInt(3);
        rr.mParcel.writeString(dtmfString);
        rr.mParcel.writeString(Integer.toString(on));
        rr.mParcel.writeString(Integer.toString(off));

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                + " : " + dtmfString);

        send(rr);
    }

    private void
    constructGsmSendSmsRilRequest (RILRequest rr, String smscPDU, String pdu) {
        rr.mParcel.writeInt(2);
        rr.mParcel.writeString(smscPDU);
        rr.mParcel.writeString(pdu);
    }

    public void
    sendSMS (String smscPDU, String pdu, Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_SEND_SMS, result);

        constructGsmSendSmsRilRequest(rr, smscPDU, pdu);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    @Override
    public void
    sendSMSExpectMore (String smscPDU, String pdu, Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_SEND_SMS_EXPECT_MORE, result);

        constructGsmSendSmsRilRequest(rr, smscPDU, pdu);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    private void
    constructCdmaSendSmsRilRequest(RILRequest rr, byte[] pdu) {
        int address_nbr_of_digits;
        int subaddr_nbr_of_digits;
        int bearerDataLength;
        ByteArrayInputStream bais = new ByteArrayInputStream(pdu);
        DataInputStream dis = new DataInputStream(bais);

        try {
            rr.mParcel.writeInt(dis.readInt()); //teleServiceId
            rr.mParcel.writeByte((byte) dis.readInt()); //servicePresent
            rr.mParcel.writeInt(dis.readInt()); //serviceCategory
            rr.mParcel.writeInt(dis.read()); //address_digit_mode
            rr.mParcel.writeInt(dis.read()); //address_nbr_mode
            rr.mParcel.writeInt(dis.read()); //address_ton
            rr.mParcel.writeInt(dis.read()); //address_nbr_plan
            address_nbr_of_digits = (byte) dis.read();
            rr.mParcel.writeByte((byte) address_nbr_of_digits);
            for(int i=0; i < address_nbr_of_digits; i++){
                rr.mParcel.writeByte(dis.readByte()); // address_orig_bytes[i]
            }
            rr.mParcel.writeInt(dis.read()); //subaddressType
            rr.mParcel.writeByte((byte) dis.read()); //subaddr_odd
            subaddr_nbr_of_digits = (byte) dis.read();
            rr.mParcel.writeByte((byte) subaddr_nbr_of_digits);
            for(int i=0; i < subaddr_nbr_of_digits; i++){
                rr.mParcel.writeByte(dis.readByte()); //subaddr_orig_bytes[i]
            }

            bearerDataLength = dis.read();
            rr.mParcel.writeInt(bearerDataLength);
            for(int i=0; i < bearerDataLength; i++){
                rr.mParcel.writeByte(dis.readByte()); //bearerData[i]
            }
        }catch (IOException ex){
            if (RILJ_LOGD) riljLog("sendSmsCdma: conversion from input stream to object failed: "
                    + ex);
        }
    }

    public void
    sendCdmaSms(byte[] pdu, Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_CDMA_SEND_SMS, result);

        constructCdmaSendSmsRilRequest(rr, pdu);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    public void
    sendImsGsmSms (String smscPDU, String pdu, int retry, int messageRef,
            Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_IMS_SEND_SMS, result);

        rr.mParcel.writeInt(RILConstants.GSM_PHONE);
        rr.mParcel.writeByte((byte)retry);
        rr.mParcel.writeInt(messageRef);

        constructGsmSendSmsRilRequest(rr, smscPDU, pdu);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    public void
    sendImsCdmaSms(byte[] pdu, int retry, int messageRef, Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_IMS_SEND_SMS, result);

        rr.mParcel.writeInt(RILConstants.CDMA_PHONE);
        rr.mParcel.writeByte((byte)retry);
        rr.mParcel.writeInt(messageRef);

        constructCdmaSendSmsRilRequest(rr, pdu);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    @Override
    public void deleteSmsOnSim(int index, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_DELETE_SMS_ON_SIM,
                response);

        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(index);

        if (RILJ_LOGV) riljLog(rr.serialString() + "> "
                + requestToString(rr.mRequest)
                + " " + index);

        send(rr);
    }

    @Override
    public void deleteSmsOnRuim(int index, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_CDMA_DELETE_SMS_ON_RUIM,
                response);

        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(index);

        if (RILJ_LOGV) riljLog(rr.serialString() + "> "
                + requestToString(rr.mRequest)
                + " " + index);

        send(rr);
    }

    @Override
    public void writeSmsToSim(int status, String smsc, String pdu, Message response) {
        status = translateStatus(status);

        RILRequest rr = RILRequest.obtain(RIL_REQUEST_WRITE_SMS_TO_SIM,
                response);

        rr.mParcel.writeInt(status);
        rr.mParcel.writeString(pdu);
        rr.mParcel.writeString(smsc);

        if (RILJ_LOGV) riljLog(rr.serialString() + "> "
                + requestToString(rr.mRequest)
                + " " + status);

        send(rr);
    }

    @Override
    public void writeSmsToRuim(int status, String pdu, Message response) {
        status = translateStatus(status);

        RILRequest rr = RILRequest.obtain(RIL_REQUEST_CDMA_WRITE_SMS_TO_RUIM,
                response);

        rr.mParcel.writeInt(status);
        rr.mParcel.writeString(pdu);

        if (RILJ_LOGV) riljLog(rr.serialString() + "> "
                + requestToString(rr.mRequest)
                + " " + status);

        send(rr);
    }

    /**
     *  Translates EF_SMS status bits to a status value compatible with
     *  SMS AT commands.  See TS 27.005 3.1.
     */
    private int translateStatus(int status) {
        switch(status & 0x7) {
            case SmsManager.STATUS_ON_ICC_READ:
                return 1;
            case SmsManager.STATUS_ON_ICC_UNREAD:
                return 0;
            case SmsManager.STATUS_ON_ICC_SENT:
                return 3;
            case SmsManager.STATUS_ON_ICC_UNSENT:
                return 2;
        }

        // Default to READ.
        return 1;
    }

    @Override
    public void
    setupDataCall(String radioTechnology, String profile, String apn,
            String user, String password, String authType, String protocol,
            Message result) {
        /* [Note by mtk01411] In original Android2.1 release: MAX PDP Connection is 1
        * request_cid is only allowed to set as "1" manually
        */
        String interfaceId = "1";
        setupDataCall(radioTechnology, profile, apn, user, password, authType, protocol, interfaceId, result);
    }

    @Override
    public void
    setupDataCall(String radioTechnology, String profile, String apn,
            String user, String password, String authType, String protocol,
            String interfaceId, Message result) {
        DefaultBearerConfig defaultBearerConfig = new DefaultBearerConfig();
        setupDataCall(radioTechnology, profile, apn, user, password, authType, protocol, interfaceId, defaultBearerConfig, result);
    }

    @Override
    public void
    setupDataCall(String radioTechnology, String profile, String apn,
            String user, String password, String authType, String protocol,
            String interfaceId, DefaultBearerConfig defaultBearerConfig, Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_SETUP_DATA_CALL, result);

        if (SystemProperties.get("ro.mtk_volte_support").equals("1")) {
            rr.mParcel.writeInt(17);
        } else {
            rr.mParcel.writeInt(8); //the number should be changed according to number of parameters
        }

        rr.mParcel.writeString(radioTechnology);
        rr.mParcel.writeString(profile);
        rr.mParcel.writeString(apn);
        rr.mParcel.writeString(user);
        rr.mParcel.writeString(password);
        rr.mParcel.writeString(authType);
        rr.mParcel.writeString(protocol);

        /** M: specify interface Id */
        rr.mParcel.writeString(interfaceId);

        //VoLTE
        rr.mParcel.writeString("" + defaultBearerConfig.mIsValid);
        rr.mParcel.writeString("" + defaultBearerConfig.mQos.qci);
        rr.mParcel.writeString("" + defaultBearerConfig.mQos.dlGbr);
        rr.mParcel.writeString("" + defaultBearerConfig.mQos.ulGbr);
        rr.mParcel.writeString("" + defaultBearerConfig.mQos.dlMbr);
        rr.mParcel.writeString("" + defaultBearerConfig.mQos.ulMbr);
        rr.mParcel.writeString("" + defaultBearerConfig.mEmergency_ind);
        rr.mParcel.writeString("" + defaultBearerConfig.mPcscf_discovery_flag);
        rr.mParcel.writeString("" + defaultBearerConfig.mSignaling_flag);


        if (RILJ_LOGD) riljLog(rr.serialString() + "> "
                + requestToString(rr.mRequest) + " " + radioTechnology + " "
                + profile + " " + apn + " " + user + " "
                + password + " " + authType + " " + protocol + " " + interfaceId + " " + defaultBearerConfig);

        send(rr);
    }

    @Override
    public void
    deactivateDataCall(int cid, int reason, Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_DEACTIVATE_DATA_CALL, result);

        rr.mParcel.writeInt(2);
        rr.mParcel.writeString(Integer.toString(cid));
        rr.mParcel.writeString(Integer.toString(reason));

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " +
                requestToString(rr.mRequest) + " " + cid + " " + reason);

        send(rr);
    }

    @Override
    public void
    setRadioPower(boolean on, Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_RADIO_POWER, result);

        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(on ? 1 : 0);

        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                    + (on ? " on" : " off"));
        }

        send(rr);
    }

    @Override
    public void requestShutdown(Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SHUTDOWN, result);

        if (RILJ_LOGD)
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    //MTK-START [mtk06800] modem power on/off
    @Override
    public void setModemPower(boolean power, Message result) {

        if (RILJ_LOGD) riljLog("Set Modem power as: " + power);
        RILRequest rr;

        if (power) {
            rr = RILRequest.obtain(RIL_REQUEST_MODEM_POWERON, result);
        }
        else {
            rr = RILRequest.obtain(RIL_REQUEST_MODEM_POWEROFF, result);
        }

        if (RILJ_LOGD) riljLog(rr.serialString() + "> "
            + requestToString(rr.mRequest));

        send(rr);
    }
    //MTK-END [mtk06800] modem power on/off

    @Override
    public void
    setSuppServiceNotifications(boolean enable, Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_SET_SUPP_SVC_NOTIFICATION, result);

        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(enable ? 1 : 0);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> "
                + requestToString(rr.mRequest));

        send(rr);
    }

    @Override
    public void
    acknowledgeLastIncomingGsmSms(boolean success, int cause, Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_SMS_ACKNOWLEDGE, result);

        rr.mParcel.writeInt(2);
        rr.mParcel.writeInt(success ? 1 : 0);
        rr.mParcel.writeInt(cause);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                + " " + success + " " + cause);

        send(rr);
    }

    @Override
    public void
    acknowledgeLastIncomingCdmaSms(boolean success, int cause, Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_CDMA_SMS_ACKNOWLEDGE, result);

        rr.mParcel.writeInt(success ? 0 : 1); //RIL_CDMA_SMS_ErrorClass
        // cause code according to X.S004-550E
        rr.mParcel.writeInt(cause);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                + " " + success + " " + cause);

        send(rr);
    }

    @Override
    public void
    acknowledgeIncomingGsmSmsWithPdu(boolean success, String ackPdu, Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_ACKNOWLEDGE_INCOMING_GSM_SMS_WITH_PDU, result);

        rr.mParcel.writeInt(2);
        rr.mParcel.writeString(success ? "1" : "0");
        rr.mParcel.writeString(ackPdu);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                + ' ' + success + " [" + ackPdu + ']');

        send(rr);
    }

    @Override
    public void
    iccIO (int command, int fileid, String path, int p1, int p2, int p3,
            String data, String pin2, Message result) {
        iccIOForApp(command, fileid, path, p1, p2, p3, data, pin2, null, result);
    }
    @Override
    public void
    iccIOForApp (int command, int fileid, String path, int p1, int p2, int p3,
            String data, String pin2, String aid, Message result) {
        //Note: This RIL request has not been renamed to ICC,
        //       but this request is also valid for SIM and RUIM
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_SIM_IO, result);

        rr.mParcel.writeInt(command);
        rr.mParcel.writeInt(fileid);
        rr.mParcel.writeString(path);
        rr.mParcel.writeInt(p1);
        rr.mParcel.writeInt(p2);
        rr.mParcel.writeInt(p3);
        rr.mParcel.writeString(data);
        rr.mParcel.writeString(pin2);
        rr.mParcel.writeString(aid);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> iccIO: "
                + requestToString(rr.mRequest)
                + " 0x" + Integer.toHexString(command)
                + " 0x" + Integer.toHexString(fileid) + " "
                + " path: " + path + ","
                + p1 + "," + p2 + "," + p3
                + " aid: " + aid);

        send(rr);
    }

    @Override
    public void
    getCLIR(Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_GET_CLIR, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    @Override
    public void
    setCLIR(int clirMode, Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_SET_CLIR, result);

        // count ints
        rr.mParcel.writeInt(1);

        rr.mParcel.writeInt(clirMode);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                    + " " + clirMode);

        send(rr);
    }

    @Override
    public void
    queryCallWaiting(int serviceClass, Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_QUERY_CALL_WAITING, response);

        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(serviceClass);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                    + " " + serviceClass);

        send(rr);
    }

    @Override
    public void
    setCallWaiting(boolean enable, int serviceClass, Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_SET_CALL_WAITING, response);

        rr.mParcel.writeInt(2);
        rr.mParcel.writeInt(enable ? 1 : 0);
        rr.mParcel.writeInt(serviceClass);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                + " " + enable + ", " + serviceClass);

        send(rr);
    }

    /* M: SS part */
    // mtk00732 add for getCOLP
    public void
    getCOLP(Message result) {
        RILRequest rr
                //= RILRequest.obtain(RIL_REQUEST_GET_COLP, result, mySimId);
                = RILRequest.obtain(RIL_REQUEST_GET_COLP, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    // mtk00732 add for setCOLP
    public void
    setCOLP(boolean enable, Message result) {
        RILRequest rr
                //= RILRequest.obtain(RIL_REQUEST_SET_COLP, result, mySimId);
                = RILRequest.obtain(RIL_REQUEST_SET_COLP, result);

        // count ints
        rr.mParcel.writeInt(1);

        if (enable) {
            rr.mParcel.writeInt(1);
        } else {
            rr.mParcel.writeInt(0);
        }

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                    + " " + enable);

        send(rr);
    }

    // mtk00732 add for getCOLR
    public void
    getCOLR(Message result) {
        RILRequest rr
                //= RILRequest.obtain(RIL_REQUEST_GET_COLR, result, mySimId);
                = RILRequest.obtain(RIL_REQUEST_GET_COLR, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }
    /* M: SS part end */

    @Override
    public void
    setNetworkSelectionModeAutomatic(Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_SET_NETWORK_SELECTION_AUTOMATIC,
                                    response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    @Override
    public void
    setNetworkSelectionModeManual(String operatorNumeric, Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_SET_NETWORK_SELECTION_MANUAL,
                                    response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                    + " " + operatorNumeric);

        rr.mParcel.writeString(operatorNumeric);

        send(rr);
    }

    @Override
    public void
    getNetworkSelectionMode(Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_QUERY_NETWORK_SELECTION_MODE,
                                    response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    @Override
    public void
    getAvailableNetworks(Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_QUERY_AVAILABLE_NETWORKS_WITH_ACT,
                                    response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    public void
    cancelAvailableNetworks(Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_ABORT_QUERY_AVAILABLE_NETWORKS,
                                    response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        send(rr);
    }

    @Override
    public void
    setCallForward(int action, int cfReason, int serviceClass,
                String number, int timeSeconds, Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_SET_CALL_FORWARD, response);

        rr.mParcel.writeInt(action);
        rr.mParcel.writeInt(cfReason);
        rr.mParcel.writeInt(serviceClass);
        rr.mParcel.writeInt(PhoneNumberUtils.toaFromString(number));
        rr.mParcel.writeString(number);
        rr.mParcel.writeInt (timeSeconds);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                    + " " + action + " " + cfReason + " " + serviceClass
                    + timeSeconds);

        send(rr);
    }

    @Override
    public void
    queryCallForwardStatus(int cfReason, int serviceClass,
                String number, Message response) {
        RILRequest rr
            = RILRequest.obtain(RIL_REQUEST_QUERY_CALL_FORWARD_STATUS, response);

        rr.mParcel.writeInt(2); // 2 is for query action, not in used anyway
        rr.mParcel.writeInt(cfReason);
        rr.mParcel.writeInt(serviceClass);
        rr.mParcel.writeInt(PhoneNumberUtils.toaFromString(number));
        rr.mParcel.writeString(number);
        rr.mParcel.writeInt (0);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                + " " + cfReason + " " + serviceClass);

        send(rr);
    }

    @Override
    public void
    queryCLIP(Message response) {
        RILRequest rr
            = RILRequest.obtain(RIL_REQUEST_QUERY_CLIP, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }


    @Override
    public void
    getBasebandVersion (Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_BASEBAND_VERSION, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    @Override
    public void
    queryFacilityLock(String facility, String password, int serviceClass,
                            Message response) {
        queryFacilityLockForApp(facility, password, serviceClass, null, response);
    }

    @Override
    public void
    queryFacilityLockForApp(String facility, String password, int serviceClass, String appId,
                            Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_QUERY_FACILITY_LOCK, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                                                 + " [" + facility + " " + serviceClass
                                                 + " " + appId + "]");

        // count strings
        rr.mParcel.writeInt(4);

        rr.mParcel.writeString(facility);
        rr.mParcel.writeString(password);

        rr.mParcel.writeString(Integer.toString(serviceClass));
        rr.mParcel.writeString(appId);

        send(rr);
    }

    @Override
    public void
    setFacilityLock (String facility, boolean lockState, String password,
                        int serviceClass, Message response) {
        setFacilityLockForApp(facility, lockState, password, serviceClass, null, response);
    }

    @Override
    public void
    setFacilityLockForApp(String facility, boolean lockState, String password,
                        int serviceClass, String appId, Message response) {
        String lockString;
         RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_SET_FACILITY_LOCK, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                                                        + " [" + facility + " " + lockState
                                                        + " " + serviceClass + " " + appId + "]");

        // count strings
        rr.mParcel.writeInt(5);

        rr.mParcel.writeString(facility);
        lockString = (lockState)?"1":"0";
        rr.mParcel.writeString(lockString);
        rr.mParcel.writeString(password);
        rr.mParcel.writeString(Integer.toString(serviceClass));
        rr.mParcel.writeString(appId);

        send(rr);

    }

    @Override
    public void
    sendUSSD (String ussdString, Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_SEND_USSD, response);

        if (RILJ_LOGD) {
            String logUssdString = "*******";
            if (RILJ_LOGV) logUssdString = ussdString;
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                                   + " " + logUssdString);
        }

        rr.mParcel.writeString(ussdString);

        send(rr);
    }

    /* M: SS part */
    ///M: For query CNAP
    public void sendCNAPSS(String cnapssString, Message response) {
        RILRequest rr
                //= RILRequest.obtain(RIL_REQUEST_SEND_CNAP, response, mySimId);
                = RILRequest.obtain(RIL_REQUEST_SEND_CNAP, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                + " " + cnapssString);

        rr.mParcel.writeString(cnapssString);

        send(rr);
    }
    /* M: SS part end */

    // inherited javadoc suffices
    @Override
    public void cancelPendingUssd (Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_CANCEL_USSD, response);

        if (RILJ_LOGD) riljLog(rr.serialString()
                + "> " + requestToString(rr.mRequest));

        send(rr);
    }


    @Override
    public void resetRadio(Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_RESET_RADIO, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    @Override
    public void invokeOemRilRequestRaw(byte[] data, Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_OEM_HOOK_RAW, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
               + "[" + IccUtils.bytesToHexString(data) + "]");

        rr.mParcel.writeByteArray(data);

        send(rr);

    }

    @Override
    public void invokeOemRilRequestStrings(String[] strings, Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_OEM_HOOK_STRINGS, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        rr.mParcel.writeStringArray(strings);

        send(rr);
    }

     /**
     * Assign a specified band for RF configuration.
     *
     * @param bandMode one of BM_*_BAND
     * @param response is callback message
     */
    @Override
    public void setBandMode (int bandMode, Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_SET_BAND_MODE, response);

        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(bandMode);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                 + " " + bandMode);

        send(rr);
     }

    /**
     * Query the list of band mode supported by RF.
     *
     * @param response is callback message
     *        ((AsyncResult)response.obj).result  is an int[] where int[0] is
     *        the size of the array and the rest of each element representing
     *        one available BM_*_BAND
     */
    @Override
    public void queryAvailableBandMode (Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_QUERY_AVAILABLE_BAND_MODE,
                response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void sendTerminalResponse(String contents, Message response) {
        RILRequest rr = RILRequest.obtain(
                RILConstants.RIL_REQUEST_STK_SEND_TERMINAL_RESPONSE, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        rr.mParcel.writeString(contents);
        send(rr);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void sendEnvelope(String contents, Message response) {
        RILRequest rr = RILRequest.obtain(
                RILConstants.RIL_REQUEST_STK_SEND_ENVELOPE_COMMAND, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        rr.mParcel.writeString(contents);
        send(rr);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void sendEnvelopeWithStatus(String contents, Message response) {
        RILRequest rr = RILRequest.obtain(
                RILConstants.RIL_REQUEST_STK_SEND_ENVELOPE_WITH_STATUS, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                + '[' + contents + ']');

        rr.mParcel.writeString(contents);
        send(rr);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void handleCallSetupRequestFromSim(
            boolean accept, int resCode, Message response) {

        RILRequest rr = RILRequest.obtain(
            RILConstants.RIL_REQUEST_STK_HANDLE_CALL_SETUP_REQUESTED_FROM_SIM,
            response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        int[] param = new int[1];
        if (resCode == 0x21 || resCode == 0x20) {
            param[0] = resCode;
        } else {
            param[0] = accept ? 1 : 0;
        }
        rr.mParcel.writeIntArray(param);
        send(rr);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setPreferredNetworkType(int networkType , Message response) {
        RILRequest rr = RILRequest.obtain(
                RILConstants.RIL_REQUEST_SET_PREFERRED_NETWORK_TYPE, response);

        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(networkType);

        mPreviousPreferredType = mPreferredNetworkType; //ALPS00799783
        mPreferredNetworkType = networkType;

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                + " : " + networkType);

        send(rr);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void getPreferredNetworkType(Message response) {
        RILRequest rr = RILRequest.obtain(
                RILConstants.RIL_REQUEST_GET_PREFERRED_NETWORK_TYPE, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void getNeighboringCids(Message response) {
        RILRequest rr = RILRequest.obtain(
                RILConstants.RIL_REQUEST_GET_NEIGHBORING_CELL_IDS, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setLocationUpdates(boolean enable, Message response) {
        //MTK-START [ALPS00093395]Consider screen on/off state
        PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        if ((pm.isScreenOn()) && (false == enable)) return;
        //MTK-END [ALPS00093395]Consider screen on/off state

        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_LOCATION_UPDATES, response);
        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(enable ? 1 : 0);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> "
                + requestToString(rr.mRequest) + ": " + enable);

        send(rr);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void getSmscAddress(Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_GET_SMSC_ADDRESS, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setSmscAddress(String address, Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_SMSC_ADDRESS, result);

        rr.mParcel.writeString(address);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                + " : " + address);

        send(rr);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void reportSmsMemoryStatus(boolean available, Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_REPORT_SMS_MEMORY_STATUS, result);
        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(available ? 1 : 0);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> "
                + requestToString(rr.mRequest) + ": " + available);

        send(rr);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void reportStkServiceIsRunning(Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_REPORT_STK_SERVICE_IS_RUNNING, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void getGsmBroadcastConfig(Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_GSM_GET_BROADCAST_CONFIG, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setGsmBroadcastConfig(SmsBroadcastConfigInfo[] config, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_GSM_SET_BROADCAST_CONFIG, response);

        int numOfConfig = config.length;
        rr.mParcel.writeInt(numOfConfig);

        for(int i = 0; i < numOfConfig; i++) {
            rr.mParcel.writeInt(config[i].getFromServiceId());
            rr.mParcel.writeInt(config[i].getToServiceId());
            rr.mParcel.writeInt(config[i].getFromCodeScheme());
            rr.mParcel.writeInt(config[i].getToCodeScheme());
            rr.mParcel.writeInt(config[i].isSelected() ? 1 : 0);
        }

        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                    + " with " + numOfConfig + " configs : ");
            for (int i = 0; i < numOfConfig; i++) {
                riljLog(config[i].toString());
            }
        }

        send(rr);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setGsmBroadcastActivation(boolean activate, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_GSM_BROADCAST_ACTIVATION, response);

        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(activate ? 0 : 1);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    //***** Private Methods

    // TODO(jeffbrown): Delete me.
    // The RIL should *not* be listening for screen state changes since they are
    // becoming increasingly ambiguous on our devices.  The RIL_REQUEST_SCREEN_STATE
    // message should be deleted and replaced with more precise messages to control
    // behavior such as signal strength reporting or power managements based on
    // more robust signals.
    private void updateScreenState() {
        final int oldState = mDefaultDisplayState;
        mDefaultDisplayState = mDefaultDisplay.getState();
        if (mDefaultDisplayState != oldState) {
            if (oldState != Display.STATE_ON
                    && mDefaultDisplayState == Display.STATE_ON) {
                sendScreenState(true);
            } else if ((oldState == Display.STATE_ON || oldState == Display.STATE_UNKNOWN)
                        && mDefaultDisplayState != Display.STATE_ON) {
                sendScreenState(false);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    public void sendScreenState(boolean on) {
        //MTK-START [mtk04070][111207][ALPS00093395]Store screen on/off state
        isScreenOn = on;
        //MTK-END [mtk04070][111207][ALPS00093395]Store screen on/off state
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SCREEN_STATE, null);
        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(on ? 1 : 0);

        if (RILJ_LOGD) riljLog(rr.serialString()
                + "> " + requestToString(rr.mRequest) + ": " + on);

        send(rr);
    }

    @Override
    protected void
    onRadioAvailable() {
        // In case screen state was lost (due to process crash),
        // this ensures that the RIL knows the correct screen state.
        updateScreenState();
   }

    private RadioState getRadioStateFromInt(int stateInt) {
        RadioState state;

        /* RIL_RadioState ril.h */
        switch(stateInt) {
            case 0: state = RadioState.RADIO_OFF; break;
            case 1: state = RadioState.RADIO_UNAVAILABLE; break;
            case 10: state = RadioState.RADIO_ON; break;

            default:
                throw new RuntimeException(
                            "Unrecognized RIL_RadioState: " + stateInt);
        }
        return state;
    }

    private void switchToRadioState(RadioState newState) {
        setRadioState(newState);
    }

    /**
     * Holds a PARTIAL_WAKE_LOCK whenever
     * a) There is outstanding RIL request sent to RIL deamon and no replied
     * b) There is a request pending to be sent out.
     *
     * There is a WAKE_LOCK_TIMEOUT to release the lock, though it shouldn't
     * happen often.
     */

    private void
    acquireWakeLock() {
        synchronized (mWakeLock) {
            mWakeLock.acquire();
            mWakeLockCount++;

            mSender.removeMessages(EVENT_WAKE_LOCK_TIMEOUT);
            Message msg = mSender.obtainMessage(EVENT_WAKE_LOCK_TIMEOUT);
            mSender.sendMessageDelayed(msg, mWakeLockTimeout);
        }
    }

    private void
    decrementWakeLock() {
        synchronized (mWakeLock) {
            if (mWakeLockCount > 1) {
                mWakeLockCount--;
            } else {
                mWakeLockCount = 0;
                mWakeLock.release();
                mSender.removeMessages(EVENT_WAKE_LOCK_TIMEOUT);
            }
        }
    }

    // true if we had the wakelock
    private boolean
    clearWakeLock() {
        synchronized (mWakeLock) {
            if (mWakeLockCount == 0 && mWakeLock.isHeld() == false) return false;
            Rlog.d(RILJ_LOG_TAG, "NOTE: mWakeLockCount is " + mWakeLockCount + "at time of clearing");
            mWakeLockCount = 0;
            mWakeLock.release();
            mSender.removeMessages(EVENT_WAKE_LOCK_TIMEOUT);
            return true;
        }
    }

    private void
    send(RILRequest rr) {
        Message msg;

        if (mSocket == null) {
            rr.onError(RADIO_NOT_AVAILABLE, null);
            rr.release();
            return;
        }

        msg = mSender.obtainMessage(EVENT_SEND, rr);

        acquireWakeLock();

        msg.sendToTarget();
    }

    private void
    processResponse (Parcel p) {
        int type;

        type = p.readInt();

        if (type == RESPONSE_UNSOLICITED) {
            processUnsolicited (p);
        } else if (type == RESPONSE_SOLICITED) {
            RILRequest rr = processSolicited (p);
            if (rr != null) {
                rr.release();
                decrementWakeLock();
            }
        }
    }

    /**
     * Release each request in mRequestList then clear the list
     * @param error is the RIL_Errno sent back
     * @param loggable true means to print all requests in mRequestList
     */
    private void clearRequestList(int error, boolean loggable) {
        RILRequest rr;
        synchronized (mRequestList) {
            int count = mRequestList.size();
            if (RILJ_LOGD && loggable) {
                Rlog.d(RILJ_LOG_TAG, "clearRequestList " +
                        " mWakeLockCount=" + mWakeLockCount +
                        " mRequestList=" + count);
            }

            for (int i = 0; i < count ; i++) {
                rr = mRequestList.valueAt(i);
                if (RILJ_LOGD && loggable) {
                    Rlog.d(RILJ_LOG_TAG, i + ": [" + rr.mSerial + "] " +
                            requestToString(rr.mRequest));
                }
                rr.onError(error, null);
                rr.release();
                decrementWakeLock();
            }
            mRequestList.clear();
        }
    }

    private RILRequest findAndRemoveRequestFromList(int serial) {
        RILRequest rr = null;
        synchronized (mRequestList) {
            rr = mRequestList.get(serial);
            if (rr != null) {
                mRequestList.remove(serial);
            }
        }

        return rr;
    }

    private RILRequest
    processSolicited (Parcel p) {
        int serial, error;
        boolean found = false;

        serial = p.readInt();
        error = p.readInt();

        RILRequest rr;

        rr = findAndRemoveRequestFromList(serial);

        if (rr == null) {
            Rlog.w(RILJ_LOG_TAG, "Unexpected solicited response! sn: "
                            + serial + " error: " + error);
            return null;
        }

        /* M: call control part start */
        /* DTMF request will be ignored when the count of requests reaches 32 */
        if ((rr.mRequest == RIL_REQUEST_DTMF_START) ||
            (rr.mRequest == RIL_REQUEST_DTMF_STOP)) {
            synchronized (mDtmfReqQueue) {
                mDtmfReqQueue.remove(rr);
                riljLog("remove first item in dtmf queue done, size = " + mDtmfReqQueue.size());
                if (mDtmfReqQueue.size() > 0) {
                    RILRequest rr2 = mDtmfReqQueue.get();
                    if (RILJ_LOGD) riljLog(rr2.serialString() + "> " + requestToString(rr2.mRequest));
                    send(rr2);
                } else {
                    if (mDtmfReqQueue.getPendingRequest() != null) {
                        riljLog("send pending switch request");
                        send(mDtmfReqQueue.getPendingRequest());
                        mDtmfReqQueue.setSendChldRequest();
                        mDtmfReqQueue.setPendingRequest(null);
                    }
                }
            }
        }
        /* M: call control part end */
        Object ret = null;

        if ((rr.mRequest == RIL_REQUEST_QUERY_AVAILABLE_NETWORKS) ||
            (rr.mRequest == RIL_REQUEST_QUERY_AVAILABLE_NETWORKS_WITH_ACT)) {
            mGetAvailableNetworkDoneRegistrant.notifyRegistrants();
        }

        /* ALPS00799783 START */
        if (rr.mRequest == RIL_REQUEST_SET_PREFERRED_NETWORK_TYPE) {
            if ((error != 0) && (mPreviousPreferredType != -1)) {
                riljLog("restore mPreferredNetworkType from " + mPreferredNetworkType + " to " + mPreviousPreferredType);
                mPreferredNetworkType = mPreviousPreferredType;
            }
            mPreviousPreferredType = -1; //reset
        }
        /* ALPS00799783 END */


        /* M: call control part start */
        if (rr.mRequest == RIL_REQUEST_SWITCH_WAITING_OR_HOLDING_AND_ACTIVE ||
            rr.mRequest == RIL_REQUEST_CONFERENCE ||
            rr.mRequest == RIL_REQUEST_SEPARATE_CONNECTION ||
            rr.mRequest == RIL_REQUEST_EXPLICIT_CALL_TRANSFER) {
            riljLog("clear mIsSendChldRequest");
            mDtmfReqQueue.resetSendChldRequest();
        }
        /* M: call control part end */

        if (error == 0 || p.dataAvail() > 0) {
            // either command succeeds or command fails but with data payload
            try {switch (rr.mRequest) {
            /*
 cat libs/telephony/ril_commands.h \
 | egrep "^ *{RIL_" \
 | sed -re 's/\{([^,]+),[^,]+,([^}]+).+/case \1: ret = \2(p); break;/'
             */
            case RIL_REQUEST_GET_SIM_STATUS: ret =  responseIccCardStatus(p); break;
            case RIL_REQUEST_ENTER_SIM_PIN: ret =  responseInts(p); break;
            case RIL_REQUEST_ENTER_SIM_PUK: ret =  responseInts(p); break;
            case RIL_REQUEST_ENTER_SIM_PIN2: ret =  responseInts(p); break;
            case RIL_REQUEST_ENTER_SIM_PUK2: ret =  responseInts(p); break;
            case RIL_REQUEST_CHANGE_SIM_PIN: ret =  responseInts(p); break;
            case RIL_REQUEST_CHANGE_SIM_PIN2: ret =  responseInts(p); break;
            case RIL_REQUEST_ENTER_NETWORK_DEPERSONALIZATION: ret =  responseInts(p); break;
            case RIL_REQUEST_GET_CURRENT_CALLS: ret =  responseCallList(p); break;
            case RIL_REQUEST_DIAL: ret =  responseVoid(p); break;
            case RIL_REQUEST_GET_IMSI: ret =  responseString(p); break;
            case RIL_REQUEST_HANGUP: ret =  responseVoid(p); break;
            case RIL_REQUEST_HANGUP_WAITING_OR_BACKGROUND: ret =  responseVoid(p); break;
            case RIL_REQUEST_HANGUP_FOREGROUND_RESUME_BACKGROUND: {
                if (mTestingEmergencyCall.getAndSet(false)) {
                    if (mEmergencyCallbackModeRegistrant != null) {
                        riljLog("testing emergency call, notify ECM Registrants");
                        mEmergencyCallbackModeRegistrant.notifyRegistrant();
                    }
                }
                ret =  responseVoid(p);
                break;
            }
            case RIL_REQUEST_SWITCH_WAITING_OR_HOLDING_AND_ACTIVE: ret =  responseVoid(p); break;
            case RIL_REQUEST_CONFERENCE: ret =  responseVoid(p); break;
            case RIL_REQUEST_UDUB: ret =  responseVoid(p); break;
            case RIL_REQUEST_LAST_CALL_FAIL_CAUSE: ret =  responseInts(p); break;
            case RIL_REQUEST_SIGNAL_STRENGTH: ret =  responseSignalStrength(p); break;
            case RIL_REQUEST_VOICE_REGISTRATION_STATE: ret =  responseStrings(p); break;
            case RIL_REQUEST_DATA_REGISTRATION_STATE: ret =  responseStrings(p); break;
            case RIL_REQUEST_OPERATOR: ret =  responseOperator(p); break;
            case RIL_REQUEST_RADIO_POWER: ret =  responseVoid(p); break;
            case RIL_REQUEST_DTMF: ret =  responseVoid(p); break;
            case RIL_REQUEST_SEND_SMS: ret =  responseSMS(p); break;
            case RIL_REQUEST_SEND_SMS_EXPECT_MORE: ret =  responseSMS(p); break;
            case RIL_REQUEST_SETUP_DATA_CALL: ret =  responseSetupDataCall(p); break;
            case RIL_REQUEST_SIM_IO: ret =  responseICC_IO(p); break;
            case RIL_REQUEST_SEND_USSD: ret =  responseVoid(p); break;
            case RIL_REQUEST_CANCEL_USSD: ret =  responseVoid(p); break;
            case RIL_REQUEST_GET_CLIR: ret =  responseInts(p); break;
            case RIL_REQUEST_SET_CLIR: ret =  responseVoid(p); break;
            case RIL_REQUEST_QUERY_CALL_FORWARD_STATUS: ret =  responseCallForward(p); break;
            case RIL_REQUEST_SET_CALL_FORWARD: ret =  responseVoid(p); break;
            case RIL_REQUEST_QUERY_CALL_WAITING: ret =  responseInts(p); break;
            case RIL_REQUEST_SET_CALL_WAITING: ret =  responseVoid(p); break;
            case RIL_REQUEST_SMS_ACKNOWLEDGE: ret =  responseVoid(p); break;
            case RIL_REQUEST_GET_IMEI: ret =  responseString(p); break;
            case RIL_REQUEST_GET_IMEISV: ret =  responseString(p); break;
            case RIL_REQUEST_ANSWER: ret =  responseVoid(p); break;
            case RIL_REQUEST_DEACTIVATE_DATA_CALL: ret = responseDeactivateDataCall(p); break; //VoLTE
            case RIL_REQUEST_QUERY_FACILITY_LOCK: ret =  responseInts(p); break;
            case RIL_REQUEST_SET_FACILITY_LOCK: ret =  responseInts(p); break;
            case RIL_REQUEST_CHANGE_BARRING_PASSWORD: ret =  responseVoid(p); break;
            case RIL_REQUEST_QUERY_NETWORK_SELECTION_MODE: ret =  responseInts(p); break;
            case RIL_REQUEST_SET_NETWORK_SELECTION_AUTOMATIC: ret =  responseVoid(p); break;
            case RIL_REQUEST_SET_NETWORK_SELECTION_MANUAL: ret =  responseVoid(p); break;
            case RIL_REQUEST_QUERY_AVAILABLE_NETWORKS : ret =  responseOperatorInfos(p); break;
            case RIL_REQUEST_ABORT_QUERY_AVAILABLE_NETWORKS: ret =  responseVoid(p); break;
            case RIL_REQUEST_DTMF_START: ret =  responseVoid(p); break;
            case RIL_REQUEST_DTMF_STOP: ret =  responseVoid(p); break;
            case RIL_REQUEST_BASEBAND_VERSION: ret =  responseString(p); break;
            case RIL_REQUEST_SEPARATE_CONNECTION: ret =  responseVoid(p); break;
            case RIL_REQUEST_SET_MUTE: ret =  responseVoid(p); break;
            case RIL_REQUEST_GET_MUTE: ret =  responseInts(p); break;
            case RIL_REQUEST_QUERY_CLIP: ret =  responseInts(p); break;
            case RIL_REQUEST_LAST_DATA_CALL_FAIL_CAUSE: ret =  responseInts(p); break;
            case RIL_REQUEST_DATA_CALL_LIST: ret =  responseDataCallList(p); break;
            case RIL_REQUEST_RESET_RADIO: ret =  responseVoid(p); break;
            case RIL_REQUEST_OEM_HOOK_RAW: ret =  responseRaw(p); break;
            case RIL_REQUEST_OEM_HOOK_STRINGS: ret =  responseStrings(p); break;
            case RIL_REQUEST_SCREEN_STATE: ret =  responseVoid(p); break;
            case RIL_REQUEST_SET_SUPP_SVC_NOTIFICATION: ret =  responseVoid(p); break;
            case RIL_REQUEST_WRITE_SMS_TO_SIM: ret =  responseInts(p); break;
            case RIL_REQUEST_DELETE_SMS_ON_SIM: ret =  responseVoid(p); break;
            case RIL_REQUEST_SET_BAND_MODE: ret =  responseVoid(p); break;
            case RIL_REQUEST_QUERY_AVAILABLE_BAND_MODE: ret =  responseInts(p); break;
            case RIL_REQUEST_STK_GET_PROFILE: ret =  responseString(p); break;
            case RIL_REQUEST_STK_SET_PROFILE: ret =  responseVoid(p); break;
            case RIL_REQUEST_STK_SEND_ENVELOPE_COMMAND: ret =  responseString(p); break;
            case RIL_REQUEST_STK_SEND_TERMINAL_RESPONSE: ret =  responseVoid(p); break;
            case RIL_REQUEST_STK_HANDLE_CALL_SETUP_REQUESTED_FROM_SIM: ret =  responseInts(p); break;
            case RIL_REQUEST_EXPLICIT_CALL_TRANSFER: ret =  responseVoid(p); break;
            case RIL_REQUEST_SET_PREFERRED_NETWORK_TYPE: ret =  responseVoid(p); break;
            case RIL_REQUEST_GET_PREFERRED_NETWORK_TYPE: ret =  responseGetPreferredNetworkType(p); break;
            case RIL_REQUEST_GET_NEIGHBORING_CELL_IDS: ret = responseCellList(p); break;
            case RIL_REQUEST_SET_LOCATION_UPDATES: ret =  responseVoid(p); break;
            case RIL_REQUEST_CDMA_SET_SUBSCRIPTION_SOURCE: ret =  responseVoid(p); break;
            case RIL_REQUEST_CDMA_SET_ROAMING_PREFERENCE: ret =  responseVoid(p); break;
            case RIL_REQUEST_CDMA_QUERY_ROAMING_PREFERENCE: ret =  responseInts(p); break;
            case RIL_REQUEST_SET_TTY_MODE: ret =  responseVoid(p); break;
            case RIL_REQUEST_QUERY_TTY_MODE: ret =  responseInts(p); break;
            case RIL_REQUEST_CDMA_SET_PREFERRED_VOICE_PRIVACY_MODE: ret =  responseVoid(p); break;
            case RIL_REQUEST_CDMA_QUERY_PREFERRED_VOICE_PRIVACY_MODE: ret =  responseInts(p); break;
            case RIL_REQUEST_CDMA_FLASH: ret =  responseVoid(p); break;
            case RIL_REQUEST_CDMA_BURST_DTMF: ret =  responseVoid(p); break;
            case RIL_REQUEST_CDMA_SEND_SMS: ret =  responseSMS(p); break;
            case RIL_REQUEST_CDMA_SMS_ACKNOWLEDGE: ret =  responseVoid(p); break;
            case RIL_REQUEST_GSM_GET_BROADCAST_CONFIG: ret =  responseGmsBroadcastConfig(p); break;
            case RIL_REQUEST_GSM_SET_BROADCAST_CONFIG: ret =  responseVoid(p); break;
            case RIL_REQUEST_GSM_BROADCAST_ACTIVATION: ret =  responseVoid(p); break;
            case RIL_REQUEST_CDMA_GET_BROADCAST_CONFIG: ret =  responseCdmaBroadcastConfig(p); break;
            case RIL_REQUEST_CDMA_SET_BROADCAST_CONFIG: ret =  responseVoid(p); break;
            case RIL_REQUEST_CDMA_BROADCAST_ACTIVATION: ret =  responseVoid(p); break;
            case RIL_REQUEST_CDMA_VALIDATE_AND_WRITE_AKEY: ret =  responseVoid(p); break;
            case RIL_REQUEST_CDMA_SUBSCRIPTION: ret =  responseStrings(p); break;
            case RIL_REQUEST_CDMA_WRITE_SMS_TO_RUIM: ret =  responseInts(p); break;
            case RIL_REQUEST_CDMA_DELETE_SMS_ON_RUIM: ret =  responseVoid(p); break;
            case RIL_REQUEST_DEVICE_IDENTITY: ret =  responseStrings(p); break;
            case RIL_REQUEST_GET_SMSC_ADDRESS: ret = responseString(p); break;
            case RIL_REQUEST_SET_SMSC_ADDRESS: ret = responseVoid(p); break;
            case RIL_REQUEST_EXIT_EMERGENCY_CALLBACK_MODE: ret = responseVoid(p); break;
            case RIL_REQUEST_REPORT_SMS_MEMORY_STATUS: ret = responseVoid(p); break;
            case RIL_REQUEST_REPORT_STK_SERVICE_IS_RUNNING: ret = responseVoid(p); break;
            case RIL_REQUEST_CDMA_GET_SUBSCRIPTION_SOURCE: ret =  responseInts(p); break;
            case RIL_REQUEST_ISIM_AUTHENTICATION: ret =  responseString(p); break;
            case RIL_REQUEST_ACKNOWLEDGE_INCOMING_GSM_SMS_WITH_PDU: ret = responseVoid(p); break;
            case RIL_REQUEST_STK_SEND_ENVELOPE_WITH_STATUS: ret = responseICC_IO(p); break;
            case RIL_REQUEST_VOICE_RADIO_TECH: ret = responseInts(p); break;
            case RIL_REQUEST_GET_CELL_INFO_LIST: ret = responseCellInfoList(p); break;
            case RIL_REQUEST_SET_UNSOL_CELL_INFO_LIST_RATE: ret = responseVoid(p); break;
            case RIL_REQUEST_SET_INITIAL_ATTACH_APN: ret = responseVoid(p); break;
            case RIL_REQUEST_SET_DATA_PROFILE: ret = responseVoid(p); break;
            case RIL_REQUEST_IMS_REGISTRATION_STATE: ret = responseInts(p); break;
            case RIL_REQUEST_IMS_SEND_SMS: ret =  responseSMS(p); break;
            case RIL_REQUEST_SIM_TRANSMIT_APDU_BASIC: ret =  responseICC_IO(p); break;
            case RIL_REQUEST_SIM_OPEN_CHANNEL: ret  = responseInts(p); break;
            case RIL_REQUEST_SIM_CLOSE_CHANNEL: ret  = responseVoid(p); break;
            case RIL_REQUEST_SIM_TRANSMIT_APDU_CHANNEL: ret = responseICC_IO(p); break;
            case RIL_REQUEST_NV_READ_ITEM: ret = responseString(p); break;
            case RIL_REQUEST_NV_WRITE_ITEM: ret = responseVoid(p); break;
            case RIL_REQUEST_NV_WRITE_CDMA_PRL: ret = responseVoid(p); break;
            case RIL_REQUEST_NV_RESET_CONFIG: ret = responseVoid(p); break;
            case RIL_REQUEST_SET_UICC_SUBSCRIPTION: ret = responseVoid(p); break;
            case RIL_REQUEST_ALLOW_DATA: ret = responseVoid(p); break;
            case RIL_REQUEST_GET_HARDWARE_CONFIG: ret = responseHardwareConfig(p); break;
            case RIL_REQUEST_SIM_AUTHENTICATION: ret =  responseICC_IOBase64(p); break;
            case RIL_REQUEST_SHUTDOWN: ret = responseVoid(p); break;
            case RIL_REQUEST_GET_PHONE_RAT_FAMILY: ret =  responseInts(p); break;
            case RIL_REQUEST_SET_PHONE_RAT_FAMILY: ret =  responsePhoneId(); break;

            /* M: call control part start */
            case RIL_REQUEST_HANGUP_ALL: ret =  responseVoid(p); break;
            case RIL_REQUEST_FORCE_RELEASE_CALL: ret = responseVoid(p); break;
            case RIL_REQUEST_SET_CALL_INDICATION: ret = responseVoid(p); break;
            case RIL_REQUEST_EMERGENCY_DIAL: ret =  responseVoid(p); break;
            case RIL_REQUEST_SET_ECC_SERVICE_CATEGORY: ret = responseVoid(p); break;
            case RIL_REQUEST_SET_ECC_LIST: ret = responseVoid(p); break;
            case RIL_REQUEST_SET_SPEECH_CODEC_INFO: ret = responseVoid(p); break;
            /* M: call control part end */

            /// M: IMS feature. @{
            case RIL_REQUEST_ADD_IMS_CONFERENCE_CALL_MEMBER: responseString(p); break;
            case RIL_REQUEST_REMOVE_IMS_CONFERENCE_CALL_MEMBER: responseString(p); break;
            case RIL_REQUEST_DIAL_WITH_SIP_URI: ret = responseVoid(p); break;
            case RIL_REQUEST_RETRIEVE_HELD_CALL: ret = responseVoid(p); break;
            /// @}

            //MTK-START SIM ME lock
            case RIL_REQUEST_QUERY_SIM_NETWORK_LOCK: ret =  responseInts(p); break;
            case RIL_REQUEST_SET_SIM_NETWORK_LOCK: ret =  responseInts(p); break;
            //MTK-END SIM ME lock
            //MTK-START multiple application support
            case RIL_REQUEST_GENERAL_SIM_AUTH: ret =  responseICC_IO(p); break;
            case RIL_REQUEST_OPEN_ICC_APPLICATION: ret = responseInts(p); break;
            case RIL_REQUEST_GET_ICC_APPLICATION_STATUS: ret = responseIccCardStatus(p); break;
            //MTK-END multiple application support
            case RIL_REQUEST_SIM_IO_EX: ret =  responseICC_IO(p); break;
            // PHB Start
            case RIL_REQUEST_QUERY_PHB_STORAGE_INFO: ret = responseInts(p); break;
            case RIL_REQUEST_WRITE_PHB_ENTRY: ret = responseVoid(p); break;
            case RIL_REQUEST_READ_PHB_ENTRY: ret = responsePhbEntries(p); break;
            case RIL_REQUEST_QUERY_UPB_CAPABILITY: ret = responseInts(p); break;
            case RIL_REQUEST_READ_UPB_GRP: ret = responseInts(p); break;
            case RIL_REQUEST_WRITE_UPB_GRP: ret = responseVoid(p); break;
            case RIL_REQUEST_EDIT_UPB_ENTRY: ret = responseVoid(p); break;
            case RIL_REQUEST_DELETE_UPB_ENTRY: ret = responseVoid(p); break;
            case RIL_REQUEST_READ_UPB_GAS_LIST: ret = responseStrings(p); break;
            case RIL_REQUEST_GET_PHB_STRING_LENGTH: ret = responseInts(p); break;
            case RIL_REQUEST_GET_PHB_MEM_STORAGE : ret = responseGetPhbMemStorage(p); break;
            case RIL_REQUEST_SET_PHB_MEM_STORAGE : responseVoid(p); break;
            case RIL_REQUEST_READ_PHB_ENTRY_EXT: ret = responseReadPhbEntryExt(p); break;
            case RIL_REQUEST_WRITE_PHB_ENTRY_EXT: ret = responseVoid(p); break;
            // PHB End


            /* M: network part start */
            case RIL_REQUEST_SET_NETWORK_SELECTION_MANUAL_WITH_ACT: ret =  responseVoid(p); break;
            case RIL_REQUEST_GET_POL_CAPABILITY: ret = responseInts(p); break;
            case RIL_REQUEST_GET_POL_LIST: ret = responseNetworkInfoWithActs(p); break;
            case RIL_REQUEST_SET_POL_ENTRY: ret = responseVoid(p); break;
            case RIL_REQUEST_SET_TRM: ret = responseInts(p); break;
            case RIL_REQUEST_QUERY_AVAILABLE_NETWORKS_WITH_ACT : ret =  responseOperatorInfosWithAct(p); break;
            case RIL_REQUEST_STK_EVDL_CALL_BY_AP: ret = responseVoid(p); break;

            //Femtocell (CSG) feature START
            case RIL_REQUEST_GET_FEMTOCELL_LIST: ret = responseFemtoCellInfos(p); break;
            case RIL_REQUEST_ABORT_FEMTOCELL_LIST: ret = responseVoid(p); break;
            case RIL_REQUEST_SELECT_FEMTOCELL: ret = responseVoid(p); break;
            //Femtocell (CSG) feature END
            /* M: network part end */

            case RIL_REQUEST_QUERY_MODEM_TYPE: ret = responseInts(p); break;
            case RIL_REQUEST_STORE_MODEM_TYPE: ret = responseVoid(p); break;

            // IMS
            case RIL_REQUEST_SET_IMS_ENABLE: ret = responseVoid(p); break;
            case RIL_REQUEST_SIM_GET_ATR: ret = responseString(p); break;
            // M: Fast Dormancy
            case RIL_REQUEST_SET_SCRI: ret = responseVoid(p); break;
            case RIL_REQUEST_SET_FD_MODE: ret = responseInts(p); break;

            // MTK-START, SMS part
            case RIL_REQUEST_GET_SMS_PARAMS: ret = responseSmsParams(p); break;
            case RIL_REQUEST_SET_SMS_PARAMS: ret = responseVoid(p); break;
            case RIL_REQUEST_GET_SMS_SIM_MEM_STATUS: ret = responseSimSmsMemoryStatus(p); break;
            case RIL_REQUEST_SET_ETWS: ret = responseVoid(p); break;
            case RIL_REQUEST_SET_CB_CHANNEL_CONFIG_INFO: ret = responseVoid(p); break;
            case RIL_REQUEST_SET_CB_LANGUAGE_CONFIG_INFO: ret = responseVoid(p); break;
            case RIL_REQUEST_GET_CB_CONFIG_INFO: ret = responseCbConfig(p); break;
            case RIL_REQUEST_REMOVE_CB_MESSAGE: ret = responseVoid(p); break;
            // MTK-END, SMS part
            case RIL_REQUEST_SET_DATA_CENTRIC: ret = responseVoid(p); break;

            //VoLTE
            case RIL_REQUEST_SETUP_DEDICATE_DATA_CALL: ret = responseSetupDedicateDataCall(p); break;
            case RIL_REQUEST_DEACTIVATE_DEDICATE_DATA_CALL: ret = responseVoid(p); break;
            case RIL_REQUEST_MODIFY_DATA_CALL: ret = responseModifyDataCall(p); break;
            case RIL_REQUEST_ABORT_SETUP_DATA_CALL: ret = responseVoid(p); break;
            case RIL_REQUEST_PCSCF_DISCOVERY_PCO: ret=responsePcscfDiscovery(p); break;
            case RIL_REQUEST_CLEAR_DATA_BEARER: ret=responseVoid(p); break;
            // M: CC33 LTE.
            case RIL_REQUEST_SET_DATA_ON_TO_MD: ret = responseVoid(p); break;
            case RIL_REQUEST_SET_REMOVE_RESTRICT_EUTRAN_MODE: ret = responseVoid(p); break;

            case RIL_REQUEST_BTSIM_CONNECT: ret = responseString(p); break;
            case RIL_REQUEST_BTSIM_DISCONNECT_OR_POWEROFF: ret = responseVoid(p); break;
            case RIL_REQUEST_BTSIM_POWERON_OR_RESETSIM: ret = responseString(p); break;
            case RIL_REQUEST_BTSIM_TRANSFERAPDU: ret = responseString(p); break;

            /* M: call control part start */
            case RIL_REQUEST_SET_IMS_CALL_STATUS: ret = responseVoid(p); break;
            /* M: call control part end */
 
            /* M: C2K part start */
            case RIL_REQUEST_GET_NITZ_TIME: ret = responseGetNitzTime(p); break;
            case RIL_REQUEST_QUERY_UIM_INSERTED: ret = responseInts(p); break;
            case RIL_REQUEST_SWITCH_HPF: ret = responseVoid(p); break;
            case RIL_REQUEST_SET_AVOID_SYS: ret = responseVoid(p); break;
            case RIL_REQUEST_QUERY_AVOID_SYS: ret = responseInts(p); break;
            case RIL_REQUEST_QUERY_CDMA_NETWORK_INFO: ret = responseStrings(p); break;
            case RIL_REQUEST_GET_LOCAL_INFO: ret =  responseInts(p); break;
            case RIL_REQUEST_UTK_REFRESH: ret =  responseVoid(p); break;
            case RIL_REQUEST_QUERY_SMS_AND_PHONEBOOK_STATUS: ret = responseInts(p); break;
            case RIL_REQUEST_QUERY_NETWORK_REGISTRATION: ret = responseInts(p); break;
            case RIL_REQUEST_AGPS_TCP_CONNIND: ret = responseVoid(p); break;
            case RIL_REQUEST_AGPS_SET_MPC_IPPORT: ret = responseVoid(p); break;
            case RIL_REQUEST_AGPS_GET_MPC_IPPORT: ret = responseStrings(p); break;
            case RIL_REQUEST_SET_MEID: ret = responseVoid(p); break;
            case RIL_REQUEST_SET_REG_RESUME: ret =  responseVoid(p); break;
            case RIL_REQUEST_ENABLE_REG_PAUSE: ret =  responseVoid(p); break;
            case RIL_REQUEST_SET_ETS_DEV: ret =  responseVoid(p); break;
            case RIL_REQUEST_WRITE_MDN: ret =  responseVoid(p); break;
            case RIL_REQUEST_SET_VIA_TRM: ret = responseVoid(p); break;
            case RIL_REQUEST_SET_ARSI_THRESHOLD: ret =  responseVoid(p); break;
            /* M: C2K part end */
            default:
                throw new RuntimeException("Unrecognized solicited response: " + rr.mRequest);
            //break;
            }} catch (Throwable tr) {
                // Exceptions here usually mean invalid RIL responses

                Rlog.w(RILJ_LOG_TAG, rr.serialString() + "< "
                        + requestToString(rr.mRequest)
                        + " exception, possible invalid RIL response", tr);

                if (rr.mResult != null) {
                    AsyncResult.forMessage(rr.mResult, null, tr);
                    rr.mResult.sendToTarget();
                }
                return rr;
            }
        }

        if (rr.mRequest == RIL_REQUEST_SHUTDOWN) {
            // Set RADIO_STATE to RADIO_UNAVAILABLE to continue shutdown process
            // regardless of error code to continue shutdown procedure.
            riljLog("Response to RIL_REQUEST_SHUTDOWN received. Error is " +
                    error + " Setting Radio State to Unavailable regardless of error.");
            setRadioState(RadioState.RADIO_UNAVAILABLE);
        }

        // Here and below fake RIL_UNSOL_RESPONSE_SIM_STATUS_CHANGED, see b/7255789.
        // This is needed otherwise we don't automatically transition to the main lock
        // screen when the pin or puk is entered incorrectly.
        switch (rr.mRequest) {
            case RIL_REQUEST_ENTER_SIM_PUK:
            case RIL_REQUEST_ENTER_SIM_PUK2:
                if (mIccStatusChangedRegistrants != null) {
                    if (RILJ_LOGD) {
                        riljLog("ON enter sim puk fakeSimStatusChanged: reg count="
                                + mIccStatusChangedRegistrants.size());
                    }
                    mIccStatusChangedRegistrants.notifyRegistrants();
                }
                break;
        }

        if (error != 0) {
            switch (rr.mRequest) {
                case RIL_REQUEST_ENTER_SIM_PIN:
                case RIL_REQUEST_ENTER_SIM_PIN2:
                case RIL_REQUEST_CHANGE_SIM_PIN:
                case RIL_REQUEST_CHANGE_SIM_PIN2:
                case RIL_REQUEST_SET_FACILITY_LOCK:
                    if (mIccStatusChangedRegistrants != null) {
                        if (RILJ_LOGD) {
                            riljLog("ON some errors fakeSimStatusChanged: reg count="
                                    + mIccStatusChangedRegistrants.size());
                        }
                        mIccStatusChangedRegistrants.notifyRegistrants();
                    }
                    break;

                case RIL_REQUEST_SET_PHONE_RAT_FAMILY:
                    if (RILJ_LOGD) {
                        riljLog("Set phone RAT failed, send response with phone ID");
                    }
                    ret = responsePhoneId();
                    break;
            }

            rr.onError(error, ret);
        } else {

            if (RILJ_LOGD) riljLog(rr.serialString() + "< " + requestToString(rr.mRequest)
                    + " " + retToString(rr.mRequest, ret));

            if (rr.mResult != null) {
                AsyncResult.forMessage(rr.mResult, ret, null);
                rr.mResult.sendToTarget();
            }
        }
        return rr;
    }

    static String
    retToString(int req, Object ret) {
        if (ret == null) return "";
        switch (req) {
            // Don't log these return values, for privacy's sake.
            case RIL_REQUEST_GET_IMSI:
            case RIL_REQUEST_GET_IMEI:
            case RIL_REQUEST_GET_IMEISV:
            case RIL_REQUEST_SIM_OPEN_CHANNEL:
            case RIL_REQUEST_SIM_TRANSMIT_APDU_CHANNEL:

                if (!RILJ_LOGV) {
                    // If not versbose logging just return and don't display IMSI and IMEI, IMEISV
                    return "";
                }
        }

        StringBuilder sb;
        String s;
        int length;
        if (ret instanceof int[]){
            int[] intArray = (int[]) ret;
            length = intArray.length;
            sb = new StringBuilder("{");
            if (length > 0) {
                int i = 0;
                sb.append(intArray[i++]);
                while ( i < length) {
                    sb.append(", ").append(intArray[i++]);
                }
            }
            sb.append("}");
            s = sb.toString();
        } else if (ret instanceof String[]) {
            String[] strings = (String[]) ret;
            length = strings.length;
            sb = new StringBuilder("{");
            if (length > 0) {
                int i = 0;
                sb.append(strings[i++]);
                while ( i < length) {
                    sb.append(", ").append(strings[i++]);
                }
            }
            sb.append("}");
            s = sb.toString();
        }else if (req == RIL_REQUEST_GET_CURRENT_CALLS) {
            ArrayList<DriverCall> calls = (ArrayList<DriverCall>) ret;
            sb = new StringBuilder(" ");
            for (DriverCall dc : calls) {
                sb.append("[").append(dc).append("] ");
            }
            s = sb.toString();
        } else if (req == RIL_REQUEST_GET_NEIGHBORING_CELL_IDS) {
            ArrayList<NeighboringCellInfo> cells;
            cells = (ArrayList<NeighboringCellInfo>) ret;
            sb = new StringBuilder(" ");
            for (NeighboringCellInfo cell : cells) {
                sb.append(cell).append(" ");
            }
            s = sb.toString();
        } else if (req == RIL_REQUEST_GET_HARDWARE_CONFIG) {
            ArrayList<HardwareConfig> hwcfgs = (ArrayList<HardwareConfig>) ret;
            sb = new StringBuilder(" ");
            for (HardwareConfig hwcfg : hwcfgs) {
                sb.append("[").append(hwcfg).append("] ");
            }
            s = sb.toString();
        } else {
            s = ret.toString();
        }
        return s;
    }

    private void
    processUnsolicited (Parcel p) {
        int response;
        Object ret;

        response = p.readInt();

        try {switch(response) {
/*
 cat libs/telephony/ril_unsol_commands.h \
 | egrep "^ *{RIL_" \
 | sed -re 's/\{([^,]+),[^,]+,([^}]+).+/case \1: \2(rr, p); break;/'
*/

            case RIL_UNSOL_RESPONSE_RADIO_STATE_CHANGED: ret =  responseVoid(p); break;
            case RIL_UNSOL_RESPONSE_CALL_STATE_CHANGED: ret =  responseVoid(p); break;
            case RIL_UNSOL_RESPONSE_VOICE_NETWORK_STATE_CHANGED: ret =  responseStrings(p); break;
            case RIL_UNSOL_RESPONSE_NEW_SMS: ret =  responseString(p); break;
            case RIL_UNSOL_RESPONSE_NEW_SMS_STATUS_REPORT: ret =  responseString(p); break;
            case RIL_UNSOL_RESPONSE_NEW_SMS_ON_SIM: ret =  responseInts(p); break;
            case RIL_UNSOL_ON_USSD: ret =  responseStrings(p); break;
            case RIL_UNSOL_NITZ_TIME_RECEIVED: ret =  responseString(p); break;
            case RIL_UNSOL_SIGNAL_STRENGTH: ret = responseSignalStrength(p); break;
            case RIL_UNSOL_DATA_CALL_LIST_CHANGED: ret = responseDataCallList(p);break;
            case RIL_UNSOL_SUPP_SVC_NOTIFICATION: ret = responseSuppServiceNotification(p); break;
            case RIL_UNSOL_STK_SESSION_END: ret = responseVoid(p); break;
            case RIL_UNSOL_STK_PROACTIVE_COMMAND: ret = responseString(p); break;
            case RIL_UNSOL_STK_EVENT_NOTIFY: ret = responseString(p); break;
            case RIL_UNSOL_STK_CALL_SETUP: ret = responseInts(p); break;
            case RIL_UNSOL_SIM_SMS_STORAGE_FULL: ret =  responseVoid(p); break;
            case RIL_UNSOL_SIM_REFRESH: ret =  responseSimRefresh(p); break;
            case RIL_UNSOL_CALL_RING: ret =  responseCallRing(p); break;
            case RIL_UNSOL_RESTRICTED_STATE_CHANGED: ret = responseInts(p); break;
            case RIL_UNSOL_RESPONSE_SIM_STATUS_CHANGED:  ret =  responseVoid(p); break;
            case RIL_UNSOL_RESPONSE_CDMA_NEW_SMS:  ret =  responseCdmaSms(p); break;
            case RIL_UNSOL_RESPONSE_NEW_BROADCAST_SMS:  ret =  responseRaw(p); break;
            case RIL_UNSOL_CDMA_RUIM_SMS_STORAGE_FULL:  ret =  responseVoid(p); break;
            case RIL_UNSOL_ENTER_EMERGENCY_CALLBACK_MODE: ret = responseVoid(p); break;
            case RIL_UNSOL_CDMA_CALL_WAITING: ret = responseCdmaCallWaiting(p); break;
            case RIL_UNSOL_CDMA_OTA_PROVISION_STATUS: ret = responseInts(p); break;
            case RIL_UNSOL_CDMA_INFO_REC: ret = responseCdmaInformationRecord(p); break;
            case RIL_UNSOL_OEM_HOOK_RAW: ret = responseRaw(p); break;
            case RIL_UNSOL_RINGBACK_TONE: ret = responseInts(p); break;
            case RIL_UNSOL_RESEND_INCALL_MUTE: ret = responseVoid(p); break;
            case RIL_UNSOL_CDMA_SUBSCRIPTION_SOURCE_CHANGED: ret = responseInts(p); break;
            case RIL_UNSOl_CDMA_PRL_CHANGED: ret = responseInts(p); break;
            case RIL_UNSOL_EXIT_EMERGENCY_CALLBACK_MODE: ret = responseVoid(p); break;
            case RIL_UNSOL_RIL_CONNECTED: ret = responseInts(p); break;
            case RIL_UNSOL_VOICE_RADIO_TECH_CHANGED: ret =  responseInts(p); break;
            case RIL_UNSOL_CELL_INFO_LIST: ret = responseCellInfoList(p); break;
            case RIL_UNSOL_RESPONSE_IMS_NETWORK_STATE_CHANGED: ret =  responseVoid(p); break;

            case RIL_UNSOL_NEIGHBORING_CELL_INFO: ret = responseStrings(p); break;
            case RIL_UNSOL_NETWORK_INFO: ret = responseStrings(p); break;
            case RIL_UNSOL_RESPONSE_PS_NETWORK_STATE_CHANGED: ret =  responseVoid(p); break;
            case RIL_UNSOL_INVALID_SIM:  ret = responseStrings(p); break;
            case RIL_UNSOL_RESPONSE_ACMT: ret = responseInts(p); break;
            case RIL_UNSOL_IMEI_LOCK: ret = responseVoid(p); break;
            case RIL_UNSOL_RESPONSE_MMRR_STATUS_CHANGED: ret = responseInts(p); break;
            case RIL_UNSOL_STK_EVDL_CALL: ret = responseInts(p); break;
            case RIL_UNSOL_STK_CALL_CTRL: ret = responseStrings(p); break;

            case RIL_UNSOL_UICC_SUBSCRIPTION_STATUS_CHANGED: ret =  responseInts(p); break;
            case RIL_UNSOL_SRVCC_STATE_NOTIFY: ret = responseInts(p); break;
            case RIL_UNSOL_HARDWARE_CONFIG_CHANGED: ret = responseHardwareConfig(p); break;
            case RIL_UNSOL_SET_PHONE_RAT_FAMILY_COMPLETE:
                    ret = responseSetPhoneRatChanged(p); break;
            /* M: call control part start */
            case RIL_UNSOL_CALL_FORWARDING: ret = responseInts(p); break;
            case RIL_UNSOL_CRSS_NOTIFICATION: ret = responseCrssNotification(p); break;
            case RIL_UNSOL_INCOMING_CALL_INDICATION: ret = responseStrings(p); break;
            case RIL_UNSOL_CIPHER_INDICATION: ret = responseStrings(p); break;
            case RIL_UNSOL_CNAP: ret = responseStrings(p); break;
            case RIL_UNSOL_SPEECH_CODEC_INFO: ret =  responseInts(p); break;
            /* M: call control part end */
            //MTK-START multiple application support
            case RIL_UNSOL_APPLICATION_SESSION_ID_CHANGED: ret = responseInts(p); break;
            //MTK-END multiple application support
            case RIL_UNSOL_SIM_MISSING: ret = responseInts(p); break;
            case RIL_UNSOL_SIM_RECOVERY: ret = responseInts(p); break;
            case RIL_UNSOL_VIRTUAL_SIM_ON: ret = responseInts(p); break;
            case RIL_UNSOL_VIRTUAL_SIM_OFF: ret = responseInts(p); break;
            case RIL_UNSOL_SIM_PLUG_OUT: ret = responseVoid(p); break;
            case RIL_UNSOL_SIM_PLUG_IN: ret = responseVoid(p); break;
            case RIL_UNSOL_SIM_COMMON_SLOT_NO_CHANGED: ret = responseVoid(p); break;
            case RIL_UNSOL_DATA_ALLOWED: ret = responseVoid(p); break;
            case RIL_UNSOL_PHB_READY_NOTIFICATION: ret = responseInts(p); break;
            case RIL_UNSOL_STK_SETUP_MENU_RESET: ret = responseVoid(p); break;
            // IMS
            case RIL_UNSOL_IMS_ENABLE_DONE: ret = responseVoid(p); break;
            case RIL_UNSOL_IMS_DISABLE_DONE: ret = responseVoid(p); break;
            case RIL_UNSOL_IMS_REGISTRATION_INFO: ret = responseInts(p); break;
            //VoLTE
            case RIL_UNSOL_DEDICATE_BEARER_ACTIVATED: ret = responseSetupDedicateDataCall(p);break;
            case RIL_UNSOL_DEDICATE_BEARER_MODIFIED: ret = responseSetupDedicateDataCall(p);break;
            case RIL_UNSOL_DEDICATE_BEARER_DEACTIVATED: ret = responseInts(p);break;
            // M: Fast Dormancy
            case RIL_UNSOL_SCRI_RESULT: ret = responseInts(p); break;

            case RIL_UNSOL_RESPONSE_PLMN_CHANGED: ret = responseStrings(p); break;
            case RIL_UNSOL_RESPONSE_REGISTRATION_SUSPENDED: ret = responseInts(p); break;
            //Remote SIM ME lock related APIs [Start]
            case RIL_UNSOL_MELOCK_NOTIFICATION: ret = responseInts(p); break;
            //Remote SIM ME lock related APIs [End]
            case RIL_UNSOL_VOLTE_EPS_NETWORK_FEATURE_SUPPORT: ret = responseInts(p); break;

            /// M: IMS feature. @{
            //For updating call ids for conference call after SRVCC is done.
            case RIL_UNSOL_ECONF_SRVCC_INDICATION: ret = responseInts(p); break;
            //For updating conference call merged/added result.
            case RIL_UNSOL_ECONF_RESULT_INDICATION: ret = responseStrings(p); break;
            //For updating call mode and pau information.
            case RIL_UNSOL_CALL_INFO_INDICATION : ret = responseStrings(p); break;
            /// @}

            case RIL_UNSOL_VOLTE_EPS_NETWORK_FEATURE_INFO: ret = responseInts(p); break;
            case RIL_UNSOL_SRVCC_HANDOVER_INFO_INDICATION:ret = responseInts(p); break;
            // M: CC33 LTE.
            case RIL_UNSOL_RAC_UPDATE: ret = responseVoid(p); break;
            case RIL_UNSOL_REMOVE_RESTRICT_EUTRAN: ret = responseVoid(p); break;

            //MTK-START for MD state change
            case RIL_UNSOL_MD_STATE_CHANGE: ret = responseInts(p); break;
            //MTK-END for MD state change
            
            case RIL_UNSOL_MO_DATA_BARRING_INFO: ret = responseInts(p); break;
            case RIL_UNSOL_SSAC_BARRING_INFO: ret = responseInts(p); break;
//MTK_TC1_FEATURE for LGE CSMCC_MO_CALL_MODIFIED { 
            case RIL_UNSOL_RESPONSE_MO_CALL_STATE_CHANGED: ret =  responseStrings(p); break; 
//}  
            /* M: C2K part start*/
            case RIL_UNSOL_CDMA_CALL_ACCEPTED: ret = responseVoid(p); break;
            case RIL_UNSOL_UTK_SESSION_END: ret = responseVoid(p); break;
            case RIL_UNSOL_UTK_PROACTIVE_COMMAND: ret = responseString(p); break;
            case RIL_UNSOL_UTK_EVENT_NOTIFY: ret = responseString(p); break;
            case RIL_UNSOL_VIA_GPS_EVENT: ret = responseInts(p); break;
            case RIL_UNSOL_VIA_NETWORK_TYPE_CHANGE: ret = responseInts(p); break;
            case RIL_UNSOL_VIA_PLMN_CHANGE_REG_PAUSE: ret = responseStrings(p); break;
            case RIL_UNSOL_VIA_INVALID_SIM_DETECTED: ret = responseVoid(p); break;
            /* M: C2K part end*/
            default:
                throw new RuntimeException("Unrecognized unsol response: " + response);
            //break; (implied)
        }} catch (Throwable tr) {
            Rlog.e(RILJ_LOG_TAG, "Exception processing unsol response: " + response +
                "Exception:" + tr.toString());
            return;
        }

        switch(response) {
            case RIL_UNSOL_RESPONSE_RADIO_STATE_CHANGED:
                /* has bonus radio state int */
                RadioState newState = getRadioStateFromInt(p.readInt());
                if (RILJ_LOGD) unsljLogMore(response, newState.toString());

                switchToRadioState(newState);
            break;
            case RIL_UNSOL_RESPONSE_IMS_NETWORK_STATE_CHANGED:
                if (RILJ_LOGD) unsljLog(response);

                mImsNetworkStateChangedRegistrants
                    .notifyRegistrants(new AsyncResult(null, null, null));
            break;
            case RIL_UNSOL_RESPONSE_CALL_STATE_CHANGED:
                if (RILJ_LOGD) unsljLog(response);

                mCallStateRegistrants
                    .notifyRegistrants(new AsyncResult(null, null, null));
            break;
            case RIL_UNSOL_RESPONSE_VOICE_NETWORK_STATE_CHANGED:
				//M: Add info to parse before pollState
                if (RILJ_LOGD) unsljLogvRet(response, ret);

                mVoiceNetworkStateRegistrants
                    .notifyRegistrants(new AsyncResult (null, ret, null));
            break;
            case RIL_UNSOL_RESPONSE_NEW_SMS: {
                if (RILJ_LOGD) unsljLog(response);

                // FIXME this should move up a layer
                String a[] = new String[2];

                a[1] = (String)ret;

                SmsMessage sms;

                sms = SmsMessage.newFromCMT(a);
                if (mGsmSmsRegistrant != null) {
                    mGsmSmsRegistrant
                        .notifyRegistrant(new AsyncResult(null, sms, null));
                }
            break;
            }
            case RIL_UNSOL_RESPONSE_NEW_SMS_STATUS_REPORT:
                if (RILJ_LOGD) unsljLogRet(response, ret);

                if (mSmsStatusRegistrant != null) {
                    mSmsStatusRegistrant.notifyRegistrant(
                            new AsyncResult(null, ret, null));
                }
            break;
            case RIL_UNSOL_RESPONSE_NEW_SMS_ON_SIM:
                if (RILJ_LOGD) unsljLogRet(response, ret);

                int[] smsIndex = (int[])ret;

                if(smsIndex.length == 1) {
                    if (mSmsOnSimRegistrant != null) {
                        mSmsOnSimRegistrant.
                                notifyRegistrant(new AsyncResult(null, smsIndex, null));
                    }
                } else {
                    if (RILJ_LOGD) riljLog(" NEW_SMS_ON_SIM ERROR with wrong length "
                            + smsIndex.length);
                }
            break;
            case RIL_UNSOL_ON_USSD:
                String[] resp = (String[])ret;

                if (resp.length < 2) {
                    resp = new String[2];
                    resp[0] = ((String[])ret)[0];
                    resp[1] = null;
                }
                if (RILJ_LOGD) unsljLogMore(response, resp[0]);
                if (mUSSDRegistrant != null) {
                    mUSSDRegistrant.notifyRegistrant(
                        new AsyncResult (null, resp, null));
                }
            break;
            case RIL_UNSOL_NITZ_TIME_RECEIVED:
                if (RILJ_LOGD) unsljLogRet(response, ret);

                // has bonus long containing milliseconds since boot that the NITZ
                // time was received
                long nitzReceiveTime = p.readLong();

                Object[] result = new Object[2];

                result[0] = ret;
                result[1] = Long.valueOf(nitzReceiveTime);

                boolean ignoreNitz = SystemProperties.getBoolean(
                        TelephonyProperties.PROPERTY_IGNORE_NITZ, false);

                if (ignoreNitz) {
                    if (RILJ_LOGD) riljLog("ignoring UNSOL_NITZ_TIME_RECEIVED");
                } else {
                    if (mNITZTimeRegistrant != null) {

                        mNITZTimeRegistrant
                            .notifyRegistrant(new AsyncResult (null, result, null));
                    } else {
                        // in case NITZ time registrant isnt registered yet
                        mLastNITZTimeInfo = result;
                    }
                }
            break;

            case RIL_UNSOL_SIGNAL_STRENGTH:
                // Note this is set to "verbose" because it happens
                // frequently
                if (RILJ_LOGV) unsljLogvRet(response, ret);

                if (mSignalStrengthRegistrant != null) {
                    mSignalStrengthRegistrant.notifyRegistrant(
                                        new AsyncResult (null, ret, null));
                }
            break;
            case RIL_UNSOL_DATA_CALL_LIST_CHANGED:
                if (RILJ_LOGD) unsljLogRet(response, ret);

                mDataNetworkStateRegistrants.notifyRegistrants(new AsyncResult(null, ret, null));
            break;

            case RIL_UNSOL_SUPP_SVC_NOTIFICATION:
                if (RILJ_LOGD) unsljLogRet(response, ret);

                if (mSsnRegistrant != null) {
                    mSsnRegistrant.notifyRegistrant(
                                        new AsyncResult (null, ret, null));
                }
                break;

            case RIL_UNSOL_STK_SESSION_END:
                if (RILJ_LOGD) unsljLog(response);

                if (mCatSessionEndRegistrant != null) {
                    mCatSessionEndRegistrant.notifyRegistrant(
                                        new AsyncResult (null, ret, null));
                }
                break;

            case RIL_UNSOL_STK_PROACTIVE_COMMAND:
                if (RILJ_LOGD) unsljLog(response);

                if (mCatProCmdRegistrant != null) {
                    mCatProCmdRegistrant.notifyRegistrant(
                                        new AsyncResult (null, ret, null));
                }
                break;

            case RIL_UNSOL_STK_EVENT_NOTIFY:
                if (RILJ_LOGD) unsljLog(response);

                if (mCatEventRegistrant != null) {
                    mCatEventRegistrant.notifyRegistrant(
                                        new AsyncResult (null, ret, null));
                }
                break;

            case RIL_UNSOL_STK_CALL_SETUP:
                if (RILJ_LOGD) unsljLogRet(response, ret);

                if (mCatCallSetUpRegistrant != null) {
                    mCatCallSetUpRegistrant.notifyRegistrant(
                                        new AsyncResult (null, ret, null));
                }
                break;

            case RIL_UNSOL_SIM_SMS_STORAGE_FULL:
                if (RILJ_LOGD) unsljLog(response);

                // MTK-START, SMS part
                if (mIccSmsFullRegistrant != null) {
                    mIccSmsFullRegistrant.notifyRegistrant();
                } else {
                    // Phone process is not ready and cache it then wait register to notify
                    if (RILJ_LOGD) Rlog.d(RILJ_LOG_TAG, "Cache sim sms full event");
                    mIsSmsSimFull = true;
                }
                // MTK-END, SMS part
                break;

            case RIL_UNSOL_SIM_REFRESH:
                if (RILJ_LOGD) unsljLogRet(response, ret);

                if (mIccRefreshRegistrants != null) {
                    mIccRefreshRegistrants.notifyRegistrants(
                            new AsyncResult (null, ret, null));
                }
                break;

            case RIL_UNSOL_CALL_RING:
                if (RILJ_LOGD) unsljLogRet(response, ret);

                if (mRingRegistrant != null) {
                    mRingRegistrant.notifyRegistrant(
                            new AsyncResult (null, ret, null));
                }
                break;

            case RIL_UNSOL_RESTRICTED_STATE_CHANGED:
                if (RILJ_LOGD) unsljLogvRet(response, ret);
                if (mRestrictedStateRegistrant != null) {
                    mRestrictedStateRegistrant.notifyRegistrant(
                                        new AsyncResult (null, ret, null));
                }
                break;

            case RIL_UNSOL_RESPONSE_SIM_STATUS_CHANGED:
                if (RILJ_LOGD) unsljLog(response);

                if (mIccStatusChangedRegistrants != null) {
                    mIccStatusChangedRegistrants.notifyRegistrants();
                }
                break;

            case RIL_UNSOL_RESPONSE_CDMA_NEW_SMS:
                if (RILJ_LOGD) unsljLog(response);

                SmsMessage sms = (SmsMessage) ret;

                if (mCdmaSmsRegistrant != null) {
                    mCdmaSmsRegistrant
                        .notifyRegistrant(new AsyncResult(null, sms, null));
                }
                break;

            case RIL_UNSOL_RESPONSE_NEW_BROADCAST_SMS:
                if (RILJ_LOGD) unsljLog(response);

                if (mGsmBroadcastSmsRegistrant != null) {
                    mGsmBroadcastSmsRegistrant
                        .notifyRegistrant(new AsyncResult(null, ret, null));
                }
                break;

            case RIL_UNSOL_CDMA_RUIM_SMS_STORAGE_FULL:
                if (RILJ_LOGD) unsljLog(response);

                if (mIccSmsFullRegistrant != null) {
                    mIccSmsFullRegistrant.notifyRegistrant();
                }
                break;

            case RIL_UNSOL_ENTER_EMERGENCY_CALLBACK_MODE:
                if (RILJ_LOGD) unsljLog(response);

                if (mEmergencyCallbackModeRegistrant != null) {
                    mEmergencyCallbackModeRegistrant.notifyRegistrant();
                }
                break;

            case RIL_UNSOL_CDMA_CALL_WAITING:
                if (RILJ_LOGD) unsljLogRet(response, ret);

                if (mCallWaitingInfoRegistrants != null) {
                    mCallWaitingInfoRegistrants.notifyRegistrants(
                                        new AsyncResult (null, ret, null));
                }
                break;

            case RIL_UNSOL_CDMA_OTA_PROVISION_STATUS:
                if (RILJ_LOGD) unsljLogRet(response, ret);

                if (mOtaProvisionRegistrants != null) {
                    mOtaProvisionRegistrants.notifyRegistrants(
                                        new AsyncResult (null, ret, null));
                }
                break;

            case RIL_UNSOL_CDMA_INFO_REC:
                ArrayList<CdmaInformationRecords> listInfoRecs;

                try {
                    listInfoRecs = (ArrayList<CdmaInformationRecords>)ret;
                } catch (ClassCastException e) {
                    Rlog.e(RILJ_LOG_TAG, "Unexpected exception casting to listInfoRecs", e);
                    break;
                }

                for (CdmaInformationRecords rec : listInfoRecs) {
                    if (RILJ_LOGD) unsljLogRet(response, rec);
                    notifyRegistrantsCdmaInfoRec(rec);
                }
                break;

            case RIL_UNSOL_OEM_HOOK_RAW:
                if (RILJ_LOGD) unsljLogvRet(response, IccUtils.bytesToHexString((byte[])ret));
                if (mUnsolOemHookRawRegistrant != null) {
                    mUnsolOemHookRawRegistrant.notifyRegistrant(new AsyncResult(null, ret, null));
                }
                break;

            case RIL_UNSOL_RINGBACK_TONE:
                if (RILJ_LOGD) unsljLogvRet(response, ret);
                if (mRingbackToneRegistrants != null) {
                    boolean playtone = (((int[])ret)[0] == 1);
                    mRingbackToneRegistrants.notifyRegistrants(
                                        new AsyncResult (null, playtone, null));
                }
                break;

            case RIL_UNSOL_RESEND_INCALL_MUTE:
                if (RILJ_LOGD) unsljLogRet(response, ret);

                if (mResendIncallMuteRegistrants != null) {
                    mResendIncallMuteRegistrants.notifyRegistrants(
                                        new AsyncResult (null, ret, null));
                }
                break;

            case RIL_UNSOL_VOICE_RADIO_TECH_CHANGED:
                if (RILJ_LOGD) unsljLogRet(response, ret);

                if (mVoiceRadioTechChangedRegistrants != null) {
                    mVoiceRadioTechChangedRegistrants.notifyRegistrants(
                            new AsyncResult(null, ret, null));
                }
                break;

            case RIL_UNSOL_CDMA_SUBSCRIPTION_SOURCE_CHANGED:
                if (RILJ_LOGD) unsljLogRet(response, ret);

                if (mCdmaSubscriptionChangedRegistrants != null) {
                    mCdmaSubscriptionChangedRegistrants.notifyRegistrants(
                                        new AsyncResult (null, ret, null));
                }
                break;

            case RIL_UNSOl_CDMA_PRL_CHANGED:
                if (RILJ_LOGD) unsljLogRet(response, ret);

                if (mCdmaPrlChangedRegistrants != null) {
                    mCdmaPrlChangedRegistrants.notifyRegistrants(
                                        new AsyncResult (null, ret, null));
                }
                break;

            case RIL_UNSOL_EXIT_EMERGENCY_CALLBACK_MODE:
                if (RILJ_LOGD) unsljLogRet(response, ret);

                if (mExitEmergencyCallbackModeRegistrants != null) {
                    mExitEmergencyCallbackModeRegistrants.notifyRegistrants(
                                        new AsyncResult (null, null, null));
                }
                break;

            case RIL_UNSOL_RIL_CONNECTED: {
                if (RILJ_LOGD) unsljLogRet(response, ret);

                // Set ecc list before MO call
                if  (TelephonyManager.getDefault().getMultiSimConfiguration() == TelephonyManager.MultiSimVariants.DSDA
                        || mInstanceId == 0) {
                    setEccList();
                }

                // Initial conditions
                //setRadioPower(false, null);
                setPreferredNetworkType(mPreferredNetworkType, null);
                setCdmaSubscriptionSource(mCdmaSubscription, null);
                setCellInfoListRate(Integer.MAX_VALUE, null);
                notifyRegistrantsRilConnectionChanged(((int[])ret)[0]);
                sendScreenState(isScreenOn);
                break;
            }
            case RIL_UNSOL_CELL_INFO_LIST: {
                if (RILJ_LOGD) unsljLogRet(response, ret);

                if (mRilCellInfoListRegistrants != null) {
                    mRilCellInfoListRegistrants.notifyRegistrants(
                                        new AsyncResult (null, ret, null));
                }
                break;
            }
            case RIL_UNSOL_UICC_SUBSCRIPTION_STATUS_CHANGED: {
                if (RILJ_LOGD) unsljLogRet(response, ret);

                if (mSubscriptionStatusRegistrants != null) {
                    mSubscriptionStatusRegistrants.notifyRegistrants(
                                        new AsyncResult (null, ret, null));
                }
                break;
            }
            case RIL_UNSOL_SRVCC_STATE_NOTIFY: {
                if (RILJ_LOGD) unsljLogRet(response, ret);

                if (mSrvccStateRegistrants != null) {
                    mSrvccStateRegistrants
                            .notifyRegistrants(new AsyncResult(null, ret, null));
                }
                break;
            }


            case RIL_UNSOL_NEIGHBORING_CELL_INFO:
                if (RILJ_LOGD) unsljLogvRet(response, ret);
                if (mNeighboringInfoRegistrants != null) {
                    mNeighboringInfoRegistrants.notifyRegistrants(
                                        new AsyncResult(null, ret, null));
                }
                break;

            case RIL_UNSOL_NETWORK_INFO:
                if (RILJ_LOGD) unsljLogvRet(response, ret);
                if (mNetworkInfoRegistrants != null) {
                    mNetworkInfoRegistrants.notifyRegistrants(
                                        new AsyncResult(null, ret, null));
                }
                break;

            case RIL_UNSOL_HARDWARE_CONFIG_CHANGED:
                if (RILJ_LOGD) unsljLogRet(response, ret);

                if (mHardwareConfigChangeRegistrants != null) {
                    mHardwareConfigChangeRegistrants.notifyRegistrants(
                                             new AsyncResult (null, ret, null));
                }
                break;

            case RIL_UNSOL_SET_PHONE_RAT_FAMILY_COMPLETE:
                if (RILJ_LOGD) unsljLogRet(response, ret);
                riljLog("mPhoneRatFamilyreg size :" + mPhoneRatFamilyChangedRegistrants.size());
                if (mPhoneRatFamilyChangedRegistrants != null) {
                    mPhoneRatFamilyChangedRegistrants.notifyRegistrants(
                            new AsyncResult(null, ret, null));
                }
                break;

            /* M: call control part start */
            case RIL_UNSOL_CALL_FORWARDING:
                if (RILJ_LOGD) unsljLogRet(response, ret);
                /* M: SS part */
                if (mCallForwardingInfoRegistrants != null) {
                    boolean bCfuEnabled = (((int[]) ret)[0] == 1);
                    boolean bIsLine1 = (((int[]) ret)[1] == 1);
                    /* ONLY notify for Line1 */
                    if (bIsLine1) {
                        mCfuReturnValue = ret;
                        mCallForwardingInfoRegistrants.notifyRegistrants(
                                        new AsyncResult(null, ret, null));
                    }
                 /* M: SS part end */
                }
                break;

            case RIL_UNSOL_CRSS_NOTIFICATION:
                if (RILJ_LOGD) unsljLogRet(response, ret);

                if (mCallRelatedSuppSvcRegistrant != null) {
                    mCallRelatedSuppSvcRegistrant.notifyRegistrant(
                                        new AsyncResult(null, ret, null));
                }
                break;

            case RIL_UNSOL_INCOMING_CALL_INDICATION:
                if (RILJ_LOGD) unsljLogvRet(response, ret);
                if (mIncomingCallIndicationRegistrant != null) {
                    mIncomingCallIndicationRegistrant.notifyRegistrant(new AsyncResult(null, ret, null));
                }
                break;

            case RIL_UNSOL_CIPHER_INDICATION:
                if (RILJ_LOGD) unsljLogvRet(response, ret);

                int simCipherStatus = Integer.parseInt(((String[]) ret)[0]);
                int sessionStatus = Integer.parseInt(((String[]) ret)[1]);
                int csStatus = Integer.parseInt(((String[]) ret)[2]);
                int psStatus = Integer.parseInt(((String[]) ret)[3]);

                riljLog("RIL_UNSOL_CIPHER_INDICATION :" + simCipherStatus + " " + sessionStatus + " " + csStatus + " " + psStatus);

                int[] cipherResult = new int[3];

                cipherResult[0] = simCipherStatus;
                cipherResult[1] = csStatus;
                cipherResult[2] = psStatus;

                if (mCipherIndicationRegistrant != null) {
                    mCipherIndicationRegistrant.notifyRegistrants(
                        new AsyncResult(null, cipherResult, null));
                }

                break;

            case RIL_UNSOL_CNAP:
                    String[] respCnap = (String[]) ret;
                    int validity = Integer.parseInt(((String[]) ret)[1]);

                    riljLog("RIL_UNSOL_CNAP :" + respCnap[0] + " " + respCnap[1]);
                    if (validity == 0) {
                        if (mCnapNotifyRegistrant != null) {
                            mCnapNotifyRegistrant.notifyRegistrant(
                                            new AsyncResult(null, respCnap, null));
                        }
                    }

                break;

            case RIL_UNSOL_SPEECH_CODEC_INFO:
                if (RILJ_LOGD) unsljLogvRet(response, ret);

                if (mSpeechCodecInfoRegistrant != null) {
                    mSpeechCodecInfoRegistrant.notifyRegistrant(
                        new AsyncResult(null, ret, null));
                }
            break;
            /* M: call control part end */

            case RIL_UNSOL_RESPONSE_PS_NETWORK_STATE_CHANGED:
                if (RILJ_LOGD) unsljLog(response);

                    mPsNetworkStateRegistrants
                        .notifyRegistrants(new AsyncResult(null, null, null));
            break;

            /* M: network part start */
            case RIL_UNSOL_IMEI_LOCK:
                if (RILJ_LOGD) unsljLog(response);
                if (mImeiLockRegistrant != null) {
                    mImeiLockRegistrant.notifyRegistrants(new AsyncResult(null, null, null));
                }
                break;

            //ALPS00248788 START
            case RIL_UNSOL_INVALID_SIM:
                if (RILJ_LOGD) unsljLogvRet(response, ret);
                if (mInvalidSimInfoRegistrant != null) {
                   mInvalidSimInfoRegistrant.notifyRegistrants(new AsyncResult(null, ret, null));
                }
                break;
            //ALPS00248788 END
            //MTK-START [MTK80515] [ALPS00368272]
            case RIL_UNSOL_RESPONSE_MMRR_STATUS_CHANGED:
                if (RILJ_LOGD) unsljLog(response);
                if (ret != null) {
                    int[] emmrrs = (int[]) ret;
                    int ps_status = Integer.valueOf(emmrrs[0]);

                    if (!SystemProperties.get("ro.mtk_bsp_package").equals("1")) {
                        try {
                            if (mServiceStateExt.isBroadcastEmmrrsPsResume(ps_status)) {
                                riljLog("Broadcast for EMMRRS: android.intent.action.EMMRRS_PS_RESUME ");
                            }
                        } catch (RuntimeException e) {
                            e.printStackTrace();
                        }
                    }
                }
                break;
            //MTK-END [MTK80515] [ALPS00368272]

            case RIL_UNSOL_FEMTOCELL_INFO:
                if (RILJ_LOGD) unsljLogvRet(response, ret);
                mFemtoCellInfoRegistrants.notifyRegistrants(new AsyncResult(null, ret, null));
                break;

            // ALPS00297719 START
            case RIL_UNSOL_RESPONSE_ACMT:
                if (RILJ_LOGD) unsljLog(response);
                if (ret != null) {
                    int[] acmt = (int[]) ret;
                    if (acmt.length == 2) {
                        int error_type = Integer.valueOf(acmt[0]);
                        int error_cause = acmt[1];


                        if (!SystemProperties.get("ro.mtk_bsp_package").equals("1")) {
                            try {
                                if (mServiceStateExt.needBrodcastAcmt(error_type, error_cause)
                                        == true) {
                                    Intent intent = new Intent(
                                            TelephonyIntents.ACTION_ACMT_NETWORK_SERVICE_STATUS_INDICATOR);
                                    intent.putExtra("CauseCode", acmt[1]);
                                    intent.putExtra("CauseType", acmt[0]);
                                    mContext.sendBroadcast(intent);
                                    riljLog("Broadcast for ACMT: com.VendorName.CauseCode "
                                            + acmt[1] + "," + acmt[0]);
                                }
                            } catch (RuntimeException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }
                break;
            // ALPS00297719 END
            /* M: network part end */
            case RIL_UNSOL_STK_EVDL_CALL:
                if (false == SystemProperties.get("ro.mtk_bsp_package").equals("1")) {
                    if (RILJ_LOGD) unsljLogvRet(response, ret);
                    if (mStkEvdlCallRegistrant != null) {
                        mStkEvdlCallRegistrant.notifyRegistrant(new AsyncResult(null, ret, null));
                    }
                }
                break;

            case RIL_UNSOL_STK_CALL_CTRL:
                if (RILJ_LOGD) {
                    unsljLogvRet(response, ret);
                }
                if (mStkCallCtrlRegistrant != null) {
                    mStkCallCtrlRegistrant.notifyRegistrant(new AsyncResult(null, ret, null));
                }
                break;
            case RIL_UNSOL_STK_SETUP_MENU_RESET:
                if (RILJ_LOGD) unsljLogRet(response, ret);

                if (mStkSetupMenuResetRegistrant != null) {
                    mStkSetupMenuResetRegistrant.notifyRegistrant(new AsyncResult(null, ret, null));
                }
                break;

            //MTK-START multiple application support
            case RIL_UNSOL_APPLICATION_SESSION_ID_CHANGED: {
                if (RILJ_LOGD) unsljLog(response);
                if (mSessionChangedRegistrants != null) {
                    mSessionChangedRegistrants.notifyRegistrants(new AsyncResult(null, ret, null));
                }
                break;
            }
            //MTK-END multiple application support
            case RIL_UNSOL_SIM_MISSING:
                if (RILJ_LOGD) unsljLogRet(response, ret);
                if (mSimMissing != null) {
                    mSimMissing.notifyRegistrants(
                                        new AsyncResult(null, ret, null));
                }
                break;
            case RIL_UNSOL_SIM_RECOVERY:
                if (RILJ_LOGD) unsljLogRet(response, ret);
                if (mSimRecovery != null) {
                    mSimRecovery.notifyRegistrants(
                                        new AsyncResult(null, ret, null));
                }
                break;
            case RIL_UNSOL_VIRTUAL_SIM_ON:
                if (RILJ_LOGD) unsljLogRet(response, ret);
                if (mVirtualSimOn != null) {
                    mVirtualSimOn.notifyRegistrants(
                                        new AsyncResult(null, ret, null));
                }
                break;
            case RIL_UNSOL_VIRTUAL_SIM_OFF:
                if (RILJ_LOGD) unsljLogRet(response, ret);
                if (mVirtualSimOff != null) {
                    mVirtualSimOff.notifyRegistrants(
                        new AsyncResult(null, ret, null));
                }
                break;
            case RIL_UNSOL_SIM_PLUG_OUT:
                if (RILJ_LOGD) unsljLogRet(response, ret);
                if (mSimPlugOutRegistrants != null) {
                    mSimPlugOutRegistrants.notifyRegistrants(
                        new AsyncResult(null, ret, null));
                }
                mCfuReturnValue = null;
                break;
            case RIL_UNSOL_SIM_PLUG_IN:
                if (RILJ_LOGD) unsljLogRet(response, ret);
                if (mSimPlugInRegistrants != null) {
                    mSimPlugInRegistrants.notifyRegistrants(
                        new AsyncResult(null, ret, null));
                }
                break;
            case RIL_UNSOL_SIM_COMMON_SLOT_NO_CHANGED:
                if (RILJ_LOGD) unsljLog(response);
                if (mCommonSlotNoChangedRegistrants != null) {
                    mCommonSlotNoChangedRegistrants.notifyRegistrants(new AsyncResult(null, null, null));
                }
                break;
            case RIL_UNSOL_DATA_ALLOWED:
                if (RILJ_LOGD) unsljLog(response);
                if (mDataAllowedRegistrants != null) {
                    mDataAllowedRegistrants.notifyRegistrants(new AsyncResult(null, null, null));
                }
                break;

            case RIL_UNSOL_PHB_READY_NOTIFICATION:
                if (RILJ_LOGD) unsljLogRet(response, ret);
                if (mPhbReadyRegistrants != null) {
                    mPhbReadyRegistrants.notifyRegistrants(
                                        new AsyncResult(null, ret, null));
                }
                break;

            case RIL_UNSOL_IMS_REGISTRATION_INFO:
                if (RILJ_LOGD) unsljLog(response);
                if (mImsRegistrationInfoRegistrants != null) {
                    mImsRegistrationInfoRegistrants.notifyRegistrants(new AsyncResult(null, ret, null));
                }
                break;
            case RIL_UNSOL_RESPONSE_PLMN_CHANGED:
                if (RILJ_LOGD) unsljLogvRet(response, ret);
                if (mPlmnChangeNotificationRegistrant != null) {
                    mPlmnChangeNotificationRegistrant.notifyRegistrants(new AsyncResult(null, ret, null));
                }
                break;
            case RIL_UNSOL_RESPONSE_REGISTRATION_SUSPENDED:
                if (RILJ_LOGD) unsljLogvRet(response, ret);
                if (mRegistrationSuspendedRegistrant != null) {
                    mRegistrationSuspendedRegistrant.notifyRegistrant(new AsyncResult(null, ret, null));
                }
                break;
            //Remote SIM ME lock related APIs [Start]
            case RIL_UNSOL_MELOCK_NOTIFICATION:
                if (RILJ_LOGD) unsljLogRet(response, ret);
                if (mMelockRegistrants != null) {
                    mMelockRegistrants.notifyRegistrants(
                                        new AsyncResult(null, ret, null));
                }
                break;
            //Remote SIM ME lock related APIs [End]
            case RIL_UNSOL_IMS_ENABLE_DONE:
                if (RILJ_LOGD) unsljLog(response);
                if (mImsEnableRegistrants != null) {
                    mImsEnableRegistrants.notifyRegistrants();
                }
                break;
            case RIL_UNSOL_IMS_DISABLE_DONE:
                if (RILJ_LOGD) unsljLog(response);
                if (mImsDisableRegistrants != null) {
                    mImsDisableRegistrants.notifyRegistrants();
                }
                break;
            // M: Fast Dormancy
            case RIL_UNSOL_SCRI_RESULT:
                Integer scriResult = (((int[]) ret)[0]);
                riljLog("s:" + scriResult + ":" + (((int[]) ret)[0]));
                if (RILJ_LOGD) unsljLogRet(response, ret);

                if (mScriResultRegistrant != null) {
                   mScriResultRegistrant.notifyRegistrant(new AsyncResult(null, scriResult, null));
                }
                break;
            case RIL_UNSOL_VOLTE_EPS_NETWORK_FEATURE_SUPPORT:
                if (RILJ_LOGD) unsljLogRet(response, ret);
                if (mEpsNetworkFeatureSupportRegistrants != null) {
                    mEpsNetworkFeatureSupportRegistrants.notifyRegistrants(
                                        new AsyncResult (null, ret, null));
                }
                break;

            /// M: IMS feature. @{
            //For updating call ids for conference call after SRVCC is done.
            case RIL_UNSOL_ECONF_SRVCC_INDICATION:
                if (RILJ_LOGD) unsljLog(response);
                if (mEconfSrvccRegistrants != null) {
                    mEconfSrvccRegistrants.notifyRegistrants(new AsyncResult(null, ret, null));
                }
                break;

            //For updating conference call merged/added result.
            case RIL_UNSOL_ECONF_RESULT_INDICATION:
                if (RILJ_LOGD) unsljLog(response);
                if (mEconfResultRegistrants != null) {
                	 riljLog("Notify ECONF result");
                	 String[] econfResult = (String[])ret;
                	 riljLog("ECONF result = " + econfResult[3]);
                	 mEconfResultRegistrants.notifyRegistrants(new AsyncResult(null, ret, null));
                }
                break;

            //For updating call mode and pau information.
            case RIL_UNSOL_CALL_INFO_INDICATION :
                if (RILJ_LOGD) unsljLog(response);
                if (mCallInfoRegistrants != null) {
                   mCallInfoRegistrants.notifyRegistrants(new AsyncResult(null, ret, null));
                }
                break;
            /// @}

            case RIL_UNSOL_VOLTE_EPS_NETWORK_FEATURE_INFO:
                if (RILJ_LOGD) unsljLog(response);
                if (mEpsNetworkFeatureInfoRegistrants != null) {
                   mEpsNetworkFeatureInfoRegistrants.notifyRegistrants(new AsyncResult(null, ret, null));
                }
                break;

            case RIL_UNSOL_SRVCC_HANDOVER_INFO_INDICATION:
                if (RILJ_LOGD) unsljLog(response);
                if (mSrvccHandoverInfoIndicationRegistrants != null) {
                    mSrvccHandoverInfoIndicationRegistrants.notifyRegistrants(new AsyncResult(null, ret, null));
                }
                break;

            // IMS
            //VoLTE
            case RIL_UNSOL_DEDICATE_BEARER_ACTIVATED:
                if (RILJ_LOGD) unsljLogRet(response, ret);
                if(mDedicateBearerActivatedRegistrant != null) {
                    mDedicateBearerActivatedRegistrant.notifyRegistrants(new AsyncResult(null, ret, null));
                }
                break;
            case RIL_UNSOL_DEDICATE_BEARER_MODIFIED:
                if (RILJ_LOGD) unsljLogRet(response, ret);
                if(mDedicateBearerModifiedRegistrant != null) {
                    mDedicateBearerModifiedRegistrant.notifyRegistrants(new AsyncResult(null, ret, null));
                }
                break;
            case RIL_UNSOL_DEDICATE_BEARER_DEACTIVATED:
                if (RILJ_LOGD) unsljLogRet(response, ret);
                if(mDedicateBearerDeactivatedRegistrant != null) {
                    mDedicateBearerDeactivatedRegistrant.notifyRegistrants(new AsyncResult(null, ret, null));
                }
                break;

            //MTK-START for MD state change
            case RIL_UNSOL_MD_STATE_CHANGE:
                if (RILJ_LOGD) {
                    unsljLogRet(response, ret);
                }
                break;
            //MTK-END for MD state change
                
            case RIL_UNSOL_MO_DATA_BARRING_INFO:
                if (RILJ_LOGD) unsljLog(response);
                if (mMoDataBarringInfoRegistrants != null) {
                    mMoDataBarringInfoRegistrants.notifyRegistrants(new AsyncResult(null, ret, null));
                }
                break;

            case RIL_UNSOL_SSAC_BARRING_INFO:
                if (RILJ_LOGD) unsljLog(response);
                if (mSsacBarringInfoRegistrants != null) {
                    mSsacBarringInfoRegistrants.notifyRegistrants(new AsyncResult(null, ret, null));
                }
                break;
                
            // M: CC33 LTE.
            case RIL_UNSOL_RAC_UPDATE:
                if (RILJ_LOGD) unsljLog(response);
                mRacUpdateRegistrants
                    .notifyRegistrants(new AsyncResult(null, null, null));
                break;
            case RIL_UNSOL_REMOVE_RESTRICT_EUTRAN:
                if (RILJ_LOGD) unsljLog(response);
                mRemoveRestrictEutranRegistrants
                    .notifyRegistrants(new AsyncResult(null, null, null));
                break;

//MTK_TC1_FEATURE for LGE CSMCC_MO_CALL_MODIFIED { 
	           case RIL_UNSOL_RESPONSE_MO_CALL_STATE_CHANGED:                
if (SystemProperties.get("ro.mtk_tc1_feature").equals("1")) {   
                
                if (RILJ_LOGD) unsljLogRet(response,ret);
                mMoCallStateRegistrants.notifyRegistrants(new AsyncResult(null, ret, null));
}
        	    break;            
//}             
            /* M: C2K part start */
            case RIL_UNSOL_CDMA_CALL_ACCEPTED:
                if (RILJ_LOGD) {
                    unsljLogRet(response, ret);
                }

                if (mAcceptedRegistrant != null) {
                    mAcceptedRegistrant.notifyRegistrants(
                            new AsyncResult(null, ret, null));
                }
                break;
            case RIL_UNSOL_UTK_SESSION_END:
                if (RILJ_LOGD) {
                    unsljLog(response);
                }

                if (mUtkSessionEndRegistrant != null) {
                    mUtkSessionEndRegistrant.notifyRegistrant(
                                        new AsyncResult(null, ret, null));
                }
                break;
            case RIL_UNSOL_UTK_PROACTIVE_COMMAND:
                if (RILJ_LOGD) {
                    unsljLogRet(response, ret);
                }

                if (mUtkProCmdRegistrant != null) {
                    mUtkProCmdRegistrant.notifyRegistrant(
                                        new AsyncResult(null, ret, null));
                }
                break;
            case RIL_UNSOL_UTK_EVENT_NOTIFY:
                if (RILJ_LOGD) {
                    unsljLogRet(response, ret);
                }
                if (mUtkEventRegistrant != null) {
                    mUtkEventRegistrant.notifyRegistrant(
                                        new AsyncResult(null, ret, null));
                }
                break;
            case RIL_UNSOL_VIA_GPS_EVENT:
                if (RILJ_LOGD) {
                    unsljLogRet(response, ret);
                }
                if (mViaGpsEvent != null) {
                    mViaGpsEvent.notifyRegistrants(new AsyncResult(null, ret, null));
                }
                break;
            case RIL_UNSOL_VIA_NETWORK_TYPE_CHANGE:
                if (RILJ_LOGD) {
                    unsljLogRet(response, ret);
                }
                if (mNetworkTypeChangedRegistrant != null) {
                    mNetworkTypeChangedRegistrant.notifyRegistrants(
                            new AsyncResult(null, ret, null));
                }
                break;
            case RIL_UNSOL_VIA_PLMN_CHANGE_REG_PAUSE:
                if (RILJ_LOGD) {
                    unsljLogRet(response, ret);
                }
                String mccmnc = "";
                if (ret != null && ret instanceof String[]) {
                    String s[] = (String[]) ret;
                    if (s.length >= 2) {
                        mccmnc = s[0] + s[1];
                    }
                }
                riljLog("mccmnc changed mccmnc=" + mccmnc);
                mMccMncChangeRegistrants.notifyRegistrants(new AsyncResult(null, mccmnc, null));
                break;
            case RIL_UNSOL_VIA_INVALID_SIM_DETECTED:
                if (RILJ_LOGD) {
                    unsljLogRet(response, ret);
                }
                if (mInvalidSimDetectedRegistrant != null) {
                    mInvalidSimDetectedRegistrant.notifyRegistrants(
                            new AsyncResult(null, ret, null));
                }
                break;
            /* M: C2K part end*/
            default:
                break;
        }
    }

    /**
     * Notifiy all registrants that the ril has connected or disconnected.
     *
     * @param rilVer is the version of the ril or -1 if disconnected.
     */
    private void notifyRegistrantsRilConnectionChanged(int rilVer) {
        mRilVersion = rilVer;
        if (mRilConnectedRegistrants != null) {
            mRilConnectedRegistrants.notifyRegistrants(
                                new AsyncResult (null, new Integer(rilVer), null));
        }
    }

    private Object
    responseInts(Parcel p) {
        int numInts;
        int response[];

        numInts = p.readInt();

        response = new int[numInts];

        for (int i = 0 ; i < numInts ; i++) {
            response[i] = p.readInt();
        }

        return response;
    }


    private Object
    responseVoid(Parcel p) {
        return null;
    }

    private Object
    responseCallForward(Parcel p) {
        int numInfos;
        CallForwardInfo infos[];

        numInfos = p.readInt();

        infos = new CallForwardInfo[numInfos];

        for (int i = 0 ; i < numInfos ; i++) {
            infos[i] = new CallForwardInfo();

            infos[i].status = p.readInt();
            infos[i].reason = p.readInt();
            infos[i].serviceClass = p.readInt();
            infos[i].toa = p.readInt();
            infos[i].number = p.readString();
            infos[i].timeSeconds = p.readInt();
        }

        return infos;
    }

    private Object
    responseSuppServiceNotification(Parcel p) {
        SuppServiceNotification notification = new SuppServiceNotification();

        notification.notificationType = p.readInt();
        notification.code = p.readInt();
        notification.index = p.readInt();
        notification.type = p.readInt();
        notification.number = p.readString();

        return notification;
    }

    private Object
    responseCdmaSms(Parcel p) {
        SmsMessage sms;
        sms = SmsMessage.newFromParcel(p);

        return sms;
    }

    private Object
    responseString(Parcel p) {
        String response;

        response = p.readString();

        return response;
    }

    private Object
    responseStrings(Parcel p) {
        int num;
        String response[];

        response = p.readStringArray();

        return response;
    }

    private Object
    responseRaw(Parcel p) {
        int num;
        byte response[];

        response = p.createByteArray();

        return response;
    }

    private Object
    responseSMS(Parcel p) {
        int messageRef, errorCode;
        String ackPDU;

        messageRef = p.readInt();
        ackPDU = p.readString();
        errorCode = p.readInt();

        SmsResponse response = new SmsResponse(messageRef, ackPDU, errorCode);

        return response;
    }


    private Object
    responseICC_IO(Parcel p) {
        int sw1, sw2;
        Message ret;

        sw1 = p.readInt();
        sw2 = p.readInt();

        String s = p.readString();

        if (RILJ_LOGV) riljLog("< iccIO: "
                + " 0x" + Integer.toHexString(sw1)
                + " 0x" + Integer.toHexString(sw2) + " "
                + s);

        return new IccIoResult(sw1, sw2, s);
    }

    private Object
    responseICC_IOBase64(Parcel p) {
        int sw1, sw2;
        Message ret;

        sw1 = p.readInt();
        sw2 = p.readInt();

        String s = p.readString();

        if (RILJ_LOGV) riljLog("< iccIO: "
                + " 0x" + Integer.toHexString(sw1)
                + " 0x" + Integer.toHexString(sw2) + " "
                + s);


        return new IccIoResult(sw1, sw2, android.util.Base64.decode(s, android.util.Base64.DEFAULT));
    }

    private Object
    responseIccCardStatus(Parcel p) {
        IccCardApplicationStatus appStatus;

        IccCardStatus cardStatus = new IccCardStatus();
        cardStatus.setCardState(p.readInt());
        cardStatus.setUniversalPinState(p.readInt());
        cardStatus.mGsmUmtsSubscriptionAppIndex = p.readInt();
        cardStatus.mCdmaSubscriptionAppIndex = p.readInt();
        cardStatus.mImsSubscriptionAppIndex = p.readInt();
        int numApplications = p.readInt();

        // limit to maximum allowed applications
        if (numApplications > IccCardStatus.CARD_MAX_APPS) {
            numApplications = IccCardStatus.CARD_MAX_APPS;
        }
        cardStatus.mApplications = new IccCardApplicationStatus[numApplications];
        for (int i = 0 ; i < numApplications ; i++) {
            appStatus = new IccCardApplicationStatus();
            appStatus.app_type       = appStatus.AppTypeFromRILInt(p.readInt());
            appStatus.app_state      = appStatus.AppStateFromRILInt(p.readInt());
            appStatus.perso_substate = appStatus.PersoSubstateFromRILInt(p.readInt());
            appStatus.aid            = p.readString();
            appStatus.app_label      = p.readString();
            appStatus.pin1_replaced  = p.readInt();
            appStatus.pin1           = appStatus.PinStateFromRILInt(p.readInt());
            appStatus.pin2           = appStatus.PinStateFromRILInt(p.readInt());
            cardStatus.mApplications[i] = appStatus;
        }
        return cardStatus;
    }

    private Object
    responseSimRefresh(Parcel p) {
        IccRefreshResponse response = new IccRefreshResponse();

        response.refreshResult = p.readInt();
        response.efId   = p.readInt();
        response.aid = p.readString();
        return response;
    }

    private Object
    responseCallList(Parcel p) {
        int num;
        int voiceSettings;
        ArrayList<DriverCall> response;
        DriverCall dc;

        num = p.readInt();
        response = new ArrayList<DriverCall>(num);

        if (RILJ_LOGV) {
            riljLog("responseCallList: num=" + num +
                    " mEmergencyCallbackModeRegistrant=" + mEmergencyCallbackModeRegistrant +
                    " mTestingEmergencyCall=" + mTestingEmergencyCall.get());
        }
        for (int i = 0 ; i < num ; i++) {
            dc = new DriverCall();

            dc.state = DriverCall.stateFromCLCC(p.readInt());
            dc.index = p.readInt();
            dc.TOA = p.readInt();
            dc.isMpty = (0 != p.readInt());
            dc.isMT = (0 != p.readInt());
            dc.als = p.readInt();
            voiceSettings = p.readInt();
            dc.isVoice = (0 == voiceSettings) ? false : true;
            dc.isVoicePrivacy = (0 != p.readInt());
            dc.number = p.readString();
            int np = p.readInt();
            dc.numberPresentation = DriverCall.presentationFromCLIP(np);
            dc.name = p.readString();
            // according to ril.h, namePresentation should be handled as numberPresentation;
            dc.namePresentation = DriverCall.presentationFromCLIP(p.readInt());
            int uusInfoPresent = p.readInt();
            if (uusInfoPresent == 1) {
                dc.uusInfo = new UUSInfo();
                dc.uusInfo.setType(p.readInt());
                dc.uusInfo.setDcs(p.readInt());
                byte[] userData = p.createByteArray();
                dc.uusInfo.setUserData(userData);
                riljLogv(String.format("Incoming UUS : type=%d, dcs=%d, length=%d",
                                dc.uusInfo.getType(), dc.uusInfo.getDcs(),
                                dc.uusInfo.getUserData().length));
                riljLogv("Incoming UUS : data (string)="
                        + new String(dc.uusInfo.getUserData()));
                riljLogv("Incoming UUS : data (hex): "
                        + IccUtils.bytesToHexString(dc.uusInfo.getUserData()));
            } else {
                riljLogv("Incoming UUS : NOT present!");
            }

            // Make sure there's a leading + on addresses with a TOA of 145
            dc.number = PhoneNumberUtils.stringFromStringAndTOA(dc.number, dc.TOA);

            response.add(dc);

            if (dc.isVoicePrivacy) {
                mVoicePrivacyOnRegistrants.notifyRegistrants();
                riljLog("InCall VoicePrivacy is enabled");
            } else {
                mVoicePrivacyOffRegistrants.notifyRegistrants();
                riljLog("InCall VoicePrivacy is disabled");
            }
        }

        Collections.sort(response);

        if ((num == 0) && mTestingEmergencyCall.getAndSet(false)) {
            if (mEmergencyCallbackModeRegistrant != null) {
                riljLog("responseCallList: call ended, testing emergency call," +
                            " notify ECM Registrants");
                mEmergencyCallbackModeRegistrant.notifyRegistrant();
            }
        }

        return response;
    }

    private DataCallResponse getDataCallResponse(Parcel p, int version) {
        DataCallResponse dataCall = new DataCallResponse();

        dataCall.version = version;
        if (version < 5) {
            dataCall.cid = p.readInt();
            dataCall.active = p.readInt();
            dataCall.type = p.readString();
            String addresses = p.readString();
            if (!TextUtils.isEmpty(addresses)) {
                dataCall.addresses = addresses.split(" ");
            }
        } else {
            dataCall.status = p.readInt();
            dataCall.suggestedRetryTime = p.readInt();
            dataCall.cid = p.readInt();
            dataCall.active = p.readInt();
            dataCall.type = p.readString();
            dataCall.ifname = p.readString();
            if ((dataCall.status == DcFailCause.NONE.getErrorCode()) &&
                    TextUtils.isEmpty(dataCall.ifname)) {
              throw new RuntimeException("getDataCallResponse, no ifname");
            }
            String addresses = p.readString();
            if (!TextUtils.isEmpty(addresses)) {
                dataCall.addresses = addresses.split(" ");
            }
            String dnses = p.readString();
            if (!TextUtils.isEmpty(dnses)) {
                dataCall.dnses = dnses.split(" ");
            }
            String gateways = p.readString();
            if (!TextUtils.isEmpty(gateways)) {
                dataCall.gateways = gateways.split(" ");
            }


            //VoLTE
            //For dedicate bearer support, add the magic number 1000
            if (version > 1000) {
                String pcscf = p.readString();
                if (!TextUtils.isEmpty(pcscf)) {
                    dataCall.pcscf = pcscf.split(" ");
                }

                //read dedicate bearer information (if any)
                Object response = responseSetupDedicateDataCall(p);
                if (response != null) {
                    if (response instanceof DedicateDataCallState) {
                        dataCall.concatenateDataCallState.add((DedicateDataCallState)response);
                    } else {
                        DedicateDataCallState[] concatenateDataCallState = (DedicateDataCallState[])response;
                        for (int i=0,length=concatenateDataCallState.length; i<length; i++)
                            dataCall.concatenateDataCallState.add(concatenateDataCallState[i]);
                    }
            }

                //read default bearer information
                DedicateDataCallState dedicateDataCall = new DedicateDataCallState();
                dedicateDataCall = (DedicateDataCallState) responseSetupDedicateDataCall(p);
                dataCall.defaultBearerDataCallState = dedicateDataCall;

                riljLog("[DefaultBearer: " + dedicateDataCall.interfaceId + ", " + dedicateDataCall.defaultCid + ", " + dedicateDataCall.cid + ", " + dedicateDataCall.active +
                ", " + dedicateDataCall.signalingFlag + ", " + dedicateDataCall.failCause + ", Qos" + dedicateDataCall.qosStatus + ", Tft" + dedicateDataCall.tftStatus +
                ", PCSCF" + dedicateDataCall.pcscfInfo);
            }
        }
        return dataCall;
    }

    private Object
    responseDataCallList(Parcel p) {
        ArrayList<DataCallResponse> response;

        int ver = p.readInt();
        int num = p.readInt();
        riljLog("responseDataCallList ver=" + ver + " num=" + num);

        response = new ArrayList<DataCallResponse>(num);
        for (int i = 0; i < num; i++) {
            response.add(getDataCallResponse(p, ver));
        }

        return response;
    }

    private Object
    responseSetupDataCall(Parcel p) {
        int ver = p.readInt();
        int num = p.readInt();
        if (RILJ_LOGV) riljLog("responseSetupDataCall ver=" + ver + " num=" + num);

        DataCallResponse dataCall;

        if (ver < 5) {
            dataCall = new DataCallResponse();
            dataCall.version = ver;
            dataCall.cid = Integer.parseInt(p.readString());
            dataCall.ifname = p.readString();
            if (TextUtils.isEmpty(dataCall.ifname)) {
                throw new RuntimeException(
                        "RIL_REQUEST_SETUP_DATA_CALL response, no ifname");
            }
            String addresses = p.readString();
            if (!TextUtils.isEmpty(addresses)) {
              dataCall.addresses = addresses.split(" ");
            }
            if (num >= 4) {
                String dnses = p.readString();
                if (RILJ_LOGD) riljLog("responseSetupDataCall got dnses=" + dnses);
                if (!TextUtils.isEmpty(dnses)) {
                    dataCall.dnses = dnses.split(" ");
                }
            }
            if (num >= 5) {
                String gateways = p.readString();
                if (RILJ_LOGD) riljLog("responseSetupDataCall got gateways=" + gateways);
                if (!TextUtils.isEmpty(gateways)) {
                    dataCall.gateways = gateways.split(" ");
                }
            }
            if (num >= 6) {
                String pcscf = p.readString();
                if (RILJ_LOGD) riljLog("responseSetupDataCall got pcscf=" + pcscf);
                if (!TextUtils.isEmpty(pcscf)) {
                    dataCall.pcscf = pcscf.split(" ");
                }
            }
        } else {
            if (num != 1) {
                throw new RuntimeException(
                        "RIL_REQUEST_SETUP_DATA_CALL response expecting 1 RIL_Data_Call_response_v5"
                        + " got " + num);
            }
            dataCall = getDataCallResponse(p, ver);
        }

        return dataCall;
    }

    // VoLTE
    private Object
    responseDeactivateDataCall(Parcel p) {
        int [] cidArray = null;
        if (p.dataSize() > 0 ) {    //checking Parcel data value
            cidArray = (int []) responseInts(p);
        }

        return cidArray;
    }

    private Object
    responseOperatorInfos(Parcel p) {
        String strings[] = (String [])responseStrings(p);
        ArrayList<OperatorInfo> ret;

        if (strings.length % 4 != 0) {
            throw new RuntimeException(
                "RIL_REQUEST_QUERY_AVAILABLE_NETWORKS: invalid response. Got "
                + strings.length + " strings, expected multible of 4");
        }

        ret = new ArrayList<OperatorInfo>(strings.length / 4);

        for (int i = 0 ; i < strings.length ; i += 4) {
            ret.add (
                new OperatorInfo(
                    strings[i+0],
                    strings[i+1],
                    strings[i+2],
                    strings[i+3]));
        }

        return ret;
    }

    private Object
    responseOperatorInfosWithAct(Parcel p) {
        String strings[] = (String []) responseStrings(p);
        ArrayList<OperatorInfo> ret;

        if (strings.length % 5 != 0) {
            throw new RuntimeException(
                "RIL_REQUEST_QUERY_AVAILABLE_NETWORKS_WITH_ACT: invalid response. Got "
                + strings.length + " strings, expected multible of 5");
        }

        String lacStr = SystemProperties.get("gsm.cops.lac");
        boolean lacValid = false;
        int lacIndex = 0;

        Rlog.d(RILJ_LOG_TAG, "lacStr = " + lacStr + " lacStr.length=" + lacStr.length() + " strings.length=" + strings.length);
        if ((lacStr.length() > 0) && (lacStr.length() % 4 == 0) && ((lacStr.length() / 4) == (strings.length / 5))) {
            Rlog.d(RILJ_LOG_TAG, "lacValid set to true");
            lacValid = true;
        }

        SystemProperties.set("gsm.cops.lac", ""); //reset property

        ret = new ArrayList<OperatorInfo>(strings.length / 5);

        for (int i = 0 ; i < strings.length ; i += 5) {
            /* Default display manufacturer maintained operator name table */
            if (strings[i + 2] != null) {
                strings[i + 0] = SpnOverride.getInstance().lookupOperatorName(SubscriptionManager.getSubIdUsingPhoneId(mInstanceId), strings[i + 2], true, mContext);
                strings[i + 1] = SpnOverride.getInstance().lookupOperatorName(SubscriptionManager.getSubIdUsingPhoneId(mInstanceId), strings[i + 2], false, mContext);
                riljLog("lookup RIL responseOperator(), longAlpha= " + strings[i + 0] + ",shortAlpha= " + strings[i + 1] + ",numeric=" + strings[i + 2]);
            }

            String longName = null;
            String shortName = null;
            /* Operator name from network MM information has higher priority to display */
            longName = lookupOperatorNameFromNetwork(SubscriptionManager.getSubIdUsingPhoneId(mInstanceId), strings[i + 2], true);
            shortName = lookupOperatorNameFromNetwork(SubscriptionManager.getSubIdUsingPhoneId(mInstanceId), strings[i + 2], false);
            if (longName != null) {
                strings[i + 0] = longName;
            }
            if (shortName != null) {
                strings[i + 1] = shortName;
            }
            riljLog("lookupOperatorNameFromNetwork in responseOperatorInfosWithAct(),updated longAlpha= " + strings[i + 0] + ",shortAlpha= " + strings[i + 1] + ",numeric=" + strings[i + 2]);

            /* Operator name from SIM (EONS/CPHS) has highest priority to display. This will be handled in GsmSST updateSpnDisplay() */
            /* ALPS00353868: To get operator name from OPL/PNN/CPHS, which need lac info */
            if ((lacValid == true) && (strings[i + 0] != null)) {
                UiccController uiccController = UiccController.getInstance();
                SIMRecords simRecord = (SIMRecords) uiccController.getIccRecords(mInstanceId, UiccController.APP_FAM_3GPP);
                int lacValue = -1;
                String sEons = null;
                String lac = lacStr.substring(lacIndex, lacIndex + 4);
                Rlog.d(RILJ_LOG_TAG, "lacIndex=" + lacIndex + " lacValue=" + lacValue + " lac=" + lac + " plmn numeric=" + strings[i + 2] + " plmn name" + strings[i + 0]);

                if (lac != "") {
                    lacValue = Integer.parseInt(lac, 16);
                    lacIndex += 4;
                    if (lacValue != 0xfffe) {
                        sEons = simRecord.getEonsIfExist(strings[i + 2], lacValue, true);
                        if (sEons != null) {
                            strings[i + 0] = sEons;
                            Rlog.d(RILJ_LOG_TAG, "plmn name update to Eons: " + strings[i + 0]);
                        } else {
                            String sCphsOns = null;
                            sCphsOns = simRecord.getSIMCPHSOns();
                            Rlog.d(RILJ_LOG_TAG, "plmn name NOT update to Eons");

                            if (sCphsOns != null) {
                                strings[i + 0] = sCphsOns;
                                Rlog.d(RILJ_LOG_TAG, "plmn name update to CPHS Ons: " + strings[i + 0]);
                            }
                        }
                    } else {
                        Rlog.d(RILJ_LOG_TAG, "invalid lac ignored");
                    }
                }
            }
            // ALPS00353868 END

            /* ALPS01597054 Always show Act info(ex: "2G","3G","4G") for PLMN list result */
            strings[i + 0] = strings[i + 0].concat(" " + strings[i + 4]);
            strings[i + 1] = strings[i + 1].concat(" " + strings[i + 4]);

            ret.add(
                new OperatorInfo(
                    strings[i + 0],
                    strings[i + 1],
                    strings[i + 2],
                    strings[i + 3]));
        }
        return ret;
    }

    private Object
    responseCellList(Parcel p) {
       int num, rssi;
       String location;
       ArrayList<NeighboringCellInfo> response;
       NeighboringCellInfo cell;

       num = p.readInt();
       response = new ArrayList<NeighboringCellInfo>();

       // ALPS00269882 START
       // Get the radio access type
       int radioType = SystemProperties.getInt("gsm.enbr.rat", NETWORK_TYPE_GPRS);
       riljLog("gsm.enbr.rat=" + radioType);
       // ALPS00269882 END

       // Interpret the location based on radio access type
       if (radioType != NETWORK_TYPE_UNKNOWN) {
           for (int i = 0 ; i < num ; i++) {
               rssi = p.readInt();
               location = p.readString();
               cell = new NeighboringCellInfo(rssi, location, radioType);
               response.add(cell);
           }
       }
       return response;
    }

    private Object responseGetPreferredNetworkType(Parcel p) {
       int [] response = (int[]) responseInts(p);

       if (response.length >= 1) {
           // Since this is the response for getPreferredNetworkType
           // we'll assume that it should be the value we want the
           // vendor ril to take if we reestablish a connection to it.
           //mPreferredNetworkType = response[0];
       }
       return response;
    }

    private Object responseGmsBroadcastConfig(Parcel p) {
        int num;
        ArrayList<SmsBroadcastConfigInfo> response;
        SmsBroadcastConfigInfo info;

        num = p.readInt();
        response = new ArrayList<SmsBroadcastConfigInfo>(num);

        for (int i = 0; i < num; i++) {
            int fromId = p.readInt();
            int toId = p.readInt();
            int fromScheme = p.readInt();
            int toScheme = p.readInt();
            boolean selected = (p.readInt() == 1);

            info = new SmsBroadcastConfigInfo(fromId, toId, fromScheme,
                    toScheme, selected);
            response.add(info);
        }
        return response;
    }

    private Object
    responseCdmaBroadcastConfig(Parcel p) {
        int numServiceCategories;
        int response[];

        numServiceCategories = p.readInt();

        if (numServiceCategories == 0) {
            // TODO: The logic of providing default values should
            // not be done by this transport layer. And needs to
            // be done by the vendor ril or application logic.
            int numInts;
            numInts = CDMA_BROADCAST_SMS_NO_OF_SERVICE_CATEGORIES * CDMA_BSI_NO_OF_INTS_STRUCT + 1;
            response = new int[numInts];

            // Faking a default record for all possible records.
            response[0] = CDMA_BROADCAST_SMS_NO_OF_SERVICE_CATEGORIES;

            // Loop over CDMA_BROADCAST_SMS_NO_OF_SERVICE_CATEGORIES set 'english' as
            // default language and selection status to false for all.
            for (int i = 1; i < numInts; i += CDMA_BSI_NO_OF_INTS_STRUCT ) {
                response[i + 0] = i / CDMA_BSI_NO_OF_INTS_STRUCT;
                response[i + 1] = 1;
                response[i + 2] = 0;
            }
        } else {
            int numInts;
            numInts = (numServiceCategories * CDMA_BSI_NO_OF_INTS_STRUCT) + 1;
            response = new int[numInts];

            response[0] = numServiceCategories;
            for (int i = 1 ; i < numInts; i++) {
                 response[i] = p.readInt();
             }
        }

        return response;
    }

    private Object
    responseSignalStrength(Parcel p) {
        // Assume this is gsm, but doesn't matter as ServiceStateTracker
        // sets the proper value.
        SignalStrength signalStrength = SignalStrength.makeSignalStrengthFromRilParcel(p);
        return signalStrength;
    }

    private ArrayList<CdmaInformationRecords>
    responseCdmaInformationRecord(Parcel p) {
        int numberOfInfoRecs;
        ArrayList<CdmaInformationRecords> response;

        /**
         * Loop through all of the information records unmarshalling them
         * and converting them to Java Objects.
         */
        numberOfInfoRecs = p.readInt();
        response = new ArrayList<CdmaInformationRecords>(numberOfInfoRecs);

        for (int i = 0; i < numberOfInfoRecs; i++) {
            CdmaInformationRecords InfoRec = new CdmaInformationRecords(p);
            response.add(InfoRec);
        }

        return response;
    }

    private Object
    responseCdmaCallWaiting(Parcel p) {
        CdmaCallWaitingNotification notification = new CdmaCallWaitingNotification();

        notification.number = p.readString();
        notification.numberPresentation =
                CdmaCallWaitingNotification.presentationFromCLIP(p.readInt());
        notification.name = p.readString();
        notification.namePresentation = notification.numberPresentation;
        notification.isPresent = p.readInt();
        notification.signalType = p.readInt();
        notification.alertPitch = p.readInt();
        notification.signal = p.readInt();
        notification.numberType = p.readInt();
        notification.numberPlan = p.readInt();

        return notification;
    }

    private Object
    responseCallRing(Parcel p){
        char response[] = new char[4];

        response[0] = (char) p.readInt();    // isPresent
        response[1] = (char) p.readInt();    // signalType
        response[2] = (char) p.readInt();    // alertPitch
        response[3] = (char) p.readInt();    // signal

        return response;
    }

    //MTK-START Femtocell (CSG)
    private Object
    responseFemtoCellInfos(Parcel p) {
        String strings[] = (String []) responseStrings(p);
        ArrayList<FemtoCellInfo> ret;

        if (strings.length % 6 != 0) {
            throw new RuntimeException(
                "RIL_REQUEST_GET_FEMTOCELL_LIST: invalid response. Got "
                + strings.length + " strings, expected multible of 6");
        }

        ret = new ArrayList<FemtoCellInfo>(strings.length / 6);

        /* <plmn numeric>,<act>,<plmn long alpha name>,<csgId>,,csgIconType>,<hnbName> */
        for (int i = 0 ; i < strings.length ; i += 6) {
            String actStr;
            String hnbName;
            int rat;

            /* ALPS00273663 handle UCS2 format name : prefix + hex string ex: "uCs2806F767C79D1" */
            if ((strings[i + 1] != null) && (strings[i + 1].startsWith("uCs2") == true))
            {
                Rlog.d(RILJ_LOG_TAG, "responseOperatorInfos handling UCS2 format name");

                try {
                    strings[i + 0] = new String(IccUtils.hexStringToBytes(strings[i + 1].substring(4)), "UTF-16");
                } catch (UnsupportedEncodingException ex) {
                    Rlog.d(RILJ_LOG_TAG, "responseOperatorInfos UnsupportedEncodingException");
                }
            }

            if (strings[i + 1] != null && (strings[i + 1].equals("") || strings[i + 1].equals(strings[i + 0]))) {
                Rlog.d(RILJ_LOG_TAG, "lookup RIL responseFemtoCellInfos() for plmn id= " + strings[i + 0]);
                strings[i + 1] = SpnOverride.getInstance().lookupOperatorName(SubscriptionManager.getSubIdUsingPhoneId(mInstanceId), strings[i + 0], true, mContext);
            }

            if (strings[i + 2].equals("7")) {
                actStr = "4G";
                rat = ServiceState.RIL_RADIO_TECHNOLOGY_LTE;
            } else if (strings[i + 2].equals("2")) {
                actStr = "3G";
                rat = ServiceState.RIL_RADIO_TECHNOLOGY_UMTS;
            } else {
                actStr = "2G";
                rat = ServiceState.RIL_RADIO_TECHNOLOGY_GPRS;
            }

            //1 and 2 is 2g. above 2 is 3g
            String property_name = "gsm.baseband.capability";
            if (mInstanceId > PhoneConstants.SIM_ID_1) {
                property_name = property_name + (mInstanceId + 1) ;
            }

            int basebandCapability = SystemProperties.getInt(property_name, 3);
            Rlog.d(RILJ_LOG_TAG, "property_name=" + property_name + ",basebandCapability=" + basebandCapability);
            if (3 < basebandCapability) {
                strings[i + 1] = strings[i + 1].concat(" " + actStr);
            }

            hnbName = new String(IccUtils.hexStringToBytes(strings[i + 5]));

            Rlog.d(RILJ_LOG_TAG, "FemtoCellInfo(" + strings[i + 3] + "," + strings[i + 4] + "," + strings[i + 5] + "," + strings[i + 0] + "," + strings[i + 1] + "," + rat + ")" + "hnbName=" + hnbName);

            ret.add(
                new FemtoCellInfo(
                    Integer.parseInt(strings[i + 3]),
                    Integer.parseInt(strings[i + 4]),
                    hnbName,
                    strings[i + 0],
                    strings[i + 1],
                    rat));
        }

        return ret;
    }
    //MTK-END Femtocell (CSG)

    private void
    notifyRegistrantsCdmaInfoRec(CdmaInformationRecords infoRec) {
        int response = RIL_UNSOL_CDMA_INFO_REC;
        if (infoRec.record instanceof CdmaInformationRecords.CdmaDisplayInfoRec) {
            if (mDisplayInfoRegistrants != null) {
                if (RILJ_LOGD) unsljLogRet(response, infoRec.record);
                mDisplayInfoRegistrants.notifyRegistrants(
                        new AsyncResult (null, infoRec.record, null));
            }
        } else if (infoRec.record instanceof CdmaInformationRecords.CdmaSignalInfoRec) {
            if (mSignalInfoRegistrants != null) {
                if (RILJ_LOGD) unsljLogRet(response, infoRec.record);
                mSignalInfoRegistrants.notifyRegistrants(
                        new AsyncResult (null, infoRec.record, null));
            }
        } else if (infoRec.record instanceof CdmaInformationRecords.CdmaNumberInfoRec) {
            if (mNumberInfoRegistrants != null) {
                if (RILJ_LOGD) unsljLogRet(response, infoRec.record);
                mNumberInfoRegistrants.notifyRegistrants(
                        new AsyncResult (null, infoRec.record, null));
            }
        } else if (infoRec.record instanceof CdmaInformationRecords.CdmaRedirectingNumberInfoRec) {
            if (mRedirNumInfoRegistrants != null) {
                if (RILJ_LOGD) unsljLogRet(response, infoRec.record);
                mRedirNumInfoRegistrants.notifyRegistrants(
                        new AsyncResult (null, infoRec.record, null));
            }
        } else if (infoRec.record instanceof CdmaInformationRecords.CdmaLineControlInfoRec) {
            if (mLineControlInfoRegistrants != null) {
                if (RILJ_LOGD) unsljLogRet(response, infoRec.record);
                mLineControlInfoRegistrants.notifyRegistrants(
                        new AsyncResult (null, infoRec.record, null));
            }
        } else if (infoRec.record instanceof CdmaInformationRecords.CdmaT53ClirInfoRec) {
            if (mT53ClirInfoRegistrants != null) {
                if (RILJ_LOGD) unsljLogRet(response, infoRec.record);
                mT53ClirInfoRegistrants.notifyRegistrants(
                        new AsyncResult (null, infoRec.record, null));
            }
        } else if (infoRec.record instanceof CdmaInformationRecords.CdmaT53AudioControlInfoRec) {
            if (mT53AudCntrlInfoRegistrants != null) {
               if (RILJ_LOGD) unsljLogRet(response, infoRec.record);
               mT53AudCntrlInfoRegistrants.notifyRegistrants(
                       new AsyncResult (null, infoRec.record, null));
            }
        }
    }

    private ArrayList<CellInfo> responseCellInfoList(Parcel p) {
        int numberOfInfoRecs;
        ArrayList<CellInfo> response;

        /**
         * Loop through all of the information records unmarshalling them
         * and converting them to Java Objects.
         */
        numberOfInfoRecs = p.readInt();
        response = new ArrayList<CellInfo>(numberOfInfoRecs);

        for (int i = 0; i < numberOfInfoRecs; i++) {
            CellInfo InfoRec = CellInfo.CREATOR.createFromParcel(p);
            response.add(InfoRec);
        }

        return response;
    }

   private Object
   responseHardwareConfig(Parcel p) {
      int num;
      ArrayList<HardwareConfig> response;
      HardwareConfig hw;

      num = p.readInt();
      response = new ArrayList<HardwareConfig>(num);

      if (RILJ_LOGV) {
         riljLog("responseHardwareConfig: num=" + num);
      }
      for (int i = 0 ; i < num ; i++) {
         int type = p.readInt();
         switch(type) {
            case HardwareConfig.DEV_HARDWARE_TYPE_MODEM: {
               hw = new HardwareConfig(type);
               hw.assignModem(p.readString(), p.readInt(), p.readInt(),
                  p.readInt(), p.readInt(), p.readInt(), p.readInt());
               break;
            }
            case HardwareConfig.DEV_HARDWARE_TYPE_SIM: {
               hw = new HardwareConfig(type);
               hw.assignSim(p.readString(), p.readInt(), p.readString());
               break;
            }
            default: {
               throw new RuntimeException(
                  "RIL_REQUEST_GET_HARDWARE_CONFIG invalid hardward type:" + type);
            }
         }

         response.add(hw);
      }

      return response;
   }

    private Object
    responsePhoneId() {
        return mInstanceId;
    }

    private Object
    responseSetPhoneRatChanged(Parcel p) {
        int num = p.readInt();
        int rat = p.readInt();
        PhoneRatFamily response = new PhoneRatFamily(mInstanceId, rat);

        return response;
    }

    static String
    requestToString(int request) {
/*
 cat libs/telephony/ril_commands.h \
 | egrep "^ *{RIL_" \
 | sed -re 's/\{RIL_([^,]+),[^,]+,([^}]+).+/case RIL_\1: return "\1";/'
*/
        switch(request) {
            case RIL_REQUEST_GET_SIM_STATUS: return "GET_SIM_STATUS";
            case RIL_REQUEST_ENTER_SIM_PIN: return "ENTER_SIM_PIN";
            case RIL_REQUEST_ENTER_SIM_PUK: return "ENTER_SIM_PUK";
            case RIL_REQUEST_ENTER_SIM_PIN2: return "ENTER_SIM_PIN2";
            case RIL_REQUEST_ENTER_SIM_PUK2: return "ENTER_SIM_PUK2";
            case RIL_REQUEST_CHANGE_SIM_PIN: return "CHANGE_SIM_PIN";
            case RIL_REQUEST_CHANGE_SIM_PIN2: return "CHANGE_SIM_PIN2";
            case RIL_REQUEST_ENTER_NETWORK_DEPERSONALIZATION: return "ENTER_NETWORK_DEPERSONALIZATION";
            case RIL_REQUEST_GET_CURRENT_CALLS: return "GET_CURRENT_CALLS";
            case RIL_REQUEST_DIAL: return "DIAL";
            case RIL_REQUEST_GET_IMSI: return "GET_IMSI";
            case RIL_REQUEST_HANGUP: return "HANGUP";
            case RIL_REQUEST_HANGUP_WAITING_OR_BACKGROUND: return "HANGUP_WAITING_OR_BACKGROUND";
            case RIL_REQUEST_HANGUP_FOREGROUND_RESUME_BACKGROUND: return "HANGUP_FOREGROUND_RESUME_BACKGROUND";
            case RIL_REQUEST_SWITCH_WAITING_OR_HOLDING_AND_ACTIVE: return "REQUEST_SWITCH_WAITING_OR_HOLDING_AND_ACTIVE";
            case RIL_REQUEST_CONFERENCE: return "CONFERENCE";
            case RIL_REQUEST_UDUB: return "UDUB";
            case RIL_REQUEST_LAST_CALL_FAIL_CAUSE: return "LAST_CALL_FAIL_CAUSE";
            case RIL_REQUEST_SIGNAL_STRENGTH: return "SIGNAL_STRENGTH";
            case RIL_REQUEST_VOICE_REGISTRATION_STATE: return "VOICE_REGISTRATION_STATE";
            case RIL_REQUEST_DATA_REGISTRATION_STATE: return "DATA_REGISTRATION_STATE";
            case RIL_REQUEST_OPERATOR: return "OPERATOR";
            case RIL_REQUEST_RADIO_POWER: return "RADIO_POWER";
            case RIL_REQUEST_DTMF: return "DTMF";
            case RIL_REQUEST_SEND_SMS: return "SEND_SMS";
            case RIL_REQUEST_SEND_SMS_EXPECT_MORE: return "SEND_SMS_EXPECT_MORE";
            case RIL_REQUEST_SETUP_DATA_CALL: return "SETUP_DATA_CALL";
            case RIL_REQUEST_SIM_IO: return "SIM_IO";
            case RIL_REQUEST_SEND_USSD: return "SEND_USSD";
            /* M: SS part */
            ///M: For query CNAP
            case RIL_REQUEST_SEND_CNAP: return "SEND_CNAP";
            /* M: SS part end */
            case RIL_REQUEST_CANCEL_USSD: return "CANCEL_USSD";
            case RIL_REQUEST_GET_CLIR: return "GET_CLIR";
            case RIL_REQUEST_SET_CLIR: return "SET_CLIR";
            case RIL_REQUEST_QUERY_CALL_FORWARD_STATUS: return "QUERY_CALL_FORWARD_STATUS";
            case RIL_REQUEST_SET_CALL_FORWARD: return "SET_CALL_FORWARD";
            case RIL_REQUEST_QUERY_CALL_WAITING: return "QUERY_CALL_WAITING";
            case RIL_REQUEST_SET_CALL_WAITING: return "SET_CALL_WAITING";
            case RIL_REQUEST_SMS_ACKNOWLEDGE: return "SMS_ACKNOWLEDGE";
            case RIL_REQUEST_GET_IMEI: return "GET_IMEI";
            case RIL_REQUEST_GET_IMEISV: return "GET_IMEISV";
            case RIL_REQUEST_ANSWER: return "ANSWER";
            case RIL_REQUEST_DEACTIVATE_DATA_CALL: return "DEACTIVATE_DATA_CALL";
            case RIL_REQUEST_QUERY_FACILITY_LOCK: return "QUERY_FACILITY_LOCK";
            case RIL_REQUEST_SET_FACILITY_LOCK: return "SET_FACILITY_LOCK";
            case RIL_REQUEST_CHANGE_BARRING_PASSWORD: return "CHANGE_BARRING_PASSWORD";
            case RIL_REQUEST_QUERY_NETWORK_SELECTION_MODE: return "QUERY_NETWORK_SELECTION_MODE";
            case RIL_REQUEST_SET_NETWORK_SELECTION_AUTOMATIC: return "SET_NETWORK_SELECTION_AUTOMATIC";
            case RIL_REQUEST_SET_NETWORK_SELECTION_MANUAL: return "SET_NETWORK_SELECTION_MANUAL";
            case RIL_REQUEST_QUERY_AVAILABLE_NETWORKS : return "QUERY_AVAILABLE_NETWORKS ";
            case RIL_REQUEST_ABORT_QUERY_AVAILABLE_NETWORKS : return "ABORT_QUERY_AVAILABLE_NETWORKS";
            case RIL_REQUEST_DTMF_START: return "DTMF_START";
            case RIL_REQUEST_DTMF_STOP: return "DTMF_STOP";
            case RIL_REQUEST_BASEBAND_VERSION: return "BASEBAND_VERSION";
            case RIL_REQUEST_SEPARATE_CONNECTION: return "SEPARATE_CONNECTION";
            case RIL_REQUEST_SET_MUTE: return "SET_MUTE";
            case RIL_REQUEST_GET_MUTE: return "GET_MUTE";
            case RIL_REQUEST_QUERY_CLIP: return "QUERY_CLIP";
            case RIL_REQUEST_LAST_DATA_CALL_FAIL_CAUSE: return "LAST_DATA_CALL_FAIL_CAUSE";
            case RIL_REQUEST_DATA_CALL_LIST: return "DATA_CALL_LIST";
            case RIL_REQUEST_RESET_RADIO: return "RESET_RADIO";
            case RIL_REQUEST_OEM_HOOK_RAW: return "OEM_HOOK_RAW";
            case RIL_REQUEST_OEM_HOOK_STRINGS: return "OEM_HOOK_STRINGS";
            case RIL_REQUEST_SCREEN_STATE: return "SCREEN_STATE";
            case RIL_REQUEST_SET_SUPP_SVC_NOTIFICATION: return "SET_SUPP_SVC_NOTIFICATION";
            case RIL_REQUEST_WRITE_SMS_TO_SIM: return "WRITE_SMS_TO_SIM";
            case RIL_REQUEST_DELETE_SMS_ON_SIM: return "DELETE_SMS_ON_SIM";
            case RIL_REQUEST_SET_BAND_MODE: return "SET_BAND_MODE";
            case RIL_REQUEST_QUERY_AVAILABLE_BAND_MODE: return "QUERY_AVAILABLE_BAND_MODE";
            case RIL_REQUEST_STK_GET_PROFILE: return "REQUEST_STK_GET_PROFILE";
            case RIL_REQUEST_STK_SET_PROFILE: return "REQUEST_STK_SET_PROFILE";
            case RIL_REQUEST_STK_SEND_ENVELOPE_COMMAND: return "REQUEST_STK_SEND_ENVELOPE_COMMAND";
            case RIL_REQUEST_STK_SEND_TERMINAL_RESPONSE: return "REQUEST_STK_SEND_TERMINAL_RESPONSE";
            case RIL_REQUEST_STK_HANDLE_CALL_SETUP_REQUESTED_FROM_SIM: return "REQUEST_STK_HANDLE_CALL_SETUP_REQUESTED_FROM_SIM";
            case RIL_REQUEST_EXPLICIT_CALL_TRANSFER: return "REQUEST_EXPLICIT_CALL_TRANSFER";
            case RIL_REQUEST_SET_PREFERRED_NETWORK_TYPE: return "REQUEST_SET_PREFERRED_NETWORK_TYPE";
            case RIL_REQUEST_GET_PREFERRED_NETWORK_TYPE: return "REQUEST_GET_PREFERRED_NETWORK_TYPE";
            case RIL_REQUEST_GET_NEIGHBORING_CELL_IDS: return "REQUEST_GET_NEIGHBORING_CELL_IDS";
            case RIL_REQUEST_SET_LOCATION_UPDATES: return "REQUEST_SET_LOCATION_UPDATES";
            case RIL_REQUEST_CDMA_SET_SUBSCRIPTION_SOURCE: return "RIL_REQUEST_CDMA_SET_SUBSCRIPTION_SOURCE";
            case RIL_REQUEST_CDMA_SET_ROAMING_PREFERENCE: return "RIL_REQUEST_CDMA_SET_ROAMING_PREFERENCE";
            case RIL_REQUEST_CDMA_QUERY_ROAMING_PREFERENCE: return "RIL_REQUEST_CDMA_QUERY_ROAMING_PREFERENCE";
            case RIL_REQUEST_SET_TTY_MODE: return "RIL_REQUEST_SET_TTY_MODE";
            case RIL_REQUEST_QUERY_TTY_MODE: return "RIL_REQUEST_QUERY_TTY_MODE";
            case RIL_REQUEST_CDMA_SET_PREFERRED_VOICE_PRIVACY_MODE: return "RIL_REQUEST_CDMA_SET_PREFERRED_VOICE_PRIVACY_MODE";
            case RIL_REQUEST_CDMA_QUERY_PREFERRED_VOICE_PRIVACY_MODE: return "RIL_REQUEST_CDMA_QUERY_PREFERRED_VOICE_PRIVACY_MODE";
            case RIL_REQUEST_CDMA_FLASH: return "RIL_REQUEST_CDMA_FLASH";
            case RIL_REQUEST_CDMA_BURST_DTMF: return "RIL_REQUEST_CDMA_BURST_DTMF";
            case RIL_REQUEST_CDMA_SEND_SMS: return "RIL_REQUEST_CDMA_SEND_SMS";
            case RIL_REQUEST_CDMA_SMS_ACKNOWLEDGE: return "RIL_REQUEST_CDMA_SMS_ACKNOWLEDGE";
            case RIL_REQUEST_GSM_GET_BROADCAST_CONFIG: return "RIL_REQUEST_GSM_GET_BROADCAST_CONFIG";
            case RIL_REQUEST_GSM_SET_BROADCAST_CONFIG: return "RIL_REQUEST_GSM_SET_BROADCAST_CONFIG";
            case RIL_REQUEST_CDMA_GET_BROADCAST_CONFIG: return "RIL_REQUEST_CDMA_GET_BROADCAST_CONFIG";
            case RIL_REQUEST_CDMA_SET_BROADCAST_CONFIG: return "RIL_REQUEST_CDMA_SET_BROADCAST_CONFIG";
            case RIL_REQUEST_GSM_BROADCAST_ACTIVATION: return "RIL_REQUEST_GSM_BROADCAST_ACTIVATION";
            case RIL_REQUEST_CDMA_VALIDATE_AND_WRITE_AKEY: return "RIL_REQUEST_CDMA_VALIDATE_AND_WRITE_AKEY";
            case RIL_REQUEST_CDMA_BROADCAST_ACTIVATION: return "RIL_REQUEST_CDMA_BROADCAST_ACTIVATION";
            case RIL_REQUEST_CDMA_SUBSCRIPTION: return "RIL_REQUEST_CDMA_SUBSCRIPTION";
            case RIL_REQUEST_CDMA_WRITE_SMS_TO_RUIM: return "RIL_REQUEST_CDMA_WRITE_SMS_TO_RUIM";
            case RIL_REQUEST_CDMA_DELETE_SMS_ON_RUIM: return "RIL_REQUEST_CDMA_DELETE_SMS_ON_RUIM";
            case RIL_REQUEST_DEVICE_IDENTITY: return "RIL_REQUEST_DEVICE_IDENTITY";
            case RIL_REQUEST_GET_SMSC_ADDRESS: return "RIL_REQUEST_GET_SMSC_ADDRESS";
            case RIL_REQUEST_SET_SMSC_ADDRESS: return "RIL_REQUEST_SET_SMSC_ADDRESS";
            case RIL_REQUEST_EXIT_EMERGENCY_CALLBACK_MODE: return "REQUEST_EXIT_EMERGENCY_CALLBACK_MODE";
            case RIL_REQUEST_REPORT_SMS_MEMORY_STATUS: return "RIL_REQUEST_REPORT_SMS_MEMORY_STATUS";
            case RIL_REQUEST_REPORT_STK_SERVICE_IS_RUNNING: return "RIL_REQUEST_REPORT_STK_SERVICE_IS_RUNNING";
            case RIL_REQUEST_CDMA_GET_SUBSCRIPTION_SOURCE: return "RIL_REQUEST_CDMA_GET_SUBSCRIPTION_SOURCE";
            case RIL_REQUEST_ISIM_AUTHENTICATION: return "RIL_REQUEST_ISIM_AUTHENTICATION";
            case RIL_REQUEST_ACKNOWLEDGE_INCOMING_GSM_SMS_WITH_PDU: return "RIL_REQUEST_ACKNOWLEDGE_INCOMING_GSM_SMS_WITH_PDU";
            case RIL_REQUEST_STK_SEND_ENVELOPE_WITH_STATUS: return "RIL_REQUEST_STK_SEND_ENVELOPE_WITH_STATUS";
            case RIL_REQUEST_VOICE_RADIO_TECH: return "RIL_REQUEST_VOICE_RADIO_TECH";
            case RIL_REQUEST_GET_CELL_INFO_LIST: return "RIL_REQUEST_GET_CELL_INFO_LIST";
            case RIL_REQUEST_SET_UNSOL_CELL_INFO_LIST_RATE: return "RIL_REQUEST_SET_CELL_INFO_LIST_RATE";
            case RIL_REQUEST_SET_INITIAL_ATTACH_APN: return "RIL_REQUEST_SET_INITIAL_ATTACH_APN";
            case RIL_REQUEST_SET_DATA_PROFILE: return "RIL_REQUEST_SET_DATA_PROFILE";
            case RIL_REQUEST_IMS_REGISTRATION_STATE: return "RIL_REQUEST_IMS_REGISTRATION_STATE";
            case RIL_REQUEST_IMS_SEND_SMS: return "RIL_REQUEST_IMS_SEND_SMS";
            case RIL_REQUEST_SIM_TRANSMIT_APDU_BASIC: return "RIL_REQUEST_SIM_TRANSMIT_APDU_BASIC";
            case RIL_REQUEST_SIM_OPEN_CHANNEL: return "RIL_REQUEST_SIM_OPEN_CHANNEL";
            case RIL_REQUEST_SIM_CLOSE_CHANNEL: return "RIL_REQUEST_SIM_CLOSE_CHANNEL";
            case RIL_REQUEST_SIM_TRANSMIT_APDU_CHANNEL: return "RIL_REQUEST_SIM_TRANSMIT_APDU_CHANNEL";
            case RIL_REQUEST_NV_READ_ITEM: return "RIL_REQUEST_NV_READ_ITEM";
            case RIL_REQUEST_NV_WRITE_ITEM: return "RIL_REQUEST_NV_WRITE_ITEM";
            case RIL_REQUEST_NV_WRITE_CDMA_PRL: return "RIL_REQUEST_NV_WRITE_CDMA_PRL";
            case RIL_REQUEST_NV_RESET_CONFIG: return "RIL_REQUEST_NV_RESET_CONFIG";
            case RIL_REQUEST_SET_UICC_SUBSCRIPTION: return "RIL_REQUEST_SET_UICC_SUBSCRIPTION";
            case RIL_REQUEST_ALLOW_DATA: return "RIL_REQUEST_ALLOW_DATA";
            case RIL_REQUEST_GET_HARDWARE_CONFIG: return "GET_HARDWARE_CONFIG";
            case RIL_REQUEST_SIM_AUTHENTICATION: return "RIL_REQUEST_SIM_AUTHENTICATION";
            case RIL_REQUEST_SHUTDOWN: return "RIL_REQUEST_SHUTDOWN";
            case RIL_REQUEST_SET_PHONE_RAT_FAMILY: return "RIL_REQUEST_SET_PHONE_RAT_FAMILY";
            case RIL_REQUEST_GET_PHONE_RAT_FAMILY: return "RIL_REQUEST_GET_PHONE_RAT_FAMILY";

            /* M: call control part start */
            case RIL_REQUEST_HANGUP_ALL: return "HANGUP_ALL";
            case RIL_REQUEST_FORCE_RELEASE_CALL: return "FORCE_RELEASE_CALL";
            case RIL_REQUEST_SET_CALL_INDICATION: return "SET_CALL_INDICATION";
            case RIL_REQUEST_EMERGENCY_DIAL: return "EMERGENCY_DIAL";
            case RIL_REQUEST_SET_ECC_SERVICE_CATEGORY: return "SET_ECC_SERVICE_CATEGORY";
            case RIL_REQUEST_SET_ECC_LIST: return "SET_ECC_LIST";
            case RIL_REQUEST_SET_SPEECH_CODEC_INFO: return "SET_SPEECH_CODEC_INFO";
            /* M: call control part end */

            /// M: IMS feature. @{
            case RIL_REQUEST_ADD_IMS_CONFERENCE_CALL_MEMBER: return "RIL_REQUEST_ADD_IMS_CONFERENCE_CALL_MEMBER";
            case RIL_REQUEST_REMOVE_IMS_CONFERENCE_CALL_MEMBER: return "RIL_REQUEST_REMOVE_IMS_CONFERENCE_CALL_MEMBER";
            case RIL_REQUEST_DIAL_WITH_SIP_URI: return "RIL_REQUEST_DIAL_WITH_SIP_URI";
            case RIL_REQUEST_RETRIEVE_HELD_CALL: return "RIL_REQUEST_RETRIEVE_HELD_CALL";
            /// @}

            //MTK-START SIM ME lock
            case RIL_REQUEST_QUERY_SIM_NETWORK_LOCK: return "QUERY_SIM_NETWORK_LOCK";
            case RIL_REQUEST_SET_SIM_NETWORK_LOCK: return "SET_SIM_NETWORK_LOCK";
            //MTK-END SIM ME lock
            //ISIM
            case RIL_REQUEST_GENERAL_SIM_AUTH: return "RIL_REQUEST_GENERAL_SIM_AUTH";
            case RIL_REQUEST_OPEN_ICC_APPLICATION: return "RIL_REQUEST_OPEN_ICC_APPLICATION";
            case RIL_REQUEST_GET_ICC_APPLICATION_STATUS: return "RIL_REQUEST_GET_ICC_APPLICATION_STATUS";
            case RIL_REQUEST_SIM_IO_EX: return "SIM_IO_EX";

            // PHB Start
            case RIL_REQUEST_QUERY_PHB_STORAGE_INFO: return "RIL_REQUEST_QUERY_PHB_STORAGE_INFO";
            case RIL_REQUEST_WRITE_PHB_ENTRY: return "RIL_REQUEST_WRITE_PHB_ENTRY";
            case RIL_REQUEST_READ_PHB_ENTRY: return "RIL_REQUEST_READ_PHB_ENTRY";
            case RIL_REQUEST_QUERY_UPB_CAPABILITY: return "RIL_REQUEST_QUERY_UPB_CAPABILITY";
            case RIL_REQUEST_EDIT_UPB_ENTRY: return "RIL_REQUEST_EDIT_UPB_ENTRY";
            case RIL_REQUEST_DELETE_UPB_ENTRY: return "RIL_REQUEST_DELETE_UPB_ENTRY";
            case RIL_REQUEST_READ_UPB_GAS_LIST: return "RIL_REQUEST_READ_UPB_GAS_LIST";
            case RIL_REQUEST_READ_UPB_GRP: return "RIL_REQUEST_READ_UPB_GRP";
            case RIL_REQUEST_WRITE_UPB_GRP: return "RIL_REQUEST_WRITE_UPB_GRP";
            case RIL_REQUEST_GET_PHB_STRING_LENGTH: return "RIL_REQUEST_GET_PHB_STRING_LENGTH";
            case RIL_REQUEST_GET_PHB_MEM_STORAGE: return "RIL_REQUEST_GET_PHB_MEM_STORAGE";
            case RIL_REQUEST_SET_PHB_MEM_STORAGE: return "RIL_REQUEST_SET_PHB_MEM_STORAGE";
            case RIL_REQUEST_READ_PHB_ENTRY_EXT: return "RIL_REQUEST_READ_PHB_ENTRY_EXT";
            case RIL_REQUEST_WRITE_PHB_ENTRY_EXT: return "RIL_REQUEST_WRITE_PHB_ENTRY_EXT";
            // PHB End

            /* M: network part start */
            case RIL_REQUEST_SET_NETWORK_SELECTION_MANUAL_WITH_ACT: return "SET_NETWORK_SELECTION_MANUAL_WITH_ACT";
            case RIL_REQUEST_GET_POL_CAPABILITY: return "RIL_REQUEST_GET_POL_CAPABILITY";
            case RIL_REQUEST_GET_POL_LIST: return "RIL_REQUEST_GET_POL_LIST";
            case RIL_REQUEST_SET_POL_ENTRY: return "RIL_REQUEST_SET_POL_ENTRY";
            case RIL_REQUEST_SET_TRM: return "RIL_REQUEST_SET_TRM";
            case RIL_REQUEST_QUERY_AVAILABLE_NETWORKS_WITH_ACT : return "QUERY_AVAILABLE_NETWORKS_WITH_ACT";
            //Femtocell (CSG) feature START
            case RIL_REQUEST_GET_FEMTOCELL_LIST: return "RIL_REQUEST_GET_FEMTOCELL_LIST";
            case RIL_REQUEST_ABORT_FEMTOCELL_LIST: return "RIL_REQUEST_ABORT_FEMTOCELL_LIST";
            case RIL_REQUEST_SELECT_FEMTOCELL: return "RIL_REQUEST_SELECT_FEMTOCELL";
            //Femtocell (CSG) feature END
            /* M: network part end */
            case RIL_REQUEST_STK_EVDL_CALL_BY_AP: return "RIL_REQUEST_STK_EVDL_CALL_BY_AP";
            case RIL_REQUEST_QUERY_MODEM_TYPE: return "RIL_REQUEST_QUERY_MODEM_TYPE";
            case RIL_REQUEST_STORE_MODEM_TYPE: return "RIL_REQUEST_STORE_MODEM_TYPE";
            case RIL_REQUEST_SIM_GET_ATR: return "SIM_GET_ATR";
            case RIL_REQUEST_SIM_OPEN_CHANNEL_WITH_SW: return "SIM_OPEN_CHANNEL_WITH_SW";
            //VoLTE
            case RIL_REQUEST_SETUP_DEDICATE_DATA_CALL: return "RIL_REQUEST_SETUP_DEDICATE_DATA_CALL";
            case RIL_REQUEST_DEACTIVATE_DEDICATE_DATA_CALL: return "RIL_REQUEST_DEACTIVATE_DEDICATE_DATA_CALL";
            case RIL_REQUEST_MODIFY_DATA_CALL: return "RIL_REQUEST_MODIFY_DATA_CALL";
            case RIL_REQUEST_ABORT_SETUP_DATA_CALL: return "RIL_REQUEST_ABORT_SETUP_DATA_CALL";
            case RIL_REQUEST_PCSCF_DISCOVERY_PCO: return "RIL_REQUEST_PCSCF_DISCOVERY_PCO";
            case RIL_REQUEST_CLEAR_DATA_BEARER: return "RIL_REQUEST_CLEAR_DATA_BEARER";

            // IMS
            case RIL_REQUEST_SET_IMS_ENABLE: return "RIL_REQUEST_SET_IMS_ENABLE";

            // M: Fast Dormancy
            case RIL_REQUEST_SET_SCRI: return "RIL_REQUEST_SET_SCRI";
            case RIL_REQUEST_SET_FD_MODE: return "RIL_REQUEST_SET_FD_MODE";
            // MTK-START, SMS part
            case RIL_REQUEST_GET_SMS_PARAMS: return "RIL_REQUEST_GET_SMS_PARAMS";
            case RIL_REQUEST_SET_SMS_PARAMS: return "RIL_REQUEST_SET_SMS_PARAMS";
            case RIL_REQUEST_GET_SMS_SIM_MEM_STATUS: return "RIL_REQUEST_GET_SMS_SIM_MEM_STATUS";
            case RIL_REQUEST_SET_ETWS: return "RIL_REQUEST_SET_ETWS";
            case RIL_REQUEST_SET_CB_CHANNEL_CONFIG_INFO:
                return "RIL_REQUEST_SET_CB_CHANNEL_CONFIG_INFO";
            case RIL_REQUEST_SET_CB_LANGUAGE_CONFIG_INFO:
                return "RIL_REQUEST_SET_CB_LANGUAGE_CONFIG_INFO";
            case RIL_REQUEST_GET_CB_CONFIG_INFO: return "RIL_REQUEST_GET_CB_CONFIG_INFO";
            case RIL_REQUEST_REMOVE_CB_MESSAGE: return "RIL_REQUEST_REMOVE_CB_MESSAGE";
            // MTK-END, SMS part
            case RIL_REQUEST_SET_DATA_CENTRIC: return "RIL_REQUEST_SET_DATA_CENTRIC";

            case RIL_REQUEST_MODEM_POWEROFF: return "MODEM_POWEROFF";
            case RIL_REQUEST_MODEM_POWERON: return "MODEM_POWERON";
            // M: CC33 LTE.
            case RIL_REQUEST_SET_DATA_ON_TO_MD: return "RIL_REQUEST_SET_DATA_ON_TO_MD";
            case RIL_REQUEST_SET_REMOVE_RESTRICT_EUTRAN_MODE: return "RIL_REQUEST_SET_REMOVE_RESTRICT_EUTRAN_MODE";
            case RIL_REQUEST_BTSIM_CONNECT: return "RIL_REQUEST_BTSIM_CONNECT";
            case RIL_REQUEST_BTSIM_DISCONNECT_OR_POWEROFF: return "RIL_REQUEST_BTSIM_DISCONNECT_OR_POWEROFF";
            case RIL_REQUEST_BTSIM_POWERON_OR_RESETSIM: return "RIL_REQUEST_BTSIM_POWERON_OR_RESETSIM";
            case RIL_REQUEST_BTSIM_TRANSFERAPDU: return "RIL_REQUEST_SEND_BTSIM_TRANSFERAPDU";

            /* M: call control part start */
            case RIL_REQUEST_SET_IMS_CALL_STATUS: return "RIL_REQUEST_SET_IMS_CALL_STATUS";
            /* M: call control part end */
            

            /* M: C2K part start */
            case RIL_REQUEST_GET_NITZ_TIME: return "RIL_REQUEST_GET_NITZ_TIME";
            case RIL_REQUEST_QUERY_UIM_INSERTED: return "RIL_REQUEST_QUERY_UIM_INSERTED";
            case RIL_REQUEST_SWITCH_HPF: return "RIL_REQUEST_SWITCH_HPF";
            case RIL_REQUEST_SET_AVOID_SYS: return "RIL_REQUEST_SET_AVOID_SYS";
            case RIL_REQUEST_QUERY_AVOID_SYS: return "RIL_REQUEST_QUERY_AVOID_SYS";
            case RIL_REQUEST_QUERY_CDMA_NETWORK_INFO: return "RIL_REQUEST_QUERY_CDMA_NETWORK_INFO";
            case RIL_REQUEST_GET_LOCAL_INFO: return "RIL_REQUEST_GET_LOCAL_INFO";
            case RIL_REQUEST_UTK_REFRESH: return "RIL_REQUEST_UTK_REFRESH";
            case RIL_REQUEST_QUERY_SMS_AND_PHONEBOOK_STATUS:
                return "RIL_REQUEST_QUERY_SMS_AND_PHONEBOOK_STATUS";
            case RIL_REQUEST_QUERY_NETWORK_REGISTRATION:
                return "RIL_REQUEST_QUERY_NETWORK_REGISTRATION";
            case RIL_REQUEST_AGPS_TCP_CONNIND: return "RIL_REQUEST_AGPS_TCP_CONNIND";
            case RIL_REQUEST_AGPS_SET_MPC_IPPORT: return "RIL_REQUEST_AGPS_SET_MPC_IPPORT";
            case RIL_REQUEST_AGPS_GET_MPC_IPPORT: return "RIL_REQUEST_AGPS_GET_MPC_IPPORT";
            case RIL_REQUEST_SET_MEID: return "RIL_REQUEST_SET_MEID";
            case RIL_REQUEST_SET_REG_RESUME: return "RIL_REQUEST_SET_REG_RESUME";
            case RIL_REQUEST_ENABLE_REG_PAUSE: return "RIL_REQUEST_ENABLE_REG_PAUSE";
            case RIL_REQUEST_SET_ETS_DEV: return "RIL_REQUEST_SET_ETS_DEV";
            case RIL_REQUEST_WRITE_MDN: return "RIL_REQUEST_WRITE_MDN";
            case RIL_REQUEST_SET_VIA_TRM: return "RIL_REQUEST_SET_VIA_TRM";
            case RIL_REQUEST_SET_ARSI_THRESHOLD: return "RIL_REQUEST_SET_ARSI_THRESHOLD";
            /* M: C2K part end */
            default: return "<unknown request>";
        }
    }

    static String
    responseToString(int request)
    {
/*
 cat libs/telephony/ril_unsol_commands.h \
 | egrep "^ *{RIL_" \
 | sed -re 's/\{RIL_([^,]+),[^,]+,([^}]+).+/case RIL_\1: return "\1";/'
*/
        switch(request) {
            case RIL_UNSOL_RESPONSE_RADIO_STATE_CHANGED: return "UNSOL_RESPONSE_RADIO_STATE_CHANGED";
            case RIL_UNSOL_RESPONSE_CALL_STATE_CHANGED: return "UNSOL_RESPONSE_CALL_STATE_CHANGED";
            case RIL_UNSOL_RESPONSE_VOICE_NETWORK_STATE_CHANGED: return "UNSOL_RESPONSE_VOICE_NETWORK_STATE_CHANGED";
            case RIL_UNSOL_RESPONSE_NEW_SMS: return "UNSOL_RESPONSE_NEW_SMS";
            case RIL_UNSOL_RESPONSE_NEW_SMS_STATUS_REPORT: return "UNSOL_RESPONSE_NEW_SMS_STATUS_REPORT";
            case RIL_UNSOL_RESPONSE_NEW_SMS_ON_SIM: return "UNSOL_RESPONSE_NEW_SMS_ON_SIM";
            case RIL_UNSOL_ON_USSD: return "UNSOL_ON_USSD";
            case RIL_UNSOL_ON_USSD_REQUEST: return "UNSOL_ON_USSD_REQUEST";
            case RIL_UNSOL_NITZ_TIME_RECEIVED: return "UNSOL_NITZ_TIME_RECEIVED";
            case RIL_UNSOL_SIGNAL_STRENGTH: return "UNSOL_SIGNAL_STRENGTH";
            case RIL_UNSOL_DATA_CALL_LIST_CHANGED: return "UNSOL_DATA_CALL_LIST_CHANGED";
            case RIL_UNSOL_SUPP_SVC_NOTIFICATION: return "UNSOL_SUPP_SVC_NOTIFICATION";
            case RIL_UNSOL_STK_SESSION_END: return "UNSOL_STK_SESSION_END";
            case RIL_UNSOL_STK_PROACTIVE_COMMAND: return "UNSOL_STK_PROACTIVE_COMMAND";
            case RIL_UNSOL_STK_EVENT_NOTIFY: return "UNSOL_STK_EVENT_NOTIFY";
            case RIL_UNSOL_STK_CALL_SETUP: return "UNSOL_STK_CALL_SETUP";
            case RIL_UNSOL_SIM_SMS_STORAGE_FULL: return "UNSOL_SIM_SMS_STORAGE_FULL";
            case RIL_UNSOL_SIM_REFRESH: return "UNSOL_SIM_REFRESH";
            case RIL_UNSOL_CALL_RING: return "UNSOL_CALL_RING";
            case RIL_UNSOL_RESPONSE_SIM_STATUS_CHANGED: return "UNSOL_RESPONSE_SIM_STATUS_CHANGED";
            case RIL_UNSOL_RESPONSE_CDMA_NEW_SMS: return "UNSOL_RESPONSE_CDMA_NEW_SMS";
            case RIL_UNSOL_RESPONSE_NEW_BROADCAST_SMS: return "UNSOL_RESPONSE_NEW_BROADCAST_SMS";
            case RIL_UNSOL_CDMA_RUIM_SMS_STORAGE_FULL: return "UNSOL_CDMA_RUIM_SMS_STORAGE_FULL";
            case RIL_UNSOL_RESTRICTED_STATE_CHANGED: return "UNSOL_RESTRICTED_STATE_CHANGED";
            case RIL_UNSOL_ENTER_EMERGENCY_CALLBACK_MODE: return "UNSOL_ENTER_EMERGENCY_CALLBACK_MODE";
            case RIL_UNSOL_CDMA_CALL_WAITING: return "UNSOL_CDMA_CALL_WAITING";
            case RIL_UNSOL_CDMA_OTA_PROVISION_STATUS: return "UNSOL_CDMA_OTA_PROVISION_STATUS";
            case RIL_UNSOL_CDMA_INFO_REC: return "UNSOL_CDMA_INFO_REC";
            case RIL_UNSOL_OEM_HOOK_RAW: return "UNSOL_OEM_HOOK_RAW";
            case RIL_UNSOL_RINGBACK_TONE: return "UNSOL_RINGBACK_TONE";
            case RIL_UNSOL_RESEND_INCALL_MUTE: return "UNSOL_RESEND_INCALL_MUTE";
            case RIL_UNSOL_CDMA_SUBSCRIPTION_SOURCE_CHANGED: return "CDMA_SUBSCRIPTION_SOURCE_CHANGED";
            case RIL_UNSOl_CDMA_PRL_CHANGED: return "UNSOL_CDMA_PRL_CHANGED";
            case RIL_UNSOL_EXIT_EMERGENCY_CALLBACK_MODE: return "UNSOL_EXIT_EMERGENCY_CALLBACK_MODE";
            case RIL_UNSOL_RIL_CONNECTED: return "UNSOL_RIL_CONNECTED";
            case RIL_UNSOL_VOICE_RADIO_TECH_CHANGED: return "UNSOL_VOICE_RADIO_TECH_CHANGED";
            case RIL_UNSOL_CELL_INFO_LIST: return "UNSOL_CELL_INFO_LIST";
            case RIL_UNSOL_RESPONSE_IMS_NETWORK_STATE_CHANGED:
                return "UNSOL_RESPONSE_IMS_NETWORK_STATE_CHANGED";
            case RIL_UNSOL_UICC_SUBSCRIPTION_STATUS_CHANGED:
                    return "RIL_UNSOL_UICC_SUBSCRIPTION_STATUS_CHANGED";
            case RIL_UNSOL_SRVCC_STATE_NOTIFY:
                    return "UNSOL_SRVCC_STATE_NOTIFY";
            case RIL_UNSOL_HARDWARE_CONFIG_CHANGED: return "RIL_UNSOL_HARDWARE_CONFIG_CHANGED";
            case RIL_UNSOL_SET_PHONE_RAT_FAMILY_COMPLETE:
                    return "RIL_UNSOL_SET_PHONE_RAT_FAMILY_COMPLETE";
            /* M: call control part start */
            case RIL_UNSOL_CALL_FORWARDING: return "UNSOL_CALL_FORWARDING";
            case RIL_UNSOL_CRSS_NOTIFICATION: return "UNSOL_CRSS_NOTIFICATION";
            case RIL_UNSOL_INCOMING_CALL_INDICATION: return "UNSOL_INCOMING_CALL_INDICATION";
            case RIL_UNSOL_CIPHER_INDICATION: return "UNSOL_CIPHER_INDICATION";
            case RIL_UNSOL_CNAP: return "UNSOL_CNAP";
            case RIL_UNSOL_SPEECH_CODEC_INFO: return "UNSOL_SPEECH_CODEC_INFO";
            /* M: call control part end */
            //MTK-START multiple application support
            case RIL_UNSOL_APPLICATION_SESSION_ID_CHANGED: return "RIL_UNSOL_APPLICATION_SESSION_ID_CHANGED";
            //MTK-END multiple application support
            case RIL_UNSOL_SIM_MISSING: return "UNSOL_SIM_MISSING";
            case RIL_UNSOL_VIRTUAL_SIM_ON: return "UNSOL_VIRTUAL_SIM_ON";
            case RIL_UNSOL_VIRTUAL_SIM_OFF: return "UNSOL_VIRTUAL_SIM_ON_OFF";
            case RIL_UNSOL_SIM_RECOVERY: return "UNSOL_SIM_RECOVERY";
            case RIL_UNSOL_SIM_PLUG_OUT: return "UNSOL_SIM_PLUG_OUT";
            case RIL_UNSOL_SIM_PLUG_IN: return "UNSOL_SIM_PLUG_IN";
            case RIL_UNSOL_SIM_COMMON_SLOT_NO_CHANGED: return "RIL_UNSOL_SIM_COMMON_SLOT_NO_CHANGED";
            case RIL_UNSOL_DATA_ALLOWED: return "RIL_UNSOL_DATA_ALLOWED";
            case RIL_UNSOL_PHB_READY_NOTIFICATION: return "UNSOL_PHB_READY_NOTIFICATION";
            case RIL_UNSOL_IMEI_LOCK: return "UNSOL_IMEI_LOCK";
            case RIL_UNSOL_RESPONSE_ACMT: return "UNSOL_ACMT_INFO";
            case RIL_UNSOL_RESPONSE_PS_NETWORK_STATE_CHANGED: return "UNSOL_RESPONSE_PS_NETWORK_STATE_CHANGED";
            case RIL_UNSOL_RESPONSE_MMRR_STATUS_CHANGED: return "UNSOL_RESPONSE_MMRR_STATUS_CHANGED";
            case RIL_UNSOL_NEIGHBORING_CELL_INFO: return "UNSOL_NEIGHBORING_CELL_INFO";
            case RIL_UNSOL_NETWORK_INFO: return "UNSOL_NETWORK_INFO";
            case RIL_UNSOL_INVALID_SIM: return "RIL_UNSOL_INVALID_SIM";
            case RIL_UNSOL_IMS_ENABLE_DONE: return "RIL_UNSOL_IMS_ENABLE_DONE";
            case RIL_UNSOL_IMS_DISABLE_DONE: return "RIL_UNSOL_IMS_DISABLE_DONE";
            case RIL_UNSOL_IMS_REGISTRATION_INFO: return "RIL_UNSOL_IMS_REGISTRATION_INFO";
            case RIL_UNSOL_STK_SETUP_MENU_RESET: return "RIL_UNSOL_STK_SETUP_MENU_RESET";
            case RIL_UNSOL_RESPONSE_PLMN_CHANGED: return "RIL_UNSOL_RESPONSE_PLMN_CHANGED";
            case RIL_UNSOL_RESPONSE_REGISTRATION_SUSPENDED: return "RIL_UNSOL_RESPONSE_REGISTRATION_SUSPENDED";
            //VoLTE
            case RIL_UNSOL_DEDICATE_BEARER_ACTIVATED: return "RIL_UNSOL_DEDICATE_BEARER_ACTIVATED";
            case RIL_UNSOL_DEDICATE_BEARER_MODIFIED: return "RIL_UNSOL_DEDICATE_BEARER_MODIFIED";
            //Remote SIM ME lock related APIs [Start]
            case RIL_UNSOL_MELOCK_NOTIFICATION: return "RIL_UNSOL_MELOCK_NOTIFICATION";
            //Remote SIM ME lock related APIs [End]
            // M: Fast Dormancy
            case RIL_UNSOL_SCRI_RESULT: return "RIL_UNSOL_SCRI_RESULT";
            case RIL_UNSOL_STK_EVDL_CALL: return "RIL_UNSOL_STK_EVDL_CALL";
            case RIL_UNSOL_STK_CALL_CTRL: return "RIL_UNSOL_STK_CALL_CTRL";

            /// M: IMS feature. @{
            case RIL_UNSOL_ECONF_SRVCC_INDICATION: return "RIL_UNSOL_ECONF_SRVCC_INDICATION";
            //For updating conference call merged/added result.
            case RIL_UNSOL_ECONF_RESULT_INDICATION: return "RIL_UNSOL_ECONF_RESULT_INDICATION";
            //For updating call mode and pau information.
            case RIL_UNSOL_CALL_INFO_INDICATION : return "RIL_UNSOL_CALL_INFO_INDICATION";
            /// @}

            case RIL_UNSOL_VOLTE_EPS_NETWORK_FEATURE_INFO: return "RIL_UNSOL_VOLTE_EPS_NETWORK_FEATURE_INFO";
            case RIL_UNSOL_SRVCC_HANDOVER_INFO_INDICATION: return "RIL_UNSOL_SRVCC_HANDOVER_INFO_INDICATION";
            // M: CC33 LTE.
            case RIL_UNSOL_RAC_UPDATE: return "RIL_UNSOL_RAC_UPDATE";
            case RIL_UNSOL_REMOVE_RESTRICT_EUTRAN: return "RIL_UNSOL_REMOVE_RESTRICT_EUTRAN";

            //MTK-START for MD state change
            case RIL_UNSOL_MD_STATE_CHANGE: return "RIL_UNSOL_MD_STATE_CHANGE";
            //MTK-END for MD state change
            
            case RIL_UNSOL_MO_DATA_BARRING_INFO: return "RIL_UNSOL_MO_DATA_BARRING_INFO";
            case RIL_UNSOL_SSAC_BARRING_INFO: return "RIL_UNSOL_SSAC_BARRING_INFO";
//MTK_TC1_FEATURE for LGE CSMCC_MO_CALL_MODIFIED { 
			case RIL_UNSOL_RESPONSE_MO_CALL_STATE_CHANGED: return "UNSOL_RESPONSE_MO_CALL_STATE_CHANGED";
//}
            /* M: C2K part start */
            case RIL_UNSOL_CDMA_CALL_ACCEPTED: return "RIL_UNSOL_CDMA_CALL_ACCEPTED";
            case RIL_UNSOL_UTK_SESSION_END: return "RIL_UNSOL_UTK_SESSION_END";
            case RIL_UNSOL_UTK_PROACTIVE_COMMAND: return "RIL_UNSOL_UTK_PROACTIVE_COMMAND";
            case RIL_UNSOL_UTK_EVENT_NOTIFY: return "RIL_UNSOL_UTK_EVENT_NOTIFY";
            case RIL_UNSOL_VIA_GPS_EVENT: return "RIL_UNSOL_VIA_GPS_EVENT";
            case RIL_UNSOL_VIA_NETWORK_TYPE_CHANGE: return "RIL_UNSOL_VIA_NETWORK_TYPE_CHANGE";
            case RIL_UNSOL_VIA_PLMN_CHANGE_REG_PAUSE: return "RIL_UNSOL_VIA_PLMN_CHANGE_REG_PAUSE";
            case RIL_UNSOL_VIA_INVALID_SIM_DETECTED: return "RIL_UNSOL_VIA_INVALID_SIM_DETECTED";
            /* M: C2K part end */
            default: return "<unknown response>";
        }
    }

    private void riljLog(String msg) {
        Rlog.d(RILJ_LOG_TAG, msg
                + (mInstanceId != null ? (" [SUB" + mInstanceId + "]") : ""));
    }

    private void riljLogv(String msg) {
        Rlog.v(RILJ_LOG_TAG, msg
                + (mInstanceId != null ? (" [SUB" + mInstanceId + "]") : ""));
    }

    private void unsljLog(int response) {
        riljLog("[UNSL]< " + responseToString(response));
    }

    private void unsljLogMore(int response, String more) {
        riljLog("[UNSL]< " + responseToString(response) + " " + more);
    }

    private void unsljLogRet(int response, Object ret) {
        riljLog("[UNSL]< " + responseToString(response) + " " + retToString(response, ret));
    }

    private void unsljLogvRet(int response, Object ret) {
        riljLogv("[UNSL]< " + responseToString(response) + " " + retToString(response, ret));
    }


    // ***** Methods for CDMA support
    @Override
    public void
    getDeviceIdentity(Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_DEVICE_IDENTITY, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    @Override
    public void
    getCDMASubscription(Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_CDMA_SUBSCRIPTION, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    @Override
    public void setPhoneType(int phoneType) { // Called by CDMAPhone and GSMPhone constructor
        if (RILJ_LOGD) riljLog("setPhoneType=" + phoneType + " old value=" + mPhoneType);
        mPhoneType = phoneType;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void queryCdmaRoamingPreference(Message response) {
        RILRequest rr = RILRequest.obtain(
                RILConstants.RIL_REQUEST_CDMA_QUERY_ROAMING_PREFERENCE, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setCdmaRoamingPreference(int cdmaRoamingType, Message response) {
        RILRequest rr = RILRequest.obtain(
                RILConstants.RIL_REQUEST_CDMA_SET_ROAMING_PREFERENCE, response);

        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(cdmaRoamingType);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                + " : " + cdmaRoamingType);

        send(rr);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setCdmaSubscriptionSource(int cdmaSubscription , Message response) {
        RILRequest rr = RILRequest.obtain(
                RILConstants.RIL_REQUEST_CDMA_SET_SUBSCRIPTION_SOURCE, response);

        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(cdmaSubscription);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                + " : " + cdmaSubscription);

        send(rr);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void getCdmaSubscriptionSource(Message response) {
        RILRequest rr = RILRequest.obtain(
                RILConstants.RIL_REQUEST_CDMA_GET_SUBSCRIPTION_SOURCE, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void queryTTYMode(Message response) {
        RILRequest rr = RILRequest.obtain(
                RILConstants.RIL_REQUEST_QUERY_TTY_MODE, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setTTYMode(int ttyMode, Message response) {
        RILRequest rr = RILRequest.obtain(
                RILConstants.RIL_REQUEST_SET_TTY_MODE, response);

        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(ttyMode);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                + " : " + ttyMode);

        send(rr);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void
    sendCDMAFeatureCode(String FeatureCode, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_CDMA_FLASH, response);

        rr.mParcel.writeString(FeatureCode);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                + " : " + FeatureCode);

        send(rr);
    }

    @Override
    public void getCdmaBroadcastConfig(Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_CDMA_GET_BROADCAST_CONFIG, response);

        send(rr);
    }

    @Override
    public void setCdmaBroadcastConfig(CdmaSmsBroadcastConfigInfo[] configs, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_CDMA_SET_BROADCAST_CONFIG, response);

        // Convert to 1 service category per config (the way RIL takes is)
        ArrayList<CdmaSmsBroadcastConfigInfo> processedConfigs =
            new ArrayList<CdmaSmsBroadcastConfigInfo>();
        for (CdmaSmsBroadcastConfigInfo config : configs) {
            for (int i = config.getFromServiceCategory(); i <= config.getToServiceCategory(); i++) {
                processedConfigs.add(new CdmaSmsBroadcastConfigInfo(i,
                        i,
                        config.getLanguage(),
                        config.isSelected()));
            }
        }

        CdmaSmsBroadcastConfigInfo[] rilConfigs = processedConfigs.toArray(configs);
        rr.mParcel.writeInt(rilConfigs.length);
        for(int i = 0; i < rilConfigs.length; i++) {
            rr.mParcel.writeInt(rilConfigs[i].getFromServiceCategory());
            rr.mParcel.writeInt(rilConfigs[i].getLanguage());
            rr.mParcel.writeInt(rilConfigs[i].isSelected() ? 1 : 0);
        }

        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                    + " with " + rilConfigs.length + " configs : ");
            for (int i = 0; i < rilConfigs.length; i++) {
                riljLog(rilConfigs[i].toString());
            }
        }

        send(rr);
    }

    @Override
    public void setCdmaBroadcastActivation(boolean activate, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_CDMA_BROADCAST_ACTIVATION, response);

        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(activate ? 0 :1);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void exitEmergencyCallbackMode(Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_EXIT_EMERGENCY_CALLBACK_MODE, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    @Override
    public void requestIsimAuthentication(String nonce, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_ISIM_AUTHENTICATION, response);

        rr.mParcel.writeString(nonce);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    @Override
    public void requestIccSimAuthentication(int authContext, String data, String aid,
                                            Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SIM_AUTHENTICATION, response);

        rr.mParcel.writeInt(authContext);
        rr.mParcel.writeString(data);
        rr.mParcel.writeString(aid);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void getCellInfoList(Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_GET_CELL_INFO_LIST, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setCellInfoListRate(int rateInMillis, Message response) {
        if (RILJ_LOGD) riljLog("setCellInfoListRate: " + rateInMillis);
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_UNSOL_CELL_INFO_LIST_RATE, response);

        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(rateInMillis);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    /** M: add extra parameter */
    public void setInitialAttachApn(String apn, String protocol, int authType, String username,
            String password, Message result) {
        setInitialAttachApn(apn, protocol, authType, username, password, "", false, result);
    }

    public void setInitialAttachApn(String apn, String protocol, int authType, String username,
            String password, String operatorNumeric, boolean canHandleIms, Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_INITIAL_ATTACH_APN, null);

        if (RILJ_LOGD) riljLog("Set RIL_REQUEST_SET_INITIAL_ATTACH_APN");

        rr.mParcel.writeString(apn);
        rr.mParcel.writeString(protocol);
        rr.mParcel.writeInt(authType);
        rr.mParcel.writeString(username);
        rr.mParcel.writeString(password);

        /** M: start */
        rr.mParcel.writeString(operatorNumeric);
        rr.mParcel.writeInt(canHandleIms ? 1 : 0);
        /* M: end */

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                + ", apn:" + apn + ", protocol:" + protocol + ", authType:" + authType
                + ", username:" + username + ", password:" + password
                + ", operatorNumeric:" + operatorNumeric + ", canHandleIms:" + canHandleIms);

        send(rr);
    }

    @Override
    public void setInitialAttachApn(String apn, String protocol, int authType, String username,
                String password, String operatorNumeric, boolean canHandleIms,
                String[] dualApnPlmnList, Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_INITIAL_ATTACH_APN, null);

        if (RILJ_LOGD) { riljLog("Set RIL_REQUEST_SET_INITIAL_ATTACH_APN"); }

        rr.mParcel.writeString(apn);
        rr.mParcel.writeString(protocol);
        rr.mParcel.writeInt(authType);
        rr.mParcel.writeString(username);
        rr.mParcel.writeString(password);

        /** M: start */
        rr.mParcel.writeString(operatorNumeric);
        rr.mParcel.writeInt(canHandleIms ? 1 : 0);
        /* M: end */

        rr.mParcel.writeStringArray(dualApnPlmnList);

        if (RILJ_LOGD) { riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                + ", apn:" + apn + ", protocol:" + protocol + ", authType:" + authType
                + ", username:" + username + ", password:" + password
                + ", operatorNumeric:" + operatorNumeric
              + ", dualApnPlmnList:" + dualApnPlmnList + ", canHandleIms:" + canHandleIms);
        }

        send(rr);
    }


    public void setDataProfile(DataProfile[] dps, Message result) {
        if (RILJ_LOGD) riljLog("Set RIL_REQUEST_SET_DATA_PROFILE");

        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_DATA_PROFILE, null);
        DataProfile.toParcel(rr.mParcel, dps);

        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                    + " with " + dps + " Data Profiles : ");
            for (int i = 0; i < dps.length; i++) {
                riljLog(dps[i].toString());
            }
        }

        send(rr);
    }

    /* (non-Javadoc)
     * @see com.android.internal.telephony.BaseCommands#testingEmergencyCall()
     */
    @Override
    public void testingEmergencyCall() {
        if (RILJ_LOGD) riljLog("testingEmergencyCall");
        mTestingEmergencyCall.set(true);
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("RIL: " + this);
        pw.println(" mSocket=" + mSocket);
        pw.println(" mSenderThread=" + mSenderThread);
        pw.println(" mSender=" + mSender);
        pw.println(" mReceiverThread=" + mReceiverThread);
        pw.println(" mReceiver=" + mReceiver);
        pw.println(" mWakeLock=" + mWakeLock);
        pw.println(" mWakeLockTimeout=" + mWakeLockTimeout);
        synchronized (mRequestList) {
            synchronized (mWakeLock) {
                pw.println(" mWakeLockCount=" + mWakeLockCount);
            }
            int count = mRequestList.size();
            pw.println(" mRequestList count=" + count);
            for (int i = 0; i < count; i++) {
                RILRequest rr = mRequestList.valueAt(i);
                pw.println("  [" + rr.mSerial + "] " + requestToString(rr.mRequest));
            }
        }
        pw.println(" mLastNITZTimeInfo=" + mLastNITZTimeInfo);
        pw.println(" mTestingEmergencyCall=" + mTestingEmergencyCall.get());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void iccOpenLogicalChannel(String AID, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SIM_OPEN_CHANNEL, response);
        rr.mParcel.writeString(AID);

        if (RILJ_LOGD)
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void iccCloseLogicalChannel(int channel, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SIM_CLOSE_CHANNEL, response);
        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(channel);

        if (RILJ_LOGD)
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void iccTransmitApduLogicalChannel(int channel, int cla, int instruction,
            int p1, int p2, int p3, String data, Message response) {
        if (channel <= 0) {
            throw new RuntimeException(
                "Invalid channel in iccTransmitApduLogicalChannel: " + channel);
        }

        iccTransmitApduHelper(RIL_REQUEST_SIM_TRANSMIT_APDU_CHANNEL, channel, cla,
                instruction, p1, p2, p3, data, response);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void iccTransmitApduBasicChannel(int cla, int instruction, int p1, int p2,
            int p3, String data, Message response) {
        iccTransmitApduHelper(RIL_REQUEST_SIM_TRANSMIT_APDU_BASIC, 0, cla, instruction,
                p1, p2, p3, data, response);
    }

    /*
     * Helper function for the iccTransmitApdu* commands above.
     */
    private void iccTransmitApduHelper(int rilCommand, int channel, int cla,
            int instruction, int p1, int p2, int p3, String data, Message response) {
        RILRequest rr = RILRequest.obtain(rilCommand, response);
        rr.mParcel.writeInt(channel);
        rr.mParcel.writeInt(cla);
        rr.mParcel.writeInt(instruction);
        rr.mParcel.writeInt(p1);
        rr.mParcel.writeInt(p2);
        rr.mParcel.writeInt(p3);
        rr.mParcel.writeString(data);

        if (RILJ_LOGD)
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    @Override
    public void nvReadItem(int itemID, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_NV_READ_ITEM, response);

        rr.mParcel.writeInt(itemID);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                + ' ' + itemID);

        send(rr);
    }

    @Override
    public void nvWriteItem(int itemID, String itemValue, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_NV_WRITE_ITEM, response);

        rr.mParcel.writeInt(itemID);
        rr.mParcel.writeString(itemValue);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                + ' ' + itemID + ": " + itemValue);

        send(rr);
    }

    @Override
    public void nvWriteCdmaPrl(byte[] preferredRoamingList, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_NV_WRITE_CDMA_PRL, response);

        rr.mParcel.writeByteArray(preferredRoamingList);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                + " (" + preferredRoamingList.length + " bytes)");

        send(rr);
    }

    @Override
    public void nvResetConfig(int resetType, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_NV_RESET_CONFIG, response);

        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(resetType);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                + ' ' + resetType);

        send(rr);
    }

    @Override
    public void setPhoneRatFamily(int ratFamily, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_PHONE_RAT_FAMILY, response);

        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(ratFamily);

        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + ": " + ratFamily);
        }

        send(rr);
    }

    @Override
    public void getPhoneRatFamily(Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_GET_PHONE_RAT_FAMILY, response);

        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        }

        send(rr);
    }

    //MTK-START Support Multi-Application
    @Override
    public void openIccApplication(int application, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_OPEN_ICC_APPLICATION, response);

        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(application);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                + ", application = " + application);
        send(rr);
    }

    @Override
    public void getIccApplicationStatus(int sessionId, Message result) {
        //Note: This RIL request has not been renamed to ICC,
        //       but this request is also valid for SIM and RUIM
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_GET_ICC_APPLICATION_STATUS, result);

        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(sessionId);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                + ", session = " + sessionId);
        send(rr);
    }

    @Override public void
    iccIOForAppEx(int command, int fileid, String path, int p1, int p2, int p3,
            String data, String pin2, String aid, int channel , Message result) {
        //Note: This RIL request has not been renamed to ICC,
        //       but this request is also valid for SIM and RUIM
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_SIM_IO_EX, result);

        rr.mParcel.writeInt(command);
        rr.mParcel.writeInt(fileid);
        rr.mParcel.writeString(path);
        rr.mParcel.writeInt(p1);
        rr.mParcel.writeInt(p2);
        rr.mParcel.writeInt(p3);
        rr.mParcel.writeString(data);
        rr.mParcel.writeString(pin2);
        rr.mParcel.writeString(aid);
        rr.mParcel.writeInt(channel);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> iccIO: "
                + requestToString(rr.mRequest)
                + " 0x" + Integer.toHexString(command)
                + " 0x" + Integer.toHexString(fileid) + " "
                + " path: " + path + ","
                + p1 + "," + p2 + "," + p3 + ",channel:" + channel
                + " aid: " + aid);

        send(rr);
    }
    //MTK-END Support Multi-Application

    @Override public void
    queryNetworkLock(int category, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_QUERY_SIM_NETWORK_LOCK, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        riljLog("queryNetworkLock:" + category);

        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(category);

        send(rr);
    }

    @Override public void
    setNetworkLock(int catagory, int lockop, String password,
                        String data_imsi, String gid1, String gid2, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_SIM_NETWORK_LOCK, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        riljLog("setNetworkLock:" + catagory + ", " + lockop + ", " + password + ", " + data_imsi
                + ", " + gid1 + ", " + gid2);

        rr.mParcel.writeInt(6);
        rr.mParcel.writeString(Integer.toString(catagory));
        rr.mParcel.writeString(Integer.toString(lockop));
        if (null != password) {
            rr.mParcel.writeString(password);
        } else {
            rr.mParcel.writeString("");
        }
        rr.mParcel.writeString(data_imsi);
        rr.mParcel.writeString(gid1);
        rr.mParcel.writeString(gid2);

        send(rr);
    }

    @Override public void
    doGeneralSimAuthentication(int sessionId, int mode , int tag, String param1,
                                         String param2, Message response) {

        RILRequest rr = RILRequest.obtain(RIL_REQUEST_GENERAL_SIM_AUTH, response);

        rr.mParcel.writeInt(sessionId);
        rr.mParcel.writeInt(mode);

        // Calcuate param1 length in byte length
        if (param1 != null && param1.length() > 0) {
            String length = Integer.toHexString(param1.length() / 2);
            length = (((length.length() % 2 == 1) ? "0" : "") + length);
            // Session id is equal to 0, for backward compability, we use old AT command
            // old AT command no need to include param's length
            rr.mParcel.writeString(((sessionId == 0) ? param1 : (length + param1)));
        } else {
            rr.mParcel.writeString(param1);
        }

        // Calcuate param2 length in byte length
        if (param2 != null && param2.length() > 0) {
            String length = Integer.toHexString(param2.length() / 2);
            length = (((length.length() % 2 == 1) ? "0" : "") + length);
            // Session id is equal to 0, for backward compability, we use old AT command
            // old AT command no need to include param's length
            rr.mParcel.writeString(((sessionId == 0) ? param2 : (length + param2)));
        } else {
            rr.mParcel.writeString(param2);
        }

        if (mode == 1) {
            rr.mParcel.writeInt(tag);
        }


        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + ": " +
            "session = " + sessionId + ",mode = " + mode + ",tag = " + tag + ", "  + param1 + ", " + param2);

        send(rr);
    }
    // Added by M begin
    @Override
    public void
    iccGetATR(Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SIM_GET_ATR, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    public void
    iccOpenChannelWithSw(String AID, Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SIM_OPEN_CHANNEL_WITH_SW, result);

        rr.mParcel.writeString(AID);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> iccOpenChannelWithSw: " + requestToString(rr.mRequest)
                + " " + AID);

        send(rr);
    }

    /**
     * {@inheritDoc}
     */
    public void sendBTSIMProfile(int nAction, int nType, String strData, Message response) {
        if (RILJ_LOGD) riljLog(" sendBTSIMProfile nAction is " + nAction);
        switch (nAction) {
            case 0:
                requestConnectSIM(response);
                break;
            case 1:
                requestDisconnectOrPowerOffSIM(nAction, response);
                break;
            case 2:
                requestPowerOnOrResetSIM(nAction, nType, response);
                break;
            case 3:
                requestDisconnectOrPowerOffSIM(nAction, response);
                break;
            case 4:
                requestPowerOnOrResetSIM(nAction, nType, response);
                break;
            case 5:
                requestTransferApdu(nAction, nType, strData, response);
                break;
        }
    }

    //***** Private Methods
    /**
    * used only by sendBTSIMProfile
    */
    private void requestConnectSIM(Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_BTSIM_CONNECT, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    /**
    * used only by sendBTSIMProfile
    */
    private void requestDisconnectOrPowerOffSIM(int nAction, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_BTSIM_DISCONNECT_OR_POWEROFF, response);

         rr.mParcel.writeString(Integer.toString(nAction));

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + ": " + nAction);

        send(rr);
    }

    /**
    * used only by sendBTSIMProfile
    */
    private void requestPowerOnOrResetSIM(int nAction, int nType, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_BTSIM_POWERON_OR_RESETSIM, response);

        rr.mParcel.writeInt(2);
        rr.mParcel.writeString(Integer.toString(nAction));
        rr.mParcel.writeString(Integer.toString(nType));

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + ": "
                + nAction + " nType: " + nType);

        send(rr);
    }

    /**
    * used only by sendBTSIMProfile
    */
    private void requestTransferApdu(int nAction, int nType, String strData, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_BTSIM_TRANSFERAPDU, response);

        rr.mParcel.writeInt(3);
        rr.mParcel.writeString(Integer.toString(nAction));
        rr.mParcel.writeString(Integer.toString(nType));
        rr.mParcel.writeString(strData);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + ": "
                + nAction + " nType: " + nType + " data: " + strData);

        send(rr);
    }
    // Added by M end

    /**
     * {@inheritDoc}
     */
    public void queryPhbStorageInfo(int type, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_QUERY_PHB_STORAGE_INFO, response);

        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(type);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + ": " + type);

        send(rr);
    }

    /**
     * {@inheritDoc}
     */
    public void writePhbEntry(PhbEntry entry, Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_WRITE_PHB_ENTRY, result);

        rr.mParcel.writeInt(entry.type);
        rr.mParcel.writeInt(entry.index);
        rr.mParcel.writeString(entry.number);
        rr.mParcel.writeInt(entry.ton);
        rr.mParcel.writeString(entry.alphaId);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + ": " + entry);

        send(rr);
    }

    /**
     * {@inheritDoc}
     */
    public void ReadPhbEntry(int type, int bIndex, int eIndex, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_READ_PHB_ENTRY, response);

        rr.mParcel.writeInt(3);
        rr.mParcel.writeInt(type);
        rr.mParcel.writeInt(bIndex);
        rr.mParcel.writeInt(eIndex);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + ": "
                + type + " begin: " + bIndex + " end: " + eIndex);

        send(rr);
    }

    private Object
    responsePhbEntries(Parcel p) {
        int numerOfEntries;
        PhbEntry[] response;

        numerOfEntries = p.readInt();
        response = new PhbEntry[numerOfEntries];

        Rlog.d(RILJ_LOG_TAG, "Number: " + numerOfEntries);

        for (int i = 0; i < numerOfEntries; i++) {
            response[i] = new PhbEntry();
            response[i].type = p.readInt();
            response[i].index = p.readInt();
            response[i].number = p.readString();
            response[i].ton = p.readInt();
            response[i].alphaId = p.readString();
        }

        return response;
    }

    public void queryUPBCapability(Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_QUERY_UPB_CAPABILITY, response);
        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        send(rr);
    }

    public void editUPBEntry(int entryType, int adnIndex, int entryIndex, String strVal, String tonForNum, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_EDIT_UPB_ENTRY, response);
        if (entryType == 0) {
            rr.mParcel.writeInt(5);
        } else {
            rr.mParcel.writeInt(4);
        }
        rr.mParcel.writeString(Integer.toString(entryType));
        rr.mParcel.writeString(Integer.toString(adnIndex));
        rr.mParcel.writeString(Integer.toString(entryIndex));
        rr.mParcel.writeString(strVal);

        if (entryType == 0) {
            rr.mParcel.writeString(tonForNum);
        }

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        send(rr);

    }

    public void deleteUPBEntry(int entryType, int adnIndex, int entryIndex, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_DELETE_UPB_ENTRY, response);
        rr.mParcel.writeInt(3);
        rr.mParcel.writeInt(entryType);
        rr.mParcel.writeInt(adnIndex);
        rr.mParcel.writeInt(entryIndex);
        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        send(rr);
    }

    public void readUPBGasList(int startIndex, int endIndex, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_READ_UPB_GAS_LIST, response);
        rr.mParcel.writeInt(2);
        rr.mParcel.writeInt(startIndex);
        rr.mParcel.writeInt(endIndex);
        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        send(rr);
    }

    public void readUPBGrpEntry(int adnIndex, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_READ_UPB_GRP, response);
        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(adnIndex);
        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        send(rr);
    }

    public void writeUPBGrpEntry(int adnIndex, int[] grpIds, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_WRITE_UPB_GRP, response);
        int nLen = grpIds.length;
        rr.mParcel.writeInt(nLen + 1);
        rr.mParcel.writeInt(adnIndex);
        for (int i = 0; i < nLen; i++) {
            rr.mParcel.writeInt(grpIds[i]);
        }
        if (RILJ_LOGD) riljLog("writeUPBGrpEntry nLen is " + nLen);
        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        send(rr);

    }

    private Object responseGetPhbMemStorage(Parcel p) {
        PBMemStorage response = PBMemStorage.createFromParcel(p);
        riljLog("responseGetPhbMemStorage:" +  response);
        return response;
    }
    private Object responseReadPhbEntryExt(Parcel p) {
        int numerOfEntries;
        PBEntry[] response;

        numerOfEntries = p.readInt();
        response = new PBEntry[numerOfEntries];

        Rlog.d(RILJ_LOG_TAG, "responseReadPhbEntryExt Number: " + numerOfEntries);

        for (int i = 0; i < numerOfEntries; i++) {
            response[i] = new PBEntry();
            response[i].setIndex1(p.readInt());
            response[i].setNumber(p.readString());
            response[i].setType(p.readInt());
            response[i].setText(p.readString());
            response[i].setHidden(p.readInt());

            response[i].setGroup(p.readString());
            response[i].setAdnumber(p.readString());
            response[i].setAdtype(p.readInt());
            response[i].setSecondtext(p.readString());
            response[i].setEmail(p.readString());
            Rlog.d(RILJ_LOG_TAG, "responseReadPhbEntryExt[" + i + "] " + response[i].toString());
        }

        return response;
    }

    /**
     * at+cpbr=?
     * @return  <nlength><tlength><glength><slength><elength>
     */
    public void getPhoneBookStringsLength(Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_GET_PHB_STRING_LENGTH, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> :::" + requestToString(rr.mRequest));

        send(rr);
    }

    /**
     * at+cpbs?
     * @return  PBMemStorage :: +cpbs:<storage>,<used>,<total>
     */
    public void getPhoneBookMemStorage(Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_GET_PHB_MEM_STORAGE, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> :::" + requestToString(rr.mRequest));

        send(rr);
    }

    /**
     * at+epin2=<p2>; at+cpbs=<storage>
     * @return
     */
    public void setPhoneBookMemStorage(String storage, String password, Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_PHB_MEM_STORAGE, result);
        rr.mParcel.writeInt(2);
        rr.mParcel.writeString(storage);
        rr.mParcel.writeString(password);
        if (RILJ_LOGD) riljLog(rr.serialString() + "> :::" + requestToString(rr.mRequest));

        send(rr);
    }

    /**
     * M at+cpbr=<index1>,<index2>
     * +CPBR:<indexn>,<number>,<type>,<text>,<hidden>,<group>,<adnumber>,<adtype>,<secondtext>,<email>
     */
    public void readPhoneBookEntryExt(int index1, int index2, Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_READ_PHB_ENTRY_EXT, result);
        rr.mParcel.writeInt(2);
        rr.mParcel.writeInt(index1);
        rr.mParcel.writeInt(index2);
        if (RILJ_LOGD) riljLog(rr.serialString() + "> :::" + requestToString(rr.mRequest));
        send(rr);
    }

    /**
     * M AT+CPBW=<index>,<number>,<type>,<text>,<hidden>,<group>,<adnumber>,<adtype>,<secondtext>,<email>
     */
    public void writePhoneBookEntryExt(PBEntry entry, Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_WRITE_PHB_ENTRY_EXT, result);

        rr.mParcel.writeInt(entry.getIndex1());
        rr.mParcel.writeString(entry.getNumber());
        rr.mParcel.writeInt(entry.getType());
        rr.mParcel.writeString(entry.getText());
        rr.mParcel.writeInt(entry.getHidden());

        rr.mParcel.writeString(entry.getGroup());
        rr.mParcel.writeString(entry.getAdnumber());
        rr.mParcel.writeInt(entry.getAdtype());
        rr.mParcel.writeString(entry.getSecondtext());
        rr.mParcel.writeString(entry.getEmail());

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + ": " + entry);

        send(rr);
    }

    // MTK-START, SMS part
    /**
     * {@inheritDoc}
     */
    public void getSmsParameters(Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_GET_SMS_PARAMS, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    private Object
    responseSmsParams(Parcel p) {
        int format = p.readInt();
        int vp = p.readInt();
        int pid = p.readInt();
        int dcs = p.readInt();

        return new SmsParameters(format, vp, pid, dcs);
    }

    /**
     * {@inheritDoc}
     */
    public void setSmsParameters(SmsParameters params, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_SMS_PARAMS, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        rr.mParcel.writeInt(4);
        rr.mParcel.writeInt(params.format);
        rr.mParcel.writeInt(params.vp);
        rr.mParcel.writeInt(params.pid);
        rr.mParcel.writeInt(params.dcs);

        send(rr);
    }

    /**
     * {@inheritDoc}
     */
    public void getSmsSimMemoryStatus(Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_GET_SMS_SIM_MEM_STATUS, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    private Object responseSimSmsMemoryStatus(Parcel p) {
        IccSmsStorageStatus response;

        response = new IccSmsStorageStatus();
        response.mUsed = p.readInt();
        response.mTotal = p.readInt();
        return response;
    }

    /**
     * {@inheritDoc}
     */
    public void setEtws(int mode, Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_ETWS, result);

        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(mode);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + " " +
                mode);

        send(rr);
    }

    public void setCellBroadcastChannelConfigInfo(String config, int cb_set_type,
            Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_CB_CHANNEL_CONFIG_INFO, response);

        rr.mParcel.writeInt(2);
        rr.mParcel.writeString(config);
        rr.mParcel.writeString(Integer.toString(cb_set_type));
        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        send(rr);
    }

    public void setCellBroadcastLanguageConfigInfo(String config, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_CB_LANGUAGE_CONFIG_INFO, response);

        rr.mParcel.writeString(config);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        send(rr);
    }

    public void queryCellBroadcastConfigInfo(Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_GET_CB_CONFIG_INFO, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        send(rr);
    }

    private Object responseCbConfig(Parcel p) {
        int mode            = p.readInt();
        String channels     = p.readString();
        String languages    = p.readString();
        boolean allOn       = (p.readInt() == 1) ? true : false;

        return new CellBroadcastConfigInfo(mode, channels, languages, allOn);
    }

    public void removeCellBroadcastMsg(int channelId, int serialId, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_REMOVE_CB_MESSAGE, response);

        rr.mParcel.writeInt(2);
        rr.mParcel.writeInt(channelId);
        rr.mParcel.writeInt(serialId);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + " " +
            channelId + ", " + serialId);

        send(rr);
    }
    // MTK-END, SMS part

    public void setTrm(int mode, Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_TRM, null);

        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(mode);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        send(rr);
    }

    public void setResumeRegistration(int sessionId, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_RESUME_REGISTRATION, response);
        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(sessionId);
        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        send(rr);
    }

    public void queryModemType(Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_QUERY_MODEM_TYPE, response);
        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        send(rr);
    }

    public void storeModemType(int modemType, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_STORE_MODEM_TYPE, response);
        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(modemType);
        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        send(rr);
    }

    public void setStkEvdlCallByAP(int enabled, Message response) {
        RILRequest rr =
                //RILRequest.obtain(RIL_REQUEST_STK_EVDL_CALL_BY_AP, response, mySimId);
                RILRequest.obtain(RIL_REQUEST_STK_EVDL_CALL_BY_AP, response);
        if (RILJ_LOGD) riljLog(rr.serialString() + ">>> " + requestToString(rr.mRequest));
        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(enabled);
        send(rr);
    }

    /* M: call control part start */
    /**
    * To check sub-permission for MoMS before using API.
    *
    * @param subPermission  The permission to be checked.
    *
    * @return Return true if the permission is granted else return false.
    */
    private boolean checkMoMSSubPermission(String subPermission) {

        try {
            IMobileManagerService mMobileManager;
            IBinder binder = ServiceManager.getService(Context.MOBILE_SERVICE);
            mMobileManager = IMobileManagerService.Stub.asInterface(binder);
            int result = mMobileManager.checkPermission(subPermission, Binder.getCallingUid());
            if (result != PackageManager.PERMISSION_GRANTED) {
                riljLog("[Error]Subpermission is not granted!!");
                return false;
            }
        } catch (Exception e) {
            riljLog("[Error]Failed to chcek permission: " +  subPermission);
            return false;
        }

        return true;
    }

    private Object
    responseCrssNotification(Parcel p) {

        SuppCrssNotification notification = new SuppCrssNotification();

        notification.code = p.readInt();
        notification.type = p.readInt();
        notification.number = p.readString();
        notification.alphaid = p.readString();
        notification.cli_validity = p.readInt();

        return notification;
    }

    /*
     * to protect modem status we need to avoid two case :
     * 1. DTMF start -> CHLD request -> DTMF stop
     * 2. CHLD request -> DTMF request
     */
    private void handleChldRelatedRequest(RILRequest rr) {
        synchronized (mDtmfReqQueue) {
            int queueSize = mDtmfReqQueue.size();
            int i, j;
            if (queueSize > 0) {
                RILRequest rr2 = mDtmfReqQueue.get();
                if (rr2.mRequest == RIL_REQUEST_DTMF_START) {
                    // need to send the STOP command
                    if (RILJ_LOGD) riljLog("DTMF queue isn't 0, first request is START, send stop dtmf and pending switch");
                    if (queueSize > 1) {
                        j = 2;
                    } else {
                        // need to create a new STOP command
                        j = 1;
                    }
                    if (RILJ_LOGD) riljLog("queue size  " + mDtmfReqQueue.size());

                    for (i = queueSize - 1; i >= j; i--) {
                        mDtmfReqQueue.remove(i);
                    }
                    if (RILJ_LOGD) riljLog("queue size  after " + mDtmfReqQueue.size());
                    if (mDtmfReqQueue.size() == 1) { // only start command, we need to add stop command
                        RILRequest rr3 = RILRequest.obtain(RIL_REQUEST_DTMF_STOP, null);
                        if (RILJ_LOGD) riljLog("add dummy stop dtmf request");
                        mDtmfReqQueue.stop();
                        mDtmfReqQueue.add(rr3);
                    }
                }
                else {
                    // first request is STOP, just remove it and send switch
                    if (RILJ_LOGD) riljLog("DTMF queue isn't 0, first request is STOP, penging switch");
                    j = 1;
                    for (i = queueSize - 1; i >= j; i--) {
                        mDtmfReqQueue.remove(i);
                    }
                }
                mDtmfReqQueue.setPendingRequest(rr);
            } else {
                if (RILJ_LOGD) riljLog("DTMF queue is 0, send switch Immediately");
                mDtmfReqQueue.setSendChldRequest();
                send(rr);
            }
        }
    }

    public void
    hangupAll(Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_HANGUP_ALL,
                                        result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    public void forceReleaseCall(int index, Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_FORCE_RELEASE_CALL, result);

        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(index);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                + " " + index);

        send(rr);
    }

    public void setCallIndication(int mode, int callId, int seqNumber, Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_SET_CALL_INDICATION, result);

        rr.mParcel.writeInt(3);
        rr.mParcel.writeInt(mode);
        rr.mParcel.writeInt(callId);
        rr.mParcel.writeInt(seqNumber);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                + " " + mode + ", " + callId + ", " + seqNumber);

        send(rr);
    }

    public void
    emergencyDial(String address, int clirMode, UUSInfo uusInfo, Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_EMERGENCY_DIAL, result);

        rr.mParcel.writeString(address);
        rr.mParcel.writeInt(clirMode);
        rr.mParcel.writeInt(0); // UUS information is absent

        if (uusInfo == null) {
            rr.mParcel.writeInt(0); // UUS information is absent
        } else {
            rr.mParcel.writeInt(1); // UUS information is present
            rr.mParcel.writeInt(uusInfo.getType());
            rr.mParcel.writeInt(uusInfo.getDcs());
            rr.mParcel.writeByteArray(uusInfo.getUserData());
        }

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    public void setEccServiceCategory(int serviceCategory) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_ECC_SERVICE_CATEGORY, null);

        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(serviceCategory);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
            + " " + serviceCategory);

        send(rr);
    }

    private void setEccList() {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_ECC_LIST, null);
        ArrayList<PhoneNumberUtils.EccEntry> eccList = PhoneNumberUtils.getEccList();

        rr.mParcel.writeInt(eccList.size() * 3);
        for (PhoneNumberUtils.EccEntry entry : eccList) {
            rr.mParcel.writeString(entry.getEcc());
            rr.mParcel.writeString(entry.getCategory());
            String strCondition = entry.getCondition();
            if (strCondition.equals(PhoneNumberUtils.EccEntry.ECC_FOR_MMI))
                strCondition = PhoneNumberUtils.EccEntry.ECC_NO_SIM;
            rr.mParcel.writeString(strCondition);
        }

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    public void setSpeechCodecInfo(boolean enable, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_SPEECH_CODEC_INFO,
                response);

        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(enable ? 1 : 0);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
            + " " + enable);
        send(rr);
    }
    /* M: call control part end */

    /// M: IMS feature. @{
    public void addConferenceMember(int confCallId, String address, int callIdToAdd, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_ADD_IMS_CONFERENCE_CALL_MEMBER, response);

        rr.mParcel.writeInt(3);
        rr.mParcel.writeString(Integer.toString(confCallId));
        rr.mParcel.writeString(address);
        rr.mParcel.writeString(Integer.toString(callIdToAdd));

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        send(rr);
    }

    public void removeConferenceMember(int confCallId, String address, int callIdToRemove, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_REMOVE_IMS_CONFERENCE_CALL_MEMBER, response);

        rr.mParcel.writeInt(3);
        rr.mParcel.writeString(Integer.toString(confCallId));
        rr.mParcel.writeString(address);
        rr.mParcel.writeString(Integer.toString(callIdToRemove));

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        send(rr);
    }

    public void retrieveHeldCall(int callIdToRetrieve, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_RETRIEVE_HELD_CALL, response);

        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(callIdToRetrieve);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        send(rr);
    }
    /// @}

    /* M: SS part */
    public void
    changeBarringPassword(String facility, String oldPwd, String newPwd,
        String newCfm, Message result) {
        //RILRequest rr = RILRequest.obtain(RIL_REQUEST_CHANGE_BARRING_PASSWORD, result, mySimId);
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_CHANGE_BARRING_PASSWORD, result);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        rr.mParcel.writeInt(4);
        rr.mParcel.writeString(facility);
        rr.mParcel.writeString(oldPwd);
        rr.mParcel.writeString(newPwd);
        rr.mParcel.writeString(newCfm);
        send(rr);
    }

    public void setCLIP(boolean enable, Message result) {
        //RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_CLIP, result, mySimId);
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_CLIP, result);

        // count ints
        rr.mParcel.writeInt(1);

        if (enable) {
            rr.mParcel.writeInt(1);
        } else {
            rr.mParcel.writeInt(0);
        }

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                    + " " + enable);

        send(rr);
    }
    /* M: SS part end */

    /* M: Network part start */
    private String lookupOperatorNameFromNetwork(long subId, String numeric, boolean desireLongName) {
        String nitzOperatorNumeric = null;
        String nitzOperatorName = null;

        nitzOperatorNumeric = TelephonyManager.getTelephonyProperty(TelephonyProperties.PROPERTY_NITZ_OPER_CODE, subId, "");
        if ((numeric != null) && (numeric.equals(nitzOperatorNumeric))) {
            if (desireLongName == true) {
                nitzOperatorName = TelephonyManager.getTelephonyProperty(TelephonyProperties.PROPERTY_NITZ_OPER_LNAME, subId, "");
            } else {
                nitzOperatorName = TelephonyManager.getTelephonyProperty(TelephonyProperties.PROPERTY_NITZ_OPER_SNAME, subId, "");
            }
        }

        /* ALPS00273663 handle UCS2 format name : prefix + hex string ex: "uCs2806F767C79D1" */
        if ((nitzOperatorName != null) && (nitzOperatorName.startsWith("uCs2") == true))
        {
            riljLog("lookupOperatorNameFromNetwork handling UCS2 format name");
            try {
                nitzOperatorName = new String(IccUtils.hexStringToBytes(nitzOperatorName.substring(4)), "UTF-16");
            } catch (UnsupportedEncodingException ex) {
                riljLog("lookupOperatorNameFromNetwork UnsupportedEncodingException");
            }
        }

        riljLog("lookupOperatorNameFromNetwork numeric= " + numeric + ",subId= " + subId + ",nitzOperatorNumeric= " + nitzOperatorNumeric + ",nitzOperatorName= " + nitzOperatorName);

        return nitzOperatorName;
    }

    private Object
    responseOperator(Parcel p) {
        int num;
        String response[] = null;
        String longName = null;
        String shortName = null;

        response = p.readStringArray();

        if (false) {
            num = p.readInt();

            response = new String[num];
            for (int i = 0; i < num; i++) {
                response[i] = p.readString();
            }
        }

        if (response.length > 2 && response[2] != null) {
            /* Default display manufacturer maintained operator name table */
            response[0] = SpnOverride.getInstance().lookupOperatorName(SubscriptionManager.getSubIdUsingPhoneId(mInstanceId), response[2], true, mContext);
            response[1] = SpnOverride.getInstance().lookupOperatorName(SubscriptionManager.getSubIdUsingPhoneId(mInstanceId), response[2], false, mContext);
            riljLog("lookup RIL responseOperator(), longAlpha= " + response[0] + ",shortAlpha= " + response[1] + ",numeric=" + response[2]);

            /* Operator name from network MM information has higher priority to display */
            longName = lookupOperatorNameFromNetwork(SubscriptionManager.getSubIdUsingPhoneId(mInstanceId), response[2], true);
            shortName = lookupOperatorNameFromNetwork(SubscriptionManager.getSubIdUsingPhoneId(mInstanceId), response[2], false);
            if (longName != null) {
                response[0] = longName;
            }
            if (shortName != null) {
                response[1] = shortName;
            }
            riljLog("lookupOperatorNameFromNetwork in responseOperator(),updated longAlpha= " + response[0] + ",shortAlpha= " + response[1] + ",numeric=" + response[2]);

            /* Operator name from SIM (EONS/CPHS) has highest priority to display. This will be handled in GsmSST updateSpnDisplay() */
        }

        return response;
    }

    @Override
    public void
    setNetworkSelectionModeManualWithAct(String operatorNumeric, String act, Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_SET_NETWORK_SELECTION_MANUAL_WITH_ACT,
                                    response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                    + " " + operatorNumeric + "" + act);

        rr.mParcel.writeInt(3);
        rr.mParcel.writeString(operatorNumeric);
        rr.mParcel.writeString(act);
        rr.mParcel.writeString("0"); //the 3rd parameter is for MTK RIL to identify it shall be processed as semi auto network selection mode or not

        send(rr);
    }

    private Object
    responseNetworkInfoWithActs(Parcel p) {
        String strings[] = (String []) responseStrings(p);
        ArrayList<NetworkInfoWithAcT> ret;

        if (strings.length % 4 != 0) {
            throw new RuntimeException(
                "RIL_REQUEST_GET_POL_LIST: invalid response. Got "
                + strings.length + " strings, expected multible of 5");
        }

        ret = new ArrayList<NetworkInfoWithAcT>(strings.length / 4);

        String strOperName = null;
        String strOperNumeric = null;
        int nAct = 0;
        int nIndex = 0;

        for (int i = 0 ; i < strings.length ; i += 4) {
            strOperName = null;
            strOperNumeric = null;
            if (strings[i] != null) {
                nIndex = Integer.parseInt(strings[i]);
            } else {
                Rlog.d(RILJ_LOG_TAG, "responseNetworkInfoWithActs: no invalid index. i is " + i);
            }

            if (strings[i + 1] != null) {
                int format = Integer.parseInt(strings[i + 1]);
                switch (format) {
                    case 0:
                    case 1:
                        strOperName = strings[i + 2];
                        break;
                    case 2:
                        if (strings[i + 2] != null) {
                            strOperNumeric = strings[i + 2];
                            strOperName = SpnOverride.getInstance().lookupOperatorName(SubscriptionManager.getSubIdUsingPhoneId(mInstanceId), strings[i + 2], true, mContext);
                        }
                        break;
                    default:
                        break;
                }
            }

            if (strings[i + 3] != null) {
                nAct = Integer.parseInt(strings[i + 3]);
            } else {
                Rlog.d(RILJ_LOG_TAG, "responseNetworkInfoWithActs: no invalid Act. i is " + i);
            }
            if (strOperNumeric != null && !strOperNumeric.equals("?????")) {
                ret.add(
                    new NetworkInfoWithAcT(
                        strOperName,
                        strOperNumeric,
                        nAct,
                        nIndex));
            } else {
                Rlog.d(RILJ_LOG_TAG, "responseNetworkInfoWithActs: invalid oper. i is " + i);
            }
        }

        return ret;
    }

    public void
    setNetworkSelectionModeSemiAutomatic(String operatorNumeric, String act, Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_SET_NETWORK_SELECTION_MANUAL_WITH_ACT,
                                    response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                    + " " + operatorNumeric + "" + act);

        rr.mParcel.writeInt(3);
        rr.mParcel.writeString(operatorNumeric);
        rr.mParcel.writeString(act);
        rr.mParcel.writeString("1"); //the 3rd parameter is for MTK RIL to identify it shall be processed as semi auto network selection mode

        send(rr);
    }

    public void getPOLCapabilty(Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_GET_POL_CAPABILITY, response);
        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        send(rr);
    }

    public void getCurrentPOLList(Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_GET_POL_LIST, response);
        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        send(rr);
    }

    public void setPOLEntry(int index, String numeric, int nAct, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_POL_ENTRY, response);
        if (numeric == null || (numeric.length() == 0)) {
            rr.mParcel.writeInt(1);
            rr.mParcel.writeString(Integer.toString(index));
        } else {
            rr.mParcel.writeInt(3);
            rr.mParcel.writeString(Integer.toString(index));
            rr.mParcel.writeString(numeric);
            rr.mParcel.writeString(Integer.toString(nAct));
        }
        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        send(rr);
    }

    // Femtocell (CSG) feature START
    public void getFemtoCellList(String operatorNumeric, int rat, Message response) {
        RILRequest rr
        = RILRequest.obtain(RIL_REQUEST_GET_FEMTOCELL_LIST,
                                    response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        rr.mParcel.writeInt(2);
        rr.mParcel.writeString(operatorNumeric);
        rr.mParcel.writeString(Integer.toString(rat));
        send(rr);
    }

    public void abortFemtoCellList(Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_ABORT_FEMTOCELL_LIST, response);
        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        send(rr);
    }

    public void selectFemtoCell(FemtoCellInfo femtocell, Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_SELECT_FEMTOCELL,
                                    response);
        int act = femtocell.getCsgRat();

        if (act == ServiceState.RIL_RADIO_TECHNOLOGY_LTE) {
            act = 7;
        } else if (act == ServiceState.RIL_RADIO_TECHNOLOGY_UMTS) {
            act = 2;
        } else {
            act = 0;
        }

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                    + " csgId=" + femtocell.getCsgId() + " plmn=" + femtocell.getOperatorNumeric() + " rat=" + femtocell.getCsgRat() + " act=" + act);

        rr.mParcel.writeInt(3);
        rr.mParcel.writeString(femtocell.getOperatorNumeric());
        rr.mParcel.writeString(Integer.toString(act));
        rr.mParcel.writeString(Integer.toString(femtocell.getCsgId()));

        send(rr);
    }
    // Femtocell (CSG) feature END

    // M: CC33 LTE.
    @Override
    public void
    setDataOnToMD(boolean enable, Message result) {
        //AT+EDSS = <on/off>
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_DATA_ON_TO_MD, result);
        int type = enable ? 1 : 0;
        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(type);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> "
                                + requestToString(rr.mRequest) + ": " + type);
        send(rr);
    }

    @Override
    public void
    setRemoveRestrictEutranMode(boolean enable, Message result) {
        //AT+ECODE33 = <on/off>
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_REMOVE_RESTRICT_EUTRAN_MODE, result);
        int type = enable ? 1 : 0;
        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(type);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> "
                                + requestToString(rr.mRequest) + ": " + type);
        send(rr);
    }

    public boolean isGettingAvailableNetworks() {
        synchronized (mRequestList) {
            for (int i = 0, s = mRequestList.size() ; i < s ; i++) {
                RILRequest rr = mRequestList.valueAt(i);
                if (rr != null &&
                    (rr.mRequest == RIL_REQUEST_QUERY_AVAILABLE_NETWORKS ||
                     rr.mRequest == RIL_REQUEST_QUERY_AVAILABLE_NETWORKS_WITH_ACT)) {
                    return true;
                }
            }
        }

        return false;
    }

    /* M: Network part end */
    // IMS
    public void setIMSEnabled(boolean enable, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_IMS_ENABLE, response);

        rr.mParcel.writeInt(1);
        if (enable) {
            rr.mParcel.writeInt(1);
        } else {
            rr.mParcel.writeInt(0);
        }

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        send(rr);
    }
    // VOLTE
    public void setupDedicateDataCall(int ddcId, int interfaceId, boolean signalingFlag, QosStatus qosStatus, TftStatus tftStatus, Message response) {
        //RILRequest rr = RILRequest.obtain(RIL_REQUEST_SETUP_DEDICATE_DATA_CALL, response, mySimId);
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SETUP_DEDICATE_DATA_CALL, response);

        rr.mParcel.writeInt(7);
        rr.mParcel.writeInt(ddcId);
        rr.mParcel.writeInt(interfaceId);
        rr.mParcel.writeInt(signalingFlag ? 1 : 0);
        if (qosStatus == null) {
            rr.mParcel.writeInt(0);
        } else {
            rr.mParcel.writeInt(1);
            qosStatus.writeTo(rr.mParcel);
        }

        if (tftStatus == null) {
            rr.mParcel.writeInt(0);
        } else {
            rr.mParcel.writeInt(1);
            tftStatus.writeTo(rr.mParcel);
        }

        if (RILJ_LOGD) riljLog(rr.serialString() + "> "
                + requestToString(rr.mRequest) + " interfaceId=" + interfaceId + " signalingFlag="
                + signalingFlag);

        send(rr);
    }

    public void deactivateDedicateDataCall(int cid, String reason, Message response) {
        //RILRequest rr = RILRequest.obtain(RIL_REQUEST_DEACTIVATE_DEDICATE_DATA_CALL, response, mySimId);
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_DEACTIVATE_DEDICATE_DATA_CALL, response);
        rr.mParcel.writeInt(2);
        rr.mParcel.writeString(Integer.toString(cid));
        rr.mParcel.writeString(reason);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " +
                requestToString(rr.mRequest) + " " + cid + " " + reason);

        send(rr);
    }

    public void modifyDataCall(int cid, QosStatus qosStatus, TftStatus tftStatus, Message response) {
        //RILRequest rr = RILRequest.obtain(RIL_REQUEST_MODIFY_DATA_CALL, response, mySimId);
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_MODIFY_DATA_CALL, response);
        rr.mParcel.writeInt(7);
        rr.mParcel.writeInt(cid);
        if (qosStatus == null) {
            rr.mParcel.writeInt(0);
        } else {
            rr.mParcel.writeInt(1);
            qosStatus.writeTo(rr.mParcel);
        }

        if (tftStatus == null) {
            rr.mParcel.writeInt(0);
        } else {
            rr.mParcel.writeInt(1);
            tftStatus.writeTo(rr.mParcel);
        }

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        send(rr);
    }

    public void abortSetupDataCall(int ddcId, String reason, Message response) {
        //RILRequest rr = RILRequest.obtain(RIL_REQUEST_ABORT_SETUP_DATA_CALL, response, mySimId);
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_ABORT_SETUP_DATA_CALL, response);
        rr.mParcel.writeInt(2);
        rr.mParcel.writeString(Integer.toString(ddcId));
        rr.mParcel.writeString(reason);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + " " + ddcId + " " + reason);
        send(rr);
    }

    public void pcscfDiscoveryPco(int cid, Message response) {
        //RILRequest rr = RILRequest.obtain(RIL_REQUEST_PCSCF_DISCOVERY_PCO, response, mySimId);
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_PCSCF_DISCOVERY_PCO, response);
        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(cid);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        send(rr);
    }

    private Object responseSetupDedicateDataCall(Parcel p) {
        int number = p.readInt();
        if (RILJ_LOGD) riljLog("responseSetupDedicateDataCall number=" + number);
        DedicateDataCallState[] dedicateDataCalls = new DedicateDataCallState[number];
        for (int i=0; i<number; i++) {
            DedicateDataCallState dedicateDataCall = new DedicateDataCallState();
            dedicateDataCall.readFrom(p);
            dedicateDataCalls[i] = dedicateDataCall;

            riljLog("[" + dedicateDataCall.interfaceId + ", " + dedicateDataCall.defaultCid + ", " + dedicateDataCall.cid + ", " + dedicateDataCall.active +
                ", " + dedicateDataCall.signalingFlag + ", " + dedicateDataCall.failCause + ", Qos" + dedicateDataCall.qosStatus +
                ", Tft" + dedicateDataCall.tftStatus + ", PCSCF" + dedicateDataCall.pcscfInfo);
        }

        if (number > 1)
            return dedicateDataCalls;
        else if (number > 0)
            return dedicateDataCalls[0];
        else
            return null;
    }

    private Object responseModifyDataCall(Parcel p) {
        return null;
    }

    private Object responsePcscfDiscovery(Parcel p) {
        PcscfInfo pcscfInfo = null;
        String pcscfStr = p.readString();
        if (!TextUtils.isEmpty(pcscfStr)) {
            String[] pcscfArray = pcscfStr.split(" ");
            if (pcscfArray != null && pcscfArray.length > 0)
                pcscfInfo = new PcscfInfo(PcscfInfo.IMC_PCSCF_ACQUIRE_BY_PCO, pcscfArray);
        }
        return pcscfInfo;
    }

    public void clearDataBearer(Message response) {
        //RILRequest rr = RILRequest.obtain(RIL_REQUEST_CLEAR_DATA_BEARER, response, mySimId);
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_CLEAR_DATA_BEARER, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        send(rr);
    }

    // M: Fast Dormancy
    public void setScri(boolean forceRelease, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_SCRI, response);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(forceRelease ? 1 : 0);

        send(rr);

    }

    //[New R8 modem FD]
    public void setFDMode(int mode, int parameter1, int parameter2, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_FD_MODE, response);

        //AT+EFD=<mode>[,<param1>[,<param2>]]
        //mode=0:disable modem Fast Dormancy; mode=1:enable modem Fast Dormancy
        //mode=3:inform modem the screen status; parameter1: screen on or off
        //mode=2:Fast Dormancy inactivity timer; parameter1:timer_id; parameter2:timer_value
        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        if (mode == 0 || mode == 1) {
            rr.mParcel.writeInt(1);
            rr.mParcel.writeInt(mode);
        } else if (mode == 3) {
            rr.mParcel.writeInt(2);
            rr.mParcel.writeInt(mode);
            rr.mParcel.writeInt(parameter1);
        } else if (mode == 2) {
            rr.mParcel.writeInt(3);
            rr.mParcel.writeInt(mode);
            rr.mParcel.writeInt(parameter1);
            rr.mParcel.writeInt(parameter2);
        }

        send(rr);

    }

    // @argument:
    // enable: yes   -> data centric
    //         false -> voice centric
    public void setDataCentric(boolean enable, Message response) {
    	if (RILJ_LOGD) riljLog("setDataCentric");
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_DATA_CENTRIC, response);

        rr.mParcel.writeInt(1);
        if(enable) {
            rr.mParcel.writeInt(1);
        } else {
            rr.mParcel.writeInt(0);
        }

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        send(rr);
    }

    
    /* M: call control part start */
    /**
     * Notify modem about IMS call status.
     * @param existed True if there is at least one IMS call existed, else return false.
     * @param response User-defined message code.
     */
    @Override
    public void setImsCallStatus (boolean existed, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_IMS_CALL_STATUS, null);

        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(existed ? 1 : 0);

        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        }
        send(rr);
    }
    /* M: call control part end */
    

    /* M: C2K part start */
    @Override
    public void setViaTRM(int mode, Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_VIA_TRM, null);

        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(mode);

        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        }
        send(rr);
    }

    @Override
    public void getNitzTime(Message result) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_GET_NITZ_TIME, result);

        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        }

        send(rr);
    }

    @Override
    public void requestSwitchHPF(boolean enableHPF, Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_SWITCH_HPF, response);

        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + " " + enableHPF);
        }

        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(enableHPF ? 1 : 0);

        send(rr);
    }

    @Override
    public void setAvoidSYS(boolean avoidSYS, Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_SET_AVOID_SYS, response);

        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + " " + avoidSYS);
        }

        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(avoidSYS ? 1 : 0);

        send(rr);
    }

    @Override
    public void getAvoidSYSList(Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_QUERY_AVOID_SYS, response);

        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        }

        send(rr);
    }

    @Override
    public void queryCDMANetworkInfo(Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_QUERY_CDMA_NETWORK_INFO, response);

        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        }

        send(rr);
    }

    @Override
    public void requestAGPSTcpConnected(int connected, Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_AGPS_TCP_CONNIND, result);
        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(connected);
        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + ": " + connected);
        }
        send(rr);
    }

    @Override
    public void requestAGPSSetMpcIpPort(String ip, String port, Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_AGPS_SET_MPC_IPPORT, result);
        rr.mParcel.writeInt(2);
        rr.mParcel.writeString(ip);
        rr.mParcel.writeString(port);
        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                    + " : " + ip + ", " + port);
        }
        send(rr);
    }

    @Override
    public void requestAGPSGetMpcIpPort(Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_AGPS_GET_MPC_IPPORT, result);
        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        }
        send(rr);
    }

    @Override
    public void requestSetEtsDev(int dev, Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_ETS_DEV, result);
        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(dev);
        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + ": " + dev);
        }
        send(rr);
    }

    @Override
    public void setArsiReportThreshold(int threshold, Message response) {
        RILRequest rr = RILRequest.obtain(
                RILConstants.RIL_REQUEST_SET_ARSI_THRESHOLD, response);

        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(threshold);

        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + " : " + threshold);
        }

        send(rr);
    }

    @Override
    public void resumeCdmaRegister(Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_SET_REG_RESUME, result);

        mVoiceNetworkStateRegistrants.notifyRegistrants(new AsyncResult (null, null, null));

        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        }

        send(rr);
    }

    @Override
    public void enableCdmaRegisterPause(boolean enable, Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_ENABLE_REG_PAUSE, result);
        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(enable ? 1 : 0);

        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                    + " enable : " + enable);
        }

        send(rr);
    }

    @Override
    public void queryCDMASmsAndPBStatus(Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_QUERY_SMS_AND_PHONEBOOK_STATUS, response);

        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        }

        send(rr);
    }

    @Override
    public void queryCDMANetWorkRegistrationState(Message response) {
        RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_QUERY_NETWORK_REGISTRATION, response);

        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        }

        send(rr);
    }

    @Override
    public void setMeid(String meid, Message response) {
        RILRequest rr
               = RILRequest.obtain(RIL_REQUEST_SET_MEID, response);

       rr.mParcel.writeString(meid);
       if (RILJ_LOGD) {
           riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + ": " + meid);
       }

       send(rr);
   }

    @Override
    public void setMdnNumber(String mdn, Message response) {
         RILRequest rr
                = RILRequest.obtain(RIL_REQUEST_WRITE_MDN, response);

        rr.mParcel.writeString(mdn);
        if (RILJ_LOGD) {
            riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + ": " + mdn);
        }

        send(rr);
    }

    private Object responseGetNitzTime(Parcel p) {
        Object[] result = new Object[2];
        String response;

        response = p.readString();
        long nitzReceiveTime = p.readLong();
        result[0] = response;
        result[1] = Long.valueOf(nitzReceiveTime);

        return result;
    }
    
    //UTK started
    public void getUtkLocalInfo(Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_GET_LOCAL_INFO, result);
        
        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }
    
    public void requestUtkRefresh(int refreshType, Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_UTK_REFRESH, result);

        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(refreshType);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    public void reportUtkServiceIsRunning(Message result) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_REPORT_STK_SERVICE_IS_RUNNING, result);
        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    public void profileDownload(String profile, Message response) {
        RILRequest rr = RILRequest.obtain(RIL_REQUEST_STK_SET_PROFILE, response);

        rr.mParcel.writeString(profile);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }    

    public void handleCallSetupRequestFromUim(boolean accept, Message response) {
        RILRequest rr = RILRequest.obtain(
            RILConstants.RIL_REQUEST_STK_HANDLE_CALL_SETUP_REQUESTED_FROM_SIM,
            response);

        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(accept ? 1 : 0);

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + " " + (accept ? 1 : 0));
        
        send(rr);
    }
    //UTK end    
    
    /* M: C2K part end */
}
