package io.github.erkko68.kolt.math

import io.github.erkko68.kolt.assertNear
import io.github.erkko68.kolt.world
import io.github.erkko68.kolt.geometry.AABox
import io.github.erkko68.kolt.geometry.Plane
import io.github.erkko68.kolt.physics.EActivation
import io.github.erkko68.kolt.physics.body.EAllowedDOFs
import io.github.erkko68.kolt.physics.collision.shape.SphereShape
import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/** The hand-written math types: their own arithmetic, that it's Jolt's, and that each crosses to C and back. */
class MathTest {
    private val x = Vec3(1f, 0f, 0f)
    private val y = Vec3(0f, 1f, 0f)
    private val z = Vec3(0f, 0f, 1f)
    private val quarterTurn = (PI / 2).toFloat()

    @Test
    fun vec3() {
        assertEquals(Vec3(5f, 7f, 9f), Vec3(1f, 2f, 3f) + Vec3(4f, 5f, 6f))
        assertEquals(Vec3(-3f, -3f, -3f), Vec3(1f, 2f, 3f) - Vec3(4f, 5f, 6f))
        assertEquals(Vec3(2f, 4f, 6f), Vec3(1f, 2f, 3f) * 2f)
        assertEquals(Vec3(0.5f, 1f, 1.5f), Vec3(1f, 2f, 3f) / 2f)
        assertEquals(Vec3(-1f, -2f, -3f), -Vec3(1f, 2f, 3f))
        assertEquals(32f, Vec3(1f, 2f, 3f) dot Vec3(4f, 5f, 6f))
        assertEquals(z, x cross y)
        assertNear(-z, y cross x)
        assertEquals(5f, Vec3(3f, 4f, 0f).length)
        assertNear(Vec3(0.6f, 0.8f, 0f), Vec3(3f, 4f, 0f).normalized())
    }

    @Test
    fun quat() {
        val aboutY = Quat.rotation(y, quarterTurn)
        assertNear(-z, aboutY * x)
        assertNear(y, aboutY * y)
        assertEquals(Vec3(1f, 2f, 3f), Quat.IDENTITY * Vec3(1f, 2f, 3f))
        // The product applies its right side first: x about X stays x, then about Y turns to -z.
        val aboutX = Quat.rotation(x, quarterTurn)
        assertNear(aboutY * (aboutX * y), (aboutY * aboutX) * y)
        assertNear(x, (aboutY * aboutX) * y)
        assertNear(Quat.IDENTITY, aboutY * aboutY.conjugated())
        assertNear(x, aboutY.conjugated() * (aboutY * x))
    }

    @Test
    fun mat44() {
        assertEquals(1f, Mat44.IDENTITY[2, 2])
        assertEquals(0f, Mat44.IDENTITY[2, 3])
        val values = FloatArray(16) { it.toFloat() }
        val matrix = Mat44.of(values)
        // By column: the value at row 1 of column 3.
        assertEquals(13f, matrix[1, 3])
        assertEquals(matrix, Mat44.of(values))
        assertEquals(matrix.hashCode(), Mat44.of(values).hashCode())
        assertNotEquals(matrix, Mat44.IDENTITY)
        // Neither the array it was made from nor the one it hands out is the matrix's own.
        values[13] = 0f
        matrix.toArray()[13] = 0f
        assertEquals(13f, matrix[1, 3])
        assertFailsWith<IllegalArgumentException> { Mat44.of(FloatArray(12)) }
    }

    /** A body's transform, which Jolt computes from the position and rotation Kotlin gave it, is what Kotlin's math says. */
    @Test
    fun agreesWithJolt() = world {
        val rotation = Quat.rotation(Vec3(1f, 2f, 3f).normalized(), 1f) * Quat.rotation(y, quarterTurn)
        val position = Vec3(1f, 2f, 3f)
        val body = SphereShape(0.5f, null).use { add(it, Vec3.ZERO) }
        bodies.setPositionAndRotation(body, position, rotation, EActivation.DontActivate)
        assertNear(rotation, bodies.getRotation(body))

        val transform = bodies.getWorldTransform(body)
        listOf(x, y, z, position).forEachIndexed { column, expected ->
            val rotated = if (column < 3) rotation * expected else expected
            assertNear(rotated, Vec3(transform[0, column], transform[1, column], transform[2, column]))
        }
        assertEquals(listOf(0f, 0f, 0f, 1f), (0..3).map { transform[3, it] })
        // And back: a box moved by the matrix Jolt returned.
        AABox(Vec3(-1f, -1f, -1f), Vec3(1f, 1f, 1f)).use { box ->
            box.transformed(transform).use { moved ->
                assertNear(position, moved.center)
                // A rotated cube's box is wider, by at most the diagonal's √3.
                assertEquals(1f, moved.extent.x, sqrt(3f) - 1 + 1e-4f)
            }
        }
    }

    /** Each math type goes to C and comes back as it went. */
    @Test
    fun roundTrips() = world {
        AABox(DVec3(-1.0, -2.0, -3.0), DVec3(1.0, 2.0, 3.0)).use { box ->
            assertEquals(Vec3(1f, 2f, 3f), box.max)
            assertEquals(true, box.contains(DVec3(0.5, -1.5, 2.5)))
            assertEquals(false, box.contains(DVec3(0.5, -2.5, 2.5)))
        }
        Plane(Vec4(0f, 1f, 0f, -2f)).use { plane ->
            assertEquals(y, plane.normal)
            assertEquals(-2f, plane.constant)
            assertEquals(3f, plane.signedDistance(Vec3(7f, 5f, 7f)))
            val stored = mutableListOf(Float4(0f, 0f, 0f, 0f))
            plane.storeFloat4(stored)
            assertEquals(listOf(Float4(0f, 1f, 0f, -2f)), stored)
        }
        // A mask of all bits or none per lane, the fourth being no axis: a body free to move in x and y alone.
        val body = SphereShape(0.5f, null).use { shape ->
            add(shape, Vec3.ZERO) { allowedDOFs = EAllowedDOFs.TranslationX or EAllowedDOFs.TranslationY }
        }
        assertEquals(UVec4(-1, -1, 0, -1), body(body).motionProperties!!.linearDOFsMask)
    }
}
