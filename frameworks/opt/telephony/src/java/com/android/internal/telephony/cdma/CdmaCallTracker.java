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

package com.android.internal.telephony.cdma;

import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.os.RegistrantList;
import android.telephony.DisconnectCause;
import android.telephony.PhoneNumberUtils;
import android.telephony.ServiceState;
import android.telephony.Rlog;
import android.os.SystemProperties;
import android.text.TextUtils;

import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.CallTracker;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.DriverCall;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyProperties;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.telephony.SubscriptionManager;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.Vibrator;
import android.preference.PreferenceManager;
import com.mediatek.common.mom.IMobileManager;
import com.mediatek.common.mom.IMobileManagerService;
import com.mediatek.common.mom.MobileManagerUtils;

/**
 * {@hide}
 */
public final class CdmaCallTracker extends CallTracker {
    static final String LOG_TAG = "CdmaCallTracker";

    private static final boolean REPEAT_POLLING = false;

    private static final boolean DBG_POLL = false;

    //***** Constants

    static final int MAX_CONNECTIONS = 8;
    static final int MAX_CONNECTIONS_PER_CALL = 1; // only 1 connection allowed per call

    //***** Instance Variables

    CdmaConnection mConnections[] = new CdmaConnection[MAX_CONNECTIONS];
    RegistrantList mVoiceCallEndedRegistrants = new RegistrantList();
    RegistrantList mVoiceCallStartedRegistrants = new RegistrantList();
    RegistrantList mCallWaitingRegistrants =  new RegistrantList();


    // connections dropped during last poll
    ArrayList<CdmaConnection> mDroppedDuringPoll
        = new ArrayList<CdmaConnection>(MAX_CONNECTIONS);

    CdmaCall mRingingCall = new CdmaCall(this);
    // A call that is ringing or (call) waiting
    CdmaCall mForegroundCall = new CdmaCall(this);
    CdmaCall mBackgroundCall = new CdmaCall(this);

    CdmaConnection mPendingMO;
    boolean mHangupPendingMO;
    boolean mPendingCallInEcm=false;
    boolean mIsInEmergencyCall = false;
    CDMAPhone mPhone;

    boolean mDesiredMute = false;    // false = mute off

    int mPendingCallClirMode;
    PhoneConstants.State mState = PhoneConstants.State.IDLE;

    private boolean mIsEcmTimerCanceled = true;

    private int m3WayCallFlashDelay = 0;
//    boolean needsPoll;

    /// M: @{
    private Vibrator mVibrator;
    private static String CNIR_PREFIX = "*76";
    private static final int MO_CALL_VIBRATE_TIME = 300;  // msec
    private static final int DIAL_THREEWAY_DELAY_MILLIS = 800;
    private static final int CALLWAITING_THRESHOLD_TIME = 10 * 1000;
    public static final int TOA_International = 0x91;
    private IMobileManagerService mMobileManagerService;
    /// @}

    //***** Events

    //***** Constructors
    CdmaCallTracker(CDMAPhone phone) {
        mPhone = phone;
        mCi = phone.mCi;
        mCi.registerForCallStateChanged(this, EVENT_CALL_STATE_CHANGE, null);
        mCi.registerForOn(this, EVENT_RADIO_AVAILABLE, null);
        mCi.registerForNotAvailable(this, EVENT_RADIO_NOT_AVAILABLE, null);
        mCi.registerForCallWaitingInfo(this, EVENT_CALL_WAITING_INFO_CDMA, null);
        /// M: @{
        mCi.registerForCallAccepted(this, EVENT_CDMA_CALL_ACCEPTED, null);
        /// @}
        mForegroundCall.setGeneric(false);
//MTK_TC1_FEATURE for LGE CSMCC_MO_CALL_MODIFIED {         
if (SystemProperties.get("ro.mtk_tc1_feature").equals("1")) { 
        mCi.registerForMoCallStateChanged(this, EVENT_MO_CALL_STATE_CHANGE, null);
}
//}    
    }

    public void dispose() {
        Rlog.d(LOG_TAG, "CdmaCallTracker dispose");
        mCi.unregisterForLineControlInfo(this);
        mCi.unregisterForCallStateChanged(this);
        mCi.unregisterForOn(this);
        mCi.unregisterForNotAvailable(this);
        mCi.unregisterForCallWaitingInfo(this);
        /// M: @{
        mCi.unregisterForCallAccepted(this);
        /// @}
//MTK_TC1_FEATURE for LGE CSMCC_MO_CALL_MODIFIED {         
if (SystemProperties.get("ro.mtk_tc1_feature").equals("1")) { 
        mCi.unregisterForMoCallStateChanged(this);
}
//}
        clearDisconnected();

    }

    @Override
    protected void finalize() {
        Rlog.d(LOG_TAG, "CdmaCallTracker finalized");
    }

    //***** Instance Methods

    //***** Public Methods
    @Override
    public void registerForVoiceCallStarted(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mVoiceCallStartedRegistrants.add(r);
        // Notify if in call when registering
        if (mState != PhoneConstants.State.IDLE) {
            r.notifyRegistrant(new AsyncResult(null, null, null));
        }
    }
    @Override
    public void unregisterForVoiceCallStarted(Handler h) {
        mVoiceCallStartedRegistrants.remove(h);
    }

    @Override
    public void registerForVoiceCallEnded(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mVoiceCallEndedRegistrants.add(r);
    }

    @Override
    public void unregisterForVoiceCallEnded(Handler h) {
        mVoiceCallEndedRegistrants.remove(h);
    }

    public void registerForCallWaiting(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);
        mCallWaitingRegistrants.add(r);
    }

    public void unregisterForCallWaiting(Handler h) {
        mCallWaitingRegistrants.remove(h);
    }

    /**
     * clirMode is one of the CLIR_ constants
     */
    Connection
    dial (String dialString, int clirMode) throws CallStateException {
        // note that this triggers call state changed notif
        clearDisconnected();

        if (!canDial()) {
            throw new CallStateException("cannot dial in current state");
        }

        String origNumber = dialString;
        String operatorIsoContry = mPhone.getSystemProperty(
                TelephonyProperties.PROPERTY_OPERATOR_ISO_COUNTRY, "");
        String simIsoContry = mPhone.getSystemProperty(
                TelephonyProperties.PROPERTY_ICC_OPERATOR_ISO_COUNTRY, "");
        boolean internationalRoaming = !TextUtils.isEmpty(operatorIsoContry)
                && !TextUtils.isEmpty(simIsoContry)
                && !simIsoContry.equals(operatorIsoContry);
        if (internationalRoaming) {
            if ("us".equals(simIsoContry)) {
                internationalRoaming = internationalRoaming && !"vi".equals(operatorIsoContry);
            } else if ("vi".equals(simIsoContry)) {
                internationalRoaming = internationalRoaming && !"us".equals(operatorIsoContry);
            }
        }
        if (internationalRoaming) {
            dialString = convertNumberIfNecessary(mPhone, dialString);
        }

        String inEcm=SystemProperties.get(TelephonyProperties.PROPERTY_INECM_MODE, "false");
        boolean isPhoneInEcmMode = inEcm.equals("true");

        /// M: @{
        //boolean isEmergencyCall =
        //        PhoneNumberUtils.isLocalEmergencyNumber(mPhone.getContext(), dialString);
        boolean isEmergencyCall = PhoneNumberUtils.isEmergencyNumberExt(dialString,
                                        PhoneConstants.PHONE_TYPE_CDMA);
        /// @}

        // Cancel Ecm timer if a second emergency call is originating in Ecm mode
        if (isPhoneInEcmMode && isEmergencyCall) {
            handleEcmTimer(CDMAPhone.CANCEL_ECM_TIMER);
        }

        // We are initiating a call therefore even if we previously
        // didn't know the state (i.e. Generic was true) we now know
        // and therefore can set Generic to false.
        mForegroundCall.setGeneric(false);

        /// M: @{
        // for exit ECM when dial threeway
        mPendingMO = new CdmaConnection(mPhone.getContext(),
                        checkForTestEmergencyNumber(dialString),
                        this, mForegroundCall);

        // The new call must be assigned to the foreground call.
        // That call must be idle, so place anything that's
        // there on hold
        if (mForegroundCall.getState() == CdmaCall.State.ACTIVE) {
            if (!isPhoneInEcmMode || (isPhoneInEcmMode && isEmergencyCall)) {
                mCi.sendCDMAFeatureCode("", obtainMessage(EVENT_SWITCH_RESULT));
                Rlog.d(LOG_TAG, "send EVENT_CDMA_DIAL_THREEWAY_DELAY");
                //sendMessageDelayed(obtainMessage(EVENT_CDMA_DIAL_THREEWAY_DELAY, dialString),
                //                               DIAL_THREEWAY_DELAY_MILLIS);
                return dialThreeWay(dialString);
            } else {
                Rlog.d(LOG_TAG, "exit emergency callback mode when dial threeway");
                mPhone.exitEmergencyCallbackMode();
                mPhone.setOnThreeWayEcmExitResponse(this,
                        EVENT_EXIT_ECM_RESPONSE_DIAL_THREEWAY, dialString);
                mPendingCallInEcm = true;
                return mPendingMO;
            }
        }

        //pendingMO = new CdmaConnection(phone.getContext(), checkForTestEmergencyNumber(dialString),
        //        this, foregroundCall);
        /// @}

        mHangupPendingMO = false;

        if ( mPendingMO.getAddress() == null || mPendingMO.getAddress().length() == 0
                || mPendingMO.getAddress().indexOf(PhoneNumberUtils.WILD) >= 0 ) {
            // Phone number is invalid
            mPendingMO.mCause = DisconnectCause.INVALID_NUMBER;

            // handlePollCalls() will notice this call not present
            // and will mark it as dropped.
            pollCallsWhenSafe();
        } else {
            // Always unmute when initiating a new call
            setMute(false);

            // Check data call
            disableDataCallInEmergencyCall(dialString);

            // In Ecm mode, if another emergency call is dialed, Ecm mode will not exit.
            if(!isPhoneInEcmMode || (isPhoneInEcmMode && isEmergencyCall)) {
                /// M: @{
                String dialNumber = formatDialNumber(mPendingMO.getAddress());
                mCi.dial(dialNumber, clirMode, obtainCompleteMessage());
                /// @}
            } else {
                mPhone.exitEmergencyCallbackMode();
                mPhone.setOnEcbModeExitResponse(this,EVENT_EXIT_ECM_RESPONSE_CDMA, null);
                mPendingCallClirMode=clirMode;
                mPendingCallInEcm=true;
            }
        }

        if (mNumberConverted) {
            mPendingMO.setConverted(origNumber);
            mNumberConverted = false;
        }

        updatePhoneState();
        mPhone.notifyPreciseCallStateChanged();

        return mPendingMO;
    }


    Connection
    dial (String dialString) throws CallStateException {
        return dial(dialString, CommandsInterface.CLIR_DEFAULT);
    }

    private Connection
    dialThreeWay (String dialString) {
        if (!mForegroundCall.isIdle()) {
            // Check data call
            disableDataCallInEmergencyCall(dialString);

            // Attach the new connection to foregroundCall
            /// M: @{
            /*mPendingMO = new CdmaConnection(mPhone.getContext(),
                                checkForTestEmergencyNumber(dialString), this, mForegroundCall);
            // Some network need a empty flash before sending the normal one
            m3WayCallFlashDelay = mPhone.getContext().getResources()
                    .getInteger(com.android.internal.R.integer.config_cdma_3waycall_flash_delay);
            if (m3WayCallFlashDelay > 0) {
                mCi.sendCDMAFeatureCode("", obtainMessage(EVENT_THREE_WAY_DIAL_BLANK_FLASH));
            } else {
                mCi.sendCDMAFeatureCode(mPendingMO.getAddress(),
                        obtainMessage(EVENT_THREE_WAY_DIAL_L2_RESULT_CDMA));
            }*/
            sendMessageDelayed(obtainMessage(EVENT_CDMA_DIAL_THREEWAY_DELAY,
                                mPendingMO.getAddress()),
                                DIAL_THREEWAY_DELAY_MILLIS);
            /// @}
            return mPendingMO;
        }
        return null;
    }

    void
    acceptCall() throws CallStateException {
        if (mRingingCall.getState() == CdmaCall.State.INCOMING) {
            Rlog.i("phone", "acceptCall: incoming...");
            // Always unmute when answering a new call
            setMute(false);
            mCi.acceptCall(obtainCompleteMessage());
        } else if (mRingingCall.getState() == CdmaCall.State.WAITING) {
            CdmaConnection cwConn = (CdmaConnection)(mRingingCall.getLatestConnection());

            /// M: @{
            // get real accept time
            long curTime = System.currentTimeMillis();
            cwConn.setRealAcceptTime(curTime);
            /// @}

            // Since there is no network response for supplimentary
            // service for CDMA, we assume call waiting is answered.
            // ringing Call state change to idle is in CdmaCall.detach
            // triggered by updateParent.
            cwConn.updateParent(mRingingCall, mForegroundCall);
            cwConn.onConnectedInOrOut();

            /// M: @{
            // fix CRTS#19533 19547 [ALPS00411590]
            List ringconnections = mRingingCall.getConnections();
            int count = ringconnections.size();
            log("acceptCall: callwaiting count = " + count);
            for (int i = count - 1; i >= 0; i--) {
                CdmaConnection ringConn = (CdmaConnection)(ringconnections.get(i));
                log("acceptCall: ringConn number = " + ringConn.getAddress());
                mRingingCall.detach(ringConn);
            }
            /// @}

            updatePhoneState();
            switchWaitingOrHoldingAndActive();
        } else {
            throw new CallStateException("phone not ringing");
        }
    }

    void
    rejectCall () throws CallStateException {
        // AT+CHLD=0 means "release held or UDUB"
        // so if the phone isn't ringing, this could hang up held
        if (mRingingCall.getState().isRinging()) {
            mCi.rejectCall(obtainCompleteMessage());
        } else {
            throw new CallStateException("phone not ringing");
        }
    }

    void
    switchWaitingOrHoldingAndActive() throws CallStateException {
        // Should we bother with this check?
        if (mRingingCall.getState() == CdmaCall.State.INCOMING) {
            throw new CallStateException("cannot be in the incoming state");
        } else if (mForegroundCall.getConnections().size() > 1) {
            flashAndSetGenericTrue();
        } else {
            // Send a flash command to CDMA network for putting the other party on hold.
            // For CDMA networks which do not support this the user would just hear a beep
            // from the network. For CDMA networks which do support it will put the other
            // party on hold.
            mCi.sendCDMAFeatureCode("", obtainMessage(EVENT_SWITCH_RESULT));
        }
    }

    void
    conference() {
        // Should we be checking state?
        flashAndSetGenericTrue();
    }

    void
    explicitCallTransfer() {
        mCi.explicitCallTransfer(obtainCompleteMessage(EVENT_ECT_RESULT));
    }

    void
    clearDisconnected() {
        internalClearDisconnected();

        updatePhoneState();
        mPhone.notifyPreciseCallStateChanged();
    }

    boolean
    canConference() {
        return mForegroundCall.getState() == CdmaCall.State.ACTIVE
                && mBackgroundCall.getState() == CdmaCall.State.HOLDING
                && !mBackgroundCall.isFull()
                && !mForegroundCall.isFull();
    }

    boolean
    canDial() {
        boolean ret;
        int serviceState = mPhone.getServiceState().getState();
        String disableCall = SystemProperties.get(
                TelephonyProperties.PROPERTY_DISABLE_CALL, "false");

        ret = (serviceState != ServiceState.STATE_POWER_OFF)
                && mPendingMO == null
                && !mRingingCall.isRinging()
                && !disableCall.equals("true")
                && (!mForegroundCall.getState().isAlive()
                    || (mForegroundCall.getState() == CdmaCall.State.ACTIVE)
                    || !mBackgroundCall.getState().isAlive());

        if (!ret) {
            log(String.format("canDial is false\n" +
                              "((serviceState=%d) != ServiceState.STATE_POWER_OFF)::=%s\n" +
                              "&& pendingMO == null::=%s\n" +
                              "&& !ringingCall.isRinging()::=%s\n" +
                              "&& !disableCall.equals(\"true\")::=%s\n" +
                              "&& (!foregroundCall.getState().isAlive()::=%s\n" +
                              "   || foregroundCall.getState() == CdmaCall.State.ACTIVE::=%s\n" +
                              "   ||!backgroundCall.getState().isAlive())::=%s)",
                    serviceState,
                    serviceState != ServiceState.STATE_POWER_OFF,
                    mPendingMO == null,
                    !mRingingCall.isRinging(),
                    !disableCall.equals("true"),
                    !mForegroundCall.getState().isAlive(),
                    mForegroundCall.getState() == CdmaCall.State.ACTIVE,
                    !mBackgroundCall.getState().isAlive()));
        }
        return ret;
    }

    boolean
    canTransfer() {
        Rlog.e(LOG_TAG, "canTransfer: not possible in CDMA");
        return false;
    }

    //***** Private Instance Methods

    private void
    internalClearDisconnected() {
        mRingingCall.clearDisconnected();
        mForegroundCall.clearDisconnected();
        mBackgroundCall.clearDisconnected();
    }

    /**
     * Obtain a message to use for signalling "invoke getCurrentCalls() when
     * this operation and all other pending operations are complete
     */
    private Message
    obtainCompleteMessage() {
        return obtainCompleteMessage(EVENT_OPERATION_COMPLETE);
    }

    /**
     * Obtain a message to use for signalling "invoke getCurrentCalls() when
     * this operation and all other pending operations are complete
     */
    private Message
    obtainCompleteMessage(int what) {
        mPendingOperations++;
        mLastRelevantPoll = null;
        mNeedsPoll = true;

        if (DBG_POLL) log("obtainCompleteMessage: pendingOperations=" +
                mPendingOperations + ", needsPoll=" + mNeedsPoll);

        return obtainMessage(what);
    }

    private void
    operationComplete() {
        mPendingOperations--;

        if (DBG_POLL) log("operationComplete: pendingOperations=" +
                mPendingOperations + ", needsPoll=" + mNeedsPoll);

        if (mPendingOperations == 0 && mNeedsPoll) {
            mLastRelevantPoll = obtainMessage(EVENT_POLL_CALLS_RESULT);
            mCi.getCurrentCalls(mLastRelevantPoll);
        } else if (mPendingOperations < 0) {
            // this should never happen
            Rlog.e(LOG_TAG,"CdmaCallTracker.pendingOperations < 0");
            mPendingOperations = 0;
        }
    }



    private void
    updatePhoneState() {
        PhoneConstants.State oldState = mState;

        if (mRingingCall.isRinging()) {
            mState = PhoneConstants.State.RINGING;
        } else if (mPendingMO != null ||
                !(mForegroundCall.isIdle() && mBackgroundCall.isIdle())) {
            mState = PhoneConstants.State.OFFHOOK;
        } else {
            mState = PhoneConstants.State.IDLE;
        }

        if (mState == PhoneConstants.State.IDLE && oldState != mState) {
            mVoiceCallEndedRegistrants.notifyRegistrants(
                new AsyncResult(null, null, null));
        } else if (oldState == PhoneConstants.State.IDLE && oldState != mState) {
            mVoiceCallStartedRegistrants.notifyRegistrants (
                    new AsyncResult(null, null, null));
        }
        if (Phone.DEBUG_PHONE) {
            log("update phone state, old=" + oldState + " new="+ mState);
        }
        if (mState != oldState) {
            mPhone.notifyPhoneStateChanged();
        }
    }

    // ***** Overwritten from CallTracker

    @Override
    protected void
    handlePollCalls(AsyncResult ar) {
        List polledCalls;

        if (ar.exception == null) {
            polledCalls = (List)ar.result;
        } else if (isCommandExceptionRadioNotAvailable(ar.exception)) {
            // just a dummy empty ArrayList to cause the loop
            // to hang up all the calls
            polledCalls = new ArrayList();
        } else {
            // Radio probably wasn't ready--try again in a bit
            // But don't keep polling if the channel is closed
            pollCallsAfterDelay();
            return;
        }

        Connection newRinging = null; //or waiting
        Connection newUnknown = null;
        boolean hasNonHangupStateChanged = false;   // Any change besides
                                                    // a dropped connection
        boolean hasAnyCallDisconnected = false;
        boolean needsPollDelay = false;
        boolean unknownConnectionAppeared = false;

        for (int i = 0, curDC = 0, dcSize = polledCalls.size()
                ; i < mConnections.length; i++) {
            CdmaConnection conn = mConnections[i];
            DriverCall dc = null;

            // polledCall list is sparse
            if (curDC < dcSize) {
                dc = (DriverCall) polledCalls.get(curDC);

                 /// M: @{
                 // Make sure there's a leading + on addresses with a TOA of 145
                 if (dc.isMT && dc.TOA == TOA_International) {
                     log("before format number = " + dc.number);
                     dc.number = PlusCodeToIddNddUtils.removeIddNddAddPlusCode(dc.number);
                     log("after format number = " + dc.number);
                 }
                 dc.number = PhoneNumberUtils.stringFromStringAndTOA(dc.number, dc.TOA);
                 /// @}

                if (dc.index == i+1) {
                    curDC++;
                } else {
                    dc = null;
                }
            }

            if (DBG_POLL) log("poll: conn[i=" + i + "]=" +
                    conn+", dc=" + dc);

            /// M: @{
            boolean isPhoneInEcmMode = SystemProperties.get(
                    TelephonyProperties.PROPERTY_INECM_MODE, "false").equals("true");
            boolean isEmergencyCall = false;
            /// @}
            
            if (conn == null && dc != null) {
                // Connection appeared in CLCC response that we don't know about
                if (mPendingMO != null && mPendingMO.compareTo(dc)) {

                    if (DBG_POLL) log("poll: pendingMO=" + mPendingMO);

                    // It's our pending mobile originating call
                    mConnections[i] = mPendingMO;
                    mPendingMO.mIndex = i;
                    mPendingMO.update(dc);
                    mPendingMO = null;

                    // Someone has already asked to hangup this call
                    if (mHangupPendingMO) {
                        mHangupPendingMO = false;
                        // Re-start Ecm timer when an uncompleted emergency call ends
                        /// M: @{
                        //if (mIsEcmTimerCanceled) {
                        isEmergencyCall = PhoneNumberUtils.isEmergencyNumberExt(
                                                mConnections[i].getOrigDialString(),
                                                PhoneConstants.PHONE_TYPE_CDMA);
                        if (isPhoneInEcmMode && isEmergencyCall) {
                        /// @}
                            handleEcmTimer(CDMAPhone.RESTART_ECM_TIMER);
                        }

                        try {
                            if (Phone.DEBUG_PHONE) log(
                                    "poll: hangupPendingMO, hangup conn " + i);
                            hangup(mConnections[i]);
                        } catch (CallStateException ex) {
                            Rlog.e(LOG_TAG, "unexpected error on hangup");
                        }

                        // Do not continue processing this poll
                        // Wait for hangup and repoll
                        return;
                    }
                } else {
                    if (Phone.DEBUG_PHONE) {
                        log("pendingMo=" + mPendingMO + ", dc=" + dc);
                    }
                    mConnections[i] = new CdmaConnection(mPhone.getContext(), dc, this, i);

                    if (mHandoverConnection != null) {
                        // Single Radio Voice Call Continuity (SRVCC) completed
                        mPhone.migrateFrom((PhoneBase) mPhone.getImsPhone());
                        mConnections[i].migrateFrom(mHandoverConnection);
                        mPhone.notifyHandoverStateChanged(mConnections[i]);
                        mHandoverConnection = null;
                    } else {
                        // find if the MT call is a new ring or unknown connection
                        newRinging = checkMtFindNewRinging(dc,i);
                        if (newRinging == null) {
                            unknownConnectionAppeared = true;
                        }
                    }
                    checkAndEnableDataCallAfterEmergencyCallDropped();
                }
                hasNonHangupStateChanged = true;
            } else if (conn != null && dc == null) {
                // This case means the RIL has no more active call anymore and
                // we need to clean up the foregroundCall and ringingCall.
                // Loop through foreground call connections as
                // it contains the known logical connections.
                int count = mForegroundCall.mConnections.size();
                for (int n = 0; n < count; n++) {
                    if (Phone.DEBUG_PHONE) log("adding fgCall cn " + n + " to droppedDuringPoll");
                    CdmaConnection cn = (CdmaConnection)mForegroundCall.mConnections.get(n);
                    mDroppedDuringPoll.add(cn);
                    /// M: @{
                    if (PhoneNumberUtils.isEmergencyNumberExt(cn.getOrigDialString(),
                            PhoneConstants.PHONE_TYPE_CDMA)) {
                        isEmergencyCall = true;
                    }
                    /// @}
                }
                count = mRingingCall.mConnections.size();
                // Loop through ringing call connections as
                // it may contain the known logical connections.
                for (int n = 0; n < count; n++) {
                    if (Phone.DEBUG_PHONE) log("adding rgCall cn " + n + " to droppedDuringPoll");
                    CdmaConnection cn = (CdmaConnection)mRingingCall.mConnections.get(n);
                    mDroppedDuringPoll.add(cn);
                    /// M: @{
                    if (PhoneNumberUtils.isEmergencyNumberExt(cn.getOrigDialString(),
                            PhoneConstants.PHONE_TYPE_CDMA)) {
                        isEmergencyCall = true;
                    }
                    /// @}
                }
                mForegroundCall.setGeneric(false);
                mRingingCall.setGeneric(false);

                // Re-start Ecm timer when the connected emergency call ends
                /// M: @{
                //if (mIsEcmTimerCanceled) {
                if (isPhoneInEcmMode && isEmergencyCall) {
                /// @}
                    handleEcmTimer(CDMAPhone.RESTART_ECM_TIMER);
                }
                // If emergency call is not going through while dialing
                checkAndEnableDataCallAfterEmergencyCallDropped();

                // Dropped connections are removed from the CallTracker
                // list but kept in the Call list
                mConnections[i] = null;
            } else if (conn != null && dc != null) { /* implicit conn.compareTo(dc) */
                // Call collision case
                if (conn.isIncoming() != dc.isMT) {
                    if (dc.isMT == true){
                        // Mt call takes precedence than Mo,drops Mo
                        mDroppedDuringPoll.add(conn);
                        // find if the MT call is a new ring or unknown connection
                        newRinging = checkMtFindNewRinging(dc,i);
                        if (newRinging == null) {
                            unknownConnectionAppeared = true;
                            newUnknown = conn;
                        }
                        checkAndEnableDataCallAfterEmergencyCallDropped();
                    } else {
                        // Call info stored in conn is not consistent with the call info from dc.
                        // We should follow the rule of MT calls taking precedence over MO calls
                        // when there is conflict, so here we drop the call info from dc and
                        // continue to use the call info from conn, and only take a log.
                        Rlog.e(LOG_TAG,"Error in RIL, Phantom call appeared " + dc);
                    }
                } else {
                    boolean changed;
                    changed = conn.update(dc);
                    hasNonHangupStateChanged = hasNonHangupStateChanged || changed;
                }
            }

            if (REPEAT_POLLING) {
                if (dc != null) {
                    // FIXME with RIL, we should not need this anymore
                    if ((dc.state == DriverCall.State.DIALING
                            /*&& cm.getOption(cm.OPTION_POLL_DIALING)*/)
                        || (dc.state == DriverCall.State.ALERTING
                            /*&& cm.getOption(cm.OPTION_POLL_ALERTING)*/)
                        || (dc.state == DriverCall.State.INCOMING
                            /*&& cm.getOption(cm.OPTION_POLL_INCOMING)*/)
                        || (dc.state == DriverCall.State.WAITING
                            /*&& cm.getOption(cm.OPTION_POLL_WAITING)*/)
                    ) {
                        // Sometimes there's no unsolicited notification
                        // for state transitions
                        needsPollDelay = true;
                    }
                }
            }
        }

        // This is the first poll after an ATD.
        // We expect the pending call to appear in the list
        // If it does not, we land here
        if (mPendingMO != null) {
            Rlog.d(LOG_TAG,"Pending MO dropped before poll fg state:"
                            + mForegroundCall.getState());

            mDroppedDuringPoll.add(mPendingMO);
            mPendingMO = null;
            mHangupPendingMO = false;
            if( mPendingCallInEcm) {
                mPendingCallInEcm = false;
            }
        }

        if (newRinging != null) {
            mPhone.notifyNewRingingConnection(newRinging);
        }

        // clear the "local hangup" and "missed/rejected call"
        // cases from the "dropped during poll" list
        // These cases need no "last call fail" reason
        for (int i = mDroppedDuringPoll.size() - 1; i >= 0 ; i--) {
            CdmaConnection conn = mDroppedDuringPoll.get(i);

            if (conn.isIncoming() && conn.getConnectTime() == 0) {
                // Missed or rejected call
                int cause;
                if (conn.mCause == DisconnectCause.LOCAL) {
                    cause = DisconnectCause.INCOMING_REJECTED;
                } else {
                    cause = DisconnectCause.INCOMING_MISSED;
                }

                if (Phone.DEBUG_PHONE) {
                    log("missed/rejected call, conn.cause=" + conn.mCause);
                    log("setting cause to " + cause);
                }
                mDroppedDuringPoll.remove(i);
                hasAnyCallDisconnected |= conn.onDisconnect(cause);
            } else if (conn.mCause == DisconnectCause.LOCAL
                    || conn.mCause == DisconnectCause.INVALID_NUMBER) {
                mDroppedDuringPoll.remove(i);
                hasAnyCallDisconnected |= conn.onDisconnect(conn.mCause);
            }
        }

        // Any non-local disconnects: determine cause
        if (mDroppedDuringPoll.size() > 0) {
            mCi.getLastCallFailCause(
                obtainNoPollCompleteMessage(EVENT_GET_LAST_CALL_FAIL_CAUSE));
        }

        if (needsPollDelay) {
            pollCallsAfterDelay();
        }

        // Cases when we can no longer keep disconnected Connection's
        // with their previous calls
        // 1) the phone has started to ring
        // 2) A Call/Connection object has changed state...
        //    we may have switched or held or answered (but not hung up)
        if (newRinging != null || hasNonHangupStateChanged || hasAnyCallDisconnected) {
            internalClearDisconnected();
        }

        updatePhoneState();

        if (unknownConnectionAppeared) {
            mPhone.notifyUnknownConnection(newUnknown);
        }

        if (hasNonHangupStateChanged || newRinging != null || hasAnyCallDisconnected) {
            mPhone.notifyPreciseCallStateChanged();
        }

        //dumpState();
    }

    //***** Called from CdmaConnection
    /*package*/ void
    hangup (CdmaConnection conn) throws CallStateException {
        if (conn.mOwner != this) {
            throw new CallStateException ("CdmaConnection " + conn
                                    + "does not belong to CdmaCallTracker " + this);
        }

        if (conn == mPendingMO) {
            // We're hanging up an outgoing call that doesn't have it's
            // GSM index assigned yet

            if (Phone.DEBUG_PHONE) log("hangup: set hangupPendingMO to true");
            mHangupPendingMO = true;
        } else if ((conn.getCall() == mRingingCall)
                && (mRingingCall.getState() == CdmaCall.State.WAITING)) {
            // Handle call waiting hang up case.
            //
            // The ringingCall state will change to IDLE in CdmaCall.detach
            // if the ringing call connection size is 0. We don't specifically
            // set the ringing call state to IDLE here to avoid a race condition
            // where a new call waiting could get a hang up from an old call
            // waiting ringingCall.
            //
            // PhoneApp does the call log itself since only PhoneApp knows
            // the hangup reason is user ignoring or timing out. So conn.onDisconnect()
            // is not called here. Instead, conn.onLocalDisconnect() is called.
            conn.onLocalDisconnect();
            updatePhoneState();
            mPhone.notifyPreciseCallStateChanged();
            return;
        } else {
            try {
                mCi.hangupConnection (conn.getCDMAIndex(), obtainCompleteMessage());
            } catch (CallStateException ex) {
                // Ignore "connection not found"
                // Call may have hung up already
                Rlog.w(LOG_TAG,"CdmaCallTracker WARN: hangup() on absent connection "
                                + conn);
            }
        }

        conn.onHangupLocal();
    }

    /*package*/ void
    separate (CdmaConnection conn) throws CallStateException {
        if (conn.mOwner != this) {
            throw new CallStateException ("CdmaConnection " + conn
                                    + "does not belong to CdmaCallTracker " + this);
        }
        try {
            mCi.separateConnection (conn.getCDMAIndex(),
                obtainCompleteMessage(EVENT_SEPARATE_RESULT));
        } catch (CallStateException ex) {
            // Ignore "connection not found"
            // Call may have hung up already
            Rlog.w(LOG_TAG,"CdmaCallTracker WARN: separate() on absent connection "
                          + conn);
        }
    }

    //***** Called from CDMAPhone

    /*package*/ void
    setMute(boolean mute) {
        mDesiredMute = mute;
        mCi.setMute(mDesiredMute, null);
    }

    /*package*/ boolean
    getMute() {
        return mDesiredMute;
    }


    //***** Called from CdmaCall

    /* package */ void
    hangup (CdmaCall call) throws CallStateException {
        if (call.getConnections().size() == 0) {
            throw new CallStateException("no connections in call");
        }

        if (call == mRingingCall) {
            if (Phone.DEBUG_PHONE) log("(ringing) hangup waiting or background");
            mCi.hangupWaitingOrBackground(obtainCompleteMessage());
        } else if (call == mForegroundCall) {
            if (call.isDialingOrAlerting()) {
                if (Phone.DEBUG_PHONE) {
                    log("(foregnd) hangup dialing or alerting...");
                }
                hangup((CdmaConnection)(call.getConnections().get(0)));
            } else {
                hangupForegroundResumeBackground();
            }
        } else if (call == mBackgroundCall) {
            if (mRingingCall.isRinging()) {
                if (Phone.DEBUG_PHONE) {
                    log("hangup all conns in background call");
                }
                hangupAllConnections(call);
            } else {
                hangupWaitingOrBackground();
            }
        } else {
            throw new RuntimeException ("CdmaCall " + call +
                    "does not belong to CdmaCallTracker " + this);
        }

        call.onHangupLocal();
        mPhone.notifyPreciseCallStateChanged();
    }

    /* package */
    void hangupWaitingOrBackground() {
        if (Phone.DEBUG_PHONE) log("hangupWaitingOrBackground");
        mCi.hangupWaitingOrBackground(obtainCompleteMessage());
    }

    /* package */
    void hangupForegroundResumeBackground() {
        if (Phone.DEBUG_PHONE) log("hangupForegroundResumeBackground");
        mCi.hangupForegroundResumeBackground(obtainCompleteMessage());
    }

    void hangupConnectionByIndex(CdmaCall call, int index)
            throws CallStateException {
        int count = call.mConnections.size();
        for (int i = 0; i < count; i++) {
            CdmaConnection cn = (CdmaConnection)call.mConnections.get(i);
            if (cn.getCDMAIndex() == index) {
                mCi.hangupConnection(index, obtainCompleteMessage());
                return;
            }
        }

        throw new CallStateException("no gsm index found");
    }

    void hangupAllConnections(CdmaCall call) {
        try {
            int count = call.mConnections.size();
            for (int i = 0; i < count; i++) {
                CdmaConnection cn = (CdmaConnection)call.mConnections.get(i);
                mCi.hangupConnection(cn.getCDMAIndex(), obtainCompleteMessage());
            }
        } catch (CallStateException ex) {
            Rlog.e(LOG_TAG, "hangupConnectionByIndex caught " + ex);
        }
    }

    /* package */
    CdmaConnection getConnectionByIndex(CdmaCall call, int index)
            throws CallStateException {
        int count = call.mConnections.size();
        for (int i = 0; i < count; i++) {
            CdmaConnection cn = (CdmaConnection)call.mConnections.get(i);
            if (cn.getCDMAIndex() == index) {
                return cn;
            }
        }

        return null;
    }

    private void flashAndSetGenericTrue() {
        mCi.sendCDMAFeatureCode("", obtainMessage(EVENT_SWITCH_RESULT));

        // Set generic to true because in CDMA it is not known what
        // the status of the call is after a call waiting is answered,
        // 3 way call merged or a switch between calls.
        mForegroundCall.setGeneric(true);
        mPhone.notifyPreciseCallStateChanged();
    }

    private void handleRadioNotAvailable() {
        // handlePollCalls will clear out its
        // call list when it gets the CommandException
        // error result from this
        pollCallsWhenSafe();
    }

    private void notifyCallWaitingInfo(CdmaCallWaitingNotification obj) {
        if (mCallWaitingRegistrants != null) {
            mCallWaitingRegistrants.notifyRegistrants(new AsyncResult(null, obj, null));
        }
    }

    private void handleCallWaitingInfo (CdmaCallWaitingNotification cw) {
        /// M: @{
        // optimize callwaiting
        String address = cw.number;
        // Make sure there's a leading + on addresses with a TOA of 145
        Rlog.d(LOG_TAG, "handleCallWaitingInfo before format number = " + cw.number);
        if (address != null && address.length() > 0) {
            String number = PlusCodeToIddNddUtils.removeIddNddAddPlusCode(address);
            if (number != null) {
                address = number;
                if (cw.numberType == 1 && number != null && number.length() > 0
                        && number.charAt(0) != '+') {
                    address = "+" + number;
                }
            }
            Rlog.d(LOG_TAG, "handleCallWaitingInfo after format number = " + address);
            // reset callwaiting number
            cw.number = address;

            int result = PackageManager.PERMISSION_GRANTED;
            if (MobileManagerUtils.isSupported()) {
                try {
                    if (mMobileManagerService == null) {
                        mMobileManagerService = IMobileManagerService.Stub.asInterface(
                            ServiceManager.getService(Context.MOBILE_SERVICE));
                    }
                    log("getInterceptionEnabledSetting:"
                        + mMobileManagerService.getInterceptionEnabledSetting());
                    if (mMobileManagerService.getInterceptionEnabledSetting()) {
                        Bundle parameter = new Bundle();
                        parameter.putString(IMobileManager.PARAMETER_PHONENUMBER, address);
                        parameter.putInt(IMobileManager.PARAMETER_CALLTYPE, 0);
                        parameter.putInt(IMobileManager.PARAMETER_SLOTID,
                                SubscriptionManager.getSlotId(mPhone.getSubId()));
                        result = mMobileManagerService.triggerManagerApListener(
                                        IMobileManager.CONTROLLER_CALL,
                                        parameter, PackageManager.PERMISSION_GRANTED);
                    }
                } catch (RemoteException e) {
                    Rlog.e(LOG_TAG, "MoMS suppressing notification failed!");
                }
            }
            log("MoMS check result:" + result);
            if (result != PackageManager.PERMISSION_GRANTED) {
                try {
                    CdmaConnection conn = new CdmaConnection(mPhone.getContext(),
                                                cw, this, mRingingCall);
                    conn.hangup();
                    conn.onDisconnectByMom(DisconnectCause.INCOMING_MISSED);
                    conn = null;
                } catch (CallStateException e) {
                    Rlog.e(LOG_TAG, "hangup failed, exception:" + e);
                }
                return;
            }

            List fgConns = mForegroundCall.getConnections();
            int fgConnCount = 0;
            if (fgConns != null) {
                fgConnCount = fgConns.size();
            }
            long curTime = System.currentTimeMillis();
            for (int i = 0; i < fgConnCount; i++) {
                CdmaConnection fgConn = (CdmaConnection)(fgConns.get(i));
                if (address.equals(fgConn.getAddress()) && 
                    (curTime - fgConn.getRealAcceptTime()) < CALLWAITING_THRESHOLD_TIME) {
                    Rlog.d(LOG_TAG, "handleCallWaitingInfo CallWaiting invalid, return");
                    return;
                }
            }

            CdmaConnection lastRingConn = (CdmaConnection)(mRingingCall.getLatestConnection());
            if (lastRingConn != null) {
                if (address.equals(lastRingConn.getAddress()) && 
                    (curTime - lastRingConn.getCreateTime()) < CALLWAITING_THRESHOLD_TIME) {
                    mRingingCall.detach(lastRingConn);
                    Rlog.d(LOG_TAG, "handleCallWaitingInfo detach last RingConn");
                }
            }
        }
        /// @}

        // Check how many connections in foregroundCall.
        // If the connection in foregroundCall is more
        // than one, then the connection information is
        // not reliable anymore since it means either
        // call waiting is connected or 3 way call is
        // dialed before, so set generic.
        if (mForegroundCall.mConnections.size() > 1 ) {
            mForegroundCall.setGeneric(true);
        }

        // Create a new CdmaConnection which attaches itself to ringingCall.
        mRingingCall.setGeneric(false);
        new CdmaConnection(mPhone.getContext(), cw, this, mRingingCall);
        updatePhoneState();

        // Finally notify application
        notifyCallWaitingInfo(cw);
    }
    //****** Overridden from Handler

    @Override
    public void
    handleMessage (Message msg) {
        AsyncResult ar;

        if (!mPhone.mIsTheCurrentActivePhone) {
            Rlog.w(LOG_TAG, "Ignoring events received on inactive CdmaPhone");
            return;
        }
        switch (msg.what) {
            case EVENT_POLL_CALLS_RESULT:{
                Rlog.d(LOG_TAG, "Event EVENT_POLL_CALLS_RESULT Received");
                ar = (AsyncResult)msg.obj;

                if(msg == mLastRelevantPoll) {
                    if(DBG_POLL) log(
                            "handle EVENT_POLL_CALL_RESULT: set needsPoll=F");
                    mNeedsPoll = false;
                    mLastRelevantPoll = null;
                    handlePollCalls((AsyncResult)msg.obj);
                }
            }
            break;

            case EVENT_OPERATION_COMPLETE:
                operationComplete();
            break;

            case EVENT_SWITCH_RESULT:
                 // In GSM call operationComplete() here which gets the
                 // current call list. But in CDMA there is no list so
                 // there is nothing to do.
            break;

            case EVENT_GET_LAST_CALL_FAIL_CAUSE:
                int causeCode;
                ar = (AsyncResult)msg.obj;

                operationComplete();

                if (ar.exception != null) {
                    // An exception occurred...just treat the disconnect
                    // cause as "normal"
                    causeCode = CallFailCause.NORMAL_CLEARING;
                    Rlog.i(LOG_TAG,
                            "Exception during getLastCallFailCause, assuming normal disconnect");
                } else {
                    causeCode = ((int[])ar.result)[0];
                }

                for (int i = 0, s =  mDroppedDuringPoll.size()
                        ; i < s ; i++
                ) {
                    CdmaConnection conn = mDroppedDuringPoll.get(i);

                    conn.onRemoteDisconnect(causeCode);
                }

                updatePhoneState();

                mPhone.notifyPreciseCallStateChanged();
                mDroppedDuringPoll.clear();
            break;

            case EVENT_REPOLL_AFTER_DELAY:
            case EVENT_CALL_STATE_CHANGE:
                pollCallsWhenSafe();
            break;

            case EVENT_RADIO_AVAILABLE:
                handleRadioAvailable();
            break;

            case EVENT_RADIO_NOT_AVAILABLE:
                handleRadioNotAvailable();
            break;

            case EVENT_EXIT_ECM_RESPONSE_CDMA:
                // no matter the result, we still do the same here
                if (mPendingCallInEcm) {
                    mCi.dial(mPendingMO.getAddress(), mPendingCallClirMode, obtainCompleteMessage());
                    mPendingCallInEcm = false;
                }
                mPhone.unsetOnEcbModeExitResponse(this);
            break;

            case EVENT_CALL_WAITING_INFO_CDMA:
               ar = (AsyncResult)msg.obj;
               if (ar.exception == null) {
                   handleCallWaitingInfo((CdmaCallWaitingNotification)ar.result);
                   Rlog.d(LOG_TAG, "Event EVENT_CALL_WAITING_INFO_CDMA Received");
               }
            break;

            case EVENT_THREE_WAY_DIAL_L2_RESULT_CDMA:
                ar = (AsyncResult)msg.obj;
                if (ar.exception == null) {
                    // Assume 3 way call is connected
                    mPendingMO.onConnectedInOrOut();
                    mPendingMO = null;
                }
            break;

            case EVENT_THREE_WAY_DIAL_BLANK_FLASH:
                ar = (AsyncResult) msg.obj;
                if (ar.exception == null) {
                    postDelayed(
                            new Runnable() {
                                public void run() {
                                    if (mPendingMO != null) {
                                        mCi.sendCDMAFeatureCode(mPendingMO.getAddress(),
                                                obtainMessage(EVENT_THREE_WAY_DIAL_L2_RESULT_CDMA));
                                    }
                                }
                            }, m3WayCallFlashDelay);
                } else {
                    mPendingMO = null;
                    Rlog.w(LOG_TAG, "exception happened on Blank Flash for 3-way call");
                }
            break;

            /// M: @{
            // for register CDMA call accepted message
            case EVENT_CDMA_CALL_ACCEPTED:
               ar = (AsyncResult)msg.obj;
               if (ar.exception == null) {
                   handleCallAccepted();
                   Rlog.d(LOG_TAG, "EVENT_CDMA_CALL_ACCEPTED");
               }
               break;

            // for dial three way call after send a empty flash
            case EVENT_CDMA_DIAL_THREEWAY_DELAY:
                String dialString = (String) msg.obj;
                Rlog.d(LOG_TAG, "EVENT_CDMA_DIAL_THREEWAY_DELAY, dialString:" + dialString);
                if (dialString != null) {
                    String dialNumber = formatDialNumber(dialString);
                    mCi.sendCDMAFeatureCode(dialNumber,
                        obtainMessage(EVENT_THREE_WAY_DIAL_L2_RESULT_CDMA));
                }
                break;

            // for exit EMC when dial threeway
            case EVENT_EXIT_ECM_RESPONSE_DIAL_THREEWAY:
                String threeWayDialString = (String) ((AsyncResult)(msg.obj)).userObj;
                Rlog.d(LOG_TAG, "dial threeway after exit ECM pendingCallInEcm:"
                    + mPendingCallInEcm + ", dialString:" + threeWayDialString);
                if (mPendingCallInEcm) {
                    mCi.sendCDMAFeatureCode("", obtainMessage(EVENT_SWITCH_RESULT));
                    dialThreeWay(threeWayDialString);
                    mPendingCallInEcm = false;
                }
                mPhone.unsetOnThreeWayEcmExitResponse(this);
            break;
            /// @}

            default:{
                Rlog.d(LOG_TAG, "unexpected event:" + msg.what);
                throw new RuntimeException("unexpected event not handled");
            }
        }
    }

    /**
     * Handle Ecm timer to be canceled or re-started
     */
    private void handleEcmTimer(int action) {
        if (Phone.DEBUG_PHONE) {
            log("handleEcmTimer,mIsEcmTimerCanceled=" + mIsEcmTimerCanceled);
        }
        mPhone.handleTimerInEmergencyCallbackMode(action);
        switch(action) {
        case CDMAPhone.CANCEL_ECM_TIMER: mIsEcmTimerCanceled = true; break;
        case CDMAPhone.RESTART_ECM_TIMER: mIsEcmTimerCanceled = false; break;
        default:
            Rlog.e(LOG_TAG, "handleEcmTimer, unsupported action " + action);
        }
    }

    /**
     * Disable data call when emergency call is connected
     */
    private void disableDataCallInEmergencyCall(String dialString) {
        /// M: @{
        if (PhoneNumberUtils.isEmergencyNumberExt(dialString,
                PhoneConstants.PHONE_TYPE_CDMA)) {
        //if (PhoneNumberUtils.isLocalEmergencyNumber(mPhone.getContext(), dialString)) {
        /// @}
            if (Phone.DEBUG_PHONE) log("disableDataCallInEmergencyCall");
            mIsInEmergencyCall = true;
            mPhone.mDcTracker.setInternalDataEnabled(false);
        }
    }

    /**
     * Check and enable data call after an emergency call is dropped if it's
     * not in ECM
     */
    private void checkAndEnableDataCallAfterEmergencyCallDropped() {
        if (mIsInEmergencyCall) {
            mIsInEmergencyCall = false;
            String inEcm=SystemProperties.get(TelephonyProperties.PROPERTY_INECM_MODE, "false");
            if (Phone.DEBUG_PHONE) {
                log("checkAndEnableDataCallAfterEmergencyCallDropped,inEcm=" + inEcm);
            }
            if (inEcm.compareTo("false") == 0) {
                // Re-initiate data connection
                mPhone.mDcTracker.setInternalDataEnabled(true);
            }
        }
    }

    /**
     * Check the MT call to see if it's a new ring or
     * a unknown connection.
     */
    private Connection checkMtFindNewRinging(DriverCall dc, int i) {

        Connection newRinging = null;

        /// M: @{
        if (mConnections[i].getCall() == mRingingCall) {
            int result = PackageManager.PERMISSION_GRANTED;
            if (MobileManagerUtils.isSupported()) {
                try {
                    if (mMobileManagerService == null) {
                        mMobileManagerService = IMobileManagerService.Stub.asInterface(
                            ServiceManager.getService(Context.MOBILE_SERVICE));
                    }
                    log("getInterceptionEnabledSetting:"
                        + mMobileManagerService.getInterceptionEnabledSetting());
                    if (mMobileManagerService.getInterceptionEnabledSetting()) {
                        Bundle parameter = new Bundle();
                        parameter.putString(IMobileManager.PARAMETER_PHONENUMBER, dc.number);
                        parameter.putInt(IMobileManager.PARAMETER_CALLTYPE, dc.isVoice ? 0 : 10);
                        parameter.putInt(IMobileManager.PARAMETER_SLOTID,
                                SubscriptionManager.getSlotId(mPhone.getSubId()));
                        result = mMobileManagerService.triggerManagerApListener(
                                        IMobileManager.CONTROLLER_CALL,
                                        parameter, PackageManager.PERMISSION_GRANTED);
                    }
                } catch (RemoteException e) {
                    Rlog.e(LOG_TAG, "MoMS suppressing notification failed!");
                }
            }
            log("MoMS check result:" + result);
            if (result != PackageManager.PERMISSION_GRANTED) {
                try {
                    mConnections[i].hangup();
                    mConnections[i].onDisconnectByMom(DisconnectCause.INCOMING_MISSED);
                    mConnections[i] = null;
                } catch (CallStateException e) {
                    Rlog.e(LOG_TAG, "hangup failed, exception:" + e);
                }
                return newRinging;
            }
        }
        /// @}

        // it's a ringing call
        if (mConnections[i].getCall() == mRingingCall) {
            newRinging = mConnections[i];
            if (Phone.DEBUG_PHONE) log("Notify new ring " + dc);
        } else {
            // Something strange happened: a call which is neither
            // a ringing call nor the one we created. It could be the
            // call collision result from RIL
            Rlog.e(LOG_TAG,"Phantom call appeared " + dc);
            // If it's a connected call, set the connect time so that
            // it's non-zero.  It may not be accurate, but at least
            // it won't appear as a Missed Call.
            if (dc.state != DriverCall.State.ALERTING
                && dc.state != DriverCall.State.DIALING) {
                mConnections[i].onConnectedInOrOut();
                if (dc.state == DriverCall.State.HOLDING) {
                    // We've transitioned into HOLDING
                    mConnections[i].onStartedHolding();
                }
            }
        }
        return newRinging;
    }

    /**
     * Check if current call is in emergency call
     *
     * @return true if it is in emergency call
     *         false if it is not in emergency call
     */
    boolean isInEmergencyCall() {
        return mIsInEmergencyCall;
    }

    @Override
    protected void log(String msg) {
        Rlog.d(LOG_TAG, "[CdmaCallTracker] " + msg);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("GsmCallTracker extends:");
        super.dump(fd, pw, args);
        pw.println("droppedDuringPoll: length=" + mConnections.length);
        for(int i=0; i < mConnections.length; i++) {
            pw.printf(" mConnections[%d]=%s\n", i, mConnections[i]);
        }
        pw.println(" mVoiceCallEndedRegistrants=" + mVoiceCallEndedRegistrants);
        pw.println(" mVoiceCallStartedRegistrants=" + mVoiceCallStartedRegistrants);
        pw.println(" mCallWaitingRegistrants=" + mCallWaitingRegistrants);
        pw.println("droppedDuringPoll: size=" + mDroppedDuringPoll.size());
        for(int i = 0; i < mDroppedDuringPoll.size(); i++) {
            pw.printf( " mDroppedDuringPoll[%d]=%s\n", i, mDroppedDuringPoll.get(i));
        }
        pw.println(" mRingingCall=" + mRingingCall);
        pw.println(" mForegroundCall=" + mForegroundCall);
        pw.println(" mBackgroundCall=" + mBackgroundCall);
        pw.println(" mPendingMO=" + mPendingMO);
        pw.println(" mHangupPendingMO=" + mHangupPendingMO);
        pw.println(" mPendingCallInEcm=" + mPendingCallInEcm);
        pw.println(" mIsInEmergencyCall=" + mIsInEmergencyCall);
        pw.println(" mPhone=" + mPhone);
        pw.println(" mDesiredMute=" + mDesiredMute);
        pw.println(" mPendingCallClirMode=" + mPendingCallClirMode);
        pw.println(" mState=" + mState);
        pw.println(" mIsEcmTimerCanceled=" + mIsEcmTimerCanceled);
    }

    /// M: @{
    // for register CDMA call accepted message
    private void handleCallAccepted() {
        Rlog.d(LOG_TAG, "handleCallAccepted");
        List connections = mForegroundCall.getConnections();
        int count = connections.size();
        Rlog.d(LOG_TAG, "count:" + count);
        if (count == 1) {
            CdmaConnection c = (CdmaConnection)connections.get(0);
            c.onCDMACallAccept();
        }

        //if CDMA phone accepted, start a Vibrator
        if (mVibrator == null) {
            mVibrator = (Vibrator) mPhone.getContext().getSystemService(Context.VIBRATOR_SERVICE);
        }
        Rlog.d(LOG_TAG,"handleCallAccepted start vibrate" + mVibrator);
        mVibrator.vibrate(MO_CALL_VIBRATE_TIME);
    }

    // Called from CdmaCall
    // disc only RingingCall with specific cause instead of INCOMING_REJECTED
    /* package */ void
    hangup (CdmaCall call, int discRingingCallCause) throws CallStateException {
        if (call.getConnections().size() == 0) {
            throw new CallStateException("no connections in call");
        }
        if (call == mRingingCall) {
            if (Phone.DEBUG_PHONE) log("(ringing) hangup waiting or background");
            mCi.hangupWaitingOrBackground(obtainCompleteMessage());
        } else {
            throw new RuntimeException ("CdmaCall " + call +
                    "does not belong to CdmaCallTracker " + this);
        }
        call.onHangupLocal();
        // Change call's state as DISCONNECTING in call.onHangupLocal() 
        // --> cn.onHangupLocal(): set cn's cause as DisconnectionCause.LOCAL
        if (call == mRingingCall) {
            CdmaConnection ringingConn = (CdmaConnection)(call.getConnections().get(0));
            ringingConn.mCause = discRingingCallCause;
        }
        mPhone.notifyPreciseCallStateChanged();
    }

    // format dial number before dialing.
    private String formatDialNumber(String dialNumber) {
        // replace pluscode with IDDNDD first, if dialNumber starts with "+".
        Rlog.d(LOG_TAG, "before format number = " + dialNumber);
        if (dialNumber != null && dialNumber.length() > 0 && dialNumber.startsWith("+")) {
            if (PlusCodeToIddNddUtils.canFormatPlusToIddNdd()) {
                String format = PlusCodeToIddNddUtils.replacePlusCodeWithIddNdd(dialNumber);
                if (format != null && format.length() > 0) {
                    dialNumber = format;
                }
            }
        }
        Rlog.d(LOG_TAG, "after replace pluscode dial number = " + dialNumber);

        // if CNIR-ENABLE = TURE, we have to add prefix before the dial number.
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mPhone.getContext());
        boolean cnir_enable = sp.getBoolean("cdma_cnir", false);
        if (cnir_enable) {
            String newNumber = CNIR_PREFIX + dialNumber;
            dialNumber = newNumber;
            Rlog.d(LOG_TAG,"after add dialNumber with CNIR number = '" + dialNumber + "'...");
        }
        return dialNumber;
    }

    void hangupAll() {
        if (Phone.DEBUG_PHONE) log("hangupAll");
        mCi.hangupAll(obtainCompleteMessage());
    }

    void hangupActiveCall() throws CallStateException {
        if (Phone.DEBUG_PHONE) log("hangupActiveCall");
        if (mForegroundCall.getConnections().size() == 0) {
            throw new CallStateException("no connections in call");
        }
        hangupAllConnections(mForegroundCall);
    }

    /*Connection
    vtDial (String dialString, int clirMode) throws CallStateException {
        // note that this triggers call state changed notif
        clearDisconnected();

        if (!canDial()) {
            throw new CallStateException("cannot dial in current state");
        }

        String inEcm = SystemProperties.get(TelephonyProperties.PROPERTY_INECM_MODE, "false");
        boolean isPhoneInEcmMode = inEcm.equals("true");
        boolean isEmergencyCall = PhoneNumberUtils.isEmergencyNumber(dialString);

        // Cancel Ecm timer if a second emergency call is originating in Ecm mode
        if (isPhoneInEcmMode && isEmergencyCall) {
            handleEcmTimer(CDMAPhone.CANCEL_ECM_TIMER);
        }

        // We are initiating a call therefore even if we previously
        // didn't know the state (i.e. Generic was true) we now know
        // and therefore can set Generic to false.
        mForegroundCall.setGeneric(false);

        // The new call must be assigned to the foreground call.
        // That call must be idle, so place anything that's
        // there on hold
        if (mForegroundCall.getState() == CdmaCall.State.ACTIVE) {
            return dialThreeWay(dialString);
        }

        mPendingMO = new CdmaConnection(mPhone.getContext(), dialString, this, mForegroundCall);
        mHangupPendingMO = false;

        if (mPendingMO.getAddress() == null || mPendingMO.getAddress().length() == 0
            || mPendingMO.getAddress().indexOf(PhoneNumberUtils.WILD) >= 0) {
            // Phone number is invalid
            mPendingMO.mCause = DisconnectCause.INVALID_NUMBER;

            // handlePollCalls() will notice this call not present
            // and will mark it as dropped.
            pollCallsWhenSafe();
        } else {
            // Always unmute when initiating a new call
            setMute(false);

            // Check data call
            disableDataCallInEmergencyCall(dialString);

            // In Ecm mode, if another emergency call is dialed, Ecm mode will not exit.
            if(!isPhoneInEcmMode || (isPhoneInEcmMode && isEmergencyCall)) {
                mCi.vtDial(mPendingMO.getAddress(), clirMode, obtainCompleteMessage());
            } else {
                mPhone.exitEmergencyCallbackMode();
                mPhone.setOnEcbModeExitResponse(this,EVENT_EXIT_ECM_RESPONSE_CDMA, null);
                mPendingCallClirMode = clirMode;
                mPendingCallInEcm = true;
            }
        }

        updatePhoneState();
        mPhone.notifyPreciseCallStateChanged();

        return mPendingMO;
    }

    Connection
    vtDial (String dialString) throws CallStateException {
        return vtDial(dialString, CommandsInterface.CLIR_DEFAULT);
    }

    void getCurrentCallMeter(Message result) {
        if (Phone.DEBUG_PHONE) log("getCurrentCallMeter");
        mCi.getCurrentCallMeter(result);
    }

    void getAccumulatedCallMeter(Message result) {
        if (Phone.DEBUG_PHONE) log("getAccumulatedCallMeter");
        mCi.getAccumulatedCallMeter(result);
    }

    void getAccumulatedCallMeterMaximum(Message result) {
        if (Phone.DEBUG_PHONE) log("getAccumulatedCallMeterMaximum");
        mCi.getAccumulatedCallMeterMaximum(result);
    }

    void getPpuAndCurrency(Message result) {
        if (Phone.DEBUG_PHONE) log("getPpuAndCurrency");
        mCi.getPpuAndCurrency(result);
    }

    void setAccumulatedCallMeterMaximum(String acmmax, String pin2, Message result) {
        if (Phone.DEBUG_PHONE) log("setAccumulatedCallMeterMaximum");
        mCi.setAccumulatedCallMeterMaximum(acmmax, pin2, result);
    }

    void resetAccumulatedCallMeter(String pin2, Message result) {
        if (Phone.DEBUG_PHONE) log("resetAccumulatedCallMeter");
        mCi.resetAccumulatedCallMeter(pin2, result);
    }

    void setPpuAndCurrency(String currency, String ppu, String pin2, Message result) {
        if (Phone.DEBUG_PHONE) log("setPpuAndCurrency");
        mCi.setPpuAndCurrency(currency, ppu, pin2, result);
    }*/
    /// @}
}
