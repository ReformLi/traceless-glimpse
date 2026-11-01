// 根构建脚本：集中声明插件版本，不在根项目应用。
// AGP 与 KGP 必须同处根构建类路径：AGP 9 内置 Kotlin 会在根类加载器中复用 KGP 类，
// 若根类路径缺 AGP，KotlinAndroidTarget 的装饰会因找不到 BaseVariant 而失败。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
