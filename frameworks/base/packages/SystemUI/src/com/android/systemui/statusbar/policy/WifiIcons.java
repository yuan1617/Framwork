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

class WifiIcons {
    /*static final int[][] WIFI_SIGNAL_STRENGTH = {
            { R.drawable.stat_sys_wifi_signal_0,
              R.drawable.stat_sys_wifi_signal_1,
              R.drawable.stat_sys_wifi_signal_2,
              R.drawable.stat_sys_wifi_signal_3,
              R.drawable.stat_sys_wifi_signal_4 },
            { R.drawable.stat_sys_wifi_signal_0_fully,
              R.drawable.stat_sys_wifi_signal_1_fully,
              R.drawable.stat_sys_wifi_signal_2_fully,
              R.drawable.stat_sys_wifi_signal_3_fully,
              R.drawable.stat_sys_wifi_signal_4_fully }
        };
    static final int[][] QS_WIFI_SIGNAL_STRENGTH = {
            { R.drawable.ic_qs_wifi_0,
              R.drawable.ic_qs_wifi_1,
              R.drawable.ic_qs_wifi_2,
              R.drawable.ic_qs_wifi_3,
              R.drawable.ic_qs_wifi_4 },
            { R.drawable.ic_qs_wifi_full_0,
              R.drawable.ic_qs_wifi_full_1,
              R.drawable.ic_qs_wifi_full_2,
              R.drawable.ic_qs_wifi_full_3,
              R.drawable.ic_qs_wifi_full_4 }
        };*/

    /// M: Wifi activity icon @{
    static final int[][] WIFI_SIGNAL_STRENGTH_INOUT = {
        { R.drawable.stat_sys_wifi_signal_0_fully,
          R.drawable.stat_sys_wifi_signal_0_fully,
          R.drawable.stat_sys_wifi_signal_0_fully,
          R.drawable.stat_sys_wifi_signal_0_fully},

        { R.drawable.stat_sys_wifi_signal_1_fully,
          R.drawable.stat_sys_wifi_signal_1_fully_in,
          R.drawable.stat_sys_wifi_signal_1_fully_out,
          R.drawable.stat_sys_wifi_signal_1_fully_inout},

        { R.drawable.stat_sys_wifi_signal_2_fully,
          R.drawable.stat_sys_wifi_signal_2_fully_in,
          R.drawable.stat_sys_wifi_signal_2_fully_out,
          R.drawable.stat_sys_wifi_signal_2_fully_inout},

        { R.drawable.stat_sys_wifi_signal_3_fully,
          R.drawable.stat_sys_wifi_signal_3_fully_in,
          R.drawable.stat_sys_wifi_signal_3_fully_out,
          R.drawable.stat_sys_wifi_signal_3_fully_inout},

        { R.drawable.stat_sys_wifi_signal_4_fully,
          R.drawable.stat_sys_wifi_signal_4_fully_in,
          R.drawable.stat_sys_wifi_signal_4_fully_out,
          R.drawable.stat_sys_wifi_signal_4_fully_inout}
    };
    /// M: Wifi activity icon @}

    /// M: config for show the ! icon or not . @{
    static final int[] WIFI_SIGNAL_STRENGTH_EXCLAMATION  = {
          R.drawable.stat_sys_wifi_signal_0,
          R.drawable.stat_sys_wifi_signal_1,
          R.drawable.stat_sys_wifi_signal_2,
          R.drawable.stat_sys_wifi_signal_3,
          R.drawable.stat_sys_wifi_signal_4 ,
    };
    static final int[] WIFI_SIGNAL_STRENGTH_FULL = {
          R.drawable.stat_sys_wifi_signal_0_fully,
          R.drawable.stat_sys_wifi_signal_1_fully,
          R.drawable.stat_sys_wifi_signal_2_fully,
          R.drawable.stat_sys_wifi_signal_3_fully,
          R.drawable.stat_sys_wifi_signal_4_fully ,
    };

    static final int[] QS_WIFI_SIGNAL_STRENGTH_EXCLAMATION = {
          R.drawable.ic_qs_wifi_0,
          R.drawable.ic_qs_wifi_1,
          R.drawable.ic_qs_wifi_2,
          R.drawable.ic_qs_wifi_3,
          R.drawable.ic_qs_wifi_4,
    };

    static final int[] QS_WIFI_SIGNAL_STRENGTH_FULL = {
          R.drawable.ic_qs_wifi_full_0,
          R.drawable.ic_qs_wifi_full_1,
          R.drawable.ic_qs_wifi_full_2,
          R.drawable.ic_qs_wifi_full_3,
          R.drawable.ic_qs_wifi_full_4,
    };

    static final int WIFI_LEVEL_COUNT = WIFI_SIGNAL_STRENGTH_FULL.length;
    static int[][] WIFI_SIGNAL_STRENGTH = new int[2][WIFI_LEVEL_COUNT];
    static int[][] QS_WIFI_SIGNAL_STRENGTH = new int[2][WIFI_LEVEL_COUNT];

    static void initWifiIcon() {
        if (NetworkControllerImpl.mShowNormalIcon) {
            WIFI_SIGNAL_STRENGTH[0] = WIFI_SIGNAL_STRENGTH_FULL;
            WIFI_SIGNAL_STRENGTH[1] = WIFI_SIGNAL_STRENGTH_FULL;
            QS_WIFI_SIGNAL_STRENGTH[0] = QS_WIFI_SIGNAL_STRENGTH_FULL;
            QS_WIFI_SIGNAL_STRENGTH[1] = QS_WIFI_SIGNAL_STRENGTH_FULL;
        } else {
            WIFI_SIGNAL_STRENGTH[0] = WIFI_SIGNAL_STRENGTH_EXCLAMATION;
            WIFI_SIGNAL_STRENGTH[1] = WIFI_SIGNAL_STRENGTH_FULL;
            QS_WIFI_SIGNAL_STRENGTH[0] = QS_WIFI_SIGNAL_STRENGTH_EXCLAMATION;
            QS_WIFI_SIGNAL_STRENGTH[1] = QS_WIFI_SIGNAL_STRENGTH_FULL;
        }

    }
    /// M: config for show the ! icon or not . @}
}
