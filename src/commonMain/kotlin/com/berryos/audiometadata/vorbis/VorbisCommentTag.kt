package com.berryos.audiometadata.vorbis

import com.berryos.audiometadata.AudioFormat
import com.berryos.audiometadata.AudioInfo
import com.berryos.audiometadata.AudioMetadata
import com.berryos.audiometadata.ReplayGain

/** A single `KEY=value` entry of a Vorbis comment block. */
data class VorbisComment(val key: String, val value: String)

/**
 * Mutable view of a Vorbis comment block, shared by FLAC and Ogg containers.
 *
 * Keys are matched case-insensitively, as required by the Vorbis comment specification.
 */
class VorbisCommentTag internal constructor(
    var vendor: String = DEFAULT_VENDOR,
    comments: List<VorbisComment> = emptyList(),
) {
    private val entries = comments.toMutableList()

    val comments: List<VorbisComment>
        get() = entries

    fun value(key: String): String? = entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value

    fun values(key: String): List<String> = entries
        .filter { it.key.equals(key, ignoreCase = true) }
        .map(VorbisComment::value)

    fun set(key: String, value: String) {
        if (value.isEmpty()) {
            remove(key)
            return
        }
        val index = entries.indexOfFirst { it.key.equals(key, ignoreCase = true) }
        if (index < 0) {
            entries += VorbisComment(key, value)
            return
        }
        entries[index] = VorbisComment(entries[index].key, value)
        entries.removeAll { it.key.equals(key, ignoreCase = true) && it !== entries[index] }
    }

    fun add(key: String, value: String) {
        entries += VorbisComment(key, value)
    }

    fun setValues(key: String, values: List<String>) {
        remove(key)
        values.forEach { add(key, it) }
    }

    fun remove(key: String) {
        entries.removeAll { it.key.equals(key, ignoreCase = true) }
    }

    var title: String
        get() = value("TITLE").orEmpty()
        set(value) = set("TITLE", value)

    var artist: String
        get() = value("ARTIST").orEmpty()
        set(value) = set("ARTIST", value)

    var album: String
        get() = value("ALBUM").orEmpty()
        set(value) = set("ALBUM", value)

    var albumArtist: String
        get() = value("ALBUMARTIST").orEmpty()
        set(value) = set("ALBUMARTIST", value)

    var date: String
        get() = value("DATE").orEmpty()
        set(value) = set("DATE", value)

    var genre: String
        get() = value("GENRE").orEmpty()
        set(value) = set("GENRE", value)

    var composer: String
        get() = value("COMPOSER").orEmpty()
        set(value) = set("COMPOSER", value)

    var comment: String
        get() = value("COMMENT").orEmpty()
        set(value) = set("COMMENT", value)

    val replayGain: ReplayGain?
        get() {
            val trackGain = value("REPLAYGAIN_TRACK_GAIN")
            val trackPeak = value("REPLAYGAIN_TRACK_PEAK")
            val albumGain = value("REPLAYGAIN_ALBUM_GAIN")
            val albumPeak = value("REPLAYGAIN_ALBUM_PEAK")
            if (trackGain == null && trackPeak == null && albumGain == null && albumPeak == null) return null
            return ReplayGain(trackGain, trackPeak, albumGain, albumPeak)
        }

    fun toAudioMetadata(format: AudioFormat, audioInfo: AudioInfo? = null): AudioMetadata {
        val trackPair = parseNumberPair(value("TRACKNUMBER"))
        val discPair = parseNumberPair(value("DISCNUMBER"))
        return AudioMetadata(
            format = format,
            title = value("TITLE")?.takeIf(String::isNotEmpty),
            artists = values("ARTIST"),
            album = value("ALBUM")?.takeIf(String::isNotEmpty),
            albumArtist = value("ALBUMARTIST")?.takeIf(String::isNotEmpty),
            trackNumber = trackPair.first,
            trackTotal = value("TRACKTOTAL")?.toIntOrNull() ?: trackPair.second,
            discNumber = discPair.first,
            discTotal = value("DISCTOTAL")?.toIntOrNull() ?: discPair.second,
            year = value("DATE")?.takeIf(String::isNotEmpty),
            genres = values("GENRE"),
            composers = values("COMPOSER"),
            comments = values("COMMENT"),
            lyrics = (value("LYRICS") ?: value("UNSYNCEDLYRICS"))?.takeIf(String::isNotEmpty),
            replayGain = replayGain,
            audioInfo = audioInfo,
        )
    }

    internal fun encode(): ByteArray {
        val vendorBytes = vendor.encodeToByteArray()
        val encodedComments = entries.map { "${it.key}=${it.value}".encodeToByteArray() }
        val size = 4 + vendorBytes.size + 4 + encodedComments.sumOf { 4 + it.size }
        val output = ByteArray(size)
        var offset = 0
        offset = output.writeLittleEndianInt(offset, vendorBytes.size)
        vendorBytes.copyInto(output, offset)
        offset += vendorBytes.size
        offset = output.writeLittleEndianInt(offset, encodedComments.size)
        encodedComments.forEach { comment ->
            offset = output.writeLittleEndianInt(offset, comment.size)
            comment.copyInto(output, offset)
            offset += comment.size
        }
        check(offset == size) { "Vorbis comment size mismatch" }
        return output
    }

    companion object {
        fun create(): VorbisCommentTag = VorbisCommentTag()

        internal fun decode(bytes: ByteArray): VorbisCommentTag? {
            var offset = 0
            if (bytes.size < 8) return null
            val vendorLength = bytes.readLittleEndianInt(offset) ?: return null
            offset += 4
            if (vendorLength < 0 || offset + vendorLength > bytes.size) return null
            val vendor = bytes.decodeToString(offset, offset + vendorLength)
            offset += vendorLength
            val count = bytes.readLittleEndianInt(offset) ?: return null
            offset += 4
            if (count < 0) return null
            val comments = ArrayList<VorbisComment>(count)
            repeat(count) {
                val length = bytes.readLittleEndianInt(offset) ?: return null
                offset += 4
                if (length < 0 || offset + length > bytes.size) return null
                val entry = bytes.decodeToString(offset, offset + length)
                offset += length
                val separator = entry.indexOf('=')
                if (separator > 0) {
                    comments += VorbisComment(entry.substring(0, separator), entry.substring(separator + 1))
                }
            }
            return VorbisCommentTag(vendor, comments)
        }
    }
}

private fun parseNumberPair(value: String?): Pair<Int?, Int?> {
    if (value.isNullOrEmpty()) return null to null
    val parts = value.split('/', limit = 2)
    return parts.getOrNull(0)?.trim()?.toIntOrNull() to parts.getOrNull(1)?.trim()?.toIntOrNull()
}

private fun ByteArray.readLittleEndianInt(offset: Int): Int? {
    if (offset < 0 || offset + 4 > size) return null
    return (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)
}

private fun ByteArray.writeLittleEndianInt(offset: Int, value: Int): Int {
    this[offset] = value.toByte()
    this[offset + 1] = (value ushr 8).toByte()
    this[offset + 2] = (value ushr 16).toByte()
    this[offset + 3] = (value ushr 24).toByte()
    return offset + 4
}

private const val DEFAULT_VENDOR = "kotlin-audio-metadata"
