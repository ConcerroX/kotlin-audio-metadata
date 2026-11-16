package com.berryos.audiometadata.id3

import com.berryos.audiometadata.Artwork
import com.berryos.audiometadata.ReplayGain
import com.berryos.audiometadata.io.FileChannelSource
import com.berryos.audiometadata.io.RandomAccessSource
import com.berryos.audiometadata.io.use
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Rewrites real files with the spec-driven writer and verifies the result with both readers. */
class Id3v2WriterRealFileTest {
    @Test
    fun rewritesV24File() = rewrite("test23.mp3")

    @Test
    fun rewritesV23File() = rewrite("test302.mp3")

    @Test
    fun rewritesPaddedFile() = rewrite("testV1Cbr128ID3v2pad.mp3")

    private fun rewrite(name: String) = withFixture(name) { file ->
        FileChannelSource(file).use { source ->
            val tag = assertNotNull(Id3v2Reader.read(source))
            val audioStart = HEADER_SIZE.toLong() + tag.declaredSize + if (tag.hasFooter) FOOTER_SIZE.toLong() else 0L
            val audioBefore = readRange(source, audioStart, (source.size - audioStart).toInt())

            tag.title = MARKER
            tag.artists = listOf("First", "Second")
            tag.replayGain = ReplayGain(trackGain = "-6.00 dB", trackPeak = "0.98")
            tag.setArtworks(listOf(Artwork("ARTWORK".encodeToByteArray(), "image/png", 3)))
            Id3v2Writer.write(source, tag, audioStart, tag.declaredSize)

            val reread = assertNotNull(Id3v2Reader.read(source))
            assertEquals(MARKER, reread.title)
            assertEquals(listOf("First", "Second"), reread.artists)
            assertEquals("-6.00 dB", reread.replayGain?.trackGain)
            assertEquals("0.98", reread.replayGain?.trackPeak)
            assertEquals(1, reread.artworks.size)
            val newAudioStart = HEADER_SIZE.toLong() + reread.declaredSize +
                if (reread.hasFooter) FOOTER_SIZE.toLong() else 0L
            assertContentEquals(audioBefore, readRange(source, newAudioStart, audioBefore.size))
        }

        val upstream = assertNotNull(AudioFileIO.read(file).tag)
        assertEquals(MARKER, upstream.getFirst(FieldKey.TITLE))
        assertEquals("First", upstream.getFirst(FieldKey.ARTIST))
    }

    private fun readRange(source: RandomAccessSource, offset: Long, count: Int): ByteArray {
        val bytes = ByteArray(count)
        var read = 0
        while (read < count) {
            read += source.readAt(offset + read, bytes, read, count - read)
        }
        return bytes
    }

    private fun withFixture(name: String, block: (File) -> Unit) {
        val fixture = File(TESTDATA, name)
        assumeTrue("$name is not available", fixture.isFile)
        val directory = Files.createTempDirectory("id3-write").toFile()
        try {
            val copy = File(directory, name)
            fixture.copyTo(copy, overwrite = true)
            block(copy)
        } finally {
            directory.deleteRecursively()
        }
    }

    private companion object {
        val TESTDATA = File("testdata")
    }
}

private const val HEADER_SIZE = 10
private const val FOOTER_SIZE = 20
private const val MARKER = "kotlin-audio-metadata"
