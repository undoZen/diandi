plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
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
        versionCode = 1
        versionName = "0.1.0"
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
        )
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

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)

    // 采集层：通知落库
    implementation(libs.room.runtime)
    implementation(libs.room.ktx) // suspend DAO + Flow 支持
    ksp(libs.room.compiler)

    // 单测（JVM 本地跑）：org.json 在单测里是 stub，须替换为真实现
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
