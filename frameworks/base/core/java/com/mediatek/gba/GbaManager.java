package com.mediatek.gba;

import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;


/**
 * A class provides the GBA service APIs.
 *
 * @hide
 */
public final class GbaManager {
    private static final String TAG = "GbaManager";

    private final Context mContext;
    private static IGbaService mService;
    private static GbaManager mGbaManager = null;

    public static final int IMS_GBA_NONE     = 0;
    public static final int IMS_GBA_ME       = 1;
    public static final int IMS_GBA_U        = 2;

    public static final String IMS_GBA_KS_NAF       = "Ks_NAF";
    public static final String IMS_GBA_KS_EXT_NAF   = "Ks_ext_NAF";

    public static final byte[] DEFAULT_UA_SECURITY_PROTOCOL_ID0 = new byte[] {(byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00};
    public static final byte[] DEFAULT_UA_SECURITY_PROTOCOL_ID1 = new byte[] {(byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x01}; //MBMS
    public static final byte[] DEFAULT_UA_SECURITY_PROTOCOL_ID2 = new byte[] {(byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x02};
    public static final byte[] DEFAULT_UA_SECURITY_PROTOCOL_ID3 = new byte[] {(byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x03}; //MBMS

    /**
     * Helpers to get the default GbaManager.     
     */
    public static GbaManager getDefaultGbaManager(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context cannot be null");
        }

        synchronized (GbaManager.class) {
            if (mGbaManager == null) {
                IBinder b = ServiceManager.getService("GbaService");

                if (b == null) {
                    Log.i("debug", "The binder is null");
                    return null;
                }

                mService = IGbaService.Stub.asInterface(b);
                mGbaManager = new GbaManager(context);
            }

            return mGbaManager;
        }
    }

    GbaManager(Context context) {
        mContext = context;
    }

    /**
     * Check GBA is supported and support type or not.
     *
     * @return GBA Support Type     
     */
    public int getGbaSupported() {
        try {
            return mService.getGbaSupported();
        } catch (RemoteException e) {
            return 0;
        }
    }

    /**
     * Check GBA NAFSession key is expired or not.
     *
     * @param nafFqdn The FQDN address of NAF server
     * @param nafSecurProtocolId The security protocol id of NAF server
     * @return indicate key is expired or not
     */
    public boolean isGbaKeyExpired(String nafFqdn, byte[] nafSecurProtocolId) {
        try {
            return mService.isGbaKeyExpired(nafFqdn, nafSecurProtocolId);
        } catch (RemoteException e) {
            return true;
        }
    }

    /**
     * Perform GBA bootstrap authentication.
     *
     * @param nafFqdn The FQDN address of NAF server
     * @param nafSecurProtocolId The security protocol id of NAF server
     * @param forceRun Indicate to force run GBA bootstrap procedure without
     *                 get NAS Session key from GBA cache
     * @return GBA NAS Session Key     
     */
    public NafSessionKey runGbaAuthentication(String nafFqdn, byte[] nafSecurProtocolId,
            boolean forceRun) {
        try {
            return mService.runGbaAuthentication(nafFqdn, nafSecurProtocolId, forceRun);
        } catch (RemoteException e) {
            return null;
        }
    }
}