# kolt

[![Jolt Physics](https://img.shields.io/badge/Jolt%20Physics-5.6.0-orange)](https://github.com/jrouwe/JoltPhysics)
[![Kotlin](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF?logo=kotlin)](https://kotlinlang.org/docs/multiplatform.html)

**kolt** is [Jolt Physics](https://github.com/jrouwe/JoltPhysics) for Kotlin Multiplatform: the same rigid body, character,
vehicle, soft body and ragdoll simulation on **Android**, **iOS**, **macOS**, the **JVM** and the **Web (JS & Wasm)**, from one
API in `commonMain`.

> [!NOTE]
> **Unofficial project.** A community-maintained Kotlin wrapper around Jolt Physics, not affiliated with or endorsed by
> its authors.

```kotlin
Jolt.init()

val temp = TempAllocatorImpl(10 * 1024 * 1024)
val jobs = JobSystemThreadPool(2048, 8, 1)
// One object layer in one broad phase layer, colliding with itself.
val broadPhaseLayers = BroadPhaseLayerInterfaceTable(1, 1).also { it.mapObjectToBroadPhaseLayer(0, BroadPhaseLayer(0)) }
val layerPairs = ObjectLayerPairFilterTable(1).also { it.enableCollision(0, 0) }
val objectVsBroadPhase = ObjectVsBroadPhaseLayerFilterTable(broadPhaseLayers, 1, layerPairs, 1)
val system = PhysicsSystem().also { it.init(1024, 0, 1024, 1024, broadPhaseLayers, objectVsBroadPhase, layerPairs) }
val bodies = system.bodyInterface

val sphere = SphereShape(0.5f, null)
val ball = BodyCreationSettings(sphere, Vec3(0f, 10f, 0f), Quat.IDENTITY, EMotionType.Dynamic, 0).use { settings ->
    bodies.createAndAddBody(settings, EActivation.Activate)
}

repeat(60) { system.update(1f / 60, 1, temp, jobs) }
println(bodies.getPosition(ball)) // about 5 m lower, a second later
```

If you know Jolt, you know kolt: the classes, methods and enums are Jolt's, in the package of their header's directory
(`physics.body.BodyInterface`). [Getting Started](docs/guide/getting-started.md) continues this example, down to closing
what it created.

## Platform support

Every platform runs on **one C API generated from Jolt's C++ headers**, and the Kotlin API is generated once, in
`commonMain`, on top of it. The API and its behavior are the same everywhere:

| Platform | Targets | How Kotlin reaches Jolt |
| :--- | :--- | :--- |
| **Android** | `arm64-v8a`, `x86_64`, minSdk 24 | JNI, `libkolt.so` in the library's jniLibs |
| **iOS** | `iosArm64`, `iosSimulatorArm64`, iOS 15 | Kotlin/Native, linked statically |
| **macOS** | `macosArm64`, macOS 12 | Kotlin/Native, linked statically |
| **JVM** | the host's (macOS arm64 today) | JNI, `libkolt` on `java.library.path` |
| **Web** | `js`, `wasmJs`, in a browser | Jolt compiled to wasm with Emscripten, `kolt.js` + `kolt.wasm` |

What differs per platform (loading the native library, threads on the web) is in
[Platform Notes](docs/guide/platform-notes.md).

## Status

Pre-release: nothing is published yet, so kolt is built from source. Clone with the Jolt submodule and run the tests:

```sh
git clone --recurse-submodules <this repository>
./gradlew :kotlin:kolt:jvmTest :kotlin:kolt:macosArm64Test
```

The toolchain each target needs is listed in [Getting Started](docs/guide/getting-started.md#building-from-source).

## API strategy

The public API follows **Jolt's C++ API**, the one [Jolt's documentation](https://jrouwe.github.io/JoltPhysics/) and
headers describe, adapted only where Kotlin has its own shape:

- **Kotlin properties** for `GetX`/`SetX` pairs and fields (`system.gravity`, `settings.motionType`); statics lose their
  `s` (`ConstraintSettings::sRestoreFromBinaryState` is `ConstraintSettings.restoreFromBinaryState`).
- **`close()` gives back** what Kotlin created; what Jolt keeps is borrowed, and closing it does nothing.
- **Subclass to listen**: `object : ContactListener() { override fun onContactAdded(…) }`. A `std::function` is a Kotlin
  lambda.
- **Results, not out-parameters**: `getPositionAndRotation(id)` returns a `Pair<Vec3, Quat>`; an error result throws
  `NativeException`.
- **Nothing invented**: no wrappers C++ doesn't have. Of Jolt's bindable API, 3604 of 3622 functions are wrapped;
  [`c/api-coverage.txt`](c/api-coverage.txt) names the rest.

The full set of rules is in [API Conventions](docs/guide/api-conventions.md).

## Documentation

### Using kolt
- **[Getting Started](docs/guide/getting-started.md)**: building from source, a first simulation, cleaning up.
- **[API Conventions](docs/guide/api-conventions.md)**: how Jolt's C++ reads in Kotlin: names, ownership, callbacks,
  out-parameters, arrays, casts.
- **[Platform Notes](docs/guide/platform-notes.md)**: targets, native libraries, what the web does differently.

### Working on kolt
- **[Repository Structure](docs/internals/repo-structure.md)**: what lives where, and what is generated.
- **[The Generator](docs/internals/generator.md)**: from C++ headers to a C API to Kotlin, and its config.
- **[Native Builds](docs/internals/native-builds.md)**: CMake, JNI, the wasm image.
- **[Testing](docs/internals/testing.md)**: the suites, how to run them per target, coverage.
- **[Upgrading Jolt](docs/internals/upgrading-jolt.md)**: moving the submodule to a new release.

### Upstream Jolt (authoritative for physics concepts)
- **[Jolt Physics documentation](https://jrouwe.github.io/JoltPhysics/)**: architecture, layers, shapes, constraints.
- **[HelloWorld.cpp](https://github.com/jrouwe/JoltPhysics/blob/master/HelloWorld/HelloWorld.cpp)**: the C++ this README's
  example mirrors.
