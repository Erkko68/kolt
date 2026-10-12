package io.github.erkko68.kolt.math

import io.github.erkko68.kolt.interop.readF32

/** C++ `JPH::Vec4`. */
data class Vec4(val x: Float, val y: Float, val z: Float, val w: Float) {
    internal constructor(v: FloatArray) : this(v.readF32(0), v.readF32(1), v.readF32(2), v.readF32(3))
    internal fun toArray() = floatArrayOf(x, y, z, w)
}
