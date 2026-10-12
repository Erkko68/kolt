package io.github.erkko68.kolt.math

/** C++ `JPH::Quat`: `w + xi + yj + zk`. */
data class Quat(val x: Float, val y: Float, val z: Float, val w: Float) {
    internal constructor(v: FloatArray) : this(v[0], v[1], v[2], v[3])
    internal fun toArray() = floatArrayOf(x, y, z, w)

    /** The rotation of [other], then this one. */
    operator fun times(other: Quat) = Quat(
        w * other.x + x * other.w + y * other.z - z * other.y,
        w * other.y - x * other.z + y * other.w + z * other.x,
        w * other.z + x * other.y - y * other.x + z * other.w,
        w * other.w - x * other.x - y * other.y - z * other.z,
    )

    /** [v] rotated by this unit quaternion. */
    operator fun times(v: Vec3): Vec3 {
        val q = Vec3(x, y, z)
        val t = (q cross v) * 2f
        return v + t * w + (q cross t)
    }

    fun conjugated() = Quat(-x, -y, -z, w)

    companion object {
        val IDENTITY = Quat(0f, 0f, 0f, 1f)

        /** The rotation of [angle] radians about the unit [axis]. */
        fun rotation(axis: Vec3, angle: Float): Quat {
            val s = kotlin.math.sin(angle / 2)
            return Quat(axis.x * s, axis.y * s, axis.z * s, kotlin.math.cos(angle / 2))
        }
    }
}
