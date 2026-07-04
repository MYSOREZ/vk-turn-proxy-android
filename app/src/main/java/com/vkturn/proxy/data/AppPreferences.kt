package com.vkturn.proxy.data

import android.content.Context
import android.content.SharedPreferences
import com.vkturn.proxy.models.ProxyProfile
import com.vkturn.proxy.models.ClientConfig
import com.vkturn.proxy.models.ProxyFlag
import com.vkturn.proxy.models.SshConfig
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.vkturn.proxy.models.*

class AppPreferences(private val context: Context) {
    private val gson = Gson()
    private val proxyPrefs: SharedPreferences = context.getSharedPreferences("ProxyPrefs", Context.MODE_PRIVATE)
    private val sshPrefs: SharedPreferences = context.getSharedPreferences("SshPrefs", Context.MODE_PRIVATE)

    private val _selectedProfileIdFlow = MutableStateFlow(proxyPrefs.getString("selectedProfileId", "") ?: "")
    val selectedProfileIdFlow: StateFlow<String> = _selectedProfileIdFlow.asStateFlow()

    private val _clientConfigFlow = MutableStateFlow(loadClientConfig())
    val clientConfigFlow: StateFlow<ClientConfig> = _clientConfigFlow.asStateFlow()

    private val _sshConfigFlow = MutableStateFlow(loadSshConfig())
    val sshConfigFlow: StateFlow<SshConfig> = _sshConfigFlow.asStateFlow()

    private val _profilesFlow = MutableStateFlow(loadProfiles())
    val profilesFlow: StateFlow<List<ProxyProfile>> = _profilesFlow.asStateFlow()

    companion object {
        fun migrateConfig(config: ClientConfig, context: android.content.Context? = null): ClientConfig {
            var currentFlags = (config.customFlags ?: emptyList()).toMutableList()
            var changed = false

            // IMPORTANT: Only treat as "empty" if we have no legacy indicators that this is an existing config.
            // If the user has a peer set, it means it's NOT a fresh install, so we shouldn't force defaults.
            val isFreshInstall = if (context != null) {
                val p = context.getSharedPreferences("ProxyPrefs", android.content.Context.MODE_PRIVATE)
                p.getString("peer", "").isNullOrEmpty() && p.getString("currentConfigJson", null) == null
            } else false

            if (currentFlags.isEmpty() && isFreshInstall) {
                currentFlags = mutableListOf(
                    ProxyFlag(id = FLAG_ID_UDP, label = "UDP", argument = "-udp", enabled = true, deletable = false),
                    ProxyFlag(id = FLAG_ID_VLESS, label = "VLESS", argument = "-vless", enabled = false, deletable = false),
                    ProxyFlag(id = FLAG_ID_CAPTCHA, label = "Manual Captcha", argument = "--manual-captcha", enabled = false, deletable = false),
                    ProxyFlag(id = FLAG_ID_DTLS, label = "Disable DTLS", argument = "-no-dtls", enabled = false, deletable = false),
                    ProxyFlag(id = FLAG_ID_DEBUG, label = "Debug", argument = "-debug", enabled = false, deletable = false)
                )
                changed = true
            } else {
                val mandatory: List<Triple<String, String, String>> = listOf(
                    Triple(FLAG_ID_UDP, "-udp", "UDP"),
                    Triple(FLAG_ID_VLESS, "-vless", "VLESS"),
                    Triple(FLAG_ID_CAPTCHA, "--manual-captcha", "Manual Captcha"),
                    Triple(FLAG_ID_DTLS, "-no-dtls", "Disable DTLS"),
                    Triple(FLAG_ID_DEBUG, "-debug", "Debug")
                )

                // 1. Update labels and ensure mandatory flags exist with stable IDs
                mandatory.forEach { (id: String, arg: String, label: String) ->
                    // Try to find by ID first, then by argument
                    val existing = currentFlags.find { it.id == id } ?: currentFlags.find { it.argument == arg }

                    if (existing == null) {
                        currentFlags.add(ProxyFlag(id = id, label = label, argument = arg, enabled = false, deletable = false))
                        changed = true
                    } else {
                        // Found existing, ensure it has stable ID and fresh label
                        // BUT: don't touch 'enabled'!
                        if (existing.id != id || existing.label != label || (existing.argument != arg && !existing.deletable)) {
                            val idx = currentFlags.indexOf(existing)
                            currentFlags[idx] = existing.copy(id = id, label = label, argument = arg)
                            changed = true
                        }
                    }
                }

                // 2. Reorder: Mandatory flags first in the specified order, then the rest
                val orderedFlags = mutableListOf<ProxyFlag>()
                mandatory.forEach { (id: String, _: String, _: String) ->
                    currentFlags.find { it.id == id }?.let { orderedFlags.add(it) }
                }
                val otherFlags = currentFlags.filter { f -> mandatory.none { it.component1() == f.id } }

                // Compare contents carefully to avoid unnecessary 'changed' triggers
                val newOrder = orderedFlags + otherFlags
                if (currentFlags != newOrder) {
                    currentFlags.clear()
                    currentFlags.addAll(newOrder)
                    changed = true
                }
            }

            // Ensure linkArgument is valid ONLY if link is present
            var linkArg = config.linkArgument
            if (linkArg.isEmpty() && config.vkLink.isNotEmpty()) {
                linkArg = if (config.vkLink.contains("yandex")) "-yandex-link" else "-vk-link"
                changed = true
            }


            // Ensure isRawMode is false if rawCommand is empty (safety for legacy profiles)
            if (config.isRawMode && config.rawCommand.isBlank()) {
                return config.copy(
                    customFlags = currentFlags,
                    linkArgument = linkArg,
                    isRawMode = false
                )
            }

            return if (changed) config.copy(customFlags = currentFlags, linkArgument = linkArg) else config
        }
    }

    private fun loadClientConfig(): ClientConfig {
        val json = proxyPrefs.getString("currentConfigJson", null)
        if (json != null) {
            return try {
                val config = gson.fromJson(json, ClientConfig::class.java)
                val migrated = migrateConfig(config, context)
                migrated
            } catch (e: Exception) {
                createDefaultConfig()
            }
        }
        return createDefaultConfig()
    }

    fun createDefaultConfig(): ClientConfig {
        val flags = listOf(
            ProxyFlag(id = FLAG_ID_UDP, label = "UDP", argument = "-udp", enabled = true, deletable = false),
            ProxyFlag(id = FLAG_ID_VLESS, label = "VLESS", argument = "-vless", enabled = false, deletable = false),
            ProxyFlag(id = FLAG_ID_CAPTCHA, label = "Manual Captcha", argument = "--manual-captcha", enabled = false, deletable = false),
            ProxyFlag(id = FLAG_ID_DTLS, label = "Disable DTLS", argument = "-no-dtls", enabled = false, deletable = false),
            ProxyFlag(id = FLAG_ID_DEBUG, label = "Debug", argument = "-debug", enabled = false, deletable = false)
        )

        return ClientConfig(
            serverAddress = proxyPrefs.getString("peer", "") ?: "",
            vkLink = proxyPrefs.getString("link", "") ?: "",
            linkArgument = proxyPrefs.getString("linkArg", "") ?: "",
            threads = proxyPrefs.getString("n", "8")?.toIntOrNull() ?: 8,
            localPort = proxyPrefs.getString("listen", "127.0.0.1:9000") ?: "127.0.0.1:9000",
            isRawMode = false,
            rawCommand = "",
            customFlags = flags
        )
    }

    private fun loadSshConfig(): SshConfig {
        return SshConfig(
            ip = sshPrefs.getString("ip", "") ?: "",
            port = sshPrefs.getString("port", "22")?.toIntOrNull() ?: 22,
            username = sshPrefs.getString("user", "root") ?: "root",
            password = sshPrefs.getString("pass", "") ?: "",
            proxyListen = sshPrefs.getString("proxyListen", "0.0.0.0:56000") ?: "0.0.0.0:56000",
            proxyConnect = sshPrefs.getString("proxyConnect", "127.0.0.1:40537") ?: "127.0.0.1:40537",
            kernelSourceUrl = sshPrefs.getString("kernelSourceUrl", "") ?: "",
            serverBinName = sshPrefs.getString("serverBinName", "vk-turn-server") ?: "vk-turn-server",
            serverExtraFlags = sshPrefs.getString("serverExtraFlags", "") ?: "",
            authMethod = sshPrefs.getString("authMethod", "password") ?: "password",
            privateKeyPem = sshPrefs.getString("privateKeyPem", "") ?: "",
            publicKeyOpenSsh = sshPrefs.getString("publicKeyOpenSsh", "") ?: "",
            keyPassphrase = sshPrefs.getString("keyPassphrase", "") ?: ""
        )
    }

    fun saveClientConfig(config: ClientConfig) {

        proxyPrefs.edit().apply {
            putString("peer", config.serverAddress)
            putString("link", config.vkLink)
            putString("linkArg", config.linkArgument)
            putString("n", config.threads.toString())
            putString("listen", config.localPort)
            putBoolean("isRaw", config.isRawMode)
            putString("rawCmd", config.rawCommand)

            // Sync legacy boolean keys for extra safety
            val udpFlag = config.customFlags.find { it.id == FLAG_ID_UDP }
            if (udpFlag != null) putBoolean("udp", udpFlag.enabled)

            val dtlsFlag = config.customFlags.find { it.id == FLAG_ID_DTLS }
            if (dtlsFlag != null) putBoolean("noDtls", dtlsFlag.enabled)

            val captchaFlag = config.customFlags.find { it.id == FLAG_ID_CAPTCHA }
            if (captchaFlag != null) putBoolean("useManualCaptcha", captchaFlag.enabled)

            putString("currentConfigJson", gson.toJson(config))
        }.apply()
        _clientConfigFlow.value = config
    }

    fun saveSshConfig(config: SshConfig) {
        sshPrefs.edit().apply {
            putString("ip", config.ip)
            putString("port", config.port.toString())
            putString("user", config.username)
            putString("pass", config.password)
            putString("proxyListen", config.proxyListen)
            putString("proxyConnect", config.proxyConnect)
            putString("kernelSourceUrl", config.kernelSourceUrl)
            putString("serverBinName", config.serverBinName)
            putString("serverExtraFlags", config.serverExtraFlags)
            putString("authMethod", config.authMethod)
            putString("privateKeyPem", config.privateKeyPem)
            putString("publicKeyOpenSsh", config.publicKeyOpenSsh)
            putString("keyPassphrase", config.keyPassphrase)
        }.apply()
        _sshConfigFlow.value = config
    }

    fun loadProfiles(): List<ProxyProfile> {
        val json = proxyPrefs.getString("profilesJson", null)
        if (json == null) {
            val initialProfile = ProxyProfile(name = "По умолчанию", config = loadClientConfig(), isDefault = true)
            val profiles = listOf(initialProfile)
            proxyPrefs.edit().putString("profilesJson", gson.toJson(profiles)).apply()
            if (proxyPrefs.getString("selectedProfileId", "").isNullOrEmpty()) {
                proxyPrefs.edit().putString("selectedProfileId", initialProfile.id).apply()
                _selectedProfileIdFlow.value = initialProfile.id
            }
            return profiles
        }
        val type = object : TypeToken<List<ProxyProfile>>() {}.type
        val rawProfiles: List<ProxyProfile> = gson.fromJson(json, type) ?: emptyList()

        val migratedProfiles = rawProfiles.map { profile ->
            val isDefault = profile.isDefault || profile.name == "По умолчанию"
            profile.copy(config = migrateConfig(profile.config, context), isDefault = isDefault)
        }


        val selectedId = proxyPrefs.getString("selectedProfileId", "") ?: ""

        // Ensure we always have a selected profile if list is not empty
        if (migratedProfiles.isNotEmpty() && selectedId.isEmpty()) {
            val firstId = migratedProfiles.first().id
            proxyPrefs.edit().putString("selectedProfileId", firstId).apply()
            _selectedProfileIdFlow.value = firstId
        }

        if (migratedProfiles != rawProfiles) {
            proxyPrefs.edit().putString("profilesJson", gson.toJson(migratedProfiles)).apply()
        }

        return migratedProfiles
    }

    fun saveProfiles(profiles: List<ProxyProfile>) {
        proxyPrefs.edit().putString("profilesJson", gson.toJson(profiles)).apply()
        _profilesFlow.value = profiles
    }

    fun saveSelectedProfileId(id: String) {
        proxyPrefs.edit().putString("selectedProfileId", id).apply()
        _selectedProfileIdFlow.value = id
    }
}
