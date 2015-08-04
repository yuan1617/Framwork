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
 * MediaTek Inc. (C) 2010. All rights reserved.
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


package com.mediatek.internal.telephony.dataconnection;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.os.AsyncResult;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Messenger;
import android.os.SystemProperties;
import android.telephony.Rlog;
import android.telephony.SubscriptionManager;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.gsm.GSMPhone;

//import com.mediatek.settings.FeatureOption;
//import com.mediatek.common.featureoption.FeatureOption;

import java.util.ArrayList;

public class FdManager extends Handler {
    // M:Fast Dormancy Manager
    protected static final boolean DBG = true;
    protected static final String LOG_TAG = "FdManager";
    protected static final String PROPERTY_3G_SWITCH = "gsm.3gswitch";
    protected static final String PROPERTY_MTK_FD_SUPPORT = "ro.mtk_fd_support";
    protected static final String PROPERTY_RIL_FD_MODE = "ril.fd.mode";
    protected static final String PROPERTY_FD_ON_CHARGE = "fd.on.charge";
    protected static final String PROPERTY_FD_SCREEN_OFF_ONLY = "fd.screen.off.only";
    private static final String STR_PROPERTY_FD_SCREEN_ON_TIMER = "persist.radio.fd.counter";
    private static final String STR_PROPERTY_FD_SCREEN_ON_R8_TIMER = "persist.radio.fd.r8.counter";
    private static final String STR_PROPERTY_FD_SCREEN_OFF_TIMER = "persist.radio.fd.off.counter";
    private static final String
            STR_PROPERTY_FD_SCREEN_OFF_R8_TIMER = "persist.radio.fd.off.r8.counter";

    private static final int BASE = 0;
    private static final int EVENT_FD_MODE_SET = BASE + 0;

    public enum FdModeType {
        DISABLE_MD_FD,
        ENABLE_MD_FD,
        SET_FD_INACTIVITY_TIMER,
        INFO_MD_SCREEN_STATUS
    }

    public enum FdTimerType {
        ScreenOffLegacyFd,
        ScreenOnLegacyFd,
        ScreenOffR8Fd,
        ScreenOnR8Fd,
        SupportedTimerTypes
    }

    private static final SparseArray<FdManager> sFdManagers = new SparseArray<FdManager>();

    private PhoneBase mPhone;
    private boolean mChargingMode = false;
    private boolean mIsTetheredMode = false;
    private int mEnableFdOnCharing = 0;
    private boolean mIsScreenOn = true;
    private static int numberOfSupportedTypes;
    private static String timerValue[] = {"50", "150", "50", "150"};
    //Time Unit:0.1 sec => {5sec, 15sec, 5sec, 15sec}

    protected BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (DBG) logd("onReceive: action=" + action);
            int fdMdEnableMode = Integer.parseInt(SystemProperties.get(PROPERTY_RIL_FD_MODE, "0"));
            int fdSimId = SystemProperties.getInt(PROPERTY_3G_SWITCH, 1) - 1;
            if (action.equals(Intent.ACTION_SCREEN_ON)) {
                mIsScreenOn = true;
                if (isFdSupport()) {
                    if (DBG) logd("fdMdEnableMode=" + fdMdEnableMode + ", 3gSimID="
                            + fdSimId + ", when switching to SCREEN ON");
                    mIsScreenOn = true;
                    if (fdMdEnableMode == 1) {
                        //fdMdEnableMode == 1: It means that the Fast Dormancy polling
                        //                                  & decision mechanism is implemented by modem side
                        if (mPhone.getPhoneId() == fdSimId) {
                            mPhone.mCi.setFDMode(FdModeType.INFO_MD_SCREEN_STATUS.ordinal(), 1, -1,
                                    obtainMessage(EVENT_FD_MODE_SET));

                            if (isFdScreenOffOnly()) {
                                if (DBG) {
                                    logd("Because FD_SCREEN_OFF_ONLY, disable fd when screen on.");
                                }
                                updateFdMdEnableStatus(false);
                            }
                        }
                    } else {
                        logd("Not Support AP-trigger FD now");
                    }
                }
            } else if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                mIsScreenOn = false;
                if (isFdSupport()) {
                    if (fdMdEnableMode == 1) {
                        if (DBG) logd("fdMdEnableMode=" + fdMdEnableMode + ", 3gSimID="
                                + fdSimId + ", when switching to SCREEN OFF");
                        if (mPhone.getPhoneId() == fdSimId) {
                            mPhone.mCi.setFDMode(FdModeType.INFO_MD_SCREEN_STATUS.ordinal(), 0, -1,
                                    obtainMessage(EVENT_FD_MODE_SET));

                            if (isFdScreenOffOnly() && checkNeedTurnOn()) {
                                if (DBG) {
                                    logd("Because FD_SCREEN_OFF_ONLY, turn on fd when screen off.");
                                }
                                updateFdMdEnableStatus(true);
                            }
                        }
                    } else {
                        logd("Not Support AP-trigger FD now");
                    }
                }
            } else if (action.equals(Intent.ACTION_BATTERY_CHANGED)) {
                if (isFdSupport()){
                    int status = intent.getIntExtra("status", 0);
                    int plugged = intent.getIntExtra("plugged", 0);
                    boolean previousChargingMode = mChargingMode;

                    String sChargingModeStr = "", sPluggedStr = "";
                    if (status == BatteryManager.BATTERY_STATUS_CHARGING) {
                        mChargingMode = true;
                        sChargingModeStr = "Charging";
                    } else {
                        mChargingMode = false;
                        sChargingModeStr = "Non-Charging";
                    }

                    if (plugged == BatteryManager.BATTERY_PLUGGED_AC) {
                        sPluggedStr="Plugged in AC";
                    } else if (plugged == BatteryManager.BATTERY_PLUGGED_USB) {
                        sPluggedStr="Plugged in USB";
                    }

                    if ((plugged == BatteryManager.BATTERY_PLUGGED_AC)
                            || (plugged == BatteryManager.BATTERY_PLUGGED_USB)) {
                        mChargingMode = true;
                    }

                    int previousEnableFDOnCharging = mEnableFdOnCharing;
                    mEnableFdOnCharing =
                            Integer.parseInt(SystemProperties.get(PROPERTY_FD_ON_CHARGE, "0"));

                    if ((previousChargingMode != mChargingMode)
                            || (previousEnableFDOnCharging != mEnableFdOnCharing)) {
                        if (DBG) logd("fdMdEnableMode=" + fdMdEnableMode + ", 3gSimID="
                                + fdSimId + ", when charging state is changed");
                        if (DBG) logd("previousEnableFdOnCharging=" + previousEnableFDOnCharging
                                + ", mEnableFdOnCharing=" + mEnableFdOnCharing
                                + ", when charging state is changed");
                        if (DBG) logd("previousChargingMode=" + previousChargingMode
                                + ", mChargingMode=" + mChargingMode + ", status=" + status
                                + "(" + sPluggedStr + ")");
                    }


                    if (fdMdEnableMode == 1) {
                        if (mPhone.getPhoneId() == fdSimId) {
                            if ((previousChargingMode != mChargingMode)
                                    || (previousEnableFDOnCharging != mEnableFdOnCharing)) {
                                if (checkNeedTurnOn()) {
                                    updateFdMdEnableStatus(true);
                                } else {
                                    updateFdMdEnableStatus(false);
                                }
                            }
                        }
                    }
                }
            } else if (action.equals(ConnectivityManager.ACTION_TETHER_STATE_CHANGED)) {
                if (isFdSupport()){
                    logd("Received ConnectivityManager.ACTION_TETHER_STATE_CHANGED");
                    ArrayList<String> active =
                            intent.getStringArrayListExtra(ConnectivityManager.EXTRA_ACTIVE_TETHER);
                    mIsTetheredMode = ((active != null) && (active.size() > 0));
                    logd("[TETHER_STATE_CHANGED]mIsTetheredMode = " + mIsTetheredMode
                            + "mChargingMode=" + mChargingMode);
                    if (checkNeedTurnOn()) {
                        updateFdMdEnableStatus(true);
                    } else {
                        updateFdMdEnableStatus(false);
                    }
                }
            }
        }
    };


    public static FdManager getInstance(PhoneBase phone) {
        if (isFdSupport()) {
            if (phone.getPhoneId() < 0) {
                Rlog.e(LOG_TAG, "phoneId[" + phone.getPhoneId() + "]is invalid!");
                return null;
            }
            FdManager fdMgr = sFdManagers.get(phone.getPhoneId());
            if (fdMgr == null) {
                //PhoneBase phone = (PhoneBase) PhoneFactory.getPhone(phone.getPhoneId());
                if (phone != null) {
                    Rlog.d(LOG_TAG, "FDMagager for phoneId:" + phone.getPhoneId()
                            + " doesn't exist, create it");
                    fdMgr = new FdManager(phone);
                    sFdManagers.put(phone.getPhoneId(), fdMgr);
                } else {
                    Rlog.e(LOG_TAG, "FDMagager for phoneId:" + phone.getPhoneId()
                            + " can't get phone to init!");
                }
            }
            return fdMgr;
        }
        return null;
    }

    private FdManager(PhoneBase p) {
        mPhone = p;
        logd("initial FastDormancyManager");

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(ConnectivityManager.ACTION_TETHER_STATE_CHANGED);
        mPhone.getContext().registerReceiver(mIntentReceiver, filter, null, mPhone);

        initFdTimer();

    }

    public void dispose() {
        if (DBG) logd("FD.dispose");
        if (isFdSupport()){
            mPhone.getContext().unregisterReceiver(this.mIntentReceiver);
            sFdManagers.remove(mPhone.getPhoneId());
        }
    }

    private void initFdTimer(){
        String timerStr[] = new String[4];
        timerStr[0] = SystemProperties.get(STR_PROPERTY_FD_SCREEN_OFF_TIMER, "5");
        timerValue[FdTimerType.ScreenOffLegacyFd.ordinal()] =
                Integer.toString((int)(Double.parseDouble(timerStr[0])*10));
        timerStr[1] = SystemProperties.get(STR_PROPERTY_FD_SCREEN_ON_TIMER, "15");
        timerValue[FdTimerType.ScreenOnLegacyFd.ordinal()] =
                Integer.toString((int)(Double.parseDouble(timerStr[1])*10));
        timerStr[2] = SystemProperties.get(STR_PROPERTY_FD_SCREEN_OFF_R8_TIMER, "5");
        timerValue[FdTimerType.ScreenOffR8Fd.ordinal()] =
                Integer.toString((int)(Double.parseDouble(timerStr[2])*10));
        timerStr[3] = SystemProperties.get(STR_PROPERTY_FD_SCREEN_ON_R8_TIMER, "15");
        timerValue[FdTimerType.ScreenOnR8Fd.ordinal()] =
                Integer.toString((int)(Double.parseDouble(timerStr[3])*10));
        logd("Default FD timers=" + timerValue[0] + "," + timerValue[1] + ","
                + timerValue[2] + "," + timerValue[3]);

    }

    public int getNumberOfSupportedTypes() {
        return FdTimerType.SupportedTimerTypes.ordinal();
    }

    /**
       * setFdTimerValue
       * @param String array for new Timer Value
       * @param Message for on complete
       */
    public int setFdTimerValue(String newTimerValue[], Message onComplete) {
        int fdMdEnableMode = Integer.parseInt(SystemProperties.get(PROPERTY_RIL_FD_MODE, "0"));
        int fdSimId = SystemProperties.getInt(PROPERTY_3G_SWITCH, 1) - 1;
        if (isFdSupport() && fdMdEnableMode == 1 && mPhone.getPhoneId() == fdSimId) {
            // TODO: remove FeatureOption
            for (int i=0; i < newTimerValue.length; i++) {
                timerValue[i] = newTimerValue[i];
            }
            mPhone.mCi.setFDMode(FdModeType.SET_FD_INACTIVITY_TIMER.ordinal(),
                    FdTimerType.ScreenOffLegacyFd.ordinal(),
                    Integer.parseInt(timerValue[FdTimerType.ScreenOffLegacyFd.ordinal()]), null);
            mPhone.mCi.setFDMode(FdModeType.SET_FD_INACTIVITY_TIMER.ordinal(),
                    FdTimerType.ScreenOnLegacyFd.ordinal(),
                    Integer.parseInt(timerValue[FdTimerType.ScreenOnLegacyFd.ordinal()]), null);
            mPhone.mCi.setFDMode(FdModeType.SET_FD_INACTIVITY_TIMER.ordinal(),
                    FdTimerType.ScreenOffR8Fd.ordinal(),
                    Integer.parseInt(timerValue[FdTimerType.ScreenOffR8Fd.ordinal()]), null);
            mPhone.mCi.setFDMode(FdModeType.SET_FD_INACTIVITY_TIMER.ordinal(),
                    FdTimerType.ScreenOnR8Fd.ordinal(),
                    Integer.parseInt(timerValue[FdTimerType.ScreenOnR8Fd.ordinal()]), onComplete);
            logd("Set Default FD timers=" + timerValue[0] + "," + timerValue[1] + ","
                    + timerValue[2] + "," + timerValue[3]);
        }
        return 0;
    }

    /**
       * setFdTimerValue
       * @param String array for new Timer Value
       * @param Message for on complete
       * @param PhoneBase for input context
       */
    public int setFdTimerValue(String newTimerValue[], Message onComplete, PhoneBase phone) {
        FdManager fdMgr = getInstance(phone);
        if (fdMgr != null) {
            fdMgr.setFdTimerValue(newTimerValue, onComplete);
        } else {
            logd("setFDTimerValue fail!");
        }
        return 0;
    }

    /**
       * getFdTimerValue
       * @return FD Timer String array
       */
    public static String[] getFdTimerValue() {
        return timerValue;
    }

    @Override
    public void handleMessage(Message msg) {
        AsyncResult ar;
        switch (msg.what) {
            case EVENT_FD_MODE_SET:
                ar = (AsyncResult) msg.obj;
                if (ar.exception != null) {
                    if (DBG) logd("SET_FD_MODE ERROR");
                }
                break;
            default:
                Rlog.e("FdManager", "Unidentified event msg=" + msg);
                break;
        }
    }

    private void updateFdMdEnableStatus(boolean enabled) {
        int fdMdEnableMode = Integer.parseInt(SystemProperties.get(PROPERTY_RIL_FD_MODE, "0"));
        int fdSimId = SystemProperties.getInt(PROPERTY_3G_SWITCH, 1) - 1;
        if (DBG) logd("updateFdMdEnableStatus():enabled=" + enabled + ",fdMdEnableMode="
                + fdMdEnableMode + ", 3gSimID=" + fdSimId);
        if (fdMdEnableMode == 1 && mPhone.getPhoneId() == fdSimId) {
            if (enabled) {
                mPhone.mCi.setFDMode(FdModeType.ENABLE_MD_FD.ordinal(), -1, -1,
                        obtainMessage(EVENT_FD_MODE_SET));
            } else {
                mPhone.mCi.setFDMode(FdModeType.DISABLE_MD_FD.ordinal(), -1, -1,
                        obtainMessage(EVENT_FD_MODE_SET));
            }
        }
    }

    // TODO: check onRecordsLoaded
    public void disableFdWhenTethering(){
        if (isFdSupport()) {
            ConnectivityManager connMgr = (ConnectivityManager) mPhone.getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            if ((connMgr != null) && (connMgr.getTetheredIfaces() != null)) {
               mIsTetheredMode = (connMgr.getTetheredIfaces().length > 0);
            }

           logd("mIsTetheredMode = " + mIsTetheredMode + "mChargingMode=" + mChargingMode);
           if(checkNeedTurnOn()) {
               updateFdMdEnableStatus(true);
           } else {
               updateFdMdEnableStatus(false);
           }
        }
    }

    /**
       * checkNeedTurnOn.
       * when Fd Screen Off only mode, check screen state to make sure the turn on or not
       * @return boolean need or not
       */
    private boolean checkNeedTurnOn() {
        if (!(isFdScreenOffOnly() && mIsScreenOn) &&
            !(mChargingMode  && (mEnableFdOnCharing == 0)) &&
            !mIsTetheredMode) {
            return true;
        }
        return false;
    }

    public static boolean isFdScreenOffOnly() {
        if (SystemProperties.getInt(PROPERTY_FD_SCREEN_OFF_ONLY, 0) == 1) {
            return true;
        }
        return false;
    }

    public static boolean isFdSupport(){
        boolean isFdSupport =
                (SystemProperties.getInt(PROPERTY_MTK_FD_SUPPORT, 1) == 1) ? true : false;
        return isFdSupport;
    }

    protected void logd(String s) {
        Rlog.d(LOG_TAG, "[GDCT][phoneId" + mPhone.getPhoneId() + "]"+ s);
    }
}

