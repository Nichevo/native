package com.nativefinds.app.data

/**
 * Regions use iNaturalist place ids, which drive "native or introduced" status and region packs.
 * Any other iNaturalist place id can be entered in Settings.
 */
data class Region(val placeId: Int, val name: String)

object Regions {
    val NEW_ZEALAND = Region(6803, "New Zealand")

    val presets = listOf(
        NEW_ZEALAND,
        Region(6744, "Australia"),
        Region(6903, "China"),
        Region(6737, "Japan"),
        Region(6857, "United Kingdom"),
        Region(6712, "Canada"),
        Region(1, "United States"),
    )

    fun nameFor(placeId: Int): String =
        presets.firstOrNull { it.placeId == placeId }?.name ?: "Place $placeId"
}
