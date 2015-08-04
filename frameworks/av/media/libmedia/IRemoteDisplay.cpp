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

#include <stdint.h>
#include <sys/types.h>

#include <gui/IGraphicBufferProducer.h>

#include <media/IRemoteDisplay.h>

namespace android {

enum {
    DISPOSE = IBinder::FIRST_CALL_TRANSACTION,
    PAUSE,
    RESUME,
#ifdef MTK_AOSP_ENHANCEMENT
    SENDGENERICMSG,
    SETBITRATECONTROL,
    GETWFDPARAM,
    SUSPENDDISPLAY,
    SENDUIBCEVENT,
#endif /* MTK_AOSP_ENHANCEMENT */
};

class BpRemoteDisplay: public BpInterface<IRemoteDisplay>
{
public:
    BpRemoteDisplay(const sp<IBinder>& impl)
        : BpInterface<IRemoteDisplay>(impl)
    {
    }

    virtual status_t pause() {
        Parcel data, reply;
        data.writeInterfaceToken(IRemoteDisplay::getInterfaceDescriptor());
        remote()->transact(PAUSE, data, &reply);
        return reply.readInt32();
    }

    virtual status_t resume() {
        Parcel data, reply;
        data.writeInterfaceToken(IRemoteDisplay::getInterfaceDescriptor());
        remote()->transact(RESUME, data, &reply);
        return reply.readInt32();
    }

    status_t dispose()
    {
        Parcel data, reply;
        data.writeInterfaceToken(IRemoteDisplay::getInterfaceDescriptor());
        remote()->transact(DISPOSE, data, &reply);
        return reply.readInt32();
    }

    ///M: add for rtsp generic message@{
#ifdef MTK_AOSP_ENHANCEMENT
    status_t sendGenericMsg(int cmd)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IRemoteDisplay::getInterfaceDescriptor());
        data.writeInt32(cmd );

        remote()->transact(SENDGENERICMSG, data, &reply);
        return reply.readInt32();
    }
    status_t setBitrateControl(int level)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IRemoteDisplay::getInterfaceDescriptor());
        data.writeInt32(level);

        remote()->transact(SETBITRATECONTROL, data, &reply);
        return reply.readInt32();
    }
    int getWfdParam(int paramType)
    {
        Parcel data, reply;
        data.writeInterfaceToken(IRemoteDisplay::getInterfaceDescriptor());
        data.writeInt32(paramType);

        remote()->transact(GETWFDPARAM, data, &reply);
        return reply.readInt32();
    }


    status_t suspendDisplay(bool suspend, const sp<IGraphicBufferProducer> &bufferProducer){
#ifdef MTK_WFD_SINK_SUPPORT 
        Parcel data, reply;
        data.writeInterfaceToken(IRemoteDisplay::getInterfaceDescriptor());
        data.writeInt32((int)suspend);
        data.writeStrongBinder(bufferProducer->asBinder());
         
        remote()->transact(SUSPENDDISPLAY, data, &reply);
        return reply.readInt32();
#else
        return NO_INIT;
#endif
    }


    status_t sendUibcEvent(const String8& eventDesc) {
#ifdef MTK_WFD_SINK_UIBC_SUPPORT 
        Parcel data, reply;
        data.writeInterfaceToken(IRemoteDisplay::getInterfaceDescriptor());
        data.writeString8(eventDesc);

        remote()->transact(SENDUIBCEVENT, data, &reply);
        return reply.readInt32();
#else
        return NO_INIT;
#endif
    }
#endif /* MTK_AOSP_ENHANCEMENT */
    ///@}
};

IMPLEMENT_META_INTERFACE(RemoteDisplay, "android.media.IRemoteDisplay");

// ----------------------------------------------------------------------

status_t BnRemoteDisplay::onTransact(
    uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    switch (code) {
        case DISPOSE: {
            CHECK_INTERFACE(IRemoteDisplay, data, reply);
            reply->writeInt32(dispose());
            return NO_ERROR;
        }

        case PAUSE:
        {
            CHECK_INTERFACE(IRemoteDisplay, data, reply);
            reply->writeInt32(pause());
            return OK;
        }

        case RESUME:
        {
            CHECK_INTERFACE(IRemoteDisplay, data, reply);
            reply->writeInt32(resume());
            return OK;
        }

        ///M:add for rtsp generic message{@
#ifdef MTK_AOSP_ENHANCEMENT        
        case SENDGENERICMSG:{
            CHECK_INTERFACE(IRemoteDisplay, data, reply);
            uint32_t cmd = data.readInt32();
            reply->writeInt32(sendGenericMsg(cmd));
            return NO_ERROR;
        }
        case SETBITRATECONTROL:{
            CHECK_INTERFACE(IRemoteDisplay, data, reply);
            uint32_t level = data.readInt32();
            reply->writeInt32(setBitrateControl(level));
            return NO_ERROR;
        }
        case GETWFDPARAM:{
            CHECK_INTERFACE(IRemoteDisplay, data, reply);
            uint32_t paramType = data.readInt32();
            reply->writeInt32(getWfdParam(paramType));
            return NO_ERROR;
        }

#ifdef MTK_WFD_SINK_SUPPORT
        case SUSPENDDISPLAY:{
            CHECK_INTERFACE(IRemoteDisplay, data, reply);
            bool suspend = (bool)data.readInt32();
            sp<IGraphicBufferProducer> bufferProducer(
                    interface_cast<IGraphicBufferProducer>(data.readStrongBinder()));
            reply->writeInt32(suspendDisplay(suspend, bufferProducer));
            return NO_ERROR;
        }
#ifdef MTK_WFD_SINK_UIBC_SUPPORT
        case SENDUIBCEVENT:{
            CHECK_INTERFACE(IRemoteDisplay, data, reply);
            String8  eventDesc = data.readString8();
            reply->writeInt32(sendUibcEvent(eventDesc));
            return NO_ERROR;
        }
#endif
#endif        
#endif /* MTK_AOSP_ENHANCEMENT */      
        ///@}
        default:
            return BBinder::onTransact(code, data, reply, flags);
    }
}

}; // namespace android
