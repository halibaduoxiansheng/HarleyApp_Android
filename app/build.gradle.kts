import java.security.KeyStore
import java.security.MessageDigest
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

// HarleyApp 已安装版本长期使用这把工程专用调试证书。若继续采用 AGP 默认配置，
// Gradle 会根据当前用户目录选择或新建 debug.keystore，换电脑、换用户或改变
// ANDROID_USER_HOME 后都可能生成不同证书，最终导致覆盖安装被 Android 拒绝。
val harleyDebugKeystore = rootProject.file(".android/debug.keystore")
val harleyDebugKeyAlias = "androiddebugkey"
val harleyDebugKeyPassword = "android"
val expectedHarleyDebugCertificateSha256 =
    "2c1f206d1ee68fa61ac92030cc7aa2ca23beb6fea96c06f9dad6329f1e3f214c"

android {
    namespace = "com.example.harleyapp"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.harleyapp"
        minSdk = 26
        targetSdk = 37
        versionCode = 7
        versionName = "1.0.7"

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

    signingConfigs {
        getByName("debug") {
            // 私钥文件由 .gitignore 排除，不上传到 GitHub；构建机必须从安全备份恢复同一文件。
            storeFile = harleyDebugKeystore
            storePassword = harleyDebugKeyPassword
            keyAlias = harleyDebugKeyAlias
            keyPassword = harleyDebugKeyPassword
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }

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

val verifyHarleyDebugSigning by tasks.registering {
    group = "verification"
    description = "Verifies the project-specific HarleyApp debug signing certificate."

    // 不缓存此校验，确保每次打包或安装前都会重新检查本地私钥是否缺失或被替换。
    outputs.upToDateWhen { false }

    doLast {
        if (!harleyDebugKeystore.isFile) {
            throw GradleException(
                "Required HarleyApp debug keystore is missing: ${harleyDebugKeystore.absolutePath}. " +
                    "Restore the project-specific keystore before building an APK."
            )
        }

        val keyStore = try {
            KeyStore.getInstance(harleyDebugKeystore, harleyDebugKeyPassword.toCharArray())
        } catch (exception: Exception) {
            throw GradleException(
                "Unable to read the HarleyApp debug keystore: ${harleyDebugKeystore.absolutePath}",
                exception
            )
        }
        val certificate = keyStore.getCertificate(harleyDebugKeyAlias)
            ?: throw GradleException(
                "Required alias '$harleyDebugKeyAlias' was not found in the HarleyApp debug keystore."
            )
        val actualCertificateSha256 = MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

        if (actualCertificateSha256 != expectedHarleyDebugCertificateSha256) {
            throw GradleException(
                "HarleyApp debug signing certificate mismatch. " +
                    "Expected $expectedHarleyDebugCertificateSha256, " +
                    "but found $actualCertificateSha256. Refusing to build an incompatible APK."
            )
        }
    }
}

tasks.configureEach {
    // assembleDebug 覆盖命令行构建，installDebug 覆盖 Android Studio/ADB 安装，
    // bundleDebug 覆盖调试 Bundle；validateSigningDebug 则保护底层打包链路。
    if (
        name == "assembleDebug" ||
        name == "installDebug" ||
        name == "bundleDebug" ||
        name == "validateSigningDebug"
    ) {
        dependsOn(verifyHarleyDebugSigning)
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
