package com.berryos.audiometadata.opus

import com.berryos.audiometadata.AudioFormat
import com.berryos.audiometadata.AudioInfo
import com.berryos.audiometadata.AudioMetadata
import com.berryos.audiometadata.io.RandomAccessSource
import com.berryos.audiometadata.io.WritableRandomAccessSource
import com.berryos.audiometadata.io.moveTail
import com.berryos.audiometadata.ogg.OggPage
import com.berryos.audiometadata.ogg.OggPages
import com.berryos.audiometadata.ogg.readLittleEndianInt
import com.berryos.audiometadata.ogg.readLittleEndianShort
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
        val pages = walkPages(source)
        if (pages.size < 2) return null
        val headPage = pages.first()
        if (!headPage.isBos) return null
        val head = OggPages.readPayload(source, headPage)
        if (!head.startsWithAscii(OPUS_HEAD) || head.size < OPUS_HEAD_MIN_SIZE) return null

        val packet = findCommentPacket(source, pages) ?: return null
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
 * The comment packet is expected to end on a page boundary, which is how standard Opus encoders
 * write it; a page with trailing packets is rejected instead of silently dropping them.
 */
object OpusMetadataWriter {
    fun write(source: WritableRandomAccessSource, metadata: OpusMetadata) {
        val pages = walkPages(source)
        if (pages.size < 2) return
        val headPage = pages.first()
        val comment = findCommentPacket(source, pages) ?: return
        check(!comment.trailingBytes) { "Opus tag page contains packets after the comment packet" }
        val lastTagPage = pages[comment.lastPageIndex]
        val audioStart = lastTagPage.offset + lastTagPage.size

        val newPages = OggPages.pagePacket(buildCommentPacket(metadata.tag), headPage.serialNumber, 1)
        val newAudioStart = headPage.size.toLong() + newPages.sumOf { it.size.toLong() }
        source.moveTail(audioStart, newAudioStart)

        var writeOffset = headPage.size.toLong()
        newPages.forEach { page ->
            source.writeAt(writeOffset, page)
            writeOffset += page.size
        }

        var sequence = newPages.size + 1
        var offset = newAudioStart
        while (offset < source.size) {
            val size = OggPages.renumberPage(source, offset, sequence++)
            if (size <= 0) break
            offset += size
        }
        source.flush()
    }
}

private class CommentPacket(val bytes: ByteArray, val lastPageIndex: Int, val trailingBytes: Boolean)

private fun walkPages(source: RandomAccessSource): List<OggPage> {
    val pages = ArrayList<OggPage>()
    var offset = 0L
    while (offset < source.size) {
        val page = OggPages.read(source, offset) ?: break
        pages += page
        offset += page.size
    }
    return pages
}

private fun findCommentPacket(source: RandomAccessSource, pages: List<OggPage>): CommentPacket? {
    if (pages.size < 2) return null
    val chunks = ArrayList<ByteArray>()
    for (index in 1 until pages.size) {
        val page = pages[index]
        val payload = OggPages.readPayload(source, page)
        var consumed = 0
        for (segment in page.segmentTable) {
            if (consumed + segment > payload.size) return null
            chunks += payload.copyOfRange(consumed, consumed + segment)
            consumed += segment
            if (segment < 255) {
                return CommentPacket(concatenate(chunks), index, trailingBytes = consumed < payload.size)
            }
        }
    }
    return null
}

private fun buildCommentPacket(tag: VorbisCommentTag): ByteArray =
    OPUS_TAGS_BYTES + tag.encode()

private fun concatenate(chunks: List<ByteArray>): ByteArray {
    val total = chunks.sumOf { it.size }
    val result = ByteArray(total)
    var offset = 0
    chunks.forEach { chunk ->
        chunk.copyInto(result, offset)
        offset += chunk.size
    }
    return result
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
