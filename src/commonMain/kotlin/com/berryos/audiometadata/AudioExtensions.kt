package com.berryos.audiometadata

/**
 * Well-known tags that are not part of the core cross-format model.
 *
 * They live in their own namespace so callers can tell them apart from [AudioFile] standard
 * properties. Storage differs per container; see each property's KDoc. Unknown or vendor-specific
 * keys are deliberately not mapped here and stay reachable through the format-specific tag APIs.
 */
@ExperimentalAudioMetadataApi
class AudioExtensions internal constructor(private val fields: ExtensionFields) {
    /** ID3: TSOT, Vorbis: TITLESORT, MP4: sonm. */
    var titleSort: String?
        get() = fields.titleSort
        set(value) {
            fields.titleSort = value
        }

    /** ID3: TSOP, Vorbis: ARTISTSORT, MP4: soar. */
    var artistSort: String?
        get() = fields.artistSort
        set(value) {
            fields.artistSort = value
        }

    /** ID3: TSOA, Vorbis: ALBUMSORT, MP4: soal. */
    var albumSort: String?
        get() = fields.albumSort
        set(value) {
            fields.albumSort = value
        }

    /** ID3: TSO2, Vorbis: ALBUMARTISTSORT, MP4: soaa. */
    var albumArtistSort: String?
        get() = fields.albumArtistSort
        set(value) {
            fields.albumArtistSort = value
        }

    /** ID3: TCMP (iTunes extension), Vorbis: COMPILATION, MP4: cpil. */
    var compilation: Boolean?
        get() = fields.compilation
        set(value) {
            fields.compilation = value
        }

    /** ID3: TBPM, Vorbis: BPM, MP4: tmpo. */
    var bpm: Int?
        get() = fields.bpm
        set(value) {
            fields.bpm = value
        }

    /** ID3: TSRC, Vorbis: ISRC, MP4: freeform atom `ISRC`. */
    var isrc: String?
        get() = fields.isrc
        set(value) {
            fields.isrc = value
        }

    /** ID3: TDOR/TORY, Vorbis: ORIGINALDATE, MP4: freeform atom `originaldate`. */
    var originalDate: String?
        get() = fields.originalDate
        set(value) {
            fields.originalDate = value
        }

    /** ID3: TXXX:R128_TRACK_GAIN, Vorbis/Opus: R128_TRACK_GAIN. */
    var r128TrackGain: String?
        get() = fields.r128TrackGain
        set(value) {
            fields.r128TrackGain = value
        }

    /** ID3: TXXX:R128_ALBUM_GAIN, Vorbis/Opus: R128_ALBUM_GAIN. */
    var r128AlbumGain: String?
        get() = fields.r128AlbumGain
        set(value) {
            fields.r128AlbumGain = value
        }

    /** MusicBrainz identifiers; each format uses its conventional storage. */
    val musicBrainz: MusicBrainzTags = MusicBrainzTags(fields.musicBrainz)
}

/**
 * MusicBrainz identifiers.
 *
 * ID3 stores the recording ID in UFID and the rest in `TXXX:MusicBrainz *`; Vorbis uses
 * `MUSICBRAINZ_*`; MP4 uses `----:com.apple.iTunes:*` freeform atoms.
 */
@ExperimentalAudioMetadataApi
class MusicBrainzTags internal constructor(private val fields: MusicBrainzFields) {
    var recordingId: String?
        get() = fields.recordingId
        set(value) {
            fields.recordingId = value
        }

    var releaseTrackId: String?
        get() = fields.releaseTrackId
        set(value) {
            fields.releaseTrackId = value
        }

    var albumId: String?
        get() = fields.albumId
        set(value) {
            fields.albumId = value
        }

    var artistId: String?
        get() = fields.artistId
        set(value) {
            fields.artistId = value
        }

    var albumArtistId: String?
        get() = fields.albumArtistId
        set(value) {
            fields.albumArtistId = value
        }

    var releaseGroupId: String?
        get() = fields.releaseGroupId
        set(value) {
            fields.releaseGroupId = value
        }

    var workId: String?
        get() = fields.workId
        set(value) {
            fields.workId = value
        }
}

/** Format-specific storage behind [AudioExtensions]. */
internal interface ExtensionFields {
    var titleSort: String?
    var artistSort: String?
    var albumSort: String?
    var albumArtistSort: String?
    var compilation: Boolean?
    var bpm: Int?
    var isrc: String?
    var originalDate: String?
    var r128TrackGain: String?
    var r128AlbumGain: String?
    val musicBrainz: MusicBrainzFields
}

/** Format-specific storage behind [MusicBrainzTags]. */
internal interface MusicBrainzFields {
    var recordingId: String?
    var releaseTrackId: String?
    var albumId: String?
    var artistId: String?
    var albumArtistId: String?
    var releaseGroupId: String?
    var workId: String?
}
