package com.mediatek.systemui.ext;

import android.view.ViewGroup;
import android.widget.ImageView;

/**
 * M: SignalCluster ext interface to support "Operator plugin - Data activity/type, strength icon".
 */
public interface ISignalClusterExt {
    /**
     * Set wifi indicators visibility.
     *
     * @param visible wifi visibility
     */
    void setWifiIndicatorsVisible(boolean visible);

    /**
     * Set Mobile Data Indicators.
     *
     * @param slotId slot Id.
     * @param visible visibility.
     * @param roaming whether roaming.
     * @param isMobileTypeIconWide whether mobile type icon is wide.
     * @param wideTypeIconStartPadding wide type icon start padding.
     * @param hasSimService Whether Sim service is available.
     * @param dataConnectioned true is data connected.
     * @param signalStrengthIcon mobile signal strength icon.
     * @param dataActivityIcon mobile data activity icon.
     * @param dataTypeIcon mobile data type icon.
     */
    void setMobileDataIndicators(int slotId, boolean visible, boolean roaming,
            boolean isMobileTypeIconWide, int wideTypeIconStartPadding,
            boolean hasSimService, boolean dataConnectioned,
            IconIdWrapper signalStrengthIcon, IconIdWrapper dataActivityIcon,
            IconIdWrapper dataTypeIcon);

    /**
     * Set airplane mode.
     *
     * @param airplaneMode whether AirplaneMode
     */
    void setAirplaneMode(boolean airplaneMode);

    /**
     * This is called when the SignalClusterExt view is attached to a window.
     * @param signalClusterCombos signal cluster combos
     * @param signalNetworkTypesImageViews signal network types imageViews
     * @param mobileViewGroups mobileGroup
     * @param mobileTypeImageViews mobile Type ImageViews
     * @param mMobileSignalStrengthImageViews mobile signal strength ImageViews
     */
    void onAttachedToWindow(ViewGroup[] signalClusterCombos,
            ImageView[] signalNetworkTypesImageViews, ViewGroup[] mobileViewGroups,
            ImageView[] mobileTypeImageViews, ImageView[] mMobileSignalStrengthImageViews);

    /**
     * This is called when the SignalClusterExt view is detached from a window.
     */
    void onDetachedFromWindow();

    /**
     * Called when any RTL property (layout direction or text direction or text alignment) has been
     * changed.
     *
     * @param layoutDirection the direction of the layout
     */
    void onRtlPropertiesChanged(int layoutDirection);

    /**
     * Apply data to view, run after indicator change.
     */
    void apply();
}
