pluginManagement {
    // djot.library: what the published modules (djot, djot-html) share.
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "djot-kmp"
include(":djot")
include(":djot-html")
