package io.github.erkko68.kolt.physics.character

import io.github.erkko68.kolt.World
import io.github.erkko68.kolt.assertNear
import io.github.erkko68.kolt.math.Quat
import io.github.erkko68.kolt.math.Vec3
import io.github.erkko68.kolt.physics.EActivation
import io.github.erkko68.kolt.physics.body.BodyFilter
import io.github.erkko68.kolt.physics.body.EMotionType
import io.github.erkko68.kolt.physics.collision.ObjectLayerFilter
import io.github.erkko68.kolt.physics.collision.ShapeFilter
import io.github.erkko68.kolt.physics.collision.broadphase.BroadPhaseLayerFilter
import io.github.erkko68.kolt.physics.collision.shape.BoxShape
import io.github.erkko68.kolt.physics.collision.shape.CapsuleShape
import io.github.erkko68.kolt.physics.collision.shape.EShapeSubType
import io.github.erkko68.kolt.physics.collision.shape.SphereShape
import io.github.erkko68.kolt.world
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A character stands on the floor, walks, and is stopped by a wall: as a rigid body, and as one Jolt only moves when told to. */
class CharacterTest {
    private val up = Vec3(0f, 1f, 0f)

    @Test
    fun rigidBody() = world {
        val floor = addFloor()
        val capsule = CapsuleShape(0.5f, 0.3f, null)
        val character = CharacterSettings().use { settings ->
            settings.shape = capsule
            settings.mass = 70f
            settings.friction = 0f
            assertEquals(up, settings.up)
            Character(settings, Vec3(0f, 0.9f, 0f), Quat.IDENTITY, 9L, system)
        }
        character.addToPhysicsSystem(EActivation.Activate, true)
        assertEquals(9L, bodies.getUserData(character.bodyID))
        assertEquals(EShapeSubType.Capsule, character.shape!!.subType)
        assertEquals(1 / 70f, body(character.bodyID).motionProperties!!.inverseMass, 1e-6f)

        // What it stands on is found after each step.
        step(30)
        character.postSimulation(0.05f, true)
        assertEquals(CharacterBase.EGroundState.OnGround, character.groundState)
        assertTrue(character.isSupported)
        assertEquals(floor, character.groundBodyID)
        assertNear(up, character.groundNormal, 1e-3f)
        assertEquals(0.8f, character.getPosition(true).y, 0.02f)
        assertEquals("OnGround", CharacterBase.toString(character.groundState))

        character.setLinearVelocity(Vec3(2f, 0f, 0f), true)
        step(30)
        assertEquals(1f, character.getPosition(true).x, 0.05f)
        // Thrown up, nothing is under it.
        character.setPositionAndRotation(Vec3(0f, 5f, 0f), Quat.IDENTITY, EActivation.Activate, true)
        character.postSimulation(0.05f, true)
        assertEquals(CharacterBase.EGroundState.InAir, character.groundState)
        assertFalse(character.isSupported)

        character.removeFromPhysicsSystem(true)
        assertFalse(bodies.isAdded(character.bodyID))
        character.close(); capsule.close()
    }

    private class Filters : AutoCloseable {
        val broadPhase = BroadPhaseLayerFilter()
        val objects = ObjectLayerFilter()
        val bodies = BodyFilter()
        val shapes = ShapeFilter()
        override fun close() = listOf(shapes, bodies, objects, broadPhase).forEach { it.close() }
    }

    private fun World.update(character: CharacterVirtual, filters: Filters, velocity: Vec3, steps: Int) = repeat(steps) {
        character.linearVelocity = velocity
        character.update(1f / 60, system.gravity, filters.broadPhase, filters.objects, filters.bodies, filters.shapes, temp)
    }

    @Test
    fun virtual() = world {
        val floor = addFloor()
        // A wall whose near face is 2.5 m along x.
        val wall = BoxShape(Vec3(0.5f, 2f, 2f), 0.05f, null).use { add(it, Vec3(3f, 2f, 0f), EMotionType.Static) }
        val sphere = SphereShape(0.5f, null)
        val character = CharacterVirtualSettings().use { settings ->
            settings.shape = sphere
            settings.maxSlopeAngle = (PI / 4).toFloat()
            CharacterVirtual(settings, Vec3(0f, 0.5f, 0f), Quat.IDENTITY, 3L, system)
        }
        assertEquals(3L, character.userData)
        // Steeper than 45° is too steep to stand on: a slope's normal 60° from up, and one 30° from it.
        val steep = (PI / 3).toFloat()
        assertTrue(character.isSlopeTooSteep(Vec3(sin(steep), cos(steep), 0f)))
        assertFalse(character.isSlopeTooSteep(Vec3(cos(steep), sin(steep), 0f)))

        Filters().use { filters ->
            update(character, filters, Vec3(1f, 0f, 0f), 60)
            assertEquals(1f, character.position.x, 0.05f)
            assertEquals(CharacterBase.EGroundState.OnGround, character.groundState)
            assertEquals(floor, character.groundBodyID)
            assertTrue(character.hasCollidedWith(floor))
            assertFalse(character.hasCollidedWith(wall))

            // Into the wall, where it stays: its surface half a meter, and its padding, from the wall's.
            update(character, filters, Vec3(1f, 0f, 0f), 120)
            assertEquals(2f - character.characterPadding, character.position.x, 0.02f)
            assertTrue(character.hasCollidedWith(wall))
            val contacts = character.activeContacts
            assertEquals(setOf(floor, wall), contacts.map { it.bodyB }.toSet())
            assertNear(Vec3(-1f, 0f, 0f), contacts.first { it.bodyB == wall }.contactNormal, 1e-3f)
            contacts.forEach { it.close() }

            // Moved off the floor, it has no ground.
            character.position = Vec3(0f, 5f, 0f)
            update(character, filters, Vec3.ZERO, 1)
            assertEquals(CharacterBase.EGroundState.InAir, character.groundState)
            assertEquals(emptyList(), character.activeContacts)

            // Back on it, by the update that also climbs stairs and sticks to the floor.
            character.position = Vec3(0f, 0.5f, 0f)
            update(character, filters, Vec3.ZERO, 1)
            CharacterVirtual.ExtendedUpdateSettings().use { extended ->
                assertEquals(Vec3(0f, -0.5f, 0f), extended.stickToFloorStepDown)
                repeat(30) {
                    character.linearVelocity = Vec3(0f, 0f, 1f)
                    character.extendedUpdate(1f / 60, system.gravity, extended, filters.broadPhase, filters.objects, filters.bodies, filters.shapes, temp)
                }
            }
            assertNear(Vec3(0f, 0.5f, 0.5f), character.position, 0.03f)
            assertEquals(CharacterBase.EGroundState.OnGround, character.groundState)
        }
        character.close(); sphere.close()
    }
}
