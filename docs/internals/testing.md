# Testing

There are two suites: one tests the library against Jolt, the other tests the generator against a fixture.

## The library: `kotlin/kolt/src/commonTest`

54 tests, written once and run on every target, each against a real Jolt. They are laid out as the library's packages:

| Directory | What it tests |
| :--- | :--- |
| `interop/` | That the generated classes reach Jolt at all: values, enums, handles and math types crossing; ownership and `close`; Kotlin overrides of listeners, filters and collectors; `std::function` callbacks; out-parameters and arrays; streams and state recording. |
| `math/` | The hand-written math types, against Jolt's own results. |
| `core/`, `geometry/` | Those packages. |
| `physics/body/` | Bodies, motion, forces, mass. |
| `physics/collision/` | Broad and narrow phase queries; every kind of shape. |
| `physics/constraints/`, `character/`, `vehicle/`, `softbody/`, `ragdoll/` | What Jolt does through each. |

`World.kt` is the helper they share: `world { … }` runs a block with a physics system, between `Jolt.init()` and
`Jolt.shutdown()`, whether the block fails or not.

### Running them

```sh
./gradlew :kotlin:kolt:jvmTest                      # the JVM, through JNI
./gradlew :kotlin:kolt:iosSimulatorArm64Test        # Kotlin/Native, in an iOS simulator
./gradlew :kotlin:kolt:connectedAndroidDeviceTest   # an Android device or emulator
./gradlew :kotlin:kolt:jsBrowserTest :kotlin:kolt:wasmJsBrowserTest   # headless Chrome
```

Each builds the native library it needs first. The web tests load `kolt.js` and `kolt.wasm` as test resources, and a
Karma bootstrap holds the run until the wasm is up, as the common tests call `Jolt.init()` without waiting.

Since the API is the same generated code on every target, a test passing on one and failing on another points at that
platform's interop or glue, not at the API.

## The generator: `build-logic/src/test`

```sh
./gradlew -p build-logic test
```

One test, `FixtureTest`, runs every stage of the generator on a small C++ library (`src/test/fixture/include/geo`) and
compares each file it writes with the committed one: the C API, the manifest, the Kotlin externals and classes, the JNI
and wasm glue, the coverage report. `-Papigen.update` rewrites the expected output after a deliberate change.

`./gradlew apiCheck` is the same idea on Jolt itself: it fails when the committed generated code isn't what the
generator writes now.

## Coverage

Line coverage is measured with [Kover](https://github.com/Kotlin/kotlinx-kover), on the JVM:

```sh
./gradlew :kotlin:kolt:koverLogJvm          # prints the percentage
./gradlew :kotlin:kolt:koverHtmlReportJvm   # kotlin/kolt/build/reports/kover/htmlJvm/index.html
./gradlew -p build-logic koverLog           # the generator
```

As of 2026-10-12:

| What | Lines | Notes |
| :--- | :--- | :--- |
| The Kotlin API (`kotlin/kolt`) | **29.1%** (1941 of 6668) | 30.0% of methods, 66.9% of classes, 21.0% of branches |
| The generator (`build-logic`) | **69.0%** | from the one fixture test |

By package, for the Kotlin API:

| Package | Lines | Covered |
| :--- | ---: | ---: |
| `math` | 55 | 92.7% |
| `interop` | 54 | 90.7% |
| `geometry` | 245 | 67.3% |
| `skeleton` | 65 | 53.8% |
| `core` | 125 | 50.4% |
| `physics` (the system itself) | 212 | 43.4% |
| `physics.ragdoll` | 127 | 41.7% |
| `physics.body` | 775 | 35.5% |
| `physics.collision` | 1827 | 32.2% |
| `physics.character` | 538 | 22.3% |
| `physics.vehicle` | 653 | 20.2% |
| `physics.constraints` | 1163 | 16.0% |
| `physics.softbody` | 570 | 14.7% |

### Reading these numbers

- **The hand-written code is covered** (`math`, `interop`: over 90%). That is where a bug would be a bug in kolt.
- **The rest is generated**, about 3,600 wrappers of a line or two each, written by a handful of templates. A test that
  calls one getter exercises the same template as the 500 it doesn't call. So 30% of methods covers far more than 30%
  of the ways a wrapper can be wrong, and two classes out of three are touched at least once.
- **The generator's own coverage is the complement**: the fixture test checks, file by file, that each template writes
  what it should.
- The number is the JVM's. The `capi` externals have no lines to count, and the other targets run the same tests on the
  same generated code but aren't measured.

What the low rows say is where behavior tests are thin: soft bodies, constraints and vehicles each have many settings
classes whose properties no test reads or writes.
