package com.vkturn.proxy

import android.content.Context
import android.content.SharedPreferences
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Properties

class SSHManager(context: Context) {
    private val appContext = context.applicationContext
    private val hostKeyPrefs: SharedPreferences by lazy {
        appContext.getSharedPreferences("SshHostKeys", Context.MODE_PRIVATE)
    }

    private var session: Session? = null
    private var shellChannel: ChannelShell? = null
    private var shellOutputStream: OutputStream? = null
    @Volatile private var isShellRunning = false

    // Проверка отпечатка хост-ключа по схеме TOFU (Trust On First Use): при первом
    // подключении к хосту отпечаток сохраняется, при последующих — сверяется.
    // Несовпадение (переустановка сервера или подмена соединения/MITM) блокирует
    // подключение вместо молчаливого принятия любого ключа.
    private fun verifyHostKey(activeSession: Session): String? {
        val hk = activeSession.hostKey ?: return null
        val fingerprint = hk.getFingerPrint(JSch())
        val key = "${activeSession.host}:${activeSession.port}"
        val saved = hostKeyPrefs.getString(key, null)
        return when {
            saved == null -> {
                hostKeyPrefs.edit().putString(key, fingerprint).apply()
                null
            }
            saved == fingerprint -> null
            else -> "ВНИМАНИЕ: хост-ключ сервера $key изменился (было: $saved, стало: $fingerprint). " +
                "Это может означать переустановку сервера ИЛИ подмену соединения (MITM). " +
                "Если переустановка ожидаема — сбросьте сохранённый ключ и подключитесь заново."
        }
    }

    fun forgetHostKey(ip: String, port: Int) {
        hostKeyPrefs.edit().remove("$ip:$port").apply()
    }

    // Устанавливает (или переиспользует уже установленную) авторизованную сессию.
    // Все дальнейшие операции — exec-команды, интерактивный shell, SFTP — используют
    // один и тот же аутентифицированный канал вместо того, чтобы открывать новое
    // TCP+SSH-соединение на каждую команду: раньше одно "Подключиться" плюс проверка
    // статуса сервера создавали до 5 отдельных SSH-хендшейков подряд, что могло
    // спровоцировать блокировку по fail2ban на стороне сервера.
    @Synchronized
    private fun ensureSession(ip: String, port: Int, user: String, pass: String): Result<Session> {
        val existing = session
        if (existing != null && existing.isConnected) return Result.success(existing)
        return try {
            val jsch = JSch()
            val newSession = jsch.getSession(user, ip, port)
            newSession.setPassword(pass)

            val config = Properties()
            config.put("StrictHostKeyChecking", "no")
            newSession.setConfig(config)
            newSession.serverAliveInterval = 10000
            newSession.connect(10000)

            val hostKeyWarning = verifyHostKey(newSession)
            if (hostKeyWarning != null) {
                newSession.disconnect()
                return Result.failure(Exception(hostKeyWarning))
            }

            session = newSession
            Result.success(newSession)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun startShell(ip: String, port: Int, user: String, pass: String, onLogReceived: (String) -> Unit, onDisconnected: (String?) -> Unit) {
        Thread {
            try {
                val activeSession = ensureSession(ip, port, user, pass).getOrElse {
                    onLogReceived("\n[ОШИБКА SHELL]: ${it.message}\n")
                    onDisconnected(it.message)
                    return@Thread
                }

                if (shellChannel != null && shellChannel!!.isConnected) {
                    return@Thread
                }

                shellChannel = activeSession.openChannel("shell") as ChannelShell
                shellChannel?.setPty(true)

                val inStream: InputStream = shellChannel!!.inputStream
                shellOutputStream = shellChannel!!.outputStream

                shellChannel?.connect(5000)
                isShellRunning = true

                val reader = inStream.bufferedReader()
                val buffer = CharArray(1024)
                var read = 0

                while (isShellRunning && reader.read(buffer).also { read = it } != -1) {
                    val output = String(buffer, 0, read)
                    onLogReceived(output)
                }

                if (isShellRunning) {
                    // Stream ended unexpectedly (timeout/dropped connection)
                    isShellRunning = false
                    onDisconnected(null)
                }
            } catch (e: Exception) {
                onLogReceived("\n[ОШИБКА SHELL]: ${e.message}\n")
                isShellRunning = false
                onDisconnected(e.message)
            }
        }.start()
    }

    fun sendShellCommand(command: String) {
        Thread {
            try {
                if (shellOutputStream != null) {
                    shellOutputStream?.write((command + "\r").toByteArray())
                    shellOutputStream?.flush()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    // НОВАЯ ФУНКЦИЯ: Эмуляция Ctrl+C (прерывание процесса)
    fun sendCtrlC() {
        Thread {
            try {
                if (shellOutputStream != null) {
                    shellOutputStream?.write(3) // Байт 3 = ASCII символ ETX (End of Text) = Ctrl+C
                    shellOutputStream?.flush()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    suspend fun executeSilentCommand(ip: String, port: Int, user: String, pass: String, command: String): String = withContext(Dispatchers.IO) {
        try {
            val activeSession = ensureSession(ip, port, user, pass).getOrElse { return@withContext "ERROR: ${it.message}" }

            val channel = activeSession.openChannel("exec") as ChannelExec
            channel.setCommand(command)
            channel.inputStream = null
            channel.setErrStream(null)

            val inStream: InputStream = channel.inputStream
            channel.connect()

            val output = StringBuilder()
            val reader = inStream.bufferedReader()

            var line: String? = null
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }

            channel.disconnect()
            output.toString().trim()
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    // Заливает локальный файл (кастомное серверное ядро) на сервер по SFTP,
    // используя тот же переиспользуемый канал, что и shell/exec-команды.
    suspend fun uploadFile(ip: String, port: Int, user: String, pass: String, localFile: File, remotePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        var sftpChannel: ChannelSftp? = null
        try {
            val activeSession = ensureSession(ip, port, user, pass).getOrElse { return@withContext Result.failure(it) }

            sftpChannel = activeSession.openChannel("sftp") as ChannelSftp
            sftpChannel.connect(10000)

            val remoteDir = remotePath.substringBeforeLast('/', "")
            if (remoteDir.isNotEmpty()) {
                try {
                    sftpChannel.mkdir(remoteDir)
                } catch (e: Exception) {
                    // Каталог, скорее всего, уже существует — игнорируем.
                }
            }

            sftpChannel.put(localFile.absolutePath, remotePath)
            sftpChannel.chmod(493, remotePath) // 0755

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            sftpChannel?.disconnect()
        }
    }

    fun disconnect() {
        isShellRunning = false
        try {
            shellOutputStream?.close()
        } catch (e: Exception) {}
        shellChannel?.disconnect()
        session?.disconnect()
        shellChannel = null
        session = null
        shellOutputStream = null
    }
}
