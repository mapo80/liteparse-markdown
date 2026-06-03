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
    ("heading_f1", "Heading F1 ↑", True),
    ("list_f1", "List F1 ↑", True),
    ("table_teds", "Table TEDS ↑", True),
    ("table_teds_struct", "Table TEDS-S ↑", True),
]


def fmt(v):
    return "—" if v is None else f"{v:.3f}"


def mean(vals):
    vals = [v for v in vals if v is not None]
    return statistics.mean(vals) if vals else None


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

    # --- aggregate ---
    w("## Aggregate (mean over documents)\n")
    w("| Metric | liteparse-markdown | pymupdf4llm | Winner |")
    w("|--------|-------------------:|------------:|:------:|")
    for key, label, hib in HEADLINE:
        a = mean([metrics[d]["liteparse"][key] for d in docs if metrics[d]["liteparse"]])
        b = mean([metrics[d]["pymupdf4llm"][key] for d in docs if metrics[d]["pymupdf4llm"]])
        win = "—"
        if a is not None and b is not None:
            win = "liteparse" if ((a > b) == hib and a != b) else ("pymupdf4llm" if a != b else "tie")
        w(f"| {label} | {fmt(a)} | {fmt(b)} | {win} |")
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

    # --- timing ---
    w("## Timing (ms, mean over documents)\n")
    lp = timing.get("liteparse", {})
    pm = timing.get("pymupdf4llm", {})
    parse = mean([v["parseMs"] for v in lp.values()])
    md = mean([v["markdownMs"] for v in lp.values()])
    tot = mean([v["totalMs"] for v in lp.values()])
    pmt = mean([v["totalMs"] for v in pm.values()])
    w("| liteparse-java (parse) | liteparse-markdown (convert) | our total | pymupdf4llm (total) |")
    w("|-----------------------:|-----------------------------:|----------:|--------------------:|")
    w(f"| {fmt(parse)} | {fmt(md)} | {fmt(tot)} | {fmt(pmt)} |")
    w("")
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
