package io.liteparse.markdown.engine;

import io.liteparse.TextItem;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Detects whether a page is laid out in two columns (the common academic-paper case) and, if so,
 * returns the gutter split. Single-column pages — and anything ambiguous — yield one column.
 *
 * <p>LiteParse emits roughly one {@link TextItem} per <em>line</em> (not per word), so a left-column
 * line spans the left half and a right-column line spans the right half; almost nothing spans the
 * central gutter except a handful of full-width lines (title, authors, page header/footer). We build
 * a horizontal "coverage" profile (how many line items span each x position) and look for a central
 * valley that sits well below the column peaks — a threshold relative to the peak, not an absolute
 * near-zero count, so a few full-width lines don't mask the gutter.
 */
public final class ColumnDetector {

    private ColumnDetector() {}

    private static final List<double[]> SINGLE = List.of(new double[] {-1e9, 1e9});

    /**
     * Column x-ranges, left to right, as contiguous {@code [lo, hi)} intervals.
     * A single element means "one column" (no split).
     */
    public static List<double[]> columns(List<TextItem> items, double pageWidth) {
        int n = items.size();
        if (n < 30 || pageWidth <= 0) {
            return SINGLE; // too few items to confidently detect columns
        }
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        for (TextItem it : items) {
            minX = Math.min(minX, it.x());
            maxX = Math.max(maxX, it.x() + it.width());
        }
        double width = maxX - minX;
        if (width <= 0) {
            return SINGLE;
        }

        final int bins = 200;
        double step = width / bins;
        int[] cover = new int[bins + 1];
        for (int i = 0; i <= bins; i++) {
            double p = minX + i * step;
            int c = 0;
            for (TextItem it : items) {
                if (it.x() < p && p < it.x() + it.width()) {
                    c++;
                }
            }
            cover[i] = c;
        }

        // Column peak = a robust high value of the coverage profile (90th percentile).
        int[] sorted = cover.clone();
        Arrays.sort(sorted);
        int peak = sorted[(int) (sorted.length * 0.90)];
        if (peak < 6) {
            return SINGLE; // sparse page — not enough lines to judge columns
        }
        // The gutter is a valley well below the column peaks. Inter-column gutters in two-column
        // papers are narrow (~10pt), so the floor is small; single-column pages have no central
        // valley at all (coverage stays near the peak across the whole text width), so a low floor
        // cannot produce a false split there.
        double valley = peak * 0.35;
        double minGutter = Math.max(pageWidth * 0.012, 8.0);

        // Search the central region only (a real two-column gutter is near the middle); pick the
        // widest qualifying low-coverage band there.
        int loBin = (int) (bins * 0.28);
        int hiBin = (int) (bins * 0.72);
        int bestStart = -1;
        int bestEnd = -1;
        int i = loBin;
        while (i <= hiBin) {
            if (cover[i] <= valley) {
                int j = i;
                while (j + 1 <= hiBin && cover[j + 1] <= valley) {
                    j++;
                }
                if (bestStart < 0 || (j - i) > (bestEnd - bestStart)) {
                    bestStart = i;
                    bestEnd = j;
                }
                i = j + 1;
            } else {
                i++;
            }
        }
        if (bestStart < 0) {
            return SINGLE; // no central valley — single column
        }
        double gLo = minX + bestStart * step;
        double gHi = minX + bestEnd * step;
        double split = (gLo + gHi) / 2.0;
        int left = 0;
        int right = 0;
        for (TextItem it : items) {
            double cx = it.x() + it.width() / 2.0;
            if (cx < split) {
                left++;
            } else {
                right++;
            }
        }
        if ((gHi - gLo) < minGutter) {
            return SINGLE;
        }
        // Both resulting columns must be wide enough and carry a real share of the lines.
        if ((split - minX) < width * 0.25 || (maxX - split) < width * 0.25) {
            return SINGLE;
        }
        if (left < n * 0.15 || right < n * 0.15) {
            return SINGLE;
        }

        List<double[]> cols = new ArrayList<>(2);
        cols.add(new double[] {minX - 1.0, split});
        cols.add(new double[] {split, maxX + 1.0});
        return cols;
    }
}
