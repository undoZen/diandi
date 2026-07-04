package app.diandi

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var urlText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        urlText = findViewById(R.id.url_text)

        findViewById<Button>(R.id.btn_start).setOnClickListener {
            startForegroundService(Intent(this, WebServerService::class.java))
            statusText.postDelayed({ refreshStatus() }, 500)
        }
        findViewById<Button>(R.id.btn_stop).setOnClickListener {
            stopService(Intent(this, WebServerService::class.java))
            statusText.postDelayed({ refreshStatus() }, 500)
        }

        // Android 13+ 前台服务通知需要 POST_NOTIFICATIONS 运行时授权
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        if (WebServerService.isRunning) {
            val lan = lanIp()
            statusText.text = "● 服务运行中"
            urlText.text = buildString {
                append("本机访问：http://127.0.0.1:${WebServerService.PORT}\n")
                if (lan != null) {
                    append("局域网访问：http://$lan:${WebServerService.PORT}")
                } else {
                    append("（未连接 Wi-Fi，无局域网地址）")
                }
            }
        } else {
            statusText.text = "○ 服务未启动"
            urlText.text = ""
        }
    }

    /** 取本机局域网 IPv4（一般是 Wi-Fi 地址） */
    private fun lanIp(): String? =
        NetworkInterface.getNetworkInterfaces()?.toList()
            ?.flatMap { it.inetAddresses.toList() }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
}
