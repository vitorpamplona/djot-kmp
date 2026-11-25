// Differential test cases: random Djot documents rendered by djot.js, to compare with this port.
//
//   node tools/differential/generate.mjs <djot.js checkout, built with `npx tsc`> <out.tsv> [count] [seed]
//
// Each output line is one case, five tab-separated base64 (UTF-8) fields: the input, djot.js's
// HTML, its AST dump, its AST dump with source positions, and an error message if djot.js threw
// ("" otherwise). DifferentialTest (jvmTest) reads the file when run with -Ddjot.diff=<out.tsv>.
import { createRequire } from "node:module";
import fs from "node:fs";
import path from "node:path";

const [djotDir, out, countArg, seedArg] = process.argv.slice(2);
if (!djotDir || !out) {
  console.error("usage: generate.mjs <djot.js dir> <out.tsv> [count] [seed]");
  process.exit(2);
}
const require = createRequire(import.meta.url);
const djot = require(path.resolve(djotDir, "lib/index.js"));
const count = Number(countArg || 5000);
let seed = Number(seedArg || 1);

// Small deterministic PRNG (mulberry32), so a seed reproduces a run.
function rand() {
  seed |= 0; seed = (seed + 0x6D2B79F5) | 0;
  let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
  t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
  return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
}
const pick = (a) => a[Math.floor(rand() * a.length)];
const many = (f, max) => { let s = ""; const n = Math.floor(rand() * (max + 1)); for (let i = 0; i < n; i++) s += f(); return s; };

const words = ["foo", "bar", "baz", "x", "1", "42", "2.", "a)", "(i)", "IV.", "café", "naïve", "日本語", "🎉", "a_b", "snake_case", "http", "mailto"];
const spaces = [" ", " ", " ", "  ", "\t", " ", " ", "　", "​", "﻿"];
const inlineBits = [
  "*", "_", "{*", "*}", "{_", "_}", "^", "~", "{=", "=}", "{+", "+}", "{-", "-}", "`", "``", "$`", "$$`",
  "[", "]", "(", ")", "![", "](", "][", "[^1]", "[^note]", "<", ">", "<http://x.y/z>", "<a@b.c>",
  ":smile:", ":+1:", "\\", "\\*", "\\\n", "'", "\"", "...", "--", "---", "{.c}", "{#i}", "{k=v}", "{k=\"a b\"}",
  "{% c %}", "{=html}", "{1=2}", "{.a .b #c}", "\n", "\n\n", "&", "<b>", "{", "}", "|", "#",
];
const blockStarts = [
  "", "# ", "## ", "###### ", "> ", ">", "- ", "* ", "+ ", "1. ", "1) ", "(1) ", "a. ", "B) ", "(iv) ", "I. ",
  "- [ ] ", "- [x] ", ": ", "| ", "^ ", "[^1]: ", "[ref]: ", "[ref]: http://x/", "    ", "{.cls}\n", "{#id k=v}\n",
  "``` ", "```\n", "``` =html\n", "::: ", ":::\n", "::: warning\n", "* * *\n", "---\n", "- - -\n",
];
const inline = () => many(() => pick(rand() < 0.5 ? words : inlineBits) + (rand() < 0.4 ? pick(spaces) : ""), 10);
const line = () => pick(blockStarts) + inline();
const table = () => {
  const cols = 1 + Math.floor(rand() * 3);
  const row = () => "|" + Array.from({ length: cols }, () => " " + inline().replace(/\n/g, " ") + " |").join("") + "\n";
  let t = row();
  if (rand() < 0.6) t += "|" + Array.from({ length: cols }, () => pick(["---", ":--", "--:", ":-:"]) + "|").join("") + "\n";
  t += many(row, 3);
  if (rand() < 0.3) t += "\n^ " + inline() + "\n";
  return t;
};
const doc = () => {
  let d = "";
  const blocks = 1 + Math.floor(rand() * 6);
  for (let i = 0; i < blocks; i++) {
    d += rand() < 0.15 ? table() : many(() => line() + "\n", 4);
    d += pick(["\n", "\n\n", "", "\n   \n"]);
    if (rand() < 0.2) d = d.split("\n").map((l) => pick(["", "", "> ", "  ", "    "]) + l).join("\n");
  }
  return d;
};

// The conformance inputs, mutated: a slice deleted, duplicated or moved.
const seeds = [];
const confDir = path.resolve(path.dirname(new URL(import.meta.url).pathname), "../../conformance/djot.js");
for (const f of fs.readdirSync(confDir).filter((f) => f.endsWith(".test"))) {
  const parts = fs.readFileSync(path.join(confDir, f), "utf8").split(/^`{3,}.*\n/m);
  for (let i = 1; i < parts.length; i += 2) seeds.push(parts[i].split(/^[.!]$/m)[0]);
}
const mutate = (s) => {
  if (s.length === 0) return s;
  const a = Math.floor(rand() * s.length), b = Math.min(s.length, a + Math.floor(rand() * 12));
  switch (Math.floor(rand() * 4)) {
    case 0: return s.slice(0, a) + s.slice(b);
    case 1: return s.slice(0, b) + s.slice(a, b) + s.slice(b);
    case 2: return s.slice(0, a) + pick(inlineBits.concat(spaces, blockStarts)) + s.slice(a);
    default: return s.slice(a, b) + s.slice(0, a) + s.slice(b);
  }
};

const b64 = (s) => Buffer.from(s, "utf8").toString("base64");
const lines = [];
const quiet = { warn: () => {} };
for (let i = 0; i < count; i++) {
  const input = rand() < 0.5 ? doc() : mutate(mutate(pick(seeds)));
  let html = "", ast = "", astPos = "", error = "";
  try {
    const parsed = djot.parse(input, quiet);
    html = djot.renderHTML(parsed, quiet);
    ast = djot.renderAST(parsed);
    astPos = djot.renderAST(djot.parse(input, { sourcePositions: true, warn: () => {} }));
  } catch (e) {
    error = String(e && e.message || e);
  }
  lines.push([input, html, ast, astPos, error].map(b64).join("\t"));
}
fs.writeFileSync(out, lines.join("\n") + "\n");
console.log(`${count} cases written to ${out}`);
