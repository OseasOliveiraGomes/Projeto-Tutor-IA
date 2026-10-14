package br.com.sylph.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class PdfMetadataTest {
    @Test void bothCsvBasesMatchPhysicalPdfPages() throws Exception {
        var pages = new PdfPages().extract(Path.of("dados", "Manual Estagio Obrigatorio UNOPAR 2026-2.pdf"));
        Map<Integer, String> text = new HashMap<>();
        pages.forEach(p -> text.put(p.number(), normalize(p.text())));
        for (String method : List.of("recursivo", "semantico")) {
            var chunks = new CsvKnowledgeStore(new ObjectMapper()).read(method);
            for (var c : chunks) {
                assertTrue(text.containsKey(c.pagina()), c.id());
                assertTrue(text.get(c.pagina()).contains(normalize(c.texto())), method + ": " + c.id());
            }
            System.out.println("[AUDIT] " + method + " chunks=" + chunks.size() + " PDF pages=" + pages.size());
        }
        for (int page : List.of(15,16,17)) System.out.println("[AUDIT] page=" + page + " " + text.get(page));
    }
    @Test void realBasesExpandFromSeventeenToBothDefinitions() throws Exception {
        var store = new CsvKnowledgeStore(new ObjectMapper());
        for (String method : List.of("recursivo", "semantico")) {
            var chunks = store.read(method);
            var ranked = chunks.stream().map(c -> new br.com.sylph.model.RankedChunk(c, 0, 0, 0)).toList();
            var seed = ranked.stream().filter(c -> c.chunk().pagina() == 17).findFirst().orElseThrow();
            var expanded = HybridRetriever.expandNearby(List.of(seed), ranked);
            for (int page : List.of(15,16)) assertTrue(expanded.stream().anyMatch(c -> c.chunk().pagina() == page));
        }
    }
    static String normalize(String s) { return s.replaceAll("\\s+", " ").trim(); }
}
