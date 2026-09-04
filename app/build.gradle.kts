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
        versionCode = 3
        versionName = "1.0.3"

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
    // ML Kit按需下载约30MB语言模型，模型就绪后翻译在手机本地完成，不上传书籍正文。
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
