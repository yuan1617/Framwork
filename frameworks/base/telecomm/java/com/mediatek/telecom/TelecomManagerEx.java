package com.mediatek.telecom;

/**
 * @hide
 */
public class TelecomManagerEx {

    //-------------For VoLTE normal call switch to ECC------------------
    public static final String EXTRA_VOLTE_MARKED_AS_EMERGENCY = "com.mediatek.volte.isMergency";

    //-------------For VoLTE SS runtime indication------------------
    public static final String EXTRA_VOLTE_MARKED_AS_WAITING = "com.mediatek.volte.isCallWaiting";

    //-------------For VoLTE PAU field------------------
    public static final String EXTRA_VOLTE_PAU_FIELD = "com.mediatek.volte.pau";

    //-------------For VoLTE Conference Xml------------------
    public static final String EXTRA_VOLTE_CONFERENCE_XML = "com.mediatek.volte.conference.xml";

    //-------------For VoLTE Call Mode------------------
    // below are int extra, and indicate gsmConnection.getCallMode()
    public static final String EXTRA_VOLTE_CALL_MODE = "com.mediatek.volte.call.mode";
    public static class VolteCallMode {
        public static final int VALUE_NOT_SET = -2;
        public static final int CS_VOICE_CALL = 0;
        public static final int CS_VIDEO_CALL = 10;
        public static final int IMS_VOICE_CALL = 20;
        public static final int IMS_VIDEO_CALL = 21;
        public static final int IMS_VOICE_CONF = 22;
    }
}
