/*
* Copyright (C) 2014 MediaTek Inc.
* Modification based on code covered by the mentioned copyright
* and/or permission notice(s).
*/
/*
* Copyright (C) 2011-2014 MediaTek Inc.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*      http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package com.android.internal.telephony;

import static android.Manifest.permission.READ_PHONE_STATE;
import android.app.ActivityManagerNative;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.telephony.Rlog;
import android.telephony.SubscriptionManager;
import android.telephony.SubInfoRecord;
import android.telephony.TelephonyManager;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneProxy;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.uicc.IccConstants;
import com.android.internal.telephony.uicc.IccFileHandler;
import com.android.internal.telephony.uicc.IccRecords;
import com.android.internal.telephony.uicc.IccUtils;
import com.android.internal.telephony.uicc.SpnOverride;
import com.android.internal.telephony.uicc.UiccController;

import com.mediatek.internal.telephony.DefaultSmsSimSettings;
import com.mediatek.internal.telephony.DefaultVoiceCallSubSettings;

import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.List;

/**
 *@hide
 */
public class SubInfoRecordUpdater extends Handler {
    private static final String LOG_TAG = "SUB";
    private static final int PROJECT_SIM_NUM = TelephonyManager.getDefault().getPhoneCount();
    private static final int EVENT_OFFSET = 8;
    private static final int EVENT_QUERY_ICCID_DONE = 1;
    private static final int EVENT_RADIO_AVAILABLE = 2;
    private static final int EVENT_ICC_RECORD_EVENTS = 3;
    private static final int EVENT_ICC_CHANGED = 4;
    private static final int EVENT_RADIO_UNAVAILABLE = 5;
    private static final String ICCID_STRING_FOR_NO_SIM = "N/A";
    /**
     *  int[] sInsertSimState maintains all slots' SIM inserted status currently,
     *  it may contain 4 kinds of values:
     *    SIM_NOT_INSERT : no SIM inserted in slot i now
     *    SIM_CHANGED    : a valid SIM insert in slot i and is different SIM from last time
     *                     it will later become SIM_NEW or SIM_REPOSITION during update procedure
     *    SIM_NOT_CHANGE : a valid SIM insert in slot i and is the same SIM as last time
     *    SIM_NEW        : a valid SIM insert in slot i and is a new SIM
     *    SIM_REPOSITION : a valid SIM insert in slot i and is inserted in different slot last time
     *    positive integer #: index to distinguish SIM cards with the same IccId
     */
    public static final int SIM_NOT_CHANGE = 0;
    public static final int SIM_CHANGED    = -1;
    public static final int SIM_NEW        = -2;
    public static final int SIM_REPOSITION = -3;
    public static final int SIM_NOT_INSERT = -99;

    public static final int STATUS_NO_SIM_INSERTED = 0x00;
    public static final int STATUS_SIM1_INSERTED = 0x01;
    public static final int STATUS_SIM2_INSERTED = 0x02;
    public static final int STATUS_SIM3_INSERTED = 0x04;
    public static final int STATUS_SIM4_INSERTED = 0x08;

    private static Phone[] sPhone;
    private static Context sContext = null;
    private CommandsInterface[] mCis = null;
    private static IccFileHandler[] sFh = new IccFileHandler[PROJECT_SIM_NUM];
    private static String sIccId[] = new String[PROJECT_SIM_NUM];
    private static int[] sInsertSimState = new int[PROJECT_SIM_NUM];
    private static TelephonyManager sTelephonyMgr = null;
    private static int[] sIsUpdateAvailable = new int[PROJECT_SIM_NUM];
    // To prevent repeatedly update flow every time receiver SIM_STATE_CHANGE
    private static boolean sNeedUpdate = true;
    private static Intent sUpdateIntent = null;
    protected AtomicReferenceArray<IccRecords> mIccRecords
            = new AtomicReferenceArray<IccRecords>(PROJECT_SIM_NUM);
    private static final int sReadICCID_retry_time = 1000;
    private int mReadIccIdCount = 0;
    protected final Object mLock = new Object();

    static String[] PROPERTY_ICCID_SIM = {
        "ril.iccid.sim1",
        "ril.iccid.sim2",
        "ril.iccid.sim3",
        "ril.iccid.sim4",
    };

    private static final String COMMON_SLOT_PROPERTY = "ro.mtk_sim_hot_swap_common_slot";

    public SubInfoRecordUpdater(Context context, Phone[] phoneProxy, CommandsInterface[] ci) {
        logd("Constructor invoked");

        sContext = context;
        sPhone = phoneProxy;
        mCis = ci;

        /* Ray Read property */
        for (int i = 0; i < PROJECT_SIM_NUM; i++) {
            sIsUpdateAvailable[i] = -1;
            sIccId[i] = SystemProperties.get(PROPERTY_ICCID_SIM[i], "");
            if (sIccId[i].length() == 3)
            {
                logd("No SIM insert :" + i);
            }
            logd("sIccId[" + i + "]:" + sIccId[i]);
        }

        if (isAllIccIdQueryDone())
        {
            new Thread() {
                public void run() {
                    updateSimInfoByIccId();
                }
            } .start();
        }
        /* Ray Read property */

        IntentFilter intentFilter = new IntentFilter(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        intentFilter.addAction("android.intent.action.ACTION_SHUTDOWN_IPO");
        intentFilter.addAction(Intent.ACTION_LOCALE_CHANGED);
        sContext.registerReceiver(sReceiver, intentFilter);

        for (int i = 0; i < mCis.length; i++) {
            Integer index = new Integer(i);
            mCis[i].registerForNotAvailable(this, EVENT_RADIO_UNAVAILABLE, index);
            mCis[i].registerForAvailable(this, EVENT_RADIO_AVAILABLE, index);
        }
    }

    private static int encodeEventId(int event, int slotId) {
        return event << (slotId * EVENT_OFFSET);
    }

    private final BroadcastReceiver sReceiver = new  BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            logd("[Receiver]+");
            String action = intent.getAction();
            int slotId;
            logd("Action: " + action);
            if (action.equals(TelephonyIntents.ACTION_SIM_STATE_CHANGED)) {
                String simStatus = intent.getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE);
                slotId = intent.getIntExtra(PhoneConstants.SLOT_KEY,
                        SubscriptionManager.INVALID_SLOT_ID);
                logd("slotId: " + slotId + " simStatus: " + simStatus);
                if (slotId == SubscriptionManager.INVALID_SLOT_ID) {
                    return;
                }
                if (IccCardConstants.INTENT_VALUE_ICC_READY.equals(simStatus)
                        || IccCardConstants.INTENT_VALUE_ICC_LOCKED.equals(simStatus)) {
                    if (sIccId[slotId] != null && sIccId[slotId].equals(ICCID_STRING_FOR_NO_SIM)) {
                        logd("SIM" + (slotId + 1) + " hot plug in");
                        sIccId[slotId] = null;
                        sNeedUpdate = true;
                    }
                    mReadIccIdCount = 10;
                    queryIccId(slotId);
                } else if (IccCardConstants.INTENT_VALUE_ICC_LOADED.equals(simStatus)) {
                    mReadIccIdCount = 10;
                    queryIccId(slotId);
                    if (sTelephonyMgr == null) {
                        sTelephonyMgr = TelephonyManager.from(sContext);
                    }

                    long subId = SubscriptionController.getInstance().getSubId(slotId)[0];

                    if (SubscriptionManager.isValidSubId(subId)) {
                        String msisdn = TelephonyManager.getDefault().getLine1NumberForSubscriber(subId);
                        ContentResolver contentResolver = sContext.getContentResolver();

                        if (msisdn != null) {
                            SubscriptionController.getInstance().setDisplayNumber(msisdn, subId,
                                    false);
                        }
                        updateSubName(subId);
                    } else {
                        logd("[Receiver] Invalid subId, could not update ContentResolver");
                    }
                } else if (IccCardConstants.INTENT_VALUE_ICC_ABSENT.equals(simStatus)) {
                    if (sIccId[slotId] != null && !sIccId[slotId].equals(ICCID_STRING_FOR_NO_SIM)) {
                        logd("SIM" + (slotId + 1) + " hot plug out");
                        sNeedUpdate = true;
                    }
                    sFh[slotId] = null;
                    sIccId[slotId] = ICCID_STRING_FOR_NO_SIM;

                    if (isAllIccIdQueryDone() && sNeedUpdate) {
                        new Thread() {
                            public void run() {
                                updateSimInfoByIccId();
                            }
                        } .start();
                    }
                }
            } else if (action.equals("android.intent.action.ACTION_SHUTDOWN_IPO")) {
                for (int i = 0; i < PROJECT_SIM_NUM; i++) {
                    clearIccId(i);
                    SubscriptionController.getInstance().clearSubInfo(i);
                }
                if (sUpdateIntent != null) {
                    sContext.removeStickyBroadcast(sUpdateIntent);
                }
            } else if (action.equals(Intent.ACTION_LOCALE_CHANGED)) {
                long[] subIdList = SubscriptionManager.getActiveSubIdList();
                for (long subId : subIdList) {
                    updateSubName(subId);
                }
            }
            logd("[Receiver]-");
        }
    };

    private boolean isAllIccIdQueryDone() {
        for (int i = 0; i < PROJECT_SIM_NUM; i++) {
            if (sIccId[i] == null || sIccId[i].equals("")) {
                logd("Wait for SIM" + (i + 1) + " IccId");
                return false;
            }
        }
        logd("All IccIds query complete");

        return true;
    }

    public static void setDisplayNameForNewSub(String newSubName, int subId, int newNameSource) {
        SubInfoRecord subInfo = SubscriptionManager.getSubInfoForSubscriber(subId);
        if (subInfo != null) {
            // overwrite SIM display name if it is not assigned by user
            int oldNameSource = subInfo.nameSource;
            String oldSubName = subInfo.displayName;
            logd("[setDisplayNameForNewSub] mSubInfoIdx = " + subInfo.subId + ", oldSimName = "
                    + oldSubName + ", oldNameSource = " + oldNameSource + ", newSubName = "
                    + newSubName + ", newNameSource = " + newNameSource);
            if (oldSubName == null ||
                (oldNameSource == SubscriptionManager.NAME_SOURCE_DEFAULT_SOURCE && newSubName != null) ||
                (oldNameSource == SubscriptionManager.NAME_SOURCE_SIM_SOURCE && newSubName != null
                        && !newSubName.equals(oldSubName))) {
                SubscriptionManager.setDisplayName(newSubName,
                        subInfo.subId, newNameSource);
            }
        } else {
            logd("SUB" + (subId + 1) + " SubInfo not created yet");
        }
    }

    @Override
    public void handleMessage(Message msg) {
        AsyncResult ar = (AsyncResult)msg.obj;
        int msgNum = msg.what;
        int slotId;
        Integer index;
        for (slotId = PhoneConstants.SUB1; slotId <= PhoneConstants.SUB3; slotId++) {
            int pivot = 1 << (slotId * EVENT_OFFSET);
            if (msgNum >= pivot) {
                continue;
            } else {
                break;
            }
        }
        slotId--;
        int event = msgNum >> (slotId * EVENT_OFFSET);
        switch (event) {
            case EVENT_QUERY_ICCID_DONE:
                logd("handleMessage : <EVENT_QUERY_ICCID_DONE> SIM" + (slotId + 1));
                if (ar.exception == null) {
                    if (ar.result != null) {
                        byte[] data = (byte[])ar.result;
                        sIccId[slotId] = IccUtils.parseIccIdToString(data, 0, data.length);
                    } else {
                        logd("Null ar");
                        sIccId[slotId] = ICCID_STRING_FOR_NO_SIM;
                    }
                } else {
                    if (ar.exception instanceof CommandException &&
                        ((CommandException) (ar.exception)).getCommandError() ==
                                CommandException.Error.RADIO_NOT_AVAILABLE) {
                        sIccId[slotId] = "";
                    } else {
                        sIccId[slotId] = ICCID_STRING_FOR_NO_SIM;
                    }
                    logd("Query IccId fail: " + ar.exception);
                }
                logd("sIccId[" + slotId + "] = " + sIccId[slotId]);
                if (isAllIccIdQueryDone() && sNeedUpdate) {
                    new Thread() {
                        public void run() {
                            updateSimInfoByIccId();
                        }
                    } .start();
                }
                break;
            case EVENT_RADIO_UNAVAILABLE:
                index = getCiIndex(msg);
                logd("handleMessage : <EVENT_RADIO_UNAVAILABLE> SIM" + (index + 1));
                sIsUpdateAvailable[index] = 0;
                break;
            case EVENT_RADIO_AVAILABLE:
                index = getCiIndex(msg);
                logd("handleMessage : <EVENT_RADIO_AVAILABLE> SIM" + (index + 1));
                sIsUpdateAvailable[index]++;
                if (checkIsAvailable()) {
                    for (int i = 0; i < PROJECT_SIM_NUM; i++) {
                        clearIccId(i);
                    }
                    SubscriptionController.getInstance().clearSubInfo();
                mReadIccIdCount = 0;
                if (!readIccIdProperty()) {
                    postDelayed(mReadIccIdPropertyRunnable, sReadICCID_retry_time);
                    }
                }
                break;
            case EVENT_ICC_RECORD_EVENTS:
                logd("handleMessage : <EVENT_ICC_RECORD_EVENTS> SIM" + (slotId + 1));
                processIccRecordEvents((Integer) ar.result, slotId);
                break;
            case EVENT_ICC_CHANGED:
                logd("handleMessage : <EVENT_ICC_CHANGED>");
                List<SubInfoRecord> subInfos = SubscriptionManager.getActiveSubInfoList();
                int nSubCount = (subInfos == null) ? 0 : subInfos.size();
                logd("nSubCount = " + nSubCount);
                for (int i = 0; i < nSubCount; i++) {
                    registerForSimRecordEvents(subInfos.get(i).slotId);
                }
                break;
            default:
                logd("Unknown msg:" + msg.what);
        }
    }

    private void queryIccId(int slotId) {
        logd("queryIccId: slotid=" + slotId);
        if (sFh[slotId] == null) {
            logd("Getting IccFileHandler");
            sFh[slotId] = ((PhoneProxy)sPhone[slotId]).getIccFileHandler();
        }
        if (sFh[slotId] != null) {
            String iccId = sIccId[slotId];
            if (iccId == null || iccId.equals("")) {
                logd("Querying IccId");
                sFh[slotId].loadEFTransparent(IccConstants.EF_ICCID,
                        obtainMessage(encodeEventId(EVENT_QUERY_ICCID_DONE, slotId)));
            } else {
                logd("NOT Querying IccId its already set sIccid[" + slotId + "]=" + iccId);
            }
        } else {
            logd("sFh[" + slotId + "] is null, ignore");
        }
    }

    synchronized public void updateSimInfoByIccId() {
        synchronized (mLock) {
            logd("[updateSimInfoByIccId]+ Start");
            sNeedUpdate = false;
    
            SubscriptionManager.clearSubInfo();
    
            long mDefaultSub = SubscriptionManager.getDefaultSubId();
            logd("[updateSimInfoByIccId] mDefaultSub = " + mDefaultSub);
    
            for (int i = 0; i < PROJECT_SIM_NUM; i++) {
                sInsertSimState[i] = SIM_NOT_CHANGE;
            }
    
            int insertedSimCount = PROJECT_SIM_NUM;
            for (int i = 0; i < PROJECT_SIM_NUM; i++) {
                if (ICCID_STRING_FOR_NO_SIM.equals(sIccId[i])) {
                    insertedSimCount--;
                    sInsertSimState[i] = SIM_NOT_INSERT;
                }
            }
            logd("insertedSimCount = " + insertedSimCount);
    
            int index = 0;
            for (int i = 0; i < PROJECT_SIM_NUM; i++) {
                if (sInsertSimState[i] == SIM_NOT_INSERT) {
                    continue;
                }
                index = 2;
                for (int j = i + 1; j < PROJECT_SIM_NUM; j++) {
                    if (sInsertSimState[j] == SIM_NOT_CHANGE && sIccId[i].equals(sIccId[j])) {
                        sInsertSimState[i] = 1;
                        sInsertSimState[j] = index;
                        index++;
                    }
                }
            }
    
            ContentResolver contentResolver = sContext.getContentResolver();
            String[] oldIccId = new String[PROJECT_SIM_NUM];
            for (int i = 0; i < PROJECT_SIM_NUM; i++) {
                oldIccId[i] = null;
                List<SubInfoRecord> oldSubInfo =
                            SubscriptionController.getInstance()
                            .getSubInfoUsingSlotIdWithCheck(i, false);
                if (oldSubInfo != null) {
                    oldIccId[i] = oldSubInfo.get(0).iccId;
                    logd("oldSubId = " + oldSubInfo.get(0).subId);
                    if (sInsertSimState[i] == SIM_NOT_CHANGE && !sIccId[i].equals(oldIccId[i])) {
                        sInsertSimState[i] = SIM_CHANGED;
                    }
                    if (sInsertSimState[i] != SIM_NOT_CHANGE) {
                        ContentValues value = new ContentValues(1);
                        value.put(SubscriptionManager.SIM_ID, SubscriptionManager.INVALID_SLOT_ID);
                        contentResolver.update(SubscriptionManager.CONTENT_URI, value,
                                SubscriptionManager._ID + "="
                                + Long.toString(oldSubInfo.get(0).subId), null);
                    }
                } else {
                    if (sInsertSimState[i] == SIM_NOT_CHANGE) {
                        // no SIM inserted last time, but there is one SIM inserted now
                        sInsertSimState[i] = SIM_CHANGED;
                    }
                    oldIccId[i] = ICCID_STRING_FOR_NO_SIM;
                    logd("No SIM in slot " + i + " last time");
                }
            }
    
            for (int i = 0; i < PROJECT_SIM_NUM; i++) {
                logd("oldIccId[" + i + "] = " + oldIccId[i] + ", sIccId[" + i + "] = " + sIccId[i]);
            }
    
            //check if the inserted SIM is new SIM
            int nNewCardCount = 0;
            int nNewSimStatus = 0;
            for (int i = 0; i < PROJECT_SIM_NUM; i++) {
                if (sInsertSimState[i] == SIM_NOT_INSERT) {
                    logd("No SIM inserted in slot " + i + " this time");
                } else {
                    if (sInsertSimState[i] > 0) {
                        //some special SIMs may have the same IccIds, add suffix to distinguish them
                        //FIXME: addSubInfoRecord can return an error.
                        SubscriptionManager.addSubInfoRecord(sIccId[i]
                                + Integer.toString(sInsertSimState[i]), i);
                        logd("SUB" + (i + 1) + " has invalid IccId");
                    } else /*if (sInsertSimState[i] != SIM_NOT_INSERT)*/ {
                        SubscriptionManager.addSubInfoRecord(sIccId[i], i);
                    }
                    if (isNewSim(sIccId[i], oldIccId)) {
                        nNewCardCount++;
                        switch (i) {
                            case PhoneConstants.SUB1:
                                nNewSimStatus |= STATUS_SIM1_INSERTED;
                                break;
                            case PhoneConstants.SUB2:
                                nNewSimStatus |= STATUS_SIM2_INSERTED;
                                break;
                            case PhoneConstants.SUB3:
                                nNewSimStatus |= STATUS_SIM3_INSERTED;
                                break;
                            //case PhoneConstants.SUB3:
                            //    nNewSimStatus |= STATUS_SIM4_INSERTED;
                            //    break;
                                default :
                                    break;
                        }
    
                        sInsertSimState[i] = SIM_NEW;
                    }
                }
            }
    
            for (int i = 0; i < PROJECT_SIM_NUM; i++) {
                if (sInsertSimState[i] == SIM_CHANGED) {
                    sInsertSimState[i] = SIM_REPOSITION;
                }
                logd("sInsertSimState[" + i + "] = " + sInsertSimState[i]);
            }
    
            List<SubInfoRecord> subInfos = SubscriptionManager.getActiveSubInfoList();
            int nSubCount = (subInfos == null) ? 0 : subInfos.size();
            logd("nSubCount = " + nSubCount);
                for (int i = 0; i < nSubCount; i++) {
                SubInfoRecord temp = subInfos.get(i);
                    String msisdn = TelephonyManager.getDefault()
                            .getLine1NumberForSubscriber(temp.subId);
    
                if (msisdn != null) {
                        SubscriptionController.getInstance()
                            .setDisplayNumber(msisdn, temp.subId, false);
                }
    
                if (temp.subId == mDefaultSub) {
                    SubscriptionManager.setDefaultSubId(mDefaultSub);
                    logd("setDefaultSub back, defaultsubId = " + mDefaultSub);
                }
    
                registerForSimRecordEvents(temp.slotId);
                UiccController.getInstance().registerForIccChanged(this, EVENT_ICC_CHANGED, null);
            }
    
            setAllDefaultSub(subInfos);
    
            // true if any slot has no SIM this time, but has SIM last time
            boolean hasSimRemoved = false;
                for (int i = 0; i < PROJECT_SIM_NUM; i++) {
                if (sIccId[i] != null && sIccId[i].equals(ICCID_STRING_FOR_NO_SIM)
                        && !oldIccId[i].equals(ICCID_STRING_FOR_NO_SIM)) {
                    hasSimRemoved = true;
                    break;
                }
            }
    
            if (nNewCardCount == 0) {
                int i;
                if (hasSimRemoved) {
                        // no new SIM, at least one SIM is removed, check if any SIM is repositioned
                        for (i = 0; i < PROJECT_SIM_NUM; i++) {
                        if (sInsertSimState[i] == SIM_REPOSITION) {
                            logd("No new SIM detected and SIM repositioned");
                            setUpdatedData(SubscriptionManager.EXTRA_VALUE_REPOSITION_SIM,
                                    nSubCount, nNewSimStatus);
                            break;
                        }
                    }
                    if (i == PROJECT_SIM_NUM) {
                        // no new SIM, no SIM is repositioned => at least one SIM is removed
                        logd("No new SIM detected and SIM removed");
                        setUpdatedData(SubscriptionManager.EXTRA_VALUE_REMOVE_SIM,
                                nSubCount, nNewSimStatus);
                    }
                } else {
                    // no SIM is removed, no new SIM, just check if any SIM is repositioned
                        for (i = 0; i < PROJECT_SIM_NUM; i++) {
                        if (sInsertSimState[i] == SIM_REPOSITION) {
                            logd("No new SIM detected and SIM repositioned");
                            setUpdatedData(SubscriptionManager.EXTRA_VALUE_REPOSITION_SIM,
                                    nSubCount, nNewSimStatus);
                            break;
                        }
                    }
                    if (i == PROJECT_SIM_NUM) {
                        // all status remain unchanged
                        logd("[updateSimInfoByIccId] All SIM inserted into the same slot");
                        setUpdatedData(SubscriptionManager.EXTRA_VALUE_NOCHANGE,
                                nSubCount, nNewSimStatus);
                    }
                }
            } else {
                logd("New SIM detected");
                setUpdatedData(SubscriptionManager.EXTRA_VALUE_NEW_SIM, nSubCount, nNewSimStatus);
            }
    
            logd("[updateSimInfoByIccId]- SimInfo update complete");
        }
    }

    private static void setUpdatedData(int detectedType, int subCount, int newSimStatus) {

        Intent intent = new Intent(TelephonyIntents.ACTION_SUBINFO_RECORD_UPDATED);

        logd("[setUpdatedData]+ ");

        if (detectedType == SubscriptionManager.EXTRA_VALUE_NEW_SIM ) {
            intent.putExtra(SubscriptionManager.INTENT_KEY_DETECT_STATUS,
                    SubscriptionManager.EXTRA_VALUE_NEW_SIM);
            intent.putExtra(SubscriptionManager.INTENT_KEY_SIM_COUNT, subCount);
            intent.putExtra(SubscriptionManager.INTENT_KEY_NEW_SIM_SLOT, newSimStatus);
        } else if (detectedType == SubscriptionManager.EXTRA_VALUE_REPOSITION_SIM) {
            intent.putExtra(SubscriptionManager.INTENT_KEY_DETECT_STATUS,
                    SubscriptionManager.EXTRA_VALUE_REPOSITION_SIM);
            intent.putExtra(SubscriptionManager.INTENT_KEY_SIM_COUNT, subCount);
        } else if (detectedType == SubscriptionManager.EXTRA_VALUE_REMOVE_SIM) {
            intent.putExtra(SubscriptionManager.INTENT_KEY_DETECT_STATUS,
                    SubscriptionManager.EXTRA_VALUE_REMOVE_SIM);
            intent.putExtra(SubscriptionManager.INTENT_KEY_SIM_COUNT, subCount);
        } else if (detectedType == SubscriptionManager.EXTRA_VALUE_NOCHANGE) {
            intent.putExtra(SubscriptionManager.INTENT_KEY_DETECT_STATUS,
                    SubscriptionManager.EXTRA_VALUE_NOCHANGE);
        }

        logd("broadcast intent ACTION_SUBINFO_RECORD_UPDATED : [" + detectedType + ", "
                + subCount + ", " + newSimStatus+ "]");
        sUpdateIntent = intent;
        sContext.sendStickyBroadcast(sUpdateIntent);
        logd("[setUpdatedData]- ");
    }

    private static boolean isNewSim(String iccId, String[] oldIccId) {
        boolean newSim = true;
        for(int i = 0; i < PROJECT_SIM_NUM; i++) {
            if(iccId.equals(oldIccId[i])) {
                newSim = false;
                break;
            }
        }
        logd("newSim = " + newSim);

        return newSim;
    }

    public void dispose() {
        logd("[dispose]");
        sContext.unregisterReceiver(sReceiver);
    }

    private static void logd(String message) {
        Rlog.d(LOG_TAG, "[SubInfoRecordUpdater]" + message);
    }

    private static void setAllDefaultSub(List<SubInfoRecord> subInfos) {
        logd("[setAllDefaultSub]+ ");
        DefaultSmsSimSettings.setSmsTalkDefaultSim(subInfos);
        logd("[setSmsTalkDefaultSim]- ");
        DefaultVoiceCallSubSettings.setVoiceCallDefaultSub(subInfos);
        logd("[setVoiceCallDefaultSub]- ");
    }

    private void clearIccId(int slotId) {
        synchronized (mLock) {
            logd("[clearIccId], slotId = " + slotId);
            sFh[slotId] = null;
            sIccId[slotId] = null;
        }
    }

    private void registerForSimRecordEvents(int slotId) {
        logd("[registerForSimRecordEvents], slotId = " + slotId);
        int family = UiccController.APP_FAM_3GPP;
        if (sPhone[slotId].getPhoneType() == PhoneConstants.PHONE_TYPE_CDMA) {
            family = UiccController.APP_FAM_3GPP2;
        }
        IccRecords newIccRecords = UiccController.getInstance().getIccRecords(slotId, family);
        IccRecords r = mIccRecords.get(slotId);
        logd("registerForSimRecordEvents: family = " + family
                + ", newIccRecords = " + newIccRecords + ", r = " + r);

        if (r != newIccRecords) {
            if (r != null) {
                logd("Removing stale icc objects.");
                r.unregisterForRecordsEvents(this);
                mIccRecords.set(slotId, null);
            }
            if (newIccRecords != null) {
                logd("New records found");
                mIccRecords.set(slotId, newIccRecords);
                newIccRecords.registerForRecordsEvents(
                        this, encodeEventId(EVENT_ICC_RECORD_EVENTS, slotId), null);
            }
        }
    }

    private void processIccRecordEvents(int eventCode, int slotId) {
        switch (eventCode) {
            case IccRecords.EVENT_MSISDN:
                logd("[processIccRecordEvents], IccRecords.EVENT_MSISDN, slotId = " + slotId);
                //Get MSISDN and setDisplayNumber to Subscription Manager.
                long subId = SubscriptionController.getInstance().getSubId(slotId)[0];
                String msisdn = TelephonyManager.getDefault().getLine1NumberForSubscriber(subId);
                logd("[processIccRecordEvents], msisdn = " + msisdn);
                if (msisdn != null) {
                    SubscriptionController.getInstance().setDisplayNumber(msisdn, subId, false);
                }
                break;
            default:
                break;
        }
    }

    private void updateSubName(long subId) {
        SubInfoRecord subInfo =
                SubscriptionManager.getSubInfoForSubscriber(subId);

        if (subInfo != null
                && subInfo.nameSource != SubscriptionManager.NAME_SOURCE_USER_INPUT) {
            SpnOverride mSpnOverride = new SpnOverride();
            String nameToSet;
            String carrierName = TelephonyManager.getDefault().getSimOperator(subId);
            int slotId = SubscriptionManager.getSlotId(subId);
            logd("updateSubName, carrierName = " + carrierName + ", subId = " + subId);

            if (SubscriptionManager.isValidSlotId(slotId)) {
                if (mSpnOverride.containsCarrier(carrierName)) {
                    String operator = SystemProperties.get("ro.operator.optr", "OM");
                    if (operator.equals("OP01")) {
                        nameToSet = mSpnOverride.lookupOperatorName(subId, carrierName,
                            true, sContext);
                    } else {
                        if (TelephonyManager.getDefault().getSimCount() > 1) {
                        nameToSet = mSpnOverride.lookupOperatorName(subId, carrierName,
                            true, sContext) + " 0" + Integer.toString(slotId + 1);
                        } else {
                            nameToSet = mSpnOverride.lookupOperatorName(subId, carrierName,
                                true, sContext);
                        }
                    }
                    logd("SPN Found, name = " + nameToSet);
                    SubscriptionManager.setDisplayName(nameToSet, subId);
                } else {
                    logd("SPN Not found");
                }
            }
        }
    }

    private Runnable mReadIccIdPropertyRunnable = new Runnable() {
        public void run() {
            ++mReadIccIdCount;
            if (mReadIccIdCount <= 10) {
                if (!readIccIdProperty()) {
                    postDelayed(mReadIccIdPropertyRunnable, sReadICCID_retry_time);
                }
            }
        }
    };

    private boolean readIccIdProperty() {
        logd("readIccIdProperty +, retry_count = " + mReadIccIdCount);
        for (int i = 0; i < PROJECT_SIM_NUM; i++) {
            if (sIccId[i] == null) {
                sIccId[i] = SystemProperties.get(PROPERTY_ICCID_SIM[i], "");
                if (sIccId[i].length() == 3) {
                    logd("No SIM insert :" + i);
                }
                logd("sIccId[" + i + "]:" + sIccId[i]);
            }
        }

        if (isAllIccIdQueryDone()) {
            new Thread() {
                public void run() {
                    updateSimInfoByIccId();
                }
            } .start();
            return true;
        } else {
            return false;
        }
    }

    private Integer getCiIndex(Message msg) {
        AsyncResult ar;
        Integer index = new Integer(PhoneConstants.DEFAULT_CARD_INDEX);

        /*
         * The events can be come in two ways. By explicitly sending it using
         * sendMessage, in this case the user object passed is msg.obj and from
         * the CommandsInterface, in this case the user object is msg.obj.userObj
         */
        if (msg != null) {
            if (msg.obj != null && msg.obj instanceof Integer) {
                index = (Integer) msg.obj;
            } else if (msg.obj != null && msg.obj instanceof AsyncResult) {
                ar = (AsyncResult) msg.obj;
                if (ar.userObj != null && ar.userObj instanceof Integer) {
                    index = (Integer) ar.userObj;
                }
            }
        }
        return index;
    }

    private boolean checkIsAvailable() {
        boolean result = true;
        for (int i = 0; i < PROJECT_SIM_NUM; i++) {
            if (sIsUpdateAvailable[i] <= 0) {
                logd("sIsUpdateAvailable[" + i + "] = " + sIsUpdateAvailable[i]);
                result = false;
                break;
            }
        }
        logd("checkIsAvailable result = " + result);
        return result;
    }
}

