package br.com.sylph.service;
import br.com.sylph.config.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
class LiveRagDiagnosticTest {
    @Test void diagnosticWhenExplicitlyEnabled() throws Exception {
        if (!Boolean.getBoolean("sylph.live")) return;
        var mapper = new ObjectMapper();
        var settings = mock(SettingsService.class);
        var cfg = new SettingsService(mapper).load();
        cfg.debug_rag = true;
        cfg.chunking_ativo = System.getProperty("sylph.mode", "recursivo");
        when(settings.load()).thenReturn(cfg);
        var store = new CsvKnowledgeStore(mapper);
        var ollama = new OllamaClient(mapper);
        var rag = new RagService(settings, new HybridRetriever(ollama, store), ollama, store);
        var result = rag.answer("Qual \u00e9 a diferen\u00e7a entre supervisor acad\u00eamico e supervisor de campo no est\u00e1gio obrigat\u00f3rio da UNOPAR? Quem \u00e9 respons\u00e1vel por cada fun\u00e7\u00e3o?");
        System.out.println("[LIVE] " + result);
        if (Boolean.getBoolean("sylph.expectAnswer")) {
            org.junit.jupiter.api.Assertions.assertTrue(result.encontrou());
            for (int page : java.util.List.of(15,16))
                org.junit.jupiter.api.Assertions.assertTrue(result.fontes().stream().anyMatch(f -> f.pagina() == page), "Missing citation " + page);
        }
    }
}
