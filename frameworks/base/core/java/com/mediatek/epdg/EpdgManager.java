package com.mediatek.epdg;

import android.content.Context;

import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;

/**
 * A class to hanlde the Epdg Configuration for single or all APNs.
 *
 * @hide
 */

public class EpdgManager {
    private static final String TAG = "EpdgManager";

    private final Context mContext;
    private static EpdgManager sEpdgManager;
    private final IEpdgManager mService;

    /**
     * The APN type for Wi-Fi offloading.
     * @hide
     */
    public static final int TYPE_FAST        = 0;

    /**
     * The APN type for IMS services over Wi-Fi.
     * @hide
     */
    public static final int TYPE_IMS        = 1;

    /**
     * The APN type for extra purpose.
     * @hide
     */
    public static final int TYPE_NET        = 2;

    /**
     * The total APN type for EPDG.
     *{@hide} */
    public static final int MAX_NETWORK_NUM = TYPE_NET + 1;

    private EpdgManager(Context context) {
        IBinder b = ServiceManager.getService("epdg_service");
        mService = IEpdgManager.Stub.asInterface(b);
        mContext = context;
    }

    /**
     * Helpers to get the default EpdgManager.
     *
     * @param context the application context
     * @return instance of EpdgManager
     */
    public static EpdgManager getInstance(Context context) {

        synchronized (EpdgManager.class) {
            if (sEpdgManager == null) {
                sEpdgManager = new EpdgManager(context);
            }
        }
        return sEpdgManager;
    }

    /**
     * Get EPDG configuration.
     *
     * @param networkType the type of network.
     * @return the EPDG configuration, contained in {@link EpdgConfig}.
     */
    public EpdgConfig getConfiguration(int networkType) {
        if (mService == null) {
            return new EpdgConfig();
        }
        try {
            return mService.getConfiguration(networkType);
        } catch (RemoteException e) {
            return new EpdgConfig();
        }
    }

    /**
     * Get EPDG configuration.
     *
     * @return the EPDG configurations, contained in {@link EpdgConfig} array.
     */
    public EpdgConfig[] getConfiguration() {
        if (mService == null) {
            return null;
        }
        try {
            return mService.getAllConfiguration();
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Set EPDG configuration.
     *
     * @param networkType the type of network.
     * @param config the EPDG configuration, contained in {@link EpdgConfig} .
     */
    @SuppressWarnings("emptyblock")
    public void setConfiguration(int networkType, EpdgConfig config) {
        try {
            mService.setConfiguration(networkType, config);
        } catch (RemoteException e) {

        }
    }

    /**
     * Set EPDG configuration.
     *
     * @param config the EPDG configuration, contained in {@link EpdgConfig} array.
     */
    @SuppressWarnings("emptyblock")
    public void setConfiguration(EpdgConfig[] config) {
        try {
            mService.setAllConfiguration(config);
        } catch (RemoteException e) {

        }
    }
}