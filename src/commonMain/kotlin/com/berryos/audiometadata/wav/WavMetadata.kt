package com.berryos.audiometadata.wav

import com.berryos.audiometadata.Artwork
import com.berryos.audiometadata.AudioFormat
import com.berryos.audiometadata.AudioInfo
import com.berryos.audiometadata.AudioMetadata
import com.berryos.audiometadata.id3.Id3v2Reader
import com.berryos.audiometadata.id3.Id3v2Tag
import com.berryos.audiometadata.id3.Id3v2Writer
import com.berryos.audiometadata.io.RandomAccessSource
import com.berryos.audiometadata.io.WritableRandomAccessSource
import com.berryos.audiometadata.io.moveTail
import com.berryos.audiometadata.io.readFully

/** Parsed WAV metadata: the embedded ID3 tag and the `fmt `/`data` properties. */
internal class WavMetadata(
    val tag: Id3v2Tag,
    val audioInfo: AudioInfo?,
    internal val chunks: List<WavChunk>,
    internal val id3Chunk: WavChunk?,
) {
    val artworks: List<Artwork>
        get() = tag.artworks

    fun toAudioMetadata(): AudioMetadata = tag.toAudioMetadata(AudioFormat.Wav, audioInfo)
}

internal class WavChunk(
    val id: String,
    val offset: Long,
    val payloadOffset: Long,
    val size: Long,
) {
    val totalSize: Long = CHUNK_HEADER_SIZE + size + (size and 1L)
    val end: Long = offset + totalSize
}

internal object WavMetadataReader {
    fun read(source: RandomAccessSource): WavMetadata? {
        if (source.size < RIFF_HEADER_SIZE) return null
        val header = ByteArray(RIFF_HEADER_SIZE)
        source.readFully(0, header, header.size)
        if (!header.hasTypeAt(0, "RIFF") || !header.hasTypeAt(8, "WAVE")) return null

        var position = RIFF_HEADER_SIZE.toLong()
        var format: WavChunk? = null
        var data: WavChunk? = null
        var id3: WavChunk? = null
        val chunks = ArrayList<WavChunk>()
        val chunkHeader = ByteArray(CHUNK_HEADER_SIZE)
        while (position + CHUNK_HEADER_SIZE <= source.size) {
            source.readFully(position, chunkHeader, chunkHeader.size)
            val id = fourCc(chunkHeader, 0)
            val size = chunkHeader.readLittleEndianInt(4).toLong() and 0xFFFFFFFFL
            if (position + CHUNK_HEADER_SIZE + size > source.size) break
            val chunk = WavChunk(id, position, position + CHUNK_HEADER_SIZE, size)
            chunks += chunk
            when {
                id == "fmt " -> format = chunk
                id == "data" -> data = chunk
                id == "id3 " || id == "ID3 " -> id3 = chunk
            }
            position = chunk.end
        }

        val audioInfo = format?.let { readAudioInfo(source, it, data?.size ?: 0L) }
        val tag = id3?.let { chunk ->
            val bytes = ByteArray(chunk.size.toInt())
            source.readFully(chunk.payloadOffset, bytes, bytes.size)
            Id3v2Reader.read(bytes)
        } ?: Id3v2Tag.create()
        return WavMetadata(tag, audioInfo, chunks, id3)
    }

    private fun readAudioInfo(source: RandomAccessSource, chunk: WavChunk, dataSize: Long): AudioInfo? {
        if (chunk.size < FORMAT_MIN_SIZE) return null
        val bytes = ByteArray(minOf(chunk.size, FORMAT_MIN_SIZE.toLong()).toInt())
        source.readFully(chunk.payloadOffset, bytes, bytes.size)
        val channels = bytes.readLittleEndianShort(2)
        val sampleRate = bytes.readLittleEndianInt(4)
        val byteRate = bytes.readLittleEndianInt(8)
        val bitsPerSample = bytes.readLittleEndianShort(14)
        val durationMs = if (byteRate > 0 && dataSize > 0) dataSize * 1000 / byteRate else null
        return AudioInfo(
            bitrateKbps = if (byteRate > 0) byteRate * 8 / 1000 else null,
            sampleRateHz = sampleRate,
            channels = channels,
            bitsPerSample = bitsPerSample,
            durationMs = durationMs,
        )
    }
}

/**
 * Writes the ID3 chunk, inserting it at the end when the file does not have one.
 *
 * The RIFF size is updated and the data chunk keeps its payload.
 */
internal object WavMetadataWriter {
    fun write(source: WritableRandomAccessSource, metadata: WavMetadata) {
        val tagBytes = Id3v2Writer.encode(metadata.tag)
        val padding = tagBytes.size and 1
        val newChunkSize = CHUNK_HEADER_SIZE + tagBytes.size + padding
        val header = ByteArray(CHUNK_HEADER_SIZE)
        "id3 ".encodeToByteArray().copyInto(header)
        header.writeLittleEndianInt(4, tagBytes.size)

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
        writeRiffSize(source)
        source.flush()
    }

    private fun writeRiffSize(source: WritableRandomAccessSource) {
        val size = ByteArray(4)
        size.writeLittleEndianInt(0, (source.size - RIFF_HEADER_SIZE).toInt())
        source.writeAt(4, size)
    }
}

internal fun ByteArray.hasTypeAt(offset: Int, type: String): Boolean {
    if (size < offset + type.length) return false
    for (index in type.indices) {
        if (this[offset + index] != type[index].code.toByte()) return false
    }
    return true
}

private fun fourCc(bytes: ByteArray, offset: Int): String = buildString(4) {
    repeat(4) { index -> append((bytes[offset + index].toInt() and 0xFF).toChar()) }
}

internal fun ByteArray.readLittleEndianInt(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)

internal fun ByteArray.readLittleEndianShort(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

internal fun ByteArray.writeLittleEndianInt(offset: Int, value: Int) {
    this[offset] = value.toByte()
    this[offset + 1] = (value ushr 8).toByte()
    this[offset + 2] = (value ushr 16).toByte()
    this[offset + 3] = (value ushr 24).toByte()
}

private const val RIFF_HEADER_SIZE = 12
private const val CHUNK_HEADER_SIZE = 8
private const val FORMAT_MIN_SIZE = 16
