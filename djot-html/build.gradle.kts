// The HTML renderer for djot's AST: djot and the Kotlin stdlib alone, on every target
// (build-logic's djot.library). The conformance suite lives here, as its cases are written in HTML.
plugins {
    id("djot.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // `api`: renderHTML takes djot's Doc, so its callers need djot's classes.
            api(project(":djot"))
        }
    }
}

// Forwards the opt-in differential test's settings to the test JVM (see DifferentialTest).
tasks.withType<Test>().configureEach {
    listOf("djot.diff", "djot.diff.out").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
    outputs.upToDateWhen { System.getProperty("djot.diff") == null }
    testLogging.showStandardStreams = System.getProperty("djot.diff") != null
}
