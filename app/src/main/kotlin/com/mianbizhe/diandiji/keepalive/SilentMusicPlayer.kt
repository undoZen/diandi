package com.mianbizhe.diandiji.keepalive

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * 无声音乐保活（「保活增强」开关的核心，思路参考 Cactus，实现自写）。
 *
 * 原理：循环播放一段全零 PCM 音频，进程进入「音频播放中」状态，
 * ColorOS/MIUI 等国产 ROM 对这类进程几乎不杀（怕打断用户听歌）。
 *
 * 代价：[setWakeMode] 持 PARTIAL_WAKE_LOCK，CPU 不休眠，**明显费电**——
 * 所以只由「保活增强」开关显式开启，默认不跑。
 *
 * 音频文件不打包进 APK：首次启动时在 cacheDir 生成 1 秒全零 WAV（16KB）。
 * 看门狗每 30 秒检查一次播放状态，掉了就重建（Cactus 的 musicInterval 思路）。
 */
object SilentMusicPlayer {

    private const val TAG = "diandi-keepalive"
    private const val WATCHDOG_MS = 30_000L

    @Volatile
    var running = false
        private set

    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null

    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            val ctx = appContext
            if (ctx != null && player?.isPlaying != true) {
                Log.w(TAG, "silent music not playing, recreating")
                releasePlayer()
                createPlayer(ctx)
            }
            handler.postDelayed(this, WATCHDOG_MS)
        }
    }

    @Synchronized
    fun start(context: Context) {
        if (running) return
        running = true
        appContext = context.applicationContext
        createPlayer(appContext!!)
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_MS)
        Log.i(TAG, "silent music started")
    }

    @Synchronized
    fun stop() {
        running = false
        handler.removeCallbacks(watchdog)
        releasePlayer()
        Log.i(TAG, "silent music stopped")
    }

    private fun createPlayer(context: Context) {
        try {
            val file = silentWavFile(context)
            val p = MediaPlayer()
            p.setDataSource(file.absolutePath)
            p.isLooping = true
            // 持 PARTIAL_WAKE_LOCK 保证熄屏后 CPU 不掉、音频流不断——费电来源，见类注释
            p.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            p.prepare()
            p.start()
            player = p
        } catch (e: Exception) {
            Log.e(TAG, "start silent music failed", e)
        }
    }

    private fun releasePlayer() {
        try {
            player?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (_: Exception) {
        }
        player = null
    }

    /** 生成 1 秒 8kHz mono 16-bit 全零 WAV（16KB），已存在则直接复用 */
    private fun silentWavFile(context: Context): File {
        val file = File(context.cacheDir, "silence.wav")
        if (file.exists() && file.length() > 44) return file

        val sampleRate = 8000
        val dataSize = sampleRate * 2 // 1 秒 × 2 字节/样本
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            // RIFF 头（44 字节标准 PCM WAV）
            raf.writeBytes("RIFF")
            raf.writeInt(Integer.reverseBytes(36 + dataSize))
            raf.writeBytes("WAVE")
            raf.writeBytes("fmt ")
            raf.writeInt(Integer.reverseBytes(16))          // PCM 块大小
            raf.writeShort(java.lang.Short.reverseBytes(1).toInt())   // PCM 格式
            raf.writeShort(java.lang.Short.reverseBytes(1).toInt())   // mono
            raf.writeInt(Integer.reverseBytes(sampleRate))
            raf.writeInt(Integer.reverseBytes(sampleRate * 2))        // byte rate
            raf.writeShort(java.lang.Short.reverseBytes(2).toInt())   // block align
            raf.writeShort(java.lang.Short.reverseBytes(16).toInt())  // bits/sample
            raf.writeBytes("data")
            raf.writeInt(Integer.reverseBytes(dataSize))
            raf.write(ByteArray(dataSize)) // 全零 = 静音
        }
        return file
    }
}
