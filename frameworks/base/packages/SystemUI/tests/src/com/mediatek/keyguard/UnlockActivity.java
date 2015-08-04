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

import android.app.Activity;
//import android.content.BroadcastReceiver;
import android.content.Context;
//import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Bundle;
import android.util.Log;
//import android.view.View;
//import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

//import com.mediatek.systemui.TestUtils;

/**
 * Unlock activity.
 */
public class UnlockActivity extends Activity {

    public static Context sSystemUIContext;
    public static Object sPhoneStatusBar;
    public static Object sStatusBarWindow;
    public static Object sStatusBarView;
    public static Object sToolBarView;
    public static Object sNotificationPanel ;

    private static final String TAG = "UnlockActivity";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.v(TAG, "onCreate() - enters.");
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        Window win = getWindow();
        WindowManager.LayoutParams winParams = win.getAttributes();
        winParams.flags |= (WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
         win.setAttributes(winParams);
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "StatusBarViewActivity onDestroy");
        super.onDestroy();
    }

    /**
      * set the test view.
      */
    public void setTestView() {
        Log.d(TAG, "setTestView() - enters.") ;

        /*
        /// Singleton
        if (sSystemUIContext != null) return;

        try {
            sSystemUIContext = this.createPackageContext("com.android.systemui",
                Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);

            /// Stop SystemUI service to avoid adding another statusbar window.
            //Intent intent = new Intent();
            //intent.setComponent(new ComponentName("com.android.systemui",
            //        "com.android.systemui.SystemUIService"));
            //sSystemUIContext.stopService(intent);

            Class cls = sSystemUIContext.getClassLoader().loadClass(
                "com.android.systemui.statusbar.phone.PhoneStatusBar"
                );
            sPhoneStatusBar = cls.newInstance();
            TestUtils.getMethodList(sPhoneStatusBar) ;

            TestUtils.setProperty(sPhoneStatusBar, "mContext", sSystemUIContext);
            TestUtils.setSuperClassProperty(sPhoneStatusBar, "mContext", sSystemUIContext);

            TestUtils.invokeMethod(sPhoneStatusBar, "setRunningInTest",
                                   new Class[] {boolean.class}, new Object[] {true});

            TestUtils.invokeMethod(sPhoneStatusBar, "start", new Class[] {}, new Object[] {});

            TestUtils.setProperty(sPhoneStatusBar, "mNavigationBarView", null);
            sStatusBarWindow = TestUtils.getProperty(sPhoneStatusBar, "sStatusBarWindow");
            sStatusBarView   = TestUtils.getProperty(sPhoneStatusBar, "sStatusBarView");
            sNotificationPanel = TestUtils.getProperty(sPhoneStatusBar, "sNotificationPanel");

            /// Deregister the broadcast event.
            Object receiver =  TestUtils.getProperty(sPhoneStatusBar, "mBroadcastReceiver");
            //sSystemUIContext.unregisterReceiver((BroadcastReceiver) receiver);

            //((ViewGroup)sStatusBarWindow).removeView((View)sStatusBarView);
            ((ViewGroup) sStatusBarWindow).removeAllViews();

            TestUtils.invokeMethod(sPhoneStatusBar,
                "showKeyguard",
                new Class[] {},
                new Object[] {});

            TestUtils.setProperty(sPhoneStatusBar, "mExpandedVisible", true);
            TestUtils.invokeMethod(sPhoneStatusBar,
                "updateExpandedViewPos",
                new Class[] { int.class },
                    new Object[] { -10001 });
            TestUtils.setProperty(sPhoneStatusBar, "mExpandedVisible", false);

            getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
            setContentView((View) sNotificationPanel);
        } catch (IllegalArgumentException e) {
            Log.d(TAG, "IllegalArgumentException");
            e.printStackTrace();
        } catch (ClassNotFoundException e) {
            Log.d(TAG, "ClassNotFoundException");
            e.printStackTrace();
        } catch (NameNotFoundException e) {
            Log.d(TAG, "NameNotFoundException");
            e.printStackTrace();
        } catch (InstantiationException e) {
            Log.d(TAG, "InstantiationException");
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            Log.d(TAG, "IllegalAccessException");
            e.printStackTrace();
        }
        */
    }

    /**
      * remove the test view.
      */
    public void removeTestView() {
        setContentView(new FrameLayout(this));
        Log.v(TAG, "remove KeyguardHostView from contentView");
    }

    /*
    public static void getMethodList(Object ob) {
        Class c = ob.getClass();
        for (Method method : c.getDeclaredMethods()) {
            Log.d(TAG, "getMethodList() - " + method.getName());
        }
    }
    */
}
