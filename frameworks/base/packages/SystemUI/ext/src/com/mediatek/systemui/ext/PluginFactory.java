package com.mediatek.systemui.ext;

import android.content.Context;

import com.mediatek.common.MPlugin;
import com.mediatek.xlog.Xlog;

/**
 * M: Plug-in helper class as the facade for accessing related add-ons.
 */
public class PluginFactory {
    private static IStatusBarPlugin mStatusBarPlugin = null;
    private static boolean isDefaultStatusBarPlugin = true;
    private static IQuickSettingsPlugin mQuickSettingsPlugin = null;
    private static final String TAG = "PluginFactory";
    private static IStatusBarPlmnPlugin mStatusBarPlmnPlugin = null;

    public static synchronized IStatusBarPlugin getStatusBarPlugin(Context context) {
        if (mStatusBarPlugin == null) {
            mStatusBarPlugin = (IStatusBarPlugin) MPlugin.createInstance(
                    IStatusBarPlugin.class.getName(), context);
            isDefaultStatusBarPlugin = false;
            if (mStatusBarPlugin == null) {
                mStatusBarPlugin = new DefaultStatusBarPlugin(context);
                isDefaultStatusBarPlugin = true;
            }
        }
        return mStatusBarPlugin;
    }

    public static synchronized IQuickSettingsPlugin getQuickSettingsPlugin(Context context) {
        if (mQuickSettingsPlugin == null) {
            mQuickSettingsPlugin = (IQuickSettingsPlugin) MPlugin.createInstance(
                    IQuickSettingsPlugin.class.getName(), context);
            Xlog.d(TAG, "getQuickSettingsPlugin mQuickSettingsPlugin= " + mQuickSettingsPlugin);

            if(mQuickSettingsPlugin == null ) {
                mQuickSettingsPlugin = new DefaultQuickSettingsPlugin(context);
                Xlog.d(TAG, "getQuickSettingsPlugin get DefaultQuickSettingsPlugin = " + mQuickSettingsPlugin);
            }
        }
        return mQuickSettingsPlugin;
    }

    public static synchronized boolean isDefaultStatusBarPlugin() {
        return isDefaultStatusBarPlugin;
    }

    public static synchronized IStatusBarPlmnPlugin getStatusBarPlmnPlugin(Context context) {
        Xlog.d("PluginFactory", "into getStatusBarPlmnPlugin");
        if (mStatusBarPlmnPlugin == null) {
            mStatusBarPlmnPlugin = (IStatusBarPlmnPlugin) MPlugin.createInstance(
                IStatusBarPlmnPlugin.class.getName(), context);
            Xlog.d("PluginFactory", "into getStatusBarPlmnPlugin" + (String) IStatusBarPlmnPlugin.class.getName());

            if(mStatusBarPlmnPlugin == null) {
                Xlog.d("PluginFactory", "exception, call default");
                mStatusBarPlmnPlugin = new DefaultStatusBarPlmnPlugin(context);
            }
        }
        return mStatusBarPlmnPlugin;
     }
}
