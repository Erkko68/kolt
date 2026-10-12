package io.github.erkko68.kolt.interop

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The hand-written half of the JNI layer (native side: c/kolt/jni/KoltJni.cpp): native memory, strings, and the
 * function C++ calls a Kotlin class's overrides through. Loads libkolt, the C API and its JNI forwarders.
 * Android finds it in the app's native libraries. ponytail: the desktop JVM, on java.library.path; a loader extracting
 * it from the jar once one is published.
 */
internal object KoltJni {
    init {
        System.loadLibrary("kolt")
    }

    /** Zero-filled native memory (calloc). */
    @JvmStatic external fun alloc(size: Long): Long
    @JvmStatic external fun free(ptr: Long)

    /** A direct ByteBuffer over [size] bytes of native memory at [ptr], in big-endian (JNI's default). */
    @JvmStatic private external fun view(ptr: Long, size: Long): ByteBuffer

    /** [view] in native byte order, as C arrays need. */
    fun buffer(ptr: Long, size: Int): ByteBuffer = view(ptr, size.toLong()).order(ByteOrder.nativeOrder())

    /** Reads a NUL-terminated UTF-8 string, or null for a null pointer. */
    @JvmStatic external fun readString(ptr: Long): String?

    // ponytail: a JNI call per upcall slot; Unsafe or the FFM API if listeners show up in profiles.
    @JvmStatic external fun getLong(ptr: Long): Long
    @JvmStatic external fun putLong(ptr: Long, value: Long)

    /** Bridge.hpp's `Upcall`, which calls [upcall] with what [newReference] made of the object. */
    @JvmStatic external fun upcallFunction(): Long
    @JvmStatic external fun newReference(target: Any): Long

    /** Called by the native `Upcall`, on whichever thread C++ calls the function from. */
    @JvmStatic
    fun upcall(target: Any, method: Int, args: Long, result: Long): Boolean = (target as NativeObject).nativeUpcall(method, args, result)
}
