buildscript {
    dependencies {
        // RNGP 经 settings 的 includeBuild 复合构建解析，无需写版本号
        classpath("com.facebook.react:react-native-gradle-plugin")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}
