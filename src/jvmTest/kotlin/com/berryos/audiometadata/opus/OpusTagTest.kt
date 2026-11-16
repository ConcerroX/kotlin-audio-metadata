package com.berryos.audiometadata.opus

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.audio.ogg.util.OggPage
import org.jaudiotagger.audio.ogg.util.OggPageHeader
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpusTagTest {
    @Test
    fun readsAndWritesOpusTags() {
        val directory = Files.createTempDirectory("opus-tags").toFile()
        try {
            val file = File(directory, "sample.opus")
            file.writeBytes(opusFile(title = "Song", artist = "Artist"))

            val audioFile = AudioFileIO.read(file)
            val tag = audioFile.getTagOrCreateAndSetDefault()
            assertEquals("Song", tag.getFirst(FieldKey.TITLE))
            assertEquals("Artist", tag.getFirst(FieldKey.ARTIST))

            tag.setField(FieldKey.TITLE, "New Title")
            audioFile.commit()

            val reread = AudioFileIO.read(file)
            assertEquals("New Title", reread.getTag().getFirst(FieldKey.TITLE))
            assertEquals("Artist", reread.getTag().getFirst(FieldKey.ARTIST))
            assertTrue(file.readBytes().containsAscii(AUDIO_MARKER))
        } finally {
            directory.deleteRecursively()
        }
    }
}

private fun opusFile(title: String, artist: String): ByteArray {
    val head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
    head.put("OpusHead".toByteArray(StandardCharsets.ISO_8859_1))
    head.put(1)
    head.put(2)
    head.putShort(312)
    head.putInt(48000)
    head.putShort(0)
    head.put(0)

    val vendor = "kotlin-audio-metadata".toByteArray(StandardCharsets.UTF_8)
    val comments = listOf("TITLE=$title", "ARTIST=$artist").map { it.toByteArray(StandardCharsets.UTF_8) }
    val commentsSize = 4 + vendor.size + 4 + comments.sumOf { 4 + it.size }
    val commentBody = ByteBuffer.allocate(commentsSize).order(ByteOrder.LITTLE_ENDIAN)
    commentBody.putInt(vendor.size)
    commentBody.put(vendor)
    commentBody.putInt(comments.size)
    comments.forEach { comment ->
        commentBody.putInt(comment.size)
        commentBody.put(comment)
    }
    val tags = "OpusTags".toByteArray(StandardCharsets.ISO_8859_1) + commentBody.array()

    return oggPage(BOS_PAGE, 0, head.array()) +
        oggPage(0, 1, tags) +
        oggPage(0, 2, AUDIO_MARKER.toByteArray(StandardCharsets.ISO_8859_1), granule = 4800)
}

private fun oggPage(type: Int, sequence: Int, content: ByteArray, granule: Long = 0): ByteArray {
    val segments = buildList {
        var remaining = content.size
        while (remaining >= 255) {
            add(255)
            remaining -= 255
        }
        if (remaining > 0 || content.isEmpty()) add(remaining)
    }
    val rawHeader = ByteArray(27 + segments.size)
    val buffer = ByteBuffer.wrap(rawHeader).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put("OggS".toByteArray(StandardCharsets.ISO_8859_1))
    buffer.put(0)
    buffer.put(type.toByte())
    buffer.putLong(granule)
    buffer.putInt(SERIAL)
    buffer.putInt(sequence)
    buffer.putInt(0)
    buffer.put(segments.size.toByte())
    segments.forEach { buffer.put(it.toByte()) }

    val page = OggPage(OggPageHeader(rawHeader), ByteBuffer.wrap(content))
    val output = ByteBuffer.allocate(page.size())
    page.write(output)
    return output.array()
}

private fun ByteArray.containsAscii(value: String): Boolean =
    String(this, StandardCharsets.ISO_8859_1).contains(value)

private const val BOS_PAGE = 0x02
private const val SERIAL = 0x1234
private const val AUDIO_MARKER = "AUDIO-PAYLOAD-MARKER"
