@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.erkko68.kolt

import io.github.erkko68.kolt.capi.*
import io.github.erkko68.kolt.core.capi.*
import io.github.erkko68.kolt.physics.body.capi.*
import io.github.erkko68.kolt.physics.capi.*
import io.github.erkko68.kolt.physics.collision.broadphase.capi.*
import io.github.erkko68.kolt.physics.collision.capi.*
import io.github.erkko68.kolt.physics.collision.shape.capi.*
import io.github.erkko68.kolt.interop.NullPointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toLong
import kotlinx.cinterop.usePinned
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val DYNAMIC = 2 // JPH_EMOTION_TYPE_DYNAMIC
private const val ACTIVATE = 0 // JPH_EACTIVATION_ACTIVATE

// A JphVec3 or JphQuat: four floats.
private inline fun <R> FloatArray.ptr(block: (Long) -> R): R = usePinned { block(it.addressOf(0).toLong()) }

/** The externals reach Jolt: a sphere dropped from 10 m has fallen about g/2 after a second. */
class FallingSphereTest {
    @Test
    fun sphereFalls() {
        Jph_RegisterDefaultAllocator()
        val factory = JphFactory_create()
        JphFactory_SetInstance(factory)
        Jph_RegisterTypes()

        val temp = JphTempAllocatorImpl_create(10 * 1024 * 1024)
        val jobs = JphJobSystemThreadPool_create_uint_uint_int(2048, 8, 1)
        // One object layer in one broad phase layer, colliding with itself.
        val broadPhaseLayers = JphBroadPhaseLayerInterfaceTable_create(1, 1)
        JphBroadPhaseLayerInterfaceTable_MapObjectToBroadPhaseLayer(broadPhaseLayers, 0, 0)
        val layerPairs = JphObjectLayerPairFilterTable_create(1)
        JphObjectLayerPairFilterTable_EnableCollision(layerPairs, 0, 0)
        val broadPhaseLayerInterface = JphBroadPhaseLayerInterfaceTable_asBroadPhaseLayerInterface(broadPhaseLayers)
        val layerPairFilter = JphObjectLayerPairFilterTable_asObjectLayerPairFilter(layerPairs)
        val objectVsBroadPhase = JphObjectVsBroadPhaseLayerFilterTable_create(broadPhaseLayerInterface, 1, layerPairFilter, 1)

        val system = JphPhysicsSystem_create()
        JphPhysicsSystem_Init(
            system, 1024, 0, 1024, 1024, broadPhaseLayerInterface,
            JphObjectVsBroadPhaseLayerFilterTable_asObjectVsBroadPhaseLayerFilter(objectVsBroadPhase), layerPairFilter,
        )
        val bodies = JphPhysicsSystem_GetBodyInterface(system)

        val shape = JphSphereShape_asShape(JphSphereShape_create_float_PhysicsMaterial(0.5f, NullPointer))
        val settings = floatArrayOf(0f, 10f, 0f, 0f).ptr { position ->
            floatArrayOf(0f, 0f, 0f, 1f).ptr { rotation ->
                JphBodyCreationSettings_create_Shape_RVec3Arg_QuatArg_EMotionType_ObjectLayer(shape, position, rotation, DYNAMIC, 0)
            }
        }
        val body = JphBodyInterface_CreateAndAddBody(bodies, settings, ACTIVATE)
        JphBodyCreationSettings_destroy(settings)

        repeat(60) {
            assertEquals(0, JphPhysicsSystem_Update(system, 1f / 60, 1, JphTempAllocatorImpl_asTempAllocator(temp), JphJobSystemThreadPool_asJobSystem(jobs)))
        }
        val position = FloatArray(4)
        position.ptr { JphBodyInterface_GetPosition(bodies, body, it) }
        assertEquals(0f, position[0])
        assertTrue(position[1] in 4.5f..5.5f, "y = ${position[1]}")

        JphBodyInterface_RemoveBody(bodies, body)
        JphBodyInterface_DestroyBody(bodies, body)
        JphPhysicsSystem_destroy(system)
        JphObjectVsBroadPhaseLayerFilterTable_destroy(objectVsBroadPhase)
        JphObjectLayerPairFilterTable_destroy(layerPairs)
        JphBroadPhaseLayerInterfaceTable_destroy(broadPhaseLayers)
        JphJobSystemThreadPool_destroy(jobs)
        JphTempAllocatorImpl_destroy(temp)
        Jph_UnregisterTypes()
        JphFactory_SetInstance(NullPointer)
        JphFactory_destroy(factory)
    }
}
