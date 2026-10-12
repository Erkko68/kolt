package io.github.erkko68.kolt.math

import io.github.erkko68.kolt.interop.readF32

/** C++ `JPH::Float4`: four floats as mesh data stores them. */
data class Float4(val x: Float, val y: Float, val z: Float, val w: Float) {
    internal constructor(v: FloatArray) : this(v.readF32(0), v.readF32(1), v.readF32(2), v.readF32(3))
    internal fun toArray() = floatArrayOf(x, y, z, w)
}
