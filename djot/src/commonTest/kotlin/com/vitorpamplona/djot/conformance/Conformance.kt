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
package com.vitorpamplona.djot.conformance

import com.vitorpamplona.djot.HtmlOptions
import com.vitorpamplona.djot.ParseOptions
import com.vitorpamplona.djot.parse
import com.vitorpamplona.djot.renderAST
import com.vitorpamplona.djot.renderHTML
import kotlin.test.assertEquals

/**
 * One djot.js conformance case, run as djot.js's `functional.spec.ts` runs it: flag `p` parses
 * with source positions, flag `a` compares the AST dump instead of the HTML, warnings are ignored.
 */
internal fun assertConformance(
    file: String,
    line: Int,
    options: String,
    input: String,
    expected: String,
) {
    val ast = parse(input, ParseOptions(sourcePositions = 'p' in options, warn = {}))
    val result =
        if ('a' in options) {
            renderAST(ast)
        } else {
            renderHTML(ast, HtmlOptions(warn = {}))
        }
    assertEquals(expected, result, "$file, case at line $line")
}
