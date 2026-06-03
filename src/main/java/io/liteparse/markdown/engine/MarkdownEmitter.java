package io.liteparse.markdown.engine;

import java.util.ArrayList;
import java.util.List;
import org.commonmark.node.Code;
import org.commonmark.node.Document;
import org.commonmark.node.Emphasis;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.renderer.markdown.MarkdownRenderer;

/**
 * Emits Markdown from {@link Block}s. Inline content (with emphasis/code and proper
 * escaping) is rendered by commonmark-java's {@link MarkdownRenderer}; block scaffolding
 * (headings, lists, tables, rules, images) is assembled around it.
 */
public final class MarkdownEmitter {

    private final MarkdownRenderer inlineRenderer = MarkdownRenderer.builder().build();

    public String emit(List<Block> blocks) {
        List<String> parts = new ArrayList<>();
        for (Block b : blocks) {
            String md;
            if (b instanceof Block.Heading h) {
                md = "#".repeat(clamp(h.level())) + " " + inline(h.spans());
            } else if (b instanceof Block.Paragraph p) {
                md = inline(p.spans());
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

    /** Render a run of styled spans to inline Markdown via commonmark (handles escaping). */
    private String inline(List<Span> spans) {
        Document doc = new Document();
        Paragraph p = new Paragraph();
        boolean first = true;
        for (Span s : spans) {
            if (s.text().isBlank()) {
                continue;
            }
            if (!first) {
                p.appendChild(new Text(" "));
            }
            first = false;
            p.appendChild(inlineNode(s));
        }
        doc.appendChild(p);
        return inlineRenderer.render(doc).strip();
    }

    private Node inlineNode(Span s) {
        if (s.code()) {
            return new Code(s.text());
        }
        if (s.bold() && s.italic()) {
            StrongEmphasis strong = new StrongEmphasis();
            Emphasis em = new Emphasis();
            em.appendChild(new Text(s.text()));
            strong.appendChild(em);
            return strong;
        }
        if (s.bold()) {
            StrongEmphasis strong = new StrongEmphasis();
            strong.appendChild(new Text(s.text()));
            return strong;
        }
        if (s.italic()) {
            Emphasis em = new Emphasis();
            em.appendChild(new Text(s.text()));
            return em;
        }
        return new Text(s.text());
    }
}
