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
package com.mediatek.recovery;

import android.content.Context;

import android.os.RemoteException;
import android.os.SystemProperties;

import android.util.Log;


public class RecoveryManager implements IRecoveryManager {
    private static final String TAG = "RecoveryManager";

    public static final String ANTIBRICKING_LEVEL_DISABLE = "0";
    public static final String ANTIBRICKING_LEVEL_LOG_ONLY = "1";
    public static final String ANTIBRICKING_LEVEL_RECOVERY = "2";
    public static final String ANTIBRICKING_LEVEL = SystemProperties.get("ro.mtk_antibricking_level", ANTIBRICKING_LEVEL_DISABLE);

    private Context mContext;
    private IRecoveryManagerService mService;
    RecoveryManager mInstance = null;

    private RecoveryManager() {};

    public RecoveryManager(Context context, IRecoveryManagerService service) {
        super();
        mContext = context;
        mService = service;
    }

    public void systemReady() {
    }

    public String getVersion() {
        try {
            return mService.getVersion();
        } catch (RemoteException re) {
            Log.e(TAG, "getVersion() failed: ", re);
            return null;
        }
    }

    public int backupSingleFile(String module, String file) {
        try {
            return mService.backupSingleFile(module, file);
        } catch (RemoteException re) {
            throw new RuntimeException("RecoveryManagerService has died!");
        }
    }

    public void startBootMonitor() {
        try {
            mService.startBootMonitor();
        } catch (RemoteException re) {
            Log.e(TAG, "startBootMonitor() failed: ", re);
        }
    }

    public void stopBootMonitor() {
        try {
            mService.stopBootMonitor();
        } catch (RemoteException re) {
            Log.e(TAG, "stopBootMonitor() failed: ", re);
        }
    }
}
