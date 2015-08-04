/* Copyright Statement:
 *
 * This software/firmware and related documentation ("MediaTek Software") are
 * protected under relevant copyright laws. The information contained herein is
 * confidential and proprietary to MediaTek Inc. and/or its licensors. Without
 * the prior written permission of MediaTek inc. and/or its licensors, any
 * reproduction, modification, use or disclosure of MediaTek Software, and
 * information contained herein, in whole or in part, shall be strictly
 * prohibited.
 *
 * MediaTek Inc. (C) 2014. All rights reserved.
 *
 * BY OPENING THIS FILE, RECEIVER HEREBY UNEQUIVOCALLY ACKNOWLEDGES AND AGREES
 * THAT THE SOFTWARE/FIRMWARE AND ITS DOCUMENTATIONS ("MEDIATEK SOFTWARE")
 * RECEIVED FROM MEDIATEK AND/OR ITS REPRESENTATIVES ARE PROVIDED TO RECEIVER
 * ON AN "AS-IS" BASIS ONLY. MEDIATEK EXPRESSLY DISCLAIMS ANY AND ALL
 * WARRANTIES, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE OR
 * NONINFRINGEMENT. NEITHER DOES MEDIATEK PROVIDE ANY WARRANTY WHATSOEVER WITH
 * RESPECT TO THE SOFTWARE OF ANY THIRD PARTY WHICH MAY BE USED BY,
 * INCORPORATED IN, OR SUPPLIED WITH THE MEDIATEK SOFTWARE, AND RECEIVER AGREES
 * TO LOOK ONLY TO SUCH THIRD PARTY FOR ANY WARRANTY CLAIM RELATING THERETO.
 * RECEIVER EXPRESSLY ACKNOWLEDGES THAT IT IS RECEIVER'S SOLE RESPONSIBILITY TO
 * OBTAIN FROM ANY THIRD PARTY ALL PROPER LICENSES CONTAINED IN MEDIATEK
 * SOFTWARE. MEDIATEK SHALL ALSO NOT BE RESPONSIBLE FOR ANY MEDIATEK SOFTWARE
 * RELEASES MADE TO RECEIVER'S SPECIFICATION OR TO CONFORM TO A PARTICULAR
 * STANDARD OR OPEN FORUM. RECEIVER'S SOLE AND EXCLUSIVE REMEDY AND MEDIATEK'S
 * ENTIRE AND CUMULATIVE LIABILITY WITH RESPECT TO THE MEDIATEK SOFTWARE
 * RELEASED HEREUNDER WILL BE, AT MEDIATEK'S OPTION, TO REVISE OR REPLACE THE
 * MEDIATEK SOFTWARE AT ISSUE, OR REFUND ANY SOFTWARE LICENSE FEES OR SERVICE
 * CHARGE PAID BY RECEIVER TO MEDIATEK FOR SUCH MEDIATEK SOFTWARE AT ISSUE.
 *
 * The following software/firmware and/or related documentation ("MediaTek
 * Software") have been modified by MediaTek Inc. All revisions are subject to
 * any receiver's applicable license agreements with MediaTek Inc.
 */

package com.mediatek.internal.telephony;

import android.os.SystemProperties;

import android.telephony.PhoneRatFamily;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;

import java.util.Arrays;

/**
 * Utility for capability switch.
 *
 */
public class RadioCapabilitySwitchUtil {
    private static final String LOG_TAG = "GSM";

    public static final int SIM_OP_INFO_OVERSEA = 0;
    public static final int SIM_OP_INFO_OP01 = 1;
    public static final int SIM_OP_INFO_OP02 = 2;

    public static final int SIM_TYPE_SIM = 0;
    public static final int SIM_TYPE_USIM = 1;
    public static final int SIM_TYPE_OTHER = 2;

    public static final String MAIN_SIM_PROP = "persist.radio.simswitch.iccid";
    private static final String PROPERTY_ICCID = "ril.iccid.sim";
    // OP01 SIMs
    private static final String[] PLMN_TABLE_TYPE1 = {
        "46000", "46002", "46007", "46008", "46011",
        // Lab test IMSI
        "00101", "00211", "00321", "00431", "00541", "00651",
        "00761", "00871", "00902", "01012", "01122", "01232",
        "46004", "46602", "50270", "46003"
    };

    // non-OP01 SIMs
    private static final String[] PLMN_TABLE_TYPE3 = {
        "46001", "46006", "46009", "45407",
        "46005", "45502"
    };

    /**
     * Update current main protocol ICCID.
     *
     * @param mProxyPhones Phone array for all phones
     */
    public static void updateIccid(Phone[] mProxyPhones) {
        for (int i = 0; i < mProxyPhones.length; i++) {
            if ((mProxyPhones[i].getPhoneRatFamily() & PhoneRatFamily.PHONE_RAT_FAMILY_3G)
                    == PhoneRatFamily.PHONE_RAT_FAMILY_3G) {
                String currIccId = SystemProperties.get(PROPERTY_ICCID + (i + 1));
                SystemProperties.set(MAIN_SIM_PROP, currIccId);
                logd("updateIccid " + currIccId);
                break;
            }
        }
    }

    /**
     * Get all SIMs operator and type.
     *
     * @param simOpInfo SIM operator info
     * @param simType SIM type
     */
    public static void getSimInfo(int[] simOpInfo, int[] simType) {
        String[] strMnc = new String[simOpInfo.length];
        String[] strSimType = new String[simOpInfo.length];
        String propStr;

        for (int i = 0; i < simOpInfo.length; i++) {
            if (i == 0) {
                propStr = "gsm.ril.uicctype";
            } else {
                propStr = "gsm.ril.uicctype." + (i + 1);
            }
            strSimType[i] = SystemProperties.get(propStr, "");
            if (strSimType[i].equals("SIM")) {
                simType[i] = RadioCapabilitySwitchUtil.SIM_TYPE_SIM;
            } else if (strSimType[i].equals("USIM")) {
                simType[i] = RadioCapabilitySwitchUtil.SIM_TYPE_USIM;
            } else {
                simType[i] = RadioCapabilitySwitchUtil.SIM_TYPE_OTHER;
            }
            logd("SimType[" + i + "]= " + strSimType[i] + ", simType[" + i + "]=" + simType[i]);
            strMnc[i] = TelephonyManager.getTelephonyProperty("gsm.sim.operator.numeric",
                    SubscriptionManager.getSubIdUsingPhoneId(i), "");
            logd("strMnc[" + i + "] from operator.numeric:" + strMnc[i]);
            if (strMnc[i].equals("")) {
                if (i == 0) {
                    propStr = "gsm.sim.ril.mcc.mnc";
                } else {
                    propStr = "gsm.sim.ril.mcc.mnc." + (i + 1);
                }
                strMnc[i] = SystemProperties.get(propStr, "");
                logd("strMnc[" + i + "] from ril.mcc.mnc:" + strMnc[i]);
            }
            for (String mccmnc : PLMN_TABLE_TYPE1) {
                if (strMnc[i].equals(mccmnc)) {
                    simOpInfo[i] = SIM_OP_INFO_OP01;
                }
            }
            for (String mccmnc : PLMN_TABLE_TYPE3) {
                if (strMnc[i].equals(mccmnc)) {
                    simOpInfo[i] = SIM_OP_INFO_OP02;
                }
            }
            logd("strMnc[" + i + "]= " + strMnc[i] + ", simOpInfo[" + i + "]=" + simOpInfo[i]);
        }
        logd("getSimInfo(simOpInfo): " + Arrays.toString(simOpInfo));
        logd("getSimInfo(simType): " + Arrays.toString(simType));
    }

    /**
     * Check if need to switch capability.
     *
     * @param mProxyPhones Phone array for all phones
     * @param rats new capability for phones
     * @return ture or false
     */
    public static boolean isNeedSwitchInOpPackage(Phone[] mProxyPhones, PhoneRatFamily[] rats) {
        String operatorSpec = SystemProperties.get("ro.operator.optr", "");
        int[] simOpInfo = new int[mProxyPhones.length];
        int[] simType = new int[mProxyPhones.length];

        logd("Operator Spec:" + operatorSpec);
        if (operatorSpec.equals("OP02")) {
            // disable capability switch in op02 package
            return false;
        } else if (operatorSpec.equals("OP01")) {
            // handle later
        } else {
            // OM package, default enable
            return true;
        }
        getSimInfo(simOpInfo, simType);
        // find target phone ID
        int targetPhoneId;
        for (targetPhoneId = 0; targetPhoneId < rats.length; targetPhoneId++) {
            if ((rats[targetPhoneId].getRatFamily() & PhoneRatFamily.PHONE_RAT_FAMILY_3G)
                == PhoneRatFamily.PHONE_RAT_FAMILY_3G) {
                break;
            }
        }
        if (operatorSpec.equals("OP01")) {
            return checkOp01(targetPhoneId, simOpInfo, simType);
        } else {
            return true;
        }
    }

    /**
     * Check if any higher priority SIM exists.
     *
     * @param curId current phone ID uses main capability
     * @param op01Usim array to indicate if op01 USIM
     * @param op01Sim array to indicate if op01 SIM
     * @param overseaUsim array to indicate if oversea USIM
     * @param overseaSim array to indicate if oversea SIM
     * @return higher priority SIM ID
     */
    public static int getHigherPrioritySimForOp01(int curId, boolean[] op01Usim, boolean[] op01Sim
            , boolean[] overseaUsim, boolean[] overseaSim) {
        int targetSim = -1;
        int phoneNum = op01Usim.length;

        if (op01Usim[curId] == true) {
            return curId;
        }
        for (int i = 0; i < phoneNum; i++) {
            if (op01Usim[i] == true) {
                targetSim = i;
            }
        }
        if (targetSim != -1 || op01Sim[curId] == true) {
            return targetSim;
        }
        for (int i = 0; i < phoneNum; i++) {
            if (op01Sim[i] == true) {
                targetSim = i;
            }
        }
        if (targetSim != -1 || overseaUsim[curId] == true) {
            return targetSim;
        }
        for (int i = 0; i < phoneNum; i++) {
            if (overseaUsim[i] == true) {
                targetSim = i;
            }
        }
        if (targetSim != -1 || overseaSim[curId] == true) {
            return targetSim;
        }
        for (int i = 0; i < phoneNum; i++) {
            if (overseaSim[i] == true) {
                targetSim = i;
            }
        }
        return targetSim;
    }

    private static boolean checkOp01(int targetPhoneId, int[] simOpInfo, int[] simType) {
        int curPhoneId = Integer.valueOf(
                SystemProperties.get(PhoneConstants.CAPABILITY_SWITCH_PROP, "1")) - 1;

        logd("checkOp01 : curPhoneId: " + curPhoneId);
        if (simOpInfo[targetPhoneId] == SIM_OP_INFO_OP01) {
            if (simType[targetPhoneId] == SIM_TYPE_SIM) {
                if ((simOpInfo[curPhoneId] == SIM_OP_INFO_OP01)
                    && simType[curPhoneId] != SIM_TYPE_SIM) {
                    logd("checkOp01 : case 1,2; stay in current phone");
                    return false;
                } else {
                    // case 3: op01-SIM + op01-SIM
                    // case 4: op01-SIM + others
                    logd("checkOp01 : case 3,4");
                    return true;
                }
            } else { // USIM, ISIM...
                // case 1: op01-USIM + op01-USIM
                // case 2: op01-USIM + others
                logd("checkOp01 : case 1,2");
                return true;
            }
        } else if (simOpInfo[targetPhoneId] == SIM_OP_INFO_OVERSEA) {
            if (simOpInfo[curPhoneId] == SIM_OP_INFO_OP01) {
                logd("checkOp01 : case 1,2,3,4; stay in current phone");
                return false;
            } else if (simType[targetPhoneId] == SIM_TYPE_SIM) {
                if ((simOpInfo[curPhoneId] == SIM_OP_INFO_OVERSEA)
                    && simType[curPhoneId] != SIM_TYPE_SIM) {
                    logd("checkOp01 : case 5,6; stay in current phone");
                    return false;
                } else {
                    // case 7: non-China SIM + non-China SIM
                    // case 8: non-China SIM + others
                    logd("checkOp01 : case 7,8");
                    return true;
                }
            } else { // USIM, ISIM...
                // case 5: non-China USIM + non-China USIM
                // case 6: non-China USIM + others
                logd("checkOp01 : case 5,6");
                return true;
            }
        } else {
            // case 9: non-op01 USIM/SIM + non-op01 USIM/SIM
            logd("checkOp01 : case 9");
            return false;
        }
    }

    private static void logd(String s) {
        Log.d(LOG_TAG, "[RadioCapSwitchUtil] " + s);
    }
}


