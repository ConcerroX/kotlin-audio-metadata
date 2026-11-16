# Notices

kotlin-audio-metadata is a Kotlin derivation of the jaudiotagger audio
tagging library. Parsing behavior, field mappings and format handling are
ported from jaudiotagger; this project is not affiliated with or endorsed by
the upstream authors.

Upstream lineage:

- Entagged audio tag library, Copyright (C) 2003-2005 Raphael Slinckx (LGPL-2.1-or-later)
- jaudiotagger / MusicTag, Copyright (C) 2003-2015 Paul Taylor, Eric Farng and contributors (LGPL-2.1-or-later)

Source base: official jaudiotagger 3.0.1
(https://bitbucket.org/ijabz/jaudiotagger, tag v3.0.1, commit b885903).

The Opus reader/writer and the OggPage helper are ported from the
jaudiotagger 2.3.x fork (https://github.com/Adonai/jaudiotagger, version
2.3.15) and adapted to the 3.0.1 APIs. Modified files carry a note where
the behavior differs from the fork.

Because this project contains code derived from the works above, it is
distributed under the GNU Lesser General Public License, version 2.1 or
later. See LICENSE.
