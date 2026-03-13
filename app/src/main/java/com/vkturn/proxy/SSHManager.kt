package com.vkturn.proxy

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.Properties

class SSHManager {
    private var session: Session? = null
    private var shellChannel: ChannelShell? = null
    private var shellOutputStream: OutputStream? = null
    @Volatile private var isShellRunning = false

    fun startShell(ip: String, port: Int, user: String, pass: String, onLogReceived: (String) -> Unit) {
        Thread {
            try {
                if (session == null || !session!!.isConnected) {
                    val jsch = JSch()
                    session = jsch.getSession(user, ip, port)
                    session?.setPassword(pass)

                    val config = Properties()
                    config.put("StrictHostKeyChecking", "no")
                    session?.setConfig(config)
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
            } catch (e: Exception) {
                onLogReceived("\\n[ОШИБКА SHELL]: ${e.message}\\n")
            }
        }.start()
    }

    fun sendShellCommand(command: String) {
        Thread {
            try {
                if (shellOutputStream != null) {
                    shellOutputStream?.write((command + "\\r").toByteArray())
                    shellOutputStream?.flush()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    fun sendCtrlC() {
        Thread {
            try {
                if (shellOutputStream != null) {
                    shellOutputStream?.write(3)
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
            val inStream: InputStream = channel.inputStream
            channel.connect()

            val output = StringBuilder()
            val reader = inStream.bufferedReader()

            var line: String? = null
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\\n")
            }

            channel.disconnect()
            output.toString().trim()
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        } finally {
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