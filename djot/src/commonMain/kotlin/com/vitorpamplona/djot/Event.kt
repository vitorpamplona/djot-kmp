/*
 * Copyright (c) 2025 Vitor Pamplona
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the
 * Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.vitorpamplona.djot

/*
 * Ported from djot.js's event.ts and options.ts (MIT, Copyright (C) 2022 John MacFarlane).
 */

/**
 * One parser event: [annot] (such as `"+para"`, `"-para"` or `"str"`) for the source between
 * [startpos] and [endpos], both inclusive offsets in UTF-16 units.
 */
public data class Event(
    val startpos: Int,
    val endpos: Int,
    val annot: String,
)

/** Something the parser noticed but recovered from, such as an undefined reference. */
public class Warning(
    public val message: String,
    public val offset: Int? = null,
    public val sourceLoc: SourceLoc? = null,
) {
    public constructor(message: String, sourceLoc: SourceLoc) : this(message, sourceLoc.offset, sourceLoc)

    public fun render(): String {
        var result = message
        if (sourceLoc != null) {
            result += " at line ${sourceLoc.line}, col ${sourceLoc.col}"
        } else if (offset != null && offset != 0) {
            result += " at offset $offset"
        }
        return result
    }
}

/** Receives the parser's and renderer's warnings. */
public fun interface WarningHandler {
    public fun warn(warning: Warning)
}

/**
 * Parser options (djot.js's `ParseOptions`, which extends its `Options`): [sourcePositions] adds a
 * [Pos] to every node; [warn] receives what the parser recovered from.
 */
public class ParseOptions(
    public val sourcePositions: Boolean = false,
    public val warn: WarningHandler? = null,
)
