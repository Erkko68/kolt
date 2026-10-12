package io.github.erkko68.kolt.geometry

import io.github.erkko68.kolt.assertNear
import io.github.erkko68.kolt.jolt
import io.github.erkko68.kolt.math.Float3
import io.github.erkko68.kolt.math.Mat44
import io.github.erkko68.kolt.math.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The geometry Jolt's API takes and returns: objects Kotlin owns, whose arithmetic is Jolt's. */
class GeometryTest {
    private val one = Vec3(1f, 1f, 1f)

    @Test
    fun aaBox() = jolt {
        AABox(-one, one).use { box ->
            assertTrue(box.isValid)
            assertEquals(Vec3.ZERO to one, box.center to box.extent)
            assertEquals(Vec3(2f, 2f, 2f), box.size)
            assertEquals(8f to 24f, box.volume to box.surfaceArea)
            assertTrue(box.contains(Vec3(0.5f, -0.5f, 1f)))
            assertFalse(box.contains(Vec3(0.5f, -0.5f, 1.5f)))
            assertEquals(Vec3(1f, 0.5f, -1f), box.getClosestPoint(Vec3(3f, 0.5f, -3f)))
            assertEquals(4f, box.getSqDistanceTo(Vec3(3f, 0f, 0f)))

            AABox(Vec3(0.5f, 0.5f, 0.5f), Vec3(3f, 3f, 3f)).use { other ->
                assertTrue(box.overlaps(other))
                assertFalse(box.contains(other))
                box.intersect(other).use { assertEquals(Vec3(0.5f, 0.5f, 0.5f) to one, it.min to it.max) }
                // Grown to hold the other: the object is Kotlin's to change.
                box.encapsulate(other)
                assertTrue(box.contains(other))
                assertEquals(-one to Vec3(3f, 3f, 3f), box.min to box.max)
            }
            box.encapsulate(Vec3(0f, -5f, 0f))
            assertEquals(-5f, box.min.y)
            box.translate(Vec3(0f, 5f, 0f))
            assertEquals(0f, box.min.y)
            box.min = Vec3(-3f, -3f, -3f)
            box.scaled(Vec3(2f, 1f, 1f)).use { assertEquals(Vec3(-6f, -3f, -3f) to Vec3(6f, 8f, 3f), it.min to it.max) }
            box.setEmpty()
            assertFalse(box.isValid)
        }
        AABox(Vec3(1f, 2f, 3f), 0.5f).use { assertEquals(Vec3(0.5f, 1.5f, 2.5f), it.min) }
        AABox.fromTwoPoints(one, -one).use { assertEquals(-one to one, it.min to it.max) }
    }

    @Test
    fun plane() = jolt {
        // The floor a meter up.
        Plane.fromPointAndNormal(Vec3(5f, 1f, 5f), Vec3(0f, 1f, 0f)).use { plane ->
            assertEquals(Vec3(0f, 1f, 0f) to -1f, plane.normal to plane.constant)
            assertEquals(2f, plane.signedDistance(Vec3(0f, 3f, 0f)))
            assertEquals(-1f, plane.signedDistance(Vec3.ZERO))
            assertEquals(Vec3(7f, 1f, 7f), plane.projectPointOnPlane(Vec3(7f, 9f, 7f)))
            plane.offset(2f).use { assertEquals(0f, it.signedDistance(Vec3(0f, 3f, 0f))) }
            AABox(-one, one).use { assertTrue(it.overlaps(plane)) }
            AABox(-one, Vec3(1f, 0.5f, 1f)).use { assertFalse(it.overlaps(plane)) }
            // Three planes meet in a point, unless two of them never do.
            Plane(Vec3(1f, 0f, 0f), -2f).use { x ->
                Plane(Vec3(0f, 0f, 1f), -3f).use { z ->
                    val (meet, point) = Plane.intersectPlanes(plane, x, z)
                    assertTrue(meet)
                    assertNear(Vec3(2f, 1f, 3f), point)
                }
                assertFalse(Plane.intersectPlanes(plane, plane, x).first)
            }
        }
        Plane.fromPointsCCW(Vec3.ZERO, Vec3(0f, 0f, 1f), Vec3(1f, 0f, 0f)).use { assertNear(Vec3(0f, 1f, 0f), it.normal) }
    }

    @Test
    fun orientedBox() = jolt {
        // A unit cube turned 45° about y reaches √2 along x: a box 1.2 away overlaps it, the axis-aligned cube doesn't.
        val s = 0.70710677f
        val turned = Mat44.of(floatArrayOf(s, 0f, -s, 0f, 0f, 1f, 0f, 0f, s, 0f, s, 0f, 0f, 0f, 0f, 1f))
        AABox(Vec3(1.2f, -1f, -1f), Vec3(3f, 1f, 1f)).use { near ->
            OrientedBox(turned, one).use { box ->
                assertEquals(turned, box.orientation)
                assertEquals(one, box.halfExtents)
                assertTrue(box.overlaps(near, 1e-6f))
            }
            OrientedBox(Mat44.IDENTITY, one).use { box ->
                assertFalse(box.overlaps(near, 1e-6f))
                OrientedBox(turned, one).use { assertTrue(box.overlaps(it, 1e-6f)) }
            }
        }
    }

    @Test
    fun triangles() = jolt {
        Triangle(Vec3.ZERO, Vec3(0f, 0f, 3f), Vec3(3f, 0f, 0f), 2, 7).use { triangle ->
            assertEquals(Vec3(1f, 0f, 1f), triangle.centroid)
            assertEquals(listOf(Float3(0f, 0f, 0f), Float3(0f, 0f, 3f), Float3(3f, 0f, 0f)), triangle.v)
            assertEquals(2 to 7, triangle.materialIndex to triangle.userData)
            AABox().use { box ->
                box.setEmpty()
                box.encapsulate(triangle)
                assertEquals(Vec3.ZERO to Vec3(3f, 0f, 3f), box.min to box.max)
            }
        }
        IndexedTriangle(5, 3, 4, 1, 0).use { triangle ->
            triangle.lowestIndexFirst.use { assertEquals(1, it.materialIndex) }
            val vertices = List(6) { Float3(it.toFloat(), 0f, 0f) }
            AABox.fromTriangle(vertices, triangle).use { assertEquals(3f to 5f, it.min.x to it.max.x) }
        }
    }
}
