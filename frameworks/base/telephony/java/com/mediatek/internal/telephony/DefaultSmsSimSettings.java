
package com.mediatek.internal.telephony;

import android.util.Log;
import android.provider.Settings;
import android.telephony.SubscriptionManager;
import android.telephony.SubInfoRecord;
import android.telephony.TelephonyManager;
import android.os.SystemProperties;
import java.util.List;

public class DefaultSmsSimSettings {
    private static final String TAG = "DefaultSmsSimSettings";

    public static void setSmsTalkDefaultSim(List<SubInfoRecord> subInfos) {
        if (!"1".equals(SystemProperties.get("ro.mtk_bsp_package"))) {
            long oldSmsDefaultSIM = SubscriptionManager.getDefaultSmsSubId();
            Log.i(TAG, "oldSmsDefaultSIM" + oldSmsDefaultSIM);

            if (subInfos == null) {
                Log.i(TAG, "subInfos == null, set to : INVALID_SUB_ID");
                SubscriptionManager.setDefaultSmsSubId(SubscriptionManager.INVALID_SUB_ID);
            } else {
                Log.i(TAG, "subInfos size = " + subInfos.size());
                if (subInfos.size() > 1) {
                    if (isoldDefaultSMSSubIdActive(subInfos)) {
                        Log.i(TAG, "subInfos size > 1 & old available, set to :"
                                + oldSmsDefaultSIM);
                        SubscriptionManager.setDefaultSmsSubId(oldSmsDefaultSIM);
                    } else {
                        Log.i(TAG, "subInfos size > 1, set to : ASK_USER_SUB_ID");
                        SubscriptionManager
                                .setDefaultSmsSubId(SubscriptionManager.ASK_USER_SUB_ID);
                    }
                } else if (subInfos.size() == 1) {
                    SubscriptionManager.setDefaultSmsSubId(subInfos.get(0).subId);
                } else {
                    Log.i(TAG, "setSmsTalkDefaultSim SIM not insert");
                    SubscriptionManager.setDefaultSmsSubId(SubscriptionManager.INVALID_SUB_ID);
                }
            }
        }
    }

    private static boolean isoldDefaultSMSSubIdActive(List<SubInfoRecord> subInfos) {
        long oldSmsDefaultSIM = SubscriptionManager.getDefaultSmsSubId();

        for (SubInfoRecord subInfo : subInfos) {
            if (subInfo.subId == oldSmsDefaultSIM) {
                return true;
            }
        }
        return false;
    }
}
