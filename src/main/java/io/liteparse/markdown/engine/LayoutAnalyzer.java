package io.liteparse.markdown.engine;

import io.liteparse.ParseResult;
import io.liteparse.ParsedPage;
import io.liteparse.TextItem;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Groups raw {@link TextItem}s into ordered {@link TextLine}s and estimates the body font size. */
public final class LayoutAnalyzer {

    private LayoutAnalyzer() {}

    // A lone token that, when sitting in the far page margin, is almost certainly a line number,
    // bracket or the arXiv date stamp — not document text. (Removed so it can't prefix a heading
    // or pollute the text, e.g. "27 I. INTRODUCTION" -> "I. INTRODUCTION".)
    private static final Pattern MARGIN_TOKEN = Pattern.compile(
            "(?i)^(\\d{1,4}|[\\[\\]()]|jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[.,]?$");

    /**
     * Group a page's text items into lines in reading order. Columns are detected first
     * (so two-column papers are read column-by-column, not merged across the gutter); within
     * each column items are grouped into lines top-to-bottom, left-to-right.
     */
    public static List<TextLine> lines(ParsedPage page) {
        double leftMargin = page.width() * 0.07;
        double rightMargin = page.width() * 0.93;
        List<TextItem> items = new ArrayList<>();
        for (TextItem it : page.textItems()) {
            if (it.text() == null || it.text().isBlank()) {
                continue;
            }
            if (page.width() > 0 && isMarginToken(it, leftMargin, rightMargin)) {
                continue; // drop margin line numbers / date-stamp fragments
            }
            items.add(it);
        }
        List<double[]> cols = ColumnDetector.columns(items, page.width());
        if (cols.size() == 1) {
            return groupLines(items);
        }
        List<TextLine> out = new ArrayList<>();
        for (double[] col : cols) {
            List<TextItem> colItems = new ArrayList<>();
            for (TextItem it : items) {
                double cx = it.x() + it.width() / 2.0;
                if (cx >= col[0] && cx < col[1]) {
                    colItems.add(it);
                }
            }
            out.addAll(groupLines(colItems));
        }
        return out;
    }

    /**
     * Normalised key for matching running headers/footers across pages: lower-cased, with a leading
     * or trailing page number removed (so "2 David Milovich" and "4 David Milovich" collapse).
     */
    public static String headerKey(String text) {
        String t = text.strip().toLowerCase(Locale.ROOT);
        t = t.replaceFirst("^\\d{1,4}\\s+", "");
        t = t.replaceFirst("\\s+\\d{1,4}$", "");
        return t.replaceAll("\\s+", " ").strip();
    }

    /**
     * Detect running headers/footers: short lines whose {@link #headerKey} recurs on a large share
     * of pages. These are dropped before classification (they would otherwise become false headings
     * or pollute the text — e.g. an author name repeated atop every page).
     */
    public static Set<String> repeatedLineKeys(List<List<TextLine>> perPage) {
        int pages = perPage.size();
        if (pages < 3) {
            return Set.of(); // too few pages to tell a running header from real content
        }
        Map<String, Integer> docFreq = new HashMap<>();
        for (List<TextLine> lines : perPage) {
            Set<String> seen = new HashSet<>();
            for (TextLine line : lines) {
                String t = line.text();
                if (t.length() < 2 || t.length() > 80) {
                    continue;
                }
                String key = headerKey(t);
                if (!key.isEmpty() && seen.add(key)) {
                    docFreq.merge(key, 1, Integer::sum);
                }
            }
        }
        int threshold = Math.max(2, (int) Math.ceil(pages * 0.4));
        Set<String> repeated = new HashSet<>();
        for (Map.Entry<String, Integer> e : docFreq.entrySet()) {
            if (e.getValue() >= threshold) {
                repeated.add(e.getKey());
            }
        }
        return repeated;
    }

    /** A short token (line number / bracket / month) sitting in the left or right page margin. */
    private static boolean isMarginToken(TextItem it, double leftMargin, double rightMargin) {
        double xEnd = it.x() + it.width();
        boolean inMargin = xEnd <= leftMargin || it.x() >= rightMargin;
        return inMargin && MARGIN_TOKEN.matcher(it.text().strip()).matches();
    }

    /** Group items (already within one column) into lines by vertical position. */
    private static List<TextLine> groupLines(List<TextItem> input) {
        List<TextItem> items = new ArrayList<>(input);
        items.sort(Comparator.comparingDouble(TextItem::y).thenComparingDouble(TextItem::x));

        List<TextLine> lines = new ArrayList<>();
        List<TextItem> current = new ArrayList<>();
        double currentTop = Double.NaN;
        double currentH = 0;

        for (TextItem it : items) {
            double tol = Math.max(it.height(), 4.0) * 0.6;
            if (current.isEmpty() || Math.abs(it.y() - currentTop) <= Math.max(tol, currentH * 0.6)) {
                if (current.isEmpty()) {
                    currentTop = it.y();
                    currentH = it.height();
                }
                current.add(it);
            } else {
                lines.add(toLine(current));
                current = new ArrayList<>();
                current.add(it);
                currentTop = it.y();
                currentH = it.height();
            }
        }
        if (!current.isEmpty()) {
            lines.add(toLine(current));
        }
        return lines;
    }

    private static TextLine toLine(List<TextItem> raw) {
        List<TextItem> sorted = new ArrayList<>(raw);
        sorted.sort(Comparator.comparingDouble(TextItem::x));
        double top = sorted.stream().mapToDouble(TextItem::y).min().orElse(0);
        double left = sorted.stream().mapToDouble(TextItem::x).min().orElse(0);
        double fontSize = dominantFontSize(sorted);
        return new TextLine(sorted, top, left, fontSize);
    }

    /**
     * Representative size of the line, weighted by characters. Uses the bounding-box
     * height rather than {@code fontSize}, because {@code fontSize} can be unreliable
     * (e.g. a 1pt font scaled up via the text matrix), while the rendered height is not.
     */
    private static double dominantFontSize(List<TextItem> items) {
        Map<Long, Integer> weight = new HashMap<>();
        double max = 0;
        for (TextItem it : items) {
            double fs = it.height() > 0 ? it.height() : (it.fontSize() != null ? it.fontSize() : 12);
            max = Math.max(max, fs);
            long key = Math.round(fs * 2); // 0.5pt buckets
            int chars = it.text() == null ? 1 : Math.max(1, it.text().strip().length());
            weight.merge(key, chars, Integer::sum);
        }
        long best = weight.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(Math.round(max * 2));
        return best / 2.0;
    }

    /** The document's body (most common) font size, used as the heading baseline. */
    public static double bodyFontSize(ParseResult result) {
        Map<Long, Integer> weight = new HashMap<>();
        for (ParsedPage page : result.pages()) {
            for (TextLine line : lines(page)) {
                long key = Math.round(line.fontSize() * 2);
                int chars = Math.max(1, line.text().length());
                weight.merge(key, chars, Integer::sum);
            }
        }
        return weight.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(e -> e.getKey() / 2.0)
                .orElse(12.0);
    }
}
