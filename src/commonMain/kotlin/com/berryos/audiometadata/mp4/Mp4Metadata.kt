package com.berryos.audiometadata.mp4

import com.berryos.audiometadata.Artwork
import com.berryos.audiometadata.AudioFormat
import com.berryos.audiometadata.AudioInfo
import com.berryos.audiometadata.AudioMetadata
import com.berryos.audiometadata.asciiAt
import com.berryos.audiometadata.guessArtworkMimeType
import com.berryos.audiometadata.io.RandomAccessSource
import com.berryos.audiometadata.io.WritableRandomAccessSource
import com.berryos.audiometadata.io.moveTail
import com.berryos.audiometadata.io.readFully

/** Parsed MP4/M4A metadata. */
internal class Mp4Metadata(
    val tags: Mp4Tag,
    val audioInfo: AudioInfo?,
) {
    val artworks: List<Artwork>?
        get() = tags.artworks

    fun toAudioMetadata(): AudioMetadata = tags.toAudioMetadata(audioInfo)
}

/** One `ilst` entry that has no typed property. */
public class Mp4Item internal constructor(
    /** Atom name; `----` for freeform entries. */
    public val type: String,
    /** Type code of the first `data` atom, or -1 when the entry has no data atom. */
    public val dataType: Int,
    /** Payload of the first `data` atom, without its header. */
    public val data: ByteArray,
    /** `mean` value of a freeform entry, otherwise null. */
    public val mean: String? = null,
    /** `name` value of a freeform entry, otherwise null. */
    public val name: String? = null,
    /** Original atom bytes, emitted unchanged when the entry was not edited. */
    internal val raw: ByteArray? = null,
) {
    public companion object {
        public const val TYPE_IMPLICIT: Int = 0
        public const val TYPE_UTF8: Int = 1
        public const val TYPE_JPEG: Int = 13
        public const val TYPE_PNG: Int = 14
        public const val TYPE_INTEGER: Int = 21
    }
}

/**
 * Mutable view of the iTunes `ilst` metadata.
 *
 * Typed properties cover the common fields. Every other entry is available through [items] and can
 * be edited with [setItem], [addItem], [removeItem], [setFreeform] and [removeFreeform].
 *
 * The tag is mutable and not thread-safe. [Mp4Item.data] is a read-only view.
 */
public class Mp4Tag internal constructor() {
    public var title: String? = null
    public var artists: List<String> = emptyList()
    public var album: String? = null
    public var albumArtist: String? = null
    public var trackNumber: Int? = null
    public var trackTotal: Int? = null
    public var discNumber: Int? = null
    public var discTotal: Int? = null
    public var year: String? = null
    public var genres: List<String> = emptyList()
    public var composers: List<String> = emptyList()
    public var comments: List<String> = emptyList()
    public var lyrics: String? = null
    public var titleSort: String? = null
    public var albumSort: String? = null
    public var artistSort: String? = null
    public var albumArtistSort: String? = null
    public var compilation: Boolean? = null
    public var bpm: Int? = null
    public var rating: Int? = null
    public var grouping: String? = null
    public var copyright: String? = null
    public var encoder: String? = null
    public var contentRating: Int? = null
    public var isrc: String? = null
    public var originalDate: String? = null
    public var musicBrainzTrackId: String? = null
    public var musicBrainzReleaseTrackId: String? = null
    public var musicBrainzAlbumId: String? = null
    public var musicBrainzArtistId: String? = null
    public var musicBrainzAlbumArtistId: String? = null
    public var musicBrainzReleaseGroupId: String? = null
    public var musicBrainzWorkId: String? = null

    /** Loaded pictures, or null when the reader was asked to skip them. */
    public var artworks: List<Artwork>? = null

    private val otherItems = ArrayList<Mp4Item>()

    /** Entries that have no typed property, in file order. */
    public val items: List<Mp4Item>
        get() = otherItems

    /** Entries with the atom name [type]. */
    public fun item(type: String): List<Mp4Item> = otherItems.filter { item -> item.type == type }

    /** The freeform entry [name] under [mean], or null. */
    public fun freeformItem(name: String, mean: String = FREEFORM_MEAN): Mp4Item? =
        otherItems.firstOrNull { item -> item.isFreeform(mean, name) }

    /** Text of the freeform entry [name] under [mean], or null. */
    public fun freeformText(name: String, mean: String = FREEFORM_MEAN): String? =
        freeformItem(name, mean)?.data?.decodeToString()

    /**
     * Replaces every entry with [type] by one entry carrying [data].
     *
     * @throws IllegalArgumentException when [type] is not a four-character atom name or is `----`.
     */
    public fun setItem(type: String, dataType: Int, data: ByteArray) {
        requireItemType(type)
        otherItems.removeAll { item -> item.type == type }
        otherItems += Mp4Item(type, dataType, data)
    }

    /** Adds an entry without removing existing entries with the same [type]. */
    public fun addItem(type: String, dataType: Int, data: ByteArray) {
        requireItemType(type)
        otherItems += Mp4Item(type, dataType, data)
    }

    /** Removes every entry with [type]. */
    public fun removeItem(type: String) {
        otherItems.removeAll { item -> item.type == type }
    }

    /** Replaces the freeform text entry [name] under the `com.apple.iTunes` mean. */
    public fun setFreeform(name: String, value: String) {
        removeFreeform(name)
        if (value.isNotEmpty()) {
            otherItems += Mp4Item(
                type = FREEFORM_ITEM,
                dataType = Mp4Item.TYPE_UTF8,
                data = value.encodeToByteArray(),
                mean = FREEFORM_MEAN,
                name = name,
            )
        }
    }

    /** Removes the freeform entry [name] under the `com.apple.iTunes` mean. */
    public fun removeFreeform(name: String) {
        otherItems.removeAll { item -> item.isFreeform(FREEFORM_MEAN, name) }
    }

    internal fun addReadItem(item: Mp4Item) {
        otherItems += item
    }

    /** Immutable snapshot of the typed fields and loaded pictures. */
    public fun toAudioMetadata(audioInfo: AudioInfo? = null): AudioMetadata = AudioMetadata(
        format = AudioFormat.MP4,
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
        lyrics = lyrics,
        audioInfo = audioInfo,
    )

    private fun requireItemType(type: String) {
        require(type.length == ATOM_TYPE_SIZE && type != FREEFORM_ITEM) {
            "Item type must be a four-character atom name; use setFreeform for ---- entries: $type"
        }
    }

    private fun Mp4Item.isFreeform(mean: String, name: String): Boolean =
        type == FREEFORM_ITEM && this.mean == mean && this.name == name
}

/**
 * Reads the iTunes-style `moov/udta/meta/ilst` metadata and the audio track properties.
 *
 * Fragmented MP4 and non-iTunes metadata keys are not handled.
 */
internal object Mp4MetadataReader {
    fun read(source: RandomAccessSource, includeArtworks: Boolean = true): Mp4Metadata? {
        val topLevel = topLevelAtoms(source) ?: return null
        val moov = topLevel.firstOrNull { it.type == "moov" } ?: return null
        val children = atomsIn(source, moov)
        val tags = readTags(source, children, includeArtworks)
        val audioInfo = readAudioInfo(source, children)
        return Mp4Metadata(tags, audioInfo)
    }

    private fun readTags(
        source: RandomAccessSource,
        moovChildren: List<Mp4Atom>,
        includeArtworks: Boolean,
    ): Mp4Tag {
        val tag = Mp4Tag()
        tag.artworks = if (includeArtworks) emptyList() else null
        val udta = moovChildren.firstOrNull { it.type == "udta" } ?: return tag
        val meta = atomsIn(source, udta).firstOrNull { it.type == "meta" } ?: return tag
        // meta is a full box: version/flags precede the child atoms.
        val ilst = atomsIn(source, meta, skip = FULL_BOX_HEADER).firstOrNull { it.type == "ilst" }
            ?: return tag
        // Some writers repeat an item atom, others put several data atoms under one item.
        val items = atomsIn(source, ilst).groupBy { it.type }
        fun texts(field: String): List<String> =
            items[field].orEmpty().flatMap { item -> readText(source, item).orEmpty() }

        val trackPair = readNumberPair(source, items["trkn"]?.firstOrNull())
        val discPair = readNumberPair(source, items["disk"]?.firstOrNull())
        val freeform = readFreeform(source, items[FREEFORM_ITEM].orEmpty())
        tag.title = texts(FIELD_TITLE).firstOrNull()
        tag.artists = texts(FIELD_ARTIST)
        tag.album = texts(FIELD_ALBUM).firstOrNull()
        tag.albumArtist = texts(FIELD_ALBUM_ARTIST).firstOrNull()
        tag.trackNumber = trackPair.first
        tag.trackTotal = trackPair.second
        tag.discNumber = discPair.first
        tag.discTotal = discPair.second
        tag.year = texts(FIELD_YEAR).firstOrNull()
        tag.genres = texts(FIELD_GENRE)
        tag.composers = texts(FIELD_COMPOSER)
        tag.comments = texts(FIELD_COMMENT)
        tag.lyrics = texts(FIELD_LYRICS).firstOrNull()
        tag.titleSort = texts(FIELD_TITLE_SORT).firstOrNull()
        tag.albumSort = texts(FIELD_ALBUM_SORT).firstOrNull()
        tag.artistSort = texts(FIELD_ARTIST_SORT).firstOrNull()
        tag.albumArtistSort = texts(FIELD_ALBUM_ARTIST_SORT).firstOrNull()
        tag.compilation = readIntegerValue(source, items[ITEM_COMPILATION]?.firstOrNull())?.let { it != 0 }
        tag.bpm = readIntegerValue(source, items[ITEM_BPM]?.firstOrNull())
        tag.rating = readIntegerValue(source, items[ITEM_RATING]?.firstOrNull())?.coerceIn(0, 100)
        tag.grouping = texts(FIELD_GROUPING).firstOrNull()
        tag.copyright = texts(FIELD_COPYRIGHT).firstOrNull()
        tag.encoder = texts(FIELD_ENCODER).firstOrNull()
        tag.contentRating = readIntegerValue(source, items[ITEM_CONTENT_RATING]?.firstOrNull())?.coerceIn(0, 255)
        tag.isrc = freeform[FREEFORM_ISRC]
        tag.originalDate = freeform[FREEFORM_ORIGINAL_DATE]
        tag.musicBrainzTrackId = freeform[FREEFORM_MB_TRACK]
        tag.musicBrainzReleaseTrackId = freeform[FREEFORM_MB_RELEASE_TRACK]
        tag.musicBrainzAlbumId = freeform[FREEFORM_MB_ALBUM]
        tag.musicBrainzArtistId = freeform[FREEFORM_MB_ARTIST]
        tag.musicBrainzAlbumArtistId = freeform[FREEFORM_MB_ALBUM_ARTIST]
        tag.musicBrainzReleaseGroupId = freeform[FREEFORM_MB_RELEASE_GROUP]
        tag.musicBrainzWorkId = freeform[FREEFORM_MB_WORK]
        tag.artworks = if (includeArtworks) readArtworks(source, items[COVER_ITEM].orEmpty()) else null
        readOtherItems(source, items).forEach(tag::addReadItem)
        return tag
    }

    private fun readOtherItems(
        source: RandomAccessSource,
        items: Map<String, List<Mp4Atom>>,
    ): List<Mp4Item> {
        val result = ArrayList<Mp4Item>()
        items.forEach { (type, atoms) ->
            if (type in REPLACED_ITEMS) return@forEach
            atoms.forEach { atom ->
                val children = if (type == FREEFORM_ITEM) atomsIn(source, atom) else emptyList()
                val mean = children.firstOrNull { child -> child.type == "mean" }
                    ?.let { atomMean -> fullBoxText(source, atomMean) }
                val name = children.firstOrNull { child -> child.type == "name" }
                    ?.let { atomName -> fullBoxText(source, atomName) }
                if (type == FREEFORM_ITEM && mean == FREEFORM_MEAN && name != null &&
                    name in MANAGED_FREEFORM_NAMES
                ) {
                    return@forEach
                }
                val data = readItemData(source, atom)
                result += Mp4Item(
                    type = type,
                    dataType = data?.first ?: MISSING_DATA_TYPE,
                    data = data?.second ?: ByteArray(0),
                    mean = mean,
                    name = name,
                    raw = readBytes(source, atom.offset, atom.size.toInt()),
                )
            }
        }
        return result
    }

    private fun readItemData(source: RandomAccessSource, item: Mp4Atom): Pair<Int, ByteArray>? {
        val data = atomsIn(source, item).firstOrNull { child -> child.type == "data" } ?: return null
        if (data.payloadSize <= DATA_HEADER_SIZE) return null
        val bytes = readBytes(source, data.payloadStart, data.payloadSize.toInt())
        val dataType = ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or
            (bytes[3].toInt() and 0xFF)
        return dataType to bytes.copyOfRange(DATA_HEADER_SIZE, bytes.size)
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
                    val payload = readBytes(
                        source,
                        entry.payloadStart,
                        minOf(entry.payloadSize, AUDIO_SAMPLE_ENTRY_SIZE.toLong()).toInt(),
                    )
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
        return data.asciiAt(8, ATOM_TYPE_SIZE)
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

    /** Reads an implicit integer atom such as `cpil` (1 byte) or `tmpo` (2 bytes). */
    private fun readIntegerValue(source: RandomAccessSource, item: Mp4Atom?): Int? {
        if (item == null) return null
        val data = atomsIn(source, item).firstOrNull { it.type == "data" } ?: return null
        if (data.payloadSize <= DATA_HEADER_SIZE) return null
        val length = minOf(data.payloadSize - DATA_HEADER_SIZE, 4L).toInt()
        val bytes = readBytes(source, data.payloadStart + DATA_HEADER_SIZE, length)
        var value = 0
        bytes.forEach { byte -> value = (value shl 8) or (byte.toInt() and 0xFF) }
        return value
    }

    private fun readFreeform(source: RandomAccessSource, items: List<Mp4Atom>): Map<String, String> {
        val values = LinkedHashMap<String, String>()
        items.forEach { item ->
            val children = atomsIn(source, item)
            val mean = children.firstOrNull { child -> child.type == "mean" }?.let { mean -> fullBoxText(source, mean) }
            if (mean != FREEFORM_MEAN) return@forEach
            val name = children.firstOrNull { child -> child.type == "name" }?.let { name -> fullBoxText(source, name) }
                ?: return@forEach
            val data = children.firstOrNull { child -> child.type == "data" } ?: return@forEach
            if (data.payloadSize <= DATA_HEADER_SIZE) return@forEach
            val dataLength = (data.payloadSize - DATA_HEADER_SIZE).toInt()
            values[name] = readBytes(source, data.payloadStart + DATA_HEADER_SIZE, dataLength).decodeToString()
        }
        return values
    }
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
    val type = header.asciiAt(4, ATOM_TYPE_SIZE)
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

private fun fullBoxText(source: RandomAccessSource, atom: Mp4Atom): String? {
    if (atom.payloadSize <= FULL_BOX_HEADER) return null
    val length = (atom.payloadSize - FULL_BOX_HEADER).toInt()
    return readBytes(source, atom.payloadStart + FULL_BOX_HEADER, length).decodeToString()
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
private const val FIELD_LYRICS = "©lyr"
private const val FIELD_GROUPING = "©grp"
private const val FIELD_COPYRIGHT = "cprt"
private const val FIELD_ENCODER = "©too"
private const val FIELD_TITLE_SORT = "sonm"
private const val FIELD_ALBUM_SORT = "soal"
private const val FIELD_ARTIST_SORT = "soar"
private const val FIELD_ALBUM_ARTIST_SORT = "soaa"
private const val ITEM_COMPILATION = "cpil"
private const val ITEM_BPM = "tmpo"
private const val ITEM_RATING = "rate"
private const val ITEM_CONTENT_RATING = "rtng"
private const val FREEFORM_ITEM = "----"
private const val FREEFORM_MEAN = "com.apple.iTunes"
private const val FREEFORM_ISRC = "ISRC"
private const val FREEFORM_ORIGINAL_DATE = "originaldate"
private const val FREEFORM_MB_TRACK = "MusicBrainz Track Id"
private const val FREEFORM_MB_RELEASE_TRACK = "MusicBrainz Release Track Id"
private const val FREEFORM_MB_ALBUM = "MusicBrainz Album Id"
private const val FREEFORM_MB_ARTIST = "MusicBrainz Artist Id"
private const val FREEFORM_MB_ALBUM_ARTIST = "MusicBrainz Album Artist Id"
private const val FREEFORM_MB_RELEASE_GROUP = "MusicBrainz Release Group Id"
private const val FREEFORM_MB_WORK = "MusicBrainz Work Id"
private const val DATA_TYPE_JPEG = Mp4Item.TYPE_JPEG
private const val DATA_TYPE_PNG = Mp4Item.TYPE_PNG
private const val DATA_TYPE_IMPLICIT = Mp4Item.TYPE_IMPLICIT
private const val DATA_TYPE_IMPLICIT_INTEGER = Mp4Item.TYPE_INTEGER
private const val DATA_TYPE_UTF8 = Mp4Item.TYPE_UTF8
private const val MISSING_DATA_TYPE = -1
private const val ATOM_TYPE_SIZE = 4

/** Rebuilds `moov` with the supplied tags and keeps sample offsets valid. */
internal object Mp4MetadataWriter {
    fun write(source: WritableRandomAccessSource, metadata: Mp4Metadata, tags: Mp4Tag = metadata.tags) {
        val topLevel = topLevelAtoms(source) ?: return
        val moov = topLevel.firstOrNull { it.type == "moov" } ?: return
        val oldMoovEnd = moov.offset + moov.size
        val newMoov = rebuildMoov(source, moov, tags)
        val delta = newMoov.size.toLong() - moov.size
        if (delta != 0L) {
            source.moveTail(oldMoovEnd, moov.offset + newMoov.size)
            patchChunkOffsets(newMoov, oldMoovEnd, delta)
        }
        source.writeAt(moov.offset, newMoov)
        source.flush()
    }

    private fun rebuildMoov(source: RandomAccessSource, moov: Mp4Atom, tags: Mp4Tag): ByteArray {
        val moovChildren = atomsIn(source, moov)
        val udta = moovChildren.firstOrNull { it.type == "udta" }
        val udtaChildren = udta?.let { atomsIn(source, it) }
        val metaInUdta = udtaChildren?.firstOrNull { it.type == "meta" }
        val metaInMoov = moovChildren.firstOrNull { it.type == "meta" }
        val meta = metaInUdta ?: metaInMoov
        val ilst = meta?.let {
            atomsIn(source, it, skip = FULL_BOX_HEADER).firstOrNull { child -> child.type == "ilst" }
        }
        val newIlst = buildIlst(source, ilst, tags)
        val newMeta = buildMeta(source, meta, newIlst)

        val newMoovChildren = ArrayList<ByteArray>()
        when {
            metaInUdta != null && udta != null && udtaChildren != null -> {
                val newUdta = buildAtom(
                    "udta",
                    concat(udtaChildren.filter { it.type != "meta" }.map { raw(source, it) } + newMeta),
                )
                moovChildren.forEach { child ->
                    newMoovChildren += if (child.type == "udta") newUdta else raw(source, child)
                }
            }

            metaInMoov != null -> {
                moovChildren.forEach { child ->
                    newMoovChildren += if (child.type == "meta") newMeta else raw(source, child)
                }
            }

            udta != null && udtaChildren != null -> {
                val newUdta = buildAtom("udta", concat(udtaChildren.map { raw(source, it) } + newMeta))
                moovChildren.forEach { child ->
                    newMoovChildren += if (child.type == "udta") newUdta else raw(source, child)
                }
            }

            else -> {
                moovChildren.forEach { child -> newMoovChildren += raw(source, child) }
                newMoovChildren += buildAtom("udta", newMeta)
            }
        }
        return buildAtom("moov", concat(newMoovChildren))
    }

    private fun buildMeta(source: RandomAccessSource, meta: Mp4Atom?, newIlst: ByteArray): ByteArray {
        val children = if (meta != null) atomsIn(source, meta, skip = FULL_BOX_HEADER) else emptyList()
        val kept = children.filter { it.type != "ilst" }.map { raw(source, it) }
        val prefix = if (meta != null) {
            readBytes(source, meta.payloadStart, FULL_BOX_HEADER)
        } else {
            ByteArray(FULL_BOX_HEADER)
        }
        return buildAtom("meta", prefix + concat(kept + listOf(newIlst)))
    }

    private fun buildIlst(source: RandomAccessSource, ilst: Mp4Atom?, tags: Mp4Tag): ByteArray {
        val keptCover = if (tags.artworks == null && ilst != null) {
            atomsIn(source, ilst)
                .filter { atom -> atom.type == COVER_ITEM }
                .map { atom -> raw(source, atom) }
        } else {
            emptyList()
        }
        val otherItems = tags.items.map { item -> item.raw ?: buildItem(item) }
        return buildAtom("ilst", concat(keptCover + buildItems(tags) + otherItems))
    }

    private fun buildItem(item: Mp4Item): ByteArray {
        if (item.type == FREEFORM_ITEM && item.mean != null && item.name != null) {
            return buildAtom(
                FREEFORM_ITEM,
                buildAtom("mean", bigEndianInt(0) + item.mean.encodeToByteArray()) +
                    buildAtom("name", bigEndianInt(0) + item.name.encodeToByteArray()) +
                    dataAtom(item.dataType, item.data),
            )
        }
        return buildAtom(item.type, dataAtom(item.dataType, item.data))
    }

    private fun buildItems(tags: Mp4Tag): List<ByteArray> {
        val items = ArrayList<ByteArray>()
        tags.title?.takeIf(String::isNotEmpty)?.let { items += textItem(FIELD_TITLE, it) }
        if (tags.artists.isNotEmpty()) items += multiTextItem(FIELD_ARTIST, tags.artists)
        tags.album?.takeIf(String::isNotEmpty)?.let { items += textItem(FIELD_ALBUM, it) }
        tags.albumArtist?.takeIf(String::isNotEmpty)?.let { items += textItem(FIELD_ALBUM_ARTIST, it) }
        if (tags.trackNumber != null || tags.trackTotal != null) {
            items += numberItem(ITEM_TRACK, tags.trackNumber ?: 0, tags.trackTotal ?: 0)
        }
        if (tags.discNumber != null || tags.discTotal != null) {
            items += numberItem(ITEM_DISC, tags.discNumber ?: 0, tags.discTotal ?: 0)
        }
        tags.year?.takeIf(String::isNotEmpty)?.let { items += textItem(FIELD_YEAR, it) }
        if (tags.genres.isNotEmpty()) items += multiTextItem(FIELD_GENRE, tags.genres)
        if (tags.composers.isNotEmpty()) items += multiTextItem(FIELD_COMPOSER, tags.composers)
        if (tags.comments.isNotEmpty()) items += multiTextItem(FIELD_COMMENT, tags.comments)
        tags.lyrics?.takeIf(String::isNotEmpty)?.let { items += textItem(FIELD_LYRICS, it) }
        addExtensionItems(items, tags)
        val artworks = tags.artworks
        if (!artworks.isNullOrEmpty()) items += coverItem(artworks)
        return items
    }

    private fun addExtensionItems(items: MutableList<ByteArray>, tags: Mp4Tag) {
        tags.titleSort?.takeIf(String::isNotEmpty)?.let { items += textItem(FIELD_TITLE_SORT, it) }
        tags.albumSort?.takeIf(String::isNotEmpty)?.let { items += textItem(FIELD_ALBUM_SORT, it) }
        tags.artistSort?.takeIf(String::isNotEmpty)?.let { items += textItem(FIELD_ARTIST_SORT, it) }
        tags.albumArtistSort?.takeIf(String::isNotEmpty)?.let { items += textItem(FIELD_ALBUM_ARTIST_SORT, it) }
        tags.compilation?.let { compilation ->
            items += integerItem(ITEM_COMPILATION, byteArrayOf(if (compilation) 1 else 0))
        }
        tags.bpm?.let { bpm -> items += integerItem(ITEM_BPM, bigEndianShort(bpm.coerceIn(0, 0xFFFF))) }
        tags.rating?.let { rating -> items += integerItem(ITEM_RATING, byteArrayOf(rating.coerceIn(0, 100).toByte())) }
        tags.grouping?.takeIf(String::isNotEmpty)?.let { items += textItem(FIELD_GROUPING, it) }
        tags.copyright?.takeIf(String::isNotEmpty)?.let { items += textItem(FIELD_COPYRIGHT, it) }
        tags.encoder?.takeIf(String::isNotEmpty)?.let { items += textItem(FIELD_ENCODER, it) }
        tags.contentRating?.let { rating ->
            items += integerItem(ITEM_CONTENT_RATING, byteArrayOf(rating.coerceIn(0, 255).toByte()))
        }
        addFreeformItem(items, FREEFORM_ISRC, tags.isrc)
        addFreeformItem(items, FREEFORM_ORIGINAL_DATE, tags.originalDate)
        addFreeformItem(items, FREEFORM_MB_TRACK, tags.musicBrainzTrackId)
        addFreeformItem(items, FREEFORM_MB_RELEASE_TRACK, tags.musicBrainzReleaseTrackId)
        addFreeformItem(items, FREEFORM_MB_ALBUM, tags.musicBrainzAlbumId)
        addFreeformItem(items, FREEFORM_MB_ARTIST, tags.musicBrainzArtistId)
        addFreeformItem(items, FREEFORM_MB_ALBUM_ARTIST, tags.musicBrainzAlbumArtistId)
        addFreeformItem(items, FREEFORM_MB_RELEASE_GROUP, tags.musicBrainzReleaseGroupId)
        addFreeformItem(items, FREEFORM_MB_WORK, tags.musicBrainzWorkId)
    }

    private fun addFreeformItem(items: MutableList<ByteArray>, name: String, value: String?) {
        if (!value.isNullOrEmpty()) items += freeformItem(name, value)
    }

    private fun raw(source: RandomAccessSource, atom: Mp4Atom): ByteArray =
        readBytes(source, atom.offset, atom.size.toInt())

    private fun textItem(type: String, value: String): ByteArray =
        buildAtom(type, dataAtom(DATA_TYPE_UTF8, value.encodeToByteArray()))

    private fun multiTextItem(type: String, values: List<String>): ByteArray =
        buildAtom(type, concat(values.map { dataAtom(DATA_TYPE_UTF8, it.encodeToByteArray()) }))

    private fun numberItem(type: String, number: Int, total: Int): ByteArray = buildAtom(
        type,
        dataAtom(
            DATA_TYPE_IMPLICIT,
            byteArrayOf(0, 0) +
                bigEndianShort(number) +
                bigEndianShort(total) +
                byteArrayOf(0, 0),
        ),
    )

    private fun coverItem(artworks: List<Artwork>): ByteArray = buildAtom(
        "covr",
        concat(artworks.map { artwork -> dataAtom(coverType(artwork), artwork.data) }),
    )

    private fun coverType(artwork: Artwork): Int {
        val mime = artwork.mimeType ?: guessArtworkMimeType(artwork.data)
        return if (mime == "image/png") DATA_TYPE_PNG else DATA_TYPE_JPEG
    }

    private fun dataAtom(dataType: Int, value: ByteArray): ByteArray =
        buildAtom("data", bigEndianInt(dataType) + bigEndianInt(0) + value)

    private fun integerItem(type: String, value: ByteArray): ByteArray =
        buildAtom(type, dataAtom(DATA_TYPE_IMPLICIT_INTEGER, value))

    private fun freeformItem(name: String, value: String): ByteArray = buildAtom(
        FREEFORM_ITEM,
        buildAtom("mean", bigEndianInt(0) + FREEFORM_MEAN.encodeToByteArray()) +
            buildAtom("name", bigEndianInt(0) + name.encodeToByteArray()) +
            dataAtom(DATA_TYPE_UTF8, value.encodeToByteArray()),
    )

    private fun patchChunkOffsets(moovBytes: ByteArray, threshold: Long, delta: Long) {
        patchChunkOffsetsIn(moovBytes, 0, moovBytes.size, threshold, delta)
    }

    private fun patchChunkOffsetsIn(bytes: ByteArray, start: Int, end: Int, threshold: Long, delta: Long) {
        var position = start
        while (position + ATOM_HEADER_SIZE <= end) {
            val size = bytes.readBigEndianInt(position)
            if (size < ATOM_HEADER_SIZE || position + size > end) return
            when (val type = bytes.asciiAt(position + 4, ATOM_TYPE_SIZE)) {
                "stco" -> {
                    val count = bytes.readBigEndianInt(position + 12)
                    var entry = position + 16
                    repeat(count) {
                        if (entry + 4 > position + size) return
                        val offset = bytes.readBigEndianInt(entry).toLong() and 0xFFFFFFFFL
                        if (offset >= threshold) bytes.writeBigEndianInt(entry, (offset + delta).toInt())
                        entry += 4
                    }
                }

                "co64" -> {
                    val count = bytes.readBigEndianInt(position + 12)
                    var entry = position + 16
                    repeat(count) {
                        if (entry + 8 > position + size) return
                        val offset = bytes.readBigEndianLong(entry)
                        if (offset >= threshold) bytes.writeBigEndianLong(entry, offset + delta)
                        entry += 8
                    }
                }

                in CONTAINER_TYPES -> patchChunkOffsetsIn(
                    bytes,
                    position + ATOM_HEADER_SIZE,
                    position + size,
                    threshold,
                    delta,
                )
            }
            position += size
        }
    }
}

/** Sample offsets referenced by stco/co64; used to validate writes. */
internal fun readChunkOffsets(source: RandomAccessSource): List<Long> {
    val moov = topLevelAtoms(source)?.firstOrNull { it.type == "moov" } ?: return emptyList()
    val offsets = ArrayList<Long>()
    collectChunkOffsets(source, moov, offsets)
    return offsets
}

private fun collectChunkOffsets(source: RandomAccessSource, parent: Mp4Atom, offsets: MutableList<Long>) {
    atomsIn(source, parent).forEach { child ->
        when (child.type) {
            "stco" -> {
                val data = readBytes(source, child.payloadStart, child.payloadSize.toInt())
                if (data.size >= 8) {
                    var position = 8
                    repeat(data.readBigEndianInt(4)) {
                        if (position + 4 <= data.size) {
                            offsets += data.readBigEndianInt(position).toLong() and 0xFFFFFFFFL
                            position += 4
                        }
                    }
                }
            }

            "co64" -> {
                val data = readBytes(source, child.payloadStart, child.payloadSize.toInt())
                if (data.size >= 8) {
                    var position = 8
                    repeat(data.readBigEndianInt(4)) {
                        if (position + 8 <= data.size) {
                            offsets += data.readBigEndianLong(position)
                            position += 8
                        }
                    }
                }
            }

            in CONTAINER_TYPES -> collectChunkOffsets(source, child, offsets)
        }
    }
}

private fun buildAtom(type: String, payload: ByteArray): ByteArray {
    val atom = ByteArray(ATOM_HEADER_SIZE + payload.size)
    atom.writeBigEndianInt(0, atom.size)
    type.toByteArray(Charsets.ISO_8859_1).copyInto(atom, 4)
    payload.copyInto(atom, ATOM_HEADER_SIZE)
    return atom
}

private fun concat(chunks: List<ByteArray>): ByteArray {
    val total = chunks.sumOf { it.size }
    val result = ByteArray(total)
    var position = 0
    chunks.forEach { chunk ->
        chunk.copyInto(result, position)
        position += chunk.size
    }
    return result
}

private fun bigEndianInt(value: Int): ByteArray = ByteArray(4).also { it.writeBigEndianInt(0, value) }

private fun bigEndianShort(value: Int): ByteArray = byteArrayOf((value ushr 8).toByte(), value.toByte())

private fun ByteArray.writeBigEndianInt(offset: Int, value: Int) {
    this[offset] = (value ushr 24).toByte()
    this[offset + 1] = (value ushr 16).toByte()
    this[offset + 2] = (value ushr 8).toByte()
    this[offset + 3] = value.toByte()
}

private fun ByteArray.writeBigEndianLong(offset: Int, value: Long) {
    for (index in 0 until 8) {
        this[offset + index] = (value ushr (56 - index * 8)).toByte()
    }
}

private val REPLACED_ITEMS = setOf(
    FIELD_TITLE, FIELD_ARTIST, FIELD_ALBUM, FIELD_ALBUM_ARTIST, FIELD_YEAR,
    FIELD_GENRE, FIELD_COMPOSER, FIELD_COMMENT, FIELD_LYRICS, ITEM_TRACK, ITEM_DISC, COVER_ITEM,
    FIELD_TITLE_SORT, FIELD_ALBUM_SORT, FIELD_ARTIST_SORT, FIELD_ALBUM_ARTIST_SORT,
    ITEM_COMPILATION, ITEM_BPM, ITEM_RATING, FIELD_GROUPING, FIELD_COPYRIGHT, FIELD_ENCODER,
    ITEM_CONTENT_RATING,
)

private val CONTAINER_TYPES = setOf("moov", "trak", "mdia", "minf", "stbl")

private val MANAGED_FREEFORM_NAMES = setOf(
    FREEFORM_ISRC,
    FREEFORM_ORIGINAL_DATE,
    FREEFORM_MB_TRACK,
    FREEFORM_MB_RELEASE_TRACK,
    FREEFORM_MB_ALBUM,
    FREEFORM_MB_ARTIST,
    FREEFORM_MB_ALBUM_ARTIST,
    FREEFORM_MB_RELEASE_GROUP,
    FREEFORM_MB_WORK,
)

private const val ITEM_TRACK = "trkn"
private const val ITEM_DISC = "disk"
private const val COVER_ITEM = "covr"
