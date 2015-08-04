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

package com.android.keyguard;

import android.content.Context;
import android.content.res.TypedArray;
import android.text.method.SingleLineTransformationMethod;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.IccCardConstants.State;
import com.android.internal.widget.LockPatternUtils;

import java.util.Locale;
import android.util.Log;

public class CarrierText extends LinearLayout {
    private static final String TAG = "CarrierText";
    private static final boolean DEBUG = KeyguardConstants.DEBUG;
    private static final int MAX_CARRIER_TEXT_NUM = 4;
    private static CharSequence mSeparator;

    private LockPatternUtils mLockPatternUtils;

    private KeyguardUpdateMonitor mUpdateMonitor;
    private int mNumOfSub;
    private TextView mCarrierView[];
    private TextView mCarrierDivider[];
    private boolean mUseAllCaps;

    private KeyguardUpdateMonitorCallback mCallback = new KeyguardUpdateMonitorCallback() {
        @Override
        public void onRefreshCarrierInfo(long subId, CharSequence plmn, CharSequence spn) {
            updateCarrierText(subId, mUpdateMonitor.getSimStateOfSub(subId), plmn, spn);
        }

        @Override
        public void onSimStateChangedUsingSubId(long subId, IccCardConstants.State simState) {
            updateCarrierText(subId, simState, mUpdateMonitor.getTelephonyPlmn(subId),
                mUpdateMonitor.getTelephonySpn(subId));
        }

        public void onScreenTurnedOff(int why) {
            for (int i = 0; i < mNumOfSub; i++) {
                mCarrierView[i].setSelected(false);
            }
        };

        public void onScreenTurnedOn() {
            for (int i = 0; i < mNumOfSub; i++) {
                mCarrierView[i].setSelected(true);
            }
        };
    };
    /**
     * The status of this lock screen. Primarily used for widgets on LockScreen.
     */
    private static enum StatusMode {
        Normal, // Normal case (sim card present, it's not locked)
        NetworkLocked, // SIM card is 'network locked'.
        SimMissing, // SIM card is missing.
        SimMissingLocked, // SIM card is missing, and device isn't provisioned; don't allow access
        SimPukLocked, // SIM card is PUK locked because SIM entered wrong too many times
        SimLocked, // SIM card is currently locked
        SimPermDisabled, // SIM card is permanently disabled due to PUK unlock failure
        SimNotReady; // SIM is not ready yet. May never be on devices w/o a SIM.
    }

    public CarrierText(Context context) {
        this(context, null);
    }

    public CarrierText(Context context, AttributeSet attrs) {
        super(context, attrs);
        LayoutInflater inflater = (LayoutInflater) context.getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);
            inflater.inflate(R.layout.keyguard_carrier_text_view, this, true);

        mLockPatternUtils = new LockPatternUtils(mContext);
        mUpdateMonitor = KeyguardUpdateMonitor.getInstance(mContext);
        mNumOfSub = mUpdateMonitor.getNumOfSubscription();

        // the carrier and carrier divider number is defined in layout: keyguard_carrier_text_view.xml
        mCarrierView = new TextView[MAX_CARRIER_TEXT_NUM];
        mCarrierDivider = new TextView[MAX_CARRIER_TEXT_NUM - 1];
        TypedArray a = context.getTheme().obtainStyledAttributes(
                attrs, R.styleable.CarrierText, 0, 0);
        try {
            mUseAllCaps = a.getBoolean(R.styleable.CarrierText_allCaps, false);
        } finally {
            a.recycle();
        }
    }

    public void setTextSize(int unit, float size) {
        for (int i = 0; i < mNumOfSub; i++) {
            mCarrierView[i].setTextSize(unit, size);
        }
    }
    protected void updateCarrierText(long subId, State simState, CharSequence plmn, CharSequence spn) {
        if (DEBUG) Log.d(TAG, "updateCarrierText, simState=" + simState + " plmn=" + plmn
                + " spn=" + spn + " subId=" + subId);
        int slotId = mUpdateMonitor.getSlotIdUsingSubId(subId);
        if (!mUpdateMonitor.isValidSlotId(slotId)) {
            if (DEBUG) Log.d(TAG, "updateCarrierText, invalidate slotId=" + slotId);
            return;
        }

        CharSequence text = getCarrierTextForSimState(simState, plmn, spn);
        TextView updateCarrierView = mCarrierView[slotId];
        if (mUseAllCaps) {
            updateCarrierView.setText(text != null ? text.toString().toUpperCase() : null);
        } else {
            updateCarrierView.setText(text != null ? text.toString() : null);
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mSeparator = getResources().getString(R.string.kg_text_message_separator);
        final boolean screenOn = KeyguardUpdateMonitor.getInstance(mContext).isScreenOn();
        setLayerType(LAYER_TYPE_HARDWARE, null); // work around nested unclipped SaveLayer bug

        mCarrierView[0] = (TextView) findViewById(R.id.carrier_1);
        mCarrierView[1] = (TextView) findViewById(R.id.carrier_2);
        mCarrierView[2] = (TextView) findViewById(R.id.carrier_3);
        mCarrierView[3] = (TextView) findViewById(R.id.carrier_4);
        mCarrierDivider[0] = (TextView) findViewById(R.id.carrier_divider_1);
        mCarrierDivider[1] = (TextView) findViewById(R.id.carrier_divider_2);
        mCarrierDivider[2] = (TextView) findViewById(R.id.carrier_divider_3);
        mCarrierView[0].setSelected(screenOn);
        mCarrierView[1].setSelected(screenOn);
        mCarrierView[2].setSelected(screenOn);
        mCarrierView[3].setSelected(screenOn);

        for (int i = 0; i < mNumOfSub; i++) {
            mCarrierView[i].setVisibility(View.VISIBLE);
            if (i < mNumOfSub - 1) {
                mCarrierDivider[i].setVisibility(View.VISIBLE);
                mCarrierDivider[i].setText("|");
            }

        }

        if (mNumOfSub == 2) {
            mCarrierView[0].setGravity(Gravity.END);
            mCarrierView[1].setGravity(Gravity.START);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        KeyguardUpdateMonitor.getInstance(mContext).registerCallback(mCallback);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        KeyguardUpdateMonitor.getInstance(mContext).removeCallback(mCallback);
    }

    /**
     * Top-level function for creating carrier text. Makes text based on simState, PLMN
     * and SPN as well as device capabilities, such as being emergency call capable.
     *
     * @param simState
     * @param plmn
     * @param spn
     * @return
     */
    private CharSequence getCarrierTextForSimState(IccCardConstants.State simState,
            CharSequence plmn, CharSequence spn) {
        CharSequence carrierText = null;
        StatusMode status = getStatusForIccState(simState);
        if (DEBUG) Log.d(TAG, "getCarrierTextForSimState: status=" + status
                + " plmn=" + plmn + " spn=" + spn);
        switch (status) {
            case Normal:
                carrierText = concatenate(plmn, spn);
                break;

            case SimNotReady:
                carrierText = null; // nothing to display yet.
                break;

            case NetworkLocked:
                carrierText = makeCarrierStringOnEmergencyCapable(
                        mContext.getText(R.string.keyguard_network_locked_message), plmn);
                break;

            case SimMissing:
                // Shows "No SIM card | Emergency calls only" on devices that are voice-capable.
                // This depends on mPlmn containing the text "Emergency calls only" when the radio
                // has some connectivity. Otherwise, it should be null or empty and just show
                // "No SIM card"
                carrierText =  makeCarrierStringOnEmergencyCapable(
                        getContext().getText(R.string.keyguard_missing_sim_message_short),
                        plmn);
                break;

            case SimPermDisabled:
                carrierText = getContext().getText(
                        R.string.keyguard_permanent_disabled_sim_message_short);
                break;

            case SimMissingLocked:
                carrierText =  makeCarrierStringOnEmergencyCapable(
                        getContext().getText(R.string.keyguard_missing_sim_message_short),
                        plmn);
                break;

            case SimLocked:
                carrierText = makeCarrierStringOnEmergencyCapable(
                        getContext().getText(R.string.keyguard_sim_locked_message),
                        plmn);
                break;

            case SimPukLocked:
                carrierText = makeCarrierStringOnEmergencyCapable(
                        getContext().getText(R.string.keyguard_sim_puk_locked_message),
                        plmn);
                break;
        }

        if (DEBUG) Log.d(TAG, "getCarrierTextForSimState: carrierText=" + carrierText);
        return carrierText;
    }

    /*
     * Add emergencyCallMessage to carrier string only if phone supports emergency calls.
     */
    private CharSequence makeCarrierStringOnEmergencyCapable(
            CharSequence simMessage, CharSequence emergencyCallMessage) {
        if (mLockPatternUtils.isEmergencyCallCapable()) {
            return concatenate(simMessage, emergencyCallMessage);
        }
        return simMessage;
    }

    /**
     * Determine the current status of the lock screen given the SIM state and other stuff.
     */
    private StatusMode getStatusForIccState(IccCardConstants.State simState) {
        // Since reading the SIM may take a while, we assume it is present until told otherwise.
        if (simState == null) {
            return StatusMode.Normal;
        }

        final boolean missingAndNotProvisioned =
                !KeyguardUpdateMonitor.getInstance(mContext).isDeviceProvisioned()
                && (simState == IccCardConstants.State.ABSENT ||
                        simState == IccCardConstants.State.PERM_DISABLED);

        // Assume we're NETWORK_LOCKED if not provisioned
        simState = missingAndNotProvisioned ? IccCardConstants.State.NETWORK_LOCKED : simState;
        switch (simState) {
            case ABSENT:
                return StatusMode.SimMissing;
            case NETWORK_LOCKED:
                return StatusMode.SimMissingLocked;
            case NOT_READY:
                return StatusMode.SimNotReady;
            case PIN_REQUIRED:
                return StatusMode.SimLocked;
            case PUK_REQUIRED:
                return StatusMode.SimPukLocked;
            case READY:
                return StatusMode.Normal;
            case PERM_DISABLED:
                return StatusMode.SimPermDisabled;
            case UNKNOWN:
                return StatusMode.SimMissing;
        }
        return StatusMode.SimMissing;
    }

    private static CharSequence concatenate(CharSequence plmn, CharSequence spn) {
        final boolean plmnValid = !TextUtils.isEmpty(plmn);
        final boolean spnValid = !TextUtils.isEmpty(spn);
        if (plmnValid && spnValid) {
            if (plmn.equals(spn)) {
                return plmn;
            } else {
                return new StringBuilder().append(plmn).append(mSeparator).append(spn).toString();
            }
        } else if (plmnValid) {
            return plmn;
        } else if (spnValid) {
            return spn;
        } else {
            return "";
        }
    }

    private CharSequence getCarrierHelpTextForSimState(IccCardConstants.State simState,
            String plmn, String spn) {
        int carrierHelpTextId = 0;
        StatusMode status = getStatusForIccState(simState);
        switch (status) {
            case NetworkLocked:
                carrierHelpTextId = R.string.keyguard_instructions_when_pattern_disabled;
                break;

            case SimMissing:
                carrierHelpTextId = R.string.keyguard_missing_sim_instructions_long;
                break;

            case SimPermDisabled:
                carrierHelpTextId = R.string.keyguard_permanent_disabled_sim_instructions;
                break;

            case SimMissingLocked:
                carrierHelpTextId = R.string.keyguard_missing_sim_instructions;
                break;

            case Normal:
            case SimLocked:
            case SimPukLocked:
                break;
        }

        return mContext.getText(carrierHelpTextId);
    }
}
