# Benchmark

Compares **liteparse-markdown** against **[pymupdf4llm](https://github.com/pymupdf/RAG)** on
**born-digital PDFs** (PDFs with a real text layer — the regime both tools target; neither does OCR
here) using ground-truth Markdown.

> Why not OmniDocBench? It ships only page **images** (no source PDFs); pymupdf4llm reads a PDF text
> layer and cannot process images. So for a fair head-to-head we use born-digital PDFs with our own
> ground truth and compute the **same metrics** OmniDocBench reports — normalized **edit distance**
> (text) and **TEDS** (tables) — standalone in [`metrics.py`](metrics.py).

## Dataset

`data/pdfs/*.pdf` are born-digital fixtures with matching ground truth in `data/gt/*.md`. The seed
fixture `report.pdf` (headings, bold/italic, a bullet list, a 3-column table) is generated with
known content by [`make_fixtures.py`](make_fixtures.py), so its ground truth is unambiguous. Add more
documents by dropping a `<name>.pdf` in `data/pdfs/` and a `<name>.md` in `data/gt/`.

## Run

```bash
cd benchmark
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt

# (optional) regenerate the born-digital fixtures
python make_fixtures.py

# build the library first so the CLI jar exists:
#   (from repo root)  ./gradlew build
# and have a liteparse-java bundle at ../libs/liteparse-java.jar (or set env vars)

python run.py        # generates predictions/<tool>/<name>.md for both tools
python evaluate.py   # prints metrics + writes results.csv
```

Jar locations default to `../build/libs/*-all.jar` and `../libs/liteparse-java.jar`; override with
`LITEPARSE_MD_JAR` and `LITEPARSE_JAVA_BUNDLE`.

## Metrics

| Metric | Meaning | Direction |
|--------|---------|-----------|
| Text edit distance | normalized Levenshtein on the de-formatted text | lower = better |
| Table TEDS | Tree-Edit-Distance-based Similarity on GFM tables | higher = better |

## Interpreting results

liteparse-markdown is a **deterministic, local, no-ML** converter. Expect it to be competitive with
pymupdf4llm on clean born-digital documents (headings, lists, tables), and to differ on ambiguous
layout heuristics (e.g. heading levels). For scanned/image documents liteparse can still run (via
OCR) whereas pymupdf4llm cannot — that regime is out of scope for this head-to-head.

This is a **seed** harness: add representative documents to grow it.
