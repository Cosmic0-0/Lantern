import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    id("org.jetbrains.kotlin.plugin.compose")
    alias(libs.plugins.ksp)
}

// Machine-specific paths come from local.properties (gitignored):
//   lantern.benchDir   the .bench folder; xn-ptts/android/jniLibs/arm64-v8a/libptts_ffi.so is built there
//   lantern.modelDir   the Phonon 31M English checkpoint folder
//   lantern.voicesDir  the phonon-voices folder holding the Everly and Trevor files
// Only packaging the app needs them. Nothing here fails while the project is configured, so :core and :sources
// tasks, and the app's JVM tests, keep working without them; the tasks below say what is missing.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.reader()?.use { load(it) }
}

// A -P<key>=... on the command line wins over local.properties.
fun localDir(key: String): File? =
    (providers.gradleProperty(key).orNull ?: localProperties.getProperty(key))?.takeIf { it.isNotBlank() }?.let { File(it) }

val localPropertiesFile = rootProject.file("local.properties")
val jniLibsDir = localDir("lantern.benchDir")?.let { File(it, "xn-ptts/android/jniLibs") }
val phononLib = jniLibsDir?.let { File(it, "arm64-v8a/libptts_ffi.so") }

// The model files go into the APK as assets/model/..., under the names Phonon expects in its model folder.
// voices/<name>.safetensors comes from the voice's id in the phonon-voices repo, as in spikes/playback/run.sh.
val modelFiles = listOf("config.json", "tokenizer.json", "model.q8.gguf", "default-voice.safetensors")
val voiceIds = mapOf("everly" to "vjFyp2BcCyTlUmYb", "trevor" to "0zLApsZfVLLNaEGl")
val modelDirs = listOf("lantern.modelDir", "lantern.voicesDir")
val modelSources: Map<String, String> = buildMap {
    localDir("lantern.modelDir")?.let { dir -> modelFiles.forEach { put(it, File(dir, it).path) } }
    localDir("lantern.voicesDir")?.let { dir ->
        voiceIds.forEach { (voice, id) -> put("voices/$voice.safetensors", File(dir, "$id.safetensors").path) }
    }
}
val modelSetupProblem: String? = modelDirs.filter { localDir(it) == null }.takeIf { it.isNotEmpty() }?.let {
    "${it.joinToString()} not set in ${localPropertiesFile.path}. Set it to the folder holding the Phonon model files."
}

/**
 * Copies the model and voices into <outputDir>/model/ and writes model/manifest.txt, one line per file:
 * `<path under model/> <size in bytes> <sha256 in hex>`. ModelInstaller checks the installed copies against it.
 */
abstract class PrepareModelAssets : DefaultTask() {
    /** Path under assets/model -> the file it is copied from. */
    @get:Input abstract val sourcePaths: MapProperty<String, String>

    /** Set when local.properties lacks a folder; the task then fails with this message. */
    @get:Input @get:Optional abstract val problem: Property<String>

    // Missing files are fine here: the action reports them by name, which is clearer than Gradle's own message.
    @get:InputFiles @get:PathSensitive(PathSensitivity.ABSOLUTE) abstract val sourceFiles: ConfigurableFileCollection

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun prepare() {
        problem.orNull?.let { throw GradleException(it) }
        val sources = sourcePaths.get().toSortedMap().mapValues { File(it.value) }
        val missing = sources.filterValues { !it.isFile }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Model files missing: " + missing.entries.joinToString { "${it.key} (${it.value.path})" } +
                    ". Check lantern.modelDir and lantern.voicesDir in local.properties.",
            )
        }

        val out = outputDir.get().asFile
        out.deleteRecursively()
        val modelOut = File(out, "model").also { it.mkdirs() }
        val manifest = StringBuilder()
        for ((path, source) in sources) {
            val target = File(modelOut, path)
            target.parentFile.mkdirs()
            source.copyTo(target)
            manifest.append(path).append(' ').append(target.length()).append(' ').append(sha256(target)).append('\n')
        }
        File(modelOut, "manifest.txt").writeText(manifest.toString())
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

val prepareModelAssets = tasks.register<PrepareModelAssets>("prepareModelAssets") {
    sourcePaths.set(modelSources)
    problem.set(modelSetupProblem)
    sourceFiles.from(modelSources.values)
    outputDir.set(layout.buildDirectory.dir("generated/modelAssets"))
}

// libptts_ffi.so sits in the gitignored .bench folder and is a jniLibs source dir, not copied. A missing one would
// give an APK that fails when Phonon loads, so say so while packaging.
val checkPhononLib = tasks.register("checkPhononLib") {
    val lib = phononLib
    val propertiesFile = localPropertiesFile
    doFirst {
        if (lib == null) {
            throw GradleException("lantern.benchDir is not set in ${propertiesFile.path}. Set it to the .bench folder.")
        }
        if (!lib.isFile) {
            throw GradleException(
                "libptts_ffi.so not found at ${lib.path}. " +
                    "Build it with xn-ptts android/build.sh (see .bench/xn-ptts/android/README.md).",
            )
        }
    }
}
tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("JniLibFolders")) dependsOn(checkPhononLib)
}

android {
    namespace = "app.lantern"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.lantern"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += listOf("arm64-v8a") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    jniLibsDir?.let { sourceSets["main"].jniLibs.directories.add(it.path) }

    // The model, voices and manifest stay stored, not deflated, in the APK: the model barely compresses, and
    // the first-launch copy then reads the bytes as they were built.
    androidResources { noCompress += listOf("gguf", "safetensors", "json", "txt") }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(prepareModelAssets, PrepareModelAssets::outputDir)
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

ksp {
    // Exported schemas are committed, so later schema changes can be checked against them.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":sources"))

    implementation(libs.coroutines.android)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)

    implementation(libs.jna) { artifact { type = "aar" } }

    testImplementation(libs.kotlin.test)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
