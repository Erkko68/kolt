package io.github.erkko68.kolt.physics.collision.shape

import io.github.erkko68.kolt.assertNear
import io.github.erkko68.kolt.jolt
import io.github.erkko68.kolt.core.TempAllocatorImpl
import io.github.erkko68.kolt.geometry.IndexedTriangle
import io.github.erkko68.kolt.geometry.Plane
import io.github.erkko68.kolt.math.Float3
import io.github.erkko68.kolt.math.Quat
import io.github.erkko68.kolt.math.Vec3
import io.github.erkko68.kolt.physics.collision.RayCast
import io.github.erkko68.kolt.physics.collision.RayCastResult
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Each kind of shape Jolt has is built, and is the size it was built to be. */
class ShapeKindsTest {
    private val pi = PI.toFloat()
    private val sphereVolume = 4f / 3 * pi * 0.5f * 0.5f * 0.5f

    private fun Shape.bounds() = localBounds.use { it.min to it.max }

    /** How far down the y axis a ray from 10 above the origin goes before it hits the shape, or null. */
    private fun Shape.hitFromAbove(x: Float = 0f, z: Float = 0f): Float? =
        RayCast(Vec3(x, 10f, z), Vec3(0f, -20f, 0f)).use { ray ->
            RayCastResult().use { hit -> SubShapeIDCreator().use { id -> if (castRay(ray, id, hit)) hit.fraction * 20f else null } }
        }

    @Test
    fun convex() = jolt {
        BoxShape(Vec3(1f, 2f, 3f), 0.05f, null).use { box ->
            assertEquals(EShapeType.Convex, box.type)
            assertEquals(EShapeSubType.Box, box.subType)
            assertEquals(Vec3(1f, 2f, 3f), box.halfExtent)
            assertEquals(0.05f, box.convexRadius)
            assertEquals(48f, box.volume, 1e-3f)
            assertEquals(Vec3(-1f, -2f, -3f) to Vec3(1f, 2f, 3f), box.bounds())
            assertEquals(1f, box.innerRadius)
            assertFalse(box.mustBeStatic())
            // Its mass at the default density, of water, which the shape then takes another of.
            assertEquals(1000f, box.density)
            box.massProperties.use { assertEquals(48_000f, it.mass, 1f) }
            box.density = 2f
            box.massProperties.use { assertEquals(96f, it.mass, 1e-2f) }
            assertEquals(8f, box.hitFromAbove()!!, 1e-4f)
            assertEquals(null, box.hitFromAbove(x = 1.5f))
            assertNear(Vec3(0f, 1f, 0f), box.getSurfaceNormal(SubShapeIDCreator().use { it.id }, Vec3(0f, 2f, 0f)))
        }
        CapsuleShape(1f, 0.5f, null).use { capsule ->
            assertEquals(EShapeSubType.Capsule, capsule.subType)
            assertEquals(1f, capsule.halfHeightOfCylinder)
            assertEquals(0.5f, capsule.radius)
            assertEquals(pi * 0.25f * 2f + sphereVolume, capsule.volume, 1e-3f)
            assertEquals(Vec3(-0.5f, -1.5f, -0.5f) to Vec3(0.5f, 1.5f, 0.5f), capsule.bounds())
        }
        CylinderShape(1f, 0.5f, 0.05f, null).use { cylinder ->
            assertEquals(EShapeSubType.Cylinder, cylinder.subType)
            assertEquals(1f, cylinder.halfHeight)
            assertEquals(pi * 0.25f * 2f, cylinder.volume, 1e-3f)
        }
        TriangleShape(Vec3(0f, 0f, 0f), Vec3(0f, 0f, 1f), Vec3(1f, 0f, 0f), 0f, null).use { triangle ->
            assertEquals(EShapeSubType.Triangle, triangle.subType)
            assertEquals(listOf(Vec3(0f, 0f, 0f), Vec3(0f, 0f, 1f), Vec3(1f, 0f, 0f)), listOf(triangle.vertex1, triangle.vertex2, triangle.vertex3))
            assertEquals(10f, triangle.hitFromAbove(0.25f, 0.25f)!!, 1e-4f)
            assertEquals(null, triangle.hitFromAbove(0.75f, 0.75f))
        }
    }

    /** A `ShapeSettings` builds the shape, which isn't always of its kind. */
    @Test
    fun fromSettings() = jolt {
        TaperedCapsuleShapeSettings(1f, 0.5f, 0.25f, null).use { settings ->
            assertTrue(settings.isValid)
            assertFalse(settings.isSphere)
            settings.userData = 42L
            settings.create().use { shape ->
                assertEquals(EShapeSubType.TaperedCapsule, shape.subType)
                assertEquals(42L, shape.userData)
            }
            // One sphere inside the other is a sphere, once the settings forget the shape they made.
            settings.bottomRadius = 3f
            assertTrue(settings.isSphere)
            settings.create().use { assertEquals(EShapeSubType.TaperedCapsule, it.subType) }
            settings.clearCachedResult()
            settings.create().use {
                assertTrue(it is SphereShape || (it as RotatedTranslatedShape).innerShape is SphereShape)
                assertEquals(4f / 3 * pi * 27f, it.volume, 0.1f)
            }
        }
        TaperedCylinderShapeSettings(1f, 0.5f, 0.25f, 0.05f, null).use { settings ->
            settings.create().use { shape ->
                assertEquals(EShapeSubType.TaperedCylinder, shape.subType)
                // A cone's frustum.
                assertEquals(pi * 2f / 3 * (0.25f + 0.125f + 0.0625f), shape.volume, 1e-2f)
            }
        }
        EmptyShapeSettings(Vec3(1f, 2f, 3f)).use { settings ->
            assertEquals(Vec3(1f, 2f, 3f), settings.centerOfMass)
            settings.create().use { shape ->
                assertEquals(EShapeSubType.Empty, shape.subType)
                assertEquals(Vec3(1f, 2f, 3f), shape.centerOfMass)
                assertEquals(0f, shape.volume)
            }
        }
    }

    @Test
    fun decorated() = jolt {
        val sphere = SphereShape(0.5f, null)
        ScaledShape(sphere, Vec3(2f, 2f, 2f)).use { scaled ->
            assertEquals(EShapeType.Decorated, scaled.type)
            assertEquals(EShapeSubType.Scaled, scaled.subType)
            assertEquals(Vec3(2f, 2f, 2f), scaled.scale)
            assertEquals(8 * sphereVolume, scaled.volume, 1e-3f)
            assertEquals(0.5f, (scaled.innerShape as SphereShape).radius)
            assertEquals(9f, scaled.hitFromAbove()!!, 1e-4f)
        }
        RotatedTranslatedShape(Vec3(0f, 3f, 0f), Quat.IDENTITY, sphere).use { moved ->
            assertEquals(EShapeSubType.RotatedTranslated, moved.subType)
            assertEquals(Vec3(0f, 3f, 0f), moved.position)
            assertEquals(Quat.IDENTITY, moved.rotation)
            assertEquals(sphereVolume, moved.volume, 1e-4f)
            // Around its center of mass, where a shape's origin is.
            assertEquals(Vec3(0f, 3f, 0f), moved.centerOfMass)
            assertEquals(Vec3(-0.5f, -0.5f, -0.5f) to Vec3(0.5f, 0.5f, 0.5f), moved.bounds())
        }
        OffsetCenterOfMassShapeSettings(Vec3(0f, -1f, 0f), sphere).use { settings ->
            assertEquals(Vec3(0f, -1f, 0f), settings.offset)
            settings.create().use { shape ->
                assertEquals(EShapeSubType.OffsetCenterOfMass, shape.subType)
                assertEquals(Vec3(0f, -1f, 0f), shape.centerOfMass)
            }
        }
        sphere.scaleShape(Vec3(3f, 3f, 3f)).use { assertEquals(27 * sphereVolume, it.volume, 1e-2f) }
        sphere.close()
    }

    @Test
    fun compound() = jolt {
        val sphere = SphereShape(0.5f, null)
        val box = BoxShape(Vec3(1f, 1f, 1f), 0.05f, null)
        StaticCompoundShapeSettings().use { settings ->
            settings.addShape(Vec3(-2f, 0f, 0f), Quat.IDENTITY, sphere, 1)
            settings.addShape(Vec3(2f, 0f, 0f), Quat.IDENTITY, sphere, 2)
            val subShapes = settings.subShapes
            assertEquals(listOf(Vec3(-2f, 0f, 0f) to 1, Vec3(2f, 0f, 0f) to 2), subShapes.map { it.position to it.userData })
            assertEquals(EShapeSubType.Sphere, subShapes[0].shapePtr!!.subType)
            subShapes.forEach { it.close() }
            listOf(settings.create(), TempAllocatorImpl(1024 * 1024).use { settings.clearCachedResult(); settings.create(it) }).forEach { shape ->
                // The class its settings make, though `create` returns a Shape.
                assertTrue(shape is StaticCompoundShape && shape !is MutableCompoundShape)
                assertEquals(2, (shape as CompoundShape).numSubShapes)
                shape.getSubShape(1).use { assertTrue(it.shape is SphereShape) }
                assertEquals(EShapeType.Compound, shape.type)
                assertEquals(EShapeSubType.StaticCompound, shape.subType)
                assertEquals(2 * sphereVolume, shape.volume, 1e-3f)
                assertEquals(Vec3(-2.5f, -0.5f, -0.5f) to Vec3(2.5f, 0.5f, 0.5f), shape.bounds())
                assertEquals(9.5f, shape.hitFromAbove(x = 2f)!!, 1e-4f)
                assertEquals(null, shape.hitFromAbove())
                assertEquals(2, shape.saveSubShapeState().size)
                shape.close()
            }
        }
        MutableCompoundShape().use { compound ->
            assertEquals(0, compound.addShape(Vec3(-2f, 0f, 0f), Quat.IDENTITY, sphere, 7, Int.MAX_VALUE))
            assertEquals(1, compound.addShape(Vec3(2f, 0f, 0f), Quat.IDENTITY, box, 8, Int.MAX_VALUE))
            assertEquals(2, compound.numSubShapes)
            assertEquals(8, compound.getCompoundUserData(1))
            compound.getSubShape(1).use { assertEquals(EShapeSubType.Box, it.shape!!.subType) }
            // The ID of the box in the compound leads back to it, with nothing of the ID left over.
            val boxID = SubShapeIDCreator().use { root -> compound.getSubShapeIDFromIndex(1, root).use { it.id } }
            assertTrue(compound.isSubShapeIDValid(boxID))
            assertEquals(1, compound.getSubShapeIndexFromID(boxID).first)
            val (leaf, remainder) = compound.getLeafShape(boxID)
            assertEquals(EShapeSubType.Box, leaf!!.subType)
            assertEquals(SubShapeIDCreator().use { it.id }, remainder)

            compound.modifyShape(1, Vec3(0f, 5f, 0f), Quat.IDENTITY)
            assertEquals(4f, compound.hitFromAbove()!!, 1e-4f)
            compound.removeShape(1)
            assertEquals(1, compound.numSubShapes)
            assertEquals(sphereVolume, compound.volume, 1e-4f)
            compound.adjustCenterOfMass()
            assertNear(Vec3(-2f, 0f, 0f), compound.centerOfMass)
        }
        box.close(); sphere.close()
    }

    @Test
    fun mesh() = jolt {
        // A square of two triangles in the plane y = 1, facing up.
        val vertices = listOf(Float3(-1f, 1f, -1f), Float3(-1f, 1f, 1f), Float3(1f, 1f, 1f), Float3(1f, 1f, -1f))
        val triangles = listOf(IndexedTriangle(0, 1, 2, 0, 5), IndexedTriangle(0, 2, 3, 0, 6))
        MeshShapeSettings(vertices, triangles, emptyList()).use { settings ->
            assertEquals(vertices, settings.triangleVertices)
            assertEquals(listOf(5, 6), settings.indexedTriangles.map { triangle -> triangle.use { it.userData } })
            settings.create().use { shape ->
                assertEquals(EShapeType.Mesh, shape.type)
                assertEquals(EShapeSubType.Mesh, shape.subType)
                assertTrue(shape.mustBeStatic())
                assertEquals(Vec3(-1f, 1f, -1f) to Vec3(1f, 1f, 1f), shape.bounds())
                assertEquals(9f, shape.hitFromAbove(0.5f, -0.5f)!!, 1e-4f)
                assertEquals(null, shape.hitFromAbove(x = 2f))
            }
        }
        triangles.forEach { it.close() }
    }

    @Test
    fun heightField() = jolt {
        // 8×8 samples a meter apart, rising with x.
        val count = 8
        val samples = FloatArray(count * count) { (it % count).toFloat() }
        HeightFieldShapeSettings(samples, Vec3.ZERO, Vec3(1f, 1f, 1f), count, ByteArray(0), emptyList()).use { settings ->
            assertEquals(count, settings.sampleCount)
            assertEquals(samples.toList(), settings.heightSamples.toList())
            val (min, max, _) = settings.determineMinAndMaxSample()
            assertEquals(0f to 7f, min to max)
            settings.create().use { shape ->
                assertEquals(EShapeSubType.HeightField, shape.subType)
                assertNear(Vec3(3f, 3f, 5f), (shape as HeightFieldShape).getPosition(3, 5), 1e-3f)
                assertTrue(shape.mustBeStatic())
                assertNear(Vec3.ZERO, shape.bounds().first)
                assertNear(Vec3(7f, 7f, 7f), shape.bounds().second)
                // The slope, halfway between samples.
                assertEquals(10f - 2.5f, shape.hitFromAbove(2.5f, 3.5f)!!, 1e-2f)
            }
        }
    }

    @Test
    fun plane() = jolt {
        Plane(Vec3(0f, 1f, 0f), -1f).use { plane ->
            PlaneShape(plane, null, 100f).use { shape ->
                assertEquals(EShapeSubType.Plane, shape.subType)
                assertEquals(100f, shape.halfExtent)
                shape.plane.use { assertEquals(Vec3(0f, 1f, 0f) to -1f, it.normal to it.constant) }
                assertTrue(shape.mustBeStatic())
                assertEquals(9f, shape.hitFromAbove(30f, -30f)!!, 1e-4f)
            }
        }
    }
}
