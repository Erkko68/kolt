import buildlogic.emsdk.emscriptenToolchain
import buildlogic.emsdk.emsdkEnvironment
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    kotlin("multiplatform") version "2.4.20"
    id("com.android.kotlin.multiplatform.library") version "9.4.1"
}

// A build whose [outputDir] holds only what's packaged: libkolt.so under <abi>, as an app's jniLibs are laid out, or kolt.{js,wasm}.
abstract class PackagedCMakeBuild : Exec() {
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
}

val cDir = rootProject.layout.projectDirectory.dir("c")
val bindings = rootProject.layout.buildDirectory.dir("generated/bindings")

/**
 * Configures c/ (the C API and Jolt) as a Ninja release build in build/cmake/<[dir]> with [args], and builds [target]
 * (all of them, for null) into [output]. [glue]: it compiles what :generateBindings writes, the JNI forwarders or the
 * wasm tables. [cmake]: the binary, where it isn't the PATH's.
 */
fun Exec.cmake(dir: String, output: File, args: String, target: String? = null, glue: Boolean = true, cmake: String = "cmake") {
    val build = rootProject.layout.buildDirectory.dir("cmake/$dir").get().asFile
    inputs.dir(cDir).withPropertyName("c")
    inputs.dir(rootProject.layout.projectDirectory.dir("jolt/Jolt")).withPropertyName("jolt")
    // Compiles what it writes, and tasks run in parallel.
    mustRunAfter(":generateCApi")
    if (glue) {
        dependsOn(":generateBindings")
        inputs.dir(bindings).withPropertyName("bindings")
    }
    commandLine(
        "sh", "-c",
        "'$cmake' -S '${cDir.asFile}' -B '$build' -G Ninja -DCMAKE_BUILD_TYPE=Release $args -DKOLT_OUTPUT_DIR='$output'" +
            (if (glue) " -DKOLT_BINDINGS_DIR='${bindings.get().asFile}'" else "") +
            " && '$cmake' --build '$build'" + (target?.let { " --target $it" } ?: ""),
    )
}

// The static libraries, and the JVM's JNI library, for the host, at Kotlin/Native's minimum macOS (konan.properties'
// minVersion.macos).
val cLibDir = rootProject.layout.buildDirectory.dir("kolt-c/macos-arm64")
val cmakeBuild = tasks.register<Exec>("cmakeBuild") {
    outputs.dir(cLibDir)
    cmake("macos-arm64", cLibDir.get().asFile, "-DCMAKE_OSX_DEPLOYMENT_TARGET=12.0 -DJNI_HOME='${System.getProperty("java.home")}'")
}

// The static libraries alone for iOS, a device's and an Apple Silicon simulator's, at Kotlin/Native's minimum iOS
// (minVersion.ios). ponytail: no iosX64; an Intel Mac's simulator is one more entry.
val iosLibs = mapOf("iosArm64" to "iphoneos", "iosSimulatorArm64" to "iphonesimulator").mapValues { (target, sdk) ->
    val libDir = rootProject.layout.buildDirectory.dir("kolt-c/$target")
    libDir to tasks.register<Exec>("cmakeBuild_$target") {
        outputs.dir(libDir)
        cmake(
            target, libDir.get().asFile,
            "-DCMAKE_SYSTEM_NAME=iOS -DCMAKE_OSX_SYSROOT=$sdk -DCMAKE_OSX_ARCHITECTURES=arm64 -DCMAKE_OSX_DEPLOYMENT_TARGET=15.0",
            target = "kolt-c", glue = false,
        )
    }
}

// The JNI library for Android, per ABI, with the SDK's NDK and CMake. ponytail: the 64-bit ABIs; the 32-bit ones need
// the interop's pointers sized (Interop.jni.kt).
val ndkVersion = "29.0.14206865"
val androidCmakeVersion = "3.22.1"
val androidMinSdk = 24
val androidAbis = listOf("arm64-v8a", "x86_64")
val sdk = androidComponents.sdkComponents.sdkDirectory
androidAbis.forEach { abi ->
    val build = tasks.register<PackagedCMakeBuild>("cmakeBuild_$abi") {
        outputDir.set(layout.buildDirectory.dir("jniLibs/$abi"))
        val cmakeBin = sdk.get().dir("cmake/$androidCmakeVersion/bin").asFile
        val ndk = sdk.get().dir("ndk/$ndkVersion").asFile
        doFirst { check(ndk.isDirectory && cmakeBin.isDirectory) { "Missing NDK/CMake: sdkmanager \"ndk;$ndkVersion\" \"cmake;$androidCmakeVersion\"" } }
        cmake(
            "android-$abi", outputDir.get().asFile.resolve(abi),
            "-DCMAKE_MAKE_PROGRAM='$cmakeBin/ninja' -DCMAKE_TOOLCHAIN_FILE='$ndk/build/cmake/android.toolchain.cmake' -DANDROID_ABI=$abi " +
                "-DANDROID_PLATFORM=android-$androidMinSdk -DANDROID_STL=c++_static",
            target = "kolt-jni", cmake = "$cmakeBin/cmake",
        )
    }
    androidComponents.onVariants { it.sources.jniLibs?.addGeneratedSourceDirectory(build, PackagedCMakeBuild::outputDir) }
}

// And kolt.{js,wasm} for the web, with the Emscripten SDK :setupEmsdk installs into .emsdk/.
val cmakeBuildWasm = tasks.register<PackagedCMakeBuild>("cmakeBuild_wasm") {
    outputDir.set(layout.buildDirectory.dir("koltWasm"))
    val emsdk = rootDir.resolve(".emsdk")
    dependsOn(":setupEmsdk")
    doFirst { environment(emsdkEnvironment(emsdk)) }
    cmake("wasm", outputDir.get().asFile, "-DCMAKE_TOOLCHAIN_FILE='${emscriptenToolchain(emsdk)}'", target = "kolt-web")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
    // Common `external fun`s bind to C through @SymbolName on Native (see interop/Interop.kt), which needs this opt-in
    // at every use site, commonMain included (as in skiko; KT-46649).
    sourceSets.all { languageSettings.optIn("kotlin.native.SymbolNameIsInternal") }

    // A header-less cinterop: the externals link by @SymbolName, so it only carries the libraries to the final binary.
    fun KotlinNativeTarget.linkCApi(libDir: Provider<Directory>, build: TaskProvider<Exec>) {
        val def = layout.buildDirectory.file("cinterop/$name/kolt.def")
        val writeDef = tasks.register("cinteropDef_$name") {
            val text = "staticLibraries = libkolt-c.a libJolt.a\nlibraryPaths = ${libDir.get().asFile.path}\nlinkerOpts = -lc++\n"
            inputs.property("text", text)
            outputs.file(def)
            doLast { def.get().asFile.writeText(text) }
        }
        compilations.getByName("main").cinterops.create("kolt") {
            definitionFile.set(def)
            tasks.named(interopProcessingTaskName) {
                dependsOn(writeDef)
                // The klib embeds the archives, so a rebuilt C API must re-run cinterop.
                inputs.files(build).withPropertyName("cApi")
            }
        }
    }
    macosArm64 { linkCApi(cLibDir, cmakeBuild) }
    iosArm64 { iosLibs.getValue(name).let { (libDir, build) -> linkCApi(libDir, build) } }
    iosSimulatorArm64 { iosLibs.getValue(name).let { (libDir, build) -> linkCApi(libDir, build) } }

    // The externals are JNI methods of libkolt (c/kolt/jni), which the tests find next to the static libraries.
    jvm()

    // And of libkolt.so, in the library's jniLibs. The tests run on a device or emulator (connectedAndroidDeviceTest).
    android {
        namespace = "io.github.erkko68.kolt"
        compileSdk = 37
        minSdk = androidMinSdk
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
        withDeviceTestBuilder { sourceSetTreeName = "test" }.configure { instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    }

    // The externals are the globals kolt.js installs from kolt.wasm's exports (c/kolt/web), which the page includes:
    // the tests' is a resource. ponytail: not in the klib; a release asset or an npm package once one is published.
    js { browser() }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs { browser() }
    sourceSets.webTest { resources.srcDir(cmakeBuildWasm.flatMap { it.outputDir }) }

    // jniMain: shared by jvm and android, which both reach the C API through JNI.
    applyDefaultHierarchyTemplate {
        common {
            group("jni") {
                withJvm()
                withCompilations { it.platformType == KotlinPlatformType.androidJvm }
            }
        }
    }
    tasks.withType<Test>().configureEach {
        dependsOn(cmakeBuild)
        systemProperty("java.library.path", cLibDir.get().asFile.path)
    }

    sourceSets.commonTest.dependencies { implementation(kotlin("test")) }
    sourceSets.named("androidDeviceTest").dependencies {
        implementation("androidx.test:runner:1.7.0")
        implementation("androidx.test.ext:junit:1.3.0")
    }
}
