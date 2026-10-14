package br.com.sylph.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Positive LLM verdicts cannot replace evidence for an actor, action or modality. */
class ResponsibilityEvidenceRegressionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final RagService.Source DISPENSA = new RagService.Source("Manual", 34,
        "O envio é realizado através do sistema Colaborar. " +
        "Preencha os dados de projeto, nome do aluno ou matrícula e insira o arquivo no campo indicado. " +
        "Os arquivos somente poderão ser enviados no formato PDF, JPG, JPEG e PNG.");

    private String cited(String text, RagService.Source... sources) {
        StringBuilder result = new StringBuilder(text);
        for (var source : sources) result.append(" [Fonte: ").append(source.documento())
            .append(" | Página: ").append(source.pagina()).append(']');
        return result.toString();
    }

    private Map<String, Object> quote(RagService.Source source, String text) {
        return Map.of("documento", source.documento(), "pagina", source.pagina(), "texto", text);
    }

    private JsonNode verdict(List<Map<String, Object>> claims) {
        return mapper.valueToTree(Map.of("claims", claims, "missing", List.of()));
    }

    private Map<String, Object> claim(int id, boolean supported, List<Map<String, Object>> quotes) {
        return Map.of("id", id, "supported", supported, "reason", "O auditor declarou apoio", "quotes", quotes);
    }

    private AnswerEvidence.Audit audit(String text, List<RagService.Source> sources,
                                       List<Map<String, Object>> quotes) {
        return AnswerEvidence.decode(verdict(List.of(claim(1, true, quotes))),
            AnswerEvidence.parse(text, sources));
    }

    @Test void passiveSubmissionAndStudentNameDoNotAssignStudentResponsibility() {
        String answer = cited("O aluno é responsável por enviar a declaração de dispensa pelo sistema Colaborar.", DISPENSA);
        for (String quote : List.of("O envio é realizado através do sistema Colaborar.", DISPENSA.trecho())) {
            var result = audit(answer, List.of(DISPENSA), List.of(quote(DISPENSA, quote)));
            assertFalse(result.accepted(), "A passiva e o nome no formulário não identificam o executor");
            assertTrue(result.approved().isEmpty());
            assertFalse(result.issues().isEmpty());
        }
    }

    @Test void responsibilityGuardIsIndependentOfActorDocumentAndPage() {
        var source = new RagService.Source("Guia de Inscrição", 87,
            "O envio é realizado através do portal. Preencha o nome da secretaria e o código do polo.");
        for (String actor : List.of("A secretaria", "O polo")) {
            var result = audit(cited(actor + " é responsável por enviar a declaração pelo portal.", source),
                List.of(source), List.of(quote(source, source.trecho())));
            assertFalse(result.accepted(), actor);
        }
    }

    @Test void nameAsObjectDoesNotReplaceExplicitDifferentExecutor() {
        var source = new RagService.Source("Procedimento", 7,
            "A empresa deve enviar os dados do coordenador.");
        var result = audit(cited("O coordenador é responsável por enviar os dados.", source),
            List.of(source), List.of(quote(source, source.trecho())));
        assertFalse(result.accepted());
        assertTrue(result.approved().isEmpty());
    }

    @Test void actorsAndActionsCannotBeSwappedWithinTheSameEvidence() {
        var source = new RagService.Source("Procedimento", 9,
            "A empresa deve enviar a declaração. A secretaria deve arquivar a declaração.");
        var result = audit(cited("A empresa deve arquivar a declaração.", source),
            List.of(source), List.of(quote(source, source.trecho())));
        assertFalse(result.accepted());
    }

    @Test void permissionOrNeutralProcedureDoesNotProveObligation() {
        for (String text : List.of("A empresa pode enviar a declaração.",
                "O envio da declaração é realizado através do portal. Informe o nome da empresa.")) {
            var source = new RagService.Source("Procedimento", 12, text);
            var result = audit(cited("A empresa deve enviar a declaração.", source),
                List.of(source), List.of(quote(source, source.trecho())));
            assertFalse(result.accepted(), text);
        }
    }

    @Test void mentioningAnActorOrAnotherObligationDoesNotGrantPermission() {
        var source = new RagService.Source("Procedimento", 18,
            "Informe o nome do fiscal. A secretaria deve enviar a declaração.");
        var result = audit(cited("O fiscal pode enviar a declaração.", source),
            List.of(source), List.of(quote(source, source.trecho())));
        assertFalse(result.accepted());
    }

    @Test void negatedAttributionsDoNotSupportAffirmativeObligationOrPermission() {
        for (String modal : List.of("deve", "pode")) {
            var source = new RagService.Source("Procedimento", 20,
                "A empresa não " + modal + " enviar a declaração.");
            var result = audit(cited("A empresa " + modal + " enviar a declaração.", source),
                List.of(source), List.of(quote(source, source.trecho())));
            assertFalse(result.accepted(), modal);
        }
    }

    @Test void acceptsExplicitActorActionInActiveAndPassiveVoice() {
        for (String text : List.of("A empresa deve enviar a declaração.",
                "A declaração deve ser enviada pela empresa.")) {
            var source = new RagService.Source("Procedimento", 22, text);
            var result = audit(cited("A empresa deve enviar a declaração.", source),
                List.of(source), List.of(quote(source, source.trecho())));
            assertTrue(result.accepted(), () -> text + ": " + result.issues());
        }
    }

    @Test void acceptsActorAndChannelSupportedByDifferentQuotesOnOnePage() {
        var source = new RagService.Source("Procedimento", 25,
            "A empresa deve enviar a declaração. O envio é realizado através do sistema Colaborar.");
        var result = audit(cited("A empresa deve enviar a declaração pelo sistema Colaborar.", source),
            List.of(source), List.of(
                quote(source, "A empresa deve enviar a declaração."),
                quote(source, "O envio é realizado através do sistema Colaborar.")));
        assertTrue(result.accepted(), () -> result.issues().toString());
    }

    @Test void acceptsCombinedAttributionOnlyWhenBothEvidencePagesAreCited() {
        var actor = new RagService.Source("Procedimento", 26, "A empresa deve enviar a declaração.");
        var channel = new RagService.Source("Procedimento", 27,
            "O envio da declaração é realizado através do sistema Colaborar.");
        var quotes = List.of(quote(actor, actor.trecho()), quote(channel, channel.trecho()));
        String factual = "A empresa deve enviar a declaração pelo sistema Colaborar.";
        var complete = audit(cited(factual, actor, channel), List.of(actor, channel), quotes);
        assertTrue(complete.accepted(), () -> complete.issues().toString());
        var incomplete = audit(cited(factual, channel), List.of(actor, channel), quotes);
        assertFalse(incomplete.accepted());
    }

    @Test void rejectingInventedActorPreservesSeparateSupportedChannelAndFormats() {
        String bad = cited("O aluno é responsável por enviar a declaração.", DISPENSA);
        String good = cited("O envio é realizado através do sistema Colaborar. " +
            "Os arquivos somente poderão ser enviados no formato PDF, JPG, JPEG e PNG.", DISPENSA);
        var result = AnswerEvidence.decode(verdict(List.of(
                claim(1, true, List.of(quote(DISPENSA, DISPENSA.trecho()))),
                claim(2, true, List.of(quote(DISPENSA, DISPENSA.trecho()))))),
            AnswerEvidence.parse(bad + "\n\n" + good, List.of(DISPENSA)));
        assertFalse(result.accepted());
        assertEquals(List.of(good), result.approved());
        assertFalse(result.issues().isEmpty());
    }

    @Test void repairIdsPreserveChannelAndFormatsWithoutInventingAnActor() {
        var sources = List.of(DISPENSA);
        var snippets = OllamaClient.buildEvidenceSnippets(sources);
        var ids = snippets.stream().filter(s -> s.text().startsWith("O envio") ||
            s.text().startsWith("Os arquivos")).map(OllamaClient.EvidenceSnippet::id).toList();
        String repaired = OllamaClient.renderRepairJson(
            mapper.valueToTree(Map.of("ids", ids, "missing", List.of())), sources, snippets);
        assertTrue(repaired.contains("sistema Colaborar"));
        assertTrue(repaired.contains("PDF, JPG, JPEG e PNG"));
        assertFalse(repaired.contains("aluno"));
        assertFalse(repaired.contains("responsável"));
        var parsed = AnswerEvidence.parse(repaired, sources);
        assertTrue(parsed.issues().isEmpty());
        var claims = parsed.units().stream().map(u -> claim(u.id(), true, List.of(quote(DISPENSA,
            AnswerEvidence.CITATION.matcher(u.text()).replaceAll("").trim())))).toList();
        assertTrue(AnswerEvidence.decode(verdict(claims), parsed).accepted());
    }

    @Test void legacyRepairTextCannotAddAnActorToLiteralProcedure() {
        var payload = mapper.valueToTree(Map.of("items", List.of(
            Map.of("evidenceId", 1, "text", "O aluno é responsável por enviar a declaração."),
            Map.of("evidenceId", 1, "text", "O envio é realizado através do sistema Colaborar.")),
            "missing", List.of()));
        String repaired = OllamaClient.renderRepairJson(payload, List.of(DISPENSA),
            OllamaClient.buildEvidenceSnippets(List.of(DISPENSA)));
        assertFalse(repaired.contains("aluno"));
        assertFalse(repaired.contains("responsável"));
        assertTrue(repaired.contains("sistema Colaborar"));
    }

    @Test void literalAttributionStillRequiresPositiveSemanticAudit() {
        var source = new RagService.Source("Procedimento", 28, "A empresa deve enviar a declaração.");
        var result = AnswerEvidence.decode(verdict(List.of(claim(1, false, List.of(quote(source, source.trecho()))))),
            AnswerEvidence.parse(cited(source.trecho(), source), List.of(source)));
        assertFalse(result.accepted());
        assertTrue(result.approved().isEmpty());
    }

    @Test void supportsExplicitRoleIdentityAcrossCitedPages() {
        var identity = new RagService.Source("Procedimento", 40,
            "A secretaria é o setor responsável pelo cadastro.");
        var action = new RagService.Source("Procedimento", 41,
            "O setor responsável pelo cadastro deve enviar a declaração.");
        var result = audit(cited("A secretaria deve enviar a declaração.", identity, action),
            List.of(identity,action), List.of(quote(identity,identity.trecho()),quote(action,action.trecho())));
        assertTrue(result.accepted(), () -> result.issues().toString());
    }

    @Test void quotationCannotCropAnAttributionOutOfItsNegationOrCondition() {
        for (String text : List.of("Não é verdade que a empresa deve enviar a declaração.",
                "Se o contrato estiver ativo, a empresa deve enviar a declaração.")) {
            var source = new RagService.Source("Procedimento", 45,text);
            var result = audit(cited("A empresa deve enviar a declaração.",source),List.of(source),
                List.of(quote(source,"a empresa deve enviar a declaração.")));
            assertFalse(result.accepted(),text);
        }
    }

    @Test void performingAnActionDoesNotProveAssignedResponsibility() {
        var source = new RagService.Source("Procedimento",46,"A empresa enviou a declaração.");
        assertFalse(audit(cited("A empresa é responsável por enviar a declaração.",source),
            List.of(source),List.of(quote(source,source.trecho()))).accepted());
    }

    @Test void exemptionDoesNotMeanProhibition() {
        var source = new RagService.Source("Procedimento",47,"A empresa não precisa enviar a declaração.");
        assertFalse(audit(cited("A empresa não deve enviar a declaração.",source),
            List.of(source),List.of(quote(source,source.trecho()))).accepted());
        assertFalse(audit(cited("A empresa não pode enviar a declaração.",source),
            List.of(source),List.of(quote(source,source.trecho()))).accepted());
    }

    @Test void eachActorInACoordinatedClaimNeedsItsOwnActionWitness() {
        var source = new RagService.Source("Procedimento",48,"A empresa deve enviar a declaração.");
        String answer = cited("A empresa deve enviar a declaração e o fiscal deve assinar a declaração.",source);
        assertFalse(audit(answer,List.of(source),List.of(quote(source,source.trecho()))).accepted());
    }

    @Test void literalFragmentCannotOverrideNegatedSourceContext() {
        var source = new RagService.Source("Procedimento",49,
            "Não é verdade que a empresa deve enviar a declaração.");
        String answer = cited("a empresa deve enviar a declaração.",source);
        var result = AnswerEvidence.decode(verdict(List.of(claim(1,true,List.of()))),
            AnswerEvidence.parse(answer,List.of(source)));
        assertFalse(result.accepted());
    }

    @Test void conditionFollowingAnActionCannotBeDropped() {
        var source = new RagService.Source("Procedimento",50,
            "A empresa deve enviar a declaração se o contrato estiver ativo.");
        assertFalse(audit(cited("A empresa deve enviar a declaração.",source),List.of(source),
            List.of(quote(source,"A empresa deve enviar a declaração"))).accepted());
        assertTrue(audit(cited(source.trecho(),source),List.of(source),
            List.of(quote(source,source.trecho()))).accepted());
    }
}
