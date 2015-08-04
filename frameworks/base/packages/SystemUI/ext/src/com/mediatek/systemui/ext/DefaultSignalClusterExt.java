package com.mediatek.systemui.ext;

import android.view.ViewGroup;
import android.widget.ImageView;

import com.mediatek.common.PluginImpl;

/**
 * Default ISignalClusterExt Empty implements.
 */
@PluginImpl(interfaceName = "com.mediatek.systemui.ext.ISignalClusterExt")

public class DefaultSignalClusterExt implements ISignalClusterExt {

    @Override
    public void setWifiIndicatorsVisible(boolean visible) {
    }

    @Override
    public void setMobileDataIndicators(int slotId, boolean visible, boolean roaming,
            boolean isMobileTypeIconWide, int wideTypeIconStartPadding,
            boolean hasSimService, boolean dataConnectioned,
            IconIdWrapper signalStrengthIcon, IconIdWrapper dataActivityIcon,
            IconIdWrapper dataTypeIcon) {
    }

    @Override
    public void setAirplaneMode(boolean airplaneMode) {
    }

    @Override
    public void onAttachedToWindow(ViewGroup[] signalClusterCombos,
            ImageView[] signalNetworkTypesImageViews, ViewGroup[] mobileViewGroups,
            ImageView[] mobileTypeImageViews, ImageView[] mMobileSignalStrengthImageViews) {
    }

    @Override
    public void onDetachedFromWindow() {
    }

    @Override
    public void onRtlPropertiesChanged(int layoutDirection) {
    }

    @Override
    public void apply() {
    }
}
