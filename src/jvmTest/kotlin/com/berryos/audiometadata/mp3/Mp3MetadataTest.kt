package com.berryos.audiometadata.mp3

import com.berryos.audiometadata.Id3Version
import com.berryos.audiometadata.io.ByteArraySource
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.id3.ID3v22Tag
import org.jaudiotagger.tag.id3.ID3v23Tag
import org.jaudiotagger.tag.id3.ID3v24Tag
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Mp3MetadataTest {
    @Test
    fun readsId3v23Fields() {
        val tagBytes = id3v23TagBytes("Song", "Artist", "Album").toByteArray()
        val source = sourceWith(tagBytes, AUDIO)

        val metadata = assertNotNull(Mp3MetadataReader.read(source))
        val audio = metadata.toAudioMetadata()

        assertEquals(Id3Version.V23, metadata.tag?.version)
        assertEquals(tagBytes.size.toLong(), metadata.audioStartOffset)
        assertEquals("Song", audio.title)
        assertEquals(listOf("Artist"), audio.artists)
        assertEquals("Album", audio.album)
        assertEquals(7, audio.trackNumber)
        assertEquals(12, audio.trackTotal)
        assertEquals("1999", audio.year)
        assertEquals(listOf("Rock"), audio.genres)
    }

    @Test
    fun keepsAudioWhenTagFits() {
        val source = sourceWith(id3v23TagBytes("Song", "Artist", "Album").toByteArray(), AUDIO)
        val before = source.toByteArray()
        val metadata = assertNotNull(Mp3MetadataReader.read(source))

        Mp3MetadataWriter.write(source, metadata)

        assertEquals(before.size.toLong(), source.size)
        assertContentEquals(AUDIO, source.toByteArray().copyOfRange(before.size - AUDIO.size, before.size))
    }

    @Test
    fun shiftsAudioWhenTagGrows() {
        val source = sourceWith(id3v23TagBytes("S", "A", "B").toByteArray(), AUDIO)
        val metadata = assertNotNull(Mp3MetadataReader.read(source))
        val longer = "A much longer title than before, well beyond the original tag space"

        metadata.tag!!.title = longer
        Mp3MetadataWriter.write(source, metadata)

        val reread = assertNotNull(Mp3MetadataReader.read(source))
        assertEquals(longer, reread.toAudioMetadata().title)
        val bytes = source.toByteArray()
        assertContentEquals(AUDIO, bytes.copyOfRange(reread.audioStartOffset.toInt(), bytes.size))
    }

    @Test
    fun preservesUnknownFrames() {
        val rawTag = buildTag(
            textFrame("TIT2", "Song"),
            frame("XYZW", byteArrayOf(1, 2, 3, 4)),
        )
        val source = sourceWith(rawTag, AUDIO)
        val metadata = assertNotNull(Mp3MetadataReader.read(source))

        metadata.tag!!.title = "New title"
        Mp3MetadataWriter.write(source, metadata)

        val bytes = source.toByteArray()
        assertTrue(bytes.containsAscii("XYZW"))
        assertEquals(1, bytes.countAscii("XYZW"))
        val reread = assertNotNull(Mp3MetadataReader.read(source))
        assertEquals("New title", reread.toAudioMetadata().title)
        assertContentEquals(AUDIO, bytes.copyOfRange(reread.audioStartOffset.toInt(), bytes.size))
    }

    @Test
    fun readsReplayGainFromTxxx() {
        val rawTag = buildTag(
            textFrame("TIT2", "Song"),
            txxx("REPLAYGAIN_TRACK_GAIN", "-6.53 dB"),
            txxx("REPLAYGAIN_ALBUM_PEAK", "0.987654"),
        )
        val metadata = assertNotNull(Mp3MetadataReader.read(sourceWith(rawTag, AUDIO)))

        val replayGain = assertNotNull(metadata.toAudioMetadata().replayGain)
        assertEquals("-6.53 dB", replayGain.trackGain)
        assertEquals("0.987654", replayGain.albumPeak)
        assertNull(replayGain.albumGain)
    }

    @Test
    fun fallsBackToId3v1() {
        val v1 = id3v1Tag("Title", "Artist", "Album", "1998", 5)
        val metadata = assertNotNull(Mp3MetadataReader.read(sourceWith(ByteArray(0), AUDIO + v1)))

        assertNull(metadata.tag)
        val audio = metadata.toAudioMetadata()
        assertEquals("Title", audio.title)
        assertEquals(listOf("Artist"), audio.artists)
        assertEquals("Album", audio.album)
        assertEquals("1998", audio.year)
        assertEquals(5, audio.trackNumber)
    }

    @Test
    fun readsApicArtwork() {
        val image = byteArrayOf(1, 2, 3, 4)
        val apic = byteArrayOf(0) +
            "image/png".encodeToByteArray() +
            byteArrayOf(0, 3, 0) +
            image
        val rawTag = buildTag(textFrame("TIT2", "Song"), frame("APIC", apic))
        val metadata = assertNotNull(Mp3MetadataReader.read(sourceWith(rawTag, AUDIO)))

        val artworks = metadata.tag!!.artworks
        assertEquals(1, artworks.size)
        assertContentEquals(image, artworks.first().data)
        assertEquals("image/png", artworks.first().mimeType)
        assertEquals(3, artworks.first().pictureType)
    }

    @Test
    fun returnsNullWithoutTags() {
        assertNull(Mp3MetadataReader.read(sourceWith(ByteArray(0), AUDIO)))
    }

    @Test
    fun roundTripsId3v24Tag() {
        val tag = ID3v24Tag()
        tag.setField(FieldKey.TITLE, "V24")
        val out = ByteArrayOutputStream().also { tag.write(it, 0) }
        val source = sourceWith(out.toByteArray(), AUDIO)

        val metadata = assertNotNull(Mp3MetadataReader.read(source))
        assertEquals(Id3Version.V24, metadata.tag?.version)

        metadata.tag!!.artist = "Artist"
        Mp3MetadataWriter.write(source, metadata)

        val reread = assertNotNull(Mp3MetadataReader.read(source))
        assertEquals(Id3Version.V24, reread.tag?.version)
        assertEquals("V24", reread.toAudioMetadata().title)
        assertEquals(listOf("Artist"), reread.toAudioMetadata().artists)
    }

    @Test
    fun readsId3v22Tag() {
        val tag = ID3v22Tag()
        tag.setField(FieldKey.TITLE, "V22")
        val out = ByteArrayOutputStream().also { tag.write(it, 0) }
        val metadata = assertNotNull(Mp3MetadataReader.read(sourceWith(out.toByteArray(), AUDIO)))

        assertEquals(Id3Version.V22, metadata.tag?.version)
        assertEquals("V22", metadata.toAudioMetadata().title)
    }

    private fun id3v23TagBytes(title: String, artist: String, album: String): ByteArrayOutputStream {
        val tag = ID3v23Tag()
        tag.setField(FieldKey.TITLE, title)
        tag.setField(FieldKey.ARTIST, artist)
        tag.setField(FieldKey.ALBUM, album)
        tag.setField(FieldKey.TRACK, "7")
        tag.setField(FieldKey.TRACK_TOTAL, "12")
        tag.setField(FieldKey.YEAR, "1999")
        tag.setField(FieldKey.GENRE, "Rock")
        return ByteArrayOutputStream().also { tag.write(it, 0) }
    }

    private fun sourceWith(tag: ByteArray, audio: ByteArray): ByteArraySource {
        val bytes = tag + audio
        return ByteArraySource().also { it.writeAt(0, bytes) }
    }
}

private fun buildTag(vararg frames: ByteArray, padding: Int = 0): ByteArray {
    val body = frames.fold(ByteArray(0)) { acc, frame -> acc + frame } + ByteArray(padding)
    val header = ByteArray(10)
    header[0] = 'I'.code.toByte()
    header[1] = 'D'.code.toByte()
    header[2] = '3'.code.toByte()
    header[3] = 3
    writeSyncSafe(header, 6, body.size)
    return header + body
}

private fun frame(id: String, body: ByteArray): ByteArray {
    val bytes = ByteArray(10 + body.size)
    id.toByteArray(Charsets.ISO_8859_1).copyInto(bytes)
    bytes[4] = (body.size ushr 24).toByte()
    bytes[5] = (body.size ushr 16).toByte()
    bytes[6] = (body.size ushr 8).toByte()
    bytes[7] = body.size.toByte()
    body.copyInto(bytes, 10)
    return bytes
}

private fun textFrame(id: String, text: String): ByteArray =
    frame(id, byteArrayOf(0) + text.toByteArray(Charsets.ISO_8859_1))

private fun txxx(description: String, value: String): ByteArray =
    frame(
        "TXXX",
        byteArrayOf(0) +
            description.toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(0) +
            value.toByteArray(Charsets.ISO_8859_1),
    )

private fun id3v1Tag(title: String, artist: String, album: String, year: String, track: Int): ByteArray {
    val bytes = ByteArray(128)
    "TAG".toByteArray(Charsets.ISO_8859_1).copyInto(bytes)
    title.toByteArray(Charsets.ISO_8859_1).copyInto(bytes, 3)
    artist.toByteArray(Charsets.ISO_8859_1).copyInto(bytes, 33)
    album.toByteArray(Charsets.ISO_8859_1).copyInto(bytes, 63)
    year.toByteArray(Charsets.ISO_8859_1).copyInto(bytes, 93)
    bytes[125] = 0
    bytes[126] = track.toByte()
    bytes[127] = 17
    return bytes
}

private fun writeSyncSafe(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = ((value ushr 21) and 0x7F).toByte()
    bytes[offset + 1] = ((value ushr 14) and 0x7F).toByte()
    bytes[offset + 2] = ((value ushr 7) and 0x7F).toByte()
    bytes[offset + 3] = (value and 0x7F).toByte()
}

private fun ByteArray.containsAscii(value: String): Boolean = countAscii(value) > 0

private fun ByteArray.countAscii(value: String): Int {
    val needle = value.toByteArray(Charsets.ISO_8859_1)
    if (needle.isEmpty() || needle.size > size) return 0
    var count = 0
    for (index in 0..size - needle.size) {
        if (needle.indices.all { this[index + it] == needle[it] }) count++
    }
    return count
}

private val AUDIO = ByteArray(2048) { (it % 251).toByte() }
