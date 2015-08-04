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
package com.mediatek.keyguard.PowerOffAlarm ;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.AttributeSet;
import android.util.Log;
import android.view.IWindowManager;
import android.view.KeyEvent ;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.multiwaveview.GlowPadView;
import com.android.keyguard.KeyguardSecurityCallback ;
import com.android.keyguard.KeyguardSecurityView ;

import com.android.keyguard.R ;

/**
 * M: The view for power-off alarm boot.
 */
public class PowerOffAlarmView extends RelativeLayout implements
        KeyguardSecurityView, GlowPadView.OnTriggerListener {
    private static final String TAG = "PowerOffAlarmView";
    private static final boolean DEBUG = false;
    private final int DELAY_TIME_SECONDS = 7;
    private int mFailedPatternAttemptsSinceLastTimeout = 0;
    private int mTotalFailedPatternAttempts = 0;
    private LockPatternUtils mLockPatternUtils;
    private Button mForgotPatternButton;
    private TextView mVcTips;
    private TextView mTitleView = null;
    private LinearLayout mVcTipsContainer;
    private KeyguardSecurityCallback mCallback;
    private boolean mIsRegistered = false;
    private boolean mEnableFallback;
    private Context mContext;

    // These defaults must match the values in res/xml/settings.xml
    private static final String DEFAULT_SNOOZE = "10";
    private static final String DEFAULT_VOLUME_BEHAVIOR = "2";
    protected static final String SCREEN_OFF = "screen_off";

    protected Alarm mAlarm;
    private int mVolumeBehavior;
    boolean mFullscreenStyle;
    private GlowPadView mGlowPadView;
    private boolean mIsDocked = false;
    private static final int UPDATE_LABEL = 99;
    // Parameters for the GlowPadView "ping" animation; see triggerPing().
    private static final int PING_MESSAGE_WHAT = 101;
    private static final boolean ENABLE_PING_AUTO_REPEAT = true;
    private static final long PING_AUTO_REPEAT_DELAY_MSEC = 1200;

    private boolean mPingEnabled = true;

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case PING_MESSAGE_WHAT:
                    triggerPing();
                    break;
                case UPDATE_LABEL:
                    if (mTitleView != null) {
                        mTitleView.setText(msg.getData().getString("label"));
                    }
                    break;
                default:
                    break;
            }
        }
    };

    /**
     * Constructor.
     * @param context context
     */
    public PowerOffAlarmView(Context context) {
        this(context, null);
    }

    /**
     * Constructor.
     * @param context context
     * @param attrs attributes
     */
    public PowerOffAlarmView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;

    }

    public void setKeyguardCallback(KeyguardSecurityCallback callback) {
        mCallback = callback;
    }

    /**
     * set lockpattern utils.
     * @param utils LockPatternUtils
     */
    public void setLockPatternUtils(LockPatternUtils utils) {
        mLockPatternUtils = utils;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        Log.w(TAG, "onFinishInflate ... ");
        setKeepScreenOn(true);
        mTitleView = (TextView) findViewById(R.id.alertTitle);
        mGlowPadView = (GlowPadView) findViewById(R.id.glow_pad_view);
        mGlowPadView.setOnTriggerListener(this);
        setFocusableInTouchMode(true);
        triggerPing();

        // Check the docking status , if the device is docked , do not limit rotation
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_DOCK_EVENT);
        Intent dockStatus = mContext.registerReceiver(null, ifilter);
        if (dockStatus != null) {
            mIsDocked = dockStatus.getIntExtra(Intent.EXTRA_DOCK_STATE, -1)
                    != Intent.EXTRA_DOCK_STATE_UNDOCKED;
        }

        // Register to get the alarm killed/snooze/dismiss intent.
        IntentFilter filter = new IntentFilter(Alarms.ALARM_KILLED);
        filter.addAction(Alarms.ALARM_SNOOZE_ACTION);
        filter.addAction(Alarms.ALARM_DISMISS_ACTION);
        filter.addAction(UPDATE_LABEL_ACTION);
        mContext.registerReceiver(mReceiver, filter);

        mLockPatternUtils = mLockPatternUtils == null ? new LockPatternUtils(
                mContext) : mLockPatternUtils;
        enableEventDispatching(true);
    }

    @Override
    public void onTrigger(View v, int target) {
        final int resId = mGlowPadView.getResourceIdForTarget(target);
        if (resId == R.drawable.mtk_ic_alarm_alert_snooze) {
            snooze();
        } else if (resId == R.drawable.mtk_ic_alarm_alert_dismiss_pwroff) {
            powerOff();
        } else if (resId == R.drawable.mtk_ic_alarm_alert_dismiss_pwron) {
            powerOn();
        } else {
            // Code should never reach here.
            Log.e(TAG, "Trigger detected on unhandled resource. Skipping.");
        }
    }

    private void triggerPing() {
        if (mPingEnabled) {
            mGlowPadView.ping();

            if (ENABLE_PING_AUTO_REPEAT) {
                mHandler.sendEmptyMessageDelayed(PING_MESSAGE_WHAT, PING_AUTO_REPEAT_DELAY_MSEC);
            }
        }
    }

    // Attempt to snooze this alert.
    private void snooze() {
        Log.d(TAG, "snooze selected");
        sendBR(SNOOZE);
    }

    // power on the device
    private void powerOn() {
        enableEventDispatching(false);
        Log.d(TAG, "powerOn selected");
        sendBR(DISMISS_AND_POWERON);
        sendBR(NORMAL_BOOT_ACTION);
    }

    // power off the device
    private void powerOff() {
        Log.d(TAG, "powerOff selected");
        sendBR(DISMISS_AND_POWEROFF);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        boolean result = super.onTouchEvent(ev);
        //TODO: if we need to add some logic here ?
        return result;
    }

    @Override
    public void showUsabilityHint() {
    }

    /** TODO: hook this up. */
    public void cleanUp() {
        if (DEBUG) {
            Log.v(TAG, "Cleanup() called on " + this);
        }
        mLockPatternUtils = null;
    }

    @Override
    public boolean needsInput() {
        return false;
    }

    @Override
    public void onPause() {
    }

    @Override
    public void onResume(int reason) {
        reset();
        Log.v(TAG, "onResume");
    }

    @Override
    public KeyguardSecurityCallback getCallback() {
        return mCallback;
    }

    @Override
    public void onDetachedFromWindow() {
        Log.v(TAG, "onDetachedFromWindow ....");
        mContext.unregisterReceiver(mReceiver);
    }

    @Override
    public void showBouncer(int duration) {
    }

    @Override
    public void hideBouncer(int duration) {
    }

    private void enableEventDispatching(boolean flag) {
        try {
            final IWindowManager wm = IWindowManager.Stub
                    .asInterface(ServiceManager
                            .getService(Context.WINDOW_SERVICE));
            if (wm != null) {
                wm.setEventDispatching(flag);
            }
        } catch (RemoteException e) {
            Log.w(TAG, e.toString());
        }
    }

    private void sendBR(String action) {
        Log.w(TAG, "send BR: " + action);
        mContext.sendBroadcast(new Intent(action));
    }

    // Receives the ALARM_KILLED action from the AlarmKlaxon,
    // and also ALARM_SNOOZE_ACTION / ALARM_DISMISS_ACTION from other
    // applications
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
       @Override
       public void onReceive(Context context, Intent intent) {
          String action = intent.getAction();
          Log.v(TAG, "receive action : " + action);
          if (UPDATE_LABEL_ACTION.equals(action)) {
              Message msg = new Message();
              msg.what = UPDATE_LABEL;
              Bundle data = new Bundle();
              data.putString("label", intent.getStringExtra("label"));
              msg.setData(data);
              mHandler.sendMessage(msg);
          } else if (PowerOffAlarmManager.isAlarmBoot()) {
              snooze();
          }
       }
    };

    @Override
    public void onGrabbed(View v, int handle) {
        // TODO Auto-generated method stub
    }

    @Override
    public void onReleased(View v, int handle) {
        // TODO Auto-generated method stub
    }

    @Override
    public void onGrabbedStateChange(View v, int handle) {
        // TODO Auto-generated method stub
    }

    @Override
    public void onFinishFinalAnimation() {
        // TODO Auto-generated method stub
    }

    @Override
    public void reset() {
        // TODO Auto-generated method stub
    }

    @Override
    public void startAppearAnimation() {
        // noop.
    }

    @Override
    public boolean startDisappearAnimation(Runnable finishRunnable) {
        return false;
    }

    private static final String SNOOZE = "com.android.deskclock.SNOOZE_ALARM";
    private static final String DISMISS_AND_POWEROFF = "com.android.deskclock.DISMISS_ALARM";
    private static final String DISMISS_AND_POWERON = "com.android.deskclock.POWER_ON_ALARM";
    private static final String UPDATE_LABEL_ACTION = "update.power.off.alarm.label";
    private static final String NORMAL_BOOT_ACTION = "android.intent.action.normal.boot";
    private static final String NORMAL_BOOT_DONE_ACTION = "android.intent.action.normal.boot.done";
    private static final String DISABLE_POWER_KEY_ACTION =
        "android.intent.action.DISABLE_POWER_KEY";

    ///M: volume key does nothing when Power Off Alarm Boot
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (PowerOffAlarmManager.isAlarmBoot()) {
            switch(keyCode) {
                case KeyEvent.KEYCODE_VOLUME_UP:
                    Log.d(TAG, "onKeyDown() - KeyEvent.KEYCODE_VOLUME_UP, do nothing.") ;
                    return true;
                case KeyEvent.KEYCODE_VOLUME_DOWN:
                    Log.d(TAG, "onKeyDown() - KeyEvent.KEYCODE_VOLUME_DOWN, do nothing.") ;
                    return true ;
                default:
                    break;
            }
        }
     return super.onKeyDown(keyCode, event);
    }

    ///M: volume key does nothing when Power Off Alarm Boot
    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (PowerOffAlarmManager.isAlarmBoot()) {
            switch(keyCode) {
                case KeyEvent.KEYCODE_VOLUME_UP:
                    Log.d(TAG, "onKeyUp() - KeyEvent.KEYCODE_VOLUME_UP, do nothing.") ;
                    return true;
                case KeyEvent.KEYCODE_VOLUME_DOWN:
                    Log.d(TAG, "onKeyUp() - KeyEvent.KEYCODE_VOLUME_DOWN, do nothing.") ;
                    return true ;
                default:
                    break;
            }
        }
     return super.onKeyDown(keyCode, event);
    }
}
