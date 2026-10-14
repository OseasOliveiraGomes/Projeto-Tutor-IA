package br.com.sylph.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

@Service
public class PdfPages {
    public record Page(int number, String text) { }

    public List<Page> extract(Path file) throws IOException {
        List<Page> pages = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(file.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                String cleaned = clean(stripper.getText(doc));
                if (cleaned.length() > 35) pages.add(new Page(p, cleaned));
            }
        }
        return pages;
    }

    public static String clean(String raw) {
        String text = raw.replace("\r", "")
            .replaceAll("(?m)^\\s*Público\\s*$", "")
            .replaceAll("(?m)^\\s*\\d{1,3}\\s*$", "")
            .replaceAll("([\\p{L}])[-‐]\\n([\\p{Ll}])", "$1$2")
            .replaceAll("[\\t ]+", " ")
            .replaceAll("(?m)^[ ]+|[ ]+$", "")
            .replaceAll("\n{3,}", "\n\n")
            .trim();
        return text;
    }
}
