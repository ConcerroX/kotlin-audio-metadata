package com.berryos.audiometadata

import com.berryos.audiometadata.flac.FlacMetadataReader
import com.berryos.audiometadata.flac.FlacMetadataWriter
import com.berryos.audiometadata.io.FileChannelSource
import com.berryos.audiometadata.io.RandomAccessSource
import com.berryos.audiometadata.io.WritableRandomAccessSource
import com.berryos.audiometadata.mp3.Mp3MetadataReader
import com.berryos.audiometadata.mp3.Mp3MetadataWriter
import com.berryos.audiometadata.ogg.OggPages
import com.berryos.audiometadata.ogg.findOggPackets
import com.berryos.audiometadata.ogg.walkOggPages
import com.berryos.audiometadata.vorbis.OggVorbisMetadataReader
import com.berryos.audiometadata.vorbis.OggVorbisMetadataWriter
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Round-trips real files from the upstream testdata directory. They are not redistributed, so these
 * tests are skipped when the directory is absent (clean checkout).
 */
class RealFileRoundTripTest {
    @Test
    fun mp3_test23() = mp3RoundTrip("test23.mp3")

    @Test
    fun mp3_test302() = mp3RoundTrip("test302.mp3")

    @Test
    fun mp3_padded() = mp3RoundTrip("testV1Cbr128ID3v2pad.mp3")

    @Test
    fun flac_test() = flacRoundTrip("test.flac")

    @Test
    fun flac_test2() = flacRoundTrip("test2.flac")

    @Test
    fun ogg_vorbis_test() = vorbisRoundTrip("test.ogg")

    @Test
    fun ogg_vorbis_test3() = vorbisRoundTrip("test3.ogg")

    private fun mp3RoundTrip(name: String) = withFixture(name) { copy ->
        val source = FileChannelSource(copy)
        try {
            val before = assertNotNull(Mp3MetadataReader.read(source))
            val tag = assertNotNull(before.tag)
            val tail = readTail(source, before.audioStartOffset)

            tag.title = MARKER
            Mp3MetadataWriter.write(source, before)

            val after = assertNotNull(Mp3MetadataReader.read(source))
            assertEquals(MARKER, after.toAudioMetadata().title)
            assertNotNull(after.audioInfo)
            assertContentEquals(tail, readTail(source, after.audioStartOffset))
            assertEquals(AudioFormat.Mp3, assertNotNull(AudioMetadataReader.read(source)).format)
        } finally {
            source.close()
        }
    }

    private fun flacRoundTrip(name: String) = withFixture(name) { copy ->
        val source = FileChannelSource(copy)
        try {
            val before = assertNotNull(FlacMetadataReader.read(source))
            val tail = readTail(source, before.audioStartOffset)

            before.tag.title = MARKER
            FlacMetadataWriter.write(source, before)

            val after = assertNotNull(FlacMetadataReader.read(source))
            assertEquals(MARKER, after.toAudioMetadata().title)
            assertNotNull(after.toAudioMetadata().audioInfo)
            assertContentEquals(tail, readTail(source, after.audioStartOffset))
            assertEquals(AudioFormat.Flac, assertNotNull(AudioMetadataReader.read(source)).format)
        } finally {
            source.close()
        }
    }

    private fun vorbisRoundTrip(name: String) = withFixture(name) { copy ->
        val source = FileChannelSource(copy)
        try {
            val before = assertNotNull(OggVorbisMetadataReader.read(source))
            val audio = audioPages(source)

            before.tag.title = MARKER
            OggVorbisMetadataWriter.write(source, before)

            val after = assertNotNull(OggVorbisMetadataReader.read(source))
            assertEquals(MARKER, after.toAudioMetadata().title)
            assertNotNull(after.audioInfo)
            val afterAudio = audioPages(source)
            assertEquals(audio.size, afterAudio.size)
            audio.indices.forEach { index ->
                assertContentEquals(audio[index].payload, afterAudio[index].payload)
                assertEquals(audio[index].granule, afterAudio[index].granule)
            }
            assertEquals(AudioFormat.Ogg, assertNotNull(AudioMetadataReader.read(source)).format)
        } finally {
            source.close()
        }
    }

    private fun withFixture(name: String, block: (File) -> Unit) {
        val fixture = File(TESTDATA, name)
        assumeTrue("$name is not available", fixture.isFile)
        val directory = Files.createTempDirectory("real-file").toFile()
        try {
            val copy = File(directory, name)
            fixture.copyTo(copy, overwrite = true)
            block(copy)
        } finally {
            directory.deleteRecursively()
        }
    }
}

private class AudioPage(val payload: ByteArray, val granule: Long)

private fun audioPages(source: RandomAccessSource): List<AudioPage> {
    val pages = walkOggPages(source)
    // Skip the identification page and both header packets (comment and setup); everything after
    // them is audio, regardless of where the packet boundaries fall.
    val header = findOggPackets(source, pages, 1, 2) ?: return emptyList()
    return pages.drop(header.lastPageIndex + 1)
        .map { page -> AudioPage(OggPages.readPayload(source, page), page.granulePosition) }
}

private fun readTail(source: RandomAccessSource, offset: Long): ByteArray {
    val length = (source.size - offset).toInt()
    val bytes = ByteArray(length)
    var read = 0
    while (read < length) {
        val count = source.readAt(offset + read, bytes, read, length - read)
        check(count > 0) { "Unexpected end of file" }
        read += count
    }
    return bytes
}

private val TESTDATA = File("testdata")
private const val MARKER = "kotlin-audio-metadata"
