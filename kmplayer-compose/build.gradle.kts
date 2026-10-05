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
            compileOnly("org.jetbrains.compose.runtime:runtime:$officialComposeVersion")
            compileOnly("org.jetbrains.compose.ui:ui:$officialComposeVersion")
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

        iosArm64Main.dependencies {
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
