package io.github.erkko68.kolt.math

import io.github.erkko68.kolt.interop.readF32

/** C++ `JPH::Float3`: three floats as mesh data stores them. */
data class Float3(val x: Float, val y: Float, val z: Float) {
    internal constructor(v: FloatArray) : this(v.readF32(0), v.readF32(1), v.readF32(2))
    internal fun toArray() = floatArrayOf(x, y, z)
}
