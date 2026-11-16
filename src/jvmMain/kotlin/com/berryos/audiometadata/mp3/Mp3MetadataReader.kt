package com.berryos.audiometadata.mp3

import com.berryos.audiometadata.Id3Version
import com.berryos.audiometadata.io.RandomAccessSource
import org.jaudiotagger.tag.TagException
import org.jaudiotagger.tag.id3.ID3v22Tag
import org.jaudiotagger.tag.id3.ID3v23Tag
import org.jaudiotagger.tag.id3.ID3v24Tag
import java.nio.ByteBuffer

/**
 * Reads ID3v2 (2.2/2.3/2.4) and ID3v1 tags from any random-access source.
 *
 * The tag is parsed by the ported jaudiotagger classes; no File is required.
 */
object Mp3MetadataReader {
    fun read(source: RandomAccessSource): Mp3Metadata? {
        val id3v2 = readId3v2(source)
        val id3v1 = readId3v1(source)
        if (id3v2 == null && id3v1 == null) return null
        val audioStart = id3v2?.audioStartOffset ?: 0L
        return Mp3Metadata(
            tag = id3v2?.tag,
            audioStartOffset = audioStart,
            id3v1 = id3v1,
            audioInfo = Mp3AudioInfoReader.read(source, audioStart),
        )
    }

    private fun readId3v2(source: RandomAccessSource): ParsedId3v2? {
        if (source.size < ID3V2_HEADER_SIZE) return null
        val header = ByteArray(ID3V2_HEADER_SIZE)
        if (source.readAt(0, header) < ID3V2_HEADER_SIZE) return null
        if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) {
            return null
        }
        val version = when (header[3].toInt()) {
            2 -> Id3Version.V22
            3 -> Id3Version.V23
            4 -> Id3Version.V24
            else -> return null
        }
        val flags = header[5].toInt() and 0xFF
        val payloadSize = decodeSyncSafe(header, 6)
        if (payloadSize < 0) return null
        val footerSize = if (version == Id3Version.V24 && flags and FOOTER_FLAG != 0) FOOTER_SIZE else 0
        val tagSize = ID3V2_HEADER_SIZE.toLong() + payloadSize + footerSize
        if (tagSize > source.size || tagSize > Int.MAX_VALUE) return null

        val bytes = ByteArray(tagSize.toInt())
        if (source.readAt(0, bytes) < bytes.size) return null
        val buffer = ByteBuffer.wrap(bytes)
        val tag = try {
            when (version) {
                Id3Version.V22 -> ID3v22Tag(buffer, LOGGING_NAME)
                Id3Version.V23 -> ID3v23Tag(buffer, LOGGING_NAME)
                Id3Version.V24 -> ID3v24Tag(buffer, LOGGING_NAME)
            }
        } catch (exception: TagException) {
            return null
        }
        return ParsedId3v2(Mp3Tag(version, tag, tagSize.toInt()), tagSize)
    }

    private fun readId3v1(source: RandomAccessSource): Id3v1Metadata? {
        if (source.size < ID3V1_SIZE) return null
        val bytes = ByteArray(ID3V1_SIZE)
        if (source.readAt(source.size - ID3V1_SIZE, bytes) < ID3V1_SIZE) return null
        if (bytes[0] != 'T'.code.toByte() || bytes[1] != 'A'.code.toByte() || bytes[2] != 'G'.code.toByte()) {
            return null
        }
        // ID3v1.1: the comment shrinks to 28 bytes, followed by a zero byte and the track number.
        val isV11 = bytes[125].toInt() == 0 && bytes[126].toInt() != 0
        return Id3v1Metadata(
            title = id3v1Text(bytes, 3, 30),
            artist = id3v1Text(bytes, 33, 30),
            album = id3v1Text(bytes, 63, 30),
            year = id3v1Text(bytes, 93, 4),
            comment = id3v1Text(bytes, 97, if (isV11) 28 else 30),
            trackNumber = if (isV11) bytes[126].toInt() and 0xFF else null,
            genreIndex = bytes[127].toInt() and 0xFF,
        )
    }
}

private class ParsedId3v2(val tag: Mp3Tag, val audioStartOffset: Long)

private fun decodeSyncSafe(bytes: ByteArray, offset: Int): Int {
    var value = 0
    for (index in offset until offset + 4) {
        val byte = bytes[index].toInt()
        if (byte and 0x80 != 0) return -1
        value = (value shl 7) or (byte and 0x7F)
    }
    return value
}

private fun id3v1Text(bytes: ByteArray, offset: Int, length: Int): String? =
    String(bytes, offset, length, Charsets.ISO_8859_1)
        .trimEnd('\u0000', ' ')
        .trim()
        .ifEmpty { null }

private const val ID3V2_HEADER_SIZE = 10
private const val ID3V1_SIZE = 128
private const val FOOTER_SIZE = 10
private const val FOOTER_FLAG = 0x10
private const val LOGGING_NAME = "audio"
