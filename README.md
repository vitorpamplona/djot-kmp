# djot-kmp

A Kotlin Multiplatform parser for [Djot](https://djot.net/), the light markup language by John
MacFarlane (author of Pandoc, co-author of CommonMark): Djot text to an AST, and the AST to HTML,
on the Kotlin stdlib alone.

It is a port of [djot.js](https://github.com/jgm/djot.js), the reference implementation. It passes
djot.js's own conformance suite (317 cases) and pathological-input tests, which run on every target
in CI, and gives djot.js's output on randomly generated documents (`tools/differential/`).

Targets: JVM, Android, iOS, macOS, Linux, JavaScript and Wasm.

Two artifacts:

- `com.vitorpamplona.djot:djot`: the parser and the AST. Depend on this alone to draw the AST
  your own way.
- `com.vitorpamplona.djot:djot-html`: renders the AST to HTML, exactly as djot.js does. Brings
  `djot` with it.

## Use

```kotlin
import com.vitorpamplona.djot.html.renderHTML
import com.vitorpamplona.djot.parse

val doc = parse("Hello _djot_ {.greeting}")
val html = renderHTML(doc)
```

`parse` returns a `Doc`: a typed tree with one class per node (`Para`, `Heading`, `Str`, `Link`,
`Table`, `Footnote`, ...), each with its `attributes` and, when parsed with
`ParseOptions(sourcePositions = true)`, its source `pos`. Walk it to render your own way;
`renderAST` (a readable dump, in `djot`) and `renderHTML` (in `djot-html`) are the renderers included. `parseEvents` gives
the lower-level event stream.

## Status

Not released yet. See `AGENTS.md` for the layout, rules and how to build and test.

## License

MIT (`LICENSE`). The parser is a port of djot.js and the conformance tests are djot.js's own,
both MIT, Copyright (C) 2022 John MacFarlane: see `NOTICE`.
