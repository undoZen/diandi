package com.mianbizhe.diandiji

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.mianbizhe.diandiji.setup.SetupActivity

/**
 * 主界面 = 纯 WebView 内嵌 React SPA。
 * 打开即自动启动 Web 服务并加载页面；服务器就绪前短暂重试。
 *
 * 原生功能入口走 /native/ 前缀链接拦截（SPA 首页卡片跳转，无需 JS bridge）：
 * - /native/setup         → 设置与引导屏（RN）
 * - /native/notifications → 通知列表屏（RN）
 * 浏览器直接访问这些路径时由 Ktor SPA fallback 兜底，不会白屏。
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView

    private val homeUrl get() = "http://127.0.0.1:${WebServerService.PORT}/"
    private var loadFailed = false

    companion object {
        /** 从其他屏（如设置屏的「打开 BLE 管理页」）指定加载路径 */
        const val EXTRA_URL = "com.mianbizhe.diandiji.EXTRA_URL"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        webView.settings.javaScriptEnabled = true // SPA 靠 JS 渲染
        // UA 加 App 标记：SPA 据此显示仅 App 内有效的入口（/native/ 链接卡片），浏览器访问时隐藏
        webView.settings.userAgentString = "${webView.settings.userAgentString} DiandiApp/${BuildConfig.VERSION_NAME}"
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

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.host == "127.0.0.1" && url.path?.startsWith("/native/") == true) {
                    when (url.path) {
                        "/native/setup" ->
                            startActivity(Intent(this@MainActivity, SetupActivity::class.java))
                        "/native/notifications" ->
                            startActivity(Intent(this@MainActivity, NotificationsActivity::class.java))
                        else -> return false
                    }
                    return true
                }
                return false
            }
        }

        // 打开应用即确保服务在跑
        if (!WebServerService.isRunning) {
            startForegroundService(Intent(this, WebServerService::class.java))
        }
        // 有权限但采集器掉绑（进程被杀后系统不主动重绑）时自动重绑：
        // 3s 首查（给系统自然绑定留时间），8s 复查一次
        webView.postDelayed({ KeepAlive.ensureCollectorBound(this) }, 3000)
        webView.postDelayed({ KeepAlive.ensureCollectorBound(this) }, 8000)
        // 给服务器一点启动时间再加载，避免必然的首次失败
        val target = intent.getStringExtra(EXTRA_URL) ?: homeUrl
        webView.postDelayed(
            { webView.loadUrl(target) },
            if (WebServerService.isRunning) 0L else 600L,
        )
    }

    private fun loadHome() {
        loadFailed = false
        webView.loadUrl(homeUrl)
    }

    /** 返回键 = 退出到桌面（App 逻辑）。不回退 WebView 历史——底部 tab 已覆盖
     *  全部导航且 tab 切换用 replace 不留历史，逐页回退在手机应用里是反直觉的 */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }
}
