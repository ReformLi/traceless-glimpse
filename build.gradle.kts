// 根构建脚本：集中声明插件版本，不在根项目应用。
// android/ 模块未接入构建（settings.gradle.kts 只挂 :shared），AGP 仅作 M3 基线记录在版本目录。
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
