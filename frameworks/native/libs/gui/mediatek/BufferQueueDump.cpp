#define LOG_TAG "BufferQueueDump"
#define ATRACE_TAG ATRACE_TAG_GRAPHICS

#include <sys/stat.h>

#include <selinux/android.h>

#include <gui/IGraphicBufferConsumer.h>
#include <gui/BufferQueueCore.h>
#include <cutils/xlog.h>
#include <cutils/properties.h>

// TODO: check whether emulator support GraphicBufferUtil
#include <GraphicBufferUtil.h>

#include <gui/mediatek/BufferQueueDump.h>

// ----------------------------------------------------------------------------
#define PROP_DUMP_NAME      "debug.bq.dump"
#define DEFAULT_DUMP_NAME   "[none]"

#define DUMP_FILE_PATH      "/data/SF_dump/"

#define ARRAY_SIZE(arr) (sizeof(arr) / sizeof((arr)[0]))

#define BQD_LOGV(x, ...) XLOGV("[%s] "x, mName.string(), ##__VA_ARGS__)
#define BQD_LOGD(x, ...) XLOGD("[%s] "x, mName.string(), ##__VA_ARGS__)
#define BQD_LOGI(x, ...) XLOGI("[%s] "x, mName.string(), ##__VA_ARGS__)
#define BQD_LOGW(x, ...) XLOGW("[%s] "x, mName.string(), ##__VA_ARGS__)
#define BQD_LOGE(x, ...) XLOGE("[%s] "x, mName.string(), ##__VA_ARGS__)


namespace android {

// ----------------------------------------------------------------------------
BufferQueueDump::BufferQueueDump(const MODE& mode, const wp<BufferQueueCore>& bq) :
        mName("unnamed BufferQueueDump"),
        mBackupBufPusher(NULL),
        mBackupBufDumper(NULL),
        mIsBackupBufInited(false),
        mMode(mode),
        mBq(bq),
        mIsRegistered(false),
        mObtainedBufs(NULL),
        mLastObtainedBuf(NULL),
        mPid(getpid())
{
    mProcName = "\?\?\?";
    FILE *fp = fopen(String8::format("/proc/%d/cmdline", mPid), "r");
    if (NULL != fp) {
        const size_t size = 64;
        char proc_name[size];
        fgets(proc_name, size, fp);
        fclose(fp);

        mProcName = proc_name;
    }
}

void BufferQueueDump::setName(const String8& name) {
    mName = name;

    // update dumper's name
    if (mBackupBufDumper != NULL) {
        mBackupBufDumper->setName(name);
    }

    // check and reset current dump setting

    char value[PROPERTY_VALUE_MAX];
    property_get(PROP_DUMP_NAME, value, DEFAULT_DUMP_NAME);

    uint32_t backupCnt = 0;
    bool isMatched = parseDumpConfig(value, &backupCnt);
    if (isMatched && strstr(value, "#") != NULL) {
        setBackupCount(backupCnt);
    }
}

void BufferQueueDump::setBackupCount(int32_t count) {
    BQD_LOGD("setBackupCount: %d", count);
    if (count > 0) {
        // create backup buffer if needed
        if (!mIsBackupBufInited) {
            mBackupBufPusher = new BackupBufPusher(mBackupBuf);
            mBackupBufDumper = new BackupBufDumper(mBackupBuf);
            if ((mBackupBufPusher != NULL) && (mBackupBufDumper != NULL)) {
                mBackupBufDumper->setName(mName);
                sp< RingBuffer< sp<BackupBuffer> >::Pusher > proxyPusher = mBackupBufPusher;
                sp< RingBuffer< sp<BackupBuffer> >::Dumper > proxyDumper = mBackupBufDumper;
                mBackupBuf.setPusher(proxyPusher);
                mBackupBuf.setDumper(proxyDumper);
                mIsBackupBufInited = true;
            } else {
                mBackupBufPusher.clear();
                mBackupBufDumper.clear();
                count = 0;
                BQD_LOGE("[%s] create Backup pusher or dumper failed", __func__);
            }
        }

        // resize backup buffer
        mBackupBuf.resize(count);
    } else {
        mBackupBuf.resize(0);
    }
}


void BufferQueueDump::dumpObtainedBufs() {
    String8 name;
    const char* bufName = (TRACK_PRODUCER == mMode) ? "Dequeued" : "Acquired";
    getDumpFileName(name, mName);
    // dump acquired buffers
    if (!mObtainedBufs.size()) {
        // if no acquired buf, try to dump the last one kept
        if (mLastObtainedBuf != NULL) {
            String8 name_prefix = String8::format("[%s](LAST_ts%lld)",
                    name.string(), ns2ms(mLastObtainedBuf->mTimeStamp));
            mLastObtainedBuf->dump(name_prefix);

            BQD_LOGD("[dump] LAYER, handle(%p)", mLastObtainedBuf->mGraphicBuffer->handle);
        }
    } else {
        // dump acquired buf old to new
        for (uint32_t i = 0; i < mObtainedBufs.size(); i++) {
            const sp<DumpBuffer>& buffer = mObtainedBufs[i];
            if (buffer->mGraphicBuffer != NULL) {
                String8 name_prefix = String8::format("[%s](%s%02u_ts%lld)",
                        name.string(), bufName, i, ns2ms(buffer->mTimeStamp));
                buffer->dump(name_prefix);

                BQD_LOGD("[dump] %s:%02u, handle(%p)", bufName, i, buffer->mGraphicBuffer->handle);
            }
        }
    }
}

int BufferQueueDump::checkBackupCnt(char* str) {
    BQD_LOGD("checkBackupCnt: str:%s", str);
    int cnt = 0;
    char *numberSign = strchr(str, '#');

    if (!numberSign)
        return cnt;
    *numberSign = '\0';
    cnt = atoi(numberSign + 1);
    return cnt;
}

bool BufferQueueDump::matchProc(char* str) {
    BQD_LOGD("matchProc: str:%s mConsumerProcName:%s(%d)", str, mProcName.string(), mPid);
    char *pProc = strstr(str, "@@");
    bool isMatched = false;

    if (pProc) {
        pProc += strlen("@@");
        // matching process's name

        // if pProc is equal to "", it means all process
        if (strlen(pProc) == 0) {
            return true;
        }
        int pid = atoi(pProc);
        BQD_LOGD("pid:%d pProc:%s", pid, pProc);
        return pid != 0 && pid == mPid ? true : false;
    } else if ((pProc = strstr(str, "@")) != NULL) {
        pProc += strlen("@");
        // matching process's pid
        return (!strlen(pProc) || strstr(mProcName, pProc)) ? true : false;
    }
    return false;
}

bool BufferQueueDump::matchCName(char* str) {
    BQD_LOGD("matchName: str:%s mName:%s \n", str, mName.string());

    return strstr(mName, str) != NULL ? true : false;
}

enum ACTION {
    ACTION_NONE,
    ACTION_INCLUDE,
    ACTION_EXCLUDE
};

int BufferQueueDump::match(char* substr) {
    // '^' means matching BufferQueue is excluded and others are included
    // the semantic is ambiguous in '@@;^Frame' case
    char* inversion = strchr(substr, '^');
    //char* inversion = strlen(substr) > 0 && substr[0] == '^';
    if (inversion) {
        substr = inversion + 1;
    }
    bool isMatchProc = true;
    char *atSign = strchr(substr, '@');
    if (atSign) {
        isMatchProc = matchProc(atSign);
        BQD_LOGD("matchProc: %d", isMatchProc);
        *atSign = '\0';
    }
    bool isMatchName = true;
    if (strlen(substr)) {
        isMatchName = matchCName(substr);
        BQD_LOGD("matchCName: %d", isMatchName);
    }
    if (inversion) {
        BQD_LOGD("match result:%d", !(isMatchProc && isMatchName) ? ACTION_INCLUDE : ACTION_EXCLUDE);
        return !(isMatchProc && isMatchName) ? ACTION_INCLUDE : ACTION_EXCLUDE;
    } else {
        BQD_LOGD("match result:%d", isMatchProc && isMatchName ? ACTION_INCLUDE : ACTION_NONE);
        return isMatchProc && isMatchName ? ACTION_INCLUDE : ACTION_NONE;
    }
}

bool BufferQueueDump::parseDumpConfig(const char* value, uint32_t* pBackupCnt) {
    if (!value || !pBackupCnt) {
        BQD_LOGE("invalid value:%p pBackupCnt:%p", value, pBackupCnt);
        return false;
    }

    bool isMatched = false;
    bool isSetting = (strchr(value, '#') != NULL);
    *pBackupCnt = 0;

    // should not modify value, so backup value
    char str[PROPERTY_VALUE_MAX] = "";
    const uint32_t strSize = PROPERTY_VALUE_MAX - 1 < strlen(value) ? PROPERTY_VALUE_MAX - 1 : strlen(value);
    memmove(str, value, strSize);
    str[strSize] = '\0';

    // split str into substrs
    Vector<char*> substrs;
    substrs.push(str);
    const char* delimiter = ";";
    char *substr = strtok(str, delimiter);
    while (substr) {
        substrs.push(substr);
        substr = strtok(NULL, delimiter);
    }

    // start matching from tail
    // because the latter rule will override the former
    BQD_LOGD("parse str:%s", value);
    for (int32_t i = substrs.size() - 1; i >= 0; --i) {
        BQD_LOGD("parse substr:%s", substrs[i]);
        // check invalid rules
        char *numSign = strchr(substrs[i], '#');
        if ((numSign && !(numSign - substrs[i])) ||
                !strlen(substrs[i]) ||
                strchr(substrs[i], ' ')) {
            BQD_LOGW("invalid matching rules");
            continue;
        }
        uint32_t tmpBackupCnt = checkBackupCnt(substrs[i]);

        int matchResult = match(substrs[i]);
        if (isSetting) {
            if (matchResult == ACTION_INCLUDE) {
                isMatched = true;
                *pBackupCnt = tmpBackupCnt;
                break;
            }
        } else {
            if (matchResult != ACTION_NONE) {
                isMatched = (matchResult == ACTION_INCLUDE) ? true : false;
                break;
            }
        }
    }
    BQD_LOGD("parse * isMatched:%d backupCnt:%d", isMatched, *pBackupCnt);

    return isMatched;
}


void BufferQueueDump::dump(String8& result, const char* prefix) {
    // dump status to log buffer first
    const char* bufName = (TRACK_PRODUCER == mMode) ? "Dequeued" : "Acquired";

    result.appendFormat("%s*BufferQueueDump mIsBackupBufInited=%d, m%sBufs(size=%d), mMode=%s\n",
            prefix, mIsBackupBufInited, bufName, mObtainedBufs.size(),
            (TRACK_PRODUCER == mMode) ? "TRACK_PRODUCER" : "TRACK_CONSUMER");

    if ((mLastObtainedBuf != NULL) && (mLastObtainedBuf->mGraphicBuffer != NULL)) {
        result.appendFormat("%s [-1] mLast%sBuf->mGraphicBuffer->handle=%p\n",
                prefix, bufName, mLastObtainedBuf->mGraphicBuffer->handle);
    }

    for (size_t i = 0; i < mObtainedBufs.size(); i++) {
        const sp<DumpBuffer>& buffer = mObtainedBufs[i];
        result.appendFormat("%s [%02u] handle=%p, fence=%p, time=%#llx\n",
            prefix, i, buffer->mGraphicBuffer->handle, buffer->mFence.get(), buffer->mTimeStamp);
    }

    // start buffer dump check and process
    char value[PROPERTY_VALUE_MAX];
    property_get(PROP_DUMP_NAME, value, DEFAULT_DUMP_NAME);
    if (strcmp(value, DEFAULT_DUMP_NAME) == 0 || strlen(value) == 0) {
        // debug feature (bqdump) is not enabled
        return;
    }

    // For aee manual dump, we must create a directory to save files.
    // The step should not be completed by a script.
    struct stat sb;
    if (stat(DUMP_FILE_PATH, &sb) != 0) {
        // ths permission of /data/SF_dump must be 777,
        // or some processes cannot save files to /data/SF_dump
        mode_t mode = umask(0);
        if (mkdir(DUMP_FILE_PATH, 0777) != 0) {
            BQD_LOGE("mkdir(%s) failed", DUMP_FILE_PATH);
        }
        umask(mode);
        if (selinux_android_restorecon(DUMP_FILE_PATH, 0) == -1) {
            BQD_LOGE("restorecon failed(%s) failed", DUMP_FILE_PATH);
        } else {
            BQD_LOGD("restorecon(%s)", DUMP_FILE_PATH);
        }
    }
    if (access(DUMP_FILE_PATH, R_OK | W_OK | X_OK) != 0) {
        BQD_LOGE("The permission of %s cannot be access by this process", DUMP_FILE_PATH);
    }

    uint32_t backupCnt = 0;
    bool isMatched = parseDumpConfig(value, &backupCnt);

    // if value contains '#', it means setting continues dump
    // otherwise, dump buffers
    if (strchr(value, '#') != NULL) {
        if (isMatched) {
            setBackupCount(backupCnt);
        }
    } else {
        if (isMatched) {
            if (mBackupBuf.getSize() > 0) {
                mBackupBuf.dump(result, prefix);
            }
            dumpObtainedBufs();
        }
    }
}

void BufferQueueDump::getDumpFileName(String8& fileName, const String8& name) {
    fileName = name;

    // check file name, filter out invalid chars
    const char invalidChar[] = {'\\', '/', ':', '*', '?', '"', '<', '>', '|'};
    size_t size = fileName.size();
    char *buf = fileName.lockBuffer(size);
    for (unsigned int i = 0; i < ARRAY_SIZE(invalidChar); i++) {
        for (size_t c = 0; c < size; c++) {
            if (buf[c] == invalidChar[i]) {
                // find invalid char, replace it with '_'
                buf[c] = '_';
            }
        }
    }
    fileName.unlockBuffer(size);
}

void BufferQueueDump::addBuffer(const int& slot,
                                const sp<GraphicBuffer>& buffer,
                                const sp<Fence>& fence,
                                const int64_t& timestamp) {
    if (buffer == NULL) {
        return;
    }

    sp<DumpBuffer> v = mObtainedBufs.valueFor(slot);
    if (v == NULL) {
        sp<DumpBuffer> b = new DumpBuffer(buffer, fence, timestamp);
        mObtainedBufs.add(slot, b);
        mLastObtainedBuf = NULL;
    } else {
        BQD_LOGW("[%s] slot(%d) acquired, seems to be abnormal, just update ...", __func__, slot);
        v->mGraphicBuffer = buffer;
        v->mFence = fence;
        v->mTimeStamp = timestamp;
    }
}

void BufferQueueDump::updateBuffer(const int& slot, const int64_t& timestamp) {
    if (mBackupBuf.getSize() > 0) {
        const sp<DumpBuffer>& v = mObtainedBufs.valueFor(slot);
        if (v != NULL) {
            // push GraphicBuffer into backup buffer if buffer ever Acquired
            sp<BackupBuffer> buffer = NULL;
            if (timestamp != -1)
                buffer = new BackupBuffer(v->mGraphicBuffer, timestamp);
            else
                buffer = new BackupBuffer(v->mGraphicBuffer, v->mTimeStamp);
            mBackupBuf.push(buffer);
        }
    }

    // keep for the last one before removed
    if (1 == mObtainedBufs.size()) {
        if (timestamp != -1)
            mObtainedBufs[0]->mTimeStamp = timestamp;

        mLastObtainedBuf = mObtainedBufs[0];
    }
    mObtainedBufs.removeItem(slot);
}

void BufferQueueDump::onAcquireBuffer(const int& slot,
                                      const sp<GraphicBuffer>& buffer,
                                      const sp<Fence>& fence,
                                      const int64_t& timestamp) {
    if (TRACK_CONSUMER == mMode) {
        addBuffer(slot, buffer, fence, timestamp);
    }
}

void BufferQueueDump::onReleaseBuffer(const int& slot) {
    if (TRACK_CONSUMER == mMode)
        updateBuffer(slot);
}

void BufferQueueDump::onFreeBuffer(const int& slot) {
    if (TRACK_CONSUMER == mMode)
        updateBuffer(slot);
}

void BufferQueueDump::onDequeueBuffer(const int& slot,
                                      const sp<GraphicBuffer>& buffer,
                                      const sp<Fence>& fence) {
    if (TRACK_PRODUCER == mMode)
        addBuffer(slot, buffer, fence);
}

void BufferQueueDump::onQueueBuffer(const int& slot, const int64_t& timestamp) {
    if (TRACK_PRODUCER == mMode)
        updateBuffer(slot, timestamp);
}

void BufferQueueDump::onCancelBuffer(const int& slot) {
    if (TRACK_PRODUCER == mMode)
        updateBuffer(slot);
}


void BufferQueueDump::onConsumerDisconnect() {
    mName += "(consumer disconnected)";

    mBackupBuf.resize(0);
    mBackupBufPusher = NULL;
    mBackupBufDumper = NULL;
    mIsBackupBufInited = false;

    mObtainedBufs.clear();
    mLastObtainedBuf = NULL;
}

status_t BufferQueueDump::kickDump(String8& result, const char* prefix) {
    sp<BufferQueueCore> bq = mBq.promote();
    if (bq != NULL) {
        bq->dump(result, prefix);
        return NO_ERROR;
    } else {
        XLOGE("kickDump() failed because BufferQueue(%p) is dead", mBq.unsafe_get());
        return DEAD_OBJECT;
    }
}
// ----------------------------------------------------------------------------
bool BackupBufPusher::push(const sp<BackupBuffer>& in) {
    if ((in == NULL) || (in->mGraphicBuffer == NULL)) {
        return false;
    }

    sp<BackupBuffer>& buffer = editHead();

    // check property of GraphicBuffer, realloc if needed
    bool needCreate = false;
    if ((buffer == NULL) || (buffer->mGraphicBuffer == NULL)) {
        needCreate = true;
    } else {
        if ((buffer->mGraphicBuffer->width != in->mGraphicBuffer->width) ||
            (buffer->mGraphicBuffer->height != in->mGraphicBuffer->height) ||
            (buffer->mGraphicBuffer->format != in->mGraphicBuffer->format)) {
            needCreate = true;
            XLOGD("[%s] geometry changed, backup=(%d, %d, %d) => active=(%d, %d, %d)",
                __func__, buffer->mGraphicBuffer->width, buffer->mGraphicBuffer->height,
                buffer->mGraphicBuffer->format, in->mGraphicBuffer->width,
                in->mGraphicBuffer->height, in->mGraphicBuffer->format);
        }
    }

    if (needCreate) {
        sp<GraphicBuffer> newGraphicBuffer = new GraphicBuffer(
                                             in->mGraphicBuffer->width, in->mGraphicBuffer->height,
                                             in->mGraphicBuffer->format, in->mGraphicBuffer->usage);
        if (newGraphicBuffer == NULL) {
            XLOGE("[%s] alloc GraphicBuffer failed", __func__);
            return false;
        }

        if (buffer == NULL) {
            buffer = new BackupBuffer();
            if (buffer == NULL) {
                XLOGE("[%s] alloc BackupBuffer failed", __func__);
                return false;
            }
        }

        buffer->mGraphicBuffer = newGraphicBuffer;
    }

    int width = in->mGraphicBuffer->width;
    int height = in->mGraphicBuffer->height;
    int format = in->mGraphicBuffer->format;
    int usage = in->mGraphicBuffer->usage;
    int stride = in->mGraphicBuffer->stride;

    uint32_t bits = getGraphicBufferUtil().getBitsPerPixel(format);
    status_t err;

    // backup
    void *src;
    void *dst;
    err = in->mGraphicBuffer->lock(GraphicBuffer::USAGE_SW_READ_OFTEN, &src);
    if (err != NO_ERROR) {
        XLOGE("[%s] lock GraphicBuffer failed", __func__);
        return false;
    }

    err = buffer->mGraphicBuffer->lock(GraphicBuffer::USAGE_SW_READ_OFTEN | GraphicBuffer::USAGE_SW_WRITE_OFTEN, &dst);
    if (err != NO_ERROR) {
        in->mGraphicBuffer->unlock();
        XLOGE("[%s] lock backup buffer failed", __func__);
        return false;
    }

    memcpy(dst, src, (stride * height * bits) >> 3);

    buffer->mGraphicBuffer->unlock();
    in->mGraphicBuffer->unlock();

    // update timestamp
    buffer->mTimeStamp = in->mTimeStamp;
    buffer->mSourceHandle = in->mGraphicBuffer->handle;

    return true;
}

// ----------------------------------------------------------------------------
void BackupBufDumper::dump(String8 &result, const char* prefix) {
    // dump status to log buffer first
    result.appendFormat("%s*BackupBufDumper mRingBuffer(size=%u, count=%u)\n",
        prefix, mRingBuffer.getSize(), mRingBuffer.getCount());

    for (size_t i = 0; i < mRingBuffer.getValidSize(); i++) {
        const sp<BackupBuffer>& buffer = getItem(i);
        result.appendFormat("%s [%02u] handle(source=%p, backup=%p)\n",
            prefix, i, buffer->mSourceHandle, buffer->mGraphicBuffer->handle);
    }

    // start buffer dump check and process
    String8 name;
    String8 name_prefix;

    BufferQueueDump::getDumpFileName(name, mName);

    for (size_t i = 0; i < mRingBuffer.getValidSize(); i++) {
        const sp<BackupBuffer>& buffer = getItem(i);
        name_prefix = String8::format("[%s](Backup%02u_H%p_ts%" PRId64 ")",
                                      name.string(), i, buffer->mSourceHandle, ns2ms(buffer->mTimeStamp));
        getGraphicBufferUtil().dump(buffer->mGraphicBuffer, name_prefix.string(), DUMP_FILE_PATH);

        BQD_LOGI("[dump] Backup:%02u, handle(source=%p, backup=%p)",
            i, buffer->mSourceHandle, buffer->mGraphicBuffer->handle);
    }
}

// ----------------------------------------------------------------------------
void DumpBuffer::dump(const String8& prefix) {
    if (mFence != NULL) {
        mFence->waitForever(__func__);
    }
    getGraphicBufferUtil().dump(mGraphicBuffer, prefix.string(), DUMP_FILE_PATH);
}

}
