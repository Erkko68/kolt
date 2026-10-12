package io.github.erkko68.kolt.physics.body

import io.github.erkko68.kolt.assertNear
import io.github.erkko68.kolt.math.Quat
import io.github.erkko68.kolt.math.Vec3
import io.github.erkko68.kolt.physics.EActivation
import io.github.erkko68.kolt.physics.collision.CollisionGroup
import io.github.erkko68.kolt.physics.collision.GroupFilterTable
import io.github.erkko68.kolt.physics.collision.shape.BoxShape
import io.github.erkko68.kolt.physics.collision.shape.EShapeSubType
import io.github.erkko68.kolt.physics.collision.shape.SphereShape
import io.github.erkko68.kolt.world
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Bodies move as their settings, and what's done to them, say. */
class BodyTest {
    /** A sphere of 1 kg that nothing slows down or pulls. */
    private fun BodyCreationSettings.free() {
        gravityFactor = 0f
        linearDamping = 0f
        angularDamping = 0f
        overrideMassProperties = EOverrideMassProperties.CalculateInertia
        // The getter's is a copy: what's set on it stays there.
        MassProperties().use { it.mass = 1f; massPropertiesOverride = it }
        massPropertiesOverride.use { assertEquals(1f, it.mass) }
    }

    @Test
    fun settings() = world {
        val sphere = SphereShape(0.5f, null)
        val body = add(sphere, Vec3(1f, 2f, 3f)) {
            friction = 0.75f
            restitution = 0.25f
            gravityFactor = 0.5f
            linearVelocity = Vec3(1f, 0f, 0f)
            motionQuality = EMotionQuality.LinearCast
            userData = -1L
            objectLayer = 0
            assertTrue(hasMassProperties())
        }
        assertEquals(0.75f, bodies.getFriction(body))
        assertEquals(0.25f, bodies.getRestitution(body))
        assertEquals(0.5f, bodies.getGravityFactor(body))
        assertEquals(Vec3(1f, 0f, 0f), bodies.getLinearVelocity(body))
        assertEquals(EMotionQuality.LinearCast, bodies.getMotionQuality(body))
        assertEquals(-1L, bodies.getUserData(body))
        assertEquals(EBodyType.RigidBody, bodies.getBodyType(body))
        assertEquals(EMotionType.Dynamic, bodies.getMotionType(body))
        assertTrue(bodies.isAdded(body))
        assertTrue(bodies.isActive(body))

        // The same, read from the body, which gives back the settings it was made from.
        val it = body(body)
        assertEquals(body, it.id)
        assertTrue(it.isDynamic && it.isRigidBody && it.isActive && it.isInBroadPhase)
        assertFalse(it.isStatic || it.isKinematic || it.isSensor || it.isSoftBody)
        assertEquals(Vec3(1f, 2f, 3f), it.position)
        assertEquals(EShapeSubType.Sphere, it.shape!!.subType)
        it.worldSpaceBounds.use { bounds -> assertEquals(Vec3(0.5f, 1.5f, 2.5f) to Vec3(1.5f, 2.5f, 3.5f), bounds.min to bounds.max) }
        it.bodyCreationSettings.use { settings ->
            assertEquals(Vec3(1f, 2f, 3f), settings.position)
            assertEquals(0.75f, settings.friction)
            assertEquals(EMotionQuality.LinearCast, settings.motionQuality)
        }
        val motion = it.motionProperties!!
        assertEquals(0.5f, motion.gravityFactor)
        assertEquals(1 / (1000f * 4 / 3 * PI.toFloat() * 0.125f), motion.inverseMass, 1e-6f)

        bodies.removeBody(body)
        assertFalse(bodies.isAdded(body))
        bodies.destroyBody(body)
        assertNull(system.bodyLockInterfaceNoLock.tryGetBody(body))
        assertEquals(0, system.numBodies)
        sphere.close()
    }

    @Test
    fun impulsesAndForces() = world {
        val sphere = SphereShape(0.5f, null)
        val pushed = add(sphere, Vec3.ZERO) { free() }
        assertEquals(1f, body(pushed).motionProperties!!.inverseMass, 1e-5f)
        bodies.addImpulse(pushed, Vec3(2f, 0f, 0f))
        assertNear(Vec3(2f, 0f, 0f), bodies.getLinearVelocity(pushed))
        step(60)
        assertNear(Vec3(2f, 0f, 0f), bodies.getPosition(pushed), 1e-3f)

        // A force lasts the step it's added before: 60 N on 1 kg for 1/60 s.
        val forced = add(sphere, Vec3(0f, 10f, 0f)) { free() }
        bodies.addForce(forced, Vec3(0f, 0f, 60f), EActivation.Activate)
        assertEquals(Vec3(0f, 0f, 60f), body(forced).accumulatedForce)
        step(2)
        assertNear(Vec3(0f, 0f, 1f), bodies.getLinearVelocity(forced), 1e-4f)
        assertEquals(Vec3.ZERO, body(forced).accumulatedForce)

        // Spinning about y, a point a meter out on x moves along -z.
        val spun = add(sphere, Vec3(0f, 20f, 0f)) { free() }
        bodies.setAngularVelocity(spun, Vec3(0f, 1f, 0f))
        assertNear(Vec3(0f, 0f, -1f), bodies.getPointVelocity(spun, Vec3(1f, 20f, 0f)))
        val (linear, angular) = bodies.getLinearAndAngularVelocity(spun)
        assertEquals(Vec3.ZERO to Vec3(0f, 1f, 0f), linear to angular)
        step(60)
        assertNear(Quat.rotation(Vec3(0f, 1f, 0f), 1f), bodies.getRotation(spun), 1e-3f)

        // No faster than its limit.
        bodies.setMaxLinearVelocity(pushed, 1f)
        bodies.setLinearVelocity(pushed, Vec3(0f, 0f, 0f))
        bodies.addImpulse(pushed, Vec3(0f, 5f, 0f))
        step(1)
        assertEquals(1f, bodies.getLinearVelocity(pushed).length, 1e-4f)
        sphere.close()
    }

    @Test
    fun gravity() = world {
        val sphere = SphereShape(0.5f, null)
        val falling = add(sphere, Vec3(0f, 10f, 0f)) { linearDamping = 0f }
        val floating = add(sphere, Vec3(5f, 10f, 0f)) { gravityFactor = 0f }
        val fixed = add(sphere, Vec3(10f, 10f, 0f), EMotionType.Static) { allowDynamicOrKinematic = true }
        system.gravity = Vec3(0f, -1f, 0f)
        step(60)
        assertEquals(-1f, bodies.getLinearVelocity(falling).y, 1e-3f)
        assertEquals(9.5f, bodies.getPosition(falling).y, 0.02f)
        assertEquals(Vec3(5f, 10f, 0f), bodies.getPosition(floating))
        assertEquals(Vec3(10f, 10f, 0f), bodies.getPosition(fixed))
        assertTrue(bodies.isActive(falling))

        // Static until it's made dynamic, which it was created able to be.
        bodies.setMotionType(fixed, EMotionType.Dynamic, EActivation.Activate)
        step(60)
        assertTrue(bodies.getPosition(fixed).y < 10f)
        sphere.close()
    }

    @Test
    fun kinematic() = world {
        val sphere = SphereShape(0.5f, null)
        val platform = add(sphere, Vec3.ZERO, EMotionType.Kinematic)
        val target = Quat.rotation(Vec3(0f, 0f, 1f), 1f)
        bodies.moveKinematic(platform, Vec3(0f, 0f, 5f), target, 1f)
        assertNear(Vec3(0f, 0f, 5f), bodies.getLinearVelocity(platform))
        step(60)
        assertNear(Vec3(0f, 0f, 5f), bodies.getPosition(platform), 1e-3f)
        assertNear(target, bodies.getRotation(platform), 1e-3f)
        // Gravity is nothing to it.
        assertEquals(0f, bodies.getPosition(platform).y)
        sphere.close()
    }

    @Test
    fun allowedDegreesOfFreedom() = world {
        val sphere = SphereShape(0.5f, null)
        val body = add(sphere, Vec3.ZERO) {
            free()
            allowedDOFs = EAllowedDOFs.Plane2D
            assertTrue(EAllowedDOFs.TranslationX in allowedDOFs)
            assertFalse(EAllowedDOFs.TranslationZ in allowedDOFs)
        }
        bodies.addImpulse(body, Vec3(1f, 1f, 1f))
        bodies.addAngularImpulse(body, Vec3(1f, 1f, 1f))
        step(10)
        assertEquals(0f, bodies.getPosition(body).z)
        assertNear(Vec3(1f, 1f, 0f), bodies.getLinearVelocity(body))
        val spin = bodies.getAngularVelocity(body)
        assertTrue(spin.x == 0f && spin.y == 0f && spin.z > 0f, "$spin")
        sphere.close()
    }

    @Test
    fun restingBodiesSleep() = world {
        addFloor()
        val ball = SphereShape(0.5f, null).use { add(it, Vec3(0f, 0.5f, 0f)) }
        val awake = SphereShape(0.5f, null).use { add(it, Vec3(5f, 0.5f, 0f)) { allowSleeping = false } }
        step(120)
        assertFalse(bodies.isActive(ball))
        assertTrue(bodies.isActive(awake))
        assertEquals(listOf(awake), system.getActiveBodies(EBodyType.RigidBody))
        bodies.activateBody(ball)
        assertTrue(bodies.isActive(ball))
    }

    @Test
    fun restitutionBounces() = world {
        // Of two bodies' restitutions the contact takes the larger.
        addFloor()
        val ball = SphereShape(0.5f, null).use { add(it, Vec3(0f, 3f, 0f)) { restitution = 1f } }
        val dull = SphereShape(0.5f, null).use { add(it, Vec3(5f, 3f, 0f)) { restitution = 0f } }
        var highestAfterLanding = 0f
        var landed = false
        repeat(120) {
            step(1)
            if (bodies.getLinearVelocity(ball).y > 0f) landed = true
            if (landed) highestAfterLanding = maxOf(highestAfterLanding, bodies.getPosition(ball).y)
        }
        assertTrue(highestAfterLanding > 2f, "bounced to $highestAfterLanding")
        assertEquals(0.5f, bodies.getPosition(dull).y, 0.05f)
    }

    /** A sensor, and a body of a group that doesn't collide with the floor's, fall through it. */
    @Test
    fun sensorsAndCollisionGroups() = world {
        val floor = addFloor()
        val sphere = SphereShape(0.5f, null)
        val solid = add(sphere, Vec3(0f, 1f, 0f))
        val sensor = add(sphere, Vec3(5f, 1f, 0f)) { isSensor = true }
        assertTrue(bodies.isSensor(sensor))

        val filter = GroupFilterTable(2)
        filter.disableCollision(0, 1)
        assertFalse(filter.isCollisionEnabled(1, 0))
        val ghost = add(sphere, Vec3(10f, 1f, 0f))
        CollisionGroup(filter, 0, 0).use { group ->
            bodies.setCollisionGroup(floor, group)
            group.subGroupID = 1
            bodies.setCollisionGroup(ghost, group)
            CollisionGroup(filter, 0, 0).use { assertFalse(group.canCollide(it)) }
        }
        bodies.getCollisionGroup(ghost).use { assertEquals(0 to 1, it.groupID to it.subGroupID) }

        step(30)
        assertTrue(system.wereBodiesInContact(floor, solid))
        assertFalse(system.wereBodiesInContact(floor, ghost))
        step(60)
        assertEquals(0.5f, bodies.getPosition(solid).y, 0.05f)
        assertTrue(bodies.getPosition(sensor).y < -2f)
        assertTrue(bodies.getPosition(ghost).y < -2f)
        filter.close(); sphere.close()
    }

    @Test
    fun swappingTheShape() = world {
        val body = SphereShape(0.5f, null).use { add(it, Vec3.ZERO) }
        BoxShape(Vec3(1f, 1f, 1f), 0.05f, null).use { bodies.setShape(body, it, true, EActivation.Activate) }
        assertEquals(EShapeSubType.Box, bodies.getShape(body)!!.subType)
        assertEquals(1 / 8000f, body(body).motionProperties!!.inverseMass, 1e-7f)
        bodies.getTransformedShape(body).use { shape ->
            assertEquals(body, shape.bodyID)
            shape.worldSpaceBounds.use { assertEquals(Vec3(1f, 1f, 1f), it.max) }
        }
    }
}
