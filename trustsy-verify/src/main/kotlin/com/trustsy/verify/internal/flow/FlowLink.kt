package com.trustsy.verify.internal.flow

import java.net.URI

/**
 * The applicant link, `/u/<code>`: the token UUID's 16 bytes as unpadded
 * base64url (web `flowLink.ts`). The SAME credential re-encoded, not a second
 * one. `/verify?token=<uuid>` links keep working.
 */
internal object FlowLink {
    private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)
    private val CODE = Regex("^[A-Za-z0-9_-]{22}$")
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    data class Parsed(val origin: String, val token: String)

    fun isToken(value: String): Boolean = UUID.matches(value)

    /** `ceb76355-da07-…` → `zrdjVdoHRw6QHUDcjtILhQ`; null for anything not a uuid. */
    fun encode(token: String): String? {
        if (!UUID.matches(token)) return null
        val hex = token.replace("-", "")
        val bytes = ByteArray(16) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        val out = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xff
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xff else -1
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xff else -1
            out.append(ALPHABET[b0 shr 2])
            out.append(ALPHABET[((b0 and 0x03) shl 4) or (if (b1 < 0) 0 else b1 shr 4)])
            if (b1 >= 0) out.append(ALPHABET[((b1 and 0x0f) shl 2) or (if (b2 < 0) 0 else b2 shr 6)])
            if (b2 >= 0) out.append(ALPHABET[b2 and 0x3f])
            i += 3
        }
        return out.toString()
    }

    /**
     * The uuid inside a code, or null. 22 characters carry 132 bits for 128, so
     * sixteen codes spell each token; only the one whose trailing bits are zero
     * (the one the backend emits) round-trips, so a link has one spelling.
     */
    fun decode(code: String): String? {
        if (!CODE.matches(code)) return null
        var bits = 0L
        var count = 0
        val bytes = ArrayList<Byte>(16)
        for (c in code) {
            bits = (bits shl 6) or ALPHABET.indexOf(c).toLong()
            count += 6
            if (count >= 8) {
                count -= 8
                bytes.add(((bits shr count) and 0xff).toByte())
                bits = bits and ((1L shl count) - 1)
            }
        }
        if (bytes.size != 16) return null
        val hex = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val token = "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20)}"
        return if (encode(token) == code) token else null
    }

    /** A `/u/<code>` or `/verify?token=` link → origin + token; null otherwise. */
    fun parse(link: String): Parsed? {
        val uri = runCatching { URI(link.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "https" && scheme != "http") return null
        val host = uri.host ?: return null
        val origin = buildString {
            append(scheme).append("://").append(host)
            if (uri.port != -1) append(':').append(uri.port)
        }
        val path = uri.rawPath ?: return null
        val token = when {
            path.startsWith("/u/") -> decode(path.removePrefix("/u/"))
            path == "/verify" || path == "/verify/" -> queryParam(uri.rawQuery, "token")?.takeIf(::isToken)
            else -> null
        } ?: return null
        return Parsed(origin, token.lowercase())
    }

    private fun queryParam(query: String?, name: String): String? =
        query?.split('&')?.firstNotNullOfOrNull { pair ->
            val eq = pair.indexOf('=')
            if (eq > 0 && pair.substring(0, eq) == name) java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
            else null
        }
}
