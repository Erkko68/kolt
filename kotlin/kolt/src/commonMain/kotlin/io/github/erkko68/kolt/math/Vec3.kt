package io.github.erkko68.kolt.math

/** C++ `JPH::Vec3`. */
data class Vec3(val x: Float, val y: Float, val z: Float) {
    /** As C mirrors it: four lanes, of which Jolt keeps the last equal to z. */
    internal constructor(v: FloatArray) : this(v[0], v[1], v[2])
    internal fun toArray() = floatArrayOf(x, y, z, z)

    operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
    operator fun times(scale: Float) = Vec3(x * scale, y * scale, z * scale)
    operator fun div(scale: Float) = Vec3(x / scale, y / scale, z / scale)
    operator fun unaryMinus() = Vec3(-x, -y, -z)

    infix fun dot(other: Vec3) = x * other.x + y * other.y + z * other.z
    infix fun cross(other: Vec3) = Vec3(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x)
    val length get() = kotlin.math.sqrt(this dot this)
    fun normalized() = this / length

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
    }
}
