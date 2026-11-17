package com.berryos.audiometadata.wav

import com.berryos.audiometadata.Artwork
import com.berryos.audiometadata.AudioArtworkReader
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

class WavMetadataTest {
    @Test
    fun readsTagAndAudioInfo() {
        val source = sourceWith(wavFile(id3Tag("Song")))
        val metadata = assertNotNull(WavMetadataReader.read(source))
        val audio = metadata.toAudioMetadata()

        assertEquals(AudioFormat.Wav, audio.format)
        assertEquals("Song", audio.title)
        assertEquals(44_100, audio.audioInfo?.sampleRateHz)
        assertEquals(2, audio.audioInfo?.channels)
        assertEquals(16, audio.audioInfo?.bitsPerSample)
        assertEquals(1411, audio.audioInfo?.bitrateKbps)
        assertEquals(250L, audio.audioInfo?.durationMs)
        assertEquals(AudioFormat.Wav, assertNotNull(AudioMetadataReader.read(source)).format)
    }

    @Test
    fun writesTagAndKeepsDataChunk() {
        val source = sourceWith(wavFile(id3Tag("Song")))
        val metadata = assertNotNull(WavMetadataReader.read(source))

        metadata.tag.title = "A much longer title than before"
        WavMetadataWriter.write(source, metadata)

        val after = assertNotNull(WavMetadataReader.read(source))
        assertEquals("A much longer title than before", after.toAudioMetadata().title)
        assertContentEquals(DATA, dataChunk(source, after))
    }

    @Test
    fun insertsTagChunkWhenMissing() {
        val source = sourceWith(wavFile(tag = null))
        val metadata = assertNotNull(WavMetadataReader.read(source))
        assertNull(metadata.tag.title)

        metadata.tag.title = "Added"
        WavMetadataWriter.write(source, metadata)

        val after = assertNotNull(WavMetadataReader.read(source))
        assertEquals("Added", after.toAudioMetadata().title)
        assertContentEquals(DATA, dataChunk(source, after))
    }

    @Test
    fun writesArtwork() {
        val source = sourceWith(wavFile(tag = null))
        val metadata = assertNotNull(WavMetadataReader.read(source))
        val image = "WAV-ARTWORK".encodeToByteArray()

        metadata.tag.setArtworks(listOf(Artwork(image, "image/png", 3)))
        WavMetadataWriter.write(source, metadata)

        val after = assertNotNull(WavMetadataReader.read(source))
        assertEquals(1, after.artworks.size)
        assertContentEquals(image, after.artworks.first().data)
        assertEquals(1, AudioArtworkReader.read(source).size)
    }

    @Test
    fun readsInfoChunkWhenNoId3() {
        val source = sourceWith(wavFile(tag = null, info = infoList()))
        val audio = assertNotNull(WavMetadataReader.read(source)).toAudioMetadata()

        assertEquals("Info Title", audio.title)
        assertEquals(listOf("Info Artist"), audio.artists)
        assertEquals("Info Album", audio.album)
        assertEquals(3, audio.trackNumber)
        assertEquals("2001", audio.year)
        assertEquals(listOf("Jazz"), audio.genres)
        assertEquals(listOf("Info Comment"), audio.comments)
    }

    @Test
    fun prefersId3OverInfo() {
        val source = sourceWith(wavFile(id3Tag("Song"), info = infoList()))

        assertEquals("Song", assertNotNull(WavMetadataReader.read(source)).toAudioMetadata().title)
    }

    private fun sourceWith(bytes: ByteArray): ByteArraySource =
        ByteArraySource().also { it.writeAt(0, bytes) }

    private fun dataChunk(source: RandomAccessSource, metadata: WavMetadata): ByteArray {
        val chunk = metadata.chunks.first { it.id == "data" }
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

private fun wavFile(tag: ByteArray?, data: ByteArray = DATA, info: ByteArray? = null): ByteArray {
    val body = "WAVE".encodeToByteArray() +
        chunk("fmt ", formatPayload()) +
        (tag?.let { chunk("id3 ", it) } ?: ByteArray(0)) +
        (info?.let { chunk("LIST", "INFO".encodeToByteArray() + it) } ?: ByteArray(0)) +
        chunk("data", data)
    val header = ByteArray(8)
    "RIFF".encodeToByteArray().copyInto(header)
    writeLittleEndianInt(header, 4, body.size)
    return header + body
}

private fun infoList(): ByteArray =
    infoItem("INAM", "Info Title") +
        infoItem("IART", "Info Artist") +
        infoItem("IPRD", "Info Album") +
        infoItem("ICRD", "2001") +
        infoItem("IGNR", "Jazz") +
        infoItem("ICMT", "Info Comment") +
        infoItem("ITRK", "3/9")

private fun infoItem(id: String, value: String): ByteArray {
    val payload = value.encodeToByteArray() + byteArrayOf(0)
    val header = ByteArray(8)
    id.encodeToByteArray().copyInto(header)
    writeLittleEndianInt(header, 4, payload.size)
    return header + payload + (if (payload.size % 2 == 0) ByteArray(0) else byteArrayOf(0))
}

private fun chunk(id: String, payload: ByteArray): ByteArray {
    val header = ByteArray(8)
    id.encodeToByteArray().copyInto(header)
    writeLittleEndianInt(header, 4, payload.size)
    val padding = if (payload.size % 2 == 0) ByteArray(0) else byteArrayOf(0)
    return header + payload + padding
}

private fun formatPayload(): ByteArray {
    val fmt = ByteArray(16)
    writeLittleEndianShort(fmt, 0, 1)
    writeLittleEndianShort(fmt, 2, 2)
    writeLittleEndianInt(fmt, 4, 44_100)
    writeLittleEndianInt(fmt, 8, 176_400)
    writeLittleEndianShort(fmt, 12, 4)
    writeLittleEndianShort(fmt, 14, 16)
    return fmt
}

private fun writeLittleEndianInt(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = value.toByte()
    bytes[offset + 1] = (value ushr 8).toByte()
    bytes[offset + 2] = (value ushr 16).toByte()
    bytes[offset + 3] = (value ushr 24).toByte()
}

private fun writeLittleEndianShort(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = value.toByte()
    bytes[offset + 1] = (value ushr 8).toByte()
}

private val DATA = ByteArray(44_100) { (it * 13 % 251).toByte() }
