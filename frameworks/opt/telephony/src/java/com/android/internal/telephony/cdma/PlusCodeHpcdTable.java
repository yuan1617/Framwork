/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.internal.telephony.cdma;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.os.Environment;
import android.util.Log;
import android.util.Xml;
import android.util.SparseIntArray;
import java.util.ArrayList;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import android.os.SystemProperties;

import com.android.internal.util.XmlUtils;

public class PlusCodeHpcdTable {
    
    private static PlusCodeHpcdTable sInstance;
    private static SparseIntArray FindOutSidMap = new SparseIntArray();       

    static final Object sInstSync = new Object();
    
    static final String LOG_TAG = "CDMA-PlusCodeHpcdTable";
    private static final boolean DBG = true;

    static final int PARAM_FOR_OFFSET = 2;

    private static final MccIddNddSid[] MccIddNddSidMap_all = {
        new MccIddNddSid(226, "40" , 16000, 16127, "00", "0"),
        new MccIddNddSid(230, "420", 16128, 16255, "00", "" ),
        new MccIddNddSid(232, "43" , 22912, 23039, "00", "0"),
        new MccIddNddSid(238, "45" , 22528, 22543, "00", "" ),
        new MccIddNddSid(240, "46" , 22400, 22403, "00", "0"),
        new MccIddNddSid(242, "47" , 22272, 22399, "00", "" ),
        new MccIddNddSid(244, "358", 24448, 24451, "00", "0"),
        new MccIddNddSid(247, "371", 10784, 10785, "00", "0"),
        new MccIddNddSid(248, "372", 10872, 10879, "00", "" ),
        new MccIddNddSid(250, "7 " , 11392, 11554, "810", "8"),
        new MccIddNddSid(250, "7"  , 11556, 11599, "810", "8"),
        new MccIddNddSid(250, "7 " , 11601, 12287, "810", "8"),
        new MccIddNddSid(255, "380", 15906, 15921, "810", "8"),
        new MccIddNddSid(257, "375", 15954, 15961, "810", "8"),
        new MccIddNddSid(259, "373", 15922, 15937, "00", "0" ),
        new MccIddNddSid(260, "48" , 16256, 16383, "00", "0" ),
        new MccIddNddSid(268, "351", 24320, 24351, "00", ""  ),
        new MccIddNddSid(272, "353", 24384, 24387, "00", "0" ),
        new MccIddNddSid(274, "354", 24416, 24447, "00", "0" ),
        new MccIddNddSid(274, "875", 24416, 24447, "00", ""  ),
        new MccIddNddSid(282, "995", 15962, 15969, "810", "8"),
        new MccIddNddSid(283, "374", 15938, 15945, "00", "0"),
        new MccIddNddSid(284, "359", 15584, 15615, "00", "0"),
        new MccIddNddSid(302, "1", 16384, 18431, "011", "1" ),
        new MccIddNddSid(310, "1", 1    , 2175 , "011", "1" ),
        new MccIddNddSid(310, "1", 2304 , 7679 , "011", "1" ),
        new MccIddNddSid(330, "1", 1    , 2175 , "011", "1" ),
        new MccIddNddSid(330, "1", 2304 , 7679 , "011", "1" ),
        new MccIddNddSid(332, "1", 1    , 2175 , "011", "1" ),
        new MccIddNddSid(332, "1", 2304 , 7679 , "011", "1" ),
        new MccIddNddSid(334, "52", 24576, 25075, "00", "01"),
        new MccIddNddSid(334, "52", 25100, 25124, "00", "01"),
        new MccIddNddSid(338, "1", 8176, 8191, "011", "0"   ),
        new MccIddNddSid(342, "1", 8160, 8175, "011", "1"   ),
        new MccIddNddSid(346, "1", 8128, 8143, "011", "1"   ),
        new MccIddNddSid(348, "1", 8112, 8127, "011", "1"   ),
        new MccIddNddSid(350, "1", 8096, 8111, "011", "1"   ),
        new MccIddNddSid(356, "1", 8032, 8047, "011", "1"    ),
        new MccIddNddSid(358, "1", 8010, 8011, "011", "1"    ),
        new MccIddNddSid(358, "1", 8016, 8031, "011", "1"    ),
        new MccIddNddSid(362, "599", 31392, 31407, "00", "0" ),
        new MccIddNddSid(363, "297", 9648 , 9663 , "00", ""  ),
        new MccIddNddSid(364, "1"  , 8080 , 8095 , "011", "1" ),
        new MccIddNddSid(370, "1"  , 8832 , 8847 , "011", "1" ),
        new MccIddNddSid(372, "509", 32608, 32639, "00", "0" ),
        new MccIddNddSid(374, "1"  , 9824 , 9855 , "011", "1" ),
        new MccIddNddSid(376, "1"  , 10800, 10815, "011", "1" ),
        new MccIddNddSid(400, "994", 15946, 15953, "00",  "0" ),
        new MccIddNddSid(401, "7" , 8928,  8943 , "810", "0"  ),
        new MccIddNddSid(404, "91", 14464, 14847, "00",   "0" ),
        new MccIddNddSid(410, "92" , 14848, 14975, "00",  "0" ),
        new MccIddNddSid(412, "93" , 14976, 15103, "00",  "0" ),
        new MccIddNddSid(413, "94" , 15104, 15231, "00",  "0" ),
        new MccIddNddSid(414, "95" , 15232, 15359, "00",  ""  ),
        new MccIddNddSid(418, "964",  15456, 15487, "00", "0" ),
        new MccIddNddSid(419, "965",  11312, 11327, "00", "0" ),
        new MccIddNddSid(420, "966", 15488, 15519, "00",  "0" ),
        new MccIddNddSid(421, "967", 11328, 11343, "00",  "0" ),
        new MccIddNddSid(421, "967", 11360, 11375, "00",  "0" ),
        new MccIddNddSid(422, "968", 11344, 11359, "00",  "0" ),
        new MccIddNddSid(425, "972",  8448,  8479,  "00",  "0" ),
        new MccIddNddSid(428, "976", 15520, 15551, "002", "0" ),//access code is change according to to http://manyou.ct10000.com/doc_ctc_next.html 
        new MccIddNddSid(429, "977", 15552, 15583, "00",  "0" ),
        new MccIddNddSid(434, "998", 10832, 10855, "810", "8" ),
        new MccIddNddSid(434, "998", 11555, 11555, "810", "8" ),
        new MccIddNddSid(434, "998", 11600, 11600, "810", "8" ),
        new MccIddNddSid(436, "992", 10856, 10871, "810", "8" ),
        new MccIddNddSid(437, "996",  21550, 21566, "00",  "0" ),
        new MccIddNddSid(438, "993", 15970, 15977, "810", "8" ),
        new MccIddNddSid(440, "81", 12288, 13311,  "010", "0" ),//access code is changed according to http://manyou.ct10000.com/doc_ctc_next.html 
        new MccIddNddSid(450, "82", 2176  , 2303,  "00700", "0" ),//access code is changed according to http://manyou.ct10000.com/doc_ctc_next.html 
        new MccIddNddSid(452, "84", 13312, 13439,  "00",  "0" ),
        new MccIddNddSid(454, "852", 10640, 10655, "001", ""  ),
        new MccIddNddSid(455, "853", 11296, 11311, "00",  "0" ),
        new MccIddNddSid(456, "855", 11104, 11135, "001", "0" ),
        new MccIddNddSid(457, "856", 13440, 13471, "00", "0"  ),
        new MccIddNddSid(460, "86" , 13568, 14335, "00", "0"  ),
        new MccIddNddSid(460, "86" , 25600, 26111, "00", "0"  ),
        new MccIddNddSid(466, "886", 13504, 13535, "005", ""  ),//access code is changed according to http://manyou.ct10000.com/doc_ctc_next.html 
        new MccIddNddSid(470, "880", 13472, 13503, "00", "0"  ),
        new MccIddNddSid(502, "60",  10368, 10495, "00", "0"  ),
        new MccIddNddSid(505, "61" , 7680 , 7807 , "0011", "0" ),
        new MccIddNddSid(505, "61" , 8320 , 8447 , "0011", "0" ),
        new MccIddNddSid(510, "62",  10496, 10623, "001",  "0" ),
        new MccIddNddSid(515, "63" , 10624, 10639, "00", "0" ),
        new MccIddNddSid(515, "63" , 10656, 10751, "00", "0" ),
        new MccIddNddSid(520, "66" , 8192 , 8223 , "001", "0" ),
        new MccIddNddSid(530, "64", 8576 , 8703 , "00",  "0" ),
        new MccIddNddSid(534, "1"  , 9680 , 9695 , "011", "1" ),
        new MccIddNddSid(535, "1"  , 9696 , 9711 , "011", "1" ),
        new MccIddNddSid(542, "679", 10960, 10975, "00", ""   ),
        new MccIddNddSid(544, "1"  , 4100 , 4100 , "011", "1" ),
        new MccIddNddSid(602, "20" , 8224 , 8255 , "00", "0"  ),
        new MccIddNddSid(603, "213", 8288, 8319, "00", "7" ),
        new MccIddNddSid(604, "212", 8256, 8287, "00", ""  ),
        new MccIddNddSid(607, "220", 8544, 8575, "00", ""  ),
        new MccIddNddSid(608, "221", 8704, 8735, "00", "0" ),
        new MccIddNddSid(609, "222", 8736, 8767, "00", "0" ),
        new MccIddNddSid(610, "223", 8768, 8799, "00", "0" ),
        new MccIddNddSid(612, "225", 8960, 8991, "00", "0" ),
        new MccIddNddSid(614, "227", 9024, 9055, "00", "0" ),
        new MccIddNddSid(615, "228", 9056, 9087, "00", ""  ),
        new MccIddNddSid(616, "229", 9088, 9119, "00", ""  ),
        new MccIddNddSid(617, "230", 9120, 9151, "020", "0" ),
        new MccIddNddSid(619, "232", 9184, 9215, "00", "0"  ),
        new MccIddNddSid(620, "233", 9216, 9247, "00", ""   ),
        new MccIddNddSid(621, "234", 9248, 9279, "009", "0" ),
        new MccIddNddSid(624, "237", 9344, 9375, "00", ""   ),
        new MccIddNddSid(630, "243", 9472, 9503, "00", ""   ),
        new MccIddNddSid(631, "244", 9504, 9535, "00", "0"  ),
        new MccIddNddSid(634, "249", 9568, 9599, "00", "0"  ),
        new MccIddNddSid(634, "258", 9984, 10015, "00", "0" ),
        new MccIddNddSid(635, "250", 9728, 9759, "00", "0"  ),
        new MccIddNddSid(636, "251", 9760, 9791, "00", "0"  ),
        new MccIddNddSid(639, "254", 9856, 9887, "000", "0" ),
        new MccIddNddSid(640, "255", 9888, 9919, "000", "0" ),
        new MccIddNddSid(641, "256", 9920, 9951, "000", "0" ),
        new MccIddNddSid(642, "257", 9952, 9983, "00", ""   ),
        new MccIddNddSid(645, "260", 10016, 10047, "00", "0" ),
        new MccIddNddSid(646, "261", 10048, 10079, "00", "0" ),
        new MccIddNddSid(648, "263", 10080, 10111, "00", "0" ),
        new MccIddNddSid(649, "264", 10112, 10143, "00", "0" ),
        new MccIddNddSid(652, "267", 10176, 10207, "00", ""  ),
        new MccIddNddSid(655, "27" , 10240, 10367, "00", "0" ),
        new MccIddNddSid(702, "501", 32640, 32649, "00", "0" ),
        new MccIddNddSid(704, "502", 32672, 32703, "00", ""  ),
        new MccIddNddSid(706, "503", 32704, 32735, "00", ""  ),
        new MccIddNddSid(708, "504", 32736, 32767, "00", "0" ),
        new MccIddNddSid(710, "505", 32512, 32543, "00", "0" ),
        new MccIddNddSid(714, "507", 32576, 32607, "00", "0" ),
        new MccIddNddSid(716, "51" , 32384, 32511, "00", "0" ),
        new MccIddNddSid(722, "54" , 32128, 32255, "00", "0" ),
        new MccIddNddSid(724, "55" , 31872, 32127, "0015", "0" ),
        new MccIddNddSid(730, "56" , 31744, 31754, "00", "0" ),
        new MccIddNddSid(730, "56" , 31809, 31820, "00", "0" ),
        new MccIddNddSid(730, "56" , 31841, 31854, "00", "0" ),
        new MccIddNddSid(732, "57" , 31616, 31743, "009", "03" ),
        new MccIddNddSid(734, "58" , 31488, 31615, "00", "0" ),
        new MccIddNddSid(740, "593", 31296, 31327, "00", "0" ),
        new MccIddNddSid(746, "597", 31136, 31167, "00", ""  ),
        new MccIddNddSid(748, "598", 31168, 31199, "00", "0" ),
        new MccIddNddSid(901, "875", 22300, 22300, "00", ""  ),
        new MccIddNddSid(902, "1"  , 4177 , 4177 , "011", "1" ),
    };

    //lists SID conflicts that involve countries with non-overlapping time zones.
    private static final MccSidLtmOff[] MccSidLtmOffMap_all = {
        new MccSidLtmOff(310, 1 , -20, -10),      new MccSidLtmOff(404, 1 , 11, 11),   
        new MccSidLtmOff(310, 7 , -20, -10),      new MccSidLtmOff(404, 7 , 11, 11),

        new MccSidLtmOff(310, 13 , -20, -10),     new MccSidLtmOff(454, 13 , 16, 16),
        
        new MccSidLtmOff(310, 3 , -20, -10),      new MccSidLtmOff(724, 3 , 8, -4),
        new MccSidLtmOff(310, 23 , -20, -10),     new MccSidLtmOff(724, 23 , -8, -4),    
        new MccSidLtmOff(310, 27 , -20, -10),     new MccSidLtmOff(724, 27 , -8, -4),    
        new MccSidLtmOff(310, 29 , -20, -10),     new MccSidLtmOff(724, 29 , -8, -4),   
        new MccSidLtmOff(310, 33 , -20, -10),     new MccSidLtmOff(724, 33 , -8, -4),    
        new MccSidLtmOff(310, 43 , -20, -10),     new MccSidLtmOff(724, 43 , -8, -4),     
        new MccSidLtmOff(310, 47 , -20, -10),     new MccSidLtmOff(724, 47 , -8, -4),   
        new MccSidLtmOff(310, 61 , -20, -10),     new MccSidLtmOff(724, 61 , -8, -4),   
        new MccSidLtmOff(310, 65 , -20, -10),     new MccSidLtmOff(724, 65 , -8, -4),  
        new MccSidLtmOff(310, 67 , -20, -10),     new MccSidLtmOff(724, 67 , -8, -4),
        new MccSidLtmOff(310, 69, -20, -10),      new MccSidLtmOff(724, 69 , -8, -4),   
        new MccSidLtmOff(310, 71 , -20, -10),     new MccSidLtmOff(724, 71 , -8, -4),   
        new MccSidLtmOff(310, 131 , -20, -10),    new MccSidLtmOff(724, 131 , -8, -4),   
        new MccSidLtmOff(310, 257 , -20, -10),    new MccSidLtmOff(724, 257 , -8, -4),   
        new MccSidLtmOff(310, 259 , -20, -10),    new MccSidLtmOff(724, 259 , -8, -4),  
        new MccSidLtmOff(310, 261 , -20, -10),    new MccSidLtmOff(724, 261 , -8, -4),  
        new MccSidLtmOff(310, 263 , -20, -10),    new MccSidLtmOff(724, 263 , -8, -4),  
        new MccSidLtmOff(310, 320 , -20, -10),    new MccSidLtmOff(724, 320 , -8, -4), 
        new MccSidLtmOff(310, 322 , -20, -10),    new MccSidLtmOff(724, 322 , -8, -4), 
        new MccSidLtmOff(310, 326 , -20, -10),    new MccSidLtmOff(724, 326 , -8, -4),
        new MccSidLtmOff(310, 328 , -20, -10),    new MccSidLtmOff(724, 328 , -8, -4),  
        new MccSidLtmOff(310, 330 , -20, -10),    new MccSidLtmOff(724, 330 , -8, -4),  
        new MccSidLtmOff(310, 387 , -20, -10),    new MccSidLtmOff(724, 387 , -8, -4),  
        new MccSidLtmOff(310, 389 , -20, -10),    new MccSidLtmOff(724, 389 , -8, -4),  
        new MccSidLtmOff(310, 391 , -20, -10),    new MccSidLtmOff(724, 391 , -8, -4),  
        new MccSidLtmOff(310, 395 , -20, -10),    new MccSidLtmOff(724, 395 , -8, -4), 
        new MccSidLtmOff(310, 576 , -20, -10),    new MccSidLtmOff(724, 576 , -8, -4),
        new MccSidLtmOff(310, 582 , -20, -10),    new MccSidLtmOff(724, 582 , -8, -4), 
        new MccSidLtmOff(310, 703 , -20, -10),    new MccSidLtmOff(724, 703 , -8, -4),    
        new MccSidLtmOff(310, 739 , -20, -10),    new MccSidLtmOff(724, 739 , -8, -4),   
        new MccSidLtmOff(310, 1218 , -20, -10),   new MccSidLtmOff(724, 1218 , -8, -4),  
        new MccSidLtmOff(310, 1220 , -20, -10),   new MccSidLtmOff(724, 1220 , -8, -4),  
        new MccSidLtmOff(310, 1222 , -20, -10),   new MccSidLtmOff(724, 1222 , -8, -4),  
        new MccSidLtmOff(310, 1282 , -20, -10),   new MccSidLtmOff(724, 1282 , -8, -4),  
        new MccSidLtmOff(310, 1313 , -20, -10),   new MccSidLtmOff(724, 1313 , -8, -4), 
        new MccSidLtmOff(310, 1315 , -20, -10),   new MccSidLtmOff(724, 1315 , -8, -4),   
        new MccSidLtmOff(310, 1329 , -20, -10),   new MccSidLtmOff(724, 1329 , -8, -4),  
        new MccSidLtmOff(310, 1409 , -20, -10),   new MccSidLtmOff(724, 1409 , -8, -4),  
        new MccSidLtmOff(310, 1443 , -20, -10),   new MccSidLtmOff(724, 1443 , -8, -4),  
        new MccSidLtmOff(310, 1521 , -20, -10),   new MccSidLtmOff(724, 1521 , -8, -4), 
        new MccSidLtmOff(310, 1569 , -20, -10),   new MccSidLtmOff(724, 1569 , -8, -4), 
        new MccSidLtmOff(310, 1581 , -20, -10),   new MccSidLtmOff(724, 1581 , -8, -4),  
        new MccSidLtmOff(310, 1634 , -20, -10),   new MccSidLtmOff(724, 1634 , -8, -4),
        new MccSidLtmOff(310, 1666 , -20, -10),   new MccSidLtmOff(724, 1666 , -8, -4),
        new MccSidLtmOff(310, 1668 , -20, -10),   new MccSidLtmOff(724, 1668 , -8, -4),

        new MccSidLtmOff(310, 111 , -20, -10),    new MccSidLtmOff(621, 111 , 2, 2),  
        new MccSidLtmOff(310, 211 , -20, -10),    new MccSidLtmOff(621, 211 , 2, 2),                                                                                    
        new MccSidLtmOff(310, 311 , -20, -10),    new MccSidLtmOff(621, 311 , 2, 2),

        new MccSidLtmOff(310, 1235 , -20, -10),   new MccSidLtmOff(515, 1235 , 16, 16),

        new MccSidLtmOff(310, 1901 , -20, -10),   new MccSidLtmOff(250, 1901 , 4, 6),

        new MccSidLtmOff(310, 1111 , -20, -10),   new MccSidLtmOff(450, 1111 , 18, 18),
        new MccSidLtmOff(310, 1112 , -20, -10),   new MccSidLtmOff(450, 1112 , 18, 18),
        new MccSidLtmOff(310, 1113 , -20, -10),   new MccSidLtmOff(450, 1113 , 18, 18),
        new MccSidLtmOff(310, 1700 , -20, -10),   new MccSidLtmOff(450, 1700 , 18, 18),
        new MccSidLtmOff(310, 2177 , -20, -10),   new MccSidLtmOff(450, 2177 , 18, 18),
        new MccSidLtmOff(310, 2179 , -20, -10),   new MccSidLtmOff(450, 2179 , 18, 18),
        new MccSidLtmOff(310, 2181 , -20, -10),   new MccSidLtmOff(450, 2181 , 18, 18),                                                                                
        new MccSidLtmOff(310, 2183 , -20, -10),   new MccSidLtmOff(450, 2183 , 18, 18),
        new MccSidLtmOff(310, 2185 , -20, -10),   new MccSidLtmOff(450, 2185 , 18, 18),
        new MccSidLtmOff(310, 2187 , -20, -10),   new MccSidLtmOff(450, 2187 , 18, 18),
        new MccSidLtmOff(310, 2189 , -20, -10),   new MccSidLtmOff(450, 2189 , 18, 18),
        new MccSidLtmOff(310, 2191 , -20, -10),   new MccSidLtmOff(450, 2191 , 18, 18),
        new MccSidLtmOff(310, 2193 , -20, -10),   new MccSidLtmOff(450, 2193 , 18, 18),
        new MccSidLtmOff(310, 2195 , -20, -10),   new MccSidLtmOff(450, 2195 , 18, 18),
        new MccSidLtmOff(310, 2197 , -20, -10),   new MccSidLtmOff(450, 2197 , 18, 18),
        new MccSidLtmOff(310, 2199 , -20, -10),   new MccSidLtmOff(450, 2199 , 18, 18),

        new MccSidLtmOff(310, 2201 , -20, -10),   new MccSidLtmOff(450, 2201 , 18, 18),                                                                                 
        new MccSidLtmOff(310, 2203 , -20, -10),   new MccSidLtmOff(450, 2203 , 18, 18),
        new MccSidLtmOff(310, 2205 , -20, -10),   new MccSidLtmOff(450, 2205 , 18, 18),
        new MccSidLtmOff(310, 2207 , -20, -10),   new MccSidLtmOff(450, 2207 , 18, 18),
        new MccSidLtmOff(310, 2209 , -20, -10),   new MccSidLtmOff(450, 2209 , 18, 18),
        new MccSidLtmOff(310, 2211 , -20, -10),   new MccSidLtmOff(450, 2211 , 18, 18),
        new MccSidLtmOff(310, 2213 , -20, -10),   new MccSidLtmOff(450, 2213 , 18, 18),
        new MccSidLtmOff(310, 2215 , -20, -10),   new MccSidLtmOff(450, 2215 , 18, 18),
        new MccSidLtmOff(310, 2217 , -20, -10),   new MccSidLtmOff(450, 2217 , 18, 18),
        new MccSidLtmOff(310, 2219 , -20, -10),   new MccSidLtmOff(450, 2219 , 18, 18),
        new MccSidLtmOff(310, 2221 , -20, -10),   new MccSidLtmOff(450, 2221 , 18, 18),                                                                                
        new MccSidLtmOff(310, 2223 , -20, -10),   new MccSidLtmOff(450, 2223 , 18, 18),
        new MccSidLtmOff(310, 2225 , -20, -10),   new MccSidLtmOff(450, 2225 , 18, 18),
        new MccSidLtmOff(310, 2227 , -20, -10),   new MccSidLtmOff(450, 2227 , 18, 18),
        new MccSidLtmOff(310, 2229 , -20, -10),   new MccSidLtmOff(450, 2229 , 18, 18),
        new MccSidLtmOff(310, 2231 , -20, -10),   new MccSidLtmOff(450, 2231 , 18, 18),
        new MccSidLtmOff(310, 2233 , -20, -10),   new MccSidLtmOff(450, 2233 , 18, 18),
        new MccSidLtmOff(310, 2235 , -20, -10),   new MccSidLtmOff(450, 2235 , 18, 18),
        new MccSidLtmOff(310, 2237 , -20, -10),   new MccSidLtmOff(450, 2237 , 18, 18),
        new MccSidLtmOff(310, 2239 , -20, -10),   new MccSidLtmOff(450, 2239 , 18, 18),
        new MccSidLtmOff(310, 2241, -20, -10),    new MccSidLtmOff(450, 2241 , 18, 18),                                                                         
        new MccSidLtmOff(310, 2243 , -20, -10),  new MccSidLtmOff(450, 2243 , 18, 18),
        new MccSidLtmOff(310, 2301 , -20, -10),   new MccSidLtmOff(450, 2301 , 18, 18),  
        new MccSidLtmOff(310, 2303 , -20, -10),   new MccSidLtmOff(450, 2303 , 18, 18),
        new MccSidLtmOff(310, 2369 , -20, -10),   new MccSidLtmOff(450, 2369 , 18, 18),
        new MccSidLtmOff(310, 2370 , -20, -10),   new MccSidLtmOff(450, 2370 , 18, 18),
        new MccSidLtmOff(310, 2371 , -20, -10),   new MccSidLtmOff(450, 2371 , 18, 18),

        new MccSidLtmOff(450, 2222 , 18, 18),     new MccSidLtmOff(404, 2222 , 11, 11),

        new MccSidLtmOff(544, 4100 , -22, -22),   new MccSidLtmOff(734, 4100 , -9, -9), 

        new MccSidLtmOff(310, 4120 , -12, -12),   new MccSidLtmOff(734, 4120 , -9, -9),    
        new MccSidLtmOff(310, 4130 , 20, 20),     new MccSidLtmOff(734, 4130 , -9, -9),                                                                         
        new MccSidLtmOff(310, 4140 , -12, -12),   new MccSidLtmOff(734, 4140 , -9, -9),  
 
        new MccSidLtmOff(520, 8189 , 14, 14),     new MccSidLtmOff(350, 8189 , -8, -8),    
        new MccSidLtmOff(603, 8294 , 2, 2),       new MccSidLtmOff(310, 8294 , -20, -10),  
        new MccSidLtmOff(505, 8358 , 16, 20),     new MccSidLtmOff(310, 8358 , -20, -10),  
        new MccSidLtmOff(505, 8360 , 16, 20),     new MccSidLtmOff(310, 8360 , -20, -10),  
        new MccSidLtmOff(530, 8616 , 24, 24),     new MccSidLtmOff(310, 8616 , -20, -10),  
                                                                                           
        //mcc = 625, can't find thd IDD and NDD in MccIddNddSidMap table.                                                                                   
        new MccSidLtmOff(625, 8860 , -2, -2),     new MccSidLtmOff(310, 8860 , -20, -10),  
        new MccSidLtmOff(625, 8861 , -2, -2),     new MccSidLtmOff(310, 8861 , -20, -10),  
        new MccSidLtmOff(625, 8863 , -2, -2),     new MccSidLtmOff(310, 8863 , -20, -10),  
                                                                                           
        //mcc = 647, can't find thd IDD and NDD in MccIddNddSidMap table.                                                                                   
        new MccSidLtmOff(647, 8950 , 8, 8),       new MccSidLtmOff(310, 8950 , -20, -10),  
        new MccSidLtmOff(647, 8952 , 8, 8),       new MccSidLtmOff(310, 8952 , -20, -10),  
                                                                                                                                                                             
        new MccSidLtmOff(612, 8960 , 0, 0),       new MccSidLtmOff(310, 8960 , -20, -10),  
        new MccSidLtmOff(612, 8962 , 0, 0),       new MccSidLtmOff(310, 8962 , -20, -10),  
        new MccSidLtmOff(615, 9080 , 0, 0),       new MccSidLtmOff(310, 9080 , -20, -10),  
        new MccSidLtmOff(619, 9212 , 0, 0),       new MccSidLtmOff(310, 9212 , -20, -10),  
        new MccSidLtmOff(620, 9244 , 0, 0),       new MccSidLtmOff(310, 9244 , -20, -10),  
        new MccSidLtmOff(620, 9426 , 0, 0),       new MccSidLtmOff(310, 9426 , -20, -10),  
                                                                                           
        //mcc = 623, can't find thd IDD and NDD in MccIddNddSidMap table.                                                                                    
        new MccSidLtmOff(623, 9322 , 2, 2),       new MccSidLtmOff(310, 9322 , -20, -10),  
                                                                                           
        //mcc = 627, can't find thd IDD and NDD in MccIddNddSidMap table.                                                                                    
        new MccSidLtmOff(627, 9394 , 2, 2),       new MccSidLtmOff(310, 9394 , -20, -10),  
                                                                                           
        //mcc = 629, can't find thd IDD and NDD in MccIddNddSidMap table.                                                                                    
        new MccSidLtmOff(629, 9488 , 2, 2),       new MccSidLtmOff(310, 9488 , -20, -10),  
                                                                                           
        //mcc = 632, can't find thd IDD and NDD in MccIddNddSidMap table.                                                                                    
        new MccSidLtmOff(632, 9562 , 0, 0),       new MccSidLtmOff(310, 9562 , -20, -10),  
                                                                                           
        //mcc = 653, can't find thd IDD and NDD in MccIddNddSidMap table.                                                                                    
        new MccSidLtmOff(653, 9640 , 4, 4),       new MccSidLtmOff(310, 9640 , -20, -10),  
        new MccSidLtmOff(653, 9642 , 4, 4),       new MccSidLtmOff(310, 9642 , -20, -10),  
        new MccSidLtmOff(653, 9644 , 4, 4),       new MccSidLtmOff(310, 9644 , -20, -10),  
                                                                                   
        new MccSidLtmOff(636, 9788 , 6, 6),       new MccSidLtmOff(310, 9788 , -20, -10),  
        new MccSidLtmOff(636, 9790 , 6, 6),       new MccSidLtmOff(310, 9790 , -20, -10),

        new MccSidLtmOff(440, 12461 , 18, 18),    new MccSidLtmOff(470, 12461 , 12, 12),   
        new MccSidLtmOff(440, 12463 , 18, 18),    new MccSidLtmOff(470, 12463 , 12, 12),   
        new MccSidLtmOff(440, 12464 , 18, 18),    new MccSidLtmOff(470, 12464 , 12, 12),
        new MccSidLtmOff(440, 12561 , 18, 18),    new MccSidLtmOff(525, 12561 , 16, 16),   
        new MccSidLtmOff(440, 12978 , 18, 18),    new MccSidLtmOff(363, 12978 , -8, -8),

        new MccSidLtmOff(410, 14850 , 10, 10),    new MccSidLtmOff(404, 14850 , 11, 11),   
        new MccSidLtmOff(708, 32752 , -12, -12),  new MccSidLtmOff(364, 32752 , -10, -10),
    };

    private static final MccIddNddSid[] MccIddNddSidMap = TelephonyPlusCode.MccIddNddSidMap_support;

    private static final MccSidLtmOff[] MccSidLtmOffMap = TelephonyPlusCode.MccSidLtmOffMap_support;

    public static PlusCodeHpcdTable getInstance() {
        synchronized (sInstSync) {
            if (sInstance == null) {
                sInstance = new PlusCodeHpcdTable();
            }
        }
        return sInstance;
    }

    private PlusCodeHpcdTable() {
        // Do nothing.
    }

    //get CC from MccIddNddSidMap by mcc value;
    public static MccIddNddSid getCcFromTableByMcc(String sMcc) {
        Log.d(LOG_TAG, " getCcFromTableByMcc mcc = " + sMcc);
        if(sMcc == null || sMcc.length() == 0) {
            Log.d(LOG_TAG, "[getCcFromTableByMcc] please check the param ");
            return null;
        }

        int mcc;
        try {
            mcc = Integer.parseInt(sMcc);
        } catch (NumberFormatException e) {
            Log.e(LOG_TAG, Log.getStackTraceString(e));
            return null;
        }
        int size = MccIddNddSidMap.length;
        int high = size -1, low = 0, guess;

        MccIddNddSid mccIddNddSid = null;
        
        /*while (high - low > 1) {
            guess = (high + low) / 2;
            mccIddNddSid = (MccIddNddSid)MccIddNddSidMap[guess];

            int temMcc = mccIddNddSid.getMcc();
            if (temMcc < mcc) {
                low = guess;
            } else {
                high = guess;
            }
        }*/

        Log.d(LOG_TAG, " getCcFromTableByMcc size = " + size);
        int find = -1;
        for(int i = 0; i < size; i++) {
            mccIddNddSid = (MccIddNddSid)MccIddNddSidMap[i];
            int tempMcc = mccIddNddSid.getMcc();
            Log.d(LOG_TAG, " getCcFromTableByMcc tempMcc = " + tempMcc);
            if(tempMcc == mcc) {
                find = i;
                break;
            }
        }

        Log.d(LOG_TAG, " getCcFromTableByMcc find = " + find);
        if (find > -1 && find < size) {
            mccIddNddSid = (MccIddNddSid)MccIddNddSidMap[find];
            Log.d(LOG_TAG, "Now find Mcc = " + mccIddNddSid.Mcc 
                 + ", Mcc = " + mccIddNddSid.Cc
                 + ", SidMin = " + mccIddNddSid.SidMin
                 + ", SidMax = " + mccIddNddSid.SidMax
                 + ", Idd = " + mccIddNddSid.Idd
                 + ", Ndd = " + mccIddNddSid.Ndd);
            return mccIddNddSid;
        } else {
            Log.d(LOG_TAG, "can't find one that match the Mcc"); 
            return null;
        }
    }

    //get MCC from conflicts table by sid;
    //if Conlicts, there was more than one value. so add into list .
    //if not, there was only one value in the list.
    public static ArrayList<String> getMccFromConflictTableBySid(String sSid) {
        Log.d(LOG_TAG, " [getMccFromConflictTableBySid] sid = " + sSid);
        if(sSid == null || sSid.length() == 0 || sSid.length() > 5) {
            Log.d(LOG_TAG, "[getMccFromConflictTableBySid] please check the param ");
            return null;
        }

        //int sid = Integer.parseInt(sSid);
        int sid;
        try {
            sid = Integer.parseInt(sSid);
        } catch (NumberFormatException e) {
            Log.e(LOG_TAG, Log.getStackTraceString(e));
            return null;
        }
 
        if(sid < 0) return null;

         ArrayList<String> mcc_arrays = new ArrayList<String>();
        MccSidLtmOff mccSidLtmOff = null;
        int mccSidMapSize = MccSidLtmOffMap.length;
        int index = 0;
        Log.d(LOG_TAG, " [getMccFromConflictTableBySid] mccSidMapSize = " + mccSidMapSize);
        for(int i = 0; i < mccSidMapSize; i ++) {
            mccSidLtmOff = (MccSidLtmOff)MccSidLtmOffMap[i];
            if(mccSidLtmOff != null && mccSidLtmOff.Sid == sid) {

                mcc_arrays.add(Integer.toString(mccSidLtmOff.Mcc));
                Log.d(LOG_TAG, "mccSidLtmOff  Mcc = " + mccSidLtmOff.Mcc
                    + ", Sid = " + mccSidLtmOff.Sid
                    + ", LtmOffMin = " + mccSidLtmOff.LtmOffMin
                    + ", LtmOffMax = " + mccSidLtmOff.LtmOffMax);
            }
        }

        return mcc_arrays;
    }

    //get CC from MccIddNddSidMap by sid.
    public static MccIddNddSid getCcFromMINSTableBySid(String sSid) {
        Log.d(LOG_TAG, " [getCcFromMINSTableBySid] sid = " + sSid);
        if(sSid == null || sSid.length() == 0 || sSid.length() > 5) {
            Log.d(LOG_TAG, "[getCcFromMINSTableBySid] please check the param ");
            return null;
        }
    
        int sid;
        try {
            sid = Integer.parseInt(sSid);
        } catch (NumberFormatException e) {
            Log.e(LOG_TAG, Log.getStackTraceString(e));
            return null;
        }
        if(sid < 0) return null;
        
        MccIddNddSid mccIddNddSid = null;
        MccIddNddSid findMccIddNddSid = null;

        int Find = -1;
        int size = MccIddNddSidMap.length;
        for (int i = 0; i < size ; i ++) {
            mccIddNddSid = (MccIddNddSid)MccIddNddSidMap[i];
            if(sid <= mccIddNddSid.SidMax && sid >= mccIddNddSid.SidMin) {
                findMccIddNddSid = mccIddNddSid;
                break;
            }
        }

        if(DBG)Log.d(LOG_TAG, " getCcFromMINSTableBySidAndLtm findMccIddNddSid = " + findMccIddNddSid);
        return findMccIddNddSid;
        
    }

   //get CC from MccIddNddSidMap by ltm_off.
    public String getCcFromMINSTableByLTM( ArrayList<String> mcc_array, String sLtm_off) {
        Log.d(LOG_TAG, " getCcFromMINSTableByLTM sLtm_off = " + sLtm_off);
        if(sLtm_off == null || sLtm_off.length() == 0 || mcc_array == null || mcc_array.size() ==0) {
            Log.d(LOG_TAG, "[getCcFromMINSTableByLTM] please check the param ");
            return null;
        }

        String FindMcc = null;

        int ltm_off;
        try {
            ltm_off = Integer.parseInt(sLtm_off);
        } catch (NumberFormatException e) {
            Log.e(LOG_TAG, Log.getStackTraceString(e));
            return null;
        }
        
        Log.d(LOG_TAG, "[getCcFromMINSTableByLTM]  ltm_off =  " + ltm_off);

        int FindOutMccSize = mcc_array.size();
        if(FindOutMccSize > 1 && MccSidLtmOffMap != null) {
            int mccSidMapSize = MccSidLtmOffMap.length;
            if(DBG) {
                Log.d(LOG_TAG, " Conflict FindOutMccSize = " + FindOutMccSize);
            }
            
            MccSidLtmOff mccSidLtmOff = null;
            int find = -1;
            int mcc = -1;
            for (int i = 0; i < FindOutMccSize; i ++) {
                try {
                    mcc = Integer.parseInt(mcc_array.get(i));
                } catch (NumberFormatException e) {
                    Log.e(LOG_TAG, Log.getStackTraceString(e));
                    return null;
                }
 
                Log.d(LOG_TAG, " Conflict mcc = " + mcc + ",index = " + i);
                for(int j = 0; j < mccSidMapSize; j ++) {
                    mccSidLtmOff = (MccSidLtmOff)MccSidLtmOffMap[j];
                    if(mccSidLtmOff.Mcc == mcc) {
               
                        int max = (mccSidLtmOff.LtmOffMax)*PARAM_FOR_OFFSET;
                        int min = (mccSidLtmOff.LtmOffMin)*PARAM_FOR_OFFSET;

                        Log.d(LOG_TAG, "mccSidLtmOff LtmOffMin = " + mccSidLtmOff.LtmOffMin
                            + ", LtmOffMax = " + mccSidLtmOff.LtmOffMax);
                        if(ltm_off <= max && ltm_off >= min) {
                            FindMcc = mcc_array.get(i);
                            break;
                        }
                    }
                }
            }
        }else {
            FindMcc = mcc_array.get(0);
        }
        
        Log.d(LOG_TAG, "find one that match the ltm_off mcc = " + FindMcc); 
        return FindMcc;
    }

    public static String getMccFromConflictTableBySidLtmOff(String sSid, String sLtm_off){
        Log.d(LOG_TAG, " [getMccFromConflictTableBySidLtmOff] sSid = " + sSid +
            ", sLtm_off = " + sLtm_off);
        if(sSid == null || sSid.length() == 0 || sSid.length() > 5
            || sLtm_off == null || sLtm_off.length() == 0) {
            Log.d(LOG_TAG, "[getMccFromConflictTableBySidLtmOff] please check the param ");
            return null;
        }
        
        //int sid = Integer.parseInt(sSid);
        int sid;
        try {
            sid = Integer.parseInt(sSid);
        } catch (NumberFormatException e) {
            Log.e(LOG_TAG, Log.getStackTraceString(e));
            return null;
        }
        if(sid < 0) return null;

        int ltm_off;
        try {
            ltm_off = Integer.parseInt(sLtm_off);
        } catch (NumberFormatException e) {
            Log.e(LOG_TAG, Log.getStackTraceString(e));
            return null;
        }

        Log.d(LOG_TAG, " [getMccFromConflictTableBySidLtmOff] sid = " + sid);

        int mccSidMapSize = MccSidLtmOffMap.length;
        Log.d(LOG_TAG, " [getMccFromConflictTableBySidLtmOff] mccSidMapSize = " + mccSidMapSize);

        MccSidLtmOff mccSidLtmOff = null;
        for(int i = 0; i < mccSidMapSize; i++){
            mccSidLtmOff = MccSidLtmOffMap[i];

            int max = (mccSidLtmOff.LtmOffMax) * PARAM_FOR_OFFSET;
            int min = (mccSidLtmOff.LtmOffMin) * PARAM_FOR_OFFSET;

            Log.d(LOG_TAG, "[getMccFromConflictTableBySidLtmOff] mccSidLtmOff.Sid = " + mccSidLtmOff.Sid
                + ", sid = " + sid
                + ", ltm_off = " + ltm_off
                + ", max = " + max
                + ", min = " + min);
            
            if(mccSidLtmOff != null && mccSidLtmOff.Sid == sid && (ltm_off <= max && ltm_off >= min)){
                String Mcc = Integer.toString(mccSidLtmOff.Mcc);

                Log.d(LOG_TAG, "[getMccFromConflictTableBySidLtmOff] Mcc = " + Mcc);
                
                return Mcc;
            }
        }

        return null;        
    }

    public static String getMccFromMINSTableBySid(String sSid){
        Log.d(LOG_TAG, " [getMccFromMINSTableBySid] sid = " + sSid);
        if(sSid == null || sSid.length() == 0 || sSid.length() > 5) {
            Log.d(LOG_TAG, "[getMccFromMINSTableBySid] please check the param ");
            return null;
        }
    
        int sid;
        try {
            sid = Integer.parseInt(sSid);
        } catch (NumberFormatException e) {
            Log.e(LOG_TAG, Log.getStackTraceString(e));
            return null;
        }
        if(sid < 0) return null;

        MccIddNddSid mccIddNddSid = null;
        
        int size = MccIddNddSidMap.length;
        Log.d(LOG_TAG, " [getMccFromMINSTableBySid] size = " + size);

        for(int i = 0; i < size; i++){
            mccIddNddSid = MccIddNddSidMap[i];

            Log.d(LOG_TAG, " [getMccFromMINSTableBySid] sid = " + sid
                + ", mccIddNddSid.SidMin = " + mccIddNddSid.SidMin
                + ", mccIddNddSid.SidMax = " + mccIddNddSid.SidMax);

            if(sid >= mccIddNddSid.SidMin && sid <= mccIddNddSid.SidMax){
                String Mcc = Integer.toString(mccIddNddSid.Mcc);

                Log.d(LOG_TAG, "[queryMccFromConflictTableBySid] Mcc = " + Mcc);
                
                return Mcc;
            }
            
        }

        return null;
    }

    public static String getMccMncFromSidMccMncListBySid(String sSid){
        Log.d(LOG_TAG, " [getMccMncFromSidMccMncListBySid] sid = " + sSid);
        if(sSid == null || sSid.length() == 0 || sSid.length() > 5) {
            Log.d(LOG_TAG, "[getMccMncFromSidMccMncListBySid] please check the param ");
            return null;
        }
    
        int sid;
        try {
            sid = Integer.parseInt(sSid);
        } catch (NumberFormatException e) {
            Log.e(LOG_TAG, Log.getStackTraceString(e));
            return null;
        }
        if(sid < 0) return null;

        List<SidMccMnc> mSidMccMncList = TelephonyPlusCode.getSidMccMncList();

        SidMccMnc mSidMccMnc = null;

        int left = 0;
        int right = mSidMccMncList.size() -1;
        int mid;
        int MccMnc = 0;

        while(left <= right) {
            mid = (left + right) / 2;
            
            mSidMccMnc = mSidMccMncList.get(mid);

            if(sid < mSidMccMnc.Sid) {
                right = mid -1;
            }else if(sid > mSidMccMnc.Sid) {
                left = mid +1;
            }else {
                MccMnc = mSidMccMnc.MccMnc;
                break;
            }
        }

        if (MccMnc != 0) {
            String MccMncStr = Integer.toString(MccMnc);

            Log.d(LOG_TAG, "[getMccMncFromSidMccMncListBySid] MccMncStr = " + MccMncStr);
                
            return MccMncStr;
        } else {
            return null;
        }

    }

}
