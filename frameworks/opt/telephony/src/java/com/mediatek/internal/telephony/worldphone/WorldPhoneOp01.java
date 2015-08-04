/* Copyright Statement:
 *
 * This software/firmware and related documentation ("MediaTek Software") are
 * protected under relevant copyright laws. The information contained herein
 * is confidential and proprietary to MediaTek Inc. and/or its licensors.
 * Without the prior written permission of MediaTek inc. and/or its licensors,
 * any reproduction, modification, use or disclosure of MediaTek Software,
 * and information contained herein, in whole or in part, shall be strictly prohibited.
 *
 * MediaTek Inc. (C) 2010. All rights reserved.
 *
 * BY OPENING THIS FILE, RECEIVER HEREBY UNEQUIVOCALLY ACKNOWLEDGES AND AGREES
 * THAT THE SOFTWARE/FIRMWARE AND ITS DOCUMENTATIONS ("MEDIATEK SOFTWARE")
 * RECEIVED FROM MEDIATEK AND/OR ITS REPRESENTATIVES ARE PROVIDED TO RECEIVER ON
 * AN "AS-IS" BASIS ONLY. MEDIATEK EXPRESSLY DISCLAIMS ANY AND ALL WARRANTIES,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE OR NONINFRINGEMENT.
 * NEITHER DOES MEDIATEK PROVIDE ANY WARRANTY WHATSOEVER WITH RESPECT TO THE
 * SOFTWARE OF ANY THIRD PARTY WHICH MAY BE USED BY, INCORPORATED IN, OR
 * SUPPLIED WITH THE MEDIATEK SOFTWARE, AND RECEIVER AGREES TO LOOK ONLY TO SUCH
 * THIRD PARTY FOR ANY WARRANTY CLAIM RELATING THERETO. RECEIVER EXPRESSLY ACKNOWLEDGES
 * THAT IT IS RECEIVER'S SOLE RESPONSIBILITY TO OBTAIN FROM ANY THIRD PARTY ALL PROPER LICENSES
 * CONTAINED IN MEDIATEK SOFTWARE. MEDIATEK SHALL ALSO NOT BE RESPONSIBLE FOR ANY MEDIATEK
 * SOFTWARE RELEASES MADE TO RECEIVER'S SPECIFICATION OR TO CONFORM TO A PARTICULAR
 * STANDARD OR OPEN FORUM. RECEIVER'S SOLE AND EXCLUSIVE REMEDY AND MEDIATEK'S ENTIRE AND
 * CUMULATIVE LIABILITY WITH RESPECT TO THE MEDIATEK SOFTWARE RELEASED HEREUNDER WILL BE,
 * AT MEDIATEK'S OPTION, TO REVISE OR REPLACE THE MEDIATEK SOFTWARE AT ISSUE,
 * OR REFUND ANY SOFTWARE LICENSE FEES OR SERVICE CHARGE PAID BY RECEIVER TO
 * MEDIATEK FOR SUCH MEDIATEK SOFTWARE AT ISSUE.
 *
 * The following software/firmware and/or related documentation ("MediaTek Software")
 * have been modified by MediaTek Inc. All revisions are subject to any receiver's
 * applicable license agreements with MediaTek Inc.
 */
package com.mediatek.internal.telephony.worldphone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.SystemProperties;
import android.provider.Settings;
import android.telephony.ServiceState;
import android.telephony.Rlog;

import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.PhoneProxy;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.uicc.IccRecords;
import com.android.internal.telephony.uicc.UiccController;

import com.mediatek.internal.telephony.ModemSwitchHandler;

/**
 *@hide
 */
public class WorldPhoneOp01 extends Handler implements IWorldPhone {
    private static Object sLock = new Object();
    private static final int PROJECT_SIM_NUM = WorldPhoneUtil.getProjectSimNum();
    private static final int[] FDD_STANDBY_TIMER = {
        60
    };
    private static final int[] TDD_STANDBY_TIMER = {
        40
    };
    private static final String[] PLMN_TABLE_TYPE1 = {
        "46000", "46002", "46007", "46008", "46011",
        // Lab test IMSI
        "00101", "00211", "00321", "00431", "00541", "00651",
        "00761", "00871", "00902", "01012", "01122", "01232",
        "46004", "46602", "50270", "46003"
    };
    private static final String[] PLMN_TABLE_TYPE3 = {
        "46001", "46006", "46009", "45407",
        "46005", "45502"
    };
    private static final String[] MCC_TABLE_DOMESTIC = {
        "460",
        // Lab test PLMN
        "001", "002", "003", "004", "005", "006",
        "007", "008", "009", "010", "011", "012"
    };

    private static Context sContext = null;
    private static Phone sDefultPhone = null;
    private static Phone[] sProxyPhones = null;
    private static Phone[] sActivePhones = new Phone[PROJECT_SIM_NUM];
    private static CommandsInterface[] sCi = new CommandsInterface[PROJECT_SIM_NUM];
    private static String sOperatorSpec;
    private static String sPlmnSs;
    private static String sLastPlmn;
    private static String[] sImsi = new String[PROJECT_SIM_NUM];
    private static String[] sNwPlmnStrings;
    private static int sVoiceRegState;
    private static int sDataRegState;
    private static int sRilVoiceRegState;
    private static int sRilDataRegState;
    private static int sRilVoiceRadioTechnology;
    private static int sRilDataRadioTechnology;
    private static int sUserType;
    private static int sRegion;
    private static int sDenyReason;
    private static int sSuspendId;
    private static int sMajorSim;
    private static int sDefaultBootuUpModem = ModemSwitchHandler.MD_TYPE_UNKNOWN;
    private static int[] sIccCardType = new int[PROJECT_SIM_NUM];
    private static boolean sVoiceCapable;
    private static boolean[] sIsInvalidSim = new boolean[PROJECT_SIM_NUM];
    private static boolean[] sSuspendWaitImsi = new boolean[PROJECT_SIM_NUM];
    private static boolean[] sFirstSelect = new boolean[PROJECT_SIM_NUM];
    private static UiccController sUiccController = null;
    private static IccRecords[] sIccRecordsInstance = new IccRecords[PROJECT_SIM_NUM];
    private static ServiceState sServiceState;
    private static ModemSwitchHandler sModemSwitchHandler = null;
    private static int sTddStandByCounter;
    private static int sFddStandByCounter;
    private static boolean sWaitInTdd;
    private static boolean sWaitInFdd;

    public WorldPhoneOp01() {
        logd("Constructor invoked");
        sOperatorSpec = SystemProperties.get("ro.operator.optr", NO_OP);
        logd("Operator Spec:" + sOperatorSpec);
        sDefultPhone = PhoneFactory.getDefaultPhone();
        sProxyPhones = PhoneFactory.getPhones();
        for (int i = 0; i < PROJECT_SIM_NUM; i++) {
            sActivePhones[i] = ((PhoneProxy) sProxyPhones[i]).getActivePhone();
            sCi[i] = ((PhoneBase) sActivePhones[i]).mCi;
        }
        for (int i = 0; i < PROJECT_SIM_NUM; i++) {
            sCi[i].setOnPlmnChangeNotification(this, EVENT_REG_PLMN_CHANGED_1 + i, null);
            sCi[i].setOnRegistrationSuspended(this, EVENT_REG_SUSPENDED_1 + i, null);
            sCi[i].registerForOn(this, EVENT_RADIO_ON_1 + i, null);
            sCi[i].setInvalidSimInfo(this, EVENT_INVALID_SIM_NOTIFY_1 + i, null);
        }

        sModemSwitchHandler = new ModemSwitchHandler();
        logd(ModemSwitchHandler.modemToString(ModemSwitchHandler.getActiveModemType()));

        IntentFilter intentFilter = new IntentFilter(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        intentFilter.addAction(TelephonyIntents.ACTION_SERVICE_STATE_CHANGED);
        intentFilter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        intentFilter.addAction(ACTION_SHUTDOWN_IPO);
        intentFilter.addAction(ACTION_ADB_SWITCH_MODEM);
        intentFilter.addAction(TelephonyIntents.ACTION_SET_PHONE_RAT_FAMILY_DONE);
        if (sDefultPhone != null) {
            sContext = sDefultPhone.getContext();
        } else {
            logd("DefaultPhone = null");
        }
        sVoiceCapable = sContext.getResources().getBoolean(com.android.internal.R.bool.config_voice_capable);
        sContext.registerReceiver(mWorldPhoneReceiver, intentFilter);

        sTddStandByCounter = 0;
        sFddStandByCounter = 0;
        sWaitInTdd = false;
        sWaitInFdd = false;
        sRegion = REGION_UNKNOWN;
        sLastPlmn = null;
        resetAllProperties();
        if (WorldPhoneUtil.getModemSelectionMode() == SELECTION_MODE_MANUAL) {
            logd("Auto select disable");
            sMajorSim = AUTO_SWITCH_OFF;
            Settings.Global.putInt(sContext.getContentResolver(),
                    Settings.Global.WORLD_PHONE_AUTO_SELECT_MODE, SELECTION_MODE_MANUAL);
        } else {
            logd("Auto select enable");
            Settings.Global.putInt(sContext.getContentResolver(),
                    Settings.Global.WORLD_PHONE_AUTO_SELECT_MODE, SELECTION_MODE_AUTO);
        }
        FDD_STANDBY_TIMER[sFddStandByCounter] = Settings.Global.getInt(
                sContext.getContentResolver(), Settings.Global.WORLD_PHONE_FDD_MODEM_TIMER, FDD_STANDBY_TIMER[sFddStandByCounter]);
        Settings.Global.putInt(sContext.getContentResolver(),
                Settings.Global.WORLD_PHONE_FDD_MODEM_TIMER, FDD_STANDBY_TIMER[sFddStandByCounter]);
        logd("FDD_STANDBY_TIMER = " + FDD_STANDBY_TIMER[sFddStandByCounter] + "s");
        logd("sDefaultBootuUpModem = " + sDefaultBootuUpModem);
    }

    private final BroadcastReceiver mWorldPhoneReceiver = new  BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            logd("[Receiver]+");
            String action = intent.getAction();
            logd("Action: " + action);
            int slotId;
            if (action.equals(TelephonyIntents.ACTION_SIM_STATE_CHANGED)) {
                String simStatus = intent.getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE);
                slotId = intent.getIntExtra(PhoneConstants.SLOT_KEY, PhoneConstants.SIM_ID_1);
                logd("slotId: " + slotId + " simStatus: " + simStatus);
                if (simStatus.equals(IccCardConstants.INTENT_VALUE_ICC_IMSI)) {
                    if (sMajorSim == MAJOR_SIM_UNKNOWN) {
                        sMajorSim = WorldPhoneUtil.getMajorSim();
                    }
                    sUiccController = UiccController.getInstance();
                    if (sUiccController != null) {
                        sIccRecordsInstance[slotId] = sUiccController.getIccRecords(slotId, UiccController.APP_FAM_3GPP);
                    } else {
                        logd("Null sUiccController");
                        return;
                    }
                    if (sIccRecordsInstance[slotId] != null) {
                        sImsi[slotId] = sIccRecordsInstance[slotId].getIMSI();
                    } else {
                        logd("Null sIccRecordsInstance");
                        return;
                    }
                    sIccCardType[slotId] = getIccCardType(slotId);
                    logd("sImsi[" + slotId + "]:" + sImsi[slotId]);
                    if (slotId == sMajorSim) {
                        logd("Major SIM");
                        sUserType = getUserType(sImsi[slotId]);
                        if (sFirstSelect[slotId]) {
                            sFirstSelect[slotId] = false;
                            if (sUserType == TYPE1_USER || sUserType == TYPE2_USER) {
                                if (sRegion == REGION_DOMESTIC) {
                                    handleSwitchModem(ModemSwitchHandler.MD_TYPE_TDD);
                                } else if (sRegion == REGION_FOREIGN) {
                                    handleSwitchModem(ModemSwitchHandler.MD_TYPE_FDD);
                                } else {
                                    logd("Region unknown");
                                }
                            } else if (sUserType == TYPE3_USER) {
                                handleSwitchModem(ModemSwitchHandler.MD_TYPE_TDD);
                            }
                        }
                        if (sSuspendWaitImsi[slotId]) {
                            sSuspendWaitImsi[slotId] = false;
                            logd("IMSI fot slot" + slotId + " now ready, resuming PLMN:"
                                    + sNwPlmnStrings[0] + " with ID:" + sSuspendId);
                            resumeCampingProcedure(slotId);
                        }
                    } else {
                        logd("Not major SIM");
                        getUserType(sImsi[slotId]);
                    }
                } else if (simStatus.equals(IccCardConstants.INTENT_VALUE_ICC_ABSENT)) {
                    sLastPlmn = null;
                    sImsi[slotId] = "";
                    sFirstSelect[slotId] = true;
                    sIsInvalidSim[slotId] = false;
                    sSuspendWaitImsi[slotId] = false;
                    sIccCardType[slotId] = ICC_CARD_TYPE_UNKNOWN;
                    if (slotId == sMajorSim) {
                        logd("Major SIM removed, no world phone service");
                        removeModemStandByTimer();
                        sUserType = UNKNOWN_USER;
                        sDenyReason = CAMP_ON_DENY_REASON_UNKNOWN;
                        sMajorSim = MAJOR_SIM_UNKNOWN;
                    } else {
                        logd("SIM" + slotId + " is not major SIM");
                    }
                }
            } else if (action.equals(TelephonyIntents.ACTION_SERVICE_STATE_CHANGED)) {
                sServiceState = ServiceState.newFromBundle(intent.getExtras());
                if (sServiceState != null) {
                    slotId = intent.getIntExtra(PhoneConstants.SLOT_KEY, PhoneConstants.SIM_ID_1);
                    sPlmnSs = sServiceState.getOperatorNumeric();
                    sVoiceRegState = sServiceState.getVoiceRegState();
                    sRilVoiceRegState = sServiceState.getRilVoiceRegState();
                    sRilVoiceRadioTechnology = sServiceState.getRilVoiceRadioTechnology();
                    sDataRegState = sServiceState.getDataRegState();
                    sRilDataRegState = sServiceState.getRilDataRegState();
                    sRilDataRadioTechnology = sServiceState.getRilDataRadioTechnology();
                    logd("slotId: " + slotId + ", " + WorldPhoneUtil.iccCardTypeToString(sIccCardType[slotId]));
                    logd("sMajorSim: " + sMajorSim);
                    logd(ModemSwitchHandler.modemToString(ModemSwitchHandler.getActiveModemType()));
                    logd("sPlmnSs: " + sPlmnSs);
                    logd("sVoiceRegState: " + WorldPhoneUtil.stateToString(sVoiceRegState));
                    logd("sRilVoiceRegState: " + WorldPhoneUtil.regStateToString(sRilVoiceRegState));
                    logd("sRilVoiceRadioTech: " + sServiceState.rilRadioTechnologyToString(sRilVoiceRadioTechnology));
                    logd("sDataRegState: " + WorldPhoneUtil.stateToString(sDataRegState));
                    logd("sRilDataRegState: " + WorldPhoneUtil.regStateToString(sRilDataRegState));
                    logd("sRilDataRadioTech: " + sServiceState.rilRadioTechnologyToString(sRilDataRadioTechnology));
                    if (slotId == sMajorSim) {
                        if (isNoService()) {
                            handleNoService();
                        } else if (isInService()) {
                            sLastPlmn = sPlmnSs;
                            removeModemStandByTimer();
                        }
                    }
                } else {
                    logd("Null sServiceState");
                }
            } else if (action.equals(ACTION_SHUTDOWN_IPO)) {
                if (sDefaultBootuUpModem == ModemSwitchHandler.MD_TYPE_FDD) {
                    if (WorldPhoneUtil.isLteSupport()) {
                        ModemSwitchHandler.reloadModem(sCi[PhoneConstants.SIM_ID_1], ModemSwitchHandler.MD_TYPE_LWG);
                        logd("Reload to FDD CSFB modem");
                    } else {
                        ModemSwitchHandler.reloadModem(sCi[PhoneConstants.SIM_ID_1], ModemSwitchHandler.MD_TYPE_WG);
                        logd("Reload to WG modem");
                    }
                } else if (sDefaultBootuUpModem == ModemSwitchHandler.MD_TYPE_TDD) {
                    if (WorldPhoneUtil.isLteSupport()) {
                        ModemSwitchHandler.reloadModem(sCi[PhoneConstants.SIM_ID_1], ModemSwitchHandler.MD_TYPE_LTG);
                        logd("Reload to TDD CSFB modem");
                    } else {
                        ModemSwitchHandler.reloadModem(sCi[PhoneConstants.SIM_ID_1], ModemSwitchHandler.MD_TYPE_TG);
                        logd("Reload to TG modem");
                    }
                }
            } else if (action.equals(ACTION_ADB_SWITCH_MODEM)) {
                int toModem = intent.getIntExtra(TelephonyIntents.EXTRA_MD_TYPE, ModemSwitchHandler.MD_TYPE_UNKNOWN);
                logd("toModem: " + toModem);
                if (toModem == ModemSwitchHandler.MD_TYPE_WG
                        || toModem == ModemSwitchHandler.MD_TYPE_TG
                        || toModem == ModemSwitchHandler.MD_TYPE_LWG
                        || toModem == ModemSwitchHandler.MD_TYPE_LTG) {
                    setModemSelectionMode(IWorldPhone.SELECTION_MODE_MANUAL, toModem);
                } else {
                    setModemSelectionMode(IWorldPhone.SELECTION_MODE_AUTO, toModem);
                }
            } else if (action.equals(Intent.ACTION_AIRPLANE_MODE_CHANGED)) {
                if (intent.getBooleanExtra("state", false) == false) {
                    logd("Leave flight mode");
                    sLastPlmn = null;
                    for (int i = 0; i < PROJECT_SIM_NUM; i++) {
                        sIsInvalidSim[i] = false;
                    }
                } else {
                    logd("Enter flight mode");
                    for (int i = 0; i < PROJECT_SIM_NUM; i++) {
                        sFirstSelect[i] = true;
                    }
                }
            } else if (action.equals(TelephonyIntents.ACTION_SET_PHONE_RAT_FAMILY_DONE)) {
                if (sMajorSim != AUTO_SWITCH_OFF) {
                    sMajorSim = WorldPhoneUtil.getMajorSim();
                }
                handleSimSwitched();
            }
            logd("[Receiver]-");
        }
    };

    public void handleMessage(Message msg) {
        AsyncResult ar = (AsyncResult) msg.obj;
        switch (msg.what) {
            case EVENT_RADIO_ON_1:
                logd("handleMessage : <EVENT_RADIO_ON>");
                handleRadioOn(PhoneConstants.SIM_ID_1);
                break;
            case EVENT_REG_PLMN_CHANGED_1:
                logd("handleMessage : <EVENT_REG_PLMN_CHANGED>");
                handlePlmnChange(ar, PhoneConstants.SIM_ID_1);
                break;
            case EVENT_REG_SUSPENDED_1:
                logd("handleMessage : <EVENT_REG_SUSPENDED>");
                handleRegistrationSuspend(ar, PhoneConstants.SIM_ID_1);
                break;
            case EVENT_RADIO_ON_2:
                logd("handleMessage : <EVENT_RADIO_ON>");
                handleRadioOn(PhoneConstants.SIM_ID_2);
                break;
            case EVENT_REG_PLMN_CHANGED_2:
                logd("handleMessage : <EVENT_REG_PLMN_CHANGED>");
                handlePlmnChange(ar, PhoneConstants.SIM_ID_2);
                break;
            case EVENT_REG_SUSPENDED_2:
                logd("handleMessage : <EVENT_REG_SUSPENDED>");
                handleRegistrationSuspend(ar, PhoneConstants.SIM_ID_2);
                break;
            case EVENT_INVALID_SIM_NOTIFY_1:
                logd("handleMessage : <EVENT_INVALID_SIM_NOTIFY>");
                handleInvalidSimNotify(PhoneConstants.SIM_ID_1, ar);
                break;
            case EVENT_INVALID_SIM_NOTIFY_2:
                logd("handleMessage : <EVENT_INVALID_SIM_NOTIFY>");
                handleInvalidSimNotify(PhoneConstants.SIM_ID_2, ar);
                break;
            default:
                logd("Unknown msg:" + msg.what);
        }
    }

    private void handleRadioOn(int slotId) {
        logd("Slot" + slotId);
        if (sMajorSim == MAJOR_SIM_UNKNOWN) {
            sMajorSim = WorldPhoneUtil.getMajorSim();
        }
    }

    private void handlePlmnChange(AsyncResult ar, int slotId) {
        logd("Slot" + slotId);
        if (sMajorSim == MAJOR_SIM_UNKNOWN) {
            sMajorSim = WorldPhoneUtil.getMajorSim();
        }
        if (ar.exception == null && ar.result != null) {
            String[] plmnString = (String[]) ar.result;
            if (slotId == sMajorSim) {
                sNwPlmnStrings = plmnString;
            }
            for (int i = 0; i < plmnString.length; i++) {
                logd("plmnString[" + i + "]=" + plmnString[i]);
            }
            if (sMajorSim == slotId && (sUserType == TYPE1_USER || sUserType == TYPE2_USER)
                    && sDenyReason != CAMP_ON_DENY_REASON_NEED_SWITCH_TO_FDD) {
                searchForDesignateService(plmnString[0]);
            }
            // To speed up performance in foreign countries, once get PLMN(no matter which slot)
            // determine region right away and switch modem type if needed
            sRegion = getRegion(plmnString[0]);
            if (sUserType != TYPE3_USER && sRegion == REGION_FOREIGN
                    && sMajorSim != AUTO_SWITCH_OFF && sMajorSim != MAJOR_CAPABILITY_OFF) {
                handleSwitchModem(ModemSwitchHandler.MD_TYPE_FDD);
            }
        } else {
            logd("AsyncResult is wrong " + ar.exception);
        }
    }

    private void handleRegistrationSuspend(AsyncResult ar, int slotId) {
        logd("Slot" + slotId);
        if (ar.exception == null && ar.result != null) {
            sSuspendId = ((int[]) ar.result)[0];
            logd("Suspending with Id=" + sSuspendId);
            if (sMajorSim == slotId) {
                if (sUserType != UNKNOWN_USER) {
                    resumeCampingProcedure(slotId);
                } else {
                    sSuspendWaitImsi[slotId] = true;
                    logd("User type unknown, wait for IMSI");
                }
            } else {
                logd("Not major slot, camp on OK");
                sCi[slotId].setResumeRegistration(sSuspendId, null);
            }
        } else {
            logd("AsyncResult is wrong " + ar.exception);
        }
    }

    private void handleInvalidSimNotify(int slotId, AsyncResult ar) {
        logd("Slot" + slotId);
        if (ar.exception == null && ar.result != null) {
            String[] invalidSimInfo = (String[]) ar.result;
            String plmn = invalidSimInfo[0];
            int cs_invalid = Integer.parseInt(invalidSimInfo[1]);
            int ps_invalid = Integer.parseInt(invalidSimInfo[2]);
            int cause = Integer.parseInt(invalidSimInfo[3]);
            int testMode = -1;
            testMode = SystemProperties.getInt("gsm.gcf.testmode", 0);
            if (testMode != 0) {
                logd("Invalid SIM notified during test mode: " + testMode);
                return;
            }
            logd("testMode:" + testMode + ", cause: " + cause + ", cs_invalid: " + cs_invalid + ", ps_invalid: " + ps_invalid + ", plmn: " + plmn);
            if (sVoiceCapable && cs_invalid == 1) {
                if (sLastPlmn == null) {
                    logd("CS reject, invalid SIM");
                    sIsInvalidSim[slotId] = true;
                    return;
                }
            }
            if (ps_invalid == 1) {
                if (sLastPlmn == null) {
                    logd("PS reject, invalid SIM");
                    sIsInvalidSim[slotId] = true;
                    return;
                }
            }
        } else {
            logd("AsyncResult is wrong " + ar.exception);
        }
    }

    private void handleSwitchModem(int toModem) {
        if (sIsInvalidSim[WorldPhoneUtil.getMajorSim()]
                && WorldPhoneUtil.getModemSelectionMode() == SELECTION_MODE_AUTO) {
            logd("Invalid SIM, switch not executed!");
            return;
        }
        if (toModem == ModemSwitchHandler.MD_TYPE_TDD) {
            if (WorldPhoneUtil.isLteSupport()) {
                toModem = ModemSwitchHandler.MD_TYPE_LTG;
            } else {
                toModem = ModemSwitchHandler.MD_TYPE_TG;
            }
        } else if (toModem == ModemSwitchHandler.MD_TYPE_FDD) {
            if (WorldPhoneUtil.isLteSupport()) {
                toModem = ModemSwitchHandler.MD_TYPE_LWG;
            } else {
                toModem = ModemSwitchHandler.MD_TYPE_WG;
            }
        }
        if (sMajorSim == AUTO_SWITCH_OFF) {
            logd("Storing modem type: " + toModem);
            sCi[PhoneConstants.SIM_ID_1].storeModemType(toModem, null);
        } else {
            if (sDefaultBootuUpModem == ModemSwitchHandler.MD_TYPE_UNKNOWN) {
                logd("Storing modem type: " + toModem);
                sCi[PhoneConstants.SIM_ID_1].storeModemType(toModem, null);
            } else if (sDefaultBootuUpModem == ModemSwitchHandler.MD_TYPE_FDD) {
                if (WorldPhoneUtil.isLteSupport()) {
                    logd("Storing modem type: " + ModemSwitchHandler.MD_TYPE_WG);
                    sCi[PhoneConstants.SIM_ID_1].storeModemType(ModemSwitchHandler.MD_TYPE_LWG, null);
                } else {
                    logd("Storing modem type: " + ModemSwitchHandler.MD_TYPE_LWG);
                    sCi[PhoneConstants.SIM_ID_1].storeModemType(ModemSwitchHandler.MD_TYPE_WG, null);
                }
            } else if (sDefaultBootuUpModem == ModemSwitchHandler.MD_TYPE_TDD) {
                if (WorldPhoneUtil.isLteSupport()) {
                    logd("Storing modem type: " + ModemSwitchHandler.MD_TYPE_WG);
                    sCi[PhoneConstants.SIM_ID_1].storeModemType(ModemSwitchHandler.MD_TYPE_LTG, null);
                } else {
                    logd("Storing modem type: " + ModemSwitchHandler.MD_TYPE_LWG);
                    sCi[PhoneConstants.SIM_ID_1].storeModemType(ModemSwitchHandler.MD_TYPE_TG, null);
                }
            }
        }
        if (toModem == ModemSwitchHandler.getActiveModemType()) {
            if (toModem == ModemSwitchHandler.MD_TYPE_WG) {
                logd("Already in WG modem");
            } else if (toModem == ModemSwitchHandler.MD_TYPE_TG) {
                logd("Already in TG modem");
            } else if (toModem == ModemSwitchHandler.MD_TYPE_LWG) {
                logd("Already in FDD CSFB modem");
            } else if (toModem == ModemSwitchHandler.MD_TYPE_LTG) {
                logd("Already in TDD CSFB modem");
            }
            return;
        } else {
            for (int i = 0; i < PROJECT_SIM_NUM; i++) {
                if (sActivePhones[i].getState() != PhoneConstants.State.IDLE) {
                    logd("Phone" + i + " is not idle, modem switch not allowed");
                    return;
                }
            }
            removeModemStandByTimer();
            if (toModem == ModemSwitchHandler.MD_TYPE_WG) {
                logd("Switching to WG modem");
            } else if (toModem == ModemSwitchHandler.MD_TYPE_TG) {
                logd("Switching to TG modem");
            } else if (toModem == ModemSwitchHandler.MD_TYPE_LWG) {
                logd("Switching to FDD CSFB modem");
            } else if (toModem == ModemSwitchHandler.MD_TYPE_LTG) {
                logd("Switching to TDD CSFB modem");
            }
            ModemSwitchHandler.switchModem(toModem);
            resetNetworkProperties();
        }
    }

    private void handleSimSwitched() {
        if (sMajorSim == MAJOR_CAPABILITY_OFF) {
            logd("Major capability turned off");
            removeModemStandByTimer();
            sUserType = UNKNOWN_USER;
        } else if (sMajorSim == AUTO_SWITCH_OFF) {
            logd("Auto modem selection disabled");
            removeModemStandByTimer();
        } else if (sMajorSim == MAJOR_SIM_UNKNOWN) {
            logd("Major SIM unknown");
        } else {
            logd("Auto modem selection enabled");
            logd("Major capability in slot" + sMajorSim);
            if (sImsi[sMajorSim] == null || sImsi[sMajorSim].equals("")) {
                // may caused by receive 3g switched intent when boot up
                logd("Major slot IMSI not ready");
                sUserType = UNKNOWN_USER;
                return;
            }
            sUserType = getUserType(sImsi[sMajorSim]);
            if (sUserType == TYPE1_USER || sUserType == TYPE2_USER) {
                if (sNwPlmnStrings != null) {
                    sRegion = getRegion(sNwPlmnStrings[0]);
                }
                if (sRegion == REGION_DOMESTIC) {
                    sFirstSelect[sMajorSim] = false;
                    sIccCardType[sMajorSim] = getIccCardType(sMajorSim);
                    handleSwitchModem(ModemSwitchHandler.MD_TYPE_TDD);
                } else if (sRegion == REGION_FOREIGN) {
                    sFirstSelect[sMajorSim] = false;
                    handleSwitchModem(ModemSwitchHandler.MD_TYPE_FDD);
                } else {
                    logd("Unknown region");
                }
            } else if (sUserType == TYPE3_USER) {
                sFirstSelect[sMajorSim] = false;
                handleSwitchModem(ModemSwitchHandler.MD_TYPE_TDD);
            } else {
                logd("Unknown user type");
            }
        }
    }

    private void handleNoService() {
        logd("[handleNoService]+ Can not find service");
        logd("Type" + sUserType + " user");
        logd(WorldPhoneUtil.regionToString(sRegion));
        int mdType = ModemSwitchHandler.getActiveModemType();
        logd(ModemSwitchHandler.modemToString(mdType));
        IccCardConstants.State iccState = ((PhoneProxy) sProxyPhones[sMajorSim]).getIccCard().getState();
        if (iccState == IccCardConstants.State.READY) {
            if (sUserType == TYPE1_USER || sUserType == TYPE2_USER) {
                if (mdType == ModemSwitchHandler.MD_TYPE_LTG
                        || mdType == ModemSwitchHandler.MD_TYPE_TG) {
                    if (TDD_STANDBY_TIMER[sTddStandByCounter] >= 0) {
                        if (!sWaitInTdd) {
                            sWaitInTdd = true;
                            logd("Wait " + TDD_STANDBY_TIMER[sTddStandByCounter] + "s. Timer index = " + sTddStandByCounter);
                            postDelayed(mTddStandByTimerRunnable, TDD_STANDBY_TIMER[sTddStandByCounter] * 1000);
                        } else {
                            logd("Timer already set:" + TDD_STANDBY_TIMER[sTddStandByCounter] + "s");
                        }
                    } else {
                        logd("Standby in TDD modem");
                    }
                } else if (mdType == ModemSwitchHandler.MD_TYPE_LWG
                        || mdType == ModemSwitchHandler.MD_TYPE_WG) {
                    if (FDD_STANDBY_TIMER[sFddStandByCounter] >= 0) {
                        if (sRegion == REGION_FOREIGN) {
                            if (!sWaitInFdd) {
                                sWaitInFdd = true;
                                logd("Wait " + FDD_STANDBY_TIMER[sFddStandByCounter] + "s. Timer index = " + sFddStandByCounter);
                                postDelayed(mFddStandByTimerRunnable, FDD_STANDBY_TIMER[sFddStandByCounter] * 1000);
                            } else {
                                logd("Timer already set:" + FDD_STANDBY_TIMER[sFddStandByCounter] + "s");
                            }
                        } else {
                            handleSwitchModem(ModemSwitchHandler.MD_TYPE_TDD);
                        }
                    } else {
                        logd("Standby in FDD modem");
                    }
                }
            } else if (sUserType == TYPE3_USER) {
                if (mdType == ModemSwitchHandler.MD_TYPE_LTG
                        || mdType == ModemSwitchHandler.MD_TYPE_TG) {
                    logd("Standby in TDD modem");
                } else {
                    logd("Should not enter this state");
                }
            } else {
                logd("Unknow user type");
            }
        } else {
            logd("IccState not ready");
        }
        logd("[handleNoService]-");

        return;
    }

    private boolean isAllowCampOn(String plmnString, int slotId) {
        logd("[isAllowCampOn]+ " + plmnString);
        logd("User type: " + sUserType);
        logd(WorldPhoneUtil.iccCardTypeToString(sIccCardType[slotId]));
        sRegion = getRegion(plmnString);
        int mdType = ModemSwitchHandler.getActiveModemType();
        logd(ModemSwitchHandler.modemToString(mdType));
        if (sUserType == TYPE1_USER || sUserType == TYPE2_USER) {
            if (sRegion == REGION_DOMESTIC) {
                if (mdType == ModemSwitchHandler.MD_TYPE_LTG
                        || mdType == ModemSwitchHandler.MD_TYPE_TG) {
                    sDenyReason = CAMP_ON_NOT_DENIED;
                    logd("Camp on OK");
                    logd("[isAllowCampOn]-");
                    return true;
                } else if (mdType == ModemSwitchHandler.MD_TYPE_LWG
                        || mdType == ModemSwitchHandler.MD_TYPE_WG) {
                    sDenyReason = CAMP_ON_DENY_REASON_NEED_SWITCH_TO_TDD;
                    logd("Camp on REJECT");
                    logd("[isAllowCampOn]-");
                    return false;
                }
            } else if (sRegion == REGION_FOREIGN) {
                if (mdType == ModemSwitchHandler.MD_TYPE_LTG
                        || mdType == ModemSwitchHandler.MD_TYPE_TG) {
                    sDenyReason = CAMP_ON_DENY_REASON_NEED_SWITCH_TO_FDD;
                    logd("Camp on REJECT");
                    logd("[isAllowCampOn]-");
                    return false;
                } else if (mdType == ModemSwitchHandler.MD_TYPE_LWG
                        || mdType == ModemSwitchHandler.MD_TYPE_WG) {
                    sDenyReason = CAMP_ON_NOT_DENIED;
                    logd("Camp on OK");
                    logd("[isAllowCampOn]-");
                    return true;
                }
            } else {
                logd("Unknow region");
            }
        } else if (sUserType == TYPE3_USER) {
            if (mdType == ModemSwitchHandler.MD_TYPE_LTG
                    || mdType == ModemSwitchHandler.MD_TYPE_TG) {
                sDenyReason = CAMP_ON_NOT_DENIED;
                logd("Camp on OK");
                logd("[isAllowCampOn]-");
                return true;
            } else if (mdType == ModemSwitchHandler.MD_TYPE_LWG
                    || mdType == ModemSwitchHandler.MD_TYPE_WG) {
                sDenyReason = CAMP_ON_DENY_REASON_NEED_SWITCH_TO_TDD;
                logd("Camp on REJECT");
                logd("[isAllowCampOn]-");
                return false;
            }
        } else {
            logd("Unknown user type");
        }
        sDenyReason = CAMP_ON_DENY_REASON_UNKNOWN;
        logd("Camp on REJECT");
        logd("[isAllowCampOn]-");

        return false;
    }

    private boolean isInService() {
        boolean inService = false;

        if (sVoiceRegState == ServiceState.STATE_IN_SERVICE
                || sDataRegState == ServiceState.STATE_IN_SERVICE) {
            inService = true;
        }
        logd("inService: " + inService);

        return inService;
    }

    private boolean isNoService() {
        boolean noService = false;

        if (sVoiceRegState == ServiceState.STATE_OUT_OF_SERVICE
                && sRilVoiceRegState == ServiceState.REGISTRATION_STATE_NOT_REGISTERED_AND_NOT_SEARCHING
                && sDataRegState == ServiceState.STATE_OUT_OF_SERVICE
                && sRilDataRegState == ServiceState.REGISTRATION_STATE_NOT_REGISTERED_AND_NOT_SEARCHING) {
            noService = true;
        } else {
            noService = false;
        }
        logd("noService: " + noService);

        return noService;
    }

    private int getIccCardType(int slotId) {
        int simType;
        String simString = "Unknown";

        simString = ((PhoneProxy) sProxyPhones[slotId]).getIccCard().getIccCardType();
        if (simString.equals("SIM")) {
            logd("IccCard type: SIM");
            simType = ICC_CARD_TYPE_SIM;
        } else if (simString.equals("USIM")) {
            logd("IccCard type: USIM");
            simType = ICC_CARD_TYPE_USIM;
        } else {
            logd("IccCard type: Unknown");
            simType = ICC_CARD_TYPE_UNKNOWN;
        }

        return simType;
    }

    private int getRegion(String plmn) {
        String currentMcc;
        if (plmn == null) {
            logd("[getRegion] null PLMN");
            return REGION_UNKNOWN;
        }
        // Lab test PLMN 46602 & 50270 are Type1 & Domestic region
        // Other real world PLMN 466xx & 502xx are Type2 & Foreign region
        currentMcc = plmn.substring(0, 5);
        if (currentMcc.equals("46602") || currentMcc.equals("50270")) {
            return REGION_DOMESTIC;
        }
        currentMcc = plmn.substring(0, 3);
        for (String mcc : MCC_TABLE_DOMESTIC) {
            if (currentMcc.equals(mcc)) {
                logd("[getRegion] REGION_DOMESTIC");
                return REGION_DOMESTIC;
            }
        }
        logd("[getRegion] REGION_FOREIGN");
        return REGION_FOREIGN;
    }

    private int getUserType(String imsi) {
        if (imsi != null && !imsi.equals("")) {
            imsi = imsi.substring(0, 5);
            for (String mccmnc : PLMN_TABLE_TYPE1) {
                if (imsi.equals(mccmnc)) {
                    logd("[getUserType] Type1 user");
                    return TYPE1_USER;
                }
            }
            for (String mccmnc : PLMN_TABLE_TYPE3) {
                if (imsi.equals(mccmnc)) {
                    logd("[getUserType] Type3 user");
                    return TYPE3_USER;
                }
            }
            logd("[getUserType] Type2 user");
            return TYPE2_USER;
        } else {
            logd("[getUserType] null IMSI");
            return UNKNOWN_USER;
        }
    }

    private void resumeCampingProcedure(int slotId) {
        logd("Resume camping slot" + slotId);
        String plmnString = sNwPlmnStrings[0];
        if (isAllowCampOn(plmnString, slotId)) {
            removeModemStandByTimer();
            sCi[slotId].setResumeRegistration(sSuspendId, null);
        } else {
            logd("Because: " + WorldPhoneUtil.denyReasonToString(sDenyReason));
            if (sDenyReason == CAMP_ON_DENY_REASON_NEED_SWITCH_TO_FDD) {
                handleSwitchModem(ModemSwitchHandler.MD_TYPE_FDD);
            } else if (sDenyReason == CAMP_ON_DENY_REASON_NEED_SWITCH_TO_TDD) {
                handleSwitchModem(ModemSwitchHandler.MD_TYPE_TDD);
            }
        }
    }

    private Runnable mTddStandByTimerRunnable = new Runnable() {
        public void run() {
            sTddStandByCounter++;
            if (sTddStandByCounter >= TDD_STANDBY_TIMER.length) {
                sTddStandByCounter = TDD_STANDBY_TIMER.length - 1;
            }
            logd("TDD time out!");
            handleSwitchModem(ModemSwitchHandler.MD_TYPE_FDD);
        }
    };

    private Runnable mFddStandByTimerRunnable = new Runnable() {
        public void run() {
            sFddStandByCounter++;
            if (sFddStandByCounter >= FDD_STANDBY_TIMER.length) {
                sFddStandByCounter = FDD_STANDBY_TIMER.length - 1;
            }
            logd("FDD time out!");
            handleSwitchModem(ModemSwitchHandler.MD_TYPE_TDD);
        }
    };

    private void removeModemStandByTimer() {
        if (sWaitInTdd) {
            logd("Remove TDD wait timer. Set sWaitInTdd = false");
            sWaitInTdd = false;
            removeCallbacks(mTddStandByTimerRunnable);
        }
        if (sWaitInFdd) {
            logd("Remove FDD wait timer. Set sWaitInFdd = false");
            sWaitInFdd = false;
            removeCallbacks(mFddStandByTimerRunnable);
        }
    }

    private void resetAllProperties() {
        logd("[resetAllProperties]");
        sNwPlmnStrings = null;
        for (int i = 0; i < PROJECT_SIM_NUM; i++) {
            sFirstSelect[i] = true;
        }
        sDenyReason = CAMP_ON_DENY_REASON_UNKNOWN;
        resetSimProperties();
        resetNetworkProperties();
    }

    private void resetNetworkProperties() {
        logd("[resetNetworkProperties]");
        synchronized (sLock) {
            for (int i = 0; i < PROJECT_SIM_NUM; i++) {
                sSuspendWaitImsi[i] = false;
            }
        }
    }

    private void resetSimProperties() {
        logd("[resetSimProperties]");
        synchronized (sLock) {
            for (int i = 0; i < PROJECT_SIM_NUM; i++) {
                sImsi[i] = "";
                sIccCardType[i] = ICC_CARD_TYPE_UNKNOWN;
            }
            sUserType = UNKNOWN_USER;
            sMajorSim = WorldPhoneUtil.getMajorSim();
        }
    }

    private void searchForDesignateService(String strPlmn) {
        if (strPlmn == null) {
            logd("[searchForDesignateService]- null source");
            return;
        }
        strPlmn = strPlmn.substring(0, 5);
        for (String mccmnc : PLMN_TABLE_TYPE1) {
            if (strPlmn.equals(mccmnc)) {
                logd("Find TD service");
                logd("sUserType: " + sUserType + " sRegion: " + sRegion);
                logd(ModemSwitchHandler.modemToString(ModemSwitchHandler.getActiveModemType()));
                handleSwitchModem(ModemSwitchHandler.MD_TYPE_TDD);
                break;
            }
        }

        return;
    }

    public void setModemSelectionMode(int mode, int modemType) {
        Settings.Global.putInt(sContext.getContentResolver(),
                Settings.Global.WORLD_PHONE_AUTO_SELECT_MODE, mode);
        if (mode == SELECTION_MODE_AUTO) {
            logd("Modem Selection <AUTO>");
            sMajorSim = WorldPhoneUtil.getMajorSim();
            handleSimSwitched();
        } else {
            logd("Modem Selection <MANUAL>");
            sMajorSim = AUTO_SWITCH_OFF;
            handleSwitchModem(modemType);
        }
    }

    private static void logd(String msg) {
        Rlog.d(LOG_TAG, "[WPOP01]" + msg);
    }
}
