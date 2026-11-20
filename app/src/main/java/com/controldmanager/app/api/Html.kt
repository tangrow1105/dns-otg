package com.controldmanager.app.api

/**
 * Control D returns names and comments HTML-escaped ("Kids &amp; Teens"); this turns them back into
 * text. One pass, so an escaped entity stays as written ("&amp;lt;" → "&lt;"); unknown entities are kept.
 */
fun String.unescapeHtml(): String {
    if ('&' !in this) return this
    return entity.replace(this) { m ->
        val body = m.groupValues[1]
        when {
            body.startsWith("#x") || body.startsWith("#X") -> codePoint(body.substring(2).toIntOrNull(16))
            body.startsWith("#") -> codePoint(body.substring(1).toIntOrNull())
            else -> named[body]
        } ?: m.value
    }
}

private val entity = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,8});")

private val named = mapOf(
    "amp" to "&", "quot" to "\"", "apos" to "'", "lt" to "<", "gt" to ">",
    "nbsp" to " ", "ndash" to "–", "mdash" to "—",
)

private fun codePoint(cp: Int?): String? =
    cp?.takeIf { it != 0 && Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) }
