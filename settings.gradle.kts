pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "Irio"
include(":app")
include(":desktop")
