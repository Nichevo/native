package com.nativefinds.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface FindDao {
    @Query("SELECT * FROM finds ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Find>>

    @Query("SELECT * FROM finds ORDER BY createdAt DESC")
    suspend fun getAll(): List<Find>

    @Query("SELECT * FROM finds WHERE id = :id")
    fun observe(id: Long): Flow<Find?>

    @Query("SELECT * FROM finds WHERE id = :id")
    suspend fun get(id: Long): Find?

    @Insert
    suspend fun insert(find: Find): Long

    @Query("SELECT * FROM finds WHERE pendingPc = 1")
    suspend fun pendingPc(): List<Find>

    @Query("SELECT * FROM find_photos WHERE findId = :findId ORDER BY addedAt, id")
    suspend fun photos(findId: Long): List<FindPhoto>

    @Query("SELECT * FROM find_photos WHERE findId = :findId ORDER BY addedAt, id")
    fun observePhotos(findId: Long): Flow<List<FindPhoto>>

    @Query("SELECT * FROM find_photos")
    suspend fun allPhotos(): List<FindPhoto>

    @Query("SELECT * FROM find_photos ORDER BY addedAt")
    fun observeAllPhotos(): Flow<List<FindPhoto>>

    @Insert
    suspend fun insertPhotos(photos: List<FindPhoto>)

    @Query("DELETE FROM find_photos WHERE findId = :findId")
    suspend fun deletePhotos(findId: Long)

    @Update
    suspend fun update(find: Find)

    @Delete
    suspend fun delete(find: Find)
}

@Dao
interface SpeciesInfoDao {
    @Query("SELECT * FROM species_details WHERE scientificName = :name COLLATE NOCASE AND placeId = :placeId LIMIT 1")
    suspend fun getDetails(name: String, placeId: Int): SpeciesDetailsRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putDetails(row: SpeciesDetailsRow)

    @Query("SELECT * FROM species_info WHERE scientificName = :name COLLATE NOCASE LIMIT 1")
    suspend fun get(name: String): SpeciesInfo?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(info: SpeciesInfo)
}

@Dao
interface RegionDao {
    @Query("SELECT * FROM region_packs ORDER BY name")
    fun observePacks(): Flow<List<RegionPack>>

    @Query("SELECT * FROM region_packs WHERE placeId = :placeId")
    suspend fun getPack(placeId: Int): RegionPack?

    @Query("SELECT * FROM region_packs WHERE placeId = :placeId")
    fun observePack(placeId: Int): Flow<RegionPack?>

    @Query("SELECT * FROM region_species WHERE placeId = :placeId")
    fun observeSpecies(placeId: Int): Flow<List<RegionSpecies>>

    @Query("SELECT * FROM region_species WHERE placeId = :placeId")
    suspend fun species(placeId: Int): List<RegionSpecies>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putPack(pack: RegionPack)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSpecies(species: List<RegionSpecies>)

    @Query("DELETE FROM region_species WHERE placeId = :placeId")
    suspend fun clearSpecies(placeId: Int)

    @Query("DELETE FROM region_packs WHERE placeId = :placeId")
    suspend fun deletePack(placeId: Int)

    @Query("SELECT * FROM region_species WHERE placeId = :placeId AND scientificName = :name COLLATE NOCASE LIMIT 1")
    suspend fun findSpecies(placeId: Int, name: String): RegionSpecies?

    /** Matches a species or any of its subspecies ("Prosthemadera novaeseelandiae novaeseelandiae"). */
    @Query("SELECT * FROM region_species WHERE placeId = :placeId AND (scientificName = :name COLLATE NOCASE OR scientificName LIKE :name || ' %') ORDER BY observations DESC LIMIT 1")
    suspend fun findSpeciesOrSub(placeId: Int, name: String): RegionSpecies?

    /** Matches by genus, for model labels that only name a genus. */
    @Query("SELECT COUNT(*) FROM region_species WHERE placeId = :placeId AND scientificName LIKE :genus || ' %'")
    suspend fun countGenus(placeId: Int, genus: String): Int
}

@Dao
interface PlaceDao {
    @Query("SELECT * FROM places ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<Place>>

    @Query("SELECT * FROM places ORDER BY name COLLATE NOCASE")
    suspend fun all(): List<Place>

    @Query("SELECT * FROM places WHERE id = :id")
    fun observe(id: Long): Flow<Place?>

    @Insert
    suspend fun insert(place: Place): Long

    @Update
    suspend fun update(place: Place)

    @Delete
    suspend fun delete(place: Place)
}

@Dao
interface BirdCountDao {
    @Query("SELECT * FROM bird_counts ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<BirdCount>>

    @Query("SELECT * FROM bird_count_entries")
    fun observeAllEntries(): Flow<List<BirdCountEntry>>

    @Insert
    suspend fun insert(count: BirdCount): Long

    @Insert
    suspend fun insertEntries(entries: List<BirdCountEntry>)

    @Delete
    suspend fun delete(count: BirdCount)
}
