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
