package io.github.erkko68.kolt.math

/** C++ `JPH::Float3`: three floats as mesh data stores them. */
data class Float3(val x: Float, val y: Float, val z: Float) {
    internal constructor(v: FloatArray) : this(v[0], v[1], v[2])
    internal fun toArray() = floatArrayOf(x, y, z)
}
