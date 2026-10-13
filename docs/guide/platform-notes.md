# Platform Notes

The Kotlin API is one `commonMain`. What differs per platform is how the call reaches Jolt, and with it, how the native
library gets loaded.

| Platform | Targets | Minimum | Native code | Loaded by |
| :--- | :--- | :--- | :--- | :--- |
| Android | `arm64-v8a`, `x86_64` | API 24 | `libkolt.so` (JNI) | the app, from the library's jniLibs |
| iOS | `iosArm64`, `iosSimulatorArm64` | iOS 15 | `libkolt-c.a` + `libJolt.a` | the linker: nothing to load |
| JVM | macOS arm64; Linux and Windows, x64 and arm64 | JDK 17 | `libkolt` (JNI) | `KoltLoader`, out of the platform's runtime jar |
| Web | `js`, `wasmJs` (browser) | wasm SIMD | `kolt.js` + `kolt.wasm` | the page, then `loadJolt()` |

## Android

`libkolt.so` is built per ABI with the NDK and packaged in the library's jniLibs, so an app depending on kolt needs no
setup. The library's classes are JVM 11 bytecode. It is linked with 16 KB page alignment, as Android 15 requires. Only the 64-bit ABIs are built.

## iOS

Jolt and the C API are static libraries embedded in the klib, and Kotlin/Native calls the C functions directly: no
glue, nothing to load at run time. There is no `iosX64` (Intel Mac simulator) target.

## JVM

The JNI library is `libkolt` (`libkolt.dylib`, `libkolt.so`, `kolt.dll`), and it comes with the dependency: `kolt`'s
JVM artifact depends on `kolt-desktop`, which brings a `kolt-runtime-<os>-<arch>` jar per platform. On the first call,
`KoltLoader` extracts the one for the machine into `~/.kolt/` and loads it; nothing to set up.

| System property | What it does |
| :--- | :--- |
| `kolt.library.path` | Load `libkolt` from this directory instead of a jar. |
| `kolt.data.path` | Where it's extracted (default `~/.kolt`). |
| `kolt.data.cleanup.days` | Extracted copies of older versions are deleted after this many days unused (default 30; `0` keeps them). |

An app that ships for one platform can depend on that platform's runtime alone: see
[`desktop/README.md`](../../desktop/README.md).

Building from source, `./gradlew :desktop:cmakeBuild` builds the host's library into `desktop/build/kolt-c/<os>-<arch>/`,
and the host's runtime jar takes it from there. On Linux it needs only glibc: the C++ runtime is linked in.

## Web (JS and wasmJs)

Jolt is compiled to WebAssembly with Emscripten, as `kolt.js` and `kolt.wasm`. Both Kotlin targets, `js` and `wasmJs`,
call the same image.

**Include the runtime.** The two files aren't in the klib (webpack never sees a klib's resources): each GitHub
release carries them as assets. Or build them with `./gradlew :kotlin:kolt:cmakeBuild_wasm`
(into `kotlin/kolt/build/koltWasm/`), serve them next to each other, and include `kolt.js` in the page before your
Kotlin code:

```html
<script src="kolt.js"></script>
<script src="your-app.js"></script>
```

**Wait for it.** Instantiating wasm is asynchronous, so there is one extra call on the web, before `Jolt.init()`:

```kotlin
loadJolt()   // a Promise: Jolt.init() and everything else come after it resolves
```

Calling into kolt before it resolves fails with `kolt.wasm isn't loaded: wait for loadJolt() before Jolt.init()`.

**One thread.** The wasm image is single-threaded. A `JobSystemThreadPool` runs its jobs on the calling thread,
whatever thread count it is given, so the same code runs unchanged, and `system.update` blocks the page for as long as
the step takes.

**Memory.** The image starts with 128 MB and grows as needed.

## The same everywhere

- **Callbacks arrive on Jolt's threads.** Overrides of a listener or filter are called from job threads during
  `system.update` (on the web, from the one thread). See [API Conventions](api-conventions.md#subclassing-listeners-filters-collectors).
- **Single precision.** Positions are `Vec3`; Jolt isn't built with `JPH_DOUBLE_PRECISION`.
- **No finalizers.** On every platform, native objects are freed by `close()`, never by the garbage collector.
- **Determinism** is Jolt's: the same platform and build give the same simulation. kolt doesn't enable Jolt's
  cross-platform deterministic mode.
