
#ifndef MTK_BITSTREAM_SOURCE_H_
#define MTK_BITSTREAM_SOURCE_H_

#include <utils/Mutex.h>
#include <utils/RefBase.h>
#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MediaSource.h>
#include <venc_drv_if_public.h> 
#include <val_types_public.h> 

namespace android {

/******************************************************************************
*
*******************************************************************************/
class MtkBSSource : public virtual MediaSource
{
public:
	
    /**
     * Factory method to create a new MtkBSSource 
     * @return NULL on error.
     */
    static sp<MediaSource> Create(const sp<MediaSource> &source, const sp<MetaData> &meta);
	MtkBSSource(const sp<MediaSource> &source, const sp<MetaData> &meta);
	virtual ~MtkBSSource();

/******************************************************************************
*  Operations in base class MediaSource
*******************************************************************************/
public:
	virtual status_t 		start(MetaData *params = NULL);
	virtual status_t 		stop();
	virtual status_t 		read(MediaBuffer **buffer, const ReadOptions *options = NULL);
	virtual sp<MetaData> 	getFormat();

/******************************************************************************
*  Operations in class MtkBSSource
*******************************************************************************/
public:
private:
	status_t setEncParam(const sp<MetaData> &meta);
	status_t dropFrame(MediaBuffer **buffer);  // drop frame before 2nd I Frame
	status_t passMetadatatoBuffer(MediaBuffer **buffer);//pass Metadata to MediaBuffer
    
	MtkBSSource(const MtkBSSource &);
	MtkBSSource &operator = (const MtkBSSource &);
	
private:
    sp<MediaSource> 	mSource;
	Mutex 				mLock;
    bool 				mStarted;
	bool				mCodecConfigReceived;
	bool				mNeedDropFrame;
	sp<MetaData> 		mOutputFormat;
public:
	VAL_HANDLE_T        MetaHandleList;
	VAL_BufInfo         rBufInfo;
};

};

#endif // MTK_BITSTREAM_SOURCE_H_


