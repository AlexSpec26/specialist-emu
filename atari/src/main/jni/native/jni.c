/* GPL-2.0-or-later */
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include "engine.h"
#define JNI(name) Java_com_alexspec_atari_NativeAtari_##name
JNIEXPORT jboolean JNICALL JNI(open)(JNIEnv *env,jclass cls,jstring dir,jstring path,jboolean basic) {
    (void)cls;const char *d=(*env)->GetStringUTFChars(env,dir,0),*p=(*env)->GetStringUTFChars(env,path,0);
    int ok=engine_open(d,p,basic);(*env)->ReleaseStringUTFChars(env,dir,d);(*env)->ReleaseStringUTFChars(env,path,p);return ok;
}
JNIEXPORT void JNICALL JNI(close)(JNIEnv *env,jclass cls){(void)env;(void)cls;engine_close();}
JNIEXPORT jdouble JNICALL JNI(fps)(JNIEnv *env,jclass cls){(void)env;(void)cls;return engine_fps();}
JNIEXPORT jint JNICALL JNI(frame)(JNIEnv *env,jclass cls,jintArray pixels,jshortArray audio,jintArray geometry,jint mask,jbyteArray held) {
    (void)cls;if((*env)->GetArrayLength(env,held)!=512 || (*env)->GetArrayLength(env,pixels)<120000 || (*env)->GetArrayLength(env,geometry)<2)return -1;
    uint8_t keys[512];(*env)->GetByteArrayRegion(env,held,0,512,(jbyte*)keys);
    jint *p=(*env)->GetIntArrayElements(env,pixels,0);jshort *a=(*env)->GetShortArrayElements(env,audio,0);
    if(!p || !a){if(p)(*env)->ReleaseIntArrayElements(env,pixels,p,JNI_ABORT);if(a)(*env)->ReleaseShortArrayElements(env,audio,a,JNI_ABORT);return -1;}
    int n=engine_frame((uint32_t*)p,120000,a,(*env)->GetArrayLength(env,audio),mask,keys);
    (*env)->ReleaseIntArrayElements(env,pixels,p,0);(*env)->ReleaseShortArrayElements(env,audio,a,0);
    jint dims[2]={engine_width(),engine_height()};(*env)->SetIntArrayRegion(env,geometry,0,2,dims);return n;
}
JNIEXPORT void JNICALL JNI(reset)(JNIEnv *env,jclass cls){(void)env;(void)cls;engine_reset();}
JNIEXPORT jbyteArray JNICALL JNI(save)(JNIEnv *env,jclass cls) {
    (void)cls;size_t size=engine_state_size();if(!size || size>8*1024*1024)return NULL;
    void *data=malloc(size);if(!data)return NULL;
    jbyteArray result=NULL;if(engine_save(data,size)){result=(*env)->NewByteArray(env,size);if(result)(*env)->SetByteArrayRegion(env,result,0,size,data);}free(data);return result;
}
JNIEXPORT jboolean JNICALL JNI(load)(JNIEnv *env,jclass cls,jbyteArray state) {
    (void)cls;int size=(*env)->GetArrayLength(env,state);if(size<16 || size>8*1024*1024)return 0;
    jbyte *data=(*env)->GetByteArrayElements(env,state,0);int ok=engine_load(data,size);(*env)->ReleaseByteArrayElements(env,state,data,JNI_ABORT);return ok;
}
JNIEXPORT jbyteArray JNICALL JNI(memory)(JNIEnv *env,jclass cls) {
    (void)cls;uint8_t *data=engine_memory();if(!data)return NULL;jbyteArray result=(*env)->NewByteArray(env,65536);if(result)(*env)->SetByteArrayRegion(env,result,0,65536,(jbyte*)data);return result;
}
