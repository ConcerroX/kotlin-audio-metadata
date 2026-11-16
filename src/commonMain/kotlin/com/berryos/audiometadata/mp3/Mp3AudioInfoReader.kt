package com.berryos.audiometadata.mp3

import com.berryos.audiometadata.AudioInfo
import com.berryos.audiometadata.io.RandomAccessSource

/**
 * Reads MPEG audio properties from the first frame after the tag.
 *
 * Xing/Info and VBRI headers are used for duration when present; otherwise the duration is
 * estimated from the file size and the first frame's bitrate (accurate for CBR files).
 */
internal object Mp3AudioInfoReader {
    fun read(source: RandomAccessSource, audioStart: Long): AudioInfo? {
        val end = if (hasId3v1(source)) source.size - ID3V1_SIZE else source.size
        if (audioStart >= end) return null
        val frameOffset = findFrame(source, audioStart, end) ?: return null
        val header = readHeader(source, frameOffset) ?: return null

        val vbr = readVbrHeader(source, frameOffset, header)
        val durationMs = vbr?.frameCount?.let { frames ->
            frames * header.samplesPerFrame * 1000L / header.sampleRate
        } ?: run {
            if (header.bitrateKbps <= 0) return null
            (end - frameOffset) * 8L / header.bitrateKbps
        }
        val averageBitrate = when {
            vbr?.byteCount != null && durationMs > 0 -> (vbr.byteCount * 8 / durationMs).toInt()
            else -> header.bitrateKbps
        }
        return AudioInfo(
            bitrateKbps = averageBitrate,
            sampleRateHz = header.sampleRate,
            channels = header.channels,
            durationMs = durationMs,
        )
    }

    private fun findFrame(source: RandomAccessSource, start: Long, end: Long): Long? {
        val limit = minOf(end, start + MAX_SYNC_SEARCH)
        val length = (limit - start).toInt()
        if (length < FRAME_HEADER_SIZE) return null
        val buffer = ByteArray(length)
        val read = source.readAt(start, buffer)
        if (read < FRAME_HEADER_SIZE) return null
        for (index in 0..read - FRAME_HEADER_SIZE) {
            if (buffer[index].toInt() and 0xFF != 0xFF) continue
            if (parseHeader(buffer, index) != null) return start + index
        }
        return null
    }

    private fun readHeader(source: RandomAccessSource, offset: Long): FrameHeader? {
        val bytes = ByteArray(FRAME_HEADER_SIZE)
        if (source.readAt(offset, bytes) < FRAME_HEADER_SIZE) return null
        return parseHeader(bytes, 0)
    }

    private fun parseHeader(bytes: ByteArray, offset: Int): FrameHeader? {
        val b0 = bytes[offset].toInt() and 0xFF
        val b1 = bytes[offset + 1].toInt() and 0xFF
        if (b0 != 0xFF || b1 and 0xE0 != 0xE0) return null
        val versionBits = (b1 ushr 3) and 0x03
        if (versionBits == 1) return null
        val layerBits = (b1 ushr 1) and 0x03
        if (layerBits == 0) return null
        val layerIndex = 3 - layerBits

        val b2 = bytes[offset + 2].toInt() and 0xFF
        val bitrateIndex = (b2 ushr 4) and 0x0F
        val sampleRateIndex = (b2 ushr 2) and 0x03
        if (bitrateIndex == 0 || bitrateIndex == 15 || sampleRateIndex == 3) return null
        val padding = (b2 ushr 1) and 0x01

        val bitrates = if (versionBits == 3) BITRATES_MPEG1[layerIndex] else BITRATES_MPEG2[layerIndex]
        val bitrate = bitrates[bitrateIndex]
        val sampleRate = SAMPLE_RATES[versionBits][sampleRateIndex]
        val b3 = bytes[offset + 3].toInt() and 0xFF
        val channels = if ((b3 ushr 6) == 3) 1 else 2

        val samplesPerFrame = when (layerIndex) {
            0 -> 384
            1 -> 1152
            else -> if (versionBits == 3) 1152 else 576
        }
        val frameLength = when (layerIndex) {
            0 -> (12 * bitrate * 1000 / sampleRate + padding) * 4
            1 -> 144 * bitrate * 1000 / sampleRate + padding
            else -> (if (versionBits == 3) 144 else 72) * bitrate * 1000 / sampleRate + padding
        }
        val sideInfoSize = when {
            versionBits == 3 -> if (channels == 1) 17 else 32
            else -> if (channels == 1) 9 else 17
        }
        return FrameHeader(
            versionBits = versionBits,
            bitrateKbps = bitrate,
            sampleRate = sampleRate,
            channels = channels,
            frameLength = frameLength,
            samplesPerFrame = samplesPerFrame,
            sideInfoSize = sideInfoSize,
        )
    }

    private fun readVbrHeader(source: RandomAccessSource, frameOffset: Long, header: FrameHeader): VbrHeader? {
        val xingOffset = frameOffset + FRAME_HEADER_SIZE + header.sideInfoSize
        val xing = ByteArray(16)
        if (source.readAt(xingOffset, xing) >= xing.size &&
            (xing.startsWithAscii("Xing") || xing.startsWithAscii("Info"))
        ) {
            val flags = xing.readBigEndianInt(4)
            var position = 8
            var frames: Long? = null
            var bytes: Long? = null
            if (flags and 0x01 != 0) {
                frames = xing.readBigEndianInt(position).toLong() and 0xFFFFFFFFL
                position += 4
            }
            if (flags and 0x02 != 0) {
                bytes = xing.readBigEndianInt(position).toLong() and 0xFFFFFFFFL
            }
            return VbrHeader(frames, bytes)
        }

        val vbriOffset = frameOffset + FRAME_HEADER_SIZE + VBRI_OFFSET
        val vbri = ByteArray(18)
        if (source.readAt(vbriOffset, vbri) >= vbri.size && vbri.startsWithAscii("VBRI")) {
            val bytes = vbri.readBigEndianInt(10).toLong() and 0xFFFFFFFFL
            val frames = vbri.readBigEndianInt(14).toLong() and 0xFFFFFFFFL
            return VbrHeader(frames, bytes)
        }
        return null
    }

    private fun hasId3v1(source: RandomAccessSource): Boolean {
        if (source.size < ID3V1_SIZE) return false
        val tag = ByteArray(3)
        if (source.readAt(source.size - ID3V1_SIZE, tag) < tag.size) return false
        return tag.startsWithAscii("TAG")
    }
}

private class FrameHeader(
    val versionBits: Int,
    val bitrateKbps: Int,
    val sampleRate: Int,
    val channels: Int,
    val frameLength: Int,
    val samplesPerFrame: Int,
    val sideInfoSize: Int,
)

private class VbrHeader(val frameCount: Long?, val byteCount: Long?)

private fun ByteArray.startsWithAscii(value: String): Boolean {
    if (size < value.length) return false
    for (index in value.indices) {
        if (this[index] != value[index].code.toByte()) return false
    }
    return true
}

private fun ByteArray.readBigEndianInt(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 24) or
        ((this[offset + 1].toInt() and 0xFF) shl 16) or
        ((this[offset + 2].toInt() and 0xFF) shl 8) or
        (this[offset + 3].toInt() and 0xFF)

private val BITRATES_MPEG1 = arrayOf(
    intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448),
    intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384),
    intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320),
)

private val BITRATES_MPEG2 = arrayOf(
    intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256),
    intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160),
    intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160),
)

private val SAMPLE_RATES = arrayOf(
    intArrayOf(11025, 12000, 8000),
    intArrayOf(0, 0, 0),
    intArrayOf(22050, 24000, 16000),
    intArrayOf(44100, 48000, 32000),
)

private const val FRAME_HEADER_SIZE = 4
private const val ID3V1_SIZE = 128
private const val VBRI_OFFSET = 32
private const val MAX_SYNC_SEARCH = 64L * 1024L
