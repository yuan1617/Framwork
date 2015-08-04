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

package com.android.server;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.AlarmManager;
import android.app.IAlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.WorkSource;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.Time;
import android.text.format.DateFormat;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.TimeUtils;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Locale;
import java.util.TimeZone;

import static android.app.AlarmManager.RTC_WAKEUP;
import static android.app.AlarmManager.RTC;
import static android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP;
import static android.app.AlarmManager.ELAPSED_REALTIME;

import com.android.internal.util.LocalLog;
import com.mediatek.amplus.AlarmManagerPlus;
import com.mediatek.common.dm.DmAgent;


class AlarmManagerService extends SystemService {
    // The threshold for how long an alarm can be late before we print a
    // warning message.  The time duration is in milliseconds.
    private static final long LATE_ALARM_THRESHOLD = 10 * 1000;

    private static final int RTC_WAKEUP_MASK = 1 << RTC_WAKEUP;
    private static final int RTC_MASK = 1 << RTC;
    private static final int ELAPSED_REALTIME_WAKEUP_MASK = 1 << ELAPSED_REALTIME_WAKEUP;
    private static final int ELAPSED_REALTIME_MASK = 1 << ELAPSED_REALTIME;
    static final int TIME_CHANGED_MASK = 1 << 16;
    static final int IS_WAKEUP_MASK = RTC_WAKEUP_MASK|ELAPSED_REALTIME_WAKEUP_MASK;

    // Mask for testing whether a given alarm type is wakeup vs non-wakeup
    static final int TYPE_NONWAKEUP_MASK = 0x1; // low bit => non-wakeup

    static final String TAG = "AlarmManager";
    static final String ClockReceiver_TAG = "ClockReceiver";
    static  boolean localLOGV = false;
    static  boolean DEBUG_BATCH = localLOGV || false;
    static  boolean DEBUG_VALIDATE = localLOGV || false;
    static final boolean DEBUG_ALARM_CLOCK = localLOGV || false;
    static final int ALARM_EVENT = 1;
    static final String TIMEZONE_PROPERTY = "persist.sys.timezone";

    /// M: Background Service Priority Adjustment @{
    static final Intent mBackgroundIntent
            = new Intent().addFlags(Intent.FLAG_FROM_BACKGROUND | Intent.FLAG_WITH_BACKGROUND_PRIORITY);
    /// @}
    static final IncreasingTimeOrder sIncreasingTimeOrder = new IncreasingTimeOrder();
    
    static final boolean WAKEUP_STATS = false;

    private static final Intent NEXT_ALARM_CLOCK_CHANGED_INTENT = new Intent(
            AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED);

    final LocalLog mLog = new LocalLog(TAG);

    final Object mLock = new Object();

    long mNativeData;
    private long mNextWakeup;
    private long mNextNonWakeup;
    int mBroadcastRefCount = 0;
    PowerManager.WakeLock mWakeLock;
    boolean mLastWakeLockUnimportantForLogging;
    ArrayList<Alarm> mPendingNonWakeupAlarms = new ArrayList<Alarm>();
    ArrayList<InFlight> mInFlight = new ArrayList<InFlight>();
    final AlarmHandler mHandler = new AlarmHandler();
    ClockReceiver mClockReceiver;
    InteractiveStateReceiver mInteractiveStateReceiver;
    private UninstallReceiver mUninstallReceiver;
    final ResultReceiver mResultReceiver = new ResultReceiver();
    PendingIntent mTimeTickSender;
    PendingIntent mDateChangeSender;
    boolean mInteractive = true;
    long mNonInteractiveStartTime;
    long mNonInteractiveTime;
    long mLastAlarmDeliveryTime;
    long mStartCurrentDelayTime;
    long mNextNonWakeupDeliveryTime;
    int mNumTimeChanged;

    private final SparseArray<AlarmManager.AlarmClockInfo> mNextAlarmClockForUser =
            new SparseArray<>();
    private final SparseArray<AlarmManager.AlarmClockInfo> mTmpSparseAlarmClockArray =
            new SparseArray<>();
    private final SparseBooleanArray mPendingSendNextAlarmClockChangedForUser =
            new SparseBooleanArray();
    private boolean mNextAlarmClockMayChange;

    // May only use on mHandler's thread, locking not required.
    private final SparseArray<AlarmManager.AlarmClockInfo> mHandlerSparseAlarmClockArray =
            new SparseArray<>();

    // /M:add for DM feature ,@{
    private DMReceiver mDMReceiver = null;
    private boolean mDMEnable = true;
    private boolean mPPLEnable = true;
    private Object mDMLock = new Object();
    private ArrayList<PendingIntent> mDmFreeList = null;
    private ArrayList<String> mAlarmIconPackageList = null;
    private ArrayList<Alarm> mDmResendList = null;
    // /@}

    /// M: BG powerSaving feature start @{
    private AlarmManagerPlus mAmPlus;
    /// M: BG powerSaving feature end @}

    // Alarm delivery ordering bookkeeping
    static final int PRIO_TICK = 0;
    static final int PRIO_WAKEUP = 1;
    static final int PRIO_NORMAL = 2;

    class PriorityClass {
        int seq;
        int priority;

        PriorityClass() {
            seq = mCurrentSeq - 1;
            priority = PRIO_NORMAL;
        }
    }

    final HashMap<String, PriorityClass> mPriorities =
            new HashMap<String, PriorityClass>();
    int mCurrentSeq = 0;

    class WakeupEvent {
        public long when;
        public int uid;
        public String action;

        public WakeupEvent(long theTime, int theUid, String theAction) {
            when = theTime;
            uid = theUid;
            action = theAction;
        }
    }

    final LinkedList<WakeupEvent> mRecentWakeups = new LinkedList<WakeupEvent>();
    final long RECENT_WAKEUP_PERIOD = 1000L * 60 * 60 * 24; // one day

    final class Batch {
        long start;     // These endpoints are always in ELAPSED
        long end;
        boolean standalone; // certain "batches" don't participate in coalescing

        final ArrayList<Alarm> alarms = new ArrayList<Alarm>();

        Batch() {
            start = 0;
            end = Long.MAX_VALUE;
        }

        Batch(Alarm seed) {
            start = seed.whenElapsed;
            end = seed.maxWhen;
            alarms.add(seed);
        }

        int size() {
            return alarms.size();
        }

        Alarm get(int index) {
            return alarms.get(index);
        }

        boolean canHold(long whenElapsed, long maxWhen) {
            return (end >= whenElapsed) && (start <= maxWhen);
        }

        boolean add(Alarm alarm) {
            boolean newStart = false;
            // narrows the batch if necessary; presumes that canHold(alarm) is true
            int index = Collections.binarySearch(alarms, alarm, sIncreasingTimeOrder);
            if (index < 0) {
                index = 0 - index - 1;
            }
            alarms.add(index, alarm);
            if (DEBUG_BATCH) {
                Slog.v(TAG, "Adding " + alarm + " to " + this);
            }
            if (alarm.whenElapsed > start) {
                start = alarm.whenElapsed;
                newStart = true;
            }
            if (alarm.maxWhen < end) {
                end = alarm.maxWhen;
            }

            if (DEBUG_BATCH) {
                Slog.v(TAG, "    => now " + this);
            }
            return newStart;
        }

        boolean remove(final PendingIntent operation) {
            boolean didRemove = false;
            long newStart = 0;  // recalculate endpoints as we go
            long newEnd = Long.MAX_VALUE;
            for (int i = 0; i < alarms.size(); ) {
                Alarm alarm = alarms.get(i);
                if (alarm.operation.equals(operation)) {
                    alarms.remove(i);
                    didRemove = true;
                    if (alarm.alarmClock != null) {
                        mNextAlarmClockMayChange = true;
                    }
                } else {
                    if (alarm.whenElapsed > newStart) {
                        newStart = alarm.whenElapsed;
                    }
                    if (alarm.maxWhen < newEnd) {
                        newEnd = alarm.maxWhen;
                    }
                    i++;
                }
            }
            if (didRemove) {
                // commit the new batch bounds
                start = newStart;
                end = newEnd;
            }
            return didRemove;
        }

        boolean remove(final String packageName) {
            boolean didRemove = false;
            long newStart = 0;  // recalculate endpoints as we go
            long newEnd = Long.MAX_VALUE;
            for (int i = 0; i < alarms.size(); ) {
                Alarm alarm = alarms.get(i);
                if (alarm.operation.getTargetPackage().equals(packageName)) {
                    alarms.remove(i);
                    didRemove = true;
                    if (alarm.alarmClock != null) {
                        mNextAlarmClockMayChange = true;
                    }
                } else {
                    if (alarm.whenElapsed > newStart) {
                        newStart = alarm.whenElapsed;
                    }
                    if (alarm.maxWhen < newEnd) {
                        newEnd = alarm.maxWhen;
                    }
                    i++;
                }
            }
            if (didRemove) {
                // commit the new batch bounds
                start = newStart;
                end = newEnd;
            }
            return didRemove;
        }

        boolean remove(final int userHandle) {
            boolean didRemove = false;
            long newStart = 0;  // recalculate endpoints as we go
            long newEnd = Long.MAX_VALUE;
            for (int i = 0; i < alarms.size(); ) {
                Alarm alarm = alarms.get(i);
                if (UserHandle.getUserId(alarm.operation.getCreatorUid()) == userHandle) {
                    alarms.remove(i);
                    didRemove = true;
                    if (alarm.alarmClock != null) {
                        mNextAlarmClockMayChange = true;
                    }
                } else {
                    if (alarm.whenElapsed > newStart) {
                        newStart = alarm.whenElapsed;
                    }
                    if (alarm.maxWhen < newEnd) {
                        newEnd = alarm.maxWhen;
                    }
                    i++;
                }
            }
            if (didRemove) {
                // commit the new batch bounds
                start = newStart;
                end = newEnd;
            }
            return didRemove;
        }

        boolean hasPackage(final String packageName) {
            final int N = alarms.size();
            for (int i = 0; i < N; i++) {
                Alarm a = alarms.get(i);
                if (a.operation.getTargetPackage().equals(packageName)) {
                    return true;
                }
            }
            return false;
        }

        boolean hasWakeups() {
            final int N = alarms.size();
            for (int i = 0; i < N; i++) {
                Alarm a = alarms.get(i);
                // non-wakeup alarms are types 1 and 3, i.e. have the low bit set
                if ((a.type & TYPE_NONWAKEUP_MASK) == 0) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String toString() {
            StringBuilder b = new StringBuilder(40);
            b.append("Batch{"); b.append(Integer.toHexString(this.hashCode()));
            b.append(" num="); b.append(size());
            b.append(" start="); b.append(start);
            b.append(" end="); b.append(end);
            if (standalone) {
                b.append(" STANDALONE");
            }
            b.append('}');
            return b.toString();
        }
    }

    static class BatchTimeOrder implements Comparator<Batch> {
        public int compare(Batch b1, Batch b2) {
            long when1 = b1.start;
            long when2 = b2.start;
            if (when1 - when2 > 0) {
                return 1;
            }
            if (when1 - when2 < 0) {
                return -1;
            }
            return 0;
        }
    }

    final Comparator<Alarm> mAlarmDispatchComparator = new Comparator<Alarm>() {
        @Override
        public int compare(Alarm lhs, Alarm rhs) {
            // priority class trumps everything.  TICK < WAKEUP < NORMAL
            if (lhs.priorityClass.priority < rhs.priorityClass.priority) {
                return -1;
            } else if (lhs.priorityClass.priority > rhs.priorityClass.priority) {
                return 1;
            }

            // within each class, sort by nominal delivery time
            if (lhs.whenElapsed < rhs.whenElapsed) {
                return -1;
            } else if (lhs.whenElapsed > rhs.whenElapsed) {
                return 1;
            }

            // same priority class + same target delivery time
            return 0;
        }
    };

    void calculateDeliveryPriorities(ArrayList<Alarm> alarms) {
        final int N = alarms.size();
        for (int i = 0; i < N; i++) {
            Alarm a = alarms.get(i);

            final int alarmPrio;
            if (Intent.ACTION_TIME_TICK.equals(a.operation.getIntent().getAction())) {
                alarmPrio = PRIO_TICK;
            } else if (a.wakeup) {
                alarmPrio = PRIO_WAKEUP;
            } else {
                alarmPrio = PRIO_NORMAL;
            }

            PriorityClass packagePrio = a.priorityClass;
            if (packagePrio == null) packagePrio = mPriorities.get(a.operation.getCreatorPackage());
            if (packagePrio == null) {
                packagePrio = a.priorityClass = new PriorityClass(); // lowest prio & stale sequence
                mPriorities.put(a.operation.getCreatorPackage(), packagePrio);
            }
            a.priorityClass = packagePrio;

            if (packagePrio.seq != mCurrentSeq) {
                // first alarm we've seen in the current delivery generation from this package
                packagePrio.priority = alarmPrio;
                packagePrio.seq = mCurrentSeq;
            } else {
                // Multiple alarms from this package being delivered in this generation;
                // bump the package's delivery class if it's warranted.
                // TICK < WAKEUP < NORMAL
                if (alarmPrio < packagePrio.priority) {
                    packagePrio.priority = alarmPrio;
                }
            }
        }
    }

    // minimum recurrence period or alarm futurity for us to be able to fuzz it
    static final long MIN_FUZZABLE_INTERVAL = 10000;
    static final BatchTimeOrder sBatchOrder = new BatchTimeOrder();
    final ArrayList<Batch> mAlarmBatches = new ArrayList<Batch>();

    public AlarmManagerService(Context context) {
        super(context);
    }

    static long convertToElapsed(long when, int type) {
        final boolean isRtc = (type == RTC || type == RTC_WAKEUP);
        if (isRtc) {
            when -= System.currentTimeMillis() - SystemClock.elapsedRealtime();
        }
        return when;
    }

    // Apply a heuristic to { recurrence interval, futurity of the trigger time } to
    // calculate the end of our nominal delivery window for the alarm.
    static long maxTriggerTime(long now, long triggerAtTime, long interval) {
        // Current heuristic: batchable window is 75% of either the recurrence interval
        // [for a periodic alarm] or of the time from now to the desired delivery time,
        // with a minimum delay/interval of 10 seconds, under which we will simply not
        // defer the alarm.
        long futurity = (interval == 0)
                ? (triggerAtTime - now)
                : interval;
        if (futurity < MIN_FUZZABLE_INTERVAL) {
            futurity = 0;
        }
        return triggerAtTime + (long)(.75 * futurity);
    }

    // returns true if the batch was added at the head
    static boolean addBatchLocked(ArrayList<Batch> list, Batch newBatch) {
        int index = Collections.binarySearch(list, newBatch, sBatchOrder);
        if (index < 0) {
            index = 0 - index - 1;
        }
        list.add(index, newBatch);
        return (index == 0);
    }

    // Return the index of the matching batch, or -1 if none found.
    int attemptCoalesceLocked(long whenElapsed, long maxWhen) {
        final int N = mAlarmBatches.size();
        for (int i = 0; i < N; i++) {
            Batch b = mAlarmBatches.get(i);
            if (!b.standalone && b.canHold(whenElapsed, maxWhen)) {
                return i;
            }
        }
        return -1;
    }

    // The RTC clock has moved arbitrarily, so we need to recalculate all the batching
    void rebatchAllAlarms() {
        synchronized (mLock) {
            rebatchAllAlarmsLocked(true);
        }
    }

    void rebatchAllAlarmsLocked(boolean doValidate) {
        ArrayList<Batch> oldSet = (ArrayList<Batch>) mAlarmBatches.clone();
        mAlarmBatches.clear();
        final long nowElapsed = SystemClock.elapsedRealtime();
        final int oldBatches = oldSet.size();
        Slog.d(TAG, "rebatchAllAlarmsLocked begin oldBatches count = " + oldBatches );
        for (int batchNum = 0; batchNum < oldBatches; batchNum++) {
            Batch batch = oldSet.get(batchNum);
            final int N = batch.size();
            Slog.d(TAG, "rebatchAllAlarmsLocked  batch.size() = " + batch.size());
            for (int i = 0; i < N; i++) {
                Alarm a = batch.get(i);
                long whenElapsed = convertToElapsed(a.when, a.type);
                final long maxElapsed;
                if (a.whenElapsed == a.maxWhen) {
                    // Exact
                    maxElapsed = whenElapsed;
                } else {
                    // Not exact.  Preserve any explicit window, otherwise recalculate
                    // the window based on the alarm's new futurity.  Note that this
                    // reflects a policy of preferring timely to deferred delivery.
                    if(SystemProperties.get("ro.mtk_bg_power_saving_support").equals("1") && (mAmPlus != null)) {
                    // M: BG powerSaving feature
                        maxElapsed = mAmPlus.getMaxTriggerTime(a.type, whenElapsed, a.windowLength,
                        a.repeatInterval, a.operation);
                    } else {
                        maxElapsed = (a.windowLength > 0)
                                ? (whenElapsed + a.windowLength)
                                : maxTriggerTime(nowElapsed, whenElapsed, a.repeatInterval);
                    }
                }
                setImplLocked(a.type, a.when, whenElapsed, a.windowLength, maxElapsed,
                        a.repeatInterval, a.operation, batch.standalone, doValidate, a.workSource,
                        a.alarmClock, a.userId);
            }
        }
        Slog.d(TAG, "rebatchAllAlarmsLocked end");
    }

    // /M:add for IPO and powerOffAlarm feature ,@{
    private Object mWaitThreadlock = new Object();
    private boolean mIPOShutdown = false;
    private Object mPowerOffAlarmLock = new Object();
    private final ArrayList<Alarm> mPoweroffAlarms = new ArrayList<Alarm>();
    // /@}


    static final class InFlight extends Intent {
        final PendingIntent mPendingIntent;
        final WorkSource mWorkSource;
        final String mTag;
        final BroadcastStats mBroadcastStats;
        final FilterStats mFilterStats;
        final int mAlarmType;

        InFlight(AlarmManagerService service, PendingIntent pendingIntent, WorkSource workSource,
                int alarmType, String tag) {
            mPendingIntent = pendingIntent;
            mWorkSource = workSource;
            mTag = tag;
            mBroadcastStats = service.getStatsLocked(pendingIntent);
            FilterStats fs = mBroadcastStats.filterStats.get(mTag);
            if (fs == null) {
                fs = new FilterStats(mBroadcastStats, mTag);
                mBroadcastStats.filterStats.put(mTag, fs);
            }
            mFilterStats = fs;
            mAlarmType = alarmType;
        }
    }

    static final class FilterStats {
        final BroadcastStats mBroadcastStats;
        final String mTag;

        long aggregateTime;
        int count;
        int numWakeup;
        long startTime;
        int nesting;

        FilterStats(BroadcastStats broadcastStats, String tag) {
            mBroadcastStats = broadcastStats;
            mTag = tag;
        }
    }
    
    static final class BroadcastStats {
        final int mUid;
        final String mPackageName;

        long aggregateTime;
        int count;
        int numWakeup;
        long startTime;
        int nesting;
        final ArrayMap<String, FilterStats> filterStats = new ArrayMap<String, FilterStats>();

        BroadcastStats(int uid, String packageName) {
            mUid = uid;
            mPackageName = packageName;
        }
    }
    
    final SparseArray<ArrayMap<String, BroadcastStats>> mBroadcastStats
            = new SparseArray<ArrayMap<String, BroadcastStats>>();

    int mNumDelayedAlarms = 0;
    long mTotalDelayTime = 0;
    long mMaxDelayTime = 0;

    @Override
    public void onStart() {
        mNativeData = init();
        mNextWakeup = mNextNonWakeup = 0;

        // We have to set current TimeZone info to kernel
        // because kernel doesn't keep this after reboot
        setTimeZoneImpl(SystemProperties.get(TIMEZONE_PROPERTY));

        /// M: BG powerSaving feature start @{

        if(SystemProperties.get("ro.mtk_bg_power_saving_support").equals("1") && (mAmPlus != null)) {
            try {
                    mAmPlus = new AlarmManagerPlus(getContext());
                } catch (Exception e) {
                    e.printStackTrace();
                }
        }
        /// M: BG powerSaving feature end @}

        PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "*alarm*");

        mTimeTickSender = PendingIntent.getBroadcastAsUser(getContext(), 0,
                new Intent(Intent.ACTION_TIME_TICK).addFlags(
                        Intent.FLAG_RECEIVER_REGISTERED_ONLY
                        | Intent.FLAG_RECEIVER_FOREGROUND), 0,
                        UserHandle.ALL);
        Intent intent = new Intent(Intent.ACTION_DATE_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        mDateChangeSender = PendingIntent.getBroadcastAsUser(getContext(), 0, intent,
                Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT, UserHandle.ALL);
        
        // now that we have initied the driver schedule the alarm
        mClockReceiver = new ClockReceiver();
        mClockReceiver.scheduleTimeTickEvent();
        mClockReceiver.scheduleDateChangedEvent();
        mInteractiveStateReceiver = new InteractiveStateReceiver();
        mUninstallReceiver = new UninstallReceiver();

        mAlarmIconPackageList = new ArrayList<String>();
        mAlarmIconPackageList.add("com.android.deskclock");
        // /M:add for DM feature ,@{
        try {
            IBinder binder = ServiceManager.getService("DmAgent");
            if (binder != null) {
                DmAgent agent = DmAgent.Stub.asInterface(binder);
                boolean locked = agent.isLockFlagSet();
                Slog.i(TAG, "dm state lock is " + locked);
                mDMEnable = !locked;
            } else {
                Slog.e(TAG, "dm binder is null!");
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "remote error");
        }
        mDMReceiver = new DMReceiver();
        mDmFreeList = new ArrayList<PendingIntent>();
        mDmFreeList.add(mTimeTickSender);
        mDmFreeList.add(mDateChangeSender);
        mDmResendList = new ArrayList<Alarm>();
        // /@}

        
        if (mNativeData != 0) {
            AlarmThread waitThread = new AlarmThread();
            waitThread.start();
        } else {
            Slog.w(TAG, "Failed to open alarm driver. Falling back to a handler.");
        }

        // /M:add for IPO and PoerOffAlarm feature ,@{
        if (SystemProperties.get("ro.mtk_ipo_support").equals("1")) {
            IntentFilter filter = new IntentFilter();
            filter.addAction("android.intent.action.ACTION_BOOT_IPO");
            filter.addAction("android.intent.action.ACTION_SHUTDOWN");
            filter.addAction("android.intent.action.ACTION_SHUTDOWN_IPO");
            getContext().registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if ("android.intent.action.ACTION_SHUTDOWN".equals(intent.getAction())
                            || "android.intent.action.ACTION_SHUTDOWN_IPO".equals(intent
                                    .getAction())) {
                        shutdownCheckPoweroffAlarm();
                        mIPOShutdown = true;
                        if (mNativeData != -1 && "android.intent.action.ACTION_SHUTDOWN_IPO".equals(intent
                                    .getAction())) {
                            Log.d(TAG, "receive ACTION_SHUTDOWN_IPO , so close the fd ");
                            close(mNativeData);
                            mNativeData = -1;
                        }
                        /*set(ELAPSED_REALTIME, 100, 0, 0, PendingIntent.getBroadcast(context,
                                0,
                                new Intent(Intent.ACTION_TIME_TICK), 0), null); // whatever. */
                    } else if ("android.intent.action.ACTION_BOOT_IPO".equals(intent.getAction())) {
                        mIPOShutdown = false;
                        mNativeData = init();
                        //Slog.i(TAG, "ipo mNativeData is " + Integer.toString(mNativeData));

                        Intent timeChangeIntent = new Intent(Intent.ACTION_TIME_CHANGED);
                        timeChangeIntent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
                        context.sendBroadcast(timeChangeIntent);

                        mClockReceiver.scheduleTimeTickEvent();
                        mClockReceiver.scheduleDateChangedEvent();
                        synchronized (mWaitThreadlock) {
                            mWaitThreadlock.notify();
                        }
                    }
                }
            }, filter);
        }
        // /@}

        publishBinderService(Context.ALARM_SERVICE, mService);
    }

    /**
     *This API for app to get the boot reason
     */
    public boolean bootFromPoweroffAlarm() {
        String bootReason = SystemProperties.get("sys.boot.reason");
        boolean ret = (bootReason != null && bootReason.equals("1")) ? true : false;
        return ret;
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            close(mNativeData);
        } finally {
            super.finalize();
        }
    }

    void setTimeZoneImpl(String tz) {
        if (TextUtils.isEmpty(tz)) {
            return;
        }

        TimeZone zone = TimeZone.getTimeZone(tz);
        // Prevent reentrant calls from stepping on each other when writing
        // the time zone property
        boolean timeZoneWasChanged = false;
        synchronized (this) {
            String current = SystemProperties.get(TIMEZONE_PROPERTY);
            if (current == null || !current.equals(zone.getID())) {
                if (localLOGV) {
                    Slog.v(TAG, "timezone changed: " + current + ", new=" + zone.getID());
                }
                timeZoneWasChanged = true;
                SystemProperties.set(TIMEZONE_PROPERTY, zone.getID());
            }

            // Update the kernel timezone information
            // Kernel tracks time offsets as 'minutes west of GMT'
            int gmtOffset = zone.getOffset(System.currentTimeMillis());
            setKernelTimezone(mNativeData, -(gmtOffset / 60000));
        }

        TimeZone.setDefault(null);

        if (timeZoneWasChanged) {
            Intent intent = new Intent(Intent.ACTION_TIMEZONE_CHANGED);
            intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
            intent.putExtra("time-zone", zone.getID());
            getContext().sendBroadcastAsUser(intent, UserHandle.ALL);
        }
    }

    void removeImpl(PendingIntent operation) {
        if (operation == null) {
            return;
        }
        synchronized (mLock) {
            removeLocked(operation);
        }
    }

    void setImpl(int type, long triggerAtTime, long windowLength, long interval,
            PendingIntent operation, boolean isStandalone, WorkSource workSource,
            AlarmManager.AlarmClockInfo alarmClock) {
        if (operation == null) {
            Slog.w(TAG, "set/setRepeating ignored because there is no intent");
            return;
        }

        // /M:add for IPO,when shut down,do not set alarm to driver ,@{
        if (mIPOShutdown && (mNativeData == -1)) {
            Slog.w(TAG, "IPO Shutdown so drop the alarm");
            return;
        }
        // /@}

        // Sanity check the window length.  This will catch people mistakenly
        // trying to pass an end-of-window timestamp rather than a duration.
        if (windowLength > AlarmManager.INTERVAL_HALF_DAY) {
            Slog.w(TAG, "Window length " + windowLength
                    + "ms suspiciously long; limiting to 1 hour");
            windowLength = AlarmManager.INTERVAL_HOUR;
        }


        if (triggerAtTime < 0) {
            final long who = Binder.getCallingUid();
            final long what = Binder.getCallingPid();
            Slog.w(TAG, "Invalid alarm trigger time! " + triggerAtTime + " from uid=" + who
                    + " pid=" + what);
            triggerAtTime = 0;
        }

        // /M:add for PowerOffAlarm feature type 7 for seetings,type 8 for
        // deskcolck ,@{
        if (type == 7 || type == 8) {
            if (mNativeData == -1) {
                Slog.w(TAG, "alarm driver not open ,return!");
                return;
            }

            Slog.d(TAG, "alarm set type 7 8, package name " + operation.getTargetPackage());
            String packageName = operation.getTargetPackage();

            String setPackageName = null;
            long nowTime = System.currentTimeMillis();
            if (triggerAtTime < nowTime) {
                Slog.w(TAG, "power off alarm set time is wrong!");
                return;
            }

            synchronized (mPowerOffAlarmLock) {
                removePoweroffAlarmLocked(operation.getTargetPackage());
                final int poweroffAlarmUserId = UserHandle.getCallingUserId();
                Alarm alarm = new Alarm(type, triggerAtTime, 0, 0, 0, interval, operation, workSource, alarmClock, poweroffAlarmUserId);
                addPoweroffAlarmLocked(alarm);
                if (mPoweroffAlarms.size() > 0) {
                    resetPoweroffAlarm(mPoweroffAlarms.get(0));
                }
            }
                type = RTC_WAKEUP;

        }
        // /@}

        final long nowElapsed = SystemClock.elapsedRealtime();
        final long triggerElapsed = convertToElapsed(triggerAtTime, type);
        final long maxElapsed;
        if(SystemProperties.get("ro.mtk_bg_power_saving_support").equals("1") && (mAmPlus != null)) {
            // M: BG powerSaving feature
            maxElapsed = mAmPlus.getMaxTriggerTime(type, triggerElapsed, windowLength, interval, operation);
        } else if (windowLength == AlarmManager.WINDOW_EXACT) {
            maxElapsed = triggerElapsed;
        } else if (windowLength < 0) {
            maxElapsed = maxTriggerTime(nowElapsed, triggerElapsed, interval);
        } else {
            maxElapsed = triggerElapsed + windowLength;
        }

        final int userId = UserHandle.getCallingUserId();

        synchronized (mLock) {
            if (DEBUG_BATCH) {
                Slog.v(TAG, "set(" + operation + ") : type=" + type
                        + " triggerAtTime=" + triggerAtTime + " win=" + windowLength
                        + " tElapsed=" + triggerElapsed + " maxElapsed=" + maxElapsed
                        + " interval=" + interval + " standalone=" + isStandalone);
            }
            setImplLocked(type, triggerAtTime, triggerElapsed, windowLength, maxElapsed,
                    interval, operation, isStandalone, true, workSource, alarmClock, userId);
        }
    }

    private void setImplLocked(int type, long when, long whenElapsed, long windowLength,
            long maxWhen, long interval, PendingIntent operation, boolean isStandalone,
            boolean doValidate, WorkSource workSource, AlarmManager.AlarmClockInfo alarmClock,
            int userId) {
        Alarm a = new Alarm(type, when, whenElapsed, windowLength, maxWhen, interval,
                operation, workSource, alarmClock, userId);
        removeLocked(operation);

        int whichBatch = (isStandalone) ? -1 : attemptCoalesceLocked(whenElapsed, maxWhen);
        if (whichBatch < 0) {
            Batch batch = new Batch(a);
            batch.standalone = isStandalone;
            addBatchLocked(mAlarmBatches, batch);
        } else {
            Batch batch = mAlarmBatches.get(whichBatch);
            if (batch.add(a)) {
                // The start time of this batch advanced, so batch ordering may
                // have just been broken.  Move it to where it now belongs.
                mAlarmBatches.remove(whichBatch);
                addBatchLocked(mAlarmBatches, batch);
            }
        }

        if (alarmClock != null) {
            mNextAlarmClockMayChange = true;
            updateNextAlarmClockLocked();
        }

        if (DEBUG_VALIDATE) {
            if (doValidate && !validateConsistencyLocked()) {
                Slog.v(TAG, "Tipping-point operation: type=" + type + " when=" + when
                        + " when(hex)=" + Long.toHexString(when)
                        + " whenElapsed=" + whenElapsed + " maxWhen=" + maxWhen
                        + " interval=" + interval + " op=" + operation
                        + " standalone=" + isStandalone);
                rebatchAllAlarmsLocked(false);
            }
        }

        rescheduleKernelAlarmsLocked();
    }

    private final IBinder mService = new IAlarmManager.Stub() {
        @Override
        public void set(int type, long triggerAtTime, long windowLength, long interval,
                PendingIntent operation, WorkSource workSource,
                AlarmManager.AlarmClockInfo alarmClock) {
            if (workSource != null) {
                getContext().enforceCallingPermission(
                        android.Manifest.permission.UPDATE_DEVICE_STATS,
                        "AlarmManager.set");
            }

            setImpl(type, triggerAtTime, windowLength, interval, operation,
                    false, workSource, alarmClock);
        }

        @Override
        public boolean setTime(long millis) {
            getContext().enforceCallingOrSelfPermission(
                    "android.permission.SET_TIME",
                    "setTime");

            if (mNativeData == 0 || mNativeData == -1) {
                Slog.w(TAG, "Not setting time since no alarm driver is available.");
                return false;
            }

            synchronized (mLock) {
                return setKernelTime(mNativeData, millis) == 0;
            }
        }

        @Override
        public void setTimeZone(String tz) {
            getContext().enforceCallingOrSelfPermission(
                    "android.permission.SET_TIME_ZONE",
                    "setTimeZone");

            final long oldId = Binder.clearCallingIdentity();
            try {
                setTimeZoneImpl(tz);
            } finally {
                Binder.restoreCallingIdentity(oldId);
            }
        }

        @Override
        public void remove(PendingIntent operation) {
            removeImpl(operation);

        }

        @Override
        public void cancelPoweroffAlarm(String name) {
            cancelPoweroffAlarmImpl(name);

        }

    @Override
    public void removeFromAms(String packageName) {
            removeFromAmsImpl(packageName);
    }

    @Override
    public boolean lookForPackageFromAms(String packageName) {
            return lookForPackageFromAmsImpl(packageName);
    }

        @Override
        public AlarmManager.AlarmClockInfo getNextAlarmClock(int userId) {

            userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, false /* allowAll */, false /* requireFull */,
                    "getNextAlarmClock", null);

            return getNextAlarmClockImpl(userId);
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (getContext().checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                    != PackageManager.PERMISSION_GRANTED) {
                pw.println("Permission Denial: can't dump AlarmManager from from pid="
                        + Binder.getCallingPid()
                        + ", uid=" + Binder.getCallingUid());
                return;
            }

            dumpImpl(pw);
        }
    };

     /// M:Add dynamic enable alarmManager log @{
    protected void configLogTag(PrintWriter pw, String[] args, int opti) {

        if (opti >= args.length) {
            pw.println("  Invalid argument!");
        } else {
            if ("on".equals(args[opti])) {
                localLOGV = true;
                DEBUG_BATCH = true;
                DEBUG_VALIDATE = true;
            } else if ("off".equals(args[opti])) {
                localLOGV = false;
                DEBUG_BATCH = false;
                DEBUG_VALIDATE = false;
            } else {
                pw.println("  Invalid argument!");
            }
        }
    }
    /// @}


    void dumpImpl(PrintWriter pw) {
    /// M: Dynamically enable alarmManager logs @{
    int opti = 0;
    /*while (opti < args.length) {
             String opt = args[opti];
             if (opt == null || opt.length() <= 0 || opt.charAt(0) != '-') {
        break;
             }
             opti++;
             if ("-h".equals(opt)) {
                 pw.println("alarm manager dump options:");
                 pw.println("  log  [on/off]");
                 pw.println("  Example:");
                 pw.println("  $adb shell dumpsys alarm log on");
                 pw.println("  $adb shell dumpsys alarm log off");
                 return;
             } else {
                 pw.println("Unknown argument: " + opt + "; use -h for help");
             }
        }

        if (opti < args.length) {
            String cmd = args[opti];
            opti++;
            if ("log".equals(cmd)) {
                configLogTag(pw, args, opti);
                return;
            }
        }
        /// @}
        synchronized (mLock) {
            pw.println("Current Alarm Manager state:");
            final long nowRTC = System.currentTimeMillis();
            final long nowELAPSED = SystemClock.elapsedRealtime();
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

            pw.print("nowRTC="); pw.print(nowRTC);
            pw.print("="); pw.print(sdf.format(new Date(nowRTC)));
            pw.print(" nowELAPSED="); TimeUtils.formatDuration(nowELAPSED, pw);
            pw.println();
            if (!mInteractive) {
                pw.print("Time since non-interactive: ");
                TimeUtils.formatDuration(nowELAPSED - mNonInteractiveStartTime, pw);
                pw.println();
                pw.print("Max wakeup delay: ");
                TimeUtils.formatDuration(currentNonWakeupFuzzLocked(nowELAPSED), pw);
                pw.println();
                pw.print("Time since last dispatch: ");
                TimeUtils.formatDuration(nowELAPSED - mLastAlarmDeliveryTime, pw);
                pw.println();
                pw.print("Next non-wakeup delivery time: ");
                TimeUtils.formatDuration(nowELAPSED - mNextNonWakeupDeliveryTime, pw);
                pw.println();
            }

            long nextWakeupRTC = mNextWakeup + (nowRTC - nowELAPSED);
            long nextNonWakeupRTC = mNextNonWakeup + (nowRTC - nowELAPSED);
            pw.print("Next non-wakeup alarm: ");
                    TimeUtils.formatDuration(mNextNonWakeup, nowELAPSED, pw);
                    pw.print(" = "); pw.println(sdf.format(new Date(nextNonWakeupRTC)));
            pw.print("Next wakeup: "); TimeUtils.formatDuration(mNextWakeup, nowELAPSED, pw);
                    pw.print(" = "); pw.println(sdf.format(new Date(nextWakeupRTC)));
            pw.print("Num time change events: "); pw.println(mNumTimeChanged);

            if (mAlarmBatches.size() > 0) {
                pw.println();
                pw.print("Pending alarm batches: ");
                pw.println(mAlarmBatches.size());
                for (Batch b : mAlarmBatches) {
                    pw.print(b); pw.println(':');
                    dumpAlarmList(pw, b.alarms, "  ", nowELAPSED, nowRTC, sdf);
                }
            }

            pw.println();
            pw.print("Past-due non-wakeup alarms: ");
            if (mPendingNonWakeupAlarms.size() > 0) {
                pw.println(mPendingNonWakeupAlarms.size());
                dumpAlarmList(pw, mPendingNonWakeupAlarms, "  ", nowELAPSED, nowRTC, sdf);
            } else {
                pw.println("(none)");
            }
            pw.print("  Number of delayed alarms: "); pw.print(mNumDelayedAlarms);
            pw.print(", total delay time: "); TimeUtils.formatDuration(mTotalDelayTime, pw);
            pw.println();
            pw.print("  Max delay time: "); TimeUtils.formatDuration(mMaxDelayTime, pw);
            pw.print(", max non-interactive time: ");
            TimeUtils.formatDuration(mNonInteractiveTime, pw);
            pw.println();

            pw.println();
            pw.print("  Broadcast ref count: "); pw.println(mBroadcastRefCount);
            pw.println();

            if (mLog.dump(pw, "  Recent problems", "    ")) {
                pw.println();
            }

            final FilterStats[] topFilters = new FilterStats[10];
            final Comparator<FilterStats> comparator = new Comparator<FilterStats>() {
                @Override
                public int compare(FilterStats lhs, FilterStats rhs) {
                    if (lhs.aggregateTime < rhs.aggregateTime) {
                        return 1;
                    } else if (lhs.aggregateTime > rhs.aggregateTime) {
                        return -1;
                    }
                    return 0;
                }
            };
            int len = 0;
            for (int iu=0; iu<mBroadcastStats.size(); iu++) {
                ArrayMap<String, BroadcastStats> uidStats = mBroadcastStats.valueAt(iu);
                for (int ip=0; ip<uidStats.size(); ip++) {
                    BroadcastStats bs = uidStats.valueAt(ip);
                    for (int is=0; is<bs.filterStats.size(); is++) {
                        FilterStats fs = bs.filterStats.valueAt(is);
                        int pos = len > 0
                                ? Arrays.binarySearch(topFilters, 0, len, fs, comparator) : 0;
                        if (pos < 0) {
                            pos = -pos - 1;
                        }
                        if (pos < topFilters.length) {
                            int copylen = topFilters.length - pos - 1;
                            if (copylen > 0) {
                                System.arraycopy(topFilters, pos, topFilters, pos+1, copylen);
                            }
                            topFilters[pos] = fs;
                            if (len < topFilters.length) {
                                len++;
                            }
                        }
                    }
                }
            }
            if (len > 0) {
                pw.println("  Top Alarms:");
                for (int i=0; i<len; i++) {
                    FilterStats fs = topFilters[i];
                    pw.print("    ");
                    if (fs.nesting > 0) pw.print("*ACTIVE* ");
                    TimeUtils.formatDuration(fs.aggregateTime, pw);
                    pw.print(" running, "); pw.print(fs.numWakeup);
                    pw.print(" wakeups, "); pw.print(fs.count);
                    pw.print(" alarms: "); UserHandle.formatUid(pw, fs.mBroadcastStats.mUid);
                    pw.print(":"); pw.print(fs.mBroadcastStats.mPackageName);
                    pw.println();
                    pw.print("      "); pw.print(fs.mTag);
                    pw.println();
                }
            }

            pw.println(" ");
            pw.println("  Alarm Stats:");
            final ArrayList<FilterStats> tmpFilters = new ArrayList<FilterStats>();
            for (int iu=0; iu<mBroadcastStats.size(); iu++) {
                ArrayMap<String, BroadcastStats> uidStats = mBroadcastStats.valueAt(iu);
                for (int ip=0; ip<uidStats.size(); ip++) {
                    BroadcastStats bs = uidStats.valueAt(ip);
                    pw.print("  ");
                    if (bs.nesting > 0) pw.print("*ACTIVE* ");
                    UserHandle.formatUid(pw, bs.mUid);
                    pw.print(":");
                    pw.print(bs.mPackageName);
                    pw.print(" "); TimeUtils.formatDuration(bs.aggregateTime, pw);
                            pw.print(" running, "); pw.print(bs.numWakeup);
                            pw.println(" wakeups:");
                    tmpFilters.clear();
                    for (int is=0; is<bs.filterStats.size(); is++) {
                        tmpFilters.add(bs.filterStats.valueAt(is));
                    }
                    Collections.sort(tmpFilters, comparator);
                    for (int i=0; i<tmpFilters.size(); i++) {
                        FilterStats fs = tmpFilters.get(i);
                        pw.print("    ");
                                if (fs.nesting > 0) pw.print("*ACTIVE* ");
                                TimeUtils.formatDuration(fs.aggregateTime, pw);
                                pw.print(" "); pw.print(fs.numWakeup);
                                pw.print(" wakes " ); pw.print(fs.count);
                                pw.print(" alarms: ");
                                pw.print(fs.mTag);
                                pw.println();
                    }
                }
            }

            if (WAKEUP_STATS) {
                pw.println();
                pw.println("  Recent Wakeup History:");
                long last = -1;
                for (WakeupEvent event : mRecentWakeups) {
                    pw.print("    "); pw.print(sdf.format(new Date(event.when)));
                    pw.print('|');
                    if (last < 0) {
                        pw.print('0');
                    } else {
                        pw.print(event.when - last);
                    }
                    last = event.when;
                    pw.print('|'); pw.print(event.uid);
                    pw.print('|'); pw.print(event.action);
                    pw.println();
                }
                pw.println();
            }
            // /M:add for PowerOffAlarm feature ,@{
             if (mPoweroffAlarms.size() > 0) {
                final long now = System.currentTimeMillis();
                pw.println(" ");
                pw.print("  power off alarms dump (now=");
                        pw.print(sdf.format(new Date(now))); pw.println("):");
                dumpAlarmList(pw, mPoweroffAlarms, "  ", "PoweroffAlarms", now);

            }
            // /@}
        }*/
    }

    private void logBatchesLocked(SimpleDateFormat sdf) {
        ByteArrayOutputStream bs = new ByteArrayOutputStream(2048);
        PrintWriter pw = new PrintWriter(bs);
        final long nowRTC = System.currentTimeMillis();
        final long nowELAPSED = SystemClock.elapsedRealtime();
        final int NZ = mAlarmBatches.size();
        for (int iz = 0; iz < NZ; iz++) {
            Batch bz = mAlarmBatches.get(iz);
            pw.append("Batch "); pw.print(iz); pw.append(": "); pw.println(bz);
            dumpAlarmList(pw, bz.alarms, "  ", nowELAPSED, nowRTC, sdf);
            pw.flush();
            Slog.v(TAG, bs.toString());
            bs.reset();
        }
    }

    private boolean validateConsistencyLocked() {
        if (DEBUG_VALIDATE) {
            long lastTime = Long.MIN_VALUE;
            final int N = mAlarmBatches.size();
            for (int i = 0; i < N; i++) {
                Batch b = mAlarmBatches.get(i);
                if (b.start >= lastTime) {
                    // duplicate start times are okay because of standalone batches
                    lastTime = b.start;
                } else {
                    Slog.e(TAG, "CONSISTENCY FAILURE: Batch " + i + " is out of order");
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                    logBatchesLocked(sdf);
                    return false;
                }
            }
        }
        return true;
    }

    private Batch findFirstWakeupBatchLocked() {
        final int N = mAlarmBatches.size();
        for (int i = 0; i < N; i++) {
            Batch b = mAlarmBatches.get(i);
            if (b.hasWakeups()) {
                return b;
            }
        }
        return null;
    }

    private AlarmManager.AlarmClockInfo getNextAlarmClockImpl(int userId) {
        synchronized (mLock) {
            return mNextAlarmClockForUser.get(userId);
        }
    }

    /**
     * Recomputes the next alarm clock for all users.
     */
    private void updateNextAlarmClockLocked() {
        if (!mNextAlarmClockMayChange) {
            return;
        }
        mNextAlarmClockMayChange = false;

        SparseArray<AlarmManager.AlarmClockInfo> nextForUser = mTmpSparseAlarmClockArray;
        nextForUser.clear();

        final int N = mAlarmBatches.size();
        for (int i = 0; i < N; i++) {
            ArrayList<Alarm> alarms = mAlarmBatches.get(i).alarms;
            final int M = alarms.size();

            for (int j = 0; j < M; j++) {
                Alarm a = alarms.get(j);
                if (a.alarmClock != null) {
                    final int userId = a.userId;

                    if (DEBUG_ALARM_CLOCK) {
                        Log.v(TAG, "Found AlarmClockInfo at " +
                                formatNextAlarm(getContext(), a.alarmClock) +
                                " for user " + userId);
                    }

                    // Alarms and batches are sorted by time, no need to compare times here.
                    if (nextForUser.get(userId) == null) {
                        nextForUser.put(userId, a.alarmClock);
                    }
                }
            }
        }

        // Update mNextAlarmForUser with new values.
        final int NN = nextForUser.size();
        for (int i = 0; i < NN; i++) {
            AlarmManager.AlarmClockInfo newAlarm = nextForUser.valueAt(i);
            int userId = nextForUser.keyAt(i);
            AlarmManager.AlarmClockInfo currentAlarm = mNextAlarmClockForUser.get(userId);
            if (!newAlarm.equals(currentAlarm)) {
                updateNextAlarmInfoForUserLocked(userId, newAlarm);
            }
        }

        // Remove users without any alarm clocks scheduled.
        final int NNN = mNextAlarmClockForUser.size();
        for (int i = NNN - 1; i >= 0; i--) {
            int userId = mNextAlarmClockForUser.keyAt(i);
            if (nextForUser.get(userId) == null) {
                updateNextAlarmInfoForUserLocked(userId, null);
            }
        }
    }

    private void updateNextAlarmInfoForUserLocked(int userId,
            AlarmManager.AlarmClockInfo alarmClock) {
        if (alarmClock != null) {
            if (DEBUG_ALARM_CLOCK) {
                Log.v(TAG, "Next AlarmClockInfoForUser(" + userId + "): " +
                        formatNextAlarm(getContext(), alarmClock));
            }
            mNextAlarmClockForUser.put(userId, alarmClock);
        } else {
            if (DEBUG_ALARM_CLOCK) {
                Log.v(TAG, "Next AlarmClockInfoForUser(" + userId + "): None");
            }
            mNextAlarmClockForUser.remove(userId);
        }

        mPendingSendNextAlarmClockChangedForUser.put(userId, true);
        mHandler.removeMessages(AlarmHandler.SEND_NEXT_ALARM_CLOCK_CHANGED);
        mHandler.sendEmptyMessage(AlarmHandler.SEND_NEXT_ALARM_CLOCK_CHANGED);
    }

    /**
     * Updates NEXT_ALARM_FORMATTED and sends NEXT_ALARM_CLOCK_CHANGED_INTENT for all users
     * for which alarm clocks have changed since the last call to this.
     *
     * Do not call with a lock held. Only call from mHandler's thread.
     *
     * @see AlarmHandler#SEND_NEXT_ALARM_CLOCK_CHANGED
     */
    private void sendNextAlarmClockChanged() {
        SparseArray<AlarmManager.AlarmClockInfo> pendingUsers = mHandlerSparseAlarmClockArray;
        pendingUsers.clear();

        synchronized (mLock) {
            final int N  = mPendingSendNextAlarmClockChangedForUser.size();
            for (int i = 0; i < N; i++) {
                int userId = mPendingSendNextAlarmClockChangedForUser.keyAt(i);
                pendingUsers.append(userId, mNextAlarmClockForUser.get(userId));
            }
            mPendingSendNextAlarmClockChangedForUser.clear();
        }

        final int N = pendingUsers.size();
        for (int i = 0; i < N; i++) {
            int userId = pendingUsers.keyAt(i);
            AlarmManager.AlarmClockInfo alarmClock = pendingUsers.valueAt(i);
            Settings.System.putStringForUser(getContext().getContentResolver(),
                    Settings.System.NEXT_ALARM_FORMATTED,
                    formatNextAlarm(getContext(), alarmClock),
                    userId);

            getContext().sendBroadcastAsUser(NEXT_ALARM_CLOCK_CHANGED_INTENT,
                    new UserHandle(userId));
        }
    }

    /**
     * Formats an alarm like platform/packages/apps/DeskClock used to.
     */
    private static String formatNextAlarm(final Context context, AlarmManager.AlarmClockInfo info) {
        String skeleton = DateFormat.is24HourFormat(context) ? "EHm" : "Ehma";
        String pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton);
        return (info == null) ? "" :
                DateFormat.format(pattern, info.getTriggerTime()).toString();
    }

    void rescheduleKernelAlarmsLocked() {
        // Schedule the next upcoming wakeup alarm.  If there is a deliverable batch
        // prior to that which contains no wakeups, we schedule that as well.

    // /M:add for IPO feature,do not set alarm when shut down,@{
    if (mIPOShutdown && (mNativeData == -1)) {
        Slog.w(TAG, "IPO Shutdown so drop the repeating alarm");
        return;
    }
    // /@}

        long nextNonWakeup = 0;
        if (mAlarmBatches.size() > 0) {
            final Batch firstWakeup = findFirstWakeupBatchLocked();
            final Batch firstBatch = mAlarmBatches.get(0);
            if (firstWakeup != null && mNextWakeup != firstWakeup.start) {
                mNextWakeup = firstWakeup.start;
                setLocked(ELAPSED_REALTIME_WAKEUP, firstWakeup.start);
            }
            if (firstBatch != firstWakeup) {
                nextNonWakeup = firstBatch.start;
            }
        }
        if (mPendingNonWakeupAlarms.size() > 0) {
            if (nextNonWakeup == 0 || mNextNonWakeupDeliveryTime < nextNonWakeup) {
                nextNonWakeup = mNextNonWakeupDeliveryTime;
            }
        }
        if (nextNonWakeup != 0 && mNextNonWakeup != nextNonWakeup) {
            mNextNonWakeup = nextNonWakeup;
            setLocked(ELAPSED_REALTIME, nextNonWakeup);
        }
    }

    private void removeLocked(PendingIntent operation) {
        boolean didRemove = false;
        for (int i = mAlarmBatches.size() - 1; i >= 0; i--) {
            Batch b = mAlarmBatches.get(i);
            didRemove |= b.remove(operation);
            if (b.size() == 0) {
                mAlarmBatches.remove(i);
            }
        }

        if (didRemove) {
            if (true) {
                Slog.d(TAG, "remove(operation) changed bounds; rebatching");
            }
            rebatchAllAlarmsLocked(true);
            rescheduleKernelAlarmsLocked();
            updateNextAlarmClockLocked();
        }
    }

    void removeLocked(String packageName) {
        boolean didRemove = false;
        for (int i = mAlarmBatches.size() - 1; i >= 0; i--) {
            Batch b = mAlarmBatches.get(i);
            didRemove |= b.remove(packageName);
            if (b.size() == 0) {
                mAlarmBatches.remove(i);
            }
        }

        if (didRemove) {
            if (true) {
                Slog.v(TAG, "remove(package) changed bounds; rebatching");
            }
            rebatchAllAlarmsLocked(true);
            rescheduleKernelAlarmsLocked();
            updateNextAlarmClockLocked();
        }
    }

    void removeUserLocked(int userHandle) {
        boolean didRemove = false;
        for (int i = mAlarmBatches.size() - 1; i >= 0; i--) {
            Batch b = mAlarmBatches.get(i);
            didRemove |= b.remove(userHandle);
            if (b.size() == 0) {
                mAlarmBatches.remove(i);
            }
        }

        if (didRemove) {
            if (DEBUG_BATCH) {
                Slog.v(TAG, "remove(user) changed bounds; rebatching");
            }
            rebatchAllAlarmsLocked(true);
            rescheduleKernelAlarmsLocked();
            updateNextAlarmClockLocked();
        }
    }

    void interactiveStateChangedLocked(boolean interactive) {
        if (mInteractive != interactive) {
            mInteractive = interactive;
            final long nowELAPSED = SystemClock.elapsedRealtime();
            if (interactive) {
                if (mPendingNonWakeupAlarms.size() > 0) {
                    final long thisDelayTime = nowELAPSED - mStartCurrentDelayTime;
                    mTotalDelayTime += thisDelayTime;
                    if (mMaxDelayTime < thisDelayTime) {
                        mMaxDelayTime = thisDelayTime;
                    }
                    deliverAlarmsLocked(mPendingNonWakeupAlarms, nowELAPSED);
                    mPendingNonWakeupAlarms.clear();
                }
                if (mNonInteractiveStartTime > 0) {
                    long dur = nowELAPSED - mNonInteractiveStartTime;
                    if (dur > mNonInteractiveTime) {
                        mNonInteractiveTime = dur;
                    }
                }
            } else {
                mNonInteractiveStartTime = nowELAPSED;
            }
        }
    }

    boolean lookForPackageLocked(String packageName) {
        for (int i = 0; i < mAlarmBatches.size(); i++) {
            Batch b = mAlarmBatches.get(i);
            if (b.hasPackage(packageName)) {
                return true;
            }
        }
        return false;
    }

    private void setLocked(int type, long when) {
        if (mNativeData != 0 && mNativeData != -1) {
            // The kernel never triggers alarms with negative wakeup times
            // so we ensure they are positive.
            long alarmSeconds, alarmNanoseconds;
            if (when < 0) {
                alarmSeconds = 0;
                alarmNanoseconds = 0;
            } else {
                alarmSeconds = when / 1000;
                alarmNanoseconds = (when % 1000) * 1000 * 1000;
            }
            Slog.d(TAG, "set alarm to RTC " + when);
            set(mNativeData, type, alarmSeconds, alarmNanoseconds);
        } else {
            Slog.d(TAG, "the mNativeData from RTC is abnormal,  mNativeData = " + mNativeData);
            Message msg = Message.obtain();
            msg.what = ALARM_EVENT;
            
            mHandler.removeMessages(ALARM_EVENT);
            mHandler.sendMessageAtTime(msg, when);
        }
    }

    private static final void dumpAlarmList(PrintWriter pw, ArrayList<Alarm> list,
            String prefix, String label, long nowRTC, long nowELAPSED, SimpleDateFormat sdf) {
        for (int i=list.size()-1; i>=0; i--) {
            Alarm a = list.get(i);
            pw.print(prefix); pw.print(label); pw.print(" #"); pw.print(i);
                    pw.print(": "); pw.println(a);
            a.dump(pw, prefix + "  ", nowRTC, nowELAPSED, sdf);
        }
    }

    private static final String labelForType(int type) {
        switch (type) {
        case RTC: return "RTC";
        case RTC_WAKEUP : return "RTC_WAKEUP";
        case ELAPSED_REALTIME : return "ELAPSED";
        case ELAPSED_REALTIME_WAKEUP: return "ELAPSED_WAKEUP";
        default:
            break;
        }
        return "--unknown--";
    }

    private static final void dumpAlarmList(PrintWriter pw, ArrayList<Alarm> list,
            String prefix, long nowELAPSED, long nowRTC, SimpleDateFormat sdf) {
        for (int i=list.size()-1; i>=0; i--) {
            Alarm a = list.get(i);
            final String label = labelForType(a.type);
            pw.print(prefix); pw.print(label); pw.print(" #"); pw.print(i);
                    pw.print(": "); pw.println(a);
            a.dump(pw, prefix + "  ", nowRTC, nowELAPSED, sdf);
        }
    }

    private native long init();
    private native void close(long nativeData);
    private native void set(long nativeData, int type, long seconds, long nanoseconds);
    private native int waitForAlarm(long nativeData);
    private native int setKernelTime(long nativeData, long millis);
    private native int setKernelTimezone(long nativeData, int minuteswest);

    // /M:add for PoerOffAlarm feature,@{
    private native boolean bootFromAlarm(int fd);

    // /@}

    boolean triggerAlarmsLocked(ArrayList<Alarm> triggerList, final long nowELAPSED,
            final long nowRTC) {
        boolean hasWakeup = false;
        // batches are temporally sorted, so we need only pull from the
        // start of the list until we either empty it or hit a batch
        // that is not yet deliverable
        while (mAlarmBatches.size() > 0) {
            Batch batch = mAlarmBatches.get(0);
            if (batch.start > nowELAPSED) {
                // Everything else is scheduled for the future
                break;
            }

            // We will (re)schedule some alarms now; don't let that interfere
            // with delivery of this current batch
            mAlarmBatches.remove(0);

            final int N = batch.size();
            for (int i = 0; i < N; i++) {
                Alarm alarm = batch.get(i);
                alarm.count = 1;
                triggerList.add(alarm);

                // Recurring alarms may have passed several alarm intervals while the
                // phone was asleep or off, so pass a trigger count when sending them.
                if (alarm.repeatInterval > 0) {
                    // this adjustment will be zero if we're late by
                    // less than one full repeat interval
                    alarm.count += (nowELAPSED - alarm.whenElapsed) / alarm.repeatInterval;

                    // Also schedule its next recurrence
                    final long delta = alarm.count * alarm.repeatInterval;
                    final long nextElapsed = alarm.whenElapsed + delta;
                    final long maxElapsed;
                    if(SystemProperties.get("ro.mtk_bg_power_saving_support").equals("1") && (mAmPlus != null)) {
                         // M: BG powerSaving feature
                        maxElapsed = mAmPlus.getMaxTriggerTime(alarm.type, nextElapsed, alarm.windowLength,
                        alarm.repeatInterval, alarm.operation);
                    } else {
                        maxElapsed = maxTriggerTime(nowELAPSED, nextElapsed, alarm.repeatInterval);
                    }
                    setImplLocked(alarm.type, alarm.when + delta, nextElapsed, alarm.windowLength,
                            maxElapsed,
                            alarm.repeatInterval, alarm.operation, batch.standalone, true,
                            alarm.workSource, alarm.alarmClock, alarm.userId);

                    // For now we count this as a wakeup alarm, meaning it needs to be
                    // delivered immediately.  In the future we should change this, but
                    // that required delaying when we reschedule the repeat...!
                    hasWakeup = false;
                } else if (alarm.wakeup) {
                    hasWakeup = true;
                }

                // We removed an alarm clock. Let the caller recompute the next alarm clock.
                if (alarm.alarmClock != null) {
                    mNextAlarmClockMayChange = true;
                }
            }
        }

        // This is a new alarm delivery set; bump the sequence number to indicate that
        // all apps' alarm delivery classes should be recalculated.
        mCurrentSeq++;
        calculateDeliveryPriorities(triggerList);
        Collections.sort(triggerList, mAlarmDispatchComparator);

        if (localLOGV) {
            for (int i=0; i<triggerList.size(); i++) {
                Slog.v(TAG, "Triggering alarm #" + i + ": " + triggerList.get(i));
            }
        }

        return hasWakeup;
    }

    /**
     * This Comparator sorts Alarms into increasing time order.
     */
    public static class IncreasingTimeOrder implements Comparator<Alarm> {
        public int compare(Alarm a1, Alarm a2) {
            long when1 = a1.when;
            long when2 = a2.when;
            if (when1 - when2 > 0) {
                return 1;
            }
            if (when1 - when2 < 0) {
                return -1;
            }
            return 0;
        }
    }
    
    private static class Alarm {
        public final int type;
        public final boolean wakeup;
        public final PendingIntent operation;
        public final String  tag;
        public final WorkSource workSource;
        public int count;
        public long when;
        public long windowLength;
        public long whenElapsed;    // 'when' in the elapsed time base
        public long maxWhen;        // also in the elapsed time base
        public long repeatInterval;
        public final AlarmManager.AlarmClockInfo alarmClock;
        public final int userId;
        public PriorityClass priorityClass;

        public Alarm(int _type, long _when, long _whenElapsed, long _windowLength, long _maxWhen,
                long _interval, PendingIntent _op, WorkSource _ws,
                AlarmManager.AlarmClockInfo _info, int _userId) {
            type = _type;
            wakeup = _type == AlarmManager.ELAPSED_REALTIME_WAKEUP
                    || _type == AlarmManager.RTC_WAKEUP;
            when = _when;
            whenElapsed = _whenElapsed;
            windowLength = _windowLength;
            maxWhen = _maxWhen;
            repeatInterval = _interval;
            operation = _op;
            tag = makeTag(_op, _type);
            workSource = _ws;
            alarmClock = _info;
            userId = _userId;
        }

        public static String makeTag(PendingIntent pi, int type) {
            return pi.getTag(type == ELAPSED_REALTIME_WAKEUP || type == RTC_WAKEUP
                    ? "*walarm*:" : "*alarm*:");
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(128);
            sb.append("Alarm{");
            sb.append(Integer.toHexString(System.identityHashCode(this)));
            sb.append(" type ");
            sb.append(type);
            sb.append(" when ");
            sb.append(when);
            sb.append(" ");
            sb.append(operation.getTargetPackage());
            sb.append('}');
            return sb.toString();
        }

        public void dump(PrintWriter pw, String prefix, long nowRTC, long nowELAPSED,
                SimpleDateFormat sdf) {
            final boolean isRtc = (type == RTC || type == RTC_WAKEUP);
            pw.print(prefix); pw.print("tag="); pw.println(tag);
            pw.print(prefix); pw.print("type="); pw.print(type);
                    pw.print(" whenElapsed="); TimeUtils.formatDuration(whenElapsed,
                            nowELAPSED, pw);
                    if (isRtc) {
                        pw.print(" when="); pw.print(sdf.format(new Date(when)));
                    } else {
                        pw.print(" when="); TimeUtils.formatDuration(when, nowELAPSED, pw);
                    }
                    pw.println();
            pw.print(prefix); pw.print("window="); pw.print(windowLength);
                    pw.print(" repeatInterval="); pw.print(repeatInterval);
                    pw.print(" count="); pw.println(count);
            pw.print(prefix); pw.print("operation="); pw.println(operation);
        }
    }

    void recordWakeupAlarms(ArrayList<Batch> batches, long nowELAPSED, long nowRTC) {
        final int numBatches = batches.size();
        for (int nextBatch = 0; nextBatch < numBatches; nextBatch++) {
            Batch b = batches.get(nextBatch);
            if (b.start > nowELAPSED) {
                break;
            }

            final int numAlarms = b.alarms.size();
            for (int nextAlarm = 0; nextAlarm < numAlarms; nextAlarm++) {
                Alarm a = b.alarms.get(nextAlarm);
                WakeupEvent e = new WakeupEvent(nowRTC,
                        a.operation.getCreatorUid(),
                        a.operation.getIntent().getAction());
                mRecentWakeups.add(e);
            }
        }
    }

    long currentNonWakeupFuzzLocked(long nowELAPSED) {
        long timeSinceOn = nowELAPSED - mNonInteractiveStartTime;
        if (timeSinceOn < 5*60*1000) {
            // If the screen has been off for 5 minutes, only delay by at most two minutes.
            return 2*60*1000;
        } else if (timeSinceOn < 30*60*1000) {
            // If the screen has been off for 30 minutes, only delay by at most 15 minutes.
            return 15*60*1000;
        } else {
            // Otherwise, we will delay by at most an hour.
            return 60*60*1000;
        }
    }

    boolean checkAllowNonWakeupDelayLocked(long nowELAPSED) {
        if (mInteractive) {
            return false;
        }
        if (mLastAlarmDeliveryTime <= 0) {
            return false;
        }
        if (mPendingNonWakeupAlarms.size() > 0 && mNextNonWakeupDeliveryTime > nowELAPSED) {
            // This is just a little paranoia, if somehow we have pending non-wakeup alarms
            // and the next delivery time is in the past, then just deliver them all.  This
            // avoids bugs where we get stuck in a loop trying to poll for alarms.
            return false;
        }
        long timeSinceLast = nowELAPSED - mLastAlarmDeliveryTime;
        return timeSinceLast <= currentNonWakeupFuzzLocked(nowELAPSED);
    }

    void deliverAlarmsLocked(ArrayList<Alarm> triggerList, long nowELAPSED) {
        mLastAlarmDeliveryTime = nowELAPSED;
    final long nowRTC = System.currentTimeMillis();
        for (int i=0; i<triggerList.size(); i++) {
            Alarm alarm = triggerList.get(i);
        // /M:add for PowerOffAlarm feature,@{
        updatePoweroffAlarm(nowRTC);
        // /@}
        // /M:add for DM feature,@{
        synchronized (mDMLock) {
        if (mDMEnable == false || mPPLEnable == false) {
            FreeDmIntent(triggerList, mDmFreeList, nowELAPSED, mDmResendList);
            break;
        }
        }
        // /@}

        // /M:add for IPO feature,@{
        if (SystemProperties.get("ro.mtk_ipo_support").equals("1")) {
        if (mIPOShutdown)
            continue;
        }
        // /@}

            try {
                if (localLOGV) Slog.v(TAG, "sending alarm " + alarm);
                if (alarm.type == RTC_WAKEUP || alarm.type == ELAPSED_REALTIME_WAKEUP) {
                    Slog.d(TAG, "wakeup alarm = " + alarm + "; package = " + alarm.operation.getTargetPackage());
                }

                alarm.operation.send(getContext(), 0,
                        mBackgroundIntent.putExtra(
                                Intent.EXTRA_ALARM_COUNT, alarm.count),
                        mResultReceiver, mHandler);

                // we have an active broadcast so stay awake.
                if (mBroadcastRefCount == 0) {
                    setWakelockWorkSource(alarm.operation, alarm.workSource,
                            alarm.type, alarm.tag, true);
                    mWakeLock.acquire();
                }
                final InFlight inflight = new InFlight(AlarmManagerService.this,
                        alarm.operation, alarm.workSource, alarm.type, alarm.tag);
                mInFlight.add(inflight);
                mBroadcastRefCount++;

                final BroadcastStats bs = inflight.mBroadcastStats;
                bs.count++;
                if (bs.nesting == 0) {
                    bs.nesting = 1;
                    bs.startTime = nowELAPSED;
                } else {
                    bs.nesting++;
                }
                final FilterStats fs = inflight.mFilterStats;
                fs.count++;
                if (fs.nesting == 0) {
                    fs.nesting = 1;
                    fs.startTime = nowELAPSED;
                } else {
                    fs.nesting++;
                }
                if (alarm.type == ELAPSED_REALTIME_WAKEUP
                        || alarm.type == RTC_WAKEUP) {
                    bs.numWakeup++;
                    fs.numWakeup++;
                    if (alarm.workSource != null && alarm.workSource.size() > 0) {
                        for (int wi=0; wi<alarm.workSource.size(); wi++) {
                            ActivityManagerNative.noteWakeupAlarm(
                                    alarm.operation, alarm.workSource.get(wi),
                                    alarm.workSource.getName(wi));
                        }
                    } else {
                        ActivityManagerNative.noteWakeupAlarm(
                                alarm.operation, -1, null);
                    }
                }
            } catch (PendingIntent.CanceledException e) {
                if (alarm.repeatInterval > 0) {
                    // This IntentSender is no longer valid, but this
                    // is a repeating alarm, so toss the hoser.
                    removeImpl(alarm.operation);
                }
            } catch (RuntimeException e) {
                Slog.w(TAG, "Failure sending alarm.", e);
            }
        }
    }

    private class AlarmThread extends Thread
    {
        public AlarmThread()
        {
            super("AlarmManager");
        }
        
        public void run()
        {
            ArrayList<Alarm> triggerList = new ArrayList<Alarm>();

            while (true)
            {

                // /M:add for IPO feature,when shut down,this thread goto
                // sleep,@{
        if (SystemProperties.get("ro.mtk_ipo_support").equals("1")) {
                    if (mIPOShutdown) {
                        try {
                            if (mNativeData != -1) {
                                synchronized (mLock) {
                                    mAlarmBatches.clear();
                                }
                            }
                            synchronized (mWaitThreadlock) {
                                mWaitThreadlock.wait();
                            }
                        } catch (InterruptedException e) {
                            Slog.v(TAG, "InterruptedException ");
                        }
                    }
                }
                // /@}

                int result = waitForAlarm(mNativeData);

                triggerList.clear();

                if ((result & TIME_CHANGED_MASK) != 0) {
                    if (DEBUG_BATCH) {
                        Slog.v(TAG, "Time changed notification from kernel; rebatching");
                    }
                    removeImpl(mTimeTickSender);
                    rebatchAllAlarms();
                    mClockReceiver.scheduleTimeTickEvent();
                    synchronized (mLock) {
                        mNumTimeChanged++;
                    }
                    Intent intent = new Intent(Intent.ACTION_TIME_CHANGED);
                    intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING
                            | Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                    getContext().sendBroadcastAsUser(intent, UserHandle.ALL);
                }
                
                synchronized (mLock) {
                    final long nowRTC = System.currentTimeMillis();
                    final long nowELAPSED = SystemClock.elapsedRealtime();
                    if (localLOGV) Slog.v(
                        TAG, "Checking for alarms... rtc=" + nowRTC
                        + ", elapsed=" + nowELAPSED);

                    if (WAKEUP_STATS) {
                        if ((result & IS_WAKEUP_MASK) != 0) {
                            long newEarliest = nowRTC - RECENT_WAKEUP_PERIOD;
                            int n = 0;
                            for (WakeupEvent event : mRecentWakeups) {
                                if (event.when > newEarliest) break;
                                n++; // number of now-stale entries at the list head
                            }
                            for (int i = 0; i < n; i++) {
                                mRecentWakeups.remove();
                            }

                            recordWakeupAlarms(mAlarmBatches, nowELAPSED, nowRTC);
                        }
                    }

                    boolean hasWakeup = triggerAlarmsLocked(triggerList, nowELAPSED, nowRTC);
                    if (!hasWakeup && checkAllowNonWakeupDelayLocked(nowELAPSED)) {
                        // if there are no wakeup alarms and the screen is off, we can
                        // delay what we have so far until the future.
                        if (mPendingNonWakeupAlarms.size() == 0) {
                            mStartCurrentDelayTime = nowELAPSED;
                            mNextNonWakeupDeliveryTime = nowELAPSED
                                    + ((currentNonWakeupFuzzLocked(nowELAPSED)*3)/2);
                        }
                        mPendingNonWakeupAlarms.addAll(triggerList);
                        mNumDelayedAlarms += triggerList.size();
                        rescheduleKernelAlarmsLocked();
                        updateNextAlarmClockLocked();
                    } else {
                        // now deliver the alarm intents; if there are pending non-wakeup
                        // alarms, we need to merge them in to the list.  note we don't
                        // just deliver them first because we generally want non-wakeup
                        // alarms delivered after wakeup alarms.
                        rescheduleKernelAlarmsLocked();
                        updateNextAlarmClockLocked();
                        if (mPendingNonWakeupAlarms.size() > 0) {
                            calculateDeliveryPriorities(mPendingNonWakeupAlarms);
                            triggerList.addAll(mPendingNonWakeupAlarms);
                            Collections.sort(triggerList, mAlarmDispatchComparator);
                            final long thisDelayTime = nowELAPSED - mStartCurrentDelayTime;
                            mTotalDelayTime += thisDelayTime;
                            if (mMaxDelayTime < thisDelayTime) {
                                mMaxDelayTime = thisDelayTime;
                            }
                            mPendingNonWakeupAlarms.clear();
                        }
                        deliverAlarmsLocked(triggerList, nowELAPSED);
                    }
                }
            }
        }
    }

    /**
     * Attribute blame for a WakeLock.
     * @param pi PendingIntent to attribute blame to if ws is null.
     * @param ws WorkSource to attribute blame.
     */
    void setWakelockWorkSource(PendingIntent pi, WorkSource ws, int type, String tag,
            boolean first) {
        try {
            final boolean unimportant = pi == mTimeTickSender;
            mWakeLock.setUnimportantForLogging(unimportant);
            if (first || mLastWakeLockUnimportantForLogging) {
                mWakeLock.setHistoryTag(tag);
            } else {
                mWakeLock.setHistoryTag(null);
            }
            mLastWakeLockUnimportantForLogging = unimportant;
            if (ws != null) {
                mWakeLock.setWorkSource(ws);
                return;
            }

            final int uid = ActivityManagerNative.getDefault()
                    .getUidForIntentSender(pi.getTarget());
            if (uid >= 0) {
                mWakeLock.setWorkSource(new WorkSource(uid));
                return;
            }
        } catch (Exception e) {
        }

        // Something went wrong; fall back to attributing the lock to the OS
        mWakeLock.setWorkSource(null);
    }

    private class AlarmHandler extends Handler {
        public static final int ALARM_EVENT = 1;
        public static final int MINUTE_CHANGE_EVENT = 2;
        public static final int DATE_CHANGE_EVENT = 3;
        public static final int SEND_NEXT_ALARM_CLOCK_CHANGED = 4;
        
        public AlarmHandler() {
        }
        
        public void handleMessage(Message msg) {
            if (msg.what == ALARM_EVENT) {
                ArrayList<Alarm> triggerList = new ArrayList<Alarm>();
                synchronized (mLock) {
                    final long nowRTC = System.currentTimeMillis();
                    final long nowELAPSED = SystemClock.elapsedRealtime();
                    triggerAlarmsLocked(triggerList, nowELAPSED, nowRTC);
                    updateNextAlarmClockLocked();
                }

                // now trigger the alarms without the lock held
                for (int i=0; i<triggerList.size(); i++) {
                    Alarm alarm = triggerList.get(i);
                    try {
                        alarm.operation.send();
                    } catch (PendingIntent.CanceledException e) {
                        if (alarm.repeatInterval > 0) {
                            // This IntentSender is no longer valid, but this
                            // is a repeating alarm, so toss the hoser.
                            removeImpl(alarm.operation);
                        }
                    }
                }
            } else if (msg.what == SEND_NEXT_ALARM_CLOCK_CHANGED) {
                sendNextAlarmClockChanged();
            }
        }
    }
    
    class ClockReceiver extends BroadcastReceiver {
        public ClockReceiver() {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_TIME_TICK);
            filter.addAction(Intent.ACTION_DATE_CHANGED);
            getContext().registerReceiver(this, filter);
        }
        
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_TIME_TICK)) {
                if (DEBUG_BATCH) {
                    Slog.v(TAG, "Received TIME_TICK alarm; rescheduling");
                }
                scheduleTimeTickEvent();
            } else if (intent.getAction().equals(Intent.ACTION_DATE_CHANGED)) {
                // Since the kernel does not keep track of DST, we need to
                // reset the TZ information at the beginning of each day
                // based off of the current Zone gmt offset + userspace tracked
                // daylight savings information.
                TimeZone zone = TimeZone.getTimeZone(SystemProperties.get(TIMEZONE_PROPERTY));
                int gmtOffset = zone.getOffset(System.currentTimeMillis());
                setKernelTimezone(mNativeData, -(gmtOffset / 60000));
                scheduleDateChangedEvent();
            }
        }
        
        public void scheduleTimeTickEvent() {
            final long currentTime = System.currentTimeMillis();
            final long nextTime = 60000 * ((currentTime / 60000) + 1);

            // Schedule this event for the amount of time that it would take to get to
            // the top of the next minute.
            final long tickEventDelay = nextTime - currentTime;

            final WorkSource workSource = null; // Let system take blame for time tick events.
            setImpl(ELAPSED_REALTIME, SystemClock.elapsedRealtime() + tickEventDelay, 0,
                    0, mTimeTickSender, true, workSource, null);
        }

        public void scheduleDateChangedEvent() {
            Calendar calendar = Calendar.getInstance();
            calendar.setTimeInMillis(System.currentTimeMillis());
            calendar.set(Calendar.HOUR, 0);
            calendar.set(Calendar.MINUTE, 0);
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND, 0);
            calendar.add(Calendar.DAY_OF_MONTH, 1);

            final WorkSource workSource = null; // Let system take blame for date change events.
            setImpl(RTC, calendar.getTimeInMillis(), 0, 0, mDateChangeSender, true, workSource,
                    null);
        }
    }
    
    class InteractiveStateReceiver extends BroadcastReceiver {
        public InteractiveStateReceiver() {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
            getContext().registerReceiver(this, filter);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (mLock) {
                interactiveStateChangedLocked(Intent.ACTION_SCREEN_ON.equals(intent.getAction()));
            }
        }
    }

    class UninstallReceiver extends BroadcastReceiver {
        public UninstallReceiver() {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            filter.addAction(Intent.ACTION_PACKAGE_RESTARTED);
            filter.addAction(Intent.ACTION_QUERY_PACKAGE_RESTART);
            filter.addDataScheme("package");
            getContext().registerReceiver(this, filter);
             // Register for events related to sdcard installation.
            IntentFilter sdFilter = new IntentFilter();
            sdFilter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
            sdFilter.addAction(Intent.ACTION_USER_STOPPED);
            getContext().registerReceiver(this, sdFilter);
        }
        
        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (mLock) {
                String action = intent.getAction();
                String pkgList[] = null;
                if (Intent.ACTION_QUERY_PACKAGE_RESTART.equals(action)) {
                    pkgList = intent.getStringArrayExtra(Intent.EXTRA_PACKAGES);
                    for (String packageName : pkgList) {
                        if (lookForPackageLocked(packageName)) {
                // /M:add for ALPS01013485,@{
                if (!"android".equals(packageName)) {
                // /@}
                setResultCode(Activity.RESULT_OK);
                return;
                // /M:add for ALPS01013485,@{
                }
                // /@}
                        }
                    }
                    return;
                } else if (Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE.equals(action)) {
                    pkgList = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
                } else if (Intent.ACTION_USER_STOPPED.equals(action)) {
                    int userHandle = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                    if (userHandle >= 0) {
                        removeUserLocked(userHandle);
                    }
                } else {
                    if (Intent.ACTION_PACKAGE_REMOVED.equals(action)
                            && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                        // This package is being updated; don't kill its alarms.
                        return;
                    }
                    Uri data = intent.getData();
                    if (data != null) {
                        String pkg = data.getSchemeSpecificPart();
                        if (pkg != null) {
                            pkgList = new String[]{pkg};
                        }
                    }
                }
                if (pkgList != null && (pkgList.length > 0)) {
                    for (String pkg : pkgList) {
                            // /M:add for ALPS01013485,@{
                            if ("android".equals(pkg)) {
                                continue;
                            }
                        // /@}
                        removeLocked(pkg);
                        mPriorities.remove(pkg);
                        for (int i=mBroadcastStats.size()-1; i>=0; i--) {
                            ArrayMap<String, BroadcastStats> uidStats = mBroadcastStats.valueAt(i);
                            if (uidStats.remove(pkg) != null) {
                                if (uidStats.size() <= 0) {
                                    mBroadcastStats.removeAt(i);
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    
    private final BroadcastStats getStatsLocked(PendingIntent pi) {
        String pkg = pi.getCreatorPackage();
        int uid = pi.getCreatorUid();
        ArrayMap<String, BroadcastStats> uidStats = mBroadcastStats.get(uid);
        if (uidStats == null) {
            uidStats = new ArrayMap<String, BroadcastStats>();
            mBroadcastStats.put(uid, uidStats);
        }
        BroadcastStats bs = uidStats.get(pkg);
        if (bs == null) {
            bs = new BroadcastStats(uid, pkg);
            uidStats.put(pkg, bs);
        }
        return bs;
    }

    class ResultReceiver implements PendingIntent.OnFinished {
        public void onSendFinished(PendingIntent pi, Intent intent, int resultCode,
                String resultData, Bundle resultExtras) {
            synchronized (mLock) {
                InFlight inflight = null;
                for (int i=0; i<mInFlight.size(); i++) {
                    if (mInFlight.get(i).mPendingIntent == pi) {
                        inflight = mInFlight.remove(i);
                        break;
                    }
                }
                if (inflight != null) {
                    final long nowELAPSED = SystemClock.elapsedRealtime();
                    BroadcastStats bs = inflight.mBroadcastStats;
                    bs.nesting--;
                    if (bs.nesting <= 0) {
                        bs.nesting = 0;
                        bs.aggregateTime += nowELAPSED - bs.startTime;
                    }
                    FilterStats fs = inflight.mFilterStats;
                    fs.nesting--;
                    if (fs.nesting <= 0) {
                        fs.nesting = 0;
                        fs.aggregateTime += nowELAPSED - fs.startTime;
                    }
                } else {
                    mLog.w("No in-flight alarm for " + pi + " " + intent);
                }
                mBroadcastRefCount--;
                if (mBroadcastRefCount == 0) {
                    mWakeLock.release();
                    if (mInFlight.size() > 0) {
                        mLog.w("Finished all broadcasts with " + mInFlight.size()
                                + " remaining inflights");
                        for (int i=0; i<mInFlight.size(); i++) {
                            mLog.w("  Remaining #" + i + ": " + mInFlight.get(i));
                        }
                        mInFlight.clear();
                    }
                } else {
                    // the next of our alarms is now in flight.  reattribute the wakelock.
                    if (mInFlight.size() > 0) {
                        InFlight inFlight = mInFlight.get(0);
                        setWakelockWorkSource(inFlight.mPendingIntent, inFlight.mWorkSource,
                                inFlight.mAlarmType, inFlight.mTag, false);
                    } else {
                        // should never happen
                        mLog.w("Alarm wakelock still held but sent queue empty");
                        mWakeLock.setWorkSource(null);
                    }
                }
            }
        }
    }

    class DMReceiver extends BroadcastReceiver {
        public DMReceiver() {
            IntentFilter filter = new IntentFilter();
            filter.addAction("com.mediatek.dm.LAWMO_LOCK");
            filter.addAction("com.mediatek.dm.LAWMO_UNLOCK");
            filter.addAction("com.mediatek.ppl.NOTIFY_LOCK");
            filter.addAction("com.mediatek.ppl.NOTIFY_UNLOCK");
            getContext().registerReceiver(this, filter);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (action.equals("com.mediatek.dm.LAWMO_LOCK")) {
                mDMEnable = false;
            } else if (action.equals("com.mediatek.dm.LAWMO_UNLOCK")) {
                mDMEnable = true;
                enableDm();
            } else if (action.equals("com.mediatek.ppl.NOTIFY_LOCK")) {
                mPPLEnable = false;
            } else if (action.equals("com.mediatek.ppl.NOTIFY_UNLOCK")) {
                mPPLEnable = true;
                enableDm();
            }
        }
    }

    /**
     *For DM feature, to enable DM
     */
    public int enableDm() {

        synchronized (mDMLock) {
            if (mDMEnable && mPPLEnable) {
                    /*
                     * boolean needIcon = false; needIcon =
                     * SearchAlarmListForPackage(mRtcWakeupAlarms,
                     * mAlarmIconPackageList); if (!needIcon) { Intent
                     * alarmChanged = new
                     * Intent("android.intent.action.ALARM_CHANGED");
                     * alarmChanged.putExtra("alarmSet", false);
                     * mContext.sendBroadcast(alarmChanged); }
                     */
                    // Intent alarmChanged = new
                    // Intent("android.intent.action.ALARM_RESET");
                    // mContext.sendBroadcast(alarmChanged);
                    resendDmPendingList(mDmResendList);
                    mDmResendList = null;
                    mDmResendList = new ArrayList<Alarm>();
            }
        }
        return -1;
    }

    /*boolean SearchAlarmListForPackage(ArrayList<Alarm> mRtcWakeupAlarms,
            ArrayList<String> mAlarmIconPackageList) {
        for (int i = 0; i < mRtcWakeupAlarms.size(); i++) {
            Alarm tempAlarm = mRtcWakeupAlarms.get(i);
            for (int j = 0; j < mAlarmIconPackageList.size(); j++) {
                if (mAlarmIconPackageList.get(j).equals(tempAlarm.operation.getTargetPackage())) {
                    return true;
                }
            }
        }
        return false;
    }*/

    /**
     *For DM feature, to Free DmIntent
     */
    private void FreeDmIntent(ArrayList<Alarm> triggerList, ArrayList<PendingIntent> mDmFreeList,
                              long nowELAPSED, ArrayList<Alarm> resendList) {
        Iterator<Alarm> it = triggerList.iterator();
        boolean isFreeIntent = false;
        while (it.hasNext()) {
            isFreeIntent = false;
            Alarm alarm = it.next();
            try {
                for (int i = 0; i < mDmFreeList.size(); i++) {
                    if (alarm.operation.equals(mDmFreeList.get(i))) {
                        if (localLOGV)
                            Slog.v(TAG, "sending alarm " + alarm);
                        alarm.operation.send(getContext(), 0,
                                mBackgroundIntent.putExtra(
                                        Intent.EXTRA_ALARM_COUNT, alarm.count),
                                mResultReceiver, mHandler);
                        // we have an active broadcast so stay awake.
                        if (mBroadcastRefCount == 0) {
                setWakelockWorkSource(alarm.operation, alarm.workSource,
                    alarm.type, alarm.tag, true);
                            mWakeLock.acquire();
                        }

            final InFlight inflight = new InFlight(AlarmManagerService.this,
                alarm.operation, alarm.workSource, alarm.type, alarm.tag);

                        mInFlight.add(inflight);
                        mBroadcastRefCount++;
                        final BroadcastStats bs = inflight.mBroadcastStats;
                        bs.count++;
                        if (bs.nesting == 0) {
                            bs.nesting = 1;
                            bs.startTime = nowELAPSED;
                        } else {
                            bs.nesting++;
                        }
                        final FilterStats fs = inflight.mFilterStats;
                        fs.count++;
                        if (fs.nesting == 0) {
                            fs.nesting = 1;
                            fs.startTime = nowELAPSED;
                        } else {
                            fs.nesting++;
                        }
                        if (alarm.type == ELAPSED_REALTIME_WAKEUP
                                || alarm.type == RTC_WAKEUP) {
                            bs.numWakeup++;
                            fs.numWakeup++;
                            //ActivityManagerNative.noteWakeupAlarm(
                                    //alarm.operation);
                        }
                        isFreeIntent = true;
                        break;
                    }

                }
                if (!isFreeIntent) {
                    resendList.add(alarm);
                    isFreeIntent = false;
                }
            } catch (PendingIntent.CanceledException e) {
                if (alarm.repeatInterval > 0) {
                    // This IntentSender is no longer valid, but this
                    // is a repeating alarm, so toss the hoser.
                    //remove(alarm.operation);
                }
            }
        }
    }

    /**
     *For DM feature, to resend DmPendingList
     */
    private void resendDmPendingList(ArrayList<Alarm> DmResendList) {
        Iterator<Alarm> it = DmResendList.iterator();
        while (it.hasNext()) {
            Alarm alarm = it.next();
            try {
                if (localLOGV)
                    Slog.v(TAG, "sending alarm " + alarm);
                alarm.operation.send(getContext(), 0,
                        mBackgroundIntent.putExtra(
                                Intent.EXTRA_ALARM_COUNT, alarm.count),
                                mResultReceiver, mHandler);

                // we have an active broadcast so stay awake.
                if (mBroadcastRefCount == 0) {
                    setWakelockWorkSource(alarm.operation, alarm.workSource,
                            alarm.type, alarm.tag, true);
                    mWakeLock.acquire();
                }
                final InFlight inflight = new InFlight(AlarmManagerService.this,
                alarm.operation, alarm.workSource, alarm.type, alarm.tag);
                mInFlight.add(inflight);
                mBroadcastRefCount++;
                final BroadcastStats bs = inflight.mBroadcastStats;
                bs.count++;
                if (bs.nesting == 0) {
                    bs.nesting = 1;
                    bs.startTime = SystemClock.elapsedRealtime();
                } else {
                    bs.nesting++;
                }
                final FilterStats fs = inflight.mFilterStats;
                fs.count++;
                if (fs.nesting == 0) {
                    fs.nesting = 1;
                    fs.startTime = SystemClock.elapsedRealtime();
                } else {
                    fs.nesting++;
                }
                if (alarm.type == ELAPSED_REALTIME_WAKEUP
                        || alarm.type == RTC_WAKEUP) {
                    bs.numWakeup++;
                    fs.numWakeup++;
                    //ActivityManagerNative.noteWakeupAlarm(
                           //alarm.operation);
                }
            } catch (PendingIntent.CanceledException e) {
                if (alarm.repeatInterval > 0) {
                    // This IntentSender is no longer valid, but this
                    // is a repeating alarm, so toss the hoser.
                    //remove(alarm.operation);
                }
            }
        }
    }

    /**
     *For PowerOffalarm feature, to query if boot from alarm
     */
    private boolean isBootFromAlarm(int fd) {
        return bootFromAlarm(fd);
    }

    /**
     *For PowerOffalarm feature, to update Poweroff Alarm
     */
    private void updatePoweroffAlarm(long nowRTC) {

        synchronized (mPowerOffAlarmLock) {

            if (mPoweroffAlarms.size() == 0) {

                return;
            }

            if (mPoweroffAlarms.get(0).when > nowRTC) {

                return;
            }

            Iterator<Alarm> it = mPoweroffAlarms.iterator();

            while (it.hasNext())
            {
                Alarm alarm = it.next();

                if (alarm.when > nowRTC) {
                    // don't fire alarms in the future
                    break;
                }
                Slog.w(TAG, "power off alarm update deleted");
                // remove the alarm from the list
                it.remove();
            }

            if (mPoweroffAlarms.size() > 0) {
                resetPoweroffAlarm(mPoweroffAlarms.get(0));
            }
        }
    }

    private int addPoweroffAlarmLocked(Alarm alarm) {
        ArrayList<Alarm> alarmList = mPoweroffAlarms;

        int index = Collections.binarySearch(alarmList, alarm, sIncreasingTimeOrder);
        if (index < 0) {
            index = 0 - index - 1;
        }
        if (localLOGV) Slog.v(TAG, "Adding alarm " + alarm + " at " + index);
        alarmList.add(index, alarm);

        if (localLOGV) {
            // Display the list of alarms for this alarm type
            Slog.v(TAG, "alarms: " + alarmList.size() + " type: " + alarm.type);
            int position = 0;
            for (Alarm a : alarmList) {
                Time time = new Time();
                time.set(a.when);
                String timeStr = time.format("%b %d %I:%M:%S %p");
                Slog.v(TAG, position + ": " + timeStr
                        + " " + a.operation.getTargetPackage());
                position += 1;
            }
        }

        return index;
    }

    private void removePoweroffAlarmLocked(String packageName) {
        ArrayList<Alarm> alarmList = mPoweroffAlarms;
        if (alarmList.size() <= 0) {
            return;
        }

        // iterator over the list removing any it where the intent match
        Iterator<Alarm> it = alarmList.iterator();

        while (it.hasNext()) {
            Alarm alarm = it.next();
            if (alarm.operation.getTargetPackage().equals(packageName)) {
                it.remove();
            }
        }
    }

    /**
     *For PowerOffalarm feature, this function is used for AlarmManagerService
     * to set the latest alarm registered
     */
    private void resetPoweroffAlarm(Alarm alarm) {

        String setPackageName = alarm.operation.getTargetPackage();
        long latestTime = alarm.when;

        // [Note] Power off Alarm +
        if (mNativeData != 0 && mNativeData != -1) {
            if (setPackageName.equals("com.android.deskclock")) {
                Slog.i(TAG, "mBootPackage = " + setPackageName + " set Prop 1");
                SystemProperties.set("persist.sys.bootpackage", "1"); // for
                                                                  // deskclock
                set(mNativeData, 6, latestTime / 1000, (latestTime % 1000) * 1000 * 1000);
            } else if (setPackageName.equals("com.mediatek.schpwronoff")) {
                Slog.i(TAG, "mBootPackage = " + setPackageName + " set Prop 2");
                SystemProperties.set("persist.sys.bootpackage", "2"); // for
                                                                  // settings
                set(mNativeData, 7, latestTime / 1000, (latestTime % 1000) * 1000 * 1000);
            // For settings to test powronoff
            } else if (setPackageName.equals("com.mediatek.poweronofftest")) {
                Slog.i(TAG, "mBootPackage = " + setPackageName + " set Prop 2");
                SystemProperties.set("persist.sys.bootpackage", "2"); // for
                                                                  // poweronofftest
                set(mNativeData, 7, latestTime / 1000, (latestTime % 1000) * 1000 * 1000);
            } else {
                Slog.w(TAG, "unknown package (" + setPackageName + ") to set power off alarm");
            }
        // [Note] Power off Alarm -

            Slog.i(TAG, "reset power off alarm is " + setPackageName);
            SystemProperties.set("sys.power_off_alarm", Long.toString(latestTime / 1000));
            } else {
            Slog.i(TAG, " do not set alarm to RTC when fd close ");
	}

    }

    /**
     * For PowerOffalarm feature, this function is used for APP to
     * cancelPoweroffAlarm
     */
    public void cancelPoweroffAlarmImpl(String name) {
        Slog.i(TAG, "remove power off alarm pacakge name " + name);
        // not need synchronized
        synchronized (mPowerOffAlarmLock) {
            removePoweroffAlarmLocked(name);
            // AlarmPair tempAlarmPair = mPoweroffAlarms.remove(name);
            // it will always to cancel the alarm in alarm driver
            String bootReason = SystemProperties.get("persist.sys.bootpackage");
            if (bootReason != null) {
                if (bootReason.equals("1") && name.equals("com.android.deskclock")) {
                    set(mNativeData, 6, 0, 0);
                    SystemProperties.set("sys.power_off_alarm", Long.toString(0));
                } else if (bootReason.equals("2") && (name.equals("com.mediatek.schpwronoff")
                           || name.equals("com.mediatek.poweronofftest"))) {
                    set(mNativeData, 7, 0, 0);
                    SystemProperties.set("sys.power_off_alarm", Long.toString(0));
                }
            }
            if (mPoweroffAlarms.size() > 0) {
                resetPoweroffAlarm(mPoweroffAlarms.get(0));
            }
        }
    }

    /**
     * For IPO feature, this function is used for reset alarm when shut down
     */
    private void shutdownCheckPoweroffAlarm() {
        Slog.i(TAG, "into shutdownCheckPoweroffAlarm()!!");
        String setPackageName = null;
        long latestTime;
        long nowTime = System.currentTimeMillis();
        synchronized (mPowerOffAlarmLock) {
            Iterator<Alarm> it = mPoweroffAlarms.iterator();
            ArrayList<Alarm> mTempPoweroffAlarms = new ArrayList<Alarm>();
            while (it.hasNext()) {
                Alarm alarm = it.next();
                latestTime = alarm.when;
                setPackageName = alarm.operation.getTargetPackage();

                if ((latestTime - 30 * 1000) <= nowTime) {
                    Slog.i(TAG, "get target latestTime < 30S!!");
                    mTempPoweroffAlarms.add(alarm);
                }
            }
            Iterator<Alarm> tempIt = mTempPoweroffAlarms.iterator();
            while (tempIt.hasNext()) {
                Alarm alarm = tempIt.next();
                latestTime = alarm.when;
                    //set(alarm.type, (latestTime + 60 * 1000), 0, 0, alarm.operation, null);
            set(mNativeData, alarm.type, latestTime / 1000, (latestTime % 1000) * 1000 * 1000);
            }
        }
        Slog.i(TAG, "away shutdownCheckPoweroffAlarm()!!");
    }

    /**
     * For LCA project,AMS can remove alrms
     */
    public void removeFromAmsImpl(String packageName) {
        if (packageName == null) {
            return;
        }
        synchronized (mLock) {
            removeLocked(packageName);
        }
    }

    /**
     * For LCA project,AMS can query alrms
     */
    public boolean lookForPackageFromAmsImpl(String packageName) {
        if (packageName == null) {
            return false;
        }
        synchronized (mLock) {
            return lookForPackageLocked(packageName);
        }
    }
}
