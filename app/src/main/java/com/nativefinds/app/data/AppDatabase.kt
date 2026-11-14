package com.nativefinds.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Find::class, FindPhoto::class, SpeciesInfo::class, SpeciesDetailsRow::class, RegionPack::class, RegionSpecies::class],
    version = 4,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun finds(): FindDao
    abstract fun speciesInfo(): SpeciesInfoDao
    abstract fun regions(): RegionDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "native-finds.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()

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
