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

// src/main/resources/js/ holds the generated JS bundle (cheerio and friends for the plugins). It is gitignored,
// so a fresh checkout has to build it once. Failing here is clearer than a missing-resource error at run time,
// and checking in processResources rather than at configuration keeps other modules' tasks working without it.
val jsBundle = file("src/main/resources/js")
tasks.named("processResources") {
    doFirst {
        for (name in listOf("runtime.js", "host.js")) {
            if (!File(jsBundle, name).isFile) {
                throw GradleException("$name is missing from ${jsBundle.absolutePath}. Run npm ci && npm run build in sources/js.")
            }
        }
    }
}

dependencies {
    api(project(":core"))
    implementation(libs.quickjs)
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    testImplementation(libs.kotlin.test)
}

tasks.test {
    // The live tests reach the real sites, so they run only with ./gradlew :sources:test -Plive.
    if (providers.gradleProperty("live").isPresent) systemProperty("lantern.live", "true")
}
