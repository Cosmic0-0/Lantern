plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Plain Kotlin, so the plugin host and network rules are tested on the laptop. The app uses quickjs-kt's
// Android build of the same library.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    api(project(":core"))
    implementation(libs.quickjs)
    implementation(libs.coroutines.core)
    testImplementation(libs.kotlin.test)
}
