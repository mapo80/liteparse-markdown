package io.liteparse.markdown;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.liteparse.ParseResult;
import io.liteparse.ParsedPage;
import io.liteparse.TextItem;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Deterministic engine tests on synthetic {@link TextItem}s (no native library required).
 * Sizes are expressed via the bounding-box height, which the engine uses for headings.
 */
class EngineTest {

    private static TextItem item(String text, double x, double y, double w, double h,
                                 Integer weight) {
        return new TextItem(text, x, y, w, h, "Helvetica", h, weight, 0, 1.0);
    }

    @Test
    void rendersHeadingParagraphListAndTable() {
        List<TextItem> items = List.of(
                // Heading (tall line)
                item("Document Title", 50, 10, 220, 30, 400),
                // Paragraph with a bold run
                item("Normal", 50, 60, 40, 12, 400),
                item("bold", 95, 60, 30, 12, 700),
                // Bullet list
                item("• Item one", 50, 90, 90, 12, 400),
                item("• Item two", 50, 110, 90, 12, 400),
                // Two-column table
                item("Name", 50, 150, 40, 12, 400),
                item("Value", 300, 150, 40, 12, 400),
                item("A", 50, 170, 12, 12, 400),
                item("1", 300, 170, 12, 12, 400));

        ParsedPage page = new ParsedPage(1, 600, 800, "", items);
        ParseResult result = new ParseResult(List.of(page), "");

        String md = Markdown.from(result);

        assertTrue(md.contains("# Document Title"), () -> "heading missing in:\n" + md);
        assertTrue(md.contains("Normal **bold**"), () -> "bold inline missing in:\n" + md);
        assertTrue(md.contains("- Item one") && md.contains("- Item two"),
                () -> "list missing in:\n" + md);
        assertTrue(md.contains("| Name | Value |"), () -> "table header missing in:\n" + md);
        assertTrue(md.contains("| --- | --- |"), () -> "table separator missing in:\n" + md);
        assertTrue(md.contains("| A | 1 |"), () -> "table row missing in:\n" + md);
    }

    @Test
    void escapesMarkdownSpecialCharacters() {
        List<TextItem> items = List.of(item("1 * 2 < 3 # ok", 50, 10, 120, 12, 400));
        ParsedPage page = new ParsedPage(1, 600, 800, "", items);
        String md = Markdown.from(new ParseResult(List.of(page), ""));
        // The leading "1 ... " must not become a heading; '*' should be escaped, not emphasis.
        assertTrue(md.contains("\\*") || md.contains("1 \\* 2"), () -> "expected escaping in:\n" + md);
    }
}
