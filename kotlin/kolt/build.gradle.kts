import buildlogic.cmake.PackagedCMakeBuild
import buildlogic.cmake.cmake
import buildlogic.emsdk.emscriptenToolchain
import buildlogic.emsdk.emsdkEnvironment
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

// The plugins' versions are build-logic's (build-logic/build.gradle.kts).
plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlinx.kover")
    id("org.jetbrains.dokka")
    id("kolt-publish")
}

// The API reference: ./gradlew :kotlin:kolt:dokkaGenerate, into build/dokka/html, labelled with the version. The
// versioning plugin adds the version dropdown and copies each build/previousDocs/<version> into the site under older/,
// so one deploy serves every release: CI restores that directory from the docs-archive branch (.github/workflows/pages.yml);
// locally it's absent and only this version renders.
dependencies {
    dokkaPlugin("org.jetbrains.dokka:versioning-plugin:2.2.0")
}
dokka {
    pluginsConfiguration.versioning {
        version = providers.gradleProperty("VERSION_NAME")
        olderVersionsDir = rootProject.layout.buildDirectory.dir("previousDocs")
    }
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
    iosArm64 { iosLibs.getValue(name).let { (libDir, build) -> linkCApi(libDir, build) } }
    iosSimulatorArm64 { iosLibs.getValue(name).let { (libDir, build) -> linkCApi(libDir, build) } }

    // The externals are JNI methods of libkolt (c/kolt/jni), which :desktop builds, ships in a jar per platform and
    // loads.
    jvm()
    sourceSets.jvmMain.dependencies { api(project(":desktop")) }

    // And of libkolt.so, in the library's jniLibs. The tests run on a device or emulator (connectedAndroidDeviceTest).
    android {
        namespace = "io.github.erkko68.kolt"
        compileSdk = 37
        minSdk = androidMinSdk
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
        withDeviceTestBuilder { sourceSetTreeName = "test" }.configure { instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    }

    // The externals are the globals kolt.js installs from kolt.wasm's exports (c/kolt/web), which the page includes:
    // the tests' is a resource. Not in the klib, as webpack never sees a klib's resources: a release carries them as
    // assets. KOLT_WASM_PREBUILT=<dir> tests an already built pair (CI's js and wasm jobs take the web-runtime job's)
    // instead of running Emscripten.
    js { browser() }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs { browser() }
    val wasmRuntime = providers.environmentVariable("KOLT_WASM_PREBUILT").orNull?.let { files(it) } ?: files(cmakeBuildWasm.flatMap { it.outputDir })
    sourceSets.webTest { resources.srcDir(wasmRuntime) }

    // jniMain: shared by jvm and android, which both reach the C API through JNI.
    applyDefaultHierarchyTemplate {
        common {
            group("jni") {
                withJvm()
                withCompilations { it.platformType == KotlinPlatformType.androidJvm }
            }
        }
    }
    // The simulator the iOS tests run in: Kotlin/Native's default, or -PiosSimulatorDevice's where Xcode hasn't that one.
    tasks.withType<KotlinNativeSimulatorTest>().configureEach {
        providers.gradleProperty("iosSimulatorDevice").orNull?.let { device.set(it) }
    }

    sourceSets.commonTest.dependencies { implementation(kotlin("test")) }
    sourceSets.named("androidDeviceTest").dependencies {
        implementation("androidx.test:runner:1.7.0")
        implementation("androidx.test.ext:junit:1.3.0")
    }
}
