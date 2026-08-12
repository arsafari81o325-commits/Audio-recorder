package com.example.audiorecorder

import org.junit.Assert.*
import org.junit.Test

class AdtsHeaderTest {

    @Test
    fun `adts header sync word is 0xFFF`() {
        val packet = ByteArray(100)
        addAdtsHeader(packet, 100, RecordingQuality.HIGH)
        assertEquals(0xFF, packet[0].toInt() and 0xFF)
        assertEquals(0xF9, packet[1].toInt() and 0xFF)
    }

    @Test
    fun `adts header profile is AAC-LC (2)`() {
        val packet = ByteArray(100)
        addAdtsHeader(packet, 100, RecordingQuality.HIGH)
        val byte2 = packet[2].toInt() and 0xFF
        val profile = ((byte2 shr 6) and 0x03) + 1
        assertEquals(2, profile)
    }

    @Test
    fun `adts header freqIdx matches HIGH quality 48kHz`() {
        val packet = ByteArray(100)
        addAdtsHeader(packet, 100, RecordingQuality.HIGH)
        val byte2 = packet[2].toInt() and 0xFF
        val freqIdx = (byte2 shr 2) and 0x0F
        assertEquals(3, freqIdx)
    }

    @Test
    fun `adts header freqIdx matches MEDIUM quality 44_1kHz`() {
        val packet = ByteArray(100)
        addAdtsHeader(packet, 100, RecordingQuality.MEDIUM)
        val byte2 = packet[2].toInt() and 0xFF
        val freqIdx = (byte2 shr 2) and 0x0F
        assertEquals(4, freqIdx)
    }

    @Test
    fun `adts header freqIdx matches LOW quality 24kHz`() {
        val packet = ByteArray(100)
        addAdtsHeader(packet, 100, RecordingQuality.LOW)
        val byte2 = packet[2].toInt() and 0xFF
        val freqIdx = (byte2 shr 2) and 0x0F
        assertEquals(6, freqIdx)
    }

    @Test
    fun `adts header channel config matches stereo`() {
        val packet = ByteArray(100)
        addAdtsHeader(packet, 100, RecordingQuality.HIGH)
        val byte2 = packet[2].toInt() and 0xFF
        val byte3 = packet[3].toInt() and 0xFF
        val chanCfg = ((byte2 and 0x03) shl 2) or ((byte3 shr 6) and 0x03)
        assertEquals(2, chanCfg)
    }

    @Test
    fun `adts header channel config matches mono`() {
        val packet = ByteArray(100)
        addAdtsHeader(packet, 100, RecordingQuality.LOW)
        val byte2 = packet[2].toInt() and 0xFF
        val byte3 = packet[3].toInt() and 0xFF
        val chanCfg = ((byte2 and 0x03) shl 2) or ((byte3 shr 6) and 0x03)
        assertEquals(1, chanCfg)
    }

    @Test
    fun `adts header packet length is encoded correctly`() {
        val packetLen = 523
        val packet = ByteArray(packetLen)
        addAdtsHeader(packet, packetLen, RecordingQuality.HIGH)
        val byte3 = packet[3].toInt() and 0xFF
        val byte4 = packet[4].toInt() and 0xFF
        val byte5 = packet[5].toInt() and 0xFF
        val decodedLen = ((byte3 and 0x03) shl 11) or (byte4 shl 3) or ((byte5 shr 5) and 0x07)
        assertEquals(packetLen, decodedLen)
    }

    @Test
    fun `adts header protection absent bit is set`() {
        val packet = ByteArray(100)
        addAdtsHeader(packet, 100, RecordingQuality.HIGH)
        val byte1 = packet[1].toInt() and 0xFF
        assertEquals(1, byte1 and 0x01)
    }

    companion object {
        private fun addAdtsHeader(packet: ByteArray, packetLen: Int, quality: RecordingQuality) {
            val profile = 2
            val freqIdx = sampleRateToAdtsIndex(quality.sampleRate)
            val chanCfg = quality.channelCount

            packet[0] = 0xFF.toByte()
            packet[1] = 0xF9.toByte()
            packet[2] = (((profile - 1) shl 6) or (freqIdx shl 2) or (chanCfg shr 2)).toByte()
            packet[3] = (((chanCfg and 3) shl 6) or (packetLen shr 11)).toByte()
            packet[4] = ((packetLen and 0x7FF) shr 3).toByte()
            packet[5] = (((packetLen and 7) shl 5) or 0x1F).toByte()
            packet[6] = 0xFC.toByte()
        }

        private fun sampleRateToAdtsIndex(sampleRate: Int): Int = when (sampleRate) {
            96000 -> 0; 88200 -> 1; 64000 -> 2; 48000 -> 3; 44100 -> 4
            32000 -> 5; 24000 -> 6; 22050 -> 7; 16000 -> 8
            12000 -> 9; 11025 -> 10; 8000 -> 11
            else -> 4
        }
    }
}
