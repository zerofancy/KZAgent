import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.compose") version "2.4.0"
    id("org.jetbrains.compose") version "1.11.1"
}

val bossTermVersion = "1.2.183"
val bossTermSources = configurations.create("bossTermSources") { isTransitive = false }

kotlin {
    jvmToolchain(17)
    sourceSets.main {
        kotlin.srcDir("../../src/main/kotlin/com/kzagent/kagent/desktop/terminal")
        kotlin.srcDir("../../src/main/kotlin/com/kzagent/kagent/desktop/ui")
        kotlin.include("**/BossTermPoc.kt", "**/BossTermTerminalHandle.kt", "**/TerminalProcessPlatform.kt",
            "**/SessionTerminalManager.kt", "**/TerminalSettingsProfile.kt", "**/SessionTerminalPanel.kt", "**/KZAgentFluentTheme.kt")
        kotlin.include("**/TerminalUnixSession.kt")
    }
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("io.github.compose-fluent:fluent:v0.1.0")
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
    implementation("com.risaboss:bossterm-compose-desktop:$bossTermVersion") {
        exclude(group = "org.jetbrains.compose.desktop", module = "desktop-jvm-macos-arm64")
    }
    // The published compose module exposes core types but declares core as runtime-only.
    implementation("com.risaboss:bossterm-core-jvm:$bossTermVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    implementation("net.java.dev.jna:jna:5.19.1")
    bossTermSources("com.risaboss:bossterm-compose-desktop:$bossTermVersion:sources")
    testImplementation(kotlin("test"))
    testImplementation(compose.desktop.uiTestJUnit4)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("bossterm.sources", bossTermSources.singleFile.absolutePath)
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true }
}

tasks.register<JavaExec>("runPoc") {
    group = "verification"
    description = "Open an isolated BossTerm/Fluent host without KZAgent configuration."
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.kzagent.poc.BossTermPocKt")
    if (providers.gradleProperty("pocSmoke").isPresent) args("--smoke")
}

tasks.register<Test>("uiTest") {
    group = "verification"
    description = "Verify terminal focus and key events inside an isolated Compose test host."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnit()
    include("**/TerminalInputTest.class")
}

compose.desktop {
    application {
        mainClass = "com.kzagent.poc.BossTermPocKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "KZAgentTerminalPoc"
            packageVersion = "1.0.0"
            macOS { bundleID = "com.kzagent.terminalpoc" }
        }
    }
}
