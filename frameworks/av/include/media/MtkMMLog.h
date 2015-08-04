/* Copyright Statement:
 *
 * This software/firmware and related documentation ("MediaTek Software") are
 * protected under relevant copyright laws. The information contained herein is
 * confidential and proprietary to MediaTek Inc. and/or its licensors. Without
 * the prior written permission of MediaTek inc. and/or its licensors, any
 * reproduction, modification, use or disclosure of MediaTek Software, and
 * information contained herein, in whole or in part, shall be strictly
 * prohibited.
 * 
 * MediaTek Inc. (C) 2010. All rights reserved.
 * 
 * BY OPENING THIS FILE, RECEIVER HEREBY UNEQUIVOCALLY ACKNOWLEDGES AND AGREES
 * THAT THE SOFTWARE/FIRMWARE AND ITS DOCUMENTATIONS ("MEDIATEK SOFTWARE")
 * RECEIVED FROM MEDIATEK AND/OR ITS REPRESENTATIVES ARE PROVIDED TO RECEIVER
 * ON AN "AS-IS" BASIS ONLY. MEDIATEK EXPRESSLY DISCLAIMS ANY AND ALL
 * WARRANTIES, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE OR
 * NONINFRINGEMENT. NEITHER DOES MEDIATEK PROVIDE ANY WARRANTY WHATSOEVER WITH
 * RESPECT TO THE SOFTWARE OF ANY THIRD PARTY WHICH MAY BE USED BY,
 * INCORPORATED IN, OR SUPPLIED WITH THE MEDIATEK SOFTWARE, AND RECEIVER AGREES
 * TO LOOK ONLY TO SUCH THIRD PARTY FOR ANY WARRANTY CLAIM RELATING THERETO.
 * RECEIVER EXPRESSLY ACKNOWLEDGES THAT IT IS RECEIVER'S SOLE RESPONSIBILITY TO
 * OBTAIN FROM ANY THIRD PARTY ALL PROPER LICENSES CONTAINED IN MEDIATEK
 * SOFTWARE. MEDIATEK SHALL ALSO NOT BE RESPONSIBLE FOR ANY MEDIATEK SOFTWARE
 * RELEASES MADE TO RECEIVER'S SPECIFICATION OR TO CONFORM TO A PARTICULAR
 * STANDARD OR OPEN FORUM. RECEIVER'S SOLE AND EXCLUSIVE REMEDY AND MEDIATEK'S
 * ENTIRE AND CUMULATIVE LIABILITY WITH RESPECT TO THE MEDIATEK SOFTWARE
 * RELEASED HEREUNDER WILL BE, AT MEDIATEK'S OPTION, TO REVISE OR REPLACE THE
 * MEDIATEK SOFTWARE AT ISSUE, OR REFUND ANY SOFTWARE LICENSE FEES OR SERVICE
 * CHARGE PAID BY RECEIVER TO MEDIATEK FOR SUCH MEDIATEK SOFTWARE AT ISSUE.
 *
 * The following software/firmware and/or related documentation ("MediaTek
 * Software") have been modified by MediaTek Inc. All revisions are subject to
 * any receiver's applicable license agreements with MediaTek Inc.
 */
/*
   If build android default code, MM_LOGI would not define.
   If build mtk aosp code, MM_LOGI would define xlog.
   The goal is that
       non andorid default log would build in Android default load.
       we can use MM_LOGV but should not use  MTK_AOSP_ENHANCEMENT to include the log
*/

#ifndef MM_LOG_H
#define MM_LOG_H

#ifdef MTK_AOSP_ENHANCEMENT
#include <cutils/xlog.h>
#define MM_LOGV(fmt, arg...)  XLOGV("[%s]line:%d "fmt, __FUNCTION__, __LINE__, ##arg)
#define MM_LOGD(fmt, arg...)  XLOGD("[%s]line:%d "fmt, __FUNCTION__, __LINE__, ##arg)
#define MM_LOGI(fmt, arg...)  XLOGI("[%s]line:%d "fmt, __FUNCTION__, __LINE__, ##arg)
#define MM_LOGW(fmt, arg...)  XLOGW("[%s]line:%d "fmt, __FUNCTION__, __LINE__, ##arg)
#define MM_LOGE(fmt, arg...)  XLOGE("[%s]line:%d "fmt, __FUNCTION__, __LINE__, ##arg)
#else
#define MM_LOGV(fmt, arg...) 
#define MM_LOGD(fmt, arg...) 
#define MM_LOGI(fmt, arg...) 
#define MM_LOGW(fmt, arg...) 
#define MM_LOGE(fmt, arg...) 
#endif

#endif     // #ifndef MM_LOG_H
