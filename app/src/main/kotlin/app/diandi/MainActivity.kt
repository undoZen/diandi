package app.diandi

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import app.diandi.collect.NotificationCollectorService
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var urlText: TextView
    private lateinit var notifAccessText: TextView
    private lateinit var batteryText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        urlText = findViewById(R.id.url_text)
        notifAccessText = findViewById(R.id.notif_access_text)
        batteryText = findViewById(R.id.battery_text)

        findViewById<Button>(R.id.btn_start).setOnClickListener {
            startForegroundService(Intent(this, WebServerService::class.java))
            statusText.postDelayed({ refreshStatus() }, 500)
        }
        findViewById<Button>(R.id.btn_stop).setOnClickListener {
            stopService(Intent(this, WebServerService::class.java))
            statusText.postDelayed({ refreshStatus() }, 500)
        }
        findViewById<Button>(R.id.btn_notif_access).setOnClickListener {
            // 通知使用权无法运行时申请，只能带用户去系统设置手动开
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.btn_battery).setOnClickListener {
            // 保活第一步：申请忽略电池优化（Doze 白名单），弹系统确认框
            if (!isIgnoringBattery()) {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:$packageName"))
                )
            }
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
        refreshNotifAccess()
        refreshBattery()
    }

    private fun refreshBattery() {
        batteryText.text = if (isIgnoringBattery()) {
            "● 已忽略电池优化（保活）"
        } else {
            "○ 未忽略电池优化，服务可能被系统杀掉"
        }
    }

    private fun isIgnoringBattery(): Boolean =
        getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(packageName)

    private fun refreshNotifAccess() {
        notifAccessText.text = when {
            !isNotifAccessGranted() -> "○ 通知使用权未授予（采集未开始）"
            NotificationCollectorService.isConnected -> "● 通知采集运行中"
            else -> "◐ 已授权，等待系统绑定…（若持续如此，试试重开授权）"
        }
    }

    /** 「通知使用权」是否已授予：查系统安全设置里的已启用监听器列表 */
    private fun isNotifAccessGranted(): Boolean =
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.split(':')
            ?.any { it.startsWith("$packageName/") } == true

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
