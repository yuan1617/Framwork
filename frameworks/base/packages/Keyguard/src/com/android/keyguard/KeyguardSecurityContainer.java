/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Slog;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardSecurityModel.SecurityMode;
import com.android.keyguard.KeyguardHostView.OnDismissAction;

import com.mediatek.keyguard.ext.ILockScreenExt;
import com.mediatek.keyguard.ext.KeyguardPluginFactory;
import com.mediatek.keyguard.AntiTheft.AntiTheftManager ;
import com.mediatek.keyguard.Telephony.KeyguardSimPinPukMeView ;
import com.mediatek.keyguard.VoiceWakeup.VoiceWakeupManager ;

public class KeyguardSecurityContainer extends FrameLayout implements KeyguardSecurityView {
    private static final boolean DEBUG = KeyguardConstants.DEBUG;
    private static final String TAG = "KeyguardSecurityContainer";
    private KeyguardSecurityModel mSecurityModel;
    private boolean mEnableFallback; // TODO: This should get the value from KeyguardPatternView
    private LockPatternUtils mLockPatternUtils;

    private KeyguardSecurityViewFlipper mSecurityViewFlipper;
    private boolean mIsVerifyUnlockOnly;
    private SecurityMode mCurrentSecuritySelection = SecurityMode.Invalid;
    private boolean mIsBouncing;
    private SecurityCallback mSecurityCallback;

    private final KeyguardUpdateMonitor mUpdateMonitor;

    // M: add for LockScreen Ext
    ILockScreenExt mLockScreenExt = null;

    // Used to notify the container when something interesting happens.
    public interface SecurityCallback {
        public boolean dismiss(boolean authenticated);
        public void userActivity();
        public void onSecurityModeChanged(SecurityMode securityMode, boolean needsInput);
        public void finish();
        public void setOnDismissAction(OnDismissAction action);
        public boolean hasOnDismissAction() ;
        public void updateNavbarStatus() ;
    }

    public KeyguardSecurityContainer(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyguardSecurityContainer(Context context) {
        this(context, null, 0);
    }

    public KeyguardSecurityContainer(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mSecurityModel = new KeyguardSecurityModel(context);
        mLockPatternUtils = new LockPatternUtils(context);
        mUpdateMonitor = KeyguardUpdateMonitor.getInstance(mContext);

        /// M: init LockScreen plugin
        try {
            mLockScreenExt = KeyguardPluginFactory.getLockScreenExt(mContext);

            Log.d(TAG , "lock screen instance created in keyguard " + mLockScreenExt);
        } catch (Exception e) {
            Log.e(TAG , "exception: ", e);
        }
    }

    public void setSecurityCallback(SecurityCallback callback) {
        mSecurityCallback = callback;
    }

    @Override
    public void onResume(int reason) {
        if (DEBUG) Log.d(TAG, "onResume(reason = " + reason + ")") ;
        if (mCurrentSecuritySelection != SecurityMode.None) {
            getSecurityView(mCurrentSecuritySelection).onResume(reason);
        }
    }

    @Override
    public void onPause() {
        if (DEBUG) Log.d(TAG, "onPause()") ;
        if (mCurrentSecuritySelection != SecurityMode.None) {
            getSecurityView(mCurrentSecuritySelection).onPause();
        }
    }

    public void startAppearAnimation() {
        if (mCurrentSecuritySelection != SecurityMode.None) {
            getSecurityView(mCurrentSecuritySelection).startAppearAnimation();
        }
    }

    public boolean startDisappearAnimation(Runnable onFinishRunnable) {
        if (mCurrentSecuritySelection != SecurityMode.None) {
            return getSecurityView(mCurrentSecuritySelection).startDisappearAnimation(
                    onFinishRunnable);
        }
        return false;
    }

    void updateSecurityViews(boolean isBouncing) {
        int children = mSecurityViewFlipper.getChildCount();
        for (int i = 0; i < children; i++) {
            updateSecurityView(mSecurityViewFlipper.getChildAt(i), isBouncing);
        }
    }

    public void announceCurrentSecurityMethod() {
        View v = (View) getSecurityView(mCurrentSecuritySelection);
        if (v != null) {
            v.announceForAccessibility(v.getContentDescription());
        }
    }

    private KeyguardSecurityView getSecurityView(SecurityMode securityMode) {
        final int securityViewIdForMode = getSecurityViewIdForMode(securityMode);
        KeyguardSecurityView view = null;
        final int children = mSecurityViewFlipper.getChildCount();
        for (int child = 0; child < children; child++) {
            if (mSecurityViewFlipper.getChildAt(child).getId() == securityViewIdForMode) {
                view = ((KeyguardSecurityView)mSecurityViewFlipper.getChildAt(child));
                break;
            }
        }
        int layoutId = getLayoutIdFor(securityMode);
        if (view == null && layoutId != 0) {
            final LayoutInflater inflater = LayoutInflater.from(mContext);
            if (DEBUG) Log.v(TAG, "inflating id = " + layoutId);
            View v = inflater.inflate(layoutId, mSecurityViewFlipper, false);
            view = (KeyguardSecurityView)v;
            /// M: Use KeygaurdSimPinPukView for pin/puk, so set sim Id for it
            if (view instanceof KeyguardSimPinPukMeView) {
                KeyguardSimPinPukMeView pinPukView = (KeyguardSimPinPukMeView) view;
                final int subIndex = mSecurityModel.getSubIndexFromSecurityMode(securityMode);
                pinPukView.setSubId(mUpdateMonitor.getSubIdUsingSubIndex(subIndex));
            }
            mSecurityViewFlipper.addView(v);
            updateSecurityView(v, mIsBouncing);

        }
        ///M: mediatek add only for PIN1 and PIN2, in this case, we needn't recreate from the same layout file
        else if (view != null && (view instanceof KeyguardSimPinPukMeView)
                  && (securityMode != mCurrentSecuritySelection)) {
             Log.i(TAG, "getSecurityView, here, we will refresh the layout");
             KeyguardSimPinPukMeView pinPukView = (KeyguardSimPinPukMeView) view;
             final int subIndex = mSecurityModel.getSubIndexFromSecurityMode(securityMode);
             pinPukView.setSubId(mUpdateMonitor.getSubIdUsingSubIndex(subIndex));
        }

        return view;
    }

    private void updateSecurityView(View view, boolean isBouncing) {
        mIsBouncing = isBouncing;
        if (view instanceof KeyguardSecurityView) {
            KeyguardSecurityView ksv = (KeyguardSecurityView) view;
            ksv.setKeyguardCallback(mCallback);
            ksv.setLockPatternUtils(mLockPatternUtils);
            if (isBouncing) {
                ksv.showBouncer(0);
            } else {
                ksv.hideBouncer(0);
            }
        } else {
            Log.w(TAG, "View " + view + " is not a KeyguardSecurityView");
        }
    }

    protected void onFinishInflate() {
        mSecurityViewFlipper = (KeyguardSecurityViewFlipper) findViewById(R.id.view_flipper);
        mSecurityViewFlipper.setLockPatternUtils(mLockPatternUtils);
    }

    public void setLockPatternUtils(LockPatternUtils utils) {
        mLockPatternUtils = utils;
        mSecurityModel.setLockPatternUtils(utils);
        mSecurityViewFlipper.setLockPatternUtils(mLockPatternUtils);
    }

    private void showDialog(String title, String message) {
        final AlertDialog dialog = new AlertDialog.Builder(mContext)
            .setTitle(title)
            .setMessage(message)
            .setNeutralButton(R.string.ok, null)
            .create();
        if (!(mContext instanceof Activity)) {
            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
        }
        dialog.show();
    }

    private void showTimeoutDialog() {
        int timeoutInSeconds = (int) LockPatternUtils.FAILED_ATTEMPT_TIMEOUT_MS / 1000;
        int messageId = 0;

        switch (mSecurityModel.getSecurityMode()) {
            case Pattern:
                messageId = R.string.kg_too_many_failed_pattern_attempts_dialog_message;
                break;
            case PIN:
                messageId = R.string.kg_too_many_failed_pin_attempts_dialog_message;
                break;
            case Password:
                messageId = R.string.kg_too_many_failed_password_attempts_dialog_message;
                break;
            // These don't have timeout dialogs.
            case Account:
            case Biometric:
            case Invalid:
            case None:
            case SimPinPukMe1:
            case SimPinPukMe2:
            case SimPinPukMe3:
            case SimPinPukMe4:
                break;
        }

        if (messageId != 0) {
            final String message = mContext.getString(messageId,
                    KeyguardUpdateMonitor.getInstance(mContext).getFailedUnlockAttempts(),
                    timeoutInSeconds);
            showDialog(null, message);
        }
    }

    private void showAlmostAtWipeDialog(int attempts, int remaining) {
        String message = mContext.getString(R.string.kg_failed_attempts_almost_at_wipe,
                attempts, remaining);
        showDialog(null, message);
    }

    private void showWipeDialog(int attempts) {
        String message = mContext.getString(R.string.kg_failed_attempts_now_wiping, attempts);
        showDialog(null, message);
    }

    private void showAlmostAtAccountLoginDialog() {
        final int timeoutInSeconds = (int) LockPatternUtils.FAILED_ATTEMPT_TIMEOUT_MS / 1000;
        final int count = LockPatternUtils.FAILED_ATTEMPTS_BEFORE_RESET
                - LockPatternUtils.FAILED_ATTEMPTS_BEFORE_TIMEOUT;
        String message = mContext.getString(R.string.kg_failed_attempts_almost_at_login,
                count, LockPatternUtils.FAILED_ATTEMPTS_BEFORE_TIMEOUT, timeoutInSeconds);
        showDialog(null, message);
    }

    private void reportFailedUnlockAttempt() {
        final KeyguardUpdateMonitor monitor = KeyguardUpdateMonitor.getInstance(mContext);
        final int failedAttempts = monitor.getFailedUnlockAttempts() + 1; // +1 for this time

        if (DEBUG) Log.d(TAG, "reportFailedPatternAttempt: #" + failedAttempts);

        SecurityMode mode = mSecurityModel.getSecurityMode();
        final boolean usingPattern = mode == KeyguardSecurityModel.SecurityMode.Pattern;

        final int failedAttemptsBeforeWipe = mLockPatternUtils.getDevicePolicyManager()
                .getMaximumFailedPasswordsForWipe(null, mLockPatternUtils.getCurrentUser());

        final int failedAttemptWarning = LockPatternUtils.FAILED_ATTEMPTS_BEFORE_RESET
                - LockPatternUtils.FAILED_ATTEMPTS_BEFORE_TIMEOUT;

        final int remainingBeforeWipe = failedAttemptsBeforeWipe > 0 ?
                (failedAttemptsBeforeWipe - failedAttempts)
                : Integer.MAX_VALUE; // because DPM returns 0 if no restriction

        boolean showTimeout = false;
        if (remainingBeforeWipe < LockPatternUtils.FAILED_ATTEMPTS_BEFORE_WIPE_GRACE) {
            // The user has installed a DevicePolicyManager that requests a user/profile to be wiped
            // N attempts. Once we get below the grace period, we post this dialog every time as a
            // clear warning until the deletion fires.
            //
            // TODO: Show a different dialog depending on whether the device will be completely
            // wiped (i.e. policy is set for the primary profile of the USER_OWNER) or a single
            // secondary user or managed profile will be removed.
            if (remainingBeforeWipe > 0) {
                showAlmostAtWipeDialog(failedAttempts, remainingBeforeWipe);
            } else {
                // Too many attempts. The device will be wiped shortly.
                Slog.i(TAG, "Too many unlock attempts; device will be wiped!");
                showWipeDialog(failedAttempts);
            }
        } else {
            showTimeout =
                (failedAttempts % LockPatternUtils.FAILED_ATTEMPTS_BEFORE_TIMEOUT) == 0;
            if (usingPattern && mEnableFallback) {
                if (failedAttempts == failedAttemptWarning) {
                    showAlmostAtAccountLoginDialog();
                    showTimeout = false; // don't show both dialogs
                } else if (failedAttempts >= LockPatternUtils.FAILED_ATTEMPTS_BEFORE_RESET) {
                    mLockPatternUtils.setPermanentlyLocked(true);
                    showSecurityScreen(SecurityMode.Account);
                    // don't show timeout dialog because we show account unlock screen next
                    showTimeout = false;
                }
            }
        }
        monitor.reportFailedUnlockAttempt();
        mLockPatternUtils.reportFailedPasswordAttempt();
        if (showTimeout) {
            showTimeoutDialog();
        }
    }

    /**
     * Shows the primary security screen for the user. This will be either the multi-selector
     * or the user's security method.
     * @param turningOff true if the device is being turned off
     */
    void showPrimarySecurityScreen(boolean turningOff) {
        SecurityMode securityMode = mSecurityModel.getSecurityMode();
        if (DEBUG) Log.v(TAG, "showPrimarySecurityScreen(turningOff=" + turningOff + ")");
        if (!turningOff &&
                KeyguardUpdateMonitor.getInstance(mContext).isAlternateUnlockEnabled()) {
            // If we're not turning off, then allow biometric alternate.
            // We'll reload it when the device comes back on.
            securityMode = mSecurityModel.getAlternateFor(securityMode);
        }
        if (mSecurityModel.isSimPinPukSecurityMode(mCurrentSecuritySelection)
                && mSecurityModel.isSimPinPukSecurityMode(securityMode)) {
            if (DEBUG) {
                Log.d(TAG, "Skip show security because it already shows SimPinPukMeView");
            }
            return;
        }

        showSecurityScreen(securityMode);
    }

    /**
     * Shows the backup security screen for the current security mode.  This could be used for
     * password recovery screens but is currently only used for pattern unlock to show the
     * account unlock screen and biometric unlock to show the user's normal unlock.
     */
    private void showBackupSecurityScreen() {
        if (DEBUG) Log.d(TAG, "showBackupSecurity()");
        SecurityMode backup = mSecurityModel.getBackupSecurityMode(mCurrentSecuritySelection);
        showSecurityScreen(backup);
    }

    /**
     * Shows the next security screen if there is one.
     * @param authenticated true if the user entered the correct authentication
     * @param authenticated
     * @return true if keyguard is done
     */
    boolean showNextSecurityScreenOrFinish(boolean authenticated) {
        if (DEBUG) Log.d(TAG, "showNextSecurityScreenOrFinish(" + authenticated + ")");
        Log.d(TAG, "showNext.. mCurrentSecuritySelection = " + mCurrentSecuritySelection) ;
        boolean finish = false;
        if (mUpdateMonitor.getUserHasTrust(mLockPatternUtils.getCurrentUser())) {
            if (DEBUG) Log.d(TAG, "showNextSecurityScreenOrFinish() - getUserHasTrust() is True, just finish.") ;
            finish = true;
        } else if (SecurityMode.None == mCurrentSecuritySelection) {
            SecurityMode securityMode = mSecurityModel.getSecurityMode();
            // Allow an alternate, such as biometric unlock
            securityMode = mSecurityModel.getAlternateFor(securityMode);
            if (SecurityMode.None == securityMode) {
                if (DEBUG) Log.d(TAG, "showNextSecurityScreenOrFinish() - securityMode is None, just finish.") ;
                finish = true; // no security required
            } else {
                if (DEBUG) Log.d(TAG, "showNextSecurityScreenOrFinish() - switch to the alternate security view for None mode.") ;
                showSecurityScreen(securityMode); // switch to the alternate security view
            }
        } else if (authenticated) {
            if (DEBUG) Log.d(TAG, "showNextSecurityScreenOrFinish() - authenticated is True, and mCurrentSecuritySelection = " + mCurrentSecuritySelection) ;
            switch (mCurrentSecuritySelection) {
                case Pattern:
                case Password:
                case PIN:
                case Account:
                case Biometric:
                case Voice: //add for voice unlock
                    finish = true;
                    break;

                case SimPinPukMe1:
                case SimPinPukMe2:
                case SimPinPukMe3:
                case SimPinPukMe4:
                    // Shortcut for SIM PIN/PUK to go to directly to user's security screen or home
                    SecurityMode securityMode = mSecurityModel.getSecurityMode();
                    if (DEBUG) {
                        Log.v(TAG, "securityMode = " + securityMode);
                    }

                    if (securityMode != SecurityMode.None) {
                        showSecurityScreen(securityMode);
                    } else {
                        finish = true;
                    }
                    break;
                ///M: ALPS01772213 for handling antitheft mode.
                case AntiTheft:
                    SecurityMode nextMode = mSecurityModel.getSecurityMode();
                    if (DEBUG) {
                        Log.v(TAG, "now is Antitheft, next securityMode = " + nextMode);
                    }
                    if (nextMode != SecurityMode.None) {
                        showSecurityScreen(nextMode);
                    } else {
                        finish = true;
                    }
                    break;
                default:
                    Log.v(TAG, "Bad security screen " + mCurrentSecuritySelection + ", fail safe");
                    showPrimarySecurityScreen(false);
                    break;
            }
        } else {
            if (DEBUG) Log.d(TAG, "showNextSecurityScreenOrFinish() - authenticated is False, just show primary security view.") ;
            showPrimarySecurityScreen(false);
        }

        mSecurityCallback.updateNavbarStatus();

        if (finish) {
            mSecurityCallback.finish();
        }

        if (DEBUG) Log.d(TAG, "showNextSecurityScreenOrFinish() - return finish = " + finish) ;
        return finish;
    }

    /**
     * Switches to the given security view unless it's already being shown, in which case
     * this is a no-op.
     *
     * @param securityMode
     */
    private void showSecurityScreen(SecurityMode securityMode) {
        if (DEBUG) Log.d(TAG, "showSecurityScreen(" + securityMode + ")");

        ///M: Though we only have one "AntiTheft" mode, it in fact covers multiple different scenarios.
        ///   Ex: DM Lock, PPL Lock, etc. They may appear consecutively.
        ///   Ex: After dismissing DM Lock, the next security mode is PPL Lock.
        ///   They share same lock view file and same SecurityMode.AntiTheft,
        ///   but 2nd antitheft view cannot show except the 1st view since we will just end showing if  "securityMode == mCurrentSecuritySelection".
        ///   Add a new condition for AntiTheft mode to avoid the above mentioned case.
        if ((securityMode == mCurrentSecuritySelection)
                && (securityMode != SecurityMode.AntiTheft)) {
            return;
        }
        VoiceWakeupManager.getInstance().notifySecurityModeChange(mCurrentSecuritySelection, securityMode) ;

        KeyguardSecurityView oldView = getSecurityView(mCurrentSecuritySelection);
        KeyguardSecurityView newView = getSecurityView(securityMode);

        // Emulate Activity life cycle
        if (oldView != null) {
            oldView.onPause();
            oldView.setKeyguardCallback(mNullCallback); // ignore requests from old view
        }

        if (securityMode != SecurityMode.None) {
            /// M: fix ALPS01832185,
            ///    KeyguardAntiTheftView needs the latest mCallback before onResume.
            newView.setKeyguardCallback(mCallback);
            newView.onResume(KeyguardSecurityView.VIEW_REVEALED);
        }

        // Find and show this child.
        final int childCount = mSecurityViewFlipper.getChildCount();

        final int securityViewIdForMode = getSecurityViewIdForMode(securityMode);
        for (int i = 0; i < childCount; i++) {
            if (mSecurityViewFlipper.getChildAt(i).getId() == securityViewIdForMode) {
                mSecurityViewFlipper.setDisplayedChild(i);
                break;
            }
        }

        Log.d(TAG, "Before update, mCurrentSecuritySelection = " + mCurrentSecuritySelection) ;
        mCurrentSecuritySelection = securityMode;
        Log.d(TAG, "After update, mCurrentSecuritySelection = " + mCurrentSecuritySelection) ;
        mSecurityCallback.onSecurityModeChanged(securityMode,
                securityMode != SecurityMode.None && newView.needsInput());
    }

    private KeyguardSecurityViewFlipper getFlipper() {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof KeyguardSecurityViewFlipper) {
                return (KeyguardSecurityViewFlipper) child;
            }
        }
        return null;
    }

    public void showBouncer(int duration) {
        KeyguardSecurityViewFlipper flipper = getFlipper();
        if (flipper != null) {
            flipper.showBouncer(duration);
        }
    }

    public void hideBouncer(int duration) {
        KeyguardSecurityViewFlipper flipper = getFlipper();
        if (flipper != null) {
            flipper.hideBouncer(duration);
        }
    }

    private KeyguardSecurityCallback mCallback = new KeyguardSecurityCallback() {

        public void userActivity() {
            if (mSecurityCallback != null) {
                mSecurityCallback.userActivity();
            }
        }

        public void dismiss(boolean authenticated) {
            mSecurityCallback.dismiss(authenticated);
        }

        public boolean isVerifyUnlockOnly() {
            return mIsVerifyUnlockOnly;
        }

        public void reportUnlockAttempt(boolean success) {
            KeyguardUpdateMonitor monitor = KeyguardUpdateMonitor.getInstance(mContext);
            if (success) {
                monitor.clearFailedUnlockAttempts();
                mLockPatternUtils.reportSuccessfulPasswordAttempt();

                /// M: init DiglLayer unlock screen
                if (mLockScreenExt != null) {
                    //notify operator about screen successful unlock
                    mLockScreenExt.notifyUnlockedScreen();
                }
            } else {
                ///M: add for voice unlock
                if (mCurrentSecuritySelection == SecurityMode.Biometric
                    || mCurrentSecuritySelection == SecurityMode.Voice) {
                    monitor.reportFailedBiometricUnlockAttempt();
                } else {
                    KeyguardSecurityContainer.this.reportFailedUnlockAttempt();
                }
            }
        }

        @Override
        public void showBackupSecurity() {
            KeyguardSecurityContainer.this.showBackupSecurityScreen();
        }

        @Override
        public boolean hasOnDismissAction() {
            return mSecurityCallback.hasOnDismissAction();
        }

        @Override
        public void setOnDismissAction(OnDismissAction action) {
            mSecurityCallback.setOnDismissAction(action);
        }
    };

    // The following is used to ignore callbacks from SecurityViews that are no longer current
    // (e.g. face unlock). This avoids unwanted asynchronous events from messing with the
    // state for the current security method.
    private KeyguardSecurityCallback mNullCallback = new KeyguardSecurityCallback() {
        @Override
        public void userActivity() { }
        @Override
        public void showBackupSecurity() { }
        @Override
        public void reportUnlockAttempt(boolean success) { }
        @Override
        public boolean isVerifyUnlockOnly() { return false; }
        @Override
        public void dismiss(boolean securityVerified) { }

        @Override
        public void setOnDismissAction(OnDismissAction action) {
        }
        @Override
        public boolean hasOnDismissAction() { return false ; }
    };

    private int getSecurityViewIdForMode(SecurityMode securityMode) {
        switch (securityMode) {
            case Pattern: return R.id.keyguard_pattern_view;
            case PIN: return R.id.keyguard_pin_view;
            case Password: return R.id.keyguard_password_view;
            case Biometric: return R.id.keyguard_face_unlock_view;
            case Account: return R.id.keyguard_account_view;
            //case SimPin: return R.id.keyguard_sim_pin_view;
            //case SimPuk: return R.id.keyguard_sim_puk_view;
            case SimPinPukMe1:
            case SimPinPukMe2:
            case SimPinPukMe3:
            case SimPinPukMe4:
                return R.id.keyguard_sim_pin_puk_me_view ;
            /// M: power-off alarm @{
            case AlarmBoot: return R.id.power_off_alarm_view;
            /// @}
            ///M: add voice unlock view id
            case Voice: return R.id.voice_unlock_view;
            ///M: add anti-theft view id
            case AntiTheft: return AntiTheftManager.getAntiTheftViewId();
        }
        return 0;
    }

    private int getLayoutIdFor(SecurityMode securityMode) {
        switch (securityMode) {
            case Pattern: return R.layout.keyguard_pattern_view;
            case PIN: return R.layout.keyguard_pin_view;
            case Password: return R.layout.keyguard_password_view;
            case Biometric: return R.layout.keyguard_face_unlock_view;
            case Account: return R.layout.keyguard_account_view;
            //case SimPin: return R.layout.keyguard_sim_pin_view;
            //case SimPuk: return R.layout.keyguard_sim_puk_view;
            case SimPinPukMe1:
            case SimPinPukMe2:
            case SimPinPukMe3:
            case SimPinPukMe4:
                return R.layout.mtk_keyguard_sim_pin_puk_me_view ;
            /// M: power-off alarm @{
            case AlarmBoot: return R.layout.mtk_power_off_alarm_view;
            /// @}
            ///M: add voice unlock view layout
            case Voice: return R.layout.mtk_voice_unlock_view;
            ///M: add dmlock view layout
            case AntiTheft: return AntiTheftManager.getAntiTheftLayoutId() ;
            default:
                return 0;
        }
    }

    public SecurityMode getSecurityMode() {
        return mSecurityModel.getSecurityMode();
    }

    public void verifyUnlock() {
        mIsVerifyUnlockOnly = true;
        showSecurityScreen(getSecurityMode());
    }

    public SecurityMode getCurrentSecuritySelection() {
        return mCurrentSecuritySelection;
    }

    public void dismiss(boolean authenticated) {
        mCallback.dismiss(authenticated);
    }

    public boolean needsInput() {
        return mSecurityViewFlipper.needsInput();
    }

    @Override
    public void setKeyguardCallback(KeyguardSecurityCallback callback) {
        mSecurityViewFlipper.setKeyguardCallback(callback);
    }

    @Override
    public void reset() {
        mSecurityViewFlipper.reset();
    }

    @Override
    public KeyguardSecurityCallback getCallback() {
        return mSecurityViewFlipper.getCallback();
    }

    @Override
    public void showUsabilityHint() {
        mSecurityViewFlipper.showUsabilityHint();
    }

    /// M: Added for plug in feature
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        /// M: init Digl layout plugin
        if (mLockScreenExt != null) {
            //add operator lockscreen layout
            mLockScreenExt.initDgilLayout(mSecurityModel.getSecurityMode().ordinal(), SecurityMode.Pattern.ordinal(), mNotificatonPanelView);
        }
    }
    /// @}

    ///M: added for DiglLayout
    private ViewGroup mNotificatonPanelView ;
    public void setNotificationPanelView(ViewGroup notificationPanelView) {
        mNotificatonPanelView = notificationPanelView ;
    }

    /**
       * M: It's called when screen turned off.
       */
    public void onScreenTurnedOff() {
        if (DEBUG) {
            Log.d(TAG, "onScreenTurnedOff");
        }
    }
}

