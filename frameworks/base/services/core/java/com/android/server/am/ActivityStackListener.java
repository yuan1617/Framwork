package com.android.server.am;

import android.util.Log;

import java.util.ArrayList;

/**
 * Use to dump activityStack when ActivityStack has changed.
 */
public class ActivityStackListener {
    private static final String TAG = "ActivityStackListener";
    final ArrayList<ArrayList> mTaskHistorys = new ArrayList<ArrayList>(15);
    private volatile boolean mRun = true;

    /**
     * The thread is used to dump stack.
     */
    public class DumpHistoryThread implements Runnable {
        public DumpHistoryThread() {}
        @Override
        public void run() {
            while (mRun) {
                dumpHistory();
            }
        }
    }

    /**
     * The constructor of ActivityStackListener.
     */
    public ActivityStackListener() {
        new Thread(new DumpHistoryThread()).start();
    }

    /**
     * Stop to dump stack.
     */
    public void closeStackListener() {
        mRun = false;
    }

    /**
     * The detail method to dump stack.
     */
    public synchronized void dumpHistory() {
        while (mTaskHistorys.size() == 0) {
            try {
                wait();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        Log.v(TAG, "Dump History Start:");
        ArrayList mTaskHistory = mTaskHistorys.get(0);
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            TaskRecord task = (TaskRecord) mTaskHistory.get(taskNdx);
            ArrayList<ActivityRecord> activities = task.mActivities;
            for (int i = activities.size() - 1; i >= 0; --i) {
                ActivityRecord ar = activities.get(i);
                Log.v(TAG, "realActivity: " + ar.realActivity);
                Log.v(TAG, "packageName: " + ar.packageName);
                Log.v(TAG, "task: " + task.realActivity);
                Log.v(TAG, "intent: " + ar.intent);
                Log.v(TAG, "launchMode: " + ar.launchMode);
                Log.v(TAG, "state: " + ar.state);
                Log.v(TAG, "taskAffinity: " + ar.taskAffinity);
                Log.v(TAG, "haveState: " + ar.haveState);
                Log.v(TAG, "finishing: " + ar.finishing);
                Log.v(TAG, "visible: " + ar.visible);
                Log.v(TAG, "waitingVisible: " + ar.waitingVisible);
                Log.v(TAG, "nowVisible: " + ar.nowVisible);
            }
        }
        Log.v(TAG, "Dump History End.");
        mTaskHistorys.remove(0);
    }

    /**
     * The api is used to trigger dump stack.
     *
     * @param mTaskHistory the tasks of stack.
     */
    public synchronized void dumpStack(ArrayList mTaskHistory) {
        mTaskHistorys.add(mTaskHistory);
        if (mTaskHistorys.size() == 1) {
            notifyAll();
        }
    }
}
