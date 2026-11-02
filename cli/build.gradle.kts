// CLI（bgv）：纯 JVM，复用 :engine-client 与 :shared 协议模型。
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    applicationName = "bgv"
    mainClass.set("com.hpu.browseguard.cli.MainKt")
}

dependencies {
    implementation(project(":engine-client"))
    implementation(libs.clikt)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
}
