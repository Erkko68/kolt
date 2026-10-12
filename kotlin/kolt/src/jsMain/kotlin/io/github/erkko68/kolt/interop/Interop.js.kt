package io.github.erkko68.kolt.interop

actual fun FloatArray.readF32(index: Int): Float = shortestF32(this[index])

private fun shortestF32(v: Float): Float = js("""{
    for (let p = 1; p < 10; p++) { const d = Number(v.toPrecision(p)); if (Math.fround(d) === v) return d; }
    return v;
}""")
