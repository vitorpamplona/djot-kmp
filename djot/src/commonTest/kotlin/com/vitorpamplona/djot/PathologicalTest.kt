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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

// Ported from djot.js src/pathological.spec.ts (MIT, Copyright (C) 2022 John MacFarlane): inputs
// built to trigger quadratic time or deep recursion, which must parse quickly and without a stack
// overflow. djot-html's HtmlPathologicalTest renders the same inputs. The limit is looser than
// djot.js's 1 s so that the slower targets don't flake; a quadratic case on these sizes takes far
// longer.
class PathologicalTest {
    private val n = 500

    private fun assertFast(input: String) {
        val start = TimeSource.Monotonic.markNow()
        parse(input)
        val elapsed = start.elapsedNow()
        assertTrue(elapsed < 5.seconds, "took $elapsed")
    }

    @Test fun nestedStrongEmph() = assertFast("_a *a ".repeat(65 * n) + "b" + " a* a_".repeat(65 * n))

    @Test fun manyEmphClosersWithNoOpeners() = assertFast("a_ ".repeat(65 * n))

    @Test fun manyEmphOpenersWithNoClosers() = assertFast("_a ".repeat(65 * n))

    @Test fun manyLinkClosersWithNoOpeners() = assertFast("a]".repeat(65 * n))

    @Test fun manyLinkOpenersWithNoClosers() = assertFast("[a".repeat(65 * n))

    @Test fun mismatchedOpenersAndClosers() = assertFast("*a_ ".repeat(50 * n))

    @Test fun issueCmark389() = assertFast("*a ".repeat(20 * n) + "_a*_ ".repeat(20 * n))

    @Test fun openersAndClosersMultipleOf3() = assertFast("a**b" + "8* ".repeat(50 * n))

    @Test fun linkOpenersAndEmphClosers() = assertFast("[ a_".repeat(50 * n))

    @Test fun patternBracketParenRepeated() = assertFast("[ (](".repeat(80 * n))

    @Test fun nestedBrackets() = assertFast("[".repeat(50 * n) + "a" + "]".repeat(50 * n))

    @Test fun nestedBlockQuotes() = assertFast("> ".repeat(50 * n) + "a")

    @Test fun deeplyNestedLists() = assertFast((0 until n).joinToString("") { " ".repeat(it + 1) + "* a\n" })

    @Test fun backticks() = assertFast((0 until 5 * n).joinToString("") { "e" + "`".repeat(it + 1) })

    @Test fun unclosedLinks() = assertFast("[a](<b".repeat(30 * n))

    @Test fun unclosedAttributes() = assertFast("a{#id k=".repeat(30 * n))

    @Test fun unclosedLinkDestinations() = assertFast("[label](blah  ".repeat(30 * n))

    @Test fun unbalancedParenInLinkLabel() = assertFast("[label)](foo ".repeat(30 * n))

    @Test fun balancedNestedParensInDestinations() = assertFast("[label](foo (bar (baz)) ".repeat(30 * n))

    @Test fun footnoteReferenceStarts() = assertFast("[^".repeat(200 * n))

    // djot.js keeps an ordered list's start as a JS number: a long one must not be cut to an Int.
    @Test
    fun longOrderedListStart() {
        val doc = parse("99999999999. a\n")
        assertEquals(99999999999L, (doc.children.single() as OrderedList).start)
    }
}
