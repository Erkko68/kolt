package io.github.erkko68.kolt.interop

import io.github.erkko68.kolt.Jolt
import io.github.erkko68.kolt.core.JobSystemThreadPool
import io.github.erkko68.kolt.core.TempAllocatorImpl
import io.github.erkko68.kolt.math.Quat
import io.github.erkko68.kolt.math.Vec3
import io.github.erkko68.kolt.physics.EPhysicsUpdateError
import io.github.erkko68.kolt.physics.PhysicsSystem
import io.github.erkko68.kolt.physics.body.BodyCreationSettings
import io.github.erkko68.kolt.physics.body.BodyFilter
import io.github.erkko68.kolt.physics.EActivation
import io.github.erkko68.kolt.physics.body.EMotionType
import io.github.erkko68.kolt.physics.collision.ObjectLayerFilter
import io.github.erkko68.kolt.physics.collision.ObjectLayerPairFilterTable
import io.github.erkko68.kolt.physics.collision.RRayCast
import io.github.erkko68.kolt.physics.collision.RayCastResult
import io.github.erkko68.kolt.physics.collision.broadphase.BroadPhaseLayer
import io.github.erkko68.kolt.physics.collision.broadphase.BroadPhaseLayerFilter
import io.github.erkko68.kolt.physics.collision.broadphase.BroadPhaseLayerInterfaceTable
import io.github.erkko68.kolt.physics.collision.broadphase.ObjectVsBroadPhaseLayerFilterTable
import io.github.erkko68.kolt.physics.collision.shape.EShapeSubType
import io.github.erkko68.kolt.physics.collision.shape.Shape
import io.github.erkko68.kolt.physics.collision.shape.SphereShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The generated classes reach Jolt: a sphere dropped from 10 m has fallen about g/2 after a second. Numbers, enums,
 * IDs, handles and math types cross, a derived class passes as its base, and `use` destroys.
 */
class FallingSphereTest {
    @Test
    fun sphereFalls() {
        Jolt.init()

        val temp = TempAllocatorImpl(10 * 1024 * 1024)
        val jobs = JobSystemThreadPool(2048, 8, 1)
        // One object layer in one broad phase layer, colliding with itself.
        val broadPhaseLayers = BroadPhaseLayerInterfaceTable(1, 1)
        broadPhaseLayers.mapObjectToBroadPhaseLayer(0, BroadPhaseLayer(0))
        val layerPairs = ObjectLayerPairFilterTable(1)
        layerPairs.enableCollision(0, 0)
        val objectVsBroadPhase = ObjectVsBroadPhaseLayerFilterTable(broadPhaseLayers, 1, layerPairs, 1)
        val system = PhysicsSystem()
        system.init(1024, 0, 1024, 1024, broadPhaseLayers, objectVsBroadPhase, layerPairs)
        assertEquals(Vec3(0f, -9.81f, 0f), system.gravity)

        val sphere = SphereShape(0.5f, null)
        assertEquals(0.5f, sphere.radius)
        val shape: Shape = sphere
        assertEquals(EShapeSubType.Sphere, shape.subType)

        // Borrowed from the system: closing it leaves the system's alone.
        val bodies = system.bodyInterface
        bodies.close()
        assertEquals(100, system.physicsSettings.use { it.numVelocitySteps * 10 })
        val body = BodyCreationSettings(sphere, Vec3(0f, 10f, 0f), Quat.IDENTITY, EMotionType.Dynamic, 0).use { settings ->
            assertEquals(EMotionType.Dynamic, settings.motionType)
            assertEquals(Vec3(0f, 10f, 0f), settings.position)
            bodies.createAndAddBody(settings, EActivation.Activate)
        }
        assertEquals(1, system.numBodies)

        repeat(60) { assertEquals(EPhysicsUpdateError.None, system.update(1f / 60, 1, temp, jobs)) }
        val position = bodies.getPosition(body)
        assertEquals(0f, position.x)
        assertTrue(position.y in 4.5f..5.5f, "y = ${position.y}")
        assertTrue(bodies.getLinearVelocity(body).y < -9f)
        // A ray down the sphere's path hits it at its top.
        RayCastResult().use { hit ->
            val filters = Triple(BroadPhaseLayerFilter(), ObjectLayerFilter(), BodyFilter())
            RRayCast(Vec3(0f, 20f, 0f), Vec3(0f, -20f, 0f)).use { ray ->
                assertTrue(system.narrowPhaseQuery.castRay(ray, hit, filters.first, filters.second, filters.third))
            }
            assertEquals(body, hit.bodyID)
            assertEquals((20f - position.y - 0.5f) / 20f, hit.fraction, 1e-3f)
            filters.first.close(); filters.second.close(); filters.third.close()
        }
        bodies.setUserData(body, 1L shl 40)
        assertEquals(1L shl 40, bodies.getUserData(body))
        // The body's shape is the sphere, borrowed: only the wrapper that owns a reference releases one.
        val borrowed = bodies.getShape(body)!!
        assertEquals(EShapeSubType.Sphere, borrowed.subType)
        // And a SphereShape, as C++ says it is, though getShape returns a Shape.
        assertEquals(0.5f, (borrowed as SphereShape).radius)
        borrowed.close()
        sphere.close()
        sphere.close()
        assertEquals(EShapeSubType.Sphere, bodies.getShape(body)!!.subType)

        bodies.removeBody(body)
        bodies.destroyBody(body)
        system.close()
        objectVsBroadPhase.close()
        layerPairs.close()
        broadPhaseLayers.close()
        jobs.close()
        temp.close()
        Jolt.shutdown()
    }
}
