package com.berryos.audiometadata.vorbis

import com.berryos.audiometadata.AudioFormat
import com.berryos.audiometadata.encodeBase64
import com.berryos.audiometadata.flacPicture
import com.berryos.audiometadata.io.ByteArraySource
import com.berryos.audiometadata.ogg.OggPage
import com.berryos.audiometadata.ogg.OggPages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OggVorbisMetadataTest {
    @Test
    fun readsTagsAndAudioInfo() {
        val metadata = assertNotNull(OggVorbisMetadataReader.read(sourceWith(vorbisFile("Song", "Artist"))))
        val audio = metadata.toAudioMetadata()

        assertEquals(AudioFormat.Ogg, audio.format)
        assertEquals("Song", audio.title)
        assertEquals(listOf("Artist"), audio.artists)
        assertEquals(44_100, audio.audioInfo?.sampleRateHz)
        assertEquals(2, audio.audioInfo?.channels)
        assertEquals(128, audio.audioInfo?.bitrateKbps)
        assertEquals(1000L, audio.audioInfo?.durationMs)
    }

    @Test
    fun writesTagsAndKeepsSetupAndAudioPages() {
        val source = sourceWith(vorbisFile("Song", "Artist"))
        val metadata = assertNotNull(OggVorbisMetadataReader.read(source))

        metadata.tag.title = "A".repeat(200)
        OggVorbisMetadataWriter.write(source, metadata)

        val reread = assertNotNull(OggVorbisMetadataReader.read(source))
        assertEquals("A".repeat(200), reread.toAudioMetadata().title)
        assertEquals("Artist", reread.toAudioMetadata().artists.first())

        val sequences = walk(source).map(OggPage::sequenceNumber)
        assertEquals(sequences.indices.toList(), sequences)
        val bytes = source.toByteArray()
        assertTrue(bytes.containsAscii(SETUP_MARKER))
        assertTrue(bytes.containsAscii(AUDIO_MARKER))
        assertEquals(44_100L, walk(source).lastOrNull { it.granulePosition > 0 }?.granulePosition)
    }

    @Test
    fun readsEmbeddedPicture() {
        val image = "PNG-DATA".encodeToByteArray()
        val tag = VorbisCommentTag.create().apply {
            title = "Song"
            set("METADATA_BLOCK_PICTURE", encodeBase64(flacPicture(image)))
        }
        val metadata = assertNotNull(OggVorbisMetadataReader.read(sourceWith(vorbisFile(tag))))

        assertEquals(1, metadata.artworks.size)
        val artwork = metadata.artworks.first()
        kotlin.test.assertContentEquals(image, artwork.data)
        assertEquals("image/png", artwork.mimeType)
        assertEquals(3, artwork.pictureType)
    }

    private fun sourceWith(bytes: ByteArray): ByteArraySource =
        ByteArraySource().also { it.writeAt(0, bytes) }

    private fun walk(source: ByteArraySource): List<OggPage> {
        val pages = ArrayList<OggPage>()
        var offset = 0L
        while (offset < source.size) {
            val page = OggPages.read(source, offset) ?: break
            pages += page
            offset += page.size
        }
        return pages
    }
}

internal fun vorbisFile(title: String, artist: String): ByteArray = vorbisFile(
    VorbisCommentTag.create().apply {
        this.title = title
        this.artist = artist
    },
)

internal fun vorbisFile(tag: VorbisCommentTag): ByteArray {
    val head = ByteArray(30)
    head[0] = 1
    "vorbis".encodeToByteArray().copyInto(head, 1)
    writeLeInt(head, 7, 0)
    head[11] = 2
    writeLeInt(head, 12, 44_100)
    writeLeInt(head, 16, 0)
    writeLeInt(head, 20, 128_000)
    writeLeInt(head, 24, 0)
    head[28] = 0
    head[29] = 1

    val comment = byteArrayOf(3) + "vorbis".encodeToByteArray() + tag.encode()
    val setup = byteArrayOf(5) + "vorbis".encodeToByteArray() + SETUP_MARKER.encodeToByteArray()

    return oggPage(head, sequence = 0, flags = BOS_FLAG, granule = 0) +
        oggPage(comment, sequence = 1, flags = 0, granule = 0) +
        oggPage(setup, sequence = 2, flags = 0, granule = 0) +
        oggPage(AUDIO_MARKER.encodeToByteArray(), sequence = 3, flags = 0, granule = 44_100)
}

private fun oggPage(payload: ByteArray, sequence: Int, flags: Int, granule: Long): ByteArray {
    val segments = ArrayList<Int>()
    var remaining = payload.size
    while (remaining >= 255) {
        segments += 255
        remaining -= 255
    }
    segments += remaining

    val header = ByteArray(27 + segments.size)
    "OggS".encodeToByteArray().copyInto(header)
    header[4] = 0
    header[5] = flags.toByte()
    writeLeLong(header, 6, granule)
    writeLeInt(header, 14, SERIAL)
    writeLeInt(header, 18, sequence)
    writeLeInt(header, 22, 0)
    header[26] = segments.size.toByte()
    segments.forEachIndexed { index, size -> header[27 + index] = size.toByte() }

    val page = header + payload
    writeLeInt(page, 22, OggPages.computeCrc(page, 0, page.size))
    return page
}

private fun ByteArray.containsAscii(value: String): Boolean =
    String(this, Charsets.ISO_8859_1).contains(value)

private fun writeLeInt(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = value.toByte()
    bytes[offset + 1] = (value ushr 8).toByte()
    bytes[offset + 2] = (value ushr 16).toByte()
    bytes[offset + 3] = (value ushr 24).toByte()
}

private fun writeLeLong(bytes: ByteArray, offset: Int, value: Long) {
    for (index in 0 until 8) {
        bytes[offset + index] = (value ushr (index * 8)).toByte()
    }
}

private const val BOS_FLAG = 0x02
private const val SERIAL = 0x4321
private const val SETUP_MARKER = "SETUP-DATA"
private const val AUDIO_MARKER = "AUDIO-PAYLOAD-MARKER"
