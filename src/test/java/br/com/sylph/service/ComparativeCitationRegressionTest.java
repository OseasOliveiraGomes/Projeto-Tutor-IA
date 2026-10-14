package br.com.sylph.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Regression for the live Qwen false-positive: both roles attributed to p.15. */
class ComparativeCitationRegressionTest {
    private final ObjectMapper json = new ObjectMapper();
    private static final RagService.Source P15 = new RagService.Source("Manual", 15,
        "SUPERVISOR ACADÊMICO\nSupervisor Acadêmico, por sua vez, é o representante da " +
        "Instituição de Ensino Superior (IES) encarregado de acompanhar o estágio. " +
        "Em muitos casos, esse supervisor é o tutor da sala de aula. " +
        "Já nos cursos de Biomedicina, Enfermagem, Farmácia, Fisioterapia, Nutrição e " +
        "Serviço Social o processo é um pouco diferente.");
    private static final RagService.Source P16 = new RagService.Source("Manual", 16,
        "SUPERVISOR DE CAMPO\nÉ o profissional designado pela empresa concedente " +
        "para orientar e acompanhar o estudante durante a execução do estágio nas instalações da empresa.");

    @Test void rejectLiveFailureDespiteAuditorSupportedTrueAndLiteralQuotes() throws Exception {
        String answer = "1. O supervisor acadêmico é o representante da Instituição de Ensino Superior, " +
            "enquanto o supervisor de campo é o profissional designado pela empresa concedente. " +
            "[Fonte: Manual | Página: 15]";
        var parsed = AnswerEvidence.parse(answer, List.of(P15, P16));
        var verdict = json.readTree("""
            {"claims":[{"id":1,"supported":true,"reason":"Apoiado",
              "quotes":[
                {"documento":"Manual","pagina":15,"texto":"SUPERVISOR ACADÊMICO\\nSupervisor Acadêmico, por sua vez, é o representante da Instituição de Ensino Superior (IES) encarregado de acompanhar o estágio."},
                {"documento":"Manual","pagina":15,"texto":"Já nos cursos de Biomedicina, Enfermagem, Farmácia, Fisioterapia, Nutrição e Serviço Social o processo é um pouco diferente."}
              ]}],"missing":[]}
            """);
        var audit = AnswerEvidence.decode(verdict, parsed);
        assertFalse(audit.accepted(), "The comparative answer must not be approved with page 15 alone");
        assertTrue(audit.approved().isEmpty());
        assertTrue(audit.issues().toString().contains("supervisor de campo"), audit.issues().toString());
    }

    @Test void acceptComparisonWhenAuditorQuotesBothActualPages() throws Exception {
        String answer = "1. O supervisor acadêmico é representante da IES, enquanto " +
            "o supervisor de campo é profissional designado pela empresa. " +
            "[Fonte: Manual | Página: 15] [Fonte: Manual | Página: 16]";
        var parsed = AnswerEvidence.parse(answer, List.of(P15, P16));
        var verdict = json.readTree("""
            {"claims":[{"id":1,"supported":true,"reason":"Apoiado",
              "quotes":[
                {"documento":"Manual","pagina":15,"texto":"SUPERVISOR ACADÊMICO\\nSupervisor Acadêmico, por sua vez, é o representante da Instituição de Ensino Superior (IES) encarregado de acompanhar o estágio."},
                {"documento":"Manual","pagina":16,"texto":"SUPERVISOR DE CAMPO\\nÉ o profissional designado pela empresa concedente para orientar e acompanhar o estudante durante a execução do estágio nas instalações da empresa."}
              ]}],"missing":[]}
            """);
        var audit = AnswerEvidence.decode(verdict, parsed);
        assertTrue(audit.accepted(), () -> audit.issues().toString());
    }

    @Test void separateSinglePageAssertionsContinueToWork() throws Exception {
        var parsed = AnswerEvidence.parse(
            "Supervisor Acadêmico, por sua vez, é o representante da Instituição de Ensino Superior (IES) encarregado de acompanhar o estágio. [Fonte: Manual | Página: 15]\n\n" +
            "É o profissional designado pela empresa concedente para orientar e acompanhar o estudante durante a execução do estágio nas instalações da empresa. [Fonte: Manual | Página: 16]",
            List.of(P15,P16));
        var verdict = json.readTree("""
            {"claims":[{"id":1,"supported":true,"quotes":[]},{"id":2,"supported":true,"quotes":[]}],"missing":[]}
            """);
        assertTrue(AnswerEvidence.decode(verdict, parsed).accepted());
    }
}
