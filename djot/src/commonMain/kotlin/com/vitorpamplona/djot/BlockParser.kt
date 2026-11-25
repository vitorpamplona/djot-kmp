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

// Ported from djot.js src/block.ts (MIT, Copyright (C) 2022 John MacFarlane).

/**
 * Parses [input] into djot's flat stream of block and inline [Event]s, in document order
 * (djot.js's `parseEvents`). The events are produced lazily as the result is iterated; the
 * result is meant to be iterated once.
 */
public fun parseEvents(
    input: String,
    options: ParseOptions = ParseOptions(),
): Iterable<Event> = EventParser(input, options)

// Everything below is nested in EventParser, so that none of its helper names can clash
// with the private top-level helpers of the other files in this package.
internal class EventParser(
    input: String,
    options: ParseOptions = ParseOptions(),
) : Iterable<Event> {
    private enum class ContentType {
        None,
        Inline,
        Block,
        Text,
        Cells,
        Attributes,
        ListItem,
    }

    // djot.js's BlockSpec (and StartSpec, whose startChars is null only for paraSpec).
    private class BlockSpec(
        val name: String,
        val type: ContentType,
        val content: ContentType,
        val startChars: BooleanArray?,
        val `continue`: (Container) -> Boolean,
        val open: (BlockSpec) -> Boolean,
        val close: () -> Unit,
    )

    // djot.js keeps `extra` as an untyped record; these are all the keys block.ts uses.
    private class Extra(
        var level: Int = 0,
        var noteLabel: String? = null,
        var indent: Int = 0,
        var key: String? = null,
        var styles: List<String> = emptyList(),
        var columns: Int = 0,
        var status: String? = null,
        var startpos: Int = 0,
        var slices: MutableList<AttributeSlice> = ArrayList(),
        var colons: Int = 0,
        var endFenceStartpos: Int? = null,
        var endFenceEndpos: Int? = null,
        var closePattern: Regex? = null,
    )

    private class Container(
        spec: BlockSpec,
        val extra: Extra,
    ) {
        val name: String = spec.name
        val type: ContentType = spec.type
        val content: ContentType = spec.content
        val `continue`: (Container) -> Boolean = spec.`continue`
        val close: () -> Unit = spec.close
        var indent: Int = 0
        var inlineParser: InlineParser? = null
        var attributeParser: AttributeParser? = null
    }

    // find.ts's result
    private class FindResult(
        val startpos: Int,
        val endpos: Int,
        val captures: List<String>,
    )

    val options: ParseOptions = options
    val warn: WarningHandler = options.warn ?: WarningHandler { }

    // Ensure the subject ends with a newline character
    val subject: String = if (input.endsWith("\n")) input else input + "\n"
    val maxoffset: Int = this.subject.length - 1

    // whether the subject contains a CR that is not part of a CRLF, which
    // decides how getEol looks for the end of a line
    val hasLoneCR: Boolean = this.subject.indexOf('\r') != -1 && hasLoneCarriageReturn(this.subject)
    var indent: Int = 0
    var startline: Int = 0
    var starteol: Int = 0
    var endeol: Int = 0
    val matches: MutableList<Event> = ArrayList()
    private val containers: MutableList<Container> = ArrayList()
    var pos: Int = 0
    var lastMatchedContainer: Int = 0
    var finishedLine: Boolean = false
    var returned: Int = 0

    // djot.js shares these across parsers in a module-level Map; one per parser
    // keeps parsers on different threads apart without changing what they match.
    private val closePatterns = HashMap<String, Regex>()

    private val paraSpec: BlockSpec =
        BlockSpec(
            name = "para",
            type = ContentType.Block,
            content = ContentType.Inline,
            startChars = null,
            `continue` = { _ ->
                if (!isWhitespace(charCodeAt(this.subject, this.pos))) {
                    true
                } else {
                    false
                }
            },
            open = { spec ->
                this.addContainer(Container(spec, Extra()))
                this.addMatch(this.pos, this.pos, "+para")
                true
            },
            close = {
                this.getInlineMatches()
                popContainer()
                val last = this.matches.lastOrNull()
                val ep = if (last != null && last.endpos + 1 != 0) last.endpos + 1 else this.pos
                this.addMatch(ep, ep, "-para")
            },
        )

    private val specs: List<BlockSpec> =
        listOf(
            BlockSpec(
                name = "block_quote",
                type = ContentType.Block,
                content = ContentType.Block,
                startChars = startBlockquote,
                `continue` = { _ ->
                    if (this.find(pattBlockquotePrefix) != null) {
                        this.pos = this.pos + 1
                        true
                    } else {
                        false
                    }
                },
                open = { spec ->
                    if (this.find(pattBlockquotePrefix) != null) {
                        this.addContainer(Container(spec, Extra()))
                        this.addMatch(this.pos, this.pos, "+block_quote")
                        this.pos = this.pos + 1
                        true
                    } else {
                        false
                    }
                },
                close = {
                    popContainer()
                    this.addMatch(this.pos, this.pos, "-block_quote")
                },
            ),
            BlockSpec(
                name = "heading",
                type = ContentType.Block,
                content = ContentType.Inline,
                startChars = startBangs,
                `continue` = { container ->
                    val m = this.find(pattBangs)
                    if (m != null &&
                        container.extra.level == (m.endpos - m.startpos + 1) &&
                        isWhitespace(charCodeAt(this.subject, m.endpos + 1))
                    ) {
                        this.pos = m.endpos + 1
                        true
                    } else {
                        false
                    }
                },
                open = { spec ->
                    val m = this.find(pattBangs)
                    if (m != null && isWhitespace(charCodeAt(this.subject, m.endpos + 1))) {
                        val level = m.endpos - m.startpos + 1
                        this.addContainer(Container(spec, Extra(level = level)))
                        this.addMatch(m.startpos, m.endpos, "+heading")
                        this.pos = m.endpos + 1
                        true
                    } else {
                        false
                    }
                },
                close = {
                    this.getInlineMatches()
                    popContainer()
                    val last = this.matches.lastOrNull()
                    val ep = if (last != null && last.endpos + 1 != 0) last.endpos + 1 else this.pos
                    this.addMatch(ep, ep, "-heading")
                },
            ),
            BlockSpec(
                name = "caption",
                type = ContentType.Block,
                content = ContentType.Inline,
                startChars = startCaption,
                `continue` = { _ ->
                    !isWhitespace(charCodeAt(this.subject, this.pos))
                },
                open = { spec ->
                    val m = this.find(pattCaptionStart)
                    if (m != null) {
                        this.pos = m.endpos + 1
                        this.addContainer(Container(spec, Extra()))
                        this.addMatch(this.pos, this.pos, "+caption")
                        true
                    } else {
                        false
                    }
                },
                close = {
                    this.getInlineMatches()
                    popContainer()
                    this.addMatch(this.pos - 1, this.pos - 1, "-caption")
                },
            ),
            // should go before reference definitions
            BlockSpec(
                name = "footnote",
                type = ContentType.Block,
                content = ContentType.Block,
                startChars = startBracket,
                `continue` = { container ->
                    // (container.extra.indent || 0): indent is always set, and 0 || 0 is 0
                    if (this.indent > container.extra.indent ||
                        this.pos == this.starteol
                    ) {
                        true
                    } else {
                        false
                    }
                },
                open = { spec ->
                    val m = this.find(pattFootnoteStart)
                    if (m != null) {
                        val sp = m.startpos
                        val ep = m.endpos
                        val label = m.captures[0]
                        // adding container will close others
                        this.addContainer(Container(spec, Extra(noteLabel = label, indent = this.indent)))
                        this.addMatch(sp, sp, "+footnote")
                        this.addMatch(sp + 2, ep - 3, "note_label")
                        this.pos = ep
                        true
                    } else {
                        false
                    }
                },
                close = {
                    popContainer()
                    this.addMatch(this.pos, this.pos, "-footnote")
                },
            ),
            BlockSpec(
                name = "reference_definition",
                type = ContentType.Block,
                content = ContentType.None,
                startChars = startBracket,
                `continue` = continueFn@{ container ->
                    if (container.extra.indent >= this.indent) {
                        return@continueFn false
                    }
                    val nws = this.find(pattNonWhitespace)
                    if (this.pos < this.starteol &&
                        nws != null && nws.endpos == this.starteol - 1
                    ) {
                        this.addMatch(this.pos, this.starteol - 1, "reference_value")
                        this.pos = this.starteol
                        true
                    } else {
                        false
                    }
                },
                open = { spec ->
                    val m = this.find(pattReferenceDefinition)
                    if (m != null) {
                        val label = m.captures[0]
                        val value = jsTrimStart(m.captures[1])
                        this.addContainer(Container(spec, Extra(key = label, indent = this.indent)))
                        this.addMatch(m.startpos, m.startpos, "+reference_definition")
                        this.addMatch(m.startpos, m.startpos + label.length + 1, "reference_key")
                        if (value.length > 0) {
                            this.addMatch(this.starteol - value.length, this.starteol - 1, "reference_value")
                        }
                        this.pos = this.starteol - 1
                        true
                    } else {
                        false
                    }
                },
                close = {
                    popContainer()
                    this.addMatch(this.pos, this.pos, "-reference_definition")
                },
            ),
            // should go before list_item_spec
            BlockSpec(
                name = "thematic_break",
                type = ContentType.Block,
                content = ContentType.None,
                startChars = startThematicBreak,
                `continue` = { _ -> false },
                open = { spec ->
                    val m = this.find(pattThematicBreak)
                    if (m != null) {
                        this.addContainer(Container(spec, Extra()))
                        this.addMatch(m.startpos, m.endpos, "thematic_break")
                        this.pos = m.endpos
                        true
                    } else {
                        false
                    }
                },
                close = {
                    popContainer()
                },
            ),
            BlockSpec(
                name = "list",
                type = ContentType.Block,
                content = ContentType.ListItem,
                startChars = startListMarker,
                `continue` = continueFn@{ container ->
                    // TODO remove code duplication btw list and list_item
                    if (this.indent > container.extra.indent ||
                        this.pos == this.starteol
                    ) {
                        return@continueFn true
                    } else { // match a list item of the correct type
                        val m = this.find(pattListMarker) ?: return@continueFn false
                        var marker = jsSubstring(this.subject, m.startpos, m.endpos)
                        val mtask = this.find(pattTaskListMarker)
                        if (mtask != null) {
                            marker = jsSubstring(this.subject, mtask.startpos, mtask.startpos + 5)
                        }

                        val styles = getListStyles(marker)
                        val newstyles = ArrayList<String>()
                        for (style in container.extra.styles) {
                            if (styles.contains(style)) {
                                newstyles.add(style)
                            }
                        }
                        if (newstyles.size > 0) {
                            // narrow styles
                            container.extra.styles = newstyles
                            return@continueFn true
                        }
                    }
                    false
                },
                open = openFn@{ spec ->
                    val m = this.find(pattListMarker) ?: return@openFn false
                    val sp = m.startpos
                    val ep = m.endpos
                    var marker = jsSubstring(this.subject, sp, ep)

                    val mtask = this.find(pattTaskListMarker)
                    if (mtask != null) {
                        marker = jsSubstring(this.subject, mtask.startpos, mtask.startpos + 5)
                    }

                    // some items have ambiguous style
                    val styles = getListStyles(marker)
                    if (styles.size == 0) {
                        return@openFn false
                    }
                    val data = Extra(styles = styles, indent = this.indent)
                    // adding container will close others
                    this.addContainer(Container(spec, data))
                    var annot = "+list"
                    for (style in styles) {
                        annot = annot + "|" + style
                    }
                    this.addMatch(sp, ep - 1, annot)
                    true
                },
                close = {
                    popContainer()
                    this.addMatch(this.pos, this.pos, "-list")
                },
            ),
            BlockSpec(
                name = "list_item",
                type = ContentType.ListItem,
                content = ContentType.Block,
                startChars = startListMarker,
                `continue` = { container ->
                    (
                        this.indent > container.extra.indent ||
                            this.pos == this.starteol
                    )
                },
                open = openFn@{ spec ->
                    val m = this.find(pattListMarker) ?: return@openFn false
                    val sp = m.startpos
                    val ep = m.endpos
                    var marker = jsSubstring(this.subject, sp, ep)
                    var checkbox: String? = null

                    val mtask = this.find(pattTaskListMarker)
                    if (mtask != null) {
                        marker = jsSubstring(this.subject, mtask.startpos, mtask.startpos + 5)
                        checkbox = jsSubstring(this.subject, mtask.startpos + 3, mtask.startpos + 4)
                    }

                    // some items have ambiguous style
                    val styles = getListStyles(marker)
                    if (styles.size == 0) {
                        return@openFn false
                    }
                    val data = Extra(styles = styles, indent = this.indent)
                    // adding container will close others
                    this.addContainer(Container(spec, data))
                    var annot = "+list_item"
                    for (style in styles) {
                        annot = annot + "|" + style
                    }
                    this.addMatch(sp, ep - 1, annot)
                    this.pos = ep

                    // `if (checkbox)`: a JS string is truthy when it is not empty
                    if (!checkbox.isNullOrEmpty()) {
                        if (checkbox == " ") {
                            this.addMatch(sp + 2, sp + 4, "checkbox_unchecked")
                        } else {
                            this.addMatch(sp + 2, sp + 4, "checkbox_checked")
                        }
                        this.pos = sp + 5
                    }
                    true
                },
                close = {
                    popContainer()
                    this.addMatch(this.pos - 1, this.pos - 1, "-list_item")
                },
            ),
            BlockSpec(
                name = "table",
                type = ContentType.Block,
                content = ContentType.Cells,
                startChars = startTableRow,
                `continue` = { _ ->
                    val m = this.find(pattTableRow)
                    if (m != null) {
                        val rawrow = m.captures[0]
                        this.parseTableRow(m.startpos, m.startpos + rawrow.length - 1)
                    } else {
                        false
                    }
                },
                open = { spec ->
                    val m = this.find(pattTableRow)
                    if (m != null) {
                        this.addContainer(Container(spec, Extra(columns = 0)))
                        val rawrow = m.captures[0]
                        this.addMatch(m.startpos, m.startpos, "+table")
                        if (this.parseTableRow(m.startpos, m.startpos + rawrow.length - 1)) {
                            true
                        } else {
                            this.matches.removeAt(this.matches.size - 1) // remove "+table" match
                            popContainer()
                            false
                        }
                    } else {
                        false
                    }
                },
                close = {
                    popContainer()
                    this.addMatch(this.pos, this.pos, "-table")
                },
            ),
            BlockSpec(
                name = "attributes",
                type = ContentType.Block,
                content = ContentType.Attributes,
                startChars = startAttributes,
                open = { spec ->
                    if (charCodeAt(this.subject, this.pos) == 123) { // {
                        val attributeParser = AttributeParser(this.subject)
                        val res = attributeParser.feed(this.pos, this.starteol)
                        if (res.status == "fail") {
                            false
                        } else if (res.status == "done" &&
                            this.find(this.subject, pattEndline, res.position + 1) == null
                        ) {
                            false
                        } else {
                            val container =
                                this.addContainer(
                                    Container(
                                        spec,
                                        Extra(
                                            status = res.status,
                                            indent = this.indent,
                                            startpos = this.pos,
                                            slices = ArrayList(),
                                        ),
                                    ),
                                )
                            container.attributeParser = attributeParser
                            container.extra.slices =
                                mutableListOf(AttributeSlice(startpos = this.pos, endpos = this.starteol))
                            this.pos = this.starteol
                            true
                        }
                    } else {
                        false
                    }
                },
                `continue` = continueFn@{ container ->
                    if (container.extra.status == "done") {
                        return@continueFn false
                    }
                    val containerAttributeParser = container.attributeParser
                    if (containerAttributeParser != null && this.indent > container.extra.indent) {
                        container.extra.slices.add(
                            AttributeSlice(startpos = this.pos, endpos = this.starteol),
                        )
                        val res = containerAttributeParser.feed(this.pos, this.endeol)
                        container.extra.status = res.status
                        if (res.status != "fail" ||
                            this.find(this.subject, pattEndline, res.position + 1) == null
                        ) {
                            this.pos = this.starteol
                            return@continueFn true
                        }
                    }
                    // if we get to here, we don't continue; either we
                    // reached the end of indentation or we failed in
                    // parsing attributes
                    // attribute parsing failed; convert to para and continue with that
                    this.addMatch(container.extra.startpos, container.extra.startpos, "+para")
                    val attrContainer = popContainer() // remove attribute contain
                    // add para container
                    val para = this.addContainer(Container(this.paraSpec, Extra()))
                    // reparse the text we couldn't parse as a block attribute:
                    val paraInlineParser = para.inlineParser
                    if (paraInlineParser == null || attrContainer == null) {
                        throw IllegalStateException("Missing inlineParser or attrContainer")
                    }
                    paraInlineParser.attributeSlices = attrContainer.extra.slices
                    paraInlineParser.reparseAttributes()
                    // A container such as a block quote has already consumed its prefix
                    // on this line. Rewinding to the previous line's attribute end would
                    // feed that prefix to the paragraph as literal text.
                    this.pos = maxOf(this.pos, paraInlineParser.lastpos + 1)
                    // A blank line ends the rejected attribute's paragraph here.
                    // Continuing this container would absorb the blank and join the
                    // following text into the same paragraph.
                    this.pos != this.starteol
                },
                close = closeFn@{
                    val container = popContainer() ?: return@closeFn
                    if (container.extra.status == "continue") {
                        // attribute parsing failed; convert to para
                        this.addMatch(container.extra.startpos, container.extra.startpos, "+para")
                        // add para container
                        val para = this.addContainer(Container(this.paraSpec, Extra()), true)
                        val paraInlineParser =
                            para.inlineParser ?: throw IllegalStateException("Could not add paragraph")
                        // reparse the text we couldn't parse as a block attribute:
                        paraInlineParser.attributeSlices = container.extra.slices
                        paraInlineParser.reparseAttributes()
                        para.close()
                    } else {
                        this.addMatch(container.extra.startpos, container.extra.startpos, "+block_attributes")
                        val containerAttributeParser = container.attributeParser
                        if (containerAttributeParser != null) { // should always be true
                            val attrMatches = containerAttributeParser.matches
                            for (match in attrMatches) {
                                this.matches.add(match)
                            }
                        }
                        this.addMatch(this.pos, this.pos, "-block_attributes")
                    }
                },
            ),
            BlockSpec(
                name = "fenced_div",
                type = ContentType.Block,
                content = ContentType.Block,
                startChars = startDivFence,
                `continue` = continueFn@{ container ->
                    val tip = this.tip()
                    if (tip != null && tip.name == "code_block") {
                        return@continueFn true // see #109
                    }
                    val m = this.find(pattDivFence)
                    if (m != null && container.extra.colons != 0) {
                        val colons = m.captures[0]
                        if (colons.length >= container.extra.colons) {
                            container.extra.endFenceStartpos = m.startpos
                            container.extra.endFenceEndpos = m.startpos + colons.length - 1
                            this.pos = this.starteol // before newline (CRLF-safe, see #113)
                            return@continueFn false
                        }
                    }
                    true
                },
                open = openFn@{ spec ->
                    val m = this.find(pattDivFenceStart) ?: return@openFn false
                    val colons = m.captures[0]
                    val m2 = this.find(this.subject, pattDivFenceEnd, m.endpos + 1) ?: return@openFn false
                    val clsp = m2.startpos
                    val lang = m2.captures[0]
                    this.addContainer(Container(spec, Extra(colons = colons.length)))
                    this.addMatch(m.startpos, m.endpos, "+div")
                    if (lang.length > 0) {
                        this.addMatch(clsp, clsp + lang.length - 1, "class")
                    }
                    this.pos = m2.endpos + 1
                    this.finishedLine = true
                    true
                },
                close = closeFn@{
                    val container = popContainer() ?: return@closeFn
                    val sp = jsOr(container.extra.endFenceStartpos, this.pos)
                    val ep = jsOr(container.extra.endFenceEndpos, this.pos)
                    // check to make sure the match is in order
                    this.addMatch(sp, ep, "-div")
                    if (sp == ep) {
                        this.warn.warn(Warning("Unclosed div", this.pos))
                    }
                },
            ),
            BlockSpec(
                name = "code_block",
                type = ContentType.Block,
                content = ContentType.Text,
                startChars = startCodeFence,
                `continue` = { container ->
                    val m = this.find(container.extra.closePattern!!)
                    if (m != null) {
                        container.extra.endFenceStartpos = m.startpos
                        container.extra.endFenceEndpos = m.startpos + m.captures[0].length - 1
                        this.pos = m.endpos // before newline
                        this.finishedLine = true
                        false
                    } else {
                        true
                    }
                },
                open = { spec ->
                    val m = this.find(pattCodeFence)
                    if (m != null) {
                        val border = m.captures[0]
                        val ws = m.captures[1]
                        val lang = m.captures[2]
                        val isRaw = lang.startsWith("=")
                        val closePattern = closePatternFor(border)
                        val cont = this.addContainer(Container(spec, Extra(closePattern = closePattern)))
                        cont.indent = this.indent
                        this.addMatch(m.startpos, m.startpos + border.length - 1, "+code_block")
                        if (lang.length > 0) {
                            val langstart = m.startpos + border.length + ws.length
                            if (isRaw) {
                                this.addMatch(langstart, langstart + lang.length - 1, "raw_format")
                            } else {
                                this.addMatch(langstart, langstart + lang.length - 1, "code_language")
                            }
                        }
                        this.pos = m.endpos // before newline;
                        this.finishedLine = true
                        true
                    } else {
                        false
                    }
                },
                close = closeFn@{
                    val container = popContainer() ?: return@closeFn
                    val sp = jsOr(container.extra.endFenceStartpos, this.pos)
                    val ep = jsOr(container.extra.endFenceEndpos, this.pos)
                    this.addMatch(sp, ep, "-code_block")
                    if (sp == ep) {
                        this.warn.warn(Warning("Unclosed code block", this.pos))
                    }
                },
            ),
        )

    // The pattern that closes a fenced code block depends only on the fence that
    // opened it, so the patterns are kept rather than compiled afresh for each block.
    private fun closePatternFor(border: String): Regex {
        var patt = closePatterns[border]
        if (patt == null) {
            patt = Regex("(" + border + jsSubstring(border, 0, 1) + "*)[ \\t]*[\\r\\n]")
            closePatterns[border] = patt
        }
        return patt
    }

    // find.ts's `find`, without the endpos argument block.ts never passes. The
    // patterns are sticky in djot.js, so a match must begin exactly at startpos.
    private fun find(
        subject: String,
        patt: Regex,
        startpos: Int,
    ): FindResult? {
        if (startpos < 0 || startpos > subject.length) {
            return null
        }
        val result = patt.matchAt(subject, startpos) ?: return null
        val capts = ArrayList<String>()
        val groups = result.groupValues
        for (i in 1 until groups.size) {
            capts.add(groups[i])
        }
        return FindResult(startpos, result.range.last, capts)
    }

    private fun find(patt: Regex): FindResult? = find(this.subject, patt, this.pos)

    private fun tip(): Container? =
        if (this.containers.size >= 1) {
            this.containers[this.containers.size - 1]
        } else {
            null
        }

    // JS's `this.containers.pop()`, which gives undefined on an empty array
    private fun popContainer(): Container? = if (this.containers.isEmpty()) null else this.containers.removeAt(this.containers.size - 1)

    fun addMatch(
        startpos: Int,
        endpos: Int,
        annot: String,
    ) {
        this.matches.add(
            Event(
                startpos = minOf(startpos, this.maxoffset),
                endpos = minOf(endpos, this.maxoffset),
                annot = annot,
            ),
        )
    }

    fun getInlineMatches() {
        val ilparser = this.tip()?.inlineParser
        if (ilparser != null) {
            var last: Event? = null
            for (match in ilparser.getMatches()) {
                if (last != null && last.annot == "str" && match.annot == "str" &&
                    match.startpos == last.endpos + 1
                ) {
                    // just extend last str:
                    val i = this.matches.size - 1
                    this.matches[i] = this.matches[i].copy(endpos = match.endpos)
                } else {
                    this.matches.add(match)
                }
                last = match
            }
        }
    }

    fun closeUnmatchedContainers() {
        val lastMatched = this.lastMatchedContainer
        // close unmatched containers
        var tip = this.tip()
        while (tip != null &&
            this.containers.size - 1 > lastMatched
        ) {
            tip.close()
            tip = this.tip()
        }
    }

    private fun addContainer(
        container: Container,
        skipCloseUnmatched: Boolean = false,
    ): Container {
        if (!skipCloseUnmatched) {
            this.closeUnmatchedContainers()
        }
        // close containers that can't contain this one:
        var tip = this.tip()
        while (tip != null && tip.content != container.type) {
            tip.close()
            tip = this.tip()
        }
        if (container.content == ContentType.Inline) {
            container.inlineParser = InlineParser(this.subject, ParseOptions(warn = this.warn))
        }
        this.containers.add(container)
        return container
    }

    // move parser position to first nonspace, adjusting indent
    fun skipSpace() {
        val subject = this.subject
        var newpos = this.pos
        while (isSpaceOrTab(charCodeAt(subject, newpos))) newpos++
        this.indent = newpos - this.startline
        this.pos = newpos
    }

    // set this.starteol, this.endeol
    fun getEol() {
        val subject = this.subject
        val pos = this.pos
        // The constructor guarantees a trailing newline, so there is always a
        // line ending at or after pos.  A lone CR ends a line too; unless the
        // subject holds a CR outside a CRLF (checked once, in the constructor)
        // the only CR that can end this line is the one just before its LF.
        if (this.hasLoneCR) {
            // reEol = /[\r\n]/g from pos
            var i = pos
            while (i < subject.length && subject[i] != '\r' && subject[i] != '\n') i++
            this.starteol = i
            if (charCodeAt(subject, i) == 13 && charCodeAt(subject, i + 1) == 10) {
                this.endeol = i + 1
            } else {
                this.endeol = i
            }
        } else {
            val i = subject.indexOf('\n', pos)
            this.starteol = if (i > pos && charCodeAt(subject, i - 1) == 13) i - 1 else i
            this.endeol = i
        }
    }

    private class TableCell(
        val startpos: Int,
        val endpos: Int,
        val matches: List<Event>,
    )

    private fun parseTableCell(): TableCell? {
        val inlineParser = InlineParser(this.subject, ParseOptions(warn = this.warn))
        var cellComplete = false
        val sp = this.pos - 1 // we start on char after |
        var ep = sp // for now
        this.skipSpace()
        while (!cellComplete) {
            val m = this.find(pattNextBarOrTicks)
            if (m == null) {
                cellComplete = false
                break
            } else { // we matched a | or `+
                val nextbar = m.endpos
                if (charCodeAt(this.subject, nextbar) == '`'.code ||
                    inlineParser.inVerbatim()
                ) {
                    inlineParser.feed(this.pos, nextbar)
                } else if (charCodeAt(this.subject, nextbar - 1) == '\\'.code) { // escaped |
                    inlineParser.feed(this.pos, nextbar)
                } else {
                    inlineParser.feed(this.pos, nextbar - 1)
                    ep = nextbar
                    cellComplete = true
                }
                this.pos = nextbar + 1
            }
        }
        return if (cellComplete) {
            val cellMatches = inlineParser.getMatches()
            TableCell(sp, ep, cellMatches)
        } else {
            null
        }
    }

    // Parameters are start and end position
    fun parseTableRow(
        sp: Int,
        ep: Int,
    ): Boolean {
        val origMatches = this.matches.size // so we can rewind
        val startpos = this.pos
        this.addMatch(sp, sp, "+row")
        // skip | and any initial space in the cell:
        this.pos++

        // check to see if we have a separator line
        val seps = ArrayList<Event>()
        var p = this.pos
        var sepfound = false
        while (!sepfound) {
            val m = this.find(this.subject, pattRowSep, p)
            if (m != null) {
                val left = m.captures[0]
                val right = m.captures[1]
                val trailing = m.captures[2]
                var st = "separator_default"
                if (left.length > 0 && right.length > 0) {
                    st = "separator_center"
                } else if (right.length > 0) {
                    st = "separator_right"
                } else if (left.length > 0) {
                    st = "separator_left"
                }
                seps.add(Event(startpos = m.startpos, endpos = m.endpos - trailing.length, annot = st))
                p = m.endpos + 1
                if (p == this.starteol) {
                    sepfound = true
                    break
                }
            } else {
                break
            }
        }
        if (sepfound) {
            for (match in seps) {
                this.addMatch(match.startpos, match.endpos, match.annot)
            }
            this.addMatch(this.starteol - 1, this.starteol - 1, "-row")
            this.pos = this.starteol
            this.finishedLine = true
            return true
        }

        // if we get here, we're parsing a regular row
        while (this.pos <= ep) {
            val cell = this.parseTableCell()
            if (cell != null) {
                // add matches for cell
                this.addMatch(cell.startpos, cell.startpos, "+cell")
                val cellMatches = cell.matches
                cellMatches.forEachIndexed { i, match ->
                    val s = match.startpos
                    var e = match.endpos
                    if (i == cellMatches.size - 1 && match.annot == "str") {
                        // strip trailing spaces and tabs
                        while (e >= s &&
                            (
                                charCodeAt(this.subject, e) == 32 ||
                                    charCodeAt(this.subject, e) == 9
                            )
                        ) {
                            e = e - 1
                        }
                    }
                    this.addMatch(s, e, match.annot)
                }
                this.addMatch(cell.endpos, cell.endpos, "-cell")
            } else {
                // rewind, this is not a valid table row
                this.pos = startpos
                while (this.matches.size > origMatches) {
                    this.matches.removeAt(this.matches.size - 1)
                }
                return false
            }
        }

        // if we get here, we've parsed a table row
        this.addMatch(this.pos, this.pos, "-row")
        this.pos = this.starteol
        this.finishedLine = true
        return true
    }

    // djot.js's [Symbol.iterator](): an iterator over events.  Its `next()` is
    // nextEvent() below, which gives null where djot.js reports `done: true`.
    override fun iterator(): Iterator<Event> {
        this.returned = 0
        return object : Iterator<Event> {
            private var pending: Event? = null
            private var done = false

            override fun hasNext(): Boolean {
                if (pending == null && !done) {
                    val value = nextEvent()
                    if (value == null) {
                        done = true
                    } else {
                        pending = value
                    }
                }
                return pending != null
            }

            override fun next(): Event {
                if (!hasNext()) throw NoSuchElementException()
                val value = pending!!
                pending = null
                return value
            }
        }
    }

    private fun nextEvent(): Event? {
        val specs = this.specs
        val subjectlen = this.subject.length

        while (this.pos < subjectlen) {
            // return any accumulated matches
            if (this.matches.size > 0 && this.returned < this.matches.size) {
                this.returned = this.returned + 1
                return this.matches[this.returned - 1]
            }

            // Every event collected so far has been handed over, so there is
            // no need to keep them.  The most recent one stays: closing a
            // paragraph or heading ends it just after the last match before
            // it, which can belong to an earlier line.
            if (this.matches.size >= DRAIN_THRESHOLD &&
                this.returned == this.matches.size
            ) {
                this.matches[0] = this.matches[this.matches.size - 1]
                this.matches.subList(1, this.matches.size).clear()
                this.returned = 1
            }

            this.indent = 0
            this.startline = this.pos
            this.finishedLine = false
            this.getEol()

            // check open containers for continuation
            this.lastMatchedContainer = -1
            var idx = 0
            while (idx < this.containers.size) {
                val container = this.containers[idx]
                // skip any indentation
                this.skipSpace()
                if (container.`continue`(container)) {
                    this.lastMatchedContainer = idx
                } else {
                    break
                }
                idx = idx + 1
            }

            // if we hit a close fence, we can move to next line
            if (this.finishedLine) {
                while (this.containers.size > 0 &&
                    this.lastMatchedContainer < this.containers.size - 1
                ) {
                    val tip = this.tip()
                    tip?.close()
                }
            }

            if (!this.finishedLine) {
                // check for new containers
                this.skipSpace()
                var isBlank = (this.pos == this.starteol)
                var newStarts = false
                var lastMatch: Container? = this.containers.getOrNull(this.lastMatchedContainer)
                var checkStarts =
                    !isBlank &&
                        (
                            lastMatch == null ||
                                lastMatch.content == ContentType.Block ||
                                lastMatch.content == ContentType.ListItem
                        ) &&
                        this.find(pattWord) == null // optimization
                while (checkStarts) {
                    checkStarts = false
                    // Let the character the line begins with say which specs are
                    // worth trying at all.  Anything outside ASCII goes in the
                    // table's last slot, as does the end of the subject (JS NaN).
                    val code = charCodeAt(this.subject, this.pos)
                    val slot = if (code in 0..127) code else 128
                    for (spec in specs) {
                        if (spec.startChars!![slot] &&
                            (
                                (lastMatch == null && spec.type == ContentType.Block) ||
                                    (lastMatch != null && lastMatch.content == spec.type)
                            )
                        ) {
                            if (spec.open(spec)) {
                                val tip = this.tip()
                                if (tip != null) {
                                    this.lastMatchedContainer = this.containers.size - 1
                                    lastMatch = this.containers[this.lastMatchedContainer]
                                    if (this.finishedLine) {
                                        checkStarts = false
                                    } else {
                                        this.skipSpace()
                                        newStarts = true
                                        checkStarts = spec.content == ContentType.Block ||
                                            spec.content == ContentType.ListItem
                                    }
                                } else {
                                    throw IllegalStateException("No tip after opening container")
                                }
                                break
                            }
                        }
                    }
                }

                if (!this.finishedLine) {
                    // handle remaining content
                    this.skipSpace()

                    isBlank = (this.pos == this.starteol)
                    var tip = this.tip()
                    val isLazy =
                        !isBlank &&
                            !newStarts &&
                            this.lastMatchedContainer < this.containers.size - 1 &&
                            tip != null &&
                            tip.content == ContentType.Inline

                    if (!isLazy) {
                        this.closeUnmatchedContainers()
                    }
                    tip = this.tip()

                    // add para by default if there's text
                    if (tip == null || tip.content == ContentType.Block) {
                        if (isBlank) {
                            if (!newStarts) {
                                // need to track these for tight/loose lists
                                this.addMatch(this.pos, this.endeol, "blankline")
                            }
                        } else {
                            this.paraSpec.open(this.paraSpec)
                            tip = this.tip()
                            if (tip != null) {
                                tip.inlineParser = InlineParser(this.subject, this.options)
                            }
                        }
                    }

                    if (tip != null && tip.content == ContentType.Text) {
                        var startpos = this.pos
                        // (tip.indent !== null is always true: indent starts at 0)
                        if (this.indent > tip.indent) {
                            // get back the leading spaces we gobbled
                            startpos = startpos - (this.indent - tip.indent)
                        }
                        this.addMatch(startpos, this.endeol, "str")
                    } else if (tip != null && tip.content == ContentType.Inline && !isBlank) {
                        val tipInlineParser = tip.inlineParser
                        if (tipInlineParser != null) {
                            tipInlineParser.feed(this.pos, this.endeol)
                        }
                    }
                }
            }

            // (this.endeol || this.pos) + 1
            this.pos = (if (this.endeol != 0) this.endeol else this.pos) + 1
        }

        // close all remaining containers
        this.lastMatchedContainer = -1
        this.closeUnmatchedContainers()
        // return any accumulated matches
        if (this.matches.size > 0 && this.returned < this.matches.size) {
            this.returned = this.returned + 1
            return this.matches[this.returned - 1]
        }
        // catch-all: djot.js returns done: true here
        return null
    }

    private companion object {
        // How many events may pile up before the iterator drops the ones it has
        // already handed over.
        const val DRAIN_THRESHOLD = 1024

        // JS `charCodeAt`, with -1 standing for the NaN it gives out of range
        fun charCodeAt(
            s: String,
            i: Int,
        ): Int = if (i >= 0 && i < s.length) s[i].code else -1

        // JS `substring`: clamps both ends to the string and swaps them if reversed
        fun jsSubstring(
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

        // JS `x || fallback` for a number that may be undefined: 0 is falsy too
        fun jsOr(
            x: Int?,
            fallback: Int,
        ): Int = if (x != null && x != 0) x else fallback

        // What JS's \s and String.prototype.trim treat as white space: WhiteSpace and
        // LineTerminator in ECMA-262 (Zs is U+0020, U+00A0, U+1680, U+2000-U+200A,
        // U+202F, U+205F, U+3000).
        fun isJsWhitespace(c: Char): Boolean =
            when (c) {
                '\t', '\n', '\u000B', '\u000C', '\r', ' ', '\u00A0', '\u1680',
                '\u2028', '\u2029', '\u202F', '\u205F', '\u3000', '\uFEFF',
                -> true
                in '\u2000'..'\u200A' -> true
                else -> false
            }

        // JS `trimStart`
        fun jsTrimStart(s: String): String {
            var i = 0
            while (i < s.length && isJsWhitespace(s[i])) i++
            return s.substring(i)
        }

        fun isSpaceOrTab(cp: Int): Boolean = (cp == 32 || cp == 9)

        fun isEolChar(cp: Int): Boolean = (cp == 10 || cp == 13)

        fun isWhitespace(cp: Int): Boolean = isSpaceOrTab(cp) || isEolChar(cp)

        // reLoneCR = /\r(?!\n)/: a CR that is not the first half of a CRLF
        fun hasLoneCarriageReturn(s: String): Boolean {
            var i = s.indexOf('\r')
            while (i != -1) {
                if (i + 1 >= s.length || s[i + 1] != '\n') return true
                i = s.indexOf('\r', i + 1)
            }
            return false
        }

        // Each pattern a block spec's `open` tries begins with a character class, so a
        // line whose first non-space character is outside that class cannot open that
        // kind of block.  One slot per ASCII character and a last (always false) slot for
        // everything else; djot.js builds it by testing each ASCII char against the class.
        fun startCharTable(chars: String): BooleanArray {
            val table = BooleanArray(129)
            for (c in chars) {
                table[c.code] = true
            }
            return table
        }

        // djot.js's patterns are sticky (`y`) RegExps; here they are applied with
        // Regex.matchAt, which anchors them at the search position the same way.
        val pattEndline = Regex("""[ \t]*\r?\n""")

        // \w+\s, with JS's ASCII \w and Unicode \s spelled out
        val pattWord =
            Regex(
                "[A-Za-z0-9_]+[\t\n\u000B\u000C\r \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]",
            )
        val pattNonWhitespace = Regex("""[^ \t\r\n]+""")
        val pattBlockquotePrefix = Regex("""[>][ \t\r\n]""")
        val startBlockquote = startCharTable(">")
        val pattBangs = Regex("""#+""")
        val startBangs = startCharTable("#")
        val pattCodeFence = Regex("""(~~~~*|````*)([ \t]*)([^ \t\r\n`]*)[ \t]*\r?\n""")
        val startCodeFence = startCharTable("~`")
        val pattRowSep = Regex("""(:?)--*(:?)([ \t]*\|[ \t]*)""")
        val pattNextBarOrTicks = Regex("""[^`|\r\n]*(?:[|]|`+)""")
        val pattCaptionStart = Regex("""\^[ \t]+""")
        val startCaption = startCharTable("^")
        val pattFootnoteStart = Regex("""\[\^([^\]\r\n]+)\]:[ \t\r\n]""")
        val pattThematicBreak = Regex("""[-*][ \t]*[-*][ \t]*[-*][-* \t]*\r?\n""")
        val startThematicBreak = startCharTable("-*")
        val pattDivFence = Regex("""(::::*)[ \t]*\r?\n""")
        val pattDivFenceStart = Regex("""(::::*)[ \t]*""")
        val startDivFence = startCharTable(":")

        // [\w_-]*, with JS's ASCII \w spelled out
        val pattDivFenceEnd = Regex("""([A-Za-z0-9_-]*)[ \t]*\r?\n""")
        val pattReferenceDefinition = Regex("""\[([^\]\r\n]*)\]:([ \t]+[^ \t\r\n]*|)[\r\n]""")

        // shared by the footnote and reference definition patterns above
        val startBracket = startCharTable("[")
        val pattTableRow = Regex("""(\|[^\r\n]*\|)[ \t]*\r?\n""")
        val startTableRow = startCharTable("|")
        val pattListMarker =
            Regex(
                """(:?[-*+:]|\([0-9]+\)|[0-9]+[.)]|[ivxlcdmIVXLCDM]+[.)]|\([ivxlcdmIVXLCDM]+\)|[a-zA-Z][.)]|\([a-zA-Z]\))[ \t\r\n]""",
            )
        val pattTaskListMarker = Regex("""[*+-] \[[Xx ]\][ \t\r\n]""")

        // the union of the first character of each branch of pattListMarker
        val startListMarker =
            startCharTable("-*+:(0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ")

        // the "{" of a block attribute
        val startAttributes = startCharTable("{")

        // getListStyles's patterns: JS `exec` on a non-global RegExp, `^`-anchored
        val reStyleTask = Regex("""^[+*-] \[[Xx ]\]""")
        val reStyleDecimal = Regex("""^[(]?[0-9]+[).]""")
        val reStyleRomanLowerOne = Regex("""^[(]?[ivxlcdm][).]""")
        val reStyleRomanUpperOne = Regex("""^[(]?[IVXLCDM][).]""")
        val reStyleRomanLower = Regex("""^[(]?[ivxlcdm]+[).]""")
        val reStyleRomanUpper = Regex("""^[(]?[IVXLCDM]+[).]""")
        val reStyleAlphaLower = Regex("""^[(]?[a-z][).]""")
        val reStyleAlphaUpper = Regex("""^[(]?[A-Z][).]""")

        // the arguments of JS's non-global String.replace, which replaces the first match
        val reDigits = Regex("""[0-9]+""")
        val reLowers = Regex("""[a-z]+""")
        val reUppers = Regex("""[A-Z]+""")
        val reLower = Regex("""[a-z]""")
        val reUpper = Regex("""[A-Z]""")

        // Return array of list styles that match a marker.
        // In ambiguous cases we return multiple values.
        fun getListStyles(marker: String): List<String> =
            if (marker == "+" || marker == "-" || marker == "*" || marker == ":") {
                listOf(marker)
            } else if (reStyleTask.containsMatchIn(marker)) {
                listOf(marker[0].toString() + "X") // task list - include marker for consistency
            } else if (reStyleDecimal.containsMatchIn(marker)) {
                listOf(reDigits.replaceFirst(marker, "1"))
            } else if (reStyleRomanLowerOne.containsMatchIn(marker)) {
                listOf(reLowers.replaceFirst(marker, "i"), reLowers.replaceFirst(marker, "a"))
            } else if (reStyleRomanUpperOne.containsMatchIn(marker)) {
                listOf(reUppers.replaceFirst(marker, "I"), reUppers.replaceFirst(marker, "A"))
            } else if (reStyleRomanLower.containsMatchIn(marker)) {
                listOf(reLowers.replaceFirst(marker, "i"))
            } else if (reStyleRomanUpper.containsMatchIn(marker)) {
                listOf(reUppers.replaceFirst(marker, "I"))
            } else if (reStyleAlphaLower.containsMatchIn(marker)) {
                listOf(reLower.replaceFirst(marker, "a"))
            } else if (reStyleAlphaUpper.containsMatchIn(marker)) {
                listOf(reUpper.replaceFirst(marker, "A"))
            } else { // doesn't match any list style
                listOf()
            }
    }
}
