package com.berryos.audiometadata

import com.berryos.audiometadata.flac.FlacMetadata
import com.berryos.audiometadata.flac.FlacMetadataReader
import com.berryos.audiometadata.flac.FlacMetadataWriter
import com.berryos.audiometadata.io.RandomAccessSource
import com.berryos.audiometadata.io.WritableRandomAccessSource
import com.berryos.audiometadata.vorbis.VorbisCommentTag

/**
 * Mutable metadata of one audio file.
 *
 * High-level properties and the format-specific tag exposed in [vorbis] edit the same state, so a
 * change made through one view is immediately visible in the other. [save] writes the state back
 * and leaves everything that was not edited untouched.
 *
 * [artworks] is null when the file was opened without pictures; [save] then preserves the embedded
 * ones. Set it to an empty list to remove them.
 */
class AudioFile internal constructor(
    val format: AudioFormat,
    internal var flac: FlacMetadata,
) {
    val audioInfo: AudioInfo?
        get() = flac.streamInfo?.toAudioInfo()

    /** Low-level Vorbis comments, shared by FLAC, Ogg Vorbis and Opus. */
    @ExperimentalAudioMetadataApi
    val vorbis: VorbisCommentTag?
        get() = flac.tag

    var title: String?
        get() = flac.tag.value("TITLE")?.takeIf(String::isNotEmpty)
        set(value) = flac.tag.set("TITLE", value.orEmpty())

    var artists: List<String>
        get() = flac.tag.values("ARTIST")
        set(value) = flac.tag.setValues("ARTIST", value)

    var album: String?
        get() = flac.tag.value("ALBUM")?.takeIf(String::isNotEmpty)
        set(value) = flac.tag.set("ALBUM", value.orEmpty())

    var albumArtist: String?
        get() = flac.tag.value("ALBUMARTIST")?.takeIf(String::isNotEmpty)
        set(value) = flac.tag.set("ALBUMARTIST", value.orEmpty())

    var trackNumber: Int?
        get() = readNumber("TRACKNUMBER", "TRACKTOTAL").first
        set(value) = writeNumber("TRACKNUMBER", value, trackTotal)

    var trackTotal: Int?
        get() = readNumber("TRACKNUMBER", "TRACKTOTAL").second
        set(value) {
            flac.tag.set("TRACKTOTAL", value?.toString().orEmpty())
            writeNumber("TRACKNUMBER", trackNumber, value)
        }

    var discNumber: Int?
        get() = readNumber("DISCNUMBER", "DISCTOTAL").first
        set(value) = writeNumber("DISCNUMBER", value, discTotal)

    var discTotal: Int?
        get() = readNumber("DISCNUMBER", "DISCTOTAL").second
        set(value) {
            flac.tag.set("DISCTOTAL", value?.toString().orEmpty())
            writeNumber("DISCNUMBER", discNumber, value)
        }

    var year: String?
        get() = flac.tag.value("DATE")?.takeIf(String::isNotEmpty)
        set(value) = flac.tag.set("DATE", value.orEmpty())

    var genres: List<String>
        get() = flac.tag.values("GENRE")
        set(value) = flac.tag.setValues("GENRE", value)

    var composers: List<String>
        get() = flac.tag.values("COMPOSER")
        set(value) = flac.tag.setValues("COMPOSER", value)

    var comments: List<String>
        get() = flac.tag.values("COMMENT")
        set(value) = flac.tag.setValues("COMMENT", value)

    var replayGain: ReplayGain?
        get() = flac.tag.replayGain
        set(value) {
            flac.tag.set("REPLAYGAIN_TRACK_GAIN", value?.trackGain.orEmpty())
            flac.tag.set("REPLAYGAIN_TRACK_PEAK", value?.trackPeak.orEmpty())
            flac.tag.set("REPLAYGAIN_ALBUM_GAIN", value?.albumGain.orEmpty())
            flac.tag.set("REPLAYGAIN_ALBUM_PEAK", value?.albumPeak.orEmpty())
        }

    var artworks: List<Artwork>?
        get() = flac.artworks
        set(value) {
            flac.artworks = value
        }

    /** Immutable snapshot of the current state, including the loaded pictures. */
    fun toAudioMetadata(): AudioMetadata = flac.toAudioMetadata()

    /** Writes the current state back to [source] and refreshes the internal layout. */
    fun save(source: WritableRandomAccessSource) {
        val metadata = flac
        FlacMetadataWriter.write(source, metadata)
        flac = FlacMetadataReader.read(source, includeArtworks = metadata.artworks != null)
            ?: throw IllegalStateException("FLAC metadata could not be read back after save")
    }

    private fun readNumber(key: String, totalKey: String): Pair<Int?, Int?> {
        val parts = flac.tag.value(key).orEmpty().split('/', limit = 2)
        val number = parts.getOrNull(0)?.trim()?.toIntOrNull()
        val total = flac.tag.value(totalKey)?.toIntOrNull()
            ?: parts.getOrNull(1)?.trim()?.toIntOrNull()
        return number to total
    }

    private fun writeNumber(key: String, number: Int?, total: Int?) {
        val text = when {
            number == null -> ""
            total == null -> number.toString()
            else -> "$number/$total"
        }
        flac.tag.set(key, text)
    }

    companion object {
        /**
         * Reads metadata from [source], returning null when the container is not supported yet.
         *
         * FLAC is the only container wired to this entry point while the unified model is being
         * rolled out; the other formats are still available through their dedicated readers.
         */
        fun read(source: RandomAccessSource, includeArtworks: Boolean = false): AudioFile? {
            val flac = FlacMetadataReader.read(source, includeArtworks) ?: return null
            return AudioFile(AudioFormat.Flac, flac)
        }
    }
}
