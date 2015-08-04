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

package com.android.internal.telephony.uicc;

import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.telephony.Rlog;

import com.android.internal.telephony.GsmAlphabet;
import com.android.internal.telephony.CommandException;

import java.util.Arrays;
import java.util.regex.Pattern;
import java.io.UnsupportedEncodingException;


/**
 *
 * Used to load or store ADNs (Abbreviated Dialing Numbers).
 *
 * {@hide}
 *
 */
public class AdnRecord implements Parcelable {
    static final String LOG_TAG = "AdnRecord";

    //***** Instance Variables

    String mAlphaTag = null;
    String mNumber = null;
    String additionalNumber = null;
    String additionalNumber2 = null;
    String additionalNumber3 = null;
    String grpIds;
    String[] mEmails;
    int mExtRecord = 0xff;
    int mEfid;                   // or 0 if none
    int mRecordNumber;           // or 0 if none
    // The index of aas
    int aas = -1;
    String sne = null;

    //***** Constants
    //see IccProvider.java
    public static final int ERROR_ICC_PROVIDER_NO_ERROR = 1;
    public static final int ERROR_ICC_PROVIDER_UNKNOWN = 0;
    public static final int ERROR_ICC_PROVIDER_NUMBER_TOO_LONG = -1;
    public static final int ERROR_ICC_PROVIDER_TEXT_TOO_LONG = -2;
    public static final int ERROR_ICC_PROVIDER_WRONG_ADN_FORMAT = -15;

    int result = ERROR_ICC_PROVIDER_NO_ERROR;

    // In an ADN record, everything but the alpha identifier
    // is in a footer that's 14 bytes
    static final int FOOTER_SIZE_BYTES = 14;

    // Maximum size of the un-extended number field
    static final int MAX_NUMBER_SIZE_BYTES = 11;

    static final int EXT_RECORD_LENGTH_BYTES = 13;
    static final int EXT_RECORD_TYPE_ADDITIONAL_DATA = 2;
    static final int EXT_RECORD_TYPE_MASK = 3;
    static final int MAX_EXT_CALLED_PARTY_LENGTH = 0xa;

    // ADN offset
    static final int ADN_BCD_NUMBER_LENGTH = 0;
    static final int ADN_TON_AND_NPI = 1;
    static final int ADN_DIALING_NUMBER_START = 2;
    static final int ADN_DIALING_NUMBER_END = 11;
    static final int ADN_CAPABILITY_ID = 12;
    static final int ADN_EXTENSION_ID = 13;
    private static final String SIM_NUM_PATTERN = "[+]?[[0-9][*#pw,;]]+[[0-9][*#pw,;]]*";

    //***** Static Methods

    public static final Parcelable.Creator<AdnRecord> CREATOR
            = new Parcelable.Creator<AdnRecord>() {
        @Override
        public AdnRecord createFromParcel(Parcel source) {
            int efid;
            int recordNumber;
            String alphaTag;
            String number;
            String anr;
            String anr2;
            String anr3;
            String grpIds;
            String[] emails;

            efid = source.readInt();
            recordNumber = source.readInt();
            alphaTag = source.readString();
            number = source.readString();
            emails = source.readStringArray();
            anr = source.readString();
            anr2 = source.readString();
            anr3 = source.readString();
            grpIds = source.readString();
            int aas = source.readInt();
            String sne = source.readString();
            AdnRecord adn = new AdnRecord(efid, recordNumber, alphaTag, number, anr, anr2, anr3, emails, grpIds);
            adn.setAasIndex(aas);
            adn.setSne(sne);
            return adn;
        }

        @Override
        public AdnRecord[] newArray(int size) {
            return new AdnRecord[size];
        }
    };


    //***** Constructor
    public AdnRecord (byte[] record) {
        this(0, 0, record);
    }

    public AdnRecord (int efid, int recordNumber, byte[] record) {
        this.mEfid = efid;
        this.mRecordNumber = recordNumber;
        parseRecord(record);
    }

    public AdnRecord (String alphaTag, String number) {
        this(0, 0, alphaTag, number);
    }

    public AdnRecord(String alphaTag, String number, String anr) {
        this(0, 0, alphaTag, number, anr);
    }

    public AdnRecord (String alphaTag, String number, String[] emails) {
        this(0, 0, alphaTag, number, emails);
    }

    public AdnRecord (int efid, int recordNumber, String alphaTag, String number, String[] emails) {
        this.mEfid = efid;
        this.mRecordNumber = recordNumber;
        this.mAlphaTag = alphaTag;
        this.mNumber = number;
        this.mEmails = emails;
        this.additionalNumber = "";
        this.additionalNumber2 = "";
        this.additionalNumber3 = "";
        this.grpIds = null;
    }

    public AdnRecord(int efid, int recordNumber, String alphaTag, String number) {
        this.mEfid = efid;
        this.mRecordNumber = recordNumber;
        this.mAlphaTag = alphaTag;
        this.mNumber = number;
        this.mEmails = null;
        this.additionalNumber = "";
        this.additionalNumber2 = "";
        this.additionalNumber3 = "";
        this.grpIds = null;
    }

    public AdnRecord(int efid, int recordNumber, String alphaTag, String number, String anr) {
        this.mEfid = efid;
        this.mRecordNumber = recordNumber;
        this.mAlphaTag = alphaTag;
        this.mNumber = number;
        this.mEmails = null;
        this.additionalNumber = anr;
        this.additionalNumber2 = "";
        this.additionalNumber3 = "";
        this.grpIds = null;
    }

    public AdnRecord(int efid, int recordNumber, String alphaTag, String number, String anr,
            String[] emails, String grps) {
        this.mEfid = efid;
        this.mRecordNumber = recordNumber;
        this.mAlphaTag = alphaTag;
        this.mNumber = number;
        this.mEmails = emails;
        this.additionalNumber = anr;
        this.additionalNumber2 = "";
        this.additionalNumber3 = "";
        this.grpIds = grps;
    }

    public AdnRecord(int efid, int recordNumber, String alphaTag, String number, String anr,
            String anr2, String anr3, String[] emails, String grps) {
        this.mEfid = efid;
        this.mRecordNumber = recordNumber;
        this.mAlphaTag = alphaTag;
        this.mNumber = number;
        this.mEmails = emails;
        this.additionalNumber = anr;
        this.additionalNumber2 = anr2;
        this.additionalNumber3 = anr3;
        this.grpIds = grps;
    }

    //***** Instance Methods
    public int getRecordIndex() {
        return mRecordNumber;
    }

    public String getAlphaTag() {
        return mAlphaTag;
    }

    public String getNumber() {
        return mNumber;
    }

    public String getAdditionalNumber() {
        return additionalNumber;
    }

    public String getAdditionalNumber(int index) {
        String number = null;
        if (index == 0) {
            number = this.additionalNumber;
        } else if (index == 1) {
            number = this.additionalNumber2;
        } else if (index == 2) {
            number = this.additionalNumber3;
        } else {
            Rlog.w(LOG_TAG, "getAdditionalNumber Error:" + index);
    }
        return number;
    }

    public int getAasIndex() {
        return aas;
    }

    public String getSne() {
        return sne;
    }

    public String[] getEmails() {
        return mEmails;
    }

    public String getGrpIds() {
        return grpIds;
    }

    public void setNumber(String number) {
        this.mNumber = number;
    }

    public void setAnr(String anr) {
        this.additionalNumber = anr;
    }

    public void setAnr(String anr, int index) {
        if (index == 0) {
            this.additionalNumber = anr;
        } else if (index == 1) {
            this.additionalNumber2 = anr;
        } else if (index == 2) {
            this.additionalNumber3 = anr;
        } else {
            Rlog.w(LOG_TAG, "setAnr Error:" + index);
        }
    }

    public void setAasIndex(int aas) {
        this.aas = aas;
    }

    public void setSne(String sne) {
        this.sne = sne;
    }

    public void setGrpIds(String grps) {
        this.grpIds = grps;
    }

    public void setEmails(String[] emails) {
        this.mEmails = emails;
    }

    public void setRecordIndex(int nIndex) {
        this.mRecordNumber = nIndex;
    }

    @Override
    public String toString() {
        return "ADN Record:" + mRecordNumber
                + ",alphaTag:" + mAlphaTag
                + ",number:" + mNumber
                + ",anr:" + additionalNumber
                + ",anr2:" + additionalNumber2
                + ",anr3:" + additionalNumber3
                + ",aas:" + aas
                + ",emails:" + mEmails
                + ",grpIds:" + grpIds
                + ",sne:" + sne;
    }

    public boolean isEmpty() {
        return TextUtils.isEmpty(mAlphaTag) && TextUtils.isEmpty(mNumber)
                && TextUtils.isEmpty(additionalNumber) && mEmails == null;
    }

    public boolean hasExtendedRecord() {
        return mExtRecord != 0 && mExtRecord != 0xff;
    }

    /** Helper function for {@link #isEqual}. */
    private static boolean stringCompareNullEqualsEmpty(String s1, String s2) {
        if (s1 == s2) {
            return true;
        }
        if (s1 == null) {
            s1 = "";
        }
        if (s2 == null) {
            s2 = "";
        }
        return (s1.equals(s2));
    }

    public boolean isEqual(AdnRecord adn) {
        return (stringCompareNullEqualsEmpty(mAlphaTag, adn.mAlphaTag) && stringCompareNullEqualsEmpty(
                mNumber, adn.mNumber));
    }

    //***** Parcelable Implementation

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mEfid);
        dest.writeInt(mRecordNumber);
        dest.writeString(mAlphaTag);
        dest.writeString(mNumber);
        dest.writeStringArray(mEmails);
        dest.writeString(additionalNumber);
        dest.writeString(additionalNumber2);
        dest.writeString(additionalNumber3);
        dest.writeString(grpIds);
        dest.writeInt(aas);
        dest.writeString(sne);
    }

    /**
     * Build adn hex byte array based on record size
     * The format of byte array is defined in 51.011 10.5.1
     *
     * @param recordSize is the size X of EF record
     * @return hex byte[recordSize] to be written to EF record
     *          return null for wrong format of dialing number or tag
     */
    public byte[] buildAdnString(int recordSize) {
        Rlog.w(LOG_TAG, "in BuildAdnString");
        byte[] bcdNumber;
        byte[] byteTag;
        byte[] adnString;
        int footerOffset = recordSize - FOOTER_SIZE_BYTES;
        int alphaIdLength = 0;

        // create an empty record
        adnString = new byte[recordSize];
        for (int i = 0; i < recordSize; i++) {
            adnString[i] = (byte) 0xFF;
        }
        if (isPhoneNumberInvaild(mNumber)) {
            Rlog.w(LOG_TAG, "[buildAdnString] invaild number");
            result = ERROR_ICC_PROVIDER_WRONG_ADN_FORMAT;
            return null;
        }
        if (TextUtils.isEmpty(mNumber)) {
            Rlog.w(LOG_TAG, "[buildAdnString] Empty dialing number");
            result = ERROR_ICC_PROVIDER_NO_ERROR;
            //return adnString;   // return the empty record (for delete)
        } else if (mNumber.length()
                > (ADN_DIALING_NUMBER_END - ADN_DIALING_NUMBER_START + 1) * 2) {
            result = ERROR_ICC_PROVIDER_NUMBER_TOO_LONG;
            Rlog.w(LOG_TAG,
                    "[buildAdnString] Max length of dialing number is 20");
            return null;
        } else {
              result = ERROR_ICC_PROVIDER_NO_ERROR;
            try {
                bcdNumber = PhoneNumberUtils.numberToCalledPartyBCD(mNumber);
            } catch (RuntimeException exc) {
                CommandException cmdEx = new CommandException(CommandException.Error.INVALID_PARAMETER);
                throw new RuntimeException("invalid number for BCD ", cmdEx);
            }
            System.arraycopy(bcdNumber, 0, adnString,
                    footerOffset + ADN_TON_AND_NPI, bcdNumber.length);

            adnString[footerOffset + ADN_BCD_NUMBER_LENGTH]
                    = (byte) (bcdNumber.length);
            adnString[footerOffset + ADN_CAPABILITY_ID]
                    = (byte) 0xFF; // Capability Id
            adnString[footerOffset + ADN_EXTENSION_ID]
                    = (byte) 0xFF; // Extension Record Id
        }

        if (!TextUtils.isEmpty(mAlphaTag)) {
            if (isContainChineseChar(mAlphaTag)) {
                Rlog.w(LOG_TAG, "[buildAdnString] getBytes,alphaTag:" + mAlphaTag);
                try {
                    Rlog.w(LOG_TAG, "call getBytes");
                    byteTag = mAlphaTag.getBytes("utf-16be");
                    Rlog.w(LOG_TAG, "byteTag," + IccUtils.bytesToHexString(byteTag));
                } catch (UnsupportedEncodingException ex) {
                    Rlog.w(LOG_TAG, "[buildAdnString] getBytes exception");
                    return null;
                }
                byte[] header = new byte[1];
                header[0] = (byte) 0x80;
                System.arraycopy(header, 0, adnString, 0, 1);
                System.arraycopy(byteTag, 0, adnString, 1, byteTag.length);
                alphaIdLength = byteTag.length + 1;
                Rlog.w(LOG_TAG, "arrarString" + IccUtils.bytesToHexString(adnString));
            } else {
                Rlog.w(LOG_TAG, "[buildAdnString] stringToGsm8BitPacked");
                byteTag = GsmAlphabet.stringToGsm8BitPacked(mAlphaTag);
                alphaIdLength = byteTag.length;
                System.arraycopy(byteTag, 0, adnString, 0, byteTag.length);
            }
        }

        if (mAlphaTag != null && alphaIdLength > footerOffset) {
            result = ERROR_ICC_PROVIDER_TEXT_TOO_LONG;
            Rlog.w(LOG_TAG,
                "[buildAdnString] Max length of tag is " + footerOffset + ",alphaIdLength:" + alphaIdLength);
            return null;
        }

        return adnString;
    }

    public int getErrorNumber() {
        return result;
    }
    /**
     * See TS 51.011 10.5.10
     */
    public void
    appendExtRecord (byte[] extRecord) {
        try {
            if (extRecord.length != EXT_RECORD_LENGTH_BYTES) {
                return;
            }

            if ((extRecord[0] & EXT_RECORD_TYPE_MASK)
                    != EXT_RECORD_TYPE_ADDITIONAL_DATA) {
                return;
            }

            if ((0xff & extRecord[1]) > MAX_EXT_CALLED_PARTY_LENGTH) {
                // invalid or empty record
                return;
            }

            mNumber += PhoneNumberUtils.calledPartyBCDFragmentToString(
                                        extRecord, 2, 0xff & extRecord[1]);

            // We don't support ext record chaining.

        } catch (RuntimeException ex) {
            Rlog.w(LOG_TAG, "Error parsing AdnRecord ext record", ex);
        }
    }

    //***** Private Methods

    /**
     * mAlphaTag and number are set to null on invalid format
     */
    private void
    parseRecord(byte[] record) {
        try {
            mAlphaTag = IccUtils.adnStringFieldToString(
                            record, 0, record.length - FOOTER_SIZE_BYTES);

            int footerOffset = record.length - FOOTER_SIZE_BYTES;

            int numberLength = 0xff & record[footerOffset];

            if (numberLength > MAX_NUMBER_SIZE_BYTES) {
                // Invalid number length
                mNumber = "";
                return;
            }

            // Please note 51.011 10.5.1:
            //
            // "If the Dialling Number/SSC String does not contain
            // a dialling number, e.g. a control string deactivating
            // a service, the TON/NPI byte shall be set to 'FF' by
            // the ME (see note 2)."

            mNumber = PhoneNumberUtils.calledPartyBCDToString(
                            record, footerOffset + 1, numberLength);


            mExtRecord = 0xff & record[record.length - 1];

            mEmails = null;
            additionalNumber = "";
            additionalNumber2 = "";
            additionalNumber3 = "";
            grpIds = null;

        } catch (RuntimeException ex) {
            Rlog.w(LOG_TAG, "Error parsing AdnRecord", ex);
            mNumber = "";
            mAlphaTag = "";
            mEmails = null;
            additionalNumber = "";
            additionalNumber2 = "";
            additionalNumber3 = "";
            grpIds = null;
        }
    }
    /// M: judge alphat whether contains chinese character
    private boolean isContainChineseChar(String alphTag) {
        boolean result = false;
        int length = alphTag.length();

        for (int i = 0; i < length; i++) {
            if (Pattern.matches("[\u4E00-\u9FA5]", alphTag.substring(i, i + 1))) {
                result = true;
                break;
            }
        }

        return result;
    }
    private boolean isPhoneNumberInvaild(String phoneNumber) {
        String tempPhoneNumber = null;
        if (!TextUtils.isEmpty(phoneNumber)) {
            tempPhoneNumber = PhoneNumberUtils.stripSeparators(phoneNumber);

            if (!Pattern.matches(SIM_NUM_PATTERN,
                    PhoneNumberUtils.extractCLIRPortion(tempPhoneNumber))) {
                return true;
            }
        }
        return false;
    }
}
