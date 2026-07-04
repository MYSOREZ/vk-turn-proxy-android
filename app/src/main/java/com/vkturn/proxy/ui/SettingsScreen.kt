package com.vkturn.proxy.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vkturn.proxy.viewmodel.MainViewModel
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.scale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import com.vkturn.proxy.models.*
import androidx.compose.ui.graphics.Color
import com.vkturn.proxy.ui.theme.StatusRed
import com.vkturn.proxy.ui.theme.StatusGreen
import com.vkturn.proxy.ui.theme.DarkSurface
import com.vkturn.proxy.ui.theme.TextSecondary
import com.vkturn.proxy.ui.theme.PrimaryAccent
import com.vkturn.proxy.ui.components.CurrentProfileHeader
import com.vkturn.proxy.ui.components.ExpandableSection
import com.vkturn.proxy.data.AppPreferences
import com.vkturn.proxy.ui.components.QrScannerScreen
import com.vkturn.proxy.ui.components.QrExportDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var showScanner by remember { mutableStateOf(false) }
    
    if (showScanner) {
        QrScannerScreen(
            onScanResult = { result ->
                viewModel.handleProfileImport(result)
                showScanner = false
            },
            onCancel = { showScanner = false },
            onClipboardImportClick = {
                viewModel.importProfileFromClipboard()
                showScanner = false
            }
        )
        return
    }

    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val selectedProfileId by viewModel.selectedProfileId.collectAsStateWithLifecycle()
    val clientConfig by viewModel.clientConfig.collectAsStateWithLifecycle()
    
    var peer by remember(clientConfig) { mutableStateOf(clientConfig.serverAddress) }
    var link by remember(clientConfig) { mutableStateOf(clientConfig.vkLink) }
    var linkArg by remember(clientConfig) { mutableStateOf(clientConfig.linkArgument) }
    var threads by remember(clientConfig) { mutableStateOf(clientConfig.threads.toFloat()) }
    var listen by remember(clientConfig) { mutableStateOf(clientConfig.localPort) }
    

    var isRawMode by remember(clientConfig) { mutableStateOf(clientConfig.isRawMode) }
    var rawCommand by remember(clientConfig) { mutableStateOf(clientConfig.rawCommand) }

    var isProfilesExpanded by remember { mutableStateOf(false) }
    var isFlagsExpanded by remember { mutableStateOf(false) }
    var isConnectionExpanded by remember { mutableStateOf(false) }
    var isSystemExpanded by remember { mutableStateOf(false) }
    var isKernelExpanded by remember { mutableStateOf(false) }

    var currentFlags by remember(clientConfig) { mutableStateOf(clientConfig.customFlags) }



    // Track unsaved changes
    val hasUnsavedChanges = remember(peer, link, linkArg, threads, listen, isRawMode, rawCommand, currentFlags, clientConfig) {
        val standardFieldsChanged = peer != clientConfig.serverAddress ||
                link != clientConfig.vkLink ||
                linkArg != clientConfig.linkArgument ||
                threads.toInt() != clientConfig.threads ||
                listen != clientConfig.localPort ||
                currentFlags != clientConfig.customFlags
        
        if (isRawMode) {
            if (clientConfig.isRawMode) {
                // If stored config was already Raw, we just compare the strings
                rawCommand != clientConfig.rawCommand
            } else {
                // If stored config was Standard, compare with what the command *would* be
                rawCommand != clientConfig.generateRawCommand()
            }
        } else {
            // In standard mode, we only care if the structural fields were modified
            standardFieldsChanged
        }
    }

    var showSavedFeedback by remember { mutableStateOf(false) }
    var showExportQrForData by remember { mutableStateOf<Pair<String, String>?>(null) }
    
    showExportQrForData?.let { (profileName, data) ->
        QrExportDialog(
            profileName = profileName,
            profileData = data,
            onDismiss = { showExportQrForData = null }
        )
    }
    
    // Hide "Saved" message if user starts editing again
    LaunchedEffect(hasUnsavedChanges) {
        if (hasUnsavedChanges) showSavedFeedback = false
    }

    // Auto-hide "Saved" message after 3 seconds
    LaunchedEffect(showSavedFeedback) {
        if (showSavedFeedback) {
            kotlinx.coroutines.delay(3000)
            showSavedFeedback = false
        }
    }

    var showNamingDialog by remember { mutableStateOf<String?>(null) } // null = hide, "" = new, "id" = rename
    var namingValue by remember { mutableStateOf("") }

    var showFlagDialog by remember { mutableStateOf<ProxyFlag?>(null) } // null = hide, id == "" -> new, else edit
    var flagLabel by remember { mutableStateOf("") }
    var flagArg by remember { mutableStateOf("") }

    if (showFlagDialog != null) {
        AlertDialog(
            onDismissRequest = { showFlagDialog = null },
            title = { Text(if (showFlagDialog!!.id.isEmpty()) "Новый флаг" else "Редактировать флаг") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = flagLabel,
                        onValueChange = { flagLabel = it },
                        label = { Text("Название (в UI)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = flagArg,
                        onValueChange = { flagArg = it },
                        label = { Text("Аргумент (например -udp)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (flagLabel.isNotBlank() && flagArg.isNotBlank()) {
                        if (showFlagDialog!!.id.isEmpty()) {
                            val newFlag = ProxyFlag(label = flagLabel, argument = flagArg, enabled = true)
                            currentFlags = currentFlags + newFlag
                        } else {
                            currentFlags = currentFlags.map { 
                                if (it.id == showFlagDialog!!.id) it.copy(label = flagLabel, argument = flagArg) else it 
                            }
                        }
                        showFlagDialog = null
                    }
                }) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showFlagDialog = null }) { Text("Отмена") }
            }
        )
    }

    if (showNamingDialog != null) {
        AlertDialog(
            onDismissRequest = { showNamingDialog = null },
            title = { Text(if (showNamingDialog == "") "Новый профиль" else "Переименовать") },
            text = {
                OutlinedTextField(
                    value = namingValue,
                    onValueChange = { namingValue = it },
                    label = { Text("Название") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (namingValue.isNotBlank()) {
                        if (showNamingDialog == "") {
                            viewModel.addProfile(namingValue)
                        } else {
                            viewModel.renameProfile(showNamingDialog!!, namingValue)
                        }
                        showNamingDialog = null
                    }
                }) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNamingDialog = null }) { Text("Отмена") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Client Settings Header
        Text("Настройки Клиента", style = MaterialTheme.typography.titleLarge)

        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                "Режим своей (Raw) команды", 
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Switch(
                checked = isRawMode, 
                onCheckedChange = { checked ->
                    if (checked) {
                        // Switching TO Raw: Generate command from fields
                        val tempConfig = clientConfig.copy(
                            serverAddress = peer,
                            vkLink = link,
                            linkArgument = linkArg,
                            threads = threads.toInt(),
                            localPort = listen,
                            customFlags = currentFlags
                        )
                        rawCommand = tempConfig.generateRawCommand()
                    } else {
                        // Switching FROM Raw: Parse command back to fields
                        val updatedConfig = clientConfig.copy(linkArgument = linkArg).parseFromRawCommand(rawCommand)
                        peer = updatedConfig.serverAddress
                        link = updatedConfig.vkLink
                        threads = updatedConfig.threads.toFloat()
                        listen = updatedConfig.localPort
                        currentFlags = updatedConfig.customFlags
                    }
                    isRawMode = checked
                }
            )
        }

        if (isRawMode) {
            // Raw Mode UI
            OutlinedTextField(
                value = rawCommand,
                onValueChange = { rawCommand = it },
                label = { Text("Команда (raw format)") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                placeholder = { Text("-peer 1.2.3.4:5678 -vk-link ... -listen 127.0.0.1:9000 -n 8") },
                trailingIcon = if (rawCommand.isNotEmpty()) {
                    {
                        IconButton(onClick = { rawCommand = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Очистить")
                        }
                    }
                } else null
            )
        } else {
            // Standard Mode UI
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = link,
                    onValueChange = { 
                        link = it 
                        if (linkArg.isEmpty() && it.isNotEmpty()) {
                            // Auto-fill if empty
                            linkArg = if (it.contains("yandex")) "-yandex-link" else "-vk-link"
                        } else if (linkArg == "-vk-link" && it.contains("yandex")) {
                            linkArg = "-yandex-link"
                        } else if (linkArg == "-yandex-link" && (it.contains("vk") || it.contains("mail"))) {
                            linkArg = "-vk-link"
                        }
                    },
                    label = { Text("Ссылка (для Bypass)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    trailingIcon = if (link.isNotEmpty()) {
                        {
                            IconButton(onClick = { link = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Очистить")
                            }
                        }
                    } else null
                )
                
                OutlinedTextField(
                    value = linkArg,
                    onValueChange = { linkArg = it },
                    label = { Text("Аргумент") },
                    modifier = Modifier.width(120.dp),
                    singleLine = true,
                    trailingIcon = if (linkArg.isNotEmpty()) {
                        {
                            IconButton(onClick = { linkArg = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Очистить")
                            }
                        }
                    } else null
                )
            }

            Column {
                Text("Количество потоков (Threads): ${if (threads == 0f) "∞" else threads.toInt()}")
                Slider(
                    value = threads,
                    onValueChange = { threads = it },
                    valueRange = 0f..8f,
                    steps = 7
                )
            }

            // 2. Flags
            ExpandableSection(
                title = "Флаги и аргументы",
                isExpanded = isFlagsExpanded,
                onExpandChange = { isFlagsExpanded = it }
            ) {
                var showDtlsWarning by remember { mutableStateOf(false) }
                
                if (showDtlsWarning) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { showDtlsWarning = false },
                        title = { Text("Внимание!") },
                        text = { Text("Отключение DTLS с большой вероятностью приведет к блокировке со стороны сервиса. Вы уверены?") },
                        confirmButton = {
                            TextButton(onClick = {
                                val newFlags = currentFlags.map {
                                    if (it.argument == "-no-dtls") it.copy(enabled = true) else it
                                }
                                currentFlags = newFlags
                                viewModel.saveClientConfig(clientConfig.copy(
                                    serverAddress = peer,
                                    vkLink = link,
                                    linkArgument = linkArg,
                                    threads = threads.toInt(),
                                    localPort = listen,
                                    isRawMode = false,
                                    rawCommand = rawCommand,
                                    customFlags = newFlags
                                ))
                                showDtlsWarning = false
                            }) {
                                Text("Продолжить", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDtlsWarning = false }) {
                                Text("Отмена")
                            }
                        }
                    )
                }

                currentFlags.forEach { flag ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surface,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Switch(
                                checked = flag.enabled,
                                onCheckedChange = { isChecked ->
                                    if (flag.argument == "-no-dtls" && isChecked) {
                                        showDtlsWarning = true
                                    } else {
                                        var newFlags = currentFlags.map { 
                                            if (it.id == flag.id) it.copy(enabled = isChecked) else it 
                                        }
                                        
                                        // Взаимоисключение для UDP и VLESS
                                        if (flag.argument == "-vless" && isChecked) {
                                            newFlags = newFlags.map { if (it.argument == "-udp") it.copy(enabled = false) else it }
                                        } else if (flag.argument == "-udp" && isChecked) {
                                            newFlags = newFlags.map { if (it.argument == "-vless") it.copy(enabled = false) else it }
                                        }
                                        
                                        currentFlags = newFlags
                                        viewModel.saveClientConfig(clientConfig.copy(
                                            serverAddress = peer,
                                            vkLink = link,
                                            linkArgument = linkArg,
                                            threads = threads.toInt(),
                                            localPort = listen,
                                            isRawMode = false,
                                            rawCommand = rawCommand,
                                            customFlags = newFlags
                                        ))
                                    }
                                },
                                modifier = Modifier.scale(0.8f)
                            )
                            Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                Text(flag.label, style = MaterialTheme.typography.bodyLarge)
                                Text(flag.argument, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            
                            IconButton(onClick = { 
                                val index = currentFlags.indexOfFirst { it.id == flag.id }
                                if (index > 0) {
                                    val list = currentFlags.toMutableList()
                                    val item = list.removeAt(index)
                                    list.add(index - 1, item)
                                    currentFlags = list
                                }
                            }) {
                                Icon(Icons.Default.KeyboardArrowUp, null, modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = { 
                                val index = currentFlags.indexOfFirst { it.id == flag.id }
                                if (index < currentFlags.size - 1 && index != -1) {
                                    val list = currentFlags.toMutableList()
                                    val item = list.removeAt(index)
                                    list.add(index + 1, item)
                                    currentFlags = list
                                }
                            }) {
                                Icon(Icons.Default.KeyboardArrowDown, null, modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = { 
                                flagLabel = flag.label
                                flagArg = flag.argument
                                showFlagDialog = flag 
                            }) {
                                Icon(Icons.Default.Edit, null, modifier = Modifier.size(18.dp))
                            }
                            if (flag.deletable) {
                                IconButton(onClick = { 
                                    currentFlags = currentFlags.filter { it.id != flag.id }
                                }) {
                                    Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
                
                TextButton(onClick = {
                    flagLabel = ""
                    flagArg = ""
                    showFlagDialog = ProxyFlag(id = "", label = "", argument = "", enabled = true) 
                }) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Добавить свой флаг")
                }
            }

            // 3. Connection Parameters
            ExpandableSection(
                title = "Параметры подключения",
                isExpanded = isConnectionExpanded,
                onExpandChange = { isConnectionExpanded = it }
            ) {
                OutlinedTextField(
                    value = peer,
                    onValueChange = { peer = it },
                    label = { Text("Адрес сервера (ip:port)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    trailingIcon = if (peer.isNotEmpty()) {
                        {
                            IconButton(onClick = { peer = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Очистить")
                            }
                        }
                    } else null
                )

                OutlinedTextField(
                    value = listen,
                    onValueChange = { listen = it },
                    label = { Text("Локальный адрес (Listen)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    trailingIcon = if (listen.isNotEmpty()) {
                        {
                            IconButton(onClick = { listen = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Очистить")
                            }
                        }
                    } else null
                )
            }
        }

        if (hasUnsavedChanges) {
            Button(
                onClick = {
                    val finalConfig = if (isRawMode) {
                        // If saving in Raw mode, parse the command first to keep structural fields in sync
                        clientConfig.copy(linkArgument = linkArg)
                            .parseFromRawCommand(rawCommand)
                            .copy(isRawMode = true, rawCommand = rawCommand)
                    } else {
                        clientConfig.copy(
                            serverAddress = peer,
                            vkLink = link,
                            linkArgument = linkArg,
                            threads = threads.toInt(),
                            localPort = listen,
                            isRawMode = false,
                            rawCommand = rawCommand, // keep it for reference
                            customFlags = currentFlags
                        )
                    }
                    
                    viewModel.saveClientConfig(finalConfig)
                    
                    // Update local state in case parsing changed something (only relevant if isRawMode was true)
                    if (isRawMode) {
                        peer = finalConfig.serverAddress
                        link = finalConfig.vkLink
                        threads = finalConfig.threads.toFloat()
                        listen = finalConfig.localPort
                        currentFlags = finalConfig.customFlags
                    }
                    
                    showSavedFeedback = true
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("СОХРАНИТЬ ИЗМЕНЕНИЯ", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            }
        } else if (showSavedFeedback) {
            Text(
                "✓ Сохранено", 
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                color = StatusGreen,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        // 4. Profiles & Backup
        ExpandableSection(
            title = "Профили и Бэкап",
            isExpanded = isProfilesExpanded,
            onExpandChange = { isProfilesExpanded = it },
            titleColor = Color.White
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { 
                        val data = viewModel.getAllProfilesExportData()
                        showExportQrForData = "Все профили (${profiles.size})" to data
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Экспорт всех")
                }
                Button(
                    onClick = { showScanner = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Импорт")
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    profiles.forEach { profile ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.selectProfile(profile.id) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            RadioButton(
                                selected = profile.id == selectedProfileId,
                                onClick = { viewModel.selectProfile(profile.id) }
                            )
                            Text(
                                text = profile.name,
                                modifier = Modifier.weight(1f).padding(start = 8.dp),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            
                            var showMenu by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { showMenu = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "Меню")
                                }

                                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Переименовать") },
                                        onClick = {
                                            namingValue = profile.name
                                            showNamingDialog = profile.id
                                            showMenu = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Поделиться") },
                                        onClick = {
                                            val data = viewModel.getProfileExportData(profile.id)
                                            if (data != null) {
                                                showExportQrForData = profile.name to data
                                            }
                                            showMenu = false
                                        }
                                    )
                                    
                                    if (profile.isDefault) {
                                        DropdownMenuItem(
                                            text = { Text("Сбросить настройки", color = MaterialTheme.colorScheme.error) },
                                            onClick = {
                                                viewModel.resetProfile(profile.id)
                                                showMenu = false
                                            }
                                        )
                                    } else {
                                        DropdownMenuItem(
                                            text = { Text("Удалить", color = MaterialTheme.colorScheme.error) },
                                            onClick = {
                                                viewModel.deleteProfile(profile.id)
                                                showMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    
                    TextButton(
                        onClick = { 
                            namingValue = ""
                            showNamingDialog = "" 
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Создать новый профиль")
                    }
                }
            }
        }

        // 5. System Settings
        ExpandableSection(
            title = "Системные настройки",
            isExpanded = isSystemExpanded,
            onExpandChange = { isSystemExpanded = it },
            titleColor = PrimaryAccent
        ) {
            val isIgnoringBattery = remember(context) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                    pm.isIgnoringBatteryOptimizations(context.packageName)
                } else true
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Оптимизация батареи", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (isIgnoringBattery) "Отключена (Рекомендуется)" else "Включена (Может мешать работе)",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isIgnoringBattery) StatusGreen else StatusRed
                            )
                        }
                        Button(
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    try {
                                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                            data = Uri.parse("package:${context.packageName}")
                                        }
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                        context.startActivity(intent)
                                    }
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text("Инфо")
                        }
                    }

                    if (!isIgnoringBattery) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Для стабильной работы в фоне (особенно при выключенном экране) необходимо разрешить приложению работать без ограничений.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Tethering Stability Advice
            Surface(
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "Проблема с раздачей интернета?",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Если при выключении экрана пропадает интернет на других устройствах, это работа Android Doze Mode. " +
                        "Для Root-пользователей рекомендуется решение:\n" +
                        "dumpsys deviceidle disable",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // 6. Kernel
        ExpandableSection(
            title = "Ядро (Binary)",
            isExpanded = isKernelExpanded,
            onExpandChange = { isKernelExpanded = it },
            titleColor = StatusRed
        ) {
            val isKernelPresent by viewModel.isKernelPresent.collectAsStateWithLifecycle()
            val kernelLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                uri?.let {
                    scope.launch {
                        val result = viewModel.importKernel(it)
                        if (result.isSuccess) {
                            Toast.makeText(context, "Ядро импортировано", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, result.exceptionOrNull()?.message ?: "Ошибка", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(if (isKernelPresent) "Ядро активно" else "Ядро не найдено", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (isKernelPresent) {
                        TextButton(onClick = { viewModel.deleteKernel() }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                            Text("Удалить")
                        }
                    } else {
                        Button(onClick = { kernelLauncher.launch("*/*") }) {
                            Text("Импорт")
                        }
                    }
                }
            }
        }
    }
}
