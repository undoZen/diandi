pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
    // React Native brownfield：RNGP 以复合构建（includeBuild）接入，
    // 使 com.facebook.react.settings / com.facebook.react 插件可被解析
    includeBuild("node_modules/@react-native/gradle-plugin")
}

plugins {
    id("com.facebook.react.settings")
}

dependencyResolutionManagement {
    // PREFER_SETTINGS（非 FAIL_ON_PROJECT_REPOS）：RNGP 的 com.facebook.react 插件
    // 需注册一个 maven 仓库，FAIL_ON_PROJECT_REPOS 会拒绝它。
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

// 自动链接：本项目纯 JS 无原生模块，结果为空列表。
// RNGP 默认 cwd 为 rootDirectory.dir("../")（即 diandi 上级 mbti），npx/node 在错误
// 目录下找不到依赖；显式指定 cwd = diandi 并用 node 直接调 CLI 绕过 npx 的 PATH 问题。
val autolinkWorkingDir = settings.layout.rootDirectory.asFile
val autolinkCli = file("$settingsDir/node_modules/@react-native-community/cli/build/bin.js").absolutePath
extensions.configure<com.facebook.react.ReactSettingsExtension> {
    autolinkLibrariesFromCommand(
        command = listOf("node", autolinkCli, "config"),
        workingDirectory = autolinkWorkingDir,
    )
}

// 顶层 includeBuild：使根 buildscript classpath（com.facebook.react:react-native-gradle-plugin）
// 能经复合构建替换解析（pluginManagement 内的那个只管插件解析）。
includeBuild("node_modules/@react-native/gradle-plugin")

rootProject.name = "diandi"
include(":app")
