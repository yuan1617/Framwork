package com.mediatek.keyguard.VoiceUnlock ;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.media.AudioSystem ;
import android.telephony.TelephonyManager;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;

import com.android.internal.widget.LockPatternUtils;

import com.android.keyguard.BiometricSensorUnlock ;
import com.android.keyguard.KeyguardSecurityView ;
import com.android.keyguard.KeyguardSecurityCallback ;
import com.android.keyguard.KeyguardMessageArea ;
import com.android.keyguard.KeyguardUpdateMonitor ;
import com.android.keyguard.KeyguardUpdateMonitorCallback ;
import com.android.keyguard.KeyguardUtils;
import com.android.keyguard.KeyguardSecurityViewHelper ;
import com.android.keyguard.R ;
import com.android.keyguard.SecurityMessageDisplay ;

public class KeyguardVoiceUnlockView extends LinearLayout implements KeyguardSecurityView {

    private static final String TAG = "KeyguardVoiceUnlockView";
    private static final boolean DEBUG = true;

    private KeyguardSecurityCallback mKeyguardSecurityCallback;
    private LockPatternUtils mLockPatternUtils;
    private BiometricSensorUnlock mBiometricUnlock;
    private View mVoiceUnlockAreaView;
    private SecurityMessageDisplay mSecurityMessageDisplay;
    private View mEcaView;
    private Drawable mBouncerFrame;

    private boolean mIsBouncerVisibleToUser = false;
    private final Object mIsBouncerVisibleToUserLock = new Object();

    public KeyguardVoiceUnlockView(Context context) {
        this(context, null);
    }

    public KeyguardVoiceUnlockView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        initializeBiometricUnlockView();

        mSecurityMessageDisplay = new KeyguardMessageArea.Helper(this);
        mEcaView = findViewById(R.id.keyguard_selector_fade_container);
        View bouncerFrameView = findViewById(R.id.keyguard_bouncer_frame);
        if (bouncerFrameView != null) {
            mBouncerFrame = bouncerFrameView.getBackground();
        }
    }

    @Override
    public void setKeyguardCallback(KeyguardSecurityCallback callback) {
        mKeyguardSecurityCallback = callback;
        // TODO: formalize this in the interface or factor it out
        ((VoiceUnlock) mBiometricUnlock).setKeyguardCallback(callback);
    }

    @Override
    public void setLockPatternUtils(LockPatternUtils utils) {
        mLockPatternUtils = utils;
    }

    @Override
    public void reset() {

    }

    @Override
    public void onDetachedFromWindow() {
        log("onDetachedFromWindow()");
        if (mBiometricUnlock != null) {
            mBiometricUnlock.stop();
        }
        KeyguardUpdateMonitor.getInstance(mContext).removeCallback(mUpdateCallback);
    }

    @Override
    public void onPause() {
        log("onPause()");
        if (mBiometricUnlock != null) {
            mBiometricUnlock.stop();
        }
        mSecurityMessageDisplay.setMessage(" ", true);
        KeyguardUpdateMonitor.getInstance(mContext).removeCallback(mUpdateCallback);
    }

    @Override
    public void onResume(int reason) {
        log("onResume()");
        synchronized (mIsBouncerVisibleToUserLock) {
            mIsBouncerVisibleToUser = isBouncerVisibleToUser();
        }
        if (!KeyguardUpdateMonitor.getInstance(mContext).isSwitchingUser()) {
            maybeStartBiometricUnlock();
        }
        KeyguardUpdateMonitor.getInstance(mContext).registerCallback(mUpdateCallback);
    }

    @Override
    public boolean needsInput() {
        return false;
    }

    @Override
    public KeyguardSecurityCallback getCallback() {
        return mKeyguardSecurityCallback;
    }

    private void initializeBiometricUnlockView() {
        log("initializeBiometricUnlockView()");
        /// M: [ALPS01748966] hide the entire voice unlock view.
        mVoiceUnlockAreaView = findViewById(R.id.voice_unlock_view);
        if (mVoiceUnlockAreaView != null) {
            mBiometricUnlock = new VoiceUnlock(mContext, this);
            mBiometricUnlock.initializeView(mVoiceUnlockAreaView);
        } else {
            log("Couldn't find biometric unlock view");
        }
    }

    /**
     * Starts the biometric unlock if it should be started based on a number of factors.  If it
     * should not be started, it either goes to the back up, or remains showing to prepare for
     * it being started later.
     */
    private void maybeStartBiometricUnlock() {
        log("maybeStartBiometricUnlock() is called.");
        if (mBiometricUnlock != null) {
            KeyguardUpdateMonitor monitor = KeyguardUpdateMonitor.getInstance(mContext);
            final boolean backupIsTimedOut = (
                    monitor.getFailedUnlockAttempts() >=
                    LockPatternUtils.FAILED_ATTEMPTS_BEFORE_TIMEOUT);
            final boolean mediaPlaying = AudioSystem.isStreamActive(AudioSystem.STREAM_MUSIC, 0)
                    || AudioSystem.isStreamActive(AudioSystem.STREAM_ALARM, 0);

            boolean isBouncerVisibleToUser;
            synchronized (mIsBouncerVisibleToUserLock) {
                isBouncerVisibleToUser = mIsBouncerVisibleToUser;
            }

            // Don't start it if the bouncer is not showing, but keep this view up because we want
            // it here and ready for when the bouncer does show.
            if (!isBouncerVisibleToUser) {
                if (mediaPlaying) {
                    log("maybeStartBiometricUnlock() - isBouncerVisibleToUser is false" +
                        " && mediaPlaying is true, call mBiometricUnlock.stopAndShowBackup()") ;
                    mBiometricUnlock.stopAndShowBackup();
                } else {
                    log("maybeStartBiometricUnlock() - isBouncerVisibleToUser is false," +
                        " call mBiometricUnlock.stop()") ;
                    mBiometricUnlock.stop(); // It shouldn't be running but calling this can't hurt.
                }
                return;
            }

            // Although these same conditions are handled in KeyguardSecurityModel, they are still
            // necessary here.  When a tablet is rotated 90 degrees, a configuration change is
            // triggered and everything is torn down and reconstructed.  That means
            // KeyguardSecurityModel gets a chance to take care of the logic and doesn't even
            // reconstruct KeyguardFaceUnlockView if the biometric unlock should be suppressed.
            // However, for a 180 degree rotation, no configuration change is triggered, so only
            // the logic here is capable of suppressing Face Unlock.
            if (monitor.getPhoneState() == TelephonyManager.CALL_STATE_IDLE
                    && monitor.isAlternateUnlockEnabled()
                    && !monitor.getMaxBiometricUnlockAttemptsReached()
                    && !backupIsTimedOut
                    && !mediaPlaying) {
                mBiometricUnlock.start();
            } else {
                log("maybeStartBiometricUnlock() - call stopAndShowBackup()") ;
                mBiometricUnlock.stopAndShowBackup();
            }
        }
    }

    // Returns true if the device is currently in a state where the user is seeing the bouncer.
    // This requires isKeyguardBouncer() to be true, but that doesn't imply that the screen is on or
    // the keyguard visibility is set to true, so we must check those conditions as well.
    private boolean isBouncerVisibleToUser() {
        KeyguardUpdateMonitor updateMonitor = KeyguardUpdateMonitor.getInstance(mContext);
        return updateMonitor.isKeyguardBouncer() && updateMonitor.isKeyguardVisible() &&
                updateMonitor.isScreenOn();
    }

    // Starts the biometric unlock if the bouncer was not previously visible to the user, but is now
    // visibile to the user.  Stops the biometric unlock if the bouncer was previously visible to
    // the user, but is no longer visible to the user.
    private void handleBouncerUserVisibilityChanged() {
        boolean wasBouncerVisibleToUser;
        synchronized (mIsBouncerVisibleToUserLock) {
            wasBouncerVisibleToUser = mIsBouncerVisibleToUser;
            mIsBouncerVisibleToUser = isBouncerVisibleToUser();
        }

        log("wasBouncerVisibleToUser = " + wasBouncerVisibleToUser +
            " , mIsBouncerVisibleToUser = " + mIsBouncerVisibleToUser) ;

        if (mBiometricUnlock != null) {
            if (wasBouncerVisibleToUser && !mIsBouncerVisibleToUser) {
                log("handleBouncerUserVisibilityChanged() - " +
                    "wasBouncerVisibleToUser && !mIsBouncerVisibleToUser") ;
                mBiometricUnlock.stop();
            } else if (!wasBouncerVisibleToUser && mIsBouncerVisibleToUser) {
                log("handleBouncerUserVisibilityChanged() - " +
                    "!wasBouncerVisibleToUser && mIsBouncerVisibleToUser") ;
                maybeStartBiometricUnlock();
            }
        }
    }

    KeyguardUpdateMonitorCallback mUpdateCallback = new KeyguardUpdateMonitorCallback() {
        // We need to stop the biometric unlock when a phone call comes in
        @Override
        public void onPhoneStateChanged(int phoneState) {
            log("onPhoneStateChanged(" + phoneState + ")");
            if (phoneState == TelephonyManager.CALL_STATE_RINGING) {
                if (mBiometricUnlock != null) {
                    mBiometricUnlock.stopAndShowBackup();
                }
            }
        }

        @Override
        public void onUserSwitching(int userId) {
            log("onUserSwitching(" + userId + ")");
            if (mBiometricUnlock != null) {
                mBiometricUnlock.stop();
            }
            // No longer required; static value set by KeyguardViewMediator
            // mLockPatternUtils.setCurrentUser(userId);
        }

        @Override
        public void onUserSwitchComplete(int userId) {
            if (DEBUG) Log.d(TAG, "onUserSwitchComplete(" + userId + ")");
            if (mBiometricUnlock != null) {
                maybeStartBiometricUnlock();
            }
        }

        @Override
        public void onKeyguardVisibilityChanged(boolean showing) {
            if (DEBUG) Log.d(TAG, "onKeyguardVisibilityChanged(" + showing + ")");
            handleBouncerUserVisibilityChanged();
        }

        @Override
        public void onKeyguardBouncerChanged(boolean bouncer) {
            if (DEBUG) Log.d(TAG, "onKeyguardBouncerChanged(" + bouncer + ")");
            handleBouncerUserVisibilityChanged();
        }

        @Override
        public void onScreenTurnedOn() {
            if (DEBUG) Log.d(TAG, "onScreenTurnedOn()");
            handleBouncerUserVisibilityChanged();
        }

        @Override
        public void onScreenTurnedOff(int why) {
            if (DEBUG) Log.d(TAG, "onScreenTurnedOff()");
            handleBouncerUserVisibilityChanged();
        }
    };

    @Override
    public void showUsabilityHint() {
    }

    @Override
    public void showBouncer(int duration) {
        KeyguardSecurityViewHelper.
                showBouncer(mSecurityMessageDisplay, mEcaView, mBouncerFrame, duration);
    }

    @Override
    public void hideBouncer(int duration) {
        KeyguardSecurityViewHelper.
                hideBouncer(mSecurityMessageDisplay, mEcaView, mBouncerFrame, duration);
    }

    @Override
    public void startAppearAnimation() {
        // TODO.
    }

    @Override
    public boolean startDisappearAnimation(Runnable finishRunnable) {
        return false;
    }

    private void log(String msg) {
        if (DEBUG) {
            Log.d(TAG, "KeyguardVoiceUnlockView: " + msg);
        }
    }
}
