package com.mediatek.systemui.statusbar.util;

import android.os.BatteryManager;


public class BatteryHelper {

    private static final String TAG = "BatteryHelper";
    public static final int FULL_LEVEL = 100;

    private BatteryHelper() {
    }

    public static boolean isBatteryFull(int level) {
        return (level >= FULL_LEVEL);
    }

    public static boolean isWirelessCharging(int mPlugType) {
        return (mPlugType == BatteryManager.BATTERY_PLUGGED_WIRELESS);
    }

    public static boolean isBatteryProtection(int status) {
        if (status != BatteryManager.BATTERY_STATUS_DISCHARGING
            && status != BatteryManager.BATTERY_STATUS_NOT_CHARGING) {
            return false;
        } else {
            return true;
        }
    }

    public static boolean isPlugForProtection(int status, int level) {
        boolean plugged = false;
        switch (status) {
            case BatteryManager.BATTERY_STATUS_CHARGING:
            case BatteryManager.BATTERY_STATUS_FULL:
                plugged = true;
                break;
        }
        return (plugged && !isBatteryFull(level) && !isBatteryProtection(status));
    }
}
