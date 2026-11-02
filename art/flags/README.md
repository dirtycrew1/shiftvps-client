# Native country flags

These unmodified SVG sources come from [flag-icons](https://github.com/lipis/flag-icons), pinned at revision `ac7e0eb3259eb62aa6100c435607e2fe03896a97`, directory `flags/4x3`.

The source and generated native VectorDrawables are licensed under MIT, copyright (c) 2013 Panayiotis Lipiridis. The full license is preserved here and bundled as `files/flag_icons_license.txt`.

`generate.py` expands source transforms, references, circles and paths into shared VectorDrawable geometry. Regenerate from this directory with `python generate.py` using svglib/reportlab. These tools are not application dependencies. The flat 4:3 icon composition is retained, including the full Kazakhstan emblem and Israel star. No flag emoji glyph or OS font support is needed.

Only authoritative two-letter server country metadata selects a flag. Server labels and hostnames are never parsed for a country; unknown or unsupported metadata displays the existing neutral globe. Names remain readable text and the flag is decorative for accessibility.
