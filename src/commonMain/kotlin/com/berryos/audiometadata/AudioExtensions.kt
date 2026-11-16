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

    /** ID3: TSRC, Vorbis: ISRC. MP4 stores it in a freeform atom and is not wired yet. */
    var isrc: String?
        get() = fields.isrc
        set(value) {
            fields.isrc = value
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
}
