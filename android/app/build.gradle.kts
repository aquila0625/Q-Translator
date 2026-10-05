import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.yishulabs.qtranslator"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yishulabs.qtranslator"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"

        // 友盟+ 统计的 AppKey 不进仓库：写在 android/local.properties（已被 git 忽略）里，例如
        //   UMENG_APPKEY_ANDROID=手机用的 AppKey
        //   UMENG_APPKEY_ANDROID_TABLET=平板用的 AppKey（可选，不填就用手机的）
        // 两个 AppKey 在友盟后台分别建 Android 应用获取。不填时不初始化友盟、不发任何统计，设置里也不显示统计开关。
        fun key(name: String) = "\"" + (local.getProperty(name) ?: "").trim() + "\""
        buildConfigField("String", "UMENG_APPKEY", key("UMENG_APPKEY_ANDROID"))
        buildConfigField("String", "UMENG_APPKEY_TABLET", key("UMENG_APPKEY_ANDROID_TABLET"))
    }

    // 发布签名：官网的 APK 和 Google Play 必须用同一把密钥，这样才是同一个应用（用户可以互相覆盖升级）。
    // 密钥文件和密码都不进仓库，写在 android/local.properties（或同名环境变量）里：
    //   QT_KEYSTORE_FILE=/绝对路径/qtranslator-release.jks
    //   QT_KEYSTORE_PASSWORD=...
    //   QT_KEY_ALIAS=...
    //   QT_KEY_PASSWORD=...
    // 没填时用调试证书签名，方便自己编译安装，但这样的包不能发布。
    val releaseKeystore = (local.getProperty("QT_KEYSTORE_FILE") ?: System.getenv("QT_KEYSTORE_FILE"))?.trim().orEmpty()
    signingConfigs {
        if (releaseKeystore.isNotEmpty()) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = local.getProperty("QT_KEYSTORE_PASSWORD") ?: System.getenv("QT_KEYSTORE_PASSWORD")
                keyAlias = local.getProperty("QT_KEY_ALIAS") ?: System.getenv("QT_KEY_ALIAS")
                keyPassword = local.getProperty("QT_KEY_PASSWORD") ?: System.getenv("QT_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 配了发布密钥就用它签名；没配就用调试证书（只能自己安装，不能发布）
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // ML Kit 的翻译和识字带本机库，按手机 CPU 分别打包，每个 APK 小一半以上
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // 本机离线翻译和图片文字识别（Google ML Kit，免费，模型下载后不用联网）
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    // 友盟+ 匿名使用统计（用户同意后才初始化；没填 AppKey 时完全不启用）
    implementation("com.umeng.umsdk:common:9.9.10")
    implementation("com.umeng.umsdk:asms:1.8.7.2")
}
