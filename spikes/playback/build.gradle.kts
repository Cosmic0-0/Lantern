plugins {
    id("com.android.application") version "9.5.1"
}

// libptts_ffi.so lives in the gitignored .bench folder; it is added as a jniLibs source dir, not copied.
val jniLibsDir = file("../../.bench/xn-ptts/android/jniLibs")
val pttsLib = File(jniLibsDir, "arm64-v8a/libptts_ffi.so")
if (!pttsLib.isFile) {
    throw GradleException(
        "libptts_ffi.so not found at ${pttsLib.absolutePath}. " +
            "Build it with xn-ptts android/build.sh (see .bench/xn-ptts/android/README.md)."
    )
}

android {
    namespace = "app.lantern.spike.playback"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.lantern.spike.playback"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    sourceSets["main"].jniLibs.directories.add(jniLibsDir.path)

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation("net.java.dev.jna:jna:5.17.0@aar")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
}
