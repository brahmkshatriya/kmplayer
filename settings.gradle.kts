pluginManagement {
    val kotlinVersion = providers.gradleProperty("kotlinVersion").get()
    val androidPluginVersion = providers.gradleProperty("androidPluginVersion").get()
    val composeUiVersion = providers.gradleProperty("composeUiVersion").get()
    val composeNativeVersion = providers.gradleProperty("composeNativeVersion").get()
    val mavenPublishPluginVersion = providers.gradleProperty("mavenPublishPluginVersion").get()

    plugins {
        id("org.jetbrains.kotlin.multiplatform") version kotlinVersion
        id("org.jetbrains.kotlin.plugin.compose") version kotlinVersion
        id("com.android.kotlin.multiplatform.library") version androidPluginVersion
        id("org.jetbrains.compose") version composeUiVersion
        id("dev.brahmkshatriya.compose") version composeNativeVersion
        id("com.vanniktech.maven.publish") version mavenPublishPluginVersion
    }
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://redirector.kotlinlang.org/maven/compose-dev")
    }
}

rootProject.name = "kmplayer"
include(
    ":kmplayer",
    ":kmplayer-compose",
)
