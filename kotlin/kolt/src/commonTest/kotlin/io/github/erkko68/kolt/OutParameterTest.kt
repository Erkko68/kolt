package io.github.erkko68.kolt

import io.github.erkko68.kolt.core.JobSystemThreadPool
import io.github.erkko68.kolt.core.TempAllocatorImpl
import io.github.erkko68.kolt.geometry.AABox
import io.github.erkko68.kolt.math.Float3
import io.github.erkko68.kolt.math.Quat
import io.github.erkko68.kolt.math.Vec3
import io.github.erkko68.kolt.physics.EActivation
import io.github.erkko68.kolt.physics.PhysicsSystem
import io.github.erkko68.kolt.physics.body.Body
import io.github.erkko68.kolt.physics.body.BodyCreationSettings
import io.github.erkko68.kolt.physics.body.BodyFilter
import io.github.erkko68.kolt.physics.body.EBodyType
import io.github.erkko68.kolt.physics.body.EMotionType
import io.github.erkko68.kolt.physics.character.CharacterContactListener
import io.github.erkko68.kolt.physics.character.CharacterVirtual
import io.github.erkko68.kolt.physics.character.CharacterVirtualSettings
import io.github.erkko68.kolt.physics.collision.ObjectLayerFilter
import io.github.erkko68.kolt.physics.collision.ObjectLayerPairFilterTable
import io.github.erkko68.kolt.physics.collision.ShapeFilter
import io.github.erkko68.kolt.physics.collision.broadphase.BroadPhaseLayer
import io.github.erkko68.kolt.physics.collision.broadphase.BroadPhaseLayerFilter
import io.github.erkko68.kolt.physics.collision.broadphase.BroadPhaseLayerInterfaceTable
import io.github.erkko68.kolt.physics.collision.broadphase.ObjectVsBroadPhaseLayerFilterTable
import io.github.erkko68.kolt.physics.collision.shape.BoxShape
import io.github.erkko68.kolt.physics.collision.shape.Shape
import io.github.erkko68.kolt.physics.collision.shape.SphereShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What C++ writes through a parameter comes back: as the function's result, in the list a pointer was made from, and
 * from a Kotlin override to C++.
 */
class OutParameterTest {
    @Test
    fun outParametersAndPointers() {
        Jolt.init()
        val temp = TempAllocatorImpl(10 * 1024 * 1024)
        val jobs = JobSystemThreadPool(2048, 8, 1)
        val broadPhaseLayers = BroadPhaseLayerInterfaceTable(1, 1)
        broadPhaseLayers.mapObjectToBroadPhaseLayer(0, BroadPhaseLayer(0))
        val layerPairs = ObjectLayerPairFilterTable(1)
        layerPairs.enableCollision(0, 0)
        val objectVsBroadPhase = ObjectVsBroadPhaseLayerFilterTable(broadPhaseLayers, 1, layerPairs, 1)
        val system = PhysicsSystem()
        system.init(1024, 0, 1024, 1024, broadPhaseLayers, objectVsBroadPhase, layerPairs)
        val bodies = system.bodyInterface

        // A batch of bodies: Jolt sorts the IDs it's given, and takes them back with the state it returned.
        val sphere = SphereShape(0.5f, null)
        val created = List(3) { i ->
            BodyCreationSettings(sphere, Vec3(10f + 2f * i, 5f, 0f), Quat.IDENTITY, EMotionType.Dynamic, 0).use { bodies.createBody(it)!!.id }
        }
        val batch = created.toMutableList()
        val state = bodies.addBodiesPrepare(batch, batch.size)
        bodies.addBodiesFinalize(batch, batch.size, state, EActivation.Activate)
        assertEquals(created.toSet(), batch.toSet())
        // A list C++ fills is the function's result.
        assertEquals(created.toSet(), system.bodies.toSet())
        assertEquals(created.toSet(), system.getActiveBodies(EBodyType.RigidBody).toSet())
        bodies.deactivateBodies(created.take(2), 2)
        assertEquals(listOf(created[2]), system.getActiveBodies(EBodyType.RigidBody))

        val (position, rotation) = bodies.getPositionAndRotation(created[1])
        assertEquals(Vec3(12f, 5f, 0f), position)
        assertEquals(Quat.IDENTITY, rotation)

        // A box's 12 triangles, into a list as long as C++ may fill.
        val box = BoxShape(Vec3(10f, 1f, 10f), 0.05f, null)
        Shape.GetTrianglesContext().use { context ->
            AABox(Vec3(-100f, -100f, -100f), Vec3(100f, 100f, 100f)).use { all -> box.getTrianglesStart(context, all, Vec3.ZERO, Quat.IDENTITY, Vec3(1f, 1f, 1f)) }
            val vertices = MutableList(3 * 16) { Float3(0f, 0f, 0f) }
            assertEquals(12, box.getTrianglesNext(context, 16, vertices, mutableListOf()))
            assertEquals(setOf(-10f, 10f), vertices.take(36).map { it.x }.toSet())
        }

        // A character standing on a floor that a Kotlin listener says moves: Jolt takes the velocity the override returns.
        val floor = BodyCreationSettings(box, Vec3(0f, -1f, 0f), Quat.IDENTITY, EMotionType.Static, 0).use { bodies.createAndAddBody(it, EActivation.DontActivate) }
        var adjusted = 0
        val listener = object : CharacterContactListener() {
            override fun onAdjustBodyVelocity(character: CharacterVirtual?, body2: Body, linearVelocity: Vec3, angularVelocity: Vec3): Pair<Vec3, Vec3> {
                assertEquals(floor, body2.id)
                adjusted++
                return Pair(linearVelocity + Vec3(1f, 0f, 0f), angularVelocity)
            }
        }
        val character = CharacterVirtualSettings().use { settings ->
            settings.shape = sphere
            CharacterVirtual(settings, Vec3(0f, 0.5f, 0f), Quat.IDENTITY, system)
        }
        character.listener = listener
        val filters = listOf(BroadPhaseLayerFilter(), ObjectLayerFilter(), BodyFilter(), ShapeFilter())
        repeat(10) {
            character.update(1f / 60, Vec3(0f, -9.81f, 0f), filters[0] as BroadPhaseLayerFilter, filters[1] as ObjectLayerFilter, filters[2] as BodyFilter, filters[3] as ShapeFilter, temp)
        }
        assertTrue(adjusted > 0)
        assertEquals(Vec3(1f, 0f, 0f), character.groundVelocity)

        character.close(); listener.close()
        filters.forEach { it.close() }
        (created + floor).forEach { bodies.removeBody(it); bodies.destroyBody(it) }
        box.close(); sphere.close()
        system.close()
        objectVsBroadPhase.close(); layerPairs.close(); broadPhaseLayers.close()
        jobs.close(); temp.close()
        Jolt.shutdown()
    }
}
