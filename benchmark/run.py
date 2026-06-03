#!/usr/bin/env python3
"""Generate Markdown predictions for every PDF in data/pdfs/ with each tool:

  - liteparse-markdown  (our CLI: java -cp <all-jar>:<liteparse-java bundle> ... Main)
  - pymupdf4llm         (Python)

Predictions are written to predictions/<tool>/<name>.md. Then run evaluate.py.

Jar locations default to ../build/libs/*-all.jar and ../libs/liteparse-java.jar; override with
env LITEPARSE_MD_JAR and LITEPARSE_JAVA_BUNDLE.
"""
import glob
import os
import pathlib
import subprocess
import sys

HERE = pathlib.Path(__file__).resolve().parent
PDFS = sorted((HERE / "data" / "pdfs").glob("*.pdf"))
PRED = HERE / "predictions"


def find_jar(env, pattern, label):
    if os.environ.get(env):
        return os.environ[env]
    matches = sorted(glob.glob(str(HERE.parent / pattern)))
    if not matches:
        sys.exit(f"Could not find {label}. Build it (./gradlew build) or set ${env}. "
                 f"Looked for {pattern}")
    return matches[-1]


def run_liteparse(pdf, out):
    jar = find_jar("LITEPARSE_MD_JAR", "build/libs/*-all.jar", "liteparse-markdown -all jar")
    bundle = find_jar("LITEPARSE_JAVA_BUNDLE", "libs/liteparse-java.jar", "liteparse-java bundle")
    cp = os.pathsep.join([jar, bundle])
    res = subprocess.run(
        ["java", "-cp", cp, "io.liteparse.markdown.cli.Main", str(pdf), "--no-ocr"],
        capture_output=True, text=True)
    if res.returncode != 0:
        print(f"  [liteparse] FAILED on {pdf.name}: {res.stderr.strip()[:200]}")
        out.write_text("")
    else:
        out.write_text(res.stdout)


def run_pymupdf4llm(pdf, out):
    import pymupdf4llm
    out.write_text(pymupdf4llm.to_markdown(str(pdf)))


def main():
    if not PDFS:
        sys.exit("No PDFs in data/pdfs/. Run make_fixtures.py or drop born-digital PDFs there.")
    tools = {"liteparse": run_liteparse, "pymupdf4llm": run_pymupdf4llm}
    for tool, fn in tools.items():
        (PRED / tool).mkdir(parents=True, exist_ok=True)
        for pdf in PDFS:
            out = PRED / tool / (pdf.stem + ".md")
            try:
                fn(pdf, out)
                print(f"[{tool}] {pdf.name} -> {out.relative_to(HERE)}")
            except Exception as e:  # noqa: BLE001
                print(f"[{tool}] ERROR on {pdf.name}: {e}")
                out.write_text("")
    print("\nDone. Now run: python evaluate.py")


if __name__ == "__main__":
    main()
