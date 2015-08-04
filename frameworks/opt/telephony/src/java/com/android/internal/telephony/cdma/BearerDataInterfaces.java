//package com.android.internal.telephony.cdma.viatelecom;
package com.android.internal.telephony.cdma;

import static android.telephony.SmsMessage.ENCODING_16BIT;
import static android.telephony.SmsMessage.MAX_USER_DATA_BYTES;
import static android.telephony.SmsMessage.MAX_USER_DATA_BYTES_WITH_HEADER;

import android.util.Log;

import android.telephony.SmsMessage;

import android.text.format.Time;

import com.android.internal.telephony.IccUtils;
import com.android.internal.telephony.GsmAlphabet;
import com.android.internal.telephony.SmsHeader;
import com.android.internal.telephony.GsmAlphabet.TextEncodingDetails;

import com.android.internal.util.BitwiseInputStream;
import com.android.internal.util.BitwiseOutputStream;

import android.content.res.Resources;

import java.util.TimeZone;
import com.android.internal.telephony.cdma.sms.BearerData;
import com.android.internal.telephony.cdma.sms.BearerData.CodingException;
import com.android.internal.telephony.cdma.sms.BearerData.Gsm7bitCodingResult;
import com.android.internal.telephony.cdma.sms.UserData;

public final class BearerDataInterfaces {
    private final static String LOG_TAG = "BearerDataInterfaces 1.0";

    /**
     * Character to use when forced to encode otherwise unencodable
     * characters, meaning those not in the respective ASCII or GSM
     * 7-bit encoding tables.  Current choice is SPACE, which is 0x20
     * in both the GSM-7bit and ASCII-7bit encodings.
     * @link(Userdata.UNENCODABLE_7_BIT_CHAR)
     */
    private final static byte UNENCODABLE_7_BIT_CHAR = 0x20;

    /**
     * For a userdata header data, this object describes protocol details of
     * encoding it to PDU.
     */
    public static class UserDataHeaderDetails {
        // header data length, dont include the length indicater
        public int headerDataLength;
        // whole length include the length indicater
        public int wholeLength;
        // need how many encoding fields
        public int headerDataFields;
        // need skip how many bits after fill the header data to pdu
        public int skipBits;

        public UserDataHeaderDetails() {
            headerDataLength = 0;
            wholeLength = 0;
            headerDataFields = 0;
            skipBits = 0;
        }

        public UserDataHeaderDetails(int dataLen, int wholelen, int fields, int skip) {
            headerDataLength = dataLen;
            wholeLength = wholelen;
            headerDataFields = fields;
            skipBits = skip;
        }

        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append("UserDataHeaderDetails ");
            builder.append("{ headerDataLength = " + headerDataLength);
            builder.append(", wholeLength = " + wholeLength);
            builder.append(", numFields = " + headerDataFields);
            builder.append(", skipBits = " + skipBits);
            builder.append(" }");
            return builder.toString();
        }
    }

    /**
     * Calculate the message text encoding length, fragmentation, and other details.
     *
     * @param msg message text
     * @param force7BitEncoding ignore (but still count) illegal characters if true
     * @return septet count, or -1 on failure
     */
    public static TextEncodingDetails calcTextEncodingDetails(CharSequence msg,
            boolean force7BitEncoding) {
        TextEncodingDetails ted;
        int septets = BearerData.countAsciiSeptets(msg, force7BitEncoding);
        Log.d(LOG_TAG, "msg = " + msg);
        Log.d(LOG_TAG, "force7BitEncoding = " + force7BitEncoding);
        Log.d(LOG_TAG, "calcTextEncodingDetails : result = " + septets);
        if (septets != -1 && septets <= SmsMessage.MAX_USER_DATA_SEPTETS) {
            ted = new TextEncodingDetails();
            ted.msgCount = 1;
            ted.codeUnitCount = septets;
            ted.codeUnitsRemaining = SmsMessage.MAX_USER_DATA_SEPTETS - septets;
            ted.codeUnitSize = SmsMessage.ENCODING_7BIT;
        } else if (septets != -1 && septets > SmsMessage.MAX_USER_DATA_SEPTETS) {
            // 7bits long messages
            Log.d(LOG_TAG, "septets > 160 , it is a long 7bit messages ");
            ted = com.android.internal.telephony.gsm.SmsMessage.calculateLength(
                    msg, force7BitEncoding);
        } else {
            // If try 7bits encoding failed, to encode text with unicode.
            // Refer to HREF#19245 diary text and fix details in SYNERGY/Change.
            Log.d(LOG_TAG, "encode text with unicode");
            ted = new TextEncodingDetails();
            ted.codeUnitCount = msg.length();
            int octets = ted.codeUnitCount * 2;
            if (octets > MAX_USER_DATA_BYTES) {
                ted.msgCount = (octets + (MAX_USER_DATA_BYTES_WITH_HEADER - 1)) /
                       MAX_USER_DATA_BYTES_WITH_HEADER;
                ted.codeUnitsRemaining = ((ted.msgCount *
                       MAX_USER_DATA_BYTES_WITH_HEADER) - octets) / 2;
            } else {
                ted.msgCount = 1;
                ted.codeUnitsRemaining = (MAX_USER_DATA_BYTES - octets)/2;
            }
            ted.codeUnitSize = ENCODING_16BIT;
        }
        return ted;
    }

    /**
     * Return the userdata header length on bytes, dont include the length indicater.
     *
     * @param header message userdata header, can be null
     * @return userdata header length by bytes, or 0 on empty header data
     */
    public static int getUserdataHeaderDataNumBytes(SmsHeader header) {
        if (header == null) {
            return 0;
        }

        byte[] headerBytes = SmsHeader.toByteArray(header);
        if (headerBytes != null) {
            return headerBytes.length;
        }

        Log.e(LOG_TAG, "error: SmsHeader != null, but we got an invalid bytesArray by SmsHeader.toByteArray");
        return 0;
    }

    /**
     * Calculate the userdata header number fields, numFields will be based on the userdata header byte length,
     * and be related with the userdata text encoding type.
     *
     * For example, the header data contain 5 bytes data, and plus the length indicater which takes 1 byte,
     * the whole userdata header will hold 6 bytes, that is 48 bits (6*8). Then if the userdata text encoding type is
     * 7-bit, we must use 7 fields to fill the whole userdata header, not 6 fields,
     * because 6 fields = 6*7(7bit encoding type ) = 42bits, 42 < 48, it is not enough obviously.
     *
     *
     * @param header message userdata header, can be null
     * @param encodingType the userdata encoding type
     * @return fields count by userdata header holding, and the next skiped bits number,
     *         included in the UserDataHeaderDetails or null on empty header data
     */
    public static UserDataHeaderDetails calcUserdataHeaderDetails(SmsHeader header, int encodingType) {
        if (header == null) {
            // no header, do noting
            return null;
        }

        int lengthOfHeader = 0;
        byte[] headerBytes = SmsHeader.toByteArray(header);
        if (headerBytes != null) {
            lengthOfHeader = headerBytes.length;
        }

        if (lengthOfHeader <= 0) {
            Log.e(LOG_TAG, "error: SmsHeader != null, but we got length = " + lengthOfHeader);
            return null;
        }

        // the length indicater takes 1 byte, plus it
        int wholeLength = lengthOfHeader + 1;
        int allbits = wholeLength * 8;
        int fields = 0;
        int numFields = 0;
        int skipBits = 0;

        switch (encodingType) {
            case UserData.ENCODING_OCTET:
                numFields = wholeLength;
                skipBits = 0;
                break;

            case UserData.ENCODING_7BIT_ASCII:
                fields = allbits / 7;
                numFields = ((allbits % 7) != 0) ? (fields + 1) : fields;
                skipBits = numFields * 7 - allbits;
                break;

            case UserData.ENCODING_UNICODE_16:
                fields = allbits / 16;
                numFields = ((allbits % 16) != 0) ? (fields + 1) : fields;
                skipBits = numFields * 16 - allbits;
                break;

            case UserData.ENCODING_IS91_EXTENDED_PROTOCOL:
            case UserData.ENCODING_IA5:
            case UserData.ENCODING_SHIFT_JIS:
            case UserData.ENCODING_KOREAN:
            case UserData.ENCODING_LATIN_HEBREW:
            case UserData.ENCODING_LATIN:
            case UserData.ENCODING_GSM_7BIT_ALPHABET:
            case UserData.ENCODING_GSM_DCS:
            default:
                Log.e(LOG_TAG, "error: not supported msg encoding type = " + encodingType);
        }

        return new UserDataHeaderDetails(lengthOfHeader, wholeLength, numFields, skipBits);

    }

    /*
     * encode userdata header to bit out stream for SMS pdu
     *
     * @param header message userdata header, can be null
     * @param udhdetails the userdata header protocol details
     * @param outstream where encode to
     * @return void
     */
    private static void encodeUserdataHeader(SmsHeader header, UserDataHeaderDetails udhdetails, BitwiseOutputStream outstream)
        throws BitwiseOutputStream.AccessException
    {
        if (outstream == null) {
            Log.e(LOG_TAG, "outstream is null, do noting");
            return;
        }

        try {
            //encoding sms header if sms has
            if (header != null) {
                Log.d(LOG_TAG, header.toString());
                if (udhdetails != null) {
                    Log.d(LOG_TAG, udhdetails.toString());
                    outstream.write(8, udhdetails.headerDataLength);
                    outstream.writeByteArray(udhdetails.headerDataLength * 8, SmsHeader.toByteArray(header));
                    //remember to skip bits
                    if (udhdetails.skipBits > 0) {
                        outstream.skip(udhdetails.skipBits);
                    }
                } else {
                    Log.e(LOG_TAG, "error: SmsHeader != null, but we got null udhDetails");
                }
            }
        } catch (BitwiseOutputStream.AccessException ex) {
            throw new BitwiseOutputStream.AccessException("userdata header encode failed: " + ex);
        }
    }

    public static byte[] encode7bitAscii(String msg, boolean force, SmsHeader header)
        throws CodingException
    {
        try {
            int length = msg.length();
            UserDataHeaderDetails udhDetails = calcUserdataHeaderDetails(header, UserData.ENCODING_7BIT_ASCII);
            // have UDH, plus the length
            if (udhDetails != null) {
                length = length + udhDetails.wholeLength;
            }

            Log.d(LOG_TAG, "encode7bitAscii: length = " + length + " and header = " + header);
            BitwiseOutputStream outStream = new BitwiseOutputStream(length);
            int msgLen = msg.length();

            //encoding sms header if sms has
            if (header != null) {
                encodeUserdataHeader(header, udhDetails, outStream);
            }

            //encoding msg content
            for (int i = 0; i < msgLen; i++) {
                int charCode = UserData.charToAscii.get(msg.charAt(i), -1);
                if (charCode == -1) {
                    if (force) {
                        outStream.write(7, UNENCODABLE_7_BIT_CHAR);
                    } else {
                        Log.d(LOG_TAG, "force = " + force + ", cannot ASCII encode (" + msg.charAt(i) + ")");
                        throw new CodingException("cannot ASCII encode (" + msg.charAt(i) + ")");
                    }
                } else {
                    outStream.write(7, charCode);
                }
            }
            return outStream.toByteArray();
        } catch (BitwiseOutputStream.AccessException ex) {
            throw new CodingException("7bit ASCII encode failed: " + ex);
        }
    }

    public static byte[] encodeUtf16(String msg, SmsHeader header)
        throws CodingException
    {
        try {
            int length = msg.length() * 2;
            UserDataHeaderDetails udhDetails = calcUserdataHeaderDetails(header, UserData.ENCODING_UNICODE_16);
            // have UDH, plus the length
            if (udhDetails != null) {
                length = length + udhDetails.wholeLength;
            }

            Log.d(LOG_TAG, "encodeUtf16: length = " + length + " and header = " + header);
            BitwiseOutputStream outStream = new BitwiseOutputStream(length);

            //encoding sms header if sms has
            if (header != null) {
                encodeUserdataHeader(header, udhDetails, outStream);
            }

            int bits = msg.length() * 16;
            outStream.writeByteArray(bits, msg.getBytes("utf-16be"));
            return outStream.toByteArray();
        } catch (java.io.UnsupportedEncodingException ex) {
            throw new CodingException("UTF-16 encode failed: " + ex);
        } catch (BitwiseOutputStream.AccessException ex) {
            throw new CodingException("UTF-16 encode failed: " + ex);
        }
    }

    public static byte[] encodeOctet(byte[] data, SmsHeader header)
        throws CodingException
    {
        try {
            int length = data.length;
            UserDataHeaderDetails udhDetails = calcUserdataHeaderDetails(header, UserData.ENCODING_OCTET);
            // have UDH, plus the length
            if (udhDetails != null) {
                length = length + udhDetails.wholeLength;
            }

            Log.d(LOG_TAG, "encodeOctet: length = " + length + " and header = " + header);
            // whole length must under the userdata max length limit
            if (length > SmsMessage.MAX_USER_DATA_BYTES) {
                throw new RuntimeException("data length exceed the max " + SmsMessage.MAX_USER_DATA_BYTES);
            }

            BitwiseOutputStream outStream = new BitwiseOutputStream(length);

            //encoding sms header if sms has
            if (header != null) {
                encodeUserdataHeader(header, udhDetails, outStream);
            }

            int bits = data.length * 8;
            outStream.writeByteArray(bits, data);
            return outStream.toByteArray();
        } catch (RuntimeException ex) {
            throw new CodingException("Octet encode failed: " + ex);
        } catch (BitwiseOutputStream.AccessException ex) {
            throw new CodingException("Octet encode failed: " + ex);
        }
    }

    public static void encodeTimeStamp(BearerData bData, BitwiseOutputStream outStream)
        throws BitwiseOutputStream.AccessException
    {
        outStream.write(8, 6);
        int year = (bData.msgCenterTimeStamp.year >= 2000) ? 
                    (bData.msgCenterTimeStamp.year - 2000) : (bData.msgCenterTimeStamp.year - 1900);
        outStream.write(8, cdmaIntToBcdByte(year));
        outStream.write(8, cdmaIntToBcdByte(bData.msgCenterTimeStamp.month + 1));
        outStream.write(8, cdmaIntToBcdByte(bData.msgCenterTimeStamp.monthDay));
        outStream.write(8, cdmaIntToBcdByte(bData.msgCenterTimeStamp.hour));
        outStream.write(8, cdmaIntToBcdByte(bData.msgCenterTimeStamp.minute));
        outStream.write(8, cdmaIntToBcdByte(bData.msgCenterTimeStamp.second));
    }

    public static byte
    cdmaIntToBcdByte(int i) {
        byte ret = 0;
        
        i = i % 100;// treat out-of-range values as 0

        ret = (byte)(((i / 10) << 4) | (i % 10));

        return ret;
    }

    /**
     * Decode userdata text encoded in 7-bit ascii.
     *
     *
     * @param byte[] data the whole userdata data in bytes of PDU,
     *                    notice that it includes the userdata header if the message have.
     * @param offset how many bytes be holded by userdata header, 0 if have no header.
     * @param numFields how many fields be holded by the whole userdata.
     * @return decoded string text of the message, the message body.
     */
    public static String decode7bitAscii(byte[] data, int offset, int numFields)
        throws CodingException
    {
        try {
            int headerDataBytesLen = offset;
            int headerHoldBitsNum = headerDataBytesLen * 8;
            StringBuffer strBuf = new StringBuffer(numFields);
            BitwiseInputStream inStream = new BitwiseInputStream(data);
            int wantedBits = (numFields * 7);
            if (inStream.available() < wantedBits) {
                throw new CodingException("insufficient data (wanted " + wantedBits +
                                          " bits, but only have " + inStream.available() + ")");
            }

            // if headerHoldBitsNum == 0, have no userdata header, do noting
            // else the message have an userdata header
            if (headerHoldBitsNum > 0) {
                // skip userdata header
                inStream.skip(headerHoldBitsNum);

                int headerHoldFields = 0;
                int needSkipBits = 0;

                // check whether the userdata header data filled an integral multiple 7-bit fields,
                // if not, must skip some reserved bits to fit a protocl septet.
                // see the example in the description of @calcUserdataHeaderDetails()@
                if ((headerHoldBitsNum % 7) != 0) {
                    headerHoldFields = (headerHoldBitsNum / 7) + 1;
                    needSkipBits = 7 - (headerHoldBitsNum % 7);
                } else {
                    headerHoldFields = (headerHoldBitsNum / 7);
                    // the userdata filled the fields just right, no need to skip any bits
                    needSkipBits = 0;
                }
                // skip reserved bits, if needSkipBits = 0, will skip noting
                inStream.skip(needSkipBits);
                // fire off the fields count holded by userdata header.
                numFields -= headerHoldFields;
            }

            for (int i = 0; i < numFields; i++) {
                int charCode = inStream.read(7);
                if ((charCode >= UserData.ASCII_MAP_BASE_INDEX) &&
                        (charCode <= UserData.ASCII_MAP_MAX_INDEX)) {
                    strBuf.append(UserData.ASCII_MAP[charCode - UserData.ASCII_MAP_BASE_INDEX]);
                } else if (charCode == UserData.ASCII_NL_INDEX) {
                    strBuf.append('\n');
                } else if (charCode == UserData.ASCII_CR_INDEX) {
                    strBuf.append('\r');
                } else {
                    /* For other charCodes, they are unprintable, and so simply use SPACE. */
                    strBuf.append(' ');
                }
            }
            return strBuf.toString();
        } catch (BitwiseInputStream.AccessException ex) {
            throw new CodingException("7bit ASCII decode failed: " + ex);
        }
    }

    /**
     * Decode userdata text encoded in 16-bit unicode.
     *
     *
     * @param byte[] data the whole userdata data in bytes of PDU,
     *                    notice that it includes the userdata header if the message have.
     * @param offset how many bytes be holded by userdata header, 0 if have no header.
     * @param numFields how many fields be holded by the whole userdata.
     * @return decoded string text of the message, the message body.
     */
    public static String decodeUtf16(byte[] data, int offset, int numFields)
        throws CodingException
    {
        int headerDataBytesLen = offset;
        int allHeaderDataBits = headerDataBytesLen * 8;
        int fields = allHeaderDataBits / 16;
        int headerDataFields = ((allHeaderDataBits % 16) != 0) ? (fields + 1) : fields;

        // fire off the fields count holded by userdata header
        numFields -= headerDataFields;
        int byteCount = numFields*2;

        Log.d(LOG_TAG, "decodeUtf16, offset = " + offset + ", numFields = "
                        + numFields + ", data.length = " + data.length);

        if (byteCount < 0 || (byteCount + offset) > data.length) {
            throw new CodingException("UTF-16 decode failed: offset or length out of range");
        }
        try {
            return new String(data, offset, byteCount, "utf-16be");
        } catch (java.io.UnsupportedEncodingException ex) {
            throw new CodingException("UTF-16 decode failed: " + ex);
        }
    }

    public static void encodeUserDataPayload(UserData uData)
        throws CodingException
    {
        if ((uData.payloadStr == null) && (uData.msgEncoding != UserData.ENCODING_OCTET)) {
            Log.e(LOG_TAG, "user data with null payloadStr");
            uData.payloadStr = "";
        }
        Log.d(LOG_TAG, "uData.msgEncodingSet = " + uData.msgEncodingSet);
        if (uData.msgEncodingSet) {
            Log.d(LOG_TAG, "uData.msgEncoding = " + uData.msgEncoding);
            if (uData.msgEncoding == UserData.ENCODING_OCTET) {
                // TODO: NOTICE Here, google default design - when set octet decoding,
                // TODO: will pay attention on payload byte data only, not the message string text.
                // TODO: will just encoding the payload byte data param in userdata object.
                if (uData.payload == null) {
                    Log.e(LOG_TAG, "user data with octet encoding but null payload");
                    uData.payload = new byte[0];
                }

                uData.payload = encodeOctet(uData.payload, uData.userDataHeader);
                uData.numFields = uData.payload.length;
                // plus the fields number if have userdataheader
                if (uData.userDataHeader != null) {
                    UserDataHeaderDetails udhDetails
                                    = calcUserdataHeaderDetails(uData.userDataHeader, UserData.ENCODING_OCTET);
                    if (udhDetails != null) {
                        uData.numFields += udhDetails.headerDataFields;
                    }
                }
            } else {
                if (uData.payloadStr == null) {
                    Log.e(LOG_TAG, "non-octet user data with null payloadStr");
                    uData.payloadStr = "";
                }
                if (uData.msgEncoding == UserData.ENCODING_GSM_7BIT_ALPHABET) {
                    /*Gsm7bitCodingResult gcr = BearerData.encode7bitGsm(uData.payloadStr, 0, true);
                    uData.payload = gcr.data;
                    uData.numFields = gcr.septets;*/
                    Log.e(LOG_TAG, "GSM_7BIT_ALPHABET encoding type dont work fine for CDMA in China,"
                                   + " because this type message will be blocked by message center"
                                   + " when it is transfered from CT(China Telecom) to CM(China Mobile)");
                    throw new CodingException("GSM_7BIT_ALPHABET encoding type dont work fine for CDMA in China,"
                                              + " dont use this encoding type.");
                } else if (uData.msgEncoding == UserData.ENCODING_7BIT_ASCII) {
                    uData.payload = encode7bitAscii(uData.payloadStr, true, uData.userDataHeader);
                    uData.numFields = uData.payloadStr.length();
                    // plus the fields number if have userdataheader
                    if (uData.userDataHeader != null) {
                        UserDataHeaderDetails udhDetails
                                        = calcUserdataHeaderDetails(uData.userDataHeader, UserData.ENCODING_7BIT_ASCII);
                        if (udhDetails != null) {
                            uData.numFields += udhDetails.headerDataFields;
                        }
                    }
                } else if (uData.msgEncoding == UserData.ENCODING_UNICODE_16) {
                    uData.payload = encodeUtf16(uData.payloadStr, uData.userDataHeader);
                    uData.numFields = uData.payloadStr.length();
                    // plus the fields number if have userdataheader
                    if (uData.userDataHeader != null) {
                        UserDataHeaderDetails udhDetails
                                        = calcUserdataHeaderDetails(uData.userDataHeader, UserData.ENCODING_UNICODE_16);
                        if (udhDetails != null) {
                            uData.numFields += udhDetails.headerDataFields;
                        }
                    }
                } else {
                    throw new CodingException("unsupported user data encoding (" +
                                              uData.msgEncoding + ")");
                }
            }
        } else {
            int headerFieldsNum = 0;

            try {
                Log.d(LOG_TAG, "not set encodetype, so try encode as 7BIT ASCII");
                uData.payload = encode7bitAscii(uData.payloadStr, false, uData.userDataHeader);
                uData.msgEncoding = UserData.ENCODING_7BIT_ASCII;
                // plus the fields number if have userdataheader
                if (uData.userDataHeader != null) {
                    UserDataHeaderDetails udhDetails
                                    = calcUserdataHeaderDetails(uData.userDataHeader, UserData.ENCODING_7BIT_ASCII);
                    if (udhDetails != null) {
                        headerFieldsNum = udhDetails.headerDataFields;
                    }
                }
            } catch (CodingException ex) {
                // TODO: here should add 8bit encoding try work?
                // TODO: NOTICE the @SmsManager.divideMessage()@ and @Smsmessage.calculateLength()@also,
                // TODO: because the APP will use those interfaces to show the current message count, and divide the message text before sending them,
                // TODO: and CDMA and GSM share those interfaces for APP, it is a big trouble for G+C double talk device,
                // TODO: because when we show the message count in UI, we dont known whether we should
                // TODO: call the CDMA calculateLength or GSM one until the user down the send button by slot id.
                // TODO: So, in double talk project, we must keep them be same.
                Log.d(LOG_TAG, "try encode as 7BIT ASCII failed, to encode by Utf16");
                uData.payload = encodeUtf16(uData.payloadStr, uData.userDataHeader);
                uData.msgEncoding = UserData.ENCODING_UNICODE_16;
                // plus the fields number if have userdataheader
                if (uData.userDataHeader != null) {
                    UserDataHeaderDetails udhDetails
                                    = calcUserdataHeaderDetails(uData.userDataHeader, UserData.ENCODING_UNICODE_16);
                    if (udhDetails != null) {
                        headerFieldsNum = udhDetails.headerDataFields;
                    }
                }
            }
            uData.numFields = uData.payloadStr.length() + headerFieldsNum;
            Log.d(LOG_TAG, "encode sucess, numFields = " + uData.numFields);
            uData.msgEncodingSet = true;
        }

    }
}
