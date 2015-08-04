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

#ifndef MY_HANDLER_H_

#define MY_HANDLER_H_

//#define LOG_NDEBUG 0
#ifndef LOG_TAG
#define LOG_TAG "MyHandler"
#endif

#include <utils/Log.h>

#include "APacketSource.h"
#include "ARTPConnection.h"
#include "ARTSPConnection.h"
#include "ASessionDescription.h"

#include <ctype.h>

#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/ALooper.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/Utils.h>

#include <arpa/inet.h>
#include <sys/socket.h>
#include <netdb.h>

#include "HTTPBase.h"

#ifdef MTK_AOSP_ENHANCEMENT 
#include <cutils/properties.h>

#ifdef MTK_RTSP_USE_XLOG
#include <cutils/xlog.h>
#undef ALOGE
#undef ALOGW
#undef ALOGI
#undef ALOGD
#undef ALOGV
#define ALOGE XLOGE
#define ALOGW XLOGW
#define ALOGI XLOGI
#define ALOGD XLOGD
#define ALOGV XLOGV
#endif
#if LOG_NDEBUG
#define UNUSED_UNLESS_VERBOSE(x) (void)(x)
#else
#define UNUSED_UNLESS_VERBOSE(x)
#endif

#include <media/stagefright/MetaData.h>
#include "ARTPSource.h"
#include "AnotherPacketSource.h" //for bitrate-adaptation
//send 3 times for pokeAHole
static int kPokeAttempts = 3;
// timeout = (trials + 1) * 3s
static int kTimeoutTrials = 5;
// live streaming 60s
static int kTimeoutLiveTrials = 19;
static int64_t kMaxSRNTPDiff = 30 * 1E6; //ll << 32;
static int64_t kSRTimeoutUs = 4000000ll;
static int64_t kTearDownTimeoutUs = 3000000ll;
static int kMaxInterleave = 60; //60s Now we just support poor interleave to 60s,avoid memory malloc fail.
#define LOG_TAG "MyHandler"
#endif // #ifdef MTK_AOSP_ENHANCEMENT

// If no access units are received within 5 secs, assume that the rtp
// stream has ended and signal end of stream.
static int64_t kAccessUnitTimeoutUs = 10000000ll;

// If no access units arrive for the first 10 secs after starting the
// stream, assume none ever will and signal EOS or switch transports.
static int64_t kStartupTimeoutUs = 10000000ll;

static int64_t kDefaultKeepAliveTimeoutUs = 60000000ll;

static int64_t kPauseDelayUs = 3000000ll;

namespace android {

static bool GetAttribute(const char *s, const char *key, AString *value) {
    value->clear();

    size_t keyLen = strlen(key);

    for (;;) {
        while (isspace(*s)) {
            ++s;
        }

        const char *colonPos = strchr(s, ';');

        size_t len =
            (colonPos == NULL) ? strlen(s) : colonPos - s;

        if (len >= keyLen + 1 && s[keyLen] == '=' && !strncmp(s, key, keyLen)) {
            value->setTo(&s[keyLen + 1], len - keyLen - 1);
            return true;
        }

        if (colonPos == NULL) {
            return false;
        }

        s = colonPos + 1;
    }
}

struct MyHandler : public AHandler {
    enum {
        kWhatConnected                  = 'conn',
        kWhatDisconnected               = 'disc',
        kWhatSeekDone                   = 'sdon',

        kWhatAccessUnit                 = 'accU',
        kWhatEOS                        = 'eos!',
        kWhatSeekDiscontinuity          = 'seeD',
        kWhatNormalPlayTimeMapping      = 'nptM',
#ifdef MTK_AOSP_ENHANCEMENT
        kWhatPauseDone                  = 'edon',
        kWhatPlayDone                   = 'ydon',
        kWhatPreSeekDone                = 'kdon',
#endif
    };

    MyHandler(
            const char *url,
            const sp<AMessage> &notify,
            bool uidValid = false, uid_t uid = 0)
        : mNotify(notify),
          mUIDValid(uidValid),
          mUID(uid),
          mNetLooper(new ALooper),
          mConn(new ARTSPConnection(mUIDValid, mUID)),
          mRTPConn(new ARTPConnection),
          mOriginalSessionURL(url),
          mSessionURL(url),
          mSetupTracksSuccessful(false),
          mSeekPending(false),
          mFirstAccessUnit(true),
          mAllTracksHaveTime(false),
          mNTPAnchorUs(-1),
          mMediaAnchorUs(-1),
          mLastMediaTimeUs(0),
          mNumAccessUnitsReceived(0),
          mCheckPending(false),
          mCheckGeneration(0),
          mCheckTimeoutGeneration(0),
          mTryTCPInterleaving(false),
          mTryFakeRTCP(false),
          mReceivedFirstRTCPPacket(false),
          mReceivedFirstRTPPacket(false),
          mSeekable(true),
          mKeepAliveTimeoutUs(kDefaultKeepAliveTimeoutUs),
          mKeepAliveGeneration(0),
          mPausing(false),
          mPauseGeneration(0),
          mPlayResponseParsed(false) {
        mNetLooper->setName("rtsp net");
        mNetLooper->start(false /* runOnCallingThread */,
                          false /* canCallJava */,
                          PRIORITY_HIGHEST);

        // Strip any authentication info from the session url, we don't
        // want to transmit user/pass in cleartext.
        AString host, path, user, pass;
        unsigned port;
#ifdef MTK_AOSP_ENHANCEMENT
        init();
        if (!(ARTSPConnection::ParseURL(
                    mSessionURL.c_str(), &host, &port, &path, &user, &pass))) {
            ALOGE("invalid url %s", mSessionURL.c_str());
            return;
        }
#else
        CHECK(ARTSPConnection::ParseURL(
                    mSessionURL.c_str(), &host, &port, &path, &user, &pass));
#endif // #ifdef MTK_AOSP_ENHANCEMENT

        if (user.size() > 0) {
            mSessionURL.clear();
            mSessionURL.append("rtsp://");
            mSessionURL.append(host);
            mSessionURL.append(":");
            mSessionURL.append(StringPrintf("%u", port));
            mSessionURL.append(path);

            ALOGI("rewritten session url: '%s'", mSessionURL.c_str());
        }

        mSessionHost = host;
    }

    void connect() {
#ifdef MTK_AOSP_ENHANCEMENT 
        mRegistered = true;
        mRTSPNetLooper->registerHandler(mConn);
#else
        looper()->registerHandler(mConn);
#endif // #ifdef MTK_AOSP_ENHANCEMENT
        (1 ? mNetLooper : looper())->registerHandler(mRTPConn);

        sp<AMessage> notify = new AMessage('biny', id());
        mConn->observeBinaryData(notify);

        sp<AMessage> reply = new AMessage('conn', id());
        mConn->connect(mOriginalSessionURL.c_str(), reply);
    }

    void loadSDP(const sp<ASessionDescription>& desc) {
        looper()->registerHandler(mConn);
        (1 ? mNetLooper : looper())->registerHandler(mRTPConn);

        sp<AMessage> notify = new AMessage('biny', id());
        mConn->observeBinaryData(notify);

        sp<AMessage> reply = new AMessage('sdpl', id());
        reply->setObject("description", desc);
        mConn->connect(mOriginalSessionURL.c_str(), reply);
    }

    AString getControlURL() {
        AString sessionLevelControlURL;
        if (mSessionDesc->findAttribute(
                0,
                "a=control",
                &sessionLevelControlURL)) {
            if (sessionLevelControlURL.compare("*") == 0) {
                return mBaseURL;
            } else {
                AString controlURL;
                CHECK(MakeURL(
                        mBaseURL.c_str(),
                        sessionLevelControlURL.c_str(),
                        &controlURL));
                return controlURL;
            }
        } else {
            return mSessionURL;
        }
    }

    void disconnect() {
#ifdef MTK_AOSP_ENHANCEMENT
        stopTCPTrying();
		exit();
#endif
        (new AMessage('abor', id()))->post();
    }

    void seek(int64_t timeUs) {
        sp<AMessage> msg = new AMessage('seek', id());
        msg->setInt64("time", timeUs);
        mPauseGeneration++;
        msg->post();
    }

    bool isSeekable() const {
        return mSeekable;
    }

    void pause() {
#ifdef MTK_AOSP_ENHANCEMENT
        if(mIntSeverError){
            ALOGE("[rtsp]Internal server error, pause return immediately");
            notifyMsg(kWhatPauseDone, OK);
            return;
        }
#endif
        sp<AMessage> msg = new AMessage('paus', id());
        mPauseGeneration++;
        msg->setInt32("pausecheck", mPauseGeneration);
#ifdef MTK_AOSP_ENHANCEMENT
        ALOGD("[rtsp]post pause with generation %d", mPauseGeneration);
        msg->post();    // mtk80902: why delay pause???
#else
        msg->post(kPauseDelayUs);
#endif
    }

    void resume() {
#ifdef MTK_AOSP_ENHANCEMENT
        if(mIntSeverError){
            ALOGE("[rtsp]Internal server error, play return immediately");
            notifyMsg(kWhatPlayDone, OK);
            return;
        }
#endif
        sp<AMessage> msg = new AMessage('resu', id());
        mPauseGeneration++;
        msg->post();
    }

    static void addRR(const sp<ABuffer> &buf) {
        uint8_t *ptr = buf->data() + buf->size();
        ptr[0] = 0x80 | 0;
        ptr[1] = 201;  // RR
        ptr[2] = 0;
        ptr[3] = 1;
        ptr[4] = 0xde;  // SSRC
        ptr[5] = 0xad;
        ptr[6] = 0xbe;
        ptr[7] = 0xef;

        buf->setRange(0, buf->size() + 8);
    }

    static void addSDES(int s, const sp<ABuffer> &buffer) {
        struct sockaddr_in addr;
        socklen_t addrSize = sizeof(addr);
        if (getsockname(s, (sockaddr *)&addr, &addrSize) != 0) {
            inet_aton("0.0.0.0", &(addr.sin_addr));
        }

        uint8_t *data = buffer->data() + buffer->size();
        data[0] = 0x80 | 1;
        data[1] = 202;  // SDES
        data[4] = 0xde;  // SSRC
        data[5] = 0xad;
        data[6] = 0xbe;
        data[7] = 0xef;

        size_t offset = 8;

        data[offset++] = 1;  // CNAME

        AString cname = "stagefright@";
        cname.append(inet_ntoa(addr.sin_addr));
        data[offset++] = cname.size();

        memcpy(&data[offset], cname.c_str(), cname.size());
        offset += cname.size();

        data[offset++] = 6;  // TOOL

        AString tool = MakeUserAgent();

        data[offset++] = tool.size();

        memcpy(&data[offset], tool.c_str(), tool.size());
        offset += tool.size();

        data[offset++] = 0;

        if ((offset % 4) > 0) {
            size_t count = 4 - (offset % 4);
            switch (count) {
                case 3:
                    data[offset++] = 0;
                case 2:
                    data[offset++] = 0;
                case 1:
                    data[offset++] = 0;
            }
        }

        size_t numWords = (offset / 4) - 1;
        data[2] = numWords >> 8;
        data[3] = numWords & 0xff;

        buffer->setRange(buffer->offset(), buffer->size() + offset);
    }

    // In case we're behind NAT, fire off two UDP packets to the remote
    // rtp/rtcp ports to poke a hole into the firewall for future incoming
    // packets. We're going to send an RR/SDES RTCP packet to both of them.
    bool pokeAHole(int rtpSocket, int rtcpSocket, const AString &transport) {
        struct sockaddr_in addr;
        memset(addr.sin_zero, 0, sizeof(addr.sin_zero));
        addr.sin_family = AF_INET;

        AString source;
        AString server_port;
        if (!GetAttribute(transport.c_str(),
                          "source",
                          &source)) {
            ALOGW("Missing 'source' field in Transport response. Using "
                 "RTSP endpoint address.");

            struct hostent *ent = gethostbyname(mSessionHost.c_str());
            if (ent == NULL) {
                ALOGE("Failed to look up address of session host '%s'",
                     mSessionHost.c_str());

                return false;
            }

            addr.sin_addr.s_addr = *(in_addr_t *)ent->h_addr;
        } else {
            addr.sin_addr.s_addr = inet_addr(source.c_str());
        }

        if (!GetAttribute(transport.c_str(),
                                 "server_port",
                                 &server_port)) {
            ALOGI("Missing 'server_port' field in Transport response.");
            return false;
        }

        int rtpPort, rtcpPort;
        if (sscanf(server_port.c_str(), "%d-%d", &rtpPort, &rtcpPort) != 2
                || rtpPort <= 0 || rtpPort > 65535
                || rtcpPort <=0 || rtcpPort > 65535
                || rtcpPort != rtpPort + 1) {
            ALOGE("Server picked invalid RTP/RTCP port pair %s,"
                 " RTP port must be even, RTCP port must be one higher.",
                 server_port.c_str());

            return false;
        }

        if (rtpPort & 1) {
            ALOGW("Server picked an odd RTP port, it should've picked an "
                 "even one, we'll let it pass for now, but this may break "
                 "in the future.");
        }

        if (addr.sin_addr.s_addr == INADDR_NONE) {
            return true;
        }

        if (IN_LOOPBACK(ntohl(addr.sin_addr.s_addr))) {
            // No firewalls to traverse on the loopback interface.
            return true;
        }

        // Make up an RR/SDES RTCP packet.
        sp<ABuffer> buf = new ABuffer(65536);
        buf->setRange(0, 0);
        addRR(buf);
        addSDES(rtpSocket, buf);

        addr.sin_port = htons(rtpPort);

        ssize_t n = sendto(
                rtpSocket, buf->data(), buf->size(), 0,
                (const sockaddr *)&addr, sizeof(addr));

        if (n < (ssize_t)buf->size()) {
            ALOGE("failed to poke a hole for RTP packets");
            return false;
        }

        addr.sin_port = htons(rtcpPort);

        n = sendto(
                rtcpSocket, buf->data(), buf->size(), 0,
                (const sockaddr *)&addr, sizeof(addr));

        if (n < (ssize_t)buf->size()) {
            ALOGE("failed to poke a hole for RTCP packets");
            return false;
        }

        ALOGV("successfully poked holes.");

        return true;
    }

    static bool isLiveStream(const sp<ASessionDescription> &desc) {
        AString attrLiveStream;
        if (desc->findAttribute(0, "a=LiveStream", &attrLiveStream)) {
            ssize_t semicolonPos = attrLiveStream.find(";", 2);

            const char* liveStreamValue;
            if (semicolonPos < 0) {
                liveStreamValue = attrLiveStream.c_str();
            } else {
                AString valString;
                valString.setTo(attrLiveStream,
                        semicolonPos + 1,
                        attrLiveStream.size() - semicolonPos - 1);
                liveStreamValue = valString.c_str();
            }

            uint32_t value = strtoul(liveStreamValue, NULL, 10);
            if (value == 1) {
                ALOGV("found live stream");
                return true;
            }
        } else {
            // It is a live stream if no duration is returned
            int64_t durationUs;
            if (!desc->getDurationUs(&durationUs)) {
                ALOGV("No duration found, assume live stream");
                return true;
            }
        }

        return false;
    }

    virtual void onMessageReceived(const sp<AMessage> &msg) {
        switch (msg->what()) {
            case 'conn':
            {
                int32_t result;
                CHECK(msg->findInt32("result", &result));

                ALOGI("connection request completed with result %d (%s)",
                     result, strerror(-result));

                if (result == OK) {
#ifdef MTK_AOSP_ENHANCEMENT 
                    if (mSkipDescribe) {
                        setupTrack(1);
                        break;
                    }
#endif // #ifdef MTK_AOSP_ENHANCEMENT
                    AString request;
                    request = "DESCRIBE ";
                    request.append(mSessionURL);
                    request.append(" RTSP/1.0\r\n");
                    request.append("Accept: application/sdp\r\n");
                    request.append("\r\n");

                    sp<AMessage> reply = new AMessage('desc', id());
                    mConn->sendRequest(request.c_str(), reply);
                } else {
#ifdef MTK_AOSP_ENHANCEMENT 
                    mFinalStatus = MappingRTSPError(result);
#endif // #ifdef MTK_AOSP_ENHANCEMENT
                    (new AMessage('disc', id()))->post();
                }
                break;
            }

            case 'disc':
            {
                ++mKeepAliveGeneration;

                int32_t reconnect;
                if (msg->findInt32("reconnect", &reconnect) && reconnect) {
#ifdef MTK_AOSP_ENHANCEMENT 
                    mFinalStatus = OK;
#endif // #ifdef MTK_AOSP_ENHANCEMENT
                    sp<AMessage> reply = new AMessage('conn', id());
                    mConn->connect(mOriginalSessionURL.c_str(), reply);
                } else {
#ifdef MTK_AOSP_ENHANCEMENT 
                    ALOGI("send eos to all tracks in disc");
                    for (size_t i = 0; i < mTracks.size(); ++i) {
                        postQueueEOS(i, ERROR_END_OF_STREAM);
                    }
#endif // #ifdef MTK_AOSP_ENHANCEMENT
                    (new AMessage('quit', id()))->post();
                }
                break;
            }

            case 'desc':
            {
                int32_t result;
#ifdef MTK_AOSP_ENHANCEMENT 
				if(!msg->findInt32("result", &result)) {
					ALOGE("no result in desc, set result ERROR_IO");
					result = ERROR_IO;
				}
#else
                CHECK(msg->findInt32("result", &result));
#endif // #ifdef MTK_AOSP_ENHANCEMENT
                ALOGI("DESCRIBE completed with result %d (%s)",
                     result, strerror(-result));

                if (result == OK) {
                    sp<RefBase> obj;
                    CHECK(msg->findObject("response", &obj));
                    sp<ARTSPResponse> response =
                        static_cast<ARTSPResponse *>(obj.get());

                    if (response->mStatusCode == 301 || response->mStatusCode == 302) {
                        ssize_t i = response->mHeaders.indexOfKey("location");
                        CHECK_GE(i, 0);

                        mOriginalSessionURL = response->mHeaders.valueAt(i);
                        mSessionURL = mOriginalSessionURL;

                        // Strip any authentication info from the session url, we don't
                        // want to transmit user/pass in cleartext.
                        AString host, path, user, pass;
                        unsigned port;
                        if (ARTSPConnection::ParseURL(
                                    mSessionURL.c_str(), &host, &port, &path, &user, &pass)
                                && user.size() > 0) {
                            mSessionURL.clear();
                            mSessionURL.append("rtsp://");
                            mSessionURL.append(host);
                            mSessionURL.append(":");
                            mSessionURL.append(StringPrintf("%u", port));
                            mSessionURL.append(path);

                            ALOGI("rewritten session url: '%s'", mSessionURL.c_str());
                        }

                        sp<AMessage> reply = new AMessage('conn', id());
                        mConn->connect(mOriginalSessionURL.c_str(), reply);
                        break;
                    }

                    if (response->mStatusCode != 200) {
#ifdef MTK_AOSP_ENHANCEMENT 
                        result = response->mStatusCode;
#else
                        result = UNKNOWN_ERROR;
#endif // #ifdef MTK_AOSP_ENHANCEMENT
                    } else if (response->mContent == NULL) {
                        result = ERROR_MALFORMED;
                        ALOGE("The response has no content.");
                    } else {
#ifdef MTK_AOSP_ENHANCEMENT
                        checkServer(response);
#endif
                        mSessionDesc = new ASessionDescription;

                        mSessionDesc->setTo(
                                response->mContent->data(),
                                response->mContent->size());

                        if (!mSessionDesc->isValid()) {
                            ALOGE("Failed to parse session description.");
                            result = ERROR_MALFORMED;
                        } else {
                            ssize_t i = response->mHeaders.indexOfKey("content-base");
                            if (i >= 0) {
                                mBaseURL = response->mHeaders.valueAt(i);
                            } else {
                                i = response->mHeaders.indexOfKey("content-location");
                                if (i >= 0) {
                                    mBaseURL = response->mHeaders.valueAt(i);
                                } else {
                                    mBaseURL = mSessionURL;
                                }
                            }

                            mSeekable = !isLiveStream(mSessionDesc);

                            if (!mBaseURL.startsWith("rtsp://")) {
                                // Some misbehaving servers specify a relative
                                // URL in one of the locations above, combine
                                // it with the absolute session URL to get
                                // something usable...

                                ALOGW("Server specified a non-absolute base URL"
                                     ", combining it with the session URL to "
                                     "get something usable...");

                                AString tmp;
                                CHECK(MakeURL(
                                            mSessionURL.c_str(),
                                            mBaseURL.c_str(),
                                            &tmp));

                                mBaseURL = tmp;
                            }

                            mControlURL = getControlURL();

#ifdef MTK_AOSP_ENHANCEMENT
                            ALOGI("base url %s", mBaseURL.c_str());
#endif
                            if (mSessionDesc->countTracks() < 2) {
                                // There's no actual tracks in this session.
                                // The first "track" is merely session meta
                                // data.

                                ALOGW("Session doesn't contain any playable "
                                     "tracks. Aborting.");
                                result = ERROR_UNSUPPORTED;
                            } else {
                                setupTrack(1);
                            }
                        }
                    }
                }

                if (result != OK) {
#ifdef MTK_AOSP_ENHANCEMENT 
                    mFinalStatus = MappingRTSPError(result);
#endif // #ifdef MTK_AOSP_ENHANCEMENT
                    sp<AMessage> reply = new AMessage('disc', id());
                    mConn->disconnect(reply);
                }
                break;
            }

            case 'sdpl':
            {
                int32_t result;
                CHECK(msg->findInt32("result", &result));

                ALOGI("SDP connection request completed with result %d (%s)",
                     result, strerror(-result));

                if (result == OK) {
                    sp<RefBase> obj;
                    CHECK(msg->findObject("description", &obj));
                    mSessionDesc =
                        static_cast<ASessionDescription *>(obj.get());

                    if (!mSessionDesc->isValid()) {
                        ALOGE("Failed to parse session description.");
                        result = ERROR_MALFORMED;
                    } else {
                        mBaseURL = mSessionURL;

                        mSeekable = !isLiveStream(mSessionDesc);

                        mControlURL = getControlURL();

                        if (mSessionDesc->countTracks() < 2) {
                            // There's no actual tracks in this session.
                            // The first "track" is merely session meta
                            // data.

                            ALOGW("Session doesn't contain any playable "
                                 "tracks. Aborting.");
                            result = ERROR_UNSUPPORTED;
                        } else {
                            setupTrack(1);
                        }
                    }
                }

                if (result != OK) {
#ifdef MTK_AOSP_ENHANCEMENT 
                    mFinalStatus = MappingRTSPError(result);
#endif
                    sp<AMessage> reply = new AMessage('disc', id());
                    mConn->disconnect(reply);
                }
                break;
            }

            case 'setu':
            {
                size_t index;
                CHECK(msg->findSize("index", &index));

                TrackInfo *track = NULL;
                size_t trackIndex;
                if (msg->findSize("track-index", &trackIndex)) {
#ifdef MTK_AOSP_ENHANCEMENT 
                    ALOGI("'setu',trackIndex=%d",trackIndex);
                    if (mTracks.size() == 0) {
                        ALOGW("SETUP %d done after abor", trackIndex);
                        break;
                    }
#endif // #ifdef MTK_AOSP_ENHANCEMENT
                    track = &mTracks.editItemAt(trackIndex);
                }

                int32_t result;
                CHECK(msg->findInt32("result", &result));

                ALOGI("SETUP(%d) completed with result %d (%s)",
                     index, result, strerror(-result));

                if (result == OK) {
                    CHECK(track != NULL);

                    sp<RefBase> obj;
                    CHECK(msg->findObject("response", &obj));
                    sp<ARTSPResponse> response =
                        static_cast<ARTSPResponse *>(obj.get());

                    if (response->mStatusCode != 200) {
#ifdef MTK_AOSP_ENHANCEMENT 
                        result = response->mStatusCode;
#else
                        result = UNKNOWN_ERROR;
#endif // #ifdef MTK_AOSP_ENHANCEMENT
                    } else {
                        ssize_t i = response->mHeaders.indexOfKey("session");
                        CHECK_GE(i, 0);

                        mSessionID = response->mHeaders.valueAt(i);

                        mKeepAliveTimeoutUs = kDefaultKeepAliveTimeoutUs;
                        AString timeoutStr;
                        if (GetAttribute(
                                    mSessionID.c_str(), "timeout", &timeoutStr)) {
                            char *end;
                            unsigned long timeoutSecs =
                                strtoul(timeoutStr.c_str(), &end, 10);

                            if (end == timeoutStr.c_str() || *end != '\0') {
                                ALOGW("server specified malformed timeout '%s'",
                                     timeoutStr.c_str());

                                mKeepAliveTimeoutUs = kDefaultKeepAliveTimeoutUs;
                            } else if (timeoutSecs < 15) {
                                ALOGW("server specified too short a timeout "
                                     "(%lu secs), using default.",
                                     timeoutSecs);

                                mKeepAliveTimeoutUs = kDefaultKeepAliveTimeoutUs;
                            } else {
                                mKeepAliveTimeoutUs = timeoutSecs * 1000000ll;

                                ALOGI("server specified timeout of %lu secs.",
                                     timeoutSecs);
                            }
                        }

                        i = mSessionID.find(";");
                        if (i >= 0) {
                            // Remove options, i.e. ";timeout=90"
                            mSessionID.erase(i, mSessionID.size() - i);
                        }

#ifdef MTK_AOSP_ENHANCEMENT
                        if (mServerInfo.empty()) {
                            checkServer(response);
                        }
#endif
                        sp<AMessage> notify = new AMessage('accu', id());
                        notify->setSize("track-index", trackIndex);
						ALOGI("'setu',create 'accu' notify with trackIndex=%d for ARTPCon",trackIndex);

                        i = response->mHeaders.indexOfKey("transport");
#ifdef MTK_AOSP_ENHANCEMENT
						if (i < 0) {
							// server maybe give wrong header "trensport"
							ALOGW("get header 'transport' failed, i(%d) < 0, use 'trensport' to get again", i);
							i = response->mHeaders.indexOfKey("trensport");
						}
#endif
                        CHECK_GE(i, 0);

                        if (track->mRTPSocket != -1 && track->mRTCPSocket != -1) {
                            AString transport = response->mHeaders.valueAt(i);
                            
#ifdef MTK_AOSP_ENHANCEMENT
                            track->mTransport = transport;
#endif // #ifdef MTK_AOSP_ENHANCEMENT

                            //UDP need to poke a hole, TCP need not --add by Jiapeng Yin
                            if (!track->mUsingInterleavedTCP) {
                            // We are going to continue even if we were
                            // unable to poke a hole into the firewall...
#ifdef MTK_AOSP_ENHANCEMENT 
                            pokeAHole(track->mRTPSocket,
                                      track->mRTCPSocket,
                                      transport,
                                      track->mRTPPort);
#else
                            pokeAHole(
                                    track->mRTPSocket,
                                    track->mRTCPSocket,
                                    transport);
#endif // #ifdef MTK_AOSP_ENHANCEMENT
                        }
#ifdef MTK_AOSP_ENHANCEMENT
						ARTPConnectionParam connParam;
            connParam.mSSRC = getSSRC(transport);
						connParam.mAPacketSource = track->mPacketSource;
						connParam.mNaduFreq = track->mNADUFreq;
#endif

                            mRTPConn->addStream(
                                    track->mRTPSocket, track->mRTCPSocket,
                                    mSessionDesc, index,
#ifdef MTK_AOSP_ENHANCEMENT 
								notify, track->mUsingInterleavedTCP, &connParam);
#else
                                notify, track->mUsingInterleavedTCP);
#endif // #ifdef MTK_AOSP_ENHANCEMENT

                            mSetupTracksSuccessful = true;
                        } else {
                            result = BAD_VALUE;
                        }
                    }
                }

                if (result != OK) {
                    if (track) {
                        if (!track->mUsingInterleavedTCP) {
                            // Clear the tag
                            if (mUIDValid) {
                                HTTPBase::UnRegisterSocketUserTag(track->mRTPSocket);
                                HTTPBase::UnRegisterSocketUserMark(track->mRTPSocket);
                                HTTPBase::UnRegisterSocketUserTag(track->mRTCPSocket);
                                HTTPBase::UnRegisterSocketUserMark(track->mRTCPSocket);
                            }

                            close(track->mRTPSocket);
                            close(track->mRTCPSocket);
                        }

#ifdef MTK_AOSP_ENHANCEMENT 
                        postQueueEOS(trackIndex, ERROR_END_OF_STREAM);
#else
                        mTracks.removeItemsAt(trackIndex);
#endif
                    }
#ifdef MTK_AOSP_ENHANCEMENT 
                    if (result != ERROR_UNSUPPORTED) {
                        postEOSDisconn(result);
                        break;
                    } else {
                        setUnsupportVideo(msg);
                    }
#endif // #ifdef MTK_AOSP_ENHANCEMENT
                }

                ++index;
                if (result == OK && index < mSessionDesc->countTracks()) {
                    setupTrack(index);
                } else if (mSetupTracksSuccessful) {
                    ++mKeepAliveGeneration;
                    postKeepAlive();

#ifdef MTK_AOSP_ENHANCEMENT 
                    if (mSwitchingTCP) {
                        switchingTCP();
                        break;
                    }
                    // finish ARTSPConnection::connect here
                    // instread of in 'accu'
					ALOGI("MyHandler,complete connect,notify kWhatConnected");
                    notifyMsg(kWhatConnected, OK);
#else
                    AString request = "PLAY ";
                    request.append(mControlURL);
                    request.append(" RTSP/1.0\r\n");

                    request.append("Session: ");
                    request.append(mSessionID);
                    request.append("\r\n");

                    request.append("\r\n");

                    sp<AMessage> reply = new AMessage('play', id());
                    mConn->sendRequest(request.c_str(), reply);
#endif // #ifdef MTK_AOSP_ENHANCEMENT
                } else {
#ifdef MTK_AOSP_ENHANCEMENT 
                    if (!mHaveAudio && !mHaveVideo) {
                        mFinalStatus = ERROR_UNSUPPORTED;
                    }
#endif // #ifdef MTK_AOSP_ENHANCEMENT
                    sp<AMessage> reply = new AMessage('disc', id());
                    mConn->disconnect(reply);
                }
                break;
            }

#ifndef MTK_AOSP_ENHANCEMENT
            case 'play':
            {
                int32_t result;
                CHECK(msg->findInt32("result", &result));

                ALOGI("PLAY completed with result %d (%s)",
                     result, strerror(-result));

                if (result == OK) {
                    sp<RefBase> obj;
                    CHECK(msg->findObject("response", &obj));
                    sp<ARTSPResponse> response =
                        static_cast<ARTSPResponse *>(obj.get());

                    if (response->mStatusCode != 200) {
                        result = UNKNOWN_ERROR;
                    } else {
                        parsePlayResponse(response);

                        sp<AMessage> timeout = new AMessage('tiou', id());
                        mCheckTimeoutGeneration++;
                        timeout->setInt32("tioucheck", mCheckTimeoutGeneration);
                        timeout->post(kStartupTimeoutUs);
                    }
                }

                if (result != OK) {
                    sp<AMessage> reply = new AMessage('disc', id());
                    mConn->disconnect(reply);
                }

                break;
            }
#endif
            case 'aliv':
            {
                int32_t generation;
                CHECK(msg->findInt32("generation", &generation));

                if (generation != mKeepAliveGeneration) {
                    // obsolete event.
                    break;
                }

                AString request;
                request.append("OPTIONS ");
#ifdef MTK_AOSP_ENHANCEMENT
                request.append(mBaseURL);
#else
                request.append(mSessionURL);
#endif
                request.append(" RTSP/1.0\r\n");
                request.append("Session: ");
                request.append(mSessionID);
                request.append("\r\n");
                request.append("\r\n");

                sp<AMessage> reply = new AMessage('opts', id());
                reply->setInt32("generation", mKeepAliveGeneration);
#ifdef MTK_AOSP_ENHANCEMENT
                reply->setInt32("keep-tcp", 1);
                ALOGI("sending keep alive");
#endif
                mConn->sendRequest(request.c_str(), reply);
                break;
            }

            case 'opts':
            {
                int32_t result;
                CHECK(msg->findInt32("result", &result));

                ALOGI("OPTIONS completed with result %d (%s)",
                     result, strerror(-result));

                int32_t generation;
                CHECK(msg->findInt32("generation", &generation));

                if (generation != mKeepAliveGeneration) {
                    // obsolete event.
                    break;
                }

                postKeepAlive();
                break;
            }

            case 'abor':
            {
#ifdef MTK_AOSP_ENHANCEMENT
                bool keepTracks = false;
                int32_t tmp;
                keepTracks = msg->findInt32("keep-tracks", &tmp);
#endif
                for (size_t i = 0; i < mTracks.size(); ++i) {
                    TrackInfo *info = &mTracks.editItemAt(i);

#ifdef MTK_AOSP_ENHANCEMENT
                    if (!keepTracks) {
#else
                    if (!mFirstAccessUnit) {
#endif
                        postQueueEOS(i, ERROR_END_OF_STREAM);
                    }

                    if (!info->mUsingInterleavedTCP) {
                        mRTPConn->removeStream(info->mRTPSocket, info->mRTCPSocket);

                        // Clear the tag
                        if (mUIDValid) {
                            HTTPBase::UnRegisterSocketUserTag(info->mRTPSocket);
                            HTTPBase::UnRegisterSocketUserMark(info->mRTPSocket);
                            HTTPBase::UnRegisterSocketUserTag(info->mRTCPSocket);
                            HTTPBase::UnRegisterSocketUserMark(info->mRTCPSocket);
                        }

                        close(info->mRTPSocket);
                        close(info->mRTCPSocket);
#ifdef MTK_AOSP_ENHANCEMENT
                        // reuse this flag to indicate that stream is removed
                        info->mUsingInterleavedTCP = true;
#endif
                    }
                }
#ifdef MTK_AOSP_ENHANCEMENT
                if (!keepTracks)
#endif
                mTracks.clear();
                mSetupTracksSuccessful = false;
                mSeekPending = false;
                mFirstAccessUnit = true;
                mAllTracksHaveTime = false;
                mNTPAnchorUs = -1;
                mMediaAnchorUs = -1;
                mNumAccessUnitsReceived = 0;
#ifndef MTK_AOSP_ENHANCEMENT 
                // DO NOT reset mReceivedFirstRTCPPacket, we do not want to 
                // try TCP if we have received RTCP
                mReceivedFirstRTCPPacket = false;
#endif // #ifdef MTK_AOSP_ENHANCEMENT
                mReceivedFirstRTPPacket = false;
#ifdef MTK_AOSP_ENHANCEMENT 
                mHaveVideo = false;
                mHaveAudio = false;
#endif // #ifdef MTK_AOSP_ENHANCEMENT
                mPausing = false;
                mSeekable = true;

                sp<AMessage> reply = new AMessage('tear', id());

                int32_t reconnect;
                if (msg->findInt32("reconnect", &reconnect) && reconnect) {
                    reply->setInt32("reconnect", true);
                }
#ifdef MTK_AOSP_ENHANCEMENT 
                else {
                    reply->setInt64("timeout", kTearDownTimeoutUs);
                }
#endif // #ifdef MTK_AOSP_ENHANCEMENT

                AString request;
                request = "TEARDOWN ";

                // XXX should use aggregate url from SDP here...
#ifdef MTK_AOSP_ENHANCEMENT
                request.append(mBaseURL);
#else
                request.append(mSessionURL);
#endif
                request.append(" RTSP/1.0\r\n");

                request.append("Session: ");
                request.append(mSessionID);
                request.append("\r\n");

                request.append("\r\n");

#ifdef MTK_AOSP_ENHANCEMENT
                ALOGD("sending TEARDOWN");
#endif // #ifdef MTK_AOSP_ENHANCEMENT
                mConn->sendRequest(request.c_str(), reply);
                break;
            }

            case 'tear':
            {
                int32_t result;
                CHECK(msg->findInt32("result", &result));

                ALOGI("TEARDOWN completed with result %d (%s)",
                     result, strerror(-result));

                sp<AMessage> reply = new AMessage('disc', id());

                int32_t reconnect;
                if (msg->findInt32("reconnect", &reconnect) && reconnect) {
                    reply->setInt32("reconnect", true);
                }

                mConn->disconnect(reply);
                break;
            }

            case 'quit':
            {
                sp<AMessage> msg = mNotify->dup();
                msg->setInt32("what", kWhatDisconnected);
#ifdef MTK_AOSP_ENHANCEMENT 
                msg->setInt32("result", mFinalStatus != (status_t)OK ? mFinalStatus : (status_t)UNKNOWN_ERROR);
#else
                msg->setInt32("result", UNKNOWN_ERROR);
#endif // #ifdef MTK_AOSP_ENHANCEMENT
                msg->post();
                break;
            }

            case 'chek':
            {
                int32_t generation;
                CHECK(msg->findInt32("generation", &generation));
                if (generation != mCheckGeneration) {
                    // This is an outdated message. Ignore.
                    break;
                }

                if (mNumAccessUnitsReceived == 0) {
#if 1
                    ALOGI("stream ended? aborting.");
                    (new AMessage('abor', id()))->post();
                    break;
#else
                    ALOGI("haven't seen an AU in a looong time.");
#endif
                }

                mNumAccessUnitsReceived = 0;
                msg->post(kAccessUnitTimeoutUs);
                break;
            }

            case 'accu':
            {
#ifdef MTK_AOSP_ENHANCEMENT
                if(mIntSeverError){
                    ALOGI("[rtsp]Internal server error happen,ignore received accu");
                    break;
                }
#endif
                int32_t timeUpdate;
                if (msg->findInt32("time-update", &timeUpdate) && timeUpdate) {
                    size_t trackIndex;
                    CHECK(msg->findSize("track-index", &trackIndex));

                    uint32_t rtpTime;
                    uint64_t ntpTime;
                    CHECK(msg->findInt32("rtp-time", (int32_t *)&rtpTime));
                    CHECK(msg->findInt64("ntp-time", (int64_t *)&ntpTime));

                    onTimeUpdate(trackIndex, rtpTime, ntpTime);
                    break;
                }
#ifdef MTK_AOSP_ENHANCEMENT
                int32_t rr;
                if (msg->findInt32("receiver-report", &rr)) {
                    onRecvRR(msg);
                    break;
                }
#endif

                int32_t first;
                if (msg->findInt32("first-rtcp", &first)) {
#ifdef MTK_AOSP_ENHANCEMENT 
                    ALOGI("receive first-rtcp");
#endif // #ifdef MTK_AOSP_ENHANCEMENT
                    mReceivedFirstRTCPPacket = true;
                    break;
                }

                if (msg->findInt32("first-rtp", &first)) {
#ifdef MTK_AOSP_ENHANCEMENT 
                    ALOGI("receive first-rtp");
#endif // #ifdef MTK_AOSP_ENHANCEMENT
                    mReceivedFirstRTPPacket = true;
                    break;
                }

                ++mNumAccessUnitsReceived;
#ifndef MTK_AOSP_ENHANCEMENT 
                // use per track check instead of all
                postAccessUnitTimeoutCheck();
#endif // #ifdef MTK_AOSP_ENHANCEMENT

                size_t trackIndex;
                CHECK(msg->findSize("track-index", &trackIndex));

                if (trackIndex >= mTracks.size()) {
                    ALOGV("late packets ignored.");
                    break;
                }

                TrackInfo *track = &mTracks.editItemAt(trackIndex);

                int32_t eos;
                if (msg->findInt32("eos", &eos)) {
                    ALOGI("received BYE on track index %d", trackIndex);
                    if (!mAllTracksHaveTime && dataReceivedOnAllChannels()) {
                        ALOGI("No time established => fake existing data");

                        track->mEOSReceived = true;
                        mTryFakeRTCP = true;
                        mReceivedFirstRTCPPacket = true;
                        fakeTimestamps();
                    } else {
                        postQueueEOS(trackIndex, ERROR_END_OF_STREAM);
                    }
                    return;
                }

#ifdef MTK_AOSP_ENHANCEMENT 
                if (msg->findInt32("stream-timeout", &eos)) {
                    return onStreamTomeOut(track, trackIndex);
                } else {
                    track->mTimeoutTrials = 0;
                }
#endif // #ifdef MTK_AOSP_ENHANCEMENT

                sp<ABuffer> accessUnit;
                CHECK(msg->findBuffer("access-unit", &accessUnit));

                uint32_t seqNum = (uint32_t)accessUnit->int32Data();

                if (mSeekPending) {
                    ALOGD("we're seeking, dropping stale packet.");
                    break;
                }

                if (seqNum < track->mFirstSeqNumInSegment) {
                    ALOGD("'accu':dropping stale access-unit (%d < %d)",
                         seqNum, track->mFirstSeqNumInSegment);
                    break;
                }

                if (track->mNewSegment) {
                    track->mNewSegment = false;
                }

                onAccessUnitComplete(trackIndex, accessUnit);
                break;
            }

            case 'paus':
            {
#ifdef MTK_AOSP_ENHANCEMENT
                //pause during swithing transport is hard to handle
                if (mSwitchingTCP) {
                    notifyMsg(kWhatPauseDone, INVALID_OPERATION);
                    break;
                }
#endif
                int32_t generation;
                CHECK(msg->findInt32("pausecheck", &generation));
                if (generation != mPauseGeneration) {
                    ALOGV("Ignoring outdated pause message.");
#ifdef MTK_AOSP_ENHANCEMENT
// mtk80902: old pause interrupted by latter operations
// here we assume that this pause is OK.
                    notifyMsg(kWhatPauseDone, OK);
#endif
                    break;
                }

                if (!mSeekable) {
#ifdef MTK_AOSP_ENHANCEMENT
                    notifyMsg(kWhatPauseDone, INVALID_OPERATION);
#endif
                    ALOGW("This is a live stream, ignoring pause request.");
                    break;
                }
                mCheckPending = true;
                ++mCheckGeneration;
                mPausing = true;
#ifdef MTK_AOSP_ENHANCEMENT
                mRTPConn->stopCheckAlives(); 
                mTiouPending = true;
                ALOGI("[rtsp]MyHandler sending pause now!");
#endif

                AString request = "PAUSE ";
                request.append(mControlURL);
                request.append(" RTSP/1.0\r\n");

                request.append("Session: ");
                request.append(mSessionID);
                request.append("\r\n");

                request.append("\r\n");

                sp<AMessage> reply = new AMessage('pau2', id());
                mConn->sendRequest(request.c_str(), reply);
                break;
            }

            case 'pau2':
            {
                int32_t result;
                CHECK(msg->findInt32("result", &result));
                mCheckTimeoutGeneration++;

                ALOGI("PAUSE completed with result %d (%s)",
                     result, strerror(-result));
#ifdef MTK_AOSP_ENHANCEMENT
                if (result != OK) {
                    mFinalStatus = MappingRTSPError(result);
                    ALOGE("pause failed, aborting.");
                    (new AMessage('abor', id()))->post();
                }
                resetTimestamps(); //set nNumTime = 0, need re-mapping rtp to ntp
                notifyMsg(kWhatPauseDone, result);
#endif
                break;
            }

            case 'resu':
            {
#ifdef MTK_AOSP_ENHANCEMENT
                ALOGD("mSeekPending %d, mPausing %d, mPlaySent %d", mSeekPending, mPausing, mPlaySent);
                if (mSeekPending) {
                    notifyMsg(kWhatPlayDone, INVALID_OPERATION);
                    break;
                } else if (!mPausing && mPlaySent) {
                    notifyMsg(kWhatPlayDone, OK);
                    break;
                }
#else
                if (mPausing && mSeekPending) {
                    // If seeking, Play will be sent from see1 instead
                    break;
                }

                if (!mPausing) {
                    // Dont send PLAY if we have not paused
                    break;
                }
#endif
                AString request = "PLAY ";
                request.append(mControlURL);
                request.append(" RTSP/1.0\r\n");

                request.append("Session: ");
                request.append(mSessionID);
                request.append("\r\n");
#ifdef MTK_AOSP_ENHANCEMENT
                if (!mPausing && !mPlaySent) {
                    request.append(
                            StringPrintf(
                                "Range: npt=0-\r\n"));
                    mPlaySent = true;
                }
#endif
                request.append("\r\n");

                sp<AMessage> reply = new AMessage('res2', id());
                mConn->sendRequest(request.c_str(), reply);
                break;
            }

            case 'res2':
            {
                int32_t result;
                CHECK(msg->findInt32("result", &result));

                ALOGI("PLAY completed with result %d (%s)",
                     result, strerror(-result));

                mCheckPending = false;
#ifndef MTK_AOSP_ENHANCEMENT
                postAccessUnitTimeoutCheck();
#endif

                if (result == OK) {
                    sp<RefBase> obj;
                    CHECK(msg->findObject("response", &obj));
                    sp<ARTSPResponse> response =
                        static_cast<ARTSPResponse *>(obj.get());

                    if (response->mStatusCode != 200) {
#ifdef MTK_AOSP_ENHANCEMENT 
                        result = onRes2Err(response);
#else
                        result = UNKNOWN_ERROR;
#endif
                    } else {
                        parsePlayResponse(response);

#ifdef MTK_AOSP_ENHANCEMENT 
                        // start per stream checker
                        mRTPConn->startCheckAlives();
						//post a new 'tiou' after play
						postTryTCPTimeOutCheck();
#else
                        // Post new timeout in order to make sure to use
                        // fake timestamps if no new Sender Reports arrive
                        sp<AMessage> timeout = new AMessage('tiou', id());
                        mCheckTimeoutGeneration++;
                        timeout->setInt32("tioucheck", mCheckTimeoutGeneration);
                        timeout->post(kStartupTimeoutUs);
#endif
                    }
                }

                if (result != OK) {
#ifdef MTK_AOSP_ENHANCEMENT 
                    mFinalStatus = MappingRTSPError(result);
#endif 
                    ALOGE("resume failed, aborting.");
                    (new AMessage('abor', id()))->post();
                }
#ifdef MTK_AOSP_ENHANCEMENT
                ALOGI("resume notifing with result %d.", result);
                notifyMsg(kWhatPlayDone, result);
#endif
                mPausing = false;
                break;
            }

            case 'seek':
            {
#ifdef MTK_AOSP_ENHANCEMENT
                int64_t timeUs;
                mNeedSeekNotify = true;
                CHECK(msg->findInt64("time", &timeUs));
                _seek(timeUs);
#else
                if (!mSeekable) {
                    ALOGW("This is a live stream, ignoring seek request.");

                    sp<AMessage> msg = mNotify->dup();
                    msg->setInt32("what", kWhatSeekDone);
                    msg->post();
                    break;
                }

                int64_t timeUs;
                CHECK(msg->findInt64("time", &timeUs));

                mSeekPending = true;

                // Disable the access unit timeout until we resumed
                // playback again.
                mCheckPending = true;
                ++mCheckGeneration;

                sp<AMessage> reply = new AMessage('see1', id());
                reply->setInt64("time", timeUs);

                if (mPausing) {
                    // PAUSE already sent
                    ALOGI("Pause already sent");
                    reply->post();
                    break;
                }
                AString request = "PAUSE ";
                request.append(mControlURL);
                request.append(" RTSP/1.0\r\n");

                request.append("Session: ");
                request.append(mSessionID);
                request.append("\r\n");

                request.append("\r\n");

                mConn->sendRequest(request.c_str(), reply);
#endif
                break;
            }

            case 'see1':
            {
                // Session is paused now.
#ifdef MTK_AOSP_ENHANCEMENT 
                int32_t result;
                CHECK(msg->findInt32("result", &result));
                ALOGI("PAUSE completed with result %d (%s)", result, strerror(-result));
                if (result != OK) {
                    onSee1Err(result);
                    break;
                }

                resetTimestamps();
#endif
                for (size_t i = 0; i < mTracks.size(); ++i) {
                    TrackInfo *info = &mTracks.editItemAt(i);

                    postQueueSeekDiscontinuity(i);
                    info->mEOSReceived = false;

                    info->mRTPAnchor = 0;
                    info->mNTPAnchorUs = -1;
                }

                mAllTracksHaveTime = false;
                mNTPAnchorUs = -1;

                // Start new timeoutgeneration to avoid getting timeout
                // before PLAY response arrive
                sp<AMessage> timeout = new AMessage('tiou', id());
                mCheckTimeoutGeneration++;
                timeout->setInt32("tioucheck", mCheckTimeoutGeneration);
                timeout->post(kStartupTimeoutUs);

                int64_t timeUs;
                CHECK(msg->findInt64("time", &timeUs));

#ifndef MTK_AOSP_ENHANCEMENT
                AString request = "PLAY ";
                request.append(mControlURL);
                request.append(" RTSP/1.0\r\n");

                request.append("Session: ");
                request.append(mSessionID);
                request.append("\r\n");

                request.append(
                        StringPrintf(
                            "Range: npt=%lld-\r\n", timeUs / 1000000ll));

                request.append("\r\n");
#endif

                sp<AMessage> reply = new AMessage('see2', id());
#ifdef MTK_AOSP_ENHANCEMENT
                mPlaySent = true;
                _play(timeUs, reply);
#else
                mConn->sendRequest(request.c_str(), reply);
#endif
                break;
            }

            case 'see2':
            {
#ifdef MTK_AOSP_ENHANCEMENT 
                // don't fail if we are aborted
                if (!mSeekPending)
                    break;
#endif // #ifdef MTK_AOSP_ENHANCEMENT
                if (mTracks.size() == 0) {
                    // We have already hit abor, break
                    break;
                }

                int32_t result;
                CHECK(msg->findInt32("result", &result));

                ALOGI("PLAY completed with result %d (%s)",
                     result, strerror(-result));

                mCheckPending = false;
#ifndef MTK_AOSP_ENHANCEMENT 
                // use per track check instead of all
                postAccessUnitTimeoutCheck();
#endif // #ifdef MTK_AOSP_ENHANCEMENT

                if (result == OK) {
                    sp<RefBase> obj;
                    CHECK(msg->findObject("response", &obj));
                    sp<ARTSPResponse> response =
                        static_cast<ARTSPResponse *>(obj.get());

                    if (response->mStatusCode != 200) {
#ifdef MTK_AOSP_ENHANCEMENT 
                        result = response->mStatusCode;
#else
                        result = UNKNOWN_ERROR;
#endif // #ifdef MTK_AOSP_ENHANCEMENT
                    } else {
                        parsePlayResponse(response);

                        // Post new timeout in order to make sure to use
                        // fake timestamps if no new Sender Reports arrive
                        sp<AMessage> timeout = new AMessage('tiou', id());
                        mCheckTimeoutGeneration++;
                        timeout->setInt32("tioucheck", mCheckTimeoutGeneration);
                        timeout->post(kStartupTimeoutUs);

                        ssize_t i = response->mHeaders.indexOfKey("rtp-info");
#ifndef MTK_AOSP_ENHANCEMENT
                        CHECK_GE(i, 0);
#endif // #ifdef MTK_AOSP_ENHANCEMENT

                        ALOGV("rtp-info: %s", response->mHeaders.valueAt(i).c_str());

                        ALOGI("seek completed.");
#ifdef MTK_AOSP_ENHANCEMENT 
                        mRTPConn->startCheckAlives();
						postTryTCPTimeOutCheck();
#endif // #ifdef MTK_AOSP_ENHANCEMENT
                    }
                }

                if (result != OK) {
#ifdef MTK_AOSP_ENHANCEMENT 
                    mFinalStatus = MappingRTSPError(result);
#endif // #ifdef MTK_AOSP_ENHANCEMENT
                    ALOGE("seek failed, aborting.");
                    (new AMessage('abor', id()))->post();
                }

                mPausing = false;
                mSeekPending = false;

#ifdef MTK_AOSP_ENHANCEMENT
                if (mNeedSeekNotify) {
                    notifyMsg(kWhatSeekDone, EINPROGRESS);
                    mNeedSeekNotify = false;
                }
#else
                sp<AMessage> msg = mNotify->dup();
                msg->setInt32("what", kWhatSeekDone);
                msg->post();
#endif
                break;
            }

            case 'biny':
            {
                sp<ABuffer> buffer;
                CHECK(msg->findBuffer("buffer", &buffer));

                int32_t index;
                CHECK(buffer->meta()->findInt32("index", &index));

                mRTPConn->injectPacket(index, buffer);
                break;
            }

            case 'tiou':
            {
#ifdef MTK_AOSP_ENHANCEMENT 
                onTiou(msg);
                break;
#endif // #ifdef MTK_AOSP_ENHANCEMENT
                int32_t timeoutGenerationCheck;
                CHECK(msg->findInt32("tioucheck", &timeoutGenerationCheck));
                if (timeoutGenerationCheck != mCheckTimeoutGeneration) {
                    // This is an outdated message. Ignore.
                    // This typically happens if a lot of seeks are
                    // performed, since new timeout messages now are
                    // posted at seek as well.
                    break;
                }
                if (!mReceivedFirstRTCPPacket) {
                    if (dataReceivedOnAllChannels() && !mTryFakeRTCP) {
                        ALOGW("We received RTP packets but no RTCP packets, "
                             "using fake timestamps.");

                        mTryFakeRTCP = true;

                        mReceivedFirstRTCPPacket = true;

                        fakeTimestamps();
                    } else if (!mReceivedFirstRTPPacket && !mTryTCPInterleaving) {
                        ALOGW("Never received any data, switching transports.");

                        mTryTCPInterleaving = true;

                        sp<AMessage> msg = new AMessage('abor', id());
                        msg->setInt32("reconnect", true);
                        msg->post();
                    } else {
                        ALOGW("Never received any data, disconnecting.");
                        (new AMessage('abor', id()))->post();
                    }
                } else {
                    if (!mAllTracksHaveTime) {
                        ALOGW("We received some RTCP packets, but time "
                              "could not be established on all tracks, now "
                              "using fake timestamps");

                        fakeTimestamps();
                    }
                }
                break;
            }

#ifdef MTK_AOSP_ENHANCEMENT
            case 'prse':
            {
                int64_t timeUs;
                CHECK(msg->findInt64("time", &timeUs));
                _preSeek(timeUs, true);
                break;
            }

            case 'nopl':
            {
                int32_t result;
                CHECK(msg->findInt32("result", &result));

                ALOGI("pipeline PLAY completed with result %d (%s)",
                     result, strerror(-result));
                break;
            }
#endif
            default:
                TRESPASS();
                break;
        }
    }

    void postKeepAlive() {
        sp<AMessage> msg = new AMessage('aliv', id());
        msg->setInt32("generation", mKeepAliveGeneration);
        msg->post((mKeepAliveTimeoutUs * 9) / 10);
    }

    void postAccessUnitTimeoutCheck() {
        if (mCheckPending) {
            return;
        }

        mCheckPending = true;
        sp<AMessage> check = new AMessage('chek', id());
        check->setInt32("generation", mCheckGeneration);
        check->post(kAccessUnitTimeoutUs);
    }

    static void SplitString(
            const AString &s, const char *separator, List<AString> *items) {
        items->clear();
        size_t start = 0;
        while (start < s.size()) {
            ssize_t offset = s.find(separator, start);

            if (offset < 0) {
                items->push_back(AString(s, start, s.size() - start));
                break;
            }

            items->push_back(AString(s, start, offset - start));
            start = offset + strlen(separator);
        }
    }

    void parsePlayResponse(const sp<ARTSPResponse> &response) {
        mPlayResponseParsed = true;
        if (mTracks.size() == 0) {
            ALOGV("parsePlayResponse: late packets ignored.");
            return;
        }
#ifdef MTK_AOSP_ENHANCEMENT 
        Vector<uint32_t> rtpTimes;
        Vector<uint32_t> rtpSeqs;
        Vector<int32_t> rtpSockets;
        mPlayRespPending = false;
#endif // #ifdef MTK_AOSP_ENHANCEMENT

        ssize_t i = response->mHeaders.indexOfKey("range");
#ifdef MTK_AOSP_ENHANCEMENT
#ifdef MTK_RTSP_ERROR_TEST_PLAY_NORANGE
        ALOGI("MTK_RTSP_ERROR_TEST_PLAY_NORANGE");
        i = -1;
#endif
#endif
        if (i < 0) {
            // Server doesn't even tell use what range it is going to
            // play, therefore we won't support seeking.
#ifdef MTK_AOSP_ENHANCEMENT 
            // enable SR timestamp
            ALOGI("no range, using SR ntp, using last play time %lld", mLastPlayTimeUs);
            mMediaAnchorUs = mLastPlayTimeUs;
#endif // #ifdef MTK_AOSP_ENHANCEMENT
            return;
        }

        AString range = response->mHeaders.valueAt(i);
        ALOGV("Range: %s", range.c_str());

        AString val;
        CHECK(GetAttribute(range.c_str(), "npt", &val));

        float npt1, npt2;
#ifdef MTK_AOSP_ENHANCEMENT
        npt1 = npt2 = 0;    // mtk80902: ALPS00374143
#endif
        if (!ASessionDescription::parseNTPRange(val.c_str(), &npt1, &npt2)) {
            // This is a live stream and therefore not seekable.

            ALOGI("This is a live stream");
#ifndef MTK_AOSP_ENHANCEMENT
            // we still need to process rtp-info
            return;
#endif
        }

        i = response->mHeaders.indexOfKey("rtp-info");
#ifdef MTK_AOSP_ENHANCEMENT
        // mtk80902: ALPS00458191 - bad server
        // PLAY resp doesnt contain rtp-info
        List<AString> streamInfos;
        if (i < 0) {
            ALOGW("No rtp-info in PLAY resp, whose bad server?");
        } else {
            AString rtpInfo = response->mHeaders.valueAt(i);
            SplitString(rtpInfo, ",", &streamInfos);
        }
#else
        CHECK_GE(i, 0);

        AString rtpInfo = response->mHeaders.valueAt(i);
        List<AString> streamInfos;
        SplitString(rtpInfo, ",", &streamInfos);
#endif
        int n = 1;
        for (List<AString>::iterator it = streamInfos.begin();
             it != streamInfos.end(); ++it) {
            (*it).trim();
            ALOGV("streamInfo[%d] = %s", n, (*it).c_str());

            CHECK(GetAttribute((*it).c_str(), "url", &val));

            size_t trackIndex = 0;
#ifdef MTK_AOSP_ENHANCEMENT 
            AString str = val;
            CHECK(MakeURL(mBaseURL.c_str(), str.c_str(), &val));
#endif // #ifdef MTK_AOSP_ENHANCEMENT
            while (trackIndex < mTracks.size()
                    && !(val == mTracks.editItemAt(trackIndex).mURL)) {
                ++trackIndex;
            }
#ifdef MTK_AOSP_ENHANCEMENT 
            if (trackIndex >= mTracks.size()) {
                ALOGW("ignore unknown url in PLAY response %s", val.c_str());
                ++n;
                continue;
            }

            // continue instead of failure
            if (!GetAttribute((*it).c_str(), "seq", &val)) {
                ++n;
                continue;
            }
#else
            CHECK_LT(trackIndex, mTracks.size());

            CHECK(GetAttribute((*it).c_str(), "seq", &val));
#endif // #ifdef MTK_AOSP_ENHANCEMENT

            char *end;
            unsigned long seq = strtoul(val.c_str(), &end, 10);

            TrackInfo *info = &mTracks.editItemAt(trackIndex);
            info->mFirstSeqNumInSegment = seq;
            info->mNewSegment = true;

#ifdef MTK_AOSP_ENHANCEMENT 
#ifdef MTK_RTSP_ERROR_TEST_PLAY_NORTPTIME
            ALOGI("MTK_RTSP_ERROR_TEST_PLAY_NORTPTIME");
            continue;
#endif
            // continue instead of failure
            if (!GetAttribute((*it).c_str(), "rtptime", &val)) {
                ++n;
                continue;
            }
#else
            CHECK(GetAttribute((*it).c_str(), "rtptime", &val));
#endif // #ifdef MTK_AOSP_ENHANCEMENT

            uint32_t rtpTime = strtoul(val.c_str(), &end, 10);

#ifdef MTK_AOSP_ENHANCEMENT //haizhen
			ALOGI("track #%d: rtpTime=%u <=> npt=%.2f", n, rtpTime, npt1);
#else
            ALOGV("track #%d: rtpTime=%u <=> npt=%.2f", n, rtpTime, npt1);
#endif

            info->mNormalPlayTimeRTP = rtpTime;
            info->mNormalPlayTimeUs = (int64_t)(npt1 * 1E6);
            
#ifdef MTK_AOSP_ENHANCEMENT //haizhen
			ALOGI("parsePlayResponse,info->mPlayStartTime=%f, npt1=%f",info->mPlayStartTime,npt1);
			if((info->mPlayStartTime) - npt1 > (float)kMaxInterleave)	
				info->mPlayStartTime = npt1;
#endif
            if (!mFirstAccessUnit) {
                postNormalPlayTimeMapping(
                        trackIndex,
                        info->mNormalPlayTimeRTP, info->mNormalPlayTimeUs);
            }

#ifdef MTK_AOSP_ENHANCEMENT 
            info->mLastMediaTimeUs = (int64_t)(npt1 * 1E6);
            info->mTimeoutTrials = 0;
            rtpTimes.push(rtpTime);
            rtpSockets.push(info->mRTPSocket);
            rtpSeqs.push(info->mFirstSeqNumInSegment);
#endif // #ifdef MTK_AOSP_ENHANCEMENT
            ++n;
        }

#ifdef MTK_AOSP_ENHANCEMENT 
        parsePlayResponse_l(rtpTimes, rtpSeqs, rtpSockets, npt1, npt2);
#endif // #ifdef MTK_AOSP_ENHANCEMENT
    }

    sp<MetaData> getTrackFormat(size_t index, int32_t *timeScale) {
        CHECK_GE(index, 0u);
        CHECK_LT(index, mTracks.size());

        const TrackInfo &info = mTracks.itemAt(index);

        *timeScale = info.mTimeScale;

        return info.mPacketSource->getFormat();
    }

    size_t countTracks() const {
        return mTracks.size();
    }

private:
    struct TrackInfo {
        AString mURL;
        int mRTPSocket;
        int mRTCPSocket;
        bool mUsingInterleavedTCP;
        uint32_t mFirstSeqNumInSegment;
        bool mNewSegment;

        uint32_t mRTPAnchor;
        int64_t mNTPAnchorUs;
        int32_t mTimeScale;
        bool mEOSReceived;

        uint32_t mNormalPlayTimeRTP;
        int64_t mNormalPlayTimeUs;

        sp<APacketSource> mPacketSource;

        // Stores packets temporarily while no notion of time
        // has been established yet.
        List<sp<ABuffer> > mPackets;
#ifdef MTK_AOSP_ENHANCEMENT 
        bool mFirstAccessUnit;
        bool mUseTrackNTP;
        uint64_t mFirstAccessUnitNTP;
        int mRTPPort;
        AString mTransport;
        int mTimeoutTrials;
        int64_t mLastMediaTimeUs;
		float mPlayStartTime; //add by haizhen
        unsigned long mNADUFreq;
	  bool mIsEOSByError;

        void setTimestamp(uint32_t rtpTime, int64_t ntpTimeUs, bool fake = false) {
            mRTPAnchor = rtpTime;
            mNTPAnchorUs = ntpTimeUs;
            mUseTrackNTP = fake;
            ALOGI("setTimestamp rtp %d, ntp %lld, track %d", mRTPAnchor, mNTPAnchorUs, mUseTrackNTP);
        }

        void resetTimestamp() {
            ALOGI("resetTimestamp rtp %d, ntp %lld, track %d", mRTPAnchor, mNTPAnchorUs, mUseTrackNTP);
            mRTPAnchor = 0;
            mNTPAnchorUs = -1;
            mUseTrackNTP = false;
            mFirstAccessUnit = true;
        };
#endif // #ifdef MTK_AOSP_ENHANCEMENT
    };

    sp<AMessage> mNotify;
    bool mUIDValid;
    uid_t mUID;
    sp<ALooper> mNetLooper;
    sp<ARTSPConnection> mConn;
    sp<ARTPConnection> mRTPConn;
    sp<ASessionDescription> mSessionDesc;
    AString mOriginalSessionURL;  // This one still has user:pass@
    AString mSessionURL;
    AString mSessionHost;
    AString mBaseURL;
    AString mControlURL;
    AString mSessionID;
    bool mSetupTracksSuccessful;
    bool mSeekPending;
    bool mFirstAccessUnit;

    bool mAllTracksHaveTime;
    int64_t mNTPAnchorUs;
    int64_t mMediaAnchorUs;
    int64_t mLastMediaTimeUs;

    int64_t mNumAccessUnitsReceived;
    bool mCheckPending;
    int32_t mCheckGeneration;
    int32_t mCheckTimeoutGeneration;
    bool mTryTCPInterleaving;
    bool mTryFakeRTCP;
    bool mReceivedFirstRTCPPacket;
    bool mReceivedFirstRTPPacket;
    bool mSeekable;
    int64_t mKeepAliveTimeoutUs;
    int32_t mKeepAliveGeneration;
    bool mPausing;
    int32_t mPauseGeneration;

    Vector<TrackInfo> mTracks;

    bool mPlayResponseParsed;

#ifdef MTK_AOSP_ENHANCEMENT 
    // please add new member below this line
    int64_t mLastPlayTimeUs;
    bool mPlayRespPending;
    bool mNeedSeekNotify;
	//for Bitrate-Adaptation
	bool mSupBitRateAdap;
	AString mServerInfo; // some server info
    //for 'bitrate-Adap' need special handle for DSS
	bool mIsDarwinServer;
    bool mIsLegacyMode;
    //bool mPausePending;
    sp<ALooper> mRTSPNetLooper;
    int64_t mLastSeekTimeTime;
	bool mIntSeverError; //haizhen
	bool mTiouPending; //haizhen for 'tiou' AMessage. if pause or seek need cancel 'tiou'
    int32_t mTiouGeneration; //haizhen  for 'tiou' AMessage. we only need handle the the new 'tiou' 
    bool mSwitchingTCP;
    int mTryingTCPIndex;
    bool mSkipDescribe;
    // only SETUP the first supported video/audio stream
    // FIXME There should be a track selection procedure in AwesomePlayer
    // to do a better selection
    bool mPlaySent;
    bool mHaveVideo;
    bool mHaveAudio;
    status_t mFinalStatus;
    int64_t mInitNPT;
    bool mExited;
    bool mNPTMode;
    bool mRegistered;
    int mMinUDPPort;
    int mMaxUDPPort;

	//for bitrate adaptation
	size_t m_BufQueSize; //Whole Buffer queue size 
    size_t m_TargetTime;  // target protected time of buffer queue duration for interrupt-free playback 
   // mtk80902: just for kKeyWantsNAL
    sp<MetaData> mSourceParam;

#endif // #ifdef MTK_AOSP_ENHANCEMENT

    void setupTrack(size_t index) {
#ifdef MTK_AOSP_ENHANCEMENT 
        if (mExited)
            return;
#endif // #ifdef MTK_AOSP_ENHANCEMENT
   		ALOGI("setupTrack index=%d",index);
        sp<APacketSource> source =
            new APacketSource(mSessionDesc, index);

        if (source->initCheck() != OK) {
            ALOGW("Unsupported format. Ignoring track #%d.", index);

            sp<AMessage> reply = new AMessage('setu', id());
            reply->setSize("index", index);
            reply->setInt32("result", ERROR_UNSUPPORTED);
#ifdef MTK_AOSP_ENHANCEMENT 
            if (source->initCheck() == ERROR_UNSUPPORTED) {
                const char *mime;
                if (source->getFormat()->findCString(kKeyMIMEType, &mime)){
                    if (!strncasecmp(mime, "video/", 6)) {
                        reply->setInt32("unsupport-video", 1);
                    }
                }
            }
#endif
            reply->post();
            return;
#ifdef MTK_AOSP_ENHANCEMENT 
        } else {
            if (skipMultiStreams(source, index))
                return;
#endif // #ifdef MTK_AOSP_ENHANCEMENT
        }

        AString url;
        CHECK(mSessionDesc->findAttribute(index, "a=control", &url));

        AString trackURL;
        CHECK(MakeURL(mBaseURL.c_str(), url.c_str(), &trackURL));

#ifdef MTK_AOSP_ENHANCEMENT
        TrackInfo *info;
        if (mSwitchingTCP) {
            switchingTCP_l(info, source, index);
        } else {
            mTracks.push(TrackInfo());
            info = &mTracks.editItemAt(mTracks.size() - 1);
            mTryingTCPIndex = mTracks.size();
        }
		ALOGI("setupTrack,mTracks.size()=%d",mTracks.size());
#else
        mTracks.push(TrackInfo());
        TrackInfo *info = &mTracks.editItemAt(mTracks.size() - 1);
#endif
        info->mURL = trackURL;
        info->mPacketSource = source;
        info->mUsingInterleavedTCP = false;
        info->mFirstSeqNumInSegment = 0;
        info->mNewSegment = true;
        info->mRTPSocket = -1;
        info->mRTCPSocket = -1;
        info->mRTPAnchor = 0;
        info->mNTPAnchorUs = -1;
        info->mNormalPlayTimeRTP = 0;
        info->mNormalPlayTimeUs = 0ll;

        unsigned long PT;
        AString formatDesc;
        AString formatParams;
        mSessionDesc->getFormatType(index, &PT, &formatDesc, &formatParams);

        int32_t timescale;
        int32_t numChannels;
        ASessionDescription::ParseFormatDesc(
                formatDesc.c_str(), &timescale, &numChannels);

        info->mTimeScale = timescale;
        info->mEOSReceived = false;

#ifdef MTK_AOSP_ENHANCEMENT 
        initInfo(info, index);
#endif		//add by haizhen stop	

        ALOGV("track #%d URL=%s", mTracks.size(), trackURL.c_str());

        AString request = "SETUP ";
        request.append(trackURL);
        request.append(" RTSP/1.0\r\n");

        if (mTryTCPInterleaving) {
#ifdef MTK_AOSP_ENHANCEMENT
            size_t interleaveIndex = 2 * (mTryingTCPIndex - 1);
#else
            size_t interleaveIndex = 2 * (mTracks.size() - 1);
#endif
            info->mUsingInterleavedTCP = true;
            info->mRTPSocket = interleaveIndex;
            info->mRTCPSocket = interleaveIndex + 1;

            request.append("Transport: RTP/AVP/TCP;interleaved=");
            request.append(interleaveIndex);
            request.append("-");
            request.append(interleaveIndex + 1);
        } else {
            unsigned rtpPort;
            ARTPConnection::MakePortPair(
#ifdef MTK_AOSP_ENHANCEMENT 
                    &info->mRTPSocket, &info->mRTCPSocket, &rtpPort,
                    mMinUDPPort, mMaxUDPPort);
#else
                    &info->mRTPSocket, &info->mRTCPSocket, &rtpPort);
#endif // #ifdef MTK_AOSP_ENHANCEMENT
#ifdef MTK_AOSP_ENHANCEMENT 
            info->mRTPPort = rtpPort;
#endif // #ifdef MTK_AOSP_ENHANCEMENT

            if (mUIDValid) {
                HTTPBase::RegisterSocketUserTag(info->mRTPSocket, mUID,
                                                (uint32_t)*(uint32_t*) "RTP_");
                HTTPBase::RegisterSocketUserTag(info->mRTCPSocket, mUID,
                                                (uint32_t)*(uint32_t*) "RTP_");
                HTTPBase::RegisterSocketUserMark(info->mRTPSocket, mUID);
                HTTPBase::RegisterSocketUserMark(info->mRTCPSocket, mUID);
            }

            request.append("Transport: RTP/AVP/UDP;unicast;client_port=");
            request.append(rtpPort);
            request.append("-");
            request.append(rtpPort + 1);
        }

        request.append("\r\n");

#ifdef MTK_AOSP_ENHANCEMENT 
        //for Bitrate-Adaptation
        info->mNADUFreq = 0;
        if (mSupBitRateAdap) {
            supBitRateAdap(info, request, index, trackURL);
        }		

        if (mTryingTCPIndex > 1) {
#else
        if (index > 1) {
#endif
            request.append("Session: ");
            request.append(mSessionID);
            request.append("\r\n");
        }

        request.append("\r\n");

        sp<AMessage> reply = new AMessage('setu', id());
        reply->setSize("index", index);
#ifdef MTK_AOSP_ENHANCEMENT
        reply->setSize("track-index", mTryingTCPIndex - 1);
#else
        reply->setSize("track-index", mTracks.size() - 1);
#endif
        mConn->sendRequest(request.c_str(), reply);
    }

    static bool MakeURL(const char *baseURL, const char *url, AString *out) {
        out->clear();

        if (strncasecmp("rtsp://", baseURL, 7)) {
            // Base URL must be absolute
            return false;
        }

        if (!strncasecmp("rtsp://", url, 7)) {
            // "url" is already an absolute URL, ignore base URL.
            out->setTo(url);
            return true;
        }

        size_t n = strlen(baseURL);
        if (baseURL[n - 1] == '/') {
            out->setTo(baseURL);
            out->append(url);
        } else {
#ifdef MTK_AOSP_ENHANCEMENT 
            // no strip chars after last '/'
            out->setTo(baseURL);
#else
            const char *slashPos = strrchr(baseURL, '/');

            if (slashPos > &baseURL[6]) {
                out->setTo(baseURL, slashPos - baseURL);
            } else {
                out->setTo(baseURL);
            }
#endif // #ifdef MTK_AOSP_ENHANCEMENT

            out->append("/");
            out->append(url);
        }

        return true;
    }

    void fakeTimestamps() {
        mNTPAnchorUs = -1ll;
        for (size_t i = 0; i < mTracks.size(); ++i) {
            onTimeUpdate(i, 0, 0ll);
        }
    }

    bool dataReceivedOnAllChannels() {
        TrackInfo *track;
        for (size_t i = 0; i < mTracks.size(); ++i) {
            track = &mTracks.editItemAt(i);
            if (track->mPackets.empty()) {
                return false;
            }
        }
        return true;
    }

    void handleFirstAccessUnit() {
        if (mFirstAccessUnit) {
#ifndef MTK_AOSP_ENHANCEMENT
            sp<AMessage> msg = mNotify->dup();
            msg->setInt32("what", kWhatConnected);
            msg->post();
#endif

            if (mSeekable) {
                for (size_t i = 0; i < mTracks.size(); ++i) {
                    TrackInfo *info = &mTracks.editItemAt(i);

                    postNormalPlayTimeMapping(
                            i,
                            info->mNormalPlayTimeRTP, info->mNormalPlayTimeUs);
                }
            }

            mFirstAccessUnit = false;
        }
    }

#ifdef MTK_AOSP_ENHANCEMENT
    void onTimeUpdate(int32_t trackIndex, uint32_t rtpTime, uint64_t ntpTime, bool fake = false) {
        ALOGI("onTimeUpdate track %d, rtpTime = 0x%08x, ntpTime = 0x%016llx, fake = %d",
             trackIndex, rtpTime, ntpTime, fake);
#else
    void onTimeUpdate(int32_t trackIndex, uint32_t rtpTime, uint64_t ntpTime) {
        ALOGV("onTimeUpdate track %d, rtpTime = 0x%08x, ntpTime = 0x%016llx",
             trackIndex, rtpTime, ntpTime);
#endif

        int64_t ntpTimeUs = (int64_t)(ntpTime * 1E6 / (1ll << 32));

        TrackInfo *track = &mTracks.editItemAt(trackIndex);

#ifdef MTK_AOSP_ENHANCEMENT
        if (track->mNTPAnchorUs < 0) {
            track->setTimestamp(rtpTime, ntpTimeUs, fake);
        }
#else
        track->mRTPAnchor = rtpTime;
        track->mNTPAnchorUs = ntpTimeUs;

        if (mNTPAnchorUs < 0) {
            mNTPAnchorUs = ntpTimeUs;
            mMediaAnchorUs = mLastMediaTimeUs;
        }
#endif

        if (!mAllTracksHaveTime) {
            bool allTracksHaveTime = true;
            for (size_t i = 0; i < mTracks.size(); ++i) {
                TrackInfo *track = &mTracks.editItemAt(i);
                if (track->mNTPAnchorUs < 0) {
                    allTracksHaveTime = false;
                    break;
                }
            }
            if (allTracksHaveTime) {
                mAllTracksHaveTime = true;
                ALOGI("Time now established for all tracks.");
            }
        }
        if (mAllTracksHaveTime && dataReceivedOnAllChannels()) {
            handleFirstAccessUnit();

            // Time is now established, lets start timestamping immediately
            for (size_t i = 0; i < mTracks.size(); ++i) {
                TrackInfo *trackInfo = &mTracks.editItemAt(i);
                while (!trackInfo->mPackets.empty()) {
                    sp<ABuffer> accessUnit = *trackInfo->mPackets.begin();
                    trackInfo->mPackets.erase(trackInfo->mPackets.begin());

                    if (addMediaTimestamp(i, trackInfo, accessUnit)) {
                        postQueueAccessUnit(i, accessUnit);
                    }
                }
            }
            for (size_t i = 0; i < mTracks.size(); ++i) {
                TrackInfo *trackInfo = &mTracks.editItemAt(i);
                if (trackInfo->mEOSReceived) {
                    postQueueEOS(i, ERROR_END_OF_STREAM);
                    trackInfo->mEOSReceived = false;
                }
            }
        }
    }

    void onAccessUnitComplete(
            int32_t trackIndex, const sp<ABuffer> &accessUnit) {
        ALOGV("onAccessUnitComplete track %d", trackIndex);

        if(!mPlayResponseParsed){
            ALOGI("play response is not parsed, storing accessunit");
            TrackInfo *track = &mTracks.editItemAt(trackIndex);
            track->mPackets.push_back(accessUnit);
            return;
        }

        handleFirstAccessUnit();

        TrackInfo *track = &mTracks.editItemAt(trackIndex);

#ifdef MTK_AOSP_ENHANCEMENT
        if (!mAllTracksHaveTime || mPlayRespPending) {
#else
        if (!mAllTracksHaveTime) {
#endif
            ALOGD("storing accessUnit, no time established yet");
            track->mPackets.push_back(accessUnit);
            return;
        }

        while (!track->mPackets.empty()) {
            sp<ABuffer> accessUnit = *track->mPackets.begin();
            track->mPackets.erase(track->mPackets.begin());

            if (addMediaTimestamp(trackIndex, track, accessUnit)) {
                postQueueAccessUnit(trackIndex, accessUnit);
            }
        }

        if (addMediaTimestamp(trackIndex, track, accessUnit)) {
            postQueueAccessUnit(trackIndex, accessUnit);
        }

        if (track->mEOSReceived) {
            postQueueEOS(trackIndex, ERROR_END_OF_STREAM);
            track->mEOSReceived = false;
        }
    }

    bool addMediaTimestamp(
#ifdef MTK_AOSP_ENHANCEMENT
            int32_t trackIndex, TrackInfo *track,
#else
            int32_t trackIndex, const TrackInfo *track,
#endif
            const sp<ABuffer> &accessUnit) {
       // UNUSED_UNLESS_VERBOSE(trackIndex);

        uint32_t rtpTime;
        CHECK(accessUnit->meta()->findInt32(
                    "rtp-time", (int32_t *)&rtpTime));

        int64_t relRtpTimeUs =
#ifdef MTK_AOSP_ENHANCEMENT
            ((int64_t)((int32_t)(rtpTime - track->mRTPAnchor)) * 1000000ll)
#else
            (((int64_t)rtpTime - (int64_t)track->mRTPAnchor) * 1000000ll)
#endif
                / track->mTimeScale;

        int64_t ntpTimeUs = track->mNTPAnchorUs + relRtpTimeUs;

#ifdef MTK_AOSP_ENHANCEMENT
        int64_t mediaTimeUs = getMediaTimeUs(track, accessUnit, rtpTime, ntpTimeUs);
        track->mLastMediaTimeUs = mediaTimeUs;
        mLastMediaTimeUs = mediaTimeUs;
#else
        int64_t mediaTimeUs = mMediaAnchorUs + ntpTimeUs - mNTPAnchorUs;

        if (mediaTimeUs > mLastMediaTimeUs) {
            mLastMediaTimeUs = mediaTimeUs;
        }

        if (mediaTimeUs < 0) {
            ALOGV("dropping early accessUnit.");
            return false;
        }
#endif

        ALOGV("track %d rtpTime=%d mediaTimeUs = %lld us (%.2f secs)",
             trackIndex, rtpTime, mediaTimeUs, mediaTimeUs / 1E6);

        accessUnit->meta()->setInt64("timeUs", mediaTimeUs);

        return true;
    }

    void postQueueAccessUnit(
            size_t trackIndex, const sp<ABuffer> &accessUnit) {
#ifdef MTK_AOSP_ENHANCEMENT
        if (noNeedPostQueueAU(trackIndex, accessUnit))
            return;
#endif		
        sp<AMessage> msg = mNotify->dup();
        msg->setInt32("what", kWhatAccessUnit);
        msg->setSize("trackIndex", trackIndex);
        msg->setBuffer("accessUnit", accessUnit);
        msg->post();
    }

    void postQueueEOS(size_t trackIndex, status_t finalResult) {
#ifdef MTK_AOSP_ENHANCEMENT
        ALOGI("postQueueEOS for %d: %d", trackIndex, finalResult);
#endif
        sp<AMessage> msg = mNotify->dup();
        msg->setInt32("what", kWhatEOS);
        msg->setSize("trackIndex", trackIndex);
        msg->setInt32("finalResult", finalResult);
        msg->post();
    }

    void postQueueSeekDiscontinuity(size_t trackIndex) {
#ifdef MTK_AOSP_ENHANCEMENT
        if (mIsLegacyMode) {
            ALOGI("flush track %d", trackIndex);
            TrackInfo *info = &mTracks.editItemAt(trackIndex);
            info->mPacketSource->flushQueue();
            return;
        }

        flushPackets(trackIndex);
#endif		
			
		ALOGI("postQueueSeekDiscontinuity flush track %d", trackIndex);
        sp<AMessage> msg = mNotify->dup();
        msg->setInt32("what", kWhatSeekDiscontinuity);
        msg->setSize("trackIndex", trackIndex);
        msg->post();
    }

    void postNormalPlayTimeMapping(
            size_t trackIndex, uint32_t rtpTime, int64_t nptUs) {
        sp<AMessage> msg = mNotify->dup();
        msg->setInt32("what", kWhatNormalPlayTimeMapping);
        msg->setSize("trackIndex", trackIndex);
        msg->setInt32("rtpTime", rtpTime);
        msg->setInt64("nptUs", nptUs);
        msg->post();
    }

    DISALLOW_EVIL_CONSTRUCTORS(MyHandler);
#ifdef MTK_AOSP_ENHANCEMENT
public:
    status_t MappingRTSPError(int32_t result) {
        switch(result) {
            case -100: // ENETDOWN
            case -101: // ENETUNREACH
            case -102: // ENETRESET
            case -103: // ECONNABORTED
            case -104: // ECONNRESET
            case -107: // ENOTCONN
            case -108: // ESHUTDOWN
            case -110: // ETIMEDOUT
            case -111: // ECONNREFUSED
            case -112: // EHOSTDOWN
            case -113: // EHOSTUNREACH
            case 503: // Service Unavailable
                return ERROR_CANNOT_CONNECT;
            case ERROR_UNSUPPORTED:
            case 415:
                return ERROR_UNSUPPORTED;
            case 403:
                return ERROR_FORBIDDEN;
            default:
                return UNKNOWN_ERROR;
        }
    }

    void init() {
        // please add new member below this line
        mLastPlayTimeUs = 0;
        mPlayRespPending = false;
        mNeedSeekNotify = false;
        mSupBitRateAdap = false;
        mIsDarwinServer = false;
        mIsLegacyMode = false;
        //mPausePending(false),
        mRTSPNetLooper = new ALooper;
        mLastSeekTimeTime = -1;
        mIntSeverError = false;//haizhen
        mTiouPending = false;//haizhen for 'tiou' AMessage. if pause or seek need cancel 'tiou'
        mTiouGeneration = 0;//haizhen  for 'tiou' AMessage. we only need handle the the new 'tiou' 
        mSwitchingTCP = false;
        mTryingTCPIndex = 0;
        mSkipDescribe = false;
        mPlaySent = false;
        mHaveVideo = false;
        mHaveAudio = false;
        mFinalStatus = OK;
        mInitNPT = 0;
        mExited = false;
        mNPTMode = false;
        mRegistered = false;
        mMinUDPPort = 0;
        mMaxUDPPort = 65535;
        
        mRTSPNetLooper->setName("rtsp looper");
        mRTSPNetLooper->start();
        
        checkBitRateAdap();
        m_BufQueSize = 0;
        m_TargetTime = 0;
        // for rand allocate rtp/rtcp ports
        srand(time(NULL));
    }
    
    void notifyMsg(int32_t what, status_t result) {
        sp<AMessage> msg = mNotify->dup();
        msg->setInt32("what", what);
        msg->setInt32("result", result);
        msg->post();
    }

    
    void checkServer(sp<ARTSPResponse> response) {
        //forward the server info (whether is DarwinServer) to ARTPConncetion
        //DSS 6.0.3 can not handle pss0 very good, so we will not send PSS0 to DSS
        ssize_t i = -1; 
        i = response->mHeaders.indexOfKey("server");
        if(i >= 0){ //if has Server header
            mServerInfo = response->mHeaders.valueAt(i);
            ALOGI("server info %s", mServerInfo.c_str());
            mServerInfo.tolower();
            if((mServerInfo.size() > 0) && 
                    ((-1 != mServerInfo.find("dss")) || (-1 != mServerInfo.find("darwin")))){
                mIsDarwinServer = true;
            }
        }
    }

    int32_t getSSRC(const AString transport) {
        int32_t ssrc = -1;
        AString val;
        ALOGI("transport %s", transport.c_str());
        if (GetAttribute(transport.c_str(), "ssrc", &val)) {
            char *end;
            ssrc = strtoul(val.c_str(), &end, 16);
            ALOGI("ssrc %s(%x)", val.c_str(), ssrc);
            return ssrc;
        }
        return -1;
    } 
    
    void postEOSDisconn(int32_t result) {
        ALOGI("send eos to all tracks");
        for(size_t i = 0; i < mTracks.size(); ++i) {
            postQueueEOS(i, ERROR_END_OF_STREAM);
        }
        mFinalStatus = MappingRTSPError(result);
        sp<AMessage> reply = new AMessage('disc', id());
        mConn->disconnect(reply);
    }

    
    void setUnsupportVideo(const sp<AMessage> &msg) {
        int v;
        if (msg->findInt32("unsupport-video", &v) && v) {
            mNotify->setInt32("unsupport-video", 1);
        }
    }

    void switchingTCP() {
        mSwitchingTCP = false;
        mPlaySent = false;
        if (mLastSeekTimeTime != -1) {
            //for some sever can't support play to seektime directly
            //without send play 0 first
            //for UDP transfering to TCP, we should play 0-> pause -> play seektime
            //msg->setInt64("play-time", mLastSeekTimeTime);
            //FIXME check return value
            _preSeek(mLastSeekTimeTime, false);
            mNeedSeekNotify = false;
            _seek(mLastSeekTimeTime);
            ALOGI("Switching to TCP, seek to mLastSeekTimeTime=%lld",mLastSeekTimeTime);    
        } else {
            sp<AMessage> msg = new AMessage('resu', id());
            msg->post();
        }

    }

    void onRecvRR(const sp<AMessage> &msg) {
        sp<RefBase> obj;
        CHECK(msg->findObject("buffer", &obj));
        sp<ABuffer> buffer = static_cast<ABuffer *>(obj.get());
        mConn->injectPacket(buffer);
    }
    
    void onStreamTomeOut(TrackInfo *track, size_t trackIndex) {
        ALOGI("MyHandler: dead track #%d", trackIndex);
        if (!mReceivedFirstRTPPacket && (!mTryTCPInterleaving || mSwitchingTCP)) {
            ALOGI("don't kill track #%d which is dead before trying TCP", trackIndex);
            return;
        }
        
        /*********************************************************************************************
         *For some case, the track start time in sdp will larger than the npt1 from play response.
         * We should not eos before the start time in sdp
         *Here we use track->mLastMediaTimeUs as a member to accumulate time, 
         *then we can know whether the real play time larger than the start time or not.
         *track->mLastMediaTimeUs will be set to npt1 when seeking what is we want and is available only undering mNPTMode=ture
         *So if not NPTMode, we eos after track->mTimeoutTrials++ >= trials, will not consider the start play time.
         ********************************************************************************************/
        ALOGI("accu,track->mLastMediaTimeUs=%lld,mNPTMode=%d",track->mLastMediaTimeUs,mNPTMode);
        if((track->mPlayStartTime * 1E6 > track->mLastMediaTimeUs) && mNPTMode){
            track->mLastMediaTimeUs += ARTPSource::kAccessUnitTimeoutUs;
            ALOGI("accu,track->mLastMediaTimeUs=%lld",track->mLastMediaTimeUs);
            if(track->mPlayStartTime* 1E6 > track->mLastMediaTimeUs){
                ALOGI("accu,track->mPlayStartTime=%f,track->mLastMediaTimeUs=%lld",track->mPlayStartTime,track->mLastMediaTimeUs);
                return;
            }
        }   
        
        int64_t tmp = 0;
        mSessionDesc->getDurationUs(&tmp);
        
        int trials = mSeekable ? kTimeoutTrials : kTimeoutLiveTrials;
        bool eos = track->mTimeoutTrials++ >= trials;
        int64_t trackNPT = track->mTimeoutTrials * ARTPSource::kAccessUnitTimeoutUs
            + track->mLastMediaTimeUs;
        
        ALOGI("mLastMediaTimeUs %lld %lld, duration %lld, mode %d, live %d", 
                mLastMediaTimeUs, track->mLastMediaTimeUs, tmp, mNPTMode, !mSeekable);
        
        eos = eos || (mNPTMode && ((mSeekable && trackNPT >= tmp)
                    || (mLastMediaTimeUs - track->mLastMediaTimeUs >= 2*ARTPSource::kAccessUnitTimeoutUs)));
        
        if (eos) {
            postQueueEOS(trackIndex, ERROR_END_OF_STREAM);
        } else {
            ALOGI("we will wait next timeout (%d < %d)", 
                    track->mTimeoutTrials - 1, trials);
        }
        return;
    }

    int32_t onRes2Err(sp<ARTSPResponse> response) {
        // start per stream checker even if failed ..
        mRTPConn->startCheckAlives();
        int32_t result = response->mStatusCode;
        
        if(result == 500 && mPausing) { 
            // ALPS0071224 from GB.TDFD by haizhen - optimize short video
            ALOGI("[rtsp]'play' response->mStatusCode ==500");  
            mIntSeverError = true; 
            // TODO: test if IntServerError means server has complete sending data
            for (size_t i = 0; i < mTracks.size(); ++i) {
                postQueueEOS(i, ERROR_END_OF_STREAM);
            }
            result = OK;
        }

        return result;
    }

    void onSee1Err(int32_t result) {
        mFinalStatus = MappingRTSPError(result);
        ALOGE("pause failed, aborting.");
        // mtk80902: ALPS01041025
        if (!mIsLegacyMode) {
            for (size_t i = 0; i < mTracks.size(); ++i) {
                postQueueSeekDiscontinuity(i);
            }
        }
        (new AMessage('abor', id()))->post();
        
        mSeekPending = false;
        
        if (mNeedSeekNotify) {
            sp<AMessage> msg = mNotify->dup();
            msg->setInt32("what", kWhatSeekDone);
            msg->post();
            mNeedSeekNotify = false;
        }
    }
    
    void onTiou(const sp<AMessage> &msg) {
        //add by haizhen start , for maybe 'tiou' is canceled or a new 'tiou' is post
         int32_t generation = 0;
        msg->findInt32("generation",&generation);
        if(mTiouPending || mTiouGeneration!= generation){
            ALOGI("'tiou' is cancelled or this is a old 'tiou'");
            return;
        }
        //add by haizhen stop
        
        if (mReceivedFirstRTPPacket) {
            ALOGI("SR timeout rtcp = %d", mReceivedFirstRTCPPacket);
            useFirstTimestamp();
            mReceivedFirstRTCPPacket = true;
        } else if (!mTryTCPInterleaving) {
            ALOGW("Never received any data, switching transports.");
        
            mTryTCPInterleaving = true;
            mSwitchingTCP = true;
            mTryingTCPIndex = 0;
            mRTPConn->stopCheckAlives();
        
            sp<AMessage> msg = new AMessage('abor', id());
            msg->setInt32("reconnect", true);
            msg->setInt32("keep-tracks", true);
            msg->post();
        } else {
            //we need to post 'tiou' circlely until Receive the first RTP Packet,
            //then check whether need useFirstTimestamp after turn to TCP mode
            postTryTCPTimeOutCheck();
        }
    }

    void parsePlayResponse_l(Vector<uint32_t> rtpTimes, Vector<uint32_t> rtpSeqs, Vector<int32_t> rtpSockets, float npt1, float npt2) {
        if (mTracks.size() != rtpTimes.size()) {
            // enable SR timestamp
            ALOGI("some track has no rtp-info, using SR ntp");
        } else {
            mNPTMode = true;
            ALOGI("all tracks have rtp-info, using NPT %f as ntp, mMediaAnchorUs %lld",
                    npt1, mMediaAnchorUs);
        
            if((int64_t)(npt1 * 1E6) >= mMediaAnchorUs || mSeekPending) {
                for(int i=0; i<(int)rtpTimes.size(); ++i) {
                    //haizhen--Only if Playe response npt is become bigger or seeking,we allow to timeupdate
                    TrackInfo *track = &mTracks.editItemAt(i);
                    float real_start = ((track->mPlayStartTime) > npt1) ?  track->mPlayStartTime : npt1; 
        
                    ALOGI("timeUpdate real_start=%f,rtpTime[%d]=%d",real_start,i,rtpTimes[i]);
                    //haizhen---for some case track will not start from npt1
                    track->setTimestamp(rtpTimes[i], real_start * 1E6);
                    mRTPConn->setHighestSeqNumber(rtpSockets[i], rtpSeqs[i]);
                }
            } else {
                //show that server is re-playing from begin, which meas server complete sending packets,
                ALOGI("[rtsp](int64_t)(npt1 * 1E6) %lld < mMediaAnchorUs %lld",(int64_t)(npt1 * 1E6),mMediaAnchorUs);
               
                for(int i=0; i<(int)rtpTimes.size(); ++i) {
                    postQueueEOS(i, ERROR_END_OF_STREAM);		       
        		TrackInfo *info = &mTracks.editItemAt(i);
			info->mIsEOSByError = true;
                }
            }
        
            mAllTracksHaveTime = true;
            mNTPAnchorUs = (int64_t)npt1 * 1E6;
            if (npt2 > 0 && npt1 >= npt2) {
                mIntSeverError = true;
                // TODO: test if IntServerError means server has complete sending data
                for(int i=0; i<(int)rtpTimes.size(); ++i) {
                    postQueueEOS(i, ERROR_END_OF_STREAM);
                }
            }
        }
        mMediaAnchorUs = (int64_t)npt1 * 1E6;
        mLastMediaTimeUs = (int64_t)(npt1 * 1E6);

    }

    bool skipMultiStreams(sp<APacketSource> source, size_t index) {
        // skip multiple audio/video streams
        bool skip = false;
        sp<MetaData> meta = source->getFormat();
        const char *mime = "";
        CHECK(meta->findCString(kKeyMIMEType, &mime));
        if (!strncasecmp(mime, "video/", 6)) {
            if (mHaveVideo) {
                ALOGW("Skip multiple video stream. Ignoring track #%d.", 
                        index);
                skip = true;
            } else {
                mHaveVideo = true;
            }
        } else if (!strncasecmp(mime, "audio/", 6)) {
            if (mHaveAudio) {
                ALOGW("Skip multiple audio stream. Ignoring track #%d.", 
                        index);
                skip = true;
            } else {
                mHaveAudio = true;
            }
        } else {
            ALOGW("Unsupported format %s. Ignoring track #%d.", mime, 
                    index);
            skip = true;
        }
        
        if (skip) {
            ALOGI("setupTrack,skip this track");
            sp<AMessage> reply = new AMessage('setu', id());
            reply->setSize("index", index);
            reply->setInt32("result", ERROR_UNSUPPORTED);
            reply->post();
            return true;
        }

        return false;
    }

    void switchingTCP_l(TrackInfo* &info, sp<APacketSource> &source, size_t index) {
        ALOGI("setupTrack,SwithingTCP");
        if (mTracks.size() == 0) {
            ALOGW("setupTrack %d after abor", mTryingTCPIndex);
            sp<AMessage> reply = new AMessage('setu', id());
            reply->setSize("index", index);
            reply->setInt32("result", ERROR_OUT_OF_RANGE);
            reply->setSize("track-index", mTryingTCPIndex);
            reply->post();
            return;
        }
        CHECK(mTryingTCPIndex < (int32_t)mTracks.size());
        info = &mTracks.editItemAt(mTryingTCPIndex++);
        source = info->mPacketSource;
    }
    
    void initInfo(TrackInfo *info, size_t index) {
        info->mTimeoutTrials = 0;
        info->mLastMediaTimeUs = 0;
        info->mFirstAccessUnit = true;
        info->mUseTrackNTP = false;
	 info->mIsEOSByError = false;
        if (mSourceParam != NULL)
            info->mPacketSource->start(mSourceParam.get());

        //add by haizhen start, for the a=range:xx-xx,not star at 0
        info->mPlayStartTime = 0.0;
        AString play_range;
        if(mSessionDesc->findAttribute(index, "a=range", &play_range)){
            float range1,range2; 
            if (ASessionDescription::parseNTPRange(play_range.c_str(), &range1, &range2)){

                info->mPlayStartTime = range1;
                ALOGI("track %d,a=range:%.2f-%.2f",index,range1,range2);
            }
        }
    }

    void  supBitRateAdap(TrackInfo *info, AString &request, size_t &index, AString &trackURL) {
        AString nadu_freq;
        if(!mIsDarwinServer && mSessionDesc->findAttribute(index, "a=3GPP-Adaptation-Support", &nadu_freq))
        {
            char *end;
            info->mNADUFreq = strtoul(nadu_freq.c_str(), &end, 10);
        }
        ALOGI("NADU Frequence =%d",info->mNADUFreq);
        //if Server support Bitrate-Adaptation
        if(info->mNADUFreq > 0){
            //get Queue buffer size and target protect time
            size_t bufQueSize = m_BufQueSize;
            size_t targetTimeMs = m_TargetTime;
            if(mIsLegacyMode){
                sp<APacketSource> packSource =info->mPacketSource;
                bufQueSize = packSource->getBufQueSize();
                targetTimeMs = packSource->getTargetTime(); //count in ms
            }
            if(bufQueSize > 0 && targetTimeMs > 0){
                request.append("3GPP-Adaptation:url=");
                request.append("\"");
                request.append(trackURL);
                request.append("\"");   
                request.append(";size=");
                request.append(bufQueSize);
                request.append(";target-time=");
                request.append(targetTimeMs);
                request.append("\r\n");
                ALOGI("sending 3GPP-Adaptation:%s",request.c_str());
            }       
        }       
    }
    
    int64_t getMediaTimeUs(TrackInfo *track, const sp<ABuffer> accessUnit, uint32_t rtpTime, int64_t &ntpTimeUs) {
        if (((int32_t)(rtpTime - track->mRTPAnchor)) > 0x40000000) {
            ALOGI("force update rtp anchor to %d %lld %d", rtpTime, ntpTimeUs, track->mUseTrackNTP);
            track->setTimestamp(rtpTime, ntpTimeUs, track->mUseTrackNTP);
        }
        
        if (mNTPAnchorUs < 0) {
            mNTPAnchorUs = ntpTimeUs;
        }
        
        if (track->mFirstAccessUnit) {
            int64_t diff = 0;
            track->mFirstAccessUnit = false;
            track->mFirstAccessUnitNTP = ntpTimeUs;
            if (!mNPTMode && !track->mUseTrackNTP) {
                diff = mNTPAnchorUs - track->mFirstAccessUnitNTP;
                diff = diff < 0 ? -diff : diff;
                if (diff > kMaxSRNTPDiff) {
                    ALOGW("NTP big difference %lld vs %lld, enable track NTP mode",
                            mNTPAnchorUs, track->mFirstAccessUnitNTP);
                    track->mUseTrackNTP = true;
                }
            }
            ALOGI("first segment unit ntpTimeUs=0x%016llx rtpTime=%u seq=%d diff=%llx",
                    ntpTimeUs, rtpTime, accessUnit->int32Data(), diff);
        }
        
        ALOGV("times %lld %lld %lld %lld", ntpTimeUs, track->mFirstAccessUnitNTP, mNTPAnchorUs, mMediaAnchorUs);
        ntpTimeUs -= track->mUseTrackNTP ? track->mFirstAccessUnitNTP : mNTPAnchorUs;
        
        int64_t mediaTimeUs = mMediaAnchorUs + ntpTimeUs;
        if (mediaTimeUs < 0) {
            mediaTimeUs = 0;
        }

        return mediaTimeUs;
    }
    
    bool noNeedPostQueueAU(size_t trackIndex, const sp<ABuffer> accessUnit) { 

        //ALPS01515043:final place to check the wrong sequence au 
        TrackInfo *track = &mTracks.editItemAt(trackIndex);
	  if( track->mIsEOSByError){
		return true;
	  }
		
        if(accessUnit->int32Data() < static_cast<int32_t>(track->mFirstSeqNumInSegment)){
            ALOGI("postQueueAccessUnit:dropping stale access-unit (%d < %d)",
                    accessUnit->int32Data(),track->mFirstSeqNumInSegment);
            return true;
        
        }
	 
        return false;
    }

    void flushPackets(size_t trackIndex) {
        //for ALPS01515043:if the mAllTracksHaveTime is false, the Packet will
        // be stored in the Packets list, seek happened, only flush the 
        // anotherPacketSource, not flush this list, after seek, 
        //packet addMediaTimeStamp->add to the anotherPacketSource, error. 
        TrackInfo *trackInfo = &mTracks.editItemAt(trackIndex);
        
        if(!(trackInfo->mPackets.empty())){
            sp<ABuffer> begin = *trackInfo->mPackets.begin();
            ALOGI("flush track %d mPackets Error:begin %d size %d",trackIndex,begin->int32Data(),trackInfo->mPackets.size());
            while (!trackInfo->mPackets.empty()) {
                trackInfo->mPackets.erase(trackInfo->mPackets.begin());
           }
        }
    }

    void setLegacyMode(bool mode) {
        mIsLegacyMode = mode;
    }

    void parseHeaders(const KeyedVector<String8, String8> *headers) {
        char value[PROPERTY_VALUE_MAX];
        if (property_get("media.stagefright.force-rtp-tcp", value, NULL)
                && (!strcmp(value, "1") || !strcasecmp(value, "true"))) {
            mTryTCPInterleaving = true;
        }
        if (headers == NULL)
            return;

        int min = -1, max = -1;
        int port = -1;
        AString host;
        for (size_t i=0; i<headers->size(); ++i) {
            const char* k = headers->keyAt(i).string();
            const char* v = headers->valueAt(i).string();
            if (strlen(v) == 0)
                continue;
            if (!strcmp(k, "MIN-UDP-PORT")) {
                ALOGD ("RTSP Min UDP Port: %s", v);
                min = atoi(v);
                continue;
            }
            if (!strcmp(k, "MAX-UDP-PORT")) {
                ALOGD ("RTSP Max UDP Port: %s", v);
                max = atoi(v);
                continue;
            }
            if (!strcmp(k, "MTK-RTSP-PROXY-HOST")) {
                ALOGD ("RTSP Proxy Host: %s", v);
                host.setTo(v);
                continue;
            }
            if (!strcmp(k, "MTK-RTSP-PROXY-PORT")) {
                ALOGD ("RTSP Proxy Port: %s", v);
                port = atoi(v);
                continue;
            }
            if (!strcmp(k, "MTK-RTSP-RTP-OVER-RTSP")) {
                ALOGD ("RTSP RTP over RTSP: %s", v);
                mTryTCPInterleaving = atoi(v) != 0;
            }
        }

        if (min != -1 || max != -1) {
            if (min >= 0 && max < 65536 && max > min + 5) {
                mMaxUDPPort = max;
                mMinUDPPort = min;
                ALOGD ("Streaming-MIN-UDP-PORT=%d", min);
                ALOGD ("Streaming-MAX-UDP-PORT=%d", max);
            } else {
                ALOGW ("Ignore invalid min/max udp ports: %d/%d", min, max);
            }
        }

        if (!host.empty()) {
            if (port == -1) {
                ALOGI ("use default proxy port 554");
                port = 554;
            }

            if (port < 0 || port >= 65536) {
                ALOGW ("Ignore invalid proxy setting (port: %d)", port);
            } else {
                ALOGD ("Streaming-Proxy=%s", host.c_str());
                ALOGD ("Streaming-Proxy-Port=%d", port);
                mConn->setProxy(host, port);
            }
        }
    }

    // return in ms
    int32_t getServerTimeout() {
        return mKeepAliveTimeoutUs / 1000;
    }

    status_t setSessionDesc(sp<ASessionDescription> desc) {
        mSessionDesc = desc;
        if (!mSessionDesc->isValid())
            return ERROR_MALFORMED;

        if (mSessionDesc->countTracks() == 1u)
            return ERROR_UNSUPPORTED;

        mSeekable = !isLiveStream(mSessionDesc);
        mBaseURL = mSessionURL;
        ALOGD("setSessionDesc:\n mBaseURL (%s)",
                mBaseURL.c_str());
        mControlURL = getControlURL();
        mSkipDescribe = true;
        return OK;
    }


    void stopTCPTrying() {
		ALOGD("stopTCPTrying");
        if (mConn != NULL)
            mConn->stopTCPTrying();
    }

    void exit() {
		ALOGD("exit");
		if (mConn != NULL)
            mConn->exit();
        mExited = true;
    }

    // a sync call to flush packets and pause ourself to receive data
    void preSeek(int64_t timeUs) {
        sp<AMessage> msg = new AMessage('prse', id());
        msg->setInt64("time", timeUs);
        msg->post();
    }

    sp<ASessionDescription> getSessionDesc() {
        return mSessionDesc;
    }

    sp<APacketSource> getPacketSource(size_t index) {
        CHECK_GE(index, 0u);
        CHECK_LT(index, mTracks.size());

        return mTracks.editItemAt(index).mPacketSource;
    }

    ~MyHandler() {
        if (looper() != NULL) {
            looper()->unregisterHandler(id());
        }

        if (mRegistered) {
            if (mRTSPNetLooper != NULL && mConn != NULL) {
                mRTSPNetLooper->unregisterHandler(mConn->id());
            }

            if (mNetLooper != NULL && mRTPConn != NULL)
                mNetLooper->unregisterHandler(mRTPConn->id());
        }
    }

    void setBufQueSize(size_t iBufQueSize){
	    //for bitrate adaptation
	    m_BufQueSize = iBufQueSize; //Whole Buffer queue size 
    }
    void setTargetTimeUs(size_t iTargetTime){	
	    //for bitrate adaptation
	    m_TargetTime = iTargetTime;  // target protected time of buffer queue duration for interrupt-free playback 
    }

    void setAnotherPacketSource(int iMyHandlerTrackIndex, sp<AnotherPacketSource> pAnotherPacketSource){

	    mRTPConn->setAnotherPacketSource(iMyHandlerTrackIndex,pAnotherPacketSource);
    }

// mtk80902: little bit ugly..
    void setPacketSourceParams(const sp<MetaData> &meta) {
	for (size_t i = 0; i < mTracks.size(); ++i) {
	    TrackInfo *track = &mTracks.editItemAt(i);
	    ALOGD("set source params: track %d's source: %p", i, track->mPacketSource.get());
	    if (track->mPacketSource != NULL)
		track->mPacketSource->start(meta.get());
	}
	mSourceParam = meta;
    }

private:
    bool pokeAHole(int rtpSocket, int rtcpSocket, const AString &transport, 
            int rtpPortOurs) {
        struct sockaddr_in addr;
        memset(addr.sin_zero, 0, sizeof(addr.sin_zero));
        addr.sin_family = AF_INET;

        AString source;
        AString server_port;
        if (!GetAttribute(transport.c_str(),
                          "source",
                          &source)) {
            ALOGW("Missing 'source' field in Transport response. Using "
                 "RTSP endpoint address.");

            struct hostent *ent = gethostbyname(mSessionHost.c_str());
            if (ent == NULL) {
                ALOGE("Failed to look up address of session host '%s'",
                     mSessionHost.c_str());

                return false;
            }

            addr.sin_addr.s_addr = *(in_addr_t *)ent->h_addr;
        } else {
            addr.sin_addr.s_addr = inet_addr(source.c_str());
        }

        if (!GetAttribute(transport.c_str(),
                                 "server_port",
                                 &server_port)) {
            ALOGI("Missing 'server_port' field in Transport response.");
            return false;
        }

        int rtpPort, rtcpPort;
        // check whether client_port is modified by NAT
        // DO NOT send packets for this type of NAT
        AString client_port;
        if (GetAttribute(transport.c_str(), "client_port", &client_port)) {
            if (sscanf(client_port.c_str(), "%d-%d", &rtpPort, &rtcpPort) != 2
                    || rtpPort <= 0 || rtpPort > 65535
                    || rtcpPort <=0 || rtcpPort > 65535
                    || rtcpPort != rtpPort + 1
                    || (rtpPort & 1) != 0) {
                return true;
            }
            if (rtpPort != rtpPortOurs) {
                ALOGW("pokeAHole(): NAT has modified our client_port from"
                        " %d to %d", rtpPortOurs, rtpPort);
                return true;
            }
        }
        if (sscanf(server_port.c_str(), "%d-%d", &rtpPort, &rtcpPort) != 2
                || rtpPort <= 0 || rtpPort > 65535
                || rtcpPort <=0 || rtcpPort > 65535
                || rtcpPort != rtpPort + 1) {
            ALOGE("Server picked invalid RTP/RTCP port pair %s,"
                 " RTP port must be even, RTCP port must be one higher.",
                 server_port.c_str());

            return false;
        }

        if (rtpPort & 1) {
            ALOGW("Server picked an odd RTP port, it should've picked an "
                 "even one, we'll let it pass for now, but this may break "
                 "in the future.");
        }

        if (addr.sin_addr.s_addr == INADDR_NONE) {
            return true;
        }

        if (IN_LOOPBACK(ntohl(addr.sin_addr.s_addr))) {
            // No firewalls to traverse on the loopback interface.
            return true;
        }

        // Make up an RR/SDES RTCP packet.
        sp<ABuffer> buf = new ABuffer(65536);
        buf->setRange(0, 0);
        addRR(buf);
        addSDES(rtpSocket, buf);

        bool success = true;
        success = success && doPokeAHole(rtpSocket, addr, rtpPort, buf);
        success = success && doPokeAHole(rtcpSocket, addr, rtcpPort, buf);
        if (!source.empty()) {
            struct hostent *ent = gethostbyname(mSessionHost.c_str());
            if (ent == NULL) {
                ALOGE("Failed to look up address of session host '%s'",
                     mSessionHost.c_str());

                return false;
            }

            if (addr.sin_addr.s_addr != *(in_addr_t *)ent->h_addr) {
                addr.sin_addr.s_addr = *(in_addr_t *)ent->h_addr;
                success = success && doPokeAHole(rtpSocket, addr, rtpPort, buf);
                success = success && doPokeAHole(rtcpSocket, addr, rtcpPort, buf);
            }
        }
        return success;
    }

    bool doPokeAHole(int socket, struct sockaddr_in addr, int port, const sp<ABuffer>& buf) {
        addr.sin_port = htons(port);
        for(int i = 0; i < kPokeAttempts; i++) {
            ssize_t n = sendto(
                    socket, buf->data(), buf->size(), 0,
                    (const sockaddr *)&addr, sizeof(addr));
            if (n == (ssize_t)buf->size())
                return true;
        }
        ALOGE("failed to poke a hole for port %d", port);
        return false;
    }

	void postTryTCPTimeOutCheck(){
		 if (kSRTimeoutUs != 0) {				
            sp<AMessage> timeout = new AMessage('tiou', id());
		mTiouPending = false; //add by haizhen
		mTiouGeneration++;
		timeout->setInt32("generation",mTiouGeneration);//add by haizhen
            timeout->post(kSRTimeoutUs);	
        }	
	}
	
    void checkBitRateAdap() {
        //for Bitrate-Adaptation
#ifdef MTK_RTSP_BITRATE_ADAPTATION_SUPPORT
        mSupBitRateAdap = true;
#else
        mSupBitRateAdap = false;
#endif
        char value[PROPERTY_VALUE_MAX];
        if(mSupBitRateAdap){
            if (property_get("media.stagefright.rtsp-adapbr", value, NULL)
                    && (!strcmp(value, "0") || !strcasecmp(value, "false"))) {
                mSupBitRateAdap = false;
            }
        } else {
            if (property_get("media.stagefright.rtsp-adapbr", value, NULL)
                    && (!strcmp(value, "1") || !strcasecmp(value, "true"))) {
                mSupBitRateAdap = true;
            }
        }

        ALOGI("bitrate adaptation is %s", mSupBitRateAdap ? "enabled" : "disabled");
    }

    void _play(int64_t timeUs, const sp<AMessage>& reply) {
        AString request = "PLAY ";
        request.append(mControlURL);
        request.append(" RTSP/1.0\r\n");

        request.append("Session: ");
        request.append(mSessionID);
        request.append("\r\n");

        if (timeUs >= 0) {
            mLastPlayTimeUs = timeUs;
            request.append(
                    StringPrintf(
                        "Range: npt=%lld-\r\n", timeUs / 1000000ll));
        }

        request.append("\r\n");

        mConn->sendRequest(request.c_str(), reply);
    }

    status_t _preSeek(int64_t timeUs, bool notify) {
        sp<AMessage> doneMsg = mNotify->dup();
        doneMsg->setInt32("what", kWhatPreSeekDone);

		if(mIntSeverError){
            ALOGW("seeking after internal server error happens.");
            if (notify) {
                doneMsg->setInt32("result", INVALID_OPERATION);
                doneMsg->post();
            }
            return INVALID_OPERATION;
        }
		
        if (mSeekPending) {
            ALOGW("seeking already exists.");
            if (notify) {
                doneMsg->setInt32("result", ALREADY_EXISTS);
                doneMsg->post();
            }
            return ALREADY_EXISTS;
        }

        // seek during swithing transport is hard to handle
        if (mSwitchingTCP) {
            ALOGW("seeking when switching tcp");
            if (notify) {
                doneMsg->setInt32("result", INVALID_OPERATION);
                doneMsg->post();
            }
            return INVALID_OPERATION;
        }

        if (!mSeekable) {
            ALOGW("This is a live stream, ignoring seek request.");
            if (notify) {
                doneMsg->setInt32("result", INVALID_OPERATION);
                doneMsg->post();
            }
            return INVALID_OPERATION;
        }

        mSeekPending = true;

        // Disable the access unit timeout until we resumed
        // playback again.
        mCheckPending = true;
        ++mCheckGeneration;

        //add by haizhen, disable 'tiou' until we resumed
        mTiouPending = true;
        mLastSeekTimeTime = timeUs;
        // flush here instead of when SEEKing completed
        for (size_t i = 0; i < mTracks.size(); ++i) {
            sp<APacketSource> s = mTracks.editItemAt(i).mPacketSource;
            if (s->isAtEOS()) {
                // reactivate NAT in case we're done for a long time
                TrackInfo *track = &mTracks.editItemAt(i);
                if (!track->mUsingInterleavedTCP) {
                    pokeAHole(track->mRTPSocket,
                            track->mRTCPSocket,
                            track->mTransport,
                            track->mRTPPort);
                }
            }
            postQueueSeekDiscontinuity(i);
        }
        mInitNPT = timeUs;
        mReceivedFirstRTCPPacket = false;
        mRTPConn->stopCheckAlives();

        if (notify) {
            doneMsg->setInt32("result", OK);
            doneMsg->post();
        }
        return OK;
    }

    status_t _seek(int64_t timeUs) {
        int64_t tmp;
        mSessionDesc->getDurationUs(&tmp);
        if (mSeekable && timeUs >= tmp) {
            ALOGI("seek to the end, eos right now");
            if (mNeedSeekNotify) {
                // dont forget flush source
                if (!mIsLegacyMode) {
                    for (size_t i = 0; i < mTracks.size(); ++i) {
                        postQueueSeekDiscontinuity(i);
                    }
                }
                sp<AMessage> doneMsg = mNotify->dup();
                doneMsg->setInt32("what", kWhatSeekDone);
                doneMsg->post(); 
                mNeedSeekNotify = false;
            }

            // mtk80902: ALPS01258456
            // Source still receiving data before EOS, progress jump
            // ERROR_EOS_QUITNOW let source quit before data drained
            int i;
            for(i = 0; i < (int)mTracks.size(); ++i) {
                postQueueEOS(i, ERROR_EOS_QUITNOW);
            }
            mSeekPending = false;
            return OK;
        }

        if (!mPlaySent) {
            if (mServerInfo.find("MDN_HWPSS") != -1) {
                ALOGI("direct PLAY for some server");
                sp<AMessage> reply = new AMessage('see1', id());
                reply->setInt64("time", timeUs);
                reply->setInt32("result", OK);
                reply->post();
                return OK;
            } else {
                mPlaySent = true;
                ALOGI("send PLAY for first seek");
                sp<AMessage> reply = new AMessage('nopl', id());
                reply->setInt64("time", timeUs);
                _play(0, reply);
            }
        }
        
        sp<AMessage> reply = new AMessage('see1', id());
        reply->setInt64("time", timeUs);

        if (mPausing) {
            // PAUSE already sent
            ALOGI("Pause already sent");
            reply->setInt32("result", OK);
            reply->post();
            return OK;
        }
        AString request = "PAUSE ";
        request.append(mControlURL);
        request.append(" RTSP/1.0\r\n");

        request.append("Session: ");
        request.append(mSessionID);
        request.append("\r\n");

        request.append("\r\n");

        mConn->sendRequest(request.c_str(), reply);
        return OK;
    }

    void resetTimestamps() {
        mNTPAnchorUs = -1;
        mPlayRespPending = true;
        mLastPlayTimeUs = mLastMediaTimeUs >= 0 ? mLastMediaTimeUs : 0;
        for (size_t i = 0; i < mTracks.size(); ++i) {
            TrackInfo *info = &mTracks.editItemAt(i);
            info->resetTimestamp();
        }
    }

    void useFirstTimestamp() {
        for (size_t i = 0; i < mTracks.size(); ++i) {
            TrackInfo *info = &mTracks.editItemAt(i);
            onTimeUpdate(i, 0, 0, true);
        }
    }

#endif
};

}  // namespace android

#endif  // MY_HANDLER_H_
