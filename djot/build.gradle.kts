// The Djot parser: the Kotlin stdlib alone, on every target (build-logic's djot.library).
plugins {
    id("djot.library")
}

// Forwards the opt-in differential test's settings to the test JVM (see DifferentialTest).
tasks.withType<Test>().configureEach {
    listOf("djot.diff", "djot.diff.out").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
    outputs.upToDateWhen { System.getProperty("djot.diff") == null }
    testLogging.showStandardStreams = System.getProperty("djot.diff") != null
}
