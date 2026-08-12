package com.example.audiorecorder

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class RecordingDatabaseMigrationTest {

    private val testDbName = "migration_test_db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RecordingDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    @Throws(IOException::class)
    fun `migrate 1 to 2 adds title and quality columns`() {
        var db = helper.createDatabase(testDbName, 1)
        db.execSQL(
            "INSERT INTO recordings (id, fileName, filePath, durationMs, fileSizeBytes, createdAt, bookmarks) " +
            "VALUES (1, 'rec_old.aac', '/path/old.aac', 5000, 1024, 1000, '')"
        )
        db.close()

        db = helper.runMigrationsAndValidate(testDbName, 2, true, RecordingDatabase.MIGRATION_1_2)
        val cursor = db.query("SELECT * FROM recordings WHERE id = 1")
        assertTrue(cursor.moveToFirst())
        val titleIdx = cursor.getColumnIndex("title")
        val qualityIdx = cursor.getColumnIndex("quality")
        assertTrue(titleIdx >= 0)
        assertTrue(qualityIdx >= 0)
        assertEquals("", cursor.getString(titleIdx))
        assertEquals("HIGH", cursor.getString(qualityIdx))
        cursor.close()
        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun `migrate 2 to 3 adds status and uuid columns`() {
        var db = helper.createDatabase(testDbName, 2)
        db.execSQL(
            "INSERT INTO recordings (id, fileName, filePath, durationMs, fileSizeBytes, createdAt, bookmarks, title, quality) " +
            "VALUES (1, 'rec_v2.aac', '/path/v2.aac', 10000, 2048, 2000, '1000,2000', 'جلسه', 'MEDIUM')"
        )
        db.close()

        db = helper.runMigrationsAndValidate(testDbName, 3, true, RecordingDatabase.MIGRATION_2_3)
        val cursor = db.query("SELECT * FROM recordings WHERE id = 1")
        assertTrue(cursor.moveToFirst())
        val statusIdx = cursor.getColumnIndex("status")
        val uuidIdx = cursor.getColumnIndex("uuid")
        assertTrue(statusIdx >= 0)
        assertTrue(uuidIdx >= 0)
        assertEquals("PENDING", cursor.getString(statusIdx))
        assertEquals("", cursor.getString(uuidIdx))
        cursor.close()
        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun `migrate 1 to 3 preserves all existing data`() {
        var db = helper.createDatabase(testDbName, 1)
        db.execSQL(
            "INSERT INTO recordings (id, fileName, filePath, durationMs, fileSizeBytes, createdAt, bookmarks) " +
            "VALUES (42, 'legacy.aac', '/legacy.aac', 30000, 50000, 123456789, '5000')"
        )
        db.close()

        db = helper.runMigrationsAndValidate(
            testDbName, 3, true,
            RecordingDatabase.MIGRATION_1_2, RecordingDatabase.MIGRATION_2_3
        )

        val cursor = db.query("SELECT * FROM recordings WHERE id = 42")
        assertTrue(cursor.moveToFirst())
        assertEquals("legacy.aac", cursor.getString(cursor.getColumnIndexOrThrow("fileName")))
        assertEquals("/legacy.aac", cursor.getString(cursor.getColumnIndexOrThrow("filePath")))
        assertEquals(30000, cursor.getLong(cursor.getColumnIndexOrThrow("durationMs")))
        assertEquals("PENDING", cursor.getString(cursor.getColumnIndexOrThrow("status")))
        cursor.close()
        db.close()
    }
}

====================================
END OF PROJECT
====================================
