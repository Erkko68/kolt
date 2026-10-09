# kolt

Prototype: Kotlin Multiplatform bindings for [Jolt Physics](https://github.com/jrouwe/JoltPhysics), through a C API
generated from Jolt's headers (the generator is filament-kmp's `buildlogic.apigen`).

```
jolt/                 Jolt Physics, pinned (submodule)
c/api-headers.txt     which Jolt headers are API
c/kolt/generated/     the generated C API (./gradlew generateCApi)
c/kolt/manual/        hand-written C for what the generator can't map, and the helpers only Jolt can write (JphBridge.hpp)
c/api-coverage.txt    each C function the API calls for: bound, or why not (./gradlew apiCoverage)
build-logic/          the generator: cpp/ (clang AST → model), c/ (model → C), gaps/ (coverage)
```

`./gradlew apiModel` reports the C++ API clang sees in the listed headers.

## The generator

`buildlogic.apigen` knows no library. Everything it is told about Jolt is one `ApiGenConfig`, in
`build-logic/src/main/kotlin/kolt-root.gradle.kts`: where the headers are, the clang arguments, how C names are built,
and the type tables (scalars, math mirrors, containers, strings, reference counting, result types, template
instantiations). Pointing it at another library is another config, an `api-headers.txt`, and, only if the library has
smart pointers, result types or math types needing fix-ups, a small bridge header on top of the generated `Bridge.hpp`.

`./gradlew -p build-logic test` runs it on `build-logic/src/test/fixture`, a small library with none of Jolt's
conventions, compares the result with the committed C API there and compiles it. After a deliberate change to the
generator, add `-Papigen.update` and review the diff.
