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

package com.android.internal.telephony.gsm;

import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.os.RegistrantList;
import android.os.SystemProperties;
import android.telephony.DisconnectCause;
import android.telephony.PhoneNumberUtils;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.telephony.gsm.GsmCellLocation;
import android.util.EventLog;
import android.telephony.Rlog;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.CallTracker;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.DriverCall;
import com.android.internal.telephony.EventLogTags;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.telephony.UUSInfo;
import com.android.internal.telephony.gsm.CallFailCause;
import com.android.internal.telephony.gsm.GSMPhone;
import com.android.internal.telephony.gsm.GsmCall;
import com.android.internal.telephony.gsm.GsmConnection;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;
import java.util.ArrayList;


/**
 * {@hide}
 */
public final class GsmCallTracker extends CallTracker {
    static final String LOG_TAG = "GsmCallTracker";
    private static final boolean REPEAT_POLLING = false;

    private static final boolean DBG_POLL = true;

    //***** Constants

    static final int MAX_CONNECTIONS = 7;   // only 7 connections allowed in GSM
    static final int MAX_CONNECTIONS_PER_CALL = 5; // only 5 connections allowed per call

    //***** Instance Variables
    GsmConnection mConnections[] = new GsmConnection[MAX_CONNECTIONS];
    RegistrantList mVoiceCallEndedRegistrants = new RegistrantList();
    RegistrantList mVoiceCallStartedRegistrants = new RegistrantList();
    /* M: call control part start */
    RegistrantList mVoiceCallIncomingIndicationRegistrants = new RegistrantList();
    /* M: call control part end */

    // connections dropped during last poll
    ArrayList<GsmConnection> mDroppedDuringPoll
        = new ArrayList<GsmConnection>(MAX_CONNECTIONS);

    GsmCall mRingingCall = new GsmCall(this);
            // A call that is ringing or (call) waiting
    GsmCall mForegroundCall = new GsmCall(this);
    GsmCall mBackgroundCall = new GsmCall(this);

    GsmConnection mPendingMO;
    boolean mHangupPendingMO;

    GSMPhone mPhone;

    boolean mDesiredMute = false;    // false = mute off

    /* M: call control part start */
    public PhoneConstants.State mState = PhoneConstants.State.IDLE;
    /* M: call control part end */

    Call.SrvccState mSrvccState = Call.SrvccState.NONE;

    /* M: call control part start */
    boolean hasPendingSwapRequest = false;

    /* Cnap indication */
    private String mPendingCnap = null;

    GsmCallTrackerHelper mHelper;

    WaitingForHold mWaitingForHoldRequest = new WaitingForHold();

    private int mSpeechCodecType = 0;
    /* M: call control part end */

    //***** Events

    /* M: call control part start */
    class WaitingForHold {

        private boolean mWaiting = false;
        private String mDialString = null;
        private int mClirMode = 0;
        private UUSInfo mUUSInfo = null;

        WaitingForHold() {
            reset();
        }

        boolean isWaiting() {
            return mWaiting;
        }

        void set() {
            mWaiting = true;
        }

        public void set(String dialSting, int clir, UUSInfo uusinfo) {
            mWaiting = true;
            mDialString = dialSting;
            mClirMode = clir;
            mUUSInfo = uusinfo;
        }

        public void reset() {

            Rlog.d(LOG_TAG, "Reset WaitingForHoldRequest variables");

            mWaiting = false;
            mDialString = null;
            mClirMode = 0;
            mUUSInfo = null;
        }

        /**
         * Check if there is another action need to be performed after holding request is done.
         *
         * @return Return true if there exists action need to be perform, else return false.
         */
        private boolean handleOperation() {
            Rlog.d(LOG_TAG, "handleWaitingOperation begin");

            if (mWaiting) {
                mCi.dial(mDialString, mClirMode, mUUSInfo, obtainCompleteMessage());

                reset();
                Rlog.d(LOG_TAG, "handleWaitingOperation end");
                return true;
            }
            return false;
        }
    }
    /* M: call control part end */

    //***** Constructors

    GsmCallTracker (GSMPhone phone) {
        this.mPhone = phone;
        mCi = phone.mCi;

        mCi.registerForCallStateChanged(this, EVENT_CALL_STATE_CHANGE, null);

        mCi.registerForOn(this, EVENT_RADIO_AVAILABLE, null);
        mCi.registerForNotAvailable(this, EVENT_RADIO_NOT_AVAILABLE, null);

//MTK_TC1_FEATURE for LGE CSMCC_MO_CALL_MODIFIED {         
if (SystemProperties.get("ro.mtk_tc1_feature").equals("1")) { 
        mCi.registerForMoCallStateChanged(this, EVENT_MO_CALL_STATE_CHANGE, null);
}
//}

        /* M: call control part start */
        mCi.registerForOffOrNotAvailable(this, EVENT_RADIO_OFF_OR_NOT_AVAILABLE, null);
        mCi.setOnIncomingCallIndication(this, EVENT_INCOMING_CALL_INDICATION, null);
        mCi.setCnapNotify(this, EVENT_CNAP_INDICATION, null);
        mCi.setOnSpeechCodecInfo(this, EVENT_SPEECH_CODEC_INFO, null);

        mHelper = new GsmCallTrackerHelper(phone.getContext(), this);
        /* M: call control part end */
    }

    public void dispose() {
        Rlog.d(LOG_TAG, "GsmCallTracker dispose");
        //Unregister for all events
        mCi.unregisterForCallStateChanged(this);
        mCi.unregisterForOn(this);
        mCi.unregisterForNotAvailable(this);
//MTK_TC1_FEATURE for LGE CSMCC_MO_CALL_MODIFIED {         
if (SystemProperties.get("ro.mtk_tc1_feature").equals("1")) { 
       	mCi.unregisterForMoCallStateChanged(this);
}
//}
        /* M: call control part start */
        mCi.unregisterForOffOrNotAvailable(this);
        mCi.unsetOnIncomingCallIndication(this);
        mCi.unSetCnapNotify(this);
        mCi.unSetOnSpeechCodecInfo(this);
        /* M: call control part end */

        clearDisconnected();
    }

    @Override
    protected void finalize() {
        Rlog.d(LOG_TAG, "GsmCallTracker finalized");
    }

    //***** Instance Methods

    //***** Public Methods
    @Override
    public void registerForVoiceCallStarted(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mVoiceCallStartedRegistrants.add(r);
    }

    @Override
    public void unregisterForVoiceCallStarted(Handler h) {
        mVoiceCallStartedRegistrants.remove(h);
    }

    /* M: call control part start */
    public void registerForVoiceCallIncomingIndication(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mVoiceCallIncomingIndicationRegistrants.add(r);
    }

    public void unregisterForVoiceCallIncomingIndication(Handler h) {
        mVoiceCallIncomingIndicationRegistrants.remove(h);
    }
    /* M: call control part end */

    @Override
    public void registerForVoiceCallEnded(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mVoiceCallEndedRegistrants.add(r);
    }

    @Override
    public void unregisterForVoiceCallEnded(Handler h) {
        mVoiceCallEndedRegistrants.remove(h);
    }

    private void
    fakeHoldForegroundBeforeDial() {
        List<Connection> connCopy;

        // We need to make a copy here, since fakeHoldBeforeDial()
        // modifies the lists, and we don't want to reverse the order
        connCopy = (List<Connection>) mForegroundCall.mConnections.clone();

        for (int i = 0, s = connCopy.size() ; i < s ; i++) {
            GsmConnection conn = (GsmConnection)connCopy.get(i);

            conn.fakeHoldBeforeDial();
        }
    }

    /* M: call control part start */
    private void resumeBackgroundAfterDialFailed() {
        // We need to make a copy here, since fakeHoldBeforeDial()
        // modifies the lists, and we don't want to reverse the order
        List<Connection> connCopy = (List<Connection>) mBackgroundCall.mConnections.clone();

        for (int i = 0, s = connCopy.size() ; i < s ; i++) {
            GsmConnection conn = (GsmConnection) connCopy.get(i);

            conn.resumeHoldAfterDialFailed();
        }
    }
    /* M: call control part end */

    /**
     * clirMode is one of the CLIR_ constants
     */
    synchronized Connection
    dial (String dialString, int clirMode, UUSInfo uusInfo) throws CallStateException {
        // note that this triggers call state changed notif
        clearDisconnected();

        if (!canDial()) {
            throw new CallStateException("cannot dial in current state");
        }

        String origNumber = dialString;
        dialString = convertNumberIfNecessary(mPhone, dialString);

        // The new call must be assigned to the foreground call.
        // That call must be idle, so place anything that's
        // there on hold
        if (mForegroundCall.getState() == GsmCall.State.ACTIVE) {
            // this will probably be done by the radio anyway
            // but the dial might fail before this happens
            // and we need to make sure the foreground call is clear
            // for the newly dialed connection

            /* M: call control part start */
            mWaitingForHoldRequest.set();
            /* M: call control part end */

            switchWaitingOrHoldingAndActive();

            // Fake local state so that
            // a) foregroundCall is empty for the newly dialed connection
            // b) hasNonHangupStateChanged remains false in the
            // next poll, so that we don't clear a failed dialing call
            fakeHoldForegroundBeforeDial();
        }

        if (mForegroundCall.getState() != GsmCall.State.IDLE) {
            //we should have failed in !canDial() above before we get here
            throw new CallStateException("cannot dial in current state");
        }

        mPendingMO = new GsmConnection(mPhone.getContext(), checkForTestEmergencyNumber(dialString),
                this, mForegroundCall);
        mHangupPendingMO = false;

        if ( mPendingMO.getAddress() == null || mPendingMO.getAddress().length() == 0
                || mPendingMO.getAddress().indexOf(PhoneNumberUtils.WILD) >= 0
        ) {
            // Phone number is invalid
            mPendingMO.mCause = DisconnectCause.INVALID_NUMBER;

            /* M: call control part start */
            mWaitingForHoldRequest.reset();
            /* M: call control part end */

            // handlePollCalls() will notice this call not present
            // and will mark it as dropped.
            pollCallsWhenSafe();
        } else {
            // Always unmute when initiating a new call
            setMute(false);

            /* M: call control part start */
            if (PhoneNumberUtils.isEmergencyNumber(dialString)
                && !PhoneNumberUtils.isSpecialEmergencyNumber(dialString)) {
                int serviceCategory = PhoneNumberUtils.getServiceCategoryFromEcc(dialString);
                mCi.setEccServiceCategory(serviceCategory);
                mCi.emergencyDial(mPendingMO.getAddress(), clirMode, uusInfo, obtainCompleteMessage(EVENT_DIAL_CALL_RESULT));
            } else {
                if (!mWaitingForHoldRequest.isWaiting()) {
                    mCi.dial(mPendingMO.getAddress(), clirMode, uusInfo, obtainCompleteMessage(EVENT_DIAL_CALL_RESULT));
                } else {
                    mWaitingForHoldRequest.set(mPendingMO.getAddress(), clirMode, uusInfo);
                }
            }
            /* M: call control part end */
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
    dial(String dialString) throws CallStateException {
        return dial(dialString, CommandsInterface.CLIR_DEFAULT, null);
    }

    Connection
    dial(String dialString, UUSInfo uusInfo) throws CallStateException {
        return dial(dialString, CommandsInterface.CLIR_DEFAULT, uusInfo);
    }

    Connection
    dial(String dialString, int clirMode) throws CallStateException {
        return dial(dialString, clirMode, null);
    }

    void
    acceptCall () throws CallStateException {
        // FIXME if SWITCH fails, should retry with ANSWER
        // in case the active/holding call disappeared and this
        // is no longer call waiting

        if (mRingingCall.getState() == GsmCall.State.INCOMING) {
            Rlog.i("phone", "acceptCall: incoming...");
            // Always unmute when answering a new call
            setMute(false);
            mCi.acceptCall(obtainCompleteMessage());
        } else if (mRingingCall.getState() == GsmCall.State.WAITING) {
            setMute(false);
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
        if (mRingingCall.getState() == GsmCall.State.INCOMING) {
            throw new CallStateException("cannot be in the incoming state");
        } else {
            /* M: call control part start */
            if (!hasPendingSwapRequest) {
                mCi.switchWaitingOrHoldingAndActive(
                        obtainCompleteMessage(EVENT_SWITCH_RESULT));
                hasPendingSwapRequest = true;
            }
            /* M: call control part end */
        }
    }

    void
    conference() {
        mCi.conference(obtainCompleteMessage(EVENT_CONFERENCE_RESULT));
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
        return mForegroundCall.getState() == GsmCall.State.ACTIVE
                && mBackgroundCall.getState() == GsmCall.State.HOLDING
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
                    || !mBackgroundCall.getState().isAlive());

        return ret;
    }

    boolean
    canTransfer() {
        return (mForegroundCall.getState() == GsmCall.State.ACTIVE
                || mForegroundCall.getState() == GsmCall.State.ALERTING
                || mForegroundCall.getState() == GsmCall.State.DIALING)
            && mBackgroundCall.getState() == GsmCall.State.HOLDING;
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
    // M: call control change type from private to protected.
    protected Message
    obtainCompleteMessage() {
        return obtainCompleteMessage(EVENT_OPERATION_COMPLETE);
    }

    /**
     * Obtain a message to use for signalling "invoke getCurrentCalls() when
     * this operation and all other pending operations are complete
     */
    // M: call control change type from private to protected.
    protected Message
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
            Rlog.e(LOG_TAG,"GsmCallTracker.pendingOperations < 0");
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
        log("updatePhoneState: old: " + oldState + " , new: " + mState);
        if (mState != oldState) {
            mPhone.notifyPhoneStateChanged();
        }
    }

    @Override
    protected synchronized void
    handlePollCalls(AsyncResult ar) {
        /* M: call control part start */
        boolean localHangup = false;
        /* M: call control part end */
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
            GsmConnection conn = mConnections[i];
            DriverCall dc = null;

            // polledCall list is sparse
            if (curDC < dcSize) {
                dc = (DriverCall) polledCalls.get(curDC);

                if (dc.index == i+1) {
                    curDC++;
                } else {
                    dc = null;
                }
            }

            if (DBG_POLL) log("poll: conn[i=" + i + "]=" +
                    conn+", dc=" + dc);

            if (conn == null && dc != null) {
                /* M: call control part start */
                if (DBG_POLL) log("case 1 : new Call appear");

                // give CLIP ALLOW default value, it will be changed on others URC
                dc.numberPresentation = PhoneConstants.PRESENTATION_ALLOWED;
                /* M: call control part end */

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
                /* M: call control part start */
                } else if (mPendingMO != null && !mPendingMO.compareTo(dc)) {

                    log("MO/MT conflict! Hang up MT call to prevent abnormal behavior.");

                    /* MO call failed due to MT call is incoming, also hang up MT call to prevent abnormal behavior. */
                    mConnections[i] = new GsmConnection(mPhone.getContext(), dc, this, i);
                    try {
                        hangup(mConnections[i]);
                    } catch (CallStateException ex) {
                        Rlog.e(LOG_TAG, "unexpected error on hangup");
                    }
					
                    //To clear forwarding address if needed
                    mHelper.clearForwardingAddressVariables(i);
                /* M: call control part end */
                } else {
                    mConnections[i] = new GsmConnection(mPhone.getContext(), dc, this, i);

                    /* M: call control part start */
                    if (mPendingCnap != null) {
                        mConnections[i].setCnapName(mPendingCnap);
                        mPendingCnap = null;
                    }

                    boolean checkFlag = mHelper.DmCheckIfCallCanComing(mConnections[i]);
                    /* M: call control part end */

                    /* M: call control part start */
                    //To store forwarding address to connection object.
                    mHelper.setForwardingAddressToConnection(i, mConnections[i]);					
                    /* M: call control part end */

                    // it's a ringing call
                    if (checkFlag && mConnections[i].getCall() == mRingingCall) {
                        newRinging = mConnections[i];
                    } else if (checkFlag && mHandoverConnection != null) {
                        // Single Radio Voice Call Continuity (SRVCC) completed
                        mPhone.migrateFrom((PhoneBase) mPhone.getImsPhone());
                        mConnections[i].migrateFrom(mHandoverConnection);
                        mPhone.notifyHandoverStateChanged(mConnections[i]);
                        mHandoverConnection = null;
                    } else if (checkFlag) {
                        // Something strange happened: a call appeared
                        // which is neither a ringing call or one we created.
                        // Either we've crashed and re-attached to an existing
                        // call, or something else (eg, SIM) initiated the call.

                        Rlog.i(LOG_TAG,"Phantom call appeared " + dc);

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

                        newUnknown = mConnections[i];

                        unknownConnectionAppeared = true;
                    }
                }
                hasNonHangupStateChanged = true;
            } else if (conn != null && dc == null) {

                /* M: call control part start */
                if (DBG_POLL) log("case 2 : old Call disappear");

                if (((conn.getCall() == mForegroundCall && mForegroundCall.mConnections.size() == 1 && mBackgroundCall.isIdle()) ||
                     (conn.getCall() == mBackgroundCall && mBackgroundCall.mConnections.size() == 1 && mForegroundCall.isIdle())) &&
                    mRingingCall.getState() == GsmCall.State.WAITING)
                    mRingingCall.mState = GsmCall.State.INCOMING;
                /* M: call control part end */

                   // Connection missing in CLCC response that we were
                   // tracking.
                   mDroppedDuringPoll.add(conn);
                   // Dropped connections are removed from the CallTracker
                   // list but kept in the GsmCall list
                   mConnections[i] = null;

                   /* M: call control part start */
                   mHelper.CallIndicationEnd();
                   /* M: call control part end */

                /* M: call control part start */
                //To clear forwarding address if needed
                mHelper.clearForwardingAddressVariables(i);
                /* M: call control part end */
            } else if (conn != null && dc != null && !conn.compareTo(dc)) {

                /* M: call control part start */
                if (DBG_POLL) log("case 3 : old Call replaced");
                /* M: call control part end */

                // Connection in CLCC response does not match what
                // we were tracking. Assume dropped call and new call

                mDroppedDuringPoll.add(conn);

                /* M: call control part start */
                // give CLIP ALLOW default value, it will be changed on others URC
                dc.numberPresentation = PhoneConstants.PRESENTATION_ALLOWED;
                /* M: call control part end */

                mConnections[i] = new GsmConnection (mPhone.getContext(), dc, this, i);

                if (mConnections[i].getCall() == mRingingCall) {
                    newRinging = mConnections[i];
                } // else something strange happened
                hasNonHangupStateChanged = true;
            } else if (conn != null && dc != null) { /* implicit conn.compareTo(dc) */

                /* M: call control part start */
                if (DBG_POLL) log("case 4 : old Call update");

                // dc's CLIP value should use conn's, because it may has been updated
                dc.numberPresentation = conn.getNumberPresentation();
                /* M: call control part end */

                boolean changed;
                changed = conn.update(dc);
                hasNonHangupStateChanged = hasNonHangupStateChanged || changed;
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
        }

        if (newRinging != null) {
            if (DBG_POLL) log("notifyNewRingingConnection");
            mPhone.notifyNewRingingConnection(newRinging);
        }

        // clear the "local hangup" and "missed/rejected call"
        // cases from the "dropped during poll" list
        // These cases need no "last call fail" reason
        log("dropped during poll size = " + mDroppedDuringPoll.size());
        for (int i = mDroppedDuringPoll.size() - 1; i >= 0 ; i--) {
            GsmConnection conn = mDroppedDuringPoll.get(i);

            /* M: call control part start */
            // If connection is disconnected due to Radio Off, set as DisconnectCause.LOCAL
            if (isCommandExceptionRadioNotAvailable(ar.exception)) {
                conn.onHangupLocal();
            }
            /* M: call control part end */

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
                /* M: call control part start */
                localHangup = true;
                /* M: call control part end */
            } else if (conn.mCause == DisconnectCause.LOCAL
                    || conn.mCause == DisconnectCause.INVALID_NUMBER) {

                log("local hangup or invalid number");
                mDroppedDuringPoll.remove(i);
                hasAnyCallDisconnected |= conn.onDisconnect(conn.mCause);
                /* M: call control part start */
                localHangup = true;
                /* M: call control part end */
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
            if (DBG_POLL) log("notifyUnknownConnection");
            mPhone.notifyUnknownConnection(newUnknown);
        }

        if ((hasNonHangupStateChanged || newRinging != null || hasAnyCallDisconnected)
            /* M: call control part start */
            && !hasPendingSwapRequest) {
            /* M: call control part end */
            if (DBG_POLL) log("notifyPreciseCallStateChanged");
            mPhone.notifyPreciseCallStateChanged();
        }

        /* M: call control part start */
        /* Solve ALPS00401290 */
        if ((mHelper.getCurrentTotalConnections() == 1) &&
            (mRingingCall.getState() == GsmCall.State.WAITING)) {
           mRingingCall.mState = GsmCall.State.INCOMING;
        }
        /* M: call control part end */

        dumpState();
    }

    private void
    handleRadioNotAvailable() {
        // handlePollCalls will clear out its
        // call list when it gets the CommandException
        // error result from this
        pollCallsWhenSafe();
    }

    private void
    dumpState() {
        List l;

        Rlog.i(LOG_TAG,"Phone State:" + mState);

        Rlog.i(LOG_TAG,"Ringing call: " + mRingingCall.toString());

        l = mRingingCall.getConnections();
        for (int i = 0, s = l.size(); i < s; i++) {
            Rlog.i(LOG_TAG,l.get(i).toString());
        }

        Rlog.i(LOG_TAG,"Foreground call: " + mForegroundCall.toString());

        l = mForegroundCall.getConnections();
        for (int i = 0, s = l.size(); i < s; i++) {
            Rlog.i(LOG_TAG,l.get(i).toString());
        }

        Rlog.i(LOG_TAG,"Background call: " + mBackgroundCall.toString());

        l = mBackgroundCall.getConnections();
        for (int i = 0, s = l.size(); i < s; i++) {
            Rlog.i(LOG_TAG,l.get(i).toString());
        }

        /* M: call control part start */
        mHelper.LogState();
        /* M: call control part end */
    }

    //***** Called from GsmConnection

    /*package*/ void
    hangup (GsmConnection conn) throws CallStateException {
        if (conn.mOwner != this) {
            throw new CallStateException ("GsmConnection " + conn
                                    + "does not belong to GsmCallTracker " + this);
        }

        if (conn == mPendingMO) {
            // We're hanging up an outgoing call that doesn't have it's
            // GSM index assigned yet

            if (Phone.DEBUG_PHONE) log("hangup: set hangupPendingMO to true");
            mHangupPendingMO = true;
            /* M: call control part start */
            mHelper.PendingHangupRequestReset();
            /* M: call control part end */
        } else {
            try {
                /* M: call control part start */
                mCi.hangupConnection(conn.getGSMIndex(), obtainCompleteMessage(EVENT_HANG_UP_RESULT));
                /* M: call control part end */
            } catch (CallStateException ex) {
                // Ignore "connection not found"
                // Call may have hung up already
                /* M: call control part start */
                mHelper.PendingHangupRequestReset();
                /* M: call control part end */
                Rlog.w(LOG_TAG,"GsmCallTracker WARN: hangup() on absent connection "
                                + conn);
            }
        }

        conn.onHangupLocal();
    }

    /*package*/ void
    separate (GsmConnection conn) throws CallStateException {
        if (conn.mOwner != this) {
            throw new CallStateException ("GsmConnection " + conn
                                    + "does not belong to GsmCallTracker " + this);
        }
        try {
            mCi.separateConnection (conn.getGSMIndex(),
                obtainCompleteMessage(EVENT_SEPARATE_RESULT));
        } catch (CallStateException ex) {
            // Ignore "connection not found"
            // Call may have hung up already
            Rlog.w(LOG_TAG,"GsmCallTracker WARN: separate() on absent connection "
                          + conn);
        }
    }

    //***** Called from GSMPhone

    /*package*/ void
    setMute(boolean mute) {
        mDesiredMute = mute;
        mCi.setMute(mDesiredMute, null);
    }

    /*package*/ boolean
    getMute() {
        return mDesiredMute;
    }


    //***** Called from GsmCall

    /* package */ void
    hangup (GsmCall call) throws CallStateException {
        if (call.getConnections().size() == 0) {
            throw new CallStateException("no connections in call");
        }

        /* M: call control part start */
        if (mHelper.hasPendingHangupRequest()) {
            Rlog.d(LOG_TAG, "hangup(GsmCall) hasPendingHangupRequest = true");
            if (mHelper.ForceReleaseAllConnection(call)) {
                return;
            }
        }
        /* M: call control part end */

        if (call == mRingingCall) {
            /* M: call control part start */
            mHelper.PendingHangupRequestInc();
            if (Phone.DEBUG_PHONE) log("(ringing) hangup waiting or background");
            hangup((GsmConnection) (call.getConnections().get(0)));
            /* M: call control part end */
        } else if (call == mForegroundCall) {
            /* M: call control part start */
            mHelper.PendingHangupRequestInc();
            /* M: call control part end */
            if (call.isDialingOrAlerting()) {
                if (Phone.DEBUG_PHONE) {
                    log("(foregnd) hangup dialing or alerting...");
                }
                hangup((GsmConnection)(call.getConnections().get(0)));
            } else if (mRingingCall.isRinging()) {
                // Do not auto-answer ringing on CHUP, instead just end active calls
                log("hangup all conns in active/background call, without affecting ringing call");
                hangupAllConnections(call);
            } else {
                /* M: call control part start */
                if (Phone.DEBUG_PHONE) log("(foregnd) hangup active");

                /* For solving [ALPS01431282][ALPS.KK1.MP2.V2.4 Regression Test][Case Fail][Call] Can not end the ECC call when enable SIM PIN lock. */
                GsmConnection cn = (GsmConnection) call.getConnections().get(0);
                String address = cn.getAddress();
                if (PhoneNumberUtils.isEmergencyNumber(address) && !PhoneNumberUtils.isSpecialEmergencyNumber(address)) {
                   log("(foregnd) hangup active Emergency call by connection index");
                   hangup((GsmConnection) (call.getConnections().get(0)));
                } else {
                /* M: call control part end */
                   hangupForegroundResumeBackground();
                }
            }
        } else if (call == mBackgroundCall) {
            if (mRingingCall.isRinging()) {
                if (Phone.DEBUG_PHONE) {
                    log("hangup all conns in background call");
                }
                hangupAllConnections(call);
            } else {
                /* M: call control part start */
                mHelper.PendingHangupRequestInc();
                if (Phone.DEBUG_PHONE) log("(backgnd) hangup waiting/background");
                /* M: call control part end */
                hangupWaitingOrBackground();
            }
        } else {
            throw new RuntimeException ("GsmCall " + call +
                    "does not belong to GsmCallTracker " + this);
        }

        call.onHangupLocal();
        mPhone.notifyPreciseCallStateChanged();
    }

    /* package */
    void hangupWaitingOrBackground() {
        if (Phone.DEBUG_PHONE) log("hangupWaitingOrBackground");
        /* M: call control part start */
        mCi.hangupWaitingOrBackground(obtainCompleteMessage(EVENT_HANG_UP_RESULT));
        /* M: call control part end */
    }

    /* package */
    void hangupForegroundResumeBackground() {
        if (Phone.DEBUG_PHONE) log("hangupForegroundResumeBackground");
        /* M: call control part start */
        mCi.hangupForegroundResumeBackground(obtainCompleteMessage(EVENT_HANG_UP_RESULT));
        /* M: call control part end */
    }

    void hangupConnectionByIndex(GsmCall call, int index)
            throws CallStateException {
        int count = call.mConnections.size();
        for (int i = 0; i < count; i++) {
            GsmConnection cn = (GsmConnection)call.mConnections.get(i);
            if (cn.getGSMIndex() == index) {
                mCi.hangupConnection(index, obtainCompleteMessage());
                return;
            }
        }
        /* M: call control part start */
        if (mHelper.hangupBgConnectionByIndex(index))
            return;
        if (mHelper.hangupRingingConnectionByIndex(index))
            return;
        /* M: call control part end */

        throw new CallStateException("no gsm index found");
    }

    void hangupAllConnections(GsmCall call) {
        try {
            int count = call.mConnections.size();
            for (int i = 0; i < count; i++) {
                GsmConnection cn = (GsmConnection)call.mConnections.get(i);
                mCi.hangupConnection(cn.getGSMIndex(), obtainCompleteMessage());
            }
        } catch (CallStateException ex) {
            Rlog.e(LOG_TAG, "hangupConnectionByIndex caught " + ex);
        }
    }

    /* package */
    GsmConnection getConnectionByIndex(GsmCall call, int index)
            throws CallStateException {
        int count = call.mConnections.size();
        for (int i = 0; i < count; i++) {
            GsmConnection cn = (GsmConnection)call.mConnections.get(i);
            if (cn.getGSMIndex() == index) {
                return cn;
            }
        }

        return null;
    }

    private Phone.SuppService getFailedService(int what) {
        switch (what) {
            case EVENT_SWITCH_RESULT:
                return Phone.SuppService.SWITCH;
            case EVENT_CONFERENCE_RESULT:
                return Phone.SuppService.CONFERENCE;
            case EVENT_SEPARATE_RESULT:
                return Phone.SuppService.SEPARATE;
            case EVENT_ECT_RESULT:
                return Phone.SuppService.TRANSFER;
        }
        return Phone.SuppService.UNKNOWN;
    }

    //****** Overridden from Handler

    @Override
    public void
    handleMessage (Message msg) {
        AsyncResult ar;

        if (!mPhone.mIsTheCurrentActivePhone) {
            Rlog.e(LOG_TAG, "Received message " + msg +
                    "[" + msg.what + "] while being destroyed. Ignoring.");
            return;
        }
        /* M: call control part start */
        mHelper.LogerMessage(msg.what);
        /* M: call control part end */

        switch (msg.what) {
            case EVENT_POLL_CALLS_RESULT:
                ar = (AsyncResult)msg.obj;

                if (msg == mLastRelevantPoll) {
                    if (DBG_POLL) log(
                            "handle EVENT_POLL_CALLS_RESULT: set needsPoll=F");
                    mNeedsPoll = false;
                    mLastRelevantPoll = null;
                    handlePollCalls((AsyncResult)msg.obj);
                }
            break;

            case EVENT_OPERATION_COMPLETE:
                ar = (AsyncResult)msg.obj;
                operationComplete();
            break;

            case EVENT_SWITCH_RESULT:
                /* M: call control part start */
                ar = (AsyncResult) msg.obj;
                if (ar.exception != null) {
                    if (mWaitingForHoldRequest.isWaiting()) {

                        mPendingMO.mCause = DisconnectCause.LOCAL;
                        mPendingMO.onDisconnect(DisconnectCause.LOCAL);
                        mPendingMO = null;
                        mHangupPendingMO = false;
                        updatePhoneState();

                        resumeBackgroundAfterDialFailed();
                        mWaitingForHoldRequest.reset();
                    }

                    mPhone.notifySuppServiceFailed(getFailedService(msg.what));
                } else {
                    if (mWaitingForHoldRequest.isWaiting()) {
                        Rlog.i(LOG_TAG, "Switch success, and then dial");
                        mWaitingForHoldRequest.handleOperation();
                    }
                }
                hasPendingSwapRequest = false;
                mPhone.notifyPreciseCallStateChanged();
                operationComplete();
            break;
            /* M: call control part end */
            case EVENT_CONFERENCE_RESULT:
            case EVENT_SEPARATE_RESULT:
            case EVENT_ECT_RESULT:
                ar = (AsyncResult)msg.obj;
                if (ar.exception != null) {
                    /* M: call control part start */
                    mHelper.PendingHangupRequestUpdate();
                    /* M: call control part end */
                    mPhone.notifySuppServiceFailed(getFailedService(msg.what));
                }
                operationComplete();
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
                // Log the causeCode if its not normal
                if (causeCode == CallFailCause.NO_CIRCUIT_AVAIL ||
                    causeCode == CallFailCause.TEMPORARY_FAILURE ||
                    causeCode == CallFailCause.SWITCHING_CONGESTION ||
                    causeCode == CallFailCause.CHANNEL_NOT_AVAIL ||
                    causeCode == CallFailCause.QOS_NOT_AVAIL ||
                    causeCode == CallFailCause.BEARER_NOT_AVAIL ||
                    causeCode == CallFailCause.ERROR_UNSPECIFIED) {
                    GsmCellLocation loc = ((GsmCellLocation)mPhone.getCellLocation());
                    EventLog.writeEvent(EventLogTags.CALL_DROP,
                            causeCode, loc != null ? loc.getCid() : -1,
                            /* M: call control part start */
                            TelephonyManager.getDefault().getNetworkType(mPhone.getSubId()));
                            /* M: call control part end */
                }

                for (int i = 0, s =  mDroppedDuringPoll.size()
                        ; i < s ; i++
                ) {
                    GsmConnection conn = mDroppedDuringPoll.get(i);

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

//MTK_TC1_FEATURE for LGE CSMCC_MO_CALL_MODIFIED { 
            case EVENT_MO_CALL_STATE_CHANGE:
if (SystemProperties.get("ro.mtk_tc1_feature").equals("1")) { 
             /**
               * Receives call mode and pau information, store to connection object and notify APP
               * +ECPI:<call_id>, <msg_type>, <is_ibt>, <is_tch>, <dir>, <call_mode>[, <number>, <toa>], "pau"
             */
                ar = (AsyncResult)msg.obj;      
                String[] CallInfo = (String[]) ar.result;
				//Test
                int call_id = Integer.parseInt(CallInfo[0]);
                int Msg_type = Integer.parseInt(CallInfo[1]);
               
                Rlog.i(LOG_TAG,"CallInfo[0]:"+call_id+"CallInfo[1]:"+Msg_type);
              	if(Msg_type==135)
                {
                //Implement here
                Rlog.e(LOG_TAG, "Received CSMCC_MO_CALL_MODIFIED_BY_SAT");
                }
}
            break;
//}
            
            case EVENT_RADIO_AVAILABLE:
                handleRadioAvailable();
            break;

            case EVENT_RADIO_NOT_AVAILABLE:
                handleRadioNotAvailable();
            break;

            /* M: call control part start */
            case EVENT_HANG_UP_RESULT:
                mHelper.PendingHangupRequestDec();
                operationComplete();
            break;

            case EVENT_RADIO_OFF_OR_NOT_AVAILABLE:
                handleRadioNotAvailable();
            break;

            case EVENT_DIAL_CALL_RESULT:
                ar = (AsyncResult) msg.obj;
                if (ar.exception != null) {
                    log("dial call failed!!");
                    mHelper.PendingHangupRequestUpdate();
                }
                operationComplete();
            break;

            case EVENT_INCOMING_CALL_INDICATION:
                mHelper.CallIndicationProcess((AsyncResult) msg.obj);
            break;

            case EVENT_CNAP_INDICATION:
                    ar = (AsyncResult) msg.obj;

                    String[] cnapResult = (String[]) ar.result;

                    log("handle EVENT_CNAP_INDICATION : " + cnapResult[0] + ", " + cnapResult[1]);

                    log("ringingCall.isIdle() : " + mRingingCall.isIdle());

                    if (!mRingingCall.isIdle()) {
                        GsmConnection cn = (GsmConnection) mRingingCall.mConnections.get(0);

                        cn.setCnapName(cnapResult[0]);
                    } else {  // queue the CNAP
                        mPendingCnap = new String(cnapResult[0]);
                    }
            break;
            case EVENT_SPEECH_CODEC_INFO:
                /* FIXME: If any suppression is needed */		
                ar = (AsyncResult) msg.obj;
                mSpeechCodecType = ((int[]) ar.result)[0];
                log("handle EVENT_SPEECH_CODEC_INFO : " + mSpeechCodecType);
                mPhone.notifySpeechCodecInfo(mSpeechCodecType);
            break;
            /* M: call control part end */

            default:
                break;
        }
    }

    @Override
    protected void log(String msg) {
        Rlog.d(LOG_TAG, "[GsmCallTracker][Phone" + (mPhone.getPhoneId()) + "] " + msg);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("GsmCallTracker extends:");
        super.dump(fd, pw, args);
        pw.println("mConnections: length=" + mConnections.length);
        for(int i=0; i < mConnections.length; i++) {
            pw.printf("  mConnections[%d]=%s\n", i, mConnections[i]);
        }
        pw.println(" mVoiceCallEndedRegistrants=" + mVoiceCallEndedRegistrants);
        pw.println(" mVoiceCallStartedRegistrants=" + mVoiceCallStartedRegistrants);
        pw.println(" mDroppedDuringPoll: size=" + mDroppedDuringPoll.size());
        for(int i = 0; i < mDroppedDuringPoll.size(); i++) {
            pw.printf( "  mDroppedDuringPoll[%d]=%s\n", i, mDroppedDuringPoll.get(i));
        }
        pw.println(" mRingingCall=" + mRingingCall);
        pw.println(" mForegroundCall=" + mForegroundCall);
        pw.println(" mBackgroundCall=" + mBackgroundCall);
        pw.println(" mPendingMO=" + mPendingMO);
        pw.println(" mHangupPendingMO=" + mHangupPendingMO);
        pw.println(" mPhone=" + mPhone);
        pw.println(" mDesiredMute=" + mDesiredMute);
        pw.println(" mState=" + mState);
    }

    /* M: call control part start */
    /**
     * clirMode is one of the CLIR_ constants
     */
    /* [ALPS00475147] Add by mtk01411 to provide disc only ringingCall with specific cause instead of INCOMMING_REJECTED */
    /* package */ void
    hangup(GsmCall call, int discRingingCallCause) throws CallStateException {
        /// M: [mtk04070][111118][ALPS00093395]MTK modified. @{
        if (call.getConnections().size() == 0) {
            throw new CallStateException("no connections in call");
        }

        if (mHelper.hasPendingHangupRequest()) {
            Rlog.d(LOG_TAG, "hangup(GsmCall) hasPendingHangupRequest = true");
            if (mHelper.ForceReleaseNotRingingConnection(call)) {
                return;
            }
        }

        if (call == mRingingCall) {
            if (Phone.DEBUG_PHONE) log("(ringing) hangup waiting or background");
            /* Solve [ALPS00303482][SIMCOM][MT6575][Acer_C8][GCF][51.010-1][26.8.1.3.5.3], mtk04070, 20120628 */
            log("Hang up waiting or background call by connection index.");
            GsmConnection conn = (GsmConnection) (call.getConnections().get(0));
            mCi.hangupConnection(conn.getGSMIndex(), obtainCompleteMessage());

        } else {
            throw new RuntimeException("GsmCall " + call +
                    "does not belong to GsmCallTracker " + this);
        }

        call.onHangupLocal();
        /* Add by mtk01411: Change call's state as DISCONNECTING in call.onHangupLocal()
               *  --> cn.onHangupLocal(): set cn's cause as DisconnectionCause.LOCAL
         */
        if (call == mRingingCall) {
            GsmConnection ringingConn = (GsmConnection) (call.getConnections().get(0));
            ringingConn.mCause = discRingingCallCause;
        }
        mPhone.notifyPreciseCallStateChanged();
        /// @}
    }

    void hangupAll() {
        if (Phone.DEBUG_PHONE) log("hangupAll");
        mCi.hangupAll(obtainCompleteMessage());

        if (!mRingingCall.isIdle()) {
            mRingingCall.onHangupLocal();
        }
        if (!mForegroundCall.isIdle()) {
            mForegroundCall.onHangupLocal();
        }
        if (!mBackgroundCall.isIdle()) {
            mBackgroundCall.onHangupLocal();
        }
    }

    public void setIncomingCallIndicationResponse(boolean accept) {
        mHelper.CallIndicationResponse(accept);
    }

    /**
     * To know if the incoming call is rejected by Mobile Manager Service.
     * @return Return true if it is rejected by Moms, else return false.
     */
    public boolean isRejectedByMoms() {
       return mHelper.MobileManagermentGetIsBlocking();
    }
    /* M: call control part end */
}
