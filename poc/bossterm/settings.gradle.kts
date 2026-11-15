pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") {
            content { includeGroup("com.github.zerofancy.compose-fluent-ui") }
        }
    }
}
rootProject.name = "kzagent-bossterm-poc"
