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

// Ported from djot.js src/html.ts (MIT, Copyright (C) 2022 John MacFarlane).

/**
 * Renders one node in place of the default handler for its tag; see [HtmlOptions.overrides].
 * It renders the node's whole subtree, and may call back into [renderer] (for example
 * [HTMLRenderer.renderChildren] or [HTMLRenderer.renderAstNodeDefault]).
 */
public typealias HtmlOverride = (node: AstNode, renderer: HTMLRenderer) -> String

/**
 * Options for [renderHTML] and [HTMLRenderer] (djot.js's `HTMLRenderOptions`): [warn] receives the
 * renderer's warnings, such as undefined references; [overrides] maps a node tag (`"para"`,
 * `"emph"`, ...) to a function that renders nodes of that tag instead of the default handler.
 */
public class HtmlOptions(
    public val warn: WarningHandler? = null,
    public val overrides: Map<String, HtmlOverride>? = null,
)

// Heading tag names, so that a heading does not have to build one.  The
// parser only ever produces levels 1-6; anything else falls back to
// composing the name.
private val htmlHeadingTags = arrayOf("h1", "h2", "h3", "h4", "h5", "h6")

private val htmlNoWarn = WarningHandler { }

// Whether `k` is an array index ("0", "1", ..., "4294967294"), which a JS
// object iterates before its other keys, in ascending numeric order.
private fun htmlIsArrayIndex(k: String): Boolean {
    if (k.isEmpty() || k.length > 10) return false
    if (k[0] == '0') return k.length == 1
    for (c in k) {
        if (c !in '0'..'9') return false
    }
    return k.toLong() <= 4294967294L
}

// The keys of `map` in the order a JS `for (const k in obj)` would visit
// them, for an object whose keys were inserted in the map's order.
private fun <V> htmlJsKeyOrder(map: Map<String, V>): Collection<String> {
    var hasIndex = false
    for (k in map.keys) {
        if (htmlIsArrayIndex(k)) {
            hasIndex = true
            break
        }
    }
    if (!hasIndex) return map.keys
    val result = ArrayList<String>(map.size)
    map.keys
        .filter { htmlIsArrayIndex(it) }
        .sortedBy { it.toLong() }
        .forEach { result.add(it) }
    map.keys.filter { !htmlIsArrayIndex(it) }.forEach { result.add(it) }
    return result
}

private const val HTML_HEX_DIGITS = "0123456789abcdef"

private fun htmlAppendUnicodeEscape(
    sb: StringBuilder,
    c: Char,
) {
    val code = c.code
    sb.append("\\u")
    sb.append(HTML_HEX_DIGITS[(code shr 12) and 0xF])
    sb.append(HTML_HEX_DIGITS[(code shr 8) and 0xF])
    sb.append(HTML_HEX_DIGITS[(code shr 4) and 0xF])
    sb.append(HTML_HEX_DIGITS[code and 0xF])
}

// JS `JSON.stringify` of a string (well-formed variant: lone surrogates are
// escaped as lowercase `\uXXXX`).
private fun htmlJsonStringify(s: String): String {
    val sb = StringBuilder(s.length + 2)
    sb.append('"')
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\b' -> sb.append("\\b")
            c == '\u000C' -> sb.append("\\f")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' -> htmlAppendUnicodeEscape(sb, c)
            c.isHighSurrogate() -> {
                if (i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                    sb.append(c)
                    sb.append(s[i + 1])
                    i++
                } else {
                    htmlAppendUnicodeEscape(sb, c)
                }
            }
            c.isLowSurrogate() -> htmlAppendUnicodeEscape(sb, c)
            else -> sb.append(c)
        }
        i++
    }
    sb.append('"')
    return sb.toString()
}

/**
 * Renders a djot AST as HTML (djot.js's `HTMLRenderer`). Call [render] once per document; per-tag
 * rendering can be replaced with [HtmlOptions.overrides], and the public members can be overridden
 * in a subclass.
 */
public open class HTMLRenderer(
    options: HtmlOptions = HtmlOptions(),
) {
    /** Receives the renderer's warnings. */
    public var warn: WarningHandler = options.warn ?: htmlNoWarn

    /** The options this renderer was made with. */
    public val options: HtmlOptions = options

    private var tight: Boolean = false

    /** The number each footnote label was given, in the order they were first referenced. */
    public var footnoteIndex: MutableMap<String, Int> = LinkedHashMap() // keys are user-supplied labels

    /** The number the next newly referenced footnote gets. */
    public var nextFootnoteIndex: Int = 1

    /** The footnote labels whose first reference already carries its `fnref` id. */
    public var fnrefIdEmitted: MutableMap<String, Boolean> = LinkedHashMap()

    /** The document's link references, by label. */
    public var references: Map<String, Reference> = LinkedHashMap()

    /** The document's automatic (heading) references, by label. */
    public var autoReferences: Map<String, Reference> = LinkedHashMap()

    // Scratch space returned by renderNodeParts.  Its contents are only
    // valid until the next renderNodeParts call; the walk in renderNested
    // consumes them before making another.
    private val parts: NodeParts = NodeParts()

    // memoised `<tag>` (attribute-free only) and `</tag>` strings:
    private val openTags: MutableMap<String, String> = HashMap()
    private val closeTags: MutableMap<String, String> = HashMap()

    // The HTML a node contributes around its children: `open` is emitted
    // before them and `close` after them.  `children` is the list to walk
    // into; it is null when there is nothing to walk into (leaves, and
    // images, whose children only supply the alt text), and then `open`
    // holds the node's entire output and `close` is not read.
    private class NodeParts {
        var open: String = ""
        var close: String = ""
        var children: List<AstNode>? = null

        // the `tight` value to apply while the children are rendered: lists
        // supply their flag, quotes and divs reset it, other nodes inherit it:
        var tight: Boolean? = null

        // a doc's footnotes, whose section can only be rendered once the body
        // has assigned every footnote its index; null for other nodes:
        var footnotes: Map<String, Footnote>? = null
    }

    // An entry on the renderer's explicit stack: a node whose `open` has
    // been emitted and whose children are still being walked.
    private class RenderFrame(
        var children: List<AstNode>,
        var childIndex: Int,
        var close: String,
        // the `tight` value to restore afterwards, for nodes that change it
        // while their children are rendered; null for other nodes:
        var oldTight: Boolean?,
        var footnotes: Map<String, Footnote>?,
    )

    /** Escapes `&`, `<` and `>` for HTML text. */
    public open fun escape(s: String): String {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '&' || c == '<' || c == '>') break
            i++
        }
        if (i == s.length) return s
        val sb = StringBuilder(s.length + 16)
        sb.append(s.substring(0, i))
        while (i < s.length) {
            when (val c = s[i]) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    /** Escapes `&`, `<`, `>` and `"` for an HTML attribute value. */
    public open fun escapeAttribute(s: String): String {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '&' || c == '<' || c == '>' || c == '"') break
            i++
        }
        if (i == s.length) return s
        val sb = StringBuilder(s.length + 16)
        sb.append(s.substring(0, i))
        while (i < s.length) {
            when (val c = s[i]) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    /** The text each smart punctuation type renders as. */
    public open val smartPunctuationMap: MutableMap<String, String> =
        linkedMapOf(
            "right_single_quote" to "\u2019",
            "left_single_quote" to "\u2018",
            "right_double_quote" to "\u201D",
            "left_double_quote" to "\u201C",
            "ellipses" to "\u2026",
            "em_dash" to "\u2014",
            "en_dash" to "\u2013",
        )

    /**
     * The attributes of an opening tag for [node], each preceded by a space: [extraAttrs] first,
     * then the node's auto-attributes and attributes, then its source position if it has one.
     */
    public open fun renderAttributes(
        node: AstNode,
        extraAttrs: Map<String, String>? = null,
    ): String {
        val result = StringBuilder()
        if (extraAttrs != null) {
            for (k in htmlJsKeyOrder(extraAttrs)) {
                if (k == "class") {
                    var v = extraAttrs.getValue(k)
                    val nodeClass = node.attributes?.get("class")
                    if (!nodeClass.isNullOrEmpty()) {
                        v = "$v $nodeClass"
                    }
                    result
                        .append(' ')
                        .append(k)
                        .append("=\"")
                        .append(escapeAttribute(v))
                        .append('"')
                } else {
                    val v = extraAttrs.getValue(k)
                    result
                        .append(' ')
                        .append(k)
                        .append("=\"")
                        .append(escapeAttribute(v))
                        .append('"')
                }
            }
        }
        // `attributes` win over `autoAttributes` of the same name but keep
        // the position the auto one held, which the merge takes care of.
        val autoAttributes = node.autoAttributes
        val nodeAttributes = node.attributes
        if (autoAttributes != null || nodeAttributes != null) {
            val attributes = LinkedHashMap<String, String>()
            if (autoAttributes != null) attributes.putAll(autoAttributes)
            if (nodeAttributes != null) attributes.putAll(nodeAttributes)
            val extraHasClass = extraAttrs != null && !extraAttrs["class"].isNullOrEmpty()
            for (k in htmlJsKeyOrder(attributes)) {
                val v = attributes.getValue(k)
                if (!(k == "class" && extraHasClass)) {
                    result
                        .append(' ')
                        .append(k)
                        .append("=\"")
                        .append(escapeAttribute(v))
                        .append('"')
                }
            }
        }
        val pos = node.pos
        if (pos != null) {
            val sp = pos.start
            val ep = pos.end
            result
                .append(" data-startpos=\"")
                .append(sp.line)
                .append(':')
                .append(sp.col)
                .append(':')
                .append(sp.offset)
                .append("\" data-endpos=\"")
                .append(ep.line)
                .append(':')
                .append(ep.col)
                .append(':')
                .append(ep.offset)
                .append('"')
        }
        return result.toString()
    }

    /** The opening tag `<tag ...>` for [node], with its attributes. */
    public open fun renderTag(
        tag: String,
        node: AstNode,
        extraAttrs: Map<String, String>? = null,
    ): String {
        if (node.attributes != null || node.autoAttributes != null || extraAttrs != null || node.pos != null) {
            return "<$tag${renderAttributes(node, extraAttrs)}>"
        }
        // with no attributes the tag depends on nothing but its name, and a
        // document asks for the same handful of names over and over, so it
        // is built once rather than reallocated per node
        var cached = openTags[tag]
        if (cached == null) {
            cached = "<$tag>"
            openTags[tag] = cached
        }
        return cached
    }

    /** The closing tag `</tag>`. */
    public open fun renderCloseTag(tag: String): String {
        var cached = closeTags[tag]
        if (cached == null) {
            cached = "</$tag>"
            closeTags[tag] = cached
        }
        return cached
    }

    // Fill in the scratch NodeParts.  Reusing one object rather than
    // returning a fresh one keeps the walk free of per-node allocation.
    private fun setParts(
        open: String,
        close: String,
        children: List<AstNode>?,
    ): NodeParts {
        val parts = this.parts
        parts.open = open
        parts.close = close
        parts.children = children
        // the rare fields default to "not applicable"; the few cases that
        // want them assign them on the returned object
        parts.tight = null
        parts.footnotes = null
        return parts
    }

    // A node with no rendered children, whose output is already known.
    // Only `open` and `children` are assigned: with no children to walk
    // into, none of the other fields is read.
    private fun leafParts(open: String): NodeParts {
        val parts = this.parts
        parts.open = open
        parts.children = null
        return parts
    }

    // A node rendered as `tag` wrapped around its children.
    private fun tagParts(
        tag: String,
        node: AstNode,
        newlines: Int,
        extraAttrs: Map<String, String>? = null,
    ): NodeParts {
        val open = renderTag(tag, node, extraAttrs)
        val close = renderCloseTag(tag)
        return if (newlines == 0) {
            setParts(open, close, node.childNodes)
        } else if (newlines == 1) {
            setParts(open, close + "\n", node.childNodes)
        } else {
            setParts(open + "\n", close + "\n", node.childNodes)
        }
    }

    /** Adds the backlink to footnote reference [ident] at the end of the rendered [note]. */
    public open fun addBacklink(
        note: String,
        ident: Int,
    ): String {
        val backlink = "<a href=\"#fnref$ident\" role=\"doc-backlink\">\u21A9\uFE0E</a>"
        // /<\/p>([\r\n]*)$/: a closing `</p>` followed by nothing but line breaks
        var end = note.length
        while (end > 0 && (note[end - 1] == '\r' || note[end - 1] == '\n')) {
            end--
        }
        return if (end >= 4 && note.startsWith("</p>", end - 4)) {
            note.substring(0, end - 4) + backlink + "</p>" + note.substring(end)
        } else {
            "$note<p>$backlink</p>\n"
        }
    }

    // Render `node` and its descendants.  The walk keeps its own stack of
    // frames instead of recursing, so the only limit on nesting depth is
    // available heap, not the call stack.  `applyOverride` is false when
    // the caller has already committed to the default handler for `node`
    // itself; overrides still apply to its descendants.
    private fun renderNested(
        node: AstNode,
        applyOverride: Boolean,
    ): String {
        val result = StringBuilder()
        // `stack` holds frames for depths 0..depth-1, and keeps the frames
        // above `depth` around for reuse.
        val stack = ArrayList<RenderFrame>()
        val overrides = options.overrides
        // the table to consult for the node being entered: null both when
        // there are no overrides at all and, for the root only, when the
        // caller has already committed to the default handler
        var lookup = if (applyOverride) overrides else null
        var depth = 0
        var current: AstNode? = node
        while (true) {
            if (current != null) {
                val override = lookup?.get(current.tag)
                lookup = overrides
                if (override != null) {
                    // An override renders its own subtree, so it may recurse.
                    result.append(override(current, this))
                } else {
                    val parts = renderNodeParts(current)
                    result.append(parts.open)
                    val children = parts.children
                    if (children != null) {
                        val tight = parts.tight
                        val frame: RenderFrame
                        if (depth < stack.size) {
                            frame = stack[depth]
                            frame.children = children
                            frame.childIndex = 0
                            frame.close = parts.close
                            frame.oldTight = null
                            frame.footnotes = parts.footnotes
                        } else {
                            frame = RenderFrame(children, 0, parts.close, null, parts.footnotes)
                            stack.add(frame)
                        }
                        depth++
                        if (tight != null) {
                            frame.oldTight = this.tight
                            this.tight = tight
                        }
                    }
                    // a childless node has no `close`: leafParts puts its whole
                    // output in `open`, so there is nothing to emit on the way out
                }
                current = null
            }
            if (depth == 0) {
                return result.toString()
            }
            val frame = stack[depth - 1]
            if (frame.childIndex < frame.children.size) {
                current = frame.children[frame.childIndex]
                frame.childIndex++
            } else {
                depth--
                result.append(frame.close)
                val oldTight = frame.oldTight
                if (oldTight != null) {
                    this.tight = oldTight
                }
                val footnotes = frame.footnotes
                if (footnotes != null && nextFootnoteIndex > 1) {
                    result.append(renderNotes(footnotes))
                }
            }
        }
    }

    /**
     * Renders [node]'s children (overrides apply to each). A list sets the tight flag for them, a
     * block quote or div clears it, other nodes leave it as it is.
     */
    public open fun renderChildren(node: AstNode): String {
        val result = StringBuilder()
        val oldtight = tight
        when (node) {
            is BulletList -> tight = node.tight
            is OrderedList -> tight = node.tight
            is TaskList -> tight = node.tight
            is BlockQuote, is Div -> tight = false
            else -> {}
        }
        for (child in node.childNodes) {
            result.append(renderNested(child, true))
        }
        tight = oldtight
        return result.toString()
    }

    /**
     * Renders [node] as [tag] wrapped around its children, for overrides: [newlines] is 0 for
     * none, 1 for a newline after the closing tag, 2 for one after each tag.
     */
    public open fun inTags(
        tag: String,
        node: AstNode,
        newlines: Int,
        extraAttrs: Map<String, String>? = null,
    ): String {
        val afterOpenSpace = if (newlines >= 2) "\n" else ""
        val afterCloseSpace = if (newlines >= 1) "\n" else ""
        val open = renderTag(tag, node, extraAttrs)
        val children = renderChildren(node)
        return "$open$afterOpenSpace$children${renderCloseTag(tag)}$afterCloseSpace"
    }

    /** Renders [node] and its descendants, applying any override for [node] itself. */
    public open fun renderAstNode(node: AstNode): String = renderNested(node, true)

    /** Renders the endnotes section for the footnotes [notes], numbered as they were referenced. */
    public open fun renderNotes(notes: Map<String, Footnote>): String {
        val result = StringBuilder()
        val renderedNotes = HashMap<String, String>()
        for (k in htmlJsKeyOrder(notes)) {
            renderedNotes[k] = renderChildren(notes.getValue(k))
        }
        // now footnoteIndex includes notes only indexed in other notes (#37)
        val orderedFootnotes = HashMap<Int, String?>()
        var length = 0
        for ((k, index) in footnoteIndex) {
            if (index > 0) {
                orderedFootnotes[index] = renderedNotes[k]
                if (index + 1 > length) length = index + 1
            }
        }
        result.append("<section role=\"doc-endnotes\">\n<hr>\n<ol>\n")
        for (i in 1 until length) {
            // note: there can be gaps in the sequence, so we
            // want to insert a dummy note in that case
            val note = orderedFootnotes[i] ?: ""
            result.append("<li id=\"fn").append(i).append("\">\n")
            result.append(addBacklink(note, i))
            result.append("</li>\n")
        }
        result.append("</ol>\n</section>\n")
        return result.toString()
    }

    /** Renders [node] with the default handler, ignoring any override for its own tag. */
    public open fun renderAstNodeDefault(node: AstNode): String = renderNested(node, false)

    // The text content of `node`, as djot.js's getStringContent: footnote
    // references are left out, breaks become newlines.  Uses an explicit
    // stack, so deeply nested content cannot overflow the call stack.
    private fun getStringContent(node: AstNode): String {
        val buffer = StringBuilder()
        val stack = ArrayList<AstNode>()
        var current = node
        while (true) {
            if (current is FootnoteReference) {
                // excluded from heading IDs
            } else if (current is HasText) {
                buffer.append(current.text)
            } else if (current is SoftBreak || current is HardBreak) {
                buffer.append('\n')
            } else {
                val children = current.childNodes
                if (children.isNotEmpty()) {
                    // descend into the first child directly, stacking only the rest
                    var i = children.size - 1
                    while (i > 0) {
                        stack.add(children[i])
                        i--
                    }
                    current = children[0]
                    continue
                }
            }
            if (stack.isEmpty()) {
                return buffer.toString()
            }
            current = stack.removeAt(stack.size - 1)
        }
    }

    private fun linkParts(
        node: AstNode,
        destination: String?,
        reference: String?,
        isImage: Boolean,
    ): NodeParts {
        val extraAttr = LinkedHashMap<String, String>()
        var dest: String? = destination
        if (!reference.isNullOrEmpty()) {
            val ref = references[reference] ?: autoReferences[reference]
            if (ref != null) {
                dest = ref.destination
                if (isImage) {
                    extraAttr["alt"] = getStringContent(node)
                    extraAttr["src"] = dest
                } else {
                    extraAttr["href"] = dest
                }
                val refAttributes = ref.attributes
                if (refAttributes != null) {
                    for ((k, v) in refAttributes) {
                        if (node.attributes?.get(k).isNullOrEmpty()) {
                            // attribs on link take priority over attribs on reference
                            extraAttr[k] = v
                        }
                    }
                }
                val refAutoAttributes = ref.autoAttributes
                if (refAutoAttributes != null) {
                    for ((k, v) in refAutoAttributes) {
                        if (node.autoAttributes?.get(k).isNullOrEmpty()) {
                            // attribs on link take priority over attribs on reference
                            extraAttr[k] = v
                        }
                    }
                }
            } else {
                val message = "Reference ${htmlJsonStringify(reference)} not found"
                val end = node.pos?.end
                warn.warn(if (end != null) Warning(message, end) else Warning(message))
            }
        } else {
            if (isImage) {
                extraAttr["alt"] = getStringContent(node)
                if (dest != null) {
                    extraAttr["src"] = dest
                }
            } else {
                if (dest != null) {
                    extraAttr["href"] = dest
                }
            }
        }
        return if (isImage) {
            // an image's children only supply the alt text, so they are
            // not rendered as content
            leafParts(renderTag("img", node, extraAttr))
        } else {
            tagParts("a", node, 0, extraAttr)
        }
    }

    // Compute what `node` emits around its children, without rendering
    // them; the walk in renderNested renders the children.
    private fun renderNodeParts(node: AstNode): NodeParts =
        when (node) {
            is Str -> {
                if (node.attributes != null || node.autoAttributes != null) {
                    leafParts("${renderTag("span", node)}${escape(node.text)}</span>")
                } else {
                    leafParts(escape(node.text))
                }
            }

            is SoftBreak -> leafParts("\n")

            is Emph -> tagParts("em", node, 0)

            is Strong -> tagParts("strong", node, 0)

            is Doc -> {
                // the notes section is appended by renderNested on the way out
                val parts = setParts("", "", node.children)
                parts.footnotes = node.footnotes
                parts
            }

            is Para -> {
                if (tight) {
                    setParts("", "\n", node.children)
                } else {
                    tagParts("p", node, 1)
                }
            }

            is BlockQuote -> {
                val parts = tagParts("blockquote", node, 2)
                parts.tight = false
                parts
            }

            is Div -> {
                val parts = tagParts("div", node, 2)
                parts.tight = false
                parts
            }

            is Section -> tagParts("section", node, 2)

            is ListItem -> tagParts("li", node, 2)

            is TaskListItem -> {
                var open = "<li>\n"
                if (node.checkbox == "checked") {
                    open += "<input disabled=\"\" type=\"checkbox\" checked=\"\"/>\n"
                } else {
                    open += "<input disabled=\"\" type=\"checkbox\"/>\n"
                }
                setParts(open, renderCloseTag("li") + "\n", node.children)
            }

            is DefinitionListItem -> setParts("", "", node.childNodes)

            is Definition -> tagParts("dd", node, 2)

            is Term -> tagParts("dt", node, 1)

            is DefinitionList -> tagParts("dl", node, 2)

            // the three list tags are the only ones carrying `tight`, which
            // governs whether their nested paragraphs get <p> tags:
            is BulletList -> {
                val parts = tagParts("ul", node, 2)
                parts.tight = node.tight
                parts
            }

            is TaskList -> {
                val parts = tagParts("ul", node, 2, linkedMapOf("class" to "task-list"))
                parts.tight = node.tight
                parts
            }

            is OrderedList -> {
                val extraAttr = LinkedHashMap<String, String>()
                val start = node.start
                if (start != null && start != 0L && start != 1L) {
                    extraAttr["start"] = start.toString()
                }
                val style = node.style
                if (style.isNotEmpty() && !style.contains('1')) {
                    extraAttr["type"] = style.filter { it != '(' && it != ')' && it != '.' }
                }
                val parts = tagParts("ol", node, 2, extraAttr)
                parts.tight = node.tight
                parts
            }

            is Heading -> {
                val level = node.level
                tagParts(if (level in 1..6) htmlHeadingTags[level - 1] else "h$level", node, 1)
            }

            is FootnoteReference -> {
                val result = StringBuilder()
                val label = node.text
                var index = footnoteIndex[label]
                if (index == null || index == 0) {
                    index = nextFootnoteIndex
                    footnoteIndex[label] = index
                    nextFootnoteIndex++
                }
                val extraAttrs = LinkedHashMap<String, String>()
                if (fnrefIdEmitted[label] != true) {
                    extraAttrs["id"] = "fnref$index"
                    fnrefIdEmitted[label] = true
                }
                extraAttrs["href"] = "#fn$index"
                extraAttrs["role"] = "doc-noteref"
                result.append(renderTag("a", node, extraAttrs))
                result.append("<sup>")
                result.append(escape(index.toString()))
                result.append("</sup></a>")
                leafParts(result.toString())
            }

            is Table -> tagParts("table", node, 2)

            is Caption -> {
                // AST always has at least a dummy caption, no
                // need to render that.
                if (node.children.size > 0) {
                    tagParts("caption", node, 1)
                } else {
                    leafParts("")
                }
            }

            is Row -> tagParts("tr", node, 2)

            is Cell -> {
                val cellAttr = LinkedHashMap<String, String>()
                if (node.align.isNotEmpty() && node.align != "default") {
                    cellAttr["style"] = "text-align: ${node.align};"
                }
                tagParts(if (node.head) "th" else "td", node, 1, cellAttr)
            }

            is ThematicBreak -> leafParts(renderTag("hr", node) + "\n")

            is CodeBlock -> {
                val result = StringBuilder()
                result.append(renderTag("pre", node))
                result.append("<code")
                val lang = node.lang
                if (!lang.isNullOrEmpty()) {
                    result.append(" class=\"language-").append(escapeAttribute(lang)).append('"')
                }
                result.append(">")
                result.append(escape(node.text))
                result.append(renderCloseTag("code"))
                result.append(renderCloseTag("pre"))
                result.append("\n")
                leafParts(result.toString())
            }

            is RawBlock -> leafParts(if (node.format == "html") node.text else "")

            is SmartPunctuation -> {
                val mapped = smartPunctuationMap[node.type]
                leafParts(if (!mapped.isNullOrEmpty()) mapped else node.text)
            }

            is DoubleQuoted -> {
                val left = smartPunctuationMap["left_double_quote"]
                val right = smartPunctuationMap["right_double_quote"]
                setParts(
                    if (!left.isNullOrEmpty()) left else "\"",
                    if (!right.isNullOrEmpty()) right else "\"",
                    node.children,
                )
            }

            is SingleQuoted -> {
                val left = smartPunctuationMap["left_single_quote"]
                val right = smartPunctuationMap["right_single_quote"]
                setParts(
                    if (!left.isNullOrEmpty()) left else "'",
                    if (!right.isNullOrEmpty()) right else "'",
                    node.children,
                )
            }

            is Symb -> leafParts(escape(":${node.alias}:"))

            is InlineMath -> {
                val result = StringBuilder()
                result.append(renderTag("span", node, linkedMapOf("class" to "math inline")))
                result.append("\\(").append(escape(node.text)).append("\\)")
                result.append(renderCloseTag("span"))
                leafParts(result.toString())
            }

            is DisplayMath -> {
                val result = StringBuilder()
                result.append(renderTag("span", node, linkedMapOf("class" to "math display")))
                result.append("\\[").append(escape(node.text)).append("\\]")
                result.append(renderCloseTag("span"))
                leafParts(result.toString())
            }

            is Verbatim -> {
                val result = StringBuilder()
                result.append(renderTag("code", node))
                result.append(escape(node.text))
                result.append(renderCloseTag("code"))
                leafParts(result.toString())
            }

            is RawInline -> leafParts(if (node.format == "html") node.text else "")

            is HardBreak -> leafParts("<br>\n")

            is NonBreakingSpace -> leafParts("&nbsp;")

            is Link -> linkParts(node, node.destination, node.reference, false)

            is Image -> linkParts(node, node.destination, node.reference, true)

            is Url, is Email -> {
                val result = StringBuilder()
                val text = (node as HasText).text
                val extraAttr = LinkedHashMap<String, String>()
                if (node is Email) {
                    extraAttr["href"] = "mailto:$text"
                } else {
                    extraAttr["href"] = text
                }
                result.append(renderTag("a", node, extraAttr))
                result.append(escape(text))
                result.append(renderCloseTag("a"))
                leafParts(result.toString())
            }

            is Span -> tagParts("span", node, 0)

            is Mark -> tagParts("mark", node, 0)

            is Insert -> tagParts("ins", node, 0)

            is Delete -> tagParts("del", node, 0)

            is Superscript -> tagParts("sup", node, 0)

            is Subscript -> tagParts("sub", node, 0)

            else -> leafParts("")
        }

    /** Renders the whole document [doc], resolving links against its references. */
    public open fun render(doc: Doc): String {
        references = doc.references
        autoReferences = doc.autoReferences
        return renderAstNode(doc)
    }
}

/** Renders the djot document [ast] as HTML, as djot.js's `renderHTML` does. */
public fun renderHTML(
    ast: Doc,
    options: HtmlOptions = HtmlOptions(),
): String {
    val renderer = HTMLRenderer(options)
    return renderer.render(ast)
}
