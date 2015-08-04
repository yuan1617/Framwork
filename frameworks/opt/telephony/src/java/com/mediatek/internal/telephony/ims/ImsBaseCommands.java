/* Copyright Statement:
 *
 * This software/firmware and related documentation ("MediaTek Software") are
 * protected under relevant copyright laws. The information contained herein
 * is confidential and proprietary to MediaTek Inc. and/or its licensors.
 * Without the prior written permission of MediaTek inc. and/or its licensors,
 * any reproduction, modification, use or disclosure of MediaTek Software,
 * and information contained herein, in whole or in part, shall be strictly prohibited.
 */
/* MediaTek Inc. (C) 2014. All rights reserved.
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


import android.content.Context;
import android.os.RegistrantList;
import android.os.Registrant;
import android.os.Handler;

/**
 * {@hide}
 */
public abstract class ImsBaseCommands implements ImsCommandsInterface {
    //***** Instance Variables
    protected Context mContext;

    protected RegistrantList mCallStateRegistrants = new RegistrantList();
    protected Registrant mRingRegistrant;
    protected RegistrantList mRingbackToneRegistrants = new RegistrantList();

    /* M: call control part start */
    protected RegistrantList mCallForwardingInfoRegistrants = new RegistrantList();
    protected Registrant mCallRelatedSuppSvcRegistrant;
    protected Registrant mIncomingCallIndicationRegistrant;
    protected Registrant mCnapNotifyRegistrant;
    protected RegistrantList mCipherIndicationRegistrant = new RegistrantList();
    protected Registrant mSpeechCodecInfoRegistrant;
    /* M: call control part end */

    // IMS VoLTE
    protected RegistrantList mEpsNetworkFeatureSupportRegistrants = new RegistrantList();
    protected RegistrantList mEpsNetworkFeatureInfoRegistrants = new RegistrantList();
    protected RegistrantList mSrvccHandoverInfoIndicationRegistrants = new RegistrantList();

    //VoLTE
    protected RegistrantList mImsEnableRegistrants = new RegistrantList();
    protected RegistrantList mImsDisableRegistrants = new RegistrantList();
    protected RegistrantList mImsRegistrationInfoRegistrants = new RegistrantList();
    protected RegistrantList mDedicateBearerActivatedRegistrant = new RegistrantList();
    protected RegistrantList mDedicateBearerModifiedRegistrant = new RegistrantList();
    protected RegistrantList mDedicateBearerDeactivatedRegistrant = new RegistrantList();

    /// M: IMS feature. @{
    /* Register for updating call ids for conference call after SRVCC is done. */
    protected RegistrantList mEconfSrvccRegistrants = new RegistrantList();
    /* Register for updating conference call merged/added result. */
    protected RegistrantList mEconfResultRegistrants = new RegistrantList();
    /* Register for updating call mode and pau. */
    protected RegistrantList mCallInfoRegistrants = new RegistrantList();
    /// @}

    public ImsBaseCommands(Context context) {
        mContext = context;
    }

    public void registerForCallStateChanged(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mCallStateRegistrants.add(r);
    }

    public void unregisterForCallStateChanged(Handler h) {
        mCallStateRegistrants.remove(h);
    }

    public void setOnCallRing(Handler h, int what, Object obj) {
        mRingRegistrant = new Registrant(h, what, obj);
    }

    public void unSetOnCallRing(Handler h) {
        if (mRingRegistrant != null && mRingRegistrant.getHandler() == h) {
            mRingRegistrant.clear();
            mRingRegistrant = null;
        }
    }

    public void registerForRingbackTone(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mRingbackToneRegistrants.add(r);
    }

    public void unregisterForRingbackTone(Handler h) {
        mRingbackToneRegistrants.remove(h);
    }

    public void registerForCallForwardingInfo(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mCallForwardingInfoRegistrants.add(r);
    }

    public void unregisterForCallForwardingInfo(Handler h) {
        mCallForwardingInfoRegistrants.remove(h);
    }

    public void setOnCallRelatedSuppSvc(Handler h, int what, Object obj) {
        mCallRelatedSuppSvcRegistrant = new Registrant(h, what, obj);
    }

    public void unSetOnCallRelatedSuppSvc(Handler h) {
        mCallRelatedSuppSvcRegistrant.clear();
    }

    public void setOnIncomingCallIndication(Handler h, int what, Object obj) {
        mIncomingCallIndicationRegistrant = new Registrant(h, what, obj);
    }

    public void unsetOnIncomingCallIndication(Handler h) {
        mIncomingCallIndicationRegistrant.clear();
    }

    public void setCnapNotify(Handler h, int what, Object obj) {
        mCnapNotifyRegistrant = new Registrant(h, what, obj);
    }

    public void unSetCnapNotify(Handler h) {
        mCnapNotifyRegistrant.clear();
    }

    public void registerForCipherIndication(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mCipherIndicationRegistrant.add(r);
    }

    public void unregisterForCipherIndication(Handler h) {
        mCipherIndicationRegistrant.remove(h);
    }

    public void setOnSpeechCodecInfo(Handler h, int what, Object obj) {
        mSpeechCodecInfoRegistrant = new Registrant(h, what, obj);
    }

    public void unSetOnSpeechCodecInfo(Handler h) {
        if (mSpeechCodecInfoRegistrant != null && mSpeechCodecInfoRegistrant.getHandler() == h) {
            mSpeechCodecInfoRegistrant.clear();
            mSpeechCodecInfoRegistrant = null;
        }
    }

    public void registerForEpsNetworkFeatureSupport(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mEpsNetworkFeatureSupportRegistrants.add(r);
    }

    public void unregisterForEpsNetworkFeatureSupport(Handler h) {
        mEpsNetworkFeatureSupportRegistrants.remove(h);
    }

    public void registerForEpsNetworkFeatureInfo(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mEpsNetworkFeatureInfoRegistrants.add(r);
    }

    public void unregisterForEpsNetworkFeatureInfo(Handler h) {
        mEpsNetworkFeatureInfoRegistrants.remove(h);
    }

    public void registerForSrvccHandoverInfoIndication(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mSrvccHandoverInfoIndicationRegistrants.add(r);
    }
    public void unregisterForSrvccHandoverInfoIndication(Handler h) {
        mSrvccHandoverInfoIndicationRegistrants.remove(h);
    }

    public void registerForEconfSrvcc(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mEconfSrvccRegistrants.add(r);
    }

    public void unregisterForEconfSrvcc(Handler h) {
        mEconfSrvccRegistrants.remove(h);
    }

    public void registerForEconfResult(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mEconfResultRegistrants.add(r);
    }

    public void unregisterForEconfResult(Handler h) {
        mEconfResultRegistrants.remove(h);
    }

    public void registerForCallInfo(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mCallInfoRegistrants.add(r);
    }

    public void unregisterForCallInfo(Handler h) {
        mCallInfoRegistrants.remove(h);
    }

    // IMS
    public void registerForImsEnable(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mImsEnableRegistrants.add(r);
    }

    public void unregisterForImsEnable(Handler h) {
        mImsEnableRegistrants.remove(h);
    }

    public void registerForImsDisable(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mImsDisableRegistrants.add(r);
    }

    public void unregisterForImsDisable(Handler h) {
        mImsDisableRegistrants.remove(h);
    }

    public void registerForImsRegistrationInfo(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mImsRegistrationInfoRegistrants.add(r);
    }

    public void unregisterForImsRegistrationInfo(Handler h) {
        mImsRegistrationInfoRegistrants.remove(h);
    }

    public void registerForDedicateBearerActivated(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mDedicateBearerActivatedRegistrant.add(r);
    }

    public void unregisterForDedicateBearerActivated(Handler h) {
        mDedicateBearerActivatedRegistrant.remove(h);
    }

    public void registerForDedicateBearerModified(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mDedicateBearerModifiedRegistrant.add(r);
    }

    public void unregisterForDedicateBearerModified(Handler h) {
        mDedicateBearerModifiedRegistrant.remove(h);
    }

    public void registerForDedicateBearerDeactivated(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mDedicateBearerDeactivatedRegistrant.add(r);
    }

    public void unregisterForDedicateBearerDeactivated(Handler h) {
        mDedicateBearerDeactivatedRegistrant.remove(h);
    }
}
