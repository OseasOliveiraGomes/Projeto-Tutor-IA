package br.com.sylph.service;

import br.com.sylph.config.ProjectConfig;
import br.com.sylph.config.SettingsService;
import br.com.sylph.model.KnowledgeChunk;
import br.com.sylph.model.RankedChunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Final presentation only: audited payloads and CSV evidence retain their original contents. */
class FinalPresentationIntegrationTest {
    private static final String FORMATS = "PDF, JPG, JPEG e PNG";
    private static final String FORMAT_SENTENCE = "Os arquivos somente poderão ser enviados no formato " + FORMATS + ";";

    @Test void mergesParaphrasedAndExtractiveFormatsWhileRetainingRestrictionsAndRawAuditData() throws Exception {
        KnowledgeChunk page = realPage(34);
        Harness harness = harness(List.of(page));
        String question = "Quem envia a declaração de dispensa e quais formatos são aceitos?";
        String formats = "2. Os formatos de arquivo aceitos são " + FORMATS + "." + cite(page);
        String rejected = "1. O aluno é responsável por enviar a declaração de dispensa." + cite(page);
        String initial = rejected + "\n\n" + formats;
        String literal = rawFormatTail(page) + cite(page);
        assertTrue(literal.contains("34 todas"), "O fixture deve conservar o rodapé documental antes da apresentação");
        AnswerEvidence.Audit first = new AnswerEvidence.Audit(List.of(formats),
            List.of("Unidade 1: atribuicao sem vinculo agente-acao/modalidade"), List.of(), true);
        AnswerEvidence.Audit second = new AnswerEvidence.Audit(List.of(literal), List.of(),
            List.of("Responsável pelo envio não identificado"), true);
        when(harness.ollama().chat(eq(harness.cfg()), eq(question), anyString())).thenReturn(initial);
        when(harness.ollama().auditAnswer(eq(harness.cfg()), eq(question), anyList(), anyString())).thenReturn(first, second);
        when(harness.ollama().repairAnswer(eq(harness.cfg()), eq(question), anyList(), eq(initial), eq(first))).thenReturn(literal);

        RagService.Answer result = harness.service().answer(question);

        assertTrue(result.encontrou());
        for (String format : List.of("PDF", "JPG", "JPEG", "PNG"))
            assertEquals(1, count(result.resposta(), format), "O formato não deve ser repetido: " + format);
        assertTrue(result.resposta().contains("somente"), "A regra restritiva deve sobreviver à deduplicação");
        assertTrue(result.resposta().contains("assinado"));
        assertTrue(result.resposta().contains("legível"));
        assertTrue(result.resposta().contains("sem rasuras"));
        assertFalse(result.resposta().contains("34 todas"));
        assertFalse(AnswerEvidence.CITATION.matcher(result.resposta()).replaceAll("").matches("(?s).*\\b34\\b.*"),
            "O número do rodapé deve desaparecer apenas do conteúdo apresentado, preservando a página da citação");
        assertTrue(result.resposta().contains(cite(page).trim()));
        assertFalse(result.resposta().contains("O aluno é responsável"));
        String notice = fold(afterLastCitation(result.resposta()));
        assertTrue(notice.contains("responsavel") || notice.contains("responsabilidade"));
        assertFalse(notice.contains("formato"), "O aviso deve mencionar o responsável ausente, não os formatos já respondidos");
        assertEquals(harness.sources(), result.fontes(), "As fontes retornadas mantêm o trecho original com o rodapé");
        verify(harness.ollama()).auditAnswer(harness.cfg(), question, harness.sources(), initial);
        verify(harness.ollama()).auditAnswer(harness.cfg(), question, harness.sources(), literal);
        verify(harness.ollama()).repairAnswer(harness.cfg(), question, harness.sources(), initial, first);
        var context = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(harness.ollama()).chat(eq(harness.cfg()), eq(question), context.capture());
        assertTrue(context.getValue().contains(page.texto()), "O contexto de geração permanece idêntico ao CSV");
    }

    @Test void retainsBothProvenancesForRepeatedContentAndPreservesARealFourDayDeadline() throws Exception {
        KnowledgeChunk originalPage = realPage(34);
        KnowledgeChunk deadlinePage = realPage(4);
        KnowledgeChunk duplicatePage = new KnowledgeChunk("fixture-copy", "Manual complementar", 99,
            "recursivo", FORMAT_SENTENCE, new double[]{1});
        Harness harness = harness(List.of(originalPage, duplicatePage, deadlinePage));
        String question = "Quais formatos são aceitos, qual o prazo de cadastro de empresa e o prazo para confirmar o recebimento da declaração?";
        String a = FORMAT_SENTENCE + cite(originalPage);
        String b = FORMAT_SENTENCE + cite(duplicatePage);
        String deadline = "Cadastro de empresa: 4 dias úteis." + cite(deadlinePage);
        List<String> approved = List.of(a, b, deadline);
        String initial = String.join("\n\n", approved);
        String missing = "Prazo para confirmar o recebimento da declaração";
        AnswerEvidence.Audit first = new AnswerEvidence.Audit(approved, List.of(), List.of(missing), true);
        AnswerEvidence.Audit second = new AnswerEvidence.Audit(approved, List.of(), List.of(missing), true);
        when(harness.ollama().chat(eq(harness.cfg()), eq(question), anyString())).thenReturn(initial);
        when(harness.ollama().auditAnswer(eq(harness.cfg()), eq(question), anyList(), anyString())).thenReturn(first, second);
        when(harness.ollama().repairAnswer(eq(harness.cfg()), eq(question), anyList(), eq(initial), eq(first))).thenReturn(initial);

        RagService.Answer result = harness.service().answer(question);

        assertTrue(result.encontrou());
        assertEquals(1, count(result.resposta(), "PDF"), "O conteúdo comum deve aparecer uma única vez");
        assertTrue(result.resposta().contains(cite(originalPage).trim()), "A primeira proveniência não deve ser apagada");
        assertTrue(result.resposta().contains(cite(duplicatePage).trim()), "A segunda proveniência não deve ser apagada");
        assertTrue(result.resposta().contains("4 dias"), "O numeral factual não é um rodapé");
        assertTrue(result.resposta().contains(cite(deadlinePage).trim()));
        String notice = fold(afterLastCitation(result.resposta()));
        assertTrue(notice.contains(fold(missing)), "O aviso deve identificar o prazo específico ainda ausente");
        assertFalse(notice.contains("formato"));
        assertFalse(notice.contains("cadastro de empresa"), "O prazo comprovado não deve entrar no aviso de ausência");
        assertEquals(3, result.fontes().size());
        assertTrue(result.fontes().containsAll(harness.sources()));
        verify(harness.ollama(), times(2)).auditAnswer(harness.cfg(), question, harness.sources(), initial);
    }

    @Test void acceptedSecondAuditPresentsOnlyTheRepairedUnitsWithoutAnAbsenceNotice() throws Exception {
        KnowledgeChunk page = realPage(34);
        Harness harness = harness(List.of(page));
        String question = "Quais formatos de arquivo são aceitos?";
        String formats = "Os formatos de arquivo aceitos são " + FORMATS + "." + cite(page);
        String initial = formats + "\nUma responsabilidade inventada.";
        String literal = rawFormatTail(page) + cite(page);
        AnswerEvidence.Audit first = new AnswerEvidence.Audit(List.of(formats), List.of("Texto sem citacao"), List.of(), true);
        AnswerEvidence.Audit second = new AnswerEvidence.Audit(List.of(literal), List.of(), List.of(), true);
        assertTrue(second.accepted());
        when(harness.ollama().chat(eq(harness.cfg()), eq(question), anyString())).thenReturn(initial);
        when(harness.ollama().auditAnswer(eq(harness.cfg()), eq(question), anyList(), anyString())).thenReturn(first, second);
        when(harness.ollama().repairAnswer(eq(harness.cfg()), eq(question), anyList(), eq(initial), eq(first))).thenReturn(literal);

        RagService.Answer result = harness.service().answer(question);

        assertTrue(result.encontrou());
        assertEquals(1, count(result.resposta(), "PDF"));
        assertFalse(result.resposta().contains("Os formatos de arquivo aceitos"), "O ramo aceito usa somente a segunda auditoria");
        assertTrue(result.resposta().contains("somente"));
        assertTrue(result.resposta().contains("devidamente assinado"));
        assertTrue(afterLastCitation(result.resposta()).replaceAll("[.!?;\\s]+", "").isBlank(),
            "Uma resposta aceita não recebe aviso genérico de ausência");
        assertFalse(result.resposta().contains("34 todas"));
        assertEquals(harness.sources(), result.fontes());
        verify(harness.ollama()).auditAnswer(harness.cfg(), question, harness.sources(), literal);
    }

    @Test void repairTransportFailureRetainsApprovedFormatsAndSpecifiesTheMissingDeadlineAndCommunicationFailure() throws Exception {
        KnowledgeChunk page = realPage(34);
        Harness harness = harness(List.of(page));
        String question = "Quais formatos são aceitos e qual o prazo de análise da solicitação?";
        String formats = "Os formatos de arquivo aceitos são " + FORMATS + "." + cite(page);
        String missing = "Prazo de análise da solicitação";
        AnswerEvidence.Audit first = new AnswerEvidence.Audit(List.of(formats), List.of(), List.of(missing), true);
        when(harness.ollama().chat(eq(harness.cfg()), eq(question), anyString())).thenReturn(formats);
        when(harness.ollama().auditAnswer(eq(harness.cfg()), eq(question), anyList(), eq(formats))).thenReturn(first);
        when(harness.ollama().repairAnswer(eq(harness.cfg()), eq(question), anyList(), eq(formats), eq(first)))
            .thenThrow(new IOException("Offline durante o reparo"));

        RagService.Answer result = harness.service().answer(question);

        assertTrue(result.encontrou());
        assertTrue(result.resposta().contains(FORMATS));
        assertEquals(1, count(result.resposta(), "PDF"));
        String notice = fold(afterLastCitation(result.resposta()));
        assertTrue(notice.contains(fold(missing)), "A falha de transporte não deve esconder qual item continua pendente");
        assertTrue(notice.contains("comunicacao"), "O aviso também deve identificar a falha técnica");
        assertFalse(notice.contains("formato"));
        assertEquals(harness.sources(), result.fontes());
        verify(harness.ollama()).auditAnswer(harness.cfg(), question, harness.sources(), formats);
    }

    private static KnowledgeChunk realPage(int number) throws IOException {
        return new CsvKnowledgeStore(new ObjectMapper()).read("recursivo").stream()
            .filter(chunk -> chunk.pagina() == number).findFirst().orElseThrow();
    }

    private static String rawFormatTail(KnowledgeChunk page) {
        String raw = AnswerEvidence.normalize(page.texto());
        int start = raw.indexOf(FORMAT_SENTENCE);
        assertTrue(start >= 0, "Regra de formatos não encontrada na página real");
        return raw.substring(start);
    }

    private static String cite(KnowledgeChunk page) {
        return " [Fonte: " + page.documento() + " | Página: " + page.pagina() + "]";
    }

    private static String afterLastCitation(String answer) {
        var matcher = AnswerEvidence.CITATION.matcher(answer);
        int last = 0;
        while (matcher.find()) last = matcher.end();
        return answer.substring(last).trim();
    }

    private static long count(String text, String term) {
        return Pattern.compile(Pattern.quote(term)).matcher(text).results().count();
    }

    private static String fold(String text) {
        return Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    }

    private static Harness harness(List<KnowledgeChunk> chunks) throws IOException {
        ProjectConfig cfg = new ProjectConfig();
        SettingsService settings = mock(SettingsService.class);
        HybridRetriever retriever = mock(HybridRetriever.class);
        OllamaClient ollama = mock(OllamaClient.class);
        CsvKnowledgeStore csv = new CsvKnowledgeStore(new ObjectMapper());
        when(settings.load()).thenReturn(cfg);
        when(retriever.retrieve(eq(cfg), anyString())).thenReturn(chunks.stream()
            .map(chunk -> new RankedChunk(chunk, 1, 1, 1)).toList());
        List<RagService.Source> sources = new ArrayList<>();
        for (KnowledgeChunk chunk : chunks) sources.add(new RagService.Source(chunk.documento(), chunk.pagina(), chunk.texto()));
        return new Harness(cfg, ollama, new RagService(settings, retriever, ollama, csv), List.copyOf(sources));
    }

    private record Harness(ProjectConfig cfg, OllamaClient ollama, RagService service, List<RagService.Source> sources) { }
}
