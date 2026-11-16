package com.berryos.audiometadata.id3

import com.berryos.audiometadata.Id3Version
import com.berryos.audiometadata.io.ByteArraySource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class Id3v2ReaderTest {
    @Test
    fun readsV23Fields() {
        val tag = read(
            buildTag(
                version = 3,
                frames = listOf(
                    textFrame(3, "TIT2", 0, "Song"),
                    textFrame(3, "TPE1", 0, "Artist", "Guest"),
                    textFrame(3, "TALB", 0, "Album"),
                    textFrame(3, "TPE2", 0, "Album Artist"),
                    textFrame(3, "TRCK", 0, "3/12"),
                    textFrame(3, "TPOS", 0, "1/2"),
                    textFrame(3, "TYER", 0, "1999"),
                    textFrame(3, "TCON", 0, "(17)"),
                    textFrame(3, "TCOM", 0, "Composer"),
                    frame(3, "COMM", commentContent("eng", "", "Comment")),
                ),
            ),
        )

        assertEquals(Id3Version.V23, tag.version)
        assertEquals("Song", tag.title)
        assertEquals(listOf("Artist", "Guest"), tag.artists)
        assertEquals("Album", tag.album)
        assertEquals("Album Artist", tag.albumArtist)
        assertEquals(3, tag.trackNumber)
        assertEquals(12, tag.trackTotal)
        assertEquals(1, tag.discNumber)
        assertEquals(2, tag.discTotal)
        assertEquals("1999", tag.year)
        assertEquals(listOf("Rock"), tag.genres)
        assertEquals(listOf("Composer"), tag.composers)
        assertEquals(listOf("Comment"), tag.comments)
    }

    @Test
    fun readsV24Fields() {
        val longTitle = "T".repeat(200)
        val tag = read(
            buildTag(
                version = 4,
                frames = listOf(
                    textFrame(4, "TIT2", 3, longTitle),
                    textFrame(4, "TPE1", 3, "First", "Second"),
                    textFrame(4, "TDRC", 3, "2001-02-03"),
                    textFrame(4, "TCON", 3, "Rock", "Pop"),
                ),
            ),
        )

        assertEquals(Id3Version.V24, tag.version)
        assertEquals(longTitle, tag.title)
        assertEquals(listOf("First", "Second"), tag.artists)
        assertEquals("2001-02-03", tag.year)
        assertEquals(listOf("Rock", "Pop"), tag.genres)
    }

    @Test
    fun decodesAllTextEncodings() {
        val tag = read(
            buildTag(
                version = 3,
                frames = listOf(
                    textFrame(3, "TIT2", 0, "Café"),
                    textFrame(3, "TPE1", 1, "Artist"),
                    textFrame(3, "TALB", 2, "Album"),
                ),
            ),
        )

        assertEquals("Café", tag.title)
        assertEquals(listOf("Artist"), tag.artists)
        assertEquals("Album", tag.album)
    }

    @Test
    fun readsArtwork() {
        val image = "PNG-BYTES".encodeToByteArray()
        val tag = read(
            buildTag(
                version = 3,
                frames = listOf(frame(3, "APIC", apicContent("image/png", 3, "cover", image))),
            ),
        )

        val artwork = assertNotNull(tag.artworks.firstOrNull())
        assertContentEquals(image, artwork.data)
        assertEquals("image/png", artwork.mimeType)
        assertEquals(3, artwork.pictureType)
        assertEquals("cover", artwork.description)
    }

    @Test
    fun readsReplayGainFromUserText() {
        val tag = read(
            buildTag(
                version = 3,
                frames = listOf(
                    frame(3, "TXXX", userTextContent(0, "REPLAYGAIN_TRACK_GAIN", "-6.53 dB")),
                    frame(3, "TXXX", userTextContent(0, "REPLAYGAIN_TRACK_PEAK", "0.98")),
                ),
            ),
        )

        assertEquals("-6.53 dB", tag.replayGain?.trackGain)
        assertEquals("0.98", tag.replayGain?.trackPeak)
    }

    @Test
    fun skipsExtendedHeaderV23() {
        // v2.3 extended header size excludes the size field itself: 6 bytes, flags, padding size.
        val extendedHeader = contentInt(6) + byteArrayOf(0, 0) + contentInt(0)
        val tag = read(
            buildTag(
                version = 3,
                flags = 0x40,
                bodyPrefix = extendedHeader,
                frames = listOf(textFrame(3, "TIT2", 0, "Song")),
            ),
        )

        assertEquals("Song", tag.title)
        assertEquals(extendedHeader.size + 4 - 4, tag.extendedHeaderSize)
    }

    @Test
    fun skipsExtendedHeaderV24() {
        // v2.4 extended header size is synchsafe and includes itself.
        val extendedHeader = ByteArray(6)
        writeSyncSafe(extendedHeader, 0, 6)
        extendedHeader[4] = 1
        extendedHeader[5] = 0
        val tag = read(
            buildTag(
                version = 4,
                flags = 0x40,
                bodyPrefix = extendedHeader,
                frames = listOf(textFrame(4, "TIT2", 3, "Song")),
            ),
        )

        assertEquals("Song", tag.title)
        assertEquals(6, tag.extendedHeaderSize)
    }

    @Test
    fun reversesTagUnsynchronisationV23() {
        val frame = textFrame(3, "TIT2", 0, "ÿ")
        val body = unsynchronise(frame)
        val header = ByteArray(10)
        "ID3".encodeToByteArray().copyInto(header)
        header[3] = 3
        header[5] = 0x80.toByte()
        writeSyncSafe(header, 6, body.size)
        val tag = read(header + body)

        assertEquals("ÿ", tag.title)
        assertEquals(true, tag.unsynchronised)
    }

    @Test
    fun reversesFrameUnsynchronisationV24() {
        val stored = unsynchronise(byteArrayOf(0) + latin1Bytes("ÿ"))
        val frameHeader = ByteArray(10)
        "TIT2".encodeToByteArray().copyInto(frameHeader)
        writeSyncSafe(frameHeader, 4, stored.size)
        frameHeader[9] = 0x02
        val tag = read(buildTag(4, frames = listOf(frameHeader + stored)))

        assertEquals("ÿ", tag.title)
    }

    @Test
    fun keepsUnknownFramesRaw() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val tag = read(buildTag(3, frames = listOf(frame(3, "PRIV", payload), textFrame(3, "TIT2", 0, "Song"))))

        val frame = assertNotNull(tag.framesOf("PRIV").firstOrNull())
        assertContentEquals(payload, frame.content)
        assertEquals("Song", tag.title)
    }

    @Test
    fun reportsPadding() {
        val tag = read(buildTag(3, frames = listOf(textFrame(3, "TIT2", 0, "Song")), padding = 64))

        assertEquals(64, tag.paddingSize)
    }

    @Test
    fun readsFromSource() {
        val bytes = buildTag(3, frames = listOf(textFrame(3, "TIT2", 0, "Song")))
        val source = ByteArraySource().also { it.writeAt(0, bytes + ByteArray(32)) }

        val tag = assertNotNull(Id3v2Reader.read(source))
        assertEquals("Song", tag.title)
    }

    @Test
    fun returnsNullWithoutTag() {
        assertNull(Id3v2Reader.read(ByteArray(32)))
    }

    @Test
    fun returnsNullForUnknownVersion() {
        val header = ByteArray(10)
        "ID3".encodeToByteArray().copyInto(header)
        header[3] = 9
        assertNull(Id3v2Reader.read(header))
    }

    private fun read(bytes: ByteArray): Id3v2Tag = assertNotNull(Id3v2Reader.read(bytes))
}

internal fun buildTag(
    version: Int,
    flags: Int = 0,
    bodyPrefix: ByteArray = ByteArray(0),
    frames: List<ByteArray>,
    padding: Int = 0,
): ByteArray {
    val body = bodyPrefix + frames.fold(ByteArray(0)) { accumulated, frame -> accumulated + frame } +
        ByteArray(padding)
    val header = ByteArray(10)
    "ID3".encodeToByteArray().copyInto(header)
    header[3] = version.toByte()
    header[4] = 0
    header[5] = flags.toByte()
    writeSyncSafe(header, 6, body.size)
    return header + body
}

internal fun frame(version: Int, id: String, content: ByteArray, status: Int = 0, format: Int = 0): ByteArray {
    val headerSize = if (version == 2) 6 else 10
    val header = ByteArray(headerSize)
    id.encodeToByteArray().copyInto(header)
    if (version == 2) {
        header[3] = (content.size ushr 16).toByte()
        header[4] = (content.size ushr 8).toByte()
        header[5] = content.size.toByte()
    } else {
        if (version == 4) {
            writeSyncSafe(header, 4, content.size)
        } else {
            contentInt(content.size).copyInto(header, 4)
        }
        header[8] = status.toByte()
        header[9] = format.toByte()
    }
    return header + content
}

internal fun textFrame(version: Int, id: String, encoding: Int, vararg values: String): ByteArray =
    frame(version, id, byteArrayOf(encoding.toByte()) + encodeText(encoding, values.toList()))

internal fun encodeText(encoding: Int, values: List<String>): ByteArray {
    val separator = if (encoding == 1 || encoding == 2) byteArrayOf(0, 0) else byteArrayOf(0)
    val chunks = ArrayList<ByteArray>()
    values.forEachIndexed { index, value ->
        if (index > 0) chunks += separator
        chunks += when (encoding) {
            0 -> latin1Bytes(value)
            1 -> byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + utf16Bytes(value, littleEndian = true)
            2 -> utf16Bytes(value, littleEndian = false)
            else -> value.encodeToByteArray()
        }
    }
    return chunks.fold(ByteArray(0)) { accumulated, chunk -> accumulated + chunk }
}

internal fun latin1Bytes(text: String): ByteArray = ByteArray(text.length) { index -> text[index].code.toByte() }

internal fun utf16Bytes(text: String, littleEndian: Boolean): ByteArray {
    val output = ByteArray(text.length * 2)
    text.forEachIndexed { index, char ->
        val code = char.code
        if (littleEndian) {
            output[index * 2] = code.toByte()
            output[index * 2 + 1] = (code shr 8).toByte()
        } else {
            output[index * 2] = (code shr 8).toByte()
            output[index * 2 + 1] = code.toByte()
        }
    }
    return output
}

internal fun contentInt(value: Int): ByteArray = byteArrayOf(
    (value ushr 24).toByte(),
    (value ushr 16).toByte(),
    (value ushr 8).toByte(),
    value.toByte(),
)

internal fun writeSyncSafe(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = ((value ushr 21) and 0x7F).toByte()
    bytes[offset + 1] = ((value ushr 14) and 0x7F).toByte()
    bytes[offset + 2] = ((value ushr 7) and 0x7F).toByte()
    bytes[offset + 3] = (value and 0x7F).toByte()
}

internal fun commentContent(language: String, description: String, text: String, encoding: Int = 0): ByteArray =
    byteArrayOf(encoding.toByte()) + language.encodeToByteArray() +
        encodeText(encoding, listOf(description)) + encodedTerminator(encoding) +
        encodeText(encoding, listOf(text))

internal fun userTextContent(encoding: Int, description: String, value: String): ByteArray =
    byteArrayOf(encoding.toByte()) + encodeText(encoding, listOf(description)) + encodedTerminator(encoding) +
        encodeText(encoding, listOf(value))

internal fun apicContent(
    mime: String,
    pictureType: Int,
    description: String,
    data: ByteArray,
    encoding: Int = 0,
): ByteArray = byteArrayOf(encoding.toByte()) + latin1Bytes(mime) + byteArrayOf(0) +
    byteArrayOf(pictureType.toByte()) + encodeText(encoding, listOf(description)) +
    encodedTerminator(encoding) + data

internal fun encodedTerminator(encoding: Int): ByteArray =
    if (encoding == 1 || encoding == 2) byteArrayOf(0, 0) else byteArrayOf(0)

internal fun unsynchronise(bytes: ByteArray): ByteArray {
    val output = ArrayList<Byte>()
    var index = 0
    while (index < bytes.size) {
        val byte = bytes[index++]
        output += byte
        if (byte.toInt() and 0xFF == 0xFF) {
            output += 0
            if (index < bytes.size && bytes[index].toInt() and 0xFF <= 0x1F) {
                output += bytes[index++]
            }
        }
    }
    return output.toByteArray()
}
