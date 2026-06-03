package io.liteparse.markdown;

import io.liteparse.ParseResult;
import io.liteparse.ParsedPage;
import io.liteparse.ScreenshotResult;
import io.liteparse.markdown.engine.Block;
import io.liteparse.markdown.engine.BlockClassifier;
import io.liteparse.markdown.engine.HeadingModel;
import io.liteparse.markdown.engine.LayoutAnalyzer;
import io.liteparse.markdown.engine.MarkdownEmitter;
import io.liteparse.markdown.engine.TextLine;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Converts a LiteParse {@link ParseResult} into Markdown.
 *
 * <p>Deterministic and fully local: structure (headings, paragraphs, bold/italic, lists,
 * tables) is inferred from the layout/font metadata produced by
 * <a href="https://github.com/mapo80/liteparse-java">liteparse-java</a>; the Markdown text is
 * emitted with commonmark-java. No LLM, no network.
 *
 * <pre>{@code
 * try (LiteParse parser = new LiteParse()) {
 *     ParseResult result = parser.parse("document.pdf");
 *     String markdown = Markdown.from(result);
 * }
 * }</pre>
 */
public final class Markdown {

    private Markdown() {}

    /** Convert with default options. */
    public static String from(ParseResult result) {
        return from(result, null, MarkdownOptions.defaults());
    }

    /** Convert with the given options. */
    public static String from(ParseResult result, MarkdownOptions options) {
        return from(result, null, options);
    }

    /**
     * Convert with options and (optionally) page screenshots for image embedding.
     *
     * @param result      the parsed document
     * @param screenshots page screenshots (from {@code LiteParse.screenshot}); may be null
     * @param options     generation options
     */
    public static String from(ParseResult result, List<ScreenshotResult> screenshots,
                              MarkdownOptions options) {
        if (result == null) {
            return "";
        }
        HeadingModel headings = HeadingModel.build(result);
        BlockClassifier classifier = new BlockClassifier(headings, options.detectTables());
        MarkdownEmitter emitter = new MarkdownEmitter();

        // Group every page into lines once, then drop document-wide running headers/footers.
        List<List<TextLine>> perPage = new ArrayList<>();
        for (ParsedPage page : result.pages()) {
            perPage.add(LayoutAnalyzer.lines(page));
        }
        Set<String> repeated = LayoutAnalyzer.repeatedLineKeys(perPage);

        List<String> pages = new ArrayList<>();
        for (int p = 0; p < result.pages().size(); p++) {
            ParsedPage page = result.pages().get(p);
            List<TextLine> lines = perPage.get(p);
            if (!repeated.isEmpty()) {
                List<TextLine> kept = new ArrayList<>(lines.size());
                for (TextLine line : lines) {
                    if (!repeated.contains(LayoutAnalyzer.headerKey(line.text()))) {
                        kept.add(line);
                    }
                }
                lines = kept;
            }
            List<Block> blocks = new ArrayList<>(classifier.classify(lines));
            if (options.includeImages() && screenshots != null) {
                screenshots.stream()
                        .filter(s -> s.pageNum() == page.pageNum())
                        .findFirst()
                        .ifPresent(shot -> blocks.add(
                                new Block.Image("Page " + page.pageNum(),
                                        saveImage(shot, options.imageDir()))));
            }
            String md = emitter.emit(blocks);
            if (!md.isBlank()) {
                pages.add(md.strip());
            }
        }
        return pages.isEmpty() ? "" : String.join("\n\n", pages) + "\n";
    }

    private static String saveImage(ScreenshotResult shot, String dir) {
        try {
            Path out = Path.of(dir);
            Files.createDirectories(out);
            String name = "page-" + shot.pageNum() + ".png";
            Files.write(out.resolve(name), shot.image());
            return dir + "/" + name;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write page image", e);
        }
    }
}
