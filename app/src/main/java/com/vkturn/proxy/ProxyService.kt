package com.vkturn.proxy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlin.concurrent.thread

class ProxyService : Service() {

    companion object {
        var isRunning = false
        val logBuffer = mutableListOf<String>()
        var onLogReceived: ((String) -> Unit)? = null

        fun addLog(msg: String) {
            if (logBuffer.size > 200) logBuffer.removeAt(0)
            logBuffer.add(msg)
            onLogReceived?.invoke(msg)
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var process: Process? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("ProxyChannel", "Proxy", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isRunning) return START_STICKY

        val notification = NotificationCompat.Builder(this, "ProxyChannel")
            .setContentTitle("VK TURN Proxy")
            .setContentText("Работает в фоне")
            .setSmallIcon(android.R.drawable.ic_menu_preferences)
            .build()
        startForeground(1, notification)
        isRunning = true

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VkTurn::BgLock")
        wakeLock?.acquire()

        addLog("=== ЗАПУСК ПРОКСИ ===")
        startBinary()

        return START_STICKY
    }

    private fun startBinary() {
        val prefs = getSharedPreferences("ProxyPrefs", Context.MODE_PRIVATE)
        val isRaw = prefs.getBoolean("isRaw", false)

        // Ищем обновленный пользователем файл, если его нет - берем вшитый в APK
        val customBin = File(filesDir, "custom_vkturn")
        val executable = if (customBin.exists()) {
            addLog("Используется кастомное ядро из памяти телефона")
            customBin.absolutePath
        } else {
            addLog("Используется стандартное ядро из APK")
            "${applicationInfo.nativeLibraryDir}/libvkturn.so"
        }

        val cmdArgs = mutableListOf<String>()

        if (isRaw) {
            val rawCmd = prefs.getString("rawCmd", "") ?: ""
            // Разбиваем строку по пробелам
            val parts = rawCmd.trim().split("\\s+".toRegex())
            cmdArgs.add(executable) // Подменяем вызов "./client" на реальный путь
            if (parts.size > 1) {
                cmdArgs.addAll(parts.subList(1, parts.size))
            }
        } else {
            val peer = prefs.getString("peer", "") ?: ""
            val link = prefs.getString("link", "") ?: ""
            val n = prefs.getString("n", "") ?: ""
            val listen = prefs.getString("listen", "127.0.0.1:9000") ?: ""

            cmdArgs.add(executable)
            cmdArgs.add("-peer")
            cmdArgs.add(peer)
            cmdArgs.add(if (link.contains("yandex")) "-yandex-link" else "-vk-link")
            cmdArgs.add(link)
            cmdArgs.add("-listen")
            cmdArgs.add(listen)

            if (n.isNotEmpty()) {
                cmdArgs.add("-n")
                cmdArgs.add(n)
            }
            if (prefs.getBoolean("udp", false)) cmdArgs.add("-udp")
            if (prefs.getBoolean("noDtls", false)) cmdArgs.add("-no-dtls")
        }

        thread {
            try {
                addLog("Команда: ${cmdArgs.joinToString(" ")}")

                process = ProcessBuilder(cmdArgs)
                    .redirectErrorStream(true)
                    .start()

                val reader = BufferedReader(InputStreamReader(process?.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    addLog(line ?: "")
                }

                // Если процесс завершился, выводим код
                val exitCode = process?.waitFor()
                addLog("=== ПРОЦЕСС ОСТАНОВЛЕН (Код: $exitCode) ===")
            } catch (e: Exception) {
                addLog("КРИТИЧЕСКАЯ ОШИБКА: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        addLog("=== ОСТАНОВКА ИЗ ИНТЕРФЕЙСА ===")
        process?.destroy()
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }
}