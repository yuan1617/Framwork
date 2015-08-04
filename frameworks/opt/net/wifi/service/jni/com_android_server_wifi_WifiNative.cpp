/*
 * Copyright 2008, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "wifiJNI"

#include "jni.h"
#include <ScopedUtfChars.h>
#include <utils/misc.h>
#include <android_runtime/AndroidRuntime.h>
#include <utils/Log.h>
#include <utils/String16.h>
#include <utils/String8.h>
#include <cutils/xlog.h>
#include <pthread.h>
#include <unicode/ucnv.h>
#include <net/if.h>
#include <sys/socket.h>
#include <linux/wireless.h>
#include <cutils/properties.h>
#include <ctype.h>

#include "wifi.h"
#include "wifi_hal.h"
#include "jni_helper.h"

#define REPLY_BUF_SIZE 4096 // wpa_supplicant's maximum size.
#define EVENT_BUF_SIZE 2048

///M: add the following
#define BUF_SIZE 256
#define SSID_LEN 500
#define LINE_LEN 1024
#define CONVERT_LINE_LEN 2048
#define REPLY_LEN 4096
#define CHARSET_CN ("gbk")
#define WIFI_INTERFACE "wlan0"

#define IOCTL_SET_INTS                   (SIOCIWFIRSTPRIV + 12)
#define PRIV_CMD_SET_TX_POWER            25

namespace android {

static jint DBG = false;

struct accessPointObjectItem {
    String8 *ssid;
    String8 *bssid;
    String8 *encossid;
    bool    isCh;
    struct  accessPointObjectItem *pNext;
};

struct accessPointObjectItem *g_pItemList = NULL;
struct accessPointObjectItem *g_pLastNode = NULL;
pthread_mutex_t *g_pItemListMutex = NULL;
String8 *g_pCurrentSSID = NULL;
bool g_isChSSID = false;
bool g_isAnyBSSID = false;

static void addAPObjectItem(const char *ssid, const char *bssid, bool isCh, const char * encossid)
{
    if (NULL == ssid || NULL == bssid || NULL == encossid) {
        XLOGE("ssid or bssid or encossid is NULL");
        return;
    }

    struct accessPointObjectItem *pTmpItemNode = NULL;
    struct accessPointObjectItem *pItemNode = NULL;
    bool foundItem = false;
    pthread_mutex_lock(g_pItemListMutex);
    pTmpItemNode = g_pItemList;
    while (pTmpItemNode) {
        if (pTmpItemNode->bssid && (*(pTmpItemNode->bssid) == bssid)) {
            foundItem = true;
            break;
        }
        pTmpItemNode = pTmpItemNode->pNext;
    }
    if (foundItem) {
        *(pTmpItemNode->ssid) = ssid;
        *(pTmpItemNode->encossid) = encossid;
        pTmpItemNode->isCh = isCh;
        if (DBG) {
            XLOGD("Found AP %s encossid= %s", pTmpItemNode->ssid->string(), pTmpItemNode->encossid->string());
        }
    } else {
        pItemNode = new struct accessPointObjectItem();
        if (NULL == pItemNode) {
            XLOGE("Failed to allocate memory for new item!");
            goto EXIT;
        }
        memset(pItemNode, 0, sizeof(accessPointObjectItem));
        pItemNode->bssid = new String8(bssid);
        if (NULL == pItemNode->bssid) {
            XLOGE("Failed to allocate memory for new bssid!");
            delete pItemNode;
            goto EXIT;
        }
        pItemNode->ssid = new String8(ssid);
        if (NULL == pItemNode->ssid) {
            XLOGE("Failed to allocate memory for new ssid!");
            delete pItemNode->bssid;
            delete pItemNode;
            goto EXIT;
        }
        pItemNode->encossid = new String8(encossid);
        if (NULL == pItemNode->encossid) {
            XLOGE("Failed to allocate memory for new encossid!");
            delete pItemNode->ssid;
            delete pItemNode->bssid;
            delete pItemNode;
            goto EXIT;
        }
        pItemNode->isCh = isCh;
        pItemNode->pNext = NULL;
        if (DBG) {
            XLOGD("AP doesn't exist, new one for %s", ssid);
        }

        if (NULL == g_pItemList) {
            g_pItemList = pItemNode;
            g_pLastNode = g_pItemList;
        } else {
            g_pLastNode->pNext = pItemNode;
            g_pLastNode = pItemNode;
        }
    }

EXIT:
    pthread_mutex_unlock(g_pItemListMutex);
}

static bool isUTF8String(const char* str, long length)
{
    unsigned int nBytes = 0;
    unsigned char chr;
    bool bAllAscii = true;
    for (int i = 0; i < length; i++) {
        chr = *(str+i);
        if ((chr & 0x80) != 0) {
            bAllAscii = false;
        }
        if (0 == nBytes) {
            if (chr >= 0x80) {
                if (chr >= 0xFC && chr <= 0xFD) {
                    nBytes = 6;
                } else if (chr >= 0xF8) {
                    nBytes = 5;
                } else if (chr >= 0xF0) {
                    nBytes = 4;
                } else if (chr >= 0xE0) {
                    nBytes = 3;
                } else if (chr >= 0xC0) {
                    nBytes = 2;
                } else {
                    return false;
                }
                nBytes--;
            }
        } else {
            if ((chr & 0xC0) != 0x80) {
                return false;
            }
            nBytes--;
        }
    }

    if (nBytes > 0 || bAllAscii) {
        return false;
    }
    return true;
}

static void parseScanResults(JNIEnv* env, String16& str, const char *reply)
{
    unsigned int lineBeg = 0, lineEnd = 0;
    size_t  replyLen = strlen(reply);
    char    *pos = NULL;
    char    bssid[BUF_SIZE] = {0};
    char    ssid[BUF_SIZE] = {0};
    String8 line;
    bool    isUTF8 = true;
    bool    isCh = false;
    UChar encossidd[CONVERT_LINE_LEN] = {0};
    String16 encossidstr;
    UChar dest[CONVERT_LINE_LEN] = {0};
    UErrorCode err = U_ZERO_ERROR;
    UConverter* pConverter = ucnv_open(CHARSET_CN, &err);
    if (U_FAILURE(err)) {
        XLOGE("ucnv_open error");
        return;
    }
    //Parse every line of the reply to construct accessPointObjectItem list
    for (lineBeg = 0, lineEnd = 0; lineEnd <= replyLen; ++lineEnd) {
        if (lineEnd == replyLen || '\n' == reply[lineEnd]) {
            line.setTo(reply + lineBeg, lineEnd - lineBeg + 1);
            /*if (DBG) {
                XLOGD("%s, line:%s", __FUNCTION__, line.string());
            }*/
            if (strncmp(line.string(), "bssid=", 6) == 0) {
                sscanf(line.string() + 6, "%255[^\n]", bssid);
            } else if (strncmp(line.string(), "ssid=", 5) == 0) {
                sscanf(line.string() + 5, "%255[^\n]", ssid);
                isUTF8 = isUTF8String(ssid, strlen(ssid));
            } else if (strncmp(line.string(), "====", 4) == 0) {
                isCh = false;
                for (pos = ssid; '\0' != *pos; pos++) {
                    if (0x80 == (*pos & 0x80)) {
                        isCh = true;
                        break;
                    }
                }
                if (isCh == true) {
                    ucnv_toUChars(pConverter, encossidd, CONVERT_LINE_LEN, ssid, BUF_SIZE, &err);
                    if (U_FAILURE(err)) {
                        XLOGE("ucnv_toUChars error");
                        goto EXIT;
                    }
                    
                    encossidstr = String16(encossidd);
                } else {
                    encossidstr = String16(ssid);

                }
                if (DBG) {
                    XLOGD("After sscanf, bssid:%s, ssid:%s, isCh:%d, isUTF8:%d enssid:%s", bssid, ssid, isCh, isUTF8, encossidstr.string());
                    for (unsigned int i = 0; i < strlen(ssid); i++) {
                        XLOGD("ssid[%d]:%c, 0x%x", i, ssid[i], ssid[i]);
                    }
                }
                jstring result = env->NewString((const jchar *)encossidstr.string(), encossidstr.size());
                ///M: result should be free after temp free
                {
                    ScopedUtfChars temp(env, result);                
                    addAPObjectItem(ssid, bssid, isCh, temp.c_str());
                    if (DBG) {
                        XLOGD("addAPObjectItem done  encossid=%s", temp.c_str());
                    }

                }
                env->DeleteLocalRef(result);

            }
            if (!isUTF8) {
                ucnv_toUChars(pConverter, dest, CONVERT_LINE_LEN, line.string(), line.size(), &err);
                if (U_FAILURE(err)) {
                    XLOGE("ucnv_toUChars error");
                    goto EXIT;
                }
                str += String16(dest);
                memset(dest, 0, CONVERT_LINE_LEN);
            } else {
                str += String16(line.string());
            }
            lineBeg = lineEnd + 1;
        }
    }

EXIT:
    ucnv_close(pConverter);
}

static void constructReply(String16& str, const char *cmd, const char *reply)
{
    if (DBG) {
        XLOGD("%s, cmd = %s, reply = %s", __FUNCTION__, cmd, reply);
    }
    size_t 	replyLen = strlen(reply);
    unsigned int lineBeg = 0, lineEnd = 0;
    String8 line;
    bool isUTF8 = true;
    UChar dest[CONVERT_LINE_LEN] = {0};
    UErrorCode err = U_ZERO_ERROR;
    UConverter* pConverter = ucnv_open(CHARSET_CN, &err);
    if (U_FAILURE(err)) {
        XLOGE("ucnv_open error");
        return;
    }

    for (lineBeg = 0, lineEnd = 0; lineEnd <= replyLen; ++lineEnd) {
        if (lineEnd == replyLen || '\n' == reply[lineEnd]) {
            line.setTo(reply + lineBeg, lineEnd - lineBeg + 1);
            isUTF8 = isUTF8String(line.string(), line.size());
            if (DBG) {
                XLOGD("%s, line=%s, isUTF8=%d", __FUNCTION__, line.string(), isUTF8);
            }
            if (!isUTF8) {
                ucnv_toUChars(pConverter, dest, CONVERT_LINE_LEN, line.string(), line.size(), &err);
                if (U_FAILURE(err)) {
                    XLOGE("ucnv_toUChars error");
                    goto EXIT;
                }
                str += String16(dest);
                memset(dest, 0, CONVERT_LINE_LEN);
            } else {
                str += String16(line.string());
            }
            lineBeg = lineEnd + 1;
        }
    }

EXIT:
    ucnv_close(pConverter);
}

static void printLongReply(const char *reply) {
    if (DBG) {
        for (unsigned int i = 0; i < strlen(reply); i++) {
            XLOGD("reply[%d]:%c, 0x%x", i, reply[i], reply[i]);
        }
    }
    unsigned int lineBeg = 0, lineEnd = 0;
    size_t replyLen = strlen(reply);
    String8 line;
    for (lineBeg = 0, lineEnd = 0; lineEnd <= replyLen; ++lineEnd) {
        if (lineEnd == replyLen || '\n' == reply[lineEnd]) {
            line.setTo(reply + lineBeg, lineEnd - lineBeg + 1);
            XLOGI("%s", line.string());
            lineBeg = lineEnd + 1;
        }
    }
}

static bool doCommand(JNIEnv* env, jstring javaCommand,
                      char* reply, size_t reply_len) {
    ScopedUtfChars command(env, javaCommand);
    if (command.c_str() == NULL) {
        return false; // ScopedUtfChars already threw on error.
    }

    if (DBG) {
        ALOGD("doCommand: %s", command.c_str());
    }

    --reply_len; // Ensure we have room to add NUL termination.
    if (::wifi_command(command.c_str(), reply, &reply_len) != 0) {
        return false;
    }

    // Strip off trailing newline.
    if (reply_len > 0 && reply[reply_len-1] == '\n') {
        reply[reply_len-1] = '\0';
    } else {
        reply[reply_len] = '\0';
    }
    return true;
}

static jint doIntCommand(JNIEnv* env, jstring javaCommand) {
    char reply[REPLY_BUF_SIZE]= {0};
    if (!doCommand(env, javaCommand, reply, sizeof(reply))) {
        return -1;
    }
    return static_cast<jint>(atoi(reply));
}

static jboolean doBooleanCommand(JNIEnv* env, jstring javaCommand) {
    char reply[REPLY_BUF_SIZE]= {0};
    ScopedUtfChars jcmd(env, javaCommand);
    XLOGD("doBooleanCommand %s", jcmd.c_str());
    if (!doCommand(env, javaCommand, reply, sizeof(reply))) {
        return JNI_FALSE;
    }
    return (strcmp(reply, "OK") == 0);
}





static int doCommandTranslated(const char *cmd, char *replybuf, int replybuflen)
{
    size_t reply_len = replybuflen - 1;

    if (DBG) {
        ALOGD("doCommand: %s", cmd);
    }
    if (::wifi_command(cmd, replybuf, &reply_len) != 0)
        return -1;
    else {
        // Strip off trailing newline
        if (reply_len > 0 && replybuf[reply_len-1] == '\n')
            replybuf[reply_len-1] = '\0';
        else
            replybuf[reply_len] = '\0';
        return 0;
    }
}

static jboolean doBooleanCommandTranslated(const char* expect, const char* fmt, ...)
{
    char buf[BUF_SIZE] = {0};
    va_list args;
    va_start(args, fmt);
    int byteCount = vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    if (byteCount < 0 || byteCount >= BUF_SIZE) {
        return JNI_FALSE;
    }
    char reply[BUF_SIZE] = {0};
    if (doCommandTranslated(buf, reply, sizeof(reply)) != 0) {
        return JNI_FALSE;
    }
    return (strcmp(reply, expect) == 0);
}

// Send a command to the supplicant, and return the reply as a String.
static jstring doStringCommand(JNIEnv* env, jstring javaCommand) {

    char reply[REPLY_BUF_SIZE]= {0};
    char buf[BUF_SIZE] = {0};
    
    if (!doCommand(env, javaCommand, reply, sizeof(reply))) {
        return NULL;
    }
    ///M:@{
    const char *nativeString = env->GetStringUTFChars(javaCommand, 0);
    int isWlanInterafce = strncmp("IFNAME=wlan",nativeString,strlen("IFNAME=wlan"));
    if(nativeString!=NULL)strncpy(buf,nativeString, sizeof(buf)-1);
    env->ReleaseStringUTFChars(javaCommand, nativeString);
    XLOGD("%s, buf %s", __FUNCTION__, buf);
    if (0 == isWlanInterafce) {
        String16 str;
        if (strstr(buf, "MASK=0x21987") ||
            (strstr(buf, "GET_NETWORK") && strstr(buf, "ssid")) ||
            (0 == strcmp(buf, "STATUS")) ||
            (0 == strcmp(buf, "LIST_NETWORKS")) ||
            (0 == strcmp(buf,"DRIVER WLS_BATCHING GET")) ){
            if (strstr(buf, "MASK=0x21987")) {
                printLongReply(reply);
                parseScanResults(env, str, reply);
            } else {
                if (DBG) {
                    printLongReply(reply);
                }
                constructReply(str, buf, reply);
            }
        } else {
            if (DBG) {
                printLongReply(reply);
            }
            str += String16((char *)reply);
        }
        return env->NewString((const jchar *)str.string(), str.size());
    } else {
        if (DBG) {
            printLongReply(reply);
        }
        jclass stringClass;
        jmethodID methodId;
        jbyteArray byteArray;
        stringClass = env->FindClass("java/lang/String");
        if (NULL == stringClass) {
            XLOGE("Failed to find String class!");
            return NULL;
        }
        methodId = env->GetMethodID(stringClass,"<init>", "([B)V");
        if (NULL == methodId) {
            XLOGE("Failed to get constructor methodId!");
            env->DeleteLocalRef(stringClass);
            return NULL;
        }
        byteArray = env->NewByteArray(strlen(reply));
        if (NULL == byteArray) {
            XLOGE("Failed to new byte array!");
            env->DeleteLocalRef(stringClass);
            return NULL;
        }
        env->SetByteArrayRegion(byteArray, 0, strlen(reply), (jbyte*)reply);
        jstring result = (jstring)(env->NewObject(stringClass, methodId, byteArray));
        env->DeleteLocalRef(byteArray);
        env->DeleteLocalRef(stringClass);
        return result;
    }
    ///@}
}

static jboolean android_net_wifi_isDriverLoaded(JNIEnv* env, jobject)
{
    return (::is_wifi_driver_loaded() == 1);
}

static jboolean android_net_wifi_loadDriver(JNIEnv* env, jobject)
{
    ///M:@{
    g_pItemListMutex = new pthread_mutex_t;
    if (NULL == g_pItemListMutex) {
        XLOGE("Failed to allocate memory for g_pItemListMutex!");
        return JNI_FALSE;
    }
    pthread_mutex_init(g_pItemListMutex, NULL);
    g_pCurrentSSID = new String8();
    if (NULL == g_pCurrentSSID) {
        XLOGE("Failed to allocate memory for g_pCurrentSSID!");
        return JNI_FALSE;
    }
    char dbg[PROPERTY_VALUE_MAX] = {0};
    if (property_get("wifi.jni.dbg", dbg, NULL) && strcmp(dbg, "true") == 0) {
        DBG = true;
    } else {
        DBG = false;
    }
    ///@}
    return (::wifi_load_driver() == 0);
}

static jboolean android_net_wifi_unloadDriver(JNIEnv* env, jobject)
{
    ///M:@{
    if (g_pCurrentSSID != NULL) {
        delete g_pCurrentSSID;
        g_pCurrentSSID = NULL;
    }
    if (g_pItemListMutex != NULL) {
        pthread_mutex_lock(g_pItemListMutex);
        struct accessPointObjectItem *pCurrentNode = g_pItemList;
        struct accessPointObjectItem *pNextNode = NULL;
        while (pCurrentNode) {
            pNextNode = pCurrentNode->pNext;
            if (NULL != pCurrentNode->ssid) {
                delete pCurrentNode->ssid;
                pCurrentNode->ssid = NULL;
            }
            if (NULL != pCurrentNode->bssid) {
                delete pCurrentNode->bssid;
                pCurrentNode->bssid = NULL;
            }
            delete pCurrentNode;
            pCurrentNode = pNextNode;
        }
        g_pItemList = NULL;
        g_pLastNode = NULL;
        pthread_mutex_unlock(g_pItemListMutex);
        pthread_mutex_destroy(g_pItemListMutex);
        delete g_pItemListMutex;
        g_pItemListMutex = NULL;
    }
    ///@}
    return (::wifi_unload_driver() == 0);
}

static jboolean android_net_wifi_startSupplicant(JNIEnv* env, jobject, jboolean p2pSupported)
{
    return (::wifi_start_supplicant(p2pSupported) == 0);
}

static jboolean android_net_wifi_killSupplicant(JNIEnv* env, jobject, jboolean p2pSupported)
{
    return (::wifi_stop_supplicant(p2pSupported) == 0);
}

static jboolean android_net_wifi_connectToSupplicant(JNIEnv* env, jobject)
{
    return (::wifi_connect_to_supplicant() == 0);
}

static void android_net_wifi_closeSupplicantConnection(JNIEnv* env, jobject)
{
    ::wifi_close_supplicant_connection();
}

static jstring android_net_wifi_waitForEvent(JNIEnv* env, jobject, jstring jIface)
{
    char buf[EVENT_BUF_SIZE]= {0};
    ScopedUtfChars ifname(env, jIface);
    int nread = ::wifi_wait_for_event(buf, sizeof buf);
    if (nread > 0) {
        ///M:@{
        //return env->NewStringUTF(buf);
        if (DBG) {
            XLOGD("android_net_wifi_waitForEvent ifname.c_str()= %s, WIFI_INTERFACE = %s",ifname.c_str(),WIFI_INTERFACE);
        }
        if (DBG) {
            XLOGD("cmd = %s", buf);
        }
  ///M:@{

        int isWlanInterafce = strncmp("IFNAME=wlan",buf,strlen("IFNAME=wlan"));
      

        if (isWlanInterafce==0) {
        //if (0 == strcmp(ifname.c_str(), WIFI_INTERFACE)) {
            if (DBG) {
                XLOGD("wlan0 case");
            }
            String16 str;
            constructReply(str, NULL, buf);
            return env->NewString((const jchar *)str.string(), str.size());
        } else {
            if (DBG) {
                XLOGD("non wlan0 case");
            }
            jclass stringClass;
            jmethodID methodId;
            jbyteArray byteArray;
            stringClass = env->FindClass("java/lang/String");
            if (NULL == stringClass) {
                XLOGE("Failed to find String class!");
                return NULL;
            }
            methodId = env->GetMethodID(stringClass,"<init>", "([B)V");
            if (NULL == methodId) {
                XLOGE("Failed to get constructor methodId!");
                env->DeleteLocalRef(stringClass);
                return NULL;
            }
            byteArray = env->NewByteArray(strlen(buf));
            if (NULL == byteArray) {
                XLOGE("Failed to new byte array!");
                env->DeleteLocalRef(stringClass);
                return NULL;
            }
            env->SetByteArrayRegion(byteArray, 0, strlen(buf), (jbyte*)buf);
            jstring result = (jstring)(env->NewObject(stringClass, methodId, byteArray));
            env->DeleteLocalRef(byteArray);
            env->DeleteLocalRef(stringClass);
            return result;
        }
        ///@}
    } else {
        return NULL;
    }
}

static jboolean android_net_wifi_doBooleanCommand(JNIEnv* env, jobject, jstring javaCommand) {
    return doBooleanCommand(env, javaCommand);
}

static jint android_net_wifi_doIntCommand(JNIEnv* env, jobject, jstring javaCommand) {
    return doIntCommand(env, javaCommand);
}

static jstring android_net_wifi_doStringCommand(JNIEnv* env, jobject, jstring javaCommand) {
    return doStringCommand(env,javaCommand);
}

/* wifi_hal <==> WifiNative bridge */

static jclass mCls;                             /* saved WifiNative object */
static JavaVM *mVM;                             /* saved JVM pointer */

static const char *WifiHandleVarName = "sWifiHalHandle";
static const char *WifiIfaceHandleVarName = "sWifiIfaceHandles";
static jmethodID OnScanResultsMethodID;

static JNIEnv *getEnv() {
    JNIEnv *env = NULL;
    mVM->AttachCurrentThread(&env, NULL);
    return env;
}

static wifi_handle getWifiHandle(JNIEnv *env, jclass cls) {
    return (wifi_handle) getStaticLongField(env, cls, WifiHandleVarName);
}

static wifi_interface_handle getIfaceHandle(JNIEnv *env, jclass cls, jint index) {
    return (wifi_interface_handle) getStaticLongArrayField(env, cls, WifiIfaceHandleVarName, index);
}

static jobject createScanResult(JNIEnv *env, wifi_scan_result *result) {

    // ALOGD("creating scan result");

    jobject scanResult = createObject(env, "android/net/wifi/ScanResult");
    if (scanResult == NULL) {
        ALOGE("Error in creating scan result");
        return NULL;
    }

    // ALOGD("setting SSID to %s", result.ssid);
    setStringField(env, scanResult, "SSID", result->ssid);

    char bssid[32];
    sprintf(bssid, "%02x:%02x:%02x:%02x:%02x:%02x", result->bssid[0], result->bssid[1],
        result->bssid[2], result->bssid[3], result->bssid[4], result->bssid[5]);

    setStringField(env, scanResult, "BSSID", bssid);

    setIntField(env, scanResult, "level", result->rssi);
    setIntField(env, scanResult, "frequency", result->channel);
    setLongField(env, scanResult, "timestamp", result->ts);

    return scanResult;
}

static jboolean android_net_wifi_startHal(JNIEnv* env, jclass cls) {
    wifi_handle halHandle = getWifiHandle(env, cls);

    if (halHandle == NULL) {
        wifi_error res = wifi_initialize(&halHandle);
        if (res == WIFI_SUCCESS) {
            setStaticLongField(env, cls, WifiHandleVarName, (jlong)halHandle);
            ALOGD("Did set static halHandle = %p", halHandle);
        }
        env->GetJavaVM(&mVM);
        mCls = (jclass) env->NewGlobalRef(cls);
        ALOGD("halHandle = %p, mVM = %p, mCls = %p", halHandle, mVM, mCls);
        return res == WIFI_SUCCESS;
    } else {
        return true;
    }
}

void android_net_wifi_hal_cleaned_up_handler(wifi_handle handle) {
    ALOGD("In wifi cleaned up handler");

    JNIEnv * env = getEnv();
    setStaticLongField(env, mCls, WifiHandleVarName, 0);
    env->DeleteGlobalRef(mCls);
    mCls = NULL;
    mVM  = NULL;
}

static void android_net_wifi_stopHal(JNIEnv* env, jclass cls) {
    ALOGD("In wifi stop Hal");
    wifi_handle halHandle = getWifiHandle(env, cls);
    wifi_cleanup(halHandle, android_net_wifi_hal_cleaned_up_handler);
}

static void android_net_wifi_waitForHalEvents(JNIEnv* env, jclass cls) {

    ALOGD("waitForHalEvents called, vm = %p, obj = %p, env = %p", mVM, mCls, env);

    wifi_handle halHandle = getWifiHandle(env, cls);
    wifi_event_loop(halHandle);
}

static int android_net_wifi_getInterfaces(JNIEnv *env, jclass cls) {
    int n = 0;
    wifi_handle halHandle = getWifiHandle(env, cls);
    wifi_interface_handle *ifaceHandles = NULL;
    int result = wifi_get_ifaces(halHandle, &n, &ifaceHandles);
    if (result < 0) {
        return result;
    }

    if (n < 0) {
        THROW(env, "android_net_wifi_getInterfaces no interfaces");
        return 0;
    }

    if (ifaceHandles == NULL) {
       THROW(env, "android_net_wifi_getInterfaces null interface array");
       return 0;
    }

    if (n > 8) {
        THROW(env, "Too many interfaces");
        return 0;
    }

    jlongArray array = (env)->NewLongArray(n);
    if (array == NULL) {
        THROW(env, "Error in accessing array");
        return 0;
    }

    jlong elems[8];
    for (int i = 0; i < n; i++) {
        elems[i] = reinterpret_cast<jlong>(ifaceHandles[i]);
    }
    env->SetLongArrayRegion(array, 0, n, elems);
    setStaticLongArrayField(env, cls, WifiIfaceHandleVarName, array);

    return (result < 0) ? result : n;
}

static jstring android_net_wifi_getInterfaceName(JNIEnv *env, jclass cls, jint i) {
    char buf[EVENT_BUF_SIZE];

    jlong value = getStaticLongArrayField(env, cls, WifiIfaceHandleVarName, i);
    wifi_interface_handle handle = (wifi_interface_handle) value;
    int result = ::wifi_get_iface_name(handle, buf, sizeof(buf));
    if (result < 0) {
        return NULL;
    } else {
        return env->NewStringUTF(buf);
    }
}


static void onScanResultsAvailable(wifi_request_id id, unsigned num_results) {

    JNIEnv *env = NULL;
    mVM->AttachCurrentThread(&env, NULL);

    ALOGD("onScanResultsAvailable called, vm = %p, obj = %p, env = %p", mVM, mCls, env);

    reportEvent(env, mCls, "onScanResultsAvailable", "(I)V", id);
}

static void onScanEvent(wifi_scan_event event, unsigned status) {
    JNIEnv *env = NULL;
    mVM->AttachCurrentThread(&env, NULL);

    ALOGD("onScanStatus called, vm = %p, obj = %p, env = %p", mVM, mCls, env);

    reportEvent(env, mCls, "onScanStatus", "(I)V", status);
}

static void onFullScanResult(wifi_request_id id, wifi_scan_result *result) {

    JNIEnv *env = NULL;
    mVM->AttachCurrentThread(&env, NULL);

    ALOGD("onFullScanResult called, vm = %p, obj = %p, env = %p", mVM, mCls, env);

    jobject scanResult = createScanResult(env, result);

    ALOGD("Creating a byte array of length %d", result->ie_length);

    jbyteArray elements = env->NewByteArray(result->ie_length);
    if (elements == NULL) {
        ALOGE("Error in allocating array");
        return;
    }

    ALOGE("Setting byte array");

    jbyte *bytes = (jbyte *)&(result->ie_data[0]);
    env->SetByteArrayRegion(elements, 0, result->ie_length, bytes);

    ALOGE("Returning result");

    reportEvent(env, mCls, "onFullScanResult", "(ILandroid/net/wifi/ScanResult;[B)V", id,
            scanResult, elements);

    env->DeleteLocalRef(scanResult);
    env->DeleteLocalRef(elements);
}

static jboolean android_net_wifi_startScan(
        JNIEnv *env, jclass cls, jint iface, jint id, jobject settings) {

    wifi_interface_handle handle = getIfaceHandle(env, cls, iface);
    ALOGD("starting scan on interface[%d] = %p", iface, handle);

    wifi_scan_cmd_params params;
    memset(&params, 0, sizeof(params));

    params.base_period = getIntField(env, settings, "base_period_ms");
    params.max_ap_per_scan = getIntField(env, settings, "max_ap_per_scan");
    params.report_threshold = getIntField(env, settings, "report_threshold");

    ALOGD("Initialized common fields %d, %d, %d", params.base_period,
            params.max_ap_per_scan, params.report_threshold);

    const char *bucket_array_type = "[Lcom/android/server/wifi/WifiNative$BucketSettings;";
    const char *channel_array_type = "[Lcom/android/server/wifi/WifiNative$ChannelSettings;";

    jobjectArray buckets = (jobjectArray)getObjectField(env, settings, "buckets", bucket_array_type);
    params.num_buckets = getIntField(env, settings, "num_buckets");

    ALOGD("Initialized num_buckets to %d", params.num_buckets);

    for (int i = 0; i < params.num_buckets; i++) {
        jobject bucket = getObjectArrayField(env, settings, "buckets", bucket_array_type, i);

        params.buckets[i].bucket = getIntField(env, bucket, "bucket");
        params.buckets[i].band = (wifi_band) getIntField(env, bucket, "band");
        params.buckets[i].period = getIntField(env, bucket, "period_ms");

        ALOGD("Initialized common bucket fields %d:%d:%d", params.buckets[i].bucket,
                params.buckets[i].band, params.buckets[i].period);

        int report_events = getIntField(env, bucket, "report_events");
        params.buckets[i].report_events = report_events;

        ALOGD("Initialized report events to %d", params.buckets[i].report_events);

        jobjectArray channels = (jobjectArray)getObjectField(
                env, bucket, "channels", channel_array_type);

        params.buckets[i].num_channels = getIntField(env, bucket, "num_channels");
        ALOGD("Initialized num_channels to %d", params.buckets[i].num_channels);

        for (int j = 0; j < params.buckets[i].num_channels; j++) {
            jobject channel = getObjectArrayField(env, bucket, "channels", channel_array_type, j);

            params.buckets[i].channels[j].channel = getIntField(env, channel, "frequency");
            params.buckets[i].channels[j].dwellTimeMs = getIntField(env, channel, "dwell_time_ms");

            bool passive = getBoolField(env, channel, "passive");
            params.buckets[i].channels[j].passive = (passive ? 1 : 0);

            ALOGD("Initialized channel %d", params.buckets[i].channels[j].channel);
        }
    }

    ALOGD("Initialized all fields");

    wifi_scan_result_handler handler;
    memset(&handler, 0, sizeof(handler));
    handler.on_scan_results_available = &onScanResultsAvailable;
    handler.on_full_scan_result = &onFullScanResult;
    handler.on_scan_event = &onScanEvent;

    return wifi_start_gscan(id, handle, params, handler) == WIFI_SUCCESS;
}

static jboolean android_net_wifi_stopScan(JNIEnv *env, jclass cls, jint iface, jint id) {
    wifi_interface_handle handle = getIfaceHandle(env, cls, iface);
    ALOGD("stopping scan on interface[%d] = %p", iface, handle);

    return wifi_stop_gscan(id, handle)  == WIFI_SUCCESS;
}

static jobject android_net_wifi_getScanResults(
        JNIEnv *env, jclass cls, jint iface, jboolean flush)  {
    
    wifi_scan_result results[256];
    int num_results = 256;
    
    wifi_interface_handle handle = getIfaceHandle(env, cls, iface);
    ALOGD("getting scan results on interface[%d] = %p", iface, handle);
    
    int result = wifi_get_cached_gscan_results(handle, 1, num_results, results, &num_results);
    if (result == WIFI_SUCCESS) {
        jclass clsScanResult = (env)->FindClass("android/net/wifi/ScanResult");
        if (clsScanResult == NULL) {
            ALOGE("Error in accessing class");
            return NULL;
        }

        jobjectArray scanResults = env->NewObjectArray(num_results, clsScanResult, NULL);
        if (scanResults == NULL) {
            ALOGE("Error in allocating array");
            return NULL;
        }

        for (int i = 0; i < num_results; i++) {

            jobject scanResult = createObject(env, "android/net/wifi/ScanResult");
            if (scanResult == NULL) {
                ALOGE("Error in creating scan result");
                return NULL;
            }

            setStringField(env, scanResult, "SSID", results[i].ssid);

            char bssid[32];
            sprintf(bssid, "%02x:%02x:%02x:%02x:%02x:%02x", results[i].bssid[0],
                    results[i].bssid[1], results[i].bssid[2], results[i].bssid[3],
                    results[i].bssid[4], results[i].bssid[5]);

            setStringField(env, scanResult, "BSSID", bssid);

            setIntField(env, scanResult, "level", results[i].rssi);
            setIntField(env, scanResult, "frequency", results[i].channel);
            setLongField(env, scanResult, "timestamp", results[i].ts);

            env->SetObjectArrayElement(scanResults, i, scanResult);
            env->DeleteLocalRef(scanResult);
        }

        return scanResults;
    } else {
        return NULL;
    }
}


static jboolean android_net_wifi_getScanCapabilities(
        JNIEnv *env, jclass cls, jint iface, jobject capabilities) {

    wifi_interface_handle handle = getIfaceHandle(env, cls, iface);
    ALOGD("getting scan capabilities on interface[%d] = %p", iface, handle);

    wifi_gscan_capabilities c;
    memset(&c, 0, sizeof(c));
    int result = wifi_get_gscan_capabilities(handle, &c);
    if (result != WIFI_SUCCESS) {
        ALOGD("failed to get capabilities : %d", result);
        return JNI_FALSE;
    }

    setIntField(env, capabilities, "max_scan_cache_size", c.max_scan_cache_size);
    setIntField(env, capabilities, "max_scan_buckets", c.max_scan_buckets);
    setIntField(env, capabilities, "max_ap_cache_per_scan", c.max_ap_cache_per_scan);
    setIntField(env, capabilities, "max_rssi_sample_size", c.max_rssi_sample_size);
    setIntField(env, capabilities, "max_scan_reporting_threshold", c.max_scan_reporting_threshold);
    setIntField(env, capabilities, "max_hotlist_aps", c.max_hotlist_aps);
    setIntField(env, capabilities, "max_significant_wifi_change_aps",
                c.max_significant_wifi_change_aps);

    return JNI_TRUE;
}


static byte parseHexChar(char ch) {
    if (isdigit(ch))
        return ch - '0';
    else if ('A' <= ch && ch <= 'F')
        return ch - 'A' + 10;
    else if ('a' <= ch && ch <= 'f')
        return ch - 'a' + 10;
    else {
        ALOGE("invalid character in bssid %c", ch);
        return 0;
    }
}

static byte parseHexByte(const char * &str) {
    byte b = parseHexChar(str[0]);
    if (str[1] == ':' || str[1] == '\0') {
        str += 2;
        return b;
    } else {
        b = b << 4 | parseHexChar(str[1]);
        str += 3;
        return b;
    }
}

static void parseMacAddress(const char *str, mac_addr addr) {
    addr[0] = parseHexByte(str);
    addr[1] = parseHexByte(str);
    addr[2] = parseHexByte(str);
    addr[3] = parseHexByte(str);
    addr[4] = parseHexByte(str);
    addr[5] = parseHexByte(str);
}

static bool parseMacAddress(JNIEnv *env, jobject obj, mac_addr addr) {
    jstring macAddrString = (jstring) getObjectField(
            env, obj, "bssid", "Ljava/lang/String;");

    if (macAddrString == NULL) {
        ALOGE("Error getting bssid field");
        return false;
    }

    const char *bssid = env->GetStringUTFChars(macAddrString, NULL);
    if (bssid == NULL) {
        ALOGE("Error getting bssid");
        return false;
    }

    parseMacAddress(bssid, addr);
    return true;
}

static void onHotlistApFound(wifi_request_id id,
        unsigned num_results, wifi_scan_result *results) {

    JNIEnv *env = NULL;
    mVM->AttachCurrentThread(&env, NULL);

    ALOGD("onHotlistApFound called, vm = %p, obj = %p, env = %p, num_results = %d",
            mVM, mCls, env, num_results);

    jclass clsScanResult = (env)->FindClass("android/net/wifi/ScanResult");
    if (clsScanResult == NULL) {
        ALOGE("Error in accessing class");
        return;
    }

    jobjectArray scanResults = env->NewObjectArray(num_results, clsScanResult, NULL);
    if (scanResults == NULL) {
        ALOGE("Error in allocating array");
        return;
    }

    for (unsigned i = 0; i < num_results; i++) {

        jobject scanResult = createObject(env, "android/net/wifi/ScanResult");
        if (scanResult == NULL) {
            ALOGE("Error in creating scan result");
            return;
        }

        setStringField(env, scanResult, "SSID", results[i].ssid);

        char bssid[32];
        sprintf(bssid, "%02x:%02x:%02x:%02x:%02x:%02x", results[i].bssid[0], results[i].bssid[1],
            results[i].bssid[2], results[i].bssid[3], results[i].bssid[4], results[i].bssid[5]);

        setStringField(env, scanResult, "BSSID", bssid);

        setIntField(env, scanResult, "level", results[i].rssi);
        setIntField(env, scanResult, "frequency", results[i].channel);
        setLongField(env, scanResult, "timestamp", results[i].ts);

        env->SetObjectArrayElement(scanResults, i, scanResult);

        ALOGD("Found AP %32s %s", results[i].ssid, bssid);
    }

    reportEvent(env, mCls, "onHotlistApFound", "(I[Landroid/net/wifi/ScanResult;)V",
        id, scanResults);
}

static jboolean android_net_wifi_setHotlist(
        JNIEnv *env, jclass cls, jint iface, jint id, jobject ap)  {

    wifi_interface_handle handle = getIfaceHandle(env, cls, iface);
    ALOGD("setting hotlist on interface[%d] = %p", iface, handle);

    wifi_bssid_hotlist_params params;
    memset(&params, 0, sizeof(params));

    jobjectArray array = (jobjectArray) getObjectField(env, ap,
            "bssidInfos", "[Landroid/net/wifi/WifiScanner$BssidInfo;");
    params.num_ap = env->GetArrayLength(array);

    if (params.num_ap == 0) {
        ALOGE("Error in accesing array");
        return false;
    }

    for (int i = 0; i < params.num_ap; i++) {
        jobject objAp = env->GetObjectArrayElement(array, i);

        jstring macAddrString = (jstring) getObjectField(
                env, objAp, "bssid", "Ljava/lang/String;");
        if (macAddrString == NULL) {
            ALOGE("Error getting bssid field");
            return false;
        }

        const char *bssid = env->GetStringUTFChars(macAddrString, NULL);
        if (bssid == NULL) {
            ALOGE("Error getting bssid");
            return false;
        }
        parseMacAddress(bssid, params.ap[i].bssid);

        mac_addr addr;
        memcpy(addr, params.ap[i].bssid, sizeof(mac_addr));

        char bssidOut[32];
        sprintf(bssidOut, "%0x:%0x:%0x:%0x:%0x:%0x", addr[0], addr[1],
            addr[2], addr[3], addr[4], addr[5]);

        ALOGD("Added bssid %s", bssidOut);

        params.ap[i].low = getIntField(env, objAp, "low");
        params.ap[i].high = getIntField(env, objAp, "high");
    }

    wifi_hotlist_ap_found_handler handler;
    memset(&handler, 0, sizeof(handler));

    handler.on_hotlist_ap_found = &onHotlistApFound;
    return wifi_set_bssid_hotlist(id, handle, params, handler) == WIFI_SUCCESS;
}

static jboolean android_net_wifi_resetHotlist(
        JNIEnv *env, jclass cls, jint iface, jint id)  {

    wifi_interface_handle handle = getIfaceHandle(env, cls, iface);
    ALOGD("resetting hotlist on interface[%d] = %p", iface, handle);

    return wifi_reset_bssid_hotlist(id, handle) == WIFI_SUCCESS;
}

void onSignificantWifiChange(wifi_request_id id,
        unsigned num_results, wifi_significant_change_result **results) {
    JNIEnv *env = NULL;
    mVM->AttachCurrentThread(&env, NULL);

    ALOGD("onSignificantWifiChange called, vm = %p, obj = %p, env = %p", mVM, mCls, env);

    jclass clsScanResult = (env)->FindClass("android/net/wifi/ScanResult");
    if (clsScanResult == NULL) {
        ALOGE("Error in accessing class");
        return;
    }

    jobjectArray scanResults = env->NewObjectArray(num_results, clsScanResult, NULL);
    if (scanResults == NULL) {
        ALOGE("Error in allocating array");
        return;
    }

    for (unsigned i = 0; i < num_results; i++) {

        wifi_significant_change_result result = *(results[i]);

        jobject scanResult = createObject(env, "android/net/wifi/ScanResult");
        if (scanResult == NULL) {
            ALOGE("Error in creating scan result");
            return;
        }

        // setStringField(env, scanResult, "SSID", results[i].ssid);

        char bssid[32];
        sprintf(bssid, "%02x:%02x:%02x:%02x:%02x:%02x", result.bssid[0], result.bssid[1],
            result.bssid[2], result.bssid[3], result.bssid[4], result.bssid[5]);

        setStringField(env, scanResult, "BSSID", bssid);

        setIntField(env, scanResult, "level", result.rssi[0]);
        setIntField(env, scanResult, "frequency", result.channel);
        // setLongField(env, scanResult, "timestamp", result.ts);

        env->SetObjectArrayElement(scanResults, i, scanResult);
    }

    reportEvent(env, mCls, "onSignificantWifiChange", "(I[Landroid/net/wifi/ScanResult;)V",
        id, scanResults);

}

static jboolean android_net_wifi_trackSignificantWifiChange(
        JNIEnv *env, jclass cls, jint iface, jint id, jobject settings)  {

    wifi_interface_handle handle = getIfaceHandle(env, cls, iface);
    ALOGD("tracking significant wifi change on interface[%d] = %p", iface, handle);

    wifi_significant_change_params params;
    memset(&params, 0, sizeof(params));

    params.rssi_sample_size = getIntField(env, settings, "rssiSampleSize");
    params.lost_ap_sample_size = getIntField(env, settings, "lostApSampleSize");
    params.min_breaching = getIntField(env, settings, "minApsBreachingThreshold");

    const char *bssid_info_array_type = "[Landroid/net/wifi/WifiScanner$BssidInfo;";
    jobjectArray bssids = (jobjectArray)getObjectField(
                env, settings, "bssidInfos", bssid_info_array_type);
    params.num_ap = env->GetArrayLength(bssids);

    if (params.num_ap == 0) {
        ALOGE("Error in accessing array");
        return false;
    }

    ALOGD("Initialized common fields %d, %d, %d, %d", params.rssi_sample_size,
            params.lost_ap_sample_size, params.min_breaching, params.num_ap);

    for (int i = 0; i < params.num_ap; i++) {
        jobject objAp = env->GetObjectArrayElement(bssids, i);

        jstring macAddrString = (jstring) getObjectField(
                env, objAp, "bssid", "Ljava/lang/String;");
        if (macAddrString == NULL) {
            ALOGE("Error getting bssid field");
            return false;
        }

        const char *bssid = env->GetStringUTFChars(macAddrString, NULL);
        if (bssid == NULL) {
            ALOGE("Error getting bssid");
            return false;
        }

        mac_addr addr;
        parseMacAddress(bssid, addr);
        memcpy(params.ap[i].bssid, addr, sizeof(mac_addr));

        char bssidOut[32];
        sprintf(bssidOut, "%02x:%02x:%02x:%02x:%02x:%02x", addr[0], addr[1],
            addr[2], addr[3], addr[4], addr[5]);

        params.ap[i].low = getIntField(env, objAp, "low");
        params.ap[i].high = getIntField(env, objAp, "high");

        ALOGD("Added bssid %s, [%04d, %04d]", bssidOut, params.ap[i].low, params.ap[i].high);
    }

    ALOGD("Added %d bssids", params.num_ap);

    wifi_significant_change_handler handler;
    memset(&handler, 0, sizeof(handler));

    handler.on_significant_change = &onSignificantWifiChange;
    return wifi_set_significant_change_handler(id, handle, params, handler) == WIFI_SUCCESS;
}

static jboolean android_net_wifi_untrackSignificantWifiChange(
        JNIEnv *env, jclass cls, jint iface, jint id)  {

    wifi_interface_handle handle = getIfaceHandle(env, cls, iface);
    ALOGD("resetting significant wifi change on interface[%d] = %p", iface, handle);

    return wifi_reset_significant_change_handler(id, handle) == WIFI_SUCCESS;
}

wifi_iface_stat link_stat;
wifi_radio_stat radio_stat; // L release has support for only one radio

void onLinkStatsResults(wifi_request_id id, wifi_iface_stat *iface_stat,
         int num_radios, wifi_radio_stat *radio_stats)
{
    if (iface_stat != 0) {
        memcpy(&link_stat, iface_stat, sizeof(wifi_iface_stat));
    } else {
        memset(&link_stat, 0, sizeof(wifi_iface_stat));
    }

    if (num_radios > 0 && radio_stats != 0) {
        memcpy(&radio_stat, radio_stats, sizeof(wifi_radio_stat));
    } else {
        memset(&radio_stat, 0, sizeof(wifi_radio_stat));
    }
}

static jobject android_net_wifi_getLinkLayerStats (JNIEnv *env, jclass cls, jint iface)  {

    wifi_stats_result_handler handler;
    memset(&handler, 0, sizeof(handler));
    handler.on_link_stats_results = &onLinkStatsResults;
    wifi_interface_handle handle = getIfaceHandle(env, cls, iface);
    int result = wifi_get_link_stats(0, handle, handler);
    if (result < 0) {
        ALOGE("android_net_wifi_getLinkLayerStats: failed to get link statistics\n");
        return NULL;
    }

    jobject wifiLinkLayerStats = createObject(env, "android/net/wifi/WifiLinkLayerStats");
    if (wifiLinkLayerStats == NULL) {
       ALOGE("Error in allocating wifiLinkLayerStats");
       return NULL;
    }

    setIntField(env, wifiLinkLayerStats, "beacon_rx", link_stat.beacon_rx);
    setIntField(env, wifiLinkLayerStats, "rssi_mgmt", link_stat.rssi_mgmt);
    setLongField(env, wifiLinkLayerStats, "rxmpdu_be", link_stat.ac[WIFI_AC_BE].rx_mpdu);
    setLongField(env, wifiLinkLayerStats, "rxmpdu_bk", link_stat.ac[WIFI_AC_BK].rx_mpdu);
    setLongField(env, wifiLinkLayerStats, "rxmpdu_vi", link_stat.ac[WIFI_AC_VI].rx_mpdu);
    setLongField(env, wifiLinkLayerStats, "rxmpdu_vo", link_stat.ac[WIFI_AC_VO].rx_mpdu);
    setLongField(env, wifiLinkLayerStats, "txmpdu_be", link_stat.ac[WIFI_AC_BE].tx_mpdu);
    setLongField(env, wifiLinkLayerStats, "txmpdu_bk", link_stat.ac[WIFI_AC_BK].tx_mpdu);
    setLongField(env, wifiLinkLayerStats, "txmpdu_vi", link_stat.ac[WIFI_AC_VI].tx_mpdu);
    setLongField(env, wifiLinkLayerStats, "txmpdu_vo", link_stat.ac[WIFI_AC_VO].tx_mpdu);
    setLongField(env, wifiLinkLayerStats, "lostmpdu_be", link_stat.ac[WIFI_AC_BE].mpdu_lost);
    setLongField(env, wifiLinkLayerStats, "lostmpdu_bk", link_stat.ac[WIFI_AC_BK].mpdu_lost);
    setLongField(env, wifiLinkLayerStats, "lostmpdu_vi",  link_stat.ac[WIFI_AC_VI].mpdu_lost);
    setLongField(env, wifiLinkLayerStats, "lostmpdu_vo", link_stat.ac[WIFI_AC_VO].mpdu_lost);
    setLongField(env, wifiLinkLayerStats, "retries_be", link_stat.ac[WIFI_AC_BE].retries);
    setLongField(env, wifiLinkLayerStats, "retries_bk", link_stat.ac[WIFI_AC_BK].retries);
    setLongField(env, wifiLinkLayerStats, "retries_vi", link_stat.ac[WIFI_AC_VI].retries);
    setLongField(env, wifiLinkLayerStats, "retries_vo", link_stat.ac[WIFI_AC_VO].retries);


    setIntField(env, wifiLinkLayerStats, "on_time", radio_stat.on_time);
    setIntField(env, wifiLinkLayerStats, "tx_time", radio_stat.tx_time);
    setIntField(env, wifiLinkLayerStats, "rx_time", radio_stat.rx_time);
    setIntField(env, wifiLinkLayerStats, "on_time_scan", radio_stat.on_time_scan);

    return wifiLinkLayerStats;
}

static jint android_net_wifi_getSupportedFeatures(JNIEnv *env, jclass cls, jint iface) {
    wifi_interface_handle handle = getIfaceHandle(env, cls, iface);
    feature_set set = 0;

    wifi_error result = WIFI_SUCCESS;
    /*
    set = WIFI_FEATURE_INFRA
        | WIFI_FEATURE_INFRA_5G
        | WIFI_FEATURE_HOTSPOT
        | WIFI_FEATURE_P2P
        | WIFI_FEATURE_SOFT_AP
        | WIFI_FEATURE_GSCAN
        | WIFI_FEATURE_PNO
        | WIFI_FEATURE_TDLS
        | WIFI_FEATURE_EPR;
    */

    result = wifi_get_supported_feature_set(handle, &set);
    if (result == WIFI_SUCCESS) {
        /* Temporary workaround for RTT capability */
        set = set | WIFI_FEATURE_D2AP_RTT;
        ALOGD("wifi_get_supported_feature_set returned set = 0x%x", set);
        return set;
    } else {
        ALOGD("wifi_get_supported_feature_set returned error = 0x%x", result);
        return 0;
    }
}

static void onRttResults(wifi_request_id id, unsigned num_results, wifi_rtt_result results[]) {
    JNIEnv *env = NULL;
    mVM->AttachCurrentThread(&env, NULL);

    ALOGD("onRttResults called, vm = %p, obj = %p, env = %p", mVM, mCls, env);

    jclass clsRttResult = (env)->FindClass("android/net/wifi/RttManager$RttResult");
    if (clsRttResult == NULL) {
        ALOGE("Error in accessing class");
        return;
    }

    jobjectArray rttResults = env->NewObjectArray(num_results, clsRttResult, NULL);
    if (rttResults == NULL) {
        ALOGE("Error in allocating array");
        return;
    }

    for (unsigned i = 0; i < num_results; i++) {

        wifi_rtt_result& result = results[i];

        jobject rttResult = createObject(env, "android/net/wifi/RttManager$RttResult");
        if (rttResult == NULL) {
            ALOGE("Error in creating rtt result");
            return;
        }

        char bssid[32];
        sprintf(bssid, "%02x:%02x:%02x:%02x:%02x:%02x", result.addr[0], result.addr[1],
            result.addr[2], result.addr[3], result.addr[4], result.addr[5]);

        setStringField(env, rttResult, "bssid", bssid);
        setIntField(env,  rttResult, "status",               result.status);
        setIntField(env,  rttResult, "requestType",          result.type);
        setLongField(env, rttResult, "ts",                   result.ts);
        setIntField(env,  rttResult, "rssi",                 result.rssi);
        setIntField(env,  rttResult, "rssi_spread",          result.rssi_spread);
        setIntField(env,  rttResult, "tx_rate",              result.tx_rate.bitrate);
        setLongField(env, rttResult, "rtt_ns",               result.rtt);
        setLongField(env, rttResult, "rtt_sd_ns",            result.rtt_sd);
        setLongField(env, rttResult, "rtt_spread_ns",        result.rtt_spread);
        setIntField(env,  rttResult, "distance_cm",          result.distance);
        setIntField(env,  rttResult, "distance_sd_cm",       result.distance_sd);
        setIntField(env,  rttResult, "distance_spread_cm",   result.distance_spread);

        env->SetObjectArrayElement(rttResults, i, rttResult);
    }

    reportEvent(env, mCls, "onRttResults", "(I[Landroid/net/wifi/RttManager$RttResult;)V",
        id, rttResults);
}

const int MaxRttConfigs = 16;

static jboolean android_net_wifi_requestRange(
        JNIEnv *env, jclass cls, jint iface, jint id, jobject params)  {

    wifi_interface_handle handle = getIfaceHandle(env, cls, iface);
    ALOGD("sending rtt request [%d] = %p", id, handle);

    wifi_rtt_config configs[MaxRttConfigs];
    memset(&configs, 0, sizeof(configs));

    int len = env->GetArrayLength((jobjectArray)params);
    if (len > MaxRttConfigs) {
        return false;
    }

    for (int i = 0; i < len; i++) {

        jobject param = env->GetObjectArrayElement((jobjectArray)params, i);
        if (param == NULL) {
            ALOGD("could not get element %d", i);
            continue;
        }

        wifi_rtt_config &config = configs[i];

        parseMacAddress(env, param, config.addr);
        config.type = (wifi_rtt_type)getIntField(env, param, "requestType");
        config.peer = (wifi_peer_type)getIntField(env, param, "deviceType");
        config.channel.center_freq = getIntField(env, param, "frequency");
        config.channel.width = (wifi_channel_width)getIntField(env, param, "channelWidth");
        config.num_samples_per_measurement = getIntField(env, param, "num_samples");
        config.num_retries_per_measurement = getIntField(env, param, "num_retries");
    }

    wifi_rtt_event_handler handler;
    handler.on_rtt_results = &onRttResults;

    return wifi_rtt_range_request(id, handle, len, configs, handler) == WIFI_SUCCESS;
}

static jboolean android_net_wifi_cancelRange(
        JNIEnv *env, jclass cls, jint iface, jint id, jobject params)  {

    wifi_interface_handle handle = getIfaceHandle(env, cls, iface);
    ALOGD("cancelling rtt request [%d] = %p", id, handle);

    mac_addr addrs[MaxRttConfigs];
    memset(&addrs, 0, sizeof(addrs));

    int len = env->GetArrayLength((jobjectArray)params);
    if (len > MaxRttConfigs) {
        return false;
    }

    for (int i = 0; i < len; i++) {

        jobject param = env->GetObjectArrayElement((jobjectArray)params, i);
        if (param == NULL) {
            ALOGD("could not get element %d", i);
            continue;
        }

        parseMacAddress(env, param, addrs[i]);
    }

    return wifi_rtt_range_cancel(id, handle, len, addrs) == WIFI_SUCCESS;
}

static jboolean android_net_wifi_setScanningMacOui(JNIEnv *env, jclass cls,
        jint iface, jbyteArray param)  {

    wifi_interface_handle handle = getIfaceHandle(env, cls, iface);
    ALOGD("setting scan oui %p", handle);

    static const unsigned oui_len = 3;          /* OUI is upper 3 bytes of mac_address */
    int len = env->GetArrayLength(param);
    if (len != oui_len) {
        ALOGE("invalid oui length %d", len);
        return false;
    }

    jbyte* bytes = env->GetByteArrayElements(param, NULL);
    if (bytes == NULL) {
        ALOGE("failed to get array");
        return false;
    }
    return WIFI_SUCCESS;
//    return wifi_set_scanning_mac_oui(handle, (byte *)bytes) == WIFI_SUCCESS;
}

static jboolean android_net_wifi_setNetworkVariableCommand(JNIEnv* env,
                                                           jobject,
                                                           jstring jIface,
                                                           jint netId,
                                                           jstring javaName,
                                                           jstring javaValue)
{
    char reply[REPLY_LEN] = {0};
    int isFound = 0; 
    char wrapbuf[BUF_SIZE];
    char chartmp;
    char charout[BUF_SIZE];    
    const char* charptr;
    unsigned short* mptr;
    int n=0;
    unsigned int i=0;
    unsigned int j=0;
    
    ScopedUtfChars name(env, javaName);
    if (name.c_str() == NULL) {
        return JNI_FALSE;
    }
    ScopedUtfChars value(env, javaValue);
    if (value.c_str() == NULL) {
        return JNI_FALSE;
    }
    ScopedUtfChars ifname(env, jIface);
    if (ifname.c_str() == NULL) {
        return JNI_FALSE;
    }
    XLOGD("setNetworkVariableCommand, name:%s, value:%s, netId:%d", name.c_str(), value.c_str(), netId);
    struct accessPointObjectItem *pTmpItemNode = NULL;
    if (0 == strcmp(name.c_str(), "bssid")) {
        if (NULL == g_pCurrentSSID) {
            XLOGE("g_pCurrentSSID is NULL");
            g_pCurrentSSID = new String8();
            if (NULL == g_pCurrentSSID) {
                XLOGE("Failed to allocate memory for g_pCurrentSSID!");
                return JNI_FALSE;
            }
        }
        pthread_mutex_lock(g_pItemListMutex);
        pTmpItemNode = g_pItemList;   
        if (NULL == pTmpItemNode) {
            XLOGD("g_pItemList is NULL");
        }
        while (pTmpItemNode) {
            if (pTmpItemNode->bssid && (0 == strcmp(pTmpItemNode->bssid->string(), value.c_str())) && pTmpItemNode->ssid) {
                *g_pCurrentSSID = *(pTmpItemNode->ssid);
                g_isChSSID = pTmpItemNode->isCh;
                isFound = 1;
                XLOGD("Found bssid:%s, g_pCurrentSSID:%s, g_isChSSID:%d", pTmpItemNode->bssid->string(), g_pCurrentSSID->string(), g_isChSSID);
                break;
            }
            pTmpItemNode = pTmpItemNode->pNext;
        }
        pthread_mutex_unlock(g_pItemListMutex);

        if(isFound == 0  && 0 == strcmp(value.c_str(), "any")) {
            g_isAnyBSSID = true;
        }else {
            g_isAnyBSSID = false;
        }
    }

    if (0 == strcmp(name.c_str(), "ssid") && g_isChSSID) {
        if (DBG) {
            ALOGD("g_pCurrentSSID->string: %s", g_pCurrentSSID->string());
        }    
        g_isChSSID = false;
        g_isAnyBSSID = false;
        return doBooleanCommandTranslated("OK", "IFNAME=%s SET_NETWORK %d %s \"%s\"",ifname.c_str(),  netId, name.c_str(), g_pCurrentSSID->string());
    }else if(0 == strcmp(name.c_str(), "ssid") && g_isAnyBSSID   && !g_isChSSID) {
        ///M: ALPS01770979 auto join cound set BSSID = any, so no isCh would be found
        
        pthread_mutex_lock(g_pItemListMutex);
        XLOGD("BSSID is any");
        pTmpItemNode = g_pItemList;   
        if (NULL == pTmpItemNode) {
            XLOGD("g_pItemList is NULL");
        }

        while (pTmpItemNode) { 
            if(pTmpItemNode->isCh) {
                if (DBG) {
                    XLOGD("check %s", pTmpItemNode->encossid->string());
                    XLOGD("with %s",  value.c_str());

                    const char* encodestr = pTmpItemNode->encossid->string();
                    for(unsigned int v =0;v< strlen(encodestr) ; v++){
                        XLOGD("encodestr[%d]:%c, 0x%x", v, encodestr [v], encodestr [v]);
                    }
                }

                memset(&wrapbuf, 0, sizeof(wrapbuf));               
                charptr = pTmpItemNode->encossid->string();
                j=0;
                for(i=0;i < strlen(pTmpItemNode->encossid->string()); ++i){
                    chartmp = charptr[i]>>4 & 0xF;
                    memset(&charout, 0, sizeof(charout));
                    n = sprintf(charout,"%x",chartmp);
                    wrapbuf[j++] = charout[0];

                    chartmp = charptr[i] & 0xF;
                    memset(&charout, 0, sizeof(charout));
                    n = sprintf(charout,"%x",chartmp);
                    wrapbuf[j++] = charout[0];
                }

                if (DBG) {
                    const char* candidatestr = wrapbuf;
                    for(unsigned int z =0;z< strlen(value.c_str()) ; z++){
                    XLOGD("candidatestr[%d]:%c, 0x%x", z, candidatestr[z], candidatestr[z]);                                      
                    }
                    n = strcmp(pTmpItemNode->encossid->string(), value.c_str());
                    XLOGD("n=%d len=%d len2=%d",n, strlen(wrapbuf),strlen(value.c_str())); 
                }
                 
                if (pTmpItemNode->encossid && (0 == strcmp(wrapbuf, value.c_str())) && pTmpItemNode->ssid) {
                    isFound =1;
                    *g_pCurrentSSID = *(pTmpItemNode->ssid);
                    g_isChSSID = pTmpItemNode->isCh;
                    XLOGD("Found any bssid: ssid:%s, g_pCurrentSSID:%s, g_isChSSID:%d", pTmpItemNode->bssid->string(), g_pCurrentSSID->string(), g_isChSSID);
                    break;
                }
            }
            pTmpItemNode = pTmpItemNode->pNext;
        }
        g_isAnyBSSID = false;
        pthread_mutex_unlock(g_pItemListMutex);
        if(g_isChSSID && isFound) {
            g_isChSSID = false;
            return doBooleanCommandTranslated("OK", "IFNAME=%s SET_NETWORK %d %s \"%s\"",ifname.c_str(),  netId, name.c_str(), g_pCurrentSSID->string());
        } else {
            g_isChSSID = false;
            return doBooleanCommandTranslated("OK", "IFNAME=%s SET_NETWORK %d %s %s", ifname.c_str(),netId, name.c_str(), value.c_str());
        }
        
    }
    else{
        return doBooleanCommandTranslated("OK", "IFNAME=%s SET_NETWORK %d %s %s", ifname.c_str(),netId, name.c_str(), value.c_str());
    }
}

static jintArray android_net_wifi_getValidChannels(JNIEnv *env, jclass cls,
        jint iface, jint band)  {

    wifi_interface_handle handle = getIfaceHandle(env, cls, iface);
    ALOGD("getting valid channels %p", handle);

    static const int MaxChannels = 64;
    wifi_channel channels[64];
    int num_channels = 0;
    wifi_error result = wifi_get_valid_channels(handle, band, MaxChannels,
            channels, &num_channels);

    if (result == WIFI_SUCCESS) {
        jintArray channelArray = env->NewIntArray(num_channels);
        if (channelArray == NULL) {
            ALOGE("failed to allocate channel list");
            return NULL;
        }

        env->SetIntArrayRegion(channelArray, 0, num_channels, channels);
        return channelArray;
    } else {
        ALOGE("failed to get channel list : %d", result);
        return NULL;
    }
}

static jboolean android_net_wifi_setTxPowerEnabledCommand(JNIEnv* env, jobject clazz, jboolean enable)
{
    jboolean result = JNI_FALSE;
    struct iwreq wrq;
    int skfd;
    int32_t au4TxPwr[2] = {4, 1};
    au4TxPwr[1] = enable ? 1 : 0;

    /* initialize socket */
    skfd = socket(PF_INET, SOCK_DGRAM, 0);
    if (skfd < 0) {
        XLOGE("Open socket failed");
        return result;
    }

    /* initliaze WEXT request */
    wrq.u.data.pointer = &(au4TxPwr[0]);
    wrq.u.data.length = 2;
    wrq.u.data.flags = PRIV_CMD_SET_TX_POWER;
    strncpy(wrq.ifr_name, "wlan0", IFNAMSIZ);

    /* do IOCTL */
    if (ioctl(skfd, IOCTL_SET_INTS, &wrq) < 0) {
        XLOGE("setTxPowerEnabledCommand failed");
    } else {
        XLOGD("setTxPowerEnabledCommand succeed");
        result = JNI_TRUE;
    }
    close(skfd);
    return result;
}

static jboolean android_net_wifi_setTxPowerCommand(JNIEnv* env, jobject clazz, jint offset)
{
    jboolean result = JNI_FALSE;
    struct iwreq wrq;
    int skfd;
    int32_t au4TxPwr[4] = {0, 0, 0, 0};
    au4TxPwr[3] = offset;

    /* initialize socket */
    skfd = socket(PF_INET, SOCK_DGRAM, 0);
    if (skfd < 0) {
        XLOGE("Open socket failed");
        return result;
    }

    /* initliaze WEXT request */
    wrq.u.data.pointer = &(au4TxPwr[0]);
    wrq.u.data.length = 4;
    wrq.u.data.flags = PRIV_CMD_SET_TX_POWER;
    strncpy(wrq.ifr_name, "wlan0", IFNAMSIZ);

    /* do IOCTL */
    if (ioctl(skfd, IOCTL_SET_INTS, &wrq) < 0) {
        XLOGE("setTxPowerCommand failed");
    } else {
        XLOGD("setTxPowerCommand succeed");
        result = JNI_TRUE;
    }
    close(skfd);
    return result;
}

static jstring android_net_wifi_getStringCredential(JNIEnv* env, jobject)
{
    char cred[256] = {0};
    size_t cred_len;
    int status = ::wifi_build_cred(cred, &cred_len);
    return env->NewStringUTF(cred);
}


// ----------------------------------------------------------------------------

/*
 * JNI registration.
 */
static JNINativeMethod gWifiMethods[] = {
    /* name, signature, funcPtr */

    { "loadDriver", "()Z",  (void *)android_net_wifi_loadDriver },
    { "isDriverLoaded", "()Z",  (void *)android_net_wifi_isDriverLoaded },
    { "unloadDriver", "()Z",  (void *)android_net_wifi_unloadDriver },
    { "startSupplicant", "(Z)Z",  (void *)android_net_wifi_startSupplicant },
    { "killSupplicant", "(Z)Z",  (void *)android_net_wifi_killSupplicant },
    { "connectToSupplicantNative", "()Z", (void *)android_net_wifi_connectToSupplicant },
    { "closeSupplicantConnectionNative", "()V",
            (void *)android_net_wifi_closeSupplicantConnection },
    ///M: modify API
    { "waitForEventNative", "(Ljava/lang/String;)Ljava/lang/String;", (void*)android_net_wifi_waitForEvent },
    { "doBooleanCommandNative", "(Ljava/lang/String;)Z", (void*)android_net_wifi_doBooleanCommand },
    { "doIntCommandNative", "(Ljava/lang/String;)I", (void*)android_net_wifi_doIntCommand },
    { "doStringCommandNative", "(Ljava/lang/String;)Ljava/lang/String;",
            (void*) android_net_wifi_doStringCommand },
    { "startHalNative", "()Z", (void*) android_net_wifi_startHal },
    { "stopHalNative", "()V", (void*) android_net_wifi_stopHal },
    { "waitForHalEventNative", "()V", (void*) android_net_wifi_waitForHalEvents },
    { "getInterfacesNative", "()I", (void*) android_net_wifi_getInterfaces},
    { "getInterfaceNameNative", "(I)Ljava/lang/String;", (void*) android_net_wifi_getInterfaceName},
    { "getScanCapabilitiesNative", "(ILcom/android/server/wifi/WifiNative$ScanCapabilities;)Z",
            (void *) android_net_wifi_getScanCapabilities},
    { "startScanNative", "(IILcom/android/server/wifi/WifiNative$ScanSettings;)Z",
            (void*) android_net_wifi_startScan},
    { "stopScanNative", "(II)Z", (void*) android_net_wifi_stopScan},
    { "getScanResultsNative", "(IZ)[Landroid/net/wifi/ScanResult;",
            (void *) android_net_wifi_getScanResults},
    { "setHotlistNative", "(IILandroid/net/wifi/WifiScanner$HotlistSettings;)Z",
            (void*) android_net_wifi_setHotlist},
    { "resetHotlistNative", "(II)Z", (void*) android_net_wifi_resetHotlist},
    { "trackSignificantWifiChangeNative", "(IILandroid/net/wifi/WifiScanner$WifiChangeSettings;)Z",
            (void*) android_net_wifi_trackSignificantWifiChange},
    { "untrackSignificantWifiChangeNative", "(II)Z",
            (void*) android_net_wifi_untrackSignificantWifiChange},
    { "getWifiLinkLayerStatsNative", "(I)Landroid/net/wifi/WifiLinkLayerStats;",
            (void*) android_net_wifi_getLinkLayerStats},
    { "getSupportedFeatureSetNative", "(I)I",
            (void*) android_net_wifi_getSupportedFeatures},
    { "requestRangeNative", "(II[Landroid/net/wifi/RttManager$RttParams;)Z",
            (void*) android_net_wifi_requestRange},
    { "cancelRangeRequestNative", "(II[Landroid/net/wifi/RttManager$RttParams;)Z",
            (void*) android_net_wifi_cancelRange},
    { "setScanningMacOuiNative", "(I[B)Z", (void*) android_net_wifi_setScanningMacOui},
    { "getChannelsForBandNative", "(II)[I", (void*) android_net_wifi_getValidChannels},

    ///M: add the following
    { "setNetworkVariableCommand", "(Ljava/lang/String;ILjava/lang/String;Ljava/lang/String;)Z",
            (void*) android_net_wifi_setNetworkVariableCommand },
    { "setTxPowerEnabled", "(Z)Z", (void*) android_net_wifi_setTxPowerEnabledCommand },
    { "setTxPower", "(I)Z", (void*) android_net_wifi_setTxPowerCommand },
    { "getCredential", "()Ljava/lang/String;", (void*) android_net_wifi_getStringCredential }

};

int register_android_net_wifi_WifiNative(JNIEnv* env) {
    return AndroidRuntime::registerNativeMethods(env,
            "com/android/server/wifi/WifiNative", gWifiMethods, NELEM(gWifiMethods));
}


/* User to register native functions */
extern "C"
jint Java_com_android_server_wifi_WifiNative_registerNatives(JNIEnv* env, jclass clazz) {
    return AndroidRuntime::registerNativeMethods(env,
            "com/android/server/wifi/WifiNative", gWifiMethods, NELEM(gWifiMethods));
}

}; // namespace android
