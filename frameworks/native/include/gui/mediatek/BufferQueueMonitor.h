#ifndef ANDROID_GUI_BUFFERQUEUEMONITOR_H
#define ANDROID_GUI_BUFFERQUEUEMONITOR_H

#include <utils/String8.h>
#include <utils/KeyedVector.h>
#include <utils/Singleton.h>
#include <ui/mediatek/IDumpTunnel.h>

namespace android
{
class BufferQueueMonitor;
class BufferQueueCore;
class BufferQueueDumpTunnel : public BnDumpTunnel
{
    public:
        BufferQueueDumpTunnel(BufferQueueMonitor* monitor);
        virtual ~BufferQueueDumpTunnel();

        // IDumpTunnel interface
        virtual status_t kickDump(String8& /*result*/, const char* /*prefix*/);

    private:
        BufferQueueMonitor *mMonitor;
};

class BufferQueueMonitor : public Singleton<BufferQueueMonitor> 
{
    public:
        BufferQueueMonitor();
        virtual ~BufferQueueMonitor();

        status_t monitor(wp<BufferQueueCore> bq);
        status_t unmonitor(wp<BufferQueueCore> bq);

        status_t dump(String8& result, const char* prefix);

    private:
        status_t getProcessName();

        String8 mProcessName;

        bool mIsRegistered;
        sp<BufferQueueDumpTunnel> mBqDumpTunnel;

        mutable Mutex mMutex;
        KeyedVector<wp<BufferQueueCore>, int> mBqList;
};
};
#endif
