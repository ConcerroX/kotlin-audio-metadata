package com.berryos.audiometadata.opus

import com.berryos.audiometadata.AudioFormat
import com.berryos.audiometadata.AudioInfo
import com.berryos.audiometadata.AudioMetadata
import com.berryos.audiometadata.io.RandomAccessSource
import com.berryos.audiometadata.io.WritableRandomAccessSource
import com.berryos.audiometadata.ogg.OggPages
import com.berryos.audiometadata.ogg.findOggPacket
import com.berryos.audiometadata.ogg.readLittleEndianInt
import com.berryos.audiometadata.ogg.readLittleEndianShort
import com.berryos.audiometadata.ogg.replaceOggPacket
import com.berryos.audiometadata.ogg.walkOggPages
import com.berryos.audiometadata.vorbis.VorbisCommentTag

/** Parsed Opus tags and stream info; the OpusTags comment block is exposed as [tag]. */
class OpusMetadata internal constructor(
    val tag: VorbisCommentTag,
    val audioInfo: AudioInfo?,
) {
    fun toAudioMetadata(): AudioMetadata = tag.toAudioMetadata(AudioFormat.Opus, audioInfo)
}

/** Reads Opus tags from an Ogg Opus stream. */
object OpusMetadataReader {
    fun read(source: RandomAccessSource): OpusMetadata? {
        val pages = walkOggPages(source)
        if (pages.size < 2) return null
        val headPage = pages.first()
        if (!headPage.isBos) return null
        val head = OggPages.readPayload(source, headPage)
        if (!head.startsWithAscii(OPUS_HEAD) || head.size < OPUS_HEAD_MIN_SIZE) return null

        val packet = findOggPacket(source, pages, 1) ?: return null
        if (!packet.bytes.startsWithAscii(OPUS_TAGS)) return null
        val tag = VorbisCommentTag.decode(packet.bytes.copyOfRange(OPUS_TAGS.length, packet.bytes.size))
            ?: VorbisCommentTag.create()

        val channels = head[9].toInt() and 0xFF
        val preSkip = head.readLittleEndianShort(10)
        val inputSampleRate = head.readLittleEndianInt(12)
        val lastGranule = pages.lastOrNull { it.granulePosition > 0 }?.granulePosition ?: 0L
        val samples = (lastGranule - preSkip).coerceAtLeast(0L)
        val audioInfo = AudioInfo(
            sampleRateHz = if (inputSampleRate > 0) inputSampleRate else OPUS_GRANULE_RATE,
            channels = channels,
            durationMs = samples * 1000 / OPUS_GRANULE_RATE,
        )
        return OpusMetadata(tag, audioInfo)
    }
}

/**
 * Rewrites the OpusTags packet and renumbers the following pages.
 *
 * The comment packet is expected to end on a page boundary; a page with trailing packets is
 * rejected instead of silently dropping them.
 */
object OpusMetadataWriter {
    fun write(source: WritableRandomAccessSource, metadata: OpusMetadata) {
        val pages = walkOggPages(source)
        if (pages.size < 2) return
        val packet = findOggPacket(source, pages, 1) ?: return
        replaceOggPacket(source, pages, packet, OPUS_TAGS_BYTES + metadata.tag.encode())
    }
}

private fun ByteArray.startsWithAscii(value: String): Boolean {
    if (size < value.length) return false
    for (index in value.indices) {
        if (this[index] != value[index].code.toByte()) return false
    }
    return true
}

private const val OPUS_HEAD = "OpusHead"
private const val OPUS_TAGS = "OpusTags"
private val OPUS_TAGS_BYTES = OPUS_TAGS.encodeToByteArray()
private const val OPUS_HEAD_MIN_SIZE = 19
private const val OPUS_GRANULE_RATE = 48_000
