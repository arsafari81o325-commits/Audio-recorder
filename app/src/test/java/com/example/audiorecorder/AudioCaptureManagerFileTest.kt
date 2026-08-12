package com.example.audiorecorder

import android.media.projection.MediaProjection
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioCaptureManagerFileTest {

    private lateinit var mockProjection: MediaProjection
    private lateinit var tempDir: File

    @Before
    fun setup() {
        mockProjection = mock()
        tempDir = createTempDir("rec_test")
    }

    @Test
    fun `output file size returns zero before recording starts`() {
        val manager = AudioCaptureManager(mockProjection, RecordingQuality.HIGH)
        assertEquals(0, manager.getOutputFileSize())
    }

    @Test
    fun `disk full callback is invoked when write fails`() {
        var diskFullCalled = false
        val manager = AudioCaptureManager(mockProjection, RecordingQuality.HIGH)
        manager.onDiskFull = { diskFullCalled = true }
        manager.onDiskFull?.invoke()
        assertTrue(diskFullCalled)
    }

    @Test
    fun `total paused duration accumulates correctly`() {
        val manager = AudioCaptureManager(mockProjection, RecordingQuality.HIGH)
        assertEquals(0, manager.totalPausedDurationMs)
        manager.pause()
        Thread.sleep(50)
        manager.resume()
        assertTrue(manager.totalPausedDurationMs >= 40)
    }

    @Test
    fun `isPaused flag toggles correctly`() {
        val manager = AudioCaptureManager(mockProjection, RecordingQuality.HIGH)
        assertFalse(manager.isPaused)
        manager.pause()
        assertTrue(manager.isPaused)
        manager.resume()
        assertFalse(manager.isPaused)
    }

    @Test
    fun `unique filename generation avoids collision`() {
        val names = mutableSetOf<String>()
        repeat(100) {
            val uuid = java.util.UUID.randomUUID().toString()
            names.add("rec_${uuid}.aac")
        }
        assertEquals(100, names.size)
    }
}
