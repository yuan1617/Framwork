/* Copyright Statement:
 *
 * This software/firmware and related documentation ("MediaTek Software") are
 * protected under relevant copyright laws. The information contained herein
 * is confidential and proprietary to MediaTek Inc. and/or its licensors.
 * Without the prior written permission of MediaTek inc. and/or its licensors,
 * any reproduction, modification, use or disclosure of MediaTek Software,
 * and information contained herein, in whole or in part, shall be strictly prohibited.
 *
 * MediaTek Inc. (C) 2010. All rights reserved.
 *
 * BY OPENING THIS FILE, RECEIVER HEREBY UNEQUIVOCALLY ACKNOWLEDGES AND AGREES
 * THAT THE SOFTWARE/FIRMWARE AND ITS DOCUMENTATIONS ("MEDIATEK SOFTWARE")
 * RECEIVED FROM MEDIATEK AND/OR ITS REPRESENTATIVES ARE PROVIDED TO RECEIVER ON
 * AN "AS-IS" BASIS ONLY. MEDIATEK EXPRESSLY DISCLAIMS ANY AND ALL WARRANTIES,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE OR NONINFRINGEMENT.
 * NEITHER DOES MEDIATEK PROVIDE ANY WARRANTY WHATSOEVER WITH RESPECT TO THE
 * SOFTWARE OF ANY THIRD PARTY WHICH MAY BE USED BY, INCORPORATED IN, OR
 * SUPPLIED WITH THE MEDIATEK SOFTWARE, AND RECEIVER AGREES TO LOOK ONLY TO SUCH
 * THIRD PARTY FOR ANY WARRANTY CLAIM RELATING THERETO. RECEIVER EXPRESSLY ACKNOWLEDGES
 * THAT IT IS RECEIVER'S SOLE RESPONSIBILITY TO OBTAIN FROM ANY THIRD PARTY ALL PROPER LICENSES
 * CONTAINED IN MEDIATEK SOFTWARE. MEDIATEK SHALL ALSO NOT BE RESPONSIBLE FOR ANY MEDIATEK
 * SOFTWARE RELEASES MADE TO RECEIVER'S SPECIFICATION OR TO CONFORM TO A PARTICULAR
 * STANDARD OR OPEN FORUM. RECEIVER'S SOLE AND EXCLUSIVE REMEDY AND MEDIATEK'S ENTIRE AND
 * CUMULATIVE LIABILITY WITH RESPECT TO THE MEDIATEK SOFTWARE RELEASED HEREUNDER WILL BE,
 * AT MEDIATEK'S OPTION, TO REVISE OR REPLACE THE MEDIATEK SOFTWARE AT ISSUE,
 * OR REFUND ANY SOFTWARE LICENSE FEES OR SERVICE CHARGE PAID BY RECEIVER TO
 * MEDIATEK FOR SUCH MEDIATEK SOFTWARE AT ISSUE.
 *
 * The following software/firmware and/or related documentation ("MediaTek Software")
 * have been modified by MediaTek Inc. All revisions are subject to any receiver's
 * applicable license agreements with MediaTek Inc.
 */

/*
 * Copyright (C) 2010 The Android Open Source Project
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

#ifndef A_RTSP_CONNECTION_H_

#define A_RTSP_CONNECTION_H_

#include <media/stagefright/foundation/AHandler.h>
#include <media/stagefright/foundation/AString.h>
#ifdef MTK_AOSP_ENHANCEMENT 
#include <arpa/inet.h>
#endif // #ifdef MTK_AOSP_ENHANCEMENT

namespace android {

struct ABuffer;

struct ARTSPResponse : public RefBase {
    unsigned long mStatusCode;
    AString mStatusLine;
    KeyedVector<AString,AString> mHeaders;
    sp<ABuffer> mContent;
};

struct ARTSPConnection : public AHandler {
    ARTSPConnection(bool uidValid = false, uid_t uid = 0);

    void connect(const char *url, const sp<AMessage> &reply);
    void disconnect(const sp<AMessage> &reply);

    void sendRequest(const char *request, const sp<AMessage> &reply);

    void observeBinaryData(const sp<AMessage> &reply);

    static bool ParseURL(
            const char *url, AString *host, unsigned *port, AString *path,
            AString *user, AString *pass);

protected:
    virtual ~ARTSPConnection();
    virtual void onMessageReceived(const sp<AMessage> &msg);

private:
    enum State {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
    };

    enum {
        kWhatConnect            = 'conn',
        kWhatDisconnect         = 'disc',
        kWhatCompleteConnection = 'comc',
        kWhatSendRequest        = 'sreq',
        kWhatReceiveResponse    = 'rres',
        kWhatObserveBinaryData  = 'obin',
#ifdef MTK_AOSP_ENHANCEMENT 
        kWhatTimeout            = 'time',
        kWhatInjectPacket       = 'injt',
#endif // #ifdef MTK_AOSP_ENHANCEMENT
    };

    enum AuthType {
        NONE,
        BASIC,
        DIGEST
    };

    static const int64_t kSelectTimeoutUs;

#ifdef MTK_AOSP_ENHANCEMENT 
    static const int64_t kRequestTimeout;
#else
    static const AString sUserAgent;
#endif // #ifdef MTK_AOSP_ENHANCEMENT

    bool mUIDValid;
    uid_t mUID;
    State mState;
    AString mUser, mPass;
    AuthType mAuthType;
    AString mNonce;
    int mSocket;
    int32_t mConnectionID;
    int32_t mNextCSeq;
    bool mReceiveResponseEventPending;

    KeyedVector<int32_t, sp<AMessage> > mPendingRequests;

    sp<AMessage> mObserveBinaryMessage;

    void performDisconnect();

    void onConnect(const sp<AMessage> &msg);
    void onDisconnect(const sp<AMessage> &msg);
    void onCompleteConnection(const sp<AMessage> &msg);
    void onSendRequest(const sp<AMessage> &msg);
    void onReceiveResponse();

    void flushPendingRequests();
    void postReceiveReponseEvent();

    // Return false iff something went unrecoverably wrong.
    bool receiveRTSPReponse();
    status_t receive(void *data, size_t size);
    bool receiveLine(AString *line);
    sp<ABuffer> receiveBinaryData();
    bool notifyResponseListener(const sp<ARTSPResponse> &response);

    bool parseAuthMethod(const sp<ARTSPResponse> &response);
    void addAuthentication(AString *request);

    void addUserAgent(AString *request) const;

    status_t findPendingRequest(
            const sp<ARTSPResponse> &response, ssize_t *index) const;

    bool handleServerRequest(const sp<ARTSPResponse> &request);

    static bool ParseSingleUnsignedLong(
            const char *from, unsigned long *x);

    DISALLOW_EVIL_CONSTRUCTORS(ARTSPConnection);

#ifdef MTK_AOSP_ENHANCEMENT 
public:
    struct sockaddr_in getRemote() const { return mRemote; }
    // caller should check proxy arguments
    void setProxy(AString& host, int port) {
        mProxyHost = host;
        mProxyPort = port;
    }
    void injectPacket(const sp<ABuffer> &buffer);
    void stopTCPTrying();
	void exit();

private:
    AString mRealm;
    struct sockaddr_in mRemote;
    AString mProxyHost;
    int mProxyPort;
    bool mKeepTCPTrying;
    bool mForceQuitTCP;
    AString mUserAgent;
    bool mExited;

    
    void init();
    void setSocketFlag(const sp<AMessage> &msg);
    bool isConnTimeout(const sp<AMessage> &msg);
    void postTimeoutMsg(sp<AMessage> msg, int32_t cseq);
    void getRealm(AString value);

    
    static void MakeUserAgent(AString *userAgent);
    void onTimeout(const sp<AMessage> &msg);
    void onInjectPacket(const sp<AMessage>& msg);
#endif // #ifdef MTK_AOSP_ENHANCEMENT

};

}  // namespace android

#endif  // A_RTSP_CONNECTION_H_
