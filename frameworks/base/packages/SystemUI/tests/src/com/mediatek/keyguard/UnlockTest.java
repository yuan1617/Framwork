/*
 * Copyright (C) 2011 The Android Open Source Project
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
package com.mediatek.systemui.keyguard;

import android.app.Instrumentation;
import android.content.ContentResolver;
import android.content.Context;
import android.test.ActivityInstrumentationTestCase2;
import android.util.Log;

//import com.mediatek.systemui.TestUtils;

/**
 * Functional tests for the recent application feature.
 */
public class UnlockTest extends ActivityInstrumentationTestCase2<UnlockActivity> {

    private static final String TAG = "UnlockTest";
    private static final int SCREEN_WAIT_TIME_SEC = 5;

    private Object mPhoneStatusBar;
    private Context mContext;
    private UnlockActivity mUnlockActivity;
    private Instrumentation mInstrumentation;
    private Object mRecentsPanelView;
    private ContentResolver mConResolver;

    /**
     * constructor.
     */
    public UnlockTest() {
        super(UnlockActivity.class);
    }

    /**
     * set up variables.
     */
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        setActivityInitialTouchMode(false);
        mUnlockActivity = getActivity();
        mInstrumentation = getInstrumentation();
        mContext = mInstrumentation.getTargetContext();
    }

    /**
     * Tear down.
     */
    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
    }

    /**
     * test swipe unlock.
     */
    public void test01SwipeUnlock() {
        Log.v(TAG, "++++++++++ Start testConstants ++++++++++");

        mInstrumentation.runOnMainSync(new Runnable() {
            @Override
            public void run() {
                mUnlockActivity.setTestView();
            }
        });
        getInstrumentation().waitForIdleSync();

        /*
        Class cls = null;
        try {
            cls = mContext.getClassLoader().loadClass("com.android.systemui.recent.Constants");
        } catch (ClassNotFoundException e) {
            Log.d(TAG, "testConstants ClassNotFoundException");
        }
        Integer a = (Integer) TestUtils.getStaticProperty(cls, "ESCAPE_VELOCITY");
        assertEquals(100, (int) a);
        */
        Log.v(TAG, "---------- end testConstants ----------");
    }
}
