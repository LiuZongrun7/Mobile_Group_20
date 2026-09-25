pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // MPAndroidChart 不在 Maven Central，只在 JitPack 上——它的依赖坐标是
        // com.github.PhilJay:MPAndroidChart，com.github.* 这个前缀就是 JitPack 的标志。
        // 少了这一行，构建会报 "Could not find com.github.PhilJay:MPAndroidChart"，
        // 而报错信息不会告诉你是缺仓库。出处是大纲 §8 的 Open-source reuse 那一行。
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "AndroidApp"
include(":app")
