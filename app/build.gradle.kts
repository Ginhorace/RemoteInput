plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 发布签名材料从环境变量读取（CI 由 GitHub Secrets 注入，本地亦可先设置再打包）。
// 未提供签名材料时不做签名，assembleRelease 产出的是 app-release-unsigned.apk —— 无法直接安装。
val releaseKeystorePath: String? =
    System.getenv("SIGNING_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }

android {
    // namespace 保持与源码包名一致：它决定生成的 R / BuildConfig 的包名，
    // 也决定 AndroidManifest 中 ".MainActivity" 这类相对类名的解析基准。
    namespace = "com.github.remotecontrol"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // 应用对外标识（安装到设备上的包名）与源码包名解耦
        applicationId = "com.github.ginforace.remoteinput"
        minSdk = 31
        targetSdk = 36
        versionCode = 5
        versionName = "1.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("SIGNING_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8：裁掉未被引用的类 / 方法 / 字段，并把标识符缩短。
            // 之前关着，APK 里 44 MB 有 43 MB 是未裁剪的 dex —— 绝大部分是
            // material-icons-extended（上万个图标）与整套 Compose 里根本没被调用的部分。
            isMinifyEnabled = true
            // 资源收缩：剔除未被引用的 res / 库资源（例如各依赖自带的图标、版本声明）。
            // 依赖 minify 开启，单独打开无效。
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 有签名材料才签名，否则保持 AGP 默认的未签名产物
            if (releaseKeystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    // Pager 与手势 API（detectTapGestures / awaitEachGesture 等）来自 foundation，
    // 此前只由 material3 传递引入，直接使用需显式声明
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}