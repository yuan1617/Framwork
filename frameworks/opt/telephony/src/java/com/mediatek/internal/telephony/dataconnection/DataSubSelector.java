package com.mediatek.internal.telephony.dataconnection;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;

import android.telephony.PhoneRatFamily;
import android.telephony.Rlog;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import com.android.internal.telephony.ITelephony;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyIntents;

import com.mediatek.internal.telephony.RadioCapabilitySwitchUtil;

import java.util.Arrays;

public class DataSubSelector {
    private static final boolean DBG = true;

    private int mPhoneNum;

    private static final String PROPERTY_ICCID = "ril.iccid.sim";
    private static final String PROPERTY_DEFAULT_DATA_ICCID = "persist.radio.data.iccid";
    private static final String NO_SIM_VALUE = "N/A";

    private static final boolean BSP_PACKAGE =
            SystemProperties.getBoolean("ro.mtk_bsp_package", false);

    private static String mOperatorSpec;
    private static final String OPERATOR_OM = "OM";
    private static final String OPERATOR_OP01 = "OP01";
    private static final String OPERATOR_OP02 = "OP02";
    

    private static final String PROPERTY_3G_SIM = "persist.radio.simswitch";

    public static final String ACTION_MOBILE_DATA_ENABLE
            = "android.intent.action.ACTION_MOBILE_DATA_ENABLE";
    public static final String EXTRA_MOBILE_DATA_ENABLE_REASON = "reason";

    public static final String REASON_MOBILE_DATA_ENABLE_USER = "user";
    public static final String REASON_MOBILE_DATA_ENABLE_SYSTEM = "system";

    protected BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            log("onReceive: action=" + action);
            if (action.equals(TelephonyIntents.ACTION_SUBINFO_RECORD_UPDATED)) {
                onSubInfoReady(intent);
            }
        }
    };

    public DataSubSelector(Context context, int phoneNum) {
        log("DataSubSelector is created");
        mPhoneNum = phoneNum;
        mOperatorSpec = SystemProperties.get("ro.operator.optr", OPERATOR_OM);
        log("Operator Spec:" + mOperatorSpec);

        IntentFilter filter = new IntentFilter();
        filter.addAction(TelephonyIntents.ACTION_SUBINFO_RECORD_UPDATED);
        context.registerReceiver(mBroadcastReceiver, filter);
    }

    private void onSubInfoReady(Intent intent) {

        if (BSP_PACKAGE) {
            log("Don't support BSP Package.");
            return;
        }

        if (mOperatorSpec.equals(OPERATOR_OP01)) {
            subSelectorForOp01(intent);
        } else if (mOperatorSpec.equals(OPERATOR_OP02)) {
            subSelectorForOp02(intent);
        } else {
            subSelectorForOm(intent);
        }

    }

    private void subSelectorForOm(Intent intent) {
        int phoneId = SubscriptionManager.INVALID_PHONE_ID;
        int insertedSimCount = 0;
        int insertedStatus = 0;
        int detectedType = intent.getIntExtra(SubscriptionManager.INTENT_KEY_DETECT_STATUS, 0);
        String[] currIccId = new String[mPhoneNum];

        log("DataSubSelector for OM");

        for (int i = 0; i < mPhoneNum; i++) {
            currIccId[i] = SystemProperties.get(PROPERTY_ICCID + (i + 1));
            if (!NO_SIM_VALUE.equals(currIccId[i])) {
                ++insertedSimCount;
                insertedStatus = insertedStatus | (1 << i);
            }
        }
        log("Inserted SIM count: " + insertedSimCount + ", insertedStatus: " + insertedStatus);

        //Get previous default data
        String defaultIccid = SystemProperties.get(PROPERTY_DEFAULT_DATA_ICCID);
        log("Default data Iccid = " + defaultIccid);

        if (insertedSimCount == 0) {
            // No SIM inserted
            // 1. Default Data: unset
            // 2. Data Enable: OFF
            // 3. 34G: No change
            log("C0: No SIM inserted, set data unset");
            setDefaultData(SubscriptionManager.INVALID_PHONE_ID);
        } else if (insertedSimCount == 1) {
            for (int i = 0; i < mPhoneNum; i++) {
                if ((insertedStatus & (1 << i)) != 0) {
                    phoneId = i;
                    break;
                }
            }

            if (detectedType == SubscriptionManager.EXTRA_VALUE_NEW_SIM) {
                // Case 1: Single SIM + New SIM:
                // 1. Default Data: this sub
                // 2. Data Enable: OFF
                // 3. 34G: this sub
                log("C1: Single SIM + New SIM: Set Default data to phone:" + phoneId);
                if (setCapability(phoneId)) {
                    setDefaultData(phoneId);
                }
                setDataEnable(false);
            } else {
                if (defaultIccid == null || "".equals(defaultIccid)) {
                    //It happened from two SIMs without default SIM -> remove one SIM.
                    // Case 3: Single SIM + Non Data SIM:
                    // 1. Default Data: this sub
                    // 2. Data Enable: OFF
                    // 3. 34G: this sub
                    log("C3: Single SIM + Non Data SIM: Set Default data to phone:" + phoneId);
                    if (setCapability(phoneId)) {
                        setDefaultData(phoneId);
                    }
                    setDataEnable(false);
                } else {
                    if (defaultIccid.equals(currIccId[phoneId])) {
                        // Case 2: Single SIM + Defult Data SIM:
                        // 1. Default Data: this sub
                        // 2. Data Enable: No Change
                        // 3. 34G: this sub
                        log("C2: Single SIM + Data SIM: Set Default data to phone:" + phoneId);
                        if (setCapability(phoneId)) {
                            setDefaultData(phoneId);
                        }
                    } else {
                        // Case 3: Single SIM + Non Data SIM:
                        // 1. Default Data: this sub
                        // 2. Data Enable: OFF
                        // 3. 34G: this sub
                        log("C3: Single SIM + Non Data SIM: Set Default data to phone:" + phoneId);
                        if (setCapability(phoneId)) {
                            setDefaultData(phoneId);
                        }
                        setDataEnable(false);
                    }
                }
            }
        } else if (insertedSimCount >= 2) {
            if (detectedType == SubscriptionManager.EXTRA_VALUE_NEW_SIM) {
                int newSimStatus = intent.getIntExtra(
                        SubscriptionManager.INTENT_KEY_NEW_SIM_SLOT, 0);

                boolean isAllNewSim = true;
                for (int i = 0; i < mPhoneNum; i++) {
                    if ((newSimStatus & (1 << i)) == 0) {
                        isAllNewSim = false;
                    }
                }

                if (isAllNewSim) {
                    // Case 4: Multi SIM + All New SIM:
                    // 1. Default Data: Unset
                    // 2. Data Enable: OFF
                    // 3. 34G: Sub1
                    log("C4: Multi SIM + All New SIM: Set 34G to sub1");
                    if (setCapability(PhoneConstants.SIM_ID_1)) {
                        setDefaultData(SubscriptionManager.INVALID_PHONE_ID);
                    }
                    setDataEnable(false);
                } else {
                    if (defaultIccid == null || "".equals(defaultIccid)) {
                        //Not found previous default SIM, don't change.
                        // Case 6: Multi SIM + New SIM + Non Default SIM:
                        // 1. Default Data: Unset
                        // 2. Data Enable: OFF
                        // 3. 34G: No Change
                        log("C6: Multi SIM + New SIM + Non Default SIM: No Change");
                        setDefaultData(SubscriptionManager.INVALID_PHONE_ID);
                        setDataEnable(false);
                    } else {
                        for (int i = 0; i < mPhoneNum; i++) {
                            if (defaultIccid.equals(currIccId[i])) {
                                phoneId = i;
                                break;
                            }
                        }

                        if (phoneId != SubscriptionManager.INVALID_PHONE_ID) {
                            // Case 5: Multi SIM + New SIM + Default SIM:
                            // 1. Default Data: Default SIM
                            // 2. Data Enable: No Change
                            // 3. 34G: Default SIM
                            log("C5: Multi SIM + New SIM + Default SIM: Set Default data to "
                                + "phone:" + phoneId);
                            if (setCapability(phoneId)) {
                                setDefaultData(phoneId);
                            }
                        } else {
                            // Case 6: Multi SIM + New SIM + Non Default SIM:
                            // 1. Default Data: Unset
                            // 2. Data Enable: OFF
                            // 3. 34G: No Change
                            log("C6: Multi SIM + New SIM + Non Default SIM: No Change");
                            setDefaultData(SubscriptionManager.INVALID_PHONE_ID);
                            setDataEnable(false);
                        }
                    }
                }
            } else {
                if (defaultIccid == null || "".equals(defaultIccid)) {
                    //Case 8: Multi SIM + All Old SIM + No Default SIM:
                    // 1. Default Data: Unset
                    // 2. Data Enable: No Change
                    // 3. 34G: No change
                    //Do nothing
                    log("C8: Do nothing");
                } else {
                    for (int i = 0; i < mPhoneNum; i++) {
                        if (defaultIccid.equals(currIccId[i])) {
                            phoneId = i;
                            break;
                        }
                    }
                    if (phoneId != SubscriptionManager.INVALID_PHONE_ID) {
                        // Case 7: Multi SIM + All Old SIM + Default SIM:
                        // 1. Default Data: Default SIM
                        // 2. Data Enable: No Change
                        // 3. 34G: Default SIM
                        log("C7: Multi SIM + New SIM + Default SIM: Set Default data to phone:"
                                + phoneId);
                        if (setCapability(phoneId)) {
                            setDefaultData(phoneId);
                        }
                    } else {
                        //Case 8: Multi SIM + All Old SIM + No Default SIM:
                        // 1. Default Data: Unset
                        // 2. Data Enable: No Change
                        // 3. 34G: No change
                        //Do nothing
                        log("C8: Do nothing");
                    }
                }
            }
        }
    }

    private void subSelectorForOp02(Intent intent) {
        int phoneId = SubscriptionManager.INVALID_PHONE_ID;
        int insertedSimCount = 0;
        int insertedStatus = 0;
        int detectedType = intent.getIntExtra(SubscriptionManager.INTENT_KEY_DETECT_STATUS, 0);
        String[] currIccId = new String[mPhoneNum];

        log("DataSubSelector for OP02");

        for (int i = 0; i < mPhoneNum; i++) {
            currIccId[i] = SystemProperties.get(PROPERTY_ICCID + (i + 1));
            if (!NO_SIM_VALUE.equals(currIccId[i])) {
                ++insertedSimCount;
                insertedStatus = insertedStatus | (1 << i);
            }
        }
        log("Inserted SIM count: " + insertedSimCount + ", insertedStatus: " + insertedStatus);

        if (detectedType == SubscriptionManager.EXTRA_VALUE_NOCHANGE) {
            // OP02 Case 0: No SIM change, do nothing
            log("OP02 C0: Inserted status no change, do nothing");
        } else if (insertedSimCount == 0) {
            // OP02 Case 1: No SIM inserted
            // 1. Default Data: unset
            // 2. Data Enable: No Change
            // 3. 34G: Always SIM1
            log("OP02 C1: No SIM inserted, set data unset");
            setDefaultData(SubscriptionManager.INVALID_PHONE_ID);
        } else if (insertedSimCount == 1) {
            for (int i = 0; i < mPhoneNum; i++) {
                if ((insertedStatus & (1 << i)) != 0) {
                    phoneId = i;
                    break;
                }
            }
            //OP02 Case 2: Single SIM
            // 1. Default Data: This sub
            // 2. Data Enable: No Change
            // 3. 34G: Always SIM1
            log("OP02 C2: Single SIM: Set Default data to phone:" + phoneId);
            setDefaultData(phoneId);
        } else if (insertedSimCount >= 2) {
            //OP02 Case 3: Multi SIM
            // 1. Default Data: Always SIM1
            // 2. Data Enable: No Change
            // 3. 34G: Always SIM1
            log("OP02 C3: Multi SIM: Set Default data to phone1");
            setDefaultData(PhoneConstants.SIM_ID_1);
        }
    }

    private void subSelectorForOp01(Intent intent) {
        int phoneId = SubscriptionManager.INVALID_PHONE_ID;
        int insertedSimCount = 0;
        int insertedStatus = 0;
        int detectedType = intent.getIntExtra(SubscriptionManager.INTENT_KEY_DETECT_STATUS, 0);
        String[] currIccId = new String[mPhoneNum];

        log("DataSubSelector for op01");

        for (int i = 0; i < mPhoneNum; i++) {
            currIccId[i] = SystemProperties.get(PROPERTY_ICCID + (i + 1));
            if (!NO_SIM_VALUE.equals(currIccId[i])) {
                ++insertedSimCount;
                insertedStatus = insertedStatus | (1 << i);
            } else {
                log("clear mcc.mnc:" + i);
                String propStr;
                if (i == 0) {
                    propStr = "gsm.sim.ril.mcc.mnc";
                } else {
                    propStr = "gsm.sim.ril.mcc.mnc." + (i + 1);
                }
                SystemProperties.set(propStr, "");
            }
        }
        log("Inserted SIM count: " + insertedSimCount + ", insertedStatus: " + insertedStatus);

        //Get previous default data
        String defaultIccid = SystemProperties.get(PROPERTY_DEFAULT_DATA_ICCID);
        log("Default data Iccid = " + defaultIccid);

        if (insertedSimCount == 0) {
            // No SIM inserted
            // 1. Default Data: unset
            // 2. Data Enable: OFF
            // 3. 34G: No change
            log("C0: No SIM inserted, set data unset");
            setDefaultData(SubscriptionManager.INVALID_PHONE_ID);
        } else if (insertedSimCount == 1) {
            for (int i = 0; i < mPhoneNum; i++) {
                if ((insertedStatus & (1 << i)) != 0) {
                    phoneId = i;
                    break;
                }
            }

            if (detectedType == SubscriptionManager.EXTRA_VALUE_NEW_SIM) {
                // Case 1: Single SIM + New SIM:
                // 1. Default Data: this sub
                // 2. Data Enable: OFF
                // 3. 34G: this sub
                log("C1: Single SIM + New SIM: Set Default data to phone:" + phoneId);
                if (setCapability(phoneId)) {
                    setDefaultData(phoneId);
                }
                setDataEnable(false);
            } else {
                if (defaultIccid == null || "".equals(defaultIccid)) {
                    //It happened from two SIMs without default SIM -> remove one SIM.
                    // Case 3: Single SIM + Non Data SIM:
                    // 1. Default Data: this sub
                    // 2. Data Enable: OFF
                    // 3. 34G: this sub
                    log("C3: Single SIM + Non Data SIM: Set Default data to phone:" + phoneId);
                    if (setCapability(phoneId)) {
                        setDefaultData(phoneId);
                    }
                    setDataEnable(false);
                } else {
                    if (defaultIccid.equals(currIccId[phoneId])) {
                        // Case 2: Single SIM + Defult Data SIM:
                        // 1. Default Data: this sub
                        // 2. Data Enable: No Change
                        // 3. 34G: this sub
                        log("C2: Single SIM + Data SIM: Set Default data to phone:" + phoneId);
                        if (setCapability(phoneId)) {
                            setDefaultData(phoneId);
                        }
                    } else {
                        // Case 3: Single SIM + Non Data SIM:
                        // 1. Default Data: this sub
                        // 2. Data Enable: OFF
                        // 3. 34G: this sub
                        log("C3: Single SIM + Non Data SIM: Set Default data to phone:" + phoneId);
                        if (setCapability(phoneId)) {
                            setDefaultData(phoneId);
                        }
                        setDataEnable(false);
                    }
                }
            }
        } else if (insertedSimCount >= 2) {
            if (detectedType == SubscriptionManager.EXTRA_VALUE_NEW_SIM) {
                int newSimStatus = intent.getIntExtra(
                        SubscriptionManager.INTENT_KEY_NEW_SIM_SLOT, 0);

                boolean isAllNewSim = true;
                for (int i = 0; i < mPhoneNum; i++) {
                    if ((newSimStatus & (1 << i)) == 0) {
                        isAllNewSim = false;
                    }
                }

                if (isAllNewSim) {
                    // Case 4: Multi SIM + All New SIM:
                    // 1. Default Data: Unset
                    // 2. Data Enable: OFF
                    // 3. 34G: Sub1
                    log("C4: Multi SIM + All New SIM: Set 34G to sub1");
                    setDefaultData(SubscriptionManager.INVALID_PHONE_ID);
                    setDataEnable(false);
                } else {
                    if (defaultIccid == null || "".equals(defaultIccid)) {
                        //Not found previous default SIM, don't change.
                        // Case 6: Multi SIM + New SIM + Non Default SIM:
                        // 1. Default Data: Unset
                        // 2. Data Enable: OFF
                        // 3. 34G: No Change
                        log("C6: Multi SIM + New SIM + Non Default SIM: No Change");
                        setDefaultData(SubscriptionManager.INVALID_PHONE_ID);
                        setDataEnable(false);
                    } else {
                        for (int i = 0; i < mPhoneNum; i++) {
                            if (defaultIccid.equals(currIccId[i])) {
                                phoneId = i;
                                break;
                            }
                        }

                        if (phoneId != SubscriptionManager.INVALID_PHONE_ID) {
                            // Case 5: Multi SIM + New SIM + Default SIM:
                            // 1. Default Data: Default SIM
                            // 2. Data Enable: No Change
                            // 3. 34G: Default SIM
                            log("C5: Multi SIM + New SIM + Default SIM: Set Default data to "
                                + "phone:" + phoneId);
                            setDefaultData(phoneId);
                        } else {
                            // Case 6: Multi SIM + New SIM + Non Default SIM:
                            // 1. Default Data: Unset
                            // 2. Data Enable: OFF
                            // 3. 34G: No Change
                            log("C6: Multi SIM + New SIM + Non Default SIM: No Change");
                            setDefaultData(SubscriptionManager.INVALID_PHONE_ID);
                            setDataEnable(false);
                        }
                    }
                }
            } else {
                if (defaultIccid == null || "".equals(defaultIccid)) {
                    //Case 8: Multi SIM + All Old SIM + No Default SIM:
                    // 1. Default Data: Unset
                    // 2. Data Enable: No Change
                    // 3. 34G: No change
                    //Do nothing
                    loge("C8: Do nothing");
                } else {
                    for (int i = 0; i < mPhoneNum; i++) {
                        if (defaultIccid.equals(currIccId[i])) {
                            phoneId = i;
                            break;
                        }
                    }
                    if (phoneId != SubscriptionManager.INVALID_PHONE_ID) {
                        // Case 7: Multi SIM + All Old SIM + Default SIM:
                        // 1. Default Data: Default SIM
                        // 2. Data Enable: No Change
                        // 3. 34G: Default SIM
                        log("C7: Multi SIM + New SIM + Default SIM: Set Default data to phone:"
                                + phoneId);
                        setDefaultData(phoneId);
                    } else {
                        //Case 8: Multi SIM + All Old SIM + No Default SIM:
                        // 1. Default Data: Unset
                        // 2. Data Enable: No Change
                        // 3. 34G: No change
                        //Do nothing
                        loge("C8: Do nothing");
                    }
                }
            }
            // check if need to switch capability
            // op01 USIM > op01 SIM > oversea USIM > oversea SIM > others
            int[] simOpInfo = new int[mPhoneNum];
            int[] simType = new int[mPhoneNum];
            int targetSim = -1;
            boolean[] op01Usim = new boolean[mPhoneNum];
            boolean[] op01Sim = new boolean[mPhoneNum];
            boolean[] overseaUsim = new boolean[mPhoneNum];
            boolean[] overseaSim = new boolean[mPhoneNum];
            String capabilitySimIccid = SystemProperties.get(
                    RadioCapabilitySwitchUtil.MAIN_SIM_PROP);
            RadioCapabilitySwitchUtil.getSimInfo(simOpInfo, simType);
            int capabilitySimId = Integer.valueOf(
                    SystemProperties.get(PhoneConstants.CAPABILITY_SWITCH_PROP, "1")) - 1;
            log("op01: capabilitySimIccid:" + capabilitySimIccid
                    + "capabilitySimId:" + capabilitySimId);
            for (int i = 0; i < mPhoneNum; i++) {
                // update SIM status
                if (simOpInfo[i] == RadioCapabilitySwitchUtil.SIM_OP_INFO_OP01) {
                    if (simType[i] != RadioCapabilitySwitchUtil.SIM_TYPE_SIM) {
                        op01Usim[i] = true;
                    } else {
                        op01Sim[i] = true;
                    }
                } else if (simOpInfo[i] == RadioCapabilitySwitchUtil.SIM_OP_INFO_OVERSEA) {
                    if (simType[i] != RadioCapabilitySwitchUtil.SIM_TYPE_SIM) {
                        overseaUsim[i] = true;
                    } else {
                        overseaSim[i] = true;
                    }
                }
            }
            // dump sim op info
            log("op01Usim: " + Arrays.toString(op01Usim));
            log("op01Sim: " + Arrays.toString(op01Sim));
            log("overseaUsim: " + Arrays.toString(overseaUsim));
            log("overseaSim: " + Arrays.toString(overseaSim));

            for (int i = 0; i < mPhoneNum; i++) {
                if (capabilitySimIccid.equals(currIccId[i])) {
                    targetSim = RadioCapabilitySwitchUtil.getHigherPrioritySimForOp01(i, op01Usim
                            , op01Sim, overseaUsim, overseaSim);
                    log("op01: i = " + i + ", currIccId : " + currIccId[i]);
                    // default capability SIM is inserted
                    if (op01Usim[i] == true) {
                        log("op01-C1: cur is old op01 USIM, no change");
                        if (capabilitySimId != i) {
                            log("op01-C1a: old op01 USIM change slot, change!");
                            setCapability(i);
                        }
                        return;
                    } else if (op01Sim[i] == true) {
                        if (targetSim != -1) {
                            log("op01-C2: cur is old op01 SIM but find op01 USIM, change!");
                            setCapability(targetSim);
                        } else if (capabilitySimId != i) {
                            log("op01-C2a: old op01 SIM change slot, change!");
                            setCapability(i);
                        }
                        return;
                    } else if (overseaUsim[i] == true) {
                        if (targetSim != -1) {
                            log("op01-C3: cur is old OS USIM but find op01 SIMs, change!");
                            setCapability(targetSim);
                        } else if (capabilitySimId != i) {
                            log("op01-C3a: old OS USIM change slot, change!");
                            setCapability(i);
                        }
                        return;
                    } else if (overseaSim[i] == true) {
                        if (targetSim != -1) {
                            log("op01-C4: cur is old OS SIM but find op01 SIMs/OS USIM, change!");
                            setCapability(targetSim);
                        } else if (capabilitySimId != i) {
                            log("op01-C4a: old OS SIM change slot, change!");
                            setCapability(i);
                        }
                        return;
                    }
                    log("op01-C5: no higher priority SIM, no cahnge");
                    return;
                }
            }
            // cannot find default capability SIM, check if higher priority SIM exists
            targetSim = RadioCapabilitySwitchUtil.getHigherPrioritySimForOp01(capabilitySimId,
                    op01Usim, op01Sim, overseaUsim, overseaSim);
            log("op01: target SIM :" + targetSim);
            if (op01Usim[capabilitySimId] == true) {
                log("op01-C6: cur is new op01 USIM, no change");
                return;
            } else if (op01Sim[capabilitySimId] == true) {
                if (targetSim != -1) {
                    log("op01-C7: cur is new op01 SIM but find op01 USIM, change!");
                    setCapability(targetSim);
                }
                return;
            } else if (overseaUsim[capabilitySimId] == true) {
                if (targetSim != -1) {
                    log("op01-C8: cur is new OS USIM but find op01 SIMs, change!");
                    setCapability(targetSim);
                }
                return;
            } else if (overseaSim[capabilitySimId] == true) {
                if (targetSim != -1) {
                    log("op01-C9: cur is new OS SIM but find op01 SIMs/OS USIM, change!");
                    setCapability(targetSim);
                }
                return;
            } else if (targetSim != -1) {
                log("op01-C10: cur is non-op01 but find higher priority SIM, change!");
                setCapability(targetSim);
            } else {
                log("op01-C11: no higher priority SIM, no cahnge");
            }
            return;
        }
    }

    private void setDataEnable(boolean enable) {
        log("setDataEnable: " + enable);

        try {
            ITelephony iTel = ITelephony.Stub.asInterface(
                    ServiceManager.getService(Context.TELEPHONY_SERVICE));

            if (null == iTel) {
                loge("Can not get phone service");
                return;
            }

            iTel.setDataEnabled(enable);
        } catch (RemoteException ex) {
            ex.printStackTrace();
        }
    }

    private void setDefaultData(int phoneId) {
        long sub = SubscriptionManager.getSubIdUsingPhoneId(phoneId);
        long currSub = SubscriptionManager.getDefaultDataSubId();

        log("setDefaultData: " + sub + ", current default sub:" + currSub);
        if (sub != currSub) {
            SubscriptionManager.setDefaultDataSubId(sub);
        } else {
            log("setDefaultData: default data unchanged");
        }
    }

    private boolean setCapability(int phoneId) {
        int[] phoneRat = new int[mPhoneNum];
        boolean isSwitchSuccess = true;

        log("setCapability: " + phoneId);

        String curr3GSim = SystemProperties.get(PROPERTY_3G_SIM, "");
        log("current 3G Sim = " + curr3GSim);

        if (curr3GSim != null && !curr3GSim.equals("")) {
            int curr3GPhoneId = Integer.parseInt(curr3GSim);
            if (curr3GPhoneId == (phoneId + 1) ) {
                log("Current 3G phone equals target phone, don't trigger switch");
                return isSwitchSuccess;
            }
        }

        try {
            ITelephony iTel = ITelephony.Stub.asInterface(
                    ServiceManager.getService(Context.TELEPHONY_SERVICE));

            if (null == iTel) {
                loge("Can not get phone service");
                return false;
            }

            int currRat = iTel.getPhoneRat(phoneId);
            log("Current phoneRat:" + currRat);

            PhoneRatFamily[] rat = new PhoneRatFamily[mPhoneNum];
            for (int i = 0; i < mPhoneNum; i++) {
                if (phoneId == i) {
                    log("SIM switch to Phone" + i);
                    phoneRat[i] = PhoneRatFamily.PHONE_RAT_FAMILY_4G
                            | PhoneRatFamily.PHONE_RAT_FAMILY_3G
                            | PhoneRatFamily.PHONE_RAT_FAMILY_2G;
                } else {
                    phoneRat[i] = PhoneRatFamily.PHONE_RAT_FAMILY_2G;
                }
                rat[i] = new PhoneRatFamily(i, phoneRat[i]);
            }
            if (false == iTel.setPhoneRat(rat)) {
                log("Set phone rat fail!!!");
                isSwitchSuccess = false;
            }
        } catch (RemoteException ex) {
            log("Set phone rat fail!!!");
            ex.printStackTrace();
            isSwitchSuccess = false;
        }
        return isSwitchSuccess;
    }

    private void log(String txt) {
        if (DBG) {
            Rlog.d("DataSubSelector", txt);
        }
    }

    private void loge(String txt) {
        if (DBG) {
            Rlog.e("DataSubSelector", txt);
        }
    }
}
