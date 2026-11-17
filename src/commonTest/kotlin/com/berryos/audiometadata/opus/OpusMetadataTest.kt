package com.berryos.audiometadata.opus

import com.berryos.audiometadata.AudioFormat
import com.berryos.audiometadata.encodeBase64
import com.berryos.audiometadata.flacPicture
import com.berryos.audiometadata.io.ByteArraySource
import com.berryos.audiometadata.ogg.OggPage
import com.berryos.audiometadata.ogg.OggPages
import com.berryos.audiometadata.vorbis.VorbisCommentTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpusMetadataTest {
    @Test
    fun readsTagsAndAudioInfo() {
        val metadata = assertNotNull(OpusMetadataReader.read(sourceWith(opusFile("Song", "Artist"))))
        val audio = metadata.toAudioMetadata()

        assertEquals(AudioFormat.OPUS, audio.format)
        assertEquals("Song", audio.title)
        assertEquals(listOf("Artist"), audio.artists)
        assertEquals(48_000, audio.audioInfo?.sampleRateHz)
        assertEquals(2, audio.audioInfo?.channels)
        assertEquals(93L, audio.audioInfo?.durationMs)
    }

    @Test
    fun writesTagsAndRenumbersPages() {
        val source = sourceWith(opusFile("Song", "Artist"))
        val metadata = assertNotNull(OpusMetadataReader.read(source))

        metadata.tag.title = "A".repeat(200)
        OpusMetadataWriter.write(source, metadata)

        val reread = assertNotNull(OpusMetadataReader.read(source))
        assertEquals("A".repeat(200), reread.toAudioMetadata().title)
        assertEquals("Artist", reread.toAudioMetadata().artists.first())

        val sequences = pageSequences(source)
        assertEquals(sequences.indices.toList(), sequences)
        assertTrue(source.toByteArray().containsAscii(AUDIO_MARKER))
        assertEquals(4800L, lastGranule(source))
    }

    @Test
    fun paginatesLargeTags() {
        val source = sourceWith(opusFile("Song", "Artist"))
        val metadata = assertNotNull(OpusMetadataReader.read(source))

        metadata.tag.title = "B".repeat(70_000)
        OpusMetadataWriter.write(source, metadata)

        val reread = assertNotNull(OpusMetadataReader.read(source))
        assertEquals("B".repeat(70_000), reread.toAudioMetadata().title)
        val sequences = pageSequences(source)
        assertTrue(sequences.size >= 4)
        assertEquals(sequences.indices.toList(), sequences)
        assertTrue(source.toByteArray().containsAscii(AUDIO_MARKER))
    }

    @Test
    fun readsEmbeddedPicture() {
        val image = "PNG-DATA".encodeToByteArray()
        val tag = VorbisCommentTag.create().apply {
            title = "Song"
            set("METADATA_BLOCK_PICTURE", encodeBase64(flacPicture(image)))
        }
        val metadata = assertNotNull(OpusMetadataReader.read(sourceWith(opusFile(tag))))

        assertEquals(1, metadata.artworks.size)
        val artwork = metadata.artworks.first()
        kotlin.test.assertContentEquals(image, artwork.data)
        assertEquals("image/png", artwork.mimeType)
    }

    private fun sourceWith(bytes: ByteArray): ByteArraySource =
        ByteArraySource().also { it.writeAt(0, bytes) }

    private fun pageSequences(source: ByteArraySource): List<Int> = walk(source).map(OggPage::sequenceNumber)

    private fun lastGranule(source: ByteArraySource): Long =
        walk(source).lastOrNull { it.granulePosition > 0 }?.granulePosition ?: 0

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

internal fun opusFile(title: String, artist: String): ByteArray = opusFile(
    VorbisCommentTag.create().apply {
        this.title = title
        this.artist = artist
    },
)

internal fun opusFile(tag: VorbisCommentTag): ByteArray {
    val head = ByteArray(19)
    "OpusHead".encodeToByteArray().copyInto(head)
    head[8] = 1
    head[9] = 2
    writeLeShort(head, 10, 312)
    writeLeInt(head, 12, 48_000)
    writeLeShort(head, 16, 0)
    head[18] = 0

    val tags = "OpusTags".encodeToByteArray() + tag.encode()

    return oggPage(head, sequence = 0, flags = BOS_FLAG, granule = 0) +
        oggPage(tags, sequence = 1, flags = 0, granule = 0) +
        oggPage(AUDIO_MARKER.encodeToByteArray(), sequence = 2, flags = 0, granule = 4800)
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

private fun writeLeShort(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = value.toByte()
    bytes[offset + 1] = (value ushr 8).toByte()
}

private fun writeLeLong(bytes: ByteArray, offset: Int, value: Long) {
    for (index in 0 until 8) {
        bytes[offset + index] = (value ushr (index * 8)).toByte()
    }
}

private const val BOS_FLAG = 0x02
private const val SERIAL = 0x1234
private const val AUDIO_MARKER = "AUDIO-PAYLOAD-MARKER"
