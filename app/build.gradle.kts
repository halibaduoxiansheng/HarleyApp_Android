import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.ksp)
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.isFile) {
        localPropertiesFile.inputStream().use(::load)
    }
}
val configuredDeveloperKeySha256 = (
    providers.environmentVariable("HARLEY_DEVELOPER_KEY_SHA256").orNull
        ?: localProperties.getProperty("HARLEY_DEVELOPER_KEY_SHA256")
    )
    ?.trim()
    ?.lowercase()
    ?.takeIf { hash -> hash.matches(Regex("[0-9a-f]{64}")) }
    .orEmpty()

android {
    namespace = "com.example.harleyapp"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.harleyapp"
        minSdk = 26
        targetSdk = 37
        versionCode = 5
        versionName = "1.0.5"

        ndk {
            // 静态sherpa AAR仅在过时32位x86目录残留共享ORT；排除该ABI可让其与M2M的ORT 1.28.0完全隔离。
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            type = "String",
            name = "DEVELOPER_KEY_SHA256",
            value = "\"$configuredDeveloperKeySha256\""
        )
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // static sherpa AAR的32位x86目录仍残留共享ORT；该ABI已不支持，合并阶段也必须显式排除。
            excludes += "lib/x86/**"
        }
    }

}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // CameraX只提供本机相机预览；ZXing二维码解码在设备端完成，不上传画面或依赖在线服务。
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.compose)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
    // sherpa-onnx把其ONNX Runtime静态链接进自身JNI，不再携带同名libonnxruntime.so，可与实时翻译运行库隔离。
    implementation(files("libs/sherpa-onnx-static-link-onnxruntime-1.13.7.aar"))
    // M2M100使用官方ONNX Runtime 1.28.0，避开1.27.0在新一代ARM处理器上的KleidiAI量化计算问题。
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.28.0")
    // 仅用于安全解压官方tar.bz2模型包，运行时不会把音频或字幕发送给该库或外部服务。
    implementation("org.apache.commons:commons-compress:1.28.0")
    // ML Kit仅供现有电子书离线翻译使用；实时字幕翻译不创建ML Kit客户端，也不依赖其语言包。
    implementation("com.google.mlkit:translate:17.0.3")
    implementation(libs.androidx.health.connect.client)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
