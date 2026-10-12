package io.github.erkko68.kolt

import io.github.erkko68.kolt.core.JobSystemThreadPool
import io.github.erkko68.kolt.core.StreamOut
import io.github.erkko68.kolt.core.TempAllocatorImpl
import io.github.erkko68.kolt.interop.NativePointer
import io.github.erkko68.kolt.math.Quat
import io.github.erkko68.kolt.math.Vec3
import io.github.erkko68.kolt.physics.EActivation
import io.github.erkko68.kolt.physics.EStateRecorderState
import io.github.erkko68.kolt.physics.PhysicsStepListener
import io.github.erkko68.kolt.physics.PhysicsStepListenerContext
import io.github.erkko68.kolt.physics.PhysicsSystem
import io.github.erkko68.kolt.physics.StateRecorderImpl
import io.github.erkko68.kolt.physics.body.BodyCreationSettings
import io.github.erkko68.kolt.physics.body.EMotionType
import io.github.erkko68.kolt.physics.collision.ObjectLayerPairFilterTable
import io.github.erkko68.kolt.physics.collision.broadphase.BroadPhaseLayer
import io.github.erkko68.kolt.physics.collision.broadphase.BroadPhaseLayerInterfaceTable
import io.github.erkko68.kolt.physics.collision.broadphase.ObjectVsBroadPhaseLayerFilterTable
import io.github.erkko68.kolt.physics.collision.shape.SphereShape
import io.github.erkko68.kolt.skeleton.Skeleton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A step listener written in Kotlin runs each step; a recorder takes the simulation back to a state it saved; a stream
 * written in Kotlin gets a shape's bytes; and a skeleton is built joint by joint.
 */
class StateTest {
    @Test
    fun stepsStatesAndStreams() {
        Jolt.init()
        val temp = TempAllocatorImpl(10 * 1024 * 1024)
        val jobs = JobSystemThreadPool(2048, 8, 2)
        val broadPhaseLayers = BroadPhaseLayerInterfaceTable(1, 1)
        broadPhaseLayers.mapObjectToBroadPhaseLayer(0, BroadPhaseLayer(0))
        val layerPairs = ObjectLayerPairFilterTable(1)
        layerPairs.enableCollision(0, 0)
        val objectVsBroadPhase = ObjectVsBroadPhaseLayerFilterTable(broadPhaseLayers, 1, layerPairs, 1)
        val system = PhysicsSystem()
        system.init(1024, 0, 1024, 1024, broadPhaseLayers, objectVsBroadPhase, layerPairs)
        val bodies = system.bodyInterface

        var steps = 0
        val listener = object : PhysicsStepListener() {
            override fun onStep(context: PhysicsStepListenerContext) {
                steps++
                assertEquals(1f / 60, context.deltaTime)
            }
        }
        system.addStepListener(listener)

        val sphere = SphereShape(0.5f, null)
        val ball = BodyCreationSettings(sphere, Vec3(0f, 10f, 0f), Quat.IDENTITY, EMotionType.Dynamic, 0).use { bodies.createAndAddBody(it, EActivation.Activate) }
        val recorder = StateRecorderImpl()
        system.saveState(recorder, EStateRecorderState.All, null)
        assertTrue(recorder.dataSize > 0)

        repeat(30) { system.update(1f / 60, 1, temp, jobs) }
        assertEquals(30, steps)
        assertTrue(bodies.getPosition(ball).y < 9.5f)
        recorder.rewind()
        assertTrue(system.restoreState(recorder, null))
        assertEquals(10f, bodies.getPosition(ball).y)

        var written = 0
        val stream = object : StreamOut() {
            override fun writeBytes(data: NativePointer, numBytes: Int) { written += numBytes }
            override val isFailed get() = false
        }
        sphere.saveBinaryState(stream)
        assertTrue(written > 0)

        // A result by value is a copy Kotlin owns.
        sphere.stats.use { assertTrue(it.sizeBytes > 0) }
        system.getDefaultLayerFilter(0).use { assertTrue(it.shouldCollide(0)) }

        val skeleton = Skeleton()
        assertEquals(0, skeleton.addJoint("root", -1))
        assertEquals(1, skeleton.addJoint("spine", "root"))
        skeleton.calculateParentJointIndices()
        assertEquals(1, skeleton.getJointIndex("spine"))
        assertTrue(skeleton.areJointsCorrectlyOrdered())

        system.removeStepListener(listener)
        bodies.removeBody(ball)
        bodies.destroyBody(ball)
        listOf(skeleton, stream, recorder, sphere, listener, system, objectVsBroadPhase, layerPairs, broadPhaseLayers, jobs, temp).forEach { it.close() }
    }
}
