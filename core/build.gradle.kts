plugins {
    id("org.jetbrains.kotlin.jvm")
}

// No Android here: core is plain Kotlin, tested on the laptop and reusable by a laptop app later.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    testImplementation(libs.kotlin.test)
}
