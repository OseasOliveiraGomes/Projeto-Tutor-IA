package br.com.sylph.service;

import br.com.sylph.config.ProjectConfig;
import br.com.sylph.model.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HybridRetrieverTest {
    private KnowledgeChunk chunk(String id, String doc, int page, String text, double... embedding) {
        return new KnowledgeChunk(id, doc, page, "recursivo", text, embedding);
    }

    @Test void retrievesContinuationOnNextPageOutsideTopK() throws Exception {
        var cfg = new ProjectConfig();
        cfg.top_k = 1;
        var ollama = mock(OllamaClient.class);
        var store = mock(CsvKnowledgeStore.class);
        var page6 = chunk("a", "Manual", 6, "cadastro empresa estágio", 1, 0);
        var page7 = chunk("b", "Manual", 7, "Continuação: o envio deve conter a assinatura.", 0, 1);
        var other = chunk("c", "Outro", 7, "Texto sem relação", 0, 1);
        var distant = chunk("d", "Manual", 10, "Texto distante", 0, 1);
        when(store.read("recursivo")).thenReturn(List.of(page6, page7, other, distant));
        when(ollama.embed(cfg, List.of("cadastro empresa"))).thenReturn(List.of(new double[]{1, 0}));
        var result = new HybridRetriever(ollama, store).retrieve(cfg, "cadastro empresa");
        assertEquals(List.of(page6, page7), result.stream().map(RankedChunk::chunk).toList());
    }

    @Test void expandsBothDirectionsWithoutDuplicatesAndBoundsContext() {
        var previous = new RankedChunk(chunk("a", "Manual", 6, "a", 1), 1, 1, 1);
        var seed = new RankedChunk(chunk("b", "Manual", 7, "b", 1), 1, 1, 1);
        var large = new RankedChunk(chunk("c", "Manual", 8, "x".repeat(16000), 1), 1, 1, 1);
        var result = HybridRetriever.expandNearby(List.of(seed, previous), List.of(seed, previous, large));
        assertEquals(List.of(seed, previous), result);
    }

    @Test void expandsTwoPagesAndAllChunksWithoutCrossingDocuments() {
        var seed = new RankedChunk(chunk("seed", "Manual", 30, "seed", 1), 1, 1, 1);
        var parts = java.util.stream.IntStream.range(0, 4).mapToObj(i ->
            new RankedChunk(chunk("part" + i, "Manual", 28, "definition" + i, 1), 0, 0, 0)).toList();
        var ranked = new java.util.ArrayList<>(parts);
        ranked.add(new RankedChunk(chunk("other", "Other", 28, "other", 1), 1, 1, 1));
        assertEquals(5, HybridRetriever.expandNearby(List.of(seed), ranked).size());
        assertEquals(List.of(seed), HybridRetriever.expandNearby(List.of(seed), ranked, 1));
    }

    @Test void unrelatedQueryStillReturnsNoContext() throws Exception {
        var cfg = new ProjectConfig();
        var ollama = mock(OllamaClient.class);
        var store = mock(CsvKnowledgeStore.class);
        when(store.read("recursivo")).thenReturn(List.of(chunk("a", "Manual", 6, "cadastro", 0, 1)));
        when(ollama.embed(cfg, List.of("astronomia"))).thenReturn(List.of(new double[]{1, 0}));
        assertTrue(new HybridRetriever(ollama, store).retrieve(cfg, "astronomia").isEmpty());
    }
}
