package io.github.erkko68.kolt.math

/** C++ `JPH::DVec3`: a position in double precision. */
data class DVec3(val x: Double, val y: Double, val z: Double) {
    /** As C mirrors it: four lanes, of which Jolt keeps the last equal to z. */
    internal constructor(v: DoubleArray) : this(v[0], v[1], v[2])
    internal fun toArray() = doubleArrayOf(x, y, z, z)
}
