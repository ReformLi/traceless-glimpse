// MCP Server：仅 StdioServerTransport，复用 :engine-client 与 :shared。
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    applicationName = "bgv-mcp"
    mainClass.set("com.hpu.browseguard.mcp.MainKt")
}

dependencies {
    implementation(project(":engine-client"))
    implementation(libs.mcp.kotlin.sdk)
    implementation(libs.kotlin.logging)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.io.core)
    // Ktor / SDK 的 SLF4J 输出固定由 simple 绑定到 stderr，绝不允许落到 stdout。
    runtimeOnly(libs.slf4j.simple)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
}

// 进程级集成测试需要独立 JVM 的完整 classpath：配置期只传 FileCollection，执行期才落文件。
val writeTestClasspath by tasks.registering {
    val runtime = sourceSets.test.get().runtimeClasspath
    val output = layout.buildDirectory.file("mcp-test-classpath.txt")
    inputs.files(runtime)
    outputs.file(output)
    doLast {
        output.get().asFile.writeText(runtime.asPath)
    }
}

tasks.test {
    dependsOn(writeTestClasspath)
    systemProperty(
        "mcp.test.classpathFile",
        layout.buildDirectory.file("mcp-test-classpath.txt").get().asFile.absolutePath,
    )
}
