/* Copyright Statement:
 *
 * This software/firmware and related documentation ("MediaTek Software") are
 * protected under relevant copyright laws. The information contained herein
 * is confidential and proprietary to MediaTek Inc. and/or its licensors.
 * Without the prior written permission of MediaTek inc. and/or its licensors,
 * any reproduction, modification, use or disclosure of MediaTek Software,
 * and information contained herein, in whole or in part, shall be strictly prohibited.
 */
/* MediaTek Inc. (C) 2010. All rights reserved.
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


import android.telephony.Rlog;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;

import com.android.ims.ImsCallProfile;
import com.android.ims.ImsStreamMediaProfile;
import com.android.ims.internal.IImsCallSessionListener;
import com.android.ims.internal.IImsCallSession;
import com.android.ims.internal.IImsVideoCallProvider;
import com.android.ims.ImsReasonInfo;


public class ImsCallSessionProxy extends IImsCallSession.Stub {
    private static final String LOG_TAG = "ImsCallSessionProxy";
    private static final boolean DBG = true;
    private static final boolean VDBG = false; // STOPSHIP if true

    private String mCallId;
    private int mState;
    private ImsRILAdapter mImsRILAdapter;
    private ImsCallProfile mCallProfile;    
    private IImsCallSessionListener mListener;
	private IImsCallSession mCallSession;

    private final Handler mHandler;

    //***** Events URC
    private static final int EVENT_POLL_CALLS_RESULT             = 101;
    private static final int EVENT_CALL_STATE_CHANGE             = 102;
    private static final int EVENT_RINGBACK_TONE                 = 103;

    //***** Events Operation result
    private static final int EVENT_DIAL_RESULT                   = 201;
    private static final int EVENT_ACCEPT_RESULT                 = 202;
    private static final int EVENT_HOLD_RESULT                   = 203;
    private static final int EVENT_RESUME_RESULT                 = 204;
    private static final int EVENT_MERGE_RESULT                  = 205;
    

    //Constructor for MT call
    ImsCallSessionProxy(ImsCallProfile profile, IImsCallSessionListener listener, ImsRILAdapter imsRILAdapter, String callId) {
        mHandler = new MyHandler();    
        mCallProfile = profile;
        mListener = listener;
		mCallSession = this;
        mImsRILAdapter = imsRILAdapter;
        mCallId = callId;
        mImsRILAdapter.registerForCallStateChanged(mHandler,EVENT_CALL_STATE_CHANGE, null);
        mImsRILAdapter.registerForRingbackTone(mHandler,EVENT_RINGBACK_TONE,null);

    }

    //Constructor for MO call
    ImsCallSessionProxy(ImsCallProfile profile, IImsCallSessionListener listener, ImsRILAdapter imsRILAdapter) {
        this(profile,listener,imsRILAdapter,null);
    }
    
    @Override
    public void close() {
        mImsRILAdapter.unregisterForCallStateChanged(mHandler);
        mImsRILAdapter.unregisterForRingbackTone(mHandler);
    }

    @Override
    public String getCallId() {
        return mCallId;
    }

    @Override
    public ImsCallProfile getCallProfile() {
        return mCallProfile;
    }

    @Override
    public ImsCallProfile getLocalCallProfile() {
        return mCallProfile;
    }

    @Override
    public String getProperty(String name) {
        return mCallProfile.getCallExtra(name);
    }

    @Override
    public int getState() {
        return mState;
    }

    @Override
    public boolean isInCall() {
        return false;
    }

    @Override
    public void setListener(IImsCallSessionListener listener) {
        mListener = listener;
    }

    @Override
    public void setMute(boolean muted) {
        mImsRILAdapter.setMute(muted);
    }

    @Override
    public void start(String callee, ImsCallProfile profile) {
        int clirMode = profile.getCallExtraInt(ImsCallProfile.EXTRA_OIR, 0);
        // Todo : 
        Message result = mHandler.obtainMessage(EVENT_DIAL_RESULT);
        mImsRILAdapter.start(callee, clirMode, result);
    }

    @Override
    public void startConference(String[] participants, ImsCallProfile profile) {
        // currently MD not support to join multiple participants to join conference call.
    }

    @Override
    public void accept(int callType, ImsStreamMediaProfile profile) {
        // Always unmute when answering a new call
        mImsRILAdapter.setMute(false);        
        mImsRILAdapter.accept();
    }

    @Override
    public void reject(int reason) {
        mImsRILAdapter.reject(Integer.parseInt(mCallId));
    }
    
    @Override
    public void terminate(int reason) {
        mImsRILAdapter.terminate(Integer.parseInt(mCallId));
    }

    @Override
    public void hold(ImsStreamMediaProfile profile) {
        // Todo : 
        Message result = mHandler.obtainMessage(EVENT_HOLD_RESULT);
        mImsRILAdapter.hold(result);
    }

    @Override
    public void resume(ImsStreamMediaProfile profile) {
        // Todo : 
        Message result = mHandler.obtainMessage(EVENT_RESUME_RESULT);
        mImsRILAdapter.resume(result);
    }

    @Override
    public void merge() {    
        // Todo : 
        Message result = mHandler.obtainMessage(EVENT_MERGE_RESULT);
        mImsRILAdapter.merge(result);
    }

    @Override
    public void update(int callType, ImsStreamMediaProfile profile) {
        // currently MD not support for video downgrade or audio upgrade.
    }

    @Override
    public void extendToConference(String[] participants) {
        // currently MD not support to join multiple participants to join conference call.
    }

    @Override
    public void inviteParticipants(String[] participants) {
        // currently MD not support to join multiple participants to join conference call.
    }

    @Override
    public void removeParticipants(String[] participants) {
        // currently MD not support to remove multiple participants to join conference call.
    }

    @Override
    public void sendDtmf(char c, Message result) {
        mImsRILAdapter.sendDtmf(c, result);
    }

    @Override
    public void sendUssd(String ussdMessage) {
    }

    @Override
    public IImsVideoCallProvider getVideoCallProvider() {
        //vp = new ImsVTProvider(mCallId);
        //return vp.getInterface();
        return null;
    }

    private class MyHandler extends Handler {

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case EVENT_CALL_STATE_CHANGE:
                    /* +ECPI:<call_id>, <msg_type>, <is_ibt>, <is_tch>, <dir>, <call_mode>[, <number>, <toa>], "",<cause>
                     *
                     * if msg_type = DISCONNECT_MSG or ALL_CALLS_DISC_MSG,
                     * +ECPI:<call_id>, <msg_type>, <is_ibt>, <is_tch>,,,"",,"",<cause>
                     *
                     * if others,
                     * +ECPI:<call_id>, <msg_type>, <is_ibt>, <is_tch>, <dir>, <call_mode>[, <number>, <toa>], ""
                     *
                     *      0  O  CSMCC_SETUP_MSG
                     *      1  X  CSMCC_DISCONNECT_MSG
                     *      2  O  CSMCC_ALERT_MSG
                     *      3  X  CSMCC_CALL_PROCESS_MSG
                     *      4  X  CSMCC_SYNC_MSG
                     *      5  X  CSMCC_PROGRESS_MSG
                     *      6  O  CSMCC_CALL_CONNECTED_MSG
                     *   129  X  CSMCC_ALL_CALLS_DISC_MSG
                     *   130  O  CSMCC_MO_CALL_ID_ASSIGN_MSG
                     *   131  O  CSMCC_STATE_CHANGE_HELD
                     *   132  O  CSMCC_STATE_CHANGE_ACTIVE
                     *   133  O  CSMCC_STATE_CHANGE_DISCONNECTED
                     *   134  X  CSMCC_STATE_CHANGE_MO_DISCONNECTING
                     */
                    
                    AsyncResult ar = (AsyncResult) msg.obj;
                    String[] CallInfo = (String[]) ar.result;
                    int callMode = 0;
                    
                    if (DBG) Rlog.d(LOG_TAG, "receive EVENT_CALL_STATE_CHANGE");
                    
                    if ((CallInfo[5] != null) && (!CallInfo[5].equals(""))) {
	                    callMode = Integer.parseInt(CallInfo[5]);
	                }
                    
                    if( mCallId != null && mCallId.equals(CallInfo[0])) {
                        switch (callMode) {
                            case 2: // CSMCC_ALERT_MSG
                                if(mListener != null) {
									try {
                                        mListener.callSessionProgressing(mCallSession, mCallProfile.mMediaProfile);
                                    } catch (RemoteException e) {
                                        Rlog.e(LOG_TAG, "RemoteException occurs when callSessionProgressing()");
                                    }
                                }
                                break;
                            case 131: //CSMCC_STATE_CHANGE_HELD
                                if(mListener != null) {
                                    try {
                                        mListener.callSessionHeld(mCallSession, mCallProfile);
                                    } catch (RemoteException e) {
                                        Rlog.e(LOG_TAG, "RemoteException occurs when callSessionHeld()");
                                    }
                                }
                                break;
                            case 132: //CSMCC_STATE_CHANGE_ACTIVE
                                if(mListener != null) {
                                    try {
                                        mListener.callSessionStarted(mCallSession, mCallProfile);
                                    } catch (RemoteException e) {
                                        Rlog.e(LOG_TAG, "RemoteException occurs when callSessionStarted()");
                                    }
                                }
                                break;
                            case 133: //CSMCC_STATE_CHANGE_DISCONNECTED
                                if(mListener != null) {
                                    try {
                                        mListener.callSessionTerminated(mCallSession, new ImsReasonInfo(Integer.parseInt(CallInfo[9]),ImsReasonInfo.CODE_UNSPECIFIED));
                                    } catch (RemoteException e) {
                                        Rlog.e(LOG_TAG, "RemoteException occurs when callSessionTerminated()");
                                    }
                                }
                                break;
                        }
                    } else if(mCallId == null && callMode == 130) {
                        mCallId = CallInfo[0];
                    }
                    break;
                case EVENT_RINGBACK_TONE:
                    ar = (AsyncResult) msg.obj;
                    if (DBG) Rlog.d(LOG_TAG, "receive EVENT_SET_IMS_DISABLE_DONE");
                    break;
                case EVENT_DIAL_RESULT:
                    ar = (AsyncResult) msg.obj;
                    if (DBG) Rlog.d(LOG_TAG, "receive EVENT_DIAL_RESULT");
                    if (ar.exception != null) {
                        if (DBG) Rlog.d(LOG_TAG,"dial call failed!!");
                        if(mListener != null) {
                            try {
                                mListener.callSessionStartFailed(mCallSession, new ImsReasonInfo());
                            } catch (RemoteException e) {
                                Rlog.e(LOG_TAG, "RemoteException occurs when callSessionStartFailed()");
                            }
                        }
                    }
                    break;
                case EVENT_HOLD_RESULT:
                    ar = (AsyncResult) msg.obj;
                    if (DBG) Rlog.d(LOG_TAG, "receive EVENT_HOLD_RESULT");
                    if (ar.exception != null) {
                        if (DBG) Rlog.d(LOG_TAG,"hold call failed!!");
                        if(mListener != null) {
                            try {
                                mListener.callSessionHoldFailed(mCallSession, new ImsReasonInfo());
                            } catch (RemoteException e) {
                                Rlog.e(LOG_TAG, "RemoteException occurs when callSessionHoldFailed()");
                            }
                        }
                    } else {
                        if(mListener != null) {
                            try {
                                mListener.callSessionHoldReceived(mCallSession, mCallProfile);
                            } catch (RemoteException e) {
                                Rlog.e(LOG_TAG, "RemoteException occurs when callSessionHoldReceived()");
                            }
                        }                    
                    }
                    break;
                case EVENT_RESUME_RESULT:
                    ar = (AsyncResult) msg.obj;
                    if (DBG) Rlog.d(LOG_TAG, "receive EVENT_RESUME_RESULT");
                    if (ar.exception != null) {
                        if (DBG) Rlog.d(LOG_TAG,"resume call failed!!");
                        if(mListener != null) {
                            try {
                                mListener.callSessionResumeFailed(mCallSession, new ImsReasonInfo());
                            } catch (RemoteException e) {
                                Rlog.e(LOG_TAG, "RemoteException occurs when callSessionResumeFailed()");
                            }
                        }
                    } else {
                        if(mListener != null) {
                            try {
                                mListener.callSessionResumeReceived(mCallSession, mCallProfile);
                            } catch (RemoteException e) {
                                Rlog.e(LOG_TAG, "RemoteException occurs when callSessionResumeReceived()");
                            }
                        }                    
                    }
                    break;
                case EVENT_MERGE_RESULT:
                    ar = (AsyncResult) msg.obj;
                    if (DBG) Rlog.d(LOG_TAG, "receive EVENT_MERGE_RESULT");
                    if (ar.exception != null) {
                        if (DBG) Rlog.d(LOG_TAG,"merge call failed!!");
                        if(mListener != null) {
                            try {
                                mListener.callSessionMergeFailed(mCallSession, new ImsReasonInfo());
                            } catch (RemoteException e) {
                                Rlog.e(LOG_TAG, "RemoteException occurs when callSessionMergeFailed()");
                            }
                        }
                    }
                    break;
                default:
                    break;
            }
        }
    }
}
