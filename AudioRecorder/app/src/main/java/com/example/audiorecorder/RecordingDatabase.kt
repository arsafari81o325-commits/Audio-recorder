package com.example.audiorecorder

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [RecordingEntity::class], version = 2, exportSchema = false)
abstract class RecordingDatabase : RoomDatabase() {
    abstract fun recordingDao(): RecordingDao

    companion object {
        @Volatile
        private var INSTANCE: RecordingDatabase? = null

        // v1 -> v2: added `title` and `quality` columns (rename + quality display).
        // A real migration (not destructive) so existing recordings survive the upgrade.
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recordings ADD COLUMN title TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE recordings ADD COLUMN quality TEXT NOT NULL DEFAULT 'HIGH'")
            }
        }

        fun getDatabase(context: Context): RecordingDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    RecordingDatabase::class.java,
                    "recording_database"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
