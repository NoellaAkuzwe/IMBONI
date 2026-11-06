package dev.noella.imboni.ingest;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns an upload into page text.
 * <ul>
 *   <li>.md / .txt: pages are separated by form feeds or "--- page N ---" lines. A first line of
 *   {@code <!-- source: scan -->} marks text that came out of OCR (the dummy scanned worksheets).</li>
 *   <li>.pdf: PDFBox text layer per page. Pages with (almost) no text layer are treated as scans and
 *   sent to the {@code tesseract} CLI if it is installed; otherwise they're flagged, not silently dropped.</li>
 * </ul>
 */
public class Extractor {

    private static final Pattern PAGE_MARK = Pattern.compile("(?m)^---\\s*page\\s*(\\d+)\\s*---\\s*$");

    public ExtractedDocument extract(String filename, byte[] bytes) throws IOException {
        String name = filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".pdf")) return extractPdf(bytes);
        if (name.endsWith(".md") || name.endsWith(".txt")) return extractText(new String(bytes, StandardCharsets.UTF_8));
        throw new UnsupportedDocumentException("unsupported file type: " + filename + " (use .pdf, .md or .txt)");
    }

    ExtractedDocument extractText(String raw) {
        boolean scanned = raw.stripLeading().startsWith("<!-- source: scan -->");
        String body = raw.replaceFirst("^\\s*<!--.*?-->\\s*", "");
        List<ExtractedDocument.Page> pages = new ArrayList<>();
        if (body.contains("\f")) {
            String[] parts = body.split("\f");
            for (int i = 0; i < parts.length; i++) pages.add(new ExtractedDocument.Page(i + 1, parts[i].strip()));
        } else {
            Matcher m = PAGE_MARK.matcher(body);
            int lastEnd = 0, lastPage = 1;
            boolean any = false;
            while (m.find()) {
                String before = body.substring(lastEnd, m.start()).strip();
                if (!before.isEmpty()) pages.add(new ExtractedDocument.Page(lastPage, before));
                lastPage = Integer.parseInt(m.group(1));
                lastEnd = m.end();
                any = true;
            }
            String rest = body.substring(lastEnd).strip();
            if (!rest.isEmpty() || !any) pages.add(new ExtractedDocument.Page(lastPage, rest));
        }
        return new ExtractedDocument(pages, scanned, List.of());
    }

    private ExtractedDocument extractPdf(byte[] bytes) throws IOException {
        List<ExtractedDocument.Page> pages = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean scanned = false;
        try (PDDocument pdf = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true); // keeps reading order closer to the visual layout
            PDFRenderer renderer = new PDFRenderer(pdf);
            for (int p = 1; p <= pdf.getNumberOfPages(); p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                String text = stripper.getText(pdf).strip();
                if (text.length() < 20) {
                    scanned = true;
                    String ocr = tryOcr(renderer, p - 1);
                    if (ocr == null) {
                        warnings.add("no_text_layer:page " + p + " (OCR unavailable; install tesseract)");
                    } else {
                        warnings.add("ocr_used:page " + p);
                        text = ocr;
                    }
                }
                pages.add(new ExtractedDocument.Page(p, text));
            }
        }
        return new ExtractedDocument(pages, scanned, warnings);
    }

    /** Runs tesseract on one rendered page. Returns null if tesseract isn't available or fails. */
    private String tryOcr(PDFRenderer renderer, int pageIndex) {
        Path img = null, out = null;
        try {
            img = Files.createTempFile("imboni-page", ".png");
            out = Files.createTempFile("imboni-ocr", "");
            ImageIO.write(renderer.renderImageWithDPI(pageIndex, 300, ImageType.GRAY), "png", img.toFile());
            Process proc = new ProcessBuilder("tesseract", img.toString(), out.toString())
                    .redirectErrorStream(true).start();
            if (!proc.waitFor(60, TimeUnit.SECONDS) || proc.exitValue() != 0) return null;
            return Files.readString(Path.of(out + ".txt")).strip();
        } catch (IOException | InterruptedException e) {
            return null;
        } finally {
            try {
                if (img != null) Files.deleteIfExists(img);
                if (out != null) {
                    Files.deleteIfExists(out);
                    Files.deleteIfExists(Path.of(out + ".txt"));
                }
            } catch (IOException ignored) {
            }
        }
    }

    public static class UnsupportedDocumentException extends RuntimeException {
        public UnsupportedDocumentException(String msg) {
            super(msg);
        }
    }
}
