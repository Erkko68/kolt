package geo

// Hand-written, as every math mirror's class is: the generator wraps what passes a Vec2 once this file exists.
class Vec2(val x: Float, val y: Float) {
    internal constructor(v: FloatArray) : this(v[0], v[1])

    internal fun toArray() = floatArrayOf(x, y)
}
