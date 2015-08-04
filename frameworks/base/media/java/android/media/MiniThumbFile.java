/*
 * Copyright (C) 2009 The Android Open Source Project
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

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.NonReadableChannelException;
import java.nio.channels.NonWritableChannelException;
import java.util.HashMap;
import java.util.zip.Adler32;

import android.net.Uri;
import android.os.Environment;

import libcore.io.IoUtils;

import com.mediatek.xlog.Xlog;

/**
 * M: This class handles the mini-thumb file. A mini-thumb file consists of three blocks: version header,
 * index header and data. version header store current thumb data version and active store thumb count,
 * index header store thumbnail original id, magic and offset position in data block indexed by id, data
 * store thumb data with BYTES_PER_MINTHUMB bytes for each one. the three blocks are format as bellow:
 * </p> version header(32 bytes):
 * </p> 1. version: int (4 bytes to store current version)
 * </p> 2. magic: long (8 bytes for version check magic number)
 * </p> 3. active count: int (4 bytes to store current active thumb data count)
 * </p> 4. author: long (8 bytes for author)
 * </p> 5. version header check sum: long (8 bytes for version check sum)
 *
 * </p> index header(32 bytes for each thumbnail, ):
 * </p> 1. original id: long (8 bytes to store thumbnail original image or video id)
 * </p> 2. magic: long (8 bytes for thumbnail magic number)
 * </p> 3. data check sum: long (8 bytes for thumb data check sum)
 * </p> 4. position: int (4 bytes for thumb data offset position)
 * </p> 5. length: int (4 bytes for thumb data length)
 *
 * </p> data(BYTES_PER_MINTHUMB bytes for each):
 * </p> only jpeg LEN part's bytes in data store thumb data, the remaining bytes are unused
 *
 * @hide This file is shared between MediaStore and MediaProvider and should remained internal use only.
 */
public class MiniThumbFile {
    private static final String TAG = "MiniThumbFile";

    // M: add version 6 to enhance mini thumb structure, all old version file will be deleted.
    private static final int MINI_THUMB_DATA_FILE_VERSION = 6;
    public static final int BYTES_PER_MINTHUMB = 16 * 1024; //old is 10000, not big enough, increase to 16K(16384);
    private Uri mUri;
    private static final HashMap<Long, MiniThumbDataFile> sMiniThumbDataFile = new HashMap<Long, MiniThumbDataFile>();
    private ByteBuffer mBuffer;
    private static MiniThumbFile sMiniThumbFile = null;

    /// M: Enhance MiniThumbFile structure. {@

    /// header size for version and index
    /**
     * M: Version header size, structure as below:
     * </p> 1. version: int
     * </p> 2. magic: long
     * </p> 3. active count: int
     * </p> 4. author: long
     * </p> 5. version header check sum: long
     */
    private static final int VH_VERSION_OFFSET = 0;
    private static final int VH_MAGIC_OFFSET = 4;
    private static final int VH_ACTIVECOUNT_OFFSET = 12;
    private static final int VH_AUTHOR_OFFSET = 16;
    private static final int VH_CHECKSUM_OFFSET = 24;
    private static final int VERSION_HEADER_SIZE = 4 + 8 + 4 + 8 + 8;

    /**
     * M: Index header size, structure as below:
     * </p> 1. original id: long
     * </p> 2. magic: long
     * </p> 3. data check sum: long
     * </p> 4. position: int
     * </p> 5. length: int
     */
    private static final int IH_ORIGINAL_ID_OFFSET = 0;
    private static final int IH_MAGIC_OFFSET = 8;
    private static final int IH_DATA_CHECKSUM_OFFSET = 16;
    private static final int IH_POSITION_OFFSET = 24;
    private static final int IH_LENGTH_OFFSET = 28;
    private static final int INDEX_HEADER_SIZE = 8 + 8 + 8 + 4 + 4;
    /**
     * M: Define the max stored thumb count of each mini thumb file can store. because of a memory page size is 4K
     * and max file write size is 512K, we make headers size as 512K to get better performance for memory and I/O
     */
    private static final int MAX_THUMB_COUNT_PER_FILE = 512 * 1024 / INDEX_HEADER_SIZE - 1;
    private static final int MAX_THUMB_FILE_SIZE = 50 * 1024 * 1024; // 50M

    /// magic check number for version
    private static final long MAGIC_THUMB_FILE = 0x20140218;
    /// author to save to thumb file
    private static final long AUTHOR = 0xC0EEC1D5;
    /// Save thumb data start offset, header size must be 512K, so data start offset is 512 * 1024=524288
    private static final int DATA_START_OFFSET = VERSION_HEADER_SIZE + MAX_THUMB_COUNT_PER_FILE * INDEX_HEADER_SIZE;

    private byte[] mVersionHeader = new byte[VERSION_HEADER_SIZE];
    private byte[] mIndexHeader = new byte[INDEX_HEADER_SIZE];
    private Adler32 mChecker = new Adler32(); // use to do check sum
    /// @}

    /**
     * M: Above android version ICS(4.0), all files are scanned to files table, so image and video have different
     * id, we can store their thumbnail in the same thumb file.
     */
    public static synchronized void reset() {
        if (sMiniThumbFile != null) {
            sMiniThumbFile.deactivate();
        }
        sMiniThumbFile = null;
    }

    public static synchronized MiniThumbFile instance(Uri uri) {
        if (sMiniThumbFile == null) {
            sMiniThumbFile = new MiniThumbFile(null);
        }
        return sMiniThumbFile;
    }

    /**
     * M: Get RandomAccessFile path to create a randomAccessFile with version and file index, every 10000 id will
     * has one randomAccessFile.(such as .thumbdata-6.0_0, .thumbdata-6.0_1)
     * @param id original id
     * @return RandomAccessFile path
     */
    private static String randomAccessFilePath(long id) {
        String storagePath = Environment.getExternalStorageDirectory().getPath();
        String directoryName = getMiniThumbFileDirectoryPath();
        int fileIndex = (int) id / MAX_THUMB_COUNT_PER_FILE;
        String fileName = getMiniThumbFilePrefix() + fileIndex;
        return storagePath + "/" + directoryName + "/" + fileName;
    }

    /**
     * M: get mini thumb data file with given original id
     * @param id original id
     * @return MiniThumbDataFile
     */
    private MiniThumbDataFile miniThumbDataFile(long id) {
        long fileIndex = id / MAX_THUMB_COUNT_PER_FILE;
        MiniThumbDataFile miniThumbDataFile = sMiniThumbDataFile.get(fileIndex);
        if (miniThumbDataFile == null) {
            String path = randomAccessFilePath(id);
            File directory = new File(path).getParentFile();
            if (!directory.isDirectory()) {
                if (!directory.mkdirs()) {
                    Xlog.e(TAG, "Unable to create .thumbnails directory " + directory.toString());
                }
            }
            File file = new File(path);
            try {
                miniThumbDataFile = new MiniThumbDataFile(new RandomAccessFile(file, "rw"), path);
            } catch (IOException ex) {
                // Open as read-only so we can at least read the existing thumbnails.
                Xlog.e(TAG, "miniThumbDataFile: IOException(rw) for: " + path + ", try read only mode", ex);
                try {
                    miniThumbDataFile = new MiniThumbDataFile(new RandomAccessFile(file, "r"), path);
                } catch (IOException ex2) {
                    Xlog.e(TAG, "miniThumbDataFile: IOException(r) for: " + path, ex2);
                }
            }
            if (miniThumbDataFile != null) {
                sMiniThumbDataFile.put(fileIndex, miniThumbDataFile);
            }
        }
        return miniThumbDataFile;
    }

    public MiniThumbFile(Uri uri) {
        mUri = uri;
        mBuffer = ByteBuffer.allocateDirect(BYTES_PER_MINTHUMB);
        Xlog.v(TAG, "activate MiniThumbFile " + this);
    }

    public synchronized void deactivate() {
        for (MiniThumbDataFile miniThumbDataFile : sMiniThumbDataFile.values()) {
            miniThumbDataFile.close();
        }
        sMiniThumbDataFile.clear();
        Xlog.v(TAG, "deactivate MiniThumbFile " + this);
    }

    // Get the magic number for the specified id in the mini-thumb file.
    // Returns 0 if the magic is not available.
    public synchronized long getMagic(long id) {
        // check the mini thumb file for the right data.  Right is
        // defined as having the right magic number at the offset
        // reserved for this "id".
        MiniThumbDataFile miniThumbDataFile = miniThumbDataFile(id);
        if (miniThumbDataFile != null) {
            try {
                long magic = miniThumbDataFile.getMagic(id);
                return magic;
            } catch (IOException ex) {
                Xlog.v(TAG, "Got exception checking file magic: ", ex);
            } catch (RuntimeException ex) {
                // Other NIO related exception like disk full, read only channel..etc
                Xlog.e(TAG, "Got exception when reading magic, id = " + id +
                        ", disk full or mount read-only? " + ex.getClass());
            }
        }
        return 0;
    }

    public synchronized void saveMiniThumbToFile(byte[] data, long id, long magic)
            throws IOException {
        MiniThumbDataFile miniThumbDataFile = miniThumbDataFile(id);
        if (miniThumbDataFile == null) return;
        try {
            Xlog.v(TAG, "saveMiniThumbToFile with : id = " + id + ", magic = " + magic);
            miniThumbDataFile.updateDataToThumbFile(data, id, magic);
        } catch (IOException ex) {
            Xlog.e(TAG, "couldn't save mini thumbnail data for "
                    + id + "; ", ex);
            throw ex;
        } catch (RuntimeException ex) {
            // Other NIO related exception like disk full, read only channel..etc
            Xlog.e(TAG, "couldn't save mini thumbnail data for "
                    + id + "; disk full or mount read-only? " + ex.getClass());
        }
    }

    /**
     * Gallery app can use this method to retrieve mini-thumbnail. Full size
     * images share the same IDs with their corresponding thumbnails.
     *
     * <br/>Now, it calls getMiniThumbFromFile(id, data, null) to get mini thumbnail.
     * @param id the ID of the image or video(same of full size image).
     * @param data the buffer to store mini-thumbnail.
     * @see MiniThumbFile#getMiniThumbFromFile(long, byte[], ThumbResult)
     * @deprecated for no check code result to be returned
     */
    public synchronized byte[] getMiniThumbFromFile(long id, byte [] data) {
        return getMiniThumbFromFile(id, data, null);
    }

    /**
     * M: Gallery app can use this method to retrieve mini-thumbnail. Full size
     * images share the same IDs with their corresponding thumbnails.
     *
     * If check code of read data is different from written,
     * null will be return instead of returning wrong data.
     * If inputed result is not null,
     * result.getDetail() will return right detail)
     * @param id the original ID of the image or video(same of full size image).
     * @param data the buffer to store mini-thumbnail.
     * @param result output the detail info for get mini thumb from file.
     * @return return stored mini thumb data from file
     */
    public synchronized byte[] getMiniThumbFromFile(long id, byte [] data, ThumbResult result) {
        MiniThumbDataFile miniThumbDataFile = miniThumbDataFile(id);
        if (miniThumbDataFile == null) return null;
        try {
            Xlog.v(TAG, "getMiniThumbFromFile for id " + id);
            return miniThumbDataFile.getDataFromThumbFile(data, id, result);
        } catch (IOException ex) {
            Xlog.w(TAG, "got exception when reading thumbnail id=" + id + ", exception: " + ex);
        } catch (RuntimeException ex) {
            // Other NIO related exception like disk full, read only channel..etc
            Xlog.e(TAG, "Got exception when reading thumbnail, id = " + id +
                    ", disk full or mount read-only? " + ex.getClass());
        }
        return null;
    }

    /**
     * M: get MiniThumbFile prefix, format is ".thumbdata-" + version + ".0_"(such as .thumbdata-6.0_)
     * @hide
     */
    public static String getMiniThumbFilePrefix() {
        return ".thumbdata-" + MINI_THUMB_DATA_FILE_VERSION + ".0_";
    }

    /**
     * M: Get save MiniThumbFile's directory path
     * @internal
     */
    public static String getMiniThumbFileDirectoryPath() {
        /// M: Move path from DCIM to Android/data/com.android.providers. media to avoid Camera can't create
        /// DCIM folder cause by MappedByteBuffer hold filemap, this memory map only release when JVM gc.
        // return "DCIM/.thumbnails";
        return ".thumbnails";
    }

    /**
     * M: create or get thumb result for more detail.
     */
    public static class ThumbResult {
        /**
         * unspecified
         */
        public static final int UNSPECIFIED = 0;
        /**
         * check code is not right.
         */
        public static final int WRONG_CHECK_CODE = 1;
        /**
         * success.
         */
        public static final int SUCCESS = 2;

        private int mDetail = UNSPECIFIED;

        void setDetail(int detail) {
            mDetail = detail;
        }

        /**
         * @return return the detail for process the thumbnail.
         */
        public int getDetail() {
            return mDetail;
        }
    }

    /// M: Enhance MiniThumbFile structure. {@
    /**
     * M: Inner class to handle mini thumb data request(getMagic, save mini thumb data and get mini thumb data)
     */
    private class MiniThumbDataFile {
        private RandomAccessFile mRandomAccessFile;
        private String mPath;
        private FileChannel mChannel;
        private MappedByteBuffer mIndexMappedBuffer;
        private int mActiveCount;

        public MiniThumbDataFile(RandomAccessFile miniThumbFile, String path) throws IOException {
            mRandomAccessFile = miniThumbFile;
            mPath = path;
            /// If load fail, reset mini thumb file.
            if (!load()) {
                reset();
            }
            /// map headers size 512K to memory, may the given RandomAccessFile is opened as read only mode, we
            /// need to try to map it with read only when map read write mode with NonWritableChannelException,
            /// so that caller app can use exist mini thumbnails.
            mChannel = mRandomAccessFile.getChannel();
            try {
                mIndexMappedBuffer = mChannel.map(FileChannel.MapMode.READ_WRITE, 0, DATA_START_OFFSET);
            } catch (NonWritableChannelException ex1) {
                Xlog.w(TAG, "map MiniThumbFile(READ_WRITE) with NonWritableChannelException, try READ_ONLY mode", ex1);
                try {
                    mIndexMappedBuffer = mChannel.map(FileChannel.MapMode.READ_ONLY, 0, DATA_START_OFFSET);
                } catch (NonReadableChannelException ex2) {
                    throw new IOException("try map as READ_ONLY mode with NonReadableChannelException");
                }
            }
            Xlog.v(TAG, "Create MiniThumbDataFile with size " + mRandomAccessFile.length() / 1024 + "KB");
        }

        private synchronized boolean load() throws IOException {
            /// get version header and check
            mRandomAccessFile.seek(0);
            if (mRandomAccessFile.read(mVersionHeader) != VERSION_HEADER_SIZE) {
                Xlog.w(TAG, "cannot read version header");
                return false;
            }
            /// 1. Check thumb file version
            if (readInt(mVersionHeader, VH_VERSION_OFFSET) != MINI_THUMB_DATA_FILE_VERSION) {
                Xlog.w(TAG, "miss MiniThumbDataFile version");
                return false;
            }
            /// 2. Check thumb file magic
            if (readLong(mVersionHeader, VH_MAGIC_OFFSET) != MAGIC_THUMB_FILE) {
                Xlog.w(TAG, "miss MiniThumbDataFile magic");
                return false;
            }
            /// 3. Check active count(< max count)
            if ((mActiveCount = readInt(mVersionHeader, VH_ACTIVECOUNT_OFFSET)) >= MAX_THUMB_COUNT_PER_FILE) {
                Xlog.w(TAG, "active count big than limit, need reset");
                return false;
            }
            /// 4. Check thumb file check sum
            if (readLong(mVersionHeader, VH_CHECKSUM_OFFSET) != checkSum(mVersionHeader, 0, VH_CHECKSUM_OFFSET)) {
                Xlog.w(TAG, "invalid version check sum, version header may be destoried");
                return false;
            }
            /// 5. Check thumb file size
            long size = mRandomAccessFile.length();
            if (size > MAX_THUMB_FILE_SIZE) {
                Xlog.w(TAG, "MiniThumbDataFile size is big than limit(current size = " + size / 1024 / 1024 + "M)");
                return false;
            }
            Xlog.d(TAG, "load MiniThumbDataFile with active count is " + mActiveCount);
            return true;
        }

        private synchronized void reset() throws IOException {
            Xlog.d(TAG, "reset MiniThumbDataFile " + mPath);
            mActiveCount = 0;
            /// truncate MiniThumbDataFile to zero, and create new one with 512K headers size.
            mRandomAccessFile.setLength(0);
            mRandomAccessFile.setLength(DATA_START_OFFSET);
            /// Update new version header
            mRandomAccessFile.seek(0);
            writeInt(mVersionHeader, VH_VERSION_OFFSET, MINI_THUMB_DATA_FILE_VERSION);
            writeLong(mVersionHeader, VH_MAGIC_OFFSET, MAGIC_THUMB_FILE);
            writeInt(mVersionHeader, VH_ACTIVECOUNT_OFFSET, mActiveCount);
            writeLong(mVersionHeader, VH_AUTHOR_OFFSET, AUTHOR);
            writeLong(mVersionHeader, VH_CHECKSUM_OFFSET, checkSum(mVersionHeader, 0, VH_CHECKSUM_OFFSET));
            mRandomAccessFile.write(mVersionHeader);
        }

        public synchronized int updateActiveCount() throws IOException {
            int currentActionCount = getActiveCount();
            writeInt(mVersionHeader, VH_VERSION_OFFSET, MINI_THUMB_DATA_FILE_VERSION);
            writeLong(mVersionHeader, VH_MAGIC_OFFSET, MAGIC_THUMB_FILE);
            writeInt(mVersionHeader, VH_ACTIVECOUNT_OFFSET, ++currentActionCount);
            writeLong(mVersionHeader, VH_AUTHOR_OFFSET, AUTHOR);
            writeLong(mVersionHeader, VH_CHECKSUM_OFFSET, checkSum(mVersionHeader, 0, VH_CHECKSUM_OFFSET));
            mIndexMappedBuffer.position(0);
            mIndexMappedBuffer.put(mVersionHeader);
            return currentActionCount;
        }

        public synchronized int getActiveCount() throws IOException {
            mIndexMappedBuffer.position(0);
            if (mIndexMappedBuffer.get(mVersionHeader) != null
                    && readLong(mVersionHeader, VH_CHECKSUM_OFFSET) == checkSum(mVersionHeader, 0, VH_CHECKSUM_OFFSET)) {
                mActiveCount = readInt(mVersionHeader, VH_ACTIVECOUNT_OFFSET);
                Xlog.v(TAG, "getActiveCount is " + mActiveCount);
                return mActiveCount;
            }
            Xlog.v(TAG, "invalid version header, reset MiniThumbDataFile");
            reset();
            return mActiveCount;
        }

        public synchronized void updateIndexHeader(byte[] header, long id) throws IOException {
            int position = VERSION_HEADER_SIZE + (int) id % MAX_THUMB_COUNT_PER_FILE * INDEX_HEADER_SIZE;
            mIndexMappedBuffer.position(position);
            mIndexMappedBuffer.put(header, 0, INDEX_HEADER_SIZE);
        }

        public synchronized ByteBuffer getIndexHeader(byte[] header, long id) throws IOException {
            int position = VERSION_HEADER_SIZE + (int) id % MAX_THUMB_COUNT_PER_FILE * INDEX_HEADER_SIZE;
            mIndexMappedBuffer.position(position);
            return mIndexMappedBuffer.get(header, 0, INDEX_HEADER_SIZE);
        }

        public synchronized long getMagic(long id) throws IOException {
            getIndexHeader(mIndexHeader, id);
            long storedId = readLong(mIndexHeader, IH_ORIGINAL_ID_OFFSET);
            long magic = readLong(mIndexHeader, IH_MAGIC_OFFSET);
            if (storedId == id) {
                Xlog.v(TAG, "getMagic succuss with: id = " + id + ", magic = " + magic);
                return magic;
            } else {
                Xlog.v(TAG, "getMagic fail for id " + id + " with store id is " + storedId);
                return 0;
            }
        }

        public synchronized void updateDataToThumbFile(byte[] data, long id, long magic) throws IOException {
            if (data == null || data.length > BYTES_PER_MINTHUMB) {
                Xlog.v(TAG, "updateDataToThumbFile with invalid data");
                return;
            }
            /// check whether have store thumb date to file with this id, if yes we need overwrite
            // it with new data.
            int position = 0;
            if (getIndexHeader(mIndexHeader, id) != null && readLong(mIndexHeader, IH_ORIGINAL_ID_OFFSET) == id) {
                /// use old position to overwrite old data
                position = readInt(mIndexHeader, IH_POSITION_OFFSET);
            } else {
                /// get new position to store new data
                position = DATA_START_OFFSET + updateActiveCount() * BYTES_PER_MINTHUMB;
            }
            /// 1. update index header first
            writeLong(mIndexHeader, IH_ORIGINAL_ID_OFFSET, id);
            writeLong(mIndexHeader, IH_MAGIC_OFFSET, magic);
            writeLong(mIndexHeader, IH_DATA_CHECKSUM_OFFSET, checkSum(data));
            writeInt(mIndexHeader, IH_POSITION_OFFSET, position);
            writeInt(mIndexHeader, IH_LENGTH_OFFSET, data.length);
            updateIndexHeader(mIndexHeader, id);
            /// 2. update data second
            mBuffer.clear();
            mBuffer.put(data);
            mBuffer.flip();
            FileLock lock = null;
            try {
                lock = mChannel.lock(position, BYTES_PER_MINTHUMB, false);
                mChannel.write(mBuffer, position);
            } finally {
                try {
                    if (lock != null)
                        lock.release();
                } catch (IOException ex) {
                    Xlog.e(TAG, "updateDataToThumbFile: can not release lock!", ex);
                }
            }
            Xlog.v(TAG, "updateDataToThumbFile succuss with " + bufferToString(mIndexHeader));
        }

        public synchronized byte[] getDataFromThumbFile(byte[] data, long id, ThumbResult result) throws IOException {
            if (getIndexHeader(mIndexHeader, id) == null) {
                Xlog.w(TAG, "can not get index header for id " + id);
                return null;
            }
            /// get id, magic, data check sum, position, length and index check sum from index header
            long oldId = readLong(mIndexHeader, IH_ORIGINAL_ID_OFFSET);
            long magic = readLong(mIndexHeader, IH_MAGIC_OFFSET);
            long dataCheckSum = readLong(mIndexHeader, IH_DATA_CHECKSUM_OFFSET);
            int position = readInt(mIndexHeader, IH_POSITION_OFFSET);
            int length = readInt(mIndexHeader, IH_LENGTH_OFFSET);
            /// check these values whether valid.
            if (oldId != id) {
                Xlog.w(TAG, "invalid store original id : store id = " + oldId + ", given id = " + id);
                return null;
            }
            if (data.length < length) {
                Xlog.w(TAG, "invalid store data length: store length = " + length + ", given length = " + data.length);
                return null;
            }
            /// get data from thumb file
            FileLock lock = null;
            try {
                mBuffer.clear();
                lock = mChannel.lock(position, BYTES_PER_MINTHUMB, false);
                if (mChannel.read(mBuffer, position) >= length) {
                    mBuffer.position(0);
                    mBuffer.get(data, 0, length);
                    if (dataCheckSum == checkSum(data, 0 , length)) {
                        if (result != null) {
                            result.setDetail(ThumbResult.SUCCESS);
                        }
                        Xlog.v(TAG, "getDataFromThumbFile success with " + bufferToString(mIndexHeader));
                        return data;
                    } else {
                        if (result != null) {
                            result.setDetail(ThumbResult.WRONG_CHECK_CODE);
                        }
                    }
                }
            } finally {
                try {
                    if (lock != null)
                        lock.release();
                } catch (IOException ex) {
                    Xlog.e(TAG, "getDataFromThumbFile: can not release lock!", ex);
                }
            }
            Xlog.v(TAG, "getDataFromThumbFile fail with " + bufferToString(mIndexHeader));
            return null;
        }

        public synchronized void close() {
            Xlog.v(TAG, "close MiniThumbDataFile " + mPath);
            syncAll();
            IoUtils.closeQuietly(mRandomAccessFile);
            IoUtils.closeQuietly(mChannel);
            mRandomAccessFile = null;
            mChannel = null;
            mIndexMappedBuffer = null;
        }

        public void syncIndex() {
            try {
                mIndexMappedBuffer.force();
            } catch (Throwable t) {
                Xlog.w(TAG, "sync MiniThumbDataFile index failed", t);
            }
        }

        public void syncAll() {
            syncIndex();
            try {
                mRandomAccessFile.getFD().sync();
            } catch (Throwable t) {
                Xlog.w(TAG, "sync MiniThumbDataFile failed", t);
            }
        }

        public String bufferToString(byte[] buffer) {
            StringBuilder builder = new StringBuilder();
            builder.append("id = ").append(readLong(buffer, IH_ORIGINAL_ID_OFFSET));
            builder.append(", magic = ").append(readLong(buffer, IH_MAGIC_OFFSET));
            builder.append(", data checksum = ").append(readLong(buffer, IH_DATA_CHECKSUM_OFFSET));
            builder.append(", position = ").append(readInt(buffer, IH_POSITION_OFFSET));
            builder.append(", length = ").append(readInt(buffer, IH_LENGTH_OFFSET));
            return builder.toString();
        }
    }

    private int readInt(byte[] buf, int offset) {
        return (buf[offset] & 0xff)
                | ((buf[offset + 1] & 0xff) << 8)
                | ((buf[offset + 2] & 0xff) << 16)
                | ((buf[offset + 3] & 0xff) << 24);
    }

    private long readLong(byte[] buf, int offset) {
        long result = buf[offset + 7] & 0xff;
        for (int i = 6; i >= 0; i--) {
            result = (result << 8) | (buf[offset + i] & 0xff);
        }
        return result;
    }

    private void writeInt(byte[] buf, int offset, int value) {
        for (int i = 0; i < 4; i++) {
            buf[offset + i] = (byte) (value & 0xff);
            value >>= 8;
        }
    }

    private void writeLong(byte[] buf, int offset, long value) {
        for (int i = 0; i < 8; i++) {
            buf[offset + i] = (byte) (value & 0xff);
            value >>= 8;
        }
    }

    private long checkSum(byte[] data) {
        mChecker.reset();
        mChecker.update(data);
        return mChecker.getValue();
    }

    private long checkSum(byte[] data, int offset, int length) {
        mChecker.reset();
        mChecker.update(data, offset, length);
        return mChecker.getValue();
    }
    /// @}
}
