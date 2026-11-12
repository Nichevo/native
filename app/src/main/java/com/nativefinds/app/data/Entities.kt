package com.nativefinds.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One saved sighting: the photo, where and when, and what it was identified as. */
@Entity(tableName = "finds")
data class Find(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val photoPath: String,
    val createdAt: Long,
    val latitude: Double?,
    val longitude: Double?,
    val accuracyMeters: Float?,
    /** [com.nativefinds.app.id.Category] name. */
    val category: String,
    val scientificName: String?,
    val commonName: String?,
    /** 0..1 score from the identifier, if any. */
    val confidence: Float?,
    /** Which identifier produced the chosen name (Pl@ntNet, on-phone, PC AI, manual). */
    val idSource: String?,
    val taxonId: Long?,
    /** e.g. "endemic", "native", "introduced" for [regionPlaceId]. */
    val establishment: String?,
    val regionPlaceId: Int?,
    val notes: String = "",
)

/** Cached species information, so species pages work offline once seen. */
@Entity(tableName = "species_info")
data class SpeciesInfo(
    @PrimaryKey val scientificName: String,
    val taxonId: Long?,
    val commonName: String?,
    val photoUrl: String?,
    val wikipediaUrl: String?,
    val summary: String?,
    val establishment: String?,
    val conservationStatus: String?,
    val placeId: Int?,
    val iconicTaxon: String?,
    val fetchedAt: Long,
    /** e.g. "Rallidae (Rails, Crakes, and Coots)" */
    val family: String? = null,
    /** Te reo Māori name, when iNaturalist has one. */
    val maoriName: String? = null,
)

/** Downloaded region pack header. */
@Entity(tableName = "region_packs")
data class RegionPack(
    @PrimaryKey val placeId: Int,
    val name: String,
    val downloadedAt: Long,
    val speciesCount: Int,
)

/** A species recorded in a region pack. */
@Entity(
    tableName = "region_species",
    primaryKeys = ["placeId", "taxonId"],
    indices = [Index(value = ["placeId", "scientificName"])],
)
data class RegionSpecies(
    val placeId: Int,
    val taxonId: Long,
    val scientificName: String,
    val commonName: String?,
    val iconicTaxon: String?,
    val photoUrl: String?,
    val observations: Int,
    val establishment: String?,
)
