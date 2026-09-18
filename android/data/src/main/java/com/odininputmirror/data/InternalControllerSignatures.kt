package com.odininputmirror.data

/**
 * Identifies a handheld's built-in controller by its USB id. Devices whose internal pad matches
 * a known signature are auto-detected; unknown devices fall back to manual user selection.
 *
 * @param vendorIds vendor ids the internal controller reports.
 * @param productIds product ids the internal controller reports (across its modes, if any).
 * @param hasVendorMirroringQuirk true when the OS re-exposes connected external controllers as
 *   virtual nodes carrying this vendor id (the Odin does this), which requires twin-name
 *   filtering to tell a genuine internal pad from a mirrored external one.
 */
internal data class InternalControllerSignature(
    val vendorIds: Set<Int>,
    val productIds: Set<Int>,
    val hasVendorMirroringQuirk: Boolean,
) {
    fun matches(vendorId: Int, productId: Int): Boolean =
        vendorId in vendorIds && productId in productIds
}

internal const val ODIN_VENDOR_ID = 0x2020
internal const val ODIN_NINTENDO_PRODUCT_ID = 0x0111
internal const val ODIN_XBOX_PRODUCT_ID = 0x0112

internal val ODIN_INTERNAL_CONTROLLER_SIGNATURE = InternalControllerSignature(
    vendorIds = setOf(ODIN_VENDOR_ID),
    productIds = setOf(ODIN_NINTENDO_PRODUCT_ID, ODIN_XBOX_PRODUCT_ID),
    hasVendorMirroringQuirk = true,
)

/** Registry of recognised handheld internal controllers. Add an entry to support a new device. */
internal val KNOWN_INTERNAL_CONTROLLER_SIGNATURES: List<InternalControllerSignature> = listOf(
    ODIN_INTERNAL_CONTROLLER_SIGNATURE,
)

internal fun matchedInternalSignature(vendorId: Int, productId: Int): InternalControllerSignature? =
    KNOWN_INTERNAL_CONTROLLER_SIGNATURES.firstOrNull { it.matches(vendorId, productId) }

/** Vendor ids belonging to a signature that re-exposes external controllers (mirroring quirk). */
internal val MIRRORING_QUIRK_VENDOR_IDS: Set<Int> =
    KNOWN_INTERNAL_CONTROLLER_SIGNATURES
        .filter { it.hasVendorMirroringQuirk }
        .flatMap { it.vendorIds }
        .toSet()
