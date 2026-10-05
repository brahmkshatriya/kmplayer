plugins {
    kotlin("multiplatform") apply false
    kotlin("plugin.compose") apply false
    id("com.android.kotlin.multiplatform.library") apply false
    id("org.jetbrains.compose") apply false
    id("dev.brahmkshatriya.compose") apply false
    id("com.vanniktech.maven.publish") apply false
}

val libraryGroup = providers.gradleProperty("GROUP").orElse("dev.brahmkshatriya.kmplayer")
val libraryVersion = providers.gradleProperty("VERSION_NAME").orElse("0.1.0-SNAPSHOT")

allprojects {
    group = libraryGroup.get()
    version = libraryVersion.get()
}

subprojects {
    plugins.withId("maven-publish") {
        extensions.configure<org.gradle.api.publish.PublishingExtension> {
            repositories {
                maven {
                    name = "releaseShard"
                    url = rootProject.layout.buildDirectory.dir("maven-release-shard").get().asFile.toURI()
                }
            }
        }
    }
}
