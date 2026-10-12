package io.github.erkko68.kolt.core

import io.github.erkko68.kolt.Jolt
import io.github.erkko68.kolt.jolt
import io.github.erkko68.kolt.math.Vec3
import io.github.erkko68.kolt.physics.EStateRecorderState
import io.github.erkko68.kolt.physics.StateRecorderImpl
import io.github.erkko68.kolt.physics.body.MassProperties
import io.github.erkko68.kolt.physics.collision.shape.SphereShape
import io.github.erkko68.kolt.physics.constraints.ESpringMode
import io.github.erkko68.kolt.physics.constraints.SpringSettings
import io.github.erkko68.kolt.verifyJoltVersionID
import io.github.erkko68.kolt.world
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoreTest {
    @Test
    fun initAndShutdown() {
        assertFailsWith<IllegalStateException> { Jolt.shutdown() }
        jolt {
            assertTrue(verifyJoltVersionID())
            assertFailsWith<IllegalStateException> { Jolt.init() }
        }
        // And again, after a shutdown.
        jolt { SphereShape(1f, null).use { assertEquals(1f, it.radius) } }
    }

    @Test
    fun linearCurve() = jolt {
        LinearCurve().use { curve ->
            curve.addPoint(2f, 20f)
            curve.addPoint(0f, 0f)
            curve.addPoint(1f, 5f)
            curve.sort()
            assertEquals(listOf(0f to 0f, 1f to 5f, 2f to 20f), curve.points.map { point -> point.use { it.x to it.y } })
            assertEquals(0f to 2f, curve.minX to curve.maxX)
            // Between the points, and held at the ends.
            assertEquals(2.5f, curve.getValue(0.5f))
            assertEquals(12.5f, curve.getValue(1.5f))
            assertEquals(20f, curve.getValue(3f))
            curve.clear()
            assertEquals(0, curve.points.size)
        }
    }

    @Test
    fun color() = jolt {
        Color(255, 128, 0, 255).use { color ->
            assertEquals(listOf(255, 128, 0, 255), listOf(color.r, color.g, color.b, color.a))
            // Unsigned in C++: the bits, alpha highest.
            assertEquals(0xFF0080FF.toInt(), color.uInt32)
            assertEquals(128 / 255f, color.toVec4().y, 1e-6f)
            Color(color, 0).use { assertEquals(0x000080FF, it.uInt32) }
            color.g = 0
            assertEquals(0xFF0000FF.toInt(), color.u32)
        }
    }

    /** Every allocator Jolt has steps the same simulation. */
    @Test
    fun tempAllocators() = world {
        SphereShape(0.5f, null).use { add(it, Vec3(0f, 10f, 0f)) }
        listOf(TempAllocatorMalloc(), TempAllocatorImplWithMallocFallback(1024), TempAllocatorImpl(1024 * 1024)).forEach { allocator ->
            repeat(10) { system.update(1f / 60, 1, allocator, jobs) }
            allocator.close()
        }
        assertTrue(system.bodies.all { bodies.getPosition(it).y < 9.9f })
    }

    /** What Jolt writes to a stream it reads back: through a recorder, which is both. */
    @Test
    fun binaryState() = world {
        val recorder = StateRecorderImpl()
        val out = recorder.asStreamOut()
        MassProperties().use { saved ->
            saved.setMassAndInertiaOfSolidBox(Vec3(1f, 2f, 3f), 1000f)
            assertEquals(6000f, saved.mass)
            saved.saveBinaryState(out)
            SpringSettings(ESpringMode.StiffnessAndDamping, 100f, 0.5f).use { it.saveBinaryState(out) }
            assertFalse(recorder.isFailed)
            assertTrue(recorder.dataSize > 0)

            MassProperties().use { restored ->
                restored.restoreBinaryState(recorder)
                assertEquals(6000f, restored.mass)
                assertEquals(saved.inertia, restored.inertia)
            }
            SpringSettings().use { restored ->
                restored.restoreBinaryState(recorder)
                assertEquals(ESpringMode.StiffnessAndDamping to 100f, restored.mode to restored.stiffness)
            }
            assertFalse(recorder.isEOF)
            // Nothing is left to read.
            MassProperties().use { it.restoreBinaryState(recorder) }
            assertTrue(recorder.isEOF || recorder.isFailed)
        }
        recorder.close()

        // Two recordings of a simulation are equal until it moves on.
        val ball = SphereShape(0.5f, null).use { add(it, Vec3(0f, 10f, 0f)) }
        val recordings = List(3) { index ->
            if (index == 2) step(1)
            StateRecorderImpl().also { system.saveState(it, EStateRecorderState.All, null) }
        }
        assertTrue(recordings[0].isEqual(recordings[1]))
        assertFalse(recordings[0].isEqual(recordings[2]))
        recordings[0].rewind()
        assertTrue(system.restoreState(recordings[0], null))
        assertEquals(Vec3(0f, 10f, 0f), bodies.getPosition(ball))
        recordings.forEach { it.close() }
    }
}
