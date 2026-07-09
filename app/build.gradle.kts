plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    id("com.facebook.react")
}

// 签名：在 ~/.gradle/gradle.properties 或 local.properties 或 CI 环境变量中设置
// 字段：signStoreFile, signStorePassword, signKeyAlias, signKeyPassword
val signFile   = extra.properties.getOrDefault("signStoreFile", null) as? String
    ?: findProperty("signStoreFile") as? String
val signPass   = findProperty("signStorePassword") as? String
val signAlias  = findProperty("signKeyAlias") as? String
val signKey    = findProperty("signKeyPassword") as? String

android {
    namespace = "com.mianbizhe.diandiji"
    compileSdk = 34

    signingConfigs {
        if (!signFile.isNullOrBlank()) {
            create("release") {
                storeFile = file(signFile!!)
                storePassword = signPass
                keyAlias = signAlias
                keyPassword = signKey
            }
        }
    }

    defaultConfig {
        applicationId = "com.mianbizhe.diandiji"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "0.1.4"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/*.md",
            "META-INF/INDEX.LIST",
            "META-INF/DEPENDENCIES",
            "META-INF/NOTICE",
            "META-INF/LICENSE",
            "META-INF/*.version",
        )
        // RN Hermes .so 需legacy packaging 才能在旧设备正确解压加载
        jniLibs { useLegacyPackaging = true }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

room {
    // schema 历史入库，将来数据库升级写 Migration 时要用
    schemaDirectory("$projectDir/schemas")
}

// React Native brownfield：版本由 RNGP 约束（react-android/hermes-android 不写版本）。
// RNGP 默认按 android/app 布局找 node_modules（../../node_modules），本项目 app 在
// diandi/app（无 android/ 目录），会错找成上级 mbti/node_modules，故显式指定三个路径。
react {
    autolinkLibrariesWithApp()
    root = rootProject.layout.projectDirectory.asFile
    reactNativeDir = rootProject.file("node_modules/react-native")
    codegenDir = rootProject.file("node_modules/@react-native/codegen")
    cliFile = rootProject.file("node_modules/@react-native-community/cli/build/bin.js")
    entryFile = rootProject.file("mobile/index.js")
    bundleConfig = rootProject.file("mobile/metro.config.js")
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)

    // 采集层：通知落库
    implementation(libs.room.runtime)
    implementation(libs.room.ktx) // suspend DAO + Flow 支持
    ksp(libs.room.compiler)

    // React Native（列表页原生界面）：版本由 RNGP 提供
    implementation("com.facebook.react:react-android")
    implementation("com.facebook.react:hermes-android")
    // RN ReactActivity 需 AppCompat 主题（仅 NotificationsActivity 用）
    implementation("androidx.appcompat:appcompat:1.6.1")

    // 单测（JVM 本地跑）：org.json 在单测里是 stub，须替换为真实现
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
