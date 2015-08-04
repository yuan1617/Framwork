package com.mediatek.systemui.ext;

import android.content.Context;
import android.content.ContextWrapper;
import android.util.Log;
import android.view.ViewGroup;


import com.mediatek.common.PluginImpl ;

/**
 * Default implementation of Plug-in definition of Quick Settings.
 */
@PluginImpl(interfaceName="com.mediatek.systemui.ext.IQuickSettingsPlugin")

public class DefaultQuickSettingsPlugin extends ContextWrapper implements
        IQuickSettingsPlugin {
    private Context mContext;
    private static final String TAG = "DefaultQuickSettingsPlugin";

    /**
     * Constructor.
     * @param context The context.
     */
    public DefaultQuickSettingsPlugin(Context context) {
        super(context);
        mContext = context;
    }

    @Override
    public void customizeTileViews(ViewGroup parent) {
        Log.d(TAG, "customizeTileViews parent= " + parent);
    }

    @Override
    public void updateResources() {
    }

    @Override
    public boolean customizeDisplayDataUsage(boolean isDisplay) {
        Log.i(TAG, "customizeDisplayDataUsage, return isDisplay = " + isDisplay);
        return isDisplay;
    }
}

