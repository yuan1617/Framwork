#ifdef MTK_SUBTITLE_SUPPORT
#define LOG_TAG "TimedTextVOBSUBSource"
#include <sys/stat.h>
#include <utils/Log.h>

#include "TimedTextVOBSubtitleParser.h"
typedef unsigned int UINT32;
typedef unsigned char UINT8;

namespace android
{
const int BUFFER_READY_LENGTH = 7;
const unsigned char BUFFER_READY[BUFFER_READY_LENGTH] = {'B', 'u', 'f', 'f', 'R', 'd', 'y'}; 

void memset32(UINT32 *pu4Dst, UINT32 ui4Value, UINT32 z_l)
{
    for (; (z_l % 4) != 0; z_l--)
    {
        *pu4Dst++ = ui4Value;
    }
    for (; z_l != 0; z_l -= 4)
    {
        pu4Dst[0] = ui4Value;
        pu4Dst[1] = ui4Value;
        pu4Dst[2] = ui4Value;
        pu4Dst[3] = ui4Value;
        pu4Dst += 4;
    }

    //HalFlushInvalidateDCache();
}

static void vEvDvdspuDecodeD8888(UINT32 u4Width, UINT32 u4Height, UINT8 *pu1Src, UINT32 *pu4Dst, UINT32 *pu4Cpt, UINT32 u4Pitch)
{
    for (; u4Height != 0; u4Height--)
    {
        UINT8 b = 0;
        bool nibble = false;
        UINT32 x = 0;
        while (x < u4Width)
        {
            UINT32 n, p;
            if (nibble)
            {
                b &= 0x0F;
                if (b >= 0x04)
                {
                    n = b >> 2;
                    p = b & 3;
                    nibble = false;
                }
                else if (b >= 0x01)
                {
                    n = b << 2;
                    b = *pu1Src++;
                    n |= b >> 6;
                    p = b >> 4 & 3;
                }
                else
                {
                    b = *pu1Src++;
                    if (b >= 0x40)
                    {
                        n = b >> 2;
                        p = b & 3;
                        nibble = false;
                    }
                    else if (b >= 10)
                    {
                        n = b << 2;
                        b = *pu1Src++;
                        n |= b >> 6;
                        p = b >> 4 & 3;
                    }
                    else
                    {
                        n = u4Width - x;
                        p = *pu1Src++ >> 4;
                    }
                }
            }
            else
            {
                b = *pu1Src++;
                if (b >= 0x40)
                {
                    n = b >> 6;
                    p = b >> 4 & 3;
                    nibble = true;
                }
                else if (b >= 0x10)
                {
                    n = b >> 2;
                    p = b & 3;
                }
                else if (b >= 0x04)
                {
                    n = b << 2;
                    b = *pu1Src++;
                    n |= b >> 6;
                    p = b >> 4 & 3;
                    nibble = true;
                }
                else if (b >= 0x01)
                {
                    n = b << 6;
                    b = *pu1Src++;
                    n |= b >> 2;
                    p = b & 3;
                }
                else
                {
                    n = u4Width - x;
                    p = *pu1Src++;
                }
            }
            p = pu4Cpt[p];
            if (n > u4Width - x)
            {
                n = u4Width - x;
            }
            memset32(&pu4Dst[x], p, n);
            x += n;
        }
        pu4Dst += u4Pitch / 4;
    }

    //HalFlushInvalidateDCache();
}

VOBSubtitleParser::VOBSubtitleParser()
{
    mCurrTmpFileIdx = 0;
    m_aiPalette[0] = 0xb48080;
    m_aiPalette[1] = 0x248080;
    m_aiPalette[2] = 0x628080;
    m_aiPalette[3] = 0xd78080;
    m_aiPalette[4] = m_aiPalette[5] = m_aiPalette[6] = m_aiPalette[7] = 0x808080;
    m_aiPalette[8] = m_aiPalette[9] = m_aiPalette[10] = m_aiPalette[11] = 0x808080;
    m_aiPalette[12] = m_aiPalette[13] = m_aiPalette[14] = m_aiPalette[15] = 0x808080;
}

VOBSubtitleParser::
VOBSubtitleParser(char *data, int size)
{
    m_pcBuffer = data;
    m_iSize = size;
    m_aiPalette[0] = 0xb48080;
    m_aiPalette[1] = 0x248080;
    m_aiPalette[2] = 0x628080;
    m_aiPalette[3] = 0xd78080;
    m_aiPalette[4] = m_aiPalette[5] = m_aiPalette[6] = m_aiPalette[7] = 0x808080;
    m_aiPalette[8] = m_aiPalette[9] = m_aiPalette[10] = m_aiPalette[11] = 0x808080;
    m_aiPalette[12] = m_aiPalette[13] = m_aiPalette[14] = m_aiPalette[15] = 0x808080;
    
    m_iSubtitlePacketSize = readBigEndian(data);
    m_iDataPacketSize = readBigEndian(data + 2);
    m_fgParseFlag = false;
    for (int i=0;i < VOB_TMP_FILE_COUNT; i++)m_pvBitmapData[i] = NULL;
    ALOGE("[RY] subtitle packet size %d, data packet size %d, orig size %d\n", m_iSubtitlePacketSize, m_iDataPacketSize, size);
}

VOBSubtitleParser::
~VOBSubtitleParser()
{
}


void VOBSubtitleParser::vSetPalette(const VOB_SUB_PALETTE palette)
{
    memcpy(m_aiPalette, palette, sizeof(VOB_SUB_PALETTE));
    for (int i = 0; i < VOB_PALETTE_SIZE; i ++)
    {
        ALOGE("m_aiPalette[%d] is %x\n", i, m_aiPalette[i]);
    }
    ALOGE("Set VOB subtitle palette done\n");
}


void VOBSubtitleParser::
vReset()
{
    if (m_pcBuffer != NULL)
    {
        //delete m_pcBuffer;
        m_pcBuffer = NULL;
    }
    m_iSize = 0;
}

status_t VOBSubtitleParser::
stParseControlPacket()
{
    status_t status = -1;
    int controlSeqOffset =  m_iDataPacketSize;
    int lastSeqOffset = 0;
    do
    {
        lastSeqOffset = controlSeqOffset;
        ALOGE("[RY] parsing sequence head from offset %x\n", controlSeqOffset);
        status = stParseControlSequence(controlSeqOffset);
        ALOGE("[RY] status is %d, updated offset is 0x%x\n", status, controlSeqOffset);
        ALOGE("[RY] last seqoffset is 0x%x\n", lastSeqOffset);
    }
    while (status == OK && controlSeqOffset != lastSeqOffset);
    
    ALOGE("out of while loop\n");
    return status;
}

status_t VOBSubtitleParser::
stParseControlSequence(int & seqStartOffset)
{
    status_t status = -1;
    char * currentPointer = m_pcBuffer + seqStartOffset;

    //get date information of control sequence
    int date = readBigEndian(currentPointer);
    currentPointer += 2;
    ALOGE("data is %x\n", date);

    //get next control sequence offset
    int nextCtrlSeqOffset = readBigEndian(currentPointer);
    currentPointer += 2;

    //parsing control command

    char palette[4] = {0, 0, 0, 0};
    char alpha[4] = {0, 0, 0, 0};

    short X1 = 0;
    short X2 = 0;
    short Y1 = 0;
    short Y2 = 0;

    short evenLineStartOffset = 0;
    short oddLineStartOffset = 0;

    int xPositionCode = 0;
    int yPositionCode = 0;

    ctrl_seq_type type = ctrl_seq_type_none;

    bool paletteCmdDetected = false;
    bool alphaCmdDetected = false;
    bool coordinateCmdDetected = false;
    bool offsetCmdDetected = false;
    bool parseEnd = false;
    do
    {
        switch (*(currentPointer ++))
        {
        case 0x00:      //forced displaying, current unused
            break;
        case 0x01:      //start date
            type = ctrl_seq_type_start;
            break;
        case 0x02:      //end date
            type = ctrl_seq_type_end;
            break;
        case 0x03:      //palette
            palette[0] = (*currentPointer & 0xF0)>>4;
            palette[1] = *currentPointer & 0x0F;
            palette[2] = (*(currentPointer + 1) & 0xF0)>>4;
            palette[3] = *(currentPointer + 1) & 0x0F;

            paletteCmdDetected = true;

            currentPointer += 2;
            break;
        case 0x04:      //alpha channel
            alpha[0] = (*currentPointer & 0xF0)>>4;
            alpha[1] = *currentPointer & 0x0F;
            alpha[2] = (*(currentPointer + 1) & 0xF0)>>4;
            alpha[3] = *(currentPointer + 1) & 0x0F;

            alphaCmdDetected = true;
            currentPointer += 2;
            break;
        case 0x05:      //coordinates for subtitle on screen
            xPositionCode = readBigEndian(currentPointer, 3);
            X1 = (xPositionCode & 0x00FFF000) >> 12;
            X2 = (xPositionCode & 0x00000FFF);

            yPositionCode = readBigEndian(currentPointer + 3, 3);
            Y1 = (yPositionCode & 0x00FFF000) >> 12;
            Y2 = (yPositionCode & 0x00000FFF);

            coordinateCmdDetected = true;
            currentPointer += 6;
            break;
        case 0x06:      //RLE offset: even line start offset and odd line start offset
            evenLineStartOffset = (short)readBigEndian(currentPointer);
            oddLineStartOffset = (short)readBigEndian(currentPointer + 2);

            offsetCmdDetected = true;
            currentPointer += 4;
            break;
        case 0xff:      //end command
            parseEnd = true;
            status = OK;
            break;
        default:
            parseEnd = true;
            status = -1;
            break;
        }
    }
    while (currentPointer < (m_pcBuffer + m_iSubtitlePacketSize) && !parseEnd);

    if (OK == status)
    {
        if (seqStartOffset == nextCtrlSeqOffset)
        {
            ALOGE("[RY] it is the last control sequence, don't update seqstartoffset\n");
        }
        else
        {
            seqStartOffset = currentPointer - m_pcBuffer;
        }

        if (paletteCmdDetected && alphaCmdDetected)
        {
            //for (int i = 0; i < 4; i++)
            {
                //ALOGE("Input PaletteIdx is %d, alpha is %d", (unsigned char)palette[i], (unsigned char)alpha[i]);
                
                //ALOGE("Color[%d] : A %x R %x G %x B %x", i, m_rColor[i].a, m_rColor[i].r, m_rColor[i].g, m_rColor[i].b);
                vGenerateSubColorInfo((unsigned char)palette[0], (unsigned char)alpha[0], m_rColor[3]);
                vGenerateSubColorInfo((unsigned char)palette[1], (unsigned char)alpha[1], m_rColor[2]);
                vGenerateSubColorInfo((unsigned char)palette[2], (unsigned char)alpha[2], m_rColor[1]);
                vGenerateSubColorInfo((unsigned char)palette[3], (unsigned char)alpha[3], m_rColor[0]);
            }
        }


        if (coordinateCmdDetected)
        {
            m_acDisplayRange[0] = X1;
            m_acDisplayRange[1] = X2;
            m_acDisplayRange[2] = Y1;
            m_acDisplayRange[3] = Y2;

            ALOGE("DisplayRange %x %x %x %x\n", m_acDisplayRange[0], m_acDisplayRange[1], m_acDisplayRange[2], m_acDisplayRange[3]);

            m_iSubWidth = X2 - X1 + 1;
            m_iSubHeight = Y2 - Y1 + 1;
            
            ALOGE("SubWidth = %d, SubHeight = %d\n", m_iSubWidth, m_iSubHeight);
            #define ROUND_16(X)     ((X + 0xF) & (~0xF))
            m_iBitmapWidth = ROUND_16(m_iSubWidth);
            m_iBitmapHeight = ROUND_16(m_iSubHeight);

            ALOGE("Width = %d, Height = %d\n", m_iBitmapWidth, m_iBitmapHeight);
        }
        if (offsetCmdDetected)
        {
            m_iEvenLineStart = evenLineStartOffset;
            m_iOddLineStart = oddLineStartOffset;

            ALOGE("m_iEvenLineStart offset is %x\n", m_iEvenLineStart);
            ALOGE("m_iOddLineStart offset is %x\n", m_iOddLineStart);
        }
        
        if (ctrl_seq_type_start == type)
        {
            m_iBeginTime = date;
            ALOGE("start date is 0x%x\n", date);
        }
        else if (ctrl_seq_type_end == type)
        {
            m_iEndTime = date;
            ALOGE("end date is 0x%x\n", date);
        }

    }
    // TODO: error handle here
    else
    {

    }
    return status;

}

status_t VOBSubtitleParser::
stParseDataPacket(const void *dataPacketBuffer, int dataPackageSize)
{
    int evenLineOffset = m_iEvenLineStart;
    int oddLineOffset = m_iOddLineStart;
    int totalLineCount = m_iSubHeight;
    int lineNum = 0;
    if (m_iDataPacketSize <= 4)
    {
        ALOGE("Incorrect Data Packet Length %d, return!\n", m_iDataPacketSize);
        return -1;
    }
    ALOGE("+++stParseDataPacket  enter!\n");
    //prepare map data;
    //ALOGE("m_pvBitmapData is 0x%x\n", m_pvBitmapData);
    char * bitmapBuffer = (char *)m_pvBitmapData[getTmpFileIdx()];
    //ALOGV("bitmapBuffer is %p,tmp file index = %d\n", bitmapBuffer,getTmpFileIdx());
    
#if 0
        static int dumpcount = 0;
    
        if (dumpcount < 0x10)
        {
            char x[40];
            sprintf(x, "/sdcard/DumpedvobFile_%d_%d_%d.tmp", dumpcount,evenLineOffset,oddLineOffset);
            FILE * f = fopen(x, "w");
            fwrite(m_pcBuffer, 1,m_iDataPacketSize, f);
            fclose(f);
            dumpcount ++;
        }
#endif
#if 0
    UINT8* topNibbles = new UINT8[(oddLineOffset - evenLineOffset) * 2];
    UINT8* bottomNibbles = new UINT8[(m_iDataPacketSize - oddLineOffset) * 2];

    UINT8* ptrTop = topNibbles;
    UINT8* ptrBottom = bottomNibbles;
    UINT8* ptrUINT8Buffer = (UINT8*)m_pcBuffer;
    {
        unsigned char* srcTop = ptrUINT8Buffer + evenLineOffset;
        unsigned char* nibbledTop = ptrTop;
        for(int count = oddLineOffset - evenLineOffset; count != 0; count--)
        {
            *nibbledTop++ = (UINT8)(*srcTop >> 4);
            *nibbledTop++ = (UINT8)(*srcTop++ & 0x0f);
        }

        UINT8* srcBottom = ptrUINT8Buffer + oddLineOffset;
        UINT8* nibbledBottom = ptrBottom;
        for(int count = m_iDataPacketSize - oddLineOffset; count != 0; count--)
        {
            *nibbledBottom++ = (UINT8)(*srcBottom >> 4);
            *nibbledBottom++ = (UINT8)(*srcBottom++ & 0x0f);
        }

        nibbledTop = ptrTop;
        nibbledBottom = ptrBottom;

        UINT32* lineStart = (UINT32 *)m_pvBitmapData[getTmpFileIdx()];
        //int* lineStart = (int*)outBuffer;
        for(int line = 0; line < m_iSubHeight; line++)
        {
            UINT32* dest = lineStart;
            int UINT8sLeft = m_iSubWidth;
            int colorIndex = 0;
            while(UINT8sLeft != 0)
            {
                int count;
                UINT8* savedNibbleTop = nibbledTop;
                if((*(UINT32*)(nibbledTop) & 0xc0f0f0f) == 0)
                {
                    count = UINT8sLeft;
                    colorIndex = *(nibbledTop + 3) & 0x3;
                    nibbledTop += 4;
                }
                else
                {
                    count = *nibbledTop & 0xc;
                    if(count != 0)
                    {
                        count >>= 2;
                        colorIndex = *nibbledTop++ & 0x3;
                    }
                    else
                    {
                        count = *nibbledTop++;
                        if(count != 0)
                        {
                            count <<= 2;
                            colorIndex = *nibbledTop++;
                            count += colorIndex >> 2;
                            colorIndex &= 0x3;
                        }
                        else
                        {
                            count = *nibbledTop++;
                            if(count >= 0x4)
                            {
                                count <<= 2;
                                colorIndex = *nibbledTop++;
                                count += colorIndex >> 2;
                                colorIndex &= 0x3;
                            }
                            else
                            {
                                count <<= 6;
                                count += (*nibbledTop++) << 2;
                                colorIndex = *nibbledTop++;
                                count += colorIndex >> 2;
                                colorIndex &= 0x3;
                            }
                        }
                    }
                }

                //int color = bmpPalette[colorIndex].ToArgb();
                UINT32 color = (m_rColor[(UINT8)colorIndex].a << 24) +
						(m_rColor[(UINT8)colorIndex].r << 16) +
						(m_rColor[(UINT8)colorIndex].g << 8) +
						(m_rColor[(UINT8)colorIndex].b);
                UINT8sLeft -= count;
                if (-1 == count || count > UINT8sLeft)
                {
                    //printf("color to zero:\n");
                    color = 0;
                }
                while(count != 0)
                {
                    *dest++ = color;
                    count--;
                }
            }
            nibbledTop += (((long)nibbledTop) & 1);
            ///printf("nibbledTop - ptrTop:%d\n",nibbledTop - ptrTop);
            
            if(++line == m_iSubHeight)
            {
                break;
            }

            lineStart += m_iBitmapWidth;
            dest = lineStart;

            UINT8sLeft = m_iSubWidth;
            colorIndex = 0;
            while(UINT8sLeft != 0)
            {
                int count;
                UINT8* savedNibbleBottom = nibbledBottom;
                if((*(UINT32*)(nibbledBottom) & 0xc0f0f0f) == 0)
                {
                    count = UINT8sLeft;
                    colorIndex = *(nibbledBottom + 3) & 0x3;
                    nibbledBottom += 4;
                }
                else
                {
                    count = *nibbledBottom & 0xc;
                    if(count != 0)
                    {
                        count >>= 2;
                        colorIndex = *nibbledBottom++ & 0x3;
                    }
                    else
                    {
                        count = *nibbledBottom++;
                        if(count != 0)
                        {
                            count <<= 2;
                            colorIndex = *nibbledBottom++;
                            count += colorIndex >> 2;
                            colorIndex &= 0x3;
                        }
                        else
                        {
                            count = *nibbledBottom++;
                            if(count >= 0x4)
                            {
                                count <<= 2;
                                colorIndex = *nibbledBottom++;
                                count += colorIndex >> 2;
                                colorIndex &= 0x3;
                            }
                            else
                            {
                                count <<= 6;
                                count += (*nibbledBottom++) << 2;
                                colorIndex = *nibbledBottom++;
                                count += colorIndex >> 2;
                                colorIndex &= 0x3;
                            }
                        }
                    }
                }

                //int color = bmpPalette[colorIndex].ToArgb();
                UINT32 color = (m_rColor[(UINT8)colorIndex].a << 24) +
						(m_rColor[(UINT8)colorIndex].r << 16) +
						(m_rColor[(UINT8)colorIndex].g << 8) +
						(m_rColor[(UINT8)colorIndex].b);;
                UINT8sLeft -= count;
                if (-1 == count || count > UINT8sLeft)
                {
                    //printf("color to zero:\n");
                    color = 0;
                }
                while(count != 0)
                {
                    *dest++ = color;
                    count--;
                }
            }
            nibbledBottom += (((long)nibbledBottom) & 1);
            lineStart += m_iBitmapWidth;
        }
    }

    delete[] topNibbles;
    delete[] bottomNibbles;
#endif
    UINT32 pu4Palette[4]={0xb48080, 0x248080, 0x628080, 0xd78080};
    pu4Palette[0] = (m_rColor[(UINT8)0].a << 24) +
						(m_rColor[(UINT8)0].r << 16) +
						(m_rColor[(UINT8)0].g << 8) +
						(m_rColor[(UINT8)0].b);
    pu4Palette[1] = (m_rColor[(UINT8)1].a << 24) +
						(m_rColor[(UINT8)1].r << 16) +
						(m_rColor[(UINT8)1].g << 8) +
						(m_rColor[(UINT8)1].b);
    pu4Palette[2] = (m_rColor[(UINT8)2].a << 24) +
						(m_rColor[(UINT8)2].r << 16) +
						(m_rColor[(UINT8)2].g << 8) +
						(m_rColor[(UINT8)2].b);
    pu4Palette[3] = (m_rColor[(UINT8)3].a << 24) +
						(m_rColor[(UINT8)3].r << 16) +
						(m_rColor[(UINT8)3].g << 8) +
						(m_rColor[(UINT8)3].b);
    
	UINT32* lineStart = (UINT32 *)m_pvBitmapData[getTmpFileIdx()];
    
    for (int y = 0; y < m_iBitmapHeight; y++)
	{	
		UINT32* 	pui4_pos = (UINT32*)((char*)lineStart + y * (m_iBitmapWidth<< 2));
		for (int x = 0; x < m_iBitmapWidth;x++,pui4_pos++)
		{
			*pui4_pos = 0x00000000;
		}
	}
    
	vEvDvdspuDecodeD8888(m_iSubWidth,m_iSubHeight/2,(UINT8 *)m_pcBuffer+m_iEvenLineStart,lineStart,pu4Palette,m_iBitmapWidth*2*4);
	vEvDvdspuDecodeD8888(m_iSubWidth,m_iSubHeight/2,(UINT8 *)m_pcBuffer+m_iOddLineStart,lineStart+m_iBitmapWidth,pu4Palette,m_iBitmapWidth*2*4);
    ALOGV("evenLineOffset is 0x%x,m_iOddLineStart is 0x%x\n", evenLineOffset,m_iOddLineStart);
    ALOGV("oddLineOffset is 0x%x,m_iDataPacketSize is 0x%x\n", oddLineOffset,m_iDataPacketSize);
#if 0
    static int dumpcount1 = 0;

    if (dumpcount1 < 0x100)
    {
        char x[40];
        sprintf(x, "/sdcard/DumpedBmpFile%d_%d_%d.tmp", dumpcount1,m_iBitmapWidth,m_iBitmapHeight);
        FILE * f = fopen(x, "w");
        fwrite(m_pvBitmapData[getTmpFileIdx()], 1,m_iBitmapHeight * m_iBitmapWidth * PIXEL_SIZE, f);
        fclose(f);
        dumpcount1 ++;
    }
#endif
    ALOGE("---stParseDataPacket  exit!\n");
    return OK;
}

status_t VOBSubtitleParser::
stGenerateBitmapLine(int & offset,char * bitmapBuffer)
{
    status_t status;
    bool isNibble = false;
    int remainPixelCount = m_iSubWidth;

    RLECode rleCode;
    rleCode.value = 0;
    rleCode.size = 0;
    char * lineData = new char[m_iBitmapWidth * PIXEL_SIZE];
    for (int i = 0; i < m_iBitmapWidth * PIXEL_SIZE; i++)
    {
        lineData[i] = 0;
    }
    do
    {
        int count = 0;
        int colorIdx = 0;
        int x = offset;
        vReadRLECode(offset, isNibble, rleCode);
        //ALOGE("RLECode at %x, value is %x, size is %d, after that offset is %x\n", x, rleCode.value, rleCode.size, offset);
        status = stParseRLECode(rleCode, count, colorIdx);
        if (OK == status)
        {
            bool fillBlank = false;
            //check count value;
            if (-1 == count || count > remainPixelCount)
            {
                fillBlank = (-1 == count) ? true : false;                
                count = remainPixelCount;
            }

            if (count > remainPixelCount)
            {
                ALOGE("[Ruiiiiii]remain Pixel Count less than draw count\n", remainPixelCount, count);
            }

            for (int i = 0; i < count; i ++)
            {
                lineData[(m_iBitmapWidth - remainPixelCount + i) * PIXEL_SIZE] = fillBlank ? 0 : m_rColor[colorIdx].r;
                lineData[(m_iBitmapWidth - remainPixelCount + i) * PIXEL_SIZE + 1] = fillBlank ? 0 : m_rColor[colorIdx].g;
                lineData[(m_iBitmapWidth - remainPixelCount + i) * PIXEL_SIZE + 2] = fillBlank ? 0 : m_rColor[colorIdx].b;
                lineData[(m_iBitmapWidth - remainPixelCount + i) * PIXEL_SIZE + 3] = fillBlank ? 0 : m_rColor[colorIdx].a;
            }
            remainPixelCount -= count;
        }
        else
        {
            break;
        }
    }
    while (remainPixelCount > 0);

    if (isNibble)
    {
        //ALOGE("After line changed, byte align shall be performed\n");
        offset += 1;
    }
    //write lineData to File
    //ALOGE("Copy Line to File\n");
    if (bitmapBuffer != NULL)
    {
        memcpy(bitmapBuffer, lineData,  m_iBitmapWidth * PIXEL_SIZE);
    }    
    delete [] lineData;
    return OK;
}

int VOBSubtitleParser::
readBigEndian(char *pByte, int bytesCount)
{
    char *pTemp = pByte;
    int sum = 0;
    while (bytesCount-- > 0)
    {
        sum <<= 8; // move 8 bits to left
        sum += (int)(*pTemp);
        //ALOGE("[MY] --- 1 sum = 0x%x, *pTemp = 0x%x", sum, (int)*pTemp);
        ++pTemp;
        //ALOGE("[MY] --- 2 sum = 0x%x, *pTemp = 0x%x", sum, (int)*pTemp);
    }
    return sum;
}


void VOBSubtitleParser::
vReadRLECode(int & offset, bool & isNibble, RLECode & rleCode)
{
    unsigned short code = 0;

    if (!isNibble)
    {
        code = (unsigned short)readBigEndian((char *)(m_pcBuffer + offset));
    }
    else
    {
        char temp = *(m_pcBuffer + offset);
        code += (temp & 0x0f) << 12;

        temp = *(m_pcBuffer + offset + 1);
        code += (temp << 4);

        temp = *(m_pcBuffer + offset + 2);
        code += (temp >> 4);
    }

    if (code & 0xC000)               //RLE alphabet 0xf to 0x4, single nibble
    {
        rleCode.value = code >> 12;
        rleCode.size = 1;
        offset = isNibble ? offset + 1 : offset;
        isNibble = !isNibble;
    }
    else if (code & 0x3000)          //RLE alphabet 0x3- to 0x1- double nibbles
    {
        rleCode.value = code >> 8;
        rleCode.size = 2;
        offset = offset + 1;
    }
    else if (code & 0x0C00)          //RLE alphabet 0x0f- to 0x04-  tripple nibbles
    {
        rleCode.value = code >> 4;
        rleCode.size = 3;
        offset = isNibble ? offset + 2 : offset + 1;
        isNibble = !isNibble;
    }
    else if (code & 0x0300)          //RLE alphabet 0x03-- to 0x01-- four times nibbles
    {
        rleCode.value = code;
        rleCode.size = 4;
        offset += 2;
    }
    /*
    else if (code & 0x00f0)          //RLE alphabet 0x00f- to 0x001- four times nibbles
    {
        // TODO: currently these values are not defined, set RLECode to invalid value
        rleCode.value = (unsigned short)(-1);
        rleCode.size = 4;
        offset += 2;
    }*/
    else                                 //RLE alphabet 0x000- four times nibbles
    {
        rleCode.value = code;
        rleCode.size = 4;
        offset += 2;
    }
}

status_t VOBSubtitleParser::
stParseRLECode(RLECode rleCode, int &count, int &colorIdx)
{
    status_t status;
    //Process Special RLE Code
    /*
    if (4 == rleCode.size && (rleCode.value & 0x00F0))         //RLE alphabet 0x00f- to 0x001-
    {
        // TODO: in DVD subtitle spec, RLE alphabet doesn't contains defination of 0x00f- to 0x001-, now treat as error
        ALOGE("incorrect RLE Code 0x%x!\n");
        count = -1;
        colorIdx = -1;
        status = -1;
    }
    else */
    if (4 == rleCode.size && 0 == (rleCode.value & 0xFFF0))
    {
        count = -1;
        colorIdx = rleCode.value & 0x3;
        status = OK;
    }
    /*
    if (4 == rleCode.size && 0 != rleCode.value)// && 0 == (rleCode.value & 0xFFF0))                        //RLE alphabet 0x000-
    {
        //It is a line change code
        count = rleCode.value >> 2;
        colorIdx = rleCode.value & 0x3;
        status = OK;

    }*/
    else    //other normal case
    {
        count = rleCode.value >> 2;
        colorIdx = rleCode.value & 0x3;
        status = OK;
    }

    return status;
}

status_t VOBSubtitleParser::
stInit()
{
    status_t status = OK;
    m_iSubtitlePacketSize = readBigEndian(m_pcBuffer);
    m_iDataPacketSize = readBigEndian(m_pcBuffer + 2);

    for (int i=0;i < VOB_TMP_FILE_COUNT; i++)m_pvBitmapData[i] = NULL;

    return status;
}

status_t VOBSubtitleParser::
stInit(void *data, int size)
{
    status_t status = OK;
    vUnInit();
    m_pcBuffer = (char *)data;
    m_iSize = size;
    if (m_iSize < 4)
    {
        return -1;
    }
    m_iSubtitlePacketSize = readBigEndian(m_pcBuffer);
    m_iDataPacketSize = readBigEndian(m_pcBuffer + 2);


    return status;
}

status_t VOBSubtitleParser::
stPrepareBitmapBuffer()
{
    char filepath[256];
    //char *filepath = "/sdcard/IdxSubBitmapBuffer_0.tmp";
    ALOGE("[RY] m_iBitmapWidth %d, m_iBitmapHeight %d\n", m_iBitmapWidth, m_iBitmapHeight);
    int bufSize = FILE_BUFFER_SIZE; //m_iBitmapWidth * m_iBitmapHeight;

    ALOGE("[RY] bufSize is %d", bufSize);
    for (int i=0;i < VOB_TMP_FILE_COUNT; i++)
    {
        sprintf(filepath,"/sdcard/IdxSubBitmapBuffer_%d.tmp",i);
        m_iFd[i] = open(filepath, O_CREAT | O_RDWR | O_TRUNC, 0x777);

        if (-1 == m_iFd[i])
        {
            //m_pvBitmapData = NULL;
            ALOGE("open fd:[%s], errno=%d:%s.", filepath, errno, strerror(errno));
            vReset();
            return -1;
        }

        lseek(m_iFd[i], bufSize, SEEK_SET);
        write(m_iFd[i], "", 1);
        ALOGD("[MY] --- start, open fd = 0x%x", m_iFd[i]);
        //m_pvBitmapData = mmap(0, bufSize, PROT_READ | PROT_WRITE, MAP_SHARED | MAP_ANONYMOUS, -1, 0);
        m_pvBitmapData[i] = mmap(0, bufSize, PROT_READ | PROT_WRITE, MAP_SHARED , m_iFd[i], 0);
        if (MAP_FAILED == m_pvBitmapData[i])
        {
            m_pvBitmapData[i] = NULL;
            ALOGE("start, mmap failed, errno=%d:%s.", errno, strerror(errno));
            vReset();
            return -1;
        }
        ALOGD("[MY] --- start, bitmap addr = %p", m_pvBitmapData[i]);
    }
    ALOGE("prepareMapMemory");
    return OK;
}

status_t VOBSubtitleParser::
stUnmapBitmapBuffer()
{
    for (int i=0;i < VOB_TMP_FILE_COUNT; i++)
    {
        if (m_pvBitmapData[i] > 0)
        {
            munmap(m_pvBitmapData[i], FILE_BUFFER_SIZE);
        }
        if (m_iFd[i] > 0)
        {
            close(m_iFd[i]);
        }
    }
    return OK;
}

void VOBSubtitleParser::vUnInit()
{
    m_pcBuffer = NULL;
    m_iSize = 0;


    m_iBitmapWidth = 0;
    m_iBitmapHeight = 0;

    m_iBeginTime = -1;
    m_iEndTime = -1;

    m_iSubtitlePacketSize = 0;
    m_iDataPacketSize = 0;

    memset(m_acPalette, 0, 4 * sizeof(char));
    memset(m_acAlpha, 0, 4 * sizeof(char));
    memset(m_acDisplayRange, 0, 4 * sizeof(short));
    m_iEvenLineStart = 0;
    m_iOddLineStart = 0;

}


void VOBSubtitleParser::vGenerateSubColorInfo(unsigned char paletteIdx, unsigned char alpha, ARGB_8888_Color & rColor)
{
    ALOGE("palette is %d, alpha is %d,m_aiPallete[] is %x\n", paletteIdx, alpha,m_aiPalette[paletteIdx]);
    rColor.a = ucExtendAlphaFrom4bitTo8bit(alpha);
    rColor.r = (unsigned char)((m_aiPalette[paletteIdx] >> 16) & 0xFF);
    rColor.g = (unsigned char)((m_aiPalette[paletteIdx] >> 8) & 0xFF);
    rColor.b = (unsigned char)((m_aiPalette[paletteIdx]) & 0xFF);

    if (0 == rColor.a)
    {
        memset(&rColor, 0, sizeof(ARGB_8888_Color));
        ALOGE("[Ruiiiiiiiiiiiii]set to 0");
    }
}

unsigned char VOBSubtitleParser::ucExtendAlphaFrom4bitTo8bit(unsigned char alpha)
{
    if (alpha > 0xf || 0 == alpha)
    {
        return alpha;
    }
    unsigned int temp = alpha + 1;
    temp = temp * 255 / 16;
    return (unsigned char)temp;
}

bool VOBSubtitleParser::fgIsBufferReady()
{
    bool fgReady = true;
    if(NULL == m_pvBitmapData || !m_fgParseFlag)
    {
        return fgReady;
    }

    fgReady = true;
    unsigned char * temp = (unsigned char *)m_pvBitmapData[0];
    for (int i = 0; i < BUFFER_READY_LENGTH; i ++)
    {
        ALOGE("Buffer[%d] is %x", i, *(temp + i));
        if (BUFFER_READY[i] != *(temp + i))
        {
            fgReady = false;
            break;
        }
    }

    ALOGE("fgReady is %d\n", fgReady);
    return fgReady;

    
}

}  // namespace android

#endif
