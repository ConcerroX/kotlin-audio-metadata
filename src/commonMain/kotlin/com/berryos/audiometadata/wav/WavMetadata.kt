package com.berryos.audiometadata.wav

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
import com.berryos.audiometadata.readUInt16Le
import com.berryos.audiometadata.readUInt32Le
import com.berryos.audiometadata.writeUInt32Le

/** Parsed WAV metadata: the embedded ID3 tag, the `LIST/INFO` fields and the audio properties. */
internal class WavMetadata(
    val tag: Id3v2Tag,
    val audioInfo: AudioInfo?,
    val info: WavInfoTags?,
    internal val chunks: List<WavChunk>,
    internal val id3Chunk: WavChunk?,
) {
    val artworks: List<Artwork>
        get() = tag.artworks

    /**
     * ID3 is the canonical tag. `LIST/INFO` is only used when the file has no ID3 chunk, so a tag
     * written through this library never mixes with stale INFO values.
     */
    fun toAudioMetadata(): AudioMetadata {
        if (id3Chunk != null) return tag.toAudioMetadata(AudioFormat.WAV, audioInfo)
        return info?.toAudioMetadata(audioInfo) ?: tag.toAudioMetadata(AudioFormat.WAV, audioInfo)
    }
}

/** Fields of a RIFF `LIST` chunk with type `INFO`. */
internal class WavInfoTags(
    val title: String?,
    val artist: String?,
    val album: String?,
    val year: String?,
    val genre: String?,
    val comment: String?,
    val trackNumber: Int?,
) {
    fun toAudioMetadata(audioInfo: AudioInfo?): AudioMetadata = AudioMetadata(
        format = AudioFormat.WAV,
        title = title,
        artists = listOfNotNull(artist),
        album = album,
        trackNumber = trackNumber,
        year = year,
        genres = listOfNotNull(genre),
        comments = listOfNotNull(comment),
        audioInfo = audioInfo,
    )
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
        if (!header.hasAsciiAt(0, "RIFF") || !header.hasAsciiAt(8, "WAVE")) return null

        var position = RIFF_HEADER_SIZE.toLong()
        var format: WavChunk? = null
        var data: WavChunk? = null
        var id3: WavChunk? = null
        var info: WavInfoTags? = null
        val chunks = ArrayList<WavChunk>()
        val chunkHeader = ByteArray(CHUNK_HEADER_SIZE)
        while (position + CHUNK_HEADER_SIZE <= source.size) {
            source.readFully(position, chunkHeader, chunkHeader.size)
            val id = chunkHeader.asciiAt(0, CHUNK_ID_SIZE)
            val size = chunkHeader.readUInt32Le(4).toLong() and 0xFFFFFFFFL
            if (position + CHUNK_HEADER_SIZE + size > source.size) break
            val chunk = WavChunk(id, position, position + CHUNK_HEADER_SIZE, size)
            chunks += chunk
            when {
                id == "fmt " -> format = chunk
                id == "data" -> data = chunk
                id == "id3 " || id == "ID3 " -> id3 = chunk
                id == "LIST" -> info = readInfo(source, chunk) ?: info
            }
            position = chunk.end
        }

        val audioInfo = format?.let { readAudioInfo(source, it, data?.size ?: 0L) }
        val tag = id3?.let { chunk ->
            val bytes = ByteArray(chunk.size.toInt())
            source.readFully(chunk.payloadOffset, bytes, bytes.size)
            Id3v2Reader.read(bytes)
        } ?: Id3v2Tag.create()
        return WavMetadata(tag, audioInfo, info, chunks, id3)
    }

    private fun readInfo(source: RandomAccessSource, chunk: WavChunk): WavInfoTags? {
        if (chunk.size < INFO_TYPE_SIZE) return null
        val type = ByteArray(INFO_TYPE_SIZE)
        source.readFully(chunk.payloadOffset, type, type.size)
        if (!type.hasAsciiAt(0, "INFO")) return null

        val end = chunk.payloadOffset + chunk.size
        var position = chunk.payloadOffset + INFO_TYPE_SIZE
        val header = ByteArray(CHUNK_HEADER_SIZE)
        val values = LinkedHashMap<String, String>()
        while (position + CHUNK_HEADER_SIZE <= end) {
            source.readFully(position, header, header.size)
            val id = header.asciiAt(0, CHUNK_ID_SIZE)
            val size = header.readUInt32Le(4).toLong() and 0xFFFFFFFFL
            val payloadStart = position + CHUNK_HEADER_SIZE
            if (payloadStart + size > end) break
            val bytes = ByteArray(size.toInt())
            source.readFully(payloadStart, bytes, bytes.size)
            decodePlainText(bytes)?.let { text -> values[id] = text }
            position = payloadStart + size + (size and 1L)
        }
        if (values.isEmpty()) return null
        return WavInfoTags(
            title = values["INAM"],
            artist = values["IART"],
            album = values["IPRD"],
            year = values["ICRD"],
            genre = values["IGNR"],
            comment = values["ICMT"],
            trackNumber = values["ITRK"]?.substringBefore('/')?.trim()?.toIntOrNull(),
        )
    }

    private fun readAudioInfo(source: RandomAccessSource, chunk: WavChunk, dataSize: Long): AudioInfo? {
        if (chunk.size < FORMAT_MIN_SIZE) return null
        val bytes = ByteArray(minOf(chunk.size, FORMAT_MIN_SIZE.toLong()).toInt())
        source.readFully(chunk.payloadOffset, bytes, bytes.size)
        val channels = bytes.readUInt16Le(2)
        val sampleRate = bytes.readUInt32Le(4)
        val byteRate = bytes.readUInt32Le(8)
        val bitsPerSample = bytes.readUInt16Le(14)
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
        header.writeUInt32Le(4, tagBytes.size)

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
        size.writeUInt32Le(0, (source.size - RIFF_HEADER_SIZE).toInt())
        source.writeAt(4, size)
    }
}

private const val RIFF_HEADER_SIZE = 12
private const val CHUNK_HEADER_SIZE = 8
private const val CHUNK_ID_SIZE = 4
private const val FORMAT_MIN_SIZE = 16
private const val INFO_TYPE_SIZE = 4
