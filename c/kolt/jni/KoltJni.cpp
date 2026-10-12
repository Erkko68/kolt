// The hand-written half of the JNI layer, what the generated forwarders can't express: native memory, strings, and
// the one function C++ calls a Kotlin class's overrides through. Backs io.github.erkko68.kolt.interop.KoltJni.

#include <jni.h>

#include <cstdint>
#include <cstdlib>

static JavaVM* sVm = nullptr;
static jclass sClass = nullptr;      // KoltJni
static jmethodID sUpcall = nullptr;  // KoltJni.upcall(Object, int, long, long): boolean

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return -1;
    sVm = vm;
    jclass cls = env->FindClass("io/github/erkko68/kolt/interop/KoltJni");
    sClass = static_cast<jclass>(env->NewGlobalRef(cls));
    sUpcall = env->GetStaticMethodID(cls, "upcall", "(Ljava/lang/Object;IJJ)Z");
    env->DeleteLocalRef(cls);
    return JNI_VERSION_1_6;
}

// Jolt calls listeners from its job threads: each attaches once, as a daemon so it never blocks VM exit, and
// detaches when it ends.
static JNIEnv* attachedEnv() {
    JNIEnv* env;
    if (sVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
#ifdef __ANDROID__
        sVm->AttachCurrentThreadAsDaemon(&env, nullptr);
#else
        sVm->AttachCurrentThreadAsDaemon(reinterpret_cast<void**>(&env), nullptr); // the desktop jni.h takes void**
#endif
        struct Detach {
            ~Detach() { sVm->DetachCurrentThread(); }
        };
        thread_local Detach detach;
    }
    return env;
}

// Bridge.hpp's Upcall: user is a global reference to the Kotlin object, which index -1 drops. An exception is
// reported and the function left to C++, rather than unwinding through it.
static int32_t upcall(void* user, int32_t method, const uint64_t* args, uint64_t* result) {
    JNIEnv* env = attachedEnv();
    jobject target = static_cast<jobject>(user);
    if (method < 0) {
        env->DeleteGlobalRef(target);
        return 0;
    }
    jboolean handled = env->CallStaticBooleanMethod(sClass, sUpcall, target, static_cast<jint>(method),
        reinterpret_cast<jlong>(args), reinterpret_cast<jlong>(result));
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        return 0;
    }
    return handled == JNI_TRUE;
}

#define KOLT_JNI(ret, name) extern "C" JNIEXPORT ret JNICALL Java_io_github_erkko68_kolt_interop_KoltJni_##name

KOLT_JNI(jlong, alloc)(JNIEnv*, jclass, jlong size) {
    return reinterpret_cast<jlong>(std::calloc(1, static_cast<size_t>(size)));
}

KOLT_JNI(void, free)(JNIEnv*, jclass, jlong ptr) {
    std::free(reinterpret_cast<void*>(ptr));
}

KOLT_JNI(jobject, view)(JNIEnv* env, jclass, jlong ptr, jlong size) {
    return env->NewDirectByteBuffer(reinterpret_cast<void*>(ptr), size);
}

KOLT_JNI(jstring, readString)(JNIEnv* env, jclass, jlong ptr) {
    return ptr ? env->NewStringUTF(reinterpret_cast<const char*>(ptr)) : nullptr;
}

KOLT_JNI(jlong, getLong)(JNIEnv*, jclass, jlong ptr) {
    return *reinterpret_cast<const jlong*>(ptr);
}

KOLT_JNI(void, putLong)(JNIEnv*, jclass, jlong ptr, jlong value) {
    *reinterpret_cast<jlong*>(ptr) = value;
}

KOLT_JNI(jlong, upcallFunction)(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(&upcall);
}

KOLT_JNI(jlong, newReference)(JNIEnv* env, jclass, jobject target) {
    return reinterpret_cast<jlong>(env->NewGlobalRef(target));
}
