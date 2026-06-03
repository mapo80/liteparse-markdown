package io.liteparse.markdown.engine;

import io.liteparse.ParseResult;
import io.liteparse.ParsedPage;
import io.liteparse.TextItem;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Document-level heading detector, modelled on pymupdf4llm's {@code IdentifyHeaders}: build a
 * character-weighted histogram of font sizes over the whole document, take the most frequent size
 * as body text, and map the distinct larger sizes (clustered) to heading levels by rank
 * (largest = {@code #}). Uses {@link TextItem#fontSize()} (reliable on born-digital PDFs), falling
 * back to bounding-box height when font size is missing/implausible.
 */
public final class HeadingModel {

    private final double bodySize;
    /** Representative size of each header cluster, descending (index 0 = level 1 = largest). */
    private final List<Double> levels;
    /** Sizes above this are display equations / figure glyphs / drop-caps, not headings. */
    private final double maxHeadingSize;

    private HeadingModel(double bodySize, List<Double> levels) {
        this.bodySize = bodySize;
        this.levels = levels;
        this.maxHeadingSize = bodySize * 2.5 + 0.5;
    }

    /** Reliable size of an item: font size when plausible, else bounding-box height. */
    static double sizeOf(TextItem it) {
        Double fs = it.fontSize();
        if (fs != null && fs > 2.0) {
            return fs;
        }
        return it.height() > 0 ? it.height() : 12.0;
    }

    public double bodySize() {
        return bodySize;
    }

    public static HeadingModel build(ParseResult result) {
        Map<Long, Long> hist = new HashMap<>();
        for (ParsedPage p : result.pages()) {
            for (TextItem it : p.textItems()) {
                String t = it.text();
                if (t == null || t.isBlank()) {
                    continue;
                }
                hist.merge(Math.round(sizeOf(it)), (long) t.strip().length(), Long::sum);
            }
        }
        if (hist.isEmpty()) {
            return new HeadingModel(12.0, List.of());
        }
        double body = hist.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(e -> (double) e.getKey())
                .orElse(12.0);

        // Header candidate sizes = sizes larger than body but not far larger: very large sizes are
        // display equations, figure glyphs or drop-caps, not headings, and would otherwise consume
        // all the level slots (and turn any big figure label into a heading). Cap at 2.5× body.
        double cap = body * 2.5 + 0.5;
        List<Double> sizes = new ArrayList<>();
        for (Map.Entry<Long, Long> e : hist.entrySet()) {
            if (e.getKey() > body + 0.5 && e.getKey() <= cap) {
                sizes.add((double) e.getKey());
            }
        }
        sizes.sort(Comparator.reverseOrder());

        // Merge near-equal sizes (within 1pt) into one level; keep up to 6 levels.
        List<Double> clusters = new ArrayList<>();
        for (double s : sizes) {
            if (clusters.isEmpty() || clusters.get(clusters.size() - 1) - s > 1.0) {
                clusters.add(s);
                if (clusters.size() == 6) {
                    break;
                }
            }
        }
        return new HeadingModel(body, clusters);
    }

    /** Heading level (1..6) for a line's representative size, or 0 if it is body text. */
    public int levelForSize(double size) {
        if (levels.isEmpty() || size <= bodySize + 0.5 || size > maxHeadingSize) {
            return 0; // body text, or an oversized display-equation / figure glyph
        }
        for (int i = 0; i < levels.size(); i++) {
            if (size >= levels.get(i) - 1.0) {
                return i + 1;
            }
        }
        return levels.size(); // smaller than the smallest header size but still > body
    }

    /** Character-weighted representative size of a line. */
    public double lineSize(TextLine line) {
        Map<Long, Long> hist = new HashMap<>();
        for (TextItem it : line.items()) {
            String t = it.text();
            if (t == null || t.isBlank()) {
                continue;
            }
            hist.merge(Math.round(sizeOf(it)), (long) t.strip().length(), Long::sum);
        }
        return hist.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(e -> (double) e.getKey())
                .orElse(bodySize);
    }
}
