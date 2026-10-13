# Native Builds

One `c/CMakeLists.txt` builds Jolt and the C API for every target. Gradle drives it: each target has a `cmakeBuild`
task in `kotlin/kolt/build.gradle.kts`, all made by one `cmake(…)` helper, and the Kotlin tasks that need a library
depend on the task that builds it. You rarely run these by hand.

| Task | Target | Produces | In |
| :--- | :--- | :--- | :--- |
| `:desktop:cmakeBuild` | the host, for the JVM | the JNI `libkolt` | `desktop/build/kolt-c/<os>-<arch>/` (`macos-arm64`, `linux-x64`, `windows-x64`, …) |
| `cmakeBuild_iosArm64` | an iOS device | `libkolt-c.a`, `libJolt.a` | `build/kolt-c/iosArm64/` |
| `cmakeBuild_iosSimulatorArm64` | an Apple Silicon simulator | `libkolt-c.a`, `libJolt.a` | `build/kolt-c/iosSimulatorArm64/` |
| `cmakeBuild_arm64-v8a`, `cmakeBuild_x86_64` | Android | `libkolt.so` | `kotlin/kolt/build/jniLibs/<abi>/` |
| `cmakeBuild_wasm` | the web | `kolt.js`, `kolt.wasm` | `kotlin/kolt/build/koltWasm/` |

All but the first are prefixed `:kotlin:kolt:`. Each is a Ninja release build in `build/cmake/<target>/`; on Windows the host's is
Visual Studio's, CMake's default there.

## What gets built

- **`Jolt`**, from `jolt/Build`, with kolt's options: no debug renderer, no profiler, no GPU compute, no LTO
  (Kotlin/Native's linker takes objects), and **RTTI on**, which the C API uses to tell Kotlin an object's real class.
- **`kolt-c`**, a static library of `c/kolt/generated/**/*.cpp` and `c/kolt/manual/*.cpp`.
- Then, per platform, what makes those reachable from Kotlin:

### Kotlin/Native (iOS)

Nothing more. A header-less cinterop carries `libkolt-c.a` and `libJolt.a` into the klib, and each Kotlin external
links to its C symbol by `@SymbolName`.

The deployment target matches Kotlin/Native's minimum: iOS 15.

### JNI (JVM, Android)

`kolt-jni` is a shared library of three parts:

- `c/kolt/jni/KoltJni.cpp`, hand-written: native memory, strings, and the function C++ calls a Kotlin override through;
- the **JNI forwarders**, one per C function, written by `generateBindings` into `build/generated/bindings/jni/`;
- `kolt-c` and Jolt.

Only the JNI entry points are exported (`kolt-jni.map` on Android and Linux, `-exported_symbol` on macOS), so Jolt's
C++ stays local and the linker drops what's never reached.

For a desktop, `:desktop` packs the library into its platform's runtime jar, and `KoltLoader` extracts and loads it
([`desktop/README.md`](../../desktop/README.md)). On Linux the C++ runtime is linked in statically; on Windows, the
static C runtime.

On Android the build uses the SDK's own CMake (3.22.1) and NDK (29.0.14206865), targets API 24 with a static
`libc++`, and links for 16 KB pages. On x86_64 the instruction sets above SSE4.2 are off, as that is all Android's ABI
promises.

### Web

`kolt-web` links the same objects with Emscripten into `kolt.js` + `kolt.wasm`:

- the exported functions are the list `generateBindings` writes (`wasm/kolt-exports.txt`);
- `kolt-types.js` (generated) and `c/kolt/web/kolt-globals.js` (hand-written) are appended, and install the exports as
  the globals the Kotlin externals bind;
- wasm SIMD is on; memory starts at 128 MB and grows; the image is single-threaded.

The Emscripten SDK is pinned by `emsdkVersion` in `gradle.properties`. `./gradlew setupEmsdk` installs it into
`.emsdk/`, and `cmakeBuild_wasm` depends on it.

## Not done yet

- `kolt.js` and `kolt.wasm` aren't in the klib or an npm package: a page includes them itself, from the GitHub release.
- A desktop's library is built on that desktop: a release's come from CI's runners, one per platform.
- Android builds the 64-bit ABIs only; there is no `iosX64`.

These are marked in the build with `ponytail:` comments naming what the fuller version would be.
