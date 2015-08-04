/*
 * Copyright (c) 2011-2013, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *     * Neither the name of The Linux Foundation nor the names of its
 *       contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.internal.telephony;

import java.util.ArrayList;

import android.content.Context;
import android.content.Intent;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.SystemProperties;

import android.provider.Settings;

import android.telephony.PhoneRatFamily;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.TelephonyManager.MultiSimVariants;

import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.dataconnection.DctController;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.PhoneProxy;
import com.android.internal.telephony.uicc.UiccController;

import com.mediatek.internal.telephony.RadioCapabilitySwitchUtil;

public class ProxyController {
    static final String LOG_TAG = "ProxyController";

    private static final int EVENT_PHONE_RAT_FAMILY_CHANGED_NOTIFY = 1;
    private static final int EVENT_SET_PHONE_RAT_FAMILY_RESPONSE   = 2;

    private static final int SET_PHONE_RAT_FAMILY_STATUS_IDLE = 0;
    private static final int SET_PHONE_RAT_FAMILY_STATUS_CHANGING = 1;
    private static final int SET_PHONE_RAT_FAMILY_STATUS_DONE = 2;

    //***** Class Variables
    private static ProxyController sProxyController;

    private Phone[] mProxyPhones;

    private UiccController mUiccController;

    private CommandsInterface[] mCi;

    private Context mContext;

    private static DctController mDctController;

    //UiccPhoneBookController to use proper IccPhoneBookInterfaceManagerProxy object
    private UiccPhoneBookController mUiccPhoneBookController;

    //PhoneSubInfoController to use proper PhoneSubInfoProxy object
    private PhoneSubInfoController mPhoneSubInfoController;

    //UiccSmsController to use proper IccSmsInterfaceManager object
    private UiccSmsController mUiccSmsController;

  //  private SubscriptionManager mSubscriptionManager;

    private int[] mSetPhoneRatFamilyStatus;

    //***** Class Methods
    public static ProxyController getInstance(Context context, Phone[] phoneProxy,
            UiccController uiccController, CommandsInterface[] ci) {
        if (sProxyController == null) {
            sProxyController = new ProxyController(context, phoneProxy, uiccController, ci);
        }
        return sProxyController;
    }

    static public ProxyController getInstance() {
        return sProxyController;
    }

    private ProxyController(Context context, Phone[] phoneProxy, UiccController uiccController,
            CommandsInterface[] ci) {
        logd("Constructor - Enter");

        mContext = context;
        mProxyPhones = phoneProxy;
        mUiccController = uiccController;
        mCi = ci;

        MultiSimVariants config = TelephonyManager.getDefault().getMultiSimConfiguration();
        mDctController = DctController.makeDctController((PhoneProxy[]) phoneProxy);

        mUiccPhoneBookController = new UiccPhoneBookController(mProxyPhones);
        mPhoneSubInfoController = new PhoneSubInfoController(mProxyPhones);
        mUiccSmsController = new UiccSmsController(mProxyPhones);
        mSetPhoneRatFamilyStatus = new int[mProxyPhones.length];

        for (int i = 0; i < mProxyPhones.length; i++) {
            ((PhoneProxy) mProxyPhones[i]).registerForPhoneRatFamilyChanged(
                mHandler, EVENT_PHONE_RAT_FAMILY_CHANGED_NOTIFY, null);
        }
        logd("Constructor - Exit");
    }

    public void updateDataConnectionTracker(int sub) {
        ((PhoneProxy) mProxyPhones[sub]).updateDataConnectionTracker();
    }

    public void enableDataConnectivity(int sub) {
        ((PhoneProxy) mProxyPhones[sub]).setInternalDataEnabled(true);
    }

    public void disableDataConnectivity(int sub,
            Message dataCleanedUpMsg) {
        ((PhoneProxy) mProxyPhones[sub]).setInternalDataEnabled(false, dataCleanedUpMsg);
    }

    public void updateCurrentCarrierInProvider(int sub) {
        ((PhoneProxy) mProxyPhones[sub]).updateCurrentCarrierInProvider();
    }

    public void registerForAllDataDisconnected(long subId, Handler h, int what, Object obj) {
        int phoneId = SubscriptionController.getInstance().getPhoneId(subId);

        if (phoneId >= 0 && phoneId < TelephonyManager.getDefault().getPhoneCount()) {
            ((PhoneProxy) mProxyPhones[phoneId]).registerForAllDataDisconnected(h, what, obj);
        }
    }

    public void unregisterForAllDataDisconnected(long subId, Handler h) {
        int phoneId = SubscriptionController.getInstance().getPhoneId(subId);

        if (phoneId >= 0 && phoneId < TelephonyManager.getDefault().getPhoneCount()) {
            ((PhoneProxy) mProxyPhones[phoneId]).unregisterForAllDataDisconnected(h);
        }
    }

    public boolean isDataDisconnected(long subId) {
        int phoneId = SubscriptionController.getInstance().getPhoneId(subId);

        if (phoneId >= 0 && phoneId < TelephonyManager.getDefault().getPhoneCount()) {
            Phone activePhone = ((PhoneProxy) mProxyPhones[phoneId]).getActivePhone();
            return ((PhoneBase) activePhone).mDcTracker.isDisconnected();
        } else {
            return false;
        }
    }

    private void logd(String string) {
        Rlog.d(LOG_TAG, string);
    }

    /**
     * Set phone RAT family.
     *
     * @param rats an PhoneRatFamily array to indicate all phone's new RAT family.
     *        The length of PhoneRatFamily must equal to phone count.
     */
    public void setPhoneRat(PhoneRatFamily[] rats) {
        // check option
        if (SystemProperties.getBoolean("ro.mtk_disable_cap_switch", false) == true) {
            broadcastSetPhoneRatDone();
            logd("mtk_disable_cap_switch is true");
            return;
        }
        if (rats.length != mProxyPhones.length) {
            throw new RuntimeException("Length of input rats must equal to total phone count");
        }
        int airplaneMode = Settings.Global.getInt(
                mContext.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0);
        if (airplaneMode > 0) {
            throw new RuntimeException("airplane mode is on, fail to set RAT for phones");
        }
        // check radio available
        for (int i = 0; i < mProxyPhones.length; i++) {
            if (!mProxyPhones[i].isRadioAvailable()) {
                throw new RuntimeException("Phone" + i + " is not available");
            }
        }
        int switchStatus = Integer.valueOf(
                SystemProperties.get(PhoneConstants.CAPABILITY_SWITCH_PROP, "1"));
        // check parameter
        boolean bIsboth3G = false;
        for (int i = 0; i < rats.length; i++) {
            if ((rats[i].getRatFamily() & PhoneRatFamily.PHONE_RAT_FAMILY_3G)
                    == PhoneRatFamily.PHONE_RAT_FAMILY_3G) {
                if (rats[i].getPhoneId() == (switchStatus - 1)) {
                    broadcastSetPhoneRatDone();
                    logd("no change, skip setPhoneRat");
                    return;
                }
                if (bIsboth3G) {
                    logd("set more than one 3G phone, fail");
                    throw new RuntimeException("input parameter is incorrect");
                } else {
                    bIsboth3G = true;
                }
            }
        }
        if (bIsboth3G == false) {
            throw new RuntimeException("input parameter is incorrect - no 3g phone");
        }
        if (false == RadioCapabilitySwitchUtil.isNeedSwitchInOpPackage(mProxyPhones, rats)) {
            broadcastSetPhoneRatDone();
            logd("no change in op check, skip setPhoneRat");
            return;
        }
        synchronized (mSetPhoneRatFamilyStatus) {
            for (int i = 0; i < mProxyPhones.length; i++) {
                if (mSetPhoneRatFamilyStatus[i] != SET_PHONE_RAT_FAMILY_STATUS_IDLE) {
                    throw new RuntimeException("Phone" + i + " is still changing RAT family");
                }
            }
            for (int i = 0; i < rats.length; i++) {
                ((PhoneProxy) mProxyPhones[rats[i].getPhoneId()]).setPhoneRatFamily(
                        rats[i].getRatFamily(), mHandler.obtainMessage(
                        EVENT_SET_PHONE_RAT_FAMILY_RESPONSE));
                mSetPhoneRatFamilyStatus[rats[i].getPhoneId()]
                        = SET_PHONE_RAT_FAMILY_STATUS_CHANGING;
            }
        }
    }

    /**
     * Get phone RAT Family.
     *
     * @param phoneId which phone you want to get
     * @return phone RAT family for input phone ID
     */
    public int getPhoneRat(int phoneId) {
        if (phoneId >= mProxyPhones.length) {
            return PhoneRatFamily.PHONE_RAT_FAMILY_NONE;
        } else {
            return ((PhoneProxy) mProxyPhones[phoneId]).getPhoneRatFamily();
        }
    }

    private void broadcastSetPhoneRatDone() {
        ArrayList<PhoneRatFamily> phoneRatList = new ArrayList<PhoneRatFamily>();

        synchronized (mSetPhoneRatFamilyStatus) {
            for (int i = 0; i < mProxyPhones.length; i++) {
                if (mSetPhoneRatFamilyStatus[i] == SET_PHONE_RAT_FAMILY_STATUS_DONE) {
                    PhoneRatFamily phoneRat = new PhoneRatFamily(
                            i, ((PhoneProxy) mProxyPhones[i]).getPhoneRatFamily());
                    phoneRatList.add(phoneRat);
                    mSetPhoneRatFamilyStatus[i] = SET_PHONE_RAT_FAMILY_STATUS_IDLE;
                }
            }
        }
        Intent intent = new Intent(TelephonyIntents.ACTION_SET_PHONE_RAT_FAMILY_DONE);
        intent.putParcelableArrayListExtra(TelephonyIntents.EXTRA_PHONES_RAT_FAMILY,
                phoneRatList);
        mContext.sendBroadcast(intent);
        RadioCapabilitySwitchUtil.updateIccid(mProxyPhones);

        logd("broadcastSetPhoneRatDone");
    }

    private Handler mHandler = new Handler() {

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case EVENT_SET_PHONE_RAT_FAMILY_RESPONSE:
                    logd("handleMessage (EVENT_SET_PHONE_RAT_FAMILY_RESPONSE)");
                    AsyncResult ar = ((AsyncResult) msg.obj);
                    if (ar.exception != null) {
                        if (ar.result != null) {
                            int phoneId = (int) (ar.result);
                            logd("exception in phone" + phoneId);
                            synchronized (mSetPhoneRatFamilyStatus) {
                                mSetPhoneRatFamilyStatus[phoneId] = SET_PHONE_RAT_FAMILY_STATUS_IDLE;
                            }
                            logd("capability," + mProxyPhones[phoneId].getPhoneRatFamily());
                            if (ar.exception instanceof CommandException) {
                                CommandException.Error err =
                                        ((CommandException) (ar.exception)).getCommandError();
                                if (err == CommandException.Error.RADIO_NOT_AVAILABLE) {
                                    if (((mProxyPhones[phoneId].getPhoneRatFamily() &
                                            PhoneRatFamily.PHONE_RAT_FAMILY_3G)
                                            == PhoneRatFamily.PHONE_RAT_FAMILY_3G)) {
                                        logd("don't send fail because handle cap in 2->3G case");
                                        return;
                                    }
                                }
                            }
                            Intent intent = new Intent(
                                    TelephonyIntents.ACTION_SET_PHONE_RAT_FAMILY_FAILED);
                            intent.putExtra(TelephonyIntents.EXTRA_PHONES_ID, phoneId);
                            mContext.sendBroadcast(intent);
                        }
                    } else {
                        int phoneId = (int) (ar.result);
                        logd("set phone rat response from phone" + phoneId);
                        synchronized (mSetPhoneRatFamilyStatus) {
                            mSetPhoneRatFamilyStatus[phoneId] = SET_PHONE_RAT_FAMILY_STATUS_IDLE;
                        }
                    }
                    break;

                case EVENT_PHONE_RAT_FAMILY_CHANGED_NOTIFY:
                    PhoneRatFamily notifyResult = (PhoneRatFamily) ((AsyncResult) msg.obj).result;
                    int phoneId = notifyResult.getPhoneId();

                    logd("handleMessage (EVENT_PHONE_RAT_FAMILY_CHANGED_NOTIFY)");

                    synchronized (mSetPhoneRatFamilyStatus) {
                        if (mSetPhoneRatFamilyStatus[phoneId] == SET_PHONE_RAT_FAMILY_STATUS_IDLE) {
                            logd("Phone" + phoneId + " cancels the operation");
                        }
                        mSetPhoneRatFamilyStatus[phoneId] = SET_PHONE_RAT_FAMILY_STATUS_DONE;
                        for (int i = 0; i < mProxyPhones.length; i++) {
                            if (mSetPhoneRatFamilyStatus[i]
                                    == SET_PHONE_RAT_FAMILY_STATUS_CHANGING) {
                                logd("Phone" + i
                                        + " is waiting EVENT_PHONE_RAT_FAMILY_CHANGED_NOTIFY");
                                return;
                            }
                        }
                    }
                    // all phones get notify, send intent
                    broadcastSetPhoneRatDone();
                    break;

                default:
                    break;
            }
        }
    };
}
