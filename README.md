# kolt

Prototype: Kotlin Multiplatform bindings for [Jolt Physics](https://github.com/jrouwe/JoltPhysics), through a C API
generated from Jolt's headers (the generator is filament-kmp's `buildlogic.apigen`).

```
jolt/                 Jolt Physics, pinned (submodule)
c/api-headers.txt     which Jolt headers are API
c/kolt/generated/     the generated C API, grouped as Jolt's dirs: physics/body/JphBody.h (./gradlew generateCApi)
c/api-manifest.json   each C function's ABI signature, by header; what the Kotlin stages read
c/kolt/manual/        hand-written C for what the generator can't map, and the helpers only Jolt can write (JphBridge.hpp)
c/kolt/jni/           the hand-written half of the JVM's JNI layer; the forwarders are generated (./gradlew generateBindings)
c/api-coverage.txt    each C function the API calls for: wrapped by Kotlin or not yet, or why it isn't bound (./gradlew apiCoverage)
c/CMakeLists.txt      builds Jolt and the C API as static libraries, and as the JNI library (./gradlew :kotlin:kolt:cmakeBuild)
kotlin/kolt/          the Kotlin module: interop/ and, per group, a package whose capi/ is the generated externals
                      (physics.body.capi; ./gradlew generateKotlinExternals)
                      and the rest the generated classes over them (./gradlew generateKotlinApi); math/ is hand-written
build-logic/          the generator: cpp/ (clang AST → model), c/ (model → C), kotlin/ (manifest → externals),
                      kotlin/api/ (what C bound → classes),
                      externals/ (manifest → JNI and wasm glue), gaps/ (coverage), verify/ (Kotlin enums' values)
```

`./gradlew :kotlin:kolt:macosArm64Test :kotlin:kolt:jvmTest` runs Jolt through the generated classes, on Kotlin/Native
(an Apple Silicon Mac, which needs no glue) and on the JVM (the host's, through JNI; `libkolt` is found on
`java.library.path`, not packaged yet): a falling sphere and a ray cast, a convex hull from points, contact listeners,
filters, a hit collector and a collision callback written in Kotlin, a batch of bodies, a shape's triangles and a
character on a moving floor through out-parameters and arrays, and a step listener, a state recorder, a stream and a
skeleton.

## The Kotlin API

`Jolt.init()` first, `Jolt.shutdown()` last. Classes, methods and enums are named as Jolt's, in the package of their
header's directory (`physics.body.BodyInterface`); `GetX`/`SetX` are a property `x`, statics lose their `s`.

- A class Kotlin creates, or that Jolt hands over a reference to, is the wrapper's to `close()`; one read from an object
  Jolt keeps is borrowed, and `close()` leaves it alone.
- A class with virtual functions is open to Kotlin: `object : ContactListener() { override fun onContactAdded(…) }`.
  Jolt calls the overrides, from its job threads too, and keeps its own for the rest. The other classes are final.
- A `std::function` is a Kotlin function, or null for none: `vehicle.setPreStepCallback { vehicle, context -> … }`.
  What Jolt takes back through a reference argument, the function returns.
- A result that's a value or an error returns the value, or throws `NativeException` with the error.
- What Jolt writes through a reference parameter is returned: `getPositionAndRotation(id)` is a `Pair<Vec3, Quat>`,
  after the function's own result if it has one. An `io` parameter is passed in and returned.
- A pointer to an array is a `List` or a primitive array, with the count where Jolt takes it; one Jolt writes to is a
  `MutableList`, changed in place. A `void*` is a `NativePointer`.
- A class Jolt returns by value is a copy the wrapper owns: `shape.stats.use { … }`.
- A second C++ base is a function: `vehicle.asPhysicsStepListener()`.
- `c/api-coverage.txt` lists what isn't bound yet (the stream save and restore maps, plain function pointers,
  `std::pair`s), and what `c/api-headers.txt` leaves out as Jolt's own machinery.

`./gradlew apiModel` reports the C++ API clang sees in the listed headers.

## The generator

`buildlogic.apigen` knows no library. Everything it is told about Jolt is one `ApiGenConfig`, in
`build-logic/src/main/kotlin/kolt-root.gradle.kts`: where the headers are, the clang arguments, how C names are built,
and the type tables (scalars, math mirrors, containers, strings, reference counting, result types, template
instantiations). Pointing it at another library is another config, an `api-headers.txt`, and, only if the library has
smart pointers, result types or math types needing fix-ups, a small bridge header on top of the generated `Bridge.hpp`.
