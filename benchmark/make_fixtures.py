#!/usr/bin/env python3
"""Generate born-digital PDF fixtures (with a real text layer) plus their exact ground-truth
Markdown. Content is fully controlled here, so the ground truth is unambiguous — these give the
table (TEDS) and list/heading metrics solid, deterministic signal alongside the real arXiv papers.

    python make_fixtures.py

Writes data/pdfs/<name>.pdf and data/gt/<name>.md for each fixture (the .md files are committed).
"""
import pathlib

from reportlab.lib import colors
from reportlab.lib.pagesizes import LETTER
from reportlab.lib.styles import ParagraphStyle
from reportlab.platypus import (ListFlowable, ListItem, Paragraph, SimpleDocTemplate,
                                Spacer, Table, TableStyle)

HERE = pathlib.Path(__file__).resolve().parent
PDFS = HERE / "data" / "pdfs"
GT = HERE / "data" / "gt"
PDFS.mkdir(parents=True, exist_ok=True)
GT.mkdir(parents=True, exist_ok=True)

title = ParagraphStyle("title", fontName="Helvetica-Bold", fontSize=24, leading=28, spaceAfter=14)
h2 = ParagraphStyle("h2", fontName="Helvetica-Bold", fontSize=17, leading=20, spaceBefore=12, spaceAfter=8)
body = ParagraphStyle("body", fontName="Helvetica", fontSize=11, leading=15, spaceAfter=8)

GRID = TableStyle([
    ("GRID", (0, 0), (-1, -1), 0.5, colors.black),
    ("FONTNAME", (0, 0), (-1, 0), "Helvetica-Bold"),
    ("FONTSIZE", (0, 0), (-1, -1), 11),
    ("LEFTPADDING", (0, 0), (-1, -1), 10),
    ("RIGHTPADDING", (0, 0), (-1, -1), 10),
    ("TOPPADDING", (0, 0), (-1, -1), 5),
    ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
])


def gfm_table(rows):
    cols = len(rows[0])
    out = ["| " + " | ".join(rows[0]) + " |",
           "| " + " | ".join(["---"] * cols) + " |"]
    for r in rows[1:]:
        out.append("| " + " | ".join(r) + " |")
    return "\n".join(out)


def build(name, story, gt_md, widths):
    doc = SimpleDocTemplate(str(PDFS / f"{name}.pdf"), pagesize=LETTER,
                            topMargin=54, leftMargin=54, rightMargin=54, bottomMargin=54)
    doc.build(story)
    (GT / f"{name}.md").write_text(gt_md.strip() + "\n")
    print("wrote", PDFS / f"{name}.pdf", "+", GT / f"{name}.md")


def report():
    rows = [["Metric", "Q1", "Q2"], ["Revenue", "100", "112"], ["Costs", "80", "76"]]
    story = [
        Paragraph("Quarterly Report", title),
        Paragraph("This report summarizes the results. It contains "
                  "<b>bold</b> and <i>italic</i> text.", body),
        Paragraph("Highlights", h2),
        ListFlowable(
            [ListItem(Paragraph("Revenue grew 12%", body)),
             ListItem(Paragraph("Costs fell 5%", body)),
             ListItem(Paragraph("Margin improved", body))],
            bulletType="bullet", start="bulletchar"),
        Spacer(1, 8),
        Paragraph("Figures", h2),
        Table(rows, colWidths=[160, 90, 90], style=GRID),
    ]
    gt = ("# Quarterly Report\n\n"
          "This report summarizes the results. It contains **bold** and *italic* text.\n\n"
          "## Highlights\n\n"
          "- Revenue grew 12%\n- Costs fell 5%\n- Margin improved\n\n"
          "## Figures\n\n" + gfm_table(rows))
    build("report", story, gt, None)


def price_list():
    rows = [["Item", "Quantity", "Price"],
            ["Widget", "10", "4.50"],
            ["Gadget", "3", "12.00"],
            ["Gizmo", "7", "8.25"],
            ["Sprocket", "21", "1.10"]]
    story = [
        Paragraph("Price List", title),
        Paragraph("The catalogue below lists current stock and unit prices.", body),
        Spacer(1, 8),
        Table(rows, colWidths=[150, 110, 90], style=GRID),
    ]
    gt = ("# Price List\n\n"
          "The catalogue below lists current stock and unit prices.\n\n" + gfm_table(rows))
    build("prices", story, gt, None)


def schedule():
    rows = [["Day", "Session", "Room"],
            ["Monday", "Kickoff", "A1"],
            ["Tuesday", "Design Review", "B2"],
            ["Wednesday", "Implementation", "A1"],
            ["Thursday", "Retrospective", "C3"]]
    story = [
        Paragraph("Weekly Schedule", title),
        Paragraph("Conference Plan", h2),
        Paragraph("All sessions start at 09:00 and run for ninety minutes.", body),
        Spacer(1, 8),
        Table(rows, colWidths=[120, 160, 80], style=GRID),
    ]
    gt = ("# Weekly Schedule\n\n## Conference Plan\n\n"
          "All sessions start at 09:00 and run for ninety minutes.\n\n" + gfm_table(rows))
    build("schedule", story, gt, None)


def measurements():
    rows = [["Sample", "Width", "Height", "Mass"],
            ["Alpha", "12", "8", "340"],
            ["Beta", "15", "9", "410"],
            ["Gamma", "11", "7", "295"],
            ["Delta", "18", "10", "520"]]
    story = [
        Paragraph("Measurements", title),
        Paragraph("Results", h2),
        Paragraph("The following table reports the measured dimensions of each sample.", body),
        Spacer(1, 8),
        Table(rows, colWidths=[110, 80, 80, 80], style=GRID),
    ]
    gt = ("# Measurements\n\n## Results\n\n"
          "The following table reports the measured dimensions of each sample.\n\n"
          + gfm_table(rows))
    build("measurements", story, gt, None)


def main():
    report()
    price_list()
    schedule()
    measurements()


if __name__ == "__main__":
    main()
