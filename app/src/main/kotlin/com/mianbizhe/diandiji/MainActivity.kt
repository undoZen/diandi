package com.mianbizhe.diandiji

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import com.mianbizhe.diandiji.collect.NotificationCollectorService

/**
 * 主界面 = 顶部状态/控制条 + WebView 内嵌 Dashboard（当前首页：/notifications）。
 * 打开即自动启动 Web 服务并加载页面；服务器就绪前短暂重试。
 */
class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var webView: WebView

    private val homeUrl get() = "http://127.0.0.1:${WebServerService.PORT}/spending"
    private var loadFailed = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        webView = findViewById(R.id.webview)

        findViewById<Button>(R.id.btn_notif_access).setOnClickListener {
            // 通知使用权无法运行时申请，只能带用户去系统设置手动开
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.btn_battery).setOnClickListener {
            if (!isIgnoringBattery()) {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:$packageName"))
                )
            }
        }
        findViewById<Button>(R.id.btn_autostart).setOnClickListener {
            // 厂商自启动 + 后台弹出界面权限（ColorOS 同页）；全屏提醒可靠性靠它
            KeepAlive.openAutoStartSettings(this)
        }
        findViewById<Button>(R.id.btn_ble_perms).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }
        findViewById<Button>(R.id.btn_refresh).setOnClickListener {
            loadHome()
        }

        webView.settings.javaScriptEnabled = true // 列表页靠 fetch 渲染
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError,
            ) {
                // 只关心主文档加载失败（服务器可能还没起来），子资源失败不管
                if (request.isForMainFrame) loadFailed = true
            }

            override fun onPageFinished(view: WebView, url: String) {
                // 服务器未就绪导致失败 → 1 秒后重试一次
                if (loadFailed) {
                    loadFailed = false
                    view.postDelayed({ loadHome() }, 1000)
                }
            }
        }

        // 打开应用即确保服务在跑
        if (!WebServerService.isRunning) {
            startForegroundService(Intent(this, WebServerService::class.java))
        }
        // 给服务器一点启动时间再加载，避免必然的首次失败
        webView.postDelayed({ loadHome() }, if (WebServerService.isRunning) 0L else 600L)

        // Android 13+ 前台服务通知需要 POST_NOTIFICATIONS 运行时授权
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        // BLE 权限运行时授权
        // API 31+ 需要 BLUETOOTH_SCAN + BLUETOOTH_CONNECT + BLUETOOTH_ADVERTISE（ANCS 配对要当外设广播）
        // ColorOS 额外需要 ACCESS_FINE_LOCATION（即使 Android 声明不再需要）
        val needBle = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                needBle += Manifest.permission.BLUETOOTH_SCAN
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                needBle += Manifest.permission.BLUETOOTH_CONNECT
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED)
                needBle += Manifest.permission.BLUETOOTH_ADVERTISE
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needBle += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (needBle.isNotEmpty()) {
            requestPermissions(needBle.toTypedArray(), 2)
        }
    }

    private fun loadHome() {
        loadFailed = false
        webView.loadUrl(homeUrl)
    }

    /** WebView 内后退优先，退无可退才退出 Activity */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置授权页返回时，系统绑定有延迟，轮询至多 5 次（0.5s / 1s / 2s / 3s / 5s）
        val delays = longArrayOf(0, 500, 1500, 3500, 6500, 11500)
        for (d in delays) {
            statusText.postDelayed({ refreshStatus() }, d)
        }
    }

    private fun refreshStatus() {
        val service = if (WebServerService.isRunning) "服务●" else "服务○"
        val granted = isNotifAccessGranted()
        val connected = NotificationCollectorService.isConnected
        val collect = when {
            !granted -> "采集○未授权"
            connected -> "采集●"
            else -> "采集◐等待绑定"
        }
        val battery = if (isIgnoringBattery()) "保活●" else "保活○"
        statusText.text = "$service  $collect  $battery  ·  $homeUrl"
    }

    /** 「通知使用权」是否已授予：查系统安全设置里的已启用监听器列表 */
    private fun isNotifAccessGranted(): Boolean =
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.split(':')
            ?.any { it.startsWith("$packageName/") } == true

    private fun isIgnoringBattery(): Boolean =
        getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(packageName)
}
