package br.com.sylph.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PdfPresentationArtifactsTest {
    private static final Path MANUAL = Path.of("dados", "Manual Estagio Obrigatorio UNOPAR 2026-2.pdf");
    private static RagService.Source source(int page) throws Exception {
        String text = new PdfPages().extract(MANUAL).stream().filter(p -> p.number() == page)
            .findFirst().orElseThrow().text();
        // CSV and PDF names differ; only exact text and physical page resolve it.
        return new RagService.Source("Manual Estágio Obrigatório_Polo-20262.pdf", page, text);
    }

    @Test void removesOnlyThePhysicallyConfirmedInlineFolioFromRealPageThirtyFour() throws Exception {
        var source = source(34);
        String untouched = source.trecho();
        String body = "ATENÇÃO! Os arquivos somente poderão ser enviados no formato PDF, JPG, JPEG e PNG; " +
            "Devem conter\n34 todas a informações, devidamente assinado e legível (sem rasuras).";
        assertTrue(normalize(source.trecho()).contains(normalize(body)), "O caso deve permanecer literal na fonte");
        String rendered = PdfPresentationArtifacts.clean(body, List.of(source));
        assertEquals(body.replace("34 ", ""), rendered);
        assertTrue(rendered.contains("PDF, JPG, JPEG e PNG"));
        assertTrue(rendered.contains("devidamente assinado e legível (sem rasuras)"));
        assertEquals(untouched, source.trecho(), "A apresentação nunca deve reescrever a evidência");
        System.out.println("[APRESENTACAO] folio_fisico_removido=true pagina=34 formatos_e_requisitos_preservados=true");
    }

    @Test void preservesRealPageFourCompanyDeadlineOfFourDays() throws Exception {
        var source = source(4);
        String body = "Cadastro de empresa: 4 dias";
        assertTrue(normalize(source.trecho()).contains(body));
        assertEquals(body, PdfPresentationArtifacts.clean(body, List.of(source)));
        assertEquals(source.trecho(), PdfPresentationArtifacts.clean(source.trecho(), List.of(source)));
    }

    @Test void preservesUnicodeSpacingOutsideTheConfirmedFolio() throws Exception {
        var source=source(34);
        String body="Aviso\u2003Os arquivos somente poderão ser enviados no formato PDF, JPG, JPEG e PNG; " +
            "Devem conter 34 todas a informações, devidamente assinado e legível (sem rasuras).";
        assertEquals(body.replace("34 ",""),PdfPresentationArtifacts.clean(body,List.of(source)));
    }

    @Test void equalPageNumbersWithoutTheLiteralPdfContextStayAvailable() throws Exception {
        var source = source(34);
        for (String body : List.of("O prazo é de 34 dias.", "34", "Página: 34", "Foram enviados 34 arquivos."))
            assertEquals(body, PdfPresentationArtifacts.clean(body, List.of(source)), body);
    }

    @Test void pageNumberAndDocumentTitleAloneCannotAuthorizeRemoval() {
        String body = "Devem conter 34 todas a informações, devidamente assinado.";
        var invented = new RagService.Source("Manual Estágio Obrigatório_Polo-20262.pdf", 34,
            "Devem conter 34 todas a informações, devidamente assinado. Conteúdo não existente no PDF.");
        assertEquals(body, PdfPresentationArtifacts.clean(body, List.of(invented)));
        assertEquals(body, PdfPresentationArtifacts.clean(body, List.of()));
    }

    @Test void leavesTextUntouchedWhenPdfProofIsUnavailable(@TempDir Path directory) {
        String body = "Devem conter 34 todas a informações, devidamente assinado.";
        var source = new RagService.Source("Documento", 34, body);
        assertEquals(body, PdfPresentationArtifacts.clean(body, List.of(source),
            List.of(directory.resolve("inexistente.pdf"))));
    }

    @Test void physicalFoliosAreDistinguishedFromRealEqualNumbersInTheSamePage(@TempDir Path directory) throws Exception {
        Path pdf = directory.resolve("generic.pdf");
        try (var document = new PDDocument()) {
            var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int page = 1; page <= 3; page++) {
                var sheet = new PDPage(new PDRectangle(600, 600));
                document.addPage(sheet);
                try (var content = new PDPageContentStream(document, sheet)) {
                    line(content, font, 16, 70, 400, "Prazo de analise: " + page + " dias.");
                    line(content, font, 16, 70, 58, "Os documentos devem conter");
                    line(content, font, 9, 20, 38, Integer.toString(page));
                    line(content, font, 16, 70, 36, "todos os dados devidamente assinados.");
                }
            }
            document.save(pdf.toFile());
        }
        String text = new PdfPages().extract(pdf).get(1).text();
        var source = new RagService.Source("Any alias", 2, text);
        String normalized = normalize(text);
        assertTrue(normalized.contains("conter 2 todos"), normalized);
        String rendered = PdfPresentationArtifacts.clean(text, List.of(source), List.of(pdf));
        assertTrue(rendered.contains("2 dias."));
        assertFalse(normalize(rendered).contains("conter 2 todos"));
        assertTrue(normalize(rendered).contains("conter todos os dados"));
        assertEquals(normalized, normalize(source.trecho()));
    }

    private static void line(PDPageContentStream content, PDType1Font font, float size,
                             float x, float y, String text) throws Exception {
        content.beginText(); content.setFont(font, size); content.newLineAtOffset(x, y);
        content.showText(text); content.endText();
    }

    private static String normalize(String text) { return text.replaceAll("\\s+", " ").trim(); }
}
