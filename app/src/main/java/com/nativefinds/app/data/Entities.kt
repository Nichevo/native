package com.nativefinds.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
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
    /** Waiting for the PC AI to look at it (it was unreachable, or you asked for it later). */
    @ColumnInfo(defaultValue = "0") val pendingPc: Boolean = false,
)

/** All photos of one find (the find's [Find.photoPath] is the cover photo). */
@Entity(
    tableName = "find_photos",
    foreignKeys = [ForeignKey(entity = Find::class, parentColumns = ["id"], childColumns = ["findId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["findId"])],
)
data class FindPhoto(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val findId: Long,
    val path: String,
    /** [com.nativefinds.app.id.Category] name for this photo (leaf, flower, ...). */
    val category: String,
    val addedAt: Long,
)

/** Cached full species page (article sections, photos, taxonomy...) as JSON, keyed by name + place. */
@Entity(tableName = "species_details", primaryKeys = ["scientificName", "placeId"])
data class SpeciesDetailsRow(
    val scientificName: String,
    val placeId: Int,
    val json: String,
    val fetchedAt: Long,
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

/** A saved place such as "My garden": every find within [radiusMeters] of the centre belongs to it. */
@Entity(tableName = "places")
data class Place(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float,
    val createdAt: Long,
) {
    fun contains(lat: Double?, lng: Double?): Boolean {
        if (lat == null || lng == null) return false
        val out = FloatArray(1)
        android.location.Location.distanceBetween(latitude, longitude, lat, lng, out)
        return out[0] <= radiusMeters
    }
}

/** A timed bird count, like the NZ Garden Bird Survey: the most of each species seen at one time. */
@Entity(tableName = "bird_counts")
data class BirdCount(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The saved place it was done at, if any (the place's name is kept in case it's deleted). */
    val placeId: Long?,
    val placeName: String?,
    val startedAt: Long,
    val minutes: Int,
    val latitude: Double?,
    val longitude: Double?,
    val notes: String = "",
)

@Entity(
    tableName = "bird_count_entries",
    foreignKeys = [ForeignKey(entity = BirdCount::class, parentColumns = ["id"], childColumns = ["countId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("countId")],
)
data class BirdCountEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val countId: Long,
    val scientificName: String,
    val commonName: String?,
    val count: Int,
)
