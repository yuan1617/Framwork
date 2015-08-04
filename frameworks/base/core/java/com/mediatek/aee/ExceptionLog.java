/* Copyright Statement:
 *
 * This software/firmware and related documentation ("MediaTek Software") are
 * protected under relevant copyright laws. The information contained herein
 * is confidential and proprietary to MediaTek Inc. and/or its licensors.
 * Without the prior written permission of MediaTek inc. and/or its licensors,
 * any reproduction, modification, use or disclosure of MediaTek Software,
 * and information contained herein, in whole or in part, shall be strictly prohibited.
 */
/* MediaTek Inc. (C) 2010. All rights reserved.
 *
 * BY OPENING THIS FILE, RECEIVER HEREBY UNEQUIVOCALLY ACKNOWLEDGES AND AGREES
 * THAT THE SOFTWARE/FIRMWARE AND ITS DOCUMENTATIONS ("MEDIATEK SOFTWARE")
 * RECEIVED FROM MEDIATEK AND/OR ITS REPRESENTATIVES ARE PROVIDED TO RECEIVER ON
 * AN "AS-IS" BASIS ONLY. MEDIATEK EXPRESSLY DISCLAIMS ANY AND ALL WARRANTIES,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE OR NONINFRINGEMENT.
 * NEITHER DOES MEDIATEK PROVIDE ANY WARRANTY WHATSOEVER WITH RESPECT TO THE
 * SOFTWARE OF ANY THIRD PARTY WHICH MAY BE USED BY, INCORPORATED IN, OR
 * SUPPLIED WITH THE MEDIATEK SOFTWARE, AND RECEIVER AGREES TO LOOK ONLY TO SUCH
 * THIRD PARTY FOR ANY WARRANTY CLAIM RELATING THERETO. RECEIVER EXPRESSLY ACKNOWLEDGES
 * THAT IT IS RECEIVER'S SOLE RESPONSIBILITY TO OBTAIN FROM ANY THIRD PARTY ALL PROPER LICENSES
 * CONTAINED IN MEDIATEK SOFTWARE. MEDIATEK SHALL ALSO NOT BE RESPONSIBLE FOR ANY MEDIATEK
 * SOFTWARE RELEASES MADE TO RECEIVER'S SPECIFICATION OR TO CONFORM TO A PARTICULAR
 * STANDARD OR OPEN FORUM. RECEIVER'S SOLE AND EXCLUSIVE REMEDY AND MEDIATEK'S ENTIRE AND
 * CUMULATIVE LIABILITY WITH RESPECT TO THE MEDIATEK SOFTWARE RELEASED HEREUNDER WILL BE,
 * AT MEDIATEK'S OPTION, TO REVISE OR REPLACE THE MEDIATEK SOFTWARE AT ISSUE,
 * OR REFUND ANY SOFTWARE LICENSE FEES OR SERVICE CHARGE PAID BY RECEIVER TO
 * MEDIATEK FOR SUCH MEDIATEK SOFTWARE AT ISSUE.
 *
 * The following software/firmware and/or related documentation ("MediaTek Software")
 * have been modified by MediaTek Inc. All revisions are subject to any receiver's
 * applicable license agreements with MediaTek Inc.
 */

package com.mediatek.aee;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.ActivityThread;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.ServiceManager;
import android.util.Log;
import android.widget.Toast;
import com.android.internal.R;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Map;
import java.io.IOException;

public class ExceptionLog {
  public static final String TAG = "AES";
	public static final byte AEE_WARNING_JNI = 0;
	public static final byte AEE_EXCEPTION_JNI = 1;

    static { 
        Log.i(TAG, "load Exception Log jni");
        System.loadLibrary("mediatek_exceptionlog"); 
    }

    public void handle(String type, String info, String pid) {
        Log.w(TAG, "Exception Log handling...");

        String proc = "";
        String pkgs = "";
        String traceback = "";
        String cause = "";
        String detail ="";
        long lpid = 0;

        String[] splitInfo = (info.split("\n+"));
        final String PROC_REGEX = "^Process:\\s+(.*)";
        final String PKG_REGEX = "^Package:\\s+(.*)";
        Pattern procMatcher = Pattern.compile(PROC_REGEX);
        Pattern pkgMatcher = Pattern.compile(PKG_REGEX);
        Matcher m;

        for (String s : splitInfo) {
            m = procMatcher.matcher(s);
            if (m.matches()) proc = m.group(1);
            m = pkgMatcher.matcher(s);
            if (m.matches()) pkgs += m.group(1) + "\n";
        }

        detail = "Backtrace of all threads:\n\n" + getAllThreadStackTraces();
        if(!pid.equals("")){
            lpid = Long.parseLong(pid);
        }
        report(proc, pkgs, info, detail, type, lpid);
    }

	public void systemreport(byte Type, String Module, String Msg, String Path) {
        String Backtrace = getThreadStackTrace();
	    systemreportImpl(Type, Module, Backtrace, Msg, Path);
		return;
    }

    public boolean getNativeExceptionPidList(int[] pidList) {
	return getNativeExceptionPidListImpl(pidList);
    }

	
    /**
     * Get stack traces of current thread. Invoked at handling
     * application erorr.
     */
    private static String getThreadStackTrace() {
        Writer traces = new StringWriter();
		String ret_trace;
		 
        try {
			Thread th = Thread.currentThread();
			StackTraceElement[] st = th.getStackTrace();

			traces.write("\"" + th.getName() + "\"" +
			   " " + (th.isDaemon() ? "daemon" : "") +
			   " prio=" + th.getPriority() +
			   " Thread id=" + th.getId() + " " + th.getState() + "\n");
            for (StackTraceElement line: st) {
                traces.write("\t" + line + "\n");
            }
            traces.write("\n");
			ret_trace = traces.toString();
        } catch (IOException e) {
            return "IOException";
        } catch (java.lang.OutOfMemoryError err) {
        	return "java.lang.OutOfMemoryError";
        }

        return ret_trace;
    }

    /**
     * Get all stack traces of current process. Invoked at handling
     * application erorr.
     */
    private static String getAllThreadStackTraces() {
        Map<Thread, StackTraceElement[]> st = Thread.getAllStackTraces();
        Writer traces = new StringWriter();
		String ret_traces;

        try {
            for (Map.Entry<Thread, StackTraceElement[]> e: st.entrySet()) {
                StackTraceElement[] el = e.getValue();
                Thread th = e.getKey();

                traces.write("\"" + th.getName() + "\"" +
                      " " + (th.isDaemon() ? "daemon" : "") +
                      " prio=" + th.getPriority() +
                      " Thread id=" + th.getId() + " " + th.getState() + "\n");

                for (StackTraceElement line: el) {
                    traces.write("\t" + line + "\n");
                }
                traces.write("\n");
            }
			ret_traces = traces.toString();
        } catch (IOException e) {
            return "IOException";
        } catch (java.lang.OutOfMemoryError err) {
        	return "java.lang.OutOfMemoryError";
        }

		return ret_traces;
    }
	public void WDTMatterJava(long lParam) {
		WDTMatter(lParam) ;
		}

    private static native void report(String process, String module, String traceback, String detail, String cause, long pid);
    private static native void systemreportImpl(byte Type, String Module, String Backtrace,String Msg,String Path);
    private static native boolean getNativeExceptionPidListImpl(int []pidList);
// QHQ RT Monitor        
    private static native void WDTMatter(long lParam) ;
// QHQ RT Monitor end
}
