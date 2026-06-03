package io.liteparse.markdown.engine;

/** An inline run of text with a uniform style. */
public record Span(String text, boolean bold, boolean italic, boolean code) {

    public static Span plain(String text) {
        return new Span(text, false, false, false);
    }

    public boolean styled() {
        return bold || italic || code;
    }
}
