import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import org.gradle.api.tasks.Exec
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File

plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
    id("org.jetbrains.compose") version "1.11.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0"
}

group = "com.kzagent"
version = "0.1.0"

val isMacOs = System.getProperty("os.name").lowercase().contains("mac")
val isWindows = System.getProperty("os.name").lowercase().contains("windows")
val windowsPackageVersion = "1.0.0"
val windowsUpgradeUuid = "ca802488-32a0-4ea6-9d9f-d0d6b7c30f64"

fun desktopRenderingJvmArgs(): List<String> = buildList {
    // macOS keeps the software renderer fallback because hardware rendering has
    // previously prevented the Swing-hosted Compose window from appearing.
//    if (isMacOs) {
//        add("-Dskiko.renderApi=SOFTWARE_COMPAT")
//        add("-Dsun.java2d.opengl=false")
//        add("-Dsun.java2d.metal=false")
//    }
}

fun platformDesktopJvmArgs(): List<String> = buildList {
    if (isMacOs) {
        add("-Dapple.awt.application.name=KZAgent")
        add("-Dapple.awt.UIElement=false")
        add("-Xdock:name=KZAgent")
    }
    addAll(desktopRenderingJvmArgs())
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
    // Compose Fluent is introduced as a shell/theme layer first. Material 3 remains
    // available for the markdown renderer and complex dialogs during the staged migration.
    implementation("io.github.compose-fluent:fluent:v0.1.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.43.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-code:0.43.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-coil3:0.43.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.5.0")
    implementation("io.coil-kt.coil3:coil-svg:3.5.0")
    implementation("io.github.vinceglb:filekit-dialogs:0.14.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.22.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "com.kzagent.kagent.MainKt"
        jvmArgs += platformDesktopJvmArgs()

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Exe, TargetFormat.Deb)
            // TextFileCodec supports UTF-32 and East Asian/Windows encodings.
            // Keep their providers in the reduced jlink runtime on every platform.
            modules("java.net.http", "jdk.charsets")
            packageName = "KZAgent"
            packageVersion = windowsPackageVersion
            macOS {
                iconFile.set(project.file("src/main/resources/icons/kzagent.icns"))
            }
            windows {
                iconFile.set(project.file("src/main/resources/icons/kzagent.ico"))
                menuGroup = "ntutn"
                upgradeUuid = windowsUpgradeUuid
            }
            linux {
                modules("jdk.security.auth")
                iconFile.set(project.file("src/main/resources/icons/kzagent.png"))
            }
        }
        buildTypes.release.proguard {
            configurationFiles.from(
                project.file("kotlinx-serialization.pro"),
                project.file("coroutines.pro"),
                project.file("okio.pro"),
                project.file("filekit.pro"),
                project.file("coil3.pro"),
                project.file("jsoup.pro"),
                project.file("compose-fluent.pro"),
                //project.file("decompose.pro")
            )
        }
    }
}

if (isWindows) {
    val windowsInstallerResources = layout.projectDirectory.dir("packaging/windows")

    fun redirectExePackagingTask(
        originalTaskName: String,
        customTaskName: String,
        appImageTaskName: String,
        binaryVariant: String,
    ) {
        val originalTask = tasks.named<AbstractJPackageTask>(originalTaskName)
        val appImageDir = layout.buildDirectory.dir(
            "compose/binaries/$binaryVariant/app/KZAgent",
        )
        val installerFile = layout.buildDirectory.file(
            "compose/binaries/$binaryVariant/exe/KZAgent-$windowsPackageVersion.exe",
        )

        val customTask = tasks.register<Exec>(customTaskName) {
            group = "compose desktop"
            description = "Builds a Windows EXE installer that supports same-version upgrades."
            dependsOn(appImageTaskName)

            inputs.dir(appImageDir)
            inputs.dir(windowsInstallerResources)
            outputs.file(installerFile)

            doFirst {
                val composeTask = originalTask.get()
                val jpackage = project.file(
                    "${composeTask.javaHome.get()}/bin/jpackage.exe",
                )
                check(jpackage.isFile) { "jpackage was not found at $jpackage" }

                val wixDir = composeTask.wixToolsetDir.get().asFile
                val inheritedPath = System.getenv("PATH").orEmpty()
                environment(
                    "PATH",
                    listOf(wixDir.absolutePath, inheritedPath)
                        .filter { it.isNotBlank() }
                        .joinToString(File.pathSeparator),
                )

                val output = installerFile.get().asFile
                project.delete(output)
                output.parentFile.mkdirs()

                executable = jpackage.absolutePath
                setArgs(
                    listOf(
                        "--type", "exe",
                        "--app-image", appImageDir.get().asFile.absolutePath,
                        "--dest", output.parentFile.absolutePath,
                        "--name", "KZAgent",
                        "--app-version", windowsPackageVersion,
                        "--resource-dir", windowsInstallerResources.asFile.absolutePath,
                        "--win-dir-chooser",
                        "--win-menu",
                        "--win-menu-group", "ntutn",
                        "--win-upgrade-uuid", windowsUpgradeUuid,
                    ),
                )
            }
        }

        // Compose Desktop does not expose jpackage's resource directory. Keep its
        // public task names working while delegating EXE creation to the customized
        // invocation above; aggregate packaging tasks therefore need no changes.
        originalTask.configure {
            enabled = false
            dependsOn(customTask)
        }
    }

    afterEvaluate {
        redirectExePackagingTask(
            originalTaskName = "packageExe",
            customTaskName = "packageSameVersionExe",
            appImageTaskName = "createDistributable",
            binaryVariant = "main",
        )
        redirectExePackagingTask(
            originalTaskName = "packageReleaseExe",
            customTaskName = "packageSameVersionReleaseExe",
            appImageTaskName = "createReleaseDistributable",
            binaryVariant = "main-release",
        )
    }
}

tasks.test {
    useJUnitPlatform()
}
