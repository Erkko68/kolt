# Samples

A wall of cubes on a floor: kolt simulates it, [filament-kmp](https://github.com/Erkko68/filament-kmp) renders it in
Compose Multiplatform. Drag orbits the camera, a scroll or a pinch zooms, and a click or a tap pushes the cube under it.

The scene is one file for every target, [`src/commonMain/kotlin/Cubes.kt`](src/commonMain/kotlin/Cubes.kt). This is its
own Gradle build, which takes kolt from this checkout (see [`settings.gradle.kts`](settings.gradle.kts)), so each
command builds Jolt for its target first. Run them from the repository's root:

| Target | Entry point | Run |
| :--- | :--- | :--- |
| Desktop (JVM) | [`src/jvmMain`](src/jvmMain/kotlin/Main.kt) | `./gradlew -p samples run` |
| Web (wasmJs) | [`src/webMain`](src/webMain/kotlin/Main.kt) | `./gradlew -p samples wasmJsBrowserDevelopmentRun` |
| Web (JS) | the same | `./gradlew -p samples jsBrowserDevelopmentRun` |
| Android | [`androidApp/`](androidApp/) | `./gradlew -p samples :androidApp:installDebug`, with `sdk.dir` in `samples/local.properties` |
| iOS | [`iosApp/`](iosApp/), around the module's framework | open `iosApp/iosApp.xcodeproj` in Xcode and run the scheme |

On the web, the page includes two runtimes besides the app: `kolt.js`, built from this checkout, and `filament-kmp.js`,
downloaded from Filament's release. `./gradlew -p samples jsBrowserDistribution` builds the static site GitHub Pages
serves.

The iOS simulator doesn't render Filament's shadows properly; a device does.
