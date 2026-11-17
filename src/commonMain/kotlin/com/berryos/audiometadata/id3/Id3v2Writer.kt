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
internal object Id3v2Writer {
    fun write(
        source: WritableRandomAccessSource,
        tag: Id3v2Tag,
        audioStart: Long,
        originalTagSize: Int,
    ) {
        val framesBytes = encodeFrames(tag)
        val bodySize = maxOf(originalTagSize, framesBytes.size)
        val newAudioStart = HEADER_SIZE + bodySize.toLong()
        if (newAudioStart != audioStart) source.moveTail(audioStart, newAudioStart)
        source.writeAt(0, header(tag.version, bodySize))
        source.writeAt(HEADER_SIZE.toLong(), framesBytes)
        writePadding(source, HEADER_SIZE + framesBytes.size.toLong(), bodySize - framesBytes.size)
        source.flush()
    }

    /** Serializes the tag as header plus frames, without padding; used by embedded ID3 chunks. */
    internal fun encode(tag: Id3v2Tag): ByteArray {
        val framesBytes = encodeFrames(tag)
        return header(tag.version, framesBytes.size) + framesBytes
    }

    private fun encodeFrames(tag: Id3v2Tag): ByteArray =
        tag.frames.fold(ByteArray(0)) { accumulated, frame -> accumulated + encodeFrame(tag.version, frame) }

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
        if (version == Id3Version.V22) return encodeV22Frame(frame)
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

    private fun encodeV22Frame(frame: Id3v2Frame): ByteArray {
        val id = v22FrameId(frame.id) ?: frame.id
        if (id.length != V22_FRAME_ID_SIZE) {
            throw AudioMetadataException("Frame ${frame.id} cannot be represented in ID3v2.2")
        }
        val content = frame.content
        val header = ByteArray(V22_FRAME_HEADER_SIZE)
        id.encodeToByteArray().copyInto(header)
        header[3] = (content.size ushr 16).toByte()
        header[4] = (content.size ushr 8).toByte()
        header[5] = content.size.toByte()
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
private const val V22_FRAME_HEADER_SIZE = 6
private const val V22_FRAME_ID_SIZE = 3
private const val PADDING_CHUNK = 8192
private const val V23_FLAG_GROUPING = 0x20
private const val V24_FLAG_GROUPING = 0x40
private const val V24_FLAG_UNSYNCHRONISATION = 0x02
private const val V24_FLAG_DATA_LENGTH = 0x01
