package com.nativefinds.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Find::class, SpeciesInfo::class, RegionPack::class, RegionSpecies::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun finds(): FindDao
    abstract fun speciesInfo(): SpeciesInfoDao
    abstract fun regions(): RegionDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "native-finds.db")
                .addMigrations(MIGRATION_1_2)
                .build()

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
