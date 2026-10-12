plugins {
    kotlin("multiplatform") version "2.4.20"
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
    tasks.withType<Test>().configureEach {
        dependsOn(cmakeBuild)
        systemProperty("java.library.path", cLibDir.get().asFile.path)
    }

    sourceSets.commonTest.dependencies { implementation(kotlin("test")) }
}
