package com.berryos.audiometadata.aiff

import com.berryos.audiometadata.Artwork
import com.berryos.audiometadata.AudioFormat
import com.berryos.audiometadata.AudioInfo
import com.berryos.audiometadata.AudioMetadata
import com.berryos.audiometadata.asciiAt
import com.berryos.audiometadata.decodePlainText
import com.berryos.audiometadata.hasAsciiAt
import com.berryos.audiometadata.id3.Id3v2Reader
import com.berryos.audiometadata.id3.Id3v2Tag
import com.berryos.audiometadata.id3.Id3v2Writer
import com.berryos.audiometadata.io.RandomAccessSource
import com.berryos.audiometadata.io.WritableRandomAccessSource
import com.berryos.audiometadata.io.moveTail
import com.berryos.audiometadata.io.readFully
import com.berryos.audiometadata.readUInt16Be
import com.berryos.audiometadata.readUInt32Be
import com.berryos.audiometadata.writeUInt32Be
import kotlin.math.pow
import kotlin.math.roundToInt

/** Parsed AIFF metadata: the embedded ID3 tag, the native text chunks and the `COMM` properties. */
internal class AiffMetadata(
    val tag: Id3v2Tag,
    val audioInfo: AudioInfo?,
    val text: AiffTextTags?,
    internal val chunks: List<AiffChunk>,
    internal val id3Chunk: AiffChunk?,
) {
    val artworks: List<Artwork>
        get() = tag.artworks

    /**
     * ID3 is the canonical tag. Native `NAME`/`AUTH`/`ANNO` chunks are only used when the file has
     * no ID3 chunk, so a tag written through this library never mixes with stale text chunks.
     */
    fun toAudioMetadata(): AudioMetadata {
        if (id3Chunk != null) return tag.toAudioMetadata(AudioFormat.AIFF, audioInfo)
        return text?.toAudioMetadata(audioInfo) ?: tag.toAudioMetadata(AudioFormat.AIFF, audioInfo)
    }
}

/** Fields of the native AIFF `NAME`, `AUTH` and `ANNO` text chunks. */
internal class AiffTextTags(
    val title: String?,
    val artist: String?,
    val comments: List<String>,
) {
    fun toAudioMetadata(audioInfo: AudioInfo?): AudioMetadata = AudioMetadata(
        format = AudioFormat.AIFF,
        title = title,
        artists = listOfNotNull(artist),
        comments = comments,
        audioInfo = audioInfo,
    )
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
        if (!header.hasAsciiAt(0, "FORM")) return null
        if (!header.hasAsciiAt(8, "AIFF") && !header.hasAsciiAt(8, "AIFC")) return null

        var position = FORM_HEADER_SIZE.toLong()
        var common: AiffChunk? = null
        var id3: AiffChunk? = null
        var textTitle: String? = null
        var textArtist: String? = null
        val textComments = ArrayList<String>()
        val chunks = ArrayList<AiffChunk>()
        val chunkHeader = ByteArray(CHUNK_HEADER_SIZE)
        while (position + CHUNK_HEADER_SIZE <= source.size) {
            source.readFully(position, chunkHeader, chunkHeader.size)
            val id = chunkHeader.asciiAt(0, CHUNK_ID_SIZE)
            val size = chunkHeader.readUInt32Be(4).toLong() and 0xFFFFFFFFL
            if (position + CHUNK_HEADER_SIZE + size > source.size) break
            val chunk = AiffChunk(id, position, position + CHUNK_HEADER_SIZE, size)
            chunks += chunk
            when (id) {
                "COMM" -> common = chunk
                "ID3 " -> id3 = chunk
                "NAME" -> textTitle = readChunkText(source, chunk)
                "AUTH" -> textArtist = readChunkText(source, chunk)
                "ANNO" -> readChunkText(source, chunk)?.let(textComments::add)
            }
            position = chunk.end
        }

        val audioInfo = common?.let { readAudioInfo(source, it) }
        val tag = id3?.let { chunk ->
            val bytes = ByteArray(chunk.size.toInt())
            source.readFully(chunk.payloadOffset, bytes, bytes.size)
            Id3v2Reader.read(bytes)
        } ?: Id3v2Tag.create()
        val text = if (textTitle != null || textArtist != null || textComments.isNotEmpty()) {
            AiffTextTags(textTitle, textArtist, textComments)
        } else {
            null
        }
        return AiffMetadata(tag, audioInfo, text, chunks, id3)
    }

    private fun readChunkText(source: RandomAccessSource, chunk: AiffChunk): String? {
        if (chunk.size <= 0 || chunk.size > MAX_TEXT_CHUNK_SIZE) return null
        val bytes = ByteArray(chunk.size.toInt())
        source.readFully(chunk.payloadOffset, bytes, bytes.size)
        return decodePlainText(bytes)
    }

    private fun readAudioInfo(source: RandomAccessSource, chunk: AiffChunk): AudioInfo? {
        if (chunk.size < COMMON_MIN_SIZE) return null
        val bytes = ByteArray(COMMON_MIN_SIZE)
        source.readFully(chunk.payloadOffset, bytes, bytes.size)
        val channels = bytes.readUInt16Be(0)
        val frames = bytes.readUInt32Be(2).toLong() and 0xFFFFFFFFL
        val bitsPerSample = bytes.readUInt16Be(6)
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
        val tagBytes = Id3v2Writer.encode(metadata.tag)
        val padding = tagBytes.size and 1
        val newChunkSize = CHUNK_HEADER_SIZE + tagBytes.size + padding
        val header = ByteArray(CHUNK_HEADER_SIZE)
        "ID3 ".encodeToByteArray().copyInto(header)
        header.writeUInt32Be(4, tagBytes.size)

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
        size.writeUInt32Be(0, (source.size - FORM_HEADER_SIZE).toInt())
        source.writeAt(4, size)
        source.flush()
    }
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

private const val FORM_HEADER_SIZE = 12
private const val CHUNK_HEADER_SIZE = 8
private const val CHUNK_ID_SIZE = 4
private const val COMMON_MIN_SIZE = 18
private const val EXTENDED_EXPONENT_BIAS = 16383
private const val MAX_TEXT_CHUNK_SIZE = 1L shl 20
