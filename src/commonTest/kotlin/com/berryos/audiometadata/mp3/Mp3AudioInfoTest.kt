package com.berryos.audiometadata.mp3

import com.berryos.audiometadata.io.ByteArraySource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class Mp3AudioInfoTest {
    @Test
    fun readsXingHeader() {
        val source = sourceWith(xingFrame(frames = 100))

        val info = assertNotNull(Mp3AudioInfoReader.read(source, 0))

        assertEquals(128, info.bitrateKbps)
        assertEquals(44_100, info.sampleRateHz)
        assertEquals(2, info.channels)
        assertEquals(2612L, info.durationMs)
    }

    @Test
    fun estimatesCbrDuration() {
        val frames = ByteArray(FRAME_LENGTH * 10)
        repeat(10) { frames.fill(0, it * FRAME_LENGTH, it * FRAME_LENGTH + 4) }
        writeFrameHeader(frames, 0)
        val source = sourceWith(frames)

        val info = assertNotNull(Mp3AudioInfoReader.read(source, 0))

        assertEquals(128, info.bitrateKbps)
        assertEquals(260L, info.durationMs)
    }

    @Test
    fun skipsLeadingId3Data() {
        val prefix = ByteArray(100) { 0x55 }
        val source = sourceWith(prefix + xingFrame(frames = 50))

        val info = assertNotNull(Mp3AudioInfoReader.read(source, prefix.size.toLong()))

        assertEquals(1306L, info.durationMs)
    }

    private fun sourceWith(bytes: ByteArray): ByteArraySource =
        ByteArraySource().also { it.writeAt(0, bytes) }
}

private fun xingFrame(frames: Int): ByteArray {
    val frame = ByteArray(FRAME_LENGTH)
    writeFrameHeader(frame, 0)
    val xing = 4 + 32
    "Xing".encodeToByteArray().copyInto(frame, xing)
    writeBigEndianInt(frame, xing + 4, 0x01)
    writeBigEndianInt(frame, xing + 8, frames)
    return frame
}

private fun writeFrameHeader(bytes: ByteArray, offset: Int) {
    bytes[offset] = 0xFF.toByte()
    bytes[offset + 1] = 0xFB.toByte()
    bytes[offset + 2] = 0x90.toByte()
    bytes[offset + 3] = 0x00
}

private fun writeBigEndianInt(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = (value ushr 24).toByte()
    bytes[offset + 1] = (value ushr 16).toByte()
    bytes[offset + 2] = (value ushr 8).toByte()
    bytes[offset + 3] = value.toByte()
}

private const val FRAME_LENGTH = 417
