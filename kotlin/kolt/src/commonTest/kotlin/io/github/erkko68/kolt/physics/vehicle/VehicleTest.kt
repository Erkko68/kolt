package io.github.erkko68.kolt.physics.vehicle

import io.github.erkko68.kolt.math.Vec3
import io.github.erkko68.kolt.physics.collision.shape.BoxShape
import io.github.erkko68.kolt.physics.constraints.EConstraintSubType
import io.github.erkko68.kolt.world
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A car of a box on four wheels, dropped on the floor, comes to rest on its suspension, and drives off. */
class VehicleTest {
    @Test
    fun wheeledVehicle() = world {
        val floor = addFloor()
        val chassis = BoxShape(Vec3(1f, 0.5f, 2f), 0.05f, null).use { add(it, Vec3(0f, 2f, 0f)) { allowSleeping = false } }
        val wheelSettings = listOf(-1f, 1f).flatMap { x -> listOf(1.5f, -1.5f).map { z -> Vec3(x, -0.5f, z) } }.map { at ->
            WheelSettingsWV().also {
                it.position = at
                it.radius = 0.3f
                it.suspensionMinLength = 0.3f
                it.suspensionMaxLength = 0.5f
                it.maxSteerAngle = if (at.z > 0) 0.5f else 0f
            }
        }
        // The engine turns the front wheels: the first of each side.
        val controllerSettings = WheeledVehicleControllerSettings()
        VehicleDifferentialSettings().use {
            it.leftWheel = 0
            it.rightWheel = 2
            controllerSettings.differentials = listOf(it)
        }
        val vehicle = VehicleConstraintSettings().use { settings ->
            settings.wheels = wheelSettings
            settings.controller = controllerSettings
            assertEquals(Vec3(0f, 1f, 0f) to Vec3(0f, 0f, 1f), settings.up to settings.forward)
            assertEquals(wheelSettings.map { it.position }, settings.wheels.map { it.position })
            VehicleConstraint(body(chassis), settings)
        }
        assertEquals(EConstraintSubType.Vehicle, vehicle.subType)
        assertEquals(chassis, vehicle.vehicleBody!!.id)
        // The controller the settings asked for: what Jolt returns as a base is the class it is.
        val controller = vehicle.controller as WheeledVehicleController
        assertTrue(vehicle.controller !is MotorcycleController)
        assertTrue(controller.settings is WheeledVehicleControllerSettings)
        val tester = VehicleCollisionTesterRay(0, Vec3(0f, 1f, 0f), (PI / 4).toFloat())
        vehicle.vehicleCollisionTester = tester
        // The vehicle is a constraint, and steps its wheels as a listener: its second C++ base.
        system.addConstraint(vehicle)
        val listener = vehicle.asPhysicsStepListener()
        system.addStepListener(listener)
        var preSteps = 0
        var postSteps = 0
        vehicle.setPreStepCallback { stepped, context ->
            assertEquals(chassis, stepped.vehicleBody!!.id)
            assertEquals(1f / 60, context.deltaTime, 1e-6f)
            preSteps++
        }
        vehicle.setPostStepCallback { _, _ -> postSteps++ }

        step(180)
        assertEquals(180 to 180, preSteps to postSteps)
        val wheels = vehicle.wheels
        assertEquals(4, wheels.size)
        wheels.forEachIndexed { index, wheel ->
            assertTrue(wheel.hasContact(), "wheel $index")
            assertEquals(floor, wheel.contactBodyID)
            assertEquals(0f, wheel.contactPosition.y, 0.02f)
            assertTrue(wheel.suspensionLength in 0.3f..0.5f, "suspension ${wheel.suspensionLength}")
            assertTrue(wheel.suspensionLambda > 0f)
            assertEquals(0.3f, wheel.settings!!.radius)
            assertTrue(wheel is WheelWV && wheel.settings is WheelSettingsWV)
        }
        // The chassis' center: above the floor by a wheel, its suspension, and half its own height.
        val height = bodies.getPosition(chassis).y
        assertEquals(0.3f + wheels[0].suspensionLength + 0.5f, height, 0.02f)
        assertEquals(0f, bodies.getLinearVelocity(chassis).length, 0.02f)

        // Full throttle: forward, which is along z.
        controller.setDriverInput(1f, 0f, 0f, 0f)
        assertEquals(1f, controller.forwardInput)
        step(120)
        assertTrue(controller.engine.currentRPM > 1000f, "rpm ${controller.engine.currentRPM}")
        assertTrue(bodies.getLinearVelocity(chassis).z > 2f, "velocity ${bodies.getLinearVelocity(chassis)}")
        assertTrue(bodies.getPosition(chassis).z > 2f)
        assertEquals(0f, bodies.getPosition(chassis).x, 0.1f)
        assertTrue(vehicle.wheels[0].angularVelocity > 1f)

        // Without the callbacks, and with gravity of its own, upwards: off the floor.
        vehicle.setPreStepCallback(null)
        vehicle.setPostStepCallback(null)
        assertFalse(vehicle.isGravityOverridden)
        vehicle.overrideGravity(Vec3(0f, 9.81f, 0f))
        assertEquals(Vec3(0f, 9.81f, 0f), vehicle.gravityOverride)
        step(60)
        assertEquals(300, preSteps)
        assertTrue(bodies.getPosition(chassis).y > height + 1f)
        assertFalse(vehicle.wheels.any { it.hasContact() })

        system.removeStepListener(listener)
        system.removeConstraint(vehicle)
        vehicle.close(); tester.close(); controllerSettings.close()
        wheelSettings.forEach { it.close() }
    }

    /** The parts a vehicle is set up from. */
    @Test
    fun engineAndDifferential() = world {
        VehicleEngineSettings().use { engine ->
            engine.maxTorque = 400f
            engine.maxRPM = 7000f
            assertEquals(400f to 7000f, engine.maxTorque to engine.maxRPM)
            // Its torque over the RPM range, as a curve.
            engine.normalizedTorque.use { curve ->
                assertEquals(0f to 1f, curve.minX to curve.maxX)
                assertTrue(curve.getValue(0.5f) in 0.5f..1f)
            }
        }
        VehicleDifferentialSettings().use { differential ->
            differential.leftWheel = 0
            differential.rightWheel = 1
            // An open differential, both wheels turning alike: half the torque each.
            assertEquals(0.5f to 0.5f, differential.calculateTorqueRatio(10f, 10f))
        }
        WheeledVehicleControllerSettings().use { settings ->
            assertEquals(0, settings.differentials.size)
            val both = listOf(VehicleDifferentialSettings().also { it.leftWheel = 0; it.rightWheel = 1 }, VehicleDifferentialSettings().also { it.leftWheel = 2; it.rightWheel = 3 })
            settings.differentials = both
            assertEquals(listOf(0 to 1, 2 to 3), settings.differentials.map { d -> d.use { it.leftWheel to it.rightWheel } })
            both.forEach { it.close() }
        }
    }
}
