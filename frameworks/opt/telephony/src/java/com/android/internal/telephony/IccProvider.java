/* Copyright Statement:
 *
 * This software/firmware and related documentation ("MediaTek Software") are
 * protected under relevant copyright laws. The information contained herein
 * is confidential and proprietary to MediaTek Inc. and/or its licensors.
 * Without the prior written permission of MediaTek inc. and/or its licensors,
 * any reproduction, modification, use or disclosure of MediaTek Software,
 * and information contained herein, in whole or in part, shall be strictly prohibited.
 */
/* MediaTek Inc. (C) 2010. All rights reserved.
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

/*
 * Copyright (C) 2006 The Android Open Source Project
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

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MergeCursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.telephony.Rlog;

import java.util.List;

import com.android.internal.telephony.IIccPhoneBook;
import com.android.internal.telephony.uicc.AdnRecord;
import com.android.internal.telephony.uicc.IccConstants;

/**
 * {@hide}
 */
public class IccProvider extends ContentProvider {
    private static final String TAG = "IccProvider";
    private static final boolean DBG = true;

    private static final String[] ADDRESS_BOOK_COLUMN_NAMES = new String[] {
            "index",
            "name",
            "number",
            "emails",
            "additionalNumber",
            "groupIds",
            "_id",
            "aas",
            "sne",
    };
    private static final int ADDRESS_SUPPORT_AAS = 8;
    private static final int ADDRESS_SUPPORT_SNE = 9;
    private static final String[] UPB_GRP_COLUMN_NAMES = new String[] {
            "index",
            "name"
    };

    protected static final int ADN = 1;
    protected static final int ADN_SUB = 2;
    protected static final int FDN = 3;
    protected static final int FDN_SUB = 4;
    protected static final int SDN = 5;
    protected static final int SDN_SUB = 6;
    protected static final int UPB = 7;
    protected static final int UPB_SUB = 8;
    protected static final int ADN_ALL = 9;

    protected static final String STR_TAG = "tag";
    protected static final String STR_NUMBER = "number";
    protected static final String STR_EMAILS = "emails";
    protected static final String STR_PIN2 = "pin2";
    protected static final String STR_ANR = "anr";
    protected static final String STR_INDEX = "index";
    private static final UriMatcher URL_MATCHER =
            new UriMatcher(UriMatcher.NO_MATCH);

    static {
        URL_MATCHER.addURI("icc", "adn", ADN);
        URL_MATCHER.addURI("icc", "adn/subId/#", ADN_SUB);
        URL_MATCHER.addURI("icc", "fdn", FDN);
        URL_MATCHER.addURI("icc", "fdn/subId/#", FDN_SUB);
        URL_MATCHER.addURI("icc", "sdn", SDN);
        URL_MATCHER.addURI("icc", "sdn/subId/#", SDN_SUB);
        URL_MATCHER.addURI("icc", "pbr", UPB);
        URL_MATCHER.addURI("icc", "pbr/subId/#", UPB_SUB);
    }
    public static final int ERROR_ICC_PROVIDER_NO_ERROR = 1;
    public static final int ERROR_ICC_PROVIDER_UNKNOWN = 0;
    public static final int ERROR_ICC_PROVIDER_NUMBER_TOO_LONG = -1;
    public static final int ERROR_ICC_PROVIDER_TEXT_TOO_LONG = -2;
    public static final int ERROR_ICC_PROVIDER_STORAGE_FULL = -3;
    public static final int ERROR_ICC_PROVIDER_NOT_READY = -4;
    public static final int ERROR_ICC_PROVIDER_PASSWORD_ERROR = -5;
    public static final int ERROR_ICC_PROVIDER_ANR_TOO_LONG = -6;
    public static final int ERROR_ICC_PROVIDER_GENERIC_FAILURE = -10;
    public static final int ERROR_ICC_PROVIDER_ADN_LIST_NOT_EXIST = -11;
    public static final int ERROR_ICC_PROVIDER_EMAIL_FULL = -12;
    public static final int ERROR_ICC_PROVIDER_EMAIL_TOOLONG = -13;
    public static final int ERROR_ICC_PROVIDER_ANR_SAVE_FAILURE = -14;
    public static final int ERROR_ICC_PROVIDER_WRONG_ADN_FORMAT = -15;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri url, String[] projection, String selection,
            String[] selectionArgs, String sort) {

        if (DBG) {
            log("query " + url);
        }

        switch (URL_MATCHER.match(url)) {
            case ADN:
                return loadFromEf(IccConstants.EF_ADN, SubscriptionManager.getDefaultSubId());

            case ADN_SUB:
                return loadFromEf(IccConstants.EF_ADN, getRequestSubId(url));

            case FDN:
                return loadFromEf(IccConstants.EF_FDN, SubscriptionManager.getDefaultSubId());

            case FDN_SUB:
                return loadFromEf(IccConstants.EF_FDN, getRequestSubId(url));

            case SDN:
                return loadFromEf(IccConstants.EF_SDN, SubscriptionManager.getDefaultSubId());

            case SDN_SUB:
                return loadFromEf(IccConstants.EF_SDN, getRequestSubId(url));

            case UPB:
                return loadFromEf(IccConstants.EF_PBR, SubscriptionManager.getDefaultSubId());

            case UPB_SUB:
                return loadFromEf(IccConstants.EF_PBR, getRequestSubId(url));

            case ADN_ALL:
                return loadAllSimContacts(IccConstants.EF_ADN);

            default:
                throw new IllegalArgumentException("Unknown URL " + url);
        }
    }

    private Cursor loadAllSimContacts(int efType) {
        long[] subIdList = SubscriptionManager.getActiveSubIdList();
        Cursor [] result = new Cursor[subIdList.length];

        int i = 0;
        for (long subId : subIdList) {
            result[i++] = loadFromEf(efType, subId);
            Rlog.i(TAG, "loadAllSimContacts: subId=" + subId);
        }

        return new MergeCursor(result);
    }

    @Override
    public String getType(Uri url) {
        switch (URL_MATCHER.match(url)) {
            case ADN:
            case ADN_SUB:
            case FDN:
            case FDN_SUB:
            case SDN:
            case SDN_SUB:
            case UPB:
            case UPB_SUB:
            case ADN_ALL:
                return "vnd.android.cursor.dir/sim-contact";

            default:
                throw new IllegalArgumentException("Unknown URL " + url);
        }
    }

    @Override
    public Uri insert(Uri url, ContentValues initialValues) {
        Uri resultUri;
        int efType;
        String pin2 = null;
        long subId;

        if (DBG) {
            log("insert " + url);
        }

        int match = URL_MATCHER.match(url);
        switch (match) {
            case ADN:
                efType = IccConstants.EF_ADN;
                subId = SubscriptionManager.getDefaultSubId();
                break;

            case ADN_SUB:
                efType = IccConstants.EF_ADN;
                subId = getRequestSubId(url);
                break;

            case FDN:
                efType = IccConstants.EF_FDN;
                subId = SubscriptionManager.getDefaultSubId();
                pin2 = initialValues.getAsString("pin2");
                break;

            case FDN_SUB:
                efType = IccConstants.EF_FDN;
                subId = getRequestSubId(url);
                pin2 = initialValues.getAsString("pin2");
                break;

            case UPB:
                efType = IccConstants.EF_PBR;
                subId = SubscriptionManager.getDefaultSubId();
                break;

            case UPB_SUB:
                efType = IccConstants.EF_PBR;
                subId = getRequestSubId(url);
                break;

            default:
                throw new UnsupportedOperationException(
                        "Cannot insert into URL: " + url);
        }

        String tag = initialValues.getAsString("tag");
        String number = initialValues.getAsString("number");
        int result = 0;
        if (UPB == match || UPB_SUB == match) {
            String strGas = initialValues.getAsString("gas");
            String strAnr = initialValues.getAsString("anr");
            String strEmail = initialValues.getAsString("emails");
            if (ADDRESS_BOOK_COLUMN_NAMES.length >= ADDRESS_SUPPORT_AAS) {
                Integer aasIndex = initialValues.getAsInteger("aas");
                if (number == null) {
                    number = "";
                }
                if (tag == null) {
                    tag = "";
                }
                AdnRecord record = new AdnRecord(efType, 0, tag, number);
                record.setAnr(strAnr);
                if (initialValues.containsKey("anr2")) {
                    String strAnr2 = initialValues.getAsString("anr2");
                    log("insert anr2: " + strAnr2);
                    record.setAnr(strAnr2, 1);
                }
                if (initialValues.containsKey("anr3")) {
                    String strAnr3 = initialValues.getAsString("anr3");
                    log("insert anr3: " + strAnr3);
                    record.setAnr(strAnr3, 2);
                }
                record.setGrpIds(strGas);
                String[] emails = null;
                if (strEmail != null && !strEmail.equals("")) {
                    emails = new String[1];
                    emails[0] = strEmail;
                }
                record.setEmails(emails);
                if (aasIndex != null) {
                    record.setAasIndex(aasIndex);
                }
                if (ADDRESS_BOOK_COLUMN_NAMES.length >= ADDRESS_SUPPORT_SNE) {
                    String sne = initialValues.getAsString("sne");
                    record.setSne(sne);
                }

                log("updateUsimPBRecordsBySearchWithError ");
                result = updateUsimPBRecordsBySearchWithError(efType, new AdnRecord("", "", ""),
                        record, subId);
            } else {
                log("addUsimRecordToEf ");
                result = addUsimRecordToEf(efType, tag, number, strAnr, strEmail, strGas, subId);
            }
        } else {
            // TODO(): Read email instead of sending null.
            result = addIccRecordToEf(efType, tag, number, null, pin2, subId);
        }

        StringBuilder buf = new StringBuilder("content://icc/");

        if (result <= ERROR_ICC_PROVIDER_UNKNOWN) {
            buf.append("error/");
            buf.append(result);
        } else {
            switch (match) {
                case ADN:
                    buf.append("adn/");
                    break;

                case ADN_SUB:
                    buf.append("adn/subId/");
                    break;

                case FDN:
                    buf.append("fdn/");
                    break;

                case FDN_SUB:
                    buf.append("fdn/subId/");
                    break;

                case UPB:
                    buf.append("pbr/");
                    break;

                case UPB_SUB:
                    buf.append("pbr/subId/");
                    break;
            }

            // TODO: we need to find out the rowId for the newly added record
            buf.append(result);
        }

        resultUri = Uri.parse(buf.toString());

        log(resultUri.toString());

        getContext().getContentResolver().notifyChange(url, null);
        /*
        // notify interested parties that an insertion happened
        getContext().getContentResolver().notifyInsert(
                resultUri, rowID, null);
        */

        return resultUri;
    }

    private String normalizeValue(String inVal) {
        int len = inVal.length();
        String retVal = inVal;

        if (inVal.charAt(0) == '\'' && inVal.charAt(len - 1) == '\'') {
            retVal = inVal.substring(1, len - 1);
        }

        return retVal;
    }

    @Override
    public int delete(Uri url, String where, String[] whereArgs) {
        int efType;
        long subId;

        if (DBG) {
            log("delete " + url);
        }
        int match = URL_MATCHER.match(url);
        switch (match) {
            case ADN:
                efType = IccConstants.EF_ADN;
                subId = SubscriptionManager.getDefaultSubId();
                break;

            case ADN_SUB:
                efType = IccConstants.EF_ADN;
                subId = getRequestSubId(url);
                break;

            case FDN:
                efType = IccConstants.EF_FDN;
                subId = SubscriptionManager.getDefaultSubId();
                break;

            case FDN_SUB:
                efType = IccConstants.EF_FDN;
                subId = getRequestSubId(url);
                break;

            case UPB:
                efType = IccConstants.EF_PBR;
                subId = SubscriptionManager.getDefaultSubId();
                break;

            case UPB_SUB:
                efType = IccConstants.EF_PBR;
                subId = getRequestSubId(url);
                break;

            default:
                throw new UnsupportedOperationException(
                        "Cannot insert into URL: " + url);
        }

        if (DBG) log("delete");

        // parse where clause
        String tag = "";
        String number = "";
        String[] emails = null;
        String pin2 = null;
        int nIndex = -1;

        String[] tokens = where.split("AND");
        int n = tokens.length;

        while (--n >= 0) {
            String param = tokens[n];
            if (DBG) {
                log("parsing '" + param + "'");
            }
            int index = param.indexOf('=');
            if (index == -1) {
                Rlog.e(TAG, "resolve: bad whereClause parameter: " + param);
                continue;
            }

            String key = param.substring(0, index).trim();
            String val = param.substring(index + 1).trim();
            log("parsing key is " + key + " index of = is " + index +
                    " val is " + val);

            /*
             * String[] pair = param.split("="); if (pair.length != 2) {
             * Rlog.e(TAG, "resolve: bad whereClause parameter: " + param);
             * continue; } String key = pair[0].trim(); String val =
             * pair[1].trim();
             */

            if (STR_INDEX.equals(key)) {
                nIndex = Integer.parseInt(val);
            } else if (STR_TAG.equals(key)) {
                tag = normalizeValue(val);
            } else if (STR_NUMBER.equals(key)) {
                number = normalizeValue(val);
            } else if (STR_EMAILS.equals(key)) {
                // TODO(): Email is null.
                emails = null;
            } else if (STR_PIN2.equals(key)) {
                pin2 = normalizeValue(val);
            }
        }

        int result = ERROR_ICC_PROVIDER_UNKNOWN;
        if (nIndex > 0) {
            log("delete index is " + nIndex);
            if (UPB == match || UPB_SUB == match) {
                log("deleteUsimRecordFromEfByIndex ");
                result = deleteUsimRecordFromEfByIndex(efType, nIndex, subId);
            } else {
                result = deleteIccRecordFromEfByIndex(efType, nIndex, pin2, subId);
            }
            return result;
        }

        if (efType == IccConstants.EF_FDN && TextUtils.isEmpty(pin2)) {
            return ERROR_ICC_PROVIDER_PASSWORD_ERROR;
        }

        if (tag.length() == 0 && number.length() == 0) {
            return ERROR_ICC_PROVIDER_UNKNOWN;
        }

        if (UPB == match || UPB_SUB == match) {
            if (ADDRESS_BOOK_COLUMN_NAMES.length >= ADDRESS_SUPPORT_AAS) {
                log("updateUsimPBRecordsBySearchWithError ");
                result = updateUsimPBRecordsBySearchWithError(efType,
                        new AdnRecord(tag, number, ""), new AdnRecord("", "", ""), subId);
            } else {
                result = deleteUsimRecordFromEf(efType, tag, number, emails, subId);
            }
        } else {
            result = deleteIccRecordFromEf(efType, tag, number, emails, pin2, subId);
        }

        getContext().getContentResolver().notifyChange(url, null);

        return result;
    }

    @Override
    public int update(Uri url, ContentValues values, String where, String[] whereArgs) {
        String pin2 = null;
        int efType;
        long subId;

        log("update " + url);

        int match = URL_MATCHER.match(url);
        switch (match) {
            case ADN:
                efType = IccConstants.EF_ADN;
                subId = SubscriptionManager.getDefaultSubId();
                break;

            case ADN_SUB:
                efType = IccConstants.EF_ADN;
                subId = getRequestSubId(url);
                break;

            case FDN:
                efType = IccConstants.EF_FDN;
                subId = SubscriptionManager.getDefaultSubId();
                pin2 = values.getAsString("pin2");
                break;

            case FDN_SUB:
                efType = IccConstants.EF_FDN;
                subId = getRequestSubId(url);
                pin2 = values.getAsString("pin2");
                break;

            case UPB:
                efType = IccConstants.EF_PBR;
                subId = SubscriptionManager.getDefaultSubId();
                break;

            case UPB_SUB:
                efType = IccConstants.EF_PBR;
                subId = getRequestSubId(url);
                break;

            default:
                throw new IllegalArgumentException("Unknown URL " + match);
        }

        String tag = values.getAsString("tag");
        String number = values.getAsString("number");

        String newTag = values.getAsString("newTag");
        String newNumber = values.getAsString("newNumber");
        Integer idInt = values.getAsInteger("index");
        int index = 0;
        if (idInt != null) {
            index = idInt.intValue();
        }
        log("update: index=" + index);
        int result = 0;
        if (UPB == match || UPB_SUB == match) {
            String strAnr = values.getAsString("newAnr");
            String strEmail = values.getAsString("newEmails");

            Integer aasIndex = values.getAsInteger("aas");
            String sne = values.getAsString("sne");
            if (newNumber == null) {
                newNumber = "";
            }
            if (newTag == null) {
                newTag = "";
            }
            AdnRecord record = new AdnRecord(efType, 0, newTag, newNumber);
            record.setAnr(strAnr);
            if (values.containsKey("newAnr2")) {
                String strAnr2 = values.getAsString("newAnr2");
                log("update newAnr2: " + strAnr2);
                record.setAnr(strAnr2, 1);
            }
            if (values.containsKey("newAnr3")) {
                String strAnr3 = values.getAsString("newAnr3");
                log("update newAnr3: " + strAnr3);
                record.setAnr(strAnr3, 2);
            }
            String[] emails = null;
            if (strEmail != null && !strEmail.equals("")) {
                emails = new String[1];
                emails[0] = strEmail;
            }
            record.setEmails(emails);
            if (aasIndex != null) {
                record.setAasIndex(aasIndex);
            }
            if (sne != null) {
                record.setSne(sne);
            }
            if (index > 0) {
                if (ADDRESS_BOOK_COLUMN_NAMES.length >= ADDRESS_SUPPORT_AAS) {
                    log("updateUsimPBRecordsByIndexWithError");
                    result = updateUsimPBRecordsByIndexWithError(efType, record, index, subId);
                } else {
                    result = updateUsimRecordInEfByIndex(efType, index, newTag, newNumber, strAnr,
                            strEmail, subId);
                }
            } else {
                if (ADDRESS_BOOK_COLUMN_NAMES.length >= ADDRESS_SUPPORT_AAS) {
                    log("updateUsimPBRecordsBySearchWithError");
                    result = updateUsimPBRecordsBySearchWithError(efType, new AdnRecord(tag,
                            number, ""), record, subId);
                } else {
                    result = updateUsimRecordInEf(efType, tag, number, newTag, newNumber, strAnr,
                            strEmail, subId);
                }

            }
        } else {
            if (index > 0) {
                result = updateIccRecordInEfByIndex(efType, index, newTag, newNumber, pin2, subId);
            } else {
                result = updateIccRecordInEf(efType, tag, number, newTag, newNumber, pin2, subId);
            }
        }

        getContext().getContentResolver().notifyChange(url, null);

        return result;

    }

    private MatrixCursor loadFromEf(int efType, long subId) {
        if (DBG) log("loadFromEf: efType=" + efType + ", subscription=" + subId);

        List<AdnRecord> adnRecords = null;
        try {
            IIccPhoneBook iccIpb = getIccPhbService();
            if (iccIpb != null) {
                adnRecords = iccIpb.getAdnRecordsInEfForSubscriber(subId, efType);
            }
        } catch (RemoteException ex) {
            log(ex.toString());
        } catch (SecurityException ex) {
            log(ex.toString());
        }

        if (adnRecords != null) {
            // Load the results
            final int size = adnRecords.size();
            final MatrixCursor cursor = new MatrixCursor(ADDRESS_BOOK_COLUMN_NAMES, size);
            if (DBG) {
                log("adnRecords.size=" + size);
            }
            for (int i = 0; i < size; i++) {
                loadRecord(adnRecords.get(i), cursor, i);
            }
            return cursor;
        } else {
            // No results to load
            Rlog.w(TAG, "Cannot load ADN records");
            return new MatrixCursor(ADDRESS_BOOK_COLUMN_NAMES);
        }
    }

    private int
    addIccRecordToEf(int efType, String name, String number, String[] emails,
            String pin2, long subId) {
        if (DBG) log("addIccRecordToEf: efType=" + efType + ", name=" + name +
                ", number=" + number + ", emails=" + emails + ", subscription=" + subId);

        int result = ERROR_ICC_PROVIDER_UNKNOWN;

        // TODO: do we need to call getAdnRecordsInEf() before calling
        // updateAdnRecordsInEfBySearch()? In any case, we will leave
        // the UI level logic to fill that prereq if necessary. But
        // hopefully, we can remove this requirement.

        try {
            IIccPhoneBook iccIpb = getIccPhbService();
            if (iccIpb != null) {
                result = iccIpb.updateAdnRecordsInEfBySearchWithError(subId, efType,
                        "", "", name, number, pin2);
            }
        } catch (RemoteException ex) {
            log(ex.toString());
        } catch (SecurityException ex) {
            log(ex.toString());
        }
        log("addIccRecordToEf: " + result);
        return result;
    }

    private int addUsimRecordToEf(int efType, String name, String number, String strAnr,
            String strEmail, String strGas, long subId) {

        if (DBG) {
            log("addUSIMRecordToEf: efType=" + efType + ", name=" + name +
                    ", number=" + number + ", anr =" + strAnr + ", emails=" + strEmail + ", subId="
                    + subId);
        }
        int result = ERROR_ICC_PROVIDER_UNKNOWN;

        // TODO: do we need to call getAdnRecordsInEf() before calling
        // updateAdnRecordsInEfBySearch()? In any case, we will leave
        // the UI level logic to fill that prereq if necessary. But
        // hopefully, we can remove this requirement.
        String[] emails = null;
        if (strEmail != null && !strEmail.equals("")) {
            emails = new String[1];
            emails[0] = strEmail;
        }

        try {
            IIccPhoneBook iccIpb = getIccPhbService();

            if (iccIpb != null) {
                result = iccIpb.updateUsimPBRecordsInEfBySearchWithError(subId, efType,
                        "", "", "", null, null, name, number, strAnr, null, emails);
            }
        } catch (RemoteException ex) {
            log(ex.toString());
        } catch (SecurityException ex) {
            log(ex.toString());
        }
        log("addUsimRecordToEf: " + result);
        return result;
    }

    private int
    updateIccRecordInEf(int efType, String oldName, String oldNumber,
            String newName, String newNumber, String pin2, long subId) {
        if (DBG) log("updateIccRecordInEf: efType=" + efType +
                ", oldname=" + oldName + ", oldnumber=" + oldNumber +
                ", newname=" + newName + ", newnumber=" + newNumber +
                ", subscription=" + subId);

        int result = ERROR_ICC_PROVIDER_UNKNOWN;

        try {
            IIccPhoneBook iccIpb = getIccPhbService();
            if (iccIpb != null) {
                result = iccIpb.updateAdnRecordsInEfBySearchWithError(subId, efType, oldName,
                        oldNumber, newName, newNumber, pin2);
            }
        } catch (RemoteException ex) {
            log(ex.toString());
        } catch (SecurityException ex) {
            log(ex.toString());
        }
        log("updateIccRecordInEf: " + result);
        return result;
    }

    private int updateIccRecordInEfByIndex(int efType, int nIndex, String newName,
            String newNumber, String pin2, long subId) {
        if (DBG) {
            log("updateIccRecordInEfByIndex: efType=" + efType + ", index=" + nIndex
                    + ", newname=" + newName + ", newnumber=" + newNumber);
        }
        int result = ERROR_ICC_PROVIDER_UNKNOWN;

        try {
            IIccPhoneBook iccIpb = getIccPhbService();

            if (iccIpb != null) {
                result = iccIpb.updateAdnRecordsInEfByIndexWithError(subId, efType,
                        newName, newNumber, nIndex, pin2);
            }
        } catch (RemoteException ex) {
            log(ex.toString());
        } catch (SecurityException ex) {
            log(ex.toString());
        }
        log("updateIccRecordInEfByIndex: " + result);
        return result;
    }

    private int updateUsimRecordInEf(int efType, String oldName, String oldNumber,
            String newName, String newNumber, String strAnr, String strEmail, long subId) {

        if (DBG) {
            log("updateUsimRecordInEf: efType=" + efType +
                    ", oldname=" + oldName + ", oldnumber=" + oldNumber +
                    ", newname=" + newName + ", newnumber=" + newNumber + ", anr =" + strAnr
                    + ", emails=" + strEmail);
        }
        int result = ERROR_ICC_PROVIDER_UNKNOWN;

        String[] emails = null;
        if (strEmail != null) {
            emails = new String[1];
            emails[0] = strEmail;
        }

        try {
            IIccPhoneBook iccIpb = getIccPhbService();

            if (iccIpb != null) {
                result = iccIpb.updateUsimPBRecordsInEfBySearchWithError(subId, efType,
                        oldName, oldNumber, "", null, null, newName, newNumber, strAnr, null,
                        emails);
            }
        } catch (RemoteException ex) {
            log(ex.toString());
        } catch (SecurityException ex) {
            log(ex.toString());
        }
        log("updateUsimRecordInEf: " + result);
        return result;
    }

    private int updateUsimRecordInEfByIndex(int efType, int nIndex, String newName,
            String newNumber,
            String strAnr, String strEmail, long subId) {

        if (DBG) {
            log("updateUsimRecordInEfByIndex: efType=" + efType + ", Index=" + nIndex
                    + ", newname=" + newName +
                    ", newnumber=" + newNumber + ", anr =" + strAnr + ", emails=" + strEmail);
        }
        int result = ERROR_ICC_PROVIDER_UNKNOWN;

        String[] emails = null;
        if (strEmail != null) {
            emails = new String[1];
            emails[0] = strEmail;
        }

        try {
            IIccPhoneBook iccIpb = getIccPhbService();

            if (iccIpb != null) {
                result = iccIpb.updateUsimPBRecordsInEfByIndexWithError(subId, efType,
                        newName, newNumber, strAnr, null, emails, nIndex);
            }
        } catch (RemoteException ex) {
            log(ex.toString());
        } catch (SecurityException ex) {
            log(ex.toString());
        }
        log("updateUsimRecordInEfByIndex: " + result);
        return result;
    }

    private int deleteIccRecordFromEf(int efType, String name, String number, String[] emails,
            String pin2, long subId) {
        if (DBG) log("deleteIccRecordFromEf: efType=" + efType +
                ", name=" + name + ", number=" + number + ", emails=" + emails +
                ", pin2=" + pin2 + ", subscription=" + subId);

        int result = ERROR_ICC_PROVIDER_UNKNOWN;

        try {
            IIccPhoneBook iccIpb = getIccPhbService();
            if (iccIpb != null) {
                result = iccIpb.updateAdnRecordsInEfBySearchWithError(subId, efType,
                        name, number, "", "", pin2);
            }
        } catch (RemoteException ex) {
            log(ex.toString());
        } catch (SecurityException ex) {
            log(ex.toString());
        }
        log("deleteIccRecordFromEf: " + result);
        return result;
    }

    private int deleteIccRecordFromEfByIndex(int efType, int nIndex, String pin2, long subId) {
        if (DBG) {
            log("deleteIccRecordFromEfByIndex: efType=" + efType +
                    ", index=" + nIndex + ", pin2=" + pin2);
        }
        int result = ERROR_ICC_PROVIDER_UNKNOWN;

        try {
            IIccPhoneBook iccIpb = getIccPhbService();

            if (iccIpb != null) {
                result = iccIpb.updateAdnRecordsInEfByIndexWithError(subId, efType, "", "", nIndex, pin2);
            }
        } catch (RemoteException ex) {
            log(ex.toString());
        } catch (SecurityException ex) {
            log(ex.toString());
        }
        log("deleteIccRecordFromEfByIndex: " + result);
        return result;
    }

    private int deleteUsimRecordFromEf(int efType, String name,
            String number, String[] emails, long subId) {
        if (DBG) {
            log("deleteUsimRecordFromEf: efType=" + efType +
                    ", name=" + name + ", number=" + number + ", emails=" + emails);
        }
        int result = ERROR_ICC_PROVIDER_UNKNOWN;

        try {
            IIccPhoneBook iccIpb = getIccPhbService();

            if (iccIpb != null) {
                result = iccIpb.updateUsimPBRecordsInEfBySearchWithError(subId, efType,
                        name, number, "", null, null, "", "", "", null, null);
            }
        } catch (RemoteException ex) {
            log(ex.toString());
        } catch (SecurityException ex) {
            log(ex.toString());
        }
        log("deleteUsimRecordFromEf: " + result);
        return result;
    }

    private int deleteUsimRecordFromEfByIndex(int efType, int nIndex, long subId) {
        if (DBG) {
            log("deleteUsimRecordFromEfByIndex: efType=" + efType + ", index=" + nIndex);
        }
        int result = ERROR_ICC_PROVIDER_UNKNOWN;

        try {
            IIccPhoneBook iccIpb = getIccPhbService();

            if (iccIpb != null) {
                result = iccIpb.updateUsimPBRecordsInEfByIndexWithError(subId, efType,
                        "", "", "", null, null, nIndex);
            }
        } catch (RemoteException ex) {
            log(ex.toString());
        } catch (SecurityException ex) {
            log(ex.toString());
        }
        log("deleteUsimRecordFromEfByIndex: " + result);
        return result;
    }

    /**
     * Loads an AdnRecord into a MatrixCursor. Must be called with mLock held.
     *
     * @param record the ADN record to load from
     * @param cursor the cursor to receive the results
     */
    private void loadRecord(AdnRecord record, MatrixCursor cursor, int id) {
        int len = ADDRESS_BOOK_COLUMN_NAMES.length;
        if (!record.isEmpty()) {
            Object[] contact = new Object[len];
            String alphaTag = record.getAlphaTag();
            String number = record.getNumber();
            String[] emails = record.getEmails();
            String anr = null;
            String grpIds = record.getGrpIds();
            String index = Integer.toString(record.getRecordIndex());

            if (len >= ADDRESS_SUPPORT_AAS) {
                int aasIndex = record.getAasIndex();
                contact[7] = aasIndex;
            }
            if (len >= ADDRESS_SUPPORT_SNE) {
                String sne = record.getSne();
                contact[8] = sne;
            }
            if (DBG) {
                log("loadRecord: record:" + record);
            }
            contact[0] = index;
            contact[1] = alphaTag;
            contact[2] = number;

            if (emails != null) {
                StringBuilder emailString = new StringBuilder();
                for (String email : emails) {
                    if (DBG) {
                        log("Adding email:" + email);
                    }
                    emailString.append(email);
                    emailString.append(",");
                }
                contact[3] = emailString.toString();
            }

            contact[4] = record.getAdditionalNumber();
            log("loadRecord Adding anrs:" + contact[4]);

            contact[5] = grpIds;
            contact[6] = id;
            cursor.addRow(contact);
        }
    }

    private void log(String msg) {
        Rlog.d(TAG, "[IccProvider] " + msg);
    }

    private IIccPhoneBook getIccPhbService() {
        IIccPhoneBook iccIpb = IIccPhoneBook.Stub.asInterface(
                ServiceManager.getService("simphonebook"));

        return iccIpb;
    }

    private long getRequestSubId(Uri url) {
        if (DBG) log("getRequestSubId url: " + url);

        try {
            return Long.parseLong(url.getLastPathSegment());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Unknown URL " + url);
        }
    }

    private int updateUsimPBRecordsBySearchWithError(int efType, AdnRecord oldAdn,
            AdnRecord newAdn, long subId) {
        if (DBG) {
            log("updateUsimPBRecordsBySearchWithError subId:" + subId + ",oldAdn:" + oldAdn + ",newAdn:"
                    + newAdn);
        }
        int result = ERROR_ICC_PROVIDER_UNKNOWN;

        try {
            IIccPhoneBook iccIpb = getIccPhbService();

            if (iccIpb != null) {
                result = iccIpb.updateUsimPBRecordsBySearchWithError(subId, efType, oldAdn, newAdn);
            }
        } catch (RemoteException ex) {
            log(ex.toString());
        } catch (SecurityException ex) {
            log(ex.toString());
        }
        log("updateUsimPBRecordsBySearchWithError: " + result);
        return result;
    }

    private int updateUsimPBRecordsByIndexWithError(int efType, AdnRecord newAdn, int index,
            long subId) {
        if (DBG) {
            log("updateUsimPBRecordsByIndexWithError subId:" + subId + ",index:" + index + ",newAdn:" + newAdn);
        }
        int result = ERROR_ICC_PROVIDER_UNKNOWN;

        try {
            IIccPhoneBook iccIpb = getIccPhbService();

            if (iccIpb != null) {
                result = iccIpb.updateUsimPBRecordsByIndexWithError(subId, efType, newAdn, index);
            }
        } catch (RemoteException ex) {
            log(ex.toString());
        } catch (SecurityException ex) {
            log(ex.toString());
        }
        log("updateUsimPBRecordsByIndexWithError: " + result);
        return result;
    }
}
