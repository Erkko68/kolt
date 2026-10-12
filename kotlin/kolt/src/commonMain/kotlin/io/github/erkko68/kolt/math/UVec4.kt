package io.github.erkko68.kolt.math

/** C++ `JPH::UVec4`: four unsigned 32-bit integers, which Kotlin holds as their bits. */
data class UVec4(val x: Int, val y: Int, val z: Int, val w: Int) {
    internal constructor(v: IntArray) : this(v[0], v[1], v[2], v[3])
    internal fun toArray() = intArrayOf(x, y, z, w)
}
