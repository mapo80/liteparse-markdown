package io.liteparse.markdown.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Emits Markdown from {@link Block}s. Inline content (emphasis/code) and block scaffolding
 * (headings, lists, tables, rules, images) are assembled here.
 *
 * <p>Inline text is escaped <em>minimally</em> — only the characters that would otherwise be
 * read as emphasis/code ({@code \ * `}) are backslash-escaped. Punctuation such as
 * {@code [ ] . ( ) #} is emitted verbatim, matching how reference Markdown (and tools like
 * pymupdf4llm) render born-digital text: e.g. a bibliography entry stays {@code [12] Author …}
 * rather than {@code \[12\] Author …}, and a numbered heading stays {@code ## 1.1 Title}.
 */
public final class MarkdownEmitter {

    public String emit(List<Block> blocks) {
        List<String> parts = new ArrayList<>();
        for (Block b : blocks) {
            String md;
            if (b instanceof Block.Heading h) {
                md = "#".repeat(clamp(h.level())) + " " + inline(h.spans());
            } else if (b instanceof Block.Paragraph p) {
                md = dehyphenate(inline(p.spans()));
            } else if (b instanceof Block.UnorderedList l) {
                md = list(l.items(), false);
            } else if (b instanceof Block.OrderedList l) {
                md = list(l.items(), true);
            } else if (b instanceof Block.Image im) {
                md = "![" + escapeBracket(im.alt()) + "](" + im.path() + ")";
            } else if (b instanceof Block.Rule) {
                md = "---";
            } else if (b instanceof Block.Table t) {
                md = table(t.rows());
            } else {
                md = null;
            }
            if (md != null && !md.isBlank()) {
                parts.add(md.strip());
            }
        }
        return parts.isEmpty() ? "" : String.join("\n\n", parts) + "\n";
    }

    private static int clamp(int level) {
        return Math.min(6, Math.max(1, level));
    }

    private String list(List<Block.ListItem> items, boolean ordered) {
        StringBuilder sb = new StringBuilder();
        int n = 1;
        for (Block.ListItem item : items) {
            String indent = "  ".repeat(Math.max(0, item.indent()));
            String marker = ordered ? (n++ + ". ") : "- ";
            sb.append(indent).append(marker).append(inline(item.spans())).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private String table(List<List<String>> rows) {
        if (rows.isEmpty()) {
            return "";
        }
        int cols = rows.stream().mapToInt(List::size).max().orElse(0);
        if (cols < 1) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(row(rows.get(0), cols)).append('\n');
        sb.append('|');
        for (int c = 0; c < cols; c++) {
            sb.append(" --- |");
        }
        sb.append('\n');
        for (int r = 1; r < rows.size(); r++) {
            sb.append(row(rows.get(r), cols)).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private String row(List<String> cells, int cols) {
        StringBuilder sb = new StringBuilder("|");
        for (int c = 0; c < cols; c++) {
            String value = c < cells.size() ? cells.get(c) : "";
            sb.append(' ').append(escapeCell(value)).append(" |");
        }
        return sb.toString();
    }

    private static String escapeCell(String s) {
        return s.replace("\\", "\\\\").replace("|", "\\|").replace("\n", " ").strip();
    }

    private static String escapeBracket(String s) {
        return s.replace("[", "\\[").replace("]", "\\]");
    }

    /** Render a run of styled spans to inline Markdown with minimal escaping. */
    private String inline(List<Span> spans) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Span s : spans) {
            if (s.text().isBlank()) {
                continue;
            }
            if (!first) {
                sb.append(' ');
            }
            first = false;
            sb.append(renderSpan(s));
        }
        return sb.toString().strip();
    }

    private static String renderSpan(Span s) {
        if (s.code()) {
            return "`" + s.text() + "`"; // code spans are verbatim
        }
        String t = escapeInline(s.text());
        if (s.bold() && s.italic()) {
            return "***" + t + "***";
        }
        if (s.bold()) {
            return "**" + t + "**";
        }
        if (s.italic()) {
            return "*" + t + "*";
        }
        return t;
    }

    /** De-hyphenate a word split across a line break inside a reflowed paragraph: "sym- plectic"
     * -> "symplectic". (Line-wrap hyphens are far more often plain word breaks than genuine
     * compounds, so removing them matches the source text more often than keeping them.) */
    static String dehyphenate(String s) {
        return s.replaceAll("(\\p{L})-\\s+(\\p{Ll})", "$1$2");
    }

    /** Escape only the characters that would otherwise start emphasis/code or an escape. */
    private static String escapeInline(String s) {
        StringBuilder out = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '*' || c == '`') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }
}
