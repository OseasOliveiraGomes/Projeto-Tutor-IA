package br.com.sylph.service;

import br.com.sylph.config.ProjectConfig;
import br.com.sylph.config.SettingsService;
import br.com.sylph.model.KnowledgeChunk;
import br.com.sylph.model.RankedChunk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises the actual HTTP client, evidence guard, repair rendering and RAG orchestration. */
class ResponsibilityRepairIntegrationTest {
    private static final String CHANNEL = "O envio é realizado através do sistema Colaborar.";
    private static final String FORMATS = "Os arquivos somente poderão ser enviados no formato PDF e PNG.";
    private static final String SOURCE = CHANNEL +
        " Preencha os dados de projeto, nome do aluno ou matrícula e insira o arquivo no campo indicado. " + FORMATS;
    private static final String CITATION = " [Fonte: Manual | Página: 34]";

    @Test void repairsAdversarialPositiveAuditWithoutInventingTheSubmissionActor() throws Exception {
        var mapper = new ObjectMapper();
        List<JsonNode> requests = Collections.synchronizedList(new ArrayList<>());
        var handlerFailure = new AtomicReference<Throwable>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            byte[] reply;
            int status = 200;
            try {
                JsonNode request = mapper.readTree(exchange.getRequestBody().readAllBytes());
                requests.add(request);
                int call = requests.size();
                String content;
                if (call == 1) {
                    content = "O aluno é responsável por enviar a declaração de dispensa." + CITATION +
                        "\n\n" + CHANNEL + CITATION;
                } else if (call == 2 || call == 4) {
                    // Even the incorrect actor receives supported=true: Java must reject it.
                    JsonNode auditInput = mapper.readTree(request.path("messages").get(1).path("content").asText());
                    List<Map<String, Object>> claims = new ArrayList<>();
                    for (JsonNode unit : auditInput.path("units")) claims.add(Map.of(
                        "id", unit.path("id").asInt(), "supported", true,
                        "reason", "Veredito positivo adversarial",
                        "quotes", List.of(Map.of("documento", "Manual", "pagina", 34, "texto", SOURCE))));
                    content = mapper.writeValueAsString(Map.of("claims", claims,
                        "missing", call == 4 ? List.of("Responsável pelo envio não identificado") : List.of()));
                } else if (call == 3) {
                    JsonNode repairInput = mapper.readTree(request.path("messages").get(1).path("content").asText());
                    List<Integer> selected = new ArrayList<>();
                    for (JsonNode candidate : repairInput.path("candidates")) {
                        String text = candidate.path("texto").asText();
                        if (text.equals(CHANNEL) || text.equals(FORMATS)) selected.add(candidate.path("id").asInt());
                    }
                    content = mapper.writeValueAsString(Map.of("ids", selected,
                        "missing", List.of("Responsável pelo envio não identificado")));
                } else {
                    throw new IllegalStateException("Chamada inesperada: " + call);
                }
                reply = mapper.writeValueAsBytes(Map.of("message", Map.of(
                    "content", content, "thinking", "Este campo não compõe a resposta ao aluno.")));
            } catch (Throwable failure) {
                handlerFailure.compareAndSet(null, failure);
                status = 500;
                reply = "Falha no servidor de teste".getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(status, reply.length);
            try (var out = exchange.getResponseBody()) { out.write(reply); }
        });
        server.start();
        try {
            var cfg = new ProjectConfig();
            cfg.ollama_url = "http://127.0.0.1:" + server.getAddress().getPort();
            var settings = mock(SettingsService.class);
            var retriever = mock(HybridRetriever.class);
            when(settings.load()).thenReturn(cfg);
            var chunk = new KnowledgeChunk("R-34-1", "Manual", 34, "recursivo", SOURCE, new double[]{1});
            String question = "Quem envia a declaração de dispensa, por qual sistema e em quais formatos?";
            when(retriever.retrieve(cfg, question)).thenReturn(List.of(new RankedChunk(chunk, 1, 1, 1)));

            var result = new RagService(settings, retriever, new OllamaClient(mapper), mock(CsvKnowledgeStore.class))
                .answer(question);

            assertNull(handlerFailure.get());
            assertEquals(4, requests.size(), "Geração, auditoria, reparo e nova auditoria devem ocorrer");
            assertEquals(List.of(true, false, false, false), requests.stream()
                .map(request -> request.path("think").asBoolean()).toList());
            assertTrue(result.encontrou());
            assertTrue(result.resposta().contains(CHANNEL));
            assertTrue(result.resposta().contains(FORMATS));
            assertFalse(result.resposta().contains("aluno"));
            assertFalse(result.resposta().contains("responsável por enviar"));
            assertFalse(result.resposta().contains("Este campo"));
            assertTrue(result.resposta().contains("confirmar"), "A responsabilidade ausente permanece sem confirmação");
            assertEquals(List.of(new RagService.Source("Manual", 34, SOURCE)), result.fontes());

            JsonNode repairInput = mapper.readTree(requests.get(2).path("messages").get(1).path("content").asText());
            assertFalse(repairInput.path("issues").isEmpty(), "A atribuição falsa deve provocar o reparo");
            assertTrue(repairInput.path("previous").asText().contains("O aluno é responsável"));
            JsonNode repairedAuditInput = mapper.readTree(requests.get(3).path("messages").get(1).path("content").asText());
            assertEquals(2, repairedAuditInput.path("units").size());
            assertFalse(repairedAuditInput.toString().contains("responsável por enviar"));
            verify(retriever).retrieve(cfg, question);
        } finally {
            server.stop(0);
        }
    }
}
