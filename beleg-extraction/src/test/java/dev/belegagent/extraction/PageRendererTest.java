package dev.belegagent.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

class PageRendererTest {

    @Test
    void rendersPagesToPngUpToMaxPages() throws IOException {
        byte[] pdf = pdfWithPages(3);

        PageRenderer.RenderedPages pages = new PageRenderer(100, 2).render(pdf);

        assertThat(pages.totalPages()).isEqualTo(3);
        assertThat(pages.pngPages()).hasSize(2);
        assertThat(FileKind.detect(pages.pngPages().getFirst())).contains(FileKind.PNG);
    }

    @Test
    void brokenPdfRaisesExtractionException() {
        assertThatThrownBy(() -> new PageRenderer(100, 1).render("%PDF-1.7 broken".getBytes()))
                .isInstanceOf(ExtractionException.class);
    }

    private static byte[] pdfWithPages(int count) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < count; i++) {
                PDPage page = new PDPage(PDRectangle.A6);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.addRect(20, 20, 100, 50);
                    cs.fill();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
