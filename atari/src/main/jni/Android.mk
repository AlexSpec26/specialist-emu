LOCAL_PATH := $(call my-dir)
CORE_DIR := $(LOCAL_PATH)/vendor/atari800
SYSTEM_ZLIB := 1
# Android provides zlib without pkg-config.
include $(CORE_DIR)/Makefile.common
include $(CLEAR_VARS)
LOCAL_MODULE := atari_engine
LOCAL_SRC_FILES := $(SOURCES_C) native/engine.c native/jni.c
LOCAL_CFLAGS := -O2 -std=gnu99 -D__LIBRETRO__ -DFRONTEND_SUPPORTS_RGB565 $(INCFLAGS)
LOCAL_C_INCLUDES := $(LOCAL_PATH)/native
LOCAL_LDLIBS := -lm -lz -llog
include $(BUILD_SHARED_LIBRARY)
