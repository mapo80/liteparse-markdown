#!/usr/bin/env python3
"""Generate Markdown predictions + timings for every PDF in data/pdfs/ with each tool.

  - liteparse: ONE JVM in --batch mode (parser/native init amortized + warmup), emitting per-file
    {"file","parseMs","markdownMs"} on stderr. parseMs = liteparse-java; markdownMs = liteparse-markdown.
  - pymupdf4llm: ONE Python process (warmup), timing to_markdown per file.

Writes predictions/<tool>/<name>.md and predictions/timing.json. Then run evaluate.py.

Jars default to ../build/libs/*-all.jar and ../libs/liteparse-java.jar; override with env
LITEPARSE_MD_JAR / LITEPARSE_JAVA_BUNDLE.
"""
import glob
import json
import os
import pathlib
import subprocess
import sys
import time

HERE = pathlib.Path(__file__).resolve().parent
PDFS = sorted((HERE / "data" / "pdfs").glob("*.pdf"))
PRED = HERE / "predictions"


def find_jar(env, pattern, label):
    if os.environ.get(env):
        return os.environ[env]
    matches = sorted(glob.glob(str(HERE.parent / pattern)))
    if not matches:
        sys.exit(f"Could not find {label}. Build it (./gradlew shadowJar) or set ${env}.")
    return matches[-1]


def run_liteparse(timing):
    out = PRED / "liteparse"
    out.mkdir(parents=True, exist_ok=True)
    jar = find_jar("LITEPARSE_MD_JAR", "build/libs/*-all.jar", "liteparse-markdown -all jar")
    bundle = find_jar("LITEPARSE_JAVA_BUNDLE", "libs/liteparse-java.jar", "liteparse-java bundle")
    cp = os.pathsep.join([jar, bundle])
    cmd = ["java", "-cp", cp, "io.liteparse.markdown.cli.Main",
           "--batch", str(out), "--no-ocr", "--timing"] + [str(p) for p in PDFS]
    res = subprocess.run(cmd, capture_output=True, text=True)
    for line in res.stderr.splitlines():
        line = line.strip()
        if line.startswith("{") and "parseMs" in line:
            try:
                rec = json.loads(line)
                timing.setdefault("liteparse", {})[rec["file"]] = {
                    "parseMs": rec["parseMs"], "markdownMs": rec["markdownMs"],
                    "totalMs": rec["parseMs"] + rec["markdownMs"]}
            except json.JSONDecodeError:
                pass
    print(f"[liteparse] {len(timing.get('liteparse', {}))} docs (batch)")


def run_pymupdf4llm(timing):
    import pymupdf4llm
    out = PRED / "pymupdf4llm"
    out.mkdir(parents=True, exist_ok=True)
    if PDFS:
        pymupdf4llm.to_markdown(str(PDFS[0]))  # warmup
    for pdf in PDFS:
        stem = pdf.stem
        t0 = time.perf_counter()
        try:
            md = pymupdf4llm.to_markdown(str(pdf))
        except Exception as e:  # noqa: BLE001
            print(f"[pymupdf4llm] ERROR {stem}: {e}")
            md = ""
        dt = (time.perf_counter() - t0) * 1000.0
        (out / f"{stem}.md").write_text(md)
        timing.setdefault("pymupdf4llm", {})[stem] = {"totalMs": dt}
    print(f"[pymupdf4llm] {len(timing.get('pymupdf4llm', {}))} docs")


def main():
    if not PDFS:
        sys.exit("No PDFs in data/pdfs/. Run download.py / make_fixtures.py first.")
    timing = {}
    run_liteparse(timing)
    run_pymupdf4llm(timing)
    (PRED / "timing.json").write_text(json.dumps(timing, indent=2))
    print(f"\nDone ({len(PDFS)} PDFs). Now run: python evaluate.py")


if __name__ == "__main__":
    main()
