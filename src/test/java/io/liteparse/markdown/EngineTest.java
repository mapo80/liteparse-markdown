package io.liteparse.markdown;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.liteparse.ParseResult;
import io.liteparse.ParsedPage;
import io.liteparse.TextItem;
import java.util.ArrayList;
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

    private static TextItem styled(String text, double x, double y, double w, double h,
                                   Integer weight, int flags, String font, double confidence) {
        return new TextItem(text, x, y, w, h, font, h, weight, flags, confidence);
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
        List<TextItem> items = List.of(item("the product a * b is the answer here", 50, 10, 200, 12, 400));
        ParsedPage page = new ParsedPage(1, 600, 800, "", items);
        String md = Markdown.from(new ParseResult(List.of(page), ""));
        // A literal '*' in prose must be escaped, not interpreted as emphasis.
        assertTrue(md.contains("a \\* b"), () -> "expected escaping in:\n" + md);
    }

    @Test
    void detectsHeadingHierarchyByHeight() {
        List<TextItem> items = List.of(
                item("Big Title", 50, 10, 200, 30, 400),
                item("Section", 50, 50, 120, 20, 400),
                item("Subsection", 50, 85, 140, 16, 400),
                item("This is a normal paragraph with enough characters to dominate the body size.",
                        50, 120, 480, 12, 400));
        String md = Markdown.from(new ParseResult(List.of(new ParsedPage(1, 600, 800, "", items)), ""));
        assertTrue(md.contains("# Big Title"), () -> md);
        assertTrue(md.contains("## Section"), () -> md);
        assertTrue(md.contains("### Subsection"), () -> md);
    }

    @Test
    void rendersBoldItalicAndInlineCode() {
        List<TextItem> items = List.of(
                styled("normal", 50, 10, 40, 12, 400, 0, "Helvetica", 1.0),
                styled("bolded", 92, 10, 45, 12, 700, 0, "Helvetica", 1.0),
                styled("italics", 139, 10, 45, 12, 400, 0x40, "Helvetica", 1.0),
                styled("codey", 186, 10, 40, 12, 400, 0, "CourierNewPSMT", 1.0));
        String md = Markdown.from(new ParseResult(List.of(new ParsedPage(1, 600, 800, "", items)), ""));
        assertTrue(md.contains("**bolded**"), () -> md);
        assertTrue(md.contains("*italics*"), () -> md);
        assertTrue(md.contains("`codey`"), () -> md);
    }

    @Test
    void rendersOrderedList() {
        List<TextItem> items = List.of(
                item("1. First item", 50, 10, 120, 12, 400),
                item("2. Second item", 50, 30, 130, 12, 400));
        String md = Markdown.from(new ParseResult(List.of(new ParsedPage(1, 600, 800, "", items)), ""));
        assertTrue(md.contains("1. First item"), () -> md);
        assertTrue(md.contains("2. Second item"), () -> md);
    }

    @Test
    void plainTextIsNotTurnedIntoTable() {
        List<TextItem> items = List.of(
                item("The", 50, 10, 20, 12, 400),
                item("quick", 72, 10, 35, 12, 400),
                item("brown", 110, 10, 40, 12, 400),
                item("fox", 152, 10, 20, 12, 400),
                item("jumps", 175, 10, 40, 12, 400));
        String md = Markdown.from(new ParseResult(List.of(new ParsedPage(1, 600, 800, "", items)), ""));
        assertFalse(md.contains("|"), () -> "single-column prose must not become a table:\n" + md);
        assertTrue(md.contains("The quick brown fox jumps"), () -> md);
    }

    @Test
    void ocrTextDoesNotBecomeHeadingFromHeight() {
        List<TextItem> items = List.of(
                // OCR line: tall but low confidence -> must NOT be a heading
                styled("Scanned Title", 50, 10, 200, 30, 400, 0, "Helvetica", 0.8),
                styled("Lots of normal body text here to set the dominant body height for the page.",
                        50, 60, 480, 12, 400, 0, "Helvetica", 1.0));
        String md = Markdown.from(new ParseResult(List.of(new ParsedPage(1, 600, 800, "", items)), ""));
        assertFalse(md.contains("# Scanned Title"), () -> "OCR text should not be a heading:\n" + md);
        assertTrue(md.contains("Scanned Title"), () -> md);
    }

    @Test
    void rendersThreeColumnTable() {
        List<TextItem> items = List.of(
                item("Col A", 50, 150, 40, 12, 400),
                item("Col B", 250, 150, 40, 12, 400),
                item("Col C", 450, 150, 40, 12, 400),
                item("a1", 50, 170, 12, 12, 400),
                item("b1", 250, 170, 12, 12, 400),
                item("c1", 450, 170, 12, 12, 400),
                item("a2", 50, 190, 12, 12, 400),
                item("b2", 250, 190, 12, 12, 400),
                item("c2", 450, 190, 12, 12, 400));
        String md = Markdown.from(new ParseResult(List.of(new ParsedPage(1, 600, 800, "", items)), ""));
        assertTrue(md.contains("| Col A | Col B | Col C |"), () -> md);
        assertTrue(md.contains("| --- | --- | --- |"), () -> md);
        assertTrue(md.contains("| a1 | b1 | c1 |"), () -> md);
        assertTrue(md.contains("| a2 | b2 | c2 |"), () -> md);
    }

    @Test
    void detectsNumberedAndRomanSectionsAtBodySize() {
        List<TextItem> items = List.of(
                item("Some opening sentence forming the dominant body text of the page here.", 50, 10, 480, 12, 400),
                item("1 Introduction", 50, 40, 120, 12, 400),
                item("More body text discussing the section in a few plain words here today.", 50, 70, 480, 12, 400),
                item("2 Methods", 50, 100, 120, 12, 400),
                item("Yet more body text so the sections are separated by real paragraphs here.", 50, 130, 480, 12, 400),
                item("III Discussion", 50, 160, 120, 12, 400));
        String md = Markdown.from(new ParseResult(List.of(new ParsedPage(1, 600, 800, "", items)), ""));
        assertTrue(md.contains("## 1 Introduction"), () -> md);
        assertTrue(md.contains("## 2 Methods"), () -> md);
        assertTrue(md.contains("## III Discussion"), () -> md);
    }

    @Test
    void referencesBecomeAListAndKeepBracketsUnescaped() {
        List<TextItem> items = List.of(
                item("References", 50, 10, 90, 12, 400),
                item("[1] Smith, J. A study of things. Journal of Things, 2001.", 50, 30, 480, 12, 400),
                item("[2] Doe, A. Another study. Journal of Things, 2002.", 50, 50, 480, 12, 400));
        String md = Markdown.from(new ParseResult(List.of(new ParsedPage(1, 600, 800, "", items)), ""));
        assertTrue(md.contains("## References"), () -> md);
        assertTrue(md.contains("- [1] Smith, J."), () -> md);
        assertFalse(md.contains("\\["), () -> "reference brackets must not be escaped:\n" + md);
    }

    @Test
    void dehyphenatesWordSplitAcrossLines() {
        List<TextItem> items = List.of(
                item("This experi-", 50, 10, 100, 12, 400),
                item("ment confirms the expected result clearly enough for the reader.", 50, 28, 420, 12, 400));
        String md = Markdown.from(new ParseResult(List.of(new ParsedPage(1, 600, 800, "", items)), ""));
        assertTrue(md.contains("experiment confirms"), () -> md);
        assertFalse(md.contains("experi- ment"), () -> md);
    }

    @Test
    void dropsDisplayEquationLines() {
        List<TextItem> items = List.of(
                item("The system is governed by the relation written on the line just below.", 50, 10, 480, 12, 400),
                item("∂u/∂t = ∇ · (D ∇u) + S", 50, 40, 160, 12, 400),
                item("which holds across the whole domain for every admissible input value.", 50, 70, 480, 12, 400));
        String md = Markdown.from(new ParseResult(List.of(new ParsedPage(1, 600, 800, "", items)), ""));
        assertTrue(md.contains("The system is governed"), () -> md);
        assertFalse(md.contains("∇"), () -> "display equation should be dropped:\n" + md);
    }

    @Test
    void dropsMarginLineNumberBeforeHeading() {
        List<TextItem> items = List.of(
                item("27", 8, 10, 12, 12, 400),                 // far-left margin line number
                item("Introduction", 54, 10, 110, 12, 400),
                item("Body sentence establishing the dominant body font size for this page here.", 54, 34, 480, 12, 400));
        String md = Markdown.from(new ParseResult(List.of(new ParsedPage(1, 600, 800, "", items)), ""));
        assertTrue(md.contains("## Introduction"), () -> md);
        assertFalse(md.contains("27 Introduction"), () -> md);
    }

    @Test
    void dropsRepeatedRunningHeader() {
        List<ParsedPage> pages = new ArrayList<>();
        for (int p = 1; p <= 3; p++) {
            pages.add(new ParsedPage(p, 600, 800, "", List.of(
                    item("J. Smith", 50, 10, 60, 12, 400),       // running header on every page
                    item("Distinct body content for page " + p + " with enough words to read as text.",
                            50, 40, 480, 12, 400))));
        }
        String md = Markdown.from(new ParseResult(pages, ""));
        assertFalse(md.contains("J. Smith"), () -> "running header should be removed:\n" + md);
        assertTrue(md.contains("Distinct body content for page 1"), () -> md);
    }

    @Test
    void readsTwoColumnsInOrderNotAsATable() {
        List<TextItem> items = new ArrayList<>();
        for (int r = 0; r < 18; r++) {
            double y = 10 + r * 16;
            items.add(item("Left column line number " + r + " with several words of body text.",
                    60, y, 180, 11, 400));
            items.add(item("Right column line number " + r + " with several words of body text.",
                    340, y, 180, 11, 400));
        }
        String md = Markdown.from(new ParseResult(List.of(new ParsedPage(1, 600, 800, "", items)), ""));
        assertFalse(md.contains("|"), () -> "two columns must not be merged into a table:\n" + md);
        assertTrue(md.contains("Left column line number 0"), () -> md);
        assertTrue(md.contains("Right column line number 0"), () -> md);
    }
}
