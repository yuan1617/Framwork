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


package com.mediatek.common.mom;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.telephony.PhoneNumberUtils;
import android.util.Log;

/**
  * Utility for mobile management.
  * @hide
  */
public class MobileManagerUtils {
    private static final String TAG = "MobileManager";
    private static final boolean FEATURE_SUPPORTED =
            SystemProperties.get("ro.mtk_mobile_management").equals("1");
    private static IMobileManagerService sMomInstance = null;

    /**
      * To check whether MoMS feature is supported or not.
      * @return true if supported.
      *
      */
    public static boolean isSupported() {
        return FEATURE_SUPPORTED;
    }

    public static boolean checkPermission(String permissionName, int callingUid) {
        return checkPermission(permissionName, callingUid, null);
    }

    public static void checkPermissionAsync(String permissionName, int callingUid, IRequestedPermissionCallback callback) {
        checkPermissionAsync(permissionName, callingUid, callback, null);
    }

    public static boolean checkPermission(String permissionName, int callingUid, Bundle data) {
        if (FEATURE_SUPPORTED) {
            try {
                IMobileManagerService mom = getServiceInstance();
                if (mom.checkPermissionWithData(permissionName, callingUid, data) != PackageManager.PERMISSION_GRANTED) {
                    return false;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "checkPermission failed!", e);
            }
        }
        return true;
    }

    public static void checkPermissionAsync(String permissionName, int callingUid, IRequestedPermissionCallback callback, Bundle data) {
        if (FEATURE_SUPPORTED) {
            try {
                IMobileManagerService mom = getServiceInstance();
                mom.checkPermissionAsyncWithData(permissionName, callingUid, data, callback);
            } catch (RemoteException e) {
                Log.e(TAG, "checkPermissionAsync failed!", e);
            }
        }
    }

    public static long getUserConfirmTime(int userId, long anrTime) {
        long time = 0;
        if (FEATURE_SUPPORTED) {
            try {
                IMobileManagerService mom = getServiceInstance();
                time = mom.getUserConfirmTime(userId, anrTime);
            } catch (RemoteException e) {
                Log.e(TAG, "getUserConfirmTime() failed!", e);
            }
        }
        return time;
    }

    public static boolean checkIntentPermission(Intent intent, ActivityInfo aInfo, Context context, int callingUid) {
        if (FEATURE_SUPPORTED) {
            Bundle data = new Bundle();
            if (aInfo != null) {
                String permission = decidePermissionAndData(aInfo.permission, intent, context, data);
                if (permission != null) {
                    if (!checkPermission(permission, callingUid, data)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static String decidePermissionAndData(String intentPermission, Intent intent, Context context, Bundle data) {
        String newPermission = null;
        if ("android.permission.CALL_PHONE".equals(intentPermission)) {
            newPermission = SubPermissions.MAKE_CALL;
            if (intent != null) {
                String phoneNumber = PhoneNumberUtils.getNumberFromIntent(intent, context);
                data.putString(IMobileManager.PARAMETER_PHONENUMBER, phoneNumber);
            }
        }
        return newPermission;
    }

    /**
      * mobile service runs on system server and will never die,
      * and permission checking may be executed frequently,
      * so cache the binder instance here for performance.
      */
    private static IMobileManagerService getServiceInstance() {
        if (sMomInstance == null) {
            IBinder binder = ServiceManager.getService(Context.MOBILE_SERVICE);
            sMomInstance = (IMobileManagerService) IMobileManagerService.Stub.asInterface(binder);
        }
        return sMomInstance;
    }
}
