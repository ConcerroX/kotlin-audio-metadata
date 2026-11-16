package com.berryos.audiometadata.flac

import com.berryos.audiometadata.AudioFormat
import com.berryos.audiometadata.flacPicture
import com.berryos.audiometadata.io.ByteArraySource
import com.berryos.audiometadata.vorbis.VorbisCommentTag
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlacMetadataTest {
    @Test
    fun readsFieldsAndStreamInfo() {
        val tag = VorbisCommentTag.create().apply {
            title = "Song"
            add("ARTIST", "Artist")
            add("ARTIST", "Guest")
            album = "Album"
            set("TRACKNUMBER", "3/12")
            date = "1999"
            genre = "Rock"
            set("REPLAYGAIN_TRACK_GAIN", "-6.53 dB")
        }
        val metadata = assertNotNull(FlacMetadataReader.read(sourceWith(buildFlac(tag), AUDIO)))
        val audio = metadata.toAudioMetadata()

        assertEquals(AudioFormat.Flac, audio.format)
        assertEquals("Song", audio.title)
        assertEquals(listOf("Artist", "Guest"), audio.artists)
        assertEquals("Album", audio.album)
        assertEquals(3, audio.trackNumber)
        assertEquals(12, audio.trackTotal)
        assertEquals("1999", audio.year)
        assertEquals(listOf("Rock"), audio.genres)
        assertEquals("-6.53 dB", audio.replayGain?.trackGain)
        assertEquals(44100, audio.audioInfo?.sampleRateHz)
        assertEquals(2, audio.audioInfo?.channels)
        assertEquals(16, audio.audioInfo?.bitsPerSample)
        assertEquals(10_000L, audio.audioInfo?.durationMs)
    }

    @Test
    fun keepsAudioAndOtherBlocksWhenTagFits() {
        val tag = VorbisCommentTag.create().apply { title = "Song" }
        val picture = "PICTURE-DATA".encodeToByteArray()
        val source = sourceWith(buildFlac(tag, extra = listOf(TYPE_PICTURE to picture), padding = 128), AUDIO)
        val before = source.toByteArray()
        val metadata = assertNotNull(FlacMetadataReader.read(source))

        metadata.tag.title = "New title"
        FlacMetadataWriter.write(source, metadata)

        val after = source.toByteArray()
        assertEquals(before.size, after.size)
        assertTrue(after.containsAscii("PICTURE-DATA"))
        val reread = assertNotNull(FlacMetadataReader.read(source))
        assertEquals("New title", reread.toAudioMetadata().title)
        assertContentEquals(AUDIO, after.copyOfRange(reread.audioStartOffset.toInt(), after.size))
    }

    @Test
    fun shiftsAudioWhenTagGrows() {
        val tag = VorbisCommentTag.create().apply { title = "S" }
        val source = sourceWith(buildFlac(tag, padding = 0), AUDIO)
        val metadata = assertNotNull(FlacMetadataReader.read(source))
        val longer = "A much longer title than the original tag space can hold"

        metadata.tag.title = longer
        FlacMetadataWriter.write(source, metadata)

        val reread = assertNotNull(FlacMetadataReader.read(source))
        assertEquals(longer, reread.toAudioMetadata().title)
        val bytes = source.toByteArray()
        assertContentEquals(AUDIO, bytes.copyOfRange(reread.audioStartOffset.toInt(), bytes.size))
    }

    @Test
    fun addsCommentBlockWhenMissing() {
        val source = sourceWith(buildFlac(tag = null, padding = 64), AUDIO)
        val metadata = assertNotNull(FlacMetadataReader.read(source))
        assertTrue(metadata.tag.comments.isEmpty())

        metadata.tag.title = "Added"
        FlacMetadataWriter.write(source, metadata)

        val reread = assertNotNull(FlacMetadataReader.read(source))
        assertEquals("Added", reread.toAudioMetadata().title)
        val bytes = source.toByteArray()
        assertContentEquals(AUDIO, bytes.copyOfRange(reread.audioStartOffset.toInt(), bytes.size))
    }

    @Test
    fun returnsNullForNonFlacData() {
        assertNull(FlacMetadataReader.read(sourceWith(ByteArray(64), AUDIO)))
    }

    @Test
    fun readsEmbeddedPicture() {
        val image = "PNG-DATA".encodeToByteArray()
        val tag = VorbisCommentTag.create().apply { title = "Song" }
        val picture = flacPicture(image)
        val metadata = assertNotNull(
            FlacMetadataReader.read(sourceWith(buildFlac(tag, extra = listOf(TYPE_PICTURE to picture)), AUDIO)),
        )

        assertEquals(1, metadata.artworks.size)
        val artwork = metadata.artworks.first()
        assertContentEquals(image, artwork.data)
        assertEquals("image/png", artwork.mimeType)
        assertEquals(3, artwork.pictureType)
        assertEquals(100, artwork.width)
        assertEquals(100, artwork.height)
        assertEquals(24, artwork.colorDepth)
    }

    @Test
    fun vorbisCommentsAreCaseInsensitive() {
        val tag = VorbisCommentTag.create()
        tag.set("Artist", "First")
        assertEquals("First", tag.value("ARTIST"))
        tag.set("ARTIST", "Second")
        assertEquals(listOf("Second"), tag.values("artist"))
        tag.add("artist", "Third")
        assertEquals(listOf("Second", "Third"), tag.values("ARTIST"))
    }

    private fun sourceWith(tag: ByteArray, audio: ByteArray): ByteArraySource =
        ByteArraySource().also { it.writeAt(0, tag + audio) }
}

private fun buildFlac(
    tag: VorbisCommentTag?,
    extra: List<Pair<Int, ByteArray>> = emptyList(),
    padding: Int = 64,
): ByteArray {
    val blocks = buildList {
        add(TYPE_STREAMINFO to streamInfo())
        if (tag != null) add(TYPE_VORBIS_COMMENT to tag.encode())
        addAll(extra)
        if (padding > 0) add(TYPE_PADDING to ByteArray(padding))
    }
    val chunks = ArrayList<ByteArray>()
    chunks += "fLaC".encodeToByteArray()
    blocks.forEachIndexed { index, (type, payload) ->
        val last = index == blocks.lastIndex
        chunks += byteArrayOf(
            ((if (last) 0x80 else 0) or type).toByte(),
            (payload.size ushr 16 and 0xFF).toByte(),
            (payload.size ushr 8 and 0xFF).toByte(),
            (payload.size and 0xFF).toByte(),
        )
        chunks += payload
    }
    return chunks.fold(ByteArray(0)) { accumulated, bytes -> accumulated + bytes }
}

private fun streamInfo(): ByteArray {
    val data = ByteArray(34)
    data[0] = 0x10
    data[1] = 0x00
    data[2] = 0x10
    data[3] = 0x00
    val sampleRate = 44100L
    val channelsMinusOne = 1L
    val bitsMinusOne = 15L
    val totalSamples = 441_000L
    val packed = (sampleRate shl 44) or (channelsMinusOne shl 41) or (bitsMinusOne shl 36) or totalSamples
    for (index in 0 until 8) {
        data[10 + index] = (packed ushr (56 - index * 8)).toByte()
    }
    return data
}

private fun ByteArray.containsAscii(value: String): Boolean =
    String(this, Charsets.ISO_8859_1).contains(value)

private const val TYPE_STREAMINFO = 0
private const val TYPE_PADDING = 1
private const val TYPE_VORBIS_COMMENT = 4
private const val TYPE_PICTURE = 6
private val AUDIO = ByteArray(1024) { (it * 7 % 251).toByte() }
