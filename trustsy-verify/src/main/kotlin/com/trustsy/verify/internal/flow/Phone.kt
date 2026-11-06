package com.trustsy.verify.internal.flow

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** One row of the dial-code registry (web `dialCodes.json`): ISO code, `+dial`, English name. */
internal data class Country(val code: String, val dial: String, val english: String)

/** The web app's `phoneNumber.ts` / `phoneMessages.ts` rules. Pure. */
internal object Phone {
    const val DEFAULT_COUNTRY = "TH"
    private const val E164_MAX_DIGITS = 15

    fun parseRegistry(json: String): List<Country> =
        Json.parseToJsonElement(json).jsonArray.map { row ->
            val r = row.jsonArray
            Country(r[0].jsonPrimitive.content, r[1].jsonPrimitive.content, r[2].jsonPrimitive.content)
        }

    /**
     * The E.164 number for a national number typed under [dial], or "" when
     * there is nothing to send. One leading trunk `0` is dropped (`081…` under
     * +66 → `+6681…`), except under +39, where Italian landlines keep it.
     */
    fun toE164(dial: String, national: String): String {
        var digits = national.filter(Char::isDigit)
        if (digits.startsWith("0") && dial != "+39") digits = digits.drop(1)
        if (digits.isEmpty()) return ""
        return "+" + (dial.removePrefix("+") + digits).take(E164_MAX_DIGITS)
    }

    /** Politeness, not a gate: a send that cannot succeed never spends an SMS. */
    fun looksLikeE164(raw: String): Boolean = Regex("^\\+[1-9]\\d{7,14}$").matches(raw.replace(Regex("[\\s\\-()]"), ""))

    /** Verdicts after which the live code is GONE: go back and ask for a new one. */
    val TERMINAL_VERDICTS = setOf("expired", "locked", "superseded", "not_found")
}
