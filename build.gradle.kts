import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
    id("org.jetbrains.compose") version "1.11.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0"
}

group = "com.kzagent"
version = "0.1.0"

val isMacOs = System.getProperty("os.name").lowercase().contains("mac")

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
