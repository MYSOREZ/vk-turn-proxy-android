package com.vkturn.proxy.viewmodel

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vkturn.proxy.ProxyService
import com.vkturn.proxy.SSHManager
import com.vkturn.proxy.data.AppPreferences
import com.vkturn.proxy.models.*
import com.vkturn.proxy.states.ProxyState
import com.vkturn.proxy.states.SshConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import android.util.Base64
import java.util.UUID
import kotlinx.coroutines.withContext
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay

sealed class ServerState {
    object Unknown : ServerState()
    object Checking : ServerState()
    data class Known(val installed: Boolean, val running: Boolean, val isService: Boolean = false) : ServerState()
    data class Error(val message: String) : ServerState()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AppPreferences(application)
    private val sshManager = SSHManager()

    val clientConfig: StateFlow<ClientConfig> = prefs.clientConfigFlow
    val sshConfig: StateFlow<SshConfig> = prefs.sshConfigFlow

    private val _proxyState = MutableStateFlow<ProxyState>(ProxyState.Idle)
    val proxyState: StateFlow<ProxyState> = _proxyState.asStateFlow()

    private val _sshState = MutableStateFlow<SshConnectionState>(SshConnectionState.Disconnected)
    val sshState: StateFlow<SshConnectionState> = _sshState.asStateFlow()

    private val _serverState = MutableStateFlow<ServerState>(ServerState.Unknown)
    val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    private val _sshLog = MutableStateFlow<List<String>>(emptyList())
    val sshLog: StateFlow<List<String>> = _sshLog.asStateFlow()

    private val _proxyLogs = MutableStateFlow<List<String>>(ProxyService.logBuffer.toList())
    val proxyLogs: StateFlow<List<String>> = _proxyLogs.asStateFlow()

    private val _isKernelPresent = MutableStateFlow<Boolean>(File(application.filesDir, "libcustom_kernel.so").exists())
    val isKernelPresent: StateFlow<Boolean> = _isKernelPresent.asStateFlow()

    // --- Profiles State --- //
    val profiles: StateFlow<List<ProxyProfile>> = prefs.profilesFlow
    val selectedProfileId: StateFlow<String> = prefs.selectedProfileIdFlow
    private val gson = Gson()

    init {
        ProxyService.onLogReceived = { msg ->
            _proxyLogs.value = ProxyService.logBuffer.toList()
            checkForCaptcha(msg)
        }

        // Profile selection is now handled robustly in AppPreferences during initialization

        viewModelScope.launch {
            while (true) {
                if (_proxyState.value !is ProxyState.CaptchaRequired) {
                    val isServiceRunning = ProxyService.isRunning
                    val currentIsRunning = _proxyState.value is ProxyState.Running
                    if (isServiceRunning && !currentIsRunning) {
                        _proxyState.value = ProxyState.Running
                    } else if (!isServiceRunning && currentIsRunning) {
                        _proxyState.value = ProxyState.Idle
                    }
                }
                delay(1000)
            }
        }
    }

    private fun checkForCaptcha(log: String) {
        // Kernel emits "CAPTCHA_SOLVE|<mode>|<url>|<token>" for the manual WebView flow.
        // The url itself never contains "|", so stop the capture there instead of
        // greedily eating the trailing token with it.
        val solveMatch = "CAPTCHA_SOLVE\\|[^|]*\\|(https?://[^|\\s]+)".toRegex().find(log)
        // Fallback for plain URLs in the log (localhost/127.0.0.1 local server,
        // or VK's own auth domains, which have changed over time: login.vk.com -> id.vk.ru).
        val fallbackMatch = "(https?://(localhost:\\d+|127\\.0\\.0\\.1:\\d+|id\\.vk\\.ru|login\\.vk\\.com)\\S*)".toRegex().find(log)
        val captchaUrl = solveMatch?.groupValues?.get(1) ?: fallbackMatch?.value
        if (captchaUrl != null) {
            _proxyState.value = ProxyState.CaptchaRequired(captchaUrl)
            ProxyService.updateNotification("Требуется авторизация", "Нажмите сюда, чтобы пройти капчу!")
        }

        // Return to Running if successfully solved
        if (log.contains("Captcha Solved") || log.contains("Manual captcha solved")) {
            if (_proxyState.value is ProxyState.CaptchaRequired) {
                viewModelScope.launch {
                    delay(3000)
                    _proxyState.value = ProxyState.Running
                    ProxyService.updateNotification("TURN Proxy", "Работает в фоне")
                }
            }
        }
    }

    fun saveClientConfig(config: ClientConfig) {
        prefs.saveClientConfig(config)
        // Обновляем конфиг в текущем выбранном профиле
        val currentProfiles = profiles.value.toMutableList()
        val index = currentProfiles.indexOfFirst { it.id == selectedProfileId.value }
        if (index != -1) {
            currentProfiles[index] = currentProfiles[index].copy(config = config)
            prefs.saveProfiles(currentProfiles)
        }
    }

    fun saveSshConfig(config: SshConfig) {
        prefs.saveSshConfig(config)
    }

    // --- Profiles Operations --- //

    fun selectProfile(id: String) {
        val profile = profiles.value.find { it.id == id }
        if (profile != null) {
            prefs.saveSelectedProfileId(id)
            prefs.saveClientConfig(profile.config)
        }
    }

    fun addProfile(name: String, config: ClientConfig = clientConfig.value) {
        val newProfile = ProxyProfile(name = name, config = config)
        val newProfiles = profiles.value + newProfile
        prefs.saveProfiles(newProfiles)
        selectProfile(newProfile.id)
    }

    fun deleteProfile(id: String) {
        if (profiles.value.size <= 1) return // Нельзя удалить последний
        val newProfiles = profiles.value.filter { it.id != id }
        prefs.saveProfiles(newProfiles)
        if (selectedProfileId.value == id) {
            selectProfile(newProfiles.first().id)
        }
    }

    fun resetProfile(id: String) {
        val currentProfiles = profiles.value.toMutableList()
        val index = currentProfiles.indexOfFirst { it.id == id }
        if (index != -1) {
            val defaultConfig = prefs.createDefaultConfig()
            currentProfiles[index] = currentProfiles[index].copy(config = defaultConfig)
            prefs.saveProfiles(currentProfiles)
            if (id == selectedProfileId.value) {
                prefs.saveClientConfig(defaultConfig)
            }
            Toast.makeText(getApplication(), "Настройки профиля '${currentProfiles[index].name}' сброшены", Toast.LENGTH_SHORT).show()
        }
    }

    fun renameProfile(id: String, newName: String) {
        val newProfiles = profiles.value.map {
            if (it.id == id) it.copy(name = newName) else it
        }
        prefs.saveProfiles(newProfiles)
    }

    // --- Flag Management --- //

    fun toggleFlag(flagId: String) {
        val currentFlags = clientConfig.value.customFlags.toMutableList()
        val index = currentFlags.indexOfFirst { it.id == flagId }
        if (index != -1) {
            currentFlags[index] = currentFlags[index].copy(enabled = !currentFlags[index].enabled)
            saveClientConfig(clientConfig.value.copy(customFlags = currentFlags))
        }
    }

    fun addCustomFlag(label: String, argument: String) {
        val currentFlags = clientConfig.value.customFlags.toMutableList()
        currentFlags.add(ProxyFlag(label = label, argument = argument, enabled = true))
        saveClientConfig(clientConfig.value.copy(customFlags = currentFlags))
    }

    fun deleteFlag(flagId: String) {
        val currentFlags = clientConfig.value.customFlags.filter {
            it.id != flagId || !it.deletable // Prevent deletion if not deletable anyway, but we'll hide the button in UI
        }
        val protectedFlag = clientConfig.value.customFlags.find { it.id == flagId }
        if (protectedFlag?.deletable == false) return

        saveClientConfig(clientConfig.value.copy(customFlags = currentFlags))
    }

    fun moveFlag(flagId: String, up: Boolean) {
        val currentFlags = clientConfig.value.customFlags.toMutableList()
        val index = currentFlags.indexOfFirst { it.id == flagId }
        if (index == -1) return

        val newIndex = if (up) index - 1 else index + 1
        if (newIndex in currentFlags.indices) {
            val item = currentFlags.removeAt(index)
            currentFlags.add(newIndex, item)
            saveClientConfig(clientConfig.value.copy(customFlags = currentFlags))
        }
    }

    fun updateFlag(flagId: String, label: String, argument: String) {
        val currentFlags = clientConfig.value.customFlags.toMutableList()
        val index = currentFlags.indexOfFirst { it.id == flagId }
        if (index != -1) {
            currentFlags[index] = currentFlags[index].copy(label = label, argument = argument)
            saveClientConfig(clientConfig.value.copy(customFlags = currentFlags))
        }
    }

    // --- Import / Export --- //

    fun getProfileExportData(id: String): String? {
        val profile = profiles.value.find { it.id == id } ?: return null
        val json = gson.toJson(profile)
        return compressAndEncode(json)
    }

    fun getAllProfilesExportData(): String {
        val json = gson.toJson(profiles.value)
        return compressAndEncode(json)
    }

    private fun compressAndEncode(data: String): String {
        val bytes = data.toByteArray(Charsets.UTF_8)
        val baos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(baos).use { it.write(bytes) }
        val prefix = "VKTGZ:"
        return prefix + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    fun exportProfile(id: String) {
        val base64 = getProfileExportData(id) ?: return
        val clipboard = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = android.content.ClipData.newPlainText("VK Turn Profile", base64)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(getApplication(), "Профиль скопирован", Toast.LENGTH_SHORT).show()
    }

    fun exportAllProfiles() {
        val base64 = getAllProfilesExportData()
        val clipboard = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = android.content.ClipData.newPlainText("VK Turn All Profiles", base64)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(getApplication(), "Все профили (${profiles.value.size}) скопированы", Toast.LENGTH_SHORT).show()
    }

    private fun generateUniqueName(baseName: String): String {
        val existingNames = profiles.value.map { it.name }
        if (!existingNames.contains(baseName)) return baseName

        var counter = 2
        while (existingNames.contains("$baseName $counter")) {
            counter++
        }
        return "$baseName $counter"
    }

    fun handleProfileImport(text: String, showToast: Boolean = true) {
        if (text.isBlank()) return

        try {
            val decoded = try {
                if (text.startsWith("VKTGZ:")) {
                    val base64Str = text.substring(6)
                    val compressed = Base64.decode(base64Str, Base64.DEFAULT)
                    val bais = java.io.ByteArrayInputStream(compressed)
                    val resultBytes = java.util.zip.GZIPInputStream(bais).readBytes()
                    String(resultBytes, Charsets.UTF_8)
                } else {
                    val data = Base64.decode(text, Base64.DEFAULT)
                    String(data)
                }
            } catch (e: Exception) {
                text
            }

            if (decoded.trim().startsWith("[")) {
                val type = object : TypeToken<List<ProxyProfile>>() {}.type
                val importedList: List<ProxyProfile> = gson.fromJson(decoded, type)
                val currentProfiles = profiles.value.toMutableList()

                importedList.forEach { imported ->
                    val migratedConfig = AppPreferences.migrateConfig(imported.config)
                    val uniqueName = generateUniqueName(imported.name)
                    currentProfiles.add(ProxyProfile(
                        name = uniqueName,
                        config = migratedConfig,
                        isDefault = false // Ensure imported is NEVER default
                    ))
                }
                prefs.saveProfiles(currentProfiles)
                if (showToast) Toast.makeText(getApplication(), "Импортировано профилей: ${importedList.size}", Toast.LENGTH_SHORT).show()
            } else {
                val imported: ProxyProfile = gson.fromJson(decoded, ProxyProfile::class.java)
                val migratedConfig = AppPreferences.migrateConfig(imported.config)
                val uniqueName = generateUniqueName(imported.name)
                addProfile(uniqueName, migratedConfig)
                if (showToast) Toast.makeText(getApplication(), "Профиль '$uniqueName' импортирован", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            if (showToast) Toast.makeText(getApplication(), "Ошибка импорта: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun importProfileFromClipboard() {
        val clipboard = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: return
        handleProfileImport(text)
    }

    fun startProxy() {
        if (_proxyState.value is ProxyState.Running) return
        _proxyState.value = ProxyState.Starting
        val intent = Intent(getApplication(), ProxyService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            getApplication<Application>().startForegroundService(intent)
        } else {
            getApplication<Application>().startService(intent)
        }
    }

    fun stopProxy() {
        val intent = Intent(getApplication(), ProxyService::class.java)
        getApplication<Application>().stopService(intent)
        _proxyState.value = ProxyState.Idle
    }

    fun dismissCaptcha() {
        _proxyState.value = if (ProxyService.isRunning) ProxyState.Running else ProxyState.Idle
        ProxyService.updateNotification("TURN Proxy", "Работает в фоне")
    }

    fun clearProxyLogs() {
        ProxyService.logBuffer.clear()
        _proxyLogs.value = emptyList()
    }

    fun clearSshLogs() {
        _sshLog.value = emptyList()
    }

    // --- Kernel Management (client-side) --- //

    private fun getKernelFile() = File(getApplication<Application>().filesDir, "libcustom_kernel.so")

    suspend fun importKernel(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        try {
            val context = getApplication<Application>()
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(20)
                val read = input.read(buffer, 0, 20)
                if (read == -1) null else buffer.sliceArray(0 until read)
            }
                ?: return@withContext Result.failure(Exception("Не удалось прочитать файл"))

            if (bytes.size < 20 || bytes[0] != 0x7F.toByte() || bytes[1] != 'E'.code.toByte() || bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte()) {
                return@withContext Result.failure(Exception("Файл не является ELF-бинарником"))
            }

            val machine = bytes[18].toInt() and 0xFF
            val primaryAbi = Build.SUPPORTED_ABIS[0]

            val isCompatible = when {
                primaryAbi.contains("arm64") -> machine == 183 // EM_AARCH64
                primaryAbi.contains("armeabi") -> machine == 40 // EM_ARM
                primaryAbi.contains("x86_64") -> machine == 62 // EM_X86_64
                primaryAbi.contains("x86") -> machine == 3 // EM_386
                else -> true // Unknown ABI, let it try
            }

            if (!isCompatible) {
                val foundArch = when(machine) {
                    183 -> "ARM 64-bit"
                    40 -> "ARM 32-bit"
                    62 -> "x86 64-bit"
                    3 -> "x86 32-bit"
                    else -> "Unknown ($machine)"
                }
                return@withContext Result.failure(Exception("Несовместимая архитектура: $foundArch. Ваше устройство: $primaryAbi"))
            }

            // Copy file
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(getKernelFile()).use { output ->
                    input.copyTo(output)
                }
            }
            getKernelFile().setExecutable(true)
            _isKernelPresent.value = true
            Result.success("Ядро успешно импортировано")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun deleteKernel() {
        getKernelFile().delete()
        _isKernelPresent.value = false
    }

    // --- SSH Logic --- //

    fun connectSsh() {
        val config = sshConfig.value
        if (config.ip.isEmpty() || config.password.isEmpty()) return

        _sshState.value = SshConnectionState.Connecting
        sshManager.disconnect()
        _sshLog.value = listOf("[Система]: Проверка доступа к серверу...")

        viewModelScope.launch(Dispatchers.IO) {
            val checkAuth = sshManager.executeSilentCommand(config.ip, config.port, config.username, config.password, "echo OK")
            if (checkAuth.contains("ERROR:") || !checkAuth.contains("OK")) {
                var errorMessage = checkAuth.replace("ERROR:", "").trim()
                if (errorMessage.contains("Connection refused", ignoreCase = true) || errorMessage.contains("ECONNREFUSED", ignoreCase = true)) {
                    errorMessage += "\n\nПохоже, сервер отклонил соединение. Возможно, в вашем регионе принудительно включены «белые списки» (БС) или SSH-сервис на сервере временно недоступен."
                } else if (errorMessage.contains("Auth fail", ignoreCase = true) || errorMessage.contains("Auth cancel", ignoreCase = true)) {
                    errorMessage = "Неверный пароль или имя пользователя"
                }
                _sshState.value = SshConnectionState.Error("Ошибка: $errorMessage")
                _sshLog.value = _sshLog.value + "[Ошибка]: ${errorMessage.take(60)}..."
            } else {
                _sshState.value = SshConnectionState.Connected(config.ip)
                _sshLog.value = _sshLog.value + "[Система]: Доступ разрешен. Устанавливаем терминал..."
                checkServerState()

                sshManager.startShell(config.ip, config.port, config.username, config.password, onLogReceived = { output ->
                    if (output.contains("[H") || output.contains("[2J") || output.contains("[c")) {
                        _sshLog.value = emptyList()
                    }
                    val clean = output.replace(Regex("\\x1B\\[[0-9;?]*[a-zA-Z]"), "").replace("\r", "")
                    if (clean.isNotBlank()) {
                        val lines = clean.split("\n").filter { it.isNotBlank() }
                        _sshLog.value = (_sshLog.value + lines).takeLast(200)
                    }
                }, onDisconnected = { error ->
                    _sshState.value = SshConnectionState.Error("Связь разорвана: ${error ?: "Таймаут/Обрыв соединения"}")
                    _sshLog.value = _sshLog.value + "[Система]: Сессия SSH прервана. Требуется переподключение."
                    _serverState.value = ServerState.Unknown
                })
            }
        }
    }

    fun checkServerState() {
        if (_sshState.value !is SshConnectionState.Connected) return
        _serverState.value = ServerState.Checking
        val config = sshConfig.value
        val bin = shq(effectiveBinName(config))

        viewModelScope.launch(Dispatchers.IO) {
            val checkInstall = sshManager.executeSilentCommand(config.ip, config.port, config.username, config.password, "test -f /opt/vk-turn/$bin && echo FOUND")
            val installed = checkInstall.contains("FOUND")

            // Проверяем статус через systemctl или ps
            val checkService = sshManager.executeSilentCommand(config.ip, config.port, config.username, config.password, "systemctl is-active vk-turn-proxy 2>/dev/null")
            val isService = checkService.trim() == "active"
            var running = isService

            if (!running) {
                val checkPs = sshManager.executeSilentCommand(config.ip, config.port, config.username, config.password, "pgrep -f $bin >/dev/null 2>&1 && echo RUNNING")
                running = checkPs.contains("RUNNING")
            }

            _serverState.value = ServerState.Known(installed, running, isService)
        }
    }

    fun disconnectSsh() {
        sshManager.disconnect()
        _sshState.value = SshConnectionState.Disconnected
        _serverState.value = ServerState.Unknown
        val log = _sshLog.value.toMutableList()
        log.add("[Система]: Отключено.")
        _sshLog.value = log
    }

    fun sendSshCommand(command: String) {
        if (_sshState.value is SshConnectionState.Connected) {
            sshManager.sendShellCommand(command)
        }
    }

    // Безопасно оборачивает произвольную строку в одинарные кавычки для вставки
    // в shell-скрипт (защита от command injection через поля listen/connect/флаги).
    private fun shq(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private fun effectiveBinName(config: SshConfig): String = config.serverBinName.ifBlank { "vk-turn-server" }

    // Общий блок настройки systemd-сервиса: используется и после скачивания по ссылке,
    // и после ручной SFTP-загрузки бинарника, чтобы кастомное имя/флаги применялись
    // одинаково независимо от способа доставки ядра на сервер.
    private fun setupServiceScript(config: SshConfig): String {
        val bin = shq(effectiveBinName(config))
        val listen = shq(config.proxyListen)
        val connect = shq(config.proxyConnect)
        val extra = shq(config.serverExtraFlags.trim())
        return """
            BIN=$bin
            LISTEN=$listen
            CONNECT=$connect
            EXTRA_FLAGS=$extra
            chmod +x "/opt/vk-turn/${'$'}BIN" 2>/dev/null
            (
                cat <<EOF > /opt/vk-turn/vk-turn-proxy.service
[Unit]
Description=VK Turn Proxy Service
After=network.target

[Service]
Type=simple
ExecStart=/opt/vk-turn/${'$'}BIN -listen ${'$'}LISTEN -connect ${'$'}CONNECT ${'$'}EXTRA_FLAGS
KillMode=process
Restart=always
RestartSec=5
User=nobody
Group=nogroup
StandardOutput=append:/var/log/vk-turn/server.log
StandardError=append:/var/log/vk-turn/error.log
SyslogIdentifier=vk-turn-proxy

[Install]
WantedBy=multi-user.target
EOF
                mkdir -p /var/log/vk-turn && chown nobody:nogroup /var/log/vk-turn 2>/dev/null
                cp /opt/vk-turn/vk-turn-proxy.service /etc/systemd/system/ 2>/dev/null && \
                systemctl daemon-reload 2>/dev/null && \
                systemctl enable vk-turn-proxy 2>/dev/null && \
                echo "Сервис Systemd настроен!"
            ) || echo "Продолжаем без системного сервиса (нет прав root)"
            echo "Установка завершена!"
        """.trimIndent()
    }

    // Устанавливает/обновляет серверное ядро по ссылке, указанной пользователем в
    // sshConfig.kernelSourceUrl. Никакого зашитого по умолчанию источника нет —
    // ссылку обязательно нужно указать самому (или воспользоваться uploadServerBinary
    // для ручной загрузки файла с телефона по SFTP).
    fun installServer() {
        val config = sshConfig.value
        if (config.kernelSourceUrl.isBlank()) {
            _sshLog.value = _sshLog.value + "[Ошибка]: Укажите ссылку на серверное ядро в поле выше, либо загрузите файл вручную кнопкой «Загрузить файл»."
            return
        }
        val bin = shq(effectiveBinName(config))
        val url = shq(config.kernelSourceUrl.trim())
        val script = """
            BIN=$bin
            RAW_URL=$url
            ARCH_TAG=${'$'}(uname -m); if [ "${'$'}ARCH_TAG" = "x86_64" ]; then ARCH_TAG=amd64; else ARCH_TAG=arm64; fi
            URL=${'$'}{RAW_URL//\{arch\}/${'$'}ARCH_TAG}
            mkdir -p /opt/vk-turn && cd /opt/vk-turn && pkill -9 -f "${'$'}BIN" 2>/dev/null;
            wget -qO "${'$'}BIN" "${'$'}URL" && chmod +x "${'$'}BIN" && echo "Ядро загружено: ${'$'}BIN" || echo "Ошибка загрузки ядра по ссылке"
        """.trimIndent() + "\n" + setupServiceScript(config)
        sshManager.sendShellCommand(script)
        // Wait a bit and check state
        viewModelScope.launch {
            delay(5000)
            checkServerState()
        }
    }

    // Заливает выбранный пользователем локальный файл (кастомное серверное ядро)
    // на сервер по SFTP и настраивает systemd-сервис под него.
    suspend fun uploadServerBinary(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        val config = sshConfig.value
        if (config.ip.isEmpty() || config.password.isEmpty()) {
            return@withContext Result.failure(Exception("Сначала подключитесь к серверу"))
        }
        val context = getApplication<Application>()
        val binName = effectiveBinName(config)
        val tempFile = File(context.cacheDir, "upload_$binName")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output -> input.copyTo(output) }
            } ?: return@withContext Result.failure(Exception("Не удалось прочитать файл"))

            val result = sshManager.uploadFile(config.ip, config.port, config.username, config.password, tempFile, "/opt/vk-turn/$binName")
            if (result.isFailure) {
                return@withContext Result.failure(result.exceptionOrNull() ?: Exception("Ошибка загрузки"))
            }

            _sshLog.value = _sshLog.value + "[Система]: Бинарник загружен на сервер (/opt/vk-turn/$binName)."
            sshManager.sendShellCommand(setupServiceScript(config))
            viewModelScope.launch {
                delay(3000)
                checkServerState()
            }
            Result.success("Ядро загружено и настроено")
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            tempFile.delete()
        }
    }

    fun startServer() {
        val config = sshConfig.value
        val bin = shq(effectiveBinName(config))
        val listen = shq(config.proxyListen)
        val connect = shq(config.proxyConnect)
        val extra = shq(config.serverExtraFlags.trim())
        val script = """
            BIN=$bin
            LISTEN=$listen
            CONNECT=$connect
            EXTRA_FLAGS=$extra
            if systemctl is-enabled vk-turn-proxy >/dev/null 2>&1; then
                systemctl restart vk-turn-proxy && echo "Сервис запущен (systemd)"
            else
                cd /opt/vk-turn &&
                nohup "./${'$'}BIN" -listen "${'$'}LISTEN" -connect "${'$'}CONNECT" ${'$'}EXTRA_FLAGS > server.log 2>&1 &
                echo ${'$'}! > proxy.pid && echo "Сервер запущен вручную (PID: ${'$'}(cat proxy.pid))"
            fi
        """.trimIndent()
        sshManager.sendShellCommand(script)
        viewModelScope.launch {
            delay(2000)
            checkServerState()
        }
    }

    fun stopServer() {
        val config = sshConfig.value
        val bin = shq(effectiveBinName(config))
        val script = """
            BIN=$bin
            if systemctl is-active vk-turn-proxy >/dev/null 2>&1; then
                systemctl stop vk-turn-proxy && echo "Сервис остановлен"
            else
                cd /opt/vk-turn &&
                if [ -f proxy.pid ]; then kill -9 ${'$'}(cat proxy.pid) 2>/dev/null; rm -f proxy.pid; fi;
                pkill -9 -f "${'$'}BIN" 2>/dev/null;
                echo "Остановлено вручную."
            fi
        """.trimIndent()
        sshManager.sendShellCommand(script)
        viewModelScope.launch {
            delay(2000)
            checkServerState()
        }
    }
}
