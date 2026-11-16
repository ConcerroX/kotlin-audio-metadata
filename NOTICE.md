# Notices

kotlin-audio-metadata provides a Kotlin Multiplatform API over audio metadata. MP3/ID3, WAV and
AIFF support on the JVM uses the jaudiotagger library as a dependency; field mappings and parsing
behavior are ported from it. This project is not affiliated with or endorsed by the upstream
authors.

Upstream lineage:

- Entagged audio tag library, Copyright (C) 2003-2005 Raphael Slinckx (LGPL-2.1-or-later)
- jaudiotagger / MusicTag, Copyright (C) 2003-2015 Paul Taylor, Eric Farng and contributors (LGPL-2.1-or-later)

Dependency: `net.jthink:jaudiotagger` 3.0.1
(https://bitbucket.org/ijabz/jaudiotagger, tag v3.0.1). No upstream source code is embedded in
this repository.

The Opus reader/writer and the OggPage helper in commonMain are ported from the jaudiotagger 2.3.x
fork (https://github.com/Adonai/jaudiotagger, version 2.3.15) and adapted to the 3.0.1 APIs.

Because this project contains code derived from the works above, it is distributed under the GNU
Lesser General Public License, version 2.1 or later. See LICENSE.
