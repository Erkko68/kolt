package io.github.erkko68.kolt.interop

// Kotlin/Native: externals bind straight to the C symbols.

actual typealias NativePointer = Long

actual val NullPointer: NativePointer = 0L

actual typealias ExternalSymbolName = kotlin.native.SymbolName
