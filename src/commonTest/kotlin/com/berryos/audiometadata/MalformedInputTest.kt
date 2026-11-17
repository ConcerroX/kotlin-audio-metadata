package com.berryos.audiometadata

import com.berryos.audiometadata.id3.Id3v2Reader
import com.berryos.audiometadata.io.ByteArraySource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MalformedInputTest {
    @Test
    fun rejectsId3TagLargerThanFile() {
        val source = sourceWith(id3Header(Int.MAX_VALUE))

        assertNull(Id3v2Reader.read(source))
        assertFailsWith<AudioMetadataException> { AudioFile.read(source) }
    }

    @Test
    fun skipsFrameLargerThanTagBody() {
        val body = frameHeader("TIT2", Int.MAX_VALUE)
        val source = sourceWith(id3Header(body.size, body))

        val file = assertNotNull(AudioFile.read(source))
        assertEquals(null, file.title)
    }

    @Test
    fun rejectsFlacCommentLargerThanFile() {
        val file = "fLaC".encodeToByteArray() + byteArrayOf(0x84.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())

        assertFailsWith<AudioMetadataException> { AudioFile.read(sourceWith(file)) }
    }

    @Test
    fun rejectsOggPageWithMissingPayload() {
        val page = ByteArray(28)
        "OggS".encodeToByteArray().copyInto(page)
        page[5] = 2
        page[26] = 1
        page[27] = 0xFF.toByte()

        assertFailsWith<AudioMetadataException> { AudioFile.read(sourceWith(page)) }
    }

    @Test
    fun rejectsMp4AtomLargerThanFile() {
        val file = ByteArray(16)
        writeBigEndianInt(file, 0, 100)
        "ftyp".encodeToByteArray().copyInto(file, 4)

        assertFailsWith<AudioMetadataException> { AudioFile.read(sourceWith(file)) }
    }

    @Test
    fun returnsNullForUnknownData() {
        assertNull(AudioFile.read(sourceWith(ByteArray(32) { 0x55 })))
    }

    private fun sourceWith(bytes: ByteArray): ByteArraySource =
        ByteArraySource().also { it.writeAt(0, bytes) }

    private fun id3Header(declaredSize: Int, body: ByteArray = ByteArray(0)): ByteArray {
        val header = ByteArray(10)
        "ID3".encodeToByteArray().copyInto(header)
        header[3] = 4
        writeSyncSafe(header, 6, declaredSize)
        return header + body
    }

    private fun frameHeader(id: String, size: Int): ByteArray {
        val header = ByteArray(10)
        id.encodeToByteArray().copyInto(header)
        writeBigEndianInt(header, 4, size)
        return header
    }

    private fun writeSyncSafe(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = ((value ushr 21) and 0x7F).toByte()
        bytes[offset + 1] = ((value ushr 14) and 0x7F).toByte()
        bytes[offset + 2] = ((value ushr 7) and 0x7F).toByte()
        bytes[offset + 3] = (value and 0x7F).toByte()
    }

    private fun writeBigEndianInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }
}
