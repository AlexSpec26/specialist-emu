/*
 * JNI bridge: com.alexspec.atari.NativeAtari → engine.c.
 * Called only from the emulation thread under the NativeAtari class monitor.
 * GPL-2.0-or-later, see atari/COPYING.
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include "engine.h"

#define JNI(name) Java_com_alexspec_atari_NativeAtari_##name
#define MAX_STATE (8 * 1024 * 1024)

JNIEXPORT jboolean JNICALL JNI(open)(JNIEnv *env, jclass cls, jstring dir, jstring path, jboolean basic) {
    (void)cls;
    const char *d = (*env)->GetStringUTFChars(env, dir, 0);
    const char *p = (*env)->GetStringUTFChars(env, path, 0);
    int ok = d && p && engine_open(d, p, basic);
    if (d) (*env)->ReleaseStringUTFChars(env, dir, d);
    if (p) (*env)->ReleaseStringUTFChars(env, path, p);
    return ok;
}

JNIEXPORT void JNICALL JNI(close)(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    engine_close();
}

JNIEXPORT jdouble JNICALL JNI(fps)(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return engine_fps();
}

JNIEXPORT jint JNICALL JNI(frame)(JNIEnv *env, jclass cls, jintArray pixels, jshortArray audio,
                                  jintArray geometry, jint mask, jbyteArray held) {
    (void)cls;
    if ((*env)->GetArrayLength(env, held) != ENGINE_KEYS
        || (*env)->GetArrayLength(env, pixels) < ENGINE_MAX_PIXELS
        || (*env)->GetArrayLength(env, geometry) < 2)
        return -1;

    uint8_t keys[ENGINE_KEYS];
    (*env)->GetByteArrayRegion(env, held, 0, ENGINE_KEYS, (jbyte *)keys);
    jint *p = (*env)->GetIntArrayElements(env, pixels, 0);
    jshort *a = (*env)->GetShortArrayElements(env, audio, 0);
    if (!p || !a) {
        if (p) (*env)->ReleaseIntArrayElements(env, pixels, p, JNI_ABORT);
        if (a) (*env)->ReleaseShortArrayElements(env, audio, a, JNI_ABORT);
        return -1;
    }
    int n = engine_frame((uint32_t *)p, ENGINE_MAX_PIXELS, a, (*env)->GetArrayLength(env, audio), mask, keys);
    (*env)->ReleaseIntArrayElements(env, pixels, p, 0);
    (*env)->ReleaseShortArrayElements(env, audio, a, 0);

    jint dims[2] = {engine_width(), engine_height()};
    (*env)->SetIntArrayRegion(env, geometry, 0, 2, dims);
    return n;
}

JNIEXPORT void JNICALL JNI(reset)(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    engine_reset();
}

JNIEXPORT jbyteArray JNICALL JNI(save)(JNIEnv *env, jclass cls) {
    (void)cls;
    size_t size = engine_state_size();
    if (!size || size > MAX_STATE) return NULL;
    void *data = malloc(size);
    if (!data) return NULL;
    jbyteArray result = NULL;
    if (engine_save(data, size)) {
        result = (*env)->NewByteArray(env, (jsize)size);
        if (result) (*env)->SetByteArrayRegion(env, result, 0, (jsize)size, data);
    }
    free(data);
    return result;
}

JNIEXPORT jboolean JNICALL JNI(load)(JNIEnv *env, jclass cls, jbyteArray state) {
    (void)cls;
    jsize size = (*env)->GetArrayLength(env, state);
    if (size < 16 || size > MAX_STATE) return 0;
    jbyte *data = (*env)->GetByteArrayElements(env, state, 0);
    if (!data) return 0;
    int ok = engine_load(data, (size_t)size);
    (*env)->ReleaseByteArrayElements(env, state, data, JNI_ABORT);
    return ok;
}

JNIEXPORT jbyteArray JNICALL JNI(memory)(JNIEnv *env, jclass cls) {
    (void)cls;
    uint8_t *data = engine_memory();
    if (!data) return NULL;
    jbyteArray result = (*env)->NewByteArray(env, 65536);
    if (result) (*env)->SetByteArrayRegion(env, result, 0, 65536, (jbyte *)data);
    return result;
}
