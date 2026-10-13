# Getting Started

This page adds kolt to a project, or builds it from source, then walks through a first simulation.

## Adding kolt

kolt is on Maven Central, as one dependency with the native code of every target:

```kotlin
commonMain.dependencies { implementation("io.github.erkko68.kolt:kolt:0.1.0") }
```

What each platform needs besides (the web's `kolt.js` and `kolt.wasm`, a desktop app's runtime jar) is in
[Platform Notes](platform-notes.md).

## Building from source

Clone with Jolt, which is a submodule pinned to the release kolt is generated from:

```sh
git clone --recurse-submodules https://github.com/Erkko68/kolt.git
# or, in an existing clone
git submodule update --init
```

The build host is an Apple Silicon Mac; the JVM target alone also builds on Linux and Windows. What each target needs:

| To build | You need |
| :--- | :--- |
| Anything | JDK 17, `cmake` and `ninja` on the `PATH` (on Windows: Visual Studio's C++ tools, and Git's `sh`, in place of `ninja`) |
| iOS | Xcode |
| JVM | nothing more: the JNI headers are the JDK's |
| Android | the Android SDK (`sdk.dir` in `local.properties`), with `sdkmanager "ndk;29.0.14206865" "cmake;3.22.1"` |
| Web | nothing more: `./gradlew setupEmsdk` installs the pinned Emscripten SDK into `.emsdk/`, and the web build runs it for you |
| The generator | `clang++` on the `PATH` (only to regenerate the API, see [The Generator](../internals/generator.md)) |

Then run the tests on the target you care about. Each builds Jolt and the C API for that target first, which takes a
few minutes the first time:

```sh
./gradlew :kotlin:kolt:jvmTest                      # the JVM, through JNI
./gradlew :kotlin:kolt:iosSimulatorArm64Test        # Kotlin/Native, in an iOS simulator
./gradlew :kotlin:kolt:connectedAndroidDeviceTest   # an Android device or emulator
./gradlew :kotlin:kolt:jsBrowserTest :kotlin:kolt:wasmJsBrowserTest   # headless Chrome
```

## A first simulation

Jolt's own [HelloWorld](https://github.com/jrouwe/JoltPhysics/blob/master/HelloWorld/HelloWorld.cpp), in Kotlin: a
sphere dropped onto a floor.

### 1. Start Jolt

```kotlin
Jolt.init()
```

`Jolt.init()` loads the native library, registers Jolt's allocator, creates its factory and registers its types. Call it
once, before creating anything. On the web, wait for `loadJolt()` first
(see [Platform Notes](platform-notes.md#web-js-and-wasmjs)).

### 2. Create a physics system

A system needs an allocator for its per-step scratch memory, a job system to run on, and the three tables that say which
layers collide. Here there is one object layer, in one broad phase layer, colliding with itself:

```kotlin
val temp = TempAllocatorImpl(10 * 1024 * 1024)
val jobs = JobSystemThreadPool(2048, 8, 1)

val broadPhaseLayers = BroadPhaseLayerInterfaceTable(1, 1)
broadPhaseLayers.mapObjectToBroadPhaseLayer(0, BroadPhaseLayer(0))
val layerPairs = ObjectLayerPairFilterTable(1)
layerPairs.enableCollision(0, 0)
val objectVsBroadPhase = ObjectVsBroadPhaseLayerFilterTable(broadPhaseLayers, 1, layerPairs, 1)

val system = PhysicsSystem()
system.init(1024, 0, 1024, 1024, broadPhaseLayers, objectVsBroadPhase, layerPairs)
```

The arguments are Jolt's, in Jolt's order: `init(maxBodies, numBodyMutexes, maxBodyPairs, maxContactConstraints, …)`.
The KDoc on each class and function is Jolt's header comment.

### 3. Add bodies

Bodies are created and moved through the system's `BodyInterface`, and referred to by `BodyID`:

```kotlin
val bodies = system.bodyInterface

val box = BoxShape(Vec3(50f, 1f, 50f), 0.05f, null)
val floor = BodyCreationSettings(box, Vec3(0f, -1f, 0f), Quat.IDENTITY, EMotionType.Static, 0).use { settings ->
    bodies.createAndAddBody(settings, EActivation.DontActivate)
}

val sphere = SphereShape(0.5f, null)
val ball = BodyCreationSettings(sphere, Vec3(0f, 10f, 0f), Quat.IDENTITY, EMotionType.Dynamic, 0).use { settings ->
    settings.restitution = 0.5f
    bodies.createAndAddBody(settings, EActivation.Activate)
}
```

`BodyCreationSettings` is only needed until the body exists, so `use` closes it. `Vec3` and `Quat` are plain Kotlin data
classes: they are copied across, and never need closing.

### 4. Step

```kotlin
repeat(120) {
    system.update(1f / 60, 1, temp, jobs)
    println(bodies.getPosition(ball))
}
```

`update` returns an `EPhysicsUpdateError`; `EPhysicsUpdateError.None` is a step that went well.

### 5. Hear about contacts

A Jolt class with virtual functions is open in Kotlin. Override what you want to hear about; Jolt keeps its own
behavior for the rest:

```kotlin
val listener = object : ContactListener() {
    override fun onContactAdded(body1: Body, body2: Body, manifold: ContactManifold, settings: ContactSettings) {
        println("${body1.id} touched ${body2.id}")
    }
}
system.contactListener = listener
```

Jolt calls it from its job threads, so what it touches must be thread-safe.

### 6. Clean up

Kotlin's garbage collector doesn't free Jolt's objects: what you created, you `close()`, in the reverse of the order it
was created in. Then shut Jolt down.

```kotlin
listOf(ball, floor).forEach { bodies.removeBody(it); bodies.destroyBody(it) }
sphere.close(); box.close()
system.close()
listener.close()
objectVsBroadPhase.close(); layerPairs.close(); broadPhaseLayers.close()
jobs.close(); temp.close()

Jolt.shutdown()
```

A system destroys the bodies and constraints it still has when it closes, so the first line is optional here. The
shapes can be closed as soon as the bodies exist: Jolt counts references, and each body holds one.

## Where next

- [API Conventions](api-conventions.md) is the complete list of how C++ maps to Kotlin, including who owns what.
- The tests in `kotlin/kolt/src/commonTest` are working examples of every area: constraints, characters, vehicles, soft
  bodies, ragdolls, queries. `World.kt` there wraps steps 1, 2 and 6 as `world { … }`, which is a good helper to copy.
- [Jolt's documentation](https://jrouwe.github.io/JoltPhysics/) explains the concepts.
