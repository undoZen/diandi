# 点滴集（diandi）

从安卓通知中提炼每日活动、被动提醒健康生活的 AI 助手。决策与规划见 `../DECISIONS.md`。

## 当前阶段

- **React Web**：首页 `/`、消费 `/spending`、统计 `/dashboard` 由 Vite+React+TS+Tailwind 构建，产物托管在 `assets/web/`，Ktor 通过 `staticResources("/", "web")` 提供。
- **React Native**：通知历史页为原生 Android 界面（FlatList），由 MainActivity 顶部状态栏「通知」按钮启动。
- **遗留手写 HTML**：`Pages.kt` 仍保留 `/ble`（DeepSeek 加入的 BLE/iOS 通知采集）作为临时入口；`/notifications` Web 页已随 RN 列表上线移除。

## 前置依赖

- Node.js 18+（当前机器 v24，npm 11）
- JDK 17
- Android SDK：`platform-tools`、`platforms;android-34`、`build-tools;34.0.0`

## 构建流程

**首次或换机后：**

```bash
cd diandi
npm install
```

**每次改完 JS 后发布 APK 前，必须先构建 Web 产物：**

```bash
npm run web:build
```

RN release bundle 由 Gradle 插件在 `assembleRelease` 时自动生成；debug 包我们预先用同一条命令打包：

```bash
npm run mobile:bundle:release
```

**构建并安装 debug APK：**

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## macOS（当前机器）

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
# local.properties 已指向 /opt/homebrew/share/android-commandlinetools
npm install
npm run web:build
npm run mobile:bundle:release
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Windows（接续开发）

1. 装 JDK 17：`winget install EclipseAdoptium.Temurin.17.JDK`（或 scoop）
2. 装 Android SDK 命令行工具：
   - 下载 [commandline-tools](https://developer.android.com/studio#command-line-tools-only) 解压到如 `C:\Android\sdk\cmdline-tools\latest\`
   - `sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"` 并 `sdkmanager --licenses` 全部接受
3. 项目根新建 `local.properties`（此文件不入库）：`sdk.dir=C\:\\Android\\sdk`
4. 装 Node 依赖并构建产物：
   ```cmd
   npm install
   npm run web:build
   npm run mobile:bundle:release
   gradlew.bat assembleDebug
   adb install -r app\build\outputs\apk\debug\app-debug.apk
   ```

## 开发调试

### React Web（/spending、/dashboard）

直接改 `web/src/`，改完 `npm run web:build` 并重装 APK。SPA 与 `/api/*` 同源（`127.0.0.1:PORT`），无 CORS 问题。

如需浏览器 HMR：

```bash
npm run web:dev
```

然后 `adb reverse tcp:5173 tcp:5173`，临时把 `MainActivity.homeUrl` 指向 `http://127.0.0.1:5173/`（跨域 API 需额外处理，非必须）。

### React Native 通知列表

```bash
npm run mobile:start
adb reverse tcp:8081 tcp:8081
```

DEBUG 包启动 `NotificationsActivity` 时会从 Metro 加载 JS；release 包使用 `assets/index.android.bundle`。

## 技术要点

- minSdk 26 / targetSdk 34 / compileSdk 34
- Kotlin 2.0.21（钉住以兼容 React Native 0.75.5 的 Gradle 插件；RN 0.76+ 需要 compileSdk 35）
- 前台服务类型 `specialUse`（`dataSync` 在 Android 15 有 6h/天限制，不适合常驻 server）
- Android 13+ 运行时申请 `POST_NOTIFICATIONS`
- BLE/ANCS（iOS 通知采集）为 DeepSeek 加入的实验性功能，见 `ble/AncsClient.kt` 与 `/ble` 页面

## 远程 Windows 调试

- **全功能（推荐）**：手机（Android 11+）与远程 Windows 都装 Tailscale 登同一账号
  → 手机开发者选项开「无线调试」
  → Windows 上 `adb pair <手机TailscaleIP>:<配对端口>` 输配对码
  → `adb connect <手机TailscaleIP>:<调试端口>` → 之后 install / logcat 与 USB 无异
- **简易**：构建出的 APK 传到手机（IM / 网盘）手动点击安装；
  没有 logcat，但本 app 自带 HTTP server，可在浏览器看 `/api/*` 端点。

## 项目结构

```
diandi/
├── app/src/main/kotlin/...    # Android Kotlin（Ktor、Room、采集、RN Activity）
├── app/src/main/assets/web/   # React Web 构建产物（.gitignore，需手动构建）
├── app/src/main/assets/index.android.bundle  # RN bundle（.gitignore）
├── web/                       # React Web SPA
├── mobile/                    # React Native 通知列表
├── shared/                    # Web/RN 共用类型与 API 客户端
├── autolinking.json           # RN autolinking 缓存（.gitignore）
└── react-native.config.js     # RN brownfield 配置
```
