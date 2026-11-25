import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "twigarcs"

plugins {
    // Downloads a matching JDK automatically if none is installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.jetbrains.intellij.platform.settings") version "2.20.1"
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
    }
}
