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

// Ported from djot.js src/parse.ts (MIT, Copyright (C) 2022 John MacFarlane).

/**
 * Parses djot [input] into a [Doc]. With [ParseOptions.sourcePositions] every node carries its
 * [Pos]; [ParseOptions.warn] receives what the parser recovered from.
 */
public fun parse(
    input: String,
    options: ParseOptions = ParseOptions(),
): Doc {
    val parser = parseEvents(input, options)
    return parseFromEventIterator(parser.iterator(), input, options)
}

/**
 * Builds a [Doc] from the block parser's [events] over [input], as djot.js's `parseFromEvents`.
 * The events are walked once, front to back.
 */
public fun parseFromEvents(
    events: Iterable<Event>,
    input: String,
    options: ParseOptions = ParseOptions(),
): Doc = parseFromEventIterator(events.iterator(), input, options)

internal fun parseFromEventIterator(
    events: Iterator<Event>,
    input: String,
    options: ParseOptions,
): Doc = ParseAstBuilder(input, options).build(events)

/**
 * The text of [node] and its descendants, concatenated: soft and hard breaks become `"\n"` and
 * footnote references are left out (as for heading identifiers).
 */
public fun getStringContent(node: AstNode): String {
    val buffer = StringBuilder()
    addStringContent(listOf(node), buffer)
    return buffer.toString()
}

private fun getStringContentOfChildren(children: List<AstNode>): String {
    val buffer = StringBuilder()
    addStringContent(children, buffer)
    return buffer.toString()
}

// Uses an explicit stack rather than recursion, so that deeply nested
// content cannot overflow the call stack. Siblings are stacked in
// reverse, so that they come back off in document order.
private fun addStringContent(
    nodes: List<AstNode>,
    buffer: StringBuilder,
) {
    val stack = ArrayList<AstNode>()
    for (i in nodes.indices.reversed()) stack.add(nodes[i])
    while (stack.isNotEmpty()) {
        val current = stack.removeAt(stack.size - 1)
        if (current is FootnoteReference) {
            // excluded from heading IDs
        } else if (current is HasText) {
            buffer.append(current.text)
        } else if (current is SoftBreak || current is HardBreak) {
            buffer.append('\n')
        } else {
            val children = current.childNodes
            for (i in children.indices.reversed()) stack.add(children[i])
        }
    }
}

// in verbatim text, trim one space next to ` at beginning or end:
private fun trimVerbatim(s: String): String {
    var r = s
    if (r.startsWith(" `")) r = r.substring(1)
    if (r.endsWith("` ")) r = r.substring(0, r.length - 1)
    return r
}

private fun romanDigit(c: Char): Int =
    when (c) {
        'i', 'I' -> 1
        'v', 'V' -> 5
        'x', 'X' -> 10
        'l', 'L' -> 50
        'c', 'C' -> 100
        'd', 'D' -> 500
        'm', 'M' -> 1000
        else -> 0
    }

private fun romanToNumber(s: String): Int {
    // go backwards through the digits
    var total = 0
    var prevdigit = 0
    var i = s.length - 1
    while (i >= 0) {
        val n = romanDigit(s[i])
        if (n == 0) {
            throw IllegalArgumentException("Encountered bad character in roman numeral $s")
        }
        if (n < prevdigit) { // e.g. ix
            total -= n
        } else {
            total += n
        }
        prevdigit = n
        i -= 1
    }
    return total
}

private fun stripListPunctuation(s: String): String = s.filterNot { it == '(' || it == ')' || it == '.' }

// JS parseInt(s, 10) on a run of ASCII digits; null where JS gives NaN. djot.js keeps a JS
// number (a double); a Long matches it up to 2^53 and saturates past Long's range, where JS would
// lose precision or print an exponent instead.
private fun jsParseInt10(s: String): Long? {
    var i = 0
    while (i < s.length && isJsWhitespace(s[i])) i++
    var negative = false
    if (i < s.length && (s[i] == '+' || s[i] == '-')) {
        negative = s[i] == '-'
        i++
    }
    val start = i
    var value = 0.0
    while (i < s.length && s[i] in '0'..'9') {
        value = value * 10 + (s[i] - '0')
        i++
    }
    if (i == start) return null
    return (if (negative) -value else value).toLong()
}

// JS String.prototype.codePointAt(index), null where JS gives undefined.
private fun jsCodePointAt(
    s: String,
    index: Int,
): Int? {
    if (index < 0 || index >= s.length) return null
    val c = s[index]
    if (c.isHighSurrogate() && index + 1 < s.length && s[index + 1].isLowSurrogate()) {
        return ((c.code - 0xD800) shl 10) + (s[index + 1].code - 0xDC00) + 0x10000
    }
    return c.code
}

private fun getListStart(
    marker: String,
    style: String,
): Long? {
    val numtype = stripListPunctuation(style)
    val s = stripListPunctuation(marker)
    return when (numtype) {
        "1" -> {
            jsParseInt10(s)
        }

        "A" -> {
            val cp = jsCodePointAt(s, 0)
            ((if (cp == null || cp == 0) 65 else cp) - 65 + 1).toLong() // 65 = "A"
        }

        "a" -> {
            val cp = jsCodePointAt(s, 0)
            ((if (cp == null || cp == 0) 97 else cp) - 97 + 1).toLong() // 97 = "a"
        }

        "I" -> {
            romanToNumber(s).toLong()
        }

        "i" -> {
            romanToNumber(s).toLong()
        }

        else -> {
            null
        }
    }
}

// Parsing context:
private enum class ParseContext {
    // add str nodes as children of tip
    Normal,

    // gather str, escape, soft_break, hard_break in accumulatedText
    Verbatim,

    // gather str, soft_break, hard_break in accumulatedText
    Literal,
}

// JS `\s` and the set String.prototype.trim strips.
private fun isJsWhitespace(c: Char): Boolean =
    when (c) {
        '\t', '\n', '\u000B', '\u000C', '\r', ' ', '\u00A0', '\u1680',
        '\u2028', '\u2029', '\u202F', '\u205F', '\u3000', '\uFEFF',
        -> true

        else -> c in '\u2000'..'\u200A'
    }

private fun jsTrim(s: String): String = s.trim { isJsWhitespace(it) }

private fun isLabelSpace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\r' || c == '\n'

// Replaces each maximal run of characters matching [matches] with [replacement] (a JS
// `.replace(/[...]+/g, replacement)`).
private inline fun replaceRuns(
    s: String,
    replacement: String,
    matches: (Char) -> Boolean,
): String {
    val sb = StringBuilder(s.length)
    var inRun = false
    for (c in s) {
        if (matches(c)) {
            if (!inRun) {
                sb.append(replacement)
                inRun = true
            }
        } else {
            sb.append(c)
            inRun = false
        }
    }
    return sb.toString()
}

private fun normalizeLabel(label: String): String = replaceRuns(jsTrim(label), " ") { isLabelSpace(it) }

// The characters /[\]\[~!@#$%^&*(){}`,.<>\\|=+/?\s]/ that identifiers replace with a space.
private fun isIdentifierSeparator(c: Char): Boolean =
    when (c) {
        ']', '[', '~', '!', '@', '#', '$', '%', '^', '&', '*', '(', ')', '{', '}', '`',
        ',', '.', '<', '>', '\\', '|', '=', '+', '/', '?',
        -> true

        else -> isJsWhitespace(c)
    }

// Removes every CR and LF (`.replace(/[\r\n]/g, "")`).
private fun stripNewlines(s: String): String = s.filterNot { it == '\r' || it == '\n' }

// The backslash escapes an attribute value resolves.
private fun isAttrEscapable(c: Char): Boolean = c in ".,\\/#!$%^&*;:{}=-_`~+[]()'\"?|"

private fun unescapeAttrValue(s: String): String {
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length && isAttrEscapable(s[i + 1])) {
            sb.append(s[i + 1])
            i += 2
        } else {
            sb.append(c)
            i++
        }
    }
    return sb.toString()
}

// JS String.prototype.substring: clamps both ends to the string and swaps them if reversed.
private fun jsSubstring(
    s: String,
    start: Int,
    end: Int,
): String {
    var a = start.coerceIn(0, s.length)
    var b = end.coerceIn(0, s.length)
    if (a > b) {
        val t = a
        a = b
        b = t
    }
    return s.substring(a, b)
}

// A JS "array index" key: such keys enumerate first, in ascending numeric order, in every JS object.
private fun parseIsArrayIndex(k: String): Boolean {
    if (k.isEmpty() || k.length > 10) return false
    if (k.length > 1 && k[0] == '0') return false
    for (c in k) if (c !in '0'..'9') return false
    return k.toLong() <= 4294967294L
}

// The entries of a map that stands in for a JS object (kept in insertion order), in the order
// JS `for...in` visits them: integer-like keys first in ascending order, then the other keys in
// insertion order.
private fun <V> parseJsOrderedEntries(map: Map<String, V>): List<Map.Entry<String, V>> {
    var hasIndex = false
    for (k in map.keys) {
        if (parseIsArrayIndex(k)) {
            hasIndex = true
            break
        }
    }
    if (!hasIndex) return map.entries.toList()
    val indexed = map.entries.filter { parseIsArrayIndex(it.key) }.sortedBy { it.key.toLong() }
    val named = map.entries.filter { !parseIsArrayIndex(it.key) }
    return indexed + named
}

private fun <V> recordPut(
    map: LinkedHashMap<String, V>,
    key: String,
    value: V,
) {
    map[key] = value
}

// Attribute maps are plain JS objects (`{}`): assigning a string to "__proto__" there is a no-op.
private fun attrPut(
    map: Attributes,
    key: String,
    value: String,
) {
    if (key == "__proto__") return
    recordPut(map, key, value)
}

@Suppress("UNCHECKED_CAST")
private fun <T : AstNode> MutableList<AstNode>.typed(): MutableList<T> = this as MutableList<T>

private fun <T : AstNode> T.withProps(
    attributes: Attributes?,
    autoAttributes: Attributes?,
    pos: Pos?,
): T {
    this.attributes = attributes
    this.autoAttributes = autoAttributes
    this.pos = pos
    return this
}

/* Not part of the AST but for processing: a node while it is open. The fields after
 * `autoAttributes` are djot.js's `data` record. */
private class ParseContainer(
    var pos: Pos?,
) {
    val children: MutableList<AstNode> = mutableListOf()
    var attributes: Attributes? = null
    var autoAttributes: Attributes? = null

    var key: String? = null
    var value: String? = null
    var isimage: Boolean = false
    var level: Int = 0
    var headinglevel: Int? = null
    var styles: List<String> = emptyList()
    var blanklines: Boolean = false

    // non-null is djot.js's `"tight" in data`: set on list containers only
    var tight: Boolean? = null
    var firstMarker: String? = null
    var definitionList: Boolean = false
    var checkbox: String? = null
    var aligns: MutableList<String>? = null
    var label: String? = null
    var format: String? = null
    var lang: String? = null
}

private class ParseAstBuilder(
    private val input: String,
    private val options: ParseOptions,
) {
    private val linestarts = ArrayList<Int>().apply { add(-1) }

    private var context = ParseContext.Normal
    private val accumulatedText = StringBuilder()
    private val references = LinkedHashMap<String, Reference>()
    private val autoReferences = LinkedHashMap<String, Reference>()
    private val footnotes = LinkedHashMap<String, Footnote>()
    private val identifiers = HashSet<String>() // identifiers used
    private val blockAttributes = Attributes() // accumulated block attributes
    private var listDepth = 0

    private val containers =
        ArrayList<ParseContainer>().apply {
            add(
                ParseContainer(Pos(SourceLoc(0, 0, 0), SourceLoc(0, 0, 0))).apply { headinglevel = 0 },
            )
        }

    init {
        if (options.sourcePositions) { // construct map of newline positions
            for (i in input.indices) {
                if (input[i] == '\n') {
                    linestarts.add(i)
                }
            }
        }
    }

    // use binary search on linestarts to find line number and col
    private fun getSourceLoc(pos: Int): SourceLoc {
        val numlines = linestarts.size
        var bottom = 0
        var top = numlines - 1
        var line = 0
        var col = 0
        while (line == 0) {
            val mid = bottom + (top - bottom) / 2
            if (linestarts[mid] > pos) {
                top = mid
            } else {
                if (mid == top || linestarts[mid + 1] > pos) {
                    line = mid + 1
                    col = pos - linestarts[mid]
                } else {
                    if (bottom == mid && bottom < top) {
                        bottom = mid + 1
                    } else {
                        bottom = mid
                    }
                }
            }
        }
        return SourceLoc(line, col, pos)
    }

    private fun warnAt(
        message: String,
        at: Int,
    ) {
        val warning =
            if (options.sourcePositions) {
                Warning(message, getSourceLoc(at))
            } else {
                Warning(message, at)
            }
        options.warn?.warn(warning)
    }

    // Moves the accumulated block attributes onto [existing] (creating it when needed).
    private fun addBlockAttributes(existing: Attributes?): Attributes? {
        if (blockAttributes.isEmpty()) return existing
        val attrs = existing ?: Attributes()
        for ((k, v) in parseJsOrderedEntries(blockAttributes)) {
            attrPut(attrs, k, v)
        }
        blockAttributes.clear()
        return attrs
    }

    private fun getUniqueIdentifier(s: String): String {
        val base =
            replaceRuns(
                jsTrim(replaceRuns(s, " ") { isIdentifierSeparator(it) }),
                "-",
            ) { it == ' ' }
        var i = 0
        var ident = base
        // generate unique id
        while (ident.isEmpty() || identifiers.contains(ident)) {
            i += 1
            ident = (base.ifEmpty { "s" }) + "-" + i
        }
        return ident
    }

    private fun pushContainer(pos: Pos?) {
        val container = ParseContainer(pos)
        container.attributes = addBlockAttributes(container.attributes)
        containers.add(container)
    }

    private fun popContainer(pos: Pos?): ParseContainer {
        if (containers.isEmpty()) {
            throw IllegalStateException("Container stack is empty (popContainer)")
        }
        val node = containers.removeAt(containers.size - 1)
        val nodePos = node.pos
        if (pos != null && nodePos != null) {
            node.pos = Pos(nodePos.start, pos.end)
        }
        return node
    }

    private fun topContainer(): ParseContainer {
        if (containers.isNotEmpty()) {
            return containers[containers.size - 1]
        } else {
            throw IllegalStateException("Container stack is empty (topContainer)")
        }
    }

    // points to last child of top container, or null (the top container itself)
    // if it doesn't have children
    private fun getTip(): AstNode? {
        val top = topContainer()
        return top.children.lastOrNull()
    }

    private fun addChildToTip(child: AstNode) {
        if (containers.isNotEmpty()) {
            containers[containers.size - 1].children.add(child)
        }
    }

    private fun tightenContainerEnd(node: ParseContainer) {
        val lastChildPos = node.children.lastOrNull()?.pos
        val nodePos = node.pos
        if (nodePos != null && lastChildPos != null) {
            node.pos = Pos(nodePos.start, lastChildPos.end)
        }
    }

    private fun takeAccumulatedText(): String {
        val text = accumulatedText.toString()
        accumulatedText.clear()
        return text
    }

    private fun mergeAttributes(
        into: Attributes,
        from: Attributes,
    ) {
        for ((k, v) in parseJsOrderedEntries(from)) {
            if (k == "class") {
                val existing = into[k]
                if (!existing.isNullOrEmpty()) {
                    attrPut(into, k, "$existing $v")
                } else {
                    attrPut(into, k, v)
                }
            } else {
                attrPut(into, k, v)
            }
        }
    }

    private fun handle(
        annot: String,
        suffixes: List<String>,
        startpos: Int,
        endpos: Int,
        pos: Pos?,
    ) {
        when (annot) {
            "str" -> {
                val txt = jsSubstring(input, startpos, endpos + 1)
                if (context == ParseContext.Normal) {
                    addChildToTip(Str(txt).also { it.pos = pos })
                } else {
                    accumulatedText.append(txt)
                }
            }

            "soft_break" -> {
                if (context == ParseContext.Normal) {
                    addChildToTip(SoftBreak().also { it.pos = pos })
                } else {
                    accumulatedText.append('\n')
                }
            }

            "escape" -> {
                if (context == ParseContext.Verbatim) {
                    accumulatedText.append('\\')
                }
            }

            "hard_break" -> {
                if (context == ParseContext.Normal) {
                    addChildToTip(HardBreak().also { it.pos = pos })
                } else {
                    accumulatedText.append('\n')
                }
            }

            "non_breaking_space" -> {
                if (context == ParseContext.Verbatim) {
                    accumulatedText.append("\\ ")
                } else {
                    addChildToTip(NonBreakingSpace().also { it.pos = pos })
                }
            }

            "symb" -> {
                if (context == ParseContext.Normal) {
                    val alias = jsSubstring(input, startpos + 1, endpos)
                    addChildToTip(Symb(alias).also { it.pos = pos })
                } else {
                    accumulatedText.append(jsSubstring(input, startpos, endpos + 1))
                }
            }

            "footnote_reference" -> {
                val fnref = jsSubstring(input, startpos + 2, endpos)
                addChildToTip(FootnoteReference(normalizeLabel(fnref)).also { it.pos = pos })
            }

            "+reference_definition" -> {
                pushContainer(pos)
            }

            "-reference_definition" -> {
                val node = popContainer(pos)
                val key = node.key
                val lab = normalizeLabel(key ?: "")
                val r =
                    Reference(lab, node.value.orEmpty()).also {
                        it.attributes = node.attributes
                        it.pos = node.pos
                    }
                if (!key.isNullOrEmpty()) {
                    recordPut(references, lab, r)
                }
            }

            "reference_key" -> {
                topContainer().key = jsSubstring(input, startpos + 1, endpos)
                topContainer().value = ""
            }

            "reference_value" -> {
                val top = topContainer()
                top.value = (top.value ?: "undefined") + jsSubstring(input, startpos, endpos + 1)
            }

            "+emph", "+strong", "+span", "+mark", "+superscript", "+subscript",
            "+delete", "+insert", "+double_quoted", "+single_quoted",
            -> {
                pushContainer(pos)
            }

            "-emph" -> {
                val node = popContainer(pos)
                addChildToTip(Emph(node.children.typed()).also { it.pos = node.pos })
            }

            "-strong" -> {
                val node = popContainer(pos)
                addChildToTip(Strong(node.children.typed()).also { it.pos = node.pos })
            }

            "-span" -> {
                val node = popContainer(pos)
                addChildToTip(Span(node.children.typed()).also { it.pos = node.pos })
            }

            "-mark" -> {
                val node = popContainer(pos)
                addChildToTip(Mark(node.children.typed()).also { it.pos = node.pos })
            }

            "-superscript" -> {
                val node = popContainer(pos)
                addChildToTip(Superscript(node.children.typed()).also { it.pos = node.pos })
            }

            "-subscript" -> {
                val node = popContainer(pos)
                addChildToTip(Subscript(node.children.typed()).also { it.pos = node.pos })
            }

            "-delete" -> {
                val node = popContainer(pos)
                addChildToTip(Delete(node.children.typed()).also { it.pos = node.pos })
            }

            "-insert" -> {
                val node = popContainer(pos)
                addChildToTip(Insert(node.children.typed()).also { it.pos = node.pos })
            }

            "-double_quoted" -> {
                val node = popContainer(pos)
                addChildToTip(DoubleQuoted(node.children.typed()).also { it.pos = node.pos })
            }

            "-single_quoted" -> {
                val node = popContainer(pos)
                addChildToTip(SingleQuoted(node.children.typed()).also { it.pos = node.pos })
            }

            "+attributes" -> {
                pushContainer(pos)
            }

            "-attributes" -> {
                endAttributes(startpos, pos)
            }

            "+block_attributes" -> {
                pushContainer(pos)
            }

            "-block_attributes" -> {
                val node = popContainer(pos)
                val nodeAttrs = node.attributes
                if (nodeAttrs != null && containers.isNotEmpty()) {
                    val id = nodeAttrs["id"]
                    if (!id.isNullOrEmpty()) {
                        identifiers.add(id)
                    }
                    mergeAttributes(blockAttributes, nodeAttrs)
                }
            }

            "class" -> {
                val top = topContainer()
                val cl = jsSubstring(input, startpos, endpos + 1)
                val attrs = top.attributes ?: Attributes().also { top.attributes = it }
                val existing = attrs["class"]
                if (!existing.isNullOrEmpty()) {
                    attrPut(attrs, "class", "$existing $cl")
                } else {
                    attrPut(attrs, "class", cl)
                }
            }

            "id" -> {
                val top = topContainer()
                val id = jsSubstring(input, startpos, endpos + 1)
                val attrs = top.attributes
                if (attrs == null) {
                    top.attributes = Attributes().also { it["id"] = id }
                } else {
                    attrPut(attrs, "id", id)
                }
            }

            "key" -> {
                val top = topContainer()
                val key = jsSubstring(input, startpos, endpos + 1)
                top.key = key
                val attrs = top.attributes ?: Attributes().also { top.attributes = it }
                attrPut(attrs, key, "")
            }

            "value" -> {
                val top = topContainer()
                // collapse interior whitespace, then resolve backslash escapes
                val v =
                    unescapeAttrValue(
                        replaceRuns(jsSubstring(input, startpos, endpos + 1), " ") {
                            it == ' ' || it == '\r' || it == '\n'
                        },
                    )
                val attrs = top.attributes ?: Attributes().also { top.attributes = it }
                val key = top.key
                if (!key.isNullOrEmpty()) {
                    // append multiple values to key
                    attrPut(attrs, key, (attrs[key] ?: "undefined") + v)
                } else {
                    throw IllegalStateException("Encountered value without key")
                }
            }

            "+linktext" -> {
                pushContainer(pos)
                topContainer().isimage = false
            }

            "-linktext" -> {
                // we don't pop yet, but wait for -destination
            }

            "+imagetext" -> {
                pushContainer(pos)
                topContainer().isimage = true
            }

            "-imagetext" -> {
                // we don't pop yet, but wait for -destination
            }

            "+destination" -> {
                context = ParseContext.Literal
            }

            "-destination" -> {
                val node = popContainer(pos) // the container added by +linktext/+imagetext
                val destination = stripNewlines(accumulatedText.toString())
                val link: AstNode =
                    if (node.isimage) {
                        Image(destination = destination, children = node.children.typed())
                    } else {
                        Link(destination = destination, children = node.children.typed())
                    }
                link.pos = node.pos
                addChildToTip(link)
                context = ParseContext.Normal
                accumulatedText.clear()
            }

            "+reference" -> {
                context = ParseContext.Literal
            }

            "-reference" -> {
                val node = popContainer(pos) // the container added by +linktext
                var ref = accumulatedText.toString()
                if (ref.isEmpty()) {
                    ref = getStringContentOfChildren(node.children)
                }
                val reference = normalizeLabel(ref)
                val link: AstNode =
                    if (node.isimage) {
                        Image(reference = reference, children = node.children.typed())
                    } else {
                        Link(reference = reference, children = node.children.typed())
                    }
                link.pos = node.pos
                addChildToTip(link)
                context = ParseContext.Normal
                accumulatedText.clear()
            }

            "+verbatim" -> {
                context = ParseContext.Verbatim
                pushContainer(pos)
            }

            "-verbatim" -> {
                val node = popContainer(pos)
                addChildToTip(Verbatim(trimVerbatim(takeAccumulatedText())).also { it.pos = node.pos })
                context = ParseContext.Normal
            }

            "raw_format" -> {
                var format = jsSubstring(input, startpos, endpos + 1)
                format =
                    when {
                        format.startsWith("{=") -> format.substring(2)
                        format.startsWith("=") -> format.substring(1)
                        else -> format
                    }
                if (format.endsWith("}")) format = format.substring(0, format.length - 1)
                val top = topContainer()
                if (context == ParseContext.Verbatim) { // in a code block
                    top.format = format
                } else {
                    val index = top.children.size - 1
                    val tip = top.children.getOrNull(index)
                    if (tip is Verbatim) {
                        top.children[index] =
                            RawInline(format, tip.text).withProps(tip.attributes, tip.autoAttributes, tip.pos)
                    } else {
                        throw IllegalStateException("raw_format is not after verbatim or code_block.")
                    }
                }
            }

            "+display_math", "+inline_math" -> {
                context = ParseContext.Verbatim
                pushContainer(pos)
            }

            "-display_math" -> {
                val node = popContainer(pos)
                addChildToTip(DisplayMath(trimVerbatim(takeAccumulatedText())).also { it.pos = node.pos })
                context = ParseContext.Normal
            }

            "-inline_math" -> {
                val node = popContainer(pos)
                addChildToTip(InlineMath(trimVerbatim(takeAccumulatedText())).also { it.pos = node.pos })
                context = ParseContext.Normal
            }

            "+url", "+email" -> {
                context = ParseContext.Literal
                pushContainer(pos)
            }

            "-url" -> {
                val node = popContainer(pos)
                addChildToTip(Url(stripNewlines(takeAccumulatedText())).also { it.pos = node.pos })
                context = ParseContext.Normal
            }

            "-email" -> {
                val node = popContainer(pos)
                addChildToTip(Email(stripNewlines(takeAccumulatedText())).also { it.pos = node.pos })
                context = ParseContext.Normal
            }

            "+para" -> {
                pushContainer(pos)
            }

            "-para" -> {
                val node = popContainer(pos)
                addChildToTip(
                    Para(node.children.typed()).also {
                        it.attributes = node.attributes
                        it.pos = node.pos
                    },
                )
            }

            "+heading" -> {
                pushContainer(pos)
                topContainer().level = 1 + endpos - startpos
            }

            "-heading" -> {
                endHeading(pos)
            }

            "+list" -> {
                pushContainer(pos)
                topContainer().styles = suffixes
                topContainer().blanklines = false
                topContainer().tight = true
                listDepth++
            }

            "-list" -> {
                endList(pos)
                listDepth--
            }

            "+list_item" -> {
                // narrow styles
                if (suffixes.size < topContainer().styles.size) {
                    topContainer().styles = suffixes
                }
                if (topContainer().firstMarker.isNullOrEmpty()) {
                    topContainer().firstMarker = jsSubstring(input, startpos, endpos + 1)
                }
                pushContainer(pos)
                if (suffixes.size == 1 && suffixes[0] == ":") {
                    topContainer().definitionList = true
                }
            }

            "-list_item" -> {
                endListItem(pos)
            }

            "checkbox_checked" -> {
                topContainer().checkbox = "checked"
            }

            "checkbox_unchecked" -> {
                topContainer().checkbox = "unchecked"
            }

            "+block_quote" -> {
                pushContainer(pos)
            }

            "-block_quote" -> {
                val node = popContainer(pos)
                tightenContainerEnd(node)
                addChildToTip(
                    BlockQuote(node.children.typed()).withProps(node.attributes, node.autoAttributes, node.pos),
                )
            }

            "+table" -> {
                pushContainer(pos)
                topContainer().aligns = mutableListOf()
            }

            "-table" -> {
                val node = popContainer(pos)
                tightenContainerEnd(node)
                val rows = node.children
                var caption: AstNode = Caption()
                if (rows.firstOrNull() is Caption) {
                    caption = rows.removeAt(0)
                }
                val children = mutableListOf<TableChild>()
                children.add(caption as TableChild)
                for (row in rows) children.add(row as TableChild)
                addChildToTip(Table(children).withProps(node.attributes, node.autoAttributes, node.pos))
            }

            "+row" -> {
                pushContainer(pos)
                topContainer().aligns = mutableListOf()
            }

            "-row" -> {
                endRow(pos)
            }

            "separator_default" -> {
                topContainer().aligns?.add("default")
            }

            "separator_left" -> {
                topContainer().aligns?.add("left")
            }

            "separator_right" -> {
                topContainer().aligns?.add("right")
            }

            "separator_center" -> {
                topContainer().aligns?.add("center")
            }

            "+cell" -> {
                pushContainer(pos)
            }

            "-cell" -> {
                val node = popContainer(pos)
                addChildToTip(
                    // head gets set in "-row", align is set at "-row"
                    Cell(head = false, align = "default", children = node.children.typed())
                        .withProps(node.attributes, node.autoAttributes, node.pos),
                )
            }

            "+caption" -> {
                pushContainer(pos)
            }

            "-caption" -> {
                val node = popContainer(pos)
                val tip = getTip()
                if (tip !is Table) {
                    return
                }
                val capt =
                    Caption(node.children.typed()).withProps(node.attributes, node.autoAttributes, node.pos)
                if (tip.children.firstOrNull() is Caption) {
                    tip.children[0] = capt
                }
            }

            "+footnote" -> {
                pushContainer(pos)
            }

            "-footnote" -> {
                val node = popContainer(pos)
                tightenContainerEnd(node)
                val label = node.label
                if (!label.isNullOrEmpty()) {
                    val lab = normalizeLabel(label)
                    recordPut(
                        footnotes,
                        lab,
                        Footnote(lab, node.children.typed()).withProps(node.attributes, node.autoAttributes, node.pos),
                    )
                } else {
                    warnAt("Ignoring footnote without a label.", endpos)
                }
            }

            "note_label" -> {
                topContainer().label = jsSubstring(input, startpos, endpos + 1)
            }

            "+code_block" -> {
                pushContainer(pos)
                context = ParseContext.Verbatim
            }

            "-code_block" -> {
                val node = popContainer(pos)
                val format = node.format
                val text = takeAccumulatedText()
                if (!format.isNullOrEmpty()) {
                    addChildToTip(
                        RawBlock(format = format, text = text).withProps(node.attributes, node.autoAttributes, node.pos),
                    )
                } else {
                    addChildToTip(
                        CodeBlock(lang = node.lang, text = text).withProps(node.attributes, node.autoAttributes, node.pos),
                    )
                }
                context = ParseContext.Normal
            }

            "code_language" -> {
                topContainer().lang = jsSubstring(input, startpos, endpos + 1)
            }

            "+div" -> {
                pushContainer(pos)
            }

            "-div" -> {
                val node = popContainer(pos)
                addChildToTip(Div(node.children.typed()).withProps(node.attributes, node.autoAttributes, node.pos))
            }

            "thematic_break" -> {
                val tb = ThematicBreak()
                tb.pos = pos
                tb.attributes = addBlockAttributes(tb.attributes)
                addChildToTip(tb)
            }

            "left_single_quote" -> {
                addChildToTip(SmartPunctuation("left_single_quote", "'").also { it.pos = pos })
            }

            "right_single_quote" -> {
                addChildToTip(SmartPunctuation("right_single_quote", "'").also { it.pos = pos })
            }

            "left_double_quote" -> {
                addChildToTip(SmartPunctuation("left_double_quote", "\"").also { it.pos = pos })
            }

            "right_double_quote" -> {
                addChildToTip(SmartPunctuation("right_double_quote", "\"").also { it.pos = pos })
            }

            "ellipses" -> {
                addChildToTip(SmartPunctuation("ellipses", "...").also { it.pos = pos })
            }

            "en_dash" -> {
                addChildToTip(SmartPunctuation("en_dash", "--").also { it.pos = pos })
            }

            "em_dash" -> {
                addChildToTip(SmartPunctuation("em_dash", "---").also { it.pos = pos })
            }

            // We set the blanklines property of a parent list or
            // sublist to aid with tight/loose list determination.
            "blankline" -> {
                val listnode =
                    if (topContainer().tight != null) {
                        topContainer()
                    } else if (containers.size >= 2 && containers[containers.size - 2].tight != null) {
                        containers[containers.size - 2]
                    } else {
                        null
                    }
                listnode?.blanklines = true
            }
        }
    }

    private fun endAttributes(
        startpos: Int,
        pos: Pos?,
    ) {
        val node = popContainer(pos)
        val nodeAttrs = node.attributes
        if (nodeAttrs != null && containers.isNotEmpty()) {
            val id = nodeAttrs["id"]
            if (!id.isNullOrEmpty()) {
                identifiers.add(id)
            }
            val tip = getTip()
            if (tip == null) {
                // no inline children to add the attribute to...
                warnAt("Ignoring unattached attribute", startpos)
                return
            }
            var endsWithSpace = false
            if (tip is Str) { // bare word
                // split off last consecutive word of string
                // and attach attributes to it
                val text = tip.text
                var index = text.length
                while (index > 0 && !isJsWhitespace(text[index - 1])) index--
                val matched = index < text.length // /[^\s]+$/ found a word
                if (matched && index > 0) {
                    val word = text.substring(index)
                    var wordpos: Pos? = null
                    val tipPos = tip.pos
                    if (tipPos != null) {
                        val origend = tipPos.end
                        tip.pos =
                            Pos(
                                tipPos.start,
                                SourceLoc(origend.line, origend.col - word.length, origend.offset - word.length),
                            )
                        wordpos =
                            Pos(
                                SourceLoc(
                                    origend.line,
                                    origend.col - word.length + 1,
                                    origend.offset - word.length + 1,
                                ),
                                origend,
                            )
                    }
                    tip.text = text.substring(0, index)
                    addChildToTip(Str(word).also { it.pos = wordpos })
                } else if (!matched) {
                    endsWithSpace = true
                }
            }
            val newTip = getTip() ?: return // get new tip, which may be the new element
            if (endsWithSpace) {
                warnAt("Ignoring unattached attribute", startpos)
                return
            }
            val tipAttrs = newTip.attributes ?: Attributes().also { newTip.attributes = it }
            mergeAttributes(tipAttrs, nodeAttrs)
        }
    }

    private fun endHeading(pos: Pos?) {
        val node = popContainer(pos)

        val headingStr = jsTrim(getStringContentOfChildren(node.children))

        if (node.attributes?.get("id").isNullOrEmpty()) {
            val auto = node.autoAttributes ?: Attributes().also { node.autoAttributes = it }
            val id = getUniqueIdentifier(headingStr)
            attrPut(auto, "id", id)
            identifiers.add(id)
        }
        // add implicit heading reference
        val lab = normalizeLabel(headingStr)
        if (!references.containsKey(lab) && !autoReferences.containsKey(lab)) {
            val explicitId = node.attributes?.get("id")
            val id = if (!explicitId.isNullOrEmpty()) explicitId else node.autoAttributes?.get("id")
            recordPut(autoReferences, lab, Reference(lab, "#" + (id ?: "undefined")))
        }

        // add section structure
        var pnode = topContainer()
        if (pnode.headinglevel != null) { // doc or section
            while ((pnode.headinglevel ?: Int.MIN_VALUE) >= node.level) {
                // close sections til we get to the right level
                pnode = popContainer(pos)
                addChildToTip(
                    Section(pnode.children.typed()).withProps(pnode.attributes, pnode.autoAttributes, pnode.pos),
                )
                pnode = topContainer()
            }
            // now we know that pnode.headinglevel is either
            // null or < node.level
            pushContainer(node.pos)
            topContainer().headinglevel = node.level
            // move id attribute from heading to section
            val auto = node.autoAttributes
            if (auto != null && !auto["id"].isNullOrEmpty()) {
                topContainer().autoAttributes = auto
                node.autoAttributes = null
            }
            val attrs = node.attributes
            if (attrs != null && !attrs["id"].isNullOrEmpty()) {
                topContainer().attributes = attrs
                node.attributes = null
            }
        }
        addChildToTip(
            Heading(node.level, node.children.typed()).withProps(node.attributes, node.autoAttributes, node.pos),
        )
    }

    private fun endList(pos: Pos?) {
        val node = popContainer(pos)
        // take first if ambiguous
        val listStyle = node.styles.getOrNull(0)
        if (listStyle.isNullOrEmpty()) {
            throw IllegalStateException("No style defined for list")
        }
        tightenContainerEnd(node)
        val listStart = getListStart(node.firstMarker ?: "", listStyle)
        val tight = node.tight ?: true
        val list: AstNode =
            if (listStyle == ":") {
                DefinitionList(node.children.typed())
            } else if (listStyle.endsWith("X")) {
                TaskList(tight, node.children.typed())
            } else if (listStyle == "+" || listStyle == "*" || listStyle == "-") {
                BulletList(listStyle, tight, node.children.typed())
            } else {
                OrderedList(listStyle, tight, listStart, node.children.typed())
            }
        addChildToTip(list.withProps(node.attributes, node.autoAttributes, node.pos))
    }

    private fun endListItem(pos: Pos?) {
        val node = popContainer(pos)
        // A lazily closed item (e.g. before a blank line that ends the list)
        // must not extend past its own content, so the parent list's
        // tightened range still contains it.
        tightenContainerEnd(node)
        if (node.definitionList) {
            val first = node.children.firstOrNull()
            val term: Term
            if (first is Para) {
                term = Term(first.children)
                node.children.removeAt(0)
            } else {
                term = Term()
            }
            val definition = Definition(node.children.typed())
            addChildToTip(
                DefinitionListItem(mutableListOf(term, definition))
                    .withProps(node.attributes, node.autoAttributes, node.pos),
            )
        } else {
            val checkbox = node.checkbox
            if (!checkbox.isNullOrEmpty()) {
                addChildToTip(
                    TaskListItem(checkbox, node.children.typed())
                        .withProps(node.attributes, node.autoAttributes, node.pos),
                )
            } else {
                addChildToTip(
                    ListItem(node.children.typed()).withProps(node.attributes, node.autoAttributes, node.pos),
                )
            }
        }
    }

    private fun endRow(pos: Pos?) {
        val node = popContainer(pos)
        if (node.children.isEmpty()) { // a separator line
            // set table aligns, so they can be propagated to future rows
            val aligns = node.aligns
            topContainer().aligns = aligns
            val tip = getTip()
            if (tip is Row) { // prev row of table
                tip.head = true
                for (i in tip.children.indices) {
                    tip.children[i].head = true
                    tip.children[i].align = aligns?.getOrNull(i).takeUnless { it.isNullOrEmpty() } ?: "default"
                }
            }
        } else {
            // get aligns from table
            node.aligns = mutableListOf()
            val tableAligns = topContainer().aligns
            for (i in node.children.indices) {
                (node.children[i] as? Cell)?.align =
                    tableAligns?.getOrNull(i).takeUnless { it.isNullOrEmpty() } ?: "default"
            }
            addChildToTip(
                // head gets set later
                Row(head = false, children = node.children.typed())
                    .withProps(node.attributes, node.autoAttributes, node.pos),
            )
        }
    }

    private fun handleEvent(event: Event) {
        var pos: Pos? = null
        if (options.sourcePositions) {
            pos = Pos(getSourceLoc(event.startpos), getSourceLoc(event.endpos))
        }
        var annot = event.annot
        var suffixes: List<String> = emptyList()
        if (annot.contains('|')) {
            val parts = annot.split('|')
            annot = parts[0]
            suffixes = parts.subList(1, parts.size)
        }

        // Attributes must come right before a block, so we
        // reset them on blank lines.
        if (annot == "blankline") {
            blockAttributes.clear()
        }

        // The following is for tight/loose determination.
        // If blanklines have already been seen, and we're
        // about to process something other than a blankline,
        // the end of a list or list item, or the start of
        // a list, then it's a loose list.
        if (listDepth > 0 && annot != "blankline") {
            val top = topContainer()
            val ln =
                if (top.tight != null) {
                    top
                } else if (containers.size >= 2 && containers[containers.size - 2].tight != null) {
                    containers[containers.size - 2]
                } else {
                    null
                }
            if (ln != null) {
                if (!(annot.startsWith("+list") || annot.startsWith("-list")) && ln.blanklines) {
                    ln.tight = false
                }
                if (!(annot == "+list_item" || annot == "-list_item")) {
                    ln.blanklines = false
                }
            }
        }

        handle(annot, suffixes, event.startpos, event.endpos, pos)
    }

    fun build(events: Iterator<Event>): Doc {
        var lastpos = 0
        for (event in events) {
            handleEvent(event)
            lastpos = event.endpos
        }
        var lastloc: SourceLoc? = null
        if (options.sourcePositions) {
            lastloc = getSourceLoc(lastpos)
        }

        // close any open sections:
        var pnode = topContainer()
        while ((pnode.headinglevel ?: 0) > 0) {
            // close sections til we get to the doc level
            popContainer(lastloc?.let { Pos(it, it) })
            addChildToTip(
                Section(pnode.children.typed()).withProps(pnode.attributes, pnode.autoAttributes, pnode.pos),
            )
            pnode = topContainer()
        }

        val root = containers[0]
        val doc =
            Doc(
                children = root.children.typed(),
                references = references,
                autoReferences = autoReferences,
                footnotes = footnotes,
            )
        if (root.autoAttributes != null) {
            doc.autoAttributes = root.autoAttributes
        }
        if (root.attributes != null) {
            doc.attributes = root.attributes
        }
        return doc
    }
}

// ---- renderAST ----

private fun hex4(code: Int): String {
    val digits = "0123456789abcdef"
    val sb = StringBuilder(4)
    for (shift in intArrayOf(12, 8, 4, 0)) sb.append(digits[(code shr shift) and 0xF])
    return sb.toString()
}

// JSON.stringify of a string: well-formed (ES2019) output, lone surrogates escaped.
private fun parseJsonStringify(s: String): String {
    val sb = StringBuilder(s.length + 2)
    sb.append('"')
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            c == '"' -> {
                sb.append("\\\"")
            }

            c == '\\' -> {
                sb.append("\\\\")
            }

            c == '\b' -> {
                sb.append("\\b")
            }

            c == '\u000C' -> {
                sb.append("\\f")
            }

            c == '\n' -> {
                sb.append("\\n")
            }

            c == '\r' -> {
                sb.append("\\r")
            }

            c == '\t' -> {
                sb.append("\\t")
            }

            c.code < 0x20 -> {
                sb.append("\\u").append(hex4(c.code))
            }

            c.isHighSurrogate() -> {
                if (i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                    sb.append(c).append(s[i + 1])
                    i++
                } else {
                    sb.append("\\u").append(hex4(c.code))
                }
            }

            c.isLowSurrogate() -> {
                sb.append("\\u").append(hex4(c.code))
            }

            else -> {
                sb.append(c)
            }
        }
        i++
    }
    sb.append('"')
    return sb.toString()
}

// djot.js's stringify: JSON.stringify(x).replace(/\\\n/g, "\\n")
private fun stringify(s: String): String = parseJsonStringify(s).replace("\\\n", "\\n")

private fun field(
    buff: StringBuilder,
    k: String,
    v: String?,
) {
    if (v != null) {
        buff
            .append(' ')
            .append(k)
            .append('=')
            .append(stringify(v))
    }
}

private fun field(
    buff: StringBuilder,
    k: String,
    v: Int?,
) {
    if (v != null) {
        buff
            .append(' ')
            .append(k)
            .append('=')
            .append(v.toString())
    }
}

private fun field(
    buff: StringBuilder,
    k: String,
    v: Long?,
) {
    if (v != null) {
        buff
            .append(' ')
            .append(k)
            .append('=')
            .append(v.toString())
    }
}

private fun field(
    buff: StringBuilder,
    k: String,
    v: Boolean,
) {
    buff
        .append(' ')
        .append(k)
        .append('=')
        .append(if (v) "true" else "false")
}

// Each node's own fields, in the order parse.ts creates them (JS `for (const k in node)`),
// leaving out children, tag, pos, attributes, autoAttributes, references, autoReferences and
// footnotes.
private fun renderFields(
    node: AstNode,
    buff: StringBuilder,
) {
    when (node) {
        is Str -> {
            field(buff, "text", node.text)
        }

        is FootnoteReference -> {
            field(buff, "text", node.text)
        }

        is Verbatim -> {
            field(buff, "text", node.text)
        }

        // a verbatim retagged in place: text first, format added after
        is RawInline -> {
            field(buff, "text", node.text)
            field(buff, "format", node.format)
        }

        is InlineMath -> {
            field(buff, "text", node.text)
        }

        is DisplayMath -> {
            field(buff, "text", node.text)
        }

        is Url -> {
            field(buff, "text", node.text)
        }

        is Email -> {
            field(buff, "text", node.text)
        }

        is Symb -> {
            field(buff, "alias", node.alias)
        }

        is SmartPunctuation -> {
            field(buff, "type", node.type)
            field(buff, "text", node.text)
        }

        is Link -> {
            field(buff, "destination", node.destination)
            field(buff, "reference", node.reference)
        }

        is Image -> {
            field(buff, "destination", node.destination)
            field(buff, "reference", node.reference)
        }

        is Heading -> {
            field(buff, "level", node.level)
        }

        is TaskList -> {
            field(buff, "tight", node.tight)
        }

        is BulletList -> {
            field(buff, "tight", node.tight)
            field(buff, "style", node.style)
        }

        is OrderedList -> {
            field(buff, "style", node.style)
            field(buff, "start", node.start)
            field(buff, "tight", node.tight)
        }

        is TaskListItem -> {
            field(buff, "checkbox", node.checkbox)
        }

        is Row -> {
            field(buff, "head", node.head)
        }

        is Cell -> {
            field(buff, "head", node.head)
            field(buff, "align", node.align)
        }

        is Footnote -> {
            field(buff, "label", node.label)
        }

        is Reference -> {
            field(buff, "label", node.label)
            field(buff, "destination", node.destination)
        }

        is RawBlock -> {
            field(buff, "format", node.format)
            field(buff, "text", node.text)
        }

        is CodeBlock -> {
            field(buff, "text", node.text)
            field(buff, "lang", node.lang)
        }

        else -> {}
    }
}

private fun renderAstNode(
    node: AstNode,
    buff: StringBuilder,
    indent: Int,
) {
    repeat(indent) { buff.append(' ') }
    if (indent > 128) {
        buff.append("(((DEEPLY NESTED CONTENT OMITTED)))\n")
        return
    }

    buff.append(node.tag)
    val pos = node.pos
    if (pos != null) {
        buff
            .append(" (")
            .append(pos.start.line)
            .append(':')
            .append(pos.start.col)
            .append(':')
            .append(pos.start.offset)
            .append('-')
            .append(pos.end.line)
            .append(':')
            .append(pos.end.col)
            .append(':')
            .append(pos.end.offset)
            .append(')')
    }
    renderFields(node, buff)
    val attributes = node.attributes
    if (attributes != null) {
        for ((k, v) in parseJsOrderedEntries(attributes)) {
            buff
                .append(' ')
                .append(k)
                .append('=')
                .append(stringify(v))
        }
    }
    buff.append('\n')
    for (child in node.childNodes) {
        renderAstNode(child, buff, indent + 2)
    }
}

/**
 * Renders [doc] in djot.js's human-readable AST form, with indentation showing the hierarchy,
 * followed by the document's references and footnotes.
 */
public fun renderAST(doc: Doc): String {
    val buff = StringBuilder()
    renderAstNode(doc, buff, 0)
    if (doc.references.isNotEmpty()) {
        buff.append("references\n")
        for ((k, v) in parseJsOrderedEntries(doc.references)) {
            buff.append("  [").append(stringify(k)).append("] =\n")
            renderAstNode(v, buff, 4)
        }
    }
    if (doc.footnotes.isNotEmpty()) {
        buff.append("footnotes\n")
        for ((k, v) in parseJsOrderedEntries(doc.footnotes)) {
            buff.append("  [").append(stringify(k)).append("] =\n")
            renderAstNode(v, buff, 4)
        }
    }
    return buff.toString()
}
