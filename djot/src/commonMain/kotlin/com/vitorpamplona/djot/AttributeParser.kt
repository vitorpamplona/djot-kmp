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

// Ported from djot.js src/attributes.ts (MIT, Copyright (C) 2022 John MacFarlane).

/* Parser for attributes, implemented as a state machine.
 *
 * attributes { id = "foo", class = "bar baz",
 *              key1 = "val1", key2 = "val2" }
 * syntax:
 *
 * attributes <- '{' whitespace* attribute (whitespace attribute)* whitespace* '}'
 * attribute <- identifier | class | keyval
 * identifier <- '#' name
 * class <- '.' name
 * name <- (nonspace, nonpunctuation other than ':', '_', '-')+
 * keyval <- key '=' val
 * key <- (ASCII_ALPHANUM | ':' | '_' | '-')+
 * val <- bareval | quotedval
 * bareval <- (ASCII_ALPHANUM | ':' | '_' | '-')+
 * quotedval <- '"' ([^"] | '\"') '"'
 */

/** What [AttributeParser.feed] returns: [status] is "done", "fail" or "continue". */
internal class AttributeFeedResult(
    val status: String,
    val position: Int,
)

// JS `subject.charAt(pos)`, as a code unit; -1 stands for the "" charAt returns out of range.
private fun attrCharAt(
    subject: String,
    pos: Int,
): Int = if (pos >= 0 && pos < subject.length) subject[pos].code else -1

// /^[a-zA-Z0-9_:-]/
private fun isKeyChar(c: Int): Boolean =
    (c >= 'a'.code && c <= 'z'.code) ||
        (c >= 'A'.code && c <= 'Z'.code) ||
        (c >= '0'.code && c <= '9'.code) ||
        c == '_'.code ||
        c == ':'.code ||
        c == '-'.code

// /^\w/ (ASCII in JS)
private fun isAttrWordChar(c: Int): Boolean =
    (c >= 'a'.code && c <= 'z'.code) ||
        (c >= 'A'.code && c <= 'Z'.code) ||
        (c >= '0'.code && c <= '9'.code) ||
        c == '_'.code

// /^\s/ with JS's meaning: WhiteSpace and LineTerminator code units.
private fun isAttrWhitespace(c: Int): Boolean =
    c == 0x09 || c == 0x0A || c == 0x0B || c == 0x0C || c == 0x0D || c == 0x20 ||
        c == 0xA0 || c == 0x1680 || (c >= 0x2000 && c <= 0x200A) ||
        c == 0x2028 || c == 0x2029 || c == 0x202F || c == 0x205F ||
        c == 0x3000 || c == 0xFEFF

// /^[^\]\[~!@#$%^&*(){}`,.<>\\|=+/?\s]/: a nonempty character outside that set.
private fun isIdChar(c: Int): Boolean {
    if (c < 0) return false
    return when (c) {
        ']'.code, '['.code, '~'.code, '!'.code, '@'.code, '#'.code, '$'.code,
        '%'.code, '^'.code, '&'.code, '*'.code, '('.code, ')'.code, '{'.code,
        '}'.code, '`'.code, ','.code, '.'.code, '<'.code, '>'.code, '\\'.code,
        '|'.code, '='.code, '+'.code, '/'.code, '?'.code,
        -> false
        else -> !isAttrWhitespace(c)
    }
}

internal class AttributeParser(
    val subject: String,
) {
    // states
    internal enum class State {
        SCANNING,
        SCANNING_ID,
        SCANNING_CLASS,
        SCANNING_KEY,
        SCANNING_VALUE,
        SCANNING_BARE_VALUE,
        SCANNING_QUOTED_VALUE,
        SCANNING_QUOTED_VALUE_CONTINUATION,
        SCANNING_ESCAPED,
        SCANNING_ESCAPED_IN_CONTINUATION,
        SCANNING_COMMENT,
        FAIL,
        DONE,
        START,
    }

    // `begin` and `lastpos` are tested with JS truthiness in djot.js (`parser.begin && ...`),
    // so a position of 0 counts as unset there; the `x != null && x != 0` tests below keep that.
    var state: State = State.START
    var begin: Int? = null
    var lastpos: Int? = null
    val matches: MutableList<Event> = ArrayList()

    fun addEvent(
        startpos: Int,
        endpos: Int,
        annot: String,
    ) {
        matches.add(Event(startpos, endpos, annot))
    }

    /* Feed parser a slice of text from the subject, between
     * startpos and endpos inclusive.  Return object with
     * status and position,
     * where status is either "done" (position should point to
     * final '}'), "fail" (position should point to first character
     * that could not be parsed), or "continue" (position should
     * point to last character parsed). */
    fun feed(
        startpos: Int,
        endpos: Int,
    ): AttributeFeedResult {
        var pos = startpos
        while (pos <= endpos) {
            state = handle(state, pos)
            if (state == State.DONE) {
                return AttributeFeedResult("done", pos)
            } else if (state == State.FAIL) {
                lastpos = pos
                return AttributeFeedResult("fail", pos)
            } else {
                lastpos = pos
                pos = pos + 1
            }
        }
        return AttributeFeedResult("continue", endpos)
    }

    // djot.js's `handlers` table, one branch per state.
    private fun handle(
        state: State,
        pos: Int,
    ): State =
        when (state) {
            State.START -> handleStart(pos)
            State.FAIL -> State.FAIL
            State.DONE -> State.DONE
            State.SCANNING -> handleScanning(pos)
            State.SCANNING_COMMENT -> handleScanningComment(pos)
            State.SCANNING_ID -> handleScanningId(pos)
            State.SCANNING_CLASS -> handleScanningClass(pos)
            State.SCANNING_KEY -> handleScanningKey(pos)
            State.SCANNING_VALUE -> handleScanningValue(pos)
            State.SCANNING_BARE_VALUE -> handleScanningBareValue(pos)
            State.SCANNING_ESCAPED -> State.SCANNING_QUOTED_VALUE
            State.SCANNING_ESCAPED_IN_CONTINUATION -> State.SCANNING_QUOTED_VALUE_CONTINUATION
            State.SCANNING_QUOTED_VALUE -> handleScanningQuotedValue(pos)
            State.SCANNING_QUOTED_VALUE_CONTINUATION -> handleScanningQuotedValueContinuation(pos)
        }

    private fun handleStart(pos: Int): State =
        if (attrCharAt(subject, pos) == '{'.code) {
            State.SCANNING
        } else {
            State.FAIL
        }

    private fun handleScanning(pos: Int): State {
        val c = attrCharAt(subject, pos)
        if (c == '\n'.code || c == '\r'.code) {
            return State.SCANNING
        } else if (c == ' '.code || c == '\t'.code) {
            addEvent(pos, pos, "attr_space")
            return State.SCANNING
        } else if (c == '}'.code) {
            return State.DONE
        } else if (c == '#'.code) {
            begin = pos
            addEvent(pos, pos, "attr_id_marker")
            return State.SCANNING_ID
        } else if (c == '%'.code) {
            begin = pos
            return State.SCANNING_COMMENT
        } else if (c == '.'.code) {
            begin = pos
            addEvent(pos, pos, "attr_class_marker")
            return State.SCANNING_CLASS
        } else if (isKeyChar(c)) {
            begin = pos
            return State.SCANNING_KEY
        } else {
            return State.FAIL
        }
    }

    private fun handleScanningComment(pos: Int): State {
        val c = attrCharAt(subject, pos)
        if (c == '%'.code) {
            val b = begin
            if (b != null && b != 0 && pos > b) {
                addEvent(b, pos, "comment")
            }
            return State.SCANNING
        } else if (c == '}'.code) {
            return State.DONE
        } else {
            return State.SCANNING_COMMENT
        }
    }

    private fun handleScanningId(pos: Int): State {
        val c = attrCharAt(subject, pos)
        if (isIdChar(c)) {
            return State.SCANNING_ID
        } else if (c == '}'.code) {
            val b = begin
            val l = lastpos
            if (b != null && b != 0 && l != null && l != 0 && l > b) {
                addEvent(b + 1, l, "id")
            }
            begin = null
            return State.DONE
        } else if (isAttrWhitespace(c)) {
            val b = begin
            val l = lastpos
            if (b != null && b != 0 && l != null && l != 0 && l > b) {
                addEvent(b + 1, l, "id")
            }
            if (!(c == '\r'.code || c == '\n'.code)) {
                addEvent(pos, pos, "attr_space")
            }
            begin = null
            return State.SCANNING
        } else {
            return State.FAIL
        }
    }

    private fun handleScanningClass(pos: Int): State {
        val c = attrCharAt(subject, pos)
        if (isAttrWordChar(c) || c == '_'.code || c == '-'.code || c == ':'.code) {
            return State.SCANNING_CLASS
        } else if (c == '}'.code) {
            val b = begin
            val l = lastpos
            if (b != null && b != 0 && l != null && l != 0 && l > b) {
                addEvent(b + 1, l, "class")
            }
            begin = null
            return State.DONE
        } else if (isAttrWhitespace(c)) {
            val b = begin
            val l = lastpos
            if (b != null && b != 0 && l != null && l != 0 && l > b) {
                addEvent(b + 1, l, "class")
            }
            if (!(c == '\r'.code || c == '\n'.code)) {
                addEvent(pos, pos, "attr_space")
            }
            begin = null
            return State.SCANNING
        } else {
            return State.FAIL
        }
    }

    private fun handleScanningKey(pos: Int): State {
        val c = attrCharAt(subject, pos)
        val b = begin
        val l = lastpos
        if (c == '='.code && b != null && b != 0 && l != null && l != 0) {
            addEvent(b, l, "key")
            addEvent(pos, pos, "attr_equal_marker")
            begin = null
            return State.SCANNING_VALUE
        } else if (isKeyChar(c)) {
            return State.SCANNING_KEY
        } else {
            return State.FAIL
        }
    }

    private fun handleScanningValue(pos: Int): State {
        val c = attrCharAt(subject, pos)
        if (c == '"'.code) {
            begin = pos
            addEvent(pos, pos, "attr_quote_marker")
            return State.SCANNING_QUOTED_VALUE
        } else if (isKeyChar(c)) {
            begin = pos
            return State.SCANNING_BARE_VALUE
        } else {
            return State.FAIL
        }
    }

    private fun handleScanningBareValue(pos: Int): State {
        val c = attrCharAt(subject, pos)
        val b = begin
        val l = lastpos
        if (isKeyChar(c)) {
            return State.SCANNING_BARE_VALUE
        } else if (c == '}'.code && b != null && b != 0 && l != null && l != 0) {
            addEvent(b, l, "value")
            begin = null
            return State.DONE
        } else if (isAttrWhitespace(c) && b != null && b != 0 && l != null && l != 0) {
            addEvent(b, l, "value")
            if (!(c == '\r'.code || c == '\n'.code)) {
                addEvent(pos, pos, "attr_space")
            }
            begin = null
            return State.SCANNING
        } else {
            return State.FAIL
        }
    }

    private fun handleScanningQuotedValue(pos: Int): State {
        val c = attrCharAt(subject, pos)
        val b = begin
        val l = lastpos
        if (c == '"'.code && b != null && b != 0 && l != null && l != 0) {
            addEvent(b + 1, l, "value")
            addEvent(pos, pos, "attr_quote_marker")
            begin = null
            return State.SCANNING
        } else if (c == '\n'.code && b != null && b != 0 && l != null && l != 0) {
            addEvent(b + 1, pos, "value")
            begin = null
            return State.SCANNING_QUOTED_VALUE_CONTINUATION
        } else if (c == '\\'.code) {
            return State.SCANNING_ESCAPED
        } else {
            return State.SCANNING_QUOTED_VALUE
        }
    }

    private fun handleScanningQuotedValueContinuation(pos: Int): State {
        val c = attrCharAt(subject, pos)
        if (begin == null) {
            begin = pos
        }
        val b = begin
        val l = lastpos
        if (c == '"'.code && b != null && b != 0 && l != null && l != 0) {
            addEvent(pos, pos, "attr_quote_marker")
            addEvent(b, l, "value")
            begin = null
            return State.SCANNING
        } else if (c == '\n'.code && b != null && b != 0 && l != null && l != 0) {
            addEvent(b, pos, "value")
            begin = null
            return State.SCANNING_QUOTED_VALUE_CONTINUATION
        } else if (c == '\\'.code) {
            return State.SCANNING_ESCAPED_IN_CONTINUATION
        } else {
            return State.SCANNING_QUOTED_VALUE_CONTINUATION
        }
    }
}
