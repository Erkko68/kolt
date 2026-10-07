# kolt

Prototype: Kotlin Multiplatform bindings for [Jolt Physics](https://github.com/jrouwe/JoltPhysics), through a C API
generated from Jolt's headers (the generator is filament-kmp's `buildlogic.apigen`).

```
jolt/                 Jolt Physics, pinned (submodule)
c/api-headers.txt     which Jolt headers are API
c/kolt/generated/     the generated C API (./gradlew generateCApi)
c/kolt/manual/        hand-written C for what the generator can't map
build-logic/          the generator: cpp/ (clang AST → model), c/ (model → C)
```

`./gradlew apiModel` reports the C++ API clang sees in the listed headers.
