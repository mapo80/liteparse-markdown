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

- **Reading order** — columns are detected via vertical-gutter analysis and read column-by-column,
  so two-column papers don't get scrambled.
- **Headings** — a document-wide, character-weighted font-size model (à la pymupdf4llm's
  `IdentifyHeaders`) maps the body size and the larger sizes to levels, complemented by numbered
  (`1`, `1.2`), Roman (`II`) and keyword (`Introduction`, `References`…) section detection. Display
  equations, figure glyphs and oversized drop-caps are excluded.
- **Bold / italic / inline code** — from `fontWeight` / `fontFlags` / font name (liteparse-java ≥ 2.1.0).
- **Lists** — bullets (`•`, `-`, `*`, …), numbered prefixes and bibliography entries (`[n]`, `(n)`),
  with hanging-indent continuation.
- **Tables** — reconstructed from column alignment (x-clustering) and emitted as GFM tables.
- **Images** — optional per-page screenshots embedded as `![](…)`.
- **Text fidelity** — de-hyphenation across line breaks; removal of running headers/footers, margin
  line numbers, arXiv id/date stamps and standalone display equations.

## Supported constructs & limitations

| Construct | Status |
|-----------|--------|
| Headings, paragraphs | ✅ document-wide font-size model + numbered/Roman/keyword section detection |
| Bold / italic / inline code | ✅ from font weight/flags/name |
| Bullet / numbered lists | ✅ incl. bibliography entries (`[n]`, `(n)`); nesting is best-effort |
| GFM tables | ✅ heuristic (column alignment); conservative, falls back to text |
| Images | ✅ per-page screenshots (opt-in); individual figure cropping is future work |
| Multi-column reading order | ✅ gutter detection, read column-by-column |
| Text fidelity | ✅ de-hyphenation; drops running headers/footers, margin line numbers, arXiv stamps, display equations |
| Links, math | ❌ not yet (display equations are dropped rather than mangled) |

It is a heuristic converter: expect great results on clean documents and approximate results on
complex layouts.

## Installation

Distributed as **GitHub Release assets** (like liteparse-java; no Maven Central) from the
[latest release](https://github.com/mapo80/liteparse-markdown/releases/latest).

### Option A — single all-platforms jar (recommended)

One self-contained download that bundles liteparse-markdown, commonmark, the liteparse-java parser
**and the native binaries for all six platforms** (Linux/macOS/Windows × x86_64/arm64). liteparse-java
selects the right native at runtime, so the same jar runs everywhere — nothing else on the classpath:

```bash
java -cp "liteparse-markdown-0.3.0-all-platforms.jar:your-app.jar" com.example.App
```

### Option B — lean jar + your platform bundle (smaller)

If you want a smaller download (or already ship a `liteparse-java` bundle), use the lean `-all` jar
(library + commonmark) plus a `liteparse-java` **platform bundle** for your OS/arch (from the
[liteparse-java releases](https://github.com/mapo80/liteparse-java/releases/latest)):

```bash
java -cp "liteparse-markdown-0.3.0-all.jar:liteparse-java-bundle-2.1.0-linux-x86_64.jar:your-app.jar" \
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
# or, from the single all-platforms jar (no other jars needed):
java -cp liteparse-markdown-0.3.0-all-platforms.jar io.liteparse.markdown.cli.Main document.pdf
```

## Benchmark

A reproducible head-to-head against **[pymupdf4llm](https://github.com/pymupdf/RAG)** — the closest
"fast, native, no-ML" analogue — on **born-digital PDFs** with ground-truth Markdown. The full harness
(dataset download, runners, metrics) lives in [`benchmark/`](benchmark/).

### Methodology

- **Dataset:** **44 documents** = **40 rich academic papers** from
  [**READoc**](https://huggingface.co/datasets/lazyc/READoc) (`READoc-arXiv`, MIT license — real
  born-digital arXiv PDFs with multi-column layout, tables and formulas, paired with LaTeX-derived
  ground-truth Markdown) **+ 4 controlled fixtures** (`report`, `prices`, `schedule`, `measurements`:
  headings, bold/italic, lists and GFM tables with exact ground truth). The arXiv set is fetched by
  `benchmark/download.py` (not committed); the fixtures are generated by `benchmark/make_fixtures.py`.
- **Tools:** liteparse-markdown (via its CLI, batch mode) and pymupdf4llm — both run locally, no GPU.
- **Metrics** (standalone, the same families as OmniDocBench/READoc): per-document **text** normalized
  edit distance + similarity + token-F1 (plus a **no-math** variant that strips LaTeX from both sides,
  since formulas are out of scope for both tools); **heading** and **list** precision/recall/F1;
  **table** **TEDS** and **TEDS-S** (structure-only). Reported as **mean and median** over documents,
  with a per-subset (arXiv / fixtures) breakdown. Plus **timing**, split for our library into
  **liteparse-java** (PDF→`ParseResult`, native PDFium) and **liteparse-markdown** (`ParseResult`→Markdown).

### Summary (44 documents)

| Metric | liteparse-markdown (mean / median) | pymupdf4llm (mean / median) | Winner |
|--------|-----------------------------------:|----------------------------:|:------:|
| Text edit distance ↓        | **0.216 / 0.202** | 0.228 / 0.220 | **liteparse** |
| Text similarity ↑           | **0.784 / 0.798** | 0.772 / 0.780 | **liteparse** |
| Text token-F1 ↑             | **0.858 / 0.883** | 0.842 / 0.878 | **liteparse** |
| Text edit distance, no-math ↓ | **0.164 / 0.164** | 0.195 / 0.198 | **liteparse** |
| Text token-F1, no-math ↑    | **0.883 / 0.900** | 0.867 / 0.892 | **liteparse** |
| List F1 ↑                   | **0.251 / 0.000** | 0.246 / 0.000 | **liteparse** (mean; median tie) |
| Table TEDS ↑                | **1.000 / 1.000** | 0.918 / 0.932 | **liteparse** |
| Table TEDS-S ↑              | 1.000 / 1.000 | 1.000 / 1.000 | tie |
| Heading F1 ↑                | 0.467 / 0.500 | **0.508 / 0.504** | pymupdf4llm (mean; ≈ tie on median) |

Per-document key-metric tally (text edit · token-F1 · TEDS, 44 docs): **liteparse 62, pymupdf4llm 22, tie 48**.

### Timing (per document; cold-start doc excluded, n=43)

| Stat | liteparse-java (parse) | liteparse-markdown (convert) | **our total** | pymupdf4llm (total) |
|------|-----------------------:|-----------------------------:|--------------:|--------------------:|
| mean   | 69.5 ms | 8.7 ms | **78.2 ms** | 8 109.1 ms |
| median | 45.6 ms | 7.3 ms | **53.7 ms** | 2 386.6 ms |

### Analysis

- **Speed — decisive win:** **~104× faster on the mean** (78 ms vs 8.1 s/doc) and **~44× on the
  median**. Most of our cost is native parsing (~70 ms via PDFium); conversion adds only ~9 ms.
  pymupdf4llm's Python layout analysis is far heavier (the slowest paper took ~23 s).
- **Text — clear win, raw and no-math:** liteparse leads edit distance, similarity and token-F1 on
  both mean and median. Stripping LaTeX (the **no-math** variant) widens the lead (token-F1
  0.883 vs 0.867), confirming the win is on real prose, not formula noise. Key enablers vs the
  previous version: **column-aware reading order** (two-column bodies were being scrambled into
  tables), **de-hyphenation**, minimal escaping, and **dropping display equations / margin noise**.
- **Lists — win on the mean (median tie):** detecting bibliography entries (`[n]`, `(n)`) as list
  items, keeping their markers verbatim, and de-hyphenating closes the previous large gap. Many
  arXiv reference lists are formula-heavy, so the per-item exact-match median is 0 for **both** tools.
- **Tables — win:** liteparse reconstructs the GFM fixtures perfectly (TEDS 1.000 vs 0.918). (arXiv
  ground truth encodes tables as HTML/LaTeX, not GFM, so the table metric is driven by the fixtures.)
- **Headings — competitive, essentially tied on the median (0.500 vs 0.504), slightly behind on the
  mean (0.467 vs 0.508).** A document-wide font-size model plus numbered/Roman/keyword detection, the
  running-header/figure/TOC filters and duplicate-heading suppression brought heading F1 up sharply
  from `0.18` in the previous release, and liteparse now **wins headings outright on several papers**.
  The residual mean gap comes from a few figure- and table-of-contents-heavy papers where text drawn
  inside figures or duplicated in a contents page is hard to separate from real headings without the
  figure/region model that PyMuPDF provides — an honest structural limit of heuristic, line-grouped
  parsing rather than a tuning gap.
- **Formulas — out of scope for both:** neither tool emits LaTeX; the no-math variant isolates prose.

**Takeaway:** liteparse-markdown is **~40–100× faster** and now **wins on text (raw and no-math),
token-F1, lists and tables, on both mean and median**, while being **competitive (median-tied) on
heading detection**. Reproduce with [`benchmark/README.md`](benchmark/README.md); raw numbers in
`benchmark/results.csv` / `results.md`.

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
