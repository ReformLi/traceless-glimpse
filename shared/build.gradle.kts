// KMP 协议模型模块：docs/PROTOCOL.md §3 的单一来源实现。
// 阶段 1：最小空壳；阶段 2：模型 + BgvJson + schema 导出器 + 测试。
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    // 客户端 minSdk 29 / JDK 17 基线：JVM 产物与 Android 单测 JVM 保持一致（字节码 61）。
    jvmToolchain(17)
    android {
        namespace = "com.hpu.browseguard.shared"
        compileSdk = 36
        minSdk = 29
    }
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

// 导出协议模型 JSON Schema 到 shared/schema/（契约产物，提交，勿手改）。
// 用法：.\gradlew.bat :shared:exportSchemas
tasks.register<JavaExec>("exportSchemas") {
    group = "bgv"
    description = "导出协议模型 JSON Schema 到 shared/schema/"
    val jvmMain = kotlin.jvm().compilations.getByName("main")
    // runtimeDependencyFiles 只含依赖 jar，须补上自身编译输出
    classpath(jvmMain.output.classesDirs, jvmMain.runtimeDependencyFiles)
    mainClass.set("com.hpu.browseguard.shared.schema.ExportSchemasKt")
    args(layout.projectDirectory.dir("schema").asFile.absolutePath)
}
