package com.berryos.audiometadata.id3

import com.berryos.audiometadata.Id3Version
import org.jaudiotagger.tag.TagException
import org.jaudiotagger.tag.id3.ID3v22Tag
import org.jaudiotagger.tag.id3.ID3v23Tag
import org.jaudiotagger.tag.id3.ID3v24Tag
import java.nio.ByteBuffer

/** Parses a complete ID3v2 tag (header included) as stored at the start of a file or chunk. */
internal fun parseId3v2Tag(bytes: ByteArray): Id3Tag? {
    if (bytes.size < ID3_HEADER_SIZE) return null
    if (bytes[0] != 'I'.code.toByte() || bytes[1] != 'D'.code.toByte() || bytes[2] != '3'.code.toByte()) return null
    val version = when (bytes[3].toInt()) {
        2 -> Id3Version.V22
        3 -> Id3Version.V23
        4 -> Id3Version.V24
        else -> return null
    }
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
    return Id3Tag(version, tag, bytes.size)
}

private const val ID3_HEADER_SIZE = 10
private const val LOGGING_NAME = "audio"
