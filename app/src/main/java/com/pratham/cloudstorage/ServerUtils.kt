package com.pratham.cloudstorage

import android.net.Uri
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLEncoder
import java.util.Collections
import java.util.Locale
import java.util.UUID

const val DEFAULT_PORT = 8080

fun buildLocalAccessUrl(port: Int = DEFAULT_PORT): String? {
    val ipAddress = getLocalIpv4Address() ?: return null
    return "http://$ipAddress:$port"
}

fun buildLocalAccessUrls(port: Int = DEFAULT_PORT): List<String> {
    val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
    return Collections.list(interfaces)
        .flatMap { Collections.list(it.inetAddresses) }
        .filterIsInstance<Inet4Address>()
        .filter { address ->
            !address.isLoopbackAddress &&
                !address.isLinkLocalAddress &&
                !address.isAnyLocalAddress &&
                address.isSiteLocalAddress
        }
        .map { address -> "http://${address.hostAddress}:$port" }
        .distinct()
}

fun generateShareCode(): String {
    return UUID.randomUUID().toString()
        .replace("-", "")
        .take(10)
        .uppercase(Locale.US)
}

fun normalizeRelayBaseUrl(value: String): String {
    var normalized = value.trim()
    
    // Strip recurring protocol segments (fix for https://https:// bug)
    while (normalized.startsWith("https://https://", ignoreCase = true)) {
        normalized = normalized.substring(8)
    }
    while (normalized.startsWith("http://http://", ignoreCase = true)) {
        normalized = normalized.substring(7)
    }

    if (normalized.isEmpty()) return ""
    
    // Ensure it has a protocol
    if (!normalized.startsWith("http://", ignoreCase = true) && 
        !normalized.startsWith("https://", ignoreCase = true)) {
        normalized = "https://$normalized"
    }
    
    return normalized.trimEnd('/')
}

fun sanitizeUrl(url: String): String = normalizeRelayBaseUrl(url)

fun isLocalDevelopmentRelayBaseUrl(value: String): Boolean {
    val normalized = normalizeRelayBaseUrl(value)
    if (normalized.isBlank()) {
        return false
    }

    val host = runCatching { Uri.parse(normalized).host.orEmpty() }.getOrDefault("")
    return host.equals("127.0.0.1", ignoreCase = true) ||
        host.equals("localhost", ignoreCase = true) ||
        host.equals("0.0.0.0", ignoreCase = true) ||
        host.equals("::1", ignoreCase = true)
}

fun resolveRelayBaseUrl(
    persistedValue: String,
    configuredValue: String
): String {
    val persisted = normalizeRelayBaseUrl(persistedValue)
    val configured = normalizeRelayBaseUrl(configuredValue)

    if (persisted.isBlank()) {
        return configured
    }

    return if (isLocalDevelopmentRelayBaseUrl(persisted) && configured.isNotBlank()) {
        configured
    } else {
        persisted
    }
}

fun buildRelayBrowserUrl(relayBaseUrl: String, shareCode: String): String? {
    val normalized = normalizeRelayBaseUrl(relayBaseUrl)
    if (normalized.isBlank()) {
        return null
    }
    return "$normalized/node/$shareCode/console"
}

fun buildInviteLink(shareCode: String): String {
    val configuredHost = BuildConfig.APP_LINK_HOST.trim()
    return if (configuredHost.isNotBlank()) {
        Uri.Builder()
            .scheme("https")
            .authority(configuredHost)
            .appendPath("join")
            .appendQueryParameter("code", shareCode)
            .build()
            .toString()
    } else {
        Uri.Builder()
            .scheme("easystoragecloud")
            .authority("join")
            .appendQueryParameter("code", shareCode)
            .build()
            .toString()
    }
}

fun buildSharePayload(
    shareCode: String,
    inviteLink: String,
    publicUrl: String?
): String {
    return buildString {
        appendLine("Easy Storage Cloud node invite")
        appendLine()
        appendLine("Share code: $shareCode")
        appendLine("Invite link: $inviteLink")
        if (!publicUrl.isNullOrBlank()) {
            appendLine("Public console: $publicUrl")
        }
        appendLine()
        append("Open the invite link in Easy Storage Cloud or paste the share code manually.")
    }
}

fun encodeUrlSegment(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

private fun getLocalIpv4Address(): String? {
    val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
    return Collections.list(interfaces)
        .flatMap { Collections.list(it.inetAddresses) }
        .firstOrNull { address ->
            address is Inet4Address && !address.isLoopbackAddress && address.isSiteLocalAddress
        }
        ?.hostAddress
}
