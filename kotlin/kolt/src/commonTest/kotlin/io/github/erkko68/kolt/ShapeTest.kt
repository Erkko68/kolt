package io.github.erkko68.kolt

import io.github.erkko68.kolt.interop.NativeException
import io.github.erkko68.kolt.math.Vec3
import io.github.erkko68.kolt.physics.collision.shape.ConvexHullShapeSettings
import io.github.erkko68.kolt.physics.collision.shape.EShapeSubType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Arrays of math types cross, and a result that's a value or an error returns the value or throws the error. */
class ShapeTest {
    @Test
    fun convexHull() {
        Jolt.init()
        val corners = listOf(-1f, 1f).flatMap { x -> listOf(-1f, 1f).flatMap { y -> listOf(-1f, 1f).map { z -> Vec3(x, y, z) } } }
        ConvexHullShapeSettings(corners, 0.05f, null).use { settings ->
            assertEquals(corners, settings.points)
            settings.create().use { shape ->
                assertEquals(EShapeSubType.ConvexHull, shape.subType)
                assertEquals(Vec3(-1f, -1f, -1f), shape.localBounds.use { it.min })
                assertEquals(8f, shape.volume, 0.5f)
            }
        }
        // By pointer, as copies aligned for SIMD.
        ConvexHullShapeSettings(corners, corners.size, 0.05f, null).use { settings ->
            assertEquals(corners, settings.points)
        }
        // Two points span no hull.
        ConvexHullShapeSettings(corners.take(2), 0.05f, null).use { settings ->
            assertTrue(assertFailsWith<NativeException> { settings.create() }.message!!.isNotEmpty())
        }
        Jolt.shutdown()
    }
}
