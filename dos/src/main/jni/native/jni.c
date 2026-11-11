/*
 * JNI bridge: com.alexspec.dos.NativeDos → engine.h. Called only from the emulation thread
 * (EmuThread). GPL-2.0-or-later, see dos/COPYING.
 */
#include <jni.h>
#include <stdlib.h>

#include "engine.h"

#define JNI(name) Java_com_alexspec_dos_NativeDos_##name
#define MAX_STATE (256 * 1024 * 1024)

static const char *str(JNIEnv *env, jstring s) { return s ? (*env)->GetStringUTFChars(env, s, 0) : NULL; }
static void unstr(JNIEnv *env, jstring s, const char *c) { if (s && c) (*env)->ReleaseStringUTFChars(env, s, c); }

JNIEXPORT jboolean JNICALL JNI(open)(JNIEnv *env, jclass cls, jstring sys, jstring save, jstring content) {
    (void)cls;
    const char *a = str(env, sys), *b = str(env, save), *c = str(env, content);
    int ok = a && b && engine_open(a, b, c ? c : "");
    unstr(env, sys, a);
    unstr(env, save, b);
    unstr(env, content, c);
    return ok;
}

JNIEXPORT jboolean JNICALL JNI(isOpen)(JNIEnv *env, jclass cls) { (void)env; (void)cls; return engine_is_open(); }

JNIEXPORT void JNICALL JNI(option)(JNIEnv *env, jclass cls, jstring key, jstring value) {
    (void)cls;
    const char *k = str(env, key), *v = str(env, value);
    if (k && v) engine_set_option(k, v);
    unstr(env, key, k);
    unstr(env, value, v);
}

JNIEXPORT jint JNICALL JNI(frame)(JNIEnv *env, jclass cls, jshortArray audio) {
    (void)cls;
    jint cap = audio ? (*env)->GetArrayLength(env, audio) : 0;
    jshort *a = audio ? (*env)->GetShortArrayElements(env, audio, 0) : NULL;
    int n = engine_frame(a, a ? cap : 0);
    if (a) (*env)->ReleaseShortArrayElements(env, audio, a, 0);
    return n;
}

JNIEXPORT jdouble JNICALL JNI(fps)(JNIEnv *env, jclass cls) { (void)env; (void)cls; return engine_fps(); }
JNIEXPORT jint JNICALL JNI(sampleRate)(JNIEnv *env, jclass cls) { (void)env; (void)cls; return engine_sample_rate(); }
JNIEXPORT jint JNICALL JNI(width)(JNIEnv *env, jclass cls) { (void)env; (void)cls; return engine_width(); }
JNIEXPORT jint JNICALL JNI(height)(JNIEnv *env, jclass cls) { (void)env; (void)cls; return engine_height(); }
JNIEXPORT jdouble JNICALL JNI(aspect)(JNIEnv *env, jclass cls) { (void)env; (void)cls; return engine_aspect(); }

JNIEXPORT jint JNICALL JNI(picture)(JNIEnv *env, jclass cls, jintArray argb) {
    (void)cls;
    jint cap = (*env)->GetArrayLength(env, argb);
    jint *p = (*env)->GetIntArrayElements(env, argb, 0);
    if (!p) return 0;
    int n = engine_picture((uint32_t *)p, cap);
    (*env)->ReleaseIntArrayElements(env, argb, p, n ? 0 : JNI_ABORT);
    return n;
}

JNIEXPORT void JNICALL JNI(key)(JNIEnv *env, jclass cls, jint code, jboolean down) { (void)env; (void)cls; engine_key(code, down); }
JNIEXPORT void JNICALL JNI(releaseKeys)(JNIEnv *env, jclass cls) { (void)env; (void)cls; engine_release_keys(); }

JNIEXPORT void JNICALL JNI(pointer)(JNIEnv *env, jclass cls, jint x, jint y, jboolean pressed, jint count) {
    (void)env; (void)cls;
    engine_pointer(x, y, pressed, count);
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

JNIEXPORT jdouble JNICALL JNI(rewind)(JNIEnv *env, jclass cls, jdouble seconds) { (void)env; (void)cls; return engine_rewind(seconds); }
JNIEXPORT jdouble JNICALL JNI(history)(JNIEnv *env, jclass cls) { (void)env; (void)cls; return engine_history_seconds(); }
