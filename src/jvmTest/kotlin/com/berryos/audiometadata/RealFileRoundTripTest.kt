package com.berryos.audiometadata

import com.berryos.audiometadata.aiff.AiffMetadata
import com.berryos.audiometadata.aiff.AiffMetadataReader
import com.berryos.audiometadata.aiff.AiffMetadataWriter
import com.berryos.audiometadata.flac.FlacMetadataReader
import com.berryos.audiometadata.flac.FlacMetadataWriter
import com.berryos.audiometadata.io.FileChannelSource
import com.berryos.audiometadata.io.RandomAccessSource
import com.berryos.audiometadata.mp3.Mp3MetadataReader
import com.berryos.audiometadata.mp3.Mp3MetadataWriter
import com.berryos.audiometadata.mp4.Mp4MetadataReader
import com.berryos.audiometadata.mp4.Mp4MetadataWriter
import com.berryos.audiometadata.mp4.readChunkOffsets
import com.berryos.audiometadata.ogg.OggPages
import com.berryos.audiometadata.ogg.findOggPackets
import com.berryos.audiometadata.ogg.walkOggPages
import com.berryos.audiometadata.vorbis.OggVorbisMetadataReader
import com.berryos.audiometadata.vorbis.OggVorbisMetadataWriter
import com.berryos.audiometadata.vorbis.setArtworks
import com.berryos.audiometadata.wav.WavMetadata
import com.berryos.audiometadata.wav.WavMetadataReader
import com.berryos.audiometadata.wav.WavMetadataWriter
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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

    @Test
    fun mp4_test() = mp4ReadOnly("test.m4a")

    @Test
    fun mp4_test2() = mp4ReadOnly("test2.m4a")

    @Test
    fun mp4_issue198() = mp4ReadOnly("issue-198.m4a")

    @Test
    fun writesMp4Tags() = withFixture("test.m4a") { copy -> mp4RoundTrip(copy) }

    @Test
    fun writesMp4TagsTest2() = withFixture("test2.m4a") { copy -> mp4RoundTrip(copy) }

    private fun mp4RoundTrip(copy: File) {
        val source = FileChannelSource(copy)
        try {
            val before = assertNotNull(Mp4MetadataReader.read(source))
            val offsetsBefore = readChunkOffsets(source)
            val samples = offsetsBefore.take(20).map { offset -> readBytesAt(source, offset, 32) }

            val image = "MP4-ARTWORK".encodeToByteArray()
            before.tags.title = MARKER
            before.tags.artists = listOf(MARKER)
            before.tags.artworks = listOf(Artwork(image, "image/png", 3))
            Mp4MetadataWriter.write(source, before)

            val after = assertNotNull(Mp4MetadataReader.read(source))
            assertEquals(MARKER, after.tags.title)
            assertEquals(listOf(MARKER), after.tags.artists)
            val artworks = assertNotNull(after.artworks)
            assertEquals(1, artworks.size)
            assertContentEquals(image, artworks.first().data)
            val offsetsAfter = readChunkOffsets(source)
            assertEquals(offsetsBefore.size, offsetsAfter.size)
            samples.forEachIndexed { index, bytes ->
                assertContentEquals(bytes, readBytesAt(source, offsetsAfter[index], bytes.size))
            }
        } finally {
            source.close()
        }
    }

    private fun readBytesAt(source: RandomAccessSource, offset: Long, count: Int): ByteArray {
        val length = minOf(count.toLong(), source.size - offset).toInt()
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val chunk = source.readAt(offset + read, bytes, read, length - read)
            if (chunk <= 0) break
            read += chunk
        }
        return bytes
    }

    @Test
    fun wav_test() = wavRoundTrip("test.wav")

    @Test
    fun wav_bug153() = wavRoundTrip("bug153.wav")

    @Test
    fun aiff_test119() = aiffRoundTrip("test119.aif")

    @Test
    fun aiff_test120() = aiffRoundTrip("test120.aif")

    private fun aiffRoundTrip(name: String) = withFixture(name) { copy ->
        val source = FileChannelSource(copy)
        try {
            val before = assertNotNull(AiffMetadataReader.read(source))
            val data = aiffChunkPayload(source, before, "SSND")

            before.tag.title = MARKER
            AiffMetadataWriter.write(source, before)

            val after = assertNotNull(AiffMetadataReader.read(source))
            assertEquals(MARKER, after.toAudioMetadata().title)
            assertNotNull(after.audioInfo)
            assertContentEquals(data, aiffChunkPayload(source, after, "SSND"))
            assertEquals(AudioFormat.AIFF, assertNotNull(AudioMetadataReader.read(source)).format)
        } finally {
            source.close()
        }
    }

    private fun aiffChunkPayload(source: RandomAccessSource, metadata: AiffMetadata, id: String): ByteArray {
        val chunk = metadata.chunks.first { it.id == id }
        val payload = ByteArray(chunk.size.toInt())
        var read = 0
        while (read < payload.size) {
            read += source.readAt(chunk.payloadOffset + read, payload, read, payload.size - read)
        }
        return payload
    }

    private fun wavRoundTrip(name: String) = withFixture(name) { copy ->
        val source = FileChannelSource(copy)
        try {
            val before = assertNotNull(WavMetadataReader.read(source))
            val data = chunkPayload(source, before, "data")

            before.tag.title = MARKER
            WavMetadataWriter.write(source, before)

            val after = assertNotNull(WavMetadataReader.read(source))
            assertEquals(MARKER, after.toAudioMetadata().title)
            assertContentEquals(data, chunkPayload(source, after, "data"))
            assertEquals(AudioFormat.WAV, assertNotNull(AudioMetadataReader.read(source)).format)
        } finally {
            source.close()
        }
    }

    private fun chunkPayload(source: RandomAccessSource, metadata: WavMetadata, id: String): ByteArray {
        val chunk = metadata.chunks.first { it.id == id }
        val payload = ByteArray(chunk.size.toInt())
        var read = 0
        while (read < payload.size) {
            read += source.readAt(chunk.payloadOffset + read, payload, read, payload.size - read)
        }
        return payload
    }

    @Test
    fun artworkFromMp4() = withReadOnly("test.m4a") { source ->
        assertEquals(1, AudioArtworkReader.read(source).size)
    }

    @Test
    fun artworkFromMultiPictureMp4() = withReadOnly("test2.m4a") { source ->
        assertEquals(3, AudioArtworkReader.read(source).size)
    }

    @Test
    fun artworkFromFlac() = withReadOnly("test.flac") { source ->
        assertTrue(AudioArtworkReader.read(source).isNotEmpty())
    }

    @Test
    fun artworkFromOggVorbis() = withReadOnly("test3.ogg") { source ->
        assertTrue(AudioArtworkReader.read(source).isNotEmpty())
    }

    @Test
    fun writesArtworkToMp3() = withFixture("test23.mp3") { copy ->
        val source = FileChannelSource(copy)
        try {
            val before = assertNotNull(Mp3MetadataReader.read(source))
            val tag = assertNotNull(before.tag)
            val tail = readTail(source, before.audioStartOffset)
            val image = "MP3-ARTWORK".encodeToByteArray()

            tag.setArtworks(listOf(Artwork(image, "image/png", 3)))
            Mp3MetadataWriter.write(source, before)

            val after = assertNotNull(Mp3MetadataReader.read(source))
            assertEquals(1, after.tag!!.artworks.size)
            assertContentEquals(image, after.tag.artworks.first().data)
            assertContentEquals(tail, readTail(source, after.audioStartOffset))
        } finally {
            source.close()
        }
    }

    @Test
    fun writesArtworkToFlac() = withFixture("test.flac") { copy ->
        val source = FileChannelSource(copy)
        try {
            val before = assertNotNull(FlacMetadataReader.read(source))
            val tail = readTail(source, before.audioStartOffset)
            val image = "FLAC-ARTWORK".encodeToByteArray()

            FlacMetadataWriter.write(source, before, artworks = listOf(Artwork(image, "image/png", 3)))

            val after = assertNotNull(FlacMetadataReader.read(source))
            val artworks = assertNotNull(after.artworks)
            assertEquals(1, artworks.size)
            assertContentEquals(image, artworks.first().data)
            assertContentEquals(tail, readTail(source, after.audioStartOffset))
        } finally {
            source.close()
        }
    }

    @Test
    fun writesArtworkToOggVorbis() = withFixture("test3.ogg") { copy ->
        val source = FileChannelSource(copy)
        try {
            val before = assertNotNull(OggVorbisMetadataReader.read(source))
            val audio = audioPages(source)
            val image = "OGG-ARTWORK".encodeToByteArray()

            before.tag.setArtworks(listOf(Artwork(image, "image/png", 3)))
            OggVorbisMetadataWriter.write(source, before)

            val after = assertNotNull(OggVorbisMetadataReader.read(source))
            assertEquals(1, after.artworks.size)
            assertContentEquals(image, after.artworks.first().data)
            val afterAudio = audioPages(source)
            assertEquals(audio.size, afterAudio.size)
            audio.indices.forEach { index ->
                assertContentEquals(audio[index].payload, afterAudio[index].payload)
                assertEquals(audio[index].granule, afterAudio[index].granule)
            }
        } finally {
            source.close()
        }
    }

    private fun withReadOnly(name: String, block: (RandomAccessSource) -> Unit) = withFixture(name) { copy ->
        val source = FileChannelSource(copy, writable = false)
        try {
            block(source)
        } finally {
            source.close()
        }
    }

    private fun mp4ReadOnly(name: String) = withFixture(name) { copy ->
        val source = FileChannelSource(copy, writable = false)
        try {
            val metadata = assertNotNull(Mp4MetadataReader.read(source))
            assertNotNull(metadata.audioInfo)
            assertEquals(AudioFormat.MP4, metadata.toAudioMetadata().format)
            assertEquals(AudioFormat.MP4, assertNotNull(AudioMetadataReader.read(source)).format)
        } finally {
            source.close()
        }
    }

    private fun mp3RoundTrip(name: String) = withFixture(name) { copy ->
        val source = FileChannelSource(copy)
        try {
            val before = assertNotNull(Mp3MetadataReader.read(source))
            val tag = assertNotNull(before.tag)
            val tailLength = (source.size - before.audioStartOffset - if (before.id3v1 != null) 128L else 0L).toInt()
            val tail = readBytesAt(source, before.audioStartOffset, tailLength)

            tag.title = MARKER
            Mp3MetadataWriter.write(source, before)

            val after = assertNotNull(Mp3MetadataReader.read(source))
            assertEquals(MARKER, after.toAudioMetadata().title)
            assertNotNull(after.audioInfo)
            assertContentEquals(tail, readBytesAt(source, after.audioStartOffset, tailLength))
            assertEquals(AudioFormat.MP3, assertNotNull(AudioMetadataReader.read(source)).format)
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
            assertEquals(AudioFormat.FLAC, assertNotNull(AudioMetadataReader.read(source)).format)
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
            assertEquals(AudioFormat.OGG, assertNotNull(AudioMetadataReader.read(source)).format)
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
