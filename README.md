# LiteParse Markdown

[![CI](https://github.com/mapo80/liteparse-markdown/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/mapo80/liteparse-markdown/actions/workflows/ci.yml)
[![Release](https://github.com/mapo80/liteparse-markdown/actions/workflows/release.yml/badge.svg)](https://github.com/mapo80/liteparse-markdown/actions/workflows/release.yml)
[![Latest release](https://img.shields.io/github/v/release/mapo80/liteparse-markdown?sort=semver)](https://github.com/mapo80/liteparse-markdown/releases/latest)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://adoptium.net/)

Generate **Markdown** from PDFs, Office documents and images, on top of
[**liteparse-java**](https://github.com/mapo80/liteparse-java). Fully **local and
deterministic** — no LLM, no cloud: document structure (headings, paragraphs, bold/italic,
lists, tables) is inferred from the layout and font metadata that LiteParse extracts, and the
Markdown is emitted with [commonmark-java](https://github.com/commonmark/commonmark-java).

## How it works

```
document ──(liteparse-java)──> ParseResult (positioned text + font metadata)
         ──(liteparse-markdown)──> heuristics ──> commonmark AST/emit ──> Markdown (GFM)
```

- **Headings** — from the relative text height (robust to PDFs that report a misleading font size).
- **Bold / italic** — from `fontWeight` / `fontFlags` exposed by liteparse-java ≥ 2.1.0.
- **Lists** — bullet (`•`, `-`, `*`, …) and numbered prefixes.
- **Tables** — reconstructed from column alignment (x-clustering) and emitted as GFM tables.
- **Inline code** — from monospaced fonts.
- **Images** — optional per-page screenshots embedded as `![](…)`.
- Markdown escaping and inline emphasis are handled by commonmark-java.

## Supported constructs & limitations

| Construct | Status |
|-----------|--------|
| Headings, paragraphs | ✅ |
| Bold / italic / inline code | ✅ |
| Bullet / numbered lists | ✅ (nesting is best-effort) |
| GFM tables | ✅ heuristic (column alignment); conservative, falls back to text |
| Images | ✅ per-page screenshots (opt-in); individual figure cropping is future work |
| Multi-column reading order | ⚠️ single-column assumed in v1 |
| Links, math | ❌ not yet |

It is a heuristic converter: expect great results on clean documents and approximate results on
complex layouts.

## Installation

Distributed as **GitHub Release assets** (like liteparse-java; no Maven Central). You need two jars
on the classpath:

1. `liteparse-markdown-<version>-all.jar` — this library **with commonmark bundled** (from the
   [latest release](https://github.com/mapo80/liteparse-markdown/releases/latest)).
2. A `liteparse-java` **platform bundle** for your OS/arch (from the
   [liteparse-java releases](https://github.com/mapo80/liteparse-java/releases/latest)) — it provides
   the parser and its native binaries.

```bash
java -cp "liteparse-markdown-0.1.0-all.jar:liteparse-java-bundle-2.1.0-linux-x86_64.jar:your-app.jar" \
    com.example.App
```

## Usage

```java
import io.liteparse.LiteParse;
import io.liteparse.ParseResult;
import io.liteparse.markdown.Markdown;
import io.liteparse.markdown.MarkdownOptions;

try (LiteParse parser = new LiteParse()) {
    ParseResult result = parser.parse("document.pdf");

    // Simplest form:
    String markdown = Markdown.from(result);

    // With options:
    String md2 = Markdown.from(result, MarkdownOptions.builder()
            .detectTables(true)
            .build());

    // With per-page images embedded:
    var shots = parser.screenshot("document.pdf");
    String md3 = Markdown.from(result, shots,
            MarkdownOptions.builder().includeImages(true).imageDir("images").build());
}
```

### CLI

A small CLI is bundled for quick checks:

```bash
./gradlew runCli -PcliArgs="document.pdf"            # prints Markdown
./gradlew runCli -PcliArgs="document.pdf --no-ocr -o out.md"
# or, from the jars:
java -cp "liteparse-markdown-0.1.0-all.jar:liteparse-java-bundle-2.1.0-<classifier>.jar" \
    io.liteparse.markdown.cli.Main document.pdf
```

## Benchmark

[`benchmark/`](benchmark/) compares liteparse-markdown against **pymupdf4llm** on born-digital PDFs
with ground-truth Markdown, using normalized **edit distance** (text) and **TEDS** (tables) — the same
metrics as OmniDocBench, computed standalone (see [benchmark/README.md](benchmark/README.md)).

Seed fixture (`report.pdf`: headings, bold/italic, a list, a 3-column table):

| Tool | Text edit dist ↓ | Table TEDS ↑ |
|------|------------------|--------------|
| **liteparse-markdown** | **0.000** | **1.000** |
| pymupdf4llm | 0.000 | 0.867 |

It is a seed of one controlled document — add representative PDFs to grow it. (OmniDocBench itself ships
only page images, which pymupdf4llm cannot parse; liteparse can, via OCR — see the benchmark README.)

## Building from source

Requires JDK 17+. `liteparse-java` (incl. the native bundle used by the end-to-end test) is
resolved automatically from its GitHub Releases.

```bash
./gradlew build        # compile + tests (+ the -all jar)
```

For local development against an unreleased liteparse-java, drop its bundle jar at
`libs/liteparse-java.jar` (the build uses it instead of resolving from a release).

## License

[Apache-2.0](LICENSE). Built on [liteparse-java](https://github.com/mapo80/liteparse-java),
[LiteParse](https://github.com/run-llama/liteparse) and
[commonmark-java](https://github.com/commonmark/commonmark-java).
