package com.berryos.audiometadata

import com.berryos.audiometadata.aiff.AiffMetadataReader
import com.berryos.audiometadata.flac.FlacMetadataReader
import com.berryos.audiometadata.id3.Id3v2Tag
import com.berryos.audiometadata.io.RandomAccessSource
import com.berryos.audiometadata.io.WritableRandomAccessSource
import com.berryos.audiometadata.mp4.Mp4MetadataReader
import com.berryos.audiometadata.mp4.Mp4Tag
import com.berryos.audiometadata.opus.OpusMetadataReader
import com.berryos.audiometadata.vorbis.OggVorbisMetadataReader
import com.berryos.audiometadata.vorbis.VorbisCommentTag
import com.berryos.audiometadata.wav.WavMetadataReader

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
@OptIn(ExperimentalAudioMetadataApi::class)
class AudioFile internal constructor(
    internal val backing: AudioFileBacking,
) {
    val format: AudioFormat
        get() = backing.format

    val id3Version: Id3Version?
        get() = backing.id3Version

    val audioInfo: AudioInfo?
        get() = backing.audioInfo

    /** Well-known non-core tags, kept separate from the standard properties above. */
    @ExperimentalAudioMetadataApi
    val extensions: AudioExtensions = AudioExtensions(backing.extensions)

    /** Low-level Vorbis comments, shared by FLAC, Ogg Vorbis and Opus. */
    @ExperimentalAudioMetadataApi
    val vorbis: VorbisCommentTag?
        get() = backing.vorbisTag

    /** Low-level iTunes fields for MP4/M4A files. */
    @ExperimentalAudioMetadataApi
    val mp4: Mp4Tag?
        get() = backing.mp4Tag

    /** Low-level ID3 tag shared by MP3, WAV and AIFF. */
    @ExperimentalAudioMetadataApi
    val id3: Id3v2Tag?
        get() = (backing as? Id3FileBacking)?.id3Tag

    var title: String?
        get() = backing.text.title
        set(value) {
            backing.text.title = value
        }

    var artists: List<String>
        get() = backing.text.artists
        set(value) {
            backing.text.artists = value
        }

    var album: String?
        get() = backing.text.album
        set(value) {
            backing.text.album = value
        }

    var albumArtist: String?
        get() = backing.text.albumArtist
        set(value) {
            backing.text.albumArtist = value
        }

    var trackNumber: Int?
        get() = backing.text.trackNumber
        set(value) {
            backing.text.trackNumber = value
        }

    var trackTotal: Int?
        get() = backing.text.trackTotal
        set(value) {
            backing.text.trackTotal = value
        }

    var discNumber: Int?
        get() = backing.text.discNumber
        set(value) {
            backing.text.discNumber = value
        }

    var discTotal: Int?
        get() = backing.text.discTotal
        set(value) {
            backing.text.discTotal = value
        }

    var year: String?
        get() = backing.text.year
        set(value) {
            backing.text.year = value
        }

    var genres: List<String>
        get() = backing.text.genres
        set(value) {
            backing.text.genres = value
        }

    var composers: List<String>
        get() = backing.text.composers
        set(value) {
            backing.text.composers = value
        }

    var comments: List<String>
        get() = backing.text.comments
        set(value) {
            backing.text.comments = value
        }

    var lyrics: String?
        get() = backing.text.lyrics
        set(value) {
            backing.text.lyrics = value
        }

    var replayGain: ReplayGain?
        get() = backing.text.replayGain
        set(value) {
            backing.text.replayGain = value
        }

    var artworks: List<Artwork>?
        get() = backing.artworks
        set(value) {
            backing.artworks = value
        }

    /** Immutable snapshot of the current state, including the loaded pictures. */
    fun toAudioMetadata(): AudioMetadata = AudioMetadata(
        format = format,
        id3Version = id3Version,
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
        replayGain = replayGain,
        audioInfo = audioInfo,
    )

    /** Writes the current state back to [source] and refreshes any stale layout information. */
    fun save(source: WritableRandomAccessSource) {
        backing.save(source)
    }

    companion object {
        /**
         * Reads metadata from [source].
         *
         * Returns null when the container is not recognised. When the signature matches a supported
         * container but its metadata is malformed, throws [AudioMetadataException].
         */
        fun read(source: RandomAccessSource, includeArtworks: Boolean = false): AudioFile? {
            val format = AudioFormatDetector.detect(source)
            val file = when (format) {
                AudioFormat.Flac -> FlacMetadataReader.read(source, includeArtworks)
                    ?.let { AudioFile(FlacBacking(it)) }

                AudioFormat.Ogg -> OpusMetadataReader.read(source)
                    ?.let { AudioFile(OpusBacking(it, includeArtworks)) }
                    ?: OggVorbisMetadataReader.read(source)
                        ?.let { AudioFile(OggVorbisBacking(it, includeArtworks)) }

                AudioFormat.Mp4 -> Mp4MetadataReader.read(source, includeArtworks)
                    ?.let { AudioFile(Mp4Backing(it)) }

                AudioFormat.Mp3, AudioFormat.Unknown -> openMp3(source, includeArtworks)
                AudioFormat.Wav -> WavMetadataReader.read(source)
                    ?.let { AudioFile(WavBacking(it, includeArtworks)) }

                AudioFormat.Aiff -> AiffMetadataReader.read(source)
                    ?.let { AudioFile(AiffBacking(it, includeArtworks)) }

                AudioFormat.Opus -> null
            }
            if (file == null && format != AudioFormat.Unknown) {
                throw AudioMetadataException("Detected ${format.name} data but the metadata could not be read")
            }
            return file
        }
    }
}
