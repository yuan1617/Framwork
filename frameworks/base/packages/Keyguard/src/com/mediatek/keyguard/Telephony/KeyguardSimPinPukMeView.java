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

package com.mediatek.keyguard.Telephony;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.INotificationManager;
import android.app.ITransientNotification;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.provider.Settings;
import android.telephony.SubscriptionManager;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManagerImpl;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.internal.telephony.ITelephony;
import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.PhoneConstants;

import com.android.keyguard.EmergencyCarrierArea ;
import com.android.keyguard.KeyguardPinBasedInputView ;
import com.android.keyguard.KeyguardSecurityModel;
import com.android.keyguard.KeyguardUpdateMonitor ;
import com.android.keyguard.KeyguardUpdateMonitorCallback ;
import com.android.keyguard.KeyguardUtils ;
import com.android.keyguard.R ;

import com.mediatek.internal.telephony.ITelephonyEx;
import com.mediatek.keyguard.ext.IKeyguardUtilExt;
import com.mediatek.keyguard.ext.KeyguardPluginFactory;
import com.mediatek.keyguard.ext.IOperatorSIMString;
import com.mediatek.keyguard.ext.IOperatorSIMString.SIMChangedTag;

/**
 * M: Displays a PIN/PUK pad for unlocking.
 */
public class KeyguardSimPinPukMeView extends KeyguardPinBasedInputView {
    private static final String TAG = "KeyguardSimPinPukMeView";
    private static final boolean DEBUG = true ;

    private ProgressDialog mSimUnlockProgressDialog = null;
    private volatile boolean mSimCheckInProgress;
    KeyguardUpdateMonitor mUpdateMonitor = null;

    private int mUnlockEnterState;

    private int mPinRetryCount;
    private int mPukRetryCount;

    private String mPukText;
    private String mNewPinText;
    private StringBuffer mSb = null;

    /// M: Save Sim Card dialog, we will close this dialog when phone state change to ringing or offhook
    private AlertDialog mSimCardDialog;

    /// M: wait next SIM ME state reflash flag
    private KeyguardSecurityModel mSecurityModel;
    private long mNextRepollStateSubId = 0;
    private IccCardConstants.State mLastSimState = IccCardConstants.State.UNKNOWN;

    static final int VERIFY_TYPE_PIN = 501;
    static final int VERIFY_TYPE_PUK = 502;

    // size limits for the pin.
    private static final int MIN_PIN_LENGTH = 4;
    private static final int MAX_PIN_LENGTH = 8;

    private static final int GET_SIM_RETRY_EMPTY = -1;

    private static final int STATE_ENTER_PIN = 0;
    private static final int STATE_ENTER_PUK = 1;
    private static final int STATE_ENTER_NEW = 2;
    private static final int STATE_REENTER_NEW = 3;
    private static final int STATE_ENTER_FINISH = 4;
    private static final int STATE_ENTER_ME = 5;
    private String[] strLockName = {" [NP]", " [NSP]", " [SP]", " [CP]", " [SIMP]"}; // Lock category name string Temp use for QA/RD
    private static final int SIMPINPUK_WAIT_STATE_CHANGE_TIMEOUT = 6000; //ms

    /// M: for get the proper SIM UIM string according to operator.
    private IOperatorSIMString mIOperatorSIMString;

    private long mSubId ;
    private KeyguardUtils mKeyguardUtils ;

    private Handler mHandler = new Handler(Looper.myLooper(), null, true /*async*/);

    /**
     * Used to dismiss SimPinPuk view after a delay
     */
    private Runnable mDismissSimPinPukRunnable = new Runnable() {
        public void run() {
            mUpdateMonitor.reportSimUnlocked(mSubId);
        }
    };

    KeyguardUpdateMonitorCallback mUpdateMonitorCallback = new KeyguardUpdateMonitorCallback() {
        @Override
        public void onSubIdUpdated(long oldSubId, long newSubId) {
            if (mSubId == oldSubId) {
                mSubId = newSubId;
                if (mUpdateMonitor.getSimStateOfSub(mSubId) != IccCardConstants.State.ABSENT) {
                    setSIMCardName();
                }
            }
        }

        @Override
        public void onSubInfoContentChanged(long subId, String column, String sValue, int iValue) {
            if (column != null && column.equals(SubscriptionManager.DISPLAY_NAME)
                    && mSubId == subId) {
                setSIMCardName();
            }
        }

        @Override
        public void onSimStateChangedUsingSubId(long subId, IccCardConstants.State simState) {
            Log.d(TAG, "onSimStateChangedUsingSubId: " + simState + ", subId = " + subId);

            if (subId == mSubId)
            {
                if (mSimUnlockProgressDialog != null) {
                    mSimUnlockProgressDialog.hide();
                }
                mHandler.removeCallbacks(mDismissSimPinPukRunnable);

                if (IccCardConstants.State.READY == simState) {
                    simStateReadyProcess();
                } else if (IccCardConstants.State.NOT_READY == simState ||
                        IccCardConstants.State.ABSENT == simState) {
                    // it will try next security screen or finish
                    mCallback.dismiss(true);
                } else if (IccCardConstants.State.NETWORK_LOCKED == simState) {
                    if (!KeyguardUtils.isMediatekSimMeLockSupport()) {
                        mCallback.dismiss(true);  // it will try next security screen or finish
                    } else if (0 == getRetryMeCount(mSubId)) { //permanently locked, exit
                        // do not show permanently locked dialog here, it will show in ViewMediator
                        Log.d(TAG, "onSimStateChanged: ME retrycount is 0, dismiss it");
                        mUpdateMonitor.setPinPukMeDismissFlagOfSub(subId, true);
                        mCallback.dismiss(true);
                    } else {
                        updateSubSimState();   // show next ME lock guiding
                    }
                } else if (IccCardConstants.State.PIN_REQUIRED == simState
                          || IccCardConstants.State.PUK_REQUIRED == simState) {
                    // reset pintext and show current sim state again
                    mPasswordEntry.reset(true);
                    mSecurityMessageDisplay.setMessage(mSb.toString(), true);
                }
                mLastSimState = simState;
                Log.d(TAG, "assign mLastSimState=" + mLastSimState);
            } else if (subId == mNextRepollStateSubId) {
                if (mSimUnlockProgressDialog != null) {
                    mSimUnlockProgressDialog.hide();
                }

                if (IccCardConstants.State.READY == simState) {
                    // pretend current sim is still ME lock state
                    mLastSimState = IccCardConstants.State.NETWORK_LOCKED;
                    simStateReadyProcess();
                } else {
                    // exit current SIM unlock to show next SIM unlock
                    mCallback.dismiss(true);
                    mLastSimState = simState;
                }
            }
        }

        ///M: fix ALPS01794428. SimPinPukMeView should disappear when Flight Mode turns on.
        @Override
        public void onAirPlaneModeChanged(boolean airPlaneModeEnabled) {
            Log.d(TAG, "onAirPlaneModeChanged(airPlaneModeEnabled = " + airPlaneModeEnabled + ")") ;
            if (airPlaneModeEnabled == true) {
                Log.d(TAG, "Flight-Mode turns on & keyguard is showing, dismiss keyguard.") ;

                // 1. dismiss keyguard
                mPasswordEntry.reset(true) ;
                mCallback.userActivity();
                mCallback.dismiss(true);
            }
        }            
    };

    public KeyguardSimPinPukMeView(Context context) {
        this(context, null);
    }

    public KeyguardSimPinPukMeView(Context context, AttributeSet attrs) {
        super(context, attrs);

        mKeyguardUtils = new KeyguardUtils(context);
        mSb = new StringBuffer();
        mUpdateMonitor = KeyguardUpdateMonitor.getInstance(getContext());
        mSecurityModel = new KeyguardSecurityModel(getContext());

        /// M: Init keyguard operator plugins @{
        try {
            mKeyguardUtilExt = KeyguardPluginFactory.getKeyguardUtilExt(context);
            mIOperatorSIMString = KeyguardPluginFactory.getOperatorSIMString(context);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setSubId(long subId) {

        mSubId = subId;
        Log.i(TAG, "setSubId=" + subId);

        updateSubSimState();

        /// M: A dialog set view to another one, it did not refresh displaying along with it,
        /// so dismiss it and set it to null.
        if (mSimCardDialog != null) {
            if (mSimCardDialog.isShowing()) {
                mSimCardDialog.dismiss();
            }
            mSimCardDialog = null;
        }
        setSIMCardName();
    }

    public void resetState() {
        super.resetState();
        mSecurityMessageDisplay.setMessage(R.string.kg_sim_pin_instructions, true);
    }

    @Override
    protected boolean shouldLockout(long deadline) {
        // SIM PIN doesn't have a timed lockout
        return false;
    }

    @Override
    protected int getPasswordTextViewId() {
        return R.id.simPinPukMeEntry;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mSubId = SubscriptionManager.INVALID_SUB_ID;
        if (mUpdateMonitor.getNumOfSubscription() > 1) {
            View simIcon = findViewById(R.id.sim_icon);
            if (simIcon != null) {
                simIcon.setVisibility(View.GONE);
            }
            View simInfoMsg = findViewById(R.id.sim_info_message);
            if (simInfoMsg != null) {
                simInfoMsg.setVisibility(View.VISIBLE);
            }
        }

        ///M: Dismiss button begin @{
        final Button dismissButton = (Button) findViewById(R.id.key_dismiss);
        if (dismissButton != null) {
            dismissButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Log.d(TAG, "dismissButton onClick, mSubId=" + mSubId);
                    mUpdateMonitor.setPinPukMeDismissFlagOfSub(mSubId, true);
                    mPasswordEntry.reset(true) ;
                    mCallback.userActivity();
                    mCallback.dismiss(true);

                    return;
                }
            });
        }
        dismissButton.setText(R.string.dismiss);
        /// @}

        mSecurityMessageDisplay.setTimeout(0); // don't show ownerinfo/charging status by default

        if (mEcaView instanceof EmergencyCarrierArea) {
            ((EmergencyCarrierArea) mEcaView).setCarrierTextVisible(true);
        }
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow() ;
        mUpdateMonitor.registerCallback(mUpdateMonitorCallback);
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mHandler.removeCallbacks(mDismissSimPinPukRunnable);
        mUpdateMonitor.removeCallback(mUpdateMonitorCallback);
    }

    @Override
    public void showUsabilityHint() {
    }

    @Override
    public void onResume(int reason) {
        if (mSimUnlockProgressDialog != null) {
            mSimUnlockProgressDialog.dismiss();
            mSimUnlockProgressDialog = null;
        }

        /// M: if has IME, then hide it @{
        InputMethodManager imm = ((InputMethodManager) mContext.
                getSystemService(Context.INPUT_METHOD_SERVICE));
        if (imm.isActive()) {
            Log.i(TAG, "IME is showing, we should hide it");
            imm.hideSoftInputFromWindow(this.getWindowToken(), InputMethodManager.HIDE_NOT_ALWAYS);
        }
        /// @}
    }

    @Override
    public void onPause() {
        if (mSimUnlockProgressDialog != null) {
            mSimUnlockProgressDialog.dismiss();
            mSimUnlockProgressDialog = null;
        }
    }

    private void setInputInvalidAlertDialog(CharSequence message, boolean shouldDisplay) {
        StringBuilder sb = new StringBuilder(message);

        if (shouldDisplay) {
            AlertDialog newDialog = new AlertDialog.Builder(mContext)
            .setMessage(sb)
            .setPositiveButton(com.android.internal.R.string.ok, null)
            .setCancelable(true)
            .create();

            newDialog.getWindow().setType(
                    WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
            newDialog.getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            newDialog.show();
        } else {
             Toast.makeText(mContext, sb).show();
        }
    }

    private String getRetryMeString(final long subId) {
        int meRetryCount = getRetryMeCount(subId);
        return "(" + mContext.getString(R.string.retries_left, meRetryCount) + ")";
    }

    private int getRetryMeCount(final long subId) {
        return mUpdateMonitor.getSimMeLeftRetryCountOfSub(subId);
    }

    private void minusRetryMeCount(final long subId) {
        mUpdateMonitor.minusSimMeLeftRetryCountOfSub(subId);
    }
    private String getRetryPuk(final long subId) {
        mPukRetryCount = mUpdateMonitor.getRetryPukCountOfSub(subId);
        switch (mPukRetryCount) {
        case GET_SIM_RETRY_EMPTY:
            return " ";
        default:
            return "(" + mContext.getString(R.string.retries_left, mPukRetryCount) + ")";
        }
    }
    private String getRetryPinString(final long subId) {
        mPinRetryCount = getRetryPinCount(subId);
        switch (mPinRetryCount) {
            case GET_SIM_RETRY_EMPTY:
                return " ";
            default:
                return "(" + mContext.getString(R.string.retries_left, mPinRetryCount) + ")";
        }
    }

    private int getRetryPinCount(final long subId) {
        int subIndex = mUpdateMonitor.getSubIndexUsingSubId(subId);
        if (subIndex == 3) {
            return SystemProperties.getInt("gsm.sim.retry.pin1.4", GET_SIM_RETRY_EMPTY);
        } else if (subIndex == 2) {
            return SystemProperties.getInt("gsm.sim.retry.pin1.3", GET_SIM_RETRY_EMPTY);
        } else if (subIndex == 1) {
            return SystemProperties.getInt("gsm.sim.retry.pin1.2", GET_SIM_RETRY_EMPTY);
        } else {
            return SystemProperties.getInt("gsm.sim.retry.pin1", GET_SIM_RETRY_EMPTY);
        }
    }

    private boolean validatePin(String pin, boolean isPUK) {
        // for pin, we have 4-8 numbers, or puk, we use only 8.
        int pinMinimum = isPUK ? MAX_PIN_LENGTH : MIN_PIN_LENGTH;
        // check validity
        if (pin == null || pin.length() < pinMinimum
                || pin.length() > MAX_PIN_LENGTH) {
            return false;
        } else {
            return true;
        }
    }

    private void updatePinEnterScreen() {

        switch (mUnlockEnterState) {
            case STATE_ENTER_PUK:
               mPukText = mPasswordEntry.getText().toString();
               if (validatePin(mPukText, true)) {
                  mUnlockEnterState = STATE_ENTER_NEW;
                  mSb.delete(0, mSb.length());
                  mSb.append(mContext.getText(R.string.keyguard_password_enter_new_pin_code));
                  mSecurityMessageDisplay.setMessage(mSb.toString(), true);
               } else {
                  mSecurityMessageDisplay.setMessage(R.string.invalidPuk, true);
               }
               break;

             case STATE_ENTER_NEW:
                 mNewPinText = mPasswordEntry.getText().toString();
                 if (validatePin(mNewPinText, false)) {
                    mUnlockEnterState = STATE_REENTER_NEW;
                    mSb.delete(0, mSb.length());
                    mSb.append(mContext.getText(R.string.keyguard_password_Confirm_pin_code));
                    mSecurityMessageDisplay.setMessage(mSb.toString(), true);
                 } else {
                    mSecurityMessageDisplay.setMessage(R.string.keyguard_code_length_prompt, true);
                 }
                 break;

             case STATE_REENTER_NEW:
                if (!mNewPinText.equals(mPasswordEntry.getText().toString())) {
                    mUnlockEnterState = STATE_ENTER_NEW;
                    mSb.delete(0, mSb.length());
                    mSb.append(mContext.getText(R.string.keyguard_code_donnot_mismatch));
                    mSb.append(mContext.getText(R.string.keyguard_password_enter_new_pin_code));
                    mSecurityMessageDisplay.setMessage(mSb.toString(), true);
                } else {
                   mUnlockEnterState = STATE_ENTER_FINISH;
                   mSecurityMessageDisplay.setMessage("", true);
                }
                break;

                default:
                    break;
        }
        mPasswordEntry.reset(true);
        mCallback.userActivity();
    }

    /**
     * Since the IPC can block, we want to run the request in a separate thread
     * with a callback.
     */
    private abstract class CheckSimPinPuk extends Thread {
        private final String mPin;
        private final String mPuk;
        private boolean mResult;

        protected CheckSimPinPuk(String pin) {
            mPin = pin;
            mPuk = null;
        }
        protected CheckSimPinPuk(String pin, long subId) {
            mPin = pin;
            mPuk = null;
        }

        protected CheckSimPinPuk(String puk, String pin, long subId) {
            mPin = pin;
            mPuk = puk;
        }

        abstract void onSimCheckResponse(boolean success);

        @Override
        public void run() {
            try {
                Log.d(TAG, "CheckSimPinPuk, " + "mSubId =" + mSubId);

                if (mUpdateMonitor.getSimStateOfSub(mSubId) ==
                    IccCardConstants.State.PIN_REQUIRED) {

                    ///M: fix ALPS01806988, avoid call function when phone service died.
                    ITelephony phoneService =
                        ITelephony.Stub.asInterface(ServiceManager.checkService("phone")) ;
                    if (phoneService != null) {
                        mResult = phoneService.supplyPinForSubscriber(mSubId, mPin);
                    } else {
                        Log.d(TAG, "phoneService is gone, skip supplyPinForSubscriber().") ;
                        mResult = false ;
                    }
                } else if (mUpdateMonitor.getSimStateOfSub(mSubId) ==
                           IccCardConstants.State.PUK_REQUIRED) {

                    ///M: fix ALPS01806988, avoid call function when phone service died.
                    ITelephony phoneService =
                        ITelephony.Stub.asInterface(ServiceManager.checkService("phone")) ;
                    if (phoneService != null) {
                        mResult = phoneService.supplyPukForSubscriber(mSubId, mPuk, mPin);
                    } else {
                        Log.d(TAG, "phoneService is gone, skip supplyPukForSubscriber().") ;
                        mResult = false ;
                    }
                }

                Log.d(TAG, "CheckSimPinPuk, " + "mSubId =" + mSubId + " mResult=" + mResult);


                if (mResult) {
                    // Create timer then wait for SIM_STATE_CHANGE for ready or network_lock
                    Log.d(TAG, "CheckSimPinPuk.run(), mResult is true(success), so we postDelayed a timeout runnable object");
                    mHandler.postDelayed(mDismissSimPinPukRunnable, SIMPINPUK_WAIT_STATE_CHANGE_TIMEOUT);
                }

                mHandler.post(new Runnable() {
                    public void run() {
                        onSimCheckResponse(mResult);
                    }
                });
            }
            catch (RemoteException e) {
                mHandler.post(new Runnable() {
                    public void run() {
                        onSimCheckResponse(false);
                    }
                });
            }
        }
    }

    /**
     * Since the IPC can block, we want to run the request in a separate thread
     * with a callback.
     */
    private static final int VERIFY_RESULT_PASS = 0;
    private static final int VERIFY_INCORRECT_PASSWORD = 1;
    private static final int VERIFY_RESULT_EXCEPTION = 2;

    private abstract class CheckSimMe extends Thread {
        private final String mPasswd;
        private int mResult;

        protected CheckSimMe(String passwd, long subId) {
            mPasswd = passwd;
        }
        abstract void onSimMeCheckResponse(final int ret);

        @Override
        public void run() {
            try {
                Log.d(TAG, "CheckMe, " + "mSubId =" + mSubId);
                mResult = ITelephonyEx.Stub.asInterface(ServiceManager.getService("phoneEx"))
                        .supplyNetworkDepersonalization(mSubId, mPasswd);
                Log.d(TAG, "CheckMe, " + "mSubId =" + mSubId + " mResult=" + mResult);

                if (VERIFY_RESULT_PASS == mResult) {
                    // Create timer then wait for SIM_STATE_CHANGE for ready or network_lock
                    Log.d(TAG, "CheckSimMe.run(), VERIFY_RESULT_PASS == ret,"
                            + " so we postDelayed a timeout runnable object");
                    mHandler.postDelayed(mDismissSimPinPukRunnable, SIMPINPUK_WAIT_STATE_CHANGE_TIMEOUT);
                }

                mHandler.post(new Runnable() {
                    public void run() {
                        onSimMeCheckResponse(mResult);
                    }
                });
            } catch (RemoteException e) {
                mHandler.post(new Runnable() {
                    public void run() {
                        onSimMeCheckResponse(VERIFY_RESULT_EXCEPTION);
                    }
                });
            }
        }
    }

    private Dialog getSimUnlockProgressDialog() {
        if (mSimUnlockProgressDialog == null) {
            mSimUnlockProgressDialog = new ProgressDialog(mContext);
            /// M: Change the String with SIM according to Operator. @{
            String msg = mContext.getString(R.string.kg_sim_unlock_progress_dialog_message);
            msg = mIOperatorSIMString.getOperatorSIMString(msg, -1, SIMChangedTag.DELSIM, mContext);
            mSimUnlockProgressDialog.setMessage(msg);
            /// @}
            mSimUnlockProgressDialog.setIndeterminate(true);
            mSimUnlockProgressDialog.setCancelable(false);
            if (!(mContext instanceof Activity)) {
                mSimUnlockProgressDialog.getWindow().setType(
                        WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
            }
        }
        return mSimUnlockProgressDialog;
    }

    @Override
    protected void verifyPasswordAndUnlock() {
        String entry = mPasswordEntry.getText().toString();

        ///M: here only for PIN code
        if ((false == validatePin(entry, false)) &&
            (mUpdateMonitor.getSimStateOfSub(mSubId) == IccCardConstants.State.PIN_REQUIRED
            || (mUpdateMonitor.getSimStateOfSub(mSubId) == IccCardConstants.State.NETWORK_LOCKED) &&
               KeyguardUtils.isMediatekSimMeLockSupport())
            ) {

            // otherwise, display a message to the user, and don't submit.
            if (mUpdateMonitor.getSimStateOfSub(mSubId) == IccCardConstants.State.PIN_REQUIRED) {
                mSecurityMessageDisplay.setMessage(R.string.kg_invalid_sim_pin_hint, true);
            }
            else {
                // hint to enter 4-8 digits for network_lock mode
                mSecurityMessageDisplay.setMessage(R.string.keyguard_code_length_prompt, true);
            }

            mPasswordEntry.reset(true);
            mCallback.userActivity();

            return;
        }
        dealWithPinOrPukUnlock();
    }

    private void dealWithPinOrPukUnlock() {
        if (mUpdateMonitor.getSimStateOfSub(mSubId) == IccCardConstants.State.PIN_REQUIRED) {
            Log.d(TAG, "onClick, check PIN, mSubId=" + mSubId);
            checkPin(mSubId);
        }
        else if (mUpdateMonitor.getSimStateOfSub(mSubId) == IccCardConstants.State.PUK_REQUIRED) {
            Log.d(TAG, "onClick, check PUK, mSubId=" + mSubId);
            checkPuk(mSubId);
        }
        else if ((mUpdateMonitor.getSimStateOfSub(mSubId) == IccCardConstants.State.NETWORK_LOCKED)
            && KeyguardUtils.isMediatekSimMeLockSupport()) {
            Log.d(TAG, "onClick, check ME, mSubId=" + mSubId);
            checkMe(mSubId);
        }
        else {
            Log.d(TAG, "wrong status, mSubId=" + mSubId);
        }
    }

    private void checkPin() {
        checkPin(mSubId);
    }

    private void checkPin(long subId) {
        getSimUnlockProgressDialog().show();
        if (!mSimCheckInProgress) {
            mSimCheckInProgress = true; // there should be only one
            new CheckSimPinPuk(mPasswordEntry.getText().toString(), subId) {
                void onSimCheckResponse(final boolean success) {
                    Log.d(TAG, "checkPin onSimLockChangedResponse, success = " + success);
                    if (success) {
                        int verify_type = VERIFY_TYPE_PIN ;
                        mKeyguardUtilExt.showToastWhenUnlockPinPuk(mContext, VERIFY_TYPE_PIN);

                    } else {
                        mSb.delete(0, mSb.length());

                        if (mSimUnlockProgressDialog != null) {
                            mSimUnlockProgressDialog.hide();
                        }
                        if (mUnlockEnterState == STATE_ENTER_PIN) {
                            mSb.append(mContext.getText(R.string.keyguard_wrong_code_input));
                            if (0 == getRetryPinCount(mSubId)) { //goto PUK
                                mPinRetryCount = 0;
                                mSb.append(mContext.getText(R.string.keyguard_password_entering_puk_code));
                                mSb.append(" " + getRetryPuk(mSubId));
                                mUnlockEnterState = STATE_ENTER_PUK;
                            } else {
                                mSb.append(mContext.getText(R.string.keyguard_password_enter_pin_code));
                                mSb.append(" " + getRetryPinString(mSubId));
                            }
                            mSecurityMessageDisplay.setMessage(mSb.toString(), true);
                            mPasswordEntry.reset(true);
                        } else if (mUnlockEnterState == STATE_ENTER_PUK) {
                            mSb.append(mContext.getText(R.string.keyguard_wrong_code_input));
                            if (0 == mUpdateMonitor.getRetryPukCountOfSub(mSubId)) { //goto PUK
                                mSb.append(mContext.getText(R.string.keyguard_password_entering_puk_code));
                                mSb.append(" " + getRetryPuk(mSubId));
                                mUnlockEnterState = STATE_ENTER_PUK;
                            } else {
                                mSb.append(mContext.getText(R.string.keyguard_password_enter_pin_code));
                                mSb.append(" " + getRetryPinString(mSubId));
                            }
                            mSecurityMessageDisplay.setMessage(mSb.toString(), true);
                            mPasswordEntry.reset(true);
                        }
                    }
                    mCallback.userActivity();
                    mSimCheckInProgress = false;
                }
            } .start();
        }
    }

    private void checkPuk() {
        checkPuk(mSubId);
    }

    private void checkPuk(long subId) {
        updatePinEnterScreen();
        if (mUnlockEnterState != STATE_ENTER_FINISH) {
            return;
        }
        getSimUnlockProgressDialog().show();
        if (!mSimCheckInProgress) {
            mSimCheckInProgress = true; // there should be only one
            new CheckSimPinPuk(mPukText, mNewPinText, subId) {
                void onSimCheckResponse(final boolean success) {
                    Log.d(TAG, "checkPuk onSimLockChangedResponse, success = " + success);
                    if (success) {
                        Log.d(TAG, "checkPuk onSimCheckResponse, success!");
                        int verify_type = VERIFY_TYPE_PUK ;
                        mKeyguardUtilExt.showToastWhenUnlockPinPuk(mContext, VERIFY_TYPE_PUK);
                    } else {
                        mSb.delete(0, mSb.length());

                        if (mSimUnlockProgressDialog != null) {
                            mSimUnlockProgressDialog.hide();
                        }
                        int retryCount = mUpdateMonitor.getRetryPukCountOfSub(mSubId) ;
                        boolean countChange = (mPukRetryCount != retryCount);
                        String retryInfo = getRetryPuk(mSubId);
                        setSIMCardName();
                        mSb.append(mContext.getText(R.string.keyguard_password_entering_puk_code));
                        mSb.append(" " + retryInfo);
                        mSecurityMessageDisplay.setMessage(mSb.toString(), true);
                        mPasswordEntry.reset(true);
                        mUnlockEnterState = STATE_ENTER_PUK;
                        if (retryCount != 0) {
                            if (countChange) {
                                setInputInvalidAlertDialog(mContext
                                        .getString(R.string.keyguard_password_wrong_puk_code)
                                        + retryInfo, false);
                            } else {
                                setInputInvalidAlertDialog(mContext.getString(R.string.lockscreen_pattern_wrong), false);
                            }
                        } else {
                            setInputInvalidAlertDialog(mContext.getString(R.string.sim_permanently_locked), true);
                            mUpdateMonitor.setPinPukMeDismissFlagOfSub(mSubId, true);
                            mCallback.dismiss(true);
                        }
                    }
                    mCallback.userActivity();
                    mSimCheckInProgress = false;
                }
            } .start();
        }
    }

    private void checkMe() {
        checkMe(mSubId);
    }

    private void checkMe(long subId) {
        getSimUnlockProgressDialog().show();
        if (!mSimCheckInProgress) {
            mSimCheckInProgress = true; // there should be only one
            new CheckSimMe(mPasswordEntry.getText().toString(), subId) {
                void onSimMeCheckResponse(final int ret) {
                    Log.d(TAG, "checkMe onSimChangedResponse, ret = " + ret);
                    if (VERIFY_RESULT_PASS == ret) {
                        Log.d(TAG, "checkMe VERIFY_RESULT_PASS == ret(we had sent runnable before");
                    } else if (VERIFY_INCORRECT_PASSWORD == ret) {
                        mSb.delete(0, mSb.length());
                        minusRetryMeCount(mSubId);

                        if (mSimUnlockProgressDialog != null) {
                            mSimUnlockProgressDialog.hide();
                        }
                        if (mUnlockEnterState == STATE_ENTER_ME) {
                            if (0 == getRetryMeCount(mSubId)) { //permanently locked
                                setInputInvalidAlertDialog(mContext.getText(R.string.simlock_slot_locked_message), true);
                                mUpdateMonitor.setPinPukMeDismissFlagOfSub(mSubId, true);
                                mCallback.dismiss(true);
                            } else {
                                int category = mUpdateMonitor.getSimMeCategoryOfSub(mSubId);
                                mSb.append(mContext.getText(R.string.keyguard_wrong_code_input));
                                mSb.append(mContext.getText(R.string.simlock_entersimmelock));
                                mSb.append(strLockName[category] + getRetryMeString(mSubId));
                            }
                            mSecurityMessageDisplay.setMessage(mSb.toString(), true);
                            mPasswordEntry.reset(true);
                        }
                    } else if (VERIFY_RESULT_EXCEPTION == ret) {
                        if (mSimUnlockProgressDialog != null) {
                            mSimUnlockProgressDialog.hide();
                        }
                        setInputInvalidAlertDialog("*** Exception happen, fail to unlock", true);
                        mUpdateMonitor.setPinPukMeDismissFlagOfSub(mSubId, true);
                        mCallback.dismiss(true);
                    }
                    mCallback.userActivity();
                    mSimCheckInProgress = false;
                }
            } .start();
        }
    }

    /**
     * M: set the content of ForText of SIMInfoView.
     */
    private void setForTextDetectingCard(long subId, TextView forText) {
        StringBuffer forSb = new StringBuffer();
        int slotId = mUpdateMonitor.getSlotIdUsingSubId(subId);

        forSb.append(mContext.getString(R.string.kg_slot_id, slotId + 1));
        forSb.append(" ");
        forSb.append(mContext.getString(R.string.kg_detecting_simcard));
        forText.setText(forSb.toString());
    }

    private void setSIMCardName() {
        String operName = null;

        try {
            operName = mKeyguardUtils.getOptrNameUsingSubId(mSubId, mContext);
        } catch (IndexOutOfBoundsException e) {
            Log.w(TAG, "getOptrNameBySlot exception, mSubId=" + mSubId);
        }
        if (DEBUG) {
            Log.i(TAG, "setSIMCardName, mSubId=" + mSubId + ", operName=" + operName);
            }
        TextView forText = (TextView) findViewById(R.id.for_text);
        ImageView subIcon = (ImageView) findViewById(R.id.sub_icon);
        TextView simCardName = (TextView) findViewById(R.id.sim_card_name);

        if (null == operName) { //this is the new SIM card inserted, show detecting
            if (DEBUG) Log.d(TAG, "mSubId " + mSubId + " is new subInfo record");
            setForTextDetectingCard(mSubId, forText);
            subIcon.setVisibility(View.GONE);
            simCardName.setVisibility(View.GONE);
        } else {
            int slotId = mUpdateMonitor.getSlotIdUsingSubId(mSubId);
            forText.setText(mContext.getString(R.string.kg_slot_id, slotId + 1) + " ");
            simCardName.setText(operName);
            Drawable iconDrawable = mKeyguardUtils.getOptrDrawableUsingSubId(mSubId, mContext);
            subIcon.setImageDrawable(iconDrawable);
            subIcon.setVisibility(View.VISIBLE);
            simCardName.setVisibility(View.VISIBLE);
        }
    }

    /**
     * M: Show input guding for different SIM locks.
     */
    public void updateSubSimState() {
        setSIMCardName();

        mSb.delete(0, mSb.length());
        IccCardConstants.State state = mUpdateMonitor.getSimStateOfSub(mSubId);
        if (IccCardConstants.State.PUK_REQUIRED == state) {
           mSb.append(mContext.getText(R.string.keyguard_password_entering_puk_code));
           mSb.append(" " + getRetryPuk(mSubId));
           mUnlockEnterState = STATE_ENTER_PUK;
        } else if (IccCardConstants.State.PIN_REQUIRED == state) {
           mSb.append(mContext.getText(R.string.keyguard_password_enter_pin_code));
           mSb.append(" " + getRetryPinString(mSubId));
           mUnlockEnterState = STATE_ENTER_PIN;
        } else if ((IccCardConstants.State.NETWORK_LOCKED == state) &&
                    KeyguardUtils.isMediatekSimMeLockSupport()) {
           int category = mUpdateMonitor.getSimMeCategoryOfSub(mSubId);
           mSb.append(mContext.getText(R.string.simlock_entersimmelock));
           mSb.append(strLockName[category] + getRetryMeString(mSubId));
           mUnlockEnterState = STATE_ENTER_ME;
        }

        mPasswordEntry.reset(true);
        mSecurityMessageDisplay.setMessage(mSb.toString(), true);
    }

    /* M: Override hideBouncer function to reshow message string */
    @Override
    public void hideBouncer(int duration) {
        mSecurityMessageDisplay.setMessage(mSb.toString(), true);
        super.hideBouncer(duration);
    }

    ///M: the process after receive SIM_STATE READY event
    /// call repollIccStateForNetworkLock if next locked SIM card is ME lock
    private void simStateReadyProcess() {
        mNextRepollStateSubId = getNextRepollStateSubId();
        Log.d(TAG, "simStateReadyProcess mNextRepollStateSubId=" + mNextRepollStateSubId);
        if (mNextRepollStateSubId != SubscriptionManager.INVALID_SUB_ID) {
            try {
                getSimUnlockProgressDialog().show();
                Log.d(TAG, "repollIccStateForNetworkLock " + "subId =" + mNextRepollStateSubId);

                ///M: call repollIccStateForNetworkLock will trigger telephony to resend
                /// sim_ state_change event of specified sim id
                ITelephonyEx.Stub.asInterface(ServiceManager.getService("phoneEx"))
                    .repollIccStateForNetworkLock(mNextRepollStateSubId, true);
            } catch (RemoteException e) {
                Log.d(TAG, "repollIccStateForNetworkLock exception caught");
            }
        } else {
            mCallback.dismiss(true);  // it will try next security screen or finish
        }
    }

    /// M: check next subscription lock state is ME lock or not
    /// return subId if we found otherwise return 0
    private long getNextRepollStateSubId() {
        if ((IccCardConstants.State.NETWORK_LOCKED == mLastSimState) &&
             KeyguardUtils.isMediatekSimMeLockSupport()) {
            for (int i = 0; i < mUpdateMonitor.getNumOfSubscription(); i++) {
                long subId = mUpdateMonitor.getSubIdUsingSubIndex(i);
                if (!mSecurityModel.isPinPukOrMeRequiredOfSubId(subId)) {
                    continue;
                }

                final IccCardConstants.State simState = mUpdateMonitor.getSimStateOfSub(subId);
                if (simState == IccCardConstants.State.NETWORK_LOCKED) {
                    return subId;
                } else {
                    break;  // for PIN or PUK lock, return INVALID_SUB_ID
                }
            }
        }
        return SubscriptionManager.INVALID_SUB_ID ;
    }

    public static class Toast {
        static final String LOCAL_TAG = "Toast";
        static final boolean LOCAL_LOGV = false;

        final Handler mHandler = new Handler();
        final Context mContext;
        final TN mTN;
        int mGravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
        int mY;
        View mView;

        public Toast(Context context) {
            mContext = context;
            mTN = new TN();
            mY = context.getResources().getDimensionPixelSize(com.android.internal.R.dimen.toast_y_offset);
        }

        public static Toast makeText(Context context, CharSequence text) {
            Toast result = new Toast(context);

            LayoutInflater inflate = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            View v = inflate.inflate(com.android.internal.R.layout.transient_notification, null);
            TextView tv = (TextView) v.findViewById(com.android.internal.R.id.message);
            tv.setText(text);

            result.mView = v;

            return result;
        }

        /**
         * Show the view for the specified duration.
         */
        public void show() {
            if (mView == null) {
                throw new RuntimeException("setView must have been called");
            }
            INotificationManager service = getService();
            String pkg = mContext.getPackageName();
            TN tn = mTN;
            try {
                service.enqueueToast(pkg, tn, 0);
            } catch (RemoteException e) {
                // Empty
            }
        }

        /**
         * Close the view if it's showing, or don't show it if it isn't showing yet. You do not normally have to call this.
         * Normally view will disappear on its own after the appropriate duration.
         */
        public void cancel() {
            mTN.hide();
        }

        private INotificationManager mService;

        private INotificationManager getService() {
            if (mService != null) {
                return mService;
            }
            mService = INotificationManager.Stub.asInterface(ServiceManager.getService("notification"));
            return mService;
        }

        private class TN extends ITransientNotification.Stub {
            final Runnable mShow = new Runnable() {
                public void run() {
                    handleShow();
                }
            };

            final Runnable mHide = new Runnable() {
                public void run() {
                    handleHide();
                }
            };

            private final WindowManager.LayoutParams mParams = new WindowManager.LayoutParams();

            WindowManagerImpl mWM;

            TN() {
                final WindowManager.LayoutParams params = mParams;
                params.height = WindowManager.LayoutParams.WRAP_CONTENT;
                params.width = WindowManager.LayoutParams.WRAP_CONTENT;
                params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
                params.format = PixelFormat.TRANSLUCENT;
                params.windowAnimations = com.android.internal.R.style.Animation_Toast;
                params.type = WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG;
                params.setTitle("Toast");
            }

            /**
             * schedule handleShow into the right thread
             */
            public void show() {
                if (LOCAL_LOGV) {
                    Log.d(LOCAL_TAG, "SHOW: " + this);
                }
                mHandler.post(mShow);
            }

            /**
             * schedule handleHide into the right thread
             */
            public void hide() {
                if (LOCAL_LOGV) {
                    Log.d(LOCAL_TAG, "HIDE: " + this);
                }
                mHandler.post(mHide);
            }

            public void handleShow() {
                if (LOCAL_LOGV) {
                    Log.d(LOCAL_TAG, "HANDLE SHOW: " + this + " mView=" + mView);
                }

                mWM = (WindowManagerImpl) mContext.getSystemService(Context.WINDOW_SERVICE);
                final int gravity = mGravity;
                mParams.gravity = gravity;
                if ((gravity & Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.FILL_HORIZONTAL) {
                    mParams.horizontalWeight = 1.0f;
                }
                if ((gravity & Gravity.VERTICAL_GRAVITY_MASK) == Gravity.FILL_VERTICAL) {
                    mParams.verticalWeight = 1.0f;
                }
                mParams.y = mY;
                if (mView != null) {
                    if (mView.getParent() != null) {
                        if (LOCAL_LOGV) {
                            Log.d(LOCAL_TAG, "REMOVE! " + mView + " in " + this);
                        }
                        mWM.removeView(mView);
                    }
                    if (LOCAL_LOGV) {
                        Log.d(LOCAL_TAG, "ADD! " + mView + " in " + this);
                    }
                    mWM.addView(mView, mParams);
                }
            }

            public void handleHide() {
                if (LOCAL_LOGV) {
                    Log.d(LOCAL_TAG, "HANDLE HIDE: " + this + " mView=" + mView);
                }
                if (mView != null) {
                    // note: checking parent() just to make sure the view has
                    // been added... i have seen cases where we get here when
                    // the view isn't yet added, so let's try not to crash.
                    if (mView.getParent() != null) {
                        if (LOCAL_LOGV) {
                            Log.d(LOCAL_TAG, "REMOVE! " + mView + " in " + this);
                        }
                        mWM.removeView(mView);
                    }

                    mView = null;
                }
            }
        }
    }
    /// M: Mediatek added variable for Operation plugin feature
    private IKeyguardUtilExt mKeyguardUtilExt;

    /// M: [ALPS00830104] Refresh the information while the window focus is changed.
    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        if (hasWindowFocus) {
            IccCardConstants.State state = mUpdateMonitor.getSimStateOfSub(mSubId);
            int pinRetryCount = getRetryPinCount(mSubId);
            int pukRetryCount = mUpdateMonitor.getRetryPukCountOfSub(mSubId) ;
            if ((mUnlockEnterState == STATE_ENTER_PIN && mPinRetryCount != pinRetryCount)
                || (mUnlockEnterState == STATE_ENTER_PUK && mPukRetryCount != pukRetryCount)) {
                updateSubSimState();
            }

            KeyguardUtils.requestImeStatusRefresh(mContext) ;
        }
    }

    //Newly added function in Android L
    @Override
    public void startAppearAnimation() {
        // noop.
    }

   @Override
    public boolean startDisappearAnimation(Runnable finishRunnable) {
        return false;
    }

}

