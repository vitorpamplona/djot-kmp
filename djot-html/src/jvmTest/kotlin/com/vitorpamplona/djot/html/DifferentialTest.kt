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
package com.vitorpamplona.djot.html

import com.vitorpamplona.djot.ParseOptions
import com.vitorpamplona.djot.WarningHandler
import com.vitorpamplona.djot.parse
import com.vitorpamplona.djot.renderAST
import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.fail

/**
 * Compares this port with djot.js on the cases `tools/differential/generate.mjs` wrote: the HTML,
 * the AST dump, and the AST dump with source positions. Opt-in: runs only with
 * `-Ddjot.diff=<cases.tsv>`, and writes each mismatch to `-Ddjot.diff.out=<file>` when given.
 */
class DifferentialTest {
    @Test
    fun matchesDjotJs() {
        val path = System.getProperty("djot.diff") ?: return
        val decoder = Base64.getDecoder()
        val report = StringBuilder()
        var cases = 0
        var mismatches = 0

        File(path).forEachLine { line ->
            if (line.isEmpty()) return@forEachLine
            val (input, html, ast, astPos, error) = line.split('\t').map { String(decoder.decode(it), Charsets.UTF_8) }
            cases++
            val problems = mutableListOf<String>()
            try {
                val quiet = WarningHandler { }
                val doc = parse(input, ParseOptions(warn = quiet))
                if (error.isNotEmpty()) problems += "djot.js threw \"$error\" but the port did not"
                check("html", html, renderHTML(doc, HtmlOptions(warn = quiet)), problems)
                check("ast", ast, renderAST(doc), problems)
                check("ast with positions", astPos, renderAST(parse(input, ParseOptions(sourcePositions = true, warn = quiet))), problems)
            } catch (e: Throwable) {
                if (error.isEmpty()) problems += "the port threw $e where djot.js did not"
            }
            if (problems.isNotEmpty()) {
                mismatches++
                report.append("=== case $cases\n--- input\n").append(input).append("\n")
                problems.forEach { report.append(it).append("\n") }
            }
        }

        System.getProperty("djot.diff.out")?.let { File(it).writeText(report.toString()) }
        println("djot.diff: $cases cases, $mismatches mismatches")
        if (mismatches > 0) fail("$mismatches of $cases cases differ from djot.js:\n" + report.take(4000))
    }

    private fun check(
        what: String,
        expected: String,
        actual: String,
        problems: MutableList<String>,
    ) {
        if (expected != actual) problems += "--- $what: djot.js\n$expected--- $what: port\n$actual"
    }
}
