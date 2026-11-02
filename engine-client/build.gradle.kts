// 纯 JVM 引擎客户端：CLI / MCP 共用，使用 JDK HttpClient，不引入 OkHttp。
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":shared"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
}
