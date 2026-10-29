plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

import java.io.FileInputStream
import java.util.Properties

android {
    namespace = "com.mdtodo"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.mdtodo"
        minSdk = 30
        targetSdk = 37
        versionCode = 11
        versionName = "2.3"
    }

    val keystoreProperties = Properties().apply {
        val propsFile = rootProject.file("keystore.properties")
        if (propsFile.exists()) {
            FileInputStream(propsFile).use { load(it) }
        }
    }
    fun signingValue(key: String): String? = System.getenv(key) ?: keystoreProperties.getProperty(key)

    signingConfigs {
        val storeFilePath = signingValue("TODOSIAN_RELEASE_STORE_FILE")
        val storePassword = signingValue("TODOSIAN_RELEASE_STORE_PASSWORD")
        val keyAlias = signingValue("TODOSIAN_RELEASE_KEY_ALIAS")
        val keyPassword = signingValue("TODOSIAN_RELEASE_KEY_PASSWORD")

        val storeFile = storeFilePath?.let { rootProject.file(it) }
        if (storeFile != null && storeFile.exists() && storePassword != null && keyAlias != null && keyPassword != null) {
            create("release") {
                this.storeFile = storeFile
                this.storePassword = storePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword

                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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

android.sourceSets {
    getByName("debug") {
        res.directories += "src/debug/res"
    }
}

dependencies {
    implementation(libs.google.material)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.foundation.layout)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.glance.appwidget)
    testImplementation(libs.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // MD Todo 移除 LeakCanary（原项目带的）：
    // 1. 它会在检测到泄漏时抢占焦点自动跳到 Leaks 界面，正常使用 App 都会被弹走
    // 2. 自用单机 App，没有多人长期在线的场景，收益远低于干扰成本
    // 3. 需要排查泄漏时临时加回即可（注释掉本行，版本号在 libs.versions.toml）
}

// MD Todo：移除原项目的「更新日志」机制（用户决定）。
// 它由 changelog.json + ChangelogRepository + ChangelogBottomSheet 三部分组成，
// 每次 preBuild 还会改写 assets/changelog.json 里的版本号。
// 自用App 不需要「更新了什么」的弹窗，改动记录看项目文档即可，
// 故连同 assets/changelog.json 与 preBuild 的依赖一起删除。
