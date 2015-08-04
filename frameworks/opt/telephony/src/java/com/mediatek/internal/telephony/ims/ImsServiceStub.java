/* Copyright Statement:
 *
 * This software/firmware and related documentation ("MediaTek Software") are
 * protected under relevant copyright laws. The information contained herein
 * is confidential and proprietary to MediaTek Inc. and/or its licensors.
 * Without the prior written permission of MediaTek inc. and/or its licensors,
 * any reproduction, modification, use or disclosure of MediaTek Software,
 * and information contained herein, in whole or in part, shall be strictly prohibited.
 */
/* MediaTek Inc. (C) 2010. All rights reserved.
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

package com.mediatek.internal.telephony.ims;

import android.app.PendingIntent;
import android.content.Context;
import android.os.ServiceManager;
import com.android.ims.ImsCallProfile;
import com.android.ims.internal.IImsRegistrationListener;
import com.android.ims.internal.IImsCallSession;
import com.android.ims.internal.IImsCallSessionListener;
import com.android.ims.internal.IImsEcbm;
import com.android.ims.internal.IImsUt;
import com.android.ims.internal.IImsConfig;
import com.android.ims.internal.IImsService;

import android.telephony.Rlog;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;

import com.android.ims.ImsServiceClass;
import com.android.ims.ImsConfig;
import com.mediatek.ims.ImsAdapter;
import com.mediatek.ims.ImsConfigStub;
import com.android.internal.telephony.PhoneConstants;


public class ImsServiceStub extends IImsService.Stub {
    private static final String LOG_TAG = "ImsServiceStub";
    private static final boolean DBG = true;
    private static final boolean VDBG = false; // STOPSHIP if true

    private ImsAdapter mImsAdapter = null;
    private ImsRILAdapter  mImsRILAdapter = null;
    private IImsCallSession mPendingMT = null;
    private Context mContext;

    private static ImsConfigStub sImsConfig = null;

    private final Handler mHandler;
    private IImsRegistrationListener mListener;
    private int mImsRegInfo = 0;
    private int mImsExtInfo = 0;
    private int mServiceId = 0;
    private int mImsState = PhoneConstants.IMS_STATE_DISABLED;

    //***** Event Constants
    private static final int EVENT_IMS_REGISTRATION_INFO = 1;
    protected static final int EVENT_RADIO_NOT_AVAILABLE    = 2;
    protected static final int EVENT_SET_IMS_ENABLED_DONE   = 3;
    protected static final int EVENT_SET_IMS_DISABLE_DONE   = 4;
    protected static final int EVENT_IMS_DISABLED_URC   = 5;
    private static final int EVENT_VIRTUAL_SIM_ON = 6;


    private static final int IMS_STATUS_REGISTERED = 1;
    private static final int IMS_ALLOW_INCOMING_CALL_INDICATION = 0;
    private static final int IMS_DISALLOW_INCOMING_CALL_INDICATION = 1;

    //***** IMS Feature Support
    private static final int IMS_VOICE_OVER_LTE = 1;
    private static final int IMS_RCS_OVER_LTE = 2;
    private static final int IMS_SMS_OVER_LTE = 4;
    private static final int IMS_VIDEO_OVER_LTE = 8;

    //Refer to ImsConfig FeatureConstants
    private static final int IMS_MAX_FEATURE_SUPPORT_SIZE = 4;


    /** events id definition */


    public ImsServiceStub(Context context) {
        mImsAdapter = new ImsAdapter(context);
        mImsRILAdapter = new ImsRILAdapter(context, null);

        mContext = context;
        mHandler = new MyHandler();

        if (sImsConfig == null) {
            sImsConfig = new ImsConfigStub(mContext);
        }

        ServiceManager.addService("ims", this, true);

        mImsRILAdapter.registerForImsRegistrationInfo(mHandler, EVENT_IMS_REGISTRATION_INFO, null);
        mImsRILAdapter.registerForImsDisable(mHandler, EVENT_IMS_DISABLED_URC, null);
    }

    public void enableImsAdapter() {
        mImsAdapter.enableImsAdapter();
    }

    public void disableImsAdapter(boolean isNormalDisable) {
        mImsAdapter.disableImsAdapter(isNormalDisable);
        mImsState = PhoneConstants.IMS_STATE_DISABLED;
    }

    @Override
    public boolean isConnected(int serviceId, int serviceType, int callType) {
        /* temp solution: always return ImsAdapter if enable */
        return mImsAdapter.getImsAdapterEnable();
    }

    @Override
    public int open(int serviceClass, PendingIntent incomingCallIntent, IImsRegistrationListener listener) {
        /* temp solution: always return 1 */
        return 1;
    }

    @Override
    public void close(int serviceId) {
    }

    @Override
    public boolean isOpened(int serviceId) {
        /* temp solution: always return ImsAdapter if enable */
        return mImsAdapter.getImsAdapterEnable();
    }

    /**
     * Used for turning on IMS when its in OFF state.
     */

    @Override
    public void turnOnIms() {
        mImsRILAdapter.turnOnIms(mHandler.obtainMessage(EVENT_SET_IMS_ENABLED_DONE));
        mImsState = PhoneConstants.IMS_STATE_ENABLING;
        enableImsAdapter();
    }

    /**
     * Used for turning off IMS when its in ON state.
     * When IMS is OFF, device will behave as CSFB'ed.
     */
    @Override
    public void turnOffIms() {
        mImsRILAdapter.turnOffIms(mHandler.obtainMessage(EVENT_SET_IMS_DISABLE_DONE));
        mImsState = PhoneConstants.IMS_STATE_DISABLING;
    }

    @Override
    public void setRegistrationListener(int serviceId, IImsRegistrationListener listener) {
        mListener = listener;
    }

    @Override
    public ImsCallProfile createCallProfile(int serviceId, int serviceType, int callType) {
        /* leave blank */
        return null;
    }

    @Override
    public IImsCallSession createCallSession(int serviceId, ImsCallProfile profile, IImsCallSessionListener listener) {
        // This API is for outgoing call to create IImsCallSession
        return new ImsCallSessionProxy(profile, listener, mImsRILAdapter);
    }

    @Override
    public IImsCallSession getPendingCallSession(int serviceId, String callId) {
        // This API is for incoming call to create IImsCallSession
        if (mPendingMT == null) {
            return null;
        }

        try {
            if (mPendingMT.getCallId().equals(callId)) {
                return mPendingMT;
            }
        } catch (RemoteException e) {
            // error handling. Currently no-op
        }

        return null;
    }

    /**
     * Ut interface for the supplementary service configuration.
     */
    @Override
    public IImsUt getUtInterface(int serviceId) {
        /* leave blank */
        return null;
    }

    /**
     * Config interface to get/set IMS service/capability parameters.
     */
    @Override
    public IImsConfig getConfigInterface() {
        if (sImsConfig == null) {
            sImsConfig = new ImsConfigStub(mContext);
        }
        return sImsConfig;
    }

    /**
     * ECBM interface for Emergency Callback mode mechanism.
     */
    @Override
    public IImsEcbm getEcbmInterface(int serviceId) {
        /* leave blank */
        return null;
    }

    /**
     *call interface for allowing/refusing the incoming call indication send to App.
     *@hide
     */
    public void SetCallIndication(String callId, int seqNum, boolean isAllow) {
        /* leave blank */
        if (isAllow) {
            mImsRILAdapter.setCallIndication(IMS_ALLOW_INCOMING_CALL_INDICATION, Integer.parseInt(callId), seqNum);
        } else {
            mImsRILAdapter.setCallIndication(IMS_DISALLOW_INCOMING_CALL_INDICATION, Integer.parseInt(callId), seqNum);
        }
    }

    /**
     * Use to query ims enable/disable status.
     *@hide
     */
    public int getImsState() {
        return mImsState;
    }

    private class MyHandler extends Handler {

        private void notifyRegistrationStateChange(int imsRegInfo) {
            if (mListener == null) {
                return;
            }

            if (imsRegInfo == IMS_STATUS_REGISTERED) {
                try {
                    mListener.registrationConnected();
                } catch (RemoteException e) {
                    // error handling. Currently no-op
                }
            } else {
                try {
                    mListener.registrationDisconnected();
                } catch (RemoteException e) {
                    // error handling. Currently no-op
                }
            }
        }

        private void notifyRegistrationCapabilityChange(int imsExtInfo) {

            if (mListener == null) {
                return;
            }

            int[] enabledFeatures = new int[IMS_MAX_FEATURE_SUPPORT_SIZE];
            int[] disabledFeatures = new int[IMS_MAX_FEATURE_SUPPORT_SIZE];

            if ((imsExtInfo & IMS_VOICE_OVER_LTE) == IMS_VOICE_OVER_LTE) {
                enabledFeatures[ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE] = ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE;
            } else {
                disabledFeatures[ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE] = ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE;
            }

            if ((imsExtInfo & IMS_VIDEO_OVER_LTE) == IMS_VIDEO_OVER_LTE) {
                enabledFeatures[ImsConfig.FeatureConstants.FEATURE_TYPE_VIDEO_OVER_LTE] = ImsConfig.FeatureConstants.FEATURE_TYPE_VIDEO_OVER_LTE;
            } else {
                disabledFeatures[ImsConfig.FeatureConstants.FEATURE_TYPE_VIDEO_OVER_LTE] = ImsConfig.FeatureConstants.FEATURE_TYPE_VIDEO_OVER_LTE;
            }

            // currently modem not support
            disabledFeatures[ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI] = ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI;
            disabledFeatures[ImsConfig.FeatureConstants.FEATURE_TYPE_VIDEO_OVER_WIFI] = ImsConfig.FeatureConstants.FEATURE_TYPE_VIDEO_OVER_WIFI;

            try {
                mListener.registrationFeatureCapabilityChanged(ImsServiceClass.MMTEL, enabledFeatures, disabledFeatures);
            } catch (RemoteException e) {
                // error handling. Currently no-op
            }
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case EVENT_IMS_REGISTRATION_INFO:
                if (DBG) Rlog.d(LOG_TAG, "receive EVENT_IMS_REGISTRATION_INFO");

                /**
                 * According to 3GPP TS 27.007 +CIREGU format
                 *
                 * AsyncResult.result is an Object[]
                 * ((Object[])AsyncResult.result)[0] is integer type to indicate the IMS regiration status.
                 *                                    0: not registered
                 *                                    1: registered
                 * ((Object[])AsyncResult.result)[1] is numeric value in hexadecimal format to indicate the IMS capability.
                 *                                    1: RTP-based transfer of voice according to MMTEL (see 3GPP TS 24.173 [87])
                 *                                    2: RTP-based transfer of text according to MMTEL (see 3GPP TS 24.173 [87])
                 *                                    4: SMS using IMS functionality (see 3GPP TS 24.341[101])
                 *                                    8: RTP-based transfer of video according to MMTEL (see 3GPP TS 24.183 [87])
                 *
                 */

                AsyncResult ar = (AsyncResult) msg.obj;
                int newImsRegInfo = ((int[]) ar.result)[0];
                int newImsExtInfo = ((int[]) ar.result)[1];

                /* notify upper application the IMS registration status is chagned */
                if (newImsRegInfo != mImsRegInfo) {
                    mImsRegInfo = newImsRegInfo;
                    notifyRegistrationStateChange(mImsRegInfo);
                }

                /* notify upper application the IMS capability is chagned when IMS is registered */
                if ((newImsExtInfo != mImsExtInfo) && (mImsRegInfo == IMS_STATUS_REGISTERED)) {
                    mImsExtInfo = newImsExtInfo;
                    notifyRegistrationCapabilityChange(mImsExtInfo);
                }
                break;
            case EVENT_SET_IMS_ENABLED_DONE:
                ar = (AsyncResult) msg.obj;
                if (DBG) Rlog.d(LOG_TAG, "receive EVENT_SET_IMS_ENABLED");
                mImsState = PhoneConstants.IMS_STATE_ENABLE;
                break;
            case EVENT_SET_IMS_DISABLE_DONE:
                ar = (AsyncResult) msg.obj;
                if (DBG) Rlog.d(LOG_TAG, "receive EVENT_SET_IMS_DISABLE_DONE");
                break;
            case EVENT_IMS_DISABLED_URC:
                if (DBG) Rlog.d(LOG_TAG, "receive EVENT_IMS_DISABLED_URC");
                disableImsAdapter(true);
                mImsState = PhoneConstants.IMS_STATE_DISABLED;
                break;

            }
        }
    }
}
