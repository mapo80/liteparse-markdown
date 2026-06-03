"""Detailed evaluation metrics for Markdown predictions vs ground truth.

Per dimension:
  - text:    normalized edit distance (lower better) + similarity + token P/R/F1
  - headings: precision/recall/F1 over heading lines
  - lists:    precision/recall/F1 over list-item lines
  - tables:   TEDS (content+structure) and TEDS-S (structure only), both higher better

These are the standard families used by OmniDocBench / READoc; computed standalone here so the
harness works on any born-digital PDF set with our own ground truth.
"""
import re
from collections import Counter

from apted import APTED, Config
from rapidfuzz.distance import Levenshtein as RFLev

_PIPE = re.compile(r"^\s*\|.*\|\s*$")
_SEP = re.compile(r"^\s*\|?\s*:?-{2,}.*$")
_HEADING = re.compile(r"^(#{1,6})\s+(.*)$")
_LIST = re.compile(r"^\s*([-*+]|\d+[.)])\s+(.*)$")


# --------------------------------------------------------------------------- text

def strip_markdown(md: str) -> str:
    out = []
    for line in md.splitlines():
        s = line.strip()
        if not s:
            continue
        if set(s) <= set("|:- ") and "-" in s:
            continue  # table separator / hr
        s = re.sub(r"^#{1,6}\s+", "", s)
        s = re.sub(r"^([-*+]|\d+[.)])\s+", "", s)
        s = s.replace("|", " ")
        s = re.sub(r"[*_`>#]", "", s)
        s = re.sub(r"\\([\\`*_{}\[\]()#+\-.!|])", r"\1", s)
        out.append(s)
    return re.sub(r"\s+", " ", " ".join(out)).strip()


_MATH_SPAN = re.compile(r"\$\$.*?\$\$|\$[^$\n]*\$|\\\(.*?\\\)|\\\[.*?\\\]", re.DOTALL)
_LATEX_CMD = re.compile(r"\\[a-zA-Z]+\*?(?:\{[^{}]*\})*")


def strip_math(md: str) -> str:
    """Remove LaTeX math (``$…$``, ``$$…$$``, ``\\(…\\)``, ``\\[…\\]``) and bare LaTeX commands.
    Formulas are out of scope for both tools, so the no-math text metrics isolate the prose."""
    md = _MATH_SPAN.sub(" ", md)
    md = _LATEX_CMD.sub(" ", md)
    return md


def text_metrics(gt_md: str, pred_md: str) -> dict:
    a, b = strip_markdown(gt_md), strip_markdown(pred_md)
    ned = 0.0 if not a and not b else RFLev.normalized_distance(a, b)
    ta, tb = Counter(a.lower().split()), Counter(b.lower().split())
    inter = sum((ta & tb).values())
    p = inter / max(sum(tb.values()), 1)
    r = inter / max(sum(ta.values()), 1)
    f1 = 0.0 if p + r == 0 else 2 * p * r / (p + r)
    return {"edit_distance": ned, "similarity": 1 - ned,
            "token_precision": p, "token_recall": r, "token_f1": f1}


# ------------------------------------------------------------------- headings / lists

def _norm(s: str) -> str:
    return re.sub(r"[*_`]", "", s).strip().lower()


def _lines(md: str, pattern, group):
    out = []
    for line in md.splitlines():
        m = pattern.match(line)
        if m:
            out.append(_norm(m.group(group)))
    return [x for x in out if x]


def _prf(gt_items, pred_items) -> dict:
    gt, pred = Counter(gt_items), Counter(pred_items)
    inter = sum((gt & pred).values())
    p = inter / max(sum(pred.values()), 1) if pred else (1.0 if not gt else 0.0)
    r = inter / max(sum(gt.values()), 1) if gt else 1.0
    f1 = 0.0 if p + r == 0 else 2 * p * r / (p + r)
    return {"precision": p, "recall": r, "f1": f1, "gt_count": sum(gt.values()),
            "pred_count": sum(pred.values())}


def heading_metrics(gt_md, pred_md):
    return _prf(_lines(gt_md, _HEADING, 2), _lines(pred_md, _HEADING, 2))


def list_metrics(gt_md, pred_md):
    return _prf(_lines(gt_md, _LIST, 2), _lines(pred_md, _LIST, 2))


# -------------------------------------------------------------------------- tables

def extract_tables(md: str):
    tables, cur = [], []
    for line in md.splitlines():
        if _PIPE.match(line):
            cells = [c.strip() for c in line.strip().strip("|").split("|")]
            if cells and all(set(c) <= set(":- ") for c in cells):
                continue
            cur.append(cells)
        else:
            if cur:
                tables.append(cur)
            cur = []
    if cur:
        tables.append(cur)
    return [t for t in tables if t and max(len(r) for r in t) >= 2]


class _Node:
    __slots__ = ("tag", "content", "children")

    def __init__(self, tag, content="", children=None):
        self.tag, self.content, self.children = tag, content, children or []


def _tree(rows):
    return _Node("table", children=[_Node("tr", children=[_Node("td", c) for c in r]) for r in rows])


def _count(n):
    return 1 + sum(_count(c) for c in n.children)


class _TedsConfig(Config):
    def __init__(self, structure_only=False):
        self.structure_only = structure_only

    def children(self, node):
        return node.children

    def rename(self, a, b):
        if a.tag != b.tag:
            return 1.0
        if a.tag == "td" and not self.structure_only:
            if a.content == b.content:
                return 0.0
            return RFLev.normalized_distance(a.content, b.content)
        return 0.0


def _teds(gt_rows, pred_rows, structure_only):
    if not gt_rows and not pred_rows:
        return 1.0
    if not gt_rows or not pred_rows:
        return 0.0
    t1, t2 = _tree(gt_rows), _tree(pred_rows)
    dist = APTED(t1, t2, _TedsConfig(structure_only)).compute_edit_distance()
    return max(0.0, 1.0 - dist / max(_count(t1), _count(t2)))


def table_metrics(gt_md, pred_md):
    gt, pred = extract_tables(gt_md), extract_tables(pred_md)
    if not gt:
        return {"gt_tables": 0, "pred_tables": len(pred), "teds": None, "teds_struct": None}
    teds, teds_s = [], []
    for i, g in enumerate(gt):
        p = pred[i] if i < len(pred) else []
        teds.append(_teds(g, p, False))
        teds_s.append(_teds(g, p, True))
    return {"gt_tables": len(gt), "pred_tables": len(pred),
            "teds": sum(teds) / len(teds), "teds_struct": sum(teds_s) / len(teds_s)}


def all_metrics(gt_md: str, pred_md: str) -> dict:
    m = {}
    m.update({"text_" + k: v for k, v in text_metrics(gt_md, pred_md).items()})
    m.update({"textnomath_" + k: v for k, v in
              text_metrics(strip_math(gt_md), strip_math(pred_md)).items()})
    m.update({"heading_" + k: v for k, v in heading_metrics(gt_md, pred_md).items()})
    m.update({"list_" + k: v for k, v in list_metrics(gt_md, pred_md).items()})
    m.update({"table_" + k: v for k, v in table_metrics(gt_md, pred_md).items()})
    return m
