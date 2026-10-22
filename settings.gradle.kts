pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        // Chaquopy publishes its Gradle plugin marker here as well as on Maven Central.
        maven("https://chaquo.com/maven")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Chaquopy resolves its prebuilt Python package wheels from this repository.
        maven("https://chaquo.com/maven")
    }
}

rootProject.name = "SerieCast"
include(":app")
