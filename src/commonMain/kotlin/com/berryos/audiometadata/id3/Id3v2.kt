package com.berryos.audiometadata.id3

import com.berryos.audiometadata.Artwork
import com.berryos.audiometadata.Id3Version
import com.berryos.audiometadata.ReplayGain
import com.berryos.audiometadata.io.RandomAccessSource
import com.berryos.audiometadata.io.readFully

/**
 * One decoded ID3v2 frame.
 *
 * [content] excludes the frame header and the extra bytes required by the format flags
 * (group identifier, data length indicator). Compressed and encrypted frames keep their stored
 * content and are never decoded by the typed accessors.
 */
class Id3v2Frame internal constructor(
    val id: String,
    val statusFlags: Int,
    val formatFlags: Int,
    val groupId: Int?,
    val dataLengthIndicator: Int?,
    val compressed: Boolean,
    val encrypted: Boolean,
    val content: ByteArray,
)

/**
 * A parsed ID3v2 tag.
 *
 * Frames are exposed as they were read, including the ones without a typed mapping. Typed
 * properties resolve the common frames listed in the ID3v2.3/2.4 specifications.
 */
class Id3v2Tag internal constructor(
    val version: Id3Version,
    val revision: Int,
    val frames: List<Id3v2Frame>,
    val declaredSize: Int,
    val paddingSize: Int,
    val unsynchronised: Boolean,
    val extendedHeaderSize: Int,
    val hasFooter: Boolean,
) {
    fun framesOf(id: String): List<Id3v2Frame> = frames.filter { frame -> frame.id == id }

    /** Decoded values of the text frame [id]; repeated frames contribute all their values. */
    fun textValues(id: String): List<String> = framesOf(id).flatMap(::frameTextValues)

    fun firstText(id: String): String? = textValues(id).firstOrNull()

    val title: String?
        get() = firstText(FRAME_TITLE)?.takeIf(String::isNotEmpty)

    val artists: List<String>
        get() = textValues(FRAME_ARTIST)

    val album: String?
        get() = firstText(FRAME_ALBUM)?.takeIf(String::isNotEmpty)

    val albumArtist: String?
        get() = firstText(FRAME_ALBUM_ARTIST)?.takeIf(String::isNotEmpty)

    val composers: List<String>
        get() = textValues(FRAME_COMPOSER)

    val trackNumber: Int?
        get() = numberPair(FRAME_TRACK).first

    val trackTotal: Int?
        get() = numberPair(FRAME_TRACK).second

    val discNumber: Int?
        get() = numberPair(FRAME_DISC).first

    val discTotal: Int?
        get() = numberPair(FRAME_DISC).second

    val year: String?
        get() = firstText(yearFrameId)?.takeIf(String::isNotEmpty)

    val genres: List<String>
        get() = Id3Genres.resolveAll(textValues(FRAME_GENRE))

    val comments: List<String>
        get() = framesOf(FRAME_COMMENT).mapNotNull(::parseComment)

    val replayGain: ReplayGain?
        get() {
            val values = framesOf(FRAME_USER_TEXT)
                .mapNotNull(::parseUserText)
                .filter { (description, _) -> description.startsWith(REPLAYGAIN_PREFIX) }
                .toMap()
            if (values.isEmpty()) return null
            return ReplayGain(
                trackGain = values["REPLAYGAIN_TRACK_GAIN"],
                trackPeak = values["REPLAYGAIN_TRACK_PEAK"],
                albumGain = values["REPLAYGAIN_ALBUM_GAIN"],
                albumPeak = values["REPLAYGAIN_ALBUM_PEAK"],
            )
        }

    val artworks: List<Artwork>
        get() = framesOf(FRAME_PICTURE).mapNotNull(::parseArtwork)

    private val yearFrameId: String
        get() = if (version == Id3Version.V24) FRAME_RECORDING_TIME else FRAME_YEAR

    private fun frameTextValues(frame: Id3v2Frame): List<String> {
        if (!frame.isDecodable()) return emptyList()
        val content = frame.content
        if (content.isEmpty()) return emptyList()
        val encoding = content[0].toInt() and 0xFF
        return Id3Text.splitValues(Id3Text.decode(encoding, content, 1, content.size))
    }

    private fun numberPair(frameId: String): Pair<Int?, Int?> {
        val value = firstText(frameId).orEmpty()
        if (value.isEmpty()) return null to null
        val parts = value.split('/', limit = 2)
        val number = parts.getOrNull(0)?.trim()?.toIntOrNull()?.takeIf { it > 0 }
        val total = parts.getOrNull(1)?.trim()?.toIntOrNull()?.takeIf { it > 0 }
        return number to total
    }

    private fun parseComment(frame: Id3v2Frame): String? {
        if (!frame.isDecodable()) return null
        val content = frame.content
        if (content.size < COMMENT_HEADER_SIZE) return null
        val encoding = content[0].toInt() and 0xFF
        val descriptionStart = COMMENT_HEADER_SIZE
        val descriptionEnd = Id3Text.findTerminator(encoding, content, descriptionStart)
        val textStart = descriptionEnd + Id3Text.terminatorLength(encoding)
        if (textStart > content.size) return null
        return Id3Text.decode(encoding, content, textStart, content.size).takeIf(String::isNotEmpty)
    }

    private fun parseUserText(frame: Id3v2Frame): Pair<String, String>? {
        if (!frame.isDecodable()) return null
        val content = frame.content
        if (content.size < 2) return null
        val encoding = content[0].toInt() and 0xFF
        val descriptionEnd = Id3Text.findTerminator(encoding, content, 1)
        val description = Id3Text.decode(encoding, content, 1, descriptionEnd)
        val valueStart = descriptionEnd + Id3Text.terminatorLength(encoding)
        if (valueStart > content.size) return null
        val value = Id3Text.decode(encoding, content, valueStart, content.size)
        return description to value
    }

    private fun parseArtwork(frame: Id3v2Frame): Artwork? {
        if (!frame.isDecodable()) return null
        val content = frame.content
        if (content.size < APIC_MIN_SIZE) return null
        val encoding = content[0].toInt() and 0xFF
        val mimeEnd = Id3Text.findTerminator(Id3Text.LATIN1, content, 1)
        if (mimeEnd >= content.size || mimeEnd + 2 > content.size) return null
        val mimeType = Id3Text.decode(Id3Text.LATIN1, content, 1, mimeEnd)
        if (mimeType == "-->" || mimeType.isEmpty()) return null
        val pictureType = content[mimeEnd + 1].toInt() and 0xFF
        val descriptionStart = mimeEnd + 2
        val descriptionEnd = Id3Text.findTerminator(encoding, content, descriptionStart)
        val description = Id3Text.decode(encoding, content, descriptionStart, descriptionEnd)
        val dataStart = descriptionEnd + Id3Text.terminatorLength(encoding)
        if (dataStart >= content.size) return null
        return Artwork(
            data = content.copyOfRange(dataStart, content.size),
            mimeType = mimeType,
            pictureType = pictureType,
            description = description.ifEmpty { null },
        )
    }
}

private fun Id3v2Frame.isDecodable(): Boolean = !compressed && !encrypted

/** Reads ID3v2 tags from the start of an audio file or an embedded chunk. */
object Id3v2Reader {
    /** Reads the tag at offset 0 of [source], or returns null when there is no parseable tag. */
    fun read(source: RandomAccessSource): Id3v2Tag? {
        if (source.size < HEADER_SIZE) return null
        val header = ByteArray(HEADER_SIZE)
        source.readFully(0, header, HEADER_SIZE)
        return readTag(header) { offset, destination ->
            source.readFully(offset, destination, destination.size)
            true
        }
    }

    /** Parses a complete tag (10-byte header included) stored in [bytes]. */
    internal fun read(bytes: ByteArray): Id3v2Tag? {
        if (bytes.size < HEADER_SIZE) return null
        val header = bytes.copyOfRange(0, HEADER_SIZE)
        return readTag(header) { offset, destination ->
            val start = offset.toInt()
            if (start + destination.size > bytes.size) return@readTag false
            bytes.copyInto(destination, 0, start, start + destination.size)
            true
        }
    }

    private inline fun readTag(
        header: ByteArray,
        readAt: (Long, ByteArray) -> Boolean,
    ): Id3v2Tag? {
        if (!header.hasId3Magic()) return null
        val version = versionOf(header[3].toInt() and 0xFF) ?: return null
        val revision = header[4].toInt() and 0xFF
        val flags = header[5].toInt() and 0xFF
        if (version == Id3Version.V22 && flags and FLAG_V22_COMPRESSION != 0) return null
        val bodySize = synchsafeInt(header, 6)
        if (bodySize < 0) return null
        val body = ByteArray(bodySize)
        if (!readAt(HEADER_SIZE.toLong(), body)) return null
        return parseTag(version, revision, flags, body)
    }

    private fun parseTag(version: Id3Version, revision: Int, flags: Int, storedBody: ByteArray): Id3v2Tag {
        val unsynchronised = flags and FLAG_UNSYNCHRONISATION != 0
        val body = if (version == Id3Version.V24 || !unsynchronised) storedBody else deUnsynchronise(storedBody)
        val extendedHeaderSize = if (flags and FLAG_EXTENDED_HEADER != 0) {
            extendedHeaderSize(version, body) ?: 0
        } else {
            0
        }
        var position = extendedHeaderSize
        val frames = ArrayList<Id3v2Frame>()
        val frameHeaderSize = if (version == Id3Version.V22) V22_FRAME_HEADER_SIZE else V23_FRAME_HEADER_SIZE
        while (position + frameHeaderSize <= body.size) {
            if (body[position].toInt() == 0) break
            val parsed = readFrame(version, body, position, unsynchronised) ?: break
            frames += parsed.frame
            position += parsed.size
        }
        val padding = (body.size - position).coerceAtLeast(0)
        return Id3v2Tag(
            version = version,
            revision = revision,
            frames = frames,
            declaredSize = storedBody.size,
            paddingSize = padding,
            unsynchronised = unsynchronised,
            extendedHeaderSize = extendedHeaderSize,
            hasFooter = flags and FLAG_FOOTER != 0,
        )
    }

    private fun readFrame(
        version: Id3Version,
        body: ByteArray,
        position: Int,
        tagUnsynchronised: Boolean,
    ): ParsedFrame? {
        if (version == Id3Version.V22) return readV22Frame(body, position)

        val id = asciiId(body, position, V23_FRAME_ID_SIZE) ?: return null
        val storedSize = if (version == Id3Version.V24) {
            synchsafeInt(body, position + V23_FRAME_ID_SIZE)
        } else {
            bigEndianInt(body, position + V23_FRAME_ID_SIZE)
        }
        if (storedSize < 0) return null
        val contentStart = position + V23_FRAME_HEADER_SIZE
        val contentEnd = contentStart + storedSize
        if (contentEnd > body.size) return null
        val statusFlags = body[position + FLAG_OFFSET_STATUS].toInt() and 0xFF
        val formatFlags = body[position + FLAG_OFFSET_FORMAT].toInt() and 0xFF
        val prefix = readFramePrefix(version, body, contentStart, contentEnd, formatFlags, tagUnsynchronised)
            ?: return null

        var content = body.copyOfRange(prefix.contentStart, contentEnd)
        if (prefix.unsynchronised) content = deUnsynchronise(content)
        val frame = Id3v2Frame(
            id = id,
            statusFlags = statusFlags,
            formatFlags = formatFlags,
            groupId = prefix.groupId,
            dataLengthIndicator = prefix.dataLength,
            compressed = prefix.compressed,
            encrypted = prefix.encrypted,
            content = content,
        )
        return ParsedFrame(frame, V23_FRAME_HEADER_SIZE + storedSize)
    }

    private fun readV22Frame(body: ByteArray, position: Int): ParsedFrame? {
        val id = asciiId(body, position, V22_FRAME_ID_SIZE) ?: return null
        val size = threeByteInt(body, position + V22_FRAME_ID_SIZE)
        val contentStart = position + V22_FRAME_HEADER_SIZE
        if (size < 0 || contentStart + size > body.size) return null
        val frame = Id3v2Frame(
            id = id,
            statusFlags = 0,
            formatFlags = 0,
            groupId = null,
            dataLengthIndicator = null,
            compressed = false,
            encrypted = false,
            content = body.copyOfRange(contentStart, contentStart + size),
        )
        return ParsedFrame(frame, V22_FRAME_HEADER_SIZE + size)
    }

    private fun readFramePrefix(
        version: Id3Version,
        body: ByteArray,
        contentStart: Int,
        contentEnd: Int,
        formatFlags: Int,
        tagUnsynchronised: Boolean,
    ): FramePrefix? {
        var cursor = contentStart
        var groupId: Int? = null
        var dataLength: Int? = null
        val compressed: Boolean
        val encrypted: Boolean
        val unsynchronised: Boolean

        if (version == Id3Version.V23) {
            compressed = formatFlags and V23_FLAG_COMPRESSION != 0
            encrypted = formatFlags and V23_FLAG_ENCRYPTION != 0
            unsynchronised = false
            if (compressed) {
                if (cursor + 4 > contentEnd) return null
                dataLength = bigEndianInt(body, cursor)
                cursor += 4
            }
            if (encrypted) {
                if (cursor + 1 > contentEnd) return null
                cursor += 1
            }
            if (formatFlags and V23_FLAG_GROUPING != 0) {
                if (cursor + 1 > contentEnd) return null
                groupId = body[cursor].toInt() and 0xFF
                cursor += 1
            }
        } else {
            compressed = formatFlags and V24_FLAG_COMPRESSION != 0
            encrypted = formatFlags and V24_FLAG_ENCRYPTION != 0
            unsynchronised = formatFlags and V24_FLAG_UNSYNCHRONISATION != 0 || tagUnsynchronised
            if (formatFlags and V24_FLAG_GROUPING != 0) {
                if (cursor + 1 > contentEnd) return null
                groupId = body[cursor].toInt() and 0xFF
                cursor += 1
            }
            if (compressed || encrypted || formatFlags and V24_FLAG_DATA_LENGTH != 0) {
                if (cursor + 4 > contentEnd) return null
                dataLength = synchsafeInt(body, cursor)
                cursor += 4
            }
        }
        if (cursor > contentEnd) return null
        return FramePrefix(groupId, dataLength, compressed, encrypted, unsynchronised, cursor)
    }

    private class FramePrefix(
        val groupId: Int?,
        val dataLength: Int?,
        val compressed: Boolean,
        val encrypted: Boolean,
        val unsynchronised: Boolean,
        val contentStart: Int,
    )

    private fun extendedHeaderSize(version: Id3Version, body: ByteArray): Int? {
        if (body.size < 4) return null
        return if (version == Id3Version.V24) {
            val size = synchsafeInt(body, 0)
            if (size < V24_EXTENDED_MIN_SIZE || size > body.size) null else size
        } else {
            val size = bigEndianInt(body, 0)
            val total = size + 4
            if (size < V23_EXTENDED_MIN_SIZE || total > body.size) null else total
        }
    }

    private class ParsedFrame(val frame: Id3v2Frame, val size: Int)
}

/** Reverses the ID3v2 unsynchronisation scheme: every $FF $00 pair becomes $FF. */
internal fun deUnsynchronise(bytes: ByteArray): ByteArray {
    val output = ByteArray(bytes.size)
    var read = 0
    var write = 0
    while (read < bytes.size) {
        val byte = bytes[read++]
        output[write++] = byte
        if (byte.toInt() and 0xFF == 0xFF && read < bytes.size && bytes[read].toInt() == 0) read++
    }
    return output.copyOf(write)
}

internal fun synchsafeInt(bytes: ByteArray, offset: Int): Int {
    if (offset < 0 || offset + 4 > bytes.size) return -1
    var value = 0
    for (index in 0 until 4) {
        val byte = bytes[offset + index].toInt() and 0xFF
        if (byte and 0x80 != 0) return -1
        value = (value shl 7) or byte
    }
    return value
}

private fun bigEndianInt(bytes: ByteArray, offset: Int): Int {
    if (offset < 0 || offset + 4 > bytes.size) return -1
    return ((bytes[offset].toInt() and 0xFF) shl 24) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
        (bytes[offset + 3].toInt() and 0xFF)
}

private fun threeByteInt(bytes: ByteArray, offset: Int): Int {
    if (offset < 0 || offset + 3 > bytes.size) return -1
    return ((bytes[offset].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        (bytes[offset + 2].toInt() and 0xFF)
}

private fun asciiId(bytes: ByteArray, offset: Int, length: Int): String? {
    if (offset + length > bytes.size) return null
    val builder = StringBuilder(length)
    for (index in offset until offset + length) {
        val byte = bytes[index].toInt() and 0xFF
        val valid = byte in UPPER_A..UPPER_Z || byte in DIGIT_0..DIGIT_9
        if (!valid) return null
        builder.append(byte.toChar())
    }
    return builder.toString()
}

private fun ByteArray.hasId3Magic(): Boolean =
    size >= HEADER_SIZE &&
        this[0] == 'I'.code.toByte() && this[1] == 'D'.code.toByte() && this[2] == '3'.code.toByte() &&
        (this[3].toInt() and 0xFF) < 0xFF && (this[4].toInt() and 0xFF) < 0xFF

private fun versionOf(major: Int): Id3Version? = when (major) {
    2 -> Id3Version.V22
    3 -> Id3Version.V23
    4 -> Id3Version.V24
    else -> null
}

private const val HEADER_SIZE = 10
private const val V22_FRAME_HEADER_SIZE = 6
private const val V22_FRAME_ID_SIZE = 3
private const val V23_FRAME_HEADER_SIZE = 10
private const val V23_FRAME_ID_SIZE = 4
private const val FLAG_OFFSET_STATUS = 8
private const val FLAG_OFFSET_FORMAT = 9
private const val FLAG_UNSYNCHRONISATION = 0x80
private const val FLAG_EXTENDED_HEADER = 0x40
private const val FLAG_V22_COMPRESSION = 0x40
private const val FLAG_FOOTER = 0x10
private const val V23_FLAG_COMPRESSION = 0x80
private const val V23_FLAG_ENCRYPTION = 0x40
private const val V23_FLAG_GROUPING = 0x20
private const val V24_FLAG_GROUPING = 0x40
private const val V24_FLAG_COMPRESSION = 0x08
private const val V24_FLAG_ENCRYPTION = 0x04
private const val V24_FLAG_UNSYNCHRONISATION = 0x02
private const val V24_FLAG_DATA_LENGTH = 0x01
private const val V23_EXTENDED_MIN_SIZE = 6
private const val V24_EXTENDED_MIN_SIZE = 6
private const val COMMENT_HEADER_SIZE = 4
private const val APIC_MIN_SIZE = 4
private const val UPPER_A = 'A'.code
private const val UPPER_Z = 'Z'.code
private const val DIGIT_0 = '0'.code
private const val DIGIT_9 = '9'.code
private const val REPLAYGAIN_PREFIX = "REPLAYGAIN_"
private const val FRAME_TITLE = "TIT2"
private const val FRAME_ARTIST = "TPE1"
private const val FRAME_ALBUM = "TALB"
private const val FRAME_ALBUM_ARTIST = "TPE2"
private const val FRAME_COMPOSER = "TCOM"
private const val FRAME_TRACK = "TRCK"
private const val FRAME_DISC = "TPOS"
private const val FRAME_YEAR = "TYER"
private const val FRAME_RECORDING_TIME = "TDRC"
private const val FRAME_GENRE = "TCON"
private const val FRAME_COMMENT = "COMM"
private const val FRAME_USER_TEXT = "TXXX"
private const val FRAME_PICTURE = "APIC"
