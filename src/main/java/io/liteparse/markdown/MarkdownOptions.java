package io.liteparse.markdown;

/** Options controlling Markdown generation. Build with {@link #builder()}. */
public final class MarkdownOptions {

    private final boolean detectTables;
    private final boolean includeImages;
    private final String imageDir;

    private MarkdownOptions(boolean detectTables, boolean includeImages, String imageDir) {
        this.detectTables = detectTables;
        this.includeImages = includeImages;
        this.imageDir = imageDir;
    }

    /** Default options: table detection on, page images off. */
    public static MarkdownOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean detectTables() {
        return detectTables;
    }

    public boolean includeImages() {
        return includeImages;
    }

    public String imageDir() {
        return imageDir;
    }

    public static final class Builder {
        private boolean detectTables = true;
        private boolean includeImages = false;
        private String imageDir = "images";

        /** Detect tabular layouts and emit GFM tables (default true). */
        public Builder detectTables(boolean v) {
            this.detectTables = v;
            return this;
        }

        /** Embed a per-page screenshot image (requires passing screenshots to render). Default false. */
        public Builder includeImages(boolean v) {
            this.includeImages = v;
            return this;
        }

        /** Directory where embedded page images are written (default "images"). */
        public Builder imageDir(String v) {
            this.imageDir = v;
            return this;
        }

        public MarkdownOptions build() {
            return new MarkdownOptions(detectTables, includeImages, imageDir);
        }
    }
}
