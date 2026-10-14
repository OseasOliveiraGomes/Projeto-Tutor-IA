package br.com.sylph.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class AnswerEvidenceTest {
    final ObjectMapper mapper = new ObjectMapper();
    final List<RagService.Source> sources = List.of(new RagService.Source("Manual",3,"A faculdade acompanha o aluno."),
        new RagService.Source("Manual",4,"A empresa orienta o aluno."));
    final String answer = "A faculdade acompanha. [Fonte: Manual | Página: 3]\nA empresa orienta. [Fonte: Manual | Página: 4]";
    String verdict = "{\"claims\":[{\"id\":1,\"supported\":true,\"quotes\":[{\"documento\":\"Manual\",\"pagina\":3,\"texto\":\"A faculdade acompanha o aluno.\"}]},{\"id\":2,\"supported\":true,\"quotes\":[{\"documento\":\"Manual\",\"pagina\":4,\"texto\":\"A empresa orienta o aluno.\"}]}],\"missing\":[]}";
    @Test void approvesTwoPagesAndDifferentCitations() throws Exception {
        var parsed = AnswerEvidence.parse(answer,sources);
        assertEquals(2,parsed.units().size());
        var audit = AnswerEvidence.decode(mapper.readTree(verdict),parsed);
        assertTrue(audit.accepted());
        assertEquals(2,audit.approved().size());
    }
    @Test void rejectsUnsupportedClaimWithoutErasingOtherPage() throws Exception {
        var bad = mapper.readTree(verdict);
        ((com.fasterxml.jackson.databind.node.ObjectNode)bad.path("claims").get(1)).put("supported",false).put("reason","Nao consta");
        var audit = AnswerEvidence.decode(bad,AnswerEvidence.parse(answer,sources));
        assertFalse(audit.accepted());
        assertEquals(1,audit.approved().size());
        assertTrue(audit.issues().getFirst().contains("Nao consta"));
    }
    @Test void literalQuoteMustExistOnCitedPageAndIdsMustBeComplete() throws Exception {
        var bad = mapper.readTree(verdict.replace("A empresa orienta o aluno.","Inventado"));
        assertFalse(AnswerEvidence.decode(bad,AnswerEvidence.parse(answer,sources)).accepted());
        assertFalse(AnswerEvidence.decode(mapper.readTree("{\"claims\":[],\"missing\":[]}"),AnswerEvidence.parse(answer,sources)).valid());
    }
    @Test void approvedLiteralUnitSurvivesAuditorQuoteFormattingError() throws Exception {
        String literal = "A faculdade acompanha o aluno. [Fonte: Manual | P\u00e1gina: 3]";
        var malformedQuote = mapper.readTree("{\"claims\":[{\"id\":1,\"supported\":true,\"quotes\":[]}],\"missing\":[]}");
        assertTrue(AnswerEvidence.decode(malformedQuote,AnswerEvidence.parse(literal,sources)).accepted());
        ((com.fasterxml.jackson.databind.node.ObjectNode)malformedQuote.path("claims").get(0)).put("supported",false);
        assertFalse(AnswerEvidence.decode(malformedQuote,AnswerEvidence.parse(literal,sources)).accepted());
    }
    @Test void groupsMultipleCitationsAndRejectsInventedSources() {
        var both = AnswerEvidence.parse("Duas funcoes. [Fonte: Manual | Página: 3] [Fonte: Manual | Página: 4]",sources);
        assertEquals(1,both.units().size());
        assertEquals(2,both.units().getFirst().evidence().size());
        var bad = AnswerEvidence.parse(answer + " Falso. [Fonte: Outro | Página: 3]",sources);
        assertEquals(2,bad.units().size());
        assertFalse(bad.issues().isEmpty());
    }
}
