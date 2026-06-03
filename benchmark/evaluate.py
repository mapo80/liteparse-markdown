#!/usr/bin/env python3
"""Score predictions/<tool>/<name>.md against data/gt/<name>.md with detailed metrics, and
report timings from predictions/timing.json. Writes results.csv and results.md; prints the report.
"""
import csv
import json
import pathlib
import statistics

from metrics import all_metrics

HERE = pathlib.Path(__file__).resolve().parent
GT_DIR = HERE / "data" / "gt"
PRED = HERE / "predictions"
TOOLS = ["liteparse", "pymupdf4llm"]

# (key, label, higher_is_better)
HEADLINE = [
    ("text_edit_distance", "Text edit dist ↓", False),
    ("text_similarity", "Text similarity ↑", True),
    ("text_token_f1", "Text token-F1 ↑", True),
    ("textnomath_edit_distance", "Text edit dist, no-math ↓", False),
    ("textnomath_token_f1", "Text token-F1, no-math ↑", True),
    ("heading_f1", "Heading F1 ↑", True),
    ("list_f1", "List F1 ↑", True),
    ("table_teds", "Table TEDS ↑", True),
    ("table_teds_struct", "Table TEDS-S ↑", True),
]

# Fixtures (synthetic, exact ground truth); everything else is a real arXiv paper.
FIXTURES = {"report", "prices", "schedule", "measurements"}


def subset_of(doc):
    return "fixtures" if doc in FIXTURES else "arxiv"


def fmt(v):
    return "—" if v is None else f"{v:.3f}"


def mean(vals):
    vals = [v for v in vals if v is not None]
    return statistics.mean(vals) if vals else None


def median(vals):
    vals = [v for v in vals if v is not None]
    return statistics.median(vals) if vals else None


def winner(a, b, hib):
    if a is None or b is None:
        return "—"
    if a == b:
        return "tie"
    return "liteparse" if (a > b) == hib else "pymupdf4llm"


def main():
    docs = sorted(p.stem for p in GT_DIR.glob("*.md"))
    if not docs:
        raise SystemExit("No ground-truth files in data/gt/.")
    timing = json.loads((PRED / "timing.json").read_text()) if (PRED / "timing.json").exists() else {}

    # metrics[doc][tool] = dict
    metrics = {}
    for doc in docs:
        gt = (GT_DIR / f"{doc}.md").read_text()
        metrics[doc] = {}
        for tool in TOOLS:
            pp = PRED / tool / f"{doc}.md"
            metrics[doc][tool] = all_metrics(gt, pp.read_text()) if pp.exists() else None

    out = []
    w = out.append
    w(f"# Benchmark results ({len(docs)} documents — READoc-arXiv + fixtures)\n")
    w("Tools: **liteparse-markdown** vs **pymupdf4llm**. Born-digital PDFs, ground-truth Markdown.\n")

    # --- aggregate (mean + median) ---
    def agg_table(heading, agg, doc_set):
        w(f"## {heading}\n")
        w("| Metric | liteparse-markdown | pymupdf4llm | Winner |")
        w("|--------|-------------------:|------------:|:------:|")
        for key, label, hib in HEADLINE:
            a = agg([metrics[d]["liteparse"][key] for d in doc_set if metrics[d]["liteparse"]])
            b = agg([metrics[d]["pymupdf4llm"][key] for d in doc_set if metrics[d]["pymupdf4llm"]])
            w(f"| {label} | {fmt(a)} | {fmt(b)} | {winner(a, b, hib)} |")
        w("")

    agg_table("Aggregate (mean over documents)", mean, docs)
    agg_table("Aggregate (median over documents)", median, docs)

    # --- per-subset (arXiv papers vs synthetic fixtures), mean ---
    subsets = {}
    for d in docs:
        subsets.setdefault(subset_of(d), []).append(d)
    w("## Per-subset (mean)\n")
    w("| Subset | n | Metric | liteparse | pymupdf4llm | Winner |")
    w("|--------|--:|--------|----------:|------------:|:------:|")
    for sub in sorted(subsets):
        ds = subsets[sub]
        for key, label, hib in HEADLINE:
            a = mean([metrics[d]["liteparse"][key] for d in ds if metrics[d]["liteparse"]])
            b = mean([metrics[d]["pymupdf4llm"][key] for d in ds if metrics[d]["pymupdf4llm"]])
            if a is None and b is None:
                continue
            w(f"| {sub} | {len(ds)} | {label} | {fmt(a)} | {fmt(b)} | {winner(a, b, hib)} |")
    w("")

    # --- win/loss per document on key metrics ---
    w("## Win / loss per document (key metrics)\n")
    keys = [("text_edit_distance", False), ("text_token_f1", True), ("table_teds", True)]
    tally = {"liteparse": 0, "pymupdf4llm": 0, "tie": 0}
    for doc in docs:
        for key, hib in keys:
            la = metrics[doc]["liteparse"] and metrics[doc]["liteparse"][key]
            pa = metrics[doc]["pymupdf4llm"] and metrics[doc]["pymupdf4llm"][key]
            if la is None or pa is None or la == pa:
                tally["tie"] += 1
            elif (la > pa) == hib:
                tally["liteparse"] += 1
            else:
                tally["pymupdf4llm"] += 1
    w(f"Across {len(docs)} docs × {len(keys)} key metrics: "
      f"**liteparse {tally['liteparse']}**, pymupdf4llm {tally['pymupdf4llm']}, tie {tally['tie']}.\n")

    # --- per-document headline ---
    w("## Per-document (text edit dist ↓ | text token-F1 ↑ | table TEDS ↑)\n")
    w("| Document | tool | Text edit ↓ | Token-F1 ↑ | TEDS ↑ |")
    w("|----------|------|------------:|-----------:|-------:|")
    for doc in docs:
        for tool in TOOLS:
            m = metrics[doc][tool]
            if not m:
                w(f"| {doc} | {tool} | — | — | — |")
            else:
                w(f"| {doc} | {tool} | {fmt(m['text_edit_distance'])} | "
                  f"{fmt(m['text_token_f1'])} | {fmt(m['table_teds'])} |")
    w("")

    # --- timing (first document excluded as cold start: JVM/native warmup) ---
    lp = timing.get("liteparse", {})
    pm = timing.get("pymupdf4llm", {})
    cold = docs[0] if docs else None
    timed = docs[1:]  # exclude the cold-start document for BOTH tools (same set)

    def med(vals):
        vals = [v for v in vals if v is not None]
        return statistics.median(vals) if vals else None

    def series(d, key):
        return [d[doc][key] for doc in timed if doc in d]

    w(f"## Timing (ms; first document `{cold}` excluded as cold start, n={len(timed)})\n")
    w("liteparse-java = PDF→ParseResult (native PDFium); liteparse-markdown = ParseResult→Markdown.\n")
    w("| Stat | liteparse-java (parse) | liteparse-markdown (convert) | our total | pymupdf4llm (total) |")
    w("|------|-----------------------:|-----------------------------:|----------:|--------------------:|")
    w(f"| mean | {fmt(mean(series(lp, 'parseMs')))} | {fmt(mean(series(lp, 'markdownMs')))} "
      f"| {fmt(mean(series(lp, 'totalMs')))} | {fmt(mean(series(pm, 'totalMs')))} |")
    w(f"| median | {fmt(med(series(lp, 'parseMs')))} | {fmt(med(series(lp, 'markdownMs')))} "
      f"| {fmt(med(series(lp, 'totalMs')))} | {fmt(med(series(pm, 'totalMs')))} |")
    our_med = med(series(lp, "totalMs"))
    pm_med = med(series(pm, "totalMs"))
    if our_med and pm_med:
        w(f"\nMedian speedup: **~{pm_med / our_med:.0f}×** faster.\n")
    if lp:
        w("### Per-document timing (ms)\n")
        w("| Document | parse (java) | convert (md) | total | pymupdf4llm |")
        w("|----------|-------------:|-------------:|------:|------------:|")
        for doc in docs:
            l = lp.get(doc)
            p = pm.get(doc)
            w(f"| {doc} | {fmt(l['parseMs']) if l else '—'} | {fmt(l['markdownMs']) if l else '—'} | "
              f"{fmt(l['totalMs']) if l else '—'} | {fmt(p['totalMs']) if p else '—'} |")
        w("")

    report = "\n".join(out)
    (HERE / "results.md").write_text(report)
    print(report)

    # --- full CSV ---
    keys_all = sorted({k for d in docs for t in TOOLS if metrics[d][t] for k in metrics[d][t]})
    with open(HERE / "results.csv", "w", newline="") as f:
        cw = csv.writer(f)
        cw.writerow(["document", "tool"] + keys_all + ["parseMs", "markdownMs", "totalMs"])
        for doc in docs:
            for tool in TOOLS:
                m = metrics[doc][tool] or {}
                tinfo = timing.get(tool, {}).get(doc, {})
                cw.writerow([doc, tool] + [m.get(k, "") for k in keys_all]
                            + [tinfo.get("parseMs", ""), tinfo.get("markdownMs", ""),
                               tinfo.get("totalMs", "")])
    print("\n(results.md and results.csv written)")


if __name__ == "__main__":
    main()
