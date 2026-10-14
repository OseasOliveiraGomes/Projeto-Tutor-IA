package br.com.sylph.service;

import org.junit.jupiter.api.Test;
import java.util.List;
import br.com.sylph.config.ProjectConfig;
import br.com.sylph.config.SettingsService;
import br.com.sylph.model.KnowledgeChunk;
import br.com.sylph.model.RankedChunk;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RagServiceTest {
    @Test void preservesRetrievedOrderMetadataAndNearbyPagesInModelContext() throws Exception {
        var settings = mock(SettingsService.class);
        var retriever = mock(HybridRetriever.class);
        var ollama = mock(OllamaClient.class);
        var cfg = new ProjectConfig();
        when(ollama.auditAnswer(eq(cfg), anyString(), anyList(), anyString())).thenAnswer(call ->
            new AnswerEvidence.Audit(List.of(call.getArgument(3, String.class)), List.of(), List.of(), true));
        when(settings.load()).thenReturn(cfg);
        var seed = new KnowledgeChunk("R-7-12", "Manual", 7, "recursivo", "Assinatura", new double[]{1});
        var nearby = new KnowledgeChunk("R-6-11", "Manual", 6, "recursivo", "Cadastro", new double[]{1});
        when(retriever.retrieve(cfg, "Cadastro e assinatura?")).thenReturn(List.of(
            new RankedChunk(seed, 1, 1, 1), new RankedChunk(nearby, 0, 0, 0)));
        String answer = "1. Cadastro. [Fonte: Manual | Página: 6]\n2. Assinatura. [Fonte: Manual | Página: 7]";
        when(ollama.chat(eq(cfg), eq("Cadastro e assinatura?"), anyString())).thenReturn(answer);
        var result = new RagService(settings, retriever, ollama, mock(CsvKnowledgeStore.class))
            .answer(" Cadastro e assinatura? ");
        var context = ArgumentCaptor.forClass(String.class);
        verify(ollama).chat(eq(cfg), eq("Cadastro e assinatura?"), context.capture());
        String text = context.getValue();
        assertTrue(text.contains("Ordem de recuperação: 1\nIdentificador do trecho: R-7-12\nDocumento: Manual\nPágina: 7\nConteúdo: Assinatura"));
        assertTrue(text.contains("Ordem de recuperação: 2\nIdentificador do trecho: R-6-11\nDocumento: Manual\nPágina: 6\nConteúdo: Cadastro"));
        assertTrue(text.indexOf("R-7-12") < text.indexOf("R-6-11"));
        assertEquals(answer, result.resposta());
        assertEquals(2, result.fontes().size());
    }
    @Test void rejectsPresentPageWithoutSemanticSupportAndMissingSubquestions() throws Exception {
        var settings = mock(SettingsService.class);
        var retriever = mock(HybridRetriever.class);
        var ollama = mock(OllamaClient.class);
        var cfg = new ProjectConfig();
        when(settings.load()).thenReturn(cfg);
        var c = new KnowledgeChunk("a", "Manual", 17, "recursivo", "Assinatura do formulario", new double[]{1});
        when(retriever.retrieve(eq(cfg), anyString())).thenReturn(List.of(new RankedChunk(c, 1, 1, 1)));
        when(ollama.chat(eq(cfg), anyString(), anyString())).thenReturn("Supervisor: professor. [Fonte: Manual | P\u00e1gina: 17]");
        when(ollama.repairAnswer(eq(cfg), anyString(), anyList(), anyString(), any())).thenReturn("Supervisor: professor. [Fonte: Manual | P\u00e1gina: 17]");
        when(ollama.auditAnswer(eq(cfg), anyString(), anyList(), anyString())).thenReturn(
            new AnswerEvidence.Audit(List.of(), List.of("Unsupported claim"), List.of(), true));
        var answer = new RagService(settings, retriever, ollama, mock(CsvKnowledgeStore.class)).answer("Dois supervisores?");
        assertTrue(answer.resposta().contains("validar"));
        assertTrue(answer.fontes().isEmpty());
        verify(ollama, times(2)).auditAnswer(eq(cfg), anyString(), anyList(), anyString());
    }

    @Test void auditReceivesEveryChunkFromTheSamePage() throws Exception {
        var settings = mock(SettingsService.class);
        var retriever = mock(HybridRetriever.class);
        var ollama = mock(OllamaClient.class);
        var cfg = new ProjectConfig();
        when(settings.load()).thenReturn(cfg);
        var a = new KnowledgeChunk("a","Manual",3,"semantico","Primeira parte",new double[]{1});
        var b = new KnowledgeChunk("b","Manual",3,"semantico","Segunda parte",new double[]{1});
        when(retriever.retrieve(eq(cfg),anyString())).thenReturn(List.of(new RankedChunk(a,1,1,1),new RankedChunk(b,1,1,1)));
        String answer = "Segunda parte [Fonte: Manual | P\u00e1gina: 3]";
        when(ollama.chat(eq(cfg),anyString(),anyString())).thenReturn(answer);
        when(ollama.auditAnswer(eq(cfg),anyString(),anyList(),anyString())).thenAnswer(call -> {
            List<RagService.Source> sources = call.getArgument(2);
            assertEquals(1,sources.size());
            assertTrue(sources.getFirst().trecho().contains("Primeira parte"));
            assertTrue(sources.getFirst().trecho().contains("Segunda parte"));
            return new AnswerEvidence.Audit(List.of(answer),List.of(),List.of(),true);
        });
        var result = new RagService(settings,retriever,ollama,mock(CsvKnowledgeStore.class)).answer("Segunda parte?");
        assertTrue(result.encontrou());
    }

    @Test void repairsPartialAnswerAndPreservesApprovedContent() throws Exception {
        var settings = mock(SettingsService.class);
        var retriever = mock(HybridRetriever.class);
        var ollama = mock(OllamaClient.class);
        var cfg = new ProjectConfig();
        when(settings.load()).thenReturn(cfg);
        var a = new KnowledgeChunk("a", "Manual", 3, "semantico", "Cadastro", new double[]{1});
        var b = new KnowledgeChunk("b", "Manual", 4, "semantico", "Assinatura", new double[]{1});
        when(retriever.retrieve(eq(cfg), anyString())).thenReturn(List.of(new RankedChunk(a,1,1,1),new RankedChunk(b,1,1,1)));
        String good = "Cadastro. [Fonte: Manual | Página: 3]";
        String fixed = good + "\nAssinatura. [Fonte: Manual | Página: 4]";
        when(ollama.chat(eq(cfg), anyString(), anyString())).thenReturn(good + " Inventado.");
        when(ollama.repairAnswer(eq(cfg), anyString(), anyList(), anyString(), any())).thenReturn(fixed);
        when(ollama.auditAnswer(eq(cfg), anyString(), anyList(), anyString())).thenReturn(
            new AnswerEvidence.Audit(List.of(good),List.of("Inventado"),List.of("Assinatura"),true),
            new AnswerEvidence.Audit(List.of(fixed),List.of(),List.of(),true));
        var result = new RagService(settings,retriever,ollama,mock(CsvKnowledgeStore.class)).answer("Cadastro e assinatura?");
        assertEquals(fixed,result.resposta());
        assertEquals(2,result.fontes().size());
        verify(ollama).repairAnswer(eq(cfg), anyString(), anyList(), anyString(), argThat(audit -> audit.approved().contains(good)));
    }

    @Test void failedRepairDoesNotEraseSupportedUnits() throws Exception {
        var settings = mock(SettingsService.class);
        var retriever = mock(HybridRetriever.class);
        var ollama = mock(OllamaClient.class);
        var cfg = new ProjectConfig();
        when(settings.load()).thenReturn(cfg);
        var c = new KnowledgeChunk("a", "Manual", 3, "recursivo", "Cadastro", new double[]{1});
        when(retriever.retrieve(eq(cfg), anyString())).thenReturn(List.of(new RankedChunk(c,1,1,1)));
        String good = "Cadastro. [Fonte: Manual | Página: 3]";
        when(ollama.chat(eq(cfg), anyString(), anyString())).thenReturn(good + " Inventado.");
        when(ollama.repairAnswer(eq(cfg), anyString(), anyList(), anyString(), any())).thenReturn("Inventado.");
        when(ollama.auditAnswer(eq(cfg), anyString(), anyList(), anyString())).thenReturn(
            new AnswerEvidence.Audit(List.of(good),List.of("Inventado"),List.of(),true),
            new AnswerEvidence.Audit(List.of(),List.of("Inventado"),List.of(),true));
        var result = new RagService(settings,retriever,ollama,mock(CsvKnowledgeStore.class)).answer("Cadastro e prazo?");
        assertTrue(result.encontrou());
        assertTrue(result.resposta().contains(good));
        assertFalse(result.resposta().contains("Inventado"));
        assertEquals(1,result.fontes().size());
    }

    @Test void repairTransportFailurePreservesApprovedClaim() throws Exception {
        var settings = mock(SettingsService.class);
        var retriever = mock(HybridRetriever.class);
        var ollama = mock(OllamaClient.class);
        var cfg = new ProjectConfig();
        when(settings.load()).thenReturn(cfg);
        var c = new KnowledgeChunk("a", "Manual", 3, "recursivo", "Cadastro", new double[]{1});
        when(retriever.retrieve(eq(cfg), anyString())).thenReturn(List.of(new RankedChunk(c,1,1,1)));
        String good = "Cadastro. [Fonte: Manual | P\u00e1gina: 3]";
        when(ollama.chat(eq(cfg),anyString(),anyString())).thenReturn(good);
        when(ollama.auditAnswer(eq(cfg),anyString(),anyList(),anyString())).thenReturn(
            new AnswerEvidence.Audit(List.of(good),List.of(),List.of("Prazo"),true));
        when(ollama.repairAnswer(eq(cfg),anyString(),anyList(),anyString(),any())).thenThrow(new java.io.IOException("Offline"));
        var result = new RagService(settings,retriever,ollama,mock(CsvKnowledgeStore.class)).answer("Cadastro e prazo?");
        assertTrue(result.encontrou());
        assertTrue(result.resposta().contains(good));
        assertTrue(result.resposta().contains("comunicação"));
    }

    @Test void genuinelyMissingEvidenceDoesNotCallModel() throws Exception {
        var settings = mock(SettingsService.class);
        var retriever = mock(HybridRetriever.class);
        var ollama = mock(OllamaClient.class);
        when(settings.load()).thenReturn(new ProjectConfig());
        when(retriever.retrieve(any(), anyString())).thenReturn(List.of());
        var result = new RagService(settings,retriever,ollama,mock(CsvKnowledgeStore.class)).answer("Astronomia?");
        assertTrue(result.resposta().contains("Astronomia?"));
        assertTrue(result.resposta().contains("informação suficiente"));
        assertFalse(result.encontrou());
        verifyNoInteractions(ollama);
    }

    @Test void unrelatedRetrievedTextAndExplicitAbsenceRemainUnknown() throws Exception {
        var settings = mock(SettingsService.class);
        var retriever = mock(HybridRetriever.class);
        var ollama = mock(OllamaClient.class);
        var cfg = new ProjectConfig();
        when(settings.load()).thenReturn(cfg);
        var c = new KnowledgeChunk("a", "Manual", 3, "recursivo", "Cadastro", new double[]{1});
        when(retriever.retrieve(eq(cfg),anyString())).thenReturn(List.of(new RankedChunk(c,1,1,1)));
        when(ollama.chat(eq(cfg),anyString(),anyString())).thenReturn(RagService.UNKNOWN);
        when(ollama.repairAnswer(eq(cfg),anyString(),anyList(),anyString(),any())).thenReturn(RagService.UNKNOWN);
        when(ollama.auditAnswer(eq(cfg),anyString(),anyList(),anyString())).thenReturn(
            new AnswerEvidence.Audit(List.of(),List.of(),List.of("Sem informacao"),true));
        var result = new RagService(settings,retriever,ollama,mock(CsvKnowledgeStore.class)).answer("Astronomia?");
        assertTrue(result.resposta().contains("Astronomia?"));
        assertTrue(result.resposta().contains("informação suficiente"));
        assertFalse(result.encontrou());
    }

    private final List<RagService.Source> sources = List.of(
        new RagService.Source("Manual", 6, "Cadastro"), new RagService.Source("Manual", 7, "Assinatura"));

    @Test void preservesSeparateCitationsAndPartialAnswer() {
        String answer = "1. Cadastro. [Fonte: Manual | Página: 6]\n"
            + "2. Assinatura. [Fonte: Manual | Página: 7]\n3. " + RagService.UNKNOWN;
        assertTrue(AnswerEvidence.parse(answer, sources).issues().isEmpty());
    }

    @Test void rejectsUnknownPageDocumentMalformedAndMissingCitations() {
        for (String answer : List.of("Prazo: 30 dias. [Fonte: Manual | Página: 8]",
                "Prazo: 30 dias. [Fonte: Outro | Página: 6]", "Prazo: 30 dias.",
                "Prazo. [Fonte: Manual | Página: X]", "Prazo. [Fonte: Manual | Página: 999999999999]",
                "Cadastro. [Fonte: Manual | Página: 6] Prazo. [Fonte: Manual | Página: X]")) {
            assertFalse(AnswerEvidence.parse(answer, sources).issues().isEmpty());
        }
    }
}
