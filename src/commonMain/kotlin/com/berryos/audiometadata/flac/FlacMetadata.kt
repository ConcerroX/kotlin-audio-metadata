package com.berryos.audiometadata.flac

import com.berryos.audiometadata.Artwork
import com.berryos.audiometadata.AudioFormat
import com.berryos.audiometadata.AudioInfo
import com.berryos.audiometadata.AudioMetadata
import com.berryos.audiometadata.io.RandomAccessSource
import com.berryos.audiometadata.io.WritableRandomAccessSource
import com.berryos.audiometadata.io.moveTail
import com.berryos.audiometadata.io.readFully
import com.berryos.audiometadata.vorbis.VorbisCommentTag

/** Fields of the mandatory FLAC STREAMINFO block. */
internal data class FlacStreamInfo(
    val minBlockSize: Int,
    val maxBlockSize: Int,
    val sampleRateHz: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val totalSamples: Long,
) {
    val durationMs: Long
        get() = if (sampleRateHz > 0) totalSamples * 1000 / sampleRateHz else 0

    fun toAudioInfo(): AudioInfo = AudioInfo(
        sampleRateHz = sampleRateHz,
        channels = channels,
        bitsPerSample = bitsPerSample,
        durationMs = durationMs,
    )
}

/**
 * Parsed FLAC metadata.
 *
 * Other blocks (pictures, seek tables, applications, ...) stay referenced by offset and are copied
 * back unchanged by [FlacMetadataWriter].
 */
internal class FlacMetadata(
    val tag: VorbisCommentTag,
    val streamInfo: FlacStreamInfo?,
    /** Loaded pictures, or null when the reader was asked to skip them. */
    var artworks: List<Artwork>?,
    internal val blocks: List<FlacBlock>,
    internal val audioStartOffset: Long,
) {
    fun toAudioMetadata(): AudioMetadata =
        tag.toAudioMetadata(AudioFormat.FLAC, streamInfo?.toAudioInfo())
}

internal class FlacBlock(val type: Int, val offset: Long, val length: Int)

/** Reads FLAC metadata blocks from any random-access source. */
internal object FlacMetadataReader {
    fun read(source: RandomAccessSource, includeArtworks: Boolean = true): FlacMetadata? {
        if (source.size < FLAC_MAGIC.size) return null
        val magic = ByteArray(FLAC_MAGIC.size)
        if (source.readAt(0, magic) < magic.size || !magic.contentEquals(FLAC_MAGIC)) return null

        var position = FLAC_MAGIC.size.toLong()
        var last = false
        var streamInfo: FlacStreamInfo? = null
        var tag = VorbisCommentTag.create()
        val artworks = if (includeArtworks) ArrayList<Artwork>() else null
        val blocks = ArrayList<FlacBlock>()
        val header = ByteArray(BLOCK_HEADER_SIZE)
        while (!last) {
            if (source.readAt(position, header) < BLOCK_HEADER_SIZE) return null
            last = header[0].toInt() and LAST_BLOCK_FLAG != 0
            val type = header[0].toInt() and BLOCK_TYPE_MASK
            val length = ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl 8) or
                (header[3].toInt() and 0xFF)
            val dataOffset = position + BLOCK_HEADER_SIZE
            if (dataOffset + length > source.size) return null
            blocks += FlacBlock(type, dataOffset, length)
            when (type) {
                TYPE_STREAMINFO -> if (length >= STREAMINFO_SIZE) {
                    val data = ByteArray(STREAMINFO_SIZE)
                    source.readFully(dataOffset, data, data.size)
                    streamInfo = parseStreamInfo(data)
                }

                TYPE_VORBIS_COMMENT -> {
                    val data = ByteArray(length)
                    source.readFully(dataOffset, data, data.size)
                    VorbisCommentTag.decode(data)?.let { tag = it }
                }

                TYPE_PICTURE -> {
                    val target = artworks
                    if (target != null) {
                        val data = ByteArray(length)
                        source.readFully(dataOffset, data, data.size)
                        parseFlacPicture(data)?.let(target::add)
                    }
                }
            }
            position = dataOffset + length
        }
        return FlacMetadata(tag, streamInfo, artworks, blocks, position)
    }
}

/**
 * Writes the Vorbis comment block back.
 *
 * Existing padding is reused so the audio frames stay in place; if the new comment does not fit,
 * the audio is shifted by the difference first. Padding is always written as the last block.
 */
internal object FlacMetadataWriter {
    /**
     * Rewrites the Vorbis comment block.
     *
     * Pass `null` for [artworks] to leave the existing picture blocks untouched; an empty list
     * removes them.
     */
    fun write(
        source: WritableRandomAccessSource,
        metadata: FlacMetadata,
        artworks: List<Artwork>? = metadata.artworks,
    ) {
        val commentPayload = metadata.tag.encode()
        val preserved = metadata.blocks.filter { block ->
            when (block.type) {
                TYPE_VORBIS_COMMENT, TYPE_PADDING -> false
                TYPE_PICTURE -> artworks == null
                else -> true
            }
        }
        val picturePayloads = artworks?.map(::encodeFlacPicture).orEmpty()
        val baseSize = FLAC_MAGIC.size.toLong() +
            preserved.sumOf { BLOCK_HEADER_SIZE + it.length.toLong() } +
            picturePayloads.sumOf { BLOCK_HEADER_SIZE + it.size.toLong() } +
            BLOCK_HEADER_SIZE + commentPayload.size
        val newSize = maxOf(baseSize + BLOCK_HEADER_SIZE, metadata.audioStartOffset)
        val padding = (newSize - baseSize - BLOCK_HEADER_SIZE).toInt()

        source.moveTail(metadata.audioStartOffset, newSize)
        var position = 0L
        source.writeAt(position, FLAC_MAGIC)
        position += FLAC_MAGIC.size
        preserved.forEach { block ->
            val payload = ByteArray(block.length)
            source.readFully(block.offset, payload, payload.size)
            position = writeBlock(source, position, block.type, payload, last = false)
        }
        picturePayloads.forEach { payload ->
            position = writeBlock(source, position, TYPE_PICTURE, payload, last = false)
        }
        position = writeBlock(source, position, TYPE_VORBIS_COMMENT, commentPayload, last = false)
        position = writePadding(source, position, padding)
        check(position == newSize) { "FLAC metadata size mismatch: $position != $newSize" }
        source.flush()
    }

    private fun writeBlock(
        source: WritableRandomAccessSource,
        offset: Long,
        type: Int,
        payload: ByteArray,
        last: Boolean,
    ): Long {
        val header = ByteArray(BLOCK_HEADER_SIZE)
        header[0] = (((if (last) LAST_BLOCK_FLAG else 0) or (type and BLOCK_TYPE_MASK))).toByte()
        header[1] = (payload.size ushr 16).toByte()
        header[2] = (payload.size ushr 8).toByte()
        header[3] = payload.size.toByte()
        source.writeAt(offset, header)
        if (payload.isNotEmpty()) source.writeAt(offset + BLOCK_HEADER_SIZE, payload)
        return offset + BLOCK_HEADER_SIZE + payload.size
    }

    private fun writePadding(source: WritableRandomAccessSource, offset: Long, padding: Int): Long {
        val header = ByteArray(BLOCK_HEADER_SIZE)
        header[0] = (LAST_BLOCK_FLAG or TYPE_PADDING).toByte()
        header[1] = (padding ushr 16).toByte()
        header[2] = (padding ushr 8).toByte()
        header[3] = padding.toByte()
        source.writeAt(offset, header)
        var written = 0
        val zeros = ByteArray(minOf(padding, ZERO_CHUNK_SIZE))
        while (written < padding) {
            val count = minOf(zeros.size, padding - written)
            source.writeAt(offset + BLOCK_HEADER_SIZE + written, zeros, 0, count)
            written += count
        }
        return offset + BLOCK_HEADER_SIZE + padding
    }
}

private fun parseStreamInfo(data: ByteArray): FlacStreamInfo {
    val minBlockSize = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
    val maxBlockSize = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
    val sampleRate = ((data[10].toInt() and 0xFF) shl 12) or
        ((data[11].toInt() and 0xFF) shl 4) or
        ((data[12].toInt() and 0xF0) ushr 4)
    val channels = ((data[12].toInt() ushr 1) and 0x07) + 1
    val bitsPerSample = (((data[12].toInt() and 0x01) shl 4) or ((data[13].toInt() and 0xF0) ushr 4)) + 1
    val totalSamples = ((data[13].toLong() and 0x0F) shl 32) or
        ((data[14].toLong() and 0xFF) shl 24) or
        ((data[15].toLong() and 0xFF) shl 16) or
        ((data[16].toLong() and 0xFF) shl 8) or
        (data[17].toLong() and 0xFF)
    return FlacStreamInfo(minBlockSize, maxBlockSize, sampleRate, channels, bitsPerSample, totalSamples)
}

private val FLAC_MAGIC = byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte())
private const val BLOCK_HEADER_SIZE = 4
private const val LAST_BLOCK_FLAG = 0x80
private const val BLOCK_TYPE_MASK = 0x7F
private const val TYPE_STREAMINFO = 0
private const val TYPE_PADDING = 1
private const val TYPE_VORBIS_COMMENT = 4
private const val TYPE_PICTURE = 6
private const val STREAMINFO_SIZE = 34
private const val ZERO_CHUNK_SIZE = 8192
