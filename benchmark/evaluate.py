#!/usr/bin/env python3
"""Score predictions/<tool>/<name>.md against data/gt/<name>.md.

Metrics: text normalized edit distance (lower = better) and table TEDS (higher = better).
Writes results.csv and prints a Markdown summary table.
"""
import csv
import pathlib
import statistics

from metrics import normalized_edit_distance, table_score

HERE = pathlib.Path(__file__).resolve().parent
GT_DIR = HERE / "data" / "gt"
PRED = HERE / "predictions"
TOOLS = ["liteparse", "pymupdf4llm"]


def main():
    gts = sorted(GT_DIR.glob("*.md"))
    if not gts:
        raise SystemExit("No ground-truth files in data/gt/.")

    rows = []
    for gt_path in gts:
        name = gt_path.stem
        gt = gt_path.read_text()
        for tool in TOOLS:
            pred_path = PRED / tool / (name + ".md")
            if not pred_path.exists():
                rows.append((name, tool, None, None, "missing prediction"))
                continue
            pred = pred_path.read_text()
            ned = normalized_edit_distance(gt, pred)
            teds = table_score(gt, pred)
            rows.append((name, tool, ned, teds, ""))

    with open(HERE / "results.csv", "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["document", "tool", "text_edit_distance", "table_teds", "note"])
        for r in rows:
            w.writerow([r[0], r[1],
                        "" if r[2] is None else f"{r[2]:.4f}",
                        "" if r[3] is None else f"{r[3]:.4f}", r[4]])

    # Per-document table
    print("\n## Per-document results\n")
    print("| Document | Tool | Text edit dist ↓ | Table TEDS ↑ |")
    print("|----------|------|------------------|--------------|")
    for name, tool, ned, teds, note in rows:
        n = "n/a" if ned is None else f"{ned:.3f}"
        t = "—" if teds is None else f"{teds:.3f}"
        print(f"| {name} | {tool} | {n} | {t} |{' ' + note if note else ''}")

    # Averages per tool
    print("\n## Averages\n")
    print("| Tool | Avg text edit dist ↓ | Avg table TEDS ↑ |")
    print("|------|----------------------|------------------|")
    for tool in TOOLS:
        neds = [r[2] for r in rows if r[1] == tool and r[2] is not None]
        tedss = [r[3] for r in rows if r[1] == tool and r[3] is not None]
        an = f"{statistics.mean(neds):.3f}" if neds else "n/a"
        at = f"{statistics.mean(tedss):.3f}" if tedss else "—"
        print(f"| {tool} | {an} | {at} |")
    print("\n(results.csv written)")


if __name__ == "__main__":
    main()
