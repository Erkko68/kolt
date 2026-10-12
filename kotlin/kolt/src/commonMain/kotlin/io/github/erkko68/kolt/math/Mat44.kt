package io.github.erkko68.kolt.math

/** C++ `JPH::Mat44`: 4×4, stored by column as Jolt does. */
class Mat44 internal constructor(private val v: FloatArray) {
    operator fun get(row: Int, column: Int): Float = v[column * 4 + row]

    /** The 16 values, column by column. */
    fun toArray(): FloatArray = v.copyOf()

    override fun equals(other: Any?) = other is Mat44 && v.contentEquals(other.v)
    override fun hashCode() = v.contentHashCode()
    override fun toString() = (0..3).joinToString("\n") { row -> (0..3).joinToString(" ") { this[row, it].toString() } }

    companion object {
        val IDENTITY = Mat44(FloatArray(16) { if (it % 5 == 0) 1f else 0f })

        /** From 16 values, column by column. */
        fun of(values: FloatArray): Mat44 = Mat44(values.copyOf().also { require(it.size == 16) { "A Mat44 has 16 values, not ${it.size}" } })
    }
}
