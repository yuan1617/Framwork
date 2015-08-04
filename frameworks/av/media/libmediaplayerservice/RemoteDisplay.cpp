/*
 * Copyright 2012, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "RemoteDisplay.h"

#include <utils/String8.h>

#include "source/WifiDisplaySource.h"

#include <media/IRemoteDisplayClient.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/foundation/ANetworkSession.h>

namespace android {

RemoteDisplay::RemoteDisplay(
        const sp<IRemoteDisplayClient> &client,
        const char *iface)
    : mLooper(new ALooper),
      mNetSession(new ANetworkSession) {
    mLooper->setName("wfd_looper");

    mSource = new WifiDisplaySource(mNetSession, client);
    mLooper->registerHandler(mSource);

    mNetSession->start();
#ifdef MTK_AOSP_ENHANCEMENT	
    //set playbacksession thread high priority.
    mLooper->start(
            false /* runOnCallingThread */,
            false /* canCallJava */,
            PRIORITY_AUDIO);
#else
    mLooper->start();
#endif

    mSource->start(iface);

///M: @{
    
#if defined(MTK_AOSP_ENHANCEMENT) && defined(MTK_WFD_SINK_SUPPORT)
    mSinkMode = false;
    mDisposed = false;
#endif
///@}

}

#ifdef MTK_AOSP_ENHANCEMENT
RemoteDisplay::RemoteDisplay(
        const sp<IRemoteDisplayClient> &client, const char *iface, const uint32_t wfdFlags)
    : mLooper(new ALooper),
      mNetSession(new ANetworkSession) {
    mLooper->setName("wfd_looper");

    ///M: @{
#ifdef MTK_WFD_SINK_SUPPORT
    mSinkMode = false;
    mDisposed = false;
#endif
    ///@}

    mSource = new WifiDisplaySource(mNetSession, client, wfdFlags, NULL);
    mLooper->registerHandler(mSource);

    mNetSession->start();
    mLooper->start();

    mSource->start(iface);
}


#ifdef MTK_WFD_SINK_SUPPORT

RemoteDisplay::RemoteDisplay(
        const sp<IRemoteDisplayClient> &client, const char *iface, const sp<IGraphicBufferProducer> &bufferProducer)
    : mLooper(new ALooper),
      mNetSession(new ANetworkSession) {
    mLooper->setName("wfd_looper");

   
    mSinkMode = true;
    mDisposed = false;

    //TODO: remove log
    if (bufferProducer != NULL) {
        ALOGI("RemoteDisplay: bufferProducer is ok");
    } else {
        ALOGI("RemoteDisplay: bufferProducer is null");
    }
    if (client != NULL) {
        ALOGI("RemoteDisplay: client is ok");
    } else {
        ALOGI("RemoteDisplay: client is null");
    }
    
    mSink = new WifiDisplaySink(
        client != NULL ? 0 : WifiDisplaySink::FLAG_SIGMA_TEST_MODE, 
        mNetSession, client, bufferProducer, NULL);
	if(client == NULL){
		mNetSession->setNetworkSessionTestMode();
	}
      
    mLooper->registerHandler(mSink);

    mNetSession->start();

    //set playbacksession thread high priority.
    mLooper->start(
            false /* runOnCallingThread */,
            false /* canCallJava */,
            PRIORITY_AUDIO);

    int32_t port;
    AString string(iface, strlen(iface));
    //string.setTo(iface, strlen(iface));
    
    ssize_t colonPos = string.find(":");

    if (colonPos >= 0) {
        const char *s = string.c_str() + colonPos + 1;

        char *end;
        port = strtoul(s, &end, 10);

        if (end == s || *end != '\0' || port > 65535) {
            ALOGI("parseIPPort error!");
        } else {
            string.erase(colonPos, string.size() - colonPos);
        }
    } else {
        port = defaultPort;
    }

    ALOGI("Connect to IP-port==>%s:%d", string.c_str(), (int) port);
           
    
    mSink->start(string.c_str(), port);   
}
#endif

///M: add for rtsp generic message{@
status_t RemoteDisplay::sendGenericMsg(int cmd) {
    mSource->sendGenericMsg(cmd);

    return OK;
}

status_t RemoteDisplay::setBitrateControl(int level) {
    mSource->setWfdLevel(level);

    return OK;
}

int RemoteDisplay::getWfdParam(int paramType) {

#if defined(MTK_AOSP_ENHANCEMENT) && defined(MTK_WFD_SINK_SUPPORT)
    if (mSinkMode) {
        return mSink->getWfdParam(paramType);
    }
    else        
#endif
    {
        return mSource->getWfdParam(paramType);
    }
}


status_t RemoteDisplay::suspendDisplay(bool suspend, const sp<IGraphicBufferProducer> &bufferProducer){
#ifdef MTK_WFD_SINK_SUPPORT   

    if (suspend) {
        mSink->mtkSinkPause();
    } else {
        mSink->mtkSinkResume(bufferProducer);
    }
#endif
    return OK;
}

status_t RemoteDisplay::sendUibcEvent(const String8& eventDesc) {
#ifdef MTK_WFD_SINK_UIBC_SUPPORT
    const char* pEventDes = eventDesc.string();
    int type = *pEventDes;

    //ALOGD("sendUibcEvent: eventDesc=%s", pEventDes);

    if (*(pEventDes+1) != ',')
        return BAD_VALUE;

    ALOGD("sendUibcEvent: type=0x%X", type);

    switch (type) {
    case 0x30:
    case 0x31:
    case 0x32:
        mSink->sendUIBCGenericTouchEvent(pEventDes);
    break;
    case 0x33:
    case 0x34:
        mSink->sendUIBCGenericKeyEvent(pEventDes);
    break;
    default:
        return BAD_VALUE;
    }
#endif
    return OK;
}
///@}
#endif

RemoteDisplay::~RemoteDisplay() {
}

status_t RemoteDisplay::pause() {
    return mSource->pause();
}

status_t RemoteDisplay::resume() {
    return mSource->resume();
}

status_t RemoteDisplay::dispose() {
#if defined(MTK_AOSP_ENHANCEMENT) && defined(MTK_WFD_SINK_SUPPORT)
    if (mSinkMode) {
        if (!mDisposed) {

            ALOGD("+++ dispose");
            
            mSink->stop();
            mSink.clear();

            mDisposed = true;
        }
    }
    else        
#endif
    {
        mSource->stop();
        mSource.clear();
    }
    ALOGD("Looper stop");
    mLooper->stop();
    ALOGD("NetSession stop");
    mNetSession->stop();

    ALOGD("--- dispose");
    return OK;
}

}  // namespace android
