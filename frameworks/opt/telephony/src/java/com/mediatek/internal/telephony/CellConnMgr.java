package com.mediatek.internal.telephony;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;

import android.telephony.Rlog;
import android.telephony.SubInfoRecord;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import com.android.internal.telephony.ITelephony;

import java.util.ArrayList;

/**
 * {@hide}
 *
 * Provide utility functionality to check flight mode, radio, locked and roaming state.
 * User cares about the mentioned state notification can use these APIs.
 *
 */
public class CellConnMgr {
    private static final String TAG = "CellConnMgr";

    /* Use bit mask to indicated state */
    public static final int STATE_READY = 0x00;
    public static final int STATE_FLIGHT_MODE = 0x01;
    public static final int STATE_RADIO_OFF = 0x02;
    public static final int STATE_SIM_LOCKED = 0x04;
    public static final int STATE_ROAMING = 0x08;

    private Context mContext;

    /**
     * To use the utility function, please create the object on your local side.
     *
     * @param context the indicated context
     *
     */
    public CellConnMgr(Context context) {
        mContext = context;

        if (mContext == null) {
            throw new RuntimeException(
                "CellConnMgr must be created by indicated context");
        }
    }

    /**
     * Query current state by indicated subscription and request type.
     *
     * @param subId indicated subscription
     * @param requestType the request type you cared
     *                    STATE_FLIGHT_MODE means that you would like to query if under flight mode.
     *                    STATE_RADIO_OFF means that you would like to query if this SIM radio off.
     *                    STATE_SIM_LOCKED will check flight mode and radio state first, and then
     *                                     check if under SIM locked state.
     *                    STATE_ROAMING will check flight mode and radio state first, and then
     *                                  check if under roaming.
     * @return a bit mask value composed by STATE_FLIGHT_MODE, STATE_RADIO_OFF, STATE_SIM_LOCKED and
     *         STATE_ROAMING.
     *
     */
    public int getCurrentState(long subId, int requestType) {
        int state = STATE_READY;

        // Query flight mode settings
        int flightMode = Settings.Global.getInt(
                mContext.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, -1);

        // Query radio state (need to check if the radio off is set by users)
        boolean isRadioOff = !isRadioOn(subId) && isRadioOffBySimManagement(subId);

        // Query SIM state
        int slotId = SubscriptionManager.getSlotId(subId);
        TelephonyManager telephonyMgr = TelephonyManager.getDefault();
        boolean isLocked =
                (TelephonyManager.SIM_STATE_PIN_REQUIRED == telephonyMgr.getSimState(slotId)
                || TelephonyManager.SIM_STATE_PUK_REQUIRED == telephonyMgr.getSimState(slotId)
                || TelephonyManager.SIM_STATE_NETWORK_LOCKED == telephonyMgr.getSimState(slotId));

        // Query roaming state
        boolean isRoaming = roamingRequest(subId);

        Rlog.d(TAG, "[getCurrentState]subId: " + subId + ", requestType:" + requestType +
                "; (flight mode, radio off, locked, roaming) = ("
                + flightMode + "," + isRadioOff + "," + isLocked + "," + isRoaming + ")");

        switch (requestType) {
            case STATE_FLIGHT_MODE:
                state = ((flightMode == 1) ? STATE_FLIGHT_MODE : STATE_READY);
                break;

            case STATE_RADIO_OFF:
                state = ((isRadioOff) ? STATE_RADIO_OFF : STATE_READY);
                break;

            case STATE_SIM_LOCKED:
                state = (((flightMode == 1) ? STATE_FLIGHT_MODE : STATE_READY) |
                        ((isRadioOff) ? STATE_RADIO_OFF : STATE_READY) |
                        ((isLocked) ? STATE_SIM_LOCKED : STATE_READY));
                break;

            case STATE_ROAMING:
                // If fligt mode on/radio off and roaming state occurred continuously, means that
                // we need to show two dialog continously.
                // That is, need to indicate multiple state at the same time.
                state = (((flightMode == 1) ? STATE_FLIGHT_MODE : STATE_READY) |
                        ((isRadioOff) ? STATE_RADIO_OFF : STATE_READY) |
                        ((isRoaming) ? STATE_ROAMING : STATE_READY));

                break;

            default:
                state = ((flightMode == 1) ? STATE_FLIGHT_MODE : STATE_READY) |
                        ((isRadioOff) ? STATE_RADIO_OFF : STATE_READY) |
                        ((isLocked) ? STATE_SIM_LOCKED : STATE_READY) |
                        ((isRoaming) ? STATE_ROAMING : STATE_READY);
        }

        Rlog.d(TAG, "[getCurrentState] state:" + state);

        return state;
    }

    /**
     * Get dialog showing description, positive button and negative button string by state.
     *
     * @param subId indicated subscription
     * @param state current state query by getCurrentState(long subId, int requestType).
     * @return title, description, positive button and negative strings with following format.
     *         stringList.get(0) = "state1's title"
     *         stringList.get(1) = "state1's description",
     *         stringList.get(2) = "state1's positive buttion"
     *         stringList.get(3) = "state1's negative button"
     *         stringList.get(4) = "state2's title"
     *         stringList.get(5) = "state1's description",
     *         stringList.get(6) = "state1's positive buttion"
     *         stringList.get(7) = "state1's negative button"
     *         A set is composited of four strings.
     *
     */
    public ArrayList<String> getStringUsingState(long subId, int state) {
        ArrayList<String> stringList = new ArrayList<String>();

        Rlog.d(TAG, "[getStringUsingState] subId: " + subId + ", state:" + state);

        if ((state & (STATE_FLIGHT_MODE | STATE_RADIO_OFF))
                == (STATE_FLIGHT_MODE | STATE_RADIO_OFF)) {
            // 0. Turn off flight mode + turn radio on
            stringList.add(Resources.getSystem().getString(
                    com.mediatek.internal.R.string.confirm_flight_mode_radio_title));
            stringList.add(Resources.getSystem().getString(
                    com.mediatek.internal.R.string.confirm_flight_mode_radio_msg));
            stringList.add(Resources.getSystem().getString(
                    com.mediatek.internal.R.string.confirm_button_ok));
            stringList.add(Resources.getSystem().getString(
                    com.mediatek.internal.R.string.confirm_button_cancel));
            Rlog.d(TAG, "[getStringUsingState] STATE_FLIGHT_MODE + STATE_RADIO_OFF");
        } else if ((state & STATE_FLIGHT_MODE) == STATE_FLIGHT_MODE) {
            // 1. Turn off flight mode
            stringList.add(Resources.getSystem().getString(
                    com.mediatek.internal.R.string.confirm_flight_mode_title));
            stringList.add(Resources.getSystem().getString(
                    com.mediatek.internal.R.string.confirm_flight_mode_msg));
            stringList.add(Resources.getSystem().getString(
                    com.mediatek.internal.R.string.confirm_button_turn_off));
            stringList.add(Resources.getSystem().getString(
                    com.mediatek.internal.R.string.confirm_button_cancel));
            Rlog.d(TAG, "[getStringUsingState] STATE_FLIGHT_MODE");
        } else if ((state & STATE_RADIO_OFF) == STATE_RADIO_OFF) {
            // 2. Turn radio on
            stringList.add(Resources.getSystem().getString(
                    com.mediatek.internal.R.string.confirm_radio_title));
            stringList.add(Resources.getSystem().getString(
                    com.mediatek.internal.R.string.confirm_radio_msg));
            stringList.add(Resources.getSystem().getString(
                    com.mediatek.internal.R.string.confirm_button_turn_on));
            stringList.add(Resources.getSystem().getString(
                    com.mediatek.internal.R.string.confirm_button_cancel));
            Rlog.d(TAG, "[getStringUsingState] STATE_RADIO_OFF");
        } else if ((state & STATE_SIM_LOCKED) == STATE_SIM_LOCKED) {
            // 3. Unlock
            stringList.add(Resources.getSystem().getString(
                    com.mediatek.internal.R.string.confirm_unlock_title));
            stringList.add(Resources.getSystem().getString(
                    com.mediatek.internal.R.string.confirm_unlock_msg));
            stringList.add(Resources.getSystem().getString(
                    com.mediatek.internal.R.string.confirm_button_unlock));
            stringList.add(Resources.getSystem().getString(
                    com.mediatek.internal.R.string.confirm_button_cancel));
            Rlog.d(TAG, "[getStringUsingState] STATE_SIM_LOCKED");
        }

        // 4. roaming reminder
        /* Comment roaming reminder since common version didn't reminder this.
        if (((state & STATE_SIM_LOCKED) != STATE_SIM_LOCKED)
                && ((state & STATE_ROAMING) == STATE_ROAMING)) {
            stringList.add(Resources.getSystem().getString(
                    com.mediatek.internal.R.string.confirm_roaming_title));
            stringList.add(Resources.getSystem().getString(
                    com.mediatek.internal.R.string.confirm_roaming_msg));
            stringList.add(Resources.getSystem().getString(
                    com.mediatek.internal.R.string.confirm_button_yes));
            stringList.add(Resources.getSystem().getString(
                    com.mediatek.internal.R.string.confirm_button_no));
            Rlog.d(TAG, "[getStringUsingState] STATE_ROAMING");
        }*/

        Rlog.d(TAG, "[getStringUsingState]stringList size: " + stringList.size());

        return ((ArrayList<String>) stringList.clone());
    }

    /**
     * Handle positive button operation by indicated state.
     *
     * @param subId indicated subscription
     * @param state current state query by getCurrentState(long subId, int requestType).
     *
     */
    public void handleRequest(long subId, int state) {

        Rlog.d(TAG, "[handleRequest] subId: " + subId + ", state:" + state);

        // 1.Turn off flight mode
        if ((state & STATE_FLIGHT_MODE) == STATE_FLIGHT_MODE) {
            Settings.Global.putInt(
                    mContext.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0);
            mContext.sendBroadcast(
                    new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).putExtra("state", false));

            Rlog.d(TAG, "[handleRequest] Turn off flight mode.");
        }

        // 2.Turn radio on
        if ((state & STATE_RADIO_OFF) == STATE_RADIO_OFF) {
            int mSimMode = 0;
            for (int i = 0 ; i < TelephonyManager.getDefault().getSimCount() ; i++) {
                // TODO: need to revise in case of sub-based modem support
                long[] targetSubId = SubscriptionManager.getSubId(i);

                if (((targetSubId != null && isRadioOn(targetSubId[0]))
                        || (i == SubscriptionManager.getSlotId(subId)))) {
                    mSimMode = mSimMode | (1 << i);
                }
            }

            Settings.System.putInt(mContext.getContentResolver(),
                    Settings.System.MSIM_MODE_SETTING, mSimMode);

            Intent intent = new Intent(Intent.ACTION_MSIM_MODE_CHANGED);
            intent.putExtra(Intent.EXTRA_MSIM_MODE, mSimMode);
            mContext.sendBroadcast(intent);

            Rlog.d(TAG, "[handleRequest] Turn radio on, MSIM mode:" + mSimMode);
        }

        // 3.Unlock
        // (only do this in case of flight mode has been truned of and radio has beend truned on)
        if (!((state & STATE_FLIGHT_MODE) == STATE_FLIGHT_MODE
                || (state & STATE_RADIO_OFF) == STATE_RADIO_OFF) &&
                ((state & STATE_SIM_LOCKED) == STATE_SIM_LOCKED)) {
            // If flight mode on or radio off, the unlock event will be trigger
            // by SIM_STATE_CHANGED.
            // That is, no need to indicate under SIM_LOCKED state here.
            // Otherwise, if flight mode off and radio on, the unlock event should be triggered
            // by UNLOCK Intent.
            // That is, need to indicate under SIM_LOCKED state here.
            try {
                ITelephonyEx iTelEx = ITelephonyEx.Stub.asInterface(
                        ServiceManager.getService(Context.TELEPHONY_SERVICE_EX));

                iTelEx.broadcastIccUnlockIntent(subId);

                Rlog.d(TAG, "[handleRequest] broadcastIccUnlockIntent");

            } catch (RemoteException ex) {
                ex.printStackTrace();
            } catch (NullPointerException ex) {
                Rlog.d(TAG, "ITelephonyEx is null");
                // This could happen before phone restarts due to crashing
                ex.printStackTrace();
            }

        }

        // 4.Roaming
        /* Comment roaming reminder since common version didn't reminder this.
        if ((state & STATE_RADIO_OFF) == STATE_RADIO_OFF) {
            //if (0 == Settings.System.getInt(mContext.getContentResolver(),
            //        Settings.System.ROAMING_REMINDER_MODE_SETTING,-1)) {
            //    clearRoamingNeeded(reqItem.getReqSlot());
            //}
        }*/
    }


    private boolean isRadioOffBySimManagement(long subId) {
        boolean result = true;
        try {
            ITelephonyEx iTelEx = ITelephonyEx.Stub.asInterface(
                    ServiceManager.getService(Context.TELEPHONY_SERVICE_EX));

            if (null == iTelEx) {
                Rlog.d(TAG, "[isRadioOffBySimManagement] iTelEx is null");
                return false;
            }

            result = iTelEx.isRadioOffBySimManagement(subId);
        } catch (RemoteException ex) {
            ex.printStackTrace();
        }

        Rlog.d(TAG, "[isRadioOffBySimManagement]  subId " + subId + ", result = " + result);
        return result;
    }


    private boolean isRadioOn(long subId) {
        Rlog.d(TAG, "isRadioOff verify subId " + subId);
        boolean radioOn = true;
        try {
            ITelephony iTel = ITelephony.Stub.asInterface(
                    ServiceManager.getService(Context.TELEPHONY_SERVICE));

            if (null == iTel) {
                Rlog.d(TAG, "isRadioOff iTel is null");
                return false;
            }

            radioOn = iTel.isRadioOnForSubscriber(subId);
        } catch (RemoteException ex) {
            ex.printStackTrace();
        }

        Rlog.d(TAG, "isRadioOff subId " + subId + " radio on? " + radioOn);
        return radioOn;
    }

    // TODO: gsm.roaming.indicator.needed.2?
    // Refer to ITelephony/ITelephonyEx:  setRoamingIndicatorNeddedProperty(boolean property)
    private boolean isRoamingNeeded(long subId) {
        return false;
/*        Rlog.d(TAG, "isRoamingNeeded slot = " + slot);
        if (slot == PhoneConstants.GEMINI_SIM_2) {
            Rlog.d(TAG, "isRoamingNeeded = "
                    + SystemProperties.getBoolean("gsm.roaming.indicator.needed.2", false));
            return SystemProperties.getBoolean("gsm.roaming.indicator.needed.2", false);
        } else {
            Rlog.d(TAG, "isRoamingNeeded = "
                    + SystemProperties.getBoolean("gsm.roaming.indicator.needed", false));
            return SystemProperties.getBoolean("gsm.roaming.indicator.needed", false);
        }
*/    }

    private void clearRoamingNeeded(int slot) {
/*        Rlog.d(TAG, "clearRoamingNeeded slot = " + slot);
        if (slot == PhoneConstants.GEMINI_SIM_2) {
            SystemProperties.set("gsm.roaming.indicator.needed.2", "false");
        } else {
            SystemProperties.set("gsm.roaming.indicator.needed", "false");
        }
*/    }

    private boolean roamingRequest(long subId) {
        Rlog.d(TAG, "roamingRequest subId = " + subId);

        if (TelephonyManager.getDefault().isNetworkRoaming(subId)) {
            Rlog.d(TAG, "roamingRequest subId = " + subId + " is roaming");
        } else {
            Rlog.d(TAG, "roamingRequest subId = " + subId + " is not roaming");
            return false;
        }

        if (1 == Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.ROAMING_REMINDER_MODE_SETTING, -1)) {
            Rlog.d(TAG, "roamingRequest reminder always");
            return true;
        }

        if (0 == Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.ROAMING_REMINDER_MODE_SETTING, -1)
                && isRoamingNeeded(subId)) {
            Rlog.d(TAG, "roamingRequest reminder once and need to indicate");
            return true;
        }

        Rlog.d(TAG, "roamingRequest result = false");
        return false;
    }
}
