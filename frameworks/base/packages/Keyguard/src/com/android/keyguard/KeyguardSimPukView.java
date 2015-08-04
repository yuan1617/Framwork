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
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.graphics.drawable.Drawable;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.telephony.SubscriptionManager;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.internal.telephony.ITelephony;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.IccCardConstants;


/**
 * Displays a PIN pad for entering a PUK (Pin Unlock Kode) provided by a carrier.
 */
public class KeyguardSimPukView extends KeyguardPinBasedInputView {
    private static final String LOG_TAG = "KeyguardSimPukView";
    private static final boolean DEBUG = KeyguardConstants.DEBUG;
    public static final String TAG = "KeyguardSimPukView";

    private ProgressDialog mSimUnlockProgressDialog = null;
    private CheckSimPuk mCheckSimPukThread;
    private String mPukText;
    private String mPinText;
    private StateMachine mStateMachine = new StateMachine();
    private AlertDialog mRemainingAttemptsDialog;
    KeyguardUpdateMonitor mUpdateMonitor;
    KeyguardUtils mKeyguardUtils;
    private long mSubId = 0;

    private KeyguardUpdateMonitorCallback mUpdateCallback = new KeyguardUpdateMonitorCallback() {
        @Override
        public void onSubIdUpdated(long oldSubId, long newSubId) {
            if (mSubId == oldSubId) {
                mSubId = newSubId;
                dealwithSIMInfoChanged();
            }
        }

        @Override
        public void onSubInfoContentChanged(long subId, String column,
                String sValue, int iValue) {
            if (column != null && column.equals(SubscriptionManager.DISPLAY_NAME)
                    && mSubId == subId) {
                dealwithSIMInfoChanged();
            }
        }

        @Override
        public void onSimStateChangedUsingSubId(long subId, IccCardConstants.State simState) {
            if (DEBUG) Log.d(TAG, "onSimStateChangedUsingSubId: " + simState + ", subId=" + subId);

            switch (simState) {
                case NOT_READY:
                case ABSENT:
                    if (subId == mSubId) {
                        mUpdateMonitor.reportSimUnlocked(mSubId);
                        mCallback.dismiss(true);
                    }
                    break;
            }
        }
    };

    private class StateMachine {
        final int ENTER_PUK = 0;
        final int ENTER_PIN = 1;
        final int CONFIRM_PIN = 2;
        final int DONE = 3;
        private int state = ENTER_PUK;

        public void next() {
            int msg = 0;
            if (state == ENTER_PUK) {
                if (checkPuk()) {
                    state = ENTER_PIN;
                    msg = R.string.kg_puk_enter_pin_hint;
                } else {
                    msg = R.string.kg_invalid_sim_puk_hint;
                }
            } else if (state == ENTER_PIN) {
                if (checkPin()) {
                    state = CONFIRM_PIN;
                    msg = R.string.kg_enter_confirm_pin_hint;
                } else {
                    msg = R.string.kg_invalid_sim_pin_hint;
                }
            } else if (state == CONFIRM_PIN) {
                if (confirmPin()) {
                    state = DONE;
                    msg = R.string.keyguard_sim_unlock_progress_dialog_message;
                    updateSim();
                } else {
                    state = ENTER_PIN; // try again?
                    msg = R.string.kg_invalid_confirm_pin_hint;
                }
            }
            resetPasswordText(true);
            if (msg != 0) {
                mSecurityMessageDisplay.setMessage(msg, true);
            }
        }

        void reset() {
            mPinText="";
            mPukText="";
            state = ENTER_PUK;
            mSecurityMessageDisplay.setMessage(R.string.kg_puk_enter_puk_hint, true);
            mPasswordEntry.requestFocus();
        }
    }

    private String getPukPasswordErrorMessage(int attemptsRemaining) {
        String displayMessage;

        if (attemptsRemaining == 0) {
            displayMessage = getContext().getString(R.string.kg_password_wrong_puk_code_dead);
        } else if (attemptsRemaining > 0) {
            displayMessage = getContext().getResources()
                    .getQuantityString(R.plurals.kg_password_wrong_puk_code, attemptsRemaining,
                            attemptsRemaining);
        } else {
            displayMessage = getContext().getString(R.string.kg_password_puk_failed);
        }
        if (DEBUG) Log.d(LOG_TAG, "getPukPasswordErrorMessage:"
                + " attemptsRemaining=" + attemptsRemaining + " displayMessage=" + displayMessage);
        return displayMessage;
    }

    public KeyguardSimPukView(Context context) {
        this(context, null);
    }

    public KeyguardSimPukView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mUpdateMonitor = KeyguardUpdateMonitor.getInstance(getContext());
        mKeyguardUtils = new KeyguardUtils(context);
    }

    public void resetState() {
        super.resetState();
        mStateMachine.reset();
    }

    @Override
    protected boolean shouldLockout(long deadline) {
        // SIM PUK doesn't have a timed lockout
        return false;
    }

    @Override
    protected int getPasswordTextViewId() {
        return R.id.pukEntry;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mSubId = mUpdateMonitor.getSimPukLockSubId();
        if (mUpdateMonitor.getNumOfSubscription() > 1) {
            View simIcon = findViewById(R.id.sim_icon);
            if (simIcon != null) {
                simIcon.setVisibility(View.GONE);
            }
            View simInfoMsg = findViewById(R.id.sim_info_message);
            if (simInfoMsg != null) {
                simInfoMsg.setVisibility(View.VISIBLE);
            }
            dealwithSIMInfoChanged();
        }

        mSecurityMessageDisplay.setTimeout(0); // don't show ownerinfo/charging status by default
        if (mEcaView instanceof EmergencyCarrierArea) {
            ((EmergencyCarrierArea) mEcaView).setCarrierTextVisible(true);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mUpdateMonitor.registerCallback(mUpdateCallback);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mUpdateMonitor.removeCallback(mUpdateCallback);
    }

    @Override
    public void showUsabilityHint() {
    }

    @Override
    public void onPause() {
        // dismiss the dialog.
        if (mSimUnlockProgressDialog != null) {
            mSimUnlockProgressDialog.dismiss();
            mSimUnlockProgressDialog = null;
        }
    }

    /**
     * Since the IPC can block, we want to run the request in a separate thread
     * with a callback.
     */
    private abstract class CheckSimPuk extends Thread {

        private final String mPin, mPuk;

        protected CheckSimPuk(String puk, String pin) {
            mPuk = puk;
            mPin = pin;
        }

        abstract void onSimLockChangedResponse(final int result, final int attemptsRemaining);

        @Override
        public void run() {
            try {
                Log.v(TAG, "call supplyPukReportResultForSubscriber() mSubId = " + mSubId);
                final int[] result = ITelephony.Stub.asInterface(ServiceManager
                        .checkService("phone")).supplyPukReportResultForSubscriber(mSubId, mPuk, mPin);
                Log.v(TAG, "supplyPukReportResultForSubscriber returned: " + result[0] + " " + result[1]);
                post(new Runnable() {
                    public void run() {
                        onSimLockChangedResponse(result[0], result[1]);
                    }
                });
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException for supplyPukReportResultForSubscriber:", e);
                post(new Runnable() {
                    public void run() {
                        onSimLockChangedResponse(PhoneConstants.PIN_GENERAL_FAILURE, -1);
                    }
                });
            }
        }
    }

    private Dialog getSimUnlockProgressDialog() {
        if (mSimUnlockProgressDialog == null) {
            mSimUnlockProgressDialog = new ProgressDialog(mContext);
            mSimUnlockProgressDialog.setMessage(
                    mContext.getString(R.string.kg_sim_unlock_progress_dialog_message));
            mSimUnlockProgressDialog.setIndeterminate(true);
            mSimUnlockProgressDialog.setCancelable(false);
            if (!(mContext instanceof Activity)) {
                mSimUnlockProgressDialog.getWindow().setType(
                        WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
            }
        }
        return mSimUnlockProgressDialog;
    }

    private Dialog getPukRemainingAttemptsDialog(int remaining) {
        String msg = getPukPasswordErrorMessage(remaining);
        if (mRemainingAttemptsDialog == null) {
            AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
            builder.setMessage(msg);
            builder.setCancelable(false);
            builder.setNeutralButton(R.string.ok, null);
            mRemainingAttemptsDialog = builder.create();
            mRemainingAttemptsDialog.getWindow().setType(
                    WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
        } else {
            mRemainingAttemptsDialog.setMessage(msg);
        }
        return mRemainingAttemptsDialog;
    }

    private boolean checkPuk() {
        // make sure the puk is at least 8 digits long.
        if (mPasswordEntry.getText().length() == 8) {
            mPukText = mPasswordEntry.getText();
            return true;
        }
        return false;
    }

    private boolean checkPin() {
        // make sure the PIN is between 4 and 8 digits
        int length = mPasswordEntry.getText().length();
        if (length >= 4 && length <= 8) {
            mPinText = mPasswordEntry.getText();
            return true;
        }
        return false;
    }

    public boolean confirmPin() {
        return mPinText.equals(mPasswordEntry.getText());
    }

    private void updateSim() {
        getSimUnlockProgressDialog().show();

        if (mCheckSimPukThread == null) {
            mCheckSimPukThread = new CheckSimPuk(mPukText, mPinText) {
                void onSimLockChangedResponse(final int result, final int attemptsRemaining) {
                    post(new Runnable() {
                        public void run() {
                            if (mSimUnlockProgressDialog != null) {
                                mSimUnlockProgressDialog.hide();
                            }
                            if (result == PhoneConstants.PIN_RESULT_SUCCESS) {
                                mUpdateMonitor.reportSimUnlocked(mSubId);
                                mCallback.dismiss(true);
                            } else {
                                if (result == PhoneConstants.PIN_PASSWORD_INCORRECT) {
                                    if (attemptsRemaining <= 2) {
                                        // this is getting critical - show dialog
                                        getPukRemainingAttemptsDialog(attemptsRemaining).show();
                                    } else {
                                        // show message
                                        mSecurityMessageDisplay.setMessage(
                                                getPukPasswordErrorMessage(attemptsRemaining), true);
                                    }
                                } else {
                                    mSecurityMessageDisplay.setMessage(getContext().getString(
                                            R.string.kg_password_puk_failed), true);
                                }
                                if (DEBUG) Log.d(LOG_TAG, "verifyPasswordAndUnlock "
                                        + " UpdateSim.onSimCheckResponse: "
                                        + " attemptsRemaining=" + attemptsRemaining);
                                mStateMachine.reset();
                            }
                            mCheckSimPukThread = null;
                        }
                    });
                }
            };
            mCheckSimPukThread.start();
        }
    }

    @Override
    protected void verifyPasswordAndUnlock() {
        mStateMachine.next();
    }

    @Override
    public void startAppearAnimation() {
        // noop.
    }

    @Override
    public boolean startDisappearAnimation(Runnable finishRunnable) {
        return false;
    }

    private void dealwithSIMInfoChanged() {
        String operName = null;

        try {
            operName = mKeyguardUtils.getOptrNameUsingSubId(mSubId, mContext);
        } catch (IndexOutOfBoundsException e) {
            Log.w(TAG, "getOptrNameBySlot exception, mSubId=" + mSubId);
        }
        if (DEBUG) Log.i(TAG, "dealwithSIMInfoChanged, mSubId=" + mSubId + ", operName=" + operName);
        TextView forText = (TextView) findViewById(R.id.for_text);
        ImageView subIcon = (ImageView) findViewById(R.id.sub_icon);
        TextView simCardName = (TextView) findViewById(R.id.sim_card_name);
        if (null == operName) { //this is the new SIM card inserted
            if (DEBUG) Log.d(TAG, "mSubId " + mSubId + " is new subInfo record");
            setForTextNewCard(mSubId, forText);
            subIcon.setVisibility(View.GONE);
            simCardName.setVisibility(View.GONE);
        } else {
            int slotId = mUpdateMonitor.getSlotIdUsingSubId(mSubId);
            if (DEBUG) Log.d(TAG, "dealwithSIMInfoChanged, show operName for slotId=" + slotId);
            forText.setText(mContext.getString(R.string.kg_slot_id, slotId + 1) + " ");
            simCardName.setText(null == operName ?
                    mContext.getString(R.string.kg_detecting_simcard) : operName);
            Drawable iconDrawable = mKeyguardUtils.getOptrDrawableUsingSubId(mSubId, mContext);
            subIcon.setImageDrawable(iconDrawable);
            subIcon.setVisibility(View.VISIBLE);
            simCardName.setVisibility(View.VISIBLE);
        }
    }

    private void setForTextNewCard(long subId, TextView forText) {
        StringBuffer forSb = new StringBuffer();
        int slotIndex = mUpdateMonitor.getSlotIdUsingSubId(subId);

        forSb.append(mContext.getString(R.string.kg_slot_id, slotIndex + 1));
        forSb.append(" ");
        forSb.append(mContext.getText(R.string.kg_new_simcard));
        forText.setText(forSb.toString());
    }
}

