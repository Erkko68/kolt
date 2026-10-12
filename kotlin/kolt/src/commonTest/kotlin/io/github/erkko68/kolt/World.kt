package io.github.erkko68.kolt

import io.github.erkko68.kolt.core.JobSystemThreadPool
import io.github.erkko68.kolt.core.TempAllocatorImpl
import io.github.erkko68.kolt.math.Quat
import io.github.erkko68.kolt.math.Vec3
import io.github.erkko68.kolt.physics.EActivation
import io.github.erkko68.kolt.physics.EPhysicsUpdateError
import io.github.erkko68.kolt.physics.PhysicsSystem
import io.github.erkko68.kolt.physics.body.Body
import io.github.erkko68.kolt.physics.body.BodyCreationSettings
import io.github.erkko68.kolt.physics.body.BodyID
import io.github.erkko68.kolt.physics.body.EMotionType
import io.github.erkko68.kolt.physics.collision.ObjectLayerPairFilterTable
import io.github.erkko68.kolt.physics.collision.broadphase.BroadPhaseLayer
import io.github.erkko68.kolt.physics.collision.broadphase.BroadPhaseLayerInterfaceTable
import io.github.erkko68.kolt.physics.collision.broadphase.ObjectVsBroadPhaseLayerFilterTable
import io.github.erkko68.kolt.physics.collision.shape.BoxShape
import io.github.erkko68.kolt.physics.collision.shape.Shape
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What a simulation needs around it: a system of one object layer, in one broad phase layer, that collides with itself. */
class World {
    val temp = TempAllocatorImpl(10 * 1024 * 1024)
    val jobs = JobSystemThreadPool(2048, 8, 1)
    private val broadPhaseLayers = BroadPhaseLayerInterfaceTable(1, 1).also { it.mapObjectToBroadPhaseLayer(0, BroadPhaseLayer(0)) }
    private val layerPairs = ObjectLayerPairFilterTable(1).also { it.enableCollision(0, 0) }
    private val objectVsBroadPhase = ObjectVsBroadPhaseLayerFilterTable(broadPhaseLayers, 1, layerPairs, 1)
    val system = PhysicsSystem().also { it.init(1024, 0, 1024, 1024, broadPhaseLayers, objectVsBroadPhase, layerPairs) }
    val bodies = system.bodyInterface

    /** Adds a body of [shape], dynamic and awake unless [motionType] says otherwise. */
    fun add(shape: Shape, position: Vec3, motionType: EMotionType = EMotionType.Dynamic, configure: BodyCreationSettings.() -> Unit = {}): BodyID =
        BodyCreationSettings(shape, position, Quat.IDENTITY, motionType, 0).use { settings ->
            settings.configure()
            bodies.createAndAddBody(settings, if (motionType == EMotionType.Static) EActivation.DontActivate else EActivation.Activate)
        }

    /** A static box whose top is the plane y = 0. The body keeps the shape. */
    fun addFloor(): BodyID = BoxShape(Vec3(50f, 1f, 50f), 0.05f, null).use { add(it, Vec3(0f, -1f, 0f), EMotionType.Static) }

    /** The body itself, as Jolt has it; what [bodies] doesn't reach by ID. */
    fun body(id: BodyID): Body = system.bodyLockInterfaceNoLock.tryGetBody(id)!!

    fun step(steps: Int, deltaTime: Float = 1f / 60) = repeat(steps) { assertEquals(EPhysicsUpdateError.None, system.update(deltaTime, 1, temp, jobs)) }

    // The system destroys the bodies and constraints it still has.
    internal fun close() = listOf(system, objectVsBroadPhase, layerPairs, broadPhaseLayers, jobs, temp).forEach { it.close() }
}

/** Runs [block] in a [World], between `Jolt.init()` and `Jolt.shutdown()` whether it fails or not. */
fun world(block: World.() -> Unit) = jolt {
    val world = World()
    try {
        world.block()
    } finally {
        world.close()
    }
}

/** Runs [block] between `Jolt.init()` and `Jolt.shutdown()`, so that a failed test doesn't fail the ones after it. */
fun jolt(block: () -> Unit) {
    Jolt.init()
    try {
        block()
    } finally {
        Jolt.shutdown()
    }
}

fun assertNear(expected: Vec3, actual: Vec3, tolerance: Float = 1e-4f) =
    assertTrue((expected - actual).length <= tolerance, "expected $expected, got $actual")

/** As rotations: q and -q are the same one. */
fun assertNear(expected: Quat, actual: Quat, tolerance: Float = 1e-4f) =
    assertTrue(1 - abs(expected.x * actual.x + expected.y * actual.y + expected.z * actual.z + expected.w * actual.w) <= tolerance, "expected $expected, got $actual")
