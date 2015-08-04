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

import com.android.internal.telephony.ITelephony;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.IccCardConstants;

import android.content.Context;
import android.app.AlertDialog;
import android.app.AlertDialog.Builder;
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

/**
 * Displays a PIN pad for unlocking.
 */
public class KeyguardSimPinView extends KeyguardPinBasedInputView {
    private static final String LOG_TAG = "KeyguardSimPinView";
    private static final boolean DEBUG = KeyguardConstants.DEBUG;
    public static final String TAG = "KeyguardSimPinView";

    private ProgressDialog mSimUnlockProgressDialog = null;
    private CheckSimPin mCheckSimPinThread;

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

    public KeyguardSimPinView(Context context) {
        this(context, null);
    }

    public KeyguardSimPinView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mUpdateMonitor = KeyguardUpdateMonitor.getInstance(getContext());
        mKeyguardUtils = new KeyguardUtils(context);
    }

    public void resetState() {
        super.resetState();
        mSecurityMessageDisplay.setMessage(R.string.kg_sim_pin_instructions, true);
    }

    private String getPinPasswordErrorMessage(int attemptsRemaining) {
        String displayMessage;

        if (attemptsRemaining == 0) {
            displayMessage = getContext().getString(R.string.kg_password_wrong_pin_code_pukked);
        } else if (attemptsRemaining > 0) {
            displayMessage = getContext().getResources()
                    .getQuantityString(R.plurals.kg_password_wrong_pin_code, attemptsRemaining,
                            attemptsRemaining);
        } else {
            displayMessage = getContext().getString(R.string.kg_password_pin_failed);
        }
        if (DEBUG) Log.d(LOG_TAG, "getPinPasswordErrorMessage:"
                + " attemptsRemaining=" + attemptsRemaining + " displayMessage=" + displayMessage);
        return displayMessage;
    }

    @Override
    protected boolean shouldLockout(long deadline) {
        // SIM PIN doesn't have a timed lockout
        return false;
    }

    @Override
    protected int getPasswordTextViewId() {
        return R.id.simPinEntry;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mSubId = mUpdateMonitor.getSimPinLockSubId();
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
    private abstract class CheckSimPin extends Thread {
        private final String mPin;

        protected CheckSimPin(String pin) {
            mPin = pin;
        }

        abstract void onSimCheckResponse(final int result, final int attemptsRemaining);

        @Override
        public void run() {
            try {
                Log.v(TAG, "call supplyPinReportResultForSubscriber() mSubId = " + mSubId);
                final int[] result = ITelephony.Stub.asInterface(ServiceManager
                        .checkService("phone")).supplyPinReportResultForSubscriber(mSubId, mPin);
                Log.v(TAG, "supplyPinReportResultForSubscriber returned: " + result[0] + " " + result[1]);
                post(new Runnable() {
                    public void run() {
                        onSimCheckResponse(result[0], result[1]);
                    }
                });
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException for supplyPinReportResultForSubscriber:", e);
                post(new Runnable() {
                    public void run() {
                        onSimCheckResponse(PhoneConstants.PIN_GENERAL_FAILURE, -1);
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
            mSimUnlockProgressDialog.getWindow().setType(
                    WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
        }
        return mSimUnlockProgressDialog;
    }

    private Dialog getSimRemainingAttemptsDialog(int remaining) {
        String msg = getPinPasswordErrorMessage(remaining);
        if (mRemainingAttemptsDialog == null) {
            Builder builder = new AlertDialog.Builder(mContext);
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

    @Override
    protected void verifyPasswordAndUnlock() {
        String entry = mPasswordEntry.getText();

        if (entry.length() < 4) {
            // otherwise, display a message to the user, and don't submit.
            mSecurityMessageDisplay.setMessage(R.string.kg_invalid_sim_pin_hint, true);
            resetPasswordText(true);
            mCallback.userActivity();
            return;
        }

        getSimUnlockProgressDialog().show();

        if (mCheckSimPinThread == null) {
            mCheckSimPinThread = new CheckSimPin(mPasswordEntry.getText()) {
                void onSimCheckResponse(final int result, final int attemptsRemaining) {
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
                                        getSimRemainingAttemptsDialog(attemptsRemaining).show();
                                    } else {
                                        // show message
                                        mSecurityMessageDisplay.setMessage(
                                                getPinPasswordErrorMessage(attemptsRemaining), true);
                                    }
                                } else {
                                    // "PIN operation failed!" - no idea what this was and no way to
                                    // find out. :/
                                    mSecurityMessageDisplay.setMessage(getContext().getString(
                                            R.string.kg_password_pin_failed), true);
                                }
                                if (DEBUG) Log.d(LOG_TAG, "verifyPasswordAndUnlock "
                                        + " CheckSimPin.onSimCheckResponse: " + result
                                        + " attemptsRemaining=" + attemptsRemaining);
                                resetPasswordText(true /* animate */);
                            }
                            mCallback.userActivity();
                            mCheckSimPinThread = null;
                        }
                    });
                }
            };
            mCheckSimPinThread.start();
        }
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

