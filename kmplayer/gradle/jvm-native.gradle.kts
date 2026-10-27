// Package the system media bridges; Linux JVM uses the installed GStreamer runtime.
import org.gradle.api.tasks.bundling.Jar

val jvmBridgeHost = System.getProperty("os.name").lowercase()
val jvmBridgeRoot = layout.buildDirectory.dir("generated/jvmNative/native")
val jvmPrebuiltRoot = providers.environmentVariable("KMPLAYER_JVM_NATIVE_DIR")
val jvmNativeIncludes = file("src/nativeInterop/cinterop/include")
val jvmNativeSource = file("src/nativeInterop/cinterop")
val jvmNativeKonan = File(System.getProperty("user.home"), ".konan/dependencies")
val jvmNativeLlvm = jvmNativeKonan.listFiles()?.filter { it.name.matches(Regex("llvm-\\d+-x86_64-linux-essentials-.*")) }
    ?.maxByOrNull { it.name }?.resolve("bin/clang")
val jvmNativeMingw = jvmNativeKonan.resolve("msys2-mingw-w64-x86_64-2")

val buildJvmWindows = tasks.register<Exec>("buildJvmNativeBridgeWindowsX64") {
    val output = jvmBridgeRoot.map { it.file("windows-x86_64/kmplayer_mf.dll") }
    inputs.files(jvmNativeSource.resolve("kmplayer_mf_bridge.cpp"))
    inputs.dir(jvmNativeIncludes)
    outputs.file(output)
    doFirst {
        output.get().asFile.parentFile.mkdirs()
        val common = listOf("-shared", "-std=c++17", "-O2", "-DKMPLAYER_MF_SHARED", "-D_WIN32_WINNT=0x0A00",
            "-DWINVER=0x0A00", "-DWIN32_LEAN_AND_MEAN", "-DNOMINMAX", "-I${jvmNativeIncludes.absolutePath}",
            jvmNativeSource.resolve("kmplayer_mf_bridge.cpp").absolutePath)
        val libraries = listOf("-lmfplat", "-lmfuuid", "-lole32", "-loleaut32", "-ld3d11", "-ldxgi", "-luuid")
        val override = providers.environmentVariable("KMPLAYER_JVM_WINDOWS_X64_CXX").orNull?.trim()?.split(Regex("\\s+"))
        val compiler = override ?: if (jvmBridgeHost.contains("windows")) listOf("g++") else {
            check(jvmNativeLlvm?.isFile == true && jvmNativeMingw.isDirectory) {
                "Set KMPLAYER_JVM_WINDOWS_X64_CXX to a MinGW C++ compiler, or supply KMPLAYER_JVM_NATIVE_DIR"
            }
            listOf(jvmNativeLlvm!!.absolutePath, "--driver-mode=g++", "--target=x86_64-w64-windows-gnu", "--sysroot=${jvmNativeMingw.absolutePath}")
        }
        commandLine(compiler + common + listOf("-static", "-static-libgcc", "-static-libstdc++") + libraries +
            listOf("-Wl,--start-group", "-lstdc++", "-lgcc_eh", "-lgcc", "-lwinpthread", "-Wl,--end-group",
                "-o", output.get().asFile.absolutePath))
    }
}

val buildJvmMac = listOf("X64" to "x86_64", "Arm64" to "aarch64").map { (suffix, arch) ->
    tasks.register<Exec>("buildJvmNativeBridgeMacos$suffix") {
        val output = jvmBridgeRoot.map { it.file("macos-$arch/libkmplayer_av.dylib") }
        inputs.files(jvmNativeSource.resolve("kmplayer_av_bridge.m"), jvmNativeIncludes.resolve("kmplayer_av_bridge.h"))
        outputs.file(output)
        doFirst {
            output.get().asFile.parentFile.mkdirs()
            val compiler = providers.environmentVariable("KMPLAYER_JVM_MACOS_${suffix.uppercase()}_CC").orNull
                ?.trim()?.split(Regex("\\s+")) ?: listOf("xcrun", "--sdk", "macosx", "clang")
            commandLine(compiler + listOf("-dynamiclib", "-O2", "-fobjc-arc", "-fblocks", "-arch", if (arch == "aarch64") "arm64" else arch,
                "-mmacosx-version-min=11.0", "-I${jvmNativeIncludes.absolutePath}",
                jvmNativeSource.resolve("kmplayer_av_bridge.m").absolutePath,
                "-framework", "Foundation", "-framework", "AVFoundation", "-framework", "CoreMedia",
                "-framework", "CoreVideo", "-framework", "CoreImage", "-framework", "CoreGraphics",
                "-Wl,-install_name,@rpath/libkmplayer_av.dylib", "-o", output.get().asFile.absolutePath))
        }
    }
}

val jvmBridgeFiles = listOf("windows-x86_64/kmplayer_mf.dll", "macos-x86_64/libkmplayer_av.dylib", "macos-aarch64/libkmplayer_av.dylib")
val jvmRequiredBridges = providers.gradleProperty("kmplayer.requireAllJvmNatives").map(String::toBoolean).orElse(false)

val verifyJvmNativeBridges = tasks.register("verifyJvmNativeBridges") {
    mustRunAfter(buildJvmWindows)
    mustRunAfter(buildJvmMac)
    doLast {
        val missing = jvmBridgeFiles.filter { relative ->
            jvmPrebuiltRoot.orNull?.let { File(it, relative).isFile } != true &&
                !jvmBridgeRoot.get().file(relative).asFile.isFile
        }
        check(missing.isEmpty()) { "Missing JVM native bridges: $missing. Build on Windows/macOS or set KMPLAYER_JVM_NATIVE_DIR." }
    }
}

tasks.named<ProcessResources>("jvmProcessResources") {
    // Prebuilt release files take precedence over locally generated files.
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    jvmPrebuiltRoot.orNull?.let { from(it) { into("native") } }
    from(jvmBridgeRoot) { into("native") }
    if (jvmPrebuiltRoot.orNull?.let { File(it, jvmBridgeFiles[0]).isFile } != true &&
        (jvmBridgeHost.contains("windows") || jvmBridgeHost.contains("linux") && jvmNativeLlvm?.isFile == true && jvmNativeMingw.isDirectory)) {
        dependsOn(buildJvmWindows)
    }
    if (jvmBridgeHost.contains("mac")) dependsOn(buildJvmMac)
    if (jvmRequiredBridges.get()) dependsOn(verifyJvmNativeBridges)
}

tasks.named<Jar>("jvmJar") {
    if (jvmRequiredBridges.get()) dependsOn(verifyJvmNativeBridges)
}
