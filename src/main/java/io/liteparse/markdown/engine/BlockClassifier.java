package io.liteparse.markdown.engine;

import io.liteparse.TextItem;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns ordered {@link TextLine}s into a sequence of {@link Block}s using layout heuristics. */
public final class BlockClassifier {

    private static final Pattern BULLET = Pattern.compile("^[\\u2022\\u2023\\u25E6\\u2043\\u2219\\u00B7•\\-\\*\\u2013\\u2014]\\s+(.*)$");
    private static final Pattern ORDERED = Pattern.compile("^(\\d{1,3})[.)]\\s+(.*)$");
    private static final Pattern MONO = Pattern.compile("(?i)(mono|courier|consolas|menlo|inconsolata)");

    private final double body;
    private final boolean detectTables;

    public BlockClassifier(double bodyFontSize, boolean detectTables) {
        this.body = bodyFontSize;
        this.detectTables = detectTables;
    }

    public List<Block> classify(List<TextLine> lines) {
        List<Block> blocks = new ArrayList<>();
        List<TextLine> textBuf = new ArrayList<>();
        List<Block.ListItem> listBuf = new ArrayList<>();
        boolean[] listOrdered = {false};

        for (TextLine line : lines) {
            if (line.text().isBlank()) {
                continue;
            }
            int heading = headingLevel(line);
            Matcher bullet = BULLET.matcher(line.text());
            Matcher ordered = ORDERED.matcher(line.text());

            if (heading > 0) {
                flushText(textBuf, blocks);
                flushList(listBuf, listOrdered[0], blocks);
                blocks.add(new Block.Heading(heading, spansFromText(line, stripPrefix(line))));
            } else if (bullet.matches() || ordered.matches()) {
                flushText(textBuf, blocks);
                boolean isOrdered = ordered.matches();
                if (!listBuf.isEmpty() && listOrdered[0] != isOrdered) {
                    flushList(listBuf, listOrdered[0], blocks);
                }
                listOrdered[0] = isOrdered;
                String content = isOrdered ? ordered.group(2) : bullet.group(1);
                listBuf.add(new Block.ListItem(0, List.of(Span.plain(content.strip()))));
            } else {
                flushList(listBuf, listOrdered[0], blocks);
                textBuf.add(line);
            }
        }
        flushText(textBuf, blocks);
        flushList(listBuf, listOrdered[0], blocks);
        return blocks;
    }

    // --- headings -----------------------------------------------------------

    private int headingLevel(TextLine line) {
        if (line.text().length() > 140) {
            return 0; // long lines are paragraphs, not headings
        }
        if (isOcr(line)) {
            return 0; // OCR glyph heights are noisy — don't infer headings from size
        }
        double ratio = line.fontSize() / body;
        if (ratio >= 1.9) return 1;
        if (ratio >= 1.5) return 2;
        if (ratio >= 1.3) return 3;
        if (ratio >= 1.15) return 4;
        return 0;
    }

    // --- text buffer: tables + paragraphs -----------------------------------

    private void flushText(List<TextLine> buf, List<Block> blocks) {
        if (buf.isEmpty()) {
            return;
        }
        List<TextLine> lines = new ArrayList<>(buf);
        buf.clear();

        int i = 0;
        while (i < lines.size()) {
            int tableEnd = detectTables ? tableRun(lines, i) : -1;
            if (tableEnd > i) {
                blocks.add(buildTable(lines.subList(i, tableEnd + 1)));
                i = tableEnd + 1;
                continue;
            }
            // paragraph: consecutive lines without a large vertical gap
            int j = i;
            List<Span> spans = new ArrayList<>(spansFromText(lines.get(i), lines.get(i).text()));
            while (j + 1 < lines.size()) {
                TextLine prev = lines.get(j);
                TextLine next = lines.get(j + 1);
                if (detectTables && tableRun(lines, j + 1) > j + 1) {
                    break;
                }
                double gap = next.top() - prev.top();
                if (gap > Math.max(prev.fontSize(), body) * 2.2) {
                    break;
                }
                spans.add(Span.plain(" "));
                spans.addAll(spansFromText(next, next.text()));
                j++;
            }
            blocks.add(new Block.Paragraph(mergeSpans(spans)));
            i = j + 1;
        }
    }

    private void flushList(List<Block.ListItem> buf, boolean ordered, List<Block> blocks) {
        if (buf.isEmpty()) {
            return;
        }
        List<Block.ListItem> items = new ArrayList<>(buf);
        buf.clear();
        blocks.add(ordered ? new Block.OrderedList(items) : new Block.UnorderedList(items));
    }

    // --- tables -------------------------------------------------------------

    /** If lines starting at {@code i} form a table, returns the index of the last table line; else -1. */
    private int tableRun(List<TextLine> lines, int i) {
        List<Cell> first = cells(lines.get(i));
        if (first.size() < 2) {
            return -1;
        }
        int j = i;
        while (j + 1 < lines.size()) {
            List<Cell> next = cells(lines.get(j + 1));
            if (next.size() != first.size() || !aligned(first, next)) {
                break;
            }
            j++;
        }
        return (j > i) ? j : -1; // need at least 2 rows
    }

    private boolean aligned(List<Cell> a, List<Cell> b) {
        for (int k = 0; k < a.size(); k++) {
            if (Math.abs(a.get(k).start - b.get(k).start) > 18.0) {
                return false;
            }
        }
        return true;
    }

    /** Split a line into cells by large horizontal gaps between items. */
    private List<Cell> cells(TextLine line) {
        List<Cell> out = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        double start = -1;
        TextItem prev = null;
        double threshold = Math.max(line.fontSize() * 1.5, 14.0);
        for (TextItem it : line.items()) {
            if (it.text() == null || it.text().isBlank()) {
                continue;
            }
            boolean newCell = prev != null && (it.x() - (prev.x() + prev.width())) > threshold;
            if (prev == null) {
                start = it.x();
            }
            if (newCell) {
                out.add(new Cell(start, text.toString().strip()));
                text.setLength(0);
                start = it.x();
            } else if (text.length() > 0) {
                text.append(' ');
            }
            text.append(it.text().strip());
            prev = it;
        }
        if (text.length() > 0) {
            out.add(new Cell(start, text.toString().strip()));
        }
        return out;
    }

    private Block.Table buildTable(List<TextLine> rows) {
        List<List<String>> out = new ArrayList<>();
        for (TextLine line : rows) {
            List<String> cells = new ArrayList<>();
            for (Cell c : cells(line)) {
                cells.add(c.text);
            }
            out.add(cells);
        }
        return new Block.Table(out);
    }

    private record Cell(double start, String text) {}

    // --- inline styling -----------------------------------------------------

    private List<Span> spansFromText(TextLine line, String overrideText) {
        // When the visible text was rewritten (heading prefix-stripped or identical),
        // fall back to per-item styling but keep the override text if items don't reconstruct it.
        List<Span> spans = spansFromItems(line.items());
        if (spans.isEmpty()) {
            return List.of(Span.plain(overrideText));
        }
        return spans;
    }

    private List<Span> spansFromItems(List<TextItem> items) {
        List<Span> spans = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        Boolean curBold = null, curItalic = null, curCode = null;
        for (TextItem it : items) {
            if (it.text() == null || it.text().isBlank()) {
                continue;
            }
            boolean bold = isBold(it);
            boolean italic = isItalic(it);
            boolean code = isMono(it);
            if (curBold != null && (bold != curBold || italic != curItalic || code != curCode)) {
                spans.add(new Span(text.toString().strip(), curBold, curItalic, curCode));
                text.setLength(0);
            }
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(it.text().strip());
            curBold = bold;
            curItalic = italic;
            curCode = code;
        }
        if (curBold != null && text.length() > 0) {
            spans.add(new Span(text.toString().strip(), curBold, curItalic, curCode));
        }
        return mergeSpans(spans);
    }

    private static boolean isBold(TextItem it) {
        if (it.fontWeight() != null && it.fontWeight() >= 600) {
            return true;
        }
        return it.fontFlags() != null && (it.fontFlags() & 0x40000) != 0; // ForceBold
    }

    private static boolean isItalic(TextItem it) {
        return it.fontFlags() != null && (it.fontFlags() & 0x40) != 0; // Italic
    }

    private static boolean isMono(TextItem it) {
        return it.fontName() != null && MONO.matcher(it.fontName()).find();
    }

    /** True if the line looks OCR-derived (any item with confidence below 1.0). */
    private static boolean isOcr(TextLine line) {
        for (TextItem it : line.items()) {
            if (it.confidence() != null && it.confidence() < 0.99) {
                return true;
            }
        }
        return false;
    }

    private static String stripPrefix(TextLine line) {
        return line.text();
    }

    /** Merge adjacent spans that share the same style and drop empties. */
    static List<Span> mergeSpans(List<Span> spans) {
        List<Span> out = new ArrayList<>();
        for (Span s : spans) {
            if (s.text().isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                Span last = out.get(out.size() - 1);
                if (last.bold() == s.bold() && last.italic() == s.italic() && last.code() == s.code()
                        && !s.text().equals(" ")) {
                    out.set(out.size() - 1,
                            new Span((last.text() + " " + s.text()).replaceAll("\\s+", " ").strip(),
                                    s.bold(), s.italic(), s.code()));
                    continue;
                }
            }
            out.add(s);
        }
        return out;
    }
}
