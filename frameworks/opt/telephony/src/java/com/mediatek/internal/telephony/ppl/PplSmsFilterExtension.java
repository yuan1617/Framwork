package com.mediatek.internal.telephony.ppl;

import java.util.List;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.telephony.PhoneNumberUtils;
import android.telephony.SmsMessage;
import android.telephony.SubscriptionManager;
import android.util.Log;

import com.mediatek.common.PluginImpl;

/**
 *@hide
 */
@PluginImpl(interfaceName="com.mediatek.internal.telephony.ppl.IPplSmsFilter")
public class PplSmsFilterExtension extends ContextWrapper implements IPplSmsFilter {
    private static final String TAG = "PPL/PplSmsFilterExtension";

    public static final String INTENT_REMOTE_INSTRUCTION_RECEIVED = "com.mediatek.ppl.REMOTE_INSTRUCTION_RECEIVED";
    public static final String INSTRUCTION_KEY_TYPE = "Type";
    public static final String INSTRUCTION_KEY_FROM = "From";
    public static final String INSTRUCTION_KEY_TO = "To";
    public static final String INSTRUCTION_KEY_SIM_ID = "SimId";

    private final IPplAgent mAgent;
    private final PplMessageManager mMessageManager;
    private final boolean mEnabled;

    public PplSmsFilterExtension(Context context) {
        super(context);
        Log.d(TAG, "PplSmsFilterExtension enter");
        if (!"1".equals(SystemProperties.get("ro.mtk_privacy_protection_lock"))) {
            mAgent = null;
            mMessageManager = null;
            mEnabled = false;
            return;
        }

        IBinder binder = ServiceManager.getService("PPLAgent");
        if (binder == null) {
            Log.e(TAG, "Failed to get PPLAgent");
            mAgent = null;
            mMessageManager = null;
            mEnabled = false;
            return;
        }

        mAgent = IPplAgent.Stub.asInterface(binder);
        if (mAgent == null) {
            Log.e(TAG, "mAgent is null!");
            mMessageManager = null;
            mEnabled = false;
            return;
        }

        mMessageManager = new PplMessageManager(context);
        mEnabled = true;
        Log.d(TAG, "PplSmsFilterExtension exit");
    }

    @Override
    public boolean pplFilter(Bundle params) {
        Log.d(TAG, "pplFilter(" + params + ")");
        if (!mEnabled) {
            Log.d(TAG, "pplFilter returns false: feature not enabled");
            return false;
        }

        String format = params.getString(KEY_FORMAT);
        boolean isMO = (params.getInt(KEY_SMS_TYPE) == 1);

        long subId = params.getLong(KEY_SUB_ID);
        int simId = SubscriptionManager.getSlotId(subId);
        Log.d(TAG, "subId = " + subId + ". simId = " + simId);

        Object[] messages = (Object[]) params.getSerializable(KEY_PDUS);
        String dst = null;
        String src = null;
        String content = null;;

        if (messages == null) {
            content = params.getString(KEY_MSG_CONTENT);
            src = params.getString(KEY_SRC_ADDR);
            dst = params.getString(KEY_DST_ADDR);
            Log.d(TAG, "pplFilter: Read msg directly and content is " + content);
        } else {
            byte[][] pdus = new byte[messages.length][];
            for (int i = 0; i < messages.length; i++) {
                pdus[i] = (byte[]) messages[i];
            }
            int pduCount = pdus.length;
            SmsMessage[] msgs = new SmsMessage[pduCount];
            for (int i = 0; i < pduCount; i++) {
                msgs[i] = SmsMessage.createFromPdu(pdus[i], format);
            }

            Log.d(TAG, "pplFilter: pdus is " + pdus + " with length " + pdus.length);
            Log.d(TAG, "pplFilter: pdus[0] is " + pdus[0]);
                    content = msgs[0].getMessageBody();
            Log.d(TAG, "pplFilter: message content is " + content);

            src = msgs[0].getOriginatingAddress();
            dst = msgs[0].getDestinationAddress();
        }

        if (content == null) {
            Log.d(TAG, "pplFilter returns false: content is null");
            return false;
        }
        
        PplControlData controlData = null;
        try {
            controlData = PplControlData.buildControlData(mAgent.readControlData());
        } catch (RemoteException e) {
            e.printStackTrace();
            Log.d(TAG, "pplFilter returns false: failed to build control data");
            return false;
        }

        if (controlData == null || !controlData.isEnabled()) {
            Log.d(TAG, "pplFilter returns false: control data is null or ppl is not enabled");
            return false;
        }

        if (isMO) {
            Log.d(TAG, "pplFilter: dst is " + dst);
            if (!matchNumber(dst, controlData.TrustedNumberList)) {
                Log.d(TAG, "pplFilter returns false: MO number does not match");
                return false;
            }
        } else {
            Log.d(TAG, "pplFilter: src is " + src);
            if (!matchNumber(src, controlData.TrustedNumberList)) {
                Log.d(TAG, "pplFilter returns false: MT number does not match");
                return false;
            }
        }

        byte instruction = mMessageManager.getMessageType(content);
        if (instruction == PplMessageManager.Type.INVALID) {
            Log.d(TAG, "pplFilter returns false: message is not matched");
            return false;
        }

        Intent intent = new Intent(INTENT_REMOTE_INSTRUCTION_RECEIVED);
        intent.setClassName("com.mediatek.ppl", "com.mediatek.ppl.PplService");
        intent.putExtra(INSTRUCTION_KEY_TYPE, instruction);
        intent.putExtra(INSTRUCTION_KEY_SIM_ID, simId);

        if (isMO) {
            intent.putExtra(INSTRUCTION_KEY_TO, dst);
        } else {
            intent.putExtra(INSTRUCTION_KEY_FROM, src);
        }
        Log.d(TAG, "start PPL Service");
        startService(intent);

        return true;
    }

    private boolean matchNumber(String number, List<String> numbers) {
        if (number != null && numbers != null) {
            for (String s : numbers) {
                if (PhoneNumberUtils.compare(s, number)) {
                    return true;
                }
            }
        }
        return false;
    }

}
