buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // quickjs-kt 1.0.15 is compiled with Kotlin 2.4 (metadata 2.4.0). AGP's built-in Kotlin defaults to 2.2.10,
        // which cannot read that, so the Kotlin Gradle plugin is raised to the version quickjs-kt itself builds with.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    }
}

plugins {
    id("com.android.application") version "9.5.1"
}

// The plugin files stay in plugins/ (not duplicated). This copies the .js files into a generated assets
// folder as plugins/<id>.js, which is where PluginHost reads them from the APK.
val copyPlugins = tasks.register<Copy>("copyPlugins") {
    from("plugins") { include("*.js") }
    into(layout.buildDirectory.dir("generated/pluginAssets/plugins"))
}
tasks.named("preBuild") { dependsOn(copyPlugins) }

android {
    namespace = "app.lantern.spike.sources"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.lantern.spike.sources"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // src/main/assets/js/ holds the generated JS bundle (npm run build in js/). plugins/ comes from copyPlugins.
    sourceSets["main"].assets.directories.add(layout.buildDirectory.dir("generated/pluginAssets").get().asFile.path)

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation("io.github.dokar3:quickjs-kt:1.0.15")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
