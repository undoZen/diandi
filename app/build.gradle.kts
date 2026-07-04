plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "app.diandi"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.diandi"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // demo 阶段先不混淆，避免 Ktor 反射被裁；正式版再配 R8 规则
            isMinifyEnabled = false
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
}
