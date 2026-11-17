package com.berryos.audiometadata.id3

import com.berryos.audiometadata.Artwork
import com.berryos.audiometadata.AudioFormat
import com.berryos.audiometadata.AudioInfo
import com.berryos.audiometadata.AudioMetadata
import com.berryos.audiometadata.Id3Version
import com.berryos.audiometadata.ReplayGain

/**
 * Mutable ID3 tag shared by MP3, WAV and AIFF.
 *
 * Values are nullable or lists; an empty value means that the frame is absent. Implementations
 * edit the underlying tag in place and are written by the container-specific writer.
 */
public interface Id3Tag {
    public val version: Id3Version
    public var title: String?
    public var artists: List<String>
    public var album: String?
    public var albumArtist: String?
    public var trackNumber: Int?
    public var trackTotal: Int?
    public var discNumber: Int?
    public var discTotal: Int?
    public var year: String?
    public var genres: List<String>
    public var composers: List<String>
    public var comments: List<String>
    public var lyrics: String?
    public var replayGain: ReplayGain?
    public val artworks: List<Artwork>

    /** Replaces the embedded pictures. */
    public fun setArtworks(artworks: List<Artwork>)

    public fun toAudioMetadata(
        format: AudioFormat = AudioFormat.MP3,
        audioInfo: AudioInfo? = null,
    ): AudioMetadata
}
