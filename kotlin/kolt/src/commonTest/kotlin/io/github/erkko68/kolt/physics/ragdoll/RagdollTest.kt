package io.github.erkko68.kolt.physics.ragdoll

import io.github.erkko68.kolt.math.Vec3
import io.github.erkko68.kolt.physics.EActivation
import io.github.erkko68.kolt.physics.body.EMotionType
import io.github.erkko68.kolt.physics.collision.shape.SphereShape
import io.github.erkko68.kolt.physics.constraints.EConstraintSpace
import io.github.erkko68.kolt.physics.constraints.PointConstraint
import io.github.erkko68.kolt.physics.constraints.PointConstraintSettings
import io.github.erkko68.kolt.skeleton.Skeleton
import io.github.erkko68.kolt.skeleton.SkeletonPose
import io.github.erkko68.kolt.world
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RagdollTest {
    /** Two balls, the lower hung from the upper by a joint: a skeleton's bodies and constraints, added and removed as one. */
    @Test
    fun twoJoints() = world {
        addFloor()
        val skeleton = Skeleton()
        skeleton.addJoint("upper", -1)
        skeleton.addJoint("lower", "upper")
        skeleton.calculateParentJointIndices()
        assertEquals(listOf("upper" to -1, "lower" to 0), skeleton.joints.map { it.name to it.parentJointIndex })

        val sphere = SphereShape(0.25f, null)
        val settings = RagdollSettings()
        settings.skeleton = skeleton
        val joint = PointConstraintSettings().also {
            it.space = EConstraintSpace.WorldSpace
            it.point1 = Vec3(0f, 5f, 0f)
            it.point2 = Vec3(0f, 5f, 0f)
        }
        val parts = listOf(Vec3(0f, 5f, 0f), Vec3(1f, 5f, 0f)).mapIndexed { index, at ->
            RagdollSettings.Part().also {
                it.shape = sphere
                it.position = at
                // The upper one stays where it is.
                it.motionType = if (index == 0) EMotionType.Static else EMotionType.Dynamic
                it.objectLayer = 0
                if (index == 1) it.toParent = joint
            }
        }
        settings.parts = parts
        parts.forEach { it.close() }
        joint.close()
        assertEquals(listOf(Vec3(0f, 5f, 0f), Vec3(1f, 5f, 0f)), settings.parts.map { part -> part.use { it.position } })
        settings.calculateBodyIndexToConstraintIndex()
        assertEquals(listOf(-1, 0), settings.bodyIndexToConstraintIndex.toList())
        assertEquals(0, settings.getConstraintIndexForBodyIndex(1))

        val ragdoll = settings.createRagdoll(0, 11L, system)!!
        assertEquals(2 to 1, ragdoll.bodyCount to ragdoll.constraintCount)
        assertTrue(ragdoll.getConstraint(0) is PointConstraint)
        assertEquals(0, system.numBodies - 3)
        ragdoll.addToPhysicsSystem(EActivation.Activate, true)
        val (upper, lower) = ragdoll.bodyIDs
        assertEquals(upper, ragdoll.getBodyID(0))
        assertEquals(11L, bodies.getUserData(lower))
        assertTrue(bodies.isAdded(upper) && bodies.isAdded(lower))
        assertTrue(ragdoll.isActive(true))
        assertEquals(1, system.constraints.size)

        // The lower swings down around the upper.
        step(45)
        assertEquals(Vec3(0f, 5f, 0f), ragdoll.getRootTransform(true).first)
        val swung = bodies.getPosition(lower)
        assertTrue(swung.y < 4.5f, "$swung")
        assertEquals(1f, (swung - bodies.getPosition(upper)).length, 2e-2f)
        ragdoll.getWorldSpaceBounds(true).use { assertEquals(5.25f, it.max.y, 1e-3f) }

        ragdoll.removeFromPhysicsSystem(true)
        assertFalse(bodies.isAdded(lower))
        assertEquals(0, system.constraints.size)
        ragdoll.close(); settings.close(); sphere.close(); skeleton.close()
    }

    @Test
    fun skeletonPose() = world {
        val skeleton = Skeleton()
        skeleton.addJoint("root", -1)
        skeleton.addJoint("child", 0)
        SkeletonPose().use { pose ->
            pose.skeleton = skeleton
            assertEquals(2, pose.jointCount)
            pose.rootOffset = Vec3(1f, 2f, 3f)
            assertEquals(Vec3(1f, 2f, 3f), pose.rootOffset)
            assertEquals(2, pose.jointMatrices.size)
        }
        skeleton.close()
    }
}
