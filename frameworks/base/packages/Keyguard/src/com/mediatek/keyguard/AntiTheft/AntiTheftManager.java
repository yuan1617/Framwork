
package com.mediatek.keyguard.AntiTheft ;

import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException ;
import android.os.ServiceManager;
import android.util.Log;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardSecurityCallback ;
import com.android.keyguard.KeyguardSecurityModel;
import com.android.keyguard.KeyguardUtils;
//import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.keyguard.ViewMediatorCallback ;

import com.mediatek.common.dm.DmAgent;
//import com.mediatek.internal.telephony.ppl.IPplAgent;
import com.mediatek.common.ppl.IPplManager;

import com.android.keyguard.R ;

public class AntiTheftManager {

    // The SMALLER value represents the HIGHER priority.
    // Ex: DmLock (value = 1) is more important than PplLock(value = 2),
    //       so we will use DmLock even if both type of locks exist.
    public static class AntiTheftMode {
        public static final int None = 0 ;
        public static final int DmLock = 1 << 0;
        public static final int PplLock = 1 << 1;
    }

    private final static String TAG = "AntiTheftManager";
    private final static boolean DEBUG = false ;

    private static Context mContext;
    private ViewMediatorCallback mViewMediatorCallback;
    private LockPatternUtils mLockPatternUtils;
    private static AntiTheftManager sInstance;
    private KeyguardSecurityModel mSecurityModel;

    public static final String RESET_FOR_ANTITHEFT_LOCK = "antitheftlock_reset";
    public static final String ANTITHEFT_NONEED_PRINT_TEXT = "AntiTheft Noneed Print Text" ;
    private static final int MSG_ANTITHEFT_KEYGUARD_UPDATE = 1001;

    // Each bit represents the related AntiTheft lock is LOCKED or NOT.
    // Bit value -- 1 : locked. 0 : not locked
    private static int mAntiTheftLockEnabled = 0 ;
    // Each bit represents the related AntiTheft lock NEEDS KEYPAD or NOT.
    // Bit value -- 1 : needs. 0 : no need
    private static int mKeypadNeeded = 0 ;
    // Each bit represents the related AntiTheft lock can be DISMISSED or NOT.
    // Bit value -- 1 : can be dismissed. 0 : cannot be dismissed
    private static int mDismissable = 0 ;

    // For Auto-Test
    private static boolean mAntiTheftAutoTestNotShowUI = false ;

    /**
     * Construct a AntiTheftManager
     * @param context
     * @param lockPatternUtils optional mock interface for LockPatternUtils
     */
    public AntiTheftManager(Context context, ViewMediatorCallback viewMediatorCallback,
            LockPatternUtils lockPatternUtils) {
        Log.d(TAG, "AntiTheftManager() is called.") ;

        mContext = context;
        mViewMediatorCallback = viewMediatorCallback;
        mLockPatternUtils = lockPatternUtils;
        mSecurityModel = new KeyguardSecurityModel(mContext);

        IntentFilter filter = new IntentFilter();

        // set up DM Lock properties
        setKeypadNeeded(AntiTheftMode.DmLock, false) ;
        setDismissable(AntiTheftMode.DmLock, false) ;
        filter.addAction(OMADM_LAWMO_LOCK);
        filter.addAction(OMADM_LAWMO_UNLOCK);

        // set up pPhonePrivacy Lock(PPL) properties
        if (KeyguardUtils.isPrivacyProtectionLockSupport()) {
            Log.d(TAG, "MTK_PRIVACY_PROTECTION_LOCK is enabled.") ;
            setKeypadNeeded(AntiTheftMode.PplLock, true) ;
            setDismissable(AntiTheftMode.PplLock, true) ;
            filter.addAction(PPL_LOCK) ;
            filter.addAction(PPL_UNLOCK) ;
        }

        ///M: fix ALPS01830654
        ///   When IPO shut down, PPL service is disconnected.
        ///   So we need to re-bind it when IPO boot on.
        filter.addAction(IPO_BOOTUP) ;

        mContext.registerReceiver(mBroadcastReceiver, filter);
    }

    // Use singleton to make sure that only one AntiTheftManager obj existed in the system.
    public static AntiTheftManager getInstance(Context context,
            ViewMediatorCallback viewMediatorCallback, LockPatternUtils lockPatternUtils) {
        Log.d(TAG, "getInstance(...) is called.") ;
        if (sInstance == null) {
            Log.d(TAG, "getInstance(...) create one.") ;
            sInstance = new AntiTheftManager(context, viewMediatorCallback, lockPatternUtils);
        }
        return sInstance;
    }

    public static String getAntiTheftModeName(final int mode) {
        switch(mode) {
            case AntiTheftMode.None:
                return "AntiTheftMode.None" ;
            case AntiTheftMode.DmLock:
                return "AntiTheftMode.DmLock" ;
            case AntiTheftMode.PplLock:
                return "AntiTheftMode.PplLock" ;
        }

        return "AntiTheftMode.None" ;
    }

    // Search from HIGHEST priority lock to LOWEST priority lock.
    // Return the HIGHEST priority lock as the current mode.
    public static int getCurrentAntiTheftMode() {
        int shift = 0 ;

        Log.d(TAG, "getCurrentAntiTheftMode() is called.") ;

        if (!isAntiTheftLocked())
            return AntiTheftMode.None;

        while (shift < 32) {
            int mode = mAntiTheftLockEnabled & (1 << shift) ;
            if (mode != AntiTheftMode.None) {
                return mode ;
            }
            shift++ ;
        }

        return AntiTheftMode.None ;
    }

    public static boolean isKeypadNeeded() {
        final int mode = getCurrentAntiTheftMode() ;
        Log.d(TAG, "getCurrentAntiTheftMode() = " + getAntiTheftModeName(mode)) ;

        final boolean needKeypad = (mKeypadNeeded & mode) != 0 ;
        Log.d(TAG, "isKeypadNeeded() = " + needKeypad) ;

        return needKeypad;
    }

    public static void setKeypadNeeded(final int lockMode, boolean need) {
        if (need) {
            mKeypadNeeded |= lockMode;
        }
        else {
            mKeypadNeeded &= ~lockMode;
        }
    }

    public static boolean isAntiTheftLocked() {
        return mAntiTheftLockEnabled != 0 ;
    }

    // To avoid resetting/dismissing the antithefet view consecutively(no secuiryt issue, but it harms the user experience),
    // we add a verification to check if sending msg to reset/dismiss is needed or not.
    private static boolean isNeedUpdate(final int lockMode, boolean enable) {
        boolean needToUpdate = true ;

        if (enable && ((mAntiTheftLockEnabled & lockMode) != 0)) {
            Log.d(TAG, "isNeedUpdate() - lockMode( " + lockMode + " ) is already enabled, no need update") ;
            needToUpdate = false ;
        }
        else if (!enable && ((mAntiTheftLockEnabled & lockMode) == 0)) {
            Log.d(TAG, "isNeedUpdate() - lockMode( " + lockMode + " ) is already disabled, no need update") ;
            needToUpdate = false ;
        }

        return needToUpdate ;
    }

    public static void setAntiTheftLocked(final int lockMode, boolean enable) {
        if (enable) {
            mAntiTheftLockEnabled |= lockMode;
        }
        else {
            mAntiTheftLockEnabled &= ~lockMode;
        }
    }

    public static boolean isDismissable() {
        final int mode = getCurrentAntiTheftMode() ;
        boolean dismissAble = false ;

        if (mode == AntiTheftMode.None) {
            dismissAble = true ;
        }
        else {
            if ((mode & mDismissable) != 0) {
                dismissAble = true ;
            }
        }

        if (DEBUG) {
            Log.d(TAG, "mode = " + mode + ", dismiss = " + dismissAble) ;
        }

        return dismissAble ;
    }

    public static void setDismissable(final int lockMode, boolean canBeDismissed) {
        Log.d(TAG, "mDismissable is " + mDismissable + " before") ;
        if (canBeDismissed) {
            mDismissable |= lockMode;
        }
        else {
            mDismissable &= ~lockMode;
        }

        Log.d(TAG, "mDismissable is " + mDismissable + " after") ;
    }

    // Some security views have to show above AntiTheft lock view.
    // We do some checks here -- is current highest priority anti-theft lock has higher priority than "KeyguardSecurityModel.SecurityMode mode"?
    // If yes, return ture and the Keyguard will show the AntiTheftLockView.
    public static boolean isAntiTheftPriorToSecMode(KeyguardSecurityModel.SecurityMode mode) {
        final int currentAntiTheftType = getCurrentAntiTheftMode() ;
        boolean isAntiTheftHasHigherPriority = false ;

        if (DEBUG) {
            Log.d(TAG, "isAntiTheftPriorToSecMode(mode = " + mode + "), currentAntiTheftType = " + currentAntiTheftType) ;
        }

        if (isAntiTheftLocked()) {
            if (currentAntiTheftType == AntiTheftMode.DmLock) {
                //DM Lock is higher than all other modes.
                isAntiTheftHasHigherPriority = true ;
            }
            else {
                switch(mode) {
                    case SimPinPukMe1:
                    case SimPinPukMe2:
                    case SimPinPukMe3:
                    case SimPinPukMe4:
                    case AlarmBoot:
                        break;
                    default:
                        isAntiTheftHasHigherPriority = true ;
                        break;
                }
            }
        }

        return isAntiTheftHasHigherPriority ;
    }

    // Reource related APIs
    public static int getAntiTheftViewId() {
        return R.id.keyguard_antitheft_lock_view;
    }

    public static int getAntiTheftLayoutId() {
        return R.layout.mtk_keyguard_anti_theft_lock_view;
    }

    public static int getPrompt() {
        int mode = getCurrentAntiTheftMode() ;

        if (mode == AntiTheftMode.DmLock) {
            return R.string.dm_prompt;
        }
        else {
            return R.string.ppl_prompt;
        }
    }

    // The Message text will be "Plz enter AntiTheft pw" or "Charging - Plz enter AntiTheft pw".
    // For ALPS01479860
    public static String getAntiTheftMessageAreaText(CharSequence text, CharSequence seperator) {
        StringBuilder b = new StringBuilder();
        if (text != null && text.length() > 0 && !text.toString().equals(ANTITHEFT_NONEED_PRINT_TEXT)) {
            b.append(text);
            b.append(seperator);
        }
        b.append(mContext.getText(getPrompt()));
        return b.toString() ;
    }

    public static boolean isAntiTheftAutoTestNotShowUI() {
        return mAntiTheftAutoTestNotShowUI ;
    }

    // If possible, refine this part to eliminate the coupling between Keyguard modules. -- start
    private static boolean mKeyguardIsAntiTheftModeNow = false ;
    public static void setKeyguardCurrentModeIsAntiTheftMode(boolean isAntiTheft) {
        if (DEBUG) {
            Log.d(TAG, "setKeyguardCurrentModeIsAntiTheftMode is " + isAntiTheft) ;
        }
        mKeyguardIsAntiTheftModeNow = isAntiTheft ;
    }

    public static boolean isKeyguardCurrentModeAntiTheftMode() {
        if (DEBUG) {
            Log.d(TAG, "mKeyguardIsAntiTheftModeNow is " + mKeyguardIsAntiTheftModeNow) ;
        }
        return mKeyguardIsAntiTheftModeNow ;
    }
    // If possible, refine this part to eliminate the coupling between Keyguard modules. -- end

    public static boolean checkPassword(String pw) {
        boolean unlockSuccess = false ;
        final int mode = getCurrentAntiTheftMode() ;

        Log.d(TAG, "checkPassword, mode is " + getAntiTheftModeName(mode)) ;

        switch(mode) {
            case AntiTheftMode.PplLock:
                unlockSuccess = doPplCheckPassword(pw) ;
                break ;
            default:
                break ;
        }

        Log.d(TAG, "checkPassword, unlockSuccess is " + unlockSuccess) ;

        return unlockSuccess ;
    }

    private final int MSG_ARG_LOCK = 0 ;
    private final int MSG_ARG_UNLOCK = 1 ;
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();

                Log.d(TAG, "handleAntiTheftViewUpdate() - action = " + action);
                /// M: DM Begin @{
                if (OMADM_LAWMO_LOCK.equals(action)) {
                    Log.d(TAG, "receive OMADM_LAWMO_LOCK");
                    sendAntiTheftUpdateMsg(AntiTheftMode.DmLock, MSG_ARG_LOCK) ;
                } else if (OMADM_LAWMO_UNLOCK.equals(action)) {
                    Log.d(TAG, "receive OMADM_LAWMO_UNLOCK");
                    sendAntiTheftUpdateMsg(AntiTheftMode.DmLock, MSG_ARG_UNLOCK) ;
                }
                /// DM end @}

                /// M: PPL Begin @{
                else if (PPL_LOCK.equals(action)) {
                    Log.d(TAG, "receive PPL_LOCK");
                    sendAntiTheftUpdateMsg(AntiTheftMode.PplLock, MSG_ARG_LOCK) ;
                } else if (PPL_UNLOCK.equals(action)) {
                    Log.d(TAG, "receive PPL_UNLOCK");
                    sendAntiTheftUpdateMsg(AntiTheftMode.PplLock, MSG_ARG_UNLOCK) ;
                /// PPL end @}
                } else if (IPO_BOOTUP.equals(action)) {
                    ///M: fix ALPS01830654
                    ///   When IPO shut down, PPL service is disconnected.
                    ///   So we need to re-bind it when IPO boot on.
                    doBindAntiThftLockServices() ;
                }
            }
    };


    private void sendAntiTheftUpdateMsg(final int antiTheftLockType, final int lock) {
        Message msg = mHandler.obtainMessage(MSG_ANTITHEFT_KEYGUARD_UPDATE);
        msg.arg1 = antiTheftLockType;
        msg.arg2 = lock ;
        msg.sendToTarget();
    }

    private Handler mHandler = new Handler(Looper.myLooper(), null, true) {
        @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case MSG_ANTITHEFT_KEYGUARD_UPDATE:
                        handleAntiTheftViewUpdate(msg.arg1, msg.arg2 == MSG_ARG_LOCK);
                        break;
                }
            }
    };

    private void handleAntiTheftViewUpdate(final int antiTheftLockType, final boolean lock) {
        if (isNeedUpdate(antiTheftLockType, lock)) {
            setAntiTheftLocked(antiTheftLockType, lock) ;

            if (lock) {
                if (!mViewMediatorCallback.isShowing()) {
                    mViewMediatorCallback.showLocked(null);
                }
                else {
                    boolean needToRest = isAntiTheftPriorToSecMode(mSecurityModel.getSecurityMode()) ;
                    if (needToRest) {
                        /// M: If anti-theft lock cause reset, tell KeyguardViewManager to force reset
                        mViewMediatorCallback.resetStateLocked();
                    }
                    else {
                        Log.d(TAG, "No need to reset the security view to show AntiTheft, since current view should show above antitheft view.") ;
                    }
                }
            }
            else {
                /// M: fix ALPS01832185
                if (mKeyguardSecurityCallback != null) {
                    mKeyguardSecurityCallback.dismiss(true) ;
                } else {
                    Log.d(TAG, "mKeyguardSecurityCallback is null !") ;
                }
            }

            adjustStatusBarLocked() ;
        }
    }

    public void doBindAntiThftLockServices() {
        Log.d(TAG, "doBindAntiThftLockServices() is called.") ;

        if (KeyguardUtils.isPrivacyProtectionLockSupport()) {
            bindPplService() ;
        }
    }

    public void doAntiTheftLockCheck() {
        doDmLockCheck() ;
    }

    public static int getHideStatusBarIconFlags() {
        Log.d(TAG, "hideStatusBarIcons()  is called.");

        int flags = StatusBarManager.DISABLE_NONE;
        // TODO: this part may be needless in Android L.
        /*if(isAntiTheftLocked()) {
          flags = StatusBarManager.DISABLE_EXPAND | StatusBarManager.DISABLE_NOTIFICATION_ALERTS
          | StatusBarManager.DISABLE_NOTIFICATION_ICONS
          | StatusBarManager.DISABLE_NOTIFICATION_TICKER
          | StatusBarManager.DISABLE_SEARCH;
          Log.d(TAG, "Since there is at least one AntiTheftLock is activated, we hide the no-need icons on statusbar flag = " + Integer.toHexString(flags)) ;
          }*/

        return flags ;
    }

    /// M: DM begin @{
    public static final String OMADM_LAWMO_LOCK = "com.mediatek.dm.LAWMO_LOCK";
    public static final String OMADM_LAWMO_UNLOCK = "com.mediatek.dm.LAWMO_UNLOCK";

    private static void doDmLockCheck() {
        try {
            //for OMA DM
            IBinder binder = ServiceManager.getService("DmAgent");
            if (binder != null) {
                DmAgent agent = DmAgent.Stub.asInterface(binder);
                boolean flag = agent.isLockFlagSet();
                Log.i(TAG, "dmCheckLocked, the lock flag is:" + flag);
                setAntiTheftLocked(AntiTheftMode.DmLock, flag);
            } else {
                Log.i(TAG, "dmCheckLocked, DmAgent doesn't exit");
            }
        } catch (RemoteException e) {
            Log.e(TAG, "doDmLockCheck() - error in get DMAgent service.");
        }
    }

    /// M: PhonePrivacyLock(PPL) begin @{
    public static final String PPL_LOCK = "com.mediatek.ppl.NOTIFY_LOCK";
    public static final String PPL_UNLOCK = "com.mediatek.ppl.NOTIFY_UNLOCK";
    private static IPplManager mIPplManager ;

    private ServiceConnection mPplServiceConnection = new ServiceConnection() {
        @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                // TODO Auto-generated method stub
                Log.i(TAG, "onServiceConnected() -- PPL");

                //iBinder = service ;
                mIPplManager = IPplManager.Stub.asInterface(service) ;
            }

        @Override
            public void onServiceDisconnected(ComponentName name) {
                // TODO Auto-generated method stub
                Log.i(TAG, "onServiceDisconnected()");
                mIPplManager = null ;
            }
    } ;

    private void bindPplService() {
        Log.e(TAG, "binPplService() is called.");

        if (mIPplManager == null) {
            try {
                Intent intent = new Intent("com.mediatek.ppl.service") ;
                intent.setClassName("com.mediatek.ppl", "com.mediatek.ppl.PplService") ;
                mContext.bindService(intent, mPplServiceConnection,  Context.BIND_AUTO_CREATE) ;
            } catch (SecurityException e) {
                Log.e(TAG, "bindPplService() - error in bind ppl service.");
            }
        }
        else {
            Log.d(TAG, "bindPplService() -- the ppl service is already bound.") ;
        }
    }

    private static boolean doPplCheckPassword(String pw) {
        boolean unlockSuccess = false ;

        if (mIPplManager != null) {
            try {
                unlockSuccess = mIPplManager.unlock(pw);
                Log.i(TAG, "doPplCheckPassword, unlockSuccess is " + unlockSuccess);

                if (unlockSuccess) {
                    //clear ppl lock
                    setAntiTheftLocked(AntiTheftMode.PplLock, false);
                }
            }
            catch (RemoteException e) {
                //just test, we don't handle it.
            }

        }
        else {
            Log.i(TAG, "doPplCheckPassword() mIPplManager == null !!??") ;
        }

        return unlockSuccess ;
    }

    public void adjustStatusBarLocked() {
        mViewMediatorCallback.adjustStatusBarLocked() ;
    }

    /// === misc part ===
    ///M: fix ALPS01830654
    ///   When IPO shut down, PPL service is disconnected.
    ///   So we need to re-bind it when IPO boot on.
    private static final String IPO_BOOTUP = "android.intent.action.ACTION_PREBOOT_IPO";

    protected KeyguardSecurityCallback mKeyguardSecurityCallback;
    /**
     * Set KeyguardSecurityCallback to AntitheftManager.
     * @param callback KeyguardSecurityCallback of KeyguardAntiTheftLockView
     */
    public void setSecurityViewCallback(KeyguardSecurityCallback callback) {
        Log.d(TAG, "setSecurityViewCallback(" + callback + ")") ;
        mKeyguardSecurityCallback = callback ;
    }

}
