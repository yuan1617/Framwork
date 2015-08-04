#define LOG_TAG "AudioMTKLosslessBTBroadcast"

#include "AudioLosslessBTBroadcast.h"
#include <cutils/properties.h>
#include <utils/String16.h>
#include <binder/BinderService.h>
#include <binder/Parcel.h>

#ifdef LOG_TAG
#undef LOG_TAG
#endif
#define LOG_TAG "AudioLosslessBTBroadcast"

#define LOSSLESS_BT_UI_CMD_PLAYING   "android.intent.action.LOSSLESS_PLAYING"
#define LOSSLESS_BT_UI_CMD_CLOSE  "android.intent.action.LOSSLESS_CLOSE"
#define LOSSLESS_BT_UI_CMD_STOP "android.intent.action.LOSSLESS_STOP"
#define LOSSLESS_BT_UI_CMD_NOT_SUPPORT "android.intent.action.LOSSLESS_NOT_SUPPORT"
#define LOSSLESS_BT_UI_CMD_MUSIC_ENABLE "com.android.lossessbt.enable"
#define LOSSLESS_BT_UI_CMD_MUSIC_DISABLE "com.android.lossessbt.disable"
#define LOSSLESS_BT_UI_CMD_MUSIC_PAUSE "android.media.AUDIO_BECOMING_NOISY"

#ifdef TYPE
    #undef TYPE
#endif

#define TYPE "type"
#define MAX_LENGTH 1024

namespace android {


bool sendBroadcastMessage(String16 action, int value)
{
    ALOGD("sendBroadcastMessage(): Action: %s, Value: %d ", action.string(), value);
    char property_val[PROPERTY_VALUE_MAX];
    bool isReady = (property_get("sys.boot_completed", property_val, "0") > 0) && (atoi(property_val) == 1);
    if (!isReady) {
        ALOGD("boot_completed NOT READY");
        usleep(1000*1000);
        return false;
    }
    sp<IServiceManager> sm = defaultServiceManager();
    sp<IBinder> am = sm->getService(String16("activity"));
    if (am != NULL) {
        Parcel data, reply;
        data.writeInterfaceToken(String16("android.app.IActivityManager"));
        data.writeStrongBinder(NULL);
        // intent begin
        data.writeString16(action); // action
        data.writeInt32(0); // URI data type
        data.writeString16(NULL, 0); // type
        data.writeInt32(0); // flags
        data.writeString16(NULL, 0); // package name
        data.writeString16(NULL, 0); // component name
        data.writeInt32(0); // source bound - size
        data.writeInt32(0); // categories - size
        data.writeInt32(0); // selector - size
        data.writeInt32(0); // clipData - size
        data.writeInt32(-2); // contentUserHint: -2 -> UserHandle.USER_CURRENT
        data.writeInt32(-1); // bundle extras length
        data.writeInt32(0x4C444E42); // 'B' 'N' 'D' 'L'
        int oldPos = data.dataPosition();
        data.writeInt32(1);  // size
        // data.writeInt32(0); // VAL_STRING, need to remove because of analyze common intent
        data.writeString16(String16(TYPE));
        data.writeInt32(1); // VAL_INTEGER
        data.writeInt32(value);
        int newPos = data.dataPosition();
        data.setDataPosition(oldPos - 8);
        data.writeInt32(newPos - oldPos); // refill bundle extras length
        data.setDataPosition(newPos);
        // intent end
        data.writeString16(NULL, 0); // resolvedType
        data.writeStrongBinder(NULL); // resultTo
        data.writeInt32(0); // resultCode
        data.writeString16(NULL, 0); // resultData
        data.writeInt32(-1); // resultExtras
        data.writeString16(NULL, 0); // permission
        data.writeInt32(0); // appOp
        data.writeInt32(1); // serialized: != 0 -> ordered
        data.writeInt32(0); // sticky
        //data.writeInt32(-2); // userId: -2 -> UserHandle.USER_CURRENT
        data.writeInt32(0); // ship fixed

        status_t ret = am->transact(IBinder::FIRST_CALL_TRANSACTION + 13, data, &reply); // BROADCAST_INTENT_TRANSACTION
        if (ret == NO_ERROR) {
            int exceptionCode = reply.readExceptionCode();
            if (exceptionCode) {
                ALOGE("sendBroadcastMessage(%s) caught exception %d\n",
                        action.string(), exceptionCode);
                return false;
            }
        } else {
            return false;
        }
    } else {
        ALOGE("getService() couldn't find activity service!\n");
        return false;
    }
    return true;
}

int PauseAudioPlayer(){
    ALOGD("PauseAudioPlayer");

    String16 cmdstr = String16(LOSSLESS_BT_UI_CMD_MUSIC_PAUSE);
    bool result = sendBroadcastMessage(cmdstr, 1);
    ALOGD("PauseAudioPlayer: result=%d", result);
    return result;    
}

int RestartAudioPlayer(bool enable){
    ALOGD("RestartAudioPlayer");
    PauseAudioPlayer();
    String16 cmdstr = enable ? String16(LOSSLESS_BT_UI_CMD_MUSIC_ENABLE) : String16(LOSSLESS_BT_UI_CMD_MUSIC_DISABLE);
    bool result = sendBroadcastMessage(cmdstr, 1);
    ALOGD("RestartAudioPlayer:%d, result=%d",enable , result);
    return result;    
}

void* AudioFlinger::ReadLLBTCommandThread(void *me)
{
    return (void *) static_cast<AudioFlinger *>(me)->ReadLLBTCommand();
}



status_t AudioFlinger::ReadLLBTCommand()
{   
    ALOGD("AudioFlinger::ReadLosslessBTCommand()");

    char value[PROPERTY_VALUE_MAX];
    mIsLosslessBTOn = (property_get(LOSSLESS_BT_PROP_NAME, value, "0") > 0) && (atoi(value) == 1);
    SetLosslessBTStatus(mIsLosslessBTOn);

    mIsLosslessBTVolumeSatisfied = false;
    mIsLosslessBTPlaying = false;
    mIsLosslessBTWorking = false;
    mIsLosslessBTSupport = false;
    mIsLosslessBTAbsoluteVolume = false;
    mIsLosslessBTVaild = 1;

    while(1){
        int new_status = false;
        const char *cmd;

        if(mIsLosslessBTVaild <= 0){
            usleep(800*1000);
            continue;
        }

        ALOGD("mIsLosslessBTOn:%d, mIsLosslessBTVolumeSatisfied:%d, mIsLosslessBTPlaying:%d", 
                        mIsLosslessBTOn, mIsLosslessBTVolumeSatisfied, mIsLosslessBTPlaying);

        if(mIsLosslessBTOn){
            if(mIsLosslessBTSupport){
                if(mIsLosslessBTVolumeSatisfied || mIsLosslessBTAbsoluteVolume){
                    cmd = LOSSLESS_BT_UI_CMD_PLAYING;
                    new_status = true;
                }else{
                    cmd = LOSSLESS_BT_UI_CMD_STOP;                
                }
            }else{
                cmd = LOSSLESS_BT_UI_CMD_NOT_SUPPORT;
            }    
        }else{
            cmd = LOSSLESS_BT_UI_CMD_CLOSE;
        }
        String16 cmdstr = String16(cmd);
        bool result = sendBroadcastMessage(cmdstr, 1);
        ALOGD("run2: %s, result2=%d",cmdstr.string() , result);
        if(!result){
            usleep(1000*1000);
            continue;
        }

        if(new_status != mIsLosslessBTWorking)
        {
            if(RestartAudioPlayer(new_status)){ //notify send success
                mIsLosslessBTWorking = new_status;
                //set invalid stream
                for (int i = 0; i < AUDIO_STREAM_CNT; i++) {
                    invalidateStream((audio_stream_type_t)i);
                }
            }
        }

        android_atomic_dec(&mIsLosslessBTVaild);
    }
    
    return NO_ERROR;
}

/*
//set ouyt
sp<PlaybackThread> thread;
{
    Mutex::Autolock _l(mLock);
    for (size_t i = 0; i < mPlaybackThreads.size(); i++) {
        thread = mPlaybackThreads.valueAt(i);
        if(thread->mOutDevice & AUDIO_DEVICE_OUT_BLUETOOTH_A2DP){                                
            struct audio_stream *s = &(thread->mOutput->stream->common);                                
            if(s->get_format(s) & AUDIO_FORMAT_PCM_16_BIT){
                //set invalid thread
            }
        }
    }
*/



// ----------------------------------------------------------------------------
}; // namespace android
