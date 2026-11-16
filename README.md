# kotlin-audio-metadata

Kotlin Multiplatform audio metadata reader and writer. Byte-based I/O lets callers read and update
tags without a `File`, and the same API is meant to work on every Kotlin target.

MP3/ID3, WAV and AIFF support on the JVM uses
[jaudiotagger](https://bitbucket.org/ijabz/jaudiotagger) 3.0.1 as a dependency
(LGPL-2.1-or-later); see [NOTICE.md](NOTICE.md) and [LICENSE](LICENSE).

## Supported formats

| Format | Read | Write |
|---|---|---|
| MP3 (ID3v2.2/2.3/2.4, ID3v1) | Yes | Yes (ID3v2) |
| FLAC | Yes | Yes |
| Ogg Vorbis | Yes | Yes |
| Opus | Yes | Yes |
| MP4 / M4A (iTunes metadata) | Yes | Yes |
| WAV (RIFF, embedded ID3) | Yes | Yes |
| AIFF (embedded ID3) | Yes | Yes |

Known limitations:

- Only the JVM target is wired up today. FLAC/Ogg/Opus/MP4 live in common code; MP3/WAV/AIFF are
  provided through a platform hook until the ID3 parser is rewritten in common Kotlin.
- Fragmented MP4 and non-iTunes MP4 metadata keys are not handled.
- ID3v2.2 files keep their existing artwork and ReplayGain frames; writing them requires 2.3/2.4.

## Usage

```kotlin
FileChannelSource(file).use { source ->
    val audio = AudioFile.read(source) ?: return
    audio.title = "New title"
    audio.artists = listOf("Artist", "Guest")
    audio.save(source)
}
```

`AudioFile` is an editable view over the parsed tags:

- High-level properties (`title`, `artists`, `trackNumber`, `replayGain`, ...) and the low-level tag
  (`vorbis`, `mp4`, `id3`) edit the same state, so changes are visible through both.
- `artworks` is `null` when the file was opened without pictures. In that case `save` leaves the
  embedded pictures untouched; assign an empty list to remove them.
- `save` writes only the data that changed and keeps unknown frames, blocks and atoms.

For a read-only value object use `toAudioMetadata()`. `AudioMetadataReader.read(source)` returns the
same snapshot without opening an editable file.

### Errors

- Unrecognised containers: `AudioFile.read` / `AudioMetadataReader.read` return `null`.
- Recognised container with malformed metadata: `AudioMetadataException` is thrown.

## Building

```shell
./gradlew jvmTest          # unit tests (real-file tests skip when testdata/ is absent)
./gradlew detekt           # static analysis, ktlint rules included
./gradlew publishToMavenLocal
```

Test fixtures from upstream jaudiotagger are not redistributed; real-file tests are skipped on a
clean checkout.
