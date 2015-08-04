package com.mediatek.keyguard.ext;

import android.content.Context;
import android.util.Log;

import com.mediatek.common.MPlugin ;

/**
 * M: Plug-in helper class as the facade for accessing related add-ons.
 */
public class KeyguardPluginFactory {
    private static final String TAG = "KeyguardPluginFactory";
    private static ICustomizeClock mCustomizeClock = null;
    private static IEmergencyButtonExt mEmergencyButtonExt = null;
    private static ICarrierTextExt mCarrierTextExt = null;
    private static IKeyguardUtilExt mKeyguardUtilExt = null;
    private static IOperatorSIMString mOperatorSIMString = null;
    private static ILockScreenExt mLockScreenExt = null;

    public static synchronized ICustomizeClock getCustomizeClock(Context context) {
        if (mCustomizeClock == null) {
            mCustomizeClock = (ICustomizeClock) MPlugin.createInstance(
                ICustomizeClock.class.getName(), context);
            Log.d(TAG, "getCustomizeClock customizeClock= " + mCustomizeClock);

            if (mCustomizeClock == null) {
                mCustomizeClock = new DefaultCustomizeClock(context);
                Log.d(TAG, "getCustomizeClock get DefaultCustomizeClock = " + mCustomizeClock);
            }
        }

        return mCustomizeClock;
    }

    public static synchronized IEmergencyButtonExt getEmergencyButtonExt(Context context) {
        if (mEmergencyButtonExt == null) {
            mEmergencyButtonExt = (IEmergencyButtonExt) MPlugin.createInstance(
                IEmergencyButtonExt.class.getName(), context);
            Log.d(TAG, "getEmergencyButtonExt emergencyButtonExt= " + mEmergencyButtonExt);

            if (mEmergencyButtonExt == null) {
                mEmergencyButtonExt = new DefaultEmergencyButtonExt();
                Log.d(TAG, "getEmergencyButtonExt get DefaultEmergencyButtonExt = " + mEmergencyButtonExt);
            }
        }

        return mEmergencyButtonExt;
    }

    public static synchronized ICarrierTextExt getCarrierTextExt(Context context) {
        if (mCarrierTextExt == null) {
            mCarrierTextExt = (ICarrierTextExt) MPlugin.createInstance(
                ICarrierTextExt.class.getName(), context);
            Log.d(TAG, "getCarrierTextExt carrierTextExt= " + mCarrierTextExt);

            if (mCarrierTextExt == null) {
                mCarrierTextExt = new DefaultCarrierTextExt();
                Log.d(TAG, "getCarrierTextExt get DefaultCarrierTextExt = " + mCarrierTextExt);
            }
        }

        return mCarrierTextExt;
    }

    public static synchronized IKeyguardUtilExt getKeyguardUtilExt(Context context) {
        if (mKeyguardUtilExt == null) {
            mKeyguardUtilExt = (IKeyguardUtilExt) MPlugin.createInstance(
                IKeyguardUtilExt.class.getName(), context);
            Log.d(TAG, "getKeyguardUtilExt keyguardUtilExt= " + mKeyguardUtilExt);

            if (mKeyguardUtilExt == null) {
                mKeyguardUtilExt = new DefaultKeyguardUtilExt();
                Log.d(TAG, "getKeyguardUtilExt get DefaultKeyguardUtilExt = " + mKeyguardUtilExt);
            }
        }
        return mKeyguardUtilExt;
    }

    public static synchronized IOperatorSIMString getOperatorSIMString(Context context) {
        if (mOperatorSIMString == null) {
                mOperatorSIMString = (IOperatorSIMString) MPlugin.createInstance(
                    IOperatorSIMString.class.getName(), context);
                Log.d(TAG, "getOperatorSIMString operatorSIMString= " + mOperatorSIMString);

            if (mOperatorSIMString == null) {
                mOperatorSIMString = new DefaultOperatorSIMString();
                Log.d(TAG, "getOperatorSIMString get DefaultOperatorSIMString = " + mOperatorSIMString);
            }
        }

        return mOperatorSIMString;
    }

    public static synchronized ILockScreenExt getLockScreenExt(Context context) {
        if (mLockScreenExt == null) {
            mLockScreenExt = (ILockScreenExt) MPlugin.createInstance(
                ILockScreenExt.class.getName(), context);
            Log.d(TAG, "getLockScreenExt lockScreenExt= " + mLockScreenExt);

            if (mLockScreenExt == null) {
                mLockScreenExt = new DefaultLockScreenExt();
                Log.d(TAG, "getLockScreenExt get DefaultLockScreenExt = " + mLockScreenExt);
            }
        }

        return mLockScreenExt;
    }
}
