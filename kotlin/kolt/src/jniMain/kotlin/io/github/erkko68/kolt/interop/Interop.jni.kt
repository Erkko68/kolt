package io.github.erkko68.kolt.interop

import java.nio.ByteBuffer

// JVM and Android: externals are JNI methods, whose forwarders generateBindings writes; arrays are copied to native memory and back.

actual typealias NativePointer = Long

actual val NullPointer: NativePointer = 0L

actual fun loadNativeLibrary() {
    KoltJni
}

actual class InteropScope actual constructor() {
    private val allocations = ArrayList<Long>(2)

    private inline fun copyIn(count: Int, width: Int, put: (ByteBuffer) -> Unit): NativePointer {
        if (count == 0) return NullPointer
        val ptr = KoltJni.alloc(count.toLong() * width)
        check(ptr != 0L) { "calloc(${count * width}) failed" }
        allocations += ptr
        put(KoltJni.buffer(ptr, count * width))
        return ptr
    }

    actual fun toInterop(array: ByteArray?): NativePointer = copyIn(array?.size ?: 0, 1) { it.put(array!!) }
    actual fun toInterop(array: IntArray?): NativePointer = copyIn(array?.size ?: 0, 4) { it.asIntBuffer().put(array!!) }
    actual fun toInterop(array: LongArray?): NativePointer = copyIn(array?.size ?: 0, 8) { it.asLongBuffer().put(array!!) }
    actual fun toInterop(array: FloatArray?): NativePointer = copyIn(array?.size ?: 0, 4) { it.asFloatBuffer().put(array!!) }
    actual fun toInterop(array: DoubleArray?): NativePointer = copyIn(array?.size ?: 0, 8) { it.asDoubleBuffer().put(array!!) }

    // ponytail: 8-byte pointers; Android's 32-bit ABIs, which aren't built, need the pointer size.
    actual fun toInterop(pointers: List<NativePointer>): NativePointer = toInterop(pointers.toLongArray())

    private inline fun NativePointer.copyOut(count: Int, width: Int, get: (ByteBuffer) -> Unit) {
        if (this != NullPointer && count > 0) get(KoltJni.buffer(this, count * width))
    }

    actual fun NativePointer.fromInterop(result: ByteArray) = copyOut(result.size, 1) { it.get(result) }
    actual fun NativePointer.fromInterop(result: IntArray) = copyOut(result.size, 4) { it.asIntBuffer().get(result) }
    actual fun NativePointer.fromInterop(result: LongArray) = copyOut(result.size, 8) { it.asLongBuffer().get(result) }
    actual fun NativePointer.fromInterop(result: FloatArray) = copyOut(result.size, 4) { it.asFloatBuffer().get(result) }
    actual fun NativePointer.fromInterop(result: DoubleArray) = copyOut(result.size, 8) { it.asDoubleBuffer().get(result) }

    actual fun release() {
        allocations.forEach(KoltJni::free)
        allocations.clear()
    }
}

actual fun stringFromInterop(ptr: NativePointer): String? = KoltJni.readString(ptr)

actual object Upcalls {
    actual val function: NativePointer = KoltJni.upcallFunction()

    // A global reference, which the native Upcall drops at index -1.
    actual fun reference(target: NativeObject): NativePointer = KoltJni.newReference(target)
}

actual fun readSlot(args: NativePointer, index: Int): Long = KoltJni.getLong(args + index * 8L)
actual fun readPointerSlot(args: NativePointer, index: Int): NativePointer = readSlot(args, index)
actual fun writeSlot(result: NativePointer, value: Long) = KoltJni.putLong(result, value)

actual fun readFloats(ptr: NativePointer, count: Int): FloatArray =
    FloatArray(count).also { if (count > 0) KoltJni.buffer(ptr, count * 4).asFloatBuffer().get(it) }

actual fun writeFloats(ptr: NativePointer, values: FloatArray) {
    if (values.isNotEmpty()) KoltJni.buffer(ptr, values.size * 4).asFloatBuffer().put(values)
}

actual fun readPointers(ptr: NativePointer, count: Int): List<NativePointer> =
    if (count == 0) emptyList() else LongArray(count).also { KoltJni.buffer(ptr, count * 8).asLongBuffer().get(it) }.toList()

@Suppress("NOTHING_TO_INLINE")
actual inline fun FloatArray.readF32(index: Int): Float = this[index]
