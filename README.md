# kolt

Prototype: Kotlin Multiplatform bindings for [Jolt Physics](https://github.com/jrouwe/JoltPhysics), through a C API
generated from Jolt's headers (the generator is filament-kmp's `buildlogic.apigen`).

```
jolt/                 Jolt Physics, pinned (submodule)
c/api-headers.txt     which Jolt headers are API
c/kolt/generated/     the generated C API, grouped as Jolt's dirs: physics/body/JphBody.h (./gradlew generateCApi)
c/api-manifest.json   each C function's ABI signature, by header; what the Kotlin stages read
c/kolt/manual/        hand-written C for what the generator can't map, and the helpers only Jolt can write (JphBridge.hpp)
c/api-coverage.txt    each C function the API calls for: wrapped by Kotlin or not yet, or why it isn't bound (./gradlew apiCoverage)
c/CMakeLists.txt      builds Jolt and the C API as static libraries (./gradlew :kotlin:kolt:cmakeBuild)
kotlin/kolt/          the Kotlin module: interop/ and, per group, a package whose capi/ is the generated externals
                      (physics.body.capi; ./gradlew generateKotlinExternals)
                      and the rest the generated classes over them (./gradlew generateKotlinApi); math/ is hand-written
build-logic/          the generator: cpp/ (clang AST → model), c/ (model → C), kotlin/ (manifest → externals),
                      kotlin/api/ (what C bound → classes),
                      externals/ (manifest → JNI and wasm glue), gaps/ (coverage), verify/ (Kotlin enums' values)
```

`./gradlew :kotlin:kolt:macosArm64Test` runs Jolt through the generated classes, on Kotlin/Native on an Apple Silicon Mac,
the only target so far, as it needs no glue: a falling sphere and a ray cast, a convex hull from points, and contact
listeners, filters and a hit collector written in Kotlin.

## The Kotlin API

`Jolt.init()` first, `Jolt.shutdown()` last. Classes, methods and enums are named as Jolt's, in the package of their
header's directory (`physics.body.BodyInterface`); `GetX`/`SetX` are a property `x`, statics lose their `s`.

- A class Kotlin creates, or that Jolt hands over a reference to, is the wrapper's to `close()`; one read from an object
  Jolt keeps is borrowed, and `close()` leaves it alone.
- A class with virtual functions is open to Kotlin: `object : ContactListener() { override fun onContactAdded(…) }`.
  Jolt calls the overrides, from its job threads too, and keeps its own for the rest.
- A result that's a value or an error returns the value, or throws `NativeException` with the error.
- `c/api-coverage.txt` lists what isn't wrapped yet: mostly functions taking raw pointers, or returning through
  reference parameters.

`./gradlew apiModel` reports the C++ API clang sees in the listed headers.

## The generator

`buildlogic.apigen` knows no library. Everything it is told about Jolt is one `ApiGenConfig`, in
`build-logic/src/main/kotlin/kolt-root.gradle.kts`: where the headers are, the clang arguments, how C names are built,
and the type tables (scalars, math mirrors, containers, strings, reference counting, result types, template
instantiations). Pointing it at another library is another config, an `api-headers.txt`, and, only if the library has
smart pointers, result types or math types needing fix-ups, a small bridge header on top of the generated `Bridge.hpp`.
