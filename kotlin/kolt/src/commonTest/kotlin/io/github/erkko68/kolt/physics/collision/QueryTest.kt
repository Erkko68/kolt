package io.github.erkko68.kolt.physics.collision

import io.github.erkko68.kolt.World
import io.github.erkko68.kolt.assertNear
import io.github.erkko68.kolt.geometry.AABox
import io.github.erkko68.kolt.geometry.OrientedBox
import io.github.erkko68.kolt.math.Mat44
import io.github.erkko68.kolt.math.Vec3
import io.github.erkko68.kolt.physics.body.BodyFilter
import io.github.erkko68.kolt.physics.body.BodyID
import io.github.erkko68.kolt.physics.body.EMotionType
import io.github.erkko68.kolt.physics.body.IgnoreMultipleBodiesFilter
import io.github.erkko68.kolt.physics.body.IgnoreSingleBodyFilter
import io.github.erkko68.kolt.physics.collision.broadphase.BroadPhaseLayer
import io.github.erkko68.kolt.physics.collision.broadphase.BroadPhaseLayerFilter
import io.github.erkko68.kolt.physics.collision.broadphase.SpecifiedBroadPhaseLayerFilter
import io.github.erkko68.kolt.physics.collision.shape.BoxShape
import io.github.erkko68.kolt.physics.collision.shape.SphereShape
import io.github.erkko68.kolt.world
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What's where: a sphere of radius ½ at the origin and a box of half extent 1 five meters along x, asked about through
 * the broad phase, which knows bodies' bounds, and the narrow phase, which knows their shapes.
 */
class QueryTest {
    private class Scene(val world: World, val sphere: BodyID, val box: BodyID) {
        val broadPhase = BroadPhaseLayerFilter()
        val objects = ObjectLayerFilter()
        val bodies = BodyFilter()
        val shapes = ShapeFilter()
        val narrow get() = world.system.narrowPhaseQuery
        val broad get() = world.system.broadPhaseQuery

        /** The fraction along a ray, from five meters before the sphere to five past it, where it first hits. */
        fun castRay(bodyFilter: BodyFilter = bodies, objectLayerFilter: ObjectLayerFilter = objects, broadPhaseLayerFilter: BroadPhaseLayerFilter = broadPhase): Pair<BodyID, Float>? =
            RRayCast(Vec3(-5f, 0f, 0f), Vec3(10f, 0f, 0f)).use { ray ->
                RayCastResult().use { hit -> if (narrow.castRay(ray, hit, broadPhaseLayerFilter, objectLayerFilter, bodyFilter)) hit.bodyID to hit.fraction else null }
            }

        fun close() = listOf(shapes, bodies, objects, broadPhase).forEach { it.close() }
    }

    private fun scene(block: Scene.() -> Unit) = world {
        val sphere = SphereShape(0.5f, null).use { add(it, Vec3.ZERO, EMotionType.Static) }
        val box = BoxShape(Vec3(1f, 1f, 1f), 0.05f, null).use { add(it, Vec3(5f, 0f, 0f), EMotionType.Static) }
        val scene = Scene(this, sphere, box)
        try {
            scene.block()
        } finally {
            scene.close()
        }
    }

    private fun translation(by: Vec3) = Mat44.of(Mat44.IDENTITY.toArray().also { it[12] = by.x; it[13] = by.y; it[14] = by.z })

    /** The bodies a broad phase query reports, through a collector written in Kotlin. */
    private fun bodiesOf(query: (CollideShapeBodyCollector) -> Unit): Set<BodyID> {
        val found = HashSet<BodyID>()
        val collector = object : CollideShapeBodyCollector() {
            override fun addHit(result: BodyID) {
                found += result
            }
        }
        query(collector)
        collector.close()
        return found
    }

    @Test
    fun rayFilters() = scene {
        val first = castRay()!!
        assertEquals(sphere, first.first)
        assertEquals(0.45f, first.second, 1e-4f)
        // Past the sphere to the box, when the sphere is left out; and past both.
        IgnoreSingleBodyFilter(sphere).use { filter ->
            val second = castRay(filter)!!
            assertEquals(box, second.first)
            assertEquals(0.9f, second.second, 1e-4f)
        }
        IgnoreMultipleBodiesFilter().use { filter ->
            filter.ignoreBody(sphere)
            filter.ignoreBody(box)
            assertEquals(null, castRay(filter))
            filter.clear()
            assertEquals(sphere, castRay(filter)!!.first)
        }
        // Both are in layer 0, of both kinds of layer.
        SpecifiedObjectLayerFilter(0).use { assertEquals(sphere, castRay(objectLayerFilter = it)!!.first) }
        SpecifiedObjectLayerFilter(1).use { assertEquals(null, castRay(objectLayerFilter = it)) }
        SpecifiedBroadPhaseLayerFilter(BroadPhaseLayer(0)).use { assertEquals(sphere, castRay(broadPhaseLayerFilter = it)!!.first) }
        SpecifiedBroadPhaseLayerFilter(BroadPhaseLayer(1)).use { assertEquals(null, castRay(broadPhaseLayerFilter = it)) }
        // Without the body locks, the same.
        RRayCast(Vec3(-5f, 0f, 0f), Vec3(10f, 0f, 0f)).use { ray ->
            RayCastResult().use { hit ->
                assertEquals(true, world.system.narrowPhaseQueryNoLock.castRay(ray, hit, broadPhase, objects, bodies))
                assertEquals(sphere, hit.bodyID)
            }
        }
    }

    @Test
    fun broadPhase() = scene {
        AABox(Vec3(-1f, -1f, -1f), Vec3(1f, 1f, 1f)).use { around -> assertEquals(setOf(sphere), bodiesOf { broad.collideAABox(around, it, broadPhase, objects) }) }
        AABox(Vec3(-1f, -1f, -1f), Vec3(4.5f, 1f, 1f)).use { around -> assertEquals(setOf(sphere, box), bodiesOf { broad.collideAABox(around, it, broadPhase, objects) }) }
        assertEquals(setOf(box), bodiesOf { broad.collideSphere(Vec3(7f, 0f, 0f), 1.5f, it, broadPhase, objects) })
        assertEquals(setOf(box), bodiesOf { broad.collidePoint(Vec3(5.9f, 0.9f, 0.9f), it, broadPhase, objects) })
        assertEquals(emptySet(), bodiesOf { broad.collidePoint(Vec3(2f, 0f, 0f), it, broadPhase, objects) })
        OrientedBox(translation(Vec3(0f, 0.75f, 0f)), Vec3(0.5f, 0.5f, 0.5f)).use { oriented ->
            assertEquals(setOf(sphere), bodiesOf { broad.collideOrientedBox(oriented, it, broadPhase, objects) })
        }
        broad.bounds.use { assertEquals(Vec3(-0.5f, -1f, -1f) to Vec3(6f, 1f, 1f), it.min to it.max) }

        // A ray, and a box swept along it, against the bodies' bounds.
        val rayHits = ArrayList<Pair<BodyID, Float>>()
        val rays = object : RayCastBodyCollector() {
            override fun addHit(result: BroadPhaseCastResult) {
                rayHits += result.bodyID to result.fraction
            }
        }
        RayCast(Vec3(-5f, 0f, 0f), Vec3(10f, 0f, 0f)).use { broad.castRay(it, rays, broadPhase, objects) }
        assertEquals(listOf(sphere, box), rayHits.sortedBy { it.second }.map { it.first })
        assertEquals(0.45f, rayHits.minOf { it.second }, 1e-4f)
        rays.close()

        val boxHits = ArrayList<Pair<BodyID, Float>>()
        val boxes = object : CastShapeBodyCollector() {
            override fun addHit(result: BroadPhaseCastResult) {
                boxHits += result.bodyID to result.fraction
            }
        }
        AABoxCast().use { cast ->
            AABox(Vec3(-5.5f, -0.5f, -0.5f), Vec3(-4.5f, 0.5f, 0.5f)).use { cast.box = it }
            cast.direction = Vec3(10f, 0f, 0f)
            assertEquals(Vec3(10f, 0f, 0f), cast.direction)
            broad.castAABox(cast, boxes, broadPhase, objects)
        }
        assertEquals(listOf(sphere, box), boxHits.sortedBy { it.second }.map { it.first })
        assertEquals(0.4f, boxHits.minOf { it.second }, 1e-4f)
        boxes.close()
    }

    @Test
    fun collidePoint() = scene {
        fun at(point: Vec3): List<BodyID> {
            val found = ArrayList<BodyID>()
            val collector = object : CollidePointCollector() {
                override fun addHit(result: CollidePointResult) {
                    found += result.bodyID
                }
            }
            narrow.collidePoint(point, collector, broadPhase, objects, bodies, shapes)
            collector.close()
            return found
        }
        assertEquals(listOf(sphere), at(Vec3(0.2f, 0.2f, 0.2f)))
        // In the sphere's bounds, not in the sphere.
        assertEquals(emptyList(), at(Vec3(0.4f, 0.4f, 0.4f)))
        assertEquals(listOf(box), at(Vec3(5.9f, 0.9f, 0.9f)))
    }

    @Test
    fun collideShape() = scene {
        // A sphere of radius 1, a meter along x: half a meter into the scene's.
        val hits = ArrayList<Triple<BodyID, Float, Vec3>>()
        val collector = object : CollideShapeCollector() {
            override fun addHit(result: CollideShapeResult) {
                hits += Triple(result.bodyID2, result.penetrationDepth, result.penetrationAxis)
            }
        }
        SphereShape(1f, null).use { probe ->
            CollideShapeSettings().use { settings ->
                narrow.collideShape(probe, Vec3(1f, 1f, 1f), translation(Vec3(1f, 0f, 0f)), settings, Vec3.ZERO, collector, broadPhase, objects, bodies, shapes)
                assertEquals(listOf(sphere), hits.map { it.first })
                assertEquals(0.5f, hits[0].second, 1e-3f)
                assertNear(Vec3(-1f, 0f, 0f), hits[0].third.normalized(), 1e-3f)

                // Scaled to twice that, it reaches the box too; and a filter written in Kotlin leaves the sphere out.
                hits.clear()
                val notTheSphere = object : BodyFilter() {
                    override fun shouldCollide(bodyID: BodyID) = bodyID != sphere
                }
                narrow.collideShape(probe, Vec3(2f, 2f, 2f), translation(Vec3(2.5f, 0f, 0f)), settings, Vec3.ZERO, collector, broadPhase, objects, notTheSphere, shapes)
                assertEquals(listOf(box), hits.map { it.first })
                assertEquals(0.5f, hits[0].second, 1e-3f)
                notTheSphere.close()
            }
        }
        collector.close()
    }

    @Test
    fun castShape() = scene {
        val hits = ArrayList<Pair<BodyID, Float>>()
        val collector = object : CastShapeCollector() {
            override fun addHit(result: ShapeCastResult) {
                hits += result.bodyID2 to result.fraction
            }
        }
        // A sphere of radius ½ thrown along the ray: it touches the scene's a meter from its center, the box at its face.
        SphereShape(0.5f, null).use { probe ->
            val start = translation(Vec3(-5f, 0f, 0f))
            probe.getWorldSpaceBounds(start, Vec3(1f, 1f, 1f)).use { bounds ->
                RShapeCast(probe, Vec3(1f, 1f, 1f), start, Vec3(10f, 0f, 0f), bounds).use { cast ->
                    ShapeCastSettings().use { settings -> narrow.castShape(cast, settings, Vec3.ZERO, collector, broadPhase, objects, bodies, shapes) }
                }
            }
        }
        assertEquals(listOf(sphere, box), hits.sortedBy { it.second }.map { it.first })
        val fractions = hits.map { it.second }.sorted()
        assertEquals(0.4f, fractions[0], 1e-3f)
        assertEquals(0.85f, fractions[1], 1e-3f)
        collector.close()
    }
}
