@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.erkko68.kolt.interop

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.Pinned
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.get
import kotlinx.cinterop.pin
import kotlinx.cinterop.set
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString
import kotlinx.cinterop.toLong

// Kotlin/Native: externals bind straight to the C symbols; arrays are pinned, not copied.

actual typealias NativePointer = Long

actual val NullPointer: NativePointer = 0L

actual typealias ExternalSymbolName = kotlin.native.SymbolName

actual class InteropScope actual constructor() {
    private val pinned = ArrayList<Pinned<*>>(2)

    private fun <T : Any> keep(p: Pinned<T>): Pinned<T> = p.also { pinned += it }

    actual fun toInterop(array: ByteArray?): NativePointer = if (array == null || array.isEmpty()) NullPointer else keep(array.pin()).addressOf(0).toLong()
    actual fun toInterop(array: IntArray?): NativePointer = if (array == null || array.isEmpty()) NullPointer else keep(array.pin()).addressOf(0).toLong()
    actual fun toInterop(array: LongArray?): NativePointer = if (array == null || array.isEmpty()) NullPointer else keep(array.pin()).addressOf(0).toLong()
    actual fun toInterop(array: FloatArray?): NativePointer = if (array == null || array.isEmpty()) NullPointer else keep(array.pin()).addressOf(0).toLong()
    actual fun toInterop(array: DoubleArray?): NativePointer = if (array == null || array.isEmpty()) NullPointer else keep(array.pin()).addressOf(0).toLong()

    actual fun toInterop(pointers: List<NativePointer>): NativePointer = toInterop(pointers.toLongArray())

    // C wrote straight into the pinned array.
    actual fun NativePointer.fromInterop(result: IntArray) {}
    actual fun NativePointer.fromInterop(result: ByteArray) {}
    actual fun NativePointer.fromInterop(result: LongArray) {}
    actual fun NativePointer.fromInterop(result: FloatArray) {}
    actual fun NativePointer.fromInterop(result: DoubleArray) {}

    actual fun release() {
        pinned.forEach { it.unpin() }
        pinned.clear()
    }
}

actual fun readPointers(ptr: NativePointer, count: Int): List<NativePointer> =
    if (count == 0) emptyList() else ptr.toCPointer<COpaquePointerVar>()!!.let { p -> List(count) { p[it].toLong() } }

actual fun stringFromInterop(ptr: NativePointer): String? = ptr.toCPointer<ByteVar>()?.toKString()

actual object Upcalls {
    // Bridge.hpp's Upcall: index -1 says C++ destroyed the object.
    actual val function: NativePointer = staticCFunction { user: COpaquePointer?, method: Int, args: COpaquePointer?, result: COpaquePointer? ->
        val ref = user!!.asStableRef<NativeObject>()
        if (method < 0) {
            ref.dispose()
            0
        } else if (ref.get().nativeUpcall(method, args.toLong(), result.toLong())) 1 else 0
    }.toLong()

    actual fun reference(target: NativeObject): NativePointer = StableRef.create(target).asCPointer().toLong()
}

actual fun readSlot(args: NativePointer, index: Int): Long = args.toCPointer<LongVar>()!![index]
actual fun readPointerSlot(args: NativePointer, index: Int): NativePointer = readSlot(args, index)
actual fun writeSlot(result: NativePointer, value: Long) {
    result.toCPointer<LongVar>()!![0] = value
}

actual fun readFloats(ptr: NativePointer, count: Int): FloatArray = ptr.toCPointer<FloatVar>()!!.let { p -> FloatArray(count) { p[it] } }
