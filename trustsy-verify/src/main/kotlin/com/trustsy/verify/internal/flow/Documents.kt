package com.trustsy.verify.internal.flow

/**
 * Which identity document the applicant photographs, and which event says so
 * (web `documents.ts`). The EVENT is the choice: a step offering several front
 * events lets the applicant pick, and the event fired decides the path.
 */
internal enum class DocumentKind(
    val wire: String,
    val front: String,
    /** Null for a document with one page to photograph. */
    val back: String?,
    /** Stored names keep the `id-` prefix: the operator review lists `id-%`. */
    val fileStem: String,
) {
    THAI_ID("thai_id", "ID_FRONT_CAPTURED", "ID_BACK_CAPTURED", "id"),
    DRIVING_LICENSE(
        "driving_license",
        "DRIVING_LICENSE_FRONT_CAPTURED",
        "DRIVING_LICENSE_BACK_CAPTURED",
        "id-driving-license",
    ),
    PASSPORT("passport", "PASSPORT_CAPTURED", null, "id-passport"),
}

internal enum class DocumentSide(val wire: String) { FRONT("front"), BACK("back") }

internal object Documents {
    const val THAILAND = "TH"

    /** The documents an applicant may present for an issuing country. */
    fun documentsFor(country: String): List<DocumentKind> =
        if (country == THAILAND) listOf(DocumentKind.THAI_ID, DocumentKind.DRIVING_LICENSE)
        else listOf(DocumentKind.PASSPORT)

    /** Documents whose FRONT this step accepts, in display order. */
    fun offered(actions: List<String>): List<DocumentKind> =
        DocumentKind.entries.filter { it.front in actions }

    /** The document whose BACK this step accepts, if any. */
    fun awaitingBack(actions: List<String>): DocumentKind? =
        DocumentKind.entries.firstOrNull { kind -> kind.back != null && kind.back in actions }

    fun eventFor(kind: DocumentKind, side: DocumentSide): String =
        if (side == DocumentSide.BACK && kind.back != null) kind.back else kind.front

    fun fileNameFor(kind: DocumentKind, side: DocumentSide): String =
        if (kind == DocumentKind.PASSPORT) "${kind.fileStem}.jpg" else "${kind.fileStem}-${side.wire}.jpg"
}
