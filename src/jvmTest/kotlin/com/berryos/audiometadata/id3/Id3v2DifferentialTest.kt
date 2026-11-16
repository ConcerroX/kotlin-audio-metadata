package com.berryos.audiometadata.id3

import com.berryos.audiometadata.io.FileChannelSource
import com.berryos.audiometadata.io.use
import com.berryos.audiometadata.mp3.Mp3MetadataReader
import org.jaudiotagger.tag.FieldKey
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Compares the spec-driven reader with jaudiotagger on real files. jaudiotagger is used as a test
 * oracle only; its implementation is not consulted while writing the parser.
 */
class Id3v2DifferentialTest {
    @Test
    fun matchesUpstreamOnV24File() = compare("test23.mp3")

    @Test
    fun matchesUpstreamOnV23File() = compare("test302.mp3")

    @Test
    fun matchesUpstreamOnPaddedFile() = compare("testV1Cbr128ID3v2pad.mp3")

    private fun compare(name: String) = withFixture(name) { file ->
        FileChannelSource(file).use { source ->
            val ours = assertNotNull(Id3v2Reader.read(source), "our reader did not parse $name")
            val upstream = assertNotNull(Mp3MetadataReader.read(source)?.tag, "upstream did not parse $name")

            assertEquals(upstream.version, ours.version)
            assertEquals(upstream.title.ifEmpty { null }, ours.title)
            assertEquals(upstream.values(FieldKey.ARTIST), ours.artists)
            assertEquals(upstream.album.ifEmpty { null }, ours.album)
            assertEquals(upstream.albumArtist.ifEmpty { null }, ours.albumArtist)
            assertEquals(upstream.trackNumber.toIntOrNull(), ours.trackNumber)
            assertEquals(upstream.trackTotal.toIntOrNull(), ours.trackTotal)
            assertEquals(upstream.discNumber.toIntOrNull(), ours.discNumber)
            assertEquals(upstream.discTotal.toIntOrNull(), ours.discTotal)
            assertEquals(upstream.year.ifEmpty { null }, ours.year)
            assertEquals(upstream.genres(), ours.genres)
            assertEquals(upstream.values(FieldKey.COMPOSER), ours.composers)
            assertEquals(upstream.values(FieldKey.COMMENT), ours.comments)
            assertEquals(upstream.replayGain?.trackGain, ours.replayGain?.trackGain)
            assertEquals(upstream.replayGain?.trackPeak, ours.replayGain?.trackPeak)
            assertEquals(upstream.replayGain?.albumGain, ours.replayGain?.albumGain)
            assertEquals(upstream.replayGain?.albumPeak, ours.replayGain?.albumPeak)
            assertEquals(upstream.artworks.size, ours.artworks.size)
        }
    }

    private fun withFixture(name: String, block: (File) -> Unit) {
        val fixture = File(TESTDATA, name)
        assumeTrue("$name is not available", fixture.isFile)
        val directory = Files.createTempDirectory("id3-diff").toFile()
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
