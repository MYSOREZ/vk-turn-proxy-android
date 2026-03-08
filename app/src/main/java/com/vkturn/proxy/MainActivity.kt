package com.vkturn.proxy

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var tvLogs: TextView
    private lateinit var logScrollView: ScrollView
    private lateinit var btnToggle: Button

    // Обработка выбора файла для обновления ядра
    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                try {
                    val inputStream = contentResolver.openInputStream(uri)
                    val customBin = File(filesDir, "custom_vkturn")
                    val outputStream = FileOutputStream(customBin)
                    inputStream?.copyTo(outputStream)
                    inputStream?.close()
                    outputStream.close()
                    customBin.setExecutable(true)
                    ProxyService.addLog("СИСТЕМА: Кастомный бинарник установлен в /files/custom_vkturn")
                    Toast.makeText(this, "Ядро обновлено!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    ProxyService.addLog("ОШИБКА ОБНОВЛЕНИЯ: ${e.message}")
                }
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (!isGranted) Toast.makeText(this, "Разрешите уведомления для фоновой работы!", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Инициализация элементов
        tvLogs = findViewById(R.id.tvLogs)
        logScrollView = findViewById(R.id.logScrollView)
        btnToggle = findViewById(R.id.btnToggle)
        tvLogs.setTextIsSelectable(true) // Позволяет выделять текст логов

        val switchRawMode = findViewById<Switch>(R.id.switchRawMode)
        val editRawCommand = findViewById<EditText>(R.id.editRawCommand)
        val layoutGuiSettings = findViewById<LinearLayout>(R.id.layoutGuiSettings)
        val editPeer = findViewById<EditText>(R.id.editPeer)
        val editLink = findViewById<EditText>(R.id.editLink)
        val editN = findViewById<EditText>(R.id.editN)
        val checkUdp = findViewById<CheckBox>(R.id.checkUdp)
        val checkNoDtls = findViewById<CheckBox>(R.id.checkNoDtls)
        val editListen = findViewById<EditText>(R.id.editListen)

        // Загрузка настроек
        val prefs = getSharedPreferences("ProxyPrefs", Context.MODE_PRIVATE)
        switchRawMode.isChecked = prefs.getBoolean("isRaw", false)
        editRawCommand.setText(prefs.getString("rawCmd", ""))
        editPeer.setText(prefs.getString("peer", ""))
        editLink.setText(prefs.getString("link", ""))
        editN.setText(prefs.getString("n", "8"))
        checkUdp.isChecked = prefs.getBoolean("udp", true)
        checkNoDtls.isChecked = prefs.getBoolean("noDtls", false)
        editListen.setText(prefs.getString("listen", "127.0.0.1:9000"))

        // Переключение режимов интерфейса
        val updateUiState = {
            if (switchRawMode.isChecked) {
                editRawCommand.visibility = View.VISIBLE
                layoutGuiSettings.visibility = View.GONE
            } else {
                editRawCommand.visibility = View.GONE
                layoutGuiSettings.visibility = View.VISIBLE
            }
        }
        updateUiState()
        switchRawMode.setOnCheckedChangeListener { _, _ -> updateUiState() }

        // Кнопки управления
        findViewById<Button>(R.id.btnUpdateBinary).setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
            filePicker.launch(intent)
        }

        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }

        findViewById<Button>(R.id.btnCopyLogs).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("vk_turn_logs", tvLogs.text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Логи скопированы!", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnClearLogs).setOnClickListener {
            ProxyService.logBuffer.clear()
            tvLogs.text = "Консоль очищена."
        }

        btnToggle.setOnClickListener {
            if (!ProxyService.isRunning) {
                // Сохранение перед стартом
                prefs.edit().apply {
                    putBoolean("isRaw", switchRawMode.isChecked)
                    putString("rawCmd", editRawCommand.text.toString())
                    putString("peer", editPeer.text.toString())
                    putString("link", editLink.text.toString())
                    putString("n", editN.text.toString())
                    putBoolean("udp", checkUdp.isChecked)
                    putBoolean("noDtls", checkNoDtls.isChecked)
                    putString("listen", editListen.text.toString())
                }.apply()

                checkPermissionsAndStart()
            } else {
                stopService(Intent(this, ProxyService::class.java))
                btnToggle.text = "ЗАПУСТИТЬ ПРОКСИ"
            }
        }
    }

    private fun checkPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        ProxyService.logBuffer.clear()
        startForegroundService(Intent(this, ProxyService::class.java))
        btnToggle.text = "ОСТАНОВИТЬ ПРОКСИ"
    }

    override fun onResume() {
        super.onResume()
        btnToggle.text = if (ProxyService.isRunning) "ОСТАНОВИТЬ ПРОКСИ" else "ЗАПУСТИТЬ ПРОКСИ"
        tvLogs.text = ProxyService.logBuffer.joinToString("\n")
        scrollLogsToEnd()

        ProxyService.onLogReceived = { msg ->
            runOnUiThread {
                if (tvLogs.text.length > 25000) tvLogs.text = tvLogs.text.substring(10000)
                tvLogs.append("\n$msg")
                scrollLogsToEnd()
            }
        }
    }

    private fun scrollLogsToEnd() {
        logScrollView.post { logScrollView.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onPause() {
        super.onPause()
        ProxyService.onLogReceived = null
    }
}