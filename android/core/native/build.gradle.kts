import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

plugins {
    alias(libs.plugins.android.library)
}

/** 64-bit ARM for phones and tablets; 32-bit ARM for TVs that run a 32-bit system on a 64-bit CPU. */
val targetAbis = listOf("arm64-v8a", "armeabi-v7a")
/** The Rust core, shared with the other platforms at the repository root. */
val rustWorkspace = rootProject.layout.projectDirectory.dir("../rust")
val rustSources = rustWorkspace.asFileTree.matching { exclude("target/**") }
val mediaLibs = rootProject.layout.projectDirectory.dir("native/out/jniLibs").asFile
val cargoExecutable = providers.gradleProperty("wplayer.cargo")
    .orElse(providers.environmentVariable("HOME").map { "$it/.cargo/bin/cargo" })

android {
    namespace = "dev.wplayer.core"
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

/** Cross-compiles the Rust core into per-ABI `jniLibs` directories with cargo-ndk. */
abstract class CargoNdkTask @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal
    abstract val workspace: DirectoryProperty

    @get:Input
    abstract val cargo: Property<String>

    @get:Input
    abstract val abis: ListProperty<String>

    @get:Input
    abstract val minSdk: Property<Int>

    @get:Internal
    abstract val ndkDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /** One cargo run per ABI, since whisper.cpp's CPU flags differ between them. */
    @TaskAction
    fun build() {
        for (abi in abis.get()) {
            exec.exec {
                workingDir = workspace.get().asFile
                environment("ANDROID_NDK_HOME", ndkDirectory.get().asFile.absolutePath)
                // whisper.cpp builds with CMake, which finds the NDK through this variable.
                environment("ANDROID_NDK_ROOT", ndkDirectory.get().asFile.absolutePath)
                if (abi == "arm64-v8a") {
                    // ARMv8.2 dot-product and fp16 kernels: every 64-bit Snapdragon since 2019 has them.
                    environment("GGML_CPU_ARM_ARCH", "armv8.2-a+dotprod+fp16")
                }
                // bindgen (rquickjs-sys) parses the NDK headers only with the API level in the
                // target triple. cargo-ndk sets the underscore form of this variable to the bare
                // sysroot; bindgen reads the hyphen form first, so it carries both.
                val (rustTriple, clangTriple) = when (abi) {
                    "arm64-v8a" -> "aarch64-linux-android" to "aarch64-linux-android"
                    "armeabi-v7a" -> "armv7-linux-androideabi" to "armv7a-linux-androideabi"
                    else -> error("no Rust target for $abi")
                }
                // The NDK ships x86_64 host tools on every OS, Apple silicon included.
                val hostTag = if (System.getProperty("os.name").startsWith("Mac")) "darwin-x86_64" else "linux-x86_64"
                val sysroot = ndkDirectory.get().asFile.resolve("toolchains/llvm/prebuilt/$hostTag/sysroot")
                environment("BINDGEN_EXTRA_CLANG_ARGS_$rustTriple", "--target=$clangTriple${minSdk.get()} --sysroot=$sysroot")
                commandLine(
                    cargo.get(), "ndk", "-t", abi,
                    "-P", minSdk.get().toString(),
                    "-o", outputDir.get().asFile.absolutePath,
                    "build", "--release", "-p", "wplayer-core",
                )
            }
        }
    }
}

/** Generates Kotlin bindings from a host build of the core (UniFFI library mode). */
abstract class UniffiBindingsTask @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal
    abstract val workspace: DirectoryProperty

    @get:Input
    abstract val cargo: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val root = workspace.get().asFile
        val extension = if (System.getProperty("os.name").startsWith("Mac")) "dylib" else "so"
        exec.exec {
            workingDir = root
            commandLine(cargo.get(), "build", "-p", "wplayer-core")
        }
        outputDir.get().asFile.deleteRecursively()
        exec.exec {
            workingDir = root
            commandLine(
                cargo.get(), "run", "-q", "-p", "uniffi-bindgen", "--",
                "generate", "--library", "target/debug/libwplayer_core.$extension",
                "--language", "kotlin", "--no-format",
                "--out-dir", outputDir.get().asFile.absolutePath,
            )
        }
    }
}

val cargoBuild = tasks.register<CargoNdkTask>("cargoBuild") {
    sources.from(rustSources)
    workspace.set(rustWorkspace)
    cargo.set(cargoExecutable)
    abis.set(targetAbis)
    minSdk.set(29)
    ndkDirectory.set(androidComponents.sdkComponents.ndkDirectory)
    outputDir.set(layout.buildDirectory.dir("rust/jniLibs"))
}

val uniffiBindings = tasks.register<UniffiBindingsTask>("uniffiBindings") {
    sources.from(rustSources)
    workspace.set(rustWorkspace)
    cargo.set(cargoExecutable)
    outputDir.set(layout.buildDirectory.dir("generated/uniffi/kotlin"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.java?.addGeneratedSourceDirectory(uniffiBindings, UniffiBindingsTask::outputDir)
        variant.sources.jniLibs?.addGeneratedSourceDirectory(cargoBuild, CargoNdkTask::outputDir)
        // libmpv, FFmpeg and friends, produced by native/build.sh.
        if (mediaLibs.isDirectory) {
            variant.sources.jniLibs?.addStaticSourceDirectory(mediaLibs.absolutePath)
        }
    }
}

dependencies {
    api(libs.jna) { artifact { type = "aar" } }
    api(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
