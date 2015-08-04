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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.util.Log;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardSecurityModel;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardViewBase;
import com.android.keyguard.R;
import com.android.keyguard.ViewMediatorCallback;
import com.android.systemui.keyguard.KeyguardViewMediator;

import com.mediatek.keyguard.PowerOffAlarm.PowerOffAlarmManager ;

import static com.android.keyguard.KeyguardHostView.OnDismissAction;
import static com.android.keyguard.KeyguardSecurityModel.SecurityMode;

/**
 * A class which manages the bouncer on the lockscreen.
 */
public class KeyguardBouncer {

    private final String TAG = "KeyguardBouncer" ;
    private final boolean DEBUG = true ;

    private Context mContext;
    private ViewMediatorCallback mCallback;
    private LockPatternUtils mLockPatternUtils;
    private ViewGroup mContainer;
    private StatusBarWindowManager mWindowManager;
    private KeyguardViewBase mKeyguardView;
    private ViewGroup mRoot;
    private boolean mShowingSoon;
    private Choreographer mChoreographer = Choreographer.getInstance();

    /// M: use securityModel to check if it needs to show full screen mode
    private KeyguardSecurityModel mSecurityModel;

    private ViewGroup mNotificationPanel ;

    public KeyguardBouncer(Context context, ViewMediatorCallback callback,
            LockPatternUtils lockPatternUtils, StatusBarWindowManager windowManager,
            ViewGroup container) {
        mContext = context;
        mCallback = callback;
        mLockPatternUtils = lockPatternUtils;
        mContainer = container;
        mWindowManager = windowManager;
        mNotificationPanel = (ViewGroup) mContainer.findViewById(R.id.notification_panel);

        mSecurityModel = new KeyguardSecurityModel(mContext);
    }

    public void show() {
        if (PowerOffAlarmManager.isAlarmBoot()) {
            Log.d(TAG, "show() - this is alarm boot, just re-inflate.") ;
            inflateView() ;
        }
        show(false) ;
    }

    public void show(boolean authenticated) {
        if (DEBUG) Log.d(TAG, "show(authenticated = " + authenticated + ") is called.") ;

        ensureView();
        if (mRoot.getVisibility() == View.VISIBLE || mShowingSoon) {

            // show() updates the current security method. This is needed in case we are already
            // showing and the current security method changed.
            mKeyguardView.show();
            return;
        }

        // Try to dismiss the Keyguard. If no security pattern is set, this will dismiss the whole
        // Keyguard. If we need to authenticate, show the bouncer.
        if (!mKeyguardView.dismiss(authenticated)) {
            if (DEBUG) {
                Log.d(TAG, "show() - try to dismiss \"Bouncer\" directly.") ;
            }

            mShowingSoon = true;

            // Split up the work over multiple frames.
            mChoreographer.postCallbackDelayed(Choreographer.CALLBACK_ANIMATION, mShowRunnable,
                    null, 48);
        }
    }

    private final Runnable mShowRunnable = new Runnable() {
        @Override
        public void run() {
            Log.d(TAG, "mShowRunnable.run() is called.") ;
            mRoot.setVisibility(View.VISIBLE);
            mKeyguardView.onResume();
            mKeyguardView.startAppearAnimation();
            mShowingSoon = false;
        }
    };

    private void cancelShowRunnable() {
        mChoreographer.removeCallbacks(Choreographer.CALLBACK_ANIMATION, mShowRunnable, null);
        mShowingSoon = false;
    }

    public void showWithDismissAction(OnDismissAction r) {
        if (DEBUG) Log.d(TAG, "showWithDismissAction() is called.") ;
        ensureView();
        mKeyguardView.setOnDismissAction(r);
        show();
    }

    public void hide(boolean destroyView) {
        if (DEBUG) {
            Log.d(TAG, "hide() is called, destroyView = " + destroyView) ;
        }

        cancelShowRunnable();
        if (mKeyguardView != null) {
            mKeyguardView.setOnDismissAction(null);
            mKeyguardView.cleanUp();
        }

        if (destroyView) {
            if (DEBUG) Log.d(TAG, "call removeView()") ;
            removeView();
        } else if (mRoot != null) {
            if (DEBUG) Log.d(TAG, "just set keyguard Invisible.") ;
            mRoot.setVisibility(View.INVISIBLE);
        }

        /// M: [ALPS01748966] true place that user has left keyguard.
        // If the alternate unlock was suppressed, it can now be safely
        // enabled because the user has left keyguard.
        Log.d(TAG, "hide() - user has left keyguard, setAlternateUnlockEnabled(true)") ;
        KeyguardUpdateMonitor.getInstance(mContext).setAlternateUnlockEnabled(true);
    }


    /**
     * See {@link StatusBarKeyguardViewManager#startPreHideAnimation}.
     */
    public void startPreHideAnimation(Runnable runnable) {
        if (mKeyguardView != null) {
            mKeyguardView.startDisappearAnimation(runnable);
        } else if (runnable != null) {
            runnable.run();
        }
    }


    /**
     * Reset the state of the view.
     */
    public void reset() {
        cancelShowRunnable();
        inflateView();
    }

    public void onScreenTurnedOff() {
        if (mKeyguardView != null && mRoot != null && mRoot.getVisibility() == View.VISIBLE) {
            mKeyguardView.onScreenTurnedOff();
            mKeyguardView.onPause();
        }
    }

    public long getUserActivityTimeout() {
        if (mKeyguardView != null) {
            long timeout = mKeyguardView.getUserActivityTimeout();
            if (timeout >= 0) {
                return timeout;
            }
        }
        return KeyguardViewMediator.AWAKE_INTERVAL_DEFAULT_MS;
    }

    public boolean isShowing() {
        return mShowingSoon || (mRoot != null && mRoot.getVisibility() == View.VISIBLE);
    }

    public void prepare() {
        ensureView();
    }

    private void ensureView() {
        if (mRoot == null) {
            inflateView();
        }
    }

    private void inflateView() {
        if (DEBUG) Log.d(TAG, "inflateView() is called, we force to re-inflate the \"Bouncer\" view.") ;

        removeView();
        mRoot = (ViewGroup) LayoutInflater.from(mContext).inflate(R.layout.keyguard_bouncer, null);
        mKeyguardView = (KeyguardViewBase) mRoot.findViewById(R.id.keyguard_host_view);
        mKeyguardView.setLockPatternUtils(mLockPatternUtils);
        mKeyguardView.setViewMediatorCallback(mCallback);
        mKeyguardView.setNotificationPanelView(mNotificationPanel) ;
        mContainer.addView(mRoot, mContainer.getChildCount());
        mRoot.setVisibility(View.INVISIBLE);
        mRoot.setSystemUiVisibility(View.STATUS_BAR_DISABLE_HOME);
    }

    private void removeView() {
        if (mRoot != null && mRoot.getParent() == mContainer) {

            Log.d(TAG, "removeView() - really remove all views.") ;

            mContainer.removeView(mRoot);
            mRoot = null;
        }
    }

    public boolean onBackPressed() {
        return mKeyguardView != null && mKeyguardView.handleBackKey();
    }

    /**
     * @return True if and only if the current security method should be shown before showing
     *         the notifications on Keyguard, like SIM PIN/PUK.
     */
    public boolean needsFullscreenBouncer() {
        SecurityMode mode = mSecurityModel.getSecurityMode();
        return mode == SecurityMode.SimPinPukMe1
                || mode == SecurityMode.SimPinPukMe2
                || mode == SecurityMode.SimPinPukMe3
                || mode == SecurityMode.SimPinPukMe4
                || mode == SecurityMode.AntiTheft
                || mode == SecurityMode.AlarmBoot;
    }

    public boolean isSecure() {
        return mKeyguardView == null || mKeyguardView.getSecurityMode() != SecurityMode.None;
    }

    public boolean onMenuPressed() {
        ensureView();
        if (mKeyguardView.handleMenuKey()) {

            // We need to show it in case it is secure. If not, it will get dismissed in any case.
            mRoot.setVisibility(View.VISIBLE);
            mKeyguardView.requestFocus();
            mKeyguardView.onResume();
            return true;
        } else {
            return false;
        }
    }

    public boolean interceptMediaKey(KeyEvent event) {
        ensureView();
        return mKeyguardView.interceptMediaKey(event);
    }

    /**
     * @return True if mRoot view container is inflated.
     */
    public boolean isContainerInflated() {
        Log.d(TAG, "isContainerInflated() - ans is " + (mRoot != null)) ;
        return mRoot != null ;
    }
}
