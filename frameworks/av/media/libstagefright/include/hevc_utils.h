#ifndef HEVC_UTILS_H_
#define HEVC_UTILS_H_

#include <media/stagefright/foundation/ABuffer.h>
#include <utils/Errors.h>

namespace android {

#if 0
enum {
    kHEVCProfileMain          = 0x01,
    kHEVCProfileMain10        = 0x02,
    kHEVCProfileMainPicture   = 0x03,
};

const char *HEVCProfileToString(uint8_t profile);
#endif
void findHEVCSPSInfo(uint8_t *sps, unsigned spsLen, unsigned * spsWidth, unsigned * spsHeight);
sp<MetaData> MakeHEVCMetaData(const sp<ABuffer> &accessUnit);

} // namespace android
#endif  // HEVC_UTILS_H_