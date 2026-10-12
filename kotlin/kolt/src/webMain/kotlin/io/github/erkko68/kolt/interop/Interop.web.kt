package io.github.erkko68.kolt.interop

// js and wasmJs: externals resolve to the `Jph*` globals kolt.js installs from its wasm's exports
// (c/kolt/web/kolt-globals.js); arrays are copied into its heap. wasm32: a pointer is an Int.

actual typealias NativePointer = Int

actual val NullPointer: NativePointer = 0

/** kolt.js's Emscripten module. */
internal external interface KoltModule : JsAny {
    fun _malloc(size: Int): Int
    fun _free(ptr: Int)
    fun addFunction(function: JsAny, signature: String): Int
    fun UTF8ToString(ptr: Int): String
}

private fun koltModule(): KoltModule? = js("globalThis.koltModule || null")

internal val kolt: KoltModule by lazy { koltModule() ?: error("kolt.wasm isn't loaded: wait for loadJolt() before Jolt.init()") }

actual fun loadNativeLibrary() {
    kolt
}

// The heap's views are read from the module on every access: memory growth replaces them.
// ponytail: a JS call per element from wasmJs; bulk copies if large arrays show up in profiles.
private fun getI8(m: KoltModule, ptr: Int): Byte = js("m.HEAP8[ptr]")
private fun setI8(m: KoltModule, ptr: Int, value: Byte): Unit = js("{ m.HEAP8[ptr] = value; }")
private fun getI32(m: KoltModule, ptr: Int): Int = js("m.HEAP32[ptr >> 2]")
private fun setI32(m: KoltModule, ptr: Int, value: Int): Unit = js("{ m.HEAP32[ptr >> 2] = value; }")
private fun getF32(m: KoltModule, ptr: Int): Float = js("m.HEAPF32[ptr >> 2]")
private fun setF32(m: KoltModule, ptr: Int, value: Float): Unit = js("{ m.HEAPF32[ptr >> 2] = value; }")
private fun getF64(m: KoltModule, ptr: Int): Double = js("m.HEAPF64[ptr >> 3]")
private fun setF64(m: KoltModule, ptr: Int, value: Double): Unit = js("{ m.HEAPF64[ptr >> 3] = value; }")
private fun getI64(m: KoltModule, ptr: Int): Long = (getI32(m, ptr + 4).toLong() shl 32) or (getI32(m, ptr).toLong() and 0xFFFFFFFFL)
private fun setI64(m: KoltModule, ptr: Int, value: Long) {
    setI32(m, ptr, value.toInt())
    setI32(m, ptr + 4, (value ushr 32).toInt())
}

actual class InteropScope actual constructor() {
    private val allocations = ArrayList<Int>(2)

    private inline fun copyIn(count: Int, width: Int, write: (index: Int, ptr: Int) -> Unit): NativePointer {
        if (count == 0) return NullPointer
        val ptr = kolt._malloc(count * width)
        check(ptr != 0) { "malloc(${count * width}) failed" }
        allocations += ptr
        for (i in 0 until count) write(i, ptr + i * width)
        return ptr
    }

    actual fun toInterop(array: ByteArray?): NativePointer = copyIn(array?.size ?: 0, 1) { i, p -> setI8(kolt, p, array!![i]) }
    actual fun toInterop(array: IntArray?): NativePointer = copyIn(array?.size ?: 0, 4) { i, p -> setI32(kolt, p, array!![i]) }
    actual fun toInterop(array: LongArray?): NativePointer = copyIn(array?.size ?: 0, 8) { i, p -> setI64(kolt, p, array!![i]) }
    actual fun toInterop(array: FloatArray?): NativePointer = copyIn(array?.size ?: 0, 4) { i, p -> setF32(kolt, p, array!![i]) }
    actual fun toInterop(array: DoubleArray?): NativePointer = copyIn(array?.size ?: 0, 8) { i, p -> setF64(kolt, p, array!![i]) }

    actual fun toInterop(pointers: List<NativePointer>): NativePointer = toInterop(pointers.toIntArray())

    private inline fun NativePointer.copyOut(count: Int, width: Int, read: (index: Int, ptr: Int) -> Unit) {
        if (this != NullPointer) for (i in 0 until count) read(i, this + i * width)
    }

    actual fun NativePointer.fromInterop(result: ByteArray) = copyOut(result.size, 1) { i, p -> result[i] = getI8(kolt, p) }
    actual fun NativePointer.fromInterop(result: IntArray) = copyOut(result.size, 4) { i, p -> result[i] = getI32(kolt, p) }
    actual fun NativePointer.fromInterop(result: LongArray) = copyOut(result.size, 8) { i, p -> result[i] = getI64(kolt, p) }
    actual fun NativePointer.fromInterop(result: FloatArray) = copyOut(result.size, 4) { i, p -> result[i] = getF32(kolt, p) }
    actual fun NativePointer.fromInterop(result: DoubleArray) = copyOut(result.size, 8) { i, p -> result[i] = getF64(kolt, p) }

    actual fun release() {
        allocations.forEach { kolt._free(it) }
        allocations.clear()
    }
}

actual fun stringFromInterop(ptr: NativePointer): String? = if (ptr == NullPointer) null else kolt.UTF8ToString(ptr)

private fun upcallFunction(f: (user: Int, method: Int, args: Int, result: Int) -> Int): JsAny = js("f")

actual object Upcalls {
    // A reference is a key of this map, which the native Upcall drops at index -1.
    private val targets = HashMap<Int, NativeObject>()
    private var lastReference = 0

    actual val function: NativePointer = kolt.addFunction(
        upcallFunction { user, method, args, result ->
            if (method < 0) {
                targets.remove(user)
                0
            } else if (targets.getValue(user).nativeUpcall(method, args, result)) 1 else 0
        },
        "iiiii",
    )

    actual fun reference(target: NativeObject): NativePointer = (++lastReference).also { targets[it] = target }
}

actual fun readSlot(args: NativePointer, index: Int): Long = getI64(kolt, args + index * 8)
actual fun readPointerSlot(args: NativePointer, index: Int): NativePointer = getI32(kolt, args + index * 8)
actual fun writeSlot(result: NativePointer, value: Long) = setI64(kolt, result, value)

actual fun readFloats(ptr: NativePointer, count: Int): FloatArray = FloatArray(count) { getF32(kolt, ptr + it * 4) }

actual fun writeFloats(ptr: NativePointer, values: FloatArray) {
    values.forEachIndexed { i, v -> setF32(kolt, ptr + i * 4, v) }
}

actual fun readPointers(ptr: NativePointer, count: Int): List<NativePointer> = List(count) { getI32(kolt, ptr + it * 4) }
