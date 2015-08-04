/**
 * Copyright (c) 2008, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.net.wifi;

import android.net.wifi.BatchedScanResult;
import android.net.wifi.BatchedScanSettings;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.ScanSettings;
import android.net.wifi.WifiChannel;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConnectionStatistics;
import android.net.wifi.WifiActivityEnergyInfo;

import android.net.DhcpInfo;


import android.os.Messenger;
import android.os.WorkSource;
//M:@{
import android.net.wifi.HotspotClient;
import android.net.wifi.PPPOEInfo;
import android.net.wifi.WpsInfo;
//@}

/**
 * Interface that allows controlling and querying Wi-Fi connectivity.
 *
 * {@hide}
 */
interface IWifiManager
{
    int getSupportedFeatures();

    WifiActivityEnergyInfo reportActivityInfo();

    List<WifiConfiguration> getConfiguredNetworks();

    List<WifiConfiguration> getPrivilegedConfiguredNetworks();

    int addOrUpdateNetwork(in WifiConfiguration config);

    boolean removeNetwork(int netId);

    boolean enableNetwork(int netId, boolean disableOthers);

    boolean disableNetwork(int netId);

    boolean pingSupplicant();

    List<WifiChannel> getChannelList();

    void startScan(in ScanSettings requested, in WorkSource ws);

    List<ScanResult> getScanResults(String callingPackage);

    void disconnect();

    void reconnect();

    void reassociate();

    WifiInfo getConnectionInfo();

    boolean setWifiEnabled(boolean enable);

    int getWifiEnabledState();

    void setCountryCode(String country, boolean persist);

    void setFrequencyBand(int band, boolean persist);

    int getFrequencyBand();

    boolean isDualBandSupported();

    boolean saveConfiguration();

    DhcpInfo getDhcpInfo();

    boolean isScanAlwaysAvailable();

    boolean acquireWifiLock(IBinder lock, int lockType, String tag, in WorkSource ws);

    void updateWifiLockWorkSource(IBinder lock, in WorkSource ws);

    boolean releaseWifiLock(IBinder lock);

    void initializeMulticastFiltering();

    boolean isMulticastEnabled();

    void acquireMulticastLock(IBinder binder, String tag);

    void releaseMulticastLock();

    void setWifiApEnabled(in WifiConfiguration wifiConfig, boolean enable);

    int getWifiApEnabledState();

    WifiConfiguration getWifiApConfiguration();

    void setWifiApConfiguration(in WifiConfiguration wifiConfig);

    void startWifi();

    void stopWifi();

    void addToBlacklist(String bssid);

    void clearBlacklist();

    Messenger getWifiServiceMessenger();

    String getConfigFile();

    void enableTdls(String remoteIPAddress, boolean enable);

    void enableTdlsWithMacAddress(String remoteMacAddress, boolean enable);

    boolean requestBatchedScan(in BatchedScanSettings requested, IBinder binder, in WorkSource ws);

    void stopBatchedScan(in BatchedScanSettings requested);

    List<BatchedScanResult> getBatchedScanResults(String callingPackage);

    boolean isBatchedScanSupported();

    void pollBatchedScan();

    String getWpsNfcConfigurationToken(int netId);

    void enableVerboseLogging(int verbose);

    int getVerboseLoggingLevel();

    int getAggressiveHandover();

    void enableAggressiveHandover(int enabled);

    int getAllowScansWithTraffic();

    void setAllowScansWithTraffic(int enabled);

    WifiConnectionStatistics getConnectionStatistics();

    // M: Added functions
    /**
     * @hide
     */ 
    String[] getAccessPointPreferredChannels();
    /**
     * @hide
     */ 
    boolean doCtiaTestOn();
    /**
     * @hide
     */ 
    boolean doCtiaTestOff();
    /**
     * @hide
     */ 
    boolean doCtiaTestRate(int rate);
    /**
     * @hide
     */ 
    boolean setTxPowerEnabled(boolean enable);
    /**
     * @hide
     */ 
    boolean setTxPower(int offset);

    /**
     * @hide
     */ 
    void startApWps(in WpsInfo config);
    /**
     * @hide
     */ 
    List<HotspotClient> getHotspotClients();
    /**
     * @hide
     */ 
    String getClientIp(String deviceAddress);
    /**
     * @hide
     */ 
    boolean blockClient(in HotspotClient client);
    /**
     * @hide
     */ 
    boolean unblockClient(in HotspotClient client);
    /**
     * @hide
     */ 
    boolean setApProbeRequestEnabled(boolean enable);
    /**
     * @hide
     */ 
    boolean setWifiEnabledForIPO(boolean enable);
    /**
     * @hide
     */ 
    void suspendNotification(int type);
    /**
     * @hide
     */ 
    boolean hasConnectableAp();
    /**
     * @hide
     */ 
    int syncGetConnectingNetworkId();
    /**
     * @hide
     */ 
    double getPoorLinkThreshold(boolean isGood);
    /**
     * @hide
     */ 
    boolean setPoorLinkThreshold(String key, double value);
    /**
     * @hide
     */ 
    void setPoorLinkProfilingOn(boolean enable);
    /**
     * @hide
     */ 
    String getWifiStatus();
    /**
     * @hide
     */ 
    void setPowerSavingMode(boolean mode);
    /**
     * @hide
     */ 
    PPPOEInfo getPPPOEInfo();
    /**
     *@hide
    */
    boolean setWoWlanNormalMode(); 
    /**
     *@hide
    */
    boolean setWoWlanMagicMode();
    /**
     *@hide
    */
    boolean is5gBandSupported();
}

