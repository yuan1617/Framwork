/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.webview.chromium;

import android.content.Context;
import android.util.Log;
import android.webkit.WebViewDatabase;

import org.chromium.android_webview.AwFormDatabase;
import org.chromium.android_webview.HttpAuthDatabase;
/// M: save password
//import org.chromium.android_webview.PasswordDatabase;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Chromium implementation of WebViewDatabase -- forwards calls to the
 * chromium internal implementation.
 */
final class WebViewDatabaseAdapter extends WebViewDatabase {

    private AwFormDatabase mFormDatabase;
    private HttpAuthDatabase mHttpAuthDatabase;
    /// M: save password
    private Object mPasswordDatabase;
    private static final String LOGTAG = "WebViewDatabaseAdapter";

    public WebViewDatabaseAdapter(AwFormDatabase formDatabase, HttpAuthDatabase httpAuthDatabase) {
        mFormDatabase = formDatabase;
        mHttpAuthDatabase = httpAuthDatabase;
        /// M: save password
        mPasswordDatabase = null;
    }

    /// M: save password
    public WebViewDatabaseAdapter(AwFormDatabase formDatabase, HttpAuthDatabase httpAuthDatabase, 
                Object passwordDatabase) {
        mFormDatabase = formDatabase;
        mHttpAuthDatabase = httpAuthDatabase;
        /// M: save password
        mPasswordDatabase = passwordDatabase;
    }

    @Override
    public boolean hasUsernamePassword() {
        /// M: save password
        //return mPasswordDatabase.hasUsernamePassword();
        if (mPasswordDatabase == null) {
            return false;
        }
        try {
            Class<?> c = mPasswordDatabase.getClass();
            Method m = c.getMethod("hasUsernamePassword");
            return (boolean) m.invoke(mPasswordDatabase);
        } catch (NoSuchMethodException e) {
            Log.e(LOGTAG, "No such method for clearUsernamePassword: " + e);
        } catch (IllegalAccessException e) {
            Log.e(LOGTAG, "Illegal access for clearUsernamePassword:" + e);
        } catch (InvocationTargetException e) {
            Log.e(LOGTAG, "Invocation target exception for clearUsernamePassword: " + e);
        } catch (NullPointerException e) {
            Log.e(LOGTAG, "Null pointer for clearUsernamePassword: " + e);
        }
        return false;
    }

    @Override
    public void clearUsernamePassword() {
        /// M: save password
        //mPasswordDatabase.clearUsernamePassword();
        if (mPasswordDatabase == null) {
            return;
        }
        try {
            Class<?> c = mPasswordDatabase.getClass();
            Method m = c.getMethod("clearUsernamePassword");
            m.invoke(mPasswordDatabase);
        } catch (NoSuchMethodException e) {
            Log.e(LOGTAG, "No such method for clearUsernamePassword: " + e);
        } catch (IllegalAccessException e) {
            Log.e(LOGTAG, "Illegal access for clearUsernamePassword:" + e);
        } catch (InvocationTargetException e) {
            Log.e(LOGTAG, "Invocation target exception for clearUsernamePassword: " + e);
        } catch (NullPointerException e) {
            Log.e(LOGTAG, "Null pointer for clearUsernamePassword: " + e);
        }
    }

    @Override
    public boolean hasHttpAuthUsernamePassword() {
        return mHttpAuthDatabase.hasHttpAuthUsernamePassword();
    }

    @Override
    public void clearHttpAuthUsernamePassword() {
        mHttpAuthDatabase.clearHttpAuthUsernamePassword();
    }

    @Override
    public boolean hasFormData() {
        return mFormDatabase.hasFormData();
    }

    @Override
    public void clearFormData() {
        mFormDatabase.clearFormData();
    }
}
