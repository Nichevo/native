package com.nativefinds.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Find::class, FindPhoto::class, SpeciesInfo::class, SpeciesDetailsRow::class, RegionPack::class, RegionSpecies::class, Place::class, BirdCount::class, BirdCountEntry::class, IdRun::class],
    version = 7,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun finds(): FindDao
    abstract fun speciesInfo(): SpeciesInfoDao
    abstract fun regions(): RegionDao
    abstract fun places(): PlaceDao
    abstract fun counts(): BirdCountDao
    abstract fun idRuns(): IdRunDao

    companion object {
        const val NAME = "native-finds.db"

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                .build()

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `id_runs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `findId` INTEGER NOT NULL, " +
                        "`at` INTEGER NOT NULL, `kind` TEXT NOT NULL, `label` TEXT NOT NULL, `method` TEXT, `candidatesJson` TEXT NOT NULL, " +
                        "`perPhotoJson` TEXT, `chosen` TEXT, " +
                        "FOREIGN KEY(`findId`) REFERENCES `finds`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_id_runs_findId` ON `id_runs` (`findId`)")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `bird_counts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `placeId` INTEGER, " +
                        "`placeName` TEXT, `startedAt` INTEGER NOT NULL, `minutes` INTEGER NOT NULL, `latitude` REAL, `longitude` REAL, " +
                        "`notes` TEXT NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `bird_count_entries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`countId` INTEGER NOT NULL, `scientificName` TEXT NOT NULL, `commonName` TEXT, `count` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`countId`) REFERENCES `bird_counts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_bird_count_entries_countId` ON `bird_count_entries` (`countId`)")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `places` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                        "`latitude` REAL NOT NULL, `longitude` REAL NOT NULL, `radiusMeters` REAL NOT NULL, `createdAt` INTEGER NOT NULL)",
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `species_details` (`scientificName` TEXT NOT NULL, `placeId` INTEGER NOT NULL, " +
                        "`json` TEXT NOT NULL, `fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`scientificName`, `placeId`))",
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `find_photos` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`findId` INTEGER NOT NULL, `path` TEXT NOT NULL, `category` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`findId`) REFERENCES `finds`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_find_photos_findId` ON `find_photos` (`findId`)")
                db.execSQL(
                    "INSERT INTO find_photos (findId, path, category, addedAt) SELECT id, photoPath, category, createdAt FROM finds",
                )
                db.execSQL("ALTER TABLE finds ADD COLUMN pendingPc INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE species_info ADD COLUMN family TEXT")
                db.execSQL("ALTER TABLE species_info ADD COLUMN maoriName TEXT")
                // Older cache rows lack the new fields; let them refresh.
                db.execSQL("UPDATE species_info SET fetchedAt = 0")
            }
        }
    }
}
