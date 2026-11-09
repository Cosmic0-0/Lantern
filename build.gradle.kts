buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // quickjs-kt is compiled with Kotlin 2.4, and AGP 9.5.1's built-in Kotlin is 2.2.10, so the Kotlin Gradle
        // plugin is raised here, once for every module (docs/decisions/toolchain.md).
        classpath(libs.kotlin.gradle.plugin)
        classpath(libs.compose.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.ksp) apply false
}
