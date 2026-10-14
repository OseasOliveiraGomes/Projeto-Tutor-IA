package br.com.sylph.service;

import br.com.sylph.config.ProjectConfig;
import br.com.sylph.config.SettingsService;
import br.com.sylph.model.KnowledgeChunk;
import br.com.sylph.model.RankedChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SocialInteractionRoutingTest {
    private final SettingsService settings = mock(SettingsService.class);
    private final HybridRetriever retriever = mock(HybridRetriever.class);
    private final OllamaClient ollama = mock(OllamaClient.class);
    private final CsvKnowledgeStore store = mock(CsvKnowledgeStore.class);
    private final ProjectConfig cfg = new ProjectConfig();
    private final RagService rag = new RagService(settings, retriever, ollama, store);

    @ParameterizedTest
    @ValueSource(strings = {
        "Oi", "Olá", "Opa", "Bom dia", "Boa tarde", "Boa noite", "Tudo bem?", "Como vai?"
    })
    void simpleGreetingsDoNotUseDocumentalServices(String message) throws Exception {
        assertSocialWithoutRag(message);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Obrigado", "Obrigada", "Valeu", "Muito obrigado!", "Muito obrigada!"})
    void thanksDoNotUseDocumentalServices(String message) throws Exception {
        assertSocialWithoutRag(message);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Tchau", "Até mais"})
    void farewellsDoNotUseDocumentalServices(String message) throws Exception {
        assertSocialWithoutRag(message);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "  OI!!!  ", "OLÁ!", "ola", "Ola\u0301!", " BOM   DIA! ", "boa\ttarde",
        "\u00a0Boa\u2003noite!\u00a0", "TUDO BEM???", "Como VAI?!", "OBRIGADA!!!",
        "ATE MAIS!", "Ate\u0301 mais…", "\n\tOpa!\r\n", "Bom\u0085dia"
    })
    void accentsCasePunctuationAndUnicodeSpacesAreHandled(String message) throws Exception {
        assertSocialWithoutRag(message);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Oi, tudo bem? Obrigado!", "Olá! Como vai?", "Boa tarde, tudo bem?", "Valeu! Até mais!"
    })
    void composedSocialMessagesStillSkipRag(String message) throws Exception {
        assertSocialWithoutRag(message);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Oi, qual é o prazo para cadastrar uma empresa?",
        "Boa tarde, como funciona o seguro de estágio?",
        "Olá, quantos alunos um preceptor pode acompanhar?",
        "Olá qual é o prazo para cadastrar uma empresa?",
        "Obrigado! Como cadastro a empresa?",
        "Tudo bem? Preciso entregar quais documentos?",
        "Até mais, mas antes: qual é a carga horária do estágio?",
        "  Oi, qual é o prazo para cadastrar uma empresa?  "
    })
    void mixedMessagesKeepTheWholeQuestionInTheDocumentalFlow(String message) throws Exception {
        assertRetrievalWithoutEvidence(message);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Qual é a carga horária do estágio?",
        "Quantos alunos um preceptor pode acompanhar?",
        "Como enviar o termo de compromisso?",
        "Astronomia?",
        "Oito alunos podem ter o mesmo preceptor?",
        "bom dia de estágio",
        "OiOi",
        "Opaquidade",
        "Tudo bem com o seguro de estágio?",
        "Como vai funcionar o cadastro?",
        "Obrigado pela orientação sobre o estágio",
        "Olá, preciso de ajuda",
        "Até mais tarde preciso enviar o relatório",
        "Valeu123", "Oi +", "o.i.", "Como vai o estágio?",
        "Tudo bem cadastrar a empresa depois?", "Bom dia 30 dias", "Oi \uD83D\uDE00 prazo"
    })
    void academicOrAmbiguousMessagesAreNotMistakenForSocialMessages(String message) throws Exception {
        assertRetrievalWithoutEvidence(message);
    }

    @Test
    void aGreetingWithAnAcademicQuestionPreservesGenerationAuditAndCitations() throws Exception {
        String question = "Olá, quantos alunos um preceptor pode acompanhar?";
        String evidence = "O preceptor pode acompanhar até dez alunos.";
        String generated = "O preceptor pode acompanhar até dez alunos. [Fonte: Manual | Página: 7]";
        var chunk = new KnowledgeChunk("R-7-1", "Manual", 7, "recursivo", evidence, new double[]{1});
        var source = new RagService.Source("Manual", 7, evidence);
        when(settings.load()).thenReturn(cfg);
        when(retriever.retrieve(cfg, question)).thenReturn(List.of(new RankedChunk(chunk, 1, 1, 1)));
        when(ollama.chat(eq(cfg), eq(question), anyString())).thenReturn(generated);
        when(ollama.auditAnswer(cfg, question, List.of(source), generated)).thenReturn(
            new AnswerEvidence.Audit(List.of(generated), List.of(), List.of(), true));

        var answer = rag.answer("  " + question + "  ");

        assertTrue(answer.encontrou());
        assertEquals(generated, answer.resposta());
        assertEquals(List.of(source), answer.fontes());
        verify(settings).load();
        verify(retriever).retrieve(cfg, question);
        verify(ollama).chat(eq(cfg), eq(question), contains("Página: 7\nConteúdo: " + evidence));
        verify(ollama).auditAnswer(cfg, question, List.of(source), generated);
        verifyNoMoreInteractions(settings, retriever, ollama);
        verifyNoInteractions(store);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n\r"})
    void invalidInputsStillFailBeforeUsingAnyServices(String message) {
        assertThrows(IllegalArgumentException.class, () -> rag.answer(message));
        verifyNoInteractions(settings, retriever, ollama, store);
    }

    @Test
    void oversizedInputStillFailsBeforeSocialClassificationOrRag() {
        String message = "Oi" + "!".repeat(1999);
        assertThrows(IllegalArgumentException.class, () -> rag.answer(message));
        verifyNoInteractions(settings, retriever, ollama, store);
    }

    private void assertSocialWithoutRag(String message) throws Exception {
        // Reproduce the old missing-evidence response instead of failing on a null configuration.
        when(settings.load()).thenReturn(cfg);
        when(retriever.retrieve(any(), anyString())).thenReturn(List.of());

        var answer = rag.answer(message);

        assertNotNull(answer.resposta());
        assertFalse(answer.resposta().isBlank());
        assertTrue(answer.resposta().length() <= 400, "A resposta social deve ser breve.");
        assertNotEquals(RagService.UNKNOWN, answer.resposta());
        assertFalse(answer.resposta().contains("informação suficiente"));
        assertFalse(answer.resposta().contains("material consultado"));
        assertFalse(answer.resposta().contains("[Fonte:"));
        assertFalse(answer.resposta().contains("Página:"));
        assertTrue(answer.fontes().isEmpty());
        assertFalse(answer.encontrou(), "Interações sociais não são evidência documental.");
        verifyNoInteractions(settings, retriever, ollama, store);
    }

    private void assertRetrievalWithoutEvidence(String message) throws Exception {
        when(settings.load()).thenReturn(cfg);
        when(retriever.retrieve(cfg, message.trim())).thenReturn(List.of());

        var answer = rag.answer(message);

        assertEquals(FinalAnswerPresentation.unknown(message), answer.resposta());
        assertFalse(answer.encontrou());
        assertTrue(answer.fontes().isEmpty());
        verify(settings).load();
        verify(retriever).retrieve(cfg, message.trim());
        verifyNoMoreInteractions(settings, retriever);
        verifyNoInteractions(ollama, store);
    }
}
