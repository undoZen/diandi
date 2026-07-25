package com.mianbizhe.diandiji.alert

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.mianbizhe.diandiji.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全屏提醒页：命中提醒规则的通知（如信用卡消费）弹出显示。
 * 由 fullScreenIntent（锁屏/灭屏时系统直接全屏拉起）或后台 startActivity（ColorOS 授权后）进入。
 */
class AlertActivity : Activity() {

    companion object {
        const val EXTRA_TITLE = "alert_title"
        const val EXTRA_TEXT = "alert_text"
        const val EXTRA_APP = "alert_app"
        const val EXTRA_POST_TIME = "alert_post_time"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 锁屏上直接显示 + 点亮屏幕（来电界面同款行为）
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        setContentView(R.layout.activity_alert)

        val postTime = intent.getLongExtra(EXTRA_POST_TIME, System.currentTimeMillis())
        findViewById<TextView>(R.id.alert_title).text =
            intent.getStringExtra(EXTRA_TITLE) ?: "（无标题）"
        findViewById<TextView>(R.id.alert_text).text =
            intent.getStringExtra(EXTRA_TEXT) ?: "（无内容）"
        findViewById<TextView>(R.id.alert_meta).text = buildString {
            append(intent.getStringExtra(EXTRA_APP) ?: "")
            append("  ·  ")
            append(SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(postTime)))
        }
        findViewById<Button>(R.id.alert_dismiss).setOnClickListener { finish() }
    }
}
