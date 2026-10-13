# kolt documentation

## Guide (for users)

| Page | What it covers |
| :--- | :--- |
| [Getting Started](guide/getting-started.md) | Building from source, a first simulation, cleaning up. |
| [API Conventions](guide/api-conventions.md) | How Jolt's C++ API reads in Kotlin: names, ownership, callbacks, out-parameters, arrays, casts, errors. |
| [Platform Notes](guide/platform-notes.md) | Targets and minimum versions, how each loads Jolt, what the web does differently. |

kolt documents how Jolt is *reached* from Kotlin, not Jolt itself. For what a broad phase layer is or how a constraint
behaves, [Jolt's own documentation](https://jrouwe.github.io/JoltPhysics/) is authoritative, and it applies as written:
the names are the same.

## Internals (for contributors)

| Page | What it covers |
| :--- | :--- |
| [Repository Structure](internals/repo-structure.md) | What lives where, what is generated and what is hand-written. |
| [The Generator](internals/generator.md) | The stages from C++ headers to C to Kotlin, the Gradle tasks, the config, the coverage report. |
| [Native Builds](internals/native-builds.md) | How CMake builds Jolt and the C API for each target, the JNI layer, the wasm image. |
| [Testing](internals/testing.md) | The two test suites, running them per target, measured coverage. |
| [Upgrading Jolt](internals/upgrading-jolt.md) | Moving the submodule to a new Jolt release. |

## The short version

```
Jolt's C++ headers ──clang AST──▶ C API (Jph*) ──▶ Kotlin externals ──▶ Kotlin classes
     jolt/                        c/kolt/generated   …/capi/              io.github.erkko68.kolt.*
```

Everything to the right of the headers is generated and committed. The generator lives in `build-logic/` and knows
nothing about Jolt beyond one config.
