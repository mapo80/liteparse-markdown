package io.liteparse.markdown.cli;

import io.liteparse.LiteParse;
import io.liteparse.LiteParseConfig;
import io.liteparse.ParseResult;
import io.liteparse.ScreenshotResult;
import io.liteparse.markdown.Markdown;
import io.liteparse.markdown.MarkdownOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * CLI: parse a document with liteparse-java and emit Markdown.
 *
 * <pre>
 *   md &lt;file&gt; [--no-ocr] [--no-tables] [--images] [--image-dir DIR] [--timing] [-o OUT]
 *   md --batch OUTDIR [--no-ocr] [--no-tables] [--timing] file1.pdf file2.pdf ...
 * </pre>
 *
 * Single-file mode prints Markdown to stdout (or {@code -o}); {@code --timing} prints
 * {@code {"parseMs":..,"markdownMs":..}} on stderr. Batch mode processes many files in one JVM
 * (parser/native init amortized + a warmup run) and, with {@code --timing}, prints one JSON line
 * per file on stderr — used by the benchmark to measure parse (liteparse-java) vs convert
 * (liteparse-markdown) time fairly.
 *
 * Run via Gradle: {@code ./gradlew runCli -PcliArgs="document.pdf"}.
 */
public final class Main {

    private static final Set<String> VALUE_FLAGS = Set.of("--image-dir", "-o", "--batch");

    private Main() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("-h") || args[0].equals("--help")) {
            System.out.println("""
                Usage:
                  md <file> [--no-ocr] [--no-tables] [--images] [--image-dir DIR] [--timing] [-o OUT]
                  md --batch OUTDIR [--no-ocr] [--no-tables] [--timing] file1 file2 ...
                Parses a PDF/Office/image with LiteParse and emits Markdown.
                --timing prints {"parseMs":..,"markdownMs":..} (per file) on stderr.""");
            System.exit(args.length == 0 ? 2 : 0);
        }

        boolean ocr = !has(args, "--no-ocr");
        boolean tables = !has(args, "--no-tables");
        boolean images = has(args, "--images");
        boolean timing = has(args, "--timing");
        String imageDir = value(args, "--image-dir", "images");
        String out = value(args, "-o", null);
        String batchDir = value(args, "--batch", null);
        List<String> files = positionals(args);

        MarkdownOptions options = MarkdownOptions.builder()
                .detectTables(tables).includeImages(images).imageDir(imageDir).build();

        try (LiteParse parser = new LiteParse(
                LiteParseConfig.builder().ocrEnabled(ocr).quiet(true).build())) {
            if (batchDir != null) {
                runBatch(parser, options, images, timing, files, batchDir);
            } else {
                runSingle(parser, options, images, timing, files.get(0), out);
            }
        }
    }

    private static void runSingle(LiteParse parser, MarkdownOptions options, boolean images,
                                  boolean timing, String file, String out) throws Exception {
        Timed t = convert(parser, options, images, file);
        if (timing) {
            System.err.printf(Locale.ROOT, "{\"parseMs\": %.3f, \"markdownMs\": %.3f}%n",
                    t.parseMs, t.markdownMs);
        }
        if (out != null) {
            Files.writeString(Path.of(out), t.markdown);
            System.out.println("wrote " + out);
        } else {
            System.out.print(t.markdown);
        }
    }

    private static void runBatch(LiteParse parser, MarkdownOptions options, boolean images,
                                 boolean timing, List<String> files, String outDir) throws Exception {
        if (files.isEmpty()) {
            System.err.println("no input files for --batch");
            System.exit(2);
        }
        Path dir = Path.of(outDir);
        Files.createDirectories(dir);
        // Warmup so native/JVM init does not skew the first file's timing.
        try {
            convert(parser, options, images, files.get(0));
        } catch (Exception ignored) {
            // a bad first file shouldn't abort the run
        }
        for (String file : files) {
            String stem = Path.of(file).getFileName().toString().replace(".pdf", "");
            try {
                Timed t = convert(parser, options, images, file);
                Files.writeString(dir.resolve(stem + ".md"), t.markdown);
                if (timing) {
                    System.err.printf(Locale.ROOT,
                            "{\"file\": \"%s\", \"parseMs\": %.3f, \"markdownMs\": %.3f}%n",
                            stem, t.parseMs, t.markdownMs);
                }
            } catch (Exception e) {
                System.err.printf(Locale.ROOT, "{\"file\": \"%s\", \"error\": \"%s\"}%n",
                        stem, String.valueOf(e.getMessage()).replace('"', '\''));
                Files.writeString(dir.resolve(stem + ".md"), "");
            }
        }
    }

    private record Timed(String markdown, double parseMs, double markdownMs) {}

    private static Timed convert(LiteParse parser, MarkdownOptions options, boolean images,
                                 String file) {
        long t0 = System.nanoTime();
        ParseResult result = parser.parse(file);                 // liteparse-java (native PDFium)
        long t1 = System.nanoTime();
        List<ScreenshotResult> shots = images ? parser.screenshot(file) : null;
        long t2 = System.nanoTime();
        String md = Markdown.from(result, shots, options);        // liteparse-markdown (this lib)
        long t3 = System.nanoTime();
        return new Timed(md, (t1 - t0) / 1_000_000.0, (t3 - t2) / 1_000_000.0);
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

    /** Positional (non-flag) arguments, skipping value-flag values. */
    private static List<String> positionals(String[] args) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (VALUE_FLAGS.contains(a)) {
                i++; // skip its value
            } else if (!a.startsWith("-")) {
                out.add(a);
            }
        }
        return out;
    }
}
