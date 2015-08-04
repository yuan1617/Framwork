/*
 * Copyright (C) 2008 The Android Open Source Project
 * Copyright (c) 2011-2013, The Linux Foundation. All rights reserved.
 * Not a Contribution.
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

import android.os.ServiceManager;
import android.os.RemoteException;
import android.telephony.Rlog;

import com.android.internal.telephony.IccPhoneBookInterfaceManagerProxy;
import com.android.internal.telephony.IIccPhoneBook;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.uicc.AdnRecord;

import com.mediatek.internal.telephony.uicc.AlphaTag;
import com.mediatek.internal.telephony.uicc.UsimGroup;
import com.mediatek.internal.telephony.uicc.UsimPBMemInfo;

import java.lang.ArrayIndexOutOfBoundsException;
import java.lang.NullPointerException;
import java.util.List;

public class UiccPhoneBookController extends IIccPhoneBook.Stub {
    private static final String TAG = "UiccPhoneBookController";
    private Phone[] mPhone;

    /* only one UiccPhoneBookController exists */
    public UiccPhoneBookController(Phone[] phone) {
        if (ServiceManager.getService("simphonebook") == null) {
               ServiceManager.addService("simphonebook", this);
        }
        mPhone = phone;
    }

    public boolean
    updateAdnRecordsInEfBySearch (int efid, String oldTag, String oldPhoneNumber,
            String newTag, String newPhoneNumber, String pin2) throws android.os.RemoteException {
        return updateAdnRecordsInEfBySearchForSubscriber(getDefaultSubscription(), efid, oldTag,
                oldPhoneNumber, newTag, newPhoneNumber, pin2);
    }

    public boolean
    updateAdnRecordsInEfBySearchForSubscriber(long subId, int efid, String oldTag,
            String oldPhoneNumber, String newTag, String newPhoneNumber,
            String pin2) throws android.os.RemoteException {
        IccPhoneBookInterfaceManagerProxy iccPbkIntMgrProxy =
                             getIccPhoneBookInterfaceManagerProxy(subId);
        if (iccPbkIntMgrProxy != null) {
            return iccPbkIntMgrProxy.updateAdnRecordsInEfBySearch(efid, oldTag,
                    oldPhoneNumber, newTag, newPhoneNumber, pin2);
        } else {
            Rlog.e(TAG,"updateAdnRecordsInEfBySearch iccPbkIntMgrProxy is" +
                      " null for Subscription:"+subId);
            return false;
        }
    }

    public int
    updateAdnRecordsInEfBySearchWithError(long subId, int efid,
            String oldTag, String oldPhoneNumber,
            String newTag, String newPhoneNumber, String pin2) throws android.os.RemoteException {
        IccPhoneBookInterfaceManagerProxy iccPbkIntMgrProxy =
                             getIccPhoneBookInterfaceManagerProxy(subId);
        if (iccPbkIntMgrProxy != null) {
            return iccPbkIntMgrProxy.updateAdnRecordsInEfBySearchWithError(efid,
                    oldTag, oldPhoneNumber, newTag, newPhoneNumber, pin2);
        } else {
            Rlog.e(TAG, "updateAdnRecordsInEfBySearch iccPbkIntMgrProxy is" +
                      " null for Subscription:" + subId);
            return 0;
        }
    }

    public int
    updateUsimPBRecordsInEfBySearchWithError(long subId, int efid,
            String oldTag, String oldPhoneNumber, String oldAnr, String oldGrpIds, String[] oldEmails,
            String newTag, String newPhoneNumber, String newAnr, String newGrpIds, String[] newEmails) throws android.os.RemoteException {
        IccPhoneBookInterfaceManagerProxy iccPbkIntMgrProxy =
                             getIccPhoneBookInterfaceManagerProxy(subId);
        if (iccPbkIntMgrProxy != null) {
            return iccPbkIntMgrProxy.updateUsimPBRecordsInEfBySearchWithError(
                    efid, oldTag, oldPhoneNumber, oldAnr, oldGrpIds, oldEmails,
                    newTag, newPhoneNumber, newAnr, newGrpIds, newEmails);
        } else {
            Rlog.e(TAG, "updateAdnRecordsInEfBySearch iccPbkIntMgrProxy is" +
                      " null for Subscription:" + subId);
            return 0;
        }
    }

    public boolean
    updateAdnRecordsInEfByIndex(int efid, String newTag,
            String newPhoneNumber, int index, String pin2) throws android.os.RemoteException {
        return updateAdnRecordsInEfByIndexForSubscriber(getDefaultSubscription(), efid, newTag,
                newPhoneNumber, index, pin2);
    }

    public boolean
    updateAdnRecordsInEfByIndexForSubscriber(long subId, int efid, String newTag,
            String newPhoneNumber, int index, String pin2) throws android.os.RemoteException {
        IccPhoneBookInterfaceManagerProxy iccPbkIntMgrProxy =
                             getIccPhoneBookInterfaceManagerProxy(subId);
        if (iccPbkIntMgrProxy != null) {
            return iccPbkIntMgrProxy.updateAdnRecordsInEfByIndex(efid, newTag,
                    newPhoneNumber, index, pin2);
        } else {
            Rlog.e(TAG,"updateAdnRecordsInEfByIndex iccPbkIntMgrProxy is" +
                      " null for Subscription:"+subId);
            return false;
        }
    }

    public int
    updateAdnRecordsInEfByIndexWithError(long subId, int efid, String newTag,
            String newPhoneNumber, int index, String pin2) throws android.os.RemoteException {
        IccPhoneBookInterfaceManagerProxy iccPbkIntMgrProxy =
                             getIccPhoneBookInterfaceManagerProxy(subId);
        if (iccPbkIntMgrProxy != null) {
            return iccPbkIntMgrProxy.updateAdnRecordsInEfByIndexWithError(efid,
                    newTag, newPhoneNumber, index, pin2);
        } else {
            Rlog.e(TAG, "updateAdnRecordsInEfBySearch iccPbkIntMgrProxy is" +
                      " null for Subscription:" + subId);
            return 0;
        }
    }

    public int
    updateUsimPBRecordsInEfByIndexWithError(long subId, int efid, String newTag,
            String newPhoneNumber, String newAnr,  String newGrpIds, String[] newEmails, int index) throws android.os.RemoteException {
        IccPhoneBookInterfaceManagerProxy iccPbkIntMgrProxy =
                             getIccPhoneBookInterfaceManagerProxy(subId);
        if (iccPbkIntMgrProxy != null) {
            return iccPbkIntMgrProxy.updateUsimPBRecordsInEfByIndexWithError(efid,
                    newTag, newPhoneNumber, newAnr, newGrpIds, newEmails, index);
        } else {
            Rlog.e(TAG, "updateAdnRecordsInEfBySearch iccPbkIntMgrProxy is" +
                      " null for Subscription:" + subId);
            return 0;
        }
    }

    public int updateUsimPBRecordsByIndexWithError(long subId, int efid, AdnRecord record, int index) throws android.os.RemoteException {
        IccPhoneBookInterfaceManagerProxy iccPbkIntMgrProxy =
                             getIccPhoneBookInterfaceManagerProxy(subId);
        if (iccPbkIntMgrProxy != null) {
            return iccPbkIntMgrProxy.updateUsimPBRecordsByIndexWithError(efid,
                    record, index);
        } else {
            Rlog.e(TAG, "updateAdnRecordsInEfBySearch iccPbkIntMgrProxy is" +
                      " null for Subscription:" + subId);
            return 0;
        }
    }

    public int updateUsimPBRecordsBySearchWithError(long subId, int efid, AdnRecord oldAdn, AdnRecord newAdn) throws android.os.RemoteException {
        IccPhoneBookInterfaceManagerProxy iccPbkIntMgrProxy =
                             getIccPhoneBookInterfaceManagerProxy(subId);
        if (iccPbkIntMgrProxy != null) {
            return iccPbkIntMgrProxy.updateUsimPBRecordsBySearchWithError(efid,
                    oldAdn, newAdn);
        } else {
            Rlog.e(TAG, "updateAdnRecordsInEfBySearch iccPbkIntMgrProxy is" +
                      " null for Subscription:" + subId);
            return 0;
        }
    }

    public int[] getAdnRecordsSize(int efid) throws android.os.RemoteException {
        return getAdnRecordsSizeForSubscriber(getDefaultSubscription(), efid);
    }

    public int[]
    getAdnRecordsSizeForSubscriber(long subId, int efid) throws android.os.RemoteException {
        IccPhoneBookInterfaceManagerProxy iccPbkIntMgrProxy =
                             getIccPhoneBookInterfaceManagerProxy(subId);
        if (iccPbkIntMgrProxy != null) {
            return iccPbkIntMgrProxy.getAdnRecordsSize(efid);
        } else {
            Rlog.e(TAG,"getAdnRecordsSize iccPbkIntMgrProxy is" +
                      " null for Subscription:"+subId);
            return null;
        }
    }

    public List<AdnRecord> getAdnRecordsInEf(int efid) throws android.os.RemoteException {
        return getAdnRecordsInEfForSubscriber(getDefaultSubscription(), efid);
    }

    public List<AdnRecord> getAdnRecordsInEfForSubscriber(long subId, int efid)
           throws android.os.RemoteException {
        IccPhoneBookInterfaceManagerProxy iccPbkIntMgrProxy =
                             getIccPhoneBookInterfaceManagerProxy(subId);
        if (iccPbkIntMgrProxy != null) {
            return iccPbkIntMgrProxy.getAdnRecordsInEf(efid);
        } else {
            Rlog.e(TAG,"getAdnRecordsInEf iccPbkIntMgrProxy is" +
                      "null for Subscription:"+subId);
            return null;
        }
    }

    public boolean isPhbReady(long subId) throws android.os.RemoteException {
        IccPhoneBookInterfaceManagerProxy iccPbkIntMgrProxy =
                             getIccPhoneBookInterfaceManagerProxy(subId);
        if (iccPbkIntMgrProxy != null) {
            return iccPbkIntMgrProxy.isPhbReady();
        } else {
            Rlog.e(TAG, "updateAdnRecordsInEfBySearch iccPbkIntMgrProxy is" +
                      " null for Subscription:" + subId);
            return false;
        }
    }

    public List<UsimGroup> getUsimGroups(long subId) throws android.os.RemoteException {
        IccPhoneBookInterfaceManagerProxy iccPbkIntMgrProxy =
                             getIccPhoneBookInterfaceManagerProxy(subId);
        if (iccPbkIntMgrProxy != null) {
            return iccPbkIntMgrProxy.getUsimGroups();
        } else {
            Rlog.e(TAG, "updateAdnRecordsInEfBySearch iccPbkIntMgrProxy is" +
                      " null for Subscription:" + subId);
            return null;
        }
    }

    public String getUsimGroupById(long subId, int nGasId) throws android.os.RemoteException {
        IccPhoneBookInterfaceManagerProxy iccPbkIntMgrProxy =
                             getIccPhoneBookInterfaceManagerProxy(subId);
        if (iccPbkIntMgrProxy != null) {
            return iccPbkIntMgrProxy.getUsimGroupById(nGasId);
        } else {
            Rlog.e(TAG, "updateAdnRecordsInEfBySearch iccPbkIntMgrProxy is" +
                      " null for Subscription:" + subId);
            return null;
        }
    }

    public boolean removeUsimGroupById(long subId, int nGasId) throws android.os.RemoteException {
        IccPhoneBookInterfaceManagerProxy iccPbkIntMgrProxy =
                             getIccPhoneBookInterfaceManagerProxy(subId);
        if (iccPbkIntMgrProxy != null) {
            return iccPbkIntMgrProxy.removeUsimGroupById(nGasId);
        } else {
            Rlog.e(TAG, "updateAdnRecordsInEfBySearch iccPbkIntMgrProxy is" +
                      " null for Subscription:" + subId);
            return false;
        }
    }

    public int insertUsimGroup(long subId, String grpName) throws android.os.RemoteException {
        IccPhoneBookInterfaceManagerProxy iccPbkIntMgrProxy =
                             getIccPhoneBookInterfaceManagerProxy(subId);
        if (iccPbkIntMgrProxy != null) {
            return iccPbkIntMgrProxy.insertUsimGroup(grpName);
        } else {
            Rlog.e(TAG, "updateAdnRecordsInEfBySearch iccPbkIntMgrProxy is" +
                      " null for Subscription:" + subId);
            return -1;
        }
    }

    public int updateUsimGroup(long subId, int nGasId, String grpName) throws android.os.RemoteException {
        IccPhoneBookInterfaceManagerProxy iccPbkIntMgrProxy =
                             getIccPhoneBookInterfaceManagerProxy(subId);
        if (iccPbkIntMgrProxy != null) {
            return iccPbkIntMgrProxy.updateUsimGroup(nGasId, grpName);
        } else {
            Rlog.e(TAG, "updateAdnRecordsInEfBySearch iccPbkIntMgrProxy is" +
                      " null for Subscription:" + subId);
            return -1;
        }
    }

    public boolean addContactToGroup(long subId, int adnIndex, int grpIndex) throws android.os.RemoteException {
        IccPhoneBookInterfaceManagerProxy iccPbkIntMgrProxy =
                             getIccPhoneBookInterfaceManagerProxy(subId);
        if (iccPbkIntMgrProxy != null) {
            return iccPbkIntMgrProxy.addContactToGroup(adnIndex, grpIndex);
        } else {
            Rlog.e(TAG, "updateAdnRecordsInEfBySearch iccPbkIntMgrProxy is" +
                      " null for Subscription:" + subId);
            return false;
        }
    }

    public boolean removeContactFromGroup(long subId, int adnIndex, int grpIndex) throws android.os.RemoteException {
        IccPhoneBookInterfaceManagerProxy iccPbkIntMgrProxy =
                             getIccPhoneBookInterfaceManagerProxy(subId);
        if (iccPbkIntMgrProxy != null) {
            return iccPbkIntMgrProxy.removeContactFromGroup(adnIndex, grpIndex);
        } else {
            Rlog.e(TAG, "updateAdnRecordsInEfBySearch iccPbkIntMgrProxy is" +
                      " null for Subscription:" + subId);
            return false;
        }
    }

    public boolean updateContactToGroups(long subId, int adnIndex, int[] grpIdList) throws android.os.RemoteException {
        IccPhoneBookInterfaceManagerProxy iccPbkIntMgrProxy =
                             getIccPhoneBookInterfaceManagerProxy(subId);
        if (iccPbkIntMgrProxy != null) {
            return iccPbkIntMgrProxy.updateContactToGroups(adnIndex, grpIdList);
        } else {
            Rlog.e(TAG, "updateAdnRecordsInEfBySearch iccPbkIntMgrProxy is" +
                      " null for Subscription:" + subId);
            return false;
        }
    }

    public boolean moveContactFromGroupsToGroups(long subId, int adnIndex, int[] fromGrpIdList, int[] toGrpIdList) throws android.os.RemoteException {
        IccPhoneBookInterfaceManagerProxy iccPbkIntMgrProxy =
                             getIccPhoneBookInterfaceManagerProxy(subId);
        if (iccPbkIntMgrProxy != null) {
            return iccPbkIntMgrProxy.moveContactFromGroupsToGroups(adnIndex,
                    fromGrpIdList, toGrpIdList);
        } else {
            Rlog.e(TAG, "updateAdnRecordsInEfBySearch iccPbkIntMgrProxy is" +
                      " null for Subscription:" + subId);
            return false;
        }
    }

    public int hasExistGroup(long subId, String grpName) throws android.os.RemoteException {
        IccPhoneBookInterfaceManagerProxy iccPbkIntMgrProxy =
                             getIccPhoneBookInterfaceManagerProxy(subId);
        if (iccPbkIntMgrProxy != null) {
            return iccPbkIntMgrProxy.hasExistGroup(grpName);
        } else {
            Rlog.e(TAG, "updateAdnRecordsInEfBySearch iccPbkIntMgrProxy is" +
                      " null for Subscription:" + subId);
            return -1;
        }
    }

    public int getUsimGrpMaxNameLen(long subId) throws android.os.RemoteException {
        IccPhoneBookInterfaceManagerProxy iccPbkIntMgrProxy =
                             getIccPhoneBookInterfaceManagerProxy(subId);
        if (iccPbkIntMgrProxy != null) {
            return iccPbkIntMgrProxy.getUsimGrpMaxNameLen();
        } else {
            Rlog.e(TAG, "updateAdnRecordsInEfBySearch iccPbkIntMgrProxy is" +
                      " null for Subscription:" + subId);
            return -1;
        }
    }

    public int getUsimGrpMaxCount(long subId) throws android.os.RemoteException {
        IccPhoneBookInterfaceManagerProxy iccPbkIntMgrProxy =
                             getIccPhoneBookInterfaceManagerProxy(subId);
        if (iccPbkIntMgrProxy != null) {
            return iccPbkIntMgrProxy.getUsimGrpMaxCount();
        } else {
            Rlog.e(TAG, "updateAdnRecordsInEfBySearch iccPbkIntMgrProxy is" +
                      " null for Subscription:" + subId);
            return -1;
        }
    }

    public List<AlphaTag> getUsimAasList(long subId) throws android.os.RemoteException {
        IccPhoneBookInterfaceManagerProxy iccPbkIntMgrProxy =
                             getIccPhoneBookInterfaceManagerProxy(subId);
        if (iccPbkIntMgrProxy != null) {
            return iccPbkIntMgrProxy.getUsimAasList();
        } else {
            Rlog.e(TAG, "updateAdnRecordsInEfBySearch iccPbkIntMgrProxy is" +
                      " null for Subscription:" + subId);
            return null;
        }
    }

    public String getUsimAasById(long subId, int index) throws android.os.RemoteException {
        IccPhoneBookInterfaceManagerProxy iccPbkIntMgrProxy =
                             getIccPhoneBookInterfaceManagerProxy(subId);
        if (iccPbkIntMgrProxy != null) {
            return iccPbkIntMgrProxy.getUsimAasById(index);
        } else {
            Rlog.e(TAG, "updateAdnRecordsInEfBySearch iccPbkIntMgrProxy is" +
                      " null for Subscription:" + subId);
            return null;
        }
    }

    public int insertUsimAas(long subId, String aasName) throws android.os.RemoteException {
        IccPhoneBookInterfaceManagerProxy iccPbkIntMgrProxy =
                             getIccPhoneBookInterfaceManagerProxy(subId);
        if (iccPbkIntMgrProxy != null) {
            return iccPbkIntMgrProxy.insertUsimAas(aasName);
        } else {
            Rlog.e(TAG, "updateAdnRecordsInEfBySearch iccPbkIntMgrProxy is" +
                      " null for Subscription:" + subId);
            return 0;
        }
    }

    public int getAnrCount(long subId) throws android.os.RemoteException {
        IccPhoneBookInterfaceManagerProxy iccPbkIntMgrProxy =
                             getIccPhoneBookInterfaceManagerProxy(subId);
        if (iccPbkIntMgrProxy != null) {
            return iccPbkIntMgrProxy.getAnrCount();
        } else {
            Rlog.e(TAG, "updateAdnRecordsInEfBySearch iccPbkIntMgrProxy is" +
                      " null for Subscription:" + subId);
            return 0;
        }
    }

    public int getEmailCount(long subId) throws android.os.RemoteException {
        IccPhoneBookInterfaceManagerProxy iccPbkIntMgrProxy =
                             getIccPhoneBookInterfaceManagerProxy(subId);
        if (iccPbkIntMgrProxy != null) {
            return iccPbkIntMgrProxy.getEmailCount();
        } else {
            Rlog.e(TAG, "updateAdnRecordsInEfBySearch iccPbkIntMgrProxy is" +
                      " null for Subscription:" + subId);
            return 0;
        }
    }

    public int getUsimAasMaxCount(long subId) throws android.os.RemoteException {
        IccPhoneBookInterfaceManagerProxy iccPbkIntMgrProxy =
                             getIccPhoneBookInterfaceManagerProxy(subId);
        if (iccPbkIntMgrProxy != null) {
            return iccPbkIntMgrProxy.getUsimAasMaxCount();
        } else {
            Rlog.e(TAG, "updateAdnRecordsInEfBySearch iccPbkIntMgrProxy is" +
                      " null for Subscription:" + subId);
            return 0;
        }
    }

    public int getUsimAasMaxNameLen(long subId) throws android.os.RemoteException {
        IccPhoneBookInterfaceManagerProxy iccPbkIntMgrProxy =
                             getIccPhoneBookInterfaceManagerProxy(subId);
        if (iccPbkIntMgrProxy != null) {
            return iccPbkIntMgrProxy.getUsimAasMaxNameLen();
        } else {
            Rlog.e(TAG, "updateAdnRecordsInEfBySearch iccPbkIntMgrProxy is" +
                      " null for Subscription:" + subId);
            return 0;
        }
    }

    public boolean updateUsimAas(long subId, int index, int pbrIndex, String aasName) throws android.os.RemoteException {
        IccPhoneBookInterfaceManagerProxy iccPbkIntMgrProxy =
                             getIccPhoneBookInterfaceManagerProxy(subId);
        if (iccPbkIntMgrProxy != null) {
            return iccPbkIntMgrProxy.updateUsimAas(index, pbrIndex, aasName);
        } else {
            Rlog.e(TAG, "updateAdnRecordsInEfBySearch iccPbkIntMgrProxy is" +
                      " null for Subscription:" + subId);
            return false;
        }
    }

    public boolean removeUsimAasById(long subId, int index, int pbrIndex) throws android.os.RemoteException {
        IccPhoneBookInterfaceManagerProxy iccPbkIntMgrProxy =
                             getIccPhoneBookInterfaceManagerProxy(subId);
        if (iccPbkIntMgrProxy != null) {
            return iccPbkIntMgrProxy.removeUsimAasById(index, pbrIndex);
        } else {
            Rlog.e(TAG, "updateAdnRecordsInEfBySearch iccPbkIntMgrProxy is" +
                      " null for Subscription:" + subId);
            return false;
        }
    }

    public boolean hasSne(long subId) throws android.os.RemoteException {
        IccPhoneBookInterfaceManagerProxy iccPbkIntMgrProxy =
                             getIccPhoneBookInterfaceManagerProxy(subId);
        if (iccPbkIntMgrProxy != null) {
            return iccPbkIntMgrProxy.hasSne();
        } else {
            Rlog.e(TAG, "updateAdnRecordsInEfBySearch iccPbkIntMgrProxy is" +
                      " null for Subscription:" + subId);
            return false;
        }
    }

    public int getSneRecordLen(long subId) throws android.os.RemoteException {
        IccPhoneBookInterfaceManagerProxy iccPbkIntMgrProxy =
                             getIccPhoneBookInterfaceManagerProxy(subId);
        if (iccPbkIntMgrProxy != null) {
            return iccPbkIntMgrProxy.getSneRecordLen();
        } else {
            Rlog.e(TAG, "updateAdnRecordsInEfBySearch iccPbkIntMgrProxy is" +
                      " null for Subscription:" + subId);
            return 0;
        }
    }

    public boolean isAdnAccessible(long subId) throws android.os.RemoteException {
        IccPhoneBookInterfaceManagerProxy iccPbkIntMgrProxy =
                             getIccPhoneBookInterfaceManagerProxy(subId);
        if (iccPbkIntMgrProxy != null) {
            return iccPbkIntMgrProxy.isAdnAccessible();
        } else {
            Rlog.e(TAG, "updateAdnRecordsInEfBySearch iccPbkIntMgrProxy is" +
                      " null for Subscription:" + subId);
            return true;
        }
    }

    public UsimPBMemInfo[] getPhonebookMemStorageExt(long subId) throws android.os.RemoteException {
        IccPhoneBookInterfaceManagerProxy iccPbkIntMgrProxy =
                             getIccPhoneBookInterfaceManagerProxy(subId);
        if (iccPbkIntMgrProxy != null) {
            return iccPbkIntMgrProxy.getPhonebookMemStorageExt();
        } else {
            Rlog.e(TAG, "updateAdnRecordsInEfBySearch iccPbkIntMgrProxy is" +
                      " null for Subscription:" + subId);
            return null;
        }
    }

    /**
     * get phone book interface manager proxy object based on subscription.
     **/
    private IccPhoneBookInterfaceManagerProxy
            getIccPhoneBookInterfaceManagerProxy(long subId) {

        long phoneId = SubscriptionController.getInstance().getPhoneId(subId);
        try {
            return ((PhoneProxy)mPhone[(int)phoneId]).getIccPhoneBookInterfaceManagerProxy();
        } catch (NullPointerException e) {
            Rlog.e(TAG, "Exception is :"+e.toString()+" For subscription :"+subId );
            e.printStackTrace(); //To print stack trace
            return null;
        } catch (ArrayIndexOutOfBoundsException e) {
            Rlog.e(TAG, "Exception is :"+e.toString()+" For subscription :"+subId );
            e.printStackTrace();
            return null;
        }
    }

    private long getDefaultSubscription() {
        return PhoneFactory.getDefaultSubscription();
    }
}
