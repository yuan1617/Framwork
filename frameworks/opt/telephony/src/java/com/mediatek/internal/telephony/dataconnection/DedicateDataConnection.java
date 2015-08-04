package com.mediatek.internal.telephony.dataconnection;

import android.os.Message;
import android.os.Handler;
import android.os.AsyncResult;
import android.util.Log;

import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.internal.util.AsyncChannel;

import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.CommandException;
import com.android.internal.telephony.dataconnection.DataConnection;
import com.android.internal.telephony.dataconnection.DcAsyncChannel;
import com.android.internal.telephony.dataconnection.DcTracker;
import com.android.internal.telephony.dataconnection.DcFailCause;

import com.mediatek.internal.telephony.DedicateDataCallState;
import com.mediatek.internal.telephony.DedicateDataCallState.SetupResult;
import com.mediatek.internal.telephony.DedicateBearerProperties;


public class DedicateDataConnection extends StateMachine{
    protected static final String TAG = "GSM";
    private static final int EVENT_CONNECT = 500;
    private static final int EVENT_DISCONNECT = 501;
    private static final int EVENT_MODIFY = 502;
    private static final int EVENT_UPDATE = 503;
    private static final int EVENT_DISCONNECT_WITHOUT_NOTIFICATION = 504;
    private static final int EVENT_SETUP_DATA_CONNECTION_DONE = 505;
    private static final int EVENT_DISCONNECT_DATA_CONNECTION_DONE = 506;
    private static final int EVENT_MODIFY_DATA_CONNECTION_DONE = 507;

    public static final String REASON_BEARER_ACTIVATION = "activation";
    public static final String REASON_BEARER_DEACTIVATION = "deactivation";
    public static final String REASON_BEARER_MODIFICATION = "modification";
    public static final String REASON_BEARER_ABORT = "abort";

    private int mId; //the ID of the DedicateDataConnection
    private int mCid = -1; //CID which is mapped to modem context;
    private DataConnection mDc; //the default bearer that the dedicate bearer associated to
    private DedicateDataConnectionAc mDdcac;
    private DedicateBearerProperties mProperties = new DedicateBearerProperties();

    private AsyncChannel mAc;
    private PhoneBase mPhone;
    private String mReason;

    private DefaultState mDefaultState = new DefaultState();
    private InactiveState mInactiveState = new InactiveState();
    private ActivatingState mActivatingState = new ActivatingState();
    private ActiveState mActiveState = new ActiveState();
    private DisconnectingState mDisconnectingState = new DisconnectingState();

    public DedicateDataConnection(PhoneBase phone, int id) {
        super("DDC-"+id);
        mPhone = phone;
        mId = id;
        addState(mDefaultState);
        addState(mInactiveState, mDefaultState);
        addState(mActivatingState, mDefaultState);
        addState(mActiveState, mDefaultState);
        addState(mDisconnectingState, mDefaultState);
        setInitialState(mInactiveState);
    }

    public void bringUp(DcTracker.EnableDedicateBearerParam param, Message onCompletedMsg) {
        sendMessage(obtainMessage(EVENT_CONNECT, new DedicateConnectionParam(param, onCompletedMsg)));
    }

    //framework triggered modification
    public void modify(DcTracker.EnableDedicateBearerParam param, Message onCompletedMsg) {
        sendMessage(obtainMessage(EVENT_MODIFY, new DedicateConnectionParam(param, onCompletedMsg)));
    }

    public void tearDown(String reason, Message onCompletedMsg) {
        sendMessage(obtainMessage(EVENT_DISCONNECT, new DedicateConnectionParam(reason, onCompletedMsg)));
    }

    //update by network
    public void update(DedicateDataCallState dedicateDataCallState, Message onCompletedMsg) {
        sendMessage(obtainMessage(EVENT_UPDATE, new DedicateConnectionParam(dedicateDataCallState, onCompletedMsg)));
    }

    //switch to inactive state, NO deactivation AT cmd will be sent
    public void disconnect(Message onCompletedMsg) {
        ddclog("DedicateDataConnection disconnect [ddcid=" + mId + ", cid=" + mCid + "]");
        sendMessage(obtainMessage(EVENT_DISCONNECT, onCompletedMsg));
    }

    public void setDataConnection(DataConnection dc) {
        mDc = dc;
    }

    public void setDedicateDataConnectionAc(DedicateDataConnectionAc ddcac) {
        mDdcac = ddcac;
    }

    public DedicateDataConnectionAc getDedicateDataConnectionAc() {
        return mDdcac;
    }

    public DataConnection getDataConnection() {
        return mDc;
    }

    public int getId() {
        return mId;
    }

    protected void onConnect(DedicateConnectionParam dp) {
        ddclog("DedicateDataConnection onConnect [" + mId + "]");
        Message msg = obtainMessage(EVENT_SETUP_DATA_CONNECTION_DONE, dp);
        msg.obj = dp;
        // TODO: need to add this code later for L Migration VOLTE
        //mPhone.mCi.setupDedicateDataCall(mId, dp.param.interfaceId, dp.param.signalingFlag, dp.param.qosStatus, dp.param.tftStatus, msg);
    }

    protected void onModification(DedicateConnectionParam dp) {
        ddclog("DedicateDataConnection onModification [" + mId + "]");
        Message msg = obtainMessage(EVENT_MODIFY_DATA_CONNECTION_DONE, dp);
        msg.obj = dp;
        // TODO: need to add this code later for L Migration VOLTE
        //mPhone.mCi.modifyDataCall(mCid, dp.param.qosStatus, dp.param.tftStatus, msg);
    }

    protected DedicateDataCallState.SetupResult onUpdate(DedicateConnectionParam dp) {
        ddclog("DedicateDataConnection onUpdate [" + mId + "]");
        mCid = dp.callState.cid;
        DedicateDataCallState.SetupResult result = updateDedicateBearerProperty(dp.callState).setupResult;
        return result;
    }

    protected void onDisconnect(Object obj) {
        Message msg = obtainMessage(EVENT_DISCONNECT_DATA_CONNECTION_DONE);
        if (obj == null) {
            ddclog("DedicateDataConnection [" + mId + "] onDisconnect, no obj parameter and send response msg directly");
            msg.sendToTarget();
        } else {
            if (obj instanceof DedicateConnectionParam) {
                ddclog("DedicateDataConnection [" + mId + "] onDisconnect with parameter, deactivate the connection");
                msg.obj = obj;
                // TODO: need to add this code later for L Migration VOLTE
                //mPhone.mCi.deactivateDedicateDataCall(mCid, ((DedicateConnectionParam)obj).reason, msg);
            } else if (obj instanceof Message) {
                ddclog("DedicateDataConnection [" + mId + "] onDisconnect with non-DedicateConnectionParam, send response msg directly");
                msg.obj = new DedicateConnectionParam("deactivate by network", (Message)obj);
                AsyncResult.forMessage(msg, null, null);
                msg.sendToTarget();
            } else {
                ddclog("DedicateDataConnection [" + mId + "] onDisconnect but unknown parameter");
            }
        }
    }

    protected void onAbort(Object obj) {
        Message msg = obtainMessage(EVENT_DISCONNECT_DATA_CONNECTION_DONE);
        ddclog("DedicateDataConnection [" + mId + "] onAbort, to abort the activation");
        msg.obj = obj;
        // TODO: need to add this code later for L Migration VOLTE
        //mPhone.mCi.abortSetupDataCall(mId, ((DedicateConnectionParam)obj).reason, msg);
    }

    private SetupResult onSetupConnectionCompleted(AsyncResult ar) {
        DedicateDataCallState[] dataCallStates = null;
        if (ar.result instanceof DedicateDataCallState) {
            dataCallStates = new DedicateDataCallState[1];
            dataCallStates[0] = (DedicateDataCallState)ar.result;
        } else if (ar != null) {
            dataCallStates = (DedicateDataCallState[]) ar.result;
        }

        SetupResult result = SetupResult.FAIL;
        if (dataCallStates != null && dataCallStates.length > 0) {
            result = updateDedicateBearerProperty(dataCallStates).setupResult;
            if (ar.exception != null) {
                if (ar.exception instanceof CommandException &&
                    ((CommandException) (ar.exception)).getCommandError() == CommandException.Error.RADIO_NOT_AVAILABLE)
                {
                    result.failCause = DcFailCause.RADIO_NOT_AVAILABLE.getErrorCode();
                } else {
                    result.failCause = dataCallStates[0].failCause;
                    if (result.failCause == DcFailCause.NONE.getErrorCode()) {
                        result.failCause = DcFailCause.UNKNOWN.getErrorCode();
                        ddclog("updateDedicateBearerProperty get exception but no fail cause, convert to UNKNOWN");
                    }
                }
            } else {
                result.failCause = dataCallStates[0].failCause;
                mCid = dataCallStates[0].cid;
            }
        } else {
            result.failCause = DcFailCause.UNKNOWN.getErrorCode();
            ddclog("onSetupConnectionCompleted but no any result");
        }
        return result;
    }

    private UpdateDedicateBearerPropertyResult updateDedicateBearerProperty(Object newState) { //newState should be DedicateDataCallState or DedicateDataCallState[]
        UpdateDedicateBearerPropertyResult result = new UpdateDedicateBearerPropertyResult(mProperties);
        if (newState == null)
            return result;

        result.newProperty = new DedicateBearerProperties();

        if (newState instanceof DedicateDataCallState[])
            result.setupResult = result.newProperty.setProperties((DedicateDataCallState[])newState);
        else
            result.setupResult = result.newProperty.setProperties((DedicateDataCallState)newState);

        if (result.setupResult != DedicateDataCallState.SetupResult.SUCCESS) {
            ddclog("updateDedicateBearerProperty failed : " + result.setupResult);
            return result;
        }

        if ((!result.oldProperty.equals(result.newProperty))) {
            ddclog("updateDedicateBearerProperty old LP=" + result.oldProperty);
            ddclog("updateDedicateBearerProperty new LP=" + result.newProperty);
        }
        mProperties = result.newProperty;

        return result;
    }

    private void clearSettings() {
        mProperties.clear();
        mCid = -1;
    }

    private void ddclog(String text) {
        Log.d(TAG, "[dedicate][GDDC-" + mId + "] " + text);
    }

    private void notifyConnectCompleted(DedicateConnectionParam param, DcFailCause cause) {
        Message onCompletedMsg = param.onCompletedMsg;
        if (onCompletedMsg == null) {
            ddclog("notifyConnectionCompleted and no complete message");
            return;
        }

        onCompletedMsg.arg1 = mCid;
        //NOT to modify arg2 here since we use it to know if we need to send notification
        //see updateActivatedConcatenatedBearer in DcTracker.java

        try {
            if (cause == DcFailCause.NONE) {
                ddclog("notifyConnectionCompleted success property=" + mProperties);
                AsyncResult.forMessage(onCompletedMsg, new DedicateBearerOperationResult((DedicateBearerProperties)mProperties.clone(), DcFailCause.NONE), null);
            } else {
                if (getCurrentState() == mActiveState) {
                    ddclog("notifyConnectionCompleted success with failcause=" + cause + ", property=" + mProperties);
                    AsyncResult.forMessage(onCompletedMsg, new DedicateBearerOperationResult((DedicateBearerProperties)mProperties.clone(), cause), null);
                } else {
                    ddclog("notifyConnectionCompleted with cause=" + cause + ", property=" + mProperties);
                    AsyncResult.forMessage(onCompletedMsg, new DedicateBearerOperationResult((DedicateBearerProperties)mProperties.clone(), cause), new Exception("Setup dedicate bearer failed"));
                }
            }
        } catch (CloneNotSupportedException e) {
            e.printStackTrace();
        }

        onCompletedMsg.sendToTarget();
    }

    private void notifyDisconnectCompleted(DedicateConnectionParam param, DcFailCause cause) {
        if (param == null) {
            ddclog("notifyDisconnectCompleted and no param, not to send complete message");
        } else {
            Message onCompletedMsg = param.onCompletedMsg;
            if (onCompletedMsg == null) {
                return;
            }

            onCompletedMsg.arg1 = mCid;

            try {
                if (cause == DcFailCause.NONE) {
                    ddclog("notifyDisconnectCompleted success property=" + mProperties);
                    AsyncResult.forMessage(onCompletedMsg, new DedicateBearerOperationResult((DedicateBearerProperties)mProperties.clone(), cause), null);
                } else {
                    ddclog("notifyDisconnectCompleted with cause=" + cause + ", property=" + mProperties);
                    AsyncResult.forMessage(onCompletedMsg, new DedicateBearerOperationResult((DedicateBearerProperties)mProperties.clone(), cause), new Exception("Deactivate dedicate bearer failed"));
                }
            } catch (CloneNotSupportedException e) {
                e.printStackTrace();
            }

            onCompletedMsg.sendToTarget();
        }
    }

    private class DefaultState extends State {
        @Override
        public boolean processMessage(Message msg) {
            boolean retVal = HANDLED; //default state machine should handle all request
            AsyncResult ar = null;
            DedicateConnectionParam dp = null;
            switch (msg.what) {
                case AsyncChannel.CMD_CHANNEL_FULL_CONNECTION:
                    if (mAc != null) {
                        ddclog("DefaultState disconnecting to previous connection [" + mId + "]");
                        mAc.replyToMessage(msg, AsyncChannel.CMD_CHANNEL_FULLY_CONNECTED,
                                AsyncChannel.STATUS_FULL_CONNECTION_REFUSED_ALREADY_CONNECTED);
                    } else {
                        mAc = new AsyncChannel();
                        mAc.connected(null, getHandler(), msg.replyTo);
                        ddclog("DefaultState FULL_CONNECTION reply connected [" + mId + "]");
                        mAc.replyToMessage(msg, AsyncChannel.CMD_CHANNEL_FULLY_CONNECTED,
                                AsyncChannel.STATUS_SUCCESSFUL, mId, "hi");
                    }
                    break;
                case AsyncChannel.CMD_CHANNEL_DISCONNECTED:
                    ddclog("DefaultState CMD_CHANNEL_DISCONNECTED");
                    quit();
                    break;
                case DcAsyncChannel.REQ_IS_INACTIVE: {
                    boolean val = getCurrentState() == mInactiveState;
                    ddclog("DefaultState REQ_IS_INACTIVE  isInactive=" + val);
                    mAc.replyToMessage(msg, DcAsyncChannel.RSP_IS_INACTIVE, val ? 1 : 0);
                    break;
                }
                case DcAsyncChannel.REQ_IS_ACTIVE: {
                    boolean val = getCurrentState() == mActiveState;
                    ddclog("DefaultState REQ_IS_ACTIVE  isActive=" + val);
                    mAc.replyToMessage(msg, DcAsyncChannel.RSP_IS_ACTIVE, val ? 1 : 0);
                    break;
                }
                case DedicateDataConnectionAc.REQ_IS_ACTIVATING: {
                    boolean val = getCurrentState() == mActivatingState;
                    ddclog("DefaultState REQ_IS_ACTIVATING  isActive=" + val);
                    mAc.replyToMessage(msg, DedicateDataConnectionAc.RSP_IS_ACTIVATING, val ? 1 : 0);
                    break;
                }
                case DcAsyncChannel.REQ_GET_LINK_PROPERTIES:
                    ddclog("DefaultState REQ_GET_LINK_PROPERTIES");
                    try {
                        mAc.replyToMessage(msg, DcAsyncChannel.RSP_GET_LINK_PROPERTIES, mProperties.clone());
                    } catch (CloneNotSupportedException e) {
                        mAc.replyToMessage(msg, DcAsyncChannel.RSP_GET_LINK_PROPERTIES, null);
                        e.printStackTrace();
                    }
                    break;
                case DcAsyncChannel.REQ_GET_CID:
                    ddclog("DefaultState REQ_GET_CID");
                    mAc.replyToMessage(msg, DcAsyncChannel.RSP_GET_CID, mCid);
                    break;
                case DedicateDataConnectionAc.REQ_SET_REASON:
                    ddclog("DefaultState REQ_SET_REASON");
                    mReason = (String)msg.obj;
                    mAc.replyToMessage(msg, DedicateDataConnectionAc.RSP_SET_REASON, 1);
                    break;
                case DedicateDataConnectionAc.REQ_GET_REASON:
                    ddclog("DefaultState REQ_GET_REASON");
                    mAc.replyToMessage(msg, DedicateDataConnectionAc.RSP_GET_REASON, mReason);
                    break;
                case EVENT_CONNECT:
                    ddclog("DefaultState receive EVENT_CONNECT (unreasonable)");
                    dp = (DedicateConnectionParam) msg.obj;
                    notifyConnectCompleted(dp, DcFailCause.UNKNOWN);
                    break;
                case EVENT_DISCONNECT:
                    ddclog("DefaultState receive EVENT_DISCONNECT (defer)");
                    deferMessage(msg);
                    break;
                case EVENT_MODIFY:
                    ddclog("DefaultState receive EVENT_CONNECT (unreasonable)");
                    dp = (DedicateConnectionParam) msg.obj;
                    notifyConnectCompleted(dp, DcFailCause.UNKNOWN);
                    break;
                case EVENT_UPDATE:
                    ddclog("DefaultState receive EVENT_CONNECT (defer)");
                    deferMessage(msg);
                    break;
                default:
                    ddclog("DedicateDataConnection receive unhandled message [DefaultState, " + msg.what + "]");
            }
            return retVal;
        }
    }

    private class InactiveState extends State {
        private boolean mIsConnect;
        private DedicateConnectionParam mDp;
        private DcFailCause mFailCause;

        @Override
        public boolean processMessage(Message msg) {
            boolean retVal = NOT_HANDLED;
            AsyncResult ar = null;
            DedicateConnectionParam dp = null;
            if (msg.obj instanceof DedicateConnectionParam) {
                dp = (DedicateConnectionParam) msg.obj;
            } else if (msg.obj != null) {
                ddclog("msg object class: " + msg.obj.getClass().getName());
                return retVal;
            }


            switch (msg.what) {
                case EVENT_CONNECT: {
                    ddclog("InactiveState msg.what=EVENT_CONNECT");
                    onConnect(dp);
                    transitionTo(mActivatingState);
                    retVal = HANDLED;
                    break;
                }
                case EVENT_DISCONNECT:
                    ddclog("InactiveState: msg.what=EVENT_DISCONNECT");
                    notifyDisconnectCompleted(dp, DcFailCause.NONE);
                    retVal = HANDLED;
                    break;
                case EVENT_UPDATE: {
                    ddclog("InactiveState receive EVENT_UPDATE");
                    DedicateDataCallState.SetupResult result = onUpdate(dp);
                    switch (result) {
                        case SUCCESS:
                            ddclog("InactiveState receive EVENT_UPDATE and is connected");
                            mReason = REASON_BEARER_ACTIVATION; //from inactive to active, set reason to activation
                            mActiveState.setEnterNotificationParams(dp, DcFailCause.NONE);
                            transitionTo(mActiveState);
                            break;
                        case FAIL:
                            DcFailCause failCause = DcFailCause.fromInt(dp.callState.failCause);
                            ddclog("InactiveState receive EVENT_UPDATE and is disconnected [" + failCause + "]");
                            notifyConnectCompleted(dp, failCause);
                            break;
                        default:
                            throw new RuntimeException("InactiveState unknown SetupResult, should not happen [" + result + "]");
                    }
                    retVal = HANDLED;
                    break;
                }
                default:
                    ddclog("DedicateDataConnection receive unhandled message [InactiveState, " + msg.what + "]");
            }
            return retVal;
        }

        @Override
        public void enter() {
            ddclog("DedicateDataConnection[" + mId + ", " + mCid + "] enter InactiveState state [dp=" + mDp + ", failCause=" + mFailCause + "]");
            if ((mDp != null) && (mFailCause != null)) {
                if (mIsConnect)
                    notifyConnectCompleted(mDp, mFailCause);
                else
                    notifyDisconnectCompleted(mDp, mFailCause);
            } else {
                ddclog("DedicateDataConnection enter InactiveState without notification");
            }
            clearSettings();
        }

        @Override
        public void exit() {
            mDp = null;
            mFailCause = null;
        }

        public void setEnterNotificationParams(boolean isToConnect, DedicateConnectionParam dp, DcFailCause cause) {
            mIsConnect = isToConnect;
            mDp = dp;
            mFailCause = cause;
        }
    }

    private class ActivatingState extends State {
        @Override
        public boolean processMessage(Message msg) {
            boolean retVal = NOT_HANDLED;
            AsyncResult ar = null;
            DedicateConnectionParam dp = null;
            switch (msg.what) {
                case EVENT_CONNECT:
                    ddclog("ActivatingState receive EVENT_CONNECT (defer)");
                    deferMessage(msg);
                    retVal = HANDLED;
                    break;
                case EVENT_DISCONNECT:
                    ddclog("ActivatingState receive EVENT_DISCONNECT (abort)");
                    onAbort(msg.obj);
                    transitionTo(mDisconnectingState);
                    retVal = HANDLED;
                    break;
                case EVENT_MODIFY:
                    ddclog("ActivatingState receive EVENT_MODIFY (defer)");
                    deferMessage(msg);
                    retVal = HANDLED;
                    break;
                case EVENT_SETUP_DATA_CONNECTION_DONE: {
                    ddclog("ActivatingState receive EVENT_SETUP_DATA_CONNECTION_DONE");
                    ar = (AsyncResult) msg.obj;
                    dp = (DedicateConnectionParam) ar.userObj;
                    DedicateDataCallState.SetupResult result = onSetupConnectionCompleted(ar);
                    switch (result) {
                        case SUCCESS:
                            ddclog("ActivatingState receive EVENT_SETUP_DATA_CONNECTION_DONE and SUCCESS [cause=" + result.failCause + "]");
                            mActiveState.setEnterNotificationParams(dp, DcFailCause.fromInt(result.failCause));
                            transitionTo(mActiveState);
                            break;
                        case FAIL:
                            ddclog("ActivatingState receive EVENT_SETUP_DATA_CONNECTION_DONE and FAIL [cause=" + result.failCause + "]");
                            mInactiveState.setEnterNotificationParams(true, dp, DcFailCause.fromInt(result.failCause));
                            transitionTo(mInactiveState);
                            break;
                        default:
                            throw new RuntimeException("ActivatingState unknown SetupResult, should not happen [" + result + "]");
                    }
                    retVal = HANDLED;
                    break;
                }
                default:
                    ddclog("DedicateDataConnection receive unhandled message [ActivatingState, " + msg.what + "]");
            }
            return retVal;
        }
    }

    private class ActiveState extends State {
        private DedicateConnectionParam mDp;
        private DcFailCause mFailCause;

        @Override
        public boolean processMessage(Message msg) {
            boolean retVal = NOT_HANDLED;
            switch (msg.what) {
                case EVENT_CONNECT:
                    ddclog("ActiveState: msg.what=EVENT_CONNECT");
                    notifyConnectCompleted((DedicateConnectionParam)msg.obj, DcFailCause.NONE);
                    retVal = HANDLED;
                    break;
                case EVENT_DISCONNECT:
                    ddclog("ActiveState: msg.what=EVENT_DISCONNECT");
                    onDisconnect(msg.obj);
                    transitionTo(mDisconnectingState);
                    retVal = HANDLED;
                    break;
                case EVENT_MODIFY:
                    ddclog("ActiveState: msg.what=EVENT_MODIFY");
                    onModification((DedicateConnectionParam) msg.obj);
                    retVal = HANDLED;
                    break;
                case EVENT_UPDATE: {
                    ddclog("ActiveState: msg.what=EVENT_UPDATE (handle update directly)");
                    DedicateConnectionParam dp = (DedicateConnectionParam) msg.obj;
                    DedicateDataCallState.SetupResult result = updateDedicateBearerProperty(dp.callState).setupResult;
                    switch (result) {
                        case SUCCESS:
                            ddclog("ActiveState receive EVENT_UPDATE and SUCCESS");
                            mReason = REASON_BEARER_MODIFICATION; //from active state to active state, set to update
                            notifyConnectCompleted(dp, DcFailCause.NONE);
                            break;
                        case FAIL:
                            ddclog("ActiveState receive EVENT_UPDATE and FAIL");
                            notifyConnectCompleted(dp, DcFailCause.fromInt(result.failCause));
                            break;
                        default:
                            throw new RuntimeException("ActiveState unknown SetupResult, should not happen [" + result + "]");
                    }
                    retVal = HANDLED;
                    break;
                }
                case EVENT_MODIFY_DATA_CONNECTION_DONE: {
                    AsyncResult ar = (AsyncResult)msg.obj;
                    DedicateConnectionParam dp = (DedicateConnectionParam)ar.userObj;
                    DedicateDataCallState.SetupResult result = updateDedicateBearerProperty(dp.callState).setupResult;
                    if (ar.exception == null) {
                        switch (result) {
                            case SUCCESS:
                                ddclog("ActiveState receive EVENT_MODIFY_DATA_CONNECTION_DONE and SUCCESS");
                                mReason = REASON_BEARER_MODIFICATION; //from active state to active state, set to update
                                notifyConnectCompleted(dp, DcFailCause.NONE);
                                break;
                            case FAIL:
                                ddclog("ActiveState receive EVENT_MODIFY_DATA_CONNECTION_DONE and FAIL");
                                notifyConnectCompleted(dp, DcFailCause.fromInt(result.failCause));
                                break;
                            default:
                                throw new RuntimeException("ActiveState unknown SetupResult, should not happen [" + result + "]");
                        }
                    } else {
                        ddclog("ActiveState receive EVENT_MODIFY_DATA_CONNECTION_DONE and exception");
                        notifyConnectCompleted(dp, DcFailCause.UNKNOWN);
                    }
                    retVal = HANDLED;
                    break;
                }
                default:
                    ddclog("DedicateDataConnection receive unhandled message [ActiveState, " + msg.what + "]");
            }
            return retVal;
        }

        @Override
        public void enter() {
            ddclog("DedicateDataConnection[" + mId + ", " + mCid + "] enter active state [dp=" + mDp + ", failCause=" + mFailCause + "]");
            if ((mDp != null) && (mFailCause != null)) {
                notifyConnectCompleted(mDp, mFailCause);
            }
        }

        @Override
        public void exit() {
            mDp = null;
            mFailCause = null;
        }

        public void setEnterNotificationParams(DedicateConnectionParam dp, DcFailCause cause) {
            mDp = dp;
            mFailCause = cause;
        }
    }

    private class DisconnectingState extends State {
        @Override
        public boolean processMessage(Message msg) {
            boolean retVal = NOT_HANDLED;
            switch (msg.what) {
                case EVENT_CONNECT:
                    ddclog("DisconnectingState receive EVENT_CONNECT (defer)");
                    deferMessage(msg);
                    retVal = HANDLED;
                    break;
                case EVENT_DISCONNECT_DATA_CONNECTION_DONE:
                    AsyncResult ar = (AsyncResult) msg.obj;
                    if (ar == null || ar.exception == null) {
                        //if ar is null, it means the disconnect is executed without completed message
                        DedicateConnectionParam dp = ar == null ? null : (DedicateConnectionParam)ar.userObj;
                        ddclog("DisconnectingState receive EVENT_DISCONNECT_DATA_CONNECTION_DONE (success)");
                        mInactiveState.setEnterNotificationParams(false, dp, DcFailCause.NONE);
                        transitionTo(mInactiveState);
                    } else {
                        ddclog("DisconnectingState receive EVENT_DISCONNECT_DATA_CONNECTION_DONE (fail)");
                        DedicateConnectionParam dp = ar == null ? null : (DedicateConnectionParam)ar.userObj;
                        //notifyDisconnectCompleted(dp, DcFailCause.UNKNOWN);
                        mInactiveState.setEnterNotificationParams(false, dp, DcFailCause.UNKNOWN);
                        transitionTo(mInactiveState);
                        //Here we force to transite to idle state with error instead of notify directly
                    }
                    retVal = HANDLED;
                    break;
                default:
                    ddclog("DedicateDataConnection receive unhandled message [DisconnectingState, " + msg.what + "]");
            }
            return retVal;
        }
    }

    private class DedicateConnectionParam {
        public DcTracker.EnableDedicateBearerParam param;
        public DedicateDataCallState callState;
        public Message onCompletedMsg;
        public String reason;

        public DedicateConnectionParam(DcTracker.EnableDedicateBearerParam enableDedicateBearerParam, Message msg) {
            param = enableDedicateBearerParam;
            onCompletedMsg = msg;
        }

        public DedicateConnectionParam(String rea, Message msg) {
            onCompletedMsg = msg;
            reason = rea;
        }

        public DedicateConnectionParam(DedicateDataCallState dedicateDataCallState, Message msg) {
            callState = dedicateDataCallState;
            onCompletedMsg = msg;
        }
    }

    private class UpdateDedicateBearerPropertyResult {
        public DedicateDataCallState.SetupResult setupResult = DedicateDataCallState.SetupResult.SUCCESS;
        public DedicateBearerProperties oldProperty;
        public DedicateBearerProperties newProperty;

        public UpdateDedicateBearerPropertyResult(DedicateBearerProperties curProperty) {
            oldProperty = curProperty;
            newProperty = curProperty;
        }
    }

    public class DedicateBearerOperationResult {
        public DedicateBearerProperties properties;
        public DcFailCause failCause;

        public DedicateBearerOperationResult() {
        }

        public DedicateBearerOperationResult(DedicateBearerProperties perperty, DcFailCause cause) {
            properties = perperty;
            failCause = cause;
        }
    }
}
