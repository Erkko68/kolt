package io.github.erkko68.kolt.interop

// The API calls the Jph* C API through the `external fun`s of capi/, named like the C symbol. Kotlin/Native binds
// them through [ExternalSymbolName].

/** Address of a native object. */
expect class NativePointer

expect val NullPointer: NativePointer

/** The C symbol a common `external fun` binds to (`@SymbolName` on Kotlin/Native, absent elsewhere). */
@OptIn(ExperimentalMultiplatform::class)
@OptionalExpectation
expect annotation class ExternalSymbolName(val name: String)

/**
 * Hands Kotlin arrays to C for the duration of one call: pinned on Native. Empty and null arrays map to [NullPointer].
 * Use through [interopScope].
 */
expect class InteropScope() {
    fun toInterop(array: ByteArray?): NativePointer
    fun toInterop(array: IntArray?): NativePointer
    fun toInterop(array: LongArray?): NativePointer
    fun toInterop(array: FloatArray?): NativePointer
    fun toInterop(array: DoubleArray?): NativePointer

    /** An array of pointers (`T* const*`, or a `T**` C writes through); read it back with [readPointers]. */
    fun toInterop(pointers: List<NativePointer>): NativePointer

    /** Copies what C wrote at this pointer back into [result] (a no-op where the array was pinned). */
    fun NativePointer.fromInterop(result: ByteArray)
    fun NativePointer.fromInterop(result: IntArray)
    fun NativePointer.fromInterop(result: LongArray)
    fun NativePointer.fromInterop(result: FloatArray)
    fun NativePointer.fromInterop(result: DoubleArray)

    fun release()
}

/** A NUL-terminated UTF-8 copy of [string] for a `const char*` parameter; null stays [NullPointer]. */
fun InteropScope.toInterop(string: String?): NativePointer = if (string == null) NullPointer else toInterop(string.encodeToByteArray() + 0)

/** Reads a NUL-terminated UTF-8 string returned by C, or null for [NullPointer]. */
expect fun stringFromInterop(ptr: NativePointer): String?

/** A native call that returns its value or an error returned the error: its message. */
class NativeException(message: String) : RuntimeException(message)

/**
 * How C++ calls the functions a Kotlin class overrides: [function] is the one C function it calls, with [reference]'s
 * result for the object, which keeps [reference]'s target alive until C++ destroys its object.
 */
expect object Upcalls {
    val function: NativePointer
    fun reference(target: NativeObject): NativePointer
}

/** Slot [index] of an upcall's arguments: a number's bits, or with [readPointerSlot] a pointer. */
expect fun readSlot(args: NativePointer, index: Int): Long
expect fun readPointerSlot(args: NativePointer, index: Int): NativePointer

/** Sets an upcall's result. */
expect fun writeSlot(result: NativePointer, value: Long)

/** A copy of [count] floats of an array C owns (a math mirror). */
expect fun readFloats(ptr: NativePointer, count: Int): FloatArray

/** A copy of [count] pointers of an array at [ptr]. */
expect fun readPointers(ptr: NativePointer, count: Int): List<NativePointer>

inline fun <T> interopScope(block: InteropScope.() -> T): T {
    val scope = InteropScope()
    try {
        return scope.block()
    } finally {
        scope.release()
    }
}

/**
 * A native object's Kotlin wrapper. [owned]: the object, or a reference to it, is this wrapper's to give back, which
 * `close` does, once. One Jolt returned from an object it keeps is borrowed, and `close` leaves it alone.
 */
abstract class NativeObject internal constructor(internal var owned: Boolean) {
    internal inline fun closeOnce(close: () -> Unit) {
        if (owned) {
            owned = false
            close()
        }
    }

    /**
     * Runs the virtual function at index [method] of a class that overrides it in Kotlin, with [args] in 64-bit slots,
     * writing what it returns to [result]; false for one it leaves to C++.
     */
    internal open fun nativeUpcall(method: Int, args: NativePointer, result: NativePointer): Boolean = false

    /** Makes a borrowed wrapper hold the reference [retain] adds. */
    internal inline fun own(retain: () -> Unit) {
        if (!owned) {
            retain()
            owned = true
        }
    }
}
