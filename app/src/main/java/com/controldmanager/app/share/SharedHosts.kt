package com.controldmanager.app.share

import java.net.IDN

/**
 * Finds the domains in text another app shared (a browser's link, often after the page title).
 * Hosts come out lowercase ASCII (punycode for international names), without credentials, port or
 * trailing dot; IP addresses are skipped, as custom rules are for hostnames.
 */
object SharedHosts {
    private const val MAX_HOSTS = 10
    private const val MAX_TEXT = 10_000
    private val url = Regex("""(?i)\b(?:https?|ftp)://([^\s/?#<>"'\\]+)""")
    /** A bare "example.com" in text that has no link at all. */
    private val bare = Regex("""(?<![\p{L}\p{N}_.@/-])((?:[\p{L}\p{N}](?:[\p{L}\p{N}-]*[\p{L}\p{N}])?\.)+\p{L}{2,63})(?![\p{L}\p{N}_-])""")
    private val label = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")

    fun from(text: String?): List<String> {
        val t = text.orEmpty().take(MAX_TEXT)
        val linked = url.findAll(t).mapNotNull { normalize(it.groupValues[1]) }.toList()
        val found = linked.ifEmpty { bare.findAll(t).mapNotNull { normalize(it.groupValues[1]) }.toList() }
        return found.distinct().take(MAX_HOSTS)
    }

    /** The host of a URL authority ("user@Example.com:8080" → "example.com"), or null if it isn't one. */
    internal fun normalize(authority: String): String? {
        val hostPort = authority.substringAfterLast('@')
        if (hostPort.startsWith("[")) return null // IPv6 literal
        val host = hostPort.substringBefore(':').trimEnd('.').lowercase()
        if (host.isEmpty()) return null
        val ascii = runCatching { IDN.toASCII(host, IDN.ALLOW_UNASSIGNED) }.getOrNull()?.lowercase() ?: return null
        if (ascii.length > 253) return null
        val labels = ascii.split('.')
        if (labels.size < 2 || labels.any { !label.matches(it) }) return null
        if (labels.last().all { it.isDigit() }) return null // IPv4 address
        return ascii
    }
}
