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

#ifndef A_RTP_CONNECTION_H_

#define A_RTP_CONNECTION_H_

#include <media/stagefright/foundation/AHandler.h>
#include <utils/List.h>

namespace android {

struct ABuffer;
struct ARTPSource;
struct ASessionDescription;

#ifdef MTK_AOSP_ENHANCEMENT
struct APacketSource;
struct AnotherPacketSource;

struct ARTPConnectionParam {
    int32_t mSSRC;
    sp<APacketSource> mAPacketSource;
    size_t mNaduFreq;
};
#endif

struct ARTPConnection : public AHandler {
    enum Flags {
#ifdef MTK_AOSP_ENHANCEMENT
        kFakeTimestamps      = 1,
#endif // #ifdef MTK_AOSP_ENHANCEMENT
        kRegularlyRequestFIR = 2,
    };

    ARTPConnection(uint32_t flags = 0);

    void addStream(
            int rtpSocket, int rtcpSocket,
            const sp<ASessionDescription> &sessionDesc, size_t index,
            const sp<AMessage> &notify,
#ifdef MTK_AOSP_ENHANCEMENT 
            bool injected, ARTPConnectionParam* pbitRateAdapParam);
#else
            bool injected);
#endif // #ifdef MTK_AOSP_ENHANCEMENT

    void removeStream(int rtpSocket, int rtcpSocket);

    void injectPacket(int index, const sp<ABuffer> &buffer);

    // Creates a pair of UDP datagram sockets bound to adjacent ports
    // (the rtpSocket is bound to an even port, the rtcpSocket to the
    // next higher port).
    static void MakePortPair(
#ifdef MTK_AOSP_ENHANCEMENT 
            int *rtpSocket, int *rtcpSocket, unsigned *rtpPort,
            int min = 0, int max = 65535);
#else
            int *rtpSocket, int *rtcpSocket, unsigned *rtpPort);
#endif // #ifdef MTK_AOSP_ENHANCEMENT

protected:
    virtual ~ARTPConnection();
    virtual void onMessageReceived(const sp<AMessage> &msg);

private:
    enum {
        kWhatAddStream,
        kWhatRemoveStream,
        kWhatPollStreams,
        kWhatInjectPacket,
#ifdef MTK_AOSP_ENHANCEMENT 
        kWhatInjectPollStreams,
        kWhatStartCheckAlives,
        kWhatStopCheckAlives,
        kWhatCheckAlive,
        kWhatSeqUpdate,
#endif // #ifdef MTK_AOSP_ENHANCEMENT
    };

    static const int64_t kSelectTimeoutUs;

    uint32_t mFlags;

    struct StreamInfo;
    List<StreamInfo> mStreams;

    bool mPollEventPending;
    int64_t mLastReceiverReportTimeUs;

    void onAddStream(const sp<AMessage> &msg);
    void onRemoveStream(const sp<AMessage> &msg);
    void onPollStreams();
    void onInjectPacket(const sp<AMessage> &msg);
    void onSendReceiverReports();

    status_t receive(StreamInfo *info, bool receiveRTP);

    status_t parseRTP(StreamInfo *info, const sp<ABuffer> &buffer);
    status_t parseRTCP(StreamInfo *info, const sp<ABuffer> &buffer);
    status_t parseSR(StreamInfo *info, const uint8_t *data, size_t size);
    status_t parseBYE(StreamInfo *info, const uint8_t *data, size_t size);

    sp<ARTPSource> findSource(StreamInfo *info, uint32_t id);

    void postPollEvent();

    DISALLOW_EVIL_CONSTRUCTORS(ARTPConnection);
#ifdef MTK_AOSP_ENHANCEMENT 
public:
    void startCheckAlives();
    void stopCheckAlives();
    void setHighestSeqNumber(int socket, uint32_t rtpSeq);
	void setAnotherPacketSource(int iMyHandlerTrackIndex, sp<AnotherPacketSource> pAnotherPacketSource);
	typedef KeyedVector<int, sp<AnotherPacketSource> > tAnotherPacketSourceMap;
	tAnotherPacketSourceMap mAnotherPacketSourceMap;
private:
    void setConnParam(ARTPConnectionParam* connParam, sp<AMessage> &msg);
    void setStreamInfo(const sp<AMessage> &msg, StreamInfo *info);
    void onRecvNewSsrc(StreamInfo *info, uint32_t srcId, sp<ARTPSource> source);
    int  getReadSize(StreamInfo *s, bool receiveRTP);
    void postRecvReport(StreamInfo *s, sp<ABuffer> &buffer);
    void addNADUApp(sp<ARTPSource> source, StreamInfo *s, sp<ABuffer> buffer);
    bool needSendNADU(StreamInfo *s);

    void sendRR();
    void onStartCheckAlives();
    void onStopCheckAlives();
    void onCheckAlive(const sp<AMessage> &msg);
    void onSetHighestSeqNumber(const sp<AMessage> &msg);
    void postInjectEvent();
    void onPostInjectEvent();

#endif // #ifdef MTK_AOSP_ENHANCEMENT
};

}  // namespace android

#endif  // A_RTP_CONNECTION_H_
