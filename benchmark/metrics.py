"""Standalone evaluation metrics (the same ones OmniDocBench uses):

- text: normalized edit distance (lower = better) on the de-formatted text;
- tables: TEDS (Tree-Edit-Distance-based Similarity, higher = better) on GFM tables.

OmniDocBench's own toolkit is tied to its image dataset + JSON schema, so for a
born-digital PDF set with our own ground truth we compute the equivalent metrics here.
"""
import re

import Levenshtein
from apted import APTED, Config

# --------------------------------------------------------------------------- text

_TABLE_PIPE = re.compile(r"^\s*\|.*\|\s*$")
_TABLE_SEP = re.compile(r"^\s*\|?\s*:?-{2,}.*$")


def strip_markdown(md: str) -> str:
    """Reduce Markdown to plain text for content comparison."""
    out = []
    for line in md.splitlines():
        s = line.strip()
        if not s:
            continue
        if _TABLE_SEP.match(s) and set(s) <= set("|:- "):
            continue
        s = re.sub(r"^#{1,6}\s+", "", s)            # headings
        s = re.sub(r"^([-*+]|\d+[.)])\s+", "", s)   # list markers
        s = s.replace("|", " ")                      # table pipes
        s = re.sub(r"[*_`>#]", "", s)                # emphasis/code/quote
        s = re.sub(r"\\([\\`*_{}\[\]()#+\-.!|])", r"\1", s)  # unescape
        out.append(s)
    return re.sub(r"\s+", " ", " ".join(out)).strip()


def normalized_edit_distance(gt_md: str, pred_md: str) -> float:
    a, b = strip_markdown(gt_md), strip_markdown(pred_md)
    if not a and not b:
        return 0.0
    return Levenshtein.distance(a, b) / max(len(a), len(b), 1)


# -------------------------------------------------------------------------- tables

def extract_tables(md: str):
    """Return a list of tables; each table is a list of rows; each row a list of cells."""
    tables, cur = [], []
    for line in md.splitlines():
        if _TABLE_PIPE.match(line):
            cells = [c.strip() for c in line.strip().strip("|").split("|")]
            if all(set(c) <= set(":- ") for c in cells) and cells:
                continue  # separator row
            cur.append(cells)
        else:
            if len(cur) >= 1:
                tables.append(cur)
            cur = []
    if cur:
        tables.append(cur)
    return [t for t in tables if len(t) >= 1 and max(len(r) for r in t) >= 2]


class _Node:
    __slots__ = ("tag", "content", "children")

    def __init__(self, tag, content="", children=None):
        self.tag = tag
        self.content = content
        self.children = children or []


def _to_tree(rows):
    trs = [_Node("tr", children=[_Node("td", content=c) for c in r]) for r in rows]
    return _Node("table", children=trs)


def _count(node):
    return 1 + sum(_count(c) for c in node.children)


class _TedsConfig(Config):
    def children(self, node):
        return node.children

    def rename(self, n1, n2):
        if n1.tag != n2.tag:
            return 1.0
        if n1.tag == "td":
            a, b = n1.content, n2.content
            if a == b:
                return 0.0
            return Levenshtein.distance(a, b) / max(len(a), len(b), 1)
        return 0.0


def teds(gt_rows, pred_rows) -> float:
    if not gt_rows and not pred_rows:
        return 1.0
    if not gt_rows or not pred_rows:
        return 0.0
    t1, t2 = _to_tree(gt_rows), _to_tree(pred_rows)
    dist = APTED(t1, t2, _TedsConfig()).compute_edit_distance()
    return max(0.0, 1.0 - dist / max(_count(t1), _count(t2)))


def table_score(gt_md: str, pred_md: str):
    """Average TEDS over GT tables, matched to predicted tables by order. None if no GT tables."""
    gt_tables = extract_tables(gt_md)
    pred_tables = extract_tables(pred_md)
    if not gt_tables:
        return None
    scores = []
    for i, gt in enumerate(gt_tables):
        pred = pred_tables[i] if i < len(pred_tables) else []
        scores.append(teds(gt, pred))
    return sum(scores) / len(scores)
