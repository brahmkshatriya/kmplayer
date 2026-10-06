@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import java.util.Locale

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("com.vanniktech.maven.publish")
}

val androidCompileSdk = providers.gradleProperty("androidCompileSdk").get().toInt()
val androidMinSdk = providers.gradleProperty("androidMinSdk").get().toInt()
val coroutinesVersion = providers.gradleProperty("coroutinesVersion").get()
val kotlinxBrowserVersion = providers.gradleProperty("kotlinxBrowserVersion").get()
val media3Version = providers.gradleProperty("media3Version").get()
val hlsJsVersion = providers.gradleProperty("hlsJsVersion").get()

val gstBridgeSources = files(
    "src/nativeInterop/cinterop/kmplayer_gst_bridge.c",
    "src/nativeInterop/cinterop/include/kmplayer_gst_bridge.h",
)

private fun String.environmentStem(): String =
    replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").uppercase(Locale.US)

private fun String.commandParts(): List<String> =
    trim().split(Regex("\\s+")).filter(String::isNotBlank)

private fun nativeLibraryDirs(targetName: String): List<String> {
    val stem = targetName.environmentStem()
    val targetSpecific = providers.environmentVariable("KMPLAYER_${stem}_LIBRARY_DIRS").orNull
        ?.split(File.pathSeparatorChar)
        ?.filter(String::isNotBlank)
        .orEmpty()
    if (targetSpecific.isNotEmpty()) return targetSpecific

    if (targetName.startsWith("linux")) {
        val generic = providers.environmentVariable("LINUX_NATIVE_LIBRARY_DIRS").orNull
            ?.split(File.pathSeparatorChar)
            ?.filter(String::isNotBlank)
            .orEmpty()
        if (generic.isNotEmpty()) return generic

        val hostOs = System.getProperty("os.name").lowercase()
        val hostArch = System.getProperty("os.arch").lowercase()
        val hostMatchesTarget = hostOs.contains("linux") && when (targetName) {
            "linuxX64" -> hostArch == "x86_64" || hostArch == "amd64"
            "linuxArm64" -> hostArch == "aarch64" || hostArch == "arm64"
            else -> false
        }
        if (hostMatchesTarget) {
            val pkgConfigDir = runCatching {
                providers.exec {
                    commandLine("pkg-config", "--variable=libdir", "gstreamer-1.0")
                    isIgnoreExitValue = true
                }.standardOutput.asText.get().trim()
            }.getOrNull()
            if (!pkgConfigDir.isNullOrBlank()) return listOf(pkgConfigDir)
        }

        return when (targetName) {
            "linuxArm64" -> listOf("/usr/lib/aarch64-linux-gnu", "/usr/lib64", "/usr/lib")
            else -> listOf("/usr/lib/x86_64-linux-gnu", "/usr/lib64", "/usr/lib")
        }
    }
    return emptyList()
}

private fun KotlinNativeTarget.configureNativeLibraryDirs() {
    val stem = name.environmentStem()
    val linkerOptions = buildList {
        addAll(nativeLibraryDirs(name).map { "-L$it" })
        addAll(
            providers.environmentVariable("KMPLAYER_${stem}_LINKER_OPTS").orNull
                ?.commandParts()
                .orEmpty(),
        )
    }.toTypedArray()
    if (linkerOptions.isNotEmpty()) binaries.all { linkerOpts(*linkerOptions) }
}

private fun konanDependency(name: String): File? =
    File(System.getProperty("user.home"), ".konan/dependencies/$name")
        .takeIf(File::isDirectory)

private fun konanLinuxClang(): File? =
    File(System.getProperty("user.home"), ".konan/dependencies")
        .listFiles()
        ?.asSequence()
        ?.filter { it.isDirectory && it.name.matches(Regex("llvm-\\d+-x86_64-linux-essentials-.*")) }
        ?.map { it.resolve("bin/clang") }
        ?.filter(File::isFile)
        ?.maxByOrNull { it.parentFile.parentFile.name }

private fun konanLinuxLlvmAr(): File? =
    konanLinuxClang()?.parentFile?.resolve("llvm-ar")?.takeIf(File::isFile)

private fun mingwCrossCompiler(): List<String>? {
    val sysroot = konanDependency("msys2-mingw-w64-x86_64-2") ?: return null
    val clang = konanLinuxClang() ?: return null
    return listOf(
        clang.absolutePath,
        "--target=x86_64-w64-windows-gnu",
        "--sysroot=${sysroot.absolutePath}",
    )
}

private fun mingwCrossArchiver(): List<String>? =
    konanLinuxLlvmAr()?.let { listOf(it.absolutePath) }

private fun KotlinNativeTarget.configureMediaFoundationInterop() {
    val targetName = name
    val stem = targetName.environmentStem()
    val outputDir = project.layout.buildDirectory.dir("native/mediafoundation/$targetName")
    val objectFile = outputDir.map { it.file("kmplayer_mf_bridge.o") }
    val archiveFile = outputDir.map { it.file("libkmplayer_mf_bridge.a") }
    val suffix = targetName.replaceFirstChar(Char::uppercaseChar)
    val configuredCompiler = project.providers.environmentVariable("KMPLAYER_${stem}_CXX")
    val configuredArchiver = project.providers.environmentVariable("KMPLAYER_${stem}_AR")
    val hostOs = System.getProperty("os.name").lowercase()
    val defaultCompiler = when {
        hostOs.contains("linux") -> mingwCrossCompiler()
        hostOs.contains("windows") -> listOf("clang++")
        else -> null
    }
    val defaultArchiver = when {
        hostOs.contains("linux") -> mingwCrossArchiver()
        hostOs.contains("windows") -> listOf("llvm-ar")
        else -> null
    }
    val compilerAvailable = configuredCompiler.isPresent || defaultCompiler != null
    val archiverAvailable = configuredArchiver.isPresent || defaultArchiver != null
    val bridgeSources = files(
        "src/nativeInterop/cinterop/kmplayer_mf_bridge.cpp",
        "src/nativeInterop/cinterop/include/kmplayer_mf_bridge.h",
        "src/nativeInterop/cinterop/include/kmplayer_mfmediaengine_abi.h",
    )

    val compileBridge = project.tasks.register<Exec>("compileKMPlayerMediaFoundationBridge$suffix") {
        inputs.files(bridgeSources)
        outputs.file(objectFile)
        onlyIf("a compatible Windows C++ compiler is configured for $targetName") { compilerAvailable }
        doFirst {
            outputDir.get().asFile.mkdirs()
            val compiler = configuredCompiler.orNull?.commandParts()?.takeIf(List<String>::isNotEmpty)
                ?: defaultCompiler
                ?: throw GradleException("Set KMPLAYER_${stem}_CXX for $targetName")
            val extraFlags = project.providers.environmentVariable("KMPLAYER_${stem}_CXXFLAGS").orNull
                ?.commandParts()
                .orEmpty()
            commandLine(
                compiler + listOf(
                    "-x", "c++",
                    "-std=c++17",
                    "-O2",
                    "-D_WIN32_WINNT=0x0A00",
                    "-DWINVER=0x0A00",
                    "-DWIN32_LEAN_AND_MEAN",
                    "-DNOMINMAX",
                    "-Isrc/nativeInterop/cinterop/include",
                ) + extraFlags + listOf(
                    "-c",
                    "src/nativeInterop/cinterop/kmplayer_mf_bridge.cpp",
                    "-o",
                    objectFile.get().asFile.absolutePath,
                ),
            )
        }
    }

    val archiveBridge = project.tasks.register<Exec>("archiveKMPlayerMediaFoundationBridge$suffix") {
        dependsOn(compileBridge)
        inputs.file(objectFile)
        outputs.file(archiveFile)
        onlyIf("a compatible Windows C++ compiler and archiver are configured for $targetName") {
            compilerAvailable && archiverAvailable
        }
        doFirst {
            val archiver = configuredArchiver.orNull?.commandParts()?.takeIf(List<String>::isNotEmpty)
                ?: defaultArchiver
                ?: throw GradleException("Set KMPLAYER_${stem}_AR for $targetName")
            commandLine(
                archiver + listOf(
                    "rcs",
                    archiveFile.get().asFile.absolutePath,
                    objectFile.get().asFile.absolutePath,
                ),
            )
        }
    }

    compilations.getByName("main") {
        cinterops.create("KMPlayerMediaFoundation") {
            defFile(project.file("src/nativeInterop/cinterop/kmplayer-mediafoundation-mingwX64.def"))
        }
    }

    project.tasks.matching { it.name == "cinteropKMPlayerMediaFoundation$suffix" }.configureEach {
        dependsOn(archiveBridge)
        inputs.file(archiveFile)
        onlyIf("a compatible Windows native bridge toolchain is configured") {
            compilerAvailable && archiverAvailable
        }
    }
}

private fun KotlinNativeTarget.configureGStreamerInterop() {
    val targetName = name
    val stem = targetName.environmentStem()
    val configuredLibraryDirs = project.providers.environmentVariable("KMPLAYER_${stem}_LIBRARY_DIRS").orNull
        ?.split(File.pathSeparatorChar)
        ?.filter(String::isNotBlank)
        .orEmpty()
    val libraryDirs = configuredLibraryDirs.ifEmpty {
        if (targetName == "linuxX64") listOf("/usr/lib") else emptyList()
    }
    if (libraryDirs.isNotEmpty()) {
        binaries.all {
            linkerOpts(*libraryDirs.map { "-L$it" }.toTypedArray())
        }
    }
    val outputDir = project.layout.buildDirectory.dir("native/gstreamer/$targetName")
    val objectFile = outputDir.map { it.file("kmplayer_gst_bridge.o") }
    val archiveFile = outputDir.map { it.file("libkmplayer_gst_bridge.a") }
    val suffix = targetName.replaceFirstChar(Char::uppercaseChar)

    val hostOs = System.getProperty("os.name").lowercase()
    val hostArch = System.getProperty("os.arch").lowercase()
    val hostIsArm64 = hostArch == "aarch64" || hostArch == "arm64"
    val defaultCompiler = when {
        targetName == "linuxX64" && hostOs.contains("linux") && !hostIsArm64 -> listOf("cc")
        targetName == "linuxArm64" && hostOs.contains("linux") && hostIsArm64 -> listOf("cc")
        else -> null
    }

    val configuredCompiler = project.providers.environmentVariable("KMPLAYER_${stem}_CC")
    val prebuiltArchive = project.providers.environmentVariable("KMPLAYER_${stem}_PREBUILT_ARCHIVE")
    val usesPrebuiltArchive = prebuiltArchive.isPresent
    val compilerAvailable = configuredCompiler.isPresent || defaultCompiler != null

    val compileBridge = project.tasks.register<Exec>("compileKMPlayerGStreamerBridge$suffix") {
        inputs.files(gstBridgeSources)
        outputs.file(objectFile)
        onlyIf("a compatible GStreamer C compiler is configured for $targetName") {
            compilerAvailable && !usesPrebuiltArchive
        }
        doFirst {
            outputDir.get().asFile.mkdirs()
            val compiler = configuredCompiler.orNull?.commandParts()?.takeIf(List<String>::isNotEmpty)
                ?: defaultCompiler
                ?: throw GradleException("Set KMPLAYER_${stem}_CC for $targetName")
            val configuredFlags = project.providers.environmentVariable("KMPLAYER_${stem}_CFLAGS").orNull
                ?.commandParts()
                .orEmpty()
            val pkgFlags = if (configuredFlags.isEmpty()) {
                project.providers.exec {
                    commandLine(
                        "pkg-config",
                        "--cflags",
                        "gstreamer-1.0",
                        "gstreamer-video-1.0",
                        "gstreamer-app-1.0",
                    )
                }.standardOutput.asText.get().trim().commandParts()
            } else {
                emptyList()
            }
            commandLine(
                compiler + listOf(
                    "-std=c11",
                    "-O2",
                    "-fPIC",
                    "-Isrc/nativeInterop/cinterop/include",
                ) + configuredFlags + pkgFlags + listOf(
                    "-c",
                    "src/nativeInterop/cinterop/kmplayer_gst_bridge.c",
                    "-o",
                    objectFile.get().asFile.absolutePath,
                ),
            )
        }
    }

    val archiveBridge = project.tasks.register<Exec>("archiveKMPlayerGStreamerBridge$suffix") {
        dependsOn(compileBridge)
        inputs.file(objectFile)
        outputs.file(archiveFile)
        onlyIf("a compatible GStreamer C compiler is configured for $targetName") {
            compilerAvailable && !usesPrebuiltArchive
        }
        doFirst {
            val archiver = project.providers.environmentVariable("KMPLAYER_${stem}_AR").orNull
                ?.commandParts()
                ?.takeIf(List<String>::isNotEmpty)
                ?: listOf("ar")
            commandLine(
                archiver + listOf(
                    "rcs",
                    archiveFile.get().asFile.absolutePath,
                    objectFile.get().asFile.absolutePath,
                ),
            )
        }
    }

    val stagePrebuiltBridge = project.tasks.register<Copy>("stagePrebuiltKMPlayerGStreamerBridge$suffix") {
        onlyIf("a prebuilt GStreamer bridge is configured for $targetName") { usesPrebuiltArchive }
        from(prebuiltArchive)
        into(outputDir)
        rename { "libkmplayer_gst_bridge.a" }
    }

    compilations.getByName("main") {
        cinterops.create("KMPlayerGStreamer") {
            defFile(project.file("src/nativeInterop/cinterop/kmplayer-gstreamer-$targetName.def"))
        }
    }

    project.tasks.matching { it.name == "cinteropKMPlayerGStreamer$suffix" }.configureEach {
        if (usesPrebuiltArchive) {
            dependsOn(stagePrebuiltBridge)
        } else {
            dependsOn(archiveBridge)
        }
        inputs.file(archiveFile)
        onlyIf("a compatible GStreamer bridge toolchain is configured for $targetName") {
            compilerAvailable || usesPrebuiltArchive
        }
    }
}

kotlin {
    explicitApi()

    linuxX64 {
        configureGStreamerInterop()
        configureNativeLibraryDirs()
    }
    linuxArm64 {
        configureGStreamerInterop()
        configureNativeLibraryDirs()
    }
    macosX64()
    macosArm64()
    iosArm64()
    mingwX64 { configureMediaFoundationInterop() }

    js {
        browser()
    }
    wasmJs {
        browser()
    }

    android {
        namespace = "dev.brahmkshatriya.kmplayer"
        compileSdk = androidCompileSdk
        minSdk = androidMinSdk
        withHostTest {}
    }

    sourceSets {
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
        }

        val webMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                api("org.jetbrains.kotlinx:kotlinx-browser:$kotlinxBrowserVersion")
            }
        }

        androidMain.dependencies {
            api("androidx.media3:media3-exoplayer:$media3Version")
            api("androidx.media3:media3-exoplayer-hls:$media3Version")
        }

        jsMain {
            dependsOn(webMain)
            dependencies {
                implementation(npm("hls.js", hlsJsVersion))
            }
        }
        wasmJsMain {
            dependsOn(webMain)
            dependencies {
                implementation(npm("hls.js", hlsJsVersion))
            }
        }

        linuxX64Main { kotlin.srcDir("src/linuxMain/kotlin") }
        linuxArm64Main { kotlin.srcDir("src/linuxMain/kotlin") }
        macosX64Main { kotlin.srcDir("src/macosMain/kotlin") }
        macosArm64Main { kotlin.srcDir("src/macosMain/kotlin") }

    }
}
