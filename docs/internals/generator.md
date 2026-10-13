# The Generator

kolt's API isn't written, it's generated: from Jolt's C++ headers to a C API, and from that C API to Kotlin. The
generator is `buildlogic.apigen`, in `build-logic/`.

## Why a C API in the middle

Kotlin can't call C++. Each of its targets can call C, though, in its own way: Kotlin/Native directly, the JVM and
Android through JNI, the web through wasm exports. So the generator first flattens Jolt's classes into C functions
(`JphBodyInterface_CreateAndAddBody`), and every platform binds those same functions. One C API means one behavior.

The Kotlin classes are then generated on top, in `commonMain`, putting back the shape C took away: classes, properties,
overloads, inheritance, ownership.

## The stages

Each stage is a function of the one before it (`ApiGen.kt`):

```
read()             clang's AST of the API headers → CppApi                   cpp/
cApi()             CppApi → CApi: the C files, and what they bind            c/
manifest()         the C headers on disk → each function's ABI signature     c/CManifest
kotlinExternals()  the manifest → Kotlin externals                           kotlin/
kotlinApi()        CApi → the typed Kotlin API over them                     kotlin/api/
glue()             the manifest → JNI forwarders, wasm tables                glue/
coverage()         CApi, and the Kotlin sources on disk → api-coverage.txt   report/
```

1. **Read.** `clang++ -fsyntax-only -Xclang -ast-dump=json` on the headers `c/api-headers.txt` lists. The JSON becomes
   a model of the C++ API: classes, methods, fields, enums, their types and doc comments.
2. **C API.** Each class becomes a `.h`/`.cpp` pair of functions forwarding to C++. How a C++ type crosses into C is
   decided here: a class is an opaque handle, a `Vec3` a struct of floats, a `Ref<Shape>` a pointer with a reference
   added, a virtual class something C can override. What can't cross is recorded with the reason.
3. **Manifest.** `api-manifest.json` is each C function's ABI signature. From here on, the stages only need C.
4. **Externals.** One Kotlin `external fun` per C function, in a `capi` package.
5. **Kotlin API.** The public classes, calling the externals.
6. **Glue.** What the JVM and the web need to reach the same C functions: a JNI forwarder per function, and the wasm
   export list. Kotlin/Native needs none, as `@SymbolName` links an external to its C symbol.
7. **Coverage.** The account of what was bound: see [below](#the-coverage-report).

## Tasks

| Task | Does |
| :--- | :--- |
| `./gradlew generateApi` | Everything committed, in order: the C API and manifest, the externals, the Kotlin API, the coverage report. **The one to run.** |
| `./gradlew apiCheck` | Fails when what's committed isn't what `generateApi` would write. |
| `./gradlew apiModel` | Reports the C++ API clang sees in the listed headers. For looking, writes nothing committed. |
| `generateCApi`, `generateKotlinExternals`, `generateKotlinApi`, `apiCoverage` | The stages of `generateApi`, one at a time. |
| `generateBindings` | The glue, into `build/generated/bindings/`. Not committed: the native builds run it. |

Generating needs `clang++` on the `PATH`.

### Keeping it honest

Generated code is committed so that the API is reviewable in a diff and the build doesn't need clang. The cost is that
it can drift from the generator. `apiCheck` closes that gap: run it after touching the generator, `api-headers.txt` or
the Jolt submodule.

## Choosing what's API: `c/api-headers.txt`

```
[kolt]
Jolt/Core/Color.h
Jolt/Physics/Body/Body.h
…
-JPH::BodyManager
-JPH::.*::sRegister
```

- `[module]` starts a C module: its declarations are generated into `c/<module>/`.
- A path or glob, relative to `jolt/`, includes headers.
- `!glob` excludes headers.
- `-regex` leaves out the C++ declarations whose qualified name matches: a class and all that uses it, a member, one
  overload (`Class::Method(Types)`) or one constructor (`Class(Types)`).

Binding more of Jolt is usually one line here, then `./gradlew generateApi`.

## The generator knows no library

Nothing in `buildlogic.apigen` mentions Jolt. Everything it is told is one `ApiGenConfig`, in
`build-logic/src/main/kotlin/kolt-root.gradle.kts`:

| Group | Says | For Jolt |
| :--- | :--- | :--- |
| `sources` | Where the headers are, how clang reads them. | `jolt/`, the `JPH::` namespace, `Jolt/Jolt.h` first. |
| `c` | How C names and files are built; whether the library has RTTI. | Prefix `Jph`, grouped as `Jolt/`'s directories, RTTI on. |
| `naming` | The prefixes the library's names carry, which Kotlin drops. | `mField`, `inParam`/`outParam`/`ioParam`, `sStatic`. |
| `types` | How the types the headers alone don't explain cross into C. | `BodyID` is a `uint32_t`; `Vec3` mirrors as 4 floats; `Array` is a sequence; `Ref`/`RefConst` count references; `Result` is a value or an error. |
| `templates` | Which template instantiations to bind. | The collector aliases (`CastRayCollector`, …). |
| `kotlin` | Packages of the Kotlin output. Leave it null for a C API alone. | `io.github.erkko68.kolt`. |

Every option is documented on `ApiGenConfig.kt`. `types` and `templates` are what a Jolt upgrade is likely to touch.

A few things only the library itself can express, in C++: how to add a reference to a smart pointer, how to unpack a
result type. Those are a small hand-written bridge header (`c/kolt/manual/JphBridge.hpp`) on top of the generated
`Bridge.hpp`.

Pointing the generator at another C++ library is therefore: another config, an `api-headers.txt`, and a bridge header
only if the library has smart pointers, result types or math types that need fix-ups.

## The coverage report

`c/api-coverage.txt` lists every function the C++ API calls for, one per line:

```
wrapped   JphAABox_GetCenter = Vec3 JPH::AABox::GetCenter() const
todo      JphShape_SaveWithChildren = …  -- ShapeToIDMap &: JPH::StreamUtils::ObjectToIDMap
skipped   JphAABox_GetSupportingFace = …  -- JPH::AABox::GetSupportingFace
```

| Status | Means |
| :--- | :--- |
| `wrapped` | Bound in C, and a public Kotlin function calls it. |
| `unwrapped` | Bound in C, but no Kotlin wrapper calls it yet. |
| `todo` | Not bound: the generator can't map a type in it yet, which the line names. |
| `skipped` | Left out by a `-regex` in `api-headers.txt`. |

Its header line is the total. Currently: **3604 wrapped, 4 unwrapped, 14 todo, 216 skipped**.

This is coverage of the *API*: how much of Jolt is reachable from Kotlin. How much of that is exercised by tests is a
different number, in [Testing](testing.md#coverage).

## Testing the generator

`./gradlew -p build-logic test` runs every stage on a small fixture library (`build-logic/src/test/fixture`, a `geo`
namespace of shapes) and compares the result with the committed output. The fixture's config uses the options Jolt's
doesn't, so they stay tested.

After a deliberate change to the generator, `./gradlew -p build-logic test -Papigen.update` rewrites the expected
output; review it as a diff.
