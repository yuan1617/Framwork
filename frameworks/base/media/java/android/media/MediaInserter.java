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

package android.media;

import android.content.ContentValues;
import android.content.IContentProvider;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * A MediaScanner helper class which enables us to do lazy insertion on the
 * given provider. This class manages buffers internally and flushes when they
 * are full. Note that you should call flushAll() after using this class.
 * {@hide}
 */
public class MediaInserter {
    private final HashMap<Uri, List<ContentValues>> mRowMap =
            new HashMap<Uri, List<ContentValues>>();
    private final HashMap<Uri, List<ContentValues>> mPriorityRowMap =
            new HashMap<Uri, List<ContentValues>>();

    private final IContentProvider mProvider;
    private final String mPackageName;
    private final int mBufferSizePerUri;

    public MediaInserter(IContentProvider provider, String packageName, int bufferSizePerUri) {
        mProvider = provider;
        mPackageName = packageName;
        mBufferSizePerUri = bufferSizePerUri;
    }

    public void insert(Uri tableUri, ContentValues values) throws RemoteException {
        insert(tableUri, values, false);
    }

    public void insertwithPriority(Uri tableUri, ContentValues values) throws RemoteException {
        insert(tableUri, values, true);
    }

    private void insert(Uri tableUri, ContentValues values, boolean priority) throws RemoteException {
        HashMap<Uri, List<ContentValues>> rowmap = priority ? mPriorityRowMap : mRowMap;
        List<ContentValues> list = rowmap.get(tableUri);
        if (list == null) {
            list = new ArrayList<ContentValues>();
            rowmap.put(tableUri, list);
        }
        list.add(new ContentValues(values));
        if (list.size() >= mBufferSizePerUri) {
            flushAllPriority();
            flush(tableUri, list);
        }
    }

    public void flushAll() throws RemoteException {
        flushAllPriority();
        for (Uri tableUri : mRowMap.keySet()){
            List<ContentValues> list = mRowMap.get(tableUri);
            flush(tableUri, list);
        }
        mRowMap.clear();
    }

    private void flushAllPriority() throws RemoteException {
        for (Uri tableUri : mPriorityRowMap.keySet()){
            List<ContentValues> list = mPriorityRowMap.get(tableUri);
            /// M: Folder need flush priority to insert directly
            flushPriority(tableUri, list);
        }
        mPriorityRowMap.clear();
    }

    private void flush(Uri tableUri, List<ContentValues> list) throws RemoteException {
        if (!list.isEmpty()) {
            /// M: Add for MediaScaner performance enhancement with threadpool phaseII.
            /// we insert them in a handler thread together.
            if (mProvider != null) {
                ContentValues[] valuesArray = new ContentValues[list.size()];
                valuesArray = list.toArray(valuesArray);
                mProvider.bulkInsert(mPackageName, tableUri, valuesArray);
                list.clear();
            } else {
                /// M: we store the tableUri in a content value and put it to the first of list,
                /// so in handler thread we will know which table we need insert.
                ContentValues matchUriValue = new ContentValues(1);
                matchUriValue.put(INSERT_TABLE_URI_KEY, tableUri.toString());
                ArrayList<ContentValues> sendList = new ArrayList<ContentValues>(list.size() + 1);
                sendList.add(matchUriValue);
                sendList.addAll(list);
                list.clear();
                Message msg = mInsertHanlder.obtainMessage(0, -1, -1, sendList);
                mInsertHanlder.sendMessage(msg);
            }
        }
    }

    /// M: Add for MediaScaner performance enhancement with threadpool phaseII. {@
    /**
     * M: Insert key value, store table uri in list, so that handler can get it and do insert.
     */
    public static final String INSERT_TABLE_URI_KEY = "insert_table_uri_key";
    /// Insert relate message
    public static final int MSG_INSERT_TO_DATABASE = 0;
    public static final int MSG_INSERT_FOLDER = 1;
    public static final int MSG_INSERT_ALL = 2;
    public static final int MSG_STOP_INSERT = 3;
    /// Scan relate message
    public static final int MSG_SCAN_DIRECTORY = 10;
    public static final int MSG_SCAN_SINGLE_FILE = 11;
    public static final int MSG_SHUTDOWN_THREADPOOL = 12;
    public static final int MSG_SCAN_FINISH_WITH_THREADPOOL = 13;
    private Handler mInsertHanlder;
    
    public MediaInserter(Handler inserterHandler, int bufferSizePerUri) {
        mInsertHanlder = inserterHandler;
        mBufferSizePerUri = bufferSizePerUri;
        mProvider = null;
        mPackageName = null;
    }

    private void flushPriority(Uri tableUri, List<ContentValues> list) throws RemoteException {
        if (!list.isEmpty()) {
            /// M: Add for MediaScaner performance enhancement with threadpool phaseII.
            /// we insert them in a handler thread together.
            if (mProvider != null) {
                ContentValues[] valuesArray = new ContentValues[list.size()];
                valuesArray = list.toArray(valuesArray);
                mProvider.bulkInsert(mPackageName, tableUri, valuesArray);
                list.clear();
            } else {
                /// we store the tableUri in a content value and put it to the first of list, so in handler thread
                /// we will know which table we need insert.
                ContentValues matchUriValue = new ContentValues(1);
                matchUriValue.put(INSERT_TABLE_URI_KEY, tableUri.toString());
                ArrayList<ContentValues> sendList = new ArrayList<ContentValues>(list.size() + 1);
                sendList.add(matchUriValue);
                sendList.addAll(list);
                list.clear();
                Message msg = mInsertHanlder.obtainMessage(MSG_INSERT_TO_DATABASE, 1, -1, sendList);
                mInsertHanlder.sendMessage(msg);
            }
        }
    }
    /// @}
}
