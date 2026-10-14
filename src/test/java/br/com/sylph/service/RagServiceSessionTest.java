package br.com.sylph.service;

import br.com.sylph.config.ProjectConfig;
import br.com.sylph.config.SettingsService;
import br.com.sylph.model.KnowledgeChunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RagServiceSessionTest {
    private final SettingsService settings = mock(SettingsService.class);
    private final HybridRetriever retriever = mock(HybridRetriever.class);
    private final OllamaClient ollama = mock(OllamaClient.class);
    private final CsvKnowledgeStore store = mock(CsvKnowledgeStore.class);
    private final RagService rag = new RagService(settings, retriever, ollama, store);

    @TempDir Path temporaryDirectory;

    @ParameterizedTest
    @ValueSource(strings = {"recursivo", "semantico"})
    void selectedMethodReadsItsBaseAndKeepsGenerationAuditAndCitations(String method) throws Exception {
        ProjectConfig config = new ProjectConfig();
        config.chunking_ativo = method.equals("recursivo") ? "semantico" : "recursivo";
        when(settings.load()).thenReturn(config);
        String question = "Quantos alunos um preceptor pode acompanhar?";
        String evidence = "O preceptor pode acompanhar até dez alunos.";
        String generated = evidence + " [Fonte: Manual | Página: 7]";
        KnowledgeChunk chunk = new KnowledgeChunk(method + "-7", "Manual", 7, method,
            evidence, new double[]{1, 0});
        RagService.Source source = new RagService.Source("Manual", 7, evidence);
        when(store.read(method)).thenReturn(List.of(chunk));
        when(ollama.embed(config, List.of(question))).thenReturn(List.of(new double[]{1, 0}));
        when(ollama.chat(eq(config), eq(question), anyString())).thenReturn(generated);
        when(ollama.auditAnswer(config, question, List.of(source), generated)).thenReturn(
            new AnswerEvidence.Audit(List.of(generated), List.of(), List.of(), true));
        RagService service = new RagService(settings, new HybridRetriever(ollama, store), ollama, store);

        RagService.Answer answer = service.answer("  " + question + "  ", method);

        assertTrue(answer.encontrou());
        assertEquals(generated, answer.resposta());
        assertEquals(List.of(source), answer.fontes());
        assertEquals(method, config.chunking_ativo);
        verify(settings).load();
        verifyNoMoreInteractions(settings);
        verify(store).read(method);
        verifyNoMoreInteractions(store);
        verify(ollama).embed(config, List.of(question));
        verify(ollama).chat(eq(config), eq(question), contains("Página: 7\nConteúdo: " + evidence));
        verify(ollama).auditAnswer(config, question, List.of(source), generated);
        verifyNoMoreInteractions(ollama);
    }

    @Test
    void overridePreservesEveryOtherConfiguredParameter() throws Exception {
        ProjectConfig config = new ProjectConfig();
        config.chunking_ativo = "recursivo";
        config.modelo_llm = "modelo-local-personalizado";
        config.modelo_embeddings = "embeddings-personalizados";
        config.ollama_url = "http://localhost:12434";
        config.top_k = 7;
        config.debug_rag = true;
        config.expansao_paginas = 4;
        config.min_similaridade = 0.27;
        config.limiar_semantico = 0.81;
        ProjectConfig original = new ObjectMapper().convertValue(config, ProjectConfig.class);
        when(settings.load()).thenReturn(config);
        when(retriever.retrieve(config, "Quais são os documentos exigidos?")).thenReturn(List.of());

        rag.answer("Quais são os documentos exigidos?", "semantico");

        assertEquals("semantico", config.chunking_ativo);
        assertParametersEqual(original, config);
        verify(settings).load();
        verifyNoMoreInteractions(settings);
        verify(retriever).retrieve(config, "Quais são os documentos exigidos?");
        verifyNoInteractions(ollama, store);
    }

    @Test
    void realSettingsRetainsConfigurationBytesAndSavedMethodAcrossSessionOverrides() throws Exception {
        Path configuration = Path.of("config.json").toAbsolutePath();
        byte[] before = Files.readAllBytes(configuration);
        SettingsService realSettings = new SettingsService(new ObjectMapper());
        ProjectConfig saved = realSettings.load();
        when(retriever.retrieve(any(ProjectConfig.class), anyString())).thenReturn(List.of());
        RagService service = new RagService(realSettings, retriever, ollama, store);

        service.answer("Documentos exigidos?", "recursivo");
        service.answer("Documentos exigidos?", "semantico");
        service.answer("Documentos exigidos?");

        ArgumentCaptor<ProjectConfig> configurations = ArgumentCaptor.forClass(ProjectConfig.class);
        verify(retriever, times(3)).retrieve(configurations.capture(), eq("Documentos exigidos?"));
        List<ProjectConfig> loaded = configurations.getAllValues();
        assertEquals(List.of("recursivo", "semantico", saved.chunking_ativo),
            loaded.stream().map(config -> config.chunking_ativo).toList());
        assertNotSame(loaded.get(0), loaded.get(1));
        assertNotSame(loaded.get(1), loaded.get(2));
        loaded.forEach(config -> assertParametersEqual(saved, config));
        assertArrayEquals(before, Files.readAllBytes(configuration));
        assertEquals(saved.chunking_ativo, realSettings.load().chunking_ativo);
        verifyNoInteractions(ollama, store);
    }

    @Test
    void successiveOverridesDoNotBecomeTheDefaultForOtherCallsOrServices() throws Exception {
        when(settings.load()).thenAnswer(invocation -> {
            ProjectConfig config = new ProjectConfig();
            config.chunking_ativo = "semantico";
            return config;
        });
        when(retriever.retrieve(any(ProjectConfig.class), anyString())).thenReturn(List.of());
        RagService otherService = new RagService(settings, retriever, ollama, store);

        rag.answer("Prazo documental?", "recursivo");
        rag.answer("Prazo documental?", "semantico");
        rag.answer("Prazo documental?", "recursivo");
        rag.answer("Prazo documental?");
        otherService.answer("Prazo documental?");
        rag.answer("Prazo documental?", null);

        ArgumentCaptor<ProjectConfig> configurations = ArgumentCaptor.forClass(ProjectConfig.class);
        verify(retriever, times(6)).retrieve(configurations.capture(), eq("Prazo documental?"));
        assertEquals(List.of("recursivo", "semantico", "recursivo", "semantico", "semantico", "semantico"),
            configurations.getAllValues().stream().map(config -> config.chunking_ativo).toList());
        assertEquals(6, configurations.getAllValues().stream().distinct().count());
        verify(settings, times(6)).load();
        verifyNoMoreInteractions(settings);
        verifyNoInteractions(ollama, store);
    }

    @ParameterizedTest
    @ValueSource(strings = {"recursivo", "semantico"})
    void selectedMethodSocialRepliesDoNotAccessDocumentalServices(String method) throws Exception {
        for (String message : List.of("Oi", "Boa tarde!", "Tudo bem?", "Obrigada!", "Até mais")) {
            RagService.Answer answer = rag.answer(message, method);

            assertFalse(answer.resposta().isBlank());
            assertFalse(answer.resposta().contains("[Fonte:"));
            assertTrue(answer.fontes().isEmpty());
            assertFalse(answer.encontrou());
        }
        verifyNoInteractions(settings, retriever, ollama, store);
    }

    @ParameterizedTest
    @MethodSource("invalidQuestions")
    void selectedMethodRejectsInvalidQuestionsBeforeUsingServices(String question) {
        for (String method : List.of("recursivo", "semantico"))
            assertThrows(IllegalArgumentException.class, () -> rag.answer(question, method));
        verifyNoInteractions(settings, retriever, ollama, store);
    }

    static Stream<String> invalidQuestions() {
        return Stream.of(null, "", " ", "\t\n\r", "x".repeat(2001));
    }

    @ParameterizedTest
    @ValueSource(strings = {"outro", "", "SEMANTICO", "../recursivo"})
    void rejectsUnknownSessionMethodsBeforeUsingServices(String method) {
        assertThrows(IllegalArgumentException.class, () -> rag.answer("Documentos exigidos?", method));
        verifyNoInteractions(settings, retriever, ollama, store);
    }

    @ParameterizedTest
    @ValueSource(strings = {"recursivo", "semantico"})
    void readyForSelectedMethodUsesOnlyItsBaseFile(String method) throws Exception {
        Path base = Files.createFile(temporaryDirectory.resolve("base_" + method + ".csv"));
        when(store.file(method)).thenReturn(base);

        assertTrue(rag.ready(method));

        verify(store).file(method);
        verifyNoMoreInteractions(store);
        verifyNoInteractions(settings, retriever, ollama);
    }

    @ParameterizedTest
    @ValueSource(strings = {"recursivo", "semantico"})
    void readyForSelectedMethodReturnsFalseForMissingBase(String method) {
        when(store.file(method)).thenReturn(temporaryDirectory.resolve("base_" + method + ".csv"));

        assertFalse(rag.ready(method));

        verifyNoInteractions(settings, retriever, ollama);
        verify(store).file(method);
        verifyNoMoreInteractions(store);
    }

    @Test
    void readyDoesNotTreatDirectoryAsBaseFile() {
        when(store.file("recursivo")).thenReturn(temporaryDirectory);

        assertFalse(rag.ready("recursivo"));

        verifyNoInteractions(settings, retriever, ollama);
    }

    @Test
    void readyReturnsFalseForInvalidMethodsInsteadOfThrowing() {
        RagService service = new RagService(settings, retriever, ollama, new CsvKnowledgeStore(new ObjectMapper()));

        for (String method : List.of("outro", "", "SEMANTICO", "../recursivo"))
            assertFalse(service.ready(method));

        verifyNoInteractions(settings, retriever, ollama, store);
    }

    @ParameterizedTest
    @ValueSource(strings = {"recursivo", "semantico"})
    void readyReturnsFalseWhenBaseLocationFails(String method) {
        when(store.file(method)).thenThrow(new IllegalArgumentException("Localização inválida"));

        assertFalse(rag.ready(method));

        verifyNoInteractions(settings, retriever, ollama);
    }

    @Test
    void legacyReadyStillUsesTheSavedMethod() throws Exception {
        ProjectConfig config = new ProjectConfig();
        config.chunking_ativo = "semantico";
        when(settings.load()).thenReturn(config);
        when(store.file("semantico")).thenReturn(Files.createFile(temporaryDirectory.resolve("base_semantico.csv")));

        assertTrue(rag.ready());

        verify(settings).load();
        verifyNoMoreInteractions(settings);
        verify(store).file("semantico");
        verifyNoMoreInteractions(store);
        verifyNoInteractions(retriever, ollama);
    }

    @Test
    void legacyReadyStillReturnsFalseWhenSettingsCannotBeLoaded() throws Exception {
        when(settings.load()).thenThrow(new IOException("Configuração ausente"));

        assertFalse(rag.ready());

        verify(settings).load();
        verifyNoMoreInteractions(settings);
        verifyNoInteractions(retriever, ollama, store);
    }

    @ParameterizedTest
    @ValueSource(strings = {"recursivo", "semantico"})
    void missingSelectedBaseFailsWithoutEmbeddingGenerationOrWritingBases(String method) throws Exception {
        when(settings.load()).thenAnswer(invocation -> new ProjectConfig());
        when(store.read(method)).thenThrow(new IOException("Base " + method + " ausente"));
        RagService service = new RagService(settings, new HybridRetriever(ollama, store), ollama, store);

        IOException failure = assertThrows(IOException.class,
            () -> service.answer("Documentos exigidos?", method));

        assertEquals("Base " + method + " ausente", failure.getMessage());
        verify(store).read(method);
        verifyNoMoreInteractions(store);
        verify(settings).load();
        verifyNoMoreInteractions(settings);
        verifyNoInteractions(ollama);
    }

    private static void assertParametersEqual(ProjectConfig expected, ProjectConfig actual) {
        assertAll(
            () -> assertEquals(expected.modelo_llm, actual.modelo_llm),
            () -> assertEquals(expected.modelo_embeddings, actual.modelo_embeddings),
            () -> assertEquals(expected.ollama_url, actual.ollama_url),
            () -> assertEquals(expected.top_k, actual.top_k),
            () -> assertEquals(expected.debug_rag, actual.debug_rag),
            () -> assertEquals(expected.expansao_paginas, actual.expansao_paginas),
            () -> assertEquals(expected.min_similaridade, actual.min_similaridade),
            () -> assertEquals(expected.limiar_semantico, actual.limiar_semantico));
    }
}
