package io.liteparse.markdown.cli;

import io.liteparse.LiteParse;
import io.liteparse.LiteParseConfig;
import io.liteparse.ParseResult;
import io.liteparse.ScreenshotResult;
import io.liteparse.markdown.Markdown;
import io.liteparse.markdown.MarkdownOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * CLI: parse a document with liteparse-java and print Markdown.
 *
 * <pre>
 *   md &lt;file&gt; [--no-ocr] [--no-tables] [--images] [--image-dir DIR] [-o OUT]
 * </pre>
 *
 * Run via Gradle: {@code ./gradlew runCli -PcliArgs="document.pdf"}.
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("-h") || args[0].equals("--help")) {
            System.out.println("""
                Usage: md <file> [--no-ocr] [--no-tables] [--images] [--image-dir DIR] [-o OUT]
                Parses a PDF/Office/image with LiteParse and prints Markdown.""");
            System.exit(args.length == 0 ? 2 : 0);
        }
        String file = args[0];
        boolean ocr = !has(args, "--no-ocr");
        boolean tables = !has(args, "--no-tables");
        boolean images = has(args, "--images");
        String imageDir = value(args, "--image-dir", "images");
        String out = value(args, "-o", null);

        MarkdownOptions options = MarkdownOptions.builder()
                .detectTables(tables)
                .includeImages(images)
                .imageDir(imageDir)
                .build();

        String markdown;
        try (LiteParse parser = new LiteParse(
                LiteParseConfig.builder().ocrEnabled(ocr).quiet(true).build())) {
            ParseResult result = parser.parse(file);
            List<ScreenshotResult> shots = images ? parser.screenshot(file) : null;
            markdown = Markdown.from(result, shots, options);
        }

        if (out != null) {
            Files.writeString(Path.of(out), markdown);
            System.out.println("wrote " + out);
        } else {
            System.out.print(markdown);
        }
    }

    private static boolean has(String[] args, String flag) {
        for (String a : args) {
            if (a.equals(flag)) {
                return true;
            }
        }
        return false;
    }

    private static String value(String[] args, String key, String def) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(key)) {
                return args[i + 1];
            }
        }
        return def;
    }
}
