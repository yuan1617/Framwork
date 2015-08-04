package com.mediatek.common.mom;

import java.util.List;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

/**
 * This manager provides an interface for application to operate with MobileManagerService.
 */
public class MobileManager implements IMobileManager {
    private static final String TAG = "MobileManager";
    private Context mContext;
    private IMobileManagerService mService;
    MobileManager mInstance = null;

    private MobileManager() {};

    private boolean checkLicense(String packageName) {
        return true;
    }

    public MobileManager(Context context, IMobileManagerService service) {
        super();
        mContext = context;
        mService = service;
        if (mService == null) {
            throw new RuntimeException("null MobileManagerService!");
        }
    }

    /**
     * [Utility Functions]
     */
    public String getVersionName() {
        try {
            return mService.getVersionName();
        } catch (RemoteException e) {
            Log.e(TAG, "getVersionName() failed: ", e);
            return null;
        }
    }

    public boolean attach(IMobileConnectionCallback callback) {
        try {
            return mService.attach(callback);
        } catch (RemoteException e) {
            Log.e(TAG, "attach() failed: ", e);
            return false;
        }
    }

    public void detach() {
        try {
            mService.detach();
        } catch (RemoteException e) {
            Log.e(TAG, "detach() failed: ", e);
        }
    }

    public void clearAllSettings() {
        try {
            mService.clearAllSettings();
        } catch (RemoteException e) {
            Log.e(TAG, "clearAllSettings() failed: ", e);
        }
    }

    public void clearPackageSettings(String packageName) {
        try {
            mService.clearPackageSettings(packageName);
        } catch (RemoteException e) {
            Log.e(TAG, "clearPackageSettings() " + packageName + " failed: ", e);
        }
    }

    /**
     * [Permission Controller Functions]
     */
    public void enablePermissionController(boolean enable) {
        try {
            mService.enablePermissionController(enable);
        } catch (RemoteException e) {
            Log.e(TAG, "enablePermissionController() " + enable + " failed: ", e);
        }
    }

    public List<PackageInfo> getInstalledPackages() {
        try {
            return mService.getInstalledPackages();
        } catch (RemoteException e) {
            Log.e(TAG, "getInstalledPackages() failed: ", e);
            return null;
        }
    }

    public List<Permission> getPackageGrantedPermissions(String packageName) {
        try {
            return mService.getPackageGrantedPermissions(packageName);
        } catch (RemoteException e) {
            Log.e(TAG, "getPackageGrantedPermissions() " +
                  packageName + " failed: ", e);
            return null;
        }
    }

    public void registerPermissionListener(IPermissionListener listener) {
        try {
            mService.registerManagerApListener(IMobileManager.CONTROLLER_PERMISSION, (IBinder) listener);
        } catch (RemoteException e) {
            Log.e(TAG, "registerPermissionListener() " + listener + " failed: ", e);
        }
    }

    public void setPermissionRecord(PermissionRecord record) {
        try {
            mService.setPermissionRecord(record);
        } catch (RemoteException e) {
            Log.e(TAG, "setPermissionRecord() " + record.toString() + " failed: ", e);
        }
    }

    public void setPermissionRecords(List<PermissionRecord> records) {
        try {
            mService.setPermissionRecords(records);
        } catch (RemoteException e) {
            Log.e(TAG, "setPermissionRecord() " + records + " failed: ", e);
        }
    }

    public void setPermissionCache(List<PermissionRecord> cache) {
        try {
            mService.setPermissionCache(cache);
        } catch (RemoteException e) {
            Log.e(TAG, "setPermissionCache() " + cache + " failed: ", e);
        }
    }

    /**
     * [Receiver Controller Functions]
     */
    /**
     * Set the enabled setting for a package to receive BOOT_COMPLETED
     * Protection Level: License
     *
     * @param packageName The package to enable
     * @param enable The new enabled state for the package.
     */
    public void setBootReceiverEnabledSetting(String packageName, boolean enable) {
        try {
            mService.setBootReceiverEnabledSetting(packageName, enable);
        } catch (RemoteException e) {
            Log.e(TAG, "setBootReceiverEnabledSetting() " +
                  packageName + " enable: " + enable + " failed: ", e);
        }
    }

    /**
     * Return the the enabled setting for a package that receives BOOT_COMPLETED
     * Protection Level: License
     *
     * @param packageName The package to retrieve.
     * @return enable Returns the current enabled state for the package.
     */
    public boolean getBootReceiverEnabledSetting(String packageName) {
        try {
            return mService.getBootReceiverEnabledSetting(packageName);
        } catch (RemoteException e) {
            Log.e(TAG, "getBootReceiverEnabledSetting() " + packageName + " failed: ", e);
            return false;
        }
    }

    public void setBootReceiverEnabledSettings(List<ReceiverRecord> list) {
        try {
            mService.setBootReceiverEnabledSettings(list);
        } catch (RemoteException e) {
            Log.e(TAG, "setBootReceiverEnabledSettings()", e);
        }
    }

    public List<ReceiverRecord> getBootReceiverList() {
        try {
            return mService.getBootReceiverList();
        } catch (RemoteException e) {
            Log.e(TAG, "getBootReceiverList()", e);
        }
        return null;
    }

    /**
     * [Package Controller Functions]
     */
    /**
     * Forcestop the specified package.
     * Protection Level: License
     *
     * @param packageName The name of the package to be forcestoped.
     */
    public void forceStopPackage(String packageName) {
        try {
            mService.forceStopPackage(packageName);
        } catch (RemoteException e) {
            Log.e(TAG, "forceStopPackage() " + packageName + " failed: ", e);
        }
    }

    /**
     * Install a package. Since this may take a little while, the result will
     * be posted back to the given callback.
     * Protection Level: License
     *
     * @param packageURI The location of the package file to install.  This can be a 'file:' or a 'content:' URI.
     * @param callback An callback to get notified when the package installation is complete.
     */
    public void installPackage(Uri packageURI, IPackageInstallCallback callback) {
        try {
            mService.installPackage(packageURI, callback);
        } catch (RemoteException e) {
            Log.e(TAG, "installPackage() " + packageURI + " failed: ", e);
        }
    }

    /**
     * Attempts to delete a package.  Since this may take a little while, the result will
     * be posted back to the given callback.
     * Protection Level: License
     *
     * @param packageName The name of the package to delete
     */
    public void deletePackage(String packageName) {
        try {
            mService.deletePackage(packageName);
        } catch (RemoteException e) {
            Log.e(TAG, "deletePackage() " + packageName + " failed: ", e);
        }
    }

    /**
     * [Notification Controller Functions]
     */
    public void cancelNotification(String packageName) {
        try {
            mService.cancelNotification(packageName);
        } catch (RemoteException e) {
            Log.e(TAG, "cancelNotification() " + packageName + " failed: ", e);
        }
    }

    public void setNotificationEnabledSetting(String packageName, boolean enable) {
        try {
            mService.setNotificationEnabledSetting(packageName, enable);
        } catch (RemoteException e) {
            Log.e(TAG, "setNotificationEnabledSetting() " +
                  packageName + " enable: " + enable + " failed: ", e);
        }
    }

    public boolean getNotificationEnabledSetting(String packageName) {
        try {
            return mService.getNotificationEnabledSetting(packageName);
        } catch (RemoteException e) {
            Log.e(TAG, "getNotificationEnabledSetting() " + packageName + " failed: ", e);
            return false;
        }
    }

    public void registerNotificationListener(INotificationListener listener) {
        try {
            mService.registerManagerApListener(IMobileManager.CONTROLLER_NOTIFICATION, (IBinder) listener);
        } catch (RemoteException e) {
            Log.e(TAG, "registerNotificationListener() " + listener + " failed: ", e);
        }
    }

    public void setNotificationCache(List<NotificationCacheRecord> cache) {
        try {
            mService.setNotificationCache(cache);
        } catch (RemoteException e) {
            Log.e(TAG, "setNotificationCache()", e);
        }
    }

    /**
     * [Interception Controller Functions]
     * To enable or disable interception controller function.
     *
     * @param enable true to enable and false to disable.
     *
     */
    public void enableInterceptionController(boolean enable) {
        try {
            mService.enableInterceptionController(enable);
        } catch (RemoteException e) {
            Log.e(TAG, "enableInterceptionController() " + enable + " failed: ", e);
        }
    }

    /**
     * To register call interception controller listener(call back function).
     *
     * @param listener interface(call back function) to handle incoming call.
     *
     */
    public void registerCallInterceptionListener(ICallInterceptionListener listener) {
        try {
            mService.registerManagerApListener(IMobileManager.CONTROLLER_CALL, (IBinder) listener);
            /* Interception controller contains Call and Message controllers, so need to retister
               common interception listener */
            mService.registerManagerApListener(IMobileManager.CONTROLLER_INTERCEPTION, (IBinder) listener);
        } catch (RemoteException e) {
            Log.e(TAG, "registerCallInterceptionListener() " + listener + " failed: ", e);
        }
    }


    /**
     * [Firewall Controller Functions]
     */
    public void setFirewallPolicy(int appUid, int networkType, boolean enable) {
        try {
            mService.setFirewallPolicy(appUid, networkType, enable);
        } catch (RemoteException e) {
            Log.e(TAG, "setFirewallPolicy() " +
                  appUid + " networkType: " + networkType +
                  " enable: " + enable + " failed: ", e);
        }
    }

    /**
     * [Radio Controller Functions]
     */


    /**
     * [Message Intercept Controller Functions]
     */
    public void registerMessageInterceptListener(IMessageInterceptListener listener) {
        try {
            mService.registerManagerApListener(IMobileManager.CONTROLLER_MESSAGE_INTERCEPT,
                (IBinder) listener);
        } catch (RemoteException e) {
            Log.e(TAG, "registerMessageInterceptListener() " + listener + " failed: ", e);
        }
    }

}
