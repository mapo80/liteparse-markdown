package io.liteparse.markdown.engine;

import io.liteparse.TextItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns ordered {@link TextLine}s into a sequence of {@link Block}s using layout heuristics. */
public final class BlockClassifier {

    private static final Pattern BULLET = Pattern.compile("^[\\u2022\\u2023\\u25E6\\u2043\\u2219\\u00B7•\\-\\*\\u2013\\u2014]\\s+(.*)$");
    private static final Pattern ORDERED = Pattern.compile("^(\\d{1,3})[.)]\\s+(.*)$");
    // Bibliography entries: "[1] Author ..." — kept verbatim (the "[n]" stays in the item text, as
    // ground-truth Markdown does). Lettered/parenthesised enumerations: "(a)", "(1)", "a)".
    private static final Pattern REFERENCE = Pattern.compile("^\\[(\\d{1,3})\\]\\s+\\S.*$");
    private static final Pattern PAREN_ITEM = Pattern.compile("^\\(([a-zA-Z]|\\d{1,2})\\)\\s+(.*)$");
    private static final Pattern MONO = Pattern.compile("(?i)(mono|courier|consolas|menlo|inconsolata)");
    // Section numbers, with an optional trailing dot: "1", "1.", "1.2", "3.1.4".
    private static final Pattern NUMBERED_SEC = Pattern.compile("^(\\d+(?:\\.\\d+)*)\\.?\\s+\\S.*");
    private static final Pattern ROMAN_SEC = Pattern.compile("^([IVXLC]{1,6})\\.?\\s+\\S.*");
    private static final Pattern ARXIV_ID = Pattern.compile("(?i)^arxiv:\\s*\\d");
    // arXiv left-margin date stamp, e.g. "21 Dec 2007" / "Dec 2007" (read as its own line).
    private static final Pattern DATE_STAMP = Pattern.compile(
            "(?i)^\\d{0,2}\\s*(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?\\s+\\d{4}$");
    // arXiv figure stubs left behind when figures are stripped, and bare URLs.
    private static final Pattern FIGURE_STUB = Pattern.compile("(?i)this figure .* available in .* format from");
    private static final Pattern URL_LINE = Pattern.compile("(?i)^https?://\\S+$");
    // arXiv subject-class tag in the margin, e.g. "[astro-ph.CO]", "[cond-mat.mes-hall]".
    private static final Pattern ARXIV_CAT = Pattern.compile(
            "(?i)^\\[(astro-ph|cond-mat|hep-[a-z]+|gr-qc|quant-ph|math(-ph)?|cs|physics|nlin|"
            + "nucl-[a-z]+|stat|q-bio|eess|econ|cmp-lg)");
    private static final Pattern NUMBER_TOKEN = Pattern.compile("(?<![\\w.])\\d+(?:\\.\\d+)*");
    private static final Set<String> REFERENCES_KEYS = Set.of("references", "bibliography");
    private static final Pattern WORD = Pattern.compile("[A-Za-z]{3,}");
    private static final Set<String> SECTION_KEYWORDS = Set.of(
            "abstract", "introduction", "references", "bibliography", "acknowledgment",
            "acknowledgments", "acknowledgement", "acknowledgements", "conclusion", "conclusions",
            "appendix", "discussion", "background", "related work", "methods", "results",
            "summary", "preliminaries", "notation", "motivation", "future work");

    private final HeadingModel headings;
    private final double body;
    private final boolean detectTables;

    public BlockClassifier(HeadingModel headings, boolean detectTables) {
        this.headings = headings;
        this.body = headings.bodySize();
        this.detectTables = detectTables;
    }

    public List<Block> classify(List<TextLine> lines) {
        List<Block> blocks = new ArrayList<>();
        List<TextLine> textBuf = new ArrayList<>();
        List<Block.ListItem> listBuf = new ArrayList<>();
        boolean[] listOrdered = {false};
        double listLeft = 0;        // left x of the current list's marker lines
        TextLine prevLine = null;
        boolean inReferences = false; // once in the bibliography, numbered lines are entries, not headings
        java.util.Set<String> seenHeadings = new java.util.HashSet<>();

        for (TextLine line : lines) {
            String text = line.text();
            if (text.isBlank() || isJunk(text)) {
                continue;
            }
            int heading = headingLevel(line, text, inReferences);
            // A heading whose text already appeared is a duplicate (e.g. a table-of-contents entry
            // repeating a real section title) — keep it as plain text, not a second heading.
            boolean mergeable = heading > 0 && !blocks.isEmpty()
                    && blocks.get(blocks.size() - 1) instanceof Block.Heading prev && prev.level() == heading;
            if (heading > 0 && !mergeable && !seenHeadings.add(dedupKey(text))) {
                heading = 0;
            }
            Matcher bullet = BULLET.matcher(text);
            Matcher ordered = ORDERED.matcher(text);
            Matcher ref = REFERENCE.matcher(text);
            Matcher paren = PAREN_ITEM.matcher(text);
            // A parenthesised marker only starts a list item if real text follows it — this rejects
            // figure panel labels like "(b)" or "(b) (c) (d)".
            boolean parenItem = paren.matches() && WORD.matcher(paren.group(2)).find();
            boolean isMarker = bullet.matches() || ordered.matches() || ref.matches() || parenItem;

            if (heading > 0) {
                String key = text.strip().toLowerCase(Locale.ROOT).replaceFirst("^[0-9.\\s]+", "").strip();
                if (REFERENCES_KEYS.contains(key)) {
                    inReferences = true;
                }
                flushText(textBuf, blocks);
                flushList(listBuf, listOrdered[0], blocks);
                // Join wrapped heading lines: merge into the immediately preceding same-level heading.
                if (!blocks.isEmpty() && blocks.get(blocks.size() - 1) instanceof Block.Heading prev
                        && prev.level() == heading) {
                    String merged = reflow(prev.spans().get(0).text(), text);
                    blocks.set(blocks.size() - 1, new Block.Heading(heading, List.of(Span.plain(merged))));
                } else {
                    blocks.add(new Block.Heading(heading, List.of(Span.plain(text.strip()))));
                }
            } else if (isMarker) {
                flushText(textBuf, blocks);
                boolean isOrdered = ordered.matches();
                if (!listBuf.isEmpty() && listOrdered[0] != isOrdered) {
                    flushList(listBuf, listOrdered[0], blocks);
                }
                listOrdered[0] = isOrdered;
                // References keep their "[n]" marker (matching ground-truth Markdown); other
                // markers are stripped and re-emitted by MarkdownEmitter.
                String content = isOrdered ? ordered.group(2)
                        : ref.matches() ? text.strip()
                        : parenItem ? paren.group(2)
                        : bullet.group(1);
                listBuf.add(new Block.ListItem(0, List.of(Span.plain(content.strip()))));
                listLeft = line.left();
            } else if (!listBuf.isEmpty() && isContinuation(line, prevLine, listLeft)) {
                appendToLastItem(listBuf, text);
            } else if (isDisplayMath(text)) {
                // Standalone display equation: drop it (renders as broken glyphs that match nothing).
                flushList(listBuf, listOrdered[0], blocks);
            } else {
                flushList(listBuf, listOrdered[0], blocks);
                textBuf.add(line);
            }
            prevLine = line;
        }
        flushText(textBuf, blocks);
        flushList(listBuf, listOrdered[0], blocks);
        return blocks;
    }

    /** A wrapped continuation of the current list item: hanging-indented past the marker, with
     * normal line spacing (a large gap or a return to the marker's left margin ends the list). */
    private boolean isContinuation(TextLine line, TextLine prev, double listLeft) {
        if (prev == null) {
            return false;
        }
        double gap = line.top() - prev.top();
        if (gap > Math.max(line.fontSize(), body) * 1.8) {
            return false;
        }
        return line.left() > listLeft + 2.0;
    }

    private static void appendToLastItem(List<Block.ListItem> listBuf, String text) {
        Block.ListItem last = listBuf.remove(listBuf.size() - 1);
        String merged = reflow(last.spans().get(0).text(), text);
        listBuf.add(new Block.ListItem(last.indent(), List.of(Span.plain(merged))));
    }

    /** Join two wrapped lines: de-hyphenate a word split across the break ("sym-" + "plectic" ->
     * "symplectic"), otherwise join with a single space. */
    static String reflow(String a, String b) {
        String left = a.strip();
        String right = b.strip();
        if (right.isEmpty()) {
            return left;
        }
        if (left.endsWith("-") && left.length() >= 2
                && Character.isLetter(left.charAt(left.length() - 2))
                && Character.isLowerCase(right.charAt(0))) {
            return (left.substring(0, left.length() - 1) + right).strip();
        }
        return (left + " " + right).replaceAll("\\s+", " ").strip();
    }

    // --- headings -----------------------------------------------------------

    private int headingLevel(TextLine line, String rawText, boolean inReferences) {
        if (isOcr(line)) {
            return 0; // OCR glyph sizes are noisy — don't infer headings from size
        }
        String t = rawText.strip();
        if (t.length() > 90 || !WORD.matcher(t).find()) {
            return 0; // headings are short and contain a real word (filters fragments/long prose)
        }
        if (countNumbers(t) >= 4) {
            return 0; // table-of-contents line ("1 Abstract 4 2 Introduction 5 …"), not a heading
        }
        // A line ending in a comma/semicolon is an affiliation or prose clause, never a heading
        // ("1 School of Mathematics, Shandong University,"). A trailing period is fine for short
        // headings ("Introduction.", "Methods.") but marks prose on long lines.
        if (t.endsWith(",") || t.endsWith(";")) {
            return 0;
        }
        boolean endsPeriod = t.endsWith(".");
        boolean shortHead = t.length() <= 45;

        int sizeLvl = headings.levelForSize(headings.lineSize(line));
        Matcher num = NUMBERED_SEC.matcher(t);
        Matcher rom = ROMAN_SEC.matcher(t);
        boolean numbered = num.matches();
        String keywordKey = t.toLowerCase(Locale.ROOT)
                .replaceFirst("^[0-9.\\s]+", "").replaceFirst("[.:]+$", "").strip();
        boolean keyword = SECTION_KEYWORDS.contains(keywordKey);
        // Title-like: short-ish, capitalised, not an equation, not a long sentence.
        boolean titleLike = t.length() <= 65 && !hasMath(t) && firstLetterUpper(t)
                && (!endsPeriod || shortHead);

        if (sizeLvl == 0) {
            if (inReferences) {
                return 0; // inside the bibliography, "3 Author, Journal …" is an entry, not a heading
            }
            // Body-sized lines: only strong structural signals make them headings (many papers set
            // section titles at body size and rely on numbering / bold instead of a larger font).
            if (numbered && titleLike) {
                return numberedLevel(num);
            }
            if (rom.matches() && titleLike && safeRoman(rom.group(1), t, line)) {
                return 2;
            }
            // A short standalone section keyword ("References", "Acknowledgment", "Introduction.")
            // is a heading even at body size — body text is virtually never just such a word.
            if (keyword && t.length() <= 30) {
                return 2;
            }
            return 0;
        }
        // Size says heading. A long sentence at a slightly larger size is prose, not a heading.
        if (endsPeriod && !shortHead && !numbered) {
            return 0;
        }
        if (numbered) {
            // A numbered line containing math is an equation ("1 X displ(g) ∗ χp"), not a section.
            return (hasMath(t) || (endsPeriod && !shortHead)) ? 0 : numberedLevel(num);
        }
        if (rom.matches()) {
            return 2;
        }
        if (keyword) {
            return Math.max(2, sizeLvl);
        }
        return sizeLvl;
    }

    /** Count standalone number tokens ("1", "2.4"); many of them signal a table-of-contents line. */
    private static int countNumbers(String t) {
        Matcher m = NUMBER_TOKEN.matcher(t);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    /** Normalised heading text for duplicate detection (case/space-insensitive). */
    private static String dedupKey(String text) {
        return text.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static int numberedLevel(Matcher num) {
        int depth = num.group(1).split("\\.").length; // "1" -> 1, "1.2" -> 2
        return Math.min(6, depth + 1);
    }

    /** Whether a Roman-numeral prefix is a genuine section marker (not the pronoun "I"): multi-letter
     * numerals (II, III, …) are safe; a lone "I" only counts if bold or followed by a section word. */
    private boolean safeRoman(String numeral, String t, TextLine line) {
        if (numeral.length() >= 2) {
            return true;
        }
        if (isBoldLine(line)) {
            return true;
        }
        String[] rest = t.substring(numeral.length()).replaceFirst("^[.\\s]+", "")
                .toLowerCase(Locale.ROOT).split("\\s+");
        return rest.length > 0 && SECTION_KEYWORDS.contains(rest[0]);
    }

    /** A standalone display equation / symbol fragment: dropped because it renders as broken glyphs
     * ("ρ = µ + ∆") that never match the ground truth's LaTeX, only hurting text precision. Prose
     * with inline math stays (its letter ratio is high); table rows have no math operators. */
    private static boolean isDisplayMath(String t) {
        return (hasMath(t) && letterRatio(t) < 0.5) || letterRatio(t) < 0.15;
    }

    /** Fraction of non-space characters that are letters. */
    private static double letterRatio(String t) {
        int letters = 0;
        int chars = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (!Character.isWhitespace(c)) {
                chars++;
                if (Character.isLetter(c)) {
                    letters++;
                }
            }
        }
        return chars == 0 ? 1.0 : (double) letters / chars;
    }

    /** True if the line contains math operators — i.e. it is an equation, not a section title. */
    private static boolean hasMath(String t) {
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '−' || "=+<>×·±∑∏∫≤≥≈≠→∈".indexOf(c) >= 0) {
                return true;
            }
        }
        return false;
    }

    /** True if the first alphabetic character is upper-case (section titles are capitalised). */
    private static boolean firstLetterUpper(String t) {
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isLetter(c)) {
                return Character.isUpperCase(c);
            }
        }
        return false;
    }

    /** Lines to drop entirely: lone glyphs and the arXiv-id sidebar. (Kept conservative so it
     * never removes short table cells; stray fragments are excluded from headings via the
     * "contains a real word" check in {@link #headingLevel}.) */
    private static boolean isJunk(String text) {
        String t = text.strip();
        return t.length() <= 1 || ARXIV_ID.matcher(t).find() || DATE_STAMP.matcher(t).matches()
                || URL_LINE.matcher(t).matches() || FIGURE_STUB.matcher(t).find()
                || ARXIV_CAT.matcher(t).find();
    }

    private boolean isBoldLine(TextLine line) {
        int bold = 0, total = 0;
        for (TextItem it : line.items()) {
            if (it.text() == null || it.text().isBlank()) {
                continue;
            }
            int chars = it.text().strip().length();
            total += chars;
            if (isBold(it)) {
                bold += chars;
            }
        }
        return total > 0 && bold >= total * 0.6;
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
