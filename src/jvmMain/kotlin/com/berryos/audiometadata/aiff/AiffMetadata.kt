package com.berryos.audiometadata.aiff

import com.berryos.audiometadata.Artwork
import com.berryos.audiometadata.AudioFormat
import com.berryos.audiometadata.AudioInfo
import com.berryos.audiometadata.AudioMetadata
import com.berryos.audiometadata.id3.Id3Tag
import com.berryos.audiometadata.id3.parseId3v2Tag
import com.berryos.audiometadata.io.RandomAccessSource
import com.berryos.audiometadata.io.WritableRandomAccessSource
import com.berryos.audiometadata.io.moveTail
import com.berryos.audiometadata.io.readFully
import java.io.ByteArrayOutputStream
import kotlin.math.pow
import kotlin.math.roundToInt

/** Parsed AIFF metadata: the embedded ID3 tag and the `COMM` properties. */
internal class AiffMetadata(
    val tag: Id3Tag,
    val audioInfo: AudioInfo?,
    internal val chunks: List<AiffChunk>,
    internal val id3Chunk: AiffChunk?,
) {
    val artworks: List<Artwork>
        get() = tag.artworks

    fun toAudioMetadata(): AudioMetadata = tag.toAudioMetadata(AudioFormat.Aiff, audioInfo)
}

internal class AiffChunk(
    val id: String,
    val offset: Long,
    val payloadOffset: Long,
    val size: Long,
) {
    val totalSize: Long = CHUNK_HEADER_SIZE + size + (size and 1L)
    val end: Long = offset + totalSize
}

internal object AiffMetadataReader {
    fun read(source: RandomAccessSource): AiffMetadata? {
        if (source.size < FORM_HEADER_SIZE) return null
        val header = ByteArray(FORM_HEADER_SIZE)
        source.readFully(0, header, header.size)
        if (!header.hasTypeAt(0, "FORM")) return null
        if (!header.hasTypeAt(8, "AIFF") && !header.hasTypeAt(8, "AIFC")) return null

        var position = FORM_HEADER_SIZE.toLong()
        var common: AiffChunk? = null
        var id3: AiffChunk? = null
        val chunks = ArrayList<AiffChunk>()
        val chunkHeader = ByteArray(CHUNK_HEADER_SIZE)
        while (position + CHUNK_HEADER_SIZE <= source.size) {
            source.readFully(position, chunkHeader, chunkHeader.size)
            val id = fourCc(chunkHeader, 0)
            val size = chunkHeader.readBigEndianInt(4).toLong() and 0xFFFFFFFFL
            if (position + CHUNK_HEADER_SIZE + size > source.size) break
            val chunk = AiffChunk(id, position, position + CHUNK_HEADER_SIZE, size)
            chunks += chunk
            when (id) {
                "COMM" -> common = chunk
                "ID3 " -> id3 = chunk
            }
            position = chunk.end
        }

        val audioInfo = common?.let { readAudioInfo(source, it) }
        val tag = id3?.let { chunk ->
            val bytes = ByteArray(chunk.size.toInt())
            source.readFully(chunk.payloadOffset, bytes, bytes.size)
            parseId3v2Tag(bytes)
        } ?: Id3Tag.create()
        return AiffMetadata(tag, audioInfo, chunks, id3)
    }

    private fun readAudioInfo(source: RandomAccessSource, chunk: AiffChunk): AudioInfo? {
        if (chunk.size < COMMON_MIN_SIZE) return null
        val bytes = ByteArray(COMMON_MIN_SIZE)
        source.readFully(chunk.payloadOffset, bytes, bytes.size)
        val channels = bytes.readBigEndianShort(0)
        val frames = bytes.readBigEndianInt(2).toLong() and 0xFFFFFFFFL
        val bitsPerSample = bytes.readBigEndianShort(6)
        val sampleRate = readExtendedFloat(bytes, 8).roundToInt()
        if (sampleRate <= 0) return null
        val durationMs = frames * 1000 / sampleRate
        val bitrateKbps = channels * bitsPerSample * sampleRate / 1000
        return AudioInfo(
            bitrateKbps = bitrateKbps,
            sampleRateHz = sampleRate,
            channels = channels,
            bitsPerSample = bitsPerSample,
            durationMs = durationMs,
        )
    }
}

/** Writes the `ID3 ` chunk, inserting it at the end when absent and updating the FORM size. */
internal object AiffMetadataWriter {
    fun write(source: WritableRandomAccessSource, metadata: AiffMetadata) {
        val tagBytes = ByteArrayOutputStream().use { output ->
            metadata.tag.tag.write(output, 0)
            output.toByteArray()
        }
        val padding = tagBytes.size and 1
        val newChunkSize = CHUNK_HEADER_SIZE + tagBytes.size + padding
        val header = ByteArray(CHUNK_HEADER_SIZE)
        "ID3 ".encodeToByteArray().copyInto(header)
        header.writeBigEndianInt(4, tagBytes.size)

        val existing = metadata.id3Chunk
        val chunkOffset = existing?.offset ?: source.size
        if (existing != null) {
            source.moveTail(existing.end, existing.offset + newChunkSize)
        }
        source.writeAt(chunkOffset, header)
        source.writeAt(chunkOffset + CHUNK_HEADER_SIZE, tagBytes)
        if (padding > 0) {
            source.writeAt(chunkOffset + CHUNK_HEADER_SIZE + tagBytes.size, byteArrayOf(0))
        }
        val size = ByteArray(4)
        size.writeBigEndianInt(0, (source.size - FORM_HEADER_SIZE).toInt())
        source.writeAt(4, size)
        source.flush()
    }
}

internal fun ByteArray.hasTypeAt(offset: Int, type: String): Boolean {
    if (size < offset + type.length) return false
    for (index in type.indices) {
        if (this[offset + index] != type[index].code.toByte()) return false
    }
    return true
}

private fun readExtendedFloat(bytes: ByteArray, offset: Int): Double {
    val sign = if (bytes[offset].toInt() and 0x80 != 0) -1 else 1
    val exponent = ((bytes[offset].toInt() and 0x7F) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
    var mantissa = 0UL
    for (index in 0 until 8) {
        mantissa = (mantissa shl 8) or (bytes[offset + 2 + index].toUByte().toULong())
    }
    if (exponent == 0 && mantissa == 0UL) return 0.0
    return sign * mantissa.toDouble() * 2.0.pow(exponent - EXTENDED_EXPONENT_BIAS - 63)
}

private fun fourCc(bytes: ByteArray, offset: Int): String = buildString(4) {
    repeat(4) { index -> append((bytes[offset + index].toInt() and 0xFF).toChar()) }
}

internal fun ByteArray.readBigEndianInt(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 24) or
        ((this[offset + 1].toInt() and 0xFF) shl 16) or
        ((this[offset + 2].toInt() and 0xFF) shl 8) or
        (this[offset + 3].toInt() and 0xFF)

internal fun ByteArray.readBigEndianShort(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)

internal fun ByteArray.writeBigEndianInt(offset: Int, value: Int) {
    this[offset] = (value ushr 24).toByte()
    this[offset + 1] = (value ushr 16).toByte()
    this[offset + 2] = (value ushr 8).toByte()
    this[offset + 3] = value.toByte()
}

private const val FORM_HEADER_SIZE = 12
private const val CHUNK_HEADER_SIZE = 8
private const val COMMON_MIN_SIZE = 18
private const val EXTENDED_EXPONENT_BIAS = 16383
