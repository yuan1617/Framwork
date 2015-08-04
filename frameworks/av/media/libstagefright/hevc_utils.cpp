/*
*/

//#define LOG_NDEBUG 0
#define LOG_TAG "hevc_utils"
#include <utils/Log.h>

#include <media/stagefright/foundation/ABitReader.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MetaData.h>

#include "include/hevc_utils.h"
#include "include/avc_utils.h"

namespace android {

#if 0
const char *HEVCProfileToString(uint8_t profile) {
    switch (profile) {
        case kHEVCProfileMain:
            return "Main";
        case kHEVCProfileMain10:
            return "Main 10";
        case kHEVCProfileMainPicture:
            return "Main Still Picture";
        default:   return "Unknown";
    }
}
#endif
 
void parseProfileTierLevel(ABitReader *br, unsigned subLayers) {

    //br->skipBits(120);//24 40 48 56  64 72

    br->getBits(2);//unsigned profile_space =
    br->getBits(1);//unsigned tier_flag = 
    br->getBits(5);//unsigned profile_idc = 
    for (int j = 0; j < 32; j++) {
        br->getBits(1);
    }
    br->getBits(4);//progressive_flag...only_constraint_flag
    br->skipBits(44);//reserved_zero_44bits

    unsigned general_level_idc = br->getBits(8);

    unsigned profilePresent[8];
    unsigned levelPresent[8];
    for (int i = 0; i < subLayers; i++) {
        profilePresent[i] = br->getBits(1);
        levelPresent[i] = br->getBits(1);	
    }
    if (subLayers > 0) {
        for (int i = subLayers; i < 8; i++) {
            unsigned reserved_zero_2bits = br->getBits(2);
            CHECK_EQ(reserved_zero_2bits, 0);
        }
    }
    for (int i = 0; i < subLayers; i++) {
        if (profilePresent[i]) {
            br->skipBits(88);
        }
        if (levelPresent[i]) {
            br->skipBits(8);
        }
    }
    return ;
}

static sp<ABuffer> FindHEVCNALNew(
        const uint8_t *data, size_t size, unsigned nalType,
        size_t *stopOffset) {
    const uint8_t *nalStart;
    size_t nalSize;
    while (getNextNALUnit(&data, &size, &nalStart, &nalSize, true) == OK) {
        if (((nalStart[0] & 0x7E)>>1) == nalType) {
            sp<ABuffer> buffer = new ABuffer(nalSize);
            memcpy(buffer->data(), nalStart, nalSize);
            return buffer;
        }
    }

    return NULL;
}

/*turn 00 00 03 to 00 00*/
status_t adjustSPS(uint8_t *sps, unsigned *spsLen) {
    uint8_t *data = sps;
    size_t  size = *spsLen;
    size_t  offset = 0;

    while (offset + 2 <= size) {
        if (data[offset] == 0x00 && data[offset+1] == 0x00 && data[offset+2] == 0x03) {
            //found 00 00 03
            if (offset + 2 == size) {//00 00 03 as suffix
                *spsLen -=1;
                return OK;
            }

            offset += 2; //point to 0x03
            memcpy(data+offset, data+(offset+1), size - offset);//cover ox03

            size -= 1;
            *spsLen -= 1;
            continue;
        }
        ++offset;
    }

    return OK;
}

void findHEVCSPSInfo(uint8_t *sps, unsigned spsLen, unsigned * spsWidth, unsigned * spsHeight) {
	
    sps += 2;
    spsLen -= 2;
    adjustSPS(sps, &spsLen);//clear emulation_prevention_three_byte
    ABitReader br(sps, spsLen);//no nalheader
    //clear emulation_prevention_three_byte
    br.skipBits(4);//sps_video_parameter_set_id
    unsigned subLayers = br.getBits(3);//sub layers num minus 1
    br.skipBits(1);

    //profile_tier_level
    parseProfileTierLevel(&br, subLayers);

    unsigned sps_seq_parameter_set_id = parseUE(&br);//sps_seq_parameter_set_id
    unsigned chroma_format_idc =  parseUE(&br);
    if (chroma_format_idc == 3) {
        br.skipBits(1);
    }
    *spsWidth = parseUE(&br);
    *spsHeight = parseUE(&br);
    ALOGD("[HEVC:SPS]subLayers:%u, sps_seq_parameter_set_id:%u, chroma_format_idc:%u, Width:%u, Height:%u", 
        subLayers, sps_seq_parameter_set_id, chroma_format_idc,
        *spsWidth, *spsHeight);
	return ;
}

sp<MetaData> MakeHEVCMetaData(const sp<ABuffer> &accessUnit) {
    const uint8_t *data = accessUnit->data();
    size_t size = accessUnit->size();
    size_t numOfParamSets = 0;
    const uint8_t SPS_NAL_TYPE = 33;
	const uint8_t PPS_NAL_TYPE = 34;

    sp<ABuffer> seqParamSet = FindHEVCNALNew(data, size, 33, NULL);//start after00000001
    if (seqParamSet != NULL) {
        numOfParamSets++;
        ALOGI("find sps, size =%d",seqParamSet->size());
    }

    sp<ABuffer> picParamSet = FindHEVCNALNew(data, size, 34, NULL);
    if (picParamSet != NULL) {
		numOfParamSets++;
		ALOGI("find pps, size =%d",picParamSet->size());
	}
    if (seqParamSet == NULL || seqParamSet == NULL) {
        ALOGE("[HEVC:SPS] no meta data");
        return NULL;
    }
    int32_t numbOfArrays = numOfParamSets;
    int32_t paramSetSize = 0;
    unsigned spsLen = seqParamSet->size();
    unsigned ppsLen = picParamSet->size();	
    if(seqParamSet != NULL){
            paramSetSize += 1 + 2 + 2 + seqParamSet->size();
        }
        if(picParamSet != NULL){
            paramSetSize += 1 + 2 + 2 + picParamSet->size();
        }
    size_t csdSize =
                1 + 1 + 4 + 6 + 1 + 2 + 1 + 1 + 1 + 1 + 2 + 1 
                + 1 + paramSetSize;
    sp<ABuffer> csd = new ABuffer(csdSize);

    //sp<ABuffer> csd = new ABuffer((spsLen + ppsLen + 33)*sizeof(uint8_t));
    uint8_t *out = csd->data();

    ALOGD("[HEVC:SPS] MakeHEVCMetaData AU size:%d, sps size:%d, pps size:%d, csd size:%d",accessUnit->size(),seqParamSet->size(),picParamSet->size(),csd->size());

    uint8_t* sps = seqParamSet->data();
    uint8_t* pps = picParamSet->data();
#if 1
    *out++ = 0x01;	// configurationVersion
	
	/*copy profile_tier_leve info in sps, containing
	1 byte:general_profile_space(2),general_tier_flag(1),general_profile_idc(5)
	4 bytes: general_profile_compatibility_flags, 6 bytes: general_constraint_indicator_flags
	1 byte:general_level_idc
	*/
	memcpy(out,seqParamSet->data() + 3, 1 + 4 + 6 + 1);
	
	out += 1 + 4 + 6 + 1;

	*out++ = 0xf0; //reserved(1111b) + min_spatial_segmentation_idc(4)
	*out++ = 0x00;// min_spatial_segmentation_idc(8) 
	*out++ = 0xfc; // reserved(6bits,111111b) + parallelismType(2)(0=unknow,1=slices,2=tiles,3=WPP)
	*out++ = 0xfd; //reserved(6bits,111111b)+chromaFormat(2)(0=monochrome, 1=4:2:0, 2=4:2:2, 3=4:4:4)

	*out++ = 0xf8;//reserved(5bits,11111b) + bitDepthLumaMinus8(3)
	*out++ = 0xf8;//reserved(5bits,11111b) + bitDepthChromaMinus8(3)
	
	uint16_t avgFrameRate = 0;
	*out++ = avgFrameRate >> 8; // avgFrameRate (16bits,in units of frames/256 seconds,0 indicates an unspecified average frame rate)
	*out++ = avgFrameRate & 0xff;

	*out++ = 0x03;//constantFrameRate(2bits,0=not be of constant frame rate),numTemporalLayers(3bits),temporalIdNested(1bits),
				 //lengthSizeMinusOne(2bits)

	*out++ = numbOfArrays;//numOfArrays

	if(seqParamSet != NULL){
		
		*out++ = 0x3f & SPS_NAL_TYPE; //array_completeness(1bit)+reserved(1bit,0)+NAL_unit_type(6bits)
		
		//num of sps
		uint16_t numNalus = 1;
		*out++ = numNalus >> 8;
		*out++ = numNalus & 0xff;

		//sps nal length
		*out++ = seqParamSet->size() >> 8;
		*out++ = seqParamSet->size() & 0xff;

		memcpy(out,seqParamSet->data(),seqParamSet->size());
		out += seqParamSet->size();

	}
	if(picParamSet != NULL){
	
		*out++ = 0x3f & PPS_NAL_TYPE; //array_completeness(1bit)+reserved(1bit,0)+NAL_unit_type(6bits)
		
		//num of pps
		uint16_t numNalus = 1;
		*out++ = numNalus >> 8;
		*out++ = numNalus & 0xff;

		//pps nal length
		*out++ = picParamSet->size() >> 8;
		*out++ = picParamSet->size() & 0xff;

		memcpy(out,picParamSet->data(),picParamSet->size());
		//no need add out offset
	}



#else
    out[0] = 1; // configurationVersion == 1
    out[1] = sps[1]; // profile_space:2 bits, tier_flag:1bits, profile_idc:5 bits
    /* 4 bytes profile_compability_indications */
    out[2] = 0;
    out[3] = 0;
    out[4] = 0;
    out[5] = 0;
    /* 2 bytes constraint_indicator_flags */
    out[6] = 0;
    out[7] = 0;
    out[8] = sps[ 3]; // level_idc   [OK?]
    out[9] = 0; //min_spatial_segmentation_idc
    out[10] = 0b11111100; //reserved 111111 + parallismType 2 bits
    out[11] = 0b11111101; //reserved  111111 + chromaFormat 2 bits->01: 4:2:0
    out[12] = 0b11111000; //reserved 11111 + bitDepthLumaMinus8 3bits
    out[13] = 0b11111000; //reserved 111111 + bitDepthChromaMinus8 3 bits
    /* 2 bytes avgFrameRate */ 
    out[14] = 0; // constantFrameRate: 2 bits, numTemporalLayers: 3 bits, temporalIdNested: 1 bit, 
    out[15] = 0; 
    //out[16] = (sps_len + pps_len) & 0b00000011; //lengthSizeMinusOne
    out[16] = 0xff; //lengthSizeMinusOne
    out[17] = 2; //numOfArrays
    /* add sps to array */
    out[18] = 0b10100000; //vps
    /* 2 bytes numOfNalus */
    out[19] = 0;
    out[20] = 1; 
    /* 2 bytes nalUnitLength */
    out[21] = (spsLen>>8) & 0b11111111;
    out[22] = spsLen & 0b11111111;
    memcpy(out + 23, sps , spsLen);
    /* add pps to array */
    out[23 + spsLen] = 0b10100001; //sps
    /* 2 bytes numOfNalus */
    out[24 + spsLen] = 0;
    out[25 + spsLen] = 1; 
    /* 2 bytes nalUnitLength */
    out[26 + spsLen] = (ppsLen >> 8) & 0b11111111;
    out[27 + spsLen] = ppsLen & 0b11111111;
    memcpy(out + 28 + spsLen, pps, ppsLen);
#endif
	unsigned pic_width_in_luma_samples;
	unsigned pic_height_in_luma_samples;

	findHEVCSPSInfo(sps, spsLen, &pic_width_in_luma_samples, &pic_height_in_luma_samples);
	
    sp<MetaData> meta = new MetaData;
    meta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_VIDEO_HEVC);
    meta->setData(kKeyHVCC, kTypeHVCC, csd->data(), csd->size());
    meta->setInt32(kKeyWidth, pic_width_in_luma_samples);
    meta->setInt32(kKeyHeight, pic_height_in_luma_samples);

    return meta;
}

}  // namespace android
