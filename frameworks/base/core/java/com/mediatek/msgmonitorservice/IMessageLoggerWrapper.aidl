package com.mediatek.msgmonitorservice;

import android.os.IBinder;

/** @hide */
oneway interface IMessageLoggerWrapper {
    void unregisterMsgLogger(String msgLoggerName);
    void dumpAllMessageHistory();
    void dumpMSGHistorybyName(String msgLoggerName);
}

