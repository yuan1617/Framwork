#define LOG_TAG "BufferQueue"
#define ATRACE_TAG ATRACE_TAG_GRAPHICS
//#define LOG_NDEBUG 0

#include <cmath>

#include <cutils/properties.h>
#include <cutils/xlog.h>

#include <binder/IPCThreadState.h>

#include <ui/gralloc_extra.h>

#include <gui/IGraphicBufferConsumer.h>
#include <gui/BufferQueueCore.h>
#include <gui/IConsumerListener.h>

#include <gui/mediatek/BufferQueueDebug.h>
#include <gui/mediatek/BufferQueueDump.h>
#include <gui/mediatek/BufferQueueMonitor.h>
#include <GraphicBufferUtil.h>

#undef BQ_LOGV
#undef BQ_LOGD
#undef BQ_LOGI
#undef BQ_LOGW
#undef BQ_LOGE

#define BQ_LOGV(x, ...) XLOGV("[%s](this:%p,id:%d,api:%d,p:%d,c:%d) "x, mConsumerName.string(), mBq.unsafe_get(), mId, mConnectedApi, mProducerPid, mConsumerPid, ##__VA_ARGS__)
#define BQ_LOGD(x, ...) XLOGD("[%s](this:%p,id:%d,api:%d,p:%d,c:%d) "x, mConsumerName.string(), mBq.unsafe_get(), mId, mConnectedApi, mProducerPid, mConsumerPid, ##__VA_ARGS__)
#define BQ_LOGI(x, ...) XLOGI("[%s](this:%p,id:%d,api:%d,p:%d,c:%d) "x, mConsumerName.string(), mBq.unsafe_get(), mId, mConnectedApi, mProducerPid, mConsumerPid, ##__VA_ARGS__)
#define BQ_LOGW(x, ...) XLOGW("[%s](this:%p,id:%d,api:%d,p:%d,c:%d) "x, mConsumerName.string(), mBq.unsafe_get(), mId, mConnectedApi, mProducerPid, mConsumerPid, ##__VA_ARGS__)
#define BQ_LOGE(x, ...) XLOGE("[%s](this:%p,id:%d,api:%d,p:%d,c:%d) "x, mConsumerName.string(), mBq.unsafe_get(), mId, mConnectedApi, mProducerPid, mConsumerPid, ##__VA_ARGS__)

#define BQP_LOGV(x, ...) XLOG_PRI(ANDROID_LOG_VORBOSE, "BufferQueueProducer", "[%s](this:%p,id:%d,api:%d,p:%d,c:%d) "x, mConsumerName.string(), mBq.unsafe_get(), mId, mConnectedApi, mProducerPid, mConsumerPid, ##__VA_ARGS__)
#define BQP_LOGD(x, ...) XLOG_PRI(ANDROID_LOG_DEBUG, "BufferQueueProducer", "[%s](this:%p,id:%d,api:%d,p:%d,c:%d) "x, mConsumerName.string(), mBq.unsafe_get(), mId, mConnectedApi, mProducerPid, mConsumerPid, ##__VA_ARGS__)
#define BQP_LOGI(x, ...) XLOG_PRI(ANDROID_LOG_INFO, "BufferQueueProducer", "[%s](this:%p,id:%d,api:%d,p:%d,c:%d) "x, mConsumerName.string(), mBq.unsafe_get(), mId, mConnectedApi, mProducerPid, mConsumerPid, ##__VA_ARGS__)
#define BQP_LOGW(x, ...) XLOG_PRI(ANDROID_LOG_WARNING, "BufferQueueProducer", "[%s](this:%p,id:%d,api:%d,p:%d,c:%d) "x, mConsumerName.string(), mBq.unsafe_get(), mId, mConnectedApi, mProducerPid, mConsumerPid, ##__VA_ARGS__)
#define BQP_LOGE(x, ...) XLOG_PRI(ANDROID_LOG_ERROR, "BufferQueueProducer", "[%s](this:%p,id:%d,api:%d,p:%d,c:%d) "x, mConsumerName.string(), mBq.unsafe_get(), mId, mConnectedApi, mProducerPid, mConsumerPid, ##__VA_ARGS__)

#define BQC_LOGV(x, ...) XLOG_PRI(ANDROID_LOG_VORBOSE, "BufferQueueConsumer", "[%s](this:%p,id:%d,api:%d,p:%d,c:%d) "x, mConsumerName.string(), mBq.unsafe_get(), mId, mConnectedApi, mProducerPid, mConsumerPid, ##__VA_ARGS__)
#define BQC_LOGD(x, ...) XLOG_PRI(ANDROID_LOG_DEBUG, "BufferQueueConsumer", "[%s](this:%p,id:%d,api:%d,p:%d,c:%d) "x, mConsumerName.string(), mBq.unsafe_get(), mId, mConnectedApi, mProducerPid, mConsumerPid, ##__VA_ARGS__)
#define BQC_LOGI(x, ...) XLOG_PRI(ANDROID_LOG_INFO, "BufferQueueConsumer", "[%s](this:%p,id:%d,api:%d,p:%d,c:%d) "x, mConsumerName.string(), mBq.unsafe_get(), mId, mConnectedApi, mProducerPid, mConsumerPid, ##__VA_ARGS__)
#define BQC_LOGW(x, ...) XLOG_PRI(ANDROID_LOG_WARNING, "BufferQueueConsumer", "[%s](this:%p,id:%d,api:%d,p:%d,c:%d) "x, mConsumerName.string(), mBq.unsafe_get(), mId, mConnectedApi, mProducerPid, mConsumerPid, ##__VA_ARGS__)
#define BQC_LOGE(x, ...) XLOG_PRI(ANDROID_LOG_ERROR, "BufferQueueConsumer", "[%s](this:%p,id:%d,api:%d,p:%d,c:%d) "x, mConsumerName.string(), mBq.unsafe_get(), mId, mConnectedApi, mProducerPid, mConsumerPid, ##__VA_ARGS__)

namespace android {
// -----------------------------------------------------------------------------
status_t drawDebugLineToGraphicBuffer(
        const sp<GraphicBuffer>& gb, uint32_t cnt, uint8_t val = 0xff) {
#ifndef MTK_EMULATOR_SUPPORT
    const uint32_t DEFAULT_LINE_W = 4;
    const uint32_t DEFAULT_LINE_H = 4;
    if (gb == NULL) {
        return INVALID_OPERATION;
    }

    int line_number_w = DEFAULT_LINE_W;
    int line_number_h = DEFAULT_LINE_H;
    int line_w = DEFAULT_LINE_W;
    int line_h = DEFAULT_LINE_H;

    char value[PROPERTY_VALUE_MAX];
    property_get("debug.bq.line_p", value, "-1");
    int line_pos = atoi(value);
    if (line_pos >= 0)
        cnt = line_pos;

    property_get("debug.bq.line_g", value, "-1");
    sscanf(value, "%d:%d", &line_w, &line_h);
    if (line_w > 0)
        line_number_w = line_w;
    if (line_h > 0)
        line_number_h = line_h;

    property_get("debug.bq.line_c", value, "-1");
    int8_t line_c = atoi(value);
    if (line_c >= 0)
        val = line_c;

    getGraphicBufferUtil().drawLine(gb, val, line_number_w, line_number_h, cnt);
#endif
    return NO_ERROR;
}

status_t getProcessName(int pid, String8& name) {
    FILE *fp = fopen(String8::format("/proc/%d/cmdline", pid), "r");
    if (NULL != fp) {
        const size_t size = 64;
        char proc_name[size];
        fgets(proc_name, size, fp);
        fclose(fp);

        name = proc_name;
        return NO_ERROR;
    }

    return INVALID_OPERATION;
}

BufferQueueDebug::BufferQueueDebug() :
    mBq(NULL),
    mId(-1),
    mConnectedApi(BufferQueueCore::NO_CONNECTED_API),
    mPid(-1),
    mProducerPid(-1),
    mConsumerPid(-1),
    mIsInGuiExt(false),
    mLine(false),
    mLineCnt(0),
    mDump(NULL),
    mGeneralDump(false)
{
}

BufferQueueDebug::~BufferQueueDebug() {
}

// BufferQueueCore part
// -----------------------------------------------------------------------------
void BufferQueueDebug::onConstructor(
        wp<BufferQueueCore> bq, const String8& consumerName) {
    mBq = bq;
    mPid = getpid();
    mConsumerName = consumerName;
    if (sscanf(consumerName.string(), "unnamed-%*d-%d", &mId) != 1) {
        BQ_LOGE("id info cannot be read from '%s'", consumerName.string());
    }

    if (NO_ERROR == getProcessName(mPid, mConsumerProcName)) {
        BQ_LOGI("BufferQueue core=(%d:%s)", mPid, mConsumerProcName.string());
    } else {
        BQ_LOGI("BufferQueue core=(%d:\?\?\?)", mPid);
    }

    mIsInGuiExt = (mConsumerProcName.find("guiext-server") != -1);
    mDump = new BufferQueueDump(mIsInGuiExt ?
            BufferQueueDump::TRACK_PRODUCER : BufferQueueDump::TRACK_CONSUMER, mBq.unsafe_get());

    if (mDump == NULL) {
        BQ_LOGE("new BufferQueueDump() failed in BufferQueue()");
    }
    // update dump name
    mDump->setName(consumerName);

    // check property for drawing debug line
    char value[PROPERTY_VALUE_MAX];
    property_get("debug.bq.line", value, "GOD'S IN HIS HEAVEN, ALL'S RIGHT WITH THE WORLD.");
    mLine = (-1 != consumerName.find(value));
    mLineCnt = 0;

    if (true == mLine) {
        BQ_LOGI("switch on debug line");
    }

    if (!mIsInGuiExt) {
        BufferQueueMonitor::getInstance().monitor(mBq);
    }
}

void BufferQueueDebug::onDestructor() {
    BQ_LOGI("~BufferQueueCore");

    if (!mIsInGuiExt) {
        BufferQueueMonitor::getInstance().unmonitor(mBq);
    }
}

void BufferQueueDebug::onDump(String8 &result, const String8& prefix) const {
    mDump->dump(result, prefix.string());
}

void BufferQueueDebug::onFreeBufferLocked(const int slot) {
    mDump->onFreeBuffer(slot);
}

// BufferQueueConsumer part
// -----------------------------------------------------------------------------
void BufferQueueDebug::onSetConsumerName(const String8& consumerName) {
    mConsumerName = consumerName;
    // update dump info
    mDump->setName(mConsumerName);

    // check property for drawing debug line
    BQC_LOGI("setConsumerName: %s", mConsumerName.string());
    char value[PROPERTY_VALUE_MAX];
    property_get("debug.bq.line", value, "GOD'S IN HIS HEAVEN, ALL'S RIGHT WITH THE WORLD.");
    mLine = (-1 != mConsumerName.find(value));
    mLineCnt = 0;

    if (true == mLine) {
        BQ_LOGI("switch on debug line");
    }
}

void BufferQueueDebug::onAcquire(
        const int buf,
        const sp<GraphicBuffer>& gb,
        const sp<Fence>& fence,
        const int64_t timestamp,
        const IGraphicBufferConsumer::BufferItem* const buffer) {
    // also inform acquireBuffer to mDump
    mDump->onAcquireBuffer(buf, gb, fence, timestamp);

    // draw white debug line
    if (true == mLine) {
        if (buffer->mFence.get())
            buffer->mFence->waitForever("BufferItemConsumer::acquireBuffer");

        drawDebugLineToGraphicBuffer(gb, mLineCnt);
        mLineCnt += 1;
    }
}

void BufferQueueDebug::onRelease(const int buf) {
    // also inform releaseBuffer to mDump
    mDump->onReleaseBuffer(buf);
}

void BufferQueueDebug::onConsumerConnect(
        const sp<IConsumerListener>& consumerListener,
        const bool controlledByApp) {
    // check if local or remote connection by the consumer listener
    // (in most cases, consumer side is a local connection)
    mConsumerPid = (NULL != consumerListener->asBinder()->localBinder())
                 ? getpid()
                 : IPCThreadState::self()->getCallingPid();

    String8 name;
    if (NO_ERROR == getProcessName(mConsumerPid, mConsumerProcName)) {
        BQC_LOGI("connect(C): consumer=(%d:%s) controlledByApp=%s",
            mConsumerPid, mConsumerProcName.string(), controlledByApp ? "true" : "false");
    } else {
        BQC_LOGI("connect(C): consumer=(%d:\?\?\?) controlledByApp=%s",
            mConsumerPid, controlledByApp ? "true" : "false");
    }
}

void BufferQueueDebug::onConsumerDisconnectHead() {
    mConsumerPid = -1;
}

void BufferQueueDebug::onConsumerDisconnectTail() {
    mDump->onConsumerDisconnect();
}

// BufferQueueProducer part
// -----------------------------------------------------------------------------
void BufferQueueDebug::setIonInfoOnDequeue(const sp<GraphicBuffer>& gb) {
#ifndef MTK_EMULATOR_SUPPORT
    gralloc_extra_ion_debug_t info;
    snprintf(info.name, 16, "p:%d c:%d", mProducerPid, mConsumerPid);
    gralloc_extra_perform(gb->handle, GRALLOC_EXTRA_SET_IOCTL_ION_DEBUG, &info);
#endif // MTK_EMULATOR_SUPPORT
}

void BufferQueueDebug::onDequeue(
        const int outBuf, sp<GraphicBuffer>& gb, sp<Fence>& fence) {
    mDump->onDequeueBuffer(outBuf, gb, fence);
}

void BufferQueueDebug::onQueue(const int buf, const int64_t timestamp) {
    // count FPS after queueBuffer() success, for producer side
    if (true == mQueueFps.update()) {
        BQP_LOGI("queueBuffer: fps=%.2f dur=%.2f max=%.2f min=%.2f",
                mQueueFps.getFps(),
                mQueueFps.getLastLogDuration() / 1e6,
                mQueueFps.getMaxDuration() / 1e6,
                mQueueFps.getMinDuration() / 1e6);
    }

    // also inform queueBuffer to mDump
    mDump->onQueueBuffer(buf, timestamp);
}

void BufferQueueDebug::onCancel(const int buf) {
    mDump->onCancelBuffer(buf);
}

void BufferQueueDebug::onProducerConnect(
        const sp<IBinder>& token, const int api, bool producerControlledByApp) {
    mProducerPid = (token != NULL && NULL != token->localBinder())
        ? getpid()
        : IPCThreadState::self()->getCallingPid();
    mConnectedApi = api;

    if (NO_ERROR == getProcessName(mProducerPid, mProducerProcName)) {
        BQP_LOGI("connect(P): api=%d producer=(%d:%s) producerControlledByApp=%s", mConnectedApi,
                mProducerPid, mProducerProcName.string(), producerControlledByApp ? "true" : "false");
    } else {
        BQP_LOGI("connect(P): api=%d producer=(%d:\?\?\?) producerControlledByApp=%s", mConnectedApi,
                mProducerPid, producerControlledByApp ? "true" : "false");
    }
}

void BufferQueueDebug::onProducerDisconnect() {
    mProducerPid = -1;
}

// -----------------------------------------------------------------------------
}; // namespace android
