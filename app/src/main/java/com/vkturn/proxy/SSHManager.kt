package com.vkturn.proxy

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

class SSHManager {
    private var session: Session? = null
    private var shellChannel: ChannelShell? = null
    private var shellOutputStream: OutputStream? = null
    @Volatile private var isShellRunning = false

    fun startShell(ip: String, port: Int, user: String, pass: String, onLogReceived: (String) -> Unit, onDisconnected: (String?) -> Unit) {
        Thread {
            try {
                if (session == null || !session!!.isConnected) {
                    val jsch = JSch()
                    session = jsch.getSession(user, ip, port)
                    session?.setPassword(pass)

                    val config = Properties()
                    config.put("StrictHostKeyChecking", "no")
                    session?.setConfig(config)
                    session?.serverAliveInterval = 10000 // Send keep-alive packet every 10 seconds
                    session?.connect(10000)
                }

                if (shellChannel != null && shellChannel!!.isConnected) {
                    return@Thread
                }

                shellChannel = session?.openChannel("shell") as ChannelShell
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
        var tempSession: Session? = null
        try {
            val jsch = JSch()
            tempSession = jsch.getSession(user, ip, port)
            tempSession.setPassword(pass)

            val config = Properties()
            config.put("StrictHostKeyChecking", "no")
            tempSession.setConfig(config)
            tempSession.connect(5000)

            val channel = tempSession.openChannel("exec") as ChannelExec
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
        } finally {
            tempSession?.disconnect()
        }
    }

    // Заливает локальный файл (кастомное серверное ядро) на сервер по SFTP.
    // Используется отдельная временная сессия, не пересекающаяся с интерактивным shell.
    suspend fun uploadFile(ip: String, port: Int, user: String, pass: String, localFile: File, remotePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        var tempSession: Session? = null
        var sftpChannel: ChannelSftp? = null
        try {
            val jsch = JSch()
            tempSession = jsch.getSession(user, ip, port)
            tempSession.setPassword(pass)

            val config = Properties()
            config.put("StrictHostKeyChecking", "no")
            tempSession.setConfig(config)
            tempSession.connect(10000)

            sftpChannel = tempSession.openChannel("sftp") as ChannelSftp
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
            tempSession?.disconnect()
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
