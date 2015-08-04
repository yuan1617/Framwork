/*
 * Copyright (C) 2007-2008 The Android Open Source Project
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

package com.android.server.storage;

import com.android.server.EventLogTags;
import com.android.server.SystemService;
import com.android.server.pm.PackageManagerService;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.IPackageDataObserver;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Environment;
import android.os.FileObserver;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.StatFs;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.text.format.Formatter;
import android.util.EventLog;
import android.util.Slog;
import android.util.TimeUtils;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;

import dalvik.system.VMRuntime;

///M:for low storage feature,@{
import android.app.AlertDialog;

import android.content.BroadcastReceiver;
import android.content.DialogInterface;
import android.content.IntentFilter;
import android.content.pm.IPackageStatsObserver;
import android.content.pm.PackageStats;

import android.view.WindowManager;
import android.content.DialogInterface.OnClickListener;

//import com.vladium.emma.rt.RT; /// M : Add emma coverage report for system server.
import com.mediatek.common.MPlugin;
import com.mediatek.common.lowstorage.ILowStorageExt;
///@}

/**
 * This class implements a service to monitor the amount of disk
 * storage space on the device.  If the free storage on device is less
 * than a tunable threshold value (a secure settings parameter;
 * default 10%) a low memory notification is displayed to alert the
 * user. If the user clicks on the low memory notification the
 * Application Manager application gets launched to let the user free
 * storage space.
 *
 * Event log events: A low memory event with the free storage on
 * device in bytes is logged to the event log when the device goes low
 * on storage space.  The amount of free storage on the device is
 * periodically logged to the event log. The log interval is a secure
 * settings parameter with a default value of 12 hours.  When the free
 * storage differential goes below a threshold (again a secure
 * settings parameter with a default value of 2MB), the free memory is
 * logged to the event log.
 */
public class DeviceStorageMonitorService extends SystemService {
    static final String TAG = "DeviceStorageMonitorService";

    static final boolean DEBUG = false;
    static final boolean localLOGV = false;

    static final int DEVICE_MEMORY_WHAT = 1;
    static final int DEVICE_MEMORY_CRITICAL_LOW = 2; // For low storage dialog show

    private static final int MONITOR_INTERVAL = 1; //in minutes
    private static final int LOW_MEMORY_NOTIFICATION_ID = 1;

    private static final int DEFAULT_THRESHOLD_PERCENTAGE = 10;
    // /M:Add for low storage feature,google default is 500M,@{
    private static final int DEFAULT_THRESHOLD_MAX_BYTES = 50 * 1024 * 1024; // 50MB
    ///@}

    private static final int DEFAULT_FREE_STORAGE_LOG_INTERVAL_IN_MINUTES = 12*60; //in minutes
    private static final long DEFAULT_DISK_FREE_CHANGE_REPORTING_THRESHOLD = 2 * 1024 * 1024; // 2MB
    private static final long DEFAULT_CHECK_INTERVAL = MONITOR_INTERVAL * 30 * 1000;  // M: change to 30, original 60

    private long mFreeMem;  // on /data
    private long mFreeMemAfterLastCacheClear;  // on /data
    private long mLastReportedFreeMem;
    private long mLastReportedFreeMemTime;
    boolean mLowMemFlag=false;
    private boolean mMemFullFlag=false;
    private final boolean mIsBootImageOnDisk;
    private final ContentResolver mResolver;
    private final long mTotalMemory;  // on /data
    private final StatFs mDataFileStats;
    private final StatFs mSystemFileStats;
    private final StatFs mCacheFileStats;

    private static final File DATA_PATH = Environment.getDataDirectory();
    private static final File SYSTEM_PATH = Environment.getRootDirectory();
    private static final File CACHE_PATH = Environment.getDownloadCacheDirectory();

    private long mThreadStartTime = -1;
    boolean mClearSucceeded = false;
    boolean mClearingCache;
    private final Intent mStorageLowIntent;
    private final Intent mStorageOkIntent;
    private final Intent mStorageFullIntent;
    private final Intent mStorageNotFullIntent;
    private CachePackageDataObserver mClearCacheObserver;
    private CacheFileDeletedObserver mCacheFileDeletedObserver;
    private static final int _TRUE = 1;
    private static final int _FALSE = 0;
    // This is the raw threshold that has been set at which we consider
    // storage to be low.
    long mMemLowThreshold;
    // This is the threshold at which we start trying to flush caches
    // to get below the low threshold limit.  It is less than the low
    // threshold; we will allow storage to get a bit beyond the limit
    // before flushing and checking if we are actually low.
    private long mMemCacheStartTrimThreshold;
    // This is the threshold that we try to get to when deleting cache
    // files.  This is greater than the low threshold so that we will flush
    // more files than absolutely needed, to reduce the frequency that
    // flushing takes place.
    private long mMemCacheTrimToThreshold;
    private long mMemFullThreshold;

    // /M:Add for low storage feature,@{
    ILowStorageExt lse = null;
    private static final int FULL_THRESHOLD_BYTES = 5 * 1024 * 1024; // 5MB
    private static final int CRITICAL_LOW_THRESHOLD_BYTES = 4 * 1024 * 1024; // 4MB
    private static final int EXCEPTION_LOW_THRESHOLD_BYTES = 10 * 1024 * 1024; // 10MB
    private static final int EMAIL_CHECK_SIZE = 50 * 1024 * 1024; // 50MB
    private boolean mConfigChanged = false;
    private static final String IPO_POWER_ON = "android.intent.action.ACTION_BOOT_IPO"; // /M:For IPO feature
    private AlertDialog mDialog = null;
    private int mLastCriticalLowLevel = 4;
    private boolean mIPOBootup = false; // /M:For IPO feature
    private boolean mCheckAppSize = true;
    private StatFs mQueryDataFs;
    private long mCacheSize = 0;
    private long mCodeSize = 0;
    private long mDataSize = 0;
    private long mTotalSize = 0;
    private String[] mStrings = null;
    private boolean mGetSize = false; // M
    // /@}

    /**
     * This string is used for ServiceManager access to this class.
     */
    static final String SERVICE = "devicestoragemonitor";

    /**
    * Handler that checks the amount of disk space on the device and sends a
    * notification if the device runs low on disk space
    */
    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            // /M:For Low storage,to show warning dialog per 30s,@{
            if (msg.what == DEVICE_MEMORY_CRITICAL_LOW) {
                if (mDialog == null || mIPOBootup || mConfigChanged) {
                    mIPOBootup = false;
                    if (mConfigChanged) {
                        mConfigChanged = false;
                    }
                    final Context context = getContext();
                    AlertDialog.Builder builder = new AlertDialog.Builder(context)
                            .setIcon(com.android.internal.R.drawable.ic_dialog_alert)
                            .setTitle(
                                    context
                                            .getText(com.mediatek.internal.R.string.low_internal_storage_view_title))
                            .setMessage(
                                    context
                                            .getText(com.mediatek.internal.R.string.low_storage_warning_message))
                            .setNegativeButton(
                                    context.getText(com.mediatek.R.string.free_memory_btn), new OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int which) {
                                            Intent mIntent = new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS);
                                            mIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                            context.startActivity(mIntent);
                                        }
                             })
                            .setPositiveButton(
                                    context.getText(com.android.internal.R.string.cancel), null);
                    if (mTotalSize > EMAIL_CHECK_SIZE) {
                        builder.setNeutralButton(
                            context.getText(com.mediatek.internal.R.string.deleteMails), new OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    Intent mIntent = new Intent();
                                    mIntent.setAction("android.intent.action.MAIN");
                                    mIntent.addCategory("android.intent.category.APP_EMAIL");
                                    mIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                    context.startActivity(mIntent);
                                }
                        });
                    }
                    mDialog = builder.create();
                }

                mDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
                if (!mDialog.isShowing()) {
                    // For CTS running,do not show the no space dailog
                    if (android.os.SystemProperties.getInt("ctsrunning", 0) == 0) {
                        mDialog.show();
                    } else {
                        Slog.i(TAG, "In CTS Running,do not show the no space dailog");
                    }
                }
                return;
            }
            // /@}
            //don't handle an invalid message
            if (msg.what != DEVICE_MEMORY_WHAT) {
                Slog.e(TAG, "Will not process invalid message");
                return;
            }
            checkMemory(msg.arg1 == _TRUE);
        }
    };

    private class CachePackageDataObserver extends IPackageDataObserver.Stub {
        public void onRemoveCompleted(String packageName, boolean succeeded) {
            mClearSucceeded = succeeded;
            mClearingCache = false;
            if(localLOGV) Slog.i(TAG, " Clear succeeded:"+mClearSucceeded
                    +", mClearingCache:"+mClearingCache+" Forcing memory check");
            postCheckMemoryMsg(false, 0);
        }
    }

    private void restatDataDir() {
        try {
            mDataFileStats.restat(DATA_PATH.getAbsolutePath());
            mFreeMem = (long) mDataFileStats.getAvailableBlocks() *
                mDataFileStats.getBlockSize();
        } catch (IllegalArgumentException e) {
            // use the old value of mFreeMem
        }
        // Allow freemem to be overridden by debug.freemem for testing
        String debugFreeMem = SystemProperties.get("debug.freemem");
        if (!"".equals(debugFreeMem)) {
            mFreeMem = Long.parseLong(debugFreeMem);
        }
        // Read the log interval from secure settings
        long freeMemLogInterval = Settings.Global.getLong(mResolver,
                Settings.Global.SYS_FREE_STORAGE_LOG_INTERVAL,
                DEFAULT_FREE_STORAGE_LOG_INTERVAL_IN_MINUTES)*60*1000;
        //log the amount of free memory in event log
        long currTime = SystemClock.elapsedRealtime();
        if((mLastReportedFreeMemTime == 0) ||
           (currTime-mLastReportedFreeMemTime) >= freeMemLogInterval) {
            mLastReportedFreeMemTime = currTime;
            long mFreeSystem = -1, mFreeCache = -1;
            try {
                mSystemFileStats.restat(SYSTEM_PATH.getAbsolutePath());
                mFreeSystem = (long) mSystemFileStats.getAvailableBlocks() *
                    mSystemFileStats.getBlockSize();
            } catch (IllegalArgumentException e) {
                // ignore; report -1
            }
            try {
                mCacheFileStats.restat(CACHE_PATH.getAbsolutePath());
                mFreeCache = (long) mCacheFileStats.getAvailableBlocks() *
                    mCacheFileStats.getBlockSize();
            } catch (IllegalArgumentException e) {
                // ignore; report -1
            }
            EventLog.writeEvent(EventLogTags.FREE_STORAGE_LEFT,
                                mFreeMem, mFreeSystem, mFreeCache);
        }
        // Read the reporting threshold from secure settings
        long threshold = Settings.Global.getLong(mResolver,
                Settings.Global.DISK_FREE_CHANGE_REPORTING_THRESHOLD,
                DEFAULT_DISK_FREE_CHANGE_REPORTING_THRESHOLD);
        // If mFree changed significantly log the new value
        long delta = mFreeMem - mLastReportedFreeMem;
        if (delta > threshold || delta < -threshold) {
            mLastReportedFreeMem = mFreeMem;
            EventLog.writeEvent(EventLogTags.FREE_STORAGE_CHANGED, mFreeMem);
        }
    }

    private void clearCache() {
        if (mClearCacheObserver == null) {
            // Lazy instantiation
            mClearCacheObserver = new CachePackageDataObserver();
        }
        mClearingCache = true;
        try {
            if (localLOGV) Slog.i(TAG, "Clearing cache");
            IPackageManager.Stub.asInterface(ServiceManager.getService("package")).
                    freeStorageAndNotify(mMemCacheTrimToThreshold, mClearCacheObserver);
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to get handle for PackageManger Exception: "+e);
            mClearingCache = false;
            mClearSucceeded = false;
        }
    }

    void checkMemory(boolean checkCache) {
        //if the thread that was started to clear cache is still running do nothing till its
        //finished clearing cache. Ideally this flag could be modified by clearCache
        // and should be accessed via a lock but even if it does this test will fail now and
        //hopefully the next time this flag will be set to the correct value.
        if(mClearingCache) {
            if(localLOGV) Slog.i(TAG, "Thread already running just skip");
            //make sure the thread is not hung for too long
            long diffTime = System.currentTimeMillis() - mThreadStartTime;
            if(diffTime > (10*60*1000)) {
                Slog.w(TAG, "Thread that clears cache file seems to run for ever");
            }
        } else {
            restatDataDir();
            if (localLOGV)  Slog.v(TAG, "freeMemory="+mFreeMem);

            // /M:Add for checking operator's low storage threshold
            // @{
            if (lse == null) {
                lse = MPlugin.createInstance("com.mediatek.common.lowstorage.ILowStorageExt",
                                                 getContext());
                if (lse == null) {
                    Slog.e(TAG, "Failed to create LowStorageExt instance.");
                }
                else {
                    lse.init(getContext(), mTotalMemory);
                }
            }
            if (lse != null) {
                lse.checkStorage(mFreeMem);
            }
            // /@}
            //post intent to NotificationManager to display icon if necessary
            if (mFreeMem < mMemLowThreshold) {
                // check the Email apk size
                if (mCheckAppSize) {
                    mCheckAppSize = false;
                    PackageManager pm = getContext().getPackageManager();
                    pm.getPackageSizeInfo("com.android.email", mStatsObserver);
                }
                if (checkCache) {
                    // We are allowed to clear cache files at this point to
                    // try to get down below the limit, because this is not
                    // the initial call after a cache clear has been attempted.
                    // In this case we will try a cache clear if our free
                    // space has gone below the cache clear limit.
                    // /M:Add for low storage, when storage < mMemLowThreshold always do
                    // clear cache,@{
                    //  if (mFreeMem < mMemCacheStartTrimThreshold) {
                        // We only clear the cache if the free storage has changed
                        // a significant amount since the last time.
                       // if ((mFreeMemAfterLastCacheClear-mFreeMem)
                       //         >= ((mMemLowThreshold-mMemCacheStartTrimThreshold)/4)) {
                            // See if clearing cache helps
                            // Note that clearing cache is asynchronous and so we do a
                            // memory check again once the cache has been cleared.
                            mThreadStartTime = System.currentTimeMillis();
                            mClearSucceeded = false;
                            clearCache();
                        //}
                   // }
                   ///@}
                } else {
                    // This is a call from after clearing the cache.  Note
                    // the amount of free storage at this point.
                    mFreeMemAfterLastCacheClear = mFreeMem;
                    if (!mLowMemFlag) {
                        // We tried to clear the cache, but that didn't get us
                        // below the low storage limit.  Tell the user.
                        Slog.i(TAG, "Running low on memory. Sending notification");
                        sendNotification();
                        mLowMemFlag = true;
                    } else {
                        if (localLOGV) Slog.v(TAG, "Running low on memory " +
                                "notification already sent. do nothing");
                    }
                }
            } else {
                mFreeMemAfterLastCacheClear = mFreeMem;
                if (mLowMemFlag) {
                    mCheckAppSize = true;
                    mGetSize = false;
                    Slog.i(TAG, "Memory available. Cancelling notification");
                    cancelNotification();
                    mLowMemFlag = false;
                }
            }
            if (!mLowMemFlag && !mIsBootImageOnDisk) {
                Slog.i(TAG, "No boot image on disk due to lack of space. Sending notification");
                sendNotification();
            }
            if (mFreeMem < mMemFullThreshold) {
                Slog.v(TAG, "Running on storage full,freeStorage=" + mFreeMem);
                if (!mMemFullFlag) {
                    sendFullNotification();
                    mMemFullFlag = true;
                }
            } else {
                if (mMemFullFlag) {
                    cancelFullNotification();
                    mMemFullFlag = false;
                }
            }
            // /M:Add for low storage, if storage <4M,show the wanring
            // message,@{
            int criticalLowLevel = (int) Math.floor(mFreeMem / (1024 * 1024));
            if (criticalLowLevel < mLastCriticalLowLevel) {
                if (mFreeMem < mLastCriticalLowLevel * 1024 * 1024 && mGetSize) {
                    mHandler.sendMessage(mHandler.obtainMessage(DEVICE_MEMORY_CRITICAL_LOW));
                    Slog.i(TAG, "Show warning dialog, critical level: " + criticalLowLevel);
                    mLastCriticalLowLevel = criticalLowLevel;
                }
            }
            if (mLastCriticalLowLevel < criticalLowLevel) {
                mLastCriticalLowLevel = Math.min(criticalLowLevel, 4);
            }
            // /@}
        }
        if(localLOGV) Slog.i(TAG, "Posting Message again");
        //keep posting messages to itself periodically
        postCheckMemoryMsg(true, DEFAULT_CHECK_INTERVAL);
    }

    // /M:For low storage,to check the APP(Eamil&Message) used data size,@{
    final IPackageStatsObserver.Stub mStatsObserver = new IPackageStatsObserver.Stub() {
        public void onGetStatsCompleted(PackageStats stats, boolean succeeded) {
            mCacheSize = stats.cacheSize;
            mCodeSize = stats.codeSize;
            mDataSize = stats.dataSize;
            mTotalSize = mCacheSize + mCodeSize + mDataSize;
            mGetSize = true;
            Slog.v(TAG, "mStatsObserver  mCacheSize = " + mCacheSize + "mCodeSize = " + mCodeSize
                    + "mDataSize=" + mDataSize + "mTotalSize=" + mTotalSize);
        }
    };

    // /@}
    void postCheckMemoryMsg(boolean clearCache, long delay) {
        // Remove queued messages
        mHandler.removeMessages(DEVICE_MEMORY_WHAT);
        mHandler.sendMessageDelayed(mHandler.obtainMessage(DEVICE_MEMORY_WHAT,
                clearCache ?_TRUE : _FALSE, 0),
                delay);
    }

    /*
    * just query settings to retrieve the memory threshold.
    * Preferred this over using a ContentObserver since Settings.Secure caches the value
    * any way
    */
    private long getMemThreshold() {
        long value = Settings.Global.getInt(
                              mResolver,
                              Settings.Global.SYS_STORAGE_THRESHOLD_PERCENTAGE,
                              DEFAULT_THRESHOLD_PERCENTAGE);
        if (localLOGV) Slog.v(TAG, "Threshold Percentage=" + value);
        value = (value * mTotalMemory) / 100;
        long maxValue = Settings.Global.getInt(
                mResolver,
                Settings.Global.SYS_STORAGE_THRESHOLD_MAX_BYTES,
                DEFAULT_THRESHOLD_MAX_BYTES);
        //evaluate threshold value
        return value < maxValue ? value : maxValue;
    }

    /**
    * Constructor to run service. initializes the disk space threshold value
    * and posts an empty message to kickstart the process.
    */
    public DeviceStorageMonitorService(Context context) {
        super(context);
        mLastReportedFreeMemTime = 0;
        mResolver = context.getContentResolver();
        mIsBootImageOnDisk = isBootImageOnDisk();
        //create StatFs object
        mDataFileStats = new StatFs(DATA_PATH.getAbsolutePath());
        mSystemFileStats = new StatFs(SYSTEM_PATH.getAbsolutePath());
        mCacheFileStats = new StatFs(CACHE_PATH.getAbsolutePath());
        mQueryDataFs = new StatFs(DATA_PATH.getAbsolutePath());
        //initialize total storage on device
        mTotalMemory = (long)mDataFileStats.getBlockCount() *
                        mDataFileStats.getBlockSize();
        mStorageLowIntent = new Intent(Intent.ACTION_DEVICE_STORAGE_LOW);
        mStorageLowIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        mStorageOkIntent = new Intent(Intent.ACTION_DEVICE_STORAGE_OK);
        mStorageOkIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        mStorageFullIntent = new Intent(Intent.ACTION_DEVICE_STORAGE_FULL);
        mStorageFullIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        mStorageNotFullIntent = new Intent(Intent.ACTION_DEVICE_STORAGE_NOT_FULL);
        mStorageNotFullIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);

        IntentFilter filter = new IntentFilter();
        filter.addAction(IPO_POWER_ON);
        filter.addAction(Intent.ACTION_LOCALE_CHANGED);
        context.registerReceiver(mIntentReceiver, filter);
    }

    private static boolean isBootImageOnDisk() {
        for (String instructionSet : PackageManagerService.getAllDexCodeInstructionSets()) {
            if (!VMRuntime.isBootClassPathOnDisk(instructionSet)) {
                return false;
            }
        }
        return true;
    }

    /**
    * Initializes the disk space threshold value and posts an empty message to
    * kickstart the process.
    */
    @Override
    public void onStart() {
        // cache storage thresholds
        //final StorageManager sm = StorageManager.from(getContext());
        //mMemLowThreshold = sm.getStorageLowBytes(DATA_PATH);
        //mMemFullThreshold = sm.getStorageFullBytes(DATA_PATH);

        mMemLowThreshold = getMemThreshold();
        mMemFullThreshold = FULL_THRESHOLD_BYTES;

        mMemCacheStartTrimThreshold = ((mMemLowThreshold*3)+mMemFullThreshold)/4;
        mMemCacheTrimToThreshold = mMemLowThreshold
                + ((mMemLowThreshold-mMemCacheStartTrimThreshold)*2);
        mFreeMemAfterLastCacheClear = mTotalMemory;
        checkMemory(true);

        mCacheFileDeletedObserver = new CacheFileDeletedObserver();
        mCacheFileDeletedObserver.startWatching();

        publishBinderService(SERVICE, mRemoteService);
        publishLocalService(DeviceStorageMonitorInternal.class, mLocalService);
    }

    private final DeviceStorageMonitorInternal mLocalService = new DeviceStorageMonitorInternal() {
        @Override
        public void checkMemory() {
            // force an early check
            postCheckMemoryMsg(true, 0);
        }

        @Override
        public boolean isMemoryLow() {
            return mLowMemFlag || !mIsBootImageOnDisk;
        }

        @Override
        public long getMemoryLowThreshold() {
            return mMemLowThreshold;
        }


        public boolean isMemoryCriticalLow() {
            long tempFreeMem = 0;
            try {
                mQueryDataFs.restat(DATA_PATH.getAbsolutePath());
                tempFreeMem = (long) mQueryDataFs.getAvailableBlocks() *
                mQueryDataFs.getBlockSize();
            } catch (IllegalArgumentException e) {
                // use the default value of tempFreeMem
                tempFreeMem = mFreeMem;
                Slog.v(TAG, "Failed to get current free storage size.");
            }
            if (tempFreeMem <= EXCEPTION_LOW_THRESHOLD_BYTES) {
                return true;
            } else {
                return false;
            }
        }
    };

    private final IBinder mRemoteService = new Binder() {
        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (getContext().checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                    != PackageManager.PERMISSION_GRANTED) {

                pw.println("Permission Denial: can't dump " + SERVICE + " from from pid="
                        + Binder.getCallingPid()
                        + ", uid=" + Binder.getCallingUid());
                return;
            }

            dumpImpl(pw);
        }
    };

    void dumpImpl(PrintWriter pw) {
        final Context context = getContext();

        // /M:Add for EMMA coverage rate counting
        // ,@{
/*        int opti = 0;
        while (opti < args.length) {
            String opt = args[opti];
            if (opt == null || opt.length() <= 0 || opt.charAt(0) != '-') {
                break;
            }
            opti++;

            if ("-r".equals(opt)) {
                RT.resetCoverageData();
                return;
            } else if ("-g".equals(opt)) {
                RT.dumpCoverageData(new File ("/data/server_coverage.ec"), false);
                return;
            } else {
                pw.println("Unknown argument: " + opt + "; use -h for help");
            }
        }
*/
        // /@}

        pw.println("Current DeviceStorageMonitor state:");

        pw.print("  mFreeMem="); pw.print(Formatter.formatFileSize(context, mFreeMem));
        pw.print(" mTotalMemory=");
        pw.println(Formatter.formatFileSize(context, mTotalMemory));

        pw.print("  mFreeMemAfterLastCacheClear=");
        pw.println(Formatter.formatFileSize(context, mFreeMemAfterLastCacheClear));

        pw.print("  mLastReportedFreeMem=");
        pw.print(Formatter.formatFileSize(context, mLastReportedFreeMem));
        pw.print(" mLastReportedFreeMemTime=");
        TimeUtils.formatDuration(mLastReportedFreeMemTime, SystemClock.elapsedRealtime(), pw);
        pw.println();

        pw.print("  mLowMemFlag="); pw.print(mLowMemFlag);
        pw.print(" mMemFullFlag="); pw.println(mMemFullFlag);
        pw.print(" mIsBootImageOnDisk="); pw.print(mIsBootImageOnDisk);

        pw.print("  mClearSucceeded="); pw.print(mClearSucceeded);
        pw.print(" mClearingCache="); pw.println(mClearingCache);

        pw.print("  mMemLowThreshold=");
        pw.print(Formatter.formatFileSize(context, mMemLowThreshold));
        pw.print(" mMemFullThreshold=");
        pw.println(Formatter.formatFileSize(context, mMemFullThreshold));

        pw.print("  mMemCacheStartTrimThreshold=");
        pw.print(Formatter.formatFileSize(context, mMemCacheStartTrimThreshold));
        pw.print(" mMemCacheTrimToThreshold=");
        pw.println(Formatter.formatFileSize(context, mMemCacheTrimToThreshold));
    }

    /**
    * This method sends a notification to NotificationManager to display
    * an error dialog indicating low disk space and launch the Installer
    * application
    */
    private void sendNotification() {
        final Context context = getContext();
        if(localLOGV) Slog.i(TAG, "Sending low memory notification");
        //log the event to event log with the amount of free storage(in bytes) left on the device
        EventLog.writeEvent(EventLogTags.LOW_STORAGE, mFreeMem);
        //  Pack up the values and broadcast them to everyone
        Intent lowMemIntent = new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS);
        lowMemIntent.putExtra("memory", mFreeMem);
        lowMemIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        NotificationManager mNotificationMgr =
                (NotificationManager)context.getSystemService(
                        Context.NOTIFICATION_SERVICE);
        CharSequence title = context.getText(
                com.mediatek.internal.R.string.low_internal_storage_view_title);
        CharSequence details = context.getText(mIsBootImageOnDisk
                ? com.android.internal.R.string.low_internal_storage_view_text
                : com.android.internal.R.string.low_internal_storage_view_text_no_boot);
        PendingIntent intent = PendingIntent.getActivityAsUser(context, 0,  lowMemIntent, 0,
                null, UserHandle.CURRENT);
        Notification notification = new Notification.Builder(context)
                .setSmallIcon(com.android.internal.R.drawable.stat_notify_disk_full)
                .setTicker(title)
                .setColor(context.getResources().getColor(
                    com.android.internal.R.color.system_notification_accent_color))
                .setContentTitle(title)
                .setContentText(details)
                .setContentIntent(intent)
                .setStyle(new Notification.BigTextStyle()
                      .bigText(details))
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_SYSTEM)
                .build();
        notification.flags |= Notification.FLAG_NO_CLEAR;
        mNotificationMgr.notifyAsUser(null, LOW_MEMORY_NOTIFICATION_ID, notification,
                UserHandle.ALL);
        context.sendStickyBroadcastAsUser(mStorageLowIntent, UserHandle.ALL);
    }

    /**
     * Cancels low storage notification and sends OK intent.
     */
    private void cancelNotification() {
        final Context context = getContext();
        if(localLOGV) Slog.i(TAG, "Canceling low memory notification");
        NotificationManager mNotificationMgr =
                (NotificationManager)context.getSystemService(
                        Context.NOTIFICATION_SERVICE);
        //cancel notification since memory has been freed
        mNotificationMgr.cancelAsUser(null, LOW_MEMORY_NOTIFICATION_ID, UserHandle.ALL);

        context.removeStickyBroadcastAsUser(mStorageLowIntent, UserHandle.ALL);
        context.sendBroadcastAsUser(mStorageOkIntent, UserHandle.ALL);
    }

    /**
     * Send a notification when storage is full.
     */
    private void sendFullNotification() {
        if(localLOGV) Slog.i(TAG, "Sending memory full notification");
        getContext().sendStickyBroadcastAsUser(mStorageFullIntent, UserHandle.ALL);
    }

    /**
     * Cancels memory full notification and sends "not full" intent.
     */
    private void cancelFullNotification() {
        if(localLOGV) Slog.i(TAG, "Canceling memory full notification");
        getContext().removeStickyBroadcastAsUser(mStorageFullIntent, UserHandle.ALL);
        getContext().sendBroadcastAsUser(mStorageNotFullIntent, UserHandle.ALL);
    }

    private static class CacheFileDeletedObserver extends FileObserver {
        public CacheFileDeletedObserver() {
            super(Environment.getDownloadCacheDirectory().getAbsolutePath(), FileObserver.DELETE);
        }

        @Override
        public void onEvent(int event, String path) {
            EventLogTags.writeCacheFileDeleted(path);
        }
    }

    /**
     * M:add for Received the IPO boot and locale change intent
     */
    private BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (action.equals(IPO_POWER_ON)) {
                mIPOBootup = true;
                mLowMemFlag = false;
            }

            if (action.equals(Intent.ACTION_LOCALE_CHANGED)) {
                mConfigChanged = true;
                if (null != mDialog) {
                    mDialog.cancel();
                }
            }
        }
    };
}
