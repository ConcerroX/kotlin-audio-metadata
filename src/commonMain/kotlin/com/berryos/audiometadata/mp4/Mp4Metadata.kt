package com.berryos.audiometadata.mp4

import com.berryos.audiometadata.AudioFormat
import com.berryos.audiometadata.AudioInfo
import com.berryos.audiometadata.AudioMetadata
import com.berryos.audiometadata.Artwork
import com.berryos.audiometadata.io.RandomAccessSource
import com.berryos.audiometadata.io.readFully

/** Parsed MP4/M4A metadata. Writing is not supported yet. */
class Mp4Metadata internal constructor(
    val tags: Mp4Tags,
    val audioInfo: AudioInfo?,
) {
    val artworks: List<Artwork>
        get() = tags.artworks

    fun toAudioMetadata(): AudioMetadata = tags.toAudioMetadata(audioInfo)
}

/** Read-only view of the common iTunes `ilst` fields. */
data class Mp4Tags(
    val title: String? = null,
    val artists: List<String> = emptyList(),
    val album: String? = null,
    val albumArtist: String? = null,
    val trackNumber: Int? = null,
    val trackTotal: Int? = null,
    val discNumber: Int? = null,
    val discTotal: Int? = null,
    val year: String? = null,
    val genres: List<String> = emptyList(),
    val composers: List<String> = emptyList(),
    val comments: List<String> = emptyList(),
    val artworks: List<Artwork> = emptyList(),
) {
    fun toAudioMetadata(audioInfo: AudioInfo? = null): AudioMetadata = AudioMetadata(
        format = AudioFormat.Mp4,
        title = title,
        artists = artists,
        album = album,
        albumArtist = albumArtist,
        trackNumber = trackNumber,
        trackTotal = trackTotal,
        discNumber = discNumber,
        discTotal = discTotal,
        year = year,
        genres = genres,
        composers = composers,
        comments = comments,
        audioInfo = audioInfo,
    )
}

/**
 * Reads the iTunes-style `moov/udta/meta/ilst` metadata and the audio track properties.
 *
 * Fragmented MP4 and non-iTunes metadata keys are not handled.
 */
object Mp4MetadataReader {
    fun read(source: RandomAccessSource): Mp4Metadata? {
        val topLevel = topLevelAtoms(source) ?: return null
        val moov = topLevel.firstOrNull { it.type == "moov" } ?: return null
        val children = atomsIn(source, moov)
        val tags = readTags(source, children)
        val audioInfo = readAudioInfo(source, children)
        return Mp4Metadata(tags, audioInfo)
    }

    private fun readTags(source: RandomAccessSource, moovChildren: List<Mp4Atom>): Mp4Tags {
        val udta = moovChildren.firstOrNull { it.type == "udta" } ?: return Mp4Tags()
        val meta = atomsIn(source, udta).firstOrNull { it.type == "meta" } ?: return Mp4Tags()
        // meta is a full box: version/flags precede the child atoms.
        val ilst = atomsIn(source, meta, skip = FULL_BOX_HEADER).firstOrNull { it.type == "ilst" } ?: return Mp4Tags()
        // Some writers repeat an item atom, others put several data atoms under one item.
        val items = atomsIn(source, ilst).groupBy { it.type }
        fun texts(field: String): List<String> =
            items[field].orEmpty().flatMap { item -> readText(source, item).orEmpty() }

        val trackPair = readNumberPair(source, items["trkn"]?.firstOrNull())
        val discPair = readNumberPair(source, items["disk"]?.firstOrNull())
        return Mp4Tags(
            title = texts(FIELD_TITLE).firstOrNull(),
            artists = texts(FIELD_ARTIST),
            album = texts(FIELD_ALBUM).firstOrNull(),
            albumArtist = texts(FIELD_ALBUM_ARTIST).firstOrNull(),
            trackNumber = trackPair.first,
            trackTotal = trackPair.second,
            discNumber = discPair.first,
            discTotal = discPair.second,
            year = texts(FIELD_YEAR).firstOrNull(),
            genres = texts(FIELD_GENRE),
            composers = texts(FIELD_COMPOSER),
            comments = texts(FIELD_COMMENT),
            artworks = readArtworks(source, items["covr"].orEmpty()),
        )
    }

    private fun readArtworks(source: RandomAccessSource, items: List<Mp4Atom>): List<Artwork> {
        val artworks = ArrayList<Artwork>()
        items.forEach { item ->
            atomsIn(source, item).filter { it.type == "data" }.forEach { data ->
                if (data.payloadSize <= DATA_HEADER_SIZE) return@forEach
                val bytes = readBytes(source, data.payloadStart, data.payloadSize.toInt())
                // data atom: version byte followed by a three-byte type code.
                val dataType = ((bytes[1].toInt() and 0xFF) shl 16) or
                    ((bytes[2].toInt() and 0xFF) shl 8) or
                    (bytes[3].toInt() and 0xFF)
                val mimeType = when (dataType) {
                    DATA_TYPE_JPEG -> "image/jpeg"
                    DATA_TYPE_PNG -> "image/png"
                    else -> null
                }
                val image = bytes.copyOfRange(DATA_HEADER_SIZE, bytes.size)
                if (image.isNotEmpty()) artworks += Artwork(image, mimeType)
            }
        }
        return artworks
    }

    private fun readAudioInfo(source: RandomAccessSource, moovChildren: List<Mp4Atom>): AudioInfo? {
        var durationMs: Long? = null
        var sampleRate: Int? = null
        var channels: Int? = null

        for (trak in moovChildren.filter { it.type == "trak" }) {
            val mdia = atomsIn(source, trak).firstOrNull { it.type == "mdia" } ?: continue
            val mdiaChildren = atomsIn(source, mdia)
            val hdlr = mdiaChildren.firstOrNull { it.type == "hdlr" } ?: continue
            if (readHandlerType(source, hdlr) != "soun") continue

            val mdhd = mdiaChildren.firstOrNull { it.type == "mdhd" }
            if (mdhd != null) {
                durationMs = readMediaDuration(source, mdhd)
            }
            val stsd = mdiaChildren.firstOrNull { it.type == "minf" }
                ?.let { atomsIn(source, it).firstOrNull { child -> child.type == "stbl" } }
                ?.let { atomsIn(source, it).firstOrNull { child -> child.type == "stsd" } }
            if (stsd != null) {
                val entry = atomsIn(source, stsd, skip = FULL_BOX_HEADER + 4).firstOrNull()
                if (entry != null) {
                    val payload = readBytes(source, entry.payloadStart, minOf(entry.payloadSize, AUDIO_SAMPLE_ENTRY_SIZE.toLong()).toInt())
                    if (payload.size >= AUDIO_SAMPLE_ENTRY_SIZE) {
                        channels = payload.readBigEndianShort(16)
                        sampleRate = payload.readBigEndianInt(24) ushr 16
                    }
                }
            }
            break
        }

        if (durationMs == null) {
            val mvhd = moovChildren.firstOrNull { it.type == "mvhd" }
            if (mvhd != null) durationMs = readMovieDuration(source, mvhd)
        }
        if (durationMs == null && sampleRate == null && channels == null) return null
        return AudioInfo(
            bitrateKbps = null,
            sampleRateHz = sampleRate,
            channels = channels,
            durationMs = durationMs,
        )
    }

    private fun readMovieDuration(source: RandomAccessSource, mvhd: Mp4Atom): Long? {
        val data = readBytes(source, mvhd.payloadStart, minOf(mvhd.payloadSize, 32L).toInt())
        if (data.size < 20) return null
        return when (data[0].toInt()) {
            1 -> {
                if (data.size < 32) return null
                val timescale = data.readBigEndianInt(20)
                val duration = data.readBigEndianLong(24)
                millis(duration, timescale)
            }

            else -> {
                val timescale = data.readBigEndianInt(12)
                val duration = data.readBigEndianInt(16).toLong() and 0xFFFFFFFFL
                millis(duration, timescale)
            }
        }
    }

    private fun readMediaDuration(source: RandomAccessSource, mdhd: Mp4Atom): Long? {
        val data = readBytes(source, mdhd.payloadStart, minOf(mdhd.payloadSize, 32L).toInt())
        if (data.size < 20) return null
        return when (data[0].toInt()) {
            1 -> {
                if (data.size < 32) return null
                val timescale = data.readBigEndianInt(20)
                val duration = data.readBigEndianLong(24)
                millis(duration, timescale)
            }

            else -> {
                val timescale = data.readBigEndianInt(12)
                val duration = data.readBigEndianInt(16).toLong() and 0xFFFFFFFFL
                millis(duration, timescale)
            }
        }
    }

    private fun millis(duration: Long, timescale: Int): Long? =
        if (timescale > 0 && duration > 0) duration * 1000 / timescale else null

    private fun readHandlerType(source: RandomAccessSource, hdlr: Mp4Atom): String? {
        val data = readBytes(source, hdlr.payloadStart, minOf(hdlr.payloadSize, 12L).toInt())
        if (data.size < 12) return null
        return fourCc(data, 8)
    }

    private fun readText(source: RandomAccessSource, item: Mp4Atom?): List<String>? {
        if (item == null) return null
        val values = ArrayList<String>()
        atomsIn(source, item).filter { it.type == "data" }.forEach { data ->
            val bytes = readBytes(source, data.payloadStart, data.payloadSize.toInt())
            if (bytes.size <= DATA_HEADER_SIZE) return@forEach
            values += bytes.decodeToString(DATA_HEADER_SIZE, bytes.size)
        }
        return values
    }

    private fun readNumberPair(source: RandomAccessSource, item: Mp4Atom?): Pair<Int?, Int?> {
        if (item == null) return null to null
        val data = atomsIn(source, item).firstOrNull { it.type == "data" } ?: return null to null
        if (data.payloadSize < DATA_HEADER_SIZE + 6) return null to null
        val bytes = readBytes(source, data.payloadStart, DATA_HEADER_SIZE + 6)
        return bytes.readBigEndianShort(DATA_HEADER_SIZE + 2) to bytes.readBigEndianShort(DATA_HEADER_SIZE + 4)
    }

    private fun topLevelAtoms(source: RandomAccessSource): List<Mp4Atom>? {
        val atoms = ArrayList<Mp4Atom>()
        var offset = 0L
        while (offset + ATOM_HEADER_SIZE <= source.size) {
            val atom = readAtom(source, offset, source.size) ?: return atoms.ifEmpty { null }
            atoms += atom
            offset += atom.size
        }
        return atoms.ifEmpty { null }
    }

    private fun atomsIn(source: RandomAccessSource, parent: Mp4Atom, skip: Int = 0): List<Mp4Atom> {
        val atoms = ArrayList<Mp4Atom>()
        var offset = parent.payloadStart + skip
        val end = parent.payloadStart + parent.payloadSize
        while (offset + ATOM_HEADER_SIZE <= end) {
            val atom = readAtom(source, offset, end) ?: break
            atoms += atom
            offset += atom.size
        }
        return atoms
    }

    private fun readAtom(source: RandomAccessSource, offset: Long, limit: Long): Mp4Atom? {
        if (offset + ATOM_HEADER_SIZE > limit) return null
        val header = ByteArray(8)
        source.readAt(offset, header)
        var size = header.readBigEndianInt(0).toLong() and 0xFFFFFFFFL
        val type = fourCc(header, 4)
        var headerSize = ATOM_HEADER_SIZE
        if (size == 1L) {
            if (offset + 16 > limit) return null
            val large = ByteArray(8)
            source.readAt(offset + 8, large)
            size = large.readBigEndianLong(0)
            headerSize = 16
        } else if (size == 0L) {
            size = limit - offset
        }
        if (size < headerSize || offset + size > limit) return null
        return Mp4Atom(type, offset, offset + headerSize.toLong(), size - headerSize)
    }
}

private class Mp4Atom(
    val type: String,
    val offset: Long,
    val payloadStart: Long,
    val payloadSize: Long,
) {
    val size: Long = (payloadStart - offset) + payloadSize
}

private fun readBytes(source: RandomAccessSource, offset: Long, length: Int): ByteArray {
    if (length <= 0) return ByteArray(0)
    val bytes = ByteArray(length)
    source.readFully(offset, bytes, length)
    return bytes
}

private fun fourCc(bytes: ByteArray, offset: Int): String = buildString(4) {
    repeat(4) { index -> append((bytes[offset + index].toInt() and 0xFF).toChar()) }
}

private fun ByteArray.readBigEndianInt(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 24) or
        ((this[offset + 1].toInt() and 0xFF) shl 16) or
        ((this[offset + 2].toInt() and 0xFF) shl 8) or
        (this[offset + 3].toInt() and 0xFF)

private fun ByteArray.readBigEndianShort(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)

private fun ByteArray.readBigEndianLong(offset: Int): Long {
    var value = 0L
    for (index in 0 until 8) {
        value = (value shl 8) or (this[offset + index].toLong() and 0xFF)
    }
    return value
}

private const val ATOM_HEADER_SIZE = 8
private const val FULL_BOX_HEADER = 4
private const val DATA_HEADER_SIZE = 8
private const val AUDIO_SAMPLE_ENTRY_SIZE = 28
private const val FIELD_TITLE = "©nam"
private const val FIELD_ARTIST = "©ART"
private const val FIELD_ALBUM = "©alb"
private const val FIELD_ALBUM_ARTIST = "aART"
private const val FIELD_YEAR = "©day"
private const val FIELD_GENRE = "©gen"
private const val FIELD_COMPOSER = "©wrt"
private const val FIELD_COMMENT = "©cmt"
private const val DATA_TYPE_JPEG = 13
private const val DATA_TYPE_PNG = 14
