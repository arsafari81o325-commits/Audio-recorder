package com.example.audiorecorder

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingQualityTest {

    @Test
    fun `fromName returns matching enum`() {
        assertEquals(RecordingQuality.LOW, RecordingQuality.fromName("LOW"))
        assertEquals(RecordingQuality.MEDIUM, RecordingQuality.fromName("MEDIUM"))
        assertEquals(RecordingQuality.HIGH, RecordingQuality.fromName("HIGH"))
    }

    @Test
    fun `fromName falls back to HIGH for unknown or null input`() {
        assertEquals(RecordingQuality.HIGH, RecordingQuality.fromName(null))
        assertEquals(RecordingQuality.HIGH, RecordingQuality.fromName("garbage"))
    }
}
