package com.example.audiorecorder

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RecordingSessionTest {

    private lateinit var session: RecordingSession

    @Before
    fun setup() {
        session = RecordingSession()
    }

    @Test
    fun `initial state is Idle`() = runBlocking {
        val state = session.state.first()
        assertTrue(state is RecordingSessionState.Idle)
    }

    @Test
    fun `transition Idle to Initializing`() = runBlocking {
        session.setInitializing()
        val state = session.state.first()
        assertTrue(state is RecordingSessionState.Initializing)
    }

    @Test
    fun `transition Initializing to Recording`() = runBlocking {
        session.setInitializing()
        session.setRecording(elapsedSeconds = 5, amplitude = 50, bookmarkCount = 2, quality = RecordingQuality.HIGH)
        val state = session.state.first()
        assertTrue(state is RecordingSessionState.Recording)
        assertEquals(5, (state as RecordingSessionState.Recording).elapsedSeconds)
        assertEquals(50, state.amplitude)
        assertEquals(2, state.bookmarkCount)
        assertEquals(RecordingQuality.HIGH, state.quality)
    }

    @Test
    fun `transition Recording to Paused`() = runBlocking {
        session.setRecording(10, 30, 1, RecordingQuality.MEDIUM)
        session.setPaused(elapsedSeconds = 10, bookmarkCount = 1, quality = RecordingQuality.MEDIUM)
        val state = session.state.first()
        assertTrue(state is RecordingSessionState.Paused)
        assertEquals(10, (state as RecordingSessionState.Paused).elapsedSeconds)
    }

    @Test
    fun `transition Paused to Recording`() = runBlocking {
        session.setPaused(10, 1, RecordingQuality.MEDIUM)
        session.setRecording(11, 40, 1, RecordingQuality.MEDIUM)
        val state = session.state.first()
        assertTrue(state is RecordingSessionState.Recording)
    }

    @Test
    fun `transition Recording to Stopping`() = runBlocking {
        session.setRecording(20, 10, 0, RecordingQuality.LOW)
        session.setStopping()
        val state = session.state.first()
        assertTrue(state is RecordingSessionState.Stopping)
    }

    @Test
    fun `transition Stopping to Idle`() = runBlocking {
        session.setStopping()
        session.setIdle()
        val state = session.state.first()
        assertTrue(state is RecordingSessionState.Idle)
    }

    @Test
    fun `Error state preserves message`() = runBlocking {
        session.setError(RecordingError.StorageFull)
        val state = session.state.first()
        assertTrue(state is RecordingSessionState.Error)
        assertEquals("فضای ذخیره‌سازی کافی نیست. لطفاً فضای خالی ایجاد کنید.",
            (state as RecordingSessionState.Error).error.userMessage)
    }

    @Test
    fun `state is overwritten by latest set call`() = runBlocking {
        session.setRecording(1, 1, 0, RecordingQuality.HIGH)
        session.setError(RecordingError.Unknown)
        val state = session.state.first()
        assertTrue(state is RecordingSessionState.Error)
    }

    @Test
    fun `impossible state isRecording true with isPaused true cannot exist`() {
        val recording = RecordingSessionState.Recording(0, 0, 0, RecordingQuality.HIGH)
        val paused = RecordingSessionState.Paused(0, 0, RecordingQuality.HIGH)
        assertNotEquals(recording::class, paused::class)
    }
}
