plugins {
    kotlin("multiplatform") version "2.4.20"
    id("com.android.kotlin.multiplatform.library") version "9.4.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    id("org.jetbrains.compose") version "1.12.1"
}

// The web's runtimes, which the page includes: webpack never sees a klib's resources. kolt's is this checkout's build;
// Filament's is its release's.
val filamentVersion = "0.7.3"
val filamentWasm = tasks.register("downloadFilamentWasm") {
    val dir = layout.buildDirectory.dir("filamentWasm")
    inputs.property("version", filamentVersion)
    outputs.dir(dir)
    doLast {
        listOf("filament-kmp.js", "filament-kmp.wasm").forEach { name ->
            uri("https://github.com/Erkko68/filament-kmp/releases/download/$filamentVersion/$name").toURL().openStream()
                .use { it.copyTo(dir.get().file(name).asFile.outputStream()) }
        }
    }
}
val koltWasm = files("../kotlin/kolt/build/koltWasm").builtBy(gradle.includedBuild("kolt").task(":kotlin:kolt:cmakeBuild_wasm"))

// The scene, and the desktop's and the web's entry points. Android's is :androidApp; iOS's is iosApp's Xcode project,
// around this module's framework.
kotlin {
    jvm()

    android {
        namespace = "io.github.erkko68.kolt.samples.shared"
        compileSdk = 37
        minSdk = 24
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
    }

    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework {
            baseName = "Samples"
            isStatic = true
        }
    }

    js {
        browser { commonWebpackConfig { outputFileName = "samples.js" } }
        binaries.executable()
    }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser { commonWebpackConfig { outputFileName = "samples.js" } }
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation("io.github.erkko68.kolt:kolt:0.1.0")
            implementation("io.github.erkko68.filament:filament-compose:$filamentVersion")
            // api: :androidApp's setContent and the framework's MainViewController take Compose's types.
            api("org.jetbrains.compose.foundation:foundation:1.12.1")
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        jvmMain.dependencies { implementation(compose.desktop.currentOs) }
        webMain { resources.srcDirs(filamentWasm, koltWasm) }
    }
}

compose.desktop {
    application {
        mainClass = "MainKt"
        // Skiko's, Filament's and kolt's loaders call System.load, which JDK 24+ warns about.
        jvmArgs += "--enable-native-access=ALL-UNNAMED"
    }
}
