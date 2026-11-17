package com.berryos.audiometadata.id3

import com.berryos.audiometadata.Artwork
import com.berryos.audiometadata.Id3Version
import com.berryos.audiometadata.ReplayGain
import com.berryos.audiometadata.io.ByteArraySource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class Id3v2WriterTest {
    @Test
    fun editsInPlaceWhenTheTagFits() {
        val source = sourceWith(buildTag(3, frames = listOf(textFrame(3, "TIT2", 0, "Song")), padding = 64))
        val tag = read(source)
        val audioStart = HEADER_SIZE + tag.declaredSize.toLong()
        val sizeBefore = source.size

        tag.title = "New"
        Id3v2Writer.write(source, tag, audioStart, tag.declaredSize)

        assertEquals(sizeBefore, source.size)
        val reread = read(source)
        assertEquals("New", reread.title)
        assertContentEquals(AUDIO, tail(source, HEADER_SIZE + reread.declaredSize))
    }

    @Test
    fun shiftsAudioWhenTheTagGrows() {
        val source = sourceWith(buildTag(3, frames = listOf(textFrame(3, "TIT2", 0, "S"))))
        val tag = read(source)

        tag.title = "A much longer title than the original tag can hold"
        Id3v2Writer.write(source, tag, HEADER_SIZE + tag.declaredSize.toLong(), tag.declaredSize)

        val reread = read(source)
        assertEquals("A much longer title than the original tag can hold", reread.title)
        assertContentEquals(AUDIO, tail(source, HEADER_SIZE + reread.declaredSize))
    }

    @Test
    fun createsTagForFileWithoutOne() {
        val source = sourceWith(null)
        val tag = Id3v2Tag.create(Id3Version.V23)
        tag.title = "Added"

        Id3v2Writer.write(source, tag, audioStart = 0, originalTagSize = 0)

        val reread = read(source)
        assertEquals("Added", reread.title)
        assertContentEquals(AUDIO, tail(source, HEADER_SIZE + reread.declaredSize))
    }

    @Test
    fun writesMultiValuesNumbersAndYear() {
        val source = sourceWith(buildTag(4, frames = emptyList()))
        val tag = read(source)

        tag.artists = listOf("First", "Second")
        tag.genres = listOf("Rock", "Pop")
        tag.trackNumber = 3
        tag.trackTotal = 12
        tag.discNumber = 1
        tag.discTotal = 2
        tag.year = "2001-02-03"
        Id3v2Writer.write(source, tag, HEADER_SIZE + tag.declaredSize.toLong(), tag.declaredSize)

        val reread = read(source)
        assertEquals(listOf("First", "Second"), reread.artists)
        assertEquals(listOf("Rock", "Pop"), reread.genres)
        assertEquals(3, reread.trackNumber)
        assertEquals(12, reread.trackTotal)
        assertEquals(1, reread.discNumber)
        assertEquals(2, reread.discTotal)
        assertEquals("2001-02-03", reread.year)
    }

    @Test
    fun writesArtworkAndReplayGain() {
        val source = sourceWith(buildTag(3, frames = emptyList()))
        val tag = read(source)
        val image = "PNG-DATA".encodeToByteArray()

        tag.setArtworks(listOf(Artwork(image, "image/png", 3, "cover")))
        tag.replayGain = ReplayGain(trackGain = "-6.00 dB", albumPeak = "0.95")
        Id3v2Writer.write(source, tag, HEADER_SIZE + tag.declaredSize.toLong(), tag.declaredSize)

        val reread = read(source)
        val artwork = assertNotNull(reread.artworks.firstOrNull())
        assertContentEquals(image, artwork.data)
        assertEquals("image/png", artwork.mimeType)
        assertEquals("cover", artwork.description)
        assertEquals("-6.00 dB", reread.replayGain?.trackGain)
        assertEquals("0.95", reread.replayGain?.albumPeak)
    }

    @Test
    fun removesFramesWhenCleared() {
        val source = sourceWith(buildTag(3, frames = listOf(textFrame(3, "TIT2", 0, "Song"))))
        val tag = read(source)

        tag.title = null
        Id3v2Writer.write(source, tag, HEADER_SIZE + tag.declaredSize.toLong(), tag.declaredSize)

        assertNull(read(source).title)
    }

    @Test
    fun keepsUnknownFrames() {
        val payload = byteArrayOf(9, 8, 7)
        val source = sourceWith(
            buildTag(3, frames = listOf(frame(3, "PRIV", payload), textFrame(3, "TIT2", 0, "Song"))),
        )
        val tag = read(source)

        tag.title = "Edited"
        Id3v2Writer.write(source, tag, HEADER_SIZE + tag.declaredSize.toLong(), tag.declaredSize)

        val reread = read(source)
        assertEquals("Edited", reread.title)
        assertContentEquals(payload, assertNotNull(reread.framesOf("PRIV").firstOrNull()).content)
    }

    @Test
    fun writesV22Tags() {
        val source = sourceWith(null)
        val tag = Id3v2Tag.create(Id3Version.V22)
        tag.title = "Song"

        Id3v2Writer.write(source, tag, audioStart = 0, originalTagSize = 0)

        val reread = read(source)
        assertEquals(Id3Version.V22, reread.version)
        assertEquals("Song", reread.title)
        assertContentEquals(AUDIO, tail(source, HEADER_SIZE + reread.declaredSize))
    }

    @Test
    fun dropsExtendedHeaderWhenRewriting() {
        val extendedHeader = contentInt(6) + byteArrayOf(0, 0) + contentInt(0)
        val source = sourceWith(
            buildTag(3, flags = 0x40, bodyPrefix = extendedHeader, frames = listOf(textFrame(3, "TIT2", 0, "Song"))),
        )
        val tag = read(source)

        Id3v2Writer.write(source, tag, HEADER_SIZE + tag.declaredSize.toLong(), tag.declaredSize)

        val reread = read(source)
        assertEquals("Song", reread.title)
        assertEquals(0, reread.extendedHeaderSize)
        assertContentEquals(AUDIO, tail(source, HEADER_SIZE + reread.declaredSize))
    }

    @Test
    fun keepsFramesWithUnsupportedFlags() {
        val compressedContent = contentInt(3) + byteArrayOf(0, 1, 2)
        val source = sourceWith(buildTag(3, frames = listOf(frame(3, "TIT2", compressedContent, format = 0x80))))
        val tag = read(source)
        val original = assertNotNull(tag.framesOf("TIT2").firstOrNull()).content

        Id3v2Writer.write(source, tag, HEADER_SIZE + tag.declaredSize.toLong(), tag.declaredSize)

        val reread = read(source)
        assertContentEquals(original, assertNotNull(reread.framesOf("TIT2").firstOrNull()).content)
    }

    @Test
    fun rewritesUnsynchronisedV23Tags() {
        val body = unsynchronise(textFrame(3, "TIT2", 0, "ÿ"))
        val header = ByteArray(HEADER_SIZE)
        "ID3".encodeToByteArray().copyInto(header)
        header[3] = 3
        header[5] = 0x80.toByte()
        writeSyncSafe(header, 6, body.size)
        val source = sourceWith(header + body)
        val tag = read(source)

        Id3v2Writer.write(source, tag, HEADER_SIZE + tag.declaredSize.toLong(), tag.declaredSize)

        val reread = read(source)
        assertEquals("ÿ", reread.title)
        assertEquals(false, reread.unsynchronised)
        assertContentEquals(AUDIO, tail(source, HEADER_SIZE + reread.declaredSize))
    }

    private fun read(source: ByteArraySource): Id3v2Tag = assertNotNull(Id3v2Reader.read(source))

    private fun sourceWith(tagBytes: ByteArray?): ByteArraySource {
        val bytes = (tagBytes ?: ByteArray(0)) + AUDIO
        return ByteArraySource().also { it.writeAt(0, bytes) }
    }

    private fun tail(source: ByteArraySource, audioStart: Int): ByteArray {
        val bytes = source.toByteArray()
        return bytes.copyOfRange(audioStart, bytes.size)
    }
}

private const val HEADER_SIZE = 10
private val AUDIO = ByteArray(512) { index -> (index * 13 % 251).toByte() }
