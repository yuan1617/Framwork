/* Copyright Statement:
 *
 * This software/firmware and related documentation ("MediaTek Software") are
 * protected under relevant copyright laws. The information contained herein
 * is confidential and proprietary to MediaTek Inc. and/or its licensors.
 * Without the prior written permission of MediaTek inc. and/or its licensors,
 * any reproduction, modification, use or disclosure of MediaTek Software,
 * and information contained herein, in whole or in part, shall be strictly prohibited.
 */
/* MediaTek Inc. (C) 2013. All rights reserved.
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
 * Copyright (C) 2007 The Android Open Source Project
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

#define LOG_TAG "AudioMTKTimeStretch"

extern "C" {
#include "AudioMTKTimeStretch.h"
}
#include <cutils/xlog.h>
#ifdef MTK_AUDIO
#include "AudioUtilmtk.h"
#endif
#define DEBUG_AUDIO_PCM
#ifdef MTK_AUDIO
#ifdef DEBUG_AUDIO_PCM
    static   const char * gaf_timestretch_in_pcm = "/sdcard/mtklog/audio_dump/af_timestretch_in_pcm.pcm";
    static   const char * gaf_timestretch_out_pcm = "/sdcard/mtklog/audio_dump/af_timestretch_out_pcm.pcm";

    static   const char * gaf_timestretch_out_propty = "af.timestretch.pcm";
#endif
#endif
namespace android {

AudioMTKTimeStretch::AudioMTKTimeStretch(int framecount)
{
	mpHandle = NULL;
	mInternalBufferSize = 0;
	mTempBufferSize = 0;
	mInBuf_smpl_cnt = 0;
	mInputBufferSize = framecount;
	mInputBuffer = new short[framecount*2];//  *2 for stereo
	mInBuf_smpl_cnt = 0;
        mFraction_remain = 0;        
        mProcessState = TS_STATE_INIT;
}
AudioMTKTimeStretch::AudioMTKTimeStretch(int ratio, int inChannelCount, int sampleRate,int framecount)
{
	mpHandle = NULL;
	mInternalBufferSize = 0;
	mTempBufferSize = 0;
	mInBuf_smpl_cnt = 0;
	mInitParam.SampleRate = sampleRate;
	mInitParam.StereoFlag = inChannelCount-1; 
	mInitParam.TD_FD = 1;
	mBTS_RTParam.TS_Ratio= ratio; // initially no change. 
	mFraction_remain = 0;
         mProcessState = TS_STATE_INIT;
         mRatio_Cache = 0;
}
AudioMTKTimeStretch::~AudioMTKTimeStretch()
{
	
	BTS_Close(mpHandle, 1);

	if(NULL != mWorkingBuffer) {
		delete[] mWorkingBuffer;
		mWorkingBuffer = NULL;
	}
	if(NULL != mTempBuffer) {
		delete[] mTempBuffer;
		mTempBuffer = NULL;
	}
	if(NULL != mInputBuffer) {
		delete[] mInputBuffer;
		mInputBuffer = NULL;
	}
	 mpHandle = NULL;
	 	
}

void AudioMTKTimeStretch::reset()
{
	SXLOGD("reset");	
	//mBTS_RTParam.TS_Ratio = 100;
	// reset buffer
	if(mWorkingBuffer != NULL);
	{memset(mWorkingBuffer, 0 ,mInternalBufferSize);
		}
	if(mTempBufferSize != NULL)
		memset((void*)mTempBuffer, 0 ,mTempBufferSize);
	// reset internal input buffer count.
	mInBuf_smpl_cnt = 0;
	// keep the same init parameter
	BTS_Open(&mpHandle,
						   mWorkingBuffer, 
						   sizeof(BTS_InitParam),
						   (void*)&mInitParam,
						   0);
	 
	 BTS_SetParameters(mpHandle, sizeof(BTS_RuntimeParam),(void*)&mBTS_RTParam);
}

int AudioMTKTimeStretch::init(int SampleRate, int inChannelCount, int ratio)
{
  
	int ret;
	mInitParam.SampleRate = SampleRate;
	mInitParam.StereoFlag = inChannelCount-1; 
	mInitParam.TD_FD = 1;
	BTS_GetBufferSize(&mInternalBufferSize, &mTempBufferSize);
	//SXLOGD("InternalBufferSize %d, TempBufferSize %d",mInternalBufferSize, mTempBufferSize);

	//mWorkingBuffer = (char*)((int *)malloc(InternalBufferSize));
	//mTempBuffer = (int *)malloc(TempBufferSize);
	mWorkingBuffer = (char*) new int[mInternalBufferSize];
	mTempBuffer = new int[mTempBufferSize];
	SXLOGV("SampleRate %d", SampleRate);
	SXLOGV("inChannelCount %d", inChannelCount);
	SXLOGV("ratio %d",ratio);
         mRatio_Cache = ratio;
	mBTS_RTParam.TS_Ratio= 100; // initially no change. 
	ret= BTS_Open(&mpHandle,
						mWorkingBuffer, 
						sizeof(BTS_InitParam),
						(void*)&mInitParam,
						0);
	if (ret!=0){
		SXLOGE("AudioMTKTimeStretch init, OPEN Fail");
		return -1;}
        mRampFlag = RAMP_UP;
	return 0;
}
int AudioMTKTimeStretch::InternalBufferSpace()
{
    //  return internal buffer space, in case buffer overflow.
    return (mInputBufferSize) -(mInBuf_smpl_cnt>>1);
}

int AudioMTKTimeStretch::InternalBufferFrameCount()
{
    //  return internal buffer space, in case buffer overflow.
    return (mInBuf_smpl_cnt>>1);
}


int AudioMTKTimeStretch::process(short* InputBuffer, short* OutputBuffer ,int* inputByte, int* outputByte )
{
	int process_smple_cnt,in_smpl_cnt,OutputCount ;
	short*  InputBufferPtr = InputBuffer;		
	short*  OutputBufferPtr = OutputBuffer;	
	int OutSampleCount = *outputByte >> 1;
	int OutTotalSampleCount = 0;
	int OutBuf_smpl = OutSampleCount; // total buffer size
	process_smple_cnt = *inputByte >> 1;//(1 + mInitParam.StereoFlag);
	in_smpl_cnt = process_smple_cnt;
    
        ALOGD("process  mProcessState %d mBTS_RTParam.TS_Ratio  %d",mProcessState ,mBTS_RTParam.TS_Ratio );
        if(mBTS_RTParam.TS_Ratio >1600)
        {
            mBTS_RTParam.TS_Ratio = 1600;
        }
        switch(mProcessState)
        {
       // case TS_STATE_INIT:
       //     return 0;
        case  TS_STATE_RAMP_DOWN_FROM_TS:
            break;
        case TS_STATE_INIT:
        case    TS_STATE_RAMP_DOWN_FROM_NORMAL:
                {
                    // first frame do not do stretch, but do ramp down
                    mRampFlag = RAMP_DOWN;
                   // *outputByte =  * inputByte;
                     *outputByte =  *outputByte>* inputByte ? * inputByte : *outputByte;
                    * inputByte =  * inputByte -  *outputByte;
                    memcpy(OutputBuffer, InputBuffer,  *outputByte);
                    volumeRamp(OutputBuffer, *outputByte>>1, OutputBuffer, &mRampFlag);
                    ALOGD("memcpy copy in to out for ramp down  *inputByte %d,*outputByte %d", *inputByte,*outputByte);
                    // set ratio here, new ratio will take effect next frame
                    if(mRatio_Cache > 400)
                    {
                        mProcessState = TS_STATE_SILENCE;                        
                    }
                    else{
                        mProcessState  = TS_STATE_RAMP_UP;
                    }
                    mBTS_RTParam.TS_Ratio = mRatio_Cache;
                }
                return 0;
            //break;
        case TS_STATE_SILENCE:
                { 
                    ALOGD("TS_STATE_SILENCE *inputByte %d,*outputByte %d,mFraction_remain %d", *inputByte,*outputByte,mFraction_remain);
                    // memset output frame, output size is calculated according to input size.                    
                    int ratio = mBTS_RTParam.TS_Ratio /100;
                    *outputByte  -= mFraction_remain;
                    *outputByte =  *outputByte < ((* inputByte) * mBTS_RTParam.TS_Ratio/100) ? (*outputByte) : ((* inputByte) * mBTS_RTParam.TS_Ratio/100);                
                    memset(OutputBuffer, 0, *outputByte + mFraction_remain);
                    mFraction_remain = (*outputByte % ratio !=0)?  (ratio -(*outputByte % ratio)):0;
                    *inputByte = *inputByte - ((*outputByte + mFraction_remain )/ratio);
                    ALOGD("after memset 0, *inputByte %d, *outputByte %d,mFraction_remain %d", *inputByte,*outputByte,mFraction_remain);
                    //mRampFlag = RAMP_NORMAL;
                }
        case   TS_STATE_RAMP_UP:
            
            SXLOGV("BTS_SetParameters %d", mBTS_RTParam.TS_Ratio);
            BTS_SetParameters(mpHandle,(const int) sizeof(BTS_RuntimeParam),(const void*)&mBTS_RTParam);
            if(mBTS_RTParam.TS_Ratio == 100)
            {
                 mRampFlag = RAMP_UP;
                  *outputByte =  *outputByte>* inputByte ? * inputByte : *outputByte;
                 * inputByte =  * inputByte -  *outputByte;
                 memcpy(OutputBuffer, InputBuffer,  *outputByte);
                 volumeRamp(OutputBuffer, *outputByte>>1, OutputBuffer, &mRampFlag);
                 ALOGD("memcpy copy in to out for ramp down  *inputByte %d,*outputByte %d", *inputByte,*outputByte);
                 mProcessState  = TS_STATE_NORMAL;
                    return 0;
            }
            break;
            default: 
                break;
        }
        if(mBTS_RTParam.TS_Ratio <= 400 ){
	int X_WinSize = 256;
	if(mInitParam.SampleRate<=24000)
		X_WinSize >>= 1;
	if(mInitParam.SampleRate <=12000)
		X_WinSize >>= 1;
	//SXLOGV("* input Buffer 0x%x, *OutputBuffer 0x%x , * inputByte %d, outputByte %d",InputBuffer,OutputBuffer,* inputByte ,*outputByte);
	//if(in_smpl_cnt < 512 || mInBuf_smpl_cnt !=0)
	{
		short* inptr = mInputBuffer + mInBuf_smpl_cnt;
		memcpy(inptr,InputBuffer,  *inputByte );
		mInBuf_smpl_cnt += in_smpl_cnt;
		//SXLOGV("add input sample %d, mInBuf_smpl_cnt %d", in_smpl_cnt, mInBuf_smpl_cnt);
                    if(mInBuf_smpl_cnt >mInputBufferSize*2)
                    {
                        SXLOGD("error, buffer over flow !!!!!!!! mInBuf_smpl_cnt %d", mInBuf_smpl_cnt);
                    }
		in_smpl_cnt = 0;
		process_smple_cnt = 0;
	}
	if(mInBuf_smpl_cnt >= X_WinSize)
	{
		//SXLOGV("use internal buffer sample count %d", mInBuf_smpl_cnt);
		in_smpl_cnt = mInBuf_smpl_cnt;
		InputBufferPtr = mInputBuffer;
		process_smple_cnt = in_smpl_cnt;
	}
	while(in_smpl_cnt >= X_WinSize && OutSampleCount >0)
	{
	//SXLOGV("in_smpl_cnt %d", in_smpl_cnt);
	//SXLOGV("OutSampleCount %d", OutSampleCount);
		  /* ========================================================= */
		  /* Process one block of data								   */
		  /* ========================================================= */
		  //OutputLength =0x240; //0x1c8;
		  
		  {
			 BTS_Process(mpHandle,
									 (char*)mTempBuffer,
									 (const short*)InputBufferPtr,
									 &process_smple_cnt,
									 OutputBufferPtr,
									 &OutSampleCount);
		  }
		  /*
		  else
		  {
			 memcpy(OutputBuffer, InputBuffer, process_smple_cnt*sizeof(short));
		  }
		  */
		  

			//  SXLOGV("process_smple_cnt %d, byte %d\n",process_smple_cnt,(process_smple_cnt<< 1));

		  InputBufferPtr = InputBufferPtr + (process_smple_cnt); //advance input buffer
		  in_smpl_cnt -= process_smple_cnt; // process_smple_cnt is consummed sample count.
		  process_smple_cnt = in_smpl_cnt; // Next Round inbuffer size.
		  OutputBufferPtr += OutSampleCount; // advance out buffer
		  OutTotalSampleCount += OutSampleCount; // accumulate out sample count
		  OutSampleCount = OutBuf_smpl - OutTotalSampleCount; // next round out buffer size
		  //SXLOGV("%10d sample generated!\n",OutTotalSampleCount);
	   }
	
	if(mInBuf_smpl_cnt >= X_WinSize)
	{
	
#ifdef MTK_AUDIO
#ifdef DEBUG_AUDIO_PCM
        const int SIZE = 256;
        char fileName[SIZE];
        sprintf(fileName,"%s_%p.pcm",gaf_timestretch_in_pcm,this);
        if(OutTotalSampleCount !=0)
        {
        short* dump_ptr;
        int dump_sz;
        dump_ptr = mInputBuffer;
        dump_sz = mInBuf_smpl_cnt - in_smpl_cnt;
        AudioDump::dump(fileName,dump_ptr,(dump_sz << 1),gaf_timestretch_out_propty);
        //SXLOGD(" process dump addr %x, size %d", InputBuffer,(*inputByte) -(in_smpl_cnt<< 1 ) );
        sprintf(fileName,"%s_%p.pcm",gaf_timestretch_out_pcm,this);
        AudioDump::dump(fileName,OutputBuffer,OutTotalSampleCount<<1 ,gaf_timestretch_out_propty);
        }
#endif
#endif
	    // re-arrange internal input buffer
		if(in_smpl_cnt != 0)

		{
			short* inptr = mInputBuffer + (mInBuf_smpl_cnt - in_smpl_cnt);
			memcpy(mInputBuffer, inptr,  (in_smpl_cnt<<1) );
		}
		mInBuf_smpl_cnt = in_smpl_cnt;
		//SXLOGV("update internal buffer sample count %d", mInBuf_smpl_cnt );
	}
	// return unused input sample count.						
	*inputByte = 0;//mInBuf_smpl_cnt<< 1;
	*outputByte = OutTotalSampleCount <<1;
        // when input sample count is not enough, there will sometime 0 OutTotalSampleCount
        // no ramp is proceed and no status change.
        if( OutTotalSampleCount !=0){
            if(mProcessState == TS_STATE_RAMP_DOWN_FROM_TS)
            {
                mRampFlag = RAMP_DOWN;
                volumeRamp(OutputBuffer, OutTotalSampleCount, OutputBuffer, &mRampFlag);
                if(mRatio_Cache > 400)
                {
                    mProcessState = TS_STATE_SILENCE;                        
                }
                else{
                    mProcessState  = TS_STATE_RAMP_UP;
                }
                mBTS_RTParam.TS_Ratio = mRatio_Cache;
            }
            else if(mProcessState == TS_STATE_RAMP_UP)
            {
                mRampFlag = RAMP_UP;
                SXLOGV("RAMP_UP OutTotalSampleCount %d", OutTotalSampleCount);
                volumeRamp(OutputBuffer, OutTotalSampleCount, OutputBuffer, &mRampFlag);
                mProcessState = TS_STATE_NORMAL;
            }
         }
         }
	return 0;

}
void AudioMTKTimeStretch ::volumeRamp(short* out, int frameCount, short* temp, int* UpDown)
{
    int32_t vl ;
    int32_t vlInc ;
    vlInc = 65536/frameCount;
    vlInc += 1;
    if(*UpDown == RAMP_UP)
    {// ramp up
        vl = 0;
    }
    else if(*UpDown == RAMP_DOWN)
    { // ramp down
         vl = 65536;
         vlInc = -vlInc;
         
    }
    else{
            return;
        }
    SXLOGV("frameCount %d, ramp %d , vl %d, vlInc %d",frameCount, *UpDown, vl, vlInc );    
    // ramp volume
        do {
                
                vl += vlInc;
                if(vl<0){
                    vl = 0;}
                    *out++ = (int16_t) (((int32_t)vl * (*temp++)>> 16));                        
                    //*out++ = (int16_t) (((int32_t)vl * (*temp++)>> 16));
                    frameCount --;
        } while (frameCount);
}

int AudioMTKTimeStretch::setParameters(
							   int *ratio)
{
	SXLOGV("bf setParameters %d", *ratio);
	if(mpHandle ==NULL)
		return -1;
	if(mBTS_RTParam.TS_Ratio == *ratio)
	{
		SXLOGV("af setParameters %d", *ratio);	
		return 0;}
        if(mBTS_RTParam.TS_Ratio != *ratio)
        {
            if(*ratio ==1600)// set ramp down            
            {  
                //reset();
            }
        }
        SXLOGV("setParameters mProcessState %d",mProcessState );
        //put ratio in cache first then set rate after ramp
        if(mProcessState == TS_STATE_INIT||mProcessState ==TS_STATE_NORMAL  )        
        {
            if(mRatio_Cache >100 && (mRatio_Cache <= 400))
            {
                mProcessState = TS_STATE_RAMP_DOWN_FROM_TS;}
                else{
                mProcessState = TS_STATE_RAMP_DOWN_FROM_NORMAL;}
            }
         else if(mProcessState == TS_STATE_SILENCE){
            mRatio_Cache = *ratio;
            mBTS_RTParam.TS_Ratio = mRatio_Cache;
            mProcessState = TS_STATE_RAMP_UP;
            }
         else if(mProcessState != TS_STATE_RAMP_UP ){
            mProcessState = TS_STATE_RAMP_DOWN_FROM_TS;
            }
        mRatio_Cache = *ratio;
        #if 0
	mBTS_RTParam.TS_Ratio = *ratio;
	BTS_SetParameters(mpHandle,(const int) sizeof(BTS_RuntimeParam),(const void*)&mBTS_RTParam);
	mBTS_RTParam.TS_Ratio = mBTS_RTParam.TS_Ratio;
	#endif
	SXLOGV("af setParameters %d", *ratio);	
	return 0;
}


}

