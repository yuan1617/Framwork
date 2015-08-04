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

package com.android.internal.telephony.cat;

public class CatResponseMessage {
    CommandDetails mCmdDet = null;
    ResultCode mResCode  = ResultCode.OK;
    int mUsersMenuSelection = 0;
    String mUsersInput  = null;
    boolean mUsersYesNoSelection = false;
    boolean mUsersConfirm = false;
    boolean mIncludeAdditionalInfo = false;
    int mEvent = 0;
    int mSourceId = 0;
    int mDestinationId = 0;
    byte[] mAdditionalInfo = null;
    boolean mOneShot = false;

    public CatResponseMessage(CatCmdMessage cmdMsg) {
        mCmdDet = cmdMsg.mCmdDet;
    }

    public void setResultCode(ResultCode resCode) {
        mResCode = resCode;
    }

    public void setMenuSelection(int selection) {
        mUsersMenuSelection = selection;
    }

    public void setInput(String input) {
        mUsersInput = input;
    }

    public void setYesNo(boolean yesNo) {
        mUsersYesNoSelection = yesNo;
    }

    public void setConfirmation(boolean confirm) {
        mUsersConfirm = confirm;
    }

    CommandDetails getCmdDetails() {
        return mCmdDet;
    }

    public CatResponseMessage() {
    }

    public CatResponseMessage(int event) {
        mEvent = event;
    }

    /**
       * Set the source id of the device tag in the terminal response
       * @internal
       */
    public void setSourceId(int sId) {
        mSourceId = sId;
    }

    public void setEventId(int event) {
        mEvent = event;
    }

    /**
       * Set the destination id of the device tag in the terminal response
       * @internal
       */
    public void setDestinationId(int dId) {
        mDestinationId = dId;
    }


    /**
       * Set the additional information of result code
       * @internal
       */
    public void setAdditionalInfo(byte[] additionalInfo) {
        if (additionalInfo != null) {
            mIncludeAdditionalInfo = true;
        }
        mAdditionalInfo = additionalInfo;
    }

    /**
       * Set the one shot flag in the terminal response
       * @internal
       */
    public void setOneShot(boolean shot) {
        mOneShot = shot;
    }
    // ICS Migration end
    }
