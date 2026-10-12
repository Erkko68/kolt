package io.github.erkko68.kolt.physics.constraints

import io.github.erkko68.kolt.World
import io.github.erkko68.kolt.assertNear
import io.github.erkko68.kolt.math.Vec3
import io.github.erkko68.kolt.physics.body.BodyID
import io.github.erkko68.kolt.physics.body.EMotionType
import io.github.erkko68.kolt.physics.collision.shape.SphereShape
import io.github.erkko68.kolt.world
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Each kind of constraint holds a ball to an anchor the way it's meant to, while gravity pulls on the ball. */
class ConstraintTest {
    private val anchorAt = Vec3(0f, 10f, 0f)
    private val ballAt = Vec3(1f, 10f, 0f)

    /** A static anchor and, a meter along x from it, a ball: far enough apart not to touch. */
    private fun World.anchorAndBall(): Pair<BodyID, BodyID> = SphereShape(0.25f, null).use { sphere ->
        add(sphere, anchorAt, EMotionType.Static) to add(sphere, ballAt) { linearDamping = 0f; angularDamping = 0f; allowSleeping = false }
    }

    private fun World.distance(a: BodyID, b: BodyID) = (bodies.getPosition(a) - bodies.getPosition(b)).length

    @Test
    fun fixed() = world {
        val (anchor, ball) = anchorAndBall()
        val constraint = FixedConstraintSettings().use { settings ->
            settings.autoDetectPoint = true
            bodies.createConstraint(settings, anchor, ball)!!
        }
        // The class its settings make, though `createConstraint` returns a TwoBodyConstraint; as the system's are.
        assertTrue(constraint is FixedConstraint)
        assertTrue(constraint.constraintSettings is FixedConstraintSettings)
        assertEquals(EConstraintType.TwoBodyConstraint, constraint.type)
        assertEquals(EConstraintSubType.Fixed, constraint.subType)
        assertEquals(anchor to ball, constraint.body1!!.id to constraint.body2!!.id)
        system.addConstraint(constraint)
        assertTrue(system.constraints.single() is FixedConstraint)
        step(60)
        assertNear(ballAt, bodies.getPosition(ball), 1e-2f)

        // Disabled, it holds nothing; removed, the system has none.
        constraint.enabled = false
        assertFalse(constraint.enabled)
        step(30)
        assertTrue(bodies.getPosition(ball).y < 9.5f)
        system.removeConstraint(constraint)
        assertEquals(0, system.constraints.size)
        constraint.close()
    }

    @Test
    fun distanceAndPoint() = world {
        val (anchor, ball) = anchorAndBall()
        val rope = DistanceConstraintSettings().use { settings ->
            settings.space = EConstraintSpace.WorldSpace
            settings.point1 = anchorAt
            settings.point2 = ballAt
            // Negative for the distance between the points.
            assertEquals(-1f to -1f, settings.minDistance to settings.maxDistance)
            DistanceConstraint(body(anchor), body(ball), settings)
        }
        assertEquals(EConstraintSubType.Distance, rope.subType)
        assertEquals(1f, rope.maxDistance, 1e-5f)
        system.addConstraint(rope)
        // A pendulum: it falls, but no further from the anchor.
        step(45)
        assertTrue(bodies.getPosition(ball).y < 9.5f)
        assertEquals(1f, distance(anchor, ball), 1e-2f)
        assertTrue(abs(rope.totalLambdaPosition) > 0f)
        rope.setDistance(0.5f, 2f)
        assertEquals(0.5f to 2f, rope.minDistance to rope.maxDistance)
        system.removeConstraint(rope)
        rope.close()

        // The same from a point both bodies share, the anchor's center.
        val (anchor2, ball2) = SphereShape(0.25f, null).use { sphere ->
            add(sphere, Vec3(20f, 10f, 0f), EMotionType.Static) to add(sphere, Vec3(21f, 10f, 0f))
        }
        val joint = PointConstraintSettings().use { settings ->
            settings.point1 = Vec3(20f, 10f, 0f)
            settings.point2 = Vec3(20f, 10f, 0f)
            settings.create(body(anchor2), body(ball2))!!
        }
        assertEquals(EConstraintSubType.Point, joint.subType)
        system.addConstraint(joint)
        step(45)
        assertTrue(bodies.getPosition(ball2).y < 9.5f)
        assertEquals(1f, distance(anchor2, ball2), 1e-2f)
        system.removeConstraint(joint)
        joint.close()
    }

    @Test
    fun hinge() = world {
        val (anchor, ball) = anchorAndBall()
        val hinge = HingeConstraintSettings().use { settings ->
            settings.space = EConstraintSpace.WorldSpace
            settings.point1 = anchorAt
            settings.point2 = anchorAt
            settings.hingeAxis1 = Vec3(0f, 0f, 1f)
            settings.hingeAxis2 = Vec3(0f, 0f, 1f)
            settings.normalAxis1 = Vec3(1f, 0f, 0f)
            settings.normalAxis2 = Vec3(1f, 0f, 0f)
            settings.limitsMin = -0.5f
            settings.limitsMax = 0.5f
            HingeConstraint(body(anchor), body(ball), settings)
        }
        assertEquals(EConstraintSubType.Hinge, hinge.subType)
        assertTrue(hinge.hasLimits())
        assertEquals(-0.5f to 0.5f, hinge.limitsMin to hinge.limitsMax)
        assertEquals(Vec3(0f, 0f, 1f), hinge.localSpaceHingeAxis1)
        system.addConstraint(hinge)
        // Knocked sideways, the ball stays in the hinge's plane, and swings down as far as the limit lets it.
        bodies.addImpulse(ball, Vec3(0f, 0f, 50f))
        step(120)
        val position = bodies.getPosition(ball)
        assertEquals(0f, position.z, 1e-2f)
        assertEquals(1f, distance(anchor, ball), 1e-2f)
        assertEquals(-0.5f, hinge.currentAngle, 0.05f)

        // A motor turns it back up to an angle, against gravity, once its spring is stiff enough to carry the ball:
        // the constraint's own motor settings, not a copy of them.
        SpringSettings(ESpringMode.FrequencyAndDamping, 20f, 1f).use { hinge.motorSettings.springSettings = it }
        hinge.motorState = EMotorState.Position
        hinge.targetAngle = 0.25f
        assertEquals(EMotorState.Position, hinge.motorState)
        step(120)
        assertEquals(0.25f, hinge.currentAngle, 0.05f)
        assertTrue(hinge.totalLambdaMotor != 0f)
        // And one with no torque lets it down.
        hinge.motorSettings.setTorqueLimit(0f)
        step(120)
        assertEquals(-0.5f, hinge.currentAngle, 0.05f)
        system.removeConstraint(hinge)
        hinge.close()
    }

    @Test
    fun slider() = world {
        val (anchor, ball) = anchorAndBall()
        val slider = SliderConstraintSettings().use { settings ->
            settings.autoDetectPoint = true
            settings.setSliderAxis(Vec3(1f, 0f, 0f))
            assertEquals(Vec3(1f, 0f, 0f), settings.sliderAxis1)
            settings.limitsMin = -0.25f
            settings.limitsMax = 2f
            SliderConstraint(body(anchor), body(ball), settings)
        }
        assertEquals(EConstraintSubType.Slider, slider.subType)
        system.addConstraint(slider)
        // Along its axis alone, to the limit.
        bodies.addImpulse(ball, Vec3(1000f, 1000f, 1000f))
        step(120)
        assertNear(ballAt + Vec3(2f, 0f, 0f), bodies.getPosition(ball), 2e-2f)
        assertEquals(2f, slider.currentPosition, 2e-2f)

        slider.motorState = EMotorState.Velocity
        slider.targetVelocity = -1f
        step(30)
        assertEquals(-1f, bodies.getLinearVelocity(ball).x, 0.05f)
        system.removeConstraint(slider)
        slider.close()
    }

    @Test
    fun sixDOF() = world {
        val (anchor, ball) = anchorAndBall()
        val constraint = SixDOFConstraintSettings().use { settings ->
            settings.space = EConstraintSpace.WorldSpace
            settings.position1 = ballAt
            settings.position2 = ballAt
            SixDOFConstraintSettings.EAxis.entries.take(6).forEach { settings.makeFixedAxis(it) }
            settings.setLimitedAxis(SixDOFConstraintSettings.EAxis.TranslationY, -2f, 0f)
            assertTrue(settings.isFixedAxis(SixDOFConstraintSettings.EAxis.TranslationX))
            assertFalse(settings.isFixedAxis(SixDOFConstraintSettings.EAxis.TranslationY))
            // The limits of the six axes, as an array.
            assertEquals(-2f, settings.limitMin[SixDOFConstraintSettings.EAxis.TranslationY.value])
            settings.create(body(anchor), body(ball))!!
        }
        assertEquals(-2f, (constraint as SixDOFConstraint).getLimitsMin(SixDOFConstraintSettings.EAxis.TranslationY))
        system.addConstraint(constraint)
        bodies.addImpulse(ball, Vec3(50f, 0f, 50f))
        step(120)
        assertNear(ballAt - Vec3(0f, 2f, 0f), bodies.getPosition(ball), 2e-2f)
        system.removeConstraint(constraint)
        constraint.close()
    }

    /** Settings a constraint is made from are values Kotlin sets and reads back. */
    @Test
    fun springsAndMotors() = world {
        SpringSettings(ESpringMode.StiffnessAndDamping, 100f, 0.5f).use { spring ->
            assertEquals(ESpringMode.StiffnessAndDamping, spring.mode)
            assertEquals(100f to 0.5f, spring.stiffness to spring.damping)
            assertTrue(spring.hasStiffness())
            DistanceConstraintSettings().use { settings ->
                settings.limitsSpringSettings.use { assertFalse(it.hasStiffness()) }
                settings.limitsSpringSettings = spring
                settings.limitsSpringSettings.use { assertEquals(100f, it.stiffness) }
            }
        }
        MotorSettings(2f, 1f, 30f, 40f).use { motor ->
            assertTrue(motor.isValid)
            assertEquals(listOf(-30f, 30f, -40f, 40f), listOf(motor.minForceLimit, motor.maxForceLimit, motor.minTorqueLimit, motor.maxTorqueLimit))
            motor.springSettings.use { assertEquals(2f to 1f, it.frequency to it.damping) }
            motor.setForceLimits(1f, -1f)
            assertFalse(motor.isValid)
        }
    }
}
