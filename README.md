# diandi（临时工作名）

从安卓通知中提炼每日活动、被动提醒健康生活的 AI 助手。决策与规划见 `../DECISIONS.md`。

## 当前阶段：里程碑 1 —— Ktor 最小 demo

前台 Service 内嵌 Ktor(CIO)，监听 `0.0.0.0:8899`：

- `GET /` — Hello World 页面（设备信息 + 时间）
- `GET /api/ping` — JSON 心跳

验证目标：APK 体积、启动速度、后台存活。

## 构建

### macOS（当前机器，已配好）

SDK 和 JDK 由 Homebrew 安装（不装 Android Studio，纯命令行）：

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
# local.properties 已指向 /opt/homebrew/share/android-commandlinetools
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Windows（接续开发）

1. 装 JDK 17：`winget install EclipseAdoptium.Temurin.17.JDK`（或 scoop）
2. 装 Android SDK 命令行工具（不需要 Android Studio）：
   - 下载 [commandline-tools](https://developer.android.com/studio#command-line-tools-only) 解压到如 `C:\Android\sdk\cmdline-tools\latest\`
   - `sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"` 并 `sdkmanager --licenses` 全部接受
3. 项目根新建 `local.properties`（此文件不入库，每台机器自己写）：
   `sdk.dir=C\:\\Android\\sdk`
4. 构建：`gradlew.bat assembleDebug`
5. 安装：`adb install -r app\build\outputs\apk\debug\app-debug.apk`

装到手机后：点「启动服务」→ 手机浏览器开 `http://127.0.0.1:8899`；
同一 Wi-Fi 下电脑访问界面上显示的局域网地址。

## 技术要点

- minSdk 26 / targetSdk 35，无 AndroidX UI 依赖（demo 阶段裸 Activity，控制体积）
- 前台服务类型 `specialUse`（`dataSync` 在 Android 15 有 6h/天限制，不适合常驻 server）
- Android 13+ 运行时申请 `POST_NOTIFICATIONS`

### 远程 Windows 调试（构建机在远端、手机在身边）

- **全功能（推荐）**：手机（Android 11+）与远程 Windows 都装 Tailscale 登同一账号
  → 手机开发者选项开「无线调试」
  → Windows 上 `adb pair <手机TailscaleIP>:<配对端口>` 输配对码
  → `adb connect <手机TailscaleIP>:<调试端口>` → 之后 install / logcat 与 USB 无异
- **简易**：构建出的 APK 传到手机（IM / 网盘）手动点击安装；
  没有 logcat，但本 app 自带 HTTP server，后续可加 `/logs` 端点在浏览器看日志