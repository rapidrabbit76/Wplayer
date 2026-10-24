import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.ByteArrayOutputStream
import java.util.Properties
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.baselineprofile)
    alias(libs.plugins.aboutlibraries)
}

/**
 * The release key, from keystore.properties (not committed; keep a backup outside the repository).
 * Without it release builds use the debug key, and such an APK cannot update an
 * install signed with the release key.
 */
val releaseSigning: Properties? = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { file ->
    Properties().apply { file.inputStream().use(::load) }
}

/**
 * The version comes from the latest `vMAJOR.MINOR.PATCH` git tag (made by
 * scripts/release.sh). Builds after a tag are named like `0.2.0-3-gabc1234`;
 * before the first tag the version is 0.1.0.
 */
val gitDescribe: String = providers.exec {
    commandLine("git", "describe", "--tags", "--match", "v[0-9]*.[0-9]*.[0-9]*")
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim().removePrefix("v").ifEmpty { "0.1.0" }

/** Always increases with the tag: 1.2.3 -> 1_002_003. */
/**
 * Lets the app read this repository's releases while it is private: a
 * fine-grained token that may only read its contents, passed by
 * scripts/release.sh. Public repositories need none.
 */
val updateToken: String = providers.environmentVariable("WPLAYER_UPDATE_TOKEN").getOrElse("")

val releaseVersionCode: Int = gitDescribe.substringBefore('-').split('.').map(String::toInt)
    .let { (major, minor, patch) -> major * 1_000_000 + minor * 1_000 + patch }

android {
    namespace = "dev.wplayer.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.wplayer"
        minSdk = 29
        targetSdk = 37
        versionCode = releaseVersionCode
        versionName = gitDescribe
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Where in-app updates come from; a fork sets wplayer.updateRepository=owner/repo in
        // gradle.properties, since only APKs signed with its own key can update its installs.
        val updateRepository = providers.gradleProperty("wplayer.updateRepository").getOrElse("rapidrabbit76/Wplayer")
        buildConfigField("String", "UPDATE_REPOSITORY", "\"$updateRepository\"")
        buildConfigField("String", "UPDATE_TOKEN", "\"$updateToken\"")
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (releaseSigning != null) {
                signingConfigs.getByName("release")
            } else {
                logger.warn("keystore.properties not found: release APK is signed with the debug key")
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // English by default (values/), Korean in values-ko/; the generated locale config
    // lists them for the system's per-app language setting. Libraries' translations
    // into other languages are dropped, so their text matches the app's language.
    androidResources {
        generateLocaleConfig = true
        localeFilters += listOf("en", "ko")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Warnings fail the build, so they are fixed (or suppressed with a reason in lint.xml) as they appear.
    lint {
        warningsAsErrors = true
        abortOnError = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Robolectric's SDK 36 runtime reaches into FileDescriptor internals.
            it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

/**
 * The AboutLibraries configuration for the open-source licenses screen: the
 * native libraries and licenses in aboutlibraries/ (with the versions from
 * native/versions.env filled in), plus every Rust crate the core links, read
 * from `cargo metadata`. Gradle dependencies are collected by the plugin itself.
 */
abstract class LibraryDefinitionsTask @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val staticDefinitions: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val nativeVersions: RegularFileProperty

    /** Cargo.lock and the manifests: what decides the crates. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val cargoManifests: ConfigurableFileCollection

    @get:Internal
    abstract val workspace: DirectoryProperty

    @get:Input
    abstract val cargo: Property<String>

    @get:Input
    abstract val rootCrate: Property<String>

    @get:Input
    abstract val targetTriple: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        val versions = nativeVersions.get().asFile.readLines()
            .map { it.substringBefore('#').trim() }
            .filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        staticDefinitions.get().asFile.walkTopDown().filter { it.isFile }.forEach { file ->
            val target = out.resolve(file.relativeTo(staticDefinitions.get().asFile))
            target.parentFile.mkdirs()
            target.writeText(versions.entries.fold(file.readText()) { text, (key, value) -> text.replace("\${$key}", value) })
        }
        rustCrates().forEach { crate ->
            out.resolve("libraries/crate-${crate["name"]}-${crate["artifactVersion"]}.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(crate)))
        }
    }

    /** The crates reachable from [rootCrate] through normal dependencies on the Android target. */
    @Suppress("UNCHECKED_CAST")
    private fun rustCrates(): List<Map<String, Any>> {
        val stdout = ByteArrayOutputStream()
        exec.exec {
            workingDir = workspace.get().asFile
            commandLine(cargo.get(), "metadata", "--format-version", "1", "--locked", "--filter-platform", targetTriple.get())
            standardOutput = stdout
        }
        val metadata = JsonSlurper().parseText(stdout.toString()) as Map<String, Any?>
        val packages = (metadata["packages"] as List<Map<String, Any?>>).associateBy { it["id"] as String }
        val nodes = ((metadata["resolve"] as Map<String, Any?>)["nodes"] as List<Map<String, Any?>>).associateBy { it["id"] as String }
        val root = packages.values.first { it["name"] == rootCrate.get() }["id"] as String
        val reached = mutableSetOf<String>()
        val pending = ArrayDeque(listOf(root))
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            if (!reached.add(id)) continue
            for (dep in nodes.getValue(id)["deps"] as List<Map<String, Any?>>) {
                val kinds = dep["dep_kinds"] as List<Map<String, Any?>>
                if (kinds.any { it["kind"] == null }) pending += dep["pkg"] as String
            }
        }
        return (reached - root).map(packages::getValue).sortedBy { it["name"] as String }.map { crate ->
            val name = crate["name"] as String
            buildMap {
                // Some crates come in several versions.
                put("uniqueId", "crates.io:$name:${crate["version"]}")
                put("name", name)
                put("artifactVersion", crate["version"] as String)
                (crate["description"] as String?)?.let { put("description", it.trim().replace(Regex("\\s+"), " ")) }
                put("website", (crate["repository"] ?: crate["homepage"] ?: "https://crates.io/crates/$name") as String)
                val authors = (crate["authors"] as List<String>?).orEmpty().map { it.substringBefore('<').trim() }.filter(String::isNotEmpty)
                put("developers", authors.map { mapOf("name" to it) })
                put("licenses", spdxIds(crate["license"] as String?))
            }
        }
    }

    /** The license ids in an SPDX expression such as `(MIT OR Apache-2.0) AND Unicode-3.0`. */
    private fun spdxIds(expression: String?): List<String> = expression.orEmpty()
        .split(Regex("\\s+(?:OR|AND)\\s+|/|\\(|\\)"))
        .map { it.substringBefore(" WITH ").trim() }
        .filter(String::isNotEmpty)
        .distinct()
}

val libraryDefinitions = tasks.register<LibraryDefinitionsTask>("libraryDefinitions") {
    val rustWorkspace = rootProject.layout.projectDirectory.dir("rust")
    staticDefinitions.set(layout.projectDirectory.dir("aboutlibraries"))
    nativeVersions.set(rootProject.layout.projectDirectory.file("native/versions.env"))
    cargoManifests.from(rustWorkspace.file("Cargo.lock"), rustWorkspace.asFileTree.matching { include("**/Cargo.toml"); exclude("target/**") })
    workspace.set(rustWorkspace)
    cargo.set(
        providers.gradleProperty("wplayer.cargo").orElse(providers.environmentVariable("HOME").map { "$it/.cargo/bin/cargo" }),
    )
    rootCrate.set("wplayer-core")
    targetTriple.set("aarch64-linux-android")
    outputDir.set(layout.buildDirectory.dir("generated/aboutlibraries-config"))
}

// The plugin resolves the classpaths while configuring (for the configuration cache), so
// Gradle reports "Configuration '...Classpath' was resolved during configuration time" when
// the cache is rebuilt. Upstream calls this informational: mikepenz/AboutLibraries#1369.
aboutLibraries {
    collect {
        configPath.set(libraryDefinitions.flatMap { it.outputDir })
    }
    export {
        // Fields the screen does not show.
        excludeFields.addAll("funding")
    }
}

dependencies {
    implementation(project(":core:native"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.navigation.suite)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.material3.adaptive)
    implementation(libs.compose.material3.adaptive.layout)
    implementation(libs.compose.material3.adaptive.navigation3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.lifecycle.viewmodel.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.androidx.compiler)
    implementation(libs.work.runtime)
    implementation(libs.media3.session)
    implementation(libs.media3.common)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    // Installs the bundled Baseline Profile on devices without Play Store profile delivery (sideloaded APKs).
    implementation(libs.profileinstaller)
    implementation(libs.aboutlibraries.compose.m3)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.telephoto.zoomable.image.coil3)
    baselineProfile(project(":baselineprofile"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.work.testing)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
