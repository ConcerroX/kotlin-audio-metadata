package com.berryos.audiometadata.vorbis

import com.berryos.audiometadata.AudioFormat
import com.berryos.audiometadata.AudioInfo
import com.berryos.audiometadata.AudioMetadata
import com.berryos.audiometadata.io.RandomAccessSource
import com.berryos.audiometadata.io.WritableRandomAccessSource
import com.berryos.audiometadata.ogg.OggPages
import com.berryos.audiometadata.ogg.findOggPacket
import com.berryos.audiometadata.ogg.findOggPackets
import com.berryos.audiometadata.ogg.readLittleEndianInt
import com.berryos.audiometadata.ogg.replaceOggPackets
import com.berryos.audiometadata.ogg.walkOggPages

/** Parsed Ogg Vorbis tags and stream info; the comment block is exposed as [tag]. */
class OggVorbisMetadata internal constructor(
    val tag: VorbisCommentTag,
    val audioInfo: AudioInfo?,
) {
    fun toAudioMetadata(): AudioMetadata = tag.toAudioMetadata(AudioFormat.Ogg, audioInfo)
}

/** Reads Vorbis comments from an Ogg Vorbis stream. */
object OggVorbisMetadataReader {
    fun read(source: RandomAccessSource): OggVorbisMetadata? {
        val pages = walkOggPages(source)
        if (pages.size < 2) return null
        val headPage = pages.first()
        if (!headPage.isBos) return null
        val head = OggPages.readPayload(source, headPage)
        if (!head.startsWith(ID_HEADER) || head.size < ID_HEADER_SIZE) return null

        val packet = findOggPacket(source, pages, 1) ?: return null
        if (!packet.bytes.startsWith(COMMENT_HEADER)) return null
        val tag = VorbisCommentTag.decode(packet.bytes.copyOfRange(COMMENT_HEADER.size, packet.bytes.size))
            ?: VorbisCommentTag.create()

        val channels = head[11].toInt() and 0xFF
        val sampleRate = head.readLittleEndianInt(12)
        val nominalBitrate = head.readLittleEndianInt(20)
        val lastGranule = pages.lastOrNull { it.granulePosition > 0 }?.granulePosition ?: 0L
        val audioInfo = AudioInfo(
            bitrateKbps = if (nominalBitrate > 0) nominalBitrate / 1000 else null,
            sampleRateHz = sampleRate,
            channels = channels,
            durationMs = if (sampleRate > 0) lastGranule * 1000 / sampleRate else 0,
        )
        return OggVorbisMetadata(tag, audioInfo)
    }
}

/**
 * Rewrites the Vorbis comment packet and renumbers the setup and audio pages.
 *
 * As with Opus, the comment packet is expected to end on a page boundary.
 */
object OggVorbisMetadataWriter {
    fun write(source: WritableRandomAccessSource, metadata: OggVorbisMetadata) {
        val pages = walkOggPages(source)
        if (pages.size < 2) return
        // The comment and setup headers often share a page; rebuild both packets together.
        val header = findOggPackets(source, pages, 1, 2) ?: return
        val setup = header.packets.getOrNull(1) ?: return
        replaceOggPackets(
            source,
            pages,
            header,
            listOf(COMMENT_HEADER + metadata.tag.encode(), setup),
        )
    }
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
    if (size < prefix.size) return false
    for (index in prefix.indices) {
        if (this[index] != prefix[index]) return false
    }
    return true
}

private val ID_HEADER = byteArrayOf(1) + "vorbis".encodeToByteArray()
private val COMMENT_HEADER = byteArrayOf(3) + "vorbis".encodeToByteArray()
private const val ID_HEADER_SIZE = 30
