package io.liteparse.markdown.engine;

import io.liteparse.TextItem;
import java.util.List;

/**
 * A line of text: the items sharing roughly the same vertical position, ordered
 * left-to-right.
 *
 * @param items    the text items, in reading order
 * @param top      the line's top y coordinate
 * @param left     the leftmost x coordinate (used for list/indent detection)
 * @param fontSize the line's representative font size (used for heading detection)
 */
public record TextLine(List<TextItem> items, double top, double left, double fontSize) {

    /** Plain text of the line (items joined by single spaces, collapsed). */
    public String text() {
        StringBuilder sb = new StringBuilder();
        for (TextItem it : items) {
            String t = it.text();
            if (t == null || t.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(t.strip());
        }
        return sb.toString().replaceAll("\\s+", " ").strip();
    }
}
