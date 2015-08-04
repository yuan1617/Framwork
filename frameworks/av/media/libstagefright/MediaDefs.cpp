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

#include <media/stagefright/MediaDefs.h>

namespace android {

const char *MEDIA_MIMETYPE_IMAGE_JPEG = "image/jpeg";
#ifdef MTK_AOSP_ENHANCEMENT
const char *MEDIA_MIMETYPE_VIDEO_VPX = "video/x-vnd.on2.vp8";
#endif
const char *MEDIA_MIMETYPE_VIDEO_VP8 = "video/x-vnd.on2.vp8";
const char *MEDIA_MIMETYPE_VIDEO_VP9 = "video/x-vnd.on2.vp9";
const char *MEDIA_MIMETYPE_VIDEO_AVC = "video/avc";
const char *MEDIA_MIMETYPE_VIDEO_HEVC = "video/hevc";
const char *MEDIA_MIMETYPE_VIDEO_MPEG4 = "video/mp4v-es";
const char *MEDIA_MIMETYPE_VIDEO_H263 = "video/3gpp";
const char *MEDIA_MIMETYPE_VIDEO_MPEG2 = "video/mpeg2";
const char *MEDIA_MIMETYPE_VIDEO_RAW = "video/raw";
#ifdef MTK_AOSP_ENHANCEMENT
const char *MEDIA_MIMETYPE_VIDEO_DIVX = "video/divx";
const char *MEDIA_MIMETYPE_VIDEO_DIVX3 = "video/divx3";
const char *MEDIA_MIMETYPE_VIDEO_XVID = "video/xvid";
const char *MEDIA_MIMETYPE_VIDEO_MSMPEG4V3 = "video/msmp4v3";
#endif
const char *MEDIA_MIMETYPE_AUDIO_AMR_NB = "audio/3gpp";
const char *MEDIA_MIMETYPE_AUDIO_AMR_WB = "audio/amr-wb";
const char *MEDIA_MIMETYPE_AUDIO_MPEG = "audio/mpeg";
const char *MEDIA_MIMETYPE_AUDIO_MPEG_LAYER_I = "audio/mpeg-L1";
const char *MEDIA_MIMETYPE_AUDIO_MPEG_LAYER_II = "audio/mpeg-L2";
const char *MEDIA_MIMETYPE_AUDIO_AAC = "audio/mp4a-latm";
const char *MEDIA_MIMETYPE_AUDIO_QCELP = "audio/qcelp";
const char *MEDIA_MIMETYPE_AUDIO_VORBIS = "audio/vorbis";
const char *MEDIA_MIMETYPE_AUDIO_OPUS = "audio/opus";
const char *MEDIA_MIMETYPE_AUDIO_G711_ALAW = "audio/g711-alaw";
const char *MEDIA_MIMETYPE_AUDIO_G711_MLAW = "audio/g711-mlaw";
const char *MEDIA_MIMETYPE_AUDIO_RAW = "audio/raw";
const char *MEDIA_MIMETYPE_AUDIO_FLAC = "audio/flac";
const char *MEDIA_MIMETYPE_AUDIO_AAC_ADTS = "audio/aac-adts";
const char *MEDIA_MIMETYPE_AUDIO_MSGSM = "audio/gsm";
const char *MEDIA_MIMETYPE_AUDIO_AC3 = "audio/ac3";

const char *MEDIA_MIMETYPE_CONTAINER_MPEG4 = "video/mp4";
const char *MEDIA_MIMETYPE_CONTAINER_WAV = "audio/x-wav";
const char *MEDIA_MIMETYPE_CONTAINER_OGG = "application/ogg";
const char *MEDIA_MIMETYPE_CONTAINER_MATROSKA = "video/x-matroska";
const char *MEDIA_MIMETYPE_CONTAINER_MPEG2TS = "video/mp2ts";
const char *MEDIA_MIMETYPE_CONTAINER_AVI = "video/avi";
const char *MEDIA_MIMETYPE_CONTAINER_MPEG2PS = "video/mp2p";

#ifdef MTK_AOSP_ENHANCEMENT
#ifdef MTK_ELEMENT_STREAM_SUPPORT
const char *MEDIA_MIMETYPE_ELEMENT_STREAM = "video/es";
#endif
#endif

const char *MEDIA_MIMETYPE_CONTAINER_WVM = "video/wvm";

const char *MEDIA_MIMETYPE_TEXT_3GPP = "text/3gpp-tt";
#ifdef MTK_SUBTITLE_SUPPORT
const char *MEDIA_MIMETYPE_TEXT_ASS = "text/ass";
const char *MEDIA_MIMETYPE_TEXT_SSA = "text/ssa";
const char *MEDIA_MIMETYPE_TEXT_TXT = "text/txt";
const char *MEDIA_MIMETYPE_TEXT_VOBSUB = "text/vobsub";
const char *MEDIA_MIMETYPE_TEXT_DVB	=  "text/dvb";
#endif
const char *MEDIA_MIMETYPE_TEXT_SUBRIP = "application/x-subrip";
#ifdef MTK_SUBTITLE_SUPPORT
const char *MEDIA_MIMETYPE_TEXT_SUBASS = "application/x-subtitle-ass";
const char *MEDIA_MIMETYPE_TEXT_SUBSSA = "application/x-subtitle-ssa";
const char *MEDIA_MIMETYPE_TEXT_SUBTXT = "application/x-subtitle-txt";
const char *MEDIA_MIMETYPE_TEXT_SUBMPL = "application/x-subtitle-mpl";		//MPL
const char *MEDIA_MIMETYPE_TEXT_SUBSMI = "application/x-subtitle-smi";	//SMI
const char *MEDIA_MIMETYPE_TEXT_SUB = "application/x-subtitle-sub";	//SUB
const char *MEDIA_MIMETYPE_TEXT_SUBIDX = "application/x-subtitle-idx";
#endif
const char *MEDIA_MIMETYPE_TEXT_VTT = "text/vtt";
const char *MEDIA_MIMETYPE_TEXT_CEA_608 = "text/cea-608";

#ifdef MTK_AOSP_ENHANCEMENT  
const char *MEDIA_MIMETYPE_APPLICATION_SDP = "application/sdp";

const char *MEDIA_MIMETYPE_CONTAINER_ASF = "video/asfff";

const char *MEDIA_MIMETYPE_VIDEO_MJPEG = "video/x-motion-jpeg";
const char *MEDIA_MIMETYPE_VIDEO_WMV = "video/x-ms-wmv";
const char *MEDIA_MIMETYPE_AUDIO_WMA = "audio/x-ms-wma";  //maybe other contains with this codec
const char *MEDIA_MIMETYPE_AUDIO_WMAPRO = "audio/x-ms-wmapro";  //maybe other contains with this codec
const char *MEDIA_MIMETYPE_VIDEO_SORENSON_SPARK = "video/flv1";

const char *MEDIA_MIMETYPE_AUDIO_APE = "audio/ape";

const char *MEDIA_MIMETYPE_AUDIO_ALAC = "audio/alac";

const char *MEDIA_MIMETYPE_CONTAINER_FLV = "video/x-flv";


const char *MEDIA_MIMETYPE_CONTAINER_OGM = "video/ogm";


const char *MEDIA_MIMETYPE_VIDEO_FLV = "video/x-flv";
const char *MEDIA_MIMETYPE_AUDIO_FLV = "audio/x-flv"; //maybe other contains with this codec

const char *MEDIA_MIMETYPE_AUDIO_MS_ADPCM = "audio/x-adpcm-ms";
const char *MEDIA_MIMETYPE_AUDIO_DVI_IMA_ADPCM = "audio/x-adpcm-dvi-ima";

#ifdef MTK_AUDIO_DDPLUS_SUPPORT
const char *MEDIA_MIMETYPE_AUDIO_EAC3 = "audio/eac3";
const char *MEDIA_MIMETYPE_AUDIO_AC3 = "audio/ac3";
#endif
#endif // #ifdef MTK_AOSP_ENHANCEMENT
}  // namespace android
