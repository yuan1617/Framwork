/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.Manifest;
import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Binder;
import android.os.Handler;
import android.os.Message;
import android.os.UserManager;
import android.provider.Telephony;
import android.telephony.Rlog;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.util.Log;

import com.android.internal.telephony.gsm.SmsBroadcastConfigInfo;
import com.android.internal.telephony.cdma.CdmaSmsBroadcastConfigInfo;
import com.android.internal.telephony.uicc.IccConstants;
import com.android.internal.telephony.uicc.IccFileHandler;
import com.android.internal.telephony.uicc.UiccController;
import com.android.internal.telephony.SmsNumberUtils;
import com.android.internal.util.HexDump;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static android.telephony.SmsManager.STATUS_ON_ICC_FREE;
import static android.telephony.SmsManager.STATUS_ON_ICC_READ;
import static android.telephony.SmsManager.STATUS_ON_ICC_UNREAD;

import android.telephony.TelephonyManager;

// MTK-START
import android.os.Process;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.Intent;
import com.android.internal.telephony.GsmAlphabet.TextEncodingDetails;
import com.mediatek.internal.telephony.IccSmsStorageStatus;
import android.telephony.SimSmsInsertStatus;
import android.telephony.SmsParameters;
import static android.telephony.SmsManager.RESULT_ERROR_GENERIC_FAILURE;
import static android.telephony.SmsManager.RESULT_ERROR_SUCCESS;
import static android.telephony.SmsManager.RESULT_ERROR_SIM_MEM_FULL;
import static android.telephony.SmsManager.RESULT_ERROR_INVALID_ADDRESS;
import static android.telephony.SmsManager.STATUS_ON_ICC_SENT;
import static android.telephony.SmsManager.STATUS_ON_ICC_UNSENT;
// Mobile Manager Service
import android.os.Bundle;
import com.mediatek.common.mom.IMobileManager;
import com.mediatek.common.mom.SubPermissions;
import com.mediatek.common.mom.MobileManagerUtils;
// Record the CB config
import com.mediatek.internal.telephony.SmsCbConfigInfo;
// MTK-END

/**
 * IccSmsInterfaceManager to provide an inter-process communication to
 * access Sms in Icc.
 */
public class IccSmsInterfaceManager {
    static final String LOG_TAG = "IccSmsInterfaceManager";
    static final boolean DBG = true;

    protected final Object mLock = new Object();
    // MTK-START
    protected final Object mLoadLock = new Object();
    // MTK-END
    protected boolean mSuccess;
    private List<SmsRawData> mSms;

    private CellBroadcastRangeManager mCellBroadcastRangeManager =
            new CellBroadcastRangeManager();
    private CdmaBroadcastRangeManager mCdmaBroadcastRangeManager =
            new CdmaBroadcastRangeManager();

    private static final int EVENT_LOAD_DONE = 1;
    private static final int EVENT_UPDATE_DONE = 2;
    protected static final int EVENT_SET_BROADCAST_ACTIVATION_DONE = 3;
    protected static final int EVENT_SET_BROADCAST_CONFIG_DONE = 4;

    // MTK-START
    private static final int EVENT_SIM_SMS_DELETE_DONE = 100;
    private static final int EVENT_SET_ETWS_CONFIG_DONE = 101;
    private static final int EVENT_GET_SMS_SIM_MEM_STATUS_DONE = 102;
    private static final int EVENT_INSERT_TEXT_MESSAGE_TO_ICC_DONE = 103;
    private static final int EVENT_GET_SMS_PARAMS = 104;
    private static final int EVENT_SET_SMS_PARAMS = 105;
    private static final int EVENT_LOAD_ONE_RECORD_DONE = 106;
    private static final int EVENT_GET_BROADCAST_CONFIG_DONE = 107;
    private static final int EVENT_GET_BROADCAST_ACTIVATION_DONE = 108;
    private static final int EVENT_REMOVE_BROADCAST_MSG_DONE = 109;
    // MTK-END
    // VIA add begin
    private static final int EVENT_WRITE_CDMA_SMS_TO_RUIM_DONE = 200;
    // VIA add end

    private static final int SMS_CB_CODE_SCHEME_MIN = 0;
    private static final int SMS_CB_CODE_SCHEME_MAX = 255;

    protected PhoneBase mPhone;
    final protected Context mContext;
    final protected AppOpsManager mAppOps;
    final private UserManager mUserManager;
    protected SMSDispatcher mDispatcher;

    // MTK-START
    private IccSmsStorageStatus mSimMemStatus;
    // Text message inserting
    private boolean mInsertMessageSuccess;
    private final Object mSimInsertLock = new Object();
    private SimSmsInsertStatus smsInsertRet = new SimSmsInsertStatus(RESULT_ERROR_SUCCESS, "");
    private static int sConcatenatedRef = 456;
    private static final String INDEXT_SPLITOR = ",";
    // Raw message inserting
    private SimSmsInsertStatus smsInsertRet2 = new SimSmsInsertStatus(RESULT_ERROR_SUCCESS, "");
    // EFsmsp read/write
    private SmsParameters mSmsParams = null;
    private boolean mSmsParamsSuccess = false;
    // Single sms record loading
    private SmsRawData mSmsRawData = null;
    // Record the CB config
    private SmsBroadcastConfigInfo[] mSmsCBConfig = null;
    // MTK-END

    protected Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            AsyncResult ar;

            switch (msg.what) {
                case EVENT_UPDATE_DONE:
                    ar = (AsyncResult) msg.obj;
                    synchronized (mLock) {
                        mSuccess = (ar.exception == null);
                        // MTK-START
                        if (mSuccess == true) {
                            try {
                                int index = ((int[]) ar.result)[0];
                                smsInsertRet2.indexInIcc += (index + INDEXT_SPLITOR);
                                log("[insertRaw save one pdu in index " + index);
                            } catch (ClassCastException e) {
                                e.printStackTrace();
                            } catch (Exception ex) {
                                ex.printStackTrace();
                            }
                        } else {
                            log("[insertRaw fail to insert raw into ICC");
                            smsInsertRet2.indexInIcc += ("-1" + INDEXT_SPLITOR);
                        }
                        // MTK-END
                        mLock.notifyAll();
                    }

                    // MTK-START
                    if (ar.exception != null) {
                        CommandException e = (CommandException) ar.exception;
                        if (DBG) log("Cannot update SMS " + e.getCommandError());

                        if (e.getCommandError() == CommandException.Error.SIM_MEM_FULL) {
                            mDispatcher.handleIccFull();
                        }
                    }
                    // MTK-END
                    break;
                case EVENT_LOAD_DONE:
                    ar = (AsyncResult)msg.obj;
                    // MTK-START
                    synchronized (mLoadLock) {
                    // MTK-END
                        if (ar.exception == null) {
                            mSms = buildValidRawData((ArrayList<byte[]>) ar.result);
                            //Mark SMS as read after importing it from card.
                            markMessagesAsRead((ArrayList<byte[]>) ar.result);
                        } else {
                            if (Rlog.isLoggable("SMS", Log.DEBUG)) {
                                log("Cannot load Sms records");
                            }
                            if (mSms != null)
                                mSms.clear();
                        }
                        // MTK-START
                        mLoadLock.notifyAll();
                        // MTK-END
                    }
                    break;
                case EVENT_SET_BROADCAST_ACTIVATION_DONE:
                case EVENT_SET_BROADCAST_CONFIG_DONE:
                // MTK-START
                case EVENT_SET_ETWS_CONFIG_DONE:
                // MTK-END
                    ar = (AsyncResult) msg.obj;
                    synchronized (mLock) {
                        mSuccess = (ar.exception == null);
                        mLock.notifyAll();
                    }
                    break;
                // MTK-START
                case EVENT_GET_SMS_SIM_MEM_STATUS_DONE:
                    ar = (AsyncResult) msg.obj;

                    synchronized (mLock) {
                        if (ar.exception == null) {
                            mSuccess = true;

                            if (mSimMemStatus == null) {
                                mSimMemStatus = new IccSmsStorageStatus();
                            }

                            IccSmsStorageStatus tmpStatus = (IccSmsStorageStatus) ar.result;

                            mSimMemStatus.mUsed = tmpStatus.mUsed;
                            mSimMemStatus.mTotal = tmpStatus.mTotal;
                        } else {
                            if (DBG)
                                log("Cannot Get Sms SIM Memory Status from SIM");
                        }
                        mLock.notifyAll();
                    }
                    break;
                case EVENT_INSERT_TEXT_MESSAGE_TO_ICC_DONE:
                    ar = (AsyncResult) msg.obj;
                    synchronized (mSimInsertLock) {
                        mInsertMessageSuccess = (ar.exception == null);
                        if (mInsertMessageSuccess == true) {
                            try {
                                int index = ((int[]) ar.result)[0];
                                smsInsertRet.indexInIcc += (index + INDEXT_SPLITOR);
                                log("insertText save one pdu in index " + index);
                            } catch (ClassCastException e) {
                                e.printStackTrace();
                            } catch (Exception ex) {
                                ex.printStackTrace();
                            }
                        } else {
                            log("insertText fail to insert sms into ICC");
                            smsInsertRet.indexInIcc += ("-1" + INDEXT_SPLITOR);
                        }

                        mSimInsertLock.notifyAll();
                    }
                    break;
                case EVENT_GET_SMS_PARAMS:
                    ar = (AsyncResult) msg.obj;
                    synchronized (mLock) {
                        if (ar.exception == null) {
                            try {
                                mSmsParams = (SmsParameters) ar.result;
                            } catch (ClassCastException e) {
                                log("[EFsmsp fail to get sms params ClassCastException");
                                e.printStackTrace();
                            } catch (Exception ex) {
                                log("[EFsmsp fail to get sms params Exception");
                                ex.printStackTrace();
                            }
                        } else {
                            log("[EFsmsp fail to get sms params");
                            mSmsParams = null;
                        }

                        mLock.notifyAll();
                    }
                    break;
                case EVENT_SET_SMS_PARAMS:
                    ar = (AsyncResult) msg.obj;
                    synchronized (mLock) {
                        if (ar.exception == null) {
                            mSmsParamsSuccess = true;
                        } else {
                            log("[EFsmsp fail to set sms params");
                            mSmsParamsSuccess = false;
                        }

                        mLock.notifyAll();
                    }
                    break;
                case EVENT_LOAD_ONE_RECORD_DONE:
                    ar = (AsyncResult) msg.obj;
                    synchronized (mLock) {
                        if (ar.exception == null) {
                            try {
                                // mSmsRawData = (SmsRawData)ar.result;
                                byte[] rawData = (byte[]) ar.result;
                                if (rawData[0] == STATUS_ON_ICC_FREE) {
                                    log("sms raw data status is FREE");
                                    mSmsRawData = null;
                                } else {
                                    mSmsRawData = new SmsRawData(rawData);
                                }
                            } catch (ClassCastException e) {
                                log("fail to get sms raw data ClassCastException");
                                e.printStackTrace();
                                mSmsRawData = null;
                            }
                        } else {
                            log("fail to get sms raw data rild");
                            mSmsRawData = null;
                        }
                        mLock.notifyAll();
                    }
                    break;
                case EVENT_GET_BROADCAST_ACTIVATION_DONE:
                    ar = (AsyncResult) msg.obj;
                    synchronized (mLock) {
                        if (ar.exception == null) {
                            ArrayList<SmsBroadcastConfigInfo> list =
                            (ArrayList<SmsBroadcastConfigInfo>) ar.result;

                            if (list.size() == 0) {
                                mSuccess = false;
                            } else {
                                SmsBroadcastConfigInfo cbConfig = list.get(0);
                                log("cbConfig: " + cbConfig.toString());

                                if (cbConfig.getFromCodeScheme() == -1 &&
                                    cbConfig.getToCodeScheme() == -1 &&
                                    cbConfig.getFromServiceId() == -1 &&
                                    cbConfig.getToServiceId() == -1 &&
                                    cbConfig.isSelected() == false) {

                                    mSuccess = false;
                                } else {
                                    mSuccess = true;
                                }
                            }
                        }

                        log("queryCbActivation: " + mSuccess);
                        mLock.notifyAll();
                    }
                    break;
                case EVENT_GET_BROADCAST_CONFIG_DONE:
                    ar = (AsyncResult) msg.obj;
                    synchronized (mLock) {
                        if (ar.exception == null) {
                            ArrayList<SmsBroadcastConfigInfo> mList = (ArrayList<SmsBroadcastConfigInfo>) ar.result;

                            if (mList.size() != 0) {
                                mSmsCBConfig = new SmsBroadcastConfigInfo[mList.size()];
                                mList.toArray(mSmsCBConfig);

                                if (mSmsCBConfig != null) {
                                    int index = 0;
                                    log("config size=" + mSmsCBConfig.length);
                                    /* Print all log here for debug */
                                    for (index = 0 ; index < mSmsCBConfig.length ; index++) {
                                        log("mSmsCBConfig[" + index + "] = " +
                                            "Channel id: " + mSmsCBConfig[index].getFromServiceId() + "-" +
                                                             mSmsCBConfig[index].getToServiceId() + ", " +
                                            "Language: " + mSmsCBConfig[index].getFromCodeScheme() + "-" +
                                                           mSmsCBConfig[index].getToCodeScheme() + ", " +
                                            "Selected: " + mSmsCBConfig[index].isSelected());
                                    }
                                }
                            }
                        } else {
                            log("Cannot Get CB configs");
                        }
                        mLock.notifyAll();
                    }
                    break;
                case EVENT_REMOVE_BROADCAST_MSG_DONE:
                    ar = (AsyncResult) msg.obj;
                    synchronized (mLock) {
                        mSuccess = (ar.exception == null);
                        mLock.notifyAll();
                    }
                    break;
                // MTK-END
            }
        }
    };

    protected IccSmsInterfaceManager(PhoneBase phone) {
        mPhone = phone;
        mContext = phone.getContext();
        mAppOps = (AppOpsManager) mContext.getSystemService(Context.APP_OPS_SERVICE);
        mUserManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        mDispatcher = new ImsSMSDispatcher(phone,
                phone.mSmsStorageMonitor, phone.mSmsUsageMonitor);
        // MTK-START
        IntentFilter filter = new IntentFilter();
        filter.addAction("com.mediatek.dm.LAWMO_WIPE");
        mContext.registerReceiver(mSmsWipeReceiver, filter);
        // MTK-END
    }

    protected void markMessagesAsRead(ArrayList<byte[]> messages) {
        if (messages == null) {
            return;
        }

        //IccFileHandler can be null, if icc card is absent.
        IccFileHandler fh = mPhone.getIccFileHandler();
        if (fh == null) {
            //shouldn't really happen, as messages are marked as read, only
            //after importing it from icc.
            if (Rlog.isLoggable("SMS", Log.DEBUG)) {
                log("markMessagesAsRead - aborting, no icc card present.");
            }
            return;
        }

        int count = messages.size();

        for (int i = 0; i < count; i++) {
             byte[] ba = messages.get(i);
             if (ba[0] == STATUS_ON_ICC_UNREAD) {
                 int n = ba.length;
                 byte[] nba = new byte[n - 1];
                 System.arraycopy(ba, 1, nba, 0, n - 1);
                 byte[] record = makeSmsRecordData(STATUS_ON_ICC_READ, nba);
                 fh.updateEFLinearFixed(IccConstants.EF_SMS, i + 1, record, null, null);
                 if (Rlog.isLoggable("SMS", Log.DEBUG)) {
                     log("SMS " + (i + 1) + " marked as read");
                 }
             }
        }
    }

    protected void updatePhoneObject(PhoneBase phone) {
        mPhone = phone;
        mDispatcher.updatePhoneObject(phone);
    }

    protected void enforceReceiveAndSend(String message) {
        // MTK-START, SIM related operation will be called by non-phone process user
        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.RECEIVE_SMS, message);
        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.SEND_SMS, message);
        // MTK-END
    }

    // MTK-START
    private boolean checkPermissionByUser(Bundle data, String message) {
        if (MobileManagerUtils.isSupported()) {
            if (false == MobileManagerUtils.checkPermission(
                    SubPermissions.SEND_SMS, Binder.getCallingUid(), data)) {
                log("User denied " + message);
                return false;
            }
        }
        return true;
    }
    // MTK-END

    /**
     * Update the specified message on the Icc.
     *
     * @param index record index of message to update
     * @param status new message status (STATUS_ON_ICC_READ,
     *                  STATUS_ON_ICC_UNREAD, STATUS_ON_ICC_SENT,
     *                  STATUS_ON_ICC_UNSENT, STATUS_ON_ICC_FREE)
     * @param pdu the raw PDU to store
     * @return success or not
     *
     */

    public boolean
    updateMessageOnIccEf(String callingPackage, int index, int status, byte[] pdu) {
        if (DBG) log("updateMessageOnIccEf: index=" + index +
                " status=" + status + " ==> " +
                "("+ Arrays.toString(pdu) + ")");
        enforceReceiveAndSend("Updating message on Icc");
        // MTK-START
        /** The calling package will change to telephony provider
         *  because of System UI will be the first AP launched
         *  and it will access telephony provider at MTK turkey
         */
        if (DBG) log("updateMessageOnIccEf: callingPackage = " + callingPackage + ", Binder.getCallingUid() = "
            + Binder.getCallingUid());
        if (Binder.getCallingUid() == Process.PHONE_UID) {
            callingPackage = "com.android.phone";
        }
        // MTK-END
        if (mAppOps.noteOp(AppOpsManager.OP_WRITE_ICC_SMS, Binder.getCallingUid(),
                callingPackage) != AppOpsManager.MODE_ALLOWED) {
            // MTK-START
            if (DBG) log("updateMessageOnIccEf: noteOp NOT ALLOWED");
            // MTK-END
            return false;
        }
        synchronized(mLock) {
            mSuccess = false;
            Message response = mHandler.obtainMessage(EVENT_UPDATE_DONE);

            if (status == STATUS_ON_ICC_FREE) {
                // RIL_REQUEST_DELETE_SMS_ON_SIM vs RIL_REQUEST_CDMA_DELETE_SMS_ON_RUIM
                // Special case FREE: call deleteSmsOnSim/Ruim instead of
                // manipulating the record
                // Will eventually fail if icc card is not present.
                if (PhoneConstants.PHONE_TYPE_GSM == mPhone.getPhoneType()) {
                    mPhone.mCi.deleteSmsOnSim(index, response);
                } else {
                    mPhone.mCi.deleteSmsOnRuim(index, response);
                }
            } else {
                //IccFilehandler can be null if ICC card is not present.
                IccFileHandler fh = mPhone.getIccFileHandler();
                if (fh == null) {
                    response.recycle();
                    return mSuccess; /* is false */
                }
                byte[] record = makeSmsRecordData(status, pdu);
                fh.updateEFLinearFixed(
                        IccConstants.EF_SMS,
                        index, record, null, response);
            }
            try {
                mLock.wait();
            } catch (InterruptedException e) {
                log("interrupted while trying to update by index");
            }
        }
        return mSuccess;
    }

    /**
     * Copy a raw SMS PDU to the Icc.
     *
     * @param pdu the raw PDU to store
     * @param status message status (STATUS_ON_ICC_READ, STATUS_ON_ICC_UNREAD,
     *               STATUS_ON_ICC_SENT, STATUS_ON_ICC_UNSENT)
     * @return success or not
     *
     */
    public boolean copyMessageToIccEf(String callingPackage, int status, byte[] pdu, byte[] smsc) {
        //NOTE smsc not used in RUIM
        if (DBG) log("copyMessageToIccEf: status=" + status + " ==> " +
                "pdu=("+ Arrays.toString(pdu) +
                "), smsc=(" + Arrays.toString(smsc) +")");
        enforceReceiveAndSend("Copying message to Icc");
        if (mAppOps.noteOp(AppOpsManager.OP_WRITE_ICC_SMS, Binder.getCallingUid(),
                callingPackage) != AppOpsManager.MODE_ALLOWED) {
            return false;
        }
        synchronized(mLock) {
            mSuccess = false;
            Message response = mHandler.obtainMessage(EVENT_UPDATE_DONE);

            //RIL_REQUEST_WRITE_SMS_TO_SIM vs RIL_REQUEST_CDMA_WRITE_SMS_TO_RUIM
            if (PhoneConstants.PHONE_TYPE_GSM == mPhone.getPhoneType()) {
                mPhone.mCi.writeSmsToSim(status, IccUtils.bytesToHexString(smsc),
                        IccUtils.bytesToHexString(pdu), response);
            } else {
                mPhone.mCi.writeSmsToRuim(status, IccUtils.bytesToHexString(pdu),
                        response);
            }

            try {
                mLock.wait();
            } catch (InterruptedException e) {
                log("interrupted while trying to update by index");
            }
        }
        return mSuccess;
    }

    /**
     * Retrieves all messages currently stored on Icc.
     *
     * @return list of SmsRawData of all sms on Icc
     */

    public List<SmsRawData> getAllMessagesFromIccEf(String callingPackage) {
        // MTK-START
        if (DBG) log("getAllMessagesFromEF " + callingPackage);
        // MTK-END

        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.RECEIVE_SMS,
                "Reading messages from Icc");
        if (mAppOps.noteOp(AppOpsManager.OP_READ_ICC_SMS, Binder.getCallingUid(),
                callingPackage) != AppOpsManager.MODE_ALLOWED) {
            return new ArrayList<SmsRawData>();
        }
        // MTK-START
        synchronized (mLoadLock) {
        // MTK-END

            IccFileHandler fh = mPhone.getIccFileHandler();
            if (fh == null) {
                Rlog.e(LOG_TAG, "Cannot load Sms records. No icc card?");
                if (mSms != null) {
                    mSms.clear();
                    return mSms;
                }
            }

            Message response = mHandler.obtainMessage(EVENT_LOAD_DONE);
            fh.loadEFLinearFixedAll(IccConstants.EF_SMS, response);

            try {
                // MTK-START
                mLoadLock.wait();
                // MTK-END
            } catch (InterruptedException e) {
                log("interrupted while trying to load from the Icc");
            }
        }
        return mSms;
    }

    /**
     * Send a data based SMS to a specific application port.
     *
     * @param destAddr the address to send the message to
     * @param scAddr is the service center address or null to use
     *  the current default SMSC
     * @param destPort the port to deliver the message to
     * @param data the body of the message to send
     * @param sentIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is successfully sent, or failed.
     *  The result code will be <code>Activity.RESULT_OK<code> for success,
     *  or one of these errors:<br>
     *  <code>RESULT_ERROR_GENERIC_FAILURE</code><br>
     *  <code>RESULT_ERROR_RADIO_OFF</code><br>
     *  <code>RESULT_ERROR_NULL_PDU</code><br>
     *  For <code>RESULT_ERROR_GENERIC_FAILURE</code> the sentIntent may include
     *  the extra "errorCode" containing a radio technology specific value,
     *  generally only useful for troubleshooting.<br>
     *  The per-application based SMS control checks sentIntent. If sentIntent
     *  is NULL the caller will be checked against all unknown applications,
     *  which cause smaller number of SMS to be sent in checking period.
     * @param deliveryIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is delivered to the recipient.  The
     *  raw pdu of the status report is in the extended data ("pdu").
     */

    public void sendData(String callingPackage, String destAddr, String scAddr, int destPort,
            byte[] data, PendingIntent sentIntent, PendingIntent deliveryIntent) {
        mPhone.getContext().enforceCallingPermission(
                Manifest.permission.SEND_SMS,
                "Sending SMS message");
        // MTK-START
        if (MobileManagerUtils.isSupported()) {
            Bundle extraInfo = new Bundle();
            extraInfo.putString(IMobileManager.SMS_MESSAGE_RECIPIENT, destAddr);
            extraInfo.putByteArray(IMobileManager.SMS_MESSAGE_DATA, data);
            if (checkPermissionByUser(extraInfo, "sendData()") == false) {
                return;
            }
        }
        // MTK-END
        if (Rlog.isLoggable("SMS", Log.VERBOSE)) {
            log("sendData: destAddr=" + destAddr + " scAddr=" + scAddr + " destPort=" +
                destPort + " data='"+ HexDump.toHexString(data)  + "' sentIntent=" +
                sentIntent + " deliveryIntent=" + deliveryIntent);
        }
        if (mAppOps.noteOp(AppOpsManager.OP_SEND_SMS, Binder.getCallingUid(),
                callingPackage) != AppOpsManager.MODE_ALLOWED) {
            return;
        }
        destAddr = filterDestAddress(destAddr);
        mDispatcher.sendData(destAddr, scAddr, destPort, data, sentIntent, deliveryIntent);
    }

    /**
     * Send a text based SMS.
     *
     * @param destAddr the address to send the message to
     * @param scAddr is the service center address or null to use
     *  the current default SMSC
     * @param text the body of the message to send
     * @param sentIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is successfully sent, or failed.
     *  The result code will be <code>Activity.RESULT_OK<code> for success,
     *  or one of these errors:<br>
     *  <code>RESULT_ERROR_GENERIC_FAILURE</code><br>
     *  <code>RESULT_ERROR_RADIO_OFF</code><br>
     *  <code>RESULT_ERROR_NULL_PDU</code><br>
     *  For <code>RESULT_ERROR_GENERIC_FAILURE</code> the sentIntent may include
     *  the extra "errorCode" containing a radio technology specific value,
     *  generally only useful for troubleshooting.<br>
     *  The per-application based SMS control checks sentIntent. If sentIntent
     *  is NULL the caller will be checked against all unknown applications,
     *  which cause smaller number of SMS to be sent in checking period.
     * @param deliveryIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is delivered to the recipient.  The
     *  raw pdu of the status report is in the extended data ("pdu").
     */

    public void sendText(String callingPackage, String destAddr, String scAddr,
            String text, PendingIntent sentIntent, PendingIntent deliveryIntent) {
        mPhone.getContext().enforceCallingPermission(
                Manifest.permission.SEND_SMS,
                "Sending SMS message");
        // MTK-START
        if (MobileManagerUtils.isSupported()) {
            Bundle extraInfo = new Bundle();
            extraInfo.putString(IMobileManager.SMS_MESSAGE_RECIPIENT, destAddr);
            extraInfo.putString(IMobileManager.SMS_MESSAGE_TEXT, text);
            if (checkPermissionByUser(extraInfo, "sendText()") == false) {
                return;
            }
        }
        // MTK-END
        if (Rlog.isLoggable("SMS", Log.VERBOSE)) {
            log("sendText: destAddr=" + destAddr + " scAddr=" + scAddr +
                " text='"+ text + "' sentIntent=" +
                sentIntent + " deliveryIntent=" + deliveryIntent);
        }
        if (mAppOps.noteOp(AppOpsManager.OP_SEND_SMS, Binder.getCallingUid(),
                callingPackage) != AppOpsManager.MODE_ALLOWED) {
            return;
        }
        destAddr = filterDestAddress(destAddr);
        mDispatcher.sendText(destAddr, scAddr, text, sentIntent, deliveryIntent,
                null/*messageUri*/, callingPackage);
    }

    /**
     * Inject an SMS PDU into the android application framework.
     *
     * @param pdu is the byte array of pdu to be injected into android application framework
     * @param format is the format of SMS pdu (3gpp or 3gpp2)
     * @param receivedIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is successfully received by the
     *  android application framework. This intent is broadcasted at
     *  the same time an SMS received from radio is acknowledged back.
     */
    public void injectSmsPdu(byte[] pdu, String format, PendingIntent receivedIntent) {
        enforceCarrierPrivilege();
        if (Rlog.isLoggable("SMS", Log.VERBOSE)) {
            log("pdu: " + pdu +
                "\n format=" + format +
                "\n receivedIntent=" + receivedIntent);
        }
        mDispatcher.injectSmsPdu(pdu, format, receivedIntent);
    }

    /**
     * Update the status of a pending (send-by-IP) SMS message and resend by PSTN if necessary.
     * This outbound message was handled by the carrier app. If the carrier app fails to send
     * this message, it would be resent by PSTN.
     *
     * @param messageRef the reference number of the SMS message.
     * @param success True if and only if the message was sent successfully. If its value is
     *  false, this message should be resent via PSTN.
     * {@hide}
     */
    public void updateSmsSendStatus(int messageRef, boolean success) {
        enforceCarrierPrivilege();
        mDispatcher.updateSmsSendStatus(messageRef, success);
    }

    /**
     * Send a multi-part text based SMS.
     *
     * @param destAddr the address to send the message to
     * @param scAddr is the service center address or null to use
     *   the current default SMSC
     * @param parts an <code>ArrayList</code> of strings that, in order,
     *   comprise the original message
     * @param sentIntents if not null, an <code>ArrayList</code> of
     *   <code>PendingIntent</code>s (one for each message part) that is
     *   broadcast when the corresponding message part has been sent.
     *   The result code will be <code>Activity.RESULT_OK<code> for success,
     *   or one of these errors:
     *   <code>RESULT_ERROR_GENERIC_FAILURE</code>
     *   <code>RESULT_ERROR_RADIO_OFF</code>
     *   <code>RESULT_ERROR_NULL_PDU</code>.
     *  The per-application based SMS control checks sentIntent. If sentIntent
     *  is NULL the caller will be checked against all unknown applications,
     *  which cause smaller number of SMS to be sent in checking period.
     * @param deliveryIntents if not null, an <code>ArrayList</code> of
     *   <code>PendingIntent</code>s (one for each message part) that is
     *   broadcast when the corresponding message part has been delivered
     *   to the recipient.  The raw pdu of the status report is in the
     *   extended data ("pdu").
     */

    public void sendMultipartText(String callingPackage, String destAddr, String scAddr,
            List<String> parts, List<PendingIntent> sentIntents,
            List<PendingIntent> deliveryIntents) {
        mPhone.getContext().enforceCallingPermission(
                Manifest.permission.SEND_SMS,
                "Sending SMS message");
        // MTK-START
        if (MobileManagerUtils.isSupported()) {
            ArrayList list = new ArrayList();
            list.add(parts);
            Bundle extraInfo = new Bundle();
            extraInfo.putString(IMobileManager.SMS_MESSAGE_RECIPIENT, destAddr);
            extraInfo.putParcelableArrayList(IMobileManager.SMS_MESSAGE_MULTIPARTTEXT, list);
            if (checkPermissionByUser(extraInfo, "sendMultipartText()") == false) {
                return;
            }
        }
        // MTK-END
        if (Rlog.isLoggable("SMS", Log.VERBOSE)) {
            int i = 0;
            for (String part : parts) {
                log("sendMultipartText: destAddr=" + destAddr + ", srAddr=" + scAddr +
                        ", part[" + (i++) + "]=" + part);
            }
        }
        if (mAppOps.noteOp(AppOpsManager.OP_SEND_SMS, Binder.getCallingUid(),
                callingPackage) != AppOpsManager.MODE_ALLOWED) {
            return;
        }

        if (parts.size() > 1 && parts.size() < 10 && !SmsMessage.hasEmsSupport()) {
            for (int i = 0; i < parts.size(); i++) {
                // If EMS is not supported, we have to break down EMS into single segment SMS
                // and add page info " x/y".
                String singlePart = parts.get(i);
                if (SmsMessage.shouldAppendPageNumberAsPrefix()) {
                    singlePart = String.valueOf(i + 1) + '/' + parts.size() + ' ' + singlePart;
                } else {
                    singlePart = singlePart.concat(' ' + String.valueOf(i + 1) + '/' + parts.size());
                }

                PendingIntent singleSentIntent = null;
                if (sentIntents != null && sentIntents.size() > i) {
                    singleSentIntent = sentIntents.get(i);
                }

                PendingIntent singleDeliveryIntent = null;
                if (deliveryIntents != null && deliveryIntents.size() > i) {
                    singleDeliveryIntent = deliveryIntents.get(i);
                }

                mDispatcher.sendText(destAddr, scAddr, singlePart,
                        singleSentIntent, singleDeliveryIntent,
                        null/*messageUri*/, callingPackage);
            }
            return;
        }

        mDispatcher.sendMultipartText(destAddr, scAddr, (ArrayList<String>) parts,
                (ArrayList<PendingIntent>) sentIntents, (ArrayList<PendingIntent>) deliveryIntents,
                null/*messageUri*/, callingPackage);
    }


    public int getPremiumSmsPermission(String packageName) {
        return mDispatcher.getPremiumSmsPermission(packageName);
    }


    public void setPremiumSmsPermission(String packageName, int permission) {
        mDispatcher.setPremiumSmsPermission(packageName, permission);
    }

    /**
     * create SmsRawData lists from all sms record byte[]
     * Use null to indicate "free" record
     *
     * @param messages List of message records from EF_SMS.
     * @return SmsRawData list of all in-used records
     */
    protected ArrayList<SmsRawData> buildValidRawData(ArrayList<byte[]> messages) {
        int count = messages.size();
        ArrayList<SmsRawData> ret;

        ret = new ArrayList<SmsRawData>(count);

        // MTK-START/
        int validSmsCount = 0;
        // MTK-END/
        for (int i = 0; i < count; i++) {
            byte[] ba = messages.get(i);
            if (ba[0] == STATUS_ON_ICC_FREE) {
                ret.add(null);
            } else {
                // MTK-START
                validSmsCount++;
                // MTK-END
                ret.add(new SmsRawData(messages.get(i)));
            }
        }
        // MTK-START
        log("validSmsCount = " + validSmsCount);
        // MTK-END

        return ret;
    }

    /**
     * Generates an EF_SMS record from status and raw PDU.
     *
     * @param status Message status.  See TS 51.011 10.5.3.
     * @param pdu Raw message PDU.
     * @return byte array for the record.
     */
    protected byte[] makeSmsRecordData(int status, byte[] pdu) {
        byte[] data;
        if (PhoneConstants.PHONE_TYPE_GSM == mPhone.getPhoneType()) {
            data = new byte[IccConstants.SMS_RECORD_LENGTH];
        } else {
            data = new byte[IccConstants.CDMA_SMS_RECORD_LENGTH];
        }

        // Status bits for this record.  See TS 51.011 10.5.3
        data[0] = (byte)(status & 7);
        // MTK-START
        log("ISIM-makeSmsRecordData: pdu size = " + pdu.length);
        if (pdu.length == IccConstants.SMS_RECORD_LENGTH) {
            log("ISIM-makeSmsRecordData: sim pdu");
            try {
                System.arraycopy(pdu, 1, data, 1, pdu.length - 1);
            } catch (ArrayIndexOutOfBoundsException e) {
                log("ISIM-makeSmsRecordData: out of bounds, sim pdu");
            }
        } else {
            log("ISIM-makeSmsRecordData: normal pdu");
            try {
                System.arraycopy(pdu, 0, data, 1, pdu.length);
            } catch (ArrayIndexOutOfBoundsException e) {
                log("ISIM-makeSmsRecordData: out of bounds, normal pdu");
            }
        }
        // MTK-END

        // Pad out with 0xFF's.
        for (int j = pdu.length+1; j < data.length; j++) {
            data[j] = -1;
        }

        return data;
    }

    public boolean enableCellBroadcast(int messageIdentifier) {
        return enableCellBroadcastRange(messageIdentifier, messageIdentifier);
    }

    public boolean disableCellBroadcast(int messageIdentifier) {
        return disableCellBroadcastRange(messageIdentifier, messageIdentifier);
    }

    public boolean enableCellBroadcastRange(int startMessageId, int endMessageId) {
        if (PhoneConstants.PHONE_TYPE_GSM == mPhone.getPhoneType()) {
            return enableGsmBroadcastRange(startMessageId, endMessageId);
        } else {
            return enableCdmaBroadcastRange(startMessageId, endMessageId);
        }
    }

    public boolean disableCellBroadcastRange(int startMessageId, int endMessageId) {
        if (PhoneConstants.PHONE_TYPE_GSM == mPhone.getPhoneType()) {
            return disableGsmBroadcastRange(startMessageId, endMessageId);
        } else {
            return disableCdmaBroadcastRange(startMessageId, endMessageId);
        }
    }

    synchronized public boolean enableGsmBroadcastRange(int startMessageId, int endMessageId) {
        if (DBG) log("enableGsmBroadcastRange");

        Context context = mPhone.getContext();

        context.enforceCallingPermission(
                "android.permission.RECEIVE_SMS",
                "Enabling cell broadcast SMS");

        String client = context.getPackageManager().getNameForUid(
                Binder.getCallingUid());

        if (!mCellBroadcastRangeManager.enableRange(startMessageId, endMessageId, client)) {
            log("Failed to add cell broadcast subscription for MID range " + startMessageId
                    + " to " + endMessageId + " from client " + client);
            return false;
        }

        if (DBG)
            log("Added cell broadcast subscription for MID range " + startMessageId
                    + " to " + endMessageId + " from client " + client);

        setCellBroadcastActivation(!mCellBroadcastRangeManager.isEmpty());

        return true;
    }

    synchronized public boolean disableGsmBroadcastRange(int startMessageId, int endMessageId) {
        if (DBG) log("disableGsmBroadcastRange");

        Context context = mPhone.getContext();

        context.enforceCallingPermission(
                "android.permission.RECEIVE_SMS",
                "Disabling cell broadcast SMS");

        String client = context.getPackageManager().getNameForUid(
                Binder.getCallingUid());

        if (!mCellBroadcastRangeManager.disableRange(startMessageId, endMessageId, client)) {
            log("Failed to remove cell broadcast subscription for MID range " + startMessageId
                    + " to " + endMessageId + " from client " + client);
            return false;
        }

        if (DBG)
            log("Removed cell broadcast subscription for MID range " + startMessageId
                    + " to " + endMessageId + " from client " + client);

        setCellBroadcastActivation(!mCellBroadcastRangeManager.isEmpty());

        return true;
    }

    synchronized public boolean enableCdmaBroadcastRange(int startMessageId, int endMessageId) {
        if (DBG) log("enableCdmaBroadcastRange");

        Context context = mPhone.getContext();

        context.enforceCallingPermission(
                "android.permission.RECEIVE_SMS",
                "Enabling cdma broadcast SMS");

        String client = context.getPackageManager().getNameForUid(
                Binder.getCallingUid());

        if (!mCdmaBroadcastRangeManager.enableRange(startMessageId, endMessageId, client)) {
            log("Failed to add cdma broadcast subscription for MID range " + startMessageId
                    + " to " + endMessageId + " from client " + client);
            return false;
        }

        if (DBG)
            log("Added cdma broadcast subscription for MID range " + startMessageId
                    + " to " + endMessageId + " from client " + client);

        setCdmaBroadcastActivation(!mCdmaBroadcastRangeManager.isEmpty());

        return true;
    }

    synchronized public boolean disableCdmaBroadcastRange(int startMessageId, int endMessageId) {
        if (DBG) log("disableCdmaBroadcastRange");

        Context context = mPhone.getContext();

        context.enforceCallingPermission(
                "android.permission.RECEIVE_SMS",
                "Disabling cell broadcast SMS");

        String client = context.getPackageManager().getNameForUid(
                Binder.getCallingUid());

        if (!mCdmaBroadcastRangeManager.disableRange(startMessageId, endMessageId, client)) {
            log("Failed to remove cdma broadcast subscription for MID range " + startMessageId
                    + " to " + endMessageId + " from client " + client);
            return false;
        }

        if (DBG)
            log("Removed cdma broadcast subscription for MID range " + startMessageId
                    + " to " + endMessageId + " from client " + client);

        setCdmaBroadcastActivation(!mCdmaBroadcastRangeManager.isEmpty());

        return true;
    }

    class CellBroadcastRangeManager extends IntRangeManager {
        private ArrayList<SmsBroadcastConfigInfo> mConfigList =
                new ArrayList<SmsBroadcastConfigInfo>();

        /**
         * Called when the list of enabled ranges has changed. This will be
         * followed by zero or more calls to {@link #addRange} followed by
         * a call to {@link #finishUpdate}.
         */
        protected void startUpdate() {
            mConfigList.clear();
        }

        /**
         * Called after {@link #startUpdate} to indicate a range of enabled
         * values.
         * @param startId the first id included in the range
         * @param endId the last id included in the range
         */
        protected void addRange(int startId, int endId, boolean selected) {
            mConfigList.add(new SmsBroadcastConfigInfo(startId, endId,
                        SMS_CB_CODE_SCHEME_MIN, SMS_CB_CODE_SCHEME_MAX, selected));
        }

        /**
         * Called to indicate the end of a range update started by the
         * previous call to {@link #startUpdate}.
         * @return true if successful, false otherwise
         */
        protected boolean finishUpdate() {
            if (mConfigList.isEmpty()) {
                return true;
            } else {
                SmsBroadcastConfigInfo[] configs =
                        mConfigList.toArray(new SmsBroadcastConfigInfo[mConfigList.size()]);
                return setCellBroadcastConfig(configs);
            }
        }
    }

    class CdmaBroadcastRangeManager extends IntRangeManager {
        private ArrayList<CdmaSmsBroadcastConfigInfo> mConfigList =
                new ArrayList<CdmaSmsBroadcastConfigInfo>();

        /**
         * Called when the list of enabled ranges has changed. This will be
         * followed by zero or more calls to {@link #addRange} followed by a
         * call to {@link #finishUpdate}.
         */
        protected void startUpdate() {
            mConfigList.clear();
        }

        /**
         * Called after {@link #startUpdate} to indicate a range of enabled
         * values.
         * @param startId the first id included in the range
         * @param endId the last id included in the range
         */
        protected void addRange(int startId, int endId, boolean selected) {
            mConfigList.add(new CdmaSmsBroadcastConfigInfo(startId, endId,
                    1, selected));
        }

        /**
         * Called to indicate the end of a range update started by the previous
         * call to {@link #startUpdate}.
         * @return true if successful, false otherwise
         */
        protected boolean finishUpdate() {
            if (mConfigList.isEmpty()) {
                return true;
            } else {
                CdmaSmsBroadcastConfigInfo[] configs =
                        mConfigList.toArray(new CdmaSmsBroadcastConfigInfo[mConfigList.size()]);
                return setCdmaBroadcastConfig(configs);
            }
        }
    }

    private boolean setCellBroadcastConfig(SmsBroadcastConfigInfo[] configs) {
        if (DBG)
            log("Calling setGsmBroadcastConfig with " + configs.length + " configurations");

        synchronized (mLock) {
            Message response = mHandler.obtainMessage(EVENT_SET_BROADCAST_CONFIG_DONE);

            mSuccess = false;
            mPhone.mCi.setGsmBroadcastConfig(configs, response);

            try {
                mLock.wait();
            } catch (InterruptedException e) {
                log("interrupted while trying to set cell broadcast config");
            }
        }

        return mSuccess;
    }

    private boolean setCellBroadcastActivation(boolean activate) {
        if (DBG)
            log("Calling setCellBroadcastActivation(" + activate + ')');

        synchronized (mLock) {
            Message response = mHandler.obtainMessage(EVENT_SET_BROADCAST_ACTIVATION_DONE);

            mSuccess = false;
            mPhone.mCi.setGsmBroadcastActivation(activate, response);

            try {
                mLock.wait();
            } catch (InterruptedException e) {
                log("interrupted while trying to set cell broadcast activation");
            }
        }

        // MTK-START, clear all ranges once close successfully
        if (!activate && mSuccess) {
            mCellBroadcastRangeManager.clearAllRanges();
        }
        // MTK-END
        return mSuccess;
    }

    private boolean setCdmaBroadcastConfig(CdmaSmsBroadcastConfigInfo[] configs) {
        if (DBG)
            log("Calling setCdmaBroadcastConfig with " + configs.length + " configurations");

        synchronized (mLock) {
            Message response = mHandler.obtainMessage(EVENT_SET_BROADCAST_CONFIG_DONE);

            mSuccess = false;
            mPhone.mCi.setCdmaBroadcastConfig(configs, response);

            try {
                mLock.wait();
            } catch (InterruptedException e) {
                log("interrupted while trying to set cdma broadcast config");
            }
        }

        return mSuccess;
    }

    private boolean setCdmaBroadcastActivation(boolean activate) {
        if (DBG)
            log("Calling setCdmaBroadcastActivation(" + activate + ")");

        synchronized (mLock) {
            Message response = mHandler.obtainMessage(EVENT_SET_BROADCAST_ACTIVATION_DONE);

            mSuccess = false;
            mPhone.mCi.setCdmaBroadcastActivation(activate, response);

            try {
                mLock.wait();
            } catch (InterruptedException e) {
                log("interrupted while trying to set cdma broadcast activation");
            }
        }

        return mSuccess;
    }

    protected void log(String msg) {
        // MTK-START, Print log to radio in order to easy debug
        Rlog.d(LOG_TAG, "[IccSmsInterfaceManager] " + msg);
        // MTK-END
    }

    public boolean isImsSmsSupported() {
        return mDispatcher.isIms();
    }

    public String getImsSmsFormat() {
        return mDispatcher.getImsSmsFormat();
    }

    public void sendStoredText(String callingPkg, Uri messageUri, String scAddress,
            PendingIntent sentIntent, PendingIntent deliveryIntent) {
        mPhone.getContext().enforceCallingPermission(Manifest.permission.SEND_SMS,
                "Sending SMS message");
        if (Rlog.isLoggable("SMS", Log.VERBOSE)) {
            log("sendStoredText: scAddr=" + scAddress + " messageUri=" + messageUri
                    + " sentIntent=" + sentIntent + " deliveryIntent=" + deliveryIntent);
        }
        if (mAppOps.noteOp(AppOpsManager.OP_SEND_SMS, Binder.getCallingUid(), callingPkg)
                != AppOpsManager.MODE_ALLOWED) {
            return;
        }
        final ContentResolver resolver = mPhone.getContext().getContentResolver();
        if (!isFailedOrDraft(resolver, messageUri)) {
            Log.e(LOG_TAG, "[IccSmsInterfaceManager]sendStoredText: not FAILED or DRAFT message");
            returnUnspecifiedFailure(sentIntent);
            return;
        }
        final String[] textAndAddress = loadTextAndAddress(resolver, messageUri);
        if (textAndAddress == null) {
            Log.e(LOG_TAG, "[IccSmsInterfaceManager]sendStoredText: can not load text");
            returnUnspecifiedFailure(sentIntent);
            return;
        }
        textAndAddress[1] = filterDestAddress(textAndAddress[1]);
        mDispatcher.sendText(textAndAddress[1], scAddress, textAndAddress[0],
                sentIntent, deliveryIntent, messageUri, callingPkg);
    }

    public void sendStoredMultipartText(String callingPkg, Uri messageUri, String scAddress,
            List<PendingIntent> sentIntents, List<PendingIntent> deliveryIntents) {
        mPhone.getContext().enforceCallingPermission(Manifest.permission.SEND_SMS,
                "Sending SMS message");
        if (mAppOps.noteOp(AppOpsManager.OP_SEND_SMS, Binder.getCallingUid(), callingPkg)
                != AppOpsManager.MODE_ALLOWED) {
            return;
        }
        final ContentResolver resolver = mPhone.getContext().getContentResolver();
        if (!isFailedOrDraft(resolver, messageUri)) {
            Log.e(LOG_TAG, "[IccSmsInterfaceManager]sendStoredMultipartText: "
                    + "not FAILED or DRAFT message");
            returnUnspecifiedFailure(sentIntents);
            return;
        }
        final String[] textAndAddress = loadTextAndAddress(resolver, messageUri);
        if (textAndAddress == null) {
            Log.e(LOG_TAG, "[IccSmsInterfaceManager]sendStoredMultipartText: can not load text");
            returnUnspecifiedFailure(sentIntents);
            return;
        }
        final ArrayList<String> parts = SmsManager.getDefault().divideMessage(textAndAddress[0]);
        if (parts == null || parts.size() < 1) {
            Log.e(LOG_TAG, "[IccSmsInterfaceManager]sendStoredMultipartText: can not divide text");
            returnUnspecifiedFailure(sentIntents);
            return;
        }

        if (parts.size() > 1 && parts.size() < 10 && !SmsMessage.hasEmsSupport()) {
            for (int i = 0; i < parts.size(); i++) {
                // If EMS is not supported, we have to break down EMS into single segment SMS
                // and add page info " x/y".
                String singlePart = parts.get(i);
                if (SmsMessage.shouldAppendPageNumberAsPrefix()) {
                    singlePart = String.valueOf(i + 1) + '/' + parts.size() + ' ' + singlePart;
                } else {
                    singlePart = singlePart.concat(' ' + String.valueOf(i + 1) + '/' + parts.size());
                }

                PendingIntent singleSentIntent = null;
                if (sentIntents != null && sentIntents.size() > i) {
                    singleSentIntent = sentIntents.get(i);
                }

                PendingIntent singleDeliveryIntent = null;
                if (deliveryIntents != null && deliveryIntents.size() > i) {
                    singleDeliveryIntent = deliveryIntents.get(i);
                }

                mDispatcher.sendText(textAndAddress[1], scAddress, singlePart,
                        singleSentIntent, singleDeliveryIntent, messageUri, callingPkg);
            }
            return;
        }

        textAndAddress[1] = filterDestAddress(textAndAddress[1]);
        mDispatcher.sendMultipartText(
                textAndAddress[1], // destAddress
                scAddress,
                parts,
                (ArrayList<PendingIntent>) sentIntents,
                (ArrayList<PendingIntent>) deliveryIntents,
                messageUri,
                callingPkg);
    }

    private boolean isFailedOrDraft(ContentResolver resolver, Uri messageUri) {
        // Clear the calling identity and query the database using the phone user id
        // Otherwise the AppOps check in TelephonyProvider would complain about mismatch
        // between the calling uid and the package uid
        final long identity = Binder.clearCallingIdentity();
        Cursor cursor = null;
        try {
            cursor = resolver.query(
                    messageUri,
                    new String[]{ Telephony.Sms.TYPE },
                    null/*selection*/,
                    null/*selectionArgs*/,
                    null/*sortOrder*/);
            if (cursor != null && cursor.moveToFirst()) {
                final int type = cursor.getInt(0);
                return type == Telephony.Sms.MESSAGE_TYPE_DRAFT
                        || type == Telephony.Sms.MESSAGE_TYPE_FAILED;
            }
        } catch (SQLiteException e) {
            Log.e(LOG_TAG, "[IccSmsInterfaceManager]isFailedOrDraft: query message type failed", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
            Binder.restoreCallingIdentity(identity);
        }
        return false;
    }

    // Return an array including both the SMS text (0) and address (1)
    private String[] loadTextAndAddress(ContentResolver resolver, Uri messageUri) {
        // Clear the calling identity and query the database using the phone user id
        // Otherwise the AppOps check in TelephonyProvider would complain about mismatch
        // between the calling uid and the package uid
        final long identity = Binder.clearCallingIdentity();
        Cursor cursor = null;
        try {
            cursor = resolver.query(
                    messageUri,
                    new String[]{
                            Telephony.Sms.BODY,
                            Telephony.Sms.ADDRESS
                    },
                    null/*selection*/,
                    null/*selectionArgs*/,
                    null/*sortOrder*/);
            if (cursor != null && cursor.moveToFirst()) {
                return new String[]{ cursor.getString(0), cursor.getString(1) };
            }
        } catch (SQLiteException e) {
            Log.e(LOG_TAG, "[IccSmsInterfaceManager]loadText: query message text failed", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
            Binder.restoreCallingIdentity(identity);
        }
        return null;
    }

    private void returnUnspecifiedFailure(PendingIntent pi) {
        if (pi != null) {
            try {
                pi.send(SmsManager.RESULT_ERROR_GENERIC_FAILURE);
            } catch (PendingIntent.CanceledException e) {
                // ignore
            }
        }
    }

    private void returnUnspecifiedFailure(List<PendingIntent> pis) {
        if (pis == null) {
            return;
        }
        for (PendingIntent pi : pis) {
            returnUnspecifiedFailure(pi);
        }
    }

    private void enforceCarrierPrivilege() {
        UiccController controller = UiccController.getInstance();
        if (controller == null || controller.getUiccCard() == null) {
            throw new SecurityException("No Carrier Privilege: No UICC");
        }
        if (controller.getUiccCard().getCarrierPrivilegeStatusForCurrentTransaction(
                mContext.getPackageManager()) !=
                    TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS) {
            throw new SecurityException("No Carrier Privilege.");
        }
    }

    private String filterDestAddress(String destAddr) {
        String result  = null;
        result = SmsNumberUtils.filterDestAddr(mContext, destAddr);
        return result != null ? result : destAddr;
    }

    // MTK-START
    /**
     * Send a data based SMS to a specific application port with original port.
     *
     * @param destAddr the address to send the message to
     * @param scAddr is the service center address or null to use
     *  the current default SMSC
     * @param destPort the port to deliver the message to
     * @param originalPort the port to deliver the message from
     * @param data the body of the message to send
     * @param sentIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is successfully sent, or failed.
     *  The result code will be <code>Activity.RESULT_OK<code> for success,
     *  or one of these errors:<br>
     *  <code>RESULT_ERROR_GENERIC_FAILURE</code><br>
     *  <code>RESULT_ERROR_RADIO_OFF</code><br>
     *  <code>RESULT_ERROR_NULL_PDU</code><br>
     *  For <code>RESULT_ERROR_GENERIC_FAILURE</code> the sentIntent may include
     *  the extra "errorCode" containing a radio technology specific value,
     *  generally only useful for troubleshooting.<br>
     *  The per-application based SMS control checks sentIntent. If sentIntent
     *  is NULL the caller will be checked against all unknown applications,
     *  which cause smaller number of SMS to be sent in checking period.
     * @param deliveryIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is delivered to the recipient.  The
     *  raw pdu of the status report is in the extended data ("pdu").
     */
    public void sendDataWithOriginalPort(String callingPackage, String destAddr, String scAddr,
            int destPort, int originalPort, byte[] data, PendingIntent sentIntent,
            PendingIntent deliveryIntent) {
        Rlog.d(LOG_TAG, "Enter IccSmsInterfaceManager.sendDataWithOriginalPort");

        mPhone.getContext().enforceCallingPermission(
                Manifest.permission.SEND_SMS,
                "Sending SMS message");
        if (MobileManagerUtils.isSupported()) {
            Bundle extraInfo = new Bundle();
            extraInfo.putString(IMobileManager.SMS_MESSAGE_RECIPIENT, destAddr);
            extraInfo.putByteArray(IMobileManager.SMS_MESSAGE_DATA, data);
            if (checkPermissionByUser(extraInfo, "sendDataWithOriginalPort()") == false) {
                return;
            }
        }

        if (Rlog.isLoggable("SMS", Log.VERBOSE)) {
            log("sendData: destAddr=" + destAddr + " scAddr=" + scAddr + " destPort=" +
                destPort + " originalPort=" + originalPort + " data='" + HexDump.toHexString(data) +
                "' sentIntent=" + sentIntent + " deliveryIntent=" + deliveryIntent);
        }
        if (mAppOps.noteOp(AppOpsManager.OP_SEND_SMS, Binder.getCallingUid(),
                callingPackage) != AppOpsManager.MODE_ALLOWED) {
            return;
        }
        mDispatcher.sendData(destAddr, scAddr, destPort, originalPort, data, sentIntent,
                deliveryIntent);
    }

    /**
     * Send a multi-part data based SMS.
     *
     * @param destinationAddress the address to send the message to
     * @param scAddress is the service center address or null to use
     *   the current default SMSC
     * @param data an <code>ArrayList</code> of strings that, in order,
     *   comprise the original message
     * @param destPort the port to deliver the message to
     * @param data an array of data messages in order,
     *   comprise the original message
     * @param sentIntents if not null, an <code>ArrayList</code> of
     *   <code>PendingIntent</code>s (one for each message part) that is
     *   broadcast when the corresponding message part has been sent.
     *   The result code will be <code>Activity.RESULT_OK<code> for success,
     *   or one of these errors:
     *   <code>RESULT_ERROR_GENERIC_FAILURE</code>
     *   <code>RESULT_ERROR_RADIO_OFF</code>
     *   <code>RESULT_ERROR_NULL_PDU</code>.
     * @param deliveryIntents if not null, an <code>ArrayList</code> of
     *   <code>PendingIntent</code>s (one for each message part) that is
     *   broadcast when the corresponding message part has been delivered
     *   to the recipient.  The raw pdu of the status report is in the
     *   extended data ("pdu").
     */
    public void sendMultipartData(
            String callingPackage,
            String destAddr,
            String scAddr,
            int destPort,
            List<SmsRawData> data,
            List<PendingIntent> sentIntents,
            List<PendingIntent> deliveryIntents) {

        mPhone.getContext().enforceCallingPermission(
                Manifest.permission.SEND_SMS,
                "Sending SMS message");

        if (MobileManagerUtils.isSupported()) {
            ArrayList list = new ArrayList();
            list.add(data);
            Bundle extraInfo = new Bundle();
            extraInfo.putString(IMobileManager.SMS_MESSAGE_RECIPIENT, destAddr);
            extraInfo.putParcelableArrayList(IMobileManager.SMS_MESSAGE_MULTIPARTDATA, list);
            if (checkPermissionByUser(extraInfo, "sendMultipartData()") == false) {
                return;
            }
        }

        if (Rlog.isLoggable("SMS", Log.VERBOSE)) {
            for (SmsRawData rData : data) {
                log("sendMultipartData: destAddr=" + destAddr + " scAddr=" + scAddr + " destPort=" +
                        destPort + " data='" + HexDump.toHexString(rData.getBytes()));
            }
        }
        if (mAppOps.noteOp(AppOpsManager.OP_SEND_SMS, Binder.getCallingUid(),
                callingPackage) != AppOpsManager.MODE_ALLOWED) {
            return;
        }
        mDispatcher.sendMultipartData(destAddr, scAddr, destPort, (ArrayList<SmsRawData>) data,
                (ArrayList<PendingIntent>) sentIntents, (ArrayList<PendingIntent>) deliveryIntents);
    }

    /**
     * Set the memory storage status of the SMS This function is used for FTA
     * test only
     *
     * @param status false for storage full, true for storage available
     */
    public void setSmsMemoryStatus(boolean status) {
        log("setSmsMemoryStatus: set storage status -> " + status);
        mDispatcher.setSmsMemoryStatus(status);
    }

    /**
     * Judge if SMS subsystem is ready or not
     *
     * @return true for success
     */
    public boolean isSmsReady() {
        boolean isReady = mDispatcher.isSmsReady();

        log("isSmsReady: " + isReady);
        return isReady;
    }

    /**
     * Send an SMS with specified encoding type.
     *
     * @param smsc the SMSC to send the message through, or NULL for the
     *  default SMSC
     * @param text the body of the message to send
     * @param encodingType the encoding type of content of message(GSM 7-bit, Unicode or Automatic)
     * @param sentIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is sucessfully sent, or failed.
     *  The result code will be <code>Activity.RESULT_OK<code> for success,
     *  or one of these errors:<br>
     *  <code>RESULT_ERROR_GENERIC_FAILURE</code><br>
     *  <code>RESULT_ERROR_RADIO_OFF</code><br>
     *  <code>RESULT_ERROR_NULL_PDU</code><br>
     *  For <code>RESULT_ERROR_GENERIC_FAILURE</code> the sentIntent may include
     *  the extra "errorCode" containing a radio technology specific value,
     *  generally only useful for troubleshooting.<br>
     *  The per-application based SMS control checks sentIntent. If sentIntent
     *  is NULL the caller will be checked against all unknown applications,
     *  which cause smaller number of SMS to be sent in checking period.
     * @param deliveryIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is delivered to the recipient.  The
     *  raw pdu of the status report is in the extended data ("pdu").
     */
    public void sendTextWithEncodingType(String callingPackage, String destAddr, String scAddr,
            String text, int encodingType, PendingIntent sentIntent, PendingIntent deliveryIntent) {
        mPhone.getContext().enforceCallingPermission(
                Manifest.permission.SEND_SMS,
                "Sending SMS message");

        if (MobileManagerUtils.isSupported()) {
            Bundle extraInfo = new Bundle();
            extraInfo.putString(IMobileManager.SMS_MESSAGE_RECIPIENT, destAddr);
            extraInfo.putString(IMobileManager.SMS_MESSAGE_TEXT, text);
            if (checkPermissionByUser(extraInfo, "sendTextWithEncodingType()") == false) {
                return;
            }
        }

        if (mAppOps.noteOp(AppOpsManager.OP_SEND_SMS, Binder.getCallingUid(),
                callingPackage) != AppOpsManager.MODE_ALLOWED) {
            return;
        }
        mDispatcher.sendTextWithEncodingType(destAddr, scAddr, text, encodingType, sentIntent,
                deliveryIntent, null/*messageUri*/, callingPackage);
    }

    /**
     * Send a multi-part text based SMS with specified encoding type.
     *
     * @param destinationAddress the address to send the message to
     * @param scAddr is the service center address or null to use
     *   the current default SMSC
     * @param parts an <code>ArrayList</code> of strings that, in order,
     *   comprise the original message
     * @param encodingType the encoding type of content of message(GSM 7-bit, Unicode or Automatic)
     * @param sentIntents if not null, an <code>ArrayList</code> of
     *   <code>PendingIntent</code>s (one for each message part) that is
     *   broadcast when the corresponding message part has been sent.
     *   The result code will be <code>Activity.RESULT_OK<code> for success,
     *   or one of these errors:
     *   <code>RESULT_ERROR_GENERIC_FAILURE</code>
     *   <code>RESULT_ERROR_RADIO_OFF</code>
     *   <code>RESULT_ERROR_NULL_PDU</code>.
     * @param deliveryIntents if not null, an <code>ArrayList</code> of
     *   <code>PendingIntent</code>s (one for each message part) that is
     *   broadcast when the corresponding message part has been delivered
     *   to the recipient.  The raw pdu of the status report is in the
     *   extended data ("pdu").
     */
    public void sendMultipartTextWithEncodingType(String callingPackage, String destAddr,
            String scAddr, List<String> parts, int encodingType, List<PendingIntent> sentIntents,
            List<PendingIntent> deliveryIntents) {

        mPhone.getContext().enforceCallingPermission(
                Manifest.permission.SEND_SMS,
                "Sending SMS message");

        if (MobileManagerUtils.isSupported()) {
            ArrayList list = new ArrayList();
            list.add(parts);
            Bundle extraInfo = new Bundle();
            extraInfo.putString(IMobileManager.SMS_MESSAGE_RECIPIENT, destAddr);
            extraInfo.putParcelableArrayList(IMobileManager.SMS_MESSAGE_MULTIPARTTEXT, list);
            if (checkPermissionByUser(extraInfo, "sendMultipartTextWithEncodingType()") == false) {
                return;
            }
        }

        if (mAppOps.noteOp(AppOpsManager.OP_SEND_SMS, Binder.getCallingUid(),
                callingPackage) != AppOpsManager.MODE_ALLOWED) {
            return;
        }

        mDispatcher.sendMultipartTextWithEncodingType(destAddr, scAddr, (ArrayList<String>) parts,
                encodingType, (ArrayList<PendingIntent>) sentIntents,
                (ArrayList<PendingIntent>) deliveryIntents, null/*messageUri*/, callingPackage);
    }

    /**
     * Send an SMS with specified encoding type.
     *
     * @param destAddr the address to send the message to
     * @param scAddr the SMSC to send the message through, or NULL for the
     *  default SMSC
     * @param text the body of the message to send
     * @param extraParams extra parameters, such as validity period, encoding type
     * @param sentIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is sucessfully sent, or failed.
     * @param deliveryIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is delivered to the recipient.  The
     *  raw pdu of the status report is in the extended data ("pdu").
     */
    public void sendTextWithExtraParams(String callingPackage, String destAddr, String scAddr,
            String text, Bundle extraParams, PendingIntent sentIntent,
            PendingIntent deliveryIntent) {

        mPhone.getContext().enforceCallingPermission(
                Manifest.permission.SEND_SMS,
                "Sending SMS message");

        if (MobileManagerUtils.isSupported()) {
            Bundle extraInfo = new Bundle();
            extraInfo.putString(IMobileManager.SMS_MESSAGE_RECIPIENT, destAddr);
            extraInfo.putString(IMobileManager.SMS_MESSAGE_TEXT, text);
            if (checkPermissionByUser(extraInfo, "sendTextWithExtraParams()") == false) {
                return;
            }
        }

        if (mAppOps.noteOp(AppOpsManager.OP_SEND_SMS, Binder.getCallingUid(),
                callingPackage) != AppOpsManager.MODE_ALLOWED) {
            return;
        }
        mDispatcher.sendTextWithExtraParams(destAddr, scAddr, text, extraParams, sentIntent,
                deliveryIntent, null/*messageUri*/, callingPackage);
    }

    /**
     * Send a multi-part text based SMS with specified encoding type.
     *
     * @param destAddr the address to send the message to
     * @param scAddr is the service center address or null to use
     *   the current default SMSC
     * @param parts an <code>ArrayList</code> of strings that, in order,
     *   comprise the original message
     * @param extraParams extra parameters, such as validity period, encoding type
     * @param sentIntents if not null, an <code>ArrayList</code> of
     *   <code>PendingIntent</code>s (one for each message part) that is
     *   broadcast when the corresponding message part has been sent.
     * @param deliveryIntents if not null, an <code>ArrayList</code> of
     *   <code>PendingIntent</code>s (one for each message part) that is
     *   broadcast when the corresponding message part has been delivered
     *   to the recipient.  The raw pdu of the status report is in the
     *   extended data ("pdu").
     */
    public void sendMultipartTextWithExtraParams(String callingPackage, String destAddr,
            String scAddr, List<String> parts, Bundle extraParams, List<PendingIntent> sentIntents,
            List<PendingIntent> deliveryIntents) {

        mPhone.getContext().enforceCallingPermission(
                Manifest.permission.SEND_SMS,
                "Sending SMS message");

        if (MobileManagerUtils.isSupported()) {
            ArrayList list = new ArrayList();
            list.add(parts);
            Bundle extraInfo = new Bundle();
            extraInfo.putString(IMobileManager.SMS_MESSAGE_RECIPIENT, destAddr);
            extraInfo.putParcelableArrayList(IMobileManager.SMS_MESSAGE_MULTIPARTTEXT, list);
            if (checkPermissionByUser(extraInfo, "sendMultipartTextWithExtraParams()") == false) {
                return;
            }
        }

        if (mAppOps.noteOp(AppOpsManager.OP_SEND_SMS, Binder.getCallingUid(),
                callingPackage) != AppOpsManager.MODE_ALLOWED) {
            return;
        }
        mDispatcher.sendMultipartTextWithExtraParams(destAddr, scAddr, (ArrayList<String>) parts,
                extraParams, (ArrayList<PendingIntent>) sentIntents,
                (ArrayList<PendingIntent>) deliveryIntents, null/*messageUri*/, callingPackage);
    }

    public String getFormat() {
        return mDispatcher.getFormat();
    }

    /**
     * Receive the WIPE intent
     */
    private BroadcastReceiver mSmsWipeReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            log("Receive intent");
            if (intent.getAction().equals("com.mediatek.dm.LAWMO_WIPE")) {
                log("Receive wipe intent");
                Thread t = new Thread() {
                    public void run() {
                        log("Delete message on sub " + mPhone.getSubId());
                        Message response = mHandler.obtainMessage(EVENT_UPDATE_DONE);
                        mPhone.mCi.deleteSmsOnSim(-1, response);
                    }
                };
                t.start();
            }
        }
    };

    /**
     * Retrieves message currently stored on ICC by index.
     *
     * @return SmsRawData of sms on ICC
     */
    public SmsRawData getMessageFromIccEf(String callingPackage, int index) {
        log("getMessageFromIccEf");

        mPhone.getContext().enforceCallingPermission(
                "android.permission.RECEIVE_SMS",
                "Reading messages from SIM");
        if (mAppOps.noteOp(AppOpsManager.OP_READ_ICC_SMS, Binder.getCallingUid(),
                callingPackage) != AppOpsManager.MODE_ALLOWED) {
            return null;
        }

        mSmsRawData = null;
        synchronized (mLock) {
            /* icc file handler will be null while plug-out sim card */
            IccFileHandler fh = mPhone.getIccFileHandler();
            if (fh != null) {
                Message response = mHandler.obtainMessage(EVENT_LOAD_ONE_RECORD_DONE);
                mPhone.getIccFileHandler().loadEFLinearFixed(IccConstants.EF_SMS, index, response);

                try {
                    mLock.wait();
                } catch (InterruptedException e) {
                    log("interrupted while trying to load from the SIM");
                }
            }
        }

        return mSmsRawData;
    }

    public List<SmsRawData> getAllMessagesFromIccEfByMode(String callingPackage, int mode) {
        if (DBG) log("getAllMessagesFromIccEfByMode, mode=" + mode);
        if (mode < PhoneConstants.PHONE_TYPE_GSM || mode > PhoneConstants.PHONE_TYPE_CDMA) {
            log("getAllMessagesFromIccEfByMode wrong mode=" + mode);
            return mSms;
        }

        // MTK-START, SIM related operation will be called by non-phone process user
        mContext.enforceCallingOrSelfPermission(
                "android.permission.RECEIVE_SMS",
                "Reading messages from Icc");
        // MTK-END
        if (mAppOps.noteOp(AppOpsManager.OP_READ_ICC_SMS, Binder.getCallingUid(),
                callingPackage) != AppOpsManager.MODE_ALLOWED) {
            return new ArrayList<SmsRawData>();
        }
        // MTK-START
        synchronized (mLoadLock) {
        // MTK-END
            IccFileHandler fh = mPhone.getIccFileHandler();
            if (fh == null) {
                Rlog.e(LOG_TAG, "Cannot load Sms records. No icc card?");
                if (mSms != null) {
                    mSms.clear();
                    return mSms;
                }
            }

            Message response = mHandler.obtainMessage(EVENT_LOAD_DONE);
            mPhone.getIccFileHandler().loadEFLinearFixedAll(IccConstants.EF_SMS, mode, response);

            try {
                // MTK-START
                mLoadLock.wait();
                // MTK-END
            } catch (InterruptedException e) {
                log("interrupted while trying to load from the SIM");
            }
        }

        return mSms;
    }

    public SmsParameters getSmsParameters(String callingPackage) {
        log("getSmsParameters");
        enforceReceiveAndSend("Get SMS parametner on SIM");
        if (mAppOps.noteOp(AppOpsManager.OP_READ_ICC_SMS, Binder.getCallingUid(),
                callingPackage) != AppOpsManager.MODE_ALLOWED) {
            return null;
        }
        synchronized (mLock) {
            Message response = mHandler.obtainMessage(EVENT_GET_SMS_PARAMS);
            mPhone.mCi.getSmsParameters(response);

            try {
                mLock.wait();
            } catch (InterruptedException e) {
                log("interrupted while trying to get sms params");
            }
        }

        return mSmsParams;
    }

    public boolean setSmsParameters(String callingPackage, SmsParameters params) {
        log("setSmsParameters");
        enforceReceiveAndSend("Set SMS parametner on SIM");
        if (mAppOps.noteOp(AppOpsManager.OP_WRITE_ICC_SMS, Binder.getCallingUid(),
                callingPackage) != AppOpsManager.MODE_ALLOWED) {
            return false;
        }
        mSmsParamsSuccess = false;
        synchronized (mLock) {
            Message response = mHandler.obtainMessage(EVENT_SET_SMS_PARAMS);
            mPhone.mCi.setSmsParameters(params, response);

            try {
                mLock.wait();
            } catch (InterruptedException e) {
                log("interrupted while trying to get sms params");
            }
        }
        return mSmsParamsSuccess;
    }

    public int copyTextMessageToIccCard(String callingPkg, String scAddress, String address,
            List<String> text, int status, long timestamp) {
        if (DBG) {
            log("copyTextMessageToIccCard, sc address: " + scAddress + " address: " + address +
                    " message count: " + text.size() + " status: " + status + " timestamp: " +
                    timestamp);
        }
        enforceReceiveAndSend("Copying message to USIM/SIM");
        if (mAppOps.noteOp(AppOpsManager.OP_WRITE_ICC_SMS, Binder.getCallingUid(),
                callingPkg) != AppOpsManager.MODE_ALLOWED) {
            return RESULT_ERROR_GENERIC_FAILURE;
        }

        IccSmsStorageStatus memStatus;

        memStatus = getSmsSimMemoryStatus(callingPkg);

        if (memStatus == null) {
            log("Fail to get SIM memory status");
            return RESULT_ERROR_GENERIC_FAILURE;
        } else {
            if (memStatus.getUnused() < text.size()) {
                log("SIM memory is not enough");
                return RESULT_ERROR_SIM_MEM_FULL;
            }
        }

        // VIA add begin
        if (isCdma()) {
            SimSmsInsertStatus insertRet =
                    writeTextMessageToCdmaUIMCardAction(address, text, status, timestamp);
            if (insertRet != null) {
                return insertRet.insertStatus;
            } else {
                return RESULT_ERROR_GENERIC_FAILURE;
            }
        }
        // VIA add end

        return mDispatcher.copyTextMessageToIccCard(scAddress, address, text, status, timestamp);
    }

    public SimSmsInsertStatus insertTextMessageToIccCard(String callingPackage,
            String scAddress, String address, List<String> text, int status, long timestamp) {
        log("insertTextMessageToIccCard");
        enforceReceiveAndSend("insertText insert message into SIM");
        if (mAppOps.noteOp(AppOpsManager.OP_WRITE_ICC_SMS, Binder.getCallingUid(),
                callingPackage) != AppOpsManager.MODE_ALLOWED) {
            smsInsertRet.insertStatus = RESULT_ERROR_GENERIC_FAILURE;
            return smsInsertRet;
        }

        int msgCount = text.size();
        boolean isDeliverPdu = true;

        log("insertText scAddr=" + scAddress + ", addr=" + address + ", msgCount=" + msgCount
                + ", status=" + status + ", timestamp=" + timestamp);

        smsInsertRet.indexInIcc = "";

        IccSmsStorageStatus memStatus = getSmsSimMemoryStatus(callingPackage);
        if (memStatus != null) {
            int unused = memStatus.getUnused();
            if (unused < msgCount) {
                log("insertText SIM mem is not enough [" + unused + "/" + msgCount + "]");
                smsInsertRet.insertStatus = RESULT_ERROR_SIM_MEM_FULL;
                return smsInsertRet;
            }
        } else {
            log("insertText fail to get SIM mem status");
            smsInsertRet.insertStatus = RESULT_ERROR_GENERIC_FAILURE;
            return smsInsertRet;
        }

        if (checkPhoneNumberInternal(scAddress) == false) {
            log("insertText invalid sc address");
            scAddress = null;
        }

        if (checkPhoneNumberInternal(address) == false) {
            log("insertText invalid address");
            smsInsertRet.insertStatus = RESULT_ERROR_INVALID_ADDRESS;
            return smsInsertRet;
        }

        if (status == STATUS_ON_ICC_READ || status == STATUS_ON_ICC_UNREAD) {
            log("insertText to encode delivery pdu");
            isDeliverPdu = true;
        } else if (status == STATUS_ON_ICC_SENT || status == STATUS_ON_ICC_UNSENT) {
            log("insertText to encode submit pdu");
            isDeliverPdu = false;
        } else {
            log("insertText invalid status " + status);
            smsInsertRet.insertStatus = RESULT_ERROR_GENERIC_FAILURE;
            return smsInsertRet;
        }
        log("insertText params check pass");

        // VIA add begin
        if (isCdma()) {
            return writeTextMessageToCdmaUIMCardAction(address, text, status, timestamp);
        }
        // VIA add end

        int encoding = SmsMessage.ENCODING_UNKNOWN;
        TextEncodingDetails details[] = new TextEncodingDetails[msgCount];
        for (int i = 0; i < msgCount; ++i) {
            details[i] = com.android.internal.telephony.gsm.SmsMessage.calculateLength(text.get(i),
                    false);
            if (encoding != details[i].codeUnitSize &&
                (encoding == SmsMessage.ENCODING_UNKNOWN || encoding == SmsMessage.ENCODING_7BIT)) {
                // use the USC2 if only one message is that coding style
                encoding = details[i].codeUnitSize;
            }
        }

        log("insertText create & insert pdu start...");
        for (int i = 0; i < msgCount; ++i) {
            if (mInsertMessageSuccess == false && i > 0) {
                log("insertText last message insert fail");
                smsInsertRet.insertStatus = RESULT_ERROR_GENERIC_FAILURE;
                return smsInsertRet;
            }

            int singleShiftId = -1;
            int lockingShiftId = -1;
            int language = details[i].shiftLangId;
            int encoding_detail = encoding;

            if (encoding == SmsMessage.ENCODING_7BIT) {
                if (details[i].languageTable > 0 && details[i].languageShiftTable > 0) {
                    singleShiftId = details[i].languageTable;
                    lockingShiftId = details[i].languageShiftTable;
                    encoding_detail =
                            com.android.internal.telephony.gsm.SmsMessage.ENCODING_7BIT_LOCKING_SINGLE;
                } else if (details[i].languageShiftTable > 0) {
                    lockingShiftId = details[i].languageShiftTable;
                    encoding_detail =
                            com.android.internal.telephony.gsm.SmsMessage.ENCODING_7BIT_LOCKING;
                } else if (details[i].languageTable > 0) {
                    singleShiftId = details[i].languageTable;
                    encoding_detail =
                            com.android.internal.telephony.gsm.SmsMessage.ENCODING_7BIT_SINGLE;
                }
            }

            byte[] smsHeader = null;
            if (msgCount > 1) {
                log("insertText create pdu header for concat-message");
                smsHeader = SmsHeader.getSubmitPduHeaderWithLang(-1, (getNextConcatRef() & 0xff),
                        (i + 1), msgCount, singleShiftId, lockingShiftId);
            }

            if (isDeliverPdu) {
                com.android.internal.telephony.gsm.SmsMessage.DeliverPdu pdu =
                        com.android.internal.telephony.gsm.SmsMessage.getDeliverPduWithLang(
                        scAddress, address, text.get(i), smsHeader, timestamp, encoding_detail,
                        language);
                if (pdu != null) {
                    mPhone.mCi.writeSmsToSim(status,
                            IccUtils.bytesToHexString(pdu.encodedScAddress),
                            IccUtils.bytesToHexString(pdu.encodedMessage),
                            mHandler.obtainMessage(EVENT_INSERT_TEXT_MESSAGE_TO_ICC_DONE));
                } else {
                    log("insertText fail to create deliver pdu");
                    smsInsertRet.insertStatus = RESULT_ERROR_GENERIC_FAILURE;
                    return smsInsertRet;
                }
            } else {
                com.android.internal.telephony.gsm.SmsMessage.SubmitPdu pdu =
                        com.android.internal.telephony.gsm.SmsMessage.getSubmitPduWithLang(
                        scAddress, address, text.get(i), false, smsHeader, encoding_detail,
                        language);
                if (pdu != null) {
                    mPhone.mCi.writeSmsToSim(status,
                            IccUtils.bytesToHexString(pdu.encodedScAddress),
                            IccUtils.bytesToHexString(pdu.encodedMessage),
                            mHandler.obtainMessage(EVENT_INSERT_TEXT_MESSAGE_TO_ICC_DONE));
                } else {
                    log("insertText fail to create submit pdu");
                    smsInsertRet.insertStatus = RESULT_ERROR_GENERIC_FAILURE;
                    return smsInsertRet;
                }
            }

            synchronized (mSimInsertLock) {
                try {
                    log("insertText wait until the pdu be wrote into the SIM");
                    mSimInsertLock.wait();
                } catch (InterruptedException e) {
                    log("insertText fail to insert pdu");
                    smsInsertRet.insertStatus = RESULT_ERROR_GENERIC_FAILURE;
                    return smsInsertRet;
                }
            }
        } // end loop for pdu creation & insertion
        log("insertText create & insert pdu end");

        if (mInsertMessageSuccess == true) {
            log("insertText all messages inserted");
            smsInsertRet.insertStatus = RESULT_ERROR_GENERIC_FAILURE;
            return smsInsertRet;
        }

        log("insertText pdu insert fail");
        smsInsertRet.insertStatus = RESULT_ERROR_GENERIC_FAILURE;
        return smsInsertRet;
    }

    public SimSmsInsertStatus insertRawMessageToIccCard(String callingPackage, int status,
            byte[] pdu, byte[] smsc) {
        if (DBG) log("insertRawMessageToIccCard");
        enforceReceiveAndSend("insertRaw insert message into SIM");
        if (mAppOps.noteOp(AppOpsManager.OP_WRITE_ICC_SMS, Binder.getCallingUid(),
                callingPackage) != AppOpsManager.MODE_ALLOWED) {
            smsInsertRet2.insertStatus = RESULT_ERROR_GENERIC_FAILURE;
            return smsInsertRet2;
        }
        synchronized (mLock) {
            mSuccess = false;
            smsInsertRet2.insertStatus = RESULT_ERROR_GENERIC_FAILURE;
            smsInsertRet2.indexInIcc = "";
            Message response = mHandler.obtainMessage(EVENT_UPDATE_DONE);

            //RIL_REQUEST_WRITE_SMS_TO_SIM vs RIL_REQUEST_CDMA_WRITE_SMS_TO_RUIM
            if (!isCdma()) {
                mPhone.mCi.writeSmsToSim(status, IccUtils.bytesToHexString(smsc),
                        IccUtils.bytesToHexString(pdu), response);
            } else {
                // VIA modified begin
                if (!writeCdmaPduToUIMCardAction(status, pdu, response)) {
                    return null;
                }
                // VIA modified end
            }

            try {
                mLock.wait();
            } catch (InterruptedException e) {
                log("insertRaw interrupted while trying to update by index");
            }
        }

        if (mSuccess == true) {
            log("insertRaw message inserted");
            smsInsertRet2.insertStatus = RESULT_ERROR_SUCCESS;
            return smsInsertRet2;
        }

        log("insertRaw pdu insert fail");
        smsInsertRet2.insertStatus = RESULT_ERROR_GENERIC_FAILURE;
        return smsInsertRet2;
    }

    public IccSmsStorageStatus getSmsSimMemoryStatus(String callingPackage) {
        if (DBG) log("getSmsSimMemoryStatus");
        enforceReceiveAndSend("Get SMS SIM Card Memory Status from RUIM");
        if (mAppOps.noteOp(AppOpsManager.OP_READ_ICC_SMS, Binder.getCallingUid(),
                callingPackage) != AppOpsManager.MODE_ALLOWED) {
            return null;
        }
        synchronized (mLock) {
            mSuccess = false;

            Message response = mHandler.obtainMessage(EVENT_GET_SMS_SIM_MEM_STATUS_DONE);

            mPhone.mCi.getSmsSimMemoryStatus(response);

            try {
                mLock.wait();
            } catch (InterruptedException e) {
                log("interrupted while trying to get SMS SIM Card Memory Status from SIM");
            }
        }

        if (mSuccess) {
            return mSimMemStatus;
        }

        return null;
    }

    public boolean setEtwsConfig(int mode) {
        if (DBG) log("Calling setEtwsConfig(" + mode + ')');

        synchronized (mLock) {
            Message response = mHandler.obtainMessage(EVENT_SET_ETWS_CONFIG_DONE);

            mSuccess = false;
            mPhone.mCi.setEtws(mode, response);

            try {
                mLock.wait();
            } catch (InterruptedException e) {
                log("interrupted while trying to set ETWS config");
            }
        }

        return mSuccess;
    }

    public boolean activateCellBroadcastSms(boolean activate) {
        log("activateCellBroadcastSms activate : " + activate);

        return setCellBroadcastActivation(activate);
    }

    private static int getNextConcatRef() {
        return sConcatenatedRef++;
    }

    private static boolean checkPhoneNumberCharacter(char c) {
        return (c >= '0' && c <= '9') || (c == '*') || (c == '+')
                || (c == '#') || (c == 'N') || (c == ' ') || (c == '-');
    }

    private static boolean checkPhoneNumberInternal(String number) {
        if (number == null) {
            return true;
        }

        for (int i = 0, n = number.length(); i < n; ++i) {
            if (checkPhoneNumberCharacter(number.charAt(i))) {
                continue;
            } else {
                return false;
            }
        }

        return true;
    }

    private SmsCbConfigInfo Convert2SmsCbConfigInfo(SmsBroadcastConfigInfo info) {
        return new SmsCbConfigInfo(
                info.getFromServiceId(),
                info.getToServiceId(),
                info.getFromCodeScheme(),
                info.getToCodeScheme(),
                info.isSelected());
    }

    private SmsBroadcastConfigInfo Convert2SmsBroadcastConfigInfo(SmsCbConfigInfo info) {
        return new SmsBroadcastConfigInfo(
                info.mFromServiceId,
                info.mToServiceId,
                info.mFromCodeScheme,
                info.mToCodeScheme,
                info.mSelected);
    }

    public SmsCbConfigInfo[] getCellBroadcastSmsConfig() {
        log("getCellBroadcastSmsConfig");

        synchronized (mLock) {
            Message response = mHandler.obtainMessage(EVENT_GET_BROADCAST_CONFIG_DONE);

            mSmsCBConfig = null;
            mPhone.mCi.getGsmBroadcastConfig(response);

            try {
                mLock.wait();
            } catch (InterruptedException e) {
                log("interrupted while trying to get CB config");
            }
        }

        if (mSmsCBConfig != null) {
            log("config length = " + mSmsCBConfig.length);
            int i = 0;
            if (mSmsCBConfig.length != 0) {
                SmsCbConfigInfo[] result = new SmsCbConfigInfo[mSmsCBConfig.length];
                for (i = 0; i < mSmsCBConfig.length; i++)
                    result[i] = Convert2SmsCbConfigInfo(mSmsCBConfig[i]);
                return result;
            }
        }

        return null;
    }

    public boolean setCellBroadcastSmsConfig(SmsCbConfigInfo[] channels,
                SmsCbConfigInfo[] languages) {
        log("setCellBroadcastSmsConfig");

        /* nothing to set, success */
        if (channels == null && languages == null) {
            return true;
        }

        synchronized (mLock) {
            Message response = mHandler.obtainMessage(EVENT_SET_BROADCAST_CONFIG_DONE);

            mSuccess = false;

            ArrayList<SmsBroadcastConfigInfo> chid_list = new ArrayList<SmsBroadcastConfigInfo>();
            if (channels != null) {
                for (int i = 0 ; i < channels.length ; i++) {
                    chid_list.add(Convert2SmsBroadcastConfigInfo(channels[i]));
                }
            }

            ArrayList<SmsBroadcastConfigInfo> lang_list = new ArrayList<SmsBroadcastConfigInfo>();
            if (languages != null) {
                for (int i = 0 ; i < languages.length ; i++) {
                    lang_list.add(Convert2SmsBroadcastConfigInfo(languages[i]));
                }
            }

            chid_list.addAll(lang_list);
            mPhone.mCi.setGsmBroadcastConfig(chid_list.toArray(new SmsBroadcastConfigInfo[1]), response);

            try {
                mLock.wait();
            } catch (InterruptedException e) {
                log("interrupted while trying to set CB config");
            }
        }

        return mSuccess;
    }

    public boolean queryCellBroadcastSmsActivation() {
        log("queryCellBroadcastSmsActivation");

        synchronized (mLock) {
            Message response = mHandler.obtainMessage(EVENT_GET_BROADCAST_ACTIVATION_DONE);

            mSuccess = false;
            mPhone.mCi.getGsmBroadcastConfig(response);

            try {
                mLock.wait();
            } catch (InterruptedException e) {
                log("interrupted while trying to get CB activation");
            }
        }

        return mSuccess;
    }

    public boolean removeCellBroadcastMsg(int channelId, int serialId) {
        if (DBG) log("removeCellBroadcastMsg(" + channelId + " , " + serialId + ")");

        synchronized (mLock) {
            Message response = mHandler.obtainMessage(EVENT_REMOVE_BROADCAST_MSG_DONE);

            mSuccess = false;
            mPhone.mCi.removeCellBroadcastMsg(channelId, serialId, response);

            try {
                mLock.wait();
            } catch (InterruptedException e) {
                log("interrupted while trying to remove CB msg");
            }
        }

        return mSuccess;
    }
    // MTK-END

    // VIA add - begin
    protected boolean isCdma() {
        if (null != mPhone && PhoneConstants.PHONE_TYPE_CDMA == mPhone.getPhoneType()) {
            return true;
        }
        return false;
    }

    /**
     * VIA add UIM sms cache process.
     * Only the platform use VIA cdma modem and
     * current IccSmsInterfaceMnager object is for cdma.
     */
    protected boolean needSmsCacheProcess() {
        if (SmsConstants.isEvdoDtSupport()) {
            if (isCdma()) {
                log("need do sms cache process work!");
                return true;
            }
        }
        log("dont need do sms cache process work!");
        return false;
    }

    /**
     * This function write one cdma PDU to uim card,
     * the pdu is google`s framwork submitpdu, is not real raw pdu defined in 3GPP2 spec,
     * for the google`s framework process differently in some fields.
     *
     * This func dont do uim sms cache work, must check and do it in the caller method.
     */
    protected boolean writeCdmaPduToUIMCardAction(int status, byte[] pdu, Message response) {
        // FIXME: VIA and mark it first
        /*
        // VIA add begin
        // platform use VIA cdma modem and is cdma now
        if (FeatureOption.EVDO_DT_VIA_SUPPORT == true) {
            // to make sure the pdu from APP can be processed by
            // writeSmsToRuim
            response = mHandler.obtainMessage(EVENT_WRITE_CDMA_SMS_TO_RUIM_DONE);
            if (status == STATUS_ON_ICC_READ || status == STATUS_ON_ICC_UNREAD) {
                // it is a deliver pdu ?
                android.telephony.SmsMessage msg = android.telephony.SmsMessage.createFromPdu(
                        pdu, android.telephony.SmsMessage.FORMAT_3GPP2);
                if (msg != null) {
                    log("getDisplayOriginatingAddress: " + msg.getDisplayOriginatingAddress());
                    log("getMessageBody: " + msg.getMessageBody());
                    log("getTimestampMillis: " + msg.getTimestampMillis());
                } else {
                    log("msg == null");
                    return false;
                }
                if (msg != null) {
                    com.android.internal.telephony.cdma.SmsMessage.SubmitPdu mpdu =
                            com.android.internal.telephony.cdma.SmsMessage.createEfPdu(
                            msg.getDisplayOriginatingAddress(),
                            msg.getMessageBody(), msg.getTimestampMillis());
                    if (mpdu != null) {
                        mPhone.mCi.writeSmsToRuim(status, IccUtils
                                .bytesToHexString(mpdu.encodedMessage), response);
                    } else {
                        log("mpdu == null");
                        return false;
                    }
                }
            } else if (status == STATUS_ON_ICC_SENT || status == STATUS_ON_ICC_UNSENT) {
                // it is a submit pdu, can loop directly
                mPhone.mCi.writeSmsToRuim(status, IccUtils.bytesToHexString(pdu), response);
            } else if (status == STATUS_ON_ICC_FREE) {
                log("error sms status for write sms to uim");
                return false;
            }
            // VIA add end
        } else {
            mPhone.mCi.writeSmsToRuim(status, IccUtils.bytesToHexString(pdu), response);
        }
        */
        return true;
    }

    /**
     * This function write one text message to uim card,
     * may be long text message.
     *
     * This function do uim sms cache work in it,
     * so should avoid repeating in the caller method.
     */
    protected SimSmsInsertStatus writeTextMessageToCdmaUIMCardAction(
                  String address, List<String> text, int status, long timestamp) {
        SimSmsInsertStatus insertRet = new SimSmsInsertStatus(RESULT_ERROR_SUCCESS, "");
        // FIXME: VIA marks it first
        /*
        mSuccess = true;

        for(int i = 0; i < text.size(); ++i) {
            if(mSuccess == false) {
                log("viacode [copyText Exception happened when copy message");
                insertRet.insertStatus = RESULT_ERROR_GENERIC_FAILURE;
                return insertRet;
            }

            com.android.internal.telephony.cdma.SmsMessage.SubmitPdu pdu
                = com.android.internal.telephony.cdma.SmsMessage.createEfPdu(address, text.get(i), timestamp);

            if(pdu != null) {
                Message response = mHandler.obtainMessage(EVENT_WRITE_CDMA_SMS_TO_RUIM_DONE);
                mPhone.mCi.writeSmsToRuim(status, IccUtils.bytesToHexString(pdu.encodedMessage), response);
            } else {
                insertRet.insertStatus = RESULT_ERROR_GENERIC_FAILURE;
                return insertRet;
            }

            synchronized (mLock) {
                try {
                    mLock.wait();
                } catch(InterruptedException e) {
                    log("viacode InterruptedException " + e);
                    insertRet.insertStatus = RESULT_ERROR_GENERIC_FAILURE;
                    return insertRet;
                }
            }
        }

        insertRet.insertStatus = RESULT_ERROR_SUCCESS;
        */
        return insertRet;
    }
    // VIA add - end
}
