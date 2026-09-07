pluginManagement {
    val toolchain = java.util.Properties()
    file("toolchain.properties").inputStream().use { toolchain.load(it) }

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version toolchain.getProperty("android.agp")
        id("org.jetbrains.kotlin.plugin.compose") version toolchain.getProperty("kotlin.version")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Meeting Epistemic Gateway"

include(":app")
