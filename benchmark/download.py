#!/usr/bin/env python3
"""Download a subset of READoc-arXiv: ground-truth Markdown (HuggingFace) + the matching
born-digital PDFs (arXiv). Lightweight — fetches individual files, not the 1.19 GB zip.

    python download.py [N]      # default N=15 documents

READoc (https://huggingface.co/datasets/lazyc/READoc, MIT license) names each GT file by its
arXiv id, e.g. arxiv_ground_truth/0705.4297.md  <->  https://arxiv.org/pdf/0705.4297

The existing fixtures (report.pdf) are left untouched.
"""
import pathlib
import sys
import time

import requests

HERE = pathlib.Path(__file__).resolve().parent
PDFS = HERE / "data" / "pdfs"
GT = HERE / "data" / "gt"
HF_API = "https://huggingface.co/api/datasets/lazyc/READoc/tree/main/arxiv_ground_truth"
HF_RAW = "https://huggingface.co/datasets/lazyc/READoc/resolve/main/arxiv_ground_truth"
UA = {"User-Agent": "liteparse-markdown-benchmark/0.1 (mailto:noreply@example.com)"}


def list_ids(n):
    r = requests.get(HF_API, headers=UA, timeout=60)
    r.raise_for_status()
    ids = [pathlib.Path(e["path"]).stem for e in r.json() if e["path"].endswith(".md")]
    return ids[:n]


def fetch_pdf(arxiv_id, dest):
    for url in (f"https://arxiv.org/pdf/{arxiv_id}", f"https://arxiv.org/pdf/{arxiv_id}.pdf"):
        try:
            r = requests.get(url, headers=UA, timeout=120, allow_redirects=True)
            if r.status_code == 200 and r.content[:5] == b"%PDF-":
                dest.write_bytes(r.content)
                return True
        except requests.RequestException:
            pass
    return False


def main():
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 15
    PDFS.mkdir(parents=True, exist_ok=True)
    GT.mkdir(parents=True, exist_ok=True)

    ids = list_ids(n)
    print(f"Selected {len(ids)} READoc-arXiv documents")
    ok = 0
    for i, aid in enumerate(ids, 1):
        pdf_path = PDFS / f"{aid}.pdf"
        gt_path = GT / f"{aid}.md"
        if pdf_path.exists() and gt_path.exists():
            print(f"[{i}/{len(ids)}] {aid}: already present")
            ok += 1
            continue
        # ground truth
        gr = requests.get(f"{HF_RAW}/{aid}.md", headers=UA, timeout=60)
        if gr.status_code != 200:
            print(f"[{i}/{len(ids)}] {aid}: GT fetch failed ({gr.status_code}) — skip")
            continue
        # pdf (be polite to arXiv)
        if not fetch_pdf(aid, pdf_path):
            print(f"[{i}/{len(ids)}] {aid}: PDF fetch failed — skip")
            continue
        gt_path.write_text(gr.text)
        ok += 1
        print(f"[{i}/{len(ids)}] {aid}: ok ({pdf_path.stat().st_size // 1024} KB pdf)")
        time.sleep(3)  # arXiv rate-limit courtesy

    print(f"\nDownloaded {ok}/{len(ids)} documents into data/pdfs + data/gt")
    if ok < 10:
        print("WARNING: fewer than 10 documents available; re-run or increase N.")


if __name__ == "__main__":
    main()
