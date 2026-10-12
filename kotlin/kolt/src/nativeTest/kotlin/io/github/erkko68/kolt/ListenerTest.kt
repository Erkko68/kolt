package io.github.erkko68.kolt

import io.github.erkko68.kolt.core.JobSystemThreadPool
import io.github.erkko68.kolt.core.TempAllocatorImpl
import io.github.erkko68.kolt.math.Quat
import io.github.erkko68.kolt.math.Vec3
import io.github.erkko68.kolt.physics.EActivation
import io.github.erkko68.kolt.physics.PhysicsSystem
import io.github.erkko68.kolt.physics.body.Body
import io.github.erkko68.kolt.physics.body.BodyActivationListener
import io.github.erkko68.kolt.physics.body.BodyCreationSettings
import io.github.erkko68.kolt.physics.body.BodyFilter
import io.github.erkko68.kolt.physics.body.BodyID
import io.github.erkko68.kolt.physics.body.EMotionType
import io.github.erkko68.kolt.physics.collision.CastRayCollector
import io.github.erkko68.kolt.physics.collision.ContactListener
import io.github.erkko68.kolt.physics.collision.ContactManifold
import io.github.erkko68.kolt.physics.collision.ContactSettings
import io.github.erkko68.kolt.physics.collision.ObjectLayerFilter
import io.github.erkko68.kolt.physics.collision.ObjectLayerPairFilterTable
import io.github.erkko68.kolt.physics.collision.RRayCast
import io.github.erkko68.kolt.physics.collision.RayCastResult
import io.github.erkko68.kolt.physics.collision.RayCastSettings
import io.github.erkko68.kolt.physics.collision.ShapeFilter
import io.github.erkko68.kolt.physics.collision.broadphase.BroadPhaseLayer
import io.github.erkko68.kolt.physics.collision.broadphase.BroadPhaseLayerFilter
import io.github.erkko68.kolt.physics.collision.broadphase.BroadPhaseLayerInterfaceTable
import io.github.erkko68.kolt.physics.collision.broadphase.ObjectVsBroadPhaseLayerFilterTable
import io.github.erkko68.kolt.physics.collision.shape.BoxShape
import io.github.erkko68.kolt.physics.collision.shape.SphereShape
import kotlin.concurrent.AtomicInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Jolt calls the functions a Kotlin class overrides, from its job threads too, and its own for the rest: a sphere
 * landing on a floor reports the contact, and a filter decides what a ray hits.
 */
class ListenerTest {
    @Test
    fun contactsAndFilters() {
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

        val contacts = ArrayList<Pair<BodyID, BodyID>>()
        val contactListener = object : ContactListener() {
            // onContactValidate, onContactPersisted and onContactRemoved stay Jolt's.
            override fun onContactAdded(body1: Body, body2: Body, manifold: ContactManifold, settings: ContactSettings) {
                contacts += body1.id to body2.id
                assertTrue(manifold.penetrationDepth > -1f)
            }
        }
        system.contactListener = contactListener
        val activated = ArrayList<BodyID>()
        val activationListener = object : BodyActivationListener() {
            override fun onBodyActivated(bodyID: BodyID, bodyUserData: Long) {
                activated += bodyID
                assertEquals(7L, bodyUserData)
            }

            override fun onBodyDeactivated(bodyID: BodyID, bodyUserData: Long) {}
        }
        system.bodyActivationListener = activationListener

        val box = BoxShape(Vec3(10f, 1f, 10f), 0.05f, null)
        val floor = BodyCreationSettings(box, Vec3(0f, -1f, 0f), Quat.IDENTITY, EMotionType.Static, 0).use { bodies.createAndAddBody(it, EActivation.DontActivate) }
        val sphere = SphereShape(0.5f, null)
        val ball = BodyCreationSettings(sphere, Vec3(0f, 2f, 0f), Quat.IDENTITY, EMotionType.Dynamic, 0).use { settings ->
            settings.userData = 7L
            bodies.createAndAddBody(settings, EActivation.Activate)
        }
        assertEquals(listOf(ball), activated)

        // A Kotlin function in place of Jolt's own collision of two bodies, which it passes on to.
        val collided = AtomicInt(0)
        system.setSimCollideBodyVsBody { body1, body2, transform1, transform2, settings, collector, filter ->
            collided.incrementAndGet()
            PhysicsSystem.defaultSimCollideBodyVsBody(body1, body2, transform1, transform2, settings, collector, filter)
        }

        repeat(120) { system.update(1f / 60, 1, temp, jobs) }
        assertTrue(collided.value > 0)
        assertEquals(listOf(floor to ball), contacts.map { (a, b) -> if (a == floor) a to b else b to a })
        assertEquals(0.5f, bodies.getPosition(ball).y, 0.05f)

        // A ray from above hits the ball first, and the floor once a filter leaves the ball out.
        val broadPhase = BroadPhaseLayerFilter()
        val objects = ObjectLayerFilter()
        val notTheBall = object : BodyFilter() {
            override fun shouldCollide(bodyID: BodyID) = bodyID != ball
        }
        RayCastResult().use { hit ->
            RRayCast(Vec3(0f, 5f, 0f), Vec3(0f, -10f, 0f)).use { ray ->
                BodyFilter().use { all -> assertTrue(system.narrowPhaseQuery.castRay(ray, hit, broadPhase, objects, all)) }
                assertEquals(ball, hit.bodyID)
            }
        }
        RayCastResult().use { hit ->
            RRayCast(Vec3(0f, 5f, 0f), Vec3(0f, -10f, 0f)).use { ray ->
                assertTrue(system.narrowPhaseQuery.castRay(ray, hit, broadPhase, objects, notTheBall))
                assertEquals(floor, hit.bodyID)
            }
        }
        // A collector written in Kotlin gets every hit of the ray: the ball, then the floor below it.
        val hits = ArrayList<Pair<BodyID, Float>>()
        val collector = object : CastRayCollector() {
            override fun addHit(result: RayCastResult) {
                hits += result.bodyID to result.fraction
            }
        }
        RRayCast(Vec3(0f, 5f, 0f), Vec3(0f, -10f, 0f)).use { ray ->
            RayCastSettings().use { settings ->
                ShapeFilter().use { shapes -> BodyFilter().use { all -> system.narrowPhaseQuery.castRay(ray, settings, collector, broadPhase, objects, all, shapes) } }
            }
        }
        assertEquals(listOf(ball, floor), hits.sortedBy { it.second }.map { it.first })
        collector.close()
        assertFalse(notTheBall.shouldCollide(ball))
        // A plain filter's function is Jolt's.
        BodyFilter().use { assertTrue(it.shouldCollide(ball)) }

        notTheBall.close(); objects.close(); broadPhase.close()
        listOf(ball, floor).forEach { bodies.removeBody(it); bodies.destroyBody(it) }
        sphere.close(); box.close()
        system.close()
        activationListener.close(); contactListener.close()
        objectVsBroadPhase.close(); layerPairs.close(); broadPhaseLayers.close()
        jobs.close(); temp.close()
        Jolt.shutdown()
    }
}
