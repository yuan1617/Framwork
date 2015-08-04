/*
 * Copyright (C) 2012 The Android Open Source Project
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

import com.android.internal.util.Protocol;

/**
 * @hide
 */
public class DctConstants {
    /**
     * IDLE: ready to start data connection setup, default state
     * CONNECTING: state of issued startPppd() but not finish yet
     * SCANNING: data connection fails with one apn but other apns are available
     *           ready to start data connection on other apns (before INITING)
     * CONNECTED: IP connection is setup
     * DISCONNECTING: Connection.disconnect() has been called, but PDP
     *                context is not yet deactivated
     * FAILED: data connection fail for all apns settings
     * RETRYING: data connection failed but we're going to retry.
     *
     * getDataConnectionState() maps State to DataState
     *      FAILED or IDLE : DISCONNECTED
     *      RETRYING or CONNECTING or SCANNING: CONNECTING
     *      CONNECTED : CONNECTED or DISCONNECTING
     */
    public enum State {
        IDLE,
        CONNECTING,
        SCANNING,
        CONNECTED,
        DISCONNECTING,
        FAILED,
        RETRYING
    }

    public enum Activity {
        NONE,
        DATAIN,
        DATAOUT,
        DATAINANDOUT,
        DORMANT
    }

    /***** Event Codes *****/
    public static final int BASE = Protocol.BASE_DATA_CONNECTION_TRACKER;
    public static final int EVENT_DATA_SETUP_COMPLETE = BASE + 0;
    public static final int EVENT_RADIO_AVAILABLE = BASE + 1;
    public static final int EVENT_RECORDS_LOADED = BASE + 2;
    public static final int EVENT_TRY_SETUP_DATA = BASE + 3;
    public static final int EVENT_DATA_STATE_CHANGED = BASE + 4;
    public static final int EVENT_POLL_PDP = BASE + 5;
    public static final int EVENT_RADIO_OFF_OR_NOT_AVAILABLE = BASE + 6;
    public static final int EVENT_VOICE_CALL_STARTED = BASE + 7;
    public static final int EVENT_VOICE_CALL_ENDED = BASE + 8;
    public static final int EVENT_DATA_CONNECTION_DETACHED = BASE + 9;
    public static final int EVENT_LINK_STATE_CHANGED = BASE + 10;
    public static final int EVENT_ROAMING_ON = BASE + 11;
    public static final int EVENT_ROAMING_OFF = BASE + 12;
    public static final int EVENT_ENABLE_NEW_APN = BASE + 13;
    public static final int EVENT_RESTORE_DEFAULT_APN = BASE + 14;
    public static final int EVENT_DISCONNECT_DONE = BASE + 15;
    public static final int EVENT_DATA_CONNECTION_ATTACHED = BASE + 16;
    public static final int EVENT_DATA_STALL_ALARM = BASE + 17;
    public static final int EVENT_DO_RECOVERY = BASE + 18;
    public static final int EVENT_APN_CHANGED = BASE + 19;
    public static final int EVENT_CDMA_DATA_DETACHED = BASE + 20;
    public static final int EVENT_CDMA_SUBSCRIPTION_SOURCE_CHANGED = BASE + 21;
    public static final int EVENT_PS_RESTRICT_ENABLED = BASE + 22;
    public static final int EVENT_PS_RESTRICT_DISABLED = BASE + 23;
    public static final int EVENT_CLEAN_UP_CONNECTION = BASE + 24;
    public static final int EVENT_CDMA_OTA_PROVISION = BASE + 25;
    public static final int EVENT_RESTART_RADIO = BASE + 26;
    public static final int EVENT_SET_INTERNAL_DATA_ENABLE = BASE + 27;
    public static final int EVENT_RESET_DONE = BASE + 28;
    public static final int EVENT_CLEAN_UP_ALL_CONNECTIONS = BASE + 29;
    public static final int CMD_SET_USER_DATA_ENABLE = BASE + 30;
    public static final int CMD_SET_DEPENDENCY_MET = BASE + 31;
    public static final int CMD_SET_POLICY_DATA_ENABLE = BASE + 32;
    public static final int EVENT_ICC_CHANGED = BASE + 33;
    public static final int EVENT_DISCONNECT_DC_RETRYING = BASE + 34;
    public static final int EVENT_DATA_SETUP_COMPLETE_ERROR = BASE + 35;
    public static final int CMD_SET_ENABLE_FAIL_FAST_MOBILE_DATA = BASE + 36;
    public static final int CMD_ENABLE_MOBILE_PROVISIONING = BASE + 37;
    public static final int CMD_IS_PROVISIONING_APN = BASE + 38;
    public static final int EVENT_PROVISIONING_APN_ALARM = BASE + 39;
    public static final int CMD_NET_STAT_POLL = BASE + 40;
    public static final int EVENT_DATA_RAT_CHANGED = BASE + 41;
    public static final int CMD_CLEAR_PROVISIONING_SPINNER = BASE + 42;

    /** M: start */
    public static final int MTK_BASE = BASE + 500;
    public static final int EVENT_DATA_ROAMING_CHANGED = MTK_BASE + 1;
    public static final int EVENT_INIT_EMERGENCY_APN_SETTINGS = MTK_BASE + 2;
    public static final int EVENT_APN_CHANGED_DONE = MTK_BASE + 3;
    public static final int EVENT_SETUP_DATA_WHEN_LOADED = MTK_BASE + 4;
    public static final int EVENT_VOICE_CALL_STARTED_PEER = MTK_BASE + 5;
    public static final int EVENT_VOICE_CALL_ENDED_PEER = MTK_BASE + 6;
    public static final int EVENT_FDN_CHANGED = MTK_BASE + 7;
    //VoLTE
    public static final int EVENT_ENABLE_DEDICATE_BEARER = MTK_BASE + 11;
    public static final int EVENT_DISABLE_DEDICATE_BEARER = MTK_BASE + 12;
    public static final int EVENT_MODIFY_DEDICATE_BEARER = MTK_BASE + 13;
    public static final int EVENT_DEDICATE_DATA_SETUP_COMPLETE = MTK_BASE + 14;
    public static final int EVENT_DEDICATE_DATA_DEACTIVATE_COMPLETE = MTK_BASE + 15;
    public static final int EVENT_DEDICATE_BEARER_MODIFIED_COMPLETE = MTK_BASE + 16;
    public static final int EVENT_DEDICATE_BEARER_ACTIVATED_BY_NETWORK = MTK_BASE + 17;
    public static final int EVENT_DEDICATE_BEARER_MODIFIED_BY_NETWORK = MTK_BASE + 18;
    public static final int EVENT_DEDICATE_BEARER_DEACTIVATED_BY_NETWORK = MTK_BASE + 19;
    public static final int EVENT_UPDATE_CONCATENATED_BEARER_COMPLETED = MTK_BASE + 20;
    public static final int EVENT_PCSCF_DISCOVERY = MTK_BASE + 21;
    public static final int EVENT_CLEAR_DATA_BEARER = MTK_BASE + 22;
    public static final int EVENT_ABORT_DEDICATE_BEARER_ENABLE = MTK_BASE + 23;
    public static final int EVENT_DEDICATE_BEARER_ABORT_COMPLETE = MTK_BASE + 24;
    /* M: end */
    // M: CC33 LTE.
    public static final int EVENT_REMOVE_RESTRICT_EUTRAN = MTK_BASE + 25;

    /***** Constants *****/

    public static final int APN_INVALID_ID = -1;
    public static final int APN_DEFAULT_ID = 0;
    public static final int APN_MMS_ID = 1;
    public static final int APN_SUPL_ID = 2;
    public static final int APN_DUN_ID = 3;
    public static final int APN_HIPRI_ID = 4;
    public static final int APN_IMS_ID = 5;
    public static final int APN_FOTA_ID = 6;
    public static final int APN_CBS_ID = 7;
    public static final int APN_IA_ID = 8;
    public static final int APN_EMERGENCY_ID = 9;

    /** M: start */
    public static final int APN_DM_ID = 10;
    public static final int APN_WAP_ID = 11;
    public static final int APN_NET_ID = 12;
    public static final int APN_CMMAIL_ID = 13;
    public static final int APN_RCSE_ID = 14;
    /** M: end */

    public static final int APN_NUM_TYPES = APN_RCSE_ID + 1;

    public static final int DISABLED = 0;
    public static final int ENABLED = 1;

    public static final String APN_TYPE_KEY = "apnType";
    public static final String PROVISIONING_URL_KEY = "provisioningUrl";
}
