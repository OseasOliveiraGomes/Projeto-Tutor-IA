package br.com.sylph.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EvidenceRepairRegressionTest {
    private static final String DOC = "Manual";
    private static final RagService.Source P15 = new RagService.Source(DOC, 15,
        "SUPERVISOR ACADÊMICO\nSupervisor Acadêmico, por sua vez, é o representante da Instituição " +
        "de Ensino Superior (IES) encarregado de acompanhar o estágio. Em muitos casos, " +
        "esse supervisor é o tutor da sala de aula.");
    private static final RagService.Source P16 = new RagService.Source(DOC, 16,
        "SUPERVISOR DE CAMPO\nÉ o profissional designado pela empresa concedente " +
        "para orientar e acompanhar o estudante durante a execução do estágio nas instalações da empresa.");

    @Test void eachRepairCandidateIsLiteralAndKeepsItsOwnPage() {
        var pieces = OllamaClient.buildEvidenceSnippets(List.of(P15, P16));
        assertTrue(pieces.stream().anyMatch(p -> p.source().pagina() == 15 && p.text().contains("SUPERVISOR ACADÊMICO")));
        assertTrue(pieces.stream().anyMatch(p -> p.source().pagina() == 16 && p.text().contains("SUPERVISOR DE CAMPO")));
        for (var piece : pieces) {
            assertTrue(AnswerEvidence.normalize(piece.source().trecho()).contains(
                AnswerEvidence.normalize(piece.text())), "Nao literal: " + piece.text());
        }
    }

    @Test void trailingPeriodAfterCitationIsNotAnUncitedClaim() {
        var parsed = AnswerEvidence.parse(
            "O representante acompanha o estágio. [Fonte: Manual | Página: 15].", List.of(P15));
        assertEquals(1, parsed.units().size());
        assertTrue(parsed.issues().isEmpty(), () -> "Issues: " + parsed.issues());
    }

    @Test void aFalseCitationMustRemainUnapproved() throws Exception {
        var parsed = AnswerEvidence.parse(
            "O supervisor de campo atua na empresa. [Fonte: Manual | Página: 15]", List.of(P15, P16));
        var auditJson = new ObjectMapper().readTree("""
            {"claims":[{"id":1,"supported":true,"reason":"teste",
              "quotes":[{"documento":"Manual","pagina":15,
              "texto":"É o profissional designado pela empresa concedente"}]}],
             "missing":[]}
            """);
        var result = AnswerEvidence.decode(auditJson, parsed);
        assertFalse(result.accepted());
        assertTrue(result.approved().isEmpty());
    }

    @Test void literalEvidenceFromBothPagesCanBeAcceptedWithoutFabricatedQuotes() throws Exception {
        var pieces = OllamaClient.buildEvidenceSnippets(List.of(P15, P16));
        var first = pieces.stream().filter(p -> p.source().pagina() == 15).findFirst().orElseThrow();
        var second = pieces.stream().filter(p -> p.source().pagina() == 16).findFirst().orElseThrow();
        String answer = first.text() + " [Fonte: Manual | Página: 15].\n\n"
                      + second.text() + " [Fonte: Manual | Página: 16]";
        var parsed = AnswerEvidence.parse(answer, List.of(P15, P16));
        assertEquals(2, parsed.units().size());
        var auditJson = new ObjectMapper().readTree("""
            {"claims":[
              {"id":1,"supported":true,"quotes":[]},
              {"id":2,"supported":true,"quotes":[]}],
             "missing":[]}
            """);
        var result = AnswerEvidence.decode(auditJson, parsed);
        assertTrue(result.accepted(), () -> "Issues: " + result.issues());
        assertEquals(2, result.approved().size());
    }

    @Test void legacyRepairPayloadKeepsLiteralQuotesAndRejectsInventedText() throws Exception {
        var sources = List.of(P15, P16);
        var snippets = OllamaClient.buildEvidenceSnippets(sources);
        var json = new ObjectMapper().readTree("""
            {"items":[
              {"evidenceId":1,"text":"Supervisor Acadêmico, por sua vez, é o representante da Instituição de Ensino Superior (IES) encarregado de acompanhar o estágio."},
              {"evidenceId":2,"text":"É o profissional designado pela empresa concedente para orientar e acompanhar o estudante durante a execução do estágio nas instalações da empresa."},
              {"evidenceId":1,"text":"Inventado"},
              {"evidenceId":1,"text":"É o profissional designado pela empresa concedente"}
            ],"missing":[]}
            """);
        String repaired = OllamaClient.renderRepairJson(json, sources, snippets);
        assertTrue(repaired.contains("Página: 15]"));
        assertTrue(repaired.contains("Página: 16]"));
        assertFalse(repaired.contains("Inventado"));
        assertEquals(2, AnswerEvidence.parse(repaired, sources).units().size());
    }

    @Test void currentIdOnlyRepairProtocolStillAcceptsOnlyExistingSnippets() throws Exception {
        var sources = List.of(P15, P16);
        var snippets = OllamaClient.buildEvidenceSnippets(sources);
        int id15 = snippets.stream().filter(s -> s.source().pagina() == 15).findFirst().orElseThrow().id();
        int id16 = snippets.stream().filter(s -> s.source().pagina() == 16).findFirst().orElseThrow().id();
        String json = "{\"ids\":[" + id15 + "," + id16 + ",999999],\"missing\":[]}";
        String repaired = OllamaClient.renderRepairJson(new ObjectMapper().readTree(json), sources, snippets);
        assertTrue(repaired.contains("Página: 15]"));
        assertTrue(repaired.contains("Página: 16]"));
        assertEquals(2, AnswerEvidence.parse(repaired, sources).units().size());
    }
}
