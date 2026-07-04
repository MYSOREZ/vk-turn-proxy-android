package com.vkturn.proxy.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.input.KeyboardType
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vkturn.proxy.viewmodel.MainViewModel
import com.vkturn.proxy.viewmodel.ServerState
import com.vkturn.proxy.states.SshConnectionState
import com.vkturn.proxy.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshScreen(viewModel: MainViewModel) {
    val sshConfig by viewModel.sshConfig.collectAsStateWithLifecycle()
    val sshState by viewModel.sshState.collectAsStateWithLifecycle()
    val serverState by viewModel.serverState.collectAsStateWithLifecycle()
    val sshLog by viewModel.sshLog.collectAsStateWithLifecycle()

    var ip by remember(sshConfig) { mutableStateOf(sshConfig.ip) }
    var port by remember(sshConfig) { mutableStateOf(sshConfig.port.toString()) }
    var user by remember(sshConfig) { mutableStateOf(sshConfig.username) }
    var pass by remember(sshConfig) { mutableStateOf(sshConfig.password) }
    var proxyListen by remember(sshConfig) { mutableStateOf(sshConfig.proxyListen) }
    var proxyConnect by remember(sshConfig) { mutableStateOf(sshConfig.proxyConnect) }
    var kernelSourceUrl by remember(sshConfig) { mutableStateOf(sshConfig.kernelSourceUrl) }
    var serverBinName by remember(sshConfig) { mutableStateOf(sshConfig.serverBinName) }
    var serverExtraFlags by remember(sshConfig) { mutableStateOf(sshConfig.serverExtraFlags) }
    var authMethod by remember(sshConfig) { mutableStateOf(sshConfig.authMethod) }
    var privateKeyPem by remember(sshConfig) { mutableStateOf(sshConfig.privateKeyPem) }
    var keyPassphrase by remember(sshConfig) { mutableStateOf(sshConfig.keyPassphrase) }

    var customCommand by remember { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboardManager = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }

    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            scope.launch {
                val result = viewModel.uploadServerBinary(it)
                if (result.isSuccess) {
                    Toast.makeText(context, result.getOrNull() ?: "Ядро загружено", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, result.exceptionOrNull()?.message ?: "Ошибка загрузки", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val keyFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val text = context.contentResolver.openInputStream(it)?.bufferedReader()?.use { r -> r.readText() }
            if (text != null) privateKeyPem = text
        }
    }

    // Пароль и приватный ключ намеренно не входят в этот автосейв: раньше пароль
    // писался в SharedPreferences на каждое нажатие клавиши. Сохраняем чувствительные
    // поля отдельно, один раз, непосредственно перед подключением (см. кнопку ниже).
    LaunchedEffect(ip, port, user, proxyListen, proxyConnect, kernelSourceUrl, serverBinName, serverExtraFlags, authMethod) {
        viewModel.saveSshConfig(
            sshConfig.copy(
                ip = ip,
                port = port.toIntOrNull() ?: 22,
                username = user,
                proxyListen = proxyListen,
                proxyConnect = proxyConnect,
                kernelSourceUrl = kernelSourceUrl,
                serverBinName = serverBinName,
                serverExtraFlags = serverExtraFlags,
                authMethod = authMethod
            )
        )
    }

    val isConnected = sshState is SshConnectionState.Connected
    val isConnecting = sshState is SshConnectionState.Connecting
    val isInstalled = (serverState as? ServerState.Known)?.installed == true
    val isRunning = (serverState as? ServerState.Known)?.running == true

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("SSH Сервер", style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(
            value = ip,
            onValueChange = { ip = it },
            label = { Text("IP сервера") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !isConnected
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = port,
                onValueChange = { port = it },
                label = { Text("Порт") },
                modifier = Modifier.weight(0.3f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                enabled = !isConnected
            )
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text("Пользователь") },
                modifier = Modifier.weight(0.7f),
                singleLine = true,
                enabled = !isConnected
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = authMethod == "password",
                onClick = { authMethod = "password" },
                label = { Text("Пароль") },
                enabled = !isConnected
            )
            FilterChip(
                selected = authMethod == "key",
                onClick = { authMethod = "key" },
                label = { Text("SSH-ключ") },
                enabled = !isConnected
            )
        }

        if (authMethod == "password") {
            OutlinedTextField(
                value = pass,
                onValueChange = { pass = it },
                label = { Text("Пароль") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isConnected
            )
        } else {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val pub = viewModel.generateAndSaveKeyPair()
                        privateKeyPem = viewModel.sshConfig.value.privateKeyPem
                        keyPassphrase = ""
                        clipboardManager.setPrimaryClip(ClipData.newPlainText("SSH public key", pub))
                        Toast.makeText(context, "Ключ сгенерирован, публичный ключ скопирован в буфер", Toast.LENGTH_LONG).show()
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isConnected
                ) {
                    Text("Сгенерировать ключ", maxLines = 1)
                }
                OutlinedButton(
                    onClick = { keyFileLauncher.launch("*/*") },
                    modifier = Modifier.weight(1f),
                    enabled = !isConnected
                ) {
                    Text("Импортировать файл", maxLines = 1)
                }
            }

            OutlinedTextField(
                value = privateKeyPem,
                onValueChange = { privateKeyPem = it },
                label = { Text("Приватный ключ") },
                placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                enabled = !isConnected
            )

            OutlinedTextField(
                value = keyPassphrase,
                onValueChange = { keyPassphrase = it },
                label = { Text("Пароль ключа (если есть)") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isConnected
            )

            if (sshConfig.publicKeyOpenSsh.isNotBlank()) {
                Text("Публичный ключ (добавьте в ~/.ssh/authorized_keys на сервере):", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                Card(colors = CardDefaults.cardColors(containerColor = DarkSurface), modifier = Modifier.fillMaxWidth()) {
                    SelectionContainer {
                        Text(
                            sshConfig.publicKeyOpenSsh,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
                TextButton(onClick = {
                    clipboardManager.setPrimaryClip(ClipData.newPlainText("pubkey", sshConfig.publicKeyOpenSsh))
                    Toast.makeText(context, "Публичный ключ скопирован", Toast.LENGTH_SHORT).show()
                }) {
                    Text("Копировать публичный ключ")
                }
            }
        }

        if (sshState is SshConnectionState.Error) {
            val errMsg = (sshState as SshConnectionState.Error).message
            Text(
                text = errMsg,
                color = StatusRed,
                style = MaterialTheme.typography.bodyMedium
            )
            if (errMsg.contains("хост-ключ", ignoreCase = true)) {
                TextButton(onClick = { viewModel.forgetSshHostKey() }) {
                    Text("Сбросить сохранённый ключ", color = StatusRed)
                }
            }
        }

        Button(
            onClick = {
                if (isConnected) {
                    viewModel.disconnectSsh()
                } else {
                    // Пароль/ключ сохраняем именно здесь — в момент реальной попытки
                    // подключения, а не на каждое нажатие клавиши.
                    viewModel.saveSshConfig(
                        sshConfig.copy(
                            ip = ip,
                            port = port.toIntOrNull() ?: 22,
                            username = user,
                            password = pass,
                            proxyListen = proxyListen,
                            proxyConnect = proxyConnect,
                            kernelSourceUrl = kernelSourceUrl,
                            serverBinName = serverBinName,
                            serverExtraFlags = serverExtraFlags,
                            authMethod = authMethod,
                            privateKeyPem = privateKeyPem,
                            keyPassphrase = keyPassphrase
                        )
                    )
                    viewModel.connectSsh()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isConnecting,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isConnected) StatusRed else MaterialTheme.colorScheme.primary
            )
        ) {
            Text(
                if (isConnecting) "Проверка..."
                else if (isConnected) "Отключиться"
                else "Подключиться"
            )
        }

        if (isConnected) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Управление сервером", style = MaterialTheme.typography.titleMedium)

                val state = serverState // Capture delegated property for stable smart cast
                if (state is ServerState.Checking) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else if (state is ServerState.Known) {
                    val statusText = when {
                        state.installed && state.running && state.isService -> "Запущен (systemd)"
                        state.installed && state.running -> "Запущен"
                        state.installed -> "Остановлен"
                        else -> "Не установлен"
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = statusText,
                            color = if (state.running) StatusGreen else if (state.installed) StatusYellow else StatusRed,
                            style = MaterialTheme.typography.labelSmall
                        )
                        if (state.binName.isNotEmpty()) {
                            Text(
                                "${state.binName} · порт ${state.portStatus}",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }

            val knownState = serverState as? ServerState.Known
            if (knownState != null && knownState.binPath.isNotEmpty()) {
                Text(
                    "Путь: ${knownState.binPath}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }

            OutlinedTextField(
                value = proxyListen,
                onValueChange = { proxyListen = it },
                label = { Text("Слушать (Listen)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = proxyConnect,
                onValueChange = { proxyConnect = it },
                label = { Text("Подключаться к (Connect)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Text("Серверное ядро (кастомное)", style = MaterialTheme.typography.titleSmall)

            OutlinedTextField(
                value = kernelSourceUrl,
                onValueChange = { kernelSourceUrl = it },
                label = { Text("Ссылка на ядро (URL)") },
                placeholder = { Text("https://.../server-{arch}, поддерживает {arch}") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = serverBinName,
                    onValueChange = { serverBinName = it },
                    label = { Text("Имя бинарника") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = serverExtraFlags,
                    onValueChange = { serverExtraFlags = it },
                    label = { Text("Доп. флаги") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { viewModel.installServer() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (isInstalled) "Обновить по ссылке" else "Установить по ссылке", maxLines = 1)
                }
                OutlinedButton(
                    onClick = { uploadLauncher.launch("*/*") },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Загрузить файл", maxLines = 1)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { viewModel.startServer() },
                    modifier = Modifier.weight(1f),
                    enabled = isInstalled && !isRunning,
                    colors = ButtonDefaults.buttonColors(containerColor = StatusGreenDark)
                ) {
                    Text("Запуск", maxLines = 1)
                }
                Button(
                    onClick = { viewModel.stopServer() },
                    modifier = Modifier.weight(1f),
                    enabled = isInstalled && isRunning,
                    colors = ButtonDefaults.buttonColors(containerColor = StatusRed)
                ) {
                    Text("Стоп", maxLines = 1)
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Отправить команду", style = MaterialTheme.typography.titleMedium)
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = customCommand,
                    onValueChange = { customCommand = it },
                    placeholder = { Text("cmd...") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Button(onClick = {
                    viewModel.sendSshCommand(customCommand)
                    customCommand = ""
                }) {
                    Text("Отправить")
                }
            }
        }

        if (sshLog.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Логи терминала", style = MaterialTheme.typography.titleMedium)
                Row {
                    TextButton(onClick = {
                        clipboardManager.setPrimaryClip(ClipData.newPlainText("logs", sshLog.joinToString("\n")))
                    }) {
                        Text("Copy", color = MaterialTheme.colorScheme.primary)
                    }
                    TextButton(onClick = { viewModel.clearSshLogs() }) {
                        Text("Clear", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = DarkSurface), modifier = Modifier.fillMaxWidth().height(200.dp)) {
                val scrollState = rememberScrollState()
                LaunchedEffect(sshLog.size) {
                    if (sshLog.isNotEmpty()) {
                        scrollState.animateScrollTo(scrollState.maxValue)
                    }
                }
                SelectionContainer(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.padding(8.dp).verticalScroll(scrollState)) {
                        sshLog.forEach { log ->
                            Text(log, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        }
                    }
                }
            }
        }
    }
}
