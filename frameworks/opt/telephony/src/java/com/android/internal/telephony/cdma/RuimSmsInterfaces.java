//package com.android.internal.telephony.cdma.viatelecom;
package com.android.internal.telephony.cdma;

import android.util.Log;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import com.android.internal.telephony.cdma.sms.CdmaSmsAddress;
import com.android.internal.telephony.cdma.sms.SmsEnvelope;

/**
 * Provide some method to convert Sms after
 * write to uim with a submitpdu.
 */
public class RuimSmsInterfaces {
    private static String LOG_TAG = "RuimSmsInterfaces 1.0";
    static public final int SMS_CDMA_RECORD_LENGTH = 255;

    /**
     * Converts a 4-Bit DTMF encoded symbol from the calling address number to ASCII character
     * porting from cdma SmsMessage.java
     */
    public static byte convertDtmfToAscii(byte dtmfDigit) {
        byte asciiDigit;

        switch (dtmfDigit) {
        case  0: asciiDigit = 68; break; // 'D'
        case  1: asciiDigit = 49; break; // '1'
        case  2: asciiDigit = 50; break; // '2'
        case  3: asciiDigit = 51; break; // '3'
        case  4: asciiDigit = 52; break; // '4'
        case  5: asciiDigit = 53; break; // '5'
        case  6: asciiDigit = 54; break; // '6'
        case  7: asciiDigit = 55; break; // '7'
        case  8: asciiDigit = 56; break; // '8'
        case  9: asciiDigit = 57; break; // '9'
        case 10: asciiDigit = 48; break; // '0'
        case 11: asciiDigit = 42; break; // '*'
        case 12: asciiDigit = 35; break; // '#'
        case 13: asciiDigit = 65; break; // 'A'
        case 14: asciiDigit = 66; break; // 'B'
        case 15: asciiDigit = 67; break; // 'C'
        default:
            asciiDigit = 32; // Invalid DTMF code
            break;
        }

        return asciiDigit;
    }

    // we use one submitpdu to write a sms to uim,
    // but parse it from one android normal pdu?
    // we must convert it from one submitpdu to a normal android pdu
    // before it be putted into the list.
    public static byte[] convertSubmitpduToPdu(byte[] pdu) {
        byte[] mPdu;
        SmsEnvelope env = new SmsEnvelope();
        CdmaSmsAddress addr = new CdmaSmsAddress();
        byte[] data;
        byte count;
        int countInt;
        int addressDigitMode;
        
        // in
        ByteArrayInputStream bais = new ByteArrayInputStream(pdu);
        DataInputStream dis = new DataInputStream(bais);
        Log.d(LOG_TAG, "to get datas from submitpdu");
        // out
        try {
            env.teleService = dis.readInt();
            if (0 != dis.readInt()) { //p_cur->bIsServicePresent
                env.messageType = SmsEnvelope.MESSAGE_TYPE_BROADCAST;
            } else {
                if (SmsEnvelope.TELESERVICE_NOT_SET == env.teleService) {
                    // assume type ACK
                    env.messageType = SmsEnvelope.MESSAGE_TYPE_ACKNOWLEDGE;
                } else {
                    env.messageType = SmsEnvelope.MESSAGE_TYPE_POINT_TO_POINT;
                }
            }
            env.serviceCategory = dis.readInt();
            addressDigitMode = dis.read();
            addr.digitMode = (byte) (0xFF & addressDigitMode); //p_cur->sAddress.digit_mode
            addr.numberMode = dis.read();
            addr.ton = dis.read();
            addr.numberPlan = dis.read();
            count = (byte) dis.read();
            addr.numberOfDigits = count;
            data = new byte[count];
            //p_cur->sAddress.digits[digitCount]
            for (int index=0; index < count; index++) {
                data[index] = dis.readByte();

                // convert the value if it is 4-bit DTMF to 8 bit
                if (addressDigitMode == CdmaSmsAddress.DIGIT_MODE_4BIT_DTMF) {
                    data[index] = convertDtmfToAscii(data[index]);
                }
            }
            addr.origBytes = data;

            // ignore subaddress
            dis.read();
            dis.read();
            byte subaddr_nbr_of_digits = (byte) dis.read();
            for(int i=0; i < subaddr_nbr_of_digits; i++){
                dis.readByte(); //subaddr_orig_bytes[i]
            }

            /* currently not supported by the modem-lib:
                env.bearerReply
                env.replySeqNo
                env.errorClass
                env.causeCode
            */
            // bearer data
            countInt = dis.read();
            if (countInt >0) {
                data = new byte[countInt];
                 //p_cur->aBearerData[digitCount] :
                for (int index=0; index < countInt; index++) {
                    data[index] = dis.readByte();
                }
                env.bearerData = data;
            }
        } catch (IOException ioe) {
            Log.e(LOG_TAG, "convertSubmitpduToPdu: read from submitpdu failed: " + ioe);
        }
        Log.d(LOG_TAG, "get datas from submitpdu done! to write datas to a deliverpdu");
        ByteArrayOutputStream baos = new ByteArrayOutputStream(100);
        DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(baos));

        try {
            dos.writeInt(env.messageType);
            dos.writeInt(env.teleService);
            dos.writeInt(env.serviceCategory);

            dos.writeByte(addr.digitMode);
            dos.writeByte(addr.numberMode);
            dos.writeByte(addr.ton);
            dos.writeByte(addr.numberPlan);
            dos.writeByte(addr.numberOfDigits);
            dos.write(addr.origBytes, 0, addr.origBytes.length); // digits

            dos.writeInt(env.bearerReply);
            // CauseCode values:
            dos.writeByte(env.replySeqNo);
            dos.writeByte(env.errorClass);
            dos.writeByte(env.causeCode);
            //encoded BearerData:
            dos.writeInt(env.bearerData.length);
            dos.write(env.bearerData, 0, env.bearerData.length);
            dos.close();
            mPdu = baos.toByteArray();
            Log.d(LOG_TAG, "write datas to a deliverpdu done!");
            return mPdu;
        } catch (IOException ex) {
            Log.e(LOG_TAG, "convertSubmitpduToPdu: conversion from object to byte array failed: " + ex);
        }

        Log.e(LOG_TAG, "convertSubmitpduToPdu: will never reach here");
        return null;
    }

    /**
     * Generates an CDMA EF_SMS record from status and raw PDU.
     * Default for real raw pdu.
     *
     * @param status Message status. See TS 51.011 10.5.3.
     * @param pdu Raw message PDU.
     *
     * @return byte array for the record.
     */
    public static byte[] makeCDMASmsRecordData(int status, byte[] pdu) {
        // default isRealPdu true.
        return makeCDMASmsRecordData(status, pdu, true);
    }

    /**
     * Generates an CDMA EF_SMS record from status and raw PDU.
     * CDMA EF_SMS record`s length is different from the GSM one.
     *
     * @param status Message status. See TS 51.011 10.5.3.
     * @param pdu Raw message PDU.
     * @param isRealPdu The pdu is a real 3GPP2 raw hex pdu or not.
     * @return byte array for the record.
     */
    public static byte[] makeCDMASmsRecordData(int status, byte[] pdu, boolean isRealPdu) {
         if (isRealPdu) {
             Log.d(LOG_TAG, "isRealPdu is true, just piece it up to a record.");
         } else {
             Log.d(LOG_TAG, "call makeCDMASmsRecordData to convert a submitpdu to a deliverpdu, so parse process can run directly");
         }
         byte[] data = new byte[SMS_CDMA_RECORD_LENGTH];
 
         // Status bits for this record.  See TS 51.011 10.5.3
         data[0] = (byte)(status & 7);

         // the param pdu already be one uim sms record
         if (pdu.length >= SMS_CDMA_RECORD_LENGTH) {
             Log.d(LOG_TAG, "the param pdu already be one uim sms record, copy data directly");
             System.arraycopy(pdu, 1, data, 1, SMS_CDMA_RECORD_LENGTH - 1);
             return data;
         }

         byte[] newPdu = isRealPdu ? pdu : convertSubmitpduToPdu(pdu);

         // the param pdu already be one uim sms record pdu with length
         if (newPdu.length == (SMS_CDMA_RECORD_LENGTH-1)) {
             Log.d(LOG_TAG, "the param pdu already be one uim sms record pdu with length");
             System.arraycopy(pdu, 0, data, 1, SMS_CDMA_RECORD_LENGTH - 1);
             return data;
         }

         // pdu length
         data[1] = (byte)newPdu.length;
 
         System.arraycopy(newPdu, 0, data, 2, newPdu.length);
 
         // Pad out with 0xFF's.
         for (int j = newPdu.length+2; j < SMS_CDMA_RECORD_LENGTH; j++) {
             data[j] = -1;
         }
 
         return data;
    }

    public RuimSmsInterfaces() {
        // nothing to see here
    }

}
