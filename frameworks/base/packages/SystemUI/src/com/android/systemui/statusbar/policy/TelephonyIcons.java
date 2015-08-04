/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import com.android.systemui.R;
import com.mediatek.systemui.ext.DataType;
import com.mediatek.systemui.ext.NetworkType;

public class TelephonyIcons {
    //***** Signal strength icons
    /// M: config for show the ! icon or not @{
    static final int[] TELEPHONY_SIGNAL_STRENGTH_EXCLAMATION  = {
          R.drawable.stat_sys_signal_0,
          R.drawable.stat_sys_signal_1,
          R.drawable.stat_sys_signal_2,
          R.drawable.stat_sys_signal_3,
          R.drawable.stat_sys_signal_4,
    };
    static final int[] TELEPHONY_SIGNAL_STRENGTH_FULL = {
          R.drawable.stat_sys_signal_0_fully,
          R.drawable.stat_sys_signal_1_fully,
          R.drawable.stat_sys_signal_2_fully,
          R.drawable.stat_sys_signal_3_fully,
          R.drawable.stat_sys_signal_4_fully,
    };

    static final int[] QS_TELEPHONY_SIGNAL_STRENGTH_EXCLAMATION = {
          R.drawable.ic_qs_signal_0,
          R.drawable.ic_qs_signal_1,
          R.drawable.ic_qs_signal_2,
          R.drawable.ic_qs_signal_3,
          R.drawable.ic_qs_signal_4,
    };

    static final int[] QS_TELEPHONY_SIGNAL_STRENGTH_FULL = {
          R.drawable.ic_qs_signal_full_0,
          R.drawable.ic_qs_signal_full_1,
          R.drawable.ic_qs_signal_full_2,
          R.drawable.ic_qs_signal_full_3,
          R.drawable.ic_qs_signal_full_4,
    };

    static final int[] TELEPHONY_SIGNAL_STRENGTH_ROAMING_EXCLAMATION  = {
          R.drawable.stat_sys_signal_0,
          R.drawable.stat_sys_signal_1,
          R.drawable.stat_sys_signal_2,
          R.drawable.stat_sys_signal_3,
          R.drawable.stat_sys_signal_4,
    };
    static final int[] TELEPHONY_SIGNAL_STRENGTH_ROAMING_FULL = {
          R.drawable.stat_sys_signal_0_fully,
          R.drawable.stat_sys_signal_1_fully,
          R.drawable.stat_sys_signal_2_fully,
          R.drawable.stat_sys_signal_3_fully,
          R.drawable.stat_sys_signal_4_fully,
    };

    static final int TELEPHONY_LEVEL_COUNT = TELEPHONY_SIGNAL_STRENGTH_FULL.length;
    static int[][] TELEPHONY_SIGNAL_STRENGTH = new int[2][TELEPHONY_LEVEL_COUNT];
    static int[][] QS_TELEPHONY_SIGNAL_STRENGTH = new int[2][TELEPHONY_LEVEL_COUNT];
    static int[][] TELEPHONY_SIGNAL_STRENGTH_ROAMING = new int[2][TELEPHONY_LEVEL_COUNT];

    static void initTelephonyIcon() {
        if (NetworkControllerImpl.mShowNormalIcon) {
            TELEPHONY_SIGNAL_STRENGTH[0] = TELEPHONY_SIGNAL_STRENGTH_FULL;
            TELEPHONY_SIGNAL_STRENGTH[1] = TELEPHONY_SIGNAL_STRENGTH_FULL;
            QS_TELEPHONY_SIGNAL_STRENGTH[0] = QS_TELEPHONY_SIGNAL_STRENGTH_FULL;
            QS_TELEPHONY_SIGNAL_STRENGTH[1] = QS_TELEPHONY_SIGNAL_STRENGTH_FULL;
            TELEPHONY_SIGNAL_STRENGTH_ROAMING[0] = TELEPHONY_SIGNAL_STRENGTH_ROAMING_FULL;
            TELEPHONY_SIGNAL_STRENGTH_ROAMING[1] = TELEPHONY_SIGNAL_STRENGTH_ROAMING_FULL;
        } else {
            TELEPHONY_SIGNAL_STRENGTH[0] = TELEPHONY_SIGNAL_STRENGTH_EXCLAMATION;
            TELEPHONY_SIGNAL_STRENGTH[1] = TELEPHONY_SIGNAL_STRENGTH_FULL;
            QS_TELEPHONY_SIGNAL_STRENGTH[0] = QS_TELEPHONY_SIGNAL_STRENGTH_EXCLAMATION;
            QS_TELEPHONY_SIGNAL_STRENGTH[1] = QS_TELEPHONY_SIGNAL_STRENGTH_FULL;
            TELEPHONY_SIGNAL_STRENGTH_ROAMING[0] = TELEPHONY_SIGNAL_STRENGTH_ROAMING_EXCLAMATION;
            TELEPHONY_SIGNAL_STRENGTH_ROAMING[1] = TELEPHONY_SIGNAL_STRENGTH_ROAMING_FULL;
        }
    }
    /// M: config for show the ! icon or not @}

    //GSM/UMTS
    /*static final int[][] TELEPHONY_SIGNAL_STRENGTH = {
        { R.drawable.stat_sys_signal_0,
          R.drawable.stat_sys_signal_1,
          R.drawable.stat_sys_signal_2,
          R.drawable.stat_sys_signal_3,
          R.drawable.stat_sys_signal_4 },
        { R.drawable.stat_sys_signal_0_fully,
          R.drawable.stat_sys_signal_1_fully,
          R.drawable.stat_sys_signal_2_fully,
          R.drawable.stat_sys_signal_3_fully,
          R.drawable.stat_sys_signal_4_fully }
    };

    static final int[][] QS_TELEPHONY_SIGNAL_STRENGTH = {
        { R.drawable.ic_qs_signal_0,
          R.drawable.ic_qs_signal_1,
          R.drawable.ic_qs_signal_2,
          R.drawable.ic_qs_signal_3,
          R.drawable.ic_qs_signal_4 },
        { R.drawable.ic_qs_signal_full_0,
          R.drawable.ic_qs_signal_full_1,
          R.drawable.ic_qs_signal_full_2,
          R.drawable.ic_qs_signal_full_3,
          R.drawable.ic_qs_signal_full_4 }
    };
    static final int[][] TELEPHONY_SIGNAL_STRENGTH_ROAMING = {
        { R.drawable.stat_sys_signal_0,
          R.drawable.stat_sys_signal_1,
          R.drawable.stat_sys_signal_2,
          R.drawable.stat_sys_signal_3,
          R.drawable.stat_sys_signal_4 },
        { R.drawable.stat_sys_signal_0_fully,
          R.drawable.stat_sys_signal_1_fully,
          R.drawable.stat_sys_signal_2_fully,
          R.drawable.stat_sys_signal_3_fully,
          R.drawable.stat_sys_signal_4_fully }
    };*/

    static final int[] QS_DATA_R = {
        R.drawable.ic_qs_signal_r,
        R.drawable.ic_qs_signal_r
    };

    static final int[][] DATA_SIGNAL_STRENGTH = TELEPHONY_SIGNAL_STRENGTH;

    //***** Data connection icons

    //GSM/UMTS
    static final int[][] DATA_G = {
            { R.drawable.stat_sys_data_fully_connected_g,
              R.drawable.stat_sys_data_fully_connected_g,
              R.drawable.stat_sys_data_fully_connected_g,
              R.drawable.stat_sys_data_fully_connected_g },
            { R.drawable.stat_sys_data_fully_connected_g,
              R.drawable.stat_sys_data_fully_connected_g,
              R.drawable.stat_sys_data_fully_connected_g,
              R.drawable.stat_sys_data_fully_connected_g }
        };

    static final int[] QS_DATA_G = {
        R.drawable.ic_qs_signal_g,
        R.drawable.ic_qs_signal_g
    };

    static final int[][] DATA_3G = {
            { R.drawable.stat_sys_data_fully_connected_3g,
              R.drawable.stat_sys_data_fully_connected_3g,
              R.drawable.stat_sys_data_fully_connected_3g,
              R.drawable.stat_sys_data_fully_connected_3g },
            { R.drawable.stat_sys_data_fully_connected_3g,
              R.drawable.stat_sys_data_fully_connected_3g,
              R.drawable.stat_sys_data_fully_connected_3g,
              R.drawable.stat_sys_data_fully_connected_3g }
        };

    static final int[] QS_DATA_3G = {
        R.drawable.ic_qs_signal_3g,
        R.drawable.ic_qs_signal_3g
    };

    static final int[][] DATA_E = {
            { R.drawable.stat_sys_data_fully_connected_e,
              R.drawable.stat_sys_data_fully_connected_e,
              R.drawable.stat_sys_data_fully_connected_e,
              R.drawable.stat_sys_data_fully_connected_e },
            { R.drawable.stat_sys_data_fully_connected_e,
              R.drawable.stat_sys_data_fully_connected_e,
              R.drawable.stat_sys_data_fully_connected_e,
              R.drawable.stat_sys_data_fully_connected_e }
        };

    static final int[] QS_DATA_E = {
        R.drawable.ic_qs_signal_e,
        R.drawable.ic_qs_signal_e
    };

    //3.5G
    static final int[][] DATA_H = {
            { R.drawable.stat_sys_data_fully_connected_h,
              R.drawable.stat_sys_data_fully_connected_h,
              R.drawable.stat_sys_data_fully_connected_h,
              R.drawable.stat_sys_data_fully_connected_h },
            { R.drawable.stat_sys_data_fully_connected_h,
              R.drawable.stat_sys_data_fully_connected_h,
              R.drawable.stat_sys_data_fully_connected_h,
              R.drawable.stat_sys_data_fully_connected_h }
    };

    static final int[] QS_DATA_H = {
                R.drawable.ic_qs_signal_h,
                R.drawable.ic_qs_signal_h
    };

    //CDMA
    // Use 3G icons for EVDO data and 1x icons for 1XRTT data
    static final int[][] DATA_1X = {
            { R.drawable.stat_sys_data_fully_connected_1x,
              R.drawable.stat_sys_data_fully_connected_1x,
              R.drawable.stat_sys_data_fully_connected_1x,
              R.drawable.stat_sys_data_fully_connected_1x },
            { R.drawable.stat_sys_data_fully_connected_1x,
              R.drawable.stat_sys_data_fully_connected_1x,
              R.drawable.stat_sys_data_fully_connected_1x,
              R.drawable.stat_sys_data_fully_connected_1x }
            };

    static final int[] QS_DATA_1X = {
        R.drawable.ic_qs_signal_1x,
        R.drawable.ic_qs_signal_1x
    };

    // LTE and eHRPD
    static final int[][] DATA_4G = {
            { R.drawable.stat_sys_data_fully_connected_4g,
              R.drawable.stat_sys_data_fully_connected_4g,
              R.drawable.stat_sys_data_fully_connected_4g,
              R.drawable.stat_sys_data_fully_connected_4g },
            { R.drawable.stat_sys_data_fully_connected_4g,
              R.drawable.stat_sys_data_fully_connected_4g,
              R.drawable.stat_sys_data_fully_connected_4g,
              R.drawable.stat_sys_data_fully_connected_4g }
        };

    static final int[] QS_DATA_4G = {
        R.drawable.ic_qs_signal_4g,
        R.drawable.ic_qs_signal_4g
    };

    // LTE branded "LTE"
    static final int[][] DATA_LTE = {
            { R.drawable.stat_sys_data_fully_connected_lte,
                    R.drawable.stat_sys_data_fully_connected_lte,
                    R.drawable.stat_sys_data_fully_connected_lte,
                    R.drawable.stat_sys_data_fully_connected_lte },
            { R.drawable.stat_sys_data_fully_connected_lte,
                    R.drawable.stat_sys_data_fully_connected_lte,
                    R.drawable.stat_sys_data_fully_connected_lte,
                    R.drawable.stat_sys_data_fully_connected_lte }
    };

    static final int[] QS_DATA_LTE = {
        R.drawable.ic_qs_signal_lte,
        R.drawable.ic_qs_signal_lte
    };

    static final int FLIGHT_MODE_ICON = R.drawable.stat_sys_airplane_mode;
    static final int ROAMING_ICON = R.drawable.stat_sys_data_fully_connected_roam;
    static final int ICON_LTE = R.drawable.stat_sys_data_fully_connected_lte;
    static final int ICON_3G = R.drawable.stat_sys_data_fully_connected_3g;
    static final int ICON_4G = R.drawable.stat_sys_data_fully_connected_4g;
    static final int ICON_1X = R.drawable.stat_sys_data_fully_connected_1x;

    static final int QS_ICON_LTE = R.drawable.ic_qs_signal_lte;
    static final int QS_ICON_3G = R.drawable.ic_qs_signal_3g;
    static final int QS_ICON_4G = R.drawable.ic_qs_signal_4g;
    static final int QS_ICON_1X = R.drawable.ic_qs_signal_1x;

    /// M: Support "Service Network Type on Statusbar". @{
    static public int getNetworkTypeIcon(NetworkType networkType) {
        if (networkType == NetworkType.Type_G) {
            return R.drawable.stat_sys_network_type_g;
        } else if (networkType == NetworkType.Type_E) {
            return R.drawable.stat_sys_network_type_e;
        } else if (networkType == NetworkType.Type_3G) {
            return R.drawable.stat_sys_network_type_3g;
        } else if (networkType == NetworkType.Type_4G) {
            return R.drawable.stat_sys_network_type_4g;
        } else if (networkType == NetworkType.Type_1X) {
            return R.drawable.stat_sys_network_type_1x;
        } else if (networkType == NetworkType.Type_1X3G) {
            return R.drawable.stat_sys_network_type_1x_3g;
        } else {
            return -1;
        }
    }
    /// M: Support "Service Network Type on Statusbar". @}

    /// M: FIXME: No png, need to add it. {
    static final int[][] DATA_R = {
            { R.drawable.stat_sys_data_fully_connected_roam,
              R.drawable.stat_sys_data_fully_connected_roam,
              R.drawable.stat_sys_data_fully_connected_roam,
              R.drawable.stat_sys_data_fully_connected_roam },
            { R.drawable.stat_sys_data_fully_connected_roam,
              R.drawable.stat_sys_data_fully_connected_roam, 
              R.drawable.stat_sys_data_fully_connected_roam,
              R.drawable.stat_sys_data_fully_connected_roam }
    };

    static final int[][] DATA_H_PLUS = {
            { R.drawable.stat_sys_data_fully_connected_h,
              R.drawable.stat_sys_data_fully_connected_h,
              R.drawable.stat_sys_data_fully_connected_h,
              R.drawable.stat_sys_data_fully_connected_h },
            { R.drawable.stat_sys_data_fully_connected_h,
              R.drawable.stat_sys_data_fully_connected_h,
              R.drawable.stat_sys_data_fully_connected_h,
              R.drawable.stat_sys_data_fully_connected_h }
    };
    static final int[][] DATA_3G_PLUS = {
            { R.drawable.stat_sys_data_fully_connected_3g,
              R.drawable.stat_sys_data_fully_connected_3g,
              R.drawable.stat_sys_data_fully_connected_3g,
              R.drawable.stat_sys_data_fully_connected_3g },
            { R.drawable.stat_sys_data_fully_connected_3g,
              R.drawable.stat_sys_data_fully_connected_3g,
              R.drawable.stat_sys_data_fully_connected_3g,
              R.drawable.stat_sys_data_fully_connected_3g }
        };
    /// M: FIXME: No png, need to add it. }

    static final int[][][] DATA = {
        DATA_1X,
        DATA_3G,
        DATA_4G,
        DATA_E,
        DATA_G,
        DATA_H,
        DATA_H_PLUS,
        DATA_3G_PLUS
    };
    public static int[][] getDataTypeIconListGemini(boolean roaming, DataType dataType) {
        int[][] iconList = null;
        /// M: FIXME: the list may not be the png you want to get.
        if (roaming) {
            iconList = DATA_R;
        } else {
            iconList = DATA[dataType.getTypeId()];
        }
        return iconList;
    }
}

