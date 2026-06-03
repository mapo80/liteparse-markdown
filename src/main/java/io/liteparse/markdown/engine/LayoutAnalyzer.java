package io.liteparse.markdown.engine;

import io.liteparse.ParseResult;
import io.liteparse.ParsedPage;
import io.liteparse.TextItem;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Groups raw {@link TextItem}s into ordered {@link TextLine}s and estimates the body font size. */
public final class LayoutAnalyzer {

    private LayoutAnalyzer() {}

    /** Group a page's text items into lines, ordered top-to-bottom, left-to-right. */
    public static List<TextLine> lines(ParsedPage page) {
        List<TextItem> items = new ArrayList<>();
        for (TextItem it : page.textItems()) {
            if (it.text() != null && !it.text().isBlank()) {
                items.add(it);
            }
        }
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
