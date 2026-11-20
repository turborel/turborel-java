// The build of turborel-jdbc, the Java driver for turborel (RULES.md D1).
// Plugins and libraries come from the Gradle Plugin Portal and Maven
// Central alone; their versions are in gradle/libs.versions.toml.

pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "turborel-jdbc"
