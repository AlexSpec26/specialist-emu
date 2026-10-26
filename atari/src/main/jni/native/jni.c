/*
 * JNI bridge: com.alexspec.atari.core.NativeAtari → engine.h.
 * Called only from the emulation thread (see EmuThread). GPL-2.0-or-later, see atari/COPYING.
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include "engine.h"

#define JNI(name) Java_com_alexspec_atari_core_NativeAtari_##name
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

JNIEXPORT void JNICALL JNI(close)(JNIEnv *env, jclass cls) { (void)env; (void)cls; engine_close(); }
JNIEXPORT void JNICALL JNI(reset)(JNIEnv *env, jclass cls) { (void)env; (void)cls; engine_reset(); }

JNIEXPORT void JNICALL JNI(setInput)(JNIEnv *env, jclass cls, jint akey, jboolean shift, jint consol, jint buttons) {
    (void)env; (void)cls;
    EngineInput in = {akey, shift, consol, buttons};
    engine_set_input(&in);
}

JNIEXPORT jint JNICALL JNI(run)(JNIEnv *env, jclass cls, jlong cycles, jboolean toFrameEnd, jshortArray audio) {
    (void)cls;
    jint cap = audio ? (*env)->GetArrayLength(env, audio) : 0;
    jshort *a = audio ? (*env)->GetShortArrayElements(env, audio, 0) : NULL;
    int n = engine_run(cycles, toFrameEnd, a, a ? cap : 0);
    if (a) (*env)->ReleaseShortArrayElements(env, audio, a, 0);
    return n;
}

JNIEXPORT void JNICALL JNI(step)(JNIEnv *env, jclass cls, jlong n) { (void)env; (void)cls; engine_step(n); }
JNIEXPORT void JNICALL JNI(forward)(JNIEnv *env, jclass cls, jlong t) { (void)env; (void)cls; engine_forward(t); }
JNIEXPORT jboolean JNICALL JNI(backInstructions)(JNIEnv *env, jclass cls, jlong n) { (void)env; (void)cls; return engine_back_instructions(n); }
JNIEXPORT jboolean JNICALL JNI(backTime)(JNIEnv *env, jclass cls, jlong t) { (void)env; (void)cls; return engine_back_time(t); }
JNIEXPORT void JNICALL JNI(forgetFuture)(JNIEnv *env, jclass cls) { (void)env; (void)cls; engine_forget_future(); }
JNIEXPORT jdouble JNICALL JNI(historySeconds)(JNIEnv *env, jclass cls) { (void)env; (void)cls; return engine_history_seconds(); }
JNIEXPORT jdouble JNICALL JNI(fps)(JNIEnv *env, jclass cls) { (void)env; (void)cls; return engine_fps(); }

/* time, instructions, frames, last start, last pc, mid-frame, beam line, beam cycle, then A X Y S P PC */
JNIEXPORT void JNICALL JNI(status)(JNIEnv *env, jclass cls, jlongArray out) {
    (void)cls;
    jlong v[14];
    int regs[6], beam[2];
    engine_regs(regs);
    v[5] = engine_beam(beam);
    v[0] = engine_time();
    v[1] = engine_instructions();
    v[2] = engine_frames();
    v[3] = engine_last_start();
    v[4] = engine_last_pc();
    v[6] = beam[0];
    v[7] = beam[1];
    for (int i = 0; i < 6; i++) v[8 + i] = regs[i];
    (*env)->SetLongArrayRegion(env, out, 0, 14, v);
}

JNIEXPORT void JNICALL JNI(picture)(JNIEnv *env, jclass cls, jintArray argb) {
    (void)cls;
    if ((*env)->GetArrayLength(env, argb) < ENGINE_WIDTH * ENGINE_HEIGHT) return;
    jint *p = (*env)->GetIntArrayElements(env, argb, 0);
    if (!p) return;
    engine_picture((uint32_t *)p);
    (*env)->ReleaseIntArrayElements(env, argb, p, 0);
}

JNIEXPORT jboolean JNICALL JNI(memory)(JNIEnv *env, jclass cls, jbyteArray out) {
    (void)cls;
    uint8_t *data = engine_memory();
    if (!data || (*env)->GetArrayLength(env, out) < 65536) return 0;
    (*env)->SetByteArrayRegion(env, out, 0, 65536, (jbyte *)data);
    return 1;
}

JNIEXPORT void JNICALL JNI(trace)(JNIEnv *env, jclass cls, jboolean on) { (void)env; (void)cls; engine_trace(on); }
JNIEXPORT void JNICALL JNI(traceClear)(JNIEnv *env, jclass cls) { (void)env; (void)cls; engine_trace_clear(); }

JNIEXPORT void JNICALL JNI(traceCopy)(JNIEnv *env, jclass cls, jlongArray reads, jlongArray writes, jlongArray execs) {
    (void)cls;
    const long long *r, *w, *x;
    engine_trace_arrays(&r, &w, &x);
    (*env)->SetLongArrayRegion(env, reads, 0, 65536, (const jlong *)r);
    (*env)->SetLongArrayRegion(env, writes, 0, 65536, (const jlong *)w);
    (*env)->SetLongArrayRegion(env, execs, 0, 65536, (const jlong *)x);
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
