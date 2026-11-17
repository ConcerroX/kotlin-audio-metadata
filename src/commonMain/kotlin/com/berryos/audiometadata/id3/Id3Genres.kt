package com.berryos.audiometadata.id3

/**
 * Genre names from the ID3v1 list (0-79) plus the Winamp extensions published with ID3v2.3
 * (80-125).
 */
internal object Id3Genres {
    private val NAMES = listOf(
        "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop",
        "Jazz", "Metal", "New Age", "Oldies", "Other", "Pop", "R&B", "Rap",
        "Reggae", "Rock", "Techno", "Industrial", "Alternative", "Ska", "Death Metal", "Pranks",
        "Soundtrack", "Euro-Techno", "Ambient", "Trip-Hop", "Vocal", "Jazz+Funk", "Fusion", "Trance",
        "Classical", "Instrumental", "Acid", "House", "Game", "Sound Clip", "Gospel", "Noise",
        "AlternRock", "Bass", "Soul", "Punk", "Space", "Meditative", "Instrumental Pop", "Instrumental Rock",
        "Ethnic", "Gothic", "Darkwave", "Techno-Industrial", "Electronic", "Pop-Folk", "Eurodance", "Dream",
        "Southern Rock", "Comedy", "Cult", "Gangsta", "Top 40", "Christian Rap", "Pop/Funk", "Jungle",
        "Native American", "Cabaret", "New Wave", "Psychadelic", "Rave", "Showtunes", "Trailer", "Lo-Fi",
        "Tribal", "Acid Punk", "Acid Jazz", "Polka", "Retro", "Musical", "Rock & Roll", "Hard Rock",
        "Folk", "Folk-Rock", "National Folk", "Swing", "Fast Fusion", "Bebob", "Latin", "Revival",
        "Celtic", "Bluegrass", "Avantgarde", "Gothic Rock",
        "Progressive Rock", "Psychedelic Rock", "Symphonic Rock", "Slow Rock",
        "Big Band", "Chorus", "Easy Listening", "Acoustic", "Humour", "Speech", "Chanson", "Opera",
        "Chamber Music", "Sonata", "Symphony", "Booty Bass",
        "Primus", "Porn Groove", "Satire", "Slow Jam",
        "Club", "Tango", "Samba", "Folklore", "Ballad", "Power Ballad", "Rhythmic Soul", "Freestyle",
        "Duet", "Punk Rock", "Drum Solo", "A cappella", "Euro-House", "Dance Hall",
    )

    private val REFERENCE = Regex("\\((\\d+)\\)")

    fun name(index: Int): String? = NAMES.getOrNull(index)

    fun index(name: String): Int? = NAMES.indexOfFirst { candidate -> candidate.equals(name, ignoreCase = true) }
        .takeIf { it >= 0 }

    /**
     * Resolves a TCON value.
     *
     * ID3v2.3 stores genres as `(number)` with an optional refinement, e.g. `(17)Alternative`.
     * Values without a numeric reference are returned unchanged.
     */
    fun resolve(value: String): List<String> {
        if (value == "(RX)" || value == "RX") return listOf("Remix")
        if (value == "(CR)" || value == "CR") return listOf("Cover")
        val matches = REFERENCE.findAll(value).toList()
        if (matches.isEmpty()) return listOf(value)
        val resolved = matches.mapNotNull { match -> match.groupValues[1].toIntOrNull()?.let(::name) }
        return resolved.ifEmpty { listOf(value) }
    }

    fun resolveAll(values: List<String>): List<String> = values.flatMap(::resolve)
}
