#!/usr/bin/env python3
"""Generate born-digital PDF fixtures (with a real text layer) for the benchmark.

Content is fully controlled here, so the ground-truth Markdown in data/gt/ is
unambiguous. Run once to (re)create data/pdfs/report.pdf:

    python make_fixtures.py
"""
import pathlib

from reportlab.lib import colors
from reportlab.lib.pagesizes import LETTER
from reportlab.lib.styles import ParagraphStyle
from reportlab.platypus import (ListFlowable, ListItem, Paragraph, SimpleDocTemplate,
                                Spacer, Table, TableStyle)

HERE = pathlib.Path(__file__).resolve().parent
OUT = HERE / "data" / "pdfs"
OUT.mkdir(parents=True, exist_ok=True)

title = ParagraphStyle("title", fontName="Helvetica-Bold", fontSize=24, leading=28, spaceAfter=14)
h2 = ParagraphStyle("h2", fontName="Helvetica-Bold", fontSize=17, leading=20, spaceBefore=12, spaceAfter=8)
body = ParagraphStyle("body", fontName="Helvetica", fontSize=11, leading=15, spaceAfter=8)


def main():
    doc = SimpleDocTemplate(str(OUT / "report.pdf"), pagesize=LETTER,
                            topMargin=54, leftMargin=54, rightMargin=54, bottomMargin=54)
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
        Table(
            [["Metric", "Q1", "Q2"],
             ["Revenue", "100", "112"],
             ["Costs", "80", "76"]],
            colWidths=[160, 90, 90],
            style=TableStyle([
                ("GRID", (0, 0), (-1, -1), 0.5, colors.black),
                ("FONTNAME", (0, 0), (-1, 0), "Helvetica-Bold"),
                ("FONTSIZE", (0, 0), (-1, -1), 11),
                ("LEFTPADDING", (0, 0), (-1, -1), 8),
                ("RIGHTPADDING", (0, 0), (-1, -1), 8),
            ])),
    ]
    doc.build(story)
    print("wrote", OUT / "report.pdf")


if __name__ == "__main__":
    main()
