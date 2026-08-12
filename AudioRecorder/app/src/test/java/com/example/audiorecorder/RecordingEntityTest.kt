package com.example.audiorecorder

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingEntityTest {

    @Test
    fun `displayName falls back to fileName when title is blank`() {
        val entity = RecordingEntity(fileName = "recording_20260101_120000.aac", filePath = "/x")
        assertEquals("recording_20260101_120000.aac", entity.displayName)
    }

    @Test
    fun `displayName prefers title when set`() {
        val entity = RecordingEntity(
            fileName = "recording_20260101_120000.aac",
            filePath = "/x",
            title = "جلسه کنکور"
        )
        assertEquals("جلسه کنکور", entity.displayName)
    }

    @Test
    fun `bookmarks csv parses back to offsets`() {
        val csv = "1000,2500,7000"
        val offsets = csv.split(",").mapNotNull { it.trim().toLongOrNull() }
        assertEquals(listOf(1000L, 2500L, 7000L), offsets)
    }
}
