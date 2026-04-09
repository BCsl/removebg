pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://jitpack.io")
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral {
            url = uri("https://repo1.maven.org/maven2/")
        }
        maven("https://jitpack.io")
    }
}

rootProject.name = "removebg"
include(":app")
include(":removebg")
