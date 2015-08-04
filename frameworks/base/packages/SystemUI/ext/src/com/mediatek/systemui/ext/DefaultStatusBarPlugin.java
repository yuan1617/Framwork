package com.mediatek.systemui.ext;

import android.content.Context;
import android.content.ContextWrapper;

import com.mediatek.common.PluginImpl;

/**
 * Default implementation of Plug-in definition of Status bar.
 */
@PluginImpl(interfaceName="com.mediatek.systemui.ext.IStatusBarPlugin")
public class DefaultStatusBarPlugin extends ContextWrapper implements IStatusBarPlugin {

    /**
     * Constructs a new DefaultStatusBarPlugin instance with Context.
     * @param context A Context object
     */
    public DefaultStatusBarPlugin(Context context) {
        super(context);
    }

    @Override
    public void customizeSignalStrengthNullIcon(int slotId, IconIdWrapper icon) {
    }

    @Override
    public void customizeSignalIndicatorIcon(int slotId, IconIdWrapper icon) {
    }

    @Override
    public void customizeDataTypeIcon(IconIdWrapper icon, boolean roaming,
            DataType dataType) {
    }

    @Override
    public void customizeDataNetworkTypeIcon(IconIdWrapper icon, NetworkType networkType) {
    }

    @Override
    public void customizeDataActivityIcon(IconIdWrapper icon, int dataActivity) {
    }

    @Override
    public void customizeWifiSignalStrengthIconList(IconIdWrapper wifiIconId, int level) {
        wifiIconId.setResources(null);
    }

    @Override
    public void customizeWifiInOutIcon(IconIdWrapper icon, int wifiActivity) {
    }

    @Override
    public boolean customizeAutoInSimChooser(boolean isEnabled) {
        return isEnabled;
    }

    @Override
    public BehaviorSet customizeBehaviorSet() {
        return BehaviorSet.DEFAULT_BS;
    }

    @Override
    public boolean customizeEnableWifiAtAirplaneMode(boolean isEnabled) {
        return isEnabled;
    }

    @Override
    public boolean customizeEnableBluetoothtAirplaneMode(boolean isEnabled) {
        return isEnabled;
    }

    @Override
    public boolean customizeMobileGroupVisible(boolean isSimInserted) {
        return isSimInserted;
    }

    @Override
    public NetworkType customizeNetworkType(boolean roaming, int dataNetType,
            NetworkType networkType) {
        return networkType;
    }

    @Override
    public DataType customizeDataType(boolean roaming, int dataNetType, DataType dataType) {
        return dataType;
    }

    @Override
    public boolean customizeHspaDistinguishable(boolean distinguishable) {
        return distinguishable;
    }

    @Override
    public ISignalClusterExt customizeSignalCluster() {
        return new DefaultSignalClusterExt();
    }
}
