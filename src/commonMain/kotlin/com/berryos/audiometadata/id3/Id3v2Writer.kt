package com.berryos.audiometadata.id3

import com.berryos.audiometadata.AudioMetadataException
import com.berryos.audiometadata.Id3Version
import com.berryos.audiometadata.io.WritableRandomAccessSource
import com.berryos.audiometadata.io.moveTail

/**
 * Writes an [Id3v2Tag] back to the start of a file.
 *
 * Existing padding is reused, so the audio stream stays in place whenever the new tag fits into
 * the old tag size. Otherwise the audio is shifted by the difference. Frames that were not edited
 * are written back with their stored flags and content.
 */
object Id3v2Writer {
    fun write(
        source: WritableRandomAccessSource,
        tag: Id3v2Tag,
        audioStart: Long,
        originalTagSize: Int,
    ) {
        validate(tag)
        val framesBytes = tag.frames.fold(ByteArray(0)) { accumulated, frame ->
            accumulated + encodeFrame(tag.version, frame)
        }
        val bodySize = maxOf(originalTagSize, framesBytes.size)
        val newAudioStart = HEADER_SIZE + bodySize.toLong()
        if (newAudioStart != audioStart) source.moveTail(audioStart, newAudioStart)
        source.writeAt(0, header(tag.version, bodySize))
        source.writeAt(HEADER_SIZE.toLong(), framesBytes)
        writePadding(source, HEADER_SIZE + framesBytes.size.toLong(), bodySize - framesBytes.size)
        source.flush()
    }

    private fun validate(tag: Id3v2Tag) {
        val error = when {
            tag.version == Id3Version.V22 -> "Writing ID3v2.2 tags is not supported yet"
            tag.extendedHeaderSize > 0 -> "Rewriting a tag with an extended header is not supported yet"
            tag.version == Id3Version.V23 && tag.unsynchronised ->
                "Rewriting an unsynchronised ID3v2.3 tag is not supported yet"

            else -> null
        }
        if (error != null) throw AudioMetadataException(error)
    }

    private fun header(version: Id3Version, bodySize: Int): ByteArray {
        val header = ByteArray(HEADER_SIZE)
        header[0] = 'I'.code.toByte()
        header[1] = 'D'.code.toByte()
        header[2] = '3'.code.toByte()
        header[3] = when (version) {
            Id3Version.V22 -> 2
            Id3Version.V23 -> 3
            Id3Version.V24 -> 4
        }.toByte()
        header[4] = 0
        header[5] = 0
        writeSyncSafe(header, 6, bodySize)
        return header
    }

    private fun encodeFrame(version: Id3Version, frame: Id3v2Frame): ByteArray {
        frame.rawHeader?.let { header -> return header + frame.content }
        val prefix = ArrayList<ByteArray>()
        if (version == Id3Version.V24) {
            frame.groupId?.let { groupId -> prefix += byteArrayOf(groupId.toByte()) }
            val dataLength = frame.dataLengthIndicator
            if (dataLength != null && dataLength >= 0) {
                val indicator = ByteArray(4)
                writeSyncSafe(indicator, 0, dataLength)
                prefix += indicator
            }
        } else {
            frame.groupId?.let { groupId -> prefix += byteArrayOf(groupId.toByte()) }
        }
        val content = prefix.fold(ByteArray(0)) { accumulated, chunk -> accumulated + chunk } + frame.content
        val header = ByteArray(FRAME_HEADER_SIZE)
        frame.id.encodeToByteArray().copyInto(header)
        if (version == Id3Version.V24) {
            writeSyncSafe(header, 4, content.size)
        } else {
            writeInt(header, 4, content.size)
        }
        header[8] = frame.statusFlags.toByte()
        header[9] = formatFlags(version, frame).toByte()
        return header + content
    }

    private fun formatFlags(version: Id3Version, frame: Id3v2Frame): Int {
        if (version == Id3Version.V24) {
            var flags = frame.formatFlags and V24_FLAG_UNSYNCHRONISATION.inv()
            flags = if (frame.groupId != null) {
                flags or V24_FLAG_GROUPING
            } else {
                flags and V24_FLAG_GROUPING.inv()
            }
            val dataLength = frame.dataLengthIndicator
            flags = if (dataLength != null && dataLength >= 0) {
                flags or V24_FLAG_DATA_LENGTH
            } else {
                flags and V24_FLAG_DATA_LENGTH.inv()
            }
            return flags and (V24_FLAG_GROUPING or V24_FLAG_DATA_LENGTH)
        }
        return if (frame.groupId != null) V23_FLAG_GROUPING else 0
    }

    private fun writePadding(source: WritableRandomAccessSource, offset: Long, length: Int) {
        if (length <= 0) return
        val zeros = ByteArray(minOf(length, PADDING_CHUNK))
        var written = 0
        while (written < length) {
            val count = minOf(zeros.size, length - written)
            source.writeAt(offset + written, zeros, 0, count)
            written += count
        }
    }

    private fun writeSyncSafe(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = ((value ushr 21) and 0x7F).toByte()
        bytes[offset + 1] = ((value ushr 14) and 0x7F).toByte()
        bytes[offset + 2] = ((value ushr 7) and 0x7F).toByte()
        bytes[offset + 3] = (value and 0x7F).toByte()
    }

    private fun writeInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }
}

private const val HEADER_SIZE = 10
private const val FRAME_HEADER_SIZE = 10
private const val PADDING_CHUNK = 8192
private const val V23_FLAG_GROUPING = 0x20
private const val V24_FLAG_GROUPING = 0x40
private const val V24_FLAG_UNSYNCHRONISATION = 0x02
private const val V24_FLAG_DATA_LENGTH = 0x01
