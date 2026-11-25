import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("java")
    id("org.jetbrains.intellij.platform")
}

group = "io.github.donquixote"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    intellijPlatform {
        // Compile against the PhpStorm version you run. The first build downloads it (~1 GB).
        phpstorm("2026.1.4")
        bundledPlugin("com.jetbrains.twig")
    }
}

intellijPlatform {
    pluginConfiguration {
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "261"
            untilBuild = provider { null }
        }
    }
    // Only useful for plugins with settings pages full of text; slows the build down a lot.
    buildSearchableOptions = false
    // ./gradlew verifyPlugin: checks binary compatibility against real IDE builds (downloads them).
    pluginVerification {
        ides {
            create(IntelliJPlatformType.PhpStorm, "2026.1.4")
        }
    }
}
