#define LOG_TAG "DisplayPowerController-JNI"

#include "JNIHelp.h"
#include "jni.h"

#include <ScopedUtfChars.h>

#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/Log.h>
#include <utils/Log.h>
#ifdef MTK_AAL_SUPPORT
#include <AALClient.h>
#endif

namespace android {


static jboolean nativeRuntimeTuningIsSupported(JNIEnv *env, jclass clazz)
{
#if defined(MTK_AAL_SUPPORT) && defined(MTK_AAL_RUNTIME_TUNING_SUPPORT)
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}


static jint nativeSetTuningInt(JNIEnv *env, jclass clazz, jint field, jint jvalue)
{
#if defined(MTK_AAL_SUPPORT) && defined(MTK_AAL_RUNTIME_TUNING_SUPPORT)
    IAALService::AdaptFieldId fieldId = static_cast<IAALService::AdaptFieldId>(field);
    int32_t value = jvalue;
    uint32_t serial;
    if (android::AALClient::getInstance().setAdaptField(
            fieldId, &value, sizeof(value), &serial) != NO_ERROR)
    {
        ALOGE("nativeSetTuningInt(%d): failed", field);
        return -1;
    }

    return serial;
#else
    return -1;
#endif
}


static jint nativeSetTuningIntArray(JNIEnv *env, jclass clazz, jint field, jintArray jcurve)
{
#if defined(MTK_AAL_SUPPORT) && defined(MTK_AAL_RUNTIME_TUNING_SUPPORT)
    IAALService::AdaptFieldId fieldId = static_cast<IAALService::AdaptFieldId>(field);
    jsize len = env->GetArrayLength(jcurve);

    int *buffer = new int[len];

    jint *curve = env->GetIntArrayElements(jcurve, 0);
    for (jsize i = 0; i < len; i++) {
        buffer[i] = curve[i];
    }
    env->ReleaseIntArrayElements(jcurve, curve, 0);
    
    uint32_t serial;
    if (android::AALClient::getInstance().setAdaptField(
            fieldId, buffer, sizeof(buffer[0]) * len, &serial) != NO_ERROR)
    {
        ALOGE("nativeSetTuningIntArray(%d): failed", field);
        return -1;
    }

    delete [] buffer;

    return serial;
#else
    return -1;
#endif
}


static jint nativeGetTuningInt(JNIEnv *env, jclass clazz, jint field)
{
#if defined(MTK_AAL_SUPPORT) && defined(MTK_AAL_RUNTIME_TUNING_SUPPORT)
    IAALService::AdaptFieldId fieldId = static_cast<IAALService::AdaptFieldId>(field);
    int32_t value;
    uint32_t serial;
    if (android::AALClient::getInstance().getAdaptField(
            fieldId, &value, sizeof(value), &serial) != NO_ERROR)
    {
        ALOGE("nativeGetTuningInt(%d): failed", field);
        return -1;
    }

    return value;
#else
    return -1;
#endif
}


static jint nativeGetTuningSerial(JNIEnv *env, jclass clazz, jint field)
{
#if defined(MTK_AAL_SUPPORT) && defined(MTK_AAL_RUNTIME_TUNING_SUPPORT)
    IAALService::AdaptFieldId fieldId = static_cast<IAALService::AdaptFieldId>(field);
    uint32_t value;
    if (android::AALClient::getInstance().getAdaptSerial(fieldId, &value) != NO_ERROR) {
        ALOGE("nativeGetTuningSerial(%d): transaction failed", field);
        return -1;
    }

    return value;
#else
    return -1;
#endif

}


static void nativeGetTuningIntArray(JNIEnv *env, jclass clazz, jint field, jintArray jcurve)
{
#if defined(MTK_AAL_SUPPORT) && defined(MTK_AAL_RUNTIME_TUNING_SUPPORT)
    IAALService::AdaptFieldId fieldId = static_cast<IAALService::AdaptFieldId>(field);
    jsize len = env->GetArrayLength(jcurve);

    int *buffer = new int[len];
    
    uint32_t serial;
    if (android::AALClient::getInstance().getAdaptField(
            fieldId, buffer, sizeof(buffer[0]) * len, &serial) != NO_ERROR)
    {
        ALOGE("nativeGetTuningIntArray(%d): failed", field);
        return;
    }

    jint *curve = env->GetIntArrayElements(jcurve, 0);
    for (jsize i = 0; i < len; i++) {
        curve[i] = buffer[i];
    }
    env->ReleaseIntArrayElements(jcurve, curve, 0);

    delete [] buffer;
#endif
}


// ----------------------------------------------------------------------------


static JNINativeMethod gDisplayPowerControllerMethods[] = {
    /* name, signature, funcPtr */
    { "nativeRuntimeTuningIsSupported", "()Z",
            (void*) nativeRuntimeTuningIsSupported },
    { "nativeSetTuningInt", "(II)I",
            (void*) nativeSetTuningInt },
    { "nativeSetTuningIntArray", "(I[I)I",
            (void*) nativeSetTuningIntArray },
    { "nativeGetTuningInt", "(I)I",
            (void*) nativeGetTuningInt },
    { "nativeGetTuningSerial", "(I)I",
            (void*) nativeGetTuningSerial },
    { "nativeGetTuningIntArray", "(I[I)V",
            (void*) nativeGetTuningIntArray },

};


int register_android_server_display_DisplayPowerController(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, "com/android/server/display/DisplayPowerController",
            gDisplayPowerControllerMethods, NELEM(gDisplayPowerControllerMethods));
    LOG_FATAL_IF(res < 0, "Unable to register native methods.");

    return 0;
}

} /* namespace android */
