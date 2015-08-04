
package com.mediatek.keyguard.PowerOffAlarm ;


import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemProperties;
import android.util.Log;

import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.ViewMediatorCallback ;

public class PowerOffAlarmManager {
    private final static String TAG = "PowerOffAlarmManager";

    private Context mContext;
    private ViewMediatorCallback mViewMediatorCallback;
    private LockPatternUtils mLockPatternUtils;
    private static PowerOffAlarmManager sInstance;
    private boolean mSystemReady = false;
    private boolean mNeedToShowAlarmView = false;

    /**
     * Construct a PowerOffAlarmManager
     * @param context
     * @param lockPatternUtils optional mock interface for LockPatternUtils
     */
    public PowerOffAlarmManager(Context context, ViewMediatorCallback viewMediatorCallback,
                                        LockPatternUtils lockPatternUtils) {

        mContext = context;
        mViewMediatorCallback = viewMediatorCallback;
        mLockPatternUtils = lockPatternUtils;

        IntentFilter filter = new IntentFilter();

        filter.addAction(NORMAL_SHUTDOWN_ACTION);
        filter.addAction(LAUNCH_PWROFF_ALARM);
        filter.addAction(NORMAL_BOOT_ACTION);

        mContext.registerReceiver(mBroadcastReceiver, filter);

    }

    public static PowerOffAlarmManager getInstance(Context context,
                       ViewMediatorCallback viewMediatorCallback, LockPatternUtils lockPatternUtils) {
        if (sInstance == null) {
            sInstance = new PowerOffAlarmManager(context, viewMediatorCallback, lockPatternUtils);
        }
        return sInstance;
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (LAUNCH_PWROFF_ALARM.equals(action)) {
                Log.d(TAG, "LAUNCH_PWROFF_ALARM: " + action);
                mHandler.sendEmptyMessageDelayed(ALARM_BOOT, 1500);

            } else if (NORMAL_BOOT_ACTION.equals(action)) {
                Log.d(TAG, "NORMAL_BOOT_ACTION: " + action);
                mHandler.sendEmptyMessageDelayed(RESHOW_KEYGUARD_LOCK, 2500);

            } else if (NORMAL_SHUTDOWN_ACTION.equals(action)) {
                //add to reset environment variables for power-off alarm
                //is running when schedule power off coming and shutdown device.
                Log.w(TAG, "ACTION_SHUTDOWN: " + action);
                mHandler.postDelayed(new Runnable() {
                    @Override
                     public void run() {
                         mViewMediatorCallback.hideLocked();
                     }
                }, 1500);
            }
        }
    };


    private Handler mHandler = new Handler(Looper.myLooper(), null, true /*async*/) {

        /// M: Add for log message string
        private String getMessageString(Message message) {
            switch (message.what) {
                case ALARM_BOOT:
                    return "ALARM_BOOT";
                case RESHOW_KEYGUARD_LOCK:
                    return "RESHOW_KEYGUARD_LOCK";
            }
            return null;
        }

        @Override
        public void handleMessage(Message msg) {
            Log.d(TAG, "handleMessage enter msg name=" + getMessageString(msg));
            switch (msg.what) {
                case ALARM_BOOT:
                    handleAlarmBoot();
                    break;

                case RESHOW_KEYGUARD_LOCK:
                    mViewMediatorCallback.setSuppressPlaySoundFlag();
                    mViewMediatorCallback.hideLocked();
                    /// M: 748639, avoid to call appWidgetHost startListening then stopListening by hide().
                    postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            /// M: 687963, fix SIM PIN lock needs to show in LockScreen disabled case.
                            if (!mLockPatternUtils.isLockScreenDisabled() || mViewMediatorCallback.isSecure()) {
                                mViewMediatorCallback.setSuppressPlaySoundFlag();
                                mViewMediatorCallback.showLocked(null);
                            }
                        }
                    }, 2000);

                    postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            mContext.sendBroadcast(new Intent(NORMAL_BOOT_DONE_ACTION));
                        }
                    }, 4000);
                    break;
            }
            Log.d(TAG, "handleMessage exit msg name=" + getMessageString(msg));
        }
    };

    /**
     * M: power-off alarm @{
     */
    private Runnable mSendRemoveIPOWinBroadcastRunnable = new Runnable() {
        @Override
        public void run() {
            Log.d(TAG, "sendRemoveIPOWinBroadcast ... ");
            Intent in = new Intent(REMOVE_IPOWIN);
            mContext.sendBroadcast(in);
        }
    };
    /** @} */

    /**
     * M: power-off alarm @{
     */
    private void handleAlarmBoot() {
        Log.d(TAG, "handleAlarmBoot");
        mNeedToShowAlarmView = true;
        maybeShowAlarmView();
    }

    public void startAlarm() {
        startAlarmService();
        ///M: delay 1500ms to make sure the poweroffAlarm view is shown
        mHandler.postDelayed(mSendRemoveIPOWinBroadcastRunnable, 1500);
    }
    private void startAlarmService() {
        Intent in = new Intent(Alarms.START_ALARM_ACTION);
        in.putExtra("isAlarmBoot", true);
        in.setPackage("com.android.deskclock");
        mContext.startService(in);
    }

    public static boolean isAlarmBoot() {
        String bootReason = SystemProperties.get("sys.boot.reason");
        boolean ret = (bootReason != null && bootReason.equals("1")) ? true
                : false;
        return ret;
    }

    public void onSystemReady() {
        mSystemReady = true;
        maybeShowAlarmView();
    }

    private void maybeShowAlarmView() {
        if (mSystemReady && mNeedToShowAlarmView) {
            mNeedToShowAlarmView = false;
            Log.d(TAG, "maybeShowAlarmView start to showLocked");
            if (mViewMediatorCallback.isShowing()) {
                mViewMediatorCallback.setSuppressPlaySoundFlag();
                mViewMediatorCallback.hideLocked();
            }
            mViewMediatorCallback.showLocked(null);
        }
    }


    /// M: add for power off alarm, IPO INTENT @{
    private static final int ALARM_BOOT = 115;
    private static final int RESHOW_KEYGUARD_LOCK = 116;
    private static final String NORMAL_BOOT_ACTION = "android.intent.action.normal.boot";
    private static final String NORMAL_BOOT_DONE_ACTION = "android.intent.action.normal.boot.done";
    private static final String LAUNCH_PWROFF_ALARM = "android.intent.action.LAUNCH_POWEROFF_ALARM";
    private static final String NORMAL_SHUTDOWN_ACTION = "android.intent.action.normal.shutdown";
    static final String REMOVE_IPOWIN = "alarm.boot.remove.ipowin";
    /// @}
}
