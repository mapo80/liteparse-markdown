package io.liteparse.markdown.engine;

import java.util.List;

/** A detected document block, ready to be emitted as Markdown. */
public sealed interface Block
        permits Block.Heading, Block.Paragraph, Block.UnorderedList, Block.OrderedList,
                Block.Table, Block.Image, Block.Rule {

    /** A heading of the given level (1–6) with styled inline content. */
    record Heading(int level, List<Span> spans) implements Block {}

    /** A paragraph of styled inline content. */
    record Paragraph(List<Span> spans) implements Block {}

    /** One list item: indent level (0-based) and its styled content. */
    record ListItem(int indent, List<Span> spans) {}

    record UnorderedList(List<ListItem> items) implements Block {}

    record OrderedList(List<ListItem> items) implements Block {}

    /** A GFM table; {@code rows.get(0)} is the header row. Cells are plain text. */
    record Table(List<List<String>> rows) implements Block {}

    /** An image reference (alt text + path/URL). */
    record Image(String alt, String path) implements Block {}

    /** A thematic break ({@code ---}). */
    record Rule() implements Block {}
}
