# Conformance tests

`djot.js/` holds the test files of [djot.js](https://github.com/jgm/djot.js), the reference Djot
implementation, copied unchanged from its `test/` folder at commit `0043d73` with its MIT license
(`djot.js/LICENSE`, Copyright (C) 2022 John MacFarlane).

Each file is a list of cases: a fence (a run of backticks and optional flags), the Djot input, a
line holding `.` (or `!` before a filter), the expected output, and the closing fence. The flag `a`
compares the AST dump (`renderAST`) instead of the HTML; `p` parses with source positions.

`tools/conformance/generate.py` turns them into Kotlin tests in
`djot/src/commonTest/kotlin/com/vitorpamplona/djot/conformance/`, one `@Test` per case, so the
suite runs on every target. Regenerate after updating the files:

```bash
python3 tools/conformance/generate.py
```

It reads the same files, in the same way, as djot.js's own runner (`src/functional.spec.ts`).
`filters.test` is copied but not generated: its cases run Lua filters, which djot.js's runner
skips too.
