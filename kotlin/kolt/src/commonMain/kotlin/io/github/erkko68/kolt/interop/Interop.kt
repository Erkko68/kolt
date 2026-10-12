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
    fun toInterop(array: IntArray?): NativePointer
    fun toInterop(array: FloatArray?): NativePointer
    fun toInterop(array: DoubleArray?): NativePointer

    /** Copies what C wrote at this pointer back into [result] (a no-op where the array was pinned). */
    fun NativePointer.fromInterop(result: IntArray)
    fun NativePointer.fromInterop(result: FloatArray)
    fun NativePointer.fromInterop(result: DoubleArray)

    fun release()
}

inline fun <T> interopScope(block: InteropScope.() -> T): T {
    val scope = InteropScope()
    try {
        return scope.block()
    } finally {
        scope.release()
    }
}
