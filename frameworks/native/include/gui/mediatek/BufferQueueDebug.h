#ifndef ANDROID_GUI_BUFFERQUEUEDEBUG_H
#define ANDROID_GUI_BUFFERQUEUEDEBUG_H

#include <gui/IGraphicBufferConsumer.h>
#include <utils/RefBase.h>
#include <FpsCounter.h>

namespace android {
// ----------------------------------------------------------------------------

class String8;
class BufferQueueCore;
class BufferQueueDump;
struct BufferQueueDebug : public RefBase {
    // debug target BQ info
    wp<BufferQueueCore> mBq;
    int32_t mId;
    int mConnectedApi;
    String8 mConsumerName;

    // process info
    int32_t mPid;
    int32_t mProducerPid;
    int32_t mConsumerPid;
    String8 mProducerProcName;
    String8 mConsumerProcName;

    // track for producer buffer return
    FpsCounter mQueueFps;

    // track for consumer buffer return
    FpsCounter mReleaseFps;

    // whether the queue is hosted in GuiExtService or not
    bool mIsInGuiExt;

    // if debug line enabled
    bool mLine;

    // debug line count
    uint32_t mLineCnt;

    // for buffer dump
    sp<BufferQueueDump> mDump;

    // whether dump mechanism of general buffer queue is enabled or not
    bool mGeneralDump;

    BufferQueueDebug();
    virtual ~BufferQueueDebug();

    // BufferQueueCore part
    void onConstructor(wp<BufferQueueCore> bq, const String8& consumerName);
    void onDestructor();
    void onDump(String8 &result, const String8& prefix) const;
    void onFreeBufferLocked(const int slot);

    // BufferQueueConsumer part
    void onConsumerDisconnectHead();
    void onConsumerDisconnectTail();
    void onSetConsumerName(const String8& consumerName);
    void onAcquire(
            const int buf,
            const sp<GraphicBuffer>& gb,
            const sp<Fence>& fence,
            const int64_t timestamp,
            const IGraphicBufferConsumer::BufferItem* const buffer);
    void onRelease(const int buf);
    void onConsumerConnect(
            const sp<IConsumerListener>& consumerListener,
            const bool controlledByApp);

    // BufferQueueProducer part
    void setIonInfoOnDequeue(const sp<GraphicBuffer>& gb);
    void onDequeue(const int outBuf, sp<GraphicBuffer>& gb, sp<Fence>& fence);
    void onQueue(const int buf, const int64_t timestamp);
    void onCancel(const int buf);
    void onProducerConnect(
            const sp<IBinder>& token,
            const int api,
            bool producerControlledByApp);
    void onProducerDisconnect();
};

status_t getProcessName(int pid, String8& name);

// ----------------------------------------------------------------------------
}; // namespace android

#endif // ANDROID_GUI_BUFFERQUEUEDEBUG_H
