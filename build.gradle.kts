import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.api.tasks.StopExecutionException
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
    id("org.jetbrains.compose") version "1.11.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0"
}

group = "com.kzagent"
version = "0.1.0"

fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
val isMacOs = System.getProperty("os.name").lowercase().contains("mac")

fun desktopRenderingJvmArgs(): List<String> = buildList {
    // macOS keeps the software renderer fallback because hardware rendering has
    // previously prevented the Swing-hosted Compose window from appearing.
    if (isMacOs) {
        add("-Dskiko.renderApi=SOFTWARE_COMPAT")
        add("-Dsun.java2d.opengl=false")
        add("-Dsun.java2d.metal=false")
    }
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
    implementation("io.github.vinceglb:filekit-dialogs:0.14.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "com.kzagent.kagent.MainKt"
        jvmArgs += platformDesktopJvmArgs()

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Exe, TargetFormat.Deb)
            modules("java.net.http")
            packageName = "KZAgent"
            packageVersion = "1.0.0"
            macOS {
                iconFile.set(project.file("src/main/resources/icons/kzagent.icns"))
            }
            windows {
                iconFile.set(project.file("src/main/resources/icons/kzagent.ico"))
            }
            linux {
                modules("jdk.security.auth")
                iconFile.set(project.file("src/main/resources/icons/kzagent.png"))
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<JavaExec>().configureEach {
    if (name == "run") {
        val jbrLauncher = javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
            if (isMacOs) {
                vendor.set(JvmVendorSpec.JETBRAINS)
            }
        }
        javaLauncher.set(jbrLauncher)
        doFirst {
            setExecutable(jbrLauncher.get().executablePath.asFile.absolutePath)
            if (isMacOs && !jvmArgs.orEmpty().contains("-XstartOnFirstThread")) {
                jvmArgs("-XstartOnFirstThread")
            }
        }
        doFirst {
            val packagedApp = layout.buildDirectory.dir("compose/binaries/main/app/KZAgent.app").get().asFile
            if (args.isEmpty() && packagedApp.exists()) {
                val desktopLog = layout.buildDirectory.file("kzagent-desktop.log").get().asFile
                println("Opening desktop app: ${packagedApp.absolutePath}")
                providers.exec {
                    commandLine(
                        "sh",
                        "-lc",
                        "(sleep 0.5; /usr/bin/open -n -a ${shellQuote(packagedApp.absolutePath)} >> ${shellQuote(desktopLog.absolutePath)} 2>&1) >/dev/null 2>&1 &",
                    )
                }.result.get().assertNormalExitValue()
                throw StopExecutionException("Opened desktop app bundle")
            }
        }
        standardInput = System.`in`
        systemProperty("kzagent.allowOpenFallback", "true")
        systemProperty(
            "kzagent.packagedAppPath",
            layout.buildDirectory.dir("compose/binaries/main/app/KZAgent.app").get().asFile.absolutePath,
        )
        jvmArgs(
            "-Dskiko.data.path=${layout.buildDirectory.dir("skiko").get().asFile.absolutePath}",
            "-Dkzagent.logPath=${layout.buildDirectory.file("kzagent-desktop.log").get().asFile.absolutePath}",
        )
    }
}
