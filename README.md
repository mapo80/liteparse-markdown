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

A reproducible head-to-head against **[pymupdf4llm](https://github.com/pymupdf/RAG)** — the closest
"fast, native, no-ML" analogue — on **born-digital PDFs** with ground-truth Markdown. The full harness
(dataset download, runners, metrics) lives in [`benchmark/`](benchmark/).

### Methodology

- **Dataset:** **14 documents** = **13 rich academic papers** from
  [**READoc**](https://huggingface.co/datasets/lazyc/READoc) (`READoc-arXiv`, MIT license — real
  born-digital arXiv PDFs with multi-column layout, tables and formulas, paired with LaTeX-derived
  ground-truth Markdown) **+ 1 controlled fixture** (`report.pdf`: headings, bold/italic, a list, a
  3-column table, with exact ground truth). The arXiv set is fetched by `benchmark/download.py`
  (not committed).
- **Tools:** liteparse-markdown (via its CLI, batch mode) and pymupdf4llm — both run locally, no GPU.
- **Metrics** (standalone, the same families as OmniDocBench/READoc): per-document **text** normalized
  edit distance + similarity + token-F1; **heading** and **list** precision/recall/F1; **table**
  **TEDS** and **TEDS-S** (structure-only). Plus **timing**, split for our library into
  **liteparse-java** (PDF→`ParseResult`, native PDFium) and **liteparse-markdown** (`ParseResult`→Markdown).

### Summary (mean over the 14 documents)

| Metric | liteparse-markdown | pymupdf4llm | Winner |
|--------|-------------------:|------------:|:------:|
| Text edit distance ↓ | 0.281 | **0.242** | pymupdf4llm |
| Text similarity ↑ | 0.719 | **0.758** | pymupdf4llm |
| Text token-F1 ↑ | 0.810 | **0.816** | ≈ tie |
| Heading F1 ↑ | 0.182 | **0.561** | pymupdf4llm |
| List F1 ↑ | 0.071 | **0.344** | pymupdf4llm |
| Table TEDS ↑ | **1.000** | 0.867 | liteparse* |
| Table TEDS-S ↑ | 1.000 | 1.000 | tie |

<sub>*Only the `report` fixture has GFM-pipe tables in its ground truth; READoc encodes arXiv tables as
HTML/LaTeX (not GFM), so the table metric here effectively reflects the controlled fixture.</sub>

### Timing (mean per document)

| liteparse-java (parse) | liteparse-markdown (convert) | **our total** | pymupdf4llm (total) |
|-----------------------:|-----------------------------:|--------------:|--------------------:|
| 138.1 ms | 21.4 ms | **159.5 ms** | 11 165.5 ms |

Per-document, our total ranges **2.7–425 ms** vs pymupdf4llm **0.43–78.3 s**.

### Analysis

- **Speed — decisive win:** liteparse-markdown is on average **~70× faster** (≈160 ms vs ≈11 s/doc).
  The split shows the cost is mostly native parsing (~138 ms via PDFium) with conversion adding only
  ~21 ms; pymupdf4llm's Python layout analysis is far heavier (one paper took 78 s).
- **Text content — near parity:** token-F1 is essentially tied (0.810 vs 0.816); pymupdf4llm is
  marginally ahead on edit distance/similarity. On clean text both extract the body faithfully.
- **Structure (headings/lists) — pymupdf4llm ahead:** our height-based heuristics under-detect section
  headings and list items in dense two-column academic papers (Heading F1 0.18, List F1 0.07). This is
  our clearest area to improve (font-flag/indent cues, column-aware reading order).
- **Tables:** on the controlled fixture liteparse reconstructs the GFM table perfectly (TEDS 1.000 vs
  0.867); broader table evaluation needs ground truth in GFM form (out of scope for the arXiv set).
- **Formulas:** out of scope — neither tool emits LaTeX math, which depresses raw-text scores on
  formula-heavy arXiv papers for **both** tools equally.

**Takeaway:** for born-digital documents where **speed and clean text** matter, liteparse-markdown is
extremely fast and competitive on text; for **rich academic structure** (headings/lists in complex
layouts) pymupdf4llm currently leads — improving the structure heuristics is the priority. Reproduce
with [`benchmark/README.md`](benchmark/README.md); raw numbers in `benchmark/results.csv`.

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
