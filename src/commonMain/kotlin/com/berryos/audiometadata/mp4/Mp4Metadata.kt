package com.berryos.audiometadata.mp4

import com.berryos.audiometadata.Artwork
import com.berryos.audiometadata.AudioFormat
import com.berryos.audiometadata.AudioInfo
import com.berryos.audiometadata.AudioMetadata
import com.berryos.audiometadata.guessArtworkMimeType
import com.berryos.audiometadata.io.RandomAccessSource
import com.berryos.audiometadata.io.WritableRandomAccessSource
import com.berryos.audiometadata.io.moveTail
import com.berryos.audiometadata.io.readFully

/** Parsed MP4/M4A metadata. */
internal class Mp4Metadata(
    val tags: Mp4Tags,
    val audioInfo: AudioInfo?,
) {
    val artworks: List<Artwork>?
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
    val titleSort: String? = null,
    val albumSort: String? = null,
    val artistSort: String? = null,
    val albumArtistSort: String? = null,
    val compilation: Boolean? = null,
    val bpm: Int? = null,
    /** Loaded pictures, or null when the reader was asked to skip them. */
    val artworks: List<Artwork>? = null,
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
    ): Mp4Tags {
        val noArtworks: List<Artwork>? = if (includeArtworks) emptyList() else null
        val udta = moovChildren.firstOrNull { it.type == "udta" } ?: return Mp4Tags(artworks = noArtworks)
        val meta = atomsIn(source, udta).firstOrNull { it.type == "meta" } ?: return Mp4Tags(artworks = noArtworks)
        // meta is a full box: version/flags precede the child atoms.
        val ilst = atomsIn(source, meta, skip = FULL_BOX_HEADER).firstOrNull { it.type == "ilst" }
            ?: return Mp4Tags(artworks = noArtworks)
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
            titleSort = texts(FIELD_TITLE_SORT).firstOrNull(),
            albumSort = texts(FIELD_ALBUM_SORT).firstOrNull(),
            artistSort = texts(FIELD_ARTIST_SORT).firstOrNull(),
            albumArtistSort = texts(FIELD_ALBUM_ARTIST_SORT).firstOrNull(),
            compilation = readIntegerValue(source, items[ITEM_COMPILATION]?.firstOrNull())?.let { it != 0 },
            bpm = readIntegerValue(source, items[ITEM_BPM]?.firstOrNull()),
            artworks = if (includeArtworks) readArtworks(source, items[COVER_ITEM].orEmpty()) else null,
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
private const val FIELD_TITLE_SORT = "sonm"
private const val FIELD_ALBUM_SORT = "soal"
private const val FIELD_ARTIST_SORT = "soar"
private const val FIELD_ALBUM_ARTIST_SORT = "soaa"
private const val ITEM_COMPILATION = "cpil"
private const val ITEM_BPM = "tmpo"
private const val DATA_TYPE_JPEG = 13
private const val DATA_TYPE_PNG = 14
private const val DATA_TYPE_IMPLICIT = 0
private const val DATA_TYPE_IMPLICIT_INTEGER = 21
private const val DATA_TYPE_UTF8 = 1

/** Rebuilds `moov` with the supplied tags and keeps sample offsets valid. */
internal object Mp4MetadataWriter {
    fun write(source: WritableRandomAccessSource, metadata: Mp4Metadata, tags: Mp4Tags = metadata.tags) {
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

    private fun rebuildMoov(source: RandomAccessSource, moov: Mp4Atom, tags: Mp4Tags): ByteArray {
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

    private fun buildIlst(source: RandomAccessSource, ilst: Mp4Atom?, tags: Mp4Tags): ByteArray {
        val kept = if (ilst != null) {
            atomsIn(source, ilst)
                .filter { it.type !in REPLACED_ITEMS || (it.type == COVER_ITEM && tags.artworks == null) }
                .map { raw(source, it) }
        } else {
            emptyList()
        }
        return buildAtom("ilst", concat(kept + buildItems(tags)))
    }

    private fun buildItems(tags: Mp4Tags): List<ByteArray> {
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
        tags.titleSort?.takeIf(String::isNotEmpty)?.let { items += textItem(FIELD_TITLE_SORT, it) }
        tags.albumSort?.takeIf(String::isNotEmpty)?.let { items += textItem(FIELD_ALBUM_SORT, it) }
        tags.artistSort?.takeIf(String::isNotEmpty)?.let { items += textItem(FIELD_ARTIST_SORT, it) }
        tags.albumArtistSort?.takeIf(String::isNotEmpty)?.let { items += textItem(FIELD_ALBUM_ARTIST_SORT, it) }
        tags.compilation?.let { compilation ->
            items += integerItem(ITEM_COMPILATION, byteArrayOf(if (compilation) 1 else 0))
        }
        tags.bpm?.let { bpm -> items += integerItem(ITEM_BPM, bigEndianShort(bpm.coerceIn(0, 0xFFFF))) }
        val artworks = tags.artworks
        if (!artworks.isNullOrEmpty()) items += coverItem(artworks)
        return items
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

    private fun patchChunkOffsets(moovBytes: ByteArray, threshold: Long, delta: Long) {
        patchChunkOffsetsIn(moovBytes, 0, moovBytes.size, threshold, delta)
    }

    private fun patchChunkOffsetsIn(bytes: ByteArray, start: Int, end: Int, threshold: Long, delta: Long) {
        var position = start
        while (position + ATOM_HEADER_SIZE <= end) {
            val size = bytes.readBigEndianInt(position)
            if (size < ATOM_HEADER_SIZE || position + size > end) return
            when (val type = fourCc(bytes, position + 4)) {
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
    FIELD_GENRE, FIELD_COMPOSER, FIELD_COMMENT, ITEM_TRACK, ITEM_DISC, COVER_ITEM,
    FIELD_TITLE_SORT, FIELD_ALBUM_SORT, FIELD_ARTIST_SORT, FIELD_ALBUM_ARTIST_SORT,
    ITEM_COMPILATION, ITEM_BPM,
)

private val CONTAINER_TYPES = setOf("moov", "trak", "mdia", "minf", "stbl")

private const val ITEM_TRACK = "trkn"
private const val ITEM_DISC = "disk"
private const val COVER_ITEM = "covr"
