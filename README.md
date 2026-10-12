# kolt

Prototype: Kotlin Multiplatform bindings for [Jolt Physics](https://github.com/jrouwe/JoltPhysics), through a C API
generated from Jolt's headers (the generator is filament-kmp's `buildlogic.apigen`).

```
jolt/                 Jolt Physics, pinned (submodule)
c/api-headers.txt     which Jolt headers are API
c/kolt/generated/     the generated C API (./gradlew generateCApi)
c/api-manifest.json   each C function's ABI signature, by header; what the Kotlin stages read
c/kolt/manual/        hand-written C for what the generator can't map, and the helpers only Jolt can write (JphBridge.hpp)
c/api-coverage.txt    each C function the API calls for: wrapped by Kotlin or not yet, or why it isn't bound (./gradlew apiCoverage)
c/CMakeLists.txt      builds Jolt and the C API as static libraries (./gradlew :kotlin:kolt:cmakeBuild)
kotlin/kolt/          the Kotlin module: capi/ is the generated externals (./gradlew generateKotlinExternals), interop/ what they need
build-logic/          the generator: cpp/ (clang AST → model), c/ (model → C), kotlin/ (manifest → externals),
                      externals/ (manifest → JNI and wasm glue), gaps/ (coverage), verify/ (Kotlin enums' values)
```

`./gradlew :kotlin:kolt:macosArm64Test` drops a sphere through the externals, on Kotlin/Native on an Apple Silicon Mac:
the only target so far, as it needs no glue.

`./gradlew apiModel` reports the C++ API clang sees in the listed headers.

## The generator

`buildlogic.apigen` knows no library. Everything it is told about Jolt is one `ApiGenConfig`, in
`build-logic/src/main/kotlin/kolt-root.gradle.kts`: where the headers are, the clang arguments, how C names are built,
and the type tables (scalars, math mirrors, containers, strings, reference counting, result types, template
instantiations). Pointing it at another library is another config, an `api-headers.txt`, and, only if the library has
smart pointers, result types or math types needing fix-ups, a small bridge header on top of the generated `Bridge.hpp`.
