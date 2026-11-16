package com.germanreader.service;

import com.germanreader.exception.DocumentException;
import com.germanreader.exception.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;

final class PdfTextExtractor {

    static final int MAX_PAGES = 300;

    private PdfTextExtractor() {
    }

    static String extract(InputStream pdfBytes, int maxCharacters) {
        // Streams are cached in a temp file, not on the heap; the file itself is bounded by the multipart limit.
        try (PDDocument pdf = Loader.loadPDF(new RandomAccessReadBuffer(pdfBytes), "", null, null,
                IOUtils.createTempFileOnlyStreamCache())) {
            if (pdf.isEncrypted()) {
                throw new DocumentException(ErrorCode.PDF_ENCRYPTED, "Encrypted PDF");
            }
            int pages = pdf.getNumberOfPages();
            if (pages > MAX_PAGES) {
                throw new DocumentException(ErrorCode.UPLOAD_TOO_LARGE, "PDF has " + pages + " pages");
            }
            String text = clean(pagesText(pdf, pages, maxCharacters));
            if (text.isEmpty()) {
                throw new DocumentException(ErrorCode.PDF_NO_TEXT, "PDF with " + pages + " pages has no text");
            }
            if (text.length() > maxCharacters) {
                throw new DocumentException(ErrorCode.UPLOAD_TOO_LARGE, "PDF text has " + text.length() + " characters");
            }
            return text;
        } catch (InvalidPasswordException e) {
            throw new DocumentException(ErrorCode.PDF_ENCRYPTED, "Password-protected PDF");
        } catch (IOException e) {
            throw new DocumentException(ErrorCode.INVALID_UPLOAD, "Unreadable PDF: " + e.getClass().getSimpleName());
        }
    }

    private static String pagesText(PDDocument pdf, int pages, int maxCharacters) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setLineSeparator("\n");
        stripper.setParagraphEnd("\n\n");
        StringBuilder text = new StringBuilder();
        for (int page = 1; page <= pages; page++) {
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            text.append(stripper.getText(pdf)).append("\n\n");
            // Raw text has extra spaces and line breaks; twice the limit is clearly too long either way.
            if (text.length() > 2L * maxCharacters) {
                throw new DocumentException(ErrorCode.UPLOAD_TOO_LARGE, "PDF text exceeds the limit by page " + page);
            }
        }
        return text.toString();
    }

    static String clean(String raw) {
        String text = raw.replace("\r\n", "\n").replace('\r', '\n').replace("\u00AD", "")
                .replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "");
        List<String> paragraphs = new ArrayList<>();
        for (String block : text.split("\n[ \\t]*\n")) {
            StringBuilder paragraph = new StringBuilder();
            for (String line : block.split("\n")) {
                line = line.strip();
                if (line.isEmpty()) {
                    continue;
                }
                int end = paragraph.length();
                if (end > 1 && paragraph.charAt(end - 1) == '-' && Character.isLetter(paragraph.charAt(end - 2))) {
                    if (Character.isLowerCase(line.charAt(0))) {
                        paragraph.setLength(end - 1); // "Bahn-" + "hof"
                    }
                } else if (end > 0) {
                    paragraph.append(' ');
                }
                paragraph.append(line);
            }
            String joined = paragraph.toString().replaceAll("[ \\t]+", " ").strip();
            if (!joined.isEmpty()) {
                paragraphs.add(joined);
            }
        }
        return String.join("\n\n", paragraphs);
    }
}
