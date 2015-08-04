package com.mediatek.internal.telephony.dataconnection;

import com.android.internal.util.AsyncChannel;
import android.os.Message;
import android.util.Log;
import com.android.internal.telephony.dataconnection.DcAsyncChannel;

import com.mediatek.internal.telephony.DedicateBearerProperties;

public class DedicateDataConnectionAc extends AsyncChannel {
    private static final String TAG = "GSM";

    public static final int REQ_SET_REASON = 500520;
    public static final int RSP_SET_REASON = 500521;
    public static final int REQ_GET_REASON = 500522;
    public static final int RSP_GET_REASON = 500523;
    public static final int REQ_IS_ACTIVATING = 500524;
    public static final int RSP_IS_ACTIVATING = 500525;

    private int mId;
    private DedicateDataConnection mDedicateDataConnection;

    public DedicateDataConnectionAc(DedicateDataConnection dedicateDataConnection) {
        mId = dedicateDataConnection.getId();
        mDedicateDataConnection = dedicateDataConnection;
        mDedicateDataConnection.setDedicateDataConnectionAc(this);
    }

    public boolean isInactiveSync() {
        Message response = sendMessageSynchronously(DcAsyncChannel.REQ_IS_INACTIVE);
        if ((response != null) && (response.what == DcAsyncChannel.RSP_IS_INACTIVE)) {
            return (response.arg1 == 1);
        } else {
            log("isInactiveSync error response=" + response);
            return false;
        }
    }

    public boolean isActiveSync() {
        Message response = sendMessageSynchronously(DcAsyncChannel.REQ_IS_ACTIVE);
        if ((response != null) && (response.what == DcAsyncChannel.RSP_IS_ACTIVE)) {
            return (response.arg1 == 1);
        } else {
            log("isActiveSync error response=" + response);
            return false;
        }
    }

    public boolean isActivatingSync() {
        Message response = sendMessageSynchronously(REQ_IS_ACTIVATING);
        if ((response != null) && (response.what == RSP_IS_ACTIVATING)) {
            return (response.arg1 == 1);
        } else {
            log("isActivatingSync error response=" + response);
            return false;
        }
    }

    public DedicateBearerProperties getBearerPropertiesSync() {
        Message response = sendMessageSynchronously(DcAsyncChannel.REQ_GET_LINK_PROPERTIES);
        if ((response != null) && (response.what == DcAsyncChannel.RSP_GET_LINK_PROPERTIES)) {
            return response.obj == null ? null : (DedicateBearerProperties)response.obj;
        } else {
            log("getBearerPropertiesSync error response=" + response);
            return null;
        }
    }

    public int getCidSync() {
        Message response = sendMessageSynchronously(DcAsyncChannel.REQ_GET_CID);
        if ((response != null) && (response.what == DcAsyncChannel.RSP_GET_CID)) {
            return response.arg1;
        } else {
            log("getCidSync error response=" + response);
            return -1;
        }
    }

    public String setReasonSync(String reason) {
        Message response = sendMessageSynchronously(REQ_SET_REASON, reason);
        if ((response != null) && (response.what == RSP_SET_REASON)) {
            return (String)response.obj;
        } else {
            log("setReasonSync error response=" + response);
            return null;
        }
    }

    public String getReasonSync() {
        Message response = sendMessageSynchronously(REQ_GET_REASON);
        if ((response != null) && (response.what == RSP_GET_REASON)) {
            return response.obj == null ? null : (String)response.obj;
        } else {
            log("setReasonSync error response=" + response);
            return null;
        }
    }

    private void log(String text) {
        Log.d(TAG, "[dedicate][GDDC-" + mId + "] " + text);
    }
}
