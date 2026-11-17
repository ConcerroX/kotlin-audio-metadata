package com.berryos.audiometadata.aiff

import com.berryos.audiometadata.AudioFormat
import com.berryos.audiometadata.AudioMetadataReader
import com.berryos.audiometadata.io.ByteArraySource
import com.berryos.audiometadata.io.RandomAccessSource
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.id3.ID3v23Tag
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AiffMetadataTest {
    @Test
    fun readsTagAndAudioInfo() {
        val source = sourceWith(aiffFile(id3Tag("Song")))
        val metadata = assertNotNull(AiffMetadataReader.read(source))
        val audio = metadata.toAudioMetadata()

        assertEquals(AudioFormat.AIFF, audio.format)
        assertEquals("Song", audio.title)
        assertEquals(44_100, audio.audioInfo?.sampleRateHz)
        assertEquals(2, audio.audioInfo?.channels)
        assertEquals(16, audio.audioInfo?.bitsPerSample)
        assertEquals(1411, audio.audioInfo?.bitrateKbps)
        assertEquals(1000L, audio.audioInfo?.durationMs)
        assertEquals(AudioFormat.AIFF, assertNotNull(AudioMetadataReader.read(source)).format)
    }

    @Test
    fun writesTagAndKeepsSoundData() {
        val source = sourceWith(aiffFile(id3Tag("Song")))
        val metadata = assertNotNull(AiffMetadataReader.read(source))

        metadata.tag.title = "A much longer title than before"
        AiffMetadataWriter.write(source, metadata)

        val after = assertNotNull(AiffMetadataReader.read(source))
        assertEquals("A much longer title than before", after.toAudioMetadata().title)
        assertContentEquals(soundData(), chunkPayload(source, after, "SSND"))
    }

    @Test
    fun insertsTagChunkWhenMissing() {
        val source = sourceWith(aiffFile(tag = null))
        val metadata = assertNotNull(AiffMetadataReader.read(source))
        assertNull(metadata.tag.title)

        metadata.tag.title = "Added"
        AiffMetadataWriter.write(source, metadata)

        val after = assertNotNull(AiffMetadataReader.read(source))
        assertEquals("Added", after.toAudioMetadata().title)
        assertContentEquals(soundData(), chunkPayload(source, after, "SSND"))
    }

    @Test
    fun readsNativeTextWhenNoId3() {
        val source = sourceWith(aiffFile(tag = null, nativeText = nativeText()))
        val audio = assertNotNull(AiffMetadataReader.read(source)).toAudioMetadata()

        assertEquals("Native Title", audio.title)
        assertEquals(listOf("Native Artist"), audio.artists)
        assertEquals(listOf("First note", "Second note"), audio.comments)
    }

    @Test
    fun prefersId3OverNativeText() {
        val source = sourceWith(aiffFile(id3Tag("Song"), nativeText = nativeText()))

        assertEquals("Song", assertNotNull(AiffMetadataReader.read(source)).toAudioMetadata().title)
    }

    private fun sourceWith(bytes: ByteArray): ByteArraySource =
        ByteArraySource().also { it.writeAt(0, bytes) }

    private fun chunkPayload(source: RandomAccessSource, metadata: AiffMetadata, id: String): ByteArray {
        val chunk = metadata.chunks.first { it.id == id }
        val payload = ByteArray(chunk.size.toInt())
        var read = 0
        while (read < payload.size) {
            read += source.readAt(chunk.payloadOffset + read, payload, read, payload.size - read)
        }
        return payload
    }
}

private fun id3Tag(title: String): ByteArray {
    val tag = ID3v23Tag()
    tag.setField(FieldKey.TITLE, title)
    return ByteArrayOutputStream().also { tag.write(it, 0) }.toByteArray()
}

private fun aiffFile(tag: ByteArray?, nativeText: ByteArray = ByteArray(0)): ByteArray {
    val body = "AIFF".encodeToByteArray() +
        chunk("COMM", commonChunk()) +
        chunk("SSND", soundData()) +
        nativeText +
        (tag?.let { chunk("ID3 ", it) } ?: ByteArray(0))
    val header = ByteArray(8)
    "FORM".encodeToByteArray().copyInto(header)
    writeBigEndianInt(header, 4, body.size)
    return header + body
}

private fun nativeText(): ByteArray =
    chunk("NAME", "Native Title".encodeToByteArray()) +
        chunk("AUTH", "Native Artist".encodeToByteArray()) +
        chunk("ANNO", "First note".encodeToByteArray()) +
        chunk("ANNO", "Second note".encodeToByteArray())

private fun commonChunk(): ByteArray {
    val bytes = ByteArray(18)
    writeBigEndianShort(bytes, 0, 2)
    writeBigEndianInt(bytes, 2, 44_100)
    writeBigEndianShort(bytes, 6, 16)
    writeExtendedFloat(bytes, 8, 44_100)
    return bytes
}

private fun soundData(): ByteArray {
    val header = ByteArray(8)
    return header + DATA
}

private fun chunk(id: String, payload: ByteArray): ByteArray {
    val header = ByteArray(8)
    id.encodeToByteArray().copyInto(header)
    writeBigEndianInt(header, 4, payload.size)
    val padding = if (payload.size % 2 == 0) ByteArray(0) else byteArrayOf(0)
    return header + payload + padding
}

private fun writeExtendedFloat(bytes: ByteArray, offset: Int, value: Int) {
    val exponent = 16_383 + 15
    bytes[offset] = (exponent ushr 8).toByte()
    bytes[offset + 1] = exponent.toByte()
    val mantissa = value.toULong() shl 48
    for (index in 0 until 8) {
        bytes[offset + 2 + index] = (mantissa shr (56 - index * 8)).toByte()
    }
}

private fun writeBigEndianInt(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = (value ushr 24).toByte()
    bytes[offset + 1] = (value ushr 16).toByte()
    bytes[offset + 2] = (value ushr 8).toByte()
    bytes[offset + 3] = value.toByte()
}

private fun writeBigEndianShort(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = (value ushr 8).toByte()
    bytes[offset + 1] = value.toByte()
}

private val DATA = ByteArray(44_100) { (it * 17 % 251).toByte() }
