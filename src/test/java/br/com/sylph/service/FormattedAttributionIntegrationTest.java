package br.com.sylph.service;

import br.com.sylph.config.ProjectConfig;
import br.com.sylph.config.SettingsService;
import br.com.sylph.model.KnowledgeChunk;
import br.com.sylph.model.RankedChunk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Deterministic HTTP replay, not a live Qwen evaluation. All orchestration, HTTP,
 * evidence validation, repair selection/rendering and second auditing are real.
 * Retrieval is fixed to the existing, unmodified CSV page used by the citations.
 * The fixture distinguishes an exact historical recording from synthetic variants.
 */
public class FormattedAttributionIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DIAGNOSTIC = "[RAG] atribuicao_rejeitada";
    private static final String THINKING_SENTINEL = "RACIOCINIO_INTERNO_NAO_APRESENTAR";

    @Test void replaysExactlyTheRecordedAnswerAndFirstAuditFromRecursivo06() throws Exception {
        replay("historical_recursivo_06", true);
    }

    @Test void repairsMixedUnitContainingTheUsersLiteralStatementWithoutLosingProcedure() throws Exception {
        replay("literal_user_statement_mixed_unit", true);
    }

    @Test void detectsResponsibilityBeforeTheLastColonDespiteMarkdownHeading() throws Exception {
        replay("markdown_two_colons_mixed_unit", true);
    }

    @Test void repairsPassiveAttributionAndRetainsFormDataIncludingTheStudentsName() throws Exception {
        replay("passive_markdown_mixed_unit", true);
    }

    @Test void stillRejectsAttributionWithoutPrintingDiagnosticsWhenDebugIsDisabled() throws Exception {
        replay("markdown_two_colons_mixed_unit", false);
    }

    /**
     * Allows a separate JVM to replay against production classes extracted from
     * the built JAR. Test dependencies (JUnit assertions and Mockito) must remain
     * on the classpath. An optional first argument writes the complete proof JSON.
     */
    public static void main(String[] args) throws Exception {
        var proof = new LinkedHashMap<String, Object>();
        proof.put("kind", "deterministic_http_replay_not_live_qwen");
        proof.put("production_class_locations", Map.of(
            "RagService", location(RagService.class), "OllamaClient", location(OllamaClient.class),
            "AnswerEvidence", location(AnswerEvidence.class), "AttributionEvidence", location(AttributionEvidence.class)));
        var test = new FormattedAttributionIntegrationTest();
        List<Map<String, Object>> executions = new ArrayList<>();
        for (String id : List.of("historical_recursivo_06", "literal_user_statement_mixed_unit",
                "markdown_two_colons_mixed_unit", "passive_markdown_mixed_unit")) executions.add(test.replay(id, true));
        executions.add(test.replay("markdown_two_colons_mixed_unit", false));
        proof.put("executions", executions);
        proof.put("passed", executions.size());
        String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(proof);
        if (args.length > 0) {
            Path output = Path.of(args[0]).toAbsolutePath();
            Files.createDirectories(output.getParent());
            Files.writeString(output, json, StandardCharsets.UTF_8);
        }
        System.out.println(json);
    }

    private static String location(Class<?> type) {
        return type.getProtectionDomain().getCodeSource().getLocation().toExternalForm();
    }

    private Map<String, Object> replay(String id, boolean debug) throws Exception {
        JsonNode fixture;
        try (var input = getClass().getResourceAsStream("/qc/formatted-attribution-replay.json")) {
            assertNotNull(input, "Fixture de replay ausente");
            fixture = MAPPER.readTree(input);
        }
        JsonNode historical = fixture.path("historical_origin");
        CsvKnowledgeStore csv = new CsvKnowledgeStore(MAPPER);
        List<KnowledgeChunk> chunks = csv.read("recursivo").stream()
            .filter(chunk -> chunk.pagina() == historical.path("source").path("pagina").asInt()
                && chunk.documento().equals(historical.path("source").path("documento").asText())).toList();
        assertFalse(chunks.isEmpty(), "O replay deve ler a página real no CSV existente");
        String realSource = String.join("\n", chunks.stream().map(KnowledgeChunk::texto).toList());
        assertEquals(historical.path("source").path("trecho").asText(), realSource,
            "A evidência histórica deve coincidir exatamente com a página existente no CSV");
        String document = chunks.getFirst().documento();
        int page = chunks.getFirst().pagina();
        String citation = " [Fonte: " + document + " | Página: " + page + "]";
        String channel = fixture.path("channel").asText();
        String data = fixture.path("data").asText();
        String formats = fixture.path("formats").asText();
        for (String literal : List.of(channel, data, formats)) assertTrue(
            AnswerEvidence.normalize(realSource).contains(literal), "Trecho do fixture deve ser literal: " + literal);
        boolean recorded = id.equals("historical_recursivo_06");
        String initial;
        String origin;
        if (recorded) {
            initial = historical.path("initial_answer").asText();
            origin = "Transcrição exata de " + historical.path("path").asText();
        } else {
            JsonNode variant = null;
            for (JsonNode candidate : fixture.path("synthetic_cases")) {
                if (candidate.path("id").asText().equals(id)) { variant = candidate; break; }
            }
            assertNotNull(variant, "Caso sintético não encontrado: " + id);
            initial = variant.path("template").asText().replace("{{CHANNEL}}", channel)
                .replace("{{DATA}}", data).replace("{{FORMATS}}", formats).replace("{{CITATION}}", citation);
            origin = variant.path("origin").asText();
        }
        String initialAnswer = initial;
        List<JsonNode> requests = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<Throwable> handlerFailure = new AtomicReference<>();
        AtomicReference<JsonNode> firstVerdict = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            byte[] body;
            int status = 200;
            try {
                JsonNode request = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
                requests.add(request);
                int call = requests.size();
                String content;
                if (call == 1) content = initialAnswer;
                else if (call == 2 || call == 4) {
                    JsonNode input = MAPPER.readTree(request.path("messages").get(1).path("content").asText());
                    JsonNode verdict;
                    if (call == 2 && recorded) verdict = historical.path("first_audit");
                    else {
                        List<Map<String, Object>> claims = new ArrayList<>();
                        for (JsonNode unit : input.path("units")) {
                            List<Map<String, Object>> quotes = new ArrayList<>();
                            if (call == 2) {
                                // Reproduce a false positive despite valid, page-correct literal quotes.
                                for (String literal : List.of(channel, data, formats)) quotes.add(Map.of(
                                    "documento", document, "pagina", page, "texto", literal));
                            } else {
                                String unitLiteral = AnswerEvidence.CITATION.matcher(unit.path("text").asText())
                                    .replaceAll("").trim();
                                quotes.add(Map.of("documento", document, "pagina", page, "texto", unitLiteral));
                            }
                            claims.add(Map.of("id", unit.path("id").asInt(), "supported", true,
                                "reason", "Veredito HTTP positivo para testar a verificação Java independente", "quotes", quotes));
                        }
                        verdict = MAPPER.valueToTree(Map.of("claims", claims, "missing", call == 4
                            ? List.of("Responsável pelo envio não identificado expressamente") : List.of()));
                    }
                    if (call == 2) firstVerdict.set(verdict);
                    content = MAPPER.writeValueAsString(verdict);
                } else if (call == 3) {
                    JsonNode input = MAPPER.readTree(request.path("messages").get(1).path("content").asText());
                    List<Integer> ids = new ArrayList<>();
                    for (JsonNode candidate : input.path("candidates")) {
                        String literal = candidate.path("texto").asText();
                        if (literal.contains(channel) || literal.equals(data) || literal.contains(formats))
                            ids.add(candidate.path("id").asInt());
                    }
                    assertEquals(3, ids.size(), "O reparo deve selecionar canal, dados e formatos reais");
                    content = MAPPER.writeValueAsString(Map.of("ids", ids,
                        "missing", List.of("Responsável pelo envio não identificado expressamente")));
                } else throw new IllegalStateException("Chamada HTTP inesperada: " + call);
                body = MAPPER.writeValueAsBytes(Map.of("message", Map.of("content", content,
                    "thinking", THINKING_SENTINEL)));
            } catch (Throwable failure) {
                handlerFailure.compareAndSet(null, failure);
                status = 500;
                body = "Falha no servidor de replay".getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(status, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
            finally { exchange.close(); }
        });
        server.start();
        PrintStream originalOutput = System.out;
        ByteArrayOutputStream logged = new ByteArrayOutputStream();
        RagService.Answer answer;
        String logs;
        try (PrintStream capture = new PrintStream(logged, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            ProjectConfig cfg = new ProjectConfig();
            cfg.ollama_url = "http://127.0.0.1:" + server.getAddress().getPort();
            cfg.top_k = 8;
            cfg.expansao_paginas = 0;
            cfg.debug_rag = debug;
            SettingsService settings = mock(SettingsService.class);
            HybridRetriever retriever = mock(HybridRetriever.class);
            when(settings.load()).thenReturn(cfg);
            String question = historical.path("question").asText();
            when(retriever.retrieve(cfg, question)).thenReturn(chunks.stream()
                .map(chunk -> new RankedChunk(chunk, 1, 1, 1)).toList());
            answer = new RagService(settings, retriever, new OllamaClient(MAPPER), csv).answer(question);
            verify(retriever).retrieve(cfg, question);
        } finally {
            System.setOut(originalOutput);
            server.stop(0);
            logs = logged.toString(StandardCharsets.UTF_8);
        }
        assertNull(handlerFailure.get(), () -> "Falha HTTP: " + handlerFailure.get());
        assertEquals(4, requests.size(), "O falso positivo deve acionar geração, auditoria, reparo e nova auditoria");
        assertEquals(List.of(true, false, false, false), requests.stream()
            .map(request -> request.path("think").asBoolean()).toList());
        assertTrue(firstVerdict.get().path("claims").get(0).path("supported").asBoolean(),
            "A auditoria do modelo precisa continuar positiva para reproduzir o falso positivo");
        JsonNode firstAuditInput = MAPPER.readTree(requests.get(1).path("messages").get(1).path("content").asText());
        assertEquals(AnswerEvidence.parse(initialAnswer, List.of(new RagService.Source(document, page, realSource))).units().getFirst().text(),
            firstAuditInput.path("units").get(0).path("text").asText(), "O texto deve chegar intacto à auditoria");
        JsonNode repairInput = MAPPER.readTree(requests.get(2).path("messages").get(1).path("content").asText());
        assertFalse(repairInput.path("issues").isEmpty(), "Java deve rejeitar a atribuição mesmo com supported=true");
        assertTrue(repairInput.path("issues").toString().contains("atribuicao"));
        assertEquals(initialAnswer, repairInput.path("previous").asText());
        assertTrue(answer.encontrou());
        assertTrue(answer.resposta().contains(channel));
        assertTrue(answer.resposta().contains(data), "Mencionar nome do aluno como dado permanece permitido");
        assertTrue(answer.resposta().contains(formats));
        for (String bad : List.of("O aluno é responsável", "o aluno deve acessar", "enviada pelo aluno", THINKING_SENTINEL))
            assertFalse(answer.resposta().contains(bad), "Trecho não comprovado não pode chegar ao aluno: " + bad);
        assertTrue(answer.resposta().contains("confirmar"), "A atribuição ausente deve permanecer pendente de confirmação");
        assertEquals(List.of(new RagService.Source(document, page, realSource)), answer.fontes());
        JsonNode repairedInput = MAPPER.readTree(requests.get(3).path("messages").get(1).path("content").asText());
        assertEquals(3, repairedInput.path("units").size());
        for (JsonNode unit : repairedInput.path("units")) {
            String extracted = AnswerEvidence.CITATION.matcher(unit.path("text").asText()).replaceAll("").trim();
            assertTrue(AnswerEvidence.normalize(realSource).contains(extracted), "Reparo precisa ser extrativo, sem reescrita");
        }
        assertEquals(debug, logs.contains(DIAGNOSTIC), "Diagnóstico de atribuição só aparece com debug_rag=true");
        var proof = new LinkedHashMap<String, Object>();
        proof.put("id", id);
        proof.put("origin", origin);
        proof.put("debug_rag", debug);
        proof.put("csv_chunk_ids", chunks.stream().map(KnowledgeChunk::id).toList());
        proof.put("initial_answer", initialAnswer);
        proof.put("first_model_audit", firstVerdict.get());
        proof.put("first_java_rejection_issues", repairInput.path("issues"));
        proof.put("http_calls", requests.size());
        proof.put("think", requests.stream().map(request -> request.path("think").asBoolean()).toList());
        proof.put("repair_executed", true);
        proof.put("final_answer", answer.resposta());
        proof.put("preserved", List.of("channel", "form_data", "PDF", "JPG", "JPEG", "PNG"));
        proof.put("diagnostic_present", logs.contains(DIAGNOSTIC));
        proof.put("logs", logs);
        return proof;
    }
}
