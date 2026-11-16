package com.berryos.audiometadata.id3

import com.berryos.audiometadata.io.FileChannelSource
import com.berryos.audiometadata.io.use
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.id3.AbstractID3v2Frame
import org.jaudiotagger.tag.id3.framebody.AbstractFrameBodyTextInfo
import org.jaudiotagger.tag.id3.framebody.FrameBodyCOMM
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
        FileChannelSource(file, writable = false).use { source ->
            val ours = assertNotNull(Id3v2Reader.read(source), "our reader did not parse $name")
            val upstream = assertNotNull(AudioFileIO.read(file).tag, "upstream did not parse $name")

            assertEquals(upstreamTexts(upstream, FieldKey.TITLE).firstOrNull(), ours.title)
            assertEquals(upstreamTexts(upstream, FieldKey.ARTIST), ours.artists)
            assertEquals(upstreamTexts(upstream, FieldKey.ALBUM).firstOrNull(), ours.album)
            assertEquals(upstreamTexts(upstream, FieldKey.ALBUM_ARTIST).firstOrNull(), ours.albumArtist)
            assertEquals(positiveInt(upstream.getFirst(FieldKey.TRACK)), ours.trackNumber)
            assertEquals(positiveInt(upstream.getFirst(FieldKey.TRACK_TOTAL)), ours.trackTotal)
            assertEquals(positiveInt(upstream.getFirst(FieldKey.DISC_NO)), ours.discNumber)
            assertEquals(positiveInt(upstream.getFirst(FieldKey.DISC_TOTAL)), ours.discTotal)
            assertEquals(upstreamTexts(upstream, FieldKey.YEAR).firstOrNull(), ours.year)
            assertEquals(upstreamTexts(upstream, FieldKey.COMPOSER), ours.composers)
            assertEquals(upstreamTexts(upstream, FieldKey.COMMENT), ours.comments)
        }
    }

    private fun upstreamTexts(tag: Tag, key: FieldKey): List<String> = tag.getFields(key)
        .flatMap { field ->
            when (val body = (field as? AbstractID3v2Frame)?.body) {
                is AbstractFrameBodyTextInfo -> body.values
                is FrameBodyCOMM -> listOf(body.text)
                else -> emptyList()
            }
        }
        .filter(String::isNotEmpty)

    private fun positiveInt(value: String?): Int? = value?.toIntOrNull()?.takeIf { it > 0 }

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
