package io.github.erkko68.kolt.math

/** C++ `JPH::Float4`: four floats as mesh data stores them. */
data class Float4(val x: Float, val y: Float, val z: Float, val w: Float) {
    internal constructor(v: FloatArray) : this(v[0], v[1], v[2], v[3])
    internal fun toArray() = floatArrayOf(x, y, z, w)
}
