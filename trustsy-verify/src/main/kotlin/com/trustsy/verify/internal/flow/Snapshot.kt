package com.trustsy.verify.internal.flow

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** One JSON configuration for every wire shape: the server adds fields freely. */
internal val WireJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/**
 * `GET /api/v1/collection-flow` and every call that advances the flow
 * (`CollectionFlowDto`, web `types.ts::CollectionFlowSnapshot`).
 */
@Serializable
internal data class Snapshot(
    /** The case (runtime) id; the liveness submit path needs it. */
    val id: String = "",
    val state: String? = null,
    /** `active` | `completed` | `failed`. */
    val status: String = "",
    val tags: List<String> = emptyList(),
    val context: JsonElement? = null,
    @SerialName("available_events") val availableEvents: List<String> = emptyList(),
    val done: Boolean = false,
    /** Who is asking. Server-resolved; never read branding from `context`. */
    val branding: Branding = Branding(),
    /** The language the link was minted with (GET only). */
    val language: String? = null,
    val step: JsonElement? = null,
)

@Serializable
internal data class Branding(
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("logo_url") val logoUrl: String? = null,
    @SerialName("website_url") val websiteUrl: String? = null,
    @SerialName("privacy_url") val privacyUrl: String? = null,
    @SerialName("terms_url") val termsUrl: String? = null,
    val style: BrandStyle? = null,
) {
    /** Null when the tenant set no name: render generic copy, never a guess. */
    val partnerName: String? get() = displayName?.trim()?.takeIf { it.isNotEmpty() }
}

@Serializable
internal data class BrandStyle(
    /** Colour role → `#RRGGBB` / `#RRGGBBAA`. */
    val colors: Map<String, String>? = null,
)
