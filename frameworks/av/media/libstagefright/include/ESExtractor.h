/*
 * Copyright (C) 2011 The Android Open Source Project
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

#ifndef ES_EXTRACTOR_H_

#define ES_EXTRACTOR_H_

#include <media/stagefright/foundation/ABase.h>
#include <media/stagefright/MediaExtractor.h>
#include <utils/threads.h>
#include <utils/KeyedVector.h>

namespace android {

struct ABuffer;
struct AMessage;
struct Track;
struct String8;

struct ESExtractor : public MediaExtractor {
    ESExtractor(const sp<DataSource> &source);

    virtual size_t countTracks();
    virtual sp<MediaSource> getTrack(size_t index);
    virtual sp<MetaData> getTrackMetaData(size_t index, uint32_t flags);
    virtual sp<MetaData> getMetaData();

    virtual uint32_t flags() const;
	bool    bisPlayable;
    virtual ~ESExtractor();

private:
    struct Track;
    struct WrappedTrack;

    mutable Mutex mLock;
    sp<DataSource> mDataSource;

    off64_t mOffset;
    status_t mFinalResult;
    sp<ABuffer> mBuffer;
    bool mScanning;
    bool mSeeking;
    off64_t mFileSize;
    bool mhasVTrack;
    bool mhasATrack;
    bool mNeedDequeuePES;
    sp<Track> mTrack;//For ES File,only have one track(audio/video)

    void setDequeueState(bool needDequeuePES);
    bool getDequeueState();
    void init();
    bool getSeeking();
    void signalDiscontinuity(const bool bKeepFormat = false);

    status_t feedMore();
    status_t dequeueES();

    DISALLOW_EVIL_CONSTRUCTORS(ESExtractor);
};

bool SniffES(
        const sp<DataSource> &source, String8 *mimeType, float *confidence,
        sp<AMessage> *);

status_t getNextNALUnit4(
        const uint8_t **_data, size_t *_size,
        const uint8_t **nalStart, size_t *nalSize,
        bool startCodeFollows = false);
}  // namespace android

#endif  // MPEG2_ES_EXTRACTOR_H_

