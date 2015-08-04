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

package com.mediatek.internal.telephony;


import android.net.LinkProperties;

import android.os.Bundle;
import android.os.Message;

import com.mediatek.internal.telephony.BtSimapOperResponse;

//VOLTE
import com.mediatek.internal.telephony.DedicateBearerProperties;
import com.mediatek.internal.telephony.DefaultBearerConfig;
import com.mediatek.internal.telephony.QosStatus;
import com.mediatek.internal.telephony.TftStatus;

/**
 * Interface used to interact with the phone.  Mostly this is used by the
 * TelephonyManager class.  A few places are still using this directly.
 * Please clean them up if possible and use TelephonyManager insteadl.
 *
 * {@hide}
 */
interface ITelephonyEx {

    Bundle queryNetworkLock(long subId, int category);

    int supplyNetworkDepersonalization(long subId, String strPasswd);

    /**
     * Modem SML change feature.
     * This function will query the SIM state of the given slot. And broadcast 
     * ACTION_UNLOCK_SIM_LOCK if the SIM state is in network lock.
     * 
     * @param subId: Indicate which sub to query
     * @param needIntent: The caller can deside to broadcast ACTION_UNLOCK_SIM_LOCK or not
     *                    in this time, because some APs will receive this intent (eg. Keyguard).
     *                    That can avoid this intent to effect other AP.
     */
    void repollIccStateForNetworkLock(long subId, boolean needIntent); 

    int setLine1Number(long subId, String alphaTag, String number);
    
    boolean isFdnEnabled(long subId);

    String getIccCardType(long subId);
    
    boolean isTestIccCard(int slotId);

    String getSimCountryIso(long subId);

    String getSubscriberId(long subId);
    
    int getSimState(int simId);

    String getSimOperator(long subId);
    
    String getSimOperatorName(long subId);

    String getSimSerialNumber(long subId);

    String getLine1AlphaTag(long subId);

    String getVoiceMailNumber(long subId);

    String getVoiceMailAlphaTag(long subId);
    
    int getVoiceMessageCountGemini(int simId);

    String getMvnoMatchType(long subId);

    String getMvnoPattern(long subId, String type);

    String getNetworkOperatorNameGemini(int slotId);
    String getNetworkOperatorNameUsingSub(long subId);
    
    String getNetworkOperatorGemini(int slotId);
    String getNetworkOperatorUsingSub(long subId);
    
    /**
     *send BT SIM profile of Connect SIM
     * @param simId specify which SIM to connect
     * @param btRsp fetch the response data.
     * @return success or error code.
     */
    int btSimapConnectSIM(int simId,  out BtSimapOperResponse btRsp);

    /**
     *send BT SIM profile of Disconnect SIM
     * @param null
     * @return success or error code.
     */
    int btSimapDisconnectSIM();

   /**
     *Transfer APDU data through BT SAP
     * @param type Indicate which transport protocol is the preferred one
     * @param cmdAPDU APDU data to transfer in hex character format    
     * @param btRsp fetch the response data.
     * @return success or error code.
     */	
    int btSimapApduRequest(int type, String cmdAPDU, out BtSimapOperResponse btRsp);

    /**
     *send BT SIM profile of Reset SIM
     * @param type Indicate which transport protocol is the preferred one
     * @param btRsp fetch the response data.
     * @return success or error code.
     */
    int btSimapResetSIM(int type, out BtSimapOperResponse btRsp);

   /**
     *send BT SIM profile of Power On SIM
     * @param type Indicate which transport protocol is the preferred onet
     * @param btRsp fetch the response data.
     * @return success or error code.
     */	
    int btSimapPowerOnSIM(int type, out BtSimapOperResponse btRsp);

   /**
     *send BT SIM profile of PowerOff SIM
     * @return success or error code.
     */ 
    int btSimapPowerOffSIM();

    /**
     * Request to run AKA authenitcation on UICC card by indicated family.
     *
     * @param slotId indicated sim id
     * @param family indiacted family category
     *        UiccController.APP_FAM_3GPP =  1; //SIM/USIM
     *        UiccController.APP_FAM_3GPP2 = 2; //RUIM/CSIM
     *        UiccController.APP_FAM_IMS   = 3; //ISIM
     * @param byteRand random challenge in byte array
     * @param byteAutn authenication token in byte array
     *
     * @retrun reponse paramenters/data from UICC
     *
     */
    byte[] simAkaAuthentication(int slotId, int family, in byte[] byteRand, in byte[] byteAutn);

    /**
     * Request to run GBA authenitcation (Bootstrapping Mode)on UICC card
     * by indicated family.
     *
     * @param slotId indicated sim id
     * @param family indiacted family category
     *        UiccController.APP_FAM_3GPP =  1; //SIM/USIM
     *        UiccController.APP_FAM_3GPP2 = 2; //RUIM/CSIM
     *        UiccController.APP_FAM_IMS   = 3; //ISIM
     * @param byteRand random challenge in byte array
     * @param byteAutn authenication token in byte array
     *
     * @retrun reponse paramenters/data from UICC
     *
     */ 
    byte[] simGbaAuthBootStrapMode(int slotId, int family, in byte[] byteRand, in byte[] byteAutn);

    /**
     * Request to run GBA authenitcation (NAF Derivation Mode)on UICC card
     * by indicated family.
     *
     * @param slotId indicated sim id
     * @param family indiacted family category
     *        UiccController.APP_FAM_3GPP =  1; //SIM/USIM
     *        UiccController.APP_FAM_3GPP2 = 2; //RUIM/CSIM
     *        UiccController.APP_FAM_IMS   = 3; //ISIM
     * @param byteNafId network application function id in byte array
     * @param byteImpi IMS private user identity in byte array
     *
     * @retrun reponse paramenters/data from UICC
     *
     */
    byte[] simGbaAuthNafMode(int slotId, int family, in byte[] byteNafId, in byte[] byteImpi);

    /**
     * Since MTK keyguard has dismiss feature, we need to retrigger unlock event
     * when user try to access the SIM card.
     *
     * @param subId inidicated subscription
     *
     * @return true represent broadcast a unlock intent to notify keyguard
     *         false represent current state is not LOCKED state. No need to retrigger.
     *
     */
    boolean broadcastIccUnlockIntent(long subId);

    /**
     * Query if the radio is turned off by user.
     *
     * @param subId inidicated subscription
     *
     * @return true radio is turned off by user.
     *         false radio isn't turned off by user.
     *
     */
    boolean isRadioOffBySimManagement(long subId);

    /**
     * Get current phone capability
     *
     * @return the capability of phone. (@see PhoneConstants)
     * @internal
     */
    int getPhoneCapability(int phoneId);
    
    /**
     * Set capability to phones
     * 
     * @param phoneId phones want to change capability 
     * @param capability new capability for each phone
     * @internal
     */
    void setPhoneCapability(in int[] phoneId, in int[] capability);
    /**
     * To config SIM swap mode(for dsda).
     * 
     * @return true if config SIM Swap mode successful, or return false
     * @internal
     */
    boolean configSimSwap(boolean toSwapped);
    /**
     * To check SIM is swapped or not(for dsda).
     * 
     * @return true if swapped, or return false
     * @internal
     */
    boolean isSimSwapped();
    /**
     * To Check if Capability Switch Manual Control Mode Enabled. 
     * 
     * @return true if Capability Switch manual control mode is enabled, else false;
     * @internal
     */
    boolean isCapSwitchManualEnabled();

    /**
     * Get item list that will be displayed on manual switch setting
     * 
     * @return String[] contains items
     * @internal
     */
    String[] getCapSwitchManualList();

  /**
     * To get located PLMN from sepcified SIM modem  protocol
     * Returns current located PLMN string(ex: "46000") or null if not availble (ex: in flight mode or no signal area or this SIM is turned off)
     * @param subId Indicate which SIM subscription to query     
     * @internal
     */
    String getLocatedPlmn(long subId);
  
   /**
     * Check if phone is hiding network temporary out of service state.
     * @param subId Indicate which SIM subscription to query
     * @return if phone is hiding network temporary out of service state.
     * @internal
    */
    int getNetworkHideState(long subId);

   /**
     * Enable or Disable IMS Service
     * @param subId Indicate which SIM subscription to set     
     * @param set enable or not (0: disable, 1:enable)
     * @internal
    */
    void setImsEnable(long subId, boolean enable);

   /**
     * Get IMS state
     * @param subId Indicate which SIM subscription to set     
     * @return IMS state
     * @internal
    */
    int getImsState(long subId);
    
   /**
     * get the network service state for specified SIM
     * @param subId Indicate which SIM subscription to query
     * @return service state.
     * @internal
    */
    Bundle getServiceState(long subId);

    /**
     * This function is used to get SIM phonebook storage information
     * by sim id.
     *
     * @param simId Indicate which sim(slot) to query
     * @return int[] which incated the storage info
     *         int[0]; // # of remaining entries
     *         int[1]; // # of total entries
     *         int[2]; // # max length of number
     *         int[3]; // # max length of alpha id
     *
     * @internal
     */ 
    int[] getAdnStorageInfo(long subId);
    
    /**
     * This function is used to check if the SIM phonebook is ready
     * by sim id.
     *
     * @param simId Indicate which sim(slot) to query
     * @return true if phone book is ready. 
     * @internal      
     */    
    boolean isPhbReady(long subId);

    /**
     * Get service center address
     *
     * @param subId subscription identity
     *
     * @return service message center address
     */
    String getScAddressUsingSubId(in long subId);

    /**
     * Set service message center address
     *
     * @param subId subscription identity
     * @param service message center addressto be set
     *
     * @return true for success, false for failure
     */
    void setScAddressUsingSubId(in long subId, in String address);

    /**
     * This function will check if phone can enter airplane mode right now
     *      
     * @return boolean: return phone can enter flight mode
     *                true: phone can enter flight mode
     *                false: phone cannot enter flight mode
     */
    boolean isAirplanemodeAvailableNow();

    /**
     * This function will register Ims disable URC registrants used to know IMS disable done after disable IMS stack
     * Modem has limitation that IMS can not enable during IMS disabling
     *
     * @return void
     */
    void registerForImsDisableDone(long subId, IBinder binder, int what);

    /**
     * This function will unregister Ims disable URC registrants used to know IMS disable done after disable IMS stack
     *
     * @return void
     */
    void unregisterForImsDisableDone(long subId, IBinder binder);
    
    // VoLTE
    /**
     * This function will check if the input cid is dedicate bearer.
     *
     * @return boolean: return dedicated bearer or not 
     *                true: is a dedicated bearer for input cid
     *                false: not a dedicated bearer for input cid
     */
    boolean isDedicateBearer(int cid);

    /**
     * This function will disable Dedicate bearer.
     *
     * @return int: return ddcid of disable dedicated bearer
     *            -1: some thing wrong
     */
    int disableDedicateBearer(String reason, int ddcid);

    /**
     * This function will enable Dedicate bearer.
     *
     * @return int: return ddcid of enable dedicated bearer
     *            -1: some thing wrong
     */
    int enableDedicateBearer(String apnType, boolean signalingFlag, in QosStatus qosStatus, in TftStatus tftStatus);

    /**
     * This function will abort Dedicate bearer.
     *
     * @return int: return ddcid of abort dedicated bearer
     *            -1: some thing wrong
     */
    int abortEnableDedicateBearer(String reason, int ddcId);    
    
    /**
     * This function will modify Dedicate bearer.
     *
     * @return int: return ddcid of abort dedicated bearer
     *            -1: some thing wrong
     */
    int modifyDedicateBearer(int cid, in QosStatus qosStatus, in TftStatus tftStatus);
    
    /**
     * This function will set Default Bearer Config for apnContext.
     *
     * @return int: return success or not
     *            0: set default bearer config successfully
     */
    int setDefaultBearerConfig(String apnType, in DefaultBearerConfig defaultBearerConfig);   
    
    /**
     * This function will get Default Bearer properties for apn type.
     *
     * @param apnType input apn type for get the mapping default bearer properties
     * @return DedicateBearerProperties return the default beare properties for input apn type
     *                             return null if something wrong 
     *
     */
    DedicateBearerProperties getDefaultBearerProperties(String apnType);
    
    
    /**
     * This function will get DcFailCause with int format.
     *
     * @return int: return int failCause value
     */
    int getLastDataConnectionFailCause(String apnType);
    
    /**
     * This function will get deactivate cids. 
     *
     * @return int []: int array about cids which is(are) deactivated
     */
    int [] getDeactivateCidArray(String apnType);

    /**
     * This function will get link properties of input apn type. 
     *
     * @param apnType input apn type for geting link properties
     * @return LinkProperties: return correspondent link properties with input apn type
     */
    LinkProperties getLinkProperties(String apnType);

    /**
     * This function will do pcscf Discovery. 
     *
     * @param apnType input apn type for geting pcscf
     * @param cid input cid 
     * @param onComplete for response event while pcscf discovery done
     * @return int: return 0: OK, -1: failed
     */
    int pcscfDiscovery(String apnType, int cid, in Message onComplete);
}

