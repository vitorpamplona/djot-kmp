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

import kotlin.jvm.JvmName

// Ported from djot.js src/inline.ts (MIT, Copyright (C) 2022 John MacFarlane).

// General note on the parsing strategy:  our objective is to
// parse without backtracking. To that end, we keep a stack of
// potential 'openers' for links, images, emphasis, and other
// inline containers.  When we parse a potential closer for
// one of these constructions, we can scan the stack of openers
// for a match, which will tell us the location of the potential
// opener. We can then change the annotation of the match at
// that location to '+emphasis' or whatever.

// Opener:
// 1 = startpos, 2 = endpos, 3 = annotation, 4 = substartpos, 5 = endpos
//
// [link text](url)
// ^         ^^
// 1         23
// startpos    (1)
// endpos      (1)
// substartpos (2)
// subendpos   (3)
// annot       "explicit_link"

/** A slice of the subject (both ends inclusive) tried as attributes; djot.js's `{ startpos, endpos }`. */
internal data class AttributeSlice(
    val startpos: Int,
    val endpos: Int,
)

private const val C_TAB = 9
private const val C_LF = 10
private const val C_CR = 13
private const val C_SPACE = 32
private const val C_BANG = 33
private const val C_DOUBLE_QUOTE = 34
private const val C_DOLLARS = 36
private const val C_SINGLE_QUOTE = 39
private const val C_LEFT_PAREN = 40
private const val C_RIGHT_PAREN = 41
private const val C_ASTERISK = 42
private const val C_PLUS = 43
private const val C_HYPHEN = 45
private const val C_PERIOD = 46
private const val C_COLON = 58
private const val C_LESSTHAN = 60
private const val C_EQUALS = 61
private const val C_LEFT_BRACKET = 91
private const val C_BACKSLASH = 92
private const val C_RIGHT_BRACKET = 93
private const val C_HAT = 94
private const val C_UNDERSCORE = 95
private const val C_BACKTICK = 96
private const val C_LEFT_BRACE = 123
private const val C_RIGHT_BRACE = 125
private const val C_TILDE = 126

// JS `subject.codePointAt(pos)` / `charCodeAt(pos)` as compared in this file: the code unit at
// pos, or -1 where JS gives undefined / NaN (out of range). Every comparison here is against an
// ASCII constant, so reading one code unit instead of a whole code point changes no result.
private fun inlineCodeAt(
    s: String,
    pos: Int,
): Int = if (pos >= 0 && pos < s.length) s[pos].code else -1

// JS truthiness of a `number | null`: null and 0 are false.
private fun inlineTruthy(x: Int?): Boolean = x != null && x != 0

// JS `a || b` for a `number | null` a.
private fun inlineOr(
    a: Int?,
    b: Int,
): Int = if (a == null || a == 0) b else a

// JS `\s`: WhiteSpace and LineTerminator code units.
private fun inlineIsJsSpace(c: Int): Boolean =
    c == 0x09 || c == 0x0A || c == 0x0B || c == 0x0C || c == 0x0D || c == 0x20 ||
        c == 0xA0 || c == 0x1680 || (c >= 0x2000 && c <= 0x200A) ||
        c == 0x2028 || c == 0x2029 || c == 0x202F || c == 0x205F ||
        c == 0x3000 || c == 0xFEFF

// JS `\w`: ASCII letters, digits and underscore.
private fun inlineIsWordChar(c: Int): Boolean =
    (c >= 'a'.code && c <= 'z'.code) ||
        (c >= 'A'.code && c <= 'Z'.code) ||
        (c >= '0'.code && c <= '9'.code) ||
        c == '_'.code

// reSpecial = /[\r\n"'()*+.:<=[\\\]^_`${}~-]/g, as a table: every special character is ASCII.
private val isSpecial: BooleanArray =
    BooleanArray(128).also { table ->
        for (ch in "\r\n\"'()*+.:<=[\\]^_`\${}~-") {
            table[ch.code] = true
        }
    }

// find first special character starting from startpos, and not
// going beyond endpos, or null if none found.
// (djot.js scans 32 characters by hand, then hands over to reSpecial; both find the first
// special code unit in [startpos, endpos] that lies inside the subject, as this loop does.)
private fun findSpecial(
    s: String,
    startpos: Int,
    endpos: Int,
): Int? {
    val stop = minOf(endpos, s.length - 1)
    var i = maxOf(startpos, 0)
    while (i <= stop) {
        val c = s[i].code
        if (c < 128 && isSpecial[c]) {
            return i
        }
        i++
    }
    return null
}

// Whether there is a character at pos and it is none of space, tab, CR or
// LF.
private fun isNonspaceAt(
    subject: String,
    pos: Int,
): Boolean {
    if (pos >= subject.length) {
        return false // nothing there
    }
    val c = inlineCodeAt(subject, pos)
    return c != C_SPACE && c != C_TAB && c != C_CR && c != C_LF
}

// A character that `{` can mark as an explicit opener, as in `{*bold*}`.
private fun isDelimChar(c: Int): Boolean =
    c == C_ASTERISK || c == C_PLUS || c == C_HYPHEN ||
        c == C_EQUALS || c == C_HAT || c == C_UNDERSCORE ||
        c == C_TILDE || c == C_SINGLE_QUOTE || c == C_DOUBLE_QUOTE

// ---------------------------------------------------------------------------------------------
// The sticky patterns of djot.js, matched by hand. djot.js's `find(subject, patt, startpos,
// endpos)` runs a sticky regex at startpos over `subject.substring(0, endpos + 1)`; each function
// below does the same and returns the match's endpos (inclusive), or null when there is no match
// (the match's startpos is always the startpos passed in).

// The end of the text find() searches: `subject.substring(0, endpos + 1).length`.
private fun findLimit(
    subject: String,
    endpos: Int,
): Int {
    val e = endpos + 1
    return if (e < 0) {
        0
    } else if (e > subject.length) {
        subject.length
    } else {
        e
    }
}

// pattLineEnd = /[ \t]*\r?\n/y
private fun findLineEnd(
    s: String,
    startpos: Int,
    endpos: Int,
): Int? {
    val limit = findLimit(s, endpos)
    var i = startpos
    while (i < limit && (s[i] == ' ' || s[i] == '\t')) {
        i++
    }
    if (i < limit && s[i] == '\r') {
        return if (i + 1 < limit && s[i + 1] == '\n') i + 1 else null
    }
    return if (i < limit && s[i] == '\n') i else null
}

// pattPunctuation: one ASCII punctuation character, !"#$%&'()*+,-./:;<=>?@[\]^_`{|}~
private fun findPunctuation(
    s: String,
    startpos: Int,
    endpos: Int,
): Int? {
    val limit = findLimit(s, endpos)
    if (startpos < 0 || startpos >= limit) return null
    val c = s[startpos].code
    val punct =
        (c in 33..47) || (c in 58..64) || (c in 91..96) || (c in 123..126)
    return if (punct) startpos else null
}

// pattAutolink = /[<][^<>\s]+[>]/y
private fun findAutolink(
    s: String,
    startpos: Int,
    endpos: Int,
): Int? {
    val limit = findLimit(s, endpos)
    if (startpos < 0 || startpos >= limit || s[startpos] != '<') return null
    var i = startpos + 1
    while (i < limit) {
        val c = s[i].code
        if (c == '<'.code || c == '>'.code || inlineIsJsSpace(c)) break
        i++
    }
    return if (i > startpos + 1 && i < limit && s[i] == '>') i else null
}

// pattEmail = /[^:]+@/y (only whether it matches is used)
private fun matchesEmail(
    s: String,
    startpos: Int,
    endpos: Int,
): Boolean {
    val limit = findLimit(s, endpos)
    if (startpos < 0) return false
    var i = startpos
    while (i < limit) {
        val c = s[i]
        if (c == ':') return false
        if (c == '@' && i > startpos) return true
        i++
    }
    return false
}

// pattUrl = /[a-zA-Z0-9+.-]+:/y (only whether it matches is used)
private fun matchesUrl(
    s: String,
    startpos: Int,
    endpos: Int,
): Boolean {
    val limit = findLimit(s, endpos)
    if (startpos < 0) return false
    var i = startpos
    while (i < limit) {
        val c = s[i]
        val ok =
            (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') ||
                c == '+' || c == '.' || c == '-'
        if (!ok) break
        i++
    }
    return i > startpos && i < limit && s[i] == ':'
}

// pattSymbol = /:[\w_+-]+:/y
private fun findSymbol(
    s: String,
    startpos: Int,
    endpos: Int,
): Int? {
    val limit = findLimit(s, endpos)
    if (startpos < 0 || startpos >= limit || s[startpos] != ':') return null
    var i = startpos + 1
    while (i < limit) {
        val c = s[i].code
        if (!(inlineIsWordChar(c) || c == '+'.code || c == '-'.code)) break
        i++
    }
    return if (i > startpos + 1 && i < limit && s[i] == ':') i else null
}

// pattBackticks0 = /`*/y (may match empty, then endpos = startpos - 1)
private fun findBackticks0(
    s: String,
    startpos: Int,
    endpos: Int,
): Int? {
    val limit = findLimit(s, endpos)
    if (startpos < 0 || startpos > limit) return null
    var i = startpos
    while (i < limit && s[i] == '`') {
        i++
    }
    return i - 1
}

// pattBackticks1 = /`+/y
private fun findBackticks1(
    s: String,
    startpos: Int,
    endpos: Int,
): Int? {
    val limit = findLimit(s, endpos)
    if (startpos < 0) return null
    var i = startpos
    while (i < limit && s[i] == '`') {
        i++
    }
    return if (i > startpos) i - 1 else null
}

// pattRawAttribute = /\{=[^\s{}`]+\}/y
private fun findRawAttribute(
    s: String,
    startpos: Int,
    endpos: Int,
): Int? {
    val limit = findLimit(s, endpos)
    if (startpos < 0 || startpos + 1 >= limit || s[startpos] != '{' || s[startpos + 1] != '=') {
        return null
    }
    var i = startpos + 2
    while (i < limit) {
        val c = s[i].code
        if (inlineIsJsSpace(c) || c == '{'.code || c == '}'.code || c == '`'.code) break
        i++
    }
    return if (i > startpos + 2 && i < limit && s[i] == '}') i else null
}

// ---------------------------------------------------------------------------------------------

private fun hasBrace(
    self: InlineParser,
    pos: Int,
): Boolean =
    (pos > 0 && inlineCodeAt(self.subject, pos - 1) == C_LEFT_BRACE) ||
        inlineCodeAt(self.subject, pos + 1) == C_RIGHT_BRACE

private fun alwaysTrue(
    self: InlineParser,
    pos: Int,
): Boolean = true

private fun betweenMatched(
    c: String,
    annotation: String,
    defaultmatch: String,
    opentest: (InlineParser, Int) -> Boolean,
): (InlineParser, Int, Int) -> Int =
    fun(
        self: InlineParser,
        pos: Int,
        endpos: Int,
    ): Int {
        val subject = self.subject
        var canOpen = isNonspaceAt(subject, pos + 1) && opentest(self, pos)
        // The sticky pattern this replaces clamped a negative start to 0, so at
        // the very start of the subject it looked at the delimiter itself, which
        // is never a space.  Keep that.
        var canClose = isNonspaceAt(subject, if (pos > 0) pos - 1 else 0)
        val lastmatch = self.matches.lastOrNull()
        val hasOpenMarker = lastmatch != null && lastmatch.annot == "open_marker"
        val hasCloseMarker =
            pos + 1 <= endpos &&
                inlineCodeAt(subject, pos + 1) == C_RIGHT_BRACE
        var endcloser = pos
        var startopener = pos

        // Allow explicit open/close markers to override:
        if (hasOpenMarker) {
            canOpen = true
            canClose = false
            startopener = pos - 1
        }
        if (!hasOpenMarker && hasCloseMarker) {
            canClose = true
            canOpen = false
            endcloser = pos + 1
        }

        var match = defaultmatch
        if (hasOpenMarker && match.startsWith("right")) {
            match = "left" + match.substring("right".length)
        } else if (hasCloseMarker && match.startsWith("left")) {
            match = "right" + match.substring("left".length)
        }

        var d = c
        if (hasCloseMarker) {
            d = "{" + d
        }
        val openers = self.openers[d]

        if (canClose && openers != null && openers.size > 0) {
            // check openers for a match
            val opener = openers[openers.size - 1]
            if (opener.endpos != pos - 1) { // exclude empty emph
                // When inside a link destination, don't match openers from outside
                // the link construct (before the [ that started this link)
                if (self.destination) {
                    val linkOpeners = self.openers["["]
                    if (linkOpeners != null && linkOpeners.size > 0) {
                        val linkOpener = linkOpeners[linkOpeners.size - 1]
                        if (linkOpener.annot == "explicit_link" &&
                            opener.startpos < linkOpener.startpos
                        ) {
                            // opener is outside the link, don't match
                            self.addMatch(pos, endcloser, match)
                            return endcloser + 1
                        }
                    }
                }
                self.clearOpeners(opener.startpos, pos)
                self.addMatch(opener.startpos, opener.endpos, "+$annotation", opener.matchIndex)
                self.addMatch(pos, endcloser, "-$annotation")
                return endcloser + 1
            }
        }

        // If we get here, we didn't match an opener:
        if (canOpen) {
            var e = c
            if (hasOpenMarker) {
                e = "{" + e
            }
            self.addOpener(e, startopener, pos, match)
            return pos + 1
        } else {
            self.addMatch(pos, endcloser, match)
            return endcloser + 1
        }
    }

// djot.js builds `betweenMatched("-", "delete", "str", hasBrace)` on each call of the hyphen
// handler; it holds no state, so one copy serves.
private val deleteMatcher = betweenMatched("-", "delete", "str", ::hasBrace)

private fun matchBacktick(
    self: InlineParser,
    pos: Int,
    endpos: Int,
): Int? {
    val subject = self.subject
    val mEnd = findBackticks0(subject, pos, endpos) ?: return null
    val endchar = mEnd
    // a $ immediately before the backticks is a math prefix, unless
    // it was backslash-escaped (in which case the preceding match is
    // an "escape" at the backslash's position):
    val prevMatch = self.matches.getOrNull(self.matches.size - 2)
    val dollarEscaped =
        prevMatch != null &&
            prevMatch.annot == "escape" && prevMatch.endpos == pos - 2
    if (pos >= 2 && inlineCodeAt(subject, pos - 2) == C_DOLLARS &&
        inlineCodeAt(subject, pos - 1) == C_DOLLARS &&
        !(pos >= 3 && inlineCodeAt(subject, pos - 3) == C_BACKSLASH)
    ) {
        self.popMatch() // remove first $
        self.popMatch() // remove second $
        self.addMatch(pos - 2, endchar, "+display_math")
        self.verbatimType = "display_math"
    } else if (pos >= 1 && inlineCodeAt(subject, pos - 1) == C_DOLLARS &&
        !dollarEscaped
    ) {
        self.popMatch() // remove $
        self.addMatch(pos - 1, endchar, "+inline_math")
        self.verbatimType = "inline_math"
    } else {
        self.addMatch(pos, endchar, "+verbatim")
        self.verbatimType = "verbatim"
    }
    self.verbatim = endchar - pos + 1
    return endchar + 1
}

private fun matchBackslash(
    self: InlineParser,
    pos: Int,
    endpos: Int,
): Int? {
    val subject = self.subject
    val mLineEnd = findLineEnd(subject, pos + 1, endpos)
    if (mLineEnd != null) {
        // see if there were preceding spaces and remove them
        if (self.matches.size > 0) {
            val lastIndex = self.matches.size - 1
            val lastmatch = self.matches[lastIndex]
            if (lastmatch.annot == "str") {
                var ep = lastmatch.endpos
                val sp = lastmatch.startpos
                while (ep >= sp &&
                    (inlineCodeAt(subject, ep) == C_SPACE || inlineCodeAt(subject, ep) == C_TAB)
                ) {
                    ep = ep - 1
                }
                if (ep < sp) {
                    self.popMatch()
                } else {
                    self.matches[lastIndex] = lastmatch.copy(endpos = ep)
                }
            }
        }
        self.addMatch(pos, pos, "escape")
        self.addMatch(pos + 1, mLineEnd, "hard_break")
        return mLineEnd + 1
    } else {
        val mPunct = findPunctuation(subject, pos + 1, endpos)
        if (mPunct != null) {
            self.addMatch(pos, pos, "escape")
            self.addMatch(pos + 1, mPunct, "str")
            return mPunct + 1
        } else if (pos + 1 <= endpos && inlineCodeAt(subject, pos + 1) == C_SPACE) {
            self.addMatch(pos, pos, "escape")
            self.addMatch(pos + 1, pos + 1, "non_breaking_space")
            return pos + 2
        } else {
            self.addMatch(pos, pos, "str")
            return pos + 1
        }
    }
}

private fun matchLessThan(
    self: InlineParser,
    pos: Int,
    endpos: Int,
): Int? {
    val subject = self.subject
    val endurl = findAutolink(subject, pos, endpos) ?: return null
    val starturl = pos
    val isEmail = matchesEmail(subject, pos + 1, endurl)
    val isUrl = matchesUrl(subject, pos + 1, endurl)
    if (isEmail) {
        self.addMatch(starturl, starturl, "+email")
        self.addMatch(starturl + 1, endurl - 1, "str")
        self.addMatch(endurl, endurl, "-email")
        return endurl + 1
    } else if (isUrl) {
        self.addMatch(starturl, starturl, "+url")
        self.addMatch(starturl + 1, endurl - 1, "str")
        self.addMatch(endurl, endurl, "-url")
        return endurl + 1
    }
    return null
}

private fun singleQuoteOpenTest(
    self: InlineParser,
    pos: Int,
): Boolean {
    if (pos == 0) {
        return true
    } else {
        val cp = inlineCodeAt(self.subject, pos - 1)
        return (
            cp == C_SPACE ||
                cp == C_TAB ||
                cp == C_CR ||
                cp == C_LF ||
                cp == C_DOUBLE_QUOTE ||
                cp == C_SINGLE_QUOTE ||
                cp == C_HYPHEN ||
                cp == C_LEFT_PAREN ||
                cp == C_LEFT_BRACKET
        )
    }
}

private fun matchLeftBrace(
    self: InlineParser,
    pos: Int,
    endpos: Int,
): Int? {
    if (pos + 1 <= endpos && isDelimChar(inlineCodeAt(self.subject, pos + 1))) {
        self.addMatch(pos, pos, "open_marker")
        return pos + 1
    } else if (self.allowAttributes) {
        self.attributeParser = AttributeParser(self.subject)
        self.attributeStart = pos
        self.attributeSlices = ArrayList()
        return pos
    } else {
        self.addMatch(pos, pos, "str")
        return pos + 1
    }
}

private fun matchColon(
    self: InlineParser,
    pos: Int,
    endpos: Int,
): Int? {
    val m = findSymbol(self.subject, pos, endpos)
    if (m != null) {
        self.addMatch(pos, m, "symb")
        return m + 1
    } else {
        self.addMatch(pos, pos, "str")
        return pos + 1
    }
}

private fun matchPeriod(
    self: InlineParser,
    pos: Int,
    endpos: Int,
): Int? {
    if (pos + 2 <= endpos &&
        inlineCodeAt(self.subject, pos + 1) == C_PERIOD &&
        inlineCodeAt(self.subject, pos + 2) == C_PERIOD
    ) {
        self.addMatch(pos, pos + 2, "ellipses")
        return pos + 3
    } else {
        return null
    }
}

private fun matchLeftBracket(
    self: InlineParser,
    pos: Int,
    endpos: Int,
): Int? {
    self.addOpener("[", pos, pos, "str")
    return pos + 1
}

private fun matchRightBracket(
    self: InlineParser,
    pos: Int,
    endpos: Int,
): Int? {
    val openers = self.openers["["]
    val subject = self.subject
    if (openers != null && openers.size > 0) {
        val opener = openers[openers.size - 1]
        val isNoteRef = inlineCodeAt(subject, opener.startpos + 1) == C_HAT
        if (isNoteRef) {
            // remove matches back to start
            var i = self.matches.size - 1
            while (i > 0 && self.matches[i].startpos > opener.startpos) {
                self.popMatch()
                i--
            }
            self.clearOpeners(opener.startpos, pos)
            self.matches[i] = self.matches[i].copy(annot = "footnote_reference", endpos = pos)
            return pos + 1
        } else if (opener.annot == "reference_link") {
            // found a reference link
            // convert all matches inside reference to str
            self.strMatches(inlineOr(opener.subendpos, opener.endpos) + 1, pos - 1)
            // add the matches
            val isImage =
                inlineCodeAt(subject, opener.startpos - 1) == C_BANG &&
                    inlineCodeAt(subject, opener.startpos - 2) != C_BACKSLASH
            if (isImage) {
                self.addImageMarker(opener)
                self.addMatch(opener.startpos, opener.endpos, "+imagetext", opener.matchIndex)
                self.addMatch(
                    inlineOr(opener.substartpos, opener.startpos),
                    inlineOr(opener.substartpos, opener.startpos),
                    "-imagetext",
                    opener.subMatchIndex,
                )
            } else {
                self.addMatch(opener.startpos, opener.endpos, "+linktext", opener.matchIndex)
                self.addMatch(
                    inlineOr(opener.substartpos, opener.startpos),
                    inlineOr(opener.substartpos, opener.startpos),
                    "-linktext",
                    opener.subMatchIndex,
                )
            }
            self.addMatch(
                inlineOr(opener.subendpos, opener.endpos),
                inlineOr(opener.subendpos, opener.endpos),
                "+reference",
                opener.subMatchIndex + 1,
            )
            self.addMatch(pos, pos, "-reference")
            // remove from openers
            self.clearOpeners(opener.startpos, pos)
            return pos + 1
        } else if (pos + 1 <= endpos && inlineCodeAt(subject, pos + 1) == C_LEFT_BRACKET) {
            opener.annot = "reference_link"
            self.addMatch(pos, pos, "str")
            opener.subMatchIndex = self.matches.size - 1
            self.addMatch(pos + 1, pos + 1, "str")
            opener.substartpos = pos // intermediate ]
            opener.subendpos = pos + 1 // intermediate [
            // remove any openers between [ and ]
            self.clearOpeners(opener.startpos + 1, pos - 1)
            return pos + 2
        } else if (pos + 1 <= endpos && inlineCodeAt(subject, pos + 1) == C_LEFT_PAREN) {
            self.openers["("] = ArrayList() // clear ( openers
            opener.annot = "explicit_link"
            self.addMatch(pos, pos, "str") // ]
            opener.subMatchIndex = self.matches.size - 1
            self.addMatch(pos + 1, pos + 1, "str") // (
            opener.substartpos = pos // intermediate ]
            opener.subendpos = pos + 1 // intermediate (
            self.destination = true
            // remove any openers between [ and ]
            self.clearOpeners(opener.startpos + 1, pos - 1)
            return pos + 2
        } else if (pos + 1 <= endpos && inlineCodeAt(subject, pos + 1) == C_LEFT_BRACE) {
            // assume this is attributes, bracketed span
            self.addMatch(opener.startpos, opener.endpos, "+span", opener.matchIndex)
            self.addMatch(pos, pos, "-span")
            if (self.allowAttributes) {
                // speculative: revert to literal brackets if the attribute
                // parse that must follow fails
                self.pendingSpan =
                    InlineParser.PendingSpan(
                        openMatchIndex = opener.matchIndex,
                        closeMatchIndex = self.matches.size - 1,
                    )
            }
            // remove any openers between [ and ]
            self.clearOpeners(opener.startpos, pos)
            return pos + 1
        }
    }
    return null
}

private fun matchLeftParen(
    self: InlineParser,
    pos: Int,
    endpos: Int,
): Int? {
    if (!self.destination) {
        return null
    }
    self.addOpener("(", pos, pos, "str")
    return pos + 1
}

private fun matchRightParen(
    self: InlineParser,
    pos: Int,
    endpos: Int,
): Int? {
    if (!self.destination) {
        return null
    }
    val parens = self.openers["("]
    if (parens != null && parens.size > 0) {
        parens.removeAt(parens.size - 1) // clear opener
        self.addMatch(pos, pos, "str")
        return pos + 1
    } else {
        val subject = self.subject
        val openers = self.openers["["]
        // djot.js reads openers[openers.length - 1] before testing openers, so it would
        // throw where openers is undefined; destination is only set once a "[" opener
        // exists, so that case does not arise.
        val opener = openers?.lastOrNull()
        if (openers != null && openers.size > 0 && opener != null &&
            opener.annot == "explicit_link"
        ) {
            // we have inline link
            // convert all matches inside destination to str
            self.strMatches(inlineOr(opener.subendpos, opener.endpos) + 1, pos - 1)
            val isImage =
                inlineCodeAt(subject, opener.startpos - 1) == C_BANG &&
                    inlineCodeAt(subject, opener.startpos - 2) != C_BACKSLASH
            if (isImage) {
                self.addImageMarker(opener)
                self.addMatch(opener.startpos, opener.endpos, "+imagetext", opener.matchIndex)
                self.addMatch(
                    inlineOr(opener.substartpos, opener.startpos),
                    inlineOr(opener.substartpos, opener.startpos),
                    "-imagetext",
                    opener.subMatchIndex,
                )
            } else {
                self.addMatch(opener.startpos, opener.endpos, "+linktext", opener.matchIndex)
                self.addMatch(
                    inlineOr(opener.substartpos, opener.startpos),
                    inlineOr(opener.substartpos, opener.startpos),
                    "-linktext",
                    opener.subMatchIndex,
                )
            }
            self.addMatch(
                inlineOr(opener.subendpos, opener.endpos),
                inlineOr(opener.subendpos, opener.endpos),
                "+destination",
                opener.subMatchIndex + 1,
            )
            self.addMatch(pos, pos, "-destination")
            self.destination = false
            // remove from openers
            self.clearOpeners(opener.startpos, pos)
            return pos + 1
        }
    }
    return null
}

private fun matchHyphen(
    self: InlineParser,
    startPos: Int,
    endpos: Int,
): Int? {
    var pos = startPos
    val subject = self.subject
    if (inlineCodeAt(subject, pos - 1) == C_LEFT_BRACE ||
        inlineCodeAt(subject, pos + 1) == C_RIGHT_BRACE
    ) {
        val newpos = deleteMatcher(self, pos, endpos)
        if (newpos != 0) { // JS truthiness of a number
            return newpos
        }
    }
    // didn't match a del, try for smart hyphens:
    var ep = pos
    var hyphens = 0
    while (ep <= endpos && inlineCodeAt(subject, ep) == C_HYPHEN) {
        ep++
        hyphens++
    }
    if (inlineCodeAt(subject, ep) == C_RIGHT_BRACE) {
        hyphens-- // last hyphen is close del
    }
    if (hyphens == 0) { // this means we have '-}'
        self.addMatch(pos, pos + 1, "str")
        return pos + 2
    }
    // Try to construct a homogeneous sequence of dashes
    val allEm = hyphens % 3 == 0
    val allEn = hyphens % 2 == 0
    while (hyphens > 0) {
        if (allEm) {
            self.addMatch(pos, pos + 2, "em_dash")
            pos = pos + 3
            hyphens = hyphens - 3
        } else if (allEn) {
            self.addMatch(pos, pos + 1, "en_dash")
            pos = pos + 2
            hyphens = hyphens - 2
        } else if (hyphens >= 3 && (hyphens % 2 != 0 || hyphens > 4)) {
            self.addMatch(pos, pos + 2, "em_dash")
            pos = pos + 3
            hyphens = hyphens - 3
        } else if (hyphens >= 2) {
            self.addMatch(pos, pos + 1, "en_dash")
            pos = pos + 2
            hyphens = hyphens - 2
        } else {
            self.addMatch(pos, pos, "str")
            pos = pos + 1
            hyphens = hyphens - 1
        }
    }
    return pos
}

// handlers for specific code points (djot.js's `matchers` object), indexed by code unit:
// every key is ASCII.
private val defaultMatchers: Array<((InlineParser, Int, Int) -> Int?)?> =
    arrayOfNulls<(InlineParser, Int, Int) -> Int?>(128).also { m ->
        m[C_BACKTICK] = ::matchBacktick
        m[C_BACKSLASH] = ::matchBackslash
        m[C_LESSTHAN] = ::matchLessThan
        m[C_TILDE] = betweenMatched("~", "subscript", "str", ::alwaysTrue)
        m[C_HAT] = betweenMatched("^", "superscript", "str", ::alwaysTrue)
        m[C_UNDERSCORE] = betweenMatched("_", "emph", "str", ::alwaysTrue)
        m[C_ASTERISK] = betweenMatched("*", "strong", "str", ::alwaysTrue)
        m[C_PLUS] = betweenMatched("+", "insert", "str", ::hasBrace)
        m[C_EQUALS] = betweenMatched("=", "mark", "str", ::hasBrace)
        m[C_SINGLE_QUOTE] =
            betweenMatched("'", "single_quoted", "right_single_quote", ::singleQuoteOpenTest)
        m[C_DOUBLE_QUOTE] =
            betweenMatched("\"", "double_quoted", "left_double_quote", ::alwaysTrue)
        m[C_LEFT_BRACE] = ::matchLeftBrace
        m[C_COLON] = ::matchColon
        m[C_PERIOD] = ::matchPeriod
        m[C_LEFT_BRACKET] = ::matchLeftBracket
        m[C_RIGHT_BRACKET] = ::matchRightBracket
        m[C_LEFT_PAREN] = ::matchLeftParen
        m[C_RIGHT_PAREN] = ::matchRightParen
        m[C_HYPHEN] = ::matchHyphen
    }

internal class InlineParser(
    val subject: String,
    val options: ParseOptions = ParseOptions(),
) {
    class Opener(
        var matchIndex: Int,
        var startpos: Int,
        var endpos: Int,
        var annot: String?,
        var subMatchIndex: Int,
        var substartpos: Int?,
        var subendpos: Int?,
    )

    class PendingSpan(
        val openMatchIndex: Int,
        val closeMatchIndex: Int,
    )

    val warn: WarningHandler = options.warn ?: WarningHandler { }
    val matches: MutableList<Event> = ArrayList() // array of matches

    // map from opener type to Opener[] in reverse order
    val openers: MutableMap<String, MutableList<Opener>> = LinkedHashMap()
    var verbatim: Int = 0 // parsing a verbatim span to be ended by N backticks
    var verbatimType: String = "" // math or regular
    var destination: Boolean = false // parsing link destination?
    var firstpos: Int = -1 // position of first slice
    var lastpos: Int = 0 // position of last slice
    var allowAttributes: Boolean = true // allow parsing of attributes
    var attributeParser: AttributeParser? = null // attribute parser
    var attributeStart: Int? = null // start pos of potential attribute

    // slices we've tried to parse as attributes
    var attributeSlices: MutableList<AttributeSlice>? = null

    // span awaiting attribute confirmation
    var pendingSpan: PendingSpan? = null

    // functions to handle different code points
    val matchers: Array<((InlineParser, Int, Int) -> Int?)?> = defaultMatchers

    fun addMatch(
        startpos: Int,
        endpos: Int,
        annot: String,
        matchIndex: Int? = null,
    ) {
        val match = Event(startpos, endpos, annot)
        if (matchIndex != null) {
            // insert into the proper place, replacing placeholder
            // (JS `splice(matchIndex, 1, match)`: a negative index counts from the end, and an
            // index at or past the end appends)
            val size = matches.size
            val start = if (matchIndex < 0) maxOf(size + matchIndex, 0) else minOf(matchIndex, size)
            if (start < size) {
                matches[start] = match
            } else {
                matches.add(match)
            }
        } else {
            matches.add(match)
        }
    }

    // JS `matches.pop()`: does nothing on an empty array.
    fun popMatch() {
        if (matches.isNotEmpty()) {
            matches.removeAt(matches.size - 1)
        }
    }

    fun addImageMarker(opener: Opener) {
        val prevIdx = opener.matchIndex - 1
        val prevMatch = matches.getOrNull(prevIdx)
        if (prevMatch != null && prevMatch.annot == "str" &&
            prevMatch.startpos < opener.startpos - 1
        ) {
            // '!' is grouped with preceding text in a single str match.
            // Truncate the str to end before '!' and insert image_marker.
            matches[prevIdx] = prevMatch.copy(endpos = opener.startpos - 2)
            matches.add(
                prevIdx + 1,
                Event(opener.startpos - 1, opener.startpos - 1, "image_marker"),
            )
            // Adjust indices since we inserted a new element
            opener.matchIndex += 1
            opener.subMatchIndex += 1
        } else {
            // '!' is alone in its str match, replace it directly
            addMatch(opener.startpos - 1, opener.startpos - 1, "image_marker", prevIdx)
        }
    }

    fun inVerbatim(): Boolean = (verbatim > 0)

    fun singleChar(pos: Int): Int {
        addMatch(pos, pos, "str")
        return pos + 1
    }

    fun reparseAttributes() {
        // Reparse attributeSlices that we tried to parse as an attribute
        val slices = attributeSlices ?: return
        val pendingSpan = this.pendingSpan
        if (pendingSpan != null) {
            // a span is only a span when followed by a valid attribute;
            // make the brackets literal again
            matches[pendingSpan.openMatchIndex] =
                matches[pendingSpan.openMatchIndex].copy(annot = "str")
            matches[pendingSpan.closeMatchIndex] =
                matches[pendingSpan.closeMatchIndex].copy(annot = "str")
            this.pendingSpan = null
        }
        allowAttributes = false
        attributeParser = null
        attributeStart = null
        for (slice in slices) {
            feed(slice.startpos, slice.endpos)
        }
        allowAttributes = true
        attributeSlices = null
    }

    // Named on the JVM apart from the `matches` property's getter, which has the same signature.
    @JvmName("getMatchList")
    fun getMatches(): MutableList<Event> {
        val subject = this.subject
        if (attributeParser != null) {
            // we're still in an attribute parse
            reparseAttributes()
        }
        val i = matches.size - 1
        // remove trailing softbreak and any spaces
        val lastMatch = matches.getOrNull(i)
        if (lastMatch != null && lastMatch.annot == "soft_break") {
            popMatch()
            val idx = matches.size - 1
            val match = matches.getOrNull(idx)
            if (match != null && match.annot == "str" &&
                inlineCodeAt(subject, match.endpos) == 32
            ) {
                var endpos = match.endpos
                while (endpos >= match.startpos &&
                    inlineCodeAt(subject, endpos) == 32
                ) {
                    endpos = endpos - 1
                }
                matches[idx] = match.copy(endpos = endpos)
                if (endpos < match.startpos) {
                    popMatch()
                }
            }
        }
        // add -verbatim if needed
        if (matches.size > 0 && verbatim > 0) { // unclosed verbatim
            val last = matches[matches.size - 1]
            warn.warn(Warning("Unclosed verbatim", last.endpos))
            matches.add(Event(last.endpos, last.endpos, "-$verbatimType"))
        }
        return matches
    }

    fun addOpener(
        name: String,
        startpos: Int,
        endpos: Int,
        defaultAnnot: String,
    ) {
        val list = openers.getOrPut(name) { ArrayList() }
        list.add(
            Opener(
                matchIndex = matches.size,
                startpos = startpos,
                endpos = endpos,
                annot = null,
                subMatchIndex = matches.size,
                substartpos = null,
                subendpos = null,
            ),
        )
        // add default match (to be used if we don't match the opener)
        addMatch(startpos, endpos, defaultAnnot)
    }

    fun clearOpeners(
        startpos: Int,
        endpos: Int,
    ) {
        // Remove other openers in between the matches
        for (v in openers.values) {
            var i = v.size - 1
            while (i >= 0 && i < v.size) {
                val opener = v[i]
                val sub = opener.substartpos
                val subEnd = opener.subendpos
                if (opener.startpos >= startpos && opener.endpos <= endpos) {
                    v.removeAt(i)
                } else if ((inlineTruthy(sub) && sub!! >= startpos) &&
                    (inlineTruthy(subEnd) && subEnd!! <= endpos)
                ) {
                    opener.substartpos = null
                    opener.subendpos = null
                    opener.annot = null
                } else {
                    break
                }
                i--
            }
        }
    }

    fun strMatches(
        startpos: Int,
        endpos: Int,
    ) {
        // convert matches between startpos and endpos to str
        var i = matches.size - 1
        // backup to startpos
        while (i > 0 && matches[i].startpos >= startpos) {
            i--
        }
        if (matches[i].startpos < startpos) {
            i++
        }
        while (i >= 0 && i < matches.size && matches[i].endpos <= endpos) {
            val m = matches[i]
            // An open marker is already included in the following delimiter match.
            if (m.annot != "escape" && m.annot != "str" &&
                m.annot != "open_marker"
            ) {
                matches[i] = m.copy(annot = "str")
            }
            i++
        }
    }

    fun feed(
        startpos: Int,
        endpos: Int,
    ) {
        // Feed a slice to the parser, updating state.
        val subject = this.subject
        if (firstpos == -1 || startpos < firstpos) {
            firstpos = startpos
        }
        if (lastpos == 0 || endpos > lastpos) {
            lastpos = endpos
        }
        var pos = startpos
        while (pos <= endpos) {
            val attrParser = attributeParser
            if (attrParser != null) {
                val sp = pos
                val nextSpecial = findSpecial(subject, pos, endpos)
                val ep2 = nextSpecial ?: endpos
                val result = attrParser.feed(sp, ep2)
                val ep = result.position
                if (result.status == "done") {
                    val attributeStart = this.attributeStart
                    // add attribute matches
                    if (attributeStart != null) {
                        addMatch(attributeStart, attributeStart, "+attributes")
                    }
                    val attrMatches = attrParser.matches
                    // add attribute matches
                    for (match in attrMatches) {
                        addMatch(match.startpos, match.endpos, match.annot)
                    }
                    addMatch(ep, ep, "-attributes")
                    // restore state to prior to adding attribute parser
                    attributeParser = null
                    this.attributeStart = null
                    attributeSlices = null
                    pendingSpan = null
                    pos = ep + 1
                } else if (result.status == "fail") {
                    reparseAttributes()
                    pos = sp
                    // as no slice was added for it
                } else if (result.status == "continue") {
                    val slices = attributeSlices ?: ArrayList<AttributeSlice>().also { attributeSlices = it }
                    slices.add(AttributeSlice(sp, ep))
                    pos = ep + 1
                }
            } else {
                // find next interesting character:
                val nextSpecial = findSpecial(subject, pos, endpos)
                val newpos = nextSpecial ?: (endpos + 1)
                if (newpos > pos) {
                    addMatch(pos, newpos - 1, "str")
                    pos = newpos
                    if (pos > endpos) {
                        break // otherwise, fall through:
                    }
                }
                // if we get here, then newpos = pos,
                // i.e. we have something interesting at pos
                val c = inlineCodeAt(subject, pos)
                if (c == -1) {
                    throw IllegalStateException("Code point at $pos is undefined.")
                }

                if (c == C_CR || c == C_LF) { // cr or lf
                    if (c == C_CR && inlineCodeAt(subject, pos + 1) == C_LF &&
                        pos + 1 <= endpos
                    ) {
                        addMatch(pos, pos + 1, "soft_break")
                        pos = pos + 2
                    } else {
                        addMatch(pos, pos, "soft_break")
                        pos = pos + 1
                    }
                } else if (verbatim > 0) {
                    if (c == C_BACKTICK) {
                        val m = findBackticks1(subject, pos, endpos)
                        if (m != null) {
                            val endchar = m
                            if (m - pos + 1 == verbatim) {
                                // check for raw attribute
                                val m2 = findRawAttribute(subject, endchar + 1, endpos)
                                if (m2 != null && verbatimType == "verbatim") { // raw
                                    addMatch(pos, endchar, "-$verbatimType")
                                    addMatch(endchar + 1, m2, "raw_format")
                                    pos = m2 + 1
                                } else {
                                    addMatch(pos, endchar, "-$verbatimType")
                                    pos = endchar + 1
                                }
                                verbatim = 0
                                verbatimType = "verbatim"
                            } else {
                                addMatch(pos, endchar, "str")
                                pos = endchar + 1
                            }
                        } else {
                            addMatch(pos, endpos, "str")
                            pos = endpos + 1
                        }
                    } else {
                        addMatch(pos, pos, "str")
                        pos = pos + 1
                    }
                } else {
                    val matcher = if (c < matchers.size) matchers[c] else null
                    if (matcher != null) {
                        val res = matcher(this, pos, endpos)
                        if (res == null) {
                            pos = singleChar(pos)
                        } else {
                            pos = res
                        }
                    } else {
                        pos = singleChar(pos)
                    }
                }
            }
        }
        return
    }
}
