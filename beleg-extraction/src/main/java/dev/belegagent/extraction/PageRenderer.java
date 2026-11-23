package dev.belegagent.extraction;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

/**
 * Renders PDF pages to PNG with PDFBox, so the vision model always receives images.
 * Only the first {@code maxPages} pages are rendered; receipts and most invoices fit on one or two pages.
 */
public final class PageRenderer {

    private final int dpi;
    private final int maxPages;

    public PageRenderer(int dpi, int maxPages) {
        if (dpi < 72 || dpi > 600) {
            throw new IllegalArgumentException("dpi must be between 72 and 600");
        }
        if (maxPages < 1) {
            throw new IllegalArgumentException("maxPages must be at least 1");
        }
        this.dpi = dpi;
        this.maxPages = maxPages;
    }

    public RenderedPages render(byte[] pdf) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            int totalPages = document.getNumberOfPages();
            int pagesToRender = Math.min(totalPages, maxPages);
            PDFRenderer renderer = new PDFRenderer(document);
            List<byte[]> pngs = new ArrayList<>(pagesToRender);
            for (int i = 0; i < pagesToRender; i++) {
                BufferedImage image = renderer.renderImageWithDPI(i, dpi, ImageType.RGB);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ImageIO.write(image, "png", out);
                pngs.add(out.toByteArray());
            }
            return new RenderedPages(pngs, totalPages);
        } catch (IOException e) {
            throw new ExtractionException("PDF could not be read: " + e.getMessage(), e);
        }
    }

    /**
     * @param pngPages   rendered pages as PNG
     * @param totalPages number of pages in the PDF (may be more than rendered)
     */
    public record RenderedPages(List<byte[]> pngPages, int totalPages) {

        public RenderedPages {
            pngPages = List.copyOf(pngPages);
        }
    }
}
