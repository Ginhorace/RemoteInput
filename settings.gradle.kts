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
    // FAIL_ON_PROJECT_REPOS：仓库只能在 settings 里集中声明，任何模块在 build.gradle.kts 里
    // 再写 repositories {} 会直接构建失败。此处 repositoriesMode 与下方 repositories 在
    // Gradle 9.7.1 仍标记为 @Incubating，所以 IDE 会标黄（仅提示，不影响构建）。
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "RemoteInput"
include(":app")
