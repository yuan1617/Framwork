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

package com.android.internal.telephony.cdma;

import android.os.SystemProperties;
import android.util.Log;
import android.util.SparseIntArray;
import java.util.ArrayList;
import android.text.TextUtils;

public class PlusCodeToIddNddUtils {
    static final String LOG_TAG = "PlusCodeToIddNddUtils";
    private static final boolean DBG = false;

    public static final String INTERNATIONAL_PREFIX_SYMBOL="+";

    private static PlusCodeHpcdTable hpcdTable = PlusCodeHpcdTable.getInstance();

    private static PlusCodeHpcdTable sHpcd = PlusCodeHpcdTable.getInstance();


    private static MccIddNddSid mccIddNddSid = null;
    public static void setMccSidLtm(String mcc, String sid, String ltm_off) {
        //Do nothing
    }
    
    public static boolean canFormatPlusToIddNdd() {
        Log.d(LOG_TAG, "-------------canFormatPlusToIddNdd-------------");
        String Mcc = SystemProperties.get(TelephonyPlusCode.PROPERTY_OPERATOR_MCC, "");

        String Sid = SystemProperties.get(TelephonyPlusCode.PROPERTY_OPERATOR_SID, "");

        String Ltm_off = SystemProperties.get(TelephonyPlusCode.PROPERTY_TIME_LTMOFFSET, "");
        Log.d(LOG_TAG, "[getProp from network] get property mcc1 = " + Mcc + ", sid1 = " + Sid +  ", ltm_off1 = " + Ltm_off);

        boolean Find = false;
        mccIddNddSid = null;
        if(sHpcd != null) {
            boolean isValid = !Mcc.startsWith("2134");
            Log.d(LOG_TAG, "[canFormatPlusToIddNdd] Mcc = " + Mcc + ", !Mcc.startsWith(2134) = " + isValid);
            //if(!TextUtils.isEmpty(Mcc) && Character.isDigit(Mcc.charAt(0)) && !Mcc.startsWith("000") && isValid/*!Mcc.startsWith("2134")*/) {
            if(!TextUtils.isEmpty(Mcc) && Character.isDigit(Mcc.charAt(0)) && !Mcc.startsWith("000") && isValid) {
                mccIddNddSid = sHpcd.getCcFromTableByMcc(Mcc);
                Log.d(LOG_TAG, "[canFormatPlusToIddNdd] getCcFromTableByMcc mccIddNddSid = " + mccIddNddSid);
                Find = (mccIddNddSid != null) ? true :false;
            } else { 
                ArrayList<String> mcc_array= sHpcd.getMccFromConflictTableBySid(Sid);
                if(mcc_array == null || mcc_array.size() == 0) {
                    Log.d(LOG_TAG, "[canFormatPlusToIddNdd] Do not find cc by SID from confilcts table, so from lookup table");
                    mccIddNddSid = sHpcd.getCcFromMINSTableBySid(Sid);
                    Log.d(LOG_TAG, "[canFormatPlusToIddNdd] getCcFromMINSTableBySid mccIddNddSid = " + mccIddNddSid);
                } else if(mcc_array.size() >= 2) {
                    String findMcc = sHpcd.getCcFromMINSTableByLTM(mcc_array, Ltm_off);
                    if(findMcc != null && findMcc.length() != 0) {
                         mccIddNddSid = sHpcd.getCcFromTableByMcc(findMcc);
                    }
                    Log.d(LOG_TAG, "[canFormatPlusToIddNdd] conflicts, getCcFromTableByMcc mccIddNddSid = " + mccIddNddSid);
                } else if(mcc_array.size() == 1) {
                    String findMcc = mcc_array.get(0);
                    mccIddNddSid = sHpcd.getCcFromTableByMcc(findMcc);
                    Log.d(LOG_TAG, "[canFormatPlusToIddNdd] do not conflicts, getCcFromTableByMcc mccIddNddSid = " + mccIddNddSid);
                }
            Find = (mccIddNddSid != null) ? true :false;
          }
        }
        Log.d(LOG_TAG, "[canFormatPlusToIddNdd] find = " + Find + ", mccIddNddSid = " +mccIddNddSid);
        return Find;
    }

    private static String formatPlusCode(String number){
        String formatNumber = null;

        //after called canFormatPlusCodeForSms() function. we have known the value of variable "Find" and "mccIddNddSid".
        if(mccIddNddSid != null) {
            String sCC = mccIddNddSid.Cc;
            Log.d(LOG_TAG, "number auto format correctly, mccIddNddSid = " + mccIddNddSid.toString());
            if(!number.startsWith(sCC)) {
                //CC dismatch, remove +(already erased before), add IDD
                formatNumber = mccIddNddSid.Idd + number;
                Log.d(LOG_TAG, "CC dismatch, remove +(already erased before), add IDD formatNumber = " + formatNumber);
            } else {
                //CC matched.
                String Ndd = mccIddNddSid.Ndd;
                if (mccIddNddSid.Cc.equals("86")){
                    //just add "00" before of number, if cc is chinese.
                    Log.d(LOG_TAG, "CC matched, cc is chinese");
                    Ndd = "00";
                }else{
                    //remove +(already erased before) and CC, add NDD.
                    number = number.substring(sCC.length(), number.length());
                    Log.d(LOG_TAG, "[isMobileNumber] number = " + number);
                    if(isMobileNumber(sCC, number)) {
                        Log.d(LOG_TAG, "CC matched, isMobile = true Ndd = ");
                        Ndd = "";
                    }
                }
                formatNumber = Ndd + number;
                Log.d(LOG_TAG, "CC matched, remove +(already erased before) and CC, add NDD formatNumber = "
                             + formatNumber);
                //CC matched and the number is mobile phone number, do not add NDD
            }
        }

        return formatNumber;
    }
    
    /**
    * replace puls code with IDD or NDD
    * input: the number input by the user
    * ouput: the number after deal with plus code
    */
    public static String replacePlusCodeWithIddNdd(String number) {
        Log.d(LOG_TAG, "replacePlusCodeWithIddNdd number = " + number );
        if(number == null || number.length() == 0 || !number.startsWith(INTERNATIONAL_PREFIX_SYMBOL)) {
            Log.d(LOG_TAG, "number can't format correctly, number = " + number);
            return null;
        }

        boolean Find = canFormatPlusToIddNdd();

        if(!Find){
            return null;
        }

        //remove "+" from the phone number;
        if(number.startsWith(INTERNATIONAL_PREFIX_SYMBOL)) {
             Log.d(LOG_TAG, "number before remove plus char , number = " + number);
             number = number.substring(1, number.length());
             Log.d(LOG_TAG, "number after   remove plus char , number = " + number);
         }

        String formatNumber = null;

        //after called canFormatPlusCodeForSms() function. we have known the value of variable "Find" and "mccIddNddSid".
        if(Find) {
            formatNumber = formatPlusCode(number);
        }
        
        return formatNumber;
    }

    private static final SparseIntArray MOBILE_NUMBER_SPEC_MAP = TelephonyPlusCode.MOBILE_NUMBER_SPEC_MAP;

    private static boolean isMobileNumber(String sCC, String number) {
        Log.d(LOG_TAG, "[isMobileNumber] number = " + number + ", sCC = " + sCC);
        if(number == null || number.length() == 0 ) {
            Log.d(LOG_TAG, "[isMobileNumber] please check the param ");
            return false;
        }
        boolean isMobile = false;

        if(MOBILE_NUMBER_SPEC_MAP == null) {
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
        for(int i = 0; i < size; i++) {
            Log.d(LOG_TAG, "[isMobileNumber] value = " + MOBILE_NUMBER_SPEC_MAP.valueAt(i) 
                + ", key =  " + MOBILE_NUMBER_SPEC_MAP.keyAt(i));
            if(MOBILE_NUMBER_SPEC_MAP.valueAt(i) == iCC) {
                Log.d(LOG_TAG, "[isMobileNumber]  value = icc"); 
                String prfix = Integer.toString(MOBILE_NUMBER_SPEC_MAP.keyAt(i));
                Log.d(LOG_TAG, "[isMobileNumber]  prfix = " + prfix); 
                if(number.startsWith(prfix)) {
                    Log.d(LOG_TAG, "[isMobileNumber]  number.startsWith(prfix) = true"); 
                    isMobile = true;
                    break;
                }
            }
        }

        return isMobile;
    }

    /**
    * replace puls code with IDD or NDD
    * input: the phone number for MT or sender of sms or mms.
    * ouput: the number after deal with plus code
    */
    public static String removeIddNddAddPlusCode(String number) {
        Log.d(LOG_TAG, "[removeIddNddAddPlusCode] befor format number = " + number);
        if(number == null || number.length() == 0) {
            Log.d(LOG_TAG, "[removeIddNddAddPlusCode] please check the param ");
            return number;
        }

        String formatNumber = number;
        String prefix = null;
        boolean Find = false;

        int length = number.length();
        if(!number.startsWith("+")) {
            Find = canFormatPlusToIddNdd();

            if(!Find){
                Log.d(LOG_TAG, "[removeIddNddAddPlusCode] find no operator that match the MCC ");
                return number;
            }

            if(mccIddNddSid != null) {
                String Idd = mccIddNddSid.Idd;
                Log.d(LOG_TAG, "[removeIddNddAddPlusCode] find match the cc, Idd = " + Idd);
                if(number.startsWith(Idd) && number.length() > Idd.length()) {
                    number = number.substring(Idd.length(), number.length());
                    formatNumber = INTERNATIONAL_PREFIX_SYMBOL + number;
                } 
            }         
         }

        Log.d(LOG_TAG, "[removeIddNddAddPlusCode] number after format = " + formatNumber);
        return formatNumber;
    } 

    public static boolean canFormatPlusCodeForSms() {
        boolean canFormat = false;
        String mcc =SystemProperties.get(TelephonyPlusCode.PROPERTY_ICC_CDMA_OPERATOR_MCC, "");
        Log.d(LOG_TAG, "[canFormatPlusCodeForSms] Mcc = " + mcc);
        mccIddNddSid = null;
        if(sHpcd != null) {
            Log.d(LOG_TAG, "[canFormatPlusCodeForSms] Mcc = " + mcc);
            if(mcc != null && mcc.length() != 0) {
                mccIddNddSid = sHpcd.getCcFromTableByMcc(mcc);
                Log.d(LOG_TAG, "[canFormatPlusCodeForSms] getCcFromTableByMcc mccIddNddSid = " + mccIddNddSid);
                canFormat = (mccIddNddSid != null) ? true :false;
            }
        }
        return canFormat;

    }

    public static String replacePlusCodeForSms(String number) {
        Log.d(LOG_TAG, "replacePlusCodeForSms number = " + number );
        if(number == null || number.length() == 0 || !number.startsWith(INTERNATIONAL_PREFIX_SYMBOL)) {
            Log.d(LOG_TAG, "number can't format correctly, number = " + number);
            return null;
        }

        boolean camFormat = canFormatPlusCodeForSms();
        if(!camFormat){
            return null;
        }

        //remove "+" from the phone number;
        if(number.startsWith(INTERNATIONAL_PREFIX_SYMBOL)) {
             Log.d(LOG_TAG, "number before remove plus char , number = " + number);
             number = number.substring(1, number.length());
             Log.d(LOG_TAG, "number after   remove plus char , number = " + number);
        }

        String formatNumber = null;

        //after called canFormatPlusCodeForSms() function. we have known the value of variable "Find" and "mccIddNddSid".
        if(camFormat) {
            formatNumber = formatPlusCode(number);
        }
        
        return formatNumber;
        
    }

    public static String removeIddNddAddPlusCodeForSms(String number) {
        Log.d(LOG_TAG, "[removeIddNddAddPlusCodeForSms] befor format number = " + number);
        if(number == null || number.length() == 0) {
            Log.d(LOG_TAG, "[removeIddNddAddPlusCodeForSms] please check the param ");
            return number;
        }

        String formatNumber = number;
        String prefix = null;
        boolean Find = false;

        int length = number.length();
        if(!number.startsWith("+")) {
            boolean camFormat = canFormatPlusCodeForSms();
            if(!camFormat){
                Log.d(LOG_TAG, "[removeIddNddAddPlusCodeForSms] find no operator that match the MCC ");
                return formatNumber;
            }

            if(mccIddNddSid != null) {
                String Idd = mccIddNddSid.Idd;
                Log.d(LOG_TAG, "[removeIddNddAddPlusCodeForSms] find match the cc, Idd = " + Idd);
                if(number.startsWith(Idd) && number.length() > Idd.length()) {
                    number = number.substring(Idd.length(), number.length());
                    Log.d(LOG_TAG, "[removeIddNddAddPlusCodeForSms] sub num = " + number);
                    formatNumber = INTERNATIONAL_PREFIX_SYMBOL + number;
                }
            }
         }

        Log.d(LOG_TAG, "[removeIddNddAddPlusCodeForSms] number after format = " + formatNumber);
        return formatNumber;
    }

    private static void testProp() {
        String mcc1 ="";//SystemProperties.get(TelephonyPlusCode.PROPERTY_OPERATOR_MCC, "");

        String sid1 = "";//SystemProperties.get(TelephonyPlusCode.PROPERTY_OPERATOR_SID, "");

        String ltm_off1 = "";//SystemProperties.get(TelephonyPlusCode.PROPERTY_TIME_LTMOFFSET, "");
        
        String icc_mcc = "";//SystemProperties.get(TelephonyPlusCode.PROPERTY_ICC_CDMA_OPERATOR_MCC, "");
        Log.d(LOG_TAG, "[testProp] get property mcc1 = " + mcc1 + 
                       ", sid1 = " + sid1 +  
                       ", ltm_off1 = " + ltm_off1 +
                       ", icc_mcc = " + icc_mcc);
    }

    public static String checkMccBySidLtmOff(String mccMnc) {
        Log.d(LOG_TAG, "[checkMccBySidLtmOff] mccMnc = " + mccMnc);
        
        String Sid = SystemProperties.get("cdma.operator.sid", "");
        String Ltm_off = SystemProperties.get("cdma.operator.ltmoffset", "");

        Log.d(LOG_TAG, "[checkMccBySidLtmOff] Sid = " + Sid + ", Ltm_off = " + Ltm_off);

        String Mcc = sHpcd.getMccFromConflictTableBySidLtmOff(Sid, Ltm_off);
        String tempMcc;
        String MccMnc;

        Log.d(LOG_TAG, "[checkMccBySidLtmOff] MccFromConflictTable = " + Mcc);

        if(Mcc != null){
            tempMcc = Mcc;
        } else{
            Mcc = sHpcd.getMccFromMINSTableBySid(Sid);
            Log.d(LOG_TAG, "[checkMccBySidLtmOff] MccFromMINSTable = " + Mcc);
            if(Mcc != null){
                tempMcc = Mcc;
            } else{
                tempMcc = mccMnc;
            }
        }

        Log.d(LOG_TAG, "[checkMccBySidLtmOff] tempMcc = " + tempMcc);

        if(tempMcc.startsWith("310") || tempMcc.startsWith("311") || tempMcc.startsWith("312")) {
            MccMnc = sHpcd.getMccMncFromSidMccMncListBySid(Sid);

            Log.d(LOG_TAG, "[checkMccBySidLtmOff] MccMnc = " + MccMnc);

            if(MccMnc != null) {
                tempMcc = MccMnc;
            }
        }

        return tempMcc;
    }

} 
