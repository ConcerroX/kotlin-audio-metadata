# kotlin-audio-metadata

Kotlin Multiplatform audio metadata reader and writer. Byte-based I/O lets callers read and update
tags without a `File`, and the same API is meant to work on every Kotlin target.

All format handling lives in common code, written from the published format specifications and
RFCs; see [NOTICE.md](NOTICE.md). Behavior and field mappings were informed by jaudiotagger, which
is used only as a test-time comparison oracle.

API documentation: https://concerrox.github.io/kotlin-audio-metadata/

## Supported formats

| Format | Read | Write |
|---|---|---|
| MP3 (ID3v2.2/2.3/2.4, ID3v1) | Yes | Yes (ID3v2.2/2.3/2.4; ID3v1 updated when present) |
| FLAC | Yes | Yes |
| Ogg Vorbis | Yes | Yes |
| Opus | Yes | Yes |
| MP4 / M4A (iTunes metadata) | Yes | Yes |
| WAV (RIFF, embedded ID3) | Yes | Yes |
| AIFF (embedded ID3) | Yes | Yes |

Known limitations:

- Targets: JVM, JS (Node.js), MinGW x64 and Linux x64. All container parsers run in common code;
  only the JVM target ships a file-backed source (`FileChannelSource`). Other targets use
  `ByteArraySource` or a custom `RandomAccessSource`.
- Fragmented MP4 is not specially handled. MP4 keys without a typed property are available through
  `Mp4Tag.items` rather than typed properties.
- ID3v2.2 keeps its existing artwork when saving and does not write the extension fields (sort
  keys, compilation, MusicBrainz, R128).
- WAV and AIFF are written as an embedded ID3 chunk. When a file has no ID3 chunk, WAV
  `LIST/INFO` and AIFF `NAME`/`AUTH`/`ANNO` text chunks are read as a fallback.
- Files carrying only an ID3v1 tag are exposed through the same editable model. Saving writes an
  ID3v2 tag and refreshes an existing ID3v1 tag; files without ID3v1 do not gain one.

## Usage

```kotlin
FileChannelSource(file).use { source ->
    val audio = AudioFile.read(source) ?: return
    audio.title = "New title"
    audio.artists = listOf("Artist", "Guest")
    audio.extensions.musicBrainz.recordingId = "8f3471b5-7e6a-48da-86a9-c1c07a0f47ae"
    audio.save(source)
}
```

`AudioFile` is an editable view over the parsed tags:

- High-level properties (`title`, `artists`, `trackNumber`, `lyrics`, `replayGain`, ...) and the
  low-level tag (`vorbis`, `mp4`, `id3`) edit the same state, so changes are visible through both.
  Lyrics use USLT on ID3, LYRICS on Vorbis comments and `©lyr` on MP4.
- `artworks` is `null` when the file was opened without pictures. In that case `save` leaves the
  embedded pictures untouched; assign an empty list to remove them.
- `extensions` holds well-known non-core tags (sort keys, compilation flag, BPM, rating, grouping,
  copyright, encoder, ISRC, MusicBrainz IDs, R128, original date) in their own namespace. Ratings
  use a 0-100 scale; MP4 additionally exposes `contentRating` (`rtng`).
- `save` writes only the data that changed and keeps unknown frames, blocks and atoms.

Every low-level tag also exposes entries that have no typed property:

```kotlin
audio.id3?.setTextFrame("TPUB", listOf("Publisher"))       // or setFrame(id, rawBytes)
audio.id3?.removeFrame("PRIV")
audio.mp4?.setItem("©grp", Mp4Item.TYPE_UTF8, "Grouping".encodeToByteArray())
audio.mp4?.setFreeform("LABEL", "Record Label")            // ----:com.apple.iTunes:LABEL
audio.vorbis?.set("MOOD", "Calm")
```

`Mp4Tag.item(type)` / `freeformItem(name)` and `Id3v2Tag.framesOf(id)` read those entries back.

On the JVM, `File` convenience functions open and close the source for you:

```kotlin
val audio = file.readAudioFile()
file.updateAudioFile { it.title = "New title" }
val snapshot = file.readAudioMetadata()
```

For a read-only value object use `toAudioMetadata()`. `AudioMetadataReader.read(source)` returns the
same snapshot without opening an editable file.

### Errors

- Unrecognised containers: `AudioFile.read` / `AudioMetadataReader.read` return `null`.
- Recognised container with malformed metadata: `AudioMetadataException` is thrown.

## License

Apache License, Version 2.0. See [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md).

## Building

```shell
./gradlew jvmTest                      # unit tests (real-file tests skip when testdata/ is absent)
./gradlew jsNodeTest                   # common tests on Node.js
./gradlew mingwX64Test                 # common tests on Windows native
./gradlew detekt                       # static analysis, ktlint rules included
./gradlew dokkaGeneratePublicationHtml # API documentation in build/dokka/html
./gradlew publishToMavenLocal
```

Test fixtures used by the differential tests are not redistributed; real-file tests are skipped on
a clean checkout.
