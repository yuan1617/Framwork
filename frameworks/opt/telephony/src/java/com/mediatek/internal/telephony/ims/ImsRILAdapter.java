/* Copyright Statement:
 *
 * This software/firmware and related documentation ("MediaTek Software") are
 * protected under relevant copyright laws. The information contained herein
 * is confidential and proprietary to MediaTek Inc. and/or its licensors.
 * Without the prior written permission of MediaTek inc. and/or its licensors,
 * any reproduction, modification, use or disclosure of MediaTek Software,
 * and information contained herein, in whole or in part, shall be strictly prohibited.
 */
/* MediaTek Inc. (C) 2014. All rights reserved.
 *
 * BY OPENING THIS FILE, RECEIVER HEREBY UNEQUIVOCALLY ACKNOWLEDGES AND AGREES
 * THAT THE SOFTWARE/FIRMWARE AND ITS DOCUMENTATIONS ("MEDIATEK SOFTWARE")
 * RECEIVED FROM MEDIATEK AND/OR ITS REPRESENTATIVES ARE PROVIDED TO RECEIVER ON
 * AN "AS-IS" BASIS ONLY. MEDIATEK EXPRESSLY DISCLAIMS ANY AND ALL WARRANTIES,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE OR NONINFRINGEMENT.
 * NEITHER DOES MEDIATEK PROVIDE ANY WARRANTY WHATSOEVER WITH RESPECT TO THE
 * SOFTWARE OF ANY THIRD PARTY WHICH MAY BE USED BY, INCORPORATED IN, OR
 * SUPPLIED WITH THE MEDIATEK SOFTWARE, AND RECEIVER AGREES TO LOOK ONLY TO SUCH
 * THIRD PARTY FOR ANY WARRANTY CLAIM RELATING THERETO. RECEIVER EXPRESSLY ACKNOWLEDGES
 * THAT IT IS RECEIVER'S SOLE RESPONSIBILITY TO OBTAIN FROM ANY THIRD PARTY ALL PROPER LICENSES
 * CONTAINED IN MEDIATEK SOFTWARE. MEDIATEK SHALL ALSO NOT BE RESPONSIBLE FOR ANY MEDIATEK
 * SOFTWARE RELEASES MADE TO RECEIVER'S SPECIFICATION OR TO CONFORM TO A PARTICULAR
 * STANDARD OR OPEN FORUM. RECEIVER'S SOLE AND EXCLUSIVE REMEDY AND MEDIATEK'S ENTIRE AND
 * CUMULATIVE LIABILITY WITH RESPECT TO THE MEDIATEK SOFTWARE RELEASED HEREUNDER WILL BE,
 * AT MEDIATEK'S OPTION, TO REVISE OR REPLACE THE MEDIATEK SOFTWARE AT ISSUE,
 * OR REFUND ANY SOFTWARE LICENSE FEES OR SERVICE CHARGE PAID BY RECEIVER TO
 * MEDIATEK FOR SUCH MEDIATEK SOFTWARE AT ISSUE.
 *
 * The following software/firmware and/or related documentation ("MediaTek Software")
 * have been modified by MediaTek Inc. All revisions are subject to any receiver's
 * applicable license agreements with MediaTek Inc.
 */


package com.mediatek.internal.telephony.ims;

import static com.android.internal.telephony.RILConstants.*;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.AsyncResult;
import android.os.Parcel;
import android.os.Message;
import android.telephony.Rlog;

import com.android.internal.telephony.imsphone.ImsPhone;
import com.android.internal.telephony.CommandsInterface;

import java.io.IOException;
import java.io.InputStream;

/**
 * IMS RIL Adapter implementation.
 *
 * {@hide}
 */
public class ImsRILAdapter extends ImsBaseCommands implements ImsCommandsInterface {
    static final String IMS_RILA_LOG_TAG = "IMS: IMS_RILA";

    //***** Instance Variables
    Context mContext;
    LocalSocket mSocket;
    Thread mReceiverThread;
    ImsRILReceiver mReceiver;
    ImsPhone mImsPhone;
    CommandsInterface mDefaultCi;

    // match with constant in ril_ims.c
    static final int RIL_MAX_COMMAND_BYTES = (8 * 1024);
    static final int RESPONSE_SOLICITED = 0;
    static final int RESPONSE_UNSOLICITED = 1;
    static final int SOCKET_OPEN_RETRY_MILLIS = 4 * 1000;

    static final boolean IMS_RILA_LOGD = true;

    public void setMute(boolean muted) {
        if (mDefaultCi != null) {
            mDefaultCi.setMute(muted, null);
        } else {
            Rlog.e(IMS_RILA_LOG_TAG, "IMS CommandsInterface of RILJ is null !!");
        }
    }

    public void start(String callee, int clirMode, Message result) {
        if (mDefaultCi != null) {
            mDefaultCi.dial(callee, clirMode, result);
        } else {
            Rlog.e(IMS_RILA_LOG_TAG, "IMS CommandsInterface of RILJ is null !!");
        }
    }

    public void accept() {
        if (mDefaultCi != null) {
            mDefaultCi.acceptCall(null);
        } else {
            Rlog.e(IMS_RILA_LOG_TAG, "IMS CommandsInterface of RILJ is null !!");
        }
    }

    public void reject(int callId) {
        if (mDefaultCi != null) {
            mDefaultCi.hangupConnection(callId, null);
        } else {
            Rlog.e(IMS_RILA_LOG_TAG, "IMS CommandsInterface of RILJ is null !!");
        }
    }

    public void terminate(int callId) {
        if (mDefaultCi != null) {
            mDefaultCi.hangupConnection(callId, null);
        } else {
            Rlog.e(IMS_RILA_LOG_TAG, "IMS CommandsInterface of RILJ is null !!");
        }

    }

    public void hold(Message result) {
        if (mDefaultCi != null) {
            mDefaultCi.switchWaitingOrHoldingAndActive(result);
        } else {
            Rlog.e(IMS_RILA_LOG_TAG, "IMS CommandsInterface of RILJ is null !!");
        }
    }

    public void resume(Message result) {
        if (mDefaultCi != null) {
            mDefaultCi.switchWaitingOrHoldingAndActive(result);
        } else {
            Rlog.e(IMS_RILA_LOG_TAG, "IMS CommandsInterface of RILJ is null !!");
        }
    }

    public void merge(Message result) {
        if (mDefaultCi != null) {
            mDefaultCi.conference(result);
        } else {
            Rlog.e(IMS_RILA_LOG_TAG, "IMS CommandsInterface of RILJ is null !!");
        }
    }

    public void sendDtmf(char c, Message result) {
        if (mDefaultCi != null) {
            mDefaultCi.sendDtmf(c, result);
        } else {
            Rlog.e(IMS_RILA_LOG_TAG, "IMS CommandsInterface of RILJ is null !!");
        }
    }

    public void setCallIndication(int mode, int callId, int seqNum) {
        if (mDefaultCi != null) {
            mDefaultCi.setCallIndication(mode, callId, seqNum, null);
        } else {
            Rlog.e(IMS_RILA_LOG_TAG, "IMS CommandsInterface of RILJ is null !!");
        }
    }

    public void turnOnIms(Message response) {
        if (mDefaultCi != null) {
            mDefaultCi.setIMSEnabled(true, response);
        } else {
            Rlog.e(IMS_RILA_LOG_TAG, "IMS CommandsInterface of RILJ is null !!");
        }
    }

    public void turnOffIms(Message response) {
        if (mDefaultCi != null) {
            mDefaultCi.setIMSEnabled(false, response);
        } else {
            Rlog.e(IMS_RILA_LOG_TAG, "IMS CommandsInterface of RILJ is null !!");
        }
    }

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

            if (countRead < 0) {
                Rlog.e(IMS_RILA_LOG_TAG, "Hit EOS reading message length");
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

            if (countRead < 0) {
                Rlog.e(IMS_RILA_LOG_TAG, "Hit EOS reading message.  messageLength=" + messageLength
                        + " remaining=" + remaining);
                return -1;
            }

            offset += countRead;
            remaining -= countRead;
        } while (remaining > 0);

        return messageLength;
    }

    class ImsRILReceiver implements Runnable {
        byte[] buffer;

        ImsRILReceiver() {
            buffer = new byte[RIL_MAX_COMMAND_BYTES];
        }

        @Override
        public void
        run() {
            int retryCount = 0;
            String imsRilSocket = "rild-ims";

            try { for (;; ) {
                LocalSocket s = null;
                LocalSocketAddress l;

                try {
                    s = new LocalSocket();
                    l = new LocalSocketAddress(imsRilSocket,
                            LocalSocketAddress.Namespace.RESERVED);
                    s.connect(l);
                } catch (IOException ex) {
                    try {
                        if (s != null) {
                            s.close();
                        }
                    } catch (IOException ex2) {
                        //ignore failure to close after failure to connect
                        Rlog.e(IMS_RILA_LOG_TAG, "Failed to close the socket");
                    }

                    // don't print an error message after the the first time
                    // or after the 8th time

                    if (retryCount == 8) {
                        Rlog.e(IMS_RILA_LOG_TAG,
                            "Couldn't find '" + imsRilSocket
                            + "' socket after " + retryCount
                            + " times, continuing to retry silently");
                    } else if (retryCount > 0 && retryCount < 8) {
                        Rlog.i(IMS_RILA_LOG_TAG,
                            "Couldn't find '" + imsRilSocket
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
                Rlog.i(IMS_RILA_LOG_TAG, "Connected to '" + imsRilSocket + "' socket");

                int length = 0;
                try {
                    InputStream is = mSocket.getInputStream();

                    for (;; ) {
                        Parcel p;

                        length = readRilMessage(is, buffer);

                        if (length < 0) {
                            // End-of-stream reached
                            break;
                        }

                        p = Parcel.obtain();
                        p.unmarshall(buffer, 0, length);
                        p.setDataPosition(0);

                        //Rlog.v(IMS_RILA_LOG_TAG, "Read packet: " + length + " bytes");

                        processResponse(p);
                        p.recycle();
                    }
                } catch (java.io.IOException ex) {
                    Rlog.i(IMS_RILA_LOG_TAG, "'" + imsRilSocket + "' socket closed",
                          ex);
                } catch (Throwable tr) {
                    Rlog.e(IMS_RILA_LOG_TAG, "Uncaught exception read length=" + length +
                        "Exception:" + tr.toString());
                }

                Rlog.i(IMS_RILA_LOG_TAG, "Disconnected from '" + imsRilSocket
                      + "' socket");

                //setRadioState (RadioState.RADIO_UNAVAILABLE);

                try {
                    mSocket.close();
                } catch (IOException ex) {
                }

                mSocket = null;
                //RILRequest.resetSerial();

                // Clear request list on close
                //clearRequestList(RADIO_NOT_AVAILABLE, false);
            } } catch (Throwable tr) {
                Rlog.e(IMS_RILA_LOG_TAG, "Uncaught exception", tr);
            }

            /* We're disconnected so we don't know the ril version */
            //notifyRegistrantsRilConnectionChanged(-1);
        }
    }

    //***** Constructors
    public ImsRILAdapter(Context context, ImsPhone imsPhone) {
        super(context);
        mContext = context;
        mImsPhone = imsPhone;
        Rlog.i(IMS_RILA_LOG_TAG, "IMS:ImsRILAdapter constructor");

        if (imsPhone != null) {
            mDefaultCi = imsPhone.getCommandsInterface();
        }
        mReceiver = new ImsRILReceiver();
        mReceiverThread = new Thread(mReceiver, "ImsRILReceiver");
        mReceiverThread.start();

    }

    private void processResponse(Parcel p) {
        int type;
        Rlog.i(IMS_RILA_LOG_TAG, " IMS processResponse()");

        type = p.readInt();
        if (type == RESPONSE_UNSOLICITED) {
            processUnsolicited(p);
        } else if (type == RESPONSE_SOLICITED) {
            //RILRequest rr = processSolicited (p);
            //if (rr != null) {
            //    rr.release();
                //decrementWakeLock();
            //}
        }
    }

    private Object responseVoid(Parcel p) {
        return null;
    }

    private Object responseString(Parcel p) {
        String response;

        response = p.readString();

        return response;
    }

    private Object responseInts(Parcel p) {
        int numInts;
        int response[];

        numInts = p.readInt();

        response = new int[numInts];

        for (int i = 0 ; i < numInts ; i++) {
            response[i] = p.readInt();
        }

        return response;
    }

    static String responseToString(int request)
    {
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
            case RIL_UNSOL_CIPHER_INDICATION: return "RIL_UNSOL_CIPHER_INDICATION";
            case RIL_UNSOL_CNAP: return "RIL_UNSOL_CNAP";
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
            default: return "<unknown response>";
        }
    }

    static String retToString(int req, Object ret) {
        if (ret == null) return "";

        StringBuilder sb;
        String s;
        int length;
        if (ret instanceof int[]) {
            int[] intArray = (int[]) ret;
            length = intArray.length;
            sb = new StringBuilder("{");
            if (length > 0) {
                int i = 0;
                sb.append(intArray[i++]);
                while (i < length) {
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
                while (i < length) {
                    sb.append(", ").append(strings[i++]);
                }
            }
            sb.append("}");
            s = sb.toString();
        } else {
            s = ret.toString();
        }
        return s;
    }

    private void riljLog(String msg) {
        Rlog.d(IMS_RILA_LOG_TAG, msg
                + "");
    }

    private void riljLogv(String msg) {
        Rlog.v(IMS_RILA_LOG_TAG, msg
                + "");
    }

    private void unsljLog(int response) {
        riljLog("[UNSL]< " + responseToString(response));
    }

    private void unsljLogRet(int response, Object ret) {
        riljLog("[UNSL]< " + responseToString(response) + " " + retToString(response, ret));
    }

    private void unsljLogvRet(int response, Object ret) {
        riljLogv("[UNSL]< " + responseToString(response) + " " + retToString(response, ret));
    }

    private void processUnsolicited(Parcel p) {
        Rlog.i(IMS_RILA_LOG_TAG, " IMS processUnsolicited !!");
                int response;
                Object ret;
                response = p.readInt();
                try { switch(response) {
                    case RIL_UNSOL_RESPONSE_CALL_STATE_CHANGED: ret =  responseVoid(p); break;
                    default:
                        throw new RuntimeException("Unrecognized unsol response: " + response);
                    //break; (implied)
                } } catch (Throwable tr) {
                    Rlog.e(IMS_RILA_LOG_TAG, "Exception processing unsol response: " + response +
                        "Exception:" + tr.toString());
                    return;
                }

                switch(response) {
                    case RIL_UNSOL_RESPONSE_CALL_STATE_CHANGED:
                        if (IMS_RILA_LOGD) unsljLog(response);
                        Rlog.i(IMS_RILA_LOG_TAG, " IMS XXX get URC for RIL_UNSOL_RESPONSE_CALL_STATE_CHANGED !!");
                        mCallStateRegistrants
                            .notifyRegistrants(new AsyncResult(null, null, null));
                        break;
                    case RIL_UNSOL_IMS_REGISTRATION_INFO:
                        if (IMS_RILA_LOGD) unsljLog(response);
                        if (mImsRegistrationInfoRegistrants != null) {
                            mImsRegistrationInfoRegistrants.notifyRegistrants(new AsyncResult(null, ret, null));
                        }
                        break;
                    case RIL_UNSOL_INCOMING_CALL_INDICATION:
                        if (IMS_RILA_LOGD) unsljLogvRet(response, ret);
                        if (mIncomingCallIndicationRegistrant != null) {
                            mIncomingCallIndicationRegistrant.notifyRegistrant(new AsyncResult(null, ret, null));
                        }
                        break;
                    case RIL_UNSOL_RINGBACK_TONE:
                        if (IMS_RILA_LOGD) unsljLogvRet(response, ret);
                        if (mRingbackToneRegistrants != null) {
                            boolean playtone = (((int[]) ret)[0] == 1);
                            mRingbackToneRegistrants.notifyRegistrants(
                                                new AsyncResult(null, playtone, null));
                        }
                        break;
                    case RIL_UNSOL_CIPHER_INDICATION:
                        if (IMS_RILA_LOGD) unsljLogvRet(response, ret);

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
                    case RIL_UNSOL_VOLTE_EPS_NETWORK_FEATURE_SUPPORT:
                        if (IMS_RILA_LOGD) unsljLogvRet(response, ret);
                        if (mEpsNetworkFeatureSupportRegistrants != null) {
                            mEpsNetworkFeatureSupportRegistrants.notifyRegistrants(
                                                new AsyncResult(null, ret, null));
                        }
                        break;
                    /// M: IMS feature. @{
                    //For updating call ids for conference call after SRVCC is done.
                    case RIL_UNSOL_ECONF_SRVCC_INDICATION:
                        if (IMS_RILA_LOGD) unsljLog(response);
                        if (mEconfSrvccRegistrants != null) {
                            mEconfSrvccRegistrants.notifyRegistrants(new AsyncResult(null, ret, null));
                        }
                        break;

                    //For updating conference call merged/added result.
                    case RIL_UNSOL_ECONF_RESULT_INDICATION:
                        if (IMS_RILA_LOGD) unsljLog(response);
                        if (mEconfResultRegistrants != null) {
                             riljLog("Notify ECONF result");
                             String[] econfResult = (String[]) ret;
                             riljLog("ECONF result = " + econfResult[3]);
                             mEconfResultRegistrants.notifyRegistrants(new AsyncResult(null, ret, null));
                        }
                        break;

                    //For updating call mode and pau information.
                    case RIL_UNSOL_CALL_INFO_INDICATION :
                        if (IMS_RILA_LOGD) unsljLog(response);
                        if (mCallInfoRegistrants != null) {
                           mCallInfoRegistrants.notifyRegistrants(new AsyncResult(null, ret, null));
                        }
                        break;

                    case RIL_UNSOL_VOLTE_EPS_NETWORK_FEATURE_INFO:
                        if (IMS_RILA_LOGD) unsljLog(response);
                        if (mEpsNetworkFeatureInfoRegistrants != null) {
                           mEpsNetworkFeatureInfoRegistrants.notifyRegistrants(new AsyncResult(null, ret, null));
                        }
                        break;

                    case RIL_UNSOL_SRVCC_HANDOVER_INFO_INDICATION:
                        if (IMS_RILA_LOGD) unsljLog(response);
                        if (mSrvccHandoverInfoIndicationRegistrants != null) {
                            mSrvccHandoverInfoIndicationRegistrants.notifyRegistrants(new AsyncResult(null, ret, null));
                        }
                        break;

                    // IMS
                    //VoLTE
                    case RIL_UNSOL_DEDICATE_BEARER_ACTIVATED:
                        if (IMS_RILA_LOGD) unsljLogRet(response, ret);
                        if (mDedicateBearerActivatedRegistrant != null) {
                            mDedicateBearerActivatedRegistrant.notifyRegistrants(new AsyncResult(null, ret, null));
                        }
                        break;
                    case RIL_UNSOL_DEDICATE_BEARER_MODIFIED:
                        if (IMS_RILA_LOGD) unsljLogRet(response, ret);
                        if (mDedicateBearerModifiedRegistrant != null) {
                            mDedicateBearerModifiedRegistrant.notifyRegistrants(new AsyncResult(null, ret, null));
                        }
                        break;
                    case RIL_UNSOL_DEDICATE_BEARER_DEACTIVATED:
                        if (IMS_RILA_LOGD) unsljLogRet(response, ret);
                        if (mDedicateBearerDeactivatedRegistrant != null) {
                            mDedicateBearerDeactivatedRegistrant.notifyRegistrants(new AsyncResult(null, ret, null));
                        }
                        break;
                    /// @}
                    case RIL_UNSOL_SPEECH_CODEC_INFO:
                        if (IMS_RILA_LOGD) unsljLogvRet(response, ret);

                        if (mSpeechCodecInfoRegistrant != null) {
                            mSpeechCodecInfoRegistrant.notifyRegistrant(
                                new AsyncResult(null, ret, null));
                        }
                    break;
                    default:
                        break;
                }

    }
}
