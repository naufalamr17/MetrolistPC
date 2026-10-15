pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        maven("https://jitpack.io") {
            content { includeGroup("com.github.MetrolistGroup.innertubex") }
        }
    }
}
rootProject.name = "MetrolistDesktop"
