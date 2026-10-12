package io.github.erkko68.kolt.math

/** C++ `JPH::Vec3`. */
data class Vec3(val x: Float, val y: Float, val z: Float) {
    /** As C mirrors it: four lanes, of which Jolt keeps the last equal to z. */
    internal constructor(v: FloatArray) : this(v[0], v[1], v[2])
    internal fun toArray() = floatArrayOf(x, y, z, z)

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
    }
}
