# Notices

kotlin-audio-metadata is an independent Kotlin Multiplatform implementation of audio metadata
readers and writers.

The parsers and writers are implemented from the published format specifications:

- ID3v1, ID3v2.2, ID3v2.3 and ID3v2.4 (id3.org)
- RFC 3533 (Ogg encapsulation) and RFC 7845 (Ogg Opus)
- RFC 9639 (FLAC)
- Vorbis I specification and Vorbis comment specification (Xiph.Org)
- RIFF/WAVE, RF64 (EBU Tech 3306) and AIFF/AIFF-C specifications
- QuickTime file format documentation (Apple)

Behavior and field mappings were informed by the jaudiotagger project
(https://bitbucket.org/ijabz/jaudiotagger, LGPL-2.1-or-later) and its Entagged predecessor. No
source code from those projects is included in this repository or in the published artifacts;
jaudiotagger is used only as a test-time comparison oracle.

The library is distributed under the Apache License, Version 2.0. See LICENSE.
