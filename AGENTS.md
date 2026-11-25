# djot-kmp

A Kotlin Multiplatform parser for [Djot](https://djot.net/), the light markup language by John
MacFarlane: Djot text to an AST, and the AST to HTML. A port of
[djot.js](https://github.com/jgm/djot.js), the reference implementation, on the Kotlin stdlib
alone. Published as `com.vitorpamplona.djot:djot`. It started for
[Amethyst](https://github.com/vitorpamplona/amethyst), which renders NIP-54 wiki pages (Djot)
with it, but it knows nothing about Nostr.

## Layout

- `djot/` — the library, in `com.vitorpamplona.djot`. One Kotlin file per djot.js source file,
  with the same names inside:
  - `Ast.kt` (`ast.ts`): the public tree, one class per node tag (`Para`, `Heading`, `Str`,
    `Link`, `Doc`, ...), mutable, with `attributes`, `autoAttributes` and `pos` on `AstNode`.
  - `Event.kt` (`event.ts`, `options.ts`): `Event`, `ParseOptions`, `Warning`, `WarningHandler`.
  - `AttributeParser.kt` (`attributes.ts`), `InlineParser.kt` (`inline.ts`), `BlockParser.kt`
    (`block.ts`, with the public `parseEvents`): the parser, as a stream of events.
  - `Parse.kt` (`parse.ts`): `parse`, `renderAST`, `getStringContent`: events to the tree.
  - `Html.kt` (`html.ts`): `renderHTML`, `HTMLRenderer`.
- `djot/api/` — the public ABI dump (`checkKotlinAbi`).
- `conformance/djot.js/` — djot.js's test files, unchanged, with its license.
  `tools/conformance/generate.py` turns them into `djot/src/commonTest/.../conformance/`, one
  test per case, so the suite runs on every target (see `conformance/README.md`).
- `tools/differential/generate.mjs` and `DifferentialTest` (`jvmTest`) — random documents, plus
  mutations of the conformance inputs, rendered by djot.js and compared with this port (HTML, AST
  dump, AST dump with positions). Opt-in, see "Build and test".
- `PathologicalTest` (`commonTest`) — djot.js's adversarial inputs (deep nesting, long delimiter
  runs), each parsed and rendered under a time limit on every target.
- `build-logic/` — `djot.library`: the targets, JVM 17, the Android SDK, the explicit API and its
  check, publishing. The module's own build file holds only what is its own.
- `tools/agent-hooks/` — the checks Claude Code and Codex run before a push.

**One module, on purpose (decided 2026-11-25).** The parser and the HTML renderer ship together
in `djot`, as djot.js ships them. Splitting would buy nothing today: both need only the stdlib, so
no consumer's classpath gets lighter; R8, the Kotlin/Native linker and the JS and Wasm compilers
drop `renderHTML` from an app that never calls it; and the conformance suite is written in HTML,
so the parser's tests need the renderer anyway. It would cost a second artifact, ABI dump and
release flow. Split when a renderer brings a dependency (a `djot-compose` goes in its own module on
top of `djot`, which stays stdlib-only), or when more of djot.js's renderers are ported (its Djot
writer, its Pandoc conversion): then `djot` keeps the parser and the tree, and each renderer,
`djot-html` included, becomes its own artifact.

## Rules

- **Behave like djot.js.** The conformance suite compares output byte for byte, on every
  target. When djot.js changes, port the change and copy its test files again (record the commit
  in `conformance/README.md`); never edit the copied test files to make a case pass.
- **Keep djot.js's names** (classes, functions, fields, event annotations) and its structure, one
  Kotlin file per TypeScript file, so its future changes can be ported by reading its diff.
- **JavaScript semantics are part of the behavior.** The traps found so far: a number is false
  when it is 0, and `""`, `null` and `undefined` are false too; `charAt` past the end gives `""`;
  `substring` clamps and swaps its arguments; JS `\s` matches Unicode spaces (U+00A0, U+2000–U+200A,
  U+3000 and more) while JVM `\s` does not; `\w` and `\d` are ASCII. Kotlin `Regex` uses each
  platform's own engine, so write such classes out explicitly instead of relying on `\s`, `\w`
  meaning the same everywhere.
- **No dependencies** beyond the Kotlin stdlib (and `kotlin-test` for tests).
- **Every input is hostile.** Djot comes from strangers. Malformed or adversarial input may parse
  oddly but must never throw, overflow the stack or take more than linear-ish time. Keep djot.js's
  explicit stacks where it avoids recursion.
- `commonMain` and `commonTest` stay free of JVM-only APIs: Linux, Apple and the web compile them.
  For `@Volatile` import `kotlin.concurrent.Volatile`; in tests use `assertTrue`, not `assert`.
- **Explicit API mode** is on: every public declaration says `public`, and `djot/api/` records the
  public ABI. Public is what djot.js exports from `index.ts` that is ported: the AST, `parse`,
  `parseEvents`, `renderAST`, `getStringContent`, `renderHTML`, `HTMLRenderer` and their options.
  Everything else is `internal`. A deliberate API change runs `./gradlew updateKotlinAbi` and
  commits the new dump.
- **Credit djot.js.** Each ported file says so in a comment after its `package` line; `NOTICE`
  carries djot.js's license.
- JVM target is 17: a library can't need a newer JVM than the apps that use it.
- Commits: conventional commits (`feat:`, `fix:`, …). Never `--no-verify`.

## Verify, don't guess

Don't assert a diagnosis you haven't reproduced. A behavior question has a cheap answer: write the
Djot input as a test, run it, and compare with djot.js's output (its playground is
<https://djot.net/playground/>, or `npx @djot/djot` on the input).

## Build and test

```bash
./gradlew installGitHook                        # pre-commit (spotless) and pre-push (checks + tests)
./gradlew spotlessApply
./gradlew :djot:jvmTest                         # also `./gradlew test`
./gradlew :djot:jvmTest --tests "com.vitorpamplona.djot.conformance.EmphasisConformanceTest"
./gradlew checkKotlinAbi                        # updateKotlinAbi after a deliberate change
./gradlew checkLinux checkWebNode               # everything the pre-push hook runs
./gradlew checkWeb                              # JS and Wasm in Node.js and headless Chrome
./gradlew checkApple                            # macOS only
python3 tools/conformance/generate.py           # after copying new djot.js test files
# Differential run against djot.js: build a djot.js checkout (`npm ci --ignore-scripts && npx tsc`),
# write cases from it, then compare (the report lists every mismatch):
node tools/differential/generate.mjs <djot.js dir> /tmp/cases.tsv 50000 <seed>
./gradlew :djot:jvmTest --tests '*DifferentialTest*' -Ddjot.diff=/tmp/cases.tsv -Ddjot.diff.out=/tmp/report.txt
```

Run the differential test after any change to the parser or renderer, and after porting a djot.js
change (build djot.js at the matching commit).

The check lists live once, as tasks in the root `build.gradle.kts`: `checkLinux` (formatting, the
API check, the JVM, Android host and Linux tests, the common metadata compile), `checkWeb` /
`checkWebNode` (JS and Wasm) and `checkApple`. The pre-push hook runs `checkLinux checkWebNode`
and the agent-config check; CI's `build` job runs `checkLinux checkWeb` and its `test-apple` job
`checkApple`; the release runs all three.

## Dependency licensing

Before adding a third-party dependency, read its actual `LICENSE`/`COPYING` file or published
POM, not your memory. djot-kmp is MIT and ships inside apps.

- Permissive (MIT, Apache-2.0, BSD, ISC, MPL-2.0, zlib, …): OK.
- LGPL, or GPL/EPL with a linking or Classpath exception: WARN. Acceptable, but say so in your
  summary, and confirm the exception exists in the license text.
- Anything stricter (GPL/AGPL without a linking exception, SSPL, proprietary): STOP. Don't add it;
  surface it and require an explicit call-out in the PR description for a maintainer to decide.

## Kotlin style

- **Never write fully-qualified class names inline in function bodies**: import the class (use
  `import … as Alias` for a real collision). Comments, KDoc and string literals are exempt.
- **Never put raw invisible or bidirectional Unicode characters in source files**: write them as
  `\uXXXX` escapes (U+202A–U+202E, U+2066–U+2069, U+200E/U+200F, U+061C, U+200B–U+200D, U+FEFF,
  U+2060), even when the character is intentional test data. The conformance generator escapes
  every non-ASCII character for this reason. Two traps: the Write and Edit tools can turn a typed
  `\uXXXX` into the raw character, and spotless then deletes a raw U+FEFF outright, which silently
  drops it from a character set (or leaves `''`, which at least fails to compile). After writing
  such code, check with `grep -nP '[^\x00-\x7F]'` and fix at byte level
  (`perl -CSD -pe 's/([^\x00-\x7F])/sprintf("\\u%04X", ord($1))/ge'`).

## Agent configuration (Claude Code + Codex)

Both agents read the same rules and checks, laid out like Amethyst's, Quartz's and kiko-kmp's:

- **This file is the single source of rules.** Codex reads `AGENTS.md`; Claude Code reads
  `.claude/CLAUDE.md`, which imports it with `@../AGENTS.md` and adds only Claude-specific notes.
  Never write a rule in a `CLAUDE.md`.
- **Skills are written once**, in `.claude/skills/<name>/`, and symlinked from
  `.agents/skills/<name>`. Never copy a skill into `.agents/`. None yet.
- **Shared checks live in `tools/agent-hooks/`.** `.claude/settings.json` and `.codex/hooks.json`
  run the same pre-push scripts (spotless, agent config), and CI and the git pre-push hook run
  `agent_config_check.py`.

## Versioning

[Semantic versioning](https://semver.org/) over the public API: every `public` declaration in
`djot/`, in source and binary form. Before 1.0.0 a minor version may break the API; from 1.0.0 on
a breaking change needs a major version, and goes out first as a deprecation in a minor. Amethyst
pins a version, so a breaking change needs a matching change there.

## Pull requests

The repo is on GitHub, `github.com/vitorpamplona/djot-kmp`, which holds the canonical `main`.
Open a PR only when the human you work for asks.

## Releasing

Bump `VERSION_NAME` in `gradle.properties`, then push a `v<version>` tag. The `create-release.yml`
workflow runs the Apple tests on macOS, then tests, publishes to Maven Central and opens the
GitHub release. It needs `SONATYPE_USERNAME`, `SONATYPE_PASSWORD`, `SIGNING_PRIVATE_KEY` and
`SIGNING_PASSWORD`, the same secrets as kiko-kmp, crypto-kmp and Quartz. Its `nostr` job publishes
the same files to Nostr + Blossom with Magpie's `publishToNostr`, independently of Maven Central.
It needs the signing secrets and `MAGPIE_BUNKER`, a NIP-46 `bunker://` URI for the release key
that `vitorpamplona.com/.well-known/nostr.json` names as `releases`. JitPack builds snapshots of
any commit (`jitpack.yml`): the common metadata, JVM and Android.
