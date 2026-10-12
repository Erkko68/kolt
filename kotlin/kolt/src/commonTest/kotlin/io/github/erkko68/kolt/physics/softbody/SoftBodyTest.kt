package io.github.erkko68.kolt.physics.softbody

import io.github.erkko68.kolt.math.Float3
import io.github.erkko68.kolt.math.Quat
import io.github.erkko68.kolt.math.Vec3
import io.github.erkko68.kolt.physics.EActivation
import io.github.erkko68.kolt.physics.body.EBodyType
import io.github.erkko68.kolt.physics.collision.shape.EShapeSubType
import io.github.erkko68.kolt.world
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SoftBodyTest {
    /** A cube of 5×5×5 vertices falls on the floor and stays on it, as a body of its own type. */
    @Test
    fun cubeFallsOnTheFloor() = world {
        addFloor()
        val shared = SoftBodySharedSettings.createCube(5, 0.25f)!!
        assertEquals(125, shared.vertices.onEach { it.close() }.size)
        val cube = SoftBodyCreationSettings(shared, Vec3(0f, 2f, 0f), Quat.IDENTITY, 0).use { settings ->
            settings.allowSleeping = false
            assertEquals(Vec3(0f, 2f, 0f), settings.position)
            bodies.createAndAddSoftBody(settings, EActivation.Activate)
        }
        assertEquals(EBodyType.SoftBody, bodies.getBodyType(cube))
        assertTrue(body(cube).isSoftBody)
        assertFalse(body(cube).isRigidBody)
        assertEquals(EShapeSubType.SoftBody, bodies.getShape(cube)!!.subType)
        assertEquals(listOf(cube), system.getActiveBodies(EBodyType.SoftBody))
        assertEquals(emptyList(), system.getActiveBodies(EBodyType.RigidBody))
        body(cube).worldSpaceBounds.use { assertEquals(1f, it.size.y, 0.05f) }

        // Its motion properties are a soft body's, which only the caller knows: they have no virtual functions to tell by.
        val motion = body(cube).motionProperties!!.asSoftBodyMotionProperties()
        assertEquals(125, motion.vertices.size)
        assertEquals(1f, motion.volume, 0.05f)
        val top = motion.getVertex(124).position.y

        step(120)
        // Local to the body, which fell with it: the vertex sank, but less than the two meters to the floor.
        assertTrue(bodies.getPosition(cube).y + motion.getVertex(124).position.y in 0.5f..1.1f)
        assertEquals(0.5f, top, 0.01f)
        // A meter high, less what it sags.
        body(cube).worldSpaceBounds.use { bounds ->
            assertEquals(0f, bounds.min.y, 0.1f)
            assertTrue(bounds.max.y in 0.5f..1.1f, "top at ${bounds.max.y}")
        }
        assertTrue(bodies.getPosition(cube).y < 1.5f)
        bodies.removeBody(cube)
        bodies.destroyBody(cube)
        shared.close()
    }

    /** A square of cloth, vertex by vertex: Jolt makes the constraints that hold its edges. */
    @Test
    fun settingsByHand() = world {
        val shared = SoftBodySharedSettings()
        val corners = listOf(Float3(0f, 0f, 0f), Float3(0f, 0f, 1f), Float3(1f, 0f, 1f), Float3(1f, 0f, 0f))
        val vertices = corners.map { SoftBodySharedSettings.Vertex(it, Float3(0f, 0f, 0f), 1f) }
        shared.vertices = vertices
        vertices.forEach { it.close() }
        assertEquals(corners, shared.vertices.map { vertex -> vertex.use { it.position } })
        listOf(SoftBodySharedSettings.Face(0, 1, 2, 0), SoftBodySharedSettings.Face(0, 2, 3, 0)).forEach { face ->
            assertFalse(face.isDegenerate)
            shared.addFace(face)
            face.close()
        }
        assertEquals(listOf(listOf(0, 1, 2), listOf(0, 2, 3)), shared.faces.map { face -> face.use { it.vertex.toList() } })

        SoftBodySharedSettings.VertexAttributes(1e-4f, 1e-4f, 1e-3f, SoftBodySharedSettings.ELRAType.None, 1f).use { attributes ->
            assertEquals(1e-3f, attributes.bendCompliance)
            shared.createConstraints(attributes, 1, SoftBodySharedSettings.EBendType.None, 0.1f)
        }
        // The four sides, the diagonal the faces share and, against shear, the other one, at the lengths they have.
        val edges = shared.edgeConstraints
        assertEquals(6, edges.size)
        val lengths = edges.map { it.restLength }.sorted()
        assertEquals(listOf(1f, 1f, 1f, 1f), lengths.take(4))
        lengths.drop(4).forEach { assertEquals(1.4142f, it, 1e-4f) }
        edges.forEach { it.close() }
        shared.optimize()
        shared.clone()!!.use { copy -> assertEquals(4, copy.vertices.onEach { it.close() }.size) }
        shared.close()
    }
}
