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
    }
}

rootProject.name = "browseguard-vid-agent"

// M3：android/ 客户端接入构建；协议模型仍由 :shared 单一来源提供。
include(":shared")
include(":android:app")
// M5：CLI / MCP 共用的纯 JVM 引擎 HTTP 客户端（不依赖 Android/OkHttp）。
include(":engine-client")
include(":cli")
// M5：MCP Server，仅 stdio transport，禁止任何 stdout 日志。
include(":mcp")
