package io.github.erkko68.kolt.math

/** C++ `JPH::Quat`: `w + xi + yj + zk`. */
data class Quat(val x: Float, val y: Float, val z: Float, val w: Float) {
    internal constructor(v: FloatArray) : this(v[0], v[1], v[2], v[3])
    internal fun toArray() = floatArrayOf(x, y, z, w)

    companion object {
        val IDENTITY = Quat(0f, 0f, 0f, 1f)
    }
}
