// Plugins are declared here with `apply false` so the app module resolves
// these exact versions. The Kotlin JVM plugin puts the Kotlin Gradle plugin on
// the build classpath, which is the version AGP's built-in Kotlin support
// compiles the app with.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}
