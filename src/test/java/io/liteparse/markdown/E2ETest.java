package io.liteparse.markdown;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.liteparse.LiteParse;
import io.liteparse.LiteParseConfig;
import io.liteparse.ParseResult;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/**
 * End-to-end: parse a real PDF with liteparse-java (native bundle on the test classpath) and
 * convert it to Markdown.
 */
class E2ETest {

    @Test
    void convertsRealPdfToMarkdown() throws Exception {
        byte[] pdf;
        try (InputStream in = E2ETest.class.getResourceAsStream("/sample.pdf")) {
            assertNotNull(in, "test resource /sample.pdf must exist");
            pdf = in.readAllBytes();
        }

        String markdown;
        try (LiteParse parser = new LiteParse(
                LiteParseConfig.builder().ocrEnabled(false).quiet(true).build())) {
            ParseResult result = parser.parse(pdf);
            markdown = Markdown.from(result);
        }

        assertFalse(markdown.isBlank(), "expected non-empty Markdown");
        assertTrue(markdown.contains("Sample"), () -> "expected 'Sample' in:\n" + markdown);
        // The large title line should become a heading.
        assertTrue(markdown.contains("# "), () -> "expected a heading in:\n" + markdown);
    }
}
