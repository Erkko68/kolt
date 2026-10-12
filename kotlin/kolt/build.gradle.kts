import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins {
    kotlin("multiplatform") version "2.4.20"
    id("com.android.kotlin.multiplatform.library") version "9.4.1"
}

// The C API (c/) and Jolt as static libraries, and as the JVM's JNI library, for the host.
val cLibDir = rootProject.layout.buildDirectory.dir("kolt-c/macos-arm64")
val cmakeDir = rootProject.layout.buildDirectory.dir("cmake/macos-arm64")
val cmakeBuild = tasks.register<Exec>("cmakeBuild") {
    val c = rootProject.layout.projectDirectory.dir("c")
    inputs.dir(c).withPropertyName("c")
    inputs.dir(rootProject.layout.projectDirectory.dir("jolt/Jolt")).withPropertyName("jolt")
    outputs.dir(cLibDir)
    // Compiles what it writes, and tasks run in parallel.
    mustRunAfter(":generateCApi")
    // The JNI forwarders.
    val bindings = rootProject.layout.buildDirectory.dir("generated/bindings")
    dependsOn(":generateBindings")
    inputs.dir(bindings).withPropertyName("bindings")
    // Kotlin/Native's minimum macOS (konan.properties' minVersion.macos).
    commandLine(
        "sh", "-c",
        "cmake -S '${c.asFile}' -B '${cmakeDir.get().asFile}' -G Ninja -DCMAKE_BUILD_TYPE=Release " +
            "-DCMAKE_OSX_DEPLOYMENT_TARGET=12.0 -DKOLT_OUTPUT_DIR='${cLibDir.get().asFile}' " +
            "-DKOLT_BINDINGS_DIR='${bindings.get().asFile}' -DJNI_HOME='${System.getProperty("java.home")}' && cmake --build '${cmakeDir.get().asFile}'",
    )
}

// libkolt.so for one Android ABI, into [outputDir]/<abi> as an app's jniLibs are laid out.
abstract class AndroidCMakeBuild : Exec() {
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
}

// The same image for Android, per ABI, with the SDK's NDK and CMake. ponytail: the 64-bit ABIs; the 32-bit ones need
// the interop's pointers sized (Interop.jni.kt).
val ndkVersion = "29.0.14206865"
val androidCmakeVersion = "3.22.1"
val androidMinSdk = 24
val androidAbis = listOf("arm64-v8a", "x86_64")
val sdk = androidComponents.sdkComponents.sdkDirectory
androidAbis.forEach { abi ->
    val build = tasks.register<AndroidCMakeBuild>("cmakeBuild_$abi") {
        val c = rootProject.layout.projectDirectory.dir("c")
        val bindings = rootProject.layout.buildDirectory.dir("generated/bindings")
        val dir = rootProject.layout.buildDirectory.dir("cmake/android-$abi").get().asFile
        dependsOn(":generateBindings")
        mustRunAfter(":generateCApi")
        inputs.dir(c).withPropertyName("c")
        inputs.dir(rootProject.layout.projectDirectory.dir("jolt/Jolt")).withPropertyName("jolt")
        inputs.dir(bindings).withPropertyName("bindings")
        outputDir.set(layout.buildDirectory.dir("jniLibs/$abi"))
        val cmakeBin = sdk.get().dir("cmake/$androidCmakeVersion/bin").asFile
        val ndk = sdk.get().dir("ndk/$ndkVersion").asFile
        doFirst { check(ndk.isDirectory && cmakeBin.isDirectory) { "Missing NDK/CMake: sdkmanager \"ndk;$ndkVersion\" \"cmake;$androidCmakeVersion\"" } }
        commandLine(
            "sh", "-c",
            "'$cmakeBin/cmake' -S '${c.asFile}' -B '$dir' -G Ninja -DCMAKE_MAKE_PROGRAM='$cmakeBin/ninja' -DCMAKE_BUILD_TYPE=Release " +
                "-DCMAKE_TOOLCHAIN_FILE='$ndk/build/cmake/android.toolchain.cmake' -DANDROID_ABI=$abi -DANDROID_PLATFORM=android-$androidMinSdk " +
                "-DANDROID_STL=c++_static -DKOLT_OUTPUT_DIR='${outputDir.get().asFile}/$abi' -DKOLT_BINDINGS_DIR='${bindings.get().asFile}' && " +
                "'$cmakeBin/cmake' --build '$dir' --target kolt-jni",
        )
    }
    androidComponents.onVariants { it.sources.jniLibs?.addGeneratedSourceDirectory(build, AndroidCMakeBuild::outputDir) }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
    // Common `external fun`s bind to C through @SymbolName on Native (see interop/Interop.kt), which needs this opt-in
    // at every use site, commonMain included (as in skiko; KT-46649).
    sourceSets.all { languageSettings.optIn("kotlin.native.SymbolNameIsInternal") }

    macosArm64 {
        // A header-less cinterop: the externals link by @SymbolName, so it only carries the libraries to the final binary.
        val def = layout.buildDirectory.file("cinterop/kolt.def")
        val writeDef = tasks.register("cinteropDef") {
            val text = "staticLibraries = libkolt-c.a libJolt.a\nlibraryPaths = ${cLibDir.get().asFile.path}\nlinkerOpts = -lc++\n"
            inputs.property("text", text)
            outputs.file(def)
            doLast { def.get().asFile.writeText(text) }
        }
        compilations.getByName("main").cinterops.create("kolt") {
            definitionFile.set(def)
            tasks.named(interopProcessingTaskName) {
                dependsOn(writeDef)
                // The klib embeds the archives, so a rebuilt C API must re-run cinterop.
                inputs.files(cmakeBuild).withPropertyName("cApi")
            }
        }
    }

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
