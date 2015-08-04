package com.mediatek.keyguard.ext;

import android.content.Context;
import android.util.Log;
import android.view.ViewGroup;

import com.mediatek.common.PluginImpl ;

/**
 * Default plugin implementation.
 */
@PluginImpl(interfaceName="com.mediatek.keyguard.ext.ICustomizeClock")
public class DefaultCustomizeClock implements ICustomizeClock {

    private static final String TAG = "DefaultCustomizeClock";

    public DefaultCustomizeClock(Context context) {
    }

    @Override
    public void addCustomizeClock(Context context, ViewGroup clockContainer, ViewGroup statusArea) {
        Log.d(TAG, "addCustomizeClock context = " + context + " clockContainer = "
                + clockContainer);
    }

    @Override
    public void reset() {
    }

    @Override
    public void updateClockLayout() {
    }
}
