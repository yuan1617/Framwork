package com.mediatek.systemui.ext;

/**
 * M: the interface for Plug-in definition of Status bar.
 */
public interface IStatusBarPlugin {

    /** Signal strength interfaces. @{ */

    /**
     * Customize Signal Strength icon when has no service or null.
     * @param slotId The slot index.
     * @param icon The icon wrapper need to be customized.
     */
    void customizeSignalStrengthNullIcon(int slotId, IconIdWrapper icon);

    /**
     * Customize the signal indicator icon.
     * @param slotId The slot index.
     * @param icon The icon wrapper need to be customized.
     */
    void customizeSignalIndicatorIcon(int slotId, IconIdWrapper icon);

    /** Signal strength interfaces. @} */

    /** Data connection interfaces. @{ */

    /**
     * Customize the data connection type icon.
     *
     * @param icon data connection type icon.
     * @param roaming roaming Whether at roaming state.
     * @param dataType The data connection type.
     */
    void customizeDataTypeIcon(IconIdWrapper icon, boolean roaming, DataType dataType);

    /**
     * Get the data connection network type icon.
     *
     * @param icon the need changed icon id wrapper.
     * @param networkType The network type.
     */
    void customizeDataNetworkTypeIcon(IconIdWrapper icon, NetworkType networkType);

    /**
     * @param icon the need changed icon id wrapper.
     * @param dataActivity the data activity index.
     */
    void customizeDataActivityIcon(IconIdWrapper icon, int dataActivity);

    /**
     * Customize whether autoinSimChooser enable or not.
     * @param isEnabled default value
     * @return Return if data type icon always display once opened.
     */
    boolean customizeAutoInSimChooser(boolean isEnabled);

    /**
     *
     * @return Return the BehaviorSet for the different OP.
     */
    BehaviorSet customizeBehaviorSet();

    /** WIFI interfaces. @{ */

    /**
     * Customize WIFI signal strength icon list.
     *
     * @param wifiIconId The container for the WIFI icon.
     * @param level The level.
     */
    void customizeWifiSignalStrengthIconList(IconIdWrapper wifiIconId, int level);

    /**
     * @param icon the icon wrappered need to change.0
     * @param wifiActivity the wifi index
     */
    void customizeWifiInOutIcon(IconIdWrapper icon, int wifiActivity);

    /**
     * @return Return if enable WIFI when at airplane mode.
     */
    boolean customizeEnableWifiAtAirplaneMode(boolean isEnabled);

    /** WIFI interfaces. @} */

    /** Bluetooth interfaces. @{ */

    /**
     * @return Return if enable Bluetooth when at airplane mode.
     */
    boolean customizeEnableBluetoothtAirplaneMode(boolean isEnabled);

    /** Bluetooth interfaces. @} */

    /** Resource interfaces. @{ */

    /** Resource interfaces. @} */

    /**
     * Get the mobile group should visible.
     *
     * @param isSimInserted the combine condition for judgment.
     * @return true if mobile group should show.
     */
    boolean customizeMobileGroupVisible(boolean isSimInserted);

    /**
    * Customise the network type for showing corresponding icons.
     *
    * @return NetworkType
     */
    public NetworkType customizeNetworkType(boolean roaming, int dataNetType, NetworkType networkType);


   /**
    * Customise the data type for showing corresponding icons.
    *
    * @return NetworkType
     */
   public DataType customizeDataType(boolean roaming, int dataNetType, DataType dataType);

    /**
     * Customize the hspa distinguishable.
     *
     * @param distinguishable Default value.
     * @return The customized distinguishable value.
     */
    boolean customizeHspaDistinguishable(boolean distinguishable);

    /**
     * Customize SignalCluster view.
     * @return ISignalClusterExt
     */
    ISignalClusterExt customizeSignalCluster();
}
