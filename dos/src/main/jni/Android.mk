# The DOS engine: DOSBox Pure core (C++, vendor/dosbox-pure) + this app's frontend (native/).
LOCAL_PATH := $(call my-dir)
CORE_DIR := $(LOCAL_PATH)/vendor/dosbox-pure
CORE_SRC := $(wildcard $(CORE_DIR)/*.cpp $(CORE_DIR)/src/*.cpp $(CORE_DIR)/src/*/*.cpp $(CORE_DIR)/src/*/*/*.cpp)

include $(CLEAR_VARS)
LOCAL_MODULE := dos_engine
LOCAL_SRC_FILES := $(CORE_SRC:$(LOCAL_PATH)/%=%) native/engine.c native/jni.c
LOCAL_C_INCLUDES := $(CORE_DIR)/include $(CORE_DIR)/libretro-common/include $(LOCAL_PATH)/native
LOCAL_CFLAGS := -O2 -DNDEBUG -D__LIBRETRO__ -D_FILE_OFFSET_BITS=64 -fomit-frame-pointer \
                -Wno-address-of-packed-member -Wno-format -Wno-switch -w
LOCAL_CPPFLAGS := -std=gnu++11
LOCAL_CONLYFLAGS := -std=gnu99
LOCAL_CPP_FEATURES := rtti exceptions
LOCAL_LDFLAGS := -Wl,-z,max-page-size=16384
LOCAL_LDLIBS := -llog
LOCAL_ARM_MODE := arm
include $(BUILD_SHARED_LIBRARY)
