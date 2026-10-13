import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import io.github.erkko68.filament.compose.FilamentSceneView
import io.github.erkko68.filament.compose.OnFrame
import io.github.erkko68.filament.compose.orbitGestures
import io.github.erkko68.filament.compose.rememberOrbitCameraController
import io.github.erkko68.filament.compose.scene.DirectionalLight
import io.github.erkko68.filament.compose.scene.Direction
import io.github.erkko68.filament.compose.scene.LightIntensity
import io.github.erkko68.filament.compose.scene.LinearColor
import io.github.erkko68.filament.compose.scene.Position
import io.github.erkko68.filament.compose.scene.Projection
import io.github.erkko68.filament.compose.scene.Rotation
import io.github.erkko68.filament.compose.scene.ShadowConfig
import io.github.erkko68.filament.compose.scene.SkyboxSource
import io.github.erkko68.filament.compose.scene.primitives.Cube
import io.github.erkko68.filament.compose.scene.primitives.Plane
import io.github.erkko68.filament.compose.scene.rememberCameraState
import io.github.erkko68.filament.compose.scene.rememberColorMaterialInstance
import io.github.erkko68.filament.compose.scene.rememberSkyboxState
import io.github.erkko68.kolt.Jolt
import io.github.erkko68.kolt.core.JobSystemThreadPool
import io.github.erkko68.kolt.core.TempAllocatorImpl
import io.github.erkko68.kolt.math.Quat
import io.github.erkko68.kolt.math.Vec3
import io.github.erkko68.kolt.physics.EActivation
import io.github.erkko68.kolt.physics.PhysicsSystem
import io.github.erkko68.kolt.physics.body.BodyCreationSettings
import io.github.erkko68.kolt.physics.body.BodyFilter
import io.github.erkko68.kolt.physics.body.BodyID
import io.github.erkko68.kolt.physics.body.EMotionType
import io.github.erkko68.kolt.physics.collision.ObjectLayerFilter
import io.github.erkko68.kolt.physics.collision.ObjectLayerPairFilterTable
import io.github.erkko68.kolt.physics.collision.RRayCast
import io.github.erkko68.kolt.physics.collision.RayCastResult
import io.github.erkko68.kolt.physics.collision.broadphase.BroadPhaseLayer
import io.github.erkko68.kolt.physics.collision.broadphase.BroadPhaseLayerFilter
import io.github.erkko68.kolt.physics.collision.broadphase.BroadPhaseLayerInterfaceTable
import io.github.erkko68.kolt.physics.collision.broadphase.ObjectVsBroadPhaseLayerFilterTable
import io.github.erkko68.kolt.physics.collision.shape.BoxShape
import kotlin.math.ceil
import kotlin.math.tan

private const val FOV_DEGREES = 45.0
private const val FLOOR = 60f
/** How far a click reaches, in meters. */
private const val REACH = 200f
/** What a click hits a cube with, in N·s: a cube is a cubic meter of water, 1000 kg, so about 8 m/s. */
private const val IMPULSE = 8000f

/** A wall of cubes on a floor: one object layer in one broad phase layer, colliding with itself. */
private class CubesWorld : AutoCloseable {
    private val temp = TempAllocatorImpl(10 * 1024 * 1024)
    private val jobs = JobSystemThreadPool(2048, 8, 4)
    private val broadPhaseLayers = BroadPhaseLayerInterfaceTable(1, 1).also { it.mapObjectToBroadPhaseLayer(0, BroadPhaseLayer(0)) }
    private val layerPairs = ObjectLayerPairFilterTable(1).also { it.enableCollision(0, 0) }
    private val objectVsBroadPhase = ObjectVsBroadPhaseLayerFilterTable(broadPhaseLayers, 1, layerPairs, 1)
    private val system = PhysicsSystem().also { it.init(1024, 0, 4096, 4096, broadPhaseLayers, objectVsBroadPhase, layerPairs) }
    private val bodies = system.bodyInterface
    // Filters that let everything through, for the click's ray.
    private val anyBroadPhaseLayer = BroadPhaseLayerFilter()
    private val anyObjectLayer = ObjectLayerFilter()
    private val anyBody = BodyFilter()

    val cubes: List<BodyID>

    init {
        // The shapes can go once the bodies exist: each body holds a reference.
        BoxShape(Vec3(FLOOR / 2, 1f, FLOOR / 2), 0.05f, null).use { add(it, Vec3(0f, -1f, 0f), EMotionType.Static) }
        cubes = BoxShape(Vec3(0.5f, 0.5f, 0.5f), 0.05f, null).use { cube ->
            (0 until 6).flatMap { row -> (-4..4).map { column -> add(cube, Vec3(column.toFloat(), row + 0.5f, 0f), EMotionType.Dynamic) } }
        }
        system.optimizeBroadPhase()
    }

    private fun add(shape: BoxShape, position: Vec3, motionType: EMotionType): BodyID =
        BodyCreationSettings(shape, position, Quat.IDENTITY, motionType, 0).use { bodies.createAndAddBody(it, EActivation.Activate) }

    /** A step per 60th of a second of [deltaSeconds], so a slow frame doesn't take one long step. */
    fun step(deltaSeconds: Float) {
        if (deltaSeconds > 0f) system.update(deltaSeconds, ceil(deltaSeconds * 60).toInt(), temp, jobs)
    }

    fun isActive(cube: BodyID) = bodies.isActive(cube)

    fun pose(cube: BodyID): Pair<Position, Rotation> =
        bodies.getPositionAndRotation(cube).let { (p, q) -> Position(p.x, p.y, p.z) to Rotation(q.x, q.y, q.z, q.w) }

    /** Pushes the first cube along the ray from [origin] towards [direction], at the point the ray hits it. */
    fun push(origin: Vec3, direction: Vec3) {
        RRayCast(origin, direction * REACH).use { ray ->
            RayCastResult().use { hit ->
                if (!system.narrowPhaseQuery.castRay(ray, hit, anyBroadPhaseLayer, anyObjectLayer, anyBody)) return
                if (hit.bodyID in cubes) bodies.addImpulse(hit.bodyID, direction * IMPULSE, origin + direction * (REACH * hit.fraction))
            }
        }
    }

    // The reverse of the order it was created in.
    override fun close() {
        listOf(anyBody, anyObjectLayer, anyBroadPhaseLayer, system, objectVsBroadPhase, layerPairs, broadPhaseLayers, jobs, temp).forEach { it.close() }
    }
}

/**
 * The direction, of length one, a camera at [eye] looking at [target] sees the point ([x], [y]) of its [width] by
 * [height] view along: x to the right and y down, as Compose has them. [fovDegrees] is the vertical field of view.
 */
internal fun pickRay(eye: Vec3, target: Vec3, up: Vec3, fovDegrees: Double, x: Float, y: Float, width: Int, height: Int): Vec3 {
    val forward = (target - eye).normalized()
    val right = (forward cross up).normalized()
    val halfHeight = tan(fovDegrees * kotlin.math.PI / 360).toFloat()
    val ndcX = 2 * x / width - 1
    val ndcY = 1 - 2 * y / height
    return (forward + right * (ndcX * halfHeight * width / height) + (right cross forward) * (ndcY * halfHeight)).normalized()
}

private fun Position.toVec3() = Vec3(x, y, z)

private val CUBE_COLORS = listOf(
    LinearColor(0.90f, 0.25f, 0.30f), LinearColor(0.20f, 0.55f, 0.95f), LinearColor(0.95f, 0.82f, 0.25f), LinearColor(0.30f, 0.80f, 0.45f),
)

/** Drag orbits, scroll zooms, a click pushes the cube under it. */
@Composable
fun CubesScene() {
    val world = remember { Jolt.init(); CubesWorld() }
    DisposableEffect(Unit) { onDispose { world.close(); Jolt.shutdown() } }

    // A state per cube, so a frame recomposes the cubes that moved.
    val poses = remember { world.cubes.map { mutableStateOf(world.pose(it)) } }
    OnFrame { frame ->
        world.step(frame.deltaSeconds)
        world.cubes.forEachIndexed { i, cube -> if (world.isActive(cube)) poses[i].value = world.pose(cube) }
    }

    val camera = rememberCameraState(
        initialEye = Position(0f, 6f, 18f),
        initialTarget = Position(0f, 3f, 0f),
        initialProjection = Projection.Perspective(fovDegrees = FOV_DEGREES),
    )
    val orbit = rememberOrbitCameraController(camera)

    FilamentSceneView(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Not detectTapGestures: the orbit consumes every move of a pressed pointer, which cancels its tap, and
                // a mouse rarely stays on one pixel through a click. A click is a press released within the touch slop.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var tap = down.position
                    do {
                        val event = awaitPointerEvent()
                        tap = event.changes.first().position
                    } while (event.changes.any { it.pressed })
                    if ((tap - down.position).getDistance() > viewConfiguration.touchSlop) return@awaitEachGesture
                    val eye = camera.eye.toVec3()
                    world.push(eye, pickRay(eye, camera.target.toVec3(), Vec3(camera.up.x, camera.up.y, camera.up.z), FOV_DEGREES, tap.x, tap.y, size.width, size.height))
                }
            }
            .orbitGestures(orbit),
        cameraState = camera,
        skyboxState = rememberSkyboxState(initialSource = SkyboxSource.Color(LinearColor(0.08f, 0.10f, 0.14f))),
    ) {
        DirectionalLight(direction = Direction(0.3f, -1f, -0.5f), intensity = LightIntensity.LuminousPower(100_000f), shadow = ShadowConfig())
        // No environment lights the scene, so a second light fills in the faces the first leaves black.
        DirectionalLight(direction = Direction(-0.4f, -0.3f, 0.8f), intensity = LightIntensity.LuminousPower(30_000f))

        Plane(material = rememberColorMaterialInstance(LinearColor(0.28f, 0.30f, 0.36f)), width = FLOOR, depth = FLOOR)
        val materials = CUBE_COLORS.map { rememberColorMaterialInstance(it) }
        poses.forEachIndexed { i, pose ->
            val (position, rotation) = pose.value
            Cube(material = materials[i % materials.size], position = position, rotation = rotation)
        }
    }
}
