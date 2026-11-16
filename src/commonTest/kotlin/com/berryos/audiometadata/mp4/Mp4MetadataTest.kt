package com.berryos.audiometadata.mp4

import com.berryos.audiometadata.AudioFormat
import com.berryos.audiometadata.io.ByteArraySource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class Mp4MetadataTest {
    @Test
    fun readsTagsAndAudioInfo() {
        val metadata = assertNotNull(Mp4MetadataReader.read(sourceWith(mp4File())))
        val audio = metadata.toAudioMetadata()

        assertEquals(AudioFormat.Mp4, audio.format)
        assertEquals("Song", audio.title)
        assertEquals(listOf("Artist"), audio.artists)
        assertEquals("Album", audio.album)
        assertEquals("Album Artist", audio.albumArtist)
        assertEquals(3, audio.trackNumber)
        assertEquals(12, audio.trackTotal)
        assertEquals(1, audio.discNumber)
        assertEquals(2, audio.discTotal)
        assertEquals("1999", audio.year)
        assertEquals(listOf("Rock"), audio.genres)
        assertEquals(listOf("Composer"), audio.composers)
        assertEquals(listOf("Comment"), audio.comments)
        assertEquals(44_100, audio.audioInfo?.sampleRateHz)
        assertEquals(2, audio.audioInfo?.channels)
        assertEquals(5000L, audio.audioInfo?.durationMs)
    }

    @Test
    fun keepsMultipleValues() {
        val file = mp4File(artists = listOf("First", "Second"))
        val metadata = assertNotNull(Mp4MetadataReader.read(sourceWith(file)))

        assertEquals(listOf("First", "Second"), metadata.toAudioMetadata().artists)
    }

    @Test
    fun returnsNullWithoutMoov() {
        assertNull(Mp4MetadataReader.read(sourceWith(ByteArray(64))))
    }

    private fun sourceWith(bytes: ByteArray): ByteArraySource =
        ByteArraySource().also { it.writeAt(0, bytes) }
}

internal fun mp4File(artists: List<String> = listOf("Artist")): ByteArray {
    val ftyp = atom(
        "ftyp",
        "M4A ".encodeToByteArray() + bigEndianInt(0) + "M4A ".encodeToByteArray() + "mp42".encodeToByteArray(),
    )

    val mvhd = fullBox(
        "mvhd",
        payload = ByteArray(16).also { data ->
            writeBigEndianInt(data, 8, 1000)
            writeBigEndianInt(data, 12, 5000)
        },
    )
    val mdhd = fullBox(
        "mdhd",
        payload = ByteArray(16).also { data ->
            writeBigEndianInt(data, 8, 44_100)
            writeBigEndianInt(data, 12, 220_500)
        },
    )
    val hdlr = fullBox("hdlr", payload = bigEndianInt(0) + "soun".encodeToByteArray() + ByteArray(12))
    val sampleEntry = ByteArray(28).also { data ->
        writeBigEndianShort(data, 16, 2)
        writeBigEndianShort(data, 18, 16)
        writeBigEndianInt(data, 24, 44_100 shl 16)
    }
    val stsd = fullBox("stsd", payload = bigEndianInt(1) + atom("mp4a", sampleEntry))
    val minf = atom("minf", atom("stbl", stsd))
    val trak = atom("trak", atom("mdia", mdhd + hdlr + minf))

    val ilst = atom(
        "ilst",
        textItem("©nam", "Song") +
            artists.fold(ByteArray(0)) { acc, artist -> acc + textItem("©ART", artist) } +
            textItem("©alb", "Album") +
            textItem("aART", "Album Artist") +
            numberItem("trkn", 3, 12) +
            numberItem("disk", 1, 2) +
            textItem("©day", "1999") +
            textItem("©gen", "Rock") +
            textItem("©wrt", "Composer") +
            textItem("©cmt", "Comment"),
    )
    val moov = atom(
        "moov",
        mvhd + trak + atom("udta", atom("meta", bigEndianInt(0) + ilst)),
    )
    val mdat = atom("mdat", ByteArray(512) { it.toByte() })
    return ftyp + moov + mdat
}

private fun atom(type: String, payload: ByteArray): ByteArray {
    val output = ByteArray(8 + payload.size)
    writeBigEndianInt(output, 0, output.size)
    type.toByteArray(Charsets.ISO_8859_1).copyInto(output, 4)
    payload.copyInto(output, 8)
    return output
}

private fun fullBox(type: String, payload: ByteArray): ByteArray = atom(type, bigEndianInt(0) + payload)

private fun textItem(field: String, text: String): ByteArray = atom(
    field,
    atom("data", bigEndianInt(DATA_TYPE_UTF8) + bigEndianInt(0) + text.encodeToByteArray()),
)

private fun numberItem(field: String, number: Int, total: Int): ByteArray = atom(
    field,
    atom(
        "data",
        bigEndianInt(0) + bigEndianInt(0) +
            byteArrayOf(0, 0) +
            bigEndianShort(number) +
            bigEndianShort(total) +
            byteArrayOf(0, 0),
    ),
)

private fun bigEndianInt(value: Int): ByteArray = ByteArray(4).also { writeBigEndianInt(it, 0, value) }

private fun bigEndianShort(value: Int): ByteArray = ByteArray(2).also { writeBigEndianShort(it, 0, value) }

private fun writeBigEndianInt(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = (value ushr 24).toByte()
    bytes[offset + 1] = (value ushr 16).toByte()
    bytes[offset + 2] = (value ushr 8).toByte()
    bytes[offset + 3] = value.toByte()
}

private fun writeBigEndianShort(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = (value ushr 8).toByte()
    bytes[offset + 1] = value.toByte()
}

private const val DATA_TYPE_UTF8 = 1
