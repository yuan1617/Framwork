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

#define LOG_TAG "AudioPolicyService"
//#define LOG_NDEBUG 0

#include "Configuration.h"
#undef __STRICT_ANSI__
#define __STDINT_LIMITS
#define __STDC_LIMIT_MACROS
#include <stdint.h>

#include <sys/time.h>
#include <binder/IServiceManager.h>
#include <utils/Log.h>
#include <cutils/properties.h>
#include <binder/IPCThreadState.h>
#include <utils/String16.h>
#include <utils/threads.h>
#include "AudioPolicyService.h"
#include "ServiceUtilities.h"
#include <hardware_legacy/power.h>
#include <media/AudioEffect.h>
#include <media/EffectsFactoryApi.h>

#include <hardware/hardware.h>
#include <system/audio.h>
#include <system/audio_policy.h>
#include <hardware/audio_policy.h>

#ifdef MTK_AUDIO
#include <cutils/xlog.h>
#include <linux/rtpm_prio.h>
#include <AudioPolicyParameters.h>
//#include <hardware/audio_policy_mtk.h>
#if defined(CONFIG_MT_ENG_BUILD)
#define MTK_AUDIO_COMMAND_QUEUE_DEBUG
#endif
#endif

#ifdef MTK_AUDIO
#define MTK_ALOG_V(fmt, arg...) SXLOGV(fmt, ##arg)
#define MTK_ALOG_D(fmt, arg...) SXLOGD(fmt, ##arg)
#define MTK_ALOG_W(fmt, arg...) SXLOGW(fmt, ##arg)
#define MTK_ALOG_E(fmt, arg...) SXLOGE("Err: %5d:, "fmt, __LINE__, ##arg)
#undef  ALOGV
#define ALOGV   MTK_ALOG_V
#else
#define MTK_ALOG_V(fmt, arg...) do { } while(0)
#define MTK_ALOG_D(fmt, arg...) do { } while(0)
#define MTK_ALOG_W(fmt, arg...) do { } while(0)
#define MTK_ALOG_E(fmt, arg...) do { } while(0)
#endif

namespace android {

#ifdef MTK_AUDIO
// return true if string need to be filter
static bool ParaMetersNeedFilter(String8 Pair){
    //String8 FilterString1(keyAddtForceuseNormal);
    String8 FilterString2(keyInitVoume);
    String8 FilterString3(keySetStreamStart);
    String8 FilterString4(keySetStreamStop);
    String8 FilterString5(keySetRecordStreamStart);
    String8 FilterString6(keySetRecordStreamStop);
    String8 FilterString7("force_standby");
    MTK_ALOG_V("ParaMetersNeedFilter Pair = %s,",Pair.string ());
    if(FilterString2 == Pair || FilterString3 == Pair
	   || FilterString4 == Pair || FilterString5 == Pair || FilterString6 == Pair||FilterString7 == Pair){
        MTK_ALOG_D("Pair = %s",Pair.string ());
        return false;
    }
    return true;
}
#endif //MTK_AUDIO

static const char kDeadlockedString[] = "AudioPolicyService may be deadlocked\n";
static const char kCmdDeadlockedString[] = "AudioPolicyService command thread may be deadlocked\n";

static const int kDumpLockRetries = 50;
static const int kDumpLockSleepUs = 20000;

static const nsecs_t kAudioCommandTimeoutNs = seconds(3); // 3 seconds

namespace {
    extern struct audio_policy_service_ops aps_ops;

};

// ----------------------------------------------------------------------------


//<MTK_AUDIO_ADD

//static
void AudioPolicyService::AudioEarphoneCallback(void *  user,int device, bool on)
{
#ifdef MTK_AUDIO
    ALOGD("+AudioEarphoneCallback device 0x%x, on %d",device,on);
    AudioPolicyService * policy = (AudioPolicyService * )user;
    const char * addr="";
    audio_policy_dev_state_t state = \
        on ? AUDIO_POLICY_DEVICE_STATE_AVAILABLE : AUDIO_POLICY_DEVICE_STATE_UNAVAILABLE;
    
     policy->setDeviceConnectionState((audio_devices_t)device,state,addr);
    ALOGD("-AudioEarphoneCallback device 0x%x, on %d",device,on);
#endif
}
//MTK_AUDIO_ADD>

AudioPolicyService::AudioPolicyService()
    : BnAudioPolicyService(), mpAudioPolicyDev(NULL), mpAudioPolicy(NULL),
      mAudioPolicyManager(NULL), mAudioPolicyClient(NULL), mPhoneState(AUDIO_MODE_INVALID)
{
}

void AudioPolicyService::onFirstRef()
{
    char value[PROPERTY_VALUE_MAX];
    const struct hw_module_t *module;
    int forced_val;
    int rc;

    {
        Mutex::Autolock _l(mLock);

        // start tone playback thread
        mTonePlaybackThread = new AudioCommandThread(String8("ApmTone"), this);
        // start audio commands thread
        mAudioCommandThread = new AudioCommandThread(String8("ApmAudio"), this);
        // start output activity command thread
        mOutputCommandThread = new AudioCommandThread(String8("ApmOutput"), this);

#ifdef USE_LEGACY_AUDIO_POLICY
        ALOGI("AudioPolicyService CSTOR in legacy mode");

        /* instantiate the audio policy manager */
        rc = hw_get_module(AUDIO_POLICY_HARDWARE_MODULE_ID, &module);
        if (rc) {
            return;
        }
        rc = audio_policy_dev_open(module, &mpAudioPolicyDev);
        ALOGE_IF(rc, "couldn't open audio policy device (%s)", strerror(-rc));
        if (rc) {
            return;
        }

        rc = mpAudioPolicyDev->create_audio_policy(mpAudioPolicyDev, &aps_ops, this,
                                                   /*<MTK_AUDIO_ADD*/(struct audio_policy **)/*MTK_AUDIO_ADD>*/&mpAudioPolicy);
        ALOGE_IF(rc, "couldn't create audio policy (%s)", strerror(-rc));
        if (rc) {
            return;
        }

        rc = mpAudioPolicy->init_check(mpAudioPolicy);
        ALOGE_IF(rc, "couldn't init_check the audio policy (%s)", strerror(-rc));
        if (rc) {
            return;
        }
        ALOGI("Loaded audio policy from %s (%s)", module->name, module->id);
#else
        ALOGI("AudioPolicyService CSTOR in new mode");

        mAudioPolicyClient = new AudioPolicyClient(this);
        mAudioPolicyManager = createAudioPolicyManager(mAudioPolicyClient);
#endif
    }
    // load audio processing modules
    sp<AudioPolicyEffects>audioPolicyEffects = new AudioPolicyEffects();
    {
        Mutex::Autolock _l(mLock);
        mAudioPolicyEffects = audioPolicyEffects;
    }
    
#ifdef MTK_AUDIO
//#ifdef MTK_HEADSET_DETECT
    mHeadsetDetect = new HeadsetDetect(this,&AudioEarphoneCallback);  //earphone callback
    if(mHeadsetDetect) mHeadsetDetect->start();
//#endif    
#endif
}

AudioPolicyService::~AudioPolicyService()
{
    mTonePlaybackThread->exit();
    mAudioCommandThread->exit();
    mOutputCommandThread->exit();

#ifdef USE_LEGACY_AUDIO_POLICY
    if (mpAudioPolicy != NULL && mpAudioPolicyDev != NULL) {
        mpAudioPolicyDev->destroy_audio_policy(mpAudioPolicyDev, mpAudioPolicy);
    }
    if (mpAudioPolicyDev != NULL) {
        audio_policy_dev_close(mpAudioPolicyDev);
    }
#else
    destroyAudioPolicyManager(mAudioPolicyManager);
    delete mAudioPolicyClient;
#endif

    mNotificationClients.clear();
    mAudioPolicyEffects.clear();
    
#ifdef MTK_AUDIO
//#ifdef MTK_HEADSET_DETECT

    if(mHeadsetDetect)
        delete mHeadsetDetect;
//#endif        
#endif
}

// A notification client is always registered by AudioSystem when the client process
// connects to AudioPolicyService.
void AudioPolicyService::registerClient(const sp<IAudioPolicyServiceClient>& client)
{

    Mutex::Autolock _l(mLock);

    uid_t uid = IPCThreadState::self()->getCallingUid();
    if (mNotificationClients.indexOfKey(uid) < 0) {
        sp<NotificationClient> notificationClient = new NotificationClient(this,
                                                                           client,
                                                                           uid);
        ALOGV("registerClient() client %p, uid %d", client.get(), uid);

        mNotificationClients.add(uid, notificationClient);

        sp<IBinder> binder = client->asBinder();
        binder->linkToDeath(notificationClient);
    }
}

// removeNotificationClient() is called when the client process dies.
void AudioPolicyService::removeNotificationClient(uid_t uid)
{
    Mutex::Autolock _l(mLock);

    mNotificationClients.removeItem(uid);

#ifndef USE_LEGACY_AUDIO_POLICY
        if (mAudioPolicyManager) {
            mAudioPolicyManager->clearAudioPatches(uid);
        }
#endif
}

void AudioPolicyService::onAudioPortListUpdate()
{
    mOutputCommandThread->updateAudioPortListCommand();
}

void AudioPolicyService::doOnAudioPortListUpdate()
{
    Mutex::Autolock _l(mLock);
    for (size_t i = 0; i < mNotificationClients.size(); i++) {
        mNotificationClients.valueAt(i)->onAudioPortListUpdate();
    }
}

void AudioPolicyService::onAudioPatchListUpdate()
{
    mOutputCommandThread->updateAudioPatchListCommand();
}

status_t AudioPolicyService::clientCreateAudioPatch(const struct audio_patch *patch,
                                                audio_patch_handle_t *handle,
                                                int delayMs)
{
    return mAudioCommandThread->createAudioPatchCommand(patch, handle, delayMs);
}

status_t AudioPolicyService::clientReleaseAudioPatch(audio_patch_handle_t handle,
                                                 int delayMs)
{
    return mAudioCommandThread->releaseAudioPatchCommand(handle, delayMs);
}

void AudioPolicyService::doOnAudioPatchListUpdate()
{
    Mutex::Autolock _l(mLock);
    for (size_t i = 0; i < mNotificationClients.size(); i++) {
        mNotificationClients.valueAt(i)->onAudioPatchListUpdate();
    }
}

status_t AudioPolicyService::clientSetAudioPortConfig(const struct audio_port_config *config,
                                                      int delayMs)
{
    return mAudioCommandThread->setAudioPortConfigCommand(config, delayMs);
}

status_t AudioPolicyService::getCustomAudioVolume(AUDIO_CUSTOM_VOLUME_STRUCT* pCustomVol)
{
#ifdef MTK_AUDIO
    return mAudioCommandThread->getCustomAudioVolumeCommand(pCustomVol);
#else
    return INVALID_OPERATION;
#endif
}


AudioPolicyService::NotificationClient::NotificationClient(const sp<AudioPolicyService>& service,
                                                     const sp<IAudioPolicyServiceClient>& client,
                                                     uid_t uid)
    : mService(service), mUid(uid), mAudioPolicyServiceClient(client)
{
}

AudioPolicyService::NotificationClient::~NotificationClient()
{
}

void AudioPolicyService::NotificationClient::binderDied(const wp<IBinder>& who __unused)
{
    sp<NotificationClient> keep(this);
    sp<AudioPolicyService> service = mService.promote();
    if (service != 0) {
        service->removeNotificationClient(mUid);
    }
}

void AudioPolicyService::NotificationClient::onAudioPortListUpdate()
{
    if (mAudioPolicyServiceClient != 0) {
        mAudioPolicyServiceClient->onAudioPortListUpdate();
    }
}

void AudioPolicyService::NotificationClient::onAudioPatchListUpdate()
{
    if (mAudioPolicyServiceClient != 0) {
        mAudioPolicyServiceClient->onAudioPatchListUpdate();
    }
}

void AudioPolicyService::binderDied(const wp<IBinder>& who) {
    ALOGW("binderDied() %p, calling pid %d", who.unsafe_get(),
            IPCThreadState::self()->getCallingPid());
}

static bool tryLock(Mutex& mutex)
{
    bool locked = false;
    for (int i = 0; i < kDumpLockRetries; ++i) {
        if (mutex.tryLock() == NO_ERROR) {
            locked = true;
            break;
        }
        usleep(kDumpLockSleepUs);
    }
    return locked;
}

status_t AudioPolicyService::dumpInternals(int fd)
{
    const size_t SIZE = 256;
    char buffer[SIZE];
    String8 result;

#ifdef USE_LEGACY_AUDIO_POLICY
    snprintf(buffer, SIZE, "PolicyManager Interface: %p\n", mpAudioPolicy);
#else
    snprintf(buffer, SIZE, "AudioPolicyManager: %p\n", mAudioPolicyManager);
#endif
    result.append(buffer);
    snprintf(buffer, SIZE, "Command Thread: %p\n", mAudioCommandThread.get());
    result.append(buffer);
    snprintf(buffer, SIZE, "Tones Thread: %p\n", mTonePlaybackThread.get());
    result.append(buffer);

    write(fd, result.string(), result.size());
    return NO_ERROR;
}

status_t AudioPolicyService::dump(int fd, const Vector<String16>& args __unused)
{
    if (!dumpAllowed()) {
        dumpPermissionDenial(fd);
    } else {
        bool locked = tryLock(mLock);
        if (!locked) {
            String8 result(kDeadlockedString);
            write(fd, result.string(), result.size());
        }

        dumpInternals(fd);
        if (mAudioCommandThread != 0) {
            mAudioCommandThread->dump(fd);
        }
        if (mTonePlaybackThread != 0) {
            mTonePlaybackThread->dump(fd);
        }

#ifdef USE_LEGACY_AUDIO_POLICY
        if (mpAudioPolicy) {
            mpAudioPolicy->dump(mpAudioPolicy, fd);
        }
#else
        if (mAudioPolicyManager) {
            mAudioPolicyManager->dump(fd);
        }
#endif

        if (locked) mLock.unlock();
    }
    return NO_ERROR;
}

status_t AudioPolicyService::dumpPermissionDenial(int fd)
{
    const size_t SIZE = 256;
    char buffer[SIZE];
    String8 result;
    snprintf(buffer, SIZE, "Permission Denial: "
            "can't dump AudioPolicyService from pid=%d, uid=%d\n",
            IPCThreadState::self()->getCallingPid(),
            IPCThreadState::self()->getCallingUid());
    result.append(buffer);
    write(fd, result.string(), result.size());
    return NO_ERROR;
}

status_t AudioPolicyService::onTransact(
        uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    return BnAudioPolicyService::onTransact(code, data, reply, flags);
}


// -----------  AudioPolicyService::AudioCommandThread implementation ----------

AudioPolicyService::AudioCommandThread::AudioCommandThread(String8 name,
                                                           const wp<AudioPolicyService>& service)
    : Thread(false), mName(name), mService(service)
{
    mpToneGenerator = NULL;
}


AudioPolicyService::AudioCommandThread::~AudioCommandThread()
{
    if (!mAudioCommands.isEmpty()) {
        release_wake_lock(mName.string());
    }
    mAudioCommands.clear();
    delete mpToneGenerator;
}

void AudioPolicyService::AudioCommandThread::onFirstRef()
{
    run(mName.string(), ANDROID_PRIORITY_AUDIO);
}

//<MTK_AUDIO_ADD
status_t AudioPolicyService::AudioCommandThread::readyToRun()
{
#ifdef MTK_AUDIO
#if MTK_USE_RT_PRIORITY
    struct sched_param sched_p;
    sched_getparam(0, &sched_p);
    sched_p.sched_priority = RTPM_PRIO_AUDIO_COMMAND;
    if(0 != sched_setscheduler(0, SCHED_RR, &sched_p)) {
 	      MTK_ALOG_E("[%s] failed, errno: %d", __func__, errno);
    }
    else {
        sched_p.sched_priority = RTPM_PRIO_AUDIO_COMMAND;
        sched_getparam(0, &sched_p);
        MTK_ALOG_D("sched_setscheduler ok, priority: %d", sched_p.sched_priority);
    }
#endif
#endif
	return NO_ERROR;
}
//MTK_AUDIO_ADD>
bool AudioPolicyService::AudioCommandThread::threadLoop()
{
    nsecs_t waitTime = INT64_MAX;

    mLock.lock();
    while (!exitPending())
    {
        sp<AudioPolicyService> svc;
        while (!mAudioCommands.isEmpty() && !exitPending()) {
            nsecs_t curTime = systemTime();
            // commands are sorted by increasing time stamp: execute them from index 0 and up
            if (mAudioCommands[0]->mTime <= curTime) {
                sp<AudioCommand> command = mAudioCommands[0];
                mAudioCommands.removeAt(0);
                mLastCommand = command;

                switch (command->mCommand) {
                case START_TONE: {
                    mLock.unlock();
                    ToneData *data = (ToneData *)command->mParam.get();
                    ALOGV("AudioCommandThread() processing start tone %d on stream %d",
                            data->mType, data->mStream);
                    delete mpToneGenerator;
#ifdef MTK_AUDIO_COMMAND_QUEUE_DEBUG
                    ALOGD("+AudioCommandThread START_TONE tone %d stream %d",data->mType,data->mStream);
#endif
                    mpToneGenerator = new ToneGenerator(data->mStream, 1.0);
                    mpToneGenerator->startTone(data->mType);
#ifdef MTK_AUDIO_COMMAND_QUEUE_DEBUG
                    ALOGD("-AudioCommandThread %d",command->mCommand);
#endif
                    mLock.lock();
                    }break;
                case STOP_TONE: {
                    mLock.unlock();
                    ALOGV("AudioCommandThread() processing stop tone");
#ifdef MTK_AUDIO_COMMAND_QUEUE_DEBUG
                    ALOGD("+AudioCommandThread STOP_TONE");
#endif
                    if (mpToneGenerator != NULL) {
                        mpToneGenerator->stopTone();
                        delete mpToneGenerator;
                        mpToneGenerator = NULL;
                    }
#ifdef MTK_AUDIO_COMMAND_QUEUE_DEBUG
                    ALOGD("-AudioCommandThread %d",command->mCommand);
#endif
                    mLock.lock();
                    }break;
                case SET_VOLUME: {
                    VolumeData *data = (VolumeData *)command->mParam.get();
#ifdef MTK_AUDIO_COMMAND_QUEUE_DEBUG
                    ALOGD("AudioCommandThread() processing set volume stream %d, \
                            volume %f, output %d", data->mStream, data->mVolume, data->mIO);
#else
                    ALOGV("AudioCommandThread() processing set volume stream %d, \
                            volume %f, output %d", data->mStream, data->mVolume, data->mIO);
#endif
                    command->mStatus = AudioSystem::setStreamVolume(data->mStream,
                                                                    data->mVolume,
                                                                    data->mIO);
#ifdef MTK_AUDIO_COMMAND_QUEUE_DEBUG
                    ALOGD("-AudioCommandThread %d",command->mCommand);
#endif
                    }break;
                case SET_PARAMETERS: {
                    ParametersData *data = (ParametersData *)command->mParam.get();
#ifdef MTK_AUDIO_COMMAND_QUEUE_DEBUG
                    ALOGD("+AudioCommandThread SET_PARAMETERS %s %d",data->mKeyValuePairs.string(), data->mIO);
#else
                    ALOGV("AudioCommandThread() processing set parameters string %s, io %d",
                            data->mKeyValuePairs.string(), data->mIO);
#endif
                    command->mStatus = AudioSystem::setParameters(data->mIO, data->mKeyValuePairs);
#ifdef MTK_AUDIO_COMMAND_QUEUE_DEBUG
                    ALOGD("-AudioCommandThread %d",command->mCommand);
#endif

                    }break;
                case SET_VOICE_VOLUME: {
                    VoiceVolumeData *data = (VoiceVolumeData *)command->mParam.get();
#ifdef MTK_AUDIO_COMMAND_QUEUE_DEBUG
                    ALOGD("AudioCommandThread SET_VOICE_VOLUME volume %f",
                            data->mVolume);
#else
                    ALOGV("AudioCommandThread() processing set voice volume volume %f",
                            data->mVolume);
#endif
                    command->mStatus = AudioSystem::setVoiceVolume(data->mVolume);
#ifdef MTK_AUDIO_COMMAND_QUEUE_DEBUG
                    ALOGD("-AudioCommandThread %d",command->mCommand);
#endif

                    }break;
                case STOP_OUTPUT: {
                    StopOutputData *data = (StopOutputData *)command->mParam.get();
#ifdef MTK_AUDIO_COMMAND_QUEUE_DEBUG
                    ALOGD("AudioCommandThread STOP_OUTPUT %d",
                            data->mIO);
#else
                    ALOGV("AudioCommandThread() processing stop output %d",
                            data->mIO);
#endif
                    svc = mService.promote();
                    if (svc == 0) {
                        break;
                    }
                    mLock.unlock();
                    svc->doStopOutput(data->mIO, data->mStream, data->mSession);
                    mLock.lock();
#ifdef MTK_AUDIO_COMMAND_QUEUE_DEBUG
                    ALOGD("-AudioCommandThread %d",command->mCommand);
#endif

                    }break;
                case RELEASE_OUTPUT: {
                    ReleaseOutputData *data = (ReleaseOutputData *)command->mParam.get();
#ifdef MTK_AUDIO_COMMAND_QUEUE_DEBUG
                    ALOGD("AudioCommandThread RELEASE_OUTPUT %d",data->mIO);
#else
                    ALOGV("AudioCommandThread() processing release output %d",
                            data->mIO);
#endif                           
                    svc = mService.promote();
                    if (svc == 0) {
                        break;
                    }
                    mLock.unlock();
                    svc->doReleaseOutput(data->mIO);
                    mLock.lock();
#ifdef MTK_AUDIO_COMMAND_QUEUE_DEBUG
                    ALOGD("-AudioCommandThread %d",command->mCommand);
#endif

                    }break;
                case CREATE_AUDIO_PATCH: {
                    CreateAudioPatchData *data = (CreateAudioPatchData *)command->mParam.get();
#ifdef MTK_AUDIO_COMMAND_QUEUE_DEBUG
                    ALOGD("AudioCommandThread CREATE_AUDIO_PATCH");
#else

                    ALOGV("AudioCommandThread() processing create audio patch");
#endif             
                    sp<IAudioFlinger> af = AudioSystem::get_audio_flinger();
                    if (af == 0) {
                        command->mStatus = PERMISSION_DENIED;
                    } else {
                        command->mStatus = af->createAudioPatch(&data->mPatch, &data->mHandle);
                    }
#ifdef MTK_AUDIO_COMMAND_QUEUE_DEBUG
                    ALOGD("-AudioCommandThread %d",command->mCommand);
#endif
                    } break;
                case RELEASE_AUDIO_PATCH: {
                    ReleaseAudioPatchData *data = (ReleaseAudioPatchData *)command->mParam.get();
#ifdef MTK_AUDIO_COMMAND_QUEUE_DEBUG
                    ALOGD("AudioCommandThread RELEASE_AUDIO_PATCH %d" );
#else 
                    ALOGV("AudioCommandThread() processing release audio patch");
#endif
                    sp<IAudioFlinger> af = AudioSystem::get_audio_flinger();
                    if (af == 0) {
                        command->mStatus = PERMISSION_DENIED;
                    } else {
                        command->mStatus = af->releaseAudioPatch(data->mHandle);
                    }
#ifdef MTK_AUDIO_COMMAND_QUEUE_DEBUG
                    ALOGD("-AudioCommandThread %d",command->mCommand);
#endif
                    } break;
                case UPDATE_AUDIOPORT_LIST: {
#ifdef MTK_AUDIO_COMMAND_QUEUE_DEBUG
                    ALOGD("AudioCommandThread UPDATE_AUDIOPORT_LIST %d");
#else 
                    ALOGV("AudioCommandThread() processing update audio port list");
#endif
                    svc = mService.promote();
                    if (svc == 0) {
                        break;
                    }
                    mLock.unlock();
                    svc->doOnAudioPortListUpdate();
                    mLock.lock();
#ifdef MTK_AUDIO_COMMAND_QUEUE_DEBUG
                    ALOGD("-AudioCommandThread %d",command->mCommand);
#endif
                    }break;
                case UPDATE_AUDIOPATCH_LIST: {
#ifdef MTK_AUDIO_COMMAND_QUEUE_DEBUG
                    ALOGD("AudioCommandThread UPDATE_AUDIOPATCH_LIST " );
#else 
                    ALOGV("AudioCommandThread() processing update audio patch list");
#endif
                    svc = mService.promote();
                    if (svc == 0) {
                        break;
                    }
                    mLock.unlock();
                    svc->doOnAudioPatchListUpdate();
                    mLock.lock();
#ifdef MTK_AUDIO_COMMAND_QUEUE_DEBUG
                    ALOGD("-AudioCommandThread %d",command->mCommand);
#endif
                    }break;
                case SET_AUDIOPORT_CONFIG: {
                    SetAudioPortConfigData *data = (SetAudioPortConfigData *)command->mParam.get();
#ifdef MTK_AUDIO_COMMAND_QUEUE_DEBUG
                    ALOGD("AudioCommandThread SET_AUDIOPORT_CONFIG ");
#else 
                    ALOGV("AudioCommandThread() processing set port config");
#endif
                    sp<IAudioFlinger> af = AudioSystem::get_audio_flinger();
                    if (af == 0) {
                        command->mStatus = PERMISSION_DENIED;
                    } else {
                        command->mStatus = af->setAudioPortConfig(&data->mConfig);
                    }
#ifdef MTK_AUDIO_COMMAND_QUEUE_DEBUG
                    ALOGD("-AudioCommandThread %d",command->mCommand);
#endif

                    } break;
#ifdef MTK_AUDIO
                case GET_CUSTOM_AUDIO_VOLUME: {
                    GetCustomAudioVolumeData *data = (GetCustomAudioVolumeData *)command->mParam.get();
#ifdef MTK_AUDIO_COMMAND_QUEUE_DEBUG
                    ALOGD("AudioCommandThread GET_CUSTOM_AUDIO_VOLUME" );
#else 
                    ALOGV("AudioCommandThread() processing GET_CUSTOM_AUDIO_VOLUME");
#endif
                    sp<IAudioFlinger> af = AudioSystem::get_audio_flinger();
                    if (af == 0) {
                        command->mStatus = PERMISSION_DENIED;
                    } else {
                        command->mStatus = af->GetAudioData(GET_AUDIO_POLICY_VOL_FROM_VER1_DATA,sizeof(AUDIO_CUSTOM_VOLUME_STRUCT),&(data->mVolConfig));
                    }
#ifdef MTK_AUDIO_COMMAND_QUEUE_DEBUG
                    ALOGD("-AudioCommandThread %d",command->mCommand);
#endif
                    break;
                }
                case STOP_OUTPUT_SAMPLERATE: {
                    StopOutputDataSamplerate *data = (StopOutputDataSamplerate *)command->mParam.get();
#ifdef MTK_AUDIO_COMMAND_QUEUE_DEBUG
                    ALOGD("AudioCommandThread STOP_OUTPUT_SAMPLERATE %d",data->mIO);
#else 
                    ALOGV("AudioCommandThread() processing stopoutputsamplerate %d",
                            data->mIO);
#endif
                    svc = mService.promote();
                    if (svc == 0) {
                        break;
                    }
                    mLock.unlock();
                    svc->doStopOutputSamplerate(data->mIO, data->mStream, data->mSession, data->mSamplerate);
                    mLock.lock();
#ifdef MTK_AUDIO_COMMAND_QUEUE_DEBUG
                    ALOGD("-AudioCommandThread %d",command->mCommand);
#endif
                    }break;
#endif
                default:
                    ALOGW("AudioCommandThread() unknown command %d", command->mCommand);
                }
                {
                    Mutex::Autolock _l(command->mLock);
                    if (command->mWaitStatus) {
                        command->mWaitStatus = false;
                        command->mCond.signal();
                    }
                }
                waitTime = INT64_MAX;
            } else {
                waitTime = mAudioCommands[0]->mTime - curTime;
                break;
            }
        }
        // release mLock before releasing strong reference on the service as
        // AudioPolicyService destructor calls AudioCommandThread::exit() which acquires mLock.
        mLock.unlock();
        svc.clear();
        mLock.lock();
        if (!exitPending() && mAudioCommands.isEmpty()) {
            // release delayed commands wake lock
            release_wake_lock(mName.string());
            ALOGV("AudioCommandThread() going to sleep %ld",waitTime);
            mWaitWorkCV.waitRelative(mLock, waitTime);
            ALOGV("AudioCommandThread() waking up");
        }
    }
    // release delayed commands wake lock before quitting
    if (!mAudioCommands.isEmpty()) {
        release_wake_lock(mName.string());
    }
    mLock.unlock();
    return false;
}

status_t AudioPolicyService::AudioCommandThread::dump(int fd)
{
    const size_t SIZE = 256;
    char buffer[SIZE];
    String8 result;

    snprintf(buffer, SIZE, "AudioCommandThread %p Dump\n", this);
    result.append(buffer);
    write(fd, result.string(), result.size());

    bool locked = tryLock(mLock);
    if (!locked) {
        String8 result2(kCmdDeadlockedString);
        write(fd, result2.string(), result2.size());
    }

    snprintf(buffer, SIZE, "- Commands:\n");
    result = String8(buffer);
    result.append("   Command Time        Wait pParam\n");
    for (size_t i = 0; i < mAudioCommands.size(); i++) {
        mAudioCommands[i]->dump(buffer, SIZE);
        result.append(buffer);
    }
    result.append("  Last Command\n");
    if (mLastCommand != 0) {
        mLastCommand->dump(buffer, SIZE);
        result.append(buffer);
    } else {
        result.append("     none\n");
    }

    write(fd, result.string(), result.size());

    if (locked) mLock.unlock();

    return NO_ERROR;
}

void AudioPolicyService::AudioCommandThread::startToneCommand(ToneGenerator::tone_type type,
        audio_stream_type_t stream)
{
    sp<AudioCommand> command = new AudioCommand();
    command->mCommand = START_TONE;
    sp<ToneData> data = new ToneData();
    data->mType = type;
    data->mStream = stream;
    command->mParam = data;
    ALOGV("AudioCommandThread() adding tone start type %d, stream %d", type, stream);
    sendCommand(command);
}

void AudioPolicyService::AudioCommandThread::stopToneCommand()
{
    sp<AudioCommand> command = new AudioCommand();
    command->mCommand = STOP_TONE;
    ALOGV("AudioCommandThread() adding tone stop");
    sendCommand(command);
}

status_t AudioPolicyService::AudioCommandThread::volumeCommand(audio_stream_type_t stream,
                                                               float volume,
                                                               audio_io_handle_t output,
                                                               int delayMs)
{
    sp<AudioCommand> command = new AudioCommand();
    command->mCommand = SET_VOLUME;
    sp<VolumeData> data = new VolumeData();
    data->mStream = stream;
    data->mVolume = volume;
    data->mIO = output;
    command->mParam = data;
    command->mWaitStatus = true;
#ifdef MTK_AUDIO_COMMAND_QUEUE_DEBUG
    ALOGD("AudioCommandThread vol add stream %d, vol %f, output %d delay %d",stream, volume, output,delayMs);
#else
    ALOGV("AudioCommandThread() adding set volume stream %d, volume %f, output %d",
            stream, volume, output);
#endif
    return sendCommand(command, delayMs);
}

status_t AudioPolicyService::AudioCommandThread::parametersCommand(audio_io_handle_t ioHandle,
                                                                   const char *keyValuePairs,
                                                                   int delayMs)
{
    sp<AudioCommand> command = new AudioCommand();
    command->mCommand = SET_PARAMETERS;
    sp<ParametersData> data = new ParametersData();
    data->mIO = ioHandle;
    data->mKeyValuePairs = String8(keyValuePairs);
    command->mParam = data;
    command->mWaitStatus = true;
    ALOGV("AudioCommandThread() adding set parameter string %s, io %d ,delay %d",
            keyValuePairs, ioHandle, delayMs);
    return sendCommand(command, delayMs);
}

status_t AudioPolicyService::AudioCommandThread::voiceVolumeCommand(float volume, int delayMs)
{
    sp<AudioCommand> command = new AudioCommand();
    command->mCommand = SET_VOICE_VOLUME;
    sp<VoiceVolumeData> data = new VoiceVolumeData();
    data->mVolume = volume;
    command->mParam = data;
    command->mWaitStatus = true;
    ALOGV("AudioCommandThread() adding set voice volume volume %f", volume);
    return sendCommand(command, delayMs);
}

void AudioPolicyService::AudioCommandThread::stopOutputCommand(audio_io_handle_t output,
                                                               audio_stream_type_t stream,
                                                               int session)
{
    sp<AudioCommand> command = new AudioCommand();
    command->mCommand = STOP_OUTPUT;
    sp<StopOutputData> data = new StopOutputData();
    data->mIO = output;
    data->mStream = stream;
    data->mSession = session;
    command->mParam = data;
    ALOGV("AudioCommandThread() adding stop output %d", output);
    sendCommand(command);
}

void AudioPolicyService::AudioCommandThread::releaseOutputCommand(audio_io_handle_t output)
{
    sp<AudioCommand> command = new AudioCommand();
    command->mCommand = RELEASE_OUTPUT;
    sp<ReleaseOutputData> data = new ReleaseOutputData();
    data->mIO = output;
    command->mParam = data;
    ALOGV("AudioCommandThread() adding release output %d", output);
    sendCommand(command);
}

status_t AudioPolicyService::AudioCommandThread::createAudioPatchCommand(
                                                const struct audio_patch *patch,
                                                audio_patch_handle_t *handle,
                                                int delayMs)
{
    status_t status = NO_ERROR;

    sp<AudioCommand> command = new AudioCommand();
    command->mCommand = CREATE_AUDIO_PATCH;
    CreateAudioPatchData *data = new CreateAudioPatchData();
    data->mPatch = *patch;
    data->mHandle = *handle;
    command->mParam = data;
    command->mWaitStatus = true;
    ALOGV("AudioCommandThread() adding create patch delay %d", delayMs);
    status = sendCommand(command, delayMs);
    if (status == NO_ERROR) {
        *handle = data->mHandle;
    }
    return status;
}

status_t AudioPolicyService::AudioCommandThread::releaseAudioPatchCommand(audio_patch_handle_t handle,
                                                 int delayMs)
{
    sp<AudioCommand> command = new AudioCommand();
    command->mCommand = RELEASE_AUDIO_PATCH;
    ReleaseAudioPatchData *data = new ReleaseAudioPatchData();
    data->mHandle = handle;
    command->mParam = data;
    command->mWaitStatus = true;
    ALOGV("AudioCommandThread() adding release patch delay %d", delayMs);
    return sendCommand(command, delayMs);
}

void AudioPolicyService::AudioCommandThread::updateAudioPortListCommand()
{
    sp<AudioCommand> command = new AudioCommand();
    command->mCommand = UPDATE_AUDIOPORT_LIST;
    ALOGV("AudioCommandThread() adding update audio port list");
    sendCommand(command);
}

void AudioPolicyService::AudioCommandThread::updateAudioPatchListCommand()
{
    sp<AudioCommand>command = new AudioCommand();
    command->mCommand = UPDATE_AUDIOPATCH_LIST;
    ALOGV("AudioCommandThread() adding update audio patch list");
    sendCommand(command);
}

status_t AudioPolicyService::AudioCommandThread::setAudioPortConfigCommand(
                                            const struct audio_port_config *config, int delayMs)
{
    sp<AudioCommand> command = new AudioCommand();
    command->mCommand = SET_AUDIOPORT_CONFIG;
    SetAudioPortConfigData *data = new SetAudioPortConfigData();
    data->mConfig = *config;
    command->mParam = data;
    command->mWaitStatus = true;
    ALOGV("AudioCommandThread() adding set port config delay %d", delayMs);
    return sendCommand(command, delayMs);
}

status_t     AudioPolicyService::AudioCommandThread::getCustomAudioVolumeCommand(AUDIO_CUSTOM_VOLUME_STRUCT* pCustomVol)
{
#ifdef MTK_AUDIO
    status_t status = NO_ERROR;
    sp<AudioCommand> command = new AudioCommand();
    command->mCommand = GET_CUSTOM_AUDIO_VOLUME;
    GetCustomAudioVolumeData *data = new GetCustomAudioVolumeData();
    memcpy(&(data->mVolConfig),pCustomVol,sizeof(AUDIO_CUSTOM_VOLUME_STRUCT));
//    data->mVolConfig = *pCustomVol;
    command->mParam = data;
    command->mWaitStatus = true;    
    ALOGD("AudioCommandThread() adding set getCustomAudioVolume");
    status = sendCommand(command);
    if (status == NO_ERROR) {
        memcpy(pCustomVol,&(data->mVolConfig),sizeof(AUDIO_CUSTOM_VOLUME_STRUCT));
    }
    return status;
#else
    return INVALID_OPERATION;
#endif
}

void AudioPolicyService::AudioCommandThread::stopOutputSamplerateCommand(audio_io_handle_t output,
                                                  audio_stream_type_t stream,
                                                  int session,
                                                  int samplerate)
{
#ifdef MTK_AUDIO

    sp<AudioCommand> command = new AudioCommand();
    command->mCommand = STOP_OUTPUT_SAMPLERATE;
    sp<StopOutputDataSamplerate> data = new StopOutputDataSamplerate();
    data->mIO = output;
    data->mStream = stream;
    data->mSession = session;
    data->mSamplerate = samplerate;
    command->mParam = data;
    ALOGD("AudioCommandThread() adding stopOutputSamplerateCommand %d", output);
    sendCommand(command);
#endif
}


status_t AudioPolicyService::AudioCommandThread::sendCommand(sp<AudioCommand>& command, int delayMs)
{
#ifdef MTK_AUDIO
    Mutex::Autolock _t(mFunLock); //must get funlock;
#endif
    {
        Mutex::Autolock _l(mLock);
        insertCommand_l(command, delayMs);
        mWaitWorkCV.signal();
    }
    Mutex::Autolock _l(command->mLock);
    while (command->mWaitStatus) {
        nsecs_t timeOutNs = kAudioCommandTimeoutNs + milliseconds(delayMs);
        if (command->mCond.waitRelative(command->mLock, timeOutNs) != NO_ERROR) {
            command->mStatus = TIMED_OUT;
            command->mWaitStatus = false;
        }
    }
#ifdef MTK_AUDIO_COMMAND_QUEUE_DEBUG
    ALOGD("-%s %d",__FUNCTION__,command->mStatus);
#endif
    return command->mStatus;
}

// insertCommand_l() must be called with mLock held
void AudioPolicyService::AudioCommandThread::insertCommand_l(sp<AudioCommand>& command, int delayMs)
{
    ssize_t i;  // not size_t because i will count down to -1
    Vector < sp<AudioCommand> > removedCommands;

#ifdef MTK_AUDIO
    //<----weiguo  alps00053099  adjust matv volume  when record sound stoping, matv sound will out through speaker.
    //   delay  -1 to put volume setting command at the end of command queue in audiopolicy service
    // attentation : this is only for matv, if for other type ,you should test it.
    if(delayMs == -1)
    {
        int lastcmdid = mAudioCommands.size()-1;
        if( lastcmdid >=0 )
        {
            sp<AudioCommand> lastcommand = mAudioCommands[lastcmdid];
            command->mTime = lastcommand->mTime + 1;  // put it at the end of command queue
        }
        else
        {
            command->mTime = systemTime(); //no command in queue.
        }
        MTK_ALOG_V("command wait time=%lld ms",ns2ms(command->mTime - systemTime()));
    }
    else
    {
        command->mTime = systemTime() + milliseconds(delayMs);
    }
#else    
    command->mTime = systemTime() + milliseconds(delayMs);
#endif    

    // acquire wake lock to make sure delayed commands are processed
    if (mAudioCommands.isEmpty()) {
        acquire_wake_lock(PARTIAL_WAKE_LOCK, mName.string());
    }

    // check same pending commands with later time stamps and eliminate them
    for (i = mAudioCommands.size()-1; i >= 0; i--) {
        sp<AudioCommand> command2 = mAudioCommands[i];
        // commands are sorted by increasing time stamp: no need to scan the rest of mAudioCommands
        if (command2->mTime <= command->mTime) break;

        // create audio patch or release audio patch commands are equivalent
        // with regard to filtering
        if ((command->mCommand == CREATE_AUDIO_PATCH) ||
                (command->mCommand == RELEASE_AUDIO_PATCH)) {
            if ((command2->mCommand != CREATE_AUDIO_PATCH) &&
                    (command2->mCommand != RELEASE_AUDIO_PATCH)) {
                continue;
            }
        } else if (command2->mCommand != command->mCommand) continue;

        switch (command->mCommand) {
        case SET_PARAMETERS: {
            ParametersData *data = (ParametersData *)command->mParam.get();
            ParametersData *data2 = (ParametersData *)command2->mParam.get();
            if (data->mIO != data2->mIO) break;
            ALOGV("Comparing parameter command %s to new command %s",
                    data2->mKeyValuePairs.string(), data->mKeyValuePairs.string());
            AudioParameter param = AudioParameter(data->mKeyValuePairs);
            AudioParameter param2 = AudioParameter(data2->mKeyValuePairs);
            for (size_t j = 0; j < param.size(); j++) {
                String8 key;
                String8 value;
                param.getAt(j, key, value);
#ifdef MTK_AUDIO
                if(!ParaMetersNeedFilter(key)) continue; // add policy for certain string
#endif                
                for (size_t k = 0; k < param2.size(); k++) {
                    String8 key2;
                    String8 value2;
                    param2.getAt(k, key2, value2);
                    if (key2 == key) {
                        param2.remove(key2);
                        ALOGV("Filtering out parameter %s", key2.string());
                        break;
                    }
                }
            }
            // if all keys have been filtered out, remove the command.
            // otherwise, update the key value pairs
            if (param2.size() == 0) {
                removedCommands.add(command2);
            } else {
                data2->mKeyValuePairs = param2.toString();
            }
            command->mTime = command2->mTime;
            // force delayMs to non 0 so that code below does not request to wait for
            // command status as the command is now delayed
            delayMs = 1;
        } break;

        case SET_VOLUME: {
            VolumeData *data = (VolumeData *)command->mParam.get();
            VolumeData *data2 = (VolumeData *)command2->mParam.get();
            if (data->mIO != data2->mIO) break;
            if (data->mStream != data2->mStream) break;
            ALOGV("Filtering out volume command on output %d for stream %d",
                    data->mIO, data->mStream);
            removedCommands.add(command2);
            command->mTime = command2->mTime;
            // force delayMs to non 0 so that code below does not request to wait for
            // command status as the command is now delayed
            delayMs = 1;
        } break;

        case CREATE_AUDIO_PATCH:
        case RELEASE_AUDIO_PATCH: {
            audio_patch_handle_t handle;
            struct audio_patch patch;
            if (command->mCommand == CREATE_AUDIO_PATCH) {
                handle = ((CreateAudioPatchData *)command->mParam.get())->mHandle;
                patch = ((CreateAudioPatchData *)command->mParam.get())->mPatch;
            } else {
                handle = ((ReleaseAudioPatchData *)command->mParam.get())->mHandle;
            }
            audio_patch_handle_t handle2;
            struct audio_patch patch2;
            if (command2->mCommand == CREATE_AUDIO_PATCH) {
                handle2 = ((CreateAudioPatchData *)command2->mParam.get())->mHandle;
                patch2 = ((CreateAudioPatchData *)command2->mParam.get())->mPatch;
            } else {
                handle2 = ((ReleaseAudioPatchData *)command2->mParam.get())->mHandle;
            }
            if (handle != handle2) break;
            /* Filter CREATE_AUDIO_PATCH commands only when they are issued for
               same output. */
            if( (command->mCommand == CREATE_AUDIO_PATCH) &&
                (command2->mCommand == CREATE_AUDIO_PATCH) ) {
                bool isOutputDiff = false;
                if (patch.num_sources == patch2.num_sources) {
                    for (unsigned count = 0; count < patch.num_sources; count++) {
                        if (patch.sources[count].id != patch2.sources[count].id) {
                            isOutputDiff = true;
                            break;
                        }
                    }
                    if (isOutputDiff)
                       break;
                }
            }
            ALOGV("Filtering out %s audio patch command for handle %d",
                  (command->mCommand == CREATE_AUDIO_PATCH) ? "create" : "release", handle);
            removedCommands.add(command2);
            command->mTime = command2->mTime;
            // force delayMs to non 0 so that code below does not request to wait for
            // command status as the command is now delayed
            delayMs = 1;
        } break;

        case START_TONE:
        case STOP_TONE:
        default:
            break;
        }
    }

    // remove filtered commands
    for (size_t j = 0; j < removedCommands.size(); j++) {
        // removed commands always have time stamps greater than current command
        for (size_t k = i + 1; k < mAudioCommands.size(); k++) {
            if (mAudioCommands[k].get() == removedCommands[j].get()) {
                ALOGD("suppressing command: %d", mAudioCommands[k]->mCommand);
                mAudioCommands.removeAt(k);
                break;
            }
        }
    }
    removedCommands.clear();
    // Disable wait for status if delay is not 0
    if (delayMs != 0) {
        command->mWaitStatus = false;
    }

    // insert command at the right place according to its time stamp
    ALOGV("inserting command: %d at index %zd, num commands %zu",
            command->mCommand, i+1, mAudioCommands.size());
    mAudioCommands.insertAt(command, i + 1);
}

void AudioPolicyService::AudioCommandThread::exit()
{
    ALOGV("AudioCommandThread::exit");
    {
        AutoMutex _l(mLock);
        requestExit();
        mWaitWorkCV.signal();
    }
    requestExitAndWait();
}

void AudioPolicyService::AudioCommandThread::AudioCommand::dump(char* buffer, size_t size)
{
    snprintf(buffer, size, "   %02d      %06d.%03d  %01u    %p\n",
            mCommand,
            (int)ns2s(mTime),
            (int)ns2ms(mTime)%1000,
            mWaitStatus,
            mParam.get());
}

/******* helpers for the service_ops callbacks defined below *********/
void AudioPolicyService::setParameters(audio_io_handle_t ioHandle,
                                       const char *keyValuePairs,
                                       int delayMs)
{
    mAudioCommandThread->parametersCommand(ioHandle, keyValuePairs,
                                           delayMs);
}

int AudioPolicyService::setStreamVolume(audio_stream_type_t stream,
                                        float volume,
                                        audio_io_handle_t output,
                                        int delayMs)
{
    return (int)mAudioCommandThread->volumeCommand(stream, volume,
                                                   output, delayMs);
}

int AudioPolicyService::startTone(audio_policy_tone_t tone,
                                  audio_stream_type_t stream)
{
    if (tone != AUDIO_POLICY_TONE_IN_CALL_NOTIFICATION) {
        ALOGE("startTone: illegal tone requested (%d)", tone);
    }
    if (stream != AUDIO_STREAM_VOICE_CALL) {
        ALOGE("startTone: illegal stream (%d) requested for tone %d", stream,
            tone);
    }
    mTonePlaybackThread->startToneCommand(ToneGenerator::TONE_SUP_CALL_WAITING,
                                          AUDIO_STREAM_VOICE_CALL);
    return 0;
}

int AudioPolicyService::stopTone()
{
    mTonePlaybackThread->stopToneCommand();
    return 0;
}

int AudioPolicyService::setVoiceVolume(float volume, int delayMs)
{
    return (int)mAudioCommandThread->voiceVolumeCommand(volume, delayMs);
}

extern "C" {
audio_module_handle_t aps_load_hw_module(void *service __unused,
                                             const char *name);
audio_io_handle_t aps_open_output(void *service __unused,
                                         audio_devices_t *pDevices,
                                         uint32_t *pSamplingRate,
                                         audio_format_t *pFormat,
                                         audio_channel_mask_t *pChannelMask,
                                         uint32_t *pLatencyMs,
                                         audio_output_flags_t flags);

audio_io_handle_t aps_open_output_on_module(void *service __unused,
                                                   audio_module_handle_t module,
                                                   audio_devices_t *pDevices,
                                                   uint32_t *pSamplingRate,
                                                   audio_format_t *pFormat,
                                                   audio_channel_mask_t *pChannelMask,
                                                   uint32_t *pLatencyMs,
                                                   audio_output_flags_t flags,
                                                   const audio_offload_info_t *offloadInfo);
audio_io_handle_t aps_open_dup_output(void *service __unused,
                                                 audio_io_handle_t output1,
                                                 audio_io_handle_t output2);
int aps_close_output(void *service __unused, audio_io_handle_t output);
int aps_suspend_output(void *service __unused, audio_io_handle_t output);
int aps_restore_output(void *service __unused, audio_io_handle_t output);
audio_io_handle_t aps_open_input(void *service __unused,
                                        audio_devices_t *pDevices,
                                        uint32_t *pSamplingRate,
                                        audio_format_t *pFormat,
                                        audio_channel_mask_t *pChannelMask,
                                        audio_in_acoustics_t acoustics __unused);
audio_io_handle_t aps_open_input_on_module(void *service __unused,
                                                  audio_module_handle_t module,
                                                  audio_devices_t *pDevices,
                                                  uint32_t *pSamplingRate,
                                                  audio_format_t *pFormat,
                                                  audio_channel_mask_t *pChannelMask);
int aps_close_input(void *service __unused, audio_io_handle_t input);
int aps_invalidate_stream(void *service __unused, audio_stream_type_t stream);
int aps_move_effects(void *service __unused, int session,
                                audio_io_handle_t src_output,
                                audio_io_handle_t dst_output);
char * aps_get_parameters(void *service __unused, audio_io_handle_t io_handle,
                                     const char *keys);
void aps_set_parameters(void *service, audio_io_handle_t io_handle,
                                   const char *kv_pairs, int delay_ms);
int aps_set_stream_volume(void *service, audio_stream_type_t stream,
                                     float volume, audio_io_handle_t output,
                                     int delay_ms);
int aps_start_tone(void *service, audio_policy_tone_t tone,
                              audio_stream_type_t stream);
int aps_stop_tone(void *service);
int aps_set_voice_volume(void *service, float volume, int delay_ms);
};

namespace {
    struct audio_policy_service_ops aps_ops = {
        .open_output           = aps_open_output,
        .open_duplicate_output = aps_open_dup_output,
        .close_output          = aps_close_output,
        .suspend_output        = aps_suspend_output,
        .restore_output        = aps_restore_output,
        .open_input            = aps_open_input,
        .close_input           = aps_close_input,
        .set_stream_volume     = aps_set_stream_volume,
        .invalidate_stream     = aps_invalidate_stream,
        .set_parameters        = aps_set_parameters,
        .get_parameters        = aps_get_parameters,
        .start_tone            = aps_start_tone,
        .stop_tone             = aps_stop_tone,
        .set_voice_volume      = aps_set_voice_volume,
        .move_effects          = aps_move_effects,
        .load_hw_module        = aps_load_hw_module,
        .open_output_on_module = aps_open_output_on_module,
        .open_input_on_module  = aps_open_input_on_module,
    };
}; // namespace <unnamed>

}; // namespace android
