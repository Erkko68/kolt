package io.github.erkko68.kolt

import io.github.erkko68.kolt.capi.JphFactory_SetInstance
import io.github.erkko68.kolt.capi.Jph_RegisterDefaultAllocator
import io.github.erkko68.kolt.core.Factory
import io.github.erkko68.kolt.core.JobSystemThreadPool
import io.github.erkko68.kolt.core.TempAllocatorImpl
import io.github.erkko68.kolt.interop.NullPointer
import io.github.erkko68.kolt.math.Quat
import io.github.erkko68.kolt.math.Vec3
import io.github.erkko68.kolt.physics.EPhysicsUpdateError
import io.github.erkko68.kolt.physics.PhysicsSystem
import io.github.erkko68.kolt.physics.body.BodyCreationSettings
import io.github.erkko68.kolt.physics.EActivation
import io.github.erkko68.kolt.physics.body.EMotionType
import io.github.erkko68.kolt.physics.collision.ObjectLayerPairFilterTable
import io.github.erkko68.kolt.physics.collision.broadphase.BroadPhaseLayer
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
        Jph_RegisterDefaultAllocator()
        val factory = Factory()
        JphFactory_SetInstance(factory.factoryHandle)
        registerTypes()

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

        val bodies = system.bodyInterface
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

        bodies.removeBody(body)
        bodies.destroyBody(body)
        sphere.close()
        system.close()
        objectVsBroadPhase.close()
        layerPairs.close()
        broadPhaseLayers.close()
        jobs.close()
        temp.close()
        unregisterTypes()
        JphFactory_SetInstance(NullPointer)
        factory.close()
    }
}
