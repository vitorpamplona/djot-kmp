plugins {
    `kotlin-dsl`
}

// compileOnly: the root build puts these plugins on the classpath (`apply false`), at the
// versions in libs.versions.toml; here they are only compiled against.
dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.vanniktech.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("djotLibrary") {
            id = "djot.library"
            implementationClass = "DjotLibraryPlugin"
        }
    }
}
