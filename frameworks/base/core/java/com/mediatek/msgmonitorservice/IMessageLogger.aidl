package com.mediatek.msgmonitorservice;

import android.os.IBinder;
import com.mediatek.msgmonitorservice.IMessageLoggerWrapper;

/**
  * M:Message Monitor Service for dump message history
  *
  * @hide
  *
  * @internal
  */
oneway interface IMessageLogger {
    void registerMsgLogger(String msgLoggerName, int pid, int tid, IMessageLoggerWrapper callback);
    /**
      * M:UnRegister Message Logger for message history dump
      *
      * @internal
      */
    void unregisterMsgLogger(String msgLoggerName);
    /**
      * M:Dump message history
      *
      * @internal
      */
    void dumpAllMessageHistory(int pid);
    void dumpMSGHistorybyName(String msgLoggerName);
    void dumpCallStack(int Pid);

}
