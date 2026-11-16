package com.germanreader;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

public final class TestPdfs {

    private TestPdfs() {
    }

    public static byte[] text(List<List<String>> pages) {
        try (PDDocument pdf = textDocument(pages)) {
            return bytes(pdf);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] text(String... lines) {
        return text(List.of(List.of(lines)));
    }

    public static byte[] imageOnly() {
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage();
            pdf.addPage(page);
            BufferedImage scan = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
            PDImageXObject image = LosslessFactory.createFromImage(pdf, scan);
            try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                content.drawImage(image, 60, 500, 400, 200);
            }
            return bytes(pdf);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] withoutPages() {
        try (PDDocument pdf = new PDDocument()) {
            return bytes(pdf);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] encrypted(String userPassword) {
        try (PDDocument pdf = textDocument(List.of(List.of("Geheimer Text.")))) {
            StandardProtectionPolicy policy = new StandardProtectionPolicy("owner-secret", userPassword, new AccessPermission());
            policy.setEncryptionKeyLength(128);
            pdf.protect(policy);
            return bytes(pdf);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static PDDocument textDocument(List<List<String>> pages) throws IOException {
        PDDocument pdf = new PDDocument();
        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        for (List<String> lines : pages) {
            PDPage page = new PDPage();
            pdf.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                content.beginText();
                content.setFont(font, 11);
                content.setLeading(14);
                content.newLineAtOffset(60, 740);
                for (String line : lines) {
                    content.showText(line);
                    content.newLine();
                }
                content.endText();
            }
        }
        return pdf;
    }

    private static byte[] bytes(PDDocument pdf) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        pdf.save(out);
        return out.toByteArray();
    }
}
