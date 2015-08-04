/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.webkit;

/**
 * @hide
 * @internal
 */
public class SavePageClient {
    /**
     * ask the app for download path
     * @hide
     * @internal
     */
    public void getSaveDir(ValueCallback<String> callback, boolean canSaveAsComplete) {
    }

    /**
     * save page start callback
     * @hide
     * @internal
     */
    public void onSavePageStart(int id, String path) {
    }

    /**
     * save progress change callback
     * @hide
     * @internal
     */
    public void onSaveProgressChange(int progress, int id) {
    }

    /**
     * save finish callback
     * @hide
     * @internal
     */
    public void onSaveFinish(int flag, int id) {
    }
}
