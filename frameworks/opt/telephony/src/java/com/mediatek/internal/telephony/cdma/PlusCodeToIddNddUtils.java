/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.mediatek.internal.telephony.cdma;

import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseIntArray;

import java.util.List;

/**
 * The utilities of converting plus code to IddNdd.
 * @hide
 */
public class PlusCodeToIddNddUtils {
    static final String LOG_TAG = "PlusCodeToIddNddUtils";

    public static final String INTERNATIONAL_PREFIX_SYMBOL = "+";

    private static PlusCodeHpcdTable sHpcd = PlusCodeHpcdTable.getInstance();
    private static MccIddNddSid sMccIddNddSid = null;

    /**
     * @return if can convert plus code to IddNdd.
     */
    public static boolean canFormatPlusToIddNdd() {
        Log.d(LOG_TAG, "-------------canFormatPlusToIddNdd-------------");
        String mccStr = SystemProperties.get(TelephonyPlusCode.PROPERTY_OPERATOR_MCC, "");
        String sidStr = SystemProperties.get(TelephonyPlusCode.PROPERTY_OPERATOR_SID, "");
        String ltmoffStr = SystemProperties.get(TelephonyPlusCode.PROPERTY_TIME_LTMOFFSET, "");
        Log.d(LOG_TAG, "[getProp from network] get property mcc1 = " + mccStr
                + ", sid1 = " + sidStr + ", ltm_off1 = " + ltmoffStr);

        boolean find = false;
        sMccIddNddSid = null;
        if (sHpcd != null) {
            boolean isValid = !mccStr.startsWith("2134");
            Log.d(LOG_TAG, "[canFormatPlusToIddNdd] Mcc = " + mccStr
                    + ", !Mcc.startsWith(2134) = " + isValid);

            if (!TextUtils.isEmpty(mccStr) && Character.isDigit(mccStr.charAt(0))
                    && !mccStr.startsWith("000") && isValid) {
                sMccIddNddSid = PlusCodeHpcdTable.getCcFromTableByMcc(mccStr);
                Log.d(LOG_TAG,
                        "[canFormatPlusToIddNdd] getCcFromTableByMcc mccIddNddSid = "
                                + sMccIddNddSid);
                find = (sMccIddNddSid != null) ? true : false;
            } else {
                List<String> mccArray = PlusCodeHpcdTable.getMccFromConflictTableBySid(sidStr);
                if (mccArray == null || mccArray.size() == 0) {
                    Log.d(LOG_TAG, "[canFormatPlusToIddNdd] Do not find cc by SID from confilcts" +
                            " table, so from lookup table");
                    sMccIddNddSid = PlusCodeHpcdTable.getCcFromMINSTableBySid(sidStr);
                    Log.d(LOG_TAG,
                            "[canFormatPlusToIddNdd] getCcFromMINSTableBySid mccIddNddSid = "
                                    + sMccIddNddSid);
                } else if (mccArray.size() >= 2) {
                    String findMcc = sHpcd.getCcFromMINSTableByLTM(mccArray, ltmoffStr);
                    if (findMcc != null && findMcc.length() != 0) {
                        sMccIddNddSid = PlusCodeHpcdTable.getCcFromTableByMcc(findMcc);
                    }
                    Log.d(LOG_TAG,
                            "[canFormatPlusToIddNdd] conflicts, getCcFromTableByMcc mccIddNddSid = "
                                    + sMccIddNddSid);
                } else if (mccArray.size() == 1) {
                    String findMcc = mccArray.get(0);
                    sMccIddNddSid = PlusCodeHpcdTable.getCcFromTableByMcc(findMcc);
                    Log.d(LOG_TAG,
                            "[canFormatPlusToIddNdd] do not conflicts, getCcFromTableByMcc" +
                            " mccIddNddSid = " + sMccIddNddSid);
                }
                find = (sMccIddNddSid != null) ? true : false;
            }
        }
        Log.d(LOG_TAG, "[canFormatPlusToIddNdd] find = " + find
                + ", mccIddNddSid = " + sMccIddNddSid);
        return find;
    }

    private static String formatPlusCode(String number) {
        String formatNumber = null;

        // after called canFormatPlusCodeForSms() function. we have known the
        // value of variable "Find" and "mccIddNddSid".
        if (sMccIddNddSid != null) {
            String sCC = sMccIddNddSid.mCc;
            Log.d(LOG_TAG, "number auto format correctly, mccIddNddSid = " +
                    sMccIddNddSid.toString());
            if (!number.startsWith(sCC)) {
                // CC dismatch, remove +(already erased before), add IDD
                formatNumber = sMccIddNddSid.mIdd + number;
                Log.d(LOG_TAG,
                        "CC dismatch, remove +(already erased before), add IDD formatNumber = "
                                + formatNumber);
            } else {
                // CC matched.
                String nddStr = sMccIddNddSid.mNdd;
                if (sMccIddNddSid.mCc.equals("86")) {
                    // just add "00" before of number, if cc is chinese.
                    Log.d(LOG_TAG, "CC matched, cc is chinese");
                    nddStr = "00";
                } else {
                    // remove +(already erased before) and CC, add NDD.
                    number = number.substring(sCC.length(), number.length());
                    Log.d(LOG_TAG, "[isMobileNumber] number = " + number);
                    if (isMobileNumber(sCC, number)) {
                        Log.d(LOG_TAG, "CC matched, isMobile = true Ndd = ");
                        nddStr = "";
                    }
                }
                formatNumber = nddStr + number;
                Log.d(LOG_TAG,
                        "CC matched, remove +(already erased before) and CC," +
                        " add NDD formatNumber = " + formatNumber);
                // CC matched and the number is mobile phone number, do not add NDD
            }
        }

        return formatNumber;
    }

    /**
     * Replace plus code with IDD or NDD input: the number input by the user.
     * @param number the number value
     * @return the number after deal with plus code
     */
    public static String replacePlusCodeWithIddNdd(String number) {
        Log.d(LOG_TAG, "replacePlusCodeWithIddNdd number = " + number);
        if (number == null || number.length() == 0
                || !number.startsWith(INTERNATIONAL_PREFIX_SYMBOL)) {
            Log.d(LOG_TAG, "number can't format correctly, number = " + number);
            return null;
        }

        boolean bFind = canFormatPlusToIddNdd();

        if (!bFind) {
            return null;
        }

        // remove "+" from the phone number;
        if (number.startsWith(INTERNATIONAL_PREFIX_SYMBOL)) {
            Log.d(LOG_TAG, "number before remove plus char , number = "
                    + number);
            number = number.substring(1, number.length());
            Log.d(LOG_TAG, "number after   remove plus char , number = "
                    + number);
        }

        String formatNumber = null;

        // after called canFormatPlusCodeForSms() function. we have known the
        // value of variable "Find" and "mccIddNddSid".
        if (bFind) {
            formatNumber = formatPlusCode(number);
        }

        return formatNumber;
    }

    private static final SparseIntArray MOBILE_NUMBER_SPEC_MAP =
            TelephonyPlusCode.MOBILE_NUMBER_SPEC_MAP;

    private static boolean isMobileNumber(String sCC, String number) {
        Log.d(LOG_TAG, "[isMobileNumber] number = " + number + ", sCC = " + sCC);
        if (number == null || number.length() == 0) {
            Log.d(LOG_TAG, "[isMobileNumber] please check the param ");
            return false;
        }
        boolean isMobile = false;

        if (MOBILE_NUMBER_SPEC_MAP == null) {
            Log.d(LOG_TAG, "[isMobileNumber] MOBILE_NUMBER_SPEC_MAP == null ");
            return isMobile;
        }

        int size = MOBILE_NUMBER_SPEC_MAP.size();
        int iCC;
        try {
            iCC = Integer.parseInt(sCC);
        } catch (NumberFormatException e) {
            Log.e(LOG_TAG, Log.getStackTraceString(e));
            return isMobile;
        }

        Log.d(LOG_TAG, "[isMobileNumber] iCC = " + iCC);
        for (int i = 0; i < size; i++) {
            Log.d(LOG_TAG,
                    "[isMobileNumber] value = "
                            + MOBILE_NUMBER_SPEC_MAP.valueAt(i) + ", key =  "
                            + MOBILE_NUMBER_SPEC_MAP.keyAt(i));
            if (MOBILE_NUMBER_SPEC_MAP.valueAt(i) == iCC) {
                Log.d(LOG_TAG, "[isMobileNumber]  value = icc");
                String prfix = Integer
                        .toString(MOBILE_NUMBER_SPEC_MAP.keyAt(i));
                Log.d(LOG_TAG, "[isMobileNumber]  prfix = " + prfix);
                if (number.startsWith(prfix)) {
                    Log.d(LOG_TAG,
                            "[isMobileNumber]  number.startsWith(prfix) = true");
                    isMobile = true;
                    break;
                }
            }
        }

        return isMobile;
    }

    /**
     * Replace puls code with IDD or NDD input: the phone number for MT or
     * sender of sms or mms.
     * @param number the number value
     * @return the number after deal with plus code
     */
    public static String removeIddNddAddPlusCode(String number) {
        Log.d(LOG_TAG, "[removeIddNddAddPlusCode] befor format number = "
                + number);
        if (number == null || number.length() == 0) {
            Log.d(LOG_TAG, "[removeIddNddAddPlusCode] please check the param ");
            return number;
        }

        String formatNumber = number;
        boolean bFind = false;

        if (!number.startsWith("+")) {
            bFind = canFormatPlusToIddNdd();

            if (!bFind) {
                Log.d(LOG_TAG,
                        "[removeIddNddAddPlusCode] find no operator that match the MCC ");
                return number;
            }

            if (sMccIddNddSid != null) {
                String strIdd = sMccIddNddSid.mIdd;
                Log.d(LOG_TAG,
                        "[removeIddNddAddPlusCode] find match the cc, Idd = " + strIdd);
                if (number.startsWith(strIdd) && number.length() > strIdd.length()) {
                    number = number.substring(strIdd.length(), number.length());
                    formatNumber = INTERNATIONAL_PREFIX_SYMBOL + number;
                }
            }
        }

        Log.d(LOG_TAG, "[removeIddNddAddPlusCode] number after format = "
                + formatNumber);
        return formatNumber;
    }

    /**
     * @return if can format plus code for sms.
     */
    public static boolean canFormatPlusCodeForSms() {
        boolean canFormat = false;
        String mcc = SystemProperties.get(
                TelephonyPlusCode.PROPERTY_ICC_CDMA_OPERATOR_MCC, "");
        Log.d(LOG_TAG, "[canFormatPlusCodeForSms] Mcc = " + mcc);
        sMccIddNddSid = null;
        if (sHpcd != null) {
            Log.d(LOG_TAG, "[canFormatPlusCodeForSms] Mcc = " + mcc);
            if (mcc != null && mcc.length() != 0) {
                sMccIddNddSid = PlusCodeHpcdTable.getCcFromTableByMcc(mcc);
                Log.d(LOG_TAG,
                        "[canFormatPlusCodeForSms] getCcFromTableByMcc mccIddNddSid = "
                                + sMccIddNddSid);
                canFormat = (sMccIddNddSid != null) ? true : false;
            }
        }
        return canFormat;

    }

    /**
     * Replace puls code, the phone number for MT or sender of sms or mms.
     * @param number the number value
     * @return the number after deal with plus code
     */
    public static String replacePlusCodeForSms(String number) {
        Log.d(LOG_TAG, "replacePlusCodeForSms number = " + number);
        if (number == null || number.length() == 0
                || !number.startsWith(INTERNATIONAL_PREFIX_SYMBOL)) {
            Log.d(LOG_TAG, "number can't format correctly, number = " + number);
            return null;
        }

        boolean camFormat = canFormatPlusCodeForSms();
        if (!camFormat) {
            return null;
        }

        // remove "+" from the phone number;
        if (number.startsWith(INTERNATIONAL_PREFIX_SYMBOL)) {
            Log.d(LOG_TAG, "number before remove plus char , number = "
                    + number);
            number = number.substring(1, number.length());
            Log.d(LOG_TAG, "number after   remove plus char , number = "
                    + number);
        }

        String formatNumber = null;

        // after called canFormatPlusCodeForSms() function. we have known the
        // value of variable "Find" and "mccIddNddSid".
        if (camFormat) {
            formatNumber = formatPlusCode(number);
        }

        return formatNumber;

    }

    /**
     * Replace puls code with IDD or NDD input: the phone number for MT or
     * sender of sms or mms.
     * @param number the number value
     * @return the number after deal with plus code
     */
    public static String removeIddNddAddPlusCodeForSms(String number) {
        Log.d(LOG_TAG, "[removeIddNddAddPlusCodeForSms] befor format number = "
                + number);
        if (number == null || number.length() == 0) {
            Log.d(LOG_TAG,
                    "[removeIddNddAddPlusCodeForSms] please check the param ");
            return number;
        }

        String formatNumber = number;
        if (!number.startsWith("+")) {
            boolean camFormat = canFormatPlusCodeForSms();
            if (!camFormat) {
                Log.d(LOG_TAG,
                        "[removeIddNddAddPlusCodeForSms] find no operator that match the MCC ");
                return formatNumber;
            }

            if (sMccIddNddSid != null) {
                String strIdd = sMccIddNddSid.mIdd;
                Log.d(LOG_TAG,
                        "[removeIddNddAddPlusCodeForSms] find match the cc, Idd = " + strIdd);
                if (number.startsWith(strIdd) && number.length() > strIdd.length()) {
                    number = number.substring(strIdd.length(), number.length());
                    Log.d(LOG_TAG, "[removeIddNddAddPlusCodeForSms] sub num = " + number);
                    formatNumber = INTERNATIONAL_PREFIX_SYMBOL + number;
                }
            }
        }

        Log.d(LOG_TAG, "[removeIddNddAddPlusCodeForSms] number after format = " + formatNumber);
        return formatNumber;
    }


    /**
     * Check mcc by sid ltm off.
     * @param mccMnc the MCCMNC
     * @return the MCCMNC
     */
    public static String checkMccBySidLtmOff(String mccMnc) {
        Log.d(LOG_TAG, "[checkMccBySidLtmOff] mccMnc = " + mccMnc);

        String strSid = SystemProperties.get("cdma.operator.sid", "");
        String strLtmOff = SystemProperties.get("cdma.operator.ltmoffset", "");

        Log.d(LOG_TAG, "[checkMccBySidLtmOff] Sid = " + strSid + ", Ltm_off = " + strLtmOff);

        String strMcc = PlusCodeHpcdTable.getMccFromConflictTableBySidLtmOff(strSid, strLtmOff);
        String tempMcc;
        String strMccMnc;

        Log.d(LOG_TAG, "[checkMccBySidLtmOff] MccFromConflictTable = " + strMcc);

        if (strMcc != null) {
            tempMcc = strMcc;
        } else {
            strMcc = PlusCodeHpcdTable.getMccFromMINSTableBySid(strSid);
            Log.d(LOG_TAG, "[checkMccBySidLtmOff] MccFromMINSTable = " + strMcc);
            if (strMcc != null) {
                tempMcc = strMcc;
            } else {
                tempMcc = mccMnc;
            }
        }

        Log.d(LOG_TAG, "[checkMccBySidLtmOff] tempMcc = " + tempMcc);

        if (tempMcc.startsWith("310") || tempMcc.startsWith("311") || tempMcc.startsWith("312")) {
            strMccMnc = PlusCodeHpcdTable.getMccMncFromSidMccMncListBySid(strSid);
            Log.d(LOG_TAG, "[checkMccBySidLtmOff] MccMnc = " + strMccMnc);
            if (strMccMnc != null) {
                tempMcc = strMccMnc;
            }
        }

        return tempMcc;
    }
}
