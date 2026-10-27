@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
    id("dev.brahmkshatriya.compose")
    id("com.android.kotlin.multiplatform.library")
    id("com.vanniktech.maven.publish")
}

val composeNativeVersion = providers.gradleProperty("composeNativeVersion").get()
val officialComposeVersion = providers.gradleProperty("composeUiVersion").get()
// Compose 1.11+ removed Intel iOS artifacts. Keep the common API on the last
// supported line; device/Apple Silicon targets still use the current version.
val intelIosComposeVersion = "1.10.3"
val androidCompileSdk = providers.gradleProperty("androidCompileSdk").get().toInt()
val androidMinSdk = providers.gradleProperty("androidMinSdk").get().toInt()
val media3Version = providers.gradleProperty("media3Version").get()

kotlin {
    explicitApi()

    linuxX64()
    linuxArm64()
    mingwX64()
    macosX64()
    macosArm64()
    iosArm64()
    iosSimulatorArm64()
    iosX64()
    jvm { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

    js { browser() }
    wasmJs { browser() }

    android {
        namespace = "dev.brahmkshatriya.kmplayer.compose"
        compileSdk = androidCompileSdk
        minSdk = androidMinSdk
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":kmplayer"))
            compileOnly("org.jetbrains.compose.runtime:runtime:$intelIosComposeVersion")
            compileOnly("org.jetbrains.compose.ui:ui:$intelIosComposeVersion")
        }

        desktopNativeMain.dependencies {
            api("dev.brahmkshatriya.compose.runtime:runtime:$composeNativeVersion")
            api("dev.brahmkshatriya.compose.ui:ui:$composeNativeVersion")
            implementation("dev.brahmkshatriya.compose.foundation:foundation:$composeNativeVersion")
            implementation("dev.brahmkshatriya.compose.desktop:desktop-native:$composeNativeVersion")
        }

        androidMain.dependencies {
            api("org.jetbrains.compose.runtime:runtime:$officialComposeVersion")
            api("org.jetbrains.compose.ui:ui:$officialComposeVersion")
            implementation("androidx.media3:media3-ui:$media3Version")
        }

        val iosMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                api("org.jetbrains.compose.runtime:runtime:$intelIosComposeVersion")
                api("org.jetbrains.compose.ui:ui:$intelIosComposeVersion")
            }
        }
        iosArm64Main {
            dependsOn(iosMain)
            dependencies {
                api("org.jetbrains.compose.runtime:runtime:$officialComposeVersion")
                api("org.jetbrains.compose.ui:ui:$officialComposeVersion")
            }
        }
        iosSimulatorArm64Main {
            dependsOn(iosMain)
            dependencies {
                api("org.jetbrains.compose.runtime:runtime:$officialComposeVersion")
                api("org.jetbrains.compose.ui:ui:$officialComposeVersion")
            }
        }
        iosX64Main { dependsOn(iosMain) }
        jvmMain.dependencies {
            api("org.jetbrains.compose.runtime:runtime:$officialComposeVersion")
            api("org.jetbrains.compose.ui:ui:$officialComposeVersion")
        }

        jsMain.dependencies {
            api("org.jetbrains.compose.runtime:runtime:$officialComposeVersion")
            api("org.jetbrains.compose.ui:ui:$officialComposeVersion")
        }
        wasmJsMain.dependencies {
            api("org.jetbrains.compose.runtime:runtime:$officialComposeVersion")
            api("org.jetbrains.compose.ui:ui:$officialComposeVersion")
        }

        jsMain { kotlin.srcDir("src/webMain/kotlin") }
        wasmJsMain { kotlin.srcDir("src/webMain/kotlin") }
        linuxX64Main { kotlin.srcDir("src/linuxMain/kotlin") }
        linuxArm64Main { kotlin.srcDir("src/linuxMain/kotlin") }
        macosX64Main { kotlin.srcDir("src/macosMain/kotlin") }
        macosArm64Main { kotlin.srcDir("src/macosMain/kotlin") }
    }
}

// Compose Native normally redirects Runtime to its current AndroidX version.
// Intel simulators need the matching older Runtime, which still publishes iosX64.
afterEvaluate {
    configurations.matching { it.name.startsWith("iosX64", ignoreCase = true) }.configureEach {
        resolutionStrategy.dependencySubstitution {
            all {
                val module = requested as? org.gradle.api.artifacts.component.ModuleComponentSelector ?: return@all
                if (module.group in setOf("androidx.compose.runtime", "org.jetbrains.compose.runtime")) {
                    useTarget("androidx.compose.runtime:${module.module}:1.10.5")
                } else if (module.group.startsWith("androidx.") || module.group.startsWith("org.jetbrains.androidx.")) {
                    useTarget("${module.group}:${module.module}:${module.version}")
                }
            }
        }
    }
}
