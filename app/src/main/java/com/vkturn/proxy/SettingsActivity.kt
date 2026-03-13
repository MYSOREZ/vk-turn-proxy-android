package com.vkturn.proxy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*

class SettingsActivity : AppCompatActivity() {

    private val sshManager = SSHManager()
    private lateinit var tvSshLog: TextView
    private lateinit var scrollSshLog: ScrollView
    private lateinit var btnInstallServer: Button
    private lateinit var btnStartProxy: Button
    private lateinit var btnStopProxy: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val editIp = findViewById<EditText>(R.id.editSshIp)
        val editPort = findViewById<EditText>(R.id.editSshPort)
        val editUser = findViewById<EditText>(R.id.editSshUser)
        val editPass = findViewById<EditText>(R.id.editSshPass)
        val editProxyListen = findViewById<EditText>(R.id.editProxyListen)
        val editProxyConnect = findViewById<EditText>(R.id.editProxyConnect)
        val editCustomCmd = findViewById<EditText>(R.id.editCustomCmd)
        val btnSendCmd = findViewById<Button>(R.id.btnSendCmd)
        val btnConnectSsh = findViewById<Button>(R.id.btnConnectSsh)
        val btnCtrlC = findViewById<Button>(R.id.btnCtrlC)

        btnInstallServer = findViewById(R.id.btnInstallServer)
        btnStartProxy = findViewById(R.id.btnStartProxy)
        btnStopProxy = findViewById(R.id.btnStopProxy)
        tvSshLog = findViewById(R.id.tvSshLog)
        scrollSshLog = findViewById(R.id.scrollSshLog)

        val prefs = getSharedPreferences("SshPrefs", Context.MODE_PRIVATE)
        editIp.setText(prefs.getString("ip", ""))
        editPort.setText(prefs.getString("port", "22"))
        editUser.setText(prefs.getString("user", "root"))
        editPass.setText(prefs.getString("pass", ""))
        editProxyListen.setText(prefs.getString("proxyListen", "0.0.0.0:56000"))
        editProxyConnect.setText(prefs.getString("proxyConnect", "127.0.0.1:40537"))

        val savePrefs = {
            prefs.edit().apply {
                putString("ip", editIp.text.toString())
                putString("port", editPort.text.toString())
                putString("user", editUser.text.toString())
                putString("pass", editPass.text.toString())
                putString("proxyListen", editProxyListen.text.toString())
                putString("proxyConnect", editProxyConnect.text.toString())
            }.apply()
        }

        findViewById<Button>(R.id.btnBack).setOnClickListener { savePrefs(); finish() }
        findViewById<Button>(R.id.btnCopySshLog).setOnClickListener {
            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("ssh_logs", tvSshLog.text))
            Toast.makeText(this, "Логи скопированы!", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnClearSshLog).setOnClickListener { tvSshLog.text = "" }

        btnConnectSsh.setOnClickListener {
            savePrefs()
            val ip = editIp.text.toString()
            val pass = editPass.text.toString()
            if (ip.isEmpty() || pass.isEmpty()) return@setOnClickListener

            sshManager.disconnect()
            tvSshLog.text = "[Система]: Подключение к серверу...\\n"
            sshManager.startShell(ip, editPort.text.toString().toIntOrNull() ?: 22, editUser.text.toString(), pass) { output ->
                val clean = output.replace(Regex("\\\\x1B\\\\[[0-9;?]*[a-zA-Z]"), "").replace("\\r", "")
                runOnUiThread {
                    tvSshLog.append(clean)
                    scrollSshLog.post { scrollSshLog.fullScroll(ScrollView.FOCUS_DOWN) }
                }
            }
            checkServerState(ip, editPort.text.toString().toIntOrNull() ?: 22, editUser.text.toString(), pass, "connect")
        }

        btnInstallServer.setOnClickListener {
            val script = """
                mkdir -p /opt/vk-turn && cd /opt/vk-turn && 
                pkill -9 -f "server-linux-" 2>/dev/null;
                ARCH=${'$'}(uname -m); 
                if [ "${'$'}ARCH" = "x86_64" ]; then BIN="server-linux-amd64"; else BIN="server-linux-arm64"; fi; 
                wget -qO ${'$'}BIN https://github.com/cacggghp/vk-turn-proxy/releases/latest/download/${'$'}BIN && 
                chmod +x ${'$'}BIN && echo "Установка завершена!"
            """.trimIndent()

            sshManager.sendShellCommand(script)
            CoroutineScope(Dispatchers.Main).launch {
                delay(6000)
                checkServerState(editIp.text.toString(), editPort.text.toString().toIntOrNull() ?: 22, editUser.text.toString(), editPass.text.toString(), "silent")
            }
        }

        btnStartProxy.setOnClickListener {
            val l = editProxyListen.text.toString().ifEmpty { "0.0.0.0:56000" }
            val c = editProxyConnect.text.toString().ifEmpty { "127.0.0.1:40537" }

            val script = """
                cd /opt/vk-turn && 
                ARCH=${'$'}(uname -m); 
                if [ "${'$'}ARCH" = "x86_64" ]; then BIN="server-linux-amd64"; else BIN="server-linux-arm64"; fi; 
                nohup ./${'$'}BIN -listen $l -connect $c > server.log 2>&1 & 
                echo ${'$'}! > proxy.pid && echo "Сервер запущен (PID: ${'$'}(cat proxy.pid))"
            """.trimIndent()

            sshManager.sendShellCommand(script)
            CoroutineScope(Dispatchers.Main).launch {
                delay(2000)
                checkServerState(editIp.text.toString(), editPort.text.toString().toIntOrNull() ?: 22, editUser.text.toString(), editPass.text.toString(), "silent")
            }
        }

        btnStopProxy.setOnClickListener {
            val script = """
                cd /opt/vk-turn && 
                if [ -f proxy.pid ]; then kill -9 ${'$'}(cat proxy.pid) 2>/dev/null; rm -f proxy.pid; fi;
                pkill -9 -f "server-linux-" 2>/dev/null; 
                echo "Остановлено."
            """.trimIndent()

            sshManager.sendShellCommand(script)
            CoroutineScope(Dispatchers.Main).launch {
                delay(2000)
                checkServerState(editIp.text.toString(), editPort.text.toString().toIntOrNull() ?: 22, editUser.text.toString(), editPass.text.toString(), "silent")
            }
        }

        btnCtrlC.setOnClickListener { sshManager.sendCtrlC(); tvSshLog.append("^C\\n") }

        btnSendCmd.setOnClickListener {
            val cmd = editCustomCmd.text.toString().trim()
            if (cmd.isNotEmpty()) {
                savePrefs()
                if (cmd.lowercase() == "clear") tvSshLog.text = "" else sshManager.sendShellCommand(cmd)
                editCustomCmd.text.clear()
            }
        }
    }

    private fun checkServerState(ip: String, port: Int, user: String, pass: String, mode: String) {
        val checkCmd = """
            if ls /opt/vk-turn/server-linux-* >/dev/null 2>&1; then echo "INSTALLED:YES"; else echo "INSTALLED:NO"; fi
            if ps aux | grep -v grep | grep -q "server-linux-"; then echo "RUNNING:YES"; else echo "RUNNING:NO"; fi
        """.trimIndent()

        CoroutineScope(Dispatchers.IO).launch {
            val result = sshManager.executeSilentCommand(ip, port, user, pass, checkCmd)

            withContext(Dispatchers.Main) {
                if (result.contains("ERROR")) return@withContext

                val isInst = result.contains("INSTALLED:YES")
                val isRun = result.contains("RUNNING:YES")

                btnInstallServer.isEnabled = true

                if (isInst) {
                    btnStartProxy.isEnabled = !isRun
                    btnStopProxy.isEnabled = isRun
                } else {
                    btnStartProxy.isEnabled = false
                    btnStopProxy.isEnabled = false
                }

                if (mode == "connect") {
                    val s = if (isRun) "РАБОТАЕТ" else "ОСТАНОВЛЕН"
                    val i = if (isInst) "УСТАНОВЛЕН" else "НЕ НАЙДЕН"
                    tvSshLog.append("\\n[Система]: vk-turn-proxy $i. Статус: $s.\\n")
                }
            }
        }
    }

    override fun onDestroy() { super.onDestroy(); sshManager.disconnect() }
}