package com.vkturn.proxy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.service.quicksettings.TileService
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
        var onStateChanged: ((Boolean) -> Unit)? = null

        fun addLog(msg: String) {
            if (logBuffer.size > 200) logBuffer.removeAt(0)
            logBuffer.add(msg)
            onLogReceived?.invoke(msg)
        }

        private fun notifyStateChanged(isRunning: Boolean) {
            onStateChanged?.invoke(isRunning)
        }

        fun startProxy(context: Context) {
            logBuffer.clear()

            val intent = Intent(context, ProxyService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopProxy(context: Context) {
            context.stopService(Intent(context, ProxyService::class.java))
        }

        fun toggleProxy(context: Context): Boolean {
            return if (isRunning) {
                stopProxy(context)
                false
            } else {
                startProxy(context)
                true
            }
        }

        fun requestTileRefresh(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

            TileService.requestListeningState(
                context,
                ComponentName(context.packageName, "${context.packageName}.ProxyTileService")
            )
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

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val openAppPendingIntent = PendingIntent.getActivity(this, 0, openAppIntent, pendingIntentFlags)

        val notification = NotificationCompat.Builder(this, "ProxyChannel")
            .setContentTitle("VK TURN Proxy")
            .setContentText("Работает в фоне")
            .setSmallIcon(android.R.drawable.ic_menu_preferences)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .build()
        startForeground(1, notification)
        isRunning = true
        notifyStateChanged(true)
        requestTileRefresh(this)

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
        notifyStateChanged(false)
        addLog("=== ОСТАНОВКА ИЗ ИНТЕРФЕЙСА ===")
        process?.destroy()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        requestTileRefresh(this)
    }
}
