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

package com.android.internal.telephony.cat;

/**
 * Interface for communication between STK App and CAT Telephony
 *
 * {@hide}
 */
public interface AppInterface {

    /*
     * Intent's actions which are broadcasted by the Telephony once a new CAT
     * proactive command, session end arrive.
     */
    public static final String CAT_CMD_ACTION =
                                    "android.intent.action.stk.command";
    public static final String CAT_SESSION_END_ACTION =
                                    "android.intent.action.stk.session_end";

    // This is used to send refresh-result when MSG_ID_ICC_REFRESH is received.
    public static final String REFRESH_RESULT = "refresh_result";
    //This is used to send card status from card to STK App.
    public static final String CARD_STATUS = "card_status";
    //Intent's actions are broadcasted by Telephony once IccRefresh occurs.
    public static final String CAT_ICC_STATUS_CHANGE =
                                    "android.intent.action.stk.icc_status_change";
    public static final String CLEAR_DISPLAY_TEXT_CMD =
            "android.intent.action.stk.clear_display_text";

    /*
     * Callback function from app to telephony to pass a result code and user's
     * input back to the ICC.
     */
    void onCmdResponse(CatResponseMessage resMsg);

    /**
     * Callback function from app to telephony to pass a envelope to the SIM.
     * @internal
     */
    void onEventDownload(CatResponseMessage resMsg);

    /**
     * Callback function from app to telephony to handle DB.
     * @internal
     */
    void onDBHandler(int sim_id);
    /**
     * Function to notify CatService that all calls are disonncected.
     * @internal
     */
    void setAllCallDisConn(boolean isDisConn);
    /**
     * Function to check if call disconnected event is received from modem.
     * @internal
     */
    boolean isCallDisConnReceived();

    /*
     * Enumeration for representing "Type of Command" of proactive commands.
     * Those are the only commands which are supported by the Telephony. Any app
     * implementation should support those.
     * Refer to ETSI TS 102.223 section 9.4
     */
    public static enum CommandType {
        DISPLAY_TEXT(0x21),
        GET_INKEY(0x22),
        GET_INPUT(0x23),
        LAUNCH_BROWSER(0x15),
        PLAY_TONE(0x20),
        REFRESH(0x01),
        SELECT_ITEM(0x24),
        SEND_SS(0x11),
        SEND_USSD(0x12),
        SEND_SMS(0x13),
        SEND_DTMF(0x14),
        SET_UP_EVENT_LIST(0x05),
        SET_UP_IDLE_MODE_TEXT(0x28),
        SET_UP_MENU(0x25),
        SET_UP_CALL(0x10),
        PROVIDE_LOCAL_INFORMATION(0x26),
                // Add By huibin
        /**
         * Proactive command MORE_TIME
         * @internal
         */
        MORE_TIME(0x02),
        /**
         * Proactive command POLL_INTERVAL
         * @internal
         */
        POLL_INTERVAL(0x03),
        /**
         * Proactive command POLLING_OFF
         * @internal
         */
        POLLING_OFF(0x04),
        /**
         * Proactive command TIMER_MANAGEMENT
         * @internal
         */
        TIMER_MANAGEMENT(0x27),
        /**
         * Proactive command PERFORM_CARD_APDU
         * @internal
         */
        PERFORM_CARD_APDU(0x30),
        /**
         * Proactive command POWER_ON_CARD
         * @internal
         */
        POWER_ON_CARD(0x31),
        /**
         * Proactive command POWER_OFF_CARD
         * @internal
         */
        POWER_OFF_CARD(0x32),
        /**
         * Proactive command GET_READER_STATUS
         * @internal
         */
        GET_READER_STATUS(0x33),
        /**
         * Proactive command RUN_AT_COMMAND
         * @internal
         */
        RUN_AT_COMMAND(0x34),
        /**
         * Proactive command LANGUAGE_NOTIFICATION
         * @internal
         */
        LANGUAGE_NOTIFICATION(0x35),
        OPEN_CHANNEL(0x40),
        CLOSE_CHANNEL(0x41),
        RECEIVE_DATA(0x42),
        SEND_DATA(0x43),
        /**
         * Proactive command GET_CHANNEL_STATUS
         * @internal
         */
        GET_CHANNEL_STATUS(0x44),
        /**
         * Proactive command SERVICE_SEARCH
         * @internal
         */
        SERVICE_SEARCH(0x45),
        /**
         * Proactive command GET_SERVICE_INFORMATION
         * @internal
         */
        GET_SERVICE_INFORMATION(0x46),
        /**
         * Proactive command DECLARE_SERVICE
         * @internal
         */
        DECLARE_SERVICE(0x47),
        /**
         * Proactive command SET_FRAME
         * @internal
         */
        SET_FRAME(0x50),
        /**
         * Proactive command GET_FRAME_STATUS
         * @internal
         */
        GET_FRAME_STATUS(0x51),
        /**
         * Proactive command RETRIEVE_MULTIMEDIA_MESSAGE
         * @internal
         */
        RETRIEVE_MULTIMEDIA_MESSAGE(0x60),
        /**
         * Proactive command SUBMIT_MULTIMEDIA_MESSAGE
         * @internal
         */
        SUBMIT_MULTIMEDIA_MESSAGE(0x61),
        /**
         * Proactive command DISPLAY_MULTIMEDIA_MESSAGE
         * @internal
         */
        DISPLAY_MULTIMEDIA_MESSAGE(0x62),
        /**
         * Proactive command ACTIVATE
         * @internal
         */
        ACTIVATE(0x70),
        /**
         * Proprietay message for Call Control alpha id display
         * @internal
         */
        CALLCTRL_RSP_MSG(0XFF);

        private int mValue;

        CommandType(int value) {
            mValue = value;
        }

        public int value() {
            return mValue;
        }

        /**
         * Create a CommandType object.
         *
         * @param value Integer value to be converted to a CommandType object.
         * @return CommandType object whose "Type of Command" value is {@code
         *         value}. If no CommandType object has that value, null is
         *         returned.
         */
        public static CommandType fromInt(int value) {
            for (CommandType e : CommandType.values()) {
                if (e.mValue == value) {
                    return e;
                }
            }
            return null;
        }
    }
}
