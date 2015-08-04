/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.mediatek.internal.telephony.uicc;


import static android.Manifest.permission.READ_PHONE_STATE;
import android.app.ActivityManagerNative;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.UserHandle;

import android.telephony.TelephonyManager;
import android.telephony.SubscriptionManager;
import android.telephony.Rlog;

import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.IccCardConstants.State;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.uicc.UiccController;
import com.android.internal.telephony.uicc.UiccCard;
import com.android.internal.telephony.uicc.UiccCardApplication;
import com.android.internal.telephony.uicc.IccRecords;
import com.android.internal.telephony.uicc.IccFileHandler;
import com.android.internal.telephony.uicc.IccCardStatus;
import com.android.internal.telephony.uicc.IccCardStatus.CardState;
import com.android.internal.telephony.PhoneConstants;

/**
 * @Deprecated use {@link UiccController}.getUiccCard instead.
 *
 * The Phone App assumes that there is only one icc card, and one icc application
 * available at a time. Moreover, it assumes such object (represented with IccCard)
 * is available all the time (whether {@link RILConstants.RIL_REQUEST_GET_SIM_STATUS} returned
 * or not, whether card has desired application or not, whether there really is a card in the
 * slot or not).
 *
 * UiccController, however, can handle multiple instances of icc objects (multiple
 * {@link UiccCardApplication}, multiple {@link IccFileHandler}, multiple {@link IccRecords})
 * created and destroyed dynamically during phone operation.
 *
 * This class implements the IccCard interface that is always available (right after default
 * phone object is constructed) to expose the current (based on voice radio technology)
 * application on the uicc card, so that external apps won't break.
 */

public class IccCardProxyEx extends Handler {
    private static final boolean DBG = true;
    private static final String LOG_TAG = "IccCardProxyEx";

    private static final int EVENT_RADIO_OFF_OR_UNAVAILABLE = 1;
    private static final int EVENT_ICC_CHANGED = 2;
    private static final int EVENT_APP_READY = 3;
    private static final int EVENT_RECORDS_LOADED = 4;

    private final Object mLock = new Object();
    private Context mContext;
    private CommandsInterface mCi;

    private static final int APPLICATION_ID_ISIM = 0;
    private static final int APPLICATION_ID_MAX = 1;

    private int mCurrentAppType = UiccController.APP_FAM_IMS;
    private UiccController mUiccController = null;

    private UiccCard mUiccCard = null;
    private UiccCardApplication[] mUiccApplication =
        new UiccCardApplication[IccCardStatus.CARD_MAX_APPS];
    private IccRecords[] mIccRecords =
        new IccRecords[IccCardStatus.CARD_MAX_APPS];

    private State[] mExternalState = new State[]{State.UNKNOWN};
    private int mSlotId;

    public IccCardProxyEx(Context context, CommandsInterface ci) {
        this(context, ci, PhoneConstants.SIM_ID_1);
    }

    public IccCardProxyEx(Context context, CommandsInterface ci, int slotId) {
        this.mContext = context;
        this.mCi = ci;
        // FIXME: slotId or phoneId?
        mSlotId = slotId;

        Integer index = new Integer(mSlotId);
        mUiccController = UiccController.getInstance();
        ci.registerForOffOrNotAvailable(this, EVENT_RADIO_OFF_OR_UNAVAILABLE, index);
        mUiccController.registerForApplicationChanged(this, EVENT_ICC_CHANGED, index);

        setExternalState(State.NOT_READY, 0);
        log("Creating");
    }

    public void dispose() {
        synchronized (mLock) {
            log("Disposing");
            //Cleanup icc references
            mCi.unregisterForOffOrNotAvailable(this);
            mUiccController.unregisterForApplicationChanged(this);
            mUiccController = null;
        }
    }

    public void handleMessage(Message msg) {
        AsyncResult ar;
        int appId;
        log("receive message " + msg.what);

        Integer index = getIndex(msg);

        if (index < 0 || index >= TelephonyManager.getDefault().getPhoneCount() || index != mSlotId) {
            loge("Invalid index : " + index + " received with event " + msg.what);
            return;
        }

        switch (msg.what) {
            case EVENT_RADIO_OFF_OR_UNAVAILABLE:
                for (int i = 0; i < APPLICATION_ID_MAX; i++) {
                    setExternalState(State.NOT_READY, i);
                }
                break;

            case EVENT_ICC_CHANGED:
                updateIccAvailability(0);
                break;

            case EVENT_APP_READY:
                ar = (AsyncResult) (msg.obj);
                appId = ((Integer) ar.userObj).intValue();
                setExternalState(State.READY, appId);
                break;

            case EVENT_RECORDS_LOADED:
                ar = (AsyncResult) (msg.obj);
                appId = ((Integer) ar.userObj).intValue();
                broadcastIccStateChangedIntent(IccCardConstants.INTENT_VALUE_ICC_LOADED, null, appId);

                break;
            default:
                loge("Unhandled message with number: " + msg.what);
                break;
        }
    }

    private void updateIccAvailability(int appId) {
        synchronized (mLock) {
            UiccCard newCard = mUiccController.getUiccCard();
            CardState state = CardState.CARDSTATE_ABSENT;
            UiccCardApplication newApp = null;
            IccRecords newRecords = null;
            int appType = 0;

            switch(appId) {
                case 0:
                    appType = UiccController.APP_FAM_IMS;
                    break;
                default:
                    break;
            }

            if (newCard != null) {
                state = newCard.getCardState();
                newApp = newCard.getApplication(appType);
                if (newApp != null) {
                    newRecords = newApp.getIccRecords();
                }
            }

            if (mIccRecords[appId] != newRecords || mUiccApplication[appId] != newApp || mUiccCard != newCard) {
                if (DBG) log("Icc changed. Reregestering.");
                unregisterUiccCardEvents(appId);
                mUiccCard = newCard;
                mUiccApplication[appId] = newApp;
                mIccRecords[appId] = newRecords;
                registerUiccCardEvents(appId);
            }

            updateExternalState(appId);
        }
    }

    private void updateExternalState(int appId) {

        if (mUiccCard == null) {
            setExternalState(State.NOT_READY, appId);
            return;
        } else if (mUiccCard.getCardState() == CardState.CARDSTATE_ABSENT) {
            setExternalState(State.ABSENT, appId);
            return;
        }

        if (DBG) log("CardState = " + mUiccCard.getCardState());

        if (mUiccCard.getCardState() == CardState.CARDSTATE_ERROR ||
                mUiccApplication[appId] == null) {
            setExternalState(State.UNKNOWN, appId);
            return;
        }

        if (DBG) log("application state = " + mUiccApplication[appId].getState());
        if (DBG) log("mUiccApplication[appId] = " + mUiccApplication[appId]);

        switch (mUiccApplication[appId].getState()) {
            case APPSTATE_UNKNOWN:
            case APPSTATE_DETECTED:
                setExternalState(State.UNKNOWN, appId);
                break;
            case APPSTATE_READY:
                setExternalState(State.READY, appId);
                break;
        }
    }

    private void registerUiccCardEvents(int appId) {
        Integer aid = new Integer(appId);
        if (mUiccApplication[appId] != null) {
            mUiccApplication[appId].registerForReady(this, EVENT_APP_READY, (Object) aid);
        }
        if (mIccRecords[appId] != null) {
            mIccRecords[appId].registerForRecordsLoaded(this, EVENT_RECORDS_LOADED, (Object) aid);
        }
    }

    private void unregisterUiccCardEvents(int appId) {
        if (mUiccApplication[appId] != null) mUiccApplication[appId].unregisterForReady(this);
        if (mIccRecords[appId] != null) mIccRecords[appId].unregisterForRecordsLoaded(this);
    }

    private void broadcastIccStateChangedIntent(String value, String reason, int appId) {
        synchronized (mLock) {

            Intent intent = new Intent(TelephonyIntents.ACTION_SIM_STATE_CHANGED_MULTI_APPLICATION);
            intent.putExtra(PhoneConstants.PHONE_NAME_KEY, "Phone");
            intent.putExtra(IccCardConstants.INTENT_KEY_ICC_STATE, value);
            intent.putExtra(IccCardConstants.INTENT_KEY_LOCKED_REASON, reason);
            // FIXME: putPhoneIdAndSubIdExtra is phone based API, slot id might not equal to phone id.
            SubscriptionManager.putPhoneIdAndSubIdExtra(intent, mSlotId);
            intent.putExtra("appId", appId);
            if (DBG) log("Broadcasting intent ACTION_SIM_STATE_CHANGED_MULTI_APPLICATION " +  value
                    + " reason " + reason + " slotd id " + mSlotId + " app id " + appId);
            ActivityManagerNative.broadcastStickyIntent(intent, READ_PHONE_STATE,
                    UserHandle.USER_ALL);
        }
    }

    private void setExternalState(State newState, boolean override, int appId) {
        synchronized (mLock) {
            if (DBG) log("setExternalState(): mExternalState = " + mExternalState[appId] +
                " newState =  " + newState + " override = " + override);

            if (!override && newState == mExternalState[appId]) {
                return;
            }

            mExternalState[appId] = newState;

            broadcastIccStateChangedIntent(getIccStateIntentString(mExternalState[appId]),
                    getIccStateReason(mExternalState[appId], appId), appId);
        }
    }

    private void setExternalState(State newState, int appId) {
        if (DBG) log("setExternalState(): newState =  " + newState + "appId = " + appId);
        setExternalState(newState, false, appId);
    }

    private String getIccStateIntentString(State state) {
        switch (state) {
            case ABSENT: return IccCardConstants.INTENT_VALUE_ICC_ABSENT;
            case PIN_REQUIRED: return IccCardConstants.INTENT_VALUE_ICC_LOCKED;
            case PUK_REQUIRED: return IccCardConstants.INTENT_VALUE_ICC_LOCKED;
            case NETWORK_LOCKED: return IccCardConstants.INTENT_VALUE_ICC_LOCKED;
            case READY: return IccCardConstants.INTENT_VALUE_ICC_READY;
            case NOT_READY: return IccCardConstants.INTENT_VALUE_ICC_NOT_READY;
            case PERM_DISABLED: return IccCardConstants.INTENT_VALUE_ICC_LOCKED;
            default: return IccCardConstants.INTENT_VALUE_ICC_UNKNOWN;
        }
    }

    /**
     * Locked state have a reason (PIN, PUK, NETWORK, PERM_DISABLED)
     * @return reason
     */
    private String getIccStateReason(State state, int appId) {
        switch (state) {
            case PIN_REQUIRED: return IccCardConstants.INTENT_VALUE_LOCKED_ON_PIN;
            case PUK_REQUIRED: return IccCardConstants.INTENT_VALUE_LOCKED_ON_PUK;
            case NETWORK_LOCKED:
                switch (mUiccApplication[appId].getPersoSubState()) {
                    case PERSOSUBSTATE_SIM_NETWORK: return IccCardConstants.INTENT_VALUE_LOCKED_NETWORK;
                    case PERSOSUBSTATE_SIM_NETWORK_SUBSET: return IccCardConstants.INTENT_VALUE_LOCKED_NETWORK_SUBSET;
                    case PERSOSUBSTATE_SIM_CORPORATE: return IccCardConstants.INTENT_VALUE_LOCKED_CORPORATE;
                    case PERSOSUBSTATE_SIM_SERVICE_PROVIDER: return IccCardConstants.INTENT_VALUE_LOCKED_SERVICE_PROVIDER;
                    case PERSOSUBSTATE_SIM_SIM: return IccCardConstants.INTENT_VALUE_LOCKED_SIM;
                    default: return null;
                }
            case PERM_DISABLED: return IccCardConstants.INTENT_VALUE_ABSENT_ON_PERM_DISABLED;
            default: return null;
       }
    }

    /* IccCard interface implementation */
    // TODO: MTK use ICS solution, there is only three state, radio.on, radio.off, radio.not.available

    public State getState(int appId) {
        synchronized (mLock) {
            return mExternalState[appId];
        }
    }


    public IccRecords getIccRecords(int family) {
        int appId = -1;
        synchronized (mLock) {
            switch (family) {
                case UiccController.APP_FAM_IMS:
                    appId = 0;
                    break;
                default:
                    if (DBG) log("Not Support");
                    break;
            }

            if (appId != -1) {
                return mIccRecords[appId];
            } else {
                return null;
            }
        }
    }

    public IccFileHandler getIccFileHandler(int appId) {
        synchronized (mLock) {
            if (mUiccApplication[appId] != null) {
                return mUiccApplication[appId].getIccFileHandler();
            }
            return null;
        }
    }

    private Integer getIndex(Message msg) {
        AsyncResult ar;
        // FIXME: PhoneConstants.DEFAULT_CARD_INDEX will be changed?
        Integer index = new Integer(PhoneConstants.DEFAULT_CARD_INDEX);

        /*
         * The events can be come in two ways. By explicitly sending it using
         * sendMessage, in this case the user object passed is msg.obj and from
         * the CommandsInterface, in this case the user object is msg.obj.userObj
         */
        if (msg != null) {
            if (msg.obj != null && msg.obj instanceof Integer) {
                index = (Integer) msg.obj;
            } else if (msg.obj != null && msg.obj instanceof AsyncResult) {
                ar = (AsyncResult) msg.obj;
                if (ar.userObj != null && ar.userObj instanceof Integer) {
                    index = (Integer) ar.userObj;
                }
            }
        }
        return index;
    }


    private void log(String msg) {
        Rlog.d(LOG_TAG, "[SIM" + (mSlotId == 0 ? "1" : "2") + "] " + msg);
    }

    private void loge(String msg) {
        Rlog.e(LOG_TAG, "[SIM" + (mSlotId == 0 ? "1" : "2") + "] " + msg);
    }
}
