package com.vkturn.proxy.models

import java.util.UUID
import com.google.gson.annotations.SerializedName

data class ProxyProfile(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    val config: ClientConfig,
    val isDefault: Boolean = false
)

const val FLAG_ID_UDP = "flag_udp"
const val FLAG_ID_VLESS = "flag_vless"
const val FLAG_ID_CAPTCHA = "flag_captcha"
const val FLAG_ID_DTLS = "flag_dtls"
const val FLAG_ID_DEBUG = "flag_debug"

data class ProxyFlag(
    @SerializedName("id") val id: String = UUID.randomUUID().toString(),
    @SerializedName("label") var label: String = "",
    @SerializedName("argument") var argument: String = "",
    @SerializedName("enabled") var enabled: Boolean = false,
    @SerializedName("deletable") val deletable: Boolean = true
)

data class ClientConfig(
    @SerializedName("serverAddress") val serverAddress: String = "",
    @SerializedName("vkLink") val vkLink: String = "",
    @SerializedName("linkArgument") val linkArgument: String = "",
    @SerializedName("threads") val threads: Int = 8,
    @SerializedName("localPort") val localPort: String = "127.0.0.1:9000",
    @SerializedName("isRawMode") val isRawMode: Boolean = false,
    @SerializedName("rawCommand") val rawCommand: String = "",
    @SerializedName("customFlags") val customFlags: List<ProxyFlag> = emptyList()
) {
    fun generateRawCommand(): String {
        val cmds = mutableListOf<String>()
        if (serverAddress.isNotBlank()) {
            cmds.add("-peer")
            cmds.add(serverAddress)
        }
        if (vkLink.isNotBlank()) {
            cmds.add(linkArgument)
            cmds.add(vkLink)
        }
        if (localPort.isNotBlank()) {
            cmds.add("-listen")
            cmds.add(localPort)
        }
        if (threads > 0) {
            cmds.add("-n")
            cmds.add(threads.toString())
        }
        
        customFlags.filter { it.enabled && it.argument.isNotBlank() }.forEach { flag ->
            flag.argument.trim().split("\\s+".toRegex()).forEach {
                cmds.add(it)
            }
        }
        
        return cmds.joinToString(" ")
    }

    fun parseFromRawCommand(rawStr: String): ClientConfig {
        if (rawStr.isBlank()) return this
        
        val parts = rawStr.trim().split("\\s+".toRegex())
        var newServerAddress = ""
        var newVkLink = ""
        var newLinkArgument = linkArgument // Start with current one
        var newThreads = 0 // Default to 0 (infinity) if not found in raw
        var newLocalPort = localPort
        
        // Map of base flag name -> full argument string (e.g., "-udp" -> "-udp" or "-test" -> "-test val")
        val encounteredFlags = mutableMapOf<String, String>()
        
        val knownLinkFlags = listOf("-vk-link", "-yandex-link", "-mail-link", "-link")
        
        var i = 0
        while (i < parts.size) {
            val part = parts[i]
            when {
                part == "-peer" && i + 1 < parts.size -> {
                    newServerAddress = parts[++i]
                }
                part == "-listen" && i + 1 < parts.size -> {
                    newLocalPort = parts[++i]
                }
                part == "-n" && i + 1 < parts.size -> {
                    newThreads = parts[++i].toIntOrNull() ?: threads
                }
                part.startsWith("-") -> {
                    val fullArg: String
                    val nextVal = if (i + 1 < parts.size && !parts[i + 1].startsWith("-")) parts[i + 1] else null
                    
                    if (nextVal != null) {
                        fullArg = "$part $nextVal"
                        // Check if this is a link flag (either known or followed by a URL)
                        if (part == newLinkArgument || knownLinkFlags.contains(part) || nextVal.startsWith("http")) {
                            newVkLink = nextVal
                            newLinkArgument = part
                            i++ // skip the value part
                        } else {
                            encounteredFlags[part] = fullArg
                            i++ // skip the value part
                        }
                    } else {
                        fullArg = part
                        encounteredFlags[part] = fullArg
                    }
                }
            }
            i++
        }
        
        // Smart merge with existing flags
        val updatedCustomFlags = customFlags.mapNotNull { existing ->
            // Extract base flag name from existing.argument (e.g. "-udp" from "-udp")
            val baseName = existing.argument.split("\\s+".toRegex())[0]
            
            // Skip if this flag is now the link argument or a standard field
            if (baseName == newLinkArgument || baseName == "-peer" || baseName == "-listen" || baseName == "-n") {
                return@mapNotNull null
            }
            
            val matchedNewArg = encounteredFlags[baseName]
            
            if (matchedNewArg != null) {
                // Flag present in raw string -> enable it and update value
                encounteredFlags.remove(baseName)
                existing.copy(enabled = true, argument = matchedNewArg)
            } else {
                // Flag missing in raw string
                if (existing.deletable) {
                    // It's a user flag, remove it completely if missing from raw
                    null
                } else {
                    // It's a system flag, just disable it
                    existing.copy(enabled = false)
                }
            }
        }.toMutableList()
        
        // Add truly NEW flags that weren't in the list before
        encounteredFlags.forEach { (baseName, fullArg) ->
            if (baseName != newLinkArgument) {
                updatedCustomFlags.add(ProxyFlag(
                    label = baseName,
                    argument = fullArg,
                    enabled = true
                ))
            }
        }
        
        return this.copy(
            serverAddress = newServerAddress.ifBlank { serverAddress },
            vkLink = newVkLink.ifBlank { vkLink },
            linkArgument = newLinkArgument,
            threads = newThreads,
            localPort = newLocalPort.ifBlank { localPort },
            customFlags = updatedCustomFlags
        )
    }
}

data class SshConfig(
    val ip: String = "",
    val port: Int = 22,
    val username: String = "root",
    val password: String = "",
    val proxyListen: String = "0.0.0.0:56000",
    val proxyConnect: String = "127.0.0.1:40537"
)
