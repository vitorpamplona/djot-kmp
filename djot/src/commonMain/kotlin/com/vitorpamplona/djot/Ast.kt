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
 * The Djot abstract syntax tree, ported from djot.js's ast.ts (MIT, Copyright (C) 2022 John
 * MacFarlane): one class per node tag, with the same field names. Nodes are mutable so the parser
 * can build them in place, as djot.js does.
 */

/** A node's attributes, in the order they were written. */
public typealias Attributes = LinkedHashMap<String, String>

/** A position in the source: 1-based line and column, 0-based offset in UTF-16 units. */
public data class SourceLoc(
    val line: Int,
    val col: Int,
    val offset: Int,
)

/** The source span a node came from, when parsed with `sourcePositions`. */
public data class Pos(
    val start: SourceLoc,
    val end: SourceLoc,
)

/** Every node: its tag (`"para"`, `"emph"`, ...), attributes and source span. */
public sealed class AstNode(
    public val tag: String,
) {
    public var attributes: Attributes? = null
    public var autoAttributes: Attributes? = null
    public var pos: Pos? = null
}

/** A node with child nodes of type [T]. */
public interface HasChildren<T : AstNode> {
    public val children: MutableList<T>
}

/** A node whose content is a string. */
public interface HasText {
    public val text: String
}

/** A block-level node: what a document, a section or a list item holds. */
public sealed class Block(
    tag: String,
) : AstNode(tag)

/** An inline node: what a paragraph, a heading or a table cell holds. */
public sealed class Inline(
    tag: String,
) : AstNode(tag)

/** What a table holds: its caption first, then its rows. */
public sealed interface TableChild

/** What a definition list item holds: its term, then its definition. */
public sealed interface DefinitionListItemChild

// Blocks

public class Para(
    override val children: MutableList<Inline> = mutableListOf(),
) : Block("para"),
    HasChildren<Inline>

public class Heading(
    public var level: Int,
    override val children: MutableList<Inline> = mutableListOf(),
) : Block("heading"),
    HasChildren<Inline>

public class ThematicBreak : Block("thematic_break")

public class Section(
    override val children: MutableList<Block> = mutableListOf(),
) : Block("section"),
    HasChildren<Block>

public class Div(
    override val children: MutableList<Block> = mutableListOf(),
) : Block("div"),
    HasChildren<Block>

public class BlockQuote(
    override val children: MutableList<Block> = mutableListOf(),
) : Block("block_quote"),
    HasChildren<Block>

public class CodeBlock(
    public var lang: String? = null,
    override var text: String = "",
) : Block("code_block"),
    HasText

public class RawBlock(
    public var format: String,
    override var text: String = "",
) : Block("raw_block"),
    HasText

/** A bullet list; [style] is `"-"`, `"+"` or `"*"`. */
public class BulletList(
    public var style: String,
    public var tight: Boolean,
    override val children: MutableList<ListItem> = mutableListOf(),
) : Block("bullet_list"),
    HasChildren<ListItem>

/**
 * An ordered list; [style] is the marker with `1`, `a`, `A`, `i` or `I` for the number and its
 * punctuation: `"1."`, `"a)"`, `"(I)"` and so on.
 */
public class OrderedList(
    public var style: String,
    public var tight: Boolean,
    public var start: Long? = null,
    override val children: MutableList<ListItem> = mutableListOf(),
) : Block("ordered_list"),
    HasChildren<ListItem>

public class TaskList(
    public var tight: Boolean,
    override val children: MutableList<TaskListItem> = mutableListOf(),
) : Block("task_list"),
    HasChildren<TaskListItem>

public class DefinitionList(
    override val children: MutableList<DefinitionListItem> = mutableListOf(),
) : Block("definition_list"),
    HasChildren<DefinitionListItem>

/** A table: its first child is its [Caption], the rest are [Row]s. */
public class Table(
    public val children: MutableList<TableChild> = mutableListOf(),
) : Block("table")

// Parts of blocks

public class ListItem(
    override val children: MutableList<Block> = mutableListOf(),
) : AstNode("list_item"),
    HasChildren<Block>

/** A task list item; [checkbox] is `"checked"` or `"unchecked"`. */
public class TaskListItem(
    public var checkbox: String,
    override val children: MutableList<Block> = mutableListOf(),
) : AstNode("task_list_item"),
    HasChildren<Block>

/** A definition list item: a [Term], then a [Definition]. */
public class DefinitionListItem(
    public val children: MutableList<DefinitionListItemChild> = mutableListOf(),
) : AstNode("definition_list_item")

public class Term(
    override val children: MutableList<Inline> = mutableListOf(),
) : AstNode("term"),
    HasChildren<Inline>,
    DefinitionListItemChild

public class Definition(
    override val children: MutableList<Block> = mutableListOf(),
) : AstNode("definition"),
    HasChildren<Block>,
    DefinitionListItemChild

public class Caption(
    override val children: MutableList<Inline> = mutableListOf(),
) : AstNode("caption"),
    HasChildren<Inline>,
    TableChild

public class Row(
    public var head: Boolean,
    override val children: MutableList<Cell> = mutableListOf(),
) : AstNode("row"),
    HasChildren<Cell>,
    TableChild

/** A table cell; [align] is `"default"`, `"left"`, `"right"` or `"center"`. */
public class Cell(
    public var head: Boolean,
    public var align: String,
    override val children: MutableList<Inline> = mutableListOf(),
) : AstNode("cell"),
    HasChildren<Inline>

/** A link reference definition: `[label]: destination`. */
public class Reference(
    public var label: String,
    public var destination: String,
) : AstNode("reference")

/** A footnote's content: `[^label]: ...`. */
public class Footnote(
    public var label: String,
    override val children: MutableList<Block> = mutableListOf(),
) : AstNode("footnote"),
    HasChildren<Block>

/** The whole document, with its link references and footnotes by label. */
public class Doc(
    override val children: MutableList<Block> = mutableListOf(),
    public val references: LinkedHashMap<String, Reference> = LinkedHashMap(),
    public val autoReferences: LinkedHashMap<String, Reference> = LinkedHashMap(),
    public val footnotes: LinkedHashMap<String, Footnote> = LinkedHashMap(),
) : AstNode("doc"),
    HasChildren<Block>

// Inlines

public class Str(
    override var text: String,
) : Inline("str"),
    HasText

public class SoftBreak : Inline("soft_break")

public class HardBreak : Inline("hard_break")

public class NonBreakingSpace : Inline("non_breaking_space")

/** A symbol: `:alias:`. */
public class Symb(
    public var alias: String,
) : Inline("symb")

public class Verbatim(
    override var text: String,
) : Inline("verbatim"),
    HasText

public class RawInline(
    public var format: String,
    override var text: String,
) : Inline("raw_inline"),
    HasText

public class InlineMath(
    override var text: String,
) : Inline("inline_math"),
    HasText

public class DisplayMath(
    override var text: String,
) : Inline("display_math"),
    HasText

public class Url(
    override var text: String,
) : Inline("url"),
    HasText

public class Email(
    override var text: String,
) : Inline("email"),
    HasText

public class FootnoteReference(
    override var text: String,
) : Inline("footnote_reference"),
    HasText

/**
 * Smart punctuation; [type] is `"left_single_quote"`, `"right_single_quote"`,
 * `"left_double_quote"`, `"right_double_quote"`, `"ellipses"`, `"em_dash"` or `"en_dash"`.
 */
public class SmartPunctuation(
    public var type: String,
    override var text: String,
) : Inline("smart_punctuation"),
    HasText

public class Emph(
    override val children: MutableList<Inline> = mutableListOf(),
) : Inline("emph"),
    HasChildren<Inline>

public class Strong(
    override val children: MutableList<Inline> = mutableListOf(),
) : Inline("strong"),
    HasChildren<Inline>

/** A link to [destination], or to the reference named [reference]. */
public class Link(
    public var destination: String? = null,
    public var reference: String? = null,
    override val children: MutableList<Inline> = mutableListOf(),
) : Inline("link"),
    HasChildren<Inline>

/** An image from [destination], or from the reference named [reference]. */
public class Image(
    public var destination: String? = null,
    public var reference: String? = null,
    override val children: MutableList<Inline> = mutableListOf(),
) : Inline("image"),
    HasChildren<Inline>

public class Span(
    override val children: MutableList<Inline> = mutableListOf(),
) : Inline("span"),
    HasChildren<Inline>

public class Mark(
    override val children: MutableList<Inline> = mutableListOf(),
) : Inline("mark"),
    HasChildren<Inline>

public class Superscript(
    override val children: MutableList<Inline> = mutableListOf(),
) : Inline("superscript"),
    HasChildren<Inline>

public class Subscript(
    override val children: MutableList<Inline> = mutableListOf(),
) : Inline("subscript"),
    HasChildren<Inline>

public class Insert(
    override val children: MutableList<Inline> = mutableListOf(),
) : Inline("insert"),
    HasChildren<Inline>

public class Delete(
    override val children: MutableList<Inline> = mutableListOf(),
) : Inline("delete"),
    HasChildren<Inline>

public class DoubleQuoted(
    override val children: MutableList<Inline> = mutableListOf(),
) : Inline("double_quoted"),
    HasChildren<Inline>

public class SingleQuoted(
    override val children: MutableList<Inline> = mutableListOf(),
) : Inline("single_quoted"),
    HasChildren<Inline>

/** The node's children of any type, or an empty list for a node that has none. */
public val AstNode.childNodes: List<AstNode>
    get() =
        when (this) {
            is HasChildren<*> -> children
            is Table -> children.map { it as AstNode }
            is DefinitionListItem -> children.map { it as AstNode }
            else -> emptyList()
        }
