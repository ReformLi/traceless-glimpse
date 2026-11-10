import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

/**
 * 签名口令从仓库根目录的 `keystore.properties` 读，文件与密钥都在 .gitignore 里。
 *
 * 读不到时不报错、也不签——CI 与别人 clone 下来仍能 `assembleRelease` 出一个未签名包，
 * 只是装不上设备。把「没有密钥」做成构建失败，会让所有不带密钥的验证构建陪绑。
 */
val keystoreProperties = Properties().apply {
    val source = rootProject.file("keystore.properties")
    if (source.isFile) source.inputStream().use { load(it) }
}
val releaseKeystore = keystoreProperties.takeIf { props ->
    listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all { !props.getProperty(it).isNullOrEmpty() }
}
// storeFile 支持绝对路径；相对路径按仓库根目录解析，而不是模块目录。
val releaseStoreFile = releaseKeystore?.getProperty("storeFile")?.let { rootProject.file(it) }
val releaseSigning = releaseKeystore?.takeIf { releaseStoreFile?.isFile == true }
if (releaseSigning == null) {
    if (releaseKeystore == null) {
        logger.lifecycle("未读到可用的 keystore.properties：本次 Release 产出未签名 APK（装不上设备，仅供构建验证）")
    } else {
        logger.lifecycle("keystore.properties 的 storeFile 不存在（${releaseStoreFile?.absolutePath}）：本次 Release 产出未签名 APK")
    }
}

android {
    namespace = "com.hpu.browseguard"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.hpu.browseguard"
        // PRD NFR-05：Android 10+（API 29）
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        releaseSigning?.let { props ->
            create("release") {
                storeFile = releaseStoreFile
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
                // minSdk 29 意味着装这个包的机器最少也是 Android 10：v1（JAR 签名）只服务
                // API < 24 的安装路径，打开它只是往 APK 里塞一份永远不会被读的 META-INF/*.SF。
                // v3 才是 Android 11+ 用来确认「签名者没换过」的那一层，得显式要。
                // v4 是给 Play 流式安装算 Merkle 树用的，侧载用不上，还会多产出 .idsig 文件。
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            releaseSigning?.let { signingConfig = signingConfigs.getByName("release") }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        // 仅为 android.util.Log.* 服务（selectorFor 的 video_only 分支要打 warning），让 JVM 单测不抛「not mocked」。
        // 约束：依赖 Android API 行为的单测必须显式 mock（或走 androidTest），不得把这里的默认返回值当断言依据。
        // 权宜方案，M3 收尾评估 mockk / Robolectric 替代。
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // 协议模型单一来源（KMP commonMain）
    implementation(project(":shared"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.webkit)

    // 全屏播放 + 断点续播；刻意不引 media3-exoplayer-ffmpeg/rtmp/cast 这些扩展包。
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    debugImplementation(libs.androidx.compose.ui.tooling)
    // Compose UI 测试要一个空壳 Activity 来跑 setContent；只在 debug 变体里存在。
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
